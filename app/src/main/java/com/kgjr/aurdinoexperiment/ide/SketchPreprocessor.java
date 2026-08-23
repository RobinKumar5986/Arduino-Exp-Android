package com.kgjr.aurdinoexperiment.ide;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns a .ino sketch into a compilable .cpp, the way the Arduino build system
 * does it:
 *
 *   1. prepend #include <Arduino.h>
 *   2. synthesise forward declarations for the sketch's own functions, so you
 *      can call a helper that is defined further down the file
 *   3. emit #line directives so compiler errors point at the sketch's real
 *      line numbers rather than the generated file's
 *
 * The prototype scanner is deliberately conservative: it only picks up
 * top-level definitions whose signature it can read with confidence, and
 * anything it skips just means you have to declare that one function
 * yourself - the same failure mode the Arduino IDE has.
 */
public final class SketchPreprocessor {

    public static final class Output {
        public final String cpp;
        public final List<String> prototypes;

        Output(String cpp, List<String> prototypes) {
            this.cpp = cpp;
            this.prototypes = prototypes;
        }
    }

    // A top-level function definition: <return type> <name>(<args>) [const] {
    private static final Pattern FUNCTION_DEF = Pattern.compile(
            "^\\s*"
          + "((?:(?:static|inline|virtual|extern\\s+\"C\"|extern|const|constexpr|unsigned|signed|volatile)\\s+)*)"
          + "([A-Za-z_][\\w:<>,\\s\\*&\\[\\]]*?)"   // return type
          + "\\s+([A-Za-z_]\\w*)"                    // function name
          + "\\s*\\(([^;{}()]*)\\)"                  // arg list (no nested parens)
          + "\\s*(const)?\\s*\\{");                  // opening brace

    private static final java.util.Set<String> KEYWORDS = new java.util.HashSet<>(
            java.util.Arrays.asList("if", "for", "while", "switch", "catch", "do",
                    "else", "return", "case", "sizeof", "class", "struct", "union",
                    "enum", "namespace", "template", "typedef", "operator"));

    public static Output process(String sketchSource, String sketchFileName) {
        String[] lines = sketchSource.split("\n", -1);
        String scannable = blankOutCommentsAndStrings(sketchSource);
        String[] scanLines = scannable.split("\n", -1);

        List<String> prototypes = new ArrayList<>();
        int firstDefLine = -1;
        int depth = 0;

        for (int i = 0; i < scanLines.length; i++) {
            String line = scanLines[i];

            if (depth == 0) {
                Matcher m = FUNCTION_DEF.matcher(line);
                if (m.find()) {
                    String qualifiers = m.group(1) == null ? "" : m.group(1).trim();
                    String returnType = m.group(2).trim();
                    String name = m.group(3).trim();
                    String args = m.group(4).trim();
                    boolean isConst = m.group(5) != null;

                    if (!KEYWORDS.contains(name) && !KEYWORDS.contains(lastWord(returnType))
                            && !returnType.isEmpty()
                            // setup/loop are already declared by Arduino.h
                            && !name.equals("setup") && !name.equals("loop")
                            && !name.equals("main")) {
                        StringBuilder proto = new StringBuilder();
                        if (!qualifiers.isEmpty() && !qualifiers.contains("extern")) {
                            proto.append(qualifiers).append(' ');
                        }
                        proto.append(returnType).append(' ').append(name)
                             .append('(').append(args).append(')');
                        if (isConst) proto.append(" const");
                        proto.append(';');
                        prototypes.add(proto.toString());
                    }
                    if (firstDefLine < 0) firstDefLine = i;
                }
            }

            depth += countChar(line, '{') - countChar(line, '}');
            if (depth < 0) depth = 0;
        }

        if (firstDefLine < 0) firstDefLine = 0;

        StringBuilder out = new StringBuilder(sketchSource.length() + 512);
        if (!sketchSource.contains("#include <Arduino.h>")
                && !sketchSource.contains("#include \"Arduino.h\"")) {
            out.append("#include <Arduino.h>\n");
        }

        // Everything above the first function definition, verbatim.
        out.append("#line 1 \"").append(sketchFileName).append("\"\n");
        for (int i = 0; i < firstDefLine; i++) {
            out.append(lines[i]).append('\n');
        }

        // Forward declarations.
        if (!prototypes.isEmpty()) {
            out.append("\n/* --- generated forward declarations --- */\n");
            for (String p : prototypes) out.append(p).append('\n');
            out.append("/* --- end generated --- */\n");
        }

        // The rest of the sketch, with line numbering restored.
        out.append("#line ").append(firstDefLine + 1)
           .append(" \"").append(sketchFileName).append("\"\n");
        for (int i = firstDefLine; i < lines.length; i++) {
            out.append(lines[i]);
            if (i < lines.length - 1) out.append('\n');
        }
        out.append('\n');

        return new Output(out.toString(), prototypes);
    }

    /**
     * Replaces comment and string-literal content with spaces so the scanner
     * never mistakes text inside them for code. Newlines are preserved so line
     * numbers stay aligned with the original.
     */
    static String blankOutCommentsAndStrings(String src) {
        char[] c = src.toCharArray();
        char[] o = new char[c.length];
        int state = 0; // 0 code, 1 line comment, 2 block comment, 3 string, 4 char
        for (int i = 0; i < c.length; i++) {
            char ch = c[i];
            char next = (i + 1 < c.length) ? c[i + 1] : '\0';
            switch (state) {
                case 0:
                    if (ch == '/' && next == '/') { state = 1; o[i] = ' '; }
                    else if (ch == '/' && next == '*') { state = 2; o[i] = ' '; }
                    else if (ch == '"') { state = 3; o[i] = ' '; }
                    else if (ch == '\'') { state = 4; o[i] = ' '; }
                    else o[i] = ch;
                    break;
                case 1:
                    if (ch == '\n') { state = 0; o[i] = '\n'; } else o[i] = ' ';
                    break;
                case 2:
                    if (ch == '*' && next == '/') { state = 0; o[i] = ' '; o[++i] = ' '; }
                    else o[i] = (ch == '\n') ? '\n' : ' ';
                    break;
                case 3:
                    if (ch == '\\') { o[i] = ' '; if (i + 1 < c.length) o[++i] = ' '; }
                    else if (ch == '"') { state = 0; o[i] = ' '; }
                    else o[i] = (ch == '\n') ? '\n' : ' ';
                    break;
                case 4:
                    if (ch == '\\') { o[i] = ' '; if (i + 1 < c.length) o[++i] = ' '; }
                    else if (ch == '\'') { state = 0; o[i] = ' '; }
                    else o[i] = (ch == '\n') ? '\n' : ' ';
                    break;
            }
        }
        return new String(o);
    }

    private static int countChar(String s, char target) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) if (s.charAt(i) == target) n++;
        return n;
    }

    private static String lastWord(String s) {
        String[] parts = s.trim().split("[^\\w:]+");
        return parts.length == 0 ? "" : parts[parts.length - 1];
    }

    private SketchPreprocessor() {}
}

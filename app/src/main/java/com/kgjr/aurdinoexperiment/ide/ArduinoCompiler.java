package com.kgjr.aurdinoexperiment.ide;

import android.content.Context;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;

/**
 * Compiles an Arduino sketch to Intel HEX entirely on the phone.
 *
 * We drive each toolchain stage by hand instead of going through the avr-gcc
 * driver, because the driver would try to exec helpers by their real names
 * (cc1plus, as, ld) and our binaries are renamed to lib*.so so Android will
 * let us execute them at all.
 *
 * The four command lines below were lifted from `avr-g++ -v` on a desktop
 * build of the same sketch and verified to produce a byte-identical .hex:
 *
 *   1. cc1plus   sketch.cpp -> sketch.s
 *   2. as        sketch.s   -> sketch.o
 *   3. ld        sketch.o + core.a + libc/libm/libgcc -> sketch.elf
 *   4. objcopy   sketch.elf -> sketch.hex
 *
 * The Arduino core is *not* recompiled here: core.a ships prebuilt in the
 * SDK zip, which is what keeps an on-phone build down to a couple of seconds
 * instead of a couple of minutes.
 */
public final class ArduinoCompiler {

    private static final Charset UTF8 = Charset.forName("UTF-8");

    private final Context ctx;
    private final AvrSdk sdk;
    private final Toolchain tools;

    public ArduinoCompiler(Context ctx) {
        this.ctx = ctx.getApplicationContext();
        this.sdk = new AvrSdk(this.ctx);
        this.tools = new Toolchain(this.ctx);
    }

    public AvrSdk sdk()       { return sdk; }
    public Toolchain tools()  { return tools; }

    public interface Log {
        void line(String s);
    }

    public BuildResult build(String sketchSource, Board board, Log log) {
        StringBuilder full = new StringBuilder();
        Log tee = s -> {
            full.append(s).append('\n');
            if (log != null) log.line(s);
        };

        long t0 = System.currentTimeMillis();
        try {
            sdk.installIfNeeded(tee::line);

            if (!tools.isComplete()) {
                tee.line(tools.missingReport());
                return BuildResult.failure(full.toString(), "AVR toolchain missing");
            }

            if (!sdk.ldScript(board).isFile()) {
                tee.line("Missing linker script: " + sdk.ldScript(board).getName());
                tee.line("");
                tee.line("assets/avr-sdk.zip predates the linker-script fix. Re-run the");
                tee.line("toolchain build and copy the avr-sdk.zip it emits into");
                tee.line("app/src/main/assets/, then rebuild the app.");
                return BuildResult.failure(full.toString(), "AVR SDK is out of date");
            }

            File buildDir = new File(ctx.getCacheDir(), "build");
            File tmpDir = new File(ctx.getCacheDir(), "tmp");
            wipe(buildDir);
            buildDir.mkdirs();
            tmpDir.mkdirs();

            // ---- stage 0: .ino -> .cpp -------------------------------------
            SketchPreprocessor.Output pre =
                    SketchPreprocessor.process(sketchSource, "sketch.ino");
            File cpp = new File(buildDir, "sketch.cpp");
            writeText(cpp, pre.cpp);
            if (!pre.prototypes.isEmpty()) {
                tee.line("Generated " + pre.prototypes.size() + " forward declaration(s).");
            }

            ProcessRunner runner = new ProcessRunner(buildDir, tmpDir);

            File asm = new File(buildDir, "sketch.s");
            File obj = new File(buildDir, "sketch.o");
            File elf = new File(buildDir, "sketch.elf");
            File hex = new File(buildDir, "sketch.hex");

            // ---- stage 1: cc1plus ------------------------------------------
            tee.line("Compiling sketch...");
            ProcessRunner.Result r = runner.run(180, cc1plusCommand(board, cpp, asm));
            if (!r.ok()) {
                tee.line(cleanCompilerOutput(r.output));
                return BuildResult.failure(full.toString(), "Compilation failed");
            }
            if (!r.output.trim().isEmpty()) tee.line(cleanCompilerOutput(r.output));

            // ---- stage 2: as -----------------------------------------------
            r = runner.run(60, ProcessRunner.cmd(
                    tools.path(Toolchain.AS),
                    "-mmcu=" + board.gccArch,
                    "-mno-skip-bug",
                    "-o", obj.getAbsolutePath(),
                    asm.getAbsolutePath()));
            if (!r.ok()) {
                tee.line(r.output);
                return BuildResult.failure(full.toString(), "Assembler failed");
            }

            // ---- stage 3: ld -----------------------------------------------
            tee.line("Linking...");
            r = runner.run(120, linkCommand(board, obj, elf));
            if (!r.ok()) {
                tee.line(explainLinkErrors(r.output));
                return BuildResult.failure(full.toString(), "Link failed");
            }
            if (!r.output.trim().isEmpty()) tee.line(r.output);

            // ---- stage 4: objcopy ------------------------------------------
            r = runner.run(60, ProcessRunner.cmd(
                    tools.path(Toolchain.OBJCOPY),
                    "-O", "ihex",
                    "-R", ".eeprom",
                    elf.getAbsolutePath(),
                    hex.getAbsolutePath()));
            if (!r.ok()) {
                tee.line(r.output);
                return BuildResult.failure(full.toString(), "objcopy failed");
            }

            // ---- size report -----------------------------------------------
            int flashUsed = IntelHexSize.programBytes(hex);
            long ms = System.currentTimeMillis() - t0;
            tee.line(String.format(
                    "Sketch uses %d bytes (%.1f%%) of program storage space. Maximum is %d bytes.",
                    flashUsed, 100.0 * flashUsed / board.flashBytes, board.flashBytes));
            tee.line("Done in " + ms + " ms.");

            if (flashUsed > board.flashBytes) {
                return BuildResult.failure(full.toString(),
                        "Sketch too big for " + board.displayName);
            }
            return BuildResult.success(full.toString(), hex, flashUsed);

        } catch (IOException | InterruptedException e) {
            tee.line("Build aborted: " + e);
            return BuildResult.failure(full.toString(), String.valueOf(e.getMessage()));
        }
    }

    // -----------------------------------------------------------------------

    private List<String> cc1plusCommand(Board board, File cpp, File asm) {
        List<String> c = new ArrayList<>();
        c.add(tools.path(Toolchain.CC1PLUS));
        c.add("-quiet");

        // user include paths
        c.add("-I"); c.add(sdk.arduinoInclude().getAbsolutePath());
        c.add("-I"); c.add(sdk.variantInclude().getAbsolutePath());

        // system include paths, in GCC's own search order
        c.add("-isystem"); c.add(sdk.gccInclude().getAbsolutePath());
        c.add("-isystem"); c.add(sdk.gccFixedInclude().getAbsolutePath());
        c.add("-isystem"); c.add(sdk.avrInclude().getAbsolutePath());

        // device + Arduino defines that the driver would normally inject
        c.add("-D"); c.add(board.mcuMacro());
        c.add("-D"); c.add("__AVR_DEVICE_NAME__=" + board.mcu);
        c.add("-D"); c.add("F_CPU=" + board.fCpu + "L");
        c.add("-D"); c.add("ARDUINO=" + board.arduinoVersion);
        c.add("-D"); c.add(board.boardDefine);
        c.add("-D"); c.add(board.archDefine);

        c.add(cpp.getAbsolutePath());

        c.add("-mn-flash=1");
        c.add("-mno-skip-bug");
        c.add("-mmcu=" + board.gccArch);

        // the standard Arduino C++ flag set
        c.add("-g");
        c.add("-Os");
        c.add("-w");
        c.add("-std=gnu++11");
        c.add("-fpermissive");
        c.add("-fno-exceptions");
        c.add("-ffunction-sections");
        c.add("-fdata-sections");
        c.add("-fno-threadsafe-statics");
        c.add("-fno-rtti");
        c.add("-fno-enforce-eh-specs");

        c.add("-o"); c.add(asm.getAbsolutePath());
        return c;
    }

    private List<String> linkCommand(Board board, File obj, File elf) {
        List<String> c = new ArrayList<>();
        c.add(tools.path(Toolchain.LD));
        c.add("-m" + board.gccArch);
        // ld resolves ldscripts/ relative to its own install location, which is
        // meaningless when it runs as libavr_ld.so out of nativeLibraryDir.
        // Naming the script outright produces a byte-identical link.
        c.add("-T"); c.add(sdk.ldScript(board).getAbsolutePath());
        c.add("-Tdata"); c.add(board.dataSection);
        c.add("-o"); c.add(elf.getAbsolutePath());
        c.add(sdk.crt(board).getAbsolutePath());
        c.add("-L" + sdk.libGccAvr5().getAbsolutePath());
        c.add("-L" + sdk.libAvr5().getAbsolutePath());
        c.add("--gc-sections");
        c.add(obj.getAbsolutePath());
        c.add(sdk.coreArchive().getAbsolutePath());
        c.add("-lm");
        c.add("--start-group");
        c.add("-lgcc");
        c.add("-lm");
        c.add("-lc");
        c.add("-l" + board.deviceLib);
        c.add("--end-group");
        return c;
    }

    /** Strip the absolute build path so errors read like "sketch.ino:12:5: error: ...". */
    private String cleanCompilerOutput(String raw) {
        return raw.replace(ctx.getCacheDir().getAbsolutePath() + "/build/", "")
                .replace(sdk.root().getAbsolutePath() + "/", "");
    }

    private String explainLinkErrors(String raw) {
        String cleaned = cleanCompilerOutput(raw);
        if (cleaned.contains("undefined reference")) {
            cleaned += "\n\nHint: an undefined reference usually means the sketch uses a\n"
                    + "library (SPI, Wire, Servo, ...) that is not bundled yet. Only the\n"
                    + "Arduino core is compiled into core.a in this build.";
        }
        return cleaned;
    }

    private static void writeText(File f, String text) throws IOException {
        try (java.io.OutputStream os = new java.io.FileOutputStream(f)) {
            os.write(text.getBytes(UTF8));
        }
    }

    private static void wipe(File dir) {
        File[] kids = dir.listFiles();
        if (kids != null) for (File k : kids) {
            if (k.isDirectory()) wipe(k);
            //noinspection ResultOfMethodCallIgnored
            k.delete();
        }
    }
}
package com.kgjr.aurdinoexperiment.ide;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Runs one of the bundled native toolchain executables and captures its output.
 *
 * Everything we exec lives in the APK's nativeLibraryDir, which is the only
 * place an app targeting Android 10+ is allowed to execute from (W^X).
 */
public final class ProcessRunner {

    public static final class Result {
        public final int exitCode;
        public final String output;   // stdout + stderr, interleaved
        public final long millis;
        public final String commandLine;

        Result(int exitCode, String output, long millis, String commandLine) {
            this.exitCode = exitCode;
            this.output = output;
            this.millis = millis;
            this.commandLine = commandLine;
        }

        public boolean ok() {
            return exitCode == 0;
        }
    }

    private final File workingDir;
    private final File tmpDir;

    public ProcessRunner(File workingDir, File tmpDir) {
        this.workingDir = workingDir;
        this.tmpDir = tmpDir;
    }

    public Result run(long timeoutSeconds, List<String> command) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(workingDir);
        pb.redirectErrorStream(true);

        Map<String, String> env = pb.environment();
        // Give GCC a writable scratch dir. Without this cc1plus can fail on
        // devices where the default TMPDIR is not writable by the app.
        env.put("TMPDIR", tmpDir.getAbsolutePath());
        env.put("TEMP", tmpDir.getAbsolutePath());
        env.put("HOME", workingDir.getAbsolutePath());
        env.put("LC_ALL", "C");

        long start = System.currentTimeMillis();
        Process p = pb.start();

        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        Thread pump = new Thread(() -> {
            try (InputStream in = p.getInputStream()) {
                byte[] chunk = new byte[8192];
                int n;
                while ((n = in.read(chunk)) > 0) {
                    synchronized (buf) {
                        buf.write(chunk, 0, n);
                    }
                }
            } catch (IOException ignored) {
                // process died; whatever we collected is what we report
            }
        });
        pump.setDaemon(true);
        pump.start();

        boolean finished = waitFor(p, timeoutSeconds * 1000L);
        if (!finished) {
            p.destroy();
            pump.join(1000);
            return new Result(-1,
                    describe(command) + "\n\n*** timed out after " + timeoutSeconds + "s ***\n" + snapshot(buf),
                    System.currentTimeMillis() - start, describe(command));
        }
        pump.join(2000);

        return new Result(p.exitValue(), snapshot(buf),
                System.currentTimeMillis() - start, describe(command));
    }

    /**
     * Poll-based wait with a deadline.
     *
     * Process.waitFor(long, TimeUnit) and destroyForcibly() both landed in
     * API 26, and there is no reason to force minSdk that high just for a
     * timeout. Polling exitValue() works on every API level.
     *
     * @return true if the process exited before the deadline
     */
    private static boolean waitFor(Process p, long timeoutMillis) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        long sleep = 5;
        while (true) {
            try {
                p.exitValue();
                return true;
            } catch (IllegalThreadStateException stillRunning) {
                if (System.currentTimeMillis() >= deadline) return false;
                Thread.sleep(sleep);
                // Back off so a long link does not spin the CPU, but stay
                // responsive for the short stages.
                if (sleep < 100) sleep *= 2;
            }
        }
    }

    private static String snapshot(ByteArrayOutputStream buf) {
        synchronized (buf) {
            return buf.toString();
        }
    }

    private static String describe(List<String> command) {
        StringBuilder sb = new StringBuilder();
        for (String c : command) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(c.indexOf(' ') >= 0 ? "\"" + c + "\"" : c);
        }
        return sb.toString();
    }

    /** Small helper so call sites read like a shell command. */
    public static List<String> cmd(String... parts) {
        List<String> list = new ArrayList<>(parts.length);
        for (String p : parts) list.add(p);
        return list;
    }

    public static Map<String, String> emptyEnv() {
        return new HashMap<>();
    }
}
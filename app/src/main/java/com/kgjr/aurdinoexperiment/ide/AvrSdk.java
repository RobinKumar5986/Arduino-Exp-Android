package com.kgjr.aurdinoexperiment.ide;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Unpacks assets/avr-sdk.zip into filesDir on first launch and hands out the
 * include/library paths the compiler needs.
 *
 * These are *data* files (headers, .a archives, crt objects) - never executed -
 * so filesDir is fine for them. Only the toolchain executables have to live in
 * nativeLibraryDir; see Toolchain.
 *
 * Layout inside the zip:
 *   VERSION
 *   include/arduino/     Arduino core headers (Arduino.h, HardwareSerial.h, ...)
 *   include/variant/     variants/standard (pins_arduino.h)
 *   include/gcc/         GCC's own headers (stddef.h, stdint.h, ...)
 *   include/gcc-fixed/   GCC include-fixed
 *   include/avr/         avr-libc headers (avr/io.h, util/delay.h, ...)
 *   lib/core.a           precompiled Arduino core for Uno @ 16 MHz
 *   lib/avr5/            crtatmega328p.o, libc.a, libm.a, libatmega328p.a
 *   lib/gcc-avr5/        libgcc.a
 *   lib/ldscripts/       avr5.x* - ld cannot locate these itself on Android
 *   hex/blink.hex        prebuilt example, lets you flash before the
 *                        toolchain binaries are in place
 */
public final class AvrSdk {

    public static final String ASSET_NAME = "avr-sdk.zip";
    private static final String PREF = "avr_sdk";
    private static final String KEY_VERSION = "installed_version";

    private final Context ctx;
    private final File root;

    public AvrSdk(Context ctx) {
        this.ctx = ctx.getApplicationContext();
        this.root = new File(this.ctx.getFilesDir(), "avr-sdk");
    }

    public File root()            { return root; }
    public File arduinoInclude()  { return new File(root, "include/arduino"); }
    public File variantInclude()  { return new File(root, "include/variant"); }
    public File gccInclude()      { return new File(root, "include/gcc"); }
    public File gccFixedInclude() { return new File(root, "include/gcc-fixed"); }
    public File avrInclude()      { return new File(root, "include/avr"); }
    public File coreArchive()     { return new File(root, "lib/core.a"); }
    public File libAvr5()         { return new File(root, "lib/avr5"); }
    public File libGccAvr5()      { return new File(root, "lib/gcc-avr5"); }
    public File crt(Board b)      { return new File(libAvr5(), b.crtObject); }
    public File ldScripts()       { return new File(root, "lib/ldscripts"); }
    public File ldScript(Board b) { return new File(ldScripts(), b.linkerScript()); }
    public File exampleBlinkHex() { return new File(root, "hex/blink.hex"); }

    /**
     * Is a COMPLETE SDK already unpacked?
     *
     * This deliberately probes one file per top-level piece rather than
     * trusting the VERSION string. A zip whose contents changed while its
     * version stayed the same would otherwise never be re-extracted, and the
     * failure surfaces much later as a missing header or linker script.
     */
    public boolean isInstalled() {
        return coreArchive().isFile()
                && new File(arduinoInclude(), "Arduino.h").isFile()
                && new File(variantInclude(), "pins_arduino.h").isFile()
                && new File(avrInclude(), "avr/io.h").isFile()
                && new File(gccInclude(), "stddef.h").isFile()
                && libAvr5().isDirectory()
                && libGccAvr5().isDirectory()
                && hasFiles(ldScripts());
    }

    private static boolean hasFiles(File dir) {
        String[] names = dir.list();
        return names != null && names.length > 0;
    }

    /**
     * Extract the bundled SDK if it is missing or out of date.
     * Cheap no-op on every launch after the first.
     */
    public synchronized void installIfNeeded(Listener listener) throws IOException {
        String bundled = readBundledVersion();
        SharedPreferences prefs = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        String installed = prefs.getString(KEY_VERSION, null);

        if (bundled.equals(installed) && isInstalled()) return;

        if (listener != null) listener.onProgress("Unpacking AVR SDK (first run)...");
        deleteRecursively(root);
        if (!root.mkdirs() && !root.isDirectory()) {
            throw new IOException("cannot create " + root);
        }

        int count = 0;
        try (InputStream raw = ctx.getAssets().open(ASSET_NAME);
             ZipInputStream zin = new ZipInputStream(raw)) {
            ZipEntry e;
            byte[] chunk = new byte[16384];
            String canonicalRoot = root.getCanonicalPath() + File.separator;
            while ((e = zin.getNextEntry()) != null) {
                File out = new File(root, e.getName());
                // Zip-slip guard.
                if (!out.getCanonicalPath().startsWith(canonicalRoot)) {
                    throw new IOException("blocked zip entry outside root: " + e.getName());
                }
                if (e.isDirectory()) {
                    out.mkdirs();
                } else {
                    File parent = out.getParentFile();
                    if (parent != null) parent.mkdirs();
                    try (OutputStream os = new FileOutputStream(out)) {
                        int n;
                        while ((n = zin.read(chunk)) > 0) os.write(chunk, 0, n);
                    }
                    count++;
                    if (listener != null && count % 50 == 0) {
                        listener.onProgress("  " + count + " files...");
                    }
                }
                zin.closeEntry();
            }
        }

        prefs.edit().putString(KEY_VERSION, bundled).apply();
        if (listener != null) listener.onProgress("AVR SDK ready (" + count + " files).");
    }

    private String readBundledVersion() {
        try (InputStream raw = ctx.getAssets().open(ASSET_NAME);
             ZipInputStream zin = new ZipInputStream(raw)) {
            ZipEntry e;
            while ((e = zin.getNextEntry()) != null) {
                if ("VERSION".equals(e.getName())) {
                    java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
                    byte[] chunk = new byte[256];
                    int n;
                    while ((n = zin.read(chunk)) > 0) bos.write(chunk, 0, n);
                    return bos.toString().trim();
                }
            }
        } catch (IOException ignored) {
            // fall through
        }
        return "unknown";
    }

    private static void deleteRecursively(File f) {
        if (f == null || !f.exists()) return;
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteRecursively(k);
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }

    public interface Listener {
        void onProgress(String line);
    }
}
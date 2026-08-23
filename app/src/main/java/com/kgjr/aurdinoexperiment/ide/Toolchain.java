package com.kgjr.aurdinoexperiment.ide;

import android.content.Context;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Locates the AVR toolchain executables that ship inside the APK.
 *
 * IMPORTANT (Android 10 / API 29+):
 *   An app may only execve() files inside its nativeLibraryDir. Files written
 *   into filesDir/cacheDir are mounted noexec and will fail with EACCES.
 *   The packaging trick is therefore:
 *
 *     app/src/main/jniLibs/arm64-v8a/libavr_cc1plus.so   <- the real cc1plus ELF
 *     app/src/main/jniLibs/arm64-v8a/libavr_as.so        <- GNU as
 *     app/src/main/jniLibs/arm64-v8a/libavr_ld.so        <- GNU ld
 *     app/src/main/jniLibs/arm64-v8a/libavr_objcopy.so   <- objcopy
 *     app/src/main/jniLibs/arm64-v8a/libavr_size.so      <- size (optional)
 *
 *   They are ordinary aarch64 executables that merely *end in* .so, because
 *   that is the only filename pattern the installer extracts into
 *   nativeLibraryDir with the exec bit set.
 *
 *   Requires in AndroidManifest:  android:extractNativeLibs="true"
 *   and in build.gradle:          jniLibs.useLegacyPackaging = true
 *
 * Because the binaries are renamed, we never invoke the `avr-gcc` driver
 * (it would go looking for a file literally called "cc1plus"). Instead
 * ArduinoCompiler drives each stage explicitly. Those exact command lines
 * were derived from `avr-g++ -v` and verified to produce a byte-identical
 * .hex to a desktop Arduino IDE build.
 */
public final class Toolchain {

    public static final String CC1PLUS = "libavr_cc1plus.so";
    public static final String CC1     = "libavr_cc1.so";      // optional, for .c files
    public static final String AS      = "libavr_as.so";
    public static final String LD      = "libavr_ld.so";
    public static final String OBJCOPY = "libavr_objcopy.so";
    public static final String SIZE    = "libavr_size.so";     // optional

    private final File libDir;

    public Toolchain(Context ctx) {
        this.libDir = new File(ctx.getApplicationInfo().nativeLibraryDir);
    }

    public File dir() {
        return libDir;
    }

    public File binary(String name) {
        return new File(libDir, name);
    }

    public String path(String name) {
        return binary(name).getAbsolutePath();
    }

    public boolean has(String name) {
        File f = binary(name);
        return f.isFile() && f.canExecute();
    }

    /** True when every executable required for a C++ sketch build is present. */
    public boolean isComplete() {
        return has(CC1PLUS) && has(AS) && has(LD) && has(OBJCOPY);
    }

    /** Human-readable list of what is missing, for the build log. */
    public String missingReport() {
        List<String> missing = new ArrayList<>();
        for (String n : new String[]{CC1PLUS, AS, LD, OBJCOPY}) {
            if (!has(n)) missing.add(n);
        }
        if (missing.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        sb.append("AVR toolchain not installed in this build.\n");
        sb.append("Missing from ").append(libDir.getAbsolutePath()).append(":\n");
        for (String m : missing) sb.append("  - ").append(m).append('\n');
        sb.append("\nBuild them with toolchain/build-avr-android.sh and drop the\n");
        sb.append("resulting files into app/src/main/jniLibs/arm64-v8a/.\n");
        return sb.toString();
    }
}

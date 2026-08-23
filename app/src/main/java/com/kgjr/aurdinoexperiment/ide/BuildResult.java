package com.kgjr.aurdinoexperiment.ide;

import java.io.File;

public final class BuildResult {
    public final boolean ok;
    public final String log;
    public final String error;      // null when ok
    public final File hexFile;      // null when !ok
    public final int programBytes;

    private BuildResult(boolean ok, String log, String error, File hexFile, int programBytes) {
        this.ok = ok;
        this.log = log;
        this.error = error;
        this.hexFile = hexFile;
        this.programBytes = programBytes;
    }

    public static BuildResult success(String log, File hex, int programBytes) {
        return new BuildResult(true, log, null, hex, programBytes);
    }

    public static BuildResult failure(String log, String error) {
        return new BuildResult(false, log, error, null, 0);
    }
}

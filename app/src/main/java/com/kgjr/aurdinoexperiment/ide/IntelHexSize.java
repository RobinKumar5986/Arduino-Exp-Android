package com.kgjr.aurdinoexperiment.ide;

import com.kgjr.aurdinoexperiment.flash.IntelHex;

import java.io.File;
import java.io.IOException;

/** Byte count of a built image, for the "Sketch uses N bytes" line. */
final class IntelHexSize {
    static int programBytes(File hex) throws IOException {
        return IntelHex.parse(hex).size();
    }

    private IntelHexSize() {}
}

package com.kgjr.aurdinoexperiment.flash;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;

/**
 * Minimal Intel HEX reader - enough for AVR flash images.
 *
 * Record types handled:
 *   00 data
 *   01 end of file
 *   02 extended segment address
 *   04 extended linear address
 * Anything else is ignored (03/05 are start-address records and mean nothing
 * to an AVR bootloader).
 */
public final class IntelHex {

    public final byte[] data;      // flash image, 0xFF-filled in the gaps
    public final int highestAddress;

    private IntelHex(byte[] data, int highestAddress) {
        this.data = data;
        this.highestAddress = highestAddress;
    }

    public int size() {
        return highestAddress;
    }

    public static IntelHex parse(File f) throws IOException {
        try (Reader r = new FileReader(f)) {
            return parse(r);
        }
    }

    public static IntelHex parse(String text) throws IOException {
        return parse(new StringReader(text));
    }

    public static IntelHex parse(Reader reader) throws IOException {
        // 256 KB ceiling is far above any AVR part we would target.
        byte[] image = new byte[256 * 1024];
        java.util.Arrays.fill(image, (byte) 0xFF);
        int highest = 0;
        int baseAddress = 0;
        boolean sawEof = false;
        int lineNo = 0;

        try (BufferedReader br = new BufferedReader(reader)) {
            String line;
            while ((line = br.readLine()) != null) {
                lineNo++;
                line = line.trim();
                if (line.isEmpty()) continue;
                if (line.charAt(0) != ':') {
                    throw new IOException("line " + lineNo + ": record does not start with ':'");
                }
                if (line.length() < 11 || (line.length() % 2) == 0) {
                    throw new IOException("line " + lineNo + ": malformed record length");
                }

                int byteCount = hex8(line, 1);
                int address = (hex8(line, 3) << 8) | hex8(line, 5);
                int recordType = hex8(line, 7);

                int expected = 11 + byteCount * 2;
                if (line.length() != expected) {
                    throw new IOException("line " + lineNo + ": expected " + expected
                            + " chars, got " + line.length());
                }

                int sum = byteCount + (address >> 8) + (address & 0xFF) + recordType;
                byte[] payload = new byte[byteCount];
                for (int i = 0; i < byteCount; i++) {
                    payload[i] = (byte) hex8(line, 9 + i * 2);
                    sum += payload[i] & 0xFF;
                }
                int checksum = hex8(line, 9 + byteCount * 2);
                if (((sum + checksum) & 0xFF) != 0) {
                    throw new IOException("line " + lineNo + ": checksum mismatch");
                }

                switch (recordType) {
                    case 0x00: {
                        int target = baseAddress + address;
                        if (target + byteCount > image.length) {
                            throw new IOException("line " + lineNo + ": address 0x"
                                    + Integer.toHexString(target) + " out of range");
                        }
                        System.arraycopy(payload, 0, image, target, byteCount);
                        if (target + byteCount > highest) highest = target + byteCount;
                        break;
                    }
                    case 0x01:
                        sawEof = true;
                        break;
                    case 0x02:
                        baseAddress = (((payload[0] & 0xFF) << 8) | (payload[1] & 0xFF)) << 4;
                        break;
                    case 0x04:
                        baseAddress = (((payload[0] & 0xFF) << 8) | (payload[1] & 0xFF)) << 16;
                        break;
                    default:
                        break; // 03 / 05: start address, irrelevant for AVR
                }
                if (sawEof) break;
            }
        }

        if (!sawEof) throw new IOException("no end-of-file record - hex is truncated");
        if (highest == 0) throw new IOException("hex contains no data");

        byte[] trimmed = new byte[highest];
        System.arraycopy(image, 0, trimmed, 0, highest);
        return new IntelHex(trimmed, highest);
    }

    private static int hex8(String s, int at) throws IOException {
        int hi = Character.digit(s.charAt(at), 16);
        int lo = Character.digit(s.charAt(at + 1), 16);
        if (hi < 0 || lo < 0) throw new IOException("bad hex digits at offset " + at);
        return (hi << 4) | lo;
    }
}

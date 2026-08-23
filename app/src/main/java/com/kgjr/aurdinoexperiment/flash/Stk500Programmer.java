package com.kgjr.aurdinoexperiment.flash;

import java.io.IOException;

/**
 * STK500 v1 host, which is what the Uno's optiboot bootloader speaks.
 * Equivalent to `avrdude -c arduino -p m328p -U flash:w:sketch.hex:i`.
 *
 * Wire protocol: every command is <opcode> [args] 0x20, and the bootloader
 * answers 0x14 <payload> 0x10. If the leading byte is not 0x14 we are out of
 * sync and there is no point continuing.
 *
 * Sequence:
 *   pulse DTR/RTS  -> board resets into the bootloader
 *   GET_SYNC       -> until it answers
 *   READ_SIGN      -> confirm we are talking to the chip we compiled for
 *   ENTER_PROGMODE
 *   for each page: LOAD_ADDRESS (word address!) then PROG_PAGE
 *   optional readback verify
 *   LEAVE_PROGMODE
 */
public final class Stk500Programmer {

    // --- protocol constants (from stk500.h) ---
    private static final byte RESP_STK_OK      = 0x10;
    private static final byte RESP_STK_INSYNC  = 0x14;
    private static final byte SYNC_CRC_EOP     = 0x20;

    private static final byte CMD_STK_GET_SYNC       = 0x30;
    private static final byte CMD_STK_GET_PARAMETER  = 0x41;
    private static final byte CMD_STK_ENTER_PROGMODE = 0x50;
    private static final byte CMD_STK_LEAVE_PROGMODE = 0x51;
    private static final byte CMD_STK_LOAD_ADDRESS   = 0x55;
    private static final byte CMD_STK_PROG_PAGE      = 0x64;
    private static final byte CMD_STK_READ_PAGE      = 0x74;
    private static final byte CMD_STK_READ_SIGN      = 0x75;

    private static final byte PARAM_SW_MAJOR = (byte) 0x81;
    private static final byte PARAM_SW_MINOR = (byte) 0x82;

    private static final int SYNC_ATTEMPTS = 12;
    private static final int IO_TIMEOUT_MS = 1000;

    public interface Progress {
        void onLog(String message);
        /** 0..100 */
        void onProgress(int percent);
    }

    public static final class ProgrammerException extends IOException {
        public ProgrammerException(String message) { super(message); }
    }

    /**
     * USB bulk endpoints deliver whole packets, and usb-serial-for-android
     * requires a destination buffer at least the endpoint's max packet size
     * (64 bytes on a CDC-ACM Uno). Reading one byte at a time silently
     * mangles or drops the reply. So we always read into a big buffer and
     * hand out bytes from it.
     */
    private static final int RX_BUFFER = 512;

    private final SerialLink port;
    private final Progress progress;

    private final byte[] rx = new byte[RX_BUFFER];
    private int rxPos;
    private int rxLen;

    public Stk500Programmer(SerialLink port, Progress progress) {
        this.port = port;
        this.progress = progress != null ? progress : new Progress() {
            public void onLog(String m) {}
            public void onProgress(int p) {}
        };
    }

    /**
     * Flashes an image.
     *
     * @param image       raw flash bytes starting at address 0
     * @param pageSize    SPM page size (128 for ATmega328P)
     * @param baud        115200 for a stock Uno
     * @param signature   expected 3 signature bytes, or null to skip the check
     * @param verify      read the pages back and compare
     */
    public void flash(byte[] image, int pageSize, int baud,
                      byte[] signature, boolean verify) throws IOException {

        port.setBaudRate(baud);
        log("Resetting board...");
        pulseReset();

        log("Syncing with bootloader...");
        sync();

        String version = readBootloaderVersion();
        if (version != null) log("Bootloader STK500 v" + version);

        if (signature != null) {
            byte[] actual = readSignature();
            log(String.format("Device signature: 0x%02X 0x%02X 0x%02X",
                    actual[0] & 0xFF, actual[1] & 0xFF, actual[2] & 0xFF));
            if (!java.util.Arrays.equals(actual, signature)) {
                throw new ProgrammerException(String.format(
                        "Wrong chip. Expected 0x%02X 0x%02X 0x%02X but the board reports "
                                + "0x%02X 0x%02X 0x%02X - the sketch was compiled for a different MCU.",
                        signature[0] & 0xFF, signature[1] & 0xFF, signature[2] & 0xFF,
                        actual[0] & 0xFF, actual[1] & 0xFF, actual[2] & 0xFF));
            }
        }

        command(new byte[]{CMD_STK_ENTER_PROGMODE}, 0);

        int pages = (image.length + pageSize - 1) / pageSize;
        log("Writing " + image.length + " bytes in " + pages + " page(s)...");

        for (int p = 0; p < pages; p++) {
            int offset = p * pageSize;
            int length = Math.min(pageSize, image.length - offset);
            loadAddress(offset);
            programPage(image, offset, length);
            progress.onProgress((int) (100L * (p + 1) / pages));
        }

        if (verify) {
            log("Verifying...");
            for (int p = 0; p < pages; p++) {
                int offset = p * pageSize;
                int length = Math.min(pageSize, image.length - offset);
                loadAddress(offset);
                byte[] back = readPage(length);
                for (int i = 0; i < length; i++) {
                    if (back[i] != image[offset + i]) {
                        throw new ProgrammerException(String.format(
                                "Verify failed at 0x%04X: wrote 0x%02X, read back 0x%02X",
                                offset + i, image[offset + i] & 0xFF, back[i] & 0xFF));
                    }
                }
                progress.onProgress((int) (100L * (p + 1) / pages));
            }
            log("Verified " + image.length + " bytes.");
        }

        command(new byte[]{CMD_STK_LEAVE_PROGMODE}, 0);
        log("Upload complete.");
    }

    // ------------------------------------------------------------------
    // protocol primitives
    // ------------------------------------------------------------------

    /**
     * Drives DTR/RTS the way avrdude does: release, wait, then assert.
     * The falling edge on DTR is coupled through a 100 nF cap to RESET,
     * which drops the chip into the bootloader for about a second.
     */
    private void pulseReset() throws IOException {
        port.setDtr(false);
        port.setRts(false);
        sleep(250);
        port.setDtr(true);
        port.setRts(true);
        sleep(50);
        drain();
    }

    private void sync() throws IOException {
        IOException last = null;
        for (int attempt = 1; attempt <= SYNC_ATTEMPTS; attempt++) {
            try {
                drain();
                port.write(new byte[]{CMD_STK_GET_SYNC, SYNC_CRC_EOP}, IO_TIMEOUT_MS);
                byte first = readByte(400);
                if (first == RESP_STK_INSYNC) {
                    byte second = readByte(400);
                    if (second == RESP_STK_OK) return;
                    last = new ProgrammerException(String.format(
                            "got 0x14 but then 0x%02X instead of 0x10", second & 0xFF));
                } else {
                    last = new ProgrammerException(String.format(
                            "unexpected sync response 0x%02X (rest: %s)",
                            first & 0xFF, pendingBytes()));
                }
            } catch (IOException e) {
                last = e;
            }
            // Half way through, try resetting again - some clones need a second nudge.
            if (attempt == SYNC_ATTEMPTS / 2) {
                log("Still no response, resetting again...");
                pulseReset();
            }
            sleep(50);
        }
        throw new ProgrammerException(
                "Could not sync with the bootloader after " + SYNC_ATTEMPTS + " tries.\n"
                        + "Things worth checking:\n"
                        + "  - baud rate (a stock Uno bootloader is 115200)\n"
                        + "  - the serial monitor in this app is closed\n"
                        + "  - DTR is actually reaching the board (some OTG cables do not pass it)\n"
                        + "  - the board is not held in reset\n"
                        + (last != null ? "Last error: " + last.getMessage() : ""));
    }

    private String readBootloaderVersion() {
        try {
            byte[] major = command(new byte[]{CMD_STK_GET_PARAMETER, PARAM_SW_MAJOR}, 1);
            byte[] minor = command(new byte[]{CMD_STK_GET_PARAMETER, PARAM_SW_MINOR}, 1);
            return (major[0] & 0xFF) + "." + (minor[0] & 0xFF);
        } catch (IOException e) {
            return null; // optiboot is allowed not to answer this
        }
    }

    private byte[] readSignature() throws IOException {
        return command(new byte[]{CMD_STK_READ_SIGN}, 3);
    }

    /** STK500 addresses flash in *words*, so the byte offset is halved. */
    private void loadAddress(int byteAddress) throws IOException {
        int word = byteAddress >> 1;
        command(new byte[]{
                CMD_STK_LOAD_ADDRESS,
                (byte) (word & 0xFF),
                (byte) ((word >> 8) & 0xFF)
        }, 0);
    }

    private void programPage(byte[] image, int offset, int length) throws IOException {
        byte[] frame = new byte[4 + length];
        frame[0] = CMD_STK_PROG_PAGE;
        frame[1] = (byte) ((length >> 8) & 0xFF);   // big-endian length
        frame[2] = (byte) (length & 0xFF);
        frame[3] = 'F';                              // 'F' = flash, 'E' = eeprom
        System.arraycopy(image, offset, frame, 4, length);
        command(frame, 0);
    }

    private byte[] readPage(int length) throws IOException {
        return command(new byte[]{
                CMD_STK_READ_PAGE,
                (byte) ((length >> 8) & 0xFF),
                (byte) (length & 0xFF),
                'F'
        }, length);
    }

    /**
     * Sends body + CRC_EOP and reads INSYNC, expectedPayload bytes, then OK.
     */
    private byte[] command(byte[] body, int expectedPayload) throws IOException {
        byte[] frame = new byte[body.length + 1];
        System.arraycopy(body, 0, frame, 0, body.length);
        frame[body.length] = SYNC_CRC_EOP;
        port.write(frame, IO_TIMEOUT_MS);

        byte insync = readByte(IO_TIMEOUT_MS);
        if (insync != RESP_STK_INSYNC) {
            throw new ProgrammerException(String.format(
                    "Lost sync after command 0x%02X (got 0x%02X, expected 0x14)",
                    body[0] & 0xFF, insync & 0xFF));
        }

        byte[] payload = new byte[expectedPayload];
        for (int i = 0; i < expectedPayload; i++) {
            payload[i] = readByte(IO_TIMEOUT_MS);
        }

        byte ok = readByte(IO_TIMEOUT_MS);
        if (ok != RESP_STK_OK) {
            throw new ProgrammerException(String.format(
                    "Command 0x%02X not acknowledged (got 0x%02X, expected 0x10)",
                    body[0] & 0xFF, ok & 0xFF));
        }
        return payload;
    }

    private byte readByte(int timeoutMs) throws IOException {
        if (rxPos < rxLen) return rx[rxPos++];

        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            int n = port.read(rx, (int) Math.max(1, deadline - System.currentTimeMillis()));
            if (n > 0) {
                rxPos = 0;
                rxLen = n;
                return rx[rxPos++];
            }
        }
        throw new ProgrammerException("Timed out waiting for a byte from the bootloader");
    }

    /** Drop both the driver's buffer and anything we have already pulled in. */
    private void drain() {
        rxPos = 0;
        rxLen = 0;
        port.purgeInput();
    }

    /** Bytes still buffered - useful when reporting a sync failure. */
    private String pendingBytes() {
        if (rxPos >= rxLen) return "none";
        StringBuilder sb = new StringBuilder();
        for (int i = rxPos; i < rxLen && i < rxPos + 8; i++) {
            sb.append(String.format("%02X ", rx[i] & 0xFF));
        }
        return sb.toString().trim();
    }

    private void log(String s) {
        progress.onLog(s);
    }

    private static void sleep(long ms) throws IOException {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("upload interrupted");
        }
    }
}
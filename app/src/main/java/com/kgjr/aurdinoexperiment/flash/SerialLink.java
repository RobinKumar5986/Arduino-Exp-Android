package com.kgjr.aurdinoexperiment.flash;

import java.io.IOException;

/**
 * The narrow slice of a serial port the bootloader protocol needs.
 *
 * Kept as an interface so the programmer does not care whether you are on
 * usb-serial-for-android, raw android.hardware.usb bulk transfers, or a
 * loopback fake in a unit test.
 */
public interface SerialLink {

    void setBaudRate(int baud) throws IOException;

    /** DTR is what pulses the Uno's auto-reset line. */
    void setDtr(boolean asserted) throws IOException;

    void setRts(boolean asserted) throws IOException;

    void write(byte[] data, int timeoutMs) throws IOException;

    /**
     * Reads into dest, blocking up to timeoutMs.
     * @return number of bytes actually read, may be 0
     */
    int read(byte[] dest, int timeoutMs) throws IOException;

    /** Drops anything already sitting in the input buffer. */
    void purgeInput();

    void close();
}

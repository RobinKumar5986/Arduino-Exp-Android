package com.kgjr.aurdinoexperiment.flash;

import android.hardware.usb.UsbDeviceConnection;

import com.hoho.android.usbserial.driver.UsbSerialPort;

import java.io.IOException;

/**
 * SerialLink backed by usb-serial-for-android.
 *
 *   implementation "com.github.mik3y:usb-serial-for-android:3.7.3"
 *
 * If your SerialTestActivity already talks to the board through a different
 * mechanism, write a second implementation of SerialLink instead of changing
 * the programmer - it only needs read/write/DTR/baud.
 */
public final class UsbSerialLink implements SerialLink {

    private final UsbSerialPort port;
    private final UsbDeviceConnection connection;

    public UsbSerialLink(UsbSerialPort port, UsbDeviceConnection connection) {
        this.port = port;
        this.connection = connection;
    }

    @Override
    public void setBaudRate(int baud) throws IOException {
        port.setParameters(baud, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE);
    }

    @Override
    public void setDtr(boolean asserted) throws IOException {
        port.setDTR(asserted);
    }

    @Override
    public void setRts(boolean asserted) throws IOException {
        port.setRTS(asserted);
    }

    @Override
    public void write(byte[] data, int timeoutMs) throws IOException {
        port.write(data, timeoutMs);
    }

    @Override
    public int read(byte[] dest, int timeoutMs) throws IOException {
        return port.read(dest, timeoutMs);
    }

    @Override
    public void purgeInput() {
        byte[] scratch = new byte[256];
        try {
            // Drain whatever the board chattered before we reset it.
            while (port.read(scratch, 20) > 0) {
                // keep going
            }
        } catch (IOException ignored) {
            // nothing buffered, or the port went away; either way we are done
        }
    }

    @Override
    public void close() {
        try {
            port.close();
        } catch (IOException ignored) {
        }
        if (connection != null) connection.close();
    }
}

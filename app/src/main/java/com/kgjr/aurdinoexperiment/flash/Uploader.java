package com.kgjr.aurdinoexperiment.flash;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.os.Build;

import com.hoho.android.usbserial.driver.UsbSerialDriver;
import com.hoho.android.usbserial.driver.UsbSerialPort;
import com.hoho.android.usbserial.driver.UsbSerialProber;

import com.kgjr.aurdinoexperiment.ide.Board;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Finds the attached board, asks for USB permission if needed, and runs the
 * STK500 programmer against it.
 *
 * Call from a background thread - it blocks.
 */
public final class Uploader {

    private static final String ACTION_USB_PERMISSION =
            "com.kgjr.aurdinoexperiment.USB_PERMISSION";

    private final Context ctx;

    public Uploader(Context ctx) {
        this.ctx = ctx.getApplicationContext();
    }

    public void upload(File hexFile, Board board, boolean verify,
                       Stk500Programmer.Progress progress) throws IOException {

        progress.onLog("Reading " + hexFile.getName() + "...");
        IntelHex hex = IntelHex.parse(hexFile);
        progress.onLog(hex.size() + " bytes of program data.");

        UsbManager manager = (UsbManager) ctx.getSystemService(Context.USB_SERVICE);
        if (manager == null) throw new IOException("no USB service on this device");

        List<UsbSerialDriver> drivers = UsbSerialProber.getDefaultProber().findAllDrivers(manager);
        if (drivers.isEmpty()) {
            throw new IOException(
                    "No USB serial device found.\n"
                  + "Check the OTG cable, and that the phone actually supplies power to the board "
                  + "(the Uno's ON LED should be lit).");
        }

        UsbSerialDriver driver = drivers.get(0);
        UsbDevice device = driver.getDevice();
        progress.onLog(String.format("Found %s (VID 0x%04X PID 0x%04X)",
                driver.getClass().getSimpleName(), device.getVendorId(), device.getProductId()));

        if (!manager.hasPermission(device)) {
            progress.onLog("Requesting USB permission...");
            if (!requestPermission(manager, device)) {
                throw new IOException("USB permission denied");
            }
        }

        UsbDeviceConnection connection = manager.openDevice(device);
        if (connection == null) throw new IOException("could not open the USB device");

        UsbSerialPort port = driver.getPorts().get(0);
        port.open(connection);

        SerialLink link = new UsbSerialLink(port, connection);
        try {
            new Stk500Programmer(link, progress)
                    .flash(hex.data, board.flashPageBytes, board.uploadBaud,
                           board.signature, verify);
        } finally {
            link.close();
        }
    }

    private boolean requestPermission(UsbManager manager, UsbDevice device) {
        final CountDownLatch latch = new CountDownLatch(1);
        final boolean[] granted = {false};

        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent intent) {
                if (ACTION_USB_PERMISSION.equals(intent.getAction())) {
                    granted[0] = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);
                    latch.countDown();
                }
            }
        };

        IntentFilter filter = new IntentFilter(ACTION_USB_PERMISSION);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ctx.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            ctx.registerReceiver(receiver, filter);
        }

        int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                ? PendingIntent.FLAG_MUTABLE
                : 0;
        PendingIntent pi = PendingIntent.getBroadcast(
                ctx, 0, new Intent(ACTION_USB_PERMISSION).setPackage(ctx.getPackageName()), flags);
        manager.requestPermission(device, pi);

        try {
            latch.await(60, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            try {
                ctx.unregisterReceiver(receiver);
            } catch (IllegalArgumentException ignored) {
            }
        }
        return granted[0];
    }
}

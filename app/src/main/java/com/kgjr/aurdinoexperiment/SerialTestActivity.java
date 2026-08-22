package com.kgjr.aurdinoexperiment;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.hoho.android.usbserial.driver.UsbSerialDriver;
import com.hoho.android.usbserial.driver.UsbSerialPort;
import com.hoho.android.usbserial.driver.UsbSerialProber;
import com.hoho.android.usbserial.util.SerialInputOutputManager;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.Executors;

public class SerialTestActivity extends AppCompatActivity implements SerialInputOutputManager.Listener {

    private static final String ACTION_USB_PERMISSION = "com.kgjr.aurdinoexperiment.USB_PERMISSION";

    private UsbManager usbManager;
    private UsbSerialPort port;
    private SerialInputOutputManager ioManager;
    private TextView logView;
    private ScrollView scrollView;

    private final BroadcastReceiver usbReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (ACTION_USB_PERMISSION.equals(intent.getAction())) {
                boolean granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);
                if (granted) {
                    openPort();
                } else {
                    log("Permission denied");
                }
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_serial_test);

        logView = findViewById(R.id.logView);
        scrollView = findViewById(R.id.scrollView);
        Button ledOnButton = findViewById(R.id.ledOnButton);
        Button ledOffButton = findViewById(R.id.ledOffButton);

        ledOnButton.setOnClickListener(v -> sendCommand("LED_ON"));
        ledOffButton.setOnClickListener(v -> sendCommand("LED_OFF"));

        usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);

        IntentFilter filter = new IntentFilter(ACTION_USB_PERMISSION);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(usbReceiver, filter);
        }

        connect();
    }

    private void connect() {
        List<UsbSerialDriver> drivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager);
        if (drivers.isEmpty()) {
            log("No USB serial device found");
            return;
        }

        UsbSerialDriver driver = drivers.get(0);
        UsbDevice device = driver.getDevice();

        if (!usbManager.hasPermission(device)) {
            int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ? PendingIntent.FLAG_MUTABLE : 0;
            PendingIntent pendingIntent = PendingIntent.getBroadcast(this, 0, new Intent(ACTION_USB_PERMISSION), flags);
            usbManager.requestPermission(device, pendingIntent);
        } else {
            openPort();
        }
    }

    private void openPort() {
        List<UsbSerialDriver> drivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager);
        if (drivers.isEmpty()) return;

        port = drivers.get(0).getPorts().get(0);

        try {
            port.open(usbManager.openDevice(port.getDriver().getDevice()));
            port.setParameters(9600, UsbSerialPort.DATABITS_8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE);

            ioManager = new SerialInputOutputManager(port, this);
            Executors.newSingleThreadExecutor().submit(ioManager);

            log("Connected");
        } catch (IOException e) {
            log("Failed to open port: " + e.getMessage());
        }
    }

    private void sendCommand(String command) {
        if (port == null) {
            log("Not connected");
            return;
        }
        try {
            port.write((command + "\n").getBytes(), 1000);
            log("Sent: " + command);
        } catch (IOException e) {
            log("Write failed: " + e.getMessage());
        }
    }

    @Override
    public void onNewData(byte[] data) {
        String received = new String(data);
        runOnUiThread(() -> log("Received: " + received.trim()));
    }

    @Override
    public void onRunError(Exception e) {
        runOnUiThread(() -> log("Run error: " + e.getMessage()));
    }

    private void log(String message) {
        logView.append(message + "\n");
        scrollView.post(() -> scrollView.fullScroll(View.FOCUS_DOWN));
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        unregisterReceiver(usbReceiver);
        if (ioManager != null) ioManager.stop();
        if (port != null) {
            try {
                port.close();
            } catch (IOException ignored) {}
        }
    }
}
package com.kgjr.aurdinoexperiment.ide;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import com.kgjr.aurdinoexperiment.R;
import com.kgjr.aurdinoexperiment.flash.Stk500Programmer;
import com.kgjr.aurdinoexperiment.flash.Uploader;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Write a sketch, compile it on the phone, flash it over USB.
 *
 * If the native toolchain is not bundled yet, Compile explains what is missing
 * and Upload falls back to the prebuilt blink.hex from the SDK assets, so the
 * USB half of the pipeline can be exercised on its own.
 */
public class EditorActivity extends AppCompatActivity {

    private static final String PREF = "editor";
    private static final String KEY_SOURCE = "source";

    private static final String DEFAULT_SKETCH =
            "// Blink the built-in LED on pin 13.\n"
          + "\n"
          + "#define LED_PIN LED_BUILTIN\n"
          + "\n"
          + "void setup() {\n"
          + "  pinMode(LED_PIN, OUTPUT);\n"
          + "}\n"
          + "\n"
          + "void loop() {\n"
          + "  digitalWrite(LED_PIN, HIGH);\n"
          + "  delay(500);\n"
          + "  digitalWrite(LED_PIN, LOW);\n"
          + "  delay(500);\n"
          + "}\n";

    private final Board board = Board.UNO;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());

    private EditText editor;
    private TextView logView;
    private ScrollView logScroll;
    private ProgressBar progressBar;
    private Button compileButton;
    private Button uploadButton;

    private File lastHex;
    private boolean busy;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_editor);

        editor = findViewById(R.id.sketchEditor);
        logView = findViewById(R.id.buildLog);
        logScroll = findViewById(R.id.logScroll);
        progressBar = findViewById(R.id.progressBar);
        compileButton = findViewById(R.id.compileButton);
        uploadButton = findViewById(R.id.uploadButton);

        SharedPreferences prefs = getSharedPreferences(PREF, Context.MODE_PRIVATE);
        editor.setText(prefs.getString(KEY_SOURCE, DEFAULT_SKETCH));

        setTitle(board.displayName);

        compileButton.setOnClickListener(v -> compile(false));
        uploadButton.setOnClickListener(v -> compile(true));
        findViewById(R.id.resetButton).setOnClickListener(v -> {
            editor.setText(DEFAULT_SKETCH);
            clearLog();
        });

        // Unpack the SDK in the background so the first Compile is not slow.
        worker.execute(() -> {
            try {
                new AvrSdk(this).installIfNeeded(this::appendLogAsync);
            } catch (Exception e) {
                appendLogAsync("Could not unpack the AVR SDK: " + e);
            }
        });
    }

    @Override
    protected void onPause() {
        super.onPause();
        getSharedPreferences(PREF, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_SOURCE, editor.getText().toString())
                .apply();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        worker.shutdownNow();
    }

    // ------------------------------------------------------------------

    private void compile(boolean thenUpload) {
        if (busy) return;
        setBusy(true);
        clearLog();

        final String source = editor.getText().toString();

        worker.execute(() -> {
            ArduinoCompiler compiler = new ArduinoCompiler(this);
            BuildResult result = compiler.build(source, board, this::appendLogAsync);

            if (result.ok) {
                lastHex = result.hexFile;
                if (thenUpload) {
                    doUpload(result.hexFile);
                    return;
                }
                ui.post(() -> {
                    setBusy(false);
                    toast("Compiled: " + result.programBytes + " bytes");
                });
                return;
            }

            // Toolchain not installed yet? Still let the user prove the USB path.
            if (thenUpload && !compiler.tools().isComplete()) {
                File fallback = compiler.sdk().exampleBlinkHex();
                if (fallback.isFile()) {
                    appendLogAsync("");
                    appendLogAsync("Falling back to the prebuilt blink.hex that ships in assets,");
                    appendLogAsync("so the upload path can be tested without the compiler.");
                    doUpload(fallback);
                    return;
                }
            }

            ui.post(() -> {
                setBusy(false);
                toast(result.error);
            });
        });
    }

    private void doUpload(File hex) {
        Stk500Programmer.Progress progress = new Stk500Programmer.Progress() {
            @Override
            public void onLog(String message) {
                appendLogAsync(message);
            }

            @Override
            public void onProgress(int percent) {
                ui.post(() -> progressBar.setProgress(percent));
            }
        };

        try {
            appendLogAsync("");
            appendLogAsync("--- upload ---");
            new Uploader(this).upload(hex, board, true, progress);
            ui.post(() -> {
                setBusy(false);
                toast("Uploaded");
            });
        } catch (Exception e) {
            appendLogAsync("");
            appendLogAsync("Upload failed: " + e.getMessage());
            ui.post(() -> {
                setBusy(false);
                toast("Upload failed");
            });
        }
    }

    // ------------------------------------------------------------------

    private void setBusy(boolean value) {
        busy = value;
        compileButton.setEnabled(!value);
        uploadButton.setEnabled(!value);
        progressBar.setVisibility(value ? View.VISIBLE : View.GONE);
        progressBar.setProgress(0);
    }

    private void clearLog() {
        logView.setText("");
    }

    private void appendLogAsync(@NonNull String line) {
        ui.post(() -> {
            logView.append(line);
            logView.append("\n");
            logScroll.post(() -> logScroll.fullScroll(View.FOCUS_DOWN));
        });
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }
}

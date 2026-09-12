package com.sillytavern.app;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Full-screen progress view for StUpdater operations: a scrolling log of
 * git/npm output with a status line and a close button that unlocks when
 * the operation finishes.
 */
public class UpdateActivity extends Activity {

    public static final String EXTRA_MODE = "mode";

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler handler = new Handler(Looper.getMainLooper());

    private TextView statusText;
    private TextView logText;
    private ScrollView scroll;
    private Button closeButton;
    private boolean running = true;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        StUpdater.Mode mode;
        try {
            mode = StUpdater.Mode.valueOf(getIntent().getStringExtra(EXTRA_MODE));
        } catch (Exception e) {
            mode = StUpdater.Mode.PACKAGES;
        }

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setBackgroundColor(Ui.BG);
        int pad = Ui.dp(this, 16);
        content.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText(titleFor(mode));
        title.setTextColor(Ui.TEXT);
        title.setTextSize(20);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        content.addView(title);

        statusText = new TextView(this);
        statusText.setTextColor(Ui.ACCENT);
        statusText.setTextSize(13);
        statusText.setPadding(0, Ui.dp(this, 4), 0, Ui.dp(this, 8));
        statusText.setText("Running...");
        content.addView(statusText);

        logText = new TextView(this);
        logText.setTextColor(Ui.TEXT_DIM);
        logText.setTextSize(11);
        logText.setTypeface(android.graphics.Typeface.MONOSPACE);
        scroll = new ScrollView(this);
        scroll.addView(logText);
        content.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        closeButton = new Button(this);
        closeButton.setText("Close");
        closeButton.setEnabled(false);
        closeButton.setOnClickListener(v -> finish());
        content.addView(closeButton, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        setContentView(content);

        final StUpdater.Mode finalMode = mode;
        executor.execute(() -> {
            try {
                StUpdater.run(getApplicationContext(), finalMode, this::appendLog);
                handler.post(() -> {
                    statusText.setText("Done");
                    statusText.setTextColor(Ui.GOOD);
                });
            } catch (Exception e) {
                appendLog("FAILED: " + e.getMessage());
                handler.post(() -> {
                    statusText.setText("Failed — you can use Repair payload to restore the bundled version");
                    statusText.setTextColor(Ui.BAD);
                });
            } finally {
                running = false;
                handler.post(() -> closeButton.setEnabled(true));
            }
        });
    }

    private static String titleFor(StUpdater.Mode mode) {
        switch (mode) {
            case ST_LATEST:
                return "Update SillyTavern · Latest";
            case ST_STAGING:
                return "Update SillyTavern · Staging";
            case PACKAGES:
                return "Update packages";
            default:
                return "Repair payload";
        }
    }

    private void appendLog(String line) {
        handler.post(() -> {
            logText.append(line + "\n");
            scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
        });
    }

    @Override
    public void onBackPressed() {
        if (!running) {
            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {
        executor.shutdownNow();
        super.onDestroy();
    }
}

package com.sillytavern.app;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Hosts the SillyTavern WebView. On first launch it unpacks the payload,
 * then starts the Node.js server service and waits for it to answer on
 * http://127.0.0.1:8000 before showing the UI.
 *
 * File imports (character cards, backups, presets) use the Android file
 * picker via onShowFileChooser. File exports are routed to the public
 * Downloads folder via DownloadBridge (blob:/data:) and a
 * DownloadListener (http(s)).
 */
public class MainActivity extends Activity {

    private static final String SERVER_URL = "http://127.0.0.1:8000";
    private static final long SERVER_TIMEOUT_MS = 20 * 60 * 1000L;
    private static final int FILE_CHOOSER_REQUEST = 42;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final ExecutorService downloadExecutor = Executors.newSingleThreadExecutor();
    private final Handler handler = new Handler(Looper.getMainLooper());

    private FrameLayout root;
    private FrameLayout loadingOverlay;
    private TextView statusText;
    private TextView logTailText;
    private WebView webView;
    private boolean loaded = false;
    private ValueCallback<Uri[]> filePathCallback;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildLayout();
        setContentView(root);
        requestNotificationPermission();

        executor.execute(() -> {
            try {
                if (!PayloadExtractor.isExtracted(this)) {
                    setStatus("Unpacking SillyTavern (first launch only)...");
                    PayloadExtractor.extract(this, (done, total, current) ->
                            setStatus(done < 0
                                    ? "Finishing setup..."
                                    : "Unpacking SillyTavern... " + (done * 100 / Math.max(total, 1)) + "%"));
                }
                startService(new Intent(this, ServerService.class).setAction(ServerService.ACTION_START));
                waitForServer();
            } catch (Exception e) {
                setStatus("Setup failed: " + e.getMessage());
            }
        });
    }

    private void buildLayout() {
        root = new FrameLayout(this);

        webView = new WebView(this);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        settings.setBuiltInZoomControls(false);
        settings.setSupportMultipleWindows(false);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        settings.setTextZoom(100);
        settings.setOffscreenPreRaster(true);
        CookieManager.getInstance().setAcceptCookie(true);

        webView.addJavascriptInterface(new DownloadBridge(this), "AndroidBridge");
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                view.evaluateJavascript(DownloadBridge.INJECT_JS, null);
            }
        });
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback,
                                             FileChooserParams params) {
                return openFilePicker(callback, params);
            }
        });
        webView.setDownloadListener((url, userAgent, contentDisposition, mimeType, contentLength) ->
                downloadExecutor.execute(() -> downloadUrl(url, userAgent, contentDisposition, mimeType)));
        webView.setVisibility(View.GONE);
        root.addView(webView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        loadingOverlay = new FrameLayout(this);
        loadingOverlay.setBackgroundColor(Color.parseColor("#1A1625"));

        FrameLayout centered = new FrameLayout(this);
        ProgressBar spinner = new ProgressBar(this);
        FrameLayout.LayoutParams spinnerParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        centered.addView(spinner, spinnerParams);
        loadingOverlay.addView(centered, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        statusText = new TextView(this);
        statusText.setTextColor(Color.WHITE);
        statusText.setTextSize(16);
        statusText.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams statusParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        statusParams.topMargin = dp(140);
        loadingOverlay.addView(statusText, statusParams);

        logTailText = new TextView(this);
        logTailText.setTextColor(Color.parseColor("#8F86A6"));
        logTailText.setTextSize(11);
        logTailText.setGravity(Gravity.CENTER_HORIZONTAL);
        FrameLayout.LayoutParams logParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
        int pad = dp(24);
        logTailText.setPadding(pad, 0, pad, pad);
        loadingOverlay.addView(logTailText, logParams);

        root.addView(loadingOverlay, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    /**
     * Opens the system file picker for <input type="file"> elements
     * (importing characters, backups, presets, etc.).
     */
    private boolean openFilePicker(ValueCallback<Uri[]> callback, WebChromeClient.FileChooserParams params) {
        if (filePathCallback != null) {
            filePathCallback.onReceiveValue(null);
        }
        filePathCallback = callback;
        Intent intent;
        try {
            intent = params.createIntent();
            intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE,
                    params.getMode() == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE);
        } catch (Exception e) {
            intent = new Intent(Intent.ACTION_GET_CONTENT)
                    .addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("*/*");
        }
        try {
            startActivityForResult(Intent.createChooser(intent, null), FILE_CHOOSER_REQUEST);
        } catch (Exception e) {
            filePathCallback = null;
            return false;
        }
        return true;
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != FILE_CHOOSER_REQUEST || filePathCallback == null) {
            return;
        }
        Uri[] uris = null;
        if (resultCode == RESULT_OK && data != null) {
            if (data.getClipData() != null) {
                ArrayList<Uri> list = new ArrayList<>();
                for (int i = 0; i < data.getClipData().getItemCount(); i++) {
                    list.add(data.getClipData().getItemAt(i).getUri());
                }
                uris = list.toArray(new Uri[0]);
            } else if (data.getData() != null) {
                uris = new Uri[]{data.getData()};
            }
        }
        filePathCallback.onReceiveValue(uris);
        filePathCallback = null;
    }

    /**
     * Handles plain http(s) downloads (blob:/data: are handled in-page by
     * DownloadBridge.INJECT_JS).
     */
    private void downloadUrl(String url, String userAgent, String contentDisposition, String mimeType) {
        String filename = filenameFromDisposition(contentDisposition);
        if (filename == null) {
            String path = Uri.parse(url).getPath();
            filename = (path == null || path.isEmpty()) ? "download"
                    : path.substring(path.lastIndexOf('/') + 1);
        }
        if (filename.isEmpty()) {
            filename = "download";
        }
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestProperty("User-Agent", userAgent);
            String cookie = CookieManager.getInstance().getCookie(url);
            if (cookie != null) {
                conn.setRequestProperty("Cookie", cookie);
            }
            if (conn.getResponseCode() >= 400) {
                toast("Download failed: HTTP " + conn.getResponseCode());
                return;
            }
            try (InputStream in = conn.getInputStream()) {
                Uri saved = DownloadBridge.saveStream(this, in, filename, mimeType);
                toast(saved != null ? "Saved: Downloads/SillyTavern/" + filename : "Download failed");
            }
        } catch (Exception e) {
            toast("Download failed: " + e.getMessage());
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private static final Pattern FILENAME_STAR = Pattern.compile("filename\\*=(?:UTF-8'')?\"?([^\";]+)\"?", Pattern.CASE_INSENSITIVE);
    private static final Pattern FILENAME_PLAIN = Pattern.compile("filename=\"?([^\";]+)\"?", Pattern.CASE_INSENSITIVE);

    private static String filenameFromDisposition(String disposition) {
        if (disposition == null) {
            return null;
        }
        Matcher star = FILENAME_STAR.matcher(disposition);
        if (star.find()) {
            try {
                return java.net.URLDecoder.decode(star.group(1), "UTF-8");
            } catch (Exception ignored) {
                return star.group(1);
            }
        }
        Matcher plain = FILENAME_PLAIN.matcher(disposition);
        return plain.find() ? plain.group(1) : null;
    }

    private void waitForServer() {
        setStatus("Starting SillyTavern server...\nFirst launch compiles the frontend, this can take a few minutes.");
        long deadline = System.currentTimeMillis() + SERVER_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            if (loaded) {
                return;
            }
            if (serverResponds()) {
                handler.post(this::showServer);
                return;
            }
            updateLogTail();
            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                return;
            }
        }
        setStatus("Server did not respond in time.\nCheck the log, then restart the app.");
    }

    private boolean serverResponds() {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(SERVER_URL + "/api/ping").openConnection();
            conn.setConnectTimeout(2000);
            conn.setReadTimeout(2000);
            conn.setRequestMethod("GET");
            conn.getResponseCode();
            return true;
        } catch (Exception e) {
            try {
                conn = (HttpURLConnection) new URL(SERVER_URL).openConnection();
                conn.setConnectTimeout(2000);
                conn.setReadTimeout(2000);
                int code = conn.getResponseCode();
                return code > 0;
            } catch (Exception e2) {
                return false;
            }
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private void showServer() {
        if (loaded) {
            return;
        }
        loaded = true;
        webView.setVisibility(View.VISIBLE);
        webView.loadUrl(SERVER_URL);
        loadingOverlay.setVisibility(View.GONE);
    }

    private void updateLogTail() {
        File log = new File(getFilesDir(), ServerService.LOG_FILE_NAME);
        if (!log.isFile()) {
            return;
        }
        try (RandomAccessFile raf = new RandomAccessFile(log, "r")) {
            long len = raf.length();
            long start = Math.max(0, len - 400);
            raf.seek(start);
            StringBuilder tail = new StringBuilder();
            String line;
            while ((line = raf.readLine()) != null) {
                tail.append(line).append('\n');
            }
            String text = tail.toString().trim();
            if (!text.isEmpty()) {
                String[] lines = text.split("\n");
                String last = lines[lines.length - 1];
                if (last.length() > 120) {
                    last = last.substring(0, 120);
                }
                String finalLast = last;
                handler.post(() -> logTailText.setText(finalLast));
            }
        } catch (Exception ignored) {
        }
    }

    private void setStatus(String text) {
        handler.post(() -> statusText.setText(text));
    }

    private void toast(String text) {
        handler.post(() -> Toast.makeText(this, text, Toast.LENGTH_LONG).show());
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        }
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {
        executor.shutdownNow();
        downloadExecutor.shutdownNow();
        if (webView != null) {
            webView.destroy();
        }
        super.onDestroy();
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
}

package com.sillytavern.app;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * ST-Manager: the launcher entry point. Lists all SillyTavern instances
 * with their state and RAM usage, and provides per-instance actions
 * (open/start/stop, backup import, updates, delete) plus instance
 * creation. The SillyTavern code is shared; an update applies to every
 * instance on next start.
 */
public class ManagerActivity extends Activity {

    private static final int IMPORT_REQUEST = 100;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler handler = new Handler(Looper.getMainLooper());

    private LinearLayout cardsContainer;
    private TextView headerSubtitle;
    private TextView updateBadge;
    private Button newInstanceButton;
    private FrameLayout busyOverlay;
    private TextView busyText;

    private final Map<Integer, TextView> statusViews = new HashMap<>();
    private int importTargetInstance = -1;
    private boolean updateChecked = false;

    private final Runnable ramPoller = new Runnable() {
        @Override
        public void run() {
            refreshStatuses();
            handler.postDelayed(this, 2000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildLayout();
        setContentView(buildRoot());
        requestNotificationPermission();

        executor.execute(() -> {
            try {
                InstanceManager.migrateIfNeeded(this);
                if (!PayloadExtractor.isExtracted(this)) {
                    setBusy("Unpacking SillyTavern (first launch only)...");
                    PayloadExtractor.extract(this, (done, total, current) ->
                            setBusy(done < 0 ? "Finishing setup..."
                                    : "Unpacking SillyTavern... " + (done * 100 / Math.max(total, 1)) + "%"));
                }
                InstanceManager.Instance one = InstanceManager.get(this, 1);
                if (one != null) {
                    InstanceManager.ensureInstanceDirs(this, one);
                }
            } catch (Exception e) {
                toast("Setup failed: " + e.getMessage());
            } finally {
                clearBusy();
                handler.post(this::rebuildCards);
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        rebuildCards();
        handler.post(ramPoller);
        checkForUpdatesOnce();
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(ramPoller);
    }

    @Override
    protected void onDestroy() {
        executor.shutdownNow();
        super.onDestroy();
    }

    // ---------- UI ----------

    private void buildLayout() {
        cardsContainer = new LinearLayout(this);
        cardsContainer.setOrientation(LinearLayout.VERTICAL);

        busyText = new TextView(this);
        busyText.setTextColor(Ui.TEXT);
        busyText.setTextSize(16);
        busyText.setGravity(Gravity.CENTER);
        FrameLayout busyInner = new FrameLayout(this);
        ProgressBar spinner = new ProgressBar(this);
        busyInner.addView(spinner, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        busyOverlay = new FrameLayout(this);
        busyOverlay.setBackgroundColor(Ui.BG);
        busyOverlay.addView(busyInner, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        FrameLayout.LayoutParams busyTextParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        busyTextParams.topMargin = Ui.dp(this, 140);
        busyOverlay.addView(busyText, busyTextParams);
        busyOverlay.setVisibility(View.GONE);
    }

    private View buildRoot() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Ui.BG);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(this, 16);
        content.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("ST-Manager");
        title.setTextColor(Ui.TEXT);
        title.setTextSize(24);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setSingleLine(true);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        LinearLayout headerRow = new LinearLayout(this);
        headerRow.setOrientation(LinearLayout.HORIZONTAL);
        headerRow.setGravity(Gravity.CENTER_VERTICAL);
        headerRow.addView(title, titleParams);
        content.addView(headerRow);

        headerSubtitle = new TextView(this);
        headerSubtitle.setTextColor(Ui.TEXT_DIM);
        headerSubtitle.setTextSize(13);
        headerSubtitle.setPadding(0, Ui.dp(this, 4), 0, 0);
        content.addView(headerSubtitle);

        updateBadge = new TextView(this);
        updateBadge.setTextColor(Ui.ACCENT);
        updateBadge.setTextSize(13);
        updateBadge.setPadding(0, Ui.dp(this, 4), 0, 0);
        updateBadge.setVisibility(View.GONE);
        content.addView(updateBadge);

        LinearLayout topActions = new LinearLayout(this);
        topActions.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams topParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        topParams.topMargin = Ui.dp(this, 10);
        content.addView(topActions, topParams);
        Button repairTop = makeButton("Repair", Ui.ACCENT_DIM);
        repairTop.setOnClickListener(v -> confirmUpdate(StUpdater.Mode.REPAIR));
        Button packagesTop = makeButton("Packages", Ui.ACCENT_DIM);
        packagesTop.setOnClickListener(v -> confirmUpdate(StUpdater.Mode.PACKAGES));
        Button updateTop = makeButton("Update ST", Ui.ACCENT);
        updateTop.setOnClickListener(v -> showUpdateDialog());
        topActions.addView(repairTop, weightParams());
        topActions.addView(packagesTop, weightParams());
        topActions.addView(updateTop, weightParams());

        ScrollView scroll = new ScrollView(this);
        scroll.addView(cardsContainer, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        scrollParams.topMargin = Ui.dp(this, 12);
        content.addView(scroll, scrollParams);

        newInstanceButton = makeButton("+ New instance", Ui.ACCENT_DIM);
        newInstanceButton.setOnClickListener(v -> createInstance());
        content.addView(newInstanceButton, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView note = new TextView(this);
        note.setText("All instances share one SillyTavern installation; updates apply to every instance. "
                + "Each running instance uses roughly 300-800 MB of RAM.");
        note.setTextColor(Ui.TEXT_DIM);
        note.setTextSize(11);
        note.setPadding(0, Ui.dp(this, 8), 0, 0);
        content.addView(note);

        root.addView(content, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(busyOverlay, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        return root;
    }

    private void rebuildCards() {
        statusViews.clear();
        cardsContainer.removeAllViews();
        List<InstanceManager.Instance> instances = InstanceManager.list(this);
        headerSubtitle.setText("SillyTavern " + InstanceManager.payloadStVersion(this)
                + " · " + instances.size() + "/" + InstanceManager.MAX_INSTANCES + " instances");
        newInstanceButton.setVisibility(
                instances.size() < InstanceManager.MAX_INSTANCES ? View.VISIBLE : View.GONE);
        for (InstanceManager.Instance inst : instances) {
            cardsContainer.addView(buildCard(inst));
        }
    }

    private View buildCard(InstanceManager.Instance inst) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Ui.CARD);
        bg.setCornerRadius(Ui.dp(this, 12));
        bg.setStroke(Ui.dp(this, 1), Ui.CARD_BORDER);
        card.setBackground(bg);
        int cardPad = Ui.dp(this, 14);
        card.setPadding(cardPad, cardPad, cardPad, cardPad);
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cardParams.bottomMargin = Ui.dp(this, 12);
        card.setLayoutParams(cardParams);

        TextView name = new TextView(this);
        name.setText(inst.name + "  ·  :" + inst.port);
        name.setTextColor(Ui.TEXT);
        name.setTextSize(18);
        name.setTypeface(null, android.graphics.Typeface.BOLD);
        card.addView(name);

        TextView status = new TextView(this);
        status.setTextSize(13);
        status.setPadding(0, Ui.dp(this, 2), 0, Ui.dp(this, 8));
        card.addView(status);
        statusViews.put(inst.id, status);

        LinearLayout row1 = new LinearLayout(this);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        card.addView(row1);
        Button open = makeButton("Open", Ui.ACCENT);
        open.setOnClickListener(v -> openInstance(inst));
        Button startStop = makeButton("Start", Ui.ACCENT_DIM);
        startStop.setOnClickListener(v -> toggleInstance(inst));
        Button stopBtn = makeButton("Stop", Ui.ACCENT_DIM);
        stopBtn.setOnClickListener(v -> stopInstance(inst));
        row1.addView(open, weightParams());
        row1.addView(startStop, weightParams());
        row1.addView(stopBtn, weightParams());

        LinearLayout row2 = new LinearLayout(this);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        card.addView(row2);
        Button importBtn = makeButton("Import backup", Ui.ACCENT_DIM);
        importBtn.setOnClickListener(v -> confirmImport(inst));
        row2.addView(importBtn, weightParams());
        if (inst.id != 1) {
            Button deleteBtn = makeButton("Delete", Ui.BAD);
            deleteBtn.setOnClickListener(v -> confirmDelete(inst));
            row2.addView(deleteBtn, weightParams());
        }

        refreshStatus(inst.id, status);
        return card;
    }

    private LinearLayout.LayoutParams weightParams() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        p.setMargins(Ui.dp(this, 2), Ui.dp(this, 2), Ui.dp(this, 2), Ui.dp(this, 2));
        return p;
    }

    private Button makeButton(String label, int color) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextSize(11);
        b.setTextColor(Ui.TEXT);
        b.setAllCaps(false);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color);
        bg.setCornerRadius(Ui.dp(this, 8));
        b.setBackground(bg);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setPadding(Ui.dp(this, 4), 0, Ui.dp(this, 4), 0);
        return b;
    }

    // ---------- status / RAM ----------

    private void refreshStatuses() {
        for (Map.Entry<Integer, TextView> e : statusViews.entrySet()) {
            refreshStatus(e.getKey(), e.getValue());
        }
    }

    private void refreshStatus(int instanceId, TextView view) {
        if (!ServerService.isRunning(instanceId)) {
            view.setText("Stopped");
            view.setTextColor(Ui.TEXT_DIM);
            return;
        }
        long mb = readRssMb(ServerService.pidOf(instanceId));
        view.setText(mb >= 0 ? "Running · " + mb + " MB RAM" : "Running");
        view.setTextColor(Ui.GOOD);
    }

    private long readRssMb(int pid) {
        if (pid <= 0) {
            return -1;
        }
        try (java.io.BufferedReader reader = new java.io.BufferedReader(
                new java.io.FileReader("/proc/" + pid + "/status"))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("VmRSS:")) {
                    String kb = line.substring(6).replace("kB", "").trim();
                    return Long.parseLong(kb) / 1024;
                }
            }
        } catch (Exception ignored) {
        }
        return -1;
    }

    // ---------- actions ----------

    private void openInstance(InstanceManager.Instance inst) {
        startActivity(new Intent(this, InstanceActivity.class)
                .putExtra(InstanceActivity.EXTRA_INSTANCE_ID, inst.id));
    }

    private void toggleInstance(InstanceManager.Instance inst) {
        if (ServerService.isRunning(inst.id)) {
            stopInstance(inst);
        } else {
            startService(new Intent(this, ServerService.class)
                    .setAction(ServerService.ACTION_START)
                    .putExtra(ServerService.EXTRA_INSTANCE_ID, inst.id));
            handler.postDelayed(this::refreshStatuses, 500);
        }
    }

    private void stopInstance(InstanceManager.Instance inst) {
        startService(new Intent(this, ServerService.class)
                .setAction(ServerService.ACTION_STOP)
                .putExtra(ServerService.EXTRA_INSTANCE_ID, inst.id));
        handler.postDelayed(this::refreshStatuses, 500);
    }

    private void createInstance() {
        executor.execute(() -> {
            InstanceManager.Instance inst = InstanceManager.create(this);
            handler.post(() -> {
                if (inst == null) {
                    toast("Instance limit reached");
                } else {
                    toast(inst.name + " created — its launcher icon will appear shortly");
                    rebuildCards();
                }
            });
        });
    }

    private void confirmDelete(InstanceManager.Instance inst) {
        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("Delete " + inst.name + "?")
                .setMessage("All characters, chats and settings of this instance will be permanently deleted.")
                .setPositiveButton("Delete", (d, w) -> {
                    stopInstance(inst);
                    executor.execute(() -> {
                        InstanceManager.delete(this, inst.id);
                        handler.post(this::rebuildCards);
                    });
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ---------- backup import ----------

    private void confirmImport(InstanceManager.Instance inst) {
        boolean running = ServerService.isRunning(inst.id);
        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("Import backup into " + inst.name + "?")
                .setMessage("Pick a SillyTavern backup .zip. Its contents replace this instance's current data."
                        + (running ? "\n\nThe instance will be stopped first." : ""))
                .setPositiveButton("Pick file", (d, w) -> {
                    if (running) {
                        stopInstance(inst);
                    }
                    importTargetInstance = inst.id;
                    Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                            .addCategory(Intent.CATEGORY_OPENABLE)
                            .setType("application/zip");
                    try {
                        startActivityForResult(intent, IMPORT_REQUEST);
                    } catch (Exception e) {
                        startActivityForResult(new Intent(Intent.ACTION_GET_CONTENT)
                                .addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), IMPORT_REQUEST);
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != IMPORT_REQUEST || resultCode != RESULT_OK || data == null
                || data.getData() == null || importTargetInstance < 1) {
            return;
        }
        final int targetId = importTargetInstance;
        importTargetInstance = -1;
        final Uri uri = data.getData();
        executor.execute(() -> {
            try {
                runImport(targetId, uri);
                toast("Backup imported into ST-Inst" + targetId);
            } catch (Exception e) {
                toast("Import failed: " + e.getMessage());
            } finally {
                clearBusy();
            }
        });
    }

    /**
     * Imports a SillyTavern backup zip. Detects whether the zip holds a
     * whole data root (contains default-user/) or a single user directory
     * (the format of SillyTavern's own backup download, with characters/,
     * chats/, settings.json at the zip root) and merges it into the right
     * place. A single wrapping top-level folder is stripped.
     */
    private void runImport(int instanceId, Uri uri) throws Exception {
        long deadline = System.currentTimeMillis() + 15_000;
        while (ServerService.isRunning(instanceId) && System.currentTimeMillis() < deadline) {
            Thread.sleep(300);
        }
        setBusy("Reading backup...");
        File stagingZip = new File(getCacheDir(), "import.zip");
        try (InputStream in = getContentResolver().openInputStream(uri);
             OutputStream out = new FileOutputStream(stagingZip)) {
            byte[] buf = new byte[256 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
        }

        File staging = new File(getCacheDir(), "import-staging");
        InstanceManager.deleteRecursively(staging);
        setBusy("Extracting backup...");
        PayloadExtractor.extractZipFile(stagingZip, staging, (done, total, current) ->
                setBusy("Extracting backup... " + done + "/" + total));
        stagingZip.delete();

        File root = staging;
        for (int i = 0; i < 3; i++) {
            File[] children = root.listFiles();
            if (children != null && children.length == 1 && children[0].isDirectory()
                    && !looksLikeDataRoot(children[0]) && !looksLikeUserDir(children[0])) {
                root = children[0];
            } else {
                break;
            }
        }

        File target;
        if (looksLikeDataRoot(root)) {
            target = InstanceManager.dataRoot(this, instanceId);
        } else {
            target = new File(InstanceManager.dataRoot(this, instanceId), "default-user");
        }
        setBusy("Installing data...");
        InstanceManager.copyRecursively(root, target);
        InstanceManager.deleteRecursively(staging);
    }

    private static boolean looksLikeDataRoot(File dir) {
        return new File(dir, "default-user").isDirectory()
                || new File(dir, "_storage").isDirectory();
    }

    private static boolean looksLikeUserDir(File dir) {
        return new File(dir, "settings.json").isFile()
                || new File(dir, "characters").isDirectory()
                || new File(dir, "chats").isDirectory();
    }

    // ---------- updates ----------

    private void showUpdateDialog() {
        String[] options = {"Latest (stable release)", "Staging (development branch)"};
        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("Update SillyTavern")
                .setItems(options, (d, which) ->
                        confirmUpdate(which == 0 ? StUpdater.Mode.ST_LATEST : StUpdater.Mode.ST_STAGING))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void confirmUpdate(StUpdater.Mode mode) {
        String title;
        String message;
        switch (mode) {
            case ST_LATEST:
                title = "Update to the latest stable release?";
                message = "Downloads and installs the newest SillyTavern release from GitHub.";
                break;
            case ST_STAGING:
                title = "Switch to the staging branch?";
                message = "Installs the current development state of SillyTavern. May be unstable.";
                break;
            case PACKAGES:
                title = "Update packages?";
                message = "Runs npm install to sync all dependencies with the current SillyTavern version.";
                break;
            default:
                title = "Repair payload?";
                message = "Restores the bundled SillyTavern " + InstanceManager.payloadStVersion(this)
                        + " from the app package, discarding any on-device updates. Instance data is kept.";
                break;
        }
        if (mode != StUpdater.Mode.REPAIR) {
            message += "\n\nAll running instances will be stopped. Requires internet.";
        } else {
            message += "\n\nAll running instances will be stopped.";
        }
        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton("Start", (d, w) ->
                        startActivity(new Intent(this, UpdateActivity.class)
                                .putExtra(UpdateActivity.EXTRA_MODE, mode.name())))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void checkForUpdatesOnce() {
        if (updateChecked) {
            return;
        }
        updateChecked = true;
        executor.execute(() -> {
            String latest = StUpdater.fetchLatestReleaseTag();
            if (latest == null) {
                return;
            }
            String current = InstanceManager.payloadStVersion(this);
            if (!latest.equals(current) && !current.startsWith("staging@")) {
                handler.post(() -> {
                    updateBadge.setText("SillyTavern " + latest + " is available — use Update ST on any instance");
                    updateBadge.setVisibility(View.VISIBLE);
                });
            }
        });
    }

    // ---------- helpers ----------

    private void setBusy(String text) {
        handler.post(() -> {
            busyText.setText(text);
            busyOverlay.setVisibility(View.VISIBLE);
        });
    }

    private void clearBusy() {
        handler.post(() -> busyOverlay.setVisibility(View.GONE));
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
}

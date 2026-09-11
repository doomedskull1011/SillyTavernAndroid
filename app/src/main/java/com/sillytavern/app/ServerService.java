package com.sillytavern.app;

import android.app.ActivityManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Foreground service that runs the bundled Node.js runtime with the
 * SillyTavern server (server.js). Server output is mirrored to a log
 * file so the UI can display startup progress.
 */
public class ServerService extends Service {

    public static final String ACTION_START = "com.sillytavern.app.START";
    public static final String ACTION_STOP = "com.sillytavern.app.STOP";
    public static final String LOG_FILE_NAME = "server.log";

    private static final String CHANNEL_ID = "server";
    private static final int NOTIFICATION_ID = 1;
    private static final String TAG = "ServerService";

    private static Process nodeProcess;
    private Thread logThread;
    private PowerManager.WakeLock wakeLock;

    public static boolean isRunning() {
        return nodeProcess != null && nodeProcess.isAlive();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopServer();
            stopSelf();
            return START_NOT_STICKY;
        }
        startForegroundWithNotification();
        if (isRunning()) {
            return START_STICKY;
        }
        startServer();
        return START_STICKY;
    }

    private void startForegroundWithNotification() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(new NotificationChannel(
                    CHANNEL_ID, getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW));
        }
        Intent stopIntent = new Intent(this, ServerService.class).setAction(ACTION_STOP);
        PendingIntent stopPending = PendingIntent.getService(
                this, 0, stopIntent, PendingIntent.FLAG_IMMUTABLE);
        Intent openIntent = new Intent(this, MainActivity.class);
        PendingIntent openPending = PendingIntent.getActivity(
                this, 0, openIntent, PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        Notification notification = builder
                .setContentTitle("SillyTavern")
                .setContentText("Local server is running on port 8000")
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentIntent(openPending)
                .addAction(new Notification.Action.Builder(null, "Stop server", stopPending).build())
                .setOngoing(true)
                .build();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private void startServer() {
        File appFiles = getFilesDir();
        File stDir = new File(appFiles, "sillytavern");
        String nativeLibDir = getApplicationInfo().nativeLibraryDir;
        File nodeBin = new File(nativeLibDir, "libnodeexec.so");

        // Unpack the git runtime data (templates, CA certs) if missing or
        // outdated, then link the git executables (packaged as jniLibs so
        // they live in the executable nativeLibraryDir) into a PATH dir via
        // symlinks. Apps targeting SDK 29+ cannot execve() files from their
        // home directory (SELinux), but nativeLibraryDir (apk_data_file)
        // is executable - that is how the node binary itself runs.
        // Failure is non-fatal: SillyTavern falls back to its pure-JS git.
        File gitDir = new File(appFiles, "git");
        File gitBinDir = new File(appFiles, "git-bin");
        boolean gitAvailable = false;
        try {
            if (!PayloadExtractor.isGitExtracted(this)) {
                writeLog("Extracting git runtime...");
                PayloadExtractor.extractGit(this);
            }
            gitAvailable = linkGitBinaries(gitBinDir, nativeLibDir);
        } catch (Exception e) {
            Log.e(TAG, "git runtime setup failed", e);
            writeLog("WARNING: git runtime unavailable: " + e.getMessage());
        }
        writeGitConfig(appFiles);

        // Scale the V8 heap to the device: 1/4 of total RAM, clamped to
        // [1024, 3072] MB. Prevents OOM kills on low-RAM phones while
        // leaving headroom for the webpack build on large ones.
        int heapMb = computeNodeHeapMb();
        writeLog("Starting node (max-old-space-size=" + heapMb + ")");

        ProcessBuilder pb = new ProcessBuilder(nodeBin.getAbsolutePath(),
                "--max-old-space-size=" + heapMb, "server.js");
        pb.directory(stDir);
        pb.redirectErrorStream(true);
        Map<String, String> env = pb.environment();
        env.put("HOME", appFiles.getAbsolutePath());
        env.put("TMPDIR", getCacheDir().getAbsolutePath());
        env.put("LD_LIBRARY_PATH", nativeLibDir);
        String basePath = "/system/bin:/system/xbin";
        if (gitAvailable) {
            env.put("PATH", gitBinDir.getAbsolutePath() + ":" + basePath);
            env.put("GIT_EXEC_PATH", gitBinDir.getAbsolutePath());
            env.put("GIT_TEMPLATE_DIR", new File(gitDir, "share/git-core/templates").getAbsolutePath());
            env.put("GIT_SSL_CAINFO", new File(gitDir, "etc/tls/cert.pem").getAbsolutePath());
            env.put("GIT_CONFIG_NOSYSTEM", "1");
            env.put("GIT_TERMINAL_PROMPT", "0");
        } else {
            env.put("PATH", basePath);
        }

        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "sillytavern:server");
        wakeLock.acquire(12 * 60 * 60 * 1000L);

        try {
            nodeProcess = pb.start();
        } catch (Exception e) {
            Log.e(TAG, "Failed to start node", e);
            writeLog("FATAL: cannot start node: " + e);
            stopSelf();
            return;
        }

        logThread = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(nodeProcess.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    Log.i(TAG, line);
                    writeLog(line);
                }
            } catch (Exception e) {
                Log.e(TAG, "log reader ended", e);
            }
        });
        logThread.setDaemon(true);
        logThread.start();

        new Thread(() -> {
            try {
                int code = nodeProcess.waitFor();
                writeLog("Server process exited with code " + code);
            } catch (InterruptedException ignored) {
            }
            stopSelf();
        }).start();
    }

    /**
     * Creates gitBinDir with symlinks named after the git executables,
     * pointing at the lib*.so copies in nativeLibraryDir. Returns true when
     * all links resolve to existing executable targets.
     */
    private boolean linkGitBinaries(File gitBinDir, String nativeLibDir) {
        String[][] links = {
                {"git", "libgitexec.so"},
                {"git-remote-http", "libgitremotehttp.so"},
                {"git-remote-https", "libgitremotehttps.so"},
        };
        if (!gitBinDir.isDirectory() && !gitBinDir.mkdirs()) {
            return false;
        }
        for (String[] link : links) {
            File target = new File(nativeLibDir, link[1]);
            File symlink = new File(gitBinDir, link[0]);
            if (!target.isFile()) {
                writeLog("WARNING: missing git binary " + target.getAbsolutePath());
                return false;
            }
            try {
                if (symlink.exists()) {
                    symlink.delete();
                }
                if (!symlink.exists()) {
                    android.system.Os.symlink(target.getAbsolutePath(), symlink.getAbsolutePath());
                }
            } catch (Exception e) {
                Log.e(TAG, "symlink failed for " + link[0], e);
                writeLog("WARNING: git symlink failed: " + e.getMessage());
                return false;
            }
            if (!symlink.canExecute()) {
                writeLog("WARNING: git symlink not executable: " + symlink.getAbsolutePath());
                return false;
            }
        }
        return true;
    }

    private int computeNodeHeapMb() {
        try {
            ActivityManager am = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
            ActivityManager.MemoryInfo info = new ActivityManager.MemoryInfo();
            am.getMemoryInfo(info);
            long totalMb = info.totalMem / (1024 * 1024);
            return (int) Math.max(1024, Math.min(3072, totalMb / 4));
        } catch (Exception e) {
            return 1536;
        }
    }

    /**
     * Pre-seed a global git identity so operations that need one (e.g.
     * merges during "git pull") never fail on a missing config.
     */
    private void writeGitConfig(File appFiles) {
        File gitconfig = new File(appFiles, ".gitconfig");
        if (gitconfig.exists()) {
            return;
        }
        String content = "[user]\n"
                + "\tname = SillyTavern\n"
                + "\temail = sillytavern@localhost\n"
                + "[init]\n"
                + "\tdefaultBranch = main\n";
        try (FileOutputStream fos = new FileOutputStream(gitconfig)) {
            fos.write(content.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            Log.w(TAG, "could not write .gitconfig", e);
        }
    }

    private void writeLog(String line) {
        try (FileOutputStream fos = new FileOutputStream(new File(getFilesDir(), LOG_FILE_NAME), true)) {
            fos.write((line + "\n").getBytes());
        } catch (Exception ignored) {
        }
    }

    private void stopServer() {
        if (nodeProcess != null) {
            nodeProcess.destroy();
            nodeProcess = null;
        }
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
    }

    @Override
    public void onDestroy() {
        stopServer();
        super.onDestroy();
    }
}

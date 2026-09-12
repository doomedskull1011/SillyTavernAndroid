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
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Foreground service that runs one bundled Node.js process per started
 * SillyTavern instance, all sharing the same extracted payload with
 * per-instance --port/--dataRoot/--configPath arguments. Server output of
 * each instance is mirrored to its own log file so the UI can display
 * startup progress. A single persistent notification summarizes all
 * running instances and offers a stop-all action.
 */
public class ServerService extends Service {

    public static final String ACTION_START = "com.sillytavern.app.START";
    public static final String ACTION_STOP = "com.sillytavern.app.STOP";
    public static final String EXTRA_INSTANCE_ID = "instance_id";

    private static final String CHANNEL_ID = "server";
    private static final int NOTIFICATION_ID = 1;
    private static final String TAG = "ServerService";

    private static final Map<Integer, Process> processes = new ConcurrentHashMap<>();
    private static final Map<Integer, Integer> pids = new ConcurrentHashMap<>();
    private PowerManager.WakeLock wakeLock;
    private NotificationManager notificationManager;

    public static boolean isRunning(int instanceId) {
        Process p = processes.get(instanceId);
        return p != null && p.isAlive();
    }

    /** Ids of all currently running instances, sorted ascending. */
    public static ArrayList<Integer> runningIds() {
        ArrayList<Integer> ids = new ArrayList<>();
        for (Map.Entry<Integer, Process> e : processes.entrySet()) {
            if (e.getValue() != null && e.getValue().isAlive()) {
                ids.add(e.getKey());
            }
        }
        ids.sort(null);
        return ids;
    }

    public static int pidOf(int instanceId) {
        Integer pid = pids.get(instanceId);
        return pid == null ? -1 : pid;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            int id = intent.getIntExtra(EXTRA_INSTANCE_ID, -1);
            if (id > 0) {
                stopInstance(id);
            } else {
                stopAll();
            }
            if (runningIds().isEmpty()) {
                stopSelf();
            } else {
                updateNotification();
            }
            return START_NOT_STICKY;
        }
        int id = intent != null ? intent.getIntExtra(EXTRA_INSTANCE_ID, 1) : 1;
        startForegroundWithNotification();
        if (isRunning(id)) {
            return START_STICKY;
        }
        startInstance(id);
        return START_STICKY;
    }

    private void startForegroundWithNotification() {
        notificationManager = getSystemService(NotificationManager.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            notificationManager.createNotificationChannel(new NotificationChannel(
                    CHANNEL_ID, getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW));
        }
        Notification notification = buildNotification();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private Notification buildNotification() {
        Intent stopIntent = new Intent(this, ServerService.class).setAction(ACTION_STOP);
        PendingIntent stopPending = PendingIntent.getService(
                this, 0, stopIntent, PendingIntent.FLAG_IMMUTABLE);
        Intent openIntent = new Intent(this, ManagerActivity.class);
        PendingIntent openPending = PendingIntent.getActivity(
                this, 0, openIntent, PendingIntent.FLAG_IMMUTABLE);

        ArrayList<Integer> ids = runningIds();
        StringBuilder text = new StringBuilder();
        if (ids.isEmpty()) {
            text.append("Starting server...");
        } else {
            text.append(ids.size() == 1 ? "1 instance running" : ids.size() + " instances running");
            text.append(" · ");
            for (int i = 0; i < ids.size(); i++) {
                if (i > 0) {
                    text.append(", ");
                }
                text.append(":").append(InstanceManager.portOf(ids.get(i)));
            }
        }

        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        return builder
                .setContentTitle("ST-Manager")
                .setContentText(text.toString())
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentIntent(openPending)
                .addAction(new Notification.Action.Builder(null, "Stop all", stopPending).build())
                .setOngoing(true)
                .build();
    }

    private void updateNotification() {
        if (notificationManager != null) {
            notificationManager.notify(NOTIFICATION_ID, buildNotification());
        }
    }

    private void startInstance(int instanceId) {
        File payload = InstanceManager.payloadDir(this);
        if (!new File(payload, "server.js").isFile()) {
            writeLog(instanceId, "FATAL: payload missing, open ST-Manager to repair");
            stopSelf();
            return;
        }
        InstanceManager.Instance inst = InstanceManager.get(this, instanceId);
        if (inst == null) {
            writeLog(instanceId, "FATAL: instance " + instanceId + " is not registered");
            stopSelf();
            return;
        }
        try {
            InstanceManager.ensureInstanceDirs(this, inst);
        } catch (Exception e) {
            writeLog(instanceId, "FATAL: " + e.getMessage());
            stopSelf();
            return;
        }

        boolean gitAvailable = Env.ensureGitReady(this);
        if (!gitAvailable) {
            writeLog(instanceId, "WARNING: git runtime unavailable, using built-in JS git");
        }
        Env.writeGitConfig(this);

        // Scale the V8 heap to the device: 1/4 of total RAM, clamped to
        // [768, 2048] MB per instance. The cap only limits growth, it is
        // not pre-allocated, so several instances can coexist.
        int heapMb = computeNodeHeapMb();
        File dataRoot = InstanceManager.dataRoot(this, instanceId);
        File config = InstanceManager.configFile(this, instanceId);
        writeLog(instanceId, "Starting instance " + instanceId + " on port " + inst.port
                + " (max-old-space-size=" + heapMb + ")");

        ProcessBuilder pb = new ProcessBuilder(Env.nodeBinary(this),
                "--max-old-space-size=" + heapMb, "server.js",
                "--port", String.valueOf(inst.port),
                "--dataRoot", dataRoot.getAbsolutePath(),
                "--configPath", config.getAbsolutePath());
        pb.directory(payload);
        pb.redirectErrorStream(true);
        Env.apply(this, pb.environment(), gitAvailable);

        acquireWakeLock();

        Process proc;
        try {
            proc = pb.start();
        } catch (Exception e) {
            Log.e(TAG, "Failed to start node", e);
            writeLog(instanceId, "FATAL: cannot start node: " + e);
            if (runningIds().isEmpty()) {
                stopSelf();
            }
            return;
        }
        processes.put(instanceId, proc);
        pids.put(instanceId, resolvePid(proc, dataRoot));
        updateNotification();

        final Process nodeProcess = proc;
        Thread logThread = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(nodeProcess.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    Log.i(TAG, "[" + instanceId + "] " + line);
                    writeLog(instanceId, line);
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
                writeLog(instanceId, "Server process exited with code " + code);
            } catch (InterruptedException ignored) {
            }
            processes.remove(instanceId);
            pids.remove(instanceId);
            if (runningIds().isEmpty()) {
                releaseWakeLock();
                stopSelf();
            } else {
                updateNotification();
            }
        }).start();
    }

    /**
     * Resolves the OS pid of a spawned process. Android's ProcessImpl keeps
     * it in a private "pid" field; if reflection fails we scan /proc for a
     * node process whose cmdline references this instance's data root.
     */
    private int resolvePid(Process proc, File dataRoot) {
        try {
            Field f = proc.getClass().getDeclaredField("pid");
            f.setAccessible(true);
            int pid = f.getInt(proc);
            if (pid > 0) {
                return pid;
            }
        } catch (Exception ignored) {
        }
        try {
            String needle = dataRoot.getAbsolutePath();
            File procDir = new File("/proc");
            File[] entries = procDir.listFiles();
            if (entries != null) {
                for (File entry : entries) {
                    if (!Character.isDigit(entry.getName().charAt(0))) {
                        continue;
                    }
                    try {
                        byte[] buf = new byte[4096];
                        try (java.io.FileInputStream in =
                                     new java.io.FileInputStream(new File(entry, "cmdline"))) {
                            int n = in.read(buf);
                            if (n > 0 && new String(buf, 0, n).contains(needle)) {
                                return Integer.parseInt(entry.getName());
                            }
                        }
                    } catch (Exception ignored) {
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return -1;
    }

    private int computeNodeHeapMb() {
        try {
            ActivityManager am = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
            ActivityManager.MemoryInfo info = new ActivityManager.MemoryInfo();
            am.getMemoryInfo(info);
            long totalMb = info.totalMem / (1024 * 1024);
            return (int) Math.max(768, Math.min(2048, totalMb / 4));
        } catch (Exception e) {
            return 1024;
        }
    }

    private void writeLog(int instanceId, String line) {
        File log = InstanceManager.logFile(this, instanceId);
        log.getParentFile().mkdirs();
        try (FileOutputStream fos = new FileOutputStream(log, true)) {
            fos.write((line + "\n").getBytes());
        } catch (Exception ignored) {
        }
    }

    private void stopInstance(int instanceId) {
        Process p = processes.remove(instanceId);
        pids.remove(instanceId);
        if (p != null) {
            p.destroy();
        }
    }

    private void stopAll() {
        for (Integer id : new ArrayList<>(processes.keySet())) {
            stopInstance(id);
        }
        releaseWakeLock();
    }

    private void acquireWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) {
            return;
        }
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "sillytavern:server");
        wakeLock.acquire(12 * 60 * 60 * 1000L);
    }

    private void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
    }

    @Override
    public void onDestroy() {
        stopAll();
        super.onDestroy();
    }
}

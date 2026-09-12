package com.sillytavern.app;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Registry and lifecycle for SillyTavern instances. The SillyTavern server
 * code (payload) is shared by all instances; each instance owns only a data
 * root (characters, chats, settings), a config.yaml and a port. Instances
 * are persisted in files/instances.json. Each instance has a matching
 * disabled-by-default activity-alias in the manifest which is enabled when
 * the instance is created, producing a launcher icon (ST-Inst2, ...).
 * Instance 1 always exists and its alias ("SillyTavern") ships enabled.
 */
public class InstanceManager {

    public static final int MAX_INSTANCES = 6;
    public static final int BASE_PORT = 8000;
    public static final String DEFAULT_INSTANCE_NAME = "SillyTavern";

    private static final String TAG = "InstanceManager";
    private static final String REGISTRY_FILE = "instances.json";
    private static final String ST_VERSION_FILE = "payload.stversion";

    public static class Instance {
        public final int id;
        public String name;
        public final int port;
        public final long createdAt;

        Instance(int id, String name, long createdAt) {
            this.id = id;
            this.name = name;
            this.port = BASE_PORT + id - 1;
            this.createdAt = createdAt;
        }

        JSONObject toJson() throws Exception {
            JSONObject o = new JSONObject();
            o.put("id", id);
            o.put("name", name);
            o.put("createdAt", createdAt);
            return o;
        }
    }

    // ---------- paths ----------

    public static File payloadDir(Context context) {
        return new File(context.getFilesDir(), "payload");
    }

    public static File instancesRoot(Context context) {
        return new File(context.getFilesDir(), "instances");
    }

    public static File instanceDir(Context context, int id) {
        return new File(instancesRoot(context), "inst" + id);
    }

    public static File dataRoot(Context context, int id) {
        return new File(instanceDir(context, id), "data");
    }

    public static File configFile(Context context, int id) {
        return new File(instanceDir(context, id), "config.yaml");
    }

    public static File logFile(Context context, int id) {
        return new File(instanceDir(context, id), "server.log");
    }

    public static int portOf(int id) {
        return BASE_PORT + id - 1;
    }

    // ---------- registry ----------

    public static synchronized List<Instance> list(Context context) {
        migrateIfNeeded(context);
        ArrayList<Instance> out = new ArrayList<>();
        try {
            File f = new File(context.getFilesDir(), REGISTRY_FILE);
            if (!f.isFile()) {
                return out;
            }
            byte[] bytes = new byte[(int) f.length()];
            try (FileInputStream in = new FileInputStream(f)) {
                int off = 0, n;
                while ((n = in.read(bytes, off, bytes.length - off)) > 0) {
                    off += n;
                }
            }
            JSONArray arr = new JSONArray(new String(bytes, StandardCharsets.UTF_8));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                out.add(new Instance(o.getInt("id"), o.optString("name", "ST-Inst" + o.getInt("id")),
                        o.optLong("createdAt", 0)));
            }
        } catch (Exception e) {
            Log.e(TAG, "failed to read registry", e);
        }
        return out;
    }

    public static synchronized Instance get(Context context, int id) {
        for (Instance i : list(context)) {
            if (i.id == id) {
                return i;
            }
        }
        return null;
    }

    /**
     * Creates the next free instance (lowest free id in 1..MAX), seeds its
     * directories and config, and enables its launcher alias. Returns null
     * when the instance limit is reached.
     */
    public static synchronized Instance create(Context context) {
        List<Instance> existing = list(context);
        if (existing.size() >= MAX_INSTANCES) {
            return null;
        }
        boolean[] used = new boolean[MAX_INSTANCES + 1];
        for (Instance i : existing) {
            if (i.id >= 1 && i.id <= MAX_INSTANCES) {
                used[i.id] = true;
            }
        }
        int id = -1;
        for (int c = 1; c <= MAX_INSTANCES; c++) {
            if (!used[c]) {
                id = c;
                break;
            }
        }
        if (id < 0) {
            return null;
        }
        Instance inst = new Instance(id, "ST-Inst" + id, System.currentTimeMillis());
        existing.add(inst);
        try {
            ensureInstanceDirs(context, inst);
            save(context, existing);
            setAliasEnabled(context, id, true);
            return inst;
        } catch (Exception e) {
            Log.e(TAG, "failed to create instance", e);
            return null;
        }
    }

    /**
     * Deletes an instance's data and registry entry and removes its launcher
     * icon. Instance 1 is protected so the "SillyTavern" icon never dangles.
     * Caller must stop the instance's server first.
     */
    public static synchronized boolean delete(Context context, int id) {
        if (id == 1) {
            return false;
        }
        List<Instance> existing = list(context);
        Instance found = null;
        for (Instance i : existing) {
            if (i.id == id) {
                found = i;
                break;
            }
        }
        if (found == null) {
            return false;
        }
        existing.remove(found);
        try {
            save(context, existing);
        } catch (Exception e) {
            Log.e(TAG, "failed to save registry", e);
            return false;
        }
        setAliasEnabled(context, id, false);
        deleteRecursively(instanceDir(context, id));
        return true;
    }

    /**
     * Creates the on-disk layout for an instance if missing: data root and
     * a config.yaml pinned to the instance port with browser autolaunch off.
     */
    public static synchronized void ensureInstanceDirs(Context context, Instance inst) throws Exception {
        File dir = instanceDir(context, inst.id);
        File data = dataRoot(context, inst.id);
        if (!data.isDirectory() && !data.mkdirs()) {
            throw new Exception("cannot create " + data.getAbsolutePath());
        }
        File config = configFile(context, inst.id);
        if (!config.isFile()) {
            String yaml = "# Generated by ST-Manager. Edit via UI or delete to reset.\n"
                    + "port: " + inst.port + "\n"
                    + "listen: false\n"
                    + "autorun: false\n"
                    + "autorunHostname: 'auto'\n";
            try (OutputStream fos = new FileOutputStream(config)) {
                fos.write(yaml.getBytes(StandardCharsets.UTF_8));
            }
        }
    }

    /** The SillyTavern version string of the shared payload ("1.18.0" or "staging@abc1234"). */
    public static String payloadStVersion(Context context) {
        File f = new File(context.getFilesDir(), ST_VERSION_FILE);
        try {
            if (f.isFile()) {
                byte[] bytes = new byte[(int) f.length()];
                try (FileInputStream in = new FileInputStream(f)) {
                    int read = in.read(bytes);
                    if (read > 0) {
                        return new String(bytes, 0, read, StandardCharsets.UTF_8).trim();
                    }
                }
            }
        } catch (Exception ignored) {
        }
        // Derive from the bundled payload marker, e.g. "st-1.18.0-2" -> "1.18.0".
        String v = PayloadExtractor.PAYLOAD_VERSION;
        if (v.startsWith("st-")) {
            v = v.substring(3);
            int dash = v.lastIndexOf('-');
            if (dash > 0) {
                v = v.substring(0, dash);
            }
        }
        return v;
    }

    public static void writeStVersion(Context context, String version) throws Exception {
        try (OutputStream fos = new FileOutputStream(new File(context.getFilesDir(), ST_VERSION_FILE))) {
            fos.write(version.getBytes(StandardCharsets.UTF_8));
        }
    }

    // ---------- migration / first run ----------

    /**
     * One-time migration from the pre-manager layout: files/sillytavern
     * (code + data together) becomes files/payload (shared code) plus
     * instances/inst1/data (user data). Also registers instance 1 when no
     * registry exists yet (fresh installs included).
     */
    public static synchronized void migrateIfNeeded(Context context) {
        File filesDir = context.getFilesDir();
        File registry = new File(filesDir, REGISTRY_FILE);
        File legacy = new File(filesDir, "sillytavern");
        File payload = payloadDir(context);

        if (legacy.isDirectory() && !payload.isDirectory()) {
            Log.i(TAG, "migrating legacy layout");
            File legacyData = new File(legacy, "data");
            if (legacyData.isDirectory()) {
                File inst1Data = dataRoot(context, 1);
                inst1Data.getParentFile().mkdirs();
                if (!inst1Data.isDirectory() && !legacyData.renameTo(inst1Data)) {
                    try {
                        copyRecursively(legacyData, inst1Data);
                        deleteRecursively(legacyData);
                    } catch (Exception e) {
                        Log.e(TAG, "data migration failed", e);
                    }
                }
            }
            if (!legacy.renameTo(payload)) {
                Log.e(TAG, "could not rename legacy payload dir");
            }
        }

        if (!registry.isFile()) {
            ArrayList<Instance> initial = new ArrayList<>();
            initial.add(new Instance(1, DEFAULT_INSTANCE_NAME, System.currentTimeMillis()));
            try {
                Instance one = initial.get(0);
                if (payload.isDirectory()) {
                    ensureInstanceDirs(context, one);
                }
                save(context, initial);
                // Instance 1's alias ships enabled in the manifest.
            } catch (Exception e) {
                Log.e(TAG, "failed to initialize registry", e);
            }
        }
    }

    // ---------- internals ----------

    static void setAliasEnabled(Context context, int id, boolean enabled) {
        try {
            ComponentName alias = new ComponentName(context, "com.sillytavern.app.Inst" + id + "Alias");
            context.getPackageManager().setComponentEnabledSetting(alias,
                    enabled ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                            : PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP);
        } catch (Exception e) {
            Log.e(TAG, "failed to toggle alias for instance " + id, e);
        }
    }

    private static void save(Context context, List<Instance> instances) throws Exception {
        JSONArray arr = new JSONArray();
        for (Instance i : instances) {
            arr.put(i.toJson());
        }
        try (OutputStream fos = new FileOutputStream(new File(context.getFilesDir(), REGISTRY_FILE))) {
            fos.write(arr.toString().getBytes(StandardCharsets.UTF_8));
        }
    }

    static void copyRecursively(File src, File dst) throws Exception {
        if (src.isDirectory()) {
            dst.mkdirs();
            File[] children = src.listFiles();
            if (children != null) {
                for (File child : children) {
                    copyRecursively(child, new File(dst, child.getName()));
                }
            }
        } else {
            File parent = dst.getParentFile();
            if (parent != null) {
                parent.mkdirs();
            }
            try (FileInputStream in = new FileInputStream(src);
                 OutputStream out = new FileOutputStream(dst)) {
                byte[] buf = new byte[256 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                }
            }
        }
    }

    static void deleteRecursively(File f) {
        if (f == null || !f.exists()) {
            return;
        }
        if (f.isDirectory()) {
            File[] children = f.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursively(child);
                }
            }
        }
        f.delete();
    }
}

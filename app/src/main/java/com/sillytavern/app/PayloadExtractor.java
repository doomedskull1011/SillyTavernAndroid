package com.sillytavern.app;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Unpacks bundled asset zips (SillyTavern payload, git runtime) into
 * app-private storage. Extraction is parallelized across CPU cores with
 * random-access ZipFile reads. Marker files record versions so updated
 * zips re-extract on upgrade. The SillyTavern user data directory
 * (sillytavern/data) is preserved across payload upgrades.
 */
public class PayloadExtractor {

    public static final String PAYLOAD_VERSION = "st-1.18.0-2";
    public static final String GIT_VERSION = "git-2.55.0-1";
    private static final String MARKER = "payload.version";
    private static final String GIT_MARKER = "git.version";
    private static final String ZIP_NAME = "payload.zip";
    private static final String GIT_ZIP_NAME = "git.zip";

    private static final int COPY_BUFFER = 256 * 1024;

    public interface ProgressListener {
        void onProgress(int filesDone, int filesTotal, String currentFile);
    }

    public static boolean isExtracted(Context context) {
        return markerMatches(new File(context.getFilesDir(), MARKER), PAYLOAD_VERSION);
    }

    public static boolean isGitExtracted(Context context) {
        return markerMatches(new File(context.getFilesDir(), GIT_MARKER), GIT_VERSION)
                && new File(context.getFilesDir(), "git/etc/tls/cert.pem").isFile()
                && new File(context.getFilesDir(), "git/share/git-core/templates").isDirectory();
    }

    private static boolean markerMatches(File marker, String expected) {
        if (!marker.isFile()) {
            return false;
        }
        try {
            byte[] bytes = new byte[(int) marker.length()];
            try (InputStream fis = new java.io.FileInputStream(marker)) {
                int read = fis.read(bytes);
                if (read <= 0) return false;
            }
            return expected.equals(new String(bytes, StandardCharsets.UTF_8).trim());
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Extracts the main SillyTavern payload, preserving an existing
     * sillytavern/data directory (user characters, chats, settings).
     * A pre-baked webpack cache shipped inside the payload's data dir is
     * merged into the preserved data dir.
     */
    public static void extract(Context context, ProgressListener listener) throws Exception {
        File filesDir = context.getFilesDir();
        File stDir = new File(filesDir, "sillytavern");
        File existingData = new File(stDir, "data");
        File savedData = new File(filesDir, ".data-backup");
        File freshWebpackTmp = new File(filesDir, ".webpack-tmp");

        boolean hasUserData = existingData.isDirectory() && containsUserContent(existingData);

        if (hasUserData) {
            deleteRecursively(savedData);
            if (!existingData.renameTo(savedData)) {
                copyRecursively(existingData, savedData);
                deleteRecursively(existingData);
            }
        }

        deleteRecursively(stDir);
        stDir.mkdirs();
        extractAssetZip(context, ZIP_NAME, filesDir, null, null, null, listener);

        if (hasUserData) {
            // Keep the pre-baked webpack cache from the fresh payload, but
            // restore everything else from the user's data directory.
            File freshWebpack = new File(stDir, "data/_webpack");
            File restoredData = new File(stDir, "data");
            deleteRecursively(freshWebpackTmp);
            if (freshWebpack.isDirectory()
                    && !freshWebpack.renameTo(freshWebpackTmp)) {
                copyRecursively(freshWebpack, freshWebpackTmp);
                deleteRecursively(freshWebpack);
            }
            deleteRecursively(restoredData);
            if (!savedData.renameTo(restoredData)) {
                copyRecursively(savedData, restoredData);
                deleteRecursively(savedData);
            }
            if (freshWebpackTmp.isDirectory()) {
                File targetWebpack = new File(restoredData, "_webpack");
                deleteRecursively(targetWebpack);
                if (!freshWebpackTmp.renameTo(targetWebpack)) {
                    copyRecursively(freshWebpackTmp, targetWebpack);
                    deleteRecursively(freshWebpackTmp);
                }
            }
        }

        writeDefaultConfig(stDir);
        writeMarker(new File(filesDir, MARKER), PAYLOAD_VERSION);
        if (listener != null) {
            listener.onProgress(-1, -1, "done");
        }
    }

    /**
     * Extracts the git runtime (bin/, libexec/, share/, etc/) into
     * filesDir/git and marks binaries executable.
     */
    public static void extractGit(Context context) throws Exception {
        File filesDir = context.getFilesDir();
        File gitDir = new File(filesDir, "git");
        deleteRecursively(gitDir);
        gitDir.mkdirs();
        extractAssetZip(context, GIT_ZIP_NAME, gitDir, null, null,
                new String[]{"bin/", "libexec/"}, null);
        writeMarker(new File(filesDir, GIT_MARKER), GIT_VERSION);
    }

    /**
     * Streams an asset to a temp file, then unpacks it with a thread pool
     * using random-access ZipFile (much faster than a single sequential
     * ZipInputStream pass for tens of thousands of small files).
     */
    private static void extractAssetZip(Context context, String assetName, File destDir,
                                        String markerName, String markerVersion,
                                        String[] executablePrefixes, ProgressListener listener) throws Exception {
        File tmp = new File(context.getCacheDir(), assetName + ".tmp");
        try {
            try (InputStream in = context.getAssets().open(assetName);
                 OutputStream out = new FileOutputStream(tmp)) {
                byte[] buf = new byte[COPY_BUFFER];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                }
            }

            final String rootPath = destDir.getCanonicalPath();
            try (ZipFile zip = new ZipFile(tmp)) {
                List<? extends ZipEntry> entries = Collections.list(zip.entries());
                final int total = entries.size();
                final AtomicInteger done = new AtomicInteger(0);
                final ConcurrentLinkedQueue<ZipEntry> queue = new ConcurrentLinkedQueue<ZipEntry>(entries);
                int threads = Math.max(2, Math.min(4, Runtime.getRuntime().availableProcessors()));
                ExecutorService pool = Executors.newFixedThreadPool(threads);
                List<Future<?>> futures = new ArrayList<>();

                final java.util.concurrent.atomic.AtomicReference<Exception> failure =
                        new java.util.concurrent.atomic.AtomicReference<>();
                for (int i = 0; i < threads; i++) {
                    futures.add(pool.submit(() -> {
                        byte[] buf = new byte[COPY_BUFFER];
                        ZipEntry entry;
                        while ((entry = queue.poll()) != null) {
                            if (entry.isDirectory()) {
                                done.incrementAndGet();
                                continue;
                            }
                            try {
                                File out = new File(destDir, entry.getName());
                                if (!out.getCanonicalPath().startsWith(rootPath)) {
                                    continue; // zip-slip guard
                                }
                                File parent = out.getParentFile();
                                if (parent != null) {
                                    parent.mkdirs();
                                }
                                try (InputStream in = zip.getInputStream(entry);
                                     OutputStream fos = new FileOutputStream(out)) {
                                    int n;
                                    while ((n = in.read(buf)) > 0) {
                                        fos.write(buf, 0, n);
                                    }
                                }
                                if (needsExec(entry.getName(), executablePrefixes)) {
                                    out.setExecutable(true, false);
                                    out.setReadable(true, false);
                                }
                                int nDone = done.incrementAndGet();
                                if (listener != null && (nDone % 250 == 0 || nDone == total)) {
                                    listener.onProgress(nDone, total, entry.getName());
                                }
                            } catch (Exception ex) {
                                failure.compareAndSet(null, ex);
                                break;
                            }
                        }
                    }));
                }
                for (Future<?> f : futures) {
                    f.get();
                }
                pool.shutdown();
                if (failure.get() != null) {
                    throw failure.get();
                }
            }
        } finally {
            tmp.delete();
        }

        if (markerName != null && markerVersion != null) {
            writeMarker(new File(context.getFilesDir(), markerName), markerVersion);
        }
    }

    private static boolean needsExec(String name, String[] prefixes) {
        if (prefixes == null) {
            return false;
        }
        for (String p : prefixes) {
            if (name.startsWith(p)) {
                return true;
            }
        }
        return false;
    }

    /**
     * A data dir counts as "user content" if it has anything beyond the
     * pristine placeholders (.gitkeep and the pre-baked _webpack cache).
     */
    private static boolean containsUserContent(File dataDir) {
        File[] children = dataDir.listFiles();
        if (children == null) {
            return false;
        }
        for (File child : children) {
            String name = child.getName();
            if (!".gitkeep".equals(name) && !"_webpack".equals(name)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Pre-seed a minimal config.yaml: never auto-open a browser (there is
     * none usable on Android), keep the server localhost-only on port 8000.
     */
    private static void writeDefaultConfig(File stDir) throws Exception {
        File config = new File(stDir, "config.yaml");
        if (config.exists()) {
            return;
        }
        String yaml = "# Generated by the Android wrapper. Edit via UI or delete to reset.\n"
                + "port: 8000\n"
                + "listen: false\n"
                + "autorun: false\n"
                + "autorunHostname: 'auto'\n";
        try (OutputStream fos = new FileOutputStream(config)) {
            fos.write(yaml.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static void writeMarker(File marker, String version) throws Exception {
        try (OutputStream fos = new FileOutputStream(marker)) {
            fos.write(version.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static void copyRecursively(File src, File dst) throws Exception {
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
            try (InputStream in = new java.io.FileInputStream(src);
                 OutputStream out = new FileOutputStream(dst)) {
                byte[] buf = new byte[COPY_BUFFER];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                }
            }
        }
    }

    private static void deleteRecursively(File f) {
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

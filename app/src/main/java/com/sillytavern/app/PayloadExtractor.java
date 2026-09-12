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
 * Unpacks bundled asset zips (SillyTavern payload, git runtime, npm
 * runtime) into app-private storage. Extraction is parallelized across CPU
 * cores with random-access ZipFile reads. Marker files record versions so
 * updated zips re-extract on upgrade.
 *
 * The payload is extracted once as the SHARED server code for all
 * instances (files/payload). User data lives per instance under
 * files/instances, so payload upgrades never touch user data.
 */
public class PayloadExtractor {

    public static final String PAYLOAD_VERSION = "st-1.18.0-2";
    public static final String GIT_VERSION = "git-2.55.0-1";
    public static final String NPM_VERSION = "npm-11.7.0-1";
    private static final String MARKER = "payload.version";
    private static final String GIT_MARKER = "git.version";
    private static final String NPM_MARKER = "npm.version";
    private static final String ZIP_NAME = "payload.zip";
    private static final String GIT_ZIP_NAME = "git.zip";
    private static final String NPM_ZIP_NAME = "npm.zip";

    private static final int COPY_BUFFER = 256 * 1024;

    public interface ProgressListener {
        void onProgress(int filesDone, int filesTotal, String currentFile);
    }

    public static boolean isExtracted(Context context) {
        return markerMatches(new File(context.getFilesDir(), MARKER), PAYLOAD_VERSION)
                && new File(InstanceManager.payloadDir(context), "server.js").isFile();
    }

    public static boolean isGitExtracted(Context context) {
        return markerMatches(new File(context.getFilesDir(), GIT_MARKER), GIT_VERSION)
                && new File(context.getFilesDir(), "git/etc/tls/cert.pem").isFile()
                && new File(context.getFilesDir(), "git/share/git-core/templates").isDirectory();
    }

    public static boolean isNpmExtracted(Context context) {
        return markerMatches(new File(context.getFilesDir(), NPM_MARKER), NPM_VERSION)
                && new File(context.getFilesDir(), "npm/bin/npm-cli.js").isFile();
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
     * Extracts the main SillyTavern payload as the shared server code at
     * files/payload. The zip has a top-level "sillytavern/" folder which is
     * renamed to "payload" after extraction. User data is stored per
     * instance elsewhere, so no data preservation is needed here.
     */
    public static void extract(Context context, ProgressListener listener) throws Exception {
        File filesDir = context.getFilesDir();
        File legacyDir = new File(filesDir, "sillytavern");
        File payloadDir = InstanceManager.payloadDir(context);

        InstanceManager.deleteRecursively(payloadDir);
        InstanceManager.deleteRecursively(legacyDir);
        extractAssetZip(context, ZIP_NAME, filesDir, null, null, null, listener);

        if (!legacyDir.renameTo(payloadDir)) {
            throw new Exception("could not move extracted payload into place");
        }
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
        InstanceManager.deleteRecursively(gitDir);
        gitDir.mkdirs();
        extractAssetZip(context, GIT_ZIP_NAME, gitDir, null, null,
                new String[]{"bin/", "libexec/"}, null);
        writeMarker(new File(filesDir, GIT_MARKER), GIT_VERSION);
    }

    /**
     * Extracts the npm CLI runtime (npm/bin/npm-cli.js and its node_modules)
     * used by the on-device updater. Extracted lazily on first update.
     */
    public static void extractNpm(Context context) throws Exception {
        File filesDir = context.getFilesDir();
        File npmDir = new File(filesDir, "npm");
        InstanceManager.deleteRecursively(npmDir);
        extractAssetZip(context, NPM_ZIP_NAME, filesDir, null, null, null, null);
        writeMarker(new File(filesDir, NPM_MARKER), NPM_VERSION);
    }

    /**
     * Extracts an arbitrary zip file (e.g. a SillyTavern backup picked via
     * the system file picker) into destDir with a zip-slip guard.
     */
    public static void extractZipFile(File zipFile, File destDir, ProgressListener listener) throws Exception {
        destDir.mkdirs();
        final String rootPath = destDir.getCanonicalPath();
        try (ZipFile zip = new ZipFile(zipFile)) {
            List<? extends ZipEntry> entries = Collections.list(zip.entries());
            final int total = entries.size();
            int done = 0;
            byte[] buf = new byte[COPY_BUFFER];
            for (ZipEntry entry : entries) {
                if (entry.isDirectory()) {
                    continue;
                }
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
                done++;
                if (listener != null && (done % 100 == 0 || done == total)) {
                    listener.onProgress(done, total, entry.getName());
                }
            }
        }
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

    private static void writeMarker(File marker, String version) throws Exception {
        try (OutputStream fos = new FileOutputStream(marker)) {
            fos.write(version.getBytes(StandardCharsets.UTF_8));
        }
    }
}

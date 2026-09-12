package com.sillytavern.app;

import android.content.Context;
import android.content.Intent;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * On-device updater for the shared SillyTavern payload.
 *
 * ST_LATEST / ST_STAGING turn the payload directory into a shallow git
 * checkout of upstream SillyTavern (lazily: git init + remote add on first
 * use, so no .git ships in the APK), fetch the target ref (latest GitHub
 * release tag or the staging branch head), hard-checkout it and refresh
 * node_modules with the bundled npm CLI. config.yaml and instance data
 * live outside the payload or are gitignored, so user data survives.
 *
 * PACKAGES only refreshes node_modules. REPAIR restores the bundled
 * payload from the APK as a known-good fallback.
 */
public class StUpdater {

    private static final String UPSTREAM = "https://github.com/SillyTavern/SillyTavern.git";
    private static final String API_LATEST =
            "https://api.github.com/repos/SillyTavern/SillyTavern/releases/latest";

    public enum Mode {ST_LATEST, ST_STAGING, PACKAGES, REPAIR}

    public interface Logger {
        void log(String line);
    }

    /** Queries GitHub for the latest upstream release tag; null on failure. */
    public static String fetchLatestReleaseTag() {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(API_LATEST).openConnection();
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);
            conn.setRequestProperty("User-Agent", "ST-Manager-Android");
            conn.setRequestProperty("Accept", "application/vnd.github+json");
            if (conn.getResponseCode() != 200) {
                return null;
            }
            StringBuilder sb = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(conn.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line);
                }
            }
            return new JSONObject(sb.toString()).optString("tag_name", null);
        } catch (Exception e) {
            return null;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    /** Runs the update. Blocks; call from a background thread. */
    public static void run(Context context, Mode mode, Logger log) throws Exception {
        stopAllInstances(context, log);

        File payload = InstanceManager.payloadDir(context);
        if (mode == Mode.REPAIR) {
            log.log("Restoring bundled payload " + PayloadExtractor.PAYLOAD_VERSION + "...");
            PayloadExtractor.extract(context, (done, total, current) -> {
                if (done > 0) {
                    log.log("Extracting... " + (done * 100 / Math.max(total, 1)) + "%");
                }
            });
            InstanceManager.writeStVersion(context, InstanceManager.payloadStVersion(context));
            log.log("Repair complete. Bundled SillyTavern restored.");
            return;
        }

        if (!Env.ensureGitReady(context)) {
            throw new Exception("git runtime is unavailable on this device");
        }
        Env.writeGitConfig(context);
        if (!PayloadExtractor.isNpmExtracted(context)) {
            log.log("Preparing npm runtime...");
            PayloadExtractor.extractNpm(context);
        }
        if (!new File(payload, "server.js").isFile()) {
            throw new Exception("payload missing; run Repair payload first");
        }

        String targetRef;
        String versionLabel;
        if (mode == Mode.ST_LATEST) {
            log.log("Checking GitHub for the latest release...");
            String tag = fetchLatestReleaseTag();
            if (tag == null) {
                throw new Exception("could not query the GitHub releases API");
            }
            log.log("Latest release: " + tag);
            targetRef = "tag";
            versionLabel = tag;
        } else if (mode == Mode.ST_STAGING) {
            targetRef = "staging";
            versionLabel = null; // resolved after checkout
        } else {
            targetRef = null;
            versionLabel = null;
        }

        if (targetRef != null) {
            ensureGitRepo(context, payload, log);
            if ("tag".equals(targetRef)) {
                runGit(context, payload, log, "fetch", "--depth", "1", "--force", "origin",
                        "tag", versionLabel);
            } else {
                runGit(context, payload, log, "fetch", "--depth", "1", "--force", "origin", "staging");
            }
            runGit(context, payload, log, "checkout", "--force", "FETCH_HEAD");
            if (versionLabel == null) {
                versionLabel = "staging@" + runGitCapture(context, payload, "rev-parse", "--short", "HEAD");
            }
        }

        runNpmInstall(context, payload, log);

        if (versionLabel != null) {
            InstanceManager.writeStVersion(context, versionLabel);
            log.log("SillyTavern is now at " + versionLabel);
        }
        log.log("Update finished. Start an instance to use it.");
    }

    private static void stopAllInstances(Context context, Logger log) throws Exception {
        if (ServerService.runningIds().isEmpty()) {
            return;
        }
        log.log("Stopping all instances...");
        context.startService(new Intent(context, ServerService.class).setAction(ServerService.ACTION_STOP));
        long deadline = System.currentTimeMillis() + 30_000;
        while (!ServerService.runningIds().isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(500);
        }
        if (!ServerService.runningIds().isEmpty()) {
            throw new Exception("instances did not stop in time");
        }
    }

    private static void ensureGitRepo(Context context, File payload, Logger log) throws Exception {
        File dotGit = new File(payload, ".git");
        if (dotGit.isDirectory()) {
            runGit(context, payload, log, "remote", "set-url", "origin", UPSTREAM);
            return;
        }
        log.log("Initializing git repository (one-time)...");
        runGit(context, payload, log, "init");
        runGit(context, payload, log, "remote", "add", "origin", UPSTREAM);
    }

    private static void runGit(Context context, File payload, Logger log, String... args) throws Exception {
        String[] cmd = new String[args.length + 1];
        cmd[0] = new File(context.getFilesDir(), "git-bin/git").getAbsolutePath();
        System.arraycopy(args, 0, cmd, 1, args.length);
        int code = runProcess(context, payload, cmd, log);
        if (code != 0) {
            throw new Exception("git " + args[0] + " failed (exit " + code + ")");
        }
    }

    private static String runGitCapture(Context context, File payload, String... args) throws Exception {
        String[] cmd = new String[args.length + 1];
        cmd[0] = new File(context.getFilesDir(), "git-bin/git").getAbsolutePath();
        System.arraycopy(args, 0, cmd, 1, args.length);
        StringBuilder sb = new StringBuilder();
        int code = runProcess(context, payload, cmd, sb::append);
        if (code != 0) {
            throw new Exception("git " + args[0] + " failed (exit " + code + ")");
        }
        return sb.toString().trim();
    }

    private static void runNpmInstall(Context context, File payload, Logger log) throws Exception {
        log.log("Installing dependencies (npm install, can take several minutes)...");
        String npmCli = new File(context.getFilesDir(), "npm/bin/npm-cli.js").getAbsolutePath();
        int code = runProcess(context, payload,
                new String[]{Env.nodeBinary(context), npmCli, "install",
                        "--no-audit", "--no-fund", "--loglevel=error", "--omit=dev"},
                log);
        if (code != 0) {
            throw new Exception("npm install failed (exit " + code + ")");
        }
    }

    private static int runProcess(Context context, File cwd, String[] cmd, Logger logger) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(cwd);
        pb.redirectErrorStream(true);
        Env.apply(context, pb.environment(), true);
        Process proc = pb.start();
        StringBuilder throttled = new StringBuilder();
        long lastLog = 0;
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(proc.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                // npm/git progress lines can be extremely chatty; throttle.
                long now = System.currentTimeMillis();
                if (now - lastLog > 300) {
                    logger.log(line);
                    lastLog = now;
                } else {
                    throttled.setLength(0);
                    throttled.append(line);
                }
            }
        }
        if (throttled.length() > 0) {
            logger.log(throttled.toString());
        }
        return proc.waitFor();
    }
}

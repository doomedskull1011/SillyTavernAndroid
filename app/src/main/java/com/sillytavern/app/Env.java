package com.sillytavern.app;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Shared process environment for anything that runs the bundled Node.js
 * runtime: the SillyTavern server (ServerService) and the update tooling
 * (StUpdater, which shells out to git and npm). Centralizes the git
 * runtime extraction/symlinking and the environment variables so all
 * callers get an identical setup.
 */
public class Env {

    private static final String TAG = "Env";
    private static Boolean gitReady;

    /**
     * Ensures the git runtime data is extracted and the git executables
     * (packaged as lib*.so in the executable nativeLibraryDir) are linked
     * into filesDir/git-bin. Cached per process. Returns true when git is
     * usable; failure is non-fatal for the server (SillyTavern falls back
     * to its pure-JS git) but fatal for the updater.
     */
    public static synchronized boolean ensureGitReady(Context context) {
        if (gitReady != null) {
            return gitReady;
        }
        boolean ok = false;
        try {
            if (!PayloadExtractor.isGitExtracted(context)) {
                PayloadExtractor.extractGit(context);
            }
            File gitBinDir = new File(context.getFilesDir(), "git-bin");
            ok = linkGitBinaries(gitBinDir, context.getApplicationInfo().nativeLibraryDir);
            if (!ok) {
                // Re-extract once and retry: a stale/corrupt git-bin or
                // git data dir should not permanently disable updates.
                Log.w(TAG, "git link failed, re-extracting git runtime");
                PayloadExtractor.extractGit(context);
                ok = linkGitBinaries(gitBinDir, context.getApplicationInfo().nativeLibraryDir);
            }
        } catch (Exception e) {
            Log.e(TAG, "git runtime setup failed", e);
        }
        // Only cache success; a failure may be fixed by a later retry.
        gitReady = ok ? Boolean.TRUE : null;
        return ok;
    }

    /**
     * Applies the common environment to a ProcessBuilder: HOME, TMPDIR,
     * LD_LIBRARY_PATH, PATH and (when git is usable) the GIT_* variables.
     * Callers must have run ensureGitReady() first if they want git.
     */
    public static void apply(Context context, Map<String, String> env, boolean withGit) {
        File appFiles = context.getFilesDir();
        String nativeLibDir = context.getApplicationInfo().nativeLibraryDir;
        env.put("HOME", appFiles.getAbsolutePath());
        env.put("TMPDIR", context.getCacheDir().getAbsolutePath());
        env.put("LD_LIBRARY_PATH", nativeLibDir);
        String basePath = "/system/bin:/system/xbin";
        if (withGit) {
            File gitDir = new File(appFiles, "git");
            File gitBinDir = new File(appFiles, "git-bin");
            env.put("PATH", gitBinDir.getAbsolutePath() + ":" + basePath);
            env.put("GIT_EXEC_PATH", gitBinDir.getAbsolutePath());
            env.put("GIT_TEMPLATE_DIR", new File(gitDir, "share/git-core/templates").getAbsolutePath());
            env.put("GIT_SSL_CAINFO", new File(gitDir, "etc/tls/cert.pem").getAbsolutePath());
            env.put("GIT_CONFIG_NOSYSTEM", "1");
            env.put("GIT_TERMINAL_PROMPT", "0");
        } else {
            env.put("PATH", basePath);
        }
    }

    /** Absolute path of the bundled Node.js executable. */
    public static String nodeBinary(Context context) {
        return new File(context.getApplicationInfo().nativeLibraryDir, "libnodeexec.so").getAbsolutePath();
    }

    /**
     * Pre-seed a global git identity so operations that need one (e.g.
     * merges during "git pull") never fail on a missing config.
     */
    public static void writeGitConfig(Context context) {
        File gitconfig = new File(context.getFilesDir(), ".gitconfig");
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

    /**
     * Creates gitBinDir with symlinks named after the git executables,
     * pointing at the lib*.so copies in nativeLibraryDir. Returns true when
     * all links resolve to existing executable targets.
     */
    private static boolean linkGitBinaries(File gitBinDir, String nativeLibDir) {
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
                Log.w(TAG, "missing git binary " + target.getAbsolutePath());
                return false;
            }
            try {
                // Unconditionally unlink first. File.exists() follows
                // symlinks, so it misses DANGLING links — e.g. left over
                // from a previous APK install whose nativeLibraryDir path
                // changed after an app update. delete() uses unlink(2) and
                // removes the link itself regardless of its target.
                symlink.delete();
                android.system.Os.symlink(target.getAbsolutePath(), symlink.getAbsolutePath());
            } catch (Exception e) {
                Log.e(TAG, "symlink failed for " + link[0], e);
                return false;
            }
            if (!symlink.canExecute()) {
                Log.w(TAG, "git symlink not executable: " + symlink.getAbsolutePath());
                return false;
            }
        }
        return true;
    }
}

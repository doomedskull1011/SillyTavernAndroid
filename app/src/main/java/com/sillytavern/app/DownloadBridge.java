package com.sillytavern.app;

import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.Base64;
import android.util.Base64InputStream;
import android.webkit.JavascriptInterface;
import android.widget.Toast;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * JavaScript bridge that lets the SillyTavern web app save downloads
 * (character cards, backups, presets...) to the public Downloads folder.
 * WebViews cannot download blob:/data: URLs natively, so the page-side
 * script (INJECT_JS) intercepts anchor downloads and streams the data
 * here in base64 chunks. Plain http(s) downloads are handled by
 * MainActivity's DownloadListener and funneled through saveStream().
 */
public class DownloadBridge {

    private final Context context;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();

    private OutputStream pendingOut;
    private File pendingTmp;
    private String pendingName;
    private String pendingMime;

    public DownloadBridge(Context context) {
        this.context = context.getApplicationContext();
    }

    @JavascriptInterface
    public void beginDownload(String filename, String mime) {
        io.execute(() -> {
            abortPendingQuietly();
            try {
                pendingTmp = new File(context.getCacheDir(), "dl-" + System.currentTimeMillis());
                pendingOut = new FileOutputStream(pendingTmp);
                pendingName = sanitizeFilename(filename);
                pendingMime = (mime == null || mime.isEmpty()) ? "application/octet-stream" : mime;
            } catch (Exception e) {
                toast("Download failed: " + e.getMessage());
            }
        });
    }

    @JavascriptInterface
    public void appendDownload(String base64Chunk) {
        io.execute(() -> {
            if (pendingOut == null) {
                return;
            }
            try (InputStream in = new Base64InputStream(
                    new ByteArrayInputStream(base64Chunk.getBytes("US-ASCII")), Base64.DEFAULT)) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) {
                    pendingOut.write(buf, 0, n);
                }
            } catch (Exception e) {
                toast("Download failed: " + e.getMessage());
                abortPendingQuietly();
            }
        });
    }

    @JavascriptInterface
    public void finishDownload() {
        io.execute(() -> {
            if (pendingOut == null) {
                return;
            }
            try {
                pendingOut.close();
                pendingOut = null;
                String name = pendingName;
                String mime = pendingMime;
                try (InputStream in = new java.io.FileInputStream(pendingTmp)) {
                    Uri where = saveStream(context, in, name, mime);
                    toast("Saved: " + (where != null ? "Downloads/SillyTavern/" + name : "failed"));
                }
            } catch (Exception e) {
                toast("Download failed: " + e.getMessage());
            } finally {
                abortPendingQuietly();
            }
        });
    }

    @JavascriptInterface
    public void abortDownload() {
        io.execute(this::abortPendingQuietly);
    }

    private void abortPendingQuietly() {
        try {
            if (pendingOut != null) {
                pendingOut.close();
            }
        } catch (Exception ignored) {
        }
        pendingOut = null;
        if (pendingTmp != null) {
            pendingTmp.delete();
        }
        pendingTmp = null;
    }

    static String sanitizeFilename(String name) {
        if (name == null || name.isEmpty()) {
            return "download";
        }
        return name.replaceAll("[\\\\/:*?\"<>|]", "_");
    }

    /**
     * Writes a stream into Downloads/SillyTavern. On Android 10+ this uses
     * MediaStore (no permission needed); on older versions it uses the
     * public Downloads dir when permitted, otherwise the app-external dir.
     *
     * @return content Uri on success, null on failure
     */
    public static Uri saveStream(Context context, InputStream in, String filename, String mime) {
        filename = sanitizeFilename(filename);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContentValues values = new ContentValues();
                values.put(MediaStore.Downloads.DISPLAY_NAME, filename);
                values.put(MediaStore.Downloads.MIME_TYPE, mime);
                values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/SillyTavern");
                Uri uri = context.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                if (uri == null) {
                    return null;
                }
                try (OutputStream out = context.getContentResolver().openOutputStream(uri)) {
                    if (out == null) {
                        return null;
                    }
                    copy(in, out);
                }
                return uri;
            }
            File dir = null;
            if (Environment.MEDIA_MOUNTED.equals(Environment.getExternalStorageState())) {
                File pub = new File(Environment.getExternalStoragePublicDirectory(
                        Environment.DIRECTORY_DOWNLOADS), "SillyTavern");
                if (pub.mkdirs() || pub.isDirectory()) {
                    if (pub.canWrite()) {
                        dir = pub;
                    }
                }
                if (dir == null) {
                    dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
                }
            }
            if (dir == null) {
                return null;
            }
            File out = new File(dir, filename);
            try (OutputStream fos = new FileOutputStream(out)) {
                copy(in, fos);
            }
            return Uri.fromFile(out);
        } catch (Exception e) {
            return null;
        }
    }

    private static void copy(InputStream in, OutputStream out) throws Exception {
        byte[] buf = new byte[64 * 1024];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
        }
    }

    private void toast(String text) {
        mainHandler.post(() -> Toast.makeText(context, text, Toast.LENGTH_LONG).show());
    }

    /**
     * Injected into every page. Intercepts programmatic and user clicks on
     * anchors with a download attribute pointing at blob: or data: URLs and
     * routes the content to AndroidBridge. Also wraps
     * URL.createObjectURL so the originating Blob stays reachable.
     */
    public static final String INJECT_JS =
            "(function(){"
            + "if(window.__stDlBridge)return;window.__stDlBridge=true;"
            + "if(!window.AndroidBridge)return;"
            + "var blobMap=new Map();"
            + "var oc=URL.createObjectURL.bind(URL);"
            + "URL.createObjectURL=function(b){var u=oc(b);if(b instanceof Blob)blobMap.set(u,b);return u;};"
            + "var orv=URL.revokeObjectURL.bind(URL);"
            + "URL.revokeObjectURL=function(u){blobMap.delete(u);orv(u);};"
            + "function sendBlob(blob,name){"
            + "  var CH=4*1024*1024,off=0;"
            + "  AndroidBridge.beginDownload(name,blob.type||'application/octet-stream');"
            + "  var reader=new FileReader();"
            + "  function step(){"
            + "    if(off>=blob.size){AndroidBridge.finishDownload();return;}"
            + "    var slice=blob.slice(off,off+CH);off+=CH;"
            + "    reader.onload=function(){"
            + "      var b64=String(reader.result).split(',')[1]||'';"
            + "      AndroidBridge.appendDownload(b64);step();};"
            + "    reader.onerror=function(){AndroidBridge.abortDownload();};"
            + "    reader.readAsDataURL(slice);}"
            + "  step();}"
            + "function sendDataUrl(href,name){"
            + "  var m=/^data:([^;,]*)(;base64)?,([\\s\\S]*)$/.exec(href);if(!m)return;"
            + "  AndroidBridge.beginDownload(name,m[1]||'application/octet-stream');"
            + "  try{"
            + "    if(m[2]){AndroidBridge.appendDownload(m[3]);}"
            + "    else{AndroidBridge.appendDownload(btoa(unescape(encodeURIComponent(decodeURIComponent(m[3])))));}"
            + "    AndroidBridge.finishDownload();"
            + "  }catch(e){AndroidBridge.abortDownload();}}"
            + "function handle(a){"
            + "  if(!a||!a.getAttribute)return false;"
            + "  var href=a.getAttribute('href')||'';var name=a.getAttribute('download')||'download';"
            + "  if(href.indexOf('blob:')===0){var b=blobMap.get(href);if(b){sendBlob(b,name);return true;}}"
            + "  if(href.indexOf('data:')===0){sendDataUrl(href,name);return true;}"
            + "  return false;}"
            + "document.addEventListener('click',function(e){"
            + "  var t=e.target;while(t&&t.tagName!=='A')t=t.parentElement;"
            + "  if(t&&t.hasAttribute('download')&&handle(t)){e.preventDefault();e.stopPropagation();}"
            + "},true);"
            + "var origClick=HTMLAnchorElement.prototype.click;"
            + "HTMLAnchorElement.prototype.click=function(){"
            + "  if(this.hasAttribute&&this.hasAttribute('download')&&handle(this))return;"
            + "  return origClick.apply(this,arguments);};"
            + "})();";
}

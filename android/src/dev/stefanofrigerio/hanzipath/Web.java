package dev.stefanofrigerio.hanzipath;

import android.content.Context;
import android.webkit.WebResourceResponse;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * The web app's files: files/web/<version>/ when the updater has put a newer copy there
 * (files/web/current names it), else the APK's assets/web/. A copy is whole or not there:
 * the updater unpacks to a temporary folder and renames it.
 */
class Web {
    final Context ctx;

    Web(Context ctx) { this.ctx = ctx; }

    static File base(Context ctx) { return new File(ctx.getFilesDir(), "web"); }

    /** The version in use: the downloaded copy's when it's newer than the APK's own. */
    static int version(Context ctx) { return Math.max(bundled(ctx), downloaded(ctx)); }

    static int bundled(Context ctx) {
        try (InputStream in = ctx.getAssets().open("web/version.txt")) {
            return Integer.parseInt(Updater.text(in).trim());
        } catch (Exception e) {
            return 0;
        }
    }

    static int downloaded(Context ctx) {
        try (InputStream in = new FileInputStream(new File(base(ctx), "current"))) {
            int v = Integer.parseInt(Updater.text(in).trim());
            return new File(base(ctx), String.valueOf(v)).isDirectory() ? v : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    static final Map<String, String> TYPES = new HashMap<>();
    static {
        TYPES.put("html", "text/html");
        TYPES.put("js", "text/javascript");
        TYPES.put("css", "text/css");
        TYPES.put("json", "application/json");
        TYPES.put("svg", "image/svg+xml");
        TYPES.put("webmanifest", "application/manifest+json");
        TYPES.put("png", "image/png");
        TYPES.put("txt", "text/plain");
    }

    WebResourceResponse open(String path) {
        if (path == null || path.equals("/") || path.isEmpty()) path = "/index.html";
        if (path.contains("..")) return notFound();
        String rel = path.substring(1);
        String ext = rel.substring(rel.lastIndexOf('.') + 1);
        String type = TYPES.containsKey(ext) ? TYPES.get(ext) : "application/octet-stream";
        try {
            InputStream in;
            int d = downloaded(ctx);
            File f = new File(new File(base(ctx), String.valueOf(d)), rel);
            if (d > bundled(ctx) && f.isFile()) in = new FileInputStream(f);
            else in = ctx.getAssets().open("web/" + rel);
            WebResourceResponse r = new WebResourceResponse(type, "utf-8", in);
            Map<String, String> h = new HashMap<>();
            h.put("Cache-Control", "no-cache");
            r.setResponseHeaders(h);
            return r;
        } catch (IOException e) {
            return notFound();
        }
    }

    static WebResourceResponse notFound() {
        WebResourceResponse r = new WebResourceResponse("text/plain", "utf-8", null);
        r.setStatusCodeAndReasonPhrase(404, "Not Found");
        return r;
    }
}

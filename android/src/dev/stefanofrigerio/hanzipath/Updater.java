package dev.stefanofrigerio.hanzipath;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInstaller;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Keeps the app up to date from the repo's GitHub release "latest" (the release workflow makes
 * it on every push to main): hanzi-path-C.apk, C the commits on main. The repo is private, so
 * it asks with a fine-grained token (read-only, Contents, this repo), pasted once in Settings.
 *
 * Asked as the app comes to the front, at most every EVERY ms (or at once from Settings). A
 * newer APK is installed over this one through PackageInstaller: silently once this app is the
 * one that installed itself (Android 12's USER_ACTION_NOT_REQUIRED), with Android's
 * confirmation the first time (after an install by adb or a file manager).
 */
class Updater {
    static final String RELEASE = "https://api.github.com/repos/stfrigerio/hanzi-path-app/releases/tags/latest";
    static final long EVERY = 3 * 3600_000L;
    static final AtomicBoolean busy = new AtomicBoolean();
    static final Pattern APK = Pattern.compile("hanzi-path-(\\d+)\\.apk");

    static void maybe(Main main, boolean now) {
        SharedPreferences p = main.getSharedPreferences("hanzi", Context.MODE_PRIVATE);
        if (!now && System.currentTimeMillis() - p.getLong("checked", 0) < EVERY) return;
        if (!busy.compareAndSet(false, true)) return;
        new Thread(() -> {
            try {
                run(main, p, now);
            } catch (Exception e) {
                status(main, p, "Couldn't check: " + e.getMessage());
            } finally {
                busy.set(false);
            }
        }, "updater").start();
    }

    static void status(Main main, SharedPreferences p, String s) {
        p.edit().putString("status", s).apply();
        main.updateStatus();
    }

    static void run(Main main, SharedPreferences p, boolean now) throws Exception {
        String token = p.getString("token", "");
        if (token.isEmpty()) {
            status(main, p, "No token: paste one to get updates.");
            return;
        }
        status(main, p, "Checking…");
        HttpURLConnection c = open(RELEASE, token, "application/vnd.github+json");
        int code = c.getResponseCode();
        if (code == 401 || code == 403) { status(main, p, "GitHub refused the token (" + code + ")."); return; }
        if (code == 404) { status(main, p, "No release yet, or the token can't see the repo."); return; }
        if (code != 200) { status(main, p, "GitHub answered " + code + "."); return; }
        JSONArray assets = new JSONObject(read(c.getInputStream())).getJSONArray("assets");
        p.edit().putLong("checked", System.currentTimeMillis()).apply();

        int apk = 0;
        String apkUrl = null;
        for (int i = 0; i < assets.length(); i++) {
            JSONObject a = assets.getJSONObject(i);
            Matcher m = APK.matcher(a.getString("name"));
            if (m.matches() && Integer.parseInt(m.group(1)) > apk) { apk = Integer.parseInt(m.group(1)); apkUrl = a.getString("url"); }
        }
        long have = main.getPackageManager().getPackageInfo(main.getPackageName(), 0).getLongVersionCode();
        if (apkUrl != null && apk > have) installApk(main, p, token, apkUrl, apk, now);
        else status(main, p, "Up to date.");
    }

    // ── the app itself ──
    /** True once the install is under way; false when it waits for the person. */
    static boolean installApk(Main main, SharedPreferences p, String token, String url, int code, boolean now) throws Exception {
        if (!now && p.getInt("apkTried", 0) == code
                && System.currentTimeMillis() - p.getLong("apkTriedAt", 0) < 24 * 3600_000L) {
            status(main, p, "App update " + code + " waiting: tap Check now to install it.");
            return false;
        }
        if (!main.getPackageManager().canRequestPackageInstalls()) {
            status(main, p, "Update " + code + " needs “Install unknown apps” allowed for 汉字之路: tap Check now.");
            if (now) main.startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + main.getPackageName())).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return false;
        }
        p.edit().putInt("apkTried", code).putLong("apkTriedAt", System.currentTimeMillis()).apply();
        status(main, p, "Downloading app update " + code + "…");
        File f = download(url, token, new File(main.getCacheDir(), "update.apk"));
        PackageInstaller pi = main.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(main.getPackageName());
        params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED);
        int id = pi.createSession(params);
        try (PackageInstaller.Session s = pi.openSession(id)) {
            try (InputStream in = new FileInputStream(f); OutputStream out = s.openWrite("app.apk", 0, f.length())) {
                copy(in, out);
                s.fsync(out);
            }
            Intent done = new Intent(main, Installed.class);
            PendingIntent pend = PendingIntent.getBroadcast(main, id, done,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
            status(main, p, "Installing app update " + code + "…");
            s.commit(pend.getIntentSender());
        }
        f.delete();
        return true;
    }

    // ── GitHub ──
    static HttpURLConnection open(String url, String token, String accept) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(60000);
        c.setInstanceFollowRedirects(false);
        c.setRequestProperty("Accept", accept);
        c.setRequestProperty("User-Agent", "hanzi-path");
        c.setRequestProperty("X-GitHub-Api-Version", "2022-11-28");
        if (token != null) c.setRequestProperty("Authorization", "Bearer " + token);
        return c;
    }

    /** An asset, through GitHub's redirect to its storage (asked there without the token). */
    static File download(String url, String token, File to) throws IOException {
        HttpURLConnection c = open(url, token, "application/octet-stream");
        for (int hops = 0; hops < 5 && c.getResponseCode() / 100 == 3; hops++) {
            String next = c.getHeaderField("Location");
            c.disconnect();
            c = open(next, null, "application/octet-stream");
        }
        if (c.getResponseCode() != 200) throw new IOException("download answered " + c.getResponseCode());
        try (InputStream in = c.getInputStream(); OutputStream out = new FileOutputStream(to)) {
            copy(in, out);
        }
        return to;
    }

    static String read(InputStream in) throws IOException {
        try (InputStream i = in) { return text(i); }
    }

    /** (InputStream's readAllBytes and transferTo are Android 13's: this runs on 12.) */
    static String text(InputStream in) throws IOException {
        java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
        copy(in, b);
        return b.toString("UTF-8");
    }

    static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[65536];
        for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
    }

    static void delete(File f) {
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) delete(k);
        f.delete();
    }
}

package com.gathin.tvhome;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * my-tv fork: in-launcher updater. Reads the latest GitHub Release of the fork named in
 * res/values/mytv_update.xml (tag "build-<versionCode>", one .apk asset) and hands a newer APK to
 * Android's PackageInstaller, which asks on screen before installing. Network calls run off the main thread.
 */
final class Updater {
    static final String PREFS = "mytv_updater";

    static final class Release {
        final long code;
        final String apkUrl;
        Release(long code, String apkUrl) { this.code = code; this.apkUrl = apkUrl; }
    }

    private Updater() { }

    static boolean configured(Context c) {
        String repo = c.getString(R.string.update_repo).trim();
        return repo.contains("/") && !repo.startsWith("CHANGE-ME");
    }

    static long installedCode(Context c) {
        try {
            PackageInfo p = c.getPackageManager().getPackageInfo(c.getPackageName(), 0);
            return Build.VERSION.SDK_INT >= 28 ? p.getLongVersionCode() : p.versionCode;
        } catch (Exception e) {
            return Long.MAX_VALUE;
        }
    }

    static boolean canInstall(Context c) {
        return c.getPackageManager().canRequestPackageInstalls();
    }

    /** Latest release, or null when none fits. Call off the main thread. */
    static Release latest(Context c) throws Exception {
        if (!configured(c)) return null;
        String repo = c.getString(R.string.update_repo).trim();
        JSONObject j = new JSONObject(get("https://api.github.com/repos/" + repo + "/releases/latest"));
        String tag = j.optString("tag_name", "");
        if (!tag.startsWith("build-")) return null;
        long code = Long.parseLong(tag.substring(6));
        JSONArray assets = j.getJSONArray("assets");
        for (int i = 0; i < assets.length(); i++) {
            JSONObject a = assets.getJSONObject(i);
            if (a.optString("name", "").endsWith(".apk")) {
                return new Release(code, a.getString("browser_download_url"));
            }
        }
        return null;
    }

    /** Downloads the APK into an install session and commits it; Android then asks for confirmation. */
    static void install(Context c, Release r) throws Exception {
        PackageInstaller pi = c.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params =
                new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(c.getPackageName());
        int id = pi.createSession(params);
        try (PackageInstaller.Session s = pi.openSession(id)) {
            HttpURLConnection conn = open(r.apkUrl);
            try {
                long len = conn.getContentLengthLong();
                try (InputStream in = conn.getInputStream();
                     OutputStream out = s.openWrite("base.apk", 0, len > 0 ? len : -1)) {
                    byte[] buf = new byte[65536];
                    for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
                    s.fsync(out);
                }
            } finally {
                conn.disconnect();
            }
            Intent done = new Intent(c, UpdateReceiver.class);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= 31) flags |= PendingIntent.FLAG_MUTABLE;
            PendingIntent p = PendingIntent.getBroadcast(c, id, done, flags);
            s.commit(p.getIntentSender());
        } catch (Exception e) {
            try { pi.abandonSession(id); } catch (Exception ignored) { }
            throw e;
        }
    }

    private static HttpURLConnection open(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", "cleanyourtv-mytv-updater");
        c.setRequestProperty("Accept", "application/vnd.github+json");
        int code = c.getResponseCode();
        if (code != 200) {
            c.disconnect();
            throw new Exception("HTTP " + code);
        }
        return c;
    }

    private static String get(String url) throws Exception {
        HttpURLConnection c = open(url);
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
            return out.toString("UTF-8");
        } finally {
            c.disconnect();
        }
    }
}

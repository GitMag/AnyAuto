package com.mag.anyauto;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.core.content.FileProvider;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Updates, only while AnyAuto is open: when the Home screen opens (setting "Check for updates
 * automatically", at most once an hour) or from the menu, asks GitHub for the latest release. A
 * newer one is downloaded and checked (this package, a higher version code, signed with the same
 * key) and the Home screen offers it. Nothing runs in the background.
 *
 * <p>Update hands the APK to UnknownAuto. Android Auto only accepts an app that Android's own
 * installer installed with Google Play as the installer, so a self-install would drop AnyAuto
 * out of the car; UnknownAuto's installer keeps that mark. One tap on Install there.
 */
final class Updater {
    private static final String TAG = "AnyAuto";
    static final String RELEASES_API = "https://api.github.com/repos/GitMag/AnyAuto/releases/latest";
    static final String RELEASES_PAGE = "https://github.com/GitMag/AnyAuto/releases/latest";
    private static final long MIN_INTERVAL_MS = 60 * 60 * 1000L;
    private static final String DIR = "update";

    /** What a check found. */
    static final class Result {
        final String version; // the newer version that is ready, or null
        final String error;   // why the check failed, or null

        Result(String version, String error) {
            this.version = version;
            this.error = error;
        }
    }

    interface Listener {
        void done(Result r);
    }

    private static volatile boolean running;

    /**
     * Checks for a newer release, downloads and verifies it. {@code force} (the menu) skips the
     * setting and the once-an-hour limit. {@code done} runs on the main thread; it is not called
     * when an automatic check is skipped.
     */
    static void check(Context context, boolean force, Listener done) {
        Context c = context.getApplicationContext();
        if (!force && (!Prefs.autoUpdate(c)
                || System.currentTimeMillis() - Prefs.lastUpdateCheck(c) < MIN_INTERVAL_MS)) return;
        if (running) return;
        running = true;
        new Thread(() -> {
            Result r;
            try {
                r = checkNow(c);
            } catch (Throwable t) {
                Log.w(TAG, "update check failed", t);
                r = new Result(null, t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName());
            } finally {
                running = false;
            }
            Result result = r;
            new Handler(Looper.getMainLooper()).post(() -> done.done(result));
        }, "anyauto-update").start();
    }

    private static Result checkNow(Context c) throws Exception {
        Prefs.setLastUpdateCheck(c, System.currentTimeMillis());
        JSONObject release = new JSONObject(new String(get(c, RELEASES_API), "UTF-8"));
        String version = release.getString("tag_name").replaceFirst("^[vV]", "");
        if (compare(version, installedName(c)) <= 0) {
            Log.i(TAG, "update check: " + installedName(c) + " is up to date (latest release " + version + ")");
            clean(c, null);
            return new Result(null, null);
        }
        File ready = ready(c);
        if (ready != null && version.equals(Prefs.updateVersion(c))) return new Result(version, null);

        String url = null;
        JSONArray assets = release.getJSONArray("assets");
        for (int i = 0; i < assets.length(); i++) {
            JSONObject a = assets.getJSONObject(i);
            if (a.getString("name").toLowerCase().endsWith(".apk")) url = a.getString("browser_download_url");
        }
        if (url == null) throw new IllegalStateException("no APK in release " + version);

        File dir = new File(c.getCacheDir(), DIR);
        dir.mkdirs();
        File apk = new File(dir, "AnyAuto-" + version + ".apk");
        File part = new File(dir, apk.getName() + ".part");
        download(c, url, part);
        String why = verify(c, part);
        if (why != null) {
            part.delete();
            throw new IllegalStateException(why);
        }
        clean(c, part); // older downloads go, this one stays
        if (!part.renameTo(apk)) throw new IllegalStateException("could not save the update");
        Prefs.setUpdateVersion(c, version);
        Log.i(TAG, "update " + version + " downloaded and checked");
        return new Result(version, null);
    }

    /** The downloaded, still-newer update, or null. */
    static File ready(Context c) {
        String v = Prefs.updateVersion(c);
        if (v == null) return null;
        File apk = new File(new File(c.getCacheDir(), DIR), "AnyAuto-" + v + ".apk");
        if (!apk.isFile() || compare(v, installedName(c)) <= 0) {
            clean(c, null);
            Prefs.setUpdateVersion(c, null);
            return null;
        }
        return apk;
    }

    static String readyVersion(Context c) {
        return ready(c) != null ? Prefs.updateVersion(c) : null;
    }

    /** Hands the update to UnknownAuto (Install there); without UnknownAuto, opens its download page. */
    static void install(Activity a) {
        File apk = ready(a);
        if (apk == null) return;
        Uri uri = FileProvider.getUriForFile(a, a.getPackageName() + ".files", apk);
        Intent i = new Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .setPackage(MainActivity.UNKNOWNAUTO)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            a.startActivity(i);
        } catch (ActivityNotFoundException e) {
            MainActivity.openUnknownAuto(a);
        }
    }

    static void openReleasePage(Activity a) {
        try {
            a.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(RELEASES_PAGE)));
        } catch (ActivityNotFoundException ignored) {
        }
    }

    /** Same package, a higher version code, signed with the same key; else why not. */
    private static String verify(Context c, File apk) {
        PackageManager pm = c.getPackageManager();
        PackageInfo info = pm.getPackageArchiveInfo(apk.getPath(), PackageManager.GET_SIGNING_CERTIFICATES);
        if (info == null) return "the download is not an APK";
        if (!c.getPackageName().equals(info.packageName)) return "the download is another app";
        if (info.getLongVersionCode() <= installedCode(c)) return "the download is not newer";
        if (info.signingInfo == null) return "the download is not signed";
        for (Signature s : info.signingInfo.getApkContentsSigners()) {
            if (!pm.hasSigningCertificate(c.getPackageName(), s.toByteArray(), PackageManager.CERT_INPUT_RAW_X509)) {
                return "the update is signed with another key than this copy of AnyAuto";
            }
        }
        return null;
    }

    private static void clean(Context c, File keep) {
        File[] files = new File(c.getCacheDir(), DIR).listFiles();
        if (files == null) return;
        for (File f : files) if (!f.equals(keep)) f.delete();
    }

    private static byte[] get(Context c, String url) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        fetch(c, url, out);
        return out.toByteArray();
    }

    private static void download(Context c, String url, File to) throws Exception {
        try (OutputStream out = new FileOutputStream(to)) {
            fetch(c, url, out);
        }
    }

    private static void fetch(Context c, String url, OutputStream out) throws Exception {
        HttpURLConnection h = (HttpURLConnection) new URL(url).openConnection();
        try {
            h.setConnectTimeout(15000);
            h.setReadTimeout(30000);
            h.setRequestProperty("User-Agent", "AnyAuto/" + installedName(c));
            h.setRequestProperty("Accept", url.startsWith("https://api.github.com/")
                    ? "application/vnd.github+json" : "application/octet-stream");
            int code = h.getResponseCode();
            if (code != 200) throw new IllegalStateException("GitHub answered " + code);
            try (InputStream in = h.getInputStream()) {
                byte[] b = new byte[64 * 1024];
                int n;
                while ((n = in.read(b)) > 0) out.write(b, 0, n);
            }
        } finally {
            h.disconnect();
        }
    }

    /** Dotted version names, numerically: 0.10 > 0.9. */
    static int compare(String a, String b) {
        String[] x = a.split("\\."), y = b.split("\\.");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int p = i < x.length ? num(x[i]) : 0, q = i < y.length ? num(y[i]) : 0;
            if (p != q) return Integer.compare(p, q);
        }
        return 0;
    }

    private static int num(String s) {
        try {
            return Integer.parseInt(s.replaceAll("[^0-9].*$", ""));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    static String installedName(Context c) {
        try {
            String v = c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName;
            return v != null ? v : "0";
        } catch (PackageManager.NameNotFoundException e) {
            return "0";
        }
    }

    private static long installedCode(Context c) {
        try {
            return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).getLongVersionCode();
        } catch (PackageManager.NameNotFoundException e) {
            return 0;
        }
    }
}

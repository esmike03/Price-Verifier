package com.priceverifier;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

final class AppUpdate {
    static final class Info {
        final String url;
        final long size;
        final long modifiedSeconds;
        final String version;
        final String changes;
        final String releaseId;

        Info(String url, long size, long modifiedSeconds, String version, String changes,
             String releaseId) {
            this.url = url;
            this.size = size;
            this.modifiedSeconds = modifiedSeconds;
            this.version = version;
            this.changes = changes;
            this.releaseId = releaseId;
        }
    }

    private static final int CONNECT_TIMEOUT_MS = 8000;
    private static final int READ_TIMEOUT_MS = 20000;
    private static final long UPDATE_GRACE_MS = 30000L;
    private static final long MAX_APK_BYTES = 150L * 1024L * 1024L;

    private AppUpdate() {
    }

    static Info check(Context context, String serverUrl) {
        String base = normalizeBase(serverUrl);
        if (base.isEmpty()) {
            return null;
        }
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(base + "index.php?app_update=1").openConnection();
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setUseCaches(false);
            if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) {
                return null;
            }
            JSONObject json = new JSONObject(readText(connection));
            if (!json.optBoolean("available", false)) {
                return null;
            }
            long modifiedSeconds = json.optLong("modified", 0L);
            long size = json.optLong("size", 0L);
            String url = json.optString("url", base + "price-verifier.apk");
            if (modifiedSeconds <= 0L || size <= 0L || size > MAX_APK_BYTES || url.isEmpty()) {
                return null;
            }
            long installedMs = installedUpdateTime(context);
            if (modifiedSeconds * 1000L <= installedMs + UPDATE_GRACE_MS) {
                return null;
            }
            String version = json.optString("version", "").trim();
            String releaseId = json.optString("release_id", "").trim();
            if (releaseId.isEmpty()) {
                releaseId = version + "|" + size + "|" + modifiedSeconds;
            }
            return new Info(url, size, modifiedSeconds,
                    version,
                    json.optString("changes", "").trim(),
                    releaseId);
        } catch (Exception ignored) {
            return null;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    static File download(Context context, Info info) throws Exception {
        HttpURLConnection connection = null;
        File target = UpdateApkProvider.updateFile(context);
        File temp = new File(target.getParentFile(), target.getName() + ".tmp");
        try {
            connection = (HttpURLConnection) new URL(info.url).openConnection();
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(60000);
            connection.setUseCaches(false);
            if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) {
                throw new IllegalStateException("Download failed.");
            }
            long length = connection.getContentLengthLong();
            if (length <= 0L) {
                length = info.size;
            }
            if (length <= 0L || length > MAX_APK_BYTES) {
                throw new IllegalStateException("APK size is invalid.");
            }
            try (InputStream input = connection.getInputStream();
                 FileOutputStream output = new FileOutputStream(temp)) {
                byte[] buffer = new byte[32768];
                long written = 0L;
                int read;
                while ((read = input.read(buffer)) != -1) {
                    written += read;
                    if (written > MAX_APK_BYTES) {
                        throw new IllegalStateException("APK is too large.");
                    }
                    output.write(buffer, 0, read);
                }
                if (written <= 0L) {
                    throw new IllegalStateException("APK download was empty.");
                }
            }
            if (!temp.renameTo(target)) {
                //noinspection ResultOfMethodCallIgnored
                target.delete();
                if (!temp.renameTo(target)) {
                    throw new IllegalStateException("Could not save APK.");
                }
            }
            return target;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
            if (temp.exists()) {
                //noinspection ResultOfMethodCallIgnored
                temp.delete();
            }
        }
    }

    private static long installedUpdateTime(Context context) throws PackageManager.NameNotFoundException {
        PackageInfo info = context.getPackageManager()
                .getPackageInfo(context.getPackageName(), 0);
        return info.lastUpdateTime;
    }

    private static String readText(HttpURLConnection connection) throws Exception {
        try (InputStream input = connection.getInputStream()) {
            byte[] buffer = new byte[8192];
            StringBuilder builder = new StringBuilder();
            int read;
            while ((read = input.read(buffer)) != -1) {
                builder.append(new String(buffer, 0, read, StandardCharsets.UTF_8));
                if (builder.length() > 16384) {
                    break;
                }
            }
            return builder.toString();
        }
    }

    private static String normalizeBase(String value) {
        String base = value == null ? "" : value.trim();
        if (base.isEmpty()) {
            return "";
        }
        if (!base.toLowerCase(Locale.US).startsWith("http://")
                && !base.toLowerCase(Locale.US).startsWith("https://")) {
            base = "http://" + base;
        }
        return base.endsWith("/") ? base : base + "/";
    }
}

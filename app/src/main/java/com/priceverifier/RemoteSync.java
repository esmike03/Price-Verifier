package com.priceverifier;

import android.content.Context;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * "Sync now" requests made in the web portal's Scanners tab. The server cannot
 * reach into a scanner, so the scanner checks in every few seconds: that tells
 * the portal it is online (and, when the check-ins stop, that it is not) and
 * returns the latest request, which the scanner then carries out and reports
 * back on.
 */
final class RemoteSync {
    static final class Request {
        final long id;
        /** What to sync; null for an app-update check or a request this version does not know. */
        final LanSync.Scope scope;
        /** Check the server for a new app version instead of syncing. */
        final boolean appUpdate;
        final long ageSeconds;

        Request(long id, LanSync.Scope scope, boolean appUpdate, long ageSeconds) {
            this.id = id;
            this.scope = scope;
            this.appUpdate = appUpdate;
            this.ageSeconds = ageSeconds;
        }

        /** False for a kind of request added to the portal after this app version. */
        boolean isSupported() {
            return scope != null || appUpdate;
        }
    }

    /**
     * Seconds between check-ins. The portal is told this number and shows the
     * scanner as offline once three check-ins in a row are missing.
     */
    static final int CHECK_INTERVAL_SECONDS = 2;

    /** An older request is skipped: the daily sync has covered it since. */
    static final long MAX_REQUEST_AGE_SECONDS = 24L * 60L * 60L;

    private static final int CONNECT_TIMEOUT_MS = 5000;
    private static final int READ_TIMEOUT_MS = 8000;
    private static final int MAX_RESPONSE_BYTES = 16 * 1024;

    private RemoteSync() {
    }

    /**
     * Checks in and returns the portal's current sync request, or null when
     * there is none, the server is unreachable, or it is an older server
     * without this feature.
     */
    static Request poll(Context context, String serverUrl) {
        try {
            String body = call(context, serverUrl, "");
            if (body == null) {
                return null;
            }
            JSONObject request = new JSONObject(body).optJSONObject("request");
            if (request == null) {
                return null;
            }
            long id = request.optLong("id", 0L);
            if (id <= 0L) {
                return null;
            }
            String scope = request.optString("scope");
            return new Request(id, syncScope(scope), "app".equals(scope), request.optLong("age", 0L));
        } catch (Exception ignored) {
            return null;
        }
    }

    private static LanSync.Scope syncScope(String scope) {
        switch (scope) {
            case "all":
                return LanSync.Scope.ALL;
            case "items":
                return LanSync.Scope.ITEMS;
            case "images":
                return LanSync.Scope.IMAGES;
            case "videos":
                return LanSync.Scope.VIDEOS;
            default:
                return null;
        }
    }

    /** Tells the portal how this scanner's sync for a request went. */
    static void report(Context context, String serverUrl, long requestId, boolean ok, String message) {
        try {
            String text = message == null ? "" : message;
            call(context, serverUrl, "&done=" + requestId + "&ok=" + (ok ? "1" : "0")
                    + "&message=" + encode(text.length() > 180 ? text.substring(0, 180) : text));
        } catch (Exception ignored) {
            // The portal just keeps showing this scanner as waiting.
        }
    }

    private static String call(Context context, String serverUrl, String extra) throws Exception {
        String base = normalizeBase(serverUrl);
        String serial = DeviceAccess.serial(context);
        if (base.isEmpty() || serial.isEmpty()) {
            return null;
        }
        HttpURLConnection connection = (HttpURLConnection) new URL(base + "index.php?device_poll=1"
                + "&serial=" + encode(serial) + "&app=" + encode(appVersion(context))
                + "&every=" + CHECK_INTERVAL_SECONDS + extra).openConnection();
        try {
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setUseCaches(false);
            if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) {
                return null;
            }
            try (InputStream input = connection.getInputStream()) {
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                byte[] buffer = new byte[2048];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    if (output.size() + count > MAX_RESPONSE_BYTES) {
                        return null;    // not this endpoint's small JSON reply
                    }
                    output.write(buffer, 0, count);
                }
                return output.toString(StandardCharsets.UTF_8.name());
            }
        } finally {
            connection.disconnect();
        }
    }

    private static String appVersion(Context context) {
        try {
            String name = context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), 0).versionName;
            return name == null ? "" : name;
        } catch (Exception ignored) {
            return "";
        }
    }

    private static String encode(String value) throws Exception {
        return URLEncoder.encode(value, StandardCharsets.UTF_8.name());
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

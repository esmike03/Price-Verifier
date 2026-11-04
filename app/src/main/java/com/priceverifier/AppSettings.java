package com.priceverifier;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;

import java.io.File;

final class AppSettings {
    private static final String PREFS = "app_settings";
    private static final String KEY_STORE_NAME = "store_name";
    private static final String KEY_VIDEO_LOGO = "video_shows_logo";
    private static final String KEY_CHECK_BG = "check_bg_color";
    private static final String KEY_CHECK_NAME = "check_name_color";
    private static final String KEY_CHECK_META = "check_meta_color";
    private static final String KEY_CHECK_PRICE = "check_price_color";
    private static final String KEY_CHECK_LAYOUT = "check_layout";
    private static final String KEY_PROMO_STYLE = "promo_style";
    private static final String KEY_MARQUEE = "marquee_text";
    private static final String KEY_MARQUEE_BG = "marquee_bg_color";
    private static final String KEY_MARQUEE_TEXT = "marquee_text_color";
    private static final String KEY_SCAN_PROMPT = "scan_prompt_color";
    private static final String KEY_LAN_URL = "lan_url";
    private static final String KEY_LAST_SYNC = "last_sync";
    private static final String KEY_AUTO_SYNC = "auto_sync_enabled";
    private static final String KEY_AUTO_SYNC_HOUR = "auto_sync_hour";
    private static final String KEY_AUTO_SYNC_MINUTE = "auto_sync_minute";
    private static final String KEY_LAST_AUTO_SYNC_DAY = "last_auto_sync_day";
    private static final String KEY_IMAGE_SECONDS = "image_display_seconds";
    private static final String KEY_VIDEO_SECONDS = "video_play_seconds";
    private static final String KEY_LOGO_SCALE = "logo_scale_percent";
    private static final String KEY_LOGO_CEILING = "logo_scale_ceiling_percent";
    private static final String KEY_VIDEO_PLAYBACK_ERROR = "video_playback_error";
    private static final String KEY_HANDLED_APP_UPDATE_MODIFIED = "handled_app_update_modified";
    private static final String KEY_HANDLED_APP_UPDATE_ID = "handled_app_update_id";
    // The newest "sync now" request from the portal's Scanners tab this device acted on.
    private static final String KEY_HANDLED_SYNC_REQUEST = "handled_sync_request";
    // Idle playback order. The server-provided order syncs from the portal; the
    // local order is an optional per-device override that wins when set. Each is
    // a newline-separated list of tokens: "banner", "video", or a scheduled
    // media file name.
    private static final String KEY_MEDIA_ORDER_SERVER = "media_order_server";
    private static final String KEY_MEDIA_ORDER_LOCAL = "media_order_local";
    private static final String LASTMOD_PREFIX = "lastmod_";
    // Comma-separated design.json keys the server currently manages (see ServerDesign).
    private static final String KEY_SERVER_DESIGN_KEYS = "server_design_keys";

    static final String DEFAULT_LAN_URL = "";
    static final String LOGO_FILE = "idle_logo.png";
    static final String BANNER_FILE = "idle_banner.jpg";
    static final String VIDEO_FILE = "idle_video.mp4";
    static final String MEDIA_DIR = "idle_media";
    static final String CATALOG_DOWNLOAD_FILE = ".catalog-download.tmp";

    static final int DEFAULT_AUTO_SYNC_HOUR = 8;
    static final int DEFAULT_AUTO_SYNC_MINUTE = 0;
    static final int DEFAULT_IMAGE_SECONDS = 12;
    static final int DEFAULT_VIDEO_SECONDS = 0;
    static final int MIN_VIDEO_SECONDS = 0;
    static final int MAX_VIDEO_SECONDS = 86400;
    static final int DEFAULT_LOGO_SCALE_PERCENT = 100;
    static final int MIN_LOGO_SCALE_PERCENT = 50;
    static final int MAX_LOGO_SCALE_PERCENT = 250;

    private final SharedPreferences prefs;
    private final File filesDir;

    AppSettings(Context context) {
        Context app = context.getApplicationContext();
        this.prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        this.filesDir = app.getFilesDir();
    }

    String getStoreName() {
        return prefs.getString(KEY_STORE_NAME, "");
    }

    void setStoreName(String value) {
        prefs.edit().putString(KEY_STORE_NAME, value == null ? "" : value.trim()).apply();
    }

    /**
     * Whether the overlay shown while an idle video plays is the store logo
     * instead of the store name. The logo is the uploaded one when there is one,
     * otherwise the built-in logo.
     */
    boolean isVideoLogoEnabled() {
        return prefs.getBoolean(KEY_VIDEO_LOGO, false);
    }

    void setVideoLogoEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_VIDEO_LOGO, enabled).apply();
    }

    // Price-check panel colors. Defaults mirror the original hard-coded values:
    // yellow background, blue product name, slate barcode, red price.
    int getCheckBackgroundColor() {
        return prefs.getInt(KEY_CHECK_BG, 0xFFFDE047);
    }

    void setCheckBackgroundColor(int color) {
        prefs.edit().putInt(KEY_CHECK_BG, color).apply();
    }

    int getCheckNameColor() {
        return prefs.getInt(KEY_CHECK_NAME, 0xFF2563EB);
    }

    void setCheckNameColor(int color) {
        prefs.edit().putInt(KEY_CHECK_NAME, color).apply();
    }

    int getCheckMetaColor() {
        return prefs.getInt(KEY_CHECK_META, 0xFF334155);
    }

    void setCheckMetaColor(int color) {
        prefs.edit().putInt(KEY_CHECK_META, color).apply();
    }

    int getCheckPriceColor() {
        return prefs.getInt(KEY_CHECK_PRICE, 0xFFDC2626);
    }

    void setCheckPriceColor(int color) {
        prefs.edit().putInt(KEY_CHECK_PRICE, color).apply();
    }

    // Where the price check puts the name, barcode and price, and how it draws a
    // promo (PriceScreen.LAYOUTS and PriceScreen.PROMO_STYLES). The defaults are
    // the original centred screen with its bottom banner.
    String getCheckLayout() {
        String layout = prefs.getString(KEY_CHECK_LAYOUT, PriceScreen.LAYOUT_CENTER);
        return PriceScreen.LAYOUTS.contains(layout) ? layout : PriceScreen.LAYOUT_CENTER;
    }

    void setCheckLayout(String layout) {
        prefs.edit().putString(KEY_CHECK_LAYOUT, layout).apply();
    }

    String getPromoStyle() {
        String style = prefs.getString(KEY_PROMO_STYLE, PriceScreen.PROMO_CLASSIC);
        return PriceScreen.PROMO_STYLES.contains(style) ? style : PriceScreen.PROMO_CLASSIC;
    }

    void setPromoStyle(String style) {
        prefs.edit().putString(KEY_PROMO_STYLE, style).apply();
    }

    String getMarqueeText() {
        return prefs.getString(KEY_MARQUEE, "");
    }

    void setMarqueeText(String value) {
        prefs.edit().putString(KEY_MARQUEE, value == null ? "" : value.trim()).apply();
    }

    int getMarqueeBackgroundColor() {
        return prefs.getInt(KEY_MARQUEE_BG, Color.BLACK);
    }

    void setMarqueeBackgroundColor(int color) {
        prefs.edit().putInt(KEY_MARQUEE_BG, color).apply();
    }

    int getMarqueeTextColor() {
        return prefs.getInt(KEY_MARQUEE_TEXT, Color.WHITE);
    }

    void setMarqueeTextColor(int color) {
        prefs.edit().putInt(KEY_MARQUEE_TEXT, color).apply();
    }

    /**
     * Colour of the floating "SCAN HERE" prompt. One colour drives the text, its
     * glow, the badge outline and the arrows, so the prompt reads as a single
     * accent. The default is the green the layout shipped with.
     */
    int getScanPromptColor() {
        return prefs.getInt(KEY_SCAN_PROMPT, 0xFF00E676);
    }

    void setScanPromptColor(int color) {
        prefs.edit().putInt(KEY_SCAN_PROMPT, color).apply();
    }

    java.util.Set<String> getServerDesignKeys() {
        java.util.Set<String> keys = new java.util.HashSet<>();
        for (String key : prefs.getString(KEY_SERVER_DESIGN_KEYS, "").split(",")) {
            if (!key.isEmpty()) {
                keys.add(key);
            }
        }
        return keys;
    }

    void setServerDesignKeys(java.util.Collection<String> keys) {
        prefs.edit().putString(KEY_SERVER_DESIGN_KEYS, android.text.TextUtils.join(",", keys)).apply();
    }

    String getLanUrl() {
        return prefs.getString(KEY_LAN_URL, DEFAULT_LAN_URL);
    }

    void setLanUrl(String value) {
        prefs.edit().putString(KEY_LAN_URL, value == null ? "" : value.trim()).apply();
    }

    long getLastSync() {
        return prefs.getLong(KEY_LAST_SYNC, 0L);
    }

    void setLastSync(long when) {
        prefs.edit().putLong(KEY_LAST_SYNC, when).apply();
    }

    boolean isAutoSyncEnabled() {
        // On unless it was switched off in Admin > Sync.
        return prefs.getBoolean(KEY_AUTO_SYNC, true);
    }

    void setAutoSyncEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_AUTO_SYNC, enabled).apply();
    }

    int getAutoSyncHour() {
        return Math.max(0, Math.min(23, prefs.getInt(KEY_AUTO_SYNC_HOUR, DEFAULT_AUTO_SYNC_HOUR)));
    }

    int getAutoSyncMinute() {
        return Math.max(0, Math.min(59,
                prefs.getInt(KEY_AUTO_SYNC_MINUTE, DEFAULT_AUTO_SYNC_MINUTE)));
    }

    void setAutoSyncTime(int hour, int minute) {
        prefs.edit()
                .putInt(KEY_AUTO_SYNC_HOUR, Math.max(0, Math.min(23, hour)))
                .putInt(KEY_AUTO_SYNC_MINUTE, Math.max(0, Math.min(59, minute)))
                .apply();
    }

    int getLastAutoSyncDay() {
        return prefs.getInt(KEY_LAST_AUTO_SYNC_DAY, 0);
    }

    void setLastAutoSyncDay(int dayKey) {
        prefs.edit().putInt(KEY_LAST_AUTO_SYNC_DAY, dayKey).apply();
    }

    int getImageSeconds() {
        int v = prefs.getInt(KEY_IMAGE_SECONDS, DEFAULT_IMAGE_SECONDS);
        return clampImageSeconds(v);
    }

    void setImageSeconds(int seconds) {
        prefs.edit().putInt(KEY_IMAGE_SECONDS, clampImageSeconds(seconds)).apply();
    }

    int getVideoSeconds() {
        int v = prefs.getInt(KEY_VIDEO_SECONDS, DEFAULT_VIDEO_SECONDS);
        return clampVideoSeconds(v);
    }

    void setVideoSeconds(int seconds) {
        prefs.edit().putInt(KEY_VIDEO_SECONDS, clampVideoSeconds(seconds)).apply();
    }

    String getVideoPlaybackError() {
        return prefs.getString(KEY_VIDEO_PLAYBACK_ERROR, "");
    }

    void setVideoPlaybackError(String value) {
        prefs.edit().putString(KEY_VIDEO_PLAYBACK_ERROR, value == null ? "" : value.trim()).apply();
    }

    long getHandledAppUpdateModified() {
        return prefs.getLong(KEY_HANDLED_APP_UPDATE_MODIFIED, 0L);
    }

    void setHandledAppUpdateModified(long modifiedSeconds) {
        prefs.edit().putLong(KEY_HANDLED_APP_UPDATE_MODIFIED, Math.max(0L, modifiedSeconds)).apply();
    }

    String getHandledAppUpdateId() {
        return prefs.getString(KEY_HANDLED_APP_UPDATE_ID, "");
    }

    void setHandledAppUpdateId(String updateId) {
        prefs.edit()
                .putString(KEY_HANDLED_APP_UPDATE_ID, updateId == null ? "" : updateId.trim())
                .apply();
    }

    long getHandledSyncRequest() {
        return prefs.getLong(KEY_HANDLED_SYNC_REQUEST, 0L);
    }

    void setHandledSyncRequest(long requestId) {
        prefs.edit().putLong(KEY_HANDLED_SYNC_REQUEST, requestId).apply();
    }

    private static int clampImageSeconds(int seconds) {
        return Math.max(1, Math.min(MAX_VIDEO_SECONDS, seconds));
    }

    private static int clampVideoSeconds(int seconds) {
        return Math.max(MIN_VIDEO_SECONDS, Math.min(MAX_VIDEO_SECONDS, seconds));
    }

    /**
     * Idle-screen logo size as a percentage of the size the layout gives it.
     * 100 keeps the built-in size; the admin media module exposes this as a
     * slider so the logo can be sized per device without a rebuild.
     */
    int getLogoScalePercent() {
        return clampLogoScale(prefs.getInt(KEY_LOGO_SCALE, DEFAULT_LOGO_SCALE_PERCENT));
    }

    void setLogoScalePercent(int percent) {
        prefs.edit().putInt(KEY_LOGO_SCALE, clampLogoScale(percent)).apply();
    }

    /**
     * The largest logo scale the idle screen can actually show, measured from the
     * logo column on this device. The admin slider stops here so it never offers a
     * size the screen has no room for.
     */
    int getLogoScaleCeilingPercent() {
        return clampLogoScale(prefs.getInt(KEY_LOGO_CEILING, MAX_LOGO_SCALE_PERCENT));
    }

    void setLogoScaleCeilingPercent(int percent) {
        int clamped = clampLogoScale(percent);
        // Written from a layout pass, so only touch the file when it really moved.
        if (clamped != getLogoScaleCeilingPercent()) {
            prefs.edit().putInt(KEY_LOGO_CEILING, clamped).apply();
        }
    }

    private static int clampLogoScale(int percent) {
        return Math.max(MIN_LOGO_SCALE_PERCENT, Math.min(MAX_LOGO_SCALE_PERCENT, percent));
    }

    String getServerMediaOrder() {
        return prefs.getString(KEY_MEDIA_ORDER_SERVER, "");
    }

    void setServerMediaOrder(String value) {
        prefs.edit().putString(KEY_MEDIA_ORDER_SERVER, value == null ? "" : value.trim()).apply();
    }

    String getLocalMediaOrder() {
        return prefs.getString(KEY_MEDIA_ORDER_LOCAL, "");
    }

    void setLocalMediaOrder(String value) {
        prefs.edit().putString(KEY_MEDIA_ORDER_LOCAL, value == null ? "" : value.trim()).apply();
    }

    /** The local override when set, otherwise the order synced from the server. */
    String getEffectiveMediaOrder() {
        String local = getLocalMediaOrder();
        return local.isEmpty() ? getServerMediaOrder() : local;
    }

    boolean hasLocalMediaOrder() {
        return !getLocalMediaOrder().isEmpty();
    }

    String getLastModified(String asset) {
        return prefs.getString(LASTMOD_PREFIX + asset, "");
    }

    void setLastModified(String asset, String value) {
        prefs.edit().putString(LASTMOD_PREFIX + asset, value == null ? "" : value).apply();
    }

    void clearLastModified() {
        SharedPreferences.Editor e = prefs.edit();
        for (String key : prefs.getAll().keySet()) {
            if (key.startsWith(LASTMOD_PREFIX)) {
                e.remove(key);
            }
        }
        e.apply();
    }

    File logoFile() {
        return new File(filesDir, LOGO_FILE);
    }

    File bannerFile() {
        return new File(filesDir, BANNER_FILE);
    }

    File mediaDir() {
        File dir = new File(filesDir, MEDIA_DIR);
        if (!dir.exists()) {
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
        }
        return dir;
    }

    boolean hasLogo() {
        return logoFile().exists() && logoFile().length() > 0;
    }

    boolean hasBanner() {
        return bannerFile().exists() && bannerFile().length() > 0;
    }

    File videoFile() {
        return new File(filesDir, VIDEO_FILE);
    }

    File catalogDownloadFile() {
        return new File(filesDir, CATALOG_DOWNLOAD_FILE);
    }

    boolean hasVideo() {
        return videoFile().exists() && videoFile().length() > 0;
    }
}

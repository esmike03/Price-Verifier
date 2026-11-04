package com.priceverifier;

import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Applies the portal's design.json: the same look settings as the Admin screen,
 * shared by every scanner on the server. Only the keys present in the file are
 * applied ("server first"); anything absent keeps this device's own value.
 * Invalid values are skipped individually rather than failing the file.
 */
final class ServerDesign {
    static final List<String> CHECK_KEYS = Arrays.asList(
            "check_bg_color", "check_name_color", "check_meta_color", "check_price_color",
            "check_layout", "promo_style");
    static final List<String> MEDIA_KEYS = Arrays.asList(
            "scan_prompt_color", "logo_scale_percent", "store_name", "video_shows_logo",
            "marquee_bg_color", "marquee_text_color", "image_display_seconds", "video_play_seconds");

    private interface IntSetter {
        void set(int value);
    }

    private interface TextSetter {
        void set(String value);
    }

    private ServerDesign() {
    }

    /** Returns true when any setting actually changed. */
    static boolean apply(AppSettings settings, byte[] data) throws JSONException {
        JSONObject json = new JSONObject(new String(data, StandardCharsets.UTF_8));
        Set<String> managed = new LinkedHashSet<>();
        boolean changed = false;

        changed |= color(json, "check_bg_color", managed, settings.getCheckBackgroundColor(),
                settings::setCheckBackgroundColor);
        changed |= color(json, "check_name_color", managed, settings.getCheckNameColor(),
                settings::setCheckNameColor);
        changed |= color(json, "check_meta_color", managed, settings.getCheckMetaColor(),
                settings::setCheckMetaColor);
        changed |= color(json, "check_price_color", managed, settings.getCheckPriceColor(),
                settings::setCheckPriceColor);
        changed |= choice(json, "check_layout", PriceScreen.LAYOUTS, managed,
                settings.getCheckLayout(), settings::setCheckLayout);
        changed |= choice(json, "promo_style", PriceScreen.PROMO_STYLES, managed,
                settings.getPromoStyle(), settings::setPromoStyle);
        changed |= color(json, "scan_prompt_color", managed, settings.getScanPromptColor(),
                settings::setScanPromptColor);
        changed |= color(json, "marquee_bg_color", managed, settings.getMarqueeBackgroundColor(),
                settings::setMarqueeBackgroundColor);
        changed |= color(json, "marquee_text_color", managed, settings.getMarqueeTextColor(),
                settings::setMarqueeTextColor);
        changed |= number(json, "logo_scale_percent", AppSettings.MIN_LOGO_SCALE_PERCENT,
                AppSettings.MAX_LOGO_SCALE_PERCENT, managed, settings.getLogoScalePercent(),
                settings::setLogoScalePercent);
        changed |= number(json, "image_display_seconds", 1, AppSettings.MAX_VIDEO_SECONDS,
                managed, settings.getImageSeconds(), settings::setImageSeconds);
        changed |= number(json, "video_play_seconds", AppSettings.MIN_VIDEO_SECONDS,
                AppSettings.MAX_VIDEO_SECONDS, managed, settings.getVideoSeconds(),
                settings::setVideoSeconds);

        Object storeName = json.opt("store_name");
        if (storeName instanceof String) {
            managed.add("store_name");
            String name = ((String) storeName).trim();
            if (!name.equals(settings.getStoreName())) {
                settings.setStoreName(name);
                changed = true;
            }
        }
        Object videoLogo = json.opt("video_shows_logo");
        if (videoLogo instanceof Boolean) {
            managed.add("video_shows_logo");
            if ((Boolean) videoLogo != settings.isVideoLogoEnabled()) {
                settings.setVideoLogoEnabled((Boolean) videoLogo);
                changed = true;
            }
        }

        settings.setServerDesignKeys(managed);
        return changed;
    }

    /** Whether any of {@code keys} is currently managed by the server. */
    static boolean managesAny(AppSettings settings, List<String> keys) {
        Set<String> managed = settings.getServerDesignKeys();
        for (String key : keys) {
            if (managed.contains(key)) {
                return true;
            }
        }
        return false;
    }

    private static boolean color(JSONObject json, String key, Set<String> managed,
                                 int current, IntSetter setter) {
        Object raw = json.opt(key);
        if (!(raw instanceof String) || !((String) raw).trim().matches("#[0-9A-Fa-f]{6}")) {
            return false;
        }
        managed.add(key);
        int value = 0xFF000000 | Integer.parseInt(((String) raw).trim().substring(1), 16);
        if (value == current) {
            return false;
        }
        setter.set(value);
        return true;
    }

    /** One of {@code allowed}; a value this app version does not know is skipped. */
    private static boolean choice(JSONObject json, String key, List<String> allowed,
                                  Set<String> managed, String current, TextSetter setter) {
        Object raw = json.opt(key);
        if (!(raw instanceof String) || !allowed.contains(raw)) {
            return false;
        }
        managed.add(key);
        if (raw.equals(current)) {
            return false;
        }
        setter.set((String) raw);
        return true;
    }

    private static boolean number(JSONObject json, String key, int min, int max,
                                  Set<String> managed, int current, IntSetter setter) {
        Object raw = json.opt(key);
        if (!(raw instanceof Number)) {
            return false;
        }
        int value = ((Number) raw).intValue();
        if (value < min || value > max) {
            return false;
        }
        managed.add(key);
        if (value == current) {
            return false;
        }
        setter.set(value);
        return true;
    }
}

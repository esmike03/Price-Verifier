package com.priceverifier;

import java.io.File;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class IdleMediaItem {
    enum Type {
        IMAGE,
        VIDEO
    }

    // Scheduled media is named [CCC_]MMDDYY[_SSSS].ext :
    //   CCC_    - optional: a 3-character alphanumeric code (e.g. S00_) placed
    //             before the date. It is ignored for scheduling; it only labels
    //             the file.
    //   MMDDYY  - Month Day Year: the calendar day the media stays active THROUGH.
    //             It plays from now until the end of that day, then expires.
    //   _SSSS   - optional: how many SECONDS to show the media each time its turn
    //             comes up in the idle rotation (a video loops to fill the time).
    //             When omitted, a video plays once through its full length and an
    //             image uses the default image duration.
    private static final Pattern SCHEDULED_NAME =
            Pattern.compile("^(?:([A-Za-z0-9]{3})_)?(\\d{6})(?:_(\\d{1,4}))?\\.(jpg|jpeg|png|mp4)$",
                    Pattern.CASE_INSENSITIVE);
    private static final Pattern OLD_DEFAULT_NAME =
            Pattern.compile("^default\\.(jpg|jpeg|png|mp4)$", Pattern.CASE_INSENSITIVE);

    final File file;
    final Type type;
    final long endAtMillis;
    final int displaySeconds;   // > 0: fixed seconds per turn; <= 0: use default
    final boolean playOnce;     // video only: play once at full length (no loop)
    final boolean defaultItem;

    private IdleMediaItem(File file, Type type, long endAtMillis, int displaySeconds,
                          boolean playOnce, boolean defaultItem) {
        this.file = file;
        this.type = type;
        this.endAtMillis = endAtMillis;
        this.displaySeconds = displaySeconds;
        this.playOnce = playOnce;
        this.defaultItem = defaultItem;
    }

    static List<IdleMediaItem> load(AppSettings settings) {
        long now = System.currentTimeMillis();
        List<IdleMediaItem> items = new ArrayList<>();
        List<IdleMediaItem> scheduled = new ArrayList<>();

        if (settings.hasBanner()) {
            items.add(new IdleMediaItem(settings.bannerFile(), Type.IMAGE,
                    Long.MAX_VALUE, 0, false, true));
        }
        if (settings.hasVideo()) {
            int videoSeconds = settings.getVideoSeconds();
            items.add(new IdleMediaItem(settings.videoFile(), Type.VIDEO,
                    Long.MAX_VALUE, videoSeconds, videoSeconds <= 0, true));
        }

        File[] files = settings.mediaDir().listFiles();
        if (files != null) {
            for (File file : files) {
                if (file == null || !file.isFile() || file.length() <= 0) {
                    continue;
                }
                Matcher matcher = SCHEDULED_NAME.matcher(file.getName());
                if (!matcher.matches()) {
                    continue;
                }
                long endAt = parseEndMillis(matcher.group(2));
                if (endAt < now) {
                    continue;
                }
                Type type = typeFromName(file.getName());
                int seconds = parseSeconds(matcher.group(3));
                boolean playOnce = type == Type.VIDEO && seconds <= 0;
                scheduled.add(new IdleMediaItem(file, type, endAt,
                        Math.max(seconds, 0), playOnce, false));
            }
        }

        sort(scheduled);
        items.addAll(scheduled);
        return items;
    }

    /**
     * Stable token used to place this item in the playback order: "banner" for
     * the banner image, "video" for the idle video, or the (lower-cased) file
     * name for a scheduled clip. Matches the tokens the server portal emits.
     */
    String orderKey() {
        if (defaultItem) {
            return type == Type.VIDEO ? "video" : "banner";
        }
        return file.getName().toLowerCase(Locale.US);
    }

    /** Splits a stored order string (newline- or comma-separated) into tokens. */
    static List<String> parseOrder(String order) {
        List<String> tokens = new ArrayList<>();
        if (order == null || order.isEmpty()) {
            return tokens;
        }
        for (String raw : order.split("[\\r\\n,]+")) {
            String token = raw.trim().toLowerCase(Locale.US);
            if (!token.isEmpty() && !token.startsWith("#")) {
                tokens.add(token);
            }
        }
        return tokens;
    }

    static boolean isScheduledMediaName(String name) {
        return name != null && SCHEDULED_NAME.matcher(name).matches();
    }

    static boolean isSupportedMediaName(String name) {
        if (name == null) {
            return false;
        }
        String lower = name.toLowerCase(Locale.US);
        return lower.endsWith(".jpg") || lower.endsWith(".jpeg")
                || lower.endsWith(".png") || lower.endsWith(".mp4");
    }

    /**
     * The instant a scheduled item expires (the midnight after its MMDDYY day),
     * or -1 if the name is not a valid scheduled name.
     */
    static long scheduledEndMillis(String name) {
        if (name == null) {
            return -1L;
        }
        Matcher matcher = SCHEDULED_NAME.matcher(name);
        return matcher.matches() ? parseEndMillis(matcher.group(2)) : -1L;
    }

    /**
     * The per-turn display seconds encoded in a scheduled name, or -1 when the
     * name carries no duration suffix (or is not a valid scheduled name).
     */
    static int scheduledDisplaySeconds(String name) {
        if (name == null) {
            return -1;
        }
        Matcher matcher = SCHEDULED_NAME.matcher(name);
        return matcher.matches() ? parseSeconds(matcher.group(3)) : -1;
    }

    static boolean isVideoName(String name) {
        return name != null && typeFromName(name) == Type.VIDEO;
    }

    static boolean isManagedMediaName(String name) {
        return isScheduledMediaName(name)
                || (name != null && OLD_DEFAULT_NAME.matcher(name).matches());
    }

    private static void sort(List<IdleMediaItem> items) {
        Collections.sort(items, Comparator
                .comparingLong((IdleMediaItem item) -> item.endAtMillis)
                .thenComparing(item -> item.file.getName().toLowerCase(Locale.US)));
    }

    private static Type typeFromName(String name) {
        return name.toLowerCase(Locale.US).endsWith(".mp4") ? Type.VIDEO : Type.IMAGE;
    }

    private static int parseSeconds(String seconds) {
        if (seconds == null) {
            return -1;
        }
        try {
            int value = Integer.parseInt(seconds);
            return value > 0 ? value : -1;
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    private static long parseEndMillis(String date) {
        try {
            int month = Integer.parseInt(date.substring(0, 2));
            int day = Integer.parseInt(date.substring(2, 4));
            int year = 2000 + Integer.parseInt(date.substring(4, 6));
            if (month < 1 || month > 12 || day < 1 || day > 31) {
                return -1L;
            }
            Calendar calendar = Calendar.getInstance();
            calendar.setLenient(false);
            calendar.clear();
            calendar.set(year, month - 1, day, 0, 0, 0);
            calendar.getTimeInMillis(); // force validation (e.g. rejects Feb 30)
            calendar.add(Calendar.DAY_OF_MONTH, 1); // expire at the end of that day
            return calendar.getTimeInMillis();
        } catch (Exception ignored) {
            return -1L;
        }
    }
}

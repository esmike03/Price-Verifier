package com.priceverifier;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

final class LanSync {
    enum Scope {
        ALL,
        ITEMS,
        IMAGES,
        VIDEOS
    }

    static final class Result {
        final boolean catalogSynced;
        final ProductImportResult catalog;
        final boolean logoSynced;
        final boolean bannerSynced;
        final boolean videoSynced;
        final boolean mediaSynced;
        final boolean marqueeSynced;
        final String marqueeText;
        final boolean serverReached;
        /** Server promos received this sync, or -1 when promos.csv was unchanged or absent. */
        final int promosSynced;
        /** Whether the server design changed any of this device's settings. */
        final boolean designSynced;

        Result(boolean catalogSynced, ProductImportResult catalog, boolean logoSynced,
               boolean bannerSynced, boolean videoSynced, boolean mediaSynced, boolean marqueeSynced,
               String marqueeText, boolean serverReached, int promosSynced, boolean designSynced) {
            this.promosSynced = promosSynced;
            this.designSynced = designSynced;
            this.catalogSynced = catalogSynced;
            this.catalog = catalog;
            this.logoSynced = logoSynced;
            this.bannerSynced = bannerSynced;
            this.videoSynced = videoSynced;
            this.mediaSynced = mediaSynced;
            this.marqueeSynced = marqueeSynced;
            this.marqueeText = marqueeText;
            this.serverReached = serverReached;
        }

        boolean anythingChanged() {
            return catalogSynced || logoSynced || bannerSynced || videoSynced || mediaSynced
                    || marqueeSynced || promosSynced >= 0 || designSynced;
        }
    }

    private static final class Fetch {
        final byte[] data;
        final String lastModified;
        final boolean reached;

        Fetch(byte[] data, String lastModified, boolean reached) {
            this.data = data;
            this.lastModified = lastModified;
            this.reached = reached;
        }
    }

    private static final class CatalogFetch {
        final ProductImportResult result;
        final String lastModified;
        final boolean reached;
        final boolean notFound;

        CatalogFetch(ProductImportResult result, String lastModified, boolean reached,
                     boolean notFound) {
            this.result = result;
            this.lastModified = lastModified;
            this.reached = reached;
            this.notFound = notFound;
        }
    }

    private static final class MediaEntry {
        final String name;
        final long size;

        MediaEntry(String name, long size) {
            this.name = name;
            this.size = size;
        }
    }

    private static final int CONNECT_TIMEOUT_MS = 8000;
    private static final int READ_TIMEOUT_MS = 120000;
    private static final int MAX_BYTES = 100 * 1024 * 1024;
    private static final String MEDIA_MANIFEST_MARKER = "# price-verifier-media-manifest";

    private final ProductDatabase database;
    private final AppSettings settings;

    LanSync(ProductDatabase database, AppSettings settings) {
        this.database = database;
        this.settings = settings;
    }

    Result run(String baseUrl) throws Exception {
        return run(baseUrl, Scope.ALL, false);
    }

    Result run(String baseUrl, boolean forceRefresh) throws Exception {
        return run(baseUrl, Scope.ALL, forceRefresh);
    }

    Result run(String baseUrl, Scope scope, boolean forceRefresh) throws Exception {
        String base = normalizeBase(baseUrl);
        if (base.isEmpty()) {
            throw new IllegalArgumentException("LAN URL is empty.");
        }

        boolean serverReached = false;
        boolean syncItems = scope == Scope.ALL || scope == Scope.ITEMS;
        boolean syncImages = scope == Scope.ALL || scope == Scope.IMAGES;
        boolean syncVideos = scope == Scope.ALL || scope == Scope.VIDEOS;

        // Some web servers answer an address that does not exist with their own
        // web page and "200 OK" instead of "404 Not Found". An earlier sync from
        // such a server saved that page as the banner, logo, video or scrolling
        // message. Discard anything like that so the real files are fetched again.
        boolean logoDiscarded = syncImages && discardIfHtml(settings.logoFile(), "logo");
        boolean bannerDiscarded = syncImages && discardIfHtml(settings.bannerFile(), "banner");
        boolean videoDiscarded = syncVideos && discardIfHtml(settings.videoFile(), "video");
        boolean marqueeDiscarded = false;
        if (syncImages && looksLikeHtml(settings.getMarqueeText().getBytes(StandardCharsets.UTF_8))) {
            settings.setMarqueeText("");
            settings.setLastModified("marquee", "");
            marqueeDiscarded = true;
        }

        ProductImportResult catalogResult = null;
        boolean catalogSynced = false;
        if (syncItems) {
            String catalogLastModified = forceRefresh ? "" : settings.getLastModified("catalog");
            CatalogFetch catalogFetch = fetchCatalog(base + "catalog.csv", catalogLastModified);
            if (catalogFetch.notFound) {
                CatalogFetch alt = fetchCatalog(base + "catalog.txt", catalogLastModified);
                catalogFetch = alt;
            }
            serverReached = serverReached || catalogFetch.reached;
            if (catalogFetch.result != null) {
                catalogResult = catalogFetch.result;
                catalogSynced = true;
                if (catalogFetch.lastModified != null) {
                    settings.setLastModified("catalog", catalogFetch.lastModified);
                }
            }
        }

        // promos.csv is optional: a 404 (older server) or a network error leaves the
        // device's promos alone; the portal keeps the file (header only) once promos
        // have been cleared, so an empty download means "no server promos".
        int promosSynced = -1;
        if (syncItems) {
            String promosLastModified = forceRefresh ? "" : settings.getLastModified("promos");
            Fetch promosFetch = fetch(base + "promos.csv", promosLastModified);
            if (promosFetch != null) {
                serverReached = serverReached || promosFetch.reached;
                if (promosFetch.data != null && !looksLikeHtml(promosFetch.data)) {
                    try {
                        List<Promo> promos = new PromoImporter().parse(
                                new java.io.ByteArrayInputStream(promosFetch.data));
                        database.replaceServerPromos(promos);
                        promosSynced = promos.size();
                        if (promosFetch.lastModified != null) {
                            settings.setLastModified("promos", promosFetch.lastModified);
                        }
                    } catch (IllegalArgumentException malformed) {
                        // A broken promos.csv must not fail the catalog/media sync;
                        // the previous server promos stay until a good file arrives.
                    }
                }
            }
        }

        boolean logoSynced = logoDiscarded;
        if (syncImages) {
            String logoLastModified = forceRefresh ? "" : settings.getLastModified("logo");
            Fetch logoFetch = fetch(base + "logo.png", logoLastModified);
            if (logoFetch == null || !logoFetch.reached) {
                logoFetch = fetch(base + "logo.jpg", logoLastModified);
            }
            if (logoFetch != null) {
                serverReached = serverReached || logoFetch.reached;
                if (logoFetch.data != null && isImage(logoFetch.data)
                        && writeFile(logoFetch.data, settings.logoFile())) {
                    logoSynced = true;
                    if (logoFetch.lastModified != null) {
                        settings.setLastModified("logo", logoFetch.lastModified);
                    }
                }
            }
        }

        boolean bannerSynced = bannerDiscarded;
        if (syncImages) {
            String bannerLastModified = forceRefresh ? "" : settings.getLastModified("banner");
            Fetch bannerFetch = fetch(base + "banner.jpg", bannerLastModified);
            if (bannerFetch == null || !bannerFetch.reached) {
                bannerFetch = fetch(base + "banner.png", bannerLastModified);
            }
            if (bannerFetch != null) {
                serverReached = serverReached || bannerFetch.reached;
                if (bannerFetch.data != null && isImage(bannerFetch.data)
                        && writeFile(bannerFetch.data, settings.bannerFile())) {
                    bannerSynced = true;
                    if (bannerFetch.lastModified != null) {
                        settings.setLastModified("banner", bannerFetch.lastModified);
                    }
                }
            }
        }

        boolean videoSynced = videoDiscarded;
        if (videoDiscarded) {
            settings.setVideoPlaybackError("");
        }
        if (syncVideos) {
            String videoLastModified = forceRefresh ? "" : settings.getLastModified("video");
            Fetch videoFetch = fetch(base + "video.mp4", videoLastModified);
            if (videoFetch != null) {
                serverReached = serverReached || videoFetch.reached;
                if (videoFetch.data != null && isMp4(videoFetch.data)
                        && writeFile(videoFetch.data, settings.videoFile())) {
                    videoSynced = true;
                    settings.setVideoPlaybackError("");
                    if (videoFetch.lastModified != null) {
                        settings.setLastModified("video", videoFetch.lastModified);
                    }
                }
            }
        }

        boolean mediaSynced = false;
        if (syncImages || syncVideos) {
            Fetch mediaManifest = fetch(base + "index.php?media_manifest=1", "");
            if (mediaManifest == null || !mediaManifest.reached) {
                mediaManifest = fetch(base + "media-manifest.txt", "");
            }
            if (mediaManifest != null) {
                serverReached = serverReached || mediaManifest.reached;
                if (mediaManifest.data != null) {
                    List<MediaEntry> entries = parseMediaManifest(mediaManifest.data);
                    if (entries != null) {
                        Boolean videoOnly = scope == Scope.ALL ? null : syncVideos;
                        mediaSynced = syncMediaEntries(base, entries, forceRefresh, videoOnly);
                        if (mediaSynced && syncVideos) {
                            settings.setVideoPlaybackError("");
                        }
                    }
                }
            }
        }

        if (syncImages || syncVideos) {
            Fetch orderFetch = fetch(base + "index.php?media_order=1", "");
            if (orderFetch == null || !orderFetch.reached) {
                orderFetch = fetch(base + "media-order.txt", "");
            }
            if (orderFetch != null) {
                serverReached = serverReached || orderFetch.reached;
                if (orderFetch.data != null) {
                    settings.setServerMediaOrder(parseMediaOrder(orderFetch.data));
                }
            }
        }

        // A discarded message counts as a change, so the screen stops showing it.
        boolean marqueeSynced = marqueeDiscarded;
        String marqueeText = "";
        if (syncImages) {
            String marqueeLastModified = forceRefresh ? "" : settings.getLastModified("marquee");
            Fetch marqueeFetch = fetch(base + "marquee.txt", marqueeLastModified);
            if (marqueeFetch != null) {
                serverReached = serverReached || marqueeFetch.reached;
                if (marqueeFetch.data != null && !looksLikeHtml(marqueeFetch.data)) {
                    marqueeText = new String(marqueeFetch.data, StandardCharsets.UTF_8).trim();
                    settings.setMarqueeText(marqueeText);
                    marqueeSynced = true;
                    if (marqueeFetch.lastModified != null) {
                        settings.setLastModified("marquee", marqueeFetch.lastModified);
                    }
                }
            }
        }

        // design.json is fetched on every sync, not only when it changed: the server
        // wins, so a change made in this device's Admin screen must be replaced
        // even when the file itself is unchanged. A 404 (older server) or a broken
        // file leaves this device's own settings alone.
        boolean designSynced = false;
        Fetch designFetch = fetch(base + "design.json", "");
        if (designFetch != null) {
            serverReached = serverReached || designFetch.reached;
            if (designFetch.data != null) {
                try {
                    designSynced = ServerDesign.apply(settings, designFetch.data);
                } catch (org.json.JSONException malformed) {
                    // Keep the previous design until the server serves a valid file.
                }
            }
        }

        if (!serverReached) {
            throw new IOException("Server not reachable at " + base);
        }

        settings.setLastSync(System.currentTimeMillis());
        return new Result(catalogSynced, catalogResult, logoSynced, bannerSynced,
                videoSynced, mediaSynced, marqueeSynced, marqueeText, true, promosSynced,
                designSynced);
    }

    /**
     * True when the server answered with a web page. Nothing a scanner downloads
     * is HTML, so such a reply is the server's own page standing in for a file
     * that does not exist.
     */
    private static boolean isHtmlReply(HttpURLConnection conn) {
        String type = conn.getContentType();
        return type != null && type.trim().toLowerCase(java.util.Locale.US).startsWith("text/html");
    }

    /** Text that starts like a web page, ignoring a byte-order mark and leading blanks. */
    static boolean looksLikeHtml(byte[] data) {
        if (data == null) {
            return false;
        }
        int start = 0;
        if (data.length >= 3 && (data[0] & 0xFF) == 0xEF && (data[1] & 0xFF) == 0xBB
                && (data[2] & 0xFF) == 0xBF) {
            start = 3;
        }
        while (start < data.length && data[start] >= 0 && data[start] <= ' ') {
            start++;
        }
        int length = Math.min(15, data.length - start);
        String head = new String(data, start, length, StandardCharsets.ISO_8859_1)
                .toLowerCase(java.util.Locale.US);
        return head.startsWith("<!doctype html") || head.startsWith("<html");
    }

    /** PNG or JPEG, by the file signature. */
    static boolean isImage(byte[] data) {
        if (data == null || data.length < 4) {
            return false;
        }
        boolean png = (data[0] & 0xFF) == 0x89 && data[1] == 'P' && data[2] == 'N' && data[3] == 'G';
        boolean jpeg = (data[0] & 0xFF) == 0xFF && (data[1] & 0xFF) == 0xD8 && (data[2] & 0xFF) == 0xFF;
        return png || jpeg;
    }

    /** An MP4-family video: the "ftyp" box comes first. */
    static boolean isMp4(byte[] data) {
        return data != null && data.length >= 12
                && data[4] == 'f' && data[5] == 't' && data[6] == 'y' && data[7] == 'p';
    }

    /**
     * Deletes a stored media file that is really a web page (see run()), and
     * forgets its timestamp so the next download is not skipped as "unchanged".
     * Anything else is left alone, including images picked on the device in
     * other formats. Returns true when the file was removed.
     */
    private boolean discardIfHtml(File file, String asset) {
        if (file == null || !file.exists() || file.length() == 0) {
            return false;
        }
        byte[] head = new byte[64];
        int read = 0;
        try (InputStream in = new FileInputStream(file)) {
            int count;
            while (read < head.length && (count = in.read(head, read, head.length - read)) != -1) {
                read += count;
            }
        } catch (IOException unreadable) {
            return false;
        }
        if (!looksLikeHtml(java.util.Arrays.copyOf(head, read)) || !file.delete()) {
            return false;
        }
        settings.setLastModified(asset, "");
        return true;
    }

    private static String normalizeBase(String url) {
        String trimmed = url == null ? "" : url.trim();
        if (trimmed.isEmpty()) {
            return "";
        }
        if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
            trimmed = "http://" + trimmed;
        }
        if (!trimmed.endsWith("/")) {
            trimmed = trimmed + "/";
        }
        return trimmed;
    }

    private static Fetch fetch(String url, String ifModifiedSince) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setRequestMethod("GET");
            if (ifModifiedSince != null && !ifModifiedSince.isEmpty()) {
                conn.setRequestProperty("If-Modified-Since", ifModifiedSince);
            }
            int code = conn.getResponseCode();
            if (code == HttpURLConnection.HTTP_NOT_MODIFIED) {
                return new Fetch(null, ifModifiedSince, true);
            }
            if (code == HttpURLConnection.HTTP_NOT_FOUND) {
                return null;
            }
            if (code != HttpURLConnection.HTTP_OK) {
                return new Fetch(null, null, true);
            }
            if (isHtmlReply(conn)) {
                return null;    // the server's own page, not the file: same as "not found"
            }
            String lastMod = conn.getHeaderField("Last-Modified");
            try (InputStream in = conn.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int read;
                int total = 0;
                while ((read = in.read(buf)) != -1) {
                    total += read;
                    if (total > MAX_BYTES) {
                        return new Fetch(null, null, true);
                    }
                    out.write(buf, 0, read);
                }
                return new Fetch(out.toByteArray(), lastMod, true);
            }
        } catch (IOException ex) {
            return null;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private CatalogFetch fetchCatalog(String url, String ifModifiedSince) throws Exception {
        HttpURLConnection conn = null;
        File temp = settings.catalogDownloadFile();
        try {
            if (temp.exists() && !temp.delete()) {
                throw new IOException("Could not clear the previous catalog download.");
            }
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setRequestMethod("GET");
            if (ifModifiedSince != null && !ifModifiedSince.isEmpty()) {
                conn.setRequestProperty("If-Modified-Since", ifModifiedSince);
            }
            int code = conn.getResponseCode();
            if (code == HttpURLConnection.HTTP_NOT_MODIFIED) {
                return new CatalogFetch(null, ifModifiedSince, true, false);
            }
            if (code == HttpURLConnection.HTTP_NOT_FOUND) {
                return new CatalogFetch(null, null, true, true);
            }
            if (code != HttpURLConnection.HTTP_OK) {
                throw new IOException("Catalog download failed (HTTP " + code + ").");
            }
            if (isHtmlReply(conn)) {
                return new CatalogFetch(null, null, true, true);
            }
            int length = conn.getContentLength();
            if (length > MAX_BYTES) {
                throw new IOException("Catalog is larger than the 100 MB safety limit.");
            }
            String lastMod = conn.getHeaderField("Last-Modified");
            int total = 0;
            try (InputStream in = conn.getInputStream();
                 FileOutputStream out = new FileOutputStream(temp)) {
                byte[] buffer = new byte[32 * 1024];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    total += read;
                    if (total > MAX_BYTES) {
                        throw new IOException("Catalog is larger than the 100 MB safety limit.");
                    }
                    out.write(buffer, 0, read);
                }
                out.getFD().sync();
            }
            if (total <= 0) {
                throw new IOException("Catalog download was empty.");
            }
            if (length >= 0 && total != length) {
                throw new IOException("Catalog download was incomplete: received " + total +
                        " of " + length + " bytes.");
            }

            conn.disconnect();
            conn = null;
            try (InputStream in = new FileInputStream(temp)) {
                ProductImportResult result = new ProductImporter()
                        .importIntoDatabase(in, database, "LAN catalog");
                return new CatalogFetch(result, lastMod, true, false);
            }
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
            if (temp.exists()) {
                //noinspection ResultOfMethodCallIgnored
                temp.delete();
            }
        }
    }

    private boolean syncMediaEntries(String base, List<MediaEntry> entries, boolean forceRefresh,
                                     Boolean videoOnly) {
        boolean changed = false;
        File dir = settings.mediaDir();
        Set<String> expected = new HashSet<>();
        for (MediaEntry entry : entries) {
            boolean isVideo = IdleMediaItem.isVideoName(entry.name);
            if (videoOnly != null && videoOnly != isVideo) {
                continue;
            }
            expected.add(entry.name);
            File target = new File(dir, entry.name);
            if (!forceRefresh && target.exists() && target.length() == entry.size && entry.size > 0) {
                continue;
            }
            Fetch mediaFetch = fetch(base + encodePath(entry.name), "");
            if (mediaFetch != null && mediaFetch.data != null && writeFile(mediaFetch.data, target)) {
                changed = true;
            }
        }

        File[] localFiles = dir.listFiles();
        if (localFiles != null) {
            for (File local : localFiles) {
                boolean matchesScope = videoOnly == null
                        || videoOnly == IdleMediaItem.isVideoName(local.getName());
                if (local.isFile() && matchesScope && IdleMediaItem.isManagedMediaName(local.getName())
                        && !expected.contains(local.getName())) {
                    //noinspection ResultOfMethodCallIgnored
                    local.delete();
                    changed = true;
                }
            }
        }
        return changed;
    }

    private static List<MediaEntry> parseMediaManifest(byte[] data) {
        String text = new String(data, StandardCharsets.UTF_8).replace("\r\n", "\n");
        String[] lines = text.split("\n");
        if (lines.length == 0 || !MEDIA_MANIFEST_MARKER.equals(lines[0].trim())) {
            return null;
        }
        List<MediaEntry> entries = new ArrayList<>();
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            String[] parts = line.split(",", 3);
            if (parts.length < 2) {
                continue;
            }
            String name = parts[0].trim();
            if (!IdleMediaItem.isSupportedMediaName(name)) {
                continue;
            }
            try {
                long size = Long.parseLong(parts[1].trim());
                if (size > 0 && size <= MAX_BYTES) {
                    entries.add(new MediaEntry(name, size));
                }
            } catch (NumberFormatException ignored) {
            }
        }
        return entries;
    }

    private static String parseMediaOrder(byte[] data) {
        String text = new String(data, StandardCharsets.UTF_8).replace("\r\n", "\n");
        StringBuilder sb = new StringBuilder();
        for (String line : text.split("\n")) {
            String token = line.trim().toLowerCase(Locale.US);
            if (token.isEmpty() || token.startsWith("#")) {
                continue;
            }
            if (token.equals("banner") || token.equals("video")
                    || IdleMediaItem.isSupportedMediaName(token)) {
                if (sb.length() > 0) {
                    sb.append('\n');
                }
                sb.append(token);
            }
        }
        return sb.toString();
    }

    private static String encodePath(String name) {
        try {
            return URLEncoder.encode(name, "UTF-8").replace("+", "%20");
        } catch (Exception ignored) {
            return name.toLowerCase(Locale.US);
        }
    }

    private static boolean writeFile(byte[] data, File target) {
        if (data == null || data.length == 0) {
            return false;
        }
        try (FileOutputStream out = new FileOutputStream(target)) {
            out.write(data);
            return true;
        } catch (IOException ex) {
            return false;
        }
    }
}

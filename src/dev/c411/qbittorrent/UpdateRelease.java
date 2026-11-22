package dev.c411.qbittorrent;

import java.util.List;
import java.util.Map;

/** What the release answer of GitHub says about an update, checked field by field: nothing in it is used before it has been. */
final class UpdateRelease {
    static final String APK_NAME = "Arr411.apk";
    static final String SUMS_NAME = "SHA256SUMS.txt";
    static final int NOTES_MAX = 4000;
    /** The most that is read of the answer of the API, and the most that is kept of it for the next start. */
    static final int ANSWER_MAX_BYTES = 1024 * 1024;
    static final int CACHE_MAX_CHARS = 256 * 1024;

    /** The tag as published ("v2.3.1"); only digits and dots follow its optional v, so it can go into an address. */
    final String tag;
    /** The version without the v ("2.3.1"). */
    final String version;
    /** The first characters of the release notes; empty when there are none. */
    final String notes;
    /** The page of the release, built from the tag. */
    final String pageUrl;
    /** Where Arr411.apk is; null when the release has no usable one. */
    final String apkUrl;
    final long size;
    /** The SHA-256 of the "digest" field of the API in lowercase; null when it has none. */
    final String digest;
    /** The API gave a digest that is not a SHA-256: no hash can be trusted. */
    final boolean digestInvalid;
    /** Where SHA256SUMS.txt is; null when the release has none or it is not usable. */
    final String sumsUrl;
    /** The release has a SHA256SUMS.txt, usable or not: when it is there it has to be read. */
    final boolean sumsPresent;

    private UpdateRelease(String tag, String version, String notes, String apkUrl, long size, String digest, boolean digestInvalid, String sumsUrl,
                          boolean sumsPresent) {
        this.tag = tag;
        this.version = version;
        this.notes = notes;
        this.pageUrl = UpdateHosts.TAG_PAGE + tag;
        this.apkUrl = apkUrl;
        this.size = size;
        this.digest = digest;
        this.digestInvalid = digestInvalid;
        this.sumsUrl = sumsUrl;
        this.sumsPresent = sumsPresent;
    }

    /**
     * The release in the answer of {@code /releases/latest}; null when there is no release to offer (a draft, a prerelease, a tag that is not a
     * version). IllegalArgumentException when the answer is not JSON or not an object.
     */
    static UpdateRelease parse(String json) {
        Object root = UpdateJson.parse(json);
        if (!(root instanceof Map)) throw new IllegalArgumentException("The answer is not an object");
        Map<?, ?> release = (Map<?, ?>) root;
        if (Boolean.TRUE.equals(release.get("draft")) || Boolean.TRUE.equals(release.get("prerelease"))) return null;
        Object tag = release.get("tag_name");
        String version = tag instanceof String ? UpdateVersion.normalize((String) tag) : null;
        if (version == null) return null;

        Map<?, ?> apk = null;
        Map<?, ?> sums = null;
        int apks = 0;
        int sumsFiles = 0;
        Object assets = release.get("assets");
        if (assets instanceof List) {
            for (Object item : (List<?>) assets) {
                if (!(item instanceof Map)) continue;
                Map<?, ?> asset = (Map<?, ?>) item;
                Object name = asset.get("name");
                if (APK_NAME.equals(name)) {
                    apks++;
                    apk = asset;
                } else if (SUMS_NAME.equals(name)) {
                    sumsFiles++;
                    sums = asset;
                }
            }
        }

        String apkUrl = null;
        long size = 0;
        String digest = null;
        boolean digestInvalid = false;
        if (apks == 1 && uploaded(apk)) {
            apkUrl = assetUrl(apk.get("browser_download_url"), APK_NAME);
            if (apk.get("size") instanceof Long) size = (Long) apk.get("size");
            Object published = apk.get("digest");
            if (published != null) {
                digest = published instanceof String ? UpdateIntegrity.parseDigest((String) published) : null;
                digestInvalid = digest == null;
            }
        }
        String sumsUrl = sumsFiles == 1 && uploaded(sums) ? assetUrl(sums.get("browser_download_url"), SUMS_NAME) : null;

        Object body = release.get("body");
        String notes = body instanceof String ? excerpt((String) body, NOTES_MAX) : "";
        return new UpdateRelease((String) tag, version, notes, apkUrl, size, digest, digestInvalid, sumsUrl, sumsFiles > 0);
    }

    /** True when the release can be installed from the app: a usable file of a size that is allowed, and at least one hash to check it with, none of them unusable. */
    boolean installable() {
        if (apkUrl == null || !UpdateIntegrity.sizeOk(size) || digestInvalid) return false;
        if (sumsPresent && sumsUrl == null) return false;
        return digest != null || sumsUrl != null;
    }

    /** True when a file that was downloaded and verified (its version, its size and the SHA-256 it was verified with) is the one of this release. */
    boolean isFile(String fileVersion, long fileSize, String fileSha256) {
        return fileVersion != null && fileSha256 != null && UpdateVersion.same(fileVersion, version) && fileSize == size
            && (digest == null || digest.equals(fileSha256));
    }

    /** The first {@code max} characters of the notes, without cutting a pair of surrogates in two. */
    static String excerpt(String body, int max) {
        if (body == null) return "";
        if (body.length() <= max) return body;
        int end = max;
        if (end > 0 && Character.isHighSurrogate(body.charAt(end - 1)) && Character.isLowSurrogate(body.charAt(end))) end--;
        return body.substring(0, end);
    }

    private static boolean uploaded(Map<?, ?> asset) {
        Object state = asset.get("state");
        return state == null || "uploaded".equals(state);
    }

    private static String assetUrl(Object url, String name) {
        if (!(url instanceof String)) return null;
        String text = (String) url;
        return UpdateHosts.isAssetUrl(text) && text.endsWith("/" + name) ? text : null;
    }
}

package dev.c411.qbittorrent;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.Locale;

/**
 * Where the update may come from. GitHub answers the API and sends the file from its CDN, so the redirects are followed one by one, and only
 * between the hosts of GitHub, over https: nothing else ever receives a request.
 */
final class UpdateHosts {
    static final String API_LATEST = "https://api.github.com/repos/HugoMeyer/arr411-android/releases/latest";
    static final String RELEASES_PAGE = "https://github.com/HugoMeyer/arr411-android/releases";
    static final String TAG_PAGE = "https://github.com/HugoMeyer/arr411-android/releases/tag/";
    private static final String ASSET_PATH = "/HugoMeyer/arr411-android/releases/download/";
    private static final String[] HOSTS = {"api.github.com", "github.com", "objects.githubusercontent.com", "release-assets.githubusercontent.com"};
    private static final int MAX_URL = 2048;

    private UpdateHosts() {}

    /** The result of a redirect: where to go, or why not. */
    static final class Hop {
        /** The next address; null when the redirect is refused. */
        final URL url;
        /** A French sentence for the user; null when the redirect is allowed. */
        final String refusal;

        private Hop(URL url, String refusal) {
            this.url = url;
            this.refusal = refusal;
        }
    }

    /** True for https on the standard port, on one of the hosts of GitHub, without credentials in the address. */
    static boolean isAllowed(URL url) {
        if (url == null || !"https".equalsIgnoreCase(url.getProtocol()) || url.getUserInfo() != null) return false;
        if (url.getPort() != -1 && url.getPort() != 443) return false;
        String text = url.toExternalForm();
        if (text.length() > MAX_URL) return false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c <= ' ' || c == 0x7f || c == '\\') return false;
        }
        String host = url.getHost().toLowerCase(Locale.ROOT);
        for (String allowed : HOSTS) {
            if (allowed.equals(host)) return true;
        }
        return false;
    }

    /** The address of a file of a release of this repository, as the API gives it: on github.com, under the download path, with no way up the tree. */
    static boolean isAssetUrl(String text) {
        if (text == null || text.length() > MAX_URL) return false;
        URL url;
        try {
            url = new URL(text);
        } catch (MalformedURLException e) {
            return false;
        }
        if (!isAllowed(url) || !"github.com".equalsIgnoreCase(url.getHost()) || url.getQuery() != null || url.getRef() != null) return false;
        String path = url.getPath();
        if (path.length() <= ASSET_PATH.length() || !path.regionMatches(true, 0, ASSET_PATH, 0, ASSET_PATH.length())) return false;
        String lower = path.toLowerCase(Locale.ROOT);
        return !lower.contains("/../") && !lower.contains("/./") && !lower.endsWith("/..") && !lower.endsWith("/.")
            && !lower.contains("%2e") && !lower.contains("%2f") && !lower.contains("%5c") && !lower.contains("//");
    }

    /** Where a redirect leads, after {@code followed} redirects, or why it is refused. */
    static Hop next(URL current, int status, String location, int followed) {
        if (!RedirectPolicy.isRedirect(status)) return new Hop(null, "Réponse inattendue du serveur (HTTP " + status + ")");
        if (followed >= RedirectPolicy.MAX_REDIRECTS) return new Hop(null, "Trop de redirections");
        URL target = RedirectPolicy.resolve(current, location);
        if (target == null) return new Hop(null, "Redirection invalide");
        if (!isAllowed(target)) return new Hop(null, RedirectPolicy.refusal(target));
        return new Hop(target, null);
    }
}

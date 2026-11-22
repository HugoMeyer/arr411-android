package dev.c411.qbittorrent;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.Locale;

/**
 * Which redirects a request to a server the user configured may follow: only the ones that stay on that server, so that
 * the headers of the request (X-Api-Key, Authorization, cookies) never travel to another host.
 */
final class RedirectPolicy {
    static final int MAX_REDIRECTS = 5;

    private RedirectPolicy() {}

    static boolean isRedirect(int status) {
        return status == 301 || status == 302 || status == 303 || status == 307 || status == 308;
    }

    /** The absolute address a Location header leads to, or null when it is not an address. */
    static URL resolve(URL current, String location) {
        if (location == null || location.trim().isEmpty()) return null;
        try {
            return new URL(current, location.trim());
        } catch (MalformedURLException e) {
            return null;
        }
    }

    /** Same host (case-insensitive), http or https, never back from https to http; the port may only change on an http to https upgrade. */
    static boolean staysOnServer(URL from, URL to) {
        String a = from.getProtocol().toLowerCase(Locale.ROOT);
        String b = to.getProtocol().toLowerCase(Locale.ROOT);
        boolean upgrade = a.equals("http") && b.equals("https");
        if (!(a.equals(b) || upgrade) || !(a.equals("http") || a.equals("https"))) return false;
        if (from.getHost().isEmpty() || !from.getHost().equalsIgnoreCase(to.getHost())) return false;
        return upgrade || port(from) == port(to);
    }

    /** The reason shown to the user when a redirect is refused. */
    static String refusal(URL to) {
        String host = to.getHost().isEmpty() ? "?" : to.getHost();
        return "Redirection vers un autre serveur refusée (" + host + (to.getPort() == -1 ? "" : ":" + to.getPort()) + ")";
    }

    /** The method of the request that follows: 301 and 302 turn a POST into a GET, 303 turns anything but GET and HEAD into a GET. */
    static String nextMethod(int status, String method) {
        if (status == 303) return method.equals("GET") || method.equals("HEAD") ? method : "GET";
        if ((status == 301 || status == 302) && method.equals("POST")) return "GET";
        return method;
    }

    private static int port(URL u) {
        return u.getPort() != -1 ? u.getPort() : u.getDefaultPort();
    }
}

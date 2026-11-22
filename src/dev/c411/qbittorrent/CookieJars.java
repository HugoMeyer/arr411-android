package dev.c411.qbittorrent;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The cookies the servers of the user set (qBittorrent's SID). One jar per server (scheme, host and port): a request only carries
 * what its own server gave, and nothing else in the process can read or send them. Pure Java: see test/PureTests.java.
 */
final class CookieJars {
    static final int MAX_JARS = 32;

    private final Map<String, CookieManager> jars = new ConcurrentHashMap<>();

    /** The Cookie header of a request to this URL, or null when its server set none. */
    String header(URL url) {
        CookieManager jar = jars.get(origin(url));
        URI uri = uri(url);
        if (jar == null || uri == null) return null;
        try {
            List<String> cookies = jar.get(uri, Collections.<String, List<String>>emptyMap()).get("Cookie");
            return cookies == null || cookies.isEmpty() ? null : String.join("; ", cookies);
        } catch (Exception e) {
            return null;
        }
    }

    /** Keeps the cookies of a response; {@code fields} are its header fields, as HttpURLConnection.getHeaderFields() gives them. */
    void keep(URL url, Map<String, List<String>> fields) {
        URI uri = uri(url);
        if (uri == null || fields == null) return;
        boolean any = false;
        for (String name : fields.keySet()) any |= name != null && name.equalsIgnoreCase("Set-Cookie");
        if (!any) return;
        String key = origin(url);
        if (jars.size() >= MAX_JARS && !jars.containsKey(key)) jars.clear(); // the next request logs in again
        try {
            jars.computeIfAbsent(key, k -> new CookieManager(null, CookiePolicy.ACCEPT_ALL)).put(uri, fields);
        } catch (Exception ignored) {
        }
    }

    /** scheme://host[:port] of the server the URL points to. */
    static String origin(URL u) {
        return u.getProtocol() + "://" + u.getHost().toLowerCase(Locale.ROOT) + (u.getPort() == -1 ? "" : ":" + u.getPort());
    }

    /**
     * The address the cookies are matched on: scheme, host, port and path. The query is left out and the path is quoted, because
     * URL.toURI() refuses what the engine really sends (the "|" of a hash list), which would silently cost the request its cookie.
     */
    static URI uri(URL url) {
        try {
            String path = url.getPath();
            String quoted = new URI("http", "x", path.isEmpty() ? "/" : path, null).getRawPath();
            return new URI(url.getProtocol() + "://" + url.getHost() + (url.getPort() == -1 ? "" : ":" + url.getPort()) + quoted);
        } catch (URISyntaxException e) {
            return null;
        }
    }
}

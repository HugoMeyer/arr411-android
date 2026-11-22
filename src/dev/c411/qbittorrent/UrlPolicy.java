package dev.c411.qbittorrent;

import java.util.Locale;

/**
 * Which addresses the app loads in the C411 view, fetches for the page or hands to another app.
 * No android.* import, so the rules are covered by test/PureTests.java.
 */
final class UrlPolicy {
    static final int MAX_URL = 8192;

    private UrlPolicy() {}

    /** c411.org or one of its subdomains. Expects a bare host name (no port, no brackets). */
    static boolean isC411Host(String host) {
        if (host == null) return false;
        String h = host.toLowerCase(Locale.ROOT);
        return h.equals("c411.org") || (h.length() > 9 && h.endsWith(".c411.org"));
    }

    /**
     * The https address to load in the C411 view for an address coming from an intent, a share or a page, or null.
     * Only c411.org and its subdomains pass, over https (http is upgraded): never javascript:, data:, file:, intent:...
     */
    static String c411Https(String url) {
        Parts p = split(url);
        if (p == null || !isC411Host(p.host)) return null;
        if (p.scheme.equals("https")) return p.port == -1 || p.port == 443 ? url : null;
        return p.port == -1 ? "https" + url.substring(4) : null;
    }

    /** A page of c411.org over https: the only kind the extension is injected into. */
    static boolean isC411Page(String url) {
        return url != null && url.regionMatches(true, 0, "https:", 0, 6) && c411Https(url) != null;
    }

    /** The addresses PageBridge.fetchBytes may request for the page, at every redirect hop: https, port 443, a c411.org host. */
    static boolean isFetchTarget(String url) {
        Parts p = split(url);
        return p != null && p.scheme.equals("https") && (p.port == -1 || p.port == 443) && isC411Host(p.host);
    }

    /** The host of an address accepted by split(), lower-cased, or null. */
    static String hostOf(String url) {
        Parts p = split(url);
        return p == null ? null : p.host;
    }

    /** The only schemes Engine.openUrl hands to another app: never intent:, file:, content:, javascript:... */
    static boolean isExternalOpenable(String url) {
        if (url == null || url.length() > MAX_URL) return false;
        int colon = url.indexOf(':');
        if (colon < 1) return false;
        String scheme = url.substring(0, colon).toLowerCase(Locale.ROOT);
        return scheme.equals("https") || scheme.equals("http") || scheme.equals("magnet");
    }

    /**
     * Whether a resolved address belongs to the public Internet: not loopback, private, link-local, shared, reserved or
     * multicast (same ranges as the Desktop PageFetcher). allowBenchmark tolerates 198.18.0.0/15, the fake addresses
     * that VPN apps hand out for every name: they never reach the user's own network.
     */
    static boolean isPublicAddress(byte[] a, boolean allowBenchmark) {
        if (a == null) return false;
        if (a.length == 4) return publicV4(a[0] & 0xff, a[1] & 0xff, a[2] & 0xff, allowBenchmark);
        if (a.length != 16) return false;

        int b0 = a[0] & 0xff, b1 = a[1] & 0xff, b2 = a[2] & 0xff, b3 = a[3] & 0xff;
        if (isZero(a, 0, 10) && b(a, 10) == 0xff && b(a, 11) == 0xff) return publicV4(b(a, 12), b(a, 13), b(a, 14), allowBenchmark);
        // NAT64 (64:ff9b::/96) and 6to4 (2002::/16) carry an IPv4 address, which is the one that counts.
        if (b0 == 0x00 && b1 == 0x64 && b2 == 0xff && b3 == 0x9b && isZero(a, 4, 8)) return publicV4(b(a, 12), b(a, 13), b(a, 14), allowBenchmark);
        if ((b0 & 0xe0) != 0x20) return false;
        if (b0 == 0x20 && b1 == 0x02) return publicV4(b2, b3, b(a, 4), allowBenchmark);
        // 2001::/23 (Teredo and other protocol assignments), 2001:db8::/32 and 3fff::/20 (documentation).
        if (b0 == 0x20 && b1 == 0x01 && (b2 & 0xfe) == 0x00) return false;
        if (b0 == 0x20 && b1 == 0x01 && b2 == 0x0d && b3 == 0xb8) return false;
        return !(b0 == 0x3f && b1 == 0xff && (b2 & 0xf0) == 0x00);
    }

    private static int b(byte[] a, int i) {
        return a[i] & 0xff;
    }

    private static boolean publicV4(int a, int b, int c, boolean allowBenchmark) {
        switch (a) {
            case 0: case 10: case 127: return false;
            case 100: return b < 64 || b > 127;
            case 169: return b != 254;
            case 172: return b < 16 || b > 31;
            case 192: return !(b == 168 || (b == 0 && (c == 0 || c == 2)) || (b == 88 && c == 99));
            case 198: return !(((b == 18 || b == 19) && !allowBenchmark) || (b == 51 && c == 100));
            case 203: return !(b == 0 && c == 113);
            default: return a < 224;
        }
    }

    private static boolean isZero(byte[] a, int from, int count) {
        for (int i = from; i < from + count; i++) if (a[i] != 0) return false;
        return true;
    }

    private static final class Parts {
        final String scheme, host;
        final int port;

        Parts(String scheme, String host, int port) {
            this.scheme = scheme;
            this.host = host;
            this.port = port;
        }
    }

    /**
     * scheme://host[:port][/...] with an http(s) scheme, a plain ASCII host name, no credentials, no space, control character
     * or backslash: the few shapes on which every URL parser (this one, java.net, Chromium) agrees about the host.
     */
    private static Parts split(String url) {
        if (url == null || url.length() > MAX_URL) return null;
        for (int i = 0; i < url.length(); i++) {
            char c = url.charAt(i);
            if (c <= ' ' || c == 0x7f || c == '\\') return null;
        }
        int sep = url.indexOf("://");
        if (sep < 1) return null;
        String scheme = url.substring(0, sep).toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) return null;

        int end = sep + 3;
        while (end < url.length() && "/?#".indexOf(url.charAt(end)) < 0) end++;
        String authority = url.substring(sep + 3, end);
        if (authority.isEmpty() || authority.indexOf('@') >= 0) return null;

        String host = authority;
        int port = -1;
        int colon = authority.lastIndexOf(':');
        if (colon >= 0) {
            host = authority.substring(0, colon);
            String digits = authority.substring(colon + 1);
            if (digits.isEmpty() || digits.length() > 5) return null;
            for (int i = 0; i < digits.length(); i++) if (digits.charAt(i) < '0' || digits.charAt(i) > '9') return null;
            port = Integer.parseInt(digits);
            if (port < 1 || port > 65535) return null;
        }
        host = host.toLowerCase(Locale.ROOT);
        return isPlainHost(host) ? new Parts(scheme, host, port) : null;
    }

    private static boolean isPlainHost(String h) {
        if (h.isEmpty() || h.length() > 253 || h.charAt(0) == '.' || h.charAt(h.length() - 1) == '.' || h.contains("..")) return false;
        for (int i = 0; i < h.length(); i++) {
            char c = h.charAt(i);
            if (!((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '-' || c == '.')) return false;
        }
        return true;
    }
}

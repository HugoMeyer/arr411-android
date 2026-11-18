package dev.c411.qbittorrent;

import java.util.regex.Pattern;

/** Takes what must not leave the phone out of a text that is about to be shared: passwords, tokens and cookies, and the credentials inside a URL. */
final class Redact {
    private static final String HIDDEN = "***";

    private static final Pattern SECRET = Pattern.compile(
        "(\\b(?:password|passwd|pwd|token|apikey|api[_-]key|secret|sid|session|cookie|set-cookie|authorization)\\b\"?\\s*[=:]\\s*)"
            + "(?:\"[^\"\\r\\n]*\"|(?:bearer|basic)\\s+[^\\s&;,\"']+|[^\\s&;,\"']+)",
        Pattern.CASE_INSENSITIVE);

    private static final Pattern URL_CREDENTIALS = Pattern.compile(
        "(\\b[a-z][a-z0-9+.\\-]*://)[^/\\s:@]+(?::[^/\\s@]*)?@", Pattern.CASE_INSENSITIVE);

    private Redact() {
    }

    static String clean(String text) {
        if (text == null || text.isEmpty()) return text;
        String clean = URL_CREDENTIALS.matcher(text).replaceAll("$1" + HIDDEN + "@");
        return SECRET.matcher(clean).replaceAll("$1" + HIDDEN);
    }
}

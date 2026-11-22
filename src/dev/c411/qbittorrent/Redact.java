package dev.c411.qbittorrent;

import java.util.regex.Pattern;

/** Takes what must not leave the phone out of a text that is about to be shared: passwords, tokens, cookies and tracker passkeys, and the credentials inside a URL. */
final class Redact {
    private static final String HIDDEN = "***";

    // A name, a separator (= or :, or %3D inside an encoded URL such as a magnet's tr=) and the value. The name may follow an encoded ? or &.
    private static final Pattern SECRET = Pattern.compile(
        "((?:\\b|(?<=%3[fF]|%26))(?:password|passwd|pwd|token|apikey|api[_-]key|pass[_-]?key|secret|sid|session|cookie|set-cookie|authorization)\\b\"?\\s*(?:[=:]|%3[dD])\\s*)"
            + "(?:\"[^\"\\r\\n]*\"|(?:bearer|basic)\\s+[^\\s&;,\"']+|[^\\s&;,\"']+)",
        Pattern.CASE_INSENSITIVE);

    // A passkey that is a segment of the announce path: .../announce/<passkey> or .../<passkey>/announce (also percent-encoded).
    private static final Pattern PASSKEY_AFTER_ANNOUNCE = Pattern.compile(
        "((?:/|%2[fF])announce(?:/|%2[fF]))[A-Za-z0-9]{20,}", Pattern.CASE_INSENSITIVE);
    private static final Pattern PASSKEY_BEFORE_ANNOUNCE = Pattern.compile(
        "((?:/|%2[fF]))[A-Za-z0-9]{20,}(?=(?:/|%2[fF])announce(?![A-Za-z0-9]))", Pattern.CASE_INSENSITIVE);

    private static final Pattern URL_CREDENTIALS = Pattern.compile(
        "(\\b[a-z][a-z0-9+.\\-]*://)[^/\\s:@]+(?::[^/\\s@]*)?@", Pattern.CASE_INSENSITIVE);

    private Redact() {
    }

    static String clean(String text) {
        if (text == null || text.isEmpty()) return text;
        String clean = URL_CREDENTIALS.matcher(text).replaceAll("$1" + HIDDEN + "@");
        clean = PASSKEY_AFTER_ANNOUNCE.matcher(clean).replaceAll("$1" + HIDDEN);
        clean = PASSKEY_BEFORE_ANNOUNCE.matcher(clean).replaceAll("$1" + HIDDEN);
        return SECRET.matcher(clean).replaceAll("$1" + HIDDEN);
    }
}

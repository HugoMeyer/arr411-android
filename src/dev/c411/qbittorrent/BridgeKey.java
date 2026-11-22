package dev.c411.qbittorrent;

import java.security.SecureRandom;

/**
 * The secret of a {@link PageBridge}. addJavascriptInterface exposes the bridge to every frame of the C411 tab (ads, embeds), but only
 * the script injected in the page itself knows the key: a call without it is not the app's own.
 */
final class BridgeKey {
    private static final int BYTES = 16;
    private static final char[] HEX = "0123456789abcdef".toCharArray();
    private static final SecureRandom RANDOM = new SecureRandom();

    private BridgeKey() {
    }

    /** 128 random bits as 32 lowercase hexadecimal characters. */
    static String create() {
        byte[] raw = new byte[BYTES];
        RANDOM.nextBytes(raw);
        char[] text = new char[BYTES * 2];
        for (int i = 0; i < BYTES; i++) {
            text[i * 2] = HEX[(raw[i] >> 4) & 0xF];
            text[i * 2 + 1] = HEX[raw[i] & 0xF];
        }
        return new String(text);
    }

    /**
     * Whether {@code given} is the {@code expected} key. The time depends on the length of the expected key, not on how much of it was right.
     * A bridge without a key (null or empty) refuses everything.
     */
    static boolean matches(String expected, String given) {
        if (expected == null || expected.isEmpty() || given == null) return false;
        int diff = expected.length() ^ given.length();
        for (int i = 0; i < expected.length(); i++) {
            char other = i < given.length() ? given.charAt(i) : 0;
            diff |= expected.charAt(i) ^ other;
        }
        return diff == 0;
    }
}

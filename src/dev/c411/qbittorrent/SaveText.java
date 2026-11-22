package dev.c411.qbittorrent;

import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

/** What the web app may save in Downloads with Native.saveText: a .json file of at most 1 MiB, named without any folder. */
final class SaveText {
    static final int MAX_BYTES = 1024 * 1024;
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,99}\\.json");

    private SaveText() {
    }

    /** A letter or a digit, then up to 99 letters, digits, dots, dashes or underscores, then ".json": no folder, and no "..". */
    static boolean validName(String name) {
        return name != null && NAME.matcher(name).matches() && !name.contains("..");
    }

    /** The UTF-8 bytes to write, or null when the name is not valid or the text is missing or over 1 MiB once encoded. */
    static byte[] encode(String name, String text) {
        // Every char gives at least one byte: a text with more chars than the limit is refused before it is encoded.
        if (!validName(name) || text == null || text.length() > MAX_BYTES) return null;
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        return bytes.length > MAX_BYTES ? null : bytes;
    }
}

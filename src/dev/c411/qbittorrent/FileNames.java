package dev.c411.qbittorrent;

/** Names of files the app creates in Downloads on behalf of a page: the page chooses the text, never the folder nor the kind of file. */
final class FileNames {
    private static final int MAX_CHARS = 120;
    private static final String EXTENSION = ".torrent";
    private static final String FORBIDDEN = "\\/:*?\"<>|";

    private FileNames() {
    }

    /** No folder, no character Android's storage refuses, no leading dot, at most 120 characters; "fichier" when nothing is left. */
    static String clean(String name) {
        return tidy(cut(spelled(name), MAX_CHARS));
    }

    /** The same, always ending in ".torrent" (kept when it is already there: "a.sh" becomes "a.sh.torrent"), at most 120 characters in all. */
    static String torrent(String name) {
        String s = clean(name);
        if (s.length() > EXTENSION.length() && s.regionMatches(true, s.length() - EXTENSION.length(), EXTENSION, 0, EXTENSION.length())) {
            return s;
        }
        return tidy(cut(s, MAX_CHARS - EXTENSION.length())) + EXTENSION;
    }

    private static String spelled(String name) {
        String s = name == null ? "" : name;
        int slash = Math.max(s.lastIndexOf('/'), s.lastIndexOf('\\'));
        if (slash >= 0) s = s.substring(slash + 1);
        StringBuilder b = new StringBuilder(Math.min(s.length(), MAX_CHARS + 1));
        for (int i = 0; i < s.length() && b.length() <= MAX_CHARS; i++) {
            char c = s.charAt(i);
            b.append(c < ' ' || c == 0x7f || FORBIDDEN.indexOf(c) >= 0 ? '_' : c);
        }
        return b.toString();
    }

    private static String cut(String s, int max) {
        if (s.length() <= max) return s;
        return s.substring(0, Character.isHighSurrogate(s.charAt(max - 1)) ? max - 1 : max);
    }

    private static String tidy(String s) {
        int start = 0;
        int end = s.length();
        while (start < end && (s.charAt(start) == '.' || s.charAt(start) == ' ')) start++;
        while (end > start && (s.charAt(end - 1) == '.' || s.charAt(end - 1) == ' ')) end--;
        return start == end ? "fichier" : s.substring(start, end);
    }
}

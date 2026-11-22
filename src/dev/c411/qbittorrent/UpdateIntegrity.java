package dev.c411.qbittorrent;

import java.util.Set;
import java.util.TreeSet;

/**
 * What makes a downloaded update trustworthy: the size and the SHA-256 that the release publishes, in the API ("digest") and in SHA256SUMS.txt. Every
 * source that exists has to agree with the file; a release without any source is not installed from the app.
 */
final class UpdateIntegrity {
    static final long MAX_APK_BYTES = 50L * 1024 * 1024;
    static final int MAX_SUMS_BYTES = 64 * 1024;
    private static final long MIN_FREE_BYTES = 8L * 1024 * 1024;
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private UpdateIntegrity() {}

    /** What the file has to be: the hash every source agrees on, or why there is none. */
    static final class Expected {
        /** Lowercase hexadecimal SHA-256; null when {@link #error} is set. */
        final String hash;
        /** A French sentence for the user; null when there is a hash. */
        final String error;

        private Expected(String hash, String error) {
            this.hash = hash;
            this.error = error;
        }
    }

    static String hex(byte[] bytes) {
        char[] out = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            out[i * 2] = HEX[(bytes[i] >> 4) & 0xf];
            out[i * 2 + 1] = HEX[bytes[i] & 0xf];
        }
        return new String(out);
    }

    /** 64 hexadecimal digits in either case as lowercase, or null for anything else. */
    static String hexOrNull(String s) {
        if (s == null || s.length() != 64) return null;
        char[] out = new char[64];
        for (int i = 0; i < 64; i++) {
            char c = s.charAt(i);
            if (c >= 'A' && c <= 'F') c = (char) (c - 'A' + 'a');
            if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'))) return null;
            out[i] = c;
        }
        return new String(out);
    }

    /** The hash of the "digest" field of the API ("sha256:" and 64 hexadecimal digits) in lowercase, or null when it is not that. */
    static String parseDigest(String digest) {
        if (digest == null || digest.length() != 7 + 64 || !digest.regionMatches(true, 0, "sha256:", 0, 7)) return null;
        return hexOrNull(digest.substring(7));
    }

    /**
     * The hashes SHA256SUMS.txt gives for a file, in lowercase: the lines that sha256sum writes ("hash", then a space or a tab, an optional * for
     * a binary file, then the name, which may start with ./). Other lines are ignored.
     */
    static Set<String> sumsFor(String text, String fileName) {
        Set<String> found = new TreeSet<>();
        if (text == null || fileName == null) return found;
        String body = text.startsWith("\uFEFF") ? text.substring(1) : text;
        for (String line : body.split("\r\n|\n|\r")) {
            if (line.length() < 66) continue;
            String hash = hexOrNull(line.substring(0, 64));
            if (hash == null) continue;
            char separator = line.charAt(64);
            if (separator != ' ' && separator != '\t') continue;
            int i = 65;
            while (i < line.length() && (line.charAt(i) == ' ' || line.charAt(i) == '\t')) i++;
            if (i < line.length() && line.charAt(i) == '*') i++;
            if (line.startsWith("./", i)) i += 2;
            if (fileName.equals(line.substring(i).trim())) found.add(hash);
        }
        return found;
    }

    /**
     * The hash the downloaded file has to have. {@code digest} is the one of the API (null when absent), {@code digestInvalid} says that the API gave a
     * digest that is not a SHA-256, {@code sumsPresent} that the release has a SHA256SUMS.txt and {@code sumsText} its content (null when it could not
     * be read). A source that is present but unusable, two sources that disagree and no source at all each refuse the installation.
     */
    static Expected expected(String digest, boolean digestInvalid, boolean sumsPresent, String sumsText, String fileName) {
        if (digestInvalid) return new Expected(null, "L’empreinte publiée par GitHub est illisible");
        String fromSums = null;
        if (sumsPresent) {
            if (sumsText == null) return new Expected(null, "SHA256SUMS.txt est introuvable ou illisible");
            Set<String> listed = sumsFor(sumsText, fileName);
            if (listed.isEmpty()) return new Expected(null, "SHA256SUMS.txt ne contient pas " + fileName);
            if (listed.size() > 1) return new Expected(null, "SHA256SUMS.txt donne plusieurs empreintes pour " + fileName);
            fromSums = listed.iterator().next();
        }
        if (digest != null && fromSums != null && !digest.equals(fromSums)) {
            return new Expected(null, "Les empreintes publiées pour la mise à jour ne concordent pas");
        }
        String hash = digest != null ? digest : fromSums;
        if (hash == null) return new Expected(null, "Aucune empreinte publiée : installation dans l’app impossible");
        return new Expected(hash, null);
    }

    /** Null when the file is what the release announced, otherwise why it is not. */
    static String verify(long expectedSize, long actualSize, String actualHash, String expectedHash) {
        if (expectedHash == null) return "Aucune empreinte pour vérifier le fichier";
        if (actualSize != expectedSize) return "Taille du fichier incorrecte";
        if (!expectedHash.equals(actualHash)) return "Empreinte du fichier incorrecte";
        return null;
    }

    static boolean sizeOk(long size) {
        return size > 0 && size <= MAX_APK_BYTES;
    }

    /** True when the length that the server announces (negative when it does not) is the size of the file in the release. */
    static boolean declaredSizeOk(long declared, long expected) {
        return declared < 0 || declared == expected;
    }

    /** True when the free space holds the download and the copy the installer makes of it, with some room left. */
    static boolean hasRoom(long usableBytes, long size) {
        return size >= 0 && size <= MAX_APK_BYTES && usableBytes >= size * 2 + MIN_FREE_BYTES;
    }
}

package dev.c411.qbittorrent;

/** The versions the releases are tagged with ("v2.3.1"): one to four numbers separated by dots, compared number by number. */
final class UpdateVersion {
    private static final int MAX_PARTS = 4;
    private static final int MAX_DIGITS = 9;

    private UpdateVersion() {}

    /** The numbers of a version, or null when it is not one: an optional leading v, then digits and dots, nothing else. */
    static int[] parts(String version) {
        if (version == null) return null;
        String v = version.startsWith("v") || version.startsWith("V") ? version.substring(1) : version;
        if (v.isEmpty() || v.length() > MAX_PARTS * (MAX_DIGITS + 1)) return null;
        String[] pieces = v.split("\\.", -1);
        if (pieces.length > MAX_PARTS) return null;
        int[] out = new int[pieces.length];
        for (int i = 0; i < pieces.length; i++) {
            String piece = pieces[i];
            if (piece.isEmpty() || piece.length() > MAX_DIGITS) return null;
            for (int j = 0; j < piece.length(); j++) {
                char c = piece.charAt(j);
                if (c < '0' || c > '9') return null;
            }
            out[i] = Integer.parseInt(piece);
        }
        return out;
    }

    /** The version without its v and its leading zeros ("v2.03.1" gives "2.3.1"), or null when it is not a version. */
    static String normalize(String version) {
        int[] p = parts(version);
        if (p == null) return null;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < p.length; i++) {
            if (i > 0) sb.append('.');
            sb.append(p[i]);
        }
        return sb.toString();
    }

    /** Negative, zero or positive when a is older than, the same as or newer than b; a missing number counts as 0. */
    static int compare(int[] a, int[] b) {
        int n = Math.max(a.length, b.length);
        for (int i = 0; i < n; i++) {
            int x = i < a.length ? a[i] : 0;
            int y = i < b.length ? b[i] : 0;
            if (x != y) return x < y ? -1 : 1;
        }
        return 0;
    }

    /** True only when both are versions and the candidate is strictly newer: a downgrade or an unreadable version is never offered. */
    static boolean isNewer(String candidate, String current) {
        int[] a = parts(candidate);
        int[] b = parts(current);
        return a != null && b != null && compare(a, b) > 0;
    }

    /** True when both are versions and they are the same one ("2.3" and "v2.3.0" are). */
    static boolean same(String a, String b) {
        int[] x = parts(a);
        int[] y = parts(b);
        return x != null && y != null && compare(x, y) == 0;
    }
}

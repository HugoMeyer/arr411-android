package dev.c411.qbittorrent;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * Decides which requests of the C411 page the bridge honours. The page runs third-party scripts, so what has a side effect
 * (arm, upload, download, saveFile, fetchBytes) needs a real touch or key press on the C411 view, recorded natively, and
 * everything is rate limited. What only reads (and disarm) is served while the tab is on screen and for a minute after it left.
 * Pure logic with an injected clock (milliseconds from a monotonic clock): see test/PureTests.java.
 */
final class PageGate {
    /** How long a real touch or key press on the visible C411 tab keeps the side-effect requests open. */
    static final long GESTURE_WINDOW_MS = 30_000;
    /** How long an accepted "arm" lets the rest of its flow (download, fetchBytes, upload) through, whatever the page was doing. */
    static final long FLOW_WINDOW_MS = 90_000;
    /** How long the page has to answer a native request to read a blob: with saveFile. */
    static final long SAVE_TICKET_MS = 60_000;
    /** How long after the tab left the screen the reads are still served: the user often goes to look at qBittorrent and comes back. */
    static final long OFF_SCREEN_GRACE_MS = 60_000;
    static final long RATE_WINDOW_MS = 10_000;
    static final int MAX_MESSAGES = 30;
    static final int MAX_UPLOADS = 5;
    static final int MAX_PARALLEL_FETCHES = 4;
    static final long TOAST_INTERVAL_MS = 3_000;
    static final int MAX_TOAST_CHARS = 140;
    /** The most characters of a page message. */
    static final int MESSAGE_LIMIT = 1024 * 1024;
    /** The most characters of an upload, whose base64 torrent file may reach 16 MB of bytes. */
    static final int UPLOAD_LIMIT = 24 * 1024 * 1024;

    // Same texts as the Windows app, which the user reads in the page's own notifications.
    static final String TOO_MANY = "Trop de requêtes d'un coup : réessaie.";
    static final String TOO_MANY_UPLOADS = "Trop d'envois d'un coup : réessaie.";
    static final String TOO_MANY_FETCHES = "Trop de téléchargements en cours";
    static final String NOT_ACTIVE = "Action refusée : l'onglet C411 n'est pas actif";
    static final String OFF_SCREEN = "Onglet C411 masqué : reviens sur la page et réessaie.";
    static final String NO_FLOW = "Envoi expiré : relance depuis le bouton qBittorrent";
    static final String REFUSED = "Message refusé";

    private static final long NEVER = Long.MIN_VALUE / 2;
    private static final int MAX_TICKETS = 4;
    private static final int MAX_FLOWS = 4;
    // The only messages the page may hand to the engine with send(): the reads first, then the ones with a side effect.
    private static final Set<String> PAGE_MESSAGES = new HashSet<>(Arrays.asList(
            "getCategories", "pageStatus", "qbitIndex", "pagePrefs", "arrMeta", "arm", "disarm", "upload"));

    private final ArrayDeque<Long> messages = new ArrayDeque<>();
    private final ArrayDeque<Long> uploads = new ArrayDeque<>();
    private final ArrayDeque<Long> tickets = new ArrayDeque<>();
    // The expiry of every accepted "arm" that no upload has used yet (a second torrent can be sent while the first is still on its way).
    private final ArrayDeque<Long> flows = new ArrayDeque<>();
    private long gestureAt = NEVER;
    private long toastAt = NEVER;
    private boolean onScreen = true;
    private long leftAt = NEVER;
    private int fetching;

    /** A real touch or key press on the C411 view while it is showing. Only ever called from the activity's input dispatch. */
    synchronized void gesture(long now) {
        gestureAt = now;
    }

    /** The C411 tab came on screen or left it (tab switch, activity paused, resumed or rebuilt): the grace of the reads starts when it leaves. */
    synchronized void screen(boolean visible, long now) {
        if (visible == onScreen) return;
        onScreen = visible;
        if (!visible) leftAt = now;
    }

    /** Whether one more native download may start for the page: at most MAX_PARALLEL_FETCHES at a time. Every true needs an endFetch. */
    synchronized boolean beginFetch() {
        if (fetching >= MAX_PARALLEL_FETCHES) return false;
        fetching++;
        return true;
    }

    synchronized void endFetch() {
        if (fetching > 0) fetching--;
    }

    /** Whether the page may send this type of message through send(): the internal messages and the native handoffs (download, saveFile...) are not for it. */
    static boolean isPageMessage(String type) {
        return type != null && PAGE_MESSAGES.contains(type);
    }

    /**
     * Whether the request of the page may go on. Returns null when it is accepted, otherwise the error to reply.
     * Reads (getCategories, pageStatus, qbitIndex, pagePrefs, arrMeta) and disarm need no touch: the tab on screen, or
     * left it less than OFF_SCREEN_GRACE_MS ago, is enough. All of them count toward the rate limits.
     */
    synchronized String admit(String type, long now, boolean visible) {
        if (type == null) return REFUSED;
        screen(visible, now);
        if (full(messages, now, MAX_MESSAGES)) return TOO_MANY;
        messages.addLast(now);

        switch (type) {
            case "getCategories": case "pageStatus": case "qbitIndex": case "pagePrefs": case "arrMeta":
                return served(now) ? null : OFF_SCREEN;
            case "disarm":
                if (!served(now)) return OFF_SCREEN;
                pruneFlows(now);
                flows.pollLast();
                return null;
            case "arm":
                if (!touched(now, visible)) return NOT_ACTIVE;
                pruneFlows(now);
                if (flows.size() >= MAX_FLOWS) flows.pollFirst();
                flows.addLast(now + FLOW_WINDOW_MS);
                return null;
            case "upload":
                pruneFlows(now);
                if (flows.isEmpty()) return NO_FLOW;
                if (full(uploads, now, MAX_UPLOADS)) return TOO_MANY_UPLOADS;
                uploads.addLast(now);
                flows.pollFirst();
                return null;
            case "download": case "fetchBytes":
                pruneFlows(now);
                return !flows.isEmpty() || touched(now, visible) ? null : NOT_ACTIVE;
            case "saveFile":
                return takeTicket(now) ? null : NOT_ACTIVE;
            default:
                return REFUSED;
        }
    }

    /** The app itself asked the page to read a blob: (a download the user started): one saveFile will be accepted. */
    synchronized void grantSave(long now) {
        pruneTickets(now);
        if (tickets.size() >= MAX_TICKETS) tickets.pollFirst();
        tickets.addLast(now + SAVE_TICKET_MS);
    }

    /** Whether the page may show a toast now (visible tab, at most one every few seconds). */
    synchronized boolean allowToast(long now, boolean visible) {
        if (!visible || now - toastAt < TOAST_INTERVAL_MS) return false;
        toastAt = now;
        return true;
    }

    /**
     * Whether a message of this size may be parsed: 1 MB for every message but an upload. Before parsing, {@code type} is null and only
     * the beginning of the text tells that it is an upload.
     */
    static boolean sizeAllowed(String msg, String type) {
        int n = msg.length();
        if (n <= MESSAGE_LIMIT) return true;
        boolean upload = type == null ? msg.substring(0, 256).contains("\"type\":\"upload\"") : "upload".equals(type);
        return upload && n <= UPLOAD_LIMIT;
    }

    /** A toast text a page sent: no control characters, at most MAX_TOAST_CHARS characters, null when nothing is left. */
    static String toastText(String text) {
        if (text == null) return null;
        StringBuilder sb = new StringBuilder(Math.min(text.length(), MAX_TOAST_CHARS));
        for (int i = 0; i < text.length() && sb.length() < MAX_TOAST_CHARS; i++) {
            char c = text.charAt(i);
            sb.append(c < ' ' || c == 0x7f ? ' ' : c);
        }
        String s = sb.toString().trim();
        return s.isEmpty() ? null : s;
    }

    private boolean touched(long now, boolean visible) {
        return visible && now - gestureAt <= GESTURE_WINDOW_MS;
    }

    private boolean served(long now) {
        return onScreen || now - leftAt < OFF_SCREEN_GRACE_MS;
    }

    private void pruneFlows(long now) {
        while (!flows.isEmpty() && now >= flows.peekFirst()) flows.pollFirst();
    }

    private boolean takeTicket(long now) {
        pruneTickets(now);
        return tickets.pollFirst() != null;
    }

    private void pruneTickets(long now) {
        while (!tickets.isEmpty() && now >= tickets.peekFirst()) tickets.pollFirst();
    }

    private static boolean full(ArrayDeque<Long> recent, long now, int max) {
        while (!recent.isEmpty() && now - recent.peekFirst() > RATE_WINDOW_MS) recent.pollFirst();
        return recent.size() >= max;
    }
}

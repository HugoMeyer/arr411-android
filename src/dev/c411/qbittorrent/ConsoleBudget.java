package dev.c411.qbittorrent;

/**
 * How many lines of page script errors reach the log: a page stuck in an error loop must not turn the log into noise nor
 * keep the file busy. The lines over the budget are counted, and announced in a single line when the next minute opens.
 */
final class ConsoleBudget {
    static final int PER_MINUTE = 20;
    static final long WINDOW_MS = 60_000;

    private static final long NEVER = Long.MIN_VALUE / 2;
    private long windowStart = NEVER;
    private int used;
    private int ignored;

    /** -1 when the line must be dropped (this minute's budget is spent); otherwise how many earlier lines were dropped and are not announced yet. */
    synchronized int take(long now) {
        if (now - windowStart >= WINDOW_MS) {
            windowStart = now;
            used = 0;
        }
        if (used >= PER_MINUTE) {
            ignored++;
            return -1;
        }
        used++;
        return takeIgnored();
    }

    /** The lines dropped and not announced yet; the count starts again. */
    synchronized int takeIgnored() {
        int n = ignored;
        ignored = 0;
        return n;
    }
}

package dev.c411.qbittorrent;

import java.util.ArrayDeque;

/**
 * How long to wait before rebuilding a web view whose renderer just died. The first deaths are rebuilt at once; one that keeps dying (the page needs
 * more memory than the system gives it) is rebuilt less and less often instead of in a loop that drains the battery and fills the log.
 */
final class RestartDelay {
    static final long WINDOW_MS = 60_000;
    static final int FREE_DEATHS = 2;
    static final long STEP_MS = 2_000;
    static final long MAX_MS = 30_000;

    private final ArrayDeque<Long> deaths = new ArrayDeque<>();

    /** Records a death at {@code nowMs} (any increasing clock) and returns the milliseconds to wait before rebuilding. */
    synchronized long afterDeath(long nowMs) {
        deaths.addLast(nowMs);
        while (!deaths.isEmpty() && nowMs - deaths.peekFirst() > WINDOW_MS) deaths.removeFirst();
        int recent = deaths.size();
        return recent <= FREE_DEATHS ? 0 : Math.min(MAX_MS, STEP_MS * (recent - FREE_DEATHS));
    }
}

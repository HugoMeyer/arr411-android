package dev.c411.qbittorrent;

/** When the update is looked for and when its progress is shown: the clocks are passed in, so that the rules can be checked without waiting. */
final class UpdateThrottle {
    /** The time between two checks. */
    static final long PERIOD_MS = 6L * 60 * 60 * 1000;
    /** The scheduler may run the job at any time of the last hour of a period. */
    static final long FLEX_MS = 60L * 60 * 1000;
    /** Two checks asked by the user are at least this far apart. */
    static final long MANUAL_GAP_MS = 60L * 1000;
    /** The wait after the start of the app before the first check. */
    static final long STARTUP_DELAY_MS = 30L * 1000;
    /** The job does not check again when a check succeeded more recently than this. */
    static final long JOB_MIN_GAP_MS = 3L * 60 * 60 * 1000;
    /** Two pushes of the progress of the download are at least this far apart. */
    static final long PROGRESS_GAP_MS = 250L;
    /** A download that takes longer than this is given up. */
    static final long DOWNLOAD_DEADLINE_MS = 10L * 60 * 1000;

    private UpdateThrottle() {}

    /** True when no check ever succeeded, when the clock went back, or when the last one is a period old. Wall-clock times, in milliseconds. */
    static boolean periodDue(long now, long lastCheckedAt) {
        return lastCheckedAt <= 0 || now < lastCheckedAt || now - lastCheckedAt >= PERIOD_MS;
    }

    /** The same for the periodic job, which comes a little earlier than a period after the last check. */
    static boolean jobDue(long now, long lastCheckedAt) {
        return lastCheckedAt <= 0 || now < lastCheckedAt || now - lastCheckedAt >= JOB_MIN_GAP_MS;
    }

    /** True when the user may ask for a check now; {@code lastManualAt} is a monotonic time, or negative when there was none. */
    static boolean manualAllowed(long now, long lastManualAt) {
        return lastManualAt < 0 || now < lastManualAt || now - lastManualAt >= MANUAL_GAP_MS;
    }

    /** True when the progress may be pushed again; {@code lastPushAt} is a monotonic time, or negative when there was none. */
    static boolean progressDue(long now, long lastPushAt) {
        return lastPushAt < 0 || now < lastPushAt || now - lastPushAt >= PROGRESS_GAP_MS;
    }
}

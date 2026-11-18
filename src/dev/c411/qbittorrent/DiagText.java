package dev.c411.qbittorrent;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** The words of the diagnostic: sizes, durations, the names Android gives to numbers, and the shape of a log entry. */
final class DiagText {
    private DiagText() {
    }

    static String megabytes(long bytes) {
        return Long.toString(bytes / (1024 * 1024));
    }

    /** "45 s", "12 min 5 s", "3 h 20 min", "2 d 3 h": the two biggest units, the way people say it. */
    static String duration(long millis) {
        long seconds = Math.max(0, millis) / 1000;
        long days = seconds / 86400;
        long hours = seconds % 86400 / 3600;
        long minutes = seconds % 3600 / 60;
        if (days > 0) return days + " d " + hours + " h";
        if (hours > 0) return hours + " h " + minutes + " min";
        if (minutes > 0) return minutes + " min " + seconds % 60 + " s";
        return seconds + " s";
    }

    static String timestamp(long millis) {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(new Date(millis));
    }

    /** ApplicationExitInfo.REASON_*: why Android says the previous process of the app ended. */
    static String exitReason(int reason) {
        switch (reason) {
            case 0: return "UNKNOWN";
            case 1: return "EXIT_SELF";
            case 2: return "SIGNALED";
            case 3: return "LOW_MEMORY";
            case 4: return "CRASH";
            case 5: return "CRASH_NATIVE";
            case 6: return "ANR";
            case 7: return "INITIALIZATION_FAILURE";
            case 8: return "PERMISSION_CHANGE";
            case 9: return "EXCESSIVE_RESOURCE_USAGE";
            case 10: return "USER_REQUESTED";
            case 11: return "USER_STOPPED";
            case 12: return "DEPENDENCY_DIED";
            case 13: return "OTHER";
            case 14: return "FREEZER";
            case 15: return "PACKAGE_STATE_CHANGE";
            case 16: return "PACKAGE_UPDATED";
            default: return "reason " + reason;
        }
    }

    /** RunningAppProcessInfo.IMPORTANCE_*: how much Android cared for the process when it ended. */
    static String importance(int importance) {
        switch (importance) {
            case 100: return "FOREGROUND";
            case 125: return "FOREGROUND_SERVICE";
            case 200: return "VISIBLE";
            case 230: return "PERCEPTIBLE";
            case 300: return "SERVICE";
            case 325: return "TOP_SLEEPING";
            case 350: return "CANT_SAVE_STATE";
            case 400: return "CACHED";
            case 500: return "EMPTY";
            case 1000: return "GONE";
            default: return "importance " + importance;
        }
    }

    /** ComponentCallbacks2.TRIM_MEMORY_*: how short of memory Android says the device is. */
    static String trimLevel(int level) {
        switch (level) {
            case 5: return "RUNNING_MODERATE";
            case 10: return "RUNNING_LOW";
            case 15: return "RUNNING_CRITICAL";
            case 20: return "UI_HIDDEN";
            case 40: return "BACKGROUND";
            case 60: return "MODERATE";
            case 80: return "COMPLETE";
            default: return "level " + level;
        }
    }

    /** WebView.RENDERER_PRIORITY_*: how important the browser process that died was to the system. */
    static String rendererPriority(int priority) {
        switch (priority) {
            case 0: return "WAIVED";
            case 1: return "BOUND";
            case 2: return "IMPORTANT";
            default: return "priority " + priority;
        }
    }

    /** A text on one line (a log line is one line), cut when it is longer than {@code maxChars}. */
    static String oneLine(String text, int maxChars) {
        if (text == null) return "";
        String line = text.trim().replaceAll("\\s*[\\r\\n]+\\s*", " | ");
        return line.length() > maxChars ? line.substring(0, maxChars) + "..." : line;
    }

    /** The stack trace with the causes that led to it, cut at {@code maxChars}. */
    static String stack(Throwable error, int maxChars) {
        StringWriter text = new StringWriter();
        try (PrintWriter out = new PrintWriter(text)) {
            error.printStackTrace(out);
        }
        String trace = text.toString().trim();
        return trace.length() > maxChars ? trace.substring(0, maxChars) + "\n[... cut]" : trace;
    }

    /** The last entry of a log made of entries that start with a line "=== ...", or null when there is none. */
    static String lastEntry(String log, int maxChars) {
        if (log == null) return null;
        int start = log.lastIndexOf("\n=== ");
        start = start >= 0 ? start + 1 : log.startsWith("=== ") ? 0 : -1;
        if (start < 0) return null;
        String entry = log.substring(start).trim();
        return entry.length() > maxChars ? entry.substring(0, maxChars) + "\n[... cut]" : entry;
    }
}

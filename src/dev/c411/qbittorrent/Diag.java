package dev.c411.qbittorrent;

import android.app.ActivityManager;
import android.app.Application;
import android.app.ApplicationExitInfo;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Debug;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;
import android.webkit.ConsoleMessage;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.WebView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * What the app writes down about itself, to understand a crash afterwards: a short log (arr411.log), the full story of every serious event (crash.log),
 * and a report that gathers both with the memory figures and how Android says the previous runs ended. Nothing here may fail, nor wait for a slow
 * thing on the main thread: every method swallows its own errors.
 */
final class Diag {
    private static final String TAG = "Arr411";
    private static final long LOG_LIMIT = 256 * 1024;
    private static final long CRASH_LIMIT = 512 * 1024;
    private static final int RESERVE_BYTES = 256 * 1024;
    private static final int REPORT_LOG_LINES = 200;
    private static final int REPORT_LOG_CHARS = 60_000;
    private static final int REPORT_CRASH_CHARS = 6_000;
    private static final long BIG_RESPONSE_CHARS = 2L * 1024 * 1024;
    private static final long SLOW_REQUEST_MS = 8_000;
    private static final long SERIOUS_EXIT_TOAST_MS = 6L * 3600 * 1000;

    private static final class Throttle {
        long last;
        int skipped;
    }

    private static final Map<String, Throttle> throttles = new HashMap<>();

    private static Application app;
    private static LogFile log;
    private static LogFile crash;
    private static SharedPreferences prefs;
    private static ScheduledExecutorService worker;
    private static String versionName = "?";
    private static long versionCode;
    private static boolean endedInForeground;
    // Freed first when the app dies of an OutOfMemoryError, so that the crash can still be written down.
    private static volatile byte[] reserve;

    private Diag() {
    }

    static synchronized void init(Application application) {
        if (app != null) return;
        try {
            app = application;
            File folder = new File(app.getFilesDir(), "logs");
            log = new LogFile(new File(folder, "arr411.log"), LOG_LIMIT);
            crash = new LogFile(new File(folder, "crash.log"), CRASH_LIMIT);
            prefs = app.getSharedPreferences("diag", Context.MODE_PRIVATE);
            endedInForeground = prefs.getBoolean("fg", false);
            reserve = new byte[RESERVE_BYTES];
            try {
                PackageInfo info = app.getPackageManager().getPackageInfo(app.getPackageName(), 0);
                versionName = info.versionName == null ? "?" : info.versionName;
                versionCode = info.getLongVersionCode();
            } catch (Exception ignored) {
            }

            Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
            Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
                uncaught(thread, error, true);
                if (previous != null) previous.uncaughtException(thread, error);
            });

            worker = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "arr411-diag");
                t.setDaemon(true);
                return t;
            });
            worker.execute(Diag::startup);
            worker.scheduleWithFixedDelay(() -> memoryLine("every 15 min"), 15, 15, TimeUnit.MINUTES);
        } catch (Throwable ignored) {
        }
    }

    // ---------- Writing ----------

    /** A thread ended with an error nobody handled. {@code fatal}: the system is about to end the process; otherwise the app goes on without it. */
    static void uncaught(Thread thread, Throwable error, boolean fatal) {
        try {
            reserve = null; // room for the writing below when the error is an OutOfMemoryError
            serious("uncaught " + (error == null ? "error" : error.getClass().getSimpleName()) + " on thread " + thread.getName()
                    + (fatal ? "" : " (the app goes on)"), error, null);
        } catch (Throwable ignored) {
        }
        if (!fatal) {
            try {
                reserve = new byte[RESERVE_BYTES];
            } catch (Throwable ignored) {
            }
        }
    }

    static void info(String message) {
        log("INFO", message);
    }

    static void warn(String message) {
        log("WARN", message);
    }

    static void log(String level, String message) {
        try {
            String line = DiagText.timestamp(System.currentTimeMillis()) + " " + level + " " + Redact.clean(DiagText.oneLine(message, 2000));
            LogFile file = log;
            if (file != null) file.append(line);
            Log.println("ERROR".equals(level) ? Log.ERROR : "WARN".equals(level) ? Log.WARN : Log.INFO, TAG, line);
        } catch (Throwable ignored) {
        }
    }

    /** A serious event (a crash, a renderer that died, memory that ran out): the whole story goes to crash.log and one line to the log. */
    static void serious(String title, Throwable error, String details) {
        try {
            StringBuilder entry = new StringBuilder();
            entry.append("=== ").append(DiagText.timestamp(System.currentTimeMillis())).append(' ').append(title).append(" ===\n");
            entry.append("Version ").append(versionName).append(" (").append(versionCode).append("), pid ").append(Process.myPid())
                    .append(", up ").append(DiagText.duration(SystemClock.elapsedRealtime() - Process.getStartElapsedRealtime()))
                    .append(", thread ").append(Thread.currentThread().getName()).append('\n');
            entry.append("Memory: ").append(memory()).append('\n');
            if (details != null && !details.isEmpty()) entry.append(details).append('\n');
            if (error != null) entry.append(DiagText.stack(error, 12_000)).append('\n');
            String text = entry.toString();
            try {
                text = Redact.clean(text);
            } catch (Throwable ignored) {
            }
            LogFile file = crash;
            if (file != null) file.append(text + "\n");
            log("ERROR", title + (error == null ? "" : ": " + error) + " (details in crash.log)");
        } catch (Throwable ignored) {
        }
    }

    /** The reason to log again, or null when the same thing was logged under this key less than {@code intervalMs} ago; "" or " (+3 similar)". */
    private static String throttled(String key, long intervalMs) {
        long now = SystemClock.elapsedRealtime();
        String id = key.length() > 200 ? key.substring(0, 200) : key;
        synchronized (throttles) {
            Throttle t = throttles.get(id);
            if (t == null) {
                if (throttles.size() >= 300) throttles.clear();
                t = new Throttle();
                throttles.put(id, t);
            } else if (now - t.last < intervalMs) {
                t.skipped++;
                return null;
            }
            int skipped = t.skipped;
            t.skipped = 0;
            t.last = now;
            return skipped > 0 ? " (+" + skipped + " similar)" : "";
        }
    }

    // ---------- What happens to the app ----------

    /** A failure that can repeat many times (a cover that does not load, a notification that is refused): one line per minute for each key. */
    static void problem(String key, String message, Throwable error) {
        try {
            String more = throttled("problem:" + key, 60_000);
            if (more != null) log("WARN", message + (error == null ? "" : ": " + error) + more);
        } catch (Throwable ignored) {
        }
    }

    /** One network request finished; {@code what} is "GET host:port/path" (never the query). Only what is worth a line is written. */
    static void http(String what, int status, String error, long chars, long millis) {
        try {
            if (error != null) {
                // A server that is switched off fails every 30 s: the first failure and then a count are enough.
                String more = throttled("net:" + what, 10 * 60_000);
                if (more != null) log("WARN", "request failed: " + what + ": " + error + " (" + millis + " ms)" + more);
                return;
            }
            if (status >= 400) {
                String more = throttled("http:" + what + status, 10 * 60_000);
                if (more != null) log(status >= 500 ? "WARN" : "INFO", "HTTP " + status + ": " + what + " (" + millis + " ms)" + more);
            }
            if (chars > BIG_RESPONSE_CHARS) {
                String more = throttled("big:" + what, 60_000);
                if (more != null) {
                    log("INFO", "large response: " + what + " -> " + status + ", " + DiagText.megabytes(chars) + " MB in "
                            + DiagText.duration(millis) + "; " + memory() + more);
                }
            } else if (millis > SLOW_REQUEST_MS) {
                String more = throttled("slow:" + what, 60_000);
                if (more != null) log("INFO", "slow request: " + what + " took " + DiagText.duration(millis) + more);
            }
        } catch (Throwable ignored) {
        }
    }

    static void rendererGone(String which, RenderProcessGoneDetail detail) {
        try {
            boolean crashed = detail != null && detail.didCrash();
            String priority = detail == null ? "unknown" : DiagText.rendererPriority(detail.rendererPriorityAtExit());
            serious(which + " web renderer " + (crashed ? "crashed" : "was killed by the system (probably low memory)"), null,
                    "Renderer priority when it ended: " + priority);
        } catch (Throwable ignored) {
        }
    }

    /** What a page says went wrong in its script. The pages of other sites (ads, trackers) are not the app's business. */
    static void console(String which, ConsoleMessage message) {
        try {
            if (message == null || message.messageLevel() != ConsoleMessage.MessageLevel.ERROR) return;
            String source = message.sourceId();
            if (thirdParty(source)) return;
            String text = DiagText.oneLine(message.message(), 300);
            String where = shortSource(source) + ":" + message.lineNumber();
            String more = throttled("js:" + which + where + text, 60_000);
            if (more != null) log("WARN", which + " page script error: " + text + " (" + where + ")" + more);
        } catch (Throwable ignored) {
        }
    }

    static void pageError(String url, int code, String description) {
        try {
            Uri u = Uri.parse(url == null ? "" : url);
            String where = u.getHost() + (u.getPath() == null ? "" : u.getPath());
            String more = throttled("page:" + where + code, 60_000);
            if (more != null) log("WARN", "page failed to load: " + where + " (" + code + " " + description + ")" + more);
        } catch (Throwable ignored) {
        }
    }

    static void trim(int level) {
        try {
            // Moderate-while-running and "the app went to the background" happen all the time.
            if (level == 5 || level == 20) return;
            String more = throttled("trim:" + level, 30_000);
            if (more == null) return;
            boolean running = level < 20;
            log(level >= 10 && running ? "WARN" : "INFO", "the system asks to release memory: " + DiagText.trimLevel(level) + more
                    + (running ? "; " + memory() : ""));
        } catch (Throwable ignored) {
        }
    }

    static void lowMemory() {
        try {
            String more = throttled("lowmemory", 30_000);
            if (more != null) log("WARN", "the system is low on memory (onLowMemory); " + memory() + more);
        } catch (Throwable ignored) {
        }
    }

    /** Whether the screen is in front of the user: when the process ends while it is, the user saw it as a crash. */
    static void foreground(boolean on) {
        try {
            if (prefs != null) prefs.edit().putBoolean("fg", on).apply();
            info(on ? "screen in front" : "screen in background");
        } catch (Throwable ignored) {
        }
    }

    /** The activity was created or destroyed: how long the process has been up tells a cold start from a rebuild. */
    static void lifecycle(String event) {
        try {
            info(event + ", process up " + DiagText.duration(SystemClock.elapsedRealtime() - Process.getStartElapsedRealtime()));
        } catch (Throwable ignored) {
        }
    }

    static void clear() {
        try {
            if (log != null) log.clear();
            if (crash != null) crash.clear();
            if (prefs != null) prefs.edit().putLong("clearedAt", System.currentTimeMillis()).apply();
            info("the log was cleared");
        } catch (Throwable ignored) {
        }
    }

    // ---------- Starting up ----------

    private static void startup() {
        try {
            info("Arr411 " + versionName + " (" + versionCode + ") started, pid " + Process.myPid());
            info("device: " + Build.MANUFACTURER + " " + Build.MODEL + ", Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + "), "
                    + (Build.SUPPORTED_ABIS.length > 0 ? Build.SUPPORTED_ABIS[0] : "?") + ", " + heapClasses());
            info("WebView: " + webView());
            memoryLine("start");
            String problem = Build.VERSION.SDK_INT >= 30 ? Exits.logNew() : null;
            if (Build.VERSION.SDK_INT < 30 && endedInForeground) {
                warn("the previous process ended while the app was in front (crash, system kill or update; Android 10 keeps no better record)");
                problem = "arrêt en cours d'utilisation";
            }
            if (problem != null) tell("Arr411 s'est arrêtée de façon inattendue (" + problem + "). Détails : Réglages > Diagnostic.");
        } catch (Throwable ignored) {
        }
    }

    private static void tell(String text) {
        new Handler(Looper.getMainLooper()).post(() -> Toast.makeText(app, text, Toast.LENGTH_LONG).show());
    }

    private static void memoryLine(String why) {
        info("memory (" + why + "): " + memory());
    }

    private static String heapClasses() {
        try {
            ActivityManager am = app.getSystemService(ActivityManager.class);
            return "memory class " + am.getMemoryClass() + " MB (large " + am.getLargeMemoryClass() + " MB), Java heap limit "
                    + DiagText.megabytes(Runtime.getRuntime().maxMemory()) + " MB";
        } catch (Throwable e) {
            return "memory class unknown";
        }
    }

    private static String webView() {
        try {
            PackageInfo p = WebView.getCurrentWebViewPackage();
            return p == null ? "not found" : p.packageName + " " + p.versionName;
        } catch (Throwable e) {
            return "unknown (" + e + ")";
        }
    }

    /** What the process holds right now, on one line: tells a slow leak from a spike, and an app short of memory from a device short of memory. */
    static String memory() {
        StringBuilder t = new StringBuilder();
        try {
            Runtime rt = Runtime.getRuntime();
            t.append("Java heap ").append(DiagText.megabytes(rt.totalMemory() - rt.freeMemory())).append('/')
                    .append(DiagText.megabytes(rt.maxMemory())).append(" MB");
        } catch (Throwable ignored) {
        }
        try {
            Debug.MemoryInfo info = new Debug.MemoryInfo();
            Debug.getMemoryInfo(info);
            Map<String, String> stats = info.getMemoryStats();
            t.append(", PSS ").append(info.getTotalPss() / 1024).append(" MB (");
            String[][] parts = {{"java", "summary.java-heap"}, {"native", "summary.native-heap"}, {"code", "summary.code"},
                    {"graphics", "summary.graphics"}, {"other", "summary.private-other"}};
            boolean first = true;
            for (String[] part : parts) {
                String value = stats == null ? null : stats.get(part[1]);
                if (value == null) continue;
                t.append(first ? "" : ", ").append(part[0]).append(' ').append(Long.parseLong(value.trim()) / 1024);
                first = false;
            }
            t.append(')');
        } catch (Throwable ignored) {
        }
        try {
            ActivityManager.MemoryInfo info = new ActivityManager.MemoryInfo();
            app.getSystemService(ActivityManager.class).getMemoryInfo(info);
            t.append(", device free ").append(DiagText.megabytes(info.availMem)).append('/').append(DiagText.megabytes(info.totalMem))
                    .append(" MB (low-memory threshold ").append(DiagText.megabytes(info.threshold)).append(" MB, low now: ")
                    .append(info.lowMemory ? "yes" : "no").append(')');
        } catch (Throwable ignored) {
        }
        try {
            String threads = procStatus("Threads");
            String[] files = new File("/proc/self/fd").list();
            if (threads != null) t.append(", ").append(threads).append(" threads");
            if (files != null) t.append(", ").append(files.length).append(" open files");
        } catch (Throwable ignored) {
        }
        return t.toString();
    }

    private static String procStatus(String key) {
        try (BufferedReader in = new BufferedReader(new FileReader("/proc/self/status"))) {
            for (String line; (line = in.readLine()) != null; ) {
                if (line.startsWith(key + ":")) return line.substring(key.length() + 1).trim();
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static boolean thirdParty(String sourceId) {
        try {
            if (sourceId == null || !sourceId.startsWith("http")) return false;
            Uri u = Uri.parse(sourceId);
            return u.getHost() != null && !MainActivity.isC411(u) && !SpaClient.HOST.equals(u.getHost());
        } catch (Throwable e) {
            return false;
        }
    }

    private static String shortSource(String sourceId) {
        if (sourceId == null || sourceId.isEmpty()) return "script";
        String name = sourceId.substring(sourceId.lastIndexOf('/') + 1);
        int query = name.indexOf('?');
        return query >= 0 ? name.substring(0, query) : name;
    }

    // ---------- The report ----------

    static String report() {
        StringBuilder t = new StringBuilder();
        try {
            t.append("Arr411 Android diagnostic report\n");
            t.append("Generated: ").append(DiagText.timestamp(System.currentTimeMillis())).append('\n');
            section(t, "Application", Diag::applicationText);
            section(t, "Memory", Diag::memory);
            section(t, "Previous processes (how Android says they ended)", Diag::previousText);
            section(t, "Settings", Diag::settingsText);
            section(t, "Last serious event (crash.log)", Diag::crashText);
            section(t, "Log (last " + REPORT_LOG_LINES + " lines)", Diag::logText);
        } catch (Throwable e) {
            t.append("\n[report incomplete: ").append(e).append(']');
        }
        return Redact.clean(t.toString());
    }

    private static void section(StringBuilder out, String title, Supplier<String> body) {
        out.append("\n== ").append(title).append(" ==\n");
        try {
            out.append(body.get()).append('\n');
        } catch (Throwable e) {
            out.append("[unavailable: ").append(e).append("]\n");
        }
    }

    private static String applicationText() {
        long up = SystemClock.elapsedRealtime() - Process.getStartElapsedRealtime();
        return "Version: " + versionName + " (" + versionCode + ")\n"
                + "Process: pid " + Process.myPid() + ", started " + DiagText.timestamp(System.currentTimeMillis() - up) + " (up " + DiagText.duration(up) + ")\n"
                + "Device: " + Build.MANUFACTURER + " " + Build.MODEL + ", Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + "), "
                + (Build.SUPPORTED_ABIS.length > 0 ? Build.SUPPORTED_ABIS[0] : "?") + "\n"
                + "WebView: " + webView() + "\n"
                + "Limits: " + heapClasses();
    }

    private static String previousText() {
        if (Build.VERSION.SDK_INT >= 30) return Exits.describeRecent();
        return "Android 10 keeps no record of how a process ended.\n"
                + "At the start of this run, the previous process had ended while the app was in front: " + (endedInForeground ? "yes" : "no");
    }

    private static String settingsText() {
        // Read from the preferences rather than through the engine, which would build a WebView on whatever thread asks for the report.
        JSONObject cfg;
        try {
            cfg = new JSONObject(app.getSharedPreferences("config", Context.MODE_PRIVATE).getString("data", "{}"));
        } catch (Exception e) {
            cfg = new JSONObject();
        }
        return "- qBittorrent: " + address(cfg.optString("qbUrl")) + ", login " + (!cfg.optString("username").isEmpty() && !cfg.optString("password").isEmpty() ? "set" : "missing") + "\n"
                + "- Radarr: " + address(cfg.optString("radarrUrl")) + ", API key " + (cfg.optString("radarrKey").trim().isEmpty() ? "missing" : "set") + "\n"
                + "- Sonarr: " + address(cfg.optString("sonarrUrl")) + ", API key " + (cfg.optString("sonarrKey").trim().isEmpty() ? "missing" : "set") + "\n"
                + "- Lidarr: " + address(cfg.optString("lidarrUrl")) + ", API key " + (cfg.optString("lidarrKey").trim().isEmpty() ? "missing" : "set") + "\n"
                + "- Notifications: " + onOff(cfg, "notifyEnabled", true) + ", for every download: " + onOff(cfg, "notifyAll", false)
                + ", list badges: " + onOff(cfg, "listBadges", true) + "\n"
                + "- Start tab: " + cfg.optString("startTab", "c411") + ", desktop mode: " + onOff(cfg, "desktopMode", false);
    }

    private static String onOff(JSONObject cfg, String key, boolean fallback) {
        return cfg.optBoolean(key, fallback) ? "on" : "off";
    }

    /** The scheme, host and port of a server, never a path, a login or a key. */
    private static String address(String url) {
        String text = url == null ? "" : url.trim();
        if (text.isEmpty()) return "not configured";
        Uri u = Uri.parse(text);
        if (u.getHost() == null) return "configured (address not understood)";
        return "configured, " + (u.getScheme() == null ? "" : u.getScheme() + "://") + u.getHost() + (u.getPort() > 0 ? ":" + u.getPort() : "");
    }

    private static String crashText() {
        LogFile file = crash;
        String entry = file == null ? null : DiagText.lastEntry(file.tailText(64 * 1024), REPORT_CRASH_CHARS);
        return entry == null ? "None recorded." : entry;
    }

    private static String logText() {
        LogFile file = log;
        List<String> lines = file == null ? null : file.tail(REPORT_LOG_LINES);
        if (lines == null || lines.isEmpty()) return "The log is empty.";
        int from = lines.size();
        int chars = 0;
        while (from > 0 && chars + lines.get(from - 1).length() + 1 <= REPORT_LOG_CHARS) {
            from--;
            chars += lines.get(from).length() + 1;
        }
        return String.join("\n", lines.subList(from, lines.size()));
    }

    /** Android 11+: the system's own record of how the previous processes of the app ended. Kept apart so older versions never load it. */
    private static final class Exits {
        private static List<ApplicationExitInfo> recent(int max) {
            return app.getSystemService(ActivityManager.class).getHistoricalProcessExitReasons(null, 0, max);
        }

        /** Killed while cached is what Android does to every app; the other endings are what the user sees as a crash. */
        private static boolean isProblem(ApplicationExitInfo e) {
            int reason = e.getReason();
            if (reason == ApplicationExitInfo.REASON_CRASH || reason == ApplicationExitInfo.REASON_CRASH_NATIVE
                    || reason == ApplicationExitInfo.REASON_ANR || reason == ApplicationExitInfo.REASON_INITIALIZATION_FAILURE
                    || reason == ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE) return true;
            boolean killed = reason == ApplicationExitInfo.REASON_LOW_MEMORY || reason == ApplicationExitInfo.REASON_SIGNALED;
            return killed && e.getImportance() <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE;
        }

        private static String describe(ApplicationExitInfo e) {
            StringBuilder t = new StringBuilder();
            t.append(DiagText.timestamp(e.getTimestamp())).append(": ").append(DiagText.exitReason(e.getReason()))
                    .append(" (status ").append(e.getStatus()).append("), importance ").append(DiagText.importance(e.getImportance()))
                    .append(", PSS ").append(e.getPss() / 1024).append(" MB, RSS ").append(e.getRss() / 1024).append(" MB, pid ").append(e.getPid());
            String description = e.getDescription();
            if (description != null && !description.isEmpty()) t.append(", \"").append(DiagText.oneLine(description, 200)).append('"');
            return t.toString();
        }

        /** Logs what happened since the last run, oldest first. Returns the reason of the last serious one that is recent, or null. */
        static String logNew() {
            long seen = prefs.getLong("exitSeen", 0);
            long newest = seen;
            String problem = null;
            List<ApplicationExitInfo> exits = recent(10);
            for (int i = exits.size() - 1; i >= 0; i--) {
                ApplicationExitInfo e = exits.get(i);
                if (e.getTimestamp() <= seen) continue;
                newest = Math.max(newest, e.getTimestamp());
                boolean serious = isProblem(e);
                log(serious ? "WARN" : "INFO", "previous process ended: " + describe(e));
                if (serious && System.currentTimeMillis() - e.getTimestamp() < SERIOUS_EXIT_TOAST_MS) problem = DiagText.exitReason(e.getReason());
            }
            if (newest != seen) prefs.edit().putLong("exitSeen", newest).apply();
            return problem;
        }

        static String describeRecent() {
            long cleared = prefs.getLong("clearedAt", 0);
            StringBuilder t = new StringBuilder();
            for (ApplicationExitInfo e : recent(10)) {
                if (e.getTimestamp() > cleared) t.append(t.length() == 0 ? "" : "\n").append("- ").append(isProblem(e) ? "PROBLEM " : "").append(describe(e));
            }
            return t.length() == 0 ? "No exit recorded." : t.toString();
        }
    }
}

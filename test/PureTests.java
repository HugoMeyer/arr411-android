package dev.c411.qbittorrent;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.URL;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.net.ssl.SSLHandshakeException;

/**
 * Checks for the classes of src that don't use android.*: run them with test.ps1. No framework: a failed check prints its name
 * and makes the exit code 1.
 */
public class PureTests {
    static int passed = 0;
    static int failed = 0;

    static void check(boolean ok, String name) {
        if (ok) {
            passed++;
        } else {
            failed++;
            System.out.println("FAIL: " + name);
        }
    }

    static void eq(Object expected, Object actual, String name) {
        boolean ok = expected == null ? actual == null : expected.equals(actual);
        if (!ok) System.out.println("  expected: [" + expected + "]\n  actual:   [" + actual + "]");
        check(ok, name);
    }

    /** A stream that gives a few bytes at a time, as a network does. */
    static class Dribble extends InputStream {
        final byte[] data;
        final int step;
        int pos;

        Dribble(byte[] data, int step) {
            this.data = data;
            this.step = step;
        }

        @Override public int read() {
            return pos < data.length ? data[pos++] & 0xff : -1;
        }

        @Override public int read(byte[] b, int off, int len) {
            if (pos >= data.length) return -1;
            int n = Math.min(Math.min(len, step), data.length - pos);
            System.arraycopy(data, pos, b, off, n);
            pos += n;
            return n;
        }
    }

    static byte[] sample(int bytes) {
        String unit = "abc \u00e9 \u65e5\u672c\u8a9e \uD83D\uDE00 {\"k\":1}\n";
        int unitBytes = unit.getBytes(StandardCharsets.UTF_8).length;
        StringBuilder text = new StringBuilder();
        for (int i = 0; i <= bytes / unitBytes + 1; i++) text.append(unit);
        byte[] all = text.toString().getBytes(StandardCharsets.UTF_8);
        // Cut on a character boundary: a continuation byte starts with 10xxxxxx.
        int end = Math.min(bytes, all.length);
        while (end > 0 && end < all.length && (all[end] & 0xC0) == 0x80) end--;
        byte[] out = new byte[end];
        System.arraycopy(all, 0, out, 0, end);
        return out;
    }

    static String read(byte[] body, long declared, int limit, int step) throws IOException {
        return BodyReader.readText(new Dribble(body, step), declared, limit);
    }

    static void deleteTree(File f) {
        File[] children = f.listFiles();
        if (children != null) for (File c : children) deleteTree(c);
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }

    static void bodyReader() throws Exception {
        byte[] small = sample(1000);
        String smallText = new String(small, StandardCharsets.UTF_8);
        eq(smallText, read(small, small.length, 1 << 20, 100), "declared exact");
        eq(smallText, read(small, -1, 1 << 20, 100), "undeclared");
        eq(smallText, read(small, 10, 1 << 20, 100), "declared smaller than real");
        eq(smallText, read(small, 5000, 1 << 20, 100), "declared bigger than real");
        eq("", read(new byte[0], 0, 1024, 10), "empty declared 0");
        eq("", read(new byte[0], -1, 1024, 10), "empty undeclared");
        eq(smallText, read(small, -1, 1 << 20, 1), "one byte at a time");

        byte[] medium = sample(300_000);
        String mediumText = new String(medium, StandardCharsets.UTF_8);
        eq(mediumText, read(medium, -1, 1 << 20, 4096), "300 KB undeclared multi-growth");
        eq(mediumText, read(medium, medium.length, 1 << 20, 4096), "300 KB declared");
        eq(mediumText, read(medium, -1, 1 << 20, 1 << 20), "300 KB big reads");

        // Boundaries around the first chunk and around the limit.
        for (int size : new int[]{65535, 65536, 65537, 98304, 98305, 131072}) {
            byte[] body = sample(size);
            String text = new String(body, StandardCharsets.UTF_8);
            eq(text, read(body, -1, 1 << 20, 8192), "boundary " + size);
        }
        byte[] exact = new byte[65536 * 3];
        java.util.Arrays.fill(exact, (byte) 'x');
        eq(new String(exact, StandardCharsets.UTF_8), read(exact, -1, exact.length, 10000), "body exactly at the limit, undeclared");
        eq(new String(exact, StandardCharsets.UTF_8), read(exact, exact.length, exact.length, 10000), "body exactly at the limit, declared");

        byte[] over = new byte[exact.length + 1];
        java.util.Arrays.fill(over, (byte) 'x');
        try {
            read(over, -1, exact.length, 10000);
            check(false, "one byte over the limit, undeclared");
        } catch (BodyReader.TooLargeException e) {
            eq((long) exact.length, e.limit, "limit reported (undeclared)");
        }
        try {
            read(over, over.length, exact.length, 10000);
            check(false, "declared over the limit");
        } catch (BodyReader.TooLargeException e) {
            check(true, "declared over the limit");
        }
        // Declared over the limit must not read anything.
        Dribble untouched = new Dribble(over, 10);
        try {
            BodyReader.readText(untouched, over.length, exact.length);
        } catch (BodyReader.TooLargeException e) {
            eq(0, untouched.pos, "declared over the limit reads nothing");
        }
        // A server that lies about the length and sends much more.
        byte[] huge = new byte[5_000_000];
        java.util.Arrays.fill(huge, (byte) 'y');
        try {
            read(huge, 100, 1_000_000, 65536);
            check(false, "liar over the limit");
        } catch (BodyReader.TooLargeException e) {
            check(true, "liar over the limit");
        }
        // Limit smaller than the first chunk, and zero.
        eq("abc", read("abc".getBytes(StandardCharsets.UTF_8), -1, 3, 1), "tiny limit exact");
        try {
            read("abcd".getBytes(StandardCharsets.UTF_8), -1, 3, 1);
            check(false, "tiny limit over");
        } catch (BodyReader.TooLargeException e) {
            check(true, "tiny limit over");
        }
        eq("", read(new byte[0], -1, 0, 1), "limit 0 empty");
        try {
            read(new byte[]{1}, -1, 0, 1);
            check(false, "limit 0 one byte");
        } catch (BodyReader.TooLargeException e) {
            check(true, "limit 0 one byte");
        }
        // Random content, random read sizes.
        Random random = new Random(42);
        for (int i = 0; i < 50; i++) {
            byte[] body = sample(random.nextInt(400_000) + 1);
            long declared = random.nextBoolean() ? body.length : -1;
            eq(new String(body, StandardCharsets.UTF_8), read(body, declared, 1 << 22, random.nextInt(70_000) + 1), "random " + i);
        }
    }

    static void logFile() throws Exception {
        File dir = Files.createTempDirectory("arr411-log").toFile();
        try {
            logFileIn(dir);
        } finally {
            deleteTree(dir);
        }
    }

    static void logFileIn(File dir) throws Exception {
        File target = new File(new File(dir, "nested"), "arr411.log");
        LogFile log = new LogFile(target, 1000);

        eq(0, log.tail(10).size(), "tail of a missing file");
        eq("", log.tailText(100), "tailText of a missing file");
        log.append("first");
        log.append("second\n");
        log.append("third");
        eq(List.of("first", "second", "third"), log.tail(10), "append creates the folder, one line each");
        eq(List.of("second", "third"), log.tail(2), "tail(2)");
        eq(0, log.tail(0).size(), "tail(0)");

        // Rotation: the file stays under twice the limit and the newest lines are always there, in order.
        for (int i = 0; i < 200; i++) log.append(String.format("line %03d %s", i, "x".repeat(40)));
        check(target.length() <= 1000 + 60, "current file under the limit + one line: " + target.length());
        check(new File(target.getPath() + ".1").isFile(), "rotated file exists");
        check(log.size() <= 2 * (1000 + 60), "total under twice the limit: " + log.size());
        List<String> last = log.tail(5);
        eq(5, last.size(), "tail(5) size");
        eq("line 199 " + "x".repeat(40), last.get(4), "newest line last");
        eq("line 195 " + "x".repeat(40), last.get(0), "5 lines back");
        List<String> many = log.tail(1000);
        List<Integer> numbers = new ArrayList<>();
        for (String line : many) numbers.add(Integer.parseInt(line.substring(5, 8)));
        boolean ordered = true;
        for (int i = 1; i < numbers.size(); i++) ordered &= numbers.get(i) == numbers.get(i - 1) + 1;
        check(ordered && numbers.get(numbers.size() - 1) == 199, "lines across both files are contiguous and ordered: " + numbers.size());
        check(log.tailText(300).contains("line 199"), "tailText has the newest");
        check(log.tailText(300).length() <= 300 + 60, "tailText bounded");

        // CRLF and blank lines.
        File crlf = new File(dir, "crlf.log");
        Files.write(crlf.toPath(), "a\r\n\r\nb\r\n".getBytes(StandardCharsets.UTF_8));
        eq(List.of("a", "b"), new LogFile(crlf, 1000).tail(10), "CRLF and blank lines");

        // A big file read from the middle: the cut first line is dropped.
        File big = new File(dir, "big.log");
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < 20000; i++) text.append("entry ").append(i).append('\n');
        Files.write(big.toPath(), text.toString().getBytes(StandardCharsets.UTF_8));
        List<String> bigTail = new LogFile(big, 10_000_000).tail(3);
        eq(List.of("entry 19997", "entry 19998", "entry 19999"), bigTail, "tail of a big file");
        List<String> bigAll = new LogFile(big, 10_000_000).tail(100_000);
        check(bigAll.size() < 20000 && bigAll.get(0).matches("entry \\d+"), "big file tail starts on a whole line: " + bigAll.get(0));

        log.clear();
        check(!target.exists() && !new File(target.getPath() + ".1").exists(), "clear removes both files");
        eq(0, log.tail(10).size(), "tail after clear");
        log.append("again");
        eq(List.of("again"), log.tail(10), "append after clear");

        // A path that cannot be written: no exception.
        File blocker = new File(dir, "blocker");
        Files.write(blocker.toPath(), new byte[]{1});
        new LogFile(new File(blocker, "sub\\x.log"), 100).append("nothing");
        check(true, "unwritable path does not throw");
    }

    static void redact() {
        eq("password=***", Redact.clean("password=hunter2"), "password=");
        eq("Password: ***", Redact.clean("Password: hunter2"), "Password:");
        eq("{\"password\":***}", Redact.clean("{\"password\":\"hunter2\"}"), "json password");
        eq("{\"password\":***,\"a\":1}", Redact.clean("{\"password\":\"hun ter2\",\"a\":1}"), "json password with a space");
        eq("GET /api?api_key=***&x=1", Redact.clean("GET /api?api_key=abc123&x=1"), "api_key in query");
        eq("GET /api?apikey=***&x=1", Redact.clean("GET /api?apikey=abc123&x=1"), "apikey in query");
        eq("Authorization: ***", Redact.clean("Authorization: Bearer abc.def.ghi"), "bearer");
        eq("authorization: ***", Redact.clean("authorization: Basic dXNlcjpwYXNz"), "basic");
        eq("Cookie: ***", Redact.clean("Cookie: SID=abcdef"), "cookie");
        eq("Set-Cookie: ***; Path=/", Redact.clean("Set-Cookie: SID=abcdef; Path=/"), "set-cookie");
        eq("sid=***;", Redact.clean("sid=abc;"), "sid");
        eq("http://***@192.168.1.2:8080/x", Redact.clean("http://admin:secret@192.168.1.2:8080/x"), "url credentials");
        eq("https://***@host/x", Redact.clean("https://user@host/x"), "url user only");
        eq("token: ***", Redact.clean("token: abcd1234"), "token");
        eq("secret=***", Redact.clean("secret=\"a b c\""), "quoted secret");
        String plain = "WARN request failed: GET 192.168.1.2:8080 /api/v2/torrents/info -> 502 in 8 s (heap 120/256 MB)";
        eq(plain, Redact.clean(plain), "plain text untouched");
        eq(null, Redact.clean(null), "null");
        eq("", Redact.clean(""), "empty");
        // Redaction twice gives the same text.
        String once = Redact.clean("password=hunter2 http://u:p@h/ token=abc");
        eq(once, Redact.clean(once), "idempotent");
    }

    static void redactPasskeys() {
        String key = "0123456789abcdef0123456789abcdef";
        eq("passkey=***", Redact.clean("passkey=abc123def"), "passkey=");
        eq("Passkey=***", Redact.clean("Passkey=ABC123"), "Passkey= (any case)");
        eq("PASSKEY: ***", Redact.clean("PASSKEY: abc123"), "PASSKEY:");
        eq("pass_key=*** pass-key=***", Redact.clean("pass_key=a1 pass-key=b2"), "pass_key and pass-key");
        eq("{\"passkey\":***}", Redact.clean("{\"passkey\":\"abc123\"}"), "json passkey");
        eq("https://t.c411.org/announce?passkey=***&x=1", Redact.clean("https://t.c411.org/announce?passkey=" + key + "&x=1"), "passkey in a query");
        eq("https://t.c411.org/announce/***", Redact.clean("https://t.c411.org/announce/" + key), "passkey after /announce/");
        eq("https://t.c411.org/***/announce", Redact.clean("https://t.c411.org/" + key + "/announce"), "passkey before /announce");
        eq("magnet:?xt=urn:btih:abc&tr=https%3A%2F%2Ft.c411.org%2Fannounce%3Fpasskey%3D***",
            Redact.clean("magnet:?xt=urn:btih:abc&tr=https%3A%2F%2Ft.c411.org%2Fannounce%3Fpasskey%3D" + key),
            "passkey inside an encoded tracker URL");
        eq("tr=https%3A%2F%2Ft.c411.org%2Fannounce%2F***", Redact.clean("tr=https%3A%2F%2Ft.c411.org%2Fannounce%2F" + key), "encoded announce path");
        for (String text : new String[]{"passkey=abc123def", "https://t.c411.org/announce/" + key, "https://t.c411.org/" + key + "/announce",
            "tr=https%3A%2F%2Ft.c411.org%2Fannounce%3Fpasskey%3D" + key}) {
            String once = Redact.clean(text);
            eq(once, Redact.clean(once), "idempotent: " + text);
            check(!once.contains(key) && !once.contains("abc123def"), "passkey gone: " + text);
        }
        String harmless = "https://c411.org/torrents/123?page=2 /api/" + key + "/download Mon passkey est invalide";
        eq(harmless, Redact.clean(harmless), "a long id that is not a passkey and a sentence stay as they are");
    }

    static void diagText() {
        eq("0", DiagText.megabytes(1_000_000), "MB small");
        eq("224", DiagText.megabytes(224L * 1024 * 1024 + 5), "MB");
        eq("45 s", DiagText.duration(45_000), "45 s");
        eq("12 min 5 s", DiagText.duration((12 * 60 + 5) * 1000L), "12 min 5 s");
        eq("3 h 20 min", DiagText.duration((3 * 3600 + 20 * 60) * 1000L), "3 h 20 min");
        eq("2 d 3 h", DiagText.duration((2 * 86400 + 3 * 3600) * 1000L), "2 d 3 h");
        eq("0 s", DiagText.duration(-5), "negative");
        eq("LOW_MEMORY", DiagText.exitReason(3), "exit 3");
        eq("CRASH_NATIVE", DiagText.exitReason(5), "exit 5");
        eq("PACKAGE_UPDATED", DiagText.exitReason(16), "exit 16");
        eq("reason 99", DiagText.exitReason(99), "exit unknown");
        eq("CANT_SAVE_STATE", DiagText.importance(350), "importance 350");
        eq("CACHED", DiagText.importance(400), "importance 400");
        eq("RUNNING_CRITICAL", DiagText.trimLevel(15), "trim 15");
        eq("IMPORTANT", DiagText.rendererPriority(2), "renderer 2");
        eq("a | b | c", DiagText.oneLine("a\nb\r\nc\n", 100), "oneLine");
        eq("abcde...", DiagText.oneLine("abcdefgh", 5), "oneLine cut");
        eq("", DiagText.oneLine(null, 5), "oneLine null");

        Throwable cause = new IllegalStateException("inner");
        Throwable error = new RuntimeException("outer", cause);
        String trace = DiagText.stack(error, 10_000);
        check(trace.startsWith("java.lang.RuntimeException: outer") && trace.contains("Caused by: java.lang.IllegalStateException: inner"), "stack has the cause");
        check(DiagText.stack(error, 50).endsWith("[... cut]") && DiagText.stack(error, 50).length() < 80, "stack is cut");

        String log = "=== 2026-11-18 10:00:00 first ===\nline\n\n=== 2026-11-18 11:00:00 second ===\nboom\nat x\n";
        eq("=== 2026-11-18 11:00:00 second ===\nboom\nat x", DiagText.lastEntry(log, 1000), "last entry");
        eq("=== only ===\nbody", DiagText.lastEntry("=== only ===\nbody\n", 1000), "single entry at start");
        eq(null, DiagText.lastEntry("no header here", 1000), "no entry");
        eq(null, DiagText.lastEntry(null, 1000), "null log");
        check(DiagText.lastEntry(log, 20).endsWith("[... cut]"), "entry cut");
        String stamp = DiagText.timestamp(0);
        check(stamp.matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}"), "timestamp shape: " + stamp);
    }

    static void restartDelay() {
        RestartDelay d = new RestartDelay();
        eq(0L, d.afterDeath(1_000), "first death is rebuilt at once");
        eq(0L, d.afterDeath(2_000), "second death is rebuilt at once");
        eq(2_000L, d.afterDeath(3_000), "third death within a minute waits 2 s");
        eq(4_000L, d.afterDeath(4_000), "fourth waits 4 s");
        eq(6_000L, d.afterDeath(5_000), "fifth waits 6 s");
        // Deaths older than the window stop counting.
        eq(0L, d.afterDeath(5_000 + RestartDelay.WINDOW_MS + 10_000), "a quiet minute resets the delay");

        RestartDelay loop = new RestartDelay();
        long last = 0;
        for (int i = 0; i < 40; i++) last = loop.afterDeath(i * 500L);
        eq(RestartDelay.MAX_MS, last, "the delay never exceeds the maximum");

        RestartDelay spaced = new RestartDelay();
        boolean allFree = true;
        for (int i = 0; i < 10; i++) allFree &= spaced.afterDeath(i * (RestartDelay.WINDOW_MS + 1)) == 0;
        check(allFree, "deaths a minute apart are always rebuilt at once");
    }

    // ---------- URL policy ----------

    static void c411Urls() {
        for (String ok : new String[]{"https://c411.org", "https://c411.org/", "https://www.c411.org/torrents?q=a#b", "https://sub.c411.org/x",
            "https://a.b.c411.org/", "https://c411.org:443/x", "HTTPS://C411.ORG/Path"}) {
            eq(ok, UrlPolicy.c411Https(ok), "c411 over https is loaded as it is: " + ok);
            check(UrlPolicy.isC411Page(ok), "c411 page: " + ok);
        }
        eq("https://c411.org/x", UrlPolicy.c411Https("http://c411.org/x"), "http is upgraded to https");
        eq("https://www.c411.org/a?b=c#d", UrlPolicy.c411Https("http://www.c411.org/a?b=c#d"), "the upgrade keeps path, query and fragment");
        eq("https://sub.c411.org", UrlPolicy.c411Https("HTTP://sub.c411.org"), "HTTP upgraded whatever its case");
        check(!UrlPolicy.isC411Page("http://c411.org/x"), "an http page is not a page the extension is injected into");

        String[] refused = {
            null, "", "c411.org", "//c411.org/x", "/torrents", "about:blank",
            "javascript:alert(1)", "javascript://c411.org/%0aalert(1)", "JavaScript://c411.org/%0Aalert(document.cookie)",
            "data:text/html,<script>alert(1)</script>", "data://c411.org/x",
            "intent://c411.org/#Intent;scheme=https;package=com.evil;end", "intent:#Intent;component=a/b;end",
            "file:///data/data/dev.c411.qbittorrent/files/x", "file://c411.org/x", "content://c411.org/x", "ftp://c411.org/",
            "blob:https://c411.org/uuid", "magnet:?xt=urn:btih:abc",
            "https://evil-c411.org/", "https://evilc411.org/", "https://xc411.org/", "https://notc411.org/", "https://c411org/",
            "https://c411.org.evil.com/", "https://c411.org.evil.com:443/", "https://www.c411.org.evil.com/x",
            "https://.c411.org/", "https://c411.org./", "https://c411.org..evil.com/",
            "https://c411.org@evil.com/", "https://evil.com@c411.org/", "https://user:pw@c411.org/", "https://c411.org:443@evil.com/",
            "https://c411.org\\@evil.com/", "https://c411.org\\evil.com/", "https://c411.org /x", "https://c411.org\n.evil.com/",
            "https://c411.org%2eevil.com/", "https://[::1]/", "https://127.0.0.1/", "https://192.168.1.2:8080/",
            "https://c411.org:8443/x", "https://c411.org:/x", "https://c411.org:99999/x", "http://c411.org:80/x", "http://c411.org:8080/x",
            "https://c411.org/" + "a".repeat(UrlPolicy.MAX_URL)};
        for (String bad : refused) {
            String shown = bad == null ? "null" : bad.length() > 60 ? bad.substring(0, 60) + "..." : bad.replace('\n', '~');
            eq(null, UrlPolicy.c411Https(bad), "refused as a C411 page: " + shown);
            check(!UrlPolicy.isC411Page(bad), "not a C411 page: " + shown);
            check(!UrlPolicy.isFetchTarget(bad), "not a fetch target: " + shown);
        }
        check(!UrlPolicy.isC411Page("javascript://c411.org/%0aalert(1)"), "javascript: is never a page");
    }

    static void fetchTargets() {
        for (String ok : new String[]{"https://c411.org/api/torrents/1/download", "https://dl.c411.org/x.torrent", "https://c411.org:443/x", "https://C411.org/x"}) {
            check(UrlPolicy.isFetchTarget(ok), "fetch target: " + ok);
        }
        for (String bad : new String[]{"http://c411.org/x", "https://127.0.0.1/x", "https://localhost/x", "https://192.168.1.2:8080/api/v2/torrents/add",
            "https://10.0.0.5/", "https://169.254.169.254/latest/meta-data", "https://[::1]/", "https://[::ffff:7f00:1]/", "https://c411.org:8443/x",
            "https://evil-c411.org/x", "https://c411.org.evil.com/x", "https://c411.org@evil.com/x", "https://qbittorrent.lan/", "javascript:alert(1)",
            "file:///x", "ftp://c411.org/x", "", null}) {
            check(!UrlPolicy.isFetchTarget(bad), "not a fetch target: " + bad);
        }
        eq("sub.c411.org", UrlPolicy.hostOf("https://Sub.C411.org:443/x"), "host of an address");
        eq("127.0.0.1", UrlPolicy.hostOf("http://127.0.0.1:8080/x"), "host of an IP address");
        eq(null, UrlPolicy.hostOf("https://c411.org@evil.com/"), "no host when there are credentials");
        eq(null, UrlPolicy.hostOf("javascript:alert(1)"), "no host for javascript:");
    }

    static void externalUrls() {
        for (String ok : new String[]{"https://a.example/x", "http://192.168.1.2:8080", "magnet:?xt=urn:btih:abc", "HTTPS://X.example", "MAGNET:?xt=1"}) {
            check(UrlPolicy.isExternalOpenable(ok), "may be opened outside: " + ok);
        }
        for (String bad : new String[]{"intent://x#Intent;scheme=https;end", "intent:#Intent;component=a/b;end", "file:///sdcard/x", "content://x/y",
            "javascript:alert(1)", "market://details?id=x", "mailto:a@b.c", "tel:123", "data:text/html,x", "ftp://x/y", "", "no scheme", ":x", null,
            "https://x.example/" + "a".repeat(UrlPolicy.MAX_URL)}) {
            check(!UrlPolicy.isExternalOpenable(bad), "never opened outside: " + (bad == null ? "null" : bad.length() > 60 ? bad.substring(0, 60) : bad));
        }
    }

    static byte[] ip(String literal) throws Exception {
        return InetAddress.getByName(literal).getAddress();
    }

    static byte[] mapped(int a, int b, int c, int d) {
        byte[] x = new byte[16];
        x[10] = (byte) 0xff;
        x[11] = (byte) 0xff;
        x[12] = (byte) a;
        x[13] = (byte) b;
        x[14] = (byte) c;
        x[15] = (byte) d;
        return x;
    }

    static void publicAddresses() throws Exception {
        for (String a : new String[]{"8.8.8.8", "1.1.1.1", "93.184.216.34", "172.15.255.255", "172.32.0.1", "100.63.255.255", "100.128.0.1", "198.20.0.1",
            "223.255.255.255", "2001:4860:4860::8888", "2606:4700:4700::1111", "2a00:1450:4007:80f::200e"}) {
            check(UrlPolicy.isPublicAddress(ip(a), false), "public: " + a);
        }
        for (String a : new String[]{"0.0.0.0", "127.0.0.1", "127.255.255.254", "10.0.0.1", "10.255.255.255", "172.16.0.1", "172.31.255.255", "192.168.0.1",
            "192.168.1.2", "169.254.169.254", "100.64.0.1", "100.127.255.255", "192.0.0.1", "192.0.2.1", "192.88.99.1", "198.18.0.1", "198.19.255.255",
            "198.51.100.7", "203.0.113.9", "224.0.0.1", "239.255.255.250", "240.0.0.1", "255.255.255.255",
            "::", "::1", "fe80::1", "fc00::1", "fd12:3456::1", "ff02::1", "2001:db8::1", "2001::1", "3fff::1",
            "64:ff9b::7f00:1", "64:ff9b::c0a8:101", "2002:c0a8:101::", "2002:7f00:1::"}) {
            check(!UrlPolicy.isPublicAddress(ip(a), false), "not public: " + a);
        }
        check(UrlPolicy.isPublicAddress(ip("64:ff9b::808:808"), false), "NAT64 of a public address");
        check(UrlPolicy.isPublicAddress(ip("2002:808:808::"), false), "6to4 of a public address");
        check(!UrlPolicy.isPublicAddress(mapped(127, 0, 0, 1), false), "IPv4-mapped loopback");
        check(!UrlPolicy.isPublicAddress(mapped(192, 168, 1, 2), false), "IPv4-mapped private");
        check(!UrlPolicy.isPublicAddress(mapped(10, 0, 0, 1), false), "IPv4-mapped 10/8");
        check(UrlPolicy.isPublicAddress(mapped(8, 8, 8, 8), false), "IPv4-mapped public");

        check(!UrlPolicy.isPublicAddress(ip("198.18.0.7"), false), "198.18/15 refused by default");
        check(UrlPolicy.isPublicAddress(ip("198.18.0.7"), true), "198.18/15 tolerated for the fake addresses of VPN apps");
        check(UrlPolicy.isPublicAddress(ip("198.19.255.255"), true), "198.19/16 tolerated for the fake addresses of VPN apps");
        check(!UrlPolicy.isPublicAddress(ip("192.168.1.2"), true), "private stays refused with that tolerance");
        check(!UrlPolicy.isPublicAddress(ip("127.0.0.1"), true), "loopback stays refused with that tolerance");
        check(!UrlPolicy.isPublicAddress(null, true), "no address");
        check(!UrlPolicy.isPublicAddress(new byte[3], true), "a malformed address");
    }

    // ---------- Redirect policy ----------

    static URL url(String s) throws Exception {
        return new URL(s);
    }

    static boolean same(String from, String to) throws Exception {
        return RedirectPolicy.staysOnServer(url(from), url(to));
    }

    static void redirects() throws Exception {
        for (int s : new int[]{301, 302, 303, 307, 308}) check(RedirectPolicy.isRedirect(s), "redirect " + s);
        for (int s : new int[]{200, 204, 300, 304, 305, 306, 400, 404, 500}) check(!RedirectPolicy.isRedirect(s), "not a redirect " + s);
        eq(5, RedirectPolicy.MAX_REDIRECTS, "five hops at most");

        URL base = url("https://nas.lan:8443/api/v2/auth/login");
        eq("https://nas.lan:8443/other", RedirectPolicy.resolve(base, "/other").toString(), "absolute path");
        eq("https://nas.lan:8443/api/v2/auth/next", RedirectPolicy.resolve(base, "next").toString(), "relative path");
        eq("http://other.example/x", RedirectPolicy.resolve(base, " http://other.example/x ").toString(), "absolute address, trimmed");
        eq(null, RedirectPolicy.resolve(base, null), "no Location");
        eq(null, RedirectPolicy.resolve(base, "  "), "blank Location");
        eq(null, RedirectPolicy.resolve(base, "javascript:alert(1)"), "unknown protocol");

        check(same("http://nas:8080/a", "http://nas:8080/b"), "same server, other path");
        check(same("http://NAS:8080/a", "http://nas:8080/b"), "host names compare without case");
        check(same("https://nas/a", "https://nas:443/a"), "explicit default port (https)");
        check(same("http://nas/a", "http://nas:80/a"), "explicit default port (http)");
        check(same("http://nas:8080/a", "https://nas/a"), "http to https upgrade on the same host");
        check(same("http://nas:8080/a", "https://nas:8443/a"), "the upgrade may change the port");
        check(!same("https://nas/a", "http://nas/a"), "never back from https to http");
        check(!same("https://nas/a", "https://other/a"), "another host");
        check(!same("https://nas.lan/a", "https://nas/a"), "a shorter host name");
        check(!same("https://nas/a", "https://nas.evil.com/a"), "a longer host name");
        check(!same("http://192.168.1.2:8080/a", "http://192.168.1.3:8080/a"), "another address");
        check(!same("https://nas:8443/a", "https://nas:9443/a"), "another port with the same scheme (https)");
        check(!same("http://nas/a", "http://nas:8080/a"), "another port with the same scheme (http)");
        check(!same("https://nas/a", "file:///etc/passwd"), "file:");
        check(!same("https://nas/a", "ftp://nas/a"), "ftp:");
        check(!same("http://nas/a", "ftp://nas/a"), "ftp: from http");

        eq("Redirection vers un autre serveur refus\u00e9e (evil.example)", RedirectPolicy.refusal(url("https://evil.example/x")), "refusal names the host");
        eq("Redirection vers un autre serveur refus\u00e9e (evil.example:8443)", RedirectPolicy.refusal(url("https://evil.example:8443/x")), "refusal names the port");

        eq("GET", RedirectPolicy.nextMethod(301, "POST"), "301 turns a POST into a GET");
        eq("GET", RedirectPolicy.nextMethod(302, "POST"), "302 turns a POST into a GET");
        eq("GET", RedirectPolicy.nextMethod(303, "POST"), "303 turns a POST into a GET");
        eq("GET", RedirectPolicy.nextMethod(303, "PUT"), "303 turns a PUT into a GET");
        eq("HEAD", RedirectPolicy.nextMethod(303, "HEAD"), "303 keeps a HEAD");
        eq("GET", RedirectPolicy.nextMethod(301, "GET"), "301 keeps a GET");
        eq("PUT", RedirectPolicy.nextMethod(301, "PUT"), "301 keeps a PUT");
        eq("POST", RedirectPolicy.nextMethod(307, "POST"), "307 keeps the POST");
        eq("PUT", RedirectPolicy.nextMethod(308, "PUT"), "308 keeps the PUT");
    }

    // ---------- Page gate ----------

    static void pageGate() {
        final long t = 1_000_000;
        PageGate g;

        g = new PageGate();
        for (String type : new String[]{"getCategories", "pageStatus", "qbitIndex", "pagePrefs", "arrMeta", "disarm"}) {
            eq(null, g.admit(type, t, true), type + " needs no touch on a visible tab");
        }

        // What only reads (and disarm) is served on screen and for a minute after the tab left: no touch needed, none can be missing.
        eq(60_000L, PageGate.OFF_SCREEN_GRACE_MS, "the grace is 60 s");
        eq("Onglet C411 masqué : reviens sur la page et réessaie.", PageGate.OFF_SCREEN, "same text as the Windows app");
        eq("Action refusée : l'onglet C411 n'est pas actif", PageGate.NOT_ACTIVE, "same text as the Windows app");
        for (String type : new String[]{"getCategories", "pageStatus", "qbitIndex", "pagePrefs", "arrMeta", "disarm"}) {
            g = new PageGate();
            g.screen(true, t);
            eq(null, g.admit(type, t + 1, true), type + " on screen");
            g.screen(false, t + 10_000);
            eq(null, g.admit(type, t + 10_000 + PageGate.OFF_SCREEN_GRACE_MS - 1, false), type + " just before the end of the grace");
            eq(PageGate.OFF_SCREEN, g.admit(type, t + 10_000 + PageGate.OFF_SCREEN_GRACE_MS, false), type + " once the grace has ended");
            eq(null, g.admit(type, t + 200_000, true), type + " served again when the tab is back");
        }
        g = new PageGate();
        g.admit("pageStatus", t, true);
        g.screen(false, t + 100_000);
        eq(null, g.admit("pageStatus", t + 130_000, false), "the grace counts from the moment the tab left, not from the last message");
        g = new PageGate();
        eq(null, g.admit("pageStatus", t, true), "a page that was never reported off screen");
        eq(null, g.admit("pageStatus", t + 5_000, false), "a tab that left unnoticed: the grace starts when it is seen");
        eq(PageGate.OFF_SCREEN, g.admit("pageStatus", t + 5_000 + PageGate.OFF_SCREEN_GRACE_MS, false), "and ends a minute later");
        g = new PageGate();
        g.screen(false, t);
        eq(null, g.admit("pageStatus", t + 5_000, false), "a page that loads while another tab is shown");
        eq(PageGate.OFF_SCREEN, g.admit("qbitIndex", t + PageGate.OFF_SCREEN_GRACE_MS, false), "but not for long");

        // What the page may send through send(): the reads and the messages of the send flow, nothing internal.
        for (String type : new String[]{"getCategories", "pageStatus", "qbitIndex", "pagePrefs", "arrMeta", "arm", "disarm", "upload"}) {
            check(PageGate.isPageMessage(type), type + " is a page message");
        }
        for (String type : new String[]{"__download", "download", "fetchBytes", "saveFile", "toast", "toggleTorrent", "listTorrents", "Upload", "pagePrefs ", "", null}) {
            check(!PageGate.isPageMessage(type), "[" + type + "] is not a page message");
        }

        g = new PageGate();
        eq(PageGate.NOT_ACTIVE, g.admit("arm", t, true), "arm without a touch");
        eq(PageGate.NO_FLOW, g.admit("upload", t, true), "upload without an arm");
        eq(PageGate.NOT_ACTIVE, g.admit("download", t, true), "download without a touch");
        eq(PageGate.NOT_ACTIVE, g.admit("fetchBytes", t, true), "fetchBytes without a touch");
        eq(PageGate.NOT_ACTIVE, g.admit("saveFile", t, true), "saveFile without a ticket");

        eq(30_000L, PageGate.GESTURE_WINDOW_MS, "the gesture window is 30 s");
        g = new PageGate();
        g.gesture(t);
        eq(null, g.admit("arm", t + PageGate.GESTURE_WINDOW_MS, true), "arm at the end of the window");
        g = new PageGate();
        g.gesture(t);
        eq(PageGate.NOT_ACTIVE, g.admit("arm", t + PageGate.GESTURE_WINDOW_MS + 1, true), "arm just after the window");
        g = new PageGate();
        g.gesture(t);
        eq(PageGate.NOT_ACTIVE, g.admit("arm", t + 1, false), "arm while the C411 tab is not the visible one");
        eq(null, g.admit("arm", t + 2, true), "same touch, tab visible again");
        g = new PageGate();
        g.gesture(t);
        g.screen(false, t + 1);
        eq(PageGate.NOT_ACTIVE, g.admit("arm", t + 2, false), "what acts has no grace after the tab left");

        // The flow of a real tap on the qBittorrent button.
        g = new PageGate();
        g.gesture(t);
        eq(null, g.admit("arm", t, true), "arm after a tap");
        eq(null, g.admit("download", t + 4_000, true), "the page download");
        eq(null, g.admit("fetchBytes", t + 5_000, true), "the native fetch");
        eq(null, g.admit("upload", t + 6_000, true), "the upload");
        eq(PageGate.NO_FLOW, g.admit("upload", t + 6_500, true), "one arm sends one torrent");

        // A slow flow outlives the gesture window, not its own.
        g = new PageGate();
        g.gesture(t);
        eq(null, g.admit("arm", t, true), "arm");
        eq(null, g.admit("download", t + 60_000, true), "download after the gesture window, inside the flow");
        eq(null, g.admit("upload", t + PageGate.FLOW_WINDOW_MS - 1, true), "upload at the end of the flow");
        g = new PageGate();
        g.gesture(t);
        g.admit("arm", t, true);
        eq(PageGate.NO_FLOW, g.admit("upload", t + PageGate.FLOW_WINDOW_MS, true), "upload after the flow expired");
        eq(PageGate.NOT_ACTIVE, g.admit("download", t + PageGate.FLOW_WINDOW_MS + 1, true), "download after the flow and the window");

        // The normal download button of the page needs no arm: a tap is enough.
        g = new PageGate();
        g.gesture(t);
        eq(null, g.admit("download", t + 10, true), "download after a tap");
        eq(null, g.admit("fetchBytes", t + 20, true), "fetchBytes after a tap");
        eq(PageGate.NOT_ACTIVE, g.admit("download", t + 30, false), "download while the tab is not visible");

        g = new PageGate();
        g.gesture(t);
        g.admit("arm", t, true);
        g.admit("arm", t + 1, true);
        eq(null, g.admit("disarm", t + 2, true), "disarm");
        eq(null, g.admit("upload", t + 3, true), "one flow is left after one disarm");
        eq(PageGate.NO_FLOW, g.admit("upload", t + 4, true), "and only one");
        g = new PageGate();
        eq(null, g.admit("disarm", t, true), "disarm without a flow is harmless");
        eq(PageGate.NO_FLOW, g.admit("upload", t + 1, true), "and opens nothing");

        g = new PageGate();
        g.gesture(t);
        for (int i = 0; i < 6; i++) eq(null, g.admit("arm", t + i, true), "arm " + i);
        int sent = 0;
        for (int i = 0; i < 6; i++) if (g.admit("upload", t + 100 + i, true) == null) sent++;
        eq(4, sent, "at most four flows wait");

        // Rates: 30 messages and 5 uploads per 10 s, and four native downloads at a time.
        eq("Trop de requêtes d'un coup : réessaie.", PageGate.TOO_MANY, "the reply text of a refused burst");
        eq("Trop d'envois d'un coup : réessaie.", PageGate.TOO_MANY_UPLOADS, "the reply text of too many uploads");
        g = new PageGate();
        for (int i = 0; i < 30; i++) eq(null, g.admit("pageStatus", t + i, true), "message " + i);
        eq(PageGate.TOO_MANY, g.admit("pageStatus", t + 30, true), "31st message in 10 s");
        eq(PageGate.TOO_MANY, g.admit("arrMeta", t + 9_000, true), "still too many 9 s later");
        eq(null, g.admit("pageStatus", t + 20_000, true), "accepted again once the window has passed");

        g = new PageGate();
        for (int i = 0; i < 30; i++) eq(null, g.admit("pagePrefs", t + i, true), "pagePrefs " + i);
        eq(PageGate.TOO_MANY, g.admit("pagePrefs", t + 30, true), "pagePrefs is rate limited like the other reads");

        g = new PageGate();
        g.gesture(t);
        for (int i = 0; i < 30; i++) g.admit("getCategories", t + i, true);
        eq(PageGate.TOO_MANY, g.admit("arm", t + 31, true), "arm is rate limited too");

        g = new PageGate();
        g.gesture(t);
        int accepted = 0;
        for (int i = 0; i < 5; i++) {
            g.admit("arm", t + 2 * i, true);
            if (g.admit("upload", t + 2 * i + 1, true) == null) accepted++;
        }
        eq(5, accepted, "five uploads in 10 s");
        eq(null, g.admit("arm", t + 100, true), "sixth arm");
        eq(PageGate.TOO_MANY_UPLOADS, g.admit("upload", t + 101, true), "sixth upload in 10 s");
        eq(null, g.admit("upload", t + 11_000, true), "the same flow goes through once the window has passed");

        g = new PageGate();
        for (int i = 0; i < PageGate.MAX_PARALLEL_FETCHES; i++) check(g.beginFetch(), "native download " + i);
        check(!g.beginFetch(), "a fifth native download at the same time");
        g.endFetch();
        check(g.beginFetch(), "room again once one has ended");
        for (int i = 0; i < 10; i++) g.endFetch();
        int started = 0;
        for (int i = 0; i < 8; i++) if (g.beginFetch()) started++;
        eq(4, started, "ending more downloads than were started does not make room");
        eq("Trop de téléchargements en cours", PageGate.TOO_MANY_FETCHES, "same text as the Windows app");

        // A saved file needs the ticket of a blob the app itself asked for.
        g = new PageGate();
        g.gesture(t);
        g.admit("arm", t, true);
        eq(PageGate.NOT_ACTIVE, g.admit("saveFile", t + 1, true), "a tap and a flow are not enough to save a file");
        g.grantSave(t + 10);
        eq(null, g.admit("saveFile", t + 20, true), "saveFile after the app asked for a blob");
        eq(PageGate.NOT_ACTIVE, g.admit("saveFile", t + 30, true), "one ticket, one file");
        g.grantSave(t + 100);
        eq(null, g.admit("saveFile", t + 100 + PageGate.SAVE_TICKET_MS - 1, true), "a ticket is valid for a minute");
        g.grantSave(t + 100);
        eq(PageGate.NOT_ACTIVE, g.admit("saveFile", t + 100 + PageGate.SAVE_TICKET_MS, true), "an expired ticket");
        g = new PageGate();
        for (int i = 0; i < 6; i++) g.grantSave(t + i);
        int saved = 0;
        for (int i = 0; i < 6; i++) if (g.admit("saveFile", t + 100 + i, true) == null) saved++;
        eq(4, saved, "at most four tickets wait");

        g = new PageGate();
        g.gesture(t);
        for (String type : new String[]{"toast", "Upload", "UPLOAD", "", "__proto__", "openUrl", "eval", "download ", "upload\u0000"}) {
            eq(PageGate.REFUSED, g.admit(type, t, true), "unknown type [" + type + "]");
        }
        eq(PageGate.REFUSED, g.admit(null, t, true), "no type");

        g = new PageGate();
        check(!g.allowToast(t, false), "no toast while the tab is hidden");
        check(g.allowToast(t, true), "first toast");
        check(!g.allowToast(t + PageGate.TOAST_INTERVAL_MS - 1, true), "second toast too soon");
        check(g.allowToast(t + PageGate.TOAST_INTERVAL_MS, true), "a toast every 3 s");
        eq("a b", PageGate.toastText("a\nb"), "control characters become spaces");
        eq("hello world", PageGate.toastText("  hello\u0000world  "), "trimmed");
        eq(null, PageGate.toastText(null), "no text");
        eq(null, PageGate.toastText(" \n\t "), "blank text");
        eq(140, PageGate.toastText("x".repeat(500)).length(), "140 characters at most");
    }

    static void messageSizes() {
        String big = "x".repeat(PageGate.MESSAGE_LIMIT + 1);
        check(PageGate.sizeAllowed("{\"type\":\"arm\"}", null), "small message");
        check(PageGate.sizeAllowed("x".repeat(PageGate.MESSAGE_LIMIT), "arm"), "exactly 1 MB");
        check(!PageGate.sizeAllowed(big, "arm"), "over 1 MB");
        check(!PageGate.sizeAllowed(big, null), "over 1 MB, type not known yet");
        String upload = "{\"type\":\"upload\",\"name\":\"a.torrent\",\"base64\":\"" + "A".repeat(20 * 1024 * 1024) + "\"}";
        check(PageGate.sizeAllowed(upload, null), "a 20 MB upload is recognised by its beginning");
        check(PageGate.sizeAllowed(upload, "upload"), "a 20 MB upload");
        check(!PageGate.sizeAllowed(upload, "arm"), "20 MB is too much for any other type");
        check(!PageGate.sizeAllowed("x".repeat(PageGate.UPLOAD_LIMIT + 1), "upload"), "over 24 MB, even an upload");
        check(!PageGate.sizeAllowed("{\"type\":\"arm\",\"x\":\"" + "A".repeat(2 * 1024 * 1024) + "\"}", null), "a big message that is not an upload");
    }

    // ---------- Log budgets ----------

    static void lruThrottle() {
        LruThrottle t = new LruThrottle(100);
        eq(0, t.allow("a", 0, 1000), "first time");
        eq(-1, t.allow("a", 500, 1000), "too soon");
        eq(-1, t.allow("a", 600, 1000), "still too soon");
        eq(2, t.allow("a", 1000, 1000), "let through, with the number of skipped repeats");
        eq(-1, t.allow("a", 1500, 1000), "the interval restarts when a line goes through");
        eq(1, t.allow("a", 2000, 1000), "one skipped since the last line");
        eq(0, t.allow("a", 3000, 1000), "nothing skipped");
        eq(0, t.allow("b", 3000, 1000), "another key is independent");

        // Bounded: the key used least recently is forgotten, not the whole table.
        LruThrottle small = new LruThrottle(3);
        small.allow("a", 0, 1_000_000);
        small.allow("b", 0, 1_000_000);
        small.allow("c", 0, 1_000_000);
        small.allow("a", 1, 1_000_000);
        small.allow("d", 2, 1_000_000);
        eq(3, small.size(), "bounded at 3 keys");
        eq(-1, small.allow("a", 3, 1_000_000), "the recently used key is still remembered");
        eq(0, small.allow("b", 4, 1_000_000), "the least recently used key was forgotten");
        eq(3, small.size(), "still 3 keys");

        LruThrottle flood = new LruThrottle(300);
        flood.allow("hot", 0, 1_000_000_000L);
        for (int i = 0; i < 5000; i++) {
            flood.allow("k" + i, 1 + i, 1_000_000_000L);
            flood.allow("hot", 1 + i, 1_000_000_000L);
        }
        check(flood.size() <= 300, "5000 different keys never exceed the bound: " + flood.size());
        eq(5000, flood.allow("hot", 2_000_000_000L, 1_000_000_000L), "a key that keeps coming is never forgotten");
    }

    static void consoleBudget() {
        ConsoleBudget b = new ConsoleBudget();
        long t = 5_000_000;
        for (int i = 0; i < ConsoleBudget.PER_MINUTE; i++) eq(0, b.take(t + i), "line " + i + " is within the budget");
        eq(-1, b.take(t + 100), "the 21st line of the minute is dropped");
        eq(-1, b.take(t + 200), "and the 22nd");
        eq(2, b.take(t + ConsoleBudget.WINDOW_MS), "the next minute announces the 2 lines dropped");
        eq(0, b.take(t + ConsoleBudget.WINDOW_MS + 1), "announced once");
        for (int i = 0; i < ConsoleBudget.PER_MINUTE - 2; i++) b.take(t + ConsoleBudget.WINDOW_MS + 2 + i);
        eq(-1, b.take(t + ConsoleBudget.WINDOW_MS + 100), "the new minute has its own budget");
        eq(1, b.takeIgnored(), "the dropped lines can be announced without waiting for a new line");
        eq(0, b.takeIgnored(), "and only once");
        eq(20, ConsoleBudget.PER_MINUTE, "20 lines a minute");
    }

    static void fileNames() {
        eq("a.torrent", FileNames.clean("a.torrent"), "plain name");
        eq("passwd", FileNames.clean("../../etc/passwd"), "no folder (/)");
        eq("evil.sh", FileNames.clean("C:\\x\\evil.sh"), "no folder (\\)");
        eq("fichier", FileNames.clean(null), "no name");
        eq("fichier", FileNames.clean(""), "empty name");
        eq("fichier", FileNames.clean(".."), "only dots");
        eq("hidden", FileNames.clean(".hidden"), "no leading dot");
        eq("a_b_c_.torrent", FileNames.clean("a:b*c?.torrent"), "characters the storage refuses");
        eq("a_b", FileNames.clean("a\nb"), "control characters");
        check(FileNames.clean("x".repeat(300)).length() <= 120, "120 characters at most");
        eq("a".repeat(119), FileNames.clean("a".repeat(119) + "\uD83D\uDE00"), "a cut never leaves half a character");

        eq("movie.torrent", FileNames.torrent("movie"), "the extension is added");
        eq("movie.torrent", FileNames.torrent("movie.torrent"), "and kept");
        eq("MOVIE.TORRENT", FileNames.torrent("MOVIE.TORRENT"), "whatever its case");
        eq("evil.sh.torrent", FileNames.torrent("evil.sh"), "another extension does not stay the last one");
        eq("a.apk.torrent", FileNames.torrent("a.apk"), "another extension (apk)");
        eq("x.torrent", FileNames.torrent("../x"), "no folder");
        eq("fichier.torrent", FileNames.torrent(null), "no name");
        String longName = FileNames.torrent("x".repeat(200) + ".torrent");
        check(longName.length() <= 120 && longName.endsWith(".torrent"), "a long name still ends with .torrent in 120 characters: " + longName.length());
    }

    static void saveText() throws Exception {
        String plain = "arr411-reglages-2026-11-22.json";
        String encrypted = "arr411-reglages-2026-11-22-chiffre.json";
        check(SaveText.validName(plain), "save: the name of a plain export");
        check(SaveText.validName(encrypted), "save: the name of an encrypted export");
        check(SaveText.validName("a".repeat(100) + ".json") && !SaveText.validName("a".repeat(101) + ".json"), "save: 100 characters before .json at most");
        for (String bad : new String[]{"../x.json", "a/b.json", "a\\b.json", "x.txt", ".json", "-x.json", "a..b.json", "a b.json", "x.json\n", ""}) {
            check(!SaveText.validName(bad), "save: refused name [" + bad.replace("\n", "\\n") + "]");
        }
        check(!SaveText.validName(null), "save: no name");

        eq("{\"a\":1}", new String(SaveText.encode(plain, "{\"a\":1}"), StandardCharsets.UTF_8), "save: the text is what gets written");
        String accents = "\u00e9\u20ac\uD83D\uDE00";
        byte[] encoded = SaveText.encode(encrypted, accents);
        check(encoded != null && encoded.length == 9 && accents.equals(new String(encoded, StandardCharsets.UTF_8)), "save: UTF-8 (2 + 3 + 4 bytes)");
        check(SaveText.encode(plain, "a".repeat(1048576)) != null, "save: exactly 1 MiB is accepted");
        check(SaveText.encode(plain, "a".repeat(1048577)) == null, "save: 1 MiB and a byte is refused");
        check(SaveText.encode(plain, "\u00e9".repeat(524288)) != null, "save: 524288 two-byte characters make exactly 1 MiB");
        check(SaveText.encode(plain, "\u00e9".repeat(524289)) == null, "save: the limit counts bytes, not characters");
        check(SaveText.encode(plain, null) == null, "save: no text");
        check(SaveText.encode("../x.json", "{}") == null && SaveText.encode(null, "{}") == null, "save: a refused name refuses the text too");

        // The glue cannot run here: the page calls it by its signature, and what it is given must be checked before the storage is touched.
        String dir = System.getProperty("arr411.src");
        check(dir != null, "arr411.src is set (run test.ps1)");
        if (dir == null) return;
        String bridge = sourceText(new File(dir, "SpaBridge.java"));
        Matcher m = Pattern.compile("@JavascriptInterface\\s+public\\s+boolean\\s+saveText\\(String name, String text\\)\\s*\\{").matcher(bridge);
        boolean found = m.find();
        check(found, "save: the page can call SpaBridge.saveText(name, text), which answers true or false");
        if (!found) return;
        int next = bridge.indexOf("@JavascriptInterface", m.end());
        String body = bridge.substring(m.end(), next < 0 ? bridge.length() : next);
        int checked = body.indexOf("SaveText.encode(name, text)");
        int stored = body.indexOf("MediaStore.Downloads.EXTERNAL_CONTENT_URI");
        check(checked >= 0 && stored > checked, "save: the name and the text are checked before anything is stored");
        check(body.contains("resolver.delete(file"), "save: a file that could not be written is removed again");
        check(Pattern.compile("Diag\\.problem\\(\"saveText\",[^;]*,\\s*null\\);").matcher(body).find(), "save: the log gets the class of the error, never its message");
    }

    // ---------- Cookie jars ----------

    /** The header fields of a response, as HttpURLConnection.getHeaderFields() gives them (the status line has a null name). */
    static Map<String, List<String>> response(String... setCookies) {
        Map<String, List<String>> fields = new LinkedHashMap<>();
        fields.put(null, Collections.singletonList("HTTP/1.1 200 OK"));
        fields.put("Content-Length", Collections.singletonList("0"));
        if (setCookies.length > 0) fields.put("Set-Cookie", Arrays.asList(setCookies));
        return fields;
    }

    static void cookieJars() throws Exception {
        final String sid = "SID=abc123; HttpOnly; SameSite=Strict; path=/";
        CookieJars jars = new CookieJars();
        URL login = url("http://192.168.1.10:8080/api/v2/auth/login");
        eq(null, jars.header(login), "no cookie before the login");
        jars.keep(login, response(sid));

        // The session goes back to its server, whatever the request looks like (qbitIndex sends a raw | in a hash list).
        eq("SID=abc123", jars.header(url("http://192.168.1.10:8080/api/v2/torrents/info")), "the session cookie goes back to its server");
        eq("SID=abc123", jars.header(url("http://192.168.1.10:8080/api/v2/torrents/info?hashes=aaa|bbb|ccc")), "with the raw | of a hash list");
        eq("SID=abc123", jars.header(url("http://192.168.1.10:8080/api/v2/torrents/info?filter={a b}&x=\"y\"#z")), "with the other characters a URI refuses");
        eq("SID=abc123", jars.header(url("http://192.168.1.10:8080/a b/{x}%zz/y?q=1")), "with a path a URI refuses");
        eq("SID=abc123", jars.header(url("http://192.168.1.10:8080")), "with no path");
        eq("SID=abc123", jars.header(url("http://user:pw@192.168.1.10:8080/api/v2/app/version")), "with a login in the address");

        // Nothing else can carry it.
        eq(null, jars.header(url("http://192.168.1.10:9090/api/v2/torrents/info")), "another port is another server");
        eq(null, jars.header(url("https://192.168.1.10:8080/api/v2/torrents/info")), "https is another server");
        eq(null, jars.header(url("http://192.168.1.11:8080/api/v2/torrents/info")), "another host is another server");
        eq(null, jars.header(url("http://nas.evil.example:8080/")), "an unrelated host");
        eq(null, jars.header(url("https://c411.org/")), "the C411 site never gets it");
        eq(null, jars.header(url("https://api.c411.org/torrent.torrent")), "nor does a subdomain of it");
        check(new CookieJars().header(login) == null, "another jar knows nothing of it");

        // The same server, typed differently.
        eq("http://nas:8080", CookieJars.origin(url("http://NAS:8080/x?y")), "the host is lower case in the origin");
        eq("https://nas", CookieJars.origin(url("https://Nas/x")), "no port in the origin when there is none");
        jars = new CookieJars();
        jars.keep(url("http://nas:8080/api/v2/auth/login"), response(sid));
        eq("SID=abc123", jars.header(url("http://NAS:8080/api/v2/torrents/info")), "the case of the host does not matter");

        // The servers the user may type.
        for (String host : new String[]{"192.168.1.10", "192.168.1.10:8080", "nas", "nas:8080", "nas.local:8080", "qb.example.com", "qb.example.com:8443",
                "[fd00::1]:8080", "localhost:8080", "10.0.0.2:80"}) {
            CookieJars one = new CookieJars();
            String scheme = host.contains("example") ? "https" : "http";
            one.keep(url(scheme + "://" + host + "/api/v2/auth/login"), response(sid));
            eq("SID=abc123", one.header(url(scheme + "://" + host + "/api/v2/torrents/info?hashes=a|b")), "round trip with " + host);
        }

        // A reverse proxy that scopes the cookie to a folder.
        jars = new CookieJars();
        jars.keep(url("http://nas:8080/qbt/api/v2/auth/login"), response("SID=p; path=/qbt"));
        eq("SID=p", jars.header(url("http://nas:8080/qbt/api/v2/torrents/info?hashes=a|b")), "a cookie scoped to a folder goes to that folder");
        eq(null, jars.header(url("http://nas:8080/other/api")), "and only there");

        // A new login replaces the cookie, a response without cookie changes nothing, a logout removes it.
        jars = new CookieJars();
        jars.keep(login, response(sid));
        jars.keep(login, response("SID=second; path=/"));
        eq("SID=second", jars.header(login), "a new login replaces the cookie");
        jars.keep(login, response());
        eq("SID=second", jars.header(login), "a response without cookie changes nothing");
        jars.keep(login, response("SID=; Max-Age=0; path=/"));
        eq(null, jars.header(login), "a logout removes the cookie");

        jars = new CookieJars();
        jars.keep(login, response("SID=a; path=/", "theme=dark; path=/"));
        String both = jars.header(login);
        check(both != null && both.contains("SID=a") && both.contains("theme=dark") && both.contains("; ") && !both.contains("\n"), "two cookies make one header: " + both);

        // Unusable addresses and responses cost nothing.
        jars = new CookieJars();
        jars.keep(url("http://my host/x"), response(sid));
        eq(null, jars.header(url("http://my host/x")), "an address a URI cannot hold gets no cookie");
        jars.keep(login, null);
        eq(null, jars.header(login), "no header fields");

        // The jars are bounded: a full table starts again, but a server that is already known never makes it start again.
        jars = new CookieJars();
        for (int i = 0; i < CookieJars.MAX_JARS; i++) jars.keep(url("http://server" + i + ":8080/api/v2/auth/login"), response(sid));
        jars.keep(url("http://server5:8080/api/v2/auth/login"), response("SID=again; path=/"));
        eq("SID=abc123", jars.header(url("http://server0:8080/x")), "a known server does not empty the table");
        eq("SID=again", jars.header(url("http://server5:8080/x")), "it only replaces its own cookie");
        jars.keep(url("http://newcomer:8080/api/v2/auth/login"), response(sid));
        eq(null, jars.header(url("http://server0:8080/x")), "a new server in a full table empties it");
        eq("SID=abc123", jars.header(url("http://newcomer:8080/x")), "and has its own cookie");

        URI uri = CookieJars.uri(url("http://h:8080/a b/{x}?q=|"));
        eq("h", uri.getHost(), "the host of the cookie address");
        eq(8080, uri.getPort(), "the port of the cookie address");
        eq("/a%20b/%7Bx%7D", uri.getRawPath(), "the path of the cookie address is quoted");
        eq(null, uri.getRawQuery(), "the query of the cookie address is left out");
        eq("/", CookieJars.uri(url("https://h")).getRawPath(), "an empty path is /");
    }

    // ---------- Bridge key ----------

    static void bridgeKey() {
        final String key = "0123456789abcdef0123456789abcdef";
        check(BridgeKey.matches(key, key), "the same key");
        check(BridgeKey.matches(key, new String(key.toCharArray())), "an equal copy of the key, not the same object");
        check(!BridgeKey.matches(key, "0123456789abcdef0123456789abcdee"), "a wrong key: the last character differs");
        check(!BridgeKey.matches(key, "f123456789abcdef0123456789abcdef"), "a wrong key: the first character differs");
        check(!BridgeKey.matches(key, key.toUpperCase()), "the case matters");
        check(!BridgeKey.matches(key, null), "no key given");
        check(!BridgeKey.matches(key, ""), "an empty key");
        check(!BridgeKey.matches(key, key.substring(0, 31)), "the beginning of the key is not enough");
        check(!BridgeKey.matches(key, key + "0"), "the key followed by another character");
        check(!BridgeKey.matches(key, key + key), "the key twice");
        check(!BridgeKey.matches(key, "0"), "a short key");
        check(!BridgeKey.matches(null, null), "a bridge without a key refuses everything (null)");
        check(!BridgeKey.matches("", ""), "a bridge without a key refuses everything (empty)");
        check(!BridgeKey.matches(null, key), "a bridge without a key refuses a key");
        check(!BridgeKey.matches("", "x"), "an empty expected key matches nothing");
        check(!BridgeKey.matches("a\u0000", "a"), "a missing character is not a zero character");

        String a = BridgeKey.create();
        String b = BridgeKey.create();
        check(a.matches("[0-9a-f]{32}"), "128 bits as 32 lowercase hexadecimal characters: " + a);
        check(!a.equals(b), "a new key every time");
        check(BridgeKey.matches(a, a) && !BridgeKey.matches(a, b), "a created key matches itself only");
    }

    /** The methods the page can call all start with the key check: that is what keeps a foreign frame out of the limits of the page. */
    static void bridgeMethods() throws Exception {
        String dir = System.getProperty("arr411.src");
        check(dir != null, "arr411.src is set (run test.ps1)");
        if (dir == null) return;
        String source = new String(Files.readAllBytes(new File(dir, "PageBridge.java").toPath()), StandardCharsets.UTF_8);
        Matcher m = Pattern.compile("@JavascriptInterface\\s+public\\s+\\w+\\s+(\\w+)\\(([^)]*)\\)\\s*\\{\\s*([^\\r\\n]*)").matcher(source);
        List<String> found = new ArrayList<>();
        while (m.find()) {
            found.add(m.group(1));
            check(m.group(2).startsWith("String k,"), "PageBridge." + m.group(1) + " takes the key first");
            eq("if (!keyOk(k)) return;", m.group(3).trim(), "PageBridge." + m.group(1) + " checks the key before anything else");
        }
        Collections.sort(found);
        eq(Arrays.asList("download", "fetchBytes", "saveFile", "send", "toast"), found, "the methods the shim calls");
    }

    // ---------- Update: helpers shared by the checks below ----------

    static final String H1 = "5fb29e9fd7f7e934d20f6845b595f324c39b75ff1bdb1040e962b4654ae8c4d3";
    static final String H2 = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";
    static final String EMPTY_SHA = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";
    static final String DOWNLOADS = "https://github.com/HugoMeyer/arr411-android/releases/download/";
    static final String LIVE_APK = DOWNLOADS + "v2.3.1/Arr411.apk";
    static final String OWN_PACKAGE = "dev.c411.qbittorrent";

    /** The answer of GitHub for the release 2.3.1, as the API gave it; null when the folder of the fixtures is not known. */
    static String liveRelease() throws IOException {
        String dir = System.getProperty("arr411.fixtures");
        check(dir != null, "arr411.fixtures is set (run test.ps1)");
        if (dir == null) return null;
        return new String(Files.readAllBytes(new File(dir, "release-2.3.1.json").toPath()), StandardCharsets.UTF_8);
    }

    static String sha256(byte[] data) throws Exception {
        StringBuilder sb = new StringBuilder();
        for (byte b : MessageDigest.getInstance("SHA-256").digest(data)) sb.append(String.format("%02x", b & 0xff));
        return sb.toString();
    }

    static Set<String> set(String... values) {
        return new TreeSet<>(Arrays.asList(values));
    }

    /** {@code text} with {@code from} replaced by {@code to}, once it is sure that {@code from} is there: a variant that changes nothing would test nothing. */
    static String swap(String text, String from, String to) {
        check(text.contains(from), "the text to change is there: " + from);
        return text.replace(from, to);
    }

    static String assetJson(String name, String url, String size, String digest) {
        return "{\"name\":\"" + name + "\",\"state\":\"uploaded\",\"size\":" + size + ",\"digest\":" + digest + ",\"browser_download_url\":\"" + url + "\"}";
    }

    static String goodApk(String tag) {
        return assetJson("Arr411.apk", DOWNLOADS + tag + "/Arr411.apk", "1000", "\"sha256:" + H1 + "\"");
    }

    static String goodSums(String tag) {
        return assetJson("SHA256SUMS.txt", DOWNLOADS + tag + "/SHA256SUMS.txt", "100", "null");
    }

    static String releaseJson(String tag, String body, String... assets) {
        return "{\"tag_name\":" + UpdateJson.quoteOrNull(tag) + ",\"draft\":false,\"prerelease\":false,\"body\":" + UpdateJson.quoteOrNull(body)
            + ",\"assets\":[" + String.join(",", assets) + "]}";
    }

    static UpdateRelease releaseOf(String tag) {
        return UpdateRelease.parse(releaseJson(tag, "Notes", goodApk(tag)));
    }

    // ---------- Update: versions ----------

    static String parts(String version) {
        int[] p = UpdateVersion.parts(version);
        return p == null ? null : Arrays.toString(p);
    }

    static String joined(int[] numbers) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < numbers.length; i++) sb.append(i > 0 ? "." : "").append(numbers[i]);
        return sb.toString();
    }

    static void updateVersions() {
        eq("[2, 3, 1]", parts("v2.3.1"), "a tag with its v");
        eq("[2, 3, 1]", parts("2.3.1"), "a version without the v");
        eq("[2, 3]", parts("V2.3"), "a capital V and two numbers");
        eq("[2]", parts("2"), "one number");
        eq("[1, 2, 3, 4]", parts("1.2.3.4"), "four numbers");
        eq("[2, 3, 1]", parts("v02.03.01"), "leading zeros are only zeros");
        eq("[999999999]", parts("999999999"), "nine digits fit");
        String[] notVersions = {null, "", "v", "V", "vv2.3", "1.2.3.4.5", "2..3", "2.3.", ".2", "2.3-beta", "2.3.1-rc1", "2.3.1+build", " 2.3", "2.3 ", "2. 3",
            "+2", "-1", "2.x", "x", "nightly", "latest", "1,2", "1_000", "1234567890", "2.1234567890", "\u0662.\u0663", "\uff12.\uff13", "0x10", "1e3", "v 2.3",
            "2.3\n", "\n2.3", "\u0000", "2.3.1\u0000", "2.3.1/../x", "1.2.3.4.5.6.7.8.9.10.11.12.13.14.15.16.17.18.19.20"};
        for (String bad : notVersions) {
            check(UpdateVersion.parts(bad) == null, "not a version: " + (bad == null ? "null" : bad.replace("\n", "\\n").replace("\u0000", "\\0")));
        }

        eq("2.3.1", UpdateVersion.normalize("v2.03.1"), "the v and the zeros go");
        eq("2.3.1", UpdateVersion.normalize("2.3.1"), "a normal version stays");
        eq("7", UpdateVersion.normalize("007"), "007 is 7");
        eq("0.0", UpdateVersion.normalize("0.0"), "zeros stay as numbers");
        eq(null, UpdateVersion.normalize("nightly"), "no version, no normal form");
        eq(null, UpdateVersion.normalize(null), "no text, no normal form");

        check(UpdateVersion.isNewer("v2.10.0", "2.9.9"), "numbers, not text: 2.10.0 is newer than 2.9.9");
        check(!UpdateVersion.isNewer("2.9.9", "2.10.0"), "and 2.9.9 is not newer than 2.10.0");
        check(UpdateVersion.isNewer("2.3.2", "2.3.1"), "a new patch");
        check(UpdateVersion.isNewer("2.4", "2.3.9"), "a new minor version");
        check(UpdateVersion.isNewer("3", "2.99.99"), "a new major version");
        check(!UpdateVersion.isNewer("2.3.1", "2.3.1"), "the same version is not an update");
        check(!UpdateVersion.isNewer("2.3", "2.3.0"), "2.3 is 2.3.0");
        check(!UpdateVersion.isNewer("2.3.0", "2.3"), "2.3.0 is 2.3");
        check(UpdateVersion.isNewer("2.3.0.1", "2.3"), "a fourth number counts");
        check(!UpdateVersion.isNewer("2.3.0.0", "2.3"), "zeros after the numbers do not count");
        check(!UpdateVersion.isNewer("2.3.0", "2.3.1"), "never a downgrade");
        check(!UpdateVersion.isNewer("1.9.9", "2.0.0"), "never a downgrade of the major version");
        check(!UpdateVersion.isNewer("nightly", "2.3.1"), "a tag that is not a version is never offered");
        check(!UpdateVersion.isNewer("2.3.2", "dev"), "nothing is newer than a version that cannot be read");
        check(!UpdateVersion.isNewer("2.3.2", null), "nothing is newer than no version");
        check(!UpdateVersion.isNewer(null, "2.3.1"), "no version is not newer");
        check(!UpdateVersion.isNewer(null, null), "no version at all");

        check(UpdateVersion.same("2.3", "v2.3.0"), "2.3 and v2.3.0 are the same version");
        check(!UpdateVersion.same("2.3", "2.3.1"), "2.3 and 2.3.1 are not");
        check(!UpdateVersion.same(null, "2.3"), "no version is not 2.3");
        check(!UpdateVersion.same("a", "a"), "two things that are not versions are not the same version");

        Random random = new Random(2311);
        int wrong = 0;
        for (int i = 0; i < 3000; i++) {
            int[] a = new int[1 + random.nextInt(4)];
            int[] b = new int[1 + random.nextInt(4)];
            for (int k = 0; k < a.length; k++) a[k] = random.nextInt(12);
            for (int k = 0; k < b.length; k++) b[k] = random.nextInt(12);
            int expected = 0;
            for (int k = 0; k < 4 && expected == 0; k++) expected = Integer.compare(k < a.length ? a[k] : 0, k < b.length ? b[k] : 0);
            String ta = (random.nextBoolean() ? "v" : "") + joined(a);
            String tb = joined(b);
            int compared = Integer.signum(UpdateVersion.compare(UpdateVersion.parts(ta), UpdateVersion.parts(tb)));
            if (compared != expected || UpdateVersion.isNewer(ta, tb) != (expected > 0) || UpdateVersion.same(ta, tb) != (expected == 0)) wrong++;
        }
        eq(0, wrong, "3000 random pairs of versions agree with a number by number comparison");
    }

    // ---------- Update: the JSON reader and writer ----------

    static String jsonError(String text) {
        try {
            UpdateJson.parse(text);
            return null;
        } catch (IllegalArgumentException e) {
            return e.getMessage();
        }
    }

    static String nested(int depth, boolean objects) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < depth; i++) sb.append(objects ? "{\"a\":" : "[");
        sb.append('1');
        for (int i = 0; i < depth; i++) sb.append(objects ? "}" : "]");
        return sb.toString();
    }

    static boolean safeToPaste(String quoted) {
        for (int i = 0; i < quoted.length(); i++) {
            char c = quoted.charAt(i);
            if (c < 0x20 || c == 0x2028 || c == 0x2029) return false;
            if (Character.isHighSurrogate(c)) {
                if (i + 1 >= quoted.length() || !Character.isLowSurrogate(quoted.charAt(i + 1))) return false;
                i++;
            } else if (Character.isLowSurrogate(c)) {
                return false;
            }
        }
        return true;
    }

    static void updateJson() {
        eq(Long.valueOf(0), UpdateJson.parse("0"), "json: zero");
        eq(Long.valueOf(-12), UpdateJson.parse(" \n-12\t"), "json: a negative integer between spaces");
        eq(Long.valueOf(0), UpdateJson.parse("-0"), "json: -0 is the integer 0");
        eq(Long.valueOf(Long.MAX_VALUE), UpdateJson.parse("9223372036854775807"), "json: the biggest long");
        eq(Long.valueOf(Long.MIN_VALUE), UpdateJson.parse("-9223372036854775808"), "json: the smallest long");
        check(UpdateJson.parse("9223372036854775808") instanceof Double, "json: an integer that is too big is a double, which no size check accepts");
        check(UpdateJson.parse("99999999999999999999") instanceof Double, "json: a huge integer is a double");
        eq(Double.valueOf(1000), UpdateJson.parse("1e3"), "json: an exponent makes a double");
        eq(Double.valueOf(-1.5), UpdateJson.parse("-1.5"), "json: a fraction");
        eq(Double.valueOf(0.25), UpdateJson.parse("2.5E-1"), "json: a capital E");
        eq(Double.valueOf(391402), UpdateJson.parse("391402.0"), "json: 391402.0 is a double, not a size");
        check(UpdateJson.parse("1" + "0".repeat(63)) instanceof Double, "json: 64 characters are the longest number");
        check(jsonError("1" + "0".repeat(64)) != null, "json: a number of 65 characters is refused");
        String[] badNumbers = {"01", "-01", "1.", ".5", "-.5", "1e", "1e+", "-", "--1", "+1", "0x1", "1.5.2", "Infinity", "-Infinity", "NaN", "1_000", "1,5", "\u0661"};
        for (String n : badNumbers) check(jsonError(n) != null, "json: not a number: " + n);

        eq(Boolean.TRUE, UpdateJson.parse("true"), "json: true");
        eq(Boolean.FALSE, UpdateJson.parse(" false "), "json: false");
        eq(null, UpdateJson.parse("null"), "json: null");
        String[] badWords = {"tru", "nul", "fals", "True", "NULL", "truee", "nulll", "undefined"};
        for (String w : badWords) check(jsonError(w) != null, "json: not a literal: " + w);

        eq("", UpdateJson.parse("\"\""), "json: the empty string");
        eq("a\"b\\c/d\b\f\n\r\t", UpdateJson.parse("\"a\\\"b\\\\c\\/d\\b\\f\\n\\r\\t\""), "json: every escape");
        eq("\u00e9", UpdateJson.parse("\"\\u00e9\""), "json: a unicode escape");
        eq("\u00e9", UpdateJson.parse("\"\\u00E9\""), "json: capital hexadecimal digits");
        eq("\uD83D\uDE00", UpdateJson.parse("\"\\ud83d\\ude00\""), "json: a pair of surrogates written as two escapes");
        eq("\uD83D", UpdateJson.parse("\"\\ud83d\""), "json: a lone surrogate is kept as it is");
        eq("\uD83D\uDE00", UpdateJson.parse("\"\uD83D\uDE00\""), "json: an emoji as it is");
        eq("\u0000", UpdateJson.parse("\"\\u0000\""), "json: an escaped zero character");
        eq("a\nb", UpdateJson.parse("\"a\nb\""), "json: a raw newline in a string is kept");
        eq("\u65e5\u672c\u8a9e", UpdateJson.parse("\"\u65e5\u672c\u8a9e\""), "json: characters outside ASCII");
        String[] badStrings = {"\"abc", "\"\\x\"", "\"\\u12\"", "\"\\u12G4\"", "\"\\", "\"\\u\"", "\"abc\\", "'abc'", "\"\\U00e9\""};
        for (String s : badStrings) check(jsonError(s) != null, "json: not a string: " + s);

        Object parsed = UpdateJson.parse("{\"a\":1,\"b\":[true,null,\"x\",{\"c\":[]}],\"d\":{}}");
        check(parsed instanceof Map, "json: an object is a map");
        Map<?, ?> map = (Map<?, ?>) parsed;
        eq(Arrays.asList("a", "b", "d"), new ArrayList<Object>(map.keySet()), "json: the keys keep their order");
        eq(Long.valueOf(1), map.get("a"), "json: a member");
        List<?> list = (List<?>) map.get("b");
        eq(4, list.size(), "json: an array has its items");
        eq(Boolean.TRUE, list.get(0), "json: an item that is true");
        eq(null, list.get(1), "json: an item that is null");
        eq("x", list.get(2), "json: an item that is a string");
        eq(Collections.singletonMap("c", new ArrayList<Object>()), list.get(3), "json: an object in an array, an empty array in it");
        eq(new LinkedHashMap<String, Object>(), map.get("d"), "json: an empty object");
        eq(new ArrayList<Object>(), UpdateJson.parse("[ ]"), "json: an empty array with a space");
        eq(Long.valueOf(2), ((Map<?, ?>) UpdateJson.parse("{\"a\":1,\"a\":2}")).get("a"), "json: with a duplicate key the last one wins");
        eq(Long.valueOf(1), ((Map<?, ?>) UpdateJson.parse("\uFEFF{\"a\":1}")).get("a"), "json: a byte order mark in front is skipped");
        check(jsonError("{\"a\":\uFEFF1}") != null, "json: a byte order mark inside is not");

        String[] notJson = {"", " ", "{", "[", "{\"a\"}", "{\"a\":}", "{\"a\":1,}", "[1,]", "[,1]", "[1 2]", "{\"a\":1 \"b\":2}", "{a:1}", "{'a':1}", "{,}", "{\"a\" 1}",
            "{} {}", "[] x", "1 2", "\"a\" \"b\"", "{\"a\":1}}", "[1]]", "\u0000", "/*c*/1", "// c\n1", "{\"a\":1}\u0000", "}", "]", ",", ":"};
        for (String bad : notJson) check(jsonError(bad) != null, "json: not JSON: " + bad.replace("\n", "\\n"));
        check(jsonError(null) != null, "json: no text is refused too");
        String leak = jsonError("{\"secret-token\":tru}") + jsonError("[1,2,\"secret-token\" x]") + jsonError("\"secret-token");
        check(!leak.contains("secret-token"), "json: the message of an error never repeats the text");

        check(jsonError(nested(32, false)) == null, "json: 32 nested arrays are read");
        check(jsonError(nested(33, false)) != null, "json: 33 nested arrays are refused");
        check(jsonError(nested(32, true)) == null, "json: 32 nested objects are read");
        check(jsonError(nested(33, true)) != null, "json: 33 nested objects are refused");
        check(jsonError("[".repeat(200000)) != null, "json: a very deep array ends with an error, not with a stack overflow");
        check(jsonError("{\"a\":".repeat(200000)) != null, "json: a very deep object ends with an error, not with a stack overflow");

        eq("\"abc\"", UpdateJson.quote("abc"), "quote: plain text");
        eq("\"\"", UpdateJson.quote(""), "quote: nothing");
        eq("\"a\\\"b\\\\c\"", UpdateJson.quote("a\"b\\c"), "quote: a quote and a backslash");
        eq("\"\\n\\r\\t\\b\\f\"", UpdateJson.quote("\n\r\t\b\f"), "quote: the short escapes");
        eq("\"\\u0000\\u001f\"", UpdateJson.quote("\u0000\u001f"), "quote: other control characters");
        eq("\"\\u2028\\u2029\"", UpdateJson.quote("\u2028\u2029"), "quote: the separators that end a line in a script");
        eq("\"\\ud83d\"", UpdateJson.quote("\uD83D"), "quote: a lone high surrogate");
        eq("\"\\ude00\"", UpdateJson.quote("\uDE00"), "quote: a lone low surrogate");
        eq("\"\\ud83dx\"", UpdateJson.quote("\uD83Dx"), "quote: a high surrogate that is not followed by a low one");
        eq("\"\\ude00\\ud83d\"", UpdateJson.quote("\uDE00\uD83D"), "quote: a low surrogate then a high one are two lone surrogates");
        eq("\"\uD83D\uDE00\"", UpdateJson.quote("\uD83D\uDE00"), "quote: a valid pair stays as it is");
        eq("\"\u00e9\u65e5\"", UpdateJson.quote("\u00e9\u65e5"), "quote: accents and other scripts stay as they are");
        eq("\"</script>\"", UpdateJson.quote("</script>"), "quote: nothing else is changed");
        eq("null", UpdateJson.quoteOrNull(null), "quote: no text is null");
        eq("\"x\"", UpdateJson.quoteOrNull("x"), "quote: a text is a literal");

        Random random = new Random(411);
        String pool = "ab \"\\/\n\r\t\b\f\u0000\u001f\u007f\u0080\u00e9\u2028\u2029\ud83d\ude00\ud83d\ude00\ud83d\ude00\ude00{}[]:,<>&'";
        int broken = 0;
        int unsafe = 0;
        for (int i = 0; i < 3000; i++) {
            StringBuilder sb = new StringBuilder();
            int length = random.nextInt(40);
            for (int k = 0; k < length; k++) sb.append(pool.charAt(random.nextInt(pool.length())));
            String text = sb.toString();
            String quoted = UpdateJson.quote(text);
            if (!text.equals(UpdateJson.parse(quoted))) broken++;
            if (!safeToPaste(quoted)) unsafe++;
        }
        eq(0, broken, "quote: 3000 random texts are read back as they were written");
        eq(0, unsafe, "quote: 3000 random texts give a literal that is safe to paste into a script");
    }

    // ---------- Update: what makes a download trustworthy ----------

    static String outcome(UpdateIntegrity.Expected e) {
        check((e.hash == null) != (e.error == null), "an outcome is a hash or an error, never both and never none");
        return e.hash != null ? e.hash : e.error;
    }

    static boolean refusedWith(UpdateIntegrity.Expected e, String fragment) {
        return e.hash == null && e.error != null && e.error.contains(fragment);
    }

    static void updateIntegrity() {
        eq("", UpdateIntegrity.hex(new byte[0]), "hex: nothing");
        eq("00ff7f80", UpdateIntegrity.hex(new byte[] {0, (byte) 0xff, 0x7f, (byte) 0x80}), "hex: lowercase, two digits a byte");
        StringBuilder every = new StringBuilder();
        byte[] all = new byte[256];
        for (int i = 0; i < 256; i++) {
            all[i] = (byte) i;
            every.append(String.format("%02x", i));
        }
        eq(every.toString(), UpdateIntegrity.hex(all), "hex: the 256 values of a byte");

        eq(H1, UpdateIntegrity.hexOrNull(H1), "hash: 64 digits");
        eq(H1, UpdateIntegrity.hexOrNull(H1.toUpperCase()), "hash: capitals become lowercase");
        String[] notHashes = {null, "", H1.substring(1), H1 + "0", "0" + H1, H1.substring(0, 63) + "g", " " + H1.substring(1), H1.substring(1) + " ",
            "0x" + H1.substring(2), "\u0660".repeat(64), "\uff41".repeat(64), H1.substring(0, 32) + "\n" + H1.substring(33)};
        for (String bad : notHashes) check(UpdateIntegrity.hexOrNull(bad) == null, "not a hash: " + (bad == null ? "null" : bad.replace("\n", "\\n")));

        eq(H1, UpdateIntegrity.parseDigest("sha256:" + H1), "digest: the form of the API");
        eq(H1, UpdateIntegrity.parseDigest("SHA256:" + H1.toUpperCase()), "digest: capitals");
        eq(H1, UpdateIntegrity.parseDigest("Sha256:" + H1), "digest: a prefix in mixed case");
        String[] notDigests = {null, "", "sha256:", "sha256:" + H1.substring(1), "sha256:" + H1 + "0", "sha1:" + H1.substring(0, 40), "md5:" + H1.substring(0, 32), H1,
            " sha256:" + H1, "sha256:" + H1 + " ", "sha256: " + H1.substring(1), "sha256=" + H1, "sha512:" + H1 + H1, "sha256:sha256:" + H1.substring(7)};
        for (String bad : notDigests) check(UpdateIntegrity.parseDigest(bad) == null, "not a digest: " + bad);

        String name = "Arr411.apk";
        eq(set(H1), UpdateIntegrity.sumsFor(H1 + "  Arr411.apk\n", name), "sums: two spaces, as sha256sum writes it");
        eq(set(H1), UpdateIntegrity.sumsFor(H1 + " *Arr411.apk\n", name), "sums: the mark of a binary file");
        eq(set(H1), UpdateIntegrity.sumsFor(H1 + "  ./Arr411.apk", name), "sums: ./ in front and no end of line");
        eq(set(H1), UpdateIntegrity.sumsFor(H1 + "\tArr411.apk\n", name), "sums: a tab");
        eq(set(H1), UpdateIntegrity.sumsFor(H1 + " Arr411.apk\n", name), "sums: one space");
        eq(set(H1), UpdateIntegrity.sumsFor(H1 + "  Arr411.apk  \n", name), "sums: spaces after the name");
        eq(set(H1), UpdateIntegrity.sumsFor(H2 + "  other.txt\r\n" + H1 + "  Arr411.apk\r\n", name), "sums: lines that end with CRLF");
        eq(set(H1), UpdateIntegrity.sumsFor(H2 + "  other.txt\r" + H1 + "  Arr411.apk\r", name), "sums: lines that end with CR");
        eq(set(H1), UpdateIntegrity.sumsFor("\uFEFF" + H1 + "  Arr411.apk\n", name), "sums: a byte order mark");
        eq(set(H1), UpdateIntegrity.sumsFor(H1.toUpperCase() + "  Arr411.apk\n", name), "sums: a hash in capitals");
        eq(set(H1), UpdateIntegrity.sumsFor("# the sums\n\nnot a hash  Arr411.apk\n" + H1 + "  Arr411.apk\nzzzz\n", name), "sums: lines that are not sums are ignored");
        eq(set(H1), UpdateIntegrity.sumsFor(H1 + "  Arr411.apk\n" + H1 + "  Arr411.apk\n", name), "sums: the same hash twice is one hash");
        eq(set(H1, H2), UpdateIntegrity.sumsFor(H1 + "  Arr411.apk\n" + H2 + "  Arr411.apk\n", name), "sums: two hashes for one name are both reported");
        eq(set(H2), UpdateIntegrity.sumsFor(H1 + "  Arr411.apk.sig\n" + H2 + "  Arr411.apk\n", name), "sums: only the line of the file");
        String[] otherNames = {"Arr411.apk.sig", "arr411.apk", "dir/Arr411.apk", "Arr411.apkx", "xArr411.apk", "Arr411.apk\\", "Arr411.ap", "", " ", "*", "./",
            "../Arr411.apk", "Arr411.apk Arr411.apk"};
        for (String other : otherNames) eq(set(), UpdateIntegrity.sumsFor(H1 + "  " + other + "\n", name), "sums: another name is not the file: [" + other + "]");
        eq(set(), UpdateIntegrity.sumsFor(H1 + "xArr411.apk", name), "sums: a name that sticks to the hash");
        eq(set(), UpdateIntegrity.sumsFor("0" + H1 + "  Arr411.apk", name), "sums: 65 digits are not a hash");
        eq(set(), UpdateIntegrity.sumsFor(H1.substring(1) + "  Arr411.apk", name), "sums: 63 digits are not a hash");
        eq(set(), UpdateIntegrity.sumsFor(H1 + "  ", name), "sums: a hash and no name");
        eq(set(), UpdateIntegrity.sumsFor(null, name), "sums: no text");
        eq(set(), UpdateIntegrity.sumsFor("", name), "sums: an empty text");
        eq(set(), UpdateIntegrity.sumsFor(H1 + "  Arr411.apk", null), "sums: no name");
        StringBuilder many = new StringBuilder();
        for (int i = 0; i < 20000; i++) many.append(H2).append("  file").append(i).append(".bin\n");
        eq(set(H1), UpdateIntegrity.sumsFor(many + H1 + "  Arr411.apk\n", name), "sums: the line of the file at the end of 20000 lines");

        String sums = H1 + "  Arr411.apk\n";
        eq(H1, outcome(UpdateIntegrity.expected(H1, false, false, null, name)), "expected: the digest of the API alone");
        eq(H1, outcome(UpdateIntegrity.expected(null, false, true, sums, name)), "expected: SHA256SUMS.txt alone");
        eq(H1, outcome(UpdateIntegrity.expected(H1, false, true, sums, name)), "expected: both agree");
        eq(H1, outcome(UpdateIntegrity.expected(H1, false, false, H2 + "  Arr411.apk\n", name)), "expected: a text of sums is not read when the release has none");
        check(refusedWith(UpdateIntegrity.expected(H1, false, true, H2 + "  Arr411.apk\n", name), "concordent"), "expected: both disagree, nothing is installed");
        check(refusedWith(UpdateIntegrity.expected(null, false, false, null, name), "Aucune empreinte"), "expected: no source at all");
        check(refusedWith(UpdateIntegrity.expected(null, false, false, sums, name), "Aucune empreinte"), "expected: no source, a text that is not a source");
        check(refusedWith(UpdateIntegrity.expected(null, true, false, null, name), "illisible"), "expected: a digest that is not a SHA-256");
        check(refusedWith(UpdateIntegrity.expected(H1, true, false, null, name), "illisible"), "expected: an unreadable digest is not made good by another hash");
        check(refusedWith(UpdateIntegrity.expected(null, true, true, sums, name), "illisible"), "expected: nor by the sums");
        check(refusedWith(UpdateIntegrity.expected(H1, false, true, null, name), "SHA256SUMS.txt"), "expected: sums that cannot be read refuse, even with a digest");
        check(refusedWith(UpdateIntegrity.expected(H1, false, true, "", name), "ne contient pas"), "expected: empty sums");
        check(refusedWith(UpdateIntegrity.expected(H1, false, true, H1 + "  other.apk\n", name), "Arr411.apk"), "expected: sums without a line for the file");
        check(refusedWith(UpdateIntegrity.expected(null, false, true, sums + H2 + "  Arr411.apk\n", name), "plusieurs"), "expected: sums that give two hashes");
        check(refusedWith(UpdateIntegrity.expected(H1, false, true, sums + H2 + "  Arr411.apk\n", name), "plusieurs"), "expected: two hashes in the sums, even if one is the digest");

        eq(null, UpdateIntegrity.verify(1000, 1000, H1, H1), "verify: the file is what was announced");
        eq(null, UpdateIntegrity.verify(0, 0, EMPTY_SHA, EMPTY_SHA), "verify: nothing is what nothing is");
        check(UpdateIntegrity.verify(1000, 999, H1, H1).contains("Taille"), "verify: a file that is too short");
        check(UpdateIntegrity.verify(1000, 1001, H1, H1).contains("Taille"), "verify: a file that is too long");
        check(UpdateIntegrity.verify(1000, 1000, H2, H1).contains("Empreinte"), "verify: another hash");
        check(UpdateIntegrity.verify(1000, 1000, null, H1).contains("Empreinte"), "verify: no hash computed");
        check(UpdateIntegrity.verify(1000, 1000, H1, null).contains("Aucune empreinte"), "verify: no hash expected");
        check(UpdateIntegrity.verify(1000, 999, H2, H1).contains("Taille"), "verify: the size is told first");

        eq(52428800L, UpdateIntegrity.MAX_APK_BYTES, "size: 50 MB at most");
        check(!UpdateIntegrity.sizeOk(0), "size: nothing is not a file");
        check(!UpdateIntegrity.sizeOk(-1), "size: a negative size");
        check(UpdateIntegrity.sizeOk(1), "size: one byte");
        check(UpdateIntegrity.sizeOk(391402), "size: the real one");
        check(UpdateIntegrity.sizeOk(50L * 1024 * 1024), "size: exactly 50 MB");
        check(!UpdateIntegrity.sizeOk(50L * 1024 * 1024 + 1), "size: one byte more");
        check(!UpdateIntegrity.sizeOk(Long.MAX_VALUE), "size: the biggest long");
        check(!UpdateIntegrity.sizeOk(Long.MIN_VALUE), "size: the smallest long");

        check(UpdateIntegrity.declaredSizeOk(391402, 391402), "declared: the length that the server announces is the size of the release");
        check(UpdateIntegrity.declaredSizeOk(-1, 391402), "declared: a server that does not announce the length");
        check(UpdateIntegrity.declaredSizeOk(Long.MIN_VALUE, 5), "declared: any negative length means unknown");
        check(!UpdateIntegrity.declaredSizeOk(391401, 391402) && !UpdateIntegrity.declaredSizeOk(391403, 391402), "declared: a length that is not the size is refused");
        check(!UpdateIntegrity.declaredSizeOk(0, 391402), "declared: an empty answer is not the file");
        check(!UpdateIntegrity.declaredSizeOk(Long.MAX_VALUE, 391402), "declared: the biggest length is refused");

        long mb = 1024L * 1024;
        check(UpdateIntegrity.hasRoom(2 * 391402 + 8 * mb, 391402), "room: exactly twice the file and 8 MB");
        check(!UpdateIntegrity.hasRoom(2 * 391402 + 8 * mb - 1, 391402), "room: one byte short");
        check(!UpdateIntegrity.hasRoom(0, 1), "room: no free space");
        check(!UpdateIntegrity.hasRoom(-1, 0), "room: a negative amount of free space");
        check(UpdateIntegrity.hasRoom(8 * mb, 0), "room: 8 MB for a file of nothing");
        check(UpdateIntegrity.hasRoom(Long.MAX_VALUE, UpdateIntegrity.MAX_APK_BYTES), "room: the biggest file that is allowed, with plenty of space");
        check(!UpdateIntegrity.hasRoom(Long.MAX_VALUE, UpdateIntegrity.MAX_APK_BYTES + 1), "room: a file that is too big is never held");
        check(!UpdateIntegrity.hasRoom(Long.MAX_VALUE, -1), "room: a negative size");
    }

    // ---------- Update: the copy that hashes ----------

    /** Records the progress, and asks to stop after a number of chunks. */
    static class Spy implements HashedCopy.Listener {
        final List<Long> seen = new ArrayList<>();
        int stopAfter = Integer.MAX_VALUE;

        @Override public void progress(long copied) {
            seen.add(copied);
        }

        @Override public boolean cancelled() {
            return seen.size() >= stopAfter;
        }
    }

    /** Says nothing for a number of reads, then gives the data. */
    static class Hiccup extends InputStream {
        final InputStream inner;
        int zeros;

        Hiccup(byte[] data, int zeros) {
            this.inner = new ByteArrayInputStream(data);
            this.zeros = zeros;
        }

        @Override public int read() throws IOException {
            return inner.read();
        }

        @Override public int read(byte[] b, int off, int len) throws IOException {
            if (zeros > 0) {
                zeros--;
                return 0;
            }
            return inner.read(b, off, len);
        }
    }

    static class WatchedIn extends ByteArrayInputStream {
        boolean closed;

        WatchedIn(byte[] data) {
            super(data);
        }

        @Override public void close() throws IOException {
            closed = true;
            super.close();
        }
    }

    static class WatchedOut extends ByteArrayOutputStream {
        boolean closed;
        boolean flushed;

        @Override public void close() throws IOException {
            closed = true;
            super.close();
        }

        @Override public void flush() throws IOException {
            flushed = true;
            super.flush();
        }
    }

    static String copyOutcome(InputStream in, OutputStream out, long max, HashedCopy.Listener listener) {
        try {
            HashedCopy.copy(in, out, max, listener);
            return "ok";
        } catch (HashedCopy.LimitException e) {
            return "limit";
        } catch (HashedCopy.CancelledException e) {
            return "cancelled";
        } catch (IOException e) {
            return "io:" + e.getMessage();
        }
    }

    static byte[] pattern(int size) {
        byte[] out = new byte[size];
        for (int i = 0; i < size; i++) out[i] = (byte) (i * 31 + 7);
        return out;
    }

    static void hashedCopy() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        HashedCopy.Result r = HashedCopy.copy(new ByteArrayInputStream("abc".getBytes(StandardCharsets.US_ASCII)), out, 100, null);
        eq(3L, r.bytes, "copy: three bytes");
        eq(H2, r.sha256, "copy: the SHA-256 of abc");
        eq("abc", out.toString("US-ASCII"), "copy: the bytes are written");

        out = new ByteArrayOutputStream();
        r = HashedCopy.copy(new ByteArrayInputStream(new byte[0]), out, 0, null);
        eq(0L, r.bytes, "copy: nothing, no byte");
        eq(EMPTY_SHA, r.sha256, "copy: the SHA-256 of nothing");
        eq(0, out.size(), "copy: nothing is written");

        byte[] data = sample(100000);
        String expected = sha256(data);
        for (int step : new int[] {1, 7, 100, 4096, 32768, 40000, 100000}) {
            out = new ByteArrayOutputStream();
            r = HashedCopy.copy(new Dribble(data, step), out, data.length, null);
            check(r.bytes == data.length && expected.equals(r.sha256) && Arrays.equals(data, out.toByteArray()), "copy: " + data.length + " bytes given " + step + " at a time");
        }
        r = HashedCopy.copy(new Dribble(data, 999), null, data.length, null);
        check(r.bytes == data.length && expected.equals(r.sha256), "copy: hash only, with no output");
        r = HashedCopy.copy(new ByteArrayInputStream("abc".getBytes(StandardCharsets.US_ASCII)), null, Long.MAX_VALUE, null);
        eq(H2, r.sha256, "copy: no ceiling in practice");

        for (int size : new int[] {1, 32767, 32768, 32769, 65535, 65536, 65537}) {
            byte[] bytes = pattern(size);
            out = new ByteArrayOutputStream();
            r = HashedCopy.copy(new ByteArrayInputStream(bytes), out, size, null);
            check(r.bytes == size && sha256(bytes).equals(r.sha256) && Arrays.equals(bytes, out.toByteArray()), "copy: exactly the ceiling passes: " + size);
            out = new ByteArrayOutputStream();
            r = HashedCopy.copy(new ByteArrayInputStream(bytes), out, size + 1L, null);
            check(r.bytes == size && sha256(bytes).equals(r.sha256), "copy: under the ceiling passes: " + size);
            out = new ByteArrayOutputStream();
            eq("limit", copyOutcome(new ByteArrayInputStream(bytes), out, size - 1L, null), "copy: one byte over the ceiling: " + size);
            check(out.size() <= size - 1, "copy: nothing past the ceiling is written: " + size);
            out = new ByteArrayOutputStream();
            eq("limit", copyOutcome(new Dribble(bytes, 1), out, size - 1L, null), "copy: one byte over the ceiling, one byte at a time: " + size);
            eq(size - 1, out.size(), "copy: the bytes under the ceiling are written, and no more: " + size);
        }
        out = new ByteArrayOutputStream();
        eq("limit", copyOutcome(new ByteArrayInputStream(pattern(1000000)), out, 1000, null), "copy: a server that sends much more than it announced");
        check(out.size() <= 1000, "copy: and nothing more than the ceiling is written");
        out = new ByteArrayOutputStream();
        eq("limit", copyOutcome(new ByteArrayInputStream(new byte[1]), out, 0, null), "copy: a ceiling of nothing and one byte");
        eq(0, out.size(), "copy: nothing is written");
        eq("ok", copyOutcome(new ByteArrayInputStream(new byte[0]), new ByteArrayOutputStream(), 0, null), "copy: a ceiling of nothing and nothing");

        Spy spy = new Spy();
        spy.stopAfter = 3;
        out = new ByteArrayOutputStream();
        Dribble ten = new Dribble(data, 10);
        eq("cancelled", copyOutcome(ten, out, data.length, spy), "copy: the listener stops the copy");
        eq(30, out.size(), "copy: after three chunks of ten bytes");
        eq(Arrays.asList(10L, 20L, 30L), spy.seen, "copy: the progress was told after each chunk");
        spy = new Spy();
        spy.stopAfter = 0;
        out = new ByteArrayOutputStream();
        ten = new Dribble(data, 10);
        eq("cancelled", copyOutcome(ten, out, data.length, spy), "copy: stopped before the first read");
        check(out.size() == 0 && ten.pos == 0, "copy: nothing was read nor written");

        spy = new Spy();
        HashedCopy.copy(new Dribble(data, 4096), new ByteArrayOutputStream(), data.length, spy);
        boolean increasing = true;
        for (int i = 1; i < spy.seen.size(); i++) increasing &= spy.seen.get(i) > spy.seen.get(i - 1);
        check(increasing && spy.seen.get(spy.seen.size() - 1) == data.length, "copy: the progress only grows and ends at the size");
        eq(25, spy.seen.size(), "copy: one report a chunk");

        eq("ok", copyOutcome(new Hiccup(data, 99), new ByteArrayOutputStream(), data.length, null), "copy: 99 empty reads in a row are patience");
        eq("io:The stream gives nothing", copyOutcome(new Hiccup(data, 100), new ByteArrayOutputStream(), data.length, null), "copy: 100 empty reads in a row are not");
        eq("io:The stream gives nothing", copyOutcome(new Hiccup(data, Integer.MAX_VALUE), new ByteArrayOutputStream(), data.length, null), "copy: a stream that never gives anything ends");

        InputStream failing = new InputStream() {
            int calls;

            @Override public int read() throws IOException {
                throw new IOException("boom");
            }

            @Override public int read(byte[] b, int off, int len) throws IOException {
                if (calls++ == 0) {
                    b[off] = 1;
                    return 1;
                }
                throw new IOException("boom");
            }
        };
        eq("io:boom", copyOutcome(failing, new ByteArrayOutputStream(), 100, null), "copy: an error of the input goes up");
        OutputStream broken = new OutputStream() {
            @Override public void write(int b) throws IOException {
                throw new IOException("disk");
            }
        };
        eq("io:disk", copyOutcome(new ByteArrayInputStream(new byte[10]), broken, 100, null), "copy: an error of the output goes up");

        try {
            HashedCopy.copy(new ByteArrayInputStream(new byte[0]), null, -1, null);
            check(false, "copy: a negative ceiling is refused");
        } catch (IllegalArgumentException expectedError) {
            check(true, "copy: a negative ceiling is refused");
        }

        WatchedIn in = new WatchedIn(data);
        WatchedOut watched = new WatchedOut();
        HashedCopy.copy(in, watched, data.length, null);
        check(!in.closed && !watched.closed && !watched.flushed, "copy: neither stream is closed or flushed, that is up to the caller");
    }

    // ---------- Update: where the update may come from ----------

    static boolean allowed(String address) throws Exception {
        return UpdateHosts.isAllowed(url(address));
    }

    static String hop(String from, int status, String location, int followed) throws Exception {
        UpdateHosts.Hop h = UpdateHosts.next(url(from), status, location, followed);
        check((h.url == null) != (h.refusal == null), "a hop goes somewhere or is refused, never both and never none");
        return h.url != null ? "go:" + h.url : "no:" + h.refusal;
    }

    static String shortened(String text) {
        return text == null ? "null" : text.length() > 100 ? text.substring(0, 100) + "..." : text;
    }

    static void updateHosts() throws Exception {
        eq("https://api.github.com/repos/HugoMeyer/arr411-android/releases/latest", UpdateHosts.API_LATEST, "hosts: the address of the API");
        eq("https://github.com/HugoMeyer/arr411-android/releases", UpdateHosts.RELEASES_PAGE, "hosts: the page of the releases");
        eq(UpdateHosts.RELEASES_PAGE + "/tag/", UpdateHosts.TAG_PAGE, "hosts: the page of a tag");
        check(allowed(UpdateHosts.API_LATEST) && allowed(UpdateHosts.RELEASES_PAGE) && allowed(LIVE_APK), "hosts: the addresses of the app are allowed");

        String[] good = {"https://api.github.com/repos/HugoMeyer/arr411-android/releases/latest", LIVE_APK,
            "https://objects.githubusercontent.com/github-production-release-asset-2e65be/1/2?X-Amz-Signature=abc&X-Amz-Expires=300",
            "https://release-assets.githubusercontent.com/github-production-release-asset/1/2?sp=r&sig=abc",
            "https://GitHub.com/x", "https://API.GITHUB.COM/x", "https://github.com:443/x", "HTTPS://github.com/x"};
        for (String ok : good) check(allowed(ok), "hosts: allowed: " + ok);

        String[] bad = {"http://github.com/x", "http://api.github.com/x", "ftp://github.com/x", "file:///etc/passwd",
            "https://github.com.evil.com/x", "https://evilgithub.com/x", "https://xgithub.com/x", "https://evil.com/github.com", "https://evil.com/?https://github.com",
            "https://github.com@evil.com/x", "https://evil.com@github.com/x", "https://user:secret@github.com/x", "https://:@github.com/x",
            "https://github.com:8443/x", "https://github.com:80/x", "https://github.com:0/x", "https://github.com./x", "https://api.github.com./x",
            "https://raw.githubusercontent.com/x", "https://githubusercontent.com/x", "https://camo.githubusercontent.com/x", "https://sub.github.com/x",
            "https://gist.github.com/x", "https://uploads.github.com/x", "https://codeload.github.com/x", "https://www.github.com/x",
            "https://127.0.0.1/x", "https://[::1]/x", "https://192.168.1.10/x", "https://10.0.0.1/x", "https://localhost/x", "https://0x7f.1/x",
            "https://github.com/a b", "https://github.com/a\\b", "https://github.com/a\u0001b", "https://github.com/a\u007fb", "https://github.com/a\tb"};
        for (String no : bad) check(!allowed(no), "hosts: refused: " + no.replace("\t", "\\t"));
        check(!UpdateHosts.isAllowed(null), "hosts: no address is not allowed");
        String head = "https://github.com/";
        check(allowed(head + "a".repeat(2048 - head.length())), "hosts: an address of 2048 characters");
        check(!allowed(head + "a".repeat(2049 - head.length())), "hosts: an address of 2049 characters");

        String[] assets = {LIVE_APK, DOWNLOADS + "v2.3.2/SHA256SUMS.txt", DOWNLOADS + "v9.9.9/Arr411.apk",
            "https://github.com/hugomeyer/ARR411-android/releases/DOWNLOAD/v2.3.1/Arr411.apk", "https://GitHub.com/HugoMeyer/arr411-android/releases/download/v1/Arr411.apk"};
        for (String ok : assets) check(UpdateHosts.isAssetUrl(ok), "hosts: the address of a file of a release: " + ok);
        String[] notAssets = {"https://github.com/Evil/arr411-android/releases/download/v1/Arr411.apk", "https://github.com/HugoMeyer/other/releases/download/v1/Arr411.apk",
            "https://github.com/HugoMeyer/arr411-android-evil/releases/download/v1/Arr411.apk", "https://github.com/HugoMeyer2/arr411-android/releases/download/v1/Arr411.apk",
            "https://github.com/HugoMeyer/arr411-android/releases/latest/download/Arr411.apk", "https://github.com/HugoMeyer/arr411-android/archive/v1.zip",
            DOWNLOADS, DOWNLOADS.substring(0, DOWNLOADS.length() - 1),
            LIVE_APK + "?", LIVE_APK + "?x=1", LIVE_APK + "#", LIVE_APK + "#frag",
            DOWNLOADS + "v2.3.1/../v2.3.0/Arr411.apk", DOWNLOADS + "v2.3.1/./Arr411.apk", DOWNLOADS + "v2.3.1/..", DOWNLOADS + "v2.3.1/.",
            DOWNLOADS + "v2.3.1/%2e%2e/Arr411.apk", DOWNLOADS + "v2.3.1/%2E%2E/Arr411.apk", DOWNLOADS + "v2.3.1%2FArr411.apk", DOWNLOADS + "v2.3.1%2fArr411.apk",
            DOWNLOADS + "v2.3.1%5cArr411.apk", DOWNLOADS + "v2.3.1%5CArr411.apk", DOWNLOADS + "v2.3.1//Arr411.apk",
            "https://api.github.com/HugoMeyer/arr411-android/releases/download/v1/Arr411.apk",
            "https://objects.githubusercontent.com/HugoMeyer/arr411-android/releases/download/v1/Arr411.apk",
            "https://evil.com/HugoMeyer/arr411-android/releases/download/v1/Arr411.apk", "http://github.com/HugoMeyer/arr411-android/releases/download/v1/Arr411.apk",
            "https://github.com:8443/HugoMeyer/arr411-android/releases/download/v1/Arr411.apk", "https://user@github.com/HugoMeyer/arr411-android/releases/download/v1/Arr411.apk",
            "https://github.com./HugoMeyer/arr411-android/releases/download/v1/Arr411.apk",
            null, "", "not a url", "/HugoMeyer/arr411-android/releases/download/v1/Arr411.apk", DOWNLOADS + "a".repeat(2100)};
        for (String no : notAssets) check(!UpdateHosts.isAssetUrl(no), "hosts: not the address of a file of a release: " + shortened(no));

        String cdn = "https://release-assets.githubusercontent.com/github-production-release-asset/1/2?sp=r&sig=SECRET";
        for (int status : new int[] {301, 302, 303, 307, 308}) eq("go:" + cdn, hop(LIVE_APK, status, cdn, 0), "hosts: the redirect " + status + " to the CDN");
        eq("go:https://objects.githubusercontent.com/x", hop(LIVE_APK, 302, "https://objects.githubusercontent.com/x", 4), "hosts: the fifth redirect");
        eq("go:https://github.com/HugoMeyer/other", hop(LIVE_APK, 302, "/HugoMeyer/other", 0), "hosts: a redirect with a path");
        eq("go:https://github.com/HugoMeyer/arr411-android/releases/download/v2.3.1/Other.apk", hop(LIVE_APK, 302, "Other.apk", 0), "hosts: a redirect from the folder");
        eq("go:https://release-assets.githubusercontent.com/x", hop(LIVE_APK, 302, "//release-assets.githubusercontent.com/x", 0), "hosts: a redirect that keeps https");
        eq("go:https://api.github.com/x", hop(LIVE_APK, 302, "  https://api.github.com/x \n", 0), "hosts: spaces around the address");

        String[] refused = {"//evil.com/x", "https://evil.com/x", "http://github.com/x", "http://release-assets.githubusercontent.com/x", "https://user:pw@github.com/x",
            "https://github.com./x", "https://github.com:8443/x", "https://githubusercontent.com/x", "https://raw.githubusercontent.com/x", "ftp://github.com/x",
            "file:///etc/passwd", "https://evil.com/x?token=SECRET", "https://127.0.0.1/x", "https://[::1]/x", "https://github.com/" + "a".repeat(3000)};
        for (String location : refused) {
            String result = hop(LIVE_APK, 302, location, 0);
            check(result.startsWith("no:Redirection vers un autre serveur refus\u00e9e"), "hosts: a redirect that is refused: " + shortened(location) + " gives " + shortened(result));
            check(!result.contains("SECRET") && !result.contains("pw") && !result.contains("token") && result.length() < 120, "hosts: the refusal tells no secret: " + shortened(location));
        }
        eq("no:Trop de redirections", hop(LIVE_APK, 302, cdn, 5), "hosts: a sixth redirect is too many");
        eq("no:Trop de redirections", hop(LIVE_APK, 302, cdn, Integer.MAX_VALUE), "hosts: and so is any later one");
        for (int status : new int[] {200, 204, 300, 304, 400, 404, 500, 0, -1}) {
            eq("no:R\u00e9ponse inattendue du serveur (HTTP " + status + ")", hop(LIVE_APK, status, cdn, 0), "hosts: not a redirect: " + status);
        }
        for (String location : new String[] {null, "", "   ", "\n", "javascript:alert(1)", "data:text/plain,x"}) {
            eq("no:Redirection invalide", hop(LIVE_APK, 302, location, 0), "hosts: a location that is no address: " + location);
        }
    }

    // ---------- Update: the release that GitHub answers ----------

    static String notesOf(String body) {
        return UpdateRelease.parse(releaseJson("v2.3.2", body, goodApk("v2.3.2"))).notes;
    }

    static boolean notAnswer(String json) {
        try {
            UpdateRelease.parse(json);
            return false;
        } catch (IllegalArgumentException e) {
            return true;
        }
    }

    static UpdateRelease parsed(String json) {
        UpdateRelease r = UpdateRelease.parse(json);
        check(r != null, "a release is read from: " + shortened(json));
        return r;
    }

    /** The release says what it should, and whether it can be installed from the app. */
    static boolean isInstallable(String json) {
        UpdateRelease r = UpdateRelease.parse(json);
        return r != null && r.installable();
    }

    static void updateRelease() throws Exception {
        String live = liveRelease();
        if (live == null) return;
        UpdateRelease r = parsed(live);
        if (r == null) return;
        eq("v2.3.1", r.tag, "release: the tag");
        eq("2.3.1", r.version, "release: the version");
        eq(UpdateHosts.TAG_PAGE + "v2.3.1", r.pageUrl, "release: the page");
        eq(LIVE_APK, r.apkUrl, "release: the file");
        eq(391402L, r.size, "release: the size");
        eq(H1, r.digest, "release: the digest");
        check(!r.digestInvalid && !r.sumsPresent && r.sumsUrl == null, "release: a digest and no sums");
        check(r.installable(), "release: the live release can be installed from the app");
        check(r.notes.startsWith("Installe **Arr411.apk** par-dessus la version pr\u00e9c\u00e9dente") && r.notes.endsWith("`" + H1 + "`\n"), "release: the notes are the body");

        String digest = "\"digest\":\"sha256:" + H1 + "\"";
        UpdateRelease v = parsed(swap(live, digest + ",", ""));
        check(v != null && v.apkUrl != null && v.digest == null && !v.digestInvalid && !v.installable(), "release: no digest and no sums, no way to check the file");
        v = parsed(swap(live, digest, "\"digest\":null"));
        check(v != null && v.apkUrl != null && v.digest == null && !v.digestInvalid && !v.installable(), "release: a null digest is no digest");
        String[] badDigests = {"\"md5:" + H1.substring(0, 32) + "\"", "\"sha256:" + H1.substring(1) + "\"", "\"" + H1 + "\"", "\"\"", "\"sha256:\"", "12", "true", "[]", "{}"};
        for (String bad : badDigests) {
            v = parsed(swap(live, digest, "\"digest\":" + bad));
            check(v != null && v.digest == null && v.digestInvalid && !v.installable(), "release: a digest that is not a SHA-256: " + bad);
        }
        v = parsed(swap(live, digest, "\"digest\":\"SHA256:" + H1.toUpperCase() + "\""));
        check(v != null && H1.equals(v.digest) && v.installable(), "release: a digest in capitals is the same digest");

        for (String size : new String[] {"1", "391402", "52428800"}) {
            v = parsed(swap(live, "\"size\":391402,", "\"size\":" + size + ","));
            check(v != null && v.size == Long.parseLong(size) && v.installable(), "release: a size that is allowed: " + size);
        }
        for (String size : new String[] {"0", "-5", "52428801", "999999999999", "9223372036854775807", "99999999999999999999", "391402.0", "3.9e5", "\"391402\"", "null", "true", "[]"}) {
            v = parsed(swap(live, "\"size\":391402,", "\"size\":" + size + ","));
            check(v != null && v.apkUrl != null && !v.installable(), "release: a size that is not allowed: " + size);
        }
        v = parsed(swap(live, "\"size\":391402,", ""));
        check(v != null && v.size == 0 && !v.installable(), "release: no size");

        for (String state : new String[] {"\"state\":\"starter\",", "\"state\":\"open\",", "\"state\":\"UPLOADED\",", "\"state\":\"\",", "\"state\":true,"}) {
            v = parsed(swap(live, "\"state\":\"uploaded\",", state));
            check(v != null && v.apkUrl == null && v.digest == null && !v.installable(), "release: a file that is not uploaded is not a file: " + state);
        }
        for (String state : new String[] {"", "\"state\":null,"}) {
            v = parsed(swap(live, "\"state\":\"uploaded\",", state));
            check(v != null && v.installable(), "release: a file with no state: " + state);
        }

        String link = "\"browser_download_url\":\"" + LIVE_APK + "\"";
        String[] badLinks = {"http://github.com/HugoMeyer/arr411-android/releases/download/v2.3.1/Arr411.apk", "https://evil.com/HugoMeyer/arr411-android/releases/download/v2.3.1/Arr411.apk",
            "https://github.com/Evil/arr411-android/releases/download/v2.3.1/Arr411.apk", "https://github.com/HugoMeyer/other/releases/download/v2.3.1/Arr411.apk",
            DOWNLOADS + "v2.3.1/Other.apk", DOWNLOADS + "v2.3.1/Arr411.apk.exe", DOWNLOADS + "v2.3.1/arr411.apk", LIVE_APK + "?x=1", LIVE_APK + "/", DOWNLOADS + "v2.3.1/../v2.3.0/Arr411.apk", "", "Arr411.apk"};
        for (String bad : badLinks) {
            v = parsed(swap(live, link, "\"browser_download_url\":\"" + bad + "\""));
            check(v != null && v.apkUrl == null && !v.installable(), "release: an address that is not the file: " + shortened(bad));
        }
        for (String bad : new String[] {"5", "null", "true", "[]", "{}"}) {
            v = parsed(swap(live, link, "\"browser_download_url\":" + bad));
            check(v != null && v.apkUrl == null && !v.installable(), "release: an address that is not text: " + bad);
        }
        v = parsed(swap(live, "," + link, ""));
        check(v != null && v.apkUrl == null && !v.installable(), "release: no address");
        for (String name : new String[] {"arr411.apk", "Arr411.apk ", "Arr411.APK", "Arr411", "", "x/Arr411.apk"}) {
            v = parsed(swap(live, "\"name\":\"Arr411.apk\"", "\"name\":\"" + name + "\""));
            check(v != null && v.apkUrl == null && !v.installable(), "release: a file with another name is not the file: [" + name + "]");
        }

        check(UpdateRelease.parse(swap(live, "\"draft\":false", "\"draft\":true")) == null, "release: a draft is not offered");
        check(UpdateRelease.parse(swap(live, "\"prerelease\":false", "\"prerelease\":true")) == null, "release: a prerelease is not offered");
        v = parsed(swap(live, "\"html_url\":\"" + UpdateHosts.TAG_PAGE + "v2.3.1\"", "\"html_url\":\"https://evil.example/phish\""));
        eq(UpdateHosts.TAG_PAGE + "v2.3.1", v == null ? null : v.pageUrl, "release: the page is built from the tag, not taken from the answer");

        for (String tag : new String[] {"nightly", "latest", "", "v", "2.3.1-rc1", "v2.3.1-rc1", "v2.3.1/../x", "v2.3.1 ", " v2.3.1", "v2.3.1\n", "1.2.3.4.5", "v1.2.3.4.5"}) {
            check(UpdateRelease.parse(releaseJson(tag, "n", goodApk("v2.3.2"))) == null, "release: a tag that is not a version is not offered: [" + tag.replace("\n", "\\n") + "]");
        }
        for (String json : new String[] {"{}", "{\"assets\":[]}", "{\"tag_name\":null}", "{\"tag_name\":2.3}", "{\"tag_name\":23}", "{\"tag_name\":[\"v2.3\"]}", "{\"tag_name\":{}}", "{\"tag_name\":true}"}) {
            check(UpdateRelease.parse(json) == null, "release: no tag in the text of a version: " + json);
        }
        v = releaseOf("V2.3.2");
        check(v != null && v.tag.equals("V2.3.2") && v.version.equals("2.3.2") && v.pageUrl.equals(UpdateHosts.TAG_PAGE + "V2.3.2") && v.installable(), "release: a tag with a capital V");
        v = releaseOf("v02.03.02");
        check(v != null && v.tag.equals("v02.03.02") && v.version.equals("2.3.2") && v.pageUrl.equals(UpdateHosts.TAG_PAGE + "v02.03.02"), "release: a tag with zeros is shown as it is written");
        v = releaseOf("2.3.2");
        check(v != null && v.tag.equals("2.3.2") && v.version.equals("2.3.2"), "release: a tag with no v");

        v = parsed(releaseJson("v2.3.2", "n", goodApk("v2.3.2"), goodApk("v2.3.2")));
        check(v != null && v.apkUrl == null && !v.installable(), "release: two files named Arr411.apk, which one is true?");
        for (String json : new String[] {"{\"tag_name\":\"v2.3.2\"}", "{\"tag_name\":\"v2.3.2\",\"assets\":null}", "{\"tag_name\":\"v2.3.2\",\"assets\":\"x\"}",
            "{\"tag_name\":\"v2.3.2\",\"assets\":{}}", "{\"tag_name\":\"v2.3.2\",\"assets\":[]}", "{\"tag_name\":\"v2.3.2\",\"assets\":[1,null,\"a\",[],true]}"}) {
            v = parsed(json);
            check(v != null && v.version.equals("2.3.2") && v.apkUrl == null && !v.installable(), "release: no list of files: " + json);
        }
        check(isInstallable("{\"tag_name\":\"v2.3.2\",\"assets\":[1,null,\"a\",[]," + goodApk("v2.3.2") + ",true]}"), "release: the file among things that are not files");

        String apkNoDigest = assetJson("Arr411.apk", DOWNLOADS + "v2.3.2/Arr411.apk", "1000", "null");
        check(!isInstallable(releaseJson("v2.3.2", "n", apkNoDigest)), "release: no hash source, no install from the app");
        v = parsed(releaseJson("v2.3.2", "n", apkNoDigest, goodSums("v2.3.2")));
        check(v != null && v.installable() && v.sumsPresent && (DOWNLOADS + "v2.3.2/SHA256SUMS.txt").equals(v.sumsUrl) && v.digest == null, "release: SHA256SUMS.txt alone is a hash source");
        v = parsed(releaseJson("v2.3.2", "n", goodApk("v2.3.2"), goodSums("v2.3.2")));
        check(v != null && v.installable() && v.digest != null && v.sumsUrl != null, "release: a digest and SHA256SUMS.txt");
        String[] badSums = {assetJson("SHA256SUMS.txt", "", "100", "null"), assetJson("SHA256SUMS.txt", "https://evil.com/SHA256SUMS.txt", "100", "null"),
            assetJson("SHA256SUMS.txt", DOWNLOADS + "v2.3.2/other.txt", "100", "null"), goodSums("v2.3.2").replace("\"uploaded\"", "\"starter\"")};
        for (String sums : badSums) {
            v = parsed(releaseJson("v2.3.2", "n", goodApk("v2.3.2"), sums));
            check(v != null && v.sumsPresent && v.sumsUrl == null && !v.installable(), "release: a SHA256SUMS.txt that cannot be read makes the file uninstallable, whatever the digest: " + shortened(sums));
        }
        v = parsed(releaseJson("v2.3.2", "n", goodApk("v2.3.2"), goodSums("v2.3.2"), goodSums("v2.3.2")));
        check(v != null && v.sumsPresent && v.sumsUrl == null && !v.installable(), "release: two SHA256SUMS.txt, which one is true?");
        v = parsed(releaseJson("v2.3.2", "n", goodApk("v2.3.2"), assetJson("sha256sums.txt", DOWNLOADS + "v2.3.2/sha256sums.txt", "100", "null")));
        check(v != null && !v.sumsPresent && v.installable(), "release: a file with another name is not SHA256SUMS.txt");

        String emoji = "\uD83D\uDE00";
        eq("a".repeat(4000), notesOf("a".repeat(5000)), "release: notes are cut at 4000 characters");
        eq("a".repeat(4000), notesOf("a".repeat(4000)), "release: notes of 4000 characters stay");
        eq("a".repeat(3999), notesOf("a".repeat(3999) + emoji), "release: an emoji that the cut would split goes");
        eq("a".repeat(3998) + emoji, notesOf("a".repeat(3998) + emoji), "release: an emoji that fits stays");
        eq("a".repeat(3998) + emoji, notesOf("a".repeat(3998) + emoji + "bcd"), "release: the cut right after an emoji");
        eq("", notesOf(null), "release: a null body");
        eq("", notesOf(""), "release: an empty body");
        eq("", UpdateRelease.parse("{\"tag_name\":\"v2.3.2\"}").notes, "release: no body");
        eq("", UpdateRelease.parse("{\"tag_name\":\"v2.3.2\",\"body\":5}").notes, "release: a body that is not text");
        eq("\u00e9\"\n\\", notesOf("\u00e9\"\n\\"), "release: the notes are text, not JSON");
        eq(4000, UpdateRelease.NOTES_MAX, "release: 4000 characters of notes");
        eq("", UpdateRelease.excerpt(null, 10), "excerpt: no text");
        eq("", UpdateRelease.excerpt("abc", 0), "excerpt: nothing");
        eq("abc", UpdateRelease.excerpt("abc", 3), "excerpt: all of it");
        eq("ab", UpdateRelease.excerpt("abc", 2), "excerpt: the start");
        eq("", UpdateRelease.excerpt(emoji, 1), "excerpt: an emoji that does not fit");
        eq("a", UpdateRelease.excerpt("a" + emoji, 2), "excerpt: an emoji that would be split");
        eq("a" + emoji, UpdateRelease.excerpt("a" + emoji, 3), "excerpt: an emoji that fits");
        eq("a" + emoji, UpdateRelease.excerpt("a" + emoji + "b", 3), "excerpt: an emoji that fits, and text after");

        for (String json : new String[] {"[]", "[{\"tag_name\":\"v2.3.2\"}]", "garbage", "", "null", "123", "\"text\"", "{", "{\"tag_name\":", "{\"a\":1}x", "\uFFFF"}) {
            check(notAnswer(json), "release: an answer that is not an object is refused: " + json);
        }
        check(notAnswer(null), "release: no answer is refused");

        check(r.isFile("2.3.1", 391402, H1), "release: the file that was verified is the file of the release");
        check(r.isFile("v2.3.1", 391402, H1), "release: whatever the way the version of the file is written");
        check(!r.isFile("2.3.0", 391402, H1), "release: a file of another version is not the file");
        check(!r.isFile("2.3.1", 391403, H1), "release: a file of another size is not the file");
        check(!r.isFile("2.3.1", 391402, H2), "release: a file that was verified with another hash than the digest is not the file");
        check(!r.isFile("2.3.1", 391402, null) && !r.isFile(null, 391402, H1) && !r.isFile("", 391402, H1), "release: a file with no record is not the file");
        check(!r.isFile("2.3.1", 0, H1), "release: an empty file is not the file");
        UpdateRelease sumsOnly = UpdateRelease.parse(releaseJson("v2.3.2", "", assetJson("Arr411.apk", DOWNLOADS + "v2.3.2/Arr411.apk", "1000", "null"), goodSums("v2.3.2")));
        check(sumsOnly != null && sumsOnly.installable() && sumsOnly.isFile("2.3.2", 1000, H2), "release: with the sums alone, the hash that the sums gave is the one that was verified");
        check(sumsOnly != null && !sumsOnly.isFile("2.3.2", 1001, H2), "release: with the sums alone, the size still has to be the same");
        check(UpdateRelease.ANSWER_MAX_BYTES == 1024 * 1024 && UpdateRelease.CACHE_MAX_CHARS == 256 * 1024, "release: how much of the answer is read and kept");
    }

    // ---------- Update: when to check, and how often to show the progress ----------

    static void updateThrottle() {
        long hour = 60L * 60 * 1000;
        long now = 1_800_000_000_000L;
        eq(6 * hour, UpdateThrottle.PERIOD_MS, "throttle: a check every 6 hours");
        eq(60_000L, UpdateThrottle.MANUAL_GAP_MS, "throttle: a check the user asks for at most every minute");
        eq(30_000L, UpdateThrottle.STARTUP_DELAY_MS, "throttle: the first check 30 seconds after the start");

        check(UpdateThrottle.periodDue(now, 0), "throttle: nothing was ever checked");
        check(UpdateThrottle.periodDue(now, -1), "throttle: a negative time is no check");
        check(UpdateThrottle.periodDue(now, now - 6 * hour), "throttle: a period has passed");
        check(!UpdateThrottle.periodDue(now, now - 6 * hour + 1), "throttle: a millisecond short of a period");
        check(!UpdateThrottle.periodDue(now, now), "throttle: just checked");
        check(UpdateThrottle.periodDue(now, now + 1), "throttle: the clock went back");
        check(UpdateThrottle.periodDue(0, Long.MAX_VALUE), "throttle: the clock went far back");
        check(UpdateThrottle.periodDue(Long.MAX_VALUE, 1), "throttle: a very old check");

        check(UpdateThrottle.jobDue(now, 0), "throttle: the job when nothing was ever checked");
        check(UpdateThrottle.jobDue(now, now - 3 * hour), "throttle: the job runs three hours after a check");
        check(!UpdateThrottle.jobDue(now, now - 3 * hour + 1), "throttle: the job skips a check that is too recent");
        check(UpdateThrottle.jobDue(now, now - 5 * hour), "throttle: the job runs in the last hour of its period");
        check(UpdateThrottle.jobDue(now, now + 1), "throttle: the job when the clock went back");

        check(UpdateThrottle.manualAllowed(5_000, -1), "throttle: the first check the user asks for");
        check(!UpdateThrottle.manualAllowed(100_000, 100_000), "throttle: the same instant");
        check(!UpdateThrottle.manualAllowed(159_999, 100_000), "throttle: 59.999 seconds later");
        check(UpdateThrottle.manualAllowed(160_000, 100_000), "throttle: a minute later");
        check(UpdateThrottle.manualAllowed(50_000, 100_000), "throttle: the monotonic clock went back");
        check(!UpdateThrottle.manualAllowed(30_000, 0), "throttle: a check at the first instant of the clock counts");

        check(UpdateThrottle.progressDue(0, -1), "throttle: the first progress");
        check(!UpdateThrottle.progressDue(1_000, 1_000), "throttle: progress at the same instant");
        check(!UpdateThrottle.progressDue(1_249, 1_000), "throttle: progress 249 ms later");
        check(UpdateThrottle.progressDue(1_250, 1_000), "throttle: progress 250 ms later");
        check(UpdateThrottle.progressDue(500, 1_000), "throttle: progress when the clock went back");
    }

    // ---------- Update: the files of the cache folder ----------

    static void updateFiles() {
        eq("Arr411-2.3.2.apk", UpdateFiles.apkName("2.3.2"), "files: the name of a file");
        eq("Arr411-2.3.2.apk", UpdateFiles.apkName("V02.03.02"), "files: the name does not depend on how the tag is written");
        eq("Arr411-2.3.2.apk.part", UpdateFiles.partName("v2.3.2"), "files: the name of a download that is not finished");
        for (String bad : new String[] {null, "", "nightly", "2.3.2-rc1", "../../x", "2.3.2/../x", "2.3.2 ", "Arr411"}) {
            eq(null, UpdateFiles.apkName(bad), "files: no name for " + quoted(bad));
            eq(null, UpdateFiles.partName(bad), "files: no name for a download of " + quoted(bad));
        }
        eq("2.3.2", UpdateFiles.versionOf("Arr411-2.3.2.apk"), "files: the version of a file");
        eq("2.3", UpdateFiles.versionOf("Arr411-2.3.apk"), "files: a version of two numbers");
        eq("2.3.2", UpdateFiles.versionOf(UpdateFiles.apkName("v2.3.2")), "files: a name leads back to its version");
        for (String name : new String[] {null, "", "Arr411.apk", "Arr411-.apk", "Arr411-2.3.2", "Arr411-2.3.2.apk.part", "Arr411-2.3.2.apk.apk", "Arr411-2.3.2.APK",
            "arr411-2.3.2.apk", "Arr411-v2.3.2.apk", "Arr411-02.3.2.apk", "Arr411-2.3.2.apk ", " Arr411-2.3.2.apk", "../Arr411-2.3.2.apk", "Arr411-../x.apk",
            "Arr411-2.3.2/../x.apk", "dir/Arr411-2.3.2.apk", "Arr411-2.3.2.0.0.apk", "notes.txt"}) {
            eq(null, UpdateFiles.versionOf(name), "files: not a file of the cache: " + quoted(name));
        }

        List<String> all = Arrays.asList("Arr411-2.3.2.apk", "Arr411-2.3.3.apk", "Arr411-2.3.3.apk.part", "Arr411-2.3.1.apk", "Arr411-2.3.0.apk", "notes.txt",
            "Arr411-v2.3.4.apk");
        eq(Arrays.asList("Arr411-2.3.2.apk", "Arr411-2.3.3.apk.part", "Arr411-2.3.1.apk", "Arr411-2.3.0.apk", "notes.txt", "Arr411-v2.3.4.apk"),
            UpdateFiles.toDelete(all, "2.3.1", null), "files: only the newest update stays");
        eq(Arrays.asList("Arr411-2.3.3.apk", "Arr411-2.3.3.apk.part", "Arr411-2.3.1.apk", "Arr411-2.3.0.apk", "notes.txt", "Arr411-v2.3.4.apk"),
            UpdateFiles.toDelete(all, "2.3.1", "Arr411-2.3.2.apk"), "files: the file to keep stays, even when another one is newer");
        eq(all, UpdateFiles.toDelete(all, "2.3.1", "Arr411-2.3.1.apk"), "files: the installed version is not worth keeping");
        eq(all, UpdateFiles.toDelete(all, "2.3.1", "Arr411-2.3.0.apk"), "files: nor is an older one");
        eq(all, UpdateFiles.toDelete(all, "2.3.1", "Arr411-9.9.9.apk"), "files: a file to keep that is not there");
        for (String keep : new String[] {"", "notes.txt", "Arr411-2.3.3.apk.part", "Arr411-v2.3.4.apk", "../Arr411-2.3.3.apk"}) {
            eq(all, UpdateFiles.toDelete(all, "2.3.1", keep), "files: a file to keep that is not a file of the cache: " + quoted(keep));
        }
        eq(all, UpdateFiles.toDelete(all, "dev", null), "files: nothing is newer than a version that is not one");
        eq(all, UpdateFiles.toDelete(all, null, null), "files: nor than no version");
        eq(Collections.emptyList(), UpdateFiles.toDelete(Collections.<String>emptyList(), "2.3.1", null), "files: no files");
        eq(Collections.emptyList(), UpdateFiles.toDelete(Collections.<String>emptyList(), "2.3.1", "Arr411-2.3.2.apk"), "files: no files, and one to keep");
        eq(Arrays.asList("Arr411-2.9.0.apk"), UpdateFiles.toDelete(Arrays.asList("Arr411-2.9.0.apk", "Arr411-2.10.0.apk"), "2.3.1", null), "files: 2.10.0 is newer than 2.9.0");
        eq(Arrays.asList("Arr411-2.9.0.apk"), UpdateFiles.toDelete(Arrays.asList("Arr411-2.10.0.apk", "Arr411-2.9.0.apk"), "2.3.1", null), "files: whatever their order");
        eq(Arrays.asList("Arr411-2.3.2.apk.part"), UpdateFiles.toDelete(Arrays.asList("Arr411-2.3.2.apk", "Arr411-2.3.2.apk.part"), "2.3.1", "Arr411-2.3.2.apk"),
            "files: a download that is not finished never stays");
    }

    // ---------- Update: what the system says about an installation ----------

    static void updateInstallStatus() {
        eq(UpdateInstallStatus.Kind.PENDING, UpdateInstallStatus.kind(-1), "install: the system needs the user");
        eq(UpdateInstallStatus.Kind.SUCCESS, UpdateInstallStatus.kind(0), "install: success");
        eq(UpdateInstallStatus.Kind.CANCELLED, UpdateInstallStatus.kind(3), "install: the user said no");
        for (int status : new int[] {1, 2, 4, 5, 6, 7, 8, 9, 10, 100, -2, -100, Integer.MAX_VALUE, Integer.MIN_VALUE}) {
            eq(UpdateInstallStatus.Kind.FAILED, UpdateInstallStatus.kind(status), "install: a failure: " + status);
        }
        Set<String> seen = new TreeSet<>();
        for (int status = 1; status <= 8; status++) {
            String text = UpdateInstallStatus.message(status);
            check(text != null && !text.isEmpty() && !text.contains("code"), "install: a sentence for the status " + status);
            seen.add(text);
        }
        eq(8, seen.size(), "install: each status has its own sentence");
        eq(UpdateState.CANCELLED, UpdateInstallStatus.message(3), "install: a cancelled installation is told the same way everywhere");
        check(UpdateInstallStatus.message(99).contains("99") && UpdateInstallStatus.message(-7).contains("-7"), "install: a status that is not known is told with its code");
    }

    // ---------- Update: what to do after a check, a download or an installation ----------

    static final String[] MODES = {"auto", "notify", "off", "never", "", null};

    static String quoted(String text) {
        return text == null ? "null" : "[" + text.replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t") + "]";
    }

    static String told(Throwable e) {
        String text = UpdateFlow.describe(e);
        check(text != null && !text.isEmpty(), "flow: something is told for " + e);
        return text == null ? "" : text;
    }

    static void updateFlow() {
        eq("auto", UpdateFlow.mode(null), "flow: no setting is auto");
        for (String raw : new String[] {"", "   ", "\t\n", "auto", "AUTO", " Auto ", "\tauto\r\n"}) eq("auto", UpdateFlow.mode(raw), "flow: the setting is auto: " + quoted(raw));
        for (String raw : new String[] {"notify", " Notify ", "NOTIFY"}) eq("notify", UpdateFlow.mode(raw), "flow: the setting is notify: " + quoted(raw));
        for (String raw : new String[] {"off", "OFF", " off\n"}) eq("off", UpdateFlow.mode(raw), "flow: the setting is off: " + quoted(raw));
        for (String raw : new String[] {"never", "false", "0", "on", "true", "auto2", "a u t o", "off!", "\u00a0auto"}) {
            eq("notify", UpdateFlow.mode(raw), "flow: a setting that is not known never installs anything: " + quoted(raw));
        }

        for (int sdk : new int[] {Integer.MIN_VALUE, -1, 0, 1, 29, 30}) check(!UpdateFlow.silentPossible(sdk, 34), "flow: no silent update before Android 12: " + sdk);
        for (int sdk = 31; sdk <= 36; sdk++) check(UpdateFlow.silentPossible(sdk, 34), "flow: silent with targetSdk 34 up to Android 16: " + sdk);
        check(!UpdateFlow.silentPossible(37, 34), "flow: Android 17 asks the user when the targetSdk is 34");
        for (int sdk : new int[] {38, 40, 1000, Integer.MAX_VALUE}) check(!UpdateFlow.silentPossible(sdk, 99), "flow: a version that is not known is assumed to ask: " + sdk);
        int[][] needs = {{31, 29}, {32, 29}, {33, 30}, {34, 31}, {35, 33}, {36, 34}, {37, 35}};
        for (int[] need : needs) {
            check(UpdateFlow.silentPossible(need[0], need[1]), "flow: API " + need[0] + " is silent from the targetSdk " + need[1]);
            check(!UpdateFlow.silentPossible(need[0], need[1] - 1), "flow: API " + need[0] + " asks below the targetSdk " + need[1]);
        }

        eq(UpdateFlow.AfterCheck.DOWNLOAD, UpdateFlow.afterCheck("auto", true, true, true, false, false), "flow: auto mode downloads an update it can verify");
        eq(UpdateFlow.AfterCheck.ASSESS_READY, UpdateFlow.afterCheck("auto", true, true, true, true, false), "flow: auto mode with the file already there");
        eq(UpdateFlow.AfterCheck.NOTIFY_AVAILABLE, UpdateFlow.afterCheck("notify", true, true, true, false, false), "flow: notify mode only tells");
        eq(UpdateFlow.AfterCheck.NOTIFY_AVAILABLE, UpdateFlow.afterCheck("notify", true, true, true, true, false), "flow: notify mode only tells, even with the file there");
        eq(UpdateFlow.AfterCheck.NONE, UpdateFlow.afterCheck("off", true, true, true, false, false), "flow: off mode does nothing");
        eq(UpdateFlow.AfterCheck.NONE, UpdateFlow.afterCheck("auto", false, true, true, false, false), "flow: a check the user asked for only shows its result");
        eq(UpdateFlow.AfterCheck.NONE, UpdateFlow.afterCheck("auto", true, false, true, false, false), "flow: nothing new, nothing to do");
        eq(UpdateFlow.AfterCheck.NOTIFY_AVAILABLE, UpdateFlow.afterCheck("auto", true, true, false, false, false), "flow: no way to verify the file, so only tell");
        eq(UpdateFlow.AfterCheck.NOTIFY_AVAILABLE, UpdateFlow.afterCheck("auto", true, true, true, false, true), "flow: a version whose file failed the checks is not downloaded again");

        List<String> wrong = new ArrayList<>();
        for (String mode : MODES) {
            for (int bits = 0; bits < 32; bits++) {
                boolean automatic = (bits & 1) != 0;
                boolean available = (bits & 2) != 0;
                boolean installable = (bits & 4) != 0;
                boolean ready = (bits & 8) != 0;
                boolean blocked = (bits & 16) != 0;
                UpdateFlow.AfterCheck r = UpdateFlow.afterCheck(mode, automatic, available, installable, ready, blocked);
                String where = quoted(mode) + " automatic=" + automatic + " available=" + available + " installable=" + installable + " ready=" + ready + " blocked=" + blocked;
                boolean fetches = r == UpdateFlow.AfterCheck.DOWNLOAD || r == UpdateFlow.AfterCheck.ASSESS_READY;
                boolean mayFetch = "auto".equals(mode) && automatic && available && installable && !blocked;
                if (fetches && !mayFetch) wrong.add("downloads when it should not: " + where);
                if (mayFetch && r != (ready ? UpdateFlow.AfterCheck.ASSESS_READY : UpdateFlow.AfterCheck.DOWNLOAD)) wrong.add("does not download: " + where);
                boolean acts = automatic && available && !"off".equals(mode);
                if (acts == (r == UpdateFlow.AfterCheck.NONE)) wrong.add(acts ? "does nothing: " + where : "acts when it should not: " + where);
            }
        }
        eq(Collections.emptyList(), wrong, "flow: what follows a check, in every case");

        eq(UpdateFlow.AfterReady.INSTALL, UpdateFlow.afterReady("auto", false, true, true, false), "flow: a download that nobody waits for is installed by itself");
        eq(UpdateFlow.AfterReady.NOTIFY_READY, UpdateFlow.afterReady("auto", true, true, true, false), "flow: not while the app is in front of the user");
        eq(UpdateFlow.AfterReady.NOTIFY_READY, UpdateFlow.afterReady("auto", false, true, false, false), "flow: not when Android would ask the user");
        eq(UpdateFlow.AfterReady.NOTIFY_PERMISSION, UpdateFlow.afterReady("auto", false, false, true, false), "flow: the user must allow the app to install updates");
        eq(UpdateFlow.AfterReady.NOTIFY_READY, UpdateFlow.afterReady("auto", false, true, true, true), "flow: an install that was already tried is not started again by itself");
        eq(UpdateFlow.AfterReady.NONE, UpdateFlow.afterReady("notify", false, true, true, false), "flow: notify mode never installs");
        eq(UpdateFlow.AfterReady.NONE, UpdateFlow.afterReady("off", false, true, true, false), "flow: off mode never installs");
        wrong.clear();
        for (String mode : MODES) {
            for (int bits = 0; bits < 16; bits++) {
                boolean foreground = (bits & 1) != 0;
                boolean mayInstall = (bits & 2) != 0;
                boolean silent = (bits & 4) != 0;
                boolean tried = (bits & 8) != 0;
                UpdateFlow.AfterReady r = UpdateFlow.afterReady(mode, foreground, mayInstall, silent, tried);
                String where = quoted(mode) + " foreground=" + foreground + " mayInstall=" + mayInstall + " silent=" + silent + " tried=" + tried;
                boolean auto = "auto".equals(mode);
                if (r == UpdateFlow.AfterReady.INSTALL && !(auto && !foreground && mayInstall && silent && !tried)) wrong.add("installs when it should not: " + where);
                if (!auto && r != UpdateFlow.AfterReady.NONE) wrong.add("acts without the auto mode: " + where);
                if (auto && (foreground || tried) && r != UpdateFlow.AfterReady.NOTIFY_READY) wrong.add("not told while in front or after a try: " + where);
                if (auto && !foreground && !tried && !mayInstall && r != UpdateFlow.AfterReady.NOTIFY_PERMISSION) wrong.add("the permission is not asked: " + where);
                if (auto && !foreground && !tried && mayInstall && r != (silent ? UpdateFlow.AfterReady.INSTALL : UpdateFlow.AfterReady.NOTIFY_READY)) wrong.add("wrong at the end: " + where);
            }
        }
        eq(Collections.emptyList(), wrong, "flow: what follows a download, in every case");

        long period = UpdateThrottle.PERIOD_MS;
        eq(UpdateFlow.JobPlan.CANCEL, UpdateFlow.jobPlan("off", true, period, true), "flow: off mode removes the job");
        eq(UpdateFlow.JobPlan.CANCEL, UpdateFlow.jobPlan("off", true, 0, false), "flow: off mode removes any job");
        eq(UpdateFlow.JobPlan.NOTHING, UpdateFlow.jobPlan("off", false, period, true), "flow: off mode with no job");
        for (String mode : new String[] {"auto", "notify"}) {
            eq(UpdateFlow.JobPlan.KEEP, UpdateFlow.jobPlan(mode, true, period, true), "flow: the job that is right is kept: " + mode);
            eq(UpdateFlow.JobPlan.SCHEDULE, UpdateFlow.jobPlan(mode, false, period, true), "flow: no job, so schedule one: " + mode);
            eq(UpdateFlow.JobPlan.SCHEDULE, UpdateFlow.jobPlan(mode, true, period, false), "flow: a job that does not survive a reboot is replaced: " + mode);
            eq(UpdateFlow.JobPlan.SCHEDULE, UpdateFlow.jobPlan(mode, true, period + 1, true), "flow: a job with another period is replaced: " + mode);
            eq(UpdateFlow.JobPlan.SCHEDULE, UpdateFlow.jobPlan(mode, true, period - 1, true), "flow: a job with a shorter period is replaced: " + mode);
        }

        long now = 1_800_000_000_000L;
        check(UpdateFlow.checksOnStartup("auto", now, 0), "flow: a check at the start when none ever succeeded");
        check(UpdateFlow.checksOnStartup("notify", now, now - period), "flow: a check at the start after a period");
        check(!UpdateFlow.checksOnStartup("auto", now, now - period + 1), "flow: no check at the start before a period");
        check(!UpdateFlow.checksOnStartup("off", now, 0), "flow: no check at the start in off mode");
        check(!UpdateFlow.checksOnStartup("off", now, now - 100 * period), "flow: no check at the start in off mode, even long after");

        eq("available:2.3.2", UpdateFlow.notifyKey("available", "v2.3.2"), "flow: the key of a notification");
        eq("ready:2.3.2", UpdateFlow.notifyKey("ready", "V02.03.02"), "flow: the key does not depend on how the tag is written");
        eq(null, UpdateFlow.notifyKey("available", "nightly"), "flow: no key for a version that is not one");
        eq(null, UpdateFlow.notifyKey("available", null), "flow: no key without a version");
        eq(null, UpdateFlow.notifyKey(null, "2.3.2"), "flow: no key without a kind");
        check(UpdateFlow.shouldNotify(null, "available", "2.3.2"), "flow: the first notification");
        check(UpdateFlow.shouldNotify("", "available", "2.3.2"), "flow: the first notification, with an empty marker");
        check(!UpdateFlow.shouldNotify("available:2.3.2", "available", "v2.3.2"), "flow: the same notification only once");
        check(UpdateFlow.shouldNotify("available:2.3.2", "ready", "2.3.2"), "flow: another kind for the same version");
        check(UpdateFlow.shouldNotify("available:2.3.2", "available", "2.3.3"), "flow: the same kind for another version");
        check(!UpdateFlow.shouldNotify(null, "available", "dev"), "flow: nothing to tell about a version that is not one");
        check(!UpdateFlow.shouldNotify("x", null, "2.3.2"), "flow: nothing to tell of no kind");

        eq(null, UpdateFlow.archiveProblem(OWN_PACKAGE, 11, OWN_PACKAGE, 12, true), "flow: a file that is a newer Arr411");
        eq(null, UpdateFlow.archiveProblem(OWN_PACKAGE, 11, OWN_PACKAGE, Long.MAX_VALUE, true), "flow: any newer version code");
        String problem = UpdateFlow.archiveProblem(OWN_PACKAGE, 11, null, 0, false);
        check(problem != null && problem.contains("application valide"), "flow: a file that Android cannot read");
        check(UpdateFlow.archiveProblem(OWN_PACKAGE, 11, OWN_PACKAGE, 12, false) != null, "flow: a file that Android cannot read, whatever it says");
        problem = UpdateFlow.archiveProblem(OWN_PACKAGE, 11, "evil.app", 99, true);
        check(problem != null && problem.endsWith("Arr411"), "flow: another app");
        for (String other : new String[] {null, "", OWN_PACKAGE.toUpperCase(java.util.Locale.ROOT), OWN_PACKAGE + ".x", OWN_PACKAGE + " "}) {
            check(UpdateFlow.archiveProblem(OWN_PACKAGE, 11, other, 99, true) != null, "flow: not the package of the app: " + quoted(other));
        }
        check(UpdateFlow.archiveProblem(null, 11, OWN_PACKAGE, 99, true) != null, "flow: nothing is installed when the own package is not known");
        problem = UpdateFlow.archiveProblem(OWN_PACKAGE, 11, OWN_PACKAGE, 11, true);
        check(problem != null && problem.contains("plus r\u00e9cente"), "flow: the same version code");
        check(UpdateFlow.archiveProblem(OWN_PACKAGE, 11, OWN_PACKAGE, 10, true) != null, "flow: an older version code");
        check(UpdateFlow.archiveProblem(OWN_PACKAGE, Long.MAX_VALUE, OWN_PACKAGE, 99, true) != null, "flow: a very high version code installed");

        check(UpdateFlow.shouldAnnounceInstall("2.3.2", "2.3.2"), "flow: the install that the app started is announced");
        check(UpdateFlow.shouldAnnounceInstall("v2.3.2", "V2.3.2"), "flow: whatever the way the version is written");
        check(!UpdateFlow.shouldAnnounceInstall(null, "2.3.2"), "flow: an install that the app did not start is not announced");
        check(!UpdateFlow.shouldAnnounceInstall("2.3.3", "2.3.2"), "flow: another version is not announced");
        check(!UpdateFlow.shouldAnnounceInstall("2.3.2", null), "flow: no installed version, nothing to announce");
        check(!UpdateFlow.shouldAnnounceInstall("dev", "dev"), "flow: a version that is not one is not announced");

        for (int status : new int[] {403, 429}) check(UpdateFlow.httpMessage(status).contains("limite temporairement"), "flow: the rate limit of GitHub: " + status);
        eq("Aucune release trouv\u00e9e sur GitHub", UpdateFlow.httpMessage(404), "flow: no release");
        for (int status : new int[] {500, 502, 503, 599}) {
            String text = UpdateFlow.httpMessage(status);
            check(text.contains("momentan\u00e9ment indisponible") && text.contains("HTTP " + status), "flow: GitHub is down: " + status);
        }
        for (int status : new int[] {200, 301, 400, 401, 410, 600, 0, -1}) {
            eq("R\u00e9ponse inattendue de GitHub (HTTP " + status + ")", UpdateFlow.httpMessage(status), "flow: an answer that is not expected: " + status);
        }

        eq("Pas de connexion \u00e0 GitHub", told(new UnknownHostException("api.github.com")), "flow: an address that is not found");
        eq("Pas de connexion \u00e0 GitHub", told(new ConnectException("Connection refused")), "flow: a connection that is refused");
        eq("Pas de connexion \u00e0 GitHub", told(new NoRouteToHostException()), "flow: no route");
        eq("GitHub ne r\u00e9pond pas : d\u00e9lai d\u00e9pass\u00e9", told(new SocketTimeoutException("timeout")), "flow: a timeout is not an interruption");
        check(told(new SSLHandshakeException("PKIX path building failed")).contains("date de l\u2019appareil"), "flow: a certificate that does not pass");
        check(told(new javax.net.ssl.SSLException("closed")).contains("date de l\u2019appareil"), "flow: any TLS error");
        eq("R\u00e9ponse de GitHub trop volumineuse", told(new BodyReader.TooLargeException(1000)), "flow: an answer that is too big");
        eq("Le fichier t\u00e9l\u00e9charg\u00e9 est plus gros que pr\u00e9vu", told(new HashedCopy.LimitException(1000)), "flow: a file that is too big");
        eq("T\u00e9l\u00e9chargement interrompu", told(new HashedCopy.CancelledException()), "flow: a download that was stopped");
        eq("T\u00e9l\u00e9chargement interrompu", told(new InterruptedIOException("x")), "flow: an interrupted transfer");
        eq("Espace de stockage insuffisant", told(new IOException("write failed: ENOSPC (No space left on device)")), "flow: a full disk");
        eq("Espace de stockage insuffisant", told(new IOException("No space left on device")), "flow: a full disk, as the JVM says it");
        eq("Erreur pendant le t\u00e9l\u00e9chargement de la mise \u00e0 jour", told(new IOException("boom")), "flow: another I/O error");
        eq("Erreur pendant le t\u00e9l\u00e9chargement de la mise \u00e0 jour", told(new IOException((String) null)), "flow: an I/O error with no message");
        eq("Erreur inattendue pendant la mise \u00e0 jour", told(new RuntimeException("secret")), "flow: a bug");
        eq("Erreur inattendue pendant la mise \u00e0 jour", told(null), "flow: nothing at all");
        String secret = "SECRET /data/user/0/dev.c411.qbittorrent/cache/updates/Arr411-2.3.2.apk.part https://x.example/a?token=T0KEN";
        Throwable[] failures = {new UnknownHostException(secret), new ConnectException(secret), new NoRouteToHostException(secret), new SocketTimeoutException(secret),
            new SSLHandshakeException(secret), new BodyReader.TooLargeException(5), new HashedCopy.LimitException(5), new HashedCopy.CancelledException(),
            new InterruptedIOException(secret), new IOException(secret), new RuntimeException(secret), new IllegalStateException(secret), new OutOfMemoryError(secret)};
        for (Throwable e : failures) {
            String text = told(e);
            check(!text.contains("SECRET") && !text.contains("/data") && !text.contains("token") && !text.contains("T0KEN") && !text.contains("example"),
                "flow: the message of the exception is not told: " + e.getClass().getSimpleName());
        }
        eq("La taille est incorrecte", told(new UpdateFailure("La taille est incorrecte", true)), "flow: a failure with a message for the user is told as it is");
        check(!told(new UpdateFailure(null, false)).isEmpty() && !told(new UpdateFailure("", true)).isEmpty(), "flow: a failure with no message is still told");
        check(new UpdateFailure("x", true).integrity && !new UpdateFailure("x", false).integrity, "flow: a failure says whether the file is wrong");

        eq("auto", UpdateFlow.modeFromConfig(null), "config: no settings");
        eq("auto", UpdateFlow.modeFromConfig(""), "config: empty settings");
        eq("auto", UpdateFlow.modeFromConfig("{}"), "config: settings without the mode");
        eq("auto", UpdateFlow.modeFromConfig("{\"qbUrl\":\"http://nas:8080\",\"autoUpdates\":\"off\"}"), "config: another key is not the mode");
        eq("auto", UpdateFlow.modeFromConfig("{\"note\":\"autoUpdate\"}"), "config: a value that looks like the key is not the mode");
        for (String mode : new String[] {"auto", "notify", "off"}) {
            eq(mode, UpdateFlow.modeFromConfig("{\"qbUrl\":\"x\",\"autoUpdate\":\"" + mode + "\",\"startTab\":\"c411\"}"), "config: the mode " + mode);
        }
        eq("off", UpdateFlow.modeFromConfig(" { \"autoUpdate\" : \"OFF\" } "), "config: spaces and capitals");
        eq("off", UpdateFlow.modeFromConfig("\uFEFF{\"autoUpdate\":\"off\"}"), "config: a byte order mark");
        eq("auto", UpdateFlow.modeFromConfig("{\"autoUpdate\":\"\"}"), "config: an empty mode is auto");
        eq("auto", UpdateFlow.modeFromConfig("{\"autoUpdate\":null}"), "config: a null mode is auto");
        eq("notify", UpdateFlow.modeFromConfig("{\"autoUpdate\":\"sometimes\"}"), "config: a mode that is not known never installs");
        for (String value : new String[] {"true", "false", "0", "1", "[\"off\"]", "{\"mode\":\"off\"}", "2.5"}) {
            eq("notify", UpdateFlow.modeFromConfig("{\"autoUpdate\":" + value + "}"), "config: a mode that is not text never installs: " + value);
        }
        for (String broken : new String[] {"{\"autoUpdate\":\"off\"", "{\"autoUpdate\":\"off\"}}", "\"autoUpdate\"", "[\"autoUpdate\"]", "{\"autoUpdate\":}", "{\"autoUpdate\":\"off\",}"}) {
            eq("notify", UpdateFlow.modeFromConfig(broken), "config: settings that cannot be read never install: " + broken);
        }
        eq("auto", UpdateFlow.modeFromConfig("autoUpdate"), "config: no key in what is not settings");
        eq("auto", UpdateFlow.modeFromConfig("{\"x\":[1,2,{\"autoUpdates\":3}]"), "config: no mention of the mode, nothing to read");
        eq("off", UpdateFlow.modeFromConfig("{\"tracked\":{\"a\":[1,2,3]},\"autoUpdate\":\"off\"}"), "config: the mode after other settings");

        eq("\"abc\"", UpdateFlow.etagOrNull("\"abc\""), "etag: a strong tag");
        eq("W/\"0a1b2c\"", UpdateFlow.etagOrNull("W/\"0a1b2c\""), "etag: a weak tag");
        eq("x".repeat(200), UpdateFlow.etagOrNull("x".repeat(200)), "etag: 200 characters");
        for (String bad : new String[] {null, "", " ", "a b", " a", "a ", "a\nb", "a\rb", "a\r\nX-Evil: 1", "a\tb", "\u00e9", "a\u0000", "a\u007f", "\u2028", "x".repeat(201)}) {
            eq(null, UpdateFlow.etagOrNull(bad), "etag: a tag that cannot be sent back: " + (bad == null ? "null" : quoted(bad.length() > 20 ? bad.substring(0, 20) : bad)));
        }

        String key = "0123456789abcdef0123456789abcdef";
        check(UpdateFlow.validTap(key, key), "tap: the key of the installation");
        check(UpdateFlow.validTap(key, new String(key)), "tap: the same key, in another string");
        for (String given : new String[] {null, "", " ", key + " ", key.substring(1), key.toUpperCase(java.util.Locale.ROOT), "0123456789abcdef0123456789abcdeg", key + key}) {
            check(!UpdateFlow.validTap(key, given), "tap: a key that is not the one: " + (given == null ? "null" : quoted(given)));
        }
        check(!UpdateFlow.validTap(null, null) && !UpdateFlow.validTap("", "") && !UpdateFlow.validTap(null, "x") && !UpdateFlow.validTap("", "x"),
            "tap: no key on the app side, no tap is accepted");
    }

    // ---------- Update: the state that the page gets ----------

    static Map<?, ?> stateOf(UpdateState state) {
        Object parsed = UpdateJson.parse(state.toJson());
        check(parsed instanceof Map, "state: the state is an object");
        return parsed instanceof Map ? (Map<?, ?>) parsed : new LinkedHashMap<String, Object>();
    }

    static void updateState() throws Exception {
        String live = liveRelease();
        if (live == null) return;
        UpdateRelease latest = UpdateRelease.parse(live);
        check(latest != null, "state: the live release is read");
        if (latest == null) return;
        long at = 1_790_000_000_000L;

        UpdateState first = UpdateState.initial("2.3.1", "auto");
        eq("{\"supported\":true,\"current\":\"2.3.1\",\"latest\":null,\"available\":false,\"status\":\"idle\",\"progress\":null,\"notes\":\"\","
            + "\"url\":\"https://github.com/HugoMeyer/arr411-android/releases\",\"checkedAt\":null,\"canInstall\":false,\"error\":null,\"mode\":\"auto\"}",
            first.toJson(), "state: before any check");
        check(first.supported && !first.available() && !first.canInstall() && !first.busy(), "state: nothing to offer before any check");

        UpdateState found = UpdateState.initial("2.3.0", "auto").released(latest, at, false);
        Map<?, ?> m = stateOf(found);
        eq(Arrays.asList("supported", "current", "latest", "available", "status", "progress", "notes", "url", "checkedAt", "canInstall", "error", "mode"),
            new ArrayList<Object>(m.keySet()), "state: the keys of the contract, in order");
        eq(Boolean.TRUE, m.get("supported"), "state: supported");
        eq("2.3.0", m.get("current"), "state: the installed version");
        eq("2.3.1", m.get("latest"), "state: the latest version");
        eq(Boolean.TRUE, m.get("available"), "state: an update is there");
        eq("idle", m.get("status"), "state: nothing happens");
        eq(null, m.get("progress"), "state: no progress");
        eq(latest.notes, m.get("notes"), "state: the notes");
        eq(UpdateHosts.TAG_PAGE + "v2.3.1", m.get("url"), "state: the page of the release");
        eq(at, m.get("checkedAt"), "state: when the last check succeeded");
        eq(Boolean.TRUE, m.get("canInstall"), "state: the update can be installed from the app");
        eq(null, m.get("error"), "state: no error");
        eq("auto", m.get("mode"), "state: the mode");

        UpdateState same = UpdateState.initial("2.3.1", "auto").released(latest, at, true);
        m = stateOf(same);
        check(same.status == UpdateState.Status.UPTODATE && !same.available() && !same.canInstall() && !same.apkReady, "state: up to date, whatever file is in the cache");
        eq("uptodate", m.get("status"), "state: the status when up to date");
        eq("2.3.1", m.get("latest"), "state: the latest version is told when up to date");
        eq(Boolean.FALSE, m.get("available"), "state: nothing is available");
        eq(Boolean.FALSE, m.get("canInstall"), "state: nothing to install");
        UpdateState older = UpdateState.initial("2.3.2", "auto").released(latest, at, true);
        check(older.status == UpdateState.Status.UPTODATE && !older.available() && !older.canInstall(), "state: an older release is never offered");
        UpdateState none = UpdateState.initial("2.3.1", "auto").released(null, at, true);
        m = stateOf(none);
        eq("uptodate", m.get("status"), "state: a release that is not offered counts as up to date");
        eq(null, m.get("latest"), "state: and there is no latest version");
        eq(UpdateHosts.RELEASES_PAGE, m.get("url"), "state: the page of the releases when there is no release");
        eq(at, m.get("checkedAt"), "state: the check succeeded");

        UpdateState readyAtOnce = UpdateState.initial("2.3.0", "auto").released(latest, at, true);
        check(readyAtOnce.status == UpdateState.Status.READY && readyAtOnce.apkReady, "state: the file is already there");

        UpdateState checking = found.checking();
        check(checking.status == UpdateState.Status.CHECKING && checking.busy() && checking.error == null && Double.isNaN(checking.progress)
            && checking.release == latest && checking.checkedAt == at, "state: checking keeps what is known");
        eq("checking", stateOf(checking).get("status"), "state: checking, as the page reads it");
        UpdateState failed = found.failed("Pas de connexion \u00e0 GitHub");
        check(failed.status == UpdateState.Status.ERROR && !failed.busy() && failed.canInstall() && failed.release == latest, "state: an error keeps the release");
        m = stateOf(failed);
        eq("error", m.get("status"), "state: the status of an error");
        eq("Pas de connexion \u00e0 GitHub", m.get("error"), "state: the error");
        eq(Boolean.TRUE, m.get("canInstall"), "state: the update can still be installed after an error");
        eq(null, failed.checking().error, "state: a new check clears the error");
        String awkward = "a \"b\"\n\\ c</script>\u2028\uD83D\uDE00";
        UpdateState awkwardError = found.failed(awkward);
        check(safeToPaste(awkwardError.toJson()), "state: an error can be pasted into a script");
        eq(awkward, stateOf(awkwardError).get("error"), "state: an error with quotes and line breaks comes back as it was");

        UpdateState part = found.downloading(0.512);
        check(part.status == UpdateState.Status.DOWNLOADING && part.busy() && !part.apkReady && part.error == null, "state: downloading");
        check(part.toJson().contains("\"status\":\"downloading\",\"progress\":0.512,"), "state: the progress, with three decimals");
        double[] fractions = {0.5, 1.5, -1, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, 0, 1};
        String[] shown = {"0.500", "1.000", "0.000", "0.000", "1.000", "0.000", "0.000", "1.000"};
        for (int i = 0; i < fractions.length; i++) {
            check(found.downloading(fractions[i]).toJson().contains("\"progress\":" + shown[i] + ","), "state: the progress " + fractions[i] + " is shown as " + shown[i]);
        }
        UpdateState ready = part.ready();
        check(ready.status == UpdateState.Status.READY && ready.apkReady && Double.isNaN(ready.progress) && ready.error == null && ready.canInstall(), "state: ready");
        check(ready.toJson().contains("\"status\":\"ready\",\"progress\":null,"), "state: no progress once ready");
        UpdateState installing = ready.installing();
        check(installing.status == UpdateState.Status.INSTALLING && installing.busy() && installing.apkReady, "state: installing");
        UpdateState aborted = installing.aborted();
        check(aborted.status == UpdateState.Status.READY && aborted.apkReady && UpdateState.CANCELLED.equals(aborted.error) && aborted.canInstall() && !aborted.busy(),
            "state: an installation that was cancelled can be started again");
        check(installing.failed("x").apkReady, "state: the file is still there after a failed installation");

        eq("notify", stateOf(found.withMode("notify")).get("mode"), "state: another mode");
        eq("auto", stateOf(found).get("mode"), "state: the state it came from does not change");
        check(ready.withoutFile().status == UpdateState.Status.IDLE && !ready.withoutFile().apkReady, "state: a file that is gone makes a ready state idle");
        check(installing.withoutFile().status == UpdateState.Status.INSTALLING && !installing.withoutFile().apkReady, "state: a file that is gone does not stop an installation");
        check(failed.withoutFile().status == UpdateState.Status.ERROR && found.withoutFile().status == UpdateState.Status.IDLE, "state: a file that is gone changes nothing else");

        UpdateState dev = UpdateState.initial("dev", "auto").released(latest, at, false);
        m = stateOf(dev);
        check(!dev.supported && !dev.available() && !dev.canInstall(), "state: a version that is not one cannot be compared");
        eq(Boolean.FALSE, m.get("supported"), "state: not supported");
        eq("dev", m.get("current"), "state: the version as it is");
        eq(Boolean.FALSE, m.get("canInstall"), "state: nothing to install");
        eq("", stateOf(UpdateState.initial(null, "auto")).get("current"), "state: no version is an empty one");

        String notes = "Line 1\r\nLine \"2\" \\ </script> \u2028\u2029 \uD83D\uDE00 \u00e9\u0001\t";
        UpdateRelease withNotes = UpdateRelease.parse(releaseJson("v2.3.2", notes, goodApk("v2.3.2")));
        check(withNotes != null && notes.equals(withNotes.notes), "state: the notes are read as they were written");
        if (withNotes != null) {
            UpdateState shownNotes = UpdateState.initial("2.3.1", "auto").released(withNotes, at, false);
            check(safeToPaste(shownNotes.toJson()), "state: the notes can be pasted into a script");
            eq(notes, stateOf(shownNotes).get("notes"), "state: the notes come back as they were");
        }

        java.util.Locale saved = java.util.Locale.getDefault();
        try {
            java.util.Locale.setDefault(java.util.Locale.FRANCE);
            check(found.downloading(0.512).toJson().contains("\"progress\":0.512,"), "state: a decimal point in a French locale");
            java.util.Locale.setDefault(new java.util.Locale("tr", "TR"));
            for (UpdateState.Status status : UpdateState.Status.values()) {
                eq(status.name().toLowerCase(java.util.Locale.ROOT), status.wire(), "state: the status in a Turkish locale: " + status);
            }
            eq("auto", UpdateFlow.mode("AUTO"), "flow: the mode in a Turkish locale");
            eq("notify", UpdateFlow.mode("NOTIFY"), "flow: the mode notify in a Turkish locale");
            eq("off", UpdateFlow.mode("OFF"), "flow: the mode off in a Turkish locale");
            eq(H1, UpdateIntegrity.parseDigest("SHA256:" + H1.toUpperCase(java.util.Locale.ROOT)), "integrity: a digest in a Turkish locale");
        } finally {
            java.util.Locale.setDefault(saved);
        }

        check(UrlPolicy.isExternalOpenable(UpdateHosts.RELEASES_PAGE), "state: the page of the releases can be opened outside the app");
        check(UrlPolicy.isExternalOpenable(UpdateHosts.TAG_PAGE + "v2.3.1"), "state: the page of a release can be opened outside the app");
    }

    // ---------- Update: what cannot run here is checked in the sources ----------

    static String sourceText(File file) throws IOException {
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    static org.w3c.dom.Element manifestElement(org.w3c.dom.Document manifest, String tag, String name) {
        org.w3c.dom.NodeList list = manifest.getElementsByTagName(tag);
        for (int i = 0; i < list.getLength(); i++) {
            org.w3c.dom.Element e = (org.w3c.dom.Element) list.item(i);
            if (name.equals(e.getAttribute("android:name"))) return e;
        }
        return null;
    }

    static List<String> manifestNames(org.w3c.dom.Element parent, String tag) {
        List<String> names = new ArrayList<>();
        org.w3c.dom.NodeList list = parent.getElementsByTagName(tag);
        for (int i = 0; i < list.getLength(); i++) names.add(((org.w3c.dom.Element) list.item(i)).getAttribute("android:name"));
        return names;
    }

    static void updateWiring() throws Exception {
        String dir = System.getProperty("arr411.src");
        check(dir != null, "arr411.src is set (run test.ps1)");
        if (dir == null) return;
        File src = new File(dir).getAbsoluteFile();

        // The page reaches the update through methods that take nothing, so there is no argument to check.
        String bridge = sourceText(new File(src, "SpaBridge.java"));
        Matcher m = Pattern.compile("@JavascriptInterface\\s+public\\s+\\w+\\s+(update\\w*)\\(([^)]*)\\)").matcher(bridge);
        List<String> methods = new ArrayList<>();
        while (m.find()) {
            methods.add(m.group(1));
            eq("", m.group(2).trim(), "wiring: SpaBridge." + m.group(1) + " takes nothing from the page");
        }
        Collections.sort(methods);
        eq(Arrays.asList("updateCheck", "updateInfo", "updateInstall"), methods, "wiring: the update methods the page can call");

        // A new mode only counts once it is stored: the updater reads it from there.
        int save = bridge.indexOf("public void saveConfig(");
        check(save >= 0, "wiring: SpaBridge.saveConfig is there");
        if (save >= 0) {
            int next = bridge.indexOf("@JavascriptInterface", save);
            String body = bridge.substring(save, next < 0 ? bridge.length() : next);
            int stored = body.indexOf("storageMerge(");
            int told = body.indexOf("Updater.get(act).onModeChanged()");
            check(stored >= 0 && told > stored, "wiring: saveConfig tells the updater once the config is stored");
            check(Pattern.compile("if \\(p\\.has\\(\"autoUpdate\"\\)\\)\\s*Updater\\.get\\(act\\)\\.onModeChanged\\(\\)").matcher(body).find(),
                "wiring: only a patch that holds the mode wakes the updater");
        }

        File root = src.getParentFile().getParentFile().getParentFile().getParentFile();
        org.w3c.dom.Document manifest = javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(new File(root, "AndroidManifest.xml"));
        Set<String> permissions = new TreeSet<>(manifestNames(manifest.getDocumentElement(), "uses-permission"));
        check(permissions.containsAll(Arrays.asList("android.permission.INTERNET", "android.permission.REQUEST_INSTALL_PACKAGES",
            "android.permission.UPDATE_PACKAGES_WITHOUT_USER_ACTION", "android.permission.RECEIVE_BOOT_COMPLETED")), "wiring: the permissions of the update: " + permissions);

        org.w3c.dom.Element job = manifestElement(manifest, "service", ".UpdateJobService");
        check(job != null, "wiring: the job service is declared");
        if (job != null) eq("android.permission.BIND_JOB_SERVICE", job.getAttribute("android:permission"), "wiring: only the job scheduler can bind the job service");
        org.w3c.dom.Element replaced = manifestElement(manifest, "receiver", ".PackageReplacedReceiver");
        check(replaced != null, "wiring: the receiver of the new version is declared");
        if (replaced != null) eq(Arrays.asList("android.intent.action.MY_PACKAGE_REPLACED"), manifestNames(replaced, "action"), "wiring: it only hears about its own replacement");
        check(manifestElement(manifest, "receiver", ".InstallStatusReceiver") != null, "wiring: the receiver of the installer status is declared");

        // Apart from the tile, which only the system may bind (speedWiring), no service, receiver or provider is open to other apps: a component that is not exported can only be reached by the system.
        for (String tag : new String[]{"service", "receiver", "provider"}) {
            for (String name : manifestNames(manifest.getDocumentElement(), tag)) {
                if (!name.equals(".SpeedTile")) {
                    eq("false", manifestElement(manifest, tag, name).getAttribute("android:exported"), "wiring: " + name + " is not exported");
                }
                check(name.startsWith(".") && new File(src, name.substring(1) + ".java").isFile(), "wiring: the class of " + name + " exists");
            }
        }

        // In a Turkish or an Arabic locale, a case conversion or a number format without Locale.ROOT gives other text: the checks above run the pure classes in one, this covers the glue.
        File[] sources = src.listFiles((d, n) -> n.matches("Update.*\\.java|HashedCopy\\.java|InstallStatusReceiver\\.java|PackageReplacedReceiver\\.java"));
        check(sources != null && sources.length > 10, "wiring: the sources of the update are found");
        if (sources == null) return;
        for (File file : sources) {
            String text = sourceText(file);
            check(!Pattern.compile("\\.to(Lower|Upper)Case\\(\\s*\\)").matcher(text).find(), "wiring: " + file.getName() + " changes the case with Locale.ROOT");
            check(!Pattern.compile("String\\.format\\((?!\\s*(java\\.util\\.)?Locale\\.ROOT)").matcher(text).find(), "wiring: " + file.getName() + " formats with Locale.ROOT");
        }
    }

    // ---------- Speed tile: the decisions, then what cannot run here, checked in the sources ----------

    /** A qBittorrent that answers from a script and notes what it is asked: "METHOD path", then the form in brackets when there is a body. */
    static final class ScriptedServer implements QbSpeed.Server {
        final List<String> asked = new ArrayList<>();
        private final List<QbSpeed.Reply> script = new ArrayList<>();

        ScriptedServer reply(int status, String text) {
            script.add(new QbSpeed.Reply(status, text, null));
            return this;
        }

        ScriptedServer down() {
            script.add(new QbSpeed.Reply(0, "", "Connection refused"));
            return this;
        }

        @Override public QbSpeed.Reply send(String method, String path, String form) {
            asked.add(method + " " + path + (form == null ? "" : " [" + form + "]"));
            // A request nobody planned for shows in the list of what was asked.
            return script.isEmpty() ? new QbSpeed.Reply(599, "unplanned", null) : script.remove(0);
        }
    }

    static final String ASK_MODE = "GET /api/v2/transfer/speedLimitsMode";
    static final String ASK_FLIP = "POST /api/v2/transfer/toggleSpeedLimitsMode []";
    static final String ASK_LOGIN = "POST /api/v2/auth/login [username=u&password=p]";

    static void speedExchange(String name, ScriptedServer server, boolean flip, boolean loginAllowed, QbSpeed.Outcome outcome, boolean refused, String... asked) {
        QbSpeed.Result r = QbSpeed.exchange(server, flip, "u", "p", loginAllowed);
        eq(outcome, r.outcome, "qbspeed: " + name + ": outcome");
        eq(refused, r.credentialsRefused, "qbspeed: " + name + ": credentials refused");
        eq(Arrays.asList(asked), server.asked, "qbspeed: " + name + ": requests");
    }

    static void qbSpeed() {
        QbSpeed.Outcome on = QbSpeed.Outcome.ON;
        QbSpeed.Outcome off = QbSpeed.Outcome.OFF;
        QbSpeed.Outcome offline = QbSpeed.Outcome.OFFLINE;
        QbSpeed.Outcome denied = QbSpeed.Outcome.REFUSED;
        QbSpeed.Outcome unset = QbSpeed.Outcome.NOT_CONFIGURED;

        // The address: a reverse proxy's path stays, the slashes and spaces around it go, and only http(s) counts.
        eq("https://h/qbittorrent", QbSpeed.baseUrl("https://h/qbittorrent/"), "qbspeed: a reverse proxy's path is kept, without its last slash");
        eq("http://192.168.1.10:8080", QbSpeed.baseUrl("  http://192.168.1.10:8080//  "), "qbspeed: spaces and slashes around the address go");
        eq("HTTP://H", QbSpeed.baseUrl("HTTP://H"), "qbspeed: a scheme in capitals is a scheme");
        eq("https://h:8443", QbSpeed.baseUrl("https://h:8443"), "qbspeed: a port is kept");
        for (String bad : new String[]{null, "", "   ", "h:8080", "192.168.1.10:8080", "ftp://h", "http://", "https:///", "https://", "javascript:alert(1)", "http:/h", "file:///sdcard/x"}) {
            eq(null, QbSpeed.baseUrl(bad), "qbspeed: not an address: " + bad);
        }

        // The login travels as a form: whatever the user typed must arrive as typed.
        eq("username=admin&password=adminadmin", QbSpeed.loginForm("admin", "adminadmin"), "qbspeed: a plain login");
        eq("username=a%20b&password=p%26w%3Dd%25%2B%C3%A9", QbSpeed.loginForm("a b", "p&w=d%+\u00e9"), "qbspeed: the characters of a form and an accent are escaped");
        eq("username=-._~&password=AZaz09", QbSpeed.loginForm("-._~", "AZaz09"), "qbspeed: letters, digits and -._~ are left as they are");
        eq("username=%F0%9F%98%80&password=", QbSpeed.loginForm("\uD83D\uDE00", ""), "qbspeed: a character of four bytes");
        eq("username=&password=", QbSpeed.loginForm(null, null), "qbspeed: no credentials");
        check(QbSpeed.canLogin("admin", "x") && QbSpeed.canLogin("admin", "") && QbSpeed.canLogin("", "x"), "qbspeed: a user or a password is something to log in with");
        check(!QbSpeed.canLogin("", "") && !QbSpeed.canLogin(null, null) && !QbSpeed.canLogin(null, ""), "qbspeed: nothing is nothing to log in with");

        // What the server answers to the login.
        check(QbSpeed.loginAccepted(204, "") && QbSpeed.loginAccepted(204, null), "qbspeed: login accepted: 204");
        check(QbSpeed.loginAccepted(200, "Ok."), "qbspeed: login accepted: 200 Ok.");
        check(QbSpeed.loginAccepted(200, "ok"), "qbspeed: login accepted: 200 ok");
        check(QbSpeed.loginAccepted(200, " OK.\n"), "qbspeed: login accepted: capitals, a space and a line break");
        for (Object[] c : new Object[][]{{200, "Fails."}, {403, "Forbidden"}, {401, ""}, {500, "Ok."}, {200, ""}, {200, null}, {302, "ok"}}) {
            check(!QbSpeed.loginAccepted((Integer) c[0], (String) c[1]), "qbspeed: login not accepted: " + c[0] + " " + c[1]);
        }
        for (Object[] c : new Object[][]{{403, ""}, {401, ""}, {200, "Fails."}, {200, " fails\n"}}) {
            check(QbSpeed.loginRefused((Integer) c[0], (String) c[1]), "qbspeed: login refused: " + c[0] + " " + c[1]);
        }
        for (Object[] c : new Object[][]{{204, ""}, {200, "Ok."}, {200, ""}, {200, "<html>"}, {200, null}, {500, "oops"}, {502, "<html>"}, {500, "Fails."}}) {
            check(!QbSpeed.loginRefused((Integer) c[0], (String) c[1]), "qbspeed: login not refused: " + c[0] + " " + c[1]);
        }
        check(QbSpeed.needsLogin(401) && QbSpeed.needsLogin(403), "qbspeed: 401 and 403 ask for a login");
        check(!QbSpeed.needsLogin(200) && !QbSpeed.needsLogin(404) && !QbSpeed.needsLogin(500), "qbspeed: nothing else does");

        // What the server answers about the limits.
        eq(Boolean.TRUE, QbSpeed.parseMode(200, "1"), "qbspeed: 1 is on");
        eq(Boolean.FALSE, QbSpeed.parseMode(200, "0"), "qbspeed: 0 is off");
        eq(Boolean.TRUE, QbSpeed.parseMode(200, " 1\n"), "qbspeed: a space and a line break around the 1");
        for (Object[] c : new Object[][]{{200, "x"}, {500, "1"}, {403, "1"}, {200, ""}, {200, null}, {200, "10"}, {200, "true"}}) {
            eq(null, QbSpeed.parseMode((Integer) c[0], (String) c[1]), "qbspeed: not an answer about the limits: " + c[0] + " " + c[1]);
        }

        // The clocks.
        check(QbSpeed.stale(1_000, -1), "qbspeed: an answer that was never kept is old");
        check(!QbSpeed.stale(1_000, 1_000), "qbspeed: an answer that was just kept is fresh");
        check(!QbSpeed.stale(1_000 + 14_999, 1_000), "qbspeed: fresh until 15 seconds");
        check(QbSpeed.stale(1_000 + 15_000, 1_000), "qbspeed: old from 15 seconds");
        check(QbSpeed.stale(500, 1_000), "qbspeed: a clock that went back makes it old");
        check(QbSpeed.mayLogin(5, -1), "qbspeed: no refusal on record, so a login may be sent");
        check(!QbSpeed.mayLogin(1_000, 1_000), "qbspeed: no login right after a refusal");
        check(!QbSpeed.mayLogin(1_000 + 599_999, 1_000), "qbspeed: no login until 10 minutes");
        check(QbSpeed.mayLogin(1_000 + 600_000, 1_000), "qbspeed: a login again from 10 minutes");
        check(QbSpeed.mayLogin(500, 1_000), "qbspeed: a clock that went back lifts the wait");

        // The tile.
        QbSpeed.Look lit = QbSpeed.look(on);
        check(lit.active, "qbspeed: on is lit");
        eq("Activée", lit.subtitle, "qbspeed: on says Activée");
        eq("Désactivée", QbSpeed.look(off).subtitle, "qbspeed: off says Désactivée");
        eq("À configurer", QbSpeed.look(unset).subtitle, "qbspeed: not configured says À configurer");
        eq("Hors ligne", QbSpeed.look(offline).subtitle, "qbspeed: unreachable says Hors ligne");
        eq("Identifiants refusés", QbSpeed.look(denied).subtitle, "qbspeed: refused says Identifiants refusés");
        Set<String> lines = new TreeSet<>(Arrays.asList(QbSpeed.BUSY));
        for (QbSpeed.Outcome o : QbSpeed.Outcome.values()) {
            if (o != on) check(!QbSpeed.look(o).active, "qbspeed: dimmed unless on: " + o);
            lines.add(QbSpeed.look(o).subtitle);
        }
        eq(QbSpeed.Outcome.values().length + 1, lines.size(), "qbspeed: every state, and the wait, has a line of its own");

        eq(QbSpeed.Tap.SETTINGS, QbSpeed.tap(false, false, null), "qbspeed: tap: no address opens the settings");
        eq(QbSpeed.Tap.SETTINGS, QbSpeed.tap(false, false, on), "qbspeed: tap: an answer from before the address went doesn't count");
        eq(QbSpeed.Tap.IGNORE, QbSpeed.tap(true, true, on), "qbspeed: tap: a request is on its way");
        eq(QbSpeed.Tap.IGNORE, QbSpeed.tap(true, true, null), "qbspeed: tap: a request is on its way, nothing known");
        eq(QbSpeed.Tap.TOGGLE, QbSpeed.tap(true, false, on), "qbspeed: tap: on is flipped");
        eq(QbSpeed.Tap.TOGGLE, QbSpeed.tap(true, false, off), "qbspeed: tap: off is flipped");
        for (QbSpeed.Outcome o : new QbSpeed.Outcome[]{null, offline, denied, unset}) {
            eq(QbSpeed.Tap.REFRESH, QbSpeed.tap(true, false, o), "qbspeed: tap: a state that isn't known is read, never flipped: " + o);
        }

        // The exchange with the server.
        speedExchange("a session that is open", new ScriptedServer().reply(200, "1"), false, true, on, false, ASK_MODE);
        speedExchange("the limits are off", new ScriptedServer().reply(200, "0"), false, true, off, false, ASK_MODE);
        speedExchange("a flip, then a read", new ScriptedServer().reply(200, "").reply(200, "1"), true, true, on, false, ASK_FLIP, ASK_MODE);
        speedExchange("a flip answered with 204", new ScriptedServer().reply(204, "").reply(200, "0"), true, true, off, false, ASK_FLIP, ASK_MODE);
        speedExchange("a session that ended (403)", new ScriptedServer().reply(403, "Forbidden").reply(204, "").reply(200, "1"), false, true, on, false, ASK_MODE, ASK_LOGIN, ASK_MODE);
        speedExchange("a session that ended (401)", new ScriptedServer().reply(401, "").reply(204, "").reply(200, "0"), false, true, off, false, ASK_MODE, ASK_LOGIN, ASK_MODE);
        speedExchange("a login that answers Ok.", new ScriptedServer().reply(403, "").reply(200, "Ok.").reply(200, "0"), false, true, off, false, ASK_MODE, ASK_LOGIN, ASK_MODE);
        speedExchange("a flip that was turned away is sent once more, and only that one", new ScriptedServer().reply(403, "").reply(204, "").reply(200, "").reply(200, "0"),
            true, true, off, false, ASK_FLIP, ASK_LOGIN, ASK_FLIP, ASK_MODE);
        speedExchange("a session that ends between the flip and the read", new ScriptedServer().reply(200, "").reply(403, "").reply(204, "").reply(200, "1"),
            true, true, on, false, ASK_FLIP, ASK_MODE, ASK_LOGIN, ASK_MODE);
        speedExchange("a login that fails", new ScriptedServer().reply(403, "").reply(200, "Fails."), false, true, denied, true, ASK_MODE, ASK_LOGIN);
        speedExchange("a login turned down by a ban", new ScriptedServer().reply(403, "").reply(403, "Your IP address has been banned"), false, true, denied, true, ASK_MODE, ASK_LOGIN);
        speedExchange("a login that gets a server error", new ScriptedServer().reply(403, "").reply(500, "oops"), false, true, offline, false, ASK_MODE, ASK_LOGIN);
        speedExchange("a login that gets a page of a proxy", new ScriptedServer().reply(403, "").reply(200, "<html>"), false, true, offline, false, ASK_MODE, ASK_LOGIN);
        speedExchange("a session that is not kept after the login", new ScriptedServer().reply(403, "").reply(204, "").reply(403, ""), false, true, denied, true, ASK_MODE, ASK_LOGIN, ASK_MODE);
        speedExchange("a flip turned away again after the login", new ScriptedServer().reply(403, "").reply(204, "").reply(403, ""), true, true, denied, true, ASK_FLIP, ASK_LOGIN, ASK_FLIP);
        speedExchange("the login is sent once, however many requests", new ScriptedServer().reply(403, "").reply(204, "").reply(200, "").reply(403, ""),
            true, true, denied, false, ASK_FLIP, ASK_LOGIN, ASK_FLIP, ASK_MODE);
        speedExchange("a wait after a refusal", new ScriptedServer().reply(403, ""), false, false, denied, false, ASK_MODE);
        speedExchange("a server that can't be reached", new ScriptedServer().down(), false, true, offline, false, ASK_MODE);
        speedExchange("a flip to a server that can't be reached", new ScriptedServer().down(), true, true, offline, false, ASK_FLIP);
        speedExchange("a login that can't be sent", new ScriptedServer().reply(403, "").down(), false, true, offline, false, ASK_MODE, ASK_LOGIN);
        speedExchange("a request that can't be sent again", new ScriptedServer().reply(403, "").reply(204, "").down(), false, true, offline, false, ASK_MODE, ASK_LOGIN, ASK_MODE);
        speedExchange("a flip that the server turns down", new ScriptedServer().reply(500, "boom"), true, true, offline, false, ASK_FLIP);
        speedExchange("a flip whose read fails", new ScriptedServer().reply(200, "").down(), true, true, offline, false, ASK_FLIP, ASK_MODE);
        speedExchange("an answer that isn't 1 or 0", new ScriptedServer().reply(200, "<html>"), false, true, offline, false, ASK_MODE);
        speedExchange("an empty answer", new ScriptedServer().reply(200, ""), false, true, offline, false, ASK_MODE);
        speedExchange("a gateway error", new ScriptedServer().reply(502, "Bad Gateway"), false, true, offline, false, ASK_MODE);

        ScriptedServer open = new ScriptedServer().reply(200, "1");
        QbSpeed.Result noLogin = QbSpeed.exchange(open, false, "", "", true);
        eq(on, noLogin.outcome, "qbspeed: a server that needs no login works with no credentials");
        eq(Arrays.asList(ASK_MODE), open.asked, "qbspeed: and none is sent");
        ScriptedServer closed = new ScriptedServer().reply(403, "");
        QbSpeed.Result noCredentials = QbSpeed.exchange(closed, false, "", "", true);
        eq(denied, noCredentials.outcome, "qbspeed: a server that needs a login, with nothing to log in with");
        check(!noCredentials.credentialsRefused, "qbspeed: nothing was sent, so nothing was turned down");
        eq(Arrays.asList(ASK_MODE), closed.asked, "qbspeed: and no login is sent");
        ScriptedServer odd = new ScriptedServer().reply(403, "").reply(204, "").reply(200, "1");
        QbSpeed.exchange(odd, false, "\u00e9 x", "&=", true);
        eq(Arrays.asList(ASK_MODE, "POST /api/v2/auth/login [username=%C3%A9%20x&password=%26%3D]", ASK_MODE), odd.asked, "qbspeed: the login carries what was typed");

        // The wait after a refusal is kept per credentials, and ends with a state that was read.
        QbSpeed.Result turnedDown = QbSpeed.exchange(new ScriptedServer().reply(403, "").reply(200, "Fails."), false, "u", "p", true);
        QbSpeed.Result read = QbSpeed.exchange(new ScriptedServer().reply(200, "1"), false, "u", "p", true);
        QbSpeed.Result unreachable = QbSpeed.exchange(new ScriptedServer().down(), false, "u", "p", true);
        QbSpeed.LoginGate gate = new QbSpeed.LoginGate();
        check(gate.allowed(1, 100), "qbspeed: gate: nothing refused yet");
        gate.done(1, 100, turnedDown);
        check(!gate.allowed(1, 101), "qbspeed: gate: the same credentials wait");
        check(gate.allowed(2, 101), "qbspeed: gate: other credentials don't");
        check(!gate.allowed(1, 100 + 599_999), "qbspeed: gate: the wait is 10 minutes");
        check(gate.allowed(1, 100 + 600_000), "qbspeed: gate: and then over");
        gate.done(1, 200, unreachable);
        check(!gate.allowed(1, 201), "qbspeed: gate: a server that can't be reached doesn't lift it");
        gate.done(2, 202, read);
        check(!gate.allowed(1, 203), "qbspeed: gate: another credentials' success doesn't lift it");
        gate.done(1, 204, read);
        check(gate.allowed(1, 205), "qbspeed: gate: a state read with these credentials lifts it");
        gate.done(3, 300, turnedDown);
        check(gate.allowed(1, 301) && !gate.allowed(3, 301), "qbspeed: gate: the last refusal is the one that is kept");
    }

    static org.w3c.dom.Element firstElement(org.w3c.dom.Element parent, String tag) {
        org.w3c.dom.NodeList list = parent.getElementsByTagName(tag);
        return list.getLength() == 0 ? null : (org.w3c.dom.Element) list.item(0);
    }

    static void speedWiring() throws Exception {
        String dir = System.getProperty("arr411.src");
        check(dir != null, "arr411.src is set (run test.ps1)");
        if (dir == null) return;
        File src = new File(dir).getAbsoluteFile();
        File root = src.getParentFile().getParentFile().getParentFile().getParentFile();
        javax.xml.parsers.DocumentBuilder xml = javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder();
        org.w3c.dom.Document manifest = xml.parse(new File(root, "AndroidManifest.xml"));
        String pkg = manifest.getDocumentElement().getAttribute("package");

        // The system binds the tile, and nobody else may: the permission is the lock on the service, not one the app asks for.
        org.w3c.dom.Element tile = manifestElement(manifest, "service", ".SpeedTile");
        check(tile != null, "speed: the tile service is declared");
        if (tile != null) {
            eq("true", tile.getAttribute("android:exported"), "speed: the tile is exported, for the system to find it");
            eq("android.permission.BIND_QUICK_SETTINGS_TILE", tile.getAttribute("android:permission"), "speed: only the system can bind the tile");
            eq(Arrays.asList("android.service.quicksettings.action.QS_TILE"), manifestNames(tile, "action"), "speed: the tile hears the action of the tiles");
            org.w3c.dom.Element toggles = firstElement(tile, "meta-data");
            check(toggles != null && "android.service.quicksettings.TOGGLEABLE_TILE".equals(toggles.getAttribute("android:name")) && "true".equals(toggles.getAttribute("android:value")),
                "speed: the tile says that it toggles");
        }
        check(!manifestNames(manifest.getDocumentElement(), "uses-permission").contains("android.permission.BIND_QUICK_SETTINGS_TILE"), "speed: the app doesn't ask for the permission that guards the tile");

        // The hop of the shortcuts and of the long press on the tile: no task of its own, and nothing on the screen.
        org.w3c.dom.Element hop = manifestElement(manifest, "activity", ".ShortcutActivity");
        check(hop != null, "speed: the trampoline is declared");
        if (hop != null) {
            eq("true", hop.getAttribute("android:exported"), "speed: the trampoline can be started by the launcher");
            check(hop.hasAttribute("android:taskAffinity") && hop.getAttribute("android:taskAffinity").isEmpty(), "speed: the trampoline has no task to join");
            eq("true", hop.getAttribute("android:excludeFromRecents"), "speed: the trampoline stays out of the recent apps");
            eq("true", hop.getAttribute("android:noHistory"), "speed: the trampoline leaves no history");
            eq("@android:style/Theme.NoDisplay", hop.getAttribute("android:theme"), "speed: the trampoline shows nothing");
            eq(Arrays.asList("android.service.quicksettings.action.QS_TILE_PREFERENCES"), manifestNames(hop, "action"), "speed: a long press on the tile ends up in the trampoline");
        }
        String hopSource = sourceText(new File(src, "ShortcutActivity.java"));
        check(hopSource.contains("MainActivity.isTab("), "speed: the trampoline only passes on a tab that exists");
        check(Pattern.compile("finally\\s*\\{\\s*(//[^\\n]*\\s*)*finish\\(\\)").matcher(hopSource).find(), "speed: the trampoline always finishes, or the system throws");

        // The shortcuts: five tabs, in order, each through the trampoline.
        org.w3c.dom.Element main = manifestElement(manifest, "activity", ".MainActivity");
        boolean linked = false;
        if (main != null) {
            org.w3c.dom.NodeList metas = main.getElementsByTagName("meta-data");
            for (int i = 0; i < metas.getLength(); i++) {
                org.w3c.dom.Element meta = (org.w3c.dom.Element) metas.item(i);
                linked |= "android.app.shortcuts".equals(meta.getAttribute("android:name")) && "@xml/shortcuts".equals(meta.getAttribute("android:resource"));
            }
        }
        check(linked, "speed: the launcher activity points to the shortcuts");

        Matcher isTab = Pattern.compile("static boolean isTab\\(String t\\)\\s*\\{(.*?)\\}", Pattern.DOTALL).matcher(sourceText(new File(src, "MainActivity.java")));
        List<String> tabs = new ArrayList<>();
        if (isTab.find()) {
            Matcher name = Pattern.compile("\"(\\w+)\"\\.equals\\(t\\)").matcher(isTab.group(1));
            while (name.find()) tabs.add(name.group(1));
        }
        check(tabs.size() >= 6, "speed: the tabs of the app are found: " + tabs);

        org.w3c.dom.NodeList list = xml.parse(new File(root, "res/xml/shortcuts.xml")).getElementsByTagName("shortcut");
        List<String> shown = new ArrayList<>();
        for (int i = 0; i < list.getLength(); i++) {
            org.w3c.dom.Element shortcut = (org.w3c.dom.Element) list.item(i);
            org.w3c.dom.Element intent = firstElement(shortcut, "intent");
            org.w3c.dom.Element extra = firstElement(shortcut, "extra");
            check(intent != null && extra != null, "speed: shortcut " + i + " has an intent with an extra");
            if (intent == null || extra == null) continue;
            eq(pkg, intent.getAttribute("android:targetPackage"), "speed: shortcut " + i + " stays in the app");
            eq(pkg + ".ShortcutActivity", intent.getAttribute("android:targetClass"), "speed: shortcut " + i + " goes through the trampoline");
            eq("tab", extra.getAttribute("android:name"), "speed: shortcut " + i + " names a tab");
            shown.add(extra.getAttribute("android:value"));
        }
        eq(Arrays.asList("qbit", "c411", "radarr", "sonarr", "lidarr"), shown, "speed: the shortcuts, in order");
        check(tabs.containsAll(shown), "speed: every shortcut opens a tab that exists: " + shown + " in " + tabs);
    }

    public static void main(String[] args) throws Exception {
        bodyReader();
        logFile();
        redact();
        redactPasskeys();
        diagText();
        restartDelay();
        c411Urls();
        fetchTargets();
        externalUrls();
        publicAddresses();
        redirects();
        pageGate();
        messageSizes();
        lruThrottle();
        consoleBudget();
        fileNames();
        saveText();
        cookieJars();
        bridgeKey();
        bridgeMethods();
        updateVersions();
        updateJson();
        updateIntegrity();
        hashedCopy();
        updateHosts();
        updateRelease();
        updateThrottle();
        updateFiles();
        updateInstallStatus();
        updateFlow();
        updateState();
        qbSpeed();
        updateWiring();
        speedWiring();
        System.out.println("passed " + passed + ", failed " + failed);
        System.exit(failed == 0 ? 0 : 1);
    }
}

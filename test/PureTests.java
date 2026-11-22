package dev.c411.qbittorrent;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

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
        cookieJars();
        System.out.println("passed " + passed + ", failed " + failed);
        System.exit(failed == 0 ? 0 : 1);
    }
}

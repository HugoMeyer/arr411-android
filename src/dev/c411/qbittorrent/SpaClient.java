package dev.c411.qbittorrent;

import android.graphics.Bitmap;
import android.net.Uri;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/** Serves the web app from assets/app at https://arr411.local/app/ and proxies Radarr/Sonarr/Lidarr covers (/mc). */
final class SpaClient extends WebViewClient {
    static final String HOST = "arr411.local";
    static final String START = "https://arr411.local/app/index.html";
    private static final long COVER_TTL = 7L * 24 * 3600 * 1000;
    private static final int PRUNE_EVERY = 64;
    private static final long TMP_STALE_MS = 60_000;
    private static final AtomicInteger writes = new AtomicInteger();

    private final MainActivity act;
    private final File covers;

    SpaClient(MainActivity act) {
        this.act = act;
        covers = new File(act.getCacheDir(), "covers");
        //noinspection ResultOfMethodCallIgnored
        covers.mkdirs();
    }

    @Override
    public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
        Uri u = req.getUrl();
        if (HOST.equals(u.getHost())) return false;
        act.openExternal(u.toString());
        return true;
    }

    @Override
    public void onPageStarted(WebView view, String url, Bitmap favicon) {
        act.onSpaStarted();
    }

    @Override
    public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
        return act.onRendererGone("app", detail);
    }

    @Override
    public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest req) {
        try {
            Uri u = req.getUrl();
            if (!HOST.equals(u.getHost())) return null;
            String path = u.getPath() == null ? "" : u.getPath();
            if (path.startsWith("/app/")) return asset(path.substring(5));
            if (path.equals("/mc")) return cover(u);
            return status(404, "Not Found");
        } catch (RuntimeException | OutOfMemoryError e) {
            // Runs on the WebView's own threads, where a failure would end the app.
            Diag.problem("intercept", "serving a page request failed", e);
            return status(500, "Internal Error");
        }
    }

    private WebResourceResponse asset(String rel) {
        if (rel.isEmpty()) rel = "index.html";
        if (rel.contains("..")) return status(404, "Not Found");
        try {
            InputStream in = act.getAssets().open("app/" + rel);
            String mime = mime(rel);
            boolean text = mime.startsWith("text/") || mime.contains("javascript") || mime.contains("json") || mime.contains("svg");
            Map<String, String> h = new HashMap<>();
            h.put("Cache-Control", "no-cache");
            return new WebResourceResponse(mime, text ? "UTF-8" : null, 200, "OK", h, in);
        } catch (Exception e) {
            return status(404, "Not Found");
        }
    }

    private static String mime(String name) {
        String n = name.toLowerCase();
        if (n.endsWith(".html")) return "text/html";
        if (n.endsWith(".js")) return "application/javascript";
        if (n.endsWith(".css")) return "text/css";
        if (n.endsWith(".svg")) return "image/svg+xml";
        if (n.endsWith(".json")) return "application/json";
        if (n.endsWith(".png")) return "image/png";
        if (n.endsWith(".webp")) return "image/webp";
        if (n.endsWith(".gif")) return "image/gif";
        if (n.endsWith(".woff2")) return "font/woff2";
        return n.endsWith(".jpg") || n.endsWith(".jpeg") ? "image/jpeg" : "application/octet-stream";
    }

    private static WebResourceResponse status(int code, String reason) {
        return new WebResourceResponse("text/plain", "UTF-8", code, reason, new HashMap<>(),
                new ByteArrayInputStream(new byte[0]));
    }

    // ---------- Covers: the API key stays native; images are cached on disk for a week ----------

    private WebResourceResponse cover(Uri u) {
        String svc = u.getQueryParameter("s");
        String target = u.getQueryParameter("u");
        if (target == null || !("radarr".equals(svc) || "sonarr".equals(svc) || "lidarr".equals(svc))) return status(400, "Bad Request");
        JSONObject cfg = act.engine.config();
        String base = cfg.optString(svc + "Url", "").trim().replaceAll("/+$", "");
        String key = cfg.optString(svc + "Key", "").trim();
        if (base.isEmpty() || key.isEmpty() || !target.startsWith(base + "/")) return status(403, "Forbidden");
        File f = new File(covers, sha1(target));
        if (f.isFile() && System.currentTimeMillis() - f.lastModified() < COVER_TTL) return image(f, target);
        byte[] data = fetch(target, key);
        if (data != null) {
            File tmp = new File(covers, f.getName() + ".tmp" + Thread.currentThread().getId());
            try (FileOutputStream os = new FileOutputStream(tmp)) {
                os.write(data);
            } catch (Exception ignored) {
            }
            if (!tmp.renameTo(f)) //noinspection ResultOfMethodCallIgnored
                tmp.delete();
            else if (writes.incrementAndGet() % PRUNE_EVERY == 0) act.engine.tryIo(() -> prune(act.getCacheDir()));
            return new WebResourceResponse(mime(target), null, new ByteArrayInputStream(data));
        }
        return f.isFile() ? image(f, target) : status(404, "Not Found"); // stale copy when offline
    }

    private static WebResourceResponse image(File f, String target) {
        try {
            return new WebResourceResponse(mime(target), null, new FileInputStream(f));
        } catch (Exception e) {
            return status(404, "Not Found");
        }
    }

    private static byte[] fetch(String url, String key) {
        HttpURLConnection c = null;
        try {
            URL current = new URL(url);
            for (int hops = 0; ; hops++) {
                c = (HttpURLConnection) current.openConnection();
                c.setInstanceFollowRedirects(false);
                c.setConnectTimeout(15000);
                c.setReadTimeout(30000);
                c.setRequestProperty("X-Api-Key", key);
                int status = c.getResponseCode();
                if (!RedirectPolicy.isRedirect(status)) {
                    if (status != 200) return null;
                    break;
                }
                // The key travels only to the server it was meant for.
                URL next = RedirectPolicy.resolve(current, c.getHeaderField("Location"));
                c.disconnect();
                c = null;
                if (next == null || hops >= RedirectPolicy.MAX_REDIRECTS) return null;
                if (!RedirectPolicy.staysOnServer(current, next)) {
                    Diag.problem("cover-redirect", RedirectPolicy.refusal(next), null);
                    return null;
                }
                current = next;
            }
            String ct = c.getContentType();
            if (ct != null && !ct.startsWith("image/")) return null;
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (InputStream in = c.getInputStream()) {
                byte[] b = new byte[16384];
                for (int n; (n = in.read(b)) > 0; ) {
                    out.write(b, 0, n);
                    if (out.size() > 15 * 1024 * 1024) return null;
                }
            }
            return out.size() > 0 ? out.toByteArray() : null;
        } catch (OutOfMemoryError e) {
            Diag.problem("cover-memory", "not enough memory to load a cover", e);
            return null;
        } catch (Exception e) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private static String sha1(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-1").digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder b = new StringBuilder();
            for (byte x : d) b.append(String.format("%02x", x));
            return b.toString();
        } catch (Exception e) {
            return Integer.toHexString(s.hashCode()) + "_" + s.length();
        }
    }

    /** Keeps the cover cache under ~150 MB (oldest files go first). Blocking. */
    static void prune(File cacheDir) {
        File[] files = new File(cacheDir, "covers").listFiles();
        if (files == null) return;
        long now = System.currentTimeMillis();
        List<File> kept = new ArrayList<>();
        long total = 0;
        for (File f : files) {
            if (!f.getName().contains(".tmp")) {
                kept.add(f);
                total += f.length();
            } else if (now - f.lastModified() > TMP_STALE_MS) { // not a copy being written right now
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
        }
        if (total < 150L * 1024 * 1024) return;
        kept.sort((a, b) -> Long.compare(a.lastModified(), b.lastModified()));
        for (File f : kept) {
            if (total < 100L * 1024 * 1024) break;
            total -= f.length();
            //noinspection ResultOfMethodCallIgnored
            f.delete();
        }
    }
}

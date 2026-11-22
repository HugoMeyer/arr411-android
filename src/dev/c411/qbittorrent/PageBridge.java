package dev.c411.qbittorrent;

import android.app.DownloadManager;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.net.Uri;
import android.os.Environment;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.util.Base64;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URL;

/**
 * window.QbtPage for the C411 tab: the injected extension talks to background.js through this. The page runs scripts of third parties and
 * addJavascriptInterface exposes this object to every frame, so nothing here trusts the caller: every request goes through {@link PageGate}
 * (a real touch, a visible tab, rate limits) and the sizes are capped before anything is parsed.
 */
final class PageBridge {
    private static final int FILE_LIMIT = 16 * 1024 * 1024;
    private static final int BASE64_LIMIT = (FILE_LIMIT + 2) / 3 * 4;
    private static final String TOO_BIG = "Message trop volumineux";
    private static final String FETCH_REFUSED = "Adresse refusée";

    private final MainActivity act;
    private final Engine engine;
    final PageGate gate = new PageGate();
    volatile String userAgent = "";

    PageBridge(MainActivity act) {
        this.act = act;
        this.engine = act.engine;
    }

    @JavascriptInterface
    public void send(String msgJson, String id) {
        if (msgJson == null || msgJson.isEmpty()) {
            refuse(id, PageGate.REFUSED);
            return;
        }
        if (!PageGate.sizeAllowed(msgJson, null)) {
            refuse(id, TOO_BIG);
            return;
        }
        boolean visible = act.c411Visible();
        boolean big = msgJson.length() > PageGate.MESSAGE_LIMIT;
        if (big) {
            // Only an upload can be this big: the gate answers before the text is parsed.
            String error = gate.admit("upload", SystemClock.elapsedRealtime(), visible);
            if (error != null) {
                refuse(id, error);
                return;
            }
        }
        String safe;
        try {
            JSONObject m = new JSONObject(msgJson);
            // Anything else (including the internal messages) counts toward the rate limit and is refused by the gate.
            String type = m.optString("type");
            if (!PageGate.isPageMessage(type)) type = "";
            if (!PageGate.sizeAllowed(msgJson, type)) {
                refuse(id, TOO_BIG);
                return;
            }
            String error = big ? null : gate.admit(type, SystemClock.elapsedRealtime(), visible);
            if (error != null) {
                refuse(id, error);
                return;
            }
            // Re-serialised so that the engine reads exactly what the gate has just checked; the engine validates every field itself.
            safe = m.toString();
        } catch (Throwable e) {
            boolean memory = e instanceof OutOfMemoryError;
            if (memory) Diag.problem("page-message-memory", "out of memory while reading a message of the page", e);
            refuse(id, memory ? "Mémoire insuffisante" : PageGate.REFUSED);
            return;
        }
        engine.send(safe, json -> reply(id, json));
    }

    @JavascriptInterface
    public void download(String url, String filename) {
        if (url == null || url.length() > UrlPolicy.MAX_URL) return;
        String error = gate.admit("download", SystemClock.elapsedRealtime(), act.c411Visible());
        if (error != null) {
            refused(error);
            return;
        }
        String name = filename == null || filename.length() <= 255 ? filename : filename.substring(0, 255);
        act.runOnUiThread(() -> sendDownloadToEngine(url, name, false));
    }

    /** Saves a .torrent the page read from a blob: the app asked for (see saveLocally): nothing else is written to Downloads on its word. */
    @JavascriptInterface
    public void saveFile(String name, String base64, String mime) {
        String error = gate.admit("saveFile", SystemClock.elapsedRealtime(), act.c411Visible());
        if (error != null) {
            refused(error);
            return;
        }
        String file = FileNames.torrent(name);
        try {
            if (base64 == null || base64.length() > BASE64_LIMIT) throw new Exception("fichier trop volumineux");
            byte[] data = Base64.decode(base64, Base64.DEFAULT);
            // A .torrent is a bencoded dictionary: it always starts with 'd'.
            if (data.length == 0 || data[0] != 'd') throw new Exception("ce n’est pas un fichier .torrent");
            ContentValues v = new ContentValues();
            v.put(MediaStore.Downloads.DISPLAY_NAME, file);
            v.put(MediaStore.Downloads.MIME_TYPE, "application/x-bittorrent");
            ContentResolver cr = act.getContentResolver();
            Uri uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
            if (uri == null) throw new Exception("stockage indisponible");
            try (OutputStream os = cr.openOutputStream(uri)) {
                if (os == null) throw new Exception("stockage indisponible");
                os.write(data);
            }
            act.toast("Enregistré dans Téléchargements : " + file);
        } catch (Throwable e) {
            String why = e instanceof OutOfMemoryError ? "fichier trop volumineux" : e.getMessage();
            act.toast("Enregistrement impossible : " + (why == null ? "erreur inconnue" : why));
        }
    }

    @JavascriptInterface
    public void toast(String text) {
        String shown = PageGate.toastText(text);
        if (shown != null && gate.allowToast(SystemClock.elapsedRealtime(), act.c411Visible())) act.toast(shown);
    }

    // Native download with the WebView's cookies: avoids page CORS limits on .torrent URLs. Only C411 itself, and every redirect is checked again.
    @JavascriptInterface
    public void fetchBytes(String url, String referer, String id) {
        String error = gate.admit("fetchBytes", SystemClock.elapsedRealtime(), act.c411Visible());
        if (error == null && !UrlPolicy.isFetchTarget(url)) error = FETCH_REFUSED;
        if (error == null && !gate.beginFetch()) error = PageGate.TOO_MANY_FETCHES;
        if (error != null) {
            reply(id, "{\"error\":" + JSONObject.quote(error) + "}");
            return;
        }
        final String ua = userAgent;
        final String from = UrlPolicy.isC411Page(referer) ? referer : null;
        boolean queued = engine.tryIo(() -> {
            try {
                fetch(url, from, ua, id);
            } finally {
                gate.endFetch();
            }
        });
        if (!queued) {
            gate.endFetch();
            reply(id, "{\"error\":" + JSONObject.quote(Engine.BUSY) + "}");
        }
    }

    private void fetch(String url, String referer, String ua, String id) {
        JSONObject r = new JSONObject();
        HttpURLConnection c = null;
        try {
            URL cur = new URL(url);
            for (int hop = 0; ; hop++) {
                if (!UrlPolicy.isFetchTarget(cur.toString())) throw new Exception(FETCH_REFUSED);
                requirePublic(cur.getHost());
                c = (HttpURLConnection) cur.openConnection();
                c.setInstanceFollowRedirects(false);
                c.setConnectTimeout(20000);
                c.setReadTimeout(30000);
                String ck = CookieManager.getInstance().getCookie(cur.toString());
                if (ck != null) c.setRequestProperty("Cookie", ck);
                c.setRequestProperty("User-Agent", ua);
                if (referer != null) c.setRequestProperty("Referer", referer);
                int st = c.getResponseCode();
                if (RedirectPolicy.isRedirect(st)) {
                    String location = c.getHeaderField("Location");
                    c.disconnect();
                    c = null;
                    URL next = location == null ? null : RedirectPolicy.resolve(cur, location);
                    if (next == null) throw new Exception("Redirection invalide");
                    if (hop >= RedirectPolicy.MAX_REDIRECTS) throw new Exception("Trop de redirections");
                    cur = next;
                    continue;
                }
                if (c.getContentLengthLong() > FILE_LIMIT) throw new Exception("Fichier trop volumineux");
                InputStream in = st >= 400 ? c.getErrorStream() : c.getInputStream();
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                if (in != null) {
                    byte[] buf = new byte[16384];
                    for (int n; (n = in.read(buf)) > 0; ) {
                        out.write(buf, 0, n);
                        // A torrent file is a few hundred KB; the answer goes through base64 and JSON, which multiply it.
                        if (out.size() > FILE_LIMIT) throw new Exception("Fichier trop volumineux");
                    }
                    in.close();
                }
                r.put("status", st).put("base64", Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP));
                break;
            }
        } catch (Exception | OutOfMemoryError e) {
            boolean memory = e instanceof OutOfMemoryError;
            if (memory) Diag.serious("out of memory while fetching a file for the page", e, null);
            try {
                r.remove("status");
                r.remove("base64");
                r.put("error", memory ? "Fichier trop volumineux" : String.valueOf(e.getMessage()));
            } catch (Exception ignored) {
            }
        } finally {
            if (c != null) c.disconnect();
        }
        reply(id, r.toString());
    }

    /** C411 has to resolve to public addresses: a name that points into the user's network is refused. Best effort: the connection resolves it again. */
    private static void requirePublic(String host) throws Exception {
        for (InetAddress a : InetAddress.getAllByName(host)) {
            // 198.18.0.0/15 is what VPN apps answer with when they route the names themselves.
            if (!UrlPolicy.isPublicAddress(a.getAddress(), true)) throw new Exception(FETCH_REFUSED);
        }
    }

    private void refuse(String id, String error) {
        reply(id, "{\"ok\":false,\"error\":" + JSONObject.quote(error) + "}");
    }

    /** A request that came without a real touch (or too often): say so once in a while, in the app's own words. */
    private void refused(String error) {
        if (gate.allowToast(SystemClock.elapsedRealtime(), act.c411Visible())) act.toast(error);
        Diag.problem("page-refused", "request of the page refused: " + error, null);
    }

    private void reply(String id, String json) {
        act.c411Js("window.__qbtReply && __qbtReply(" + JSONObject.quote(id) + "," + JSONObject.quote(json) + ")");
    }

    // ---------- Downloads started by the page ----------

    /** WebView download listener: the page hook normally catches armed downloads; this is the fallback. */
    void onDownload(String url, String filename) {
        if (url == null || url.length() > UrlPolicy.MAX_URL) return;
        String error = gate.admit("download", SystemClock.elapsedRealtime(), act.c411Visible());
        if (error != null) {
            refused(error);
            return;
        }
        sendDownloadToEngine(url, filename, true);
    }

    private void sendDownloadToEngine(String url, String filename, boolean saveIfNotArmed) {
        try {
            String msg = new JSONObject().put("type", "__download").put("url", url).put("filename", filename).toString();
            engine.send(msg, res -> {
                boolean handled = false;
                try {
                    handled = new JSONObject(res).optBoolean("handled");
                } catch (Exception ignored) {
                }
                if (!handled && saveIfNotArmed) act.runOnUiThread(() -> saveLocally(url, filename));
                if (!handled && !saveIfNotArmed) act.toast("Délai dépassé : réessaie.");
            });
        } catch (Exception ignored) {
        }
    }

    private void saveLocally(String url, String filename) {
        if (url.startsWith("blob:")) {
            gate.grantSave(SystemClock.elapsedRealtime());
            act.c411Js("window.__qbtSaveBlob && __qbtSaveBlob(" + JSONObject.quote(url) + "," + JSONObject.quote(filename) + ")");
            return;
        }
        if (!url.regionMatches(true, 0, "https://", 0, 8) && !url.regionMatches(true, 0, "http://", 0, 7)) return;
        try {
            String file = FileNames.clean(filename);
            DownloadManager.Request r = new DownloadManager.Request(Uri.parse(url));
            String cookies = CookieManager.getInstance().getCookie(url);
            if (cookies != null) r.addRequestHeader("Cookie", cookies);
            r.addRequestHeader("User-Agent", userAgent);
            r.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            r.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, file);
            act.getSystemService(DownloadManager.class).enqueue(r);
            act.toast("Téléchargement : " + file);
        } catch (Exception e) {
            act.toast("Téléchargement impossible : " + e.getMessage());
        }
    }
}

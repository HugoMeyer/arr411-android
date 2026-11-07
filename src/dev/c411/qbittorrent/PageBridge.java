package dev.c411.qbittorrent;

import android.app.DownloadManager;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Base64;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/** window.QbtPage for the C411 tab: the injected extension talks to background.js through this. */
final class PageBridge {
    // Only these messages may come from the C411 page (no settings, no arbitrary servers).
    private static final Set<String> PAGE_TYPES = new HashSet<>(Arrays.asList(
            "getCategories", "pageStatus", "qbitIndex", "arm", "disarm", "upload", "arrMeta"));

    private final MainActivity act;
    private final Engine engine;
    volatile String userAgent = "";

    PageBridge(MainActivity act) {
        this.act = act;
        this.engine = act.engine;
    }

    @JavascriptInterface
    public void send(String msgJson, String id) {
        String safe;
        try {
            JSONObject m = new JSONObject(msgJson);
            if (!PAGE_TYPES.contains(m.optString("type"))) throw new Exception("Message refusé");
            m.remove("cfg"); // arrMeta always uses the saved settings
            safe = m.toString();
        } catch (Exception e) {
            reply(id, "{\"ok\":false,\"error\":\"Message refusé\"}");
            return;
        }
        engine.send(safe, json -> reply(id, json));
    }

    @JavascriptInterface
    public void download(String url, String filename) {
        act.runOnUiThread(() -> sendDownloadToEngine(url, filename, false));
    }

    @JavascriptInterface
    public void saveFile(String name, String base64, String mime) {
        try {
            ContentValues v = new ContentValues();
            v.put(MediaStore.Downloads.DISPLAY_NAME, name.replaceAll("[\\\\/:*?\"<>|]", "_"));
            v.put(MediaStore.Downloads.MIME_TYPE, mime);
            ContentResolver cr = act.getContentResolver();
            Uri uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
            if (uri == null) throw new Exception("stockage indisponible");
            try (OutputStream os = cr.openOutputStream(uri)) {
                if (os == null) throw new Exception("stockage indisponible");
                os.write(Base64.decode(base64, Base64.DEFAULT));
            }
            act.toast("Enregistré dans Téléchargements : " + name);
        } catch (Exception e) {
            act.toast("Enregistrement impossible : " + e.getMessage());
        }
    }

    @JavascriptInterface
    public void toast(String text) {
        act.toast(text);
    }

    // Native download with the WebView's cookies: avoids page CORS limits on .torrent URLs.
    @JavascriptInterface
    public void fetchBytes(String url, String referer, String id) {
        final String ua = userAgent;
        engine.io().execute(() -> {
            JSONObject r = new JSONObject();
            HttpURLConnection c = null;
            try {
                String cur = url;
                for (int hop = 0; hop < 6; hop++) {
                    if (!cur.startsWith("http://") && !cur.startsWith("https://")) throw new Exception("URL non supportée");
                    c = (HttpURLConnection) new URL(cur).openConnection();
                    c.setInstanceFollowRedirects(false);
                    c.setConnectTimeout(20000);
                    c.setReadTimeout(30000);
                    String ck = CookieManager.getInstance().getCookie(cur);
                    if (ck != null) c.setRequestProperty("Cookie", ck);
                    c.setRequestProperty("User-Agent", ua);
                    if (referer != null && !referer.isEmpty()) c.setRequestProperty("Referer", referer);
                    int st = c.getResponseCode();
                    if (st >= 300 && st < 400 && c.getHeaderField("Location") != null) {
                        cur = new URL(new URL(cur), c.getHeaderField("Location")).toString();
                        c.disconnect();
                        c = null;
                        continue;
                    }
                    InputStream in = st >= 400 ? c.getErrorStream() : c.getInputStream();
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    if (in != null) {
                        byte[] buf = new byte[16384];
                        for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
                        in.close();
                    }
                    r.put("status", st).put("base64", Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP));
                    break;
                }
                if (!r.has("status")) r.put("error", "Trop de redirections");
            } catch (Exception e) {
                try {
                    r.put("error", String.valueOf(e.getMessage()));
                } catch (Exception ignored) {
                }
            } finally {
                if (c != null) c.disconnect();
            }
            reply(id, r.toString());
        });
    }

    private void reply(String id, String json) {
        act.c411Js("window.__qbtReply && __qbtReply(" + JSONObject.quote(id) + "," + JSONObject.quote(json) + ")");
    }

    // ---------- Downloads started by the page ----------

    /** WebView download listener: the page hook normally catches armed downloads; this is the fallback. */
    void onDownload(String url, String filename) {
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
            act.c411Js("window.__qbtSaveBlob && __qbtSaveBlob(" + JSONObject.quote(url) + "," + JSONObject.quote(filename) + ")");
            return;
        }
        if (!url.startsWith("http")) return;
        try {
            DownloadManager.Request r = new DownloadManager.Request(Uri.parse(url));
            String cookies = CookieManager.getInstance().getCookie(url);
            if (cookies != null) r.addRequestHeader("Cookie", cookies);
            r.addRequestHeader("User-Agent", userAgent);
            r.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            r.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, filename);
            act.getSystemService(DownloadManager.class).enqueue(r);
            act.toast("Téléchargement : " + filename);
        } catch (Exception e) {
            act.toast("Téléchargement impossible : " + e.getMessage());
        }
    }
}

package dev.c411.qbittorrent;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.CookieHandler;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/** Runs the extension's background.js in a hidden WebView and gives it native HTTP, storage and notifications. */
public final class Engine {
    public interface Callback { void done(String json); }
    public interface EventSink { void onEvent(String json); }

    private static Engine instance;

    public static synchronized Engine get(Context c) {
        if (instance == null) instance = new Engine(c.getApplicationContext());
        return instance;
    }

    private final Context ctx;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService pool = Executors.newCachedThreadPool();
    private final SharedPreferences prefs;
    private final Map<String, Callback> pending = new ConcurrentHashMap<>();
    private final List<Runnable> queue = new ArrayList<>();
    private final AtomicInteger seq = new AtomicInteger();
    private final AtomicInteger notifId = new AtomicInteger(100);
    private WebView wv; // main thread only
    private boolean ready = false;
    public volatile EventSink sink;

    private Engine(Context ctx) {
        this.ctx = ctx;
        prefs = ctx.getSharedPreferences("config", Context.MODE_PRIVATE);
        CookieHandler.setDefault(new CookieManager(null, CookiePolicy.ACCEPT_ALL));
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel("done", "Téléchargements terminés", NotificationManager.IMPORTANCE_DEFAULT));
        wv = createWebView();
    }

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    private WebView createWebView() {
        WebView w = new WebView(ctx);
        w.getSettings().setJavaScriptEnabled(true);
        w.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return true; // the engine never navigates
            }

            @Override
            public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
                // Renderer killed (low memory): rebuild the engine instead of crashing the app.
                if (view == wv) {
                    ready = false;
                    wv = null;
                    queue.clear();
                    failPending();
                    main.post(() -> wv = createWebView());
                }
                view.destroy();
                return true;
            }
        });
        w.addJavascriptInterface(new Bridge(), "QbtEngine");
        w.loadUrl("file:///android_asset/engine.html");
        return w;
    }

    /** Thread pool for blocking work (HTTP, files). */
    public ExecutorService io() {
        return pool;
    }

    /** Sends a message to background.js; the callback receives its JSON response. */
    public void send(String msgJson, Callback cb) {
        String id = "m" + seq.incrementAndGet();
        pending.put(id, cb);
        Runnable r = () -> {
            if (wv != null) wv.evaluateJavascript(
                    "__engineHandle(" + JSONObject.quote(id) + "," + JSONObject.quote(msgJson) + ")", null);
        };
        main.post(() -> {
            if (ready) r.run();
            else queue.add(r);
        });
    }

    /** The engine's renderer died: the requests it was handling will never be answered. */
    private void failPending() {
        String err = "{\"ok\":false,\"error\":\"Le moteur de l'app a redémarré, réessaie.\"}";
        for (String id : new ArrayList<>(pending.keySet())) {
            Callback cb = pending.remove(id);
            if (cb != null) main.post(() -> cb.done(err));
        }
    }

    public JSONObject config() {
        try {
            return new JSONObject(prefs.getString("data", "{}"));
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    public synchronized void storageMerge(String json) {
        try {
            JSONObject cur = config();
            JSONObject add = new JSONObject(json);
            for (Iterator<String> it = add.keys(); it.hasNext(); ) {
                String k = it.next();
                cur.put(k, add.get(k));
            }
            prefs.edit().putString("data", cur.toString()).apply();
        } catch (Exception ignored) {
        }
    }

    public void openUrl(String url) {
        if (url == null || url.isEmpty()) return;
        Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            ctx.startActivity(i);
        } catch (Exception ignored) {
        }
    }

    private final class Bridge {
        @JavascriptInterface
        public void ready() {
            main.post(() -> {
                ready = true;
                for (Runnable r : queue) r.run();
                queue.clear();
            });
        }

        @JavascriptInterface
        public void reply(String id, String json) {
            Callback cb = pending.remove(id);
            if (cb != null) main.post(() -> cb.done(json));
        }

        @JavascriptInterface
        public void emit(String json) {
            EventSink s = sink;
            if (s != null) main.post(() -> s.onEvent(json));
        }

        @JavascriptInterface
        public String storageGet() {
            return config().toString();
        }

        @JavascriptInterface
        public void storageSet(String json) {
            storageMerge(json);
        }

        @JavascriptInterface
        public void openUrl(String url) {
            Engine.this.openUrl(url);
        }

        @JavascriptInterface
        public void notify(String title, String text) {
            Intent open = new Intent(ctx, MainActivity.class).putExtra("tab", "qbit")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            PendingIntent pi = PendingIntent.getActivity(ctx, 1, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            Notification n = new Notification.Builder(ctx, "done")
                    .setSmallIcon(android.R.drawable.stat_sys_download_done)
                    .setContentTitle(title)
                    .setContentText(text)
                    .setStyle(new Notification.BigTextStyle().bigText(text))
                    .setContentIntent(pi)
                    .setAutoCancel(true)
                    .build();
            try {
                ctx.getSystemService(NotificationManager.class).notify(notifId.incrementAndGet(), n);
            } catch (SecurityException ignored) {
            }
        }

        @JavascriptInterface
        public void http(String reqJson, String id) {
            pool.execute(() -> {
                String result = doHttp(reqJson);
                main.post(() -> {
                    if (wv != null) wv.evaluateJavascript(
                            "__httpDone(" + JSONObject.quote(id) + "," + JSONObject.quote(result) + ")", null);
                });
            });
        }
    }

    private static String doHttp(String reqJson) {
        Resp r = request(reqJson);
        try {
            JSONObject o = new JSONObject();
            if (r.error != null) o.put("error", r.error);
            else o.put("status", r.status).put("text", r.text);
            return o.toString();
        } catch (Exception e) {
            return "{\"error\":\"Réseau\"}";
        }
    }

    public static final class Resp {
        public int status;
        public String text = "";
        public String error; // set only for network failures
    }

    private static String netError(Exception e) {
        String m = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        if (e instanceof java.net.UnknownHostException) return "Réseau : serveur introuvable (" + m + ")";
        if (e instanceof java.net.SocketTimeoutException) return "Réseau : délai dépassé";
        if (e instanceof java.net.ConnectException) return "Réseau : connexion refusée (" + m + ")";
        if (e instanceof javax.net.ssl.SSLException) return "Réseau : erreur SSL / certificat (" + m + ")";
        if (e instanceof java.net.MalformedURLException) return "Adresse invalide : " + m;
        return "Réseau : " + m;
    }

    /** One HTTP request described as JSON: {url, method, headers, body: {type: none|text|multipart}}. Blocking. */
    public static Resp request(String reqJson) {
        Resp res = new Resp();
        HttpURLConnection c = null;
        try {
            JSONObject req = new JSONObject(reqJson);
            URL url = new URL(req.getString("url"));
            c = (HttpURLConnection) url.openConnection();
            c.setConnectTimeout(20000);
            c.setReadTimeout(60000);
            c.setRequestMethod(req.optString("method", "GET"));
            // qBittorrent's CSRF protection wants Origin/Referer matching its own host.
            String origin = url.getProtocol() + "://" + url.getAuthority();
            c.setRequestProperty("Origin", origin);
            c.setRequestProperty("Referer", origin + "/");
            JSONObject headers = req.optJSONObject("headers");
            if (headers != null) {
                for (Iterator<String> it = headers.keys(); it.hasNext(); ) {
                    String k = it.next();
                    c.setRequestProperty(k, headers.getString(k));
                }
            }
            JSONObject body = req.optJSONObject("body");
            String type = body == null ? "none" : body.optString("type", "none");
            if ("text".equals(type)) {
                byte[] data = body.getString("text").getBytes(StandardCharsets.UTF_8);
                c.setDoOutput(true);
                c.setFixedLengthStreamingMode(data.length);
                try (OutputStream os = c.getOutputStream()) {
                    os.write(data);
                }
            } else if ("multipart".equals(type)) {
                String boundary = "----c411qbt" + System.nanoTime();
                ByteArrayOutputStream buf = new ByteArrayOutputStream();
                org.json.JSONArray parts = body.getJSONArray("parts");
                for (int i = 0; i < parts.length(); i++) {
                    JSONObject p = parts.getJSONObject(i);
                    StringBuilder h = new StringBuilder("--").append(boundary).append("\r\n")
                            .append("Content-Disposition: form-data; name=\"").append(p.getString("name")).append('"');
                    if (p.has("base64")) {
                        h.append("; filename=\"").append(p.optString("filename", "file").replace("\"", "")).append("\"\r\n")
                                .append("Content-Type: ").append(p.optString("contentType", "application/octet-stream")).append("\r\n\r\n");
                        buf.write(h.toString().getBytes(StandardCharsets.UTF_8));
                        buf.write(Base64.decode(p.getString("base64"), Base64.DEFAULT));
                    } else {
                        h.append("\r\n\r\n").append(p.optString("value"));
                        buf.write(h.toString().getBytes(StandardCharsets.UTF_8));
                    }
                    buf.write("\r\n".getBytes(StandardCharsets.UTF_8));
                }
                buf.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
                byte[] data = buf.toByteArray();
                c.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
                c.setDoOutput(true);
                c.setFixedLengthStreamingMode(data.length);
                try (OutputStream os = c.getOutputStream()) {
                    os.write(data);
                }
            }
            int status = c.getResponseCode();
            InputStream in = status >= 400 ? c.getErrorStream() : c.getInputStream();
            String text = "";
            if (in != null) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] b = new byte[16384];
                for (int n; (n = in.read(b)) > 0; ) out.write(b, 0, n);
                in.close();
                text = out.toString("UTF-8");
            }
            res.status = status;
            res.text = text;
        } catch (Exception e) {
            res.error = netError(e);
        } finally {
            if (c != null) c.disconnect();
        }
        return res;
    }
}

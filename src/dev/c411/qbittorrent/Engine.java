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
import android.os.SystemClock;
import android.util.Base64;
import android.webkit.ConsoleMessage;
import android.webkit.JavascriptInterface;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Runs the extension's background.js in a hidden WebView and gives it native HTTP, storage and notifications. */
public final class Engine {
    public interface Callback { void done(String json); }
    public interface EventSink { void onEvent(String json); }

    private static Engine instance;

    // A body bigger than this is refused with a message: running out of memory on a pool thread would take the whole process down.
    // The engine's own requests are small now (it asks for what it needs only) and go through three copies on their way to the page.
    private static final int ENGINE_BODY_LIMIT = 16 * 1024 * 1024;
    private static final int BODY_LIMIT = 64 * 1024 * 1024;
    // The largest message handed to the engine: an uploaded torrent (16 MB) as base64 inside JSON, with its escapes.
    static final int MAX_MESSAGE_CHARS = 28 * 1024 * 1024;
    private static final int IO_THREADS = 8;
    private static final int IO_QUEUE = 64;
    static final String BUSY = "Trop de requêtes en cours : réessaie dans un instant";
    private static final AtomicInteger ioThreads = new AtomicInteger();
    // The qBittorrent session cookie lives here, per server, and nowhere else: no other request carries it.
    private static final CookieJars cookies = new CookieJars();

    public static synchronized Engine get(Context c) {
        if (instance == null) instance = new Engine(c.getApplicationContext());
        return instance;
    }

    private final Context ctx;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ThreadPoolExecutor pool = newPool();
    private final RestartDelay restarts = new RestartDelay();
    private final SharedPreferences prefs;
    private final Map<String, Callback> pending = new ConcurrentHashMap<>();
    private final List<Runnable> queue = new ArrayList<>();
    private final AtomicInteger seq = new AtomicInteger();
    private final AtomicInteger notifId = new AtomicInteger(100);
    private volatile JSONObject configCache; // never modified once published: storageMerge builds a new one
    private WebView wv; // main thread only
    private boolean ready = false;
    public volatile EventSink sink;

    private Engine(Context ctx) {
        this.ctx = ctx;
        prefs = ctx.getSharedPreferences("config", Context.MODE_PRIVATE);
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel("done", "Téléchargements terminés", NotificationManager.IMPORTANCE_DEFAULT));
        wv = createWebView();
    }

    private static ThreadPoolExecutor newPool() {
        ThreadPoolExecutor p = new ThreadPoolExecutor(IO_THREADS, IO_THREADS, 30, TimeUnit.SECONDS, new ArrayBlockingQueue<>(IO_QUEUE), r -> {
            Thread t = new Thread(r, "arr411-io-" + ioThreads.incrementAndGet());
            // A background task that fails is written down and dropped: it must not take the whole app down with it.
            t.setUncaughtExceptionHandler((thread, error) -> Diag.uncaught(thread, error, false));
            return t;
        });
        p.allowCoreThreadTimeOut(true);
        return p;
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
                Diag.rendererGone("engine", detail);
                // Renderer killed (low memory): rebuild the engine instead of crashing the app, less and less eagerly if it keeps dying.
                if (view == wv) {
                    ready = false;
                    wv = null;
                    queue.clear();
                    failPending();
                    main.postDelayed(() -> wv = createWebView(), restarts.afterDeath(SystemClock.elapsedRealtime()));
                }
                view.destroy();
                return true;
            }
        });
        w.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onConsoleMessage(ConsoleMessage message) {
                Diag.console("engine", message);
                return false;
            }
        });
        w.addJavascriptInterface(new Bridge(), "QbtEngine");
        w.loadUrl("file:///android_asset/engine.html");
        return w;
    }

    /** Runs blocking work (HTTP, files) on the pool; false when it is saturated, and the caller then reports the failure itself. */
    public boolean tryIo(Runnable task) {
        try {
            pool.execute(task);
            return true;
        } catch (RejectedExecutionException e) {
            return false;
        }
    }

    /** Sends a message to background.js; the callback receives its JSON response. */
    public void send(String msgJson, Callback cb) {
        String id = "m" + seq.incrementAndGet();
        String script = null;
        if (msgJson != null && msgJson.length() <= MAX_MESSAGE_CHARS) {
            try {
                script = "__engineHandle(" + JSONObject.quote(id) + "," + JSONObject.quote(msgJson) + ")";
            } catch (OutOfMemoryError ignored) {
                // answered below
            }
        }
        if (script == null) {
            main.post(() -> cb.done("{\"ok\":false,\"error\":\"Message trop volumineux\"}"));
            return;
        }
        String js = script;
        pending.put(id, cb);
        Runnable r = () -> {
            if (wv != null) wv.evaluateJavascript(js, null);
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

    /** The saved settings. Shared and read-only: change them through storageMerge. */
    public JSONObject config() {
        JSONObject c = configCache;
        if (c == null) {
            try {
                c = new JSONObject(prefs.getString("data", "{}"));
            } catch (Exception e) {
                c = new JSONObject();
            }
            configCache = c;
        }
        return c;
    }

    public synchronized void storageMerge(String json) {
        try {
            JSONObject cur = new JSONObject(config().toString());
            JSONObject add = new JSONObject(json);
            for (Iterator<String> it = add.keys(); it.hasNext(); ) {
                String k = it.next();
                cur.put(k, add.get(k));
            }
            prefs.edit().putString("data", cur.toString()).apply();
            configCache = cur;
        } catch (Exception ignored) {
        }
    }

    public void openUrl(String url) {
        if (!UrlPolicy.isExternalOpenable(url)) return;
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
            try {
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
                ctx.getSystemService(NotificationManager.class).notify(notifId.incrementAndGet(), n);
            } catch (RuntimeException e) {
                // Refused by the system (permission, too large…): a notification is never worth the app.
                Diag.problem("notify", "notification failed", e);
            }
        }

        @JavascriptInterface
        public void http(String reqJson, String id) {
            boolean queued = tryIo(() -> {
                String js;
                try {
                    js = "__httpDone(" + JSONObject.quote(id) + "," + JSONObject.quote(doHttp(reqJson)) + ")";
                } catch (Throwable e) {
                    // Whatever happens, the page gets an answer: it waits for one.
                    Diag.serious("engine request failed", e, null);
                    String why = e instanceof OutOfMemoryError ? "Mémoire insuffisante" : "Erreur interne";
                    js = "__httpDone(" + JSONObject.quote(id) + "," + JSONObject.quote("{\"error\":\"" + why + "\"}") + ")";
                }
                String script = js;
                main.post(() -> {
                    if (wv != null) wv.evaluateJavascript(script, null);
                });
            });
            if (!queued) {
                String script = "__httpDone(" + JSONObject.quote(id) + "," + JSONObject.quote("{\"error\":\"" + BUSY + "\"}") + ")";
                main.post(() -> {
                    if (wv != null) wv.evaluateJavascript(script, null);
                });
            }
        }
    }

    private static String doHttp(String reqJson) {
        Resp r = request(reqJson, ENGINE_BODY_LIMIT);
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
        return request(reqJson, BODY_LIMIT);
    }

    /** Same, refusing a response body bigger than {@code bodyLimit} bytes. */
    static Resp request(String reqJson, int bodyLimit) {
        Resp res = new Resp();
        HttpURLConnection c = null;
        String what = "request";
        long started = SystemClock.elapsedRealtime();
        try {
            JSONObject req = new JSONObject(reqJson);
            URL url = new URL(req.getString("url"));
            String method = req.optString("method", "GET");
            // For the log: where and what, never the query or a login.
            what = method + " " + url.getHost() + (url.getPort() > 0 ? ":" + url.getPort() : "") + url.getPath();
            JSONObject headers = req.optJSONObject("headers");
            Body body = body(req.optJSONObject("body"));
            boolean bodyDropped = false;
            for (int hop = 0; ; hop++) {
                c = open(url, method, headers, body, bodyDropped);
                int status = c.getResponseCode();
                cookies.keep(url, c.getHeaderFields());
                String location = RedirectPolicy.isRedirect(status) ? c.getHeaderField("Location") : null;
                if (location != null) {
                    // Redirects are followed by hand: only within the same server, so that the API key and the session cookie never reach another one.
                    URL next = RedirectPolicy.resolve(url, location);
                    if (next == null) {
                        res.error = "Redirection invalide";
                    } else if (hop >= RedirectPolicy.MAX_REDIRECTS) {
                        res.error = "Trop de redirections";
                    } else if (!RedirectPolicy.staysOnServer(url, next)) {
                        res.error = RedirectPolicy.refusal(next);
                    } else {
                        String nextMethod = RedirectPolicy.nextMethod(status, method);
                        if (!nextMethod.equals(method)) {
                            method = nextMethod;
                            body = null;
                            bodyDropped = true;
                        }
                        url = next;
                        c.disconnect();
                        continue;
                    }
                    break;
                }
                InputStream in = status >= 400 ? c.getErrorStream() : c.getInputStream();
                String text = "";
                if (in != null) {
                    try (InputStream stream = in) {
                        text = BodyReader.readText(stream, c.getContentLengthLong(), bodyLimit);
                    }
                }
                res.status = status;
                res.text = text;
                break;
            }
        } catch (BodyReader.TooLargeException e) {
            res.error = "Réponse trop volumineuse (plus de " + (e.limit >> 20) + " Mo) : abandon pour protéger la mémoire";
        } catch (OutOfMemoryError e) {
            res.error = "Mémoire insuffisante pour lire la réponse du serveur";
            Diag.serious("out of memory while reading " + what, e, null);
        } catch (Exception e) {
            res.error = netError(e);
        } finally {
            if (c != null) c.disconnect();
        }
        Diag.http(what, res.status, res.error, res.text.length(), SystemClock.elapsedRealtime() - started);
        return res;
    }

    private static final class Body {
        final byte[] data;
        final String contentType; // null: the request's own headers decide

        Body(byte[] data, String contentType) {
            this.data = data;
            this.contentType = contentType;
        }
    }

    private static Body body(JSONObject body) throws Exception {
        String type = body == null ? "none" : body.optString("type", "none");
        if ("text".equals(type)) return new Body(body.getString("text").getBytes(StandardCharsets.UTF_8), null);
        if (!"multipart".equals(type)) return null;
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
        return new Body(buf.toByteArray(), "multipart/form-data; boundary=" + boundary);
    }

    private static HttpURLConnection open(URL url, String method, JSONObject headers, Body body, boolean bodyDropped) throws Exception {
        HttpURLConnection c = (HttpURLConnection) url.openConnection();
        try {
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(20000);
            c.setReadTimeout(60000);
            c.setRequestMethod(method);
            // qBittorrent's CSRF protection wants Origin/Referer matching its own host.
            String origin = CookieJars.origin(url);
            c.setRequestProperty("Origin", origin);
            c.setRequestProperty("Referer", origin + "/");
            String cookie = cookies.header(url);
            if (cookie != null) c.setRequestProperty("Cookie", cookie);
            if (headers != null) {
                for (Iterator<String> it = headers.keys(); it.hasNext(); ) {
                    String k = it.next();
                    if (bodyDropped && k.equalsIgnoreCase("Content-Type")) continue;
                    c.setRequestProperty(k, headers.getString(k));
                }
            }
            if (body != null) {
                if (body.contentType != null) c.setRequestProperty("Content-Type", body.contentType);
                c.setDoOutput(true);
                c.setFixedLengthStreamingMode(body.data.length);
                try (OutputStream os = c.getOutputStream()) {
                    os.write(body.data);
                }
            }
            return c;
        } catch (Exception e) {
            c.disconnect();
            throw e;
        }
    }
}

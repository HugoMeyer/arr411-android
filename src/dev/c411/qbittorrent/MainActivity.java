package dev.c411.qbittorrent;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.Menu;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.inputmethod.InputMethodManager;
import android.webkit.CookieManager;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/** Arr411: C411 (with the extension injected) + the Radarr / Sonarr / Lidarr / qBittorrent web app, behind a bottom bar. */
public class MainActivity extends Activity implements NavBar.Listener {
    static final String HOME = "https://c411.org/";
    private static final int REQ_FILES = 7;
    private static final int BG = 0xFF070D0B;
    private static final String DESKTOP_JS = "(function(){var m=document.querySelector('meta[name=viewport]');"
            + "if(!m){m=document.createElement('meta');m.name='viewport';document.head.appendChild(m);}"
            + "m.content='width=1280';})()";

    Engine engine;
    NavBar nav;
    volatile String tab = "";
    private volatile String spaTab = "";
    private String prevTab = "c411";
    private WebView c411, spa;
    private PageBridge page;
    private SpaBridge spaBridge;
    private Engine.EventSink sink;
    private FrameLayout content;
    private View hairline;
    private ProgressBar progress;
    private String bundle;
    private boolean spaReady, dead, resumed, loading;
    private final List<String> sharedQueue = new ArrayList<>();
    private ValueCallback<Uri[]> fileCb;
    private Toast lastToast;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        engine = Engine.get(this);
        bundle = buildBundle();
        migrateDesktopPref();
        Window w = getWindow();
        w.setStatusBarColor(BG);
        w.setNavigationBarColor(NavBar.BG);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        content = new FrameLayout(this);
        root.addView(content, new LinearLayout.LayoutParams(-1, 0, 1f));
        hairline = new View(this);
        hairline.setBackgroundColor(0xFF15211C);
        root.addView(hairline, new LinearLayout.LayoutParams(-1, Math.max(1, dp(0.7f))));
        nav = new NavBar(this, this);
        root.addView(nav, new LinearLayout.LayoutParams(-1, dp(62)));
        setContentView(root);

        c411 = createC411();
        spa = createSpa();
        content.addView(c411, new FrameLayout.LayoutParams(-1, -1));
        content.addView(spa, new FrameLayout.LayoutParams(-1, -1));
        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        progress.setProgressTintList(ColorStateList.valueOf(NavBar.ACCENT));
        progress.setProgressBackgroundTintList(ColorStateList.valueOf(0));
        progress.setVisibility(View.GONE);
        content.addView(progress, new FrameLayout.LayoutParams(-1, dp(3), Gravity.TOP));
        watchKeyboard(root);

        sink = json -> c411Js("window.__qbtEvent && __qbtEvent(" + JSONObject.quote(json) + ")");
        engine.sink = sink;

        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        }

        String first = state == null ? null : state.getString("tab");
        showTab(isTab(first) ? first : startTab(getIntent()), false);
        if (state == null || c411.restoreState(state) == null) c411.loadUrl(c411Url(getIntent()));
        spa.loadUrl(SpaClient.START);

        boolean fresh = state == null && (getIntent().getFlags() & Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) == 0;
        if (fresh) handleIntent(getIntent(), false);
        engine.io().execute(() -> SpaClient.prune(getCacheDir()));
    }

    // ---------- WebViews ----------

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    private WebView createC411() {
        WebView w = new WebView(this);
        WebSettings s = w.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        w.setBackgroundColor(BG);
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(w, true);
        page = new PageBridge(this);
        page.userAgent = s.getUserAgentString();
        w.addJavascriptInterface(page, "QbtPage");
        w.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                Uri u = req.getUrl();
                String sc = u.getScheme() == null ? "" : u.getScheme().toLowerCase();
                if ("magnet".equals(sc)) {
                    shared(textJson(u.toString()));
                    return true;
                }
                if ("http".equals(sc) || "https".equals(sc)) {
                    if (isC411(u) || !req.isForMainFrame()) return false;
                    openExternal(u.toString());
                    return true;
                }
                if (sc.isEmpty() || "about".equals(sc) || "data".equals(sc) || "blob".equals(sc) || "javascript".equals(sc)) return false;
                engine.openUrl(u.toString()); // mailto:, tel:, market:…
                return true;
            }

            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                loading = true;
                updateProgress();
            }

            @Override
            public void onPageCommitVisible(WebView view, String url) {
                inject(url);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                inject(url);
                loading = false;
                updateProgress();
                CookieManager.getInstance().flush();
            }

            @Override
            public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
                return onRendererGone();
            }
        });
        w.setWebChromeClient(new Chrome(true));
        w.setDownloadListener((url, ua, cd, mime, len) -> page.onDownload(url, URLUtil.guessFileName(url, cd, mime)));
        return w;
    }

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    private WebView createSpa() {
        WebView w = new WebView(this);
        WebSettings s = w.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(false);
        w.setBackgroundColor(BG);
        w.setOverScrollMode(View.OVER_SCROLL_NEVER);
        spaBridge = new SpaBridge(this);
        w.addJavascriptInterface(spaBridge, "Native");
        w.setWebViewClient(new SpaClient(this));
        w.setWebChromeClient(new Chrome(false));
        return w;
    }

    /** Page progress (C411 only) and <input type=file> for both WebViews. */
    private final class Chrome extends WebChromeClient {
        private final boolean isPage;

        Chrome(boolean isPage) {
            this.isPage = isPage;
        }

        @Override
        public void onProgressChanged(WebView view, int p) {
            if (!isPage) return;
            if (p >= 100) loading = false;
            updateProgress();
        }

        @Override
        public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> cb, FileChooserParams params) {
            if (fileCb != null) fileCb.onReceiveValue(null);
            fileCb = cb;
            Intent i = new Intent(Intent.ACTION_GET_CONTENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*");
            if (params.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE) i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
            try {
                startActivityForResult(i, REQ_FILES);
                return true;
            } catch (Exception e) {
                fileCb = null;
                toast("Aucune application pour choisir un fichier");
                return false;
            }
        }
    }

    private void inject(String url) {
        if (dead || url == null || !isC411(Uri.parse(url))) return;
        c411.evaluateJavascript(bundle, null);
        if (desktopMode()) c411.evaluateJavascript(DESKTOP_JS, null);
    }

    private String asset(String name) {
        try (InputStream in = getAssets().open(name)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] b = new byte[16384];
            for (int n; (n = in.read(b)) > 0; ) out.write(b, 0, n);
            return out.toString("UTF-8");
        } catch (Exception e) {
            return "";
        }
    }

    private String buildBundle() {
        return "(function(){ if (window.__qbtLoaded) return; window.__qbtLoaded = true;\n"
                + asset("shim-page.js") + "\n" + asset("page-hook.js") + "\n" + asset("content.js")
                + "\n})();";
    }

    private boolean desktopMode() {
        return engine.config().optBoolean("desktopMode", false);
    }

    /** v1 kept "desktop" in the activity preferences; it now lives in the shared config. */
    private void migrateDesktopPref() {
        SharedPreferences p = getPreferences(MODE_PRIVATE);
        if (!p.contains("desktop")) return;
        if (p.getBoolean("desktop", false) && !engine.config().has("desktopMode")) engine.storageMerge("{\"desktopMode\":true}");
        p.edit().remove("desktop").apply();
    }

    static boolean isC411(Uri u) {
        String h = u == null ? null : u.getHost();
        return h != null && (h.equalsIgnoreCase("c411.org") || h.toLowerCase().endsWith(".c411.org"));
    }

    private static String c411Url(Intent i) {
        Uri u = i == null ? null : i.getData();
        return u != null && isC411(u) ? u.toString() : HOME;
    }

    void c411Js(String js) {
        runOnUiThread(() -> {
            if (!dead) c411.evaluateJavascript(js, null);
        });
    }

    void spaJs(String js) {
        runOnUiThread(() -> {
            if (!dead) spa.evaluateJavascript(js, null);
        });
    }

    private static String textJson(String text) {
        try {
            return new JSONObject().put("text", text).toString();
        } catch (Exception e) {
            return null;
        }
    }

    // ---------- Tabs & navigation ----------

    static boolean isTab(String t) {
        return "c411".equals(t) || "radarr".equals(t) || "sonarr".equals(t) || "lidarr".equals(t) || "qbit".equals(t) || "settings".equals(t);
    }

    private String startTab(Intent in) {
        String t = in == null ? null : in.getStringExtra("tab");
        if (isTab(t)) return t;
        if (in != null && Intent.ACTION_VIEW.equals(in.getAction()) && isC411(in.getData())) return "c411";
        JSONObject cfg = engine.config();
        if (cfg.optString("qbUrl").trim().isEmpty() && cfg.optString("radarrUrl").trim().isEmpty()
                && cfg.optString("sonarrUrl").trim().isEmpty() && cfg.optString("lidarrUrl").trim().isEmpty()) return "settings"; // first launch
        String pref = cfg.optString("startTab", "c411");
        if ("last".equals(pref)) pref = getPreferences(MODE_PRIVATE).getString("lastTab", "c411");
        return isTab(pref) && !"settings".equals(pref) ? pref : "c411";
    }

    /** Shows a tab natively; tellSpa = the web app must switch too (false when the web app asked for it). */
    void showTab(String name, boolean tellSpa) {
        if (dead || !isTab(name)) return;
        if (!name.equals(tab)) {
            if (!tab.isEmpty()) {
                prevTab = tab;
                hideKeyboard();
            }
            tab = name;
            boolean web = !"c411".equals(name);
            c411.setVisibility(web ? View.INVISIBLE : View.VISIBLE);
            spa.setVisibility(web ? View.VISIBLE : View.INVISIBLE);
            nav.select(name);
            updateProgress();
            if (!"settings".equals(name)) getPreferences(MODE_PRIVATE).edit().putString("lastTab", name).apply();
        }
        if (!tellSpa) spaTab = name;
        else if (spaReady && !name.equals(spaTab)) {
            spaTab = name;
            spaJs("App.showTab(" + JSONObject.quote(name) + ")");
        }
    }

    /** Called by the web app at boot: remembers which tab it starts on. */
    String tabForSpa() {
        String t = tab;
        spaTab = t;
        return t;
    }

    private void updateProgress() {
        if (dead || progress == null) return;
        progress.setProgress(c411.getProgress());
        progress.setVisibility(loading && "c411".equals(tab) ? View.VISIBLE : View.GONE);
    }

    void openC411(String url, boolean tellSpa) {
        if (dead) return;
        showTab("c411", tellSpa);
        if (url != null && isC411(Uri.parse(url))) c411.loadUrl(url);
    }

    void reloadC411() {
        if (!dead) c411.reload();
    }

    /** Links leaving the app: C411 opens in its tab, magnets go to qBittorrent, the rest to other apps. */
    void openExternal(String url) {
        if (dead || url == null || url.isEmpty()) return;
        Uri u = Uri.parse(url);
        if (isC411(u)) openC411(url, true);
        else if ("magnet".equalsIgnoreCase(u.getScheme())) shared(textJson(url));
        else engine.openUrl(url);
    }

    @Override
    public void onTab(String t, boolean reselected) {
        if (dead) return;
        if (!reselected) showTab(t, true);
        else if ("c411".equals(t)) showC411Menu();
        else if (spaReady) {
            String q = JSONObject.quote(t);
            spaJs("App.popToRoot(" + q + ");App.showTab(" + q + ")");
        }
    }

    @Override
    public void onLongTab(String t) {
        if (dead) return;
        haptic();
        if ("c411".equals(t)) showC411Menu();
        else shared("{\"tab\":\"settings\",\"section\":" + JSONObject.quote(t) + "}");
    }

    private void showC411Menu() {
        PopupMenu m = new PopupMenu(this, nav.getChildAt(0), Gravity.TOP);
        Menu menu = m.getMenu();
        menu.add(0, 1, 0, "Recharger la page");
        menu.add(0, 2, 1, "Accueil C411");
        menu.add(0, 3, 2, "Mode ordinateur").setCheckable(true).setChecked(desktopMode());
        menu.add(0, 4, 3, "Copier le lien");
        menu.add(0, 5, 4, "Partager le lien");
        menu.add(0, 6, 5, "Réglages Arr411");
        m.setOnMenuItemClickListener(item -> {
            onC411Menu(item.getItemId());
            return true;
        });
        m.show();
    }

    private void onC411Menu(int id) {
        if (dead) return;
        String url = c411.getUrl() == null ? HOME : c411.getUrl();
        switch (id) {
            case 1:
                c411.reload();
                break;
            case 2:
                showTab("c411", true);
                c411.loadUrl(HOME);
                break;
            case 3:
                engine.storageMerge("{\"desktopMode\":" + !desktopMode() + "}");
                if (spaReady) spaJs("App.loadCfg && App.loadCfg()");
                c411.reload();
                break;
            case 4:
                ClipboardManager cm = getSystemService(ClipboardManager.class);
                if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("C411", url));
                if (Build.VERSION.SDK_INT < 33) toast("Lien copié");
                break;
            case 5:
                Intent s = new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, url);
                try {
                    startActivity(Intent.createChooser(s, "Partager le lien"));
                } catch (Exception ignored) {
                }
                break;
            case 6:
                shared("{\"tab\":\"settings\"}");
                break;
        }
    }

    @Override
    public void onBackPressed() {
        if (dead) {
            super.onBackPressed();
        } else if ("c411".equals(tab)) {
            if (c411.canGoBack()) c411.goBack();
            else super.onBackPressed();
        } else if (!spaReady) {
            showTab("c411", true);
        } else {
            spa.evaluateJavascript("(function(){try{return App.back()}catch(e){return false}})()", v -> {
                if (dead || "true".equals(v)) return;
                showTab("settings".equals(tab) && !"settings".equals(prevTab) ? prevTab : "c411", true);
            });
        }
    }

    private void hideKeyboard() {
        InputMethodManager imm = getSystemService(InputMethodManager.class);
        View f = getCurrentFocus();
        if (imm != null) imm.hideSoftInputFromWindow((f != null ? f : content).getWindowToken(), 0);
    }

    /** Hides the bottom bar while the keyboard is open (more room to type). */
    private void watchKeyboard(View root) {
        root.getViewTreeObserver().addOnGlobalLayoutListener(() -> {
            if (dead) return;
            boolean ime;
            WindowInsets wi = root.getRootWindowInsets();
            if (Build.VERSION.SDK_INT >= 30 && wi != null) {
                ime = wi.isVisible(WindowInsets.Type.ime());
            } else {
                Rect r = new Rect();
                root.getWindowVisibleDisplayFrame(r);
                int full = root.getRootView().getHeight();
                ime = full > 0 && full - r.bottom > full * 0.15f;
            }
            int vis = ime ? View.GONE : View.VISIBLE;
            if (nav.getVisibility() != vis) {
                nav.setVisibility(vis);
                hairline.setVisibility(vis);
            }
        });
    }

    void haptic() {
        if (!dead) nav.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
    }

    void toast(String text) {
        runOnUiThread(() -> {
            if (lastToast != null) lastToast.cancel();
            lastToast = Toast.makeText(getApplicationContext(), text, Toast.LENGTH_LONG);
            lastToast.show();
        });
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    // ---------- Intents & shared content ----------

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent, true);
    }

    /** running = the activity already existed (its C411 page did not load this intent's URL). */
    private void handleIntent(Intent in, boolean running) {
        if (in == null || dead) return;
        String t = in.getStringExtra("tab");
        if (isTab(t)) {
            showTab(t, true);
            return;
        }
        String action = in.getAction();
        Uri data = in.getData();
        if (Intent.ACTION_VIEW.equals(action) && isC411(data)) {
            if (running) openC411(data.toString(), true);
            else showTab("c411", true);
            return;
        }
        if (Intent.ACTION_VIEW.equals(action) || Intent.ACTION_SEND.equals(action) || Intent.ACTION_SEND_MULTIPLE.equals(action)) {
            engine.io().execute(() -> {
                try {
                    String json = Shared.fromIntent(this, in);
                    if (json != null) runOnUiThread(() -> shared(json));
                } catch (Exception e) {
                    toast("Partage impossible : " + e.getMessage());
                }
            });
        }
    }

    /** Hands a link / files / tab request to the web app (queued until it has booted). Main thread. */
    void shared(String json) {
        if (dead || json == null) return;
        if (spaReady) spaJs("App.handleShared(" + JSONObject.quote(json) + ")");
        else sharedQueue.add(json);
    }

    void onSpaStarted() {
        spaReady = false;
        spaBridge.texts.clear();
    }

    void onSpaReady() {
        if (dead) return;
        spaReady = true;
        if (!tab.equals(spaTab)) {
            spaTab = tab;
            spaJs("App.showTab(" + JSONObject.quote(tab) + ")");
        }
        spaJs("App.setForeground(" + resumed + ")");
        List<String> q = new ArrayList<>(sharedQueue);
        sharedQueue.clear();
        for (String json : q) shared(json);
    }

    // ---------- Lifecycle ----------

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        if (dead) return;
        c411.onResume();
        spa.onResume();
        if (spaReady) spaJs("App.setForeground(true)");
    }

    @Override
    protected void onPause() {
        resumed = false;
        if (!dead) {
            c411.onPause();
            spa.onPause();
            if (spaReady) spaJs("App.setForeground(false)");
        }
        CookieManager.getInstance().flush();
        super.onPause();
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putString("tab", tab);
        if (!dead) c411.saveState(out);
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_FILES) return;
        ValueCallback<Uri[]> cb = fileCb;
        fileCb = null;
        if (cb == null) return;
        List<Uri> picked = new ArrayList<>();
        if (res == RESULT_OK && data != null) {
            ClipData cd = data.getClipData();
            if (cd != null) {
                for (int i = 0; i < cd.getItemCount(); i++) {
                    Uri u = cd.getItemAt(i).getUri();
                    if (u != null) picked.add(u);
                }
            }
            if (picked.isEmpty() && data.getData() != null) picked.add(data.getData());
        }
        cb.onReceiveValue(picked.isEmpty() ? null : picked.toArray(new Uri[0]));
    }

    /** A WebView renderer died (usually low memory): drop both WebViews and rebuild the activity. */
    boolean onRendererGone() {
        if (!dead) {
            destroyWebViews();
            content.post(this::recreate);
        }
        return true;
    }

    private void destroyWebViews() {
        dead = true;
        if (fileCb != null) {
            fileCb.onReceiveValue(null);
            fileCb = null;
        }
        for (WebView w : new WebView[]{c411, spa}) {
            if (w == null) continue;
            if (content != null) content.removeView(w);
            w.destroy();
        }
    }

    @Override
    protected void onDestroy() {
        if (engine != null && engine.sink == sink) engine.sink = null;
        if (!dead) destroyWebViews();
        super.onDestroy();
    }
}

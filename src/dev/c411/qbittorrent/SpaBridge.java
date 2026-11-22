package dev.c411.qbittorrent;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.webkit.JavascriptInterface;

import org.json.JSONObject;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** window.Native for the Arr411 web app (Radarr, Sonarr, Lidarr, qBittorrent, settings). */
final class SpaBridge {
    private static final int MAX_WAITING = 64;

    private final MainActivity act;
    private final Engine engine;
    // Response bodies wait here until the page takes them (keeps big JSON out of evaluateJavascript).
    // Bounded: when nobody takes them (the page was reloaded under us), the oldest ones go first instead of everything.
    final Map<String, String> texts = Collections.synchronizedMap(new LinkedHashMap<String, String>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
            boolean full = size() > MAX_WAITING;
            if (full) Diag.problem("texts-evicted", "dropping a response the page never took", null);
            return full;
        }
    });

    SpaBridge(MainActivity act) {
        this.act = act;
        this.engine = act.engine;
    }

    @JavascriptInterface
    public void ready() {
        act.runOnUiThread(act::onSpaReady);
    }

    @JavascriptInterface
    public void http(String reqJson, String id) {
        boolean queued = engine.tryIo(() -> {
            int status = 0;
            String error = null;
            try {
                Engine.Resp r = Engine.request(reqJson);
                status = r.status;
                error = r.error;
                if (error == null) texts.put(id, r.text == null ? "" : r.text);
            } catch (Throwable e) {
                // The page waits for an answer whatever happens.
                Diag.serious("app request failed", e, null);
                error = e instanceof OutOfMemoryError ? "Mémoire insuffisante" : "Erreur interne";
            }
            done(id, status, error);
        });
        if (!queued) done(id, 0, Engine.BUSY);
    }

    private void done(String id, int status, String error) {
        act.spaJs("window.__nativeDone&&__nativeDone(" + JSONObject.quote(id) + "," + status + ","
                + (error == null ? "null" : JSONObject.quote(error)) + ")");
    }

    @JavascriptInterface
    public String take(String id) {
        return texts.remove(id);
    }

    @JavascriptInterface
    public String config() {
        return engine.config().toString();
    }

    @JavascriptInterface
    public void saveConfig(String patchJson) {
        try {
            JSONObject p = new JSONObject(patchJson);
            p.remove("tracked"); // owned by background.js
            engine.storageMerge(p.toString());
        } catch (Exception ignored) {
        }
    }

    @JavascriptInterface
    public String currentTab() {
        return act.tabForSpa();
    }

    @JavascriptInterface
    public void selectTab(String name) {
        act.runOnUiThread(() -> act.showTab(name, false));
    }

    @JavascriptInterface
    public void openC411(String url) {
        act.runOnUiThread(() -> act.openC411(url, false));
    }

    @JavascriptInterface
    public void reloadC411() {
        act.runOnUiThread(act::reloadC411);
    }

    @JavascriptInterface
    public void openUrl(String url) {
        act.runOnUiThread(() -> act.openExternal(url));
    }

    @JavascriptInterface
    public void toast(String text) {
        act.toast(text);
    }

    @JavascriptInterface
    public void setBadge(int n) {
        act.runOnUiThread(() -> act.nav.setBadge(n));
    }

    @JavascriptInterface
    public void copy(String text) {
        act.runOnUiThread(() -> {
            ClipboardManager cm = act.getSystemService(ClipboardManager.class);
            if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("Arr411", text == null ? "" : text));
        });
    }

    @JavascriptInterface
    public void haptic() {
        act.runOnUiThread(act::haptic);
    }

    @JavascriptInterface
    public String appVersion() {
        try {
            return act.getPackageManager().getPackageInfo(act.getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "";
        }
    }

    // ---------- Diagnostic (Settings) ----------

    @JavascriptInterface
    public String diagReport() {
        return Diag.report();
    }

    @JavascriptInterface
    public void diagShare() {
        String report = Diag.report();
        act.runOnUiThread(() -> {
            try {
                Intent send = new Intent(Intent.ACTION_SEND).setType("text/plain")
                        .putExtra(Intent.EXTRA_SUBJECT, "Arr411 : rapport de diagnostic")
                        .putExtra(Intent.EXTRA_TEXT, report);
                act.startActivity(Intent.createChooser(send, "Partager le diagnostic"));
            } catch (Exception e) {
                act.toast("Partage impossible : " + e.getMessage());
            }
        });
    }

    @JavascriptInterface
    public void diagClear() {
        Diag.clear();
    }
}

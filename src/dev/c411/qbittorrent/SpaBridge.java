package dev.c411.qbittorrent;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.webkit.JavascriptInterface;

import org.json.JSONObject;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** window.Native for the Arr411 web app (Radarr, Sonarr, Lidarr, qBittorrent, settings). */
final class SpaBridge {
    private final MainActivity act;
    private final Engine engine;
    // Response bodies wait here until the page takes them (keeps big JSON out of evaluateJavascript).
    final Map<String, String> texts = new ConcurrentHashMap<>();

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
        engine.io().execute(() -> {
            Engine.Resp r = Engine.request(reqJson);
            if (r.error == null) texts.put(id, r.text == null ? "" : r.text);
            act.spaJs("window.__nativeDone&&__nativeDone(" + JSONObject.quote(id) + "," + r.status + ","
                    + (r.error == null ? "null" : JSONObject.quote(r.error)) + ")");
        });
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
}

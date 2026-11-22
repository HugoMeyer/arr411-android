package dev.c411.qbittorrent;

import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

import org.json.JSONObject;

import java.util.Objects;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The Quick Settings tile « Vitesse alternative »: shows whether qBittorrent's alternative speed limits are on, and a tap flips them.
 * What to do is {@link QbSpeed}'s to say, so that it is tested without a phone. This is the Android side: the tile, the requests on a worker thread
 * (through the engine's HTTP client, which keeps the session cookie), and the screen to open when nothing is set up yet.
 */
public final class SpeedTile extends TileService {
    // Distinct from the request codes of the notifications (1, and 411001 up).
    private static final int REQUEST_CODE = 7301;
    private static final int TIMEOUT_MS = 8000;
    private static final int BODY_LIMIT = 64 * 1024;

    // The system makes a new service each time it binds the tile, so what has to outlast one is static. Main thread only, except the worker and the gate.
    private static final Handler main = new Handler(Looper.getMainLooper());
    private static final ThreadPoolExecutor worker = newWorker();
    private static final AtomicBoolean busy = new AtomicBoolean();
    private static final QbSpeed.LoginGate gate = new QbSpeed.LoginGate();
    private static Answer last;
    // The service that is on screen, if any: a late answer is drawn on it, or else only kept.
    private static SpeedTile listener;

    /** What the app's settings say about qBittorrent. {@code base} is null when there is no usable address. */
    private static final class Settings {
        final String base;
        final String user;
        final String pass;
        final int key;

        Settings(String base, String user, String pass) {
            this.base = base;
            this.user = user;
            this.pass = pass;
            this.key = Objects.hash(base, user, pass);
        }
    }

    /** What the server said last for the settings that {@code key} stands for, and when ({@link SystemClock#elapsedRealtime}). */
    private static final class Answer {
        final QbSpeed.Outcome outcome;
        final long at;
        final int key;

        Answer(QbSpeed.Outcome outcome, long at, int key) {
            this.outcome = outcome;
            this.at = at;
            this.key = key;
        }
    }

    /** The requests of {@link QbSpeed#exchange}. */
    private static final class Network implements QbSpeed.Server {
        private final String base;

        Network(String base) {
            this.base = base;
        }

        @Override
        public QbSpeed.Reply send(String method, String path, String form) {
            try {
                JSONObject req = new JSONObject().put("url", base + path).put("method", method).put("timeoutMs", TIMEOUT_MS);
                if (form != null) {
                    req.put("headers", new JSONObject().put("Content-Type", "application/x-www-form-urlencoded"));
                    req.put("body", new JSONObject().put("type", "text").put("text", form));
                }
                Engine.Resp resp = Engine.request(req.toString(), BODY_LIMIT);
                return new QbSpeed.Reply(resp.status, resp.text, resp.error);
            } catch (Exception e) {
                return new QbSpeed.Reply(0, "", e.getClass().getSimpleName());
            }
        }
    }

    private static ThreadPoolExecutor newWorker() {
        ThreadPoolExecutor pool = new ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS, new LinkedBlockingQueue<>(), r -> {
            Thread t = new Thread(r, "arr411-tile");
            t.setUncaughtExceptionHandler((thread, error) -> Diag.uncaught(thread, error, false));
            return t;
        });
        pool.allowCoreThreadTimeOut(true);
        return pool;
    }

    // ---------- The tile ----------

    @Override
    public void onStartListening() {
        super.onStartListening();
        try {
            listener = this;
            Settings s = settings(this);
            if (s.base != null && !busy.get() && stale(known(s))) start(this, s, false);
            render(s);
        } catch (Throwable e) {
            Diag.problem("tile-listen", "speed tile: could not be drawn", e);
        }
    }

    @Override
    public void onStopListening() {
        if (listener == this) listener = null;
        super.onStopListening();
    }

    @Override
    public void onDestroy() {
        if (listener == this) listener = null;
        super.onDestroy();
    }

    @Override
    public void onClick() {
        super.onClick();
        try {
            // A remote action with side effects: not from a locked screen.
            if (isLocked()) unlockAndRun(this::tapped);
            else tapped();
        } catch (Throwable e) {
            Diag.problem("tile-click", "speed tile: a tap could not be handled", e);
        }
    }

    private void tapped() {
        try {
            Settings s = settings(this);
            Answer known = known(s);
            switch (QbSpeed.tap(s.base != null, busy.get(), known == null ? null : known.outcome)) {
                case SETTINGS:
                    openSettings();
                    break;
                case TOGGLE:
                    start(this, s, true);
                    render(s);
                    break;
                case REFRESH:
                    start(this, s, false);
                    render(s);
                    break;
                case IGNORE:
                default:
                    break;
            }
        } catch (Throwable e) {
            Diag.problem("tile-tap", "speed tile: a tap could not be handled", e);
        }
    }

    /** Opens the settings of the app and closes the panel: there is no address to talk to yet. */
    @SuppressWarnings("deprecation")
    private void openSettings() {
        Intent open = new Intent(this, MainActivity.class)
                .putExtra("tab", "settings")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(PendingIntent.getActivity(this, REQUEST_CODE, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
        } else {
            startActivityAndCollapse(open);
        }
    }

    // ---------- What is shown ----------

    /** Draws what is known: no address yet, a request on its way (the last state stays, under a "…"), or the last answer. */
    private void render(Settings s) {
        Tile tile = getQsTile();
        if (tile == null) return;
        Answer known = known(s);
        if (s.base == null) {
            show(tile, QbSpeed.look(QbSpeed.Outcome.NOT_CONFIGURED));
        } else if (busy.get() || known == null) {
            boolean lit = known != null ? QbSpeed.look(known.outcome).active : tile.getState() == Tile.STATE_ACTIVE;
            paint(tile, lit, QbSpeed.BUSY, QbSpeed.BUSY_STATE);
        } else {
            show(tile, QbSpeed.look(known.outcome));
        }
    }

    private static void show(Tile tile, QbSpeed.Look look) {
        paint(tile, look.active, look.subtitle, look.subtitle);
    }

    // Never STATE_UNAVAILABLE: a tile in that state swallows the taps, and an offline tile has to stay clickable to try again.
    private static void paint(Tile tile, boolean lit, String subtitle, String stateDescription) {
        tile.setState(lit ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        tile.setSubtitle(subtitle);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) tile.setStateDescription(stateDescription);
        tile.updateTile();
    }

    // ---------- What is known ----------

    private static Settings settings(Context context) {
        String url = "";
        String user = "";
        String pass = "";
        try {
            JSONObject cfg = new JSONObject(context.getSharedPreferences("config", Context.MODE_PRIVATE).getString("data", "{}"));
            url = text(cfg, "qbUrl");
            user = text(cfg, "username");
            pass = text(cfg, "password");
        } catch (Exception e) {
            // Settings that can't be read are no settings: the tile asks to set them up.
        }
        return new Settings(QbSpeed.baseUrl(url), user, pass);
    }

    /** A value of the settings as text: absent and null are empty (optString would say "null"). */
    private static String text(JSONObject cfg, String name) {
        return cfg.isNull(name) ? "" : cfg.optString(name, "");
    }

    /** The last answer, when it was read for these settings. */
    private static Answer known(Settings s) {
        Answer a = last;
        return a != null && a.key == s.key ? a : null;
    }

    private static boolean stale(Answer a) {
        return a == null || QbSpeed.stale(SystemClock.elapsedRealtime(), a.at);
    }

    // ---------- The requests ----------

    /** Asks the server, flipping the limits first when {@code toggle}. One request at a time: a tap on the way is ignored. */
    private static void start(Context context, Settings s, boolean toggle) {
        if (!busy.compareAndSet(false, true)) return;
        Context app = context.getApplicationContext();
        try {
            worker.execute(() -> run(app, s, toggle));
        } catch (Throwable e) {
            Diag.problem("tile-start", "speed tile: a request could not be started", e);
            finish(app, s, QbSpeed.Outcome.OFFLINE);
        }
    }

    /** On the worker thread. */
    private static void run(Context app, Settings s, boolean toggle) {
        QbSpeed.Outcome outcome = QbSpeed.Outcome.OFFLINE;
        try {
            QbSpeed.Result result = QbSpeed.exchange(new Network(s.base), toggle, s.user, s.pass, gate.allowed(s.key, SystemClock.elapsedRealtime()));
            gate.done(s.key, SystemClock.elapsedRealtime(), result);
            outcome = result.outcome;
            Diag.info("speed tile: " + (toggle ? "flipped" : "read") + ", " + outcome);
        } catch (Throwable e) {
            Diag.problem("tile-request", "speed tile: the request failed", e);
        }
        QbSpeed.Outcome answer = outcome;
        main.post(() -> finish(app, s, answer));
    }

    /** Main thread: keeps the answer, and draws it when the tile is on screen. */
    private static void finish(Context app, Settings s, QbSpeed.Outcome outcome) {
        last = new Answer(outcome, SystemClock.elapsedRealtime(), s.key);
        busy.set(false);
        try {
            SpeedTile tile = listener;
            if (tile == null) {
                TileService.requestListeningState(app, new ComponentName(app, SpeedTile.class));
                return;
            }
            // The settings may have changed while the request was on its way: what was read is then not for them.
            Settings now = settings(app);
            if (now.base != null && known(now) == null) start(app, now, false);
            tile.render(now);
        } catch (Throwable e) {
            Diag.problem("tile-update", "speed tile: could not be redrawn", e);
        }
    }
}

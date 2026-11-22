package dev.c411.qbittorrent;

import android.app.Activity;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.util.Arrays;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.UnaryOperator;

/**
 * The update of the app, from the release on GitHub to the installer of Android. There is one for the process: the web app, the periodic job and the
 * notifications all go through it. The decisions are in the pure classes (UpdateFlow, UpdateState, UpdateIntegrity…); this class carries them out, and
 * never lets an error out: a failure becomes the state "error" with a sentence for the user, and a line in the diagnostic log.
 *
 * <p>Threads: the web app calls on the thread of its bridge, which only reads and changes the state. The work (the request, the download, the
 * installation) runs on a thread of its own and the answers of the installer on another, so that a long download never holds up a broadcast. The state
 * is an immutable snapshot, replaced under {@link #lock} and pushed to the web app in the same order. It does not use {@link Engine}, which builds a
 * WebView: the process may have been started by the job or by a broadcast, with no screen.
 */
final class Updater {
    static final String ACTION_INSTALL_STATUS = "dev.c411.qbittorrent.action.INSTALL_STATUS";
    static final String ACTION_INSTALL_UPDATE = "dev.c411.qbittorrent.action.INSTALL_UPDATE";
    static final String EXTRA_KEY = "updateKey";
    static final int JOB_ID = 4110;

    private static final long WATCHDOG_MS = 120_000L;
    private static final String UPDATES_DIR = "updates";
    private static final String BUSY = "Une opération est déjà en cours : réessayez dans un instant";
    private static final String PERMISSION_HELP = "Ouvrez Réglages > Applications > Arr411 > Installer des applis inconnues, puis réessayez";
    private static final String PERMISSION_DENIED = "Autorisation refusée : Arr411 ne peut pas installer sa mise à jour";

    /** Where the new states go: the web app, through the activity. */
    interface Sink {
        void push(String json);
    }

    /** What started a check. Only the checks of the app itself may download or install by themselves. */
    private enum Origin {
        MANUAL,
        STARTUP,
        JOB
    }

    private static Updater instance;

    private final Context app;
    private final UpdateStore store;
    private final ThreadPoolExecutor work = pool("arr411-update");
    private final ThreadPoolExecutor events = pool("arr411-update-events");
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Object lock = new Object();

    private volatile Sink sink;
    private volatile UpdateState state = UpdateState.initial("", UpdateFlow.AUTO);
    private volatile WeakReference<Activity> front = new WeakReference<>(null);
    private volatile boolean foreground;
    private volatile boolean awaitingPermission;
    private volatile boolean jobStopped;
    private volatile String current = "";
    private volatile String userAgent = "Arr411-Android/unknown";
    private boolean loaded; // under lock
    private long lastManual = -1; // under lock
    private int watchdog; // under lock

    private Updater(Context context) {
        app = context;
        store = new UpdateStore(context);
    }

    static synchronized Updater get(Context context) {
        if (instance == null) {
            Context application = context.getApplicationContext();
            instance = new Updater(application != null ? application : context);
        }
        return instance;
    }

    /** An action that runs once, however many times it is called: a broadcast or a job has to be finished exactly once. */
    static Runnable once(Runnable action) {
        AtomicBoolean done = new AtomicBoolean();
        return () -> {
            if (done.compareAndSet(false, true)) action.run();
        };
    }

    // ---------- The web app ----------

    void setSink(Sink target) {
        sink = target;
    }

    /** Takes {@code target} away, unless a newer activity has already put its own. */
    void clearSink(Sink target) {
        if (sink == target) sink = null;
    }

    /** The state, as the object of the contract. */
    String info() {
        try {
            ensureLoaded();
            return state.toJson();
        } catch (Throwable e) {
            unexpected(e);
            return UpdateState.initial("", UpdateFlow.AUTO).toJson();
        }
    }

    /** The user asks for a check. The state is "checking" when this returns. */
    void check() {
        try {
            ensureLoaded();
            synchronized (lock) {
                UpdateState s = state;
                long now = SystemClock.elapsedRealtime();
                if (!s.supported || s.busy() || !UpdateThrottle.manualAllowed(now, lastManual)) {
                    push(s);
                    return;
                }
                lastManual = now;
                set(s.checking());
            }
            if (!submit(() -> runCheck(Origin.MANUAL))) update(s -> s.failed(BUSY));
        } catch (Throwable e) {
            unexpected(e);
        }
    }

    /** The user asks to install the update. The state is no longer idle when this returns, unless Android has to be asked for a permission first. */
    void install() {
        try {
            ensureLoaded();
            UpdateState s = state;
            if (!s.canInstall() || s.busy()) {
                push(s);
                return;
            }
            if (!app.getPackageManager().canRequestPackageInstalls()) {
                requestPermission();
                return;
            }
            beginInstall();
        } catch (Throwable e) {
            unexpected(e);
        }
    }

    /** The setting autoUpdate may have changed. */
    void onModeChanged() {
        try {
            submit(() -> {
                ensureLoaded();
                String mode = readMode();
                synchronized (lock) {
                    if (mode.equals(state.mode)) return;
                    set(state.withMode(mode));
                }
                applyJobPlan();
                UpdateState s = state;
                if (UpdateFlow.checksOnStartup(mode, System.currentTimeMillis(), s.checkedAt)) {
                    if (beginAutomatic()) runCheck(Origin.STARTUP);
                } else if (UpdateFlow.AUTO.equals(mode) && !s.busy()) {
                    evaluate(Origin.STARTUP);
                }
            });
        } catch (Throwable e) {
            unexpected(e);
        }
    }

    // ---------- The screen ----------

    void onResume(Activity activity) {
        try {
            front = new WeakReference<>(activity);
            foreground = true;
            if (awaitingPermission) {
                awaitingPermission = false;
                submit(this::continueAfterPermission);
            }
        } catch (Throwable e) {
            unexpected(e);
        }
    }

    void onPause(Activity activity) {
        try {
            if (front.get() == activity) {
                foreground = false;
                front = new WeakReference<>(null);
            }
        } catch (Throwable e) {
            unexpected(e);
        }
    }

    /** A tap on a notification came to the activity: it installs only with the key that the notifications of this installation carry. */
    void onTap(Activity activity, String key) {
        try {
            front = new WeakReference<>(activity);
            submit(() -> {
                ensureLoaded();
                if (!UpdateFlow.validTap(store.tapKey(), key)) {
                    Diag.warn("update: a request to install came without the key of this installation, ignored");
                    return;
                }
                install();
            });
        } catch (Throwable e) {
            unexpected(e);
        }
    }

    // ---------- The system ----------

    /** The app starts: it sets the job up, and checks a little later if the last check is old. */
    void onAppStart() {
        try {
            submit(() -> {
                ensureLoaded();
                applyJobPlan();
            });
            main.postDelayed(() -> submit(this::startupCheck), UpdateThrottle.STARTUP_DELAY_MS);
        } catch (Throwable e) {
            unexpected(e);
        }
    }

    /**
     * The periodic job starts. False when there is nothing to run (the job is then over); true when {@code done} will be called, whatever happens, to
     * tell the scheduler.
     */
    boolean runScheduled(Runnable done) {
        return submit(() -> {
            try {
                jobStopped = false;
                ensureLoaded();
                UpdateState s = state;
                if (UpdateFlow.OFF.equals(s.mode) || !UpdateThrottle.jobDue(System.currentTimeMillis(), s.checkedAt)) return;
                if (beginAutomatic()) runCheck(Origin.JOB);
            } finally {
                done.run();
            }
        });
    }

    /** The scheduler stops the job: a download that belongs to it stops too. */
    void cancelWork() {
        jobStopped = true;
    }

    /** The installer answers. {@code done} is called once the answer is handled. */
    void onInstallStatus(Intent intent, Runnable done) {
        boolean queued = submitEvent(() -> {
            try {
                handleInstallStatus(intent);
            } finally {
                done.run();
            }
        });
        if (!queued) done.run();
    }

    /** The app has just been replaced by a new version. */
    void onPackageReplaced(Runnable done) {
        boolean queued = submitEvent(() -> {
            try {
                ensureLoaded();
                UpdateNotifier.cancelPending(app);
                if (UpdateFlow.shouldAnnounceInstall(store.installing(), current)) {
                    UpdateNotifier.post(app, UpdateNotifier.Kind.INSTALLED, current, store.tapKey());
                }
                store.clearInstalling();
                applyJobPlan();
            } finally {
                done.run();
            }
        });
        if (!queued) done.run();
    }

    // ---------- State ----------

    /** Replaces the state and tells the web app. Under the lock. */
    private void set(UpdateState next) {
        state = next;
        push(next);
    }

    private UpdateState update(UnaryOperator<UpdateState> change) {
        synchronized (lock) {
            UpdateState next = change.apply(state);
            set(next);
            return next;
        }
    }

    private void push(UpdateState snapshot) {
        Sink target = sink;
        if (target == null) return;
        try {
            target.push(snapshot.toJson());
        } catch (RuntimeException e) {
            Diag.problem("update-push", "update: the state could not be sent to the page [" + e.getClass().getSimpleName() + "]", null);
        }
    }

    /** Reads what the app remembers, once. Under the lock, so that nothing else runs on a state that is half built. */
    private void ensureLoaded() {
        synchronized (lock) {
            if (loaded) return;
            loaded = true;
            try {
                PackageInfo info = app.getPackageManager().getPackageInfo(app.getPackageName(), 0);
                current = info.versionName == null ? "" : info.versionName;
                String version = UpdateVersion.normalize(current);
                userAgent = "Arr411-Android/" + (version == null ? "unknown" : version);
                store.bind(current);
                UpdateState loadedState = UpdateState.initial(current, readMode());
                String json = store.json();
                if (json != null) {
                    try {
                        UpdateRelease cached = UpdateRelease.parse(json);
                        loadedState = loadedState.released(cached, store.checkedAt(), cached != null && fileReady(cached));
                    } catch (RuntimeException e) {
                        store.saveCheck(null, null, 0); // an answer that cannot be read is not kept
                    }
                }
                state = loadedState;
                purge(null);
            } catch (PackageManager.NameNotFoundException | RuntimeException e) {
                Diag.problem("update-load", "update: the state could not be loaded [" + e.getClass().getSimpleName() + "]", null);
            }
        }
    }

    private String readMode() {
        return UpdateFlow.modeFromConfig(app.getSharedPreferences("config", Context.MODE_PRIVATE).getString("data", "{}"));
    }

    /** A check or a download starts by itself: false when something else is going on or the user turned the update off. */
    private boolean beginAutomatic() {
        synchronized (lock) {
            UpdateState s = state;
            if (!s.supported || s.busy() || UpdateFlow.OFF.equals(s.mode)) return false;
            set(s.checking());
            return true;
        }
    }

    // ---------- Threads ----------

    private static ThreadPoolExecutor pool(String name) {
        ThreadPoolExecutor pool = new ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS, new LinkedBlockingQueue<>(), runnable -> {
            Thread thread = new Thread(runnable, name);
            thread.setDaemon(true);
            thread.setUncaughtExceptionHandler((t, e) -> Diag.uncaught(t, e, false));
            return thread;
        });
        pool.allowCoreThreadTimeOut(true);
        return pool;
    }

    private boolean submit(Runnable task) {
        return execute(work, task);
    }

    private boolean submitEvent(Runnable task) {
        return execute(events, task);
    }

    private static boolean execute(ThreadPoolExecutor pool, Runnable task) {
        try {
            pool.execute(() -> {
                try {
                    task.run();
                } catch (Throwable e) {
                    unexpected(e);
                }
            });
            return true;
        } catch (RejectedExecutionException e) {
            return false;
        }
    }

    // ---------- Checking ----------

    private void startupCheck() {
        ensureLoaded();
        UpdateState s = state;
        if (UpdateFlow.checksOnStartup(s.mode, System.currentTimeMillis(), s.checkedAt) && beginAutomatic()) runCheck(Origin.STARTUP);
    }

    private void runCheck(Origin origin) {
        UpdateRelease release = null;
        try {
            ensureLoaded();
            long now = System.currentTimeMillis();
            release = fetchLatest(now);
            UpdateRelease latest = release;
            boolean ready = latest != null && fileReady(latest);
            update(s -> s.released(latest, now, ready));
            Diag.info("update: checked (" + origin + "), installed " + current + ", latest " + (latest == null ? "none" : latest.version));
        } catch (Throwable e) {
            fail(e, release);
            return;
        }
        evaluate(origin);
    }

    /** The answer of GitHub, from the network or from the copy that GitHub said had not changed; null when there is no release to offer. */
    private UpdateRelease fetchLatest(long now) throws IOException {
        String cached = store.json();
        String etag = cached == null ? null : UpdateFlow.etagOrNull(store.etag());
        try (UpdateNet.Reply reply = UpdateNet.get(UpdateHosts.API_LATEST, userAgent, UpdateNet.Kind.API, etag)) {
            if (reply.status == 304) {
                if (cached == null) throw new UpdateFailure(UpdateFlow.httpMessage(304), false);
                store.touchChecked(now);
                try {
                    return parse(cached);
                } catch (UpdateFailure e) {
                    store.saveCheck(null, null, 0);
                    throw e;
                }
            }
            if (reply.status != 200) throw new UpdateFailure(UpdateFlow.httpMessage(reply.status), false);
            String text;
            try (InputStream in = reply.body()) {
                text = BodyReader.readText(in, reply.length(), UpdateRelease.ANSWER_MAX_BYTES);
            }
            UpdateRelease release = parse(text);
            store.saveCheck(UpdateFlow.etagOrNull(reply.header("ETag")), text.length() <= UpdateRelease.CACHE_MAX_CHARS ? text : null, now);
            return release;
        }
    }

    private static UpdateRelease parse(String json) throws UpdateFailure {
        try {
            return UpdateRelease.parse(json);
        } catch (RuntimeException e) {
            throw new UpdateFailure("Réponse de GitHub illisible", false);
        }
    }

    /** What follows a check: nothing, a notification, or a download and what comes after it. Never throws. */
    private void evaluate(Origin origin) {
        UpdateRelease release = state.release;
        try {
            afterCheck(origin);
        } catch (Throwable e) {
            fail(e, release);
        }
    }

    private void afterCheck(Origin origin) throws IOException {
        UpdateState s = state;
        UpdateRelease release = s.release;
        if (release == null) return;
        boolean blocked = UpdateVersion.same(store.blocked(), release.version);
        switch (UpdateFlow.afterCheck(s.mode, origin != Origin.MANUAL, s.available(), release.installable(), s.apkReady, blocked)) {
            case NOTIFY_AVAILABLE:
                notifyOnce(UpdateNotifier.Kind.AVAILABLE, release.version);
                break;
            case DOWNLOAD:
                afterDownload(release, download(release, origin));
                break;
            case ASSESS_READY:
                afterDownload(release, apkFile(release.version));
                break;
            default:
                break;
        }
    }

    // ---------- Downloading ----------

    /** Downloads the file of the release and checks it against every hash that the release publishes. The file in the cache is verified when this returns. */
    private File download(UpdateRelease release, Origin origin) throws IOException {
        if (!release.installable()) throw new UpdateFailure("Installation dans l’app impossible : aucune empreinte publiée", false);
        File dir = updatesDir();
        if (!dir.isDirectory() && !dir.mkdirs()) throw new UpdateFailure("Espace de stockage indisponible", false);
        if (!UpdateIntegrity.hasRoom(dir.getUsableSpace(), release.size)) throw new UpdateFailure("Espace de stockage insuffisant", false);
        update(s -> s.downloading(0));
        String expected = expectedHash(release);
        File part = new File(dir, UpdateFiles.partName(release.version));
        File apk = new File(dir, UpdateFiles.apkName(release.version));
        String sha256;
        boolean finished = false;
        try {
            Diag.info("update: downloading " + release.version + " (" + release.size + " bytes)");
            HashedCopy.Result copied;
            try (UpdateNet.Reply reply = UpdateNet.get(release.apkUrl, userAgent, UpdateNet.Kind.DOWNLOAD, null)) {
                if (reply.status != 200) throw new UpdateFailure(UpdateFlow.httpMessage(reply.status), false);
                if (!UpdateIntegrity.declaredSizeOk(reply.length(), release.size)) throw new UpdateFailure("Taille annoncée incorrecte", true);
                try (InputStream in = reply.body(); FileOutputStream out = new FileOutputStream(part)) {
                    copied = HashedCopy.copy(in, out, release.size, progress(release, origin));
                    out.flush();
                    out.getFD().sync();
                } catch (HashedCopy.LimitException e) {
                    throw new UpdateFailure("Taille du fichier incorrecte", true);
                }
            }
            if (copied.bytes < release.size) throw new UpdateFailure("Téléchargement incomplet : réessayez", false);
            String problem = UpdateIntegrity.verify(release.size, copied.bytes, copied.sha256, expected);
            if (problem != null) throw new UpdateFailure(problem, true);
            if (apk.exists() && !apk.delete()) throw new UpdateFailure("Impossible d’enregistrer le fichier téléchargé", false);
            if (!part.renameTo(apk)) throw new UpdateFailure("Impossible d’enregistrer le fichier téléchargé", false);
            sha256 = copied.sha256;
            finished = true;
        } finally {
            if (!finished && !part.delete() && part.exists()) {
                Diag.problem("update-part", "update: a partial download could not be deleted", null);
            }
        }
        String archive = UpdateInstaller.archiveProblem(app, apk);
        if (archive != null) throw new UpdateFailure(archive, true);
        store.setReady(release.version, sha256, release.size);
        purge(apk.getName());
        update(UpdateState::ready);
        Diag.info("update: " + release.version + " is downloaded and verified");
        return apk;
    }

    /** The SHA-256 the file has to have, from the API and from SHA256SUMS.txt when the release has one; every source has to agree. */
    private String expectedHash(UpdateRelease release) throws IOException {
        String sums = null;
        if (release.sumsPresent) {
            if (release.sumsUrl == null) throw new UpdateFailure("SHA256SUMS.txt est introuvable ou illisible", false);
            try (UpdateNet.Reply reply = UpdateNet.get(release.sumsUrl, userAgent, UpdateNet.Kind.DOWNLOAD, null)) {
                if (reply.status != 200) throw new UpdateFailure("SHA256SUMS.txt est introuvable ou illisible", false);
                try (InputStream in = reply.body()) {
                    sums = BodyReader.readText(in, reply.length(), UpdateIntegrity.MAX_SUMS_BYTES);
                }
            }
        }
        UpdateIntegrity.Expected expected = UpdateIntegrity.expected(release.digest, release.digestInvalid, release.sumsPresent, sums, UpdateRelease.APK_NAME);
        if (expected.error != null) throw new UpdateFailure(expected.error, true);
        return expected.hash;
    }

    private HashedCopy.Listener progress(UpdateRelease release, Origin origin) {
        long deadline = SystemClock.elapsedRealtime() + UpdateThrottle.DOWNLOAD_DEADLINE_MS;
        long[] lastPush = {-1};
        return new HashedCopy.Listener() {
            @Override
            public void progress(long copied) {
                long now = SystemClock.elapsedRealtime();
                if (!UpdateThrottle.progressDue(now, lastPush[0])) return;
                lastPush[0] = now;
                double fraction = (double) copied / release.size;
                update(s -> s.downloading(fraction));
            }

            @Override
            public boolean cancelled() {
                return (origin == Origin.JOB && jobStopped) || SystemClock.elapsedRealtime() > deadline;
            }
        };
    }

    /** The update is downloaded without the user having asked to install it: install it, or tell the user, as the mode and the system allow. */
    private void afterDownload(UpdateRelease release, File apk) throws IOException {
        boolean tried = UpdateVersion.same(store.tried(), release.version);
        boolean silent = UpdateFlow.silentPossible(Build.VERSION.SDK_INT, app.getApplicationInfo().targetSdkVersion);
        switch (UpdateFlow.afterReady(state.mode, foreground, app.getPackageManager().canRequestPackageInstalls(), silent, tried)) {
            case INSTALL:
                store.setTried(release.version);
                installStaged(release, apk);
                break;
            case NOTIFY_READY:
                notifyOnce(UpdateNotifier.Kind.READY, release.version);
                break;
            case NOTIFY_PERMISSION:
                notifyOnce(UpdateNotifier.Kind.PERMISSION, release.version);
                break;
            default:
                break;
        }
    }

    // ---------- Installing ----------

    private void beginInstall() {
        synchronized (lock) {
            UpdateState s = state;
            if (!s.canInstall() || s.busy()) {
                push(s);
                return;
            }
            set(s.apkReady ? s.installing() : s.downloading(0));
        }
        if (!submit(this::installTask)) update(s -> s.failed(BUSY));
    }

    private void installTask() {
        UpdateRelease release = null;
        try {
            ensureLoaded();
            release = state.release;
            if (release == null) throw new UpdateFailure("Aucune mise à jour à installer", false);
            store.clearBlocked();
            installStaged(release, fileReady(release) ? apkFile(release.version) : download(release, Origin.MANUAL));
        } catch (Throwable e) {
            fail(e, release);
        }
    }

    /** Hands the verified file to the installer. The answer comes later, to {@link #handleInstallStatus}. */
    private void installStaged(UpdateRelease release, File apk) throws IOException {
        update(UpdateState::installing);
        String problem = UpdateInstaller.archiveProblem(app, apk);
        if (problem != null) throw new UpdateFailure(problem, true);
        UpdateNotifier.cancelPending(app);
        // An older session that is abandoned answers too: it must not find the marker of this one.
        store.clearInstalling();
        try {
            int session = UpdateInstaller.start(app, apk, release.size, store.readySha(), id -> store.setInstalling(release.version, id));
            Diag.info("update: installing " + release.version + " (session " + session + ")");
        } catch (IOException | RuntimeException e) {
            store.clearInstalling();
            throw e;
        }
        armWatchdog();
    }

    /** The installation needs the permission to install apps: the settings of Android are opened, and the work goes on when the user comes back. */
    private void requestPermission() {
        Activity activity = frontActivity();
        if (activity == null) {
            UpdateRelease release = state.release;
            if (release != null) notifyOnce(UpdateNotifier.Kind.PERMISSION, release.version);
            return;
        }
        Intent settings = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + app.getPackageName()));
        activity.runOnUiThread(() -> {
            try {
                awaitingPermission = true;
                activity.startActivity(settings);
            } catch (RuntimeException e) {
                awaitingPermission = false;
                Diag.problem("update-settings", "update: the settings of Android could not be opened [" + e.getClass().getSimpleName() + "]", null);
                update(s -> s.failed(PERMISSION_HELP));
            }
        });
    }

    private void continueAfterPermission() {
        ensureLoaded();
        if (app.getPackageManager().canRequestPackageInstalls()) beginInstall();
        else update(s -> s.failed(PERMISSION_DENIED));
    }

    private Activity frontActivity() {
        Activity activity = front.get();
        return activity == null || activity.isFinishing() || activity.isDestroyed() ? null : activity;
    }

    private void armWatchdog() {
        int token = bumpWatchdog();
        main.postDelayed(() -> submitEvent(() -> {
            synchronized (lock) {
                if (token != watchdog || state.status != UpdateState.Status.INSTALLING) return;
                set(state.failed("L’installation n’a pas répondu : réessayez"));
            }
        }), WATCHDOG_MS);
    }

    private int bumpWatchdog() {
        synchronized (lock) {
            return ++watchdog;
        }
    }

    /** The installer has answered about the session that was committed. */
    private void handleInstallStatus(Intent intent) {
        int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, Integer.MIN_VALUE);
        if (status == Integer.MIN_VALUE) return;
        int session = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1);
        ensureLoaded();
        if (session != -1 && session != store.session()) {
            Diag.info("update: the installer answered about an older session, ignored");
            return;
        }
        bumpWatchdog();
        switch (UpdateInstallStatus.kind(status)) {
            case PENDING: {
                Intent confirm = confirmIntent(intent);
                if (confirm == null) {
                    store.clearInstalling();
                    update(s -> s.failed("L’installation n’a pas pu démarrer"));
                    break;
                }
                Diag.info("update: the system asks the user to confirm the installation");
                update(s -> s.status == UpdateState.Status.INSTALLING ? s.ready() : s);
                showConfirm(confirm);
                break;
            }
            case SUCCESS:
                // The app is replaced, and this process ends: the new one announces it (see onPackageReplaced), which needs the marker.
                Diag.info("update: the installer reports that the update is installed");
                break;
            case CANCELLED:
                Diag.info("update: the installation was cancelled");
                store.clearInstalling();
                UpdateNotifier.cancel(app, UpdateNotifier.Kind.CONFIRM);
                update(UpdateState::aborted);
                break;
            case FAILED:
                Diag.warn("update: the installer failed with status " + status + ": " + statusMessage(intent));
                store.clearInstalling();
                UpdateNotifier.cancel(app, UpdateNotifier.Kind.CONFIRM);
                update(s -> s.failed(UpdateInstallStatus.message(status)));
                break;
            default:
                break;
        }
    }

    /** The dialog of the system that asks the user to confirm: from the screen if it is in front (Android refuses to start it from the background), else from a notification. */
    private void showConfirm(Intent confirm) {
        UpdateRelease release = state.release;
        String version = release == null ? "" : release.version;
        Activity activity = foreground ? frontActivity() : null;
        if (activity == null) {
            notifyOnce(UpdateNotifier.Kind.CONFIRM, version);
            return;
        }
        activity.runOnUiThread(() -> {
            try {
                activity.startActivity(confirm);
            } catch (RuntimeException e) {
                Diag.problem("update-confirm", "update: the confirmation could not be shown [" + e.getClass().getSimpleName() + "]", null);
                submitEvent(() -> notifyOnce(UpdateNotifier.Kind.CONFIRM, version));
            }
        });
    }

    @SuppressWarnings("deprecation") // the typed version of getParcelableExtra needs API 33
    private static Intent confirmIntent(Intent status) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return status.getParcelableExtra(Intent.EXTRA_INTENT, Intent.class);
            return status.getParcelableExtra(Intent.EXTRA_INTENT);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String statusMessage(Intent status) {
        String message = status.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
        if (message == null) return "";
        return message.length() > 200 ? message.substring(0, 200) : message;
    }

    // ---------- Failing ----------

    /** Turns an error into the state "error", and takes back what a file that failed its verification left. */
    private void fail(Throwable e, UpdateRelease release) {
        String message = UpdateFlow.describe(e);
        boolean integrity = e instanceof UpdateFailure && ((UpdateFailure) e).integrity;
        // Only the class and where it was thrown: the message of a network error can carry an address, and the addresses of the CDN are signed.
        Diag.warn("update: " + message + " [" + e.getClass().getSimpleName() + (e instanceof UpdateFailure || e instanceof IOException ? "" : where(e)) + "]");
        try {
            if (integrity && release != null) {
                store.setBlocked(release.version);
                store.clearReady();
                deleteFiles(release.version);
            }
        } catch (RuntimeException ignored) {
        }
        boolean dropFile = integrity && release != null;
        update(s -> dropFile ? s.withoutFile().failed(message) : s.failed(message));
    }

    private static void unexpected(Throwable e) {
        Diag.problem("update-unexpected", "update: unexpected " + e.getClass().getSimpleName() + where(e), null);
    }

    private static String where(Throwable e) {
        StackTraceElement[] stack = e.getStackTrace();
        if (stack.length == 0) return "";
        String name = stack[0].getClassName();
        return " at " + name.substring(name.lastIndexOf('.') + 1) + "." + stack[0].getMethodName() + ":" + stack[0].getLineNumber();
    }

    // ---------- Notifications ----------

    /** Posts the notification unless this kind was already posted for this version. */
    private void notifyOnce(UpdateNotifier.Kind kind, String version) {
        if (!UpdateFlow.shouldNotify(store.notified(kind.key), kind.key, version)) return;
        if (UpdateNotifier.post(app, kind, version, store.tapKey())) store.setNotified(kind.key, UpdateFlow.notifyKey(kind.key, version));
    }

    // ---------- The job ----------

    /** Makes the periodic job what the mode asks for: scheduled, kept as it is, or cancelled. */
    private void applyJobPlan() {
        try {
            JobScheduler scheduler = app.getSystemService(JobScheduler.class);
            if (scheduler == null) return;
            JobInfo pending = scheduler.getPendingJob(JOB_ID);
            UpdateFlow.JobPlan plan = UpdateFlow.jobPlan(state.mode, pending != null, pending == null ? 0 : pending.getIntervalMillis(),
                    pending != null && pending.isPersisted());
            if (plan == UpdateFlow.JobPlan.SCHEDULE) {
                JobInfo job = new JobInfo.Builder(JOB_ID, new ComponentName(app, UpdateJobService.class))
                        .setPeriodic(UpdateThrottle.PERIOD_MS, UpdateThrottle.FLEX_MS)
                        .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                        .setPersisted(true)
                        .build();
                if (scheduler.schedule(job) != JobScheduler.RESULT_SUCCESS) Diag.warn("update: the periodic check could not be scheduled");
            } else if (plan == UpdateFlow.JobPlan.CANCEL) {
                scheduler.cancel(JOB_ID);
            }
        } catch (RuntimeException e) {
            Diag.problem("update-job", "update: the periodic check could not be set up [" + e.getClass().getSimpleName() + "]", null);
        }
    }

    // ---------- Files ----------

    private File updatesDir() {
        return new File(app.getCacheDir(), UPDATES_DIR);
    }

    private File apkFile(String version) {
        return new File(updatesDir(), UpdateFiles.apkName(version));
    }

    /** True when the file that was verified is the one of this release and is still in the cache. */
    private boolean fileReady(UpdateRelease release) {
        if (UpdateFiles.apkName(release.version) == null || !release.isFile(store.readyVersion(), store.readySize(), store.readySha())) return false;
        File apk = apkFile(release.version);
        return apk.isFile() && apk.length() == release.size;
    }

    private void deleteFiles(String version) {
        String apk = UpdateFiles.apkName(version);
        String part = UpdateFiles.partName(version);
        if (apk == null || part == null) return;
        new File(updatesDir(), apk).delete();
        new File(updatesDir(), part).delete();
    }

    /** Keeps the file in the cache that is {@code keep} (or the newest newer than the installed version when null) and removes the rest. */
    private void purge(String keep) {
        try {
            String[] names = updatesDir().list();
            if (names == null) return;
            for (String name : UpdateFiles.toDelete(Arrays.asList(names), current, keep)) {
                File file = new File(updatesDir(), name);
                if (!file.delete() && file.exists()) Diag.problem("update-purge", "update: an old file could not be deleted", null);
            }
        } catch (RuntimeException e) {
            Diag.problem("update-purge", "update: the cache could not be cleaned [" + e.getClass().getSimpleName() + "]", null);
        }
    }
}

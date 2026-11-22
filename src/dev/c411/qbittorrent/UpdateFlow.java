package dev.c411.qbittorrent;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.Map;
import javax.net.ssl.SSLException;

/** The decisions of the update that do not need Android: what to do after a check, whether to install by itself, which job to keep, what to tell the user. */
final class UpdateFlow {
    static final String AUTO = "auto";
    static final String NOTIFY = "notify";
    static final String OFF = "off";

    private UpdateFlow() {}

    /** What follows a check that found out whether an update exists. */
    enum AfterCheck {
        NONE,
        /** Tell the user that a version exists; nothing is downloaded. */
        NOTIFY_AVAILABLE,
        DOWNLOAD,
        /** The file is already there: go on with {@link #afterReady}. */
        ASSESS_READY
    }

    /** What follows a verified download of an update the user did not ask to install. */
    enum AfterReady {
        NONE,
        INSTALL,
        /** Tell the user that the update is ready to install. */
        NOTIFY_READY,
        /** Tell the user to allow the app to install updates. */
        NOTIFY_PERMISSION
    }

    enum JobPlan {
        SCHEDULE,
        KEEP,
        CANCEL,
        NOTHING
    }

    /** The mode of the setting autoUpdate: absent or empty is auto; a value that is not one of the three modes is read as notify, which never installs anything. */
    static String mode(String raw) {
        if (raw == null) return AUTO;
        String mode = raw.trim().toLowerCase(Locale.ROOT);
        if (mode.isEmpty()) return AUTO;
        if (mode.equals(AUTO) || mode.equals(NOTIFY) || mode.equals(OFF)) return mode;
        return NOTIFY;
    }

    /**
     * The mode in the saved settings (the text of a JSON object): auto when they do not mention it. A value that is not a text, or settings that cannot
     * be read although they mention it, count as notify: nothing is installed when the choice of the user is not known.
     */
    static String modeFromConfig(String json) {
        if (json == null || !json.contains("\"autoUpdate\"")) return AUTO;
        try {
            Object root = UpdateJson.parse(json);
            if (!(root instanceof Map)) return NOTIFY;
            Object value = ((Map<?, ?>) root).get("autoUpdate");
            if (value == null) return AUTO;
            return value instanceof String ? mode((String) value) : NOTIFY;
        } catch (RuntimeException e) {
            return NOTIFY;
        }
    }

    /**
     * Whether Android installs an update of the app itself without asking, once the app is allowed to install apps, has the permission to update
     * without user action and asks for no user action: it depends on the version of Android and on the targetSdk of the app. Before Android 12 the user
     * always confirms, and a version that is not known is assumed to ask too.
     */
    static boolean silentPossible(int sdk, int targetSdk) {
        if (sdk < 31 || sdk > 37) return false;
        int minimum;
        if (sdk <= 32) minimum = 29;
        else if (sdk == 33) minimum = 30;
        else if (sdk == 34) minimum = 31;
        else if (sdk == 35) minimum = 33;
        else if (sdk == 36) minimum = 34;
        else minimum = 35;
        return targetSdk >= minimum;
    }

    /**
     * What to do once a check is over. Only the checks that the app runs by itself act: one the user asked for just shows its result. {@code installable}
     * is whether the release can be installed from the app, {@code blocked} whether the verification of its file failed before. A mode that is not
     * auto never downloads anything.
     */
    static AfterCheck afterCheck(String mode, boolean automatic, boolean available, boolean installable, boolean apkReady, boolean blocked) {
        if (!automatic || !available || OFF.equals(mode)) return AfterCheck.NONE;
        if (!AUTO.equals(mode) || !installable || blocked) return AfterCheck.NOTIFY_AVAILABLE;
        return apkReady ? AfterCheck.ASSESS_READY : AfterCheck.DOWNLOAD;
    }

    /**
     * What to do with an update that is downloaded and verified when the user did not ask for it. It is installed by itself only in auto mode, while
     * no screen of the app is in front, when the app may install, Android would not ask the user and the app did not already try for this version
     * (so that an install that fails does not start again at every check); in every other case the user is told.
     */
    static AfterReady afterReady(String mode, boolean foreground, boolean mayInstall, boolean silentPossible, boolean alreadyTried) {
        if (!AUTO.equals(mode)) return AfterReady.NONE;
        if (foreground || alreadyTried) return AfterReady.NOTIFY_READY;
        if (!mayInstall) return AfterReady.NOTIFY_PERMISSION;
        return silentPossible ? AfterReady.INSTALL : AfterReady.NOTIFY_READY;
    }

    /** What to do with the periodic job: none when the mode is off, and the one that exists when it already has the right period and survives a reboot (scheduling again would restart its period). */
    static JobPlan jobPlan(String mode, boolean pendingExists, long pendingInterval, boolean pendingPersisted) {
        if (OFF.equals(mode)) return pendingExists ? JobPlan.CANCEL : JobPlan.NOTHING;
        if (pendingExists && pendingInterval == UpdateThrottle.PERIOD_MS && pendingPersisted) return JobPlan.KEEP;
        return JobPlan.SCHEDULE;
    }

    /** True when the app should check shortly after it starts. */
    static boolean checksOnStartup(String mode, long now, long lastCheckedAt) {
        return !OFF.equals(mode) && UpdateThrottle.periodDue(now, lastCheckedAt);
    }

    /** What identifies a notification, so that one version is announced once for each kind; null when the version is not one. */
    static String notifyKey(String kind, String version) {
        String v = UpdateVersion.normalize(version);
        return v == null || kind == null ? null : kind + ":" + v;
    }

    /** True when the notification of this kind for this version was not posted yet; {@code last} is the key of the last one posted. */
    static boolean shouldNotify(String last, String kind, String version) {
        String key = notifyKey(kind, version);
        return key != null && !key.equals(last);
    }

    /** Null when the downloaded file is an update of this app, otherwise why not (a French sentence). {@code readable} is false when Android could not read it as an APK. */
    static String archiveProblem(String ownPackage, long ownCode, String archivePackage, long archiveCode, boolean readable) {
        if (!readable) return "Le fichier téléchargé n’est pas une application valide";
        if (ownPackage == null || !ownPackage.equals(archivePackage)) return "Le fichier téléchargé n’est pas Arr411";
        if (archiveCode <= ownCode) return "La version téléchargée n’est pas plus récente que celle installée";
        return null;
    }

    /** True when the app that has just been replaced is the version the updater was installing. */
    static boolean shouldAnnounceInstall(String markerVersion, String installedVersion) {
        return markerVersion != null && UpdateVersion.same(markerVersion, installedVersion);
    }

    /** The entity tag that GitHub sent, when it can be sent back in a header; null for anything else. */
    static String etagOrNull(String etag) {
        if (etag == null || etag.isEmpty() || etag.length() > 200) return null;
        for (int i = 0; i < etag.length(); i++) {
            char c = etag.charAt(i);
            if (c < 0x21 || c > 0x7e) return null;
        }
        return etag;
    }

    /** True when the key that came with a tap on a notification is the one of this installation. */
    static boolean validTap(String expected, String given) {
        if (expected == null || expected.isEmpty() || given == null) return false;
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), given.getBytes(StandardCharsets.UTF_8));
    }

    /** What to tell the user when GitHub answers a status that is not the release. */
    static String httpMessage(int status) {
        if (status == 403 || status == 429) return "GitHub limite temporairement les requêtes : réessayez plus tard";
        if (status == 404) return "Aucune release trouvée sur GitHub";
        if (status >= 500 && status < 600) return "GitHub est momentanément indisponible (HTTP " + status + ")";
        return "Réponse inattendue de GitHub (HTTP " + status + ")";
    }

    /** What to tell the user when a request or a download fails; the message of the exception is not used, it may name a path or an address. */
    static String describe(Throwable e) {
        if (e instanceof UpdateFailure && e.getMessage() != null && !e.getMessage().isEmpty()) return e.getMessage();
        if (e instanceof UnknownHostException || e instanceof ConnectException || e instanceof NoRouteToHostException) return "Pas de connexion à GitHub";
        if (e instanceof SocketTimeoutException) return "GitHub ne répond pas : délai dépassé";
        if (e instanceof SSLException) return "Connexion sécurisée impossible : vérifiez la date de l’appareil";
        if (e instanceof BodyReader.TooLargeException) return "Réponse de GitHub trop volumineuse";
        if (e instanceof HashedCopy.LimitException) return "Le fichier téléchargé est plus gros que prévu";
        if (e instanceof HashedCopy.CancelledException || e instanceof InterruptedIOException) return "Téléchargement interrompu";
        if (e instanceof IOException) {
            String message = e.getMessage();
            if (message != null && (message.contains("ENOSPC") || message.toLowerCase(Locale.ROOT).contains("no space left"))) {
                return "Espace de stockage insuffisant";
            }
            return "Erreur pendant le téléchargement de la mise à jour";
        }
        return "Erreur inattendue pendant la mise à jour";
    }
}

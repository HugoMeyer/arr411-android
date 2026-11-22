package dev.c411.qbittorrent;

import java.util.Locale;

/**
 * The state of the update as the page sees it: an immutable snapshot, and the transitions that make the next one. The page gets {@link #toJson()}, in the
 * shape of the contract shared with the desktop app.
 */
final class UpdateState {
    enum Status {
        IDLE,
        CHECKING,
        UPTODATE,
        DOWNLOADING,
        READY,
        INSTALLING,
        ERROR;

        String wire() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    static final String CANCELLED = "Installation annulée";

    /** False when the installed version is not a version: nothing can be compared with it. */
    final boolean supported;
    final String current;
    /** The latest release known; null until a check succeeded. */
    final UpdateRelease release;
    final Status status;
    /** 0 to 1 while a download goes on, else NaN. */
    final double progress;
    /** Epoch milliseconds of the last check that succeeded, or 0. */
    final long checkedAt;
    /** A French sentence; null when there is no error. */
    final String error;
    final String mode;
    /** A verified file of the latest release is in the cache. */
    final boolean apkReady;

    private UpdateState(boolean supported, String current, UpdateRelease release, Status status, double progress, long checkedAt, String error, String mode,
                        boolean apkReady) {
        this.supported = supported;
        this.current = current;
        this.release = release;
        this.status = status;
        this.progress = progress;
        this.checkedAt = checkedAt;
        this.error = error;
        this.mode = mode;
        this.apkReady = apkReady;
    }

    /** The state of an app that has not checked anything yet. */
    static UpdateState initial(String current, String mode) {
        String shown = current == null ? "" : current;
        return new UpdateState(UpdateVersion.parts(shown) != null, shown, null, Status.IDLE, Double.NaN, 0, null, mode, false);
    }

    /** A release newer than the installed version exists. */
    boolean available() {
        return release != null && UpdateVersion.isNewer(release.version, current);
    }

    /** The page may offer to install: the release is newer and its file can be verified. */
    boolean canInstall() {
        return supported && available() && release.installable();
    }

    /** A check, a download or an installation is under way. */
    boolean busy() {
        return status == Status.CHECKING || status == Status.DOWNLOADING || status == Status.INSTALLING;
    }

    UpdateState checking() {
        return new UpdateState(supported, current, release, Status.CHECKING, Double.NaN, checkedAt, null, mode, apkReady);
    }

    /** A check succeeded: {@code apkReady} is whether a verified file of that very release is in the cache. */
    UpdateState released(UpdateRelease latest, long when, boolean apkReady) {
        boolean newer = latest != null && UpdateVersion.isNewer(latest.version, current);
        Status next = !newer ? Status.UPTODATE : apkReady ? Status.READY : Status.IDLE;
        return new UpdateState(supported, current, latest, next, Double.NaN, when, null, mode, newer && apkReady);
    }

    UpdateState downloading(double fraction) {
        double clamped = Double.isNaN(fraction) ? 0 : Math.max(0, Math.min(1, fraction));
        return new UpdateState(supported, current, release, Status.DOWNLOADING, clamped, checkedAt, null, mode, false);
    }

    UpdateState ready() {
        return new UpdateState(supported, current, release, Status.READY, Double.NaN, checkedAt, null, mode, true);
    }

    UpdateState installing() {
        return new UpdateState(supported, current, release, Status.INSTALLING, Double.NaN, checkedAt, null, mode, apkReady);
    }

    /** Something failed: the release stays known, so the page can still offer to install or to open the release page. */
    UpdateState failed(String message) {
        return new UpdateState(supported, current, release, Status.ERROR, Double.NaN, checkedAt, message, mode, apkReady);
    }

    /** The user closed the dialog of the system: the file is still there to install. */
    UpdateState aborted() {
        return new UpdateState(supported, current, release, Status.READY, Double.NaN, checkedAt, CANCELLED, mode, true);
    }

    UpdateState withMode(String newMode) {
        return new UpdateState(supported, current, release, status, progress, checkedAt, error, newMode, apkReady);
    }

    /** Same state, with a file that is no longer in the cache. */
    UpdateState withoutFile() {
        Status next = status == Status.READY ? Status.IDLE : status;
        return new UpdateState(supported, current, release, next, progress, checkedAt, error, mode, false);
    }

    /** The object of the contract, keys in this order. */
    String toJson() {
        StringBuilder sb = new StringBuilder(512);
        sb.append("{\"supported\":").append(supported);
        sb.append(",\"current\":").append(UpdateJson.quote(current));
        sb.append(",\"latest\":").append(release == null ? "null" : UpdateJson.quote(release.version));
        sb.append(",\"available\":").append(available());
        sb.append(",\"status\":\"").append(status.wire()).append('"');
        sb.append(",\"progress\":").append(Double.isNaN(progress) ? "null" : String.format(Locale.ROOT, "%.3f", progress));
        sb.append(",\"notes\":").append(UpdateJson.quote(release == null ? "" : release.notes));
        sb.append(",\"url\":").append(UpdateJson.quote(release == null ? UpdateHosts.RELEASES_PAGE : release.pageUrl));
        sb.append(",\"checkedAt\":").append(checkedAt > 0 ? Long.toString(checkedAt) : "null");
        sb.append(",\"canInstall\":").append(canInstall());
        sb.append(",\"error\":").append(UpdateJson.quoteOrNull(error));
        sb.append(",\"mode\":").append(UpdateJson.quote(mode));
        return sb.append('}').toString();
    }
}

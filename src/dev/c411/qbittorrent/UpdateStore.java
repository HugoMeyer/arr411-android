package dev.c411.qbittorrent;

import android.content.Context;
import android.content.SharedPreferences;
import java.security.SecureRandom;

/**
 * What the updater remembers between two runs of the app, in preferences of its own: the last answer of GitHub with its entity tag, the file that was
 * downloaded and verified, the version that is being installed and the notifications already shown. What describes the update that is offered is dropped
 * when the installed version changes. Nothing here reads the disk before the first call, which is made off the main thread.
 */
final class UpdateStore {
    private static final String FILE = "update";
    private static final String FOR = "for";
    private static final String ETAG = "etag";
    private static final String JSON = "json";
    private static final String CHECKED_AT = "checkedAt";
    private static final String READY_VERSION = "readyVersion";
    private static final String READY_SHA = "readySha";
    private static final String READY_SIZE = "readySize";
    private static final String BLOCKED = "blocked";
    private static final String TRIED = "tried";
    private static final String INSTALLING = "installing";
    private static final String SESSION = "session";
    private static final String NOTIFIED = "notified.";
    private static final String TAP_KEY = "tapKey";

    private final SharedPreferences prefs;

    UpdateStore(Context context) {
        prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    /**
     * Drops what belongs to another version of the app. The marker of the installation that is under way stays: the new version needs it to announce
     * that it was installed.
     */
    void bind(String current) {
        if (current.equals(prefs.getString(FOR, null))) return;
        prefs.edit().remove(ETAG).remove(JSON).remove(CHECKED_AT).remove(READY_VERSION).remove(READY_SHA).remove(READY_SIZE).remove(BLOCKED)
                .remove(TRIED).remove(SESSION).putString(FOR, current).apply();
    }

    // ---------- The last answer of GitHub ----------

    String etag() {
        return prefs.getString(ETAG, null);
    }

    /** The text of the last release answer; null when there is none or it was too big to keep. */
    String json() {
        return prefs.getString(JSON, null);
    }

    /** Epoch milliseconds of the last check that succeeded, or 0. */
    long checkedAt() {
        return prefs.getLong(CHECKED_AT, 0);
    }

    /** The answer, its entity tag and the time of the check go together; without the text, the tag would be of no use. */
    void saveCheck(String etag, String json, long checkedAt) {
        SharedPreferences.Editor edit = prefs.edit().putLong(CHECKED_AT, checkedAt);
        if (json == null) {
            edit.remove(JSON).remove(ETAG);
        } else {
            edit.putString(JSON, json);
            if (etag == null) edit.remove(ETAG);
            else edit.putString(ETAG, etag);
        }
        edit.apply();
    }

    /** GitHub says that nothing changed: only the time of the check does. */
    void touchChecked(long checkedAt) {
        prefs.edit().putLong(CHECKED_AT, checkedAt).apply();
    }

    // ---------- The file that is ready ----------

    String readyVersion() {
        return prefs.getString(READY_VERSION, null);
    }

    String readySha() {
        return prefs.getString(READY_SHA, null);
    }

    long readySize() {
        return prefs.getLong(READY_SIZE, -1);
    }

    void setReady(String version, String sha256, long size) {
        prefs.edit().putString(READY_VERSION, version).putString(READY_SHA, sha256).putLong(READY_SIZE, size).apply();
    }

    void clearReady() {
        prefs.edit().remove(READY_VERSION).remove(READY_SHA).remove(READY_SIZE).apply();
    }

    // ---------- What went wrong with a version ----------

    /** The version whose file did not pass the verification: it is not downloaded again by itself. */
    String blocked() {
        return prefs.getString(BLOCKED, null);
    }

    void setBlocked(String version) {
        prefs.edit().putString(BLOCKED, version).apply();
    }

    void clearBlocked() {
        prefs.edit().remove(BLOCKED).apply();
    }

    /** The version that the app tried to install by itself: it does not try twice. */
    String tried() {
        return prefs.getString(TRIED, null);
    }

    void setTried(String version) {
        prefs.edit().putString(TRIED, version).apply();
    }

    // ---------- The installation under way ----------

    /** The version that is being installed; null when no installation of the app is under way. */
    String installing() {
        return prefs.getString(INSTALLING, null);
    }

    /** The session of the installer that goes with {@link #installing()}, or -1. */
    int session() {
        return prefs.getInt(SESSION, -1);
    }

    /** Written at once: the process may be ended by the installation itself, and the new version has to find the marker. */
    void setInstalling(String version, int session) {
        prefs.edit().putString(INSTALLING, version).putInt(SESSION, session).commit();
    }

    void clearInstalling() {
        prefs.edit().remove(INSTALLING).remove(SESSION).commit();
    }

    // ---------- Notifications ----------

    /** The key of the last notification of this kind that was posted, or null. */
    String notified(String kind) {
        return prefs.getString(NOTIFIED + kind, null);
    }

    void setNotified(String kind, String key) {
        prefs.edit().putString(NOTIFIED + kind, key).apply();
    }

    /** The secret that a tap on a notification carries: the activity can be opened by any app, only the notifications of this installation know the key. */
    synchronized String tapKey() {
        String key = prefs.getString(TAP_KEY, null);
        if (key == null || key.length() != 32) {
            byte[] random = new byte[16];
            new SecureRandom().nextBytes(random);
            key = UpdateIntegrity.hex(random);
            prefs.edit().putString(TAP_KEY, key).commit();
        }
        return key;
    }
}

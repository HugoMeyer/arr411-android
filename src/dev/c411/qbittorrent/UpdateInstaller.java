package dev.c411.qbittorrent;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.IntentSender;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.os.Build;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.List;
import java.util.function.IntConsumer;

/** The part of the update that talks to the installer of Android: it examines the downloaded file and hands it over in a session. */
final class UpdateInstaller {
    private static final String SESSION_FILE = "Arr411.apk";

    private UpdateInstaller() {}

    /**
     * Null when the file is an update of this app (same package, a higher version code), otherwise why it is not. The signature is not compared here:
     * the installer refuses a file that is not signed with the key of the installed app.
     */
    @SuppressWarnings("deprecation") // getPackageArchiveInfo(String, int): the replacement needs API 33
    static String archiveProblem(Context context, File apk) {
        try {
            PackageManager packages = context.getPackageManager();
            PackageInfo own = packages.getPackageInfo(context.getPackageName(), 0);
            PackageInfo archive = packages.getPackageArchiveInfo(apk.getAbsolutePath(), 0);
            if (archive == null) return UpdateFlow.archiveProblem(own.packageName, own.getLongVersionCode(), null, 0, false);
            return UpdateFlow.archiveProblem(own.packageName, own.getLongVersionCode(), archive.packageName, archive.getLongVersionCode(), true);
        } catch (PackageManager.NameNotFoundException | RuntimeException e) {
            Diag.problem("update-archive", "update: the downloaded file could not be examined [" + e.getClass().getSimpleName() + "]", null);
            return "Impossible d’examiner le fichier téléchargé";
        }
    }

    /**
     * Writes the file into a new session of the installer and commits it; the answer of the installer comes to {@link InstallStatusReceiver}. The file is
     * hashed again while it is copied, and the session is abandoned when it is not the file that was verified. {@code beforeCommit} gets the id of the
     * session just before the commit, to note it down: the installation may end the process as soon as it succeeds.
     */
    static int start(Context context, File apk, long size, String expectedSha256, IntConsumer beforeCommit) throws IOException {
        PackageInstaller installer = context.getPackageManager().getPackageInstaller();
        abandonStale(installer);
        PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(context.getPackageName());
        params.setSize(size);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED);
        }
        int id = installer.createSession(params);
        PackageInstaller.Session session = null;
        boolean committed = false;
        try {
            session = installer.openSession(id);
            write(session, apk, size, expectedSha256);
            beforeCommit.accept(id);
            session.commit(statusSender(context, id));
            committed = true;
            return id;
        } finally {
            if (!committed) abandon(installer, id);
            if (session != null) {
                try {
                    session.close();
                } catch (RuntimeException ignored) {
                }
            }
        }
    }

    private static void write(PackageInstaller.Session session, File apk, long size, String expectedSha256) throws IOException {
        try (FileInputStream in = new FileInputStream(apk); OutputStream out = session.openWrite(SESSION_FILE, 0, size)) {
            HashedCopy.Result copied;
            try {
                copied = HashedCopy.copy(in, out, size, null);
            } catch (HashedCopy.LimitException e) {
                throw new UpdateFailure("Taille du fichier incorrecte", true);
            }
            out.flush();
            session.fsync(out);
            String problem = UpdateIntegrity.verify(size, copied.bytes, copied.sha256, expectedSha256);
            if (problem != null) throw new UpdateFailure(problem, true);
        }
    }

    private static IntentSender statusSender(Context context, int id) {
        Intent intent = new Intent(context, InstallStatusReceiver.class).setAction(Updater.ACTION_INSTALL_STATUS);
        // The installer adds the status to this intent, so it has to be mutable; before Android 12 every one is.
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ? PendingIntent.FLAG_MUTABLE : 0);
        return PendingIntent.getBroadcast(context, id, intent, flags).getIntentSender();
    }

    /** A session left by an earlier attempt (waiting for a confirmation that never came) would keep its staged copy of the file. */
    private static void abandonStale(PackageInstaller installer) {
        try {
            List<PackageInstaller.SessionInfo> sessions = installer.getMySessions();
            for (PackageInstaller.SessionInfo info : sessions) abandon(installer, info.getSessionId());
        } catch (RuntimeException e) {
            Diag.problem("update-sessions", "update: the sessions of the installer could not be listed [" + e.getClass().getSimpleName() + "]", null);
        }
    }

    private static void abandon(PackageInstaller installer, int id) {
        try {
            installer.abandonSession(id);
        } catch (RuntimeException e) {
            Diag.problem("update-abandon", "update: a session of the installer could not be abandoned [" + e.getClass().getSimpleName() + "]", null);
        }
    }
}

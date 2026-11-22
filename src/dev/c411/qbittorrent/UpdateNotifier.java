package dev.c411.qbittorrent;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;

/**
 * The notifications of the update. They are posted at most once for each version and kind (the caller decides, see {@link UpdateFlow#shouldNotify}); a
 * tap on one that asks for something comes back to the app with the secret key of the installation, which the activity checks before it acts.
 */
final class UpdateNotifier {
    private static final String CHANNEL_INFO = "update_available";
    private static final String CHANNEL_ACTION = "update_ready";

    enum Kind {
        /** A newer version exists. */
        AVAILABLE("available", 411001, CHANNEL_INFO),
        /** The update is downloaded and verified, and waits for the user. */
        READY("ready", 411002, CHANNEL_ACTION),
        /** The app is not allowed to install apps yet. */
        PERMISSION("permission", 411003, CHANNEL_ACTION),
        /** Android wants the user to confirm the installation. */
        CONFIRM("confirm", 411004, CHANNEL_ACTION),
        /** The update was installed. */
        INSTALLED("installed", 411005, CHANNEL_INFO);

        final String key;
        final int id;
        final String channel;

        Kind(String key, int id, String channel) {
            this.key = key;
            this.id = id;
            this.channel = channel;
        }

        /** True when a tap asks the app to go on with the installation. */
        boolean actionable() {
            return this == READY || this == PERMISSION || this == CONFIRM;
        }
    }

    private UpdateNotifier() {}

    /** Posts the notification; false when the user turned the notifications off or the system refused it. */
    static boolean post(Context context, Kind kind, String version, String tapKey) {
        try {
            NotificationManager manager = context.getSystemService(NotificationManager.class);
            if (manager == null || !manager.areNotificationsEnabled()) return false;
            createChannels(manager);
            String text = text(kind);
            Notification notification = new Notification.Builder(context, kind.channel)
                    .setSmallIcon(android.R.drawable.stat_sys_download_done)
                    .setContentTitle(title(kind, version))
                    .setContentText(text)
                    .setStyle(new Notification.BigTextStyle().bigText(text))
                    .setContentIntent(contentIntent(context, kind, tapKey))
                    .setAutoCancel(true)
                    .build();
            manager.notify(kind.id, notification);
            return true;
        } catch (RuntimeException e) {
            // Refused by the system (permission, too large…): a notification is never worth the app.
            Diag.problem("update-notify", "update: a notification was refused [" + e.getClass().getSimpleName() + "]", null);
            return false;
        }
    }

    /** Takes down one kind of notification. */
    static void cancel(Context context, Kind kind) {
        try {
            NotificationManager manager = context.getSystemService(NotificationManager.class);
            if (manager != null) manager.cancel(kind.id);
        } catch (RuntimeException e) {
            Diag.problem("update-notify", "update: a notification could not be taken down [" + e.getClass().getSimpleName() + "]", null);
        }
    }

    /** Takes down the notifications that ask for something: they are out of date once the installation starts, and survive the update itself. */
    static void cancelPending(Context context) {
        try {
            NotificationManager manager = context.getSystemService(NotificationManager.class);
            if (manager == null) return;
            for (Kind kind : Kind.values()) {
                if (kind != Kind.INSTALLED) manager.cancel(kind.id);
            }
        } catch (RuntimeException e) {
            Diag.problem("update-notify", "update: notifications could not be taken down [" + e.getClass().getSimpleName() + "]", null);
        }
    }

    private static void createChannels(NotificationManager manager) {
        manager.createNotificationChannel(new NotificationChannel(CHANNEL_INFO, "Mises à jour", NotificationManager.IMPORTANCE_LOW));
        manager.createNotificationChannel(new NotificationChannel(CHANNEL_ACTION, "Mises à jour prêtes", NotificationManager.IMPORTANCE_DEFAULT));
    }

    private static PendingIntent contentIntent(Context context, Kind kind, String tapKey) {
        Intent open = new Intent(context, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        if (kind.actionable()) open.setAction(Updater.ACTION_INSTALL_UPDATE).putExtra(Updater.EXTRA_KEY, tapKey);
        else open.putExtra("tab", "settings");
        return PendingIntent.getActivity(context, kind.id, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    static String title(Kind kind, String version) {
        switch (kind) {
            case AVAILABLE:
                return "Arr411 " + version + " disponible";
            case READY:
                return "Mise à jour " + version + " prête";
            case PERMISSION:
                return "Autorisez Arr411 à installer ses mises à jour";
            case CONFIRM:
                return "Confirmez la mise à jour " + version;
            default:
                return "Arr411 a été mis à jour en " + version;
        }
    }

    static String text(Kind kind) {
        switch (kind) {
            case AVAILABLE:
                return "Ouvrez les réglages pour l’installer.";
            case READY:
                return "Touchez pour l’installer.";
            case PERMISSION:
                return "Touchez pour ouvrir le réglage Android, puis revenez dans l’app.";
            case CONFIRM:
                return "Touchez pour continuer l’installation.";
            default:
                return "La mise à jour est installée.";
        }
    }
}

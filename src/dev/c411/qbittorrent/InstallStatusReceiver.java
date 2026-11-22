package dev.c411.qbittorrent;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** The answer of the installer about the update: the session ended, or Android asks the user to confirm it. Not exported: only the system sends it. */
public final class InstallStatusReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        PendingResult pending = null;
        try {
            if (intent == null || !Updater.ACTION_INSTALL_STATUS.equals(intent.getAction())) return;
            pending = goAsync();
            PendingResult result = pending;
            Updater.get(context).onInstallStatus(intent, Updater.once(() -> {
                if (result != null) result.finish();
            }));
        } catch (Throwable e) {
            Diag.problem("update-status", "update: the answer of the installer could not be handled [" + e.getClass().getSimpleName() + "]", null);
            if (pending != null) {
                try {
                    pending.finish();
                } catch (RuntimeException ignored) {
                }
            }
        }
    }
}

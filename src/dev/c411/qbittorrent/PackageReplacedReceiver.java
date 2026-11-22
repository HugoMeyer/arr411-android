package dev.c411.qbittorrent;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** The app has just been replaced by a new version: it tells the user that the update is installed, and sets its periodic check up again. */
public final class PackageReplacedReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        PendingResult pending = null;
        try {
            if (intent == null || !Intent.ACTION_MY_PACKAGE_REPLACED.equals(intent.getAction())) return;
            pending = goAsync();
            PendingResult result = pending;
            Updater.get(context).onPackageReplaced(Updater.once(() -> {
                if (result != null) result.finish();
            }));
        } catch (Throwable e) {
            Diag.problem("update-replaced", "update: the new version could not be announced [" + e.getClass().getSimpleName() + "]", null);
            if (pending != null) {
                try {
                    pending.finish();
                } catch (RuntimeException ignored) {
                }
            }
        }
    }
}

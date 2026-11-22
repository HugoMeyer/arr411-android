package dev.c411.qbittorrent;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.service.quicksettings.TileService;

/**
 * Where the app shortcuts and a long press on the Quick Settings tile lead: it opens a tab of {@link MainActivity} and is gone at once, without a screen.
 * Android starts a static shortcut in a new task and clears that task. A shortcut that pointed at MainActivity would restart the running app and
 * reload every page in it. This activity has no task affinity, so only its own task is cleared; it then hands the tab to the running app, as the notifications do.
 */
public final class ShortcutActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            Intent in = getIntent();
            String tab = in == null ? null : in.getStringExtra("tab");
            Intent open = new Intent(this, MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            if (MainActivity.isTab(tab)) {
                open.putExtra("tab", tab);
            } else if (in != null && TileService.ACTION_QS_TILE_PREFERENCES.equals(in.getAction())) {
                // A long press on the tile: the speed limits are in the qBittorrent tab.
                open.putExtra("tab", "qbit");
            }
            startActivity(open);
        } catch (Throwable e) {
            Diag.problem("shortcut", "a shortcut could not open the app", e);
        } finally {
            // Required with Theme.NoDisplay: the activity must be finished before it is resumed.
            finish();
        }
    }
}

package dev.c411.qbittorrent;

import android.app.Application;

/** Starts the diagnostic before anything else, so that the very first crash already has somewhere to be written. */
public class App extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        Diag.init(this);
    }

    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        Diag.trim(level);
    }

    @Override
    public void onLowMemory() {
        super.onLowMemory();
        Diag.lowMemory();
    }
}

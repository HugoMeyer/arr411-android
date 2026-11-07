package dev.c411.qbittorrent;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Bottom navigation: C411, Radarr, Sonarr, Lidarr, qBittorrent (with a badge for active downloads). */
final class NavBar extends LinearLayout {
    interface Listener {
        void onTab(String tab, boolean reselected);

        void onLongTab(String tab);
    }

    static final String[] TABS = {"c411", "radarr", "sonarr", "lidarr", "qbit"};
    private static final String[] LABELS = {"C411", "Radarr", "Sonarr", "Lidarr", "qBittorrent"};
    private static final int[] ICONS = {R.drawable.nav_c411, R.drawable.nav_radarr, R.drawable.nav_sonarr, R.drawable.nav_lidarr, R.drawable.nav_qbit};
    static final int ACCENT = 0xFF1FD394;
    static final int MUTED = 0xFF8FA39B;
    static final int BG = 0xFF0A1411;

    private final ImageView[] icons = new ImageView[TABS.length];
    private final TextView[] labels = new TextView[TABS.length];
    private final View[] pills = new View[TABS.length];
    private final TextView badge;
    private String selected = "";

    NavBar(Context c, Listener listener) {
        super(c);
        setOrientation(HORIZONTAL);
        setBackgroundColor(BG);
        TextView b = null;
        for (int i = 0; i < TABS.length; i++) {
            final String tab = TABS[i];
            FrameLayout item = new FrameLayout(c);
            item.setBackground(new RippleDrawable(ColorStateList.valueOf(0x331FD394), null, null));
            item.setContentDescription(LABELS[i]);
            item.setOnClickListener(v -> listener.onTab(tab, tab.equals(selected)));
            item.setOnLongClickListener(v -> {
                listener.onLongTab(tab);
                return true;
            });

            LinearLayout col = new LinearLayout(c);
            col.setOrientation(VERTICAL);
            col.setGravity(Gravity.CENTER);
            item.addView(col, new FrameLayout.LayoutParams(-1, -1));

            FrameLayout iconBox = new FrameLayout(c);
            col.addView(iconBox, new LinearLayout.LayoutParams(dp(54), dp(30)));
            View pill = new View(c);
            pill.setBackground(round(0x2B1FD394, dp(15)));
            iconBox.addView(pill, new FrameLayout.LayoutParams(-1, -1));
            ImageView icon = new ImageView(c);
            icon.setImageResource(ICONS[i]);
            iconBox.addView(icon, new FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER));

            TextView label = new TextView(c);
            label.setText(LABELS[i]);
            label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
            label.setSingleLine(true);
            label.setEllipsize(TextUtils.TruncateAt.END);
            label.setPadding(dp(2), 0, dp(2), 0);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.topMargin = dp(3);
            col.addView(label, lp);

            if ("qbit".equals(tab)) {
                b = new TextView(c);
                b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
                b.setTypeface(Typeface.DEFAULT_BOLD);
                b.setTextColor(0xFF03140D);
                b.setIncludeFontPadding(false);
                b.setGravity(Gravity.CENTER);
                b.setMinWidth(dp(18));
                b.setPadding(dp(4), 0, dp(4), 0);
                GradientDrawable bg = round(ACCENT, dp(9));
                bg.setStroke(dp(2), BG);
                b.setBackground(bg);
                b.setVisibility(GONE);
                FrameLayout.LayoutParams bp = new FrameLayout.LayoutParams(-2, dp(18), Gravity.TOP | Gravity.START);
                bp.leftMargin = dp(32);
                iconBox.addView(b, bp);
            }
            icons[i] = icon;
            labels[i] = label;
            pills[i] = pill;
            addView(item, new LinearLayout.LayoutParams(0, -1, 1));
        }
        badge = b;
        select("");
    }

    /** Highlights a tab; any other value (e.g. "settings") leaves every item unselected. */
    void select(String tab) {
        selected = tab == null ? "" : tab;
        for (int i = 0; i < TABS.length; i++) {
            boolean on = TABS[i].equals(selected);
            icons[i].setImageTintList(ColorStateList.valueOf(on ? ACCENT : MUTED));
            labels[i].setTextColor(on ? ACCENT : MUTED);
            labels[i].setTypeface(on ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
            pills[i].setVisibility(on ? VISIBLE : INVISIBLE);
        }
    }

    void setBadge(int n) {
        if (badge == null) return;
        badge.setVisibility(n > 0 ? VISIBLE : GONE);
        badge.setText(n > 99 ? "99+" : String.valueOf(n));
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    static GradientDrawable round(int color, float radius) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radius);
        return g;
    }
}

package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.view.View;

/**
 * my-tv fork: invisible view on Home. At most every 12 hours while Home is on screen, checks the fork's
 * latest release; when it is newer than this build, downloads it and Android asks on screen whether to
 * install. A build that was offered and not installed is offered again after 24 hours.
 */
public class UpdateWatcher extends View {
    private static final long CHECK_EVERY_MS = 12 * 60 * 60 * 1000L;
    private static final long REOFFER_AFTER_MS = 24 * 60 * 60 * 1000L;
    private final Handler main = new Handler(Looper.getMainLooper());
    private boolean running = false;

    public UpdateWatcher(Context c) { super(c); }
    public UpdateWatcher(Context c, AttributeSet a) { super(c, a); }

    @Override protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        if (visibility == View.VISIBLE) maybeCheck();
    }

    private void maybeCheck() {
        final Context c = getContext().getApplicationContext();
        if (running || !Updater.configured(c) || !Updater.canInstall(c)) return;
        final SharedPreferences p = c.getSharedPreferences(Updater.PREFS, Context.MODE_PRIVATE);
        final long now = System.currentTimeMillis();
        if (now - p.getLong("last_check", 0) < CHECK_EVERY_MS) return;
        p.edit().putLong("last_check", now).apply();
        running = true;
        new Thread(() -> {
            try {
                Updater.Release r = Updater.latest(c);
                if (r != null && r.code > Updater.installedCode(c)) {
                    boolean offered = p.getLong("offered_code", 0) == r.code
                            && now - p.getLong("offered_at", 0) < REOFFER_AFTER_MS;
                    if (!offered) {
                        p.edit().putLong("offered_code", r.code).putLong("offered_at", now).apply();
                        Updater.install(c, r);
                    }
                }
            } catch (Exception ignored) {
                // Offline or GitHub unreachable: try again at the next check.
            } finally {
                main.post(() -> running = false);
            }
        }, "update-watch").start();
    }
}

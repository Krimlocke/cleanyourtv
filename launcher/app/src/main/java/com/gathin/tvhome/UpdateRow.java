package com.gathin.tvhome;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.AttributeSet;
import android.widget.TextView;

/** my-tv fork: "Check for launcher update" row in Launcher settings. Handles its own click. */
public class UpdateRow extends TextView {
    private final Handler main = new Handler(Looper.getMainLooper());
    private boolean busy = false;

    public UpdateRow(Context c) { super(c); init(); }
    public UpdateRow(Context c, AttributeSet a) { super(c, a); init(); }
    public UpdateRow(Context c, AttributeSet a, int s) { super(c, a, s); init(); }

    private void init() {
        setOnClickListener(v -> run());
    }

    private void run() {
        if (busy) return;
        final Context c = getContext();
        if (!Updater.configured(c)) { setText("Updates: set update_repo in mytv_update.xml"); return; }
        busy = true;
        setText("Checking for updates…");
        new Thread(() -> {
            Updater.Release r = null;
            String error = null;
            try { r = Updater.latest(c); } catch (Exception e) { error = e.getMessage(); }
            final Updater.Release rel = r;
            final String err = error;
            main.post(() -> after(c, rel, err));
        }, "update-check").start();
    }

    private void after(Context c, Updater.Release r, String err) {
        long installed = Updater.installedCode(c);
        if (err != null) { done("Update check failed (" + err + ")"); return; }
        if (r == null) { done("No launcher release found"); return; }
        if (r.code <= installed) { done("Launcher is up to date (build " + installed + ")"); return; }
        if (!Updater.canInstall(c)) {
            done("Allow “Install unknown apps” for this launcher, then try again");
            try {
                c.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + c.getPackageName())).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (ActivityNotFoundException ignored) { }
            return;
        }
        setText("Downloading build " + r.code + "…");
        new Thread(() -> {
            String e2 = null;
            try { Updater.install(c, r); } catch (Exception e) { e2 = e.getMessage(); }
            final String ie = e2;
            main.post(() -> done(ie == null ? "Confirm the update on screen" : "Download failed (" + ie + ")"));
        }, "update-install").start();
    }

    private void done(String text) {
        busy = false;
        setText(text);
    }
}

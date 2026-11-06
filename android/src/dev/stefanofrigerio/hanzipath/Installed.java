package dev.stefanofrigerio.hanzipath;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;

/**
 * PackageInstaller's answer about an app update. When Android wants the person to confirm
 * (the first update after an install by adb or a file manager), its dialog is opened; a
 * failure is kept as the updater's status for Settings to show.
 */
public class Installed extends BroadcastReceiver {
    @Override
    @SuppressWarnings("deprecation")   // the typed getParcelableExtra is Android 13's
    public void onReceive(Context ctx, Intent i) {
        int st = i.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        if (st == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            Intent confirm = i.getParcelableExtra(Intent.EXTRA_INTENT);
            if (confirm != null) ctx.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return;
        }
        String msg = st == PackageInstaller.STATUS_SUCCESS ? "Updated."
                : "App update failed: " + i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
        ctx.getSharedPreferences("hanzi", Context.MODE_PRIVATE).edit().putString("status", msg).apply();
    }
}

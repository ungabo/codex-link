package com.codexlink.android;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class PackageReplacedReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !Intent.ACTION_MY_PACKAGE_REPLACED.equals(intent.getAction())) {
            return;
        }
        NotificationHelper.showInstalledNotification(context);
        context.getSharedPreferences("codex_link", Context.MODE_PRIVATE)
                .edit()
                .putInt("last_install_notification_version", BuildConfig.VERSION_CODE)
                .apply();
        try {
            CodexQueueService.start(context);
        } catch (Exception ignored) {
        }
    }
}

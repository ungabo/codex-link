package com.codexlink.android;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;

final class NotificationHelper {
    private static final String CHANNEL_ID = "codex_link_status";
    private static final String PREFS = "codex_link";
    private static final String PREF_FOREGROUND_ACTIVE = "foreground_active";
    private static final String PREF_FOREGROUND_THREAD_ID = "foreground_thread_id";
    private static final int INSTALL_NOTIFICATION_ID = 18765;
    private static final int COMPLETION_NOTIFICATION_BASE_ID = 19765;

    private NotificationHelper() {
    }

    static void ensureChannel(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "Codex Link status",
                NotificationManager.IMPORTANCE_DEFAULT);
        channel.setDescription("Install and connection status for Codex Link.");
        manager.createNotificationChannel(channel);
    }

    static void showInstalledNotification(Context context) {
        if (Build.VERSION.SDK_INT >= 33
                && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        ensureChannel(context);
        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) {
            return;
        }

        Intent intent = new Intent(context, MainActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                context,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(context, CHANNEL_ID)
                : new Notification.Builder(context);
        Notification notification = builder
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle("Codex Link installed")
                .setContentText("Version " + BuildConfig.VERSION_NAME + " is ready.")
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .build();
        manager.notify(INSTALL_NOTIFICATION_ID, notification);
    }

    static Notification buildQueueNotification(Context context, String title, String text, boolean ongoing) {
        ensureChannel(context);
        Intent intent = new Intent(context, MainActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                context,
                1,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(context, CHANNEL_ID)
                : new Notification.Builder(context);
        return builder
                .setSmallIcon(android.R.drawable.stat_sys_upload)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(pendingIntent)
                .setOngoing(ongoing)
                .build();
    }

    static void showQueueNotification(Context context, int notificationId, String title, String text, boolean ongoing) {
        if (Build.VERSION.SDK_INT >= 33
                && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) {
            return;
        }
        manager.notify(notificationId, buildQueueNotification(context, title, text, ongoing));
    }

    static void showTurnCompletedNotification(Context context, String threadId, String threadTitle) {
        if (threadId == null || threadId.isEmpty() || isForegroundChat(context, threadId)) {
            return;
        }
        if (Build.VERSION.SDK_INT >= 33
                && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        ensureChannel(context);
        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) {
            return;
        }

        Intent intent = new Intent(context, MainActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        intent.putExtra(MainActivity.EXTRA_OPEN_THREAD_ID, threadId);
        intent.putExtra(MainActivity.EXTRA_OPEN_THREAD_TITLE, threadTitle == null ? "" : threadTitle);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                context,
                2 + Math.abs(threadId.hashCode() % 10000),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        String text = threadTitle == null || threadTitle.trim().isEmpty()
                ? "A Codex request finished."
                : threadTitle.trim();
        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(context, CHANNEL_ID)
                : new Notification.Builder(context);
        Notification notification = builder
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle("Codex request completed")
                .setContentText(text)
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .build();
        manager.notify(COMPLETION_NOTIFICATION_BASE_ID + Math.abs(threadId.hashCode() % 1000), notification);
    }

    private static boolean isForegroundChat(Context context, String threadId) {
        if (threadId == null || threadId.isEmpty()) {
            return false;
        }
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(PREF_FOREGROUND_ACTIVE, false)
                && threadId.equals(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(PREF_FOREGROUND_THREAD_ID, ""));
    }
}

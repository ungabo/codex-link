package com.codexlink.android;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import java.io.File;

public class BootCompletedReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            return;
        }
        File queueDir = new File(context.getFilesDir(), "queues");
        File[] files = queueDir.listFiles((file, name) -> name.endsWith(".json") && file.length() > 2);
        if (files == null || files.length == 0) {
            return;
        }
        try {
            CodexQueueService.start(context);
        } catch (Exception ignored) {
        }
    }
}

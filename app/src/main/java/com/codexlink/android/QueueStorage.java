package com.codexlink.android;

import android.util.Log;

import org.json.JSONArray;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

final class QueueStorage {
    static final Object LOCK = new Object();
    private static final String TAG = "QueueStorage";

    private QueueStorage() {
    }

    static JSONArray readArray(File file) {
        if (file == null || !file.exists()) {
            return new JSONArray();
        }
        try (InputStream input = new FileInputStream(file);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
            return new JSONArray(output.toString(StandardCharsets.UTF_8.name()));
        } catch (Exception error) {
            Log.w(TAG, "Could not read queue JSON " + file.getName(), error);
            return new JSONArray();
        }
    }

    static void writeArrayAtomic(File file, JSONArray array) throws IOException {
        if (file == null) {
            return;
        }
        File parent = file.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("Could not create " + parent);
        }
        File temp = new File(parent == null ? new File(".") : parent, file.getName() + ".tmp");
        try (FileOutputStream output = new FileOutputStream(temp, false)) {
            output.write((array == null ? new JSONArray() : array).toString().getBytes(StandardCharsets.UTF_8));
            output.getFD().sync();
        }
        if (file.exists() && !file.delete()) {
            throw new IOException("Could not replace " + file);
        }
        if (!temp.renameTo(file)) {
            throw new IOException("Could not move " + temp + " to " + file);
        }
    }
}

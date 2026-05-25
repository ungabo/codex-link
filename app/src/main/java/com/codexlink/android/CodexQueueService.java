package com.codexlink.android;

import android.app.Notification;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.SystemClock;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class CodexQueueService extends Service {
    static final String ACTION_PROCESS_QUEUE = "com.codexlink.android.PROCESS_QUEUE";
    static final String ACTION_QUEUE_CHANGED = "com.codexlink.android.QUEUE_CHANGED";
    static final String EXTRA_THREAD_ID = "thread_id";
    static final String EXTRA_STATUS = "status";
    static final String EXTRA_ERROR = "error";

    private static final String TAG = "CodexQueueService";
    private static final String PREFS = "codex_link";
    private static final String PREF_TOKEN = "token";
    private static final String PREF_CONNECTION_MODE = "connection_mode";
    private static final String PREF_LOCAL_ENDPOINT = "local_endpoint";
    private static final String PREF_LOCAL_TOKEN = "local_token";
    private static final String PREF_WEB_ENDPOINT = "web_endpoint";
    private static final String PREF_WEB_TOKEN = "web_token";
    private static final String MODE_WEB = "web";
    private static final String DEFAULT_WEB_ENDPOINT = "https://www.sitesindevelopment.com/codex-link/index.php/link";
    private static final String DEFAULT_WEB_TOKEN = BuildConfig.CODEX_LINK_DEFAULT_WEB_TOKEN;
    private static final int NOTIFICATION_ID = 18766;
    private static final int READ_TIMEOUT_MS = 300000;
    private static final int CONNECT_TIMEOUT_MS = 10000;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private volatile boolean workerRunning = false;
    private volatile boolean stopRequested = false;

    private static class QueueItem {
        final String id;
        final long createdAt;
        final JSONObject payload;

        QueueItem(String id, long createdAt, JSONObject payload) {
            this.id = id == null || id.isEmpty() ? "" : id;
            this.createdAt = createdAt;
            this.payload = payload;
        }
    }

    private static class QueueFile {
        final String threadId;
        final File file;
        final ArrayList<QueueItem> items;

        QueueFile(String threadId, File file, ArrayList<QueueItem> items) {
            this.threadId = threadId;
            this.file = file;
            this.items = items;
        }

        long oldestAt() {
            return items.isEmpty() ? Long.MAX_VALUE : items.get(0).createdAt;
        }
    }

    private static class EndpointConfig {
        final String endpoint;
        final String token;
        final boolean webMode;

        EndpointConfig(String endpoint, String token, boolean webMode) {
            this.endpoint = endpoint == null ? "" : endpoint;
            this.token = token == null ? "" : token;
            this.webMode = webMode;
        }
    }

    private static class HttpResult {
        final int status;
        final String body;

        HttpResult(int status, String body) {
            this.status = status;
            this.body = body == null ? "" : body;
        }

        boolean isOk() {
            return status >= 200 && status < 300;
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        NotificationHelper.ensureChannel(this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.i(TAG, "Queue service start requested.");
        startAsForeground("Checking queued Codex messages...");
        requestQueueWork();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        stopRequested = true;
        executor.shutdownNow();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void requestQueueWork() {
        if (workerRunning) {
            Log.i(TAG, "Queue worker already running.");
            return;
        }
        workerRunning = true;
        stopRequested = false;
        executor.execute(() -> {
            try {
                runQueueLoop();
            } finally {
                workerRunning = false;
            }
        });
    }

    private void runQueueLoop() {
        Log.i(TAG, "Queue worker loop started.");
        while (!stopRequested) {
            ArrayList<QueueFile> queues = allQueues();
            int queuedCount = totalQueuedCount(queues);
            Log.i(TAG, "Queue pass found " + queuedCount + " queued message(s) in " + queues.size() + " chat(s).");
            if (queuedCount == 0) {
                broadcastQueueChanged("", "Queue empty.", false);
                stopForegroundAndSelf();
                return;
            }

            updateNotification("Sending queued Codex messages", queuedCount + " queued");
            EndpointConfig config = endpointConfig();
            if (config.endpoint.isEmpty()) {
                broadcastQueueChanged("", "No Codex Link endpoint is configured.", true);
                sleepFor(30000);
                continue;
            }

            boolean madeProgress = false;
            boolean sawBusyThread = false;
            String lastStatus = "";
            for (QueueFile queue : queues) {
                if (stopRequested || queue.items.isEmpty()) {
                    continue;
                }

                QueueItem item = queue.items.get(0);
                try {
                    ThreadState state = loadThreadState(config, queue.threadId);
                    Log.i(TAG, "Queue thread " + queue.threadId + " active=" + state.active);
                    if (state.active) {
                        sawBusyThread = true;
                        lastStatus = "Waiting for " + queue.threadId + " to finish.";
                        continue;
                    }

                    String turnsEndpoint = threadTurnsEndpointFor(config.endpoint, queue.threadId);
                    Log.i(TAG, "Posting queued turn for " + queue.threadId + " to " + turnsEndpoint);
                    HttpResult result = postJson(config, turnsEndpoint, item.payload);
                    Log.i(TAG, "Queued turn result for " + queue.threadId + ": HTTP " + result.status);
                    if (result.status == 409) {
                        sawBusyThread = true;
                        lastStatus = "Codex is still processing for " + queue.threadId + ".";
                        continue;
                    }
                    if (!result.isOk()) {
                        lastStatus = httpErrorMessage("Queued send", result);
                        Log.w(TAG, lastStatus);
                        broadcastQueueChanged(queue.threadId, lastStatus, true);
                        continue;
                    }

                    removeQueuedItem(queue.threadId, item.id);
                    madeProgress = true;
                    lastStatus = "Sent queued message.";
                    broadcastQueueChanged(queue.threadId, lastStatus, false);
                    break;
                } catch (Exception error) {
                    lastStatus = error.getMessage() == null ? "Queued send failed." : error.getMessage();
                    Log.w(TAG, "Queue pass failed for " + queue.threadId, error);
                    broadcastQueueChanged(queue.threadId, lastStatus, true);
                }
            }

            if (madeProgress) {
                sleepFor(2500);
            } else if (sawBusyThread) {
                updateNotification("Codex Link queue waiting", lastStatus.isEmpty() ? "Waiting for active chats" : lastStatus);
                sleepFor(15000);
            } else {
                updateNotification("Codex Link queue paused", lastStatus.isEmpty() ? "Queued sends could not complete" : lastStatus);
                sleepFor(30000);
            }
        }
    }

    private ThreadState loadThreadState(EndpointConfig config, String threadId) throws IOException, JSONException {
        String threadEndpoint = threadEndpointFor(config.endpoint, threadId);
        HttpResult result = getJson(config, threadEndpoint);
        if (!result.isOk()) {
            throw new IOException(httpErrorMessage("Thread status", result));
        }
        JSONObject response = new JSONObject(result.body);
        JSONObject thread = response.optJSONObject("thread");
        if (thread == null) {
            thread = response;
        }
        boolean active = thread.optBoolean("active", false);
        boolean staleActive = thread.optBoolean("staleActive", false);
        return new ThreadState(active && !staleActive);
    }

    private static class ThreadState {
        final boolean active;

        ThreadState(boolean active) {
            this.active = active;
        }
    }

    private EndpointConfig endpointConfig() {
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        String mode = prefs.getString(PREF_CONNECTION_MODE, "");
        boolean webMode = MODE_WEB.equals(mode);
        String endpoint;
        String token;
        if (webMode) {
            endpoint = prefs.getString(PREF_WEB_ENDPOINT, DEFAULT_WEB_ENDPOINT);
            token = prefs.getString(PREF_WEB_TOKEN, DEFAULT_WEB_TOKEN);
            if (endpoint == null || endpoint.isEmpty()) {
                endpoint = DEFAULT_WEB_ENDPOINT;
            }
            if ((token == null || token.isEmpty()) && !DEFAULT_WEB_TOKEN.isEmpty()) {
                token = DEFAULT_WEB_TOKEN;
            }
        } else {
            endpoint = prefs.getString(PREF_LOCAL_ENDPOINT, prefs.getString("endpoint", ""));
            token = prefs.getString(PREF_LOCAL_TOKEN, prefs.getString(PREF_TOKEN, ""));
        }
        return new EndpointConfig(normalizeEndpoint(endpoint, webMode), token, webMode);
    }

    private HttpResult getJson(EndpointConfig config, String endpoint) throws IOException {
        HttpResult result = getJsonOnce(endpoint, config.token);
        if (shouldRetryWithIncludedWebToken(config, endpoint, result.status)) {
            result = getJsonOnce(endpoint, DEFAULT_WEB_TOKEN);
            if (result.isOk()) {
                rememberIncludedWebToken();
            }
        }
        return result;
    }

    private HttpResult getJsonOnce(String endpoint, String token) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(endpoint).openConnection();
        connection.setRequestMethod("GET");
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(CONNECT_TIMEOUT_MS);
        connection.setRequestProperty("Accept", "application/json");
        if (token != null && !token.isEmpty()) {
            connection.setRequestProperty("Authorization", "Bearer " + token);
        }
        int status = connection.getResponseCode();
        String response = readResponse(connection, status);
        connection.disconnect();
        return new HttpResult(status, response);
    }

    private HttpResult postJson(EndpointConfig config, String endpoint, JSONObject payload) throws IOException {
        HttpResult result = postJsonOnce(endpoint, config.token, payload);
        if (shouldRetryWithIncludedWebToken(config, endpoint, result.status)) {
            result = postJsonOnce(endpoint, DEFAULT_WEB_TOKEN, payload);
            if (result.isOk()) {
                rememberIncludedWebToken();
            }
        }
        return result;
    }

    private HttpResult postJsonOnce(String endpoint, String token, JSONObject payload) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(endpoint).openConnection();
        connection.setRequestMethod("POST");
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(READ_TIMEOUT_MS);
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        connection.setRequestProperty("Accept", "application/json");
        if (token != null && !token.isEmpty()) {
            connection.setRequestProperty("Authorization", "Bearer " + token);
        }

        byte[] body = payload.toString().getBytes(StandardCharsets.UTF_8);
        connection.setFixedLengthStreamingMode(body.length);
        try (OutputStream output = connection.getOutputStream()) {
            output.write(body);
        }

        int status = connection.getResponseCode();
        String response = readResponse(connection, status);
        connection.disconnect();
        return new HttpResult(status, response);
    }

    private boolean shouldRetryWithIncludedWebToken(EndpointConfig config, String endpoint, int status) {
        if (status != 401 || !config.webMode || DEFAULT_WEB_TOKEN.isEmpty() || DEFAULT_WEB_TOKEN.equals(config.token)) {
            return false;
        }
        try {
            URL url = new URL(endpoint);
            String host = url.getHost() == null ? "" : url.getHost().toLowerCase(Locale.US);
            String path = url.getPath() == null ? "" : url.getPath();
            return host.endsWith("sitesindevelopment.com") && path.startsWith("/codex-link");
        } catch (Exception ignored) {
            return false;
        }
    }

    private void rememberIncludedWebToken() {
        getSharedPreferences(PREFS, MODE_PRIVATE)
                .edit()
                .putString(PREF_WEB_TOKEN, DEFAULT_WEB_TOKEN)
                .putString(PREF_TOKEN, DEFAULT_WEB_TOKEN)
                .apply();
    }

    private ArrayList<QueueFile> allQueues() {
        ArrayList<QueueFile> queues = new ArrayList<>();
        File dir = new File(getFilesDir(), "queues");
        File[] files = dir.listFiles((file, name) -> name.endsWith(".json"));
        if (files == null) {
            return queues;
        }
        for (File file : files) {
            ArrayList<QueueItem> items = readQueue(file);
            if (items.isEmpty()) {
                continue;
            }
            String name = file.getName();
            String threadId = name.substring(0, name.length() - ".json".length());
            queues.add(new QueueFile(threadId, file, items));
        }
        Collections.sort(queues, (left, right) -> Long.compare(left.oldestAt(), right.oldestAt()));
        return queues;
    }

    private int totalQueuedCount(ArrayList<QueueFile> queues) {
        int count = 0;
        for (QueueFile queue : queues) {
            count += queue.items.size();
        }
        return count;
    }

    private ArrayList<QueueItem> readQueue(File file) {
        ArrayList<QueueItem> items = new ArrayList<>();
        synchronized (CodexQueueService.class) {
            if (!file.exists()) {
                return items;
            }
            try (InputStream input = new FileInputStream(file);
                 ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[4096];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    output.write(buffer, 0, read);
                }
                JSONArray array = new JSONArray(output.toString(StandardCharsets.UTF_8.name()));
                for (int index = 0; index < array.length(); index++) {
                    JSONObject object = array.optJSONObject(index);
                    if (object == null) {
                        continue;
                    }
                    JSONObject payload = object.optJSONObject("payload");
                    if (payload == null) {
                        continue;
                    }
                    items.add(new QueueItem(object.optString("id", ""), object.optLong("createdAt", 0L), payload));
                }
            } catch (Exception error) {
                Log.w(TAG, "Could not read queue " + file.getName(), error);
            }
        }
        return items;
    }

    private void removeQueuedItem(String threadId, String itemId) {
        File file = queueFileForThread(threadId);
        synchronized (CodexQueueService.class) {
            ArrayList<QueueItem> items = readQueue(file);
            JSONArray array = new JSONArray();
            boolean removed = false;
            for (QueueItem item : items) {
                if (!removed && !itemId.isEmpty() && itemId.equals(item.id)) {
                    removed = true;
                    continue;
                }
                array.put(queueItemJson(item));
            }
            try (FileOutputStream output = new FileOutputStream(file, false)) {
                output.write(array.toString().getBytes(StandardCharsets.UTF_8));
            } catch (Exception error) {
                Log.w(TAG, "Could not update queue " + threadId, error);
            }
        }
    }

    private JSONObject queueItemJson(QueueItem item) {
        try {
            return new JSONObject()
                    .put("id", item.id)
                    .put("createdAt", item.createdAt)
                    .put("payload", item.payload);
        } catch (JSONException error) {
            return new JSONObject();
        }
    }

    private File queueFileForThread(String threadId) {
        return new File(new File(getFilesDir(), "queues"), safeFileName(threadId) + ".json");
    }

    private String safeFileName(String value) {
        String safe = value == null ? "" : value.replaceAll("[^A-Za-z0-9._-]+", "_");
        if (safe.isEmpty()) {
            return "thread";
        }
        return safe.length() > 120 ? safe.substring(0, 120) : safe;
    }

    private String threadEndpointFor(String endpoint, String threadId) throws IOException {
        URL url = new URL(endpoint);
        String encodedThreadId = URLEncoder.encode(threadId, StandardCharsets.UTF_8.name());
        return new URL(url.getProtocol(), url.getHost(), url.getPort(), apiBasePathFor(url) + "threads/" + encodedThreadId + "?limit=1").toString();
    }

    private String threadTurnsEndpointFor(String endpoint, String threadId) throws IOException {
        URL url = new URL(endpoint);
        String encodedThreadId = URLEncoder.encode(threadId, StandardCharsets.UTF_8.name());
        return new URL(url.getProtocol(), url.getHost(), url.getPort(), apiBasePathFor(url) + "threads/" + encodedThreadId + "/turns").toString();
    }

    private String apiBasePathFor(URL url) {
        String path = url.getPath();
        if (path == null || path.isEmpty() || "/".equals(path)) {
            return "/";
        }
        int lastSlash = path.lastIndexOf('/');
        String lastSegment = lastSlash >= 0 ? path.substring(lastSlash + 1) : path;
        if ("link".equals(lastSegment) || "catalog".equals(lastSegment)) {
            return path.substring(0, lastSlash + 1);
        }
        if (path.endsWith("/")) {
            return path;
        }
        return path + "/";
    }

    private String normalizeEndpoint(String endpoint, boolean webMode) {
        if (endpoint == null) {
            return "";
        }
        String cleanEndpoint = endpoint.trim();
        int httpIndex = cleanEndpoint.indexOf("http://");
        int httpsIndex = cleanEndpoint.indexOf("https://");
        int start = -1;
        if (httpIndex >= 0 && httpsIndex >= 0) {
            start = Math.min(httpIndex, httpsIndex);
        } else if (httpIndex >= 0) {
            start = httpIndex;
        } else if (httpsIndex >= 0) {
            start = httpsIndex;
        }
        if (start > 0) {
            cleanEndpoint = cleanEndpoint.substring(start).trim();
        }
        if (!webMode || cleanEndpoint.isEmpty()) {
            return cleanEndpoint;
        }
        try {
            URL url = new URL(cleanEndpoint);
            String host = url.getHost() == null ? "" : url.getHost().toLowerCase(Locale.US);
            String path = url.getPath() == null ? "" : url.getPath();
            if (host.endsWith("sitesindevelopment.com") && path.startsWith("/codex-link")) {
                if (!path.startsWith("/codex-link/index.php")
                        || "/codex-link/index.php".equals(path)
                        || "/codex-link/index.php/".equals(path)) {
                    return new URL(url.getProtocol(), url.getHost(), url.getPort(), "/codex-link/index.php/link").toString();
                }
            }
        } catch (Exception ignored) {
        }
        return cleanEndpoint;
    }

    private String readResponse(HttpURLConnection connection, int status) throws IOException {
        InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
        if (stream == null) {
            return "";
        }
        try (InputStream input = stream;
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
            return output.toString(StandardCharsets.UTF_8.name());
        }
    }

    private String httpErrorMessage(String label, HttpResult result) {
        if (result.status == 401) {
            return "Not authorized. Check the Codex Link token.";
        }
        if (result.status == 404) {
            return label + " was not found. Check the endpoint and bridge.";
        }
        if (result.status == 502 || result.status == 503 || result.status == 504) {
            return "Web Link relay could not reach the Windows tunnel.";
        }
        if (result.body.isEmpty()) {
            return label + " replied with HTTP " + result.status + ".";
        }
        return "HTTP " + result.status + ": " + result.body;
    }

    private void startAsForeground(String text) {
        Notification notification = NotificationHelper.buildQueueNotification(this, "Codex Link queue", text, true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private void updateNotification(String title, String text) {
        NotificationHelper.showQueueNotification(this, NOTIFICATION_ID, title, text, true);
    }

    private void stopForegroundAndSelf() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE);
        } else {
            stopForeground(true);
        }
        stopSelf();
    }

    private void broadcastQueueChanged(String threadId, String status, boolean isError) {
        Intent intent = new Intent(ACTION_QUEUE_CHANGED);
        intent.setPackage(getPackageName());
        intent.putExtra(EXTRA_THREAD_ID, threadId == null ? "" : threadId);
        intent.putExtra(EXTRA_STATUS, status == null ? "" : status);
        intent.putExtra(EXTRA_ERROR, isError);
        sendBroadcast(intent);
    }

    private void sleepFor(long millis) {
        long end = SystemClock.elapsedRealtime() + millis;
        while (!stopRequested && SystemClock.elapsedRealtime() < end) {
            SystemClock.sleep(Math.min(500, Math.max(1, end - SystemClock.elapsedRealtime())));
        }
    }

    static void start(Context context) {
        Intent intent = new Intent(context, CodexQueueService.class);
        intent.setAction(ACTION_PROCESS_QUEUE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }
}

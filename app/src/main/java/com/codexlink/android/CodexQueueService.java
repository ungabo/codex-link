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
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class CodexQueueService extends Service {
    static final String ACTION_PROCESS_QUEUE = "com.codexlink.android.PROCESS_QUEUE";
    static final String ACTION_STOP_QUEUE = "com.codexlink.android.STOP_QUEUE";
    static final String ACTION_QUEUE_CHANGED = "com.codexlink.android.QUEUE_CHANGED";
    static final String EXTRA_THREAD_ID = "thread_id";
    static final String EXTRA_STATUS = "status";
    static final String EXTRA_ERROR = "error";
    static final String EXTRA_REQUEST_ID = "request_id";
    static final String EXTRA_REQUEST_TURN_ID = "request_turn_id";
    static final String EXTRA_REQUEST_STAGE = "request_stage";
    static final String EXTRA_REQUEST_SUMMARY = "request_summary";

    private static final String TAG = "CodexQueueService";
    private static final String PREFS = "codex_link";
    private static final String PREF_TOKEN = "token";
    private static final String PREF_CONNECTION_MODE = "connection_mode";
    private static final String PREF_LOCAL_ENDPOINT = "local_endpoint";
    private static final String PREF_LOCAL_TOKEN = "local_token";
    private static final String PREF_WEB_ENDPOINT = "web_endpoint";
    private static final String PREF_WEB_TOKEN = "web_token";
    private static final String PREF_QUEUE_PAUSED = "queue_paused";
    private static final String PREF_QUEUE_STALLED_DETAIL = "queue_stalled_detail";
    private static final String PREF_QUEUE_FAILURE_PREFIX = "queue_failure_count_";
    private static final String PREF_QUEUE_FAILURE_DETAIL_PREFIX = "queue_failure_detail_";
    private static final String PREF_EDITING_QUEUE_THREAD_ID = "editing_queue_thread_id";
    private static final String PREF_EDITING_QUEUE_ITEM_ID = "editing_queue_item_id";
    private static final String MODE_WEB = "web";
    private static final String DEFAULT_WEB_ENDPOINT = "https://www.sitesindevelopment.com/codex-link/index.php/link";
    private static final String DEFAULT_WEB_TOKEN = BuildConfig.CODEX_LINK_DEFAULT_WEB_TOKEN;
    private static final int NOTIFICATION_ID = 18766;
    private static final int READ_TIMEOUT_MS = 120000;
    private static final int CONNECT_TIMEOUT_MS = 10000;
    private static final int MAX_FAILURES_BEFORE_PAUSE = 3;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private volatile boolean workerRunning = false;
    private volatile boolean stopRequested = false;
    private String lastNotificationTitle = "";
    private String lastNotificationText = "";

    private static class QueueItem {
        final String id;
        final long createdAt;
        final JSONObject payload;

        QueueItem(String id, long createdAt, JSONObject payload) {
            this.id = id == null || id.isEmpty() ? "" : id;
            this.createdAt = createdAt;
            this.payload = payload;
            ensurePayloadRequestId(this.payload, this.id);
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

    private static class ProcessingMonitorResult {
        final boolean active;
        final boolean completed;

        ProcessingMonitorResult(boolean active, boolean completed) {
            this.active = active;
            this.completed = completed;
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
        String action = intent == null ? "" : intent.getAction();
        startAsForeground("Checking queued Codex messages...");
        if (ACTION_STOP_QUEUE.equals(action)) {
            stopRequested = true;
            setQueuePaused(true, "Queue stopped from the Android app.");
            broadcastQueueChanged("", "Queue stopped. Tap Resume queue to continue.", false);
            stopForegroundAndSelf();
            return START_NOT_STICKY;
        }
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
            if (isQueuePaused()) {
                String detail = queuePausedDetail();
                if (isRecoverablePausedQueue(detail)) {
                    setQueuePaused(false, "");
                    broadcastQueueChanged("", "Queue resumed after stale state recovery.", false);
                } else {
                    broadcastQueueChanged("", detail.isEmpty() ? "Queue paused. Tap Resume queue to continue." : detail, false);
                    updateNotification("Codex Link queue paused", detail.isEmpty() ? "Tap Resume queue to continue" : detail);
                    stopForegroundAndSelf();
                    return;
                }
            }
            EndpointConfig config = endpointConfig();
            if (config.endpoint.isEmpty()) {
                pauseQueue("No Codex Link endpoint is configured.");
                return;
            }
            ArrayList<QueueFile> queues = allQueues();
            int queuedCount = totalQueuedCount(queues);
            ProcessingMonitorResult monitor = monitorProcessingHistories(config);
            Log.i(TAG, "Queue pass found " + queuedCount + " queued message(s) in " + queues.size() + " chat(s).");
            if (queuedCount == 0) {
                if (monitor.active) {
                    broadcastQueueChanged("", "Accepted Codex request is still processing in Windows.", false);
                    updateNotification("Codex Link waiting for Windows", "Accepted request is still processing");
                    sleepFor(15000);
                    continue;
                }
                broadcastQueueChanged("", monitor.completed ? "Accepted Codex request completed." : "Queue empty.", false);
                stopForegroundAndSelf();
                return;
            }

            updateNotification("Sending queued Codex messages", queuedCount + " queued");

            boolean madeProgress = false;
            boolean sawBusyThread = false;
            boolean sawStalledItem = false;
            String lastStatus = "";
            for (QueueFile queue : queues) {
                if (stopRequested || queue.items.isEmpty()) {
                    continue;
                }

                QueueItem item = null;
                for (QueueItem candidate : queue.items) {
                    if (isQueueItemBeingEdited(queue.threadId, candidate.id)) {
                        sawBusyThread = true;
                        lastStatus = "Waiting for you to save or cancel the queued edit.";
                        broadcastQueueChanged(queue.threadId, candidate, "Editing", lastStatus, false);
                        continue;
                    }
                    if (isQueueItemStalled(queue.threadId, candidate.id)) {
                        sawStalledItem = true;
                        lastStatus = stalledItemDetail(queue.threadId, candidate.id);
                        broadcastQueueChanged(queue.threadId, candidate, "Error", lastStatus, true);
                        continue;
                    }
                    item = candidate;
                    break;
                }
                if (item == null) {
                    continue;
                }
                try {
                    ThreadState state = loadThreadState(config, queue.threadId);
                    Log.i(TAG, "Queue thread " + queue.threadId + " active=" + state.active);
                    if (state.active) {
                        sawBusyThread = true;
                        lastStatus = activeStatusText(state);
                        broadcastQueueChanged(queue.threadId, item, "Waiting", lastStatus, false);
                        continue;
                    }
                    if (state.staleActive) {
                        lastStatus = staleStatusText(state) + " Treating it as idle and continuing the queue.";
                        markThreadProcessingHistoryCompleted(queue.threadId, lastStatus);
                        broadcastQueueChanged(queue.threadId, item, "Recovered", lastStatus, false);
                    }
                    markThreadProcessingHistoryCompleted(
                            queue.threadId,
                            "Windows is idle. The accepted request finished and the queue is continuing.");

                    String turnsEndpoint = threadTurnsEndpointFor(config.endpoint, queue.threadId);
                    Log.i(TAG, "Posting queued turn for " + queue.threadId + " to " + turnsEndpoint);
                    broadcastQueueChanged(queue.threadId, item, "Sending", "Sending to Windows.", false);
                    ensurePayloadRequestId(item.payload, item.id);
                    HttpResult result = postJson(config, turnsEndpoint, item.payload);
                    Log.i(TAG, "Queued turn result for " + queue.threadId + ": HTTP " + result.status);
                    if (result.status == 409) {
                        sawBusyThread = true;
                        lastStatus = "Windows is processing this chat. Queue will retry when it finishes.";
                        broadcastQueueChanged(queue.threadId, item, "Waiting", lastStatus, false);
                        continue;
                    }
                    if (!result.isOk()) {
                        lastStatus = httpErrorMessage("Queued send", result);
                        Log.w(TAG, lastStatus);
                        broadcastQueueChanged(queue.threadId, item, "Error", lastStatus, true);
                        if (recordFailureAndShouldPause(queue.threadId, item.id, lastStatus)) {
                            sawStalledItem = true;
                        }
                        continue;
                    }

                    madeProgress = true;
                    clearFailure(queue.threadId, item.id);
                    String sentStage;
                    String sentDetail;
                    String acceptedTurnId = turnIdFromTurnResponse(result.body);
                    if (isProcessingResponse(result)) {
                        sentStage = "Processing";
                        sentDetail = "Received by Windows once and removed from the phone queue. Waiting for Windows to finish.";
                    } else {
                        sentStage = "Completed";
                        sentDetail = "Received by Windows and completed by Codex.";
                    }
                    archiveSentQueuedItem(queue.threadId, item, sentStage, sentDetail, acceptedTurnId);
                    removeQueuedItem(queue.threadId, item.id);
                    if ("Completed".equalsIgnoreCase(sentStage)) {
                        NotificationHelper.showTurnCompletedNotification(this, queue.threadId, "");
                    }
                    lastStatus = sentDetail;
                    broadcastQueueChanged(queue.threadId, item, sentStage, lastStatus, false, acceptedTurnId);
                    break;
                } catch (Exception error) {
                    lastStatus = error.getMessage() == null ? "Queued send failed." : error.getMessage();
                    Log.w(TAG, "Queue pass failed for " + queue.threadId, error);
                    broadcastQueueChanged(queue.threadId, item, "Error", lastStatus, true);
                    if (recordFailureAndShouldPause(queue.threadId, item.id, lastStatus)) {
                        sawStalledItem = true;
                    }
                }
            }

            if (madeProgress) {
                sleepFor(2500);
            } else if (sawStalledItem) {
                pauseQueue(lastStatus.isEmpty() ? "A queued message needs edit, delete, or Try now." : lastStatus);
                return;
            } else if (sawBusyThread) {
                updateNotification("Codex Link queue waiting", lastStatus.isEmpty() ? "Waiting for active chats" : lastStatus);
                sleepFor(15000);
            } else {
                updateNotification("Codex Link queue retrying", lastStatus.isEmpty() ? "Queued sends could not complete" : lastStatus);
                sleepFor(30000);
            }
        }
    }

    private boolean recordFailureAndShouldPause(String threadId, String itemId, String detail) {
        String key = failureKey(threadId, itemId);
        String cleanDetail = cleanFailureDetail(detail);
        int count = getSharedPreferences(PREFS, MODE_PRIVATE).getInt(PREF_QUEUE_FAILURE_PREFIX + key, 0) + 1;
        getSharedPreferences(PREFS, MODE_PRIVATE)
                .edit()
                .putInt(PREF_QUEUE_FAILURE_PREFIX + key, count)
                .putString(PREF_QUEUE_FAILURE_DETAIL_PREFIX + key, cleanDetail)
                .apply();
        if (count < MAX_FAILURES_BEFORE_PAUSE) {
            return false;
        }
        updateNotification("Codex Link queue item stalled", "Use Edit, Delete, or Try now for the failed queued message.");
        return true;
    }

    private boolean isQueueItemStalled(String threadId, String itemId) {
        String key = failureKey(threadId, itemId);
        return getSharedPreferences(PREFS, MODE_PRIVATE)
                .getInt(PREF_QUEUE_FAILURE_PREFIX + key, 0) >= MAX_FAILURES_BEFORE_PAUSE;
    }

    private String stalledItemDetail(String threadId, String itemId) {
        String key = failureKey(threadId, itemId);
        String detail = getSharedPreferences(PREFS, MODE_PRIVATE)
                .getString(PREF_QUEUE_FAILURE_DETAIL_PREFIX + key, "");
        if (detail == null || detail.trim().isEmpty()) {
            return "Queued message stalled. Use Edit, Delete, or Try now.";
        }
        return "Queued message stalled. " + detail;
    }

    private void clearFailure(String threadId, String itemId) {
        String key = failureKey(threadId, itemId);
        getSharedPreferences(PREFS, MODE_PRIVATE)
                .edit()
                .remove(PREF_QUEUE_FAILURE_PREFIX + key)
                .remove(PREF_QUEUE_FAILURE_DETAIL_PREFIX + key)
                .apply();
    }

    private String failureKey(String threadId, String itemId) {
        String raw = (threadId == null ? "" : threadId) + ":" + (itemId == null ? "" : itemId);
        return raw.replaceAll("[^A-Za-z0-9._:-]+", "_");
    }

    private String cleanFailureDetail(String detail) {
        if (detail == null || detail.trim().isEmpty()) {
            return "Tap Resume queue after fixing the problem.";
        }
        String clean = detail.replace('\r', ' ').replace('\n', ' ').trim();
        if (clean.length() > 260) {
            clean = clean.substring(0, 257).trim() + "...";
        }
        return clean;
    }

    private void pauseQueue(String detail) {
        String clean = cleanFailureDetail(detail);
        setQueuePaused(true, clean);
        broadcastQueueChanged("", clean, true);
        updateNotification("Codex Link queue stalled", clean);
        stopForegroundAndSelf();
    }

    private boolean isQueuePaused() {
        return getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(PREF_QUEUE_PAUSED, false);
    }

    private boolean isQueueItemBeingEdited(String threadId, String itemId) {
        if (threadId == null || threadId.isEmpty() || itemId == null || itemId.isEmpty()) {
            return false;
        }
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        return threadId.equals(prefs.getString(PREF_EDITING_QUEUE_THREAD_ID, ""))
                && itemId.equals(prefs.getString(PREF_EDITING_QUEUE_ITEM_ID, ""));
    }

    private String queuePausedDetail() {
        return getSharedPreferences(PREFS, MODE_PRIVATE).getString(PREF_QUEUE_STALLED_DETAIL, "");
    }

    private boolean isRecoverablePausedQueue(String detail) {
        String lower = detail == null ? "" : detail.toLowerCase(Locale.US);
        return lower.contains("queue preserved after qa")
                || lower.contains("queue paused so it does not resend");
    }

    private void setQueuePaused(boolean paused, String detail) {
        getSharedPreferences(PREFS, MODE_PRIVATE)
                .edit()
                .putBoolean(PREF_QUEUE_PAUSED, paused)
                .putString(PREF_QUEUE_STALLED_DETAIL, detail == null ? "" : detail)
                .apply();
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
        JSONObject activity = response.optJSONObject("activity");
        if (activity != null) {
            active = thread.optBoolean("active", activity.optBoolean("active", active));
            staleActive = thread.optBoolean("staleActive", activity.optBoolean("staleActive", staleActive));
        }
        return new ThreadState(
                active,
                staleActive,
                thread.optString("staleReason", activity == null ? "" : activity.optString("staleReason", "")),
                thread.optString("status", activity == null ? "" : activity.optString("status", "")),
                thread.optString("activeTurnId", activity == null ? "" : activity.optString("activeTurnId", "")),
                thread.optString("activeStartedAt", activity == null ? "" : activity.optString("activeStartedAt", "")),
                activity == null
                        ? thread.optString("updatedAt", "")
                        : activity.optString("activeLastEventAt", activity.optString("lastEventAt", thread.optString("updatedAt", ""))),
                response.optString("generatedAt", ""),
                activity == null ? 0 : activity.optInt("activeCount", 0));
    }

    private static class ThreadState {
        final boolean active;
        final boolean staleActive;
        final String staleReason;
        final String status;
        final String activeTurnId;
        final String activeStartedAt;
        final String lastEventAt;
        final String checkedAt;
        final int activeCount;

        ThreadState(boolean active, boolean staleActive, String staleReason, String status, String activeTurnId, String activeStartedAt, String lastEventAt, String checkedAt, int activeCount) {
            this.active = active;
            this.staleActive = staleActive;
            this.staleReason = staleReason == null ? "" : staleReason;
            this.status = status == null ? "" : status;
            this.activeTurnId = activeTurnId == null ? "" : activeTurnId;
            this.activeStartedAt = activeStartedAt == null ? "" : activeStartedAt;
            this.lastEventAt = lastEventAt == null ? "" : lastEventAt;
            this.checkedAt = checkedAt == null ? "" : checkedAt;
            this.activeCount = activeCount;
        }
    }

    private String staleStatusText(ThreadState state) {
        StringBuilder builder = new StringBuilder();
        builder.append("Stale processing marker cleared because Windows now reports the chat as idle.");
        if (state != null && !state.activeStartedAt.isEmpty()) {
            builder.append(" Started ").append(formatIsoForStatus(state.activeStartedAt)).append(".");
        }
        if (state != null && !state.staleReason.isEmpty()) {
            builder.append(" Reason: ").append(state.staleReason).append(".");
        }
        return builder.toString();
    }

    private String activeStatusText(ThreadState state) {
        StringBuilder builder = new StringBuilder();
        builder.append("Windows is processing this chat.");
        if (state != null && !state.activeStartedAt.isEmpty()) {
            builder.append(" Processing since ").append(formatIsoForStatus(state.activeStartedAt)).append(".");
        }
        if (state != null && !state.lastEventAt.isEmpty()) {
            builder.append(" Last desktop output ").append(formatIsoForStatus(state.lastEventAt)).append(".");
        }
        if (state != null && !state.checkedAt.isEmpty()) {
            builder.append(" Checked ").append(formatIsoForStatus(state.checkedAt)).append(".");
        }
        builder.append(" Queue will retry when it finishes.");
        return builder.toString();
    }

    private String formatIsoForStatus(String isoTimestamp) {
        if (isoTimestamp == null || isoTimestamp.trim().isEmpty()) {
            return "";
        }
        String clean = isoTimestamp.trim();
        String[] patterns = {
                "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
                "yyyy-MM-dd'T'HH:mm:ss'Z'"
        };
        for (String pattern : patterns) {
            try {
                SimpleDateFormat parser = new SimpleDateFormat(pattern, Locale.US);
                parser.setTimeZone(TimeZone.getTimeZone("UTC"));
                Date date = parser.parse(clean);
                if (date == null) {
                    continue;
                }
                SimpleDateFormat output = new SimpleDateFormat("MMM d, h:mm a", Locale.US);
                output.setTimeZone(TimeZone.getDefault());
                return output.format(date);
            } catch (ParseException ignored) {
            }
        }
        return clean;
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
        String requestId = payload.optString("androidRequestId", payload.optString("clientRequestId", ""));
        if (!requestId.isEmpty()) {
            connection.setRequestProperty("X-Codex-Link-Request-Id", requestId);
        }
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

    private static void ensurePayloadRequestId(JSONObject payload, String requestId) {
        if (payload == null || requestId == null || requestId.trim().isEmpty()) {
            return;
        }
        try {
            if (payload.optString("androidRequestId", "").isEmpty()) {
                payload.put("androidRequestId", requestId);
            }
            if (payload.optString("clientRequestId", "").isEmpty()) {
                payload.put("clientRequestId", requestId);
            }
        } catch (JSONException ignored) {
        }
    }

    private static String requestIdForPayload(JSONObject payload) {
        if (payload == null) {
            return "";
        }
        String id = payload.optString("androidRequestId", "");
        if (id == null || id.isEmpty()) {
            id = payload.optString("clientRequestId", "");
        }
        return id == null ? "" : id;
    }

    private boolean isProcessingResponse(HttpResult result) {
        if (result == null || result.body == null || result.body.isEmpty()) {
            return result != null && result.status == 202;
        }
        try {
            JSONObject object = new JSONObject(result.body);
            if ("processing".equalsIgnoreCase(object.optString("status", ""))) {
                return true;
            }
            if (object.optBoolean("accepted", false) && !object.optBoolean("completed", true)) {
                return true;
            }
        } catch (JSONException ignored) {
        }
        return result.status == 202;
    }

    private String turnIdFromTurnResponse(String body) {
        if (body == null || body.trim().isEmpty()) {
            return "";
        }
        try {
            JSONObject object = new JSONObject(body);
            String id = object.optString("turnId", "");
            if (!id.isEmpty()) {
                return id;
            }
            JSONObject turn = object.optJSONObject("turn");
            return turn == null ? "" : turn.optString("id", "");
        } catch (JSONException ignored) {
            return "";
        }
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
            String name = file.getName();
            String threadId = name.substring(0, name.length() - ".json".length());
            pruneAcceptedQueuedItems(threadId);
            ArrayList<QueueItem> items = readQueue(file);
            if (items.isEmpty()) {
                continue;
            }
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
        synchronized (QueueStorage.LOCK) {
            if (!file.exists()) {
                return items;
            }
            try {
                JSONArray array = QueueStorage.readArray(file);
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

    private boolean pruneAcceptedQueuedItems(String threadId) {
        if (threadId == null || threadId.isEmpty()) {
            return false;
        }
        File queueFile = queueFileForThread(threadId);
        File historyFile = sentQueueFileForThread(threadId);
        if (!queueFile.exists() || !historyFile.exists()) {
            return false;
        }
        synchronized (QueueStorage.LOCK) {
            HashSet<String> acceptedIds = acceptedQueueHistoryIdsLocked(historyFile);
            if (acceptedIds.isEmpty()) {
                return false;
            }
            JSONArray existing = QueueStorage.readArray(queueFile);
            JSONArray cleaned = new JSONArray();
            boolean changed = false;
            for (int index = 0; index < existing.length(); index++) {
                JSONObject item = existing.optJSONObject(index);
                if (item == null) {
                    continue;
                }
                String id = item.optString("id", "");
                String payloadId = requestIdForPayload(item.optJSONObject("payload"));
                if ((!id.isEmpty() && acceptedIds.contains(id))
                        || (!payloadId.isEmpty() && acceptedIds.contains(payloadId))) {
                    clearFailure(threadId, id);
                    changed = true;
                    continue;
                }
                cleaned.put(item);
            }
            if (!changed) {
                return false;
            }
            try {
                QueueStorage.writeArrayAtomic(queueFile, cleaned);
                return true;
            } catch (Exception error) {
                Log.w(TAG, "Could not prune accepted queue items for " + threadId, error);
                return false;
            }
        }
    }

    private HashSet<String> acceptedQueueHistoryIdsLocked(File historyFile) {
        HashSet<String> ids = new HashSet<>();
        JSONArray history = QueueStorage.readArray(historyFile);
        for (int index = 0; index < history.length(); index++) {
            JSONObject item = history.optJSONObject(index);
            if (item == null || !isAcceptedQueueHistoryStage(item.optString("stage", ""))) {
                continue;
            }
            String id = item.optString("id", "");
            if (!id.isEmpty()) {
                ids.add(id);
            }
            String payloadId = requestIdForPayload(item.optJSONObject("payload"));
            if (!payloadId.isEmpty()) {
                ids.add(payloadId);
            }
        }
        return ids;
    }

    private boolean isAcceptedQueueHistoryStage(String stage) {
        return "Processing".equalsIgnoreCase(stage)
                || "Completed".equalsIgnoreCase(stage)
                || "Sent".equalsIgnoreCase(stage);
    }

    private void removeQueuedItem(String threadId, String itemId) {
        File file = queueFileForThread(threadId);
        synchronized (QueueStorage.LOCK) {
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
            try {
                QueueStorage.writeArrayAtomic(file, array);
            } catch (Exception error) {
                Log.w(TAG, "Could not update queue " + threadId, error);
            }
        }
    }

    private void archiveSentQueuedItem(String threadId, QueueItem item, String stage, String detail, String turnId) {
        if (threadId == null || threadId.isEmpty() || item == null) {
            return;
        }
        File file = sentQueueFileForThread(threadId);
        synchronized (QueueStorage.LOCK) {
            try {
                JSONArray array = QueueStorage.readArray(file);
                array.put(new JSONObject()
                        .put("threadId", threadId)
                        .put("id", item.id)
                        .put("createdAt", item.createdAt)
                        .put("sentAt", System.currentTimeMillis())
                        .put("stage", stage == null || stage.isEmpty() ? "Sent" : stage)
                        .put("detail", detail == null ? "" : detail)
                        .put("turnId", turnId == null ? "" : turnId)
                        .put("payload", item.payload));
                JSONArray trimmed = new JSONArray();
                int start = Math.max(0, array.length() - 50);
                for (int index = start; index < array.length(); index++) {
                    trimmed.put(array.opt(index));
                }
                QueueStorage.writeArrayAtomic(file, trimmed);
            } catch (Exception error) {
                Log.w(TAG, "Could not archive sent queue item " + threadId, error);
            }
        }
    }

    private void markThreadProcessingHistoryCompleted(String threadId, String detail) {
        if (threadId == null || threadId.isEmpty()) {
            return;
        }
        File file = sentQueueFileForThread(threadId);
        synchronized (QueueStorage.LOCK) {
            JSONArray array = QueueStorage.readArray(file);
            boolean changed = false;
            long completedAt = System.currentTimeMillis();
            for (int index = 0; index < array.length(); index++) {
                JSONObject item = array.optJSONObject(index);
                if (item == null) {
                    continue;
                }
                if ("Processing".equalsIgnoreCase(item.optString("stage", ""))) {
                    try {
                        item.put("stage", "Completed");
                        item.put("detail", detail == null ? "Windows is idle. The request finished." : detail);
                        item.put("completedAt", completedAt);
                        changed = true;
                    } catch (JSONException error) {
                        Log.w(TAG, "Could not update sent queue history item", error);
                    }
                }
            }
            if (!changed) {
                return;
            }
            try {
                QueueStorage.writeArrayAtomic(file, array);
                NotificationHelper.showTurnCompletedNotification(this, threadId, "");
            } catch (Exception error) {
                Log.w(TAG, "Could not mark queue history completed for " + threadId, error);
            }
        }
    }

    private ProcessingMonitorResult monitorProcessingHistories(EndpointConfig config) {
        File dir = new File(getFilesDir(), "queue-history");
        File[] files = dir.listFiles((file, name) -> name.endsWith(".json"));
        if (files == null || files.length == 0) {
            return new ProcessingMonitorResult(false, false);
        }

        boolean sawActive = false;
        boolean completedAny = false;
        for (File file : files) {
            String name = file.getName();
            String threadId = name.substring(0, name.length() - ".json".length());
            if (!historyHasProcessingItem(file)) {
                continue;
            }
            try {
                ThreadState state = loadThreadState(config, threadId);
                if (state.active) {
                    sawActive = true;
                    continue;
                }
                if (state.staleActive) {
                    markThreadProcessingHistoryCompleted(
                            threadId,
                            "Windows reported stale processing. The phone stopped treating this old request as active.");
                    completedAny = true;
                    continue;
                }
                markThreadProcessingHistoryCompleted(
                        threadId,
                        "Windows is idle. The accepted request finished.");
                completedAny = true;
            } catch (Exception error) {
                sawActive = true;
                Log.w(TAG, "Could not monitor processing queue history for " + threadId, error);
            }
        }
        return new ProcessingMonitorResult(sawActive, completedAny);
    }

    private boolean historyHasProcessingItem(File file) {
        synchronized (QueueStorage.LOCK) {
            JSONArray array = QueueStorage.readArray(file);
            for (int index = 0; index < array.length(); index++) {
                JSONObject item = array.optJSONObject(index);
                if (item != null && "Processing".equalsIgnoreCase(item.optString("stage", ""))) {
                    return true;
                }
            }
        }
        return false;
    }

    private JSONArray readJsonArrayFile(File file) {
        synchronized (QueueStorage.LOCK) {
            return QueueStorage.readArray(file);
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

    private File sentQueueFileForThread(String threadId) {
        return new File(new File(getFilesDir(), "queue-history"), safeFileName(threadId) + ".json");
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
        return new URL(url.getProtocol(), url.getHost(), url.getPort(), apiBasePathFor(url) + "threads/" + encodedThreadId + "/status").toString();
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
        String cleanPath = path.endsWith("/") && path.length() > 1
                ? path.substring(0, path.length() - 1)
                : path;
        if (cleanPath.equals("/codex-link/index.php") || cleanPath.startsWith("/codex-link/index.php/")) {
            return "/codex-link/index.php/";
        }
        if (cleanPath.equals("/codex")) {
            return "/codex/";
        }
        if (cleanPath.startsWith("/codex/")) {
            String remainder = cleanPath.substring("/codex/".length());
            if (isApiRouteSegment(firstPathSegment(remainder))) {
                return "/codex/";
            }
        }
        String firstSegment = firstPathSegment(cleanPath.startsWith("/") ? cleanPath.substring(1) : cleanPath);
        if (isApiRouteSegment(firstSegment)) {
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

    private String firstPathSegment(String path) {
        if (path == null || path.isEmpty()) {
            return "";
        }
        int slash = path.indexOf('/');
        return slash >= 0 ? path.substring(0, slash) : path;
    }

    private boolean isApiRouteSegment(String segment) {
        if (segment == null) {
            return false;
        }
        return "link".equals(segment)
                || "catalog".equals(segment)
                || "threads".equals(segment)
                || "health".equals(segment)
                || "server-health".equals(segment);
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
        String cleanTitle = title == null ? "" : title;
        String cleanText = text == null ? "" : text;
        if (cleanTitle.equals(lastNotificationTitle) && cleanText.equals(lastNotificationText)) {
            return;
        }
        lastNotificationTitle = cleanTitle;
        lastNotificationText = cleanText;
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

    private void broadcastQueueChanged(String threadId, QueueItem item, String stage, String status, boolean isError) {
        broadcastQueueChanged(threadId, item, stage, status, isError, "");
    }

    private void broadcastQueueChanged(String threadId, QueueItem item, String stage, String status, boolean isError, String turnId) {
        Intent intent = new Intent(ACTION_QUEUE_CHANGED);
        intent.setPackage(getPackageName());
        intent.putExtra(EXTRA_THREAD_ID, threadId == null ? "" : threadId);
        intent.putExtra(EXTRA_STATUS, status == null ? "" : status);
        intent.putExtra(EXTRA_ERROR, isError);
        intent.putExtra(EXTRA_REQUEST_ID, item == null ? "" : item.id);
        intent.putExtra(EXTRA_REQUEST_TURN_ID, turnId == null ? "" : turnId);
        intent.putExtra(EXTRA_REQUEST_STAGE, stage == null ? "" : stage);
        intent.putExtra(EXTRA_REQUEST_SUMMARY, requestSummary(item));
        sendBroadcast(intent);
    }

    private String requestSummary(QueueItem item) {
        if (item == null || item.payload == null) {
            return "";
        }
        String prompt = item.payload.optString("prompt", "").trim();
        if (prompt.isEmpty()) {
            prompt = "Image-only message";
        }
        JSONArray images = item.payload.optJSONArray("images");
        int imageCount = images == null ? 0 : images.length();
        if (imageCount > 0) {
            prompt = prompt + " (" + imageCount + (imageCount == 1 ? " image" : " images") + ")";
        }
        prompt = prompt.replace('\r', ' ').replace('\n', ' ').trim();
        return prompt.length() > 120 ? prompt.substring(0, 117).trim() + "..." : prompt;
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

    static void stopQueue(Context context) {
        context.getSharedPreferences(PREFS, MODE_PRIVATE)
                .edit()
                .putBoolean(PREF_QUEUE_PAUSED, true)
                .putString(PREF_QUEUE_STALLED_DETAIL, "Queue stopped from the Android app.")
                .apply();
        Intent intent = new Intent(context, CodexQueueService.class);
        intent.setAction(ACTION_STOP_QUEUE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }

    static void clearQueueFailures(Context context) {
        android.content.SharedPreferences prefs = context.getSharedPreferences(PREFS, MODE_PRIVATE);
        android.content.SharedPreferences.Editor editor = prefs.edit();
        for (String key : prefs.getAll().keySet()) {
            if (key.startsWith(PREF_QUEUE_FAILURE_PREFIX) || key.startsWith(PREF_QUEUE_FAILURE_DETAIL_PREFIX)) {
                editor.remove(key);
            }
        }
        editor.apply();
    }
}

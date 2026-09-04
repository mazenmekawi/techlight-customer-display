package sa.techlight.customerdisplay;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Durable API mutation queue. It never reports a local state change as synced
 * unless the configured official endpoint returns HTTP 2xx. When no mutation
 * endpoint is configured, actions remain visibly pending instead of guessing.
 */
public final class KitchenStatusSyncQueue {
    public interface Listener {
        void onSyncResult(String orderId, boolean synced, String detail);
    }

    private static final String PREF = "techlight_kds_status_queue_v1";
    private static final String KEY = "pending";
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    private final SharedPreferences preferences;
    private final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(12, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .writeTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .build();
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final String token;
    private volatile String endpoint;
    private volatile Listener listener;

    public KitchenStatusSyncQueue(Context context, String token, String endpoint) {
        preferences = context.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        this.token = stripBearer(token);
        this.endpoint = clean(endpoint);
    }

    public void setListener(Listener listener) { this.listener = listener; }
    public void setEndpoint(String endpoint) { this.endpoint = clean(endpoint); }
    public boolean isConfigured() { return !clean(endpoint).isEmpty(); }

    public synchronized int pendingCount() {
        try { return new JSONArray(preferences.getString(KEY, "[]")).length(); }
        catch (Throwable ignored) { return 0; }
    }

    public void enqueue(KitchenOrder order, KitchenOrder.Status status, String employee) {
        if (order == null || status == null) return;
        JSONObject event = new JSONObject();
        try {
            event.put("eventId", order.id + "-" + status.name() + "-" + System.currentTimeMillis());
            event.put("orderId", order.id);
            event.put("invoiceNumber", order.bestNumber());
            event.put("status", apiStatus(status));
            event.put("employee", clean(employee));
            event.put("occurredAt", System.currentTimeMillis());
            event.put("attempts", 0);
        } catch (Throwable ignored) { return; }
        append(event);
        if (!isConfigured()) {
            notifyResult(order.id, false, "Official status endpoint is not configured");
            return;
        }
        flush();
    }

    public void flush() {
        if (!isConfigured()) return;
        executor.execute(this::flushBlocking);
    }

    private void flushBlocking() {
        while (isConfigured()) {
            JSONObject event = first();
            if (event == null) return;
            String orderId = event.optString("orderId", "");
            try {
                RequestBody body = RequestBody.create(JSON, event.toString());
                Request.Builder builder = new Request.Builder().url(endpoint).post(body)
                        .header("Accept", "application/json")
                        .header("Content-Type", "application/json");
                if (!token.isEmpty()) builder.header("Authorization", "Bearer " + token);
                try (Response response = client.newCall(builder.build()).execute()) {
                    if (response.isSuccessful()) {
                        removeFirst(event.optString("eventId", ""));
                        notifyResult(orderId, true, "HTTP " + response.code());
                        continue;
                    }
                    incrementAttempts(event.optString("eventId", ""));
                    notifyResult(orderId, false, "HTTP " + response.code());
                    return;
                }
            } catch (Throwable error) {
                incrementAttempts(event.optString("eventId", ""));
                notifyResult(orderId, false,
                        error.getClass().getSimpleName() + ": " + String.valueOf(error.getMessage()));
                return;
            }
        }
    }

    private synchronized void append(JSONObject event) {
        try {
            JSONArray array = new JSONArray(preferences.getString(KEY, "[]"));
            String eventId = event.optString("eventId", "");
            for (int i = 0; i < array.length(); i++) {
                JSONObject old = array.optJSONObject(i);
                if (old != null && eventId.equals(old.optString("eventId", ""))) return;
            }
            array.put(event);
            while (array.length() > 500) {
                JSONArray trimmed = new JSONArray();
                for (int i = Math.max(1, array.length() - 499); i < array.length(); i++) trimmed.put(array.opt(i));
                array = trimmed;
            }
            preferences.edit().putString(KEY, array.toString()).apply();
        } catch (Throwable ignored) { }
    }

    private synchronized JSONObject first() {
        try {
            JSONArray array = new JSONArray(preferences.getString(KEY, "[]"));
            return array.length() == 0 ? null : array.optJSONObject(0);
        } catch (Throwable ignored) { return null; }
    }

    private synchronized void removeFirst(String eventId) {
        try {
            JSONArray array = new JSONArray(preferences.getString(KEY, "[]"));
            JSONArray next = new JSONArray();
            boolean removed = false;
            for (int i = 0; i < array.length(); i++) {
                JSONObject event = array.optJSONObject(i);
                if (!removed && event != null && eventId.equals(event.optString("eventId", ""))) {
                    removed = true;
                    continue;
                }
                next.put(array.opt(i));
            }
            preferences.edit().putString(KEY, next.toString()).apply();
        } catch (Throwable ignored) { }
    }

    private synchronized void incrementAttempts(String eventId) {
        try {
            JSONArray array = new JSONArray(preferences.getString(KEY, "[]"));
            for (int i = 0; i < array.length(); i++) {
                JSONObject event = array.optJSONObject(i);
                if (event != null && eventId.equals(event.optString("eventId", ""))) {
                    event.put("attempts", event.optInt("attempts", 0) + 1);
                    event.put("lastAttemptAt", System.currentTimeMillis());
                    break;
                }
            }
            preferences.edit().putString(KEY, array.toString()).apply();
        } catch (Throwable ignored) { }
    }

    private void notifyResult(String orderId, boolean success, String detail) {
        Listener current = listener;
        if (current != null) current.onSyncResult(orderId, success, detail);
    }

    public void shutdown() {
        executor.shutdownNow();
        try { client.dispatcher().executorService().shutdown(); } catch (Throwable ignored) { }
        try { client.connectionPool().evictAll(); } catch (Throwable ignored) { }
    }

    private static String apiStatus(KitchenOrder.Status status) {
        if (status == KitchenOrder.Status.PREPARING) return "Preparing";
        if (status == KitchenOrder.Status.READY) return "Ready";
        if (status == KitchenOrder.Status.DONE) return "Completed";
        if (status == KitchenOrder.Status.CANCELLED) return "Cancelled";
        return "New";
    }

    private static String stripBearer(String value) {
        String clean = clean(value);
        if (clean.toLowerCase(java.util.Locale.US).startsWith("bearer ")) return clean.substring(7).trim();
        return clean;
    }
    private static String clean(String value) { return value == null ? "" : value.trim(); }
}

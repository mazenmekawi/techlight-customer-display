package sa.techlight.customerdisplay;

import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Locale;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

public final class TechProClient {
    public interface Listener {
        void onConnected();
        void onDisconnected(String reason);
        void onOrder(OrderState order);
        void onRaw(String raw);
    }

    private static final long RECONNECT_DELAY_MS = 2000;
    private static final String[] SOCKET_PATHS = {"", "/ws", "/customer-display"};

    private final String host;
    private final int port;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final OkHttpClient http = new OkHttpClient.Builder()
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(20, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build();

    private volatile boolean running;
    private volatile int generation;
    private volatile int disconnectedGeneration = -1;
    private volatile WebSocket socket;
    private int pathIndex;

    public TechProClient(String host, int port, Listener listener) {
        this.host = host;
        this.port = port;
        this.listener = listener;
    }

    public void start() {
        if (running) return;
        running = true;
        connect();
    }

    public void stop() {
        running = false;
        generation++;
        main.removeCallbacksAndMessages(null);
        WebSocket current = socket;
        socket = null;
        if (current != null) current.cancel();
        http.connectionPool().evictAll();
    }

    private void connect() {
        if (!running) return;
        final int currentGeneration = ++generation;
        disconnectedGeneration = -1;
        String path = SOCKET_PATHS[pathIndex];
        String url = "ws://" + host + ":" + port + path;
        Request request = new Request.Builder().url(url).build();
        socket = http.newWebSocket(request, new WebSocketListener() {
            @Override public void onOpen(WebSocket webSocket, Response response) {
                if (!isCurrent(currentGeneration)) {
                    webSocket.cancel();
                    return;
                }
                socket = webSocket;
                main.post(() -> {
                    if (isCurrent(currentGeneration)) listener.onConnected();
                });
            }

            @Override public void onMessage(WebSocket webSocket, String text) {
                if (!isCurrent(currentGeneration)) return;
                handleMessage(webSocket, text, currentGeneration);
            }

            @Override public void onMessage(WebSocket webSocket, ByteString bytes) {
                if (!isCurrent(currentGeneration)) return;
                handleMessage(webSocket, bytes.utf8(), currentGeneration);
            }

            @Override public void onClosing(WebSocket webSocket, int code, String reason) {
                webSocket.close(code, reason);
            }

            @Override public void onClosed(WebSocket webSocket, int code, String reason) {
                if (!isCurrent(currentGeneration)) return;
                scheduleReconnect(currentGeneration, "WebSocket closed " + code + ": " + reason);
            }

            @Override public void onFailure(WebSocket webSocket, Throwable error, Response response) {
                if (!isCurrent(currentGeneration)) return;
                int responseCode = response == null ? 0 : response.code();
                if (responseCode == 400 || responseCode == 404) {
                    pathIndex = (pathIndex + 1) % SOCKET_PATHS.length;
                }
                String reason = error.getClass().getSimpleName() + ": " + String.valueOf(error.getMessage());
                if (responseCode > 0) reason = "HTTP " + responseCode + " — " + reason;
                scheduleReconnect(currentGeneration, reason);
            }
        });
    }

    private boolean isCurrent(int value) {
        return running && value == generation;
    }

    private void scheduleReconnect(int currentGeneration, String reason) {
        if (!isCurrent(currentGeneration)) return;
        if (disconnectedGeneration != currentGeneration) {
            disconnectedGeneration = currentGeneration;
            main.post(() -> {
                if (isCurrent(currentGeneration)) listener.onDisconnected(reason);
            });
        }
        main.postDelayed(() -> {
            if (isCurrent(currentGeneration)) connect();
        }, RECONNECT_DELAY_MS);
    }

    private void handleMessage(WebSocket webSocket, String raw, int currentGeneration) {
        if (raw == null) return;
        String trimmed = raw.trim();
        if (trimmed.equalsIgnoreCase("heartbeat") || trimmed.equalsIgnoreCase("ping")) {
            webSocket.send(trimmed.startsWith("{") ? "{\"type\":\"pong\"}" : "pong");
            return;
        }
        JSONObject envelope = objectFrom(trimmed);
        if (envelope != null && "heartbeat".equalsIgnoreCase(firstText(envelope, "type", "messageType", "event"))) {
            webSocket.send("{\"type\":\"pong\"}");
        }
        OrderState order = parseOrder(trimmed);
        main.post(() -> {
            if (!isCurrent(currentGeneration)) return;
            listener.onRaw(raw);
            if (order != null) listener.onOrder(order);
        });
    }

    private OrderState parseOrder(String raw) {
        try {
            JSONObject envelope = objectFrom(raw);
            if (envelope == null) return null;
            String type = firstText(envelope, "type", "messageType", "event", "action");
            String normalizedType = type == null ? "" : type.toLowerCase(Locale.US);

            Object payloadValue = firstValue(envelope, "payload", "body", "message");
            JSONArray directLines = payloadValue instanceof JSONArray ? (JSONArray) payloadValue : null;
            JSONObject data = objectFrom(payloadValue);
            if (data == null) data = envelope;
            data = unwrap(data, "snapshot", "order", "data", "cart");

            JSONArray lines = directLines != null ? directLines : firstArray(
                    data,
                    "lines", "items", "orderLines", "cartItems", "products"
            );
            if (lines == null && data != envelope) {
                lines = firstArray(envelope, "lines", "items", "orderLines", "cartItems", "products");
            }

            boolean orderMessage = normalizedType.isEmpty()
                    || normalizedType.contains("order")
                    || normalizedType.contains("snapshot")
                    || normalizedType.contains("thankyou")
                    || normalizedType.contains("clearcustomerdisplay");
            if (lines == null && !orderMessage) return null;

            OrderState result = new OrderState();
            if (lines != null) {
                for (int index = 0; index < lines.length(); index++) {
                    JSONObject source = objectFrom(lines.opt(index));
                    if (source == null) continue;
                    OrderState.Item item = new OrderState.Item();
                    item.name = firstText(
                            source,
                            "itemName", "name", "productName", "displayNameAr", "itemNameAr",
                            "nameAr", "displayNameEn", "itemNameEn", "nameEn"
                    );
                    if (item.name == null || item.name.trim().isEmpty()) item.name = "صنف";
                    item.qty = firstDouble(source, 1, "quantity", "qty", "count");
                    item.unitPrice = firstDouble(
                            source,
                            0,
                            "unitPrice", "price", "unitPriceInclVat", "itemPriceAfterDiscountWithTax"
                    );
                    item.lineTotal = firstDouble(
                            source,
                            Double.NaN,
                            "lineTotal", "total", "amount", "totalAfterDiscountInclVat"
                    );
                    result.items.add(item);
                }
            }

            result.subtotal = firstDouble(
                    data,
                    sumItems(result),
                    "subtotal", "subTotal", "totalBeforeDiscountInclVat", "totalBeforeDiscount"
            );
            result.tax = firstDouble(data, 0, "tax", "totalTax", "taxAmount", "vatTotalAfterDiscount");
            result.discount = firstDouble(
                    data,
                    0,
                    "discount", "discountTotal", "totalAllDiscounts", "itemsDiscount"
            );
            result.total = firstDouble(
                    data,
                    sumItems(result),
                    "total", "grandTotal", "invoiceTotalAfterTax", "totalAfterDiscountInclVat",
                    "totalAfterDiscountWithVat", "total_including_tax"
            );
            String status = firstText(data, "status", "viewState", "state");
            result.completed = normalizedType.contains("thankyou")
                    || data.optBoolean("completed", false)
                    || "completed".equalsIgnoreCase(status)
                    || "thankYou".equalsIgnoreCase(status);
            return result;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static double sumItems(OrderState order) {
        double total = 0;
        for (OrderState.Item item : order.items) total += item.total();
        return total;
    }

    private static JSONObject unwrap(JSONObject object, String... keys) {
        JSONObject current = object;
        for (int pass = 0; pass < 4; pass++) {
            JSONObject nested = null;
            for (String key : keys) {
                nested = objectFrom(current.opt(key));
                if (nested != null) break;
            }
            if (nested == null || nested == current) break;
            current = nested;
        }
        return current;
    }

    private static JSONObject objectFrom(Object value) {
        if (value instanceof JSONObject) return (JSONObject) value;
        if (!(value instanceof String)) return null;
        String text = ((String) value).trim();
        if (text.isEmpty()) return null;
        try {
            return new JSONObject(text);
        } catch (Exception ignored) {
            try {
                String decoded = new JSONArray("[" + text + "]").getString(0);
                return new JSONObject(decoded);
            } catch (Exception ignoredAgain) {
                return null;
            }
        }
    }

    private static Object firstValue(JSONObject object, String... keys) {
        for (String key : keys) {
            Object value = object.opt(key);
            if (value != null && value != JSONObject.NULL) return value;
        }
        return null;
    }

    private static JSONArray firstArray(JSONObject object, String... keys) {
        for (String key : keys) {
            JSONArray value = object.optJSONArray(key);
            if (value != null) return value;
            Object raw = object.opt(key);
            if (raw instanceof String) {
                try { return new JSONArray((String) raw); }
                catch (Exception ignored) { }
            }
        }
        return null;
    }

    private static String firstText(JSONObject object, String... keys) {
        for (String key : keys) {
            String value = object.optString(key, "").trim();
            if (!value.isEmpty() && !"null".equalsIgnoreCase(value)) return value;
        }
        return null;
    }

    private static double firstDouble(JSONObject object, double fallback, String... keys) {
        for (String key : keys) {
            Object value = object.opt(key);
            if (value == null || value == JSONObject.NULL) continue;
            if (value instanceof Number) return ((Number) value).doubleValue();
            try { return Double.parseDouble(String.valueOf(value).replace(",", "").trim()); }
            catch (Exception ignored) { }
        }
        return fallback;
    }
}

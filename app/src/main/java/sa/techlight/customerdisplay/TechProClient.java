package sa.techlight.customerdisplay;

import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Iterator;
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
        void onDiagnostic(String stage, String detail);
    }

    private static final long RECONNECT_DELAY_MS = 2000;

    private final String host;
    private final int port;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final OkHttpClient http = new OkHttpClient.Builder()
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(10, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build();

    private volatile boolean running;
    private volatile int generation;
    private volatile int disconnectedGeneration = -1;
    private volatile WebSocket socket;

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
        String url = "ws://" + host + ":" + port;
        diagnostic(currentGeneration, "CONNECTING", url);
        Request request = new Request.Builder().url(url).build();
        socket = http.newWebSocket(request, new WebSocketListener() {
            @Override public void onOpen(WebSocket webSocket, Response response) {
                if (!isCurrent(currentGeneration)) {
                    webSocket.cancel();
                    return;
                }
                socket = webSocket;
                main.post(() -> {
                    if (isCurrent(currentGeneration)) {
                        listener.onDiagnostic("WEBSOCKET_OPEN", url);
                        listener.onConnected();
                    }
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
        diagnostic(currentGeneration, "DISCONNECTED", reason);
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
        OrderState order = parseOrderMessage(trimmed);
        String type = envelope == null ? "RAW" : firstText(envelope, "type", "messageType", "event", "action");
        String diagnosticType = type == null ? "SNAPSHOT" : type;
        int itemCount = order == null || order.items == null ? -1 : order.items.size();
        main.post(() -> {
            if (!isCurrent(currentGeneration)) return;
            listener.onDiagnostic(
                    order == null ? "MESSAGE_UNPARSED" : "MESSAGE_PARSED",
                    diagnosticType + (itemCount >= 0 ? " — items=" + itemCount : "")
            );
            listener.onRaw(raw);
            if (order != null) listener.onOrder(order);
        });
    }

    static OrderState parseOrderMessage(String raw) {
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

            JSONArray lines = directLines != null ? directLines : findItemArray(data);
            if ((lines == null || lines.length() == 0) && data != envelope) {
                JSONArray envelopeLines = findItemArray(envelope);
                if (envelopeLines != null && envelopeLines.length() > 0) lines = envelopeLines;
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
                    item.name = firstTextDeep(
                            source,
                            "itemName", "name", "productName", "displayNameAr", "itemNameAr",
                            "nameAr", "displayNameEn", "itemNameEn", "nameEn", "titleAr", "titleEn", "title",
                            "item_name", "product_name", "name_ar", "name_en"
                    );
                    if (item.name == null || item.name.trim().isEmpty()) item.name = "صنف";
                    item.qty = firstDoubleDeep(source, 1,
                            "quantity", "qty", "count", "itemQuantity", "amountQty", "item_qty", "item_quantity");
                    item.unitPrice = firstDoubleDeep(
                            source,
                            0,
                            "unitPrice", "price", "unitPriceInclVat", "itemPriceAfterDiscountWithTax", "salePrice",
                            "unit_price", "sale_price"
                    );
                    item.lineTotal = firstDoubleDeep(
                            source,
                            Double.NaN,
                            "lineTotal", "total", "amount", "totalAfterDiscountInclVat", "rowTotal",
                            "line_total", "row_total"
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

    private void diagnostic(int currentGeneration, String stage, String detail) {
        main.post(() -> {
            if (isCurrent(currentGeneration)) listener.onDiagnostic(stage, detail);
        });
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

    private static JSONArray findItemArray(JSONObject object) {
        if (object == null) return null;
        String[] preferredKeys = {
                "items", "lines", "orderLines", "cartItems", "products",
                "orderItems", "invoiceItems", "rows", "order_items", "invoice_items", "cart_items"
        };
        JSONArray emptyPreferred = null;
        for (String key : preferredKeys) {
            JSONArray candidate = arrayFrom(object.opt(key));
            if (candidate == null) continue;
            if (candidate.length() > 0) return candidate;
            if (emptyPreferred == null) emptyPreferred = candidate;
        }
        JSONArray nested = findItemArrayRecursive(object, 0);
        return nested != null ? nested : emptyPreferred;
    }

    private static JSONArray findItemArrayRecursive(Object value, int depth) {
        if (value == null || value == JSONObject.NULL || depth > 7) return null;
        JSONObject object = objectFrom(value);
        if (object != null) {
            String[] preferredKeys = {
                    "items", "lines", "orderLines", "cartItems", "products",
                    "orderItems", "invoiceItems", "rows", "order_items", "invoice_items", "cart_items"
            };
            for (String key : preferredKeys) {
                JSONArray candidate = arrayFrom(object.opt(key));
                if (candidate != null && candidate.length() > 0) return candidate;
            }
            Iterator<String> keys = object.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                if ("settings".equalsIgnoreCase(key)
                        || "idleContent".equalsIgnoreCase(key)
                        || "paymentQr".equalsIgnoreCase(key)
                        || "thankYou".equalsIgnoreCase(key)) continue;
                Object nestedValue = object.opt(key);
                JSONArray nested = findItemArrayRecursive(nestedValue, depth + 1);
                if (nested != null && nested.length() > 0) return nested;
            }
            return null;
        }
        if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            if (looksLikeItemArray(array)) return array;
            for (int index = 0; index < array.length(); index++) {
                JSONArray nested = findItemArrayRecursive(array.opt(index), depth + 1);
                if (nested != null && nested.length() > 0) return nested;
            }
        }
        return null;
    }

    private static JSONArray arrayFrom(Object value) {
        if (value instanceof JSONArray) return (JSONArray) value;
        JSONObject object = objectFrom(value);
        if (object != null) {
            JSONArray result = new JSONArray();
            if (looksLikeItemObject(object)) {
                result.put(object);
                return result;
            }
            Iterator<String> keys = object.keys();
            while (keys.hasNext()) {
                Object entry = object.opt(keys.next());
                if (objectFrom(entry) != null) result.put(entry);
            }
            return result.length() == 0 ? null : result;
        }
        if (value instanceof String) {
            String text = ((String) value).trim();
            if (text.isEmpty()) return null;
            try { return new JSONArray(text); }
            catch (Exception ignored) { return null; }
        }
        return null;
    }

    private static boolean looksLikeItemArray(JSONArray array) {
        if (array == null || array.length() == 0) return false;
        int checked = Math.min(array.length(), 4);
        for (int index = 0; index < checked; index++) {
            JSONObject item = objectFrom(array.opt(index));
            if (item != null && looksLikeItemObject(item)) return true;
        }
        return false;
    }

    private static boolean looksLikeItemObject(JSONObject object) {
        return firstTextDeep(object,
                "itemName", "name", "productName", "nameAr", "nameEn", "title", "titleAr", "titleEn",
                "item_name", "product_name", "name_ar", "name_en") != null
                || hasAnyKey(object,
                "qty", "quantity", "unitPrice", "lineTotal", "salePrice",
                "item_qty", "unit_price", "line_total", "sale_price");
    }

    private static boolean hasAnyKey(JSONObject object, String... keys) {
        for (String key : keys) if (object.has(key) && !object.isNull(key)) return true;
        return false;
    }

    private static String firstText(JSONObject object, String... keys) {
        for (String key : keys) {
            Object raw = object.opt(key);
            if (raw instanceof JSONObject) {
                String localized = firstText((JSONObject) raw, "ar", "ar-SA", "arabic", "en", "en-US", "english", "value");
                if (localized != null) return localized;
                continue;
            }
            if (raw == null || raw == JSONObject.NULL) continue;
            String value = String.valueOf(raw).trim();
            if (!value.isEmpty() && !"null".equalsIgnoreCase(value)) return value;
        }
        return null;
    }

    private static String firstTextDeep(JSONObject object, String... keys) {
        return firstTextDeep(object, 0, keys);
    }

    private static String firstTextDeep(JSONObject object, int depth, String... keys) {
        if (object == null || depth > 3) return null;
        String direct = firstText(object, keys);
        if (direct != null) return direct;
        String[] nestedKeys = {"product", "item", "productData", "menuItem", "productInfo", "details", "data"};
        for (String nestedKey : nestedKeys) {
            JSONObject nested = objectFrom(object.opt(nestedKey));
            String value = firstTextDeep(nested, depth + 1, keys);
            if (value != null) return value;
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

    private static double firstDoubleDeep(JSONObject object, double fallback, String... keys) {
        if (object == null) return fallback;
        double direct = firstDouble(object, Double.NaN, keys);
        if (!Double.isNaN(direct)) return direct;
        String[] nestedKeys = {"product", "item", "productData", "menuItem", "productInfo", "details", "data"};
        for (String nestedKey : nestedKeys) {
            JSONObject nested = objectFrom(object.opt(nestedKey));
            double value = firstDouble(nested, Double.NaN, keys);
            if (!Double.isNaN(value)) return value;
        }
        return fallback;
    }
}

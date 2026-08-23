package sa.techlight.customerdisplay;

import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

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
    private OrderState lastOrder;

    public TechProClient(String host, int port, Listener listener) {
        this.host = host;
        this.port = port;
        this.listener = listener;
    }

    public void start() {
        if (running) return;
        lastOrder = null;
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
        OrderState order = mergeOrderPatch(parseOrderMessage(trimmed));
        String type = envelope == null ? "RAW" : firstText(envelope, "type", "messageType", "event", "action");
        String diagnosticType = type == null ? "SNAPSHOT" : type;
        int itemCount = order == null || order.items == null ? -1 : order.items.size();
        String parseDetail = diagnosticType + (itemCount >= 0 ? " — items=" + itemCount : "")
                + (order == null ? "" : " — itemsField=" + order.itemsIncluded + " — total=" + order.total);
        main.post(() -> {
            if (!isCurrent(currentGeneration)) return;
            listener.onDiagnostic(
                    order == null ? "MESSAGE_UNPARSED" : "MESSAGE_PARSED",
                    parseDetail
            );
            listener.onRaw(raw);
            if (order != null) listener.onOrder(order);
        });
    }

    static OrderState parseOrderMessage(String raw) {
        try {
            return parseOrderMessageOrThrow(raw);
        } catch (Exception ignored) {
            return null;
        }
    }

    static OrderState parseOrderMessageOrThrow(String raw) throws Exception {
        JSONObject envelope = objectFrom(raw);
        if (envelope == null) return null;
        String type = firstText(envelope, "type", "messageType", "event", "action");
        String normalizedType = type == null ? "" : type.toLowerCase(Locale.US);

        Object payloadValue = structuredFrom(firstValue(envelope, "payload", "body", "message"));
        JSONArray directLines = payloadValue instanceof JSONArray ? (JSONArray) payloadValue : null;
        JSONObject data = objectFrom(payloadValue);
        if (data == null) data = envelope;
        JSONObject unwrapped = unwrap(data, "snapshot", "order", "data", "cart", "invoice");

        JSONArray lines = directLines != null ? directLines : findItemArray(unwrapped);
        if (lines == null && unwrapped != data) lines = findItemArray(data);
        if (lines == null && data != envelope) {
            JSONArray envelopeLines = findItemArray(envelope);
            if (envelopeLines != null) lines = envelopeLines;
        }

        boolean orderMessage = normalizedType.isEmpty()
                || normalizedType.contains("order")
                || normalizedType.contains("snapshot")
                || normalizedType.contains("thankyou")
                || normalizedType.contains("clearcustomerdisplay");
        boolean orderAmountsPresent = hasAnyKey(unwrapped,
                "subtotal", "tax", "discount", "total", "grandTotal", "invTotal", "netAmount")
                || (unwrapped != data && hasAnyKey(data,
                "subtotal", "tax", "discount", "total", "grandTotal", "invTotal", "netAmount"));
        if (lines == null && !orderMessage && !orderAmountsPresent) return null;

        OrderState result = new OrderState();
        result.itemsIncluded = lines != null;
        result.clearRequested = normalizedType.contains("clearorder")
                || normalizedType.contains("clearcustomerdisplay");
        if (lines != null) {
            for (int index = 0; index < lines.length(); index++) {
                JSONObject source = objectFrom(lines.opt(index));
                OrderState.Item item = source == null
                        ? itemFromArray(lines.opt(index))
                        : itemFromObject(source);
                if (item != null) result.items.add(item);
            }
        }

        JSONObject[] valueSources = unwrapped == data
                ? (data == envelope ? new JSONObject[]{unwrapped} : new JSONObject[]{unwrapped, envelope})
                : (data == envelope ? new JSONObject[]{unwrapped, data} : new JSONObject[]{unwrapped, data, envelope});
        String[] subtotalKeys = {"subtotal", "subTotal", "totalBeforeDiscountInclVat", "totalBeforeDiscount", "netAmount"};
        String[] taxKeys = {"tax", "totalTax", "taxAmount", "vatTotalAfterDiscount", "vatAmount"};
        String[] discountKeys = {"discount", "discountTotal", "totalAllDiscounts", "itemsDiscount", "discountAmount"};
        String[] totalKeys = {"total", "grandTotal", "invoiceTotalAfterTax", "totalAfterDiscountInclVat",
                "totalAfterDiscountWithVat", "total_including_tax", "invTotal"};
        result.subtotalIncluded = hasAnyKeyInSources(valueSources, subtotalKeys);
        result.subtotal = firstDoubleInSources(valueSources, sumItems(result), subtotalKeys);
        result.taxIncluded = hasAnyKeyInSources(valueSources, taxKeys);
        result.tax = firstDoubleInSources(valueSources, 0, taxKeys);
        result.discountIncluded = hasAnyKeyInSources(valueSources, discountKeys);
        result.discount = firstDoubleInSources(valueSources, 0, discountKeys);
        result.totalIncluded = hasAnyKeyInSources(valueSources, totalKeys);
        result.total = firstDoubleInSources(valueSources, sumItems(result), totalKeys);
        String status = firstTextInSources(valueSources, "status", "viewState", "state");
        result.completed = normalizedType.contains("thankyou")
                || firstBoolean(unwrapped, false, "completed")
                || "completed".equalsIgnoreCase(status)
                || "thankYou".equalsIgnoreCase(status);
        if ("idle".equalsIgnoreCase(status) && result.itemsIncluded && result.items.isEmpty()) {
            result.clearRequested = true;
        }
        return result;
    }

    private static OrderState.Item itemFromObject(JSONObject source) {
        OrderState.Item item = new OrderState.Item();
        item.name = firstTextDeep(
                source,
                "itemName", "name", "productName", "displayNameAr", "itemNameAr",
                "nameAr", "displayNameEn", "itemNameEn", "nameEn", "titleAr", "titleEn", "title",
                "item_name", "product_name", "name_ar", "name_en", "description"
        );
        boolean hasQuantity = hasAnyKeyDeep(source,
                "quantity", "qty", "count", "itemQuantity", "amountQty", "item_qty", "item_quantity");
        boolean hasPrice = hasAnyKeyDeep(source,
                "unitPrice", "price", "unitPriceInclVat", "itemPriceAfterDiscountWithTax", "salePrice",
                "unit_price", "sale_price", "finalPrice");
        boolean hasLineTotal = hasAnyKeyDeep(source,
                "lineTotal", "total", "amount", "totalAfterDiscountInclVat", "rowTotal",
                "line_total", "row_total");
        if ((item.name == null || item.name.trim().isEmpty()) && !hasQuantity && !hasPrice && !hasLineTotal) {
            return null;
        }
        if (item.name == null || item.name.trim().isEmpty()) item.name = "صنف";
        item.qty = firstDoubleDeep(source, 1,
                "quantity", "qty", "count", "itemQuantity", "amountQty", "item_qty", "item_quantity");
        item.unitPrice = firstDoubleDeep(
                source,
                0,
                "unitPrice", "price", "unitPriceInclVat", "itemPriceAfterDiscountWithTax", "salePrice",
                "unit_price", "sale_price", "finalPrice"
        );
        item.lineTotal = firstDoubleDeep(
                source,
                Double.NaN,
                "lineTotal", "total", "amount", "totalAfterDiscountInclVat", "rowTotal",
                "line_total", "row_total"
        );
        return item;
    }

    private static OrderState.Item itemFromArray(Object value) {
        Object structured = structuredFrom(value);
        if (!(structured instanceof JSONArray)) return null;
        JSONArray row = (JSONArray) structured;
        if (row.length() < 3) return null;
        int nameIndex = -1;
        for (int index = 0; index < row.length(); index++) {
            Object cell = row.opt(index);
            if (!(cell instanceof String)) continue;
            String text = ((String) cell).trim();
            if (!text.isEmpty() && Double.isNaN(numberOrNaN(text))) {
                nameIndex = index;
                break;
            }
        }
        if (nameIndex < 0) return null;
        OrderState.Item item = new OrderState.Item();
        item.name = String.valueOf(row.opt(nameIndex)).trim();
        item.qty = numericCell(row, nameIndex + 1, 1);
        item.unitPrice = numericCell(row, nameIndex + 2, 0);
        item.lineTotal = numericCell(row, nameIndex + 3, Double.NaN);
        return item;
    }

    private static double numericCell(JSONArray row, int index, double fallback) {
        if (index < 0 || index >= row.length()) return fallback;
        double value = numberOrNaN(row.opt(index));
        return Double.isNaN(value) ? fallback : value;
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
                nested = objectFrom(valueForKey(current, key));
                if (nested != null) break;
            }
            if (nested == null || nested == current) break;
            current = nested;
        }
        return current;
    }

    private static JSONObject objectFrom(Object value) {
        Object structured = structuredFrom(value);
        return structured instanceof JSONObject ? (JSONObject) structured : null;
    }

    private static Object structuredFrom(Object value) {
        Object current = value;
        for (int pass = 0; pass < 4; pass++) {
            if (current instanceof JSONObject || current instanceof JSONArray) return current;
            if (!(current instanceof String)) return null;
            String text = ((String) current).trim();
            if (text.isEmpty()) return null;
            try {
                Object parsed = new JSONTokener(text).nextValue();
                if (parsed instanceof String && text.equals(parsed)) return null;
                current = parsed;
            } catch (Exception ignored) {
                try {
                    String decoded = new JSONArray("[" + text + "]").getString(0);
                    if (decoded.equals(text)) return null;
                    current = decoded;
                } catch (Exception ignoredAgain) {
                    return null;
                }
            }
        }
        return current instanceof JSONObject || current instanceof JSONArray ? current : null;
    }

    private static Object firstValue(JSONObject object, String... keys) {
        for (String key : keys) {
            Object value = valueForKey(object, key);
            if (value != null && value != JSONObject.NULL) return value;
        }
        return null;
    }

    private static JSONArray findItemArray(JSONObject object) {
        if (object == null) return null;
        String[] preferredKeys = {
                "items", "lines", "orderLines", "cartItems", "products",
                "orderItems", "invoiceItems", "rows", "order_items", "invoice_items", "cart_items",
                "itemList", "Itemlist", "pos_dt_Collection", "invoiceDetails", "orderDetails", "details"
        };
        JSONArray emptyPreferred = null;
        for (String key : preferredKeys) {
            if (!hasKey(object, key)) continue;
            JSONArray candidate = arrayFrom(valueForKey(object, key));
            if (candidate == null) continue;
            if (candidate.length() > 0) return candidate;
            if (emptyPreferred == null) emptyPreferred = candidate;
        }
        JSONArray nested = findItemArrayRecursive(object, 0);
        return nested != null ? nested : emptyPreferred;
    }

    private static JSONArray findItemArrayRecursive(Object value, int depth) {
        if (value == null || value == JSONObject.NULL || depth > 7) return null;
        Object structured = structuredFrom(value);
        JSONObject object = structured instanceof JSONObject ? (JSONObject) structured : null;
        if (object != null) {
            String[] preferredKeys = {
                    "items", "lines", "orderLines", "cartItems", "products",
                    "orderItems", "invoiceItems", "rows", "order_items", "invoice_items", "cart_items",
                    "itemList", "Itemlist", "pos_dt_Collection", "invoiceDetails", "orderDetails", "details"
            };
            JSONArray emptyPreferred = null;
            for (String key : preferredKeys) {
                if (!hasKey(object, key)) continue;
                JSONArray candidate = arrayFrom(valueForKey(object, key));
                if (candidate == null) continue;
                if (candidate.length() > 0) return candidate;
                if (emptyPreferred == null) emptyPreferred = candidate;
            }
            if (looksLikeItemObject(object)) {
                JSONArray single = new JSONArray();
                single.put(object);
                return single;
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
            return emptyPreferred;
        }
        if (structured instanceof JSONArray) {
            JSONArray array = (JSONArray) structured;
            if (looksLikeItemArray(array)) return array;
            for (int index = 0; index < array.length(); index++) {
                JSONArray nested = findItemArrayRecursive(array.opt(index), depth + 1);
                if (nested != null && nested.length() > 0) return nested;
            }
        }
        return null;
    }

    private static JSONArray arrayFrom(Object value) {
        Object structured = structuredFrom(value);
        if (structured instanceof JSONArray) return (JSONArray) structured;
        JSONObject object = objectFrom(structured);
        if (object != null) {
            JSONArray result = new JSONArray();
            if (looksLikeItemObject(object)) {
                result.put(object);
                return result;
            }
            Iterator<String> keys = object.keys();
            while (keys.hasNext()) {
                Object entry = object.opt(keys.next());
                Object parsed = structuredFrom(entry);
                if (parsed instanceof JSONObject || parsed instanceof JSONArray) result.put(parsed);
            }
            return result.length() == 0 ? null : result;
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
        if (object == null) return false;
        for (String key : keys) {
            Object value = valueForKey(object, key);
            if (value != null && value != JSONObject.NULL) return true;
        }
        return false;
    }

    private static boolean hasAnyKeyInSources(JSONObject[] sources, String... keys) {
        for (JSONObject source : sources) if (hasAnyKey(source, keys)) return true;
        return false;
    }

    private static boolean hasAnyKeyDeep(JSONObject object, String... keys) {
        return hasAnyKeyDeep(object, 0, keys);
    }

    private static boolean hasAnyKeyDeep(JSONObject object, int depth, String... keys) {
        if (object == null || depth > 4) return false;
        if (hasAnyKey(object, keys)) return true;
        String[] nestedKeys = {"product", "item", "productData", "menuItem", "productInfo", "details", "data"};
        for (String nestedKey : nestedKeys) {
            JSONObject nested = objectFrom(valueForKey(object, nestedKey));
            if (hasAnyKeyDeep(nested, depth + 1, keys)) return true;
        }
        return false;
    }

    private static String firstText(JSONObject object, String... keys) {
        for (String key : keys) {
            Object raw = valueForKey(object, key);
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

    private static String firstTextInSources(JSONObject[] sources, String... keys) {
        for (JSONObject source : sources) {
            String value = firstText(source, keys);
            if (value != null) return value;
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
            JSONObject nested = objectFrom(valueForKey(object, nestedKey));
            String value = firstTextDeep(nested, depth + 1, keys);
            if (value != null) return value;
        }
        return null;
    }

    private static double firstDouble(JSONObject object, double fallback, String... keys) {
        if (object == null) return fallback;
        for (String key : keys) {
            Object value = valueForKey(object, key);
            if (value == null || value == JSONObject.NULL) continue;
            double parsed = numberOrNaN(value);
            if (!Double.isNaN(parsed)) return parsed;
        }
        return fallback;
    }

    private static double firstDoubleInSources(JSONObject[] sources, double fallback, String... keys) {
        for (JSONObject source : sources) {
            double value = firstDouble(source, Double.NaN, keys);
            if (!Double.isNaN(value)) return value;
        }
        return fallback;
    }

    private static double firstDoubleDeep(JSONObject object, double fallback, String... keys) {
        if (object == null) return fallback;
        double direct = firstDouble(object, Double.NaN, keys);
        if (!Double.isNaN(direct)) return direct;
        String[] nestedKeys = {"product", "item", "productData", "menuItem", "productInfo", "details", "data"};
        for (String nestedKey : nestedKeys) {
            JSONObject nested = objectFrom(valueForKey(object, nestedKey));
            if (nested == null) continue;
            double value = firstDouble(nested, Double.NaN, keys);
            if (!Double.isNaN(value)) return value;
        }
        return fallback;
    }

    private static boolean firstBoolean(JSONObject object, boolean fallback, String... keys) {
        Object value = firstValue(object, keys);
        if (value instanceof Boolean) return (Boolean) value;
        if (value instanceof Number) return ((Number) value).intValue() != 0;
        if (value == null) return fallback;
        String text = String.valueOf(value).trim();
        if ("true".equalsIgnoreCase(text) || "1".equals(text)) return true;
        if ("false".equalsIgnoreCase(text) || "0".equals(text)) return false;
        return fallback;
    }

    private static Object valueForKey(JSONObject object, String requested) {
        if (object == null || requested == null) return null;
        if (object.has(requested)) return object.opt(requested);
        String canonicalRequested = canonicalKey(requested);
        Iterator<String> keys = object.keys();
        while (keys.hasNext()) {
            String actual = keys.next();
            if (canonicalRequested.equals(canonicalKey(actual))) return object.opt(actual);
        }
        return null;
    }

    private static boolean hasKey(JSONObject object, String requested) {
        if (object == null || requested == null) return false;
        if (object.has(requested)) return true;
        String canonicalRequested = canonicalKey(requested);
        Iterator<String> keys = object.keys();
        while (keys.hasNext()) {
            if (canonicalRequested.equals(canonicalKey(keys.next()))) return true;
        }
        return false;
    }

    private static String canonicalKey(String key) {
        return key.replace("_", "")
                .replace("-", "")
                .replace(" ", "")
                .toLowerCase(Locale.US);
    }

    private static double numberOrNaN(Object value) {
        if (value instanceof Number) return ((Number) value).doubleValue();
        if (value == null || value == JSONObject.NULL) return Double.NaN;
        String text = String.valueOf(value).trim()
                .replace(",", "")
                .replace("SAR", "")
                .replace("sar", "")
                .replace("ر.س", "")
                .trim();
        try { return Double.parseDouble(text); }
        catch (Exception ignored) {
            java.util.regex.Matcher matcher = java.util.regex.Pattern
                    .compile("-?\\d+(?:\\.\\d+)?")
                    .matcher(text);
            if (!matcher.find()) return Double.NaN;
            try { return Double.parseDouble(matcher.group()); }
            catch (Exception ignoredAgain) { return Double.NaN; }
        }
    }

    private synchronized OrderState mergeOrderPatch(OrderState incoming) {
        if (incoming == null) return null;
        boolean suspiciousEmptySnapshot = incoming.itemsIncluded
                && incoming.items.isEmpty()
                && incoming.total > 0.0001
                && !incoming.clearRequested
                && lastOrder != null
                && !lastOrder.items.isEmpty();
        if (suspiciousEmptySnapshot) {
            incoming.items.addAll(lastOrder.items);
        }
        if (!incoming.clearRequested && !incoming.itemsIncluded && lastOrder != null) {
            incoming.items.addAll(lastOrder.items);
        }
        if (lastOrder != null && !incoming.clearRequested) {
            if (!incoming.subtotalIncluded && !incoming.itemsIncluded) incoming.subtotal = lastOrder.subtotal;
            if (!incoming.taxIncluded) incoming.tax = lastOrder.tax;
            if (!incoming.discountIncluded) incoming.discount = lastOrder.discount;
            if (!incoming.totalIncluded && !incoming.itemsIncluded) incoming.total = lastOrder.total;
        }
        if (incoming.itemsIncluded && !incoming.totalIncluded) incoming.total = sumItems(incoming);
        if (incoming.itemsIncluded && !incoming.subtotalIncluded) incoming.subtotal = sumItems(incoming);
        lastOrder = incoming;
        return incoming;
    }
}

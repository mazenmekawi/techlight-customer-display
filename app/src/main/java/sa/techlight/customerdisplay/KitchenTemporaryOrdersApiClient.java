package sa.techlight.customerdisplay;

import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.IOException;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Read-only cloud resolver for TechPro temporary orders.
 *
 * The local customer-display websocket is excellent for live cart lines but some TechPro builds do not
 * include the cashier-facing temporary-order/invoice number in that stream. This client asks the same
 * authenticated TechPro account API used by the POS for its saved temporary orders, then matches the
 * cloud record back to the local cart by item ids and quantities.
 */
public final class KitchenTemporaryOrdersApiClient {
    static final String API = "https://posapifornewapp.techlight.sa/api/";
    private static final String[] LIST_PATHS = {
            "TemporaryOrders?Page=1&PageSize=300",
            "TemporaryOrders/List"
    };
    private static final int MAX_RETRIES = 4;

    public interface Listener {
        void onResolved(KitchenOrder order, String detail);
        void onNotFound(String detail);
        void onUnauthorized();
    }

    static final class Candidate {
        long id;
        String number = "";
        String code = "";
        String table = "";
        String orderType = "";
        long orderTypeId;
        String note = "";
        String posCode = "";
        long orderDate;
        final ArrayList<ItemKey> items = new ArrayList<>();

        String usableNumber() {
            String n = cleanNumber(number);
            if (!n.isEmpty()) return n;
            String c = cleanNumber(code);
            return isMostlyNumeric(c) ? c : "";
        }
    }

    static final class ItemKey {
        long itemId;
        long unitId;
        double qty;
        String name = "";
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final OkHttpClient http = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(18, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build();
    private final String token;
    private volatile boolean closed;
    private volatile Map<Long, String> orderTypeNames;

    public KitchenTemporaryOrdersApiClient(String token) {
        this.token = stripBearer(token);
    }

    public void resolveSavedOrder(KitchenOrder draft, String posCode, List<String> knownNumbers, Listener listener) {
        if (listener == null) return;
        if (draft == null || draft.items.isEmpty()) {
            main.post(() -> listener.onNotFound("No local cart lines to match"));
            return;
        }
        final KitchenOrder local = draft.copy();
        final String point = clean(posCode);
        final HashSet<String> known = new HashSet<>();
        if (knownNumbers != null) for (String value : knownNumbers) {
            String number = cleanNumber(value);
            if (!number.isEmpty()) known.add(number);
        }

        worker.execute(() -> {
            boolean unauthorized = false;
            String lastDetail = "Temporary order not visible in cloud yet";
            for (int attempt = 0; attempt < MAX_RETRIES && !closed; attempt++) {
                if (attempt > 0) {
                    try { Thread.sleep(attempt == 1 ? 700L : attempt == 2 ? 1200L : 1900L); }
                    catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); return; }
                }
                try {
                    ArrayList<Candidate> candidates = new ArrayList<>();
                    StringBuilder sources = new StringBuilder();
                    for (String path : LIST_PATHS) {
                        if (closed) return;
                        try {
                            String raw = executeGet(API + path);
                            List<Candidate> parsed = parseCandidates(raw);
                            mergeCandidates(candidates, parsed);
                            if (sources.length() > 0) sources.append(" + ");
                            sources.append(path).append('(').append(parsed.size()).append(')');
                        } catch (Unauthorized error) {
                            unauthorized = true;
                            break;
                        } catch (Exception error) {
                            lastDetail = path + ": " + safe(error.getMessage());
                        }
                    }
                    if (unauthorized) break;
                    Candidate best = chooseBest(local, point, known, candidates);
                    if (best != null && !best.usableNumber().isEmpty()) {
                        KitchenOrder resolved = enrich(local, best);
                        if (clean(resolved.orderType).isEmpty() && best.orderTypeId > 0L) {
                            String type = orderTypeName(best.orderTypeId);
                            if (!type.isEmpty()) resolved.orderType = type;
                        }
                        String detail = "TemporaryOrders API • " + sources + " • #" + resolved.displayNumber;
                        main.post(() -> listener.onResolved(resolved, detail));
                        return;
                    }
                    lastDetail = "TemporaryOrders API returned " + candidates.size() + " records; no safe cart match yet";
                } catch (Throwable error) {
                    lastDetail = error.getClass().getSimpleName() + ": " + safe(error.getMessage());
                }
            }
            if (closed) return;
            if (unauthorized) main.post(listener::onUnauthorized);
            else {
                String detail = lastDetail;
                main.post(() -> listener.onNotFound(detail));
            }
        });
    }

    public void shutdown() {
        closed = true;
        worker.shutdownNow();
        http.dispatcher().cancelAll();
        http.connectionPool().evictAll();
    }

    private String executeGet(String url) throws Exception {
        Request request = new Request.Builder()
                .url(HttpUrl.get(url))
                .get()
                .header("Accept", "application/json")
                .header("Authorization", "Bearer " + token)
                .build();
        try (Response response = http.newCall(request).execute()) {
            String raw = response.body() == null ? "" : response.body().string();
            if (response.code() == 401 || response.code() == 403) throw new Unauthorized();
            if (!response.isSuccessful()) throw new IOException("HTTP " + response.code());
            return raw;
        }
    }

    private String orderTypeName(long id) {
        Map<Long, String> cached = orderTypeNames;
        if (cached == null) {
            synchronized (this) {
                cached = orderTypeNames;
                if (cached == null) {
                    cached = new HashMap<>();
                    try {
                        String raw = executeGet(API + "ErpLov/168");
                        collectLov(parseStructured(raw), cached, 0);
                    } catch (Exception ignored) { }
                    orderTypeNames = cached;
                }
            }
        }
        String value = cached.get(id);
        return value == null ? "" : value;
    }

    static KitchenOrder resolveFromPayloads(KitchenOrder draft, String posCode, List<String> knownNumbers, String... payloads) {
        if (draft == null) return null;
        ArrayList<Candidate> candidates = new ArrayList<>();
        if (payloads != null) for (String raw : payloads) mergeCandidates(candidates, parseCandidates(raw));
        HashSet<String> known = new HashSet<>();
        if (knownNumbers != null) for (String value : knownNumbers) {
            String n = cleanNumber(value);
            if (!n.isEmpty()) known.add(n);
        }
        Candidate best = chooseBest(draft, clean(posCode), known, candidates);
        return best == null ? null : enrich(draft, best);
    }

    static List<Candidate> parseCandidates(String raw) {
        ArrayList<Candidate> output = new ArrayList<>();
        collectCandidates(parseStructured(raw), output, 0);
        // De-duplicate representations of the same temp order found through wrappers.
        LinkedHashMap<String, Candidate> unique = new LinkedHashMap<>();
        for (Candidate candidate : output) {
            String key = candidate.id > 0 ? "id:" + candidate.id : "num:" + candidate.usableNumber();
            if (key.endsWith(":")) key = "sig:" + candidate.items.size() + ":" + candidate.orderDate + ":" + candidate.code;
            Candidate old = unique.get(key);
            if (old == null || candidate.items.size() > old.items.size()) unique.put(key, candidate);
        }
        return new ArrayList<>(unique.values());
    }

    private static void collectCandidates(Object raw, List<Candidate> output, int depth) {
        if (raw == null || raw == JSONObject.NULL || depth > 14) return;
        Object value = parseStructured(raw);
        if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            for (int i = 0; i < array.length(); i++) collectCandidates(array.opt(i), output, depth + 1);
            return;
        }
        if (!(value instanceof JSONObject)) return;
        JSONObject object = (JSONObject) value;
        if (looksLikeTemporaryOrder(object)) {
            Candidate candidate = parseCandidate(object);
            if (candidate != null && (!candidate.usableNumber().isEmpty() || candidate.id > 0L)) output.add(candidate);
        }
        Iterator<String> keys = object.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            Object child = object.opt(key);
            if (child instanceof JSONObject || child instanceof JSONArray || child instanceof String) {
                Object structured = parseStructured(child);
                if (structured instanceof JSONObject || structured instanceof JSONArray) collectCandidates(structured, output, depth + 1);
            }
        }
    }

    private static boolean looksLikeTemporaryOrder(JSONObject object) {
        if (object == null) return false;
        boolean marker = hasAny(object,
                "orderDate", "isMenuOrder", "reservationNumber", "orderTypeId", "isNewOrder", "isSync",
                "temporaryOrderItems", "tempOrderItems", "TempOrderItems", "orderItems", "OrderItems");
        if (!marker) return false;
        return scalar(object, "id", "orderId", "temporaryOrderId", "tempOrderId") != null
                || scalar(object, "number", "orderNumber", "temporaryOrderNumber", "tempOrderNumber", "code") != null;
    }

    private static Candidate parseCandidate(JSONObject object) {
        Candidate c = new Candidate();
        c.id = longValue(object, 0L, "id", "orderId", "temporaryOrderId", "tempOrderId", "Id", "OrderId");
        c.number = text(object,
                "temporaryOrderNumber", "tempOrderNumber", "orderNumber", "number", "Number", "orderNo", "tempOrderNo");
        c.code = text(object, "code", "Code", "orderCode", "temporaryOrderCode");
        c.table = text(object,
                "tableNumber", "tableNo", "tableName", "table", "reservationNumber", "ReservationNumber");
        c.orderType = text(object,
                "orderTypeName", "serviceTypeName", "orderType", "serviceType", "OrderTypeName");
        c.orderTypeId = longValue(object, 0L, "orderTypeId", "OrderTypeId", "serviceTypeId");
        c.note = text(object, "note", "notes", "Note", "orderNote", "customerNote");
        c.posCode = text(object, "posCode", "PosCode", "pointCode", "cashierCode", "pos_code");
        c.orderDate = dateValue(first(object, "orderDate", "OrderDate", "createdAt", "creationDate", "date"));
        JSONArray rows = itemArray(object);
        if (rows != null) for (int i = 0; i < rows.length(); i++) {
            JSONObject row = object(rows.opt(i));
            if (row == null) continue;
            ItemKey item = new ItemKey();
            item.itemId = longValue(row, 0L, "itemId", "ItemId", "productId", "item_id");
            item.unitId = longValue(row, 0L, "unitId", "UnitId", "unit_id");
            item.qty = doubleValue(row, 0d, "qty", "Qty", "quantity", "Quantity", "itemQty");
            item.name = textDeep(row, "itemName", "name", "nameAr", "nameEn", "productName");
            if (item.itemId > 0L || !clean(item.name).isEmpty()) c.items.add(item);
        }
        return c;
    }

    static Candidate chooseBest(KitchenOrder draft, String posCode, Set<String> knownNumbers, List<Candidate> candidates) {
        if (draft == null || draft.items.isEmpty() || candidates == null || candidates.isEmpty()) return null;
        Candidate best = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (Candidate candidate : candidates) {
            String number = candidate.usableNumber();
            if (number.isEmpty()) continue;
            double score = matchScore(draft, candidate);
            if (score < 120d) continue; // refuse weak accidental matches across an account.
            if (!clean(posCode).isEmpty() && !clean(candidate.posCode).isEmpty()) {
                score += clean(posCode).equalsIgnoreCase(clean(candidate.posCode)) ? 180d : -500d;
            }
            if (knownNumbers != null && !knownNumbers.contains(number)) score += 18d;
            if (candidate.orderDate > 0L) score += Math.min(30d, candidate.orderDate / 1_000_000_000_000d);
            score += Math.min(20d, candidate.id / 1_000_000d);
            if (best == null || score > bestScore || (Math.abs(score - bestScore) < 0.001 && candidate.id > best.id)) {
                best = candidate;
                bestScore = score;
            }
        }
        return best;
    }

    static double matchScore(KitchenOrder draft, Candidate candidate) {
        Map<Long, Double> wanted = aggregateDraft(draft);
        Map<Long, Double> got = aggregateCandidate(candidate);
        if (!wanted.isEmpty() && !got.isEmpty()) {
            double score = 0d;
            int exact = 0;
            int overlap = 0;
            for (Map.Entry<Long, Double> entry : wanted.entrySet()) {
                Double other = got.get(entry.getKey());
                if (other == null) { score -= 75d; continue; }
                overlap++;
                double diff = Math.abs(entry.getValue() - other);
                if (diff < 0.001) { exact++; score += 170d; }
                else score += Math.max(20d, 100d - diff * 35d);
            }
            for (Long id : got.keySet()) if (!wanted.containsKey(id)) score -= 35d;
            if (exact == wanted.size() && got.size() == wanted.size()) score += 550d;
            else if (overlap == wanted.size()) score += 180d;
            return score;
        }

        // Fallback for websocket rows without ids: compare row count, quantities and names conservatively.
        if (candidate.items.isEmpty()) return -1000d;
        double score = Math.abs(draft.items.size() - candidate.items.size()) == 0 ? 120d : -80d;
        double draftQty = 0d, cloudQty = 0d;
        for (KitchenOrder.Item item : draft.items) draftQty += item.qty;
        for (ItemKey item : candidate.items) cloudQty += item.qty;
        score += Math.abs(draftQty - cloudQty) < 0.001 ? 90d : -Math.abs(draftQty - cloudQty) * 25d;
        int nameHits = 0;
        for (KitchenOrder.Item local : draft.items) {
            String name = normalizeName(local.name);
            if (name.isEmpty()) name = normalizeName(local.nameAr);
            if (name.isEmpty()) name = normalizeName(local.nameEn);
            if (name.isEmpty()) continue;
            for (ItemKey remote : candidate.items) if (name.equals(normalizeName(remote.name))) { nameHits++; break; }
        }
        score += nameHits * 80d;
        return score;
    }

    private static KitchenOrder enrich(KitchenOrder draft, Candidate candidate) {
        KitchenOrder order = draft.copy();
        String number = candidate.usableNumber();
        order.displayNumber = number;
        order.id = "invoice-" + number;
        if (!clean(candidate.table).isEmpty() && !"0".equals(clean(candidate.table))) order.table = candidate.table;
        if (!clean(candidate.orderType).isEmpty()) order.orderType = KitchenSignalV2.normalizeOrderType(candidate.orderType);
        if (!clean(candidate.note).isEmpty() && clean(order.customerNote).isEmpty()) order.customerNote = candidate.note;
        order.temporaryOrder = true;
        if (clean(order.paymentStatus).isEmpty()) order.paymentStatus = "UNPAID";
        return order;
    }

    private static Map<Long, Double> aggregateDraft(KitchenOrder draft) {
        HashMap<Long, Double> map = new HashMap<>();
        if (draft == null) return map;
        for (KitchenOrder.Item item : draft.items) if (item.itemId > 0L) map.put(item.itemId, map.getOrDefault(item.itemId, 0d) + item.qty);
        return map;
    }

    private static Map<Long, Double> aggregateCandidate(Candidate candidate) {
        HashMap<Long, Double> map = new HashMap<>();
        if (candidate == null) return map;
        for (ItemKey item : candidate.items) if (item.itemId > 0L) map.put(item.itemId, map.getOrDefault(item.itemId, 0d) + item.qty);
        return map;
    }

    private static void mergeCandidates(List<Candidate> target, List<Candidate> incoming) {
        if (incoming == null) return;
        for (Candidate candidate : incoming) {
            boolean duplicate = false;
            for (int i = 0; i < target.size(); i++) {
                Candidate old = target.get(i);
                if ((candidate.id > 0 && candidate.id == old.id)
                        || (!candidate.usableNumber().isEmpty() && candidate.usableNumber().equals(old.usableNumber()))) {
                    if (candidate.items.size() > old.items.size()) target.set(i, candidate);
                    duplicate = true;
                    break;
                }
            }
            if (!duplicate) target.add(candidate);
        }
    }

    private static void collectLov(Object raw, Map<Long, String> output, int depth) {
        if (raw == null || raw == JSONObject.NULL || depth > 10) return;
        Object value = parseStructured(raw);
        if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            for (int i = 0; i < array.length(); i++) collectLov(array.opt(i), output, depth + 1);
            return;
        }
        if (!(value instanceof JSONObject)) return;
        JSONObject object = (JSONObject) value;
        long id = longValue(object, 0L, "id", "Id", "value", "Value", "code", "Code");
        String name = text(object, "nameAr", "NameAr", "name", "Name", "descriptionAr", "description", "label");
        if (id > 0L && !clean(name).isEmpty()) output.put(id, name);
        Iterator<String> keys = object.keys();
        while (keys.hasNext()) collectLov(object.opt(keys.next()), output, depth + 1);
    }

    private static JSONArray itemArray(JSONObject object) {
        String[] keys = {"temporaryOrderItems", "tempOrderItems", "TempOrderItems", "items", "Items", "orderItems", "OrderItems", "details", "orderDetails"};
        for (String key : keys) {
            Object raw = value(object, key);
            Object structured = parseStructured(raw);
            if (structured instanceof JSONArray) return (JSONArray) structured;
            if (structured instanceof JSONObject) {
                JSONArray converted = objectValues((JSONObject) structured);
                if (converted.length() > 0) return converted;
            }
        }
        return null;
    }

    private static JSONArray objectValues(JSONObject object) {
        JSONArray array = new JSONArray();
        if (object == null) return array;
        Iterator<String> keys = object.keys();
        while (keys.hasNext()) {
            Object value = object.opt(keys.next());
            if (value instanceof JSONObject) array.put(value);
        }
        return array;
    }

    private static String textDeep(JSONObject object, String... keys) {
        String direct = text(object, keys);
        if (!direct.isEmpty()) return direct;
        String[] nested = {"item", "product", "itemData", "productData", "data"};
        for (String key : nested) {
            JSONObject child = object(value(object, key));
            if (child == null) continue;
            String found = textDeep(child, keys);
            if (!found.isEmpty()) return found;
        }
        return "";
    }

    private static String text(JSONObject object, String... keys) {
        if (object == null) return "";
        for (String key : keys) {
            Object raw = value(object, key);
            if (raw == null || raw == JSONObject.NULL || raw instanceof JSONArray) continue;
            if (raw instanceof JSONObject) {
                String localized = text((JSONObject) raw, "ar", "nameAr", "value", "en", "nameEn");
                if (!localized.isEmpty()) return localized;
                continue;
            }
            String text = clean(String.valueOf(raw));
            if (!text.isEmpty() && !"null".equalsIgnoreCase(text)) return text;
        }
        return "";
    }

    private static Object scalar(JSONObject object, String... keys) {
        for (String key : keys) {
            Object raw = value(object, key);
            if (raw instanceof String || raw instanceof Number) return raw;
        }
        return null;
    }

    private static Object first(JSONObject object, String... keys) {
        for (String key : keys) {
            Object raw = value(object, key);
            if (raw != null && raw != JSONObject.NULL) return raw;
        }
        return null;
    }

    private static boolean hasAny(JSONObject object, String... keys) {
        for (String key : keys) if (value(object, key) != null) return true;
        return false;
    }

    private static long longValue(JSONObject object, long fallback, String... keys) {
        Object raw = first(object, keys);
        if (raw == null) return fallback;
        try { return raw instanceof Number ? ((Number) raw).longValue() : Long.parseLong(String.valueOf(raw).trim()); }
        catch (Exception ignored) { return fallback; }
    }

    private static double doubleValue(JSONObject object, double fallback, String... keys) {
        Object raw = first(object, keys);
        if (raw == null) return fallback;
        try { return raw instanceof Number ? ((Number) raw).doubleValue() : Double.parseDouble(String.valueOf(raw).trim()); }
        catch (Exception ignored) { return fallback; }
    }

    private static Object value(JSONObject object, String requested) {
        if (object == null) return null;
        if (object.has(requested)) return object.opt(requested);
        String wanted = normalizeKey(requested);
        Iterator<String> keys = object.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            if (normalizeKey(key).equals(wanted)) return object.opt(key);
        }
        return null;
    }

    private static String normalizeKey(String value) {
        return value == null ? "" : value.toLowerCase(Locale.US).replace("_", "").replace("-", "").replace(" ", "");
    }

    private static JSONObject object(Object raw) {
        Object value = parseStructured(raw);
        return value instanceof JSONObject ? (JSONObject) value : null;
    }

    private static Object parseStructured(Object raw) {
        Object value = raw;
        for (int i = 0; i < 5; i++) {
            if (value instanceof JSONObject || value instanceof JSONArray) return value;
            if (!(value instanceof String)) return value;
            String text = ((String) value).trim();
            if (text.isEmpty()) return value;
            try {
                Object parsed = new JSONTokener(text).nextValue();
                if (parsed instanceof String && parsed.equals(value)) return parsed;
                value = parsed;
            } catch (Exception ignored) { return value; }
        }
        return value;
    }

    private static long dateValue(Object raw) {
        if (raw == null || raw == JSONObject.NULL) return 0L;
        if (raw instanceof Number) {
            long value = ((Number) raw).longValue();
            return value < 10_000_000_000L ? value * 1000L : value;
        }
        String text = clean(String.valueOf(raw));
        if (text.isEmpty()) return 0L;
        try {
            long value = Long.parseLong(text);
            return value < 10_000_000_000L ? value * 1000L : value;
        } catch (Exception ignored) { }
        String[] formats = {"yyyy-MM-dd'T'HH:mm:ss.SSSXXX", "yyyy-MM-dd'T'HH:mm:ssXXX", "yyyy-MM-dd'T'HH:mm:ss.SSS", "yyyy-MM-dd'T'HH:mm:ss", "yyyy-MM-dd HH:mm:ss"};
        for (String format : formats) {
            try {
                SimpleDateFormat parser = new SimpleDateFormat(format, Locale.US);
                if (!format.contains("XXX")) parser.setTimeZone(TimeZone.getDefault());
                Date date = parser.parse(text);
                if (date != null) return date.getTime();
            } catch (ParseException ignored) { }
        }
        return 0L;
    }

    private static String cleanNumber(String value) {
        String result = KitchenSignalV2.cleanIdentity(value);
        if (result.startsWith("invoice-")) result = result.substring(8);
        return result.trim();
    }

    private static boolean isMostlyNumeric(String value) {
        if (value == null || value.trim().isEmpty()) return false;
        int digits = 0, other = 0;
        for (char c : value.toCharArray()) {
            if (Character.isDigit(c)) digits++;
            else if (!Character.isWhitespace(c) && c != '-' && c != '/' && c != '#') other++;
        }
        return digits > 0 && other == 0;
    }

    private static String normalizeName(String value) {
        return clean(value).toLowerCase(Locale.US).replaceAll("\\s+", " ");
    }

    private static String stripBearer(String value) {
        String token = clean(value);
        return token.toLowerCase(Locale.US).startsWith("bearer ") ? token.substring(7).trim() : token;
    }

    private static String clean(String value) { return value == null ? "" : value.trim(); }
    private static String safe(String value) { return value == null ? "" : value; }

    private static final class Unauthorized extends Exception { }
}

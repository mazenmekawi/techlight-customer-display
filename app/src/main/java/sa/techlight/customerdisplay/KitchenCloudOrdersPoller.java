package sa.techlight.customerdisplay;

import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Cloud-first KDS transport using the operational API embedded in the official TechPro POS APK.
 * TemporaryOrders handles parked orders; PosInvoice contributes newly paid/direct invoices.
 */
public final class KitchenCloudOrdersPoller {
    public interface Listener {
        void onSnapshot(List<KitchenOrder> orders, String detail);
        void onStatus(String status, boolean connected);
        void onUnauthorized();
    }

    static final String API = "https://posapi.techlight.sa/api/";
    static final String TEMP_LIST_ENDPOINT = API + "TemporaryOrders/List";
    static final String TEMP_ENDPOINT = API + "TemporaryOrders";
    static final String ORDER_TYPES_ENDPOINT = API + "ErpLov/168";
    static final String POS_INVOICE_ENDPOINT = API + "PosInvoice";
    private static final long POLL_MS = 2000L;
    private static final long RECENT_BASELINE_WINDOW_MS = 120_000L;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final OkHttpClient http = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build();
    private final AtomicBoolean requestRunning = new AtomicBoolean(false);
    private final String token;
    private final String posCode;
    private final Listener listener;
    private final HashMap<Long, String> orderTypeNames = new HashMap<>();
    private final HashSet<String> seenPaidInvoices = new HashSet<>();
    private final long startedAt = System.currentTimeMillis();
    private volatile boolean orderTypesLoaded;
    private volatile boolean invoiceBaselineReady;
    private volatile boolean running;

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!running) return;
            if (requestRunning.compareAndSet(false, true)) worker.execute(KitchenCloudOrdersPoller.this::fetchOnce);
            main.postDelayed(this, POLL_MS);
        }
    };

    public KitchenCloudOrdersPoller(String token, String posCode, Listener listener) {
        this.token = stripBearer(token);
        this.posCode = clean(posCode);
        this.listener = listener;
    }

    public void start() {
        if (running) return;
        running = true;
        main.post(() -> {
            if (listener != null) listener.onStatus("Cloud syncing", false);
            tick.run();
        });
    }

    public void stop() {
        running = false;
        main.removeCallbacks(tick);
        worker.shutdownNow();
        http.dispatcher().cancelAll();
        http.connectionPool().evictAll();
    }

    private void fetchOnce() {
        long started = System.currentTimeMillis();
        try {
            if (!orderTypesLoaded) loadOrderTypes();

            String tempRaw = fetchTemporaryOrders();
            List<KitchenTemporaryOrdersApiClient.Candidate> tempCandidates = KitchenTemporaryOrdersApiClient.parseCandidates(tempRaw);
            List<KitchenOrder> temporary = convertTemporary(tempCandidates, posCode, orderTypeNames);

            ArrayList<KitchenOrder> combined = new ArrayList<>(temporary);
            int paidNew = 0;
            String invoiceDetail = "invoice=ok";
            try {
                List<KitchenOrder> newPaid = fetchNewPaidInvoices();
                paidNew = newPaid.size();
                combined.addAll(newPaid);
            } catch (Unauthorized unauthorized) {
                throw unauthorized;
            } catch (Throwable invoiceError) {
                invoiceDetail = "invoice=" + invoiceError.getClass().getSimpleName();
            }

            long elapsed = System.currentTimeMillis() - started;
            String detail = "Cloud official API • temp=" + temporary.size() + " • paidNew=" + paidNew
                    + " • " + invoiceDetail + " • " + elapsed + "ms";
            main.post(() -> {
                if (!running || listener == null) return;
                listener.onStatus("Cloud connected", true);
                listener.onSnapshot(combined, detail);
            });
        } catch (Unauthorized unauthorized) {
            main.post(() -> {
                if (!running || listener == null) return;
                listener.onStatus("Cloud session expired", false);
                listener.onUnauthorized();
            });
        } catch (Throwable error) {
            String detail = "Cloud retry • " + error.getClass().getSimpleName() + " • " + clean(error.getMessage());
            main.post(() -> {
                if (running && listener != null) listener.onStatus(detail, false);
            });
        } finally {
            requestRunning.set(false);
        }
    }

    private String fetchTemporaryOrders() throws Exception {
        try {
            return executeGet(TEMP_LIST_ENDPOINT);
        } catch (Unauthorized unauthorized) {
            throw unauthorized;
        } catch (Exception listError) {
            try {
                return executeGet(TEMP_ENDPOINT);
            } catch (Unauthorized unauthorized) {
                throw unauthorized;
            } catch (Exception baseError) {
                HttpUrl paged = HttpUrl.get(TEMP_ENDPOINT).newBuilder()
                        .addQueryParameter("Page", "1")
                        .addQueryParameter("PageSize", "300")
                        .build();
                return executeGet(paged.toString());
            }
        }
    }

    private List<KitchenOrder> fetchNewPaidInvoices() throws Exception {
        String day = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
        HttpUrl url = HttpUrl.get(POS_INVOICE_ENDPOINT).newBuilder()
                .addQueryParameter("FromDate", day)
                .addQueryParameter("ToDate", day)
                .build();
        String raw = executeGet(url.toString());
        List<KitchenPosInvoiceParser.Candidate> candidates = KitchenPosInvoiceParser.parse(raw);
        List<KitchenOrder> invoices = KitchenPosInvoiceParser.convert(candidates, posCode);

        ArrayList<KitchenOrder> delta = new ArrayList<>();
        if (!invoiceBaselineReady) {
            for (KitchenOrder invoice : invoices) {
                String number = invoice.bestNumber();
                if (!number.isEmpty()) seenPaidInvoices.add(number);
                if (invoice.createdAt > 0L && invoice.createdAt >= startedAt - RECENT_BASELINE_WINDOW_MS) {
                    KitchenOrder hydrated = hydrateInvoice(invoice, candidates);
                    if (hydrated != null && !hydrated.items.isEmpty()) delta.add(hydrated);
                }
            }
            invoiceBaselineReady = true;
            return delta;
        }

        for (KitchenOrder invoice : invoices) {
            String number = invoice.bestNumber();
            if (number.isEmpty() || !seenPaidInvoices.add(number)) continue;
            KitchenOrder hydrated = hydrateInvoice(invoice, candidates);
            if (hydrated != null) delta.add(hydrated);
        }
        return delta;
    }

    private KitchenOrder hydrateInvoice(KitchenOrder header, List<KitchenPosInvoiceParser.Candidate> candidates) {
        if (header == null) return null;
        if (!header.items.isEmpty()) return resolveTypeName(header);
        String number = header.bestNumber();
        String lookup = number;
        if (candidates != null) for (KitchenPosInvoiceParser.Candidate c : candidates) {
            if (c != null && number.equals(c.usableNumber()) && !clean(c.code).isEmpty()) {
                lookup = c.code;
                break;
            }
        }
        if (clean(lookup).isEmpty()) return resolveTypeName(header);
        try {
            HttpUrl detailUrl = HttpUrl.get(POS_INVOICE_ENDPOINT).newBuilder()
                    .addQueryParameter("InvoiceCode", lookup)
                    .build();
            List<KitchenOrder> detail = KitchenPosInvoiceParser.convert(
                    KitchenPosInvoiceParser.parse(executeGet(detailUrl.toString())), posCode
            );
            for (KitchenOrder candidate : detail) {
                if (number.equals(candidate.bestNumber()) || detail.size() == 1) return resolveTypeName(candidate);
            }
        } catch (Throwable ignored) { }
        return resolveTypeName(header);
    }

    private KitchenOrder resolveTypeName(KitchenOrder order) {
        if (order == null) return null;
        String raw = clean(order.orderType);
        if (raw.startsWith("TYPE_")) {
            try {
                long id = Long.parseLong(raw.substring(5));
                String name = orderTypeNames.get(id);
                if (!clean(name).isEmpty()) order.orderType = normalizeOrderType(name);
            } catch (Exception ignored) { }
        }
        return order;
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

    private void loadOrderTypes() throws Unauthorized {
        try {
            String raw = executeGet(ORDER_TYPES_ENDPOINT);
            HashMap<Long, String> parsed = new HashMap<>();
            collectOrderTypes(structured(raw), parsed, 0);
            synchronized (orderTypeNames) {
                orderTypeNames.clear();
                orderTypeNames.putAll(parsed);
            }
        } catch (Unauthorized unauthorized) {
            throw unauthorized;
        } catch (Throwable ignored) {
            // Orders still work if LOV is temporarily unavailable; raw type metadata remains visible.
        } finally {
            orderTypesLoaded = true;
        }
    }

    static List<KitchenOrder> convert(List<KitchenTemporaryOrdersApiClient.Candidate> candidates, String posCode) {
        return convertTemporary(candidates, posCode, new HashMap<>());
    }

    static List<KitchenOrder> convertTemporary(
            List<KitchenTemporaryOrdersApiClient.Candidate> candidates,
            String posCode,
            Map<Long, String> orderTypes
    ) {
        ArrayList<KitchenOrder> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        if (candidates == null) return result;
        for (KitchenTemporaryOrdersApiClient.Candidate c : candidates) {
            if (c == null) continue;
            String number = c.usableNumber();
            if (number.isEmpty() || seen.contains(number)) continue;
            if (!clean(posCode).isEmpty() && !clean(c.posCode).isEmpty()
                    && !clean(posCode).equalsIgnoreCase(clean(c.posCode))) continue;

            KitchenOrder order = new KitchenOrder();
            order.displayNumber = number;
            order.id = "invoice-" + number;
            order.table = normalizeTable(c.table);
            String typeName = clean(c.orderType);
            if (typeName.isEmpty() && c.orderTypeId > 0L && orderTypes != null) {
                String mapped = orderTypes.get(c.orderTypeId);
                if (mapped != null) typeName = mapped;
            }
            order.orderType = normalizeOrderType(typeName);
            order.customerNote = clean(c.note);
            order.paymentStatus = "UNPAID";
            order.rawStatus = "TEMPORARY";
            order.temporaryOrder = true;
            if (c.orderDate > 0L) {
                order.createdAt = c.orderDate;
                order.updatedAt = c.orderDate;
            }
            for (KitchenTemporaryOrdersApiClient.ItemKey source : c.items) {
                if (source == null || (source.itemId <= 0L && clean(source.name).isEmpty())) continue;
                KitchenOrder.Item item = new KitchenOrder.Item();
                item.itemId = source.itemId;
                item.qty = source.qty > 0d ? source.qty : 1d;
                item.name = clean(source.name);
                order.items.add(item);
            }
            if (order.items.isEmpty()) continue;
            seen.add(number);
            result.add(order);
        }
        return result;
    }

    private static void collectOrderTypes(Object raw, Map<Long, String> output, int depth) {
        if (raw == null || raw == JSONObject.NULL || depth > 10) return;
        Object value = structured(raw);
        if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            for (int i = 0; i < array.length(); i++) collectOrderTypes(array.opt(i), output, depth + 1);
            return;
        }
        if (!(value instanceof JSONObject)) return;
        JSONObject object = (JSONObject) value;
        long id = longValue(object, "id", "Id", "value", "Value", "code", "Code", "lovId");
        String name = text(object, "nameAr", "NameAr", "name", "Name", "descriptionAr", "description", "label", "title");
        if (id > 0L && !name.isEmpty()) output.put(id, name);
        Iterator<String> keys = object.keys();
        while (keys.hasNext()) collectOrderTypes(object.opt(keys.next()), output, depth + 1);
    }

    private static String normalizeOrderType(String raw) {
        String normalized = KitchenSignalV2.normalizeOrderType(raw);
        if (!normalized.isEmpty()) return normalized;
        String value = clean(raw).toLowerCase(Locale.US);
        if (value.contains("محلي") || value.contains("صالة") || value.contains("dine") || value.contains("local")) return "DINE_IN";
        if (value.contains("سفري") || value.contains("take") || value.contains("pickup")) return "TAKEAWAY";
        if (value.contains("توصيل") || value.contains("delivery")) return "DELIVERY";
        return clean(raw);
    }

    private static String normalizeTable(String value) {
        String table = clean(value);
        if (table.equals("0") || table.equals("0.0") || table.equalsIgnoreCase("null")) return "";
        return table;
    }

    private static Object structured(Object raw) {
        Object value = raw;
        for (int i = 0; i < 4; i++) {
            if (value instanceof JSONObject || value instanceof JSONArray) return value;
            if (!(value instanceof String)) return value;
            String text = ((String) value).trim();
            if (text.isEmpty()) return value;
            try { value = new JSONTokener(text).nextValue(); }
            catch (Exception ignored) { return value; }
        }
        return value;
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

    private static long longValue(JSONObject object, String... keys) {
        for (String key : keys) {
            Object raw = value(object, key);
            if (raw == null || raw == JSONObject.NULL) continue;
            try { return raw instanceof Number ? ((Number) raw).longValue() : Long.parseLong(String.valueOf(raw).trim()); }
            catch (Exception ignored) { }
        }
        return 0L;
    }

    private static String text(JSONObject object, String... keys) {
        for (String key : keys) {
            Object raw = value(object, key);
            if (raw == null || raw == JSONObject.NULL || raw instanceof JSONArray || raw instanceof JSONObject) continue;
            String result = clean(String.valueOf(raw));
            if (!result.isEmpty() && !result.equalsIgnoreCase("null")) return result;
        }
        return "";
    }

    private static String normalizeKey(String value) {
        return clean(value).toLowerCase(Locale.US).replace("_", "").replace("-", "").replace(" ", "");
    }

    private static String stripBearer(String value) {
        String token = clean(value);
        return token.toLowerCase(Locale.US).startsWith("bearer ") ? token.substring(7).trim() : token;
    }

    private static String clean(String value) { return value == null ? "" : value.trim(); }

    private static final class Unauthorized extends Exception { }
}

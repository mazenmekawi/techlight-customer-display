package sa.techlight.customerdisplay;

import android.os.Handler;
import android.os.Looper;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
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
 * Cloud-first KDS transport. It does not need the cashier LAN websocket.
 * It polls the same TemporaryOrders endpoint embedded in the official TechPro POS app.
 */
public final class KitchenCloudOrdersPoller {
    public interface Listener {
        void onSnapshot(List<KitchenOrder> orders, String detail);
        void onStatus(String status, boolean connected);
        void onUnauthorized();
    }

    static final String ENDPOINT = "https://posapifornewapp.techlight.sa/api/TemporaryOrders?Page=1&PageSize=300";
    private static final long POLL_MS = 2000L;
    private static final long RETRY_MS = 5000L;

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
            Request request = new Request.Builder()
                    .url(HttpUrl.get(ENDPOINT))
                    .get()
                    .header("Accept", "application/json")
                    .header("Authorization", "Bearer " + token)
                    .build();
            try (Response response = http.newCall(request).execute()) {
                String raw = response.body() == null ? "" : response.body().string();
                if (response.code() == 401 || response.code() == 403) {
                    main.post(() -> {
                        if (!running || listener == null) return;
                        listener.onStatus("Cloud session expired", false);
                        listener.onUnauthorized();
                    });
                    return;
                }
                if (!response.isSuccessful()) throw new IOException("TemporaryOrders HTTP " + response.code());

                List<KitchenTemporaryOrdersApiClient.Candidate> candidates = KitchenTemporaryOrdersApiClient.parseCandidates(raw);
                List<KitchenOrder> orders = convert(candidates, posCode);
                long elapsed = System.currentTimeMillis() - started;
                String detail = "Cloud • " + orders.size() + " active • " + elapsed + "ms";
                main.post(() -> {
                    if (!running || listener == null) return;
                    listener.onStatus("Cloud connected", true);
                    listener.onSnapshot(orders, detail);
                });
            }
        } catch (Throwable error) {
            String detail = "Cloud retry • " + error.getClass().getSimpleName();
            main.post(() -> {
                if (running && listener != null) listener.onStatus(detail, false);
            });
        } finally {
            requestRunning.set(false);
        }
    }

    static List<KitchenOrder> convert(List<KitchenTemporaryOrdersApiClient.Candidate> candidates, String posCode) {
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
            order.orderType = normalizeOrderType(c.orderType, c.orderTypeId);
            order.customerNote = clean(c.note);
            order.paymentStatus = "UNPAID";
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
            // A temp order without item rows is not useful to a KDS and often represents a wrapper object.
            if (order.items.isEmpty()) continue;
            seen.add(number);
            result.add(order);
        }
        return result;
    }

    private static String normalizeOrderType(String raw, long id) {
        String normalized = KitchenSignalV2.normalizeOrderType(raw);
        if (!normalized.isEmpty()) return normalized;
        // Common TechPro order-type IDs are intentionally not guessed here. If API returns only an id,
        // expose a stable token instead of showing an incorrect Local/Takeaway label.
        return id > 0L ? "TYPE_" + id : "";
    }

    private static String normalizeTable(String value) {
        String table = clean(value);
        if (table.equals("0") || table.equals("0.0") || table.equalsIgnoreCase("null")) return "";
        return table;
    }

    private static String stripBearer(String value) {
        String token = clean(value);
        return token.toLowerCase(Locale.US).startsWith("bearer ") ? token.substring(7).trim() : token;
    }

    private static String clean(String value) { return value == null ? "" : value.trim(); }
}

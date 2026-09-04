package sa.techlight.customerdisplay;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Production metadata that sits beside the existing durable order store.
 * It records additions, employees and timeline events without changing the
 * verified TechPro order protocol or inventing missing server values.
 */
public final class KitchenProState {
    private static final String PREF = "techlight_kds_pro_state_v1";
    private static final String ORDERS = "orders";
    private static final int MAX_EVENTS_PER_ORDER = 80;
    private static final long META_RETENTION_MS = 45L * 24L * 60L * 60L * 1000L;

    private final SharedPreferences preferences;
    private final LinkedHashMap<String, Meta> metas = new LinkedHashMap<>();

    private static final class Meta {
        String id = "";
        String invoice = "";
        String employee = "";
        long firstSeenAt;
        long lastSeenAt;
        long additionsAt;
        int pendingAdditions;
        final LinkedHashMap<String, Double> quantities = new LinkedHashMap<>();
        final ArrayList<Event> events = new ArrayList<>();
    }

    private static final class Event {
        String type = "";
        String employee = "";
        String detail = "";
        long at;
    }

    public KitchenProState(Context context) {
        preferences = context.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        load();
        prune(System.currentTimeMillis());
    }

    /** Observe an authoritative merged ticket and detect only positive item deltas. */
    public synchronized int observe(KitchenOrder order) {
        if (order == null || clean(order.id).isEmpty()) return 0;
        long now = System.currentTimeMillis();
        Meta meta = metas.get(order.id);
        boolean first = meta == null;
        if (meta == null) {
            meta = new Meta();
            meta.id = order.id;
            meta.invoice = order.bestNumber();
            meta.firstSeenAt = order.createdAt > 0L ? order.createdAt : now;
            meta.lastSeenAt = now;
            meta.quantities.putAll(itemQuantities(order));
            addEvent(meta, "CREATED", "", "", meta.firstSeenAt);
            metas.put(meta.id, meta);
            persist();
            return 0;
        }

        meta.lastSeenAt = now;
        if (!order.bestNumber().isEmpty()) meta.invoice = order.bestNumber();
        LinkedHashMap<String, Double> current = itemQuantities(order);
        int added = 0;
        for (Map.Entry<String, Double> entry : current.entrySet()) {
            double before = meta.quantities.containsKey(entry.getKey()) ? meta.quantities.get(entry.getKey()) : 0d;
            double delta = entry.getValue() - before;
            if (delta > 0.0001d) added += Math.max(1, (int) Math.ceil(delta));
        }
        meta.quantities.clear();
        meta.quantities.putAll(current);
        if (!first && added > 0) {
            meta.pendingAdditions += added;
            meta.additionsAt = now;
            addEvent(meta, "ADDITIONAL_ITEMS", meta.employee,
                    "+" + added + " items", now);
        }
        persist();
        return added;
    }

    public synchronized int pendingAdditions(String orderId) {
        Meta meta = metas.get(clean(orderId));
        return meta == null ? 0 : Math.max(0, meta.pendingAdditions);
    }

    public synchronized long additionsAt(String orderId) {
        Meta meta = metas.get(clean(orderId));
        return meta == null ? 0L : meta.additionsAt;
    }

    public synchronized void acknowledge(String orderId, String employee) {
        Meta meta = ensure(orderId, "");
        int count = meta.pendingAdditions;
        meta.pendingAdditions = 0;
        if (!clean(employee).isEmpty()) meta.employee = clean(employee);
        addEvent(meta, "ADDITIONS_ACKNOWLEDGED", meta.employee,
                count > 0 ? String.valueOf(count) : "", System.currentTimeMillis());
        persist();
    }

    public synchronized void recordStatus(KitchenOrder order, KitchenOrder.Status status,
                                          String employee) {
        if (order == null || status == null) return;
        Meta meta = ensure(order.id, order.bestNumber());
        if (!clean(employee).isEmpty()) meta.employee = clean(employee);
        String type;
        switch (status) {
            case PREPARING: type = "PREPARING"; break;
            case READY: type = "READY"; break;
            case DONE: type = "COMPLETED"; break;
            case CANCELLED: type = "CANCELLED"; break;
            default: type = "NEW"; break;
        }
        addEvent(meta, type, meta.employee, "", System.currentTimeMillis());
        persist();
    }

    public synchronized void recordSync(String orderId, String detail, boolean success) {
        Meta meta = ensure(orderId, "");
        addEvent(meta, success ? "API_SYNCED" : "API_PENDING", meta.employee,
                clean(detail), System.currentTimeMillis());
        persist();
    }

    public synchronized String employee(String orderId) {
        Meta meta = metas.get(clean(orderId));
        return meta == null ? "" : clean(meta.employee);
    }

    public synchronized void setEmployee(String orderId, String employee) {
        if (clean(employee).isEmpty()) return;
        Meta meta = ensure(orderId, "");
        meta.employee = clean(employee);
        persist();
    }

    public synchronized int complexity(KitchenOrder order) {
        if (order == null) return 0;
        int score = 0;
        for (KitchenOrder.Item item : order.items) {
            score += Math.max(1, (int) Math.ceil(Math.max(0d, item.qty)));
            score += item.modifiers.size() * 2;
            score += item.removed.size();
            if (!clean(item.note).isEmpty()) score += 2;
        }
        if (!clean(order.customerNote).isEmpty()) score += 3;
        score += pendingAdditions(order.id) * 3;
        return score;
    }

    public synchronized String timeline(String orderId, boolean arabic) {
        Meta meta = metas.get(clean(orderId));
        if (meta == null || meta.events.isEmpty()) return "";
        StringBuilder out = new StringBuilder();
        for (Event event : meta.events) {
            out.append(formatTime(event.at)).append("  ")
                    .append(eventLabel(event.type, arabic));
            if (!clean(event.employee).isEmpty()) out.append(" • ").append(event.employee);
            if (!clean(event.detail).isEmpty()) out.append(" • ").append(event.detail);
            out.append('\n');
        }
        return out.toString().trim();
    }

    public synchronized int totalAdditionalEvents() {
        int total = 0;
        for (Meta meta : metas.values()) {
            for (Event event : meta.events) if ("ADDITIONAL_ITEMS".equals(event.type)) total++;
        }
        return total;
    }

    private Meta ensure(String orderId, String invoice) {
        String id = clean(orderId);
        if (id.isEmpty()) id = "unknown-" + System.currentTimeMillis();
        Meta meta = metas.get(id);
        if (meta == null) {
            meta = new Meta();
            meta.id = id;
            meta.invoice = clean(invoice);
            meta.firstSeenAt = System.currentTimeMillis();
            meta.lastSeenAt = meta.firstSeenAt;
            metas.put(id, meta);
        }
        if (!clean(invoice).isEmpty()) meta.invoice = clean(invoice);
        return meta;
    }

    private static LinkedHashMap<String, Double> itemQuantities(KitchenOrder order) {
        LinkedHashMap<String, Double> result = new LinkedHashMap<>();
        int index = 0;
        for (KitchenOrder.Item item : order.items) {
            String key = KitchenItemMerger.key(item);
            if (key.isEmpty()) key = "row:" + index + ":" + normalize(item.displayName(false));
            double old = result.containsKey(key) ? result.get(key) : 0d;
            result.put(key, old + Math.max(0d, item.qty));
            index++;
        }
        return result;
    }

    private static void addEvent(Meta meta, String type, String employee,
                                 String detail, long at) {
        if (meta == null) return;
        if (!meta.events.isEmpty()) {
            Event last = meta.events.get(meta.events.size() - 1);
            if (type.equals(last.type) && clean(detail).equals(clean(last.detail))
                    && Math.abs(at - last.at) < 1500L) return;
        }
        Event event = new Event();
        event.type = type;
        event.employee = clean(employee);
        event.detail = clean(detail);
        event.at = at > 0L ? at : System.currentTimeMillis();
        meta.events.add(event);
        while (meta.events.size() > MAX_EVENTS_PER_ORDER) meta.events.remove(0);
    }

    private void load() {
        metas.clear();
        try {
            JSONArray array = new JSONArray(preferences.getString(ORDERS, "[]"));
            for (int i = 0; i < array.length(); i++) {
                JSONObject object = array.optJSONObject(i);
                if (object == null) continue;
                Meta meta = new Meta();
                meta.id = object.optString("id", "");
                if (meta.id.isEmpty()) continue;
                meta.invoice = object.optString("invoice", "");
                meta.employee = object.optString("employee", "");
                meta.firstSeenAt = object.optLong("firstSeenAt", 0L);
                meta.lastSeenAt = object.optLong("lastSeenAt", meta.firstSeenAt);
                meta.additionsAt = object.optLong("additionsAt", 0L);
                meta.pendingAdditions = object.optInt("pendingAdditions", 0);
                JSONObject quantities = object.optJSONObject("quantities");
                if (quantities != null) {
                    JSONArray names = quantities.names();
                    if (names != null) for (int n = 0; n < names.length(); n++) {
                        String key = names.optString(n, "");
                        if (!key.isEmpty()) meta.quantities.put(key, quantities.optDouble(key, 0d));
                    }
                }
                JSONArray events = object.optJSONArray("events");
                if (events != null) for (int n = 0; n < events.length(); n++) {
                    JSONObject e = events.optJSONObject(n);
                    if (e == null) continue;
                    Event event = new Event();
                    event.type = e.optString("type", "");
                    event.employee = e.optString("employee", "");
                    event.detail = e.optString("detail", "");
                    event.at = e.optLong("at", 0L);
                    meta.events.add(event);
                }
                metas.put(meta.id, meta);
            }
        } catch (Throwable ignored) {
            metas.clear();
        }
    }

    private void persist() {
        try {
            JSONArray array = new JSONArray();
            for (Meta meta : metas.values()) {
                JSONObject object = new JSONObject();
                object.put("id", meta.id);
                object.put("invoice", meta.invoice);
                object.put("employee", meta.employee);
                object.put("firstSeenAt", meta.firstSeenAt);
                object.put("lastSeenAt", meta.lastSeenAt);
                object.put("additionsAt", meta.additionsAt);
                object.put("pendingAdditions", meta.pendingAdditions);
                JSONObject quantities = new JSONObject();
                for (Map.Entry<String, Double> entry : meta.quantities.entrySet()) {
                    quantities.put(entry.getKey(), entry.getValue());
                }
                object.put("quantities", quantities);
                JSONArray events = new JSONArray();
                for (Event event : meta.events) {
                    JSONObject e = new JSONObject();
                    e.put("type", event.type);
                    e.put("employee", event.employee);
                    e.put("detail", event.detail);
                    e.put("at", event.at);
                    events.put(e);
                }
                object.put("events", events);
                array.put(object);
            }
            preferences.edit().putString(ORDERS, array.toString()).apply();
        } catch (Throwable ignored) { }
    }

    private void prune(long now) {
        boolean changed = false;
        ArrayList<String> remove = new ArrayList<>();
        for (Map.Entry<String, Meta> entry : metas.entrySet()) {
            Meta meta = entry.getValue();
            if (meta.lastSeenAt > 0L && now - meta.lastSeenAt > META_RETENTION_MS
                    && meta.pendingAdditions <= 0) remove.add(entry.getKey());
        }
        for (String id : remove) { metas.remove(id); changed = true; }
        if (changed) persist();
    }

    private static String eventLabel(String type, boolean ar) {
        if ("CREATED".equals(type)) return ar ? "تم إنشاء الطلب" : "Order created";
        if ("PREPARING".equals(type)) return ar ? "بدأ التحضير" : "Preparing started";
        if ("READY".equals(type)) return ar ? "أصبح جاهزًا" : "Marked ready";
        if ("COMPLETED".equals(type)) return ar ? "تم التسليم" : "Completed";
        if ("CANCELLED".equals(type)) return ar ? "تم الإلغاء" : "Cancelled";
        if ("ADDITIONAL_ITEMS".equals(type)) return ar ? "أصناف إضافية" : "Additional items";
        if ("ADDITIONS_ACKNOWLEDGED".equals(type)) return ar ? "تمت مراجعة الإضافات" : "Additions reviewed";
        if ("API_SYNCED".equals(type)) return ar ? "تمت مزامنة الحالة" : "Status synced";
        if ("API_PENDING".equals(type)) return ar ? "الحالة بانتظار المزامنة" : "Status pending sync";
        return type;
    }

    private static String formatTime(long at) {
        if (at <= 0L) return "--:--";
        java.text.SimpleDateFormat format = new java.text.SimpleDateFormat("HH:mm:ss", Locale.US);
        return format.format(new java.util.Date(at));
    }

    private static String clean(String value) { return value == null ? "" : value.trim(); }
    private static String normalize(String value) {
        return clean(value).toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }
}

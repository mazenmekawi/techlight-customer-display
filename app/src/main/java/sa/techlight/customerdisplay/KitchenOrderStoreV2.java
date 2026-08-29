package sa.techlight.customerdisplay;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;

/** Durable kitchen queue with fixed lifecycle timestamps for history analytics. */
public final class KitchenOrderStoreV2 {
    private static final String PREF = "techpro_kitchen_queue_v2";
    private static final int MAX_HISTORY = 180;

    private final SharedPreferences preferences;
    private final LinkedHashMap<String, KitchenOrder> active = new LinkedHashMap<>();
    private final ArrayList<KitchenOrder> history = new ArrayList<>();

    public KitchenOrderStoreV2(Context context) {
        preferences = context.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        load();
    }

    public synchronized List<KitchenOrder> active() {
        ArrayList<KitchenOrder> result = copies(active.values());
        Collections.sort(result, Comparator.comparingLong(value -> value.createdAt));
        return result;
    }

    public synchronized List<KitchenOrder> history() {
        ArrayList<KitchenOrder> result = new ArrayList<>();
        for (KitchenOrder order : history) result.add(order.copy());
        return result;
    }

    public synchronized KitchenOrder find(String id) {
        String key = KitchenSignalV2.cleanIdentity(id);
        if (key.isEmpty()) return null;
        KitchenOrder order = active.get(key);
        return order == null ? null : order.copy();
    }

    public synchronized KitchenOrder findByNumber(String number) {
        String target = KitchenSignalV2.cleanIdentity(number);
        if (target.isEmpty()) return null;
        for (KitchenOrder order : active.values()) {
            if (target.equalsIgnoreCase(KitchenSignalV2.cleanIdentity(order.displayNumber))) return order.copy();
        }
        return null;
    }

    public synchronized boolean upsert(KitchenOrder incoming) {
        if (incoming == null) return false;
        String id = KitchenSignalV2.cleanIdentity(incoming.id);
        if (id.isEmpty()) return false;
        incoming.id = id;
        long now = System.currentTimeMillis();
        KitchenOrder previous = active.get(id);
        if (previous == null) {
            KitchenOrder fresh = incoming.copy();
            fresh.createdAt = fresh.createdAt > 0 ? fresh.createdAt : now;
            fresh.updatedAt = now;
            fresh.changedAt = 0L;
            fresh.startedAt = 0L;
            fresh.readyAt = 0L;
            fresh.kitchenStatus = KitchenOrder.Status.NEW;
            active.put(id, fresh);
            persist();
            return true;
        }

        KitchenOrder merged = previous.copy();
        String before = merged.contentSignature();
        if (KitchenSignalV2.valid(incoming.displayNumber)) merged.displayNumber = incoming.displayNumber;
        if (!clean(incoming.table).isEmpty()) merged.table = incoming.table;
        if (!clean(incoming.orderType).isEmpty()) merged.orderType = incoming.orderType;
        if (!clean(incoming.customerNote).isEmpty()) merged.customerNote = incoming.customerNote;
        if (!clean(incoming.paymentStatus).isEmpty()) merged.paymentStatus = incoming.paymentStatus;
        if (!clean(incoming.rawStatus).isEmpty()) merged.rawStatus = incoming.rawStatus;
        if (!incoming.items.isEmpty()) {
            merged.items.clear();
            for (KitchenOrder.Item item : incoming.items) merged.items.add(KitchenOrder.copyItem(item));
        }
        String after = merged.contentSignature();
        boolean changed = !before.equals(after);
        merged.updatedAt = now;
        merged.revision = Math.max(previous.revision + (changed ? 1 : 0), incoming.revision);
        if (changed) merged.changedAt = now;
        merged.inferredTemporarySave = previous.inferredTemporarySave || incoming.inferredTemporarySave;
        merged.temporaryOrder = previous.temporaryOrder || incoming.temporaryOrder;
        active.put(id, merged);
        persist();
        return changed;
    }

    public synchronized void updatePayment(String id, String payment) {
        KitchenOrder order = active.get(KitchenSignalV2.cleanIdentity(id));
        if (order == null) return;
        order.paymentStatus = clean(payment).isEmpty() ? "PAID" : payment;
        order.updatedAt = System.currentTimeMillis();
        persist();
    }

    public synchronized void setStatus(String id, KitchenOrder.Status status) {
        KitchenOrder order = active.get(KitchenSignalV2.cleanIdentity(id));
        if (order == null) return;
        long now = System.currentTimeMillis();
        order.kitchenStatus = status;
        order.updatedAt = now;
        if (status == KitchenOrder.Status.PREPARING && order.startedAt <= 0L) {
            order.startedAt = now;
        }
        if (status == KitchenOrder.Status.READY && order.readyAt <= 0L) {
            if (order.startedAt <= 0L) order.startedAt = now;
            order.readyAt = now;
        }
        if (status == KitchenOrder.Status.DONE) {
            if (order.startedAt <= 0L) order.startedAt = now;
            if (order.readyAt <= 0L) order.readyAt = now;
            active.remove(order.id);
            KitchenOrder archived = order.copy();
            archived.updatedAt = now; // fixed completion timestamp; never moves in history.
            history.add(0, archived);
            trimHistory();
        }
        persist();
    }

    public synchronized void cancel(String id) {
        KitchenOrder order = active.get(KitchenSignalV2.cleanIdentity(id));
        if (order == null) return;
        long now = System.currentTimeMillis();
        order.kitchenStatus = KitchenOrder.Status.CANCELLED;
        order.updatedAt = now;
        order.changedAt = now;
        persist();
    }

    public synchronized KitchenOrder recallLast() {
        if (history.isEmpty()) return null;
        KitchenOrder order = history.remove(0);
        order.kitchenStatus = KitchenOrder.Status.READY;
        order.updatedAt = System.currentTimeMillis();
        active.put(order.id, order);
        persist();
        return order.copy();
    }

    public synchronized int count(KitchenOrder.Status status) {
        int result = 0;
        for (KitchenOrder order : active.values()) if (order.kitchenStatus == status) result++;
        return result;
    }

    private void load() {
        active.clear();
        history.clear();
        try {
            JSONArray rows = new JSONArray(preferences.getString("active", "[]"));
            for (int i = 0; i < rows.length(); i++) {
                JSONObject json = rows.optJSONObject(i);
                if (json == null) continue;
                KitchenOrder order = KitchenOrder.fromJson(json);
                order.id = KitchenSignalV2.cleanIdentity(order.id);
                order.displayNumber = KitchenSignalV2.cleanIdentity(order.displayNumber);
                if (!order.id.isEmpty()) active.put(order.id, order);
            }
            JSONArray old = new JSONArray(preferences.getString("history", "[]"));
            for (int i = 0; i < old.length() && history.size() < MAX_HISTORY; i++) {
                JSONObject json = old.optJSONObject(i);
                if (json != null) history.add(KitchenOrder.fromJson(json));
            }
        } catch (Exception ignored) {
            active.clear();
            history.clear();
        }
    }

    private void persist() {
        try {
            JSONArray rows = new JSONArray();
            for (KitchenOrder order : active.values()) rows.put(order.toJson());
            JSONArray old = new JSONArray();
            for (KitchenOrder order : history) old.put(order.toJson());
            preferences.edit().putString("active", rows.toString()).putString("history", old.toString()).apply();
        } catch (Exception ignored) { }
    }

    private void trimHistory() {
        while (history.size() > MAX_HISTORY) history.remove(history.size() - 1);
    }

    private static ArrayList<KitchenOrder> copies(Iterable<KitchenOrder> values) {
        ArrayList<KitchenOrder> result = new ArrayList<>();
        for (KitchenOrder order : values) result.add(order.copy());
        return result;
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}

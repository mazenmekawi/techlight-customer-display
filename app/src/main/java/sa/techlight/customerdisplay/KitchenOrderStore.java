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
import java.util.Map;

/** Durable queue for active and recently completed kitchen tickets. */
public final class KitchenOrderStore {
    private static final String PREF = "techpro_kitchen_queue_v1";
    private static final int MAX_HISTORY = 150;

    private final SharedPreferences preferences;
    private final LinkedHashMap<String, KitchenOrder> active = new LinkedHashMap<>();
    private final ArrayList<KitchenOrder> history = new ArrayList<>();

    public KitchenOrderStore(Context context) {
        preferences = context.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        load();
    }

    public synchronized List<KitchenOrder> active() {
        ArrayList<KitchenOrder> result = new ArrayList<>();
        for (KitchenOrder order : active.values()) result.add(order.copy());
        Collections.sort(result, Comparator.comparingLong(value -> value.createdAt));
        return result;
    }

    public synchronized List<KitchenOrder> history() {
        ArrayList<KitchenOrder> result = new ArrayList<>();
        for (KitchenOrder order : history) result.add(order.copy());
        return result;
    }

    public synchronized KitchenOrder find(String id) {
        KitchenOrder value = active.get(id);
        return value == null ? null : value.copy();
    }

    public synchronized KitchenOrder findByNumber(String number) {
        String target = KitchenOrder.meaningfulNumber(number);
        if (target.isEmpty()) return null;
        for (KitchenOrder order : active.values()) {
            if (target.equalsIgnoreCase(KitchenOrder.meaningfulNumber(order.displayNumber))) return order.copy();
        }
        return null;
    }

    /** Upserts one authoritative saved ticket. Payment-only messages never wipe line items. */
    public synchronized boolean upsert(KitchenOrder incoming) {
        if (incoming == null || KitchenOrder.clean(incoming.id).isEmpty()) return false;
        long now = System.currentTimeMillis();
        KitchenOrder previous = active.get(incoming.id);
        if (previous == null && !KitchenOrder.meaningfulNumber(incoming.displayNumber).isEmpty()) {
            for (Map.Entry<String, KitchenOrder> entry : active.entrySet()) {
                if (incoming.displayNumber.equalsIgnoreCase(KitchenOrder.meaningfulNumber(entry.getValue().displayNumber))) {
                    previous = entry.getValue();
                    incoming.id = entry.getKey();
                    break;
                }
            }
        }
        if (previous == null) {
            incoming.createdAt = incoming.createdAt > 0 ? incoming.createdAt : now;
            incoming.updatedAt = now;
            incoming.revision = Math.max(1, incoming.revision);
            active.put(incoming.id, incoming.copy());
            persist();
            return true;
        }

        KitchenOrder merged = previous.copy();
        String before = merged.contentSignature();
        if (!KitchenOrder.meaningfulNumber(incoming.displayNumber).isEmpty()) merged.displayNumber = incoming.displayNumber;
        if (!KitchenOrder.clean(incoming.table).isEmpty()) merged.table = incoming.table;
        if (!KitchenOrder.clean(incoming.orderType).isEmpty()) merged.orderType = incoming.orderType;
        if (!KitchenOrder.clean(incoming.customerNote).isEmpty()) merged.customerNote = incoming.customerNote;
        if (!KitchenOrder.clean(incoming.paymentStatus).isEmpty()) merged.paymentStatus = incoming.paymentStatus;
        if (!KitchenOrder.clean(incoming.rawStatus).isEmpty()) merged.rawStatus = incoming.rawStatus;
        if (!incoming.items.isEmpty()) {
            merged.items.clear();
            for (KitchenOrder.Item item : incoming.items) merged.items.add(KitchenOrder.copyItem(item));
        }
        merged.updatedAt = now;
        merged.revision = Math.max(previous.revision + 1, incoming.revision);
        merged.temporaryOrder = previous.temporaryOrder || incoming.temporaryOrder;
        merged.inferredTemporarySave = previous.inferredTemporarySave || incoming.inferredTemporarySave;
        if (!before.equals(merged.contentSignature())) merged.changedAt = now;
        active.put(merged.id, merged);
        persist();
        return !before.equals(merged.contentSignature());
    }

    public synchronized void updatePayment(String id, String payment) {
        KitchenOrder order = active.get(id);
        if (order == null) return;
        order.paymentStatus = KitchenOrder.clean(payment).isEmpty() ? "PAID" : payment;
        order.updatedAt = System.currentTimeMillis();
        persist();
    }

    public synchronized void setStatus(String id, KitchenOrder.Status status) {
        KitchenOrder order = active.get(id);
        if (order == null) return;
        long now = System.currentTimeMillis();
        order.kitchenStatus = status;
        order.updatedAt = now;
        if (status == KitchenOrder.Status.PREPARING && order.startedAt <= 0) order.startedAt = now;
        if (status == KitchenOrder.Status.READY && order.readyAt <= 0) order.readyAt = now;
        if (status == KitchenOrder.Status.NEW) {
            order.startedAt = 0L;
            order.readyAt = 0L;
        }
        if (status == KitchenOrder.Status.DONE) {
            active.remove(id);
            history.add(0, order.copy());
            while (history.size() > MAX_HISTORY) history.remove(history.size() - 1);
        }
        persist();
    }

    public synchronized void cancel(String id) {
        KitchenOrder order = active.get(id);
        if (order == null) return;
        order.kitchenStatus = KitchenOrder.Status.CANCELLED;
        order.updatedAt = System.currentTimeMillis();
        order.changedAt = order.updatedAt;
        persist();
    }

    public synchronized KitchenOrder recall(String id) {
        if (KitchenOrder.clean(id).isEmpty()) return null;
        for (int i = 0; i < history.size(); i++) {
            KitchenOrder order = history.get(i);
            if (!id.equals(order.id)) continue;
            history.remove(i);
            order.kitchenStatus = KitchenOrder.Status.READY;
            order.updatedAt = System.currentTimeMillis();
            active.put(order.id, order.copy());
            persist();
            return order.copy();
        }
        return null;
    }

    public synchronized KitchenOrder recallLast() {
        if (history.isEmpty()) return null;
        return recall(history.get(0).id);
    }

    public synchronized int count(KitchenOrder.Status status) {
        int count = 0;
        for (KitchenOrder order : active.values()) if (order.kitchenStatus == status) count++;
        return count;
    }

    private void load() {
        active.clear();
        history.clear();
        try {
            JSONArray rows = new JSONArray(preferences.getString("active", "[]"));
            for (int i = 0; i < rows.length(); i++) {
                JSONObject value = rows.optJSONObject(i);
                if (value == null) continue;
                KitchenOrder order = KitchenOrder.fromJson(value);
                if (!KitchenOrder.clean(order.id).isEmpty()) active.put(order.id, order);
            }
            JSONArray old = new JSONArray(preferences.getString("history", "[]"));
            for (int i = 0; i < old.length() && history.size() < MAX_HISTORY; i++) {
                JSONObject value = old.optJSONObject(i);
                if (value != null) history.add(KitchenOrder.fromJson(value));
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
}

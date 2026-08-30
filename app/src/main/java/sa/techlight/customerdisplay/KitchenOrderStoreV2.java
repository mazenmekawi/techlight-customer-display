package sa.techlight.customerdisplay;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;

/** Durable kitchen queue with fixed lifecycle timestamps, account isolation and bounded history retention. */
public final class KitchenOrderStoreV2 {
    private static final String PREF = "techpro_kitchen_queue_v2";
    private static final int MAX_HISTORY = 1500;
    private static final long HISTORY_RETENTION_MS = 2L * 24L * 60L * 60L * 1000L;
    private static final long DEFAULT_PROMOTION_WINDOW_MS = 20_000L;

    private final SharedPreferences preferences;
    private final LinkedHashMap<String, KitchenOrder> active = new LinkedHashMap<>();
    private final ArrayList<KitchenOrder> history = new ArrayList<>();

    public KitchenOrderStoreV2(Context context) {
        this(context, "");
    }

    /**
     * Scope isolates active/history data by TechPro POS account so changing login can never surface
     * a ticket left behind by another point/user. Old unscoped V4 data is deliberately not migrated.
     */
    public KitchenOrderStoreV2(Context context, String scope) {
        String cleanScope = clean(scope);
        String name = cleanScope.isEmpty() ? PREF : PREF + "_" + scopeKey(cleanScope);
        preferences = context.getSharedPreferences(name, Context.MODE_PRIVATE);
        load();
    }

    public synchronized List<KitchenOrder> active() {
        ArrayList<KitchenOrder> result = copies(active.values());
        Collections.sort(result, Comparator.comparingLong(value -> value.createdAt));
        return result;
    }

    public synchronized List<KitchenOrder> history() {
        if (pruneHistory(System.currentTimeMillis())) persist();
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

    public synchronized KitchenOrder findPromotableWeak(KitchenOrder incoming) {
        return findPromotableWeak(incoming, DEFAULT_PROMOTION_WINDOW_MS);
    }

    public synchronized KitchenOrder findPromotableWeak(KitchenOrder incoming, long maxAgeMs) {
        String key = promotableWeakKey(incoming, System.currentTimeMillis(), maxAgeMs);
        KitchenOrder order = key.isEmpty() ? null : active.get(key);
        return order == null ? null : order.copy();
    }

    public synchronized boolean upsert(KitchenOrder incoming) {
        if (incoming == null) return false;
        String id = KitchenSignalV2.cleanIdentity(incoming.id);
        if (id.isEmpty()) return false;
        incoming.id = id;
        long now = System.currentTimeMillis();
        KitchenOrder previous = active.get(id);

        if (previous == null && isStrongId(id) && !incoming.items.isEmpty()) {
            String weakKey = promotableWeakKey(incoming, now, DEFAULT_PROMOTION_WINDOW_MS);
            if (!weakKey.isEmpty()) {
                KitchenOrder weak = active.remove(weakKey);
                if (weak != null) {
                    KitchenOrder promoted = promote(weak, incoming, id, now);
                    active.put(id, promoted);
                    persist();
                    return materiallyDifferent(weak, promoted);
                }
            }
        }

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

        KitchenOrder merged = mergeExisting(previous, incoming, now);
        boolean changed = materiallyDifferent(previous, merged);
        if (!changed) return false; // no disk write and no UI invalidation for identical cloud polls.
        active.put(id, merged);
        persist();
        return true;
    }

    private KitchenOrder mergeExisting(KitchenOrder previous, KitchenOrder incoming, long now) {
        KitchenOrder merged = previous.copy();
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
        boolean changed = materiallyDifferent(previous, merged);
        if (changed) {
            merged.updatedAt = now;
            merged.changedAt = now;
            merged.revision = Math.max(previous.revision + 1, incoming.revision);
        } else {
            merged.updatedAt = previous.updatedAt;
            merged.changedAt = previous.changedAt;
            merged.revision = previous.revision;
        }
        merged.inferredTemporarySave = previous.inferredTemporarySave || incoming.inferredTemporarySave;
        merged.temporaryOrder = incoming.rawStatus != null && incoming.rawStatus.equals("POS_INVOICE")
                ? false : (previous.temporaryOrder || incoming.temporaryOrder);
        return merged;
    }

    private KitchenOrder promote(KitchenOrder weak, KitchenOrder incoming, String strongId, long now) {
        KitchenOrder promoted = weak.copy();
        promoted.id = strongId;
        if (KitchenSignalV2.valid(incoming.displayNumber)) promoted.displayNumber = incoming.displayNumber;
        if (!clean(incoming.table).isEmpty()) promoted.table = incoming.table;
        if (!clean(incoming.orderType).isEmpty()) promoted.orderType = incoming.orderType;
        if (!clean(incoming.customerNote).isEmpty()) promoted.customerNote = incoming.customerNote;
        if (!clean(incoming.paymentStatus).isEmpty()) promoted.paymentStatus = incoming.paymentStatus;
        if (!clean(incoming.rawStatus).isEmpty()) promoted.rawStatus = incoming.rawStatus;
        if (!incoming.items.isEmpty()) {
            promoted.items.clear();
            for (KitchenOrder.Item item : incoming.items) promoted.items.add(KitchenOrder.copyItem(item));
        }
        promoted.updatedAt = now;
        promoted.revision = Math.max(weak.revision, incoming.revision);
        promoted.temporaryOrder = incoming.rawStatus != null && incoming.rawStatus.equals("POS_INVOICE")
                ? false : true;
        promoted.inferredTemporarySave = weak.inferredTemporarySave || incoming.inferredTemporarySave;
        return promoted;
    }

    private static boolean materiallyDifferent(KitchenOrder a, KitchenOrder b) {
        if (a == b) return false;
        if (a == null || b == null) return true;
        if (!clean(a.displayNumber).equals(clean(b.displayNumber))) return true;
        if (!clean(a.paymentStatus).equals(clean(b.paymentStatus))) return true;
        if (!clean(a.rawStatus).equals(clean(b.rawStatus))) return true;
        if (a.temporaryOrder != b.temporaryOrder) return true;
        return !a.contentSignature().equals(b.contentSignature());
    }

    private String promotableWeakKey(KitchenOrder incoming, long now, long maxAgeMs) {
        if (incoming == null || incoming.items.isEmpty()) return "";
        String strongId = KitchenSignalV2.cleanIdentity(incoming.id);
        String strongNumber = KitchenSignalV2.cleanIdentity(incoming.displayNumber);
        if (!isStrongId(strongId) && strongNumber.isEmpty()) return "";

        String bestKey = "";
        long bestCreated = Long.MIN_VALUE;
        for (java.util.Map.Entry<String, KitchenOrder> entry : active.entrySet()) {
            String key = entry.getKey();
            KitchenOrder candidate = entry.getValue();
            if (!isWeakId(key) || candidate == null) continue;
            long age = now - candidate.createdAt;
            if (age < 0L || age > Math.max(2_000L, maxAgeMs)) continue;
            if (!metadataCompatible(candidate, incoming)) continue;
            if (!sameCart(candidate, incoming)) continue;
            if (candidate.createdAt > bestCreated) {
                bestCreated = candidate.createdAt;
                bestKey = key;
            }
        }
        return bestKey;
    }

    private static boolean metadataCompatible(KitchenOrder a, KitchenOrder b) {
        String ta = normalized(a.table);
        String tb = normalized(b.table);
        if (!ta.isEmpty() && !tb.isEmpty() && !ta.equals(tb)) return false;
        String oa = normalizedType(a.orderType);
        String ob = normalizedType(b.orderType);
        return oa.isEmpty() || ob.isEmpty() || oa.equals(ob);
    }

    private static boolean sameCart(KitchenOrder a, KitchenOrder b) {
        if (a == null || b == null || a.items.isEmpty() || b.items.isEmpty()) return false;
        if (a.items.size() != b.items.size()) return false;
        ArrayList<String> left = cartFingerprints(a.items);
        ArrayList<String> right = cartFingerprints(b.items);
        Collections.sort(left);
        Collections.sort(right);
        return left.equals(right);
    }

    private static ArrayList<String> cartFingerprints(List<KitchenOrder.Item> items) {
        ArrayList<String> out = new ArrayList<>();
        for (KitchenOrder.Item item : items) {
            String identity = item.itemId > 0L ? "id:" + item.itemId : "name:" + normalized(item.name);
            String qty = String.format(Locale.US, "%.3f", item.qty);
            out.add(identity + "|q:" + qty);
        }
        return out;
    }

    private static boolean isWeakId(String id) {
        return clean(id).toLowerCase(Locale.US).startsWith("weak-");
    }

    private static boolean isStrongId(String id) {
        String value = KitchenSignalV2.cleanIdentity(id);
        return !value.isEmpty() && !isWeakId(value);
    }

    private static String normalized(String value) {
        return clean(value).toLowerCase(Locale.US).replaceAll("\\s+", " ");
    }

    private static String normalizedType(String value) {
        String v = normalized(value).replace("_", "").replace("-", "").replace(" ", "");
        if (v.contains("dinein") || v.contains("local") || v.contains("محلي")) return "dinein";
        if (v.contains("takeaway") || v.contains("takeout") || v.contains("pickup") || v.contains("سفري")) return "takeaway";
        if (v.contains("delivery") || v.contains("توصيل")) return "delivery";
        return v;
    }

    public synchronized void updatePayment(String id, String payment) {
        KitchenOrder order = active.get(KitchenSignalV2.cleanIdentity(id));
        if (order == null) return;
        String next = clean(payment).isEmpty() ? "PAID" : payment;
        if (next.equals(clean(order.paymentStatus))) return;
        order.paymentStatus = next;
        order.updatedAt = System.currentTimeMillis();
        persist();
    }

    public synchronized void setStatus(String id, KitchenOrder.Status status) {
        KitchenOrder order = active.get(KitchenSignalV2.cleanIdentity(id));
        if (order == null) return;
        long now = System.currentTimeMillis();
        order.kitchenStatus = status;
        order.updatedAt = now;
        if (status == KitchenOrder.Status.PREPARING && order.startedAt <= 0L) order.startedAt = now;
        if (status == KitchenOrder.Status.READY && order.readyAt <= 0L) {
            if (order.startedAt <= 0L) order.startedAt = now;
            order.readyAt = now;
        }
        if (status == KitchenOrder.Status.DONE) {
            if (order.startedAt <= 0L) order.startedAt = now;
            if (order.readyAt <= 0L) order.readyAt = now;
            active.remove(order.id);
            KitchenOrder archived = order.copy();
            archived.updatedAt = now;
            history.add(0, archived);
            trimHistory(now);
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
        if (pruneHistory(System.currentTimeMillis())) persist();
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

    public synchronized void clearActive() { active.clear(); persist(); }
    public synchronized void clearHistory() { history.clear(); persist(); }
    public synchronized void clearAll() { active.clear(); history.clear(); persist(); }

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
            for (int i = 0; i < old.length(); i++) {
                JSONObject json = old.optJSONObject(i);
                if (json != null) history.add(KitchenOrder.fromJson(json));
            }
            if (pruneHistory(System.currentTimeMillis())) persist();
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

    private void trimHistory(long now) { pruneHistory(now); }

    private boolean pruneHistory(long now) {
        boolean changed = false;
        long cutoff = now - HISTORY_RETENTION_MS;
        for (int i = history.size() - 1; i >= 0; i--) {
            KitchenOrder order = history.get(i);
            long completedAt = order.updatedAt > 0L ? order.updatedAt : order.readyAt;
            if (completedAt > 0L && completedAt < cutoff) {
                history.remove(i);
                changed = true;
            }
        }
        while (history.size() > MAX_HISTORY) {
            history.remove(history.size() - 1);
            changed = true;
        }
        return changed;
    }

    private static ArrayList<KitchenOrder> copies(Iterable<KitchenOrder> values) {
        ArrayList<KitchenOrder> result = new ArrayList<>();
        for (KitchenOrder order : values) result.add(order.copy());
        return result;
    }

    private static String scopeKey(String scope) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(scope.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder();
            for (int i = 0; i < 8; i++) out.append(String.format(Locale.US, "%02x", digest[i] & 0xff));
            return out.toString();
        } catch (Exception ignored) {
            return Integer.toHexString(scope.hashCode());
        }
    }

    private static String clean(String value) { return value == null ? "" : value.trim(); }
}

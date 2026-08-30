from pathlib import Path

poller_path = Path('app/src/main/java/sa/techlight/customerdisplay/KitchenCloudOrdersPoller.java')
poller = poller_path.read_text(encoding='utf-8')
activity_path = Path('app/src/main/java/sa/techlight/customerdisplay/KitchenActivityV42.java')
activity = activity_path.read_text(encoding='utf-8')
order_path = Path('app/src/main/java/sa/techlight/customerdisplay/KitchenOrder.java')
order = order_path.read_text(encoding='utf-8')


def replace_once(text, old, new, label):
    if old not in text:
        raise SystemExit(f'V4.6 patch target not found: {label}')
    return text.replace(old, new, 1)

# ---------- Cloud transport: session baseline + deltas only ----------
if 'import java.util.Collections;' not in poller:
    poller = poller.replace('import java.util.ArrayList;\n', 'import java.util.ArrayList;\nimport java.util.Collections;\n', 1)

poller = replace_once(
    poller,
    '    private static final long RECENT_BASELINE_WINDOW_MS = 120_000L;\n',
    '    private static final long RECENT_BASELINE_WINDOW_MS = 120_000L;\n    private static final long SESSION_GRACE_MS = 5_000L;\n',
    'session grace constant'
)

poller = replace_once(
    poller,
    '    private final String posCode;\n    private final Listener listener;\n',
    '    private final String posCode;\n    private final long sessionLoginAt;\n    private final Listener listener;\n    private final TemporaryDeltaTracker temporaryTracker;\n',
    'session fields'
)

old_constructor = '''    public KitchenCloudOrdersPoller(String token, String posCode, Listener listener) {
        this.token = stripBearer(token);
        this.posCode = clean(posCode);
        this.listener = listener;
    }
'''
new_constructor = '''    public KitchenCloudOrdersPoller(String token, String posCode, Listener listener) {
        this(token, posCode, System.currentTimeMillis(), listener);
    }

    public KitchenCloudOrdersPoller(String token, String posCode, long sessionLoginAt, Listener listener) {
        this.token = stripBearer(token);
        this.posCode = clean(posCode);
        this.sessionLoginAt = sessionLoginAt > 0L ? sessionLoginAt : startedAt;
        this.listener = listener;
        long baselineCutoff = Math.max(
                this.sessionLoginAt - SESSION_GRACE_MS,
                startedAt - RECENT_BASELINE_WINDOW_MS
        );
        this.temporaryTracker = new TemporaryDeltaTracker(baselineCutoff);
    }
'''
poller = replace_once(poller, old_constructor, new_constructor, 'session-aware poller constructor')

old_temp_fetch = '''            String tempRaw = fetchTemporaryOrders();
            List<KitchenTemporaryOrdersApiClient.Candidate> tempCandidates = KitchenTemporaryOrdersApiClient.parseCandidates(tempRaw);
            List<KitchenOrder> temporary = convertTemporary(tempCandidates, posCode, orderTypeNames);

            ArrayList<KitchenOrder> combined = new ArrayList<>(temporary);
'''
new_temp_fetch = '''            String tempRaw = fetchTemporaryOrders();
            List<KitchenTemporaryOrdersApiClient.Candidate> tempCandidates = KitchenTemporaryOrdersApiClient.parseCandidates(tempRaw);
            List<KitchenOrder> temporaryAll = convertTemporary(tempCandidates, posCode, orderTypeNames);
            List<KitchenOrder> temporary = temporaryTracker.select(temporaryAll);

            ArrayList<KitchenOrder> combined = new ArrayList<>(temporary);
'''
poller = replace_once(poller, old_temp_fetch, new_temp_fetch, 'temporary delta selection')

old_detail = '''            String detail = "Cloud official API • temp=" + temporary.size() + " • paidNew=" + paidNew
                    + " • " + invoiceDetail + " • " + elapsed + "ms";
'''
new_detail = '''            String detail = "Cloud official API • tempActive=" + temporaryAll.size()
                    + " • tempDelta=" + temporary.size() + " • paidNew=" + paidNew
                    + " • " + invoiceDetail + " • " + elapsed + "ms";
'''
poller = replace_once(poller, old_detail, new_detail, 'cloud detail counts')

old_paid_baseline = '''                if (invoice.createdAt > 0L && invoice.createdAt >= startedAt - RECENT_BASELINE_WINDOW_MS) {
'''
new_paid_baseline = '''                long baselineCutoff = Math.max(
                        sessionLoginAt - SESSION_GRACE_MS,
                        startedAt - RECENT_BASELINE_WINDOW_MS
                );
                if (invoice.createdAt > 0L && invoice.createdAt >= baselineCutoff) {
'''
poller = replace_once(poller, old_paid_baseline, new_paid_baseline, 'paid invoice session baseline')

old_order_date = '''            if (c.orderDate > 0L) {
                order.createdAt = c.orderDate;
                order.updatedAt = c.orderDate;
            }
'''
new_order_date = '''            if (c.orderDate > 0L) {
                order.createdAt = c.orderDate;
                order.updatedAt = c.orderDate;
            } else {
                // Unknown-date records are part of the first cloud baseline, never guessed as new.
                order.createdAt = 0L;
                order.updatedAt = 0L;
            }
'''
poller = replace_once(poller, old_order_date, new_order_date, 'unknown temporary order date handling')

tracker_anchor = '    private static void collectOrderTypes(Object raw, Map<Long, String> output, int depth) {\n'
if tracker_anchor not in poller:
    raise SystemExit('V4.6 tracker insertion anchor missing')
tracker_code = r'''    /**
     * Emits only new or materially changed temporary orders. The first successful cloud response is a
     * baseline: orders that pre-date the current login remain silent, so logout/login cannot resurrect
     * a previously parked invoice. Existing local tickets remain visible from the scoped local store.
     */
    static final class TemporaryDeltaTracker {
        private final long baselineCutoff;
        private final HashMap<String, String> seen = new HashMap<>();
        private boolean baselineReady;

        TemporaryDeltaTracker(long baselineCutoff) {
            this.baselineCutoff = baselineCutoff;
        }

        synchronized List<KitchenOrder> select(List<KitchenOrder> current) {
            ArrayList<KitchenOrder> delta = new ArrayList<>();
            if (current == null) current = Collections.emptyList();

            if (!baselineReady) {
                for (KitchenOrder order : current) {
                    String number = order == null ? "" : order.bestNumber();
                    if (number.isEmpty()) continue;
                    seen.put(number, stableTicketFingerprint(order));
                    if (order.createdAt > 0L && order.createdAt >= baselineCutoff) {
                        delta.add(order.copy());
                    }
                }
                baselineReady = true;
                return delta;
            }

            for (KitchenOrder order : current) {
                String number = order == null ? "" : order.bestNumber();
                if (number.isEmpty()) continue;
                String fingerprint = stableTicketFingerprint(order);
                String previous = seen.put(number, fingerprint);
                if (previous == null || !previous.equals(fingerprint)) delta.add(order.copy());
            }
            return delta;
        }
    }

    static String stableTicketFingerprint(KitchenOrder order) {
        if (order == null) return "";
        ArrayList<String> rows = new ArrayList<>();
        for (KitchenOrder.Item item : order.items) {
            if (item != null) rows.add(item.signature());
        }
        Collections.sort(rows);
        StringBuilder out = new StringBuilder();
        out.append(clean(order.bestNumber())).append('|')
                .append(clean(order.table)).append('|')
                .append(clean(order.orderType)).append('|')
                .append(clean(order.customerNote)).append('|')
                .append(clean(order.paymentStatus)).append('|')
                .append(clean(order.rawStatus));
        for (String row : rows) out.append("||").append(row);
        return out.toString();
    }

'''
poller = poller.replace(tracker_anchor, tracker_code + tracker_anchor, 1)

# ---------- Stable signatures: backend row order must never trigger a redraw ----------
if 'import java.util.Collections;' not in order:
    order = order.replace('import java.util.ArrayList;\n', 'import java.util.ArrayList;\nimport java.util.Collections;\n', 1)
old_signature = '''    public String contentSignature() {
        StringBuilder out = new StringBuilder();
        out.append(clean(table)).append('|').append(clean(orderType)).append('|').append(clean(customerNote));
        for (Item item : items) out.append("||").append(item.signature());
        return out.toString();
    }
'''
new_signature = '''    public String contentSignature() {
        StringBuilder out = new StringBuilder();
        out.append(clean(table)).append('|').append(clean(orderType)).append('|').append(clean(customerNote));
        ArrayList<String> rows = new ArrayList<>();
        for (Item item : items) if (item != null) rows.add(item.signature());
        Collections.sort(rows);
        for (String row : rows) out.append("||").append(row);
        return out.toString();
    }
'''
order = replace_once(order, old_signature, new_signature, 'order-independent content signature')

# ---------- Activity: use persistent login time and clear on every authentication exit ----------
old_poller_call = '''            cloudPoller = new KitchenCloudOrdersPoller(
                    session == null ? "" : session.token(),
                    session == null ? "" : session.posCode(),
                    new KitchenCloudOrdersPoller.Listener() {
'''
new_poller_call = '''            cloudPoller = new KitchenCloudOrdersPoller(
                    session == null ? "" : session.token(),
                    session == null ? "" : session.posCode(),
                    session == null ? System.currentTimeMillis() : session.loginAt(),
                    new KitchenCloudOrdersPoller.Listener() {
'''
activity = replace_once(activity, old_poller_call, new_poller_call, 'activity session-aware cloud constructor')

old_unauthorized = '''                            if (session != null) session.clear();
                            openLogin();
'''
new_unauthorized = '''                            if (store != null) store.clearActive();
                            pendingSaved = null;
                            liveDraft = null;
                            if (session != null) session.clear();
                            openLogin();
'''
activity = replace_once(activity, old_unauthorized, new_unauthorized, 'clear active queue on expired session')

activity = activity.replace('TechPro Kitchen 4.5 Stable Cloud', 'TechPro Kitchen 4.6 Stable Cloud')

# Final invariants.
for required in [
        'TemporaryDeltaTracker',
        'stableTicketFingerprint',
        'sessionLoginAt - SESSION_GRACE_MS',
        'tempDelta=',
        'order.createdAt = 0L',
]:
    if required not in poller:
        raise SystemExit(f'V4.6 poller invariant missing: {required}')
for required in [
        'session.loginAt()',
        'if (store != null) store.clearActive()',
        'if (anyChange) renderBoard();',
        'TechPro Kitchen 4.6 Stable Cloud',
]:
    if required not in activity:
        raise SystemExit(f'V4.6 activity invariant missing: {required}')
if 'if (anyChange || (orders != null && !orders.isEmpty())) renderBoard();' in activity:
    raise SystemExit('V4.6 periodic full redraw returned')
if 'Collections.sort(rows);' not in order:
    raise SystemExit('V4.6 order signature is still row-order-sensitive')

poller_path.write_text(poller, encoding='utf-8')
activity_path.write_text(activity, encoding='utf-8')
order_path.write_text(order, encoding='utf-8')
print('TechPro Kitchen V4.6 session baseline + anti-flicker stability applied successfully')

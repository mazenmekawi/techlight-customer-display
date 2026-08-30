from pathlib import Path

activity_path = Path('app/src/main/java/sa/techlight/customerdisplay/KitchenActivityV42.java')
text = activity_path.read_text(encoding='utf-8')

# Official TechPro APK uses posapi.techlight.sa for login, TemporaryOrders and PosInvoice.
for path in [
    Path('app/src/main/java/sa/techlight/customerdisplay/TechProAccountClient.java'),
    Path('app/src/main/java/sa/techlight/customerdisplay/KitchenTemporaryOrdersApiClient.java'),
]:
    value = path.read_text(encoding='utf-8')
    value = value.replace('https://posapifornewapp.techlight.sa/api/', 'https://posapi.techlight.sa/api/')
    if 'posapifornewapp.techlight.sa' in value:
        raise SystemExit(f'V4.5 legacy API host remains in {path}')
    path.write_text(value, encoding='utf-8')

old_store = '            store = new KitchenOrderStoreV2(this);\n'
new_store = '            store = new KitchenOrderStoreV2(this, (session == null ? "" : session.posCode()) + "|" + (session == null ? "" : session.userName()));\n'
if old_store not in text:
    raise SystemExit('V4.5 scoped store target not found')
text = text.replace(old_store, new_store, 1)

# Replace V4.4 snapshot handler: never rebuild cards for an identical poll, but allow paid invoice
# headers without lines to upgrade an existing temporary ticket.
start = text.find('    private void applyCloudSnapshot(List<KitchenOrder> orders, String detail) {')
end = text.find('    private void restoreConnection()', start)
if start < 0 or end < 0:
    raise SystemExit('V4.5 cloud snapshot method not found')
new_snapshot = r'''    private void applyCloudSnapshot(List<KitchenOrder> orders, String detail) {
        if (store == null) return;
        try {
            java.util.HashSet<String> archived = new java.util.HashSet<>();
            for (KitchenOrder old : store.history()) {
                String number = invoiceNumber(old);
                if (!number.isEmpty()) archived.add(number);
            }

            boolean anyChange = false;
            int newCount = 0;
            int changedCount = 0;
            int paidCount = 0;
            if (orders != null) for (KitchenOrder cloud : orders) {
                if (cloud == null || !hasInvoice(cloud)) continue;
                String number = invoiceNumber(cloud);
                if (number.isEmpty() || archived.contains(number)) continue;

                boolean paidInvoice = "POS_INVOICE".equalsIgnoreCase(clean(cloud.rawStatus));
                KitchenOrder before = store.findByNumber(number);

                // PosInvoice list can expose only the header. It is still enough to change an existing
                // temporary ticket to paid without creating an empty duplicate card.
                if (cloud.items.isEmpty()) {
                    if (paidInvoice && before != null) {
                        KitchenOrder payment = before.copy();
                        payment.paymentStatus = "PAID";
                        payment.rawStatus = "POS_INVOICE";
                        payment.temporaryOrder = false;
                        if (store.upsert(payment)) {
                            paidCount++;
                            anyChange = true;
                        }
                    }
                    continue;
                }

                boolean changed = store.upsert(cloud);
                if (before == null) {
                    newCount++;
                    anyChange = true;
                    beepNew();
                } else if (changed) {
                    changedCount++;
                    anyChange = true;
                    // Payment transition is not a second kitchen order and must not sound like one.
                    if (!paidInvoice) beepModified();
                }
            }
            recordDiagnostic("cloud-snapshot", (detail == null ? "" : detail)
                    + " • new=" + newCount + " • changed=" + changedCount + " • paid=" + paidCount);
            // Critical V4.5 anti-flicker rule: an identical 2-second poll must not remove/re-add views.
            if (anyChange) renderBoard();
        } catch (Throwable error) {
            recordError("cloud-snapshot", error);
        }
    }

'''
text = text[:start] + new_snapshot + text[end:]

logout_old = 'setNegativeButton(t("logout"), (d, w) -> { session.clear(); openLogin(); }).show();'
logout_new = 'setNegativeButton(t("logout"), (d, w) -> { try { if (cloudPoller != null) cloudPoller.stop(); } catch (Throwable ignored) { } if (store != null) store.clearActive(); pendingSaved = null; liveDraft = null; if (session != null) session.clear(); openLogin(); }).show();'
if logout_old not in text:
    raise SystemExit('V4.5 logout target not found')
text = text.replace(logout_old, logout_new, 1)

text = text.replace('TechPro Kitchen 4.4 Cloud', 'TechPro Kitchen 4.5 Stable Cloud')
text = text.replace(
    'لا يحتاج IP أو ربط محلي. الطلبات المؤقتة تُقرأ مباشرة من TechPro Cloud.',
    'TemporaryOrders للطلبات المؤقتة و PosInvoice للطلبات العادية — بدون IP أو ربط محلي.'
)
text = text.replace(
    'No IP or LAN pairing required. Temporary orders are read directly from TechPro Cloud.',
    'TemporaryOrders + PosInvoice are read from TechPro Cloud; no IP or LAN pairing required.'
)

# V4.5 invariants: no periodic full redraw, account-scoped queue, official operational API and paid source.
for required in [
    'new KitchenOrderStoreV2(this,',
    'if (anyChange) renderBoard();',
    'payment.paymentStatus = "PAID"',
    'store.clearActive()',
    'TechPro Kitchen 4.5 Stable Cloud',
]:
    if required not in text:
        raise SystemExit(f'V4.5 activity invariant missing: {required}')
if 'if (anyChange || (orders != null && !orders.isEmpty())) renderBoard();' in text:
    raise SystemExit('V4.5 still contains periodic full-board redraw')

activity_path.write_text(text, encoding='utf-8')

poller = Path('app/src/main/java/sa/techlight/customerdisplay/KitchenCloudOrdersPoller.java').read_text(encoding='utf-8')
for required in [
    'https://posapi.techlight.sa/api/',
    'TemporaryOrders/List',
    'PosInvoice',
    'InvoiceCode',
    'seenPaidInvoices',
]:
    if required not in poller:
        raise SystemExit(f'V4.5 cloud poller invariant missing: {required}')
if 'posapifornewapp.techlight.sa' in poller:
    raise SystemExit('V4.5 poller still uses legacy host')

print('TechPro Kitchen V4.5 official API + anti-flicker + session isolation applied successfully')

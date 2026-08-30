from pathlib import Path

activity = Path('app/src/main/java/sa/techlight/customerdisplay/KitchenActivityV42.java')
text = activity.read_text(encoding='utf-8')


def replace_once(old, new, label):
    global text
    if old not in text:
        raise SystemExit(f'V4.3 patch target not found: {label}')
    text = text.replace(old, new, 1)

replace_once(
    '    private TechProClient client;\n',
    '    private TechProClient client;\n    private KitchenTemporaryOrdersApiClient tempOrdersApi;\n',
    'cloud client field'
)

replace_once(
    '            diagnostics = getSharedPreferences("kitchen_diagnostics_v42", MODE_PRIVATE);\n            store = new KitchenOrderStoreV2(this);\n',
    '            diagnostics = getSharedPreferences("kitchen_diagnostics_v43", MODE_PRIVATE);\n            store = new KitchenOrderStoreV2(this);\n            tempOrdersApi = new KitchenTemporaryOrdersApiClient(session.token());\n',
    'cloud client init'
)

replace_once(
'''        try {
            KitchenSignalV2.Signal signal = KitchenSignalV2.parse(raw);
            if (signal != null && signal.order != null) {
                String strictInvoice = StrictInvoiceExtractor.extract(raw); signal.order.displayNumber = strictInvoice; if (!strictInvoice.isEmpty()) signal.order.id = "invoice-" + strictInvoice;
            }
            processSignal(signal);
        } catch (Throwable error) { recordError("raw", error); }
''',
'''        try {
            // WebSocket remains the low-latency source for cart lines. Invoice identity is resolved
            // independently through TechPro TemporaryOrders REST in resolveSavedViaCloud().
            processSignal(KitchenSignalV2.parse(raw));
        } catch (Throwable error) { recordError("raw", error); }
''',
    'remove websocket invoice dependency'
)

replace_once(
'''        if (kind == KitchenOrderParser.Kind.SAVED) {
            KitchenOrder saved = bestPayload(incoming, liveDraft); if (saved == null || saved.items.isEmpty()) return; saved.temporaryOrder = true;
            if (hasInvoice(saved)) { commitInvoice(saved, false); pendingSaved = null; pendingSavedAt = 0L; }
            else { pendingSaved = saved.copy(); pendingSavedAt = now; }
            liveDraft = null; return;
        }
''',
'''        if (kind == KitchenOrderParser.Kind.SAVED) {
            KitchenOrder saved = bestPayload(incoming, liveDraft);
            if (saved == null || saved.items.isEmpty()) return;
            saved.temporaryOrder = true;
            resolveSavedViaCloud(saved, false);
            liveDraft = null;
            return;
        }
''',
    'saved uses cloud resolver'
)

replace_once(
'''        if (kind == KitchenOrderParser.Kind.CLEARED) {
            if (liveDraft == null || liveDraft.items.isEmpty()) return;
            if (now - lastPaymentAt >= 0L && now - lastPaymentAt < 7000L) { liveDraft = null; return; }
            if (hasInvoice(liveDraft)) { liveDraft.temporaryOrder = true; commitInvoice(liveDraft.copy(), false); }
            else { pendingSaved = liveDraft.copy(); pendingSaved.temporaryOrder = true; pendingSaved.inferredTemporarySave = true; pendingSavedAt = now; }
            liveDraft = null;
        }
''',
'''        if (kind == KitchenOrderParser.Kind.CLEARED) {
            if (liveDraft == null || liveDraft.items.isEmpty()) return;
            if (now - lastPaymentAt >= 0L && now - lastPaymentAt < 7000L) { liveDraft = null; return; }
            KitchenOrder saved = liveDraft.copy();
            saved.temporaryOrder = true;
            saved.inferredTemporarySave = true;
            resolveSavedViaCloud(saved, true);
            liveDraft = null;
        }
''',
    'clear uses cloud resolver'
)

anchor = '    private KitchenOrder bestPayload(KitchenOrder first, KitchenOrder fallback) {\n'
if anchor not in text:
    raise SystemExit('V4.3 patch target not found: cloud resolver method anchor')
cloud_methods = r'''    private void resolveSavedViaCloud(KitchenOrder saved, boolean inferred) {
        if (saved == null || saved.items.isEmpty()) return;
        pendingSaved = saved.copy();
        pendingSaved.temporaryOrder = true;
        pendingSaved.inferredTemporarySave = inferred;
        pendingSavedAt = System.currentTimeMillis();
        resolveSavedViaCloudAttempt(saved.copy(), inferred, pendingSavedAt, 0);
    }

    private void resolveSavedViaCloudAttempt(KitchenOrder saved, boolean inferred, long stamp, int round) {
        if (tempOrdersApi == null || saved == null || saved.items.isEmpty()) {
            if (hasInvoice(saved)) commitInvoice(saved, false);
            return;
        }
        recordDiagnostic("temp-api", "Resolving saved cart through TemporaryOrders API — round " + (round + 1));
        tempOrdersApi.resolveSavedOrder(saved, session == null ? "" : session.posCode(), activeInvoiceNumbers(), new KitchenTemporaryOrdersApiClient.Listener() {
            @Override public void onResolved(KitchenOrder resolved, String detail) {
                if (stamp != pendingSavedAt) return; // a newer cashier save superseded this lookup.
                KitchenOrder local = pendingSaved == null ? saved.copy() : pendingSaved.copy();
                KitchenOrder merged = bestPayload(resolved, local);
                if (merged == null || merged.items.isEmpty() || !hasInvoice(merged)) return;
                merged.temporaryOrder = true;
                merged.inferredTemporarySave = inferred;
                recordDiagnostic("temp-api-resolved", detail);
                commitInvoice(merged, false);
                pendingSaved = null;
                pendingSavedAt = 0L;
            }

            @Override public void onNotFound(String detail) {
                if (stamp != pendingSavedAt) return;
                recordDiagnostic("temp-api-wait", detail);
                KitchenOrder latest = pendingSaved == null ? saved.copy() : pendingSaved.copy();
                if (round < 2) {
                    handler.postDelayed(() -> {
                        if (stamp == pendingSavedAt && pendingSaved != null) {
                            resolveSavedViaCloudAttempt(pendingSaved.copy(), inferred, stamp, round + 1);
                        }
                    }, round == 0 ? 2500L : 4500L);
                    return;
                }
                // A WebSocket invoice number is only a final fallback; cloud TemporaryOrders is primary.
                if (hasInvoice(latest)) {
                    commitInvoice(latest, false);
                    pendingSaved = null;
                    pendingSavedAt = 0L;
                }
            }

            @Override public void onUnauthorized() {
                if (stamp != pendingSavedAt) return;
                recordDiagnostic("temp-api-auth", "TechPro TemporaryOrders API rejected the saved session");
                KitchenOrder latest = pendingSaved == null ? saved.copy() : pendingSaved.copy();
                if (hasInvoice(latest)) {
                    commitInvoice(latest, false);
                    pendingSaved = null;
                    pendingSavedAt = 0L;
                }
            }
        });
    }

    private List<String> activeInvoiceNumbers() {
        ArrayList<String> numbers = new ArrayList<>();
        if (store == null) return numbers;
        for (KitchenOrder order : store.active()) {
            String number = invoiceNumber(order);
            if (!number.isEmpty() && !numbers.contains(number)) numbers.add(number);
        }
        return numbers;
    }

'''
text = text.replace(anchor, cloud_methods + anchor, 1)

text = text.replace(
    'case "waitingSub": return a ? "لن يظهر أي طلب قبل وصول رقم الفاتورة الحقيقي من TechPro." : "No ticket appears until TechPro sends the real invoice number.";',
    'case "waitingSub": return a ? "الحفظ المؤقت يُطابق مع TemporaryOrders API ثم يظهر بنفس رقم فاتورة الكاشير." : "Saved carts are matched through TemporaryOrders API and shown with the cashier invoice number.";'
)
text = text.replace('setTitle("TechPro Kitchen 4.2")', 'setTitle("TechPro Kitchen 4.3")')

replace_once(
'''        handler.removeCallbacks(secondTick); handler.removeCallbacks(watchdog); try { if (client != null) client.stop(); } catch (Throwable ignored) { } try { if (tone != null) tone.release(); } catch (Throwable ignored) { } try { if (imageLoader != null) imageLoader.shutdown(); } catch (Throwable ignored) { } try { if (catalog != null) catalog.close(); } catch (Throwable ignored) { } super.onDestroy();
''',
'''        handler.removeCallbacks(secondTick); handler.removeCallbacks(watchdog); try { if (client != null) client.stop(); } catch (Throwable ignored) { } try { if (tempOrdersApi != null) tempOrdersApi.shutdown(); } catch (Throwable ignored) { } try { if (tone != null) tone.release(); } catch (Throwable ignored) { } try { if (imageLoader != null) imageLoader.shutdown(); } catch (Throwable ignored) { } try { if (catalog != null) catalog.close(); } catch (Throwable ignored) { } super.onDestroy();
''',
    'cloud client shutdown'
)

for required in [
        'new KitchenTemporaryOrdersApiClient(session.token())',
        'resolveSavedViaCloud(saved, false)',
        'resolveSavedViaCloud(saved, true)',
        'TemporaryOrders API',
        'tempOrdersApi.shutdown()',
        'handler.postDelayed(this, 1000L)',
        't("readyAction")']:
    if required not in text:
        raise SystemExit(f'V4.3 validation missing: {required}')

activity.write_text(text, encoding='utf-8')
print('TechPro Kitchen V4.3 cloud TemporaryOrders integration applied successfully')

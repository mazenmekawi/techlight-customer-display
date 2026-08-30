from pathlib import Path

activity = Path('app/src/main/java/sa/techlight/customerdisplay/KitchenActivityV42.java')
text = activity.read_text(encoding='utf-8')


def replace_once(old, new, label):
    global text
    if old not in text:
        raise SystemExit(f'V4.4 patch target not found: {label}')
    text = text.replace(old, new, 1)

replace_once(
    '    private KitchenTemporaryOrdersApiClient tempOrdersApi;\n',
    '    private KitchenTemporaryOrdersApiClient tempOrdersApi;\n    private KitchenCloudOrdersPoller cloudPoller;\n',
    'cloud poller field'
)

replace_once(
    '            restoreConnection();\n            handler.postDelayed(secondTick, 1000L);\n            handler.postDelayed(watchdog, 10_000L);\n',
    '            startCloudMode();\n            handler.postDelayed(secondTick, 1000L);\n',
    'cloud startup replaces LAN startup'
)

anchor = '    private void restoreConnection() {'
if anchor not in text:
    raise SystemExit('V4.4 patch target not found: start cloud method anchor')

cloud_methods = r'''    private void startCloudMode() {
        try {
            if (cloudPoller != null) {
                try { cloudPoller.stop(); } catch (Throwable ignored) { }
            }
            connectionOk = false;
            setConnection(ar() ? "مزامنة السحابة" : "Cloud syncing", false);
            cloudPoller = new KitchenCloudOrdersPoller(
                    session == null ? "" : session.token(),
                    session == null ? "" : session.posCode(),
                    new KitchenCloudOrdersPoller.Listener() {
                        @Override public void onSnapshot(List<KitchenOrder> orders, String detail) {
                            applyCloudSnapshot(orders, detail);
                        }

                        @Override public void onStatus(String status, boolean connected) {
                            connectionOk = connected;
                            String visible;
                            if (connected) visible = ar() ? "السحابة متصلة" : "Cloud connected";
                            else if (status != null && status.toLowerCase(Locale.US).contains("expired")) {
                                visible = ar() ? "انتهت الجلسة" : "Session expired";
                            } else {
                                visible = ar() ? "إعادة اتصال السحابة" : "Cloud reconnecting";
                            }
                            setConnection(visible, connected);
                            recordDiagnostic("cloud-status", status == null ? "" : status);
                        }

                        @Override public void onUnauthorized() {
                            Toast.makeText(KitchenActivityV42.this,
                                    ar() ? "انتهت جلسة TechPro. سجل الدخول مرة أخرى." : "TechPro session expired. Sign in again.",
                                    Toast.LENGTH_LONG).show();
                            if (session != null) session.clear();
                            openLogin();
                        }
                    }
            );
            cloudPoller.start();
        } catch (Throwable error) {
            recordError("cloud-start", error);
            setConnection(ar() ? "تعذر اتصال السحابة" : "Cloud unavailable", false);
        }
    }

    private void restartCloudMode() {
        try { if (cloudPoller != null) cloudPoller.stop(); } catch (Throwable ignored) { }
        cloudPoller = null;
        startCloudMode();
    }

    private void applyCloudSnapshot(List<KitchenOrder> orders, String detail) {
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
            if (orders != null) for (KitchenOrder cloud : orders) {
                if (cloud == null || cloud.items.isEmpty() || !hasInvoice(cloud)) continue;
                String number = invoiceNumber(cloud);
                if (archived.contains(number)) continue;

                KitchenOrder before = store.findByNumber(number);
                boolean changed = store.upsert(cloud);
                if (before == null) {
                    newCount++;
                    anyChange = true;
                    beepNew();
                } else if (changed) {
                    changedCount++;
                    anyChange = true;
                    beepModified();
                }
            }
            recordDiagnostic("cloud-snapshot", (detail == null ? "" : detail)
                    + " • new=" + newCount + " • changed=" + changedCount);
            if (anyChange || (orders != null && !orders.isEmpty())) renderBoard();
        } catch (Throwable error) {
            recordError("cloud-snapshot", error);
        }
    }

'''
text = text.replace(anchor, cloud_methods + anchor, 1)

# Pair/IP button is no longer needed for order reception. Reuse it as a deliberate cloud refresh.
old_pair = '        TextView pairButton = action(t("pair"), false); pairButton.setOnClickListener(v -> showPairDialog()); LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(46)); pp.setMargins(0, dp(10), 0, 0); panel.addView(pairButton, pp);\n'
new_pair = '        TextView pairButton = action(ar() ? "تحديث اتصال السحابة" : "Refresh cloud connection", false); pairButton.setOnClickListener(v -> restartCloudMode()); LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(46)); pp.setMargins(0, dp(10), 0, 0); panel.addView(pairButton, pp);\n'
replace_once(old_pair, new_pair, 'replace LAN pair button')

text = text.replace(
    'case "waiting": return a ? "بانتظار فاتورة محفوظة" : "Waiting for a saved invoice";',
    'case "waiting": return a ? "بانتظار الطلبات السحابية" : "Waiting for cloud orders";'
)
text = text.replace(
    'case "waitingSub": return a ? "الحفظ المؤقت يُطابق مع TemporaryOrders API ثم يظهر بنفس رقم فاتورة الكاشير." : "Saved carts are matched through TemporaryOrders API and shown with the cashier invoice number.";',
    'case "waitingSub": return a ? "لا يحتاج IP أو ربط محلي. الطلبات المؤقتة تُقرأ مباشرة من TechPro Cloud." : "No IP or LAN pairing required. Temporary orders are read directly from TechPro Cloud.";'
)
text = text.replace('setTitle("TechPro Kitchen 4.3")', 'setTitle("TechPro Kitchen 4.4 Cloud")')

replace_once(
    'try { if (tempOrdersApi != null) tempOrdersApi.shutdown(); } catch (Throwable ignored) { } try { if (tone != null) tone.release(); }',
    'try { if (tempOrdersApi != null) tempOrdersApi.shutdown(); } catch (Throwable ignored) { } try { if (cloudPoller != null) cloudPoller.stop(); } catch (Throwable ignored) { } try { if (tone != null) tone.release(); }',
    'stop cloud poller'
)

for required in [
        'new KitchenCloudOrdersPoller(',
        'applyCloudSnapshot(orders, detail)',
        'startCloudMode();',
        'cloudPoller.stop()',
        'Cloud connected',
        'handler.postDelayed(secondTick, 1000L)']:
    if required not in text:
        raise SystemExit(f'V4.4 validation missing: {required}')

# Ensure no automatic LAN restore/watchdog is active in the startup path.
startup_slice = text[text.find('buildUi();'):text.find('private void openLogin')]
if 'restoreConnection();' in startup_slice or 'handler.postDelayed(watchdog' in startup_slice:
    raise SystemExit('V4.4 still starts LAN transport')

activity.write_text(text, encoding='utf-8')
print('TechPro Kitchen V4.4 cloud-only polling applied successfully')

from pathlib import Path


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if old not in text:
        raise SystemExit(f"V4.8 patch target not found: {label}")
    return text.replace(old, new, 1)


def replace_between(text: str, start_marker: str, end_marker: str, replacement: str, label: str) -> str:
    start = text.find(start_marker)
    end = text.find(end_marker, start + len(start_marker))
    if start < 0 or end < 0:
        raise SystemExit(f"V4.8 patch range not found: {label}")
    return text[:start] + replacement + text[end:]


# -----------------------------------------------------------------------------
# Temporary-order resolver: use the same API host that accepted the login.
# -----------------------------------------------------------------------------
temp_path = Path("app/src/main/java/sa/techlight/customerdisplay/KitchenTemporaryOrdersApiClient.java")
temp = temp_path.read_text(encoding="utf-8")

temp = replace_once(
    temp,
    '    static final String API = "https://posapifornewapp.techlight.sa/api/";\n',
    '    static final String DEFAULT_API = "https://posapifornewapp.techlight.sa/api/";\n',
    "temporary resolver API constant",
)
temp = replace_once(
    temp,
    '    private final String token;\n',
    '    private final String token;\n    private final String apiBase;\n',
    "temporary resolver API field",
)
temp = replace_once(
    temp,
    '''    public KitchenTemporaryOrdersApiClient(String token) {
        this.token = stripBearer(token);
    }
''',
    '''    public KitchenTemporaryOrdersApiClient(String token) {
        this(token, DEFAULT_API);
    }

    public KitchenTemporaryOrdersApiClient(String token, String apiBase) {
        this.token = stripBearer(token);
        this.apiBase = normalizeApiBase(apiBase);
    }
''',
    "temporary resolver constructors",
)
temp = temp.replace("executeGet(API + path)", "executeGet(apiBase + path)")
temp = temp.replace('executeGet(API + "ErpLov/168")', 'executeGet(apiBase + "ErpLov/168")')
temp = replace_once(
    temp,
    '    private static String stripBearer(String value) {\n',
    '''    static String normalizeApiBase(String value) {
        String base = clean(value);
        if (base.isEmpty()) return DEFAULT_API;
        if (!base.endsWith("/")) base += "/";
        if (!base.endsWith("api/")) {
            if (base.endsWith("api")) base += "/";
            else base += "api/";
        }
        return base;
    }

    private static String stripBearer(String value) {
''',
    "temporary resolver API normalizer",
)
for required in [
    "private final String apiBase;",
    "KitchenTemporaryOrdersApiClient(String token, String apiBase)",
    "executeGet(apiBase + path)",
    'executeGet(apiBase + "ErpLov/168")',
]:
    if required not in temp:
        raise SystemExit(f"V4.8 temporary resolver invariant missing: {required}")
temp_path.write_text(temp, encoding="utf-8")


# -----------------------------------------------------------------------------
# Kitchen activity: run TechPro cloud and the cashier WebSocket/IP at the same
# time. The local feed is low latency; cloud is fallback/enrichment. Both feeds
# are de-duplicated by the real invoice number in KitchenOrderStoreV2.
# -----------------------------------------------------------------------------
activity_path = Path("app/src/main/java/sa/techlight/customerdisplay/KitchenActivityV42.java")
activity = activity_path.read_text(encoding="utf-8")

activity = replace_once(
    activity,
    '    private boolean connectionOk;\n',
    '    private boolean connectionOk;\n'
    '    private boolean cloudConnected;\n'
    '    private boolean lanConnected;\n'
    '    private String lanDetail = "";\n',
    "hybrid connection fields",
)
activity = replace_once(
    activity,
    '            tempOrdersApi = new KitchenTemporaryOrdersApiClient(session.token());\n',
    '            tempOrdersApi = new KitchenTemporaryOrdersApiClient(session.token(), session.apiBase());\n',
    "selected host for temporary resolver",
)
activity = replace_once(
    activity,
    '''            startCloudMode();
            handler.postDelayed(secondTick, 1000L);
''',
    '''            startCloudMode();
            restoreConnection();
            handler.postDelayed(secondTick, 1000L);
            handler.postDelayed(watchdog, 10_000L);
''',
    "start both cloud and cashier IP",
)

# The LAN watchdog must track the LAN socket, not the combined connection flag.
activity = replace_once(
    activity,
    '                        boolean silent = connectionOk && lastRawAt > 0L && now - lastRawAt >= SILENT_RECONNECT_MS;\n'
    '                        boolean stuck = !connectionOk && now - lastConnectAttemptAt >= 35_000L;\n',
    '                        boolean silent = lanConnected && lastRawAt > 0L && now - lastRawAt >= SILENT_RECONNECT_MS;\n'
    '                        boolean stuck = !lanConnected && now - lastConnectAttemptAt >= 35_000L;\n',
    "LAN watchdog state",
)
activity = replace_once(
    activity,
    '                            setConnection(t("recovering"), false);\n'
    '                            connect(ip, port);\n',
    '                            lanDetail = ar() ? "استعادة اتصال IP" : "Recovering cashier IP";\n'
    '                            updateHybridConnection();\n'
    '                            connect(ip, port);\n',
    "LAN watchdog visible state",
)

# Starting/restarting cloud must never mark the whole app disconnected while IP works.
activity = replace_once(
    activity,
    '            connectionOk = false;\n'
    '            setConnection(ar() ? "مزامنة السحابة" : "Cloud syncing", false);\n',
    '            cloudConnected = false;\n'
    '            lastCloudDetail = ar() ? "مزامنة السحابة" : "Cloud syncing";\n'
    '            updateHybridConnection();\n',
    "cloud startup hybrid state",
)

old_cloud_status = r'''                        @Override public void onStatus(String status, boolean connected) {
                            connectionOk = connected;
                            lastCloudDetail = status == null ? "" : status;
                            String visible;
                            boolean modern = lastCloudDetail.contains("NEW");
                            boolean legacy = lastCloudDetail.contains("OLD");
                            if (connected) {
                                if (modern) visible = ar() ? "متصل • الخادم الجديد" : "Connected • New API";
                                else if (legacy) visible = ar() ? "متصل • الخادم القديم" : "Connected • Old API";
                                else visible = ar() ? "السحابة متصلة" : "Cloud connected";
                            } else if (status != null && status.toLowerCase(Locale.US).contains("expired")) {
                                visible = ar() ? "انتهت الجلسة" : "Session expired";
                            } else {
                                visible = ar() ? "إعادة اتصال السحابة" : "Cloud reconnecting";
                            }
                            setConnection(visible, connected);
                            recordDiagnostic("cloud-status", lastCloudDetail);
                            if (store != null && store.active().isEmpty()) renderBoard();
                        }
'''
new_cloud_status = r'''                        @Override public void onStatus(String status, boolean connected) {
                            cloudConnected = connected;
                            lastCloudDetail = status == null ? "" : status;
                            recordDiagnostic("cloud-status", lastCloudDetail);
                            updateHybridConnection();
                        }
'''
activity = replace_once(activity, old_cloud_status, new_cloud_status, "hybrid cloud status callback")

old_unauthorized = r'''                        @Override public void onUnauthorized() {
                            Toast.makeText(KitchenActivityV42.this,
                                    ar() ? "انتهت جلسة TechPro. سجل الدخول مرة أخرى." : "TechPro session expired. Sign in again.",
                                    Toast.LENGTH_LONG).show();
                            if (store != null) store.clearActive();
                            pendingSaved = null;
                            liveDraft = null;
                            if (session != null) session.clear();
                            openLogin();
                        }
'''
new_unauthorized = r'''                        @Override public void onUnauthorized() {
                            cloudConnected = false;
                            lastCloudDetail = ar() ? "جلسة السحابة منتهية" : "Cloud session expired";
                            try { if (cloudPoller != null) cloudPoller.stop(); } catch (Throwable ignored) { }
                            cloudPoller = null;
                            updateHybridConnection();
                            if (lanConnected) {
                                Toast.makeText(KitchenActivityV42.this,
                                        ar() ? "السحابة توقفت، لكن اتصال IP مستمر في استقبال الطلبات." : "Cloud stopped; cashier IP remains active.",
                                        Toast.LENGTH_LONG).show();
                            } else {
                                Toast.makeText(KitchenActivityV42.this,
                                        ar() ? "انتهت جلسة TechPro. سجل الدخول مرة أخرى." : "TechPro session expired. Sign in again.",
                                        Toast.LENGTH_LONG).show();
                                if (session != null) session.clear();
                                openLogin();
                            }
                        }
'''
activity = replace_once(activity, old_unauthorized, new_unauthorized, "keep IP alive after cloud expiry")

helper_anchor = '    private void restartCloudMode() {\n'
if helper_anchor not in activity:
    raise SystemExit("V4.8 hybrid helper anchor missing")
hybrid_helpers = r'''    private void updateHybridConnection() {
        connectionOk = cloudConnected || lanConnected;
        String visible;
        if (lanConnected && cloudConnected) {
            visible = ar() ? "IP + السحابة متصلان" : "IP + Cloud connected";
        } else if (lanConnected) {
            visible = ar() ? "IP متصل" : "Cashier IP connected";
        } else if (cloudConnected) {
            visible = ar() ? "السحابة متصلة" : "Cloud connected";
        } else {
            String ip = pair == null ? "" : clean(pair.getString("ip", ""));
            visible = ip.isEmpty()
                    ? (ar() ? "أدخل IP الكاشير" : "Set cashier IP")
                    : (ar() ? "إعادة اتصال IP والسحابة" : "Reconnecting IP + Cloud");
        }
        setConnection(visible, connectionOk);
        if (store != null && store.active().isEmpty() && emptyState != null) renderBoard();
    }

    private String hybridDiagnostic() {
        String ip = pair == null ? "" : clean(pair.getString("ip", ""));
        int port = pair == null ? 4040 : pair.getInt("port", 4040);
        String local;
        if (ip.isEmpty()) {
            local = ar() ? "IP: غير مضبوط" : "IP: not configured";
        } else if (lanConnected) {
            local = (ar() ? "IP متصل: " : "IP connected: ") + ip + ":" + port;
        } else {
            local = (ar() ? "IP غير متصل: " : "IP disconnected: ") + ip + ":" + port;
        }
        String cloud = clean(lastCloudDetail);
        if (cloud.isEmpty()) cloud = ar() ? "السحابة: بانتظار الاتصال" : "Cloud: waiting";
        return local + "\n" + cloud;
    }

'''
activity = activity.replace(helper_anchor, hybrid_helpers + helper_anchor, 1)

# Local WebSocket callbacks keep a separate state and feed the same invoice store.
activity = replace_once(
    activity,
    '    @Override public void onConnected() { connectionOk = true; lastRawAt = System.currentTimeMillis(); runOnUiThread(() -> setConnection(t("connected"), true)); }\n'
    '    @Override public void onDisconnected(String reason) { connectionOk = false; recordDiagnostic("disconnect", reason); runOnUiThread(() -> setConnection(t("reconnecting"), false)); }\n',
    '    @Override public void onConnected() { lanConnected = true; lastRawAt = System.currentTimeMillis(); lanDetail = ar() ? "IP متصل" : "Cashier IP connected"; recordDiagnostic("ip-connected", lanDetail); runOnUiThread(this::updateHybridConnection); }\n'
    '    @Override public void onDisconnected(String reason) { lanConnected = false; lanDetail = reason == null ? "" : reason; recordDiagnostic("ip-disconnected", lanDetail); runOnUiThread(this::updateHybridConnection); }\n',
    "separate LAN callbacks",
)

old_on_raw = r'''        try {
            // WebSocket remains the low-latency source for cart lines. Invoice identity is resolved
            // independently through TechPro TemporaryOrders REST in resolveSavedViaCloud().
            processSignal(KitchenSignalV2.parse(raw));
        } catch (Throwable error) { recordError("raw", error); }
'''
new_on_raw = r'''        try {
            KitchenSignalV2.Signal signal = KitchenSignalV2.parse(raw);
            if (signal != null && signal.order != null && !hasInvoice(signal.order)) {
                String strictInvoice = StrictInvoiceExtractor.extract(raw);
                if (!strictInvoice.isEmpty()) {
                    signal.order.displayNumber = strictInvoice;
                    signal.order.id = "invoice-" + strictInvoice;
                }
            }
            lanDetail = "message " + lastRawAt;
            recordDiagnostic("ip-message", signal == null || signal.parsed == null
                    ? "unparsed" : String.valueOf(signal.parsed.kind));
            processSignal(signal);
        } catch (Throwable error) { recordError("raw", error); }
'''
activity = replace_once(activity, old_on_raw, new_on_raw, "restore local invoice extraction")

# A paid local invoice updates an existing temporary card; a direct paid sale can
# create a card only when the local message includes both invoice number and lines.
old_payment = r'''        if (kind == KitchenOrderParser.Kind.PAYMENT) {
            lastPaymentAt = now; pendingSaved = null; pendingSavedAt = 0L; KitchenOrder existing = existingByInvoice(incoming);
            if (existing != null) { store.updatePayment(existing.id, incoming == null || clean(incoming.paymentStatus).isEmpty() ? "PAID" : incoming.paymentStatus); runOnUiThread(this::renderBoard); }
            liveDraft = null; return;
        }
'''
new_payment = r'''        if (kind == KitchenOrderParser.Kind.PAYMENT) {
            lastPaymentAt = now;
            pendingSaved = null;
            pendingSavedAt = 0L;
            KitchenOrder existing = existingByInvoice(incoming);
            if (existing != null) {
                store.updatePayment(existing.id,
                        incoming == null || clean(incoming.paymentStatus).isEmpty() ? "PAID" : incoming.paymentStatus);
                runOnUiThread(this::renderBoard);
            } else if (incoming != null && hasInvoice(incoming) && !incoming.items.isEmpty()) {
                incoming.paymentStatus = clean(incoming.paymentStatus).isEmpty() ? "PAID" : incoming.paymentStatus;
                incoming.rawStatus = "POS_INVOICE";
                incoming.temporaryOrder = false;
                commitInvoice(incoming, false);
            }
            liveDraft = null;
            return;
        }
'''
activity = replace_once(activity, old_payment, new_payment, "direct paid local invoice handling")

# Local invoice number wins immediately. Cloud resolution remains the fallback
# only for local saved carts that truly have no invoice identity.
old_saved = r'''        if (kind == KitchenOrderParser.Kind.SAVED) {
            KitchenOrder saved = bestPayload(incoming, liveDraft);
            if (saved == null || saved.items.isEmpty()) return;
            saved.temporaryOrder = true;
            resolveSavedViaCloud(saved, false);
            liveDraft = null;
            return;
        }
'''
new_saved = r'''        if (kind == KitchenOrderParser.Kind.SAVED) {
            KitchenOrder saved = bestPayload(incoming, liveDraft);
            if (saved == null || saved.items.isEmpty()) return;
            saved.temporaryOrder = true;
            saved.rawStatus = "TEMPORARY";
            if (hasInvoice(saved)) {
                commitInvoice(saved, false);
                pendingSaved = null;
                pendingSavedAt = 0L;
            } else {
                resolveSavedViaCloud(saved, false);
            }
            liveDraft = null;
            return;
        }
'''
activity = replace_once(activity, old_saved, new_saved, "local saved invoice first")

old_cleared = r'''        if (kind == KitchenOrderParser.Kind.CLEARED) {
            if (liveDraft == null || liveDraft.items.isEmpty()) return;
            if (now - lastPaymentAt >= 0L && now - lastPaymentAt < 7000L) { liveDraft = null; return; }
            KitchenOrder saved = liveDraft.copy();
            saved.temporaryOrder = true;
            saved.inferredTemporarySave = true;
            resolveSavedViaCloud(saved, true);
            liveDraft = null;
        }
'''
new_cleared = r'''        if (kind == KitchenOrderParser.Kind.CLEARED) {
            if (liveDraft == null || liveDraft.items.isEmpty()) return;
            if (now - lastPaymentAt >= 0L && now - lastPaymentAt < 7000L) { liveDraft = null; return; }
            KitchenOrder saved = liveDraft.copy();
            saved.temporaryOrder = true;
            saved.rawStatus = "TEMPORARY";
            saved.inferredTemporarySave = true;
            if (hasInvoice(saved)) {
                commitInvoice(saved, false);
                pendingSaved = null;
                pendingSavedAt = 0L;
            } else {
                resolveSavedViaCloud(saved, true);
            }
            liveDraft = null;
        }
'''
activity = replace_once(activity, old_cleared, new_cleared, "local cleared invoice first")

old_commit = '    private void commitInvoice(KitchenOrder order, boolean updateExistingOnly) {\n        if (order == null || order.items.isEmpty() || !hasInvoice(order)) return; String number = invoiceNumber(order); order.displayNumber = number; order.id = "invoice-" + number; order.temporaryOrder = true;\n        KitchenOrder before = store.findByNumber(number); if (updateExistingOnly && before == null) return; boolean changed = store.upsert(order); if (before == null) beepNew(); else if (changed) beepModified(); runOnUiThread(this::renderBoard);\n    }\n'
new_commit = '''    private void commitInvoice(KitchenOrder order, boolean updateExistingOnly) {
        if (order == null || order.items.isEmpty() || !hasInvoice(order) || store == null) return;
        String number = invoiceNumber(order);
        order.displayNumber = number;
        order.id = "invoice-" + number;
        boolean paidSource = "POS_INVOICE".equalsIgnoreCase(clean(order.rawStatus)) || order.isPaid();
        order.temporaryOrder = !paidSource;
        if (clean(order.rawStatus).isEmpty()) order.rawStatus = paidSource ? "POS_INVOICE" : "TEMPORARY";
        KitchenOrder before = store.findByNumber(number);
        if (updateExistingOnly && before == null) return;
        boolean changed = store.upsert(order);
        if (before == null) beepNew();
        else if (changed && !paidSource) beepModified();
        if (before == null || changed) runOnUiThread(this::renderBoard);
    }
'''
activity = replace_once(activity, old_commit, new_commit, "hybrid de-duplicated invoice commit")

# Restore and configure the cashier IP without taking cloud offline.
activity = replace_once(
    activity,
    '    private void restoreConnection() { String ip = pair == null ? "" : pair.getString("ip", ""); int port = pair == null ? 4040 : pair.getInt("port", 4040); if (ip.isEmpty()) setConnection(t("notPaired"), false); else connect(ip, port); }\n',
    '''    private void restoreConnection() {
        String ip = pair == null ? "" : clean(pair.getString("ip", ""));
        int port = pair == null ? 4040 : pair.getInt("port", 4040);
        if (ip.isEmpty()) {
            lanConnected = false;
            lanDetail = ar() ? "IP غير مضبوط" : "IP not configured";
            updateHybridConnection();
        } else {
            connect(ip, port);
        }
    }
''',
    "hybrid IP restore",
)
activity = replace_once(
    activity,
    '''    private void connect(String ip, int port) {
        try { pair.edit().putString("ip", ip).putInt("port", port).apply(); lastConnectAttemptAt = System.currentTimeMillis(); connectionOk = false; setConnection(t("connecting"), false); if (client != null) try { client.stop(); } catch (Throwable ignored) { } client = new TechProClient(ip, port, this); client.start(); }
        catch (Throwable error) { recordError("connect", error); setConnection(t("reconnecting"), false); }
    }
''',
    '''    private void connect(String ip, int port) {
        try {
            String host = clean(ip);
            if (host.isEmpty() || port < 1 || port > 65535) throw new IllegalArgumentException("Invalid IP/port");
            pair.edit().putString("ip", host).putInt("port", port).apply();
            lastConnectAttemptAt = System.currentTimeMillis();
            lanConnected = false;
            lanDetail = (ar() ? "جارٍ ربط IP: " : "Connecting IP: ") + host + ":" + port;
            updateHybridConnection();
            if (client != null) try { client.stop(); } catch (Throwable ignored) { }
            client = new TechProClient(host, port, this);
            client.start();
        } catch (Throwable error) {
            lanConnected = false;
            lanDetail = String.valueOf(error.getMessage());
            recordError("ip-connect", error);
            updateHybridConnection();
        }
    }
''',
    "hybrid IP connect",
)

pair_start = '    private void showPairDialog() {\n'
pair_end = '    private void setConnection(String value, boolean ok) {'
new_pair_dialog = r'''    private void showPairDialog() {
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(20), 0, dp(20), 0);
        TextView help = label(ar()
                ? "اكتب IP جهاز الكاشير الموجود على نفس الشبكة. المنفذ الافتراضي 4040."
                : "Enter the cashier device IP on the same network. Default port is 4040.",
                12, muted, false);
        help.setPadding(0, 0, 0, dp(8));
        form.addView(help);
        EditText ip = new EditText(this);
        ip.setHint("192.168.1.20");
        ip.setSingleLine(true);
        ip.setText(pair == null ? "" : pair.getString("ip", ""));
        form.addView(ip);
        EditText port = new EditText(this);
        port.setHint("4040");
        port.setSingleLine(true);
        port.setInputType(InputType.TYPE_CLASS_NUMBER);
        port.setText(String.valueOf(pair == null ? 4040 : pair.getInt("port", 4040)));
        form.addView(port);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(ar() ? "ربط IP الكاشير" : "Cashier IP connection")
                .setView(form)
                .setPositiveButton(ar() ? "ربط" : "Connect", null)
                .setNeutralButton(ar() ? "إلغاء ربط IP" : "Remove IP", null)
                .setNegativeButton(ar() ? "إلغاء" : "Cancel", null)
                .create();
        dialog.setOnShowListener(x -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                try {
                    String host = ip.getText().toString().trim();
                    int p = Integer.parseInt(port.getText().toString().trim());
                    if (host.isEmpty() || p < 1 || p > 65535) throw new IllegalArgumentException();
                    dialog.dismiss();
                    connect(host, p);
                } catch (Throwable error) {
                    port.setError("IP / Port");
                }
            });
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
                try { if (client != null) client.stop(); } catch (Throwable ignored) { }
                client = null;
                if (pair != null) pair.edit().remove("ip").remove("port").apply();
                lanConnected = false;
                lanDetail = ar() ? "تم إلغاء ربط IP" : "IP removed";
                dialog.dismiss();
                updateHybridConnection();
            });
        });
        dialog.show();
    }

'''
activity = replace_between(activity, pair_start, pair_end, new_pair_dialog, "cashier IP dialog")

old_pair_button = '        TextView pairButton = action(ar() ? "تحديث اتصال السحابة" : "Refresh cloud connection", false); pairButton.setOnClickListener(v -> restartCloudMode()); LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(46)); pp.setMargins(0, dp(10), 0, 0); panel.addView(pairButton, pp);\n'
new_pair_buttons = '''        String savedIp = pair == null ? "" : clean(pair.getString("ip", ""));
        TextView ipButton = action((ar() ? "ربط IP الكاشير" : "Connect cashier IP") + (savedIp.isEmpty() ? "" : " • " + savedIp), false);
        ipButton.setOnClickListener(v -> showPairDialog());
        LinearLayout.LayoutParams ipParams = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(46));
        ipParams.setMargins(0, dp(10), 0, 0);
        panel.addView(ipButton, ipParams);
        TextView cloudButton = action(ar() ? "تحديث اتصال السحابة" : "Refresh cloud connection", false);
        cloudButton.setOnClickListener(v -> restartCloudMode());
        LinearLayout.LayoutParams cloudParams = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(46));
        cloudParams.setMargins(0, dp(8), 0, 0);
        panel.addView(cloudButton, cloudParams);
'''
activity = replace_once(activity, old_pair_button, new_pair_buttons, "separate IP and cloud controls")

# Live empty-state diagnostics show both sources, not cloud only.
activity = replace_once(
    activity,
    '                String detail = clean(lastCloudDetail);\n',
    '                String detail = hybridDiagnostic();\n',
    "hybrid empty-state diagnostics",
)
activity = activity.replace(
    'case "waitingSub": return a ? "يراقب الطلبات المؤقتة والعادية تلقائيًا من خادم TechPro الصحيح." : "Temporary and paid orders are monitored automatically from the selected TechPro API.";',
    'case "waitingSub": return a ? "يستقبل من IP الكاشير والسحابة معًا، ويجمعهما بنفس رقم الفاتورة بدون تكرار." : "Cashier IP and cloud run together and merge by invoice number without duplicates.";'
)

# Stop both transports on an explicit sign-out.
activity = replace_once(
    activity,
    'setNegativeButton(t("logout"), (d, w) -> { try { if (cloudPoller != null) cloudPoller.stop(); } catch (Throwable ignored) { } if (store != null) store.clearActive();',
    'setNegativeButton(t("logout"), (d, w) -> { try { if (cloudPoller != null) cloudPoller.stop(); } catch (Throwable ignored) { } try { if (client != null) client.stop(); } catch (Throwable ignored) { } if (store != null) store.clearActive();',
    "stop IP on sign-out",
)

activity = activity.replace("TechPro Kitchen 4.7 Dual API", "TechPro Kitchen 4.8 Hybrid IP + Cloud")
activity = activity.replace("TechPro Kitchen V4.2", "TechPro Kitchen V4.8 Hybrid")

# Final safety/integration invariants.
for required in [
    "restoreConnection();",
    "handler.postDelayed(watchdog, 10_000L);",
    "private boolean cloudConnected;",
    "private boolean lanConnected;",
    "IP + السحابة متصلان",
    "new TechProClient(host, port, this)",
    "StrictInvoiceExtractor.extract(raw)",
    "if (hasInvoice(saved))",
    "if (before == null || changed) runOnUiThread(this::renderBoard);",
    "session.apiBase()",
    "hybridDiagnostic()",
    "TechPro Kitchen 4.8 Hybrid IP + Cloud",
]:
    if required not in activity:
        raise SystemExit(f"V4.8 activity invariant missing: {required}")
if 'boolean silent = connectionOk' in activity or 'boolean stuck = !connectionOk' in activity:
    raise SystemExit("V4.8 LAN watchdog still depends on combined cloud state")
if 'if (anyChange || (orders != null && !orders.isEmpty())) renderBoard();' in activity:
    raise SystemExit("V4.8 periodic cloud redraw returned")

activity_path.write_text(activity, encoding="utf-8")
print("TechPro Kitchen V4.8 hybrid cashier-IP + cloud transport applied successfully")

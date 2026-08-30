from pathlib import Path

MODERN = "https://posapifornewapp.techlight.sa/api/"
LEGACY = "https://posapi.techlight.sa/api/"


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if old not in text:
        raise SystemExit(f"V4.7 patch target not found: {label}")
    return text.replace(old, new, 1)


def replace_between(text: str, start_marker: str, end_marker: str, replacement: str, label: str) -> str:
    start = text.find(start_marker)
    end = text.find(end_marker, start + len(start_marker))
    if start < 0 or end < 0:
        raise SystemExit(f"V4.7 patch range not found: {label}")
    return text[:start] + replacement + text[end:]


# Login and product sync: prefer the current TechPro API, fall back to legacy.
account_path = Path("app/src/main/java/sa/techlight/customerdisplay/TechProAccountClient.java")
account = account_path.read_text(encoding="utf-8")

account = replace_once(
    account,
    f'    static final String API = "{LEGACY}";\n',
    f'    static final String MODERN_API = "{MODERN}";\n'
    f'    static final String LEGACY_API = "{LEGACY}";\n',
    "account API constants",
)
account = replace_once(
    account,
    '    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");\n',
    '    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");\n'
    '    private volatile String apiBase = MODERN_API;\n',
    "selected account API field",
)

login_start = "    public void login(String posCode, String userName, String password, LoginListener listener) {\n"
login_end = "    /** Matches LoginModel.toJson in the original TechPro app. */\n"
new_login = r'''    private interface HostLoginListener {
        void onSuccess(String token, String accountName);
        void onFailure(String message);
    }

    public void login(String posCode, String userName, String password, LoginListener listener) {
        final JSONObject payload;
        try {
            payload = buildLoginPayload(posCode, userName, password);
        } catch (Exception impossible) {
            failLogin(listener, "تعذّر تجهيز بيانات تسجيل الدخول");
            return;
        }

        attemptLogin(MODERN_API, payload, new HostLoginListener() {
            @Override public void onSuccess(String token, String accountName) {
                deliverLoginSuccess(listener, MODERN_API, token, accountName);
            }

            @Override public void onFailure(String modernMessage) {
                attemptLogin(LEGACY_API, payload, new HostLoginListener() {
                    @Override public void onSuccess(String token, String accountName) {
                        deliverLoginSuccess(listener, LEGACY_API, token, accountName);
                    }

                    @Override public void onFailure(String legacyMessage) {
                        String message = clean(legacyMessage);
                        if (message.isEmpty()) message = clean(modernMessage);
                        if (message.isEmpty()) message = "تعذّر الاتصال بحساب TechPro";
                        failLogin(listener, message);
                    }
                });
            }
        });
    }

    private void attemptLogin(String base, JSONObject payload, HostLoginListener result) {
        Request request = new Request.Builder()
                .url(normalizeApiBase(base) + "Account/login")
                .post(RequestBody.create(payload.toString(), JSON))
                .header("Accept", "application/json")
                .build();
        http.newCall(request).enqueue(new Callback() {
            @Override public void onFailure(Call call, IOException error) {
                result.onFailure(friendlyNetworkError(error));
            }

            @Override public void onResponse(Call call, Response response) {
                try (Response closeable = response) {
                    String raw = closeable.body() == null ? "" : closeable.body().string();
                    if (!closeable.isSuccessful()) {
                        result.onFailure(responseMessage(raw, closeable.code()));
                        return;
                    }
                    Object parsed = parseJson(raw);
                    String token = findTextRecursive(parsed, 0,
                            "accessToken", "access_token", "jwtToken", "jwt", "token");
                    if ((token == null || token.trim().isEmpty()) && parsed instanceof String) {
                        token = ((String) parsed).trim();
                    }
                    if (token == null || token.trim().isEmpty()) {
                        result.onFailure("تم الرد من TechPro لكن لم يصل رمز الجلسة");
                        return;
                    }
                    String accountName = findTextRecursive(parsed, 0,
                            "companyNameAr", "branchNameAr", "companyName", "branchName",
                            "nameAr", "displayName", "userName");
                    result.onSuccess(stripBearer(token), accountName == null ? "حساب TechPro" : accountName);
                } catch (Exception error) {
                    result.onFailure("تعذّر قراءة رد تسجيل الدخول");
                }
            }
        });
    }

    private void deliverLoginSuccess(LoginListener listener, String base, String token, String accountName) {
        apiBase = normalizeApiBase(base);
        main.post(() -> listener.onSuccess(token, accountName));
    }

    public String apiBase() {
        return normalizeApiBase(apiBase);
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }

    static String normalizeApiBase(String value) {
        String base = clean(value);
        if (base.isEmpty()) return MODERN_API;
        if (!base.endsWith("/")) base += "/";
        if (!base.endsWith("api/")) {
            if (base.endsWith("api")) base += "/";
            else base += "api/";
        }
        return base;
    }

'''
account = replace_between(account, login_start, login_end, new_login, "dual-host login")
account = account.replace('HttpUrl.get(API + "Items/', 'HttpUrl.get(apiBase() + "Items/')
account = account.replace('HttpUrl.get(API + "', 'HttpUrl.get(apiBase() + "')
if 'API + "' in account:
    raise SystemExit("V4.7 account client still references removed API constant")
for required in ["MODERN_API", "LEGACY_API", "attemptLogin(MODERN_API", "attemptLogin(LEGACY_API", "public String apiBase()"]:
    if required not in account:
        raise SystemExit(f"V4.7 account invariant missing: {required}")
account_path.write_text(account, encoding="utf-8")


# Persist the selected API host alongside the encrypted session token.
session_path = Path("app/src/main/java/sa/techlight/customerdisplay/TechProSession.java")
session = session_path.read_text(encoding="utf-8")
session = replace_once(
    session,
    '    private static final String SOFTWARE_SALT = "TechLight.Kitchen.Session.Fallback.v1";\n',
    '    private static final String SOFTWARE_SALT = "TechLight.Kitchen.Session.Fallback.v1";\n'
    f'    private static final String DEFAULT_API = "{MODERN}";\n',
    "session default API",
)

save_start = "    public synchronized void save(String token, String posCode, String userName, String accountName) throws Exception {\n"
save_end = "    private void saveEncrypted(\n"
new_save = r'''    public synchronized void save(String token, String posCode, String userName, String accountName) throws Exception {
        save(token, posCode, userName, accountName, DEFAULT_API);
    }

    public synchronized void save(
            String token,
            String posCode,
            String userName,
            String accountName,
            String apiBase
    ) throws Exception {
        if (token == null || token.trim().isEmpty()) throw new IllegalArgumentException("Missing token");
        String clearToken = token.trim();
        Exception primaryError = null;
        try {
            saveEncrypted(clearToken, getOrCreateKey(), "keystore", posCode, userName, accountName, apiBase);
            return;
        } catch (Exception error) {
            primaryError = error;
        }
        try {
            saveEncrypted(clearToken, softwareKey(), "software", posCode, userName, accountName, apiBase);
        } catch (Exception fallbackError) {
            if (primaryError != null) fallbackError.addSuppressed(primaryError);
            throw fallbackError;
        }
    }

'''
session = replace_between(session, save_start, save_end, new_save, "session save overload")
session = replace_once(
    session,
    "            String accountName\n    ) throws Exception {\n",
    "            String accountName,\n            String apiBase\n    ) throws Exception {\n",
    "saveEncrypted API argument",
)
session = replace_once(
    session,
    '                .putString("account_name", accountName == null ? "" : accountName.trim())\n'
    '                .putLong("login_at", System.currentTimeMillis())\n',
    '                .putString("account_name", accountName == null ? "" : accountName.trim())\n'
    '                .putString("api_base", normalizeApiBase(apiBase))\n'
    '                .putLong("login_at", System.currentTimeMillis())\n',
    "persist selected API",
)
session = replace_once(
    session,
    '    public String accountName() {\n        return preferences.getString("account_name", "");\n    }\n\n',
    '    public String accountName() {\n        return preferences.getString("account_name", "");\n    }\n\n'
    '    public String apiBase() {\n'
    '        return normalizeApiBase(preferences.getString("api_base", DEFAULT_API));\n'
    '    }\n\n',
    "session API getter",
)
session = replace_once(
    session,
    '    private SecretKey getOrCreateKey() throws Exception {\n',
    '    private static String normalizeApiBase(String value) {\n'
    '        String base = value == null ? "" : value.trim();\n'
    '        if (base.isEmpty()) return DEFAULT_API;\n'
    '        if (!base.endsWith("/")) base += "/";\n'
    '        if (!base.endsWith("api/")) {\n'
    '            if (base.endsWith("api")) base += "/";\n'
    '            else base += "api/";\n'
    '        }\n'
    '        return base;\n'
    '    }\n\n'
    '    private SecretKey getOrCreateKey() throws Exception {\n',
    "session API normalizer",
)
for required in ["api_base", "public String apiBase()", "String apiBase\n    ) throws Exception"]:
    if required not in session:
        raise SystemExit(f"V4.7 session invariant missing: {required}")
session_path.write_text(session, encoding="utf-8")


# Login screen stores the host that actually accepted the account.
login_path = Path("app/src/main/java/sa/techlight/customerdisplay/KitchenLoginActivityV3.java")
login = login_path.read_text(encoding="utf-8")
login = replace_once(
    login,
    "                    session.save(token, point, user, accountName);\n",
    "                    session.save(token, point, user, accountName, accountClient.apiBase());\n",
    "login persists selected API",
)
login_path.write_text(login, encoding="utf-8")


# Cloud KDS: use selected host and merge all temporary-order list shapes.
poller_path = Path("app/src/main/java/sa/techlight/customerdisplay/KitchenCloudOrdersPoller.java")
poller = poller_path.read_text(encoding="utf-8")
old_constants = f'''    static final String API = "{LEGACY}";
    static final String TEMP_LIST_ENDPOINT = API + "TemporaryOrders/List";
    static final String TEMP_ENDPOINT = API + "TemporaryOrders";
    static final String ORDER_TYPES_ENDPOINT = API + "ErpLov/168";
    static final String POS_INVOICE_ENDPOINT = API + "PosInvoice";
'''
new_constants = f'''    static final String MODERN_API = "{MODERN}";
    static final String LEGACY_API = "{LEGACY}";
    private static final String TEMP_LIST_PATH = "TemporaryOrders/List";
    private static final String TEMP_PATH = "TemporaryOrders";
    private static final String ORDER_TYPES_PATH = "ErpLov/168";
    private static final String POS_INVOICE_PATH = "PosInvoice";
'''
poller = replace_once(poller, old_constants, new_constants, "poller API paths")
poller = replace_once(
    poller,
    "    private final String posCode;\n    private final long sessionLoginAt;\n",
    "    private final String posCode;\n    private final String apiBase;\n    private final long sessionLoginAt;\n",
    "poller selected API field",
)

old_constructors = r'''    public KitchenCloudOrdersPoller(String token, String posCode, Listener listener) {
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
new_constructors = r'''    public KitchenCloudOrdersPoller(String token, String posCode, Listener listener) {
        this(token, posCode, System.currentTimeMillis(), MODERN_API, listener);
    }

    public KitchenCloudOrdersPoller(String token, String posCode, long sessionLoginAt, Listener listener) {
        this(token, posCode, sessionLoginAt, MODERN_API, listener);
    }

    public KitchenCloudOrdersPoller(
            String token,
            String posCode,
            long sessionLoginAt,
            String apiBase,
            Listener listener
    ) {
        this.token = stripBearer(token);
        this.posCode = clean(posCode);
        this.apiBase = normalizeApiBase(apiBase);
        this.sessionLoginAt = sessionLoginAt > 0L ? sessionLoginAt : startedAt;
        this.listener = listener;
        long baselineCutoff = Math.max(
                this.sessionLoginAt - SESSION_GRACE_MS,
                startedAt - RECENT_BASELINE_WINDOW_MS
        );
        this.temporaryTracker = new TemporaryDeltaTracker(baselineCutoff);
    }
'''
poller = replace_once(poller, old_constructors, new_constructors, "poller selected-host constructors")

old_fetch_block = r'''            String tempRaw = fetchTemporaryOrders();
            List<KitchenTemporaryOrdersApiClient.Candidate> tempCandidates = KitchenTemporaryOrdersApiClient.parseCandidates(tempRaw);
            List<KitchenOrder> temporaryAll = convertTemporary(tempCandidates, posCode, orderTypeNames);
'''
new_fetch_block = r'''            List<KitchenTemporaryOrdersApiClient.Candidate> tempCandidates = fetchTemporaryCandidates();
            List<KitchenOrder> temporaryAll = convertTemporary(tempCandidates, posCode, orderTypeNames);
'''
poller = replace_once(poller, old_fetch_block, new_fetch_block, "merged temporary candidates")

fetch_method_start = "    private String fetchTemporaryOrders() throws Exception {\n"
fetch_method_end = "    private List<KitchenOrder> fetchNewPaidInvoices() throws Exception {\n"
new_fetch_method = r'''    private List<KitchenTemporaryOrdersApiClient.Candidate> fetchTemporaryCandidates() throws Exception {
        ArrayList<KitchenTemporaryOrdersApiClient.Candidate> merged = new ArrayList<>();
        Exception lastError = null;
        boolean receivedResponse = false;

        HttpUrl paged = HttpUrl.get(apiBase + TEMP_PATH).newBuilder()
                .addQueryParameter("Page", "1")
                .addQueryParameter("PageSize", "300")
                .build();
        String[] urls = {
                paged.toString(),
                apiBase + TEMP_LIST_PATH,
                apiBase + TEMP_PATH
        };

        for (String url : urls) {
            try {
                String raw = executeGet(url);
                receivedResponse = true;
                mergeTemporaryCandidates(merged, KitchenTemporaryOrdersApiClient.parseCandidates(raw));
            } catch (Unauthorized unauthorized) {
                throw unauthorized;
            } catch (Exception error) {
                lastError = error;
            }
        }

        if (!merged.isEmpty() || receivedResponse) return merged;
        if (lastError != null) throw lastError;
        return merged;
    }

    static void mergeTemporaryCandidates(
            List<KitchenTemporaryOrdersApiClient.Candidate> target,
            List<KitchenTemporaryOrdersApiClient.Candidate> incoming
    ) {
        if (target == null || incoming == null) return;
        for (KitchenTemporaryOrdersApiClient.Candidate next : incoming) {
            if (next == null) continue;
            KitchenTemporaryOrdersApiClient.Candidate existing = null;
            String nextNumber = next.usableNumber();
            for (KitchenTemporaryOrdersApiClient.Candidate current : target) {
                if (current == null) continue;
                String currentNumber = current.usableNumber();
                if ((!nextNumber.isEmpty() && nextNumber.equals(currentNumber))
                        || (next.id > 0L && current.id == next.id)) {
                    existing = current;
                    break;
                }
            }
            if (existing == null) {
                target.add(next);
                continue;
            }
            if (clean(existing.number).isEmpty()) existing.number = next.number;
            if (clean(existing.code).isEmpty()) existing.code = next.code;
            if (clean(existing.table).isEmpty()) existing.table = next.table;
            if (clean(existing.orderType).isEmpty()) existing.orderType = next.orderType;
            if (existing.orderTypeId <= 0L) existing.orderTypeId = next.orderTypeId;
            if (clean(existing.note).isEmpty()) existing.note = next.note;
            if (clean(existing.posCode).isEmpty()) existing.posCode = next.posCode;
            if (existing.orderDate <= 0L) existing.orderDate = next.orderDate;
            if (next.items.size() > existing.items.size()) {
                existing.items.clear();
                existing.items.addAll(next.items);
            }
        }
    }

'''
poller = replace_between(poller, fetch_method_start, fetch_method_end, new_fetch_method, "multi-shape temporary fetch")

poller = poller.replace("POS_INVOICE_ENDPOINT", "apiBase + POS_INVOICE_PATH")
poller = poller.replace("ORDER_TYPES_ENDPOINT", "apiBase + ORDER_TYPES_PATH")
poller = poller.replace("TEMP_LIST_ENDPOINT", "apiBase + TEMP_LIST_PATH")
poller = poller.replace("TEMP_ENDPOINT", "apiBase + TEMP_PATH")

poller = replace_once(
    poller,
    '            String detail = "Cloud official API • tempActive=" + temporaryAll.size()\n'
    '                    + " • tempDelta=" + temporary.size() + " • paidNew=" + paidNew\n',
    '            String detail = "TechPro " + apiLabel() + " • tempActive=" + temporaryAll.size()\n'
    '                    + " • tempDelta=" + temporary.size() + " • paidNew=" + paidNew\n',
    "visible cloud host detail",
)
poller = replace_once(
    poller,
    '                listener.onStatus("Cloud connected", true);\n',
    '                listener.onStatus("Cloud connected • " + apiLabel(), true);\n',
    "connected host status",
)
poller = replace_once(
    poller,
    '            String detail = "Cloud retry • " + error.getClass().getSimpleName() + " • " + clean(error.getMessage());\n',
    '            String detail = "Cloud retry • " + apiLabel() + " • " + error.getClass().getSimpleName() + " • " + clean(error.getMessage());\n',
    "error host detail",
)
poller = replace_once(
    poller,
    "    private String executeGet(String url) throws Exception {\n",
    r'''    String apiLabel() {
        return apiBase.contains("posapifornewapp") ? "NEW" : "OLD";
    }

    static String normalizeApiBase(String value) {
        String base = clean(value);
        if (base.isEmpty()) return MODERN_API;
        if (!base.endsWith("/")) base += "/";
        if (!base.endsWith("api/")) {
            if (base.endsWith("api")) base += "/";
            else base += "api/";
        }
        return base;
    }

    private String executeGet(String url) throws Exception {
''',
    "poller API helpers",
)
for required in [
    "posapifornewapp.techlight.sa",
    "mergeTemporaryCandidates",
    "fetchTemporaryCandidates",
    "String apiLabel()",
    'listener.onStatus("Cloud connected • " + apiLabel()',
]:
    if required not in poller:
        raise SystemExit(f"V4.7 poller invariant missing: {required}")
poller_path.write_text(poller, encoding="utf-8")


# The old websocket resolver is no longer primary, but keep its fallback on the current host.
temp_client_path = Path("app/src/main/java/sa/techlight/customerdisplay/KitchenTemporaryOrdersApiClient.java")
temp_client = temp_client_path.read_text(encoding="utf-8")
temp_client = temp_client.replace(
    f'static final String API = "{LEGACY}";',
    f'static final String API = "{MODERN}";',
)
temp_client_path.write_text(temp_client, encoding="utf-8")


# Activity: pass selected host and expose compact diagnostics under the empty state.
activity_path = Path("app/src/main/java/sa/techlight/customerdisplay/KitchenActivityV42.java")
activity = activity_path.read_text(encoding="utf-8")
activity = replace_once(
    activity,
    "    private TextView connectionText;\n",
    "    private TextView connectionText;\n    private String lastCloudDetail = \"\";\n",
    "activity cloud diagnostic field",
)
old_poller_call = r'''            cloudPoller = new KitchenCloudOrdersPoller(
                    session == null ? "" : session.token(),
                    session == null ? "" : session.posCode(),
                    session == null ? System.currentTimeMillis() : session.loginAt(),
                    new KitchenCloudOrdersPoller.Listener() {
'''
new_poller_call = r'''            cloudPoller = new KitchenCloudOrdersPoller(
                    session == null ? "" : session.token(),
                    session == null ? "" : session.posCode(),
                    session == null ? System.currentTimeMillis() : session.loginAt(),
                    session == null ? KitchenCloudOrdersPoller.MODERN_API : session.apiBase(),
                    new KitchenCloudOrdersPoller.Listener() {
'''
activity = replace_once(activity, old_poller_call, new_poller_call, "activity passes selected host")

old_status = r'''                        @Override public void onStatus(String status, boolean connected) {
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
'''
new_status = r'''                        @Override public void onStatus(String status, boolean connected) {
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
activity = replace_once(activity, old_status, new_status, "activity visible host status")
activity = replace_once(
    activity,
    "    private void applyCloudSnapshot(List<KitchenOrder> orders, String detail) {\n        if (store == null) return;\n",
    "    private void applyCloudSnapshot(List<KitchenOrder> orders, String detail) {\n        lastCloudDetail = detail == null ? \"\" : detail;\n        if (store == null) return;\n",
    "snapshot diagnostic capture",
)
activity = replace_once(
    activity,
    '            else { emptyTitle.setText(t("waiting")); emptySub.setText(t("waitingSub")); }\n',
    '            else {\n'
    '                emptyTitle.setText(t("waiting"));\n'
    '                String detail = clean(lastCloudDetail);\n'
    '                emptySub.setText(t("waitingSub") + (detail.isEmpty() ? "" : "\\n" + detail));\n'
    '            }\n',
    "empty-state live diagnostic",
)
activity = activity.replace(
    'case "waitingSub": return a ? "TemporaryOrders للطلبات المؤقتة و PosInvoice للطلبات العادية — بدون IP أو ربط محلي." : "TemporaryOrders + PosInvoice are read from TechPro Cloud; no IP or LAN pairing required.";',
    'case "waitingSub": return a ? "يراقب الطلبات المؤقتة والعادية تلقائيًا من خادم TechPro الصحيح." : "Temporary and paid orders are monitored automatically from the selected TechPro API.";'
)
activity = activity.replace("TechPro Kitchen 4.6 Stable Cloud", "TechPro Kitchen 4.7 Dual API")
for required in [
    "session.apiBase()",
    "lastCloudDetail",
    "الخادم الجديد",
    'emptySub.setText(t("waitingSub")',
    "TechPro Kitchen 4.7 Dual API",
]:
    if required not in activity:
        raise SystemExit(f"V4.7 activity invariant missing: {required}")
activity_path.write_text(activity, encoding="utf-8")

print("TechPro Kitchen V4.7 dual API + merged cloud order sources applied successfully")

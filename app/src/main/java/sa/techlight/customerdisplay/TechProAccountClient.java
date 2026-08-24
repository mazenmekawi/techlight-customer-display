package sa.techlight.customerdisplay;

import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public final class TechProAccountClient {
    static final String API = "https://posapifornewapp.techlight.sa/api/";
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    public interface LoginListener {
        void onSuccess(String token, String accountName);
        void onFailure(String message);
    }

    public interface SyncListener {
        void onProgress(String message, int productsFound);
        void onSuccess(List<ProductCatalog.Product> products);
        void onFailure(String message, boolean unauthorized);
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final OkHttpClient http = new OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(35, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build();

    public void login(String userName, String password, LoginListener listener) {
        JSONObject payload = new JSONObject();
        try {
            // These names match TechPro's UserLoginModel in the original APK.
            payload.put("UserName", userName == null ? "" : userName.trim());
            payload.put("Password", password == null ? "" : password);
        } catch (Exception impossible) {
            failLogin(listener, "تعذّر تجهيز بيانات تسجيل الدخول");
            return;
        }
        Request request = new Request.Builder()
                .url(API + "Account/login")
                .post(RequestBody.create(payload.toString(), JSON))
                .header("Accept", "application/json")
                .build();
        http.newCall(request).enqueue(new Callback() {
            @Override public void onFailure(Call call, IOException error) {
                failLogin(listener, friendlyNetworkError(error));
            }

            @Override public void onResponse(Call call, Response response) {
                try (Response closeable = response) {
                    String raw = closeable.body() == null ? "" : closeable.body().string();
                    if (!closeable.isSuccessful()) {
                        failLogin(listener, responseMessage(raw, closeable.code()));
                        return;
                    }
                    Object parsed = parseJson(raw);
                    String token = findTextRecursive(parsed, 0,
                            "accessToken", "access_token", "jwtToken", "jwt", "token");
                    if ((token == null || token.trim().isEmpty()) && parsed instanceof String) {
                        token = ((String) parsed).trim();
                    }
                    if (token == null || token.trim().isEmpty()) {
                        failLogin(listener, "تم الرد من TechPro لكن لم يصل رمز الجلسة");
                        return;
                    }
                    String account = findTextRecursive(parsed, 0,
                            "companyNameAr", "branchNameAr", "companyName", "branchName",
                            "nameAr", "displayName", "userName");
                    String finalToken = stripBearer(token);
                    String finalAccount = account == null ? "حساب TechPro" : account;
                    main.post(() -> listener.onSuccess(finalToken, finalAccount));
                } catch (Exception error) {
                    failLogin(listener, "تعذّر قراءة رد تسجيل الدخول");
                }
            }
        });
    }

    public void syncCatalog(String token, SyncListener listener) {
        worker.execute(() -> {
            try {
                List<ProductCatalog.Product> products;
                try {
                    products = downloadPagedCatalog(token, listener);
                } catch (Unauthorized error) {
                    throw error;
                } catch (Exception pagedError) {
                    products = new ArrayList<>();
                }
                if (products.isEmpty()) {
                    products = downloadLegacyCatalog(token, listener);
                }
                if (products.isEmpty()) {
                    failSync(listener, "تم تسجيل الدخول لكن لم تصل أصناف هذا الحساب", false);
                    return;
                }
                List<ProductCatalog.Product> result = products;
                main.post(() -> listener.onSuccess(result));
            } catch (Unauthorized error) {
                failSync(listener, "انتهت جلسة TechPro. سجّل الدخول مرة أخرى.", true);
            } catch (Exception error) {
                failSync(listener, friendlyNetworkError(error), false);
            }
        });
    }

    public void shutdown() {
        worker.shutdownNow();
        http.dispatcher().cancelAll();
        http.connectionPool().evictAll();
    }

    private List<ProductCatalog.Product> downloadPagedCatalog(String token, SyncListener listener) throws Exception {
        Map<String, ProductCatalog.Product> all = new LinkedHashMap<>();
        String previousSignature = "";
        for (int page = 1; page <= 40; page++) {
            HttpUrl url = HttpUrl.get(API + "Items/GetItemsAndGroupsWithPaging").newBuilder()
                    .addQueryParameter("Page", String.valueOf(page))
                    .addQueryParameter("PageSize", "500")
                    .build();
            String raw = executeGet(url, token);
            List<ProductCatalog.Product> pageProducts = parseCatalog(raw);
            if (pageProducts.isEmpty()) break;

            String signature = pageSignature(pageProducts);
            if (page > 1 && signature.equals(previousSignature)) break;
            previousSignature = signature;
            for (ProductCatalog.Product product : pageProducts) mergeRecord(all, product);
            progress(listener, "جاري مزامنة الأصناف — الصفحة " + page, all.size());

            Set<Long> pageItems = new HashSet<>();
            for (ProductCatalog.Product product : pageProducts) pageItems.add(product.itemId);
            if (pageItems.size() < 500) break;
        }
        return new ArrayList<>(all.values());
    }

    private List<ProductCatalog.Product> downloadLegacyCatalog(String token, SyncListener listener) throws Exception {
        progress(listener, "جاري تجربة مسار المزامنة المتوافق", 0);
        String raw = executeGet(HttpUrl.get(API + "Items/GetItems"), token);
        List<ProductCatalog.Product> products = parseCatalog(raw);
        progress(listener, "تم استلام دليل الأصناف", products.size());
        return products;
    }

    private String executeGet(HttpUrl url, String token) throws Exception {
        Request request = new Request.Builder()
                .url(url)
                .get()
                .header("Accept", "application/json")
                .header("Authorization", "Bearer " + stripBearer(token))
                .build();
        try (Response response = http.newCall(request).execute()) {
            String raw = response.body() == null ? "" : response.body().string();
            if (response.code() == 401 || response.code() == 403) throw new Unauthorized();
            if (!response.isSuccessful()) throw new IOException(responseMessage(raw, response.code()));
            return raw;
        }
    }

    static List<ProductCatalog.Product> parseCatalog(String raw) {
        Object root = parseJson(raw);
        Map<String, ProductCatalog.Product> records = new LinkedHashMap<>();
        collectProducts(root, null, 0, records);
        return new ArrayList<>(records.values());
    }

    private static void collectProducts(
            Object value,
            ProductCatalog.Product inherited,
            int depth,
            Map<String, ProductCatalog.Product> output
    ) {
        if (value == null || value == JSONObject.NULL || depth > 14) return;
        Object structured = parseStructured(value);
        if (structured instanceof JSONArray) {
            JSONArray array = (JSONArray) structured;
            for (int index = 0; index < array.length(); index++) {
                collectProducts(array.opt(index), inherited, depth + 1, output);
            }
            return;
        }
        if (!(structured instanceof JSONObject)) return;

        JSONObject object = (JSONObject) structured;
        long explicitItemId = firstLong(object, "itemId", "productId", "ItemId", "ProductId");
        long genericId = firstLong(object, "id", "Id");
        long unitId = firstLong(object, "unitId", "selectedUnitId", "defaultUnitId", "UnitId");
        String nameAr = firstText(object,
                "itemNameAr", "displayNameAr", "nameAr", "arabicName", "ItemNameAr");
        String nameEn = firstText(object,
                "itemNameEn", "displayNameEn", "nameEn", "englishName", "ItemNameEn");
        String barcode = firstText(object, "unitBarcode", "barcode", "itemBarcode", "Barcode");
        String itemCode = firstText(object, "itemCode", "code", "itemNo", "ItemCode");
        double price = firstDouble(object,
                "salePrice", "unitPrice", "price", "unitPriceInclVat", "SalePrice");

        boolean hasItemMarkers = explicitItemId > 0 || barcode != null || itemCode != null
                || hasAnyKey(object,
                "isSlsAllow", "defaultUnitId", "units", "itemUnits", "salePrice", "vat", "itemNo");
        boolean unitLike = explicitItemId > 0 && (hasAnyKey(object,
                "unitId", "unitBarcode", "displayNameAr", "displayNameEn", "salePrice")
                || (genericId > 0 && genericId != explicitItemId));

        long itemId = explicitItemId;
        if (itemId <= 0 && hasItemMarkers) itemId = genericId;
        if (itemId <= 0 && inherited != null) itemId = inherited.itemId;
        if (unitLike && unitId <= 0 && genericId > 0 && genericId != itemId) unitId = genericId;

        ProductCatalog.Product current = inherited;
        if (itemId > 0 && (hasItemMarkers || explicitItemId > 0)) {
            current = new ProductCatalog.Product();
            current.itemId = itemId;
            current.unitId = Math.max(0, unitId);
            current.nameAr = unitLike && inherited != null && !ProductCatalog.clean(inherited.nameAr).isEmpty()
                    ? inherited.nameAr : coalesce(nameAr, inherited == null ? null : inherited.nameAr);
            current.nameEn = unitLike && inherited != null && !ProductCatalog.clean(inherited.nameEn).isEmpty()
                    ? inherited.nameEn : coalesce(nameEn, inherited == null ? null : inherited.nameEn);
            current.barcode = coalesce(barcode, unitLike ? null : inherited == null ? null : inherited.barcode);
            current.itemCode = coalesce(itemCode, inherited == null ? null : inherited.itemCode);
            current.price = price > 0 ? price : inherited == null ? 0 : inherited.price;
            mergeRecord(output, current);
        }

        Iterator<String> keys = object.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            Object child = object.opt(key);
            if (child instanceof JSONObject || child instanceof JSONArray || child instanceof String) {
                Object childStructured = parseStructured(child);
                if (childStructured instanceof JSONObject || childStructured instanceof JSONArray) {
                    collectProducts(childStructured, current, depth + 1, output);
                }
            }
        }
    }

    private static void mergeRecord(Map<String, ProductCatalog.Product> output, ProductCatalog.Product incoming) {
        if (incoming == null || incoming.itemId <= 0) return;
        String key = incoming.itemId + ":" + incoming.unitId + ":" + ProductCatalog.clean(incoming.barcode);
        ProductCatalog.Product existing = output.get(key);
        if (existing == null) {
            output.put(key, incoming.copy());
            return;
        }
        if (ProductCatalog.clean(existing.nameAr).isEmpty()) existing.nameAr = incoming.nameAr;
        if (ProductCatalog.clean(existing.nameEn).isEmpty()) existing.nameEn = incoming.nameEn;
        if (ProductCatalog.clean(existing.barcode).isEmpty()) existing.barcode = incoming.barcode;
        if (ProductCatalog.clean(existing.itemCode).isEmpty()) existing.itemCode = incoming.itemCode;
        if (existing.price <= 0 && incoming.price > 0) existing.price = incoming.price;
    }

    private static String pageSignature(List<ProductCatalog.Product> products) {
        if (products.isEmpty()) return "";
        ProductCatalog.Product first = products.get(0);
        ProductCatalog.Product last = products.get(products.size() - 1);
        return products.size() + ":" + first.itemId + ":" + last.itemId;
    }

    private void progress(SyncListener listener, String message, int count) {
        main.post(() -> listener.onProgress(message, count));
    }

    private void failLogin(LoginListener listener, String message) {
        main.post(() -> listener.onFailure(message));
    }

    private void failSync(SyncListener listener, String message, boolean unauthorized) {
        main.post(() -> listener.onFailure(message, unauthorized));
    }

    private static String friendlyNetworkError(Exception error) {
        String message = error == null ? "" : String.valueOf(error.getMessage());
        if (message.contains("Unable to resolve host") || message.contains("Failed to connect")) {
            return "تعذّر الوصول إلى خادم TechPro. تحقق من الإنترنت.";
        }
        if (message.contains("timeout") || message.contains("timed out")) {
            return "استغرق خادم TechPro وقتًا طويلًا. حاول مرة أخرى.";
        }
        if (!message.trim().isEmpty() && !"null".equalsIgnoreCase(message)) return message;
        return "تعذّر الاتصال بخادم TechPro";
    }

    private static String responseMessage(String raw, int code) {
        Object parsed = parseJson(raw);
        String message = findTextRecursive(parsed, 0,
                "message", "error", "errorMessage", "title", "detail", "Message");
        if (message != null && !message.trim().isEmpty()) return message;
        if (code == 400 || code == 401) return "اسم المستخدم أو كلمة المرور غير صحيحة";
        return "تعذّر تنفيذ الطلب في TechPro — HTTP " + code;
    }

    private static String stripBearer(String token) {
        if (token == null) return "";
        String value = token.trim();
        if (value.toLowerCase(Locale.US).startsWith("bearer ")) return value.substring(7).trim();
        return value;
    }

    private static Object parseJson(String raw) {
        if (raw == null || raw.trim().isEmpty()) return null;
        try {
            return new JSONTokener(raw.trim()).nextValue();
        } catch (Exception ignored) {
            return raw.trim();
        }
    }

    private static Object parseStructured(Object value) {
        if (value instanceof JSONObject || value instanceof JSONArray) return value;
        if (!(value instanceof String)) return value;
        String text = ((String) value).trim();
        if (!(text.startsWith("{") || text.startsWith("["))) return value;
        return parseJson(text);
    }

    private static String findTextRecursive(Object value, int depth, String... keys) {
        if (value == null || value == JSONObject.NULL || depth > 8) return null;
        Object structured = parseStructured(value);
        if (structured instanceof JSONObject) {
            JSONObject object = (JSONObject) structured;
            String direct = firstText(object, keys);
            if (direct != null) return direct;
            Iterator<String> iterator = object.keys();
            while (iterator.hasNext()) {
                String found = findTextRecursive(object.opt(iterator.next()), depth + 1, keys);
                if (found != null) return found;
            }
        } else if (structured instanceof JSONArray) {
            JSONArray array = (JSONArray) structured;
            for (int index = 0; index < array.length(); index++) {
                String found = findTextRecursive(array.opt(index), depth + 1, keys);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static String firstText(JSONObject object, String... keys) {
        for (String requested : keys) {
            Object raw = valueForKey(object, requested);
            if (raw == null || raw == JSONObject.NULL || raw instanceof JSONObject || raw instanceof JSONArray) continue;
            String value = String.valueOf(raw).trim();
            if (!value.isEmpty() && !"null".equalsIgnoreCase(value)) return value;
        }
        return null;
    }

    private static long firstLong(JSONObject object, String... keys) {
        for (String requested : keys) {
            Object raw = valueForKey(object, requested);
            if (raw instanceof Number) return ((Number) raw).longValue();
            if (raw != null && raw != JSONObject.NULL) {
                try { return Long.parseLong(String.valueOf(raw).trim()); }
                catch (Exception ignored) { }
            }
        }
        return 0;
    }

    private static double firstDouble(JSONObject object, String... keys) {
        for (String requested : keys) {
            Object raw = valueForKey(object, requested);
            if (raw instanceof Number) return ((Number) raw).doubleValue();
            if (raw != null && raw != JSONObject.NULL) {
                String value = String.valueOf(raw).replace(",", "").trim();
                try { return Double.parseDouble(value); }
                catch (Exception ignored) { }
            }
        }
        return 0;
    }

    private static boolean hasAnyKey(JSONObject object, String... keys) {
        for (String key : keys) if (valueForKey(object, key) != null) return true;
        return false;
    }

    private static Object valueForKey(JSONObject object, String requested) {
        if (object == null) return null;
        if (object.has(requested)) return object.opt(requested);
        String canonical = canonical(requested);
        Iterator<String> keys = object.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            if (canonical.equals(canonical(key))) return object.opt(key);
        }
        return null;
    }

    private static String canonical(String key) {
        return key == null ? "" : key.replace("_", "").replace("-", "").toLowerCase(Locale.US);
    }

    private static String coalesce(String value, String fallback) {
        String clean = ProductCatalog.clean(value);
        return clean.isEmpty() ? ProductCatalog.clean(fallback) : clean;
    }

    private static final class Unauthorized extends Exception { }
}

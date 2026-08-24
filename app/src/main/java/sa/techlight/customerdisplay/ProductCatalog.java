package sa.techlight.customerdisplay;

import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class ProductCatalog extends SQLiteOpenHelper {
    private static final String DB_NAME = "techpro_catalog.db";
    private static final int DB_VERSION = 1;

    public static final class Product {
        public long itemId;
        public long unitId;
        public String barcode = "";
        public String itemCode = "";
        public String nameAr = "";
        public String nameEn = "";
        public double price;

        public Product copy() {
            Product result = new Product();
            result.itemId = itemId;
            result.unitId = unitId;
            result.barcode = barcode;
            result.itemCode = itemCode;
            result.nameAr = nameAr;
            result.nameEn = nameEn;
            result.price = price;
            return result;
        }
    }

    private final SharedPreferences meta;

    public ProductCatalog(Context context) {
        super(context.getApplicationContext(), DB_NAME, null, DB_VERSION);
        meta = context.getApplicationContext().getSharedPreferences("catalog_meta", Context.MODE_PRIVATE);
    }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE products ("
                + "item_id INTEGER NOT NULL, "
                + "unit_id INTEGER NOT NULL DEFAULT 0, "
                + "barcode TEXT NOT NULL DEFAULT '', "
                + "item_code TEXT NOT NULL DEFAULT '', "
                + "name_ar TEXT NOT NULL DEFAULT '', "
                + "name_en TEXT NOT NULL DEFAULT '', "
                + "price REAL NOT NULL DEFAULT 0, "
                + "PRIMARY KEY(item_id, unit_id, barcode))");
        db.execSQL("CREATE INDEX idx_catalog_barcode ON products(barcode)");
        db.execSQL("CREATE INDEX idx_catalog_item_code ON products(item_code)");
    }

    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        db.execSQL("DROP TABLE IF EXISTS products");
        onCreate(db);
    }

    public synchronized int replaceAll(List<Product> incoming) {
        Map<String, Product> deduplicated = new LinkedHashMap<>();
        for (Product product : incoming) {
            if (product == null || product.itemId <= 0) continue;
            String key = product.itemId + ":" + product.unitId + ":" + clean(product.barcode);
            Product existing = deduplicated.get(key);
            if (existing == null) {
                deduplicated.put(key, product.copy());
            } else {
                merge(existing, product);
            }
        }

        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            db.delete("products", null, null);
            for (Product product : deduplicated.values()) {
                ContentValues values = new ContentValues();
                values.put("item_id", product.itemId);
                values.put("unit_id", product.unitId);
                values.put("barcode", clean(product.barcode));
                values.put("item_code", clean(product.itemCode));
                values.put("name_ar", clean(product.nameAr));
                values.put("name_en", clean(product.nameEn));
                values.put("price", product.price);
                db.insertWithOnConflict("products", null, values, SQLiteDatabase.CONFLICT_REPLACE);
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        meta.edit()
                .putInt("count", deduplicated.size())
                .putLong("synced_at", System.currentTimeMillis())
                .apply();
        return deduplicated.size();
    }

    public int count() {
        int cached = meta.getInt("count", -1);
        if (cached >= 0) return cached;
        try (Cursor cursor = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM products", null)) {
            int value = cursor.moveToFirst() ? cursor.getInt(0) : 0;
            meta.edit().putInt("count", value).apply();
            return value;
        }
    }

    public long syncedAt() {
        return meta.getLong("synced_at", 0);
    }

    public void clearCatalog() {
        getWritableDatabase().delete("products", null, null);
        meta.edit().clear().apply();
    }

    public Product find(long itemId, long unitId, String barcode, String itemCode) {
        SQLiteDatabase db = getReadableDatabase();
        Cursor cursor = null;
        try {
            if (itemId > 0 && unitId > 0) {
                cursor = db.query("products", null, "item_id=? AND unit_id=?",
                        new String[]{String.valueOf(itemId), String.valueOf(unitId)}, null, null, null, "1");
                if (cursor.moveToFirst()) return fromCursor(cursor);
                cursor.close();
                cursor = null;
            }
            if (itemId > 0) {
                cursor = db.query("products", null, "item_id=?",
                        new String[]{String.valueOf(itemId)}, null, null,
                        "CASE WHEN unit_id=0 THEN 0 ELSE 1 END, unit_id", "1");
                if (cursor.moveToFirst()) return fromCursor(cursor);
                cursor.close();
                cursor = null;
            }
            String normalizedBarcode = clean(barcode);
            if (!normalizedBarcode.isEmpty()) {
                cursor = db.query("products", null, "barcode=?",
                        new String[]{normalizedBarcode}, null, null, null, "1");
                if (cursor.moveToFirst()) return fromCursor(cursor);
                cursor.close();
                cursor = null;
            }
            String normalizedCode = clean(itemCode);
            if (!normalizedCode.isEmpty()) {
                cursor = db.query("products", null, "item_code=?",
                        new String[]{normalizedCode}, null, null, null, "1");
                if (cursor.moveToFirst()) return fromCursor(cursor);
            }
        } finally {
            if (cursor != null) cursor.close();
        }
        return null;
    }

    public int enrich(OrderState order) {
        if (order == null || order.items == null) return 0;
        int resolved = 0;
        for (OrderState.Item item : order.items) {
            Product product = find(item.itemId, item.unitId, item.barcode, item.itemCode);
            if (product == null) {
                if (item.name == null || item.name.trim().isEmpty()) {
                    item.name = item.itemId > 0 ? "صنف #" + item.itemId : "صنف";
                }
                continue;
            }
            if (item.name == null || item.name.trim().isEmpty() || "صنف".equals(item.name)) {
                item.name = !clean(product.nameAr).isEmpty() ? product.nameAr : product.nameEn;
            }
            if (item.unitPrice <= 0.00001 && product.price > 0) item.unitPrice = product.price;
            if (item.itemId <= 0) item.itemId = product.itemId;
            if (item.unitId <= 0) item.unitId = product.unitId;
            resolved++;
        }
        return resolved;
    }

    private Product fromCursor(Cursor cursor) {
        Product product = new Product();
        product.itemId = cursor.getLong(cursor.getColumnIndexOrThrow("item_id"));
        product.unitId = cursor.getLong(cursor.getColumnIndexOrThrow("unit_id"));
        product.barcode = cursor.getString(cursor.getColumnIndexOrThrow("barcode"));
        product.itemCode = cursor.getString(cursor.getColumnIndexOrThrow("item_code"));
        product.nameAr = cursor.getString(cursor.getColumnIndexOrThrow("name_ar"));
        product.nameEn = cursor.getString(cursor.getColumnIndexOrThrow("name_en"));
        product.price = cursor.getDouble(cursor.getColumnIndexOrThrow("price"));
        return product;
    }

    private static void merge(Product destination, Product source) {
        if (clean(destination.barcode).isEmpty()) destination.barcode = clean(source.barcode);
        if (clean(destination.itemCode).isEmpty()) destination.itemCode = clean(source.itemCode);
        if (clean(destination.nameAr).isEmpty()) destination.nameAr = clean(source.nameAr);
        if (clean(destination.nameEn).isEmpty()) destination.nameEn = clean(source.nameEn);
        if (destination.price <= 0 && source.price > 0) destination.price = source.price;
        if (destination.unitId <= 0 && source.unitId > 0) destination.unitId = source.unitId;
    }

    static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}

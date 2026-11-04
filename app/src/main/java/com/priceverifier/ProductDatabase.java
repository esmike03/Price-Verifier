package com.priceverifier;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.database.sqlite.SQLiteStatement;

import java.util.Collections;
import java.util.List;
import java.util.ArrayList;

final class ProductDatabase extends SQLiteOpenHelper {
    private static final String DB_NAME = "price_verifier.db";
    private static final int DB_VERSION = 6;
    private static final int BULK_IMPORT_BATCH_SIZE = 5000;
    private static final String STAGING_TABLE = "products_import_staging";

    private boolean stagedImportRunning;

    ProductDatabase(Context context) {
        super(context.getApplicationContext(), DB_NAME, null, DB_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE products (" +
                "barcode TEXT PRIMARY KEY NOT NULL, " +
                "name TEXT NOT NULL, " +
                "price_cents INTEGER NOT NULL, " +
                "uom TEXT, " +
                "category TEXT, " +
                "item_code TEXT, " +
                "updated_at INTEGER NOT NULL)");
        db.execSQL("CREATE INDEX idx_products_name ON products(name)");
        db.execSQL("CREATE TABLE import_history (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "source_name TEXT NOT NULL, " +
                "total_rows INTEGER NOT NULL, " +
                "valid_rows INTEGER NOT NULL, " +
                "inserted INTEGER NOT NULL, " +
                "updated INTEGER NOT NULL, " +
                "skipped INTEGER NOT NULL, " +
                "imported_at INTEGER NOT NULL)");
        createPromoTable(db);
    }

    // Promos live in their own table so catalog imports (which swap the whole
    // products table) never touch them.
    private static void createPromoTable(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE promos (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "barcode TEXT NOT NULL, " +
                "source TEXT NOT NULL, " +
                "label TEXT NOT NULL, " +
                "type TEXT NOT NULL, " +
                "value INTEGER NOT NULL, " +
                "buy_qty INTEGER NOT NULL DEFAULT 0, " +
                "take_qty INTEGER NOT NULL DEFAULT 0, " +
                "start_date TEXT NOT NULL DEFAULT '', " +
                "end_date TEXT NOT NULL DEFAULT '', " +
                "updated_at INTEGER NOT NULL)");
        db.execSQL("CREATE INDEX idx_promos_barcode ON promos(barcode)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        int version = oldVersion;
        if (oldVersion == 1 && newVersion >= 2) {
            db.execSQL("DROP INDEX IF EXISTS idx_products_name");
            db.execSQL("ALTER TABLE products RENAME TO products_old");
            db.execSQL("CREATE TABLE products (" +
                    "barcode TEXT PRIMARY KEY NOT NULL, " +
                    "sku TEXT, " +
                    "name TEXT NOT NULL, " +
                    "price_cents INTEGER NOT NULL, " +
                    "department TEXT, " +
                    "updated_at INTEGER NOT NULL)");
            db.execSQL("INSERT INTO products (barcode, sku, name, price_cents, department, updated_at) " +
                    "SELECT barcode, sku, name, price_cents, department, updated_at FROM products_old");
            db.execSQL("DROP TABLE products_old");
            db.execSQL("CREATE INDEX idx_products_name ON products(name)");
            version = 2;
        }
        if (version == 2 && newVersion >= 3) {
            db.execSQL("DROP INDEX IF EXISTS idx_products_name");
            db.execSQL("ALTER TABLE products RENAME TO products_old");
            db.execSQL("CREATE TABLE products (" +
                    "barcode TEXT PRIMARY KEY NOT NULL, " +
                    "item_code TEXT, " +
                    "name TEXT NOT NULL, " +
                    "price_cents INTEGER NOT NULL, " +
                    "uom TEXT, " +
                    "category TEXT, " +
                    "updated_at INTEGER NOT NULL)");
            db.execSQL("INSERT INTO products (barcode, item_code, name, price_cents, uom, category, updated_at) " +
                    "SELECT barcode, sku, name, price_cents, NULL, department, updated_at FROM products_old");
            db.execSQL("DROP TABLE products_old");
            db.execSQL("CREATE INDEX idx_products_name ON products(name)");
            version = 3;
        }
        if (version == 3 && newVersion >= 4) {
            db.execSQL("DROP INDEX IF EXISTS idx_products_name");
            db.execSQL("ALTER TABLE products RENAME TO products_old");
            db.execSQL("CREATE TABLE products (" +
                    "barcode TEXT PRIMARY KEY NOT NULL, " +
                    "name TEXT NOT NULL, " +
                    "price_cents INTEGER NOT NULL, " +
                    "uom TEXT, " +
                    "category TEXT, " +
                    "updated_at INTEGER NOT NULL)");
            db.execSQL("INSERT INTO products (barcode, name, price_cents, uom, category, updated_at) " +
                    "SELECT barcode, name, price_cents, uom, category, updated_at FROM products_old");
            db.execSQL("DROP TABLE products_old");
            db.execSQL("CREATE INDEX idx_products_name ON products(name)");
            version = 4;
        }
        if (version == 4 && newVersion >= 5) {
            db.execSQL("ALTER TABLE products ADD COLUMN item_code TEXT");
            version = 5;
        }
        if (version == 5 && newVersion >= 6) {
            createPromoTable(db);
            version = 6;
        }
        if (version == newVersion) {
            return;
        }
        throw new IllegalStateException("Database migration missing from " + oldVersion + " to " + newVersion);
    }

    Product findByBarcode(String barcode) {
        String normalized = Product.normalizeBarcode(barcode);
        if (normalized.isEmpty()) {
            return null;
        }
        for (String candidate : Product.lookupVariants(normalized)) {
            Product match = findExactByBarcode(candidate);
            if (match != null) {
                return match;
            }
        }
        return null;
    }

    private Product findExactByBarcode(String barcode) {
        SQLiteDatabase db = getReadableDatabase();
        try (Cursor cursor = db.query("products", null, "barcode = ?",
                new String[]{barcode}, null, null, null, "1")) {
            if (!cursor.moveToFirst()) {
                return null;
            }
            return fromCursor(cursor);
        }
    }

    int countProducts() {
        SQLiteDatabase db = getReadableDatabase();
        try (Cursor cursor = db.rawQuery("SELECT COUNT(*) FROM products", null)) {
            return cursor.moveToFirst() ? cursor.getInt(0) : 0;
        }
    }

    void clearProducts() {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            db.delete("products", null, null);
            db.delete("import_history", null, null);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    ProductImportResult upsertProducts(List<Product> products, int totalRows, String sourceName) {
        try (ImportSession session = beginImport(sourceName)) {
            for (Product product : products) {
                session.accept(product);
            }
            return session.finish(totalRows);
        }
    }

    boolean upsertProduct(Product product) {
        boolean existed = exists(getReadableDatabase(), product.barcode);
        upsertProducts(Collections.singletonList(product), 1, "Manual entry");
        return existed;
    }

    ImportSession beginImport(String sourceName) {
        return new ImportSession(getWritableDatabase(), sourceName);
    }

    synchronized StagedImportSession beginStagedImport(String sourceName) {
        if (stagedImportRunning) {
            throw new IllegalStateException("Another product import is already running.");
        }
        stagedImportRunning = true;
        try {
            return new StagedImportSession(getWritableDatabase(), sourceName);
        } catch (RuntimeException ex) {
            stagedImportRunning = false;
            throw ex;
        }
    }

    private synchronized void releaseStagedImport() {
        stagedImportRunning = false;
    }

    List<Product> listProducts(String query, int limit, int offset) {
        SQLiteDatabase db = getReadableDatabase();
        String cleaned = Product.clean(query);
        String selection = null;
        String[] args = null;
        if (!cleaned.isEmpty()) {
            selection = "barcode LIKE ? ESCAPE '\\' OR name LIKE ? ESCAPE '\\' " +
                    "OR item_code LIKE ? ESCAPE '\\' OR category LIKE ? ESCAPE '\\'";
            String match = "%" + escapeLike(cleaned) + "%";
            args = new String[]{match, match, match, match};
        }
        List<Product> products = new ArrayList<>();
        try (Cursor cursor = db.query("products", null, selection, args, null, null,
                "name COLLATE NOCASE, barcode", offset + "," + limit)) {
            while (cursor.moveToNext()) {
                products.add(fromCursor(cursor));
            }
        }
        return products;
    }

    int countProducts(String query) {
        SQLiteDatabase db = getReadableDatabase();
        String cleaned = Product.clean(query);
        String sql = "SELECT COUNT(*) FROM products";
        String[] args = null;
        if (!cleaned.isEmpty()) {
            sql += " WHERE barcode LIKE ? ESCAPE '\\' OR name LIKE ? ESCAPE '\\' " +
                    "OR item_code LIKE ? ESCAPE '\\' OR category LIKE ? ESCAPE '\\'";
            String match = "%" + escapeLike(cleaned) + "%";
            args = new String[]{match, match, match, match};
        }
        try (Cursor cursor = db.rawQuery(sql, args)) {
            return cursor.moveToFirst() ? cursor.getInt(0) : 0;
        }
    }

    void deleteProduct(String barcode) {
        getWritableDatabase().delete("products", "barcode = ?",
                new String[]{Product.normalizeBarcode(barcode)});
    }

    /** Every promo (server and device) for a product, matching any encoding of its barcode. */
    List<Promo> promosFor(String barcode) {
        List<String> variants = Product.lookupVariants(Product.normalizeBarcode(barcode));
        List<Promo> promos = new ArrayList<>();
        if (variants.isEmpty()) {
            return promos;
        }
        StringBuilder in = new StringBuilder();
        for (int i = 0; i < variants.size(); i++) {
            in.append(i == 0 ? "?" : ",?");
        }
        try (Cursor cursor = getReadableDatabase().query("promos", null,
                "barcode IN (" + in + ")", variants.toArray(new String[0]),
                null, null, "source DESC, start_date, id")) {
            while (cursor.moveToNext()) {
                promos.add(promoFromCursor(cursor));
            }
        }
        return promos;
    }

    int countPromos(String source) {
        try (Cursor cursor = getReadableDatabase().rawQuery(
                "SELECT COUNT(*) FROM promos WHERE source = ?", new String[]{source})) {
            return cursor.moveToFirst() ? cursor.getInt(0) : 0;
        }
    }

    /** Adds a device promo, or updates it when {@code promo.id} is set. */
    void saveDevicePromo(Promo promo) {
        ContentValues values = promoValues(promo, Promo.SOURCE_DEVICE);
        SQLiteDatabase db = getWritableDatabase();
        if (promo.id > 0) {
            db.update("promos", values, "id = ? AND source = ?",
                    new String[]{String.valueOf(promo.id), Promo.SOURCE_DEVICE});
        } else {
            db.insertOrThrow("promos", null, values);
        }
    }

    void deleteDevicePromo(long id) {
        getWritableDatabase().delete("promos", "id = ? AND source = ?",
                new String[]{String.valueOf(id), Promo.SOURCE_DEVICE});
    }

    /**
     * Replaces every server promo with {@code promos}. The server wins: device
     * promos for any product the server now has a promo for are removed.
     */
    void replaceServerPromos(List<Promo> promos) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            db.delete("promos", "source = ?", new String[]{Promo.SOURCE_SERVER});
            java.util.Set<String> covered = new java.util.HashSet<>();
            for (Promo promo : promos) {
                db.insertOrThrow("promos", null, promoValues(promo, Promo.SOURCE_SERVER));
                covered.addAll(Product.lookupVariants(promo.barcode));
            }
            for (String code : covered) {
                db.delete("promos", "barcode = ? AND source = ?",
                        new String[]{code, Promo.SOURCE_DEVICE});
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    private static ContentValues promoValues(Promo promo, String source) {
        ContentValues values = new ContentValues();
        values.put("barcode", promo.barcode);
        values.put("source", source);
        values.put("label", promo.label);
        values.put("type", Promo.typeKey(promo.type));
        values.put("value", promo.value);
        values.put("buy_qty", promo.buyQty);
        values.put("take_qty", promo.takeQty);
        values.put("start_date", promo.startDate);
        values.put("end_date", promo.endDate);
        values.put("updated_at", System.currentTimeMillis());
        return values;
    }

    private static Promo promoFromCursor(Cursor cursor) {
        Promo.Type type = Promo.parseType(cursor.getString(cursor.getColumnIndexOrThrow("type")));
        return new Promo(
                cursor.getLong(cursor.getColumnIndexOrThrow("id")),
                cursor.getString(cursor.getColumnIndexOrThrow("barcode")),
                cursor.getString(cursor.getColumnIndexOrThrow("source")),
                cursor.getString(cursor.getColumnIndexOrThrow("label")),
                type == null ? Promo.Type.FIXED : type,
                cursor.getLong(cursor.getColumnIndexOrThrow("value")),
                cursor.getInt(cursor.getColumnIndexOrThrow("buy_qty")),
                cursor.getInt(cursor.getColumnIndexOrThrow("take_qty")),
                cursor.getString(cursor.getColumnIndexOrThrow("start_date")),
                cursor.getString(cursor.getColumnIndexOrThrow("end_date")));
    }

    private static String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private static ContentValues valuesFor(Product product) {
        ContentValues values = new ContentValues();
        values.put("barcode", product.barcode);
        values.put("name", product.name);
        values.put("price_cents", product.priceCents);
        values.put("uom", product.uom);
        values.put("category", product.category);
        values.put("item_code", product.itemCode);
        values.put("updated_at", product.updatedAt);
        return values;
    }

    private static boolean exists(SQLiteDatabase db, String barcode) {
        try (Cursor cursor = db.query("products", new String[]{"barcode"}, "barcode = ?",
                new String[]{barcode}, null, null, null, "1")) {
            return cursor.moveToFirst();
        }
    }

    final class StagedImportSession implements AutoCloseable {
        private final SQLiteDatabase db;
        private final String sourceName;
        private final SQLiteStatement insert;
        private final SQLiteStatement update;
        private int inserted;
        private int updated;
        private int validRows;
        private int batchRows;
        private boolean batchOpen;
        private boolean finished;
        private boolean closed;

        StagedImportSession(SQLiteDatabase db, String sourceName) {
            this.db = db;
            this.sourceName = sourceName;
            prepareStagingTable();
            insert = db.compileStatement("INSERT OR IGNORE INTO " + STAGING_TABLE + " " +
                    "(barcode, name, price_cents, uom, category, item_code, updated_at) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?)");
            update = db.compileStatement("UPDATE " + STAGING_TABLE + " SET name = ?, " +
                    "price_cents = ?, uom = ?, category = ?, item_code = ?, updated_at = ? " +
                    "WHERE barcode = ?");
        }

        private void prepareStagingTable() {
            db.beginTransaction();
            try {
                db.execSQL("DROP TABLE IF EXISTS " + STAGING_TABLE);
                db.execSQL("CREATE TABLE " + STAGING_TABLE + " (" +
                        "barcode TEXT PRIMARY KEY NOT NULL, " +
                        "name TEXT NOT NULL, " +
                        "price_cents INTEGER NOT NULL, " +
                        "uom TEXT, " +
                        "category TEXT, " +
                        "item_code TEXT, " +
                        "updated_at INTEGER NOT NULL)");
                // Preserve local/manual products while the hosted catalog is applied.
                db.execSQL("INSERT INTO " + STAGING_TABLE + " " +
                        "(barcode, name, price_cents, uom, category, item_code, updated_at) " +
                        "SELECT barcode, name, price_cents, uom, category, item_code, updated_at " +
                        "FROM products");
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
        }

        void accept(Product product) {
            ensureBatch();
            bindInsert(insert, product);
            long rowId = insert.executeInsert();
            if (rowId == -1L) {
                bindUpdate(update, product);
                update.executeUpdateDelete();
                updated++;
            } else {
                inserted++;
            }
            validRows++;
            batchRows++;
            if (batchRows >= BULK_IMPORT_BATCH_SIZE) {
                commitBatch();
            }
        }

        private void ensureBatch() {
            if (!batchOpen) {
                db.beginTransaction();
                batchOpen = true;
                batchRows = 0;
            }
        }

        private void commitBatch() {
            if (!batchOpen) {
                return;
            }
            db.setTransactionSuccessful();
            db.endTransaction();
            batchOpen = false;
            batchRows = 0;
        }

        ProductImportResult finish(int totalRows) {
            commitBatch();
            db.beginTransaction();
            try {
                db.execSQL("DROP INDEX IF EXISTS idx_products_name");
                db.execSQL("DROP TABLE IF EXISTS products_previous");
                db.execSQL("ALTER TABLE products RENAME TO products_previous");
                db.execSQL("ALTER TABLE " + STAGING_TABLE + " RENAME TO products");
                db.execSQL("DROP TABLE products_previous");
                db.execSQL("CREATE INDEX idx_products_name ON products(name)");

                ContentValues history = new ContentValues();
                history.put("source_name", sourceName);
                history.put("total_rows", totalRows);
                history.put("valid_rows", validRows);
                history.put("inserted", inserted);
                history.put("updated", updated);
                history.put("skipped", Math.max(0, totalRows - validRows));
                history.put("imported_at", System.currentTimeMillis());
                db.insertOrThrow("import_history", null, history);
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
            finished = true;
            return new ProductImportResult(totalRows, validRows, inserted, updated,
                    Math.max(0, totalRows - validRows), sourceName, "Import complete");
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            try {
                if (batchOpen) {
                    db.endTransaction();
                    batchOpen = false;
                }
                insert.close();
                update.close();
                if (!finished) {
                    db.execSQL("DROP TABLE IF EXISTS " + STAGING_TABLE);
                }
            } finally {
                releaseStagedImport();
            }
        }

        private void bindInsert(SQLiteStatement statement, Product product) {
            statement.clearBindings();
            statement.bindString(1, product.barcode);
            statement.bindString(2, product.name);
            statement.bindLong(3, product.priceCents);
            statement.bindString(4, product.uom);
            statement.bindString(5, product.category);
            statement.bindString(6, product.itemCode);
            statement.bindLong(7, product.updatedAt);
        }

        private void bindUpdate(SQLiteStatement statement, Product product) {
            statement.clearBindings();
            statement.bindString(1, product.name);
            statement.bindLong(2, product.priceCents);
            statement.bindString(3, product.uom);
            statement.bindString(4, product.category);
            statement.bindString(5, product.itemCode);
            statement.bindLong(6, product.updatedAt);
            statement.bindString(7, product.barcode);
        }
    }

    final class ImportSession implements AutoCloseable {
        private final SQLiteDatabase db;
        private final String sourceName;
        private final SQLiteStatement insert;
        private final SQLiteStatement update;
        private int inserted;
        private int updated;
        private int validRows;

        ImportSession(SQLiteDatabase db, String sourceName) {
            this.db = db;
            this.sourceName = sourceName;
            db.beginTransaction();
            insert = db.compileStatement("INSERT OR IGNORE INTO products " +
                    "(barcode, name, price_cents, uom, category, item_code, updated_at) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?)");
            update = db.compileStatement("UPDATE products SET name = ?, price_cents = ?, " +
                    "uom = ?, category = ?, item_code = ?, updated_at = ? WHERE barcode = ?");
        }

        void accept(Product product) {
            bindInsert(insert, product);
            long rowId = insert.executeInsert();
            if (rowId == -1L) {
                bindUpdate(update, product);
                update.executeUpdateDelete();
                updated++;
            } else {
                inserted++;
            }
            validRows++;
        }

        ProductImportResult finish(int totalRows) {
            ContentValues history = new ContentValues();
            history.put("source_name", sourceName);
            history.put("total_rows", totalRows);
            history.put("valid_rows", validRows);
            history.put("inserted", inserted);
            history.put("updated", updated);
            history.put("skipped", Math.max(0, totalRows - validRows));
            history.put("imported_at", System.currentTimeMillis());
            db.insert("import_history", null, history);
            db.setTransactionSuccessful();
            return new ProductImportResult(totalRows, validRows, inserted, updated,
                    Math.max(0, totalRows - validRows), sourceName, "Import complete");
        }

        @Override
        public void close() {
            insert.close();
            update.close();
            db.endTransaction();
        }

        private void bindInsert(SQLiteStatement statement, Product product) {
            statement.clearBindings();
            statement.bindString(1, product.barcode);
            statement.bindString(2, product.name);
            statement.bindLong(3, product.priceCents);
            statement.bindString(4, product.uom);
            statement.bindString(5, product.category);
            statement.bindString(6, product.itemCode);
            statement.bindLong(7, product.updatedAt);
        }

        private void bindUpdate(SQLiteStatement statement, Product product) {
            statement.clearBindings();
            statement.bindString(1, product.name);
            statement.bindLong(2, product.priceCents);
            statement.bindString(3, product.uom);
            statement.bindString(4, product.category);
            statement.bindString(5, product.itemCode);
            statement.bindLong(6, product.updatedAt);
            statement.bindString(7, product.barcode);
        }
    }

    private static Product fromCursor(Cursor cursor) {
        return new Product(
                cursor.getString(cursor.getColumnIndexOrThrow("barcode")),
                cursor.getString(cursor.getColumnIndexOrThrow("name")),
                cursor.getLong(cursor.getColumnIndexOrThrow("price_cents")),
                cursor.getString(cursor.getColumnIndexOrThrow("uom")),
                cursor.getString(cursor.getColumnIndexOrThrow("category")),
                cursor.getString(cursor.getColumnIndexOrThrow("item_code")),
                cursor.getLong(cursor.getColumnIndexOrThrow("updated_at")));
    }
}

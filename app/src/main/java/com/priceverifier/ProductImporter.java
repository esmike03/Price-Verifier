package com.priceverifier;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PushbackReader;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class ProductImporter {
    static final class Batch {
        final List<Product> products;
        final int totalRows;

        Batch(List<Product> products, int totalRows) {
            this.products = products;
            this.totalRows = totalRows;
        }
    }

    Batch parse(String fileName, InputStream inputStream) throws Exception {
        List<List<String>> rows = parseCsv(inputStream);
        return rowsToProducts(rows);
    }

    ProductImportResult importIntoDatabase(InputStream inputStream, ProductDatabase database,
                                           String sourceName) throws Exception {
        try (CsvReader reader = new CsvReader(inputStream)) {
            List<String> headerRow = reader.readRow();
            if (headerRow == null) {
                try (ProductDatabase.StagedImportSession session =
                             database.beginStagedImport(sourceName)) {
                    return session.finish(0);
                }
            }
            Columns columns = columnsFor(headerRow);
            int totalRows = 0;
            long now = System.currentTimeMillis();
            try (ProductDatabase.StagedImportSession session =
                         database.beginStagedImport(sourceName)) {
                List<String> row;
                while ((row = reader.readRow()) != null) {
                    totalRows++;
                    Product product = productFromRow(row, columns, now);
                    if (product != null) {
                        session.accept(product);
                    }
                }
                return session.finish(totalRows);
            }
        }
    }

    private Batch rowsToProducts(List<List<String>> rows) {
        List<Product> products = new ArrayList<>();
        if (rows.isEmpty()) {
            return new Batch(products, 0);
        }

        Columns columns = columnsFor(rows.get(0));
        long now = System.currentTimeMillis();
        for (int i = 1; i < rows.size(); i++) {
            Product product = productFromRow(rows.get(i), columns, now);
            if (product != null) {
                products.add(product);
            }
        }
        return new Batch(products, Math.max(0, rows.size() - 1));
    }

    private static Columns columnsFor(List<String> headerRow) {
        Map<String, Integer> headers = headerMap(headerRow);
        Columns columns = new Columns(
                firstPresent(headers, "barcode", "bar code", "upc", "ean", "code"),
                firstPresent(headers, "name", "product", "description", "item", "item name"),
                firstPresent(headers, "price", "amount", "retail", "srp", "selling price"),
                firstPresent(headers, "uom", "unit", "unit of measure"),
                firstPresent(headers, "category", "department"),
                firstPresent(headers, "item code", "item_code", "itemcode", "sku", "item sku"));
        if (columns.barcode < 0 || columns.name < 0 || columns.price < 0) {
            throw new IllegalArgumentException("Import file must include barcode, name, and price columns.");
        }
        return columns;
    }

    private static Product productFromRow(List<String> row, Columns columns, long now) {
        String barcode = Product.normalizeBarcode(valueAt(row, columns.barcode));
        String name = Product.clean(valueAt(row, columns.name));
        Long priceCents = parsePriceCents(valueAt(row, columns.price));
        if (barcode.isEmpty() || name.isEmpty() || priceCents == null || priceCents < 0) {
            return null;
        }
        return new Product(barcode, name, priceCents, valueAt(row, columns.uom),
                valueAt(row, columns.category), valueAt(row, columns.itemCode), now);
    }

    private static List<List<String>> parseCsv(InputStream inputStream) throws IOException {
        List<List<String>> rows = new ArrayList<>();
        try (CsvReader reader = new CsvReader(inputStream)) {
            List<String> row;
            while ((row = reader.readRow()) != null) {
                rows.add(row);
            }
        }
        return rows;
    }

    private static final class Columns {
        final int barcode;
        final int name;
        final int price;
        final int uom;
        final int category;
        final int itemCode;

        Columns(int barcode, int name, int price, int uom, int category, int itemCode) {
            this.barcode = barcode;
            this.name = name;
            this.price = price;
            this.uom = uom;
            this.category = category;
            this.itemCode = itemCode;
        }
    }

    static final class CsvReader implements AutoCloseable {
        private final PushbackReader reader;
        private boolean firstCharacter = true;
        private boolean eof;

        CsvReader(InputStream inputStream) {
            reader = new PushbackReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8), 1);
        }

        List<String> readRow() throws IOException {
            if (eof) {
                return null;
            }
            List<String> row = new ArrayList<>();
            StringBuilder cell = new StringBuilder();
            boolean inQuotes = false;
            boolean atCellStart = true;
            int raw;

            while ((raw = reader.read()) != -1) {
                char ch = (char) raw;
                if (firstCharacter && ch == '\uFEFF') {
                    continue;
                }
                firstCharacter = false;
                if (inQuotes) {
                    if (ch == '"') {
                        int next = reader.read();
                        if (next == '"') {
                            cell.append('"');
                        } else {
                            inQuotes = false;
                            if (next != -1) {
                                reader.unread(next);
                            }
                        }
                    } else {
                        cell.append(ch);
                    }
                    atCellStart = false;
                    continue;
                }

                if (ch == '"' && atCellStart) {
                    inQuotes = true;
                    atCellStart = false;
                } else if (ch == ',') {
                    row.add(cell.toString());
                    cell.setLength(0);
                    atCellStart = true;
                } else if (ch == '\n') {
                    row.add(cell.toString());
                    return row;
                } else if (ch != '\r') {
                    cell.append(ch);
                    atCellStart = false;
                }
            }

            eof = true;
            if (cell.length() > 0 || !row.isEmpty()) {
                row.add(cell.toString());
                return row;
            }
            return null;
        }

        @Override
        public void close() throws IOException {
            reader.close();
        }
    }

    static Map<String, Integer> headerMap(List<String> headerRow) {
        Map<String, Integer> map = new HashMap<>();
        for (int i = 0; i < headerRow.size(); i++) {
            map.put(normalizeHeader(headerRow.get(i)), i);
        }
        return map;
    }

    static int firstPresent(Map<String, Integer> headers, String... names) {
        for (String name : names) {
            Integer index = headers.get(normalizeHeader(name));
            if (index != null) {
                return index;
            }
        }
        return -1;
    }

    private static String normalizeHeader(String header) {
        return Product.clean(header).toLowerCase(Locale.US).replace('-', ' ').replace('_', ' ');
    }

    static String valueAt(List<String> row, int index) {
        return index >= 0 && index < row.size() ? row.get(index) : "";
    }

    static Long parsePriceCents(String value) {
        String cleaned = Product.clean(value).replace(",", "");
        if (cleaned.isEmpty()) {
            return null;
        }
        cleaned = cleaned.replaceAll("[^0-9.\\-]", "");
        if (cleaned.isEmpty() || "-".equals(cleaned)) {
            return null;
        }
        try {
            return new BigDecimal(cleaned).movePointRight(2).setScale(0, RoundingMode.HALF_UP).longValueExact();
        } catch (ArithmeticException | NumberFormatException ex) {
            return null;
        }
    }
}

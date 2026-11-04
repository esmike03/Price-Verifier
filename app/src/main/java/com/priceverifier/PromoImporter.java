package com.priceverifier;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Reads the server's promos.csv. Like the catalog, columns are matched by header
 * name: barcode, label, type, value, buy, take, start, end. Rows that cannot be
 * understood are skipped rather than failing the whole file.
 */
final class PromoImporter {
    List<Promo> parse(InputStream inputStream) throws Exception {
        List<Promo> promos = new ArrayList<>();
        try (ProductImporter.CsvReader reader = new ProductImporter.CsvReader(inputStream)) {
            List<String> header = reader.readRow();
            if (header == null) {
                return promos;
            }
            Map<String, Integer> headers = ProductImporter.headerMap(header);
            int barcode = ProductImporter.firstPresent(headers, "barcode", "bar code", "upc", "ean", "code");
            int label = ProductImporter.firstPresent(headers, "label", "kind");
            int type = ProductImporter.firstPresent(headers, "type", "promo type");
            int value = ProductImporter.firstPresent(headers, "value", "amount", "percent", "price");
            int buy = ProductImporter.firstPresent(headers, "buy", "buy qty");
            int take = ProductImporter.firstPresent(headers, "take", "take qty", "get");
            int start = ProductImporter.firstPresent(headers, "start", "start date", "from");
            int end = ProductImporter.firstPresent(headers, "end", "end date", "until", "to");
            if (barcode < 0 || type < 0) {
                throw new IllegalArgumentException("promos.csv must include barcode and type columns.");
            }
            List<String> row;
            while ((row = reader.readRow()) != null) {
                Promo promo = fromRow(
                        ProductImporter.valueAt(row, barcode), ProductImporter.valueAt(row, label),
                        ProductImporter.valueAt(row, type), ProductImporter.valueAt(row, value),
                        ProductImporter.valueAt(row, buy), ProductImporter.valueAt(row, take),
                        ProductImporter.valueAt(row, start), ProductImporter.valueAt(row, end),
                        Promo.SOURCE_SERVER);
                if (promo != null) {
                    promos.add(promo);
                }
            }
        }
        return promos;
    }

    /** Builds a promo from text fields (CSV cells or the device form); null when invalid. */
    static Promo fromRow(String barcode, String label, String type, String value,
                         String buy, String take, String start, String end, String source) {
        String code = Product.normalizeBarcode(barcode);
        Promo.Type promoType = Promo.parseType(type);
        String startDate = Promo.parseDate(start);
        String endDate = Promo.parseDate(end);
        if (code.isEmpty() || promoType == null || startDate == null || endDate == null
                || (!startDate.isEmpty() && !endDate.isEmpty() && endDate.compareTo(startDate) < 0)) {
            return null;
        }
        long amount = 0;
        int buyQty = 0;
        int takeQty = 0;
        if (promoType == Promo.Type.PERCENT) {
            Long percent = Promo.parsePercent(value);
            if (percent == null) {
                return null;
            }
            amount = percent;
        } else if (promoType == Promo.Type.BUY_TAKE) {
            buyQty = parseQty(buy);
            takeQty = parseQty(take);
            if (buyQty <= 0 || takeQty <= 0) {
                return null;
            }
        } else {
            Long cents = ProductImporter.parsePriceCents(value);
            if (cents == null || cents < 0 || (promoType == Promo.Type.AMOUNT && cents == 0)) {
                return null;
            }
            amount = cents;
        }
        return new Promo(0, code, source, Promo.parseLabel(label), promoType, amount,
                buyQty, takeQty, startDate, endDate);
    }

    private static int parseQty(String text) {
        try {
            int qty = Integer.parseInt(Product.clean(text));
            return qty > 0 && qty <= 999 ? qty : 0;
        } catch (NumberFormatException ex) {
            return 0;
        }
    }
}

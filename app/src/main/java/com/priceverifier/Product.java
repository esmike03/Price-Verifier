package com.priceverifier;

final class Product {
    final String barcode;
    final String name;
    final long priceCents;
    final String uom;
    final String category;
    final String itemCode;
    final long updatedAt;

    Product(String barcode, String name, long priceCents,
            String uom, String category, String itemCode, long updatedAt) {
        this.barcode = normalizeBarcode(barcode);
        this.name = clean(name);
        this.priceCents = priceCents;
        this.uom = clean(uom);
        this.category = clean(category);
        this.itemCode = clean(itemCode);
        this.updatedAt = updatedAt;
    }

    static String normalizeBarcode(String value) {
        String cleaned = clean(value);
        if (cleaned.endsWith(".0")) {
            cleaned = cleaned.substring(0, cleaned.length() - 2);
        }
        // Drop whitespace and any CR/LF/tab terminator a wedge scanner appends.
        // The barcode itself is kept exactly as scanned/imported (no digits added
        // or removed) so the value shown on screen matches the product label.
        return cleaned.replaceAll("\\s+", "");
    }

    /**
     * Equivalent encodings of the same product so a lookup matches whether the
     * scanner transmits a UPC-A as 12 digits, as its zero-prefixed EAN-13 form,
     * or as the compact UPC-E form. Every variant retains its check digit. The
     * original code is always returned first.
     *
     * Last comes the code filled with zeros in front to 13 digits: some store
     * systems keep every barcode at that length, so an EAN-8 such as 69025310
     * (or the store's own short code) is in the catalog as 0000069025310.
     */
    static java.util.List<String> lookupVariants(String code) {
        java.util.LinkedHashSet<String> variants = new java.util.LinkedHashSet<>();
        variants.add(code);
        if (code.matches("\\d{12}")) {
            variants.add("0" + code);
            String compact = compressUpcA(code);
            if (compact != null) {
                variants.add(compact);
            }
        } else if (code.matches("\\d{13}") && code.charAt(0) == '0') {
            String upcA = code.substring(1);
            variants.add(upcA);
            String compact = compressUpcA(upcA);
            if (compact != null) {
                variants.add(compact);
            }
        } else if (code.matches("\\d{8}")) {
            String upcA = expandUpcE(code);
            if (upcA != null) {
                variants.add(upcA);
                variants.add("0" + upcA);
            }
        }
        if (code.matches("\\d{1,12}")) {
            StringBuilder padded = new StringBuilder(code);
            while (padded.length() < 13) {
                padded.insert(0, '0');
            }
            variants.add(padded.toString());
        }
        return new java.util.ArrayList<>(variants);
    }

    private static String expandUpcE(String code) {
        char numberSystem = code.charAt(0);
        if (numberSystem != '0' && numberSystem != '1') {
            return null;
        }
        String body = code.substring(1, 7);
        char mode = body.charAt(5);
        String manufacturer;
        String item;
        if (mode == '0' || mode == '1' || mode == '2') {
            manufacturer = body.substring(0, 2) + mode + "00";
            item = "00" + body.substring(2, 5);
        } else if (mode == '3') {
            manufacturer = body.substring(0, 3) + "00";
            item = "000" + body.substring(3, 5);
        } else if (mode == '4') {
            manufacturer = body.substring(0, 4) + "0";
            item = "0000" + body.charAt(4);
        } else {
            manufacturer = body.substring(0, 5);
            item = "0000" + mode;
        }
        return numberSystem + manufacturer + item + code.charAt(7);
    }

    private static String compressUpcA(String code) {
        char numberSystem = code.charAt(0);
        if ((numberSystem != '0' && numberSystem != '1') || !code.matches("\\d{12}")) {
            return null;
        }
        String manufacturer = code.substring(1, 6);
        String item = code.substring(6, 11);
        String body;
        if ((manufacturer.endsWith("000") || manufacturer.endsWith("100") || manufacturer.endsWith("200"))
                && item.startsWith("00")) {
            body = manufacturer.substring(0, 2) + item.substring(2) + manufacturer.charAt(2);
        } else if (manufacturer.endsWith("00") && item.startsWith("000")) {
            body = manufacturer.substring(0, 3) + item.substring(3) + "3";
        } else if (manufacturer.endsWith("0") && item.startsWith("0000")) {
            body = manufacturer.substring(0, 4) + item.charAt(4) + "4";
        } else if (item.startsWith("0000") && item.charAt(4) >= '5') {
            body = manufacturer + item.charAt(4);
        } else {
            return null;
        }
        return numberSystem + body + code.charAt(11);
    }

    static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}

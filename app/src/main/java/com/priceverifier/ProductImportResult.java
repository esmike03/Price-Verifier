package com.priceverifier;

final class ProductImportResult {
    final int totalRows;
    final int validRows;
    final int inserted;
    final int updated;
    final int skipped;
    final String sourceName;
    final String message;

    ProductImportResult(int totalRows, int validRows, int inserted, int updated, int skipped,
                        String sourceName, String message) {
        this.totalRows = totalRows;
        this.validRows = validRows;
        this.inserted = inserted;
        this.updated = updated;
        this.skipped = skipped;
        this.sourceName = sourceName;
        this.message = message;
    }
}

# Price Verifier

Production-ready Android Studio project for a barcode-based price verifier terminal.

## Features

- Customer-facing scan screen optimized for built-in hardware barcode scanners.
- Offline SQLite product database.
- Admin PIN gate with TXT and CSV import.
- Offline manual add or update form for individual products.
- Paged admin product list with search, edit, and delete controls.
- Transactional upsert import with inserted, updated, and skipped row counts.
- Streamed LAN catalog imports and single-flight auto-sync for large catalogs.
- Large product name and price display for aisle/customer terminals.
- Four price check layouts (Centered, Left aligned, Split, Price first) and five promo styles (Classic banner, Sticker, Corner ribbon, Poster block, Price tag), chosen in Admin > Price Check Display or for every scanner in the portal's Design tab.
- Optional server-side data source: the portal's Data source tab reads the catalog straight from the store's POS database (MySQL/MariaDB, SQL Server including SAP Business One, PostgreSQL, SAP HANA, Oracle, Firebird, IBM Db2, Access, SQLite or any ODBC database), a POS export file, or a cloud POS web address, on a schedule, with a safety stop and a live preview. Scanners need no change: they sync catalog.csv as before.
- Supports scanner keyboard-wedge input and configurable scanner intent output.

## Scanner configuration

Most price verifier devices expose the built-in scanner as a keyboard wedge. In that mode no setup is required: scan on the customer screen and the app reads the barcode followed by Enter.

For devices that emit intents, configure the scanner app to deliver either:

- Action: `com.priceverifier.SCAN`
- Extra: `barcode`, `data`, `SCAN_BARCODE1`, or `com.symbol.datawedge.data_string`

The activity also accepts these extras when launched via a scan intent.

## Import format

TXT and CSV files should contain a header row. TXT files use the same comma-delimited format as CSV. Supported column aliases:

```text
barcode,name,price,uom,category,item_code
```

- Barcode: `barcode`, `bar code`, `upc`, `ean`, `code`. EAN-13, UPC-A, UPC-E, and EAN-8 are supported. The app preserves check digits and reconciles UPC-A with scanners that transmit its zero-prefixed EAN-13 or compact UPC-E equivalent.
- Product name: `name`, `product`, `description`, `item`, `item name`
- Price: `price`, `amount`, `retail`, `srp`, `selling price`
- Optional nullable fields: `uom`, `unit`, `unit of measure`, `category`, `department`, `item code`, `item_code`, `itemcode`, `sku`, `item sku`

Only `barcode`, `name`, and `price` are required. The optional columns can be omitted from the header or left blank in a row. Columns are matched by header name, so existing files with a different column order still import.

Rows without a barcode, name, or valid non-negative price are skipped.

Ready-to-import sample files are included at `sample-data/products-sample.txt` and `sample-data/products-sample-template.csv`.

## Default admin PIN

The default admin PIN is `1234`. Change it on the admin screen before deploying.

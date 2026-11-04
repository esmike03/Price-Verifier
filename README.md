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

## Build and run the Android app

1. Open this repository's root folder in Android Studio and let Gradle sync.
2. Install Android SDK Platform 36 when prompted. The app supports Android 6.0
   (API 23) and newer; the compile and target SDK are configured in
   [`app/build.gradle`](app/build.gradle).
3. Register the deployment device's hardware serial in
   [`DeviceAccess.java`](app/src/main/java/com/priceverifier/DeviceAccess.java)
   before building. An unregistered device displays its serial on the
   authorization screen.
4. Connect the Android device and use Android Studio's Run action, or build a
   debug APK from the repository root:

   ```powershell
   .\gradlew.bat assembleDebug
   ```

   On macOS or Linux:

   ```sh
   bash ./gradlew assembleDebug
   ```

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`.
Android Studio creates the machine-specific SDK configuration in
`local.properties`; that file is excluded from Git.

## Set up the server portal

The Android app can work offline. To distribute catalogs, media, design settings,
and app updates over the network, deploy the portal from
[`server-installer/portal`](server-installer/portal). Its program files are:

```text
index.php
.htaccess
.user.ini
README-client-upload.txt
assets/app.css
assets/app.js
```

Keep the `assets` directory beside `index.php` so the portal loads its styles and
scripts. Follow the [hosted portal instructions](server-installer/portal/README-client-upload.txt)
for cPanel, or the [server installation guide](server-installer/README-install.txt)
for the Windows and Linux installer packages. After setup, enter the server's
address in the Android app under **Admin > Sync > Server URL**.

The portal creates its password, data-source configuration, catalog, and device
state at runtime. These local files are excluded from Git. Create the portal
password on first use and configure any POS connection on the deployed server.

### Build offline server installers

The source checkout excludes bundled third-party runtimes and generated archives.
Before packaging, populate these paths relative to `server-installer`:

```text
vendor/windows/php/              # Complete portable PHP distribution, including php-cgi.exe
vendor/windows/caddy.exe
vendor/windows/vc_redist.x64.exe
vendor/linux/frankenphp
```

The package builder requires both the Windows and Linux runtime files because it
builds both packages in one run. From the repository root, run:

```powershell
.\server-installer\build-package.ps1
```

Packages are written to `server-installer/dist`, using the version in
[`server-installer/VERSION`](server-installer/VERSION). The builder copies only
portal program files, so an existing portal's credentials and catalog are not
included in the installer.

## Documentation and checks

- [User guide (PDF)](docs/Price-Verifier-Guide.pdf)
- [User guide (HTML)](docs/guide/price-verifier-guide.html)
- [Server installation and data-source guide](server-installer/README-install.txt)
- [Installer and scanner-status checks](server-installer/tests/README.txt)

The scanner-status checks can run without a real scanner or installed server:

```powershell
php server-installer/tests/scanner-status.php
node server-installer/tests/scanner-status-ui.cjs
```

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

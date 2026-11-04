PRICE VERIFIER SERVER - INSTALLATION

This package installs the Price Verifier web portal that scanners sync from
(catalog, display media, promos and design). Everything it needs is included;
the PC does not need internet access to install.

The product catalog can be uploaded as CSV, TXT or an Excel workbook (.xlsx or
the older .xls) with the same columns (barcode, name, price, and optionally
uom, category, item_code). An Excel upload is converted to catalog.csv on the
server, which the scanners then sync as usual.

Optional: instead of uploading, the portal's Data source tab can read the
catalog straight from the store's POS database (MySQL/MariaDB, SQL Server,
PostgreSQL, SAP HANA, Oracle, Firebird, IBM Db2, Access, SQLite, or any
database with an ODBC driver), from a file the POS exports to a folder, or
from a web address (cloud POS). It syncs on a schedule, keeps the current
catalog when the source has no products or far fewer than before, and tells
the scanners to update when prices change. See DATA SOURCE below.

- Starts automatically when the PC/server boots, before anyone logs in, and
  restarts itself if it stops.
- Only devices on the same local network can reach it. Requests from the
  internet or other networks are refused, even if the port is forwarded.
- Running the installer again upgrades the program and keeps all data.


WINDOWS 10/11 OR WINDOWS SERVER (64-bit)
  1. Extract the zip to any folder.
  2. Double-click install.cmd and allow administrator access.
  3. Answer the questions:
       - Port (default 8000)
       - Optional: a folder to import existing portal data from
         (for example C:\price verifier server)
  4. The installer prints the address to enter on the scanners, e.g.
       http://192.168.0.130:8000

  Installed to:  C:\PriceVerifier
    portal\    web portal and its data (catalog, media, promos, design)
    logs\      supervisor.log (start-up, and why any part stopped - look here
               first), caddy-console.log, access.log, php-errors.log,
               php-worker-<port>.log, catalog-source.log and
               catalog-source-error.log (the last data source sync)
  Autostart:     Task Scheduler > "Price Verifier Server" (runs as SYSTEM)
  If the installer's final check fails, it prints what the server recorded.
  Firewall:      Windows Defender Firewall > "Price Verifier Server" (local subnet only)
  Uninstall:     C:\PriceVerifier\uninstall.cmd (asks whether to keep the data)

  Unattended:
    powershell -ExecutionPolicy Bypass -File install.ps1 -Port 8000 -ImportFrom "" -Quiet


LINUX (64-bit x86_64 with systemd: Ubuntu, Debian, RHEL, ...)
  1. Extract:   tar -xzf PriceVerifierServer-Linux-*.tar.gz
  2. Install:   cd PriceVerifierServer-Linux-*  &&  sudo bash install.sh
  3. Answer the questions (port, optional import folder).

  Installed to:  /opt/price-verifier   (service user: priceverifier)
  Autostart:     systemctl status price-verifier
  Logs:          journalctl -u price-verifier   and  /opt/price-verifier/logs
  Data source:   systemctl status price-verifier-sync.timer
                 (journalctl -u price-verifier-sync for the syncs)
  Firewall:      ufw or firewalld rules for the local subnet, when one is active
  Uninstall:     sudo /opt/price-verifier/uninstall.sh

  Unattended:
    sudo PORT=8000 IMPORT_FROM= QUIET=1 bash install.sh


DATA SOURCE (optional: the catalog from the POS database)
  Set it up in the portal: Data source tab. Steps: choose the kind of
  database, enter the connection, Test connection, choose the table (or type
  a query), Load columns, check the matched columns, Preview products, then
  tick "Turn on" and Save. The server checks every 30 seconds whether a sync
  is due; the tab shows the last syncs and their results.

  - Use a database user that can only read (examples are on the tab). Only a
    single SELECT is accepted, and the password stays on this server.
  - SQL Server works with the driver built into Windows (SQL Server 2005 and
    newer); ODBC Driver 17/18 for SQL Server is used when installed. For a
    named instance (POS-PC\SQLEXPRESS) leave the port blank and keep the SQL
    Server Browser service running.
  - SAP HANA, Oracle, Firebird, IBM Db2, Access and other ODBC databases need
    that database's 64-bit ODBC driver installed on the Windows server. Run
    the installer again after installing a Firebird or Oracle client so PHP
    can also connect to them directly.
  - The Linux server can read MySQL/MariaDB, PostgreSQL, SQLite, export files
    and web addresses; for the other databases use the Windows server.
  - The Windows server runs as the SYSTEM account: use a System DSN (not a
    User DSN), and a shared folder must allow this PC's account to read it.

SCANNERS
  Authorized scanner serials are built into the Android app
  (DeviceAccess.ALLOWED_SERIALS); the portal does not register devices.
  On each scanner: Admin > Sync > Server URL = the address printed above.

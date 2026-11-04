PRICE VERIFIER CPANEL UPLOAD PORTAL

1. In cPanel File Manager, open public_html.
2. Create a folder named price-verifier.
3. Upload index.php, .htaccess, and .user.ini into that folder, plus the assets
   folder containing app.css and app.js. Keep assets beside index.php.
4. Upload any initial catalog and media files into the same folder.
5. Open https://yourdomain.com/price-verifier/ in a browser.
6. Create the client upload password on the first visit.
7. Use https://yourdomain.com/price-verifier/ as the Android app server URL.

The portal accepts:
- catalog.csv or catalog.txt, or an Excel workbook (.xlsx or the older .xls)
  with the same columns; a workbook is converted to catalog.csv on upload
  (.xlsx needs the PHP zip and xmlreader extensions; .xls needs nothing extra
  and holds at most 65,536 rows)
- price-verifier.apk
- logo.png or logo.jpg
- banner.jpg or banner.png
- video.mp4
- scheduled display media: JPG, PNG, or MP4
- the scrolling message: typed in the portal, or uploaded as marquee.txt
  (saving it empty hides the scrolling bar on the scanners)

Scheduled images and videos can be uploaded with any filename. In the portal,
an upload that is not already named with an end date is renamed automatically
to play for one month from today (MMDDYY.ext, or 001_MMDDYY.ext when that name
is taken). Rename a file to MMDDYY.ext or MMDDYY_SECONDS.ext to change its end
date or the seconds per turn.

App updates:
- Upload the new APK as price-verifier.apk in the portal.
- Enter the app version and changes in App update details.
- Devices check the server URL for a newer APK and show "New version available"
  inside the app with the version and changes.
- Tapping Download downloads the APK and opens Android's installer on the device.

Registered devices:
- Authorized scanner serials are hard-coded in the Android app
  (DeviceAccess.ALLOWED_SERIALS). Add a serial there and rebuild the app;
  the portal no longer manages device registration.

Image uploads are automatically resized/recompressed by the portal when PHP GD
is available. MP4 uploads are compressed only when the cPanel account has
ffmpeg and command execution enabled; otherwise the original valid MP4 is saved.

Use this catalog header:
barcode,name,uom,category,price,item_code

The portal also includes an Add or Update Item form. Entering an existing
barcode updates that row in the hosted catalog. A new barcode adds a row.
The catalog list can be searched and edited from the browser. Upload the APK
to show the Download Android app button in the portal header.

Scheduled display media file names:
- MMDDYY.jpg, MMDDYY.png, or MMDDYY.mp4
- MMDDYY_SECONDS.jpg, MMDDYY_SECONDS.png, or MMDDYY_SECONDS.mp4
- 3CODE_MMDDYY_SECONDS.jpg, 3CODE_MMDDYY_SECONDS.png, or 3CODE_MMDDYY_SECONDS.mp4

Example: 000_010126_20.mp4 stays active through January 1, 2026 and displays
for 20 seconds each time its turn comes up. The 3-character code is optional.
Without _SECONDS, images use the default image time and videos play once
through their full length. The Android app rotates the default banner/video
together with all active scheduled media.
The banner.jpg/png and video.mp4 uploads are the default idle media; do not
upload default.* files in Scheduled display media.

Data source tab (optional): the portal can read the catalog from the store's
POS database or a POS export instead of uploads. On cPanel or other hosting,
add a cron job that runs every minute so it can sync on schedule:
  php /home/ACCOUNT/public_html/price-verifier/index.php --catalog-source-sync
The settings, with the database password, are kept in catalog-source.php;
.htaccess blocks it, and it shows nothing if opened. A hosted portal can only
reach databases that accept connections from the hosting server.

The portal creates upload-config.php automatically after password setup.
Keep that file private. Do not send the main cPanel password to the client.

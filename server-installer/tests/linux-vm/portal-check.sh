#!/usr/bin/env bash
# Exercises an installed Price Verifier server the way staff and scanners do,
# and prints one line per check. The same script is run against the Windows
# install and the Linux install; the two outputs are then compared.
#
#   portal-check.sh <portal base url> <pos-sync base url> <pos token> <folder with test files>
set -u
cd "$4" || exit 1
U="${1%/}/index.php"; ROOT="${1%/}"; POS="${2%/}"; TOKEN="$3"; FILES="$4"
J="$(mktemp)"; trap 'rm -f "$J"' EXIT
PW="check-$(date +%s)-pw"

code() { curl -s -o /dev/null -w '%{http_code}' "$@"; }
csrf() { curl -s -b "$J" -c "$J" "$U" | grep -o 'name="csrf" value="[^"]*"' | head -1 | sed 's/.*value="//;s/"//'; }
post() { curl -s -o /dev/null -w '%{http_code}' -b "$J" -c "$J" "$@" "$U"; }
flash() { curl -s -b "$J" -c "$J" "$U" | grep -o 'class="flash[^>]*>[^<]*' | head -1 | sed 's/.*>//' | sed 's/^ *//;s/ *$//'; }
line() { printf '%-34s %s\n' "$1" "$2"; }

line "portal page"            "$(code "$U")"
line "missing file"           "$(code "$ROOT/banner-does-not-exist.jpg")"
line "protected state file"   "$(code "$ROOT/device-sync.php") body=$(curl -s "$ROOT/device-sync.php" | wc -c)"
line "protected config file"  "$(code "$ROOT/upload-config.php") body=$(curl -s "$ROOT/upload-config.php" | wc -c)"

T="$(csrf)"; line "create password" "$(post -d "csrf=$T&action=setup&password=$PW&confirm=$PW")"
T="$(csrf)"; line "sign out"        "$(post -d "csrf=$T&action=logout")"
T="$(csrf)"; line "wrong password"  "$(post -d "csrf=$T&action=login&password=wrong-password-x") $(flash | cut -c1-40)"
T="$(csrf)"; line "sign in"         "$(post -d "csrf=$T&action=login&password=$PW")"

# Catalog: CSV (replace), Excel .xlsx and .xls, then add-and-update.
T="$(csrf)"; line "upload catalog.csv" "$(post -F "csrf=$T" -F action=upload -F slot=catalog -F catalog_mode=replace -F "upload[]=@catalog.csv") | $(flash | cut -c1-70)"
line "scanner gets catalog.csv"  "$(code "$ROOT/catalog.csv") rows=$(curl -s "$ROOT/catalog.csv" | tr -d '\r' | grep -c .) sha=$(curl -s "$ROOT/catalog.csv" | tr -d '\r' | sha256sum | cut -c1-12)"
T="$(csrf)"; line "upload catalog.xlsx" "$(post -F "csrf=$T" -F action=upload -F slot=catalog -F catalog_mode=replace -F "upload[]=@catalog.xlsx") | $(flash | cut -c1-70)"
line "catalog after .xlsx"      "rows=$(curl -s "$ROOT/catalog.csv" | tr -d '\r' | grep -c .) sha=$(curl -s "$ROOT/catalog.csv" | tr -d '\r' | sha256sum | cut -c1-12) zeros=$(curl -s "$ROOT/catalog.csv" | grep -c '^0000069025310,')"
T="$(csrf)"; line "upload catalog.xls"  "$(post -F "csrf=$T" -F action=upload -F slot=catalog -F catalog_mode=replace -F "upload[]=@catalog.xls") | $(flash | cut -c1-70)"
line "catalog after .xls"       "rows=$(curl -s "$ROOT/catalog.csv" | tr -d '\r' | grep -c .) sha=$(curl -s "$ROOT/catalog.csv" | tr -d '\r' | sha256sum | cut -c1-12)"
T="$(csrf)"; line "add and update only" "$(post -F "csrf=$T" -F action=upload -F slot=catalog -F catalog_mode=merge -F "upload[]=@changes.csv") | $(flash | cut -c1-90)"
line "catalog after merge"      "rows=$(curl -s "$ROOT/catalog.csv" | tr -d '\r' | grep -c .) sha=$(curl -s "$ROOT/catalog.csv" | tr -d '\r' | sha256sum | cut -c1-12)"

# Scrolling message typed in the portal, and cleared.
T="$(csrf)"; line "save scrolling message" "$(post --data-urlencode "csrf=$T" -d action=save_marquee --data-urlencode "marquee_text=Welcome to the store") text=$(curl -s "$ROOT/marquee.txt")"
T="$(csrf)"; line "empty scrolling message" "$(post --data-urlencode "csrf=$T" -d action=save_marquee -d marquee_text=) file=$(code "$ROOT/marquee.txt") bytes=$(curl -s "$ROOT/marquee.txt" | wc -c)"

# Banner image, and what scanners ask for.
T="$(csrf)"; line "upload banner"       "$(post -F "csrf=$T" -F action=upload -F slot=banner -F "upload[]=@banner.png") | $(flash | cut -c1-50)"
line "scanner gets banner.png"  "$(code "$ROOT/banner.png") type=$(curl -s -o /dev/null -w '%{content_type}' "$ROOT/banner.png")"
line "media manifest"           "$(code "$U?media_manifest=1") first=$(curl -s "$U?media_manifest=1" | head -1 | tr -d '\r')"
line "app update info"          "$(code "$U?app_update=1") $(curl -s "$U?app_update=1" | cut -c1-40)"

# Scanners tab: check-ins, the five requests, offline after the check-ins stop.
line "old app check-in"         "$(curl -s "$U?device_poll=1&serial=OLDAPP001&app=v8")"
line "new app check-in"         "$(curl -s "$U?device_poll=1&serial=NEWAPP001&app=v8&every=2")"
for scope in items images videos app all; do
    T="$(csrf)"; r="$(post -d "csrf=$T&action=request_sync&scope=$scope")"
    old="$(curl -s "$U?device_poll=1&serial=OLDAPP001&app=v8" | sed 's/"id":[0-9]*/"id":N/;s/"age":[0-9]*/"age":N/')"
    new="$(curl -s "$U?device_poll=1&serial=NEWAPP001&app=v8&every=2" | sed 's/"id":[0-9]*/"id":N/;s/"age":[0-9]*/"age":N/')"
    line "request $scope" "$r old-app:$old new-app:$new"
done
ID="$(curl -s "$U?device_poll=1&serial=NEWAPP001&app=v8&every=2" | grep -o '"id":[0-9]*' | cut -d: -f2)"
curl -s -o /dev/null "$U?device_poll=1&serial=NEWAPP001&app=v8&every=2&done=$ID&ok=1&message=POS:%201200%20products"
status() { curl -s -b "$J" "$U?scanner_status=1" | sed 's/<[^>]*>/ /g' | tr -s ' \n\r\t' ' '; }
line "scanner list"             "$(status | grep -o 'NEWAPP001.*' | sed 's/seen [a-z0-9 ]*ago/seen X/g;s/[0-9]* sec ago/X ago/g;s/[0-9.]* v8/IP v8/g' | cut -c1-200)"
sleep 8
line "8 s after last check-in"  "new app: $(status | grep -o 'NEWAPP001 [0-9.]* v8 [A-Za-z]*' | awk '{print $4}'), old app: $(status | grep -o 'OLDAPP001 [0-9.]* v8 [A-Za-z]*' | awk '{print $4}')"
line "status needs sign-in"     "$(code "$U?scanner_status=1")"

# pos-sync
line "pos-sync without token"   "$(code "$POS/health")"
line "pos-sync wrong token"     "$(code -H 'X-Price-Verifier-Token: 00000000' "$POS/health")"
line "pos-sync with token"      "$(code --max-time 30 -H "X-Price-Verifier-Token: $TOKEN" "$POS/health")"
line "pos-sync catalog"         "$(code --max-time 30 -H "X-Price-Verifier-Token: $TOKEN" "$POS/catalog.csv")"

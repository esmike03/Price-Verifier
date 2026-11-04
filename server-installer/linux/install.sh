#!/usr/bin/env bash
# Price Verifier server installer for Linux (systemd, x86_64).
#
#   sudo bash install.sh
#
# Options (environment variables):
#   PORT=8000                 port for scanners and the web portal
#   IMPORT_FROM=/path/portal  copy data from an existing portal folder ('' = start empty)
#   INSTALL_DIR=/opt/price-verifier
#   QUIET=1                   accept defaults instead of asking
#
# Starts at boot (systemd, restarts if it stops) and is reachable only from the
# local network. Re-running it upgrades the program files and keeps portal data.
set -euo pipefail
shopt -u patsub_replacement 2>/dev/null || true  # bash 5.2+: keep '&' literal in ${var//x/y}

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PAYLOAD="$HERE/files"
VERSION="$(tr -d '[:space:]' < "$HERE/VERSION")"
INSTALL_DIR="${INSTALL_DIR:-/opt/price-verifier}"
SERVICE=price-verifier
SVC_USER=priceverifier
QUIET="${QUIET:-0}"
# Program files replaced on every install; everything else in portal/ is data.
CODE_FILES=(index.php .htaccess .user.ini README-client-upload.txt assets)

say()  { printf '%s\n' "$*"; }
step() { printf '\n\033[36m==> %s\033[0m\n' "$*"; }
warn() { printf '\033[33m%s\033[0m\n' "$*"; }
die()  { printf '\033[31mError: %s\033[0m\n' "$*" >&2; exit 1; }
ask() {
    local question="$1" default="$2" answer
    if [ "$QUIET" = 1 ] || [ ! -r /dev/tty ]; then printf '%s' "$default"; return; fi
    read -r -p "$question${default:+ [$default]}: " answer </dev/tty || true
    printf '%s' "${answer:-$default}"
}
port_in_use() { ss -ltnH "sport = :$1" 2>/dev/null | grep -q .; }
# The HTTP status this machine answers with on a port, or 000 when nothing
# answers. Uses curl when it is installed and plain bash otherwise: Ubuntu
# Desktop, for one, does not come with curl.
http_status() { # port path [header]
    local port="$1" path="$2" header="${3:-}" status=""
    if command -v curl >/dev/null 2>&1; then
        status="$(curl -s -o /dev/null -w '%{http_code}' --max-time 20 ${header:+-H "$header"} "http://127.0.0.1:$port$path" 2>/dev/null || true)"
    else
        status="$( (
            exec 3<>"/dev/tcp/127.0.0.1/$port" || exit 0
            printf 'GET %s HTTP/1.0\r\nHost: 127.0.0.1:%s\r\n%s\r\n' "$path" "$port" "${header:+$header$'\r\n'}" >&3
            IFS=' ' read -r -t 20 _ code _ <&3 || true
            printf '%s' "${code:-}"
        ) 2>/dev/null || true)"
    fi
    [[ "$status" =~ ^[0-9]{3}$ ]] || status=000
    printf '%s' "$status"
}
local_networks() { ip -o -f inet route show scope link 2>/dev/null | awk '/proto kernel/ {print $1}' | sort -u; }
is_code_file() { local name="$1" f; for f in "${CODE_FILES[@]}"; do [ "$name" = "$f" ] && return 0; done; return 1; }

# Removes firewall rules added by a previous install (ufw comment / firewalld rich rules).
remove_firewall_rules() {
    local old_port="$1" net n
    if command -v ufw >/dev/null 2>&1; then
        while n="$(ufw status numbered 2>/dev/null | grep 'Price Verifier' | head -n1 | sed -E 's/^\[ *([0-9]+)\].*/\1/')" && [ -n "$n" ]; do
            ufw --force delete "$n" >/dev/null
        done
    fi
    if command -v firewall-cmd >/dev/null 2>&1 && firewall-cmd --state >/dev/null 2>&1 && [ -n "$old_port" ]; then
        for net in $(firewall-cmd --permanent --list-rich-rules | grep "port=\"$old_port\"" | sed -E 's/.*source address="([^"]+)".*/\1/'); do
            firewall-cmd --permanent --remove-rich-rule="rule family=\"ipv4\" source address=\"$net\" port port=\"$old_port\" protocol=\"tcp\" accept" >/dev/null || true
        done
        firewall-cmd --reload >/dev/null || true
    fi
}

# Allows the port from the local subnets only, when ufw or firewalld is active.
allow_local_port() {
    local port="$1" net
    if command -v ufw >/dev/null 2>&1 && ufw status 2>/dev/null | grep -q 'Status: active'; then
        for net in $(local_networks); do ufw allow from "$net" to any port "$port" proto tcp comment 'Price Verifier' >/dev/null; done
        say "ufw: port $port allowed from $(local_networks | tr '\n' ' ')"
    elif command -v firewall-cmd >/dev/null 2>&1 && firewall-cmd --state >/dev/null 2>&1; then
        for net in $(local_networks); do
            firewall-cmd --permanent --add-rich-rule="rule family=\"ipv4\" source address=\"$net\" port port=\"$port\" protocol=\"tcp\" accept" >/dev/null
        done
        firewall-cmd --reload >/dev/null
        say "firewalld: port $port allowed from $(local_networks | tr '\n' ' ')"
    else
        say "No active ufw/firewalld found. The server itself still refuses non-local addresses."
    fi
}

fill_template() { # source target KEY=value...
    local src="$1" dst="$2" text; shift 2
    text="$(cat "$src")"
    for pair in "$@"; do text="${text//\{\{${pair%%=*}\}\}/${pair#*=}}"; done
    printf '%s\n' "$text" > "$dst"
}

# --- Preconditions --------------------------------------------------------------
[ "$(id -u)" -eq 0 ] || die "Run as root: sudo bash install.sh"
command -v systemctl >/dev/null 2>&1 || die "systemd is required."
[ "$(uname -m)" = x86_64 ] || die "This package is for 64-bit Intel/AMD (x86_64) Linux."

say "Price Verifier server $VERSION"
CONFIG_FILE="$INSTALL_DIR/config/server.env"
OLD_PORT=""
if [ -f "$CONFIG_FILE" ]; then
    OLD_PORT="$(sed -n 's/^PORT=//p' "$CONFIG_FILE")"
    say "Existing installation found in $INSTALL_DIR; it will be upgraded, data is kept."
    step "Stopping the running server"
    systemctl stop "$SERVICE" 2>/dev/null || true
fi

# --- Port -------------------------------------------------------------------------
step "Choosing the port"
PORT="${PORT:-$(ask 'Port for scanners and the web portal' "${OLD_PORT:-8000}")}"
[[ "$PORT" =~ ^[0-9]+$ ]] && [ "$PORT" -ge 1 ] && [ "$PORT" -le 65535 ] || die "Invalid port: $PORT"
while port_in_use "$PORT"; do
    say "Port $PORT is already used by another program."
    [ "$QUIET" = 1 ] && die "Port $PORT is in use. Re-run with PORT=<free port>."
    PORT="$(ask 'Choose another port' "$((PORT + 1))")"
done
say "Using port $PORT."

# --- Program files ----------------------------------------------------------------
step "Installing program files to $INSTALL_DIR"
id -u "$SVC_USER" >/dev/null 2>&1 || useradd --system --home-dir "$INSTALL_DIR" --no-create-home --shell /usr/sbin/nologin "$SVC_USER"
mkdir -p "$INSTALL_DIR"/{bin,config,logs,sessions,data,portal}
install -m 0755 "$PAYLOAD/bin/frankenphp" "$INSTALL_DIR/bin/frankenphp"
install -m 0755 "$HERE/uninstall.sh" "$INSTALL_DIR/uninstall.sh"
cp "$HERE/VERSION" "$INSTALL_DIR/VERSION"
"$INSTALL_DIR/bin/frankenphp" version >/dev/null 2>&1 || die "The bundled FrankenPHP does not run on this system."

# --- Portal data --------------------------------------------------------------------
PORTAL="$INSTALL_DIR/portal"
has_data=0
for entry in "$PORTAL"/* "$PORTAL"/.[!.]*; do
    [ -e "$entry" ] || continue
    is_code_file "$(basename "$entry")" || { has_data=1; break; }
done
if [ "$has_data" = 0 ]; then
    step "Portal data"
    if [ "${IMPORT_FROM-__ask__}" = "__ask__" ]; then
        IMPORT_FROM="$(ask 'Import data from an existing portal folder? Enter its path, or leave blank to start empty' '')"
    fi
    if [ -n "$IMPORT_FROM" ]; then
        [ -f "$IMPORT_FROM/index.php" ] || die "No portal found in '$IMPORT_FROM'."
        say "Copying catalog, media, promos, design and the portal password from $IMPORT_FROM ..."
        for entry in "$IMPORT_FROM"/* "$IMPORT_FROM"/.[!.]*; do
            [ -e "$entry" ] || continue
            name="$(basename "$entry")"
            is_code_file "$name" && continue
            case "$name" in device-serials.txt|assets.zip|*.tmp|.catalog-*|.promos-*|.design-*) continue ;; esac
            cp -a "$entry" "$PORTAL/"
        done
    else
        say "Starting with an empty portal. Open it in a browser to create the portal password."
    fi
fi
for item in "${CODE_FILES[@]}"; do
    rm -rf "${PORTAL:?}/$item"
    cp -a "$PAYLOAD/portal/$item" "$PORTAL/$item"
done
chown -R "$SVC_USER:$SVC_USER" "$PORTAL" "$INSTALL_DIR/logs" "$INSTALL_DIR/sessions" "$INSTALL_DIR/data"

# --- Configuration --------------------------------------------------------------------
step "Writing configuration"
TIMEZONE="$(timedatectl show -p Timezone --value 2>/dev/null || true)"
[ -n "$TIMEZONE" ] || TIMEZONE="$(cat /etc/timezone 2>/dev/null || echo Asia/Manila)"
CAPS=""
[ "$PORT" -lt 1024 ] && CAPS="AmbientCapabilities=CAP_NET_BIND_SERVICE"
fill_template "$PAYLOAD/Caddyfile.template" "$INSTALL_DIR/config/Caddyfile" \
    "PORT=$PORT" "PORTAL=$PORTAL" "LOGS=$INSTALL_DIR/logs"
fill_template "$PAYLOAD/php.ini.template" "$INSTALL_DIR/config/price-verifier.ini" \
    "LOGS=$INSTALL_DIR/logs" "SESSIONS=$INSTALL_DIR/sessions" "TIMEZONE=$TIMEZONE"
fill_template "$PAYLOAD/price-verifier.service.template" "/etc/systemd/system/$SERVICE.service" \
    "DIR=$INSTALL_DIR" "CAPS=$CAPS"
# Data source tab: checks every 30 seconds whether a sync with the POS database is due.
fill_template "$PAYLOAD/price-verifier-sync.service.template" "/etc/systemd/system/$SERVICE-sync.service" "DIR=$INSTALL_DIR"
fill_template "$PAYLOAD/price-verifier-sync.timer.template" "/etc/systemd/system/$SERVICE-sync.timer"
printf 'VERSION=%s\nPORT=%s\n' "$VERSION" "$PORT" > "$CONFIG_FILE"
"$INSTALL_DIR/bin/frankenphp" validate --config "$INSTALL_DIR/config/Caddyfile" --adapter caddyfile >/dev/null 2>&1 \
    || die "Web server configuration is invalid: $INSTALL_DIR/config/Caddyfile"
# That check ran as root and creates the access log if there is none yet. The
# server runs as $SVC_USER and cannot start unless it can write to that file.
chown -R "$SVC_USER:$SVC_USER" "$INSTALL_DIR/logs" "$INSTALL_DIR/sessions" "$INSTALL_DIR/data"

# --- Firewall: local network only -------------------------------------------------------
step "Allowing scanners on the local network (firewall)"
remove_firewall_rules "$OLD_PORT"
allow_local_port "$PORT"

# --- Autostart ----------------------------------------------------------------------------
step "Setting up automatic start at boot"
systemctl daemon-reload
systemctl enable "$SERVICE" >/dev/null 2>&1
systemctl restart "$SERVICE"
systemctl enable --now "$SERVICE-sync.timer" >/dev/null 2>&1

# --- Health check ---------------------------------------------------------------------------
step "Checking that the server answers"
ok=0
for _ in $(seq 1 30); do
    sleep 1
    if [ "$(http_status "$PORT" /index.php)" = 200 ]; then ok=1; break; fi
done
if [ "$ok" != 1 ]; then
    # Show what the server recorded, so the cause can be read (or photographed)
    # without knowing where to look.
    warn "--- What the server recorded (journalctl -u $SERVICE) ---"
    journalctl -u "$SERVICE" --no-pager -n 12 -o cat 2>/dev/null | cut -c1-600 || true
    warn "--- end ---"
    die "The server did not answer on port $PORT. Full log: journalctl -u $SERVICE, and $INSTALL_DIR/logs"
fi

printf '\n\033[32mPrice Verifier server is installed and running.\033[0m\n'
say "  Starts automatically at boot (systemd service '$SERVICE')."
say "  Scanners and staff on the local network can use:"
for ip in $(hostname -I 2>/dev/null); do
    case "$ip" in *:*|127.*|169.254.*) continue ;; esac
    say "      http://$ip:$PORT"
done
say "  Enter that address as the Server URL in each scanner's Admin > Sync."
say "  Uninstall: sudo $INSTALL_DIR/uninstall.sh"

#!/usr/bin/env bash
# Removes the Price Verifier server: stops it, removes autostart and firewall
# rules, and deletes the program files. Portal data is kept unless REMOVE_DATA=1.
#
#   sudo /opt/price-verifier/uninstall.sh
set -euo pipefail

INSTALL_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SERVICE=price-verifier
SVC_USER=priceverifier
REMOVE_DATA="${REMOVE_DATA:-ask}"

[ "$(id -u)" -eq 0 ] || { echo "Run as root: sudo $0" >&2; exit 1; }
[ -f "$INSTALL_DIR/config/server.env" ] || { echo "No Price Verifier installation in $INSTALL_DIR." >&2; exit 1; }
PORT="$(sed -n 's/^PORT=//p' "$INSTALL_DIR/config/server.env")"

if [ "$REMOVE_DATA" = ask ]; then
    REMOVE_DATA=0
    if [ -r /dev/tty ]; then
        read -r -p "Also delete the portal data (catalog, media, promos, design, password) in $INSTALL_DIR/portal? [y/N]: " answer </dev/tty || true
        case "$answer" in y|Y|yes|YES) REMOVE_DATA=1 ;; esac
    fi
fi

echo "Stopping the server..."
systemctl disable --now "$SERVICE-sync.timer" 2>/dev/null || true
systemctl disable --now "$SERVICE" 2>/dev/null || true
rm -f "/etc/systemd/system/$SERVICE.service" "/etc/systemd/system/$SERVICE-sync.service" "/etc/systemd/system/$SERVICE-sync.timer"
systemctl daemon-reload

echo "Removing firewall rules..."
if command -v ufw >/dev/null 2>&1; then
    while n="$(ufw status numbered 2>/dev/null | grep 'Price Verifier' | head -n1 | sed -E 's/^\[ *([0-9]+)\].*/\1/')" && [ -n "$n" ]; do
        ufw --force delete "$n" >/dev/null
    done
fi
if command -v firewall-cmd >/dev/null 2>&1 && firewall-cmd --state >/dev/null 2>&1; then
    for net in $(firewall-cmd --permanent --list-rich-rules | grep "port=\"$PORT\"" | sed -E 's/.*source address="([^"]+)".*/\1/'); do
        firewall-cmd --permanent --remove-rich-rule="rule family=\"ipv4\" source address=\"$net\" port port=\"$PORT\" protocol=\"tcp\" accept" >/dev/null || true
    done
    firewall-cmd --reload >/dev/null || true
fi

echo "Removing program files..."
rm -rf "${INSTALL_DIR:?}"/{bin,config,logs,sessions,data,VERSION}

if [ "$REMOVE_DATA" = 1 ]; then
    rm -rf "${INSTALL_DIR:?}"
    userdel "$SVC_USER" 2>/dev/null || true
    echo "The Price Verifier server and its data were removed."
else
    rm -f "$INSTALL_DIR/uninstall.sh"
    echo "The Price Verifier server was removed. Portal data was kept in $INSTALL_DIR/portal."
fi

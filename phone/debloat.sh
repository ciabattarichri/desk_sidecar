#!/usr/bin/env bash
# Removes the packages listed in debloat.txt for user 0 (reversible, no root, no Knox trip).
#   phone/debloat.sh            remove
#   phone/debloat.sh --restore  reinstall them
# Re-run after a system update: OTA updates can bring some back.
set -euo pipefail
LIST="$(dirname "$0")/debloat.txt"
installed="$(adb shell pm list packages | sed 's/package://' | tr -d '\r')"
grep -vE '^\s*(#|$)' "$LIST" | while read -r pkg; do
    if [[ "${1:-}" == "--restore" ]]; then
        adb shell cmd package install-existing "$pkg" >/dev/null 2>&1 && echo "restored  $pkg" || echo "skip      $pkg"
    elif grep -qx "$pkg" <<<"$installed"; then
        printf '%-60s %s\n' "$pkg" "$(adb shell pm uninstall -k --user 0 "$pkg" 2>&1 | tr -d '\r')"
    fi
done

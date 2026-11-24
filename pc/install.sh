#!/usr/bin/env bash
# Installs the PC side of desk_sidecar for the current user (no sudo):
#   - phonedash CLI        -> ~/.local/bin/phonedash (symlink)
#   - systemd user service -> ~/.config/systemd/user/phonedash.service
#   - DMS bar plugin       -> ~/.config/dms/plugins/phoneDash (symlink)
# Re-running it is safe. Use --uninstall to remove everything it installed (config is kept).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
BIN="$HOME/.local/bin/phonedash"
UNIT="$HOME/.config/systemd/user/phonedash.service"
PLUGIN="$HOME/.config/dms/plugins/phoneDash"

if [[ "${1:-}" == "--uninstall" ]]; then
    systemctl --user disable --now phonedash.service 2>/dev/null || true
    rm -f "$UNIT" "$BIN"
    [[ -L "$PLUGIN" ]] && rm -f "$PLUGIN"
    systemctl --user daemon-reload
    echo "Uninstalled. Config and token are still in ~/.config/phone-dash/."
    exit 0
fi

mkdir -p "$(dirname "$BIN")" "$(dirname "$UNIT")"
ln -sfn "$ROOT/pc/phonedash" "$BIN"
sed "s|@PHONEDASH@|$ROOT/pc/phonedash|" "$ROOT/pc/phonedash.service.in" > "$UNIT"
systemctl --user daemon-reload

if [[ -d "$HOME/.config/dms" ]]; then
    mkdir -p "$(dirname "$PLUGIN")"
    ln -sfn "$ROOT/dms-plugin/phoneDash" "$PLUGIN"
    command -v dms >/dev/null && dms ipc call plugins enable phoneDash >/dev/null 2>&1 || true
    echo "DMS plugin linked. Add the 'Desk Sidecar' widget to your bar in DMS settings."
fi

cat <<MSG
Installed. Next steps:
  1. Phone on USB, dashboard app opened once:   phonedash pair
  2. Edit ~/.config/phone-dash/config.toml      (phone = your Tailscale name or IP)
  3. Start the service:                          systemctl --user enable --now phonedash.service
MSG

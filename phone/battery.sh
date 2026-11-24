#!/usr/bin/env bash
# Battery settings used for the always-on desk_sidecar dashboard (Galaxy A53 / One UI 8). All reversible.
#   phone/battery.sh            apply
#   phone/battery.sh --revert   undo
set -euo pipefail
if [[ "${1:-}" == "--revert" ]]; then
    adb shell settings put secure refresh_rate_mode 2
    adb shell cmd connectivity airplane-mode disable
    adb shell svc bluetooth enable
    adb shell settings put system aod_mode 1
    adb shell settings put system haptic_feedback_enabled 1
    for p in com.google.android.googlequicksearchbox com.google.android.apps.messaging com.android.chrome; do
        adb shell pm enable --user 0 "$p"
    done
    exit 0
fi
adb shell settings put secure refresh_rate_mode 0          # 60 Hz (the panel only does 60/120)
adb shell cmd connectivity airplane-mode enable            # modem off (no usable SIM) ...
sleep 3
adb shell svc wifi enable                                   # ... but Wi-Fi back on (Tailscale needs it)
adb shell svc bluetooth disable
adb shell settings put system aod_mode 0                   # Always On Display off
adb shell settings put system haptic_feedback_enabled 0
for p in com.google.android.googlequicksearchbox com.google.android.apps.messaging com.android.chrome; do
    adb shell pm disable-user --user 0 "$p"                 # Google app/Assistant, Messages, Chrome
done
echo "Done. NFC can only be turned off by hand: Settings > Connections > NFC."

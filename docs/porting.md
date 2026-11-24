# Porting to another phone or desktop

The project was built around one phone and one desktop, but almost everything is generic. This
guide lists what is device- or desktop-specific, how to adapt each piece, and how to let an AI
coding assistant do most of the legwork safely.

## What is portable as-is

* The Android app: any phone with **Android 13+ (API 33)**, Wi-Fi and a decent screen. Stock,
  debloated stock or a custom ROM all work. No root needed.
* `phonedash` core: HTTP protocol, schedule, photos, Billable Hours, post-its, options.
* The data sources the phone fetches itself: weather, calendar, Google Home, lyrics.

## What depends on the phone

| Area | Where | What to check / change |
|---|---|---|
| Screen size and aspect | `Dashboard.kt`, `MediaCard.kt`, `Tabs.kt`, `WeatherTab.kt` | The layout targets a ~915 × 410 dp landscape window (6.5" 20:9). On wider/narrower screens tweak clock size (`textStyle(104, …)`), player cover size, paddings. Take screenshots with `adb exec-out screencap -p > shot.png` and iterate. |
| Panel type | — | AMOLED makes the black background and "black screen off" almost free. On LCD the backlight stays on: prefer real schedules with `off` ranges and lower brightness. |
| Refresh rates | `phone/battery.sh` | `refresh_rate_mode` is Samsung-specific. On other phones look at `adb shell dumpsys display \| grep -i refresh` and the settings `peak_refresh_rate` / `min_refresh_rate` (system/secure tables). |
| Bloatware | `phone/debloat.txt` | Vendor-specific. Generate a new list (see below). Never remove packages you can't identify. |
| Battery killers | vendor settings | Some vendors kill foreground apps or limit Wi-Fi in standby. Exclude the app from battery optimization (`adb shell dumpsys deviceidle whitelist +dev.vq.dashboard`) and check the vendor notes on dontkillmyapp.com. |
| Default launcher (for undo) | `docs/setup.md` → Uninstall | `adb shell cmd package resolve-activity -a android.intent.action.MAIN -c android.intent.category.HOME` before switching tells you the original one. |
| Light sensor | `Widgets.kt` (`Ambient`) | Lux buckets are tuned by eye; sensors differ. Log values with a quick `adb logcat` print if brightness feels off, or disable it (`light_sensor = false`). |
| Case | `case/a53_phone_model.FCMacro` | Body, screen, port and camera parameters at the top. Measure the phone. |

## What depends on the desktop

`phonedash` reads four desktop things. Each is one small function in `pc/phonedash`:

| Feature | Function / constant | DMS implementation | Alternatives |
|---|---|---|---|
| Theme colours | `COLORS`, `state_payload()` | `~/.cache/dms/dms-colors.json` (`colors.dark.*`, Material 3 role names) | any generator that outputs Material 3 roles (matugen does natively); for pywal/wallust-style palettes, map the 16 colours to roles; or omit `colors` and the phone uses its built-in palette |
| Notifications | `recent_notifications()` | `~/.cache/dms/notification_history.json` | `makoctl history` (JSON), `dunstctl history` (JSON), swaync's control client; or a small D-Bus monitor on `org.freedesktop.Notifications` writing a JSON file |
| Lock state | `pc_locked()` | `dms ipc call lock isLocked`, falls back to logind `LockedHint` | logind `LockedHint` works if your locker sets it; otherwise check whether the locker process is running (`pgrep -x <locker>`) |
| System stats | `pc_stats()`, `_hwmon_temp()` | `/proc/stat`, `/proc/meminfo`, hwmon `coretemp` and `nvme` | hwmon names differ: `k10temp`/`zenpower` (AMD CPUs), `amdgpu`, `nvme`… list them with `cat /sys/class/hwmon/hwmon*/name` |

Everything else is plain Linux:

* **Service manager**: `pc/phonedash.service.in` is a systemd user unit. Without systemd, run
  `phonedash run` from your session autostart (it handles SIGTERM to say goodbye on shutdown).
* **Bar integration**: the plugin in `dms-plugin/` is DMS-only, but it is just a UI over
  `phonedash`. Any bar that runs commands can do the same: a module that prints
  `phonedash status` (JSON) every 30 s and calls `phonedash brightness N`, `phonedash screen …`,
  `systemctl --user start|stop phonedash` on click. Waybar custom modules, eww, AGS, Quickshell,
  Polybar all fit.
* **Transport**: Tailscale is optional. On a LAN, give the phone a fixed IP and set `phone` to it.

## Doing it with an AI assistant

The fastest route is to pair with an AI coding assistant that can run shell commands (Claude
Code or similar) in a clone of this repository, with the phone connected over USB.

### Rules worth stating up front

1. Read-only first: the assistant inventories the phone and the PC before changing anything.
2. Every change on the phone must be reversible (`pm uninstall -k --user 0`, `pm disable-user`,
   `settings put` with the old value noted) and listed before it is applied.
3. The assistant asks before installing packages with sudo, enabling services, or touching your
   desktop configuration; you review the commands.
4. Never remove packages it cannot identify; never touch `android`, `com.android.systemui`,
   phone/telephony providers, the keyboard, or the package installer.

### Suggested sequence and prompts

**1. Give it the context.**
> Read README.md and docs/how-it-works.md. I want to run desk_sidecar on a different phone
> and a different Linux desktop. Ask before any action that changes the phone or my system.

**2. Inventory the phone (read-only).**
> With adb, collect: model, Android version, screen resolution and density, supported refresh
> rates, panel type if you can tell, installed packages, battery health. Summarize what needs
> adapting compared with the Galaxy A53 setup.

Useful commands it will run: `adb shell getprop`, `adb shell wm size`, `adb shell wm density`,
`adb shell dumpsys display`, `adb shell pm list packages -s` / `-3`.

**3. Build a debloat list.**
> Classify the system packages into "safe to remove for a kiosk dashboard", "keep" and "unsure",
> with a one-line reason each. Write the safe ones to phone/debloat.txt in the same format. Don't
> apply anything yet.

Review it, then run `phone/debloat.sh`.

**4. Adapt the battery script.**
> Rewrite phone/battery.sh for this phone: find the right settings keys for refresh rate,
> always-on display and haptics on this vendor, keep airplane mode + Wi-Fi, and write a matching
> --revert. Show me the diff.

**5. Fit the layout.**
> Build and install the app, set it as launcher, take a screenshot and compare with the intended
> layout. Adjust sizes for this screen until nothing overlaps or is cut off, in both the
> "music playing" and "no music" layouts.

**6. Adapt the desktop integration.**
> On this PC, find where my notification daemon keeps its history, how to know if the session is
> locked, which hwmon sensors exist and whether a Material 3 palette file is available. Update
> recent_notifications(), pc_locked(), pc_stats() and the colours in pc/phonedash accordingly,
> with fallbacks, and test each function on its own before restarting the service.

**7. Bar module (optional).**
> Write a module for my bar that shows the phone's brightness from `phonedash status` and offers
> brightness, screen and slideshow controls, styled like the rest of my bar.

**8. Case (optional).**
> I measured the phone: <width> × <length> × <thickness> mm, screen <w> × <h> mm with bezels
> <…>, USB-C at <position>, camera module <…>. Update case/a53_phone_model.FCMacro (rename it)
> and the stand macro, run them headless with FreeCAD and show me a preview.

### Things to verify yourself

* `phonedash status` shows `"pc_online": true` and the brightness changes with the schedule.
* Locking the PC switches the phone to the photo frame (or black screen) within a few seconds,
  and stopping the service does it immediately.
* Notifications, theme colours and PC stats appear on the phone.
* After a day: no removed apps came back, the phone stays reachable overnight, and CPU is near
  zero when idle (`adb shell top -b -n 1 -m 5`).

# Setup

From a phone in a drawer to a working desk_sidecar. Written for a Samsung Galaxy A53 5G and a
Fedora PC running niri + DankMaterialShell (DMS); other Android 13+ phones and other desktops
work with small changes — see **[porting.md](porting.md)** for a different phone or desktop
(including how to do it with an AI assistant).

**Time:** about an hour, most of it waiting for system updates.

## 0. What you need

| On the PC | |
|---|---|
| `adb` | `sudo dnf install android-tools` |
| JDK 21+ with `javac` | `sudo dnf install java-25-openjdk-devel` (only to build the app) |
| Android SDK | platform 37 + build-tools (Android Studio's SDK manager or `cmdline-tools`) |
| Python 3.11+ and Pillow | Fedora ships both (`python3-pillow`) |
| Tailscale (recommended) | already on the PC; install the Android app on the phone |
| DMS (optional) | for the bar plugin and the live theme / notifications / lock state |

Prefer not to build? Use the APK from a release bundle (`release/v*/`, see
[release/README.md](../release/README.md)) and skip the JDK/SDK.

## 1. Prepare the phone

1. **Factory reset** (optional but recommended) and sign in with the Google account whose
   calendar you want to see. Install every pending **system update** before going on: updates can
   bring removed apps back.
2. **Developer options**: *Settings → About phone → Software information*, tap *Build number*
   7 times. Then *Settings → Developer options → USB debugging* on. Connect the USB cable and
   accept the RSA prompt ("Always allow").
3. Check that the PC sees it: `adb devices -l` must say `device`, not `unauthorized`.
4. **Debloat** (reversible, no root):
   ```sh
   phone/debloat.sh            # phone/debloat.sh --restore to undo
   ```
   The list is in `phone/debloat.txt`; edit it freely. `com.samsung.android.fmm` refuses to go,
   that's expected.
5. **Tailscale**: install it from the Play Store, sign in to your tailnet and note the device name
   (`tailscale status` on the PC), e.g. `galaxy-a53-5g`. On a plain LAN give the phone a fixed IP
   in your router instead.
6. **No SIM** needed. If there is one, take it out: a SIM that can't register keeps the modem
   searching.
7. Optional: turn off the lock screen (*Settings → Lock screen → None*), so that after a reboot
   you land on the dashboard and not on a PIN pad.

## 2. Build and install the app

```sh
./gradlew assembleRelease
adb install -r app/build/outputs/apk/release/app-release.apk
```

(`local.properties` must point to your SDK: `sdk.dir=/home/<you>/Android/Sdk`.)

Grant what the app needs and make it the launcher:

```sh
P=dev.vq.dashboard
adb shell pm grant $P android.permission.READ_CALENDAR
adb shell pm grant $P android.permission.ACCESS_COARSE_LOCATION
adb shell cmd notification allow_listener $P/$P.MediaListener   # "now playing"
adb shell cmd package set-home-activity $P/.MainActivity
adb shell input keyevent KEYCODE_HOME
```

The notification-listener access has to be granted over adb: Android 13+ blocks the toggle in
Settings for sideloaded apps.

> Back to Samsung's launcher at any time: long press on the dashboard → **Change launcher**.

## 3. Battery settings (optional, recommended)

```sh
phone/battery.sh            # phone/battery.sh --revert to undo
```

60 Hz, airplane mode with Wi-Fi back on, Bluetooth / Always-On Display / haptics off, Google app,
Messages and Chrome disabled. NFC can only be switched off by hand (*Settings → Connections*).
Leave Bluetooth on if you pair speakers or headphones to this phone.

## 4. Install the PC side

```sh
pc/install.sh
```

This links `phonedash` into `~/.local/bin`, writes the systemd user unit and (if DMS is present)
links and enables the bar plugin. Then:

```sh
phonedash pair                        # phone on USB, dashboard opened at least once
$EDITOR ~/.config/phone-dash/config.toml
systemctl --user enable --now phonedash.service
phonedash status                      # should print JSON with "pc_online": true
```

In `config.toml` set at least `phone` (Tailscale name or IP). Everything else has sensible
defaults; see [configuration.md](configuration.md).

**DMS bar plugin**: add the *Desk Sidecar* widget to your bar in DMS settings (Bar → widgets).
Click it for brightness, screen, post-its, slideshow and **Options**.

## 5. Optional integrations

### Photos for the slideshow
Set `[slideshow] folders` in `config.toml`. The service uploads them in the background (resized
to `max_size`); `phonedash sync-photos` does it now with a progress counter.

### Google Home
Open the **Home** tab on the dashboard → *Connect Google Home* and sign in. If Google rejects the
embedded browser:

1. on the PC, open <https://accounts.google.com/EmbeddedSetup> in a private window and sign in;
2. copy the value of the `oauth_token` cookie for `accounts.google.com` (browser dev tools →
   Storage → Cookies);
3. `phonedash google-token '<value>'`.

Use a secondary Google account that is a member of your home if you can (see
[how-it-works.md](how-it-works.md#google-home)). Long press a device to hide it.

### Billable Hours
Copy the backup web-app URL and secret word from the watchapp settings (*Options → Backup*) into
`[billable]` in `config.toml`. The hours card appears next to the clock within a minute.

### Outlook / other calendars
Anything synced into Android's calendar shows up: install the Outlook app and enable *Sync
calendars*, or add the account in *Settings → Accounts*. Hide calendars you don't want (e.g. a
shared boss calendar) from the drawer: long press → **Calendars**.

## 6. Daily use

* Long press anywhere: app drawer (long press an app to hide it), calendars, weather city,
  settings.
* Tap the player cover/buttons, tabs, devices; everything else is meant to be looked at.
* From the PC: the bar plugin, or `phonedash brightness 30`, `phonedash screen off`, …

## Troubleshooting

| Symptom | Check |
|---|---|
| Plugin pill shows `—` | `phonedash status`; is the phone on Wi-Fi / Tailscale up? `phone` in config right? |
| `unauthorized` from the phone | token mismatch: `phonedash pair` again |
| No "now playing" | `adb shell cmd notification allow_listener dev.vq.dashboard/dev.vq.dashboard.MediaListener` |
| Empty agenda | calendar permission; calendar visible in the Samsung/Google Calendar app; not hidden in the drawer |
| No weather | set a city in the drawer, or grant location |
| Photo frame never appears | the service must stop (or the PC lock) for it to trigger; slideshow folder must contain images |
| Removed apps came back | an OTA update reinstalled them: run `phone/debloat.sh` again |
| Logs | `journalctl --user -u phonedash -f`; on the phone `adb logcat \| grep dev.vq` |

## Uninstall

```sh
pc/install.sh --uninstall                   # keeps ~/.config/phone-dash
adb shell cmd package set-home-activity com.sec.android.app.launcher/.activities.LauncherActivity
adb uninstall dev.vq.dashboard
phone/battery.sh --revert; phone/debloat.sh --restore
```

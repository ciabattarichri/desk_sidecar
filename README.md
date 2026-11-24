# desk_sidecar

**Desk Sidecar** — an old Android phone turned into an always-on desk display that looks and behaves like part of
the desktop: it follows the PC's [DankMaterialShell](https://github.com/AvengeMedia/DankMaterialShell)
theme, shows the PC's notifications, knows when the PC is locked or off, and is controlled from
the DMS bar.

Built for a Samsung Galaxy A53 5G on stock One UI (debloated) and a Fedora PC with niri + DMS.

## Features

* **Glanceable home screen** — rounded bold clock, date, weather with forecast, Billable Hours
  totals (today / month), agenda with a "next event in N min" banner.
* **Now playing** — any media session (Spotify, …) in a DMS "Bento"-style player: cookie-shaped
  cover, wavy progress bar, connected transport buttons; a mini control pill when paused.
* **Tabs** — Notifications (from the PC), Agenda, Home (Google Home devices), Photos, Weather
  (3-hour table: temperature, rain, humidity), PC (CPU, RAM, temperatures). Reorder and hide them
  from the bar plugin.
* **Photo frame** — when the PC is off or locked (or on demand): full-screen Ken Burns slideshow
  of a PC folder, clock and weather, synced lyrics, now playing.
* **Post-its** written from the PC, stuck on the dashboard.
* **Live theme** — the PC's matugen palette is pushed to the phone; Google Sans Flex + Material
  Symbols Rounded, black AMOLED background.
* **Power-aware** — brightness schedule × ambient light, night clock, black "screen off" ranges,
  near-zero CPU when idle.
* **Controlled from the PC** — `phonedash` CLI and a DMS bar plugin (brightness, screen,
  slideshow, post-its, options, IT/EN language).
* **Companion launcher** — [sidecar_launcher](https://github.com/ciabattarichri/sidecar_launcher), a
  portrait phone launcher in the same style that pulls photos and colours from `phonedash`.
* **3D-printable stand** — parametric FreeCAD macros for the phone model and a desk stand.

## Repository layout

| Path | What |
|---|---|
| [`app/`](app) | Android app (Kotlin, Jetpack Compose), package `dev.vq.dashboard` |
| [`pc/`](pc) | `phonedash` CLI/service, systemd unit template, `install.sh` |
| [`dms-plugin/phoneDash/`](dms-plugin/phoneDash) | DMS bar widget |
| [`phone/`](phone) | debloat list and scripts, battery settings script |
| [`case/`](case) | FreeCAD macros, STEP/STL of the phone model and the stand |
| [`docs/`](docs) | [how it works](docs/how-it-works.md), [setup](docs/setup.md), [configuration](docs/configuration.md), [porting](docs/porting.md) |
| [`release/`](release) | release bundles (built by `scripts/make-release.sh`) |
| [`licenses/`](licenses) | licenses of the bundled fonts |

## Quick start

```sh
./gradlew assembleRelease
adb install -r app/build/outputs/apk/release/app-release.apk
pc/install.sh
phonedash pair && $EDITOR ~/.config/phone-dash/config.toml
systemctl --user enable --now phonedash.service
```

The full walkthrough (phone preparation, permissions, Tailscale, Google Home, Billable Hours,
troubleshooting) is in **[docs/setup.md](docs/setup.md)**. Different phone or desktop? Start
from **[docs/porting.md](docs/porting.md)**.

## Building a release

```sh
scripts/make-release.sh        # -> release/v<versionName>/
```

Bump `versionCode` / `versionName` in `app/build.gradle.kts` first. See
[release/README.md](release/README.md).

## Requirements

* Phone: Android 13+ (API 33), landscape, ideally AMOLED. Tested on Galaxy A53 5G, One UI 8.
* PC: Linux with Python 3.11+, Pillow, adb, systemd user services. DMS is optional (theme,
  notifications, lock state and the bar plugin need it).
* Build: JDK 21+, Android SDK platform 37, Gradle wrapper included (Gradle 9.9, AGP 9.5,
  Kotlin 2.4).

## Caveats

* Google Home uses the Home app's **private, reverse-engineered API** (ported from Core Devices'
  Pebble plugin). It can break at any time and needs a powerful account token — prefer a
  secondary Google account. Details in [docs/how-it-works.md](docs/how-it-works.md#google-home).
* The release APK is signed with the debug key: it is meant for sideloading on your own device.
* The phone's HTTP API is protected by a token only; keep it on Tailscale or a trusted LAN.

## License

**[PolyForm Noncommercial 1.0.0](LICENSE.md)** (SPDX: `PolyForm-Noncommercial-1.0.0`).

In plain words: you can use, copy, modify and share this project for any **noncommercial**
purpose — personal use, study, hobby projects, research, non-profits. **Commercial use is not
allowed** (selling it, or using it in a product or service for profit). Keep the license and the
`Required Notice` line when you share it. This is a *source-available* license, not an
OSI-approved open-source one, precisely because of the noncommercial clause.

Bundled fonts keep their own licenses (OFL 1.1 and Apache 2.0) — see
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

This project was written largely with the help of an AI coding assistant (Claude Code), under
human direction and review.

## Credits

* [DankMaterialShell](https://github.com/AvengeMedia/DankMaterialShell) — the design this follows.
* [Core Devices](https://github.com/coredevices/pebble-apps) — Google Home private API research.
* [Open-Meteo](https://open-meteo.com), [LRCLIB](https://lrclib.net) — free weather and lyrics APIs.
* Third-party notices: [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

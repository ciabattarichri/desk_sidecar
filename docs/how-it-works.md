# How it works

This document explains the moving parts of desk_sidecar and how they talk to each other.
For installation see [setup.md](setup.md); for every setting and command see
[configuration.md](configuration.md).

## The big picture

```
 ┌──────────────────────────── PC (Fedora · niri · DankMaterialShell) ───────────────────────────┐
 │                                                                                               │
 │  ~/.config/phone-dash/config.toml ──┐                                                         │
 │  ~/.cache/dms/dms-colors.json ──────┤  (matugen palette)                                      │
 │  ~/.cache/dms/notification_history ─┤                                                         │
 │  DMS lock state · /proc · hwmon ────┼──►  phonedash run  (systemd --user service)             │
 │  ~/Pictures/... (slideshow) ────────┘          │      ▲                                       │
 │                                                │      │ CLI calls                             │
 │                       DMS bar plugin "phoneDash" ──────┘ (brightness, screen, slideshow,      │
 │                                                           post-its, options)                  │
 └────────────────────────────────────────────────┼──────────────────────────────────────────────┘
                                                  │ HTTP + bearer token, over Tailscale (or LAN)
                                                  ▼  :8765
 ┌──────────────────────────── Phone (Galaxy A53, stock One UI, debloated) ──────────────────────┐
 │  dev.vq.dashboard — a Jetpack Compose launcher                                                │
 │                                                                                               │
 │  HttpServer ──► Remote (config, palette, heartbeat, lock, notes, notifications, PC stats)     │
 │                    │                                                                          │
 │                    ▼                                                                          │
 │  Dashboard UI ◄── NowPlaying (media sessions) · AgendaRepo (Android calendar provider)        │
 │               ◄── WeatherRepo (Open-Meteo) · Billable (Google Sheets web app)                 │
 │               ◄── GoogleHome (private "foyer" API) · Lyrics (LRCLIB) · Ambient light sensor   │
 └───────────────────────────────────────────────────────────────────────────────────────────────┘
```

The phone is the display and does its own fetching (weather, calendar, Google Home, Billable
Hours, lyrics), so it keeps working when the PC is off. The PC pushes everything that only the
PC knows: the theme colours, the schedule and options, desktop notifications, system stats, the
lock state, post-its and the photos for the slideshow.

## The phone app (`app/`)

A single-activity Compose app registered as the **HOME launcher**, landscape, full screen,
`FLAG_KEEP_SCREEN_ON`. A long press anywhere opens the app drawer.

### Screen states

| State | When | What is shown |
|---|---|---|
| Day | default | clock, hours card, date, weather/forecast or player, tab card |
| Night | inside a `night` range | dimmed rounded clock only (+ current track) |
| Photo frame | PC off, PC locked (`on_lock = "frame"`) or slideshow mode from the plugin | full-screen Ken Burns slideshow, clock/weather pill, lyrics card, now-playing pill |
| Screen off | inside an `off` range, PC locked with `on_lock = "off"`, or `phonedash screen off` | pure black at minimum brightness (AMOLED pixels off); a tap wakes it for 30 s |

Any touch keeps the dashboard up for 10 s before the photo frame returns.

### Source files

| File | Responsibility |
|---|---|
| `MainActivity.kt` | launcher activity, immersive mode, permission requests, starts the HTTP server, ambient sensor, restarts itself on a language change |
| `Dashboard.kt` | top-level layout and screen states, clock, hours card, weather pills, mini controls, agenda list, next-event banner, photo frame |
| `Tabs.kt` | tab card (order/visibility from config), Google Home tab |
| `MediaCard.kt` | DMS "Bento"-style player: cookie-shaped cover, wavy progress, connected buttons |
| `NowPlaying.kt` | follows the active media session via a `NotificationListenerService` |
| `Agenda.kt` | events from every visible calendar synced on the phone (Google, Outlook app, …) |
| `Weather.kt` / `WeatherTab.kt` | Open-Meteo current/daily/hourly data; 3-hour table tab |
| `Billable.kt` | totals of the Billable Hours watchapp read from its Google Sheets backup web app |
| `GoogleHome.kt` / `LoginActivity.kt` | Google Home devices through the Home app's private API |
| `Notifications.kt` | PC notifications tab |
| `Widgets.kt` | PC stats tab, post-it overlay, ambient light sensor |
| `Lyrics.kt` | synced lyrics from LRCLIB, event-driven line switching |
| `Photos.kt` | photo store (app-private) and the cross-fading slideshow |
| `Remote.kt` | everything the PC controls: `RemoteConfig`, overrides, heartbeat/lock state |
| `HttpServer.kt` | tiny HTTP/1.1 server the PC talks to |
| `Theme.kt` | palette (Material 3 roles from matugen), fonts, icons, the `Pill` component |
| `I18n.kt` | Italian/English strings (`tr("it", "en")`) |
| `AppDrawer.kt` / `Prefs.kt` | drawer (hide apps, calendars, weather city) and local preferences |

### Look and feel

The UI copies the DMS bar: fully rounded pills on `surfaceContainer`, cards with 28 dp corners,
`primary` accents, Google Sans Flex (the DMS UI font, bundled) and Material Symbols Rounded
(bundled as a ~15 KB subset containing only the icons used). The colours are the PC's live
matugen palette, so the phone follows the wallpaper. The background is pure black so AMOLED
pixels are off.

## The PC side (`pc/`, `dms-plugin/`)

### `phonedash`

A single Python 3 script (standard library + Pillow for photos):

* **`phonedash run`** is the service loop (systemd user unit). Every 3 s it checks the DMS lock
  state; every 30 s, or immediately when the lock state changes, it `POST`s `/state` with the
  config, palette, notifications, PC stats, lock state and post-its. That request is also the
  **heartbeat**: if the phone hears nothing for `pc_offline_after` seconds it assumes the PC is
  off. On `SIGTERM` (service stopped, PC shutting down) it sends a goodbye so the phone switches
  to the photo frame immediately.
* Every 10 minutes a background thread **syncs photos**: images from the configured folders
  are resized (long side `max_size`), named by a hash of path + mtime + size and uploaded; photos
  that no longer exist on the PC are deleted from the phone.
* A small **pull server** (port 8766, bearer token) serves the slideshow photos, resized on
  demand and cached, and the matugen palette to [sidecar_launcher](https://github.com/ciabattarichri/sidecar_launcher),
  the companion phone launcher, which downloads them on Wi-Fi while it is on screen.
  `phonedash pair-launcher` hands it the PC addresses (Tailscale name and IP, LAN IPs) and the token;
  the phone asks for confirmation before accepting.
* The other sub-commands are thin wrappers used by you or by the plugin (brightness, screen,
  schedule, notes, layout, options, …); they edit `config.toml` and push it at once.

### DMS plugin

`dms-plugin/phoneDash` is a DankBar widget: a pill with a phone icon and the current brightness,
grey when the phone is unreachable. Its popout has the everyday controls (brightness, screen,
post-its, slideshow on/off) and an **Options** section (schedule editor, tab order/visibility,
player animation, lyrics, light sensor, slideshow speed, lock behaviour, language). It never talks
to the phone directly: it runs `phonedash`, so all the logic lives in one place.

## Phone ⇄ PC protocol

Plain HTTP on port **8765**, every request needs `Authorization: Bearer <token>`. The token is
generated on the phone at first start and copied to the PC with `phonedash pair` (USB).

| Method & path | Body | Effect |
|---|---|---|
| `POST /state` | `{config, colors, notifications, notes, pc_locked, pc_stats}` | heartbeat + apply everything |
| `POST /cmd` | `{"brightness": 30 \| "auto"}`, `{"screen": "on" \| "off" \| "auto"}`, `{"oauth_token": "…"}`, `{"pc": "offline"}` | overrides, Google Home sign-in, goodbye |
| `GET /status` | — | brightness, overrides, screen state, photo count, Google Home, PC online, heartbeat age |
| `GET /photos` | — | list of stored photo names |
| `PUT /photos/<name>` | JPEG | store a photo |
| `DELETE /photos/<name>` | — | remove a photo |

Overrides (brightness, screen) last until the schedule slot they override changes.
`POST /cmd` also takes `{"mirror": "full" | "tab" | "off"}` (PC screen full screen / Screen tab / back).

### PC screen ("Screen" tab)

A separate TCP connection on **127.0.0.1:8767** of the PC, reached by the phone through
`adb reverse tcp:8767 tcp:8767` (kept up by `phonedash run` on every USB-connected phone): it works
without Wi-Fi and nothing listens on the network. The phone opens it only while the Screen tab or the
full-screen view is visible, and starts with a JSON line `{"token": "…"}` (its API token).

* PC → phone: frames of 1-byte type + 4-byte big-endian length + payload — `1` = `{"w","h"}` JSON,
  `2` = one H.264 access unit (Annex-B, SPS/PPS before each keyframe).
* Phone → PC: JSON lines — `{"t":"d"|"m"|"u","i":id,"x":0..1,"y":0..1}` touches, `{"t":"key"}`
  keyframe request.

Per connection `phonedash` runs `phonedash _mirror-stream OUTPUT BITRATE FPS`: niri's
`org.gnome.Mutter.ScreenCast` D-Bus API (`RecordMonitor`, cursor embedded) → PipeWire →
GStreamer `glupload ! glcolorconvert ! gldownload` (DMA-BUF import) → `nvh264enc` (low latency,
CBR, keyframe every 2 s, at most `fps` frames in) → stdout. Touches go to a uinput multitouch
device; niri binds it to the output with `input { touch { map-to-output "OUTPUT" } }`. Settings in
`[mirror]` of `config.toml` (output, port, bitrate, fps).

## External services

| Service | Used for | Notes |
|---|---|---|
| [Open-Meteo](https://open-meteo.com) | weather, geocoding | free, no key |
| [LRCLIB](https://lrclib.net) | synced lyrics | free, no key |
| Google Apps Script web app | Billable Hours totals | your own backup script (`doGet`), URL + secret word in the config |
| Google Home "foyer" API | devices and on/off | **private, reverse engineered** (see below) |
| Tailscale | phone ⇄ PC transport | optional; plain LAN works too |

### Google Home

There is no public API that reads the state of existing devices without registering an app, so
this uses the private API of the Google Home app, ported from Core Devices' Pebble plugin
(`coredevices/pebble-apps`, `googlehome/`). Sign-in happens on Google's EmbeddedSetup page inside
the app; the resulting `oauth_token` is exchanged once for a **master token** stored in the app's
private storage, from which short-lived bearers are minted. Devices are listed every 30 minutes
and their state polled every 30 s while the screen is on.

* The master token is powerful (account-level). Prefer a secondary Google account that is a
  member of your home.
* The API is undocumented and may break at any time; the rest of the dashboard is unaffected.

## Power design

The phone is always plugged in and always on, so the app is built to do almost nothing while
nobody looks at it:

* release build with R8 (Compose is several times faster than in debug);
* the whole screen recomposes once a minute; timers use a 2 s tick read through `derivedStateOf`,
  so nothing recomposes unless a result flips;
* player wave (20 fps) and cover spin (10 fps) are stepped and drawn in the draw/layer phase, and
  by default run only for 30 s after a track change or a touch;
* lyrics wake up only when the next line is due;
* black background on AMOLED; brightness follows a schedule and the room light;
* "screen off" is a black frame instead of a real screen-off, so the app stays reachable;
* on the phone: 60 Hz, airplane mode with Wi-Fi, Bluetooth/AOD/haptics off, bloat removed
  (`phone/battery.sh`, `phone/debloat.sh`).

Measured on the A53: ~78% CPU + 30% SurfaceFlinger with the first debug build, ~0% when idle
with music playing after these changes.

## Security model

* The HTTP server listens on all interfaces; every request needs the 48-hex-digit token. Use
  Tailscale and/or a trusted LAN.
* The token lives in the app's private storage and in its external files dir (readable only
  through adb, used by `phonedash pair`), and on the PC in `~/.config/phone-dash/token` (0600).
* `config.toml` contains the Billable web-app secret: keep it 0600 and out of git (it lives in
  `~/.config`, not in this repository).
* PC notifications and stats travel to the phone and are kept in memory only.
* The PC screen stream binds to 127.0.0.1 only (reached through `adb reverse`, i.e. the USB cable)
  and needs the same token; touches can only reach the mirrored output.

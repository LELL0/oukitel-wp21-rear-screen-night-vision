# Oukitel WP21 Rear Screen & Night Vision

Two root apps for the **Oukitel WP21**:

- **WP21 Rear Screen**: turns the small round rear display into a touch smartwatch-style screen.
- **WP21 Night Vision**: night camera viewfinder with control of the phone's infrared illuminator.

Download both APKs from the [Releases](../../releases) page.

## WP21 Rear Screen

Turns the small round rear screen of the **Oukitel WP21** into a touch device: watch faces, music,
a selfie viewfinder for the main cameras, notifications, calls, quick toggles, tools, a photo frame
and a programmable side button.

Needs root (Magisk). Tested on LineageOS 21 (Android 14) GSI with the stock vendor partition.
Hardware details are in [HARDWARE.md](HARDWARE.md).

## Features

All of these are operated by touching the rear screen itself.

| Gesture on the rear | Does |
|---|---|
| swipe left / right | next / previous page |
| swipe down from the top | quick toggles |
| swipe up from the bottom | notifications |
| double-tap the watch face | panel off (optional) |
| double-tap the dark panel | panel on (if the touch controller stays powered) |
| long-press | page options (on the watch face: face picker) |

**Pages** (reorder or hide them in the app):

- **Watch faces**: digital, analog with smooth seconds, minimal, battery ring, world clock, and
  complications (weather, steps, battery, next alarm, notification count). Colours and a photo
  background per face; dim always-on variant that updates once a minute.
- **Music**: the active media session (any player): art, title, artist, play/pause, next/previous,
  seek bar, volume by sliding a finger around the rim. Opens by itself when playback starts.
- **Camera**: live viewfinder from any camera (main, night, macro, front). Tap = photo, long-press =
  video, self-timer, flash. Saves to `DCIM/RearScreen`. Works while the phone is locked.
- **Tools**: launcher ring with the torch in the middle; **compass**, **spirit level**,
  **stopwatch** (laps), **timer** (set by sliding around the rim; rings with sound and vibration,
  shows the next alarm), **battery** (%, charging power, time to full, temperature, voltage),
  **steps** (daily count and goal).
- **Photo frame**: slideshow from a chosen folder or the newest photos.
- **Idle screen**: animated GIF/WebP or a muted looping video.

**Overlays**: quick toggles (torch, Wi-Fi, Bluetooth, DND, mobile data, auto-rotate, ringer mode,
rear brightness, panel off), notifications (expand, swipe to dismiss, reply-free actions; hidden
content while locked), new-notification popup (per-app mute list), incoming-call screen (slide to
answer/reject; caller name and photo) and in-call screen (timer, mute, speaker, hang up).

**Waking**: manual, follow the main screen, or always on; timeout; always-on clock when idle;
flip to wake (lay the phone face down); pocket check with the proximity sensor; auto brightness;
night dimming schedule; back to the watch face after a delay.

**Side button** (the programmable key, `KEY_F1`): single press toggles the rear screen; single,
double, triple and long press can each be set to: rear screen on/off, rear camera, torch,
play/pause, next/previous track, notifications, next rear page, DND, ringer mode, screenshot,
main screen on/off, voice assistant, or **open any app**.

**Settings app** (main screen): live mirror of the panel (touch it to control the rear), big
emergency **PANEL OFF** button, settings, page manager, side-button actions, watch-face editor,
calibration (display rotation/mirror, colour byte order, touch orientation by tapping 4 targets),
diagnostics (daemon status, fps, HAL return codes, raw touch viewer, brightness sweep, frame dump,
log export), permission setup (normal requests or one tap with root), start at boot.

## WP21 Night Vision

- Live viewfinder from the infrared-sensitive **night camera** (HAL camera 2), or any other camera.
- **IR illuminator on/off** (needs root) and **Auto IR** from the light sensor; the LEDs are switched off
  whenever you leave the app, even if it crashes.
- Modes: **Natural**, **Night green**, **Mono**, **Heat map** (false colour of brightness, not real
  thermal), **Amber**; **gain** (1–8×) and **exposure** sliders; pinch to zoom; HUD with lux and fps.
- Photos are saved full resolution **as you see them** to `Pictures/NightVision`; video is recorded
  unfiltered to `Movies/NightVision`.

## Install

1. Install the APKs: `adb install -r -g WP21RearScreen.apk` and `adb install -r -g WP21NightVision.apk`
   (or open them on the phone).
2. Open **WP21 Rear Screen**. Magisk asks for root: **Grant**.
3. Tap **Grant everything with root** (or grant the permissions one by one and enable notification
   access).
4. Open **Calibration** once: pick the rotation so the arrow points up, check the colours, tap the four
   touch targets.

The panel starts automatically after a reboot and is switched off when the phone shuts down.

## Emergency: turn the panel off

- In the app: the red **EMERGENCY: PANEL OFF** button (also disables the rear screen until you
  switch it back on).
- From a PC:

  ```
  adb shell su -c 'am force-stop com.lello.wp21rear; CLASSPATH=$(pm path com.lello.wp21rear | cut -d: -f2) app_process /system/bin com.lello.wp21rear.daemon.RearDaemon off'
  ```

  or `tools/rear.sh off`.

## Build

Requirements: JDK 17+ (21 works), Android SDK with platform 35 and build-tools, `ANDROID_HOME` or
`local.properties` with `sdk.dir`.

```
./gradlew assembleRelease          # app/…/app-release.apk and nightvision/…/nightvision-release.apk
./gradlew assembleDebug            # debug build, same signing key
adb install -r -g app/build/outputs/apk/release/app-release.apk
```

Signing: `keystore/keystore.properties` and `keystore/release.jks` (not committed). Create your own:

```
keytool -genkeypair -keystore keystore/release.jks -alias rear -keyalg RSA -keysize 2048 -validity 10000
printf 'storeFile=release.jks\nstorePassword=…\nkeyAlias=rear\nkeyPassword=…\n' > keystore/keystore.properties
```

The hidden HIDL classes (`android.os.HwBinder`, `HwParcel`, `HwBlob`, `IHwBinder`) come from the
compile-only `hidlstubs` module; the real ones are in the phone's framework. Minification is off so
the daemon entry point keeps its name.

## Architecture

```
 ┌──────────── app process (u0_aXXX) ─────────────┐        ┌──── root (magisk domain) ────┐
 │ RearService (foreground)                       │ stdin  │ RearDaemon (app_process)      │
 │  ├ RearUi: pages + overlays → 360×360 Canvas   │──────▶ │  ├ ITinylcd HAL: power, level, │
 │  ├ Panel: power sequences, RGB565, latest-wins │ frames │  │  frames                    │
 │  ├ WakeController: timeout, AOD, flip, prox    │ ◀──────│  ├ hyn_ts reader + EVIOCGRAB  │
 │  ├ ButtonHandler: KEY_F1 single/double/…/long  │ touch, │  ├ mtk-kpd reader (KEY_F1)    │
 │  ├ CallMonitor, NotifRepo, MediaRepo, Toggles  │ keys   │  └ sh -c for privileged cmds  │
 │  └ DaemonClient: su, framing, auto-restart     │        └───────────────────────────────┘
 │ NotifListener · BootReceiver · DebugReceiver   │
 │ Settings app: MainActivity (mirror) + screens  │
 └────────────────────────────────────────────────┘
```

- **Root daemon** (`daemon/RearDaemon.java`): a plain Java class in the APK, started by
  `su` + `app_process`. Binary protocol on stdin/stdout (`daemon/Proto.java`: 8-byte header, typed
  payloads). It turns the panel off when the app goes away (pipe closed) and on normal exit. The app
  restarts it with backoff if it dies.
- **UI toolkit** (`ui/`): `Page`/`Overlay` with draw, tap, double-tap, long-press and drag hooks; a
  gesture detector; pager with interactive transitions; pull-in overlays; toasts. Frames are drawn only
  when something changed or while animating (capped at 15 fps, the panel's limit), then rotated or
  mirrored per calibration and encoded to RGB565.
- **Battery**: no polling loops; a partial wakelock only while the panel is fully on (it expires by
  itself); the always-on clock wakes the CPU once a minute with an alarm; wake sensors are registered
  only while the panel is off and the feature is on; tool sensors only while their page is visible.

## Developer tools

`tools/rear.sh` (adb): `wake`, `sleep`, `goto <page>`, `dump [file.png]` (last pushed frame),
`tap X Y`, `swipe X1 Y1 X2 Y2`, `set KEY VALUE`, `log`, `off`.

The debug broadcasts it uses are protected by `android.permission.DUMP`, which only the shell holds.

## Troubleshooting

| Symptom | Fix |
|---|---|
| "Root daemon: down — su not available" | Magisk missing or root denied: Magisk → Superuser → allow WP21 Rear Screen. |
| Daemon keeps restarting | Diagnostics → log; check that `vendor.yft.hardware.tinylcd@1.0-service` runs (`adb shell ps -A \| grep tinylcd`). |
| Image upside down or mirrored | Calibration → step 1. |
| Wrong colours | Calibration → step 2 (byte order). |
| Swipes go the wrong way | Calibration → step 3. |
| Rear touches also tap the main screen | Settings → Advanced → "Block rear touches from the main screen" must be on and the daemon running. |
| No notifications / music | Grant notification access (button in the app). |
| Camera page says permission missing | Grant camera (and microphone for video sound). |
| Clock stops updating in always-on mode | Allow exact alarms (included in "Grant everything with root"). |
| Prototype app still installed | Uninstall `com.lello.rearscreen`; two apps driving the panel fight each other. |

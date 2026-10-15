# Oukitel WP21 rear screen: hardware findings

Measured on an Oukitel WP21 (MediaTek Helio G99, MT6789) running a LineageOS 21 (Android 14) GSI on top
of the stock Android 12 vendor partition, rooted with Magisk.

## Panel

| Item | Value |
|---|---|
| Panel | round AMOLED, 360×360 |
| Kernel device | `/dev/tiny_lcd_miscdev` (SELinux `tinylcd_device`) — never opened directly by this project |
| HAL | `vendor.yft.hardware.tinylcd@1.0::ITinylcd/default`, process `vendor.yft.hardware.tinylcd@1.0-service` |
| Pixel format | RGB565, big-endian (high byte first), 259 200 bytes per frame |
| Partial updates | none: the HAL only takes full frames |
| Frame write time | 63–71 ms per `lcd_wirte_frame`, independent of content (solid, pattern and noise all measured) → **about 15 fps maximum** |
| `lcd_frame_status()` | about 1 ms; the stock app (and this one) only writes when it returns `<= 1` |
| Brightness | `lcd_set_backlight(0..7)` |

The transfer rate works out to roughly 32 Mbit/s, which matches an SPI-class link; the HAL call blocks for the
whole transfer. An earlier measurement through the prototype helper reported ~17 ms per frame; that was
an artefact of the prototype's status polling and is not reproducible.

### HIDL transactions

| code | method | args / return |
|---|---|---|
| 1 | `lcd_set_power(int)` | 0 = panel power off, 1 = on |
| 2 | `lcd_set_backlight(int)` | brightness 0..7 |
| 3 | `lcd_set_bl(int)` | backlight 0 = off, 1 = on |
| 4 | `lcd_wirte_frame(uint8_t[259200])` | one `HwBlob`, written with `HwParcel.writeBuffer` |
| 5 | `lcd_frame_status()` | int |

Every request starts with `writeInterfaceToken("vendor.yft.hardware.tinylcd@1.0::ITinylcd")`; replies are
read with `verifySuccess()` then `readInt32()`.

Power sequences (from the stock app, used unchanged):

- **on**: `power(1)` → frame → ~400 ms → `bl(1)` → `backlight(level)` → frame
- **off**: `bl(0)` → ~100 ms → `power(0)`

### Access

Only `platform_app` and the HAL may use the service under SELinux. A Java entry point inside the APK started
as root with `su -c "CLASSPATH=<apk> app_process /system/bin <class>"` runs in Magisk's `magisk` domain and
can call `HwBinder.getService(...)` directly, so no SELinux policy change is needed.

## Rear touch

| Item | Value |
|---|---|
| Device | `hyn_ts` (Hynitron), `/dev/input/event3`, I²C `0-0015` |
| Protocol | multitouch **protocol A**: `BTN_TOUCH`, `ABS_MT_TRACKING_ID`, `ABS_MT_TOUCH_MAJOR`, `ABS_MT_POSITION_X/Y`, `SYN_MT_REPORT`, `SYN_REPORT` |
| Range | x, y 0..360, single contact (`ABS_MT_TRACKING_ID` max 1) |
| Report rate | about 70 Hz (one report every ~14 ms) |
| Gestures | none in hardware: taps and swipes arrive as plain coordinates |

**Ghost touches:** Android's input reader treats `hyn_ts` as a second touchscreen and scales it onto the
whole main display (`dumpsys input` shows X 0..1077, Y 0..2453, no associated display), so rear touches land
on the main screen. The root daemon opens the device and calls `EVIOCGRAB` (through the hidden
`android.system.Os.ioctlInt`), which gives it exclusive access while it runs. Closing the file descriptor
releases the grab, so if the daemon dies the old behaviour returns until it restarts.

The stock app did not read the device itself: it drew a `TYPE_DISPLAY_OVERLAY` (2026) window and relied on
the stock firmware to route the touches; that path does not exist on the GSI.

## Side button (programmable key)

| Item | Value |
|---|---|
| Device | `mtk-kpd`, `/dev/input/event1` |
| Code | `KEY_F1` (linux 59), Android `KEYCODE_F1` through `Generic.kl` |
| Events | normal down/up; a long press is simply a long down→up |

The same device also carries volume-up and the camera key, so it is **not** grabbed: the daemon only reads
it. The daemon takes a 2 s kernel wakelock (`/sys/power/wake_lock`) on each key or touch event so the app
can react before the kernel suspends again.

`ff_key` (`/dev/input/event5`) reported a single `KEY_F11` press during discovery; its source is unknown
(possibly the fingerprint sensor's gesture key). It is not used.

## Sensors

From `dumpsys sensorservice`:

| Type | Sensor |
|---|---|
| accelerometer | `icm4n607_acc` (TDK) |
| magnetometer | `mmc5603` (MEMSIC) |
| gyroscope | `icm4n607_gyro` |
| light | `stk3a5x_als` (Sensortek) |
| proximity | `stk3a5x_ps` (front) |
| step counter / detector | `step_count`, `step_detect` (MTK, need `ACTIVITY_RECOGNITION`) |
| others | significant motion, tilt, wake gesture, device orientation, rotation vectors (AOSP fusion) |

The stock `TurnSensorListener` is a plain accelerometer check: face down when z is between −7 and −12 m/s²
and x²+y² < 60 after having been face up. This app uses the same idea (z < −7, |xy| < 4, held 0.6 s),
sampled at 5 Hz with 1 s batching and only while the panel is off.

## Cameras

`dumpsys media.camera` lists four HAL devices, all exposed to apps on this GSI:

| ID | Facing | Sensor array | Flash | Likely role |
|---|---|---|---|---|
| 0 | back | 9248×6944 (64 MP) | yes | main |
| 1 | front | 2592×1940 | no | selfie |
| 2 | back | 5184×3880 (20 MP) | no | night vision |
| 3 | back | small | no | macro |

The rear viewfinder uses CameraX `ImageAnalysis` at about 640×480 RGBA, rotated and centre-cropped to
360×360; the panel (15 fps) is the bottleneck, not the camera. The switch button cycles through all IDs.

Using the camera from a background service needs the `camera` foreground-service type, which Android 14
refuses when the service is started from the background. The app asks the root daemon to run
`am start-foreground-service`; a root caller is exempt from that restriction, so the service can add the
camera (and microphone) type at any time, including on the lock screen.

## Infrared illuminator (night vision)

Driver `irled-gpio` (`/vendor_dlkm/lib/modules/irled-gpio.ko`, author `yft-drv`), device-tree node
`irled_gpio` with pin states `ir1..ir4` × `high` / `low` / `pwm`, PWM through `mtk-pwm`.

| File | Writes | Effect |
|---|---|---|
| `/sys/bus/platform/drivers/irled-gpio/yft_irled_ctl` | `on` / `off` | IR on: pin 0 high + PWM enabled (26 MHz clock, duty ≈ 11395/39385); reads back `on`/`off` |
| `/sys/bus/platform/drivers/irled-gpio/irmotor` | `gpio <pin 0..3> <0\|1>` | sets one pin (despite the name, not an IR-cut motor command in this build) |
| same | `pwm <pin> <freq> <0\|1>` | PWM on one pin |

Found by reading the module's strings and disassembling `store_irmotor_value` (NDK `llvm-objdump`):
it compares the input with `gpio` (then `sscanf "%d %d"`) or `pwm` (then `"%d %d %d"`); pin numbers
must be below 4. Vendor init makes the files world-writable, but SELinux (`sysfs_yft_file`) still blocks
apps, so the night-vision app writes them from a root shell that switches the LEDs off when the app dies.

The night camera is HAL camera **2** (20 MP, back). There is no separate IR-cut control exposed.

## Rendering approach

Two options were considered:

1. **Own retained-mode UI on a `Canvas`** (chosen).
2. Real Android views in a `VirtualDisplay` + `Presentation`, with rear touches injected into that display.

Option 1 won because the panel is slow (15 fps) and round, so a small custom toolkit with dirty-frame
rendering is enough and costs nothing when idle; touches are delivered straight from the daemon without
`INJECT_EVENTS`, input-injection latency or focus problems; and nothing depends on the lock screen or the
window manager, so everything keeps working while the phone is locked or the main screen is off.

## Stock app notes (`com.yft.miniscreen`)

- Renders a `FrameLayout` into a 360×360 bitmap and calls the HAL from a handler loop.
- Settings in `Settings.Global`: `subscreen_screen_timeout` (default 30 s), a "go home" timeout (15 s),
  `double_touch_key`, `subscreen_backlight_level`, `subscreen_with_mainscreen`, `subscreen_noti_tip_enable`.
- Music buttons inject `KEYCODE_MEDIA_PLAY_PAUSE` / `NEXT` / `PREVIOUS` with `InputManager.injectInputEvent`.
- Camera: `TextureView` preview + `ImageReader` JPEG at 720×720 (`subscreen_*_photo_width/height`).
- Test mode: `persist.sys.yft_tinylcd_testmode`.

## Emergency: panel off without the app

```
adb shell su -c 'am force-stop com.lello.wp21rear; CLASSPATH=$(pm path com.lello.wp21rear | cut -d: -f2) app_process /system/bin com.lello.wp21rear.daemon.RearDaemon off'
```

(`tools/rear.sh off` runs the same.)

## Open questions

- Whether `hyn_ts` reports touches while the panel is powered off (needed for double-tap-to-wake).
- Native display orientation and touch orientation relative to the image: set with the calibration screen.
- One unexplained kernel-panic reboot happened during discovery (about 00:10 on 2026-10-15), with no HAL
  or daemon activity at that moment.

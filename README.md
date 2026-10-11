# TimeLapseCamera (fork)

Android time-lapse camera app: records in the background with the screen off,
as MP4 video or individual JPEG stills, in various resolutions and from any
camera, with optional shutter-sound muting, scheduled starts, and no fixed
recording limit (stops on low battery or low disk space).

## What this fork adds

### 1. CCTV-style timestamp overlay on stills

When enabled (Settings → Timestamp overlay, on by default), every captured
still gets a date/time stamp burned into the frame, CCTV-style. Options:

- On/off (default on)
- Position: top-left / top-right / bottom-left / bottom-right (default) / center
- Size: small / medium (default) / large
- Content: date+time (default) / date / time / date+time+frame counter
- Format: `SimpleDateFormat` presets (default `yyyy-MM-dd HH:mm:ss`) or custom pattern (invalid falls back to default)
- Text color: white (default) / yellow / green / cyan
- Background shading: transparent / 40% / 60% (default) / 80% black pill

Scope: the stamp covers `IMAGE_TIME_LAPSE` captures ("Time Lapsed Images
(JPG)"), including the power-saving variant. Direct `VIDEO` and
`VIDEO_TIME_LAPSE` modes are NOT stamped (frames go straight to
`MediaRecorder` with no point to draw text).

### 2. GitHub Action that builds/releases the debug APK

`.github/workflows/release.yml` runs `./gradlew assembleDebug` on pushes to
`master`/`main` (plus manual runs) and uploads the APK as a `debug-apk`
artifact; pushing a `v*` tag publishes it as a GitHub Release asset.

## Credit

This fork is based on [woheller69/TimeLapseCamera](https://github.com/woheller69/TimeLapseCamera).

## License

GPL-3.0 — see LICENSE (untouched).

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
- Two-line stamp: the `2026-01-31 + 14:05:22 (two lines)` preset stacks date over time (extra lines are capped at 3)
- Text color: white (default) / yellow / green / cyan
- Background shading: transparent / 40% / 60% (default) / 80% black pill

Scope: the stamp covers `IMAGE_TIME_LAPSE` captures ("Time Lapsed Images
(JPG)"), including the power-saving variant. `VIDEO` and
`VIDEO_TIME_LAPSE` MP4s are stamped too when "Stamp videos too" is on
(default): after recording stops, each file is transcoded in the
background with the running date/time burned into every frame, using the
same appearance settings above. Transcoding takes a while on long clips
and re-encodes the video (audio is copied untouched); the original file
is kept if stamping fails.

### 2. GitHub Action that builds/releases the debug APK

`.github/workflows/release.yml` runs `./gradlew assembleDebug` on pushes to
`master`/`main` (plus manual runs) and uploads the APK as a `debug-apk`
artifact; pushing a `v*` tag publishes it as a GitHub Release asset.

## Credit

This fork is based on [woheller69/TimeLapseCamera](https://github.com/woheller69/TimeLapseCamera).

## License

GPL-3.0 — see LICENSE (untouched).

# ThermaSpace / FLIR One Viewer — HTTP API reference

Both Quest apps run a small LAN-only HTTP server for viewing, tuning, and
exporting. Nothing leaves your network; the server lives and dies with the app
(foreground only). Find the headset IP in the in-app guide (`?`), or via
`adb shell ip route`.

| App | Port |
|---|---|
| **ThermaSpace** (quest3-spatial, VR) | **8081** |
| FLIR One Viewer (quest3-app, 2D panel) | 8080 |

Base URL below: `http://<quest-ip>:8081` (substitute 8080 for the 2D app).

## Viewing

| Endpoint | Returns | Notes |
|---|---|---|
| `GET /` | HTML page | live stream + a link bar to /export, /config, /status (and /log, /raw in debug builds) |
| `GET /stream` | `multipart/x-mixed-replace` MJPEG | the rendered thermal view (palette, MSX, scale bar); also playable via `mac_viewer.py <ip> 8081` or VLC |
| `GET /status` | JSON | camera telemetry passthrough — `shutterState`, `ffcState`, `shutterTemperature` (Kelvin), timestamps |
| `GET /log` | text | rolling app/driver diagnostics (last 300 lines): USB init, MRUK scene load, capture decisions |
| `GET /raw` | binary | latest thermal USB payload verbatim (10332 B VoSPI: 63 lines × 82 u16 = `[line id][crc][80 px]`, lines 60–62 telemetry) |
| `GET /stats` | JSON | local feature-usage counters + session totals (debug builds only; see Analytics.kt) |

## Imaging configuration — `GET /config`

Returns current settings as JSON; any query parameter applies immediately
(and returns the updated JSON). Example:
`curl "http://<ip>:8081/config?pal=1&span=15,35&unit=f"`

| Param | Values | Meaning |
|---|---|---|
| `ema` | 0.05–1.0 | temporal denoise strength (1 = off; default 0.35) |
| `msx` | 0–1 | visible-camera edge overlay strength (0 = off; default 0.55) |
| `mscale` | 0.5–2 | MSX alignment: scale |
| `mdx`, `mdy` | px | MSX alignment: offset (output px) |
| `emis` | 0.1–1.0 | emissivity for temperature conversion (default 0.95) |
| `pal` | 0–3 | palette: 0 iron, 1 white-hot, 2 black-hot, 3 rainbow |
| `iso` | 0–2 | isotherm: 0 off, 1 hottest-fraction, 2 coldest-fraction |
| `isofrac` | 0.02–0.5 | isotherm fraction (default 0.15) |
| `rot` | 0/90/180/270 | camera mounting rotation |
| `span` | `lo,hi` or `auto` | lock the color scale to a fixed °C range / return to auto |
| `unit` | `f` or `c` | display unit (all API/CSV values stay °C) |

Read-only fields in the JSON: `calibrated`, `rawOffset` (FFC shutter
calibration state).

The 2D app (8080) supports the original subset: `ema msx mscale mdx mdy emis`.

(`/regions` was removed in v9.0 along with watch regions — placed captures
now have a per-capture 🔒 live-lock icon in-headset instead.)

## Export

| Endpoint | Returns |
|---|---|
| `GET /export` | `thermaspace-export.zip` — the whole gallery (works even after Clear; gated only on the gallery being non-empty) |

ZIP layout:
```
heatmap.json              metadata: per capture id, name, note, palette,
                          capturedAt (ms)
gallery/<id>.png          capture as displayed (crosshair, scale bar, MSX)
gallery/<id>.raw          radiometric: 8B header (w,h LE i32) + LE u16
gallery/<id>.photo.jpg    real-world context photo (visible camera)
```
Raw→temperature: `raw*4 → Planck` (constants in `flirone.py` /
`FlirFrame`); ~40 raw counts ≈ 1 °C on this unit.

## Access control
- **Debug builds** (what `install.sh` installs): server always on, no key,
  all endpoints — the development workflow.
- **Release builds**: server responds 403 until the user enables **📡 Share**
  (Tools menu). Every request must then carry the per-session key shown in the
  headset status line (`?key=1234`, works appended to any endpoint). Sharing
  auto-disables when the app pauses. `/log` and `/raw` do not exist in
  release builds.

## Caveats
- LAN only; nothing is ever sent off-network. The server only responds while
  the app is foregrounded on the headset (Horizon OS suspends background
  apps) and pauses when the headset sleeps.
- `/export` returns `nothing to export yet` (text) before any capture.
- `/stream` is the only consumer of JPEG encoding — the app skips encoding
  entirely while no stream client is connected (battery).

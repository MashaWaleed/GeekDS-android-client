# GeekDS Android Client

Android TV digital signage client for the [GeekDS CMS](https://github.com/MashaWaleed/GeekDS). Designed to run on ~400 Android TV boxes, playing scheduled media playlists with full offline capability.

---

## 🏗 Architecture

```
┌──────────────────────────────────────────────────────────┐
│  MainActivity.kt (single-activity architecture)          │
│                                                          │
│  ┌──────────────┐  ┌──────────────┐  ┌───────────────┐  │
│  │ Registration │  │  Heartbeat   │  │ Schedule      │  │
│  │ (UUID-based) │  │  (every 10s) │  │ Enforcement   │  │
│  └──────────────┘  └──────────────┘  │ (every 3s)    │  │
│                                      └───────────────┘  │
│  ┌──────────────┐  ┌──────────────┐  ┌───────────────┐  │
│  │ Playback     │  │  Screenshot  │  │ OTA Update    │  │
│  │ (ExoPlayer)  │  │  Capture     │  │ (APK install) │  │
│  └──────────────┘  └──────────────┘  └───────────────┘  │
│                                                          │
│  ┌────────────────────────────────────────────────────┐  │
│  │  Local Storage (SharedPreferences)                 │  │
│  │  - Schedules, Playlists (Gson serialized)          │  │
│  │  - Device ID, UUID, Name                          │  │
│  │  - Playlist version timestamps                     │  │
│  └────────────────────────────────────────────────────┘  │
│                                                          │
│  ┌────────────────────────────────────────────────────┐  │
│  │  Media Cache (getExternalFilesDir)                 │  │
│  │  - Downloaded media files: {id}-{filename}         │  │
│  │  - config.json (external config)                   │  │
│  └────────────────────────────────────────────────────┘  │
└──────────────────────────────────────────────────────────┘
```

## 📁 Source Structure

All code lives in a single-activity architecture under `app/src/main/java/com/example/geekds/`:

| File | Purpose |
|---|---|
| `MainActivity.kt` | Entry point, state machine, initialization |
| `MainActivityRegistration.kt` | UUID generation, registration code flow, polling |
| `MainActivityHeartbeat.kt` | 10s heartbeat loop, version tracking, screenshot command processing |
| `MainActivitySchedule.kt` | Multi-schedule enforcement (every 3s), time/day/validity checks |
| `MainActivityScheduleApi.kt` | Fetch & cache schedules/playlists from server |
| `MainActivityPlayback.kt` | ExoPlayer setup, media download, playlist playback |
| `MainActivityScreenshot.kt` | Screen capture (4 fallback methods), upload to server |
| `MainActivityConnection.kt` | Network monitoring, wake lock, circuit breaker, health probes |
| `MainActivityDevice.kt` | Clear registration, stop activities, cleanup |
| `MainActivityUi.kt` | Standby image display, UI management |
| `MainActivityUpdate.kt` | OTA APK download and silent/regular install |
| `GeekDsConstants.kt` | Constants (TAG, defaults, fallback URLs) |
| `AppState.kt` | State enum: REGISTERING, IDLE, SYNCING, ERROR |
| `model/Models.kt` | Data classes: Schedule, Playlist, MediaFile |
| `network/ApiClient.kt` | OkHttp client singleton (30s connect, 60s read timeouts) |
| `data/LocalStorage.kt` | Gson-based SharedPreferences persistence |
| `util/DeviceIdentity.kt` | Hardware UUID generation (Android ID → SHA-1 → UUID v5) |
| `util/NetworkUtils.kt` | Local IP address detection |
| `config/AppConfig.kt` | External JSON config loader (config.json) |

## 🔄 Lifecycle

### 1. Startup
- Loads external `config.json` (device_name, server_mdns)
- Generates/loads hardware-based UUID
- Checks if device is already registered (device_id in prefs)

### 2. Registration
- If unregistered: shows registration dialog, polls server for UUID match
- Dashboard admin enters 6-digit code to link device

### 3. Heartbeat Loop (every 10s)
Sends `PATCH /api/devices/:id/heartbeat` with:
- Playback state, schedule/playlist version counters
- Current media filename & position
- App version, IP, UUID

Receives:
- Schedule/playlist change flags
- Active playlist ID
- Screenshot commands
- Update request flag

### 4. Schedule Enforcement (every 3s)
- Loads cached schedules from SharedPreferences
- Checks current UTC day & time against all schedules
- Switches playlists when schedule transitions occur
- Works fully offline using cached data

### 5. Playback
- Downloads media files to `getExternalFilesDir`
- Uses ExoPlayer (Media3) for video playback
- Shows standby image when no schedule is active
- Pre-caches playlists for all assigned schedules

### 6. Connection Resilience
- Network monitoring via ConnectivityManager
- Wake lock to prevent sleep during playback
- Circuit breaker: pauses heartbeats after 12 consecutive failures
- Health probes: periodic `/api/health` checks when paused
- Exponential backoff during registration polling

## 📸 Screenshot Handling

The Android app supports **device-side screenshot capture** triggered by heartbeat commands:

1. Dashboard requests screenshot → server creates `screenshot_requests` row
2. Next heartbeat response includes `commands: [{ type: "screenshot_request" }]`
3. App calls `takeScreenshot()` which tries 4 methods in order:
   - Extract frame from stored TextureView reference
   - Extract frame from PlayerView's TextureView surface
   - Recursive search for TextureView in view hierarchy
   - MediaMetadataRetriever frame extraction from source file
4. Captured bitmap is scaled to 1280×720, compressed to JPEG (85% quality)
5. Uploaded via `POST /api/devices/:id/screenshot/upload`

> **Note:** The server also supports server-side screenshot generation via ffmpeg (`POST /api/devices/:id/screenshot`), which extracts frames directly from stored media files. Both mechanisms coexist.

## 🔄 OTA Updates

1. Server sets `update_requested = true` on device (via dashboard)
2. Heartbeat response includes `update_requested: true`
3. App downloads latest APK from `GET /api/devices/apk/latest`
4. Attempts silent install (PackageInstaller API), falls back to regular install
5. After update, heartbeat sends new `app_version` → server auto-clears flag

## 🛠️ Building

```bash
./gradlew assembleDebug    # Debug build
./gradlew assembleRelease  # Release build
```

## ⚙️ External Configuration

Place a `config.json` in the app's external files directory:
```json
{
  "device_name": "Lobby-TV-01",
  "server_mdns": "192.168.1.11:5000"
}
```

## 📦 Dependencies

- **ExoPlayer (Media3)** — Video playback
- **OkHttp** — HTTP client
- **Gson** — JSON serialization
- **Kotlin Coroutines** — Async operations
- **AndroidX** — Core Android support libraries

## 🖥️ Server Compatibility

Requires [GeekDS CMS](https://github.com/MashaWaleed/GeekDS) backend v0.1.0+.
Communicates via REST API on port 5000.

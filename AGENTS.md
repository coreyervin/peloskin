# AGENTS.md

Guidance for AI coding agents and contributors working on PeloSkin.

## What this project is, and isn't

PeloSkin is a metrics skin for a Peloton bike's own tablet. It shows Peloton-style ride metrics
over whatever is playing, and keeps each rider's profile (name, picture, FTP, rides, records)
itself. It's deliberately agnostic about the class app: Peloton App+, YouTube, Apple Fitness+ on
another screen, a movie. Tracking shouldn't depend on which service the rider uses.

It assumes the bike's tablet is already unlocked with OpenPelo or a similar sideloading tool.

Out of scope, on purpose:

- **Broadcasting metrics to other devices** (Bluetooth FTMS, ANT+, DirCon, Zwift, watches,
  Strava). That's [Grupetto](https://github.com/doudar/grupetto)'s job. Don't add it here.
- **Depending on Grupetto.** PeloSkin reads the bike itself and must work with Grupetto
  uninstalled. Don't add a Grupetto data source or fallback.

## Architecture

A light clean architecture, one Gradle module. Dependencies only point inward:
`ui` → `data` → `domain`.

| Layer | Package | Rules |
|---|---|---|
| Domain | `app.peloskin.domain` | Plain Kotlin only: no `android.*`, no `org.json`, no imports from `data` or `ui`. Ride tracking, power zones, FTP rules, the speed curve, sample cleanup, settings model. Everything here is unit-tested. |
| Data | `app.peloskin.data.bike`, `.data.bike.<model>`, `.data.store` | Talks to the outside world: Peloton's sensor software, files, backup. May use Android and domain types. |
| UI | `app.peloskin.ui.overlay`, `.ui.summary`, `.ui.settings` | Compose overlay windows and the settings screen. May use data and domain. |

There's no DI framework and no per-action use-case classes; the app is small enough that
they'd be ceremony. Keep it that way unless it grows a lot.

## Riders, rides and records

- `Settings.profiles` holds each rider (`Profile`): FTP, rides (newest first, max 100) and
  `Records`. Layout and units are shared. `Settings.active` is the rider on the bike.
- A finished ride is recorded for whoever is active when it ends (`SettingsStore.recordRide`),
  and its time in zones uses that rider's FTP: `RideTracker` keeps one entry per second (watts,
  pedaled) and works out zones and the graph when the ride ends. Adding a rider doesn't
  switch to them, so a ride in progress keeps its rider.
  That also updates records (`PersonalRecords.apply`), may raise FTP, saves the ride's output
  graph (`RideDetail`, one JSON file per ride under `rides/<profileId>/`), and sets
  `lastFinished` for the summary card.
- Records: best average watts over 5 s, 1, 5 and 20 min, and best total kJ per class length
  (a ride within 15% of 5/10/15/20/30/45/60/75/90/120 min). Only records that beat an earlier
  one are announced; a rider's first ride doesn't fill the screen with "PR"s.
- The settings file is version 2. Version 1 (single rider, FTP and rides at the top level) is
  read as one profile. Keep `SettingsJson` able to read every older version.
- `Profile.lifetime` totals every saved ride, so it keeps counting after old rides drop out of
  `rides`. Files saved before it existed start it from the kept rides; a backup merge takes the
  larger of each total, since the two copies share rides.
- Demo rides show a summary but are never recorded.
- Rider pictures are files, not settings: `RiderPhotos` keeps a 320 px square JPEG per rider id in
  `files/photos/` and `Documents/PeloSkin/photos/`, syncs the two once the backup is readable,
  and prunes pictures of removed riders. The skin, settings and summary card draw them with
  `RiderAvatar`, which falls back to initials on the rider's color.
- The settings screen is ordered by how often things are used: skin on/off, *Who's riding*, the
  selected rider's card (FTP, records, rides), then skin settings. Rider-scoped controls belong
  in the rider card; shared ones in *Skin settings*.
- A rider ends a ride by holding the skin (`tapOrHold` in `SkinUi`) or with *End ride* in
  settings (`SkinService.requestEndRide`). Both call `RideTracker.finish(byRider = true)`,
  which waits for pedaling to stop before a new ride can start.

## Heart rate

- `data/hr/HeartRateLink` talks to Bluetooth LE heart rate monitors: the standard Heart Rate
  service `0x180D`, Heart Rate Measurement `0x2A37` notifications (enabled through descriptor
  `0x2902`), parsed by the domain `HeartRateMeasurement` (flags bit 0: 8- or 16-bit value).
- Each `Profile` has its own `heartRateMonitor` (address and name) and `maxHr`. `SkinService`
  calls `HeartRateLink.follow()` with the active rider's monitor whenever the rider changes, so
  switching rider drops one monitor and looks for the other. It finds the monitor with a scan
  filtered to its address, then connects; without scan permission it falls back to
  `connectGatt(autoConnect = true)`. It reconnects on its own and clears the reading after 5 s
  without one.
- `RideTracker.onHeartRate()` records one value per second alongside watts (0 = no reading).
  Rides keep average and max heart rate and time in Peloton's 5 heart rate zones
  (`HeartRateZones`: 65/75/85/95% of max); `RideDetail.heartRate` keeps every second for the
  graph and for export.
- Android 11 (the Gen 1 tablet) needs `ACCESS_FINE_LOCATION`, `ACCESS_BACKGROUND_LOCATION` (the
  skin scans from a background service) and Location turned on to scan. Android 12+ uses
  `BLUETOOTH_SCAN` and `BLUETOOTH_CONNECT`.
- On non-Peloton devices the pairing list includes a demo monitor with a made-up heart rate.

## Bike models

Code is organized by bike model under `data/bike/`:

- `BikeModel.detect()` reads Peloton's `Settings.Global["peloton_platform"]`
  (`v1` = Gen 1 Bike, `titan` = Bike+, `prism` = Tread, `caesar` = Row).
- `BikeModel.sources()` lists the `SensorSource`s to try, in order, for each model.
- `BikeFeed` tries them in order, reconnects when one goes away, reports `Unsupported` for a
  model with no sources, and runs every reading through the domain `ResistanceFilter`.
- Sources must not block the main thread. `Gen1SensorServiceSource` registers on its own
  thread and unregisters with `FLAG_ONEWAY`, because the Peloton service can hang.

To add a model (Bike+ is next):

1. Create `data/bike/<model>/` with one or more `SensorSource` implementations. Each one turns
   raw readings into `BikeSample`s with `BikeMath.sample(power, cadence, resistance)`.
2. Return them from `BikeModel.sources()` for that model.
3. Add its package to `<queries>` in `AndroidManifest.xml` (Android 11 package visibility).
4. Test on real hardware. The emulator has no Peloton services.

### Gen 1 sensor protocols

Both are Peloton system apps on the Gen 1 tablet. Keep them both: if one stops answering after a
Peloton update, the other may still work.

**Sensor service** (`Gen1SensorServiceSource`, preferred). Package `com.onepeloton.affernetservice`.
Bind with action `com.onepeloton.affernetservice.IV1Interface` and the package set.

- Raw `Binder.transact` against interface descriptor `com.onepeloton.affernetservice.IV1Interface`.
- Code `1` registers a callback; code `2` unregisters it. Both take
  `writeStrongBinder(callback)` and `writeString(clientName)`.
- The service calls back with descriptor `com.onepeloton.affernetservice.IV1Callback`:
  - code `1`: sensor data. `readInt()` has-data flag, then `readLong()` rpm,
    `readLong()` power in centiwatts, `readLong()` stepper position, `readLong()` load cell,
    `readInt()` current resistance (%), `readInt()` target resistance.
  - codes `2` and `3`: errors and calibration status, which we ignore.

**Sensor app** (`Gen1SensorAppSource`, fallback). Package `com.peloton.service.SensorData`.
Bind with action `android.intent.action.peloton.SensorData`, then talk over a `Messenger`.

- Send `what` `1` (cadence), `2` (power) or `3` (resistance), each with a `requestId` string
  extra and `replyTo` set. Each request starts repeating updates.
- Replies carry a float in `data` (power in centiwatts), a `time`, and a raw
  `responseHexString`, which is `TIME_OUT` on a sensor timeout.

The manifest declares `onepeloton.permission.ACCESS_SENSOR_SERVICE`.

## Build and test

```sh
export JAVA_HOME=/path/to/jdk17
./gradlew testDebugUnitTest assembleRelease
```

- Unit tests live in `app/src/test`. Add tests for any domain change. `BikeFeed` is tested
  with fake `SensorSource`s and `kotlinx-coroutines-test`.
- On an emulator or other non-Peloton device, the settings screen has a **Demo ride** switch
  that feeds a made-up interval ride. Use it for UI work and screenshots. Demo rides are kept
  out of ride history.
- The launcher entry is an `activity-alias` named `.MainActivity` pointing at
  `ui.settings.MainActivity`. Keep that name so home-screen shortcuts survive updates.
- On the bike, over adb: install, then
  `adb shell appops set app.peloskin SYSTEM_ALERT_WINDOW allow` and
  `adb shell appops set app.peloskin MANAGE_EXTERNAL_STORAGE allow`.
- The Peloton tablet's system fonts are missing some symbols (▲ ▼ ⌃ ⌄ showed as boxes).
  Draw icons with `Canvas` instead of typing them, and check any new non-ASCII character
  against the bundled Figtree font.

## Releases

- Bump `versionCode` and `versionName` in `app/build.gradle`.
- Sign with the maintainer's key. `peloskin.keystore` and `keystore.properties` are
  gitignored and must never be committed.
- Publish with `gh release create vX.Y.Z app-release.apk`.

## Commits

Commits are authored by the maintainer only. Don't add AI co-author trailers or
"Generated with" lines to commits, PRs or releases.

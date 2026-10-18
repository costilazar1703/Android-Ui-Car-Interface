# E92 Launcher: Custom Android Interface for a BMW E92

The factory navigation on my BMW E92 stopped working, and the aftermarket Android units I tried had slow, clunky interfaces. So I built my own launcher: fast, made to feel like part of the car, showing live car data and controlled with the physical iDrive knob.

It is an Android 8.1 (API 27) launcher for a 10.25" head unit with a 1280x480 screen.

## Tech stack
Written in **Kotlin** with classic Views, `ConstraintLayout` and `ViewBinding`. To keep it fast on limited car hardware, it does not use Jetpack Compose or a dependency injection framework, and it is built as a single activity.

## Main features

### 1. iDrive control (`FocusEngine`)
The whole UI works with the rotary controller, so no touch is needed while driving.
- **Focus graph:** custom routing for the Up/Down/Left/Right inputs.
- **Rotary accelerator:** a fast turn of the knob jumps 2 or 5 items at a time and resets when the direction changes.
- **HardKeyRouter:** maps the steering wheel and console buttons to actions, with a "Learn Key" mode.

### 2. Live car data and CAN bus (`CanDataSource`)
- Reads car data from CAN bus broadcasts and falls back to GPS.
- `VehicleRepository` limits updates to 10 Hz so the UI is not flooded.
- Custom dashboard: a `GaugeView` drawn directly on the `Canvas`, with a 30 fps needle, 10 Hz numbers and no allocations in `onDraw`, so the garbage collector does not cause stutters.

### 3. Visuals and media
- **ConnectorView:** a curved line with a glow effect between the rotary menu and the selected row, made with alpha layer-lists instead of expensive elevation.
- **MediaHub:** uses `MediaSessionManager` to get the song info, album art and playback controls from any app.
- **App drawer:** a horizontal grid that loads icons off the main thread and caches them in an `LruCache`.

## Architecture
Hardware logic is kept separate from the UI. The screens only react to `LauncherAction` and `VehicleState`.

- **`E92Application`** (service locator): holds `VehicleRepository`, `HardKeyRouter`, `MediaHub` and `ConnectivityMonitor`.
- **`HomeActivity`:** the single activity, hosting the `ScreenStack`.
- **`FocusEngine`:** decides what is focused; the pages follow the focus, so pagination needs no extra state.

## Build and install
The app targets an emulator (AVD) or a real unit at **1280x480**.

1. Clone the repository and open it in Android Studio.
2. Build the release APK (it is signed with the included debug keystore, so it installs directly on the unit):
   ```bash
   ./gradlew assembleRelease
   ```

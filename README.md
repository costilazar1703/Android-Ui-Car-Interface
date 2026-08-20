# E92 Launcher: Custom BMW Android Interface 🚗💻

**The Story:** It all started when the factory navigation system on my BMW E92 gave out. Instead of settling for a generic, clunky aftermarket Android unit with a frustrating UI, I decided to build my own. The goal was simple but ambitious: create a lightning-fast, custom Android launcher that feels native to the car, reads real telemetry data, and integrates perfectly with the physical iDrive controller. 

This repository contains the result: a custom Android 8.1 (API 27) launcher built specifically for a 10.25" (1280x480) head unit.

## 🚀 Core Philosophy & Tech Stack
Written entirely in **Kotlin** using traditional Views, `ConstraintLayout`, and `ViewBinding`. To maximize performance on limited automotive hardware, this project explicitly avoids Jetpack Compose and heavy Dependency Injection frameworks, relying instead on a highly optimized Single-Activity architecture.

## ⚙️ Key Features

### 1. Physical iDrive Integration (`FocusEngine`)
The UI is completely built around physical rotary control, bypassing the need for touch inputs while driving.
* **Explicit Focus Graph:** Custom routing for Up/Down/Left/Right hardware inputs.
* **Rotary Accelerator:** Detects fast wheel rotation to jump 2 or 5 elements at a time, resetting instantly on direction change.
* **HardKeyRouter:** Maps physical steering wheel and console buttons to actions (includes a "Learn Key" mode).

### 2. Live Telemetry & CAN Bus (`CanDataSource`)
* **Data Fusion:** Reads and merges data from CAN Bus broadcasts and GPS fallback.
* **VehicleRepository:** Processes raw car data with a strict 10 Hz throttle to prevent UI flooding.
* **Custom Dashboard:** A bespoke `GaugeView` drawn directly on the `Canvas`. Features a 30fps needle and 10Hz numeric updates with **zero allocations in `onDraw`** to guarantee smooth rendering without Garbage Collection stutters.

### 3. Native Visuals & Media
* **Immersive ConnectorView:** A cubic curve with a custom glow effect connecting the 3D rotary menu to the selected row, achieved through progressive alpha layer-lists rather than expensive hardware elevation.
* **MediaHub:** Deep integration with `MediaSessionManager` for fetching metadata, album art, and transport controls across all apps.
* **App Drawer:** A horizontal grid with off-thread icon pre-loading and an `LruCache` for instant access.

## 🏗️ Architecture Overview

The system strictly decouples hardware protocols from the UI. The screens only react to `LauncherAction` and `VehicleState`.

*   **`E92Application` (Service Locator):** Manages `VehicleRepository`, `HardKeyRouter`, `MediaHub`, and `ConnectivityMonitor`.
*   **`HomeActivity`:** The single activity hosting the `ScreenStack`.
*   **`FocusEngine`:** The source of truth for navigation. The UI pages follow the focus, eliminating complex state management for pagination.

## 🛠️ Build & Installation

The project targets an AVD or physical unit running at **1280x480**.

1. Clone the repository and open it in Android Studio.
2. Build the release APK using the provided debug keystore for seamless physical installation:
   ```bash
   ./gradlew assembleRelease

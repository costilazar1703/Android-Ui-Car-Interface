# Testing without the car (Android emulator)

Every screen of the launcher can be tested on the Android emulator. The launcher detects the emulator and starts a built-in driving simulator (speed, rpm, boost, temperatures). Everything else that comes from the car — gear lever, Drive Sport, manual mode, traction control, reverse with parking sensors, Bluetooth music and calls — is sent from the computer with [`scripts/sim.ps1`](../scripts/sim.ps1).

All commands below were run on the emulator described in step 1.

## 1. Create the emulator

In Android Studio, open **Device Manager → Create Virtual Device → New Hardware Profile**:

| Setting | Value |
|---|---|
| Resolution | 1280 x 480 |
| Screen size | 10.25" |
| RAM | 2048 MB |
| System image | Android 8.1 (API 27), **Google APIs**, x86 |

Use a "Google APIs" image, not "Google Play": only the first one allows `adb root`, which the script uses to read the diagnostics key by itself.

## 2. Build, install and start

```powershell
./gradlew assembleRelease
adb install -r app/build/outputs/apk/release/app-release.apk
adb shell am start -n ro.e92.launcher/.ui.HomeActivity
```

Press the emulator's Home button and choose **E92** as the home app if you want it to behave like on the head unit.

## 3. Run the simulator

```powershell
.\scripts\sim.ps1           # list of all commands
.\scripts\sim.ps1 demo      # P -> D -> Sport -> M1..M3 -> D -> P, then Drift Mode on and off
```

The script finds `adb` in the default Android SDK folder (or on the PATH).

### Gear lever and driving modes

These go through the launcher's built-in diagnostics server (port 8092, forwarded by the script with `adb forward`). They feed the same code path as the CAN frames in the car.

| Command | What you should see |
|---|---|
| `.\scripts\sim.ps1 gear P` | Home strip: TRANSMISSION / PARKING / P |
| `.\scripts\sim.ps1 gear D` | Comfort card, then DRIVING MODE / COMFORT / D |
| `.\scripts\sim.ps1 sport on` | Dynamic Sport card with the rev counter, then SPORT / DS |
| `.\scripts\sim.ps1 manual 1` … `manual 6` | Manual card, then TRANSMISSION / MANUAL / M1…M6 |
| `.\scripts\sim.ps1 manual off` / `sport off` | back to Comfort |
| `.\scripts\sim.ps1 gear N` | Neutral (shown after the lever stays in N for a moment) |
| `.\scripts\sim.ps1 drift on` | full-screen Drift Mode scene, DRIFT MODE badge above the car |
| `.\scripts\sim.ps1 drift off` | full-screen Normal Mode scene |
| `.\scripts\sim.ps1 scene sport` | plays one animation directly: `drift`, `normal`, `sport`, `drive`, `park`, `neutral`, `manual` |

Like in the car, the **first value after the launcher starts is only a starting point** and plays no animation (the car sends its current state when the engine starts, and that should not look like a change). Send a different value after it to see the animation. `demo` does this for you.

### Reverse

| Command | What you should see |
|---|---|
| `.\scripts\sim.ps1 reverse` | reverse screen, rear sensors at 150, 40, 18 and 60 cm |
| `.\scripts\sim.ps1 reverse "120,60,30,90" -6000` | other distances (cm, left to right) and the guide lines turned by the steering wheel (-10400 … 10400 = full lock) |
| `.\scripts\sim.ps1 forward` | leaves reverse |

### Car data

On the emulator the Dashboard (DASH) already moves by itself: the built-in simulator drives a loop of accelerating, cruising and braking. The commands below send the head unit's own car data packet instead, with fixed values.

| Command | Values |
|---|---|
| `.\scripts\sim.ps1 data-drive` | 87 km/h, 2300 rpm, 41 l, 512 km range, 14.5 °C outside |
| `.\scripts\sim.ps1 data-park` | stopped, engine idling |
| `.\scripts\sim.ps1 lowfuel` | 6 l, 45 km range, 2.5 °C |
| `.\scripts\sim.ps1 handbrake` | handbrake pulled while moving |

### Phone and media

| Command | What you should see |
|---|---|
| `.\scripts\sim.ps1 music "Demo Song"` | Home: "Demo Song" playing from Bluetooth, phone connected |
| `.\scripts\sim.ps1 call` | incoming call from "Demo Contact" with Answer / Decline |
| `.\scripts\sim.ps1 talk` | call in progress |
| `.\scripts\sim.ps1 hangup` | call ended |

These are the broadcasts of the head unit's Bluetooth app. If that app (`com.szchoiceway.btsuite`) is itself installed on the emulator, the launcher connects to it and its real state ("no phone") replaces the simulated one; on a normal emulator it is not installed.

Music from any app installed on the emulator also shows on the Home media widget once the launcher has notification access (`Settings → Apps & notifications → Special app access → Notification access → E92`).

### Unit

| Command | What it does |
|---|---|
| `.\scripts\sim.ps1 ignition off` / `on` | key turned off (ends the current trip in Trip computer) / on again |
| `.\scripts\sim.ps1 wifi 0` … `3`, `off`, `real` | Wi-Fi signal in the status bar |
| `.\scripts\sim.ps1 vol up` / `down` | steering wheel volume buttons (volume bar) |

## The diagnostics key

The diagnostics server answers only requests that carry its 4-digit key, because on the head unit it is reachable over Wi-Fi. The script reads the key from the emulator with `adb root`. If that is not possible, open **CAR → Diagnostics** in the launcher: the first line shows the address and `cheie NNNN` (the key). Then:

```powershell
.\scripts\sim.ps1 demo -Key 1234
```

or set it once for the session with `$env:E92_KEY = "1234"`.

## macOS / Linux

The script is PowerShell. Without PowerShell, the same commands work with `adb` and `curl`:

```bash
adb forward tcp:8092 tcp:8092
KEY=1234   # from CAR > Diagnostics
curl -H "X-Key: $KEY" "http://127.0.0.1:8092/sim?lever=P"
curl -H "X-Key: $KEY" "http://127.0.0.1:8092/sim?lever=D"
curl -H "X-Key: $KEY" "http://127.0.0.1:8092/sim?sport=1"
curl -H "X-Key: $KEY" "http://127.0.0.1:8092/sim?manual=2"
curl -H "X-Key: $KEY" "http://127.0.0.1:8092/sim?drift=1"
curl -H "X-Key: $KEY" "http://127.0.0.1:8092/sim?scene=1&reverse=1&cm=150,40,18,60&steer=4000"
curl -H "X-Key: $KEY" "http://127.0.0.1:8092/sim?reverse=0"

# Bluetooth music, through the head unit's Bluetooth broadcasts
adb shell am broadcast -a com.szchoiceway.btsuite.HBCP_EVT_HSHF_STATUS --ei com.szchoiceway.btsuite.DATA_INT 3
adb shell am broadcast -a com.szchoiceway.btsuite.HBCP_EVT_AV_STATUS --ei com.szchoiceway.btsuite.DATA_INT 4 --es com.szchoiceway.btsuite.DATA_STR "'Demo Song'"
```

`curl -H "X-Key: $KEY" http://127.0.0.1:8092/help` lists every endpoint of the diagnostics server.

## What the emulator cannot show

- The real CAN timing and the values of other cars: the frames were decoded on this E92 only.
- The head unit's own screens (factory reverse camera, KSW settings) and its Bluetooth module.
- Brightness and volume of the head unit itself: those commands go to the unit's MCU, which the emulator does not have.

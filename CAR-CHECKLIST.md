# Car checklist

Laptop and unit on the iPhone hotspot. Install the latest version once:
`.\scripts\e92.ps1 -Ip <unit IP> -Key 6543 install`, then tap **Install** on the unit.

## Everything you can fix from the car: Settings → Car & unit

| Problem | What to do |
|---|---|
| Phone won't connect / Bluetooth stuck on "Initialising" | **Repair Bluetooth** (phone drops for a few seconds, then reconnects) |
| Music drops when it starts | **Bluetooth module type** → next one (BC6 → BC5 → Feiyitong), restart ignition |
| Music too quiet | **Unit volume** → 35 or 40, then use the car radio knob |
| Steering volume / next / prev don't work | **Car type** → 2 (E60 small screen), restart ignition. If something breaks, go back to 6 |
| KSW Bluetooth screen covers the greeting | Check **Stop KSW reopening its screen** and **Keep launcher in front** are *on* |
| Something opens by itself at start and you want it | Turn **Keep launcher in front** *off* |
| Need the old KSW menus | **Open KSW Bluetooth app** / **Open KSW Factory settings** |

Other places:
- Phone not seen at all: Bluetooth → Phone → **Pair a phone**, on the phone pick the unit, PIN 0000.
- Console MEDIA / NAV buttons: Settings → Button mapping → *MEDIA → Media menu* / *NAV → Navigation menu*, press the button.
- Music stops: leave the KSW Bluetooth screen with **HOME**, not BACK (BACK closes it and stops the music; pressing play again brings it back).

## Bluetooth drop: which theory is right
Each run is 3 minutes: `.\scripts\car.ps1 btcapture -Minutes 3 -Label <name>`. Do them in this order.

| Label | Setup | What it tells us |
|---|---|---|
| `idle-off` | iPhone Bluetooth off, no phone near the car | Stack restarts here: the phone is not the cause |
| `idle-on` | iPhone Bluetooth on, unit forgotten on the phone | Restarts only here: the phone's radio matters |
| `paired` | Paired, music playing | When HFP rises and exactly what falls |
| `old-phone` | iPhone 13 Pro, if still available, music playing | No drops: the iPhone 17 / iOS is the difference |

## Next test (Bluetooth music only, reverse scene)
Laptop and unit on the iPhone hotspot, ignition on.

1. Install and start the stack: `.\scripts\car.ps1 prepare`, tap **Install** on the unit.
   The launcher sends `AT+PROFILE160`, which deletes the pairing, then restarts the stack once.
   `prepare` must report profile 160.
2. iPhone: Settings > Bluetooth > forget the unit, then pair it again, PIN 0000.
   If pairing does not finish after the restart: ignition off and on, then try again.
3. Play music and run `.\scripts\car.ps1 btcapture -Minutes 15 -Label music-only`.
   Goal: `HFP ridicat: 0`, `A2DP cazut: 0`, the stack process does not change.
   If it gets worse: Settings > Car & unit > **Bluetooth: music only** off, then re-pair.
4. Allow reverse detection once: `.\scripts\car.ps1 grant`.
5. Sensor data, KSW screen still on: `.\scripts\car.ps1 reverse -Minutes 3`, shift to R and
   back slowly toward an obstacle. A `radar KSW:` line means the distances reach the unit.
6. Our scene: `.\scripts\car.ps1 scene-on`, shift to R. KSW must stay closed and the scene must
   open and close with the gear. If not: `.\scripts\car.ps1 scene-off`.
7. Car type back to 6 in Factory if steering keys still do nothing.

The scene stays off for daily use until the distances are real.

## Checkpoint and next test (2026-11-08)
Fallback: `.unit-dump/checkpoints/E92-checkpoint-bt-stable-0b1db3a6.apk`, branch
`checkpoint/bt-stable`: music-only Bluetooth, no CAN, no Drift, no Sport, KSW reverse screen.
Install it with `.\scripts\car.ps1 prepare -Apk <path>` if the new build misbehaves.

New build, engine running. CAN data is on only while in R (with the E92 scene), plus while the
engine runs if Settings > Car & unit > Drift mode scene is on (off by default).
1. Drift (turn the setting on first): press the DSC button once, wait 10 s, press again; then
   hold it for DSC off, wait, press.
   Expected ON at each first press and OFF at each second. `/events` shows `buton DSC:` lines.
2. Gear: P, R, N, D, DS, D, P with 5 s each, then a short drive in D and in DS. Only logged
   (`treaptă brut:`), the Sport greeting is off until the byte is decoded.
3. Reverse twice in a row, with a drive between. `/events` shows `tip cameră KSW` at each R
   and any KSW app that jumped in front afterwards.
4. Sensors with a tape: someone at 10, 30, 60 and 100 cm behind each corner. `senzori brut:`
   in `/events` gives the raw values for the cm mapping.

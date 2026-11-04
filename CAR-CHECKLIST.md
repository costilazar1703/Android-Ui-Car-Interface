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

## Next test (Bluetooth music only, reverse scene)
Laptop and unit on the iPhone hotspot, ignition on.

1. Install and start the stack: `.\scripts\car.ps1 prepare`, tap **Install** on the unit.
   The launcher switches the stack to music only and restarts it once.
2. iPhone: Settings > Bluetooth > forget the unit, then pair it again, PIN 0000.
3. Play music and run `.\scripts\car.ps1 watch -Minutes 10`. Goal: no drop at 15 s.
   If it gets worse: Settings > Car & unit > **Bluetooth: music only** off, then re-pair.
4. Allow reverse detection once: `.\scripts\car.ps1 grant`.
5. Sensor data, KSW screen still on: `.\scripts\car.ps1 reverse -Minutes 3`, shift to R and
   back slowly toward an obstacle. A `radar KSW:` line means the distances reach the unit.
6. Our scene: `.\scripts\car.ps1 scene-on`, shift to R. KSW must stay closed and the scene must
   open and close with the gear. If not: `.\scripts\car.ps1 scene-off`.
7. Car type back to 6 in Factory if steering keys still do nothing.

The scene stays off for daily use until the distances are real.

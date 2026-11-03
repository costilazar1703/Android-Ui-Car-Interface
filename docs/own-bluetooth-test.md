# Car test: E92 Bluetooth engine

The launcher talks to the Feasycom stack itself, and bTSuite is left out. It is music only
(A2DP + AVRCP), with no calls and no phonebook. The default is still **KSW** until this test passes.

Before you start: laptop and unit on the iPhone hotspot, latest version installed.
On the iPhone, leave **Calls** off for the car, as in the last good session.

## Switch to E92

**Settings → Car & unit → Bluetooth engine** → tap until it says **E92 (music only)**.

The phone may drop for a few seconds while bTSuite lets go of the Bluetooth port.
The choice is kept, so it survives ignition off and restarts.

## What to check

Read the log after each block: `.\scripts\e92.ps1 -Ip <ip> -Key 6543 events`

### 1. The switch

- [ ] The log has `motor Bluetooth: e92`, then `/dev/goc_serial deschis, raw`, then `→ AT#MM`.
- [ ] The log does **not** have `nu pot deschide`, `modul raw a eșuat` or `nu pot scrie tipul`.
      If it does, go back to KSW and send the log.
- [ ] The log does **not** have `bTSuite încă ține`. If it does and the chip says restart,
      turn the ignition off and on once. Type 6 is already saved, so bTSuite starts out of the way.

### 2. Connecting

- [ ] Within about 5 s of the switch: `→ AT#MI`, `← MU2`, `muzica e legată`.
- [ ] The phone chip shows the iPhone name.

### 3. Music (the first test no emulator can do)

- [ ] Press play. **You hear music.** The log has `← MB: muzica cântă` and `→ AT#VB`.
- [ ] Music keeps playing with the launcher in front and no KSW screen ever opened.
- [ ] Mini-player and the Media tile show the title. Media screen shows title and artist.
- [ ] Steering wheel next and previous change the track, and the title follows.
- [ ] Pause from the mini-player, then play again.

If there is no sound but the log shows `← MB`, see *Known unknowns* below.

### 4. The 15 second drop (what this is all for)

- [ ] Music plays for **10 minutes** without `← MY: muzica s-a rupt`.
- [ ] Turn the iPhone's **Calls** on and reconnect. The log shows `← IB: HFP ridicat de telefon`.
      Music should now survive past 15 s, since we never ask for the phonebook. Turn it back off after.

### 5. Recovery

- [ ] Turn Bluetooth off on the iPhone: `← MY`. Turn it on: music reconnects by itself within about 30 s.
- [ ] Ignition off, wait 2 minutes, ignition on: it reconnects without touching anything.
- [ ] Engine start while playing: note whether anything drops.
- [ ] Bluetooth menu → **Connect new mobile** with no phone connected: the unit appears on the
      phone, and after pairing you get the "paired" message. While connected, the log says
      `nu intru în împerechere` and nothing else happens.

### 6. A real drive

- [ ] One normal drive. Afterwards read `events` and look for `muzica s-a rupt` and `stiva nu mai răspunde`.

## Go back to KSW

**Settings → Car & unit → Bluetooth engine** → tap until it says **KSW**.

The launcher releases the port, writes the module type back to the one in **Bluetooth module type**
(BC6 by default) and restarts BTService. If Settings is out of reach, installing a build from
before this engine has the same effect: at start it writes the module type back to BC6.
If the type somehow stays wrong, set it in the Factory app under **BT Module**.

## Known unknowns

Only the car can answer these. The emulator test uses a fake stack with no real phone or audio.

- **Sound without bTSuite.** blueware plays the audio itself through Android media volume, and we
  unmute it with `VB` and take audio focus like bTSuite. bTSuite also tells the MCU the play state
  (`IEventService.sendPlayState`), which we do not do. If there is no sound, this is the first suspect.
- **Writing type 6 and restarting BTService.** The emulator has no bTSuite, so this path ran only
  on paper. It uses the same calls as the existing module type switch, and block 1 above checks it.
- **Stack auto-connect.** The stack's own power-on auto-connect (`MF01`) stays as it is. It could still
  ask the iPhone for HFP by itself. If `IB` or drops appear at start with Calls off, the next thing to
  try is `AT#MH`, which turns it off.
- **Progress bar.** It stays hidden, as it does with KSW today. The stack gives the track length but
  not the position, so a bar would be a guess.
- **More than one paired phone.** On a music-only link the name shown is the first one in the
  stack's paired list, which may not be the phone that connected.

## Emulator test

A fake stack with a real pty at `/dev/goc_serial`, so the launcher has no test code in it.
Needs a `google_apis` image, because it uses `adb root`.

```bash
powershell -File scripts/fake-goc/run.ps1 start -Serial emulator-5580
```

Then install the APK, set the engine to E92 in Settings, and drive the "phone":
`run.ps1 drop`, `phone`, `paired`, `hfp`, `poll`, `die`. Use `run.ps1 check` to fail on any HFP or
phonebook command or on echo, and `run.ps1 events` to read the launcher's log.

Tested this way on 2026-11-03: KSW default sends nothing on the pty; connect, play, pause,
next and previous through the media keys; track info with diacritics and `0xFF` separators;
reconnect after a drop; launcher restart with the phone linked; pairing and reset; the launcher
surviving the stack's death and reconnecting when it returns; KSW → E92 → KSW → E92 in Settings.

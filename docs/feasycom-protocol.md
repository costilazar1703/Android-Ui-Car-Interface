# Feasycom (blueware) AT protocol on the KSW unit

Source: bTSuite decompiled (`.unit-dump/src/bTSuite`, `BTUtils`, `BTService`) and the
stack's own log `/data/feasycom/log` read as root on 2026-11-03
(`.unit-dump/bt-2026-11-03-night/`).

## Transport

- `/oem/bin/blueware /dev/ttyS4 921600` (init service `feasycom`) drives the CSR chip
  over H5 on ttyS4 and exposes a pty, symlinked as `/dev/goc_serial` (`/dev/pts/0`, mode 0777).
- The host app writes `AT#<CMD>[args]\r\n` and reads lines `<2-letter code><data>\r\n`
  (the log prints them as `<<:AT#..` and `>>:..`).
- Only one client should hold the pty: bTSuite opens it for `Sys_BTDeviceType` 2-5
  (BC6 = 3). With type 6 it opens `/dev/ttyS2` @115200 instead (nothing there), which
  frees `/dev/goc_serial` for the launcher. Type back to 3 restores bTSuite.

## Commands (host -> stack)

| Cmd | Meaning |
|-----|---------|
| DY | HFP status query (answer MG<n>) - bTSuite polls every 5 s |
| MY | firmware version (answer MW) |
| MM[name] | get / set local name (answer MM<name>) |
| MN[pin] | get / set PIN (answer MN<pin>) |
| MG / MH | enable / disable auto connect at power on (answer MF..) |
| MF | auto-answer / auto-connect config |
| MI | connect last A2DP source |
| MJ | disconnect A2DP |
| MA | AV play/pause toggle |
| MC | AV stop |
| MD / ME | next / previous track |
| MZ | music info (answer MZ<addr>...) |
| MV / MO | A2DP / AVRCP status |
| VA / VB | mute / unmute BT music playback in blueware |
| VC / VD | BT music background / normal |
| VF<n> | BT music volume |
| MX | paired list (answer MX<idx><addr><name>) |
| DA / DB | enter / exit pairing |
| DC / DD | HFP connect / disconnect (DC is also sent by bTSuite at every BTService start) |
| DV | delete all paired, enter pairing |
| PA | phonebook (SIM book) download - sent by bTSuite right after every HFP connect; iPhone answers ERROR first |
| SD / ST | start / stop inquiry |

## Indications (stack -> host)

| Code | Meaning |
|------|---------|
| IS | power-on init complete |
| II / IJ | enter / exit pairing mode |
| IV | connecting |
| IB | HFP connected · IA HFP disconnected |
| MG<n> | HFP status: 1 idle, 2 connecting, 3 connected, 4-6 call states |
| MU<n> | AVRCP status: 1 disconnected, 3 connected, 4 paused, 5 playing |
| MA / MB | AV suspended / AV playing |
| MY | A2DP disconnected |
| MI<title>\xff<artist>\xff<album>\xff<n>\xff<ms>\xff | track info |
| MX0/1<addr><name> | connected / paired device |
| PA1 / PB.. / PC | phonebook ready / entry / end |

## Observed failure modes (2026-11-03)

1. bTSuite crash: any intent to an existing `BTMainActivity` -> NPE in
   `FragmentBase.refreshFocusView_KSW` -> process dies -> BTService restarts ->
   sends MY, MM, MN, MG, **DC**, MQ while the phone is connected -> link drops;
   blueware's worker can SIGSEGV and lose the new link key ("auth failed").
2. iPhone drops A2DP + HFP ~15 s after HFP SLC, right after bTSuite's `PA`
   (phonebook) request. With HFP gone and only A2DP up, music stayed stable.
3. BTService stops A2DP every second while `BTMainActivity` does not exist (btMode).

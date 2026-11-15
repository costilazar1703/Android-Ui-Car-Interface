# KSW head unit: the factory reverse screen comes back after the first reverse

Brief for brainstorming. Everything below is measured on the car unless marked as a guess.

## Setup

- Car: BMW E92 320d (2009, automatic), no factory iDrive screen.
- Head unit: KSW / szchoiceway Android 8.1 unit, RK3399, 1280×480, model "BMW X1 2016 6.5" (car type 1).
  The unit has an MCU (microcontroller) that talks to the car through a CAN box. Android talks to
  the MCU through the vendor service `com.szchoiceway.eventcenter` (EventService), over a serial link.
  Frames look like `F2 00 <cmd> <len> <data...> <checksum>`.
- An aftermarket reverse camera is installed but broken, so the KSW reverse screen shows "No Signal".
- We wrote our own launcher (system-level privileges: no; normal app with READ_LOGS granted via adb).
  It has its own reverse scene (parking sensor bars and steering lines drawn from CAN data).

## Goal

Every time the driver selects R, show our reverse scene and never the KSW/MCU reverse picture.

## What happens

- **First R after the unit boots:** our scene shows correctly.
- **Every R after that:** the screen switches to the KSW reverse picture. With the MCU radar and guide
  lines turned off it is now just "No Signal" on black.
- This repeats after every ignition cycle: first R good, the rest bad.

## What we know for sure

1. The picture is **not an Android window**. EventCenter never calls its own reverse window
   (`startBackcar`/`backcarConnected` are not logged in R2/R3). Screenshots from Android show our app
   while the screen shows the KSW picture. So the **MCU switches the display/video path** itself.
2. On every R the MCU sends `F2 00 11 02 01 01` (sub-type 01 = "aftermarket reverse", 进入后装倒车)
   and `F2 00 11 02 01 00` when leaving R. The sub-type is always 01, even with camera type 0.
3. MCU display settings that Android can send (`F2 00 6A 02 <index> <value>`, same as the KSW menus):
   - index 0x0B, camera type: 0 none, 1 aftermarket, 2 360°, 3 OEM. **With 3, both the first and
     the later Rs show "No Signal"**, so this setting does reach the MCU. 0 is the best we found.
   - 0x16, guide lines; 0x17, radar. Setting both to 0 removed the KSW sensor and steering overlay.
   - 0x13, "original car video display": no visible effect.
   We resend these at boot and on every R. They do not stop the switch on R2+.
4. EventCenter sends a heartbeat every second: `F2 00 76 03 <interfaceStatus> <reverseState> <backlight>`.
   After boot both values are -1 (0xFF). We sent this heartbeat ourselves at 4 Hz with
   reverse 0xFF (interface 1) and with both 0xFF. **No effect.**
5. **Only difference found between the first and later Rs:** when leaving the *first* R, the MCU sends
   `F2 00 15 02 01 01`. EventCenter handles it as "original (OEM) mode ended" (`ksw_original_end()`),
   sets interfaceStatus = 1, calls endBackcar and sends a HOME key. Later R exits do not send 0x15.
   Our guess: before the first R the MCU is in an "original/OEM screen" state and does not switch to the
   camera input. After that first R it leaves that state for good and switches on every R.
6. We turn on raw CAN forwarding with a Factory command (`F2 00 70 02 0F 01`). On R2 and R3 the sensor
   frames (CAN id 0x1C2) did **not** arrive, although the command was sent. So the KSW picture and the
   missing CAN data may be linked: the MCU may stop forwarding while it is in its own camera mode.
7. Earlier test: the unit setting `KESAIWEI_SYS_CAMERA_SELECTION = 0` stops EventCenter's own window,
   but not the MCU switch.

## Limits

- The launcher is a normal app. It can send MCU frames through EventCenter's binder (as above) and
  read logcat. adb (root shell) is available on request.
- No MCU firmware source. Flashing the MCU is not wanted (risk of bricking the unit).
- The vendor apps are in /oem/app and can be disabled but not removed.

## Ideas so far (not tested unless stated)

- **Hardware: reverse trigger wire.** The "aftermarket reverse" frame (type 01) may come from a
  physical reverse/camera trigger wire on the unit's harness (from the reverse light), not from CAN.
  If that wire is disconnected, the MCU would not enter its camera mode. Our app would then detect R
  from CAN instead (gear frame 0x304: C2 = R), or from the CAN box data.
- **Put the MCU back in "original" mode.** Find which Android→MCU command starts the OEM/original mode
  (EventCenter's `ksw_original_start()` and the source command `F2 00 67 01 <mode>`), and send it after
  every R exit, so the MCU is in the same state as before the first R.
- **Factory menu.** Look for a "reverse source / reverse detect" option (CAN vs wire) or "camera: none"
  in the KSW Factory settings (password usually 8888 / 1234 / 5678).
- **Feed a video signal.** Plug any composite video source into the camera input: the MCU would show
  that instead of "No Signal". It does not give our scene, but it removes the error screen.
- **Different car type / CAN profile** in Factory that has no reverse screen support (risky: affects
  steering-wheel keys and car data).

## Questions for brainstorming

1. Has anyone seen KSW/szchoiceway MCUs keep a "first reverse after boot" state like this?
2. Which MCU command or Factory option disables the MCU's own reverse video switching?
3. Is "aftermarket reverse" on these units triggered by a harness wire or by CAN?
4. Any other way to keep the display on the Android/ARM output during reverse?

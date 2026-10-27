# Scripted controller thumbstick on PICO Emulator 6.1.0 (emulator-5562, AVD PICO_6_1_Factory2, qemu PID 39248)

Status: WORKS (verified 2026-10-22 on emulator-5562). Host input is PostMessage + UI Automation only.

## How it works
The emulator's Settings > Controller Setting page (opened by the `controller_button` toolbar button) maps PC keys
to controller inputs. Read from UIA (ValuePattern of the `ControllerLineEdit` fields), defaults:

| Controller | Stick up | left | down | right | stick click | trigger | grip | A/X | B/Y | menu/home |
|---|---|---|---|---|---|---|---|---|---|---|
| right | I | J | K | L | Y | U | O | Space | Del | C (camera) |
| left  | W | A | S | D | R | Q | E | X | Z | M |

Posted WM_KEYDOWN/WM_KEYUP to the Qt main window (`PICO Emulator - 6.1.0`, class Qt653QWindowIcon, picked by qemu PID)
deflect the stick fully while the key is held. Conditions:
1. The emulator must be in Right (or Left) Controller Mode. In Eye Gesture Mode the keys do nothing to the controller.
2. Qt must believe its window has focus: post WM_KILLFOCUS+WM_SETFOCUS (`emu.ps1 fast on`) before the key.
   Any posted mouse move (emu.ps1 move/click/drag) drops that state; then the keys are silently ignored.
   (`keyboard_button` toggle stays Off; it is not needed.)

## Commands
```bash
S=/c/Users/Admin/AppData/Local/Temp/claude/C--Users-Admin-Downloads/a0d3e6e6-e66a-4b8a-b283-721816b24b11/scratchpad/stick/stick.sh
$S mode right            # UIA: ctrl_dropdown_button > "Right Controller Mode"
$S right 1500            # thumbstick +X (key L) for 1.5 s   -> thumbstick x ~ +1
$S left 1500             # thumbstick -X (key J)             -> thumbstick x ~ -1
$S up 1500 / down 1500   # +Y (I) / -Y (K)
$S down-only right ... $S up-only right   # open-ended hold
$S right 1000 left       # left controller stick (needs left controller online)
$S read                  # raw driver bytes (rocker_x/rocker_y, 128 = centre)
$S mode eye              # back to the default mode the rest of the harness assumes
```
stick.sh wraps `C:\Users\Admin\Downloads\trace-light\tools\harness\emu.ps1` (fast on / keydown / keyup, `-Avd PICO_6_1_Factory2`)
and `emu_mode.ps1 -Avd PICO_6_1_Factory2 -Serial emulator-5562`. Each powershell call takes ~1 s, so the real hold is
about HOLD_MS + 1 s. The system samples the stick within ~10-500 ms, so hold at least 600 ms.

## Evidence (logcat, emulator-5562)
```
EmuPipeService-Controller: run_controller: hand[1] key[15] value[128], setHookKeyValue rv[0]
EmuPipeService-Controller: run_controller: hand[1] key[16] value[255], setHookKeyValue rv[0]
InputDeviceManager: activemonitor keyfsm rocker right rocker enter high_seen rockerX=0.992 rockerY=0.000 ...   (key L)
InputDeviceManager: activemonitor keyfsm rocker right rocker enter high_seen rockerX=-1.000 rockerY=0.000 ...  (key J)
InputDeviceManager: activemonitor keyfsm rocker right rocker enter high_seen rockerX=0.000 rockerY=0.992 ...   (key I)
InputDeviceManager: activemonitor keyfsm rocker right rocker enter high_seen rockerX=0.000 rockerY=-1.000 ...  (key K)
InputDeviceManager: activemonitor keyfsm rocker left rocker enter high_seen rockerX=0.992 rockerY=0.000 ...    (key D, hand[0])
on release: ... enter low_ready rockerX=0.000 rockerY=0.000 ... then "activemonitor switch apply preActiveInputDevice=hand curActiveInputDevice=controller"
```
Raw driver bytes (`pxrcontrollerservice_tools_emulator getControllerKeyData 1`) are rotated relative to the
normalised value: L -> rocker_y 255, J -> rocker_y 0, I -> rocker_x 255, K -> rocker_x 0 (centre 128).
The normalised InputDeviceManager rockerX is what corresponds to thumbstick x; confirm in the app with its `STICK x=` log.

Note: the first full press-release cycle switches the system's active input device from hand to controller.
If the app ignores the first deflection, send one short priming flick first (`$S right 700`).

## Not working / dead ends
- `pxrcontrollerservice_tools_emulator injectKeyValue <hand> <key> <value> <x>` (in-guest test tool): runs, but the client
  logs `mInjectKeyDataBuffer sharedmemoey is nullptr` (user build, ro.debuggable=0) and nothing changes.
- Guest has no root; `cmd pxrcontrollerservice` fails; strings in the host qemu exe are VMProtect-packed.

## Side effects
None persistent. The settings window was opened read-only and closed via its close button (no Save).
The emulator was left in Eye Gesture Mode. After one eye->right switch both controllers reported online
(left and right); harmless, back to offline in eye mode.

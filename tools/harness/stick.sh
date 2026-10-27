#!/usr/bin/env bash
# Deflect a PICO Emulator 6.1.0 controller thumbstick from a script (emulator-5562 / AVD PICO_6_1_Factory2 only).
# Mechanism: the emulator's Controller Setting page maps keys to stick directions
#   right controller: I=up J=left K=down L=right      left controller: W=up A=left S=down D=right
# Keys are POSTED (WM_KEYDOWN/WM_KEYUP) to the emulator's Qt main window by furniture-fit/tools/harness/emu.ps1
# (PostMessage to the qemu PID of the AVD only; no SendInput, no focus change).
# Qt only accepts the posted keys while it believes it has focus: every key action first posts WM_KILLFOCUS+WM_SETFOCUS
# (emu.ps1 "fast on"; real focus unchanged). Any posted mouse move (emu.ps1 move/click/drag) cancels that state.
# The keys only reach the controller in "Right/Left Controller Mode" (in Eye Gesture Mode they do nothing / WASD moves the camera).
#
#   stick.sh mode right|left|eye          switch interaction mode via UI Automation (emu_mode.ps1)
#   stick.sh right|left|up|down [HOLD_MS] [HAND]   hold the stick at full deflection for HOLD_MS (default 1500), HAND=right|left (default right)
#   stick.sh down-only DIR [HAND] / up-only DIR [HAND]   press / release without timing (for long holds driven by the caller)
#   stick.sh read [HAND]                  raw driver state: rocker_x/rocker_y 0..255, 128 = centre
#                                         (raw axes are rotated: L/R change rocker_y, I/K change rocker_x;
#                                          the normalised system value in InputDeviceManager logcat is rockerX=+-1 for L/J)
set -u
DEV=emulator-5562
AVD=PICO_6_1_Factory2
H='C:\Users\Admin\Downloads\trace-light\tools\harness'
EMU="powershell -NoProfile -ExecutionPolicy Bypass -File $H\emu.ps1"
MODE="powershell -NoProfile -ExecutionPolicy Bypass -File $H\emu_mode.ps1"
[ "$(adb -s $DEV emu avd name 2>/dev/null | head -1 | tr -d '\r')" = "$AVD" ] || { echo "ERROR: $DEV is not $AVD"; exit 2; }
key_for() {   # DIR HAND
  case "$2:$1" in
    right:right) echo L;; right:left) echo J;; right:up) echo I;; right:down) echo K;;
    left:right)  echo D;; left:left)  echo A;; left:up)  echo W;; left:down)  echo S;;
    *) echo "ERROR: dir right|left|up|down, hand right|left" >&2; exit 4;;
  esac
}
hand_idx() { [ "$1" = left ] && echo 0 || echo 1; }
case "${1:-}" in
  mode) $MODE -Mode "${2:?right|left|eye}" -Avd $AVD -Serial $DEV ;;
  read) adb -s $DEV shell "pxrcontrollerservice_tools_emulator getControllerKeyData $(hand_idx "${2:-right}") | grep -o 'Rocker-.*'" ;;
  down-only) k=$(key_for "${2:?dir}" "${3:-right}") || exit 4; $EMU fast on -Avd $AVD >/dev/null; $EMU keydown $k -Avd $AVD ;;
  up-only)   k=$(key_for "${2:?dir}" "${3:-right}") || exit 4; $EMU keyup $k -Avd $AVD ;;
  right|left|up|down)
    hold="${2:-1500}"; hand="${3:-right}"; k=$(key_for "$1" "$hand") || exit 4
    $EMU fast on -Avd $AVD >/dev/null
    $EMU keydown $k -Avd $AVD >/dev/null
    sleep "$(awk "BEGIN{print $hold/1000}")"
    $EMU keyup $k -Avd $AVD >/dev/null
    echo "stick $hand $1 (key $k) held ${hold} ms" ;;
  *) sed -n '2,19p' "$0"; exit 1 ;;
esac

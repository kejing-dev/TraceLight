#!/usr/bin/env bash
# All design states in one go: tools/qa_tour.sh [S1 S5 ...]  -> .scratch/shots/<id>.png + tour.png + tour.txt
# Each state: `state <id>`, wait, console screenshot, then `dump` to check the reached designState.
set -u
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DEV=${DEV:-emulator-5562}
ids=("$@")
[ ${#ids[@]} -eq 0 ] && ids=(S1 S2 S3 S4 S5 S6 S7 S8 S9 S10 S11 S12 S13 S14 S15 S16 S17 S18 S19 S20 S22 S21)
# design captures read in cm (the emulator region is en-US, whose default is inches)
bash "$ROOT/tools/dev.sh" cmd ui unit_cm >/dev/null
out="$ROOT/.scratch/shots/tour.txt"
: > "$out"
for id in "${ids[@]}"; do
  case "$id" in
    S15) bash "$ROOT/tools/dev.sh" cmd snack long >/dev/null; settle=2 ;;
    S6) settle=4 ;;
    *) settle=${SETTLE:-11} ;;
  esac
  SETTLE=$settle bash "$ROOT/tools/qa_shot.sh" "$id" >/dev/null 2>&1
  bash "$ROOT/tools/dev.sh" cmd dump >/dev/null
  sleep 1
  got=$(adb -s $DEV logcat -d -s TraceLight:V 2>/dev/null | grep "DUMP" | tail -1 | sed -E 's/.*"designState":"([^"]+)".*/\1/')
  echo "$id reached=$got" | tee -a "$out"
done
python "$ROOT/tools/contact.py" "$ROOT/.scratch/shots" "${ids[@]}"
bash "$ROOT/tools/dev.sh" cmd snack normal >/dev/null

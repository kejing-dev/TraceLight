# Emulator test harness for Spatial Ruler (PICO Emulator 6.1.0, AVD `PICO_6.1_Ruler`, `emulator-5556`)

This folder was copied from `spatial-ruler-spike/tools/harness`. `emu.sh` now defaults to the product
(`PKG=io.embodit.spatialruler`, `TAG=SpatialRuler`); for the spike use `PKG=io.embodit.rulerspike TAG=RulerSpike`.
`calibrate.py`, `emu.ps1` and `calib.json` are unchanged. Everything below the line is the original report,
measured on the spike app.

## What the product debug build provides (round 2a, checked 2026-10-02)

Commands are debug broadcasts: `emu.sh cmd NAME [numbers] [text]` (or `tools/dev.sh cmd ...`). The full list with
arguments is the header of `app/src/debug/java/io/embodit/spatialruler/debug/DebugCommands.kt` and the table in
`AGENTS.md`. What a test needs most:

Reading back
- `pose` -> `POSE hmd pos=(x, y, z) q(x, y, z, w) fwd=(..) up=(..) right=(..) valid=true pointer_eye=left eye_pos=(x, y, z)`,
  five decimals. `pointer_eye` / `eye_pos`: where the app takes the system pointer's ray to start (emulator: the left
  eye, 0.032 m to the head's left; on a device `pointer_eye=unknown` and `eye_pos` is the head).
- `bounds` -> for every visible attachment panel
  `PANEL id=<id> pos=(stage) q(x, y, z, w) scale=<k> size_px=<w>x<h> stretch_x=<f>` (five decimals), followed by
  `CTRL panel=<id> name=<name> cx= cy= w= h=` (panel pixels as drawn, origin top-left), then `BOUNDS panels= controls=`.
  A panel pixel is 0.4 mm at scale 1 (1 dp = 2 px = 0.8 mm). Panel point (cx, cy) is at
  `pos + R(q) * ((cx - w/2) * 0.0004 * scale * stretch_x, (h/2 - cy) * 0.0004 * scale, 0)` in Stage metres.
  `stretch_x` is 1 for most panels. Reading plates and the snackbar are made exactly as wide as their content
  (`stretch_x` = content width / panel width, e.g. 0.56 for "38.0 cm"): their content fills the whole `size_px`.
  `tools/test/rt.py` (`panel_point`) and `tools/ctrlpx.py` apply it; a parser that ignores it still finds the centre
  of a plate, but misses controls that are off centre (the snackbar's action button).
  Panel ids: `body`, `plate0..19`, `plateLive` (the plate on a tape that is being pulled; not a control),
  `chip0..5`, `pinTag` (304 x 70), `pinTagTune` (304 x 128), `tapeTag` (166 x 70), `reticleTag` (96 x 24; the figure
  beside the reticle; not a control), `menuPanel` (256 x 120), `coachPanel` (360 x 80), `dialogPanel` (480 x 280 dp;
  the card inside is 400 dp wide and hugs its content), `snackPanel`, `calib`. Round 4: the menu is always the
  attachment panel `menuPanel` (no popup, no `langPanel`); plates are 184 x 40 dp.
  Round-4 control names: body `undo more unit_*`; `menuPanel` `lang_system lang_zh lang_en snap menu_clear menu_exit`;
  `pinTag` / `pinTagTune` `pin_tune pin_remove pin_close step_u_minus step_u_plus step_v_minus step_v_plus`
  (‹ › ˅ ˄); `tapeTag` `tape_retract`; `coachPanel` `coach_got_it`; dialog and snackbar names unchanged. Removed:
  `menu_language`, `tape_close`, `langPanel`; moved: `snap` (body -> menuPanel), `lang_*` (popup -> menuPanel).
- `rig` -> one `RIG slot id= mount=world|child|head rest=shown|parked|stowed|off_in_place|panel_off shown= bound=
  settled= holder_enabled= panel_enabled= pos=(stage) q(..) scale= view=(yaw, pitch, range | behind) engine_local=(..)`
  per pooled panel (36 slots since round 4: no `langPanel`, plus `coachPanel`), `RIG body center=(stage) q(..) half=(x, y, z) rest_offset=(..) collider=frame name=body`,
  `RIG body engine mount= anchor=`, every `RIG violation ...` that holds right now, and
  `RIG summary slots= shown= bound= violations= hidemode=`. `pos` of a hidden slot is where it is stowed.
  Without being asked the app logs `RIG slot id=<id> shown|parked|stowed|kick` whenever a slot changes, and every
  0.5 s checks the rule "a hidden slot is switched off and outside the view": `RIG violation id=<id> hidden slot
  enabled ...` / `... hidden slot in view yaw= pitch= range= ...` (each at most every 5 s). No violation was seen in
  round 2a.
- `dump` -> `DUMP {json}` (long ones as `DUMP+ i/n ...`; always complete in `files/dump.json`). New keys: `snack`,
  `snack_id`, `snack_counts`, `snack_remaining_ms`, `snack_text`, `snack_desc`, `snack_action` (the copy in the app's
  language), `settings_known`, `tracking_lost`, `pull`, `truing`, `planes`, `scanning`.
- `fps` s=on|off -> `FPS app=<frames per second> frames=<n> ms=<span>` once a second (the app's own frame callback).
- `planes` -> `PLANE <label> kind=.. center=.. normal=.. verts=..`, `PLANES listed n=`.

Designed states the emulator never reaches by itself
- `tracking` s=lost|ok: `TRACK forced lost=true`, `TRACK lost`, `STATE tracking-lost`, `SNACK lost ... ms=sticky`;
  `ok` -> `TRACK recovered`, `SNACK recovered`.
- `blocked` s=on|off: on = the session starts again as "spatial features unavailable" (`BLOCKED reason=unknown`,
  `STATE blocked`, dialog with `blocked_exit` / `blocked_retry`); while it is on, Retry stays blocked; `off` lifts it,
  then press Retry (`STATE starting` -> `RESTORE ...`).
- `elsewhere` a=<n>: the last n pins count as "not in this space" (`PIN elsewhere ids=...`, they and their tapes are
  hidden, `SNACK elsewhere`, action "Clear them" = `snackbar_action`).
- `snack` s=long|normal: timed snackbars stay 60 s. `marks` s=long|normal: the seat mark of a snapped pin stays 10 s
  instead of 0.8 s (so a screenshot can catch it).
- `fail_next_anchor`: the next pin is unsaved, snackbar with Retry.

Tracked pointers without hardware (round 2b)
- `source` s=controller|hand|system|synthetic|auto: which pointer aims (`SRC forced=...`, then `SRC active=...`).
  `controller` / `hand`: that device; while it delivers nothing, the synthetic pointer stands in for it, so
  `pointer` + `trigger` then log `TAP pointer kind=controller|hand ...` and `PIN add ... via=controller|hand`.
- `pointer` a=ox,oy,oz,dx,dy,dz sets the ray (Stage metres); aim it from where a hand would be (0.25 m right of the
  head, 0.25 m below, 0.2 m ahead): from the head itself a ray to the lower left runs through the body and is left
  to the body panel (`RETICLE state=blocked:body`, `AIM commit skipped reason=body`).
- `trigger` = one full press; `trigger half` holds the trigger half pressed (`AIM fine=on`, the reticle moves a
  quarter as far as the ray), `trigger full` commits once, `trigger release` lets go.
- `contact` a=x,y,z sets the tool tip (within 12 cm of a surface: `RETICLE state=contact`, a press sets the pin
  at the tip itself, `PIN add ... grade=contact ... via=contact`); `contact` alone removes it. `hand_lost` removes
  the whole synthetic pointer (`SRC active=system`). `steady` s=on|off|auto forces the stability.
- `src` -> `SRC active= forced= trigger= synthetic= controller[origin= dir= tip= press= pressed= ready= hand=]
  hand[...]`: what every source delivers right now.
- Log: `RETICLE state=aim|steady|fine|far|snap|contact|none|mapping|blocked:body|blocked:panel:<id> stage= dist=
  stable=0..1 snap=none|corner|edge|pin|plumb|level src=` on every change and every 2 s; `FEEDBACK pin_drop|snap|
  tape_lock|retract|fail hand=right|left` when a sound / touch was played.
- One press, one pin: while a tracked source is active (`SRC active=` not `system`) a mouse pinch on a surface
  logs `TAP surface ... ignored reason=tracked-source` and sets nothing; pins, plates, chips, tags and body keys
  still take the pinch. Clear the synthetic pointer (`pointer` with no numbers, or `hand_lost`) to tap surfaces
  with the mouse again.

Controller mode on the emulator (`emu_mode.ps1 -Mode right`, verified 2026-10-02)
- The app's `ControllerTrackingProvider` becomes `SUPPORTED` after the first posted mouse move. The controller ray
  starts at the left eye and goes through the mouse cursor, so `emu.sh move X Y` aims it exactly like a gaze; the
  reticle follows (`RETICLE ... src=controller`). `emu.sh click` / `down` / `up` is the trigger: `triggerPressed`
  with `triggerValue` 0 or 1 - there is no half press in this mode. A click commits at its press edge
  (`TAP pointer kind=controller ...`, `PIN add ... via=controller`); the same click also arrives as a spatial tap
  of kind `RayBasedPinch`, which the one-press rule drops. A click on a pin acts once; a click on a body key is
  left to the panel (`AIM commit skipped reason=body`, `UI unit_mm`). Hands never appear on the emulator.
- Every other command of this harness assumes eye mode: finish with `emu_mode.ps1 -Mode eye` and check a pinch on
  a body key (`UI snap`). Seen once (2026-10-02 14:50): after a launch in which the app's hand tracking provider
  went `NOT_IN_FULL_SPACE` -> `SUPPORTED`, the guest kept `isHandTracking:true` and eye-mode pinches stopped
  reaching any app (no `PTR`, no `HOVER_ENTERED`); controller mode kept working. App restarts, mode toggles and
  the tool bar's `interaction_button` did not bring it back; restart the emulator. The app no longer starts hand
  tracking on the emulator.

Other switches: `lang`, `unit`, `snap`, `clear`, `undo`, `place`, `connect`, `tap_pin`, `ui` (press a control by name),
`overlay`, `gest`, `calib`, `hidemode`, and for looking at rendering and the pointer: `lit` s=on|off, `lit`
a=metallic,steelGlow,r,g,b, `tonemap` s=on|off, `ghost` s=on|off (hides the body panel and switches its collider off:
every pinch aimed at the body must then be answered by `TAP blocked by=body ... key=<name>`), `collider` [a=<mm>].

Log lines of input (tag `SpatialRuler`)
- `TAP surface plane=FLOOR:dc233687 kind=GazePinch stage=(x, y, z) view_px=(x, y, z) near=<pinId>:<deg>deg|none`:
  `view_px` is the position the system reports (pixels of the app's SpatialView, not console pixels); `near` is the
  pin nearest to the line of sight that was not taken, with its angle off that line.
  `TAP pin id=p1 ...`; `TAP pin id=p1 ... via=surface` (the pinch arrived on the surface inside the pin's touch sphere:
  a quick second pinch on a pin that was just set); `TAP backstop ...`; `TAP other target=<entity> ...`;
  `TAP blocked by=body target=<what> stage= eye= key=<body key|none>` (a pinch whose line of sight runs through the
  body arrived on something behind it and was dropped; the key under the line of sight is pressed instead);
  `TAP ignored reason=after-drag`.
- `DRAG start target= kind= stage=(press point) device=...`, `DRAG begin mode=pull|true|none ...` (the hand has moved
  1 cm), `DRAG move ... stage=(tail, or the pin that is trued)` (about 10 Hz), `DRAG end ...`, `DRAG cancel ...`;
  `PULL start head= new=`, `PULL end head= tail=`, `PULL cancel head=`; `PIN true id= du= dv= pos=` (a pin slid by a drag;
  `PIN nudge ...` for a stepper press).
- `PTR panel=<id> ev=Press|Release|Enter|Exit x= y=` for every pointer event that reaches the drawn part of a panel
  (panel pixels). No `PTR` line after a click means the pinch did not reach that panel's content.
- Entity names: `pin:<id>` (touch sphere), `pinView:<id>`, `plane:<SEMANTIC>:<uuid8>`, `backstop:<side>`, `body`,
  `holder:<panel id>`, `panel:<panel id>` (the system logs `HOVER_ENTERED <id> <name>`, `SELECT_ENTERED touch down <name>`).
- Start-up: `SCENE body panel up ms=`, `SCENE body panel drawn ms=`, `SCENE attached build_ms= steps_ms:...`.

Harness (this folder)
- `emu.sh clicks X1 Y1 X2 Y2 ... [-GapMs n]`: several pinch-taps in one process; repeat a point for a double tap
  (two taps 260 ms apart with `-GapMs 100`).
- `emu.sh click | clicks | drag` need 60 fps. When the emulator does not reach 50 fps they are **not sent**, the
  script prints `ERROR: 50 fps gate failed, <action> not sent (exit 3)` and exits with 3. `-NoFast` sends a slow
  press instead (clicks Compose buttons, never a spatial tap).
- `emu_mode.ps1 -Mode eye|left|right|status` (PowerShell, UI Automation, no mouse and no focus change) switches the
  emulator's interaction mode; controller mode makes the left / right controller `online` in the guest (see
  "Controller mode on the emulator" above). Every other command here assumes eye mode: leave the emulator in eye
  mode (`-Mode status` prints the tool-bar state and the guest's controller state).
- `python tools/ctrlpx.py <panel id> <control name | @cx,cy>` prints the console pixel of a control or panel point.
- `tools/test/rt.py` is the test library used by the round 1 scripts (`press`, `tap`, `bounds`, `pose`, `project`).

Checked with real input in round 2a (emulator, eye mode): body keys at the standing pose and with the head 0.19 m
above the floor (`UNIT cm`, `SNAP on`, no `PIN add`); plate -> tape tag (`tape_close`, gone in round 4: a second tap on the plate deselects); pin tag (`pin_tune`,
`step_u_plus`, `step_v_minus`, `pin_close`); dialog `dialog_cancel`, `blocked_retry`; snackbar `snackbar_action`;
tap - tap, press-and-pull (`emu.sh drag X1 Y1 X2 Y2`), true-up drag with fine-tune open; double tap (`clicks`).

Notes for testing the product
- Launching while another Full Space app is in front fails with `result code=100`: stop that app first.
- Start-up: the body panel is up first (`SCENE body panel drawn`), then the meshes are built: 14 - 23 s at the
  emulator's idle frame rate (`SCENE attached build_ms=`), then `STATE idle-scanning` / `idle`.
- At idle fps the picture lags seconds behind the log. Use `emu.sh waitfast` before a screenshot that must show the
  latest state; snackbars last 1.8 - 7 s (`snack long` keeps them).
- The instrument body is head-locked at the lower left: content behind it cannot be seen or tapped. It rests on
  surfaces: with the head low, or with the camera inside furniture, it is pushed out of the surface (`rig`:
  `rest_offset`).
- **A panel takes the pointer over its whole rectangle, also where it draws nothing.** A pinch on an empty part of a
  panel reaches neither the panel's content nor what is behind it: no log line at all. (The round 1 note that the
  transparent rest lets a pinch through was wrong for plates.) Plates and the snackbar are therefore squeezed to the
  width of their content (`stretch_x`); chips keep a margin of 8 dp at the sides and 2 dp above and below.
- The system pointer does not take the nearest hit on its ray. It ranks a panel by the distance from the head to the
  panel's centre and a collider by the distance to the hit point: a collider behind a panel that is seen from the
  side can take presses on that panel. The body's collider is therefore a frame around the glass, with nothing behind it.
- A pooled panel's content is composed two engine frames after the panel is put in place. `bounds` can list a panel
  before its `CTRL` lines exist; `rt.press` reads `bounds` once, so wait for the state line first.
- Plates choose their place when a tape locks, a pin moves or a tape goes away, and keep it. With more plates than
  there is room for (20 tapes on 1 m2 of floor) they overlap; a pinch then selects the tape of the plate in front.
- The drag gesture reports hand travel, about the cursor's travel at the depth of the target on the emulator; the
  tape's tail follows the pointing-line rule and moves about four times as far as the cursor at 2 m. Assert on
  `DRAG ... stage=` and on the state, never on the on-screen distance.
- `emu.sh cmd hidemode "" park` brings back the defect fixed on 2026-10-02 (body keys do not get the pinch);
  `auto` is the default.

---

# Original harness report (spike app)

Measured 2026-10-01 on the spike app `io.embodit.rulerspike`. Everything below was run; nothing is from docs.
Host input is `PostMessage` to windows of the qemu PID of the chosen AVD only. The real cursor and the real
foreground window are never touched (checked after every experiment: `emulator main is foreground: False`).

## Files
| File | Purpose |
|---|---|
| `emu.sh` | Entry point for Git Bash. Device calls are pinned to `$DEV` (default `emulator-5556`) and check the AVD name. |
| `emu.ps1` | PowerShell 5.1 + C#: finds the window by qemu PID, posts mouse/key messages, `PrintWindow`. DPI-aware. |
| `calibrate.py` | Fits framebuffer px -> window px from fiducials, then proves it with clicks. Writes `calib.json`. |
| `calib.json` | Current mapping + the verification clicks. `verify_*.json`: later verification runs. |
| `emu_input.ps1` | First prototype of the main session. Superseded (not DPI-aware, single moves). Do not use. |

## Commands
```bash
H=tools/harness/emu.sh            # env: DEV, AVD, PKG (debug-broadcast package), TAG (logcat tag), SHOTS
$H info                           # window geometry, mapping in use, is the emulator really foreground
$H click 1300 1900                # pinch-tap at framebuffer pixel (console-screenshot pixel). ~4.5 s per click
$H clicks 1300 1900 1300 1900 -GapMs 100   # several taps in one process (here: a double tap). exit 3 = 50 fps gate failed, nothing sent
$H click 681 1327 -NoFast         # 2 s press at 2-4 fps: clicks Compose buttons, never a spatial tap
$H drag 1013 1075 1413 1075 -Steps 10 -StepMs 60            # held pinch + interpolated moves
$H drag 1080 1079 1280 1079 -Button right                   # rotate camera (see Camera)
$H key w -HoldMs 400              # also keydown/keyup NAME, wheel X Y -Notches -3
$H shot NAME                      # console screenshot 2160x2158 -> .scratch/shots/harness/NAME.png
$H winshot NAME [HWND]            # PrintWindow of the emulator window (works while covered)
$H cmd NAME [floats] [string]     # debug broadcast; also sets the log mark
$H waitlog 'REGEX' 10 [all]       # poll logcat since the last mark (click/drag/key/cmd set it). exit 1 on timeout
$H log 'REGEX' [all]              # lines since the mark;  `all` = every tag, default = $TAG only
$H pose                           # POSE hmd pos=... q(...) fwd=... (needs the app's `pose` command)
$H fps                            # last compositor FPS line;  $H waitfast = make sure a fresh 60 fps period is running
$H calibrate                      # fit + 7 verification clicks;  --no-fit = verify only;  --dist 3 --scale 11 = other depth
```

## How the emulator reacts to posted input (measured)
1. **Frame rate.** Idle = 2-4 fps (`PxrMetric: FPS=2/60`). A posted `WM_KILLFOCUS`+`WM_SETFOCUS` pair makes Qt
   believe it got focus: 60 fps after ~3 s, for ~10 s, then it throttles by itself. A second focus-in inside a fast
   period does not extend it. `WM_ACTIVATE` / `WM_ACTIVATEAPP` alone and continuous mouse moves (500 in 8 s) do nothing.
   `emu.sh` waits for the old period to end, re-arms, waits for `FPS>=50`, and treats the next 5 s as usable.
2. **Gaze** follows genuine mouse moves only. Qt drops the first button-less `WM_MOUSEMOVE` after the pointer
   "left" (always, the real cursor is elsewhere), so `emu.ps1` queues 5 moves within microseconds (`MoveBurst`).
   Posting them one by one from PowerShell lost that race about half of the time.
3. **A press without a preceding genuine move pinches the previous gaze target**, then moves the gaze
   (off-by-one: 9 of 9 taps hit the target of the click before). The harness therefore does burst, 200 ms, burst, 200 ms, press.
4. **Hold time.** At 60 fps a 150 ms press is a tap. At 2-4 fps presses under ~1 s are not sampled and longer
   ones are long-presses: `detectSpatialTapGesture` cannot fire at idle frame rate. Compose `Button` still clicks
   with a 2 s press (`-NoFast`).
5. Result with 1-4 in place: 15/15 taps on the intended entity (cube / floor / wall alternating).

## Calibration (window 1661x1715 physical px, display scale 175 %)
`window_x = 0.768541 * fb_x - 0.121`, `window_y = 0.768646 * fb_y + 56.131` (fit residual <= 0.13 window px;
the framebuffer is stretched over the `subWin` child, 56 px = custom title bar). 1 window px = 1.3 fb px.
Re-run `emu.sh calibrate` when `emu.sh info` prints the geometry WARNING (window resized, other display scale).
Moving the window needs no re-calibration (it was moved by someone during this session; clicks stayed exact).

Clicks on the head-locked calibration panel, intended vs. landed (framebuffer px):
| depth | intended | landed | err |
|---|---|---|---|
| 0.9 m | 346.6, 300.7 | 346.2, 300.3 | -0.4, -0.4 |
| 0.9 m | 1904.8, 318.8 | 1905.2, 317.3 | +0.4, -1.5 |
| 0.9 m | 763.3, 789.4 | 763.3, 788.6 | 0.0, -0.8 |
| 0.9 m | 1488.0, 1296.2 | 1486.2, 1293.9 | -1.9, -2.3 |
| 0.9 m | 328.4, 1857.3 | 327.3, 1855.8 | -1.1, -1.5 |
| 0.9 m | 1922.9, 1875.4 | 1924.0, 1874.6 | +1.1, -0.8 |
| 0.9 m | 1125.7, 572.2 | 1123.8, 569.9 | -1.9, -2.3 |
| 0.9 m | 618.3, 1260.0 | 616.1, 1260.0 | -2.3, 0.0 |
| 0.9 m | 1669.2, 717.0 | 1669.2, 717.0 | 0.0, 0.0 |
| 3.0 m | 314.6, 300.7 | 314.2, 300.3 | -0.4, -0.4 |
| 3.0 m | 1872.8, 318.8 | 1871.3, 317.3 | -1.5, -1.5 |
| 3.0 m | 731.3, 789.4 | 731.3, 788.6 | 0.0, -0.8 |
| 3.0 m | 1456.0, 1296.2 | 1454.2, 1293.9 | -1.9, -2.3 |
Max |err| 2.3 px, mean (-0.7, -1.1) px; identical with the slow (2 s press) and the fast (150 ms) click.
Spatial taps on room planes, red marker centroid in the screenshot vs. clicked pixel: (+5.2,-1.4) wall 4.6 m,
(+0.1,+0.6) floor, (-1.7,-10.6) ceiling 5.3 m, (+1.3,-0.1) floor, (-0.8,+0.1) floor.

## Camera model of the console screenshot (for projecting Stage points to pixels)
Left eye. `fx = fy = 1286.8 px`, principal point `(1080, 1079)`, camera = HMD pose shifted 0.032 m to the
head's left. Head-forward axis lands at x=1125.7 for 0.9 m and 1093.7 for 3.0 m. A pinch ray goes through the
clicked left-eye pixel at every depth (no parallax error between 0.9 m and 3 m).
`px = 1080 + 1286.8 * (x_h + 0.032) / depth`, `py = 1079 - 1286.8 * y_h / depth` (x_h right, y_h up, depth along forward).

## Camera movement (posted, deterministic, reversible by the opposite input)
| Input | Effect |
|---|---|
| `key w/s` or `up/down` | forward / back along the view direction, ~0.45 m per 400 ms hold |
| `key a/d` or `left/right` | strafe left / right, ~0.3-0.4 m per 400 ms |
| `key q` / `key e` | down / up, ~0.45 m per 400 ms |
| `drag ... -Button right` | yaw / pitch, 0.044 deg per framebuffer px (200 px = 8.8 deg) |
| `drag ... -Button middle` | pan, 1.3 mm per px (drag right moves the camera left) |
| `wheel X Y N` | forward ~1 cm per notch |
Keys overshoot slightly; set a pose with drags and read it back with `emu.sh pose`.

## Automatable vs. human-only
| Automatable now | Human-only / not found |
|---|---|
| Pinch-tap on panels, Compose buttons, 3D colliders (`GazePinch`) | (was: controller mode. Posted clicks cannot choose an item of the mode menu; `emu_mode.ps1` now switches the mode through UI Automation, see the product section above.) |
| Pinch-drag on a panel (Compose `Move` events) and on an entity (`detectSpatialDragGesture`) | Webcam hand tracking (`HandTrackingProvider` joints) |
| Camera translate / rotate / pan | Two-handed gestures (scale / rotate) |
| 60 fps on demand, complete screenshots, window captures | Anything that needs real focus |

## Pitfalls
- **Do not read click targets off a screenshot by eye.** The spike HUD buttons were estimated at (531,1282) and
  (660,1282); measured (HUD on/off image difference) they are at (551,1327) and (681,1327), HUD rect x 462..987,
  y 1123..1377. Clicks at the estimated points landed on the panel at local y=277, above the button row (y~381),
  and did nothing. This is the whole "58 px vertical offset" seen before calibration: the mapping has no offset.
  Get targets from image processing, from app-logged bounds, or from the camera model.
- Take the screenshot and click inside the same camera pose. The pose changed twice without any harness input:
  21:02:50 rotation (yaw 22.5 deg, pitch 18 deg = a 395 px right-button drag) and 21:40:47 translation 0.66 m
  backward along the view direction (= ~0.6 s of `s`). The window was also moved on screen around 21:07. Somebody
  else drives this emulator window. Read `emu.sh pose` before computing targets; never hard-code pixels of
  world-locked content.
- Drag distance is not the on-screen distance. Pinch-drag moved the pointer 2.13x (panel at 0.9 m, 800 fb px ->
  904 panel px on a 3.3x scaled panel) and an entity 1.87x (cube at 1 m, 400 fb px -> `total(px)=(1327.8, 0, 548.2)` = 0.57 m).
  Assert on app state, or measure the gain for the case under test.
- A new or resized AttachmentPanel can show only its top rows for many seconds at 2-4 fps (stale rows from the
  previous size below). `calibrate.py` polls the screenshot until all 9 fiducials are complete. The host window
  (`winshot`) lags the framebuffer (`shot`); poll it as well.
- Panels are clipped at 720 dp height (800 dp square showed 1600x1440 px).
- Nothing may stand between the head and the calibration panel (the cube at 1 m took the clicks). Move it away first.
- `pico-cli capture screenshot` returns partial frames at idle fps. Use `emu.sh shot`.
- PowerShell: negative positionals are parsed as parameter names (`wheel X Y -Notches -3`).
- Each `emu.sh` host action costs ~1 s (PowerShell start + C# compile). Waiting is done on logcat, not with sleeps.

## What the app must provide (the spike has it; the product debug build needs the same)
- Debug receiver commands: `calib on|off|clear` (`a="dist,scale,sizeDp"`), `pose`, `gest both|tap|drag|none`.
- Log lines (tag = `$TAG`): `CALIB panel attached`, `CALIB ev=Press|Move|Release x= y=` (panel-local px),
  `POSE hmd pos= q() fwd=`, one line per gesture callback with target entity name and Stage position.
- Unique entity names (`setName`): the system logs `HOVER_ENTERED <id> <name>` and `SELECT_ENTERED touch down <name>`.

## Changes made to the spike for this harness
`SpikeStage.kt`: `CalibPanel` attachment, `detectSpatialDragGesture`, rate-limited `PTR` log, `gestureMode`.
`SpikeWorld.kt`: `calib` / `gest` / `pose` commands, `contentProbe` entity, `onTap` logs Stage metres, target-local
position and the analytic plane hit, `onDrag*`. Originals are in `.scratch/backup_pre_harness/`.

## State left behind
Emulator settings unchanged (`controller_settings.ini` identical, mode Eye Gesture, no popup left open).
My camera test motions were reversed (back to pos (-0.015, 1.389, 0.007) q(-0.056, -0.196, -0.011, 0.979) at 21:40:12);
the pose is now pos (-0.267, 1.465, 0.612), same rotation, because of the external move at 21:40:47.
Throttled again with `fast off` (FPS=2/60). Spike reinstalled with the changes above; cube back at
(0.35, 1.25, -1.0); HUD counter `clicks=101` from the two final button checks.

## Round 3 (dev agent, 2026-10-02)

- Menu rows with the cursor hijack excluded: a primed `clicks` sequence (catcher, More key, row; `-GapMs 1500`)
  keeps the 60 fps period open, the first press goes to the catcher (`CALIB ev=Press`), the next two are not
  hijacked. Native popup rows (fb 760, 1420 "Clear all…", 760, 1496 "Exit"): 1 / 3 and 1 / 3 acted, the others
  only dismissed the popup (`STATE idle`, no `UI` line) - the popup takes every press first, so the catcher cannot
  exclude H1 while it is open, but the primed sequence does. Attachment-panel menu (`overlay menu panel`, now the
  default; rows from `bounds` `menuPanel` `menu_clear` / `menu_exit`): 3 / 3 "Clear all…", 1 / 1 "Exit"
  (`UI menu_exit`, `STATE exited`, `STAGE close requested`; the process stays as before).
- R2-2 with real pinches: More key, then a pinch on the floor 0.6 m from the menu: `TAP surface ... ` followed by
  `TAP consumed reason=menu-open event=surface`, `STATE idle`, no pin (2 / 2).
- R2-4 with a real primed drag (`r2.py "pdragat 0.496 0.4151 -2.301 1.1 0.4151 -2.301 14 60 300"` on a selected
  pin with fine-tune open): `PIN true ... du=229 dv=50`, `DRAG end`, 0.6 s later `ANCHOR rebase start`, then
  `ANCHOR rebase id=p270 uuid_old=... uuid_new=... shift_mm=0`, `ANCHOR removed uuid=<old>`; `dump` shows
  `off=[0, 0]`, `save=saved`, the tape's new length. The coachmark panel covers the pin when it stands there:
  dismiss it first (`coach_got_it` on `coachPanel`, a real pinch works) or place pins farther right.
- T11 (head turns by `drag ... -Button right`, 0.044 deg / px): 455 px = 20 degrees, no `BODY` line, the body
  stays in the room; 1136 px more = 70 degrees: `BODY follow reason=angle angle=88.1 dist=0.60` within 0.5 s,
  `BODY park reason=slide` 351 ms later, body back at 18 / 22 degrees. The pose at 2026-10-02 18:00 is
  pos (0, 1.636, 0), identity; the floor quad is at y = 0.4006 (`planes`).
- Reticle shots: `tools/test/r3.py` (`shot_at(name, point)` crops around a Stage point);
  points on the floor more than about 1.9 m ahead are inside the frame from the start pose.
- Helpers `tools/test/t2a.py`, `aim.py`, `r3.py` (copied from the dev agent's scratchpad). Screenshots: `.scratch/shots/round3/` (reticle_close, reticle_far, chips_over_plates, body_after_head_turn_20,
  body_after_slide_back_70, coachmark_step1 / step2, menu_exit_path, no_source_snack).
- Round-3 regression fixes (19:15): R3-1 real primed drag on p284 (`du=227 dv=123`, rebase) then a primed real
  Undo pinch (catcher + `undo` key): `UNDO depth=2`, `ANCHOR rebase start id=p284 ... pos=(1.3000, 0.4006, -2.4000)
  reason=undo`, `ANCHOR rebase ... shift_mm=0`, `dump` p284 back at (1.3000, 0.4006, -2.4000), t219 120.0 cm as
  before. R3-2: `source controller` + `pointer` + `trigger`, `hand_lost`, a gaze press 2.4 s later (`PTR panel=body
  ev=Press`): no `SOURCE lost` in 5 s; controller mode (`emu_mode.ps1 -Mode right`, two `move`s) then `-Mode eye`:
  `SOURCE lost` + sticky snackbar after 3 s, the first gaze pinch (`TAP surface`) logs `SOURCE back`, `dump
  snack=None`. R3-3 left as specified: the slide target is the designed pose for the head at the moment the slide
  starts (`BodyView.startSlide` -> `designedFor(head)`), so a turn that goes on after the slide ends with the body
  off the final heading; `body park` re-parks.

## Round 5 (dev agent, 2026-10-05): pin drag

- Names: `pin_tune` and the panel `pinTagTune` are gone; `pinTag` is 304 x 128 with `pin_remove pin_close step_u_minus
  step_u_plus step_v_minus step_v_plus`. New entities `stub:<pinId>` (the tape-head stub's touch sphere, a drag on
  it pulls a tape) and `pinGhost`. Logs `PIN drag start|move|end|release|gain`, `DRAG begin mode=pin`.
- A real drag of a pin on the emulator: a primed drag (`pdrag.ps1`, catcher click + drag) from the pin cap's pixel
  (`rt.project(pin + normal * 0.0145)`); the line of sight to the cap must be clear (the bed and the niche wall
  occlude much of the floor from the start pose: `rt.first_hit` over `planes` finds a clear floor point, see
  `r5.py` in the dev agent's scratchpad), and a drag under ~2 degrees does not start at all (H3). Measured: a 132 px
  cursor move (4.5 degrees) moved the pin 0.31 m along the floor at 2.3 m, i.e. the pin stays under the cursor;
  the slow drag (24 steps x 60 ms over 70 px, under 2 deg/s) logs `PIN drag gain ... gain=1:4` within 150 ms and
  shows "×4". `.scratch/shots/round5/`: s01 mid-drag (stretched tape, live plate, strip), s02 after release, s03
  slow drag with the reticle, ghost ring and ×4, s04 the strip after the drag.

## Round 5b (dev agent, 2026-10-06): pin-drag feel (QA R5-1 .. R5-4)

- Names and logs: `PIN drag gain id= gain=1:4|1:1 speed= reason=still|fast` on every change of the gain (`still`:
  the line was under 1 deg/s for 250 ms, `fast`: the first event over 4 deg/s); `DRAG start target=stub:<pin> ...
  retarget=pin:<pin>` when a press named the stub but lay inside the pin's touch sphere (it moves the pin); entity
  `pinGhostCase` under `pinGhost`; `emu.sh drag ... -PauseAtStep n -PauseMs m` rests the pointer after the n-th step.
- The system's drag travel on the emulator (eye + hand mode) was measured from the raw `DRAG end ... hand=` over the
  cursor's view-plane displacement after the press point (`DRAG start ... stage=`, where the system recognised the
  drag, 24 - 37 px along the way; the cursor's pixel -> view-plane mapping is a pinhole: taps at every test position
  landed within 2 px). Metres of travel per unit of view-plane displacement (at unit depth): 2.55 on the bed top
  (press 1.18 m ahead, 0.76 m below the eye; 8 drags, +-3 %, sideways and away), 4.08 on the floor right of the bed
  (1.90 ahead, 1.21 below), 5.05 on the slanted wall (3.19 ahead, 0.54 below; 2 drags). Neither a multiple of the
  depth nor of the distance nor an affine rule in the depth fits all three (25 - 40 % off at one of them); the rule
  `1.52 x (z^2 + y^2) / z` fits all within 2 % (`DragPinUseCase.equivalentDepth`). The travel is fixed at the press:
  an away drag over the bed (1.43 -> 1.73 m) gave the same travel per unit as a sideways one. Drags that cross a
  surface edge under the cursor (floor -> bed top, dragged leftwards from the floor) gave 13 - 44 % more travel with
  a large spread; two drags towards the camera ending within 130 px of the frame's bottom edge gave +20 % (edge
  artefact; the pin then lands 10 - 11 px long). The emulator renders the drag travel with a tail (about 93 % 0.5 s
  after the mouse stops, 99 % after 1.5 s).
- Harness notes: with the emulator window minimised there is no cursor hijack - a catcher click lands where it is
  posted (it placed a stray pin on the bed), so drive drags with `emu.sh drag` directly (no `pdrag.ps1`). `place`
  arms the new pin (stub out): deselect it with `tap_pin <id>` before pressing its cap, or the press is a stub press.
  The body panel stands at the lower left of the view (about px 414 - 1042 x 1500 - 1770 from the start pose) and
  swallows presses behind it (`TAP blocked by=body`). Visible points from the start pose: bed top (0, 0.866, -1.2)
  at 1.43 m (px 1114, 1920), the floor right of the bed around (1.0, 0.40, -1.9) at 2.5 m (dragged leftwards), the
  slanted wall (-1.1, 1.1, -3.28) at 3.50 m (px 661, 1295). The pin's final pixel is `rt.project(pos + n * 0.0145)`
  against the cursor's end pixel; `r5b.py` in the dev agent's scratchpad runs the whole smoke (`t1 t2 t3 t4 t5 t6`).
- Measured on the final build (`r5b.py t1 t3 te`, then `t2 t4 t5 t6`; cursor-to-pin error = the pin's resting cap
  pixel against the cursor's end pixel, along / across the drag): 1.43 m bed top sideways 150 px -> 6.5 px (+1.2 /
  -6.4); the selected pin pressed again at 1.44 m -> `DRAG begin mode=pin`, 0.1 px; 2.52 m floor sideways 150 px
  -> 5.8 and 5.2 px (with two tapes; plates 50.1 / 45.7 cm live mid-drag, s01); 3.50 m wall sideways 150 px -> 2.6
  px; 1.43 m away 150 px -> 6.0 px; 1.43 m towards the camera 110 px (ending 130 px from the frame edge) -> 11.0 px.
  Pause-then-quick (150 px, 1.0 s rest, 100 px in 240 ms): `gain=1:4 reason=still` during the rest, `gain=1:1
  speed=6.6 reason=fast` on the quick move, the pin moved 88 px for the quick 100 (the slow first and last events
  of the emulator's smoothed motion are the fine part), 8.5 px short in all. Sustained fast 250 px -> 7.8 px gap at
  the end. Undo after a drag: 0.0 mm. Shots: s00 before (three pins, two tapes), s01 mid-drag (reticle over the
  pin, x4, live plates, no chips), s02 after release (strip, stub, plates), s03 ghost ring on the floor (zoom).

## Round 5c (dev agent, 2026-10-06): head pitch, gain pauses, plate hover, no lag

- Travel model refit with the head pitched (`r5c_measure.py` in the dev agent's scratchpad: `rot(0, pitch)` by
  right-button drags at 0.044 deg/px, points A bed top 1.43 m, B floor 2.52 m, C wall 3.50 m, sideways and away,
  pitch 0 / -20 / -40): the round-5b rule in the head's own frame under-predicted the travel by up to 30 % once
  the head was pitched (A at -40: predicted 2.14 m per unit, measured 2.72 / 2.84). Measured travel per unit of
  view-plane motion barely depends on the pitch: A 2.51 - 2.64 (level) / 2.56 - 2.70 (-20) / 2.72 - 2.84 (-40);
  B 4.08 / 3.99 - 4.31 / 4.12 - 4.30; C 5.04 - 5.21 / 5.27 - 5.43 / 5.47 - 5.82; QA's floor points at yaw 88,
  pitch -38: 2.59 (1.41 m), 4.13 (2.49), 5.40 (3.50). A first fit over these 33 drags, `(0.74 + 1.31 x
  distance) x (1 + 0.0015 x |pitch deg|)` (rms 2.4 %), left 250 px drags resting 3 - 22 px short while 150 px
  drags were right: the 250 px drags deliver about 5 % less travel per cursor pixel. Solving 150 / 250 px pairs
  for a common start shows the system's travel counting from about 13 px before the press point it reports
  (the emulator's smoothed hand lags the cursor). Joint fit over the 36 verification drags (`v1_results.json`):
  start 13 px short (`START_SHORT_U` = 0.0101) and `(0.67 + 1.186 x distance) x (1 + 0.0015 x |pitch|)`, rms
  5.9 px against both lengths; identical drags repeat within about +-4 %, which bounds what any rule can do.
- Gain (QA R5b-2): fine needs 400 ms under 1 deg/s AND 250 ms without any raw step (`AdaptiveGain.sample(speed,
  moved, now)`); a 100 - 200 ms event pause inside a move no longer engages it.
- Plate "dimmed" (QA R5b-3) is the SpatialUI hover tint: `emu.sh move` onto a plate turns it light grey
  (`round5c/h01_hover_on_plate`), it stays grey after the pointer leaves until a later pointer event
  (`h02`, `h04`). During a pin drag the captured pointer crosses plates near the pin and the tint stays. Fix:
  no `spatialHoverEffect` on plates while `RulerUiState.pinDragging` (`ReadingPlate(hover = ...)`).
- R5-6 (out 100 px, back to the start pixel): with the start line through the cap the press offset is undone,
  but the pin still rests about 40 px out (`m1_p0_B_outback_*`, 62 - 70 mm): the return's first event after the
  1.2 s rest is one batched step of 40 - 50 px timed over the rest (reads 1.4 deg/s), so it is applied at 1 : 4;
  the next event lets go (`speed=5.3 reason=fast`). The disengage rule was to stay unchanged, so this waits
  (the one-line fix would be to time the first event after a pause over the event cadence, not the pause).
- No in-app smoothing on the system drag path any more (`stepPinDrag`: the pin stands where the events put the
  line; tracked pointers keep the 30 ms lag). `emu.sh drag ... -Return [-ReturnMs m]` goes back along the path.
- Final build, `r5c_measure.py v2 table` (yaw 0; A bed top 1.43 m, B floor 2.52 m right of the bed, C wall 3.50 m;
  150 px in 10 x 60 ms, 250 px in 12 x 60 ms, 2.5 s hold; error = resting cap pixel against the cursor's end
  pixel, + = beyond the cursor; the C away-250 path at -40 ran within 73 px of the frame's top edge):

  | pitch | dir | rest after 250 px (along): A / B / C | release after 150 px (total, along): A / B / C |
  |---|---|---|---|
  | 0 | side | +1 / +15 / -6 | 10 (+8) / 9 (+8) / 0 (-0) |
  | 0 | away | -10 / -4 / +2 | 6 (-2) / 5 (+3) / 5 (+5) |
  | -20 | side | -3 / +6 / -4 | 5 (+1) / 4 (+2) / 1 (-0) |
  | -20 | away | -3 / -6 / +10 | 6 (-1) / 3 (+0) / 4 (+4) |
  | -40 | side | -2 / +3 / -6 | 5 (-0) / 5 (-4) / 1 (-1) |
  | -40 | away | +5 / +1 / +13 | 5 (+2) / 3 (+3) / 9 (+9) |

  250 px: 14 of 18 within 10 px (median 5.1, worst +15 at B level); 150 px: 15 of 18 within 8 px (median 4.9,
  worst 9.8). Out-and-back 100 px with a 2.5 s hold: back to 4.0 px (0.0 along), i.e. R5-6 holds when the
  emulator's tail has arrived; released at once: 21.6 px out (the last 20 px of the return never arrive).
  Pause 1 s then 100 px: the pin moved the quick 100 px in full (274 of 250 in all: B at the level pose gives
  5 - 6 % more travel than the rule); no `gain=1:4` during the 1 s rest, because the emulator's tail keeps
  sending 1 px events for 1.5 - 2 s after a move and the new rule needs 250 ms without any raw step, so on the
  emulator x4 engages about 2 s after a fast move stops (0.7 - 0.9 s before). Two tapes on the dragged pin:
  plates live and black mid-drag and after release (`s01_mid_drag_two_tapes`, `s02_after_release_two_tapes`);
  `s03_mid_drag_pitch40` the reticle over the pin and the ghost ring at -40. Hover check `h01` - `h04`.

## Round 5d (dev agent, 2026-10-06): micro-round after QA round 5c

- Gain per event: an event's speed is its step over at most 100 ms (not over the rest before it), so the first
  event of a quick move after a rest goes 1 : 1; fine engages only after 400 ms without any raw step >= 1 px
  (`AdaptiveGain`, `MOTION_PX`), which on the emulator means after its event tail (about 2 s after a stop).
- Plate hover: off during a pin drag and until the plate's next pointer Enter after it (`RulerScene.plateHover`).
- Measured (`r5d_micro.py`, `.scratch/shots/round5d/`): pause 1 s then 100 px -> the pin moved 112 px for the
  quick 100 (B level, 156 -> 268 px; the whole 250 px drag +18 px, B gives 5 - 6 % more travel than the rule);
  looking down (-40) at B 2.5 m, 250 px fast -> rest error +0.6 px along (3.3 total); two tapes, drag across,
  2 s after release both plates solid (`s01_after_release_two_tapes`). No `gain=1:4` inside 2.5 s holds.

## Round 5e (dev agent, 2026-10-06): deferred placement (user report: the first tap after a relaunch placed nothing)

- A system tap that hits only the backstop is kept as the pending aim (`TAP backstop ... pending=true`, `TAP pending
  at= dir= scanning= replaced=`, `SNACK not_mapped`, the body's spinner, no `FEEDBACK fail`); the ViewModel re-tries
  the line of sight against the analytic planes on every `PlanesChanged` and every 300 ms; a hit places the pin
  through the normal path (`TAP pending resolved after_ms=`, `PIN add ... via=system deferred=true`); after 15 s
  without a surface `TAP pending expired` + `SNACK no_surface`. Orphan anchors at launch: one `RemoveOrphans` batch
  removed one at a time 5 s after the launch (`ANCHOR removed ... orphan=true`).
- Reproducing the launch race on the emulator (`r5e_pending.py`): the scene attaches about 1.7 s after `STAGE enter`
  (`SCENE attached build_ms=`), the planes come 0.03 - 4 s later (`PLANES n=57`); a tap before the attach is not a
  spatial tap at all (no collider yet). `emu.sh click` waits for the fps gate, which the launch keeps low for many
  seconds, so the script calls `emu.ps1 click X Y -FastWaitMs 350 -SettleMs 100 -Avd PICO_6.1_Ruler` directly as
  soon as `SCENE attached` is logged (the press lands about 1.8 s later); an empty scene before each relaunch, or
  the restored pin takes the tap. About 2 launches in 7 have the planes late enough.
- Evidence (emulator-5556, 19:57): `TAP backstop ... pending=true` 19:57:57.465 -> `TAP pending ...
  scanning=true replaced=false` + `SNACK not_mapped` 57.466 -> `PLANES n=57` 58.551 -> `TAP pending resolved
  after_ms=1089 plane=BED:0b4bbaf1` + `PIN add id=p628 ... via=system deferred=true` + `STATE pulling` 58.555, no
  `FEEDBACK fail`. When the analytic planes are already known at the tap (the collider was not up yet) the pin is
  placed at once (`PIN add ... via=system` right after `TAP backstop ... pending=true`, attempt 4). Final APK
  (20:00, `evidence_final.txt`, shot `round5e/s01_deferred_pin`): `TAP backstop ... pending=true` 20:00:32.573 ->
  `PLANES n=57` 34.556 -> `TAP pending resolved after_ms=1985` + `PIN add id=p629 ... via=system deferred=true`
  34.558, `FEEDBACK pin_drop`, no `FEEDBACK fail`.


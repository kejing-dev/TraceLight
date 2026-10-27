# Furniture Fit (家具试摆)

MR furniture-fitting app for PICO OS 6. Kotlin, PICO Spatial SDK 6.1.9 (template 6.1), one Mixed Stage
(Full Space only, no Shared Space version), package `io.embodit.tracelight`. Tap a tile and a piece of
furniture lands 1:1 on your real floor; hold and drag it, it snaps to walls; yellow tapes read the clearance on
every side; it turns red when it goes into a wall, another piece or real furniture; orange when it blocks a door.
v0.1.1 (real-device feedback, taste profile D): the controller thumbstick X turns the selected (or dragged) piece about
its own vertical axis; a piece moved or turned near a real-furniture dashed outline snaps onto it (centre and heading)
and then stands in for that furniture (no collision with it); the strip shows one hint line 「摇杆左右转动」.

Design package (binding): `../spatial-app-factory/apps/furniture-fit/design-package.json` (states S1 - S22,
rules, panels, entities, palette, copy). Approved sample: `../spatial-app-factory/apps/furniture-fit/sample.html`.
Skeleton D, built from Spatial Ruler (`../spatial-ruler`) and its port in `../jieyan` (panel pool, Stage root,
plane colliders, world anchors, gesture conversion, theme, fonts).

## Structure

```
app/src/main/java/io/embodit/tracelight/
  Main.kt                 DefaultStage { TraceLightTheme { FitScreen() } }
  domain/                 pure Kotlin, JVM-tested
    Vec3.kt Geometry.kt   vectors, P2 (floor plane), Prism (upright box), Geo2 (SAT, rays, min rect), FacePanel
    Room.kt               PlaneSurface, MeshPoint, RoomModel.build: floor, 4 cm slabs behind vertical planes,
                          door zones (0.8 m), real furniture from spatial-mesh clusters (15 cm cells)
    Furniture.kt          Templates (11 tiles), Piece, Recipes (basic-shape looks, <= 12 boxes)
    Fit.kt                FitRules: check (collide / door / out of room), gaps, snap, pushOut, rotate, placement, inside
    DragLine.kt           system-drag model (Spatial Ruler round 5c): pointing line + horizontal plane at the grab height
    Layout.kt Settings.kt saved layout (one room anchor + local poses), JSON codec, units
  data/Stores.kt          AnchorGateway / LayoutStore interfaces, SharedPreferences store
  platform/               TraceGraph (services), TraceLog, HeadTracker, RoomSensing (PlaneTracker, MeshTracker),
                          WorldAnchorGateway, LaunchActivity, SpatialApplication
  ui/fit/                 FitUiState (+ designState S1..S22), FitEvent, FitViewModel (all behaviour), FitScreen
  ui/fit/scene/           FitScene (ECS: one registered System, panel placement, drag, gaps), PanelRig (pool),
                          PieceView, RoomViews (gap tape, floor rects, room colliders), SceneKit (materials, meshes)
  ui/fit/components/      SpatialUI panels: LibraryPanel, StripPanels (piece strip, keypad, gap plate),
                          Overlays (more menu, dialogs, snackbar, coachmark), FitStage (SpatialView + attachments)
  ui/theme/               TraceLightTheme (PicoTheme + 4 semantic roles + Noto Sans / Noto Sans SC), TraceColors, TraceType
app/src/debug/            DebugCommandReceiver + DebugCommands (QA hooks)
app/src/test/             30 JVM tests (FitRulesTest, FitViewModelTest)
tools/                    dev.sh, cap.sh, qa_shot.sh, qa_tour.sh, contact.py, icons_from_sample.py,
                          subset_cjk_font.py, harness/ (emulator input: camera turn, pinch, drag; from Spatial Ruler)
```

One direction of data: panel controls and scene taps / drags -> `FitViewModel.onEvent` -> `FitUiState` ->
`FitScene.render` (entities, panels). Per-frame work (drag, live snap, live collision colours, gap tapes and
plates, the inside check, lazy follow, animations, mesh lifecycle) is in `FitScene.onFrame` only; the only
Compose state it writes is `panelShown` and `gapPlates`, on change.

## UI rules

- All 2D UI is SpatialUI inside `PicoTheme`. No Material / Material3 (gate: `../spatial-app-factory/bin/material-grep.txt`).
- Every panel is a pre-built Stage attachment panel (AlertDialog / SnackbarHost do not work in a Stage).
- Icons are vector drawables made from the sample's `<symbol>`s by `tools/icons_from_sample.py`.
- Copy in `res/values` / `res/values-zh`; read through `rs()` (`LocalFitResources`) so the language switches live.
  After changing zh copy run `python tools/subset_cjk_font.py`.

## Build, run (Git Bash; device commands are pinned to emulator-5564 / AVD PICO_6_1_Factory3)

```bash
tools/dev.sh build && tools/dev.sh install && tools/dev.sh launch
tools/dev.sh log 'STATE|PIECE|ROOM' 40
tools/dev.sh crash
./gradlew.bat testDebugUnitTest      # with JAVA_HOME = the primer JDK 21 (see tools/dev.sh)
tools/cap.sh NAME                     # console screenshot -> .scratch/shots/NAME.png
tools/qa_shot.sh S8                   # one design state -> .scratch/shots/S8.png
tools/qa_tour.sh                      # all 22 states + .scratch/shots/tour.png + tour.txt (reached state per shot)
tools/harness/emu.sh drag 800 1000 1700 1000 -Button right   # turn the emulator camera ~40 deg right
```

QA tips (verified 2026-10-21):
- The emulator room has a big bed straight ahead of the start pose. Turn the camera about 40 degrees right (the drag
  above, 900 px of right-drag ≈ 39.5°) before the tour: the debug states then find free floor in view
  (`DebugCommands.ahead`). The Stage re-centres on the head at every launch, so a relaunch keeps that view.
- S9: the room's only door is about 54° further right of that view (`room` prints `DOORZONE`): right-drag 1230 px,
  shoot S9, drag back.
- Panels lag 3 - 10 s behind a state change on the emulator: wait ≥ 11 s (`qa_tour.sh` default) or shoot twice.
  S15: the "n pieces restored" snackbar reaches the screen 2 - 6 s after the rise; run `snack long` first and shoot
  again a few seconds later. S18 lines up twelve bookcases in view; some touch the room (red) - the state is about
  the tiles (12/12, disabled), not the verdicts.
- The emulator region is en-US, whose default unit is inches (design `i18n`); `qa_tour.sh` switches to cm first.
- Real input works: `tools/harness/emu.sh click X Y` on a piece selects it (`TAP piece`), on the floor deselects
  (`TAP room`), `emu.sh drag X1 Y1 X2 Y2` on a piece moves it (`DRAG begin ... DRAG end`, `PIECE move`).
  X Y are console-screenshot pixels scaled to 2160 wide (x 0.844).

## Debug commands (debug build only)

`adb -s emulator-5564 shell "am broadcast -a io.embodit.tracelight.DEBUG -p io.embodit.tracelight --es cmd NAME [--es s TEXT] [--es a NUMBERS]"`
or `tools/dev.sh cmd NAME [TEXT] [NUMBERS]`.

| cmd | args | effect |
|---|---|---|
| `state` | s=S1..S22 | jump to that design state (resets to an empty calm screen first) |
| `place` | s=templateId [a=x,z[,yawDeg]] | a piece like a tile press, or at a Stage point |
| `select` / `tap` | a=id / - | select a piece / tap the empty room |
| `drag` | a=id,x,z[,ms] [s=hold] | the piece glides to (x, z) through the live drag path (snap, colours, tapes); `hold` stays in S6 |
| `drag_end` | | release the glide |
| `stick` | a=x[,ms] | the thumbstick reads x (-1..1, right +) for ms (default 1000); same path as the controller |
| `ui` | s=control | press a control: `undo more rotate remove size key_0..key_9 key_back key_ok row_0..2 tile_<id> look_solid look_wire lang_system lang_zh lang_en unit_cm unit_in menu_clear menu_exit dialog_cancel dialog_confirm blocked_exit blocked_retry snackbar_action coach_ok` |
| `obstacles` | s=on/off | draw all obstacle geometry (cyan boxes) and door zones |
| `coach_reset` / `restore_timeout` / `restore` | | first-run coach again / S16 now / restore from storage again (S15) |
| `tracking` | s=lost/ok | S19 |
| `blocked` | s=on/off | S20 |
| `starting` | s=on/off | hold S1 |
| `nofloor` | s=on/off | rebuild the room without its floor (S2) |
| `fail_next_anchor` / `hold_anchor` | - / a=ms | S17 / S21 |
| `snack` | s=long/normal | timed snackbars stay 60 s |
| `clear` | | remove all pieces without the dialog |
| `dump` | | `DUMP {json}` (designState, pieces with verdicts and hits, gaps of the selected piece); also files/dump.json |
| `pose` `panels` `planes` `mesh` `room` `bounds` | | read back head pose, panel slots, planes, mesh semantics, obstacles, control bounds |

## QA screenshot list (design-package `states[]`)

S1 starting (library spinner) · S2 scanning (`nofloor`) · S3 empty · S4 placed · S5 selected (strip + tapes + plates)
· S6 dragging · S7 snapped (yellow contact bar) · S8 colliding (red + strip ✕) · S9 door (camera must face the door:
the emulator room's door is at x = 6, z = 0.4) · S10 sizing (keypad) · S11 inside (piece at the head, 25 %) ·
S12 wireframe · S13 more menu · S14 confirm clear · S15 restoring (rise + "n pieces restored") · S16 elsewhere ·
S17 unsaved (white dashes + Retry) · S18 limit (12/12) · S19 tracking lost (grey) · S20 blocked dialog ·
S21 saving (yellow dashes) · S22 coachmark.

## Log (tag `TraceLight`)

`STATE Sx name` · `PLANES add` · `MESH add|tracking started|stopped|restart` · `ROOM floor= walls= real= doors=` ·
`PIECE add|move|rotate|resize|remove` · `TAP piece|room|other` · `DRAG start|begin|move|end|refused` ·
`ANCHOR create start|saved|failed|removed` · `SAVE` · `RESTORE` · `SNACK` · `COACH` · `UI <event>` · `TRACK lost|recovered` ·
`BLOCKED` · `FOLLOW` · `RIG slot` · `PTR panel=` (debug) · `CMD` · `DUMP` ·
`STICK start|turn start|turn end` · `PIECE turn|outline` · `SNAP outline` (v0.1.1). `FRAME` lines carry `stick=support=… actions=… x=…`.

## Stick turn and outline snap (v0.1.1)

- Input: `platform/tracking/ControllerStick.kt` - `ControllerTrackingProvider` + `ControllerActionListener` (SDK doc
  "Obtain controller input actions", Full Space only); the listener stores the X of the stick pushed further; the ECS
  system reads it once per frame (`FitScene.stepStick`). Started / stopped with the head tracker (attach, pause,
  resume, detach). Hand gestures are a separate path and unchanged.
- Rules (`domain/Fit.kt`, JVM-tested in `StickAndOutlineTest`): dead zone 0.15, square curve up to 90 deg/s; stick right
  = clockwise seen from above (yaw down, like the 90° key). Outline snap: centre within 20 cm of a real outline's
  centre and heading within 12° of one of its sides (k x 90°) -> onto it; `settle` = outline snap, else face snap; a
  piece exactly on an outline (1 cm, 1°) does not collide with, gap to or face-snap against that furniture.
- Live: the turn is drawn live and written once 150 ms after the stick is back in the dead zone (`FitEvent.PieceTurned`,
  push-out + settle + save, one undo step). While dragging, the stick turns the dragged piece; the heading rides in
  `DragEnded`. Real outlines within 60 cm of the focus piece are drawn as white dashes (snap targets), red when hit,
  and flash yellow and wider for 0.8 s when a piece lands on one; the piece's footprint is taped down again.
- Not turned while: no floor, tracking lost, blocked, elsewhere, clear dialog, menu open, keypad open.

## Runtime facts (emulator, SDK 6.1.9, 2026-10-21)

- Planes and the spatial mesh arrive within a second on a warm emulator (57 planes, 48 mesh anchors). Mesh
  semantics seen: UNKNOWN, PLANT, CURTAIN, BED, CABINET, LAMP, WALL, WINDOW, DOOR, CHAIR, WALL_ART, FLOOR;
  semantics are per vertex (list size = vertex count). No SOFA / TABLE in the emulator room's mesh.
- The emulator room's FLOOR plane is at y = 0.40 (Stage), the head starts at y = 1.65.
- `WorldTrackingManager.createAnchor` answers -6 "provider is not ready" until the app has subscribed to anchor
  updates; `WorldAnchorGateway.start()` subscribes at scene attach and creation retries -6 for a few seconds.
- Spatial Ruler's panel-pool rules apply (see `ui/fit/scene/PanelRig.kt`).

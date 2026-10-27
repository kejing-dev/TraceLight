# Trace Light (透写台)

MR tracing app for PICO OS 6 (Swan). Kotlin, PICO Spatial SDK 6.1.9, one Mixed Stage (Full Space,
passthrough), package `io.embodit.tracelight`, v0.2.0. Lay any sheet of paper on the desk, line a frame up
with it by hand, pick a drawing and its guide lines appear on the paper step by step; you trace them with a
real pencil. Free drawing (with an optional grid) too.

Design package: `../spatial-app-factory/apps/trace-light/design-package.json` (skeleton D, Stage·Mixed).
v0.2.0 follows the user's decisions of 2026-10-23 / 26 over it: alignment by hand only (camera
auto-detection was tried and removed: "效果很垃圾"), any paper size, no spike / calibration flow, straight to
drawing, a rich library. Built from Furniture Fit (`../furniture-fit`): panel pool, Stage root, plane
colliders, DragLine, thumbstick turn, world anchors.

## Flow (design states, QA screenshot names)

S00 loading · S01 welcome · S02 finding the desk · S03 drop the frame (ghost A4 frame on the desk under the
head ray; pinch) · S04 drag the corners (① near left ② near right ③ far right ④ far left, plus a centre
handle that moves the whole frame; thumbstick left / right turns it; 对好了 always enabled) · S06 aligned
(0.8 s) · S07 library (6 sets × 5 drawings, easy → hard; 自由画) · S09 tracing (current step bright blue,
earlier steps faded, an amber pen dot walks the strokes in order) · S12 done (下一张 / 回目录) · S14 free
drawing (grid toggle) · S16 no desk. ⋯ in the drawing panel: 重新对齐 (snaps onto the old sheet within
15 mm / 5°; 取消 returns), 自由画, 回目录.

## Structure

```
app/src/main/java/io/embodit/tracelight/
  Main.kt                 DefaultStage { TraceLightTheme { TraceScreen() } }
  domain/                 pure Kotlin, JVM-tested
    Lessons.kt            the library: a drawing DSL (line, poly, rect, ellipse, curve = Catmull-Rom, bez)
                          on a 100 x 140 canvas, 6 sets x 5 pieces, LessonFit (canvas -> any sheet, 15 mm margins)
    Paper.kt              PaperPose, CornerDraft, SheetRect (any-size rectangle fit), FrameSnap, Stick, PaperFit
    Surfaces.kt           PlaneSurface, raycast, Desks (desk filter)
    DragLine.kt Vec3.kt Geometry.kt Settings.kt
  data/Stores.kt          settings + finished pieces (SharedPreferences), AnchorGateway
  platform/               TraceGraph, TraceLog, HeadTracker, PlaneTracker, ControllerInput, WorldAnchorGateway
  ui/trace/               TraceUiState (+ events), TraceViewModel (all behaviour), TraceScreen
  ui/trace/scene/         TraceScene (ECS: one registered System), GuideViews (drawing meshes, pen dot,
                          grid), PaperViews (corner handles, outlines, sheet meshes), PanelRig, SceneKit, DeskColliders
  ui/trace/components/    SpatialUI panels: LessonPanel (setup S00 - S06, S16), DrawPanel (S07 - S14), TraceStage
app/src/debug/            DebugCommandReceiver + DebugCommands
app/src/test/             PaperFitTest, LessonsTest (14 tests); LessonsTest also dumps every drawing for
                          tools/preview_lessons.py (contact sheets per set)
```

Panels are pre-built Stage attachment panels (no dialogs in a Stage). All 2D UI is SpatialUI in `PicoTheme`;
Material / Material3 are banned (`../spatial-app-factory/bin/material-grep.txt`). Copy in `res/values(-zh)`.

## Build, install (headset first)

```bash
tools/dev.sh build                                   # JAVA_HOME = the primer JDK 21 inside dev.sh
adb -s PB324XJGL2090081G install -r app/build/outputs/apk/debug/app-debug.apk
adb -s PB324XJGL2090081G shell monkey -p io.embodit.tracelight -c android.intent.category.LAUNCHER 1
tools/dev.sh install && tools/dev.sh relaunch       # emulator-5562 / AVD PICO_6_1_Factory2
./gradlew.bat testDebugUnitTest
python tools/preview_lessons.py                      # after the tests: app/build/lesson-previews/*.png
python tools/make_icon.py <export.png>               # icon layers + 512 export
```

## Debug commands (debug build; `tools/dev.sh cmd NAME [TEXT] [NUMBERS]`)

`state` s=S00..S16 (S04 a=k corners placed; S09 a=step; S07/S09/S12/S14 seed an A4 sheet first) ·
`desk` (a TABLE plane in front of the emulator camera: its own desks are metres away) · `seed` · `sheet`
a=wMm,hMm · `piece` s=id a=step · `set` s=id · `drag` a=i,dx,dy · `move` a=dx,dy · `confirm` · `stick` a=x,ms ·
`ui` s=ready|confirm|restart|cancel|next|prev|nextPiece|library|free|grid|menu|realign · `hold` · `nodesk` ·
`tracking` · `dump` · `pose` · `panels` · `planes` · `pieces` · `bounds`.

QA tour on the emulator: `cmd desk`, tilt the camera down (`tools/harness/emu.sh drag 1080 600 1080 1400
-Button right`), then `state S01`, `S03`, `S04`, `S06`, `S07`, `piece animals-3 2` (S09), `S12`, `S14` + `ui
grid`, `ui menu`; screenshots with `tools/shot.sh NAME` (waits, captures twice). The debug broadcast does not
reach the app on the Swan headset; there, only real input.

## Runtime facts

- First-launch ANR on the emulator when meshes are built right away (MeshResource.load waits for a frame at
  ~1 fps): the scene build waits 1.5 s and pauses 150 ms between steps.
- On the Swan a keyboard / laptop counts as a horizontal plane 3 - 4 cm above the desk: the frame's height
  comes from the TABLE plane under the point (`TraceScene.deskUnder`).
- Lines thinner than ~0.6 mm render under a pixel at desk distance: frame 1 mm, guides 1 mm, ghost 1.5 mm.
- Camera readback (removed in v0.2.0, kept for reference): SpatialML readback needs CAMERA +
  `com.picovr.permission.SPATIAL_DATA`; a dynamic RGBA texture reads back with 768-px row padding cut at
  512×512×4 bytes; `uvTo3DInCameraSpace` crashed the system SecureMR backend.

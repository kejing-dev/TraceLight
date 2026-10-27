"""Contact sheets of the tracing library: python tools/preview_lessons.py (after the LessonsTest unit test)."""
import json, os, sys
from PIL import Image, ImageDraw
ROOT = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
src = os.path.join(ROOT, "app", "build", "lesson-previews", "lessons.json")
out = sys.argv[1] if len(sys.argv) > 1 else os.path.dirname(src)
pieces = json.load(open(src, encoding="utf-8"))
sets = {}
for p in pieces: sets.setdefault(p["set"], []).append(p)
C = 260
for sid, ps in sets.items():
    im = Image.new("RGB", (C * len(ps), int(C * 1.4) + 24), "white")
    d = ImageDraw.Draw(im)
    for i, p in enumerate(ps):
        k = C / 110.0
        ox, oy = i * C + C / 2, 24 + C * 0.7
        d.rectangle((i * C + 2, 24, i * C + C - 3, 24 + int(C * 1.4) - 3), outline=(210, 210, 210))
        d.text((i * C + 6, 6), p["id"] + "  " + p["en"], fill=(60, 60, 60))
        n = len(p["steps"])
        for si, st in enumerate(p["steps"]):
            col = (40, 90 + 120 * si // max(1, n - 1), 230 - 150 * si // max(1, n - 1))
            for s in st:
                pts = [(ox + x * k, oy - y * k) for x, y in s["p"]]
                if s["d"]:
                    for j in range(0, len(pts) - 1, 2): d.line(pts[j:j + 2], fill=col, width=1)
                    if len(pts) == 2:
                        (x0, y0), (x1, y1) = pts
                        for t in range(0, 20, 2):
                            d.line((x0 + (x1 - x0) * t / 20, y0 + (y1 - y0) * t / 20, x0 + (x1 - x0) * (t + 1) / 20, y0 + (y1 - y0) * (t + 1) / 20), fill=col, width=2)
                else:
                    d.line(pts, fill=col, width=2)
    path = os.path.join(out, sid + ".png")
    im.save(path)
    print("wrote", path)

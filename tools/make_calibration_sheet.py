"""Trace Light calibration sheet (design `preconditions.deviceSpike.build.sheet`): A4 1:1, five ring targets at
the same paper coordinates as the S20 crosses ((0, 0), (+-80, +-120) mm), rings r = 1 / 2 / 3 / 5 / 10 mm in
black / blue / red / green / black, a 100 mm check bar, L marks 8 mm in from the corners.

    python tools/make_calibration_sheet.py OUT_DIR

Writes calibration-a4.pdf (0.5 mm rings) and calibration-a4-bold.pdf (0.8 mm rings). Print at "Actual size / 100 %".
Paper coordinates: origin at the centre, x right, y towards the top edge (away from you), millimetres.
"""
import os
import sys
from PIL import Image, ImageDraw, ImageFont

DPI = 600
MM = DPI / 25.4
W, H = 210.0, 297.0
ROOT = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
FONT = r"C:\Windows\Fonts\msyh.ttc"  # the app font is a subset without these glyphs
LATIN = os.path.join(ROOT, "app", "src", "main", "res", "font", "noto_sans_medium.ttf")
TARGETS = [(0, 0), (-80, -120), (80, -120), (80, 120), (-80, 120)]
RINGS = [(1, (0, 0, 0)), (2, (20, 90, 230)), (3, (220, 40, 30)), (5, (20, 150, 70)), (10, (0, 0, 0))]


def px(x, y):
    """Paper mm -> image pixels (image y down, paper y up)."""
    return ((x + W / 2) * MM, (H / 2 - y) * MM)


def font(path, size_mm):
    try:
        return ImageFont.truetype(path, int(size_mm * MM))
    except OSError:
        return ImageFont.load_default()


def sheet(line_mm, label):
    im = Image.new("RGB", (int(round(W * MM)), int(round(H * MM))), "white")
    d = ImageDraw.Draw(im)
    lw = max(1, int(round(line_mm * MM)))
    small = font(LATIN, 2.2)
    for k, (tx, ty) in enumerate(TARGETS):
        cx, cy = px(tx, ty)
        for r, col in RINGS:
            rr = r * MM
            d.ellipse((cx - rr, cy - rr, cx + rr, cy + rr), outline=col, width=lw)
        # ring numbers outside the 10 mm ring, to the right
        # a 0.3 mm centre dot
        c = 0.15 * MM
        d.ellipse((cx - c, cy - c, cx + c, cy + c), fill="black")
        d.text((cx - 9 * MM, cy + 11.5 * MM), f"#{k + 1}", fill=(80, 80, 80), font=small)
    # 100 mm check bar, lower left, ticks every 10 mm
    x0, y0 = -95.0, -138.0
    a, b = px(x0, y0), px(x0 + 100, y0)
    d.line((a, b), fill="black", width=max(1, int(0.4 * MM)))
    for i in range(11):
        t0 = px(x0 + i * 10, y0)
        t1 = px(x0 + i * 10, y0 + (4 if i % 5 == 0 else 2.5))
        d.line((t0, t1), fill="black", width=max(1, int(0.3 * MM)))
    d.text(px(x0 + 102, y0 + 2.2), "100 mm", fill="black", font=small)
    # L marks 8 mm in from each corner, 10 mm arms
    for sx, sy in ((-1, -1), (1, -1), (1, 1), (-1, 1)):
        cx, cy = sx * (W / 2 - 8), sy * (H / 2 - 8)
        d.line((px(cx, cy), px(cx - sx * 10, cy)), fill="black", width=max(1, int(0.4 * MM)))
        d.line((px(cx, cy), px(cx, cy - sy * 10)), fill="black", width=max(1, int(0.4 * MM)))
    zh = font(FONT, 3.2)
    lines = [
        f"透写台 标定纸（{label}）  ↑ 这一边朝远离你的方向",
        "打印选「实际大小 / 100%」；量左下校验条应为 100 mm",
        "环半径：黑 1 · 蓝 2 · 红 3 · 绿 5 · 黑 10 mm",
        "读数：蓝色虚拟十字的中心落在第几环内",
    ]
    for i, t in enumerate(lines):
        d.text(px(-60, 132 - i * 5.0), t, fill=(60, 60, 60), font=zh)
    return im


def main():
    out = sys.argv[1] if len(sys.argv) > 1 else "."
    os.makedirs(out, exist_ok=True)
    for name, lw, label in (("calibration-a4.pdf", 0.5, "0.5 mm 环线"), ("calibration-a4-bold.pdf", 0.8, "0.8 mm 加粗版")):
        im = sheet(lw, label)
        path = os.path.join(out, name)
        im.save(path, "PDF", resolution=DPI)
        im.resize((im.width // 6, im.height // 6)).save(path.replace(".pdf", "-preview.png"))
        print("wrote", path, im.size)


if __name__ == "__main__":
    main()

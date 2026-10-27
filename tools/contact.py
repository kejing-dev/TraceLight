"""Contact sheet of state screenshots: python tools/contact.py <dir> S1 S2 ... -> <dir>/tour.png"""
import os
import sys
from PIL import Image, ImageDraw, ImageFont

d = sys.argv[1]
ids = sys.argv[2:]
W = 480
cols = 4
rows = (len(ids) + cols - 1) // cols
sheet = Image.new("RGB", (cols * W, rows * (W + 30)), "white")
draw = ImageDraw.Draw(sheet)
try:
    font = ImageFont.truetype("arial.ttf", 22)
except OSError:
    font = ImageFont.load_default()
for i, sid in enumerate(ids):
    p = os.path.join(d, sid + ".png")
    x, y = (i % cols) * W, (i // cols) * (W + 30)
    draw.text((x + 8, y + 4), sid, fill="black", font=font)
    if os.path.exists(p):
        im = Image.open(p).convert("RGB").resize((W, W))
        sheet.paste(im, (x, y + 30))
sheet.save(os.path.join(d, "tour.png"))
print("tour.png", len(ids))

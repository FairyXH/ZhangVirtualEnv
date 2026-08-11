import sys
from PIL import Image

im = Image.open(sys.argv[1]).convert("RGB")
w, h = im.size
px = im.load()

# 整行扫描：y=800 全部 x，标注黑/渐变/亮
print("full row y=800:")
prev_kind = None
for x in range(0, w, 4):
    v = px[x, 800]
    b = sum(v) / 3
    kind = "BLACK" if b < 30 else ("DARK" if b < 180 else ("MID" if b < 230 else "LIGHT"))
    if kind != prev_kind:
        print(f"x={x} {v} {kind}")
        prev_kind = kind

# 纵向：x=632 全行
print("full col x=632:")
prev_kind = None
for y in range(0, h, 6):
    v = px[632, y]
    b = sum(v) / 3
    kind = "BLACK" if b < 30 else ("DARK" if b < 180 else ("MID" if b < 230 else "LIGHT"))
    if kind != prev_kind:
        print(f"y={y} {v} {kind}")
        prev_kind = kind

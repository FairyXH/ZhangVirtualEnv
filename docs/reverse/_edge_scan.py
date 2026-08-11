import sys
from PIL import Image

im = Image.open(sys.argv[1]).convert("RGB")
w, h = im.size
px = im.load()

print("left edge scan x=0..160 at y=800:")
prev = None
for x in range(0, 161, 8):
    v = px[x, 800]
    print(x, v, "dark" if sum(v) < 30 else "")

print("top edge scan y=0..170 at x=632:")
for y in range(0, 171, 10):
    v = px[632, y]
    print(y, v, "dark" if sum(v) < 30 else "")

print("bottom edge scan y=2680..2779 at x=632:")
for y in range(2680, 2780, 10):
    v = px[632, y]
    print(y, v, "dark" if sum(v) < 30 else "")

print("right edge scan x=1180..1263 at y=800:")
for x in range(1180, 1264, 10):
    v = px[x, 800]
    print(x, v, "dark" if sum(v) < 30 else "")

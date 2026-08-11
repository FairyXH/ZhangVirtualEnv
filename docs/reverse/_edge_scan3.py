import sys
from PIL import Image

im = Image.open(sys.argv[1]).convert("RGB")
w, h = im.size
px = im.load()

print("left zone y=800, x=0..140 every 2px:")
for x in range(0, 141, 2):
    v = px[x, 800]
    print(x, v)

print("left zone y=300, x=0..140 every 2px:")
for x in range(0, 141, 2):
    v = px[x, 300]
    print(x, v)

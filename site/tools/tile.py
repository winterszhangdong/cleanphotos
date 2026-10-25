# 把整页长截图切段并排，便于快速检查：tile.py in.png out.png [列宽]
import sys
from PIL import Image
im = Image.open(sys.argv[1]).convert('RGB'); colw = int(sys.argv[3]) if len(sys.argv) > 3 else 360
s = colw / im.width; im = im.resize((colw, int(im.height * s)))
seg = int(colw * 2.6); n = -(-im.height // seg)
out = Image.new('RGB', (n * (colw + 8), seg), (40, 40, 40))
for i in range(n): out.paste(im.crop((0, i * seg, colw, min(im.height, (i + 1) * seg))), (i * (colw + 8), 0))
out.save(sys.argv[2], quality=85)

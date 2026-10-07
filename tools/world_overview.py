#!/usr/bin/env python3
"""One top-down picture (1 pixel per block) of the whole Tranzit Reimagined save, with a 100-block grid labelled in world coordinates, to place regions in tranzit_regions.json.
  python tools/world_overview.py "<Tranzit Download dir>"  ->  maps_local/world.png"""
import hashlib, os, sys
import numpy as np
from PIL import Image, ImageDraw
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import extract_tranzit as ex

rdir = ex.region_dir(sys.argv[1])
import glob
files = glob.glob(rdir + '/r.*.mca')
minx = min(int(os.path.basename(f).split('.')[1]) for f in files) * 512; maxx = (max(int(os.path.basename(f).split('.')[1]) for f in files) + 1) * 512
minz = min(int(os.path.basename(f).split('.')[2]) for f in files) * 512; maxz = (max(int(os.path.basename(f).split('.')[2]) for f in files) + 1) * 512
im = Image.new('RGB', (maxx - minx, maxz - minz)); px = im.load()
SKIP = ('air', 'leaves', 'short_grass', 'tall_grass', 'fern', 'vine', 'water')
for fn in files:
    for lx, lz, c in ex.chunks(fn):
        cx = int(os.path.basename(fn).split('.')[1]) * 32 + lx; cz = int(os.path.basename(fn).split('.')[2]) * 32 + lz
        top = np.full((16, 16), None, object); done = np.zeros((16, 16), bool)
        for s in sorted(c['sections'], key=lambda s: -int(s['Y'])):
            bs = s['block_states']; names = [str(p['Name']).replace('minecraft:', '') for p in bs['palette']]
            if len(names) == 1: idx = np.zeros(4096, np.int64)
            else:
                b = max(4, (len(names) - 1).bit_length()); per = 64 // b
                d = np.array(bs['data'], dtype=np.int64).view(np.uint64)
                sh = (np.arange(4096) % per * b).astype(np.uint64)
                idx = ((d[np.arange(4096) // per] >> sh) & np.uint64((1 << b) - 1)).astype(np.int64)
            a = np.array([n in SKIP or n.endswith('leaves') for n in names])[idx].reshape(16, 16, 16)  # y, z, x
            nm = np.array(names, object)[idx].reshape(16, 16, 16)
            for y in range(15, -1, -1):
                new = ~done & ~a[y]
                top[new] = nm[y][new]; done |= new
            if done.all(): break
        for z in range(16):
            for x in range(16):
                n = top[z, x]
                if n is None: continue
                col = {'grass_block': (50, 120, 45), 'stone': (110, 110, 110), 'dirt': (110, 80, 50)}.get(n) or tuple(int(v) for v in hashlib.md5(n.encode()).digest()[:3])
                px[cx * 16 + x - minx, cz * 16 + z - minz] = col
d = ImageDraw.Draw(im)
for gx in range((minx // 100) * 100, maxx, 100):
    d.line((gx - minx, 0, gx - minx, im.height), fill=(255, 255, 255), width=1); d.text((gx - minx + 2, 2), str(gx), fill=(255, 255, 0))
for gz in range((minz // 100) * 100, maxz, 100):
    d.line((0, gz - minz, im.width, gz - minz), fill=(255, 255, 255), width=1); d.text((2, gz - minz + 2), str(gz), fill=(255, 255, 0))
im.save(os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', 'maps_local', 'world.png')); print(im.size, minx, minz)

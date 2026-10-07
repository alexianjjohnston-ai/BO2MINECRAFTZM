"""Top-down preview of the map sheets (topmost block per column) -> assets_src/depot/topdown.png"""
import json, os
from PIL import Image
SH = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', 'sheets')
ld = lambda n: json.load(open(os.path.join(SH, n), encoding='utf-8'))
vox = {}
R = lambda a, b: range(min(a, b), max(a, b) + 1)
for o in sorted(ld('map_ops.json'), key=lambda r: r['order']):
    for x in R(o['x1'], o['x2']):
        for y in R(o['y1'], o['y2']):
            for z in R(o['z1'], o['z2']):
                if o['op'] == 'fill': vox[(x, y, z)] = o['block']
                elif o['op'] == 'walls':
                    if x in (min(o['x1'], o['x2']), max(o['x1'], o['x2'])) or z in (min(o['z1'], o['z2']), max(o['z1'], o['z2'])): vox[(x, y, z)] = o['block']
                elif o['op'] == 'checker': vox[(x, y, z)] = o['block'] if (x + z) % 2 == 0 else o['block2']
                elif o['op'] == 'grid':
                    if (x - min(o['x1'], o['x2'])) % o['stepX'] == 0 and (z - min(o['z1'], o['z2'])) % o['stepZ'] == 0: vox[(x, y, z)] = o['block']
for w in ld('map_windows.json'):
    for i in range(w['width']):
        for j in range(w['height']):
            c = (w['a'] + i, w['y0'] + j, w['fixed']) if w['wall'] in 'NS' else (w['fixed'], w['y0'] + j, w['a'] + i)
            vox[c] = 'minecraft:oak_planks'
COL = {'grass_block': (80, 140, 60), 'black_concrete': (30, 30, 30), 'yellow_concrete': (230, 200, 40), 'light_gray_concrete': (170, 170, 170),
       'smooth_stone': (150, 150, 150), 'gray_concrete': (90, 90, 90), 'red_concrete': (180, 40, 40), 'sea_lantern': (230, 240, 220), 'quartz_block': (240, 235, 225),
       'lava': (255, 100, 0), 'iron_bars': (200, 200, 210), 'air': None, 'oak_planks': (190, 150, 90), 'dirt': (110, 80, 50), 'green_concrete': (60, 130, 60),
       'black_stained_glass': (40, 40, 60), 'light_blue_concrete': (60, 150, 200), 'blue_terracotta': (70, 80, 140), 'dark_oak_slab': (70, 45, 20)}
S = 10; W, H = 82, 62
im = Image.new('RGB', (W * S, H * S), (0, 0, 0))
for x in range(-40, 41):
    for z in range(-30, 31):
        top = None
        for y in range(14, -5, -1):
            b = vox.get((x, y, z))
            if b and b.split('[')[0].replace('minecraft:', '') != 'air': top = b; break
        c = COL.get((top or 'air').split('[')[0].replace('minecraft:', '')) or (0, 0, 0)
        if top is None: continue
        for dx in range(S):
            for dz in range(S): im.putpixel(((x + 40) * S + dx, (z + 30) * S + dz), c)
im.save(os.path.join(SH, '..', 'assets_src', 'depot', 'topdown.png'))

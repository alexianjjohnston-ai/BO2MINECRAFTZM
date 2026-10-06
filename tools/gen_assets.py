#!/usr/bin/env python3
"""Generates the gun item textures (16x16 pixel art, drawn here, not taken from any game), item models and the lang file
from sheets/weapons.json. Pure standard library."""
import json, os, struct, zlib

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..')
RES = os.path.join(ROOT, 'mod', 'src', 'main', 'resources', 'assets', 'zombiecraft')
weapons = json.load(open(os.path.join(ROOT, 'sheets', 'weapons.json'), encoding='utf-8'))

def png(path, pixels, w=16, h=16):
    raw = b''.join(b'\x00' + b''.join(bytes(p) for p in pixels[y * w:(y + 1) * w]) for y in range(h))
    def chunk(t, d): c = struct.pack('>I', len(d)) + t + d; return c + struct.pack('>I', zlib.crc32(t + d) & 0xffffffff)
    open(path, 'wb').write(b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('>IIBBBBB', w, h, 8, 6, 0, 0, 0)) + chunk(b'IDAT', zlib.compress(raw, 9)) + chunk(b'IEND', b''))

def shade(c, f): return tuple(max(0, min(255, int(v * f))) for v in c)
def hexrgb(s): s = s.lstrip('#'); return tuple(int(s[i:i + 2], 16) for i in (0, 2, 4))

# rectangles (x1,y1,x2,y2,part) per shape, drawn pointing right
SHAPES = {
 'pistol':   [(3, 4, 11, 6, 'body'), (11, 5, 13, 5, 'dark'), (4, 7, 6, 11, 'wood'), (6, 8, 8, 8, 'dark')],
 'revolver': [(4, 4, 13, 5, 'body'), (3, 6, 6, 8, 'dark'), (2, 7, 4, 11, 'wood'), (13, 4, 14, 4, 'dark')],
 'smg':      [(2, 5, 12, 7, 'body'), (12, 6, 14, 6, 'dark'), (6, 8, 7, 12, 'dark'), (3, 8, 4, 10, 'wood'), (0, 5, 2, 6, 'wood')],
 'rifle':    [(3, 6, 11, 7, 'body'), (11, 6, 15, 6, 'dark'), (0, 6, 3, 9, 'wood'), (7, 8, 8, 11, 'dark'), (9, 7, 12, 8, 'wood')],
 'shotgun':  [(6, 5, 15, 6, 'dark'), (7, 7, 10, 8, 'wood'), (0, 6, 6, 9, 'wood'), (5, 7, 6, 8, 'body')],
 'raygun':   [(2, 5, 10, 8, 'body'), (10, 6, 14, 7, 'dark'), (14, 5, 15, 8, 'body'), (4, 8, 6, 12, 'dark'), (6, 4, 8, 4, 'dark')],
}

def draw(shape, color):
    base = hexrgb(color)
    pal = {'body': base, 'dark': shade(base, 0.55), 'wood': (121, 85, 48)}
    grid = [None] * 256
    for x1, y1, x2, y2, part in SHAPES[shape]:
        for y in range(y1, y2 + 1):
            for x in range(x1, x2 + 1): grid[y * 16 + x] = pal[part] + (255,)
    out = [(0, 0, 0, 0)] * 256
    for y in range(16):
        for x in range(16):
            if grid[y * 16 + x]: out[y * 16 + x] = grid[y * 16 + x]
    # outline
    ol = list(out)
    for y in range(16):
        for x in range(16):
            if out[y * 16 + x][3] == 0:
                for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
                    nx, ny = x + dx, y + dy
                    if 0 <= nx < 16 and 0 <= ny < 16 and out[ny * 16 + nx][3] == 255: ol[y * 16 + x] = (20, 20, 20, 255); break
    return ol

os.makedirs(os.path.join(RES, 'textures', 'item'), exist_ok=True)
os.makedirs(os.path.join(RES, 'models', 'item'), exist_ok=True)
os.makedirs(os.path.join(RES, 'items'), exist_ok=True)
os.makedirs(os.path.join(RES, 'lang'), exist_ok=True)
lang = {"key.zombiecraft.interact": "Buy / Use / Repair", "key.zombiecraft.reload": "Reload", "key.categories.zombiecraft": "Zombiecraft"}
for w in [x for x in weapons if not x['upgrade']]:
    png(os.path.join(RES, 'textures', 'item', w['id'] + '.png'), draw(w['iconShape'], w['iconColor']))
    DISPLAY = {
        "firstperson_righthand": {"rotation": [0, -90, 0], "translation": [1.0, 3.2, -2.5], "scale": [0.9, 0.9, 0.9]},
        "firstperson_lefthand": {"rotation": [0, -90, 0], "translation": [1.0, 3.2, -2.5], "scale": [0.9, 0.9, 0.9]},
        "thirdperson_righthand": {"rotation": [0, -90, 0], "translation": [0, 3, 1], "scale": [0.8, 0.8, 0.8]},
        "gui": {"rotation": [0, 0, 0], "translation": [0, 0, 0], "scale": [1, 1, 1]},
        "ground": {"rotation": [0, 0, 0], "translation": [0, 2, 0], "scale": [0.6, 0.6, 0.6]},
        "fixed": {"rotation": [0, 0, 0], "translation": [0, 0, 0], "scale": [1, 1, 1]}}
    json.dump({"parent": "minecraft:item/generated", "textures": {"layer0": f"zombiecraft:item/{w['id']}"}, "display": DISPLAY}, open(os.path.join(RES, 'models', 'item', w['id'] + '.json'), 'w'), indent=1)
    json.dump({"model": {"type": "minecraft:model", "model": f"zombiecraft:item/{w['id']}"}}, open(os.path.join(RES, 'items', w['id'] + '.json'), 'w'), indent=1)
    lang[f"item.zombiecraft.{w['id']}"] = w['name']
lang["entity.zombiecraft.zombie"] = "Zombie"
json.dump(lang, open(os.path.join(RES, 'lang', 'en_us.json'), 'w'), indent=1)
print(len(weapons), 'weapon icons, models and lang entries written')

#!/usr/bin/env python3
"""Writes the resource files of the custom blocks (blockstates, models, item definitions, lang, neutral placeholder textures) under
mod/src/main/resources/assets/zombiecraft/. The placeholders are plain procedural noise (our own art) so the game works without Black Ops II;
the generated texture pack (sheets/textures.json) replaces them with BO2 images on the player's PC.
Block ids here must match com.zombiecraft.block.ModBlocks."""
import json, os, random
from PIL import Image

RES = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', 'mod', 'src', 'main', 'resources', 'assets', 'zombiecraft')

# id -> (display name, placeholder colour, kind)   kind: cube | board | checker
BLOCKS = {
    'cinder_block':   ('Cinder Block',   (138, 135, 128), 'cube'),
    'concrete_wall':  ('Concrete Wall',  (119, 117, 111), 'cube'),
    'metal_panel':    ('Metal Panel',    (107, 112, 120), 'cube'),
    'asphalt':        ('Asphalt',        (58, 58, 60),    'cube'),
    'depot_tile':     ('Depot Tile',     (200, 200, 196), 'checker'),
    'wood_floor':     ('Wood Floor',     (106, 84, 64),   'cube'),
    'ground':         ('Hard Ground',    (90, 74, 56),    'cube'),
    'grass':          ('Dead Grass',     (95, 90, 58),    'cube'),
    'glass_brick':    ('Glass Brick',    (127, 145, 140), 'cube'),
    'neon':           ('Neon',           (48, 200, 216),  'cube'),
    'barricade_board': ('Barricade Boards', (111, 96, 72), 'board'),
}


def w(path, data):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, 'w', encoding='utf-8') as f:
        json.dump(data, f, indent=1)


def tex(name, col, kind):
    rnd = random.Random(name)
    im = Image.new('RGBA', (16, 16))
    for y in range(16):
        for x in range(16):
            n = rnd.randint(-14, 14)
            c = col
            if kind == 'checker' and ((x // 8) + (y // 8)) % 2:
                c = (50, 50, 52)
            im.putpixel((x, y), (max(0, min(255, c[0] + n)), max(0, min(255, c[1] + n)), max(0, min(255, c[2] + n)), 255))
    p = os.path.join(RES, 'textures', 'block', name + '.png')
    os.makedirs(os.path.dirname(p), exist_ok=True)
    im.save(p)


lang_p = os.path.join(RES, 'lang', 'en_us.json')
lang = json.load(open(lang_p, encoding='utf-8')) if os.path.exists(lang_p) else {}

for bid, (title, col, kind) in BLOCKS.items():
    tex(bid, col, kind)
    lang['block.zombiecraft.' + bid] = title
    if kind == 'board':
        w(os.path.join(RES, 'blockstates', bid + '.json'), {'variants': {
            'axis=x': {'model': 'zombiecraft:block/' + bid},
            'axis=z': {'model': 'zombiecraft:block/' + bid, 'y': 90}}})
        # three rough planks nailed across the opening (running along x); the block is still a full solid cell for collision
        els = []
        for y0, y1, off in ((1, 5, 0), (6, 10, 1), (11, 15, -1)):
            faces = {}
            for f in ('north', 'south', 'east', 'west', 'up', 'down'):
                faces[f] = {'uv': [0, 0, 16, 4] if f in ('north', 'south') else [0, 0, 2, 4], 'texture': '#0'}
            els.append({'from': [0, y0, 7 + off * 0.5], 'to': [16, y1, 9 + off * 0.5], 'faces': faces})
        w(os.path.join(RES, 'models', 'block', bid + '.json'),
          {'textures': {'0': 'zombiecraft:block/' + bid, 'particle': 'zombiecraft:block/' + bid}, 'elements': els})
    else:
        w(os.path.join(RES, 'blockstates', bid + '.json'), {'variants': {'': {'model': 'zombiecraft:block/' + bid}}})
        w(os.path.join(RES, 'models', 'block', bid + '.json'), {'parent': 'minecraft:block/cube_all', 'textures': {'all': 'zombiecraft:block/' + bid}})
    w(os.path.join(RES, 'items', bid + '.json'), {'model': {'type': 'minecraft:model', 'model': 'zombiecraft:block/' + bid}})

json.dump(lang, open(lang_p, 'w', encoding='utf-8'), indent=1, ensure_ascii=False)
print(len(BLOCKS), 'blocks written')

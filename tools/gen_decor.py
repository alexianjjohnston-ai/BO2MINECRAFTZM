"""Decor blocks (benches, chairs, lockers, signs, pillars ...). Part of gen_blocks.py (exec'd there: uses its RES, w, lang, json, os, random, Image).
Every decor block faces a horizontal direction (models are authored facing NORTH = -z; the front is at low z). Writes the blockstate, a multi-element model,
an item, the lang entry, the procedural placeholder textures (our own art, incl. the sign lettering) and resources/assets/zombiecraft/decor.json (shape box,
collision, light) which ModBlocks reads, so the shapes live in one place.
Textures: <id> is the main one, <id>_b the optional second (parts that use "1"). The BO2 pack may replace them (sheets/textures.json)."""
from PIL import ImageDraw, ImageFont

FONT = 'C:/Windows/Fonts/arialbd.ttf'


def font(sz):
    try:
        return ImageFont.truetype(FONT, sz)
    except OSError:
        return ImageFont.load_default()


def noise(name, col, size=16, fn=None):
    rnd = random.Random(name)
    im = Image.new('RGBA', (size, size))
    for y in range(size):
        for x in range(size):
            n = rnd.randint(-12, 12)
            c = fn(x, y) if fn else col
            c = c or col
            im.putpixel((x, y), tuple(max(0, min(255, v + n)) for v in c) + (255,))
    return im


def sign(name, size, bg, fg, lines, border=None):
    im = Image.new('RGBA', size, bg + (255,))
    d = ImageDraw.Draw(im)
    if border: d.rectangle([0, 0, size[0] - 1, size[1] - 1], outline=border, width=max(2, size[1] // 12))
    fs = size[1] // (len(lines) + 1)
    for i, t in enumerate(lines):
        f = font(fs)
        while d.textlength(t, font=f) > size[0] * 0.9 and fs > 6:
            fs -= 1; f = font(fs)
        d.text((size[0] / 2, size[1] * (i + 1) / (len(lines) + 1)), t, fill=fg, font=f, anchor='mm')
    return im


def clock():
    im = Image.new('RGBA', (64, 64), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    d.ellipse([2, 2, 61, 61], fill=(30, 28, 26, 255), outline=(190, 180, 150, 255), width=3)
    d.ellipse([7, 7, 56, 56], fill=(218, 212, 190, 255))
    for k in range(12):
        import math
        a = k * math.pi / 6
        d.line([32 + 21 * math.sin(a), 32 - 21 * math.cos(a), 32 + 24 * math.sin(a), 32 - 24 * math.cos(a)], fill=(30, 30, 30, 255), width=2)
    d.line([32, 32, 32, 16], fill=(20, 20, 20, 255), width=3)
    d.line([32, 32, 42, 38], fill=(20, 20, 20, 255), width=2)
    return im


def vend():
    im = Image.new('RGBA', (64, 64), (170, 36, 34, 255))
    d = ImageDraw.Draw(im)
    d.rectangle([6, 6, 57, 34], fill=(40, 52, 60, 255))
    for i in range(4):
        d.rectangle([9 + i * 12, 9, 17 + i * 12, 31], fill=(200, 60, 50, 255) if i % 2 else (230, 200, 70, 255))
    d.rectangle([10, 40, 53, 46], fill=(20, 20, 20, 255))
    d.text((32, 55), 'COLA', fill=(255, 255, 255, 255), font=font(11), anchor='mm')
    return im


def locker_front():
    im = noise('locker', (84, 104, 94), 32)
    d = ImageDraw.Draw(im)
    d.rectangle([0, 0, 31, 31], outline=(40, 52, 46, 255))
    d.line([16, 0, 16, 31], fill=(40, 52, 46, 255))
    for x0 in (3, 19):
        for y in range(4, 12, 3): d.line([x0, y, x0 + 9, y], fill=(40, 52, 46, 255))
        d.rectangle([x0 + 7, 17, x0 + 9, 20], fill=(150, 150, 140, 255))
    return im


def pump_front():
    im = noise('pump', (180, 30, 28), 32)
    d = ImageDraw.Draw(im)
    d.rectangle([5, 5, 26, 13], fill=(30, 40, 30, 255))
    d.rectangle([8, 18, 24, 29], fill=(60, 60, 60, 255))
    d.line([24, 18, 24, 28], fill=(10, 10, 10, 255), width=2)
    return im


def kiosk():
    im = Image.new('RGBA', (64, 64), (46, 54, 66, 255))
    d = ImageDraw.Draw(im)
    d.rectangle([2, 2, 61, 61], fill=(214, 186, 134, 255), outline=(30, 34, 40, 255), width=3)
    rnd = random.Random('map')
    for _ in range(14):
        x, y = rnd.randint(8, 52), rnd.randint(8, 52)
        d.line([x, y, x + rnd.randint(-10, 10), y + rnd.randint(-8, 8)], fill=(150, 70, 50, 255), width=1)
    return im


def stripes(name, a, b, size=16):
    return noise(name, a, size, lambda x, y: b if (x + y) // 4 % 2 else a)


PAINT = {  # texture name -> image
    'bench': noise('bench', (110, 84, 56), 16, lambda x, y: (70, 50, 34) if y % 5 == 0 else None),
    'bench_b': noise('bench_b', (76, 80, 84)),
    'waiting_chair': noise('waiting_chair', (176, 184, 178)),
    'waiting_chair_b': noise('waiting_chair_b', (70, 72, 74)),
    'trash_can': noise('trash_can', (206, 208, 200), 16, lambda x, y: (30, 30, 30) if 6 <= y <= 9 and 4 <= x <= 11 else None),
    'trash_can_b': noise('trash_can_b', (150, 152, 146)),
    'locker': locker_front(),
    'locker_b': noise('locker_b', (74, 92, 84)),
    'crate': noise('crate', (126, 98, 62), 16, lambda x, y: (82, 60, 36) if x in (0, 15) or y in (0, 15) or x == y else None),
    'barrel': noise('barrel', (150, 60, 40)),
    'barrel_b': noise('barrel_b', (96, 40, 30)),
    'street_lamp': noise('street_lamp', (58, 64, 62)),
    'street_lamp_head': noise('street_lamp_head', (58, 64, 62)),
    'street_lamp_head_b': noise('street_lamp_head_b', (255, 232, 160)),
    'street_lamp_b': noise('street_lamp_b', (255, 232, 160)),
    'bus_stop_sign': noise('bus_stop_sign', (140, 142, 138)),
    'bus_stop_sign_b': sign('bus_stop_sign_b', (64, 64), (24, 74, 138), (255, 255, 255), ['BUS', 'STOP'], (255, 255, 255)),
    'sign_bus': sign('sign_bus', (128, 64), (22, 22, 24), (232, 190, 40), ['BUS'], (232, 190, 40)),
    'sign_depot': sign('sign_depot', (128, 64), (22, 22, 24), (232, 190, 40), ['DEPOT'], (232, 190, 40)),
    'sign_employees': sign('sign_employees', (128, 64), (200, 200, 190), (20, 20, 20), ['EMPLOYEES', 'ONLY'], (150, 30, 30)),
    'sign_fire': sign('sign_fire', (128, 64), (170, 30, 28), (255, 255, 255), ['FIRE', 'REGULATIONS'], (255, 255, 255)),
    'sign_restrooms': sign('sign_restrooms', (128, 64), (30, 70, 120), (255, 255, 255), ['RESTROOMS'], (255, 255, 255)),
    'poster_ride': sign('poster_ride', (64, 64), (226, 168, 40), (30, 30, 30), ['RIDE', 'THE', 'BUS'], (120, 60, 20)),
    'wall_clock': clock(),
    'pillar_round': noise('pillar_round', (150, 146, 134)),
    'pillar_round_b': noise('pillar_round_b', (214, 168, 34)),
    'ticket_counter': noise('ticket_counter', (104, 100, 92)),
    'ticket_counter_b': noise('ticket_counter_b', (86, 60, 40)),
    'vending': vend(),
    'vending_b': noise('vending_b', (150, 34, 32)),
    'gas_pump': pump_front(),
    'gas_pump_b': noise('gas_pump_b', (150, 150, 146)),
    'map_kiosk': kiosk(),
    'map_kiosk_b': noise('map_kiosk_b', (52, 60, 72)),
    'jersey_barrier': noise('jersey_barrier', (150, 148, 140)),
}

NORTH = ('north', 'south', 'east', 'west', 'up', 'down')


def box(a, b, t='0', rot=None, only=None):
    """One model element from a to b in 1/16 blocks. t = texture key for all faces; only = {face: key} overrides (a face set to None is skipped)."""
    e = {'from': list(a), 'to': list(b), 'faces': {f: {'texture': '#' + (only or {}).get(f, t)} for f in NORTH if (only or {}).get(f, t) is not None}}
    if rot: e['rotation'] = {'angle': rot, 'axis': 'y', 'origin': [8, 8, 8], 'rescale': True}
    return e


def octagon(a, b, t='0'):  # a vertical eight-sided prism: the box plus the same box turned 45 degrees
    return [box(a, b, t), box(a, b, t, rot=45)]


def plate(a, b, front='0'):  # a flat sign: visible on the north face only
    return [box(a, b, '1', only={'north': front, 'south': '1'})]


# id: (title, elements, shape box (x1,y1,z1,x2,y2,z2), collide, light, second texture used)
DECOR = {
    'bench': ('Bench', [box((0, 7, 3), (16, 8.5, 13), '0'), box((0, 8.5, 11), (16, 15, 12.5), '0'), box((1, 0, 4), (3, 7, 6), '1'), box((13, 0, 4), (15, 7, 6), '1'),
                         box((1, 0, 10), (3, 7, 12), '1'), box((13, 0, 10), (15, 7, 12), '1')], (0, 0, 3, 16, 15, 13), True, 0),
    'waiting_chair': ('Waiting Chair', [box((3, 6, 3), (13, 8, 13), '0'), box((3, 8, 11), (13, 16, 13), '0'), box((3, 0, 3), (5, 6, 5), '1'), box((11, 0, 3), (13, 6, 5), '1'),
                                        box((3, 0, 11), (5, 6, 13), '1'), box((11, 0, 11), (13, 6, 13), '1')], (3, 0, 3, 13, 16, 13), True, 0),
    'trash_can': ('Trash Can', octagon((4, 0, 4), (12, 10, 12)) + [box((3, 10, 3), (13, 11.5, 13), '1')], (3, 0, 3, 13, 12, 13), True, 0),
    'locker': ('Locker', [box((1, 0, 1), (15, 16, 15), '1', only={'north': '0'})], (1, 0, 1, 15, 16, 15), True, 0),
    'crate': ('Crate', [box((1, 0, 1), (15, 14, 15), '0')], (1, 0, 1, 15, 14, 15), True, 0),
    'barrel': ('Barrel', octagon((3, 0, 3), (13, 13, 13)) + [box((2.5, 4, 2.5), (13.5, 6, 13.5), '1', rot=22.5)], (3, 0, 3, 13, 13, 13), True, 0),
    'street_lamp': ('Street Lamp Pole', [box((7, 0, 7), (9, 16, 9), '0')], (7, 0, 7, 9, 16, 9), True, 0),
    'street_lamp_head': ('Street Lamp', [box((7, 0, 7), (9, 12, 9), '0'), box((7, 11, 2), (9, 12.5, 9), '0'), box((6, 10, 0), (10, 11, 5), '1')], (6, 0, 0, 10, 12.5, 9), True, 14),
    'bus_stop_sign': ('Bus Stop Sign', [box((7.5, 0, 7.5), (8.5, 16, 8.5), '0'), box((2, 8, 8.5), (14, 15, 9.5), '1', only={'north': '1', 'south': '1', 'east': '0', 'west': '0', 'up': '0', 'down': '0'})],
                       (2, 0, 7.5, 14, 15, 9.5), False, 0),
    'sign_bus': ('Sign: BUS', plate((0, 3, 15), (16, 13, 16)), (0, 3, 15, 16, 13, 16), False, 0),
    'sign_depot': ('Sign: DEPOT', plate((0, 3, 15), (16, 13, 16)), (0, 3, 15, 16, 13, 16), False, 0),
    'sign_employees': ('Sign: Employees Only', plate((2, 4, 15.5), (14, 12, 16)), (2, 4, 15.5, 14, 12, 16), False, 0),
    'sign_fire': ('Sign: Fire Regulations', plate((2, 4, 15.5), (14, 12, 16)), (2, 4, 15.5, 14, 12, 16), False, 0),
    'sign_restrooms': ('Sign: Restrooms', plate((1, 5, 15.5), (15, 11, 16)), (1, 5, 15.5, 15, 11, 16), False, 0),
    'poster_ride': ('Poster: Ride the Bus', plate((2, 1, 15.5), (14, 15, 16)), (2, 1, 15.5, 14, 15, 16), False, 0),
    'wall_clock': ('Wall Clock', [box((3, 3, 15), (13, 13, 16), '0', only={'north': '0', 'south': None, 'east': None, 'west': None, 'up': None, 'down': None})], (3, 3, 15, 13, 13, 16), False, 0),
    'pillar_round': ('Round Pillar', octagon((3, 0, 3), (13, 16, 13)) + octagon((2.5, 2, 2.5), (13.5, 7, 13.5), '1'), (2, 0, 2, 14, 16, 14), True, 0),
    'ticket_counter': ('Ticket Counter', [box((0, 0, 2), (16, 12, 14), '0'), box((0, 12, 1), (16, 14, 15), '1')], (0, 0, 1, 16, 14, 15), True, 0),
    'vending': ('Vending Machine', [box((1, 0, 1), (15, 16, 15), '1', only={'north': '0'})], (1, 0, 1, 15, 16, 15), True, 0),
    'gas_pump': ('Gas Pump', [box((3, 0, 4), (13, 14, 12), '1', only={'north': '0', 'south': '0'}), box((2, 14, 3), (14, 16, 13), '1')], (2, 0, 3, 14, 16, 13), True, 0),
    'map_kiosk': ('Ticket Map Kiosk', [box((0, 0, 4), (16, 16, 10), '1', only={'north': '0'}), box((1, 0, 10), (15, 16, 11), '1')], (0, 0, 4, 16, 16, 11), True, 6),
    'jersey_barrier': ('Concrete Barrier', [box((0, 0, 3), (16, 2, 13), '0'), box((0, 2, 4), (16, 8, 12), '0'), box((0, 8, 5), (16, 12, 11), '0')], (0, 0, 3, 16, 12, 13), True, 0),
}

decor_json = {}
for bid, (title, els, shape, collide, light) in DECOR.items():
    lang['block.zombiecraft.' + bid] = title
    decor_json[bid] = dict(box=list(shape), collide=collide, light=light)
    main, second = PAINT[bid], PAINT.get(bid + '_b')
    for nm, im in ((bid, main), (bid + '_b', second)):
        if im is None: continue
        p = os.path.join(RES, 'textures', 'block', nm + '.png')
        im.save(p)
    tx = {'0': 'zombiecraft:block/' + bid, 'particle': 'zombiecraft:block/' + bid}
    tx['1'] = 'zombiecraft:block/' + (bid + '_b' if second is not None else bid)
    w(os.path.join(RES, 'models', 'block', bid + '.json'), {'textures': tx, 'elements': els})
    w(os.path.join(RES, 'blockstates', bid + '.json'), {'variants': {
        'facing=north': {'model': 'zombiecraft:block/' + bid}, 'facing=east': {'model': 'zombiecraft:block/' + bid, 'y': 90},
        'facing=south': {'model': 'zombiecraft:block/' + bid, 'y': 180}, 'facing=west': {'model': 'zombiecraft:block/' + bid, 'y': 270}}})
    w(os.path.join(RES, 'items', bid + '.json'), {'model': {'type': 'minecraft:model', 'model': 'zombiecraft:block/' + bid}})
w(os.path.join(RES, 'decor.json'), decor_json)

# invisible helper blocks: window_clip (a barrier that only stops players) and door_clip (a closed door's collision); no model, particles only
for bid in ('window_clip', 'door_clip'):
    w(os.path.join(RES, 'blockstates', bid + '.json'), {'variants': {'': {'model': 'zombiecraft:block/clip'}}})
w(os.path.join(RES, 'models', 'block', 'clip.json'), {'textures': {'particle': 'zombiecraft:block/door_metal'}})
print(len(DECOR), 'decor blocks written')

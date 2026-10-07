#!/usr/bin/env python3
"""Draw the BO2 gameplay entities of one location (Survival set) on top of the Tranzit Reimagined cut's top-down preview, to find the offset between the two.
  python tools/overlay_loc.py town 178 129      ->  maps_local/town_overlay.png   (cut x = bx - 178, cut z = bz - 129; the preview is drawn 4x)
Markers: red = barricade, yellow = wall gun, green = Mystery Box, cyan = perk, magenta = Pack-a-Punch, white = player start, blue = zombie spawner."""
import json, os, sys
from PIL import Image, ImageDraw

HERE = os.path.dirname(os.path.abspath(__file__))
ENTS = os.path.join(HERE, '..', 'assets_src', 'depot', 'ents.json')
loc, ox, oz = sys.argv[1], float(sys.argv[2]), float(sys.argv[3])
PREFIX = {'town': ('town', 'bank', 'bar', 'barber', 'bowling', 'church', 'labs', 'vault'), 'farm': ('farm', 'farmhouse'), 'power': ('power',)}[loc]
ents = json.load(open(ENTS))


def B(o):
    x, y, z = map(float, o.split()); return (x + 8400) / 40, (6900 - y) / 40


im = Image.open(os.path.join(HERE, '..', 'maps_local', loc + '.png')).convert('RGB'); d = ImageDraw.Draw(im)


def dot(o, col, label=''):
    bx, bz = B(o); x, z = (bx - ox) * 4, (bz - oz) * 4
    d.ellipse((x - 5, z - 5, x + 5, z + 5), outline=col, width=2)
    if label: d.text((x + 6, z - 5), label, fill=col)


for e in ents:
    t, c, sn = e.get('script_string', ''), e['classname'], e.get('script_noteworthy', '')
    if c.startswith('zbarrier') and 'baricade' in t and t.rsplit('_baricade', 1)[0] in PREFIX: dot(e['origin'], (255, 60, 60), t.replace('_baricade', '')[:8])
    elif e.get('zombie_weapon_upgrade') and ('zstandard_' + loc) in sn.replace(' ', ''): dot(e['origin'], (255, 230, 0), e['zombie_weapon_upgrade'][:9])
    elif c == 'zbarrier_zmcore_MagicBox' and e.get('script_noteworthy', '').startswith(loc if loc != 'power' else 'pow'): dot(e['origin'], (60, 255, 60), 'box')
    elif e.get('targetname') == 'zm_perk_machine' and ('zstandard_perks_' + loc) in t: dot(e['origin'], (60, 255, 255) if 'weapupgrade' not in sn else (255, 60, 255), sn[10:16])
    elif e.get('targetname') == loc + '_standard_player_spawns': dot(e['origin'], (255, 255, 255))
    elif e.get('targetname', '').startswith('zone_' + loc) and e['targetname'].endswith('_spawners'): dot(e['origin'], (90, 120, 255))
out = os.path.join(HERE, '..', 'maps_local', loc + '_overlay.png'); im.save(out); print(out)

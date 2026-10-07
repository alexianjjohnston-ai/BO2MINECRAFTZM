#!/usr/bin/env python3
"""Find the offset between BO2 block coordinates and a Tranzit Reimagined cut: score each (ox, oz) by how many barricades and wall guns sit on a wall.
  python tools/fit_loc.py town 160 200 100 160"""
import gzip, json, os, sys
import numpy as np
HERE = os.path.dirname(os.path.abspath(__file__))
loc, x0, x1, z0, z1 = sys.argv[1], *map(int, sys.argv[2:6])
cut = sys.argv[6] if len(sys.argv) > 6 else loc
PREFIX = {'town': ('town', 'bank', 'bar', 'barber', 'bowling', 'church', 'labs', 'vault'), 'farm': ('farm', 'farmhouse'), 'power': ('power',), 'diner': ('diner', 'gas')}[loc]
d = json.load(gzip.open(os.path.join(HERE, '..', 'maps_local', cut + '.json.gz'), 'rt'))
SX, SY, SZ = d['size']; GR = d['groundRow']
g = np.concatenate([np.full(c, i, np.int32) for i, c in d['rle']]).reshape(SY, SZ, SX)
air = np.array([p.startswith('minecraft:air') or any(k in p for k in ('leaves', 'grass', 'fern', 'vine', 'flower', 'dandelion', 'poppy')) for p in d['palette']])
solid = ~air[g]  # y, z, x
ents = json.load(open(os.path.join(HERE, '..', 'assets_src', 'depot', 'ents.json')))
B = lambda o: ((float(o.split()[0]) + 8400) / 40, (6900 - float(o.split()[1])) / 40)
pts = []
for e in ents:
    t, sn = e.get('script_string', ''), e.get('script_noteworthy', '')
    if e['classname'].startswith('zbarrier') and 'baricade' in t and t.rsplit('_baricade', 1)[0] in PREFIX: pts.append(B(e['origin']))
    elif e.get('zombie_weapon_upgrade') and ('zstandard_' + loc) in sn.replace(' ', ''): pts.append(B(e['origin']))
OPEN = {'town': ('town_standard_player_spawns',), 'farm': ('farm_standard_player_spawns',), 'power': ('race_transit_power_team1_spawn', 'race_transit_power_team2_spawn'), 'diner': ()}[loc]
opens = [B(e['origin']) for e in ents if e.get('targetname') in OPEN]
print(len(pts), 'wall points,', len(opens), 'open points')


def wall(x, z):
    x, z = int(np.floor(x)), int(np.floor(z))
    if not (1 <= x < SX - 1 and 1 <= z < SZ - 1): return -1
    w = solid[GR + 1:GR + 4, z - 1:z + 2, x - 1:x + 2].mean()
    return 1 if 0.2 < w < 0.75 else 0


def free(x, z):
    x, z = int(np.floor(x)), int(np.floor(z))
    if not (0 <= x < SX and 0 <= z < SZ): return 0
    return 1 if (not solid[GR + 1, z, x] and not solid[GR + 2, z, x] and solid[GR, z, x]) else 0


best = []
for ox in range(x0, x1):
    for oz in range(z0, z1):
        s = sum(max(0, wall(bx - ox, bz - oz)) for bx, bz in pts) + 2 * sum(free(bx - ox, bz - oz) for bx, bz in opens)
        best.append((s, ox, oz))
best.sort(reverse=True)
print(best[:8], 'of', len(pts), '| median', sorted(b[0] for b in best)[len(best) // 2])

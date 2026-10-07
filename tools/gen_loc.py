#!/usr/bin/env python3
"""Gameplay sheets for a Tranzit location (town, farm, power) played on its Tranzit Reimagined cut: sheets/maps/tranzit_<loc>/map_*.json.
  python tools/gen_loc.py town|farm|power|all
The build is pasted from maps_local/<loc>.json.gz (python tools/extract_tranzit.py <download> <loc>) with the "import" op.
Town and Farm follow Black Ops II's Survival layout: every barricade, wall gun, perk machine, the Pack-a-Punch, the Mystery Box and the player start from the map entities
(assets_src/depot/ents.json, zm_transit) is moved onto the Reimagined block build with a fixed offset (tools/fit_loc.py / overlay_loc.py) and then snapped to the nearest spot where it
fits (a window in a wall, a gun on a wall face, ...). Things BO2 has but this game has not (Marathon, Tombstone, the Galvaknuckles) are left out. The BO2 Survival scripts
(zm_transit_standard_town/farm.gsc) switch the power on and open every door at the start, so no door is written and everything is one room.
The Power Station has no Survival data in BO2 (only 3 barricades, a box and an AK74u from Tranzit), so its building is found in the blocks and the same things are placed by a spread rule.
Everything is checked against the real blocks (the rules of gen_tranzit_depot.py); what does not fit or cannot be reached is dropped and printed."""
import glob, gzip, json, math, os, re, sys
import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
ENTS = os.path.join(HERE, '..', 'assets_src', 'depot', 'ents.json')
LOCS = {
    'town': dict(off=(174, 146), key='zstandard_town', perks='zstandard_perks_town', chests=('town_chest_zbarrier', 'town_chest_2_zbarrier'), spawns=('town_standard_player_spawns',),
                 bars=('town', 'bank', 'bar', 'barber', 'bowling', 'church', 'labs', 'vault'), pap=True, win=8, label='Town'),
    'farm': dict(off=(329, 243), key='zstandard_farm', perks='zstandard_perks_farm', chests=('farm_chest_zbarrier',), spawns=('farm_standard_player_spawns',),
                 bars=('farm', 'farmhouse'), pap=False, win=8, winR=16, label='Farm'),
    'power': dict(off=None, key=None, perks=None, chests=(), spawns=(), bars=('power',), pap=False, win=6, label='Power Station'),
}
GUN = {'rottweil72_zm': 'rottweil72', 'm14_zm': 'm14', '870mcs_zm': '870mcs', 'mp5k_zm': 'mp5k', 'ak74u_zm': 'ak74u', 'fnfal_zm': 'fnfal', 'saiga12_zm': 'saiga12', 'galil_zm': 'galil'}
PERK = {'specialty_armorvest': 'jug', 'specialty_quickrevive': 'revive', 'specialty_fastreload': 'speed', 'specialty_rof': 'doubletap'}
D = {'north': (0, -1), 'south': (0, 1), 'east': (1, 0), 'west': (-1, 0)}
FACE = {0: 'east', 90: 'north', 180: 'west', 270: 'south'}
OUT_DIR = {'S': (0, 1), 'N': (0, -1), 'E': (1, 0), 'W': (-1, 0)}
PASS = ('short_grass', 'tall_grass', 'fern', 'large_fern', 'dandelion', 'poppy', 'azure_bluet', 'cornflower', 'oxeye_daisy', 'vine', 'snow')


DUMP = os.environ.get('BO2_DUMP', r'C:\Users\alexi\bo2-dump')
SCALE = 0.025 / 0.0225        # the prop renderer draws 0.0225 blocks per BO2 unit, the map is 40 units per block


def model_points(name):
    for f in glob.glob(os.path.join(DUMP, 'out*', '*', 'model_export', name + '_lod0.xmodel_export')):
        t = open(f, errors='ignore').read()
        return np.array([[float(v) for v in m.groups()] for m in re.finditer(r'^OFFSET (-?[\d.e+-]+), (-?[\d.e+-]+), (-?[\d.e+-]+)\s*$', t, re.M)])
    return None


def rot(pitch, yaw, roll):
    """BO2 angles: yaw about z, then pitch about y (positive = nose down), then roll about x."""
    c = lambda d: math.cos(math.radians(d)); sn = lambda d: math.sin(math.radians(d))
    Rz = np.array([[c(yaw), -sn(yaw), 0], [sn(yaw), c(yaw), 0], [0, 0, 1]])
    Ry = np.array([[c(pitch), 0, sn(pitch)], [0, 1, 0], [-sn(pitch), 0, c(pitch)]])
    Rx = np.array([[1, 0, 0], [0, c(roll), -sn(roll)], [0, sn(roll), c(roll)]])
    return Rz @ Ry @ Rx


def facing(yaw): return FACE[int(round(yaw / 90.0)) % 4 * 90]


def B(origin):
    x, y, z = map(float, origin.split()); return (x + 8400) / 40, (6900 - y) / 40


def build(loc):
    cfg = LOCS[loc]
    OUT = os.path.join(HERE, '..', 'sheets', 'maps', 'tranzit_' + loc)
    doc = json.load(gzip.open(os.path.join(HERE, '..', 'maps_local', loc + '.json.gz'), 'rt'))
    SX, SY, SZ = doc['size']; GR = doc['groundRow']; pal = doc['palette']
    grid = np.concatenate([np.full(c, i, np.int32) for i, c in doc['rle']]).reshape(SY, SZ, SX)
    isair = np.array([p.startswith('minecraft:air') or p.split('[')[0][10:] in PASS for p in pal])
    vox = {}
    warn = lambda m: print('  drop:', m)

    def at(x, y, z):
        if (x, y, z) in vox: return vox[(x, y, z)]
        if not (0 <= x < SX and 0 <= z < SZ and 0 <= GR + y < SY): return 'minecraft:air'
        return pal[grid[GR + y, z, x]]

    def air(x, y, z):
        n = at(x, y, z); return n.startswith('minecraft:air') or n.split('[')[0][10:] in PASS

    def solid(x, y, z): return not air(x, y, z)
    def free(x, z): return air(x, 1, z) and air(x, 2, z) and solid(x, 0, z)
    def inb(x, z): return 3 <= x < SX - 3 and 3 <= z < SZ - 3

    ents = json.load(open(ENTS))
    ox, oz = cfg['off'] if cfg['off'] else (0, 0)
    P = lambda o: (lambda b: (b[0] - ox, b[1] - oz))(B(o))      # BO2 entity origin -> cut cell (float)

    # ---- the main building of the cut: roofed floor that is connected (Power Station: this is what the spread rule fills)
    roofed = np.zeros((SZ, SX), bool)
    for z in range(SZ):
        for x in range(SX):
            col = grid[GR + 5:min(SY, GR + 16), z, x]
            roofed[z, x] = bool((~isair[col]).any()) if len(col) else False
    indoor = np.zeros((SZ, SX), bool)
    for z in range(3, SZ - 3):
        for x in range(3, SX - 3):
            indoor[z, x] = roofed[z, x] and free(x, z)

    def nearest(pred, px, pz, R, extra=None):
        best = None
        for z in range(max(3, int(pz) - R), min(SZ - 3, int(pz) + R + 1)):
            for x in range(max(3, int(px) - R), min(SX - 3, int(px) + R + 1)):
                c = math.hypot(x + .5 - px, z + .5 - pz)
                if c > R: continue
                r = pred(x, z)
                if r is None: continue
                c += r
                if best is None or c < best[0]: best = (c, x, z)
        return best

    taken = set()

    # ---- player start
    if cfg['off']:
        pts = [P(e['origin']) for e in ents if e.get('targetname') in cfg['spawns']]
        px, pz = sum(p[0] for p in pts) / len(pts), sum(p[1] for p in pts) / len(pts)
        spot = nearest(lambda x, z: 0 if free(x, z) and air(x, 3, z) else None, px, pz, 12)
    else:
        zs, xs = np.nonzero(indoor)
        # biggest indoor blob: take the indoor cell nearest the centre of all indoor cells
        px, pz = float(xs.mean()), float(zs.mean())
        spot = nearest(lambda x, z: 0 if indoor[z, x] else None, px, pz, 80)
    if not spot: sys.exit(f'{loc}: no place for the player')
    PL = (spot[1], spot[2])

    # ---- windows: every candidate 3-wide opening in a wall (6 solid cells), room behind it and outside it
    def win_ok(wall, fixed, a):
        horiz = wall in 'NS'; d = OUT_DIR[wall]
        cells = [((a + i, y, fixed) if horiz else (fixed, y, a + i)) for i in range(3) for y in (2, 3)]
        if sum(solid(*c) for c in cells) < 4 or not (solid(*cells[0]) or solid(*cells[1])) or not (solid(*cells[-1]) or solid(*cells[-2])): return None  # an open frame wall still counts: at least 4 of the 6 cells, both ends held
        mx, mz = (a + 1, fixed) if horiz else (fixed, a + 1)
        for k in (1, 2):
            if not (air(mx - k * d[0], 1, mz - k * d[1]) and air(mx - k * d[0], 2, mz - k * d[1])): return None
        if not (air(mx + d[0], 1, mz + d[1]) and air(mx + d[0], 2, mz + d[1])): return None
        if not solid(mx + d[0], 0, mz + d[1]): return None
        return (mx, mz, cells)

    cands = []
    for wall in 'NSEW':
        for f in range(4, (SZ if wall in 'NS' else SX) - 4):
            for a in range(4, (SX if wall in 'NS' else SZ) - 7):
                r = win_ok(wall, f, a)
                if r: cands.append(dict(wall=wall, fixed=f, a=a, mx=r[0], mz=r[1], cells=r[2]))
    print(loc, ':', len(cands), 'window places in the blocks')
    windows = []

    def add_window(c, room='hall'):
        if any(math.hypot(c['mx'] - w['mx'], c['mz'] - w['mz']) < 5 for w in windows): return False
        # zombies must be able to come at it: two free spots outside
        d = OUT_DIR[c['wall']]; lat = (1, 0) if c['wall'] in 'NS' else (0, 1)
        spots = []
        for k in (3, 4, 2, 5, 6, 7):
            for s in (0, 1, -1, 2, -2):
                x, z = c['mx'] + d[0] * k + lat[0] * s, c['mz'] + d[1] * k + lat[1] * s
                if free(x, z) and (x, z) not in spots: spots.append((x, z))
                if len(spots) >= 2: break
            if len(spots) >= 2: break
        if len(spots) < 2: return False
        windows.append(dict(c, spots=spots[:2], room=room)); return True

    if cfg['off']:
        for e in ents:
            t = e.get('script_string', '')
            if not (e['classname'].startswith('zbarrier') and 'baricade' in t and t.rsplit('_baricade', 1)[0] in cfg['bars']): continue
            if e['classname'].endswith('MagicBox'): continue
            bx, bz = P(e['origin'])
            nb = [x for x in ents if x.get('script_string') == t and x['classname'] == 'node_negotiation_begin']
            ne = [x for x in ents if x.get('script_string') == t and x['classname'] == 'node_negotiation_end']
            pref = None
            if nb and ne:
                b0, e0 = B(nb[0]['origin']), B(ne[0]['origin']); dx, dz = e0[0] - b0[0], e0[1] - b0[1]
                pref = ('N' if dz > 0 else 'S') if abs(dz) >= abs(dx) else ('W' if dx > 0 else 'E')
            best = None
            for c in cands:
                if any(math.hypot(c['mx'] - w['mx'], c['mz'] - w['mz']) < 5 for w in windows): continue
                cost = math.hypot(c['mx'] + .5 - bx, c['mz'] + .5 - bz) + (0 if c['wall'] == pref else 3)
                if cost <= cfg.get('winR', 9) and (best is None or cost < best[0]): best = (cost, c)
            if best and add_window(best[1]): pass
            else: warn(f'window {t}: no wall near ({bx:.0f},{bz:.0f})')
        # the Reimagined build may have fewer walls than BO2: top up to the BO2 count with walls near the start, spread apart
        want = sum(1 for e in ents if e['classname'].startswith('zbarrier') and 'baricade' in e.get('script_string', '') and e['script_string'].rsplit('_baricade', 1)[0] in cfg['bars'] and not e['classname'].endswith('MagicBox'))
        while len(windows) < want:
            best = None
            for c in cands:
                if math.hypot(c['mx'] - PL[0], c['mz'] - PL[1]) > 40: continue
                dmin = min([math.hypot(c['mx'] - w['mx'], c['mz'] - w['mz']) for w in windows] or [99])
                if dmin >= 7 and (best is None or dmin > best[0]): best = (dmin, c)
            if not best or not add_window(best[1]): break
    else:
        # spread rule: windows of the main building (an indoor cell right behind the wall), far apart from each other
        good = [c for c in cands if indoor[c['mz'] - OUT_DIR[c['wall']][1], c['mx'] - OUT_DIR[c['wall']][0]]]
        print('  indoor cells', int(indoor.sum()), 'candidates with a room behind', len(good))
        pick = []
        for _ in range(cfg['win']):
            best = None
            for c in good:
                dmin = min([math.hypot(c['mx'] - p['mx'], c['mz'] - p['mz']) for p in pick] or [99])
                if dmin >= 9 and (best is None or dmin > best[0]): best = (dmin, c)
            if not best: break
            pick.append(best[1])
        for c in pick: add_window(c)

    # ---- reachability from the player start with every window open (players and zombies hop the sill)
    wincells = {(x, y, z) for w in windows for (x, y, z) in w['cells']}
    for c in wincells: vox[c] = 'minecraft:air'

    def w1(x, z): return air(x, 1, z) and air(x, 2, z)
    def w2(x, z): return air(x, 2, z) and air(x, 3, z)
    seen = {PL}; q = [PL]
    while q:
        x, z = q.pop()
        for dx, dz in ((1, 0), (-1, 0), (0, 1), (0, -1)):
            n = (x + dx, z + dz)
            if n in seen or not (0 <= n[0] < SX and 0 <= n[1] < SZ): continue
            if (w1(*n) and solid(n[0], 0, n[1])) or (w2(*n) and (w1(x, z) or w2(x, z))): seen.add(n); q.append(n)
    keep = []
    for w in windows:
        inside = (w['mx'] - OUT_DIR[w['wall']][0], w['mz'] - OUT_DIR[w['wall']][1])
        if inside in seen and all(s in seen for s in w['spots']): keep.append(w)
        else: warn(f"window at ({w['mx']},{w['mz']}) {w['wall']}: the room behind it or its spawn spots cannot be reached from the start")
    print('  windows before/after the reach check:', len(windows), len(keep), '| reachable cells', len(seen))
    windows = keep
    if not windows: sys.exit(f'{loc}: no usable window')

    # ---- wall guns, perks, box, Pack-a-Punch
    def wall_spot(px, pz, pref, R=9, y2=True):
        best = None
        for f in ('north', 'south', 'east', 'west'):
            d = D[f]
            def pred(x, z, d=d):
                if (x, z) in taken or (x, z) not in seen: return None
                if not (free(x, z) and solid(x - d[0], 2, z - d[1]) and solid(x - d[0], 1, z - d[1]) and air(x + d[0], 1, z + d[1]) and air(x + d[0], 2, z + d[1])): return None
                return 0 if f == pref else 3
            r = nearest(pred, px, pz, R)
            if r and (best is None or r[0] < best[0]): best = (r[0], r[1], r[2], f)
        return best

    def spread(n, avoid, pref=None):
        """Auto mode: n wall spots, far from each other and from `avoid`; prefers indoor cells."""
        out = []
        cand = []
        for f in ('north', 'south', 'east', 'west'):
            d = D[f]
            for z in range(3, SZ - 3):
                for x in range(3, SX - 3):
                    if (x, z) in seen and (x, z) not in taken and indoor[z, x] and free(x, z) and solid(x - d[0], 1, z - d[1]) and solid(x - d[0], 2, z - d[1]) and air(x + d[0], 1, z + d[1]) and air(x + d[0], 2, z + d[1]) and air(x, 3, z):
                        cand.append((x, z, f))
        for _ in range(n):
            best = None
            for x, z, f in cand:
                dm = min([math.hypot(x - a[0], z - a[1]) for a in avoid + [o[:2] for o in out]] or [99])
                if dm >= 4 and (best is None or dm > best[0]): best = (dm, x, z, f)
            if not best: break
            out.append(best[1:]); taken.add((best[1], best[2]))
        return out

    wall, mach, boxes, pap = [], [], [], None
    if cfg['off']:
        for e in ents:
            if not (e.get('zombie_weapon_upgrade') and cfg['key'] in e.get('script_noteworthy', '').replace(' ', '')): continue
            g = GUN.get(e['zombie_weapon_upgrade'])
            if not g: warn(f"wall gun {e['zombie_weapon_upgrade']} is not in this game"); continue
            px, pz = P(e['origin']); r = wall_spot(px, pz, facing(float(e['angles'].split()[1])))
            if r: taken.add((r[1], r[2])); wall.append((g, r[1], r[2], r[3]))
            else: warn(f'wall gun {g}: no wall face near ({px:.0f},{pz:.0f})')
        for e in ents:
            if e.get('targetname') != 'zm_perk_machine' or cfg['perks'] not in e.get('script_string', ''): continue
            nm = e['script_noteworthy']
            if nm == 'specialty_weapupgrade': continue
            if nm not in PERK: warn(f'perk {nm} is not in this game'); continue
            px, pz = P(e['origin']); r = wall_spot(px, pz, facing(float(e['angles'].split()[1])), R=10)
            if r: taken.add((r[1], r[2])); mach.append((PERK[nm], r[1], r[2], r[3]))
            else: warn(f'perk {nm}: no wall face near ({px:.0f},{pz:.0f})')
        for i, name in enumerate(cfg['chests']):
            e = [x for x in ents if x.get('script_noteworthy') == name][0]
            px, pz = P(e['origin'])

            def bpred(x, z, d):
                if (x, z) in taken or (x, z) not in seen: return None
                ok = air(x, 1, z) and air(x, 2, z) and free(x, z) and air(x - d[0], 1, z - d[1]) and solid(x - 2 * d[0], 1, z - 2 * d[1]) and air(x + d[0], 1, z + d[1]) and air(x + d[1], 1, z + d[0]) and air(x - d[1], 1, z - d[0])
                return 0 if ok else None
            best = None
            for f in D:
                r = nearest(lambda x, z, d=D[f]: bpred(x, z, d), px, pz, 14)
                if r and (best is None or r[0] < best[0]): best = (r[0], r[1], r[2], f)
            if best: taken.add((best[1], best[2])); boxes.append((best[1], best[2], best[3], i == 0))
            else: warn(f'box {name}: no spot near ({px:.0f},{pz:.0f})')
    else:
        r = spread(1, [PL] + [(w['mx'], w['mz']) for w in windows])
        wall = [('ak74u', *r[0])] if r else []
        pm = spread(4, [PL] + [(w['mx'], w['mz']) for w in windows] + [(w[1], w[2]) for w in wall])
        mach = list(zip(['jug', 'speed', 'revive', 'doubletap'], [p[0] for p in pm], [p[1] for p in pm], [p[2] for p in pm]))
        bs = spread(1, [PL] + [(w['mx'], w['mz']) for w in windows] + [(w[1], w[2]) for w in wall])
        boxes = [(b[0], b[1], b[2], True) for b in bs]

    if cfg['pap']:
        e = [x for x in ents if x.get('targetname') == 'zm_perk_machine' and cfg['perks'] in x.get('script_string', '') and x['script_noteworthy'] == 'specialty_weapupgrade'][0]
        px, pz = P(e['origin']); best = None
        for f, d in D.items():
            lat = (abs(d[1]), abs(d[0]))
            for z in range(max(4, int(pz) - 14), min(SZ - 4, int(pz) + 15)):
                for x in range(max(4, int(px) - 14), min(SX - 4, int(px) + 15)):
                    # (x, z) = centre of the back row; the footprint runs 3 deep along d, wall behind the back row
                    cells = [(x + lat[0] * i + d[0] * j, z + lat[1] * i + d[1] * j) for i in (-1, 0, 1) for j in (0, 1, 2)]
                    if any((cx, cz) in taken for cx, cz in cells): continue
                    if not all(free(cx, cz) and air(cx, 3, cz) and air(cx, 4, cz) for cx, cz in cells): continue
                    if not all(solid(x + lat[0] * i - d[0], 1, z + lat[1] * i - d[1]) for i in (-1, 0, 1)): continue
                    st = (x + d[0] * 4, z + d[1] * 4)
                    if st not in seen: continue
                    c = math.hypot(x + d[0] - px, z + d[1] - pz)
                    if best is None or c < best[0]: best = (c, x, z, f, cells)
        if best:
            pap = best
            for cx, cz in best[4]: taken.add((cx, cz))
        else: warn('Pack-a-Punch: no 3x3 floor patch with a wall behind it near its BO2 spot')

    # ---- BO2 props the Survival script places (the wrecks that wall the playable area in): BO2 model on the street, its blocks as stand-ins (invisible barriers once the model is drawn)
    props, prop_cells = [], []
    if cfg['off']:
        for i, e in enumerate(x for x in ents if x.get('targetname') == 'game_mode_object' and x.get('script_noteworthy') == loc):
            pitch, yaw, roll = (float(v) for v in e['angles'].split()); pitch, roll = ((pitch + 180) % 360) - 180, ((roll + 180) % 360) - 180
            px, pz = P(e['origin'])
            if not inb(int(px), int(pz)): continue
            pts = model_points(e['model']); cells = {}
            if pts is not None:
                w = (rot(pitch, yaw, roll) @ pts.T).T / 40.0
                for xi, zi, yi in zip(np.floor(px + w[:, 0]).astype(int), np.floor(pz - w[:, 1]).astype(int), np.floor(1 + w[:, 2]).astype(int)):
                    lo, hi = cells.get((xi, zi), (yi, yi)); cells[(xi, zi)] = (min(lo, yi), max(hi, yi))
                cells = {k: (max(1, lo), hi) for k, (lo, hi) in cells.items() if hi >= 1 and air(int(k[0]), 1, int(k[1]))}
            for (xi, zi), (lo, hi) in sorted(cells.items()): prop_cells.append((f'{loc}_wreck{i}', int(xi), int(lo), int(zi), int(hi)))
            props.append(dict(id=f'{loc}_wreck{i}', model=e['model'], px=px, pz=pz, yaw=yaw, pitch=pitch, roll=roll, cells=cells))

    # ---- sheet origin: an open ground column near the player (the game reads the surface height there)
    def open_col(x, z): return all(air(x, y, z) for y in range(1, 16)) and solid(x, 0, z)
    o = nearest(lambda x, z: 0 if open_col(x, z) else None, PL[0], PL[1], 60)
    OX, OZ = o[1], o[2]
    T = lambda x, z: (x - OX, z - OZ)

    ops = [dict(id='tranzit_' + loc, order=0, op='import', block=loc, block2=None, x1=-OX, y1=0, z1=-OZ, x2=-OX, y2=0, z2=-OZ, stepX=1, stepZ=1, group='ground',
                note=f'Tranzit Reimagined {cfg["label"]}, cut out by tools/extract_tranzit.py (x/z = where its corner lands in the sheet frame, y = grass layer)')]
    a, b = T(2, 2), T(SX - 3, SZ - 3)
    ops.append(dict(id='barrier_ring', order=90, op='walls', block='minecraft:barrier', block2=None, x1=a[0], y1=1, z1=a[1], x2=b[0], y2=14, z2=b[1], stepX=1, stepZ=1, group='ground',
                    note='invisible wall round the cut so nobody walks off its edge'))

    def fill(id, block, x1, y1, z1, x2, y2, z2, order, note):
        p, q2 = T(x1, z1), T(x2, z2)
        ops.append(dict(id=id, order=order, op='fill', block=block, block2=None, x1=p[0], y1=y1, z1=p[1], x2=q2[0], y2=y2, z2=q2[1], stepX=1, stepZ=1, group='pap', note=note))

    paprows = []
    if pap:
        _, x, z, f, cells = pap; d = D[f]; lat = (abs(d[1]), abs(d[0]))
        def cell(i, j): return (x + lat[0] * i + d[0] * j, z + lat[1] * i + d[1] * j)
        (ax, az), (bx_, bz_) = cell(-1, 0), cell(1, 1)
        fill('pap_frame', 'zombiecraft:neon', ax, 1, az, bx_, 3, bz_, 2, 'Pack-a-Punch')
        (ax, az), (bx_, bz_) = cell(-1, 2), cell(1, 2)
        fill('pap_glass', 'minecraft:light_blue_stained_glass', ax, 2, az, bx_, 3, bz_, 3, 'Pack-a-Punch front glass')
        (ax, az), (bx_, bz_) = cell(-1, 0), cell(1, 1)
        fill('pap_top', 'minecraft:sea_lantern', ax, 4, az, bx_, 4, bz_, 4, 'Pack-a-Punch light')
        (ax, az), (bx_, bz_) = cell(-1, 0), cell(1, 2)
        T1, T2 = T(ax, az), T(bx_, bz_)
        paprows = [dict(id='pap1', x1=min(T1[0], T2[0]), y1=1, z1=min(T1[1], T2[1]), x2=max(T1[0], T2[0]), y2=4, z2=max(T1[1], T2[1]), facing=f, room='hall')]

    prop_rows = []
    for k, (pid, x, y1, z, y2) in enumerate(prop_cells):
        t = T(x, z)
        ops.append(dict(id=f'{pid}_c{k}', order=50, op='fill', block='minecraft:gray_concrete', block2=None, x1=t[0], y1=y1, z1=t[1], x2=t[0], y2=y2, z2=t[1], stepX=1, stepZ=1, group='props',
                        note='stand-in blocks for ' + pid + ' (invisible barriers once the BO2 model is drawn)'))
    for pr in props:
        t = T(pr['px'], pr['pz'])
        if pr['cells']:
            xs = [T(int(k[0]), int(k[1]))[0] for k in pr['cells']]; zs = [T(int(k[0]), int(k[1]))[1] for k in pr['cells']]; ys = [int(v) for lh in pr['cells'].values() for v in lh]
            hide = f'{min(xs)},{min(ys)},{min(zs)},{max(xs)},{max(ys)},{max(zs)}'
        else: hide = 'none'
        prop_rows.append(dict(id=pr['id'], model=pr['model'], x=round(t[0], 3), y=1.0, z=round(t[1], 3), yaw=round(-pr['yaw'], 2), scale=round(SCALE, 4), hide=hide, room='hall',
                              pitch=round(pr['pitch'], 2), roll=round(pr['roll'], 2), exact=True, fallback=('minecraft:gray_concrete' if pr['cells'] else None)))
    rows_w, rows_s = [], []
    for i, w in enumerate(windows):
        horiz = w['wall'] in 'NS'; t = T(w['a'], w['fixed']) if horiz else T(w['fixed'], w['a'])
        rows_w.append(dict(id=f'w{i + 1}', wall=w['wall'], fixed=(t[1] if horiz else t[0]), a=(t[0] if horiz else t[1]), width=3, y0=2, height=2, boards=6, room='hall',
                           boardBlock='zombiecraft:barricade_board[axis=%s]' % ('x' if horiz else 'z'), sillBlock='zombiecraft:wood_floor'))
        for k, (x, z) in enumerate(w['spots']):
            tt = T(x, z); rows_s.append(dict(id=f's{i + 1}{"ab"[k]}', window=f'w{i + 1}', x=tt[0], y=1, z=tt[1]))
    nw = min(windows, key=lambda w: math.hypot(w['mx'] - PL[0], w['mz'] - PL[1]))
    yaw = round(math.degrees(math.atan2(-(nw['mx'] - PL[0]), nw['mz'] - PL[1])), 1)
    tp = T(*PL)
    os.makedirs(OUT, exist_ok=True)

    def sheet(name, rows):
        with open(os.path.join(OUT, name), 'w', encoding='utf-8') as f: json.dump(rows, f, indent=1, ensure_ascii=False); f.write('\n')

    sheet('map_ops.json', ops); sheet('map_windows.json', rows_w); sheet('map_spawns.json', rows_s)
    sheet('map_player.json', [dict(id='spawn', x=tp[0], y=1, z=tp[1], yaw=yaw, room='hall')])
    sheet('map_wallbuys.json', [dict(id=f'wb{i + 1}', weaponId=g, x=T(x, z)[0], y=2, z=T(x, z)[1], facing=f, room='hall') for i, (g, x, z, f) in enumerate(wall)])
    sheet('map_boxes.json', [dict(id=f'bx{i + 1}', x=T(x, z)[0], y=1, z=T(x, z)[1], facing=f, initial=n, room='hall') for i, (x, z, f, n) in enumerate(boxes)])
    sheet('map_machines.json', [dict(id=f'm_{p}', kind='perk', perk=p, x=T(x, z)[0], y=1, z=T(x, z)[1], facing=f, room='hall') for p, x, z, f in mach])
    sheet('map_pap.json', paprows); sheet('map_doors.json', []); sheet('map_props.json', prop_rows)
    print(f'ok {loc}: {len(rows_w)} windows, {len(rows_s)} spawns, {len(wall)} wall guns, {len(boxes)} boxes, {len(mach)} perks, pap {bool(pap)}, {len(prop_rows)} BO2 props; sheet origin = cut ({OX},{OZ}), player cut {PL}')
    for w in windows: print('  window', w['wall'], w['mx'], w['mz'])


if __name__ == '__main__':
    for l in (LOCS if sys.argv[1:] == ['all'] else sys.argv[1:]): build(l)

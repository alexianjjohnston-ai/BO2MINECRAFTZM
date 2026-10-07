#!/usr/bin/env python3
"""Turn a map mesh export (OBJ with usemtl) of the BO2 Bus Depot into the block file the game pastes (maps_local/bo2_depot.json.gz, same format as extract_tranzit.py).
The mesh is the player's own export from their BO2 install (e.g. Greyhound / Cordycep: zm_transit or zm_transit_gump_busstation, OBJ with materials), never committed.
  python tools/obj_to_blocks.py <export.obj> [--up y|z] [--floor-z UNITS] [--scale S] [--check]
Coordinates: BO2 game units, x east, y north, z up. A block is 40 units: bx = (x + 8400) / 40, bz = (6900 - y) / 40 (the same frame gen_depot.py / gen_bo2_depot.py use).
--up y   the OBJ is Y-up (x, y, z)_obj = (x, z, -y)_game  (what most exporters write); --up z (default) keeps game axes. --scale converts the OBJ's unit to BO2 units.
--floor-z  game z (units) of the hall floor (standing level). Default -56: the floor/street height the entity file implies (perk struct, wrecks and the box room sit at -1.4 blocks, window sills 0.9 above).
Only the playable area (REGION, as in gen_bo2_depot.py) is converted. Surfaces become one-block-thick shells (walls, floors, roofs); the ground under floors is filled down.
Materials -> blocks by keyword (MATERIALS below, first match wins; horizontal faces use the floor variants), so the BO2 textures of the mod's own blocks apply.
--check compares the result with the exact entity positions (windows must sit in walls, the box and wall guns must have air in front) and prints what is off."""
import argparse, collections, gzip, hashlib, json, math, os, re, sys
import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
REGION = (0, 15, 72, 80)  # bx0, bz0, bx1, bz1 (same as gen_bo2_depot.py)
DEPTH = 4                 # rows kept below the floor
HEIGHT = 24               # rows kept above it
# (regex on the material name, wall block, floor block)
MATERIALS = [
    (r'glass_?brick|glassblock|diamond_?mesh|window_?block', 'zombiecraft:glass_brick', 'zombiecraft:glass_brick'),
    (r'glass|window|(?<![a-z])pane(?![a-z])', 'minecraft:light_gray_stained_glass', 'minecraft:light_gray_stained_glass'),
    (r'asphalt|road|tarmac', 'zombiecraft:asphalt', 'zombiecraft:asphalt'),
    (r'brick', 'zombiecraft:red_brick', 'zombiecraft:concrete_floor'),
    (r'tile|linoleum|checker', 'zombiecraft:depot_tile', 'zombiecraft:depot_tile'),
    (r'wood|plank|board|door', 'zombiecraft:wood_floor', 'zombiecraft:wood_floor'),
    (r'metal|steel|rust|corrug|siding|vent|pipe|rail', 'zombiecraft:metal_panel', 'zombiecraft:metal_panel'),
    (r'plaster|stucco|paint|wall', 'zombiecraft:plaster_wall', 'zombiecraft:concrete_floor'),
    (r'light|lamp|fluor|neon', 'zombiecraft:light_panel', 'zombiecraft:light_panel'),
    (r'concrete|cement|stone|curb|sidewalk|cinder|block', 'zombiecraft:concrete_wall', 'zombiecraft:concrete_floor'),
    (r'dirt|grass|ground|terrain|soil|mud|gravel|sand', 'minecraft:coarse_dirt', 'minecraft:coarse_dirt'),
]
DEFAULT = ('zombiecraft:concrete_wall', 'zombiecraft:concrete_floor')


def material_blocks(name):
    n = name.lower()
    for rx, wall, floor in MATERIALS:
        if re.search(rx, n): return wall, floor
    return DEFAULT


def read_obj(path, up, scale):
    """-> (triangles (N,3,3) in BO2 game units, material index per triangle, material names)."""
    V, F, M, mats, cur = [], [], [], [], 0
    mid = {}
    with open(path, 'r', errors='ignore') as f:
        for line in f:
            if line.startswith('v '):
                p = line.split()
                V.append((float(p[1]), float(p[2]), float(p[3])))
            elif line.startswith('usemtl'):
                nm = line.split(None, 1)[1].strip()
                if nm not in mid: mid[nm] = len(mats); mats.append(nm)
                cur = mid[nm]
            elif line.startswith('f '):
                idx = []
                for tok in line.split()[1:]:
                    i = int(tok.split('/')[0]); idx.append(i - 1 if i > 0 else len(V) + i)
                for k in range(1, len(idx) - 1):
                    F.append((idx[0], idx[k], idx[k + 1])); M.append(cur)
    V = np.array(V, dtype=np.float64) * scale
    if up == 'y': V = np.stack([V[:, 0], -V[:, 2], V[:, 1]], axis=1)
    tri = V[np.array(F, dtype=np.int64)]
    return tri, np.array(M, dtype=np.int32), mats or ['default']


def to_blocks(tri):
    """game units -> block coordinates (bx, bz, by-up)."""
    out = np.empty_like(tri)
    out[..., 0] = (tri[..., 0] + 8400) / 40
    out[..., 1] = (6900 - tri[..., 1]) / 40
    out[..., 2] = tri[..., 2] / 40
    return out


def rasterise(tri, step=0.3):
    """points sampled over the triangles -> (points (M,3), triangle index per point)."""
    e = np.maximum.reduce([np.linalg.norm(tri[:, 1] - tri[:, 0], axis=1), np.linalg.norm(tri[:, 2] - tri[:, 1], axis=1), np.linalg.norm(tri[:, 0] - tri[:, 2], axis=1)])
    n = np.maximum(1, np.ceil(e / step).astype(int))
    pts, owner = [], []
    for k in np.unique(n):
        sel = np.flatnonzero(n == k)
        uv = np.array([(i / k, j / k) for i in range(k + 1) for j in range(k + 1 - i)])
        a, b = uv[:, 0:1], uv[:, 1:2]
        P = (1 - a - b)[None] * tri[sel, 0][:, None, :] + a[None] * tri[sel, 1][:, None, :] + b[None] * tri[sel, 2][:, None, :]
        pts.append(P.reshape(-1, 3)); owner.append(np.repeat(sel, len(uv)))
    return np.concatenate(pts), np.concatenate(owner)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('obj'); ap.add_argument('--up', default='z', choices=['y', 'z']); ap.add_argument('--scale', type=float, default=1.0)
    ap.add_argument('--floor-z', type=float, default=-56.0); ap.add_argument('--check', action='store_true'); ap.add_argument('--name', default='bo2_depot')
    a = ap.parse_args()
    x0, z0, x1, z1 = REGION
    print('reading', a.obj)
    tri, mat, mats = read_obj(a.obj, a.up, a.scale)
    tri = to_blocks(tri)
    print(len(tri), 'triangles, materials:', len(mats))
    lo = tri.min(axis=1); hi = tri.max(axis=1)
    keep = (hi[:, 0] >= x0 - 1) & (lo[:, 0] <= x1 + 1) & (hi[:, 1] >= z0 - 1) & (lo[:, 1] <= z1 + 1)
    tri, mat = tri[keep], mat[keep]
    print(len(tri), 'inside the playable area')
    if not len(tri): sys.exit('nothing inside the region: check --up / --scale (expected block coords bx 0..72, bz 15..80 after the conversion)')
    nrm = np.cross(tri[:, 1] - tri[:, 0], tri[:, 2] - tri[:, 0]); ar = np.linalg.norm(nrm, axis=1) + 1e-9
    horiz = np.abs(nrm[:, 2]) / ar > 0.8
    floor = a.floor_z / 40
    print('floor height (blocks):', floor)
    P, own = rasterise(tri)
    ix = np.floor(P[:, 0]).astype(int); iz = np.floor(P[:, 1]).astype(int); iy = np.floor(P[:, 2] - floor - 1e-3).astype(int) + 1
    ok = (ix >= x0) & (ix < x1) & (iz >= z0) & (iz < z1) & (iy >= -DEPTH) & (iy < HEIGHT)
    ix, iz, iy, own = ix[ok], iz[ok], iy[ok], own[ok]
    palette = ['minecraft:air']; pid = {'minecraft:air': 0}
    def bid(b):
        if b not in pid: pid[b] = len(palette); palette.append(b)
        return pid[b]
    sx, sz, sy = x1 - x0, z1 - z0, HEIGHT + DEPTH
    grid = np.zeros((sy, sz, sx), np.int32)
    hmask = (np.abs(nrm[own, 2]) / ar[own]) > 0.8
    lut_w = np.array([bid(material_blocks(m)[0]) for m in mats]); lut_f = np.array([bid(material_blocks(m)[1]) for m in mats])
    ids = np.where(hmask, lut_f[mat[own]], lut_w[mat[own]])
    # vertical surfaces win over floors in a shared cell (walls stay walls)
    order = np.argsort(hmask, kind='stable')[::-1]
    grid[iy[order] + DEPTH, iz[order] - z0, ix[order] - x0] = ids[order]
    # ground: fill under every floor cell
    ground = bid('minecraft:stone')
    for y in range(DEPTH - 1, -1, -1):
        under = (grid[DEPTH] != 0) & (grid[y] == 0)
        grid[y][under] = ground
    flat = grid.ravel()
    starts = np.concatenate([[0], np.flatnonzero(np.diff(flat)) + 1])
    counts = np.diff(np.concatenate([starts, [len(flat)]]))
    doc = {'id': a.name, 'label': 'BO2 Bus Depot (player export)', 'size': [sx, sy, sz], 'groundRow': DEPTH, 'worldBox': [x0, z0, x1, z1],
           'palette': palette, 'rle': [[int(flat[s]), int(c)] for s, c in zip(starts, counts)], 'entities': [], 'spawners': []}
    out = os.path.join(HERE, '..', 'maps_local'); os.makedirs(out, exist_ok=True)
    with gzip.open(os.path.join(out, a.name + '.json.gz'), 'wt') as f: json.dump(doc, f, separators=(',', ':'))
    print('wrote maps_local/%s.json.gz: %dx%dx%d, %d block kinds, %d solid cells' % (a.name, sx, sy, sz, len(palette), int((grid != 0).sum())))
    # top-down preview of the highest surface per column
    try:
        from PIL import Image
        top = sy - 1 - np.argmax((grid != 0)[::-1], axis=0)
        im = Image.new('RGB', (sx * 8, sz * 8)); px = im.load()
        for z in range(sz):
            for x in range(sx):
                g = grid[top[z, x], z, x]
                c = (30, 30, 30) if g == 0 else tuple(hashlib.md5(palette[g].encode()).digest()[:3])
                for dx in range(8):
                    for dz in range(8): px[x * 8 + dx, z * 8 + dz] = c
        im.save(os.path.join(out, a.name + '.png'))
        print('top-down preview: maps_local/%s.png' % a.name)
    except ImportError: pass
    if a.check: check(grid, palette)


def check(grid, palette):
    """Entity positions are exact; the geometry has to agree with them. Reports what does not."""
    sh = os.path.join(HERE, '..', 'sheets', 'maps', 'bo2_depot')
    x0, z0 = REGION[0], REGION[1]
    ld = lambda n: json.load(open(os.path.join(sh, n + '.json')))
    def cell(sx, y, sz):
        gx, gz = sx + 40 - x0, sz + 47 - z0
        if not (0 <= gx < grid.shape[2] and 0 <= gz < grid.shape[1] and 0 <= y + DEPTH < grid.shape[0]): return None
        return grid[y + DEPTH, gz, gx] != 0
    bad = 0
    for w in ld('map_windows'):
        horiz = w['wall'] in 'NS'
        cells = [(w['a'] + i, w['y0'] + j, w['fixed']) if horiz else (w['fixed'], w['y0'] + j, w['a'] + i) for i in range(w['width']) for j in range(w['height'])]
        solid = sum(1 for c in cells if (cell(*(c if horiz else (c[0], c[1], c[2]))) is True))
        print(f"  window {w['id']}: {solid}/{len(cells)} opening cells sit in a wall"); bad += solid < len(cells) // 2
    for g in ld('map_wallbuys'):
        d = {'north': (0, -1), 'south': (0, 1), 'east': (1, 0), 'west': (-1, 0)}[g['facing']]
        back = cell(g['x'] - d[0], g['y'], g['z'] - d[1]); front = cell(g['x'] + d[0], g['y'], g['z'] + d[1])
        print(f"  wall gun {g['id']} {g['weaponId']}: wall behind={back} free in front={front is False}"); bad += (not back) or (front is True)
    b = ld('map_boxes')[0]; print('  box cell free:', cell(b['x'], 1, b['z']) is False); p = ld('map_player')[0]; print('  player cell free:', cell(p['x'], 1, p['z']) is False)
    print('OK' if not bad else f'{bad} things do not agree with the BO2 positions: check --up / --scale / --floor-z, then the export itself')


if __name__ == '__main__':
    main()

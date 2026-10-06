#!/usr/bin/env python3
"""Preflight for the Zombiecraft sheets. Lays every sheet over the others and reports:
  - unfilled / mistyped cells (every row x column is a checkbox)
  - references between sheets that do not resolve (including keys the Java code asks for)
  - semantic checks (audio files exist in the player's BO2 banks, geometry of the map, one start weapon ...)
Exit code 0 only when nothing is unfilled or unresolved. Usage: preflight.py [--bo2 DIR] [--code DIR]"""
import json, os, re, sys, argparse, collections

HERE = os.path.dirname(os.path.abspath(__file__))
SH = os.path.join(HERE, '..', 'sheets')
ap = argparse.ArgumentParser()
ap.add_argument('--bo2', default=None, help='BO2 install dir: verify audio_files against the real banks')
ap.add_argument('--code', default=os.path.join(HERE, '..', 'mod', 'src'), help='folder scanned for Sheets.sys("key") style references')
ap.add_argument('--quiet', action='store_true')
args = ap.parse_args()

schema = json.load(open(os.path.join(SH, 'schema.json'), encoding='utf-8'))
errors, warns = [], []
def err(m): errors.append(m)
def warn(m): warns.append(m)

data, cells, filled = {}, 0, 0
per = {}
# ---------- 1. cells and types
for name, sc in schema.items():
    path = os.path.join(SH, sc['file'])
    if not os.path.exists(path):
        err(f"[{name}] sheet file missing: {sc['file']}"); continue
    rows = json.load(open(path, encoding='utf-8'))
    data[name] = rows
    cols = sc['columns']
    seen = set(); nf = 0; nc = 0
    for i, row in enumerate(rows):
        rid = row.get(sc['key'], f'#{i}')
        if rid in seen: err(f"[{name}] duplicate key {rid!r}")
        seen.add(rid)
        for extra in set(row) - set(cols): err(f"[{name}.{rid}] column not in schema: {extra}")
        for col, spec in cols.items():
            nc += 1
            v = row.get(col, None)
            if v is None or (isinstance(v, str) and v.strip() == ''):
                if spec.get('optional'):
                    nf += 1; continue
                err(f"[{name}.{rid}] UNFILLED cell: {col}"); continue
            nf += 1
            t = spec['t']
            ok = (t == 'string' and isinstance(v, str)) or (t == 'number' and isinstance(v, (int, float)) and not isinstance(v, bool)) or (t == 'boolean' and isinstance(v, bool))
            if not ok: err(f"[{name}.{rid}] {col}: expected {t}, got {type(v).__name__} {v!r}"); continue
            if 'enum' in spec and v not in spec['enum']: err(f"[{name}.{rid}] {col}: {v!r} not in {spec['enum']}")
    cells += nc; filled += nf; per[name] = (len(rows), nc, nc - nf)

# ---------- 2. references
index = {n: {r.get(sc['key']) for r in data.get(n, [])} for n, sc in schema.items()}
for name, sc in schema.items():
    for col, spec in sc['columns'].items():
        if 'ref' in spec:
            tsheet, tcol = spec['ref'].split('.')
            targets = {r.get(tcol) for r in data.get(tsheet, [])}
            for row in data.get(name, []):
                v = row.get(col)
                if v is not None and v not in targets:
                    err(f"[{name}.{row.get(sc['key'])}] {col} -> {spec['ref']}: {v!r} does not resolve")

# keys the Java code asks for
sysKeys = {r['id'] for r in data.get('systems', [])}
used = collections.defaultdict(set)
pat = re.compile(r'Sheets\.sys(?:Int)?\(\s*"([a-z0-9_]+)"')
if os.path.isdir(args.code):
    for root, _, fs in os.walk(args.code):
        for f in fs:
            if f.endswith('.java'):
                txt = open(os.path.join(root, f), encoding='utf-8').read()
                for m in pat.finditer(txt): used[m.group(1)].add(f)
    for k, fs in sorted(used.items()):
        if k not in sysKeys: err(f"[code] {sorted(fs)} ask for systems key {k!r} which is not in systems.json")
    for k in sorted(sysKeys - set(used)):
        if k not in ('max_round_rows',): warn(f"[systems.{k}] defined but no code reads it yet")

# ---------- 3. semantics
W = {r['id']: r for r in data.get('weapons', [])}
starts = [w for w in W.values() if w.get('start')]
if len(starts) != 1: err(f"[weapons] expected exactly one start weapon, found {len(starts)}")
for r in data.get('box_pool', []):
    w = W.get(r['weaponId'])
    if w and w['boxWeight'] <= 0: err(f"[box_pool.{r['id']}] weapon {w['id']} has boxWeight 0")
poolIds = {r['weaponId'] for r in data.get('box_pool', [])}
for w in W.values():
    if w['boxWeight'] > 0 and w['id'] not in poolIds: err(f"[weapons.{w['id']}] boxWeight>0 but not in box_pool")
for r in data.get('map_wallbuys', []):
    w = W.get(r['weaponId'])
    if w and w.get('wallCost') is None: err(f"[map_wallbuys.{r['id']}] weapon {w['id']} has no wallCost")
wallw = {r['weaponId'] for r in data.get('map_wallbuys', [])}
for w in W.values():
    if w.get('wallCost') is not None and w['id'] not in wallw: warn(f"[weapons.{w['id']}] has a wallCost but no wall-buy on the map")
    if w['upgrade']:
        if not any(x.get('papId') == w['id'] for x in W.values()): err(f"[weapons.{w['id']}] is an upgrade row but no weapon names it as papId")
    else:
        if w.get('papId') is None or w['papId'] not in W or not W[w['papId']]['upgrade']: err(f"[weapons.{w['id']}] papId must name an upgrade row")
        if w.get('wallCost') is None and w['boxWeight'] <= 0 and not w['start']: err(f"[weapons.{w['id']}] cannot be obtained (no wall, not in box, not the start weapon)")
    if w['projectile'] and (w['projSpeed'] <= 0 or w['explRadius'] <= 0): err(f"[weapons.{w['id']}] projectile weapon needs projSpeed and explRadius")
    if w['projectile'] and not (w.get('cueExplosion') or w['kind'] != 'raygun'): err(f"[weapons.{w['id']}] projectile without an explosion cue")
if sum(1 for b in data.get('map_boxes', []) if b['initial']) != 1: err("[map_boxes] exactly one box must be initial")
spawnWin = {s['window'] for s in data.get('map_spawns', [])}
for wdw in data.get('map_windows', []):
    if wdw['id'] not in spawnWin: err(f"[map_windows.{wdw['id']}] no zombie spawn assigned to this window")
rr = sorted(r['round'] for r in data.get('rounds', []))
if rr != list(range(1, len(rr) + 1)): err("[rounds] round numbers are not contiguous from 1")
mx = [r['value'] for r in data.get('systems', []) if r['id'] == 'max_round_rows']
if mx and len(rr) != int(mx[0]): err(f"[rounds] {len(rr)} rows but systems.max_round_rows = {int(mx[0])}")

# bo2 models (converted at runtime from the player's own install; names must be unique and every weapon needs a gun model)
mids = [m['id'] for m in data.get('bo2_models', [])]
for i in {x for x in mids if mids.count(x) > 1}: err(f"[bo2_models.{i}] duplicate id")
wids = {w['id'] for w in data.get('weapons', [])}
for m in data.get('bo2_models', []):
    if not m['xmodel']: err(f"[bo2_models.{m['id']}] empty xmodel")
    if m['group'] == 'gun' and m['id'] not in wids: err(f"[bo2_models.{m['id']}] gun model for unknown weapon")
    if m['group'] == 'gun' and not m['world']: err(f"[bo2_models.{m['id']}] gun needs a world model")
gunModels = {m['id'] for m in data.get('bo2_models', []) if m['group'] == 'gun'}
for w in data.get('weapons', []):
    base = w['id'][:-4] if w.get('upgrade') and w['id'].endswith('_pap') else w['id']
    if w['id'] not in gunModels and base not in gunModels: err(f"[weapons.{w['id']}] no gun model in bo2_models")

# audio
files = collections.defaultdict(list)
for f in data.get('audio_files', []): files[f['cue']].append(f)
for a in data.get('audio', []):
    fl = files.get(a['cue'], [])
    if not fl: err(f"[audio.{a['cue']}] no audio_files rows"); continue
    if a['variants'] != len(fl): err(f"[audio.{a['cue']}] variants={a['variants']} but {len(fl)} files")
    if a['positional'] and any(f['channels'] != 1 for f in fl): err(f"[audio.{a['cue']}] positional cue has a stereo file")
    if a['positional'] and a['category'] == 'music': err(f"[audio.{a['cue']}] music cannot be positional")
usedCues = set()
for sh in ('weapons', 'zombies'):
    for row in data.get(sh, []):
        for col, v in row.items():
            if col.startswith('cue') and isinstance(v, str): usedCues.add(v)
for c in sorted({a['cue'] for a in data.get('audio', [])} - usedCues):
    pass  # base cues are used directly by code; checked below via the code scan
codeCues = set()
cpat = re.compile(r'Cue\.\w+\(\s*"([a-z0-9_]+)"')
if os.path.isdir(args.code):
    for root, _, fs in os.walk(args.code):
        for f in fs:
            if f.endswith('.java'):
                for m in cpat.finditer(open(os.path.join(root, f), encoding='utf-8').read()): codeCues.add(m.group(1))
    for c in sorted(codeCues - {a['cue'] for a in data.get('audio', [])}): err(f"[code] plays cue {c!r} which is not in audio.json")

# bank check against the real install
if args.bo2:
    import struct
    cache = {}
    for f in data.get('audio_files', []):
        b = f['bank']
        if b not in cache:
            p = os.path.join(args.bo2, 'sound', b)
            if not os.path.exists(p): cache[b] = None; err(f"[audio_files] bank {b} not found in {args.bo2}"); continue
            h = open(p, 'rb').read(0x800)
            ec, = struct.unpack_from('<I', h, 0x14); esz, = struct.unpack_from('<I', h, 0x08); eoff, = struct.unpack_from('<Q', h, 0x28)
            fh = open(p, 'rb'); fh.seek(eoff); raw = fh.read(ec * esz)
            cache[b] = {struct.unpack_from('<I', raw, i * esz)[0]: struct.unpack_from('<II', raw, i * esz + 4) for i in range(ec)}
        if cache[b] is None: continue
        e = cache[b].get(f['entryId'])
        if e is None: err(f"[audio_files.{f['id']}] entry {f['entryId']} not in {b}")
        elif e[0] != f['size']: err(f"[audio_files.{f['id']}] size {e[0]} in {b} != sheet {f['size']}")

# ---------- 4. geometry oracle: build the map in memory and check what the code will rely on
vox = {}
def rng(a, b): return range(min(a, b), max(a, b) + 1)
for o in sorted(data.get('map_ops', []), key=lambda r: r['order']):
    blk = o['block']; b2 = o.get('block2')
    xs, ys, zs = rng(o['x1'], o['x2']), rng(o['y1'], o['y2']), rng(o['z1'], o['z2'])
    if o['op'] == 'fill':
        for x in xs:
            for y in ys:
                for z in zs: vox[(x, y, z)] = blk
    elif o['op'] == 'walls':
        x1, x2, z1, z2 = min(o['x1'], o['x2']), max(o['x1'], o['x2']), min(o['z1'], o['z2']), max(o['z1'], o['z2'])
        for x in xs:
            for y in ys:
                for z in zs:
                    if x in (x1, x2) or z in (z1, z2): vox[(x, y, z)] = blk
    elif o['op'] == 'checker':
        for x in xs:
            for y in ys:
                for z in zs: vox[(x, y, z)] = blk if (x + z) % 2 == 0 else b2
    elif o['op'] == 'grid':
        sx, sz = max(1, int(o['stepX'])), max(1, int(o['stepZ']))
        for x in range(min(o['x1'], o['x2']), max(o['x1'], o['x2']) + 1, sx):
            for z in range(min(o['z1'], o['z2']), max(o['z1'], o['z2']) + 1, sz):
                for y in ys: vox[(x, y, z)] = blk
AIR = 'minecraft:air'
def solid(p): return vox.get(p, AIR) != AIR and p[1] >= 0 or (p[1] == 0)   # y=0 is the ground layer, always solid
def air(p): return p[1] >= 1 and vox.get(p, AIR) == AIR
# windows: wall cells to carve
def window_cells(w):
    cells = []
    for i in range(w['width']):
        for j in range(w['height']):
            y = w['y0'] + j
            if w['wall'] in ('S', 'N'): cells.append((w['a'] + i, y, w['fixed']))
            else: cells.append((w['fixed'], y, w['a'] + i))
    return cells
DIR = {'north': (0, 0, -1), 'south': (0, 0, 1), 'east': (1, 0, 0), 'west': (-1, 0, 0)}
OUT = {'S': (0, 0, 1), 'N': (0, 0, -1), 'E': (1, 0, 0), 'W': (-1, 0, 0)}
for w in data.get('map_windows', []):
    cs = window_cells(w)
    if len(cs) != w['boards']: warn(f"[map_windows.{w['id']}] {w['boards']} boards but {len(cs)} opening cells (one board per cell is assumed)")
    for c in cs:
        if vox.get(c, AIR) != 'minecraft:white_concrete' and vox.get(c, AIR) == AIR: err(f"[map_windows.{w['id']}] opening cell {c} is not inside a wall")
    for c in cs: vox[c] = AIR
    # sill row under the opening
    for i in range(w['width']):
        sx = (w['a'] + i, w['y0'] - 1, w['fixed']) if w['wall'] in ('S', 'N') else (w['fixed'], w['y0'] - 1, w['a'] + i)
        vox[sx] = w['sillBlock']
for w in data.get('map_windows', []):
    d = OUT[w['wall']]
    mid = window_cells(w)[len(window_cells(w)) // 2]
    outside = (mid[0] + d[0], 1, mid[2] + d[2]); inside = (mid[0] - d[0], 1, mid[2] - d[2])
    if not air(outside): err(f"[map_windows.{w['id']}] outside standing spot {outside} is not free air")
    if not air(inside): err(f"[map_windows.{w['id']}] inside standing spot {inside} is not free air")
for s in data.get('map_spawns', []):
    p = (s['x'], s['y'], s['z'])
    if not (air(p) and air((s['x'], s['y'] + 1, s['z']))): err(f"[map_spawns.{s['id']}] {p} is blocked")
for r in data.get('map_wallbuys', []):
    f = (r['x'], r['y'], r['z']); d = DIR[r['facing']]; back = (f[0] - d[0], f[1], f[2] - d[2])
    if not air(f): err(f"[map_wallbuys.{r['id']}] frame cell {f} is not free air")
    if vox.get(back, AIR) == AIR: err(f"[map_wallbuys.{r['id']}] nothing behind the frame at {back}")
for r in data.get('map_boxes', []):
    p = (r['x'], r['y'], r['z']); d = DIR[r['facing']]; back = (p[0] - d[0], p[1], p[2] - d[2])
    if not air(p): err(f"[map_boxes.{r['id']}] chest cell {p} is not free air")
    if vox.get(back, AIR) == AIR: err(f"[map_boxes.{r['id']}] chest has nothing behind it at {back}")
    front = (p[0] + d[0], p[1], p[2] + d[2])
    if not air(front): err(f"[map_boxes.{r['id']}] nothing free in front of the chest at {front}")
for r in data.get('map_pap', []):
    d = DIR[r['facing']]
    cx = (r['x1'] + r['x2']) // 2; cz = (r['z1'] + r['z2']) // 2
    stand = (cx + d[0] * (abs(r['x2'] - r['x1']) // 2 + 2), 1, cz + d[2] * (abs(r['z2'] - r['z1']) // 2 + 2))
    if not air(stand): err(f"[map_pap.{r['id']}] nowhere to stand in front of the machine at {stand}")
for r in data.get('map_player', []):
    p = (r['x'], r['y'], r['z'])
    if not (air(p) and air((p[0], p[1] + 1, p[2]))): err(f"[map_player.{r['id']}] spawn {p} is blocked")
# connectivity: flood fill walkable cells (y=1 plane) from the player spawn; windows open for the test, so spawns outside will be connected through them
def walkable(x, z): return air((x, 1, z)) and air((x, 2, z))
start = (data['map_player'][0]['x'], data['map_player'][0]['z']) if data.get('map_player') else None
if start:
    seen = {start}; q = [start]
    while q:
        x, z = q.pop()
        for dx, dz in ((1, 0), (-1, 0), (0, 1), (0, -1)):
            n = (x + dx, z + dz)
            if -40 <= n[0] <= 40 and -30 <= n[1] <= 30 and n not in seen and walkable(*n): seen.add(n); q.append(n)
    # with all windows open every spawn must reach the player
    for w in data.get('map_windows', []): pass
    # windows are open in vox (carved) - but their sill makes y=1 solid; open cells at y=2..3: check reachability at y=2 plane through the sill
    # (zombies jump the 1-block sill) so test the plane y=2 instead for window crossing
    def walk2(x, z): return air((x, 2, z)) and air((x, 3, z))
    seen2 = {start}; q = [start]
    while q:
        x, z = q.pop()
        for dx, dz in ((1, 0), (-1, 0), (0, 1), (0, -1)):
            n = (x + dx, z + dz)
            if -40 <= n[0] <= 40 and -30 <= n[1] <= 30 and n not in seen2 and (walkable(*n) or (walk2(*n) and (walkable(x, z) or walk2(x, z)))): seen2.add(n); q.append(n)
    for s in data.get('map_spawns', []):
        if (s['x'], s['z']) not in seen2: err(f"[map_spawns.{s['id']}] cannot reach the player spawn even with every window open")
    for r in data.get('map_boxes', []):
        if (r['x'], r['z']) not in seen2 and (r['x'] + DIR[r['facing']][0], r['z'] + DIR[r['facing']][2]) not in seen2: err(f"[map_boxes.{r['id']}] not reachable from the player spawn")
    for r in data.get('map_wallbuys', []):
        if (r['x'], r['z']) not in seen2: err(f"[map_wallbuys.{r['id']}] frame cell not reachable from the player spawn")

# ---------- report
hs = collections.Counter(h['status'] for h in data.get('hooks', []))
if not args.quiet:
    print("SHEET              rows  cells  unfilled")
    for n, (r, c, u) in per.items(): print(f"  {n:16s} {r:5d} {c:6d} {u:5d}")
    print(f"  TOTAL cells {cells}, filled {filled}")
    print(f"  hooks: {dict(hs)}")
for w in warns: print("WARN ", w)
for e in errors: print("ERROR", e)
print(f"\npreflight: {len(errors)} error(s), {len(warns)} warning(s)")
sys.exit(1 if errors else 0)

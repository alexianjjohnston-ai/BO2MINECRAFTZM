#!/usr/bin/env python3
"""Gameplay sheets for the Bus Depot of Tranzit Reimagined (sheets/maps/tranzit_depot/map_*.json).
The build itself is NOT in the repo: the game pastes it from maps_local/depot.json.gz (python tools/extract_tranzit.py <download>) with the "import" op.
Positions below are in extraction coordinates (ex, ez; y is the row above the grass layer); T() shifts them into the sheet frame.
The sheet origin (0, 0) is a plain ground column on purpose: Game.start() reads the surface height there to find the ground layer again on a restart.
Everything is checked against the real blocks (same rules as preflight.py's geometry oracle); run needs maps_local/depot.json.gz.
Select the map with  map=tranzit_depot  in config/zombiecraft.properties  (or -Dzombiecraft.map=tranzit_depot)."""
import gzip, json, os, sys
import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, '..', 'sheets', 'maps', 'tranzit_depot')
SRC = os.path.join(HERE, '..', 'maps_local', 'depot.json.gz')
OX, OZ = 30, 28  # extraction (30, 28) is sheet (0, 0)


def T(x, z): return x - OX, z - OZ


doc = json.load(gzip.open(SRC, 'rt'))
SX, SY, SZ = doc['size']; GR = doc['groundRow']
grid = np.concatenate([np.full(c, i, np.int32) for i, c in doc['rle']]).reshape(SY, SZ, SX)
pal = doc['palette']
vox = {}  # (ex, y, ez) -> block name for the cells our ops/windows change; the rest reads from grid


def at(x, y, z):
    if (x, y, z) in vox: return vox[(x, y, z)]
    if not (0 <= x < SX and 0 <= z < SZ and 0 <= GR + y < SY): return 'minecraft:air'
    return pal[grid[GR + y, z, x]]


def air(x, y, z): return y >= 1 and at(x, y, z).startswith('minecraft:air')
def solid(x, y, z): return not at(x, y, z).startswith('minecraft:air')


errors = []
def err(m): errors.append(m)


ops = []
def op(id, kind, block, x1, y1, z1, x2, y2, z2, group, note, order, block2=None):
    a, b = T(x1, z1), T(x2, z2)
    ops.append(dict(id=id, order=order, op=kind, block=block, block2=block2, x1=a[0], y1=y1, z1=a[1], x2=b[0], y2=y2, z2=b[1], stepX=1, stepZ=1, group=group, note=note))
    if kind == 'fill':
        for x in range(min(x1, x2), max(x1, x2) + 1):
            for y in range(min(y1, y2), max(y1, y2) + 1):
                for z in range(min(z1, z2), max(z1, z2) + 1): vox[(x, y, z)] = block


# ---- the pasted build, then our changes on top of it
ops.append(dict(id='tranzit_depot', order=0, op='import', block='depot', block2=None, x1=-OX, y1=0, z1=-OZ, x2=-OX, y2=0, z2=-OZ, stepX=1, stepZ=1, group='ground',
                note='Tranzit Reimagined bus depot, cut out by tools/extract_tranzit.py (x/z = where its corner lands in the sheet frame, y = grass layer)'))
op('door_back_cut', 'fill', 'minecraft:air', 53, 1, 11, 54, 2, 11, 'building', 'doorway in the partition to the back room (sealed by door_back)', 1)
# Pack-a-Punch against the north wall of the north room (same blocks as the built-in depot's machine)
op('pap_frame', 'fill', 'zombiecraft:neon', 52, 1, 12, 54, 3, 13, 'pap', 'Pack-a-Punch', 2)
op('pap_glass', 'fill', 'minecraft:light_blue_stained_glass', 52, 2, 14, 54, 3, 14, 'pap', 'Pack-a-Punch front glass', 3)
op('pap_top', 'fill', 'minecraft:sea_lantern', 52, 4, 12, 54, 4, 13, 'pap', 'Pack-a-Punch light', 4)

# ---- windows: (id, wall, fixed, a, room)  3 wide, 2 tall, sill one below (same as the built-in depot)
WIN = [('w_hall_e1', 'E', 63, 29, 'hall'), ('w_hall_e2', 'E', 63, 34, 'hall'), ('w_hall_s', 'S', 42, 57, 'hall'), ('w_hall_e3', 'E', 59, 21, 'hall'),
       ('w_north_e', 'E', 59, 13, 'north'), ('w_north_w', 'W', 47, 14, 'north'), ('w_back_n', 'N', 6, 51, 'back')]
OUT_DIR = {'S': (0, 1), 'N': (0, -1), 'E': (1, 0), 'W': (-1, 0)}
windows = []
for id, wall, fixed, a, room in WIN:
    horiz = wall in 'NS'
    t = T(a, fixed) if horiz else T(fixed, a)
    cells = [((a + i, y, fixed) if horiz else (fixed, y, a + i)) for i in range(3) for y in (2, 3)]
    for c in cells:
        if not solid(*c): err(f'window {id}: opening cell {c} is air (not inside a wall)')
    mid = cells[len(cells) // 2]; d = OUT_DIR[wall]
    for k in (1, 2):
        if not (air(mid[0] - k * d[0], 1, mid[2] - k * d[1])): err(f'window {id}: inside spot {k} block in is blocked')
    if not air(mid[0] + d[0], 1, mid[2] + d[1]): err(f'window {id}: outside spot is blocked')
    windows.append(dict(id=id, wall=wall, fixed=(t[1] if horiz else t[0]), a=(t[0] if horiz else t[1]), width=3, y0=2, height=2, boards=6, room=room,
                        boardBlock='zombiecraft:barricade_board[axis=%s]' % ('x' if horiz else 'z'), sillBlock='zombiecraft:wood_floor'))
    for c in cells: vox[c] = 'minecraft:air'

# ---- zombie spawn spots outside each window (two each): (window, ex, ez)
SPAWN = [('w_hall_e1', 65, 30), ('w_hall_e1', 65, 29), ('w_hall_e2', 65, 35), ('w_hall_e2', 65, 34), ('w_hall_s', 58, 44), ('w_hall_s', 59, 45),
         ('w_hall_e3', 61, 22), ('w_hall_e3', 62, 23), ('w_north_e', 62, 14), ('w_north_e', 62, 13), ('w_north_w', 44, 15), ('w_north_w', 43, 16),
         ('w_back_n', 52, 4), ('w_back_n', 51, 3)]
spawns = []
for i, (w, x, z) in enumerate(SPAWN):
    if not (air(x, 1, z) and air(x, 2, z)): err(f'spawn {w} ({x},{z}) is blocked')
    t = T(x, z); spawns.append(dict(id=f's{i + 1}', window=w, x=t[0], y=1, z=t[1]))

# ---- player, wall buys, box, machines, Pack-a-Punch, doors
D = {'north': (0, -1), 'south': (0, 1), 'east': (1, 0), 'west': (-1, 0)}
player = [(54, 22, 0.0, 'hall')]
for x, z, yaw, room in player:
    if not (air(x, 1, z) and air(x, 2, z)): err(f'player spawn ({x},{z}) is blocked')
WALL = [('wb1', 'rottweil72', 51, 19, 'south', 'hall'), ('wb2', 'm14', 56, 19, 'south', 'hall'), ('wb3', 'ak74u', 58, 20, 'west', 'hall'), ('wb4', 'mp5k', 57, 12, 'south', 'north')]
for id, w, x, z, f, room in WALL:
    d = D[f]
    if not (air(x, 2, z) and air(x, 1, z) and solid(x - d[0], 2, z - d[1]) and air(x + d[0], 1, z + d[1]) and air(x + d[0], 2, z + d[1])): err(f'wallbuy {id} at ({x},{z}) {f} does not fit')
BOX = [('bx1', 57, 33, 'west', True, 'hall'), ('bx2', 50, 16, 'north', False, 'north')]
for id, x, z, f, init, room in BOX:
    d = D[f]
    ok = air(x, 1, z) and air(x - d[0], 1, z - d[1]) and solid(x - 2 * d[0], 1, z - 2 * d[1]) and air(x + d[0], 1, z + d[1]) and air(x + d[1], 1, z + d[0]) and air(x - d[1], 1, z - d[0])
    if not ok: err(f'box {id} at ({x},{z}) {f} does not fit')
MACH = [('m_power', 'power', '', 48, 16, 'east', 'north'), ('m_jug', 'perk', 'jug', 62, 38, 'west', 'hall'), ('m_revive', 'perk', 'revive', 62, 32, 'west', 'hall'),
        ('m_speed', 'perk', 'speed', 56, 7, 'south', 'back'), ('m_doubletap', 'perk', 'doubletap', 49, 7, 'south', 'back')]
for id, kind, perk, x, z, f, room in MACH:
    d = D[f]
    if not (air(x, 1, z) and air(x, 2, z) and solid(x - d[0], 1, z - d[1]) and air(x + d[0], 1, z + d[1]) and air(x + d[0], 2, z + d[1])): err(f'machine {id} at ({x},{z}) {f} does not fit')
# pap region (inclusive), facing south: the front glass is the last row, the player stands two blocks in front of the centre
PAP = (52, 1, 12, 54, 4, 14, 'south')
stand = ((PAP[0] + PAP[3]) // 2, 1, (PAP[2] + PAP[5]) // 2 + (abs(PAP[5] - PAP[2]) // 2 + 2))
if not air(*stand): err(f'pap: nowhere to stand at {stand}')
# doors: (id, cost, ex1, ez1, ex2, ez2, leaf block, opens, cue, label, room)
DOORS = [('door_north', 750, 53, 18, 54, 18, 'zombiecraft:door_metal', 'north', 'zmb_power_door', 'Offices', 'hall'),
         ('door_back', 1000, 53, 11, 54, 11, 'zombiecraft:door_wood', 'back', 'zmb_small_wood_door', 'Back Room', 'north')]
for id, cost, x1, z1, x2, z2, blk, opens, cue, label, room in DOORS:
    for x in range(x1, x2 + 1):
        for z in range(z1, z2 + 1):
            if not air(x, 1, z) and (x, z) not in [(53, 18), (54, 18)]: err(f'door {id}: cell ({x},{z}) is not open')
    if not any(w[4] == opens.split(',')[0] for w in WIN): err(f'door {id} opens a room with no window')

# reachability from the player spawn with every window open (zombies and players hop the 1-block sill): plane y=1 or y=2/3 for cells in a window
def walk1(x, z): return air(x, 1, z) and air(x, 2, z)
def walk2(x, z): return air(x, 2, z) and air(x, 3, z)
for id, kind, perk, x, z, f, room in MACH: pass
seen = {(54, 22)}; q = [(54, 22)]
while q:
    x, z = q.pop()
    for dx, dz in ((1, 0), (-1, 0), (0, 1), (0, -1)):
        n = (x + dx, z + dz)
        if n in seen or not (0 <= n[0] < SX and 0 <= n[1] < SZ): continue
        if walk1(*n) or (walk2(*n) and (walk1(x, z) or walk2(x, z))) or (not solid(n[0], 2, n[1]) and False): seen.add(n); q.append(n)
# door cells and the rest of the sealed rooms count as reachable (they open when bought)
for dd in DOORS:
    for x in range(dd[2], dd[4] + 1):
        for z in range(dd[3], dd[5] + 1): seen.add((x, z)); q.append((x, z))
while q:
    x, z = q.pop()
    for dx, dz in ((1, 0), (-1, 0), (0, 1), (0, -1)):
        n = (x + dx, z + dz)
        if n not in seen and 0 <= n[0] < SX and 0 <= n[1] < SZ and (walk1(*n) or walk2(*n)): seen.add(n); q.append(n)
for w, x, z in SPAWN:
    if (x, z) not in seen: err(f'spawn {w} ({x},{z}) cannot reach the player spawn')
for id, x, z, f, init, room in BOX:
    if (x, z) not in seen: err(f'box {id} not reachable')
for id, kind, perk, x, z, f, room in MACH:
    if (x, z) not in seen: err(f'machine {id} not reachable')
for id, w, x, z, f, room in WALL:
    if (x, z) not in seen: err(f'wallbuy {id} not reachable')

if errors:
    print('\n'.join(errors)); sys.exit(1)


def sheet(name, rows):
    os.makedirs(OUT, exist_ok=True)
    with open(os.path.join(OUT, name), 'w', encoding='utf-8') as f: json.dump(rows, f, indent=1, ensure_ascii=False)


sheet('map_ops.json', ops)
sheet('map_windows.json', windows)
sheet('map_spawns.json', spawns)
sheet('map_player.json', [dict(id='spawn', x=T(x, z)[0], y=1, z=T(x, z)[1], yaw=yaw, room=room) for x, z, yaw, room in player])
sheet('map_wallbuys.json', [dict(id=i, weaponId=w, x=T(x, z)[0], y=2, z=T(x, z)[1], facing=f, room=r) for i, w, x, z, f, r in WALL])
sheet('map_boxes.json', [dict(id=i, x=T(x, z)[0], y=1, z=T(x, z)[1], facing=f, initial=n, room=r) for i, x, z, f, n, r in BOX])
sheet('map_machines.json', [dict(id=i, kind=k, perk=p, x=T(x, z)[0], y=1, z=T(x, z)[1], facing=f, room=r) for i, k, p, x, z, f, r in MACH])
a, b = T(PAP[0], PAP[2]), T(PAP[3], PAP[5])
sheet('map_pap.json', [dict(id='pap1', x1=a[0], y1=PAP[1], z1=a[1], x2=b[0], y2=PAP[4], z2=b[1], facing=PAP[6], room='north')])
sheet('map_doors.json', [dict(id=i, cost=c, x1=T(x1, z1)[0], y1=1, z1=T(x1, z1)[1], x2=T(x2, z2)[0], y2=2, z2=T(x2, z2)[1], block=blk, opens=o, cue=cue, label=l, room=r)
                         for i, c, x1, z1, x2, z2, blk, o, cue, l, r in DOORS])
sheet('map_props.json', [])
print('ok:', len(windows), 'windows,', len(spawns), 'spawns,', len(WALL), 'wall buys,', len(BOX), 'boxes,', len(MACH), 'machines, pap, doors', len(DOORS))

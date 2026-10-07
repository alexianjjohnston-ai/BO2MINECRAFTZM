#!/usr/bin/env python3
"""Gameplay sheets for the Bus Depot exactly as Black Ops II places things (Survival: zstandard_transit), written to sheets/maps/bo2_depot/map_*.json.
Everything comes from the map entities (assets_src/depot/ents.json, dumped from zm_transit.d3dbsp.ents): barricades with their facing, the Mystery Box,
the four wall guns, the doors, the player start and which zone each window belongs to. 1 block = 40 BO2 units; sheet frame x = bx - 40, z = bz - 47 (as gen_depot.py).
Survival has NO perks and NO Pack-a-Punch in the depot (the in-game text says so, and the entities hold only a classic-mode Quick Revive), so none are written.
The walls and floor are not in the entity file: they are pasted from maps_local/bo2_depot.json.gz (tools/obj_to_blocks.py, from a map export).
Read from the scripts (Tranzit/zm_transit_standard_station.gsc = the Survival depot, zm_transit.gsc zones): zone_pri = the main hall (start), zone_station_ext = behind the
750 door (OnPriDoorYar2), zone_pri2 = the side rooms behind the two electric doors. The Survival script turns power on at the start and trigger_off()s every
local_electric_door, so those two doors can never be used (cost -1 here); it removes the Quick Revive machine and puts a p_glo_tools_chest_tall in its place; it spawns
the "game_mode_object" wrecks (cars, overturned truck cabs, rocks) that wall in the playable area, and a collision model that only exists in Survival.
Door sizes come from the door leaf models the entity file places (60 units wide each, two leaves = 3 blocks, 100 units = 2.5 blocks tall)."""
import glob, json, math, os, re
import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
ENTS = os.path.join(HERE, '..', 'assets_src', 'depot', 'ents.json')
OUT = os.path.join(HERE, '..', 'sheets', 'maps', 'bo2_depot')
OX, OZ = 40, 47
REGION = (0, 15, 72, 80)  # bx0, bz0, bx1, bz1 of the pasted block file: covers every pathnode of the depot with a margin


def B(origin):
    x, y, z = map(float, origin.split())
    return (x + 8400) / 40, (6900 - y) / 40, z / 40


def S(bx, bz): return math.floor(bx) - OX, math.floor(bz) - OZ


FACE = {0: 'east', 90: 'north', 180: 'west', 270: 'south'}  # BO2 yaw (0 = +x, 90 = +y = north = -z) -> the way a block-frame thing looks


def facing(yaw):
    return FACE[int(round(yaw / 90.0)) % 4 * 90]


ents = json.load(open(ENTS))
by = lambda **kw: [e for e in ents if all(e.get(k) == v for k, v in kw.items())]

# ---- windows: one per depot barricade. The zbarrier sits on the wall; the node_negotiation pair crosses it from outside to inside.
ZONE = {'depot_baricade1': 'hall', 'depot_baricade2': 'hall', 'depot_baricade4': 'hall', 'depot_baricade5': 'side',
        'depot_baricade3': 'ext', 'depot_baricade6': 'ext', 'depot_baricade7': 'ext'}
windows, spawns = [], []
for n in (1, 2, 3, 4, 5, 6, 7):
    tag = f'depot_baricade{n}'
    zb = [e for e in ents if e.get('script_string') == tag and e['classname'].startswith('zbarrier')][0]
    nb = [e for e in ents if e.get('script_string') == tag and e['classname'] == 'node_negotiation_begin'][0]
    ne = [e for e in ents if e.get('script_string') == tag and e['classname'] == 'node_negotiation_end'][0]
    c = B(zb['origin']); b = B(nb['origin']); e_ = B(ne['origin'])
    dx, dz = e_[0] - b[0], e_[1] - b[1]           # outside -> inside
    if abs(dz) >= abs(dx): wall, along = ('N' if dz > 0 else 'S'), 'x'    # a wall along x; zombies come from -z (north) when dz > 0
    else: wall, along = ('W' if dx > 0 else 'E'), 'z'
    horiz = along == 'x'
    fixed = math.floor(c[1] if horiz else c[0]) - (OZ if horiz else OX)
    a = math.floor((c[0] if horiz else c[1]) - 1.5 + 0.5) - (OX if horiz else OZ)  # three cells centred on the barricade
    diag = abs(abs(dz) - abs(dx)) < 0.5
    windows.append(dict(id=f'w{n}', wall=wall, fixed=fixed, a=a, width=3, y0=2, height=2, boards=6, room=ZONE[tag],
                        boardBlock='zombiecraft:barricade_board[axis=%s]' % ('x' if horiz else 'z'), sillBlock='zombiecraft:wood_floor'))
    ow = {'N': (0, -1), 'S': (0, 1), 'E': (1, 0), 'W': (-1, 0)}[wall]
    mid = (math.floor(c[0]) - OX, math.floor(c[1]) - OZ)
    for i, k in enumerate((3, 4)):
        spawns.append(dict(id=f's{n}{"ab"[i]}', window=f'w{n}', x=mid[0] + ow[0] * k, y=1, z=mid[1] + ow[1] * k))
    if diag: print(f'NOTE w{n}: the BO2 barricade is diagonal (yaw {zb["angles"]}); snapped to a {wall} wall, check it against the real geometry')

# ---- wall guns: the struct faces away from its wall; frame is one block up (y 2)
GUN = {'rottweil72_zm': 'rottweil72', 'm14_zm': 'm14', '870mcs_zm': '870mcs', 'mp5k_zm': 'mp5k'}
GUN_AT = [('rottweil72_zm', (36.88, 33.65)), ('m14_zm', (51.77, 38.75)), ('870mcs_zm', (21.82, 48.35)), ('mp5k_zm', (33.85, 69.2))]
wall = []
for i, (g, (bx, bz)) in enumerate(GUN_AT):
    st = [e for e in ents if e.get('zombie_weapon_upgrade') == g and abs(B(e['origin'])[0] - bx) < .05 and abs(B(e['origin'])[1] - bz) < .05][0]
    x, z = S(*B(st['origin'])[:2])
    wall.append(dict(id=f'wb{i + 1}', weaponId=GUN[g], x=x, y=2, z=z, facing=facing(float(st['angles'].split()[1])), room='hall' if i < 2 else ('side' if i == 2 else 'ext')))

# ---- the box, the player, the doors
mb = by(classname='zbarrier_zmcore_MagicBox', script_noteworthy='depot_chest_zbarrier')[0]
bx, bz, _ = B(mb['origin'])
boxes = [dict(id='bx1', x=S(bx, bz)[0], y=1, z=S(bx, bz)[1], facing=facing(float(mb['angles'].split()[1])), initial=True, room='ext')]
ps = by(classname='info_player_start')[0]
px, pz, _ = B(ps['origin'])
yaw = float(ps['angles'].split()[1])
player = [dict(id='spawn', x=S(px, pz)[0], y=1, z=S(px, pz)[1], yaw=round(math.degrees(math.atan2(-math.cos(math.radians(yaw)), -math.sin(math.radians(yaw)))), 1), room='hall')]
# doors: two leaf models each, 1.5 blocks wide, 2.5 tall (3 cells): 750 door leaves at x 37.2 / 40.2 (z 46.1), electric doors at z 39.8 / 42.8 (x 30.4) and x 25.6 / 28.5 (z 48.2)
def cells(lo, hi): return math.floor(lo), math.floor(hi)


dx1, dx2 = cells(37.2, 40.2); ez1, ez2 = cells(39.8, 42.8); fx1, fx2 = cells(25.6, 28.5)
doors = [
    dict(id='door_750', cost=750, x1=dx1 - OX, y1=1, z1=46 - OZ, x2=dx2 - OX, y2=3, z2=46 - OZ, block='zombiecraft:door_metal', opens='ext', cue='zmb_power_door', label='Bus Station', room='hall'),
    dict(id='door_west_a', cost=-1, x1=30 - OX, y1=1, z1=ez1 - OZ, x2=30 - OX, y2=3, z2=ez2 - OZ, block='zombiecraft:door_metal', opens='side', cue='zmb_power_door', label='Switched off in Survival', room='hall'),
    dict(id='door_west_b', cost=-1, x1=fx1 - OX, y1=1, z1=48 - OZ, x2=fx2 - OX, y2=3, z2=48 - OZ, block='zombiecraft:door_metal', opens='side', cue='zmb_power_door', label='Switched off in Survival', room='ext'),
]

# ---- the exact Survival props: wrecks that wall the area in, the rocks, and the tool chest where Quick Revive would stand (all BO2 models, placed by their own origin)
DUMP = os.environ.get('BO2_DUMP', r'C:\Users\alexi\bo2-dump')
SCALE = 0.025 / 0.0225        # the prop renderer draws 0.0225 blocks per unit; the map is 40 units per block
FLOOR_Z = -1.4                # BO2 z (blocks) of the floor and street: the perk struct, the box room and every wreck sit at -1.4, and the barricade origins (the window sill, ~0.9 up) at -0.6. Same as obj_to_blocks.py's --floor-z default (-56 units)


def model_points(name):
    for f in glob.glob(os.path.join(DUMP, 'out*', '*', 'model_export', name + '_lod0.xmodel_export')):
        t = open(f, errors='ignore').read()
        return np.array([[float(v) for v in m.groups()] for m in re.finditer(r'^OFFSET (-?[\d.e+-]+), (-?[\d.e+-]+), (-?[\d.e+-]+)\s*$', t, re.M)])
    return None


def rot(pitch, yaw, roll):
    """BO2 angles: yaw about z, then pitch about y (positive = nose down), then roll about x, applied to model points as Rz Ry Rx."""
    c = lambda d: math.cos(math.radians(d)); sn = lambda d: math.sin(math.radians(d))
    Rz = np.array([[c(yaw), -sn(yaw), 0], [sn(yaw), c(yaw), 0], [0, 0, 1]])
    Ry = np.array([[c(pitch), 0, sn(pitch)], [0, 1, 0], [-sn(pitch), 0, c(pitch)]])
    Rx = np.array([[1, 0, 0], [0, c(roll), -sn(roll)], [0, sn(roll), c(roll)]])
    return Rz @ Ry @ Rx


FALLBACK = {'p6_zm_rocks_small_cluster_01': 'minecraft:cobblestone', 'p_glo_tools_chest_tall': 'zombiecraft:crate'}
prop_list = []   # (id, model, struct origin, angles)
for i, st in enumerate(e for e in ents if e.get('targetname') == 'game_mode_object' and e.get('script_noteworthy') == 'station'):
    prop_list.append((f'wreck{i}', st['model'], st['origin'], st['angles']))
rv = by(classname='script_struct', targetname='zm_perk_machine', script_noteworthy='specialty_quickrevive')[0]
prop_list.append(('tool_chest', 'p_glo_tools_chest_tall', rv['origin'], rv['angles']))
props, ops_cells = [], []
for pid, model, origin, angles in prop_list:
    pitch, yaw, roll = (float(v) for v in angles.split())
    pitch, roll = ((pitch + 180) % 360) - 180, ((roll + 180) % 360) - 180
    bx, bz, bz_up = B(origin)
    h0 = bz_up - FLOOR_Z
    pts = model_points(model)
    hide, cellset = 'none', {}
    if pts is not None:
        w = (rot(pitch, yaw, roll) @ pts.T).T / 40.0           # blocks: x east, y north, z up
        X, Zb, Y = bx + w[:, 0], bz - w[:, 1], 1 + h0 + w[:, 2]  # block x, block z (south), standing-level height
        for xi, zi, yi in zip(np.floor(X).astype(int), np.floor(Zb).astype(int), np.floor(Y).astype(int)):
            lo, hi = cellset.get((xi, zi), (yi, yi)); cellset[(xi, zi)] = (min(lo, yi), max(hi, yi))
        cellset = {k: (max(1, lo), hi) for k, (lo, hi) in cellset.items() if hi >= 1}
    fb = FALLBACK.get(model, 'minecraft:gray_concrete')
    for (xi, zi), (lo, hi) in sorted(cellset.items()):
        ops_cells.append((pid, int(xi - OX), int(lo), int(zi - OZ), int(hi), fb))
    if cellset:
        xs = [int(k[0] - OX) for k in cellset]; zs = [int(k[1] - OZ) for k in cellset]; ys = [int(v) for lh in cellset.values() for v in lh]
        hide = f'{min(xs)},{min(ys)},{min(zs)},{max(xs)},{max(ys)},{max(zs)}'
    props.append(dict(id=pid, model=model, x=round(bx - OX, 3), y=round(1 + h0, 3), z=round(bz - OZ, 3), yaw=round(-yaw, 2), scale=round(SCALE, 4), hide=hide, room='ext',
                      pitch=round(pitch, 2), roll=round(roll, 2), exact=True, fallback=(fb if cellset else None)))


# ---- build: paste the exported geometry, then an invisible wall round the playable area (BO2 pathnodes span bx 1..70, bz 17..77)
x0, z0, x1, z1 = REGION
SCEN = (-120, -112)  # sheet frame: where depot_scenery's corner lands so that the original compound's lot (crop offset 80, 80) sits under REGION
ops = [dict(id='scenery', order=-2, op='scenery', block='depot_scenery', block2=None, x1=SCEN[0], y1=0, z1=SCEN[1], x2=SCEN[0], y2=0, z2=SCEN[1], stepX=1, stepZ=1, group='ground',
            note='roads, forest and hills round the depot from Tranzit Reimagined (tools/make_depot_scenery.py), so the map does not end in a cut; air is skipped'),
       dict(id='play_area_clear', order=-1, op='fill', block='minecraft:air', block2=None, x1=x0 - OX, y1=1, z1=z0 - OZ, x2=x1 - OX, y2=30, z2=z1 - OZ, stepX=1, stepZ=1, group='ground',
            note='the lot of the original depot is emptied (its building and bus); the BO2 geometry is pasted here'),
       dict(id='bo2_depot', order=0, op='import', block='bo2_depot', block2=None, x1=x0 - OX, y1=0, z1=z0 - OZ, x2=x0 - OX, y2=0, z2=z0 - OZ, stepX=1, stepZ=1, group='ground',
            note='Bus Depot geometry from the player\'s own BO2 map export (tools/obj_to_blocks.py); x/z = where its corner lands in the sheet frame'),
       dict(id='barrier_ring', order=90, op='walls', block='minecraft:barrier', block2=None, x1=x0 - OX + 1, y1=1, z1=z0 - OZ + 1, x2=x1 - OX - 1, y2=14, z2=z1 - OZ - 1, stepX=1, stepZ=1,
            group='ground', note='invisible wall round the playable area so nobody walks off the cut')]


for k, (pid, x, y1, z, y2, fb) in enumerate(ops_cells):
    ops.append(dict(id=f'{pid}_c{k}', order=50, op='fill', block=fb, block2=None, x1=x, y1=y1, z1=z, x2=x, y2=y2, z2=z, stepX=1, stepZ=1, group='props',
                    note='stand-in blocks for ' + pid + ' (they become invisible barriers when the BO2 model is drawn)'))


def dump(name, rows):
    os.makedirs(OUT, exist_ok=True)
    with open(os.path.join(OUT, name), 'w', encoding='utf-8') as f:
        json.dump(rows, f, indent=1, ensure_ascii=False)
        f.write('\n')


for n, r in (('map_ops', ops), ('map_windows', windows), ('map_spawns', spawns), ('map_wallbuys', wall), ('map_boxes', boxes), ('map_doors', doors), ('map_player', player),
             ('map_machines', []), ('map_pap', []), ('map_props', props)):
    dump(n + '.json', r)
print(len(props), 'exact props,', len(ops_cells), 'stand-in cells;', len(windows), 'windows', len(spawns), 'spawns', len(wall), 'wall guns, 1 box,', len(doors), 'doors; no perks/PaP (Survival)')
for w in windows: print(' ', w['id'], w['wall'], 'fixed', w['fixed'], 'a', w['a'], w['room'])
for g in wall: print(' ', g['id'], g['weaponId'], g['x'], g['z'], g['facing'])
print('  box', boxes[0]['x'], boxes[0]['z'], boxes[0]['facing'], '| player', player[0]['x'], player[0]['z'], player[0]['yaw'])

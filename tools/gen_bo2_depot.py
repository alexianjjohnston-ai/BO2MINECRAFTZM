#!/usr/bin/env python3
"""Gameplay sheets for the Bus Depot exactly as Black Ops II places things (Survival: zstandard_transit), written to sheets/maps/bo2_depot/map_*.json.
Everything comes from the map entities (assets_src/depot/ents.json, dumped from zm_transit.d3dbsp.ents): barricades with their facing, the Mystery Box,
the four wall guns, the doors, the player start and which zone each window belongs to. 1 block = 40 BO2 units; sheet frame x = bx - 40, z = bz - 47 (as gen_depot.py).
Survival has NO perks and NO Pack-a-Punch in the depot (the in-game text says so, and the entities hold only a classic-mode Quick Revive), so none are written.
The walls and floor are not in the entity file: they are pasted from maps_local/bo2_depot.json.gz (tools/obj_to_blocks.py, from a map export).
Read from the scripts (Tranzit/zm_transit.gsc): zone_pri = the main hall (start), zone_station_ext = behind the 750 door (OnPriDoorYar2), zone_pri2 = the side rooms
behind two electric doors that need the turbine's local power, which Survival has no way to switch on: they are written as doors nobody can pay for."""
import json, math, os

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
GUN = {'rottweil72_zm': 'rottweil72', 'm14_zm': 'm14', '870mcs_zm': 'ak74u', 'mp5k_zm': 'mp5k'}  # TODO the Remington 870 MCS is not in weapons.json yet: ak74u stands in (the old depot's choice)
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
# door brushes have no extent in the entity file: widths follow the leaves ("rotate" brushes) around the trigger
doors = [
    dict(id='door_750', cost=750, x1=S(36.7, 46)[0], y1=1, z1=S(36.7, 46)[1], x2=S(40.7, 46)[0], y2=2, z2=S(36.7, 46)[1], block='zombiecraft:door_metal', opens='ext',
         cue='zmb_bus_depot_dbl', label='Bus Station', room='hall'),
    dict(id='door_west_a', cost=99999, x1=S(30.4, 39.8)[0], y1=1, z1=S(30.4, 39.8)[1], x2=S(30.4, 39.8)[0], y2=2, z2=S(30.4, 42.8)[1], block='zombiecraft:door_metal', opens='side',
         cue='zmb_power_door', label='Needs power', room='hall'),
    dict(id='door_west_b', cost=99999, x1=S(25.6, 48.1)[0], y1=1, z1=S(25.6, 48.1)[1], x2=S(28.5, 48.1)[0], y2=2, z2=S(25.6, 48.1)[1], block='zombiecraft:door_metal', opens='side',
         cue='zmb_power_door', label='Needs power', room='ext'),
]

# ---- build: paste the exported geometry, then an invisible wall round the playable area (BO2 pathnodes span bx 1..70, bz 17..77)
x0, z0, x1, z1 = REGION
ops = [dict(id='bo2_depot', order=0, op='import', block='bo2_depot', block2=None, x1=x0 - OX, y1=0, z1=z0 - OZ, x2=x0 - OX, y2=0, z2=z0 - OZ, stepX=1, stepZ=1, group='ground',
            note='Bus Depot geometry from the player\'s own BO2 map export (tools/obj_to_blocks.py); x/z = where its corner lands in the sheet frame'),
       dict(id='barrier_ring', order=90, op='walls', block='minecraft:barrier', block2=None, x1=x0 - OX + 1, y1=1, z1=z0 - OZ + 1, x2=x1 - OX - 1, y2=14, z2=z1 - OZ - 1, stepX=1, stepZ=1,
            group='ground', note='invisible wall round the playable area so nobody walks off the cut')]


def dump(name, rows):
    os.makedirs(OUT, exist_ok=True)
    with open(os.path.join(OUT, name), 'w', encoding='utf-8') as f:
        json.dump(rows, f, indent=1, ensure_ascii=False)
        f.write('\n')


for n, r in (('map_ops', ops), ('map_windows', windows), ('map_spawns', spawns), ('map_wallbuys', wall), ('map_boxes', boxes), ('map_doors', doors), ('map_player', player),
             ('map_machines', []), ('map_pap', []), ('map_props', [])):
    dump(n + '.json', r)
print(len(windows), 'windows', len(spawns), 'spawns', len(wall), 'wall guns, 1 box,', len(doors), 'doors; no perks/PaP (Survival)')
for w in windows: print(' ', w['id'], w['wall'], 'fixed', w['fixed'], 'a', w['a'], w['room'])
for g in wall: print(' ', g['id'], g['weaponId'], g['x'], g['z'], g['facing'])
print('  box', boxes[0]['x'], boxes[0]['z'], boxes[0]['facing'], '| player', player[0]['x'], player[0]['z'], player[0]['yaw'])

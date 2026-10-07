#!/usr/bin/env python3
"""Generates the Green Run Bus Depot map sheets (map_*.json) from the real BO2 entity positions.
Coordinates below are "depot blocks" (bx, bz) = ((x + 8400) / 40, (6900 - y) / 40) of zm_transit.d3dbsp.ents (1 block ~ 40 units);
L() shifts them into the sheet frame (the map fits x -40..40, z -30..30). The old diner sheets live in sheets/_diner/."""
import json, os

SH = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', 'sheets')


def L(bx, bz):
    return bx - 40, bz - 47


ops = []


def mc(b):
    return b if ':' in b.split('[')[0] else 'minecraft:' + b


def op(id, kind, block, x1, y1, z1, x2, y2, z2, group, note, order=None, block2=None, step=1):
    a = L(x1, z1)
    b = L(x2, z2)
    ops.append(dict(id=id, order=(order if order is not None else len(ops)), op=kind, block=mc(block), block2=None if block2 is None else mc(block2),
                    x1=a[0], y1=y1, z1=a[1], x2=b[0], y2=y2, z2=b[1], stepX=step, stepZ=step, group=group, note=note))


def fill(id, block, x1, y1, z1, x2, y2, z2, group, note, **k):
    op(id, 'fill', block, x1, y1, z1, x2, y2, z2, group, note, **k)


# ---- ground, road, lot (the sheet frame is x -40..40, z -30..30 -> bx 0..80, bz 17..77)
ops.append(dict(id='base', order=-1, op='fill', block='minecraft:dirt', block2=None, x1=-40, y1=-4, z1=-30, x2=40, y2=-1, z2=30, stepX=1, stepZ=1, group='ground', note='solid ground under the whole map'))
ops.append(dict(id='clear', order=0, op='fill', block='minecraft:air', block2=None, x1=-40, y1=0, z1=-30, x2=40, y2=14, z2=30, stepX=1, stepZ=1, group='ground', note='wipe everything so the map can be rebuilt'))
fill('grass', 'grass_block', 0, 0, 17, 80, 0, 77, 'ground', 'ground layer', order=1)
fill('road', 'black_concrete', 0, 0, 20, 80, 0, 26, 'road', 'the bus road', order=2)
op('road_line', 'checker', 'yellow_concrete', 0, 0, 23, 80, 0, 23, 'road', 'centre line', order=3, block2='black_concrete')
fill('sidewalk', 'light_gray_concrete', 0, 0, 27, 80, 0, 28, 'road', 'kerb', order=4)
fill('plaza', 'light_gray_concrete', 14, 0, 29, 62, 0, 32, 'depot', 'forecourt in front of the depot', order=5)
fill('apron_w', 'light_gray_concrete', 12, 0, 33, 19, 0, 55, 'depot', 'west side lot', order=6)
fill('apron_e', 'light_gray_concrete', 57, 0, 33, 63, 0, 55, 'depot', 'east side lot', order=7)
fill('apron_s', 'light_gray_concrete', 12, 0, 54, 63, 0, 57, 'depot', 'south walkway behind the depot', order=8)

# ---- floors
op('floor_lobby', 'checker', 'smooth_stone', 29, 0, 34, 55, 0, 45, 'lobby', 'tiled waiting hall', order=10, block2='light_gray_concrete')
fill('floor_back', 'gray_concrete', 29, 0, 47, 55, 0, 52, 'back', 'back room', order=11)
fill('floor_wing', 'gray_concrete', 21, 0, 34, 27, 0, 52, 'wing', 'west wing', order=12)
fill('floor_mid', 'gray_concrete', 28, 0, 34, 28, 0, 52, 'wing', 'wall footing', order=13)
fill('floor_part', 'gray_concrete', 29, 0, 46, 55, 0, 46, 'back', 'partition footing', order=14)

# ---- the building shell: outer walls, west-wing wall (x=28), partition (z=46)
op('shell', 'walls', 'light_gray_concrete', 20, 1, 33, 56, 6, 53, 'building', 'outer walls', order=20)
op('shell_trim', 'walls', 'red_concrete', 20, 6, 33, 56, 6, 53, 'building', 'red roof trim', order=21)
fill('wing_wall', 'light_gray_concrete', 28, 1, 34, 28, 6, 52, 'building', 'wall between the west wing and the hall', order=22)
fill('partition', 'light_gray_concrete', 29, 1, 46, 55, 6, 46, 'building', 'wall between the waiting hall and the back room', order=23)
fill('door_b', 'air', 28, 1, 40, 28, 4, 42, 'building', 'opening to the west wing (BO2 electric door B)', order=24)
fill('door_a', 'air', 28, 1, 47, 28, 4, 49, 'building', 'opening to the west wing from the back room (door A)', order=25)
fill('door_750', 'air', 37, 1, 46, 40, 4, 46, 'building', 'the 750 door between the hall and the back room', order=26)
fill('roof', 'smooth_stone', 20, 7, 33, 56, 7, 53, 'building', 'flat roof', order=27)
op('lights', 'grid', 'sea_lantern', 22, 7, 35, 54, 7, 51, 'building', 'ceiling lights', order=28, step=4)
op('lamps', 'grid', 'sea_lantern', 22, 6, 35, 54, 6, 51, 'building', 'light panels under the roof', order=29, step=4)

# ---- props
fill('ticket_counter', 'quartz_block', 40, 1, 36, 48, 1, 36, 'lobby', 'ticket counter', order=40)
fill('ticket_top', 'quartz_slab[type=bottom]', 40, 2, 36, 48, 2, 36, 'lobby', 'ticket counter top', order=41)
fill('bench_w', 'dark_oak_slab[type=bottom]', 31, 1, 41, 35, 1, 41, 'lobby', 'waiting bench', order=42)
fill('bench_e', 'dark_oak_slab[type=bottom]', 50, 1, 41, 54, 1, 41, 'lobby', 'waiting bench', order=43)
fill('lockers', 'blue_terracotta', 21, 1, 43, 21, 2, 45, 'wing', 'lockers', order=44)
fill('revive_body', 'light_blue_concrete', 41, 1, 47, 42, 3, 47, 'back', 'Quick Revive machine (zm_perk_machine at bx 42, bz 48)', order=45)
fill('revive_top', 'sea_lantern', 41, 4, 47, 42, 4, 47, 'back', 'Quick Revive light', order=46)
fill('pap_frame', 'light_blue_concrete', 22, 1, 34, 24, 3, 35, 'pap', 'Pack-a-Punch (placed in the depot so the game loop works)', order=47)
fill('pap_glass', 'light_blue_stained_glass', 22, 2, 36, 24, 3, 36, 'pap', 'Pack-a-Punch front glass', order=48)
fill('pap_top', 'sea_lantern', 22, 4, 34, 24, 4, 35, 'pap', 'Pack-a-Punch light', order=49)
fill('turbine_table', 'smooth_stone', 25, 1, 50, 27, 1, 51, 'wing', 'turbine buildable table (bx 25, bz 48)', order=50)

# ---- the bus (parked on the road at the west end, where the bus entry points are)
fill('bus_body', 'light_gray_concrete', 14, 1, 21, 24, 3, 25, 'bus', 'bus shell', order=60)
fill('bus_hollow', 'air', 15, 1, 22, 23, 2, 24, 'bus', 'bus interior', order=61)
op('bus_windows', 'grid', 'black_stained_glass', 15, 2, 21, 23, 2, 25, 'bus', 'bus windows', order=62, step=2)
fill('bus_stripe', 'green_concrete', 14, 1, 21, 24, 1, 25, 'bus', 'lower stripe', order=63)
fill('bus_roof', 'light_gray_concrete', 14, 4, 21, 24, 4, 25, 'bus', 'bus roof', order=64)

# ---- lava pit behind the depot (BO2 depot_lava_pit), fenced
fill('pit', 'lava', 36, -2, 60, 50, -1, 68, 'pit', 'lava pit', order=70)
fill('pit_air', 'air', 36, 0, 60, 50, 0, 68, 'pit', 'open pit above the lava', order=71)
fill('pit_fence', 'iron_bars', 35, 1, 59, 51, 2, 59, 'pit', 'fence in front of the pit', order=72)
fill('pit_fence_w', 'iron_bars', 35, 1, 60, 35, 2, 68, 'pit', 'fence', order=73)
fill('pit_fence_e', 'iron_bars', 51, 1, 60, 51, 2, 68, 'pit', 'fence', order=74)
fill('pit_fence_s', 'iron_bars', 35, 1, 69, 51, 2, 69, 'pit', 'fence', order=75)
fill('pit_path', 'light_gray_concrete', 35, 0, 58, 51, 0, 58, 'pit', 'path in front of the fence', order=76)


# ---- windows (the seven BO2 depot barricades) -> map_windows / map_spawns
def win(id, wall, fixed_b, a_b, room):
    # wall N/S: fixed is bz, a is bx; wall E/W: fixed is bx, a is bz
    if wall in ('N', 'S'):
        fixed, a = fixed_b - 47, a_b - 40
    else:
        fixed, a = fixed_b - 40, a_b - 47
    return dict(id=id, wall=wall, fixed=fixed, a=a, width=3, y0=2, height=2, boards=6, room=room, boardBlock='minecraft:oak_planks', sillBlock='minecraft:dark_oak_planks')


windows = [win('w1', 'N', 33, 32, 'lobby'), win('w2', 'N', 33, 47, 'lobby'), win('w3', 'E', 56, 38, 'lobby'), win('w4', 'W', 20, 39, 'wing'),
           win('w5', 'S', 53, 33, 'back'), win('w6', 'S', 53, 44, 'back'), win('w7', 'E', 56, 48, 'back')]
# the game carves each opening and fits the boards itself (Barrier.build), so the wall stays solid here
spawn_pts = {  # (bx, bz) just outside each window, two per window
    'w1': [(33, 29), (33, 30)], 'w2': [(48, 29), (47, 30)], 'w3': [(60, 38), (60, 41)], 'w4': [(14, 39), (16, 41)],
    'w5': [(34, 57), (34, 56)], 'w6': [(45, 57), (46, 56)], 'w7': [(60, 48), (60, 51)]}
spawns = []
for w, pts in spawn_pts.items():
    for i, (bx, bz) in enumerate(pts):
        x, z = L(bx, bz)
        spawns.append(dict(id=f's{w[1]}{"ab"[i]}', window=w, x=x, y=1, z=z))

wall = []
for id, weapon, bx, bz, facing, room in [('wb1', 'rottweil72', 37, 34, 'south', 'lobby'), ('wb2', 'm14', 55, 35, 'west', 'lobby'),
                                          ('wb3', 'ak74u', 21, 48, 'east', 'wing'), ('wb4', 'mp5k', 38, 52, 'north', 'back')]:
    x, z = L(bx, bz)
    wall.append(dict(id=id, weaponId=weapon, x=x, y=2, z=z, facing=facing, room=room))
b1 = L(48, 47)
b2 = L(55, 43)
boxes = [dict(id='bx1', x=b1[0], y=1, z=b1[1], facing='south', initial=True, room='back'),
         dict(id='bx2', x=b2[0], y=1, z=b2[1], facing='west', initial=False, room='lobby')]
p1 = L(22, 34)
p2 = L(24, 35)
pap = [dict(id='pap1', x1=p1[0], y1=1, z1=p1[1], x2=p2[0], y2=4, z2=p2[1], facing='south', room='wing')]
ps = L(42, 40)
player = [dict(id='spawn', x=ps[0], y=1, z=ps[1], yaw=0.0, room='lobby')]


def dump(name, rows):
    with open(os.path.join(SH, name), 'w', encoding='utf-8') as f:
        json.dump(rows, f, indent=1, ensure_ascii=False)
        f.write('\n')


dump('map_ops.json', sorted(ops, key=lambda o: o['order']))
dump('map_windows.json', windows)
dump('map_spawns.json', spawns)
dump('map_wallbuys.json', wall)
dump('map_boxes.json', boxes)
dump('map_pap.json', pap)
dump('map_player.json', player)
print(len(ops), 'ops,', len(windows), 'windows,', len(spawns), 'spawns')

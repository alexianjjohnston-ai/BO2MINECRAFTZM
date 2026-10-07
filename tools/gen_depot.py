#!/usr/bin/env python3
"""Generates the Green Run Bus Depot map sheets (map_*.json) from the real BO2 entity positions.
Coordinates below are "depot blocks" (bx, bz) = ((x + 8400) / 40, (6900 - y) / 40) of zm_transit.d3dbsp.ents (1 block ~ 40 units);
L() shifts them into the sheet frame (the map fits x -40..40, z -30..30). The old diner sheets live in sheets/_diner/."""
import json, os

SH = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', 'sheets')


def L(bx, bz):
    return bx - 40, bz - 47


ops = []

# ---- one palette so nothing clashes: weathered stone and dark wood
PLASTER = 'zombiecraft:plaster_wall'
WALL, WAINSCOT, TRIM, ROOF = 'zombiecraft:cinder_block', 'zombiecraft:concrete_wall', 'zombiecraft:wood_floor', 'zombiecraft:metal_panel'
FLOOR_A, FLOOR_B, FLOOR_C, GROUND = 'zombiecraft:depot_tile', 'zombiecraft:concrete_floor', 'zombiecraft:concrete_floor', 'zombiecraft:ground'
LIGHT, METAL, GLASS = 'shroomlight', 'iron_block', 'zombiecraft:glass_brick'


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
fill('grass', 'zombiecraft:grass', 0, 0, 17, 80, 0, 77, 'ground', 'ground layer', order=1)
fill('road', 'zombiecraft:asphalt', 0, 0, 20, 80, 0, 26, 'road', 'the bus road', order=2)
op('road_line', 'checker', 'yellow_concrete', 0, 0, 23, 80, 0, 23, 'road', 'centre line', order=3, block2='zombiecraft:asphalt')
fill('sidewalk', FLOOR_B, 0, 0, 27, 80, 0, 28, 'road', 'kerb', order=4)
fill('plaza', GROUND, 14, 0, 29, 62, 0, 32, 'depot', 'forecourt in front of the depot', order=5)
fill('apron_w', GROUND, 12, 0, 33, 19, 0, 55, 'depot', 'west side lot', order=6)
fill('apron_e', GROUND, 57, 0, 33, 63, 0, 55, 'depot', 'east side lot', order=7)
fill('apron_s', GROUND, 12, 0, 54, 63, 0, 57, 'depot', 'south walkway behind the depot', order=8)

# ---- floors
op('floor_lobby', 'checker', FLOOR_A, 29, 0, 34, 55, 0, 45, 'lobby', 'tiled waiting hall', order=10, block2=FLOOR_B)
fill('floor_back', FLOOR_C, 29, 0, 47, 55, 0, 52, 'back', 'back room', order=11)
fill('floor_wing', FLOOR_C, 21, 0, 34, 27, 0, 52, 'wing', 'west wing', order=12)
fill('floor_mid', FLOOR_C, 28, 0, 34, 28, 0, 52, 'wing', 'wall footing', order=13)
fill('floor_part', FLOOR_C, 29, 0, 46, 55, 0, 46, 'back', 'partition footing', order=14)

# ---- the building shell: outer walls, west-wing wall (x=28), partition (z=46)
op('shell', 'walls', WALL, 20, 1, 33, 56, 6, 53, 'building', 'outer walls', order=20)
op('shell_wainscot', 'walls', WAINSCOT, 20, 1, 33, 56, 1, 53, 'building', 'stone base course', order=21)
op('shell_trim', 'walls', TRIM, 20, 6, 33, 56, 6, 53, 'building', 'wooden eaves', order=21)
op('shell_neon', 'walls', 'zombiecraft:neon', 19, 5, 32, 57, 5, 54, 'building', 'cyan neon line around the outside of the building (reference)', order=21)
fill('wing_wall', PLASTER, 28, 1, 34, 28, 6, 52, 'building', 'wall between the west wing and the hall', order=22)
fill('partition', PLASTER, 29, 1, 46, 55, 6, 46, 'building', 'wall between the waiting hall and the back room', order=23)
fill('door_b', 'air', 28, 1, 40, 28, 4, 42, 'building', 'opening to the west wing (BO2 electric door B)', order=24)
fill('door_a', 'air', 28, 1, 47, 28, 4, 49, 'building', 'opening to the west wing from the back room (door A)', order=25)
fill('door_750', 'air', 37, 1, 46, 40, 4, 46, 'building', 'the 750 door between the hall and the back room', order=26)
for i, (x1, z1, x2, z2) in enumerate([(28, 39, 28, 39), (28, 43, 28, 43), (28, 46, 28, 46), (28, 50, 28, 50), (36, 46, 36, 46), (41, 46, 41, 46)]):
    fill(f'jamb{i}', 'zombiecraft:yellow_trim', x1, 1, z1, x2, 4, z2, 'building', 'yellow door frame', order=26)
fill('roof', ROOF, 20, 7, 33, 56, 7, 53, 'building', 'roof', order=27)
# flush fluorescent panels under the roof, each with a dim light block below it (dark and moody, still readable)
for i, (x1, z1, x2, z2) in enumerate([(32, 39, 36, 39), (40, 39, 44, 39), (48, 39, 52, 39), (32, 43, 36, 43), (40, 43, 44, 43), (48, 43, 52, 43),
                                      (24, 38, 24, 41), (24, 46, 24, 50), (32, 49, 36, 49), (44, 49, 48, 49),
                                      (26, 35, 26, 37), (26, 43, 26, 45), (36, 36, 39, 36), (53, 36, 53, 40), (53, 49, 54, 49), (40, 51, 43, 51), (30, 36, 30, 40)]):
    fill(f'strip{i}', 'zombiecraft:light_panel', x1, 6, z1, x2, 6, z2, 'building', 'ceiling light panel', order=29)
    fill(f'glow{i}', 'light[level=13]', (x1 + x2) // 2, 5, (z1 + z2) // 2, (x1 + x2) // 2, 5, (z1 + z2) // 2, 'building', 'light source under the panel', order=29)
fill('canopy', 'dark_oak_slab[type=top]', 22, 6, 29, 54, 6, 32, 'depot', 'entrance canopy', order=28)
op('canopy_posts', 'grid', 'zombiecraft:pillar_round', 22, 1, 29, 54, 5, 29, 'depot', 'canopy posts (round pillars)', order=27, step=8)


def put(id, block, bx, bz, facing=None, y=1, group='lobby', n=1, dx=0, dz=0, order=45):
    """n copies of a decor block starting at (bx, y, bz), stepping (dx, dz); facing = direction the front looks at."""
    st = block if facing is None else f'zombiecraft:{block}[facing={facing}]'
    for i in range(n):
        fill(f'{id}{i}' if n > 1 else id, st, bx + i * dx, y, bz + i * dz, bx + i * dx, y, bz + i * dz, group, id, order=order)


# ---- props (decor blocks come from tools/gen_decor.py; facing = the way the front looks)
put('ticket_counter', 'ticket_counter', 40, 36, 'south', n=9, dx=1, order=40)
put('map_kiosk_a', 'map_kiosk', 47, 39, 'west', n=3, dz=1)
put('map_kiosk_b', 'map_kiosk', 47, 39, 'west', n=3, dz=1, y=2)
put('map_kiosk_c', 'map_kiosk', 47, 39, 'west', n=3, dz=1, y=3)
put('sign_bus', 'sign_bus', 43, 34, 'south', y=4)
put('sign_depot', 'sign_depot', 44, 34, 'south', y=4)
put('wall_clock', 'wall_clock', 50, 34, 'south', y=4)
put('sign_employees', 'sign_employees', 36, 45, 'north', y=3)
put('sign_fire', 'sign_fire', 35, 45, 'north', y=3)
put('poster_ride', 'poster_ride', 31, 45, 'north', y=2)
put('sign_restrooms', 'sign_restrooms', 53, 45, 'north', y=3)
put('chairs_w', 'waiting_chair', 31, 41, 'north', n=5, dx=1, order=42)
put('chairs_e', 'waiting_chair', 50, 41, 'north', n=5, dx=1, order=43)
put('chairs_back', 'waiting_chair', 38, 52, 'north', n=4, dx=1, group='back')
for i, (bx, bz) in enumerate([(35, 39), (51, 39), (34, 44), (52, 44)]):
    put(f'pillar{i}', 'pillar_round', bx, bz, 'north', n=1)
    for y in (2, 3, 4, 5): fill(f'pillar{i}_{y}', 'zombiecraft:pillar_round[facing=north]', bx, y, bz, bx, y, bz, 'lobby', 'round pillar section', order=45)
for i, (bx, bz) in enumerate([(29, 34), (54, 45), (31, 52), (53, 52)]):
    put(f'bin{i}', 'trash_can', bx, bz, 'south', group='lobby')
put('vending', 'vending', 53, 34, 'south')
put('lockers_a', 'locker', 21, 43, 'east', n=3, dz=1, group='wing')
put('lockers_b', 'locker', 21, 43, 'east', n=3, dz=1, group='wing', y=2)
put('crates_a', 'crate', 30, 51, 'north', n=2, dx=1, group='back')
put('crates_b', 'crate', 30, 51, 'north', y=2, group='back')
put('barrels', 'barrel', 52, 52, 'north', group='back')
for i, (x1, z1, x2, z2) in enumerate([(29, 44, 30, 45), (53, 52, 54, 52)]):
    fill(f'rubble{i}', 'gravel', x1, 1, z1, x2, 1, z2, 'lobby', 'rubble pile', order=46)
    fill(f'rubble_top{i}', 'cobblestone_slab', x1, 2, z1, x1, 2, z1, 'lobby', 'rubble pile top', order=46)
fill('pap_frame', 'zombiecraft:neon', 22, 1, 34, 24, 3, 35, 'pap', 'Pack-a-Punch (placed in the depot so the game loop works)', order=47)
fill('pap_glass', 'light_blue_stained_glass', 22, 2, 36, 24, 3, 36, 'pap', 'Pack-a-Punch front glass', order=48)
fill('pap_top', 'sea_lantern', 22, 4, 34, 24, 4, 35, 'pap', 'Pack-a-Punch light', order=49)
fill('turbine_table', 'smooth_stone', 25, 1, 50, 27, 1, 51, 'wing', 'turbine buildable table (bx 25, bz 48)', order=50)

# ---- the bus (parked on the road at the west end, where the bus entry points are)
fill('bus_body', 'light_gray_concrete', 14, 1, 21, 24, 3, 25, 'bus', 'bus shell', order=60)
fill('bus_hollow', 'air', 15, 1, 22, 23, 2, 24, 'bus', 'bus interior', order=61)
op('bus_windows', 'grid', GLASS, 15, 2, 21, 23, 2, 25, 'bus', 'bus windows', order=62, step=2)
fill('bus_stripe', 'green_concrete', 14, 1, 21, 24, 1, 25, 'bus', 'lower stripe', order=63)
fill('bus_roof', 'light_gray_concrete', 14, 4, 21, 24, 4, 25, 'bus', 'bus roof', order=64)

# ---- the town outside the windows: street-front buildings, a gas station, wrecks, bare trees, benches
FACADES = ['zombiecraft:cinder_block', 'zombiecraft:red_brick', 'zombiecraft:concrete_wall', 'zombiecraft:plaster_wall', 'zombiecraft:cinder_block', 'zombiecraft:green_panel']
for i, (x1, z1, x2, z2, fx1, fz1, fx2, fz2, along) in enumerate([(0, 17, 80, 19, 0, 19, 80, 19, 'x'), (0, 72, 80, 77, 0, 72, 80, 72, 'x'),
                                                                  (0, 20, 3, 71, 3, 20, 3, 71, 'z'), (77, 20, 80, 71, 77, 20, 77, 71, 'z')]):
    lo, hi = (x1, x2) if along == 'x' else (z1, z2)
    for k, c in enumerate(range(lo, hi + 1, 10)):   # one building per 10 blocks, each its own material and height
        e = min(c + 9, hi)
        hgt = 8 + (k * 5) % 4
        a1 = (c, z1, e, z2) if along == 'x' else (x1, c, x2, e)
        fill(f'town{i}_{k}', FACADES[(i + k) % len(FACADES)], a1[0], 1, a1[1], a1[2], hgt, a1[3], 'town', 'distant building', order=80)
        f1 = (c, fz1, e, fz2) if along == 'x' else (fx1, c, fx2, e)
        op(f'town_win{i}_{k}', 'grid', 'black_stained_glass', f1[0], 3, f1[1], f1[2], 4, f1[3], 'town', 'building windows (on the street face)', order=81, step=3)
        op(f'town_win2_{i}_{k}', 'grid', 'black_stained_glass', f1[0], 6, f1[1], f1[2], 7, f1[3], 'town', 'upper windows', order=81, step=3)
        fill(f'town_neon{i}_{k}', 'zombiecraft:neon', f1[0], 5, f1[1], f1[2], 5, f1[3], 'town', 'neon line (Tranzit)', order=81) if k % 3 == 1 else None
fill('gas_roof', 'dark_oak_slab[type=top]', 66, 5, 34, 78, 5, 42, 'town', 'gas station canopy', order=82)
op('gas_posts', 'grid', 'iron_bars', 67, 1, 35, 77, 4, 41, 'town', 'canopy posts', order=83, step=10)
fill('pumps', 'blackstone', 70, 1, 38, 74, 2, 38, 'town', 'fuel pumps', order=84)
for i, (bx, bz, c) in enumerate([(22, 27, 'brown'), (52, 30, 'gray'), (62, 24, 'red'), (8, 45, 'brown'), (66, 54, 'gray')]):
    fill(f'car{i}', f'{c}_concrete', bx, 1, bz, bx + 3, 1, bz + 1, 'town', 'wrecked car', order=85)
    fill(f'car_top{i}', 'black_stained_glass', bx + 1, 2, bz, bx + 2, 2, bz + 1, 'town', 'wrecked car cabin', order=85)
for i, (bx, bz) in enumerate([(8, 30), (10, 60), (68, 48), (72, 62), (6, 38), (30, 62), (60, 62)]):
    fill(f'tree{i}', 'dark_oak_log', bx, 1, bz, bx, 4, bz, 'town', 'bare tree', order=86)
    fill(f'crown{i}', 'dark_oak_fence', bx - 1, 5, bz - 1, bx + 1, 5, bz + 1, 'town', 'bare branches', order=86)
put('stop_bench', 'bench', 30, 28, 'north', n=4, dx=1, group='road', order=87)
put('stop_sign', 'bus_stop_sign', 34, 28, 'north', group='road', order=87)
for i, bx in enumerate((18, 40, 64)):
    put(f'lamp{i}', 'street_lamp', bx, 27, group='road', n=1, order=88) if False else None
    for y in (1, 2, 3): fill(f'lamp{i}_{y}', 'zombiecraft:street_lamp[facing=north]', bx, y, 27, bx, y, 27, 'road', 'street lamp pole', order=88)
    put(f'lamp{i}_head', 'street_lamp_head', bx, 27, 'south', y=4, group='road', order=88)
put('pump', 'gas_pump', 70, 38, 'south', n=2, dx=4, group='town', order=88)
put('jersey', 'jersey_barrier', 66, 44, 'north', n=6, dx=1, group='town', order=88)
put('town_barrel', 'barrel', 78, 36, 'north', n=3, dz=2, group='town', order=88)
put('town_crate', 'crate', 8, 52, 'north', n=2, dx=1, group='town', order=88)

# ---- lava pit behind the depot (BO2 depot_lava_pit), fenced
fill('pit', 'lava', 36, -2, 60, 50, -1, 68, 'pit', 'lava pit', order=70)
fill('pit_air', 'air', 36, 0, 60, 50, 0, 68, 'pit', 'open pit above the lava', order=71)
fill('pit_fence', 'iron_bars', 35, 1, 59, 51, 2, 59, 'pit', 'fence in front of the pit', order=72)
fill('pit_fence_w', 'iron_bars', 35, 1, 60, 35, 2, 68, 'pit', 'fence', order=73)
fill('pit_fence_e', 'iron_bars', 51, 1, 60, 51, 2, 68, 'pit', 'fence', order=74)
fill('pit_fence_s', 'iron_bars', 35, 1, 69, 51, 2, 69, 'pit', 'fence', order=75)
fill('pit_path', FLOOR_B, 35, 0, 58, 51, 0, 58, 'pit', 'path in front of the fence', order=76)


# ---- windows (the seven BO2 depot barricades) -> map_windows / map_spawns
def win(id, wall, fixed_b, a_b, room):
    # wall N/S: fixed is bz, a is bx; wall E/W: fixed is bx, a is bz
    if wall in ('N', 'S'):
        fixed, a = fixed_b - 47, a_b - 40
    else:
        fixed, a = fixed_b - 40, a_b - 47
    return dict(id=id, wall=wall, fixed=fixed, a=a, width=3, y0=2, height=2, boards=6, room=room, boardBlock='zombiecraft:barricade_board[axis=%s]' % ('x' if wall in ('N', 'S') else 'z'), sillBlock='zombiecraft:wood_floor')


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
def at(bx, bz): return L(bx, bz)
b1 = at(30, 37)   # lobby box, 2 blocks off the wing wall so the 3-wide box model never clips into it
b2 = at(50, 48)   # back-room box, 2 blocks off the partition
boxes = [dict(id='bx1', x=b1[0], y=1, z=b1[1], facing='east', initial=True, room='lobby'),
         dict(id='bx2', x=b2[0], y=1, z=b2[1], facing='south', initial=False, room='back')]
def machine(id, kind, perk, bx, bz, facing, room):
    x, z = at(bx, bz); return dict(id=id, kind=kind, perk=perk, x=x, y=1, z=z, facing=facing, room=room)
machines = [machine('m_power', 'power', '', 21, 46, 'east', 'wing'), machine('m_jug', 'perk', 'jug', 55, 43, 'west', 'lobby'),
            machine('m_speed', 'perk', 'speed', 55, 51, 'west', 'back'), machine('m_doubletap', 'perk', 'doubletap', 50, 52, 'north', 'back'),
            machine('m_revive', 'perk', 'revive', 42, 47, 'south', 'back')]
def door(id, cost, bx1, bz1, bx2, bz2, block, opens, cue, label):
    a, b = at(bx1, bz1), at(bx2, bz2)
    return dict(id=id, cost=cost, x1=a[0], y1=1, z1=a[1], x2=b[0], y2=4, z2=b[1], block=mc(block), opens=opens, cue=cue, label=label, room='lobby')
doors = [door('door_750', 750, 37, 46, 40, 46, 'zombiecraft:door_wood', 'back', 'zmb_small_wood_door', 'Back Room'),
         door('door_b', 1000, 28, 40, 28, 42, 'zombiecraft:door_metal', 'wing', 'zmb_power_door', 'West Wing'),
         door('door_a', 1000, 28, 47, 28, 49, 'zombiecraft:door_metal', 'wing', 'zmb_power_door', 'West Wing')]
p1 = L(22, 34)
p2 = L(24, 36)   # include the front glass so targeting/model replacement covers the whole machine
pap = [dict(id='pap1', x1=p1[0], y1=1, z1=p1[1], x2=p2[0], y2=4, z2=p2[1], facing='south', room='wing')]
ps = L(42, 40)
player = [dict(id='spawn', x=ps[0], y=1, z=ps[1], yaw=180.0,  # facing north: windows, ticket board and a wall gun in view
                room='lobby')]


# ---- BO2 models (ripped from the player's install at run time; the decor blocks they replace stay as the fallback and the collision)
props = []
YAW = {'south': 0, 'west': 90, 'north': 180, 'east': -90}


def prop(id, model, bx, bz, facing='south', y=1.0, ox=0.0, oz=0.0, rot=0, scale=1.0, hide=None, room='lobby'):
    """A BO2 model at the middle of block (bx, bz); facing = where its front looks; rot = extra yaw for models whose front is not on -y;
    hide = (bx1, y1, bz1, bx2, y2, bz2) cells (decor blocks, trees ...) the model replaces."""
    x, z = L(bx, bz)
    h = 'none'
    if hide:
        a, b = L(hide[0], hide[2]), L(hide[3], hide[5])
        h = f'{a[0]},{hide[1]},{a[1]},{b[0]},{hide[4]},{b[1]}'
    props.append(dict(id=id, model=model, x=x + 0.5 + ox, y=y, z=z + 0.5 + oz, yaw=YAW[facing] + rot, scale=scale, hide=h, room=room))


prop('kiosk', 'p6_zm_kiosk', 47, 40, 'west', hide=(47, 1, 39, 47, 3, 41))
for i, bx in enumerate((32.4, 35.1)):
    prop(f'seats_w{i}', 'p6_zm_bench_plastic', int(bx), 41, 'north', ox=bx - int(bx) - .5, rot=-90, hide=(31, 1, 41, 35, 1, 41) if i == 0 else None)
for i, bx in enumerate((51.4, 54.1)):
    prop(f'seats_e{i}', 'p6_zm_bench_plastic', int(bx), 41, 'north', ox=bx - int(bx) - .5, rot=-90, hide=(50, 1, 41, 54, 1, 41) if i == 0 else None)
prop('seats_back', 'p6_zm_bench_plastic', 39, 52, 'north', ox=.5, rot=-90, hide=(38, 1, 52, 41, 1, 52), room='back')
for i, (bx, bz) in enumerate([(29, 34), (54, 45), (31, 52), (53, 52)]):
    prop(f'bin{i}', 'p_glo_trashcan', bx, bz, hide=(bx, 1, bz, bx, 1, bz), room='lobby')
for i, bz in enumerate((43, 44, 45)):
    prop(f'locker{i}', 'p_rus_locker_closed', 21, bz, 'east', oz=0, ox=-.2, hide=(21, 1, bz, 21, 2, bz), room='wing')
prop('sign_restrooms', 'p6_zm_sign_restrooms', 53, 45, 'north', y=3.2, oz=.45, hide=(53, 3, 45, 53, 3, 45))
prop('clock', 'p_zom_clock', 50, 34, 'south', y=4.0, oz=-.45, hide=(50, 4, 34, 50, 4, 34))
for i, bx in enumerate((31.5, 34.2)):
    prop(f'stop_bench{i}', 'p6_zm_bench_old', int(bx), 28, 'north', ox=bx - int(bx) - .5, hide=(30, 1, 28, 33, 1, 28) if i == 0 else None, room='road')
for i, bx in enumerate((18, 40, 64)):
    prop(f'lamp{i}', 'p_glo_street_light02', bx, 27, 'south', hide=(bx, 1, 27, bx, 4, 27), room='road')
for i, (bx, bz) in enumerate([(35, 39), (51, 39), (34, 44), (52, 44)]):
    pass  # the round pillars have no BO2 model of their own; they stay blocks
for i, (x1, z1, x2, z2) in enumerate([(32, 39, 36, 39), (40, 39, 44, 39), (48, 39, 52, 39), (32, 43, 36, 43), (40, 43, 44, 43), (48, 43, 52, 43), (32, 49, 36, 49), (44, 49, 48, 49)]):
    prop(f'light{i}', 'p_glo_lights_fluorescent_yellow_on_depot', (x1 + x2) // 2, (z1 + z2) // 2, 'south', y=6.0, rot=90 if x2 > x1 else 0)
# extra scenery from the depot zone and the Tranzit town
prop('payphone', 'com_payphone_america', 29, 38, 'east', y=2.0, ox=-.45)
prop('fountain', 'ch_water_fountain', 30, 35, 'east', y=1.0)
prop('magazines', 'p6_zm_magazines_rack', 54, 38, 'west', ox=.45)
prop('sofa', 'p6_sofa_damaged_panama', 23, 41, 'east', room='wing')
prop('trash_pile0', 'mp_m_trash_pile', 30, 45, hide=None)
prop('trash_pile1', 'mp_m_trash_pile', 54, 52, room='back')
prop('suitcase', 'p_eb_lg_suitcase', 32, 44, 'south', ox=.2)
prop('stanchion0', 'p6_stanchion_post', 40, 38)
prop('stanchion1', 'p6_stanchion_post', 48, 38)
prop('workbench', 'p6_zm_work_bench', 26, 51, 'west', room='wing')
prop('tools', 'p_glo_tools_chest_tall', 22, 51, 'east', room='wing')
prop('radiator', 'ch_radiator01', 55, 40, 'west', ox=.4)
prop('ac0', 'p_rus_ac_unit', 30, 40, y=8.0, room='roof')
prop('ac1', 'p_rus_ac_unit', 48, 48, y=8.0, room='roof')
prop('dumpster', 'p_rus_dumpster_zm_bstation', 59, 55, 'west', room='road')
prop('outhouse', 'p6_zm_outhouse', 70, 58, 'north', room='road')
prop('planter0', 'ny_harbor_planter', 26, 30, room='road')
prop('planter1', 'ny_harbor_planter', 58, 30, room='road')
prop('tower', 'p6_zm_water_tower', 70, 66, room='road')
prop('pole0', 'p6_zm_street_power_pole', 6, 28, room='road')
prop('pole1', 'afr_powerpole1', 76, 30, room='road')
prop('bikes', 'com_bike_destroyed', 15, 30, room='road')
prop('tires', 'com_junktire', 62, 34, y=1.2, room='road')
prop('pallet', 'afr_pallet_destroyed', 8, 53, room='road')
prop('sandbags0', 'p_glo_sandbags_green_lego_mdl', 20, 31, 'south', room='road')
prop('caution', 'p_jun_caution_sign', 36, 29, 'north', room='road')
for i, (bx, bz, c) in enumerate([(22, 27, 'brown'), (52, 30, 'gray'), (62, 24, 'red'), (8, 45, 'brown'), (66, 54, 'gray')]):
    prop(f'wreck{i}', 'veh_t6_civ_microbus_dead', bx + 1, bz, 'south', ox=.5, rot=90 if i % 2 else 0, hide=(bx, 1, bz, bx + 3, 2, bz + 1), room='road')
for i, (bx, bz) in enumerate([(8, 30), (10, 60), (68, 48), (72, 62), (6, 38), (30, 62), (60, 62)]):
    prop(f'tree{i}', 't5_foliage_tree_burnt02' if i % 2 else 't5_foliage_tree_burnt03', bx, bz, 'south', scale=.6, hide=(bx - 1, 1, bz - 1, bx + 1, 5, bz + 1), room='road')


def dump(name, rows):
    with open(os.path.join(SH, name), 'w', encoding='utf-8') as f:
        json.dump(rows, f, indent=1, ensure_ascii=False)
        f.write('\n')


dump('map_ops.json', sorted(ops, key=lambda o: o['order']))
dump('map_windows.json', windows)
dump('map_spawns.json', spawns)
dump('map_wallbuys.json', wall)
dump('map_boxes.json', boxes)
dump('map_machines.json', machines)
dump('map_doors.json', doors)
dump('map_pap.json', pap)
dump('map_player.json', player)
dump('map_props.json', props)
print(len(ops), 'ops,', len(windows), 'windows,', len(spawns), 'spawns')

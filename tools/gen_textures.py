#!/usr/bin/env python3
"""Writes sheets/textures.json and sheets/atmosphere.json.

textures.json maps a Minecraft/mod block TEXTURE to a Black Ops II IMAGE. The game builds a resource pack from these rows on the player's PC
(bo2/TexturePack) out of their own install; no BO2 art is ever stored in this repo.
  map      "*" = every map; otherwise a map id. A map-specific row for the same texture beats the "*" row.
  texture  "minecraft:stone" or "zombiecraft:glass_brick" (a file under assets/<ns>/textures/block/).
  bo2      image name (without ~-g prefix / .dds). "checker:a+b" tiles two images in a 2x2 checkerboard.
  opaque   flatten any transparency over the image's average colour (solid blocks).
  tint     optional #RRGGBB multiplied over the result (to darken or warm a texture).
To add a map: add rows with its id (and one atmosphere row). The BO2 image catalogue (every colour map per zone) is made by
tools/texture_catalog.py to help pick images.
"""
import json, os

SH = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', 'sheets')

T = []
def t(texture, bo2, map='*', opaque=True, tint='', note=''):
    row = dict(id=f"{map}:{texture}", map=map, texture=texture, bo2=bo2, opaque=opaque)
    if tint: row['tint'] = tint
    if note: row['note'] = note
    T.append(row)

# ---- Tranzit family (Bus Depot, Diner, Farm, Town, Power Station share these; map-specific rows can override)
t('minecraft:stone', 'metal_aluminum01_painted_dirty_col', note='dirty painted concrete')
t('minecraft:andesite', 'me_wall_metalpanels_1_col', note='grey wall panel')
t('minecraft:polished_andesite', 'eb_art_wall_metal_panels_c', note='smooth wall panel')
t('minecraft:smooth_stone', 'metal_aluminum01_painted_dirty_col')
t('minecraft:stone_bricks', 'ug_wall_brick_blue_col', tint='#D8B898', note='brick, warmed up (the plaster decal tiled badly)')
t('minecraft:deepslate_bricks', 'berlin_ceiling_wood_burnt_c', note='burnt ceiling boards (roofs)')
t('minecraft:dark_oak_planks', 'rus_floor_wood_barn_c', note='dark floor boards')
t('minecraft:oak_planks', 'p6_wood_plank_rustic01_c', note='rustic planks (barricade boards)')
t('minecraft:iron_block', 'metal_galvanised_ducting_dirty_c')
t('minecraft:iron_bars', 'zombie_chainlink_fence_c', opaque=False, note='chain-link fence')
t('minecraft:light_gray_concrete', 'asphalt_road_single_lane01_c', note='road')
t('minecraft:black_concrete', 'asphalt_road_cracks_col', note='cracked road')
t('minecraft:green_concrete', 'metal_darkgreen_col')
t('minecraft:light_blue_concrete', 'p6_zm_neon_cyan_c', note='neon trim')
t('minecraft:sea_lantern', 'p6_zm_neon_white_c')
t('minecraft:shroomlight', 'lightfluohang_yellow_c', note='fluorescent light')
t('minecraft:dirt', 'dirt_rocky01_c')
t('minecraft:grass_block_top', 'afr_ground_grass_01_col', note='dead grass (colormap is neutral in the pack)')
t('minecraft:grass_block_side', 'dirt_rocky01_c')
t('minecraft:lava_still', 'rock_lava_cooling_c', note='static; vanilla animation is dropped')
t('minecraft:lava_flow', 'rock_lava_cooling_c')

# ---- custom blocks (zombiecraft:*): placeholders ship in the jar, these override them
t('zombiecraft:glass_brick', 'cub_art_glass_brick_d')
t('zombiecraft:cinder_block', 'ug_wall_brick_blue_col', tint='#D8B898')
t('zombiecraft:asphalt', 'asphalt_road_single_lane01_c')
t('zombiecraft:concrete_wall', 'metal_aluminum01_painted_dirty_col')
t('zombiecraft:metal_panel', 'eb_art_wall_metal_panels_c')
t('zombiecraft:depot_tile', 'checker:eb_art_wall_metal_panels_c+asphalt_road_single_lane01_c', note='black and white floor, tiled from two BO2 images')
t('zombiecraft:wood_floor', 'rus_floor_wood_barn_c')
t('zombiecraft:ground', 'dirt_rocky01_c')
t('zombiecraft:grass', 'afr_ground_grass_01_col')
t('zombiecraft:barricade_board', 'p6_wood_plank_rustic01_c', opaque=True)
t('zombiecraft:fence', 'zombie_chainlink_fence_c', opaque=False)
t('zombiecraft:neon', 'p6_zm_neon_cyan_c')

json.dump(T, open(os.path.join(SH, 'textures.json'), 'w'), indent=1)

# fog and sky: colours as #RRGGBB, distances in blocks, stars 0..1, timeOfDay in ticks (18000 = midnight, -1 = leave the world's clock)
A = [
    dict(id='*', fogColor='#2A2118', fogStart=8.0, fogEnd=64.0, skyColor='#15110D', cloudColor='#2A2018', stars=0.25, timeOfDay=15500,
         note='default: warm brown night haze'),
    dict(id='bus_depot', fogColor='#3B2A1B', fogStart=6.0, fogEnd=56.0, skyColor='#17110C', cloudColor='#33261A', stars=0.2, timeOfDay=15500,
         note='Tranzit: dusty orange-brown haze, dark sky, short sight lines'),
]
json.dump(A, open(os.path.join(SH, 'atmosphere.json'), 'w'), indent=1)
print(len(T), 'texture rows,', len(A), 'atmosphere rows')

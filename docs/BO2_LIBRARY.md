# The Black Ops II library (for building maps)

A local, searchable copy of everything in your own Black Ops II install: textures, models, animations, sounds, weapons, effects, map geometry, collision, path nodes,
spawns, lights, AI data and more. It is **built on your PC from your game** by the tools in `tools/` and is never committed (the repo is public, the game is not ours).
What the repo does carry is small: the tools, a name catalogue (`catalog/`) and the placement data of Tranzit (`mapdata/zm_transit/`), so you can plan without the game.

## 1. Set up (once per machine)

```bash
python tools/bo2_setup.py                 # shows what is missing
python tools/bo2_setup.py --fetch-header  # downloads OpenAssetTools' struct header (GPL-3.0 source text) into the library
python tools/bo2_setup.py --all           # builds everything: ~1-2 h, ~50 GB.  --tier zm  = zombies only (fast)
```
You need the game (Steam) and the Unlinker from the [OpenAssetTools releases](https://github.com/Laupetin/OpenAssetTools/releases).
Paths live in `tools/bo2_paths.py` (override with `BO2_LIB`, `BO2_OAT`, `BO2_DIR` or one-line files `.bo2lib`, `.bo2oat`, `.bo2dir`, `.bo2scripts` in the repo root).
Decompiled scripts (optional, for `scripts/`) come from a separate dump of the game's GSC; point `BO2_SCRIPTS` at it.

## 2. Find things

```bash
python tools/bo2_library.py find "ai_zombie_walk" --type xanim          # animations
python tools/bo2_library.py find "t6_wpn_ar_m14" --type model            # models (LOD files)
python tools/bo2_library.py find "zmb_cha_ching" --type alias            # sounds by the name scripts use
python tools/bo2_library.py find "." --type worldmap --zone zm_transit   # every map file
```
kinds: `image model xmodel xanim material weapon sound alias worldmap fx misc gsc ai video extra ...`. `INDEX.md` in the library lists counts per kind and zone.

## 3. Layout of the library

| Folder | Content |
|---|---|
| `zones/<zone>/` | Unlinker dump of every zone: `images/*.dds`, `model_export/*.xmodel_export` (+ `xmodel/*.json`), `xanim/`, `materials/`, `weapons/`, `camo/`, `animstatedefs/`, `soundbank/` (alias tables), `english/localizedstrings/`, `maps/*.ents` (entity text) ... |
| `audio/<bank>/` | every sound bank entry as `.flac` / `.wav`, named by source path; `audio/aliases.json` = script alias -> files |
| `maps/<zone>/` | map data, see section 4 (36 maps) |
| `fx/<zone>.json` | effect definitions (elements, spawn, lifespan, velocity, colour/size over time, materials, trails, decals) |
| `misc/<zone>.json` | glass panes, destructibles, impact tables, font glyphs, sound driver globals (volume groups, curves, pans, masters, futz), key/value tables, DDL definitions, sound patches |
| `ai/` | zombie AI: `animstates.json`, `aitypes.json`, `zombie_assets.json`, `README.md` (where each rule lives) |
| `scripts/` | decompiled GSC/CSC of every map and the zombies core |
| `pap_camo.json`, `weapons_report.json` | Pack-a-Punch camo per weapon family; every weapon -> models -> materials -> images (all present) |
| `video/`, `extra/` | the game's `.webm` movies; localization and player config files |
| `index.db`, `INDEX.md` | search index |

## 4. A map folder (`maps/<zone>/`)

Coordinates are **game units**: x east, y north, z up. The project's block convention is 1 block = 40 units (see `tools/obj_to_blocks.py`; depot: `bx = (x + 8400) / 40`, `bz = (6900 - y) / 40`).

| File | What |
|---|---|
| `world.obj/.mtl`, `world.npz` | the BSP surface mesh. npz: `verts, uvs, normals, tangents_sign, colors (RGBA), lightmap_uvs, tris (CCW), tri_surface`; `surfaces.json` (material, bounds per surface), `materials.json` (material -> colour/normal images, found in `zones/<zone>/images`) |
| `smodels.json` | every placed static model: name, origin, axis (3x3), scale |
| `collision.npz/.obj`, `collision_hulls.npz/.obj`, `collision_brushes.json` | terrain collision triangles; every collision brush as a convex hull with its contents flags |
| `accel.npz`, `accel.json` | collision BSP nodes / leafs / AABB trees / partitions, render planes + nodes + cell AABB trees, collision static models |
| `pathnodes.json` | the AI navigation graph: nodes (type, origin, facing, links with distances; traversal nodes carry `ent.animscript`, e.g. `zm_jump_down_127`) |
| `spawns.json`, `entities.json`, `entities_full.json` | spawn points grouped (`initial_spawn`, `player_respawn`, `zombie_spawn_location`, dog / screecher / avogadro locations, zone spawners, barricades, windows, box, perks, wall weapons, doors ...), all entities (slim and full) |
| `volumes.json`, `submodels.json`, `triggers.json` | every brush entity (zone volumes, triggers, clips) with its world box |
| `vehicle_paths.json` | vehicle node graph and the paths it forms (Tranzit: the closed 79,731-unit bus loop), `script_vehicle` entities |
| `lights.json`, `lighting.json`, `lightgrid.npz/.json`, `cells.json` | primary lights, sun / sky / fog / exposure / LUT volumes / probes / lightmaps, the light grid, visibility cells + portals |
| `dynents.json`, `constraints.json`, `ropes.json` | dynamic entities, rope / physics constraints |
| `footsteps.json` | surface -> footstep sound alias and effect |

```python
import numpy as np, json
m = np.load(r'<lib>/maps/zm_transit/world.npz')
V, F, N = m['verts'], m['tris'], m['normals']                    # game units
sp = json.load(open(r'<lib>/maps/zm_transit/spawns.json'))        # sp['initial_spawn'][0] -> {'origin': [x, y, z], 'angles': ...}
```
The repo's `mapdata/zm_transit/*.json.gz` holds the placement files above (no meshes) so a clone can read spawns, volumes, path nodes etc. without building the library.

## 5. Building a Minecraft map from it
1. `world.obj` -> blocks: `python tools/obj_to_blocks.py <lib>/maps/zm_transit/world.obj --up z --check` (region and materials are configured at the top of that tool; the materials map to the mod's own blocks).
2. Props: `smodels.json` names the model to draw at each origin/axis/scale; model files are in `zones/*/model_export` (extracted on the player's PC at run time by the mod, never shipped).
3. Gameplay: `spawns.json` (where players and zombies appear), `volumes.json` (zone boxes: `info_volume` with `targetname zone_*`), `pathnodes.json` + `collision_hulls` (where zombies can walk), `vehicle_paths.json` (the bus).
4. Rules: `ai/README.md` and `scripts/` for how the zombies behave; `sheets/*.json` hold the numbers the mod already uses.

## 6. What the extraction can and cannot do
The Unlinker writes most asset types itself. BSP, collision, light grid, effects, path nodes, glass, destructibles, impact tables, fonts and a few more are read out of the running
Unlinker's memory (`tools/bo2_proc.py` freezes it right after it loaded a zone; `tools/bo2_structs.py` knows the struct layouts from `T6_Assets.h`). Details and caveats are in `MODLOG.md`.

## 7. Legal
The tools are MIT (this repo). OpenAssetTools is GPL-3.0 and is **not** bundled. Everything in the library is Activision's: keep it on your machine, do not commit or share it.

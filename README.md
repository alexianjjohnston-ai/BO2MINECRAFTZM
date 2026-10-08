# Block Ops 2 (Black Ops Zombies in Minecraft)

Round-based Zombies survival inside Minecraft: Java Edition (Fabric, 1.21.4), in a blocky Tranzit Bus Depot.
Unofficial fan project, not affiliated with Activision, Treyarch, Mojang or Microsoft.

**BO2 files are never included or modified.** Sounds are read, read-only, from *your own* Black Ops II install the first time you play
and cached locally (about 27 MB). Without Black Ops II the game still plays with Minecraft sounds.

## Play it from source
1. Install a JDK 25 (needed only to run the build tool) and own Minecraft: Java Edition.
2. Double-click **`play.bat`** (first run downloads Minecraft + build tools, several minutes). It opens straight into a flat world.

Controls (Black Ops II PC defaults): **WASD** move · **left click** fire · **right click** aim down the sight · **R** reload · **V** knife · **G** frag grenade (hold to cook, let go to throw) · **F** buy / open Mystery Box / Pack-a-Punch / revive / hold to repair windows · **Left Shift** sprint · **C** crouch · **Z** prone · **Space** jump · **1-3 / mouse wheel** switch weapon · **Tab** scoreboard · **T** chat. All of them can be changed in Options > Controls.

Black Ops II is found automatically (Steam libraries). To point at it by hand: `mod/run/config/zombiecraft.properties` with `bo2.dir=D:/path/to/Call of Duty Black Ops II`.

## Layout
| Folder | What |
|---|---|
| `sheets/` | The design, as JSON sheets (rounds, weapons, zombies, box, map, audio cues...). Source of truth; bundled into the mod. |
| `mod/` | The Fabric mod (Java). `./gradlew build` / `runClient`; `-Pbench` runs the scripted self-test. |
| `tools/` | `preflight.py` (checks every sheet cell and reference, builds the map in memory), generators (`gen_weapons.py`, `derive_audio.py`, `gen_assets.py`), `package.py` (release zip with portable Prism). |
| `MODLOG.md` | Journal: decisions, findings (BO2 sound bank format, hashes), test results. |

Check the design before building: `python tools/preflight.py --bo2 "<BO2 folder>"` (0 errors expected).
`tools/derive_audio.py` and `tools/gen_weapons.py` need a local read-only dump of BO2 data made with OpenAssetTools (see MODLOG.md); the generated sheets are committed.

## Licence
Code and sheets: MIT. Not included: Black Ops II assets, Minecraft, Prism Launcher (GPL-3.0, only bundled in release zips), Fabric (Apache-2.0).

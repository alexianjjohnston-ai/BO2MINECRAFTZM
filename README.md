# Block Ops 2 (Black Ops Zombies in Minecraft)

Round-based Zombies survival inside Minecraft: Java Edition (Fabric, 1.21.4), in a blocky Tranzit Bus Depot.
Unofficial fan project, not affiliated with Activision, Treyarch, Mojang or Microsoft.

**BO2 files are never included or modified.** Sounds are read, read-only, from *your own* Black Ops II install the first time you play
and cached locally (about 27 MB). Without Black Ops II the game still plays with Minecraft sounds.

## Play it from source
1. Install a JDK 25 (needed only to run the build tool) and own Minecraft: Java Edition.
2. Double-click **`play.bat`** (first run downloads Minecraft + build tools, several minutes). It opens straight into a flat world.

### Mac (and Linux)
1. Open Terminal and run `git clone https://github.com/alexianjjohnston-ai/BO2MINECRAFTZM && cd BO2MINECRAFTZM && ./play.command`, or download just **`play.command`**, put it in an empty folder and double-click it (first time: right-click > Open, or `xattr -d com.apple.quarantine play.command`). It updates itself, downloads JDK 25 into the folder if you have none, then starts the game. You need Minecraft: Java Edition, signed in the first time Minecraft asks.
2. Black Ops II has no Mac version, so its files have to come from somewhere else. Sounds only need the **`sound`** folder (about 30 MB) from a Black Ops II install on a PC; put it in a folder called `Call of Duty Black Ops II` anywhere under your home folder, on an external drive, or in `~/Library/Application Support/Steam/steamapps/common/`. A Windows Steam inside CrossOver or Whisky is found too. To point at it by hand: `export ZOMBIECRAFT_BO2_DIR="/path/to/Call of Duty Black Ops II"` before `./play.command`, or `bo2.dir=` in `mod/run/config/zombiecraft.properties`.
3. Models, menu art and block textures are converted from the BO2 files by OpenAssetTools' Unlinker, which has no Mac build. This is automatic: when `play.command` finds the BO2 files (the whole folder, not only `sound`: the models come from `zone/all`) and the finished files are missing, it downloads a prebuilt Wine (about 190 MB, into the game folder, plus Rosetta on Apple Silicon, which asks for your password once) and the game converts everything on first start (several minutes). Instead of that you can copy `mod/run/zombiecraft/bo2` and `mod/run/resourcepacks/BlockOps2` from a PC that already ran the game. With neither, the game plays with plain shapes and Minecraft models and says why in `mod/run/logs/latest.log`.

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

## Building maps from Black Ops II data
The tools in `tools/` can extract textures, models, animations, sounds, map geometry, collision, path nodes, spawns and more from your own Black Ops II install into a local library; see [docs/BO2_LIBRARY.md](docs/BO2_LIBRARY.md). Nothing from the game is stored in this repository.

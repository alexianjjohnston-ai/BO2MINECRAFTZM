# MODLOG: Zombiecraft (Black Ops II Zombies inside Minecraft: Java Edition)

Journal for the mashup. Anything not written here is lost at the next context compaction.

## Intent (agreed with the creator, 2026-10-06)
- Host game: Minecraft: Java Edition, Fabric mod, bundled in a portable Prism Launcher (Melty installs no loader itself).
- Guest game: Call of Duty: Black Ops II (Steam, PC). Required game: the player's own copy supplies the audio.
- One sentence: a solo, round-based Zombies survival game in a blocky Tranzit diner and gas station town,
  with BO2's own sounds and the BO2 Zombies rules (rounds, points, barricades, wall-buys, Mystery Box, Pack-a-Punch).
- First minute: spawn at the diner, short countdown, round 1 starts with the BO2 round-start sting, boards on windows, buy a wall gun.
- Players: solo first, co-op (up to 4) later. Design must not block co-op: all state is per player, server authoritative.
- v1 must have: core loop (rounds, points, barricades, wall-buys), Mystery Box + Pack-a-Punch, real BO2 audio.
  NOT in v1: perks, power switch, power-ups, dogs, co-op.
- Creator allows Call of Duty branding in the title (owns both games). Still list it as an unofficial fan project.

## Route and why
Pattern 1/5 from the toolkit's mashup-mods skill: all gameplay is our own Fabric mod code (rules reimplemented from
the BO2 script numbers); the guest's assets are converted on the player's PC from their own install into a private
local cache; the mod stays playable (vanilla sounds as fallback) when the cache is missing.
- BO2 itself is never launched, patched or hooked by the mod. Its files are only READ (sound banks).
- BO2 is online-capable with VAC: so no BO2 file is ever modified and nothing here talks to BO2's servers.
- Plutonium is NOT a dependency. Creator offered Plutonium LAN only as a local aid for checking BO2 behaviour.

## Anti-cheat / account risk
- BO2 (VAC): modifying BO2 + playing online can get a Steam account banned. We never modify it. Read-only access to `sound/*.sab?`.
- Minecraft: no anti-cheat for solo/LAN. The mod is for solo worlds / own LAN, not for public servers.

## Machine facts (this PC)
- BO2 (real install): `D:\SteamLibrary\steamapps\common\Call of Duty Black Ops II` (has t6mp.exe, t6zm.exe, no t6sp.exe).
  `C:\Program Files (x86)\Steam\steamapps\common\Call of Duty Black Ops II` is an empty stub (installscript.vdf only).
- Reference script dump (decompiled GSC, NOT to be shipped): `C:\Users\alexi\Downloads\t6-scripts-main\t6-scripts-main\ZM`
- Minecraft: Microsoft Store launcher at `%APPDATA%\.minecraft` (many versions + Fabric profiles). Not touched by this project.
- JDKs: `C:\Program Files\Java\jdk-21` (used to build), `jdk-25.0.2`.
- Toolkit: `C:\Users\alexi\um-toolkit` (universal-modder, cloned). Dev dumps of BO2 data: `C:\Users\alexi\bo2-dump` (never shipped, never in the project).
- Project: `C:\Users\alexi\zombiecraft` (this folder). Short path on purpose (git/Gradle hit Windows path limits in the session scratch folder).

## Versions
- Minecraft 1.21.4, Fabric Loader 0.19.5, Fabric API 0.119.4+1.21.4, Loom 1.18-SNAPSHOT, Mojang mappings, Java 21, Gradle 9.7.1 (wrapper).

## Findings: BO2 audio (feasibility gate PASSED, 2026-10-06)
- `sound/*.sabs` (streamed) and `*.sabl` (loaded) are "2UX#" banks, version 14, little endian.
  Header 0x800 bytes: entryCount @0x14 (u32), entrySize @0x08 (=0x14), entryOffset @0x28 (u64).
  Entry (0x14): id u32, size u32, offset u32, frameCount u32, rateIdx u8, channels u8, looping u8, format u8.
  format 0 = raw PCM s16le (.sabl), format 8 = FLAC stream starting with "fLaC" (.sabs). rateIdx 6 = 48000 Hz.
  Spec: https://codresearch.dev/index.php/SABS_%26_SABL_Files
- Sound entry id = SND_HashName(assetFileName): h=0x1505; for each byte (lowercased) h = c + 0x1003F*h (u32); 0 -> 1.
  assetFileName is the alias "FileSource" exactly as dumped (backslashes, `raw\sound\...\x.LL55.pc.snd`, without the `.flac` suffix the dumper adds).
  Source: OpenAssetTools `src/Common/Game/T6/CommonT6.h` (SND_HashName) and `SndBankDumperT6.cpp`.
- Alias name -> file path table came from OpenAssetTools Unlinker v0.33.0 (dev tool only, run read-only on `so_zclassic_zm_transit.ff`):
  9915 alias rows, 3051 unique alias names, ALL 9915 resolve to a real bank entry with the hash above.
- Verified: `zmb_cha_ching` (PCM, zmb_common.all.sabl, 2.77 s mono 48k) and `mus_zombie_round_start`
  (FLAC, zmb_common.all.sabs, 14.02 s stereo 48k; STREAMINFO sample count == bank frameCount).
- Player-side plan: the mod finds BO2 (Steam library scan), reads the banks itself (Java), decodes FLAC (bundled MIT-licensed decoder),
  writes WAV cues into its own cache folder. Shipped data = only (cue id, bank file name, entry id, size) metadata, never audio.

## Findings: BO2 rules (from the script dump, used in sheets)
- Health: r1 150, +100 per round to r9 (950), then x1.1 per round (int). Move speed var = round*8; per zombie rand in [v, v+35):
  <=35 walk, <=70 run, else sprint. Spawn delay 2.0 x 0.95 per round (min 0.08). Between rounds 10 s.
- Zombie count: max=24 + int(0.5*6*mult) solo, mult=max(1, r/5) (x r*0.15 from r10); r1 x0.25, r2 x0.3, r3 x0.5, r4 x0.7, r5 x0.9.
- Points: hit 10; kill 50 + head 50 / neck 20 / torso 10 / melee 80. Start 500. Box 950. Pack-a-Punch 5000.
- Teddy rule: box moves when `chance_of_joker > random`; none before 4 uses; see sheets/box.json.

## Open items / risks (update as they resolve)
- [x] FLAC decoding: own decoder, verified.
- [ ] Microsoft sign-in in the bundled Prism on first launch (can't be removed; Minecraft needs an owned account).
- [ ] Two-copy co-op test is out of v1.

## Build state (updated as work lands)
- Sheets (16) in `sheets/`, preflight `python tools/preflight.py --bo2 <BO2 dir>` = 0 errors. It also scans the Java code for
  `Sheets.sys("key")` and `Cue.xxx("cue")` references, and builds the map in memory to check windows, spawns, wall-buys, box, PaP and reachability.
- Mod source in `mod/` (Fabric 1.21.4, Mojang names). Build/run with JDK 25 as JAVA_HOME (Loom 1.18 needs it; bytecode is Java 21):
  `JAVA_HOME="C:/Program Files/Java/jdk-25.0.2" ./gradlew build` / `runClient` (dev client has -Dzombiecraft.autoplay=true).
- Design deviations from the first plan (and why):
  - Interact key is F (buy, box, PaP, hold to repair windows); right click = fire; left click = knife; R = reload. Right click on chest/blocks is cancelled.
  - Players are in Adventure mode so nothing can be broken or placed.
  - Audio: own FLAC decoder (verified with per-frame CRC-16, 658/658 frames on mus_zombie_round_start), own mixer on a javax.sound line (no OpenAL poking).
  - Ray Gun deferred: its fire alias is not in the zombie alias tables I dumped.
- Audio pipeline verified in plain Java against the real install: 160 cue files, 26.8 MB WAV, 0.6 s, 0 skipped; re-run uses the cache.
- BO2 locator found `D:\New folder (2)\steamapps\common\Call of Duty Black Ops II` via Steam's library list (a second copy is in D:\SteamLibrary).
- Prism notes: portable mode = `portable.txt` next to the exe. With no Microsoft account Prism offers a time-limited demo only, and a pre-made
  offline account would sidestep Minecraft's ownership check, so we do NOT do that: first launch needs the player's own Microsoft sign-in (same as IsaacCraft).

## Test results (2026-10-06)
- User played a dev session by hand: finished round 1 (500 -> 1220 points), 15 minutes, no exceptions in the log.
- Bug reported by the user: first spawn landed outside the map (the teleport in the JOIN event races the join); after /kill the restart placed them in the map.
  Fix: world spawn is set inside the diner at server start (spawnRadius 0) and players are re-teleported 15 ticks after start if they are far away.
- Scripted self-test (`./gradlew runClient -Pbench`, results in `mod/run/zc-bench.txt`, screenshots in `mod/run/screenshots/zc-*.png`):
  PASS round-start, shoot-kill, wallbuy-gun, wallbuy-ammo, box-pay x4, box-take x3, box-teddy (use 4), box-moves, pap-start, pap-take (Mnesia 16/128).
  FAIL window-repair (1/6 boards, +10): the user was playing in the same window (mouse and clicks, ammo dropped), so treat as unproven and re-run alone.
  NOT RUN yet: window-entry (a real zombie through a window), round-end/round-2, game-over/restart (restart was exercised by hand via /kill).
- Audio in the real game: 160 sounds extracted from the BO2 found via Steam's library list, 0 skipped, mixer loaded 160. Confirmed working by ear (user, 2026-10-06).
- Harmless vanilla log line "Display entity: Not a string" = text_display without `alignment`; silenced by adding alignment:"center".

## Melty: DROPPED (2026-10-06)
Publishing is off the table for now (user decision). The private draft on melty.gg is unused; ignore Melty tools. Revisit only when the game is finished.

## Log
- 2026-10-06: Melty read-up done (no existing Minecraft x BO2). Interview done. BO2 audio feasibility gate passed. Project folder + template created.
- 2026-10-06: sheets + preflight, full server game code, client HUD/input, audio extractor + mixer written; first dev launch pending.

## Repo move (2026-10-06)
Project now lives in `C:\Users\alexi\OneDrive\Desktop\BO2MINECRAFTZM` (git, MIT). The earlier working copy `C:\Users\alexi\zombiecraft` is superseded (it also holds the dev run folder and dist/).
Dev-only inputs stay outside the repo: BO2 dumps in `C:\Users\alexi\bo2-dump`, toolkit in `C:\Users\alexi\um-toolkit`.
Roadmap from the user's list (not done yet): bigger original map with zones + bought doors + bounds, power switch + perks + power-ups,
BO2 models/textures/animations (zombie bodies c_zom_zombie1_body01 etc. are extractable with OpenAssetTools), 3D guns, Mystery Box model.
Done: real BO2 weapon stats incl. Ray Gun (sheets/weapons.json via tools/gen_weapons.py), projectiles, barricade repair from anywhere inside.

## Stable checkpoint (2026-10-06): scripted self-test 27/27 PASS
round start, shoot+headshot points, wall-buy gun+ammo, Mystery Box x7 incl. teddy + move, Pack-a-Punch (Mnesia 16/192), window repair, real zombie
through a window, round end -> round 2, game over -> restart. Bugs fixed on the way: starting gear was skipped on the very first join (race with
level.players()), first spawn outside the map. Run it: `cd mod && ./gradlew runClient -Pbench` (results in mod/run/zc-bench.txt, screenshots in mod/run/screenshots).
Dev tip: set `pauseOnLostFocus:false` in mod/run/options.txt or the integrated server pauses whenever the window is not focused.

## Next (updated 2026-10-07)
Done since: zombie renderer, Bus Depot map, doors/zones, ZcProp machines, animated Pack-a-Punch. Self-tests: `-Pbench` 33/33, `-PpapBench` 19/19.
Plan: (1) menus match the Plutonium reference screenshots (leaderboards, theater, options, tab scoreboard, game over); (2) PaP intake visual, depot look/lighting; (3) co-op, dogs, more perks and maps. Video reference needs ffmpeg to extract frames.

## UI with real BO2 art (2026-10-06)
Menus and HUD use art converted on the player's PC from their own install (`bo2/UiAssets`, cache `zombiecraft/bo2/ui/`, needs OAT Unlinker like the models). Falls back to plain drawing without it.
Done: title screen, lobby, map select (Green Run open, 5 locked "coming soon"), BO2 loading postcards, pause menu, HUD (weapon icons incl. Ray Gun, round tally marks, low-health red edge instead of hearts/XP).
Perk icons are extracted (specialty_*_zombies) but not drawn yet: the HUD perk row comes with the perk system.
Dev: put `oat.dir=<OAT folder>` in mod/run/config/zombiecraft.properties so the dev client can build the art cache.

## Naming and HUD pass (2026-10-06)
User-facing name is now "Block Ops 2" (UI, lang, mod name, README, licence). Internal ids, packages, folders (`zombiecraft/`), env vars and system properties are unchanged on purpose (saves, caches, config keep working).
HUD now follows the BO2 Zombies layout: chalkmarks tallies bottom left, points + cyan +N popup + ammo bottom right over the BO2 blood splat (hud_dpad_blood), grenade icon, BO2 low-health overlay, small crosshair. Only Green Run is selectable; it starts the Diner and shows the diner postcard.
Real BO2 images worth reusing later: chalkmarks_0-5, hud_dpad_blood, overlay_low_health, grenadeicon_32, damage_feedback (hit marker), hit_direction_zm, specialty_*_zombies (common_zm / zm_transit).

## Menu audio + HUD banner (2026-10-06)
Menu music = BO2 `mus_zombie_splash_screen` (restarted when it ends, stopped when a game starts). UI sounds: uin_main_nav (hover), uin_lobby_join, uin_cmn_backout, uin_main_pause, zmb_ui_globe_spin_start / map_level_switch / map_level_select, cac_cmn_deny (locked map). In game: mus_zombie_game_over, chr_heart_beat_ingame at low health. 11 new cues added via tools/audio_seed.json + derive_audio.py. CueMixer now plays menu sounds with no world and while paused.
"Get ready" and "Next round" banners removed; the controls are listed on the pause menu instead.

## Sound pass 2 (2026-10-06)
Menu music is now the real BO2 front-end theme (`mus_fe_main` = mus_bo2_theme_ambient, from spl_frontend.all.sabs; loops). Vanilla Minecraft music is stopped every tick; the local player and all zombies are silent (vanilla step/hurt/ambient sounds), so only BO2 cues play.
Added: player footsteps (fly_step_walk/run_plr_ceramic), zombie footsteps (fly_step_*_npc_ceramic), diner ambience (amb_diner_l/r + light hum loops, random creaks/rustles near players). Quieter: child laugh, zombie groans (also less frequent).
Alias map note: dev alias_map_transit.json now also reads spl_frontend (out5) and uses the full install (D:/New folder (2)), since D:/SteamLibrary lacks the spl_* banks.

Loading screen music: `mus_load_zm_transit` (BO2's Tranzit loading track, lovesong_for_the_damned, from zmb_code_post_gfx.all.sabs) plays while the BO2 loading screen is shown and stops when it ends.

## Power, perks, power-ups (2026-10-06)
Sheet `map_machines.json` (power switch + 4 perk machines; spots chosen from the map checker's voxel model, preflight checks them). `Machines`: power starts off; perk machines and Pack-a-Punch refuse until it is on. Perks per the BO2 scripts: Juggernog 2500 (health x2.5), Speed Cola 3000 (reload x0.5), Double Tap 2000 (fire time x0.75), Quick Revive 500 (solo self-revive, 3 buys per game). `PowerUps`: drop queue from team earned points (500+2000, x1.14 each), max 4 per round, 30 s lifetime; Max Ammo, Insta-Kill (30 s), Double Points (30 s), Nuke (+400), Carpenter (+200, repairs all). 27 new BO2 cues (power on, machine hum, jingles, stings, drink, power-up spawn/grab/loops, nuke, carpenter).
Self-test (bench) gained perk/power/power-up steps: all pass. Older steps (wall-buy, Pack-a-Punch, window-repair points) fail because another session replaced the diner with the Bus Depot map; the Pack-a-Punch step now times out instead of hanging.
Mystery Box mesh: BO2 box front is local +y (scripts put its trigger there), scaled to 1.6 blocks wide so it no longer pokes through walls. Fixed `item_display` transformation key (`translation`).

## Mystery Box, animated (2026-10-06)
The box is now an entity (`ZcBox`, renderer `ZcBoxRenderer`) drawn from the local p6_anim_zm_magic_box model, skinned with the real lid animations (o_zombie_magic_box_open/close/leave/arrive; bone j_hinge), at about real size (2.3 blocks wide, 1.6 where the spot is narrow), raised on cinder-block feet, with a full-bright pass for the glowing question marks and a light block above it. Open/close follow the BoxSystem states; on a teddy the box plays its leave animation before it moves. Front of the model is local +y (per the BO2 scripts' trigger offset). Not yet checked by eye: orientation, size against the map's walls, glow threshold.

Box glow, found by looking at it in game: the question marks are separate geometry on an additive "objective" material with a black texture (colour from constants), so they were first drawn black and then culled by the additive render type. Now drawn with a non-culling emissive type in pulsing gold, plus a soft halo over the lid and a glow on the floor. Dev: `-Dzombiecraft.debugBox=true` (with autoplay) stands the player in front of the box.

## Texture pack, custom blocks, atmosphere (2026-10-07)
Built so new maps are data, not code:
- `sheets/textures.json` (made by `tools/gen_textures.py`): one row per block texture -> one BO2 image (centre-cropped square, optional flatten/tint, `checker:a+b` tiles two images). `bo2/TexturePack` builds the resource pack `<game>/resourcepacks/BlockOps2` on the player's PC inside `Bo2Assets.convert` (zones: zm_transit, patch zones, common_zm; all Tranzit maps share the 1872 images of zm_transit - the gump zones hold no separate images). `PackSelector` enables it and reloads once when rebuilt. Size: -Dzombiecraft.packSize (default 128).
- Adding a map: add its rows (texture ids unique to that map if they differ, e.g. `zombiecraft:diner_floor`), its blocks (`tools/gen_blocks.py` + `block/ModBlocks`), an atmosphere row. Pick images with the catalogue idea in gen_textures.py (list `bo2-dump/out4/zm_transit/images`).
- Custom blocks (`block/ModBlocks`, resources from `tools/gen_blocks.py`) ship neutral placeholder textures so the game works without BO2: cinder_block, concrete_wall, metal_panel, asphalt, depot_tile, wood_floor, ground, grass, glass_brick, neon, barricade_board (3 planks, solid cell, axis x/z). `gen_depot.py` now builds with them.
- `sheets/atmosphere.json`: fog colour/start/end, sky, cloud, stars, time of day per map ("*" default). Client mixins `ZcFogMixin` (FogRenderer) and `ZcSkyMixin` (ClientLevel) apply it only while a match runs (`client/Atmosphere`); `Game.start` sets the clock from it.
- The pack makes the grass/foliage colour maps neutral and drops lava animation (static texture).
- Bench 41/41 with all of this. Dev: -PdevProps=name[=value],... opens `run/shot` with -Dzombiecraft.<name>; debugOptions=title|root|settings|controls|quit opens a menu directly.

## Guns: BO2 first-person rig, glowing displays, more guns (2026-10-07)
- `client/render/ViewModel`: the real BO2 view rig. Hands model `c_zom_<character>_viewhands` (-Dzombiecraft.character, default oldman) and the gun's `t6_wpn_*_view` are both posed by the gun's own clips `viewmodel_<anim>_{idle,fire,reload,reload_empty,pullout,first_raise}` (prefix in `bo2_models.anim`; extracted by Bo2Assets). The gun skeleton (root j_gun) rides the hands' `tag_weapon` (`Pose.buildOn`). Missing clips fall back (M14 holds first_raise; RPD/Judge use whatever reload exists). Per-character sleeve images are not in the install's data, so sleeves are a plain cloth texture and the skin uses BO2's `c_gen_arm_*` map. Muzzle flash is drawn at `tag_flash`. Old procedural recoil is skipped when the rig is available.
- Displays: `ZcProp` kinds WALLGUN / BOXGUN, plus the gun inside Pack-a-Punch (`ZcPropRenderer`), drawn as the world model with two translucent blue halo layers (`Bo2Mesh.draw(..., halo)`). Wall guns keep an invisible item frame for aiming; with BO2 models the frame is empty.
- New guns from BO2's own weapon files: FAL, Saiga 12, RPD, Executioner (+ PaP rows). Upgraded-gun NAMES for these are placeholders ("<gun> PaP"): the real names are localised strings that are not in the extracted data. Not added: B23R and Five-seven (no HUD icon / viewmodel clips in the install), DSR 50 (no icon).
- Cache/pack rebuild now also keys on the bo2_models sheet (`Bo2Assets.sheetsHash`).
- Dev switches (use `-PdevProps=a,b`): debugGun (every gun idle + reload screenshots), debugPap, debugDrink, debugPowerups, debugOptions=..., debugVm.
- Known: `bench` box-pay can flake on a late teddy; `viewmodel_m14_idle` does not exist in BO2.

## Zombies, combat feel (workstream C, first pass, 2026-10-07)
- `game/Blood`: dark red dust spray/mist on every zombie hit, bigger burst (more on headshots) on kills; replaces the vanilla crit stars.
- `ZcZombie.tickDeath`: bodies stay on the floor for 8 s and are discarded without vanilla's white poof cloud (the white clumps).
- Hit direction: `Payloads.HitDirection` sent when a zombie hurts a player; `ZcHud` draws a red arc on a ring round the crosshair that fades in 2 s. A short warm light bloom is drawn when firing.
- Existing and kept: hit marker (white/gold headshot/red kill), red low-health edge, `tag_flash` muzzle flash.
- Not done / not verified by eye: zombie close-up textures, eyes, torn clothes, climb/tear animation review, spawn presentation, crawlers, counts. Compiles; not run in game.

## Gameplay rules (workstream A, first pass, 2026-10-07)
- Bug "cannot shoot after drinking a perk": `Machines.use` set `fireCooldown = game.tick + 50`, but `fireCooldown` is in seconds (it drops 0.05 per tick), so the gun was locked for an hour. Now `DRINK_TICKS * 0.05`. Bench step `perk-can-fire-after-drink`.
- Start ammo: BO2 `givestartammo` uses the weapon file's `startAmmo`, not `maxAmmo` (M1911: clip 8, startAmmo 4 mags = 32, max 80). New column `weapons.startReserve`; every freshly given non-PaP gun starts with it (`Gun` ctor), refills and Max Ammo still use `reserve`. Only the M1911 differs among current guns (FAL/Saiga/RPD/Executioner have no dump, so start = max). Bench `start-pistol-8/32`, `start-ammo`.
- Ammo for a Pack-a-Punched gun at its wall-buy costs a flat 4500 (`systems.pap_ammo_cost`, from `_zm_weapons.gsc`); normal guns stay half the wall price.
- Checked against scripts and already right: kill 50 / head +50 / neck +20 / torso +10 / melee +80, hit 10, box 950, PaP 5000, board points cap.
- Bench: the zombie-through-window step used old diner coordinates (player now parked at the map spawn); full bench green on the Bus Depot (41 pass, 0 fail).
- Not yet done in A: rounds 1-10 hand playthrough, stuck-zombie teleport/respawn, bounds/exploit checks, Max Ammo/nuke/carpenter numbers vs scripts, drop rates, down/revive numbers, `-PpapBench`.

## Map look, stream B (2026-10-07)
`tools/gen_depot.py` (re-run, don't hand-edit sheets): indoor floors are concrete (no asphalt road lines inside); floating lamp cubes replaced by flush neon strips with dim `light[level=9]` blocks under them; set dressing (ticket board, chairs as stairs, yellow-band pillars, bins, rubble, poster); a town outside the windows (street-front buildings with dark windows closing all four sides, gas-station canopy, wrecked cars, bare trees, bus-stop line). Player spawn now faces north (windows, ticket board, a wall gun). `atmosphere.json` bus_depot: dusk haze (timeOfDay 11800, orange-brown fog 4..48) so the outside is lit and the inside stays dark from its own lights. Preflight 0 errors.
Dev: `-PdevProps=debugTour` visits 7 viewpoints and writes `zc-tour_N.png`; `-PdevDir=run/shot-x` gives a private run folder (several chats can run at once).
Known gaps (B): no readable text signs (BUS DEPOT, Employees only, Fire regulations: signs need NBT text), no wall clock/ticket-counter detail/lockers upgrade, no glass-block wall with a cracked hole, tour shows only blocky approximations, neon strips are cyan placeholder texture, no flicker.

## Stream D (first-person rig), first pass
- Viewmodel raised (`zombiecraft.vmY`, default 0.06) so both hands show; before, the right hand sat below the screen on long guns (looked like a "missing hand").
- Sleeves are now dark cloth and all characters use BO2's black arm map. The skin still reads brownish in debugGun shots (the `_skin` surface may not be the gloves); needs a closer look.
- Not done: black patches check, per-gun clip audit (debugGun shots in run/shot/screenshots), sprint/walk bob, ADS, grenade/knife clips.
- C, tested in game (dev switch `-PdevProps=debugZombies`, three free zombies, one hit then killed; screenshots `zc-zombies_*`): zombies read well (real BO2 body/head, torn dirty clothes); blood spray works; body stays on the floor after the kill. Dust particles are square, so blood uses small sizes (0.55-1.1) to avoid big pixel clumps.
- Test tip: another chat holding `run/shot` locks the world; copy it (`cp -r run/shot run/ctest`, delete `session.lock`) and pass `-PdevDir=run/ctest`.

## Map objects, doors, barricades, textures (2026-10-07, stream B part 2)
- **Window clip**: a torn barricade cell becomes `zombiecraft:window_clip` instead of air: invisible, solid only for players (`EntityCollisionContext`), no outline so shots and the repair prompt pass; zombies path and walk through it. Players can no longer climb through an open window (dev check in `debugTour` logs it).
- **Doors**: each buyable door is two block-display leaves hinged at the doorway sides, swinging 90 degrees (30 ticks, display interpolation) into the room they open, over an invisible solid `door_clip` that is removed on purchase. Free doors (rooms already open) swing too. Leaf block comes from the `block` column of `map_doors.json` (`door_metal` / `door_wood`).
- **Decor blocks** (22, `tools/gen_decor.py`, shapes in generated `decor.json`, read by `ModBlocks`): bench, waiting chair, trash can, locker, crate, barrel, street lamp + head, bus stop sign, 5 lettered wall signs (BUS, DEPOT, Employees Only, Fire Regulations, Restrooms), poster, wall clock, round yellow-banded pillar, ticket counter, ticket map kiosk, vending machine, gas pump, concrete barrier. All face a direction (`[facing=...]`, front = the way it looks). Sign lettering is drawn by the generator (our own art, no BO2 needed).
- **New cubes**: light_panel (fluorescent, glows), plaster_wall, concrete_floor, yellow_trim, green_panel, red_brick, door_metal, door_wood, road_bus / road_stop (BO2 road paint decals).
- **Textures** (`gen_textures.py`, 67 rows): BO2 images for all the above plus vanilla blocks the map uses (logs, gravel, cobble, coloured concrete). Chosen from a contact sheet of the zm_transit image set. The generator also owns `atmosphere.json` (dusk haze values moved in).
- **Layout** (`gen_depot.py`): plaster interior walls, concrete floors, yellow door frames, neon line around the outside, kiosk/counter/signs/chairs/pillars/lockers/crates in the rooms, town facades varied per building, street lamps, gas pumps, bus-stop bench, BUS and STOP painted on the road. Preflight 0 errors.
- Reference frames (2026-10-06 clip, t=70..190 s) show what is still missing: big diamond-mesh windows, tan rubble drifts on the floor, a free-standing glowing map kiosk with an arched BUS DEPOT sign, white cylindrical bins (done), grey double doors with inset panels (done). Exact BO2 geometry is not extracted, the layout is still the entity-based approximation.

## BO2 sounds fixed, BO2 scenery models (2026-10-07)
- **Why every BO2 sound was missing**: `Bo2Locator` took the first install it found (`D:\SteamLibrary`), a partial copy without `spl_frontend.all.sabs`; the extractor threw and the whole audio system fell back to Minecraft sounds (no music, no cues). Now the locator scores every install by how many of the needed banks it has and takes the best, and the extractor skips a missing bank (its cues fall back individually) instead of failing.
- **More cues** (`tools/audio_seed.json` -> `derive_audio.py`, 183 cues / 294 files): depot ambience bed (`amb_depot_l/r`, `amb_depot_map_light`) replaces the diner one, `mus_zombie_splash_screen` at the match start, `mus_raygun_stinger` when the box offers a Ray Gun, `chr_pain_exhale` on zombie hits, `uin_lobby_leave` when leaving a match, plus unused menu/UI cues in the sheet (cac_*, globe/zoom, depot door sounds) ready to wire.
- **Scenery models ripped from the install**: `sheets/map_props.json` (written by `gen_depot.py`'s `prop()`) places any BO2 xmodel (`ZcProp.SCENERY`, model name in PAP_WEAPON, scale in PAP_DEPTH, drawn by `ZcPropRenderer.renderScenery`). The Unlinker now also reads the map zones (`zm_transit_gump_*`: bus station, town, diner, farm, ...), which hold the depot's own props. In the depot: kiosk, plastic waiting benches, trash cans, lockers, clock, restroom sign, fluorescent fixtures, payphone, fountain, magazine rack, sofa, work bench, AC units, stanchions; outside: street lights, burnt trees, wrecked microbuses, dumpster, outhouse, power poles, water tower, planters, tires, pallets. When the model cache is ready the decor blocks they replace (`hide` cells) become invisible barriers or air; without BO2 the blocks stay as the fallback.
- `Bo2Assets.VERSION` 8 (cache rebuilds once). Dev dump `C:/Users/alexi/bo2-dump/out8` (all gump zones) is in `run/config/zombiecraft.properties`.
- Known gaps: orientation of each model is a guess verified only for the kiosk, benches, lights and street lamp; the blocky bus is still blocks (BO2 `veh_t6_civ_bus_zombie` is in the dump, not placed); decor wiring for most new menu sounds not done.

## Audit pass: lighting, drink animation, textures (2026-10-07)
- `debugTour` now stands in front of every machine, box, Pack-a-Punch, wall gun, door and window (23 views, names printed as `[tour]`); every prompt resolved, barricades hold (clip test), doors swing, models sit where the sheets say.
- **Machines were black**: their entity stands with its back in the wall (light 0). `ZcPropRenderer` now takes the light from the air in front (`State.lit`), machines get a dim light block when unpowered (9) and a bright one when on (12). Light blocks under the ceiling panels are level 13, with 7 more panels (wing, back room, wall-gun spots).
- **Perk drink now uses BO2's own hands**: the bottle models got `anim=zombie_perksacola` and the rig plays `viewmodel_zombie_perksacola_drink` (hands + bottle on tag_weapon); the gun lowers out of view meanwhile. Falls back to the old floating bottle without the clips. Cache `VERSION` 9 (adds the drink/lower clips).
- **Gun "missed spots"**: surfaces with no texture are skipped (now logged once per model: only the hands lack their sleeve image, handled). The FAL's modelled-in M203 launcher (`..._attach_gl_*`, a big black slab in view) is no longer drawn.
- **Textures**: BUS/STOP road paint removed (the square crop cut the letters), white concrete no longer an image atlas.
- Not fixed: `start-pistol-8/32` bench step fails (m1911 reserve 24 vs 32), from another stream; HUD blood-streak drawn at screen left in every shot is another stream's overlay.

## BO2 map flow: planet -> top-down Tranzit -> match lobby (2026-10-07)
- Frames: reference screenshots (planet, top-down map, focused place with its photo + mode list, "BUS DEPOT / SURVIVAL" lobby with "Game starting in 3").
- `menu/Bo2Locations`: `Select` shows `menu_zm_map_transit_large` cropped/stretched like BO2 with the five places (depot, town, power, diner, farm: hotspot fractions from the photos). Nothing is preselected; hovering counts only after the mouse moves. Focusing a place blurs the rest (offset copies + dim), shows its `menu_zm_map_transit_blit_*` photo and mode list (Bus Depot: TRANZIT locked, SURVIVAL). `Match` is the lobby: START MATCH (3 s countdown), ONLINE GAME: OFF/ON (hosting moved here from the Zombies menu; shows 4 Max), MAP; postcard with the place's loading picture, which the loading screen then also uses.
- Only the Bus Depot is playable; the rest say COMING SOON. Playing it uses the converted Tranzit depot when extracted (`Sheets.useMap` is called from the lobby), the built-in depot otherwise. `MapBuilder` now clears a 121x101x30 box above the ground before building so switching maps in one world leaves nothing behind.
- `UiAssets.VERSION` 7 (adds the five blit images): the menu art cache rebuilds once from the player's BO2 install.
- Dev: `-PdevProps=debugOptions=locations|match,debugSel=<0..4|-1>` opens the screen and saves `screenshots/zc-<name>.png` after 4 s.
- Not tested: actually starting a match from the menu (only the screens and the earlier dev-tour run of the map), hosting from the new toggle, mouse feel.

## Exact BO2 Bus Depot: data and tooling (2026-10-07, geometry still needed)
- Findings from the BO2 entity file (`assets_src/depot/ents.json`, scripts in `Downloads/t6-scripts-main`): the depot has 7 barricades (depot_baricade1..7, facing from the node_negotiation pair), ONE Mystery Box (48.2, 48.9 in depot blocks), 4 wall guns (Olympia, M14, Remington 870 MCS, MP5K), the 750 busstop door and two electric doors that need the turbine's local power. Survival (zstandard_transit) has NO perks and NO Pack-a-Punch in the depot (the in-game text says so; only classic has Quick Revive at 42.2, 47.7). Our earlier depot sheets, and the Tranzit Reimagined cut, only approximate this.
- Zones (zm_transit.gsc): zone_pri = main hall (start 42.2, 33.1), zone_station_ext = behind the 750 door (box, barricades 3/6/7), zone_pri2 = side rooms behind the electric doors.
- `tools/gen_bo2_depot.py` writes `sheets/maps/bo2_depot/*` from those entities (7 windows with zones, 14 spawns, 4 wall guns, box, 3 doors, player start, invisible barrier ring, no perks/PaP). Open items: the diagonal barricade 4 is snapped to a wall, door widths are guessed (the brushes have no extent in the entity file), the electric doors are priced 99999 (unopenable), Remington 870 MCS is not in weapons.json (ak74u stands in).
- The walls/floor/static props are NOT in the entity file (BSP is not dumpable with OAT). `tools/obj_to_blocks.py <map export.obj> --check` converts a Greyhound/Cordycep-style OBJ export into `maps_local/bo2_depot.json.gz` and checks it against the exact positions. Tested only on a synthetic room. The lobby uses `bo2_depot` when that file exists, else the Tranzit Reimagined depot, else the built-in one.

## Exact Survival Bus Depot: what the BO2 scripts say, and what is now in (2026-10-07)
- Source of truth for Survival: `Tranzit/maps/mp/zm_transit_standard_station.gsc` + `_zm_gametype::setup_standard_objects("station")`. Power is on from the start; every `local_electric_door` is `trigger_off()`ed (the two side doors can never be used: cost -1, `Doors.at` skips them); the Quick Revive machine is replaced by a `p_glo_tools_chest_tall` (same origin/angles); the `game_mode_object` "station" structs spawn the dead cars, overturned truck cabs and rocks that wall the area in (exact origin + pitch/yaw/roll); a Survival-only collision model `zm_collision_transit_busdepot_survival` sits at bx 37.6, bz 53.9 (extent unknown without the BSP).
- Door sizes from the door leaf models in the entity file: 60 units wide each, two leaves = 3 blocks, 100 units = 2.5 blocks (3 cells). 750 door x 37..40, side doors z 39..42 (x=30) and x 25..28 (z=48).
- Floor reference: BO2 z = -1.4 blocks (-56 units): perk struct, box room and every wreck sit there, barricade origins (the sill) at -0.6 (0.9 above). `gen_bo2_depot.FLOOR_Z` and `obj_to_blocks --floor-z -56` agree; recheck against the export.
- Props: `MapProp` gained pitch, roll, exact (model placed by its own origin, BO2 yaw/pitch/roll) and fallback (stand-in block; only those cells become barriers when the model is drawn). `ZcProp` has ROLL/EXACT synced data. Collision cells are voxelised from the real xmodel vertices (`xmodel_export`), stand-in blocks gray concrete / cobblestone / crate. Verified in game: wrecks at their positions, truck cabs on their sides.
- A map with no Pack-a-Punch (or no power switch) now runs: `Game` skips `PapSystem`, `Machines.power` starts true when there is no power row.
- Remington 870 MCS added (weapons.json 870mcs + 870mcs_pap from `gen_weapons.py`, wall cost 1500 from the wiki, 6/48, 310 dmg x4 pellets, full shell-by-shell reload 5.15 s = 1.0 + 6 x 0.55 + 0.85), box pool, bo2_models row, HUD icon list, six sound cues derived from the install (appended, existing cues untouched; derive_audio.py itself fails on this PC for a missing spl_frontend bank, pre-existing). `ViewModel` plays reload_in / reload_loop (once per shell) / reload_out; `Bo2Assets.GUN_CLIPS` carries them. The viewmodel clips come from the player's install at first run: not seen in game on this PC (dev model cache lacks the gump models).
- Converter: tested on real BO2 geometry (Bus Station model OBJs from the dump, real material names, 10.5k triangles, 0.5 s): positions, Y-up conversion, materials and the `--check` all work. Still untested on a full map export.
- Open: diagonal barricade 4 (snapped to a wall), the real room shapes and wall thickness (need the export), BO2 door models instead of block leaves, `zm_collision_transit_busdepot_survival` extent, a missing p_glo_tools_chest_tall / p6_zm_kiosk in this PC's dev model cache (the dump config omits those zones' output; players' installs extract them at first run).

## Online lobby (2026-10-07)
- With ONLINE GAME: ON, START MATCH now opens the world to LAN in a lobby (`PHASE_LOBBY`) instead of starting at once: `Bo2Locations.Lobby` shows the players present (4 max, a 5th is refused), INVITE FRIENDS (the playit.gg steps) and START MATCH for the host only (`Payloads.StartMatch` -> 3 s "Game starting in N" for everyone). Joiners land in the same lobby screen; joining mid-match still works. `Game.lobbyNext` is how the client tells the integrated server. Dev: `-PdevProps=debugLobby`.
- Not tested: a second real client joining the lobby.

## Bus Depot surroundings (Tranzit Reimagined scenery)
- The owner of Tranzit Reimagined confirmed sharing and republishing, so `maps_local/depot_scenery.json.gz` (88 KB) is committed. `tools/extract_tranzit.py depot_wide` cuts a 320x340 area round the original depot; `tools/make_depot_scenery.py` crops it to 232x217 (roads, forest, hills, ~80 blocks of margin).
- New map op `scenery` (like `import`, but air runs are skipped, so a mostly-sky file is cheap). `bo2_depot` pastes it at order -2 (sheet origin -120,-112, so the original lot sits under the play region), empties the play region (order -1), then the BO2 geometry import (0), props (50) and the barrier ring (90) go on top.
- Checked in game with stand-in geometry: beyond the barrier ring there is terrain, grass, flowers and trees out into the fog, no void edge. Dev tour got four "outside" stops (ring sides, 12 up, looking away).
- Still open for the depot: the real BO2 wall geometry (needs the map export), diagonal barricade 4, door models. Town is not started (depot first).

## Join codes through a relay (2026-10-07)
- `tools/relay/relay.py` (stdlib Python, you host it; default port 25565): the host's game registers (`HOST`) and gets a 6-char code; a joiner sends `JOIN <code>`, the relay asks the host for a data connection (`CONN id` -> `DATA id`) and pipes the two. `test_relay.py` passes.
- Mod: `menu/Relay` (host registration when the world opens to LAN; loopback forwarder for joiners, so vanilla's connect screen just goes to 127.0.0.1). Address from `relay=host:port` in config/zombiecraft.properties (or -Dzombiecraft.relay). The lobby shows JOIN CODE; Join Game accepts a 6-character code or an address; INVITE FRIENDS has COPY CODE.
- Verified with two real clients through a local relay: the second one joined the host's lobby by code and showed in its player list. Not done: a relay run on a public machine, a default relay address, starting the match with a joiner and playing it.

## Join codes without a relay: the code is the address (2026-10-07)
- `Relay.expose` (called when the world opens to LAN): with `relay=` set it still uses the relay; otherwise the host's router is asked to forward the port (`menu/Upnp`: SSDP + SOAP, no libraries) and the code is the public IPv4 + port in 10 base-32 characters (`XXXXX-XXXXX`, 6 bytes). `publicAddress=host:port` in config/zombiecraft.properties replaces the router step (a playit.gg tunnel's address, a forwarded port). The joiner types the code, `Relay.resolveJoin` decodes it and vanilla's connect screen goes straight to the host. The lobby shows the code, or why there is none (`Relay.status`).
- On this PC (checked with scripts): Ethernet behind 192.168.1.1, public IP 174.113.24.34 (not CGNAT), but the router does NOT answer UPnP (TVs and Rokus do), so the automatic route cannot work here; `publicAddress=147.185.221.29:53506` (tunnel ninth-kinase.tun.ply.gg -> 127.0.0.1:25565) is set in mod/run/config. The game made the code SO452-HORAI for it, and it decodes back to that address.
- The friend's earlier tries reached playit but failed with "timed out connecting to 127.0.0.1:25565": at that time a leftover test game of mine held 25565, and a game that cannot get 25565 falls back to another port that the tunnel does not point to. Keep 25565 free, and look for `opened to LAN on port 25565` in the log.
- A status ping to the tunnel address from this same PC is reset by playit's edge (no agent log entry), so a join through the tunnel could not be tested from here; a ping to 127.0.0.1:25565 gets the game's status. Needs a real test from another network.

## Town, Farm and Power Station on the Tranzit Reimagined cuts
- **Save checked**: no datapacks or labels in it (the 87 item frames are shop decoration); the map is the blocks. `tools/world_overview.py` draws the whole save (1 pixel per block, 100-block grid) to find places: depot (NE), town (centre), farm (south), diner (west of the road, small), the underground circular reactor SE of the loop = the Power Station (matches the Brady/Tranzit overview map with its round building). The old labels were guesses: `power` and `diner` were swapped, the farm cut was too small. `tools/tranzit_regions.json` has the new boxes.
- **Cutter**: `extract_tranzit.py` now lifts sunken floors/low ground to y=0 (the game stands everything on the grass layer; the Power Station warehouse floor is one below). Town 128x112, Farm 221x151, Power 151x151, all re-cut.
- **BO2 layout on the Reimagined blocks**: the Reimagined town and farm are 1 block per 40 BO2 units, so BO2's entity positions land on the right buildings with a fixed offset. `tools/fit_loc.py` finds it (scores barricades and wall guns on walls and player starts on open floor: Town (174,146), Farm (329,243)); `overlay_loc.py` draws the entities on the cut to check it.
- **`tools/gen_loc.py town|farm|power`** writes `sheets/maps/tranzit_<loc>/`: every BO2 barricade becomes the nearest valid 3-wide wall opening (facing from the node_negotiation pair), wall guns/perks/boxes snap to the nearest wall face (their BO2 facing preferred), the Pack-a-Punch (Town) to a free 3x3 patch against a wall, player start at the centroid of BO2's spawns; everything is checked against the blocks and reachable from the start. Survival scripts (standard_town/farm.gsc) turn power on and open every door, so there are no doors and one room. Left out because this game has no such thing: Marathon, Tombstone, Galvaknuckles. Town: 16 windows, M14/MP5K/Olympia-slot Rottweil, 2 boxes, Jug/Revive/Speed/Double Tap, PaP. Farm: 8 windows, Rottweil + MP5K, 1 box, 4 perks, no PaP (BO2's Farm has none). Power Station has no Survival data in BO2, so the building is found in the blocks (warehouse) and windows/box/perks are spread (AK74u wall gun from the Tranzit entities).
- **Menu**: Town/Farm/Power Station become playable (no COMING SOON) when their `maps_local/<id>.json.gz` exists; they are committed (owner allowed sharing).
- **No vanilla-looking blocks**: `tools/texture_gaps.py` lists every vanilla texture the maps use without a BO2 image (203 before); `tools/texture_auto.py` assigns each one a BO2 image from the map zone by category (grass, leaves, bark, planks, stone, brick, ore, glass, cloth, metal ...) and colour (a darkening tint matches the colour; `tools/texture_preview.py` draws vanilla vs result), written to `tools/texture_auto.json` and appended by `gen_textures.py` (rows marked "auto"). New `mask` option in `textures.json`: cut-out blocks (leaves, glass, plants, doors, chains) keep Minecraft's shape, the pixels come from BO2. `texture_gaps.py` now reports 0 gaps. Gump zones hold no images, so every install has the same pool (about 140 material images): colour variety comes from tints.
- Dev tour: stop 0 is the sheet's player start now; four "outside" stops exist.

## Random join codes, playit PROXY protocol, verified through the tunnel (2026-10-07)
- **Why joins through playit never worked**: the tunnel prepends a PROXY protocol header (`\r\n\r\n\0\r\nQUIT\n...`) to every connection and vanilla Minecraft drops it. `client/net/ProxyProtocolDecoder` (installed first in the integrated server's pipeline by `ZcServerConnectionMixin`) swallows a v1/v2 header and passes ordinary connections through. playit also routes by the HOSTNAME in the handshake: connecting with the bare IP is reset by the edge (the earlier IP-encoded codes could never work through it). Both found by putting a logging fake server on 25565 behind the tunnel.
- **Codes**: each lobby gets a random 6-character code (`Relay.randomCode`, no 0/O/1/I). Friends' games connect to `Relay.joinHost()` (`host=name:port` in config/zombiecraft.properties, default `ninth-kinase.tun.ply.gg:53506`, the owner's tunnel) and send `Payloads.JoinCode`; `Game` kicks anyone who is not the host and has not sent the current code within 5 s ("Join code missing or wrong."). An old code stops working with the lobby. UPnP and the address-in-the-code experiments were removed (the router here has UPnP off, and playit needs a hostname anyway). `relay=` (tools/relay) still works and then hands out its own codes.
- Verified on this PC with two real clients: the right code joins through the tunnel (from outside, mcstatus.io reports the server online, 1.21.4), a wrong code is kicked in ~1 s. Playit's service had to be restarted once (its control session was stuck after Cloudflare 502s).
- Keep port 25565 free: the tunnel points at 127.0.0.1:25565 and the lobby warns when the game had to take another port. Everyone playing from the repo connects to the owner's tunnel by default; hosting from another PC needs `host=` set on every player's config.

- Friend got "Failed to log in: Invalid session": the hosted world required Mojang's account check, which dev/offline accounts (play.bat runs the dev client) can never pass; only the debugHost test switch had it off. Hosting online now always sets setUsesAuthentication(false); the join code is the gate. Verified without debugHost: a second dev-account client joins through the tunnel. A friend on a real vanilla launcher account who still sees it needs to restart their launcher.

- The join code is picked when ONLINE GAME is switched on in the match screen (Relay.reserve), shown there as JOIN CODE, and the lobby reuses the same code, so it can be read before starting. Dev: -PdevProps=debugOptions=match,debugSel=0,debugOnline opens that screen with online on. Confirmed working by the owner.

- ONLINE GAME on the match screen now opens the lobby at once (world opens to LAN, Lobby screen with the code), so friends can join as soon as it is switched on; before, the world only opened at START MATCH and a friend joining earlier got playit "timed out connecting to 127.0.0.1:25565". Verified with debugToggleOnline (presses the button after 6 s): second client joined by code through the tunnel and showed in the list.

## Online lobby flow polish (2026-10-07)
- ONLINE GAME no longer shows the loading screen or stops the menu music: Bo2Menus.quietLoad makes the world-loading screens draw the lobby backdrop (Bo2Locations.quietLoading, Opening the lobby...) and keeps mus_fe_main playing; joiners (Join Game) get the same. When the host presses START MATCH, every client at once stops the menu music and shows the BO2 loading picture with its music for 5 s (Bo2Locations.Starting), the server cue mus_zombie_splash_screen plays as before.
- INVITE FRIENDS is now a BO2 screen on the lobby backdrop: big JOIN CODE, three plain steps, COPY CODE, COPY INVITE MESSAGE (a ready sentence with the code), BACK; the playit instructions are gone. Dev: -PdevProps=debugOptions=match,debugSel=0,debugToggleOnline walks press -> loading -> lobby -> invite -> START MATCH -> countdown -> start screen and saves zc-flow-*.png. Checked by screenshots; the music itself was not listened to.

## Stable release check (2026-10-08)
- Commit 6b24475 verified on a clean worktree: `gradlew build` OK, `tools/preflight.py` 0 errors, scripted bench (`-Pbench`) 49 passed / 0 failed (rounds, wall buys, box, Pack-a-Punch, window entry, perks, power-ups, downed/bleedout, restart). Tagged `v1.0-stable`.
- Run the bench on a quiet checkout: a window quit/focus loss or another game holding the integrated server derails later steps (seen once, a false window-entry / bench-crash).
- Not covered by the bench: a real second-player join through the tunnel and a match with a joiner, audio by ear, BO2 viewmodel clips on a fresh install.

## BO2 fit pass: menus, wall guns, prompts, game over (2026-10-08)
- Method: `-PdevProps=debugOptions=tour` (new) opens every menu screen in turn and saves `zc-tour-NN-name.png`; `debugTour,debugPower` does the same for the match. Compared against `BO2_Screeshotrefrences` by measuring text height against screen height: BO2's menu text is about 0.4-0.7x of what we drew, so `Bo2Menus.H()` lost its 1.3 factor and the item/title scales were cut (items 1.2, titles 1.5-1.8).
- Menus: no orange boxes around selected rows (BO2 only colours the text orange); Options is centred (open entry large and orange); Settings/Controls get the `< GRAPHICS SOUND ... >` tab bar, darker backdrop, no scroll counter on top (it overlapped the CHAT tab); pause menu is `ZOMBIES / RESUME GAME / OPTIONS / END GAME` (the controls list lives in Options > Controls); the quit box asks `Leave Game?` with Yes / Cancel; the lobby planet sits behind the menu again; the title has the logo and menu at the left edge and fades up from black.
- `UiFont` measured text without the fractional-metrics hint it draws with, so the last letter of some lines was cut ("You Survived 2 Round"); fixed with a wider texture and the same hint.
- Wall guns are BO2's glowing white chalk drawing of the gun (`UiArt.chalk`: the weapon icon made pure white, drawn twice as an emissive quad on the wall); the floating blue 3D gun only appears when the icon art is missing.
- Prompts: `Hold F to buy X [Cost: N]`, `Hold F to buy ammo for X [Cost: N]`, `Hold F to rebuild Barrier`, key in yellow. Mystery Box keeps BO2's `Hold F for Random Weapon`.
- Game Over: red tint over the world and smaller text (BO2 look), scoreboard unchanged.
- Built-in depot only: `cinder_block` now uses the plaster image (it was a near-black brick). The Tranzit Reimagined depot is mostly stone/ore stand-ins; measured mean brightness of the in-game screenshots (26-38 of 255) matches the BO2 interior frames (26-38), so no global brightening.
- Known gaps: real BO2 wall geometry (needs the BSP export), BO2 door models, the glass-block/checker detail of the hall, the cracked-glass overlay on menu pages (not found in the dump), the lobby menu entries SERVER BROWSER / CUSTOM GAMES / THEATER / LEADERBOARDS / MODS (no such features), Controls > Look has only sensitivity and invert.
- Bench: 46 pass, 0 fail (run on this branch before the prompt wording change; the bench does not check prompt text).

## Multiplayer: every player on the scoreboard, BO2 characters instead of Steve (2026-10-08)
- **Scoreboard**: the server sends a `Roster` payload (name, points, kills, downs, revives, headshots, stance per player) to everyone every 5 ticks (`Game.syncRoster`); `ZcHud.scoreboard` draws one row per player (own row outlined orange and live, dead players greyed).
- **Bodies**: `ZcLivingEntityRendererMixin` hands every `PlayerRenderState` to `render/ZcPlayerBody` during a match; it draws the real `c_zom_player_<oldman|engineer|farmgirl|reporter>_fb` (character picked by roster order) with BO2's third-person clips (`pb_*` from common_zm, listed in `Bo2Assets.PLAYER_ANIMS`; cache VERSION 10 so caches rebuild once): stand/crouch idle and run in four directions, sprint, prone aim and crawl, downed idle and crawl, rifle and pistol holds, 0.18 s blend between clips. The gun's world model sits on `tag_weapon_right`. Dead/spectating players are not drawn. Falls back to the vanilla body when the cache has no character.
- The characters' own body/head/cloth images are not in the install's data (only the farmgirl hair is), so missing surfaces get plain stand-in skin/cloth/hair colours per character (`patchTextures`).
- Dev: `-PdevProps=debugBody=stand|prone|down` puts the camera in front of your own character and saves `zc-body*.png`; checked all three stances by screenshot.
- Not done: aim pitch on the torso, firing/reload/melee/drink third-person clips, crouch checked only in code, name tags, a real two-client test of the roster rows (single client only), the local player's hands still use the one configured character.

## Character textures and the death flow (2026-10-08)
- **Real textures for the survivors**: the characters' body/head/arm/gear images are streamed from `zm.ipak`, which the Unlinker only loads for zones named `zm_*`, so earlier dumps of `so_zclassic_zm_transit` had no data for them. `Bo2Assets.unlink` now copies `zm.ipak` into the temporary dump folder under the name `so_zclassic_zm_transit.ipak` and passes it with `--search-path` (the install is untouched, the copy is deleted with the dump); 120 more images come out, including the first-person sleeves. Cache VERSION 11. Dev dumps (`bo2-dump/out2`) got the same images by hand. The plain stand-in colours in `ZcPlayerBody` remain for installs where an image is still missing.
- **Death flow** (BO2 order): down (red screen, bleed-out, crawl) -> bled out: spectate a teammate who is still up (`Revive.tickAll` points the camera at one, re-picks when they go down; HUD "YOU HAVE BLED OUT / Spectating NAME / You will return next round") -> last player down: game over, the horde freezes where it stands (no longer deleted) and everybody is invulnerable; the world fades to red, "GAME OVER / You Survived N Rounds" fade in, then the Survival - Green Run scoreboard, 12 s (was 8; `game_over_delay_s`), fade to black over the last 2 s -> online matches return to the lobby (`Game.online`, host can start again), solo/offline sends `EndMatch` and the client goes to the BO2 title (was: restarted the match by itself). Bench scripts still restart (`-Dzombiecraft.bench|feelBench|papBench`).
- Dev: `-PdevProps=debugDie` ends the match after 12 s and saves `zc-die1/2.png`. Checked: red/fade screen and scoreboard by screenshot, the way back to the title in the log. The spectate camera and the online return to the lobby were not run (need two clients).

## Movement and jumping like BO2 (2026-10-08)
- 1 block = 40 BO2 units (the scale the maps were cut at). Run: g_speed 190 in/s = 4.75 blocks/s (Minecraft walks 4.32): movement attribute 0.11. Sprint: BO2 player_sprintSpeedScale 1.5 (Minecraft's own bonus is 1.3, `Game.sprintBoost` adds the rest while sprinting). Jump: BO2 jump_height 39 in (the 64 in the scripts is old-school mode only) with bg_gravity 800 in/s2 = 0.05 blocks/tick2 (Minecraft 0.08): jump strength 0.305 peaks at about 1.03 blocks (simulated, tick by tick), a touch above BO2's 0.98 so a one-block ledge can still be hopped, and the jump lasts 14 ticks instead of 12 (floatier, like BO2). All four numbers are rows in `sheets/systems.json` (`player_*`), applied in `Game.applyMovement` whenever a player is set up (also joins and lobby). Prone/downed speeds are multipliers on top, unchanged.
- Checked by compiling and by simulating the jump; not yet felt in game, and the sprint/prone/crawl multipliers were not re-tuned. The weapon move-speed scale of each gun (BO2 slows you down a little with heavy guns) is not done.

Polish (2026-10-06): the loading picture is held at least 16 s (game paused meanwhile) so the Tranzit track is heard; loading/menu music now fades out instead of cutting (the abrupt stop sounded like a glitch). More ambience: wind howl, crickets, neon hum loops; creaks, groans, crows, wolves, church bell, distant screams as random one-shots.
Dev note: the scripted self-test (-Pbench) is flaky when its window loses focus or is clicked; one run passed 20 checks with 0 failures, others were interrupted.

## Cleanup pass: the chosen place follows the whole online flow, dead code, hot paths (2026-10-08)
- **Bug**: turning ONLINE GAME on showed the depot in the lobby, loading and start screens, and the sky/clock/ambience were the depot's whatever place was picked (Town, Power Station, Farm). Now `Atmosphere.map` holds the place id (set by the host when it launches, and by the server's new `Place` payload when anyone joins, so joiners follow the host); `Sheets.place()` derives it from the loaded map, `Sheets.atmosphere(place)` picks the row (own row, else the shared Tranzit `bus_depot` row, else `*`) for both the server clock and the client sky. A joiner sees "Joining the lobby..." until the server answers. Ambience beds are per place (depot keeps its hum beds, the diner its own, the others wind and crickets only).
- Joiners and respawned players now get Insta-Kill if it is running; Insta-Kill itself is applied once in `ZombieHealth.hit` (was repeated at five call sites).
- Dev views moved from `Game.tick` to `game/DevTools` (switches read once, `DevTools.ANY` false in normal play). Same `-PdevProps` names.
- Hot paths: `Barrier.shotClip` compares block identity instead of registry-id strings; `Bo2Mesh.draw` reads per-surface flags computed once per model and reuses its skinning buffers; `UiArt` remembers a missing image for 2 s instead of a disk check per draw per frame (also fixes `chalk()` caching a miss for good); `UiFont` cache key is a record, bulk pixel copy.
- Small things: `MapSelect`/`Select` lost an always-false `host` argument, the match and lobby postcard is one method, `StateSync` has `drinking()/drinkPerk()` instead of magic 512/`>>10`, one weapon-icon mapping (`UiArt.weaponIcon`), unused fields/locals/`HookRow`/`Cue.at(pitch)` removed, the Lobby text no longer says playit.gg. `tools/preflight.py` now sees `sys("...")` calls inside `Sheets.java` and the zombie tiers' hit-damage rows (20 warnings down to 15, all rows that really have no code yet).
- Another chat was editing the repo at the same time (`tools/bo2_library.py`, `Bench` `benchStart`); its files ended up in the commit made here.

## BO2 local library (2026-10-08)
- `tools/bo2_library.py` dumps EVERY zone of the full install (`D:\New folder (2)`: 209 zones = zombies, multiplayer, campaign, English voice zones) with the Unlinker into `C:\Users\alexi\bo2-dump\library\zones\<zone>` (images as DDS, models as XMODEL_EXPORT, xanim, materials, techsets, weapons, mapents, soundbank alias tables, stringtables, ui, aitype ...), hard-links identical files through `_pool`, and writes every sound bank entry (`audio/<bank>/<source name>.flac|wav`, 28,485 files, 28,412 named, `audio/aliases.json` = alias name -> files). `index.db` + `INDEX.md` cover ~400k entries. Look up with `python tools/bo2_library.py find <regex> [--type image|model|xanim|material|weapon|map|alias|sound] [--zone z]`. Lives outside the repo (BO2 files never ship); resumable (`build` skips zones with a `.done` marker, `--force` redoes).
- Not obtainable with Unlinker v0.33: BSP (gfxworld / clipmap / comworld) and fx. Entity placement (mapents) is dumped.

## BO2 PC controls, aim down the sight, frag grenade (2026-10-08)
- **Keys now match BO2 PC**: left click fires, right click aims down the sight (hold), `R` reload, `V` knife (was left click), `G` frag grenade, `F` use, `Left Shift` sprint, `C` crouch, `Z` prone, `Space` jump, `1-9` / mouse wheel weapon, `Tab` scoreboard, `T` chat. Minecraft's crouch (Shift) and sprint (Ctrl) are moved once to BO2's keys on first start (marker file `zombiecraft_controls_v1` in the game folder; Options > Controls "Reset to Default" uses the BO2 keys too). Controls screen shows BO2's names (FIRE WEAPON, AIM DOWN THE SIGHT, MELEE ATTACK, FRAG GRENADE ...); prone moved to the MOVE tab.
- **ADS**: `Input` payload carries `ads` and `grenade`. Server: spread x `ads_spread_mult` (0.25, tuned) while aiming, walking x `ads_move_mult` (0.7, tuned, same idea as BO2's adsMoveSpeedScale), cancelled by reload/sprint. Client (`ViewState`, new): the real clips `viewmodel_<gun>_ads_up / ads_down / ads_fire` (the raise holds its last frame; lowering starts from wherever the raise had got to), world FOV x `ads_zoom_fov_mult` (0.78, tuned) through `ZcGameRendererMixin` (the hands keep their own FOV), crosshair hidden while sighted. Sprint uses `sprint_in / sprint_loop / sprint_out` the same way. Cache VERSION 12 (the extra clips are copied for every gun that has an `anim` prefix).
- **Frag grenade** (`game/Grenades`, values from `frag_grenade_zm` into systems.json `grenade_*`): start 2 (assumed, the weapon file's max is 4 and Max Ammo refills to it; not checked against the zm scripts), hold `G` to pull the pin (`wpn_grenade_pull_pin`) and cook, let go after 0.55 s to throw (23 blocks/s + 3 up, gravity 20 blocks/s^2), bounces off blocks (45% kept, `wpn_grenade_bounce_concrete`) and drops off zombies, 3.5 s fuse counted from the pin pull (a fully cooked one is thrown, it does not blow up in the hand), blast 6.4 blocks, 300 damage in the centre to 75 at the edge, walls shield. Kills go through `ZombieHealth.hit` like any other damage (same points). No firing while the pin is out. HUD: one grenade icon per grenade held. The thrown grenade is the real `t6_wpn_grenade_frag_projectile` on a new `ZcEntities.GRENADE` (a `ZcProp` that updates every tick); the hands are `c_zom_*_viewhands` with the real `t6_wpn_grenade_frag_view` and the M67 clips `pullpin` / `throw`, then the gun's own pull-out. Explosion sound is `wpn_grenade_explode` (stereo, so not positional). Three cues were added to audio.json by hand (the derive script needs a bank this PC does not have).
- **Checked**: bench steps `ads-tightens-spread`, `hip-spread-normal`, `grenade-start-count`, `grenade-pin-out`, `grenade-thrown`, `grenade-kills` pass (`-Pbench -PdevProps=benchStart=12` runs from that step). Screenshots with `-PdevProps=debugHands=ads|grenade|sprint` (client plays the action itself, saves `zc-hands-<mode>-<tick>.png`): ADS centres the M1911 and zooms, the grenade shows the real M67 with the pin ring and the throw, sprint lowers the gun with the real clips.
- **Not done**: tactical grenade (`Q`, no tactical item exists in this map), grenade hurting the thrower, the third-person body holding/throwing a grenade or aiming down the sight, ADS clips are only seen for the M1911 (other guns use the same clip names but were not looked at one by one), mouse sensitivity does not drop while zoomed, a grenade wall-buy.

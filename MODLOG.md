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

## Next
Finish the BO2 zombie renderer on branch `bo2-visuals` (models, textures, anims are readable; renderer not wired). Then: bigger map, power/perks/power-ups, 3D guns, box model.

## UI with real BO2 art (2026-10-06)
Menus and HUD use art converted on the player's PC from their own install (`bo2/UiAssets`, cache `zombiecraft/bo2/ui/`, needs OAT Unlinker like the models). Falls back to plain drawing without it.
Done: title screen, lobby, map select (Green Run open, 5 locked "coming soon"), BO2 loading postcards, pause menu, HUD (weapon icons incl. Ray Gun, round tally marks, low-health red edge instead of hearts/XP).
Perk icons are extracted (specialty_*_zombies) but not drawn yet: the HUD perk row comes with the perk system.
Dev: put `oat.dir=<OAT folder>` in mod/run/config/zombiecraft.properties so the dev client can build the art cache.

## Naming and HUD pass (2026-10-06)
User-facing name is now "Block Ops 2" (UI, lang, mod name, README, licence). Internal ids, packages, folders (`zombiecraft/`), env vars and system properties are unchanged on purpose (saves, caches, config keep working).
HUD now follows the BO2 Zombies layout: chalkmarks tallies bottom left, points + cyan +N popup + ammo bottom right over the BO2 blood splat (hud_dpad_blood), grenade icon, BO2 low-health overlay, small crosshair. Only Green Run is selectable; it starts the Diner and shows the diner postcard.
Real BO2 images worth reusing later: chalkmarks_0-5, hud_dpad_blood, overlay_low_health, grenadeicon_32, damage_feedback (hit marker), hit_direction_zm, specialty_*_zombies (common_zm / zm_transit).

# Block Ops 2: path to parity with Black Ops II Zombies

Goal: after these workstreams a player should be able to sit down, play Green Run / Bus Depot / Survival, and feel it is the same
game as BO2 (same flow, same look and feel, same rules), just built from blocks.

Source of this plan: review of the clip `MedalTVMinecraft20261007134220187.mp4` (2026-10-07) against `BO2_Screeshotrefrences`
(30 menu/gameplay screenshots + 2 Plutonium gameplay videos: `...20261006223247467.mp4`, `...20261007133513830.mp4`).
What the clip could NOT show: audio, zombie AI, round pacing, anything past round 1. Sections below tell each chat to check those by playing.

Each section is independent and can be given to its own chat. Touch only the files named, to avoid merge conflicts.

---

## 0. Ground rules (every chat reads this first)

- **Never ship or modify BO2 files.** BO2 assets (models, images, sounds) are read from the player's own install, converted on their PC
  and cached (`bo2/Bo2Assets`, `bo2/UiAssets`, `bo2/TexturePack`, audio extractor). Every new BO2-based visual must have a plain fallback so the
  game still runs without BO2 (see how `ModBlocks` ships neutral textures).
- Design lives in `sheets/*.json` (source of truth, checked by `python tools/preflight.py --bo2 "<BO2 dir>"`, must stay at 0 errors).
  Prefer changing data over code. Generated sheets (`gen_*.py`) must be re-run, not hand-edited.
- BO2 dev data (decompiled scripts, OAT dumps) is outside the repo: scripts `C:\Users\alexi\Downloads\t6-scripts-main\t6-scripts-main\ZM`,
  dumps `C:\Users\alexi\bo2-dump`, OAT in the toolkit. Do not copy them into the repo. When a rule or number is unknown, look it up in the scripts, don't guess.
- Server authoritative and per-player state (co-op must keep working): game state goes in `game/*` + `PlayerGame`, client only draws.
- Match the style of the surrounding code (tabs, comment density, naming). Mod/internal ids stay `zombiecraft` on purpose.
- Visuals: reuse BO2 UI/art assets; no big text flashes, no vanilla Minecraft UI leaking into a match (user's standing preference).
- Verify by running the game, not only by building: `cd mod && ./gradlew runClient` (JDK 25 as JAVA_HOME; see README). Self-tests:
  `./gradlew runClient -Pbench` (scripted, results `mod/run/zc-bench.txt`, screenshots `mod/run/screenshots/`) and `-PpapBench`.
  Dev switches: `-PdevProps=debugGun,debugPap,debugDrink,debugPowerups,debugBox,debugVm,debugOptions=title|root|settings|controls|quit`.
  Set `pauseOnLostFocus:false` in `mod/run/options.txt`.
- Commit after each finished stream (small commits, message says what and why). Update `MODLOG.md` with decisions and findings.
- Keep a "Known gaps" line in your final message so the next chat knows what was skipped.

### Reference clips (all BO2, Plutonium) and what each is good for
- `D:\Medal\Clips\Call of Duty Black Ops 2 Zombie\MedalTVCallofDutyBlackOps2Zombie20261006223247467.mp4` (4 min): menus, lobby, friends, full round 1 to 3 on Bus Depot,
  wall-gun chalk, outdoors, game over and scoreboard. The most useful one.
- `...20261007133513830.mp4` (about 1 min 30): lobby and early Bus Depot gameplay. Also copied in `BO2_Screeshotrefrences`.
- `...20261007133408211.mp4` (30 s) and `...20261007133508268.mp4` (4 s): the title-screen intro animation only.
- To look at a moment: `ffmpeg -ss <sec> -i <clip> -frames:v 1 out.jpg` (ffmpeg is installed via winget).

### Extra things the long reference clip shows (not in the screenshots)
- **Title intro**: fade in from black, a bright light flare with a horizontal streak and bokeh, dark asteroid debris drifting in over a few seconds. Menu entries in the original:
  ONLINE / OPTIONS / CAMPAIGN / MULTIPLAYER / QUIT (ours: PLAY / OPTIONS / QUIT, fine), `F Friends` bottom right.
- **Wall guns** are glowing white chalk drawings of the gun on the wall, lit by a soft light cone, with the real gun model above or beside them (not a floating blue gun).
- **Outdoors of the Bus Depot**: orange-brown fog and sky, bare trees, wrecked cars, a gas-station canopy, a glass-block wall with a cracked hole, neon `US` sign, `BUS STOP` painted on the road,
  benches, trash cans, scattered papers. Windows are wooden planks over a barred or glass-block window with this view behind.
- **Zombies at windows** grab and tear planks one by one, have glowing blue eyes, torn dirty clothes and blood; killed zombies leave bodies on the floor for a while.
- **Muzzle flash** is a bright yellow-white burst with a real light bloom on the room.
- **HUD details**: the gun name appears above the ammo for a moment after you pick it up (`M14`); red round tally marks bottom left (1, 2, 3 marks); grenade icons bottom right
  (1 to 3 icons); points `+10` / `+60` popups stack above the points; the M1911 starts at `8/32`; a sniper shows a scope overlay.
- **Game over**: text appears over the live world (`GAME OVER` / `You Survived 3 Rounds`), then the Tab-style scoreboard (`Survival - Green Run`, Score, Kills, Downs, Revives, Headshots, Ping, CDC logo).
- Hands are dark gloves with a dark sleeve; the viewmodel gun is detailed, and the weapon bobs when walking.

### Reference cheat sheet (what BO2 shows; use as the target)
- HUD (bottom right): points (white, big), cyan `+N` popup under them, ammo `clip/reserve` with the blood splat behind it, weapon icon to the left,
  grenade icons next to the ammo, perk icons in a row bottom centre-left, round tally (red tally marks) bottom left, small crosshair, red edge when low health.
- Interact prompts: small text, centred low: `Hold F to rebuild Barrier` (F in yellow), `Hold F to buy <Gun> [Cost: N]`.
- Start: M1911 shown as `8/32`, 500 points.
- Menus: blurred dark background with the shattered-glass overlay, white plate headings in the BO2 font, orange highlight for the selected row,
  `ESC Back` bottom left (orange key letter), planet/asteroid main menu, lobby `BUS DEPOT / SURVIVAL` with a player row and the map postcard.
- Game over: screen turns red, `GAME OVER` / `You Survived N Round(s)`.

---

## A. Gameplay correctness and rules (start here, small and high value)

Parity target: every number and rule behaves like BO2's solo Survival.
Files: `game/Machines.java`, `game/WeaponSystem.java`, `game/PlayerGame.java`, `game/Game.java`, `game/ZombieHealth.java`, `game/Interactions.java`,
`sheets/weapons.json` (+ `tools/gen_weapons.py`), `sheets/rounds.json`, `sheets/systems.json`, `game/Bench.java`.

Tasks
1. **Bug: cannot shoot after drinking a perk.** In the clip the ammo stays 20/102 for ~15 s after the bottle while the player types
   "gun can not shoot after drinking". The server lock is only `fireCooldown = tick + DRINK_TICKS` (50 ticks, `Machines.use`), so the cause is elsewhere.
   Check: client fire state (`ZombiecraftClient`, `GunFeedback`), `pg.fireHeld`, reload interrupted by `WeaponSystem.cancelReload`, `drinking` flag
   sync in `Game.java:~443` (`512 | drinkPerk << 10`) and client handling, the held-item/bottle swap not restoring the gun, two perks bought in a row, buying while reloading.
   Fix the root cause, then add a bench step: buy perk -> wait -> fire -> ammo decreases; also buy two perks back to back.
2. **Starting ammo.** BO2 solo shows `8/32` for the M1911 but the weapon file says 80 (`sheets/weapons.json` row `m1911`, status `weapon-file`).
   Find the script that overrides it (zombie start weapon ammo, `t6-scripts ZM`), apply the real values for the start gun, grenade count and for bought/box guns, and note it in MODLOG.
   Also check wall-buy ammo cost (half price) and Pack-a-Punch ammo cost against the scripts.
3. **Walk the whole rules list against the scripts** and fix mismatches: round health, zombie count, spawn delay, speed tiers (`sheets/rounds.json`),
   points (hit 10, kill 50/+50 head/+20 neck/+10 torso/+80 melee), barricade points (+10 per board), nuke/carpenter/insta/double points, Max Ammo,
   box price 950 and teddy rule, PaP 5000 and repack, Juggernog/Speed/Double Tap/Quick Revive effects, down/bleed-out/revive times, drop rates.
4. **Round flow.** Play rounds 1 to 10 by hand: round-start sting, `round number` change animation, between-round delay (10 s), last zombie of a round,
   zombies that get stuck (spawn in unreachable spot, fall out of the map), zombies that never reach the player (they must teleport/respawn like BO2).
5. **Bounds and exploits.** Player cannot leave the map, break or place blocks, or get stuck in windows; zombies cannot be hit through walls; knife range.
6. Run `-Pbench` and `-PpapBench`; extend the bench so every item above has a step that fails when it regresses. Fix the stale steps (see MODLOG: wall-buy,
   Pack-a-Punch and window-repair steps still reference the old diner map).

Done when: bench is fully green on the Bus Depot, a 10-round hand playthrough shows no stuck zombies, no dead input, and numbers match the scripts.

---

## B. Map: Bus Depot look, exterior and lighting

Parity target: standing in the depot should read as the Tranzit Bus Depot from the reference screenshots (bus-station hall, ticket board, chairs,
pillars with yellow trim, glass-block walls, neon, rubble, foggy town visible outside the windows).
Files: `tools/gen_depot.py`, `map/MapBuilder.java`, `sheets/map_*.json`, `sheets/atmosphere.json`, `sheets/textures.json`, `tools/gen_textures.py`,
`tools/gen_blocks.py`, `block/ModBlocks.java`, `tools/preflight.py`, plot tools `depot_plot.py`/`depot_slice.py`/`depot_topdown.py`.
Coordinate with section E (it owns the machine/prop sheets); do not edit `map_machines.json` here without telling that chat.

Tasks
1. **Window exteriors.** Behind every barrier build a real outside (street, buildings, props) lit and fogged like Tranzit; currently there is a flat tan wall and
   a blue strip. Zombies should visibly come from outside and climb in.
2. **Remove indoor road lines.** The asphalt block with dashed lines is used in corridors; use depot tile/concrete indoors, keep asphalt for outdoor roads only.
3. **Real light fixtures.** Replace the floating light cubes with ceiling fixtures (flush panels, hanging lamps, the green/blue neon strips from the reference).
   Add actual light sources (light blocks) so the depot is readable but still dark and moody. Add flicker where BO2 has it if cheap.
4. **Set dressing from the reference:** `BUS DEPOT` sign over the ticket map board, rows of waiting chairs, round pillars with the yellow band, the wall clock,
   ticket counter, lockers, "Employees only" and "Fire regulations" signs, glass-block wall, double doors with mesh windows, rubble piles, trash cans,
   bathroom tiles seen through the window, "Ride the Bus" poster. Use texture-pack images where they exist (`textures.json`), otherwise neutral blocks.
5. **Materials.** Reduce the "flat dark brick" look: variation in floor (checker already), wall damage, stains, plaster over brick; check the texture rows still resolve
   after `Bo2Assets.sheetsHash` changes.
6. **Atmosphere** (`sheets/atmosphere.json`): fog colour/start/end, sky, time of day to match the orange-grey Tranzit haze outside and dark interior; check the fog mixins still apply only during a match.
7. **Layout fixes.** Move the spawn so the player does not start staring at a blank wall and can see a window, a wall gun and the way forward; make sure doors, zones and
   spawn points still validate; keep sightlines like the original (long hall with windows, central pillar area).
8. If doors, windows or spawns move, update the sheets and make `python tools/preflight.py` pass (reachability, windows, spawns, wall-buys, box, PaP).

Done when: a side-by-side of in-game vs reference screenshots shows the same landmarks, nothing floating, no flat tan voids behind windows, lighting readable.

---

## C. Zombies, combat feel and effects

Parity target: zombies are clearly visible, menacing and killed with BO2-style gore; shooting feels punchy.
Files: `entity/ZcZombie.java`, `client/render/ZcZombieRenderer|Model|State.java`, `ZombieSkins.java`, `sheets/zombies.json`, `client/GunFeedback.java`,
`game/ZombieHealth.java`, `game/Barrier.java`, particles/effects (new files under `client/`).

Tasks
1. **Look at zombies up close in a real round** (the clip barely shows any): textures, skin variants, torn clothes, correct size, animation sets
   (walk, run, sprint, attack, board tear-in, climb-in, idle at window, death, crawler after legs are shot if available in `bo2_models`).
2. **Hit and kill effects.** Replace the large white pixel chunks (vanilla block-break style particles) with red blood spray, mist and gibs at the hit location;
   head/torso/limb differences; blood decals on floor/walls are optional.
3. **Hit marker and direction.** Add `damage_feedback` (hit marker) on hit and kill (BO2 has a different mark for kills/headshots) and `hit_direction_zm` when the player is hit;
   check player damage blood on screen and the red low-health edge.
4. **Muzzle flash and shells.** The current flat yellow triangle should become a BO2-style flash at `tag_flash` (several frames, additive) with a brief dynamic light bloom on the
   surroundings (visible in the reference), plus smoke, shell ejects, tracers if present.
   **Dead bodies:** BO2 leaves dead zombies on the floor for a while; check ours despawn timing and ragdoll/death pose.
   **Zombie eyes and dirt:** glowing blue eyes, torn dirty clothes and blood on the body skins (`ZombieSkins`).
5. **Zombie behaviour.** Window tear-in: grab boards one at a time, board sounds; attack from the window; climb animation through the opening; reaction to being shot; zombies
   chasing at different speeds per tier; groaning cadence. Compare the reference videos for timing.
6. **Zombie spawn presentation.** Zombies should not pop into view inside the room; spawn outside the windows (needs section B's exteriors) or rise from the ground where BO2 does.
7. **Counts and pacing.** Confirm the live zombie cap and that bigger rounds stay playable (performance, see section K).

Done when: in a round-5 hand test the zombies read as BO2 zombies, every shot gives feedback (hit marker, blood, sound), and there are no white-pixel particle clumps.

---

## D. First-person rig and held items

Parity target: hands and guns look like BO2 viewmodels in every state.
Files: `client/render/ViewModel.java`, `Hands.java`, `Bo2Mesh.java`, `bo2/Pose.java`, `bo2/XModel.java`, `bo2/XAnim.java`, `bo2/Bo2Assets.java`,
`sheets/bo2_models.json` (+ `tools/gen_bo2_models.py`), `client/mixin/ZcItemInHandRendererMixin.java`.

Tasks
1. **Black patches** on the hands/knuckles (visible on the pistol and AK74u): find whether it is UV, a missing texture, or a bone with a bad skin weight;
   fix in the mesh/texture conversion.
2. **Left hand floating** on the AK74u (open hand next to the gun, in the clip): the left hand should grip the foregrip as in BO2. Check clip selection per gun
   (`viewmodel_<gun>_idle`), the gun/hand attach (`Pose.buildOn`, `tag_weapon`), and the fallbacks for missing clips.
3. **Skin and sleeves.** BO2 shows dark gloved hands; ours are tan with teal sleeves. Pick the right character viewhands (`-Dzombiecraft.character`) and the arm texture, or tint to match the reference.
4. **Every gun, every state.** Run `-PdevProps=debugGun` and review idle, fire, reload, reload-empty, pullout (switching), knife, and the Pack-a-Punch camo for all guns
   in `weapons.json`; list guns whose clips are missing and make the fallbacks look intentional. Fix the weapon swap (pull-out/put-away) timing.
5. **Sprint/jump/crouch/prone arm movement**: BO2 lowers or tilts the weapon when sprinting and bobs when walking; add a subtle walk bob and sprint pose (see H for sprint itself).
6. **Aim down sights**: confirm whether ADS exists (the in-game controls list shows only shoot/reload/knife/interact/crouch/prone). BO2 players expect it:
   decide the binding with the creator (right click is fire today because of Minecraft's input), implement it with the sight model positions, FOV zoom and spread reduction.
7. **Grenade and knife viewmodel clips** (needed by H).

Done when: no visual artefacts on hands, every gun holds correctly and animates through fire/reload/swap, and the hands match the reference colours.

---

## E. Machines, boxes and world props

Parity target: perk machines, power switch, wall weapons, Mystery Box and Pack-a-Punch look like BO2 and are readable from across the room.
Files: `entity/ZcProp.java`, `client/render/ZcPropRenderer.java`, `game/Machines.java`, `game/BoxSystem.java`, `game/PapSystem.java`, `entity/PapVisual.java`,
`sheets/map_machines.json`, `map_wallbuys.json`, `map_boxes.json`, `map_pap.json`, `sheets/bo2_models.json`.

Tasks
1. **Perk machines.** Plain black boxes today. Use the BO2 machine models (`zombie_vending_*` family) and textures, with coloured emissive lighting, the perk logo,
   flicker when off, light when on, machine hum and jingle (audio already in `Machines.switchOn`). Check each of the four perks has a distinct look and colour.
2. **Power switch.** Real lever model, pull animation, sparks and the power-on lighting wave over the map (the map lights up and machines come alive).
3. **Wall weapons.** The blue glow is tiny and floats away from the wall at distance. In BO2 each wall gun is a large white chalk drawing of the gun on the wall, lit by a soft
   light cone (blue-white or warm depending on the room), with the gun model shown with it. Add the chalk drawing (the BO2 `chalk_*` / weapon outline images if they are in the
   install, otherwise draw one from the gun icon), scale to BO2 size, add the light cone, and make the F prompt appear at the right distance with name and cost.
4. **Mystery Box.** Verify orientation and size against the map walls after section B; the light beam above it (BO2's blue/white beam) when the box is active and the moving
   animation (box leaves, a new location lights up, teddy bear and its voice line) per `box_rules.json`.
5. **Pack-a-Punch.** Verify against the real machine: camo gun appears in the slot, upgrade timer, sounds; make sure it still works after map changes (`-PpapBench`).
6. **Power-ups** (Max Ammo, Insta-Kill, Double Points, Nuke, Carpenter): BO2 drop models and glow with the spinning, timed disappearance with flashing, HUD icons with timers,
   screen flash for Nuke, correct sounds. Check `PowerUps.java` drop rules and the HUD indicators.
7. **Doors and debris.** Buyable doors show their cost and open with BO2-style effect and sound; chalk/price text readable.

Done when: each interactive object is recognisable from the reference, has a glow or light that reads at distance and works in the bench.

---

## F. HUD, prompts and text

Parity target: the on-screen text and icons are exactly BO2's in wording, size and placement; no vanilla UI visible.
Files: `client/hud/ZcHud.java`, `client/mixin/ZcGuiMixin.java`, `client/menu/UiFont.java|UiArt.java`, `src/main/resources/assets/zombiecraft/lang/*`, `bo2/UiAssets.java`.

Tasks
1. **Prompt wording and style**: `Hold F to rebuild Barrier` (small, with the F in yellow); `Hold F to buy <Weapon> [Cost: N]`;
   `Hold F to buy ammo for <Weapon> [Cost: N]`; perk, box, PaP, door, power prompts exactly as BO2 (check the reference videos for the text).
2. **Vanilla UI out.** Chat and command errors (red `Invalid integer` lines, `<Player688>` chat) overlap the HUD in a match. Hide chat and system messages in-game
   (keep a dev toggle), route needed messages (e.g. `The power is on`) through the BO2-style subtitle line.
3. **Ammo and grenade area**: grenade icons next to the ammo (count shown by icons as in BO2), weapon icon, low-ammo colour, reload bar under the ammo (the orange bar exists).
   Also the weapon name label above the ammo for a moment after picking up or switching a gun (`M14` in the reference), and the round tally marks in red (1, 2, 3...).
   Scoped weapons (sniper) need a scope overlay.
4. **Perk row** sizing and spacing, with the perk drinking indicator; the Juggernog/Speed/Double Tap/Quick Revive icons and a lost-perk effect.
5. **Round counter**: tally marks and the big round-number fade in/out at round change like BO2 (check no large text "flash" artefacts).
6. **Damage feedback in HUD**: red screen edges when low health; the BO2 hit indicator; downed screen (blur/desaturate), bleed-out timer, revive progress bar.
7. **Points popups**: the cyan `+N` stacking behaviour (several at once), `-N` on purchases, power-up status icons with a countdown.
8. **Safe areas/resolutions**: test at 1280x720, 1920x1080 and an ultrawide; scale text and icons so nothing overlaps.

Done when: HUD screenshots at 1080p match the reference placement and wording, and no vanilla text appears during play.

---

## G. Menus and flow (most exist, now match the reference)

State: title, lobby, map select (Green Run open), options (Settings + Controls tabs), online join, pause menu, quit dialog, Tab scoreboard and a Game Over
screen all exist (`client/menu/Bo2Menus.java`, `Bo2Options.java`, `Bo2Online.java`, `ZcHud.java`). Task is comparison and gaps.
Files: `client/menu/*`, `client/mixin/ZcLoadingScreenMixin.java`, `ZcHud.java` (scoreboard, game over), `bo2/UiAssets.java`.

Tasks
0. **Title intro animation** (see the extra reference notes): fade in from black, light flare with horizontal streak and bokeh, asteroid debris drifting in. Compare with ours
   frame by frame (`...133408211.mp4`) and match timing; the planet/globe animation on lobby and map select too.
1. **Screen-by-screen diff** against the reference screenshots and fix layout, fonts and spacing:
   ZOMBIES lobby (SOLO PLAY / CUSTOM GAMES / THEATER / LEADERBOARDS / OPTIONS / MODS), `BUS DEPOT / SURVIVAL` lobby with `Game starting in 3` countdown and postcard,
   map select with the description text ("SURVIVAL, Survive in Town ..."), Settings tabs (Graphics/Advanced/Sound/Voice chat/Game) and Controls tabs (Look/Move/Combat/Interact/Gamepad),
   system info, friends, `Leave Lobby?` dialog, pause menu (`ZOMBIES / RESUME GAME / OPTIONS / END GAME`).
2. **Pause menu**: the clip shows the controls list overlaid on the pause menu; BO2's pause is just the three entries. Move the controls list to Options > Controls
   (keep it readable) so the pause screen looks like the reference.
3. **Game Over**: red overlay and text `GAME OVER` / `You Survived N Rounds` (reference has the correct singular/plural), then the stats row, then back to the BO2 title with the music.
   Check Tab scoreboard layout against `Survival - Green Run | Score Kills Downs Revives Headshots Ping` and add the CDC logo plate.
4. **Settings that do something**: sensitivity, FOV (65 default in BO2), brightness, volume sliders (music/effects/voice), invert mouse, key rebinding for the real controls.
5. **Controls screen** lists the real bindings (including whatever sections D/H add: ADS, sprint, grenade, switch weapon).
6. **Leaderboards / Theater / Friends / Mods**: simple, honest screens (local stats, "no data", `You have no friends`) instead of dead entries; low priority but no crashes.
7. **Loading screen**: BO2 postcard + loading music exists; make sure it shows for the real load time only and never flashes.
8. **First-run flow**: if BO2 is not found, a clear BO2-styled message with how to point to the install (`zombiecraft.properties`), not a vanilla error.
9. Unlock the other 5 maps only when they exist; keep the "coming soon" deny sound.

Done when: each screen above can be placed next to its reference screenshot and differ only in the map/mod-specific text.

---

## H. Missing core mechanics

Parity target: the core moves BO2 players use all the time. None of the below is confirmed implemented (grenades are only an icon; the pause screen lists no
sprint, ADS or grenade key). Verify each by playing first, then build what is missing.
Files: `game/WeaponSystem.java`, `game/Projectiles.java`, `game/PlayerGame.java`, `client/ZombiecraftClient.java`, `client/mixin/ZcMouseHandlerMixin.java`,
`net/Payloads.java`, `sheets/systems.json`, `sheets/weapons.json`.

Tasks
1. **Lethal grenades**: start count as in BO2 (check the script), throw key (G), cook/fuse, arc and bounce, explosion radius/damage, zombie launch/gib, points, pickup from Max Ammo,
   HUD icons (F section), sounds, throwing viewmodel clip.
2. **Tactical/other equipment** only if the Bus Depot Survival rules include any (check scripts); skip otherwise and write that in MODLOG.
3. **Sprint** (Shift is crouch today; BO2 PC sprint is a key hold): sprint speed, stamina rules if any, gun lowering, footstep sounds, no shooting while sprinting. Decide bindings with the creator.
4. **ADS** (see D6) and **crouch/prone** accuracy and speed numbers vs BO2.
5. **Weapon slots and switching**: two weapons (three with Mule Kick, not in this map), switch key, scroll wheel, pullout time, swapping when buying a third gun replaces the current one.
6. **Knife**: melee range, lunge, animation, points (+80), BO2 knife sounds; Bowie only if the map has it.
7. **Wall-gun roster for the Bus Depot**: compare `map_wallbuys.json` with BO2's actual wall weapons and prices for this location, and the Mystery Box pool (`box_pool.json`)
   with BO2's list. Add missing guns that exist in the install (some are skipped for missing icons/clips; see MODLOG).
8. **Perks**: only add extra perks if the creator wants them; BO2's Bus Depot Survival reference text says no perks / PaP. Decision needed: keep ours (richer) or match survival exactly
   (put the choice in a setting). Document the decision.
9. **Player health and damage model**: BO2 regenerates health after a delay; zombie hit damage per round, down on third hit, revive rules. Verify against `systems.json` and scripts.

Done when: a BO2 player can use grenades, sprint, aim, swap and knife with the same keys/feel, each covered by a bench step where scriptable.

---

## I. Audio and atmosphere

Parity target: it should sound like Tranzit. (Audio already loaded from the player's BO2 banks; this stream is about completeness and mix.)
Files: `client/audio/*`, `audio/*`, `sheets/audio.json`, `tools/audio_seed.json`, `tools/derive_audio.py`, `game/Cue.java`.

Tasks
1. **Listen-through checklist** in a round: round start/end stings, zombie spawn/groan/attack/death, barrier board sounds, buy/denied/ching, box open/teddy/music, PaP loop and jingle,
   perk jingles and bottle sounds, power on, power-ups pickup lines, low-health heartbeat, downed/revive, game-over music. List every missing or wrong cue.
2. **Announcer / character lines** (BO2 plays voice lines for power-ups, perks, kills): add the ones available in the banks.
3. **Mix and positional audio**: distances, occlusion (muffle through walls if feasible), zombie voice volumes, footsteps (player + zombies) on different surfaces.
4. **Ambience**: outdoor wind/fog and distant moans, depot hum and creaks (some exist); the BO2 Tranzit ambient music/stingers between rounds.
5. **Volume settings** in Options drive the mixer (music/effects/dialogue).
6. Make sure nothing plays vanilla Minecraft sounds (step, hurt, ambient, music) during a match.

Done when: a 10-minute listen shows no missing core cues, no silence where BO2 has sound, and no vanilla sounds.

---

## J. Co-op (up to 4) and online

Parity target: the BO2 co-op experience: shared map, separate points, revive, scoreboard with all players.
State: `game/Revive.java` (down/bleed-out/revive) and `client/menu/Bo2Online.java` exist; not yet verified with two real players.
Files: `game/Revive.java`, `game/Game.java`, `game/PlayerGame.java`, `net/Payloads.java`, `client/menu/Bo2Online.java`, `client/hud/ZcHud.java`.

Tasks
1. Two-copy (and 3/4 player LAN) test: join, start, points per player, kills, buying, box/PaP use by two players, power switch, shared zombie target selection.
2. Scoreboard rows for all players with names, the lobby player list, teammate name indicators and the revive prompt/bar, downed teammate marker.
3. Round scaling for 2-4 players (zombie count, health, spawn timing, power-up/box rules) as in the scripts.
4. Disconnect/rejoin, host leaving, game over when all players are down.
5. Zombie targeting and pathing when players are in different rooms; barricade usage by several players.
6. Make joining easy ("Invite info" exists in pause) and safe: only solo/own LAN, as agreed in MODLOG.

Done when: 2 and 4 players can finish several rounds on one LAN with correct points and revives.

---

## K. Stability, performance and release

Parity target: runs well, never crashes, installs easily.
Files: `mod/*`, `tools/package.py`, `play.bat`, `README.md`, `tools/preflight.py`, `MODLOG.md`.

Tasks
1. **Performance**: measure FPS in the depot at round 1, 10, 20 (many zombies); cache BO2 meshes/skins, avoid per-tick allocation, cap particles, fix any stutter on first use of a gun/prop.
2. **Long soak test**: 30+ minutes, rounds 20+, no memory growth, no leftover entities, clean restarts (game over -> title -> play again many times).
3. **Autoplay/dev code paths**: make sure dev flags cannot be triggered in a normal play session; `/zc` dev commands (e.g. `zc points`) validate input (the clip shows an `Invalid integer 10000000000000` error) and are disabled unless dev mode is on.
4. **Without BO2** (no cache, no install): game still starts, uses fallbacks, shows the BO2-styled first-run message (see G8).
5. **Graphics settings**: texture pack size (`-Dzombiecraft.packSize`), render distance, low-spec mode.
6. **Packaging**: `tools/package.py` release zip with the portable Prism launcher; fresh-PC install test; update README controls and requirements.
7. **QA pass**: run bench and papBench, preflight, a full hand playthrough, and log results in MODLOG.

Done when: a fresh install on another PC plays a 30-minute session without crashes and at a steady frame rate.

---

## Order and dependencies

1. A first (small, unblocks testing). K3 (dev command validation) can go with it.
2. B and E share map sheets (`map_machines.json`): one chat owns the sheet each, or run E after B.
3. C (spawn presentation) depends on B's exteriors; the rest of C is independent.
4. D before H6/H1 (needs viewmodel clips for ADS/grenade).
5. F, G, I are independent of everything.
6. J after A/H are stable. K last, but K1/K2 are useful after C and B.

## Final parity checklist (all must be true)

- [ ] Start a match from the BO2 title and menus with no vanilla UI, no flashes, correct music.
- [ ] Depot looks like the reference screenshots: lit, dressed, windows open onto a foggy town.
- [ ] Zombies look right, come from outside, tear boards, die with blood and gibs; hit markers and sounds on every shot.
- [ ] Hands and every gun animate correctly; ADS, sprint, grenades, knife, weapon swap all work.
- [ ] Buy, box, PaP, perks, power, power-ups, doors behave and look like BO2; prompts use BO2 wording.
- [ ] Numbers (start ammo, health, points, rounds, drops, box rules) match the scripts; 10 rounds played without stuck zombies.
- [ ] Down, bleed-out, revive, Quick Revive; Game Over screen red with correct round text; return to title.
- [ ] Co-op with 2 to 4 players works.
- [ ] Audio checklist complete, no vanilla sounds.
- [ ] 30-minute soak with steady FPS; package installs on a clean PC; bench and preflight all green.

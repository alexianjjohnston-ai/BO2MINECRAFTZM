"""Writes sheets/hooks.json and sheets/schema.json (the two authored meta sheets)."""
import json, os
SH = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', 'sheets')

H = lambda id, system, kind, target, handler, note: dict(id=id, system=system, kind=kind, target=target, handler=handler, status="todo", note=note)
hooks = [
    H("h_join", "bootstrap", "event", "ServerPlayConnectionEvents.JOIN", "GameBootstrap.onJoin", "build the map once, set gamerules and time, give the starting gun, start the game"),
    H("h_tick", "rounds", "event", "ServerTickEvents.END_SERVER_TICK", "RoundDirector.tick", "round state machine: countdown, spawning, intermission"),
    H("h_gun_use", "weapons", "item", "GunItem.use / onUseTick", "GunItem", "right click fires; holding repeats for auto weapons"),
    H("h_gun_reload", "weapons", "key", "client key R -> ReloadPayload (C2S)", "WeaponSystem.reload", "reload with the BO2 mag-out/in/end cues"),
    H("h_block_use", "interact", "event", "UseBlockCallback.EVENT", "Interactions.onUseBlock", "box chest, Pack-a-Punch machine, board repair"),
    H("h_entity_use", "interact", "event", "UseEntityCallback.EVENT", "Interactions.onUseEntity", "wall-buy item frames"),
    H("h_attack", "melee", "event", "AttackEntityCallback.EVENT", "Melee.onAttack", "left click is the knife"),
    H("h_damage", "zombies", "event", "ServerLivingEntityEvents.ALLOW_DAMAGE", "ZombieHealth.onDamage", "cancel vanilla damage to Zombiecraft zombies; hit points are our own"),
    H("h_player_death", "bootstrap", "event", "ServerLivingEntityEvents.ALLOW_DEATH", "GameOver.onPlayerDeath", "game over screen then restart"),
    H("h_zombie_entity", "zombies", "entity", "ZcZombie extends Zombie", "ZcZombie", "own goals: go to the window, tear boards, climb in, hunt the player"),
    H("h_commands", "dev", "command", "CommandRegistrationCallback.EVENT", "DevCommands", "/zc start|stop|round N|points N|give ID|status for testing"),
    H("h_state_sync", "net", "network", "ServerPlayNetworking.send StateSync (S2C)", "StateSync", "round, points, ammo, boards, box state to the HUD"),
    H("h_cue_net", "net", "network", "ServerPlayNetworking.send CuePlay (S2C)", "AudioNet", "tells clients which BO2 cue to play at a position"),
    H("h_hud", "hud", "event", "HudRenderCallback.EVENT", "ZcHud.render", "round number, points, ammo, messages"),
    H("h_audio_boot", "audio", "event", "ClientLifecycleEvents.CLIENT_STARTED", "AudioCache.prepare", "find BO2, read sound banks, write the local cue cache"),
    H("h_audio_play", "audio", "client", "CuePlayer via OpenAL", "CuePlayer", "plays cached WAV cues (positional or 2D), falls back to vanilla sounds"),
    H("h_food", "bootstrap", "event", "ServerTickEvents.END_SERVER_TICK", "PlayerRules.tick", "keep food full, custom health regeneration"),
    H("h_barrier_ai", "zombies", "goal", "ZcZombie.registerGoals", "BarrierGoal", "walk to the window attack spot, tear boards, then enter"),
]
json.dump(hooks, open(os.path.join(SH, 'hooks.json'), 'w'), indent=1)
print(len(hooks), 'hooks')

STR = {"t": "string"}; NUM = {"t": "number"}; BOOL = {"t": "boolean"}
def S(**kw): return dict(kw)
def opt(d): return dict(d, optional=True)
def enum(*v): return {"t": "string", "enum": list(v)}
def ref(r): return {"t": "string", "ref": r}
FACING = enum("north", "south", "east", "west")
ST = enum("script", "tuned")
schema = {
 "systems": {"file": "systems.json", "key": "id", "columns": {"id": STR, "value": NUM, "unit": STR, "source": opt(STR), "status": ST, "note": opt(STR)}},
 "rounds": {"file": "rounds.json", "key": "round", "columns": {"round": NUM, "zombies": NUM, "health": NUM, "spawnDelay": NUM, "walkPct": NUM, "runPct": NUM, "sprintPct": NUM, "source": STR}},
 "zombies": {"file": "zombies.json", "key": "id", "columns": {"id": STR, "speedRatio": NUM, "hitDamageKey": ref("systems.id"), "attackIntervalTicks": NUM, "reachBlocks": NUM,
    "cueAmbient": ref("audio.cue"), "cueAttack": ref("audio.cue"), "cueDeath": ref("audio.cue"), "cueSpawn": ref("audio.cue"), "cueTear": ref("audio.cue"), "cueRun": ref("audio.cue"), "status": ST}},
 "weapons": {"file": "weapons.json", "key": "id", "columns": {"id": STR, "name": STR, "bo2Id": STR, "kind": enum("pistol", "shotgun", "smg", "rifle", "raygun"), "fireMode": enum("semi", "auto", "burst"),
    "damage": NUM, "damageMin": NUM, "rangeFull": NUM, "rangeMin": NUM, "headMult": NUM, "pellets": NUM, "spreadDeg": NUM, "fireTime": NUM, "burstCount": NUM, "burstGap": NUM,
    "mag": NUM, "reserve": NUM, "reloadTime": NUM, "reloadEmptyTime": NUM, "projectile": BOOL, "projSpeed": NUM, "explRadius": NUM, "range": NUM,
    "wallCost": opt(NUM), "boxWeight": NUM, "start": BOOL, "upgrade": BOOL, "papId": opt(ref("weapons.id")), "papName": STR,
    "cueFire": ref("audio.cue"), "cueDry": ref("audio.cue"), "cueReloadOut": ref("audio.cue"), "cueReloadIn": ref("audio.cue"), "cueReloadEnd": ref("audio.cue"),
    "cueProjectile": opt(ref("audio.cue")), "cueExplosion": opt(ref("audio.cue")),
    "iconShape": enum("pistol", "shotgun", "smg", "rifle", "revolver", "raygun"), "iconColor": STR, "status": enum("script", "tuned", "weapon-file")}},
 "box_pool": {"file": "box_pool.json", "key": "id", "columns": {"id": STR, "weaponId": ref("weapons.id"), "weight": NUM, "source": STR}},
 "box_rules": {"file": "box_rules.json", "key": "id", "columns": {"id": STR, "usesMin": NUM, "usesMax": NUM, "moves": enum("any", "zero", "positive"), "teddyPct": NUM, "source": STR, "note": STR}},
 "map_ops": {"file": "map_ops.json", "key": "id", "columns": {"id": STR, "order": NUM, "op": enum("fill", "walls", "checker", "grid"), "block": STR, "block2": opt(STR),
    "x1": NUM, "y1": NUM, "z1": NUM, "x2": NUM, "y2": NUM, "z2": NUM, "stepX": NUM, "stepZ": NUM, "group": STR, "note": STR}},
 "map_windows": {"file": "map_windows.json", "key": "id", "columns": {"id": STR, "wall": enum("N", "S", "E", "W"), "fixed": NUM, "a": NUM, "width": NUM, "y0": NUM, "height": NUM, "boards": NUM, "room": STR, "boardBlock": STR, "sillBlock": STR}},
 "map_spawns": {"file": "map_spawns.json", "key": "id", "columns": {"id": STR, "window": ref("map_windows.id"), "x": NUM, "y": NUM, "z": NUM}},
 "map_wallbuys": {"file": "map_wallbuys.json", "key": "id", "columns": {"id": STR, "weaponId": ref("weapons.id"), "x": NUM, "y": NUM, "z": NUM, "facing": FACING, "room": STR}},
 "map_boxes": {"file": "map_boxes.json", "key": "id", "columns": {"id": STR, "x": NUM, "y": NUM, "z": NUM, "facing": FACING, "initial": BOOL, "room": STR}},
 "map_doors": {"file": "map_doors.json", "key": "id", "columns": {"id": STR, "cost": NUM, "x1": NUM, "y1": NUM, "z1": NUM, "x2": NUM, "y2": NUM, "z2": NUM, "block": STR, "opens": STR, "cue": ref("audio.cue"), "label": STR, "room": STR}},
 "map_machines": {"file": "map_machines.json", "key": "id", "columns": {"id": STR, "kind": enum("power", "perk"), "perk": opt(STR), "x": NUM, "y": NUM, "z": NUM, "facing": FACING, "room": STR}},
 "map_pap": {"file": "map_pap.json", "key": "id", "columns": {"id": STR, "x1": NUM, "y1": NUM, "z1": NUM, "x2": NUM, "y2": NUM, "z2": NUM, "facing": FACING, "room": STR}},
 "map_player": {"file": "map_player.json", "key": "id", "columns": {"id": STR, "x": NUM, "y": NUM, "z": NUM, "yaw": NUM, "room": STR}},
 "audio": {"file": "audio.json", "key": "cue", "columns": {"cue": STR, "category": enum("music", "ui", "world", "vox", "weapon"), "soundSource": enum("MASTER", "MUSIC", "PLAYERS", "HOSTILE", "BLOCKS", "AMBIENT"),
    "volume": NUM, "positional": BOOL, "loop": BOOL, "variants": NUM, "fallback": STR}},
 "audio_files": {"file": "audio_files.json", "key": "id", "columns": {"id": STR, "cue": ref("audio.cue"), "bank": STR, "entryId": NUM, "size": NUM, "format": enum("pcm16", "flac"), "channels": NUM, "rateHz": NUM, "frames": NUM}},
 "bo2_models": {"file": "bo2_models.json", "key": "id", "columns": {"id": STR, "group": enum("gun", "zombie", "head", "machine", "prop", "perkbottle", "player"), "xmodel": STR, "world": opt(STR), "note": opt(STR)}},
 "hooks": {"file": "hooks.json", "key": "id", "columns": {"id": STR, "system": STR, "kind": STR, "target": STR, "handler": STR, "status": enum("todo", "coded", "tested"), "note": STR}},
}
json.dump(schema, open(os.path.join(SH, 'schema.json'), 'w'), indent=1)
print(len(schema), 'sheets in schema')

package com.zombiecraft.game;

import com.zombiecraft.entity.ZcEntities;
import com.zombiecraft.entity.ZcProp;
import com.zombiecraft.entity.ZcZombie;
import com.zombiecraft.game.PlayerGame.Gun;
import com.zombiecraft.map.MapBuilder;
import com.zombiecraft.net.Payloads;
import com.zombiecraft.sheet.Rows.PlayerSpawn;
import com.zombiecraft.sheet.Rows.RoundRow;
import com.zombiecraft.sheet.Rows.SpawnDef;
import com.zombiecraft.sheet.Rows.WallBuyDef;
import com.zombiecraft.sheet.Sheets;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.*;

/** The Zombies game for one server: phases, rounds, spawning, players. Everything numeric comes from the sheets. */
public final class Game {
	public static Game INSTANCE;
	/** Set by the client when the player hosts an online match: the next world waits in the lobby instead of starting at once. */
	public static volatile boolean lobbyNext;
	public static final int MAX_PLAYERS = 4;
	/** The lobby's random join code while hosting online (null = nobody is checked). Players other than the host must send it within 5 seconds of joining. */
	public static volatile String joinCode;
	private final Set<UUID> verified = new HashSet<>();

	public final MinecraftServer server;
	public ServerLevel level;
	public BlockPos origin;
	public long tick, startedAt;
	public int phase = Payloads.PHASE_IDLE, round, zombiesToSpawn, spawnCooldown, countdown, intermission, gameOverTicks, roundsSurvived;
	public final Map<UUID, PlayerGame> players = new HashMap<>();
	public final List<Barrier> barriers = new ArrayList<>();
	public final Set<ZcZombie> alive = new HashSet<>();
	public BoxSystem box;
	public PapSystem pap;
	public Machines machines;
	public Doors doors;
	public PowerUps powerups;
	/** Points the whole team has earned this game (drives power-up drops). */
	public int teamEarned;
	/** Ticks left of the lobby's "Game starting in N" (0 = waiting for the host). */
	public int lobbyCountdown;
	/** The match was opened from an online lobby: after game over everyone goes back to it instead of to the title screen. */
	public boolean online;
	private final java.util.TreeMap<Long, List<Runnable>> scheduled = new java.util.TreeMap<>();
	private final Random rng = new Random();

	private Game(MinecraftServer server) { this.server = server; }

	// ------------------------------------------------------------------ wiring
	public static void register() {
		ServerLifecycleEvents.SERVER_STARTED.register(s -> { INSTANCE = new Game(s); INSTANCE.prepareSpawn(); });
		ServerLifecycleEvents.SERVER_STOPPING.register(s -> INSTANCE = null);
		ServerTickEvents.END_SERVER_TICK.register(s -> { if (INSTANCE != null) INSTANCE.tick(); });
		ServerPlayConnectionEvents.JOIN.register((handler, sender, s) -> {
			if (INSTANCE == null) return;
			if (s.getPlayerCount() > MAX_PLAYERS) { handler.disconnect(net.minecraft.network.chat.Component.literal("This match is full (" + MAX_PLAYERS + " players max).")); return; }
			ServerPlayer joined = handler.getPlayer();
			if (joinCode != null && !s.isSingleplayerOwner(joined.getGameProfile())) {
				UUID id = joined.getUUID();
				INSTANCE.later(100, () -> { if (!INSTANCE.verified.contains(id)) handler.disconnect(net.minecraft.network.chat.Component.literal("Join code missing or wrong.")); });
			}
			INSTANCE.onJoin(joined);
		});
		ServerPlayNetworking.registerGlobalReceiver(Payloads.JoinCode.TYPE, (payload, ctx) -> {
			Game g = INSTANCE;
			if (g == null) return;
			String expected = joinCode;
			if (expected == null || expected.equalsIgnoreCase(payload.code())) g.verified.add(ctx.player().getUUID());
			else ctx.player().connection.disconnect(net.minecraft.network.chat.Component.literal("Wrong join code."));
		});
		ServerPlayNetworking.registerGlobalReceiver(Payloads.StartMatch.TYPE, (payload, ctx) -> {
			Game g = INSTANCE;
			if (g != null && g.phase == Payloads.PHASE_LOBBY && g.lobbyCountdown == 0 && g.server.isSingleplayerOwner(ctx.player().getGameProfile())) {
				com.zombiecraft.ZombiecraftMod.LOG.info("Block Ops 2: host pressed START MATCH");
				g.lobbyCountdown = 60;
				Cue.all("uin_lobby_join", g.level);
			}
		});
		ServerPlayConnectionEvents.DISCONNECT.register((handler, s) -> { if (INSTANCE != null) { INSTANCE.players.remove(handler.getPlayer().getUUID()); INSTANCE.verified.remove(handler.getPlayer().getUUID()); } });

		ServerPlayNetworking.registerGlobalReceiver(Payloads.Input.TYPE, (payload, ctx) -> {
			Game g = INSTANCE;
			if (g == null) return;
			ServerPlayer p = ctx.player();
			PlayerGame pg = g.players.get(p.getUUID());
			if (pg == null) return;
			pg.interactHeld = payload.interactHeld();
			if (pg.downed || pg.dead) { pg.fireHeld = false; pg.ads = false; Grenades.input(g, p, pg, false); return; }
			pg.fireHeld = payload.fireHeld();
			pg.ads = payload.ads();
			Grenades.input(g, p, pg, payload.grenade());
			if (payload.fireClick()) pg.fireClick = true;
			if (payload.prone()) Revive.toggleProne(g, p, pg);
			if (payload.reload() && g.phase != Payloads.PHASE_GAMEOVER) WeaponSystem.startReload(g, p, pg);
			if (payload.melee() && g.phase != Payloads.PHASE_GAMEOVER) WeaponSystem.melee(g, p, pg);
		});

		ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) -> {
			if (entity instanceof ZcZombie) return source.is(DamageTypes.GENERIC_KILL) || source.is(DamageTypes.FELL_OUT_OF_WORLD);
			if (entity instanceof ServerPlayer p && INSTANCE != null && INSTANCE.phase != Payloads.PHASE_IDLE) {
				if (INSTANCE.phase == Payloads.PHASE_GAMEOVER) return false;
				PlayerGame pg = INSTANCE.players.get(p.getUUID());
				if (pg != null && (pg.downed || pg.dead)) return false;
				if (pg != null && INSTANCE.tick < pg.shieldUntil) return false;
				if (pg != null) pg.lastHurtTick = INSTANCE.tick;
				if (source.getEntity() instanceof ZcZombie zz) {
					Cue.ui("evt_player_swiped", p);
					Cue.ui("chr_pain_exhale", p);
					ServerPlayNetworking.send(p, new Payloads.HitDirection(zz.getX(), zz.getZ()));
				}
			}
			return true;
		});
		ServerLivingEntityEvents.ALLOW_DEATH.register((entity, source, amount) -> {
			if (entity instanceof ServerPlayer p && INSTANCE != null && INSTANCE.phase != Payloads.PHASE_IDLE) {
				PlayerGame pg = INSTANCE.players.get(p.getUUID());
				if (pg != null && (pg.downed || pg.dead)) return false;
				if (pg != null && INSTANCE.phase != Payloads.PHASE_GAMEOVER) {
					// with teammates still standing the player goes down and can be revived; alone, Quick Revive is the only save
					if (Revive.othersStanding(INSTANCE, p)) { Revive.down(INSTANCE, p, pg); return false; }
					if (INSTANCE.machines.revive(p, pg)) return false;
				}
				INSTANCE.gameOver(p);
				return false;
			}
			return true;
		});
	}

	// ------------------------------------------------------------------ helpers
	public void cmd(String command) {
		server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSuppressedOutput().withPermission(4).withLevel(level), command);
	}

	private Barrier barrierFor(String windowId) {
		for (Barrier b : barriers) if (b.def.id().equals(windowId)) return b;
		return null;
	}

	/** Runs {@code r} on the server after the given number of ticks. */
	public void later(int ticks, Runnable r) { scheduled.computeIfAbsent(tick + ticks, k -> new ArrayList<>()).add(r); }

	public PlayerGame pg(ServerPlayer p) { return players.computeIfAbsent(p.getUUID(), PlayerGame::new); }

	// ------------------------------------------------------------------ start / restart
	/** Before anyone joins: make the world spawn the diner, so the first spawn is already inside the map. */
	private void prepareSpawn() {
		level = server.overworld();
		int surface = level.getHeight(Heightmap.Types.WORLD_SURFACE, 0, 0);
		origin = new BlockPos(0, surface - 1, 0);
		PlayerSpawn sp = Sheets.PLAYER_SPAWNS.get(0);
		cmd("gamerule spawnRadius 0");
		cmd(String.format("setworldspawn %d %d %d", origin.getX() + sp.x(), origin.getY() + sp.y(), origin.getZ() + sp.z()));
	}

	public void onJoin(ServerPlayer p) {
		if (phase == Payloads.PHASE_IDLE) { boolean lobby = lobbyNext; lobbyNext = false; com.zombiecraft.ZombiecraftMod.LOG.info("Block Ops 2: first player joined, starting {}", lobby ? "the lobby" : "the match"); start(lobby); }
		// the joining player may not be in level.players() yet when start() runs: always make sure they are set up
		if (!players.containsKey(p.getUUID()) || players.get(p.getUUID()).guns[0] == null) resetPlayer(p);
		ServerPlayNetworking.send(p, new Payloads.Place(Sheets.place())); // the lobby, loading picture and sky follow the map the host chose
	}

	/** BO2 models standing in the map. With the local model cache the decor blocks they replace become invisible barriers (or air) so the model is all you see. */
	private void placeProps() {
		for (var p : Sheets.PROPS) {
			ZcProp e = new ZcProp(ZcEntities.PROP, level);
			e.moveTo(origin.getX() + p.x(), origin.getY() + p.y(), origin.getZ() + p.z(), (float) p.yaw(), (float) p.pitch());
			e.setYRot((float) p.yaw());
			e.getEntityData().set(ZcProp.ROLL, (float) p.roll());
			e.getEntityData().set(ZcProp.EXACT, p.exact());
			e.addTag("zc"); e.addTag("zc_scenery");
			e.getEntityData().set(ZcProp.KIND, ZcProp.SCENERY);
			e.getEntityData().set(ZcProp.PAP_WEAPON, p.model());
			e.getEntityData().set(ZcProp.PAP_DEPTH, (float) p.scale());
			level.addFreshEntity(e);
			if (!LocalAssets.models || p.hide() == null || p.hide().equals("none")) continue;
			String[] h = p.hide().split(",");
			int[] c = new int[6];
			for (int i = 0; i < 6; i++) c[i] = Integer.parseInt(h[i].trim());
			for (int x = c[0]; x <= c[3]; x++) for (int y = c[1]; y <= c[4]; y++) for (int z = c[2]; z <= c[5]; z++) {
				BlockPos bp = origin.offset(x, y, z);
				var st = level.getBlockState(bp);
				if (p.fallback() != null && !net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(st.getBlock()).toString().equals(p.fallback())) continue; // only our stand-in blocks
				if (!st.isAir())
					level.setBlock(bp, st.getCollisionShape(level, bp).isEmpty() ? net.minecraft.world.level.block.Blocks.AIR.defaultBlockState() : net.minecraft.world.level.block.Blocks.BARRIER.defaultBlockState(), 3);
			}
		}
	}

	public void start() { start(false); }

	/** (Re)start the whole game: rebuild the map and reset every player. With {@code lobby} players gather at the spawn and the match only begins when the host starts it; otherwise round 1 counts down. */
	public void start(boolean lobby) {
		online = lobby;
		level = server.overworld();
		int surface = level.getHeight(Heightmap.Types.WORLD_SURFACE, 0, 0);
		origin = new BlockPos(0, surface - 1, 0);

		cmd("kill @e[type=zombiecraft:zombie]");
		cmd("kill @e[tag=zc]");
		alive.clear();
		Projectiles.clear();
		Grenades.clear();
		MapBuilder.buildOps(level, origin);
		barriers.clear();
		for (var w : Sheets.WINDOWS) { Barrier b = new Barrier(level, origin, w); b.build(); barriers.add(b); }
		placeWallBuys();
		box = new BoxSystem(this);
		if (pap != null) pap.shutdown();
		pap = Sheets.PAPS.isEmpty() ? null : new PapSystem(this); // a map may have no Pack-a-Punch (Survival Bus Depot)
		if (machines != null) machines.shutdown();
		if (powerups != null) powerups.shutdown();
		machines = new Machines(this);
		doors = new Doors(this);
		placeProps();
		powerups = new PowerUps(this);
		teamEarned = 0;
		scheduled.clear();

		for (String rule : new String[]{"doMobSpawning false", "doDaylightCycle false", "doWeatherCycle false", "naturalRegeneration false",
				"mobGriefing false", "doFireTick false", "keepInventory true", "announceAdvancements false", "doImmediateRespawn true",
				"doInsomnia false", "doPatrolSpawning false", "doTraderSpawning false", "sendCommandFeedback false"})
			cmd("gamerule " + rule);
		cmd("difficulty normal");
		cmd("weather clear");
		var atmo = Sheets.atmosphere(Sheets.place());
		cmd("time set " + (atmo != null && atmo.timeOfDay() >= 0 ? atmo.timeOfDay() : Sheets.sysInt("world_time")));
		cmd(String.format("setworldspawn %d %d %d", origin.getX() + Sheets.PLAYER_SPAWNS.get(0).x(), origin.getY() + 1, origin.getZ() + Sheets.PLAYER_SPAWNS.get(0).z()));

		round = 0; zombiesToSpawn = 0; roundsSurvived = 0;
		startedAt = tick;
		players.clear();
		for (ServerPlayer p : level.players()) resetPlayer(p);
		lobbyCountdown = 0;
		if (lobby) { phase = Payloads.PHASE_LOBBY; return; }
		beginCountdown();
	}

	/** Lobby -> match: everyone in the lobby starts fresh and the first-round countdown runs. */
	private void beginMatch() {
		lobbyCountdown = 0;
		startedAt = tick;
		players.clear();
		for (ServerPlayer p : level.players()) resetPlayer(p);
		beginCountdown();
	}

	private void beginCountdown() {
		phase = Payloads.PHASE_COUNTDOWN;
		Cue.all("mus_zombie_splash_screen", level);
		countdown = Sheets.sysInt("first_round_delay_s") * 20;
		stopAmbience();
		playing = ambience();
		for (String c : playing) Cue.all(c, level);
		ambientTimer = 200;
	}

	public void resetPlayer(ServerPlayer p) {
		PlayerGame pg = new PlayerGame(p.getUUID());
		players.put(p.getUUID(), pg);
		Revive.clear(p);
		pg.points = Sheets.sysInt("start_points");
		pg.instaKill = powerups != null && powerups.instaTicks > 0; // joined or came back while Insta-Kill runs
		pg.grenades = Sheets.sysInt("grenade_start");
		p.getInventory().clearContent();
		p.setGameMode(GameType.ADVENTURE);
		p.setInvulnerable(false);
		p.getAttribute(Attributes.MAX_HEALTH).setBaseValue(Sheets.sys("player_max_health"));
		applyMovement(p);
		p.setHealth(p.getMaxHealth());
		p.getFoodData().setFoodLevel(20);
		WeaponSystem.give(p, pg, 0, Sheets.startWeapon().id(), false);
		PlayerSpawn sp = Sheets.PLAYER_SPAWNS.get(0);
		p.teleportTo(level, origin.getX() + sp.x() + 0.5, origin.getY() + sp.y(), origin.getZ() + sp.z() + 0.5, Set.of(), (float) sp.yaw(), 0f, true);
	}

	private static final net.minecraft.resources.ResourceLocation SPRINT_BOOST = Payloads.id("sprint_boost");

	/** BO2's run speed, jump and gravity (sheets/systems.json player_*) instead of Minecraft's. */
	private static void applyMovement(ServerPlayer p) {
		p.getAttribute(Attributes.MOVEMENT_SPEED).setBaseValue(Sheets.sys("player_walk_speed_attr"));
		p.getAttribute(Attributes.JUMP_STRENGTH).setBaseValue(Sheets.sys("player_jump_strength"));
		p.getAttribute(Attributes.GRAVITY).setBaseValue(Sheets.sys("player_gravity"));
	}

	/** Sprinting is 1.5 times running in BO2; Minecraft's own sprint bonus is 1.3, so the rest is added while the sprint lasts. */
	private static void sprintBoost(ServerPlayer p) {
		var speed = p.getAttribute(Attributes.MOVEMENT_SPEED);
		boolean on = p.isSprinting();
		if (on == (speed.getModifier(SPRINT_BOOST) != null)) return;
		if (on) speed.addTransientModifier(new net.minecraft.world.entity.ai.attributes.AttributeModifier(SPRINT_BOOST, Sheets.sys("player_sprint_scale") / 1.3 - 1.0,
				net.minecraft.world.entity.ai.attributes.AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
		else speed.removeModifier(SPRINT_BOOST);
	}

	private static final net.minecraft.resources.ResourceLocation ADS_SLOW = Payloads.id("ads_slow");

	/** Aiming down the sight walks slower (BO2's adsMoveSpeedScale). */
	private static void adsSlow(ServerPlayer p, PlayerGame pg) {
		var speed = p.getAttribute(Attributes.MOVEMENT_SPEED);
		boolean on = pg.ads && !p.isSprinting() && !pg.downed && !pg.dead;
		if (on == (speed.getModifier(ADS_SLOW) != null)) return;
		if (on) speed.addTransientModifier(new net.minecraft.world.entity.ai.attributes.AttributeModifier(ADS_SLOW, Sheets.sys("ads_move_mult") - 1.0,
				net.minecraft.world.entity.ai.attributes.AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
		else speed.removeModifier(ADS_SLOW);
	}

	private void placeWallBuys() {
		for (WallBuyDef w : Sheets.WALLBUYS) {
			int facing = switch (w.facing()) { case "north" -> 2; case "south" -> 3; case "west" -> 4; default -> 5; };
			double x = origin.getX() + w.x() + 0.5, y = origin.getY() + w.y() + 0.5, z = origin.getZ() + w.z() + 0.5;
			var weapon = Sheets.weapon(w.weaponId());
			if (LocalAssets.models) {
				// BO2 look: the real gun model hung flat on the wall with its blue glow
				var f = net.minecraft.core.Direction.valueOf(w.facing().toUpperCase());
				var gun = new com.zombiecraft.entity.ZcProp(com.zombiecraft.entity.ZcEntities.PROP, level);
				gun.moveTo(x - f.getStepX() * 0.44, y, z - f.getStepZ() * 0.44, Machines.yawOf(w.facing()), 0f);
				gun.setYRot(Machines.yawOf(w.facing()));
				gun.addTag("zc"); gun.addTag("zc_wb:" + w.id());
				gun.getEntityData().set(com.zombiecraft.entity.ZcProp.KIND, com.zombiecraft.entity.ZcProp.WALLGUN);
				gun.getEntityData().set(com.zombiecraft.entity.ZcProp.PAP_WEAPON, w.weaponId());
				level.addFreshEntity(gun);
			}
			// the invisible frame is what the player aims at; it holds the item only when there is no glowing model
			cmd(String.format(Locale.ROOT, "summon item_frame %.2f %.2f %.2f {Facing:%db,Fixed:1b,Invisible:1b,Invulnerable:1b,Silent:1b,ItemDropChance:0f,%sTags:[\"zc\",\"zc_wb:%s\"]}",
					x, y, z, facing, LocalAssets.models ? "" : "Item:{id:\"zombiecraft:" + w.weaponId() + "\",count:1},", w.id()));
		}
	}

	// ------------------------------------------------------------------ rounds
	private void beginRound(int n) {
		round = n;
		RoundRow row = Sheets.round(n);
		zombiesToSpawn = row.zombies();
		spawnCooldown = 40;
		phase = Payloads.PHASE_ACTIVE;
		Revive.respawnDead(this);
		for (PlayerGame pg : players.values()) pg.boardPointsThisRound = 0;
		if (powerups != null) powerups.newRound();
		Cue.all("mus_zombie_round_start", level);
	}

	private void endRound() {
		phase = Payloads.PHASE_INTERMISSION;
		intermission = Sheets.sysInt("between_round_s") * 20;
		Cue.all("mus_zombie_round_over", level);
	}

	private void spawnZombie() {
		RoundRow row = Sheets.round(round);
		List<SpawnDef> usable = Sheets.SPAWNS.stream().filter(sp -> doors == null || doors.windowOpen(sp.window())).toList();
		SpawnDef s = usable.get(rng.nextInt(usable.size()));
		int roll = rng.nextInt(100);
		String tier = roll < row.walkPct() ? "walk" : roll < row.walkPct() + row.runPct() ? "run" : "sprint";
		ZcZombie z = new ZcZombie(ZcEntities.ZOMBIE, level);
		z.moveTo(origin.getX() + s.x() + 0.5, origin.getY() + s.y(), origin.getZ() + s.z() + 0.5, rng.nextFloat() * 360f, 0f);
		z.setup(tier, row.health(), barrierFor(s.window()));
		level.addFreshEntity(z);
		alive.add(z);
		zombiesToSpawn--;
		Cue.at("zmb_zombie_spawn", level, z.position());
	}

	/** Used by the self-test: spawn one zombie at a named spawn point (it goes through that spawn's window like a real one). */
	public ZcZombie spawnTest(String spawnId, String tier, int health) {
		SpawnDef s = Sheets.SPAWNS.stream().filter(x -> x.id().equals(spawnId)).findFirst().orElseThrow();
		ZcZombie z = new ZcZombie(ZcEntities.ZOMBIE, level);
		z.moveTo(origin.getX() + s.x() + 0.5, origin.getY() + s.y(), origin.getZ() + s.z() + 0.5, 0f, 0f);
		z.setup(tier, health, barrierFor(s.window()));
		level.addFreshEntity(z);
		alive.add(z);
		return z;
	}

	public void onZombieKilled(ZcZombie z) {
		alive.remove(z);
		if (powerups != null) powerups.onKill(z.position());
	}

	public void gameOver(ServerPlayer p) {
		if (phase == Payloads.PHASE_GAMEOVER) return;
		phase = Payloads.PHASE_GAMEOVER;
		for (ServerPlayer player : level.players()) WeaponSystem.cancelReload(player, pg(player));
		roundsSurvived = round;
		pg(p).downs++;
		gameOverTicks = Sheets.sysInt("game_over_delay_s") * 20;
		p.setHealth(p.getMaxHealth());
		p.setInvulnerable(true);
		Cue.ui("evt_player_final_hit", p);
		Cue.ui("evt_player_death", p);
		Cue.ui("mus_zombie_game_over", p);
		stopAmbience();
		machines.shutdown();
		if (pap != null) pap.shutdown();
		powerups.shutdown();
		// BO2: the horde stays where it is, standing over you, until the match is over
		for (ZcZombie z : alive) z.setNoAi(true);
		for (ServerPlayer player : level.players()) player.setInvulnerable(true);
	}

	/** After the game over screen: back to the online lobby, or out to the title screen (the bench scripts restart instead). */
	private void endMatch() {
		boolean dev = Boolean.getBoolean("zombiecraft.bench") || Boolean.getBoolean("zombiecraft.feelBench") || Boolean.getBoolean("zombiecraft.papBench");
		if (online) { start(true); return; }
		if (dev) { start(); return; }
		for (ServerPlayer p : level.players()) ServerPlayNetworking.send(p, new Payloads.EndMatch());
		phase = Payloads.PHASE_IDLE;
	}

	// ------------------------------------------------------------------ the tick
	/** The looping beds of a place (its indoor hum, wind, crickets); the odd creak or rustle near a player comes on top. Places without beds of their own get the outdoor ones. */
	private static String[] ambience() {
		return switch (Sheets.place()) {
			case "depot" -> new String[] {"amb_depot_l", "amb_depot_r", "amb_depot_map_light", "amb_flourescent_light", "amb_wind_howl", "amb_crickets", "amb_neon_stdy"};
			case "diner" -> new String[] {"amb_diner_l", "amb_diner_r", "amb_wind_howl", "amb_crickets"};
			default -> new String[] {"amb_wind_howl", "amb_crickets"};
		};
	}
	private String[] playing = new String[0];
	private static final String[] ONE_SHOTS = {"amb_diner_metal_creak", "amb_metal_creak_lgt", "amb_paper_rustle", "amb_wood_creak", "amb_sign_creak", "amb_metal_groan",
			"amb_crows", "amb_wolves", "amb_church_bell", "amb_screams"}; // the last four are far away
	private static final int NEAR_ONE_SHOTS = 6;
	private int ambientTimer;

	private void stopAmbience() {
		for (String c : playing) Cue.stopAll(c, level);
		playing = new String[0];
	}

	public void tick() {
		tick++;
		if (level == null || phase == Payloads.PHASE_IDLE) return;
		if ((phase == Payloads.PHASE_ACTIVE || phase == Payloads.PHASE_INTERMISSION) && --ambientTimer <= 0) {
			ambientTimer = 160 + level.random.nextInt(320);
			for (ServerPlayer p : level.players()) {
				int i = level.random.nextInt(ONE_SHOTS.length), r = i < NEAR_ONE_SHOTS ? 6 : 24;
				Cue.at(ONE_SHOTS[i], level, p.position().add(level.random.nextInt(2 * r + 1) - r, 1, level.random.nextInt(2 * r + 1) - r));
			}
		}

		if (tick - startedAt == 15 && phase == Payloads.PHASE_COUNTDOWN) {
			PlayerSpawn sp = Sheets.PLAYER_SPAWNS.get(0);
			for (ServerPlayer p : level.players())
				if (p.position().distanceToSqr(origin.getX() + sp.x(), origin.getY() + sp.y(), origin.getZ() + sp.z()) > 25 * 25)
					p.teleportTo(level, origin.getX() + sp.x() + 0.5, origin.getY() + sp.y(), origin.getZ() + sp.z() + 0.5, Set.of(), (float) sp.yaw(), 0f, true);
		}
		if (DevTools.ANY) DevTools.tick(this);
		switch (phase) {
			case Payloads.PHASE_LOBBY -> { if (lobbyCountdown > 0 && --lobbyCountdown <= 0) beginMatch(); }
			case Payloads.PHASE_COUNTDOWN -> { if (--countdown <= 0) beginRound(1); }
			case Payloads.PHASE_ACTIVE -> {
				alive.removeIf(z -> z.isRemoved() || !z.isAlive());
				if (zombiesToSpawn > 0 && alive.size() < Sheets.sysInt("max_alive") && --spawnCooldown <= 0) {
					spawnZombie();
					spawnCooldown = Math.max(1, (int) Math.round(Sheets.round(round).spawnDelay() * 20));
				}
				if (zombiesToSpawn <= 0 && alive.isEmpty()) endRound();
			}
			case Payloads.PHASE_INTERMISSION -> { if (--intermission <= 0) beginRound(round + 1); }
			case Payloads.PHASE_GAMEOVER -> { if (--gameOverTicks <= 0) endMatch(); }
			default -> {}
		}

		Projectiles.tick(this);
		Grenades.tick(this);
		if (phase != Payloads.PHASE_GAMEOVER) {
			box.tick();
			if (pap != null) pap.tick();
			machines.tick();
			powerups.tick();
		}
		var due = scheduled.headMap(tick, true);
		for (var list : new ArrayList<>(due.values())) for (Runnable r : list) r.run();
		due.clear();
		Revive.tickAll(this);
		for (ServerPlayer p : level.players()) tickPlayer(p);
		if (tick % 5 == 0) syncRoster();
	}

	/** Everyone's scoreboard line and stance, to every player (the tab screen, and teammates drawn as BO2 characters). */
	private void syncRoster() {
		java.util.List<Payloads.RosterEntry> list = new ArrayList<>();
		for (ServerPlayer p : level.players()) {
			PlayerGame pg = players.get(p.getUUID());
			if (pg == null) continue;
			int stance = pg.dead || p.isSpectator() ? Payloads.RosterEntry.DEAD : pg.downed ? Payloads.RosterEntry.DOWNED : pg.prone ? Payloads.RosterEntry.PRONE : Payloads.RosterEntry.STAND;
			list.add(new Payloads.RosterEntry(p.getUUID(), p.getGameProfile().getName(), pg.points, pg.kills, pg.downs, pg.revives, pg.headshots, stance));
		}
		Payloads.Roster roster = new Payloads.Roster(list);
		for (ServerPlayer p : level.players()) ServerPlayNetworking.send(p, roster);
	}

	private void tickPlayer(ServerPlayer p) {
		PlayerGame pg = pg(p);
		sprintBoost(p);
		adsSlow(p, pg);
		Grenades.cook(this, p, pg);
		if (pg.downed || pg.dead || phase == Payloads.PHASE_LOBBY) pg.prompt = "";
		else if (phase != Payloads.PHASE_GAMEOVER) {
			WeaponSystem.tick(this, p, pg);
			Interactions.update(this, p, pg);
			p.getFoodData().setFoodLevel(20);
			if (tick - pg.lastHurtTick > Sheets.sysInt("regen_delay_ticks") && p.getHealth() < p.getMaxHealth())
				p.heal((float) Sheets.sys("regen_per_tick"));
		}
		if (pg.messageTicks > 0) pg.messageTicks--;
		if (tick % 4 == 0) sync(p, pg);
	}

	private void sync(ServerPlayer p, PlayerGame pg) {
		Gun g = WeaponSystem.active(p, pg);
		int sec = phase == Payloads.PHASE_LOBBY ? (lobbyCountdown + 19) / 20 : phase == Payloads.PHASE_COUNTDOWN ? (countdown + 19) / 20 : phase == Payloads.PHASE_INTERMISSION ? (intermission + 19) / 20 : phase == Payloads.PHASE_GAMEOVER ? (gameOverTicks + 19) / 20 : 0;
		ServerPlayNetworking.send(p, new Payloads.StateSync(phase, round, pg.points, g == null ? -1 : g.mag, g == null ? 0 : g.reserve,
				g == null ? "" : g.displayName(), pg.prompt, pg.messageTicks > 0 ? pg.message : "", pg.interactable,
				zombiesToSpawn + alive.size(), sec, roundsSurvived,
				pg.perks | (machines.power ? Payloads.StateSync.FLAG_POWER : 0) | (pg.drinking ? Payloads.StateSync.FLAG_DRINKING | (pg.drinkPerk << Payloads.StateSync.DRINK_PERK_SHIFT) : 0), powerups.instaTicks / 20, powerups.doubleTicks / 20,
				pg.kills, pg.headshots, pg.downs, pg.revives,
				pg.downed ? (int) Math.max(1, (pg.bleedEnd - tick + 19) / 20) : 0, pg.reviveShow, pg.grenades));
	}
}

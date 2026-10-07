package com.zombiecraft.game;

import com.zombiecraft.entity.ZcEntities;
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
	private final java.util.TreeMap<Long, List<Runnable>> scheduled = new java.util.TreeMap<>();
	private final Random rng = new Random();

	private Game(MinecraftServer server) { this.server = server; }

	// ------------------------------------------------------------------ wiring
	public static void register() {
		ServerLifecycleEvents.SERVER_STARTED.register(s -> { INSTANCE = new Game(s); INSTANCE.prepareSpawn(); });
		ServerLifecycleEvents.SERVER_STOPPING.register(s -> INSTANCE = null);
		ServerTickEvents.END_SERVER_TICK.register(s -> { if (INSTANCE != null) INSTANCE.tick(); });
		ServerPlayConnectionEvents.JOIN.register((handler, sender, s) -> { if (INSTANCE != null) INSTANCE.onJoin(handler.getPlayer()); });
		ServerPlayConnectionEvents.DISCONNECT.register((handler, s) -> { if (INSTANCE != null) INSTANCE.players.remove(handler.getPlayer().getUUID()); });

		ServerPlayNetworking.registerGlobalReceiver(Payloads.Input.TYPE, (payload, ctx) -> {
			Game g = INSTANCE;
			if (g == null) return;
			ServerPlayer p = ctx.player();
			PlayerGame pg = g.players.get(p.getUUID());
			if (pg == null) return;
			pg.interactHeld = payload.interactHeld();
			if (pg.downed || pg.dead) { pg.fireHeld = false; return; }
			pg.fireHeld = payload.fireHeld();
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
		if (phase == Payloads.PHASE_IDLE) start();
		// the joining player may not be in level.players() yet when start() runs: always make sure they are set up
		if (!players.containsKey(p.getUUID()) || players.get(p.getUUID()).guns[0] == null) resetPlayer(p);
	}

	/** (Re)start the whole game: rebuild the map, reset every player, count down to round 1. */
	public void start() {
		level = server.overworld();
		int surface = level.getHeight(Heightmap.Types.WORLD_SURFACE, 0, 0);
		origin = new BlockPos(0, surface - 1, 0);

		cmd("kill @e[type=zombiecraft:zombie]");
		cmd("kill @e[tag=zc]");
		alive.clear();
		Projectiles.clear();
		MapBuilder.buildOps(level, origin);
		barriers.clear();
		for (var w : Sheets.WINDOWS) { Barrier b = new Barrier(level, origin, w); b.build(); barriers.add(b); }
		placeWallBuys();
		box = new BoxSystem(this);
		if (pap != null) pap.shutdown();
		pap = new PapSystem(this);
		if (machines != null) machines.shutdown();
		if (powerups != null) powerups.shutdown();
		machines = new Machines(this);
		doors = new Doors(this);
		powerups = new PowerUps(this);
		teamEarned = 0;
		scheduled.clear();

		for (String rule : new String[]{"doMobSpawning false", "doDaylightCycle false", "doWeatherCycle false", "naturalRegeneration false",
				"mobGriefing false", "doFireTick false", "keepInventory true", "announceAdvancements false", "doImmediateRespawn true",
				"doInsomnia false", "doPatrolSpawning false", "doTraderSpawning false", "sendCommandFeedback false"})
			cmd("gamerule " + rule);
		cmd("difficulty normal");
		cmd("weather clear");
		var atmo = Sheets.ATMOSPHERE.stream().filter(x -> x.id().equals("bus_depot")).findFirst()
				.or(() -> Sheets.ATMOSPHERE.stream().filter(x -> x.id().equals("*")).findFirst());
		cmd("time set " + (atmo.isPresent() && atmo.get().timeOfDay() >= 0 ? atmo.get().timeOfDay() : Sheets.sysInt("world_time")));
		cmd(String.format("setworldspawn %d %d %d", origin.getX() + Sheets.PLAYER_SPAWNS.get(0).x(), origin.getY() + 1, origin.getZ() + Sheets.PLAYER_SPAWNS.get(0).z()));

		round = 0; zombiesToSpawn = 0; roundsSurvived = 0;
		startedAt = tick;
		players.clear();
		for (ServerPlayer p : level.players()) resetPlayer(p);
		phase = Payloads.PHASE_COUNTDOWN;
		countdown = Sheets.sysInt("first_round_delay_s") * 20;
		for (String c : AMBIENCE) { Cue.stopAll(c, level); Cue.all(c, level); }
		ambientTimer = 200;
	}

	public void resetPlayer(ServerPlayer p) {
		PlayerGame pg = new PlayerGame(p.getUUID());
		players.put(p.getUUID(), pg);
		Revive.clear(p);
		pg.points = Sheets.sysInt("start_points");
		p.getInventory().clearContent();
		p.setGameMode(GameType.ADVENTURE);
		p.setInvulnerable(false);
		p.getAttribute(Attributes.MAX_HEALTH).setBaseValue(Sheets.sys("player_max_health"));
		p.setHealth(p.getMaxHealth());
		p.getFoodData().setFoodLevel(20);
		WeaponSystem.give(p, pg, 0, Sheets.startWeapon().id(), false);
		PlayerSpawn sp = Sheets.PLAYER_SPAWNS.get(0);
		p.teleportTo(level, origin.getX() + sp.x() + 0.5, origin.getY() + sp.y(), origin.getZ() + sp.z() + 0.5, Set.of(), (float) sp.yaw(), 0f, true);
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
		Cue.ui("evt_player_death", p);
		Cue.ui("mus_zombie_game_over", p);
		for (String c : AMBIENCE) Cue.stopAll(c, level);
		machines.shutdown();
		if (pap != null) pap.shutdown();
		powerups.shutdown();
		cmd("kill @e[type=zombiecraft:zombie]");
		alive.clear();
	}

	// ------------------------------------------------------------------ the tick
	/** The diner's looping bed (wind left/right, light hum) and the odd creak or rustle near a player. */
	private static final String[] AMBIENCE = {"amb_diner_l", "amb_diner_r", "amb_flourescent_light"};
	private static final String[] ONE_SHOTS = {"amb_diner_metal_creak", "amb_metal_creak_lgt", "amb_paper_rustle"};
	private int ambientTimer;
	private final List<ZcZombie> dbgZ = new java.util.ArrayList<>();

	public void tick() {
		tick++;
		if (level == null || phase == Payloads.PHASE_IDLE) return;
		if ((phase == Payloads.PHASE_ACTIVE || phase == Payloads.PHASE_INTERMISSION) && --ambientTimer <= 0) {
			ambientTimer = 300 + level.random.nextInt(500);
			for (ServerPlayer p : level.players())
				Cue.at(ONE_SHOTS[level.random.nextInt(ONE_SHOTS.length)], level, p.position().add(level.random.nextInt(13) - 6, 1, level.random.nextInt(13) - 6));
		}

		if (tick - startedAt == 15 && phase == Payloads.PHASE_COUNTDOWN) {
			PlayerSpawn sp = Sheets.PLAYER_SPAWNS.get(0);
			for (ServerPlayer p : level.players())
				if (p.position().distanceToSqr(origin.getX() + sp.x(), origin.getY() + sp.y(), origin.getZ() + sp.z()) > 25 * 25)
					p.teleportTo(level, origin.getX() + sp.x() + 0.5, origin.getY() + sp.y(), origin.getZ() + sp.z() + 0.5, Set.of(), (float) sp.yaw(), 0f, true);
		}
		// dev: -Dzombiecraft.debugBox=true stands the player in front of the Mystery Box looking at it (for screenshots)
		if (Boolean.getBoolean("zombiecraft.debugBox") && tick - startedAt == 60 && box != null) {
			net.minecraft.core.Direction f = net.minecraft.core.Direction.valueOf(box.loc().facing().toUpperCase());
			var cp = box.chestPos();
			double px = cp.getX() + 0.5 + f.getStepX() * 2.6, pz = cp.getZ() + 0.5 + f.getStepZ() * 2.6;
			float yaw = (float) Math.toDegrees(Math.atan2(f.getStepX(), -f.getStepZ()));
			for (ServerPlayer p : level.players()) p.teleportTo(level, px, cp.getY(), pz, Set.of(), yaw, 22f, true);
		}
		// dev: -Dzombiecraft.debugTour=true visits a few map viewpoints {x, z, yaw} (sheet frame) and screenshots each (map look checks)
		if (Boolean.getBoolean("zombiecraft.debugTour") && tick - startedAt >= 60 && (tick - startedAt) % 40 % 30 == 0) {
			boolean snap = (tick - startedAt) % 40 == 30;
			int i = (int) ((tick - startedAt - (snap ? 30 : 0)) / 40) - 2;
			double[][] views = {{2, -7, 180}, {-6, -7, -90}, {2, -7, 0}, {0, -16, 0}, {0, -16, 180}, {2, 3, 180}, {-18, -10, 90}};
			if (i >= 0 && i < views.length) for (ServerPlayer p : level.players()) {
				if (snap) ServerPlayNetworking.send(p, new Payloads.Shot("tour_" + i));
				else p.teleportTo(level, origin.getX() + views[i][0] + 0.5, origin.getY() + 1, origin.getZ() + views[i][1] + 0.5, Set.of(), (float) views[i][2], 0f, true);
			}
		}
		// dev: -Dzombiecraft.debugZombies=true puts three free zombies in front of the player, then hits and kills one (screenshots)
		if (Boolean.getBoolean("zombiecraft.debugZombies")) {
			long t = tick - startedAt;
			for (ServerPlayer p : level.players()) {
				PlayerGame pgd = pg(p);
				if (t == 40) { dbgZ.clear(); double yr = Math.toRadians(p.getYRot());
					for (int i = 0; i < 3; i++) {
						double d = 2.6 + i * 1.4, side = (i - 1) * 1.1;
						ZcZombie z = new ZcZombie(ZcEntities.ZOMBIE, level);
						z.moveTo(p.getX() - Math.sin(yr) * d + Math.cos(yr) * side, p.getY(), p.getZ() + Math.cos(yr) * d + Math.sin(yr) * side, p.getYRot() + 180f, 0f);
						z.setup(i == 0 ? "walk" : i == 1 ? "run" : "sprint", 100000, null);
						z.setNoAi(true);
						level.addFreshEntity(z); alive.add(z); dbgZ.add(z);
					} }
				if (t == 70 || t == 100) ServerPlayNetworking.send(p, new Payloads.Shot("zombies_" + t));
				if (t == 110 && !dbgZ.isEmpty()) ZombieHealth.hit(this, p, pgd, dbgZ.get(0), 10, false, false);
				if (t == 112) ServerPlayNetworking.send(p, new Payloads.Shot("zombies_hit"));
				if (t == 120 && dbgZ.size() > 1) { dbgZ.get(1).hp = 1; ZombieHealth.hit(this, p, pgd, dbgZ.get(1), 10, true, false); }
				if (t == 122 || t == 140 || t == 190) ServerPlayNetworking.send(p, new Payloads.Shot("zombies_kill_" + t));
			}
		}
		// dev: -Dzombiecraft.debugPap=true shows Pack-a-Punch holding a gun (upgrading, then ready) and saves screenshots
		if (Boolean.getBoolean("zombiecraft.debugPap") && pap != null && machines != null) {
			long t = tick - startedAt;
			if (t == 50) {
				var d = pap.def();
				var f = net.minecraft.core.Direction.valueOf(d.facing().toUpperCase());
				double px = origin.getX() + (d.x1() + d.x2()) / 2.0 + 0.5 + f.getStepX() * 3.2, pz = origin.getZ() + (d.z1() + d.z2()) / 2.0 + 0.5 + f.getStepZ() * 3.2;
				float yaw = (float) Math.toDegrees(Math.atan2(f.getStepX(), -f.getStepZ()));
				for (ServerPlayer p : level.players()) p.teleportTo(level, px, origin.getY() + d.y1(), pz, Set.of(), yaw, 12f, true);
			}
			if (t == 60) pap.debugShow(PapSystem.State.UPGRADING, "m14");
			if (t == 75 || t == 95) for (ServerPlayer p : level.players()) net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(p, new Payloads.Shot("pap_work_" + t));
			if (t == 100) pap.debugShow(PapSystem.State.READY, "m14");
			if (t == 112 || t == 140) for (ServerPlayer p : level.players()) net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(p, new Payloads.Shot("pap_ready_" + t));
		}
		// dev: -Dzombiecraft.debugDrink=true (with debugPower) buys Juggernog and screenshots the bottle animation
		if (Boolean.getBoolean("zombiecraft.debugDrink") && machines != null) {
			long t = tick - startedAt;
			for (ServerPlayer p : level.players()) {
				if (t == 70) { pg(p).points = 9000; for (var md : Sheets.MACHINES) if (md.perk() != null && md.perk().equals("jug")) machines.use(md, p, pg(p)); }
				if (t == 85 || t == 95 || t == 105 || t == 115 || t == 125) net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(p, new Payloads.Shot("drink_" + t));
			}
		}
		// dev: -Dzombiecraft.debugGun=true puts every gun in the hand in turn (60 ticks each) and saves an idle and a reload screenshot
		if (Boolean.getBoolean("zombiecraft.debugGun")) {
			long t = tick - startedAt - 60;
			var ids = Sheets.WEAPONS.stream().filter(w -> !w.upgrade()).map(com.zombiecraft.sheet.Rows.WeaponDef::id).toList();
			int idx = (int) (t / 70), ph = (int) (t % 70);
			if (t >= 0 && idx < ids.size()) for (ServerPlayer p : level.players()) {
				var gun = WeaponSystem.active(p, pg(p));
				if (ph == 0) { WeaponSystem.give(p, pg(p), 0, ids.get(idx), false); p.getInventory().selected = 0; }
				if (ph == 25) net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(p, new Payloads.Shot("gun_" + ids.get(idx) + "_idle"));
				if (ph == 30 && gun != null) { gun.mag = Math.min(gun.mag, 1); WeaponSystem.startReload(this, p, pg(p)); }
				if (ph == 48) net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(p, new Payloads.Shot("gun_" + ids.get(idx) + "_reload"));
			}
		}
		// dev: -Dzombiecraft.debugPowerups=true lays out every power-up in an arc in front of the player and saves a screenshot
		if (Boolean.getBoolean("zombiecraft.debugPowerups") && powerups != null && (tick - startedAt == 60 || tick - startedAt == 120)) {
			for (ServerPlayer p : level.players()) {
				if (tick - startedAt == 120) { net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(p, new Payloads.Shot("view_powerups")); continue; }
				p.teleportTo(level, p.getX(), p.getY(), p.getZ(), Set.of(), p.getYRot(), 10f, true);
				double yaw = Math.toRadians(p.getYRot());
				PowerUps.Kind[] ks = PowerUps.Kind.values();
				for (int i = 0; i < ks.length; i++) {
					double a = yaw + Math.toRadians((i - (ks.length - 1) / 2.0) * 22);
					powerups.dropNow(ks[i], p.position().add(-Math.sin(a) * 3.2, 0.2, Math.cos(a) * 3.2));
				}
			}
		}
		// dev: -Dzombiecraft.debugView=jug,speed,doubletap,revive,power,pap visits each in turn (100 ticks apiece) and saves a screenshot;
		// -Dzombiecraft.debugPower=true turns the power on first
		String view = System.getProperty("zombiecraft.debugView");
		if (view != null && machines != null && tick - startedAt >= 60) {
			String[] names = view.split(",");
			long rel = tick - startedAt - 60; int idx = (int) (rel / 100), ph = (int) (rel % 100);
			if (idx < names.length && (ph == 0 || ph == 60)) {
				String n = names[idx];
				double px = 0, py = 0, pz = 0; float yaw = 0;
				if (n.equals("pap")) {
					var d = pap.def();
					var f = net.minecraft.core.Direction.valueOf(d.facing().toUpperCase());
					px = origin.getX() + (d.x1() + d.x2()) / 2.0 + 0.5 + f.getStepX() * 4.0; pz = origin.getZ() + (d.z1() + d.z2()) / 2.0 + 0.5 + f.getStepZ() * 4.0;
					py = origin.getY() + d.y1(); yaw = (float) Math.toDegrees(Math.atan2(f.getStepX(), -f.getStepZ()));
				} else for (var md : Sheets.MACHINES) if (md.perk().equals(n) || md.kind().equals(n)) {
					var f = net.minecraft.core.Direction.valueOf(md.facing().toUpperCase());
					px = origin.getX() + md.x() + 0.5 + f.getStepX() * 3.0; pz = origin.getZ() + md.z() + 0.5 + f.getStepZ() * 3.0;
					py = origin.getY() + md.y(); yaw = (float) Math.toDegrees(Math.atan2(f.getStepX(), -f.getStepZ()));
				}
				for (ServerPlayer p : level.players()) {
					if (ph == 0) p.teleportTo(level, px, py, pz, Set.of(), yaw, 8f, true);
					else net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(p, new Payloads.Shot("view_" + n));
				}
			}
		}
		if (Boolean.getBoolean("zombiecraft.debugPower") && machines != null && !machines.power && tick - startedAt == 40)
			for (var md : Sheets.MACHINES) if (md.kind().equals("power")) for (ServerPlayer p : level.players()) machines.use(md, p, pg(p));
		switch (phase) {
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
			case Payloads.PHASE_GAMEOVER -> { if (--gameOverTicks <= 0) start(); }
			default -> {}
		}

		Projectiles.tick(this);
		if (phase != Payloads.PHASE_GAMEOVER) {
			box.tick();
			pap.tick();
			machines.tick();
			powerups.tick();
		}
		var due = scheduled.headMap(tick, true);
		for (var list : new ArrayList<>(due.values())) for (Runnable r : list) r.run();
		due.clear();
		Revive.tickAll(this);
		for (ServerPlayer p : level.players()) tickPlayer(p);
	}

	private void tickPlayer(ServerPlayer p) {
		PlayerGame pg = pg(p);
		if (pg.downed || pg.dead) pg.prompt = "";
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
		int sec = phase == Payloads.PHASE_COUNTDOWN ? (countdown + 19) / 20 : phase == Payloads.PHASE_INTERMISSION ? (intermission + 19) / 20 : 0;
		ServerPlayNetworking.send(p, new Payloads.StateSync(phase, round, pg.points, g == null ? -1 : g.mag, g == null ? 0 : g.reserve,
				g == null ? "" : g.displayName(), pg.prompt, pg.messageTicks > 0 ? pg.message : "", pg.interactable,
				zombiesToSpawn + alive.size(), sec, roundsSurvived,
				pg.perks | (machines.power ? 256 : 0) | (pg.drinking ? 512 | (pg.drinkPerk << 10) : 0), powerups.instaTicks / 20, powerups.doubleTicks / 20,
				pg.kills, pg.headshots, pg.downs, pg.revives,
				pg.downed ? (int) Math.max(1, (pg.bleedEnd - tick + 19) / 20) : 0, pg.reviveShow));
	}
}

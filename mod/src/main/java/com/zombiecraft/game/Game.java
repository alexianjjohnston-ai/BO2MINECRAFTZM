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
			pg.fireHeld = payload.fireHeld();
			if (payload.fireClick()) pg.fireClick = true;
			pg.interactHeld = payload.interactHeld();
			if (payload.reload() && g.phase != Payloads.PHASE_GAMEOVER) WeaponSystem.startReload(g, p, pg);
			if (payload.melee() && g.phase != Payloads.PHASE_GAMEOVER) WeaponSystem.melee(g, p, pg);
		});

		ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) -> {
			if (entity instanceof ZcZombie) return source.is(DamageTypes.GENERIC_KILL) || source.is(DamageTypes.FELL_OUT_OF_WORLD);
			if (entity instanceof ServerPlayer p && INSTANCE != null && INSTANCE.phase != Payloads.PHASE_IDLE) {
				if (INSTANCE.phase == Payloads.PHASE_GAMEOVER) return false;
				PlayerGame pg = INSTANCE.players.get(p.getUUID());
				if (pg != null) pg.lastHurtTick = INSTANCE.tick;
				if (source.getEntity() instanceof ZcZombie) Cue.ui("evt_player_swiped", p);
			}
			return true;
		});
		ServerLivingEntityEvents.ALLOW_DEATH.register((entity, source, amount) -> {
			if (entity instanceof ServerPlayer p && INSTANCE != null && INSTANCE.phase != Payloads.PHASE_IDLE) {
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
		pap = new PapSystem(this);

		for (String rule : new String[]{"doMobSpawning false", "doDaylightCycle false", "doWeatherCycle false", "naturalRegeneration false",
				"mobGriefing false", "doFireTick false", "keepInventory true", "announceAdvancements false", "doImmediateRespawn true",
				"doInsomnia false", "doPatrolSpawning false", "doTraderSpawning false", "sendCommandFeedback false"})
			cmd("gamerule " + rule);
		cmd("difficulty normal");
		cmd("weather clear");
		cmd("time set " + Sheets.sysInt("world_time"));
		cmd(String.format("setworldspawn %d %d %d", origin.getX() + Sheets.PLAYER_SPAWNS.get(0).x(), origin.getY() + 1, origin.getZ() + Sheets.PLAYER_SPAWNS.get(0).z()));

		round = 0; zombiesToSpawn = 0; roundsSurvived = 0;
		startedAt = tick;
		players.clear();
		for (ServerPlayer p : level.players()) resetPlayer(p);
		phase = Payloads.PHASE_COUNTDOWN;
		countdown = Sheets.sysInt("first_round_delay_s") * 20;
	}

	public void resetPlayer(ServerPlayer p) {
		PlayerGame pg = new PlayerGame(p.getUUID());
		players.put(p.getUUID(), pg);
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
			cmd(String.format(Locale.ROOT, "summon item_frame %.2f %.2f %.2f {Facing:%db,Fixed:1b,Invulnerable:1b,Silent:1b,ItemDropChance:0f,Item:{id:\"zombiecraft:%s\",count:1},Tags:[\"zc\",\"zc_wb:%s\"]}",
					x, y, z, facing, w.weaponId(), w.id()));
			cmd(String.format(Locale.ROOT, "summon text_display %.2f %.2f %.2f {text:'{\"text\":\"%s  [%d]\",\"color\":\"gold\"}',billboard:\"center\",alignment:\"center\",Tags:[\"zc\"]}",
					x, y + 0.9, z, weapon.name(), weapon.wallCost()));
		}
	}

	// ------------------------------------------------------------------ rounds
	private void beginRound(int n) {
		round = n;
		RoundRow row = Sheets.round(n);
		zombiesToSpawn = row.zombies();
		spawnCooldown = 40;
		phase = Payloads.PHASE_ACTIVE;
		for (PlayerGame pg : players.values()) pg.boardPointsThisRound = 0;
		Cue.all("mus_zombie_round_start", level);
		for (ServerPlayer p : level.players()) pg(p).say("Round " + n, 80);
	}

	private void endRound() {
		phase = Payloads.PHASE_INTERMISSION;
		intermission = Sheets.sysInt("between_round_s") * 20;
		Cue.all("mus_zombie_round_over", level);
	}

	private void spawnZombie() {
		RoundRow row = Sheets.round(round);
		SpawnDef s = Sheets.SPAWNS.get(rng.nextInt(Sheets.SPAWNS.size()));
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

	public void onZombieKilled(ZcZombie z) { alive.remove(z); }

	public void gameOver(ServerPlayer p) {
		if (phase == Payloads.PHASE_GAMEOVER) return;
		phase = Payloads.PHASE_GAMEOVER;
		roundsSurvived = round;
		gameOverTicks = Sheets.sysInt("game_over_delay_s") * 20;
		p.setHealth(p.getMaxHealth());
		p.setInvulnerable(true);
		Cue.ui("evt_player_death", p);
		cmd("kill @e[type=zombiecraft:zombie]");
		alive.clear();
	}

	// ------------------------------------------------------------------ the tick
	public void tick() {
		tick++;
		if (level == null || phase == Payloads.PHASE_IDLE) return;

		if (tick - startedAt == 15 && phase == Payloads.PHASE_COUNTDOWN) {
			PlayerSpawn sp = Sheets.PLAYER_SPAWNS.get(0);
			for (ServerPlayer p : level.players())
				if (p.position().distanceToSqr(origin.getX() + sp.x(), origin.getY() + sp.y(), origin.getZ() + sp.z()) > 25 * 25)
					p.teleportTo(level, origin.getX() + sp.x() + 0.5, origin.getY() + sp.y(), origin.getZ() + sp.z() + 0.5, Set.of(), (float) sp.yaw(), 0f, true);
		}
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
		}
		for (ServerPlayer p : level.players()) tickPlayer(p);
	}

	private void tickPlayer(ServerPlayer p) {
		PlayerGame pg = pg(p);
		if (phase != Payloads.PHASE_GAMEOVER) {
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
				zombiesToSpawn + alive.size(), sec, roundsSurvived));
	}
}

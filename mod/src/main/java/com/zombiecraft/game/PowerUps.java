package com.zombiecraft.game;

import com.zombiecraft.entity.ZcEntities;
import com.zombiecraft.entity.ZcProp;
import com.zombiecraft.entity.ZcZombie;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * Power-up drops. Rules follow the BO2 scripts: a drop is queued each time the team's total earned points pass a threshold
 * (500 per player + 2000, growing x1.14 each time), it comes out of the next zombie kill, at most 4 per round; each lasts 30 s.
 * Insta-Kill and Double Points run 30 s; Nuke pays 400; Carpenter repairs every window and pays 200.
 */
public final class PowerUps {
	public enum Kind {
		MAX_AMMO("Max Ammo", ZcProp.AMMO, "zmb_full_ammo"),
		INSTA_KILL("Insta-Kill", ZcProp.INSTA, "zmb_insta_kill"),
		DOUBLE_POINTS("Double Points", ZcProp.X2, "zmb_powerup_grabbed"),
		NUKE("Kaboom!", ZcProp.NUKE, "evt_nuke_flash"),
		CARPENTER("Carpenter", ZcProp.CARPENTER, "evt_carpenter");

		final String title, cue;
		final int prop;
		Kind(String title, int prop, String cue) { this.title = title; this.prop = prop; this.cue = cue; }
	}

	public static final int EFFECT_TICKS = 30 * 20;
	private static final int DROP_LIFETIME = 30 * 20, MAX_PER_ROUND = 4;

	private static final class Drop {
		final int id; final Kind kind; final Vec3 pos; int ticks = DROP_LIFETIME; ZcProp prop;
		Drop(int id, Kind kind, Vec3 pos) { this.id = id; this.kind = kind; this.pos = pos; }
		String tag() { return "zc_pu" + id; }
	}

	private final Game game;
	private final ServerLevel level;
	private final List<Drop> drops = new ArrayList<>();
	private int nextId, droppedThisRound;
	private double increment = 2000, scoreToDrop;
	private boolean pending;
	private Kind last;
	/** Ticks left of the timed effects (all players share them). */
	public int instaTicks, doubleTicks;

	public PowerUps(Game game) {
		this.game = game; this.level = game.level;
		scoreToDrop = 500 + increment;
		game.cmd("kill @e[tag=zc_powerup]");
	}

	/** Test/dev: drops a specific power-up right now. */
	public void dropNow(Kind k, Vec3 pos) { spawn(k, pos); }

	public void newRound() { droppedThisRound = 0; }

	/** A zombie died at {@code pos}: if the team has earned enough since the last drop, this kill drops a power-up. */
	public void onKill(Vec3 pos) {
		if (!pending && game.teamEarned >= scoreToDrop) pending = true;
		if (!pending || droppedThisRound >= MAX_PER_ROUND) return;
		Kind k = pick();
		if (k == null) return;
		pending = false; droppedThisRound++; last = k;
		increment *= 1.14;
		scoreToDrop = game.teamEarned + increment;
		spawn(k, pos);
	}

	private Kind pick() {
		List<Kind> options = new ArrayList<>(List.of(Kind.values()));
		options.remove(last);
		if (game.barriers.stream().noneMatch(b -> b.boardsLeft() < b.boardsTotal())) options.remove(Kind.CARPENTER);
		return options.isEmpty() ? null : options.get(level.random.nextInt(options.size()));
	}

	private void spawn(Kind k, Vec3 pos) {
		Drop d = new Drop(nextId++, k, pos.add(0, 0.9, 0));
		drops.add(d);
		d.prop = new ZcProp(ZcEntities.PROP, level);
		d.prop.moveTo(d.pos.x, d.pos.y - 0.5, d.pos.z, 0f, 0f);
		d.prop.addTag("zc"); d.prop.addTag("zc_powerup"); d.prop.addTag(d.tag());
		d.prop.getEntityData().set(ZcProp.KIND, k.prop);
		level.addFreshEntity(d.prop);
		Cue.at("zmb_spawn_powerup", level, d.pos);
		Cue.at("zmb_spawn_powerup_loop", level, d.pos);
	}

	public void tick() {
		for (int i = drops.size() - 1; i >= 0; i--) {
			Drop d = drops.get(i);
			if (--d.ticks <= 0) { remove(d); drops.remove(i); continue; }
			// blink in the last 5 seconds
			if (d.ticks < 100 && d.ticks % 10 == 0) d.prop.getEntityData().set(ZcProp.BUSY, d.ticks % 20 != 0);
			for (ServerPlayer p : level.players()) {
				if (p.distanceToSqr(d.pos.x, d.pos.y - 0.4, d.pos.z) < 2.25) { collect(d, p); remove(d); drops.remove(i); break; }
			}
		}
		if (instaTicks > 0 && --instaTicks == 0) { Cue.stopAll("zmb_insta_kill_loop", level); setInsta(false); }
		if (doubleTicks > 0 && --doubleTicks == 0) { Cue.stopAll("zmb_double_point_loop", level); Cue.all("zmb_points_loop_off", level); }
	}

	private void remove(Drop d) {
		if (d.prop != null) d.prop.discard();
		if (drops.stream().noneMatch(x -> x != d)) Cue.stopAll("zmb_spawn_powerup_loop", level);
	}

	private void setInsta(boolean on) { for (PlayerGame pg : game.players.values()) pg.instaKill = on; }

	private void collect(Drop d, ServerPlayer taker) {
		Cue.all("zmb_powerup_grabbed", level);
		Cue.all(d.kind.cue, level);
		switch (d.kind) {
			case MAX_AMMO -> { for (ServerPlayer p : level.players()) { for (var g : game.pg(p).guns) if (g != null) g.refill(); game.pg(p).grenades = com.zombiecraft.sheet.Sheets.sysInt("grenade_max"); } }
			case INSTA_KILL -> {
				if (instaTicks == 0) Cue.all("zmb_insta_kill_loop", level);
				instaTicks = EFFECT_TICKS; setInsta(true);
			}
			case DOUBLE_POINTS -> {
				if (doubleTicks == 0) Cue.all("zmb_double_point_loop", level);
				doubleTicks = EFFECT_TICKS;
			}
			case NUKE -> {
				Cue.all("evt_nuked", level);
				for (ZcZombie z : new ArrayList<>(game.alive)) {
					z.hp = 0;
					game.onZombieKilled(z);
					z.kill(level);
				}
				for (ServerPlayer p : level.players()) game.pg(p).earn(400);
			}
			case CARPENTER -> {
				for (var b : game.barriers) while (b.repair()) { /* every board back */ }
				for (ServerPlayer p : level.players()) game.pg(p).earn(200);
				game.later(60, () -> Cue.all("evt_carpenter_end", level));
			}
		}
	}

	public void shutdown() {
		for (Drop d : drops) if (d.prop != null) d.prop.discard();
		drops.clear();
		for (String c : new String[] {"zmb_spawn_powerup_loop", "zmb_insta_kill_loop", "zmb_double_point_loop"}) Cue.stopAll(c, level);
		instaTicks = doubleTicks = 0;
		setInsta(false);
	}
}

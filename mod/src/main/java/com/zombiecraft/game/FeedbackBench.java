package com.zombiecraft.game;

import com.zombiecraft.ZombiecraftMod;
import com.zombiecraft.entity.ZcEntities;
import com.zombiecraft.entity.ZcZombie;
import com.zombiecraft.net.Payloads;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.concurrent.atomic.AtomicReferenceArray;

/** Short integrated-client regression run, opt-in only. Counts actual decoded client receipts, not sends. */
public final class FeedbackBench {
	private FeedbackBench() {}
	private static final boolean ON = Boolean.getBoolean("zombiecraft.feelBench");
	private static final Path OUT = Path.of("zc-feedback-bench.txt");
	private static final AtomicIntegerArray RECEIVED = new AtomicIntegerArray(4);
	private static final AtomicReferenceArray<Payloads.CombatFeedback> LAST = new AtomicReferenceArray<>(4);
	private static final int[] before = new int[4];
	private static volatile boolean finished;
	private static int step, passes, fails, serverTicks;
	private static long until, entered, captureAt;
	private static String capture;
	private static ZcZombie target;
	private static PlayerGame.Gun gun;
	private static int magBefore, reserveBefore, pointsBefore;
	private static double hpBefore;

	/** Called only from the real client CombatFeedback receiver. */
	public static void received(Payloads.CombatFeedback feedback) {
		if (!ON || feedback.kind() < 0 || feedback.kind() >= RECEIVED.length()) return;
		LAST.set(feedback.kind(), feedback);
		RECEIVED.incrementAndGet(feedback.kind());
	}

	/** The client may close itself once the final screenshots have had time to save. */
	public static boolean finished() { return ON && finished; }

	public static void register() {
		if (!ON) return;
		try { Files.writeString(OUT, "Combat feedback regression: actual integrated-client receipts\n"); }
		catch (IOException e) { ZombiecraftMod.LOG.error("Cannot initialize feedback bench log", e); }
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			if (finished) return;
			try {
				if (++serverTicks > 900 && step < 15) {
					check(false, "timeout", "step=" + step);
					complete();
				}
				tick();
			} catch (Throwable t) {
				check(false, "bench-crash", t.toString());
				ZombiecraftMod.LOG.error("Feedback bench crashed", t);
				complete();
			}
		});
	}

	private static void line(String text) {
		ZombiecraftMod.LOG.info("[feedback-bench] {}", text);
		try { Files.writeString(OUT, text + System.lineSeparator(), StandardOpenOption.CREATE, StandardOpenOption.APPEND); }
		catch (IOException e) { ZombiecraftMod.LOG.error("Cannot write feedback bench log", e); }
	}

	private static void check(boolean ok, String name, String detail) {
		if (ok) passes++; else fails++;
		line((ok ? "PASS " : "FAIL ") + name + " | " + detail);
	}

	private static void complete() {
		line((fails == 0 ? "PASS " : "FAIL ") + "BENCH-DONE | " + passes + " passed, " + fails + " failed");
		step = 16;
		until = serverTicks + 20;
	}

	private static void next(Game g, int nextStep, int delay) {
		step = nextStep; entered = g.tick; until = g.tick + delay;
	}

	private static void baseline() {
		for (int i = 0; i < before.length; i++) before[i] = RECEIVED.get(i);
	}

	private static int delta(int kind) { return RECEIVED.get(kind) - before[kind]; }

	private static boolean waitFor(Game g, int kind) {
		return delta(kind) < 1 && g.tick - entered < 40;
	}

	private static void screenshot(Game g, String name) { capture = "feel_" + name; captureAt = g.tick + 1; }

	private static Vec3 stand(Game g) { return new Vec3(g.origin.getX() - 21.5, g.origin.getY() + 1, g.origin.getZ() - 10.5); }
	private static Vec3 targetPos(Game g) { return stand(g).add(0, 0, 4); }

	private static void face(ServerPlayer p, Vec3 from, Vec3 to) {
		double dx = to.x - from.x, dy = to.y - (from.y + 1.62), dz = to.z - from.z;
		p.connection.teleport(from.x, from.y, from.z, (float) Math.toDegrees(Math.atan2(-dx, dz)),
				(float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz))));
	}

	private static void spawn(Game g, int health) {
		if (target != null) { target.discard(); g.alive.remove(target); }
		target = new ZcZombie(ZcEntities.ZOMBIE, g.level);
		Vec3 pos = targetPos(g);
		target.moveTo(pos.x, pos.y, pos.z, 180f, 0f);
		target.setup("walk", health, null);
		target.setNoAi(true);
		g.level.addFreshEntity(target);
		g.alive.add(target);
	}

	private static void fire(PlayerGame pg) {
		pg.fireHeld = false; pg.prevFire = false; pg.fireClick = true;
	}

	private static boolean lastIs(int kind, String weapon, int slot) {
		var f = LAST.get(kind);
		return f != null && f.weapon().equals(weapon) && f.slot() == slot;
	}

	private static void tick() {
		Game g = Game.INSTANCE;
		if (step == 16 && serverTicks >= until) { finished = true; return; }
		if (g == null || g.level == null || g.phase == Payloads.PHASE_IDLE || g.level.players().isEmpty()) return;
		ServerPlayer p = g.level.players().get(0);
		PlayerGame pg = g.pg(p);
		if (capture != null && g.tick >= captureAt) {
			ServerPlayNetworking.send(p, new Payloads.Shot(capture));
			capture = null;
		}
		if (step == 16) return;
		if (step == 0 && g.tick - g.startedAt < 25) return;
		// Keep real weapon/projectile ticks running, while preventing natural spawns and round changes.
		g.phase = Payloads.PHASE_COUNTDOWN; g.countdown = 1200; g.zombiesToSpawn = 1;
		pg.fireHeld = false; pg.interactHeld = false;
		for (ZcZombie z : g.alive) { z.setNoAi(true); z.setDeltaMovement(Vec3.ZERO); }
		if (g.tick < until) return;

		switch (step) {
			case 0 -> {
				p.setInvulnerable(true);
				g.alive.forEach(ZcZombie::discard); g.alive.clear(); Projectiles.clear();
				WeaponSystem.give(p, pg, 1, "m14", false);
				WeaponSystem.give(p, pg, 0, "m1911", false);
				gun = pg.guns[0]; pg.fireCooldown = 0; pg.burstLeft = 0;
				spawn(g, 500);
				face(p, stand(g), stand(g).add(4, 1.75, 0));
				next(g, 1, 6);
			}
			case 1 -> {
				baseline(); magBefore = gun.mag; hpBefore = target.hp;
				fire(pg); next(g, 2, 3);
			}
			case 2 -> {
				if (waitFor(g, Payloads.FEEDBACK_SHOT)) return;
				check(gun.mag == magBefore - 1 && delta(Payloads.FEEDBACK_SHOT) == 1 && lastIs(Payloads.FEEDBACK_SHOT, "m1911", 0),
						"accepted-shot", "ammo=" + magBefore + "->" + gun.mag + " client shots=" + delta(Payloads.FEEDBACK_SHOT));
				check(target.hp == hpBefore && delta(Payloads.FEEDBACK_HIT) == 0, "miss-no-hit-feedback", "hp=" + target.hp + " client hits=" + delta(Payloads.FEEDBACK_HIT));
				baseline(); magBefore = gun.mag; pg.fireCooldown = 1.0;
				fire(pg); next(g, 3, 6);
			}
			case 3 -> {
				check(gun.mag == magBefore && delta(Payloads.FEEDBACK_SHOT) == 0, "cooldown-blocks-feedback", "ammo=" + gun.mag + " client shots=" + delta(Payloads.FEEDBACK_SHOT));
				gun.mag = 0; gun.reserve = 0; pg.fireCooldown = 0; baseline();
				fire(pg); next(g, 4, 6);
			}
			case 4 -> {
				check(gun.mag == 0 && delta(Payloads.FEEDBACK_SHOT) == 0 && delta(Payloads.FEEDBACK_RELOAD_START) == 0,
						"dry-trigger-no-shot", "client shots=" + delta(Payloads.FEEDBACK_SHOT) + " reload starts=" + delta(Payloads.FEEDBACK_RELOAD_START));
				gun.refill(); pg.fireCooldown = 0;
				face(p, stand(g), target.position().add(0, 1.75, 0));
				next(g, 5, 4);
			}
			case 5 -> {
				baseline(); hpBefore = target.hp; pointsBefore = pg.points;
				fire(pg); next(g, 6, 1);
			}
			case 6 -> {
				if (waitFor(g, Payloads.FEEDBACK_HIT) || waitFor(g, Payloads.FEEDBACK_SHOT)) return;
				var hit = LAST.get(Payloads.FEEDBACK_HIT);
				check(target.hp < hpBefore && target.hp > 0 && pg.points > pointsBefore && delta(Payloads.FEEDBACK_HIT) == 1
						&& hit != null && hit.headshot() && !hit.killed() && lastIs(Payloads.FEEDBACK_HIT, "m1911", 0),
						"confirmed-headshot", "hp=" + hpBefore + "->" + target.hp + " client hit=" + hit);
				screenshot(g, "01_headshot"); next(g, 7, 6);
			}
			case 7 -> {
				target.hp = 1; baseline(); pg.fireCooldown = 0;
				face(p, stand(g), target.position().add(0, 1.75, 0));
				fire(pg); next(g, 8, 1);
			}
			case 8 -> {
				if (waitFor(g, Payloads.FEEDBACK_HIT)) return;
				var hit = LAST.get(Payloads.FEEDBACK_HIT);
				check(target.hp <= 0 && delta(Payloads.FEEDBACK_HIT) == 1 && hit != null && hit.headshot() && hit.killed(),
						"confirmed-kill", "hp=" + target.hp + " client hit=" + hit);
				screenshot(g, "02_kill");
				target.discard(); g.alive.remove(target);
				gun.mag = 2; gun.reserve = 10; magBefore = gun.mag; reserveBefore = gun.reserve; baseline();
				WeaponSystem.startReload(g, p, pg); next(g, 9, 4);
			}
			case 9 -> {
				if (waitFor(g, Payloads.FEEDBACK_RELOAD_START)) return;
				var reload = LAST.get(Payloads.FEEDBACK_RELOAD_START);
				check(pg.reloadSlot == 0 && delta(Payloads.FEEDBACK_RELOAD_START) == 1 && reload != null
						&& reload.durationTicks() == pg.reloadEnd - pg.reloadStart && lastIs(Payloads.FEEDBACK_RELOAD_START, "m1911", 0),
						"reload-start-delivered", "slot=" + pg.reloadSlot + " client reload=" + reload);
				check(g.tick < pg.reloadEnd && gun.mag == magBefore && gun.reserve == reserveBefore,
						"reload-no-early-ammo", "ammo=" + gun.mag + "/" + gun.reserve + " ticks left=" + (pg.reloadEnd - g.tick));
				screenshot(g, "03_reload");
				next(g, 10, (int) Math.max(1, pg.reloadEnd - g.tick + 1));
			}
			case 10 -> {
				if (waitFor(g, Payloads.FEEDBACK_RELOAD_STOP)) return;
				int transfer = Math.min(gun.magSize() - magBefore, reserveBefore);
				check(pg.reloadSlot == -1 && gun.mag == magBefore + transfer && gun.reserve == reserveBefore - transfer
						&& delta(Payloads.FEEDBACK_RELOAD_STOP) == 1 && delta(Payloads.FEEDBACK_SHOT) == 0,
						"reload-complete", "ammo=" + gun.mag + "/" + gun.reserve + " client stops=" + delta(Payloads.FEEDBACK_RELOAD_STOP));
				gun.mag = 2; gun.reserve = 10; baseline();
				WeaponSystem.startReload(g, p, pg); next(g, 11, 4);
			}
			case 11 -> {
				if (waitFor(g, Payloads.FEEDBACK_RELOAD_START)) return;
				check(pg.reloadSlot == 0 && delta(Payloads.FEEDBACK_RELOAD_START) == 1, "cancel-reload-started", "client starts=" + delta(Payloads.FEEDBACK_RELOAD_START));
				p.getInventory().selected = 1;
				next(g, 12, 1);
			}
			case 12 -> {
				if (waitFor(g, Payloads.FEEDBACK_RELOAD_STOP)) return;
				check(pg.reloadSlot == -1 && gun.mag == 2 && gun.reserve == 10 && delta(Payloads.FEEDBACK_RELOAD_STOP) == 1,
						"switch-cancels-reload", "ammo=" + gun.mag + "/" + gun.reserve + " client stops=" + delta(Payloads.FEEDBACK_RELOAD_STOP));
				screenshot(g, "04_reload_cancelled"); next(g, 13, 5);
			}
			case 13 -> {
				WeaponSystem.give(p, pg, 0, "ray_gun", false); gun = pg.guns[0];
				spawn(g, 150); face(p, stand(g), target.position().add(0, 1.0, 0));
				pg.fireCooldown = 0; baseline(); magBefore = gun.mag;
				fire(pg); next(g, 14, 1);
			}
			case 14 -> {
				if (waitFor(g, Payloads.FEEDBACK_HIT) || waitFor(g, Payloads.FEEDBACK_SHOT)) return;
				var hit = LAST.get(Payloads.FEEDBACK_HIT);
				check(gun.mag == magBefore - 1 && target.hp <= 0 && delta(Payloads.FEEDBACK_SHOT) == 1
						&& delta(Payloads.FEEDBACK_HIT) == 1 && hit != null && !hit.headshot() && hit.killed()
						&& lastIs(Payloads.FEEDBACK_HIT, "ray_gun", 0),
						"projectile-hit", "ammo=" + gun.mag + " hp=" + target.hp + " client hit=" + hit);
				screenshot(g, "05_projectile_hit"); next(g, 15, 5);
			}
			case 15 -> complete();
			default -> {}
		}
	}
}

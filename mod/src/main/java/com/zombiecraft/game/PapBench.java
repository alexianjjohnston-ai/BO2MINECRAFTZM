package com.zombiecraft.game;

import com.zombiecraft.ZombiecraftMod;
import com.zombiecraft.entity.ZcProp;
import com.zombiecraft.entity.ZcZombie;
import com.zombiecraft.net.Payloads;
import com.zombiecraft.sheet.Sheets;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.Direction;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Locale;

/** Opt-in integrated-client test of real F targeting, the full PaP cycle, and cleanup. */
public final class PapBench {
	private PapBench() {}
	private static final boolean ON = Boolean.getBoolean("zombiecraft.papBench");
	private static final Path OUT = Path.of("zc-pap-bench.txt");
	private static volatile boolean finished;
	private static int step, passes, fails, serverTicks;
	private static long until, entered;
	private static PapSystem beforeReset;
	private static ZcProp propBeforeReset;

	/** Suppress physical input while the scripted F-key edges are being exercised. */
	public static boolean controlsInput() { return ON && !finished; }
	public static boolean finished() { return ON && finished && !Boolean.getBoolean("zombiecraft.papBenchHold"); }

	public static void register() {
		if (!ON) return;
		try { Files.writeString(OUT, "Pack-a-Punch regression: normal Game/Interactions ticks and client screenshots\n"); }
		catch (IOException e) { ZombiecraftMod.LOG.error("Cannot initialize PaP bench log", e); }
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			if (finished) return;
			try {
				serverTicks++;
				if (step != 99 && serverTicks > 2400) {
					check(false, "timeout", "step=" + step);
					complete();
				}
				tick();
			} catch (Throwable t) {
				check(false, "bench-crash", t.toString());
				ZombiecraftMod.LOG.error("PaP bench crashed", t);
				complete();
			}
		});
	}

	private static void line(String text) {
		ZombiecraftMod.LOG.info("[pap-bench] {}", text);
		try { Files.writeString(OUT, text + System.lineSeparator(), StandardOpenOption.CREATE, StandardOpenOption.APPEND); }
		catch (IOException e) { ZombiecraftMod.LOG.error("Cannot write PaP bench log", e); }
	}

	private static void check(boolean ok, String name, String detail) {
		if (ok) passes++; else fails++;
		line((ok ? "PASS " : "FAIL ") + name + " | " + detail);
	}

	private static void complete() {
		if (step == 99) return;
		line((fails == 0 ? "PASS " : "FAIL ") + "BENCH-DONE | " + passes + " passed, " + fails + " failed");
		if (Game.INSTANCE != null) Game.INSTANCE.players.values().forEach(pg -> pg.interactHeld = false);
		step = 99;
		until = serverTicks + 30; // let the last screenshot finish before the client exits
	}

	private static void next(Game g, int next, int delay) {
		step = next; entered = g.tick; until = g.tick + delay;
	}

	private static void shot(ServerPlayer p, String name) { ServerPlayNetworking.send(p, new Payloads.Shot("pap_" + name)); }

	private static AABB area(Game g) {
		var d = g.pap.def();
		return new AABB(net.minecraft.world.phys.Vec3.atLowerCornerOf(g.origin.offset(Math.min(d.x1(), d.x2()), Math.min(d.y1(), d.y2()), Math.min(d.z1(), d.z2()))),
				net.minecraft.world.phys.Vec3.atLowerCornerOf(g.origin.offset(Math.max(d.x1(), d.x2()) + 1, Math.max(d.y1(), d.y2()) + 1, Math.max(d.z1(), d.z2()) + 1))).inflate(3);
	}

	private static ZcProp prop(Game g) {
		return g.level.getEntitiesOfClass(ZcProp.class, area(g), p -> p.getTags().contains("zc_pap")).stream().findFirst().orElse(null);
	}

	private static boolean visualState(Game g, int expected) {
		ZcProp p = prop(g);
		return p != null && p.getEntityData().get(ZcProp.PAP_STATE) == expected
				&& p.getEntityData().get(ZcProp.BUSY) == (expected == 1)
				&& (expected == 0 ? p.getEntityData().get(ZcProp.PAP_WEAPON).isEmpty()
						: p.getEntityData().get(ZcProp.PAP_WEAPON).equals("m14")
						&& p.getEntityData().get(ZcProp.PAP_DURATION) > 0
						&& p.getEntityData().get(ZcProp.PAP_SINCE) <= g.level.getGameTime());
	}

	private static boolean noFloatingDisplay(Game g) {
		return g.level.getEntitiesOfClass(net.minecraft.world.entity.Display.ItemDisplay.class, area(g),
				e -> e.getTags().contains("zc_papdisp")).isEmpty();
	}

	private static void faceMachine(Game g, ServerPlayer p) { faceMachine(g, p, 2.0); }

	private static void faceMachine(Game g, ServerPlayer p, double distance) {
		var d = g.pap.def();
		Direction f = Direction.valueOf(d.facing().toUpperCase(Locale.ROOT));
		AABB box = g.pap.targetBounds();
		double cx = (box.minX + box.maxX) / 2, cz = (box.minZ + box.maxZ) / 2;
		double halfDepth = (f.getAxis() == Direction.Axis.X ? box.maxX - box.minX : box.maxZ - box.minZ) / 2;
		Vec3 front = new Vec3(cx + f.getStepX() * halfDepth, box.minY + Math.min(1.3, (box.maxY - box.minY) * 0.65),
				cz + f.getStepZ() * halfDepth);
		Vec3 from = new Vec3(front.x + f.getStepX() * distance, g.origin.getY() + Math.min(d.y1(), d.y2()), front.z + f.getStepZ() * distance);
		double dx = front.x - from.x, dy = front.y - (from.y + p.getEyeHeight()), dz = front.z - from.z;
		p.connection.teleport(from.x, from.y, from.z, (float) Math.toDegrees(Math.atan2(-dx, dz)),
				(float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz))));
	}

	private static String detail(Game g, PlayerGame pg) {
		return "state=" + g.pap.state + " points=" + pg.points + " slot0=" + (pg.guns[0] == null ? "empty" : pg.guns[0].displayName());
	}

	private static void tick() {
		if (step == 99) { if (serverTicks >= until) finished = true; return; }
		Game g = Game.INSTANCE;
		if (g == null || g.level == null || g.phase == Payloads.PHASE_IDLE || g.level.players().isEmpty()) return;
		if (step == 0 && g.tick - g.startedAt < 120) return;
		ServerPlayer p = g.level.players().get(0);
		PlayerGame pg = g.pg(p);
		// Keep regular interactions and machine ticks, with no zombie or round interference.
		g.phase = Payloads.PHASE_COUNTDOWN; g.countdown = 1200; g.zombiesToSpawn = 1;
		pg.fireHeld = false; pg.fireClick = false;
		if (g.tick < until) return;
		int cost = Sheets.sysInt("pap_cost");

		switch (step) {
			case 0 -> {
				p.setInvulnerable(true);
				g.alive.forEach(ZcZombie::discard); g.alive.clear(); Projectiles.clear();
				p.getInventory().clearContent(); pg.guns[1] = null;
				WeaponSystem.give(p, pg, 0, "m14", false);
				g.machines.power = false; pg.points = cost + 1000;
				pg.interactHeld = false; pg.prevInteract = false;
				faceMachine(g, p); next(g, 1, 12);
			}
			case 1 -> {
				check(Interactions.find(g, p).kind() == Interactions.Kind.PAP && pg.prompt.contains("power"),
						"front-target", "target=" + Interactions.find(g, p).kind() + " prompt=" + pg.prompt);
				BlockPos obstruction = BlockPos.containing(p.getEyePosition().add(p.getViewVector(1).scale(0.9)));
				var original = g.level.getBlockState(obstruction);
				g.level.setBlock(obstruction, Blocks.STONE.defaultBlockState(), 3);
				try { check(Interactions.find(g, p).kind() != Interactions.Kind.PAP, "blocked-line-of-sight", "stone between player and machine blocks use"); }
				finally { g.level.setBlock(obstruction, original, 3); }
				faceMachine(g, p, Sheets.sys("interact_reach") + 1.0);
				check(Interactions.find(g, p).kind() != Interactions.Kind.PAP, "outside-reach", "machine farther than configured reach");
				faceMachine(g, p);
				pg.interactHeld = true; next(g, 2, 3);
			}
			case 2 -> {
				pg.interactHeld = false;
				check(g.pap.state == PapSystem.State.IDLE && pg.points == cost + 1000 && pg.guns[0] != null,
						"power-denial", detail(g, pg));
				g.machines.power = true; pg.points = cost - 1; next(g, 3, 5);
			}
			case 3 -> { pg.interactHeld = true; next(g, 4, 3); }
			case 4 -> {
				pg.interactHeld = false;
				check(g.pap.state == PapSystem.State.IDLE && pg.points == cost - 1 && pg.guns[0] != null,
						"insufficient-points", detail(g, pg));
				pg.points = cost + 1000; next(g, 5, 6);
			}
			case 5 -> {
				check(visualState(g, 0) && prop(g).getEntityData().get(ZcProp.POWERED), "powered-idle", "prop state matches machine");
				shot(p, "01_powered_idle"); next(g, 6, 8);
			}
			case 6 -> { pg.interactHeld = true; next(g, 7, 9); }
			case 7 -> {
				check(g.pap.state == PapSystem.State.UPGRADING && pg.points == 1000 && pg.guns[0] == null,
						"pay-and-intake", detail(g, pg));
				check(visualState(g, 1) && noFloatingDisplay(g), "intake-visual-state", "synchronized intake, no old floating display");
				shot(p, "02_intake"); next(g, 8, 16);
			}
			case 8 -> {
				check(pg.points == 1000 && pg.guns[0] == null, "held-use-pays-once", detail(g, pg));
				pg.interactHeld = false; next(g, 9, 3);
			}
			case 9 -> { pg.interactHeld = true; next(g, 10, 4); }
			case 10 -> {
				pg.interactHeld = false;
				check(g.pap.state == PapSystem.State.UPGRADING && pg.points == 1000 && pg.guns[0] == null,
						"busy-use-ignored", detail(g, pg));
				shot(p, "03_processing"); next(g, 11, 1);
			}
			case 11 -> {
				if (g.pap.state != PapSystem.State.READY && g.tick - entered < Sheets.sysInt("pap_upgrade_ticks") + 40) return;
				check(g.pap.state == PapSystem.State.READY && visualState(g, 2), "ready-state", detail(g, pg));
				next(g, 12, 24); // capture after the return animation clears the machine mouth
			}
			case 12 -> { shot(p, "04_ready"); next(g, 13, 6); }
			case 13 -> { pg.interactHeld = true; next(g, 14, 4); }
			case 14 -> {
				pg.interactHeld = false;
				var gun = WeaponSystem.active(p, pg);
				check(g.pap.state == PapSystem.State.IDLE && gun != null && gun.pap && gun.weapon.equals("m14")
						&& gun.mag == gun.magSize() && gun.reserve == gun.reserveMax() && pg.points == 1000,
						"collect-upgraded-gun", detail(g, pg));
				check(visualState(g, 0) && noFloatingDisplay(g), "collection-cleanup", "idle visual state, no stale display");
				shot(p, "05_completed"); next(g, 15, 10);
			}
			case 15 -> { pg.points = cost + 1000; pg.interactHeld = true; next(g, 16, 3); }
			case 16 -> {
				pg.interactHeld = false;
				check(g.pap.state == PapSystem.State.IDLE && pg.points == cost + 1000 && pg.guns[0] != null && pg.guns[0].pap,
						"already-upgraded-denial", detail(g, pg));
				WeaponSystem.give(p, pg, 0, "m14", false); next(g, 17, 5);
			}
			case 17 -> { pg.interactHeld = true; next(g, 18, 3); }
			case 18 -> {
				pg.interactHeld = false;
				check(g.pap.state == PapSystem.State.UPGRADING && pg.points == 1000, "timeout-cycle-start", detail(g, pg));
				next(g, 19, 1);
			}
			case 19 -> {
				if (g.pap.state != PapSystem.State.READY && g.tick - entered < Sheets.sysInt("pap_upgrade_ticks") + 40) return;
				check(g.pap.state == PapSystem.State.READY, "timeout-cycle-ready", detail(g, pg));
				next(g, 20, Sheets.sysInt("pap_pickup_timeout_s") * 20 + 3);
			}
			case 20 -> {
				check(g.pap.state == PapSystem.State.IDLE && pg.guns[0] == null && pg.points == 1000
						&& visualState(g, 0) && noFloatingDisplay(g), "pickup-timeout-cleanup", detail(g, pg));
				WeaponSystem.give(p, pg, 0, "m14", false); pg.points = cost + 1000; next(g, 21, 5);
			}
			case 21 -> { pg.interactHeld = true; next(g, 22, 4); }
			case 22 -> {
				pg.interactHeld = false;
				check(g.pap.state == PapSystem.State.UPGRADING, "reset-during-upgrade-start", detail(g, pg));
				beforeReset = g.pap; propBeforeReset = prop(g);
				g.start(); next(g, 23, 10);
			}
			case 23 -> {
				check(beforeReset.state == PapSystem.State.IDLE && propBeforeReset != null && propBeforeReset.isRemoved()
						&& g.pap != beforeReset && g.pap.state == PapSystem.State.IDLE && visualState(g, 0) && noFloatingDisplay(g),
						"round-reset-cleanup", "old=" + beforeReset.state + " new=" + g.pap.state);
				g.machines.power = true; faceMachine(g, p); next(g, 24, 6);
			}
			case 24 -> complete();
			default -> {}
		}
	}
}

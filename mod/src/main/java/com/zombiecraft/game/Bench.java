package com.zombiecraft.game;

import com.zombiecraft.ZombiecraftMod;
import com.zombiecraft.entity.ZcEntities;
import com.zombiecraft.entity.ZcZombie;
import com.zombiecraft.net.Payloads;
import com.zombiecraft.sheet.Rows.BoxDef;
import com.zombiecraft.sheet.Rows.WallBuyDef;
import com.zombiecraft.sheet.Sheets;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Scripted self-test, only active with -Dzombiecraft.bench. It drives the server game directly (no human input), checks the results
 * and writes PASS/FAIL lines to run/zc-bench.txt. It also asks the client to save real in-game screenshots at key moments.
 */
public final class Bench {
	private Bench() {}

	private static final boolean ON = System.getProperty("zombiecraft.bench") != null;
	private static int step, sub, passes, fails;
	private static long until;
	private static int shots, boxUses, ticksInStep;
	private static ZcZombie target;
	private static int pointsBefore;
	private static String weaponBefore;
	private static int locBefore;
	private static boolean sawTeddy;
	private static Path out;

	public static void register() {
		if (!ON) return;
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			try { tick(); } catch (Throwable t) { log(false, "bench-crash", String.valueOf(t)); ZombiecraftMod.LOG.error("bench crashed", t); step = 999; }
		});
	}

	private static void log(boolean ok, String name, String detail) {
		if (ok) passes++; else fails++;
		String line = (ok ? "PASS " : "FAIL ") + name + " | " + detail;
		ZombiecraftMod.LOG.info("[bench] {}", line);
		try {
			if (out == null) out = Path.of("zc-bench.txt");
			Files.writeString(out, line + System.lineSeparator(), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
		} catch (IOException ignored) {}
	}

	private static void shot(ServerPlayer p, String name) { ServerPlayNetworking.send(p, new Payloads.Shot(name)); }

	private static void next(int s, int delayTicks) { step = s; sub = 0; ticksInStep = 0; until = Game.INSTANCE.tick + delayTicks; }

	private static void face(ServerPlayer p, Vec3 from, Vec3 to) {
		double dx = to.x - from.x, dy = to.y - (from.y + 1.62), dz = to.z - from.z;
		float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
		float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
		p.connection.teleport(from.x, from.y, from.z, yaw, pitch);
	}

	private static com.zombiecraft.sheet.Rows.MachineDef machine(String id) { return Sheets.MACHINES.stream().filter(m -> m.id().equals(id)).findFirst().orElseThrow(); }

	private static Vec3 abs(Game g, double x, double y, double z) { return new Vec3(g.origin.getX() + x, g.origin.getY() + y, g.origin.getZ() + z); }

	private static void tick() {
		Game g = Game.INSTANCE;
		if (g == null || g.level == null || g.phase == Payloads.PHASE_IDLE) return;
		List<ServerPlayer> ps = g.level.players();
		if (ps.isEmpty()) return;
		ServerPlayer p = ps.get(0);
		PlayerGame pg = g.pg(p);
		ticksInStep++;
		if (g.tick < until) return;

		switch (step) {
			case 0 -> { // wait for round 1
				if (g.phase == Payloads.PHASE_ACTIVE) {
					p.setInvulnerable(true);
					log(g.round == 1 && g.zombiesToSpawn + g.alive.size() == Sheets.round(1).zombies(), "round-start", "round=" + g.round + " zombies=" + (g.zombiesToSpawn + g.alive.size()) + " expected=" + Sheets.round(1).zombies());
					var g0 = pg.guns[0]; log(g0 != null && g0.mag == 8 && g0.reserve == 32, "start-pistol-8/32", "m1911 ammo=" + (g0 == null ? "none" : g0.mag + "/" + g0.reserve));
					shot(p, "01_round1");
					next(1, 60);
				}
			}
			case 1 -> { // shooting a free zombie in front of the player
				if (sub == 0) {
					g.zombiesToSpawn = Math.max(g.zombiesToSpawn, 1); // keep the round open
					Vec3 pos = abs(g, 2, 1, -7);
					ZcZombie z = new ZcZombie(ZcEntities.ZOMBIE, g.level);
					Vec3 zp = abs(g, -21.5, 1, -6.5);
					z.moveTo(zp.x, zp.y, zp.z, 180f, 0f);
					z.setup("walk", 150, null);
					z.setNoAi(true);
					g.level.addFreshEntity(z);
					g.alive.add(z);
					target = z; pointsBefore = pg.points; shots = 0; sub = 1;
					p.connection.teleport(pos.x, pos.y, pos.z, 0f, 0f);
					until = g.tick + 10;
				} else {
					Vec3 pos = abs(g, -21.5, 1, -10.5);
					face(p, pos, new Vec3(target.getX(), target.getY() + 1.75, target.getZ()));
					if (!target.isAlive() || target.hp <= 0) {
						int gained = pg.points - pointsBefore;
						log(shots <= 12 && gained >= 100, "shoot-kill", "shots=" + shots + " points +" + gained + " (expect >= 100: kill 50 + head 50, plus hits)");
						shot(p, "02_shoot");
						next(2, 20);
					} else if (shots >= 14) {
						log(false, "shoot-kill", "zombie still alive after " + shots + " shots hp=" + target.hp);
						next(2, 20);
					} else if (ticksInStep % 6 == 0) {
						var gun = WeaponSystem.active(p, pg);
						if (gun != null && gun.mag > 0) { pg.fireClick = true; shots++; }
						else if (gun != null) WeaponSystem.startReload(g, p, pg);
					}
				}
			}
			case 2 -> { // wall-buy: gun, then ammo
				WallBuyDef wb = Sheets.WALLBUYS.get(0); // Olympia
				double fx = wb.facing().equals("east") ? 1 : wb.facing().equals("west") ? -1 : 0, fz = wb.facing().equals("south") ? 1 : wb.facing().equals("north") ? -1 : 0;
				Vec3 stand = abs(g, wb.x() + 0.5 + fx * 2.6, 1, wb.z() + 0.5 + fz * 2.6);
				Vec3 frame = abs(g, wb.x() + 0.5, wb.y() + 0.5, wb.z() + 0.5);
				if (sub == 0) { pg.points = 1000; weaponBefore = pg.guns[0].weapon; face(p, stand, frame); sub = 1; until = g.tick + 5; }
				else if (sub == 1) { face(p, stand, frame); pg.interactHeld = true; sub = 2; until = g.tick + 3; }
				else if (sub == 2) {
					pg.interactHeld = false;
					boolean has = WeaponSystem.slotHolding(pg, wb.weaponId()) >= 0;
					log(has && pg.points == 1000 - Sheets.weapon(wb.weaponId()).wallCost(), "wallbuy-gun", wb.weaponId() + " owned=" + has + " points=" + pg.points + " (expect " + (1000 - Sheets.weapon(wb.weaponId()).wallCost()) + ")");
					shot(p, "03_wallbuy");
					int slot = WeaponSystem.slotHolding(pg, wb.weaponId());
					if (slot >= 0) { var bg = pg.guns[slot]; log(bg.reserve == bg.base().startReserve(), "start-ammo", bg.weapon + " reserve=" + bg.reserve + " (expect " + bg.base().startReserve() + ")"); }
					if (slot >= 0) { pg.guns[slot].mag = 0; pg.guns[slot].reserve = 0; }
					sub = 3; until = g.tick + 5;
				} else if (sub == 3) { face(p, stand, frame); pg.interactHeld = true; sub = 4; until = g.tick + 3; }
				else {
					pg.interactHeld = false;
					int slot = WeaponSystem.slotHolding(pg, wb.weaponId());
					var gun = slot >= 0 ? pg.guns[slot] : null;
					boolean full = gun != null && gun.mag == gun.magSize() && gun.reserve == gun.reserveMax();
					log(full && pg.points == 500 - (int) (Sheets.weapon(wb.weaponId()).wallCost() * Sheets.sys("wallbuy_ammo_ratio")), "wallbuy-ammo", "refilled=" + full + " points=" + pg.points);
					next(3, 20);
				}
			}
			case 3 -> { // Mystery Box until the teddy bear moves it
				BoxSystem box = g.box;
				BoxDef loc = box.loc();
				BlockPos cp = box.chestPos();
				Vec3 center = new Vec3(cp.getX() + 0.5, cp.getY() + 0.5, cp.getZ() + 0.5);
				int dx = loc.facing().equals("east") ? 2 : loc.facing().equals("west") ? -2 : 0;
				int dz = loc.facing().equals("south") ? 2 : loc.facing().equals("north") ? -2 : 0;
				Vec3 stand = new Vec3(cp.getX() + 0.5 + dx * 0.8, g.origin.getY() + 1, cp.getZ() + 0.5 + dz * 0.8);
				switch (sub) {
					case 0 -> { pg.points = 9000; locBefore = g.box.loc().x(); sawTeddy = false; boxUses = 0; face(p, stand, center); sub = 1; until = g.tick + 5; }
					case 1 -> { // pay
						if (box.state == BoxSystem.State.CLOSED) {
							face(p, stand, center); pointsBefore = pg.points; pg.interactHeld = true; sub = 2; until = g.tick + 3;
						} else if (box.state == BoxSystem.State.GONE) { until = g.tick + 10; }
						else { until = g.tick + 5; }
					}
					case 2 -> {
						pg.interactHeld = false; boxUses++;
						log(box.state == BoxSystem.State.ROLLING && pg.points == pointsBefore - Sheets.sysInt("box_cost"), "box-pay", "use " + boxUses + " state=" + box.state + " points " + pointsBefore + " -> " + pg.points);
						if (boxUses == 1) shot(p, "04_box_rolling");
						sub = 3;
					}
					case 3 -> { // wait for the result
						if (box.state == BoxSystem.State.OFFER) {
							if (boxUses == 1) shot(p, "05_box_offer");
							face(p, stand, center); pg.interactHeld = true; weaponBefore = pg.guns[0] == null ? "" : pg.guns[0].weapon; sub = 4; until = g.tick + 3;
						} else if (box.state == BoxSystem.State.TEDDY) {
							shot(p, "06_box_teddy"); sawTeddy = true; sub = 5; log(true, "box-teddy", "teddy after " + boxUses + " uses");
						}
					}
					case 4 -> {
						pg.interactHeld = false;
						log(box.state == BoxSystem.State.CLOSED, "box-take", "box state after taking = " + box.state + ", guns = " + (pg.guns[0] == null ? "-" : pg.guns[0].weapon) + "," + (pg.guns[1] == null ? "-" : pg.guns[1].weapon));
						sub = boxUses >= 14 ? 6 : 1; until = g.tick + 10;
					}
					case 5 -> { // wait for the box to move
						if (box.state == BoxSystem.State.CLOSED) {
							boolean moved = g.box.loc().x() != locBefore;
							boolean chestThere = g.level.getBlockState(g.box.chestPos()).is(Blocks.CHEST) || g.level.getBlockState(g.box.chestPos()).is(Blocks.BARRIER);
							log(moved && chestThere && pg.points >= 0, "box-moves", "location changed=" + moved + " chest at new spot=" + chestThere + " after " + boxUses + " uses");
							shot(p, "07_box_moved");
							next(4, 20);
						}
					}
					default -> { log(false, "box-teddy", "no teddy bear in 14 uses"); next(4, 20); }
				}
			}
			case 4 -> { // Pack-a-Punch
				var d = g.pap.def();
				Vec3 stand = abs(g, (d.x1() + d.x2()) / 2.0 + 0.5, 1, d.z2() + 2.4);
				Vec3 machine = abs(g, (d.x1() + d.x2()) / 2.0 + 0.5, 2.2, d.z2() + 0.5);
				switch (sub) {
					case 0 -> {
						WeaponSystem.give(p, pg, 0, "m14", false);
						if (g.machines != null) g.machines.power = true; // the machine refuses to work without power
						pg.points = 6000; face(p, stand, machine); sub = 1; until = g.tick + 5;
					}
					case 1 -> { face(p, stand, machine); pg.interactHeld = true; sub = 2; until = g.tick + 3; }
					case 2 -> {
						pg.interactHeld = false;
						log(g.pap.state == PapSystem.State.UPGRADING && pg.points == 1000 && pg.guns[0] == null, "pap-start", "state=" + g.pap.state + " points=" + pg.points + " gun removed=" + (pg.guns[0] == null));
						sub = 3;
					}
					case 3 -> {
						if (g.pap.state == PapSystem.State.READY) { shot(p, "08_pap_ready"); face(p, stand, machine); pg.interactHeld = true; sub = 4; until = g.tick + 3; }
						else if (ticksInStep > 900) { log(false, "pap-take", "the machine never became ready (state " + g.pap.state + ")"); next(5, 20); }
					}
					default -> {
						pg.interactHeld = false;
						var gun = WeaponSystem.active(p, pg);
						log(gun != null && gun.pap && gun.weapon.equals("m14") && g.pap.state == PapSystem.State.IDLE, "pap-take", "gun=" + (gun == null ? "none" : gun.displayName()) + " mag=" + (gun == null ? 0 : gun.mag) + "/" + (gun == null ? 0 : gun.reserve));
						shot(p, "09_pap_gun");
						next(5, 20);
					}
				}
			}
			case 5 -> { // window repair
				Barrier b = g.barriers.stream().filter(x -> g.doors != null && !g.doors.windowOpen(x.def.id())).findFirst().orElse(g.barriers.get(0)); // a locked room's window: no zombie is tearing it meanwhile
				Vec3 stand = new Vec3(b.insideSpot.x, b.insideSpot.y, b.insideSpot.z);
				switch (sub) {
					case 0 -> { while (b.repair()) { } b.tear(); b.tear(); b.tear(); pg.points = 0; pg.boardPointsThisRound = 0; face(p, stand, b.center); sub = 1; until = g.tick + 5; }
					case 1 -> { shot(p, "10_window_torn"); face(p, stand, b.center); pg.interactHeld = true; sub = 2; ticksInStep = 0; }
					default -> {
						if (b.boardsLeft() >= b.boardsTotal() || ticksInStep > 200) {
							pg.interactHeld = false;
							log(b.boardsLeft() == b.boardsTotal() && pg.points == 30, "window-repair", "boards " + b.boardsLeft() + "/" + b.boardsTotal() + " points=" + pg.points + " (expect 30)");
							shot(p, "11_window_repaired");
							next(6, 20);
						}
					}
				}
			}
			case 6 -> { // a real zombie goes through a window
				if (sub == 0) {
					target = g.spawnTest("s1a", "run", 150); sub = 1; ticksInStep = 0;
					// keep the player away from the window so the zombie is not distracted
					Vec3 pos = abs(g, -21.5, 1, -10.5); p.connection.teleport(pos.x, pos.y, pos.z, 0f, 0f);
				} else {
					if (target.stage == 1 && shots == 0) { shots = 1; shot(p, "12_zombie_tearing"); }
					if (target.stage == 3) {
						log(true, "window-entry", "zombie came in through " + target.barrier.def.id() + " after " + ticksInStep / 20 + " s, boards left " + target.barrier.boardsLeft() + "/" + target.barrier.boardsTotal());
						target.discard(); g.alive.remove(target);
						next(7, 20);
					} else if (ticksInStep > 1800) {
						var sp = target.barrier.outsideSpot; StringBuilder around = new StringBuilder();
						for (int dz = -2; dz <= 1; dz++) for (int dy = 0; dy <= 2; dy++) around.append(g.level.getBlockState(BlockPos.containing(sp.x, sp.y - 1 + dy, sp.z + dz)).getBlock().getDescriptionId().replace("block.minecraft.", "")).append(dy == 2 ? " | " : ",");
						log(false, "window-entry", "zombie stuck at stage " + target.stage + " after 90 s at " + target.position() + " spot=" + sp + " boards=" + target.barrier.boardsLeft() + "/" + target.barrier.boardsTotal() + " alive=" + target.isAlive() + " noAi=" + target.isNoAi() + " others=" + g.alive.size() + " path=" + (target.getNavigation().getPath() == null ? "none" : target.getNavigation().getPath().getNodeCount() + " nodes") + " around(z-2..z+1, y-1..y+1)=" + around);
						target.discard(); g.alive.remove(target);
						next(7, 20);
					}
				}
			}
			case 7 -> { // round flow
				if (sub == 0) {
					g.alive.forEach(z -> z.discard()); g.alive.clear(); g.zombiesToSpawn = 0;
					sub = 1; until = g.tick + 5;
				} else if (sub == 1) {
					log(g.phase == Payloads.PHASE_INTERMISSION, "round-end", "phase=" + g.phase + " (3 = intermission)");
					sub = 2;
				} else if (g.phase == Payloads.PHASE_ACTIVE) {
					log(g.round == 2 && g.zombiesToSpawn + g.alive.size() == Sheets.round(2).zombies(), "round-2", "round=" + g.round + " zombies=" + (g.zombiesToSpawn + g.alive.size()) + " expected " + Sheets.round(2).zombies());
					shot(p, "13_round2"); g.later(30, () -> shot(p, "13b_round_tally"));
					next(8, 40);
				}
			}
			case 8 -> { // game over and restart
				if (sub == 0) { g.gameOver(p); sub = 1; until = g.tick + 20; }
				else if (sub == 1) { log(g.phase == Payloads.PHASE_GAMEOVER, "game-over", "phase=" + g.phase); shot(p, "14_game_over"); sub = 2; }
				else if (g.phase == Payloads.PHASE_COUNTDOWN) {
					boolean boardsFull = g.barriers.stream().allMatch(b -> b.boardsLeft() == b.boardsTotal());
					log(g.round == 0 && boardsFull && pg.points == Sheets.sysInt("start_points") && g.alive.isEmpty(), "restart", "round=" + g.round + " boardsFull=" + boardsFull + " points=" + pg.points);
					shot(p, "15_restarted");
					next(9, 40);
				}
			}
			case 9 -> { // power switch and perks (called directly: where the machines stand is the map's business)
				var jug = machine("m_jug"); var speed = machine("m_speed"); var dtap = machine("m_doubletap"); var rev = machine("m_revive"); var power = machine("m_power");
				switch (sub) {
					case 0 -> {
						pg.points = 20000; int before = pg.points;
						g.machines.use(jug, p, pg);
						log(!g.machines.power && pg.perks == 0 && pg.points == before, "perk-needs-power", "perks=" + pg.perks + " points=" + pg.points);
						sub = 1;
					}
					case 1 -> { g.machines.use(power, p, pg); log(g.machines.power, "power-on", "power=" + g.machines.power); sub = 2; }
					case 2 -> { g.machines.use(jug, p, pg); g.later(10, () -> shot(p, "16_drink_up")); g.later(26, () -> shot(p, "17_drink_tip")); sub = 3; until = g.tick + 70; }
					case 3 -> {
						log((pg.perks & 1) != 0 && p.getMaxHealth() > Sheets.sys("player_max_health") * 2.4 && pg.points == 17500, "perk-jug", "perks=" + pg.perks + " maxHealth=" + p.getMaxHealth() + " points=" + pg.points);
						log(pg.fireCooldown <= 0 && !pg.drinking, "perk-can-fire-after-drink", "fireCooldown=" + pg.fireCooldown + " drinking=" + pg.drinking);
						g.machines.use(speed, p, pg); sub = 4; until = g.tick + 70;
					}
					case 4 -> {
						log((pg.perks & 2) != 0 && pg.perkReloadFactor == 0.5, "perk-speed", "reloadFactor=" + pg.perkReloadFactor);
						g.machines.use(dtap, p, pg); sub = 5; until = g.tick + 70;
					}
					case 5 -> {
						log((pg.perks & 4) != 0 && pg.perkFireFactor < 1.0, "perk-doubletap", "fireFactor=" + pg.perkFireFactor);
						g.machines.use(rev, p, pg); sub = 6; until = g.tick + 70;
					}
					case 6 -> {
						log((pg.perks & 8) != 0 && pg.points == 12000, "perk-revive-buy", "perks=" + pg.perks + " points=" + pg.points);
						p.setHealth(1f);
						boolean saved = g.machines.revive(p, pg);
						log(saved && p.getHealth() >= p.getMaxHealth() - 0.5f && (pg.perks & 8) == 0, "perk-revive-save", "saved=" + saved + " health=" + p.getHealth() + " perks=" + pg.perks);
						next(10, 20);
					}
					default -> {}
				}
			}
			case 10 -> { // power-ups: drop each one on the player and let the pickup logic collect it
				PowerUps.Kind[] kinds = {PowerUps.Kind.DOUBLE_POINTS, PowerUps.Kind.INSTA_KILL, PowerUps.Kind.MAX_AMMO, PowerUps.Kind.NUKE, PowerUps.Kind.CARPENTER};
				if (sub >= kinds.length * 3) { next(11, 20); break; }
				PowerUps.Kind k = kinds[sub / 3];
				switch (sub % 3) {
					case 0 -> { // set up and drop
						if (k == PowerUps.Kind.MAX_AMMO) { pg.guns[0].mag = 0; pg.guns[0].reserve = 0; }
						if (k == PowerUps.Kind.NUKE) { g.zombiesToSpawn = Math.max(g.zombiesToSpawn, 1); g.spawnTest("s1a", "walk", 150); pg.points = 0; }
						if (k == PowerUps.Kind.CARPENTER) { g.barriers.get(0).tear(); g.barriers.get(0).tear(); pg.points = 0; }
						if (k == PowerUps.Kind.DOUBLE_POINTS) pg.points = 0;
						g.powerups.dropNow(k, p.position().add(0, -0.5, 0));
						sub++; until = g.tick + 6;
					}
					case 1 -> { // collected by walking into it; check the effect
						switch (k) {
							case DOUBLE_POINTS -> { pg.earn(50); log(g.powerups.doubleTicks > 0 && pg.points == 100, "powerup-double", "points=" + pg.points + " ticks=" + g.powerups.doubleTicks); g.powerups.doubleTicks = 1; }
							case INSTA_KILL -> { log(g.powerups.instaTicks > 0 && pg.instaKill, "powerup-insta", "instaTicks=" + g.powerups.instaTicks); g.powerups.instaTicks = 1; }
							case MAX_AMMO -> log(pg.guns[0].mag == pg.guns[0].magSize() && pg.guns[0].reserve == pg.guns[0].reserveMax(), "powerup-maxammo", "ammo=" + pg.guns[0].mag + "/" + pg.guns[0].reserve);
							case NUKE -> log(g.alive.isEmpty() && pg.points == 400, "powerup-nuke", "alive=" + g.alive.size() + " points=" + pg.points);
							case CARPENTER -> log(g.barriers.get(0).boardsLeft() == g.barriers.get(0).boardsTotal() && pg.points == 200, "powerup-carpenter", "boards=" + g.barriers.get(0).boardsLeft() + " points=" + pg.points);
						}
						sub++; until = g.tick + 6;
					}
					default -> sub++;
				}
			}
			case 11 -> { // stance, going down and bleeding out (a lone player: the last one down ends the game)
				var scale = p.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.SCALE);
				switch (sub) {
					case 0 -> { Revive.toggleProne(g, p, pg); sub = 1; until = g.tick + 5; }
					case 1 -> {
						log(pg.prone && scale.getValue() < 0.55, "prone-down", "prone=" + pg.prone + " scale=" + scale.getValue());
						Revive.toggleProne(g, p, pg); sub = 2; until = g.tick + 5;
					}
					case 2 -> {
						log(!pg.prone && scale.getValue() > 0.99, "prone-stand", "prone=" + pg.prone + " scale=" + scale.getValue());
						Revive.down(g, p, pg); sub = 3; until = g.tick + 5;
					}
					case 3 -> {
						log(pg.downed && scale.getValue() < 0.55 && Revive.isDowned(p), "downed", "downed=" + pg.downed + " scale=" + scale.getValue());
						pg.bleedEnd = g.tick + 1; sub = 4; until = g.tick + 10;
					}
					case 4 -> { log(pg.dead && g.phase == Payloads.PHASE_GAMEOVER, "bleedout-last-player-ends-game", "dead=" + pg.dead + " phase=" + g.phase); sub = 5; }
					case 5 -> {
						if (g.phase != Payloads.PHASE_COUNTDOWN) break;
						PlayerGame np = g.pg(p);
						log(!np.dead && !np.downed && !p.isSpectator() && scale.getValue() > 0.99, "restart-clears-downed", "dead=" + np.dead + " scale=" + scale.getValue());
						next(12, 20);
					}
					default -> {}
				}
			}
			case 12 -> {
				log(fails == 0, "BENCH-DONE", passes + " passed, " + fails + " failed");
				step = 999;
			}
			default -> {}
		}
	}
}

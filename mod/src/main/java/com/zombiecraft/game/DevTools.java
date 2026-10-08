package com.zombiecraft.game;

import com.zombiecraft.entity.ZcEntities;
import com.zombiecraft.entity.ZcZombie;
import com.zombiecraft.net.Payloads;
import com.zombiecraft.sheet.Sheets;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Scripted views for screenshots, switched on with {@code -PdevProps=debugX} (see README). Everything here is dev only: when no switch is set
 * {@link #ANY} is false and the game never calls in.
 */
final class DevTools {
	private DevTools() {}

	private static final boolean BOX = Boolean.getBoolean("zombiecraft.debugBox");
	private static final boolean DIE = Boolean.getBoolean("zombiecraft.debugDie");
	private static final boolean DRINK = Boolean.getBoolean("zombiecraft.debugDrink");
	private static final boolean GUN = Boolean.getBoolean("zombiecraft.debugGun");
	private static final boolean PAP = Boolean.getBoolean("zombiecraft.debugPap");
	private static final boolean POWER = Boolean.getBoolean("zombiecraft.debugPower");
	private static final boolean POWERUPS = Boolean.getBoolean("zombiecraft.debugPowerups");
	private static final boolean TOUR = Boolean.getBoolean("zombiecraft.debugTour");
	private static final boolean ZOMBIES = Boolean.getBoolean("zombiecraft.debugZombies");
	private static final String VIEW = System.getProperty("zombiecraft.debugView");
	static final boolean ANY = VIEW != null || BOX || DIE || DRINK || GUN || PAP || POWER || POWERUPS || TOUR || ZOMBIES;

	private static final List<double[]> tourViews = new ArrayList<>();
	private static final List<String> tourNames = new ArrayList<>();
	private static final List<ZcZombie> dbgZ = new ArrayList<>();

	private static void tourAdd(String name, double x, double z, String facing, double dist, double pitch) {
		var d = net.minecraft.core.Direction.valueOf(facing.toUpperCase());
		tourViews.add(new double[] {x + 0.5 + d.getStepX() * dist, z + 0.5 + d.getStepZ() * dist, Machines.yawOf(d.getOpposite().getName()), pitch});
		tourNames.add(name);
	}

	private static void tourBuild() {
		tourAdd("spawn", 0, 0, "south", 0, 0);
		if (!Sheets.PLAYER_SPAWNS.isEmpty()) { var ps = Sheets.PLAYER_SPAWNS.get(0); tourViews.set(0, new double[] {ps.x() + 0.5, ps.z() + 0.5, ps.yaw(), 0}); }  // looks the way the player starts, from where they start
		for (var m : Sheets.MACHINES) tourAdd("machine " + m.id(), m.x(), m.z(), m.facing(), 3.2, 8);
		for (var b : Sheets.BOXES) tourAdd("box " + b.id(), b.x(), b.z(), b.facing(), 3.5, 12);
		for (var p : Sheets.PAPS) tourAdd("pap " + p.id(), (p.x1() + p.x2()) / 2.0, (p.z1() + p.z2()) / 2.0, p.facing(), 4, 8);
		for (var w : Sheets.WALLBUYS) tourAdd("wallbuy " + w.id() + " " + w.weaponId(), w.x(), w.z(), w.facing(), 3, 0);
		for (var d : Sheets.DOORS) { var dir = d.x1() == d.x2() ? "east" : "south"; tourAdd("door " + d.id(), (d.x1() + d.x2()) / 2.0, (d.z1() + d.z2()) / 2.0, dir, 3.5, 0); }
		for (var pr : Sheets.PROPS) if (pr.exact()) tourAdd("prop " + pr.id(), pr.x() - 0.5, pr.z() - 0.5, "south", 8, 12); // BO2-placed props: stand south of each, looking north
		for (var w : Sheets.WINDOWS) {
			boolean horiz = w.wall().equals("N") || w.wall().equals("S");
			double cx = horiz ? w.a() + w.width() / 2.0 : w.fixed(), cz = horiz ? w.fixed() : w.a() + w.width() / 2.0;
			String inside = switch (w.wall()) { case "N" -> "south"; case "S" -> "north"; case "E" -> "west"; default -> "east"; };
			tourAdd("window " + w.id(), cx - 0.5, cz - 0.5, inside, 3, 0);
		}
		// outside the play area: four views from 12 up at the middle of each side of the barrier ring, looking away from the map (what the player sees beyond the cut)
		var ring = Sheets.MAP_OPS.stream().filter(o -> o.id().equals("barrier_ring")).findFirst();
		if (ring.isPresent()) {
			var r = ring.get(); double mx = (r.x1() + r.x2()) / 2.0, mz = (r.z1() + r.z2()) / 2.0;
			tourViews.add(new double[] {mx, r.z1() - 0.5, 180, 18, 12}); tourNames.add("outside north");
			tourViews.add(new double[] {mx, r.z2() + 1.5, 0, 18, 12}); tourNames.add("outside south");
			tourViews.add(new double[] {r.x1() - 0.5, mz, 90, 18, 12}); tourNames.add("outside west");
			tourViews.add(new double[] {r.x2() + 1.5, mz, 270, 18, 12}); tourNames.add("outside east");
		}
	}


	static void tick(Game g) {
		var level = g.level;
		long tick = g.tick, startedAt = g.startedAt;
		var origin = g.origin;
		var box = g.box; var pap = g.pap; var machines = g.machines; var powerups = g.powerups; var doors = g.doors;
	// dev: -Dzombiecraft.debugDie=true ends the match after 12 s and screenshots the game over screen (zc-die1/2.png); the normal end flow then runs
		if (DIE && !level.players().isEmpty()) {
			long t = tick - startedAt;
			ServerPlayer first = level.players().get(0);
			if (t == 240 && g.phase != Payloads.PHASE_GAMEOVER) g.gameOver(first);
			if (t == 240 + 40 || t == 240 + 160) ServerPlayNetworking.send(first, new Payloads.Shot(t < 300 ? "die1" : "die2"));
		}
	// dev: -Dzombiecraft.debugBox=true stands the player in front of the Mystery Box looking at it (for screenshots)
		if (BOX && tick - startedAt == 60 && box != null) {
			net.minecraft.core.Direction f = net.minecraft.core.Direction.valueOf(box.loc().facing().toUpperCase());
			var cp = box.chestPos();
			double px = cp.getX() + 0.5 + f.getStepX() * 2.6, pz = cp.getZ() + 0.5 + f.getStepZ() * 2.6;
			float yaw = (float) Math.toDegrees(Math.atan2(f.getStepX(), -f.getStepZ()));
			for (ServerPlayer p : level.players()) p.teleportTo(level, px, cp.getY(), pz, Set.of(), yaw, 22f, true);
		}
	// dev: -Dzombiecraft.debugTour=true stands in front of every machine, box, Pack-a-Punch, wall gun, door and window in turn and screenshots each
		// (tour_N.png; the list with N is printed as [tour]); the doors are opened before the door views
		if (TOUR && tick - startedAt >= 60 && (tick - startedAt) % 40 % 30 == 0) {
			if (tourViews.isEmpty()) tourBuild();
			boolean snap = (tick - startedAt) % 40 == 30;
			int i = (int) ((tick - startedAt - (snap ? 30 : 0)) / 40) - 2;
			if (i >= 0 && i < tourViews.size()) for (ServerPlayer p : level.players()) {
				double[] v = tourViews.get(i);
				if (snap) ServerPlayNetworking.send(p, new Payloads.Shot("tour_" + i));
				else {
					if (tourNames.get(i).startsWith("door") && i > 0 && !tourNames.get(i - 1).startsWith("door")) doors.openAll();
					p.teleportTo(level, origin.getX() + v[0], origin.getY() + (v.length > 4 ? v[4] : 1), origin.getZ() + v[1], Set.of(), (float) v[2], (float) v[3], true);
					System.out.println("[tour] " + i + " " + tourNames.get(i));
				}
			}
		}
	// dev: -Dzombiecraft.debugZombies=true puts three free zombies in front of the player, then hits and kills one (screenshots)
		if (ZOMBIES) {
			long t = tick - startedAt;
			for (ServerPlayer p : level.players()) {
				PlayerGame pgd = g.pg(p);
				if (t == 40) { dbgZ.clear(); double yr = Math.toRadians(p.getYRot());
					for (int i = 0; i < 3; i++) {
						double d = 2.6 + i * 1.4, side = (i - 1) * 1.1;
						ZcZombie z = new ZcZombie(ZcEntities.ZOMBIE, level);
						z.moveTo(p.getX() - Math.sin(yr) * d + Math.cos(yr) * side, p.getY(), p.getZ() + Math.cos(yr) * d + Math.sin(yr) * side, p.getYRot() + 180f, 0f);
						z.setup(i == 0 ? "walk" : i == 1 ? "run" : "sprint", 100000, null);
						z.setNoAi(true);
						level.addFreshEntity(z); g.alive.add(z); dbgZ.add(z);
					} }
				if (t == 70 || t == 100) ServerPlayNetworking.send(p, new Payloads.Shot("zombies_" + t));
				if (t == 110 && !dbgZ.isEmpty()) ZombieHealth.hit(g, p, pgd, dbgZ.get(0), 10, false, false);
				if (t == 112) ServerPlayNetworking.send(p, new Payloads.Shot("zombies_hit"));
				if (t == 120 && dbgZ.size() > 1) { dbgZ.get(1).hp = 1; ZombieHealth.hit(g, p, pgd, dbgZ.get(1), 10, true, false); }
				if (t == 122 || t == 140 || t == 190) ServerPlayNetworking.send(p, new Payloads.Shot("zombies_kill_" + t));
			}
		}
	// dev: -Dzombiecraft.debugPap=true shows Pack-a-Punch holding a gun (upgrading, then ready) and saves screenshots
		if (PAP && pap != null && machines != null) {
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
		if (DRINK && machines != null) {
			long t = tick - startedAt;
			for (ServerPlayer p : level.players()) {
				if (t == 70) { g.pg(p).points = 9000; for (var md : Sheets.MACHINES) if (md.perk() != null && md.perk().equals("jug")) machines.use(md, p, g.pg(p)); }
				if (t == 85 || t == 95 || t == 105 || t == 115 || t == 125) net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(p, new Payloads.Shot("drink_" + t));
			}
		}
	// dev: -Dzombiecraft.debugGun=true puts every gun in the hand in turn (60 ticks each) and saves an idle and a reload screenshot
		if (GUN) {
			long t = tick - startedAt - 60;
			var ids = Sheets.WEAPONS.stream().filter(w -> !w.upgrade()).map(com.zombiecraft.sheet.Rows.WeaponDef::id).toList();
			int idx = (int) (t / 70), ph = (int) (t % 70);
			if (t >= 0 && idx < ids.size()) for (ServerPlayer p : level.players()) {
				var gun = WeaponSystem.active(p, g.pg(p));
				if (ph == 0) { WeaponSystem.give(p, g.pg(p), 0, ids.get(idx), false); p.getInventory().selected = 0; }
				if (ph == 25) net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(p, new Payloads.Shot("gun_" + ids.get(idx) + "_idle"));
				if (ph == 30 && gun != null) { gun.mag = Math.min(gun.mag, 1); WeaponSystem.startReload(g, p, g.pg(p)); }
				if (ph == 48) net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(p, new Payloads.Shot("gun_" + ids.get(idx) + "_reload"));
			}
		}
	// dev: -Dzombiecraft.debugPowerups=true lays out every power-up in an arc in front of the player and saves a screenshot
		if (POWERUPS && powerups != null && (tick - startedAt == 60 || tick - startedAt == 120)) {
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
		String view = VIEW;
		if (view != null && machines != null && tick - startedAt >= 60) {
			String[] names = view.split(",");
			long rel = tick - startedAt - 60; int idx = (int) (rel / 100), ph = (int) (rel % 100);
			if (idx < names.length && (ph == 0 || ph == 60)) {
				String n = names[idx];
				double px = 0, py = 0, pz = 0; float yaw = 0;
				if (n.equals("pap") && pap != null) {
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
		if (POWER && machines != null && !machines.power && tick - startedAt == 40)
			for (var md : Sheets.MACHINES) if (md.kind().equals("power")) for (ServerPlayer p : level.players()) machines.use(md, p, g.pg(p));
	}
}

package com.zombiecraft.game;

import com.zombiecraft.entity.ZcZombie;
import com.zombiecraft.game.PlayerGame.Gun;
import com.zombiecraft.item.ModItems;
import com.zombiecraft.net.Payloads;
import com.zombiecraft.sheet.Rows.WeaponDef;
import com.zombiecraft.sheet.Sheets;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.protocol.game.ClientboundSetHeldSlotPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.*;

/** Guns: slots, ammo, fire cadence (in seconds, like the game's weapon files), range falloff, hitscan, projectiles, reload and the knife. Server side. */
public final class WeaponSystem {
	private WeaponSystem() {}

	private static final Random RNG = new Random();
	private static final double TICK = 0.05;

	public static Gun active(ServerPlayer p, PlayerGame pg) {
		int slot = p.getInventory().selected;
		return slot >= 0 && slot < pg.guns.length ? pg.guns[slot] : null;
	}

	/** Put a gun into a slot (and select it). */
	public static void give(ServerPlayer p, PlayerGame pg, int slot, String weaponId, boolean pap) {
		cancelReload(p, pg);
		pg.guns[slot] = new Gun(weaponId, pap);
		p.getInventory().setItem(slot, ModItems.stack(weaponId, pap));
		p.getInventory().selected = slot;
		p.connection.send(new ClientboundSetHeldSlotPacket(slot));
	}

	/** Slot a newly bought gun goes into: an empty slot, else the one in hand. */
	public static int slotFor(ServerPlayer p, PlayerGame pg) {
		for (int i = 0; i < pg.guns.length; i++) if (pg.guns[i] == null) return i;
		int s = p.getInventory().selected;
		return s >= 0 && s < pg.guns.length ? s : 0;
	}

	public static int slotHolding(PlayerGame pg, String weaponId) {
		for (int i = 0; i < pg.guns.length; i++) if (pg.guns[i] != null && pg.guns[i].weapon.equals(weaponId)) return i;
		return -1;
	}

	public static void cancelReload(ServerPlayer p, PlayerGame pg) {
		if (pg.reloadSlot < 0) return;
		Gun gun = pg.guns[pg.reloadSlot];
		ServerPlayNetworking.send(p, new Payloads.CombatFeedback(Payloads.FEEDBACK_RELOAD_STOP, pg.reloadSlot,
				gun == null ? "" : gun.weapon, 0, false, false));
		pg.reloadSlot = -1;
	}

	/** Called every tick for every player in the game. */
	public static void tick(Game game, ServerPlayer p, PlayerGame pg) {
		long now = game.tick;
		Gun g = active(p, pg);

		// reload progress; switching guns cancels it
		if (pg.reloadSlot >= 0) {
			if (g == null || pg.guns[pg.reloadSlot] != g || p.getInventory().selected != pg.reloadSlot) cancelReload(p, pg);
			else {
				WeaponDef w = g.def();
				long total = pg.reloadEnd - pg.reloadStart;
				long done = now - pg.reloadStart;
				if (pg.reloadStage == 0 && done >= 2) { Cue.ui(w.cueReloadOut(), p); pg.reloadStage = 1; }
				if (pg.reloadStage == 1 && done >= total * 0.45) { Cue.ui(w.cueReloadIn(), p); pg.reloadStage = 2; }
				if (pg.reloadStage == 2 && done >= total * 0.8) { Cue.ui(w.cueReloadEnd(), p); pg.reloadStage = 3; }
				if (now >= pg.reloadEnd) {
					int n = Math.min(g.magSize() - g.mag, g.reserve);
					g.mag += n; g.reserve -= n;
					cancelReload(p, pg);
				}
			}
		}

		// fire cadence in seconds: several shots may fall in one tick for fast guns
		pg.fireCooldown = Math.max(pg.fireCooldown - TICK, -TICK);
		if (g != null && pg.reloadSlot < 0) {
			WeaponDef w = g.def();
			double gap = w.fireTime() * pg.perkFireFactor;
			int guard = 0;
			while (pg.burstLeft > 0 && pg.fireCooldown <= 0 && guard++ < 4) {
				if (g.mag <= 0) { pg.burstLeft = 0; break; }
				shoot(game, p, pg, g);
				pg.burstLeft--;
				pg.fireCooldown += gap + (pg.burstLeft == 0 ? w.burstGap() : 0);
			}
			boolean trigger = w.fireMode().equals("auto") ? pg.fireHeld : (pg.fireClick || (pg.fireHeld && !pg.prevFire));
			if (trigger && pg.burstLeft == 0) {
				while (pg.fireCooldown <= 0 && guard++ < 8) {
					if (g.mag <= 0) {
						if (g.reserve > 0) startReload(game, p, pg);
						else if (now - pg.lastDry > 10) { Cue.ui(w.cueDry(), p); pg.lastDry = now; }
						break;
					}
					shoot(game, p, pg, g);
					pg.fireCooldown += gap;
					if (w.fireMode().equals("burst")) {
						pg.burstLeft = w.burstCount() - 1;
						if (pg.burstLeft == 0) pg.fireCooldown += w.burstGap();
						break;
					}
					if (!w.fireMode().equals("auto")) break;
				}
				if (g.mag <= 0 && g.reserve > 0 && pg.reloadSlot < 0 && pg.burstLeft == 0) startReload(game, p, pg);
			}
		} else if (g == null) pg.burstLeft = 0;
		pg.prevFire = pg.fireHeld;
		pg.fireClick = false;
	}

	public static void startReload(Game game, ServerPlayer p, PlayerGame pg) {
		Gun g = active(p, pg);
		if (g == null || pg.reloadSlot >= 0 || g.mag >= g.magSize() || g.reserve <= 0) return;
		WeaponDef w = g.def();
		double seconds = (g.mag == 0 ? w.reloadEmptyTime() : w.reloadTime()) * pg.perkReloadFactor;
		pg.reloadSlot = p.getInventory().selected;
		pg.reloadStart = game.tick;
		pg.reloadEnd = game.tick + Math.max(4, (long) Math.ceil(seconds * 20));
		pg.reloadStage = 0;
		ServerPlayNetworking.send(p, new Payloads.CombatFeedback(Payloads.FEEDBACK_RELOAD_START, pg.reloadSlot,
				g.weapon, (int) (pg.reloadEnd - pg.reloadStart), false, false));
	}

	private static Vec3 spread(Vec3 dir, double deg) {
		if (deg <= 0) return dir;
		double r = Math.toRadians(deg);
		Vec3 up = Math.abs(dir.y) > 0.95 ? new Vec3(1, 0, 0) : new Vec3(0, 1, 0);
		Vec3 right = dir.cross(up).normalize();
		Vec3 up2 = right.cross(dir).normalize();
		double a = RNG.nextDouble() * Math.PI * 2, m = Math.sqrt(RNG.nextDouble()) * Math.tan(r);
		return dir.add(right.scale(Math.cos(a) * m)).add(up2.scale(Math.sin(a) * m)).normalize();
	}

	/** Damage at a distance: full inside rangeFull, falling to damageMin at rangeMin (values from the weapon file). */
	public static double damageAt(WeaponDef w, double dist) {
		if (w.rangeMin() <= w.rangeFull()) return w.damage();
		double t = Math.max(0, Math.min(1, (dist - w.rangeFull()) / (w.rangeMin() - w.rangeFull())));
		return w.damage() + (w.damageMin() - w.damage()) * t;
	}

	private static void shoot(Game game, ServerPlayer p, PlayerGame pg, Gun g) {
		ServerLevel level = (ServerLevel) p.level();
		WeaponDef w = g.def();
		g.mag--;
		Cue.ui(w.cueFire(), p);
		ServerPlayNetworking.send(p, new Payloads.CombatFeedback(Payloads.FEEDBACK_SHOT, p.getInventory().selected,
				g.weapon, 0, false, false));
		Vec3 eye = p.getEyePosition();
		Vec3 look = p.getViewVector(1f);

		if (w.projectile()) {
			Projectiles.launch(p, w, eye.add(look.scale(0.6)), spread(look, w.spreadDeg() * Revive.spreadFactor(p, pg)));
			return;
		}

		Map<ZcZombie, double[]> hits = new LinkedHashMap<>();
		double band = Sheets.sys("headshot_band");
		for (int i = 0; i < Math.max(1, w.pellets()); i++) {
			Vec3 dir = spread(look, w.spreadDeg() * Revive.spreadFactor(p, pg));
			Vec3 end = eye.add(dir.scale(w.range()));
			BlockHitResult bh = Barrier.shotClip(level, eye, end);
			double maxDist = bh.getType() == HitResult.Type.MISS ? w.range() : bh.getLocation().distanceTo(eye);
			ZcZombie best = null; double bestDist = maxDist; Vec3 bestPos = null;
			AABB box = new AABB(eye, end).inflate(1.0);
			for (ZcZombie z : level.getEntitiesOfClass(ZcZombie.class, box, e -> e.isAlive())) {
				Optional<Vec3> hit = z.getBoundingBox().inflate(0.1).clip(eye, end);
				if (hit.isEmpty()) continue;
				double d = hit.get().distanceTo(eye);
				if (d < bestDist) { best = z; bestDist = d; bestPos = hit.get(); }
			}
			if (best != null) {
				boolean head = bestPos.y >= best.getY() + best.getBbHeight() * (1.0 - band);
				double dmg = damageAt(w, bestDist) * (head ? w.headMult() : 1.0);
				double[] acc = hits.computeIfAbsent(best, k -> new double[]{0, 0});
				acc[0] += dmg; if (head) acc[1] = 1;
				Blood.hit(level, bestPos, head);
			} else if (bh.getType() != HitResult.Type.MISS) {
				Vec3 l = bh.getLocation();
				level.sendParticles(ParticleTypes.SMOKE, l.x, l.y, l.z, 2, 0.05, 0.05, 0.05, 0.01);
			}
		}
		for (var e : hits.entrySet()) ZombieHealth.hit(game, p, pg, e.getKey(), pg.instaKill ? 1e9 : e.getValue()[0], e.getValue()[1] > 0, false);
	}

	/** Left click is the knife. */
	public static void melee(Game game, ServerPlayer p, PlayerGame pg) {
		long now = game.tick;
		if (now < pg.nextMelee) return;
		pg.nextMelee = now + (long) Sheets.sys("melee_cooldown_ticks");
		ServerLevel level = (ServerLevel) p.level();
		Cue.ui("zmb_melee_whoosh_plr", p);
		Vec3 eye = p.getEyePosition(), look = p.getViewVector(1f);
		double reach = Sheets.sys("melee_range");
		ZcZombie best = null; double bestDot = 0.55;
		for (ZcZombie z : level.getEntitiesOfClass(ZcZombie.class, p.getBoundingBox().inflate(reach + 1), e -> e.isAlive())) {
			Vec3 to = z.getBoundingBox().getCenter().subtract(eye);
			if (to.length() > reach + 0.6) continue;
			double dot = to.normalize().dot(look);
			if (dot > bestDot) { bestDot = dot; best = z; }
		}
		if (best != null) {
			Cue.ui("wpn_melee_hit", p);
			ZombieHealth.hit(game, p, pg, best, pg.instaKill ? 1e9 : Sheets.sys("melee_damage"), false, true);
		}
	}
}

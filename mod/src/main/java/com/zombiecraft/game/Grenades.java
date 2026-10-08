package com.zombiecraft.game;

import com.zombiecraft.entity.ZcEntities;
import com.zombiecraft.entity.ZcProp;
import com.zombiecraft.entity.ZcZombie;
import com.zombiecraft.net.Payloads;
import com.zombiecraft.sheet.Sheets;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;

/**
 * BO2's frag grenade (frag_grenade_zm): G pulls the pin (cooking starts), letting go throws it once the pin is out, it bounces off
 * the map and explodes when the fuse runs out. Numbers come from the weapon file via sheets/systems.json (grenade_*).
 */
public final class Grenades {
	private Grenades() {}

	private static final double DT = 0.05;

	private static final class Live {
		Vec3 pos, vel;
		int fuse, quiet;
		UUID owner;
		ZcProp visual;
		float spin;
	}

	private static final List<Live> LIVE = new ArrayList<>();

	public static void clear() {
		for (Live l : LIVE) if (l.visual != null) l.visual.discard();
		LIVE.clear();
	}

	private static boolean canThrow(Game g, PlayerGame pg) {
		return pg.grenades > 0 && !pg.downed && !pg.dead && !pg.drinking && g.phase != Payloads.PHASE_GAMEOVER && g.phase != Payloads.PHASE_LOBBY;
	}

	/** The grenade key went down (pin out) or up (throw as soon as the pin animation is done). */
	public static void input(Game g, ServerPlayer p, PlayerGame pg, boolean held) {
		if (held == pg.grenadeHeld) return;
		pg.grenadeHeld = held;
		if (held && pg.cookStart < 0 && canThrow(g, pg)) {
			pg.cookStart = g.tick;
			WeaponSystem.cancelReload(p, pg);
			Cue.ui("wpn_grenade_pull_pin", p);
		}
	}

	/** Per player and tick: throw when the key is up and the pin animation is done, or when the cooked fuse has run out. */
	public static void cook(Game g, ServerPlayer p, PlayerGame pg) {
		if (pg.cookStart < 0) return;
		if (!canThrow(g, pg)) { pg.cookStart = -1; return; }
		long age = g.tick - pg.cookStart;
		long pin = Math.round(Sheets.sys("grenade_pin_sec") / DT), fuse = Math.round(Sheets.sys("grenade_fuse_sec") / DT);
		if (age < fuse && (pg.grenadeHeld || age < pin)) return;
		pg.cookStart = -1;
		pg.grenades--;
		Vec3 look = p.getLookAngle(), eye = p.getEyePosition();
		Vec3 start = eye.add(look.scale(0.5)).add(0, -0.15, 0);
		BlockHitResult wall = Barrier.shotClip(g.level, eye, start);
		if (wall.getType() != HitResult.Type.MISS) start = wall.getLocation().subtract(look.scale(0.1));
		Live l = new Live();
		l.pos = start;
		l.vel = look.scale(Sheets.sys("grenade_throw_speed")).add(0, Sheets.sys("grenade_throw_up"), 0);
		l.fuse = (int) Math.max(1, fuse - age);
		l.owner = p.getUUID();
		l.visual = new ZcProp(ZcEntities.GRENADE, g.level);
		l.visual.addTag("zc");
		l.visual.getEntityData().set(ZcProp.KIND, ZcProp.SCENERY);
		l.visual.getEntityData().set(ZcProp.PAP_WEAPON, "t6_wpn_grenade_frag_projectile");
		l.visual.getEntityData().set(ZcProp.PAP_DEPTH, 1f);
		l.visual.setPos(start.x, start.y - 0.05, start.z);
		g.level.addFreshEntity(l.visual);
		LIVE.add(l);
	}

	public static void tick(Game g) {
		ServerLevel level = g.level;
		double gravity = Sheets.sys("grenade_gravity"), keep = Sheets.sys("grenade_bounce");
		Iterator<Live> it = LIVE.iterator();
		while (it.hasNext()) {
			Live l = it.next();
			if (--l.fuse <= 0) { explode(g, l); it.remove(); continue; }
			l.quiet--;
			l.vel = l.vel.add(0, -gravity * DT, 0);
			Vec3 next = l.pos.add(l.vel.scale(DT));
			BlockHitResult hit = Barrier.shotClip(level, l.pos, next);
			if (hit.getType() == HitResult.Type.MISS) l.pos = next;
			else {
				Vec3 n = new Vec3(hit.getDirection().getStepX(), hit.getDirection().getStepY(), hit.getDirection().getStepZ());
				double into = l.vel.dot(n);
				Vec3 along = l.vel.subtract(n.scale(into));
				double rebound = -into * keep;
				// resting on a floor: stop bouncing and let friction slow the slide
				if (n.y > 0.5 && rebound < 1.0) rebound = 0;
				l.vel = n.scale(rebound).add(along.scale(0.7));
				l.pos = hit.getLocation().add(n.scale(0.05));
				if (-into > 3.0 && l.quiet <= 0) { Cue.at("wpn_grenade_bounce_concrete", level, l.pos); l.quiet = 4; }
			}
			l.spin += (float) (l.vel.length() * 9);
			l.visual.setPos(l.pos.x, l.pos.y - 0.05, l.pos.z);
			l.visual.setYRot(l.spin);
		}
	}

	private static void explode(Game g, Live l) {
		ServerLevel level = g.level;
		if (l.visual != null) l.visual.discard();
		Vec3 at = l.pos;
		level.sendParticles(ParticleTypes.EXPLOSION_EMITTER, at.x, at.y + 0.2, at.z, 1, 0, 0, 0, 0);
		level.sendParticles(ParticleTypes.FLAME, at.x, at.y + 0.3, at.z, 30, 0.6, 0.4, 0.6, 0.06);
		level.sendParticles(ParticleTypes.SMOKE, at.x, at.y + 0.3, at.z, 20, 0.7, 0.5, 0.7, 0.03);
		Cue.at("wpn_grenade_explode", level, at);
		ServerPlayer owner = level.getServer().getPlayerList().getPlayer(l.owner);
		if (owner == null) return;
		PlayerGame pg = g.pg(owner);
		double r = Sheets.sys("grenade_radius"), inner = Sheets.sys("grenade_damage_inner"), outer = Sheets.sys("grenade_damage_outer");
		Vec3 from = at.add(0, 0.3, 0);
		for (ZcZombie z : level.getEntitiesOfClass(ZcZombie.class, new AABB(at, at).inflate(r + 1), e -> e.isAlive())) {
			Vec3 c = z.getBoundingBox().getCenter();
			double d = c.distanceTo(at);
			if (d > r) continue;
			// walls shield: the blast must reach the zombie in a straight line
			if (Barrier.shotClip(level, from, c).getType() != HitResult.Type.MISS) continue;
			double dmg = inner + (outer - inner) * (d / r);
			ZombieHealth.hit(g, owner, pg, z, pg.instaKill ? 1e9 : dmg, false, false);
		}
	}
}

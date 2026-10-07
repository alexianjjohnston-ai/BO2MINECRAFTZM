package com.zombiecraft.game;

import com.zombiecraft.entity.ZcZombie;
import com.zombiecraft.sheet.Rows.WeaponDef;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Explosive shots (Ray Gun, upgraded M1911): fly at the weapon file's speed and explode with inner/outer damage over a radius. */
public final class Projectiles {
	private Projectiles() {}

	private static final class Shot {
		Vec3 pos, dir; WeaponDef w; UUID owner; int life = 80;
	}

	private static final List<Shot> SHOTS = new ArrayList<>();

	public static void clear() { SHOTS.clear(); }

	public static void launch(ServerPlayer p, WeaponDef w, Vec3 from, Vec3 dir) {
		Shot s = new Shot();
		s.pos = from; s.dir = dir; s.w = w; s.owner = p.getUUID();
		SHOTS.add(s);
	}

	private static boolean green(WeaponDef w) { return w.kind().equals("raygun"); }

	public static void tick(Game game) {
		ServerLevel level = game.level;
		Iterator<Shot> it = SHOTS.iterator();
		while (it.hasNext()) {
			Shot s = it.next();
			if (--s.life <= 0) { it.remove(); continue; }
			Vec3 next = s.pos.add(s.dir.scale(s.w.projSpeed() * 0.05));
			ServerPlayer owner = level.getServer().getPlayerList().getPlayer(s.owner);
			BlockHitResult bh = Barrier.shotClip(level, s.pos, next);
			Vec3 end = bh.getType() == HitResult.Type.MISS ? next : bh.getLocation();
			ZcZombie hitZ = null; double best = Double.MAX_VALUE; Vec3 hitPos = null;
			for (ZcZombie z : level.getEntitiesOfClass(ZcZombie.class, new AABB(s.pos, end).inflate(1.0), e -> e.isAlive())) {
				Optional<Vec3> h = z.getBoundingBox().inflate(0.25).clip(s.pos, end);
				if (h.isPresent()) { double d = h.get().distanceToSqr(s.pos); if (d < best) { best = d; hitZ = z; hitPos = h.get(); } }
			}
			// trail
			int steps = Math.max(1, (int) (s.pos.distanceTo(end) / 0.6));
			for (int i = 0; i <= steps; i++) {
				Vec3 q = s.pos.add(end.subtract(s.pos).scale(i / (double) steps));
				if (green(s.w)) level.sendParticles(new DustParticleOptions(0x46FF9A, 1.1f), q.x, q.y, q.z, 1, 0.02, 0.02, 0.02, 0);
				else level.sendParticles(ParticleTypes.FLAME, q.x, q.y, q.z, 1, 0.02, 0.02, 0.02, 0.0);
			}
			if (hitZ != null || bh.getType() != HitResult.Type.MISS) {
				explode(game, s, hitPos != null ? hitPos : end, owner);
				it.remove();
			} else s.pos = next;
		}
	}

	private static void explode(Game game, Shot s, Vec3 at, ServerPlayer owner) {
		ServerLevel level = game.level;
		WeaponDef w = s.w;
		if (green(w)) {
			level.sendParticles(new DustParticleOptions(0x46FF9A, 2.2f), at.x, at.y + 0.4, at.z, 40, 0.8, 0.6, 0.8, 0.02);
			level.sendParticles(ParticleTypes.ELECTRIC_SPARK, at.x, at.y + 0.4, at.z, 25, 0.8, 0.6, 0.8, 0.2);
		} else {
			level.sendParticles(ParticleTypes.EXPLOSION, at.x, at.y + 0.4, at.z, 3, 0.8, 0.5, 0.8, 0.0);
			level.sendParticles(ParticleTypes.FLAME, at.x, at.y + 0.4, at.z, 30, 0.8, 0.6, 0.8, 0.05);
		}
		Cue.at(w.cueExplosion() != null ? w.cueExplosion() : "zmb_explo", level, at);
		if (owner == null) return;
		PlayerGame pg = game.pg(owner);
		double r = Math.max(0.5, w.explRadius());
		for (ZcZombie z : level.getEntitiesOfClass(ZcZombie.class, new AABB(at, at).inflate(r + 1), e -> e.isAlive())) {
			double d = z.getBoundingBox().getCenter().distanceTo(at) - 0.4;
			if (d > r) continue;
			double t = Math.max(0, Math.min(1, d / r));
			double dmg = w.damage() + (w.damageMin() - w.damage()) * t;
			ZombieHealth.hit(game, owner, pg, z, pg.instaKill ? 1e9 : dmg, false, false);
		}
	}
}

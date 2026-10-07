package com.zombiecraft.game;

import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

/** BO2-style gore: dark red spray and mist instead of vanilla block/crit particles. */
public final class Blood {
	private static final DustParticleOptions RED = new DustParticleOptions(0x8A0A0A, 0.55f);
	private static final DustParticleOptions DARK = new DustParticleOptions(0x4A0505, 0.8f);
	private static final DustParticleOptions MIST = new DustParticleOptions(0xB01818, 1.1f);

	private Blood() {}

	/** A small spray at a bullet impact; headshots spray more. */
	public static void hit(ServerLevel level, Vec3 at, boolean head) {
		level.sendParticles(RED, at.x, at.y, at.z, head ? 18 : 10, 0.10, 0.10, 0.10, 0.03);
		level.sendParticles(MIST, at.x, at.y, at.z, 1, 0.05, 0.05, 0.05, 0);
	}

	/** Death burst: a cloud of mist, spray, and heavy droplets (gibs) around the body. */
	public static void kill(ServerLevel level, Vec3 at, boolean head) {
		level.sendParticles(MIST, at.x, at.y, at.z, head ? 3 : 2, 0.2, 0.25, 0.25, 0);
		level.sendParticles(RED, at.x, at.y, at.z, head ? 50 : 34, 0.30, 0.30, 0.30, 0.04);
		level.sendParticles(DARK, at.x, at.y - 0.3, at.z, 14, 0.25, 0.4, 0.25, 0.02);
	}
}

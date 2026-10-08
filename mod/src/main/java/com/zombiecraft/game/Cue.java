package com.zombiecraft.game;

import com.zombiecraft.net.Payloads;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

/** Server-side helper: tell clients to play a cue. The id is a BO2 alias name from audio.json (the cue id is always the first argument). */
public final class Cue {
	private Cue() {}

	/** A 2D sound for one player (UI, music, their own gun). */
	public static void ui(String cue, ServerPlayer p) {
		ServerPlayNetworking.send(p, new Payloads.CuePlay(cue, 0, 0, 0, false, 1f, 1f));
	}

	/** A 2D sound for every player. */
	public static void all(String cue, ServerLevel level) {
		for (ServerPlayer p : level.players()) ui(cue, p);
	}

	/** A sound in the world, heard by players within range. */
	public static void at(String cue, ServerLevel level, Vec3 pos) {
		for (ServerPlayer p : level.players()) {
			if (p.position().distanceToSqr(pos) < 96 * 96)
				ServerPlayNetworking.send(p, new Payloads.CuePlay(cue, pos.x, pos.y, pos.z, true, 1f, 1f));
		}
	}

	public static void stopAll(String cue, ServerLevel level) {
		for (ServerPlayer p : level.players()) ServerPlayNetworking.send(p, new Payloads.CueStop(cue));
	}
}

package com.zombiecraft.client.audio;

import com.zombiecraft.client.hud.ZcHud;
import net.minecraft.client.Minecraft;

/** BO2 footsteps for the local player, and silence for everything vanilla (music, the player's own step/hurt sounds). */
public final class Footsteps {
	private Footsteps() {}

	private static double dist;

	public static void tick(Minecraft mc) {
		com.zombiecraft.client.menu.Bo2Menus.holdLoading(mc);
		MenuAudio.tick(com.zombiecraft.client.menu.Bo2Menus.loadingShown());
		mc.getMusicManager().stopPlaying(); // Minecraft's own music never plays: the BO2 tracks are ours
		var p = mc.player;
		if (p == null || !ZcHud.usesWeaponHud(mc)) { dist = 0; return; }
		p.setSilent(true);
		if (mc.isPaused() || !p.onGround()) return;
		dist += Math.hypot(p.getX() - p.xo, p.getZ() - p.zo);
		boolean sneak = p.isShiftKeyDown();
		if (dist >= (sneak ? 1.0 : 1.7)) {
			dist = 0;
			MenuAudio.play(sneak ? "fly_step_walk_plr_ceramic" : "fly_step_run_plr_ceramic");
		}
	}
}

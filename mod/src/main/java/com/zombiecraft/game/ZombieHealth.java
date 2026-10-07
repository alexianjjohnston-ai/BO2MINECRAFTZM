package com.zombiecraft.game;

import com.zombiecraft.entity.ZcZombie;
import com.zombiecraft.net.Payloads;
import com.zombiecraft.sheet.Rows.ZombieTier;
import com.zombiecraft.sheet.Sheets;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/** Zombie hit points are our own numbers (BO2 health goes far past vanilla's 1024 cap); vanilla damage is cancelled. */
public final class ZombieHealth {
	private ZombieHealth() {}

	public static void hit(Game game, ServerPlayer p, PlayerGame pg, ZcZombie z, double dmg, boolean head, boolean melee) {
		if (!z.isAlive() || z.hp <= 0) return;
		ServerLevel level = (ServerLevel) z.level();
		z.hp -= dmg;
		ServerPlayNetworking.send(p, new Payloads.CombatFeedback(Payloads.FEEDBACK_HIT, -1, "", 0, head, z.hp <= 0));
		if (z.hp > 0) {
			pg.earn(Sheets.sysInt("hit_points"));
			level.broadcastEntityEvent(z, (byte) 2);
			z.knockback(0.2, p.getX() - z.getX(), p.getZ() - z.getZ());
			return;
		}
		z.hp = 0;
		pg.kills++;
		if (head && !melee) pg.headshots++;
		int bonus = melee ? Sheets.sysInt("bonus_melee") : head ? Sheets.sysInt("bonus_head") : Sheets.sysInt("bonus_torso");
		pg.earn(Sheets.sysInt("kill_points") + bonus);
		ZombieTier t = Sheets.tier(z.tier);
		Cue.at(t.cueDeath(), level, z.position());
		if (head && !melee) Cue.at("zmb_zombie_head_gib", level, z.getEyePosition());
		game.onZombieKilled(z);
		z.kill(level);
	}
}

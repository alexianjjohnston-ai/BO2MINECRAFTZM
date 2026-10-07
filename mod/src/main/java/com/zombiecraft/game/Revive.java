package com.zombiecraft.game;

import com.zombiecraft.net.Payloads;
import com.zombiecraft.sheet.Sheets;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.AABB;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Co-op: going down, bleeding out, being revived, and the prone stance (the downed crawl is the same body, just slower).
 * With nobody else left standing a fatal hit still ends the game, as before.
 */
public final class Revive {
	private Revive() {}

	private static final ResourceLocation STANCE = Payloads.id("stance");
	private static final int QUICK_BIT = 1 << Machines.Perk.REVIVE.bit;

	// ------------------------------------------------------------------ who is up
	public static boolean standing(Game g, ServerPlayer p) {
		PlayerGame pg = g.players.get(p.getUUID());
		return pg != null && !pg.downed && !pg.dead && !p.isSpectator();
	}

	public static boolean othersStanding(Game g, ServerPlayer p) {
		for (ServerPlayer o : g.level.players()) if (o != p && standing(g, o)) return true;
		return false;
	}

	public static boolean isDowned(ServerPlayer p) {
		Game g = Game.INSTANCE;
		PlayerGame pg = g == null ? null : g.players.get(p.getUUID());
		return pg != null && pg.downed;
	}

	// ------------------------------------------------------------------ stance
	/** Shrinks the body (lower camera, smaller box), slows it and stops jumping. */
	private static void lowerBody(ServerPlayer p, double speedMult) {
		set(p, Attributes.SCALE, -0.5);
		set(p, Attributes.MOVEMENT_SPEED, speedMult - 1.0);
		set(p, Attributes.JUMP_STRENGTH, -1.0);
	}

	private static void set(ServerPlayer p, Holder<Attribute> attr, double amount) {
		AttributeInstance a = p.getAttribute(attr);
		if (a != null) a.addOrReplacePermanentModifier(new AttributeModifier(STANCE, amount, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
	}

	private static void raiseBody(ServerPlayer p) {
		for (Holder<Attribute> attr : java.util.List.of(Attributes.SCALE, Attributes.MOVEMENT_SPEED, Attributes.JUMP_STRENGTH)) {
			AttributeInstance a = p.getAttribute(attr);
			if (a != null) a.removeModifier(STANCE);
		}
	}

	/** Back to a normal standing player (new game, respawn, revive). */
	public static void clear(ServerPlayer p) {
		raiseBody(p);
		p.setGlowingTag(false);
	}

	private static boolean roomToStand(ServerPlayer p) {
		AABB full = new AABB(p.getX() - 0.3, p.getY(), p.getZ() - 0.3, p.getX() + 0.3, p.getY() + 1.8, p.getZ() + 0.3);
		return p.level().noCollision(p, full);
	}

	/** The prone key: lie down, or stand up when there is room. */
	public static void toggleProne(Game g, ServerPlayer p, PlayerGame pg) {
		if (pg.downed || pg.dead || g.phase == Payloads.PHASE_GAMEOVER) return;
		if (!pg.prone) {
			pg.prone = true;
			WeaponSystem.cancelReload(p, pg);
			lowerBody(p, Sheets.sys("prone_speed_mult"));
		} else if (roomToStand(p)) {
			pg.prone = false;
			raiseBody(p);
		} else p.displayClientMessage(Component.literal("Not enough room to stand"), true);
	}

	/** Gun spread factor for the current stance. */
	public static double spreadFactor(ServerPlayer p, PlayerGame pg) {
		return pg.prone ? Sheets.sys("prone_spread_mult") : p.isShiftKeyDown() ? Sheets.sys("crouch_spread_mult") : 1.0;
	}

	// ------------------------------------------------------------------ going down
	/** A fatal hit with teammates still standing: the player goes down instead of dying. */
	public static void down(Game g, ServerPlayer p, PlayerGame pg) {
		pg.downed = true; pg.prone = false;
		pg.downs++;
		pg.bleedEnd = g.tick + Sheets.sysInt("bleedout_s") * 20L;
		pg.reviveTicks = 0;
		pg.fireHeld = false; pg.interactHeld = false; pg.drinking = false;
		WeaponSystem.cancelReload(p, pg);
		// BO2: perks are lost on going down. Quick Revive is kept so its owner still revives fast (and can self-revive when alone)
		pg.perks &= QUICK_BIT;
		pg.perkReloadFactor = 1.0; pg.perkFireFactor = 1.0;
		p.getAttribute(Attributes.MAX_HEALTH).setBaseValue(Sheets.sys("player_max_health"));
		p.setHealth(p.getMaxHealth());
		lowerBody(p, Sheets.sys("downed_speed_mult"));
		p.setGlowingTag(true);
		Cue.ui("evt_player_death", p);
	}

	private static void getUp(Game g, ServerPlayer p, PlayerGame pg) {
		pg.downed = false; pg.reviveTicks = 0; pg.reviveShow = 0;
		clear(p);
		p.setHealth(p.getMaxHealth());
		pg.shieldUntil = g.tick + 80;
		Cue.ui("mus_perks_revive_sting", p);
	}

	private static void bleedOut(Game g, ServerPlayer p, PlayerGame pg) {
		pg.downed = false; pg.dead = true; pg.reviveTicks = 0; pg.reviveShow = 0;
		clear(p);
		p.setHealth(p.getMaxHealth());
		p.setGameMode(GameType.SPECTATOR);
		Cue.ui("evt_player_death", p);
		boolean anyUp = false;
		for (ServerPlayer o : g.level.players()) if (standing(g, o)) anyUp = true;
		if (!anyUp) g.gameOver(p);
	}

	/** Next round: everyone who bled out comes back with a fresh start (stats are kept). */
	public static void respawnDead(Game g) {
		for (ServerPlayer p : g.level.players()) {
			PlayerGame old = g.players.get(p.getUUID());
			if (old == null || !old.dead) continue;
			g.resetPlayer(p);
			PlayerGame pg = g.pg(p);
			pg.kills = old.kills; pg.headshots = old.headshots; pg.downs = old.downs; pg.revives = old.revives;
		}
	}

	// ------------------------------------------------------------------ the tick
	/** The downed teammate this player is close enough to revive, or null. */
	public static ServerPlayer reviveTarget(Game g, ServerPlayer p) {
		PlayerGame me = g.players.get(p.getUUID());
		if (me == null || me.downed || me.dead || me.drinking) return null;
		double reach = Sheets.sys("revive_reach"), best = reach * reach;
		ServerPlayer found = null;
		for (ServerPlayer o : g.level.players()) {
			if (o == p) continue;
			PlayerGame pg = g.players.get(o.getUUID());
			if (pg == null || !pg.downed || Math.abs(o.getY() - p.getY()) > 1.6) continue;
			double dx = o.getX() - p.getX(), dz = o.getZ() - p.getZ(), d = dx * dx + dz * dz;
			if (d <= best) { best = d; found = o; }
		}
		return found;
	}

	/** Runs once a tick before the per-player update: revive progress, bleeding out. */
	public static void tickAll(Game g) {
		if (g.phase == Payloads.PHASE_GAMEOVER) return;
		for (PlayerGame pg : g.players.values()) pg.reviveShow = 0;
		Set<UUID> advanced = new HashSet<>();
		for (ServerPlayer p : g.level.players()) {
			PlayerGame me = g.players.get(p.getUUID());
			ServerPlayer t = reviveTarget(g, p);
			if (me == null || t == null || !me.interactHeld) continue;
			PlayerGame tp = g.players.get(t.getUUID());
			int need = (int) Math.round(Sheets.sys((me.perks & QUICK_BIT) != 0 ? "revive_time_quick_s" : "revive_time_s") * 20);
			tp.reviveTicks++;
			advanced.add(t.getUUID());
			int pct = Math.min(100, tp.reviveTicks * 100 / Math.max(1, need));
			tp.reviveShow = pct; me.reviveShow = pct;
			if (tp.reviveTicks >= need) {
				getUp(g, t, tp);
				me.revives++;
				me.points += Sheets.sysInt("revive_points");
				Cue.ui("zmb_cha_ching", p);
			}
		}
		for (ServerPlayer p : g.level.players()) {
			PlayerGame pg = g.players.get(p.getUUID());
			if (pg == null) continue;
			if (pg.downed) {
				if (!advanced.contains(p.getUUID())) pg.reviveTicks = 0;
				if (g.tick >= pg.bleedEnd) bleedOut(g, p, pg);
			} else if (pg.dead && g.tick % 40 == 0) {
				p.displayClientMessage(Component.literal("You bled out. You will return next round"), true);
			}
		}
	}
}

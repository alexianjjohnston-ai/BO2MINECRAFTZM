package com.zombiecraft.game;

import com.zombiecraft.game.PlayerGame.Gun;
import com.zombiecraft.sheet.Rows.PapDef;
import com.zombiecraft.sheet.Sheets;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

import java.util.Locale;
import java.util.UUID;

/** The Pack-a-Punch machine: pay, wait while it works, take the upgraded gun before the timer runs out. */
public final class PapSystem {
	public enum State { IDLE, UPGRADING, READY }

	private final Game game;
	private final ServerLevel level;
	public State state = State.IDLE;
	private int timer;
	private UUID user;
	private String weapon;

	public PapSystem(Game game) { this.game = game; this.level = game.level; }

	public PapDef def() { return Sheets.PAPS.get(0); }

	public boolean contains(BlockPos p) {
		PapDef d = def(); BlockPos o = game.origin;
		return p.getX() >= o.getX() + Math.min(d.x1(), d.x2()) && p.getX() <= o.getX() + Math.max(d.x1(), d.x2())
				&& p.getY() >= o.getY() + Math.min(d.y1(), d.y2()) && p.getY() <= o.getY() + Math.max(d.y1(), d.y2())
				&& p.getZ() >= o.getZ() + Math.min(d.z1(), d.z2()) && p.getZ() <= o.getZ() + Math.max(d.z1(), d.z2());
	}

	private Vec3 center() {
		PapDef d = def(); BlockPos o = game.origin;
		return new Vec3(o.getX() + (d.x1() + d.x2()) / 2.0 + 0.5, o.getY() + d.y1() + 1.5, o.getZ() + (d.z1() + d.z2()) / 2.0 + 0.5);
	}

	private void display(String id) {
		Vec3 c = center();
		game.cmd("kill @e[tag=zc_papdisp]");
		game.cmd(String.format(Locale.ROOT,
				"summon item_display %.2f %.2f %.2f {item:{id:\"%s\",count:1},billboard:\"center\",Tags:[\"zc\",\"zc_papdisp\"],transformation:{translation:[0f,0f,0f],left_rotation:[0f,0f,0f,1f],scale:[1.4f,1.4f,1.4f],right_rotation:[0f,0f,0f,1f]}}",
				c.x, c.y, c.z, id));
	}

	public void use(ServerPlayer p, PlayerGame pg) {
		int cost = Sheets.sysInt("pap_cost");
		if (state == State.IDLE && game.machines != null && !game.machines.power) { Cue.ui("evt_perk_deny", p); return; }
		if (state == State.IDLE) {
			Gun g = WeaponSystem.active(p, pg);
			if (g == null || g.pap || pg.points < cost) { Cue.ui("zmb_perks_packa_deny", p); return; }
			pg.points -= cost;
			Cue.ui("zmb_cha_ching", p);
			int slot = p.getInventory().selected;
			weapon = g.weapon; user = p.getUUID();
			WeaponSystem.cancelReload(p, pg);
			pg.guns[slot] = null;
			p.getInventory().setItem(slot, net.minecraft.world.item.ItemStack.EMPTY);
			state = State.UPGRADING; timer = Sheets.sysInt("pap_upgrade_ticks");
			display("zombiecraft:" + weapon);
			Cue.at("zmb_perks_packa_upgrade", level, center());
			Cue.at("zmb_perks_packa_loop", level, center());
		} else if (state == State.READY && p.getUUID().equals(user)) {
			int slot = WeaponSystem.slotFor(p, pg);
			WeaponSystem.give(p, pg, slot, weapon, true);
			Cue.ui("mus_perks_packa_sting", p);
			finish();
		}
	}

	private void finish() {
		Cue.stopAll("zmb_perks_packa_ticktock", level);
		Cue.stopAll("zmb_perks_packa_loop", level);
		game.cmd("kill @e[tag=zc_papdisp]");
		state = State.IDLE; weapon = null; user = null;
	}

	public void tick() {
		if (state == State.UPGRADING && --timer <= 0) {
			Cue.stopAll("zmb_perks_packa_loop", level);
			Cue.at("zmb_perks_packa_ready", level, center());
			Cue.at("zmb_perks_packa_ticktock", level, center());
			state = State.READY; timer = Sheets.sysInt("pap_pickup_timeout_s") * 20;
		} else if (state == State.READY && --timer <= 0) {
			finish(); // the gun is lost if nobody takes it
		}
	}

	public String promptFor(ServerPlayer p, PlayerGame pg) {
		Gun g = WeaponSystem.active(p, pg);
		if (state == State.IDLE && game.machines != null && !game.machines.power) return "The power must be turned on first";
		return switch (state) {
			case IDLE -> g == null ? "" : g.pap ? "This gun is already Pack-a-Punched" : "Press F to Pack-a-Punch the " + g.def().name() + " [" + Sheets.sysInt("pap_cost") + "]";
			case READY -> p.getUUID().equals(user) ? "Press F to take your " + Sheets.weapon(weapon).papName() : "";
			default -> "";
		};
	}
}

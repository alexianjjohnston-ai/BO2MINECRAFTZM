package com.zombiecraft.game;

import com.zombiecraft.game.PlayerGame.Gun;
import com.zombiecraft.entity.PapVisual;
import com.zombiecraft.entity.ZcEntities;
import com.zombiecraft.entity.ZcProp;
import com.zombiecraft.sheet.Rows.PapDef;
import com.zombiecraft.sheet.Sheets;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.level.block.Blocks;

import java.util.UUID;

/** The Pack-a-Punch machine: pay, wait while it works, take the upgraded gun before the timer runs out. */
public final class PapSystem {
	public enum State { IDLE, UPGRADING, READY }

	private final Game game;
	private final ServerLevel level;
	public State state = State.IDLE;
	private int timer;
	private int phaseDuration;
	private UUID user;
	private String weapon;

	private ZcProp prop;
	private final boolean modeled;

	public PapSystem(Game game) {
		this.game = game; this.level = game.level; this.modeled = LocalAssets.models;
		buildProp();
	}

	/** Replace the whole placeholder, including its front glass; collision follows the compact BO2 machine. */
	private void buildProp() {
		PapDef d = def(); BlockPos o = game.origin;
		game.cmd("kill @e[tag=zc_pap]");
		game.cmd("kill @e[tag=zc_papdisp]"); // remove displays left by the old presentation
		net.minecraft.core.Direction f = net.minecraft.core.Direction.valueOf(d.facing().toUpperCase());
		double cx = o.getX() + (d.x1() + d.x2()) / 2.0 + 0.5, cz = o.getZ() + (d.z1() + d.z2()) / 2.0 + 0.5;
		double depth = f.getAxis() == net.minecraft.core.Direction.Axis.X ? Math.abs(d.x2() - d.x1()) + 1 : Math.abs(d.z2() - d.z1()) + 1;
		prop = new ZcProp(ZcEntities.PROP, level);
		prop.moveTo(cx - f.getStepX() * depth / 2, o.getY() + Math.min(d.y1(), d.y2()), cz - f.getStepZ() * depth / 2, Machines.yawOf(d.facing()), 0f);
		prop.setYRot(Machines.yawOf(d.facing()));
		prop.addTag("zc"); prop.addTag("zc_pap");
		prop.getEntityData().set(ZcProp.KIND, ZcProp.PAP);
		prop.getEntityData().set(ZcProp.PAP_MODELED, modeled);
		prop.getEntityData().set(ZcProp.PAP_DEPTH, (float) depth);
		level.addFreshEntity(prop);
		if (modeled) {
			AABB body = targetBounds();
			for (int x = Math.min(d.x1(), d.x2()); x <= Math.max(d.x1(), d.x2()); x++)
				for (int y = Math.min(d.y1(), d.y2()); y <= Math.max(d.y1(), d.y2()); y++)
					for (int z = Math.min(d.z1(), d.z2()); z <= Math.max(d.z1(), d.z2()); z++) {
						BlockPos pos = o.offset(x, y, z);
						level.setBlock(pos, (body.contains(Vec3.atCenterOf(pos)) ? Blocks.BARRIER : Blocks.AIR).defaultBlockState(), 3);
					}
		}
	}

	/** The visible machine's bounds, used by F targeting instead of its old oversized placeholder. */
	public AABB targetBounds() {
		PapDef d = def(); BlockPos o = game.origin;
		if (!modeled) return new AABB(o.getX() + Math.min(d.x1(), d.x2()), o.getY() + Math.min(d.y1(), d.y2()), o.getZ() + Math.min(d.z1(), d.z2()),
				o.getX() + Math.max(d.x1(), d.x2()) + 1, o.getY() + Math.max(d.y1(), d.y2()) + 1, o.getZ() + Math.max(d.z1(), d.z2()) + 1);
		var f = net.minecraft.core.Direction.valueOf(d.facing().toUpperCase(java.util.Locale.ROOT));
		double cx = prop.getX() + f.getStepX() * PapVisual.DEPTH / 2, cz = prop.getZ() + f.getStepZ() * PapVisual.DEPTH / 2;
		double hx = f.getStepX() == 0 ? PapVisual.WIDTH / 2 : PapVisual.DEPTH / 2;
		double hz = f.getStepZ() == 0 ? PapVisual.WIDTH / 2 : PapVisual.DEPTH / 2;
		return new AABB(cx - hx, prop.getY(), cz - hz, cx + hx, prop.getY() + PapVisual.HEIGHT, cz + hz);
	}

	/** Lit once the power is on; shudders while it works. */
	private void syncProp() {
		if (prop == null) return;
		prop.setPowered(game.machines != null && game.machines.power);
		prop.setPapState(state.ordinal(), weapon == null ? "" : weapon, phaseDuration);
	}

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
			state = State.UPGRADING; timer = phaseDuration = Math.max(PapVisual.INTAKE_TICKS, Sheets.sysInt("pap_upgrade_ticks"));
			syncProp();
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
		timer = phaseDuration = 0;
		syncProp();
	}

	/** A restart or game over must not leave upgrade sounds, a weapon, or a prop behind. */
	public void shutdown() {
		finish();
		if (prop != null) { prop.discard(); prop = null; }
	}

	public void tick() {
		if (state == State.UPGRADING && --timer <= 0) {
			Cue.stopAll("zmb_perks_packa_loop", level);
			Cue.at("zmb_perks_packa_ready", level, center());
			Cue.at("zmb_perks_packa_ticktock", level, center());
			state = State.READY; timer = phaseDuration = Sheets.sysInt("pap_pickup_timeout_s") * 20;
		} else if (state == State.READY && --timer <= 0) {
			finish(); // the gun is lost if nobody takes it
		}
		syncProp();
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

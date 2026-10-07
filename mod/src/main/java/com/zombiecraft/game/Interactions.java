package com.zombiecraft.game;

import com.zombiecraft.game.PlayerGame.Gun;
import com.zombiecraft.sheet.Rows.MachineDef;
import com.zombiecraft.sheet.Rows.WallBuyDef;
import com.zombiecraft.sheet.Rows.WeaponDef;
import com.zombiecraft.sheet.Sheets;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.Optional;

/** The F key: wall-buys, the Mystery Box, Pack-a-Punch and window repair. Works out what the player is looking at, shows a prompt, acts. */
public final class Interactions {
	private Interactions() {}

	enum Kind { NONE, WALLBUY, BOX, PAP, MACHINE, BARRIER }

	record Target(Kind kind, WallBuyDef wallbuy, Barrier barrier, MachineDef machine) {
		Target(Kind kind, WallBuyDef wallbuy, Barrier barrier) { this(kind, wallbuy, barrier, null); }
		static final Target NONE = new Target(Kind.NONE, null, null);
	}

	static Target find(Game g, ServerPlayer p) {
		ServerLevel level = g.level;
		double reach = Sheets.sys("interact_reach");
		Vec3 eye = p.getEyePosition(), look = p.getViewVector(1f), end = eye.add(look.scale(reach));

		// wall-buy item frames
		ItemFrame bestFrame = null; double bestD = reach + 1;
		for (ItemFrame f : level.getEntitiesOfClass(ItemFrame.class, new AABB(eye, end).inflate(0.8))) {
			if (f.getTags().stream().noneMatch(t -> t.startsWith("zc_wb:"))) continue;
			Optional<Vec3> hit = f.getBoundingBox().inflate(0.35).clip(eye, end);
			if (hit.isPresent() && hit.get().distanceTo(eye) < bestD) { bestD = hit.get().distanceTo(eye); bestFrame = f; }
		}
		BlockHitResult bh = level.clip(new ClipContext(eye, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, p));
		double blockD = bh.getType() == HitResult.Type.MISS ? Double.MAX_VALUE : bh.getLocation().distanceTo(eye);
		if (bestFrame != null && bestD <= blockD + 0.3) {
			String tag = bestFrame.getTags().stream().filter(t -> t.startsWith("zc_wb:")).findFirst().orElse("");
			String id = tag.substring("zc_wb:".length());
			for (WallBuyDef w : Sheets.WALLBUYS) if (w.id().equals(id)) return new Target(Kind.WALLBUY, w, null);
		}
		if (bh.getType() == HitResult.Type.BLOCK) {
			BlockPos bp = bh.getBlockPos();
			if (g.box != null && g.box.state != BoxSystem.State.GONE && bp.equals(g.box.chestPos())) return new Target(Kind.BOX, null, null);
			if (g.pap != null && g.pap.contains(bp)) return new Target(Kind.PAP, null, null);
			if (g.machines != null) { MachineDef md = g.machines.at(bp); if (md != null) return new Target(Kind.MACHINE, null, null, md); }
		}
		// windows: any damaged window within reach on the inside, wherever the player is looking
		Barrier nearest = null; double nd = 1e9;
		for (Barrier b : g.barriers) {
			if (b.boardsLeft() >= b.boardsTotal()) continue;
			double dx = p.getX() - b.center.x, dz = p.getZ() - b.center.z;
			double dist = Math.sqrt(dx * dx + dz * dz);
			double side = dx * b.insideX + dz * b.insideZ;      // > 0: on the inside
			double lateral = Math.abs(dx * -b.insideZ + dz * b.insideX);
			if (dist > 3.8 || side < 0.3 || lateral > 2.6) continue;
			if (dist < nd) { nd = dist; nearest = b; }
		}
		if (nearest != null) return new Target(Kind.BARRIER, null, nearest);
		return Target.NONE;
	}

	/** Called every tick for every player: prompt, and act on F. */
	public static void update(Game g, ServerPlayer p, PlayerGame pg) {
		Target t = find(g, p);
		pg.interactable = t.kind != Kind.NONE;
		boolean edge = pg.interactHeld && !pg.prevInteract;
		switch (t.kind) {
			case WALLBUY -> {
				WeaponDef w = Sheets.weapon(t.wallbuy.weaponId());
				int slot = WeaponSystem.slotHolding(pg, w.id());
				pg.prompt = slot >= 0
						? "Press F for ammo: " + w.name() + " [" + (int) (w.wallCost() * Sheets.sys("wallbuy_ammo_ratio")) + "]"
						: "Press F to buy " + w.name() + " [" + w.wallCost() + "]";
				if (edge) buyWall(g, p, pg, w);
			}
			case BOX -> {
				pg.prompt = g.box.promptFor(pg);
				if (edge) g.box.use(p, pg);
			}
			case PAP -> {
				pg.prompt = g.pap.promptFor(p, pg);
				if (edge) g.pap.use(p, pg);
			}
			case MACHINE -> {
				pg.prompt = g.machines.promptFor(t.machine, pg);
				if (edge) g.machines.use(t.machine, p, pg);
			}
			case BARRIER -> {
				pg.prompt = "Hold F to repair the window";
				if (pg.interactHeld && g.tick - pg.lastBoardRepair >= Sheets.sysInt("board_repair_ticks") && t.barrier.repair()) {
					pg.lastBoardRepair = g.tick;
					Cue.at("zmb_repair_boards", g.level, t.barrier.center);
					int pts = Sheets.sysInt("board_repair_points");
					int cap = Math.min(Sheets.sysInt("board_cap_max"), Sheets.sysInt("board_cap_mult") * Math.max(1, g.round));
					pg.boardPointsThisRound += pts;
					if (pg.boardPointsThisRound < cap) pg.earn(pts);
				}
			}
			default -> pg.prompt = "";
		}
		pg.prevInteract = pg.interactHeld;
	}

	private static void buyWall(Game g, ServerPlayer p, PlayerGame pg, WeaponDef w) {
		int slot = WeaponSystem.slotHolding(pg, w.id());
		if (slot >= 0) {
			Gun gun = pg.guns[slot];
			int cost = (int) (w.wallCost() * Sheets.sys("wallbuy_ammo_ratio"));
			if (gun.mag >= gun.magSize() && gun.reserve >= gun.reserveMax()) { pg.say("Ammo is already full", 40); return; }
			if (pg.points < cost) { Cue.ui("zmb_no_cha_ching", p); return; }
			pg.points -= cost; gun.refill();
			Cue.ui("zmb_cha_ching", p);
			return;
		}
		if (pg.points < w.wallCost()) { Cue.ui("zmb_no_cha_ching", p); return; }
		pg.points -= w.wallCost();
		WeaponSystem.give(p, pg, WeaponSystem.slotFor(p, pg), w.id(), false);
		Cue.ui("zmb_cha_ching", p);
		Cue.ui("zmb_weap_wall", p);
	}
}

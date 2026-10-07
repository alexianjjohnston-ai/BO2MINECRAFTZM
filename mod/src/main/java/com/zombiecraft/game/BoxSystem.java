package com.zombiecraft.game;

import com.zombiecraft.item.ModItems;
import com.zombiecraft.sheet.Rows.BoxDef;
import com.zombiecraft.sheet.Rows.BoxPoolRow;
import com.zombiecraft.sheet.Rows.BoxRule;
import com.zombiecraft.sheet.Sheets;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.LightBlock;
import com.zombiecraft.entity.ZcBox;
import com.zombiecraft.entity.ZcEntities;
import net.minecraft.world.phys.Vec3;

import java.util.*;

/** The Mystery Box: pay, roll, take the gun (or get the teddy bear and the box moves). */
public final class BoxSystem {
	public enum State { CLOSED, ROLLING, OFFER, TEDDY, GONE }

	private final Game game;
	private final ServerLevel level;
	public State state = State.CLOSED;
	private int locIndex = 0, uses = 0, moves = 0, timer = 0;
	private UUID user;
	private String offered;
	private final Random rng = new Random();

	public BoxSystem(Game game) {
		this.game = game; this.level = game.level;
		for (int i = 0; i < Sheets.BOXES.size(); i++) if (Sheets.BOXES.get(i).initial()) locIndex = i;
		placeChest();
	}

	public BoxDef loc() { return Sheets.BOXES.get(locIndex); }

	public BlockPos chestPos() { return game.origin.offset(loc().x(), loc().y(), loc().z()); }

	private Vec3 center() { BlockPos p = chestPos(); return new Vec3(p.getX() + 0.5, p.getY() + 0.5, p.getZ() + 0.5); }

	private ZcBox boxEntity;
	private boolean sideBlocks;

	/** Unit vector along the box's width (it is wide across its facing). */
	private int[] wide(Direction d) { return d == Direction.EAST || d == Direction.WEST ? new int[] {0, 1} : new int[] {1, 0}; }

	private void placeChest() {
		Direction d = Direction.valueOf(loc().facing().toUpperCase());
		if (LocalAssets.models) {
			// the box mesh (an entity that plays the lid animations) stands on cinder-block feet; an invisible barrier keeps the hit test
			BlockPos cp = chestPos();
			int[] w = wide(d);
			BlockPos left = cp.offset(w[0], 0, w[1]), right = cp.offset(-w[0], 0, -w[1]);
			boolean roomy = level.getBlockState(left).isAir() && level.getBlockState(right).isAir();
			level.setBlock(cp, Blocks.BARRIER.defaultBlockState(), 3);
			level.setBlock(cp.above(), Blocks.LIGHT.defaultBlockState().setValue(LightBlock.LEVEL, 12), 3);
			sideBlocks = roomy;
			if (roomy) { level.setBlock(left, Blocks.BARRIER.defaultBlockState(), 3); level.setBlock(right, Blocks.BARRIER.defaultBlockState(), 3); }
			Vec3 c = center();
			float yaw = switch (d) { case SOUTH -> 0f; case WEST -> 90f; case NORTH -> 180f; default -> -90f; };
			boxEntity = new ZcBox(ZcEntities.BOX, level);
			boxEntity.moveTo(c.x, c.y - 0.5, c.z, yaw, 0f);
			boxEntity.setYRot(yaw);
			boxEntity.addTag("zc");
			boxEntity.getEntityData().set(ZcBox.SCALE, roomy ? 0.0241f : 0.0165f);
			level.addFreshEntity(boxEntity);
			boxEntity.play(moves > 0 ? ZcBox.ARRIVE : ZcBox.IDLE);
			for (int i = -1; i <= 1; i++) {
				double fx = c.x + w[0] * i * (roomy ? 0.85 : 0.5), fz = c.z + w[1] * i * (roomy ? 0.85 : 0.5);
				double sx = w[0] == 1 ? 0.4 : 0.56, sz = w[0] == 1 ? 0.56 : 0.4;
				game.cmd(String.format(Locale.ROOT, "summon block_display %.2f %.2f %.2f {block_state:{Name:\"minecraft:light_gray_concrete\"},Tags:[\"zc\",\"zc_boxfeet\"],transformation:{translation:[0f,0f,0f],left_rotation:[0f,0f,0f,1f],scale:[%.2ff,0.34f,%.2ff],right_rotation:[0f,0f,0f,1f]}}",
						fx - sx / 2, c.y - 0.5, fz - sz / 2, sx, sz));
			}
		} else {
			level.setBlock(chestPos(), Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, d), 3);
		}
	}

	private void removeChest() {
		Direction d = Direction.valueOf(loc().facing().toUpperCase());
		BlockPos cp = chestPos();
		level.setBlock(cp, Blocks.AIR.defaultBlockState(), 3);
		if (boxEntity != null) {
			level.setBlock(cp.above(), Blocks.AIR.defaultBlockState(), 3);
			if (sideBlocks) {
				int[] w = wide(d);
				level.setBlock(cp.offset(w[0], 0, w[1]), Blocks.AIR.defaultBlockState(), 3);
				level.setBlock(cp.offset(-w[0], 0, -w[1]), Blocks.AIR.defaultBlockState(), 3);
			}
			boxEntity.discard();
			boxEntity = null;
			game.cmd("kill @e[tag=zc_boxfeet]");
		}
	}

	private void lid(boolean open) {
		if (boxEntity != null) boxEntity.play(open ? ZcBox.OPEN : ZcBox.CLOSE);
		else level.blockEvent(chestPos(), Blocks.CHEST, 1, open ? 1 : 0);
	}

	private void display(String weapon) {
		Vec3 c = center();
		game.cmd("kill @e[tag=zc_boxdisp]");
		game.cmd(String.format(Locale.ROOT,
				"summon item_display %.2f %.2f %.2f {item:{id:\"zombiecraft:%s\",count:1},billboard:\"center\",Tags:[\"zc\",\"zc_boxdisp\"],transformation:{translation:[0f,0f,0f],left_rotation:[0f,0f,0f,1f],scale:[1.6f,1.6f,1.6f],right_rotation:[0f,0f,0f,1f]}}",
				c.x, c.y + 1.3, c.z, weapon));
	}

	private void showItem(String id) {
		game.cmd("data merge entity @e[tag=zc_boxdisp,limit=1] {item:{id:\"" + id + "\",count:1}}");
	}

	private void clearDisplay() { game.cmd("kill @e[tag=zc_boxdisp]"); }

	public void use(ServerPlayer p, PlayerGame pg) {
		int cost = Sheets.sysInt("box_cost");
		switch (state) {
			case CLOSED -> {
				if (pg.points < cost) { Cue.ui("zmb_no_cha_ching", p); return; }
				pg.points -= cost;
				Cue.ui("zmb_cha_ching", p);
				uses++; user = p.getUUID();
				state = State.ROLLING; timer = 90;
				lid(true);
				Cue.at("zmb_lid_open", level, center());
				Cue.at("zmb_music_box", level, center());
				display(pickWeapon(pg));
			}
			case OFFER -> {
				if (offered == null) return;
				int slot = WeaponSystem.slotFor(p, pg);
				WeaponSystem.give(p, pg, slot, offered, false);
				Cue.ui("zmb_weap_wall", p);
				offered = null;
				close();
			}
			default -> {}
		}
	}

	private void close() {
		lid(false);
		Cue.at("zmb_lid_close", level, center());
		clearDisplay();
		state = State.CLOSED;
	}

	/** Weighted draw from box_pool.json, skipping guns the player already holds. */
	private String pickWeapon(PlayerGame pg) {
		List<BoxPoolRow> pool = new ArrayList<>();
		for (BoxPoolRow r : Sheets.BOX_POOL) if (WeaponSystem.slotHolding(pg, r.weaponId()) < 0) pool.add(r);
		if (pool.isEmpty()) pool.addAll(Sheets.BOX_POOL);
		double total = 0; for (BoxPoolRow r : pool) total += r.weight();
		double roll = rng.nextDouble() * total;
		for (BoxPoolRow r : pool) { roll -= r.weight(); if (roll <= 0) return r.weaponId(); }
		return pool.get(pool.size() - 1).weaponId();
	}

	/** The teddy rules from the BO2 scripts (box_rules.json), evaluated after this use has been counted. */
	private boolean teddy() {
		for (BoxRule r : Sheets.BOX_RULES) {
			if (uses < r.usesMin() || uses > r.usesMax()) continue;
			boolean movesOk = switch (r.moves()) { case "zero" -> moves == 0; case "positive" -> moves > 0; default -> true; };
			if (!movesOk) continue;
			return rng.nextInt(100) < r.teddyPct();
		}
		return false;
	}

	public void tick() {
		switch (state) {
			case ROLLING -> {
				timer--;
				PlayerGame pg = game.players.get(user);
				if (timer % 5 == 0 && pg != null) showItem("zombiecraft:" + pickWeapon(pg));
				if (timer <= 0) {
					if (pg != null && teddy()) {
						state = State.TEDDY; timer = 70;
						showItem("zombiecraft:teddy");
						Cue.at("zmb_laugh_child", level, center());
					} else {
						offered = pg == null ? Sheets.BOX_POOL.get(0).weaponId() : pickWeapon(pg);
						showItem("zombiecraft:" + offered);
						state = State.OFFER; timer = Sheets.sysInt("box_timeout_s") * 20;
					}
				}
			}
			case OFFER -> { if (--timer <= 0) { offered = null; close(); } }
			case TEDDY -> {
				if (--timer <= 0) {
					PlayerGame pg = game.players.get(user);
					if (pg != null) pg.points += Sheets.sysInt("box_cost");
					clearDisplay();
					Cue.at("zmb_box_move", level, center());
					if (boxEntity != null) {
						boxEntity.play(ZcBox.LEAVE);
						level.setBlock(chestPos(), Blocks.AIR.defaultBlockState(), 3);
						game.later(60, () -> { Cue.at("zmb_box_poof", level, center()); removeChest(); });
					} else {
						lid(false);
						Cue.at("zmb_box_poof", level, center());
						removeChest();
					}
					state = State.GONE; timer = 100;
				}
			}
			case GONE -> {
				if (--timer <= 0) {
					locIndex = (locIndex + 1) % Sheets.BOXES.size();
					uses = 0; moves++;
					placeChest();
					Cue.at("zmb_box_poof", level, center());
					state = State.CLOSED;
				}
			}
			default -> {}
		}
	}

	public String promptFor(PlayerGame pg) {
		return switch (state) {
			case CLOSED -> "Hold F for Random Weapon [Cost: " + Sheets.sysInt("box_cost") + "]";
			case OFFER -> "Hold F to take " + Sheets.weapon(offered).name();
			default -> "";
		};
	}
}

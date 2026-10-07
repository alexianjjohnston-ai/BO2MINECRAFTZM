package com.zombiecraft.game;

import com.zombiecraft.sheet.Rows.DoorDef;
import com.zombiecraft.sheet.Rows.WindowDef;
import com.zombiecraft.sheet.Sheets;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.*;

/**
 * The buyable doors. Every door row seals a doorway with blocks; paying for it opens the doorway and unlocks the rooms it leads to.
 * Zombies only spawn at windows of unlocked rooms (BO2's zone activation): a room is open from the start unless some door opens it.
 */
public final class Doors {
	private static final int FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SUPPRESS_DROPS;

	private final Game game;
	private final ServerLevel level;
	private final Set<String> opened = new HashSet<>();
	private final Set<String> openRooms = new HashSet<>();

	public Doors(Game game) {
		this.game = game; this.level = game.level;
		Set<String> locked = new HashSet<>();
		for (DoorDef d : Sheets.DOORS) locked.addAll(rooms(d));
		for (WindowDef w : Sheets.WINDOWS) if (!locked.contains(w.room())) openRooms.add(w.room());
		game.cmd("kill @e[tag=zc_door]");
		for (DoorDef d : Sheets.DOORS) {
			BlockState s = com.zombiecraft.game.Barrier.parse(level, d.block());
			forEach(d, p -> level.setBlock(p, s, FLAGS));
			Vec3 c = center(d);
		}
	}

	private static List<String> rooms(DoorDef d) { return Arrays.asList(d.opens().split(",")); }

	private void forEach(DoorDef d, java.util.function.Consumer<BlockPos> f) {
		BlockPos o = game.origin;
		for (int x = Math.min(d.x1(), d.x2()); x <= Math.max(d.x1(), d.x2()); x++)
			for (int y = Math.min(d.y1(), d.y2()); y <= Math.max(d.y1(), d.y2()); y++)
				for (int z = Math.min(d.z1(), d.z2()); z <= Math.max(d.z1(), d.z2()); z++) f.accept(o.offset(x, y, z));
	}

	private Vec3 center(DoorDef d) {
		BlockPos o = game.origin;
		return new Vec3(o.getX() + (d.x1() + d.x2()) / 2.0 + 0.5, o.getY() + Math.min(d.y1(), d.y2()), o.getZ() + (d.z1() + d.z2()) / 2.0 + 0.5);
	}

	/** The still-closed door that owns this block, or null. */
	public DoorDef at(BlockPos p) {
		BlockPos o = game.origin;
		for (DoorDef d : Sheets.DOORS) {
			if (opened.contains(d.id())) continue;
			if (p.getX() >= o.getX() + Math.min(d.x1(), d.x2()) && p.getX() <= o.getX() + Math.max(d.x1(), d.x2())
					&& p.getY() >= o.getY() + Math.min(d.y1(), d.y2()) && p.getY() <= o.getY() + Math.max(d.y1(), d.y2())
					&& p.getZ() >= o.getZ() + Math.min(d.z1(), d.z2()) && p.getZ() <= o.getZ() + Math.max(d.z1(), d.z2())) return d;
		}
		return null;
	}

	public boolean windowOpen(String windowId) {
		WindowDef w = Sheets.window(windowId);
		return w == null || openRooms.contains(w.room());
	}

	public String promptFor(DoorDef d, PlayerGame pg) {
		return "Hold F to open Door [Cost: " + d.cost() + "]";
	}

	public void use(DoorDef d, ServerPlayer p, PlayerGame pg) {
		if (pg.points < d.cost()) { Cue.ui("zmb_no_cha_ching", p); return; }
		pg.points -= d.cost();
		Cue.ui("zmb_cha_ching", p);
		open(d);
	}

	private void open(DoorDef d) {
		opened.add(d.id());
		BlockState air = Blocks.AIR.defaultBlockState();
		var block = com.zombiecraft.game.Barrier.parse(level, d.block());
		forEach(d, pos -> {
			level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, block), pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, 6, 0.3, 0.3, 0.3, 0.05);
			level.setBlock(pos, air, FLAGS);
		});
		Cue.at(d.cue(), level, center(d));
		openRooms.addAll(rooms(d));
		// a door whose rooms are all open already guards nothing any more: let it swing open for free
		for (DoorDef o : Sheets.DOORS) {
			if (opened.contains(o.id()) || !openRooms.containsAll(rooms(o))) continue;
			opened.add(o.id());
			forEach(o, pos -> level.setBlock(pos, air, FLAGS));
		}
		for (String id : opened) game.cmd("kill @e[tag=zcd_" + id + "]");
	}
}

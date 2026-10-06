package com.zombiecraft.game;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.zombiecraft.sheet.Rows.WindowDef;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.*;

/** One barricaded window: a sill, an opening, and boards (one per opening cell) that zombies tear off and players put back. */
public final class Barrier {
	public final WindowDef def;
	private final ServerLevel level;
	private final BlockPos origin;
	private final List<BlockPos> cells = new ArrayList<>();
	private final boolean[] intact;
	private final Deque<Integer> torn = new ArrayDeque<>();
	private final List<Integer> tearOrder = new ArrayList<>();
	private final BlockState boardState, sillState;
	public final Vec3 outsideSpot, insideSpot, center;
	/** Unit vector (x, z) pointing from the window towards the inside of the building. */
	public final double insideX, insideZ;

	private static final int FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SUPPRESS_DROPS;

	public static BlockState parse(ServerLevel level, String s) {
		try {
			return BlockStateParser.parseForBlock(level.holderLookup(Registries.BLOCK), s, false).blockState();
		} catch (CommandSyntaxException e) {
			throw new IllegalStateException("bad block in sheet: " + s, e);
		}
	}

	public Barrier(ServerLevel level, BlockPos origin, WindowDef def) {
		this.level = level; this.origin = origin; this.def = def;
		this.boardState = parse(level, def.boardBlock());
		this.sillState = parse(level, def.sillBlock());
		boolean horizontal = def.wall().equals("S") || def.wall().equals("N");
		for (int i = 0; i < def.width(); i++)
			for (int j = def.height() - 1; j >= 0; j--) {
				int y = def.y0() + j;
				cells.add(origin.offset(horizontal ? def.a() + i : def.fixed(), y, horizontal ? def.fixed() : def.a() + i));
			}
		intact = new boolean[cells.size()];
		for (int i = 0; i < cells.size(); i++) tearOrder.add(i);
		Collections.shuffle(tearOrder, new Random(def.id().hashCode()));
		int dx = def.wall().equals("E") ? 1 : def.wall().equals("W") ? -1 : 0;
		int dz = def.wall().equals("S") ? 1 : def.wall().equals("N") ? -1 : 0;
		BlockPos mid = origin.offset(horizontal ? def.a() + def.width() / 2 : def.fixed(), 0, horizontal ? def.fixed() : def.a() + def.width() / 2);
		double gy = origin.getY() + 1.0;
		this.center = new Vec3(mid.getX() + 0.5, gy, mid.getZ() + 0.5);
		this.outsideSpot = new Vec3(mid.getX() + 0.5 + dx, gy, mid.getZ() + 0.5 + dz);
		this.insideSpot = new Vec3(mid.getX() + 0.5 - 2 * dx, gy, mid.getZ() + 0.5 - 2 * dz);
		this.insideX = -dx; this.insideZ = -dz;
	}

	/** Carve the opening, lay the sill and put every board back. */
	public void build() {
		boolean horizontal = def.wall().equals("S") || def.wall().equals("N");
		for (int i = 0; i < def.width(); i++) {
			BlockPos sill = origin.offset(horizontal ? def.a() + i : def.fixed(), def.y0() - 1, horizontal ? def.fixed() : def.a() + i);
			level.setBlock(sill, sillState, FLAGS);
		}
		torn.clear();
		for (int i = 0; i < cells.size(); i++) { intact[i] = true; level.setBlock(cells.get(i), boardState, FLAGS); }
	}

	public int boardsLeft() { int n = 0; for (boolean b : intact) if (b) n++; return n; }
	public int boardsTotal() { return intact.length; }
	public boolean open() { return boardsLeft() == 0; }

	public boolean contains(BlockPos p) { return cells.contains(p); }

	/** A zombie tears one board off. Returns true if a board was removed. */
	public boolean tear() {
		for (int idx : tearOrder) {
			if (intact[idx]) {
				intact[idx] = false; torn.push(idx);
				BlockPos p = cells.get(idx);
				level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, boardState), p.getX() + 0.5, p.getY() + 0.5, p.getZ() + 0.5, 10, 0.3, 0.3, 0.3, 0.05);
				level.setBlock(p, Blocks.AIR.defaultBlockState(), FLAGS);
				return true;
			}
		}
		return false;
	}

	/** A player puts one board back (last torn first). Returns true if a board was restored. */
	public boolean repair() {
		if (torn.isEmpty()) return false;
		int idx = torn.pop();
		intact[idx] = true;
		BlockPos p = cells.get(idx);
		level.setBlock(p, boardState, FLAGS);
		level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, boardState), p.getX() + 0.5, p.getY() + 0.5, p.getZ() + 0.5, 6, 0.3, 0.3, 0.3, 0.02);
		return true;
	}
}

package com.zombiecraft.map;

import com.zombiecraft.game.Barrier;
import com.zombiecraft.sheet.Rows.MapOp;
import com.zombiecraft.sheet.Sheets;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Builds the diner and gas station from map_ops.json. Coordinates in the sheet are relative to the origin (y=0 is the ground layer). */
public final class MapBuilder {
	private MapBuilder() {}

	private static final int FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SUPPRESS_DROPS;

	public static int buildOps(ServerLevel level, BlockPos origin) {
		Map<String, BlockState> cache = new HashMap<>();
		List<MapOp> ops = Sheets.MAP_OPS.stream().sorted(Comparator.comparingInt(MapOp::order)).toList();
		int placed = 0;
		// a world that already holds another map (picked in the menu) must not keep pieces of it: clear a generous box first
		BlockState air = net.minecraft.world.level.block.Blocks.AIR.defaultBlockState();
		for (int x = -60; x <= 60; x++) for (int y = 1; y <= 30; y++) for (int z = -50; z <= 50; z++) {
			BlockPos p = origin.offset(x, y, z);
			if (!level.getBlockState(p).isAir()) level.setBlock(p, air, FLAGS);
		}
		for (MapOp o : ops) {
			boolean imp = o.op().equals("import"); // its "block" is a map file name, not a block
			BlockState a = imp ? null : cache.computeIfAbsent(o.block(), s -> Barrier.parse(level, s));
			BlockState b = o.block2() == null ? a : cache.computeIfAbsent(o.block2(), s -> Barrier.parse(level, s));
			int x1 = Math.min(o.x1(), o.x2()), x2 = Math.max(o.x1(), o.x2());
			int y1 = Math.min(o.y1(), o.y2()), y2 = Math.max(o.y1(), o.y2());
			int z1 = Math.min(o.z1(), o.z2()), z2 = Math.max(o.z1(), o.z2());
			switch (o.op()) {
				case "fill" -> {
					for (int x = x1; x <= x2; x++) for (int y = y1; y <= y2; y++) for (int z = z1; z <= z2; z++) { set(level, origin, x, y, z, a); placed++; }
				}
				case "walls" -> {
					for (int x = x1; x <= x2; x++) for (int y = y1; y <= y2; y++) for (int z = z1; z <= z2; z++)
						if (x == x1 || x == x2 || z == z1 || z == z2) { set(level, origin, x, y, z, a); placed++; }
				}
				case "checker" -> {
					for (int x = x1; x <= x2; x++) for (int y = y1; y <= y2; y++) for (int z = z1; z <= z2; z++) { set(level, origin, x, y, z, ((x + z) & 1) == 0 ? a : b); placed++; }
				}
				case "grid" -> {
					int sx = Math.max(1, o.stepX()), sz = Math.max(1, o.stepZ());
					for (int x = x1; x <= x2; x += sx) for (int z = z1; z <= z2; z += sz) for (int y = y1; y <= y2; y++) { set(level, origin, x, y, z, a); placed++; }
				}
				case "import" -> placed += ImportedMap.place(level, origin, o.block(), o.x1(), o.y1(), o.z1());
				default -> throw new IllegalStateException("unknown map op " + o.op() + " in " + o.id());
			}
		}
		return placed;
	}

	private static void set(ServerLevel level, BlockPos origin, int x, int y, int z, BlockState s) {
		level.setBlock(origin.offset(x, y, z), s, FLAGS);
	}
}

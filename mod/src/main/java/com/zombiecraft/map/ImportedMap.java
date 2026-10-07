package com.zombiecraft.map;

import com.google.gson.Gson;
import com.zombiecraft.game.Barrier;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPInputStream;

/**
 * A block file cut from the player's own copy of the Tranzit Reimagined world (tools/extract_tranzit.py). It is never shipped with the mod:
 * it is read from {@code <game dir>/config/zombiecraft/maps/<name>.json.gz}, or from {@code maps_local/} next to the repo when running from source.
 * Layout: rle is runs [paletteIndex, count] in y, z, x order; groundRow is the grass layer (it lands on y=0 of the sheet frame).
 */
public final class ImportedMap {
	private ImportedMap() {}

	private static final int FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SUPPRESS_DROPS;

	private record File(int[] size, int groundRow, List<String> palette, int[][] rle) {}

	/** True when the player has extracted this map (menus only offer the imported maps then). */
	public static boolean available(String name) {
		try { find(name); return true; } catch (IllegalStateException e) { return false; }
	}

	private static Path find(String name) {
		Path game = FabricLoader.getInstance().getGameDir();
		List<Path> dirs = new ArrayList<>();
		String over = System.getProperty("zombiecraft.maps", "");
		if (!over.isBlank()) dirs.add(Path.of(over));
		dirs.add(game.resolve("config").resolve("zombiecraft").resolve("maps"));
		for (Path p = game.toAbsolutePath().normalize(); p != null; p = p.getParent()) dirs.add(p.resolve("maps_local")); // running from source: the repo's maps_local
		for (Path d : dirs) {
			Path f = d.resolve(name + ".json.gz");
			if (Files.isRegularFile(f)) return f;
		}
		throw new IllegalStateException("Map file " + name + ".json.gz not found. Run tools/extract_tranzit.py on your Tranzit Reimagined download and put the result in "
				+ game.resolve("config").resolve("zombiecraft").resolve("maps") + " (searched: " + dirs + ")");
	}

	/** Pastes the map so that its grass layer is y=0 and its (0, 0) corner lands on (ox, oz) of the sheet frame. Returns the number of blocks set. */
	public static int place(ServerLevel level, BlockPos origin, String name, int ox, int oy, int oz) { return place(level, origin, name, ox, oy, oz, false); }

	/** With skipAir the air runs are left alone (cheap backdrops: a big scenery file is mostly sky; whatever was there stays, MapBuilder clears a box first). */
	public static int place(ServerLevel level, BlockPos origin, String name, int ox, int oy, int oz, boolean skipAir) {
		File f;
		try (var in = new InputStreamReader(new GZIPInputStream(Files.newInputStream(find(name))), StandardCharsets.UTF_8)) {
			f = new Gson().fromJson(in, File.class);
		} catch (IOException e) {
			throw new IllegalStateException("cannot read map " + name, e);
		}
		BlockState[] states = new BlockState[f.palette().size()];
		int bad = 0;
		for (int i = 0; i < states.length; i++) {
			try { states[i] = Barrier.parse(level, f.palette().get(i)); }
			catch (RuntimeException e) { states[i] = Blocks.AIR.defaultBlockState(); bad++; }
		}
		int sx = f.size()[0], sy = f.size()[1], sz = f.size()[2], i = 0, placed = 0;
		BlockPos base = origin.offset(ox, oy - f.groundRow(), oz);
		for (int[] run : f.rle()) {
			BlockState s = states[run[0]];
			if (skipAir && s.isAir()) { i += run[1]; continue; }
			for (int n = 0; n < run[1]; n++, i++) {
				int x = i % sx, z = (i / sx) % sz, y = i / (sx * sz);
				level.setBlock(base.offset(x, y, z), s, FLAGS);
				placed++;
			}
		}
		if (bad > 0) com.zombiecraft.ZombiecraftMod.LOG.warn("map {}: {} block kinds are not in this Minecraft version and became air", name, bad);
		return placed;
	}
}

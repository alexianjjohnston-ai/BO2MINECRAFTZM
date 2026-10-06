package com.zombiecraft.bo2;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;

/**
 * Menu and loading-screen art from the player's own Black Ops II install, converted to PNG in the local cache
 * ({@code <game>/zombiecraft/bo2/ui/}). Nothing ships with the mod; without the install the menus fall back to plain drawing.
 */
public final class UiAssets {
	private UiAssets() {}

	/** Bump when {@link #IMAGES} changes: the set is rebuilt once. */
	public static final int VERSION = 1;
	/** Patches first: the first zone that has an image wins. */
	static final String[] ZONES = {"patch_ui_zm", "ui_zm", "patch_zm", "code_post_gfx_zm"};
	public static final String[] IMAGES = {"lui_bkg_zm", "lui_bkg_zm_rocks_back", "lui_bkg_zm_rocks_front", "menu_zm_title_screen",
			"loadscreen_transit_standard_town", "loadscreen_transit_standard_busdepot",
			"loadscreen_transit_standard_farm", "loadscreen_transit_classic"};

	public static Path dir(Path gameDir) { return Bo2Assets.cacheDir(gameDir).resolve("ui"); }
	public static Path file(Path gameDir, String name) { return dir(gameDir).resolve(name + ".png"); }
	private static Path marker(Path gameDir) { return dir(gameDir).resolve("ready-v" + VERSION); }

	public static boolean ready(Path gameDir) { return Files.isRegularFile(marker(gameDir)); }

	/** {@code existingDumps} (dev only) are folders that already hold unlinked zones, which skips the Unlinker run. */
	public static void prepare(Path gameDir, Path bo2Dir, List<Path> existingDumps, Consumer<String> log) throws IOException {
		Path out = dir(gameDir), temp = out.resolve("_dump");
		Files.createDirectories(out);
		List<Path> roots = new ArrayList<>(existingDumps);
		boolean own = roots.isEmpty();
		if (own) {
			Path exe = Bo2Assets.findUnlinker(gameDir).orElseThrow(() -> new IOException("OpenAssetTools Unlinker.exe not found (put it in zombiecraft/tools/oat or set ZOMBIECRAFT_OAT_DIR)"));
			log.accept("Reading Black Ops II menu art (first run only)...");
			Bo2Assets.unlink(exe, bo2Dir, temp, log, ZONES, new String[] {"image"});
			roots.add(temp);
		}
		try {
			int n = 0;
			for (String name : IMAGES) {
				Path src = find(roots, name);
				if (src == null) { log.accept("menu image not found: " + name); continue; }
				Dds.Image im = Dds.read(src);
				BufferedImage bi = new BufferedImage(im.width(), im.height(), BufferedImage.TYPE_INT_ARGB);
				bi.setRGB(0, 0, im.width(), im.height(), im.argb(), 0, im.width());
				ImageIO.write(bi, "png", file(gameDir, name).toFile());
				n++;
			}
			Files.writeString(marker(gameDir), "ok");
			log.accept("Black Ops II menu art ready: " + n + " images");
		} finally {
			if (own) Bo2Assets.deleteTree(temp);
		}
	}

	private static Path find(List<Path> roots, String name) throws IOException {
		for (String zone : ZONES)
			for (Path root : roots)
				for (Path base : List.of(root.resolve(zone), root)) {
					Path f = base.resolve("images").resolve(name + ".dds");
					if (Files.isRegularFile(f)) return f;
				}
		return null;
	}
}

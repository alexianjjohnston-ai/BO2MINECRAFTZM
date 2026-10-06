package com.zombiecraft.bo2;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * Menu and loading-screen art from the player's own Black Ops II install, converted to PNG in the local cache
 * ({@code <game>/zombiecraft/bo2/ui/}). Nothing ships with the mod; without the install the menus fall back to plain drawing.
 */
public final class UiAssets {
	private UiAssets() {}

	/** Bump when {@link #IMAGES} changes: the set is rebuilt once. */
	public static final int VERSION = 3;
	/** Patches first: the first zone that has an image wins. */
	static final String[] ZONES = {"patch_ui_zm", "ui_zm", "patch_zm", "code_post_gfx_zm", "zm_transit"};
	public static final String[] IMAGES = concat(
			new String[] {"lui_bkg_zm", "lui_bkg_zm_rocks_back", "lui_bkg_zm_rocks_front", "menu_zm_title_screen",
					"loadscreen_transit_standard_town", "loadscreen_transit_standard_busdepot", "loadscreen_transit_standard_farm", "loadscreen_transit_classic",
					"menu_zm_tranzit_map_select_final", "menu_zm_map_frame", "menu_zm_map_signpost_nuketown", "menu_zm_map_signpost_transit", "pc_lock", "globe_map_zm", "menu_zm_map_transit_large",
					"menu_zm_map_transit_blit_depot", "menu_zm_map_transit_blit_diner", "menu_zm_map_transit_blit_farm", "menu_zm_map_transit_blit_power", "menu_zm_map_transit_blit_town",
					"menu_zm_weapons_raygun_big", "specialty_juggernaut_zombies", "specialty_fastreload_zombies", "specialty_doubletap_zombies", "specialty_quickrevive_zombies"},
			names("menu_mp_weapons_%s_big", "1911", "olympia", "mp5", "ak74u", "m14", "m16", "galil", "python"));

	private static String[] names(String fmt, String... ids) { return Arrays.stream(ids).map(i -> fmt.formatted(i)).toArray(String[]::new); }
	private static String[] concat(String[] a, String[] b) { return Stream.concat(Arrays.stream(a), Arrays.stream(b)).toArray(String[]::new); }

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
				int[] px = im.argb();
				// weapon icons are drawn on solid black: make black transparent
				if (name.contains("_weapons_")) for (int i = 0; i < px.length; i++) { int c = px[i]; px[i] = (Math.max((c >> 16) & 255, Math.max((c >> 8) & 255, c & 255)) << 24) | (c & 0xFFFFFF); }
				bi.setRGB(0, 0, im.width(), im.height(), px, 0, im.width());
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

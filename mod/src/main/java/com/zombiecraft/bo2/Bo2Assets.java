package com.zombiecraft.bo2;

import com.zombiecraft.sheet.Rows.Bo2Model;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * Turns the player's own Black Ops II models and textures into a small local cache the game can render.
 * <p>
 * Nothing from BO2 ships with the mod. On first run this reads the player's install (read-only) through OpenAssetTools'
 * Unlinker (a separate GPL-3.0 program, found next to the game or set with ZOMBIECRAFT_OAT_DIR), converts only the models the
 * {@code bo2_models} sheet names plus the textures they use into {@code <game>/zombiecraft/bo2/}, then deletes the raw dump.
 */
public final class Bo2Assets {
	private Bo2Assets() {}

	/** Bump when the cache format or the converted set changes: the cache is rebuilt once. */
	public static final int VERSION = 6;

	/** Zones that hold the models, in order of preference (patches override the base zone). */
	static final String[] ZONES = {"zm_transit_patch", "patch_zm", "zm_transit", "so_zclassic_zm_transit", "common_zm"};
	static final String[] ASSET_TYPES = {"xmodel", "material", "image", "xanim"};
	/** Animations copied into the cache (compiled xanim files are small). The renderer picks them by these names. */
	public static final String[] ANIMS = {"ai_zombie_walk_v1", "ai_zombie_walk_v2", "ai_zombie_walk_v3", "ai_zombie_walk_v4",
			"ai_zombie_run_v2", "ai_zombie_run_v3", "ai_zombie_sprint_v1", "ai_zombie_sprint_v2", "ai_zombie_attack_v1", "ai_zombie_attack_v2",
			"ai_zombie_boardtear_aligned_m_1_pull", "ai_zombie_barricade_enter_m_v1", "ch_dazed_a_death", "ch_dazed_b_death",
			"o_zombie_magic_box_open", "o_zombie_magic_box_close", "o_zombie_magic_box_leave", "o_zombie_magic_box_arrive"};

	public static Path cacheDir(Path gameDir) { return gameDir.resolve("zombiecraft").resolve("bo2"); }

	public static boolean ready(Path gameDir, Path bo2Dir) {
		Properties p = manifest(cacheDir(gameDir));
		return p != null && p.getProperty("version", "").equals(String.valueOf(VERSION)) && p.getProperty("fingerprint", "").equals(fingerprint(bo2Dir));
	}

	private static Properties manifest(Path cache) {
		Path f = cache.resolve("manifest.properties");
		if (!Files.isRegularFile(f)) return null;
		try (var in = Files.newInputStream(f)) { Properties p = new Properties(); p.load(in); return p; }
		catch (IOException e) { return null; }
	}

	/** Cheap identity of the install's model zone: size and modification time of zm_transit.ff. */
	static String fingerprint(Path bo2Dir) {
		try {
			Path z = bo2Dir.resolve("zone").resolve("all").resolve("zm_transit.ff");
			return Files.size(z) + "-" + Files.getLastModifiedTime(z).toMillis();
		} catch (IOException | RuntimeException e) { return "unknown"; }
	}

	public static Optional<Path> findUnlinker(Path gameDir) {
		List<Path> dirs = new ArrayList<>();
		for (String k : new String[]{"ZOMBIECRAFT_OAT_DIR"}) { String v = System.getenv(k); if (v != null && !v.isBlank()) dirs.add(Path.of(v)); }
		String prop = System.getProperty("zombiecraft.oat");
		if (prop != null && !prop.isBlank()) dirs.add(Path.of(prop));
		dirs.add(gameDir.resolve("zombiecraft").resolve("tools").resolve("oat"));
		dirs.add(gameDir.resolve("tools").resolve("oat"));
		try {
			Path cfg = gameDir.resolve("config").resolve("zombiecraft.properties");
			if (Files.isRegularFile(cfg)) {
				Properties p = new Properties();
				try (var in = Files.newInputStream(cfg)) { p.load(in); }
				String v = p.getProperty("oat.dir");
				if (v != null && !v.isBlank()) dirs.add(Path.of(v.trim()));
			}
		} catch (IOException | RuntimeException ignored) {}
		for (Path d : dirs) {
			Path exe = d.resolve("Unlinker.exe");
			if (Files.isRegularFile(exe)) return Optional.of(exe);
		}
		return Optional.empty();
	}

	/**
	 * Builds the cache. {@code existingDumps} (dev only, -Dzombiecraft.bo2dump) are folders that already hold unlinked zones, which skips the
	 * Unlinker run. Returns the number of models written.
	 */
	public static int prepare(Path gameDir, Path bo2Dir, List<Path> existingDumps, Consumer<String> log) throws IOException {
		Path cache = cacheDir(gameDir);
		Files.createDirectories(cache);
		List<Path> dumpRoots = new ArrayList<>(existingDumps);
		Path temp = cache.resolve("_dump");
		boolean ownDump = false;
		if (dumpRoots.isEmpty()) {
			Path exe = findUnlinker(gameDir).orElseThrow(() -> new IOException("OpenAssetTools Unlinker.exe not found (put it in zombiecraft/tools/oat or set ZOMBIECRAFT_OAT_DIR)"));
			log.accept("Reading Black Ops II models (first run only, about a minute)...");
			unlink(exe, bo2Dir, temp, log, ZONES, ASSET_TYPES);
			dumpRoots.add(temp);
			ownDump = true;
		}
		try {
			return convert(cache, dumpRoots, bo2Dir, log);
		} finally {
			if (ownDump) deleteTree(temp);
		}
	}

	static void unlink(Path exe, Path bo2Dir, Path out, Consumer<String> log, String[] zones, String[] types) throws IOException {
		Files.createDirectories(out);
		Path zoneDir = bo2Dir.resolve("zone").resolve("all");
		List<String> cmd = new ArrayList<>(List.of(exe.toString(), "--no-color", "--model-format", "XMODEL_EXPORT", "--image-format", "DDS",
				"--include-assets", String.join(",", types), "-o", out.toString() + "/?zone?"));
		for (String z : zones) {
			Path ff = zoneDir.resolve(z + ".ff");
			if (Files.isRegularFile(ff)) cmd.add(ff.toString());
		}
		Process pr = new ProcessBuilder(cmd).directory(exe.getParent().toFile()).redirectErrorStream(true).start();
		Path logFile = out.resolve("unlinker.log");
		int n = 0;
		try (BufferedReader r = new BufferedReader(new InputStreamReader(pr.getInputStream())); var w = Files.newBufferedWriter(logFile)) {
			String line;
			while ((line = r.readLine()) != null) {
				w.write(line); w.newLine();
				if (line.startsWith("Dumped") && (++n % 400) == 0) log.accept("Reading Black Ops II data... " + n + " assets");
			}
		}
		try {
			if (!pr.waitFor(15, TimeUnit.MINUTES)) { pr.destroyForcibly(); throw new IOException("Unlinker timed out"); }
		} catch (InterruptedException e) { pr.destroyForcibly(); throw new IOException("interrupted"); }
		if (pr.exitValue() != 0) throw new IOException("Unlinker failed with exit code " + pr.exitValue() + " (see " + logFile + ")");
	}

	private static int convert(Path cache, List<Path> roots, Path bo2Dir, Consumer<String> log) throws IOException {
		// every zone folder that has a model_export directory, preferred zones first
		List<Path> zoneDirs = new ArrayList<>();
		for (Path root : roots) {
			if (Files.isDirectory(root.resolve("model_export"))) zoneDirs.add(root);
			List<Path> kids;
			try (Stream<Path> s = Files.list(root)) { kids = s.filter(Files::isDirectory).toList(); }
			for (Path k : kids) if (Files.isDirectory(k.resolve("model_export"))) zoneDirs.add(k);
		}
		zoneDirs.sort(Comparator.comparingInt((Path p) -> {
			int i = Arrays.asList(ZONES).indexOf(p.getFileName().toString());
			return i < 0 ? 99 : i;
		}));
		Path models = cache.resolve("models"), tex = cache.resolve("tex");
		Files.createDirectories(models); Files.createDirectories(tex);

		Set<String> wanted = new LinkedHashSet<>();
		for (Bo2Model m : com.zombiecraft.sheet.Sheets.BO2_MODELS) {
			wanted.add(m.xmodel());
			if (m.world() != null && !m.world().isEmpty()) wanted.add(m.world());
		}
		int written = 0, textures = 0; List<String> missing = new ArrayList<>();
		Set<String> doneTex = new HashSet<>();
		for (String name : wanted) {
			Path src = null;
			for (Path z : zoneDirs) {
				Path f = z.resolve("model_export").resolve(name + "_lod0.xmodel_export");
				if (Files.isRegularFile(f)) { src = f; break; }
			}
			if (src == null) { missing.add(name); continue; }
			XModel m = XModel.read(src);
			m.name = name;
			for (int mi = 0; mi < m.materials.size(); mi++) {
				XModel.Material mat0 = m.materials.get(mi);
				String better = colorMapFor(zoneDirs, mat0.name());
				if (better != null) m.materials.set(mi, new XModel.Material(mat0.name(), better));
				XModel.Material mat = m.materials.get(mi);
				String t = mat.texture();
				if (t.isEmpty() || !doneTex.add(t)) continue;
				Path found = null;
				for (Path z : zoneDirs) { Path f = z.resolve("images").resolve(t); if (Files.isRegularFile(f)) { found = f; break; } }
				if (found == null) continue;
				try {
					Dds.Image im = Dds.read(found);
					BufferedImage bi = new BufferedImage(im.width(), im.height(), BufferedImage.TYPE_INT_ARGB);
					bi.setRGB(0, 0, im.width(), im.height(), im.argb(), 0, im.width());
					ImageIO.write(bi, "png", tex.resolve(texName(t)).toFile());
					textures++;
				} catch (IOException | RuntimeException e) { log.accept("texture " + t + " skipped: " + e.getMessage()); }
			}
			m.writeCompact(models.resolve(name + ".zcm"));
			written++;
		}
		Path anims = cache.resolve("anims");
		Files.createDirectories(anims);
		for (String a : ANIMS)
			for (Path z : zoneDirs) {
				Path f = z.resolve("xanim").resolve(a);
				if (Files.isRegularFile(f)) { Files.copy(f, anims.resolve(a), StandardCopyOption.REPLACE_EXISTING); break; }
			}
		try { TexturePack.build(cache.getParent().getParent(), zoneDirs, TexturePack.configuredSize(), log); }
		catch (IOException | RuntimeException e) { log.accept("texture pack skipped: " + e); }
		if (!missing.isEmpty()) log.accept("models not found in this install: " + missing);
		Properties p = new Properties();
		p.setProperty("version", String.valueOf(VERSION));
		p.setProperty("fingerprint", fingerprint(bo2Dir));
		p.setProperty("models", String.valueOf(written));
		p.setProperty("textures", String.valueOf(textures));
		try (var out = Files.newOutputStream(cache.resolve("manifest.properties"))) { p.store(out, "Zombiecraft Black Ops II asset cache (generated on this PC)"); }
		log.accept("Black Ops II models ready: " + written + " models, " + textures + " textures");
		return written;
	}

	/**
	 * The export names a material's first image as its texture, which is sometimes a mask. The material file (materials/&lt;name&gt;.json)
	 * says which image is the diffuse map. Returns that image's DDS file name, or null when unknown.
	 */
	public static String colorMapFor(List<Path> zoneDirs, String material) {
		for (Path z : zoneDirs) {
			Path f = z.resolve("materials").resolve(material + ".json");
			if (!Files.isRegularFile(f)) continue;
			try (var r = Files.newBufferedReader(f)) {
				var root = com.google.gson.JsonParser.parseReader(r).getAsJsonObject();
				var tex = root.getAsJsonArray("textures");
				if (tex == null) return null;
				String first = null, named = null;
				for (var e : tex) {
					var o = e.getAsJsonObject();
					if (!"colorMap".equals(o.has("semantic") ? o.get("semantic").getAsString() : "")) continue;
					String img = o.get("image").getAsString();
					if (first == null) first = img;
					String nm = o.has("name") ? o.get("name").getAsString() : "";
					if (named == null && (nm.equalsIgnoreCase("Diffuse_Map") || nm.equalsIgnoreCase("Color_Map") || nm.equalsIgnoreCase("colorMap"))) named = img;
				}
				String pick = named != null ? named : first;
				return pick == null ? null : pick + ".dds";
			} catch (IOException | RuntimeException e) { return null; }
		}
		return null;
	}

	/** File name of a converted texture: the DDS name without extension, made safe for resource ids. */
	public static String texName(String dds) {
		String n = dds.replaceAll("\\.dds$", "").toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]", "_");
		return n + ".png";
	}

	public static Path modelFile(Path gameDir, String xmodel) { return cacheDir(gameDir).resolve("models").resolve(xmodel + ".zcm"); }
	public static Path textureFile(Path gameDir, String ddsName) { return cacheDir(gameDir).resolve("tex").resolve(texName(ddsName)); }

	static void deleteTree(Path p) {
		if (!Files.exists(p)) return;
		try (Stream<Path> s = Files.walk(p)) {
			s.sorted(Comparator.reverseOrder()).forEach(f -> { try { Files.delete(f); } catch (IOException ignored) {} });
		} catch (IOException ignored) {}
	}
}

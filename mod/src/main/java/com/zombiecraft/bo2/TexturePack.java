package com.zombiecraft.bo2;

import com.zombiecraft.sheet.Rows.TextureDef;
import com.zombiecraft.sheet.Sheets;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Builds the "Block Ops 2" resource pack on the player's PC from their own Black Ops II images, following sheets/textures.json:
 * every row turns one BO2 image into one block texture (centre-cropped square, optionally flattened to opaque, tinted, scaled down).
 * The pack lives in {@code <game>/resourcepacks/BlockOps2}; nothing from it ever ships with the mod. Maps add rows, never code.
 */
public final class TexturePack {
	private TexturePack() {}

	public static final String FOLDER = "BlockOps2";
	public static final int PACK_FORMAT = 46; // Minecraft 1.21.4

	public static Path dir(Path gameDir) { return gameDir.resolve("resourcepacks").resolve(FOLDER); }

	/** Image {@code name} (BO2 colour maps are stored as ~-g&lt;name&gt;.dds or &lt;name&gt;.dds) from the first zone that has it. */
	private static Dds.Image find(List<Path> zones, String name) {
		for (Path z : zones)
			for (String f : new String[] {"~-g" + name + ".dds", name + ".dds"}) {
				Path p = z.resolve("images").resolve(f);
				if (Files.isRegularFile(p)) {
					try { return Dds.read(p); } catch (IOException | RuntimeException ignored) {}
				}
			}
		return null;
	}

	private static BufferedImage toBuffered(Dds.Image im) {
		BufferedImage b = new BufferedImage(im.width(), im.height(), BufferedImage.TYPE_INT_ARGB);
		b.setRGB(0, 0, im.width(), im.height(), im.argb(), 0, im.width());
		return b;
	}

	/** Largest centred square (BO2 textures are often strips, and Minecraft block textures are square). */
	private static BufferedImage square(BufferedImage b) {
		int s = Math.min(b.getWidth(), b.getHeight());
		return b.getSubimage((b.getWidth() - s) / 2, (b.getHeight() - s) / 2, s, s);
	}

	private static BufferedImage scale(BufferedImage b, int size) {
		BufferedImage cur = b;
		while (cur.getWidth() > size * 2) cur = resize(cur, cur.getWidth() / 2);
		return cur.getWidth() == size ? cur : resize(cur, size);
	}

	private static BufferedImage resize(BufferedImage b, int size) {
		BufferedImage o = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = o.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
		g.drawImage(b, 0, 0, size, size, null);
		g.dispose();
		return o;
	}

	/** Replace transparency by the image's own average colour, so solid blocks never show holes. */
	private static void flatten(BufferedImage b) {
		long r = 0, g = 0, bl = 0, n = 0;
		for (int y = 0; y < b.getHeight(); y++)
			for (int x = 0; x < b.getWidth(); x++) {
				int c = b.getRGB(x, y);
				if ((c >>> 24) > 160) { r += c >> 16 & 255; g += c >> 8 & 255; bl += c & 255; n++; }
			}
		int ar = n == 0 ? 90 : (int) (r / n), ag = n == 0 ? 90 : (int) (g / n), ab = n == 0 ? 90 : (int) (bl / n);
		for (int y = 0; y < b.getHeight(); y++)
			for (int x = 0; x < b.getWidth(); x++) {
				int c = b.getRGB(x, y), a = c >>> 24;
				int nr = (ar * (255 - a) + (c >> 16 & 255) * a) / 255, ng = (ag * (255 - a) + (c >> 8 & 255) * a) / 255, nb = (ab * (255 - a) + (c & 255) * a) / 255;
				b.setRGB(x, y, 0xFF000000 | nr << 16 | ng << 8 | nb);
			}
	}

	private static void tint(BufferedImage b, String hex) {
		if (hex == null || hex.isBlank()) return;
		int t = Integer.parseInt(hex.replace("#", ""), 16);
		int tr = t >> 16 & 255, tg = t >> 8 & 255, tb = t & 255;
		for (int y = 0; y < b.getHeight(); y++)
			for (int x = 0; x < b.getWidth(); x++) {
				int c = b.getRGB(x, y);
				b.setRGB(x, y, (c & 0xFF000000) | ((c >> 16 & 255) * tr / 255) << 16 | ((c >> 8 & 255) * tg / 255) << 8 | (c & 255) * tb / 255);
			}
	}

	/** Cut-out blocks (leaves, glass, plants, doors, chains ...) keep Minecraft's shape: the alpha of the vanilla texture is stretched over the BO2 pixels. */
	private static void applyShape(BufferedImage img, String texture) {
		String ns = texture.contains(":") ? texture.substring(0, texture.indexOf(':')) : "minecraft";
		String name = texture.substring(texture.indexOf(':') + 1);
		try (var in = TexturePack.class.getResourceAsStream("/assets/" + ns + "/textures/block/" + name + ".png")) {
			if (in == null) return;
			BufferedImage v = ImageIO.read(in);
			int vs = Math.min(v.getWidth(), v.getHeight()); // animated strips: the first frame
			for (int y = 0; y < img.getHeight(); y++)
				for (int x = 0; x < img.getWidth(); x++) {
					int a = v.getRGB(x * vs / img.getWidth(), y * vs / img.getHeight()) >>> 24;
					img.setRGB(x, y, (img.getRGB(x, y) & 0x00FFFFFF) | a << 24);
				}
		} catch (IOException | RuntimeException ignored) {}
	}

	private static BufferedImage load(List<Path> zones, TextureDef d, int size) {
		if (d.bo2().startsWith("checker:")) {
			String[] two = d.bo2().substring(8).split("\\+");
			Dds.Image a = find(zones, two[0]), b = find(zones, two[1]);
			if (a == null || b == null) return null;
			BufferedImage ba = scale(square(toBuffered(a)), size / 2), bb = scale(square(toBuffered(b)), size / 2);
			BufferedImage o = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
			Graphics2D g = o.createGraphics();
			g.drawImage(ba, 0, 0, null); g.drawImage(bb, size / 2, 0, null);
			g.drawImage(bb, 0, size / 2, null); g.drawImage(ba, size / 2, size / 2, null);
			g.dispose();
			return o;
		}
		Dds.Image im = find(zones, d.bo2());
		if (im == null) return null;
		BufferedImage b = square(toBuffered(im));
		// flatten at full size so the average comes from real pixels, then shrink
		BufferedImage copy = new BufferedImage(b.getWidth(), b.getHeight(), BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = copy.createGraphics(); g.drawImage(b, 0, 0, null); g.dispose();
		if (d.opaque() || d.mask()) flatten(copy);
		return scale(copy, size);
	}

	private static void solid(Path file, int w, int h, int argb) throws IOException {
		BufferedImage b = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
		for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) b.setRGB(x, y, argb);
		Files.createDirectories(file.getParent());
		ImageIO.write(b, "png", file.toFile());
	}

	/** Writes the pack and returns how many block textures it holds. */
	public static int build(Path gameDir, List<Path> zones, int size, Consumer<String> log) throws IOException {
		Path root = dir(gameDir);
		Map<String, TextureDef> rows = new LinkedHashMap<>();
		for (TextureDef d : Sheets.TEXTURES) rows.putIfAbsent(d.texture(), d); // first row for a texture wins
		int written = 0;
		java.util.List<String> missing = new java.util.ArrayList<>();
		for (TextureDef d : rows.values()) {
			BufferedImage img = load(zones, d, size);
			if (img == null) { missing.add(d.bo2()); continue; }
			tint(img, d.tint());
			if (d.mask()) applyShape(img, d.texture());
			String ns = d.texture().contains(":") ? d.texture().substring(0, d.texture().indexOf(':')) : "minecraft";
			String name = d.texture().substring(d.texture().indexOf(':') + 1);
			Path out = root.resolve("assets").resolve(ns).resolve("textures").resolve("block").resolve(name + ".png");
			Files.createDirectories(out.getParent());
			ImageIO.write(img, "png", out.toFile());
			written++;
		}
		// vanilla tints grass and leaves by biome colour maps: neutral ones keep BO2's dead, dusty look
		solid(root.resolve("assets/minecraft/textures/colormap/grass.png"), 256, 256, 0xFFB4B09A);
		solid(root.resolve("assets/minecraft/textures/colormap/foliage.png"), 256, 256, 0xFF9C9884);
		solid(root.resolve("assets/minecraft/textures/block/grass_block_side_overlay.png"), 16, 16, 0x00000000);
		Files.writeString(root.resolve("pack.mcmeta"), "{\"pack\":{\"pack_format\":" + PACK_FORMAT
				+ ",\"description\":\"Block Ops 2: textures made on this PC from your own Black Ops II install\"}}", StandardCharsets.UTF_8);
		if (!missing.isEmpty()) log.accept("texture pack: images not found in this install: " + missing);
		log.accept("texture pack ready: " + written + " block textures at " + size + "px in " + root);
		return written;
	}

	/** Pack size in pixels per block texture: {@code -Dzombiecraft.packSize} or 128. */
	public static int configuredSize() {
		try { return Math.max(16, Math.min(512, Integer.parseInt(System.getProperty("zombiecraft.packSize", "128")))); }
		catch (NumberFormatException e) { return 128; }
	}

}

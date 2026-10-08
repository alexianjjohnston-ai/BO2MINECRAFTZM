package com.zombiecraft.client.menu;

import com.mojang.blaze3d.platform.NativeImage;
import com.zombiecraft.ZombiecraftMod;
import com.zombiecraft.audio.Bo2Locator;
import com.zombiecraft.bo2.UiAssets;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/** BO2 menu art from the local cache (built from the player's own install on first run). Everything returns false/null until it exists. */
public final class UiArt {
	private UiArt() {}

	private record Tex(ResourceLocation loc, int w, int h) {}

	private static final Map<String, Tex> TEX = new HashMap<>();
	/** When an image was last looked for and not found: the HUD asks for these every frame, so the disk is only checked again after a moment (the art may still be converting). */
	private static final Map<String, Long> MISSING = new HashMap<>();
	private static final long RECHECK_NS = 2_000_000_000L;
	private static volatile boolean working, failed;

	/** Starts the one-time conversion in the background if the cache is missing. */
	public static void ensure() {
		Path game = Minecraft.getInstance().gameDirectory.toPath();
		if (UiAssets.ready(game) || working || failed) return;
		working = true;
		Thread t = new Thread(() -> {
			try {
				Optional<Path> bo2 = Bo2Locator.find(game);
				if (bo2.isEmpty()) { failed = true; return; }
				UiAssets.prepare(game, bo2.get(), com.zombiecraft.bo2.Bo2Config.dumps(game), m -> ZombiecraftMod.LOG.info("Block Ops 2 menu art: {}", m));
			} catch (IOException | RuntimeException e) {
				ZombiecraftMod.LOG.warn("Block Ops 2 menu art unavailable: {}", e.toString());
				failed = true;
			} finally { working = false; }
		}, "zombiecraft-menu-art");
		t.setDaemon(true);
		t.start();
	}

	static boolean busy() { return working; }

	private static Tex tex(String name) {
		Tex t = TEX.get(name);
		if (t != null) return t;
		Path f = file(name);
		if (f == null) return null;
		try (var in = Files.newInputStream(f)) {
			NativeImage img = NativeImage.read(in);
			ResourceLocation loc = ResourceLocation.fromNamespaceAndPath("zombiecraft", "bo2ui/" + name);
			Minecraft.getInstance().getTextureManager().register(loc, new DynamicTexture(img));
			t = new Tex(loc, img.getWidth(), img.getHeight());
		} catch (IOException | RuntimeException e) {
			ZombiecraftMod.LOG.warn("Block Ops 2 menu art {} unreadable: {}", name, e.toString());
			t = new Tex(null, 0, 0);
		}
		TEX.put(name, t);
		return t;
	}

	/** The cached file of an image, or null (and no disk check for a moment) when it is not there. */
	private static Path file(String name) {
		Long checked = MISSING.get(name);
		long now = System.nanoTime();
		if (checked != null && now - checked < RECHECK_NS) return null;
		Path f = UiAssets.file(Minecraft.getInstance().gameDirectory.toPath(), name);
		if (Files.isRegularFile(f)) { MISSING.remove(name); return f; }
		MISSING.put(name, now);
		return null;
	}

	/** The registered texture of a menu image (loads it on first use), or null while the art is not there. */
	public static ResourceLocation location(String name) { Tex t = tex(name); return t == null ? null : t.loc; }

	private static final Map<String, ResourceLocation> CHALK = new HashMap<>();

	/** The same image as pure white chalk (alpha kept and boosted), for drawings that glow on a wall; null while the art is not there. */
	public static ResourceLocation chalk(String name) {
		ResourceLocation loc = CHALK.get(name);
		if (loc != null) return loc;
		Path f = file(name);
		if (f == null) return null;
		try (var in = Files.newInputStream(f)) {
			NativeImage img = NativeImage.read(in);
			for (int y = 0; y < img.getHeight(); y++) for (int x = 0; x < img.getWidth(); x++) {
				int a = img.getPixel(x, y) >>> 24;
				img.setPixel(x, y, (Math.min(255, a * 2) << 24) | 0xFFFFFF);
			}
			loc = ResourceLocation.fromNamespaceAndPath("zombiecraft", "bo2ui/chalk_" + name);
			Minecraft.getInstance().getTextureManager().register(loc, new DynamicTexture(img));
		} catch (IOException | RuntimeException e) {
			ZombiecraftMod.LOG.warn("Block Ops 2 chalk art {} unreadable: {}", name, e.toString());
			MISSING.put(name, Long.MAX_VALUE / 2); // do not retry an unreadable file every frame
		}
		if (loc != null) CHALK.put(name, loc);
		return loc;
	}

	/** BO2 icon image of a weapon id (pack-a-punched guns use the base gun's). */
	public static String weaponIcon(String weaponId) {
		String id = weaponId.replace("_pap", "");
		return switch (id) {
			case "m1911" -> "menu_mp_weapons_1911_big";
			case "rottweil72" -> "menu_mp_weapons_olympia_big";
			case "mp5k" -> "menu_mp_weapons_mp5_big";
			case "fnfal" -> "menu_mp_weapons_fal_big";
			case "ray_gun" -> "menu_zm_weapons_raygun_big";
			default -> "menu_mp_weapons_" + id + "_big";
		};
	}

	public static boolean has(String name) { Tex t = tex(name); return t != null && t.loc != null; }

	/** Whole image stretched into the box. */
	public static boolean draw(GuiGraphics g, String name, int x, int y, int w, int h) { return draw(g, name, x, y, w, h, -1); }

	/** Same, tinted by an ARGB colour (alpha fades it). */
	public static boolean draw(GuiGraphics g, String name, int x, int y, int w, int h, int argb) {
		Tex t = tex(name);
		if (t == null || t.loc == null) return false;
		g.blit(RenderType::guiTextured, t.loc, x, y, 0f, 0f, w, h, t.w, t.h, t.w, t.h, argb);
		return true;
	}

	public static int w(String name) { Tex t = tex(name); return t == null ? 0 : t.w; }
	public static int h(String name) { Tex t = tex(name); return t == null ? 0 : t.h; }

	/** A (rw x rh) piece of the image starting at (u, v), stretched into the box; u wraps around the image width. */
	static void strip(GuiGraphics g, String name, int x, int y, int w, int h, int u, int v, int rw, int rh, int argb) {
		Tex t = tex(name);
		if (t == null || t.loc == null || w <= 0) return;
		u = Math.floorMod(u, t.w);
		int first = Math.min(rw, t.w - u);
		int w1 = Math.max(1, Math.round((float) w * first / rw));
		g.blit(RenderType::guiTextured, t.loc, x, y, u, v, w1, h, first, rh, t.w, t.h, argb);
		if (first < rw) g.blit(RenderType::guiTextured, t.loc, x + w1, y, 0f, v, w - w1, h, rw - first, rh, t.w, t.h, argb);
	}

	/** Image scaled to fill the screen, centre-cropped. */
	static boolean cover(GuiGraphics g, String name, int w, int h) {
		Tex t = tex(name);
		if (t == null || t.loc == null) return false;
		float s = Math.max((float) w / t.w, (float) h / t.h);
		int rw = Math.min(t.w, Math.round(w / s)), rh = Math.min(t.h, Math.round(h / s));
		g.blit(RenderType::guiTextured, t.loc, 0, 0, (t.w - rw) / 2f, (t.h - rh) / 2f, w, h, rw, rh, t.w, t.h);
		return true;
	}
}

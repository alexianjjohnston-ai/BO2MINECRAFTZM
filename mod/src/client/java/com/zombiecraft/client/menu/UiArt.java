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
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** BO2 menu art from the local cache (built from the player's own install on first run). Everything returns false/null until it exists. */
public final class UiArt {
	private UiArt() {}

	private record Tex(ResourceLocation loc, int w, int h) {}

	private static final Map<String, Tex> TEX = new HashMap<>();
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
				String dump = System.getProperty("zombiecraft.bo2dump", "");
				List<Path> dumps = dump.isBlank() ? List.of() : java.util.Arrays.stream(dump.split(";")).map(Path::of).toList();
				UiAssets.prepare(game, bo2.get(), dumps, m -> ZombiecraftMod.LOG.info("Zombiecraft menu art: {}", m));
			} catch (IOException | RuntimeException e) {
				ZombiecraftMod.LOG.warn("Zombiecraft menu art unavailable: {}", e.toString());
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
		Path f = UiAssets.file(Minecraft.getInstance().gameDirectory.toPath(), name);
		if (!Files.isRegularFile(f)) return null;
		try (var in = Files.newInputStream(f)) {
			NativeImage img = NativeImage.read(in);
			ResourceLocation loc = ResourceLocation.fromNamespaceAndPath("zombiecraft", "bo2ui/" + name);
			Minecraft.getInstance().getTextureManager().register(loc, new DynamicTexture(img));
			t = new Tex(loc, img.getWidth(), img.getHeight());
		} catch (IOException | RuntimeException e) {
			ZombiecraftMod.LOG.warn("Zombiecraft menu art {} unreadable: {}", name, e.toString());
			t = new Tex(null, 0, 0);
		}
		TEX.put(name, t);
		return t;
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

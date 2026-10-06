package com.zombiecraft.client.menu;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;

import java.awt.GraphicsEnvironment;
import java.awt.RenderingHints;
import java.awt.font.TextAttribute;
import java.awt.image.BufferedImage;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Condensed bold text like BO2's UI font, rendered with a font that is already on the player's PC (Bahnschrift on Windows 10+,
 * else Arial squeezed). Strings become small cached textures, so nothing is bundled. Falls back to Minecraft's font on any error.
 */
public final class UiFont {
	private UiFont() {}

	private record Tex(ResourceLocation loc, int w, int h) {}

	private static final Map<String, Tex> CACHE = new LinkedHashMap<>(256, 0.75f, true) {
		@Override protected boolean removeEldestEntry(Map.Entry<String, Tex> e) {
			if (size() <= 300) return false;
			Minecraft.getInstance().getTextureManager().release(e.getValue().loc);
			return true;
		}
	};
	private static java.awt.Font base;
	private static boolean broken;
	private static int counter;

	private static java.awt.Font base() {
		if (base == null) {
			Set<String> names = Set.of(GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames());
			String pick = "Arial";
			for (String n : new String[] {"Bahnschrift", "Agency FB", "Arial Narrow"}) if (names.contains(n)) { pick = n; break; }
			Map<TextAttribute, Object> a = new HashMap<>();
			a.put(TextAttribute.FAMILY, pick);
			a.put(TextAttribute.WEIGHT, TextAttribute.WEIGHT_BOLD);
			a.put(TextAttribute.WIDTH, pick.equals("Agency FB") || pick.equals("Arial Narrow") ? 1.0f : 0.82f);
			base = new java.awt.Font(a);
		}
		return base;
	}

	private static Tex tex(String s, float height, double scale) {
		if (broken) return null;
		String key = s + "|" + Math.round(height * 4) + "|" + scale;
		Tex t = CACHE.get(key);
		if (t != null) return t;
		try {
			java.awt.Font f = base().deriveFont((float) (height * scale * 0.95));
			BufferedImage probe = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
			var pg = probe.createGraphics();
			var fm = pg.getFontMetrics(f);
			int w = Math.max(1, fm.stringWidth(s) + 4), h = fm.getAscent() + fm.getDescent() + 2, asc = fm.getAscent();
			pg.dispose();
			BufferedImage bi = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
			var g2 = bi.createGraphics();
			g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
			g2.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
			g2.setFont(f);
			g2.setColor(java.awt.Color.WHITE);
			g2.drawString(s, 2, asc);
			g2.dispose();
			NativeImage img = new NativeImage(w, h, false);
			for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) img.setPixel(x, y, (bi.getRGB(x, y) & 0xFF000000) | 0xFFFFFF);
			ResourceLocation loc = ResourceLocation.fromNamespaceAndPath("zombiecraft", "uifont/" + (counter++));
			Minecraft.getInstance().getTextureManager().register(loc, new DynamicTexture(img));
			t = new Tex(loc, w, h);
			CACHE.put(key, t);
			return t;
		} catch (RuntimeException | Error e) {
			broken = true;
			return null;
		}
	}

	private static double gui() { return Minecraft.getInstance().getWindow().getGuiScale(); }

	/** Width in GUI pixels of {@code s} drawn at {@code height}. */
	public static int width(String s, float height) {
		double gs = gui();
		Tex t = tex(s, height, gs);
		return t == null ? (int) (Minecraft.getInstance().font.width(s) * height / 9f) : (int) Math.round((t.w - 4) / gs);
	}

	/** Draws {@code s} with its top-left at (x, y). {@code height} is the font size in GUI pixels (9 is Minecraft's own size). */
	public static void draw(GuiGraphics g, String s, int x, int y, float height, int argb, boolean shadow) {
		if (s.isEmpty()) return;
		double gs = gui();
		Tex t = tex(s, height, gs);
		if (t == null) { g.drawString(Minecraft.getInstance().font, s, x, y, argb, shadow); return; }
		int dw = (int) Math.round(t.w / gs), dh = (int) Math.round(t.h / gs), px = x - (int) Math.round(2 / gs);
		if (shadow) g.blit(RenderType::guiTextured, t.loc, px + 1, y + 1, 0f, 0f, dw, dh, t.w, t.h, t.w, t.h, ((argb >>> 24) * 6 / 10) << 24);
		g.blit(RenderType::guiTextured, t.loc, px, y, 0f, 0f, dw, dh, t.w, t.h, t.w, t.h, argb);
	}
}

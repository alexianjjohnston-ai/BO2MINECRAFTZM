package com.zombiecraft.client.render;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;

import java.io.IOException;
import java.util.Random;

/**
 * Four decayed zombie skins made at runtime by regrading the vanilla zombie texture: sickly skin, different faded clothes, grime,
 * rips and dried blood. Nothing is stored in the mod and no outside art is used.
 */
public final class ZombieSkins {
	private ZombieSkins() {}

	private static final ResourceLocation[] SKINS = new ResourceLocation[4];
	private static final ResourceLocation BASE = ResourceLocation.withDefaultNamespace("textures/entity/zombie/zombie.png");
	/** Per-variant colour multipliers (r, g, b out of 255), applied to the base texture's brightness. */
	private static final int[][] TINT = {{150, 160, 135}, {170, 150, 120}, {135, 150, 165}, {160, 155, 150}};

	public static ResourceLocation get(int variant) {
		int i = Math.floorMod(variant, SKINS.length);
		if (SKINS[i] == null) SKINS[i] = make(i);
		return SKINS[i];
	}

	private static ResourceLocation make(int v) {
		NativeImage img;
		try (var in = Minecraft.getInstance().getResourceManager().getResourceOrThrow(BASE).open()) {
			img = NativeImage.read(in);
		} catch (IOException | RuntimeException e) {
			return BASE; // plain vanilla look if the base cannot be read
		}
		Random r = new Random(1000 + v * 77L);
		int w = img.getWidth(), h = img.getHeight();
		for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
			int c = img.getPixel(x, y); // ABGR
			int a = c >>> 24;
			if (a == 0) continue;
			int red = c & 255, grn = (c >> 8) & 255, blu = (c >> 16) & 255;
			float lum = (red * 0.3f + grn * 0.59f + blu * 0.11f) / 255f;
			float grime = 0.82f + r.nextFloat() * 0.3f;
			int nr = (int) (TINT[v][0] * lum * 1.7f * grime), ng = (int) (TINT[v][1] * lum * 1.7f * grime), nb = (int) (TINT[v][2] * lum * 1.7f * grime);
			img.setPixel(x, y, (a << 24) | (cl(nb) << 16) | (cl(ng) << 8) | cl(nr));
		}
		// dried blood and rips: dark red blotches, darker tears
		int blots = w * h / 90;
		for (int n = 0; n < blots; n++) {
			int cx = r.nextInt(w), cy = r.nextInt(h), rad = 1 + r.nextInt(3);
			boolean rip = r.nextInt(4) == 0;
			for (int dy = -rad; dy <= rad; dy++) for (int dx = -rad; dx <= rad; dx++) {
				int px = cx + dx, py = cy + dy;
				if (px < 0 || py < 0 || px >= w || py >= h || dx * dx + dy * dy > rad * rad + 1) continue;
				int c = img.getPixel(px, py), a = c >>> 24;
				if (a == 0) continue;
				int red = c & 255, grn = (c >> 8) & 255, blu = (c >> 16) & 255;
				if (rip) { red = red * 2 / 5; grn = grn * 2 / 5; blu = blu * 2 / 5; }
				else { red = Math.min(255, red / 2 + 70); grn = grn / 4; blu = blu / 4; }
				img.setPixel(px, py, (a << 24) | (cl(blu) << 16) | (cl(grn) << 8) | cl(red));
			}
		}
		ResourceLocation loc = ResourceLocation.fromNamespaceAndPath("zombiecraft", "zombie_skin_" + v);
		Minecraft.getInstance().getTextureManager().register(loc, new DynamicTexture(img));
		return loc;
	}

	private static int cl(int x) { return Math.max(0, Math.min(255, x)); }
}

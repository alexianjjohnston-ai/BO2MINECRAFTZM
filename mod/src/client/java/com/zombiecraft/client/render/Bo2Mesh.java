package com.zombiecraft.client.render;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.zombiecraft.ZombiecraftMod;
import com.zombiecraft.bo2.Bo2Assets;
import com.zombiecraft.bo2.Pose;
import com.zombiecraft.bo2.XModel;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Draws models from the local cache (made from the player's own install). BO2 space is inches with x forward, y left, z up;
 * {@link #draw} maps it into Minecraft's x right/west, y up, z south(forward) at the given scale.
 */
public final class Bo2Mesh {
	private Bo2Mesh() {}

	public static final float INCH = 0.0254f;

	/** A loaded model with its per-surface textures (null where the texture is missing). */
	public static final class Loaded {
		public final XModel model;
		final ResourceLocation[] tex;
		/** Bounding box centre in BO2 inches. */
		public final float cx, cy, cz;
		public final float minX, maxX, minY, maxY, minZ, maxZ;
		Loaded(XModel m, ResourceLocation[] tex) {
			this.model = m; this.tex = tex;
			float a = 1e9f, b = -1e9f, c = 1e9f, d = -1e9f, e = 1e9f, f = -1e9f;
			for (int i = 0; i < m.vertCount; i++) {
				float x = m.pos[i * 3], y = m.pos[i * 3 + 1], z = m.pos[i * 3 + 2];
				a = Math.min(a, x); b = Math.max(b, x); c = Math.min(c, y); d = Math.max(d, y); e = Math.min(e, z); f = Math.max(f, z);
			}
			minX = a; maxX = b; minY = c; maxY = d; minZ = e; maxZ = f;
			cx = (a + b) / 2; cy = (c + d) / 2; cz = (e + f) / 2;
		}
	}

	private static final Map<String, Loaded> MODELS = new HashMap<>();
	private static final Map<String, ResourceLocation> TEXTURES = new HashMap<>();
	private static int counter;

	private static Path gameDir() { return Minecraft.getInstance().gameDirectory.toPath(); }

	/** The model, or null while the cache is not ready or the model is not in it. */
	public static Loaded get(String name) {
		if (!ModelCache.ready()) return null;
		Loaded l = MODELS.get(name);
		if (l != null || MODELS.containsKey(name)) return l;
		try {
			XModel m = XModel.readCompact(Bo2Assets.modelFile(gameDir(), name));
			ResourceLocation[] tex = new ResourceLocation[m.surfaces.size()];
			for (int i = 0; i < tex.length; i++) {
				XModel.Material mat = m.materials.get(m.surfaces.get(i).material);
				tex[i] = texture(mat.texture());
			}
			l = new Loaded(m, tex);
		} catch (IOException | RuntimeException e) {
			ZombiecraftMod.LOG.warn("Block Ops 2 model {} not available: {}", name, e.toString());
		}
		MODELS.put(name, l);
		return l;
	}

	private static final Map<String, com.zombiecraft.bo2.XAnim> ANIMS = new HashMap<>();
	private static final Map<String, ResourceLocation> GLOWS = new HashMap<>();

	/** An animation from the local cache, or null. */
	public static com.zombiecraft.bo2.XAnim anim(String name) {
		if (!ModelCache.ready()) return null;
		if (ANIMS.containsKey(name)) return ANIMS.get(name);
		com.zombiecraft.bo2.XAnim a = null;
		try { a = com.zombiecraft.bo2.XAnim.read(Bo2Assets.cacheDir(gameDir()).resolve("anims").resolve(name)); }
		catch (IOException | RuntimeException e) { ZombiecraftMod.LOG.warn("Block Ops 2 animation {} not available: {}", name, e.toString()); }
		ANIMS.put(name, a);
		return a;
	}

	/** A copy of a texture that keeps only its bright orange/yellow glow (the box's question marks) on black, for a full-bright pass. */
	private static ResourceLocation glow(String dds) {
		if (GLOWS.containsKey(dds)) return GLOWS.get(dds);
		ResourceLocation loc = null;
		Path f = Bo2Assets.textureFile(gameDir(), dds);
		if (Files.isRegularFile(f)) {
			try (var in = Files.newInputStream(f)) {
				NativeImage src = NativeImage.read(in);
				NativeImage out = new NativeImage(src.getWidth(), src.getHeight(), false);
				for (int y = 0; y < src.getHeight(); y++) for (int x = 0; x < src.getWidth(); x++) {
					int c = src.getPixel(x, y); // ABGR
					int r = c & 255, g = (c >> 8) & 255, b = (c >> 16) & 255;
					boolean glowing = r > 190 && g > 120 && b < 140 && r - b > 90;
					out.setPixel(x, y, glowing ? (0xFF000000 | (b << 16) | (g << 8) | r) : 0xFF000000);
				}
				src.close();
				loc = ResourceLocation.fromNamespaceAndPath("zombiecraft", "bo2glow/" + (counter++));
				Minecraft.getInstance().getTextureManager().register(loc, new DynamicTexture(out));
			} catch (IOException | RuntimeException e) { loc = null; }
		}
		GLOWS.put(dds, loc);
		return loc;
	}

	/** Same as {@link #draw} plus a full-bright pass of the model's glowing texels. Used for the Mystery Box. */
	public static void drawGlowing(Loaded l, Pose pose, PoseStack ps, MultiBufferSource buf, int light, float scale, int axes) {
		draw(l, pose, ps, buf, light, scale, axes);
		XModel m = l.model;
		float[] sx = null, sy = null, sz = null;
		if (pose != null) {
			sx = new float[m.vertCount]; sy = new float[m.vertCount]; sz = new float[m.vertCount];
			float[] t = new float[3];
			for (int v = 0; v < m.vertCount; v++) { pose.skinPos(v, t); sx[v] = t[0]; sy[v] = t[1]; sz[v] = t[2]; }
		}
		PoseStack.Pose p = ps.last();
		for (int s = 0; s < m.surfaces.size(); s++) {
			XModel.Material mat = m.materials.get(m.surfaces.get(s).material);
			if (mat.texture() == null || !mat.texture().contains("magic_box_c")) continue;
			ResourceLocation g = glow(mat.texture());
			if (g == null) continue;
			XModel.Surface surf = m.surfaces.get(s);
			VertexConsumer vc = buf.getBuffer(RenderType.eyes(g));
			for (int c = 0; c + 2 < surf.cornerCount(); c += 3) {
				for (int k = 0; k < 4; k++) {
					int ci = c + Math.min(k, 2), v = surf.vert[ci];
					float x, y, z;
					if (pose != null) { x = sx[v]; y = sy[v]; z = sz[v]; } else { x = m.pos[v * 3]; y = m.pos[v * 3 + 1]; z = m.pos[v * 3 + 2]; }
					float px, py, pz;
					if (axes == 3) { px = -x; py = z; pz = y; } else { px = y; py = z; pz = x; }
					vc.addVertex(p, px * scale, py * scale, pz * scale).setColor(255, 255, 255, 255).setUv(surf.uv[ci * 2], surf.uv[ci * 2 + 1])
							.setOverlay(OverlayTexture.NO_OVERLAY).setLight(0xF000F0).setNormal(p, 0, 1, 0);
				}
			}
		}
	}

	private static ResourceLocation texture(String dds) {
		if (dds == null || dds.isEmpty()) return null;
		if (TEXTURES.containsKey(dds)) return TEXTURES.get(dds);
		ResourceLocation loc = null;
		Path f = Bo2Assets.textureFile(gameDir(), dds);
		if (Files.isRegularFile(f)) {
			try (var in = Files.newInputStream(f)) {
				loc = ResourceLocation.fromNamespaceAndPath("zombiecraft", "bo2tex/" + (counter++));
				Minecraft.getInstance().getTextureManager().register(loc, new DynamicTexture(NativeImage.read(in)));
			} catch (IOException | RuntimeException e) { loc = null; }
		}
		TEXTURES.put(dds, loc);
		return loc;
	}

	/**
	 * Draws the model at the current pose. {@code pose} (optional) skins it with an animation. The model's BO2 axes are mapped to
	 * (x, y, z) = (left, up, forward) times {@code scale}, then {@code axes} says how: 0 = entity style (forward is +z), 1 = side view
	 * for displays (forward is +x, left side away from the viewer).
	 */
	public static void draw(Loaded l, Pose pose, PoseStack ps, MultiBufferSource buf, int light, float scale, int axes) {
		XModel m = l.model;
		float[] sx = null, sy = null, sz = null;
		if (pose != null) {
			sx = new float[m.vertCount]; sy = new float[m.vertCount]; sz = new float[m.vertCount];
			float[] t = new float[3];
			for (int v = 0; v < m.vertCount; v++) { pose.skinPos(v, t); sx[v] = t[0]; sy[v] = t[1]; sz[v] = t[2]; }
		}
		PoseStack.Pose p = ps.last();
		float[] n = new float[3];
		for (int s = 0; s < m.surfaces.size(); s++) {
			ResourceLocation tex = l.tex[s];
			if (tex == null) continue;
			XModel.Surface surf = m.surfaces.get(s);
			VertexConsumer vc = buf.getBuffer(RenderType.entityCutoutNoCull(tex));
			for (int c = 0; c + 2 < surf.cornerCount(); c += 3) {
				for (int k = 0; k < 4; k++) {
					int ci = c + Math.min(k, 2), v = surf.vert[ci];
					float x, y, z, nx = surf.normal[ci * 3], ny = surf.normal[ci * 3 + 1], nz = surf.normal[ci * 3 + 2];
					if (pose != null) { x = sx[v]; y = sy[v]; z = sz[v]; pose.skinNormal(v, nx, ny, nz, n); nx = n[0]; ny = n[1]; nz = n[2]; }
					else { x = m.pos[v * 3]; y = m.pos[v * 3 + 1]; z = m.pos[v * 3 + 2]; }
					float px, py, pz, qx, qy, qz;
					if (axes == 1) { px = x; py = z; pz = -y; qx = nx; qy = nz; qz = -ny; }
					else if (axes == 2) { px = -y; py = z; pz = -x; qx = -ny; qy = nz; qz = -nx; }
					else if (axes == 3) { px = -x; py = z; pz = y; qx = -nx; qy = nz; qz = ny; }
					else { px = y; py = z; pz = x; qx = ny; qy = nz; qz = nx; }
					vc.addVertex(p, px * scale, py * scale, pz * scale).setColor(255, 255, 255, 255)
							.setUv(surf.uv[ci * 2], surf.uv[ci * 2 + 1]).setOverlay(OverlayTexture.NO_OVERLAY).setLight(light).setNormal(p, qx, qy, qz);
				}
			}
		}
	}
}

package com.zombiecraft.client.render;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;

/** Simple original first-person hands and sleeves (blocky, drawn here with no outside art) that grip the gun. */
public final class Hands {
	private Hands() {}

	private static ResourceLocation tex;

	/** 16x16: left half skin, right half sleeve cloth, with a little noise so it is not flat. */
	private static ResourceLocation tex() {
		if (tex == null) {
			NativeImage img = new NativeImage(16, 16, false);
			java.util.Random r = new java.util.Random(5);
			for (int y = 0; y < 16; y++) for (int x = 0; x < 16; x++) {
				int n = r.nextInt(14) - 7;
				int rr, gg, bb;
				if (x < 8) { rr = 205 + n; gg = 152 + n; bb = 118 + n; } else { rr = 66 + n; gg = 74 + n; bb = 58 + n; }
				if (y == 0 || y == 15) { rr = rr * 3 / 4; gg = gg * 3 / 4; bb = bb * 3 / 4; }
				img.setPixel(x, y, 0xFF000000 | (clamp(bb) << 16) | (clamp(gg) << 8) | clamp(rr)); // ABGR
			}
			tex = ResourceLocation.fromNamespaceAndPath("zombiecraft", "hands");
			Minecraft.getInstance().getTextureManager().register(tex, new DynamicTexture(img));
		}
		return tex;
	}

	private static int clamp(int v) { return Math.max(0, Math.min(255, v)); }

	/** Draws the hands for a gun of the given kind, in the gun's view space (blocks; the gun's origin is where the right hand holds it). */
	public static void draw(PoseStack ps, MultiBufferSource buf, int light, Bo2Mesh.Loaded gun, String kind, float scale) {
		VertexConsumer vc = buf.getBuffer(RenderType.entityCutoutNoCull(tex()));
		// right hand on the grip, forearm running back and down toward the screen corner
		ps.pushPose();
		ps.translate(0f, -0.045f, 0.02f);
		box(ps, vc, light, 0f, 0f, 0f, 0.060f, 0.075f, 0.11f, 0f);
		ps.translate(0.012f, -0.03f, 0.12f);
		ps.mulPose(Axis.XP.rotationDegrees(-22f));
		ps.mulPose(Axis.YP.rotationDegrees(-6f));
		box(ps, vc, light, 0f, 0f, 0.13f, 0.068f, 0.068f, 0.30f, 1f);
		ps.popPose();
		// the support hand cups the front of long guns
		if (!kind.equals("pistol") && !kind.equals("revolver")) {
			float len = (gun.maxX - gun.minX) * scale;
			ps.pushPose();
			ps.translate(-gun.cy * scale - 0.01f, gun.minZ * scale * 0.7f - 0.045f, -(gun.minX * scale + len * 0.55f));
			box(ps, vc, light, 0f, 0f, 0f, 0.070f, 0.060f, 0.10f, 0f);
			ps.translate(-0.01f, -0.035f, 0.1f);
			ps.mulPose(Axis.XP.rotationDegrees(-30f));
			ps.mulPose(Axis.YP.rotationDegrees(22f));
			box(ps, vc, light, 0f, 0f, 0.13f, 0.066f, 0.066f, 0.30f, 1f);
			ps.popPose();
		}
	}

	/** An axis-aligned box centred at (cx, cy, cz); {@code part} 0 is skin and 1 is sleeve (picks the half of the texture). */
	private static void box(PoseStack ps, VertexConsumer vc, int light, float cx, float cy, float cz, float sx, float sy, float sz, float part) {
		PoseStack.Pose p = ps.last();
		float x0 = cx - sx / 2, x1 = cx + sx / 2, y0 = cy - sy / 2, y1 = cy + sy / 2, z0 = cz - sz / 2, z1 = cz + sz / 2;
		float u0 = part * 0.5f + 0.02f, u1 = part * 0.5f + 0.48f;
		quad(p, vc, light, u0, u1, x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1, 0, 0, 1);   // back (toward the camera)
		quad(p, vc, light, u0, u1, x1, y0, z0, x0, y0, z0, x0, y1, z0, x1, y1, z0, 0, 0, -1);  // front
		quad(p, vc, light, u0, u1, x0, y0, z0, x0, y0, z1, x0, y1, z1, x0, y1, z0, -1, 0, 0);  // left
		quad(p, vc, light, u0, u1, x1, y0, z1, x1, y0, z0, x1, y1, z0, x1, y1, z1, 1, 0, 0);   // right
		quad(p, vc, light, u0, u1, x0, y1, z1, x1, y1, z1, x1, y1, z0, x0, y1, z0, 0, 1, 0);   // top
		quad(p, vc, light, u0, u1, x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1, 0, -1, 0);  // bottom
	}

	private static void quad(PoseStack.Pose p, VertexConsumer vc, int light, float u0, float u1,
			float ax, float ay, float az, float bx, float by, float bz, float cx, float cy, float cz, float dx, float dy, float dz,
			float nx, float ny, float nz) {
		vertex(p, vc, light, ax, ay, az, u0, 1f, nx, ny, nz);
		vertex(p, vc, light, bx, by, bz, u1, 1f, nx, ny, nz);
		vertex(p, vc, light, cx, cy, cz, u1, 0f, nx, ny, nz);
		vertex(p, vc, light, dx, dy, dz, u0, 0f, nx, ny, nz);
	}

	private static void vertex(PoseStack.Pose p, VertexConsumer vc, int light, float x, float y, float z, float u, float v, float nx, float ny, float nz) {
		vc.addVertex(p, x, y, z).setColor(255, 255, 255, 255).setUv(u, v).setOverlay(OverlayTexture.NO_OVERLAY).setLight(light).setNormal(p, nx, ny, nz);
	}
}

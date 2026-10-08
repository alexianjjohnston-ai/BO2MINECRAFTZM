package com.zombiecraft.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.zombiecraft.bo2.Pose;
import com.zombiecraft.bo2.XAnim;
import com.zombiecraft.entity.ZcBox;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.state.EntityRenderState;

/** Draws the Mystery Box from the local BO2 model, playing the lid animations the server asks for. */
public class ZcBoxRenderer extends EntityRenderer<ZcBox, ZcBoxRenderer.State> {
	public static final class State extends EntityRenderState {
		int anim;
		float time, scale, yaw;
	}

	private static final String[] ANIM_NAMES = {null, "o_zombie_magic_box_open", "o_zombie_magic_box_close", "o_zombie_magic_box_leave", "o_zombie_magic_box_arrive"};
	/** The box stands on its cinder-block feet (drawn by the server as displays); this lifts the mesh onto them. */
	public static final float FEET = 0.34f;

	private Pose pose;
	private Bo2Mesh.Loaded poseOf;

	public ZcBoxRenderer(EntityRendererProvider.Context ctx) { super(ctx); }

	private static net.minecraft.resources.ResourceLocation glowTex;

	/** A soft round falloff, white, with alpha 1 in the middle and 0 at the rim. */
	private static net.minecraft.resources.ResourceLocation glowTex() {
		if (glowTex == null) {
			com.mojang.blaze3d.platform.NativeImage img = new com.mojang.blaze3d.platform.NativeImage(128, 128, false);
			for (int y = 0; y < 128; y++) for (int x = 0; x < 128; x++) {
				double d = Math.hypot(x - 63.5, y - 63.5) / 64.0, a = Math.max(0, 1 - d);
				img.setPixel(x, y, ((int) (a * a * 255) << 24) | 0xFFFFFF);
			}
			glowTex = net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("zombiecraft", "boxglow");
			var dt = new net.minecraft.client.renderer.texture.DynamicTexture(img);
			dt.setFilter(true, false); // smooth, so the glow has no stepped edges
			net.minecraft.client.Minecraft.getInstance().getTextureManager().register(glowTex, dt);
		}
		return glowTex;
	}

	/** A flat glow sprite lying at height y (blocks), centred on the box. */
	private static void glowQuad(PoseStack ps, MultiBufferSource buf, float hx, float hz, float y, int r, int g, int b, int a) {
		var vc = buf.getBuffer(net.minecraft.client.renderer.RenderType.entityTranslucentEmissive(glowTex()));
		PoseStack.Pose p = ps.last();
		float[][] c = {{-hx, -hz, 0, 0}, {hx, -hz, 1, 0}, {hx, hz, 1, 1}, {-hx, hz, 0, 1}};
		for (float[] v : c)
			vc.addVertex(p, v[0], y, v[1]).setColor(r, g, b, a).setUv(v[2], v[3])
					.setOverlay(net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY).setLight(0xF000F0).setNormal(p, 0, 1, 0);
	}

	@Override public State createRenderState() { return new State(); }

	@Override public void extractRenderState(ZcBox box, State s, float partialTick) {
		super.extractRenderState(box, s, partialTick);
		s.anim = box.getEntityData().get(ZcBox.ANIM);
		s.time = (box.level().getGameTime() - box.getEntityData().get(ZcBox.START) + partialTick) / 20f;
		s.scale = box.getEntityData().get(ZcBox.SCALE);
		s.yaw = box.getYRot();
	}

	@Override public void render(State s, PoseStack ps, MultiBufferSource buf, int light) {
		Bo2Mesh.Loaded l = Bo2Mesh.get("p6_anim_zm_magic_box");
		if (l == null) return;
		if (pose == null || poseOf != l) { pose = new Pose(l.model); poseOf = l; }
		pose.reset();
		String name = s.anim >= 0 && s.anim < ANIM_NAMES.length ? ANIM_NAMES[s.anim] : null;
		XAnim a = name == null ? null : Bo2Mesh.anim(name);
		if (a != null) {
			float frame = Math.max(0f, Math.min(a.numFrames, s.time * a.frameRate));
			pose.apply(a, frame, 1f);
		}
		pose.build();
		ps.pushPose();
		ps.mulPose(Axis.YP.rotationDegrees(-s.yaw));
		// golden light spilling on the floor around the box
		float pu = Bo2Mesh.pulse();
		glowQuad(ps, buf, 2.6f, 1.7f, 0.03f, 255, 190, 40, (int) (40 + 40 * pu));
		ps.pushPose();
		ps.translate(0f, FEET, 0f);
		Bo2Mesh.draw(l, pose, ps, buf, light, s.scale, 3);
		// and a soft halo hanging over the lid, around the question marks
		glowQuad(ps, buf, 1.5f, 0.75f, 19.7f * s.scale, 255, 200, 60, (int) (50 + 70 * pu));
		ps.popPose();
		ps.popPose();
	}
}

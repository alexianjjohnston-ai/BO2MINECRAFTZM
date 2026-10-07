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
		ps.translate(0f, FEET, 0f);
		Bo2Mesh.drawGlowing(l, pose, ps, buf, light, s.scale, 3);
		ps.popPose();
	}
}

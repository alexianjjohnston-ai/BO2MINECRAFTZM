package com.zombiecraft.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.zombiecraft.bo2.Pose;
import com.zombiecraft.entity.ZcProp;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.state.EntityRenderState;

/** Draws the perk machines, Pack-a-Punch and the power switch from the local BO2 models: dark when unpowered, lit when powered. */
public class ZcPropRenderer extends EntityRenderer<ZcProp, ZcPropRenderer.State> {
	public static final class State extends EntityRenderState {
		int kind;
		boolean powered, busy;
		float yaw, sinceSec;
	}

	private static final String[] OFF = {"zombie_vending_jugg", "zombie_vending_sleight", "zombie_vending_doubletap2", "zombie_vending_revive", "p6_anim_zm_buildable_pap", "p6_zm_buildable_pswitch_body"};
	private static final String[] ON = {"zombie_vending_jugg_on", "zombie_vending_sleight_on", "zombie_vending_doubletap2_on", "zombie_vending_revive_on", "p6_anim_zm_buildable_pap_on", "p6_zm_buildable_pswitch_body"};
	/** Blocks per BO2 inch: a machine is about 2.2 blocks tall. */
	public static final float SCALE = 0.0225f;

	private final java.util.Map<Bo2Mesh.Loaded, Pose> poses = new java.util.HashMap<>();

	public ZcPropRenderer(EntityRendererProvider.Context ctx) { super(ctx); }

	@Override public State createRenderState() { return new State(); }

	@Override public void extractRenderState(ZcProp e, State s, float partialTick) {
		super.extractRenderState(e, s, partialTick);
		s.kind = e.getEntityData().get(ZcProp.KIND);
		s.powered = e.getEntityData().get(ZcProp.POWERED);
		s.busy = e.getEntityData().get(ZcProp.BUSY);
		s.yaw = e.getYRot();
		s.sinceSec = (e.level().getGameTime() - e.getEntityData().get(ZcProp.SINCE) + partialTick) / 20f;
	}

	@Override public void render(State s, PoseStack ps, MultiBufferSource buf, int light) {
		int k = Math.max(0, Math.min(OFF.length - 1, s.kind));
		Bo2Mesh.Loaded l = Bo2Mesh.get(s.powered ? ON[k] : OFF[k]);
		if (l == null) return;
		ps.pushPose();
		ps.mulPose(Axis.YP.rotationDegrees(-s.yaw));
		float t = (System.nanoTime() % 100_000_000_000L) / 1e9f;
		if (s.kind == ZcProp.PAP && s.busy) {
			// working: the machine shudders
			ps.translate(Math.sin(t * 47) * 0.006, Math.sin(t * 61) * 0.004, Math.sin(t * 53) * 0.006);
		}
		// the models' fronts are on -y: axes mode 1 maps (x, y, z) to (x, z, -y), so the front faces the entity's facing. The entity stands on
		// the back face of its spot: slide the model so its back is flush with it and it is centred across.
		ps.translate(-l.cx * SCALE, 0f, l.maxY * SCALE + 0.02f);
		if (s.kind == ZcProp.SWITCH) {
			Bo2Mesh.draw(l, null, ps, buf, light, SCALE, 1);
			Bo2Mesh.Loaded lever = Bo2Mesh.get("p6_zm_buildable_pswitch_lever");
			if (lever != null) {
				// the lever swings from up to down over a second when the power comes on
				float f = s.powered ? Math.min(1f, s.sinceSec) : 0f;
				ps.pushPose();
				ps.translate(0f, 34f * SCALE, -2f * SCALE);
				ps.mulPose(Axis.XP.rotationDegrees(-50f + 100f * f));
				Bo2Mesh.draw(lever, null, ps, buf, light, SCALE, 1);
				ps.popPose();
			}
		} else {
			Bo2Mesh.draw(l, null, ps, buf, light, SCALE, 1);
		}
		ps.popPose();
	}
}

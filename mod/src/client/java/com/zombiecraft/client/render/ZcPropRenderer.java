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
		String weapon = "";
		int papState;
		float papSec;
	}

	private static final String[] OFF = {"zombie_vending_jugg", "zombie_vending_sleight", "zombie_vending_doubletap2", "zombie_vending_revive", "p6_anim_zm_buildable_pap", "p6_zm_buildable_pswitch_body"};
	private static final String[] POWERUP = {"zombie_ammocan", "zombie_skull", "zombie_x2_icon", "zombie_bomb", "zombie_carpenter"};
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
		s.weapon = e.getEntityData().get(ZcProp.PAP_WEAPON);
		s.papState = e.getEntityData().get(ZcProp.PAP_STATE);
		s.papSec = (e.level().getGameTime() - e.getEntityData().get(ZcProp.PAP_SINCE) + partialTick) / 20f;
		s.sinceSec = (e.level().getGameTime() - e.getEntityData().get(ZcProp.SINCE) + partialTick) / 20f;
	}

	private static void gunLayer(Bo2Mesh.Loaded l, PoseStack ps, MultiBufferSource buf, int light, float sc, int halo) {
		ps.pushPose();
		ps.translate(-l.cx * sc, -l.cz * sc, l.cy * sc);
		Bo2Mesh.draw(l, null, ps, buf, light, sc, 1, halo);
		ps.popPose();
	}

	/** A weapon with BO2's pulsing blue glow: hung on the wall, or rising out of the Mystery Box and bobbing (the teddy bear has no glow). */
	private void renderGun(State s, PoseStack ps, MultiBufferSource buf, int light) {
		if (s.weapon.isEmpty()) return;
		boolean wall = s.kind == ZcProp.WALLGUN;
		float t = (System.nanoTime() % 100_000_000_000L) / 1e9f;
		float rise = wall ? 0f : Math.min(1f, s.sinceSec / 0.8f);
		ps.pushPose();
		ps.mulPose(Axis.YP.rotationDegrees(-s.yaw));
		if (!wall) ps.translate(0f, 0.05f + rise * 0.5f + (float) Math.sin(t * 2.0f) * 0.03f, 0f);
		if (s.weapon.equals("teddy")) {
			ZcItemModels.render("teddy", net.minecraft.world.item.ItemDisplayContext.NONE, ps, buf, 0xF000F0);
			ps.popPose();
			return;
		}
		String name = ZcItemModels.modelName(s.weapon, false);
		Bo2Mesh.Loaded l = name == null ? null : Bo2Mesh.get(name);
		if (l != null) {
			float len = Math.max(l.maxX - l.minX, Math.max(l.maxY - l.minY, l.maxZ - l.minZ));
			float base = (wall ? 1.35f : 1.1f) / Math.max(1f, len);
			float pulse = 0.75f + 0.25f * (float) Math.sin(t * 3f);
			if (!wall) ps.mulPose(Axis.YP.rotationDegrees((float) Math.sin(t * 0.9f) * 12f));
			gunLayer(l, ps, buf, light, base * 1.16f, ((int) (wall ? 0x38 * pulse : 0x22 * pulse) << 24) | 0x2E78FF);
			gunLayer(l, ps, buf, light, base * 1.07f, ((int) (wall ? 0x52 * pulse : 0x34 * pulse) << 24) | 0x74B8FF);
			gunLayer(l, ps, buf, 0xC000C0, base, 0);
		}
		ps.popPose();
	}

	/** A power-up: the BO2 model, lit from within, spinning and bobbing above the spot (hidden on the blink frames). */
	private void renderPowerup(State s, PoseStack ps, MultiBufferSource buf) {
		if (s.busy) return;
		Bo2Mesh.Loaded l = Bo2Mesh.get(POWERUP[s.kind - ZcProp.AMMO]);
		if (l == null) return;
		float t = (System.nanoTime() % 100_000_000_000L) / 1e9f;
		float size = Math.max(l.maxX - l.minX, Math.max(l.maxY - l.minY, l.maxZ - l.minZ));
		float sc = 0.7f / Math.max(1f, size);
		ps.pushPose();
		ps.translate(0f, 0.15f + Math.sin(t * 2.2f) * 0.08f, 0f);
		ps.mulPose(Axis.YP.rotationDegrees(t * 90f));
		ps.translate(-l.cx * sc, -l.cz * sc, l.cy * sc);
		Bo2Mesh.draw(l, null, ps, buf, net.minecraft.client.renderer.LightTexture.FULL_BRIGHT, sc, 1);
		ps.popPose();
	}

	/** The gun inside Pack-a-Punch: slid in, glowing while it works, pushed back out ready to take. Drawn in the machine's frame (entity origin, +z = out front). */
	private void renderPapGun(State s, PoseStack ps, MultiBufferSource buf, int light) {
		if (s.weapon.isEmpty() || s.papState == 0) return;
		String name = ZcItemModels.modelName(s.weapon.replace("_pap", ""), false);
		Bo2Mesh.Loaded l = name == null ? null : Bo2Mesh.get(name);
		if (l == null) return;
		float t = (System.nanoTime() % 100_000_000_000L) / 1e9f;
		float y, z;
		boolean working = s.papState == 1;
		if (working) {
			float k = com.zombiecraft.entity.PapVisual.ease(s.papSec * 20f / com.zombiecraft.entity.PapVisual.INTAKE_TICKS);
			z = 1.55f + (0.72f - 1.55f) * k; y = 1.2f + (0.95f - 1.2f) * k;
		} else {
			float k = com.zombiecraft.entity.PapVisual.ease(s.papSec * 20f / com.zombiecraft.entity.PapVisual.RETURN_TICKS);
			z = 0.72f + (1.2f - 0.72f) * k; y = 0.98f + 0.06f * k + (float) Math.sin(t * 2f) * 0.02f * k;
		}
		float len = Math.max(l.maxX - l.minX, Math.max(l.maxY - l.minY, l.maxZ - l.minZ));
		float base = 1.35f / Math.max(1f, len);
		float pulse = working ? 0.6f + 0.4f * (float) Math.sin(t * 14f) : 0.75f + 0.25f * (float) Math.sin(t * 3f);
		ps.pushPose();
		ps.translate(0f, y, z);
		gunLayer(l, ps, buf, light, base * 1.18f, ((int) (0x66 * pulse) << 24) | 0x2E78FF);
		gunLayer(l, ps, buf, light, base * 1.08f, ((int) (0x88 * pulse) << 24) | 0x74B8FF);
		gunLayer(l, ps, buf, 0xC000C0, base, 0);
		ps.popPose();
	}

	@Override public void render(State s, PoseStack ps, MultiBufferSource buf, int light) {
		if (s.kind >= ZcProp.WALLGUN) { renderGun(s, ps, buf, light); return; }
		if (s.kind >= ZcProp.AMMO) { renderPowerup(s, ps, buf); return; }
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
		if (s.kind == ZcProp.PAP) renderPapGun(s, ps, buf, light);
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

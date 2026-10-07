package com.zombiecraft.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.zombiecraft.bo2.Pose;
import com.zombiecraft.bo2.XAnim;
import com.zombiecraft.entity.ZcZombie;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.HumanoidMobRenderer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.monster.Zombie;

/**
 * Draws the Zombies-mode zombie. With the local BO2 models it is the real zombie body and head, skinned and played with the real
 * zombie animations (walk, run, sprint, attack, board tearing, climbing in, death). Without them it falls back to a regraded vanilla zombie.
 */
public class ZcZombieRenderer extends HumanoidMobRenderer<Zombie, ZcZombieState, ZcZombieModel> {
	private static final String[] BODIES = {"c_zom_zombie1_body01", "c_zom_zombie1_body02", "c_zom_zombie2_body01", "c_zom_zombie3_body01", "c_zom_zombie3_body02"};
	private static final String[] HEADS = {"c_zom_zombie_head_a", "c_zom_zombie_head_k", "c_zom_zombie_head_l", "c_zom_zombie_head_n"};
	private static final String[] WALK = {"ai_zombie_walk_v1", "ai_zombie_walk_v2", "ai_zombie_walk_v3", "ai_zombie_walk_v4"};
	private static final String[] RUN = {"ai_zombie_run_v2", "ai_zombie_run_v3"};
	private static final String[] SPRINT = {"ai_zombie_sprint_v1", "ai_zombie_sprint_v2"};
	private static final String[] ATTACK = {"ai_zombie_attack_v1", "ai_zombie_attack_v2"};
	private static final String[] DEATH = {"ch_dazed_a_death", "ch_dazed_b_death"};
	/** BO2 inches to blocks, with the zombie a touch under 2 blocks tall. */
	private static final float SCALE = Bo2Mesh.INCH * 1.02f;

	private static final java.util.Map<String, Float> LIFT = new java.util.HashMap<>();
	private final java.util.Map<Bo2Mesh.Loaded, Pose> poses = new java.util.HashMap<>();

	public ZcZombieRenderer(EntityRendererProvider.Context ctx) {
		super(ctx, new ZcZombieModel(ctx.bakeLayer(ModelLayers.ZOMBIE)), 0.5f);
	}

	@Override public ZcZombieState createRenderState() { return new ZcZombieState(); }

	@Override public void extractRenderState(Zombie z, ZcZombieState s, float partialTick) {
		super.extractRenderState(z, s, partialTick);
		byte anim = z instanceof ZcZombie ? z.getEntityData().get(ZcZombie.DATA_ANIM) : 3;
		s.stage = anim & 3;
		s.tier = (anim >> 2) & 3;
		s.variant = z.getId();
		s.isAggressive = true;
	}

	@Override public ResourceLocation getTextureLocation(ZcZombieState s) { return ZombieSkins.get(s.variant); }

	private Pose pose(Bo2Mesh.Loaded l) { return poses.computeIfAbsent(l, k -> new Pose(k.model)); }

	private static String pick(String[] a, int v) { return a[Math.floorMod(v, a.length)]; }

	/** Which clip plays, and how far into it (seconds). */
	private static String clip(ZcZombieState s) {
		if (s.deathTime > 0) return pick(DEATH, s.variant);
		if (s.stage == 1) return "ai_zombie_boardtear_aligned_m_1_pull";
		if (s.stage == 2) return "ai_zombie_barricade_enter_m_v1";
		if (s.attackTime > 0) return pick(ATTACK, s.variant);
		return switch (s.tier) { case 0 -> pick(WALK, s.variant >> 1); case 1 -> pick(RUN, s.variant >> 1); default -> pick(SPRINT, s.variant >> 1); };
	}

	@Override public void render(ZcZombieState s, PoseStack ps, MultiBufferSource buf, int light) {
		Bo2Mesh.Loaded body = Bo2Mesh.get(pick(BODIES, s.variant)), head = Bo2Mesh.get(pick(HEADS, s.variant >> 2));
		if (body == null) { super.render(s, ps, buf, light); return; }
		String name = clip(s);
		XAnim a = Bo2Mesh.anim(name);
		Pose bp = pose(body);
		bp.reset();
		if (a != null) {
			float t = (s.ageInTicks + (s.variant & 15) * 7f) / 20f, frame;
			if (s.deathTime > 0) frame = Math.min(a.numFrames, s.deathTime / 20f * a.frameRate * 1.3f); // plays out over the removal delay
			else if (s.attackTime > 0) frame = Math.min(a.numFrames, s.attackTime * a.numFrames);
			else frame = (t * a.frameRate) % Math.max(1, a.numFrames);
			bp.apply(a, frame, 1f);
		}
		bp.build();
		if (a != null && !LIFT.containsKey(name)) { // the clips put the model about 37 inches under the floor: find each clip's lowest point once and lift by it
			float lo = 1e9f; float[] t3 = new float[3];
			for (int f = 0; f <= a.numFrames; f += Math.max(1, a.numFrames / 8)) { bp.reset(); bp.apply(a, f, 1f); bp.build(); for (int v = 0; v < body.model.vertCount; v += 5) { bp.skinPos(v, t3); lo = Math.min(lo, t3[2]); } }
			LIFT.put(name, -lo);
			bp.reset(); bp.apply(a, 0f, 1f); bp.build();
		}
		ps.pushPose();
		ps.mulPose(Axis.YP.rotationDegrees(-s.bodyRot));
		ps.translate(0f, LIFT.getOrDefault(name, 0f) * SCALE, 0f);
		Bo2Mesh.draw(body, bp, ps, buf, light, SCALE, 0);
		if (head != null) {
			Pose hp = pose(head);
			hp.reset();
			if (a != null) hp.apply(a, 0f, 0f);
			hp.followWorld(bp);
			Bo2Mesh.draw(head, hp, ps, buf, light, SCALE, 0);
		}
		ps.popPose();
	}
}

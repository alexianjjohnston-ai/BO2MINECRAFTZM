package com.zombiecraft.client.render;

import com.zombiecraft.entity.ZcZombie;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.HumanoidMobRenderer;
import net.minecraft.resources.ResourceLocation;

/** Draws the Zombies-mode zombie: humanoid body, per-zombie decayed skin, and poses driven by the server-synced stage and speed tier. */
public class ZcZombieRenderer extends HumanoidMobRenderer<Zombie, ZcZombieState, ZcZombieModel> {
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
}

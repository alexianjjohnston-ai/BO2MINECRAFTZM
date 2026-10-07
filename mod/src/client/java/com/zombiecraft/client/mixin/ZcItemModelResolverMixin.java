package com.zombiecraft.client.mixin;

import com.zombiecraft.client.render.ZcItemModels;
import com.zombiecraft.client.render.ZcRenderStateAccess;
import net.minecraft.client.renderer.item.ItemModelResolver;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Marks the render state of our items so ItemStackRenderState can draw their cached meshes. */
@Mixin(ItemModelResolver.class)
public abstract class ZcItemModelResolverMixin {
	@Inject(method = "updateForTopItem", at = @At("TAIL"))
	private void zombiecraft$top(ItemStackRenderState state, ItemStack stack, ItemDisplayContext ctx, boolean left, Level level, LivingEntity entity, int seed, CallbackInfo ci) {
		((ZcRenderStateAccess) state).zombiecraft$setModel(ZcItemModels.markFor(stack, ctx));
	}

	@Inject(method = "updateForNonLiving", at = @At("TAIL"))
	private void zombiecraft$nonLiving(ItemStackRenderState state, ItemStack stack, ItemDisplayContext ctx, Entity entity, CallbackInfo ci) {
		((ZcRenderStateAccess) state).zombiecraft$setModel(ZcItemModels.markFor(stack, ctx));
	}

	@Inject(method = "updateForLiving", at = @At("TAIL"))
	private void zombiecraft$living(ItemStackRenderState state, ItemStack stack, ItemDisplayContext ctx, boolean left, LivingEntity entity, CallbackInfo ci) {
		((ZcRenderStateAccess) state).zombiecraft$setModel(ZcItemModels.markFor(stack, ctx));
	}
}

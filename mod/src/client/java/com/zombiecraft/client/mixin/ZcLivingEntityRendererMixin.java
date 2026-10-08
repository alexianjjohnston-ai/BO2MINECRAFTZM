package com.zombiecraft.client.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.zombiecraft.client.render.ZcPlayerBody;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.client.renderer.entity.state.PlayerRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** During a match every player is drawn as a BO2 character instead of the vanilla Steve/Alex body. */
@Mixin(LivingEntityRenderer.class)
public abstract class ZcLivingEntityRendererMixin {
	@Inject(method = "render(Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
			at = @At("HEAD"), cancellable = true)
	private void zombiecraft$bo2Player(LivingEntityRenderState state, PoseStack poseStack, MultiBufferSource buffers, int light, CallbackInfo ci) {
		if (state instanceof PlayerRenderState player && ZcPlayerBody.render(player, poseStack, buffers, light)) ci.cancel();
	}
}

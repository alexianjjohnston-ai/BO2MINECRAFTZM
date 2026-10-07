package com.zombiecraft.client.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.zombiecraft.client.render.ZcItemModels;
import com.zombiecraft.client.render.ZcRenderStateAccess;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.world.item.ItemDisplayContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ItemStackRenderState.class)
public abstract class ZcItemStackRenderStateMixin implements ZcRenderStateAccess {
	@Shadow ItemDisplayContext displayContext;
	@Unique private String zombiecraft$model;

	@Override public String zombiecraft$getModel() { return zombiecraft$model; }
	@Override public void zombiecraft$setModel(String key) { zombiecraft$model = key; }

	@Inject(method = "clear", at = @At("HEAD"))
	private void zombiecraft$clear(CallbackInfo ci) { zombiecraft$model = null; }

	@Inject(method = "render", at = @At("HEAD"), cancellable = true)
	private void zombiecraft$render(PoseStack pose, MultiBufferSource buffers, int light, int overlay, CallbackInfo ci) {
		if (zombiecraft$model != null && ZcItemModels.render(zombiecraft$model, displayContext, pose, buffers, light)) ci.cancel();
	}
}

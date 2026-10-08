package com.zombiecraft.client.mixin;

import com.zombiecraft.client.ViewState;
import com.zombiecraft.client.hud.ZcHud;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Aiming down the sight narrows the world's field of view. The hands are drawn with their own FOV and stay the same size. */
@Mixin(GameRenderer.class)
public abstract class ZcGameRendererMixin {
	@Inject(method = "getFov", at = @At("RETURN"), cancellable = true)
	private void zombiecraft$adsZoom(Camera camera, float partialTick, boolean useFovSetting, CallbackInfoReturnable<Float> cir) {
		if (!useFovSetting || !ZcHud.usesWeaponHud(Minecraft.getInstance())) return;
		cir.setReturnValue(cir.getReturnValueF() * ViewState.fovScale());
	}
}

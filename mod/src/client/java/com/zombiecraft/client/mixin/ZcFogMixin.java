package com.zombiecraft.client.mixin;

import com.mojang.blaze3d.shaders.FogShape;
import com.zombiecraft.client.Atmosphere;
import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.FogParameters;
import net.minecraft.client.renderer.FogRenderer;
import net.minecraft.world.level.material.FogType;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** The map's haze: BO2 Tranzit's dusty colour and short sight lines instead of vanilla's clear day. Water and lava keep their own fog. */
@Mixin(FogRenderer.class)
public abstract class ZcFogMixin {
	@Inject(method = "computeFogColor", at = @At("RETURN"), cancellable = true)
	private static void zombiecraft$fogColor(Camera camera, float partialTick, ClientLevel level, int renderDistance, float darken, CallbackInfoReturnable<Vector4f> cir) {
		var a = Atmosphere.active();
		if (a == null || camera.getFluidInCamera() != FogType.NONE) return;
		int c = Atmosphere.rgb(a.fogColor());
		cir.setReturnValue(new Vector4f((c >> 16 & 255) / 255f, (c >> 8 & 255) / 255f, (c & 255) / 255f, 1f));
	}

	@Inject(method = "setupFog", at = @At("RETURN"), cancellable = true)
	private static void zombiecraft$fog(Camera camera, FogRenderer.FogMode mode, Vector4f color, float renderDistance, boolean foggy, float partialTick, CallbackInfoReturnable<FogParameters> cir) {
		var a = Atmosphere.active();
		if (a == null || mode != FogRenderer.FogMode.FOG_TERRAIN || camera.getFluidInCamera() != FogType.NONE) return;
		cir.setReturnValue(new FogParameters((float) a.fogStart(), (float) a.fogEnd(), FogShape.SPHERE, color.x, color.y, color.z, color.w));
	}
}

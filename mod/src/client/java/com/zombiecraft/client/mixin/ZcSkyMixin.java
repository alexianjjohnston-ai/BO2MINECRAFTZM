package com.zombiecraft.client.mixin;

import com.zombiecraft.client.Atmosphere;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Sky, cloud and star values from the map's atmosphere row. */
@Mixin(ClientLevel.class)
public abstract class ZcSkyMixin {
	@Inject(method = "getSkyColor", at = @At("RETURN"), cancellable = true)
	private void zombiecraft$sky(Vec3 pos, float partialTick, CallbackInfoReturnable<Integer> cir) {
		var a = Atmosphere.active();
		if (a != null) cir.setReturnValue(0xFF000000 | Atmosphere.rgb(a.skyColor()));
	}

	@Inject(method = "getCloudColor", at = @At("RETURN"), cancellable = true)
	private void zombiecraft$cloud(float partialTick, CallbackInfoReturnable<Integer> cir) {
		var a = Atmosphere.active();
		if (a != null) cir.setReturnValue(0xFF000000 | Atmosphere.rgb(a.cloudColor()));
	}

	@Inject(method = "getStarBrightness", at = @At("RETURN"), cancellable = true)
	private void zombiecraft$stars(float partialTick, CallbackInfoReturnable<Float> cir) {
		var a = Atmosphere.active();
		if (a != null) cir.setReturnValue((float) a.stars());
	}
}

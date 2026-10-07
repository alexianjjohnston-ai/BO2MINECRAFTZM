package com.zombiecraft.client.mixin;

import com.zombiecraft.client.hud.ZcHud;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.PlayerTabOverlay;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.Scoreboard;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The BO2 scoreboard in ZcHud replaces vanilla's player list during a match. */
@Mixin(PlayerTabOverlay.class)
public abstract class ZcTabOverlayMixin {
	@Inject(method = "render", at = @At("HEAD"), cancellable = true)
	private void zombiecraft$hideTabList(GuiGraphics graphics, int width, Scoreboard scoreboard, Objective objective, CallbackInfo ci) {
		if (ZcHud.usesWeaponHud(Minecraft.getInstance())) ci.cancel();
	}
}

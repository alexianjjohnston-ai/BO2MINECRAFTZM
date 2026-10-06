package com.zombiecraft.client.mixin;

import com.zombiecraft.client.hud.ZcHud;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphics;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The Zombiecraft HUD supplies the weapon slots and weapon name while a match is running. */
@Mixin(Gui.class)
public abstract class ZcGuiMixin {
	@Inject(method = "renderItemHotbar", at = @At("HEAD"), cancellable = true)
	private void zombiecraft$hideVanillaHotbar(GuiGraphics graphics, DeltaTracker deltaTracker, CallbackInfo ci) {
		if (ZcHud.usesWeaponHud(Minecraft.getInstance())) ci.cancel();
	}

	@Inject(method = "renderSelectedItemName", at = @At("HEAD"), cancellable = true)
	private void zombiecraft$hideDuplicateItemName(GuiGraphics graphics, CallbackInfo ci) {
		if (ZcHud.usesWeaponHud(Minecraft.getInstance())) ci.cancel();
	}
}

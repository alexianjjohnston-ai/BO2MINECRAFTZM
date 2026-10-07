package com.zombiecraft.client.mixin;

import com.zombiecraft.client.menu.Bo2Online;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The failure message of a refused or dropped connection keeps the BO2 backdrop and menu music. */
@Mixin(DisconnectedScreen.class)
public abstract class ZcDisconnectedScreenMixin {
	@Inject(method = "render", at = @At("HEAD"))
	private void zombiecraft$bo2Backdrop(GuiGraphics graphics, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
		Bo2Online.disconnected(graphics, (Screen) (Object) this);
	}
}

package com.zombiecraft.client.mixin;

import com.zombiecraft.client.menu.Bo2Online;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Online play: vanilla's "Connecting to the server" screen is drawn the BO2 way, with a timeout. */
@Mixin(ConnectScreen.class)
public abstract class ZcConnectScreenMixin {
	@Inject(method = "render", at = @At("HEAD"), cancellable = true)
	private void zombiecraft$bo2Connecting(GuiGraphics graphics, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
		Bo2Online.connecting(graphics, (Screen) (Object) this);
		ci.cancel();
	}
}

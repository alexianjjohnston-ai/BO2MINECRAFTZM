package com.zombiecraft.client.mixin;

import com.zombiecraft.client.menu.Bo2Online;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.Screen;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Online play: vanilla's "Connecting to the server" screen is drawn the BO2 way, with a timeout. */
@Mixin(ConnectScreen.class)
public abstract class ZcConnectScreenMixin {
	@Inject(method = "render", at = @At("HEAD"), cancellable = true)
	private void zombiecraft$bo2Connecting(GuiGraphics graphics, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
		Bo2Online.connecting(graphics, (Screen) (Object) this);
		ci.cancel();
	}

	@Inject(method = "keyPressed", at = @At("HEAD"), cancellable = true)
	private void zombiecraft$escCancels(int key, int scan, int mods, CallbackInfoReturnable<Boolean> cir) {
		if (key != GLFW.GLFW_KEY_ESCAPE) return;
		Bo2Online.cancel((Screen) (Object) this);
		cir.setReturnValue(true);
	}
}

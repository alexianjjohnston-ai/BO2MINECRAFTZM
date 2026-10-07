package com.zombiecraft.client.mixin;

import com.zombiecraft.client.menu.Bo2Menus;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.LevelLoadingScreen;
import net.minecraft.client.gui.screens.ProgressScreen;
import net.minecraft.client.gui.screens.ReceivingLevelScreen;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The world loading screens (and their percent text and chunk grid) are replaced by the BO2 loading picture. */
@Mixin({LevelLoadingScreen.class, ReceivingLevelScreen.class, ProgressScreen.class})
public abstract class ZcLoadingScreenMixin {
	@Inject(method = "render", at = @At("HEAD"), cancellable = true)
	private void zombiecraft$bo2Loading(GuiGraphics graphics, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
		Screen self = (Screen) (Object) this;
		Bo2Menus.loading(graphics, self.width, self.height);
		ci.cancel();
	}
}

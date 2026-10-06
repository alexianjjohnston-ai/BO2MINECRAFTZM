package com.zombiecraft.client.mixin;

import com.zombiecraft.client.hud.ZcHud;
import com.zombiecraft.item.ModItems;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.ScrollWheelHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Keep mouse-wheel selection on the occupied weapon slots shown by the HUD. */
@Mixin(MouseHandler.class)
public abstract class ZcMouseHandlerMixin {
	@Redirect(method = "onScroll", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/ScrollWheelHandler;getNextScrollWheelSelection(DII)I"))
	private int zombiecraft$cycleWeapons(double scroll, int selected, int slotCount) {
		Minecraft mc = Minecraft.getInstance();
		if (ZcHud.usesWeaponHud(mc) && scroll != 0) {
			int direction = -(int) Math.signum(scroll);
			for (int step = 1; step <= slotCount; step++) {
				int candidate = Math.floorMod(selected + direction * step, slotCount);
				if (ModItems.weaponOf(mc.player.getInventory().getItem(candidate)) != null) return candidate;
			}
			return selected;
		}
		return ScrollWheelHandler.getNextScrollWheelSelection(scroll, selected, slotCount);
	}
}

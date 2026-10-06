package com.zombiecraft.client.hud;

import com.zombiecraft.client.ZombiecraftClient;
import com.zombiecraft.client.GunFeedback;
import com.zombiecraft.item.ModItems;
import com.zombiecraft.net.Payloads;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Inventory;

/** Round counter, points, ammo, prompts and phase banners, drawn from the last StateSync. */
public final class ZcHud {
	private static final int DAMAGE_FLASH_TICKS = 10;
	private static LocalPlayer trackedPlayer;
	private static float previousHealth;
	private static int previousHurtTime, previousPhase, damageFlashTicks;

	private ZcHud() {}

	/** Shared scope for the replacement hotbar and its vanilla GUI hooks. */
	public static boolean usesWeaponHud(Minecraft mc) {
		return mc.player != null && mc.level != null && !mc.player.isSpectator()
			&& ZombiecraftClient.state.phase() != Payloads.PHASE_IDLE;
	}

	/** Called once per client tick, even when the HUD is hidden. */
	public static void tick(Minecraft mc) {
		int phase = ZombiecraftClient.state.phase();
		if (!usesWeaponHud(mc)) {
			reset();
			return;
		}
		LocalPlayer player = mc.player;
		if (phase == Payloads.PHASE_GAMEOVER) {
			// The server restores health on a fatal hit, so its phase transition is the damage signal.
			if (trackedPlayer != player) damageFlashTicks = 0;
			else if (previousPhase != phase) damageFlashTicks = DAMAGE_FLASH_TICKS;
			else if (!mc.isPaused() && damageFlashTicks > 0) damageFlashTicks--;
			trackedPlayer = player;
			previousPhase = phase;
			return;
		}
		float health = player.getHealth();
		if (trackedPlayer != player || (phase == Payloads.PHASE_COUNTDOWN && previousPhase != phase)) {
			trackedPlayer = player;
			previousHealth = health;
			previousHurtTime = player.hurtTime;
			damageFlashTicks = 0;
		}
		if (!mc.isPaused()) {
			if (damageFlashTicks > 0) damageFlashTicks--;
			// A new hurt pulse also covers damage absorbed before it lowers health.
			// The rising edge prevents a held hurtTime from restarting the flash each tick.
			if (health < previousHealth || player.hurtTime > previousHurtTime) damageFlashTicks = DAMAGE_FLASH_TICKS;
			previousHealth = health;
			previousHurtTime = player.hurtTime;
		}
		previousPhase = phase;
	}

	public static void reset() {
		trackedPlayer = null;
		previousHealth = 0;
		previousHurtTime = previousPhase = damageFlashTicks = 0;
	}

	private static void damageFlash(GuiGraphics g, float partialTick) {
		float strength = Math.max(0f, (damageFlashTicks - partialTick) / DAMAGE_FLASH_TICKS);
		if (strength <= 0f) return;
		int w = g.guiWidth(), h = g.guiHeight();
		int edge = Math.max(12, Math.min(48, Math.min(w, h) / 8));
		for (int i = 0; i < edge; i++) {
			float fade = 1f - (float) i / edge;
			int alpha = (int) (110 * strength * fade * fade);
			if (alpha == 0) continue;
			int color = (alpha << 24) | 0xC01010;
			g.fill(i, i, w - i, i + 1, color);
			g.fill(i, h - i - 1, w - i, h - i, color);
			g.fill(i, i + 1, i + 1, h - i - 1, color);
			g.fill(w - i - 1, i + 1, w - i, h - i - 1, color);
		}
	}

	private static void weaponSlots(GuiGraphics g, Minecraft mc) {
		Inventory inventory = mc.player.getInventory();
		int count = 0;
		for (int slot = 0; slot < Inventory.getSelectionSize(); slot++) {
			if (ModItems.weaponOf(inventory.getItem(slot)) != null) count++;
		}
		if (count == 0) return;
		int width = 28, gap = 4;
		int x = (g.guiWidth() - (count * (width + gap) - gap)) / 2;
		int y = g.guiHeight() - 27;
		for (int slot = 0; slot < Inventory.getSelectionSize(); slot++) {
			var stack = inventory.getItem(slot);
			if (ModItems.weaponOf(stack) == null) continue;
			boolean selected = slot == inventory.selected;
			g.fill(x, y, x + width, y + 24, selected ? 0xC02A2722 : 0x8A141414);
			g.renderOutline(x, y, width, 24, selected ? 0xFFD8A020 : 0x80666666);
			g.renderItem(stack, x + 6, y + 2);
			g.drawString(mc.font, Integer.toString(slot + 1), x + 2, y + 14, selected ? 0xFFFFD878 : 0xFFAAAAAA, true);
			x += width + gap;
		}
	}

	private static void hitMarker(GuiGraphics g, Minecraft mc, float partialTick) {
		if (!mc.options.getCameraType().isFirstPerson() || mc.screen != null) return;
		int alpha = (int) (255 * GunFeedback.hitMarkerAlpha(partialTick));
		if (alpha <= 0) return;
		int rgb = GunFeedback.hitMarkerKilled() ? 0xFF5555 : GunFeedback.hitMarkerHeadshot() ? 0xFFD878 : 0xFFFFFF;
		int cx = g.guiWidth() / 2, cy = g.guiHeight() / 2;
		// Four separated diagonal strokes leave the aim point visible.
		for (int pass = 0; pass < 2; pass++) {
			int color = (alpha << 24) | (pass == 0 ? 0x121212 : rgb);
			int padding = pass == 0 ? 1 : 0;
			for (int dx = -1; dx <= 1; dx += 2) for (int dy = -1; dy <= 1; dy += 2) {
				for (int offset = 4; offset < 9; offset++) {
					int x = cx + dx * offset, y = cy + dy * offset;
					g.fill(x - padding, y - padding, x + 1 + padding, y + 1 + padding, color);
				}
			}
		}
	}

	private static void text(GuiGraphics g, Font font, String s, int x, int y, float scale, int color, boolean centered) {
		g.pose().pushPose();
		g.pose().scale(scale, scale, 1f);
		int w = font.width(s);
		int px = centered ? (int) (x / scale - w / 2f) : (int) (x / scale);
		g.drawString(font, s, px, (int) (y / scale), color, true);
		g.pose().popPose();
	}

	public static void render(GuiGraphics g, DeltaTracker dt) {
		Minecraft mc = Minecraft.getInstance();
		if (!usesWeaponHud(mc) || mc.options.hideGui) return;
		Payloads.StateSync s = ZombiecraftClient.state;
		Font font = mc.font;
		int w = g.guiWidth(), h = g.guiHeight();
		float partialTick = dt.getGameTimeDeltaPartialTick(false);
		damageFlash(g, partialTick);

		// tell the player where the sounds come from
		var audio = com.zombiecraft.client.audio.AudioCache.status;
		if (audio == com.zombiecraft.client.audio.AudioCache.Status.WORKING) text(g, font, "Preparing Black Ops II sounds...", 8, 6, 1f, 0xFF999999, false);
		else if (audio == com.zombiecraft.client.audio.AudioCache.Status.NO_BO2) text(g, font, "Black Ops II not found: using Minecraft sounds (see config/zombiecraft.properties)", 8, 6, 1f, 0xFFCC9944, false);

		if (s.phase() == Payloads.PHASE_GAMEOVER) {
			text(g, font, "GAME OVER", w / 2, h / 2 - 40, 4f, 0xFFCC1111, true);
			text(g, font, "You survived " + s.roundsSurvived() + (s.roundsSurvived() == 1 ? " round" : " rounds"), w / 2, h / 2 + 8, 2f, 0xFFFFFFFF, true);
			return;
		}
		weaponSlots(g, mc);
		hitMarker(g, mc, partialTick);

		// round counter (bottom left, red) and points
		if (s.round() > 0) text(g, font, String.valueOf(s.round()), 14, h - 78, 5f, 0xFFB01010, false);
		text(g, font, String.valueOf(s.points()), 16, h - 30, 2f, 0xFFFFFFFF, false);

		// ammo (bottom right)
		if (s.mag() >= 0) {
			text(g, font, s.gun(), w - 14 - font.width(s.gun()), h - 60, 1f, 0xFFDDDDDD, false);
			String ammo = s.mag() + " / " + s.reserve();
			text(g, font, ammo, w - 14 - (int) (font.width(ammo) * 2f), h - 48, 2f, s.mag() == 0 ? 0xFFFF4444 : 0xFFFFFFFF, false);
			if (GunFeedback.isReloading()) {
				int barWidth = 64;
				text(g, font, "RELOADING", w - 14 - font.width("RELOADING"), h - 25, 1f, 0xFFFFD878, false);
				g.fill(w - 14 - barWidth, h - 12, w - 14, h - 10, 0xA0404040);
				g.fill(w - 14 - barWidth, h - 12, w - 14 - barWidth + (int) (barWidth * GunFeedback.reloadProgress(partialTick)), h - 10, 0xFFD8A020);
			}
		}

		// zombies left (top right, small)
		if (s.phase() == Payloads.PHASE_ACTIVE) text(g, font, "Zombies: " + s.zombiesLeft(), w - 12 - font.width("Zombies: 00"), 10, 1f, 0xFFAAAAAA, false);

		// banners
		if (s.phase() == Payloads.PHASE_COUNTDOWN) {
			text(g, font, "Get ready... " + s.countdownSec(), w / 2, h / 3, 3f, 0xFFFFFFFF, true);
			text(g, font, "Right click: shoot    R: reload    Left click: knife", w / 2, h / 3 + 40, 1.0f, 0xFFDDDDDD, true);
			text(g, font, "F: buy guns, Mystery Box, Pack-a-Punch  (hold F to repair windows)", w / 2, h / 3 + 54, 1.0f, 0xFFDDDDDD, true);
		}
		else if (s.phase() == Payloads.PHASE_INTERMISSION) text(g, font, "Next round in " + s.countdownSec(), w / 2, h / 4, 2f, 0xFFCCCCCC, true);
		if (!s.message().isEmpty()) text(g, font, s.message(), w / 2, h / 4 + 24, 3f, 0xFFD8A020, true);

		// prompt (lower middle)
		if (!s.prompt().isEmpty()) text(g, font, s.prompt(), w / 2, h / 2 + 46, 1.2f, 0xFFFFFFFF, true);
	}
}

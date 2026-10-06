package com.zombiecraft.client.hud;

import com.zombiecraft.client.ZombiecraftClient;
import com.zombiecraft.client.GunFeedback;
import com.zombiecraft.client.menu.UiArt;
import com.zombiecraft.client.menu.UiFont;
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
	private static int previousHurtTime, previousPhase, damageFlashTicks, lastPoints = -1, popup, popupTicks;

	private ZcHud() {}

	/** Shared scope for the replacement hotbar and its vanilla GUI hooks. */
	public static boolean usesWeaponHud(Minecraft mc) {
		return mc.player != null && mc.level != null && !mc.player.isSpectator()
			&& ZombiecraftClient.state.phase() != Payloads.PHASE_IDLE;
	}

	/** Called once per client tick, even when the HUD is hidden. */
	public static void tick(Minecraft mc) {
		if (popupTicks > 0) popupTicks--;
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
		lastPoints = -1;
		popupTicks = 0;
		previousHealth = 0;
		previousHurtTime = previousPhase = damageFlashTicks = 0;
	}

	/** Red screen edge: a pulse when hit, and a steady one while health is low (BO2 has no hearts). */
	private static void damageFlash(GuiGraphics g, Minecraft mc, float partialTick) {
		float strength = Math.max(0f, (damageFlashTicks - partialTick) / DAMAGE_FLASH_TICKS);
		float low = 1f - mc.player.getHealth() / (mc.player.getMaxHealth() * 0.5f);
		strength = Math.max(strength, Math.max(0f, Math.min(1f, low)) * 0.8f);
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

	/** BO2 weapon icon files by weapon id (pack-a-punched guns use the base gun's icon). */
	private static String icon(String weaponId) {
		String id = weaponId.replace("_pap", "");
		return switch (id) {
			case "m1911" -> "menu_mp_weapons_1911_big";
			case "rottweil72" -> "menu_mp_weapons_olympia_big";
			case "mp5k" -> "menu_mp_weapons_mp5_big";
			case "ray_gun" -> "menu_zm_weapons_raygun_big";
			default -> "menu_mp_weapons_" + id + "_big";
		};
	}

	/** The gun in hand as a big icon bottom right, the others small and dim to its left. Returns false when no icon art exists. */
	private static boolean weaponIcons(GuiGraphics g, Minecraft mc) {
		Inventory inventory = mc.player.getInventory();
		int w = g.guiWidth(), h = g.guiHeight(), x = w - 14 - 128, shown = 0;
		for (int slot = 0; slot < Inventory.getSelectionSize(); slot++) {
			String id = ModItems.weaponOf(inventory.getItem(slot));
			if (id == null) continue;
			if (slot == inventory.selected) {
				if (!UiArt.draw(g, icon(id), w - 14 - 128, h - 118, 128, 64)) return false;
			} else {
				shown++;
				UiArt.draw(g, icon(id), x - shown * 66, h - 98, 64, 32, 0x80FFFFFF);
			}
		}
		return true;
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

	/** Blood-red chalk tally marks: four strokes struck through by a fifth. */
	private static void tally(GuiGraphics g, int n, int x, int y) {
		for (int group = 0; group * 5 < n; group++) {
			int marks = Math.min(5, n - group * 5), gx = x + group * 40;
			for (int i = 0; i < Math.min(4, marks); i++) { g.fill(gx + i * 7 + 1, y + 1, gx + i * 7 + 4, y + 33, 0xFF300404); g.fill(gx + i * 7, y, gx + i * 7 + 3, y + 32, 0xFFB01010); }
			if (marks == 5) for (int k = 0; k < 32; k++) { int dx = gx - 2 + k * 30 / 32, dy = y + 30 - k; g.fill(dx, dy, dx + 4, dy + 3, 0xFFB01010); }
		}
	}

	private static void text(GuiGraphics g, Font font, String s, int x, int y, float scale, int color, boolean centered) {
		float height = 9f * scale * 1.3f;
		int px = centered ? x - UiFont.width(s, height) / 2 : x;
		UiFont.draw(g, s, px, y, height, color, true);
	}

	public static void render(GuiGraphics g, DeltaTracker dt) {
		Minecraft mc = Minecraft.getInstance();
		if (!usesWeaponHud(mc) || mc.options.hideGui) return;
		Payloads.StateSync s = ZombiecraftClient.state;
		Font font = mc.font;
		int w = g.guiWidth(), h = g.guiHeight();
		float partialTick = dt.getGameTimeDeltaPartialTick(false);
		damageFlash(g, mc, partialTick);

		// tell the player where the sounds come from
		var audio = com.zombiecraft.client.audio.AudioCache.status;
		if (audio == com.zombiecraft.client.audio.AudioCache.Status.WORKING) text(g, font, "Preparing Black Ops II sounds...", 8, 6, 1f, 0xFF999999, false);
		else if (audio == com.zombiecraft.client.audio.AudioCache.Status.NO_BO2) text(g, font, "Black Ops II not found: using Minecraft sounds (see config/zombiecraft.properties)", 8, 6, 1f, 0xFFCC9944, false);

		if (s.phase() == Payloads.PHASE_GAMEOVER) {
			text(g, font, "GAME OVER", w / 2, h / 2 - 40, 4f, 0xFFCC1111, true);
			text(g, font, "You survived " + s.roundsSurvived() + (s.roundsSurvived() == 1 ? " round" : " rounds"), w / 2, h / 2 + 8, 2f, 0xFFFFFFFF, true);
			return;
		}
		boolean icons = weaponIcons(g, mc);
		hitMarker(g, mc, partialTick);

		// round counter (bottom left, red) and points
		if (s.round() > 10) text(g, font, String.valueOf(s.round()), 14, h - 78, 5f, 0xFFB01010, false);
		else tally(g, s.round(), 16, h - 74);
		if (s.points() != lastPoints) { if (s.points() > lastPoints && lastPoints >= 0) { popup = s.points() - lastPoints; popupTicks = 50; } lastPoints = s.points(); }
		text(g, font, String.valueOf(s.points()), 16, h - 52, 2.4f, 0xFFFFFFFF, false);
		if (popupTicks > 0) text(g, font, "+" + popup, 18, h - 26, 1.5f, (Math.min(255, popupTicks * 8) << 24) | 0x5FE0E8, false);

		// ammo (bottom right)
		if (s.mag() >= 0) {
			if (!icons) text(g, font, s.gun(), w - 14 - font.width(s.gun()), h - 60, 1f, 0xFFDDDDDD, false);
			String ammo = s.mag() + "/" + s.reserve();
			text(g, font, ammo, w - 14 - UiFont.width(ammo, 9f * 2.4f * 1.3f), h - 50, 2.4f, s.mag() == 0 ? 0xFFFF4444 : 0xFFFFFFFF, false);
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

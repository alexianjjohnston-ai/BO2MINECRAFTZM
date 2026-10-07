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
	private static int previousHurtTime, previousPhase, damageFlashTicks, lastPoints = -1, popup, popupTicks, heartTicks;

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
		// low health: BO2's heartbeat
		if (!mc.isPaused() && health <= player.getMaxHealth() * 0.35f && ++heartTicks >= 24) { heartTicks = 0; com.zombiecraft.client.audio.MenuAudio.play("chr_heart_beat_ingame"); }
		else if (health > player.getMaxHealth() * 0.35f) heartTicks = 24;
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
		if (UiArt.draw(g, "overlay_low_health", 0, 0, w, h, ((int) (255 * Math.min(1f, strength * 1.2f)) << 24) | 0xFFFFFF)) return;
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

	/** Icon file of the gun in hand, or null. */
	private static String heldIcon(Minecraft mc) {
		Inventory inventory = mc.player.getInventory();
		String id = ModItems.weaponOf(inventory.getItem(inventory.selected));
		return id == null ? null : icon(id);
	}

	/** BO2's small four-tick crosshair (the vanilla one is hidden while a match runs). */
	private static void crosshair(GuiGraphics g, Minecraft mc) {
		if (!mc.options.getCameraType().isFirstPerson() || mc.screen != null) return;
		int cx = g.guiWidth() / 2, cy = g.guiHeight() / 2;
		for (int pass = 0; pass < 2; pass++) {
			int c = pass == 0 ? 0x90000000 : 0xFFFFFFFF, o = pass == 0 ? 1 : 0;
			g.fill(cx - o, cy - 7 - o, cx + 1 + o, cy - 3 + o, c);
			g.fill(cx - o, cy + 4 - o, cx + 1 + o, cy + 8 + o, c);
			g.fill(cx - 7 - o, cy - o, cx - 3 + o, cy + 1 + o, c);
			g.fill(cx + 4 - o, cy - o, cx + 8 + o, cy + 1 + o, c);
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

	/** Round tallies from BO2's own chalk-mark images, tinted blood red: groups of five, then the remainder. */
	private static void tally(GuiGraphics g, int n, int x, int y, int size) {
		int step = size * 3 / 4, groups = n / 5, rest = n % 5;
		for (int i = 0; i < groups; i++) UiArt.draw(g, "chalkmarks_5", x + i * step, y, size, size, 0xFFB01010);
		if (rest > 0) UiArt.draw(g, "chalkmarks_" + rest, x + groups * step, y, size, size, 0xFFB01010);
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
		hitMarker(g, mc, partialTick);
		crosshair(g, mc);

		// round tallies bottom left (a number once past round 10)
		int tallySize = Math.max(32, h / 6);
		if (s.round() > 10) text(g, font, String.valueOf(s.round()), 14, h - 14 - (int) (9 * 4f * 1.3f), 4f, 0xFFB01010, false);
		else if (s.round() > 0) tally(g, s.round(), 6, h - tallySize - 6, tallySize);

		// bottom right, stacked up from the ammo line so nothing overlaps: blood splat, points, +points, ammo
		float ammoScale = 2.2f, pointsScale = 1.9f, popScale = 1.2f;
		int right = w - Math.max(16, w / 14), ammoH = (int) (9 * ammoScale * 1.3f), popH = (int) (9 * popScale * 1.3f), pointsH = (int) (9 * pointsScale * 1.3f);
		int ammoY = h - 10 - ammoH, popY = ammoY - popH - 3, pointsY = popY - pointsH - 1;
		UiArt.draw(g, "hud_dpad_blood", right - 130, pointsY - 12, 170, 85 + (h - 10 - pointsY) - 40, 0xA0B01010);
		if (s.points() != lastPoints) { if (s.points() > lastPoints && lastPoints >= 0) { popup = s.points() - lastPoints; popupTicks = 50; } lastPoints = s.points(); }
		String pts = String.valueOf(s.points());
		text(g, font, pts, right - UiFont.width(pts, 9f * pointsScale * 1.3f), pointsY, pointsScale, 0xFFFFFFFF, false);
		if (popupTicks > 0) {
			String pop = "+" + popup;
			text(g, font, pop, right - UiFont.width(pop, 9f * popScale * 1.3f), popY, popScale, (Math.min(255, popupTicks * 8) << 24) | 0x5FE0E8, false);
		}
		if (s.mag() >= 0) {
			String ammo = s.mag() + "/" + s.reserve();
			int ammoW = UiFont.width(ammo, 9f * ammoScale * 1.3f);
			text(g, font, ammo, right - ammoW, ammoY, ammoScale, s.mag() == 0 ? 0xFFFF4444 : 0xFFFFFFFF, false);
			String held = heldIcon(mc);
			if (held != null && !UiArt.draw(g, held, right - ammoW - 70, ammoY - 4, 64, 32, 0xC0FFFFFF)) text(g, font, s.gun(), right - ammoW - 8 - UiFont.width(s.gun(), 9f * 1.3f), ammoY + 6, 1f, 0xFFDDDDDD, false);
			UiArt.draw(g, "grenadeicon_32", right + 8, ammoY, 20, 20);
			if (GunFeedback.isReloading()) {
				int barWidth = 64;
				g.fill(right - barWidth, h - 6, right, h - 4, 0xA0404040);
				g.fill(right - barWidth, h - 6, right - barWidth + (int) (barWidth * GunFeedback.reloadProgress(partialTick)), h - 4, 0xFFD8A020);
			}
		}

		// perk icons (BO2's own) along the bottom, power-up timers at the top middle
		String[] perkIcons = {"specialty_juggernaut_zombies", "specialty_fastreload_zombies", "specialty_doubletap_zombies", "specialty_quickrevive_zombies"};
		int owned = 0;
		for (int b = 0; b < 4; b++) if ((s.perks() & (1 << b)) != 0) owned++;
		int ix = w / 2 - owned * 17;
		for (int b = 0; b < 4; b++) {
			if ((s.perks() & (1 << b)) == 0) continue;
			UiArt.draw(g, perkIcons[b], ix, h - 40, 32, 32);
			ix += 34;
		}
		int ty = (int) (h * 0.12);
		if (s.instaSec() > 0) { text(g, font, "INSTA-KILL  " + s.instaSec(), w / 2, ty, 1.3f, 0xFFFF5050, true); ty += 18; }
		if (s.doubleSec() > 0) text(g, font, "DOUBLE POINTS  " + s.doubleSec(), w / 2, ty, 1.3f, 0xFFFFE060, true);

		// banners
		if (!s.message().isEmpty()) text(g, font, s.message(), w / 2, h / 4 + 24, 3f, 0xFFD8A020, true);

		// prompt (lower middle)
		if (!s.prompt().isEmpty()) text(g, font, s.prompt(), w / 2, h / 2 + 46, 1.2f, 0xFFFFFFFF, true);
	}
}

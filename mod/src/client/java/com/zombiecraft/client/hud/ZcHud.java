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
			&& ZombiecraftClient.state.phase() != Payloads.PHASE_IDLE && ZombiecraftClient.state.phase() != Payloads.PHASE_LOBBY;
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
			else if (previousPhase != phase) { damageFlashTicks = DAMAGE_FLASH_TICKS; com.zombiecraft.client.audio.MenuAudio.play("uin_lobby_summary"); }
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

	private static double hitX, hitZ;
	private static long hitTick = -1000;

	public static void hitFrom(double x, double z) {
		hitX = x; hitZ = z;
		Minecraft mc = Minecraft.getInstance();
		hitTick = mc.level == null ? -1000 : mc.level.getGameTime();
	}

	/** BO2's hit direction: a red wedge on a ring around the crosshair, pointing at whatever hit you. */
	private static void hitDirection(GuiGraphics g, Minecraft mc, float partialTick) {
		if (mc.player == null || mc.level == null) return;
		float age = mc.level.getGameTime() - hitTick + partialTick;
		if (age < 0 || age > 40) return;
		double dx = hitX - mc.player.getX(), dz = hitZ - mc.player.getZ();
		double world = Math.atan2(-dx, dz); // yaw that faces the attacker
		double rel = Math.toRadians(world * 180 / Math.PI - mc.player.getViewYRot(partialTick));
		int cx = g.guiWidth() / 2, cy = g.guiHeight() / 2, r = Math.min(cx, cy) / 2;
		int alpha = (int) (200 * (1f - age / 40f));
		for (int i = -6; i <= 6; i++) {
			double a = rel + i * 0.045;
			int x = cx + (int) Math.round(Math.sin(a) * r), y = cy + (int) Math.round(Math.cos(a) * -r);
			int half = 6 - Math.abs(i) / 2;
			g.fill(x - half / 2, y - half / 2, x + half / 2 + 1, y + half / 2 + 1, (alpha << 24) | 0xC01010);
		}
	}

	/** Brief warm light bloom on the screen when firing (the gun lights up the room). */
	private static void muzzleBloom(GuiGraphics g, Minecraft mc, float partialTick) {
		if (!mc.options.getCameraType().isFirstPerson()) return;
		float age = GunFeedback.shotAgeTicks(partialTick);
		if (age >= 2f) return;
		int a = (int) (38 * (1f - age / 2f));
		int w = g.guiWidth(), h = g.guiHeight();
		g.fillGradient(0, h / 3, w, h, 0x00FFD890, (a << 24) | 0xFFD890);
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

	/** Downed: red screen, bleed-out countdown. Reviving (either side): a progress bar under the crosshair. */
	private static void downedOverlay(GuiGraphics g, Font font, Payloads.StateSync s, int w, int h) {
		boolean down = s.bleedSec() > 0;
		if (down) {
			float pulse = 0.7f + 0.3f * (float) Math.sin(System.currentTimeMillis() / 250.0);
			if (!UiArt.draw(g, "overlay_low_health", 0, 0, w, h, ((int) (255 * pulse) << 24) | 0xFFFFFF)) g.fill(0, 0, w, h, 0x50A01010);
			text(g, font, "BLEEDING OUT  " + s.bleedSec(), w / 2, h - h / 5, 1.6f, s.bleedSec() <= 10 ? 0xFFFF4444 : 0xFFFFFFFF, true);
		}
		if (s.revivePct() > 0) {
			int bw = Math.max(120, w / 6), x = (w - bw) / 2, y = h / 2 + h / 8;
			g.fill(x - 1, y - 1, x + bw + 1, y + 7, 0xA0000000);
			g.fill(x, y, x + bw * s.revivePct() / 100, y + 6, down ? 0xFF5FE0E8 : 0xFFD8A020);
			text(g, font, down ? "BEING REVIVED" : "REVIVING", w / 2, y + 12, 0.9f, 0xFFFFFFFF, true);
		}
	}

	/** BO2 weapon icon files by weapon id (pack-a-punched guns use the base gun's icon). */
	private static String icon(String weaponId) {
		String id = weaponId.replace("_pap", "");
		return switch (id) {
			case "m1911" -> "menu_mp_weapons_1911_big";
			case "rottweil72" -> "menu_mp_weapons_olympia_big";
			case "mp5k" -> "menu_mp_weapons_mp5_big";
			case "fnfal" -> "menu_mp_weapons_fal_big";
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
	private static void tally(GuiGraphics g, int n, int x, int y, int size) { tally(g, n, x, y, size, 1f); }

	private static void tally(GuiGraphics g, int n, int x, int y, int size, float alpha) {
		int step = size * 3 / 4, groups = n / 5, rest = n % 5, color = ((int) (255 * alpha) << 24) | 0xB01010;
		for (int i = 0; i < groups; i++) UiArt.draw(g, "chalkmarks_5", x + i * step, y, size, size, color);
		if (rest > 0) UiArt.draw(g, "chalkmarks_" + rest, x + groups * step, y, size, size, color);
	}

	private static float lerp(float a, float b, float t) { return a + (b - a) * t; }
	private static float ease(float t) { t = Math.max(0f, Math.min(1f, t)); return t * t * (3f - 2f * t); }

	private static int lastRound = -1;
	private static long roundAnimStart;
	private static final float ROUND_ANIM_SECONDS = 5f;

	/**
	 * BO2's round change: the new chalk tally fades in large in the middle of the screen, holds, then shrinks into the
	 * bottom-left corner. No text. Returns true while the animation owns the tally.
	 */
	private static boolean roundTally(GuiGraphics g, Font font, int round, int w, int h, int cornerSize) {
		if (round != lastRound) {
			if (round > 0 && lastRound >= 0) roundAnimStart = System.nanoTime();
			lastRound = round;
		}
		float t = (System.nanoTime() - roundAnimStart) / 1e9f / ROUND_ANIM_SECONDS;
		if (roundAnimStart == 0 || t >= 1f || round <= 0) return false;
		float fade = ease(t / 0.12f), move = ease((t - 0.55f) / 0.35f);
		if (round > 10) {
			float scale = lerp(14f, 4f, move);
			int ph = (int) (9 * scale * 1.3f), px = (int) lerp(w / 2, 14 + UiFont.width(String.valueOf(round), 9f * 4f * 1.3f) / 2, move);
			int py = (int) lerp(h / 2 - ph / 2, h - 14 - (int) (9 * 4f * 1.3f), move);
			text(g, font, String.valueOf(round), px, py, scale, ((int) (255 * fade) << 24) | 0xB01010, true);
			return true;
		}
		int size = (int) lerp(h * 0.62f, cornerSize, move), step = size * 3 / 4, groups = round / 5, rest = round % 5;
		int totalW = groups * step + (rest > 0 ? size : size / 4);
		int x = (int) lerp(w / 2 - totalW / 2, 6, move), y = (int) lerp(h / 2 - size / 2, h - cornerSize - 6, move);
		tally(g, round, x, y, size, fade);
		if (move < 1f && round > 1) tally(g, round - 1, 6, h - cornerSize - 6, cornerSize, 1f - move);
		return true;
	}

	private static void text(GuiGraphics g, Font font, String s, int x, int y, float scale, int color, boolean centered) {
		float height = 9f * scale * 1.3f;
		int px = centered ? x - UiFont.width(s, height) / 2 : x;
		UiFont.draw(g, s, px, y, height, color, true);
	}

	/** An interact prompt: BO2 draws the key in yellow ("Hold F to buy ..."), the rest white. */
	private static void prompt(GuiGraphics g, String p, int cx, int y) {
		float hh = 9f * 1.2f * 1.3f;
		int x = cx - UiFont.width(p, hh) / 2;
		if (!p.startsWith("Hold F ")) { UiFont.draw(g, p, x, y, hh, 0xFFFFFFFF, true); return; }
		UiFont.draw(g, "Hold ", x, y, hh, 0xFFFFFFFF, true);
		UiFont.draw(g, "F", x + UiFont.width("Hold ", hh), y, hh, 0xFFF5D547, true);
		UiFont.draw(g, p.substring(6), x + UiFont.width("Hold F", hh), y, hh, 0xFFFFFFFF, true);
	}

	/** BO2's tab screen: one row per player with score, kills, downs, revives, headshots and ping. */
	private static void scoreboard(GuiGraphics g, Minecraft mc, Payloads.StateSync s) {
		if (s.phase() != Payloads.PHASE_GAMEOVER && !mc.options.keyPlayerList.isDown()) return;
		Font font = mc.font;
		int w = g.guiWidth(), h = g.guiHeight();
		int pw = Math.min(w - 40, 560), x0 = (w - pw) / 2, y0 = (int) (h * (s.phase() == Payloads.PHASE_GAMEOVER ? 0.45 : 0.32)), rowH = 18, rows = 4;
		int nameX = x0 + (int) (pw * 0.2);
		int[] colX = new int[5];
		for (int i = 0; i < 5; i++) colX[i] = x0 + (int) (pw * (0.58 + 0.08 * i));
		String[] heads = {"Score", "Kills", "Downs", "Revives", "Headshots"};
		g.fill(x0, y0, x0 + pw, y0 + rowH, 0xD0101010);
		text(g, font, "Survival - Green Run", x0 + 10, y0 + 2, 0.9f, 0xFFFFFFFF, false);
		for (int i = 0; i < 5; i++) text(g, font, heads[i], colX[i], y0 + 4, 0.65f, 0xFFBBBBBB, true);
		text(g, font, "Ping", x0 + pw - 14, y0 + 4, 0.65f, 0xFFBBBBBB, true);
		g.fill(x0, y0 + rowH + 1, x0 + pw, y0 + rowH + 1 + rowH * rows, 0xA0000000);
		// the gold column bands behind the stats
		for (int i = 0; i < 5; i += 2) g.fill(colX[i] - 22, y0 + rowH + 1, colX[i] + 22, y0 + rowH + 1 + rowH * rows, 0x60B09020);
		// one row per player; before the first roster arrives (or if it lacks this player) the local player's own numbers stand in
		java.util.List<Payloads.RosterEntry> list = new java.util.ArrayList<>(com.zombiecraft.client.ZcRoster.all());
		java.util.UUID me = mc.player.getUUID();
		if (list.stream().noneMatch(e -> e.id().equals(me)))
			list.add(0, new Payloads.RosterEntry(me, mc.getUser().getName(), s.points(), s.kills(), s.downs(), s.revives(), s.headshots(), 0));
		for (int r = 0; r < Math.min(rows, list.size()); r++) {
			Payloads.RosterEntry e = list.get(r);
			boolean mine = e.id().equals(me);
			int ry = y0 + rowH + 2 + r * rowH;
			if (mine) g.renderOutline(x0 + pw / 10, ry, pw - pw / 10, rowH, 0xFFE8760A);
			// the local numbers are live (the roster refreshes a few times a second)
			int[] vals = mine ? new int[] {s.points(), s.kills(), s.downs(), s.revives(), s.headshots()} : new int[] {e.points(), e.kills(), e.downs(), e.revives(), e.headshots()};
			text(g, font, mine ? mc.getUser().getName() : e.name(), nameX, ry + 2, 0.8f, mine ? 0xFFF0D060 : e.stance() == Payloads.RosterEntry.DEAD ? 0xFF888888 : 0xFFFFFFFF, false);
			for (int i = 0; i < 5; i++) text(g, font, String.valueOf(vals[i]), colX[i], ry + 3, 0.7f, 0xFFFFFFFF, true);
			var info = mc.getConnection() == null ? null : mc.getConnection().getPlayerInfo(e.id());
			text(g, font, String.valueOf(info == null ? 0 : info.getLatency()), x0 + pw - 14, ry + 3, 0.7f, 0xFFFFFFFF, true);
		}
	}

	public static void render(GuiGraphics g, DeltaTracker dt) {
		Minecraft mc = Minecraft.getInstance();
		if (!usesWeaponHud(mc) || mc.options.hideGui) return;
		Payloads.StateSync s = ZombiecraftClient.state;
		Font font = mc.font;
		int w = g.guiWidth(), h = g.guiHeight();
		float partialTick = dt.getGameTimeDeltaPartialTick(false);
		damageFlash(g, mc, partialTick);
		if (s.bleedSec() > 0 || s.revivePct() > 0) downedOverlay(g, font, s, w, h);

		// tell the player where the sounds come from
		var audio = com.zombiecraft.client.audio.AudioCache.status;
		if (audio == com.zombiecraft.client.audio.AudioCache.Status.WORKING) text(g, font, "Preparing Black Ops II sounds...", 8, 6, 1f, 0xFF999999, false);
		else if (audio == com.zombiecraft.client.audio.AudioCache.Status.NO_BO2) text(g, font, "Black Ops II not found: using Minecraft sounds (see config/zombiecraft.properties)", 8, 6, 1f, 0xFFCC9944, false);

		if (s.phase() == Payloads.PHASE_GAMEOVER) {
			// BO2: the world goes red (darker at the edges), white text in the upper centre
			g.fill(0, 0, w, h, 0x60A01010);
			g.fillGradient(0, 0, w, h / 3, 0x60300000, 0x00000000);
			g.fillGradient(0, h * 2 / 3, w, h, 0x00000000, 0x80300000);
			text(g, font, "GAME OVER", w / 2, (int) (h * 0.16), 1.9f, 0xFFFFFFFF, true);
			text(g, font, "You Survived " + s.roundsSurvived() + (s.roundsSurvived() == 1 ? " Round" : " Rounds"), w / 2, (int) (h * 0.16) + 26, 1.1f, 0xFFFFFFFF, true);
			scoreboard(g, mc, s);
			return;
		}
		hitMarker(g, mc, partialTick);
		hitDirection(g, mc, partialTick);
		muzzleBloom(g, mc, partialTick);
		crosshair(g, mc);

		// round tallies bottom left (a number once past round 10)
		int tallySize = Math.max(32, h / 6);
		if (!roundTally(g, font, s.round(), w, h, tallySize)) {
			if (s.round() > 10) text(g, font, String.valueOf(s.round()), 14, h - 14 - (int) (9 * 4f * 1.3f), 4f, 0xFFB01010, false);
			else if (s.round() > 0) tally(g, s.round(), 6, h - tallySize - 6, tallySize);
		}

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
		// active power-ups: BO2's own icon with the seconds left, in a row above the perks
		int[] secs = {s.instaSec(), s.doubleSec()};
		String[] puIcons = {"specialty_instakill_zombies", "specialty_doublepoints_zombies"};
		int active = (secs[0] > 0 ? 1 : 0) + (secs[1] > 0 ? 1 : 0), px = w / 2 - active * 22;
		for (int i = 0; i < 2; i++) {
			if (secs[i] <= 0) continue;
			boolean blink = secs[i] <= 5 && (System.currentTimeMillis() / 250) % 2 == 0;
			if (!blink && !UiArt.draw(g, puIcons[i], px, h - 84, 40, 40)) text(g, font, i == 0 ? "INSTA" : "x2", px + 20, h - 80, 1f, 0xFFFFFFFF, true);
			text(g, font, String.valueOf(secs[i]), px + 20, h - 44, 0.8f, 0xFFFFFFFF, true);
			px += 44;
		}

		// banners
		if (!s.message().isEmpty()) text(g, font, s.message(), w / 2, h / 2 + 24, 1.2f, 0xFFFFFFFF, true);

		scoreboard(g, mc, s);

		// prompt (lower middle)
		if (!s.prompt().isEmpty()) prompt(g, s.prompt(), w / 2, h / 2 + 46);
	}
}

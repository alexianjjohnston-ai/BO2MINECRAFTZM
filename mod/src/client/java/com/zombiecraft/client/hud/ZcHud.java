package com.zombiecraft.client.hud;

import com.zombiecraft.client.ZombiecraftClient;
import com.zombiecraft.net.Payloads;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

/** Round counter, points, ammo, prompts and phase banners, drawn from the last StateSync. */
public final class ZcHud {
	private ZcHud() {}

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
		if (mc.player == null || mc.options.hideGui) return;
		Payloads.StateSync s = ZombiecraftClient.state;
		if (s.phase() == Payloads.PHASE_IDLE) return;
		Font font = mc.font;
		int w = g.guiWidth(), h = g.guiHeight();

		// tell the player where the sounds come from
		var audio = com.zombiecraft.client.audio.AudioCache.status;
		if (audio == com.zombiecraft.client.audio.AudioCache.Status.WORKING) text(g, font, "Preparing Black Ops II sounds...", 8, 6, 1f, 0xFF999999, false);
		else if (audio == com.zombiecraft.client.audio.AudioCache.Status.NO_BO2) text(g, font, "Black Ops II not found: using Minecraft sounds (see config/zombiecraft.properties)", 8, 6, 1f, 0xFFCC9944, false);

		if (s.phase() == Payloads.PHASE_GAMEOVER) {
			text(g, font, "GAME OVER", w / 2, h / 2 - 40, 4f, 0xFFCC1111, true);
			text(g, font, "You survived " + s.roundsSurvived() + (s.roundsSurvived() == 1 ? " round" : " rounds"), w / 2, h / 2 + 8, 2f, 0xFFFFFFFF, true);
			return;
		}

		// round counter (bottom left, red) and points
		if (s.round() > 0) text(g, font, String.valueOf(s.round()), 14, h - 78, 5f, 0xFFB01010, false);
		text(g, font, String.valueOf(s.points()), 16, h - 30, 2f, 0xFFFFFFFF, false);

		// ammo (bottom right)
		if (s.mag() >= 0) {
			text(g, font, s.gun(), w - 14 - font.width(s.gun()), h - 52, 1f, 0xFFDDDDDD, false);
			String ammo = s.mag() + " / " + s.reserve();
			text(g, font, ammo, w - 14 - (int) (font.width(ammo) * 2f), h - 40, 2f, s.mag() == 0 ? 0xFFFF4444 : 0xFFFFFFFF, false);
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

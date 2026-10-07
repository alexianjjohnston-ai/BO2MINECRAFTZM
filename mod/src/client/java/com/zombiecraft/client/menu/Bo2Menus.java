package com.zombiecraft.client.menu;

import com.zombiecraft.client.AutoWorld;
import com.zombiecraft.client.audio.MenuAudio;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.GenericMessageScreen;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.options.OptionsScreen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/** BO2-styled title screen, lobby, map select, pause menu and loading screen. Uses the player's own BO2 art when it is cached, plain drawing otherwise. */
public final class Bo2Menus {
	private Bo2Menus() {}

	/** Set when the player ends a game: autoplay skips the title only once, so the title shown after leaving must be the BO2 one. */
	static boolean afterGame;

	static final int ORANGE = 0xFFF08A1C, WHITE = 0xFFF2EEE6, GREY = 0xFF9A958C, YELLOW = 0xFFF5D547, GOLD = 0xFFE0A82E;
	private static final long T0 = System.nanoTime();
	private static long loadStart, loadSeen;
	/** The only map is the Diner, so its postcard is the only loading picture. */
	private static final String LOADSCREEN = "loadscreen_transit_classic";

	public static void register() {
		Bo2Online.register();
		boolean autoplay = Boolean.getBoolean("zombiecraft.autoplay");
		ScreenEvents.AFTER_INIT.register((mc, screen, w, h) -> {
			String dbg = System.getProperty("zombiecraft.debugOptions");
				if (screen instanceof TitleScreen && dbg != null) {
					// dev: -Dzombiecraft.debugOptions=title|root|settings|controls opens that Options page straight away
					mc.execute(() -> {
						Screen t = new Title(), root = new Bo2Options.Root(t);
						mc.setScreen(switch (dbg) { case "settings" -> new Bo2Options.Settings(root); case "controls" -> new Bo2Options.Controls(root); case "title" -> t; case "quit" -> new QuitDialog(t); default -> root; });
					});
				} else if (screen instanceof TitleScreen && (!autoplay || afterGame)) {
				mc.execute(() -> mc.setScreen(new Title()));
			} else if (screen instanceof PauseScreen ps && ps.showsPauseMenu()) {
				mc.execute(() -> mc.setScreen(new Pause()));
			}
		});
	}

	/** Leaves the running game and returns to the BO2 title (the vanilla one would show with autoplay on). */
	public static void endGame() {
		Minecraft mc = Minecraft.getInstance();
		boolean local = mc.isLocalServer();
		if (mc.level != null) mc.level.disconnect();
		if (local) mc.disconnect(new GenericMessageScreen(Component.translatable("menu.savingLevel")));
		else mc.disconnect();
		afterGame = true;
		mc.setScreen(new TitleScreen());
	}

	private static float seconds() { return (System.nanoTime() - T0) / 1e9f; }

	/** {@code scale} 1 is about Minecraft's text size; the BO2 font is drawn a little bigger to read the same. */
	static float H(float scale) { return 9f * scale * 1.3f; }
	static void raw(GuiGraphics g, String s, int x, int y, float scale, int color) { UiFont.draw(g, s, x, y, H(scale), color, true); }

	/** Small grey gradient behind light text so it stays readable over bright art: solid on the left, fading out to the right. */
	static void plate(GuiGraphics g, int x, int y, int w, int h) {
		int fade = Math.min(w / 2, 28);
		g.fill(x, y, x + w - fade, y + h, 0x78242424);
		for (int i = 0; i < fade; i++) g.fill(x + w - fade + i, y, x + w - fade + i + 1, y + h, ((0x78 * (fade - i) / fade) << 24) | 0x242424);
	}

	private static boolean light(int c) { return ((c >> 16 & 255) + (c >> 8 & 255) + (c & 255)) / 3 >= 140; }

	static void text(GuiGraphics g, String s, int x, int y, float scale, int color) {
		if (light(color)) plate(g, x - 6, y, tw(s, scale) + 22, (int) H(scale) + 2);
		raw(g, s, x, y, scale, color);
	}
	static int tw(String s, float scale) { return UiFont.width(s, H(scale)); }

	static List<String> wrap(String s, int maxWidth, float scale) {
		List<String> out = new ArrayList<>();
		StringBuilder line = new StringBuilder();
		for (String word : s.split(" ")) {
			String t = line.isEmpty() ? word : line + " " + word;
			if (tw(t, scale) > maxWidth && !line.isEmpty()) { out.add(line.toString()); line = new StringBuilder(word); }
			else line = new StringBuilder(t);
		}
		if (!line.isEmpty()) out.add(line.toString());
		return out;
	}

	/** "ESC Back": a gold key name and its white label. Returns the width used. */
	static int hint(GuiGraphics g, String key, String label, int x, int y) {
		int kw = tw(key, 1.0f) + 5, total = kw + tw(label, 1.0f);
		plate(g, x - 6, y, total + 22, (int) H(1.0f) + 2);
		raw(g, key, x, y, 1.0f, GOLD);
		raw(g, label, x + kw, y, 1.0f, WHITE);
		return total + 16;
	}

	/** Dark asteroid field with a warm glow, like the BO2 Zombies menu. */
	static void background(GuiGraphics g, int w, int h) {
		UiArt.ensure();
		if (UiArt.cover(g, "lui_bkg_zm", w, h)) {
			// two drifting rock strips, far one slower
			float t = seconds();
			int sh = w / 4;
			for (int layer = 0; layer < 2; layer++) {
				int off = (int) ((t * (layer == 0 ? 4 : 9)) % w);
				for (int k = -1; k <= 0; k++) UiArt.draw(g, layer == 0 ? "lui_bkg_zm_rocks_back" : "lui_bkg_zm_rocks_front", k * w + off, (int) (h * 0.5) - sh / 2, w, sh);
			}
			return;
		}
		g.fillGradient(0, 0, w, h, 0xFF1C140D, 0xFF050403);
		int cx = (int) (w * 0.38), cy = (int) (h * 0.55);
		for (int r = 200; r >= 10; r -= 10) g.fill(cx - r * 3, cy - r / 3, cx + r * 3, cy + r / 3, (7 << 24) | 0xFFB347);
		Random rnd = new Random(7);
		for (int i = 0; i < 160; i++) {
			int x = rnd.nextInt(w), y = rnd.nextInt(h), s = 1 + rnd.nextInt(4);
			g.fill(x, y, x + s, y + s, rnd.nextInt(5) == 0 ? 0x33FFFFFF : 0xB0000000);
		}
	}

	/** The cracked lava planet as a rotating sphere: the world map is sampled one pixel row at a time and squeezed to the chord width. */
	static void planet(GuiGraphics g, int cx, int cy, int r, float turns) {
		String map = "globe_map_zm";
		boolean art = UiArt.has(map);
		int tw = UiArt.w(map), th = UiArt.h(map);
		for (int dy = -r; dy < r; dy++) {
			double phi = Math.asin((dy + 0.5) / r);
			int cw = (int) (r * Math.cos(phi));
			if (cw <= 0) continue;
			int y = cy + dy;
			g.fill(cx - cw, y, cx + cw, y + 1, 0xFF090807);
			if (art) UiArt.strip(g, map, cx - cw, y, 2 * cw, 1, (int) (turns * tw) - tw / 4, Math.min(th - 1, (int) ((0.5 - phi / Math.PI) * th)), tw / 2, 1, 0xFFFFFFFF);
			int edge = Math.max(2, cw / 14);
			g.fill(cx - cw, y, cx - cw + edge, y + 1, 0x70000000);
			g.fill(cx + cw - edge, y, cx + cw, y + 1, 0x70000000);
		}
	}

	/** Called in place of the vanilla world-loading screens' own drawing. */
	/** True while the BO2 loading screen was drawn within the last half second. */
	public static boolean loadingShown() { return System.nanoTime() - loadSeen < 500_000_000L; }

	public static void loading(GuiGraphics g, int w, int h) {
		MenuAudio.loadingMusic();
		long now = System.nanoTime();
		if (now - loadSeen > 2_000_000_000L) loadStart = now;
		loadSeen = now;
		float t = (now - loadStart) / 1e9f;
		boolean art = UiArt.cover(g, LOADSCREEN, w, h);
		if (art) g.fillGradient(0, 0, w, 60, 0xB0000000, 0x00000000);
		else background(g, w, h);
		text(g, "GREEN RUN", 14, 12, 1.2f, ORANGE);
		text(g, "NORTHERN HEMISPHERE", 14, 28, 0.8f, GREY);
		text(g, "SURVIVAL", 14, 40, 0.8f, GREY);
		if (!art) postcard(g, w, h);
		// progress line + status
		g.fill(14, h - 26, w - 14, h - 25, 0x40FFFFFF);
		g.fill(14, h - 26, 14 + (int) ((w - 28) * (1 - Math.exp(-t / 2.5))), h - 25, ORANGE);
		text(g, "Awaiting challenge...", 14, h - 20, 0.8f, GREY);
	}

	/** Plain-drawn postcard for installs without BO2 art. */
	private static void postcard(GuiGraphics g, int w, int h) {
		int cw = Math.min(w - 80, 300), ch = cw * 5 / 8, x = (w - cw) / 2, y = (h - ch) / 2 - 10;
		g.fill(x - 3, y - 3, x + cw + 3, y + ch + 3, 0xFF000000);
		g.fill(x, y, x + cw, y + ch, 0xFFD9CDB0);
		g.fill(x + cw / 10, y + ch / 4, x + cw * 7 / 10, y + ch * 3 / 5, 0xFF2D4A78);
		g.fill(x + cw / 10, y + ch * 2 / 5, x + cw * 7 / 10, y + ch * 2 / 5 + 3, 0xFFE8E4D8);
		g.fill(x + cw * 7 / 10, y + ch / 4 - 8, x + cw * 7 / 10 + 8, y + ch * 3 / 5, 0xFF1D2F4C);
		Random rnd = new Random(3);
		for (int i = 0; i < 9; i++) {
			int bx = x + rnd.nextInt(cw), by = y + rnd.nextInt(ch), s = 2 + rnd.nextInt(5);
			g.fill(bx, by, bx + s, by + s, 0xFF7A0C0C);
		}
		g.drawString(Minecraft.getInstance().font, Component.literal("Greetings!").withStyle(ChatFormatting.ITALIC), x + cw / 8, y + ch * 2 / 3, 0xFF8A3A1A, false);
	}

	/** Shared menu behaviour: a vertical list of text items with an orange bracket on the selected one. */
	abstract static class MenuScreen extends Screen {
		String[] items;
		int sel, x, y0, lastSel = -1;
		float scale = 1.7f;

		MenuScreen(String title) { super(Component.literal(title)); }

		abstract void activate(int i);

		int step() { return (int) (H(scale) * 1.2f); }

		void drawItems(GuiGraphics g, int mx, int my) {
			for (int i = 0; i < items.length; i++) {
				int y = y0 + i * step(), w = tw(items[i], scale);
				if (mx >= x - 6 && mx <= x + w + 6 && my >= y - 3 && my <= y + step() - 3) sel = i;
				boolean on = i == sel;
				if (on) g.renderOutline(x - 6, y - 3, w + 12, step() - 2, ORANGE);
				text(g, items[i], x, y, scale, on ? ORANGE : WHITE);
			}
			if (sel != lastSel) { if (lastSel >= 0) MenuAudio.play("uin_main_nav"); lastSel = sel; }
		}

		@Override public boolean mouseClicked(double mx, double my, int button) {
			if (button == 0) { activate(sel); return true; }
			return false;
		}

		@Override public boolean keyPressed(int key, int scan, int mods) {
			if (key == GLFW.GLFW_KEY_DOWN) sel = (sel + 1) % items.length;
			else if (key == GLFW.GLFW_KEY_UP) sel = (sel + items.length - 1) % items.length;
			else if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) activate(sel);
			else return super.keyPressed(key, scan, mods);
			return true;
		}

		@Override public boolean shouldCloseOnEsc() { return false; }
		@Override public void renderBackground(GuiGraphics g, int mx, int my, float dt) {}
	}

	static final class Title extends MenuScreen {
		Title() { super("Block Ops 2"); items = new String[] {"PLAY", "OPTIONS", "QUIT"}; }

		@Override protected void init() { x = (int) (width * 0.16); y0 = (int) (height * 0.58); }

		@Override void activate(int i) {
			Minecraft mc = Minecraft.getInstance();
			switch (i) {
				case 0 -> { MenuAudio.play("uin_lobby_join"); mc.setScreen(new Lobby(this)); }
				case 1 -> mc.setScreen(new Bo2Options.Root(this));
				default -> mc.stop();
			}
		}

		@Override public void render(GuiGraphics g, int mx, int my, float dt) {
			MenuAudio.music();
			background(g, width, height);
			int lx = (int) (width * 0.16), ly = (int) (height * 0.2), lw = (int) (width * 0.42);
			if (!UiArt.draw(g, "menu_zm_title_screen", lx - lw / 14, ly - lw / 5, lw, lw / 2)) {
				text(g, "BLACK OPS II", lx, ly, 3.0f, WHITE);
				text(g, "ZOMBIES", lx, ly + 36, 4.0f, 0xFFB9B2A6);
			}
			drawItems(g, mx, my);
			text(g, "BLOCK OPS 2  0.1", width - 14 - tw("BLOCK OPS 2  0.1", 0.8f), 8, 0.8f, GREY);
			if (UiArt.busy()) text(g, "Preparing Black Ops II art...", 14, height - 26 - (int) H(1.0f) - (int) H(0.8f) - 10, 0.8f, GREY);
			hint(g, "ENTER", "Select", 14, height - 26);
		}
	}

	static final class Lobby extends MenuScreen {
		private final Screen parent;

		Lobby(Screen parent) {
			super("Zombies");
			this.parent = parent;
			items = new String[] {"SOLO PLAY", "HOST ONLINE GAME", "JOIN GAME", "OPTIONS"};
		}

		@Override protected void init() { x = (int) (width * 0.16); y0 = (int) (height * 0.07) + (int) H(2.6f) + 12; }

		@Override void activate(int i) {
			Minecraft mc = Minecraft.getInstance();
			if (i == 0 || i == 1) { MenuAudio.play("zmb_ui_globe_spin_start"); mc.setScreen(new MapSelect(this, i == 1)); }
			else if (i == 2) { MenuAudio.play("uin_lobby_join"); mc.setScreen(new Bo2Online.Join(this)); }
			else mc.setScreen(new Bo2Options.Root(this));
		}

		@Override public boolean keyPressed(int key, int scan, int mods) {
			if (key == GLFW.GLFW_KEY_ESCAPE) { MenuAudio.play("uin_cmn_backout"); Minecraft.getInstance().setScreen(parent); return true; }
			return super.keyPressed(key, scan, mods);
		}

		@Override public void render(GuiGraphics g, int mx, int my, float dt) {
			MenuAudio.music();
			background(g, width, height);
			planet(g, -(int) (width * 0.06), (int) (height * 0.82), (int) (height * 0.5), seconds() / 90f);
			text(g, "ZOMBIES", x, (int) (height * 0.07), 2.6f, WHITE);
			drawItems(g, mx, my);
			String desc = switch (sel) {
				case 0 -> "Survive unending waves of the undead. Earn points, buy weapons and see how many rounds you can last.";
				case 1 -> "Start a game and open it to friends online. You will be shown what to forward with playit.gg.";
				case 2 -> "Join a friend's game with the address they got from playit.gg.";
				default -> "Change video, audio and controls.";
			};
			int ty = y0 + items.length * step() + 14;
			for (int k = 0; k < 4; k++) g.fill(x - 2, ty + 2 + k, x + 1 + k, ty + 3 + k, WHITE);
			for (int k = 0; k < 3; k++) g.fill(x - 2, ty + 9 - k, x + 1 + k, ty + 10 - k, WHITE);
			for (String line : wrap(desc, (int) (width * 0.3), 0.9f)) {
				text(g, line, x + 12, ty, 0.9f, 0xFFD2CEC6);
				ty += (int) (H(0.9f) * 1.15f);
			}
			int rx = (int) (width * 0.56), ry = (int) (height * 0.12);
			text(g, "Players (4 Max)", rx, ry, 1.0f, WHITE);
			g.fill(rx - 4, ry + 20, width - 40, ry + 21, 0x50FFFFFF);
			g.fill(rx, ry + 26, rx + 10, ry + 36, 0xFF55606A);
			text(g, Minecraft.getInstance().getUser().getName(), rx + 16, ry + 24, 1.0f, YELLOW);
			hint(g, "ESC", "Back", x, height - 26);
			text(g, "BLOCK OPS 2  0.1", width - 14 - tw("BLOCK OPS 2  0.1", 0.8f), 8, 0.8f, GREY);
		}
	}

	/** Map select: the lava planet with one marker per map. Only Green Run is playable for now. */
	static final class MapSelect extends Screen {
		private static final String[] NAMES = {"GREEN RUN", "NUKETOWN ZOMBIES", "DIE RISE", "MOB OF THE DEAD", "BURIED", "ORIGINS"};
		/** Marker centre as a fraction of the planet radius from its centre. */
		private static final double[][] POS = {{-0.12, -0.02}, {0.42, 0.18}, {-0.5, -0.45}, {0.22, -0.55}, {-0.38, 0.5}, {0.1, 0.62}};
		private final Screen parent;
		private final boolean host;
		private int sel, lastSel = -1;

		MapSelect(Screen parent, boolean host) { super(Component.literal("Select map")); this.parent = parent; this.host = host; }

		private int radius() { return (int) (height * 0.36); }
		private int markerSize(int i) { return (int) (radius() * (i == 0 ? 0.3 : i == 1 ? 0.2 : 0.13)); }
		private int mx(int i) { return (int) (width / 2 + POS[i][0] * radius()); }
		private int my(int i) { return (int) (height / 2 + POS[i][1] * radius()); }

		private void pick() {
			if (sel == 0) { MenuAudio.play("zmb_ui_map_level_select"); if (host) Bo2Online.hostNext(); AutoWorld.start(Minecraft.getInstance(), this); }
			else MenuAudio.play("cac_cmn_deny");
		}

		@Override public boolean mouseClicked(double x, double y, int button) {
			if (button == 0) { pick(); return true; }
			return false;
		}

		@Override public boolean keyPressed(int key, int scan, int mods) {
			if (key == GLFW.GLFW_KEY_ESCAPE) { MenuAudio.play("uin_cmn_backout"); Minecraft.getInstance().setScreen(parent); }
			else if (key == GLFW.GLFW_KEY_RIGHT || key == GLFW.GLFW_KEY_DOWN) sel = (sel + 1) % NAMES.length;
			else if (key == GLFW.GLFW_KEY_LEFT || key == GLFW.GLFW_KEY_UP) sel = (sel + NAMES.length - 1) % NAMES.length;
			else if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) pick();
			else return super.keyPressed(key, scan, mods);
			return true;
		}

		@Override public boolean shouldCloseOnEsc() { return false; }
		@Override public void renderBackground(GuiGraphics g, int mx, int my, float dt) {}

		@Override public void render(GuiGraphics g, int mouseX, int mouseY, float dt) {
			MenuAudio.music();
			background(g, width, height);
			planet(g, width / 2, height / 2, radius(), seconds() / 80f);
			for (int i = 0; i < NAMES.length; i++) {
				int s = markerSize(i), x = mx(i) - s / 2, y = my(i) - s / 2;
				if (mouseX >= x && mouseX <= x + s && mouseY >= y && mouseY <= y + s) sel = i;
				String icon = i == 0 ? "menu_zm_map_signpost_transit" : i == 1 ? "menu_zm_map_signpost_nuketown" : "pc_lock";
				UiArt.draw(g, icon, x, y, s, s, i == 0 ? 0xFFFFFFFF : 0x90FFFFFF);
				if (i == sel) g.renderOutline(x - 3, y - 3, s + 6, s + 6, ORANGE);
			}
			if (sel != lastSel) { if (lastSel >= 0) MenuAudio.play("zmb_ui_map_level_switch"); lastSel = sel; }
			text(g, NAMES[sel], 24, height - 78, 2.2f, sel == 0 ? WHITE : GREY);
			if (sel != 0) text(g, "COMING SOON", 24, height - 46, 1.0f, GREY);
			hint(g, "ESC", "Back", 24, height - 26);
			if (sel == 0) hint(g, "ENTER", "Select", width - 24 - tw("ENTER", 1.0f) - tw("Select", 1.0f) - 10, height - 26);
		}
	}

	/** In-game pause menu in the same style. */
	static final class Pause extends MenuScreen {
		Pause() {
			super("Paused");
			Minecraft mc = Minecraft.getInstance();
			items = Bo2Online.hosting(mc) ? new String[] {"RESUME GAME", "INVITE INFO", "OPTIONS", "END GAME"}
					: new String[] {"RESUME GAME", "OPTIONS", mc.isLocalServer() ? "END GAME" : "LEAVE GAME"};
		}

		@Override protected void init() { x = (int) (width * 0.1); y0 = (int) (height * 0.3); MenuAudio.play("uin_main_pause"); }

		@Override void activate(int i) {
			Minecraft mc = Minecraft.getInstance();
			switch (items[i]) {
				case "RESUME GAME" -> mc.setScreen(null);
				case "INVITE INFO" -> mc.setScreen(new Bo2Online.Invite());
				case "OPTIONS" -> mc.setScreen(new Bo2Options.Root(this));
				default -> mc.setScreen(new QuitDialog(this));
			}
		}

		@Override public boolean shouldCloseOnEsc() { return true; }
		@Override public boolean isPauseScreen() { return true; }

		@Override public void render(GuiGraphics g, int mx, int my, float dt) {
			g.fillGradient(0, 0, width, height, 0x90000000, 0xB0000000);
			g.fill(0, 0, (int) (width * 0.36), height, 0x70000000);
			text(g, "PAUSED", x, (int) (height * 0.14), 2.6f, WHITE);
			drawItems(g, mx, my);
			hint(g, "ESC", "Resume", x, height - 26);
			// the controls, shown here instead of as a banner over the game
			int cx = (int) (width * 0.58), cy = y0, line = (int) (H(1.0f) * 1.5f);
			text(g, "CONTROLS", cx, cy - line - 6, 1.3f, WHITE);
			String[][] keys = {{"RIGHT CLICK", "Shoot"}, {"R", "Reload"}, {"LEFT CLICK", "Knife"}, {"F", "Buy / Open / Hold to repair or revive"}, {"SHIFT", "Crouch"}, {"Z", "Prone"}};
			for (String[] k : keys) { hint(g, k[0], k[1], cx, cy); cy += line; }
		}
	}

	/** BO2's "Quit Game" confirmation: framed box, Yes / No (No is the default). */
	static final class QuitDialog extends MenuScreen {
		private final Screen parent;

		QuitDialog(Screen parent) { super("Quit Game"); this.parent = parent; items = new String[] {"Yes", "No"}; sel = 1; scale = 1.1f; }

		@Override void activate(int i) {
			Minecraft mc = Minecraft.getInstance();
			if (i == 1) { MenuAudio.play("uin_cmn_backout"); mc.setScreen(parent); return; }
			endGame();
		}

		@Override public boolean keyPressed(int key, int scan, int mods) {
			if (key == GLFW.GLFW_KEY_ESCAPE) { activate(1); return true; }
			return super.keyPressed(key, scan, mods);
		}

		@Override public boolean isPauseScreen() { return true; }

		@Override public void render(GuiGraphics g, int mx, int my, float dt) {
			g.fillGradient(0, 0, width, height, 0xA0000000, 0xC0000000);
			// the box is sized to its content so the answers can never land on the text
			int bw = Math.max((int) (width * 0.34), 300), pad = 16;
			List<String> lines = wrap("If you leave, you will lose all progress. Are you sure you want to leave the game?", bw - 2 * pad - 8, 0.75f);
			int titleH = (int) H(1.5f), lineH = (int) (H(0.75f) * 1.25f);
			int bh = pad + titleH + 10 + lines.size() * lineH + 16 + 2 * step() + pad;
			int bx = (width - bw) / 2, by = (height - bh) / 2;
			g.fill(bx - 3, by - 3, bx + bw + 3, by + bh + 3, 0xFF6A645C);
			g.fill(bx, by, bx + bw, by + bh, 0xF0141210);
			text(g, "Quit Game", bx + pad, by + pad, 1.5f, WHITE);
			int ty = by + pad + titleH + 10;
			for (String line : lines) {
				text(g, line, bx + pad, ty, 0.75f, 0xFFD2CEC6);
				ty += lineH;
			}
			x = bx + pad; y0 = ty + 16;
			drawItems(g, mx, my);
			hint(g, "ESC", "Back", bx, by + bh + 12);
		}
	}
}

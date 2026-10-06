package com.zombiecraft.client.menu;

import com.zombiecraft.client.AutoWorld;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.GenericMessageScreen;
import net.minecraft.client.gui.screens.LevelLoadingScreen;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.ProgressScreen;
import net.minecraft.client.gui.screens.ReceivingLevelScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.options.OptionsScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import org.lwjgl.glfw.GLFW;

import java.util.Random;

/** BO2-styled title screen, Zombies lobby and loading screen. Drawn procedurally: no BO2 art is shipped. */
public final class Bo2Menus {
	private Bo2Menus() {}

	static final int ORANGE = 0xFFF08A1C, WHITE = 0xFFF2EEE6, GREY = 0xFF9A958C, YELLOW = 0xFFF5D547;
	private static final long T0 = System.nanoTime();
	private static long loadStart, loadSeen;
	private static final String[] LOADSCREENS = {"loadscreen_transit_standard_town", "loadscreen_transit_standard_busdepot", "loadscreen_transit_standard_farm", "loadscreen_transit_classic"};
	private static String loadscreen = LOADSCREENS[0];

	public static void register() {
		boolean autoplay = Boolean.getBoolean("zombiecraft.autoplay");
		ScreenEvents.AFTER_INIT.register((mc, screen, w, h) -> {
			if (screen instanceof TitleScreen && !autoplay) {
				mc.execute(() -> mc.setScreen(new Title()));
			} else if (screen instanceof PauseScreen ps && ps.showsPauseMenu()) {
				mc.execute(() -> mc.setScreen(new Pause()));
			} else if (screen instanceof LevelLoadingScreen || screen instanceof ReceivingLevelScreen || screen instanceof ProgressScreen) {
				long now = System.nanoTime();
				if (now - loadSeen > 2_000_000_000L) { loadStart = now; loadscreen = LOADSCREENS[new Random().nextInt(LOADSCREENS.length)]; }
				ScreenEvents.afterRender(screen).register((s, g, mx, my, dt) -> loading(g, s.width, s.height));
			}
		});
	}

	static void text(GuiGraphics g, String s, int x, int y, float scale, int color) {
		g.pose().pushPose();
		g.pose().translate(x, y, 0);
		g.pose().scale(scale, scale, 1);
		g.drawString(Minecraft.getInstance().font, s, 0, 0, color, true);
		g.pose().popPose();
	}

	/** Dark asteroid field with a warm glow, like the BO2 Zombies menu. */
	static void background(GuiGraphics g, int w, int h) {
		UiArt.ensure();
		if (UiArt.cover(g, "lui_bkg_zm", w, h)) {
			// two drifting rock strips, far one slower
			float t = (System.nanoTime() - T0) / 1e9f;
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

	private static void loading(GuiGraphics g, int w, int h) {
		long now = System.nanoTime();
		loadSeen = now;
		float t = (now - loadStart) / 1e9f;
		boolean art = UiArt.cover(g, loadscreen, w, h);
		if (art) g.fillGradient(0, 0, w, 60, 0xB0000000, 0x00000000);
		else background(g, w, h);
		text(g, "GREEN RUN", 14, 12, 1.0f, ORANGE);
		text(g, "NORTHERN HEMISPHERE", 14, 24, 0.7f, GREY);
		text(g, "SURVIVAL", 14, 33, 0.7f, GREY);
		if (!art) postcard(g, w, h);
		// progress line + status
		g.fill(14, h - 22, w - 14, h - 21, 0x40FFFFFF);
		g.fill(14, h - 22, 14 + (int) ((w - 28) * (1 - Math.exp(-t / 2.5))), h - 21, ORANGE);
		text(g, "Awaiting challenge...", 14, h - 16, 0.7f, GREY);
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

	/** Shared menu behaviour: a vertical list of text items with an orange [bracket] on the selected one. */
	abstract static class MenuScreen extends Screen {
		String[] items;
		int sel, x, y0;
		static final int STEP = 22;

		MenuScreen(String title) { super(Component.literal(title)); }

		abstract void activate(int i);

		void drawItems(GuiGraphics g, int mx, int my) {
			var font = Minecraft.getInstance().font;
			for (int i = 0; i < items.length; i++) {
				int y = y0 + i * STEP, w = (int) (font.width(items[i]) * 1.6f);
				if (mx >= x - 6 && mx <= x + w + 6 && my >= y - 3 && my <= y + 15) sel = i;
				boolean on = i == sel;
				if (on) g.renderOutline(x - 6, y - 3, w + 12, 19, ORANGE);
				text(g, items[i], x, y, 1.6f, on ? ORANGE : WHITE);
			}
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
		Title() { super("Zombiecraft"); items = new String[] {"PLAY", "OPTIONS", "QUIT"}; }

		@Override protected void init() { x = (int) (width * 0.16); y0 = (int) (height * 0.58); }

		@Override void activate(int i) {
			Minecraft mc = Minecraft.getInstance();
			switch (i) {
				case 0 -> mc.setScreen(new Lobby(this));
				case 1 -> mc.setScreen(new OptionsScreen(this, mc.options));
				default -> mc.stop();
			}
		}

		@Override public void render(GuiGraphics g, int mx, int my, float dt) {
			background(g, width, height);
			int lx = (int) (width * 0.16), ly = (int) (height * 0.2);
			int lw = (int) (width * 0.42);
			if (!UiArt.draw(g, "menu_zm_title_screen", lx - lw / 14, ly - lw / 5, lw, lw / 2)) {
				text(g, "BLACK OPS", lx, ly, 3.0f, WHITE);
				text(g, "II", lx + 3 * Minecraft.getInstance().font.width("BLACK OPS "), ly, 3.0f, ORANGE);
				text(g, "ZOMBIES", lx, ly + 30, 5.0f, 0xFFB9B2A6);
			}
			drawItems(g, mx, my);
			text(g, "ZOMBIECRAFT - unofficial fan project", width - 190, 8, 0.7f, GREY);
			if (UiArt.busy()) text(g, "Preparing Black Ops II art...", 14, height - 14, 0.7f, GREY);
		}
	}

	static final class Lobby extends MenuScreen {
		private final Screen parent;

		Lobby(Screen parent) {
			super("Zombies");
			this.parent = parent;
			items = new String[] {"SOLO PLAY", "OPTIONS"};
		}

		@Override protected void init() { x = (int) (width * 0.16); y0 = (int) (height * 0.26); }

		@Override void activate(int i) {
			Minecraft mc = Minecraft.getInstance();
			if (i == 0) mc.setScreen(new MapSelect(this));
			else mc.setScreen(new OptionsScreen(this, mc.options));
		}

		@Override public boolean keyPressed(int key, int scan, int mods) {
			if (key == GLFW.GLFW_KEY_ESCAPE) { Minecraft.getInstance().setScreen(parent); return true; }
			return super.keyPressed(key, scan, mods);
		}

		@Override public void render(GuiGraphics g, int mx, int my, float dt) {
			var font = Minecraft.getInstance().font;
			background(g, width, height);
			text(g, "ZOMBIES", x, (int) (height * 0.08), 3.0f, WHITE);
			drawItems(g, mx, my);
			String desc = sel == 0 ? "Survive unending waves of the undead. Earn points, buy weapons and see how many rounds you can last."
					: "Change video, audio and controls.";
			int ty = (int) (height * 0.62);
			g.fill(x - 6, ty - 6, x - 4, ty + 40, ORANGE);
			for (FormattedCharSequence line : font.split(Component.literal(desc), (int) (width * 0.36))) {
				g.drawString(font, line, x + 4, ty, WHITE, true);
				ty += 11;
			}
			int rx = (int) (width * 0.55), ry = (int) (height * 0.14);
			g.drawString(font, "1 Player (4 Max)", rx, ry, WHITE, true);
			g.fill(rx - 4, ry + 14, width - 40, ry + 32, 0x40FFFFFF);
			g.drawString(font, "x " + Minecraft.getInstance().getUser().getName(), rx + 4, ry + 19, YELLOW, true);
			g.fill(14, height - 22, 22, height - 14, 0xFFC8201C);
			g.drawString(font, "Back (Esc)", 28, height - 22, WHITE, true);
		}
	}

	/** Map select: only Green Run is playable for now. */
	static final class MapSelect extends Screen {
		private static final String[] NAMES = {"GREEN RUN", "NUKETOWN ZOMBIES", "DIE RISE", "MOB OF THE DEAD", "BURIED", "ORIGINS"};
		private final Screen parent;
		private int sel;

		MapSelect(Screen parent) { super(Component.literal("Select map")); this.parent = parent; }

		private int cardSize() { return Math.min((int) (width * 0.14), 130); }
		private int cardX(int i) { int cs = cardSize(), gap = cs / 8; return (width - (NAMES.length * cs + (NAMES.length - 1) * gap)) / 2 + i * (cs + gap); }
		private int cardY() { return (int) (height * 0.28); }

		private void start() { if (sel == 0) AutoWorld.start(Minecraft.getInstance(), this); }

		@Override public boolean mouseClicked(double mx, double my, int button) {
			if (button == 0 && sel == 0) { start(); return true; }
			return false;
		}

		@Override public boolean keyPressed(int key, int scan, int mods) {
			if (key == GLFW.GLFW_KEY_ESCAPE) Minecraft.getInstance().setScreen(parent);
			else if (key == GLFW.GLFW_KEY_RIGHT) sel = (sel + 1) % NAMES.length;
			else if (key == GLFW.GLFW_KEY_LEFT) sel = (sel + NAMES.length - 1) % NAMES.length;
			else if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) start();
			else return super.keyPressed(key, scan, mods);
			return true;
		}

		@Override public boolean shouldCloseOnEsc() { return false; }
		@Override public void renderBackground(GuiGraphics g, int mx, int my, float dt) {}

		@Override public void render(GuiGraphics g, int mx, int my, float dt) {
			var font = Minecraft.getInstance().font;
			background(g, width, height);
			text(g, "SELECT MAP", (int) (width * 0.1), (int) (height * 0.08), 3.0f, WHITE);
			int cs = cardSize(), y = cardY();
			for (int i = 0; i < NAMES.length; i++) {
				int x = cardX(i);
				if (mx >= x && mx <= x + cs && my >= y && my <= y + cs) sel = i;
				boolean open = i == 0;
				int inset = cs / 14;
				if (open && !UiArt.draw(g, "menu_zm_tranzit_map_select_final", x + inset, y + inset, cs - 2 * inset, cs - 2 * inset)) g.fill(x + inset, y + inset, x + cs - inset, y + cs - inset, 0xFF2D4A78);
				if (!open) {
					g.fill(x + inset, y + inset, x + cs - inset, y + cs - inset, 0xFF0C0A08);
					if (i == 1) UiArt.draw(g, "menu_zm_map_signpost_nuketown", x + cs / 4, y + cs / 4, cs / 2, cs / 2, 0x60FFFFFF);
					UiArt.draw(g, "pc_lock", x + cs / 2 - 12, y + cs / 2 - 12, 24, 24);
				}
				UiArt.draw(g, "menu_zm_map_frame", x, y, cs, cs);
				if (i == sel) g.renderOutline(x - 2, y - 2, cs + 4, cs + 4, ORANGE);
			}
			boolean open = sel == 0;
			int cx = width / 2, ty = y + cs + 18;
			g.fill(0, ty - 6, width, ty + 38, 0xA0000000);
			String name = NAMES[sel];
			text(g, name, cx - (int) (font.width(name) * 2.0f / 2), ty, 2.0f, open ? ORANGE : GREY);
			String sub = open ? "Transit - Survive the diner. Press Enter or click to start." : "COMING SOON";
			text(g, sub, cx - (int) (font.width(sub) * 1.0f / 2), ty + 24, 1.0f, open ? WHITE : GREY);
			g.fill(14, height - 22, 22, height - 14, 0xFFC8201C);
			g.drawString(font, "Back (Esc)", 28, height - 22, WHITE, true);
		}
	}

	/** In-game pause menu in the same style. */
	static final class Pause extends MenuScreen {
		Pause() { super("Paused"); items = new String[] {"RESUME GAME", "OPTIONS", "LEAVE GAME"}; }

		@Override protected void init() { x = (int) (width * 0.1); y0 = (int) (height * 0.3); }

		@Override void activate(int i) {
			Minecraft mc = Minecraft.getInstance();
			switch (i) {
				case 0 -> mc.setScreen(null);
				case 1 -> mc.setScreen(new OptionsScreen(this, mc.options));
				default -> {
					boolean local = mc.isLocalServer();
					mc.level.disconnect();
					if (local) mc.disconnect(new GenericMessageScreen(Component.translatable("menu.savingLevel")));
					else mc.disconnect();
					mc.setScreen(new TitleScreen());
				}
			}
		}

		@Override public boolean shouldCloseOnEsc() { return true; }
		@Override public boolean isPauseScreen() { return true; }

		@Override public void render(GuiGraphics g, int mx, int my, float dt) {
			g.fillGradient(0, 0, width, height, 0x90000000, 0xB0000000);
			g.fill(0, 0, (int) (width * 0.36), height, 0x70000000);
			text(g, "PAUSED", x, (int) (height * 0.14), 3.0f, WHITE);
			drawItems(g, mx, my);
		}
	}
}

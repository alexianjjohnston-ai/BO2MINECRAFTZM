package com.zombiecraft.client.menu;

import com.zombiecraft.client.AutoWorld;
import com.zombiecraft.client.audio.MenuAudio;
import com.zombiecraft.map.ImportedMap;
import com.zombiecraft.sheet.Sheets;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.util.List;

/**
 * The BO2 map flow after the planet: the top-down view of Tranzit with its five places (pick one and the rest blurs, its photo and the
 * mode list show), then the match lobby ("BUS DEPOT / SURVIVAL", START MATCH). Art comes from the player's own BO2 install.
 */
final class Bo2Locations {
	private Bo2Locations() {}

	static final String MAP_IMG = "menu_zm_map_transit_large";
	/** The part of the 2048 square that BO2 shows, stretched over the whole screen (the five places sit inside it). */
	private static final int CU = 486, CV = 455, CW = 1010, CH = 1059;

	record Mode(String name, String desc, boolean playable) {}

	/** fx, fy: where the place sits on the stretched top-down view (fractions of the screen). */
	record Loc(String id, String name, double fx, double fy, String loadscreen, List<Mode> modes) {
		String blit() { return "menu_zm_map_transit_blit_" + id; }
	}

	static final Loc[] LOCS = {
			new Loc("depot", "BUS DEPOT", 0.275, 0.265, "loadscreen_transit_standard_busdepot", List.of(
					new Mode("TRANZIT", "Continue the fight against the undead and search for clues to the truth of what lies ahead...", false),
					new Mode("SURVIVAL", "Survive at the Bus Depot with limited wall weapons, perks, and the Mystery Box.", true))),
			new Loc("town", "TOWN", 0.51, 0.40, "loadscreen_transit_standard_town", List.of(
					new Mode("SURVIVAL", "Survive in Town with various perks, wall weapons, and the Pack-a-Punch.", false))),
			new Loc("power", "POWER STATION", 0.72, 0.53, "loadscreen_transit_classic", List.of(
					new Mode("SURVIVAL", "Survive at the Power Station with limited wall weapons, perks, and the Mystery Box.", false))),
			new Loc("diner", "DINER", 0.29, 0.68, "loadscreen_transit_classic", List.of(
					new Mode("SURVIVAL", "Survive at the Diner with limited wall weapons, perks, and the Mystery Box.", false))),
			new Loc("farm", "FARM", 0.68, 0.72, "loadscreen_transit_standard_farm", List.of(
					new Mode("SURVIVAL", "Survive in the Farm with limited wall weapons, perks, and the Mystery Box.", false))),
	};

	/** Sheet id of the map a place plays on ("" = the built-in Bus Depot). The converted Tranzit Reimagined depot is used when the player has extracted it. */
	static String sheetMap(Loc l) { return ImportedMap.available("depot") ? "tranzit_depot" : ""; }

	/** The top-down view; blurred (offset copies, dimmed) when something is focused, like BO2. */
	static void topDown(GuiGraphics g, int w, int h, boolean blur) {
		UiArt.ensure();
		g.fill(0, 0, w, h, 0xFF000000);
		if (!UiArt.has(MAP_IMG)) { Bo2Menus.background(g, w, h); return; }
		if (!blur) { UiArt.strip(g, MAP_IMG, 0, 0, w, h, CU, CV, CW, CH, 0xFFFFFFFF); return; }
		int r = Math.max(3, w / 160);
		int[][] d = {{-r, 0}, {r, 0}, {0, -r}, {0, r}, {-r, -r}, {r, r}, {-r, r}, {r, -r}, {0, 0}};
		for (int[] o : d) UiArt.strip(g, MAP_IMG, o[0], o[1], w, h, CU, CV, CW, CH, 0x38FFFFFF);
		g.fill(0, 0, w, h, 0x90000000);
	}

	/** The small white arrow BO2 puts in front of descriptions. */
	private static void arrow(GuiGraphics g, int x, int y) {
		for (int k = 0; k < 4; k++) g.fill(x + k, y + k, x + k + 1, y + 8 - k, 0xFFD2CEC6);
	}

	/** Dev: -Dzombiecraft.debugOptions=locations|match opens these straight away and screenshots after a few seconds. */
	static Screen dev(String which, Screen title) {
		Screen planet = new Bo2Menus.MapSelect(title, false);
		int sel = Integer.getInteger("zombiecraft.debugSel", -1);
		Select s = new Select(planet, false);
		s.loc = sel;
		Screen out = which.equals("match") ? new Match(s, false, LOCS[Math.max(0, sel)], LOCS[Math.max(0, sel)].modes().get(LOCS[Math.max(0, sel)].modes().size() - 1)) : s;
		Thread t = new Thread(() -> {
			try { Thread.sleep(4000); } catch (InterruptedException ignored) {}
			Minecraft mc = Minecraft.getInstance();
			mc.execute(() -> net.minecraft.client.Screenshot.grab(mc.gameDirectory, "zc-" + which + (sel >= 0 ? sel : "") + ".png", mc.getMainRenderTarget(), c -> {}));
		}, "zc-dev-shot");
		t.setDaemon(true);
		t.start();
		return out;
	}

	// ------------------------------------------------------------------ top-down view
	static final class Select extends Screen {
		private final Screen parent;
		private final boolean host;
		int loc = -1;
		private int mode, lastLoc = -2, lastMode = -1;
		private final int[][] rows = new int[2][4];
		private int px, py, pw, ph, tx, ty, tw;

		Select(Screen parent, boolean host) { super(Component.literal("Select place")); this.parent = parent; this.host = host; }

		@Override public boolean shouldCloseOnEsc() { return false; }
		@Override public void renderBackground(GuiGraphics g, int mx, int my, float dt) {}

		private Loc cur() { return loc < 0 ? null : LOCS[loc]; }

		private boolean in(int x, int y, int rx, int ry, int rw, int rh) { return x >= rx && x <= rx + rw && y >= ry && y <= ry + rh; }

		private int hotspotAt(int mx, int my) {
			for (int i = 0; i < LOCS.length; i++)
				if (Math.abs(mx - LOCS[i].fx() * width) < width * 0.09 && Math.abs(my - LOCS[i].fy() * height) < height * 0.13) return i;
			return -1;
		}

		private void confirm() {
			Loc l = cur();
			if (l == null) return;
			Mode m = l.modes().get(Math.min(mode, l.modes().size() - 1));
			if (!m.playable()) { MenuAudio.play("cac_cmn_deny"); return; }
			MenuAudio.play("uin_lobby_join");
			Minecraft.getInstance().setScreen(new Match(this, host, l, m));
		}

		@Override public boolean mouseClicked(double x, double y, int button) {
			if (button != 0) return false;
			Loc l = cur();
			if (l != null) {
				for (int i = 0; i < l.modes().size(); i++)
					if (in((int) x, (int) y, rows[i][0], rows[i][1], rows[i][2], rows[i][3])) { mode = i; confirm(); return true; }
				if (in((int) x, (int) y, px, py, pw, ph)) { confirm(); return true; }
			}
			int h = hotspotAt((int) x, (int) y);
			if (h >= 0) { loc = h; mode = LOCS[h].modes().size() - 1; }
			return true;
		}

		@Override public boolean keyPressed(int key, int scan, int mods) {
			if (key == GLFW.GLFW_KEY_ESCAPE) {
				MenuAudio.play("uin_cmn_backout");
				if (loc >= 0) loc = -1; else Minecraft.getInstance().setScreen(parent);
			} else if (key == GLFW.GLFW_KEY_RIGHT || key == GLFW.GLFW_KEY_LEFT) {
				int d = key == GLFW.GLFW_KEY_RIGHT ? 1 : LOCS.length - 1;
				loc = loc < 0 ? 0 : (loc + d) % LOCS.length;
				mode = LOCS[loc].modes().size() - 1;
			} else if ((key == GLFW.GLFW_KEY_DOWN || key == GLFW.GLFW_KEY_UP) && loc >= 0) {
				int n = LOCS[loc].modes().size();
				mode = (mode + (key == GLFW.GLFW_KEY_DOWN ? 1 : n - 1)) % n;
			} else if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) confirm();
			else return super.keyPressed(key, scan, mods);
			return true;
		}

		@Override public void render(GuiGraphics g, int mouseX, int mouseY, float dt) {
			MenuAudio.music();
			Loc l = cur();
			// moving over another place focuses it; moving over the open panel keeps it
			if (!(l != null && in(mouseX, mouseY, Math.min(px, tx) - 6, py - 6, Math.max(px + pw, tx + tw) - Math.min(px, tx) + 12, ph + 90))) {
				int h = hotspotAt(mouseX, mouseY);
				if (h >= 0 && h != loc) { loc = h; mode = LOCS[h].modes().size() - 1; l = cur(); }
			}
			topDown(g, width, height, l != null);
			if (l != null) {
				pw = (int) (width * 0.27); ph = pw / 2;
				px = Math.max(8, Math.min(width - pw - 8, (int) (l.fx() * width - pw / 2.0)));
				py = Math.max(8, (int) (l.fy() * height - ph / 2.0));
				tw = (int) (width * 0.24);
				tx = l.fx() < 0.58 ? px + pw + 6 : px - tw - 6;
				if (!UiArt.draw(g, l.blit(), px, py, pw, ph)) g.fill(px + 10, py + 10, px + pw - 10, py + ph - 10, 0xFF2A2420);
				int y = py + 8;
				for (int i = 0; i < l.modes().size(); i++) {
					Mode m = l.modes().get(i);
					boolean on = i == mode;
					String nm = m.name();
					int w = Bo2Menus.tw(nm, 1.2f), hh = (int) Bo2Menus.H(1.2f);
					rows[i][0] = tx; rows[i][1] = y - 3; rows[i][2] = w + 12; rows[i][3] = hh + 6;
					if (mouseX >= tx && mouseX <= tx + w + 12 && mouseY >= y - 3 && mouseY <= y + hh + 3) mode = i;
					if (on) g.renderOutline(tx, y - 3, w + 12, hh + 6, Bo2Menus.ORANGE);
					Bo2Menus.raw(g, nm, tx + 6, y, 1.2f, on ? Bo2Menus.ORANGE : Bo2Menus.WHITE);
					if (!m.playable()) UiArt.draw(g, "pc_lock", tx + w + 18, y, hh, hh, 0xB0FFFFFF);
					y += hh + 8;
				}
				Mode m = l.modes().get(Math.min(mode, l.modes().size() - 1));
				arrow(g, tx + 2, y + 3);
				for (String line : Bo2Menus.wrap(m.desc(), tw - 16, 0.85f)) {
					Bo2Menus.raw(g, line, tx + 14, y, 0.85f, 0xFFD2CEC6);
					y += (int) (Bo2Menus.H(0.85f) * 1.15f);
				}
				if (!m.playable()) Bo2Menus.raw(g, "COMING SOON", tx + 14, y + 4, 0.85f, Bo2Menus.GREY);
			}
			if (loc != lastLoc || mode != lastMode) { if (lastLoc != -2) MenuAudio.play("zmb_ui_map_level_switch"); lastLoc = loc; lastMode = mode; }
			Bo2Menus.hint(g, "ESC", "Back", 24, height - 26);
		}
	}

	// ------------------------------------------------------------------ match lobby
	static final class Match extends Screen {
		private final Screen parent;
		private final boolean host;
		private final Loc loc;
		private final Mode mode;
		private final String[] items = {"START MATCH", "MAP"};
		private int sel, lastSel = -1;
		private long startAt;
		private boolean launched;

		Match(Screen parent, boolean host, Loc loc, Mode mode) { super(Component.literal("Match")); this.parent = parent; this.host = host; this.loc = loc; this.mode = mode; }

		@Override public boolean shouldCloseOnEsc() { return false; }
		@Override public void renderBackground(GuiGraphics g, int mx, int my, float dt) {}

		private int x() { return (int) (width * 0.05); }
		private int y0() { return (int) (height * 0.07) + (int) Bo2Menus.H(2.0f) + 14; }
		private int step() { return (int) (Bo2Menus.H(1.25f) * 1.25f); }

		private void activate(int i) {
			if (startAt != 0) return;
			if (i == 0) { startAt = System.currentTimeMillis() + 3000; MenuAudio.play("uin_lobby_join"); }
			else { MenuAudio.play("uin_cmn_backout"); Minecraft.getInstance().setScreen(parent); }
		}

		private void launch() {
			if (launched) return;
			launched = true;
			Bo2Menus.loadScreen = loc.loadscreen();
			Bo2Menus.loadPlace = loc.name();
			Sheets.useMap(sheetMap(loc));
			MenuAudio.play("zmb_ui_map_level_select");
			if (host) Bo2Online.hostNext();
			AutoWorld.start(Minecraft.getInstance(), this);
		}

		@Override public boolean mouseClicked(double mx, double my, int button) {
			if (button == 0) { activate(sel); return true; }
			return false;
		}

		@Override public boolean keyPressed(int key, int scan, int mods) {
			if (key == GLFW.GLFW_KEY_ESCAPE) {
				MenuAudio.play("uin_cmn_backout");
				if (startAt != 0) startAt = 0; else Minecraft.getInstance().setScreen(parent);
			} else if (key == GLFW.GLFW_KEY_DOWN) sel = (sel + 1) % items.length;
			else if (key == GLFW.GLFW_KEY_UP) sel = (sel + items.length - 1) % items.length;
			else if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) activate(sel);
			else return super.keyPressed(key, scan, mods);
			return true;
		}

		@Override public void render(GuiGraphics g, int mx, int my, float dt) {
			MenuAudio.music();
			topDown(g, width, height, true);
			int x = x();
			Bo2Menus.raw(g, loc.name() + " / " + mode.name(), x, (int) (height * 0.07), 2.0f, Bo2Menus.WHITE);
			for (int i = 0; i < items.length; i++) {
				int y = y0() + i * step(), w = Bo2Menus.tw(items[i], 1.25f);
				if (startAt == 0 && mx >= x - 6 && mx <= x + w + 6 && my >= y - 3 && my <= y + step() - 3) sel = i;
				boolean on = i == sel;
				if (on) g.renderOutline(x - 6, y - 3, w + 12, step() - 2, Bo2Menus.ORANGE);
				Bo2Menus.raw(g, items[i], x, y, 1.25f, on ? Bo2Menus.ORANGE : Bo2Menus.WHITE);
			}
			if (sel != lastSel) { if (lastSel >= 0) MenuAudio.play("uin_main_nav"); lastSel = sel; }
			int ty = y0() + items.length * step() + 6;
			arrow(g, x - 2, ty + 3);
			Bo2Menus.raw(g, sel == 0 ? "Begin the game." : "Select game mode and location.", x + 10, ty, 0.85f, 0xFFD2CEC6);

			int rx = (int) (width * 0.53), ry = (int) (height * 0.13);
			Bo2Menus.raw(g, "1 Player (1 Max)", rx, ry, 0.85f, Bo2Menus.WHITE);
			g.fill(rx - 4, ry + (int) Bo2Menus.H(0.85f) + 4, width - 40, ry + (int) Bo2Menus.H(0.85f) + 5, 0x40FFFFFF);
			Bo2Menus.raw(g, Minecraft.getInstance().getUser().getName(), rx + 30, ry + (int) Bo2Menus.H(0.85f) + 8, 0.9f, Bo2Menus.YELLOW);

			// the postcard of the match, with BO2's countdown above it
			int cw = (int) (width * 0.33), ch = (int) (height * 0.27), cx = x - 4, cy = (int) (height * 0.62);
			String cap = "Ready for the match";
			if (startAt != 0) {
				long left = startAt - System.currentTimeMillis();
				if (left <= 0) launch();
				cap = "Game starting in " + Math.max(1, (left + 999) / 1000);
			}
			Bo2Menus.raw(g, cap, cx, cy - (int) Bo2Menus.H(1.0f) - 8, 1.0f, Bo2Menus.WHITE);
			g.fill(cx - 3, cy - 3, cx + cw + 3, cy + ch + 3, 0xFFA8A8A8);
			g.fill(cx, cy, cx + cw, cy + ch, 0xFF000000);
			String img = loc.loadscreen();
			if (UiArt.has(img)) {
				int iw = UiArt.w(img), ih = UiArt.h(img);
				int rw = Math.min(iw, (int) (ih * (double) cw / ch));
				UiArt.strip(g, img, cx + 2, cy + 2, cw - 4, ch - 4, (iw - rw) / 2, 0, rw, ih, 0xFFFFFFFF);
			}
			g.fillGradient(cx + 2, cy + ch - 44, cx + cw - 2, cy + ch - 2, 0x00000000, 0xC0000000);
			String l1 = "GREEN RUN", l2 = loc.name() + " / " + mode.name();
			Bo2Menus.raw(g, l1, cx + cw - 10 - Bo2Menus.tw(l1, 0.8f), cy + ch - 34, 0.8f, Bo2Menus.WHITE);
			Bo2Menus.raw(g, l2, cx + cw - 10 - Bo2Menus.tw(l2, 0.7f), cy + ch - 20, 0.7f, Bo2Menus.WHITE);
			Bo2Menus.hint(g, "ESC", "Back", x, height - 26);
		}
	}
}

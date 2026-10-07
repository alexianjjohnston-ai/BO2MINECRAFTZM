package com.zombiecraft.client.menu;

import com.mojang.blaze3d.platform.InputConstants;
import com.zombiecraft.client.audio.MenuAudio;
import net.minecraft.client.GraphicsStatus;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundSource;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;
import java.util.function.Supplier;

/**
 * BO2's Options screens in place of vanilla's: OPTIONS (Settings / Controls), Settings with Graphics / Sound / Game tabs and Controls
 * with Look / Move / Combat / Interact tabs. Every row reads and writes the real Minecraft option, so nothing here is cosmetic.
 */
final class Bo2Options {
	private Bo2Options() {}

	/** One line of a settings page: a label, the current value as text, and what left/right (or a click) does. */
	private record Row(String label, Supplier<String> value, IntConsumer change) {}

	private static void save() { Minecraft.getInstance().options.save(); }

	private static Row ints(String label, OptionInstance<Integer> o, int min, int max, int step) {
		return new Row(label, () -> String.valueOf(o.get()), d -> { o.set(Math.max(min, Math.min(max, o.get() + d * step))); save(); });
	}

	private static Row pct(String label, OptionInstance<Double> o) {
		return new Row(label, () -> Math.round(o.get() * 100) + "%", d -> { o.set(Math.max(0.0, Math.min(1.0, Math.round((o.get() + d * 0.05) * 100) / 100.0))); save(); });
	}

	private static Row bool(String label, OptionInstance<Boolean> o) {
		return new Row(label, () -> o.get() ? "ENABLED" : "DISABLED", d -> { o.set(!o.get()); save(); });
	}

	private static Row graphics(OptionInstance<GraphicsStatus> o) {
		return new Row("GRAPHICS QUALITY", () -> o.get().toString().toUpperCase(), d -> {
			GraphicsStatus[] v = GraphicsStatus.values();
			o.set(v[(o.get().ordinal() + d + v.length) % v.length]); save();
		});
	}

	private static Row key(KeyMapping km) {
		return new Row(Component.translatable(km.getName()).getString().toUpperCase(), () -> km.getTranslatedKeyMessage().getString().toUpperCase(), d -> {});
	}

	/** Shared look: title, tab bar, rows with an orange bracket on the selected one, key hints. */
	abstract static class Page extends Screen {
		final Screen parent;
		final String[] tabs;
		int tab, sel;
		List<Row> rows = new ArrayList<>();

		Page(String title, Screen parent, String... tabs) { super(Component.literal(title)); this.parent = parent; this.tabs = tabs; }

		abstract List<Row> build(int tab);

		@Override protected void init() { rows = build(tab); sel = Math.min(sel, Math.max(0, rows.size() - 1)); }

		void setTab(int t) {
			tab = (t + tabs.length) % tabs.length; sel = 0; rows = build(tab);
			MenuAudio.play("uin_main_nav");
		}

		void back() { MenuAudio.play("uin_cmn_backout"); save(); Minecraft.getInstance().setScreen(parent); }

		boolean capturing() { return false; }

		int rowTop() { return (int) (height * 0.25); }
		int rowStep() { return (int) (Bo2Menus.H(1.0f) * 1.35f); }
		int labelX() { return (int) (width * 0.06); }
		int valueX() { return (int) (width * 0.46); }

		@Override public boolean keyPressed(int key, int scan, int mods) {
			if (capturing()) return super.keyPressed(key, scan, mods);
			if (key == GLFW.GLFW_KEY_ESCAPE) { back(); return true; }
			if (key == GLFW.GLFW_KEY_DOWN && !rows.isEmpty()) { sel = (sel + 1) % rows.size(); MenuAudio.play("uin_main_nav"); return true; }
			if (key == GLFW.GLFW_KEY_UP && !rows.isEmpty()) { sel = (sel + rows.size() - 1) % rows.size(); MenuAudio.play("uin_main_nav"); return true; }
			if (key == GLFW.GLFW_KEY_Q || key == GLFW.GLFW_KEY_PAGE_UP) { setTab(tab - 1); return true; }
			if (key == GLFW.GLFW_KEY_E || key == GLFW.GLFW_KEY_PAGE_DOWN) { setTab(tab + 1); return true; }
			if (!rows.isEmpty()) {
				if (key == GLFW.GLFW_KEY_LEFT) { rows.get(sel).change().accept(-1); return true; }
				if (key == GLFW.GLFW_KEY_RIGHT) { rows.get(sel).change().accept(1); return true; }
				if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) { activate(sel); return true; }
			}
			return super.keyPressed(key, scan, mods);
		}

		void activate(int i) { rows.get(i).change().accept(1); }

		@Override public boolean mouseClicked(double mx, double my, int button) {
			if (capturing()) return false;
			int ty = (int) (height * 0.13), x = (int) (width * 0.5 - totalTabsWidth() / 2.0);
			for (int i = 0; i < tabs.length; i++) {
				int w = Bo2Menus.tw(tabs[i], 0.9f);
				if (mx >= x - 6 && mx <= x + w + 6 && my >= ty - 3 && my <= ty + Bo2Menus.H(0.9f) + 3) { setTab(i); return true; }
				x += w + 28;
			}
			for (int i = 0; i < rows.size(); i++) {
				int y = rowTop() + i * rowStep();
				if (my >= y - 2 && my <= y + rowStep() - 2 && mx >= labelX() - 6) {
					sel = i;
					if (button == 1) rows.get(i).change().accept(-1); else activate(i);
					return true;
				}
			}
			return false;
		}

		@Override public boolean mouseScrolled(double mx, double my, double sx, double sy) {
			if (capturing() || rows.isEmpty()) return false;
			int y = (int) ((my - rowTop()) / rowStep());
			if (y >= 0 && y < rows.size()) { sel = y; rows.get(y).change().accept(sy > 0 ? 1 : -1); return true; }
			return false;
		}

		int totalTabsWidth() {
			int total = 0;
			for (String t : tabs) total += Bo2Menus.tw(t, 0.9f) + 28;
			return total - 28;
		}

		@Override public boolean shouldCloseOnEsc() { return false; }
		@Override public void renderBackground(GuiGraphics g, int mx, int my, float dt) {}

		@Override public void render(GuiGraphics g, int mx, int my, float dt) {
			Bo2Menus.background(g, width, height);
			g.fill(0, 0, width, height, 0x90000000);
			int tw = Bo2Menus.tw(title.getString(), 2.2f);
			Bo2Menus.raw(g, title.getString(), (width - tw) / 2, (int) (height * 0.04), 2.2f, Bo2Menus.WHITE);
			if (tabs.length > 1) {
				int x = (int) (width * 0.5 - totalTabsWidth() / 2.0), ty = (int) (height * 0.13);
				for (int i = 0; i < tabs.length; i++) {
					int w = Bo2Menus.tw(tabs[i], 0.9f);
					boolean on = i == tab;
					if (on) g.fill(x - 6, ty - 3, x + w + 6, ty + (int) Bo2Menus.H(0.9f) + 3, 0xC0000000);
					Bo2Menus.raw(g, tabs[i], x, ty, 0.9f, on ? Bo2Menus.ORANGE : Bo2Menus.GREY);
					x += w + 28;
				}
			}
			for (int i = 0; i < rows.size(); i++) {
				int y = rowTop() + i * rowStep();
				Row r = rows.get(i);
				boolean on = i == sel;
				if (on) g.renderOutline(labelX() - 6, y - 3, (int) (width * 0.88) - labelX(), rowStep() - 2, Bo2Menus.ORANGE);
				Bo2Menus.raw(g, r.label(), labelX(), y, 1.0f, on ? Bo2Menus.ORANGE : Bo2Menus.WHITE);
				String v = r.value().get();
				if (capturing() && on) v = "PRESS A KEY";
				Bo2Menus.raw(g, v, valueX(), y, 1.0f, on ? Bo2Menus.ORANGE : Bo2Menus.WHITE);
			}
			Bo2Menus.hint(g, "ESC", "Back", labelX(), height - 26);
			if (tabs.length > 1) Bo2Menus.hint(g, "Q E", "Change page", (int) (width * 0.36), height - 26);
			extraHints(g);
		}

		void extraHints(GuiGraphics g) {}
	}

	/** OPTIONS: Settings or Controls. */
	static final class Root extends Page {
		Root(Screen parent) { super("OPTIONS", parent); }

		@Override List<Row> build(int tab) {
			Minecraft mc = Minecraft.getInstance();
			List<Row> r = new ArrayList<>();
			r.add(new Row("SETTINGS", () -> "", d -> mc.setScreen(new Settings(this))));
			r.add(new Row("CONTROLS", () -> "", d -> mc.setScreen(new Controls(this))));
			return r;
		}
	}

	/** Settings: video, sound and game pages. */
	static final class Settings extends Page {
		Settings(Screen parent) { super("SETTINGS", parent, "GRAPHICS", "SOUND", "GAME"); }

		@Override List<Row> build(int tab) {
			Minecraft mc = Minecraft.getInstance();
			var o = mc.options;
			List<Row> r = new ArrayList<>();
			switch (tab) {
				case 0 -> {
					r.add(bool("FULLSCREEN", o.fullscreen()));
					r.add(ints("FIELD OF VIEW", o.fov(), 30, 110, 5));
					r.add(pct("BRIGHTNESS", o.gamma()));
					r.add(ints("RENDER DISTANCE", o.renderDistance(), 2, 32, 1));
					r.add(graphics(o.graphicsMode()));
					r.add(bool("VSYNC", o.enableVsync()));
					r.add(ints("MAX FRAMERATE", o.framerateLimit(), 10, 260, 10));
				}
				case 1 -> {
					r.add(pct("MASTER VOLUME", o.getSoundSourceOptionInstance(SoundSource.MASTER)));
					r.add(pct("MUSIC VOLUME", o.getSoundSourceOptionInstance(SoundSource.MUSIC)));
					r.add(pct("EFFECTS VOLUME", o.getSoundSourceOptionInstance(SoundSource.PLAYERS)));
					r.add(pct("AMBIENT VOLUME", o.getSoundSourceOptionInstance(SoundSource.AMBIENT)));
					r.add(bool("SUBTITLES", o.showSubtitles()));
				}
				default -> {
					r.add(bool("VIEW BOBBING", o.bobView()));
					r.add(pct("MOUSE SENSITIVITY", o.sensitivity()));
					r.add(bool("INVERT MOUSE", o.invertYMouse()));
					r.add(bool("AUTO-JUMP", o.autoJump()));
					r.add(bool("SHOW COORDINATES (DEBUG)", o.reducedDebugInfo()));
				}
			}
			return r;
		}
	}

	/** Controls: the real key bindings, grouped like BO2's pages. Click a binding, then press the new key. */
	static final class Controls extends Page {
		private KeyMapping listening;

		Controls(Screen parent) { super("CONTROLS", parent, "LOOK", "MOVE", "COMBAT", "INTERACT"); }

		@Override boolean capturing() { return listening != null; }

		private static boolean in(KeyMapping km, int tab) {
			String c = km.getCategory();
			boolean move = c.equals("key.categories.movement");
			boolean combat = c.equals("key.categories.gameplay") || c.equals("key.categories.zombiecraft") || km.getName().startsWith("key.zombiecraft");
			return switch (tab) {
				case 1 -> move;
				case 2 -> combat;
				case 3 -> !move && !combat;
				default -> false;
			};
		}

		@Override List<Row> build(int tab) {
			var o = Minecraft.getInstance().options;
			List<Row> r = new ArrayList<>();
			if (tab == 0) {
				r.add(pct("MOUSE SENSITIVITY", o.sensitivity()));
				r.add(bool("INVERT MOUSE", o.invertYMouse()));
				return r;
			}
			for (KeyMapping km : o.keyMappings) if (in(km, tab)) r.add(key(km));
			return r;
		}

		private KeyMapping mappingAt(int i) {
			var o = Minecraft.getInstance().options;
			if (tab == 0) return null;
			int n = 0;
			for (KeyMapping km : o.keyMappings) if (in(km, tab) && n++ == i) return km;
			return null;
		}

		@Override void activate(int i) {
			KeyMapping km = mappingAt(i);
			if (km == null) rows.get(i).change().accept(1);
			else listening = km;
		}

		private void assign(InputConstants.Key k) {
			var o = Minecraft.getInstance().options;
			listening.setKey(k);
			KeyMapping.resetMapping();
			listening = null;
			save();
		}

		@Override public boolean keyPressed(int key, int scan, int mods) {
			if (listening != null) {
				if (key == GLFW.GLFW_KEY_ESCAPE) assign(InputConstants.UNKNOWN); else assign(InputConstants.getKey(key, scan));
				return true;
			}
			if (key == GLFW.GLFW_KEY_R) { resetAll(); return true; }
			return super.keyPressed(key, scan, mods);
		}

		@Override public boolean mouseClicked(double mx, double my, int button) {
			if (listening != null) { assign(InputConstants.Type.MOUSE.getOrCreate(button)); return true; }
			return super.mouseClicked(mx, my, button);
		}

		private void resetAll() {
			var o = Minecraft.getInstance().options;
			for (KeyMapping km : o.keyMappings) km.setKey(km.getDefaultKey());
			KeyMapping.resetMapping();
			save();
			MenuAudio.play("uin_main_nav");
		}

		@Override void extraHints(GuiGraphics g) {
			Bo2Menus.hint(g, "R", "Reset to Default", width - (int) (width * 0.06) - Bo2Menus.tw("R Reset to Default", 1.0f), height - 26);
		}
	}
}

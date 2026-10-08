package com.zombiecraft.client.menu;

import com.mojang.blaze3d.platform.InputConstants;
import com.zombiecraft.client.audio.MenuAudio;
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

	private static Row range(String label, OptionInstance<Double> o, double min, double max, double step) {
		return new Row(label, () -> String.format("%.2f", o.get()), d -> { o.set(Math.max(min, Math.min(max, Math.round((o.get() + d * step) * 100) / 100.0))); save(); });
	}

	/** Any enum-valued Minecraft option: left/right cycles through its values. */
	private static <E extends Enum<E>> Row cycle(String label, OptionInstance<E> o) {
		return new Row(label, () -> o.get().name().replace('_', ' '), d -> {
			E[] v = o.get().getDeclaringClass().getEnumConstants();
			o.set(v[(o.get().ordinal() + d + v.length) % v.length]); save();
		});
	}

	/** GUI scale changes need the window re-laid out. */
	private static Row guiScale(OptionInstance<Integer> o) {
		return new Row("GUI SCALE", () -> o.get() == 0 ? "AUTO" : String.valueOf(o.get()), d -> {
			o.set(Math.max(0, Math.min(6, o.get() + d))); save(); Minecraft.getInstance().resizeDisplay();
		});
	}

	private static Row key(KeyMapping km) {
		return new Row(Component.translatable(km.getName()).getString().toUpperCase(), () -> km.getTranslatedKeyMessage().getString().toUpperCase(), d -> {});
	}

	/** Shared look: title, tab bar, rows with an orange bracket on the selected one, key hints. */
	abstract static class Page extends Screen {
		final Screen parent;
		final String[] tabs;
		int tab, sel, scroll;
		List<Row> rows = new ArrayList<>();

		Page(String title, Screen parent, String... tabs) { super(Component.literal(title)); this.parent = parent; this.tabs = tabs; }

		abstract List<Row> build(int tab);

		@Override protected void init() { rows = build(tab); sel = Math.min(sel, Math.max(0, rows.size() - 1)); keepVisible(); }

		void setTab(int t) {
			tab = (t + tabs.length) % tabs.length; sel = 0; scroll = 0; rows = build(tab);
			MenuAudio.play("cac_submenu_nav");
		}

		void back() { MenuAudio.play("uin_cmn_backout"); save(); Minecraft.getInstance().setScreen(parent); }

		boolean capturing() { return false; }

		int visible() { return Math.max(3, (int) ((height * 0.88 - rowTop()) / rowStep())); }
		void keepVisible() {
			if (sel < scroll) scroll = sel;
			if (sel >= scroll + visible()) scroll = sel - visible() + 1;
			scroll = Math.max(0, Math.min(scroll, Math.max(0, rows.size() - visible())));
		}

		int rowTop() { return (int) (height * 0.25); }
		int rowStep() { return (int) (Bo2Menus.H(1.0f) * 1.35f); }
		int labelX() { return (int) (width * 0.06); }
		int valueX() { return (int) (width * 0.46); }

		@Override public boolean keyPressed(int key, int scan, int mods) {
			if (capturing()) return super.keyPressed(key, scan, mods);
			if (key == GLFW.GLFW_KEY_ESCAPE) { back(); return true; }
			if (key == GLFW.GLFW_KEY_DOWN && !rows.isEmpty()) { sel = (sel + 1) % rows.size(); keepVisible(); MenuAudio.play("uin_main_nav"); return true; }
			if (key == GLFW.GLFW_KEY_UP && !rows.isEmpty()) { sel = (sel + rows.size() - 1) % rows.size(); keepVisible(); MenuAudio.play("uin_main_nav"); return true; }
			if (key == GLFW.GLFW_KEY_Q || key == GLFW.GLFW_KEY_PAGE_UP) { setTab(tab - 1); return true; }
			if (key == GLFW.GLFW_KEY_E || key == GLFW.GLFW_KEY_PAGE_DOWN) { setTab(tab + 1); return true; }
			if (!rows.isEmpty()) {
				if (key == GLFW.GLFW_KEY_LEFT) { rows.get(sel).change().accept(-1); MenuAudio.play("cac_slide_nav_down"); return true; }
				if (key == GLFW.GLFW_KEY_RIGHT) { rows.get(sel).change().accept(1); MenuAudio.play("cac_slide_nav_up"); return true; }
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
			for (int i = scroll; i < Math.min(rows.size(), scroll + visible()); i++) {
				int y = rowTop() + (i - scroll) * rowStep();
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
			int y = (int) ((my - rowTop()) / rowStep()) + scroll;
			if (y >= scroll && y < Math.min(rows.size(), scroll + visible())) { sel = y; rows.get(y).change().accept(sy > 0 ? 1 : -1); return true; }
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
			g.fill(0, 0, width, height, 0xB8000000);
			int tw = Bo2Menus.tw(title.getString(), 2.2f);
			Bo2Menus.raw(g, title.getString(), (width - tw) / 2, (int) (height * 0.04), 2.2f, Bo2Menus.WHITE);
			if (tabs.length > 1) {
				// BO2 tab bar: an arrow at each end, the open tab orange, the others dim
				int x = (int) (width * 0.5 - totalTabsWidth() / 2.0), ty = (int) (height * 0.13);
				Bo2Menus.raw(g, "<", x - 30, ty, 0.9f, Bo2Menus.WHITE);
				for (int i = 0; i < tabs.length; i++) {
					int w = Bo2Menus.tw(tabs[i], 0.9f);
					Bo2Menus.raw(g, tabs[i], x, ty, 0.9f, i == tab ? Bo2Menus.ORANGE : 0xFF6C675F);
					x += w + 28;
				}
				Bo2Menus.raw(g, ">", x - 14, ty, 0.9f, Bo2Menus.WHITE);
			}
			for (int i = scroll; i < Math.min(rows.size(), scroll + visible()); i++) {
				int y = rowTop() + (i - scroll) * rowStep();
				Row r = rows.get(i);
				boolean on = i == sel;
				Bo2Menus.raw(g, r.label(), labelX(), y, 1.0f, on ? Bo2Menus.ORANGE : Bo2Menus.WHITE);
				String v = r.value().get();
				if (capturing() && on) v = "PRESS A KEY";
				Bo2Menus.raw(g, v, valueX(), y, 1.0f, on ? Bo2Menus.ORANGE : Bo2Menus.WHITE);
			}
			if (rows.size() > visible()) Bo2Menus.raw(g, (scroll + 1) + "-" + Math.min(rows.size(), scroll + visible()) + " / " + rows.size(), (int) (width * 0.86), height - 26, 0.8f, Bo2Menus.GREY);
			Bo2Menus.hint(g, "ESC", "Back", labelX(), height - 26);
			if (tabs.length > 1) Bo2Menus.hint(g, "Q E", "Change page", (int) (width * 0.36), height - 26);
			extraHints(g);
		}

		void extraHints(GuiGraphics g) {}
	}

	/** OPTIONS: Settings or Controls, centred like BO2's: the open entry large and orange, the other smaller and grey. */
	static final class Root extends Page {
		Root(Screen parent) { super("OPTIONS", parent); }

		@Override List<Row> build(int tab) {
			Minecraft mc = Minecraft.getInstance();
			List<Row> r = new ArrayList<>();
			r.add(new Row("SETTINGS", () -> "", d -> mc.setScreen(new Settings(this))));
			r.add(new Row("CONTROLS", () -> "", d -> mc.setScreen(new Controls(this))));
			return r;
		}

		private int entryY(int i) { return (int) (height * 0.38) + i * (int) (Bo2Menus.H(2.0f) * 1.5f); }
		private float entryScale(int i) { return i == sel ? 2.0f : 1.2f; }

		@Override public boolean mouseClicked(double mx, double my, int button) {
			for (int i = 0; i < rows.size(); i++) {
				int w = Bo2Menus.tw(rows.get(i).label(), entryScale(i)), h = (int) Bo2Menus.H(entryScale(i));
				if (Math.abs(mx - width / 2.0) <= w / 2.0 + 10 && my >= entryY(i) - 4 && my <= entryY(i) + h + 4) { sel = i; activate(i); return true; }
			}
			return false;
		}

		@Override public void render(GuiGraphics g, int mx, int my, float dt) {
			Bo2Menus.background(g, width, height);
			g.fill(0, 0, width, height, 0xB8000000);
			Bo2Menus.raw(g, "OPTIONS", (width - Bo2Menus.tw("OPTIONS", 2.2f)) / 2, (int) (height * 0.04), 2.2f, Bo2Menus.WHITE);
			for (int i = 0; i < rows.size(); i++) {
				float sc = entryScale(i);
				String l = rows.get(i).label();
				Bo2Menus.raw(g, l, (width - Bo2Menus.tw(l, sc)) / 2, entryY(i), sc, i == sel ? Bo2Menus.ORANGE : 0xFF8A857C);
			}
			Bo2Menus.hint(g, "ESC", "Back", labelX(), height - 26);
		}
	}

	/** Settings: video, sound and game pages. */
	static final class Settings extends Page {
		Settings(Screen parent) { super("SETTINGS", parent, "GRAPHICS", "SOUND", "GAME", "ACCESS", "CHAT"); }

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
					r.add(ints("SIMULATION DISTANCE", o.simulationDistance(), 5, 32, 1));
					r.add(cycle("GRAPHICS QUALITY", o.graphicsMode()));
					r.add(bool("VSYNC", o.enableVsync()));
					r.add(ints("MAX FRAMERATE", o.framerateLimit(), 10, 260, 10));
					r.add(cycle("FRAMERATE WHEN IDLE", o.inactivityFpsLimit()));
					r.add(guiScale(o.guiScale()));
					r.add(cycle("CLOUDS", o.cloudStatus()));
					r.add(cycle("PARTICLES", o.particles()));
					r.add(bool("SMOOTH LIGHTING", o.ambientOcclusion()));
					r.add(ints("BIOME BLEND", o.biomeBlendRadius(), 0, 7, 1));
					r.add(range("ENTITY DISTANCE", o.entityDistanceScaling(), 0.5, 5.0, 0.25));
					r.add(bool("ENTITY SHADOWS", o.entityShadows()));
					r.add(ints("MIPMAP LEVELS", o.mipmapLevels(), 0, 4, 1));
					r.add(cycle("CHUNK UPDATES", o.prioritizeChunkUpdates()));
					r.add(pct("DISTORTION EFFECTS", o.screenEffectScale()));
					r.add(pct("FOV EFFECTS", o.fovEffectScale()));
					r.add(pct("DARKNESS PULSING", o.darknessEffectScale()));
					r.add(pct("DAMAGE TILT", o.damageTiltStrength()));
					r.add(pct("GLINT SPEED", o.glintSpeed()));
					r.add(pct("GLINT STRENGTH", o.glintStrength()));
					r.add(cycle("ATTACK INDICATOR", o.attackIndicator()));
					r.add(bool("AUTOSAVE INDICATOR", o.showAutosaveIndicator()));
					r.add(ints("MENU BLUR", o.menuBackgroundBlurriness(), 0, 10, 1));
					r.add(pct("PANORAMA SPEED", o.panoramaSpeed()));
					r.add(bool("DARK LOADING BACKGROUND", o.darkMojangStudiosBackground()));
					r.add(bool("HIDE LIGHTNING FLASH", o.hideLightningFlash()));
					r.add(bool("HIDE SPLASH TEXTS", o.hideSplashTexts()));
				}
				case 1 -> {
					r.add(pct("MASTER VOLUME", o.getSoundSourceOptionInstance(SoundSource.MASTER)));
					r.add(pct("MUSIC VOLUME", o.getSoundSourceOptionInstance(SoundSource.MUSIC)));
					r.add(pct("EFFECTS VOLUME", o.getSoundSourceOptionInstance(SoundSource.PLAYERS)));
					r.add(pct("AMBIENT VOLUME", o.getSoundSourceOptionInstance(SoundSource.AMBIENT)));
					r.add(pct("JUKEBOX / NOTE BLOCKS", o.getSoundSourceOptionInstance(SoundSource.RECORDS)));
					r.add(pct("WEATHER VOLUME", o.getSoundSourceOptionInstance(SoundSource.WEATHER)));
					r.add(pct("BLOCKS VOLUME", o.getSoundSourceOptionInstance(SoundSource.BLOCKS)));
					r.add(pct("HOSTILE CREATURES", o.getSoundSourceOptionInstance(SoundSource.HOSTILE)));
					r.add(pct("FRIENDLY CREATURES", o.getSoundSourceOptionInstance(SoundSource.NEUTRAL)));
					r.add(pct("VOICE / SPEECH", o.getSoundSourceOptionInstance(SoundSource.VOICE)));
					r.add(bool("SUBTITLES", o.showSubtitles()));
					r.add(bool("DIRECTIONAL AUDIO", o.directionalAudio()));
				}
				case 2 -> {
					r.add(bool("VIEW BOBBING", o.bobView()));
					r.add(pct("MOUSE SENSITIVITY", o.sensitivity()));
					r.add(bool("INVERT MOUSE", o.invertYMouse()));
					r.add(bool("RAW MOUSE INPUT", o.rawMouseInput()));
					r.add(bool("DISCRETE SCROLLING", o.discreteMouseScroll()));
					r.add(range("SCROLL SENSITIVITY", o.mouseWheelSensitivity(), 0.1, 10.0, 0.1));
					r.add(bool("TOGGLE CROUCH", o.toggleCrouch()));
					r.add(bool("TOGGLE SPRINT", o.toggleSprint()));
					r.add(bool("AUTO-JUMP", o.autoJump()));
					r.add(cycle("MAIN HAND", o.mainHand()));
					r.add(bool("ROTATE WITH MINECART", o.rotateWithMinecart()));
					r.add(bool("TOUCHSCREEN MODE", o.touchscreen()));
					r.add(bool("OPERATOR ITEMS TAB", o.operatorItemsTab()));
					r.add(bool("SHOW COORDINATES (DEBUG)", o.reducedDebugInfo()));
					r.add(bool("ALLOW SERVER LISTING", o.allowServerListing()));
					r.add(bool("REALMS NOTIFICATIONS", o.realmsNotifications()));
				}
				case 3 -> {
					r.add(bool("HIGH CONTRAST", o.highContrast()));
					r.add(bool("HIGH CONTRAST BLOCK OUTLINE", o.highContrastBlockOutline()));
					r.add(bool("FORCE UNICODE FONT", o.forceUnicodeFont()));
					r.add(bool("JAPANESE GLYPH VARIANTS", o.japaneseGlyphVariants()));
					r.add(pct("TEXT BACKGROUND OPACITY", o.textBackgroundOpacity()));
					r.add(bool("BACKGROUND FOR CHAT ONLY", o.backgroundForChatOnly()));
					r.add(pct("NOTIFICATION TIME", o.notificationDisplayTime()));
				}
				default -> {
					r.add(cycle("CHAT", o.chatVisibility()));
					r.add(bool("CHAT COLORS", o.chatColors()));
					r.add(bool("WEB LINKS", o.chatLinks()));
					r.add(bool("CONFIRM LINK PROMPT", o.chatLinksPrompt()));
					r.add(pct("CHAT OPACITY", o.chatOpacity()));
					r.add(pct("CHAT TEXT SIZE", o.chatScale()));
					r.add(pct("CHAT LINE SPACING", o.chatLineSpacing()));
					r.add(pct("CHAT WIDTH", o.chatWidth()));
					r.add(pct("CHAT HEIGHT (FOCUSED)", o.chatHeightFocused()));
					r.add(pct("CHAT HEIGHT (UNFOCUSED)", o.chatHeightUnfocused()));
					r.add(pct("CHAT DELAY", o.chatDelay()));
					r.add(bool("COMMAND SUGGESTIONS", o.autoSuggestions()));
					r.add(bool("HIDE MATCHED NAMES", o.hideMatchedNames()));
					r.add(bool("ONLY SHOW SECURE CHAT", o.onlyShowSecureChat()));
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
			if (!com.zombiecraft.client.ZombiecraftClient.keyNeeded(km)) return false;
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
			for (KeyMapping km : o.keyMappings) km.setKey(com.zombiecraft.client.ZombiecraftClient.keyNeeded(km) ? km.getDefaultKey() : InputConstants.UNKNOWN);
			KeyMapping.resetMapping();
			save();
			MenuAudio.play("uin_main_nav");
		}

		@Override void extraHints(GuiGraphics g) {
			Bo2Menus.hint(g, "R", "Reset to Default", width - (int) (width * 0.06) - Bo2Menus.tw("R Reset to Default", 1.0f), height - 26);
		}
	}
}

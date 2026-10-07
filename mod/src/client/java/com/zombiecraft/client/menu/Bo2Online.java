package com.zombiecraft.client.menu;

import com.zombiecraft.client.audio.MenuAudio;
import com.zombiecraft.client.menu.Bo2Menus.MenuScreen;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.network.chat.Component;
import net.minecraft.util.HttpUtil;
import net.minecraft.world.level.GameType;
import org.lwjgl.glfw.GLFW;

import java.util.List;

/**
 * Online play, BO2 style. "Host online game" starts the map, opens the world to LAN on a fixed port and shows what to forward
 * (playit.gg tunnels that port); "Join game" takes the address the host shares.
 */
public final class Bo2Online {
	private Bo2Online() {}

	private static final int DEFAULT_PORT = 25565;
	private static boolean hostPending, showInvite, lobbyHosting;
	private static String lastAddress = "";
	/** Shown on the Join screen after a connection failed or timed out. */
	private static String joinError;
	private static final long TIMEOUT_MS = 20_000;
	private static Screen connectingScreen;
	private static long connectStart;

	/** Called from map select when the player chose to host: the world is opened to LAN as soon as they are in it. */
	static void hostNext() { hostPending = true; lobbyHosting = true; com.zombiecraft.game.Game.lobbyNext = true; }

	static boolean hosting(Minecraft mc) { return mc.hasSingleplayerServer() && mc.getSingleplayerServer().isPublished(); }

	public static void register() {
		// dev: -Dzombiecraft.debugHost=true opens the autoplay world to LAN without auth; -Dzombiecraft.debugJoin=<address> joins one by itself
		boolean debugHost = Boolean.getBoolean("zombiecraft.debugHost");
		String debugJoin = System.getProperty("zombiecraft.debugJoin");
		if (debugHost) hostPending = true;
		// dev: -Dzombiecraft.debugLobby=true hosts the autoplay world as an online lobby and saves zc-lobby.png once it is up
		if (Boolean.getBoolean("zombiecraft.debugLobby")) {
			hostNext();
			Thread t = new Thread(() -> {
				try { Thread.sleep(300000); } catch (InterruptedException ignored) {}
				Minecraft mc = Minecraft.getInstance();
				mc.execute(() -> net.minecraft.client.Screenshot.grab(mc.gameDirectory, "zc-lobby.png", mc.getMainRenderTarget(), c -> {}));
			}, "zc-dev-shot");
			t.setDaemon(true);
			t.start();
		}
		if (debugJoin != null) {
			boolean[] done = {false};
			ClientTickEvents.END_CLIENT_TICK.register(mc -> {
				if (done[0] || mc.level != null || mc.getOverlay() != null || mc.screen == null) return;
				done[0] = true; lastAddress = debugJoin;
					String target = debugJoin;
					try { String r = Relay.resolveJoin(debugJoin); if (r != null) target = r; } catch (java.io.IOException e) { return; }
				ConnectScreen.startConnecting(mc.screen, mc, ServerAddress.parseString(target), new ServerData("Block Ops 2", target, ServerData.Type.OTHER), false, null);
			});
		}
		net.fabricmc.fabric.api.client.screen.v1.ScreenEvents.AFTER_INIT.register((mc, screen, w, h) -> {
			if (screen instanceof net.minecraft.client.gui.screens.DisconnectedScreen)
				net.fabricmc.fabric.api.client.screen.v1.ScreenEvents.beforeRender(screen).register((scr, g, mx, my, dt) -> disconnected(g, scr));
			if (screen instanceof ConnectScreen)
				net.fabricmc.fabric.api.client.screen.v1.ScreenKeyboardEvents.allowKeyPress(screen).register((scr, key, scan, mods) -> {
					if (key != GLFW.GLFW_KEY_ESCAPE) return true;
					cancel(scr);
					return false;
				});
		});
		ClientPlayConnectionEvents.JOIN.register((handler, sender, mc) -> {
			if (!Bo2Menus.quietLoad) MenuAudio.stopMusic();
			joinError = null;
			if (!mc.hasSingleplayerServer() && Relay.joinCode != null) { Relay.joinedAt = System.currentTimeMillis(); net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.send(new com.zombiecraft.net.Payloads.JoinCode(Relay.joinCode)); }
			if (!hostPending || !mc.hasSingleplayerServer()) {
				com.zombiecraft.ZombiecraftMod.LOG.info("Block Ops 2 online: not opening to LAN (hostPending={}, singleplayerServer={})", hostPending, mc.hasSingleplayerServer());
				return;
			}
			hostPending = false;
			mc.execute(() -> {
				var server = mc.getSingleplayerServer();
				if (server == null) return;
				server.setUsesAuthentication(false); // players run dev/offline accounts that Mojang's session check would refuse ("Invalid session"); the join code is the gate
				int port = HttpUtil.isPortAvailable(DEFAULT_PORT) ? DEFAULT_PORT : HttpUtil.getAvailablePort();
				boolean ok = server.publishServer(GameType.ADVENTURE, false, port);
				com.zombiecraft.ZombiecraftMod.LOG.info("Block Ops 2 online: opened to LAN on port {}: {}", port, ok);
				if (ok) { showInvite = true; Relay.expose(port); }
			});
		});
		ClientPlayConnectionEvents.DISCONNECT.register((handler, mc) -> { hostPending = false; showInvite = false; lobbyHosting = false; com.zombiecraft.game.Game.lobbyNext = false; Bo2Menus.quietLoad = false; Relay.stop(); Relay.joinCode = null; });
		// the invite box waits for the loading screen to finish; a hosted lobby has its own screen (with INVITE FRIENDS), so no box first
		ClientTickEvents.END_CLIENT_TICK.register(mc -> {
			if (showInvite && mc.player != null && mc.screen == null) { showInvite = false; if (!lobbyHosting) mc.setScreen(new Invite()); }
			boolean inLobby = mc.player != null && com.zombiecraft.client.ZombiecraftClient.state.phase() == com.zombiecraft.net.Payloads.PHASE_LOBBY;
			if (inLobby && mc.screen == null) mc.setScreen(new Bo2Locations.Lobby());
			else if (!inLobby && (mc.screen instanceof Bo2Locations.Lobby || mc.screen instanceof Invite)) {
				// the host started the match: now, for everyone together, the menu music ends and the loading picture with its music shows
				Bo2Menus.quietLoad = false;
				MenuAudio.stopMusic();
				mc.setScreen(new Bo2Locations.Starting());
			}
		});
	}

	/** The BO2 look for vanilla's "Connecting to the server" screen (drawn from ZcConnectScreenMixin): address, time spent, hints, and a timeout back to Join Game with a message. */
	public static void connecting(GuiGraphics g, Screen s) {
		MenuAudio.music();
		if (s != connectingScreen) { connectingScreen = s; connectStart = System.currentTimeMillis(); }
		long ms = System.currentTimeMillis() - connectStart;
		int w = s.width, h = s.height;
		Bo2Menus.background(g, w, h);
		g.fillGradient(0, 0, w, h, 0x80000000, 0xB0000000);
		int x = (int) (w * 0.16), y = (int) (h * 0.2);
		Bo2Menus.text(g, "CONNECTING", x, y, 2.6f, Bo2Menus.WHITE);
		y += (int) Bo2Menus.H(2.6f) + 24;
		Bo2Menus.raw(g, lastAddress.isEmpty() ? "Host" : lastAddress, x, y, 1.3f, Bo2Menus.ORANGE);
		y += (int) Bo2Menus.H(1.3f) + 14;
		int left = (int) Math.max(0, (TIMEOUT_MS - ms + 999) / 1000);
		String dots = ".".repeat((int) (ms / 400 % 4));
		Bo2Menus.raw(g, "Reaching the host" + dots + "  " + (ms / 1000) + "s", x, y, 1.0f, 0xFFD2CEC6);
		y += (int) Bo2Menus.H(1.0f) + 10;
		int bw = (int) (w * 0.4);
		g.fill(x, y, x + bw, y + 4, 0x40FFFFFF);
		g.fill(x, y, x + (int) (bw * Math.min(1f, ms / (float) TIMEOUT_MS)), y + 4, Bo2Menus.ORANGE);
		y += 18;
		if (ms > 8000) {
			for (String l : Bo2Menus.wrap("Still trying. Check that the host turned ONLINE GAME on before starting the match, that the playit.gg agent is running, and that this address is exactly what playit shows. Giving up in " + left + "s.", bw + 80, 0.8f)) {
				Bo2Menus.raw(g, l, x, y, 0.8f, 0xFFD2CEC6);
				y += (int) (Bo2Menus.H(0.8f) * 1.2f);
			}
		}
		Bo2Menus.hint(g, "ESC", "Cancel", x, h - 26);
		if (ms > TIMEOUT_MS) {
			joinError = "Could not reach " + (lastAddress.isEmpty() ? "the host" : lastAddress) + " after " + TIMEOUT_MS / 1000 + " seconds. The host may not be online, the tunnel may be off, or the address may be wrong.";
			cancel(s);
		}
	}

	/** Same as pressing the Cancel button of vanilla's connecting screen. */
	public static void cancel(Screen s) {
		connectingScreen = null;
		Bo2Menus.quietLoad = false;
		for (var c : s.children()) if (c instanceof net.minecraft.client.gui.components.Button b) { b.onPress(); return; }
		Minecraft.getInstance().setScreen(new net.minecraft.client.gui.screens.TitleScreen());
	}

	/** A failed or dropped connection (vanilla's disconnected screen) gets the BO2 backdrop and music behind its message. */
	public static void disconnected(GuiGraphics g, Screen s) {
		MenuAudio.music();
		Bo2Menus.background(g, s.width, s.height);
		g.fillGradient(0, 0, s.width, s.height, 0x80000000, 0xB0000000);
		if (Relay.joinedAt != 0 && System.currentTimeMillis() - Relay.joinedAt < 20_000) {
			int y = (int) (s.height * 0.78);
			for (String l : Bo2Menus.wrap("Disconnected right after joining. That is almost always a wrong or old join code: the host's code changes every lobby, so ask for the one on their lobby screen and try again.", (int) (s.width * 0.6), 1.0f)) { Bo2Menus.raw(g, l, (int) (s.width * 0.16), y, 1.0f, 0xFFFF9A4A); y += (int) (Bo2Menus.H(1.0f) * 1.2f); }
		}
		if (Relay.lastError != null) Bo2Menus.raw(g, Relay.lastError, (int) (s.width * 0.16), (int) (s.height * 0.78), 1.0f, 0xFFFF5A4A);
	}

	/** What to forward, and where to get the address to share. */
	static final class Invite extends Screen {
		private final String[] items = {"COPY CODE", "COPY INVITE MESSAGE", "BACK"};
		private int sel, lastSel = -1, seenX = -1, seenY = -1;
		private String flash = "";
		private long flashUntil;

		Invite() { super(Component.literal("Invite friends")); }

		@Override public boolean shouldCloseOnEsc() { return false; }
		@Override public boolean isPauseScreen() { return false; }
		@Override public void renderBackground(GuiGraphics g, int mx, int my, float dt) {}

		private void activate(int i) {
			Minecraft mc = Minecraft.getInstance();
			if (i == 2) { MenuAudio.play("uin_cmn_backout"); mc.setScreen(null); return; } // the lobby screen comes back by itself
			String code = Relay.code;
			if (code == null) { MenuAudio.play("cac_cmn_deny"); return; }
			mc.keyboardHandler.setClipboard(i == 0 ? code : "Join my Block Ops 2 lobby! Open the game, pick Join Game and type the code: " + code);
			flash = i == 0 ? "Code copied." : "Invite message copied. Paste it to your friends.";
			flashUntil = System.currentTimeMillis() + 2500;
			MenuAudio.play("uin_main_nav");
		}

		@Override public boolean mouseClicked(double mx, double my, int button) {
			if (button == 0) { activate(sel); return true; }
			return false;
		}

		@Override public boolean keyPressed(int key, int scan, int mods) {
			if (key == GLFW.GLFW_KEY_ESCAPE) activate(2);
			else if (key == GLFW.GLFW_KEY_DOWN) sel = (sel + 1) % items.length;
			else if (key == GLFW.GLFW_KEY_UP) sel = (sel + items.length - 1) % items.length;
			else if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) activate(sel);
			else return super.keyPressed(key, scan, mods);
			return true;
		}

		@Override public void render(GuiGraphics g, int mx, int my, float dt) {
			MenuAudio.music();
			Bo2Locations.topDown(g, width, height, true);
			boolean moved = seenX >= 0 && (mx != seenX || my != seenY);
			seenX = mx; seenY = my;
			int x = (int) (width * 0.05), y = (int) (height * 0.07);
			Bo2Menus.raw(g, "INVITE FRIENDS", x, y, 2.0f, Bo2Menus.WHITE);
			y += (int) Bo2Menus.H(2.0f) + 6;
			String code = Relay.code;
			Bo2Menus.raw(g, "JOIN CODE", x, y, 0.85f, Bo2Menus.GREY);
			y += (int) Bo2Menus.H(0.85f) + 4;
			if (code != null) Bo2Menus.raw(g, code, x, y, 2.4f, Bo2Menus.YELLOW);
			else Bo2Menus.raw(g, Relay.status.isEmpty() ? "Getting your code..." : "No code yet", x, y, 1.4f, Bo2Menus.GREY);
			y += (int) Bo2Menus.H(2.4f) + 6;
			if (code == null && !Relay.status.isEmpty())
				for (String line : Bo2Menus.wrap(Relay.status, (int) (width * 0.5), 0.8f)) { Bo2Menus.raw(g, line, x, y, 0.8f, 0xFFFF9A4A); y += (int) (Bo2Menus.H(0.8f) * 1.2f); }
			String[] steps = {"1. Your friend opens Block Ops 2 and picks Join Game.", "2. They type the code above and press ENTER.", "3. They show up in your lobby. Press START MATCH when everyone is in.", "Up to 4 players. The code changes every lobby."};
			for (String s : steps) { Bo2Menus.raw(g, s, x, y, 0.75f, 0xFFD2CEC6); y += (int) (Bo2Menus.H(0.75f) * 1.2f); }
			y += 8;
			int step = (int) (Bo2Menus.H(1.1f) * 1.2f);
			for (int i = 0; i < items.length; i++) {
				int iy = y + i * step, w = Bo2Menus.tw(items[i], 1.1f);
				if (moved && mx >= x - 6 && mx <= x + w + 6 && my >= iy - 3 && my <= iy + step - 3) sel = i;
				boolean on = i == sel;
				if (on) g.renderOutline(x - 6, iy - 3, w + 12, step - 2, Bo2Menus.ORANGE);
				Bo2Menus.raw(g, items[i], x, iy, 1.1f, code == null && i < 2 ? Bo2Menus.GREY : on ? Bo2Menus.ORANGE : Bo2Menus.WHITE);
			}
			if (sel != lastSel) { if (lastSel >= 0) MenuAudio.play("uin_main_nav"); lastSel = sel; }
			if (System.currentTimeMillis() < flashUntil) Bo2Menus.raw(g, flash, x + (int) (width * 0.3), y + 2, 0.9f, Bo2Menus.YELLOW);
			Bo2Menus.hint(g, "ESC", "Back", x, height - 26);
		}
	}

	/** Type or paste the host's address. */
	static final class Join extends Screen {
		private final Screen parent;
		private final StringBuilder addr = new StringBuilder(lastAddress);

		Join(Screen parent) { super(Component.literal("Join Game")); this.parent = parent; }

		private void connect() {
			String a = addr.toString().trim();
			if (a.isEmpty() || !ServerAddress.isValidAddress(a)) { MenuAudio.play("cac_cmn_deny"); return; }
			String viaCode;
			try { viaCode = Relay.resolveJoin(a); } catch (java.io.IOException e) { joinError = e.getMessage(); MenuAudio.play("cac_cmn_deny"); return; }
			Bo2Menus.quietLoad = true; // joining a lobby: no loading screen or loading music, the lobby screen follows
			lastAddress = viaCode != null ? a.toUpperCase() : a;
			if (viaCode != null) a = viaCode;
			MenuAudio.play("uin_lobby_join");
			joinError = null;
			Minecraft mc = Minecraft.getInstance();
			ConnectScreen.startConnecting(this, mc, ServerAddress.parseString(a), new ServerData("Block Ops 2", a, ServerData.Type.OTHER), false, null);
		}

		@Override public boolean charTyped(char c, int mods) {
			if (c > 32 && c < 127 && addr.length() < 120) { addr.append(c); joinError = null; }
			return true;
		}

		@Override public boolean keyPressed(int key, int scan, int mods) {
			if (key == GLFW.GLFW_KEY_ESCAPE) { MenuAudio.play("uin_cmn_backout"); Minecraft.getInstance().setScreen(parent); }
			else if (key == GLFW.GLFW_KEY_BACKSPACE) { if (!addr.isEmpty()) addr.setLength(addr.length() - 1); }
			else if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) connect();
			else if (key == GLFW.GLFW_KEY_V && (mods & GLFW.GLFW_MOD_CONTROL) != 0) {
				String clip = Minecraft.getInstance().keyboardHandler.getClipboard().replaceAll("[^\\x21-\\x7e]", "");
				addr.setLength(0);
				addr.append(clip, 0, Math.min(120, clip.length()));
			} else return super.keyPressed(key, scan, mods);
			return true;
		}

		@Override public boolean shouldCloseOnEsc() { return false; }
		@Override public void renderBackground(GuiGraphics g, int mx, int my, float dt) {}

		@Override public void render(GuiGraphics g, int mx, int my, float dt) {
			MenuAudio.music();
			Bo2Menus.background(g, width, height);
			int x = (int) (width * 0.16);
			Bo2Menus.text(g, "JOIN GAME", x, (int) (height * 0.07), 2.6f, Bo2Menus.WHITE);
			int y = (int) (height * 0.07) + (int) Bo2Menus.H(2.6f) + 30;
			Bo2Menus.text(g, "Join code, or host address", x, y, 1.0f, Bo2Menus.GREY);
			y += (int) Bo2Menus.H(1.0f) + 10;
			int bw = (int) (width * 0.5), bh = (int) Bo2Menus.H(1.3f) + 10;
			g.fill(x - 4, y - 4, x + bw, y - 4 + bh, 0xB0000000);
			g.renderOutline(x - 4, y - 4, bw + 4, bh, Bo2Menus.ORANGE);
			String shown = addr + ((System.currentTimeMillis() / 500) % 2 == 0 ? "_" : "");
			Bo2Menus.raw(g, shown, x, y, 1.3f, Bo2Menus.WHITE);
			y += bh + 10;
			List<String> help = Bo2Menus.wrap("Type the join code the host sees in the lobby. Or paste an address: what playit.gg shows the host, for example name.joinmc.link. Everyone needs the same Block Ops 2 version.", bw, 0.8f);
			for (String l : help) { Bo2Menus.raw(g, l, x, y, 0.8f, 0xFFD2CEC6); y += (int) (Bo2Menus.H(0.8f) * 1.2f); }
			if (joinError != null) {
				y += 8;
				for (String l : Bo2Menus.wrap(joinError, bw, 0.85f)) { Bo2Menus.raw(g, l, x, y, 0.85f, 0xFFFF5A4A); y += (int) (Bo2Menus.H(0.85f) * 1.2f); }
			}
			int hx = x;
			hx += Bo2Menus.hint(g, "ENTER", "Connect", hx, height - 26);
			hx += Bo2Menus.hint(g, "CTRL V", "Paste", hx, height - 26);
			Bo2Menus.hint(g, "ESC", "Back", hx, height - 26);
		}
	}
}

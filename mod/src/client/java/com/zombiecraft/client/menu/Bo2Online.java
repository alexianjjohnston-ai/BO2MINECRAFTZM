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
				try { Thread.sleep(100000); } catch (InterruptedException ignored) {}
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
					if (Relay.looksLikeCode(debugJoin)) try { target = "127.0.0.1:" + Relay.forward(debugJoin); } catch (java.io.IOException e) { return; }
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
			MenuAudio.stopMusic();
			joinError = null;
			if (!hostPending || !mc.hasSingleplayerServer()) {
				com.zombiecraft.ZombiecraftMod.LOG.info("Block Ops 2 online: not opening to LAN (hostPending={}, singleplayerServer={})", hostPending, mc.hasSingleplayerServer());
				return;
			}
			hostPending = false;
			mc.execute(() -> {
				var server = mc.getSingleplayerServer();
				if (server == null) return;
				if (debugHost) server.setUsesAuthentication(false);
				int port = HttpUtil.isPortAvailable(DEFAULT_PORT) ? DEFAULT_PORT : HttpUtil.getAvailablePort();
				boolean ok = server.publishServer(GameType.ADVENTURE, false, port);
				com.zombiecraft.ZombiecraftMod.LOG.info("Block Ops 2 online: opened to LAN on port {}: {}", port, ok);
				if (ok) { showInvite = true; Relay.host(port); }
			});
		});
		ClientPlayConnectionEvents.DISCONNECT.register((handler, mc) -> { hostPending = false; showInvite = false; lobbyHosting = false; com.zombiecraft.game.Game.lobbyNext = false; Relay.stop(); });
		// the invite box waits for the loading screen to finish; a hosted lobby has its own screen (with INVITE FRIENDS), so no box first
		ClientTickEvents.END_CLIENT_TICK.register(mc -> {
			if (showInvite && mc.player != null && mc.screen == null) { showInvite = false; if (!lobbyHosting) mc.setScreen(new Invite()); }
			boolean inLobby = mc.player != null && com.zombiecraft.client.ZombiecraftClient.state.phase() == com.zombiecraft.net.Payloads.PHASE_LOBBY;
			if (inLobby && mc.screen == null) mc.setScreen(new Bo2Locations.Lobby());
			else if (!inLobby && mc.screen instanceof Bo2Locations.Lobby) mc.setScreen(null);
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
		for (var c : s.children()) if (c instanceof net.minecraft.client.gui.components.Button b) { b.onPress(); return; }
		Minecraft.getInstance().setScreen(new net.minecraft.client.gui.screens.TitleScreen());
	}

	/** A failed or dropped connection (vanilla's disconnected screen) gets the BO2 backdrop and music behind its message. */
	public static void disconnected(GuiGraphics g, Screen s) {
		MenuAudio.music();
		Bo2Menus.background(g, s.width, s.height);
		g.fillGradient(0, 0, s.width, s.height, 0x80000000, 0xB0000000);
		if (Relay.lastError != null) Bo2Menus.raw(g, Relay.lastError, (int) (s.width * 0.16), (int) (s.height * 0.78), 1.0f, 0xFFFF5A4A);
	}

	/** What to forward, and where to get the address to share. */
	static final class Invite extends MenuScreen {
		Invite() { super("Online Game"); items = Relay.configured() != null ? new String[] {"COPY CODE", "OPEN PLAYIT.GG", "CONTINUE"} : new String[] {"OPEN PLAYIT.GG", "COPY PORT", "CONTINUE"}; scale = 1.1f; }

		private int port() {
			var s = Minecraft.getInstance().getSingleplayerServer();
			return s == null ? DEFAULT_PORT : s.getPort();
		}

		@Override void activate(int i) {
			Minecraft mc = Minecraft.getInstance();
			if (Relay.configured() != null && i < 2) {
				if (i == 0 && Relay.code != null) mc.keyboardHandler.setClipboard(Relay.code);
				else if (i == 1) net.minecraft.Util.getPlatform().openUri("https://playit.gg/account/tunnels");
				return;
			}
			switch (i) {
				case 0 -> net.minecraft.Util.getPlatform().openUri("https://playit.gg/account/tunnels");
				case 1 -> mc.keyboardHandler.setClipboard(String.valueOf(port()));
				default -> { MenuAudio.play("uin_cmn_backout"); mc.setScreen(null); }
			}
		}

		@Override public boolean keyPressed(int key, int scan, int mods) {
			if (key == GLFW.GLFW_KEY_ESCAPE) { activate(2); return true; }
			return super.keyPressed(key, scan, mods);
		}

		@Override public void render(GuiGraphics g, int mx, int my, float dt) {
			g.fillGradient(0, 0, width, height, 0xA0000000, 0xC0000000);
			int bw = Math.max((int) (width * 0.5), 360), pad = 16;
			String[] steps = Relay.configured() != null ? new String[] {
				"Your game is open for friends. Join code: " + (Relay.code != null ? Relay.code : "connecting to the relay..."),
				"Friends pick Join Game and type the code. No port forwarding needed.",
				"A playit.gg tunnel to 127.0.0.1 port " + port() + " (TCP) also still works."
			} : new String[] {
				"Your game is open for friends on port " + port() + " (TCP).",
				"1. Start the playit.gg agent on this PC and sign in.",
				"2. Create a tunnel of type Minecraft Java (TCP) with local address 127.0.0.1 and local port " + port() + ".",
				"3. Send your friends the address playit shows. They pick Join Game and type it in."
			};
			int titleH = (int) Bo2Menus.H(1.5f), lineH = (int) (Bo2Menus.H(0.75f) * 1.25f);
			int bodyH = 0;
			for (String s : steps) bodyH += Bo2Menus.wrap(s, bw - 2 * pad - 8, 0.75f).size() * lineH + 4;
			int bh = pad + titleH + 10 + bodyH + 12 + 3 * step() + pad;
			int bx = (width - bw) / 2, by = (height - bh) / 2;
			g.fill(bx - 3, by - 3, bx + bw + 3, by + bh + 3, 0xFF6A645C);
			g.fill(bx, by, bx + bw, by + bh, 0xF0141210);
			Bo2Menus.text(g, "Online Game", bx + pad, by + pad, 1.5f, Bo2Menus.WHITE);
			int ty = by + pad + titleH + 10;
			for (String s : steps) {
				for (String line : Bo2Menus.wrap(s, bw - 2 * pad - 8, 0.75f)) { Bo2Menus.raw(g, line, bx + pad, ty, 0.75f, 0xFFD2CEC6); ty += lineH; }
				ty += 4;
			}
			x = bx + pad; y0 = ty + 12;
			drawItems(g, mx, my);
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
			if (Relay.looksLikeCode(a)) {
				if (Relay.configured() == null) { joinError = "Join codes need a relay. Add relay=host:port to config/zombiecraft.properties (the address of your relay.py), or enter the host's address instead."; MenuAudio.play("cac_cmn_deny"); return; }
				try { a = "127.0.0.1:" + Relay.forward(a); } catch (java.io.IOException e) { joinError = "Could not start the join: " + e.getMessage(); return; }
				lastAddress = addr.toString().trim().toUpperCase();
			} else lastAddress = a;
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
			List<String> help = Bo2Menus.wrap("Type the 6-character join code the host sees in the lobby. Or paste an address: what playit.gg shows the host, for example name.joinmc.link. Everyone needs the same Block Ops 2 version.", bw, 0.8f);
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

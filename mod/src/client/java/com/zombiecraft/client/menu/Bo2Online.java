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
	private static boolean hostPending, showInvite;
	private static String lastAddress = "";

	/** Called from map select when the player chose to host: the world is opened to LAN as soon as they are in it. */
	static void hostNext() { hostPending = true; }

	static boolean hosting(Minecraft mc) { return mc.hasSingleplayerServer() && mc.getSingleplayerServer().isPublished(); }

	public static void register() {
		// dev: -Dzombiecraft.debugHost=true opens the autoplay world to LAN without auth; -Dzombiecraft.debugJoin=<address> joins one by itself
		boolean debugHost = Boolean.getBoolean("zombiecraft.debugHost");
		String debugJoin = System.getProperty("zombiecraft.debugJoin");
		if (debugHost) hostPending = true;
		if (debugJoin != null) {
			boolean[] done = {false};
			ClientTickEvents.END_CLIENT_TICK.register(mc -> {
				if (done[0] || mc.level != null || mc.getOverlay() != null || mc.screen == null) return;
				done[0] = true;
				ConnectScreen.startConnecting(mc.screen, mc, ServerAddress.parseString(debugJoin), new ServerData("Block Ops 2", debugJoin, ServerData.Type.OTHER), false, null);
			});
		}
		ClientPlayConnectionEvents.JOIN.register((handler, sender, mc) -> {
			if (!hostPending || !mc.hasSingleplayerServer()) return;
			hostPending = false;
			mc.execute(() -> {
				var server = mc.getSingleplayerServer();
				if (server == null) return;
				if (debugHost) server.setUsesAuthentication(false);
				int port = HttpUtil.isPortAvailable(DEFAULT_PORT) ? DEFAULT_PORT : HttpUtil.getAvailablePort();
				if (server.publishServer(GameType.ADVENTURE, false, port)) showInvite = true;
			});
		});
		ClientPlayConnectionEvents.DISCONNECT.register((handler, mc) -> { hostPending = false; showInvite = false; });
		// the invite box waits for the loading screen to finish
		ClientTickEvents.END_CLIENT_TICK.register(mc -> {
			if (showInvite && mc.player != null && mc.screen == null) { showInvite = false; mc.setScreen(new Invite()); }
		});
	}

	/** What to forward, and where to get the address to share. */
	static final class Invite extends MenuScreen {
		Invite() { super("Online Game"); items = new String[] {"OPEN PLAYIT.GG", "COPY PORT", "CONTINUE"}; scale = 1.1f; }

		private int port() {
			var s = Minecraft.getInstance().getSingleplayerServer();
			return s == null ? DEFAULT_PORT : s.getPort();
		}

		@Override void activate(int i) {
			Minecraft mc = Minecraft.getInstance();
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
			String[] steps = {
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
			lastAddress = a;
			MenuAudio.play("uin_lobby_join");
			MenuAudio.stopMusic();
			Minecraft mc = Minecraft.getInstance();
			ConnectScreen.startConnecting(this, mc, ServerAddress.parseString(a), new ServerData("Block Ops 2", a, ServerData.Type.OTHER), false, null);
		}

		@Override public boolean charTyped(char c, int mods) {
			if (c > 32 && c < 127 && addr.length() < 120) addr.append(c);
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
			Bo2Menus.text(g, "Host address (from playit.gg)", x, y, 1.0f, Bo2Menus.GREY);
			y += (int) Bo2Menus.H(1.0f) + 10;
			int bw = (int) (width * 0.5), bh = (int) Bo2Menus.H(1.3f) + 10;
			g.fill(x - 4, y - 4, x + bw, y - 4 + bh, 0xB0000000);
			g.renderOutline(x - 4, y - 4, bw + 4, bh, Bo2Menus.ORANGE);
			String shown = addr + ((System.currentTimeMillis() / 500) % 2 == 0 ? "_" : "");
			Bo2Menus.raw(g, shown, x, y, 1.3f, Bo2Menus.WHITE);
			y += bh + 10;
			List<String> help = Bo2Menus.wrap("Paste the address your host got from playit.gg, for example name.joinmc.link or an address with a port. Everyone needs the same Block Ops 2 version.", bw, 0.8f);
			for (String l : help) { Bo2Menus.raw(g, l, x, y, 0.8f, 0xFFD2CEC6); y += (int) (Bo2Menus.H(0.8f) * 1.2f); }
			int hx = x;
			hx += Bo2Menus.hint(g, "ENTER", "Connect", hx, height - 26);
			hx += Bo2Menus.hint(g, "CTRL V", "Paste", hx, height - 26);
			Bo2Menus.hint(g, "ESC", "Back", hx, height - 26);
		}
	}
}

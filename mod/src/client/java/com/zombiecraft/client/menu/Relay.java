package com.zombiecraft.client.menu;

import com.zombiecraft.ZombiecraftMod;
import net.minecraft.client.Minecraft;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Join codes through a relay (tools/relay/relay.py). The host's game dials out to the relay and gets a 6-character code; a joiner types the code and
 * a loopback forwarder carries Minecraft's connection through the relay to the host's LAN port. Nobody forwards a port. The relay address is
 * {@code relay=host:port} in config/zombiecraft.properties (or -Dzombiecraft.relay=host:port); without it the playit.gg steps remain.
 */
final class Relay {
	private Relay() {}

	/** The code of the match being hosted (null until the relay answered, or without a relay). */
	static volatile String code;
	/** Why the last join through the relay failed, shown on the disconnected screen. */
	static volatile String lastError;
	private static volatile boolean hosting;
	private static volatile Socket control;
	private static ServerSocket forwarder;

	/** What the lobby shows while there is no code yet, or why there will be none. */
	static volatile String status = "";

	/** "host:port" of a relay from the config, or null. */
	static String configured() { return prop("relay"); }

	private static String prop(String key) {
		String v = System.getProperty("zombiecraft." + key, "");
		if (v.isBlank()) {
			try {
				Path cfg = Minecraft.getInstance().gameDirectory.toPath().resolve("config").resolve("zombiecraft.properties");
				if (Files.isRegularFile(cfg)) {
					Properties p = new Properties();
					try (var in = Files.newInputStream(cfg)) { p.load(in); }
					v = p.getProperty(key, "");
				}
			} catch (IOException | RuntimeException ignored) {}
		}
		v = v.trim();
		return v.isEmpty() ? null : v;
	}

	/** Where a friend's game connects when it is given a join code: the host's tunnel (playit.gg), {@code host=name:port} in the config, else this built-in default. */
	static final String DEFAULT_HOST = "ninth-kinase.tun.ply.gg:53506";

	static String joinHost() { String h = prop("host"); return h != null ? h : DEFAULT_HOST; }

	/** A join code is 6 letters/digits; anything else (a dot or colon) is an address. */
	static boolean looksLikeCode(String s) { return s.matches("[A-Za-z0-9]{6}"); }

	private static final String CODE_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"; // no 0/O/1/I

	/** A fresh random code for each lobby. */
	static String randomCode() {
		java.security.SecureRandom r = new java.security.SecureRandom();
		StringBuilder b = new StringBuilder();
		for (int i = 0; i < 6; i++) b.append(CODE_CHARS.charAt(r.nextInt(CODE_CHARS.length())));
		return b.toString();
	}

	/** The code this player typed, sent to the host once connected (the host's game refuses players without the current code). */
	static volatile String joinCode;
	/** When the code was sent: a disconnect soon after is almost always a wrong or outdated code. */
	static volatile long joinedAt;

	/** What to connect to for a typed join code (the host's tunnel; with a relay, a loopback forwarder through it). Null when the input is not a code. */
	static String resolveJoin(String input) throws IOException {
		String a = input.trim();
		if (!looksLikeCode(a)) return null;
		if (configured() != null) return "127.0.0.1:" + forward(a);
		joinCode = a.toUpperCase();
		return joinHost();
	}

	/**
	 * Called when the world opens to LAN: makes the lobby's random join code. Players connect to the host's tunnel and the host's game checks the code
	 * ({@link com.zombiecraft.game.Game#joinCode}). With {@code relay=} set the relay hands out the code instead.
	 */
	static void expose(int localPort) {
		code = null;
		status = "";
		joinCode = null;
		com.zombiecraft.game.Game.joinCode = null;
		if (configured() != null) { status = "Connecting to the relay..."; com.zombiecraft.game.Game.joinCode = null; host(localPort); return; }
		code = randomCode();
		com.zombiecraft.game.Game.joinCode = code;
		// a tunnel points at one local port, 25565 by default: if the game had to take another, friends will not reach it
		if (localPort != 25565) status = "Warning: port 25565 was busy, so the game opened on " + localPort + " and your tunnel will not reach it. Close whatever uses 25565 (another game or server) and host again.";
		ZombiecraftMod.LOG.info("Block Ops 2 online: join code {} (friends connect to {})", code, joinHost());
	}

	private static Socket open(String relay) throws IOException {
		int i = relay.lastIndexOf(':');
		String host = i < 0 ? relay : relay.substring(0, i);
		int port = i < 0 ? 25565 : Integer.parseInt(relay.substring(i + 1));
		Socket s = new Socket();
		s.connect(new java.net.InetSocketAddress(host, port), 8000);
		s.setTcpNoDelay(true);
		return s;
	}

	private static void send(Socket s, String line) throws IOException {
		OutputStream o = s.getOutputStream();
		o.write((line + "\n").getBytes(StandardCharsets.US_ASCII));
		o.flush();
	}

	/** One text line without reading past it (the bytes after it belong to Minecraft). */
	private static String line(InputStream in) throws IOException {
		StringBuilder b = new StringBuilder();
		int c;
		while ((c = in.read()) >= 0 && c != '\n') { if (b.length() > 200) break; b.append((char) c); }
		return c < 0 && b.isEmpty() ? null : b.toString().trim();
	}

	private static void pipe(Socket a, Socket b) {
		for (int dir = 0; dir < 2; dir++) {
			Socket from = dir == 0 ? a : b, to = dir == 0 ? b : a;
			Thread t = new Thread(() -> {
				try { from.getInputStream().transferTo(to.getOutputStream()); } catch (IOException ignored) {}
				try { a.close(); } catch (IOException ignored) {}
				try { b.close(); } catch (IOException ignored) {}
			}, "zc-relay-pipe");
			t.setDaemon(true);
			t.start();
		}
	}

	// ------------------------------------------------------------------ host
	/** Registers this world (open on {@code localPort}) with the relay; {@link #code} fills in when it answers. */
	static void host(int localPort) {
		String relay = configured();
		if (relay == null) return;
		hosting = true;
		code = null;
		Thread t = new Thread(() -> {
			while (hosting) {
				try (Socket c = open(relay)) {
					control = c;
					send(c, "HOST");
					InputStream in = c.getInputStream();
					String l;
					while (hosting && (l = line(in)) != null) {
						if (l.startsWith("CODE ")) { code = l.substring(5); status = ""; ZombiecraftMod.LOG.info("Block Ops 2 relay: join code {}", code); }
						else if (l.startsWith("CONN ")) accept(relay, l.substring(5), localPort);
					}
				} catch (IOException | RuntimeException e) {
					ZombiecraftMod.LOG.warn("Block Ops 2 relay: {}", e.toString());
				}
				code = null;
				if (hosting) try { Thread.sleep(5000); } catch (InterruptedException ignored) {}
			}
		}, "zc-relay-host");
		t.setDaemon(true);
		t.start();
	}

	private static void accept(String relay, String id, int localPort) {
		Thread t = new Thread(() -> {
			try {
				Socket data = open(relay);
				send(data, "DATA " + id);
				Socket local = new Socket(InetAddress.getLoopbackAddress(), localPort);
				local.setTcpNoDelay(true);
				pipe(data, local);
			} catch (IOException e) {
				ZombiecraftMod.LOG.warn("Block Ops 2 relay: could not carry a player: {}", e.toString());
			}
		}, "zc-relay-accept");
		t.setDaemon(true);
		t.start();
	}

	static void stop() {
		hosting = false;
		code = null;
		status = "";
		joinCode = null;
		com.zombiecraft.game.Game.joinCode = null;
		Socket c = control;
		if (c != null) try { c.close(); } catch (IOException ignored) {}
	}

	// ------------------------------------------------------------------ joiner
	/** Starts a loopback forwarder for {@code joinCode} and returns its port; connect Minecraft to 127.0.0.1:port. */
	static int forward(String joinCode) throws IOException {
		String relay = configured();
		if (relay == null) throw new IOException("no relay configured");
		lastError = null;
		if (forwarder != null) try { forwarder.close(); } catch (IOException ignored) {}
		ServerSocket ss = forwarder = new ServerSocket(0, 4, InetAddress.getLoopbackAddress());
		Thread t = new Thread(() -> {
			try {
				while (true) {
					Socket mc = ss.accept();
					Thread h = new Thread(() -> {
						try {
							Socket r = open(relay);
							send(r, "JOIN " + joinCode.toUpperCase());
							String ans = line(r.getInputStream());
							if (ans == null || !ans.equals("OK")) {
								lastError = ans != null && ans.startsWith("ERR ") ? "The relay says: " + ans.substring(4) + "." : "The relay closed the connection.";
								r.close(); mc.close(); return;
							}
							mc.setTcpNoDelay(true);
							pipe(mc, r);
						} catch (IOException e) {
							lastError = "Could not reach the relay (" + e.getMessage() + ").";
							try { mc.close(); } catch (IOException ignored) {}
						}
					}, "zc-relay-join");
					h.setDaemon(true);
					h.start();
				}
			} catch (IOException ignored) {}
		}, "zc-relay-forward");
		t.setDaemon(true);
		t.start();
		return ss.getLocalPort();
	}
}

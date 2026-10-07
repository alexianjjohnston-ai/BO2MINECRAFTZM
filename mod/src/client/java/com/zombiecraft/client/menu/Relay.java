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

	/** A join code is 6 letters/digits (relay) or 10 base-32 characters, optionally with a dash (the address itself); anything with a dot or colon is an address. */
	static boolean looksLikeCode(String s) { return s.matches("[A-Za-z0-9]{6}|[A-Za-z2-7]{5}-?[A-Za-z2-7]{5}"); }

	private static final String B32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

	/** "1.2.3.4" + port -> "XXXXX-XXXXX" (6 bytes in 10 base-32 characters). */
	static String encode(String ip, int port) {
		long v = 0;
		for (String part : ip.split("\\.")) v = v << 8 | Integer.parseInt(part);
		v = v << 16 | port;
		v <<= 2; // 48 bits -> 50 bits
		StringBuilder b = new StringBuilder();
		for (int i = 9; i >= 0; i--) b.append(B32.charAt((int) (v >> (5 * i) & 31)));
		return b.insert(5, '-').toString();
	}

	/** The inverse of {@link #encode}: "ip:port". */
	static String decode(String code) {
		String s = code.replace("-", "").toUpperCase();
		long v = 0;
		for (int i = 0; i < 10; i++) v = v << 5 | B32.indexOf(s.charAt(i));
		v >>= 2;
		int port = (int) (v & 0xFFFF);
		long ip = v >> 16;
		return (ip >> 24 & 255) + "." + (ip >> 16 & 255) + "." + (ip >> 8 & 255) + "." + (ip & 255) + ":" + port;
	}

	/** What to connect to for a typed join code: the address inside a 10-character code, or a loopback forwarder through the relay for a 6-character one. Null when it is not a code. */
	static String resolveJoin(String input) throws IOException {
		String a = input.trim();
		if (!looksLikeCode(a)) return null;
		if (a.replace("-", "").length() == 10) return decode(a);
		if (configured() == null) throw new IOException("That code needs a relay (relay=host:port in config/zombiecraft.properties). Codes from the lobby have 10 characters.");
		return "127.0.0.1:" + forward(a);
	}

	/**
	 * Makes the world on {@code localPort} reachable and fills in {@link #code}: through the relay when one is configured, else the router is asked to
	 * open the port (UPnP) and the code is the public address itself. {@code publicAddress=host:port} in the config (a playit.gg tunnel, a forwarded port)
	 * replaces the router step. {@link #status} says what is happening or what went wrong.
	 */
	static void expose(int localPort) {
		code = null;
		if (configured() != null) { status = "Connecting to the relay..."; host(localPort); return; }
		status = "Opening a port on your router...";
		Thread t = new Thread(() -> {
			try {
				String pub = prop("publicAddress"), ip;
				int port = localPort;
				if (pub != null) {
					int i = pub.lastIndexOf(':');
					ip = InetAddress.getByName(i < 0 ? pub : pub.substring(0, i)).getHostAddress();
					port = i < 0 ? localPort : Integer.parseInt(pub.substring(i + 1));
				} else {
					ip = Upnp.open(localPort);
					if (Upnp.isPrivate(ip)) throw new IOException("Your internet provider shares one public address between customers, so friends cannot reach you directly. Use a tunnel such as playit.gg and put its address in config/zombiecraft.properties as publicAddress=host:port.");
				}
				if (!ip.matches("\\d+\\.\\d+\\.\\d+\\.\\d+")) throw new IOException("The public address must be IPv4 (got " + ip + ").");
				code = encode(ip, port);
				status = "";
				ZombiecraftMod.LOG.info("Block Ops 2 online: join code {} = {}:{}", code, ip, port);
			} catch (IOException | RuntimeException e) {
				status = e.getMessage() == null ? e.toString() : e.getMessage();
				ZombiecraftMod.LOG.warn("Block Ops 2 online: no join code: {}", status);
			}
		}, "zc-expose");
		t.setDaemon(true);
		t.start();
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
		Upnp.close();
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

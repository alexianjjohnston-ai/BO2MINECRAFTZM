package com.zombiecraft.client.menu;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.IOException;
import java.io.InputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.nio.charset.StandardCharsets;

/**
 * Asks the home router (UPnP Internet Gateway Device) to forward a TCP port to this PC and tells the public address. No libraries: SSDP discovery,
 * the device description, then SOAP calls. Most home routers allow it; some switch it off, and ISPs that share one address between customers (CGNAT) can't.
 */
final class Upnp {
	private Upnp() {}

	private static String control, service;
	private static int mappedPort;

	/** Forwards {@code port} (TCP, same port outside) and returns the router's public IPv4 address. */
	static String open(int port) throws IOException {
		discover();
		String local = localAddress();
		IOException err = null;
		for (int lease : new int[] {0, 7200}) { // some routers only take timed leases
			try {
				soap("AddPortMapping", "<NewRemoteHost></NewRemoteHost><NewExternalPort>" + port + "</NewExternalPort><NewProtocol>TCP</NewProtocol>"
						+ "<NewInternalPort>" + port + "</NewInternalPort><NewInternalClient>" + local + "</NewInternalClient><NewEnabled>1</NewEnabled>"
						+ "<NewPortMappingDescription>Block Ops 2</NewPortMappingDescription><NewLeaseDuration>" + lease + "</NewLeaseDuration>");
				err = null;
				break;
			} catch (IOException e) { err = e; }
		}
		if (err != null) throw new IOException("The router refused to open the port (" + err.getMessage() + ").");
		mappedPort = port;
		String reply = soap("GetExternalIPAddress", "");
		int a = reply.indexOf("<NewExternalIPAddress>"), b = reply.indexOf("</NewExternalIPAddress>");
		if (a < 0 || b < 0) throw new IOException("The router did not tell its public address.");
		return reply.substring(a + "<NewExternalIPAddress>".length(), b).trim();
	}

	static void close() {
		if (mappedPort == 0 || control == null) return;
		int p = mappedPort;
		mappedPort = 0;
		try { soap("DeletePortMapping", "<NewRemoteHost></NewRemoteHost><NewExternalPort>" + p + "</NewExternalPort><NewProtocol>TCP</NewProtocol>"); } catch (IOException ignored) {}
	}

	private static void discover() throws IOException {
		String location = null;
		for (String st : new String[] {"urn:schemas-upnp-org:device:InternetGatewayDevice:1", "urn:schemas-upnp-org:device:InternetGatewayDevice:2", "upnp:rootdevice"}) {
			String msg = "M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\nMX: 2\r\nST: " + st + "\r\n\r\n";
			try (DatagramSocket s = new DatagramSocket()) {
				s.setSoTimeout(2500);
				byte[] out = msg.getBytes(StandardCharsets.US_ASCII);
				s.send(new DatagramPacket(out, out.length, new InetSocketAddress("239.255.255.250", 1900)));
				long end = System.currentTimeMillis() + 2500;
				while (location == null && System.currentTimeMillis() < end) {
					DatagramPacket in = new DatagramPacket(new byte[2048], 2048);
					try { s.receive(in); } catch (SocketTimeoutException e) { break; }
					for (String l : new String(in.getData(), 0, in.getLength(), StandardCharsets.US_ASCII).split("\r\n"))
						if (l.regionMatches(true, 0, "location:", 0, 9)) { String loc = l.substring(9).trim(); if (describe(loc)) { location = loc; break; } }
				}
			}
			if (location != null) return;
		}
		throw new IOException("No router that allows automatic port opening was found (UPnP is off or unsupported).");
	}

	/** Reads the device description; true when it has an IP/PPP connection service (then control and service are set). */
	private static boolean describe(String location) {
		try {
			HttpURLConnection c = (HttpURLConnection) URI.create(location).toURL().openConnection();
			c.setConnectTimeout(3000); c.setReadTimeout(3000);
			DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
			f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
			Document d;
			try (InputStream in = c.getInputStream()) { d = f.newDocumentBuilder().parse(in); }
			NodeList list = d.getElementsByTagName("service");
			for (int i = 0; i < list.getLength(); i++) {
				Element e = (Element) list.item(i);
				String type = text(e, "serviceType");
				if (type != null && (type.contains("WANIPConnection") || type.contains("WANPPPConnection"))) {
					String url = text(e, "controlURL");
					if (url == null) continue;
					control = URI.create(location).resolve(url).toString();
					service = type;
					return true;
				}
			}
		} catch (Exception ignored) {}
		return false;
	}

	private static String text(Element e, String tag) {
		NodeList n = e.getElementsByTagName(tag);
		return n.getLength() == 0 ? null : n.item(0).getTextContent().trim();
	}

	/** The address of this PC on the network that reaches the router. */
	private static String localAddress() throws IOException {
		URI u = URI.create(control);
		try (Socket s = new Socket()) {
			s.connect(new InetSocketAddress(u.getHost(), u.getPort() < 0 ? 80 : u.getPort()), 3000);
			return ((InetSocketAddress) s.getLocalSocketAddress()).getAddress().getHostAddress();
		}
	}

	private static String soap(String action, String args) throws IOException {
		String body = "<?xml version=\"1.0\"?><s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">"
				+ "<s:Body><u:" + action + " xmlns:u=\"" + service + "\">" + args + "</u:" + action + "></s:Body></s:Envelope>";
		HttpURLConnection c = (HttpURLConnection) URI.create(control).toURL().openConnection();
		c.setConnectTimeout(3000); c.setReadTimeout(5000);
		c.setRequestMethod("POST");
		c.setDoOutput(true);
		c.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"");
		c.setRequestProperty("SOAPAction", "\"" + service + "#" + action + "\"");
		c.getOutputStream().write(body.getBytes(StandardCharsets.UTF_8));
		int code = c.getResponseCode();
		try (InputStream in = code < 400 ? c.getInputStream() : c.getErrorStream()) {
			String r = in == null ? "" : new String(in.readAllBytes(), StandardCharsets.UTF_8);
			if (code >= 400) {
				int a = r.indexOf("<errorDescription>"), b = r.indexOf("</errorDescription>");
				throw new IOException(a >= 0 && b > a ? r.substring(a + 18, b) : "HTTP " + code);
			}
			return r;
		}
	}

	/** True for addresses that are not reachable from the internet (home networks and carrier-grade NAT). */
	static boolean isPrivate(String ip) {
		try {
			InetAddress a = InetAddress.getByName(ip);
			byte[] b = a.getAddress();
			return a.isSiteLocalAddress() || a.isLoopbackAddress() || a.isLinkLocalAddress() || a.isAnyLocalAddress()
					|| (b.length == 4 && (b[0] & 255) == 100 && (b[1] & 192) == 64);
		} catch (IOException e) { return true; }
	}
}

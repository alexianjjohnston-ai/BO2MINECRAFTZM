package com.zombiecraft.client.net;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;

import java.util.List;

/**
 * Tunnels such as playit.gg put a PROXY protocol header (v2 binary or v1 text) in front of every connection they forward. Vanilla Minecraft cannot read
 * it and drops the player before the handshake, so a world opened to LAN behind such a tunnel looks dead. This handler sits first in the server's
 * connection pipeline, swallows a header when there is one, and gets out of the way; ordinary connections pass through untouched.
 */
public final class ProxyProtocolDecoder extends ByteToMessageDecoder {
	private static final byte[] V2 = {0x0D, 0x0A, 0x0D, 0x0A, 0x00, 0x0D, 0x0A, 0x51, 0x55, 0x49, 0x54, 0x0A};
	private static final byte[] V1 = {'P', 'R', 'O', 'X', 'Y', ' '};

	@Override
	protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
		boolean v2 = matches(in, V2), v1 = matches(in, V1);
		if (!v2 && !v1) { ctx.pipeline().remove(this); return; } // not a proxy header: hand everything on
		if (v2) {
			if (in.readableBytes() < 16) return;
			int len = in.getUnsignedShort(in.readerIndex() + 14);
			if (in.readableBytes() < 16 + len) return;
			in.skipBytes(16 + len);
		} else {
			int end = -1, start = in.readerIndex();
			for (int i = start; i + 1 < in.writerIndex() && i - start < 108; i++)
				if (in.getByte(i) == '\r' && in.getByte(i + 1) == '\n') { end = i + 2; break; }
			if (end < 0) { if (in.readableBytes() >= 108) ctx.close(); return; }
			in.readerIndex(end);
		}
		ctx.pipeline().remove(this);
	}

	/** True while the bytes seen so far are a prefix of the signature and, once enough have arrived, the whole signature. */
	private static boolean matches(ByteBuf in, byte[] sig) {
		int n = Math.min(in.readableBytes(), sig.length);
		if (n == 0) return true; // nothing yet: wait
		for (int i = 0; i < n; i++) if (in.getByte(in.readerIndex() + i) != sig[i]) return false;
		return true;
	}
}

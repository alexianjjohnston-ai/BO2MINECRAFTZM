package com.zombiecraft.audio;

import java.io.ByteArrayOutputStream;
import java.security.MessageDigest;

/**
 * A small FLAC decoder (written from the public FLAC format specification, no third-party code).
 * Decodes a whole FLAC stream held in memory to interleaved 16-bit PCM and checks the result against the
 * MD5 of the unencoded audio that every FLAC stream carries in its STREAMINFO block.
 */
public final class FlacDecoder {
	private FlacDecoder() {}

	public static final class Result {
		public int channels, sampleRate, bitsPerSample;
		public long totalSamples;
		public short[] pcm;          // interleaved, 16-bit
		public boolean md5Checked, md5Ok;
		public int frames, crcBad;
	}

	private static final class Bits {
		final byte[] d; int pos; // bit position
		Bits(byte[] d, int bytePos) { this.d = d; this.pos = bytePos * 8; }
		int bit() { int b = (d[pos >> 3] >> (7 - (pos & 7))) & 1; pos++; return b; }
		long uint(int n) { long v = 0; for (int i = 0; i < n; i++) v = (v << 1) | bit(); return v; }
		int sint(int n) { if (n == 0) return 0; long v = uint(n); return (int) (v << (64 - n) >> (64 - n)); }
		int unary() { int n = 0; while (bit() == 0) n++; return n; }
		void align() { pos = (pos + 7) & ~7; }
		int bytePos() { return pos >> 3; }
		boolean eof() { return (pos >> 3) >= d.length; }
	}

	public static Result decode(byte[] f) {
		if (f.length < 42 || f[0] != 'f' || f[1] != 'L' || f[2] != 'a' || f[3] != 'C') throw new IllegalArgumentException("not a FLAC stream");
		Result r = new Result();
		int p = 4;
		byte[] md5 = null;
		boolean last = false;
		while (!last) {
			int h = f[p] & 0xff; last = (h & 0x80) != 0; int type = h & 0x7f;
			int len = ((f[p + 1] & 0xff) << 16) | ((f[p + 2] & 0xff) << 8) | (f[p + 3] & 0xff);
			if (type == 0) {
				Bits b = new Bits(f, p + 4);
				b.uint(16); b.uint(16); b.uint(24); b.uint(24);
				r.sampleRate = (int) b.uint(20); r.channels = (int) b.uint(3) + 1; r.bitsPerSample = (int) b.uint(5) + 1; r.totalSamples = b.uint(36);
				md5 = new byte[16];
				for (int i = 0; i < 16; i++) md5[i] = (byte) b.uint(8);
			}
			p += 4 + len;
		}
		if (r.channels == 0) throw new IllegalArgumentException("FLAC stream has no STREAMINFO");
		int total = (int) r.totalSamples;
		int[][] ch = new int[r.channels][total];
		Bits b = new Bits(f, p);
		int written = 0;
		while (written < total && !b.eof()) {
			int n = frame(b, r, ch, written);
			if (n <= 0) break;
			written += n;
		}
		if (written < total) throw new IllegalStateException("FLAC ended early: " + written + " of " + total + " samples");

		// interleave to 16-bit
		int shiftDown = Math.max(0, r.bitsPerSample - 16), shiftUp = Math.max(0, 16 - r.bitsPerSample);
		r.pcm = new short[total * r.channels];
		for (int i = 0; i < total; i++)
			for (int c = 0; c < r.channels; c++) {
				int v = ch[c][i];
				r.pcm[i * r.channels + c] = (short) (shiftDown > 0 ? v >> shiftDown : v << shiftUp);
			}

		// MD5 of the unencoded audio: signed little-endian, ceil(bps/8) bytes per sample
		if (md5 != null) {
			try {
				int bytes = (r.bitsPerSample + 7) / 8;
				MessageDigest md = MessageDigest.getInstance("MD5");
				ByteArrayOutputStream bo = new ByteArrayOutputStream(total * r.channels * bytes);
				for (int i = 0; i < total; i++)
					for (int c = 0; c < r.channels; c++) { int v = ch[c][i]; for (int k = 0; k < bytes; k++) bo.write(v >> (8 * k)); }
				byte[] got = md.digest(bo.toByteArray());
				boolean zero = true; for (byte x : md5) if (x != 0) zero = false;
				if (!zero) { r.md5Checked = true; r.md5Ok = java.util.Arrays.equals(got, md5); }
			} catch (java.security.NoSuchAlgorithmException e) { /* not checked */ }
		}
		return r;
	}

	/** Decodes one frame into ch[][] starting at 'at'. Returns its block size. */
	private static int frame(Bits b, Result r, int[][] ch, int at) {
		int startByte = b.bytePos();
		int sync = (int) b.uint(14);
		if (sync != 0x3FFE) return -1;
		b.bit(); b.bit(); // reserved, blocking strategy
		int bsCode = (int) b.uint(4), srCode = (int) b.uint(4), chAssign = (int) b.uint(4), ssCode = (int) b.uint(3);
		b.bit();
		// UTF-8 style frame/sample number
		int first = (int) b.uint(8);
		int extra = first < 0x80 ? 0 : first < 0xE0 ? 1 : first < 0xF0 ? 2 : first < 0xF8 ? 3 : first < 0xFC ? 4 : first < 0xFE ? 5 : 6;
		for (int i = 0; i < extra; i++) b.uint(8);
		int blockSize;
		if (bsCode == 1) blockSize = 192;
		else if (bsCode >= 2 && bsCode <= 5) blockSize = 576 << (bsCode - 2);
		else if (bsCode == 6) blockSize = (int) b.uint(8) + 1;
		else if (bsCode == 7) blockSize = (int) b.uint(16) + 1;
		else if (bsCode >= 8) blockSize = 256 << (bsCode - 8);
		else throw new IllegalStateException("reserved FLAC block size");
		if (srCode == 12) b.uint(8); else if (srCode == 13 || srCode == 14) b.uint(16);
		b.uint(8); // CRC-8
		int bps = switch (ssCode) { case 0 -> r.bitsPerSample; case 1 -> 8; case 2 -> 12; case 4 -> 16; case 5 -> 20; case 6 -> 24; case 7 -> 32; default -> throw new IllegalStateException("reserved sample size"); };
		int nch = chAssign < 8 ? chAssign + 1 : 2;
		int[][] sub = new int[nch][blockSize];
		for (int c = 0; c < nch; c++) {
			int sbps = bps;
			if ((chAssign == 8 && c == 1) || (chAssign == 9 && c == 0) || (chAssign == 10 && c == 1)) sbps++;
			subframe(b, sub[c], blockSize, sbps);
		}
		b.align();
		int endByte = b.bytePos();
		int crcStored = (int) b.uint(16);
		r.frames++;
		if (crc16(b.d, startByte, endByte) != crcStored) r.crcBad++;
		int n = Math.min(blockSize, ch[0].length - at);
		for (int i = 0; i < n; i++) {
			switch (chAssign) {
				case 8 -> { int l = sub[0][i], s = sub[1][i]; ch[0][at + i] = l; ch[1][at + i] = l - s; }
				case 9 -> { int s = sub[0][i], rr = sub[1][i]; ch[0][at + i] = rr + s; ch[1][at + i] = rr; }
				case 10 -> { int m = sub[0][i], s = sub[1][i]; int mm = (m << 1) | (s & 1); ch[0][at + i] = (mm + s) >> 1; ch[1][at + i] = (mm - s) >> 1; }
				default -> { for (int c = 0; c < nch; c++) ch[c][at + i] = sub[c][i]; }
			}
		}
		return blockSize;
	}

	private static int crc16(byte[] d, int from, int to) {
		int crc = 0;
		for (int i = from; i < to; i++) {
			crc ^= (d[i] & 0xff) << 8;
			for (int k = 0; k < 8; k++) crc = (crc & 0x8000) != 0 ? ((crc << 1) ^ 0x8005) & 0xffff : (crc << 1) & 0xffff;
		}
		return crc;
	}

	private static void subframe(Bits b, int[] out, int n, int bps) {
		if (b.bit() != 0) throw new IllegalStateException("bad subframe padding");
		int type = (int) b.uint(6);
		int wasted = 0;
		if (b.bit() == 1) wasted = b.unary() + 1;
		bps -= wasted;
		if (type == 0) { int v = b.sint(bps); java.util.Arrays.fill(out, v); }
		else if (type == 1) { for (int i = 0; i < n; i++) out[i] = b.sint(bps); }
		else if (type >= 8 && type <= 12) {
			int order = type - 8;
			for (int i = 0; i < order; i++) out[i] = b.sint(bps);
			int[] res = residual(b, n, order);
			for (int i = order; i < n; i++) {
				long pred = switch (order) {
					case 0 -> 0;
					case 1 -> out[i - 1];
					case 2 -> 2L * out[i - 1] - out[i - 2];
					case 3 -> 3L * out[i - 1] - 3L * out[i - 2] + out[i - 3];
					default -> 4L * out[i - 1] - 6L * out[i - 2] + 4L * out[i - 3] - out[i - 4];
				};
				out[i] = (int) (pred + res[i - order]);
			}
		} else if (type >= 32) {
			int order = (type & 31) + 1;
			for (int i = 0; i < order; i++) out[i] = b.sint(bps);
			int prec = (int) b.uint(4) + 1;
			int shift = b.sint(5);
			int[] coef = new int[order];
			for (int i = 0; i < order; i++) coef[i] = b.sint(prec);
			int[] res = residual(b, n, order);
			for (int i = order; i < n; i++) {
				long sum = 0;
				for (int j = 0; j < order; j++) sum += (long) coef[j] * out[i - 1 - j];
				out[i] = (int) ((sum >> shift) + res[i - order]);
			}
		} else throw new IllegalStateException("reserved subframe type " + type);
		if (wasted > 0) for (int i = 0; i < n; i++) out[i] <<= wasted;
	}

	private static int[] residual(Bits b, int n, int predOrder) {
		int method = (int) b.uint(2);
		int partOrder = (int) b.uint(4);
		int parts = 1 << partOrder;
		int paramBits = method == 0 ? 4 : 5, escape = method == 0 ? 15 : 31;
		int[] res = new int[n - predOrder];
		int idx = 0;
		for (int p = 0; p < parts; p++) {
			int count = (n >> partOrder) - (p == 0 ? predOrder : 0);
			int param = (int) b.uint(paramBits);
			if (param == escape) {
				int bits = (int) b.uint(5);
				for (int i = 0; i < count; i++) res[idx++] = b.sint(bits);
			} else {
				for (int i = 0; i < count; i++) {
					int q = b.unary();
					long v = ((long) q << param) | (param == 0 ? 0 : b.uint(param));
					res[idx++] = (int) ((v >>> 1) ^ -(v & 1));
				}
			}
		}
		return res;
	}
}

package com.zombiecraft.bo2;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;

/** Decodes the top mip of a DDS texture (DXT1/DXT3/DXT5, BC4/BC5 through DX10, 32 bit) to ARGB. */
public final class Dds {
	private Dds() {}

	public record Image(int width, int height, int[] argb) {}

	public static Image read(Path file) throws IOException { return decode(Files.readAllBytes(file)); }

	public static Image decode(byte[] data) throws IOException {
		ByteBuffer b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
		if (data.length < 128 || b.getInt(0) != 0x20534444) throw new IOException("not a DDS file");
		int h = b.getInt(12), w = b.getInt(16);
		int pfFlags = b.getInt(80), fourCC = b.getInt(84), bits = b.getInt(88);
		int rMask = b.getInt(92), gMask = b.getInt(96), bMask = b.getInt(100), aMask = b.getInt(104);
		int off = 128;
		int[] out = new int[w * h];
		if ((pfFlags & 4) != 0) {
			String cc = new String(new char[]{(char) (fourCC & 255), (char) (fourCC >> 8 & 255), (char) (fourCC >> 16 & 255), (char) (fourCC >> 24 & 255)});
			switch (cc) {
				case "DXT1" -> block(b, off, w, h, out, 8, 1);
				case "DXT3" -> block(b, off, w, h, out, 16, 3);
				case "DXT5" -> block(b, off, w, h, out, 16, 5);
				case "DX10" -> {
					int dxgi = b.getInt(128);
					off = 148;
					switch (dxgi) {
						case 71, 72 -> block(b, off, w, h, out, 8, 1);
						case 74, 75 -> block(b, off, w, h, out, 16, 3);
						case 77, 78 -> block(b, off, w, h, out, 16, 5);
						case 80, 81 -> block(b, off, w, h, out, 8, 4);
						case 83, 84 -> block(b, off, w, h, out, 16, 6);
						case 28, 29 -> raw(b, off, w, h, out, 32, 0xFF, 0xFF00, 0xFF0000, 0xFF000000);
						case 87, 91 -> raw(b, off, w, h, out, 32, 0xFF0000, 0xFF00, 0xFF, 0xFF000000);
						default -> throw new IOException("unsupported DXGI format " + dxgi);
					}
				}
				default -> throw new IOException("unsupported DDS format " + cc);
			}
		} else {
			raw(b, off, w, h, out, bits, rMask, gMask, bMask, (pfFlags & 1) != 0 ? aMask : 0);
		}
		return new Image(w, h, out);
	}

	private static void raw(ByteBuffer b, int off, int w, int h, int[] out, int bits, int rM, int gM, int bM, int aM) {
		int bytes = bits / 8;
		for (int i = 0; i < w * h; i++) {
			int p = off + i * bytes;
			int v = 0;
			for (int k = 0; k < bytes && p + k < b.limit(); k++) v |= (b.get(p + k) & 255) << (8 * k);
			int r = ch(v, rM), g = ch(v, gM), bl = ch(v, bM), a = aM == 0 ? 255 : ch(v, aM);
			out[i] = a << 24 | r << 16 | g << 8 | bl;
		}
	}

	private static int ch(int v, int mask) {
		if (mask == 0) return 0;
		int shift = Integer.numberOfTrailingZeros(mask);
		int max = mask >>> shift;
		return Math.round(((v & mask) >>> shift) * 255f / max);
	}

	private static int rgb565(int c, int[] o) {
		o[0] = ((c >> 11) & 31) * 255 / 31; o[1] = ((c >> 5) & 63) * 255 / 63; o[2] = (c & 31) * 255 / 31;
		return c;
	}

	/** mode: 1 = DXT1, 3 = DXT3 (explicit alpha), 5 = DXT5 (interpolated alpha), 4 = BC4 (one channel), 6 = BC5 (two channels, normal map) */
	private static void block(ByteBuffer b, int off, int w, int h, int[] out, int blockBytes, int mode) {
		int bw = (w + 3) / 4, bh = (h + 3) / 4;
		int[] c0 = new int[3], c1 = new int[3];
		int[] pal = new int[4];
		int[] alpha = new int[16];
		for (int by = 0; by < bh; by++) {
			for (int bx = 0; bx < bw; bx++) {
				int p = off + (by * bw + bx) * blockBytes;
				if (p + blockBytes > b.limit()) return;
				if (mode == 4 || mode == 6) {
					int[] r = bc4(b, p), g = mode == 6 ? bc4(b, p + 8) : r;
					for (int i = 0; i < 16; i++) {
						int x = bx * 4 + (i & 3), y = by * 4 + (i >> 2);
						if (x >= w || y >= h) continue;
						int rr = r[i], gg = g[i];
						if (mode == 6) {
							// reconstruct a normal's z, show it as an ordinary colour
							float nx = rr / 127.5f - 1, ny = gg / 127.5f - 1;
							float nz = (float) Math.sqrt(Math.max(0, 1 - nx * nx - ny * ny));
							out[y * w + x] = 0xFF000000 | rr << 16 | gg << 8 | Math.round((nz * 0.5f + 0.5f) * 255);
						} else out[y * w + x] = 0xFF000000 | rr << 16 | rr << 8 | rr;
					}
					continue;
				}
				int cp = p;
				if (mode == 3) {
					for (int i = 0; i < 4; i++) {
						int row = b.getShort(p + i * 2) & 0xFFFF;
						for (int k = 0; k < 4; k++) alpha[i * 4 + k] = ((row >> (k * 4)) & 15) * 17;
					}
					cp = p + 8;
				} else if (mode == 5) {
					int[] a = bc4(b, p);
					System.arraycopy(a, 0, alpha, 0, 16);
					cp = p + 8;
				} else java.util.Arrays.fill(alpha, 255);
				int q0 = b.getShort(cp) & 0xFFFF, q1 = b.getShort(cp + 2) & 0xFFFF;
				rgb565(q0, c0); rgb565(q1, c1);
				pal[0] = 0xFF000000 | c0[0] << 16 | c0[1] << 8 | c0[2];
				pal[1] = 0xFF000000 | c1[0] << 16 | c1[1] << 8 | c1[2];
				if (q0 > q1 || mode != 1) {
					pal[2] = 0xFF000000 | ((2 * c0[0] + c1[0]) / 3) << 16 | ((2 * c0[1] + c1[1]) / 3) << 8 | (2 * c0[2] + c1[2]) / 3;
					pal[3] = 0xFF000000 | ((c0[0] + 2 * c1[0]) / 3) << 16 | ((c0[1] + 2 * c1[1]) / 3) << 8 | (c0[2] + 2 * c1[2]) / 3;
				} else {
					pal[2] = 0xFF000000 | ((c0[0] + c1[0]) / 2) << 16 | ((c0[1] + c1[1]) / 2) << 8 | (c0[2] + c1[2]) / 2;
					pal[3] = 0;     // transparent black
				}
				int bits = b.getInt(cp + 4);
				for (int i = 0; i < 16; i++) {
					int x = bx * 4 + (i & 3), y = by * 4 + (i >> 2);
					if (x >= w || y >= h) continue;
					int c = pal[(bits >>> (i * 2)) & 3];
					if (mode != 1) c = (c & 0x00FFFFFF) | alpha[i] << 24;
					out[y * w + x] = c;
				}
			}
		}
	}

	private static int[] bc4(ByteBuffer b, int p) {
		int a0 = b.get(p) & 255, a1 = b.get(p + 1) & 255;
		int[] pal = new int[8];
		pal[0] = a0; pal[1] = a1;
		if (a0 > a1) for (int i = 1; i < 7; i++) pal[i + 1] = ((7 - i) * a0 + i * a1) / 7;
		else { for (int i = 1; i < 5; i++) pal[i + 1] = ((5 - i) * a0 + i * a1) / 5; pal[6] = 0; pal[7] = 255; }
		long bits = 0;
		for (int i = 0; i < 6; i++) bits |= (long) (b.get(p + 2 + i) & 255) << (8 * i);
		int[] out = new int[16];
		for (int i = 0; i < 16; i++) out[i] = pal[(int) ((bits >>> (i * 3)) & 7)];
		return out;
	}
}

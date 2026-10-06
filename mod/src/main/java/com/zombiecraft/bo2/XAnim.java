package com.zombiecraft.bo2;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * A Black Ops II animation in the compiled xanim format (version 19) that OpenAssetTools' Unlinker writes out of the player's own install.
 * Layout follows OAT's CompiledXAnimLoader. Rotations are parent-relative quaternions, translations parent-relative offsets, in inches.
 */
public final class XAnim {
	public String name = "";
	public int numFrames;          // last frame index (frames are 0..numFrames)
	public boolean looped;
	public float frameRate;
	public String[] boneNames = new String[0];
	public Track[] tracks = new Track[0];
	/** Root motion (tag_origin delta), may be null. */
	public Track delta;
	public String[] notifyNames = new String[0];
	public float[] notifyTimes = new float[0];

	/** One bone: quaternion keys (x,y,z,w floats, or null = identity) and translation keys (or null = keep the model's offset). */
	public static final class Track {
		public int[] quatFrames;       // key frame numbers, ascending
		public float[] quat;           // 4 per key
		public int[] transFrames;
		public float[] trans;          // 3 per key
	}

	public float lengthSeconds() { return frameRate > 0 ? numFrames / frameRate : 0; }

	public static XAnim read(Path file) throws IOException {
		XAnim a = decode(Files.readAllBytes(file));
		a.name = file.getFileName().toString();
		return a;
	}

	public static XAnim decode(byte[] data) throws IOException {
		ByteBuffer b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
		int version = b.getShort() & 0xFFFF;
		if (version != 19) throw new IOException("xanim version " + version + " is not supported");
		XAnim a = new XAnim();
		int numFramesRaw = b.getShort() & 0xFFFF;
		int boneCount = b.getShort() & 0xFFFF;
		int flags = b.get() & 255;
		b.get();                                    // asset type
		a.frameRate = b.getShort() & 0xFFFF;
		boolean t6 = (flags & 0x80) != 0;
		a.looped = (flags & 1) != 0;
		boolean delta = (flags & 2) != 0, delta3d = t6 && (flags & 4) != 0;
		boolean streamable = !t6 && (flags & 8) != 0;
		int numLoopFrames = a.looped ? numFramesRaw + 1 : numFramesRaw;
		a.numFrames = numLoopFrames - 1;
		if (streamable) b.getFloat();
		boolean byteIdx = a.numFrames < 256;

		if (delta || delta3d) {
			Track d = new Track();
			if (delta3d) readQuat(b, d, numLoopFrames, byteIdx, false, false, true);
			else readQuat(b, d, numLoopFrames, byteIdx, false, true, true);
			readTrans(b, d, numLoopFrames, byteIdx);
			a.delta = d;
		}
		if (boneCount > 0) {
			int mask = (boneCount + 7) / 8;
			byte[] flip = new byte[mask], half = new byte[mask];
			b.get(flip); b.get(half);
			a.boneNames = new String[boneCount];
			for (int i = 0; i < boneCount; i++) a.boneNames[i] = cstring(b);
			a.tracks = new Track[boneCount];
			for (int i = 0; i < boneCount; i++) {
				Track t = new Track();
				boolean f = (flip[i / 8] & (1 << (i % 8))) != 0, h = (half[i / 8] & (1 << (i % 8))) != 0;
				readQuat(b, t, numLoopFrames, byteIdx, f, h, false);
				readTrans(b, t, numLoopFrames, byteIdx);
				a.tracks[i] = t;
			}
		}
		int notes = b.get() & 255;
		a.notifyNames = new String[notes]; a.notifyTimes = new float[notes];
		for (int i = 0; i < notes; i++) {
			a.notifyNames[i] = cstring(b);
			int frame = b.getShort() & 0xFFFF;
			a.notifyTimes[i] = a.numFrames > 0 ? frame / (float) a.numFrames : 0;
		}
		if (b.hasRemaining()) throw new IOException("xanim has " + b.remaining() + " unread bytes");
		return a;
	}

	private static String cstring(ByteBuffer b) {
		int start = b.position();
		while (b.get() != 0) { }
		return new String(b.array(), start, b.position() - start - 1, StandardCharsets.US_ASCII);
	}

	private static int[] indices(ByteBuffer b, int n, int numLoopFrames, boolean byteIdx) {
		int[] idx = new int[n];
		if (n >= numLoopFrames) { for (int i = 0; i < n; i++) idx[i] = i; }
		else if (byteIdx) { for (int i = 0; i < n; i++) idx[i] = b.get() & 255; }
		else { for (int i = 0; i < n; i++) idx[i] = b.getShort() & 0xFFFF; }
		return idx;
	}

	private static final float Q = 1f / 32767f;

	/** half = rotation about z only (2 stored ints: z, w); delta tracks never carry the per-bone flip bit. */
	private static void readQuat(ByteBuffer b, Track t, int numLoopFrames, boolean byteIdx, boolean flip, boolean half, boolean isDelta) {
		int n = b.getShort() & 0xFFFF;
		if (n == 0) { t.quatFrames = null; t.quat = null; return; }
		t.quatFrames = n == 1 ? new int[]{0} : indices(b, n, numLoopFrames, byteIdx);
		t.quat = new float[n * 4];
		int[] prev = null;
		for (int k = 0; k < n; k++) {
			int[] q = new int[4];
			if (half) {
				q[2] = b.getShort();
				q[3] = recon(q[2] * q[2]);
			} else {
				q[0] = b.getShort(); q[1] = b.getShort(); q[2] = b.getShort();
				q[3] = recon(q[0] * q[0] + q[1] * q[1] + q[2] * q[2]);
			}
			if (k > 0 && n > 1) {
				long dot = (long) prev[0] * q[0] + (long) prev[1] * q[1] + (long) prev[2] * q[2] + (long) prev[3] * q[3];
				if (dot < 0) for (int c = 0; c < 4; c++) q[c] = -q[c];
			} else if (flip && !isDelta) for (int c = 0; c < 4; c++) q[c] = -q[c];
			prev = q;
			float x = q[0] * Q, y = q[1] * Q, z = q[2] * Q, w = q[3] * Q;
			float len = (float) Math.sqrt(x * x + y * y + z * z + w * w);
			if (len > 0) { x /= len; y /= len; z /= len; w /= len; } else w = 1;
			t.quat[k * 4] = x; t.quat[k * 4 + 1] = y; t.quat[k * 4 + 2] = z; t.quat[k * 4 + 3] = w;
		}
	}

	private static int recon(int sumSq) {
		int temp = 0x3FFF0001 - sumSq;
		return temp <= 0 ? 0 : (int) Math.floor(Math.sqrt((float) temp) + 0.5f);
	}

	private static void readTrans(ByteBuffer b, Track t, int numLoopFrames, boolean byteIdx) {
		int n = b.getShort() & 0xFFFF;
		if (n == 0) { t.transFrames = null; t.trans = null; return; }
		if (n == 1) {
			t.transFrames = new int[]{0};
			t.trans = new float[]{b.getFloat(), b.getFloat(), b.getFloat()};
			return;
		}
		t.transFrames = indices(b, n, numLoopFrames, byteIdx);
		boolean small = b.get() != 0;
		float[] min = {b.getFloat(), b.getFloat(), b.getFloat()};
		float scale = small ? 0.003921568859368563f : 0.00001525902189314365f;
		float[] size = {b.getFloat() * scale, b.getFloat() * scale, b.getFloat() * scale};
		t.trans = new float[n * 3];
		for (int k = 0; k < n; k++)
			for (int c = 0; c < 3; c++) {
				int v = small ? (b.get() & 255) : (b.getShort() & 0xFFFF);
				t.trans[k * 3 + c] = min[c] + size[c] * v;
			}
	}

	/** Interpolated rotation of a track at a (fractional) frame into out[0..3]. Returns false when the track has no rotation. */
	public static boolean sampleQuat(Track t, float frame, float[] out) {
		if (t.quat == null) return false;
		int n = t.quatFrames.length;
		if (n == 1) { System.arraycopy(t.quat, 0, out, 0, 4); return true; }
		int k = seg(t.quatFrames, frame);
		int k1 = Math.min(n - 1, k + 1);
		float f0 = t.quatFrames[k], f1 = t.quatFrames[k1];
		float a = f1 > f0 ? Math.max(0, Math.min(1, (frame - f0) / (f1 - f0))) : 0;
		float x = t.quat[k * 4] * (1 - a) + t.quat[k1 * 4] * a, y = t.quat[k * 4 + 1] * (1 - a) + t.quat[k1 * 4 + 1] * a;
		float z = t.quat[k * 4 + 2] * (1 - a) + t.quat[k1 * 4 + 2] * a, w = t.quat[k * 4 + 3] * (1 - a) + t.quat[k1 * 4 + 3] * a;
		float len = (float) Math.sqrt(x * x + y * y + z * z + w * w);
		if (len < 1e-6f) { out[0] = out[1] = out[2] = 0; out[3] = 1; return true; }
		out[0] = x / len; out[1] = y / len; out[2] = z / len; out[3] = w / len;
		return true;
	}

	public static boolean sampleTrans(Track t, float frame, float[] out) {
		if (t.trans == null) return false;
		int n = t.transFrames.length;
		if (n == 1) { System.arraycopy(t.trans, 0, out, 0, 3); return true; }
		int k = seg(t.transFrames, frame);
		int k1 = Math.min(n - 1, k + 1);
		float f0 = t.transFrames[k], f1 = t.transFrames[k1];
		float a = f1 > f0 ? Math.max(0, Math.min(1, (frame - f0) / (f1 - f0))) : 0;
		for (int c = 0; c < 3; c++) out[c] = t.trans[k * 3 + c] * (1 - a) + t.trans[k1 * 3 + c] * a;
		return true;
	}

	private static int seg(int[] frames, float frame) {
		int lo = 0, hi = frames.length - 1;
		if (frame <= frames[0]) return 0;
		if (frame >= frames[hi]) return hi;
		while (hi - lo > 1) { int mid = (lo + hi) >>> 1; if (frames[mid] <= frame) lo = mid; else hi = mid; }
		return lo;
	}
}

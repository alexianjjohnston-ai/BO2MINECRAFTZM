package com.zombiecraft.bo2;

import java.util.HashMap;
import java.util.Map;

/**
 * Poses a skinned {@link XModel} with {@link XAnim}s: per-bone local rotation/translation, then world matrices, then skinning matrices
 * (world * inverse bind). Matrices are 3x4, row-major: r00 r01 r02 tx / r10 r11 r12 ty / r20 r21 r22 tz. All in BO2 space (inches, z up).
 */
public final class Pose {
	public final XModel model;
	final int n;
	final float[] bindQ, bindT;          // parent-relative bind pose
	final float[] invBind;               // inverse world bind, 12 per bone
	public final float[] localQ, localT; // current local pose
	public final float[] world, skin;    // 12 per bone
	private final Map<XAnim, int[]> trackMaps = new HashMap<>();

	public Pose(XModel m) {
		this.model = m; this.n = m.boneCount();
		bindQ = new float[n * 4]; bindT = new float[n * 3]; invBind = new float[n * 12];
		localQ = new float[n * 4]; localT = new float[n * 3];
		world = new float[n * 12]; skin = new float[n * 12];
		for (int i = 0; i < n; i++) {
			// world bind rotation: columns are the bone's X, Y, Z axes (rows of the export)
			float[] rw = worldRot(m, i);
			float[] pw = {m.bindPos[i * 3], m.bindPos[i * 3 + 1], m.bindPos[i * 3 + 2]};
			int p = m.parent[i];
			float[] lr, lt;
			if (p < 0) { lr = rw; lt = pw; }
			else {
				float[] rp = worldRot(m, p);
				lr = mul33(transpose(rp), rw);
				float[] d = {pw[0] - m.bindPos[p * 3], pw[1] - m.bindPos[p * 3 + 1], pw[2] - m.bindPos[p * 3 + 2]};
				lt = mul33v(transpose(rp), d);
			}
			float[] q = quatFrom(lr);
			System.arraycopy(q, 0, bindQ, i * 4, 4);
			System.arraycopy(lt, 0, bindT, i * 3, 3);
			// inverse world bind: R^T, -R^T p
			float[] rt = transpose(rw);
			float[] ip = mul33v(rt, pw);
			for (int r = 0; r < 3; r++) {
				for (int c = 0; c < 3; c++) invBind[i * 12 + r * 4 + c] = rt[r * 3 + c];
				invBind[i * 12 + r * 4 + 3] = -ip[r];
			}
		}
		reset();
	}

	private static float[] worldRot(XModel m, int i) {
		float[] r = new float[9];
		for (int axis = 0; axis < 3; axis++)
			for (int k = 0; k < 3; k++) r[k * 3 + axis] = m.bindRot[i * 9 + axis * 3 + k];
		return orthonormalise(r);
	}

	/** Back to the bind pose. */
	public void reset() {
		System.arraycopy(bindQ, 0, localQ, 0, bindQ.length);
		System.arraycopy(bindT, 0, localT, 0, bindT.length);
	}

	private int[] map(XAnim a) {
		return trackMaps.computeIfAbsent(a, k -> {
			int[] t = new int[n];
			java.util.Arrays.fill(t, -1);
			for (int j = 0; j < k.boneNames.length; j++) {
				int b = model.boneIndex(k.boneNames[j]);
				if (b >= 0) t[b] = j;
			}
			return t;
		});
	}

	private final float[] tq = new float[4], tt = new float[3];

	/** Blends animation {@code a} at {@code frame} into the local pose with weight {@code w} (1 = replace). */
	public void apply(XAnim a, float frame, float w) {
		int[] t = map(a);
		for (int i = 0; i < n; i++) {
			int j = t[i];
			if (j < 0) continue;
			XAnim.Track tr = a.tracks[j];
			if (XAnim.sampleQuat(tr, frame, tq)) blendQ(i, tq, w);
			else { tq[0] = tq[1] = tq[2] = 0; tq[3] = 1; blendQ(i, tq, w); }
			if (XAnim.sampleTrans(tr, frame, tt))
				for (int c = 0; c < 3; c++) localT[i * 3 + c] += (tt[c] - localT[i * 3 + c]) * w;
		}
	}

	private void blendQ(int i, float[] q, float w) {
		int o = i * 4;
		float dot = localQ[o] * q[0] + localQ[o + 1] * q[1] + localQ[o + 2] * q[2] + localQ[o + 3] * q[3];
		float s = dot < 0 ? -1 : 1;
		float x = localQ[o] + (q[0] * s - localQ[o]) * w, y = localQ[o + 1] + (q[1] * s - localQ[o + 1]) * w;
		float z = localQ[o + 2] + (q[2] * s - localQ[o + 2]) * w, ww = localQ[o + 3] + (q[3] * s - localQ[o + 3]) * w;
		float len = (float) Math.sqrt(x * x + y * y + z * z + ww * ww);
		if (len < 1e-6f) return;
		localQ[o] = x / len; localQ[o + 1] = y / len; localQ[o + 2] = z / len; localQ[o + 3] = ww / len;
	}

	/** Computes world and skinning matrices from the local pose. Parents always come before children in an export. */
	public void build() {
		float[] r = new float[9];
		for (int i = 0; i < n; i++) {
			quatToMat(localQ, i * 4, r);
			int o = i * 12, p = model.parent[i];
			float tx = localT[i * 3], ty = localT[i * 3 + 1], tz = localT[i * 3 + 2];
			if (p < 0) {
				for (int a = 0; a < 3; a++) {
					for (int b = 0; b < 3; b++) world[o + a * 4 + b] = r[a * 3 + b];
				}
				world[o + 3] = tx; world[o + 7] = ty; world[o + 11] = tz;
			} else {
				int po = p * 12;
				for (int a = 0; a < 3; a++) {
					for (int b = 0; b < 3; b++)
						world[o + a * 4 + b] = world[po + a * 4] * r[b] + world[po + a * 4 + 1] * r[3 + b] + world[po + a * 4 + 2] * r[6 + b];
					world[o + a * 4 + 3] = world[po + a * 4] * tx + world[po + a * 4 + 1] * ty + world[po + a * 4 + 2] * tz + world[po + a * 4 + 3];
				}
			}
			mul34(world, o, invBind, o, skin, o);
		}
	}

	/**
	 * Like {@link #build()} but for a part carried by another skeleton (a gun in the hands): every bone is placed relative to
	 * {@code parent}'s bone {@code tag} (its animated world matrix), e.g. the gun's j_gun on the hands' tag_weapon.
	 */
	public void buildOn(Pose parent, String tag) {
		build();
		int tb = parent.model.boneIndex(tag);
		if (tb < 0) return;
		float[] tmp = new float[12];
		for (int i = 0; i < n; i++) {
			mul34(parent.world, tb * 12, world, i * 12, tmp, 0);
			System.arraycopy(tmp, 0, world, i * 12, 12);
			mul34(world, i * 12, invBind, i * 12, skin, i * 12);
		}
	}

	/** Skins vertex v into out (3 floats), position only. */
	public void skinPos(int v, float[] out) {
		float x = model.pos[v * 3], y = model.pos[v * 3 + 1], z = model.pos[v * 3 + 2];
		float rx = 0, ry = 0, rz = 0;
		for (int k = 0; k < 4; k++) {
			float w = model.boneW[v * 4 + k];
			if (w <= 0) continue;
			int o = model.boneIdx[v * 4 + k] * 12;
			rx += w * (skin[o] * x + skin[o + 1] * y + skin[o + 2] * z + skin[o + 3]);
			ry += w * (skin[o + 4] * x + skin[o + 5] * y + skin[o + 6] * z + skin[o + 7]);
			rz += w * (skin[o + 8] * x + skin[o + 9] * y + skin[o + 10] * z + skin[o + 11]);
		}
		out[0] = rx; out[1] = ry; out[2] = rz;
	}

	/** Rotates a normal by the skinning of vertex v. */
	public void skinNormal(int v, float nx, float ny, float nz, float[] out) {
		float rx = 0, ry = 0, rz = 0;
		for (int k = 0; k < 4; k++) {
			float w = model.boneW[v * 4 + k];
			if (w <= 0) continue;
			int o = model.boneIdx[v * 4 + k] * 12;
			rx += w * (skin[o] * nx + skin[o + 1] * ny + skin[o + 2] * nz);
			ry += w * (skin[o + 4] * nx + skin[o + 5] * ny + skin[o + 6] * nz);
			rz += w * (skin[o + 8] * nx + skin[o + 9] * ny + skin[o + 10] * nz);
		}
		out[0] = rx; out[1] = ry; out[2] = rz;
	}

	/**
	 * For a separate part (head, hat) sharing bone names with a body: takes the body's world matrices for every shared bone,
	 * so the part sits exactly where the body's bones are, then rebuilds the skinning matrices.
	 */
	public void followWorld(Pose body) {
		float[] r = new float[9], local = new float[12];
		for (int i = 0; i < n; i++) {
			int b = body.model.boneIndex(model.boneNames[i]);
			if (b >= 0) System.arraycopy(body.world, b * 12, world, i * 12, 12);
			else if (model.parent[i] >= 0) {
				// own bone (jaw, hair...): re-attach to the possibly moved parent
				quatToMat(localQ, i * 4, r);
				for (int a = 0; a < 3; a++) { local[a * 4] = r[a * 3]; local[a * 4 + 1] = r[a * 3 + 1]; local[a * 4 + 2] = r[a * 3 + 2]; local[a * 4 + 3] = localT[i * 3 + a]; }
				mul34(world, model.parent[i] * 12, local, 0, world, i * 12);
			}
			mul34(world, i * 12, invBind, i * 12, skin, i * 12);
		}
	}

	// ---- small maths ----
	static void mul34(float[] a, int ao, float[] b, int bo, float[] out, int oo) {
		for (int r = 0; r < 3; r++) {
			float a0 = a[ao + r * 4], a1 = a[ao + r * 4 + 1], a2 = a[ao + r * 4 + 2], a3 = a[ao + r * 4 + 3];
			for (int c = 0; c < 4; c++) {
				float v = a0 * b[bo + c] + a1 * b[bo + 4 + c] + a2 * b[bo + 8 + c];
				if (c == 3) v += a3;
				out[oo + r * 4 + c] = v;
			}
		}
	}

	static float[] transpose(float[] m) { return new float[]{m[0], m[3], m[6], m[1], m[4], m[7], m[2], m[5], m[8]}; }

	static float[] mul33(float[] a, float[] b) {
		float[] o = new float[9];
		for (int r = 0; r < 3; r++) for (int c = 0; c < 3; c++) o[r * 3 + c] = a[r * 3] * b[c] + a[r * 3 + 1] * b[3 + c] + a[r * 3 + 2] * b[6 + c];
		return o;
	}

	static float[] mul33v(float[] a, float[] v) {
		return new float[]{a[0] * v[0] + a[1] * v[1] + a[2] * v[2], a[3] * v[0] + a[4] * v[1] + a[5] * v[2], a[6] * v[0] + a[7] * v[1] + a[8] * v[2]};
	}

	static float[] orthonormalise(float[] m) {
		// Gram-Schmidt on the columns: exports carry rounding noise
		float[] x = {m[0], m[3], m[6]}, y = {m[1], m[4], m[7]};
		norm(x);
		float d = x[0] * y[0] + x[1] * y[1] + x[2] * y[2];
		y[0] -= d * x[0]; y[1] -= d * x[1]; y[2] -= d * x[2];
		norm(y);
		float[] z = {x[1] * y[2] - x[2] * y[1], x[2] * y[0] - x[0] * y[2], x[0] * y[1] - x[1] * y[0]};
		return new float[]{x[0], y[0], z[0], x[1], y[1], z[1], x[2], y[2], z[2]};
	}

	private static void norm(float[] v) {
		float l = (float) Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
		if (l > 0) { v[0] /= l; v[1] /= l; v[2] /= l; }
	}

	static float[] quatFrom(float[] m) {
		float tr = m[0] + m[4] + m[8];
		float x, y, z, w;
		if (tr > 0) {
			float s = (float) Math.sqrt(tr + 1) * 2;
			w = 0.25f * s; x = (m[7] - m[5]) / s; y = (m[2] - m[6]) / s; z = (m[3] - m[1]) / s;
		} else if (m[0] > m[4] && m[0] > m[8]) {
			float s = (float) Math.sqrt(1 + m[0] - m[4] - m[8]) * 2;
			w = (m[7] - m[5]) / s; x = 0.25f * s; y = (m[1] + m[3]) / s; z = (m[2] + m[6]) / s;
		} else if (m[4] > m[8]) {
			float s = (float) Math.sqrt(1 + m[4] - m[0] - m[8]) * 2;
			w = (m[2] - m[6]) / s; x = (m[1] + m[3]) / s; y = 0.25f * s; z = (m[5] + m[7]) / s;
		} else {
			float s = (float) Math.sqrt(1 + m[8] - m[0] - m[4]) * 2;
			w = (m[3] - m[1]) / s; x = (m[2] + m[6]) / s; y = (m[5] + m[7]) / s; z = 0.25f * s;
		}
		float l = (float) Math.sqrt(x * x + y * y + z * z + w * w);
		return new float[]{x / l, y / l, z / l, w / l};
	}

	static void quatToMat(float[] q, int o, float[] r) {
		float x = q[o], y = q[o + 1], z = q[o + 2], w = q[o + 3];
		r[0] = 1 - 2 * (y * y + z * z); r[1] = 2 * (x * y - z * w); r[2] = 2 * (x * z + y * w);
		r[3] = 2 * (x * y + z * w); r[4] = 1 - 2 * (x * x + z * z); r[5] = 2 * (y * z - x * w);
		r[6] = 2 * (x * z - y * w); r[7] = 2 * (y * z + x * w); r[8] = 1 - 2 * (x * x + y * y);
	}
}

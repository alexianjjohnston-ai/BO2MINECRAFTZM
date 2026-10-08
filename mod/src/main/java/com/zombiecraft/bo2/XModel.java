package com.zombiecraft.bo2;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * A Black Ops II model read from OpenAssetTools' XMODEL_EXPORT text format (the player's own data, converted on their PC).
 * Coordinates stay in BO2 space: inches, x forward, y left, z up. Bones carry a world-space bind pose.
 */
public final class XModel {
	public String name = "";
	public String[] boneNames = new String[0];
	public int[] parent = new int[0];
	/** World-space bind pose per bone: position (3) and rotation axes X, Y, Z (9, row-major). */
	public float[] bindPos = new float[0], bindRot = new float[0];

	public int vertCount;
	public float[] pos = new float[0];
	/** Up to 4 bone influences per vertex. */
	public int[] boneIdx = new int[0];
	public float[] boneW = new float[0];

	public final List<Material> materials = new ArrayList<>();
	public final List<Surface> surfaces = new ArrayList<>();

	public record Material(String name, String texture) {}

	/** Triangles sharing one material. Per corner: vertex index, normal (3), uv (2). */
	public static final class Surface {
		public int material;
		public int[] vert = new int[0];
		public float[] normal = new float[0], uv = new float[0];
		public int cornerCount() { return vert.length; }
	}

	private static final int MAGIC = 0x5A434D31;   // "ZCM1"

	/** Compact local cache format (gzip): everything the renderer needs, no text parsing at game start. */
	public void writeCompact(Path file) throws IOException {
		try (var out = new java.io.DataOutputStream(new java.io.BufferedOutputStream(new java.util.zip.GZIPOutputStream(Files.newOutputStream(file), 1 << 16)))) {
			out.writeInt(MAGIC);
			out.writeUTF(name);
			out.writeShort(boneNames.length);
			for (int i = 0; i < boneNames.length; i++) { out.writeUTF(boneNames[i]); out.writeShort(parent[i]); }
			for (float f : bindPos) out.writeFloat(f);
			for (float f : bindRot) out.writeFloat(f);
			out.writeInt(vertCount);
			for (float f : pos) out.writeFloat(f);
			for (int i = 0; i < vertCount * 4; i++) { out.writeByte(boneIdx[i]); out.writeFloat(boneW[i]); }
			out.writeShort(materials.size());
			for (Material m : materials) { out.writeUTF(m.name()); out.writeUTF(m.texture()); }
			out.writeShort(surfaces.size());
			for (Surface s : surfaces) {
				out.writeShort(s.material);
				out.writeInt(s.vert.length);
				for (int v : s.vert) out.writeInt(v);
				for (float f : s.normal) out.writeFloat(f);
				for (float f : s.uv) out.writeFloat(f);
			}
		}
	}

	public static XModel readCompact(Path file) throws IOException {
		try (var in = new java.io.DataInputStream(new java.io.BufferedInputStream(new java.util.zip.GZIPInputStream(Files.newInputStream(file), 1 << 16)))) {
			if (in.readInt() != MAGIC) throw new IOException("not a Zombiecraft model: " + file);
			XModel m = new XModel();
			m.name = in.readUTF();
			int nb = in.readUnsignedShort();
			m.boneNames = new String[nb]; m.parent = new int[nb];
			for (int i = 0; i < nb; i++) { m.boneNames[i] = in.readUTF(); m.parent[i] = in.readShort(); }
			m.bindPos = new float[nb * 3]; for (int i = 0; i < m.bindPos.length; i++) m.bindPos[i] = in.readFloat();
			m.bindRot = new float[nb * 9]; for (int i = 0; i < m.bindRot.length; i++) m.bindRot[i] = in.readFloat();
			m.vertCount = in.readInt();
			m.pos = new float[m.vertCount * 3]; for (int i = 0; i < m.pos.length; i++) m.pos[i] = in.readFloat();
			m.boneIdx = new int[m.vertCount * 4]; m.boneW = new float[m.vertCount * 4];
			for (int i = 0; i < m.vertCount * 4; i++) { m.boneIdx[i] = in.readUnsignedByte(); m.boneW[i] = in.readFloat(); }
			int nm = in.readUnsignedShort();
			for (int i = 0; i < nm; i++) m.materials.add(new Material(in.readUTF(), in.readUTF()));
			int ns = in.readUnsignedShort();
			for (int i = 0; i < ns; i++) {
				Surface s = new Surface();
				s.material = in.readUnsignedShort();
				int n = in.readInt();
				s.vert = new int[n]; for (int k = 0; k < n; k++) s.vert[k] = in.readInt();
				s.normal = new float[n * 3]; for (int k = 0; k < s.normal.length; k++) s.normal[k] = in.readFloat();
				s.uv = new float[n * 2]; for (int k = 0; k < s.uv.length; k++) s.uv[k] = in.readFloat();
				m.surfaces.add(s);
			}
			return m;
		}
	}

	public int boneCount() { return boneNames.length; }

	public int boneIndex(String n) {
		for (int i = 0; i < boneNames.length; i++) if (boneNames[i].equals(n)) return i;
		return -1;
	}

	public static XModel read(Path file) throws IOException {
		try (InputStream in = Files.newInputStream(file)) {
			XModel m = read(in);
			String f = file.getFileName().toString();
			m.name = f.replaceAll("\\.xmodel_export$", "");
			return m;
		}
	}

	private static String[] tok(String line) {
		// splits on spaces, but keeps "quoted names" together
		List<String> out = new ArrayList<>(6);
		int i = 0, n = line.length();
		while (i < n) {
			char c = line.charAt(i);
			if (c == ' ' || c == ',' || c == '\t' || c == '\r') { i++; continue; }
			if (c == '"') {
				int j = line.indexOf('"', i + 1);
				if (j < 0) j = n;
				out.add(line.substring(i + 1, j)); i = j + 1;
			} else {
				int j = i;
				while (j < n && " ,\t\r".indexOf(line.charAt(j)) < 0) j++;
				out.add(line.substring(i, j)); i = j;
			}
		}
		return out.toArray(new String[0]);
	}

	public static XModel read(InputStream in) throws IOException {
		XModel m = new XModel();
		BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8), 1 << 16);
		String line;
		int curBone = -1, curVert = -1;
		String section = "";
		int pendingVert = -1; float[] pendingNormal = null, pendingUv = null;
		java.util.Map<Integer, SurfBuilder> builders = new java.util.TreeMap<>();
		SurfBuilder sb = null;
		int influences = 0, infRead = 0;
		float[] tmpW = new float[16]; int[] tmpB = new int[16];

		while ((line = r.readLine()) != null) {
			if (line.isEmpty() || line.startsWith("//")) continue;
			String[] t = tok(line);
			if (t.length == 0) continue;
			switch (t[0]) {
				case "NUMBONES" -> {
					int n = Integer.parseInt(t[1]);
					m.boneNames = new String[n]; m.parent = new int[n];
					m.bindPos = new float[n * 3]; m.bindRot = new float[n * 9];
					section = "bonelist";
				}
				case "BONE" -> {
					if (section.equals("bonelist") && t.length >= 4) {
						int i = Integer.parseInt(t[1]);
						m.parent[i] = Integer.parseInt(t[2]); m.boneNames[i] = t[3];
					} else if (section.equals("bonepose")) {
						curBone = Integer.parseInt(t[1]);
					} else if (section.equals("verts")) {
						// "BONE b w" under a VERT
						tmpB[infRead] = Integer.parseInt(t[1]); tmpW[infRead] = Float.parseFloat(t[2]); infRead++;
						if (infRead == influences) assignInfluences(m, curVert, tmpB, tmpW, infRead);
					} else if (t.length == 2) {
						section = "bonepose"; curBone = Integer.parseInt(t[1]);
					}
				}
				case "OFFSET" -> {
					if (section.equals("bonepose") && curBone >= 0) {
						m.bindPos[curBone * 3] = Float.parseFloat(t[1]); m.bindPos[curBone * 3 + 1] = Float.parseFloat(t[2]); m.bindPos[curBone * 3 + 2] = Float.parseFloat(t[3]);
					} else if (section.equals("verts") && curVert >= 0) {
						m.pos[curVert * 3] = Float.parseFloat(t[1]); m.pos[curVert * 3 + 1] = Float.parseFloat(t[2]); m.pos[curVert * 3 + 2] = Float.parseFloat(t[3]);
					}
				}
				case "X", "Y", "Z" -> {
					if (section.equals("bonepose") && curBone >= 0) {
						int row = t[0].charAt(0) - 'X';
						for (int k = 0; k < 3; k++) m.bindRot[curBone * 9 + row * 3 + k] = Float.parseFloat(t[1 + k]);
					}
				}
				case "NUMVERTS" -> {
					m.vertCount = Integer.parseInt(t[1]);
					m.pos = new float[m.vertCount * 3];
					m.boneIdx = new int[m.vertCount * 4]; m.boneW = new float[m.vertCount * 4];
					section = "verts"; curVert = -1;
				}
				case "VERT" -> {
					int idx = Integer.parseInt(t[1]);
					if (section.equals("verts")) curVert = idx;
					else if (section.equals("faces")) {
						pendingVert = idx; pendingNormal = new float[3]; pendingUv = new float[2];
					}
				}
				case "BONES" -> { influences = Integer.parseInt(t[1]); infRead = 0; if (influences == 0) { m.boneIdx[curVert * 4] = 0; m.boneW[curVert * 4] = 1f; } }
				case "NUMFACES" -> section = "faces";
				case "TRI" -> {
					int mat = Integer.parseInt(t[2]);
					sb = builders.computeIfAbsent(mat, SurfBuilder::new);
				}
				case "NORMAL" -> { if (pendingNormal != null) { pendingNormal[0] = Float.parseFloat(t[1]); pendingNormal[1] = Float.parseFloat(t[2]); pendingNormal[2] = Float.parseFloat(t[3]); } }
				case "UV" -> {
					if (pendingUv != null && sb != null) {
						pendingUv[0] = Float.parseFloat(t[2]); pendingUv[1] = Float.parseFloat(t[3]);
						sb.add(pendingVert, pendingNormal, pendingUv);
						pendingNormal = null; pendingUv = null; pendingVert = -1;
					}
				}
				case "NUMMATERIALS" -> section = "materials";
				case "MATERIAL" -> {
					String file = t.length > 4 ? t[4] : "";
					int sl = Math.max(file.lastIndexOf('/'), file.lastIndexOf('\\'));
					m.materials.add(new Material(t[2], sl >= 0 ? file.substring(sl + 1) : file));
				}
				default -> {}
			}
		}
		for (SurfBuilder b : builders.values()) m.surfaces.add(b.build());
		return m;
	}

	private static void assignInfluences(XModel m, int v, int[] b, float[] w, int n) {
		// keep the 4 strongest influences, renormalised
		Integer[] order = new Integer[n];
		for (int i = 0; i < n; i++) order[i] = i;
		java.util.Arrays.sort(order, (x, y) -> Float.compare(w[y], w[x]));
		float sum = 0;
		int k = Math.min(4, n);
		for (int i = 0; i < k; i++) sum += w[order[i]];
		for (int i = 0; i < k; i++) { m.boneIdx[v * 4 + i] = b[order[i]]; m.boneW[v * 4 + i] = sum > 0 ? w[order[i]] / sum : 0; }
	}

	private static final class SurfBuilder {
		final int material;
		int n; int[] vert = new int[3072]; float[] normal = new float[9216], uv = new float[6144];
		SurfBuilder(int material) { this.material = material; }
		void add(int v, float[] nrm, float[] t) {
			if (n == vert.length) {
				vert = java.util.Arrays.copyOf(vert, n * 2); normal = java.util.Arrays.copyOf(normal, n * 6); uv = java.util.Arrays.copyOf(uv, n * 4);
			}
			vert[n] = v;
			normal[n * 3] = nrm[0]; normal[n * 3 + 1] = nrm[1]; normal[n * 3 + 2] = nrm[2];
			uv[n * 2] = t[0]; uv[n * 2 + 1] = t[1];
			n++;
		}
		Surface build() {
			Surface s = new Surface();
			s.material = material;
			s.vert = java.util.Arrays.copyOf(vert, n);
			s.normal = java.util.Arrays.copyOf(normal, n * 3);
			s.uv = java.util.Arrays.copyOf(uv, n * 2);
			return s;
		}
	}
}

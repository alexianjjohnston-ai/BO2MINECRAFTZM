import com.zombiecraft.bo2.Dds;
import com.zombiecraft.bo2.XModel;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Dev tool: renders a dumped BO2 model with a tiny software rasteriser so the converter can be checked by eye.
 * usage: java Preview <model.xmodel_export> <images dir> <out.png> [yawDegrees] [size] [anim file] [frames] [head.xmodel_export]
 * With an animation, renders [frames] evenly spaced poses side by side.
 */
public class Preview {
	public static void main(String[] a) throws Exception {
		Path mf = Path.of(a[0]), imgDir = Path.of(a[1]), out = Path.of(a[2]);
		double yaw = a.length > 3 ? Math.toRadians(Double.parseDouble(a[3])) : 0;
		int size = a.length > 4 ? Integer.parseInt(a[4]) : 700;
		XModel m = XModel.read(mf);
		if (a.length > 5) {
			com.zombiecraft.bo2.XAnim anim = com.zombiecraft.bo2.XAnim.read(Path.of(a[5]));
			int frames = a.length > 6 ? Integer.parseInt(a[6]) : 4;
			XModel head = a.length > 7 ? XModel.read(Path.of(a[7])) : null;
			BufferedImage sheet = new BufferedImage(size * frames, size, BufferedImage.TYPE_INT_ARGB);
			for (int f = 0; f < frames; f++) {
				float fr = anim.numFrames * f / (float) frames;
				com.zombiecraft.bo2.Pose pose = new com.zombiecraft.bo2.Pose(m);
				pose.apply(anim, fr, 1f); pose.build();
				XModel posed = posedCopy(m, pose);
				if (head != null) {
					com.zombiecraft.bo2.Pose hp = new com.zombiecraft.bo2.Pose(head);
					hp.apply(anim, fr, 1f); hp.build();
					hp.followWorld(pose);
					posed = merge(posed, posedCopy(head, hp));
				}
				java.awt.Graphics2D g = sheet.createGraphics();
				g.drawImage(render(posed, imgDir, yaw, size, f == 0), f * size, 0, null);
				g.dispose();
			}
			ImageIO.write(sheet, "png", out.toFile());
			System.out.println(anim.name + ": " + anim.numFrames + " frames @" + anim.frameRate);
			return;
		}
		ImageIO.write(render(m, imgDir, yaw, size, true), "png", out.toFile());
	}

	static XModel posedCopy(XModel m, com.zombiecraft.bo2.Pose pose) {
		XModel c = new XModel();
		c.name = m.name; c.vertCount = m.vertCount; c.pos = new float[m.pos.length];
		float[] o = new float[3];
		for (int v = 0; v < m.vertCount; v++) { pose.skinPos(v, o); System.arraycopy(o, 0, c.pos, v * 3, 3); }
		c.materials.addAll(m.materials); c.surfaces.addAll(m.surfaces);
		return c;
	}

	static XModel merge(XModel a, XModel b) {
		XModel c = new XModel();
		c.vertCount = a.vertCount + b.vertCount;
		c.pos = new float[c.vertCount * 3];
		System.arraycopy(a.pos, 0, c.pos, 0, a.pos.length); System.arraycopy(b.pos, 0, c.pos, a.pos.length, b.pos.length);
		c.materials.addAll(a.materials); c.materials.addAll(b.materials);
		c.surfaces.addAll(a.surfaces);
		for (XModel.Surface s : b.surfaces) {
			XModel.Surface t = new XModel.Surface();
			t.material = s.material + a.materials.size();
			t.vert = s.vert.clone(); for (int i = 0; i < t.vert.length; i++) t.vert[i] += a.vertCount;
			t.normal = s.normal; t.uv = s.uv;
			c.surfaces.add(t);
		}
		return c;
	}

	static final java.util.Map<String, Dds.Image> TEX = new HashMap<>();
	static float FIXED_SCALE = -1, FIX_MINX, FIX_MINY;

	static BufferedImage render(XModel m, Path imgDir, double yaw, int size, boolean log) throws Exception {
		if (log) System.out.printf("%s: %d bones, %d verts, %d surfaces, %d materials%n", m.name, m.boneCount(), m.vertCount, m.surfaces.size(), m.materials.size());
		Map<Integer, Dds.Image> tex = new HashMap<>();
		for (int i = 0; i < m.materials.size(); i++) {
			String t = m.materials.get(i).texture();
			String better = com.zombiecraft.bo2.Bo2Assets.colorMapFor(java.util.List.of(imgDir.getParent()), m.materials.get(i).name());
			if (better != null) t = better;
			Path p = imgDir.resolve(t);
			try { Dds.Image im = TEX.get(t); if (im == null) { im = Dds.read(p); TEX.put(t, im); } tex.put(i, im); }
			catch (Exception e) { if (log) System.out.println("  tex FAILED " + t + ": " + e.getMessage()); }
		}
		// view: rotate around the up axis (z), look along +y after rotation; screen x = -y_rot? keep simple
		float[] p = m.pos; int n = m.vertCount;
		float[] sx = new float[n], sy = new float[n], sz = new float[n];
		double cs = Math.cos(yaw), sn = Math.sin(yaw);
		float minX = 1e9f, maxX = -1e9f, minY = 1e9f, maxY = -1e9f;
		for (int i = 0; i < n; i++) {
			double x = p[i * 3], y = p[i * 3 + 1], z = p[i * 3 + 2];
			double rx = x * cs - y * sn, ry = x * sn + y * cs;       // rotate about z
			sx[i] = (float) -ry; sy[i] = (float) -z; sz[i] = (float) rx;   // screen: x = right (BO2 -y), y down (-z), depth = x (forward is away)
			minX = Math.min(minX, sx[i]); maxX = Math.max(maxX, sx[i]); minY = Math.min(minY, sy[i]); maxY = Math.max(maxY, sy[i]);
		}
		if (FIXED_SCALE < 0) { FIXED_SCALE = (size - 40) / Math.max(maxX - minX, maxY - minY) * 0.8f; FIX_MINX = minX - (maxX - minX) * 0.12f; FIX_MINY = minY - (maxY - minY) * 0.12f; }
		float scale = FIXED_SCALE; minX = FIX_MINX; minY = FIX_MINY; maxX = minX + (size - 40) / scale; maxY = minY + (size - 40) / scale;
		BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
		float[] zb = new float[size * size];
		java.util.Arrays.fill(zb, Float.MAX_VALUE);
		for (int i = 0; i < size * size; i++) img.setRGB(i % size, i / size, 0xFF303844);
		float ox = (size - (maxX - minX) * scale) / 2, oy = (size - (maxY - minY) * scale) / 2;
		for (XModel.Surface s : m.surfaces) {
			Dds.Image t = tex.get(s.material);
			for (int c = 0; c + 2 < s.cornerCount(); c += 3) {
				float[] X = new float[3], Y = new float[3], Z = new float[3], U = new float[3], V = new float[3], L = new float[3];
				for (int k = 0; k < 3; k++) {
					int v = s.vert[c + k];
					X[k] = (sx[v] - minX) * scale + ox; Y[k] = (sy[v] - minY) * scale + oy; Z[k] = sz[v];
					U[k] = s.uv[(c + k) * 2]; V[k] = s.uv[(c + k) * 2 + 1];
					double nx = s.normal[(c + k) * 3], ny = s.normal[(c + k) * 3 + 1], nz = s.normal[(c + k) * 3 + 2];
					double rnx = nx * cs - ny * sn, rny = nx * sn + ny * cs;
					// light from the viewer-upper-left
					L[k] = (float) Math.max(0.25, Math.min(1.1, 0.55 + 0.45 * (-rnx * 0.4 + rny * 0.3 + nz * 0.6)));
				}
				raster(img, zb, size, X, Y, Z, U, V, L, t);
			}
		}
		return img;
	}

	static void raster(BufferedImage img, float[] zb, int size, float[] X, float[] Y, float[] Z, float[] U, float[] V, float[] L, Dds.Image t) {
		int x0 = Math.max(0, (int) Math.floor(Math.min(X[0], Math.min(X[1], X[2])))), x1 = Math.min(size - 1, (int) Math.ceil(Math.max(X[0], Math.max(X[1], X[2]))));
		int y0 = Math.max(0, (int) Math.floor(Math.min(Y[0], Math.min(Y[1], Y[2])))), y1 = Math.min(size - 1, (int) Math.ceil(Math.max(Y[0], Math.max(Y[1], Y[2]))));
		float d = (Y[1] - Y[2]) * (X[0] - X[2]) + (X[2] - X[1]) * (Y[0] - Y[2]);
		if (Math.abs(d) < 1e-9) return;
		for (int y = y0; y <= y1; y++) for (int x = x0; x <= x1; x++) {
			float px = x + 0.5f, py = y + 0.5f;
			float w0 = ((Y[1] - Y[2]) * (px - X[2]) + (X[2] - X[1]) * (py - Y[2])) / d;
			float w1 = ((Y[2] - Y[0]) * (px - X[2]) + (X[0] - X[2]) * (py - Y[2])) / d;
			float w2 = 1 - w0 - w1;
			if (w0 < 0 || w1 < 0 || w2 < 0) continue;
			float z = w0 * Z[0] + w1 * Z[1] + w2 * Z[2];
			if (z >= zb[y * size + x]) continue;
			int col;
			if (t != null) {
				float u = w0 * U[0] + w1 * U[1] + w2 * U[2], v = w0 * V[0] + w1 * V[1] + w2 * V[2];
				u -= Math.floor(u); v -= Math.floor(v);
				col = t.argb()[Math.min(t.height() - 1, (int) (v * t.height())) * t.width() + Math.min(t.width() - 1, (int) (u * t.width()))];
				if ((col >>> 24) < 128) continue;
			} else col = 0xFFB0B0B0;
			float l = w0 * L[0] + w1 * L[1] + w2 * L[2];
			int r = Math.min(255, (int) (((col >> 16) & 255) * l)), g = Math.min(255, (int) (((col >> 8) & 255) * l)), b = Math.min(255, (int) ((col & 255) * l));
			zb[y * size + x] = z;
			img.setRGB(x, y, 0xFF000000 | r << 16 | g << 8 | b);
		}
	}
}

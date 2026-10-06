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
 * usage: java Preview <model.xmodel_export> <images dir> <out.png> [yawDegrees] [size]
 */
public class Preview {
	public static void main(String[] a) throws Exception {
		Path mf = Path.of(a[0]), imgDir = Path.of(a[1]), out = Path.of(a[2]);
		double yaw = a.length > 3 ? Math.toRadians(Double.parseDouble(a[3])) : 0;
		int size = a.length > 4 ? Integer.parseInt(a[4]) : 700;
		XModel m = XModel.read(mf);
		System.out.printf("%s: %d bones, %d verts, %d surfaces, %d materials%n", m.name, m.boneCount(), m.vertCount, m.surfaces.size(), m.materials.size());
		Map<Integer, Dds.Image> tex = new HashMap<>();
		for (int i = 0; i < m.materials.size(); i++) {
			String t = m.materials.get(i).texture();
			Path p = imgDir.resolve(t);
			try { tex.put(i, Dds.read(p)); System.out.println("  tex " + t + " " + tex.get(i).width() + "x" + tex.get(i).height()); }
			catch (Exception e) { System.out.println("  tex FAILED " + t + ": " + e.getMessage()); }
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
		float scale = (size - 40) / Math.max(maxX - minX, maxY - minY);
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
		ImageIO.write(img, "png", out.toFile());
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

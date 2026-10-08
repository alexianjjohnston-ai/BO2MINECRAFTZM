"""Map geometry, static model placements, lights and collision of a Black Ops II zone, read out of the Unlinker's memory (see bo2_proc.py).
Dev tool: output goes to the local library (<lib>/maps/<zone>/), never into the repo.
  python tools/bo2_geometry.py <zone> [<zone> ...]      zones as in zone/all (zm_transit, zm_transit_gump_busstation, mp_nuketown_2020 ...)
  python tools/bo2_geometry.py --all                    every zone that has a world
Writes per zone:  world.obj + world.mtl (game units: x east, y north, z up; one object per surface, usemtl = material)
                  world.npz   verts, uvs, normals (unit), tangents_sign (xyz + binormal sign), colors (RGBA u8), lightmap_uvs (0..1), tris (CCW), tri_surface   (twin of the OBJ)
                  surfaces.json (per surface: material, lightmap, flags, bounds, tri range)   materials.json (material -> textures/images)
                  smodels.json (every placed static model: name, origin, axis, scale)         lights.json (primary lights)
                  collision.npz + collision.obj (terrain collision triangles) and brushes.json (collision brush boxes with contents)
                  meta.json (name, bounds, counts)"""
import argparse, collections, json, os, re, struct, sys, time
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from bo2_paths import LIB, OAT
import numpy as np
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from bo2_proc import Capture
from bo2_structs import Structs

STRIDE = 36  # world vertex 0: xyz f4 | binormal sign f4 | color 4 | uv f2 f2 | lightmap uv f2 f2 | packed normal | packed tangent
SEMANTICS = {0: '2d', 1: 'function', 2: 'color', 3: 'detail', 5: 'normal', 8: 'specular', 11: 'water'}


def find_bo2():
	sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
	import bo2_library
	return bo2_library.find_bo2()


class Zone:
	def __init__(self, c, S):
		self.c, self.S = c, S
		self.rd = c.read

	def struct(self, name, addr): return self.S.read(self.rd, name, addr)

	def array(self, name, addr, n):
		t = self.S.layout(name)
		raw = self.rd(addr, t.size * n)
		return [self.S._decode(t, raw, i * t.size) for i in range(n)]

	def find_world(self):
		"""GfxWorld: the struct whose first field points at 'maps/....d3dbsp', whose baseName is an identifier and whose plane/node counts are plausible."""
		best = []
		for h in self.c.scan(b'.d3dbsp\0'):
			pre = self.c.read(h - 80, 88)
			m = re.search(rb'maps/[A-Za-z0-9_/]+\.d3dbsp\0$', pre)
			if not m: continue
			st = h - 80 + m.start()
			for q in self.c.find_ptrs(st):
				try: w = struct.unpack('<5I', self.c.read(q, 20))
				except MemoryError: continue
				b = self.c.cstr(w[1]) if w[1] else None
				if b and b.isidentifier() and 100 < w[2] < 10**7 and 100 < w[3] < 10**7 and w[4] < 10**7:
					best.append((q, st))
		return best[0] if best else (None, None)

	def find_clipmap(self, name_addr):
		"""clipMap_t: first field is the map name; recognised by a plausible plane / brush table (the same name string is also used by GfxWorld, ComWorld, MapEnts ...)."""
		for q in self.c.find_ptrs(name_addr):
			try:
				cm = self.struct('clipMap_t', q)
			except MemoryError:
				continue
			i = cm['info']
			if cm['isInUse'] in (0, 1) and cm['numSubModels'] < 100_000 and cm['numNodes'] < 2_000_000 and cm['numLeafs'] < 2_000_000 and cm['vertCount'] < 20_000_000 and 100 < i['planeCount'] < 2_000_000 and i['planes'] and 0 < i['numBrushes'] < 500_000 and i['brushes'] and i['numBrushSides'] and i['brushsides']:
				return q
		return None

	def find_by_name_ptr(self, name_addr, test):
		for q in self.c.find_ptrs(name_addr):
			try:
				if test(struct.unpack('<8I', self.c.read(q, 32))): return q
			except MemoryError: pass
		return None


def vec(v): return [round(x, 4) for x in v['v']] if isinstance(v, dict) else v


def extract(zone, ff, bo2, out_root):
	S = Structs()
	out = os.path.join(out_root, zone); os.makedirs(out, exist_ok=True)
	t0 = time.time()
	with Capture(ff, OAT, bo2, assets='rawfile') as c:
		Z = Zone(c, S)
		gaddr, name_addr = Z.find_world()
		if not gaddr:
			print('%-34s no world' % zone); return False
		g = Z.struct('GfxWorld', gaddr)
		wname = c.cstr(g['name'])
		n, d, dp = g['surfaceCount'], g['draw'], g['dpvs']
		print('%-34s %s: %d surfaces, %d verts, %d smodels' % (zone, wname, n, d['vertexCount'], dp['smodelCount']), flush=True)

		# ---- surfaces -> mesh ----
		surfs = Z.array('GfxSurface', dp['surfaces'], n)
		blob = np.frombuffer(c.read(d['vd0']['data'], d['vertexDataSize0']), dtype=np.uint8)
		ix = np.frombuffer(c.read(d['indices'], d['indexCount'] * 2), dtype='<u2')
		mat_cache = {}

		def material(mp):
			if mp in mat_cache: return mat_cache[mp]
			m = Z.struct('Material', mp)
			info = m['info']
			name = c.cstr(info['name'])
			tex = []
			if m['textureTable'] and m['textureCount']:
				for t in Z.array('MaterialTextureDef', m['textureTable'], m['textureCount']):
					img = c.cstr(Z.struct('GfxImage', t['image'])['name']) if t['image'] else None
					tex.append({'semantic': SEMANTICS.get(t['semantic'], t['semantic']), 'image': img})
			ts = c.cstr(Z.struct('MaterialTechniqueSet', m['techniqueSet'])['name']) if m['techniqueSet'] else None
			mat_cache[mp] = {'name': name, 'techset': ts, 'textures': tex}
			return mat_cache[mp]

		V, UV, F, FS, surf_rows = [], [], [], [], []
		NRM, TAN, COL, LM = [], [], [], []
		vbase = 0; fbase = 0
		for si, s in enumerate(surfs):
			t = s['tris']
			if not t['triCount'] or not s['material']: continue
			idx = ix[t['baseIndex']:t['baseIndex'] + t['triCount'] * 3].astype(np.int64)
			u, inv = np.unique(idx, return_inverse=True)
			rows = blob[(t['vertexDataOffset0'] + u * STRIDE)[:, None] + np.arange(STRIDE)]
			pos = rows[:, :12].copy().view('<f4').reshape(-1, 3)
			uv = rows[:, 20:24].copy().view('<f2').astype('<f4').reshape(-1, 2)
			V.append(pos); UV.append(uv)
			NRM.append(unpack_snorm10(rows[:, 24:28])); TAN.append(np.concatenate([unpack_snorm10(rows[:, 28:32]), rows[:, 12:16].copy().view('<f4')], 1))
			COL.append(rows[:, 16:20].copy()); LM.append(rows[:, 32:36].copy().view('<u2').astype('<f4').reshape(-1, 2) / 65535.0)
			tri = inv.reshape(-1, 3)[:, [0, 2, 1]] + vbase  # the game winds clockwise (D3D); flip so face normals point the way the vertex normals do
			F.append(tri); FS.append(np.full(len(tri), len(surf_rows), np.int32))
			mat = material(s['material'])
			surf_rows.append({'surface': si, 'material': mat['name'], 'lightmap': s['lightmapIndex'], 'flags': s['flags'], 'reflectionProbe': s['reflectionProbeIndex'],
				'bounds': [vec(s['bounds'][0]), vec(s['bounds'][1])], 'verts': [vbase, vbase + len(u)], 'tris': [fbase, fbase + len(tri)]})
			vbase += len(u); fbase += len(tri)
		V = np.concatenate(V).astype('<f4'); UV = np.concatenate(UV); F = np.concatenate(F).astype('<i4'); FS = np.concatenate(FS)
		NRM = np.concatenate(NRM); TAN = np.concatenate(TAN).astype('<f4'); COL = np.concatenate(COL); LM = np.concatenate(LM)
		np.savez_compressed(os.path.join(out, 'world.npz'), verts=V, uvs=UV, tris=F, tri_surface=FS, normals=NRM, tangents_sign=TAN, colors=COL, lightmap_uvs=LM)
		json.dump(surf_rows, open(os.path.join(out, 'surfaces.json'), 'w'))
		json.dump({m['name']: m for m in mat_cache.values()}, open(os.path.join(out, 'materials.json'), 'w'), indent=0)
		write_obj(os.path.join(out, 'world'), V, UV, F, surf_rows, {m['name']: m for m in mat_cache.values()}, zone, NRM)

		# ---- static models ----
		sm = Z.array('GfxStaticModelDrawInst', dp['smodelDrawInsts'], dp['smodelCount'])
		names = {}
		models = []
		for m in sm:
			p = m['model']
			if p not in names: names[p] = c.cstr(Z.struct('XModel', p)['name']) if p else None
			pl = m['placement']
			models.append({'model': names[p], 'origin': vec(pl['origin']), 'axis': [vec(a) for a in pl['axis']], 'scale': round(pl['scale'], 5)})
		json.dump(models, open(os.path.join(out, 'smodels.json'), 'w'))

		# ---- lights (ComWorld) ----
		lights = []
		cw = Z.find_by_name_ptr(name_addr, lambda w: w[1] == 1 and 0 < w[2] < 100000 and w[3] > 0x10000)
		if cw:
			cwd = Z.struct('ComWorld', cw)
			for L in Z.array('ComPrimaryLight', cwd['primaryLights'], cwd['primaryLightCount']):
				lights.append({'type': L['type'], 'color': vec(L['color']), 'dir': vec(L['dir']), 'origin': vec(L['origin']), 'radius': round(L['radius'], 3),
					'cosOuter': round(L['cosHalfFovOuter'], 4), 'cosInner': round(L['cosHalfFovInner'], 4), 'exponent': L['exponent'], 'cullDist': L['cullDist']})
		json.dump(lights, open(os.path.join(out, 'lights.json'), 'w'))

		# ---- collision (clipMap_t) ----
		ncol = 0
		cm = Z.find_clipmap(name_addr)
		if cm:
			cmd = Z.struct('clipMap_t', cm)
			if cmd['vertCount'] and cmd['triCount']:
				cv = np.frombuffer(c.read(cmd['verts'], cmd['vertCount'] * 12), dtype='<f4').reshape(-1, 3)
				ct = np.frombuffer(c.read(cmd['triIndices'], cmd['triCount'] * 6), dtype='<u2').reshape(-1, 3).astype('<i4')
				np.savez_compressed(os.path.join(out, 'collision.npz'), verts=cv, tris=ct)
				with open(os.path.join(out, 'collision.obj'), 'w') as f:
					f.write('\n'.join('v %.3f %.3f %.3f' % tuple(p) for p in cv) + '\n')
					f.write('\n'.join('f %d %d %d' % (a + 1, b + 1, e + 1) for a, b, e in ct) + '\n')
				ncol = len(ct)
			info = cmd['info']
			bb = []
			if info['numBrushes'] and info['brushBounds']:
				bounds = np.frombuffer(c.read(info['brushBounds'], info['numBrushes'] * 24), dtype='<f4').reshape(-1, 6)
				cont = np.frombuffer(c.read(info['brushContents'], info['numBrushes'] * 4), dtype='<i4') if info['brushContents'] else np.zeros(info['numBrushes'], '<i4')
				bb = [[round(float(x), 3) for x in b] + [int(k)] for b, k in zip(bounds, cont)]
			json.dump({'bounds_mins_maxs_contents': bb}, open(os.path.join(out, 'brushes.json'), 'w'))

		meta = {'zone': zone, 'world': wname, 'mins': vec(g['mins']), 'maxs': vec(g['maxs']), 'surfaces': len(surf_rows), 'vertices': int(len(V)), 'triangles': int(len(F)),
			'staticModels': len(models), 'distinctStaticModels': len({m['model'] for m in models}), 'lights': len(lights), 'collisionTriangles': ncol,
			'materials': len(mat_cache), 'lightmaps': d['lightmapCount'], 'reflectionProbes': d['reflectionProbeCount'], 'seconds': round(time.time() - t0, 1)}
		json.dump(meta, open(os.path.join(out, 'meta.json'), 'w'), indent=1)
		print('   -> %s  %d tris, %d materials, %d smodels (%d distinct), %d lights, %d collision tris, %.1fs' % (out, len(F), len(mat_cache), len(models), meta['distinctStaticModels'], len(lights), ncol, time.time() - t0))
	return True


def unpack_snorm10(b):
	"""4 bytes -> 3 signed 10-bit components (x in the low bits), normalised to unit length"""
	u = b.copy().view('<u4').reshape(-1).astype(np.int64)
	out = np.stack([((u >> sh) & 0x3FF) for sh in (0, 10, 20)], 1)
	out = np.where(out >= 512, out - 1024, out) / 511.0
	return (out / np.maximum(np.linalg.norm(out, axis=1, keepdims=True), 1e-9)).astype('<f4')


def write_obj(base, V, UV, F, surf_rows, mats, zone, N):
	with open(base + '.mtl', 'w') as f:
		for m in mats.values():
			colour = next((t['image'] for t in m['textures'] if t['semantic'] == 'color' and t['image']), None)
			f.write('newmtl %s\n' % m['name'].replace(' ', '_'))
			if colour: f.write('map_Kd ../../zones/%s/images/%s.dds\n' % ('{zone}', colour.replace('/', '_')))
	with open(base + '.obj', 'w') as f:
		f.write('mtllib world.mtl\n')
		f.write('\n'.join('v %.3f %.3f %.3f' % (x, y, z) for x, y, z in V) + '\n')
		f.write('\n'.join('vt %.5f %.5f' % (u, 1 - v) for u, v in UV) + '\n')
		for s in surf_rows:
			f.write('o s%d\nusemtl %s\n' % (s['surface'], s['material'].replace(' ', '_')))
			a, b = s['tris']
			f.write('\n'.join('f %d/%d %d/%d %d/%d' % (i + 1, i + 1, j + 1, j + 1, k + 1, k + 1) for i, j, k in F[a:b]) + '\n')


if __name__ == '__main__':
	ap = argparse.ArgumentParser()
	ap.add_argument('zones', nargs='*'); ap.add_argument('--all', action='store_true'); ap.add_argument('--bo2')
	a = ap.parse_args()
	bo2 = a.bo2 or find_bo2()
	out_root = os.path.join(LIB, 'maps')
	zones = {}
	for sub in ('zone\\all', 'zone\\english'):
		for f in sorted(os.listdir(os.path.join(bo2, sub))):
			if f.endswith('.ff') and not f.startswith('en_') or sub.endswith('all') and f.endswith('.ff'): zones[f[:-3]] = os.path.join(bo2, sub, f)
	want = list(zones) if a.all else a.zones
	for z in want:
		try: extract(z, zones[z], bo2, out_root)
		except Exception as e: print('%-34s FAILED %s: %s' % (z, type(e).__name__, e), flush=True)

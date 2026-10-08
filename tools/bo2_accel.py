"""Acceleration structures of a Black Ops II map, read out of the Unlinker's memory (see bo2_proc.py) into <lib>/maps/<zone>/accel.npz + accel.json.
They are what the engine uses for collision queries and visibility, and are handy for fast ray casts / spatial lookups in map tooling.
Collision (clipMap_t):  cm_nodes[N,3] (plane index, child0, child1; child < 0 = leaf ~child)      cm_leafs (see LEAF_COLS)
  cm_leafbrush_nodes_raw / cm_leafbrushes (u16 brush indices)   cm_aabb_trees[N,10] (origin xyz, halfSize xyz, materialIndex, childCount, firstChildIndex|partitionIndex)
  cm_partitions[N,4] (triCount, firstTri, nuinds, fuind)    cm_planes[N,5] (normal xyz, dist, type)    cm_static_models in accel.json
Render (GfxWorld):       gfx_planes[N,5], gfx_nodes (u16), gfx_stream_trees[N,12] (mins xyzw, maxs xyzw, maxStreamingDistance, firstItem, itemCount, firstChild, childCount, smodelCount/surfaceCount packed),
  gfx_leaf_refs, gfx_cell_trees[N,13] (cell, mins xyz, maxs xyz, childCount, surfaceCount, startSurfIndex, smodelIndexCount, childrenOffset, firstSmodelIndex) + gfx_cell_smodel_indexes
  python tools/bo2_accel.py <zone> [...]   |   --all"""
import argparse, json, os, struct, sys, time
import numpy as np
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from bo2_proc import Capture
from bo2_structs import Structs
import bo2_geometry as G
from bo2_world2 import W, clean

LEAF_COLS = ['firstCollAabbIndex', 'collAabbCount', 'brushContents', 'terrainContents', 'mins.x', 'mins.y', 'mins.z', 'maxs.x', 'maxs.y', 'maxs.z', 'leafBrushNode', 'cluster']


def table(S, w, name, ptr, n, fields):
	"""struct array -> float64 matrix of the named (possibly nested 'a.b') scalar fields"""
	if not ptr or not n or n > 5_000_000: return np.zeros((0, len(fields)))
	t = S.layout(name); raw = w.raw(ptr, t.size * n)
	out = np.zeros((n, len(fields)))
	for j, f in enumerate(fields):
		off, ft = 0, t
		for part in f.split('.'):
			o, ft = ft.by[part]; off += o
		fmt = '<' + ft.fmt
		out[:, j] = np.frombuffer(raw, dtype=np.dtype(fmt), count=n * 0 + n, offset=off) if False else [struct.unpack_from(fmt, raw, i * t.size + off)[0] for i in range(n)]
	return out


def extract(zone, ff, bo2, out_root):
	S = Structs(); t0 = time.time()
	out = os.path.join(out_root, zone)
	with Capture(ff, G.OAT, bo2) as c:
		Z = G.Zone(c, S); w = W(Z, S, c)
		ga, name_addr = Z.find_world()
		if not ga: return False
		g = Z.struct('GfxWorld', ga)
		arrays, meta = {}, {}
		cmaddr = Z.find_clipmap(name_addr)
		if cmaddr:
			cm = Z.struct('clipMap_t', cmaddr); info = cm['info']
			pl = np.frombuffer(w.raw(info['planes'], info['planeCount'] * 20), dtype=np.uint8).reshape(-1, 20)
			arrays['cm_planes'] = np.concatenate([pl[:, :12].copy().view('<f4'), pl[:, 12:16].copy().view('<f4'), pl[:, 16:17].astype('<f4')], 1)
			if cm['nodes'] and cm['numNodes']:
				raw = np.frombuffer(w.raw(cm['nodes'], cm['numNodes'] * 8), dtype=np.uint8).reshape(-1, 8)
				pidx = (raw[:, :4].copy().view('<u4').reshape(-1).astype(np.int64) - info['planes']) // 20
				arrays['cm_nodes'] = np.stack([pidx, raw[:, 4:6].copy().view('<i2').reshape(-1), raw[:, 6:8].copy().view('<i2').reshape(-1)], 1).astype('<i4')
			arrays['cm_leafs'] = table(S, w, 'cLeaf_s', cm['leafs'], cm['numLeafs'], LEAF_COLS).astype('<f4')
			arrays['cm_aabb_trees'] = table(S, w, 'CollisionAabbTree', cm['aabbTrees'], cm['aabbTreeCount'], ['origin.x', 'origin.y', 'origin.z', 'halfSize.x', 'halfSize.y', 'halfSize.z', 'materialIndex', 'childCount', 'u.firstChildIndex'] if 'firstChildIndex' in S.layout('CollisionAabbTreeIndex').by else ['origin.x', 'origin.y', 'origin.z', 'halfSize.x', 'halfSize.y', 'halfSize.z', 'materialIndex', 'childCount']).astype('<f4')
			arrays['cm_partitions'] = table(S, w, 'CollisionPartition', cm['partitions'], cm['partitionCount'], ['triCount', 'firstTri', 'nuinds', 'fuind']).astype('<i4')
			ln = S.sizeof('cLeafBrushNode_s')
			if info['leafbrushNodes'] and info['leafbrushNodesCount']: arrays['cm_leafbrush_nodes_raw'] = np.frombuffer(w.raw(info['leafbrushNodes'], ln * info['leafbrushNodesCount']), np.uint8).reshape(-1, ln)
			if info['leafbrushes'] and info['numLeafBrushes']: arrays['cm_leafbrushes'] = np.frombuffer(w.raw(info['leafbrushes'], info['numLeafBrushes'] * 2), '<u2')
			sm = []
			for m in w.arr('cStaticModel_s', cm['staticModelList'], cm['numStaticModels']):
				sm.append({'model': w.name_of(m['xmodel']), 'contents': m['contents'], 'origin': m['origin'], 'invScaledAxis': m['invScaledAxis'], 'absmin': m['absmin'], 'absmax': m['absmax']})
			meta['cm_static_models'] = sm
		# render side
		dp = g['dpvsPlanes']
		if dp['planes'] and g['planeCount']:
			pl = np.frombuffer(w.raw(dp['planes'], g['planeCount'] * 20), dtype=np.uint8).reshape(-1, 20)
			arrays['gfx_planes'] = np.concatenate([pl[:, :12].copy().view('<f4'), pl[:, 12:16].copy().view('<f4'), pl[:, 16:17].astype('<f4')], 1)
		if dp['nodes'] and g['nodeCount']: arrays['gfx_nodes'] = np.frombuffer(w.raw(dp['nodes'], g['nodeCount'] * 2), '<u2')
		si = g['streamInfo']
		if si['aabbTrees'] and si['aabbTreeCount']:
			t = S.layout('GfxStreamingAabbTree'); raw = w.raw(si['aabbTrees'], t.size * si['aabbTreeCount'])
			rows = []
			for i in range(si['aabbTreeCount']):
				d = S._decode(t, raw, i * t.size)
				rows.append(d['mins']['v'] + d['maxs']['v'] + [d['maxStreamingDistance'], d['firstItem'], d['itemCount'], d['firstChild'], d['childCount'], d['smodelCount'], d['surfaceCount']])
			arrays['gfx_stream_trees'] = np.array(rows, '<f4')
		if si['leafRefs'] and si['leafRefCount']: arrays['gfx_leaf_refs'] = np.frombuffer(w.raw(si['leafRefs'], si['leafRefCount'] * 4), '<i4')
		rows, sidx = [], []
		for ci, cell in enumerate(w.arr('GfxCell', g['cells'], dp['cellCount'])):
			for tr in w.arr('GfxAabbTree', cell['aabbTree'], cell['aabbTreeCount']):
				first = len(sidx)
				if tr['smodelIndexes'] and tr['smodelIndexCount']: sidx += list(np.frombuffer(w.raw(tr['smodelIndexes'], tr['smodelIndexCount'] * 2), '<u2'))
				rows.append([ci] + tr['mins']['v'] + tr['maxs']['v'] + [tr['childCount'], tr['surfaceCount'], tr['startSurfIndex'], tr['smodelIndexCount'], tr['childrenOffset'], first])
		if rows:
			arrays['gfx_cell_trees'] = np.array(rows, '<f4') if False else np.array(rows, dtype=np.float64).astype('<f4')
			arrays['gfx_cell_smodel_indexes'] = np.array(sidx, '<u2')
		sd = dict(g['dpvs'])
		if sd['sortedSurfIndex'] and sd['staticSurfaceCount']: arrays['gfx_sorted_surf_index'] = np.frombuffer(w.raw(sd['sortedSurfIndex'], sd['staticSurfaceCount'] * 2), '<u2')
	if not arrays: return False
	np.savez_compressed(os.path.join(out, 'accel.npz'), **arrays)
	meta['arrays'] = {k: list(v.shape) for k, v in arrays.items()}
	json.dump(clean(meta), open(os.path.join(out, 'accel.json'), 'w'))
	print('%-30s %s  %.1fs' % (zone, ', '.join('%s %s' % (k, v[0]) for k, v in meta['arrays'].items() if k in ('cm_nodes', 'cm_leafs', 'cm_aabb_trees', 'gfx_stream_trees', 'gfx_cell_trees')), time.time() - t0), flush=True)
	return True


if __name__ == '__main__':
	ap = argparse.ArgumentParser(); ap.add_argument('zones', nargs='*'); ap.add_argument('--all', action='store_true'); ap.add_argument('--bo2')
	a = ap.parse_args(); bo2 = a.bo2 or G.find_bo2()
	zones = {f[:-3]: os.path.join(bo2, 'zone', 'all', f) for f in sorted(os.listdir(os.path.join(bo2, 'zone', 'all'))) if f.endswith('.ff')}
	maps = os.path.join(G.LIB, 'maps')
	todo = list(zones) if a.all else a.zones
	if a.all: todo = [z for z in todo if os.path.exists(os.path.join(maps, z, 'world.npz'))]
	for z in todo:
		try: extract(z, zones[z], bo2, maps)
		except Exception as e: print('%-30s FAILED %s: %s' % (z, type(e).__name__, e), flush=True)

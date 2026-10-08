"""More of a Black Ops II zone read out of the Unlinker's memory (see bo2_proc.py), into <lib>/maps/<zone>/:
  pathnodes.json          the AI navigation graph (GameWorld PathData): every node with type, origin, facing, radius and its links (this is what the zombies path on)
  collision_hulls.obj/.npz + collision_brushes.json   every collision brush as a convex hull (faces rebuilt from the brush planes and vertices) with its contents flags
  footsteps.json          footstep sound / effect tables: surface type -> alias per movement type, surface -> effect
  python tools/bo2_extra.py <zone> [<zone> ...]   |   --all"""
import argparse, collections, json, os, re, struct, subprocess, sys, time
import numpy as np
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from bo2_proc import Capture
from bo2_structs import Structs
import bo2_geometry as G

SURFACES = ['default', 'bark', 'brick', 'carpet', 'cloth', 'concrete', 'dirt', 'flesh', 'foliage', 'glass', 'grass', 'gravel', 'ice', 'metal', 'mud', 'paper', 'plaster', 'rock',
	'sand', 'snow', 'water', 'wood', 'asphalt', 'ceramic', 'plastic', 'rubber', 'cushion', 'fruit', 'painted_metal', 'player', 'tall_grass', 'riot_shield']
NODE_TYPES = ['bad', 'path', 'cover_stand', 'cover_crouch', 'cover_crouch_window', 'cover_prone', 'cover_right', 'cover_left', 'cover_pillar', 'ambush', 'exposed', 'concealment_stand',
	'concealment_crouch', 'concealment_prone', 'reacquire', 'balcony', 'scripted', 'negotiation_begin', 'negotiation_end', 'turret', 'guard', 'numtypes', 'dontlink']


def snd_hash(s):
	h = 0x1505
	for c in s.encode():
		if 65 <= c <= 90: c += 32
		h = (c + 0x1003F * h) & 0xFFFFFFFF
	return h or 1


def listed(ff, kind):
	"""names of one asset type in a zone, from `Unlinker --list`"""
	out = subprocess.run([G.OAT, '--no-color', '--list', ff], capture_output=True, text=True, errors='replace', cwd=os.path.dirname(G.OAT)).stdout
	names = []
	for l in out.splitlines():
		p = l.split(',')
		if p[0] == kind and len(p) >= 2: names.append(p[-1].strip())
	return names


def vec(v): return [round(x, 3) for x in v['v']] if isinstance(v, dict) else v


def pathnodes(Z, S, c, name_addr):
	pg = Z.find_by_name_ptr(name_addr, lambda w: 0 < w[1] < 200000 and 0 < w[2] <= w[1] and w[3] > 0x10000 and w[4] > 0x10000)
	if not pg: return None
	pd = Z.struct('GameWorldMp', pg)['path']
	n = pd['nodeCount']
	nodes = Z.array('pathnode_t', pd['nodes'], n)
	out = []
	for i, nd in enumerate(nodes):
		k = nd['constant']
		links = []
		if k['Links'] and k['totalLinkCount']:
			for L in Z.array('pathlink_s', k['Links'], k['totalLinkCount']):
				links.append({'to': L['nodeNum'], 'dist': round(L['fDist'], 2), 'negotiation': L['negotiationLink'], 'flags': L['flags']})
		out.append({'i': i, 'type': NODE_TYPES[k['type']] if k['type'] < len(NODE_TYPES) else k['type'], 'spawnflags': k['spawnflags'], 'origin': vec(k['vOrigin']),
			'angle': round(k['fAngle'], 2), 'forward': [round(x, 3) for x in (k['forward']['v'] if isinstance(k['forward'], dict) else k['forward'])] if k['forward'] else None,
			'radius': round(k['fRadius'], 2), 'overlap': [x for x in k['wOverlapNode']], 'links': links})
	return {'nodeCount': n, 'originalNodeCount': pd['originalNodeCount'], 'nodes': out}


def hulls(Z, S, c, cm):
	info = Z.struct('clipMap_t', cm)['info']
	nb = info['numBrushes']
	if not nb: return None
	brushes = Z.array('cbrush_t', info['brushes'], nb)
	planes = np.frombuffer(c.read(info['planes'], info['planeCount'] * 20), dtype=np.uint8).reshape(-1, 20)  # cplane_s: normal xyz, dist, type, signbits
	pn = planes[:, :12].copy().view('<f4').reshape(-1, 3); pd = planes[:, 12:16].copy().view('<f4').reshape(-1)
	raw = c.try_read(info['brushVerts'], info['numBrushVerts'] * 12) if info['numBrushVerts'] and info['brushVerts'] else None
	bv = np.frombuffer(raw, dtype='<f4').reshape(-1, 3) if raw else None  # one big block when readable, else brush by brush
	V, T, BT, meta = [], [], [], []
	vbase = 0
	for bi, b in enumerate(brushes):
		nv = b['numverts']
		if nv < 4 or not b['verts']: continue
		if bv is not None and 0 <= (b['verts'] - info['brushVerts']) // 12 and (b['verts'] - info['brushVerts']) // 12 + nv <= len(bv):
			o = (b['verts'] - info['brushVerts']) // 12; pts = bv[o:o + nv].astype(np.float64)
		else:
			r = c.try_read(b['verts'], nv * 12)
			if r is None: continue
			pts = np.frombuffer(r, dtype='<f4').reshape(-1, 3).astype(np.float64)
		cand = []
		mn, mx = np.array(vec(b['mins'])), np.array(vec(b['maxs']))
		for ax in range(3):
			for sgn, val in ((1, mx[ax]), (-1, -mn[ax])):
				nrm = np.zeros(3); nrm[ax] = sgn; cand.append((nrm, val))
		sflags = set()
		if b['numsides'] and b['sides']:
			for sd in Z.array('cbrushside_t', b['sides'], b['numsides']):
				pi = (sd['plane'] - info['planes']) // 20
				if 0 <= pi < len(pn): cand.append((pn[pi].astype(np.float64), float(pd[pi]))); sflags.add(sd['sflags'])
		tris = []; seen = []
		for nrm, dist in cand:
			if any(np.allclose(nrm, s[0], atol=1e-3) and abs(dist - s[1]) < 0.05 for s in seen): continue
			on = np.nonzero(np.abs(pts @ nrm - dist) < 0.08)[0]
			if len(on) < 3: continue
			seen.append((nrm, dist))
			cen = pts[on].mean(0)
			a = np.cross(nrm, [1, 0, 0] if abs(nrm[0]) < 0.9 else [0, 1, 0]); a /= np.linalg.norm(a); bb = np.cross(nrm, a)
			ang = np.arctan2((pts[on] - cen) @ bb, (pts[on] - cen) @ a)
			order = on[np.argsort(ang)]
			for k in range(1, len(order) - 1): tris.append((order[0], order[k], order[k + 1]))
		if not tris: continue
		V.append(pts.astype('<f4')); T.append(np.array(tris, '<i4') + vbase); BT.append(np.full(len(tris), len(meta), '<i4')); vbase += len(pts)
		meta.append({'brush': bi, 'contents': b['contents'], 'surfaceFlags': sorted(sflags), 'mins': vec(b['mins']), 'maxs': vec(b['maxs'])})
	if not V: return None
	return np.concatenate(V), np.concatenate(T), np.concatenate(BT), meta


def footsteps(Z, S, c, ff):
	out = {'sound': {}, 'fx': {}}
	known = json.load(open(os.path.join(G.LIB, 'audio', 'aliases.json'))) if os.path.exists(os.path.join(G.LIB, 'audio', 'aliases.json')) else {}
	h2a = {snd_hash(a): a for a in known}
	for nm in listed(ff, 'footsteptable'):
		hits = [a for a in c.scan(nm.encode() + b'\0')]
		for h in hits:
			for q in c.find_ptrs(h):
				try: t = S.read(c.read, 'FootstepTableDef', q)
				except MemoryError: continue
				if c.cstr(t['name']) != nm: continue
				rows = {}
				for si, row in enumerate(t['sndAliasTable']):
					rows[SURFACES[si] if si < len(SURFACES) else si] = [h2a.get(v, v) if v else None for v in row]
				out['sound'][nm] = rows; break
			if nm in out['sound']: break
	for nm in listed(ff, 'footstepfxtable'):
		for h in c.scan(nm.encode() + b'\0'):
			done = False
			for q in c.find_ptrs(h):
				try: t = S.read(c.read, 'FootstepFXTableDef', q)
				except MemoryError: continue
				if c.cstr(t['name']) != nm: continue
				rows = {}
				for si, p in enumerate(t['footstepFX']):
					if p:
						try: rows[SURFACES[si] if si < len(SURFACES) else si] = (c.cstr(S.read(c.read, 'FxEffectDef', p)['name']) or '').lstrip(',')
						except MemoryError: pass
				out['fx'][nm] = rows; done = True; break
			if done: break
	return out


def extract(zone, ff, bo2, out_root):
	S = Structs(); t0 = time.time()
	with Capture(ff, G.OAT, bo2) as c:
		Z = G.Zone(c, S)
		ga, name_addr = Z.find_world()
		out = os.path.join(out_root, zone)
		res = []
		fs = footsteps(Z, S, c, ff)
		if fs['sound'] or fs['fx']:
			os.makedirs(out, exist_ok=True); json.dump(fs, open(os.path.join(out, 'footsteps.json'), 'w'), indent=0); res.append('footsteps %d/%d' % (len(fs['sound']), len(fs['fx'])))
		if not ga:
			print('%-34s %s' % (zone, ', '.join(res) or 'nothing'), flush=True); return bool(res)
		g = Z.struct('GfxWorld', ga)
		pn = pathnodes(Z, S, c, name_addr)
		if pn:
			json.dump(pn, open(os.path.join(out, 'pathnodes.json'), 'w')); res.append('%d path nodes' % pn['nodeCount'])
		cm = Z.find_clipmap(name_addr)
		if cm:
			h = hulls(Z, S, c, cm)
			if h:
				V, T, BT, meta = h
				np.savez_compressed(os.path.join(out, 'collision_hulls.npz'), verts=V, tris=T, tri_brush=BT)
				json.dump(meta, open(os.path.join(out, 'collision_brushes.json'), 'w'))
				with open(os.path.join(out, 'collision_hulls.obj'), 'w') as f:
					f.write('\n'.join('v %.3f %.3f %.3f' % tuple(p) for p in V) + '\n')
					cur = -1
					for tri, b in zip(T, BT):
						if b != cur: f.write('o brush%d_c%x\n' % (meta[b]['brush'], meta[b]['contents'])); cur = b
						f.write('f %d %d %d\n' % tuple(tri + 1))
				res.append('%d collision hulls' % len(meta))
	print('%-34s %s  %.1fs' % (zone, ', '.join(res) or 'nothing', time.time() - t0), flush=True)
	return True


if __name__ == '__main__':
	ap = argparse.ArgumentParser(); ap.add_argument('zones', nargs='*'); ap.add_argument('--all', action='store_true'); ap.add_argument('--bo2')
	a = ap.parse_args(); bo2 = a.bo2 or G.find_bo2()
	zones = {f[:-3]: os.path.join(bo2, 'zone', 'all', f) for f in sorted(os.listdir(os.path.join(bo2, 'zone', 'all'))) if f.endswith('.ff')}
	for z in (list(zones) if a.all else a.zones):
		try: extract(z, zones[z], bo2, os.path.join(G.LIB, 'maps'))
		except Exception as e: print('%-34s FAILED %s: %s' % (z, type(e).__name__, e), flush=True)

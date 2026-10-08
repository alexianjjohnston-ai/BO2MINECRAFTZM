"""The rest of a Black Ops II map, read out of the Unlinker's memory (see bo2_proc.py), into <lib>/maps/<zone>/:
  lightgrid.npz + lightgrid.json   the light grid (row data, entries, compressed colours, SH coefficients, sky grid volumes) used to light characters and props
  lighting.json                    sun, sky, fog, exposure / LUT / shadow volumes, coronas, hero lights, occluders, outdoor bounds, reflection probes, lightmaps, flags
  cells.json                       visibility cells and portals (PVS)
  dynents.json                     dynamic entities: destructibles / clutter with model, destroyed model, effects, health, physics preset, placement
  triggers.json, submodels.json    trigger volumes (hulls / slabs) and the bounds of every brush submodel ("*N" models)
  volumes.json                     every brush entity (trigger, info_volume, door, clip ...) with the bounds of its "*N" model
  ropes.json, constraints.json     physics constraints from the clip map and the rope entities
  entities_full.json               every entity with ALL its key/values
  vehicle_paths.json               vehicle node graph and the paths (bus route, trains ...) it forms, plus script_vehicle entities
  python tools/bo2_world2.py <zone> [<zone> ...]   |   --all"""
import argparse, collections, json, math, os, re, struct, sys, time
import numpy as np
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from bo2_proc import Capture
from bo2_structs import Structs
import bo2_geometry as G


def clean(o):
	if isinstance(o, dict):
		if set(o) in ({'x', 'y', 'z', 'v'}, {'x', 'y', 'v'}): return [round(x, 4) for x in o['v']]
		return {k: clean(v) for k, v in o.items()}
	if isinstance(o, list): return [clean(x) for x in o]
	if isinstance(o, float): return None if math.isnan(o) or math.isinf(o) else round(o, 5)
	if isinstance(o, (bytes, bytearray)):
		t = bytes(o).split(b'\0', 1)[0]
		return t.decode('latin1') if t and all(32 <= c < 127 for c in t) else list(o)
	return o


class W:
	def __init__(self, Z, S, c): self.Z, self.S, self.c = Z, S, c

	def arr(self, name, ptr, n, limit=2_000_000):
		if not ptr or not n or n < 0 or n > limit: return []
		return self.Z.array(name, ptr, n)

	def raw(self, ptr, n):
		return self.c.read(ptr, n) if ptr and n else b''

	def name_of(self, ptr, struct_name='XModel'):
		if not ptr: return None
		try: return (self.c.cstr(self.Z.struct(struct_name, ptr)['name']) or '').lstrip(',')
		except MemoryError: return None

	def mat(self, ptr):
		if not ptr: return None
		try: return (self.c.cstr(self.Z.struct('Material', ptr)['info']['name']) or '').lstrip(',')
		except MemoryError: return None

	def s(self, ptr): return (self.c.cstr(ptr) or '').lstrip(',') if ptr else None


def light(w, g, out):
	lg = g['lightGrid']
	rows = (lg['maxs'][lg['rowAxis']] - lg['mins'][lg['rowAxis']] + 1) if lg['rowAxis'] < 3 else 0
	arrays = {}
	if lg['rowDataStart'] and rows > 0: arrays['row_data_start'] = np.frombuffer(w.raw(lg['rowDataStart'], rows * 2), '<u2')
	if lg['rawRowData'] and lg['rawRowDataSize']: arrays['raw_row_data'] = np.frombuffer(w.raw(lg['rawRowData'], lg['rawRowDataSize']), np.uint8)
	if lg['entries'] and lg['entryCount']: arrays['entries'] = np.frombuffer(w.raw(lg['entries'], lg['entryCount'] * 4), np.uint8).reshape(-1, 4)  # colorsIndex u16, primaryLightIndex, visibility
	if lg['colors'] and lg['colorCount']: arrays['colors'] = np.frombuffer(w.raw(lg['colors'], lg['colorCount'] * 168), np.uint8).reshape(-1, 56, 3)
	if lg['coeffs'] and lg['coeffCount']: arrays['coeffs'] = np.frombuffer(w.raw(lg['coeffs'], lg['coeffCount'] * 54), '<u2').reshape(-1, 9, 3)
	if arrays: np.savez_compressed(os.path.join(out, 'lightgrid.npz'), **arrays)
	meta = {k: lg[k] for k in ('sunPrimaryLightIndex', 'mins', 'maxs', 'offset', 'rowAxis', 'colAxis', 'rawRowDataSize', 'entryCount', 'colorCount', 'coeffCount', 'skyGridVolumeCount')}
	meta['skyGridVolumes'] = w.arr('GfxSkyGridVolume', lg['skyGridVolumes'], lg['skyGridVolumeCount'])
	json.dump(clean(meta), open(os.path.join(out, 'lightgrid.json'), 'w'))
	return {k: len(v) for k, v in arrays.items()}


def lighting(w, g, out):
	d = {}
	sp = g['sunParse']
	d['sun'] = {'name': clean(sp['name']), 'world': sp['initWorldSun'][0], 'fogTransitionTime': sp['fogTransitionTime'], 'fog': sp['initWorldFog'][0]}
	d['sunLight'] = w.Z.struct('GfxLight', g['sunLight']) if g['sunLight'] else None
	sf = dict(g['sun'])
	for k in ('spriteMaterial', 'flareMaterial'): sf[k] = w.mat(sf[k])
	d['sunFlare'] = sf
	for k in ('skyDynIntensity', 'lightingFlags', 'lightingQuality', 'primaryLightCount', 'sunPrimaryLightIndex', 'outdoorLookupMatrix', 'mins', 'maxs', 'checksum', 'waterDirection'):
		d[k] = g[k]
	for key, st, cnt, pl, plc in (('coronas', 'GfxLightCorona', 'coronaCount', None, None), ('shadowMapVolumes', 'GfxShadowMapVolume', 'shadowMapVolumeCount', 'shadowMapVolumePlanes', 'shadowMapVolumePlaneCount'),
			('exposureVolumes', 'GfxExposureVolume', 'exposureVolumeCount', 'exposureVolumePlanes', 'exposureVolumePlaneCount'), ('worldFogVolumes', 'GfxWorldFogVolume', 'worldFogVolumeCount', 'worldFogVolumePlanes', 'worldFogVolumePlaneCount'),
			('worldFogModifierVolumes', 'GfxWorldFogModifierVolume', 'worldFogModifierVolumeCount', 'worldFogModifierVolumePlanes', 'worldFogModifierVolumePlaneCount'),
			('lutVolumes', 'GfxLutVolume', 'lutVolumeCount', 'lutVolumePlanes', 'lutVolumePlaneCount')):
		d[key] = w.arr(st, g[key], g[cnt])
		if pl: d[key + '_planes'] = [p['plane'] for p in w.arr('GfxVolumePlane', g[pl], g[plc])]
	d['heroLights'] = w.arr('GfxHeroLight', g['heroLights'], g['heroLightCount'])
	d['heroLightTree'] = w.arr('GfxHeroLightTree', g['heroLightTree'], g['heroLightTreeCount'])
	d['occluders'] = w.arr('Occluder', g['occluders'], g['numOccluders'])
	d['outdoorBounds'] = w.arr('GfxOutdoorBounds', g['outdoorBounds'], g['numOutdoorBounds'])
	dr = g['draw']
	d['reflectionProbes'] = []
	for p in w.arr('GfxReflectionProbe', dr['reflectionProbes'], dr['reflectionProbeCount']):
		vols = [v['volumePlanes'] for v in w.arr('GfxReflectionProbeVolumeData', p['probeVolumes'], p['probeVolumeCount'])]
		d['reflectionProbes'].append({'origin': p['origin'], 'image': w.name_of(p['reflectionImage'], 'GfxImage'), 'mipLodBias': p['mipLodBias'], 'lightingSH': p['lightingSH'], 'volumes': vols})
	d['lightmaps'] = [{'primary': w.name_of(l['primary'], 'GfxImage'), 'secondary': w.name_of(l['secondary'], 'GfxImage')} for l in w.arr('GfxLightmapArray', dr['lightmaps'], dr['lightmapCount'])]
	d['brushModels'] = [{'bounds': b['bounds'], 'surfaceCount': b['surfaceCount'], 'startSurfIndex': b['startSurfIndex']} for b in w.arr('GfxBrushModel', g['models'], g['modelCount'])]
	d['materialMemory'] = [{'material': w.mat(m['material']), 'memory': m['memory']} for m in w.arr('MaterialMemory', g['materialMemory'], g['materialMemoryCount'])]
	json.dump(clean(d), open(os.path.join(out, 'lighting.json'), 'w'))
	return d


def cells(w, g, out):
	cs = w.arr('GfxCell', g['cells'], g['dpvsPlanes']['cellCount'])
	base = g['cells']; csz = w.S.sizeof('GfxCell'); res = []
	for c in cs:
		portals = []
		for p in w.arr('GfxPortal', c['portals'], c['portalCount']):
			verts = []
			if p['vertices'] and p['vertexCount']:
				verts = np.frombuffer(w.raw(p['vertices'], p['vertexCount'] * 12), '<f4').reshape(-1, 3).round(2).tolist()
			tgt = (p['cell'] - base) // csz if p['cell'] else None
			portals.append({'plane': p['plane']['coeffs'], 'to': tgt, 'verts': verts, 'bounds': p['bounds']})
		rp = list(w.raw(c['reflectionProbes'], c['reflectionProbeCount'])) if c['reflectionProbes'] and c['reflectionProbeCount'] else []
		res.append({'mins': c['mins'], 'maxs': c['maxs'], 'aabbTreeCount': c['aabbTreeCount'], 'portals': portals, 'reflectionProbes': rp})
	json.dump(clean(res), open(os.path.join(out, 'cells.json'), 'w'))
	return len(res)


def dynents(w, cm, out):
	res = []
	names = {}
	def nm(p, st):
		k = (p, st)
		if k not in names: names[k] = w.name_of(p, st)
		return names[k]
	for kind in (0, 1):
		n = cm['dynEntCount'][kind] if kind < len(cm['dynEntCount']) else 0
		for i, e in enumerate(w.arr('DynEntityDef', cm['dynEntDefList'][kind], n)):
			res.append({'list': kind, 'i': i, 'type': e['type'], 'origin': e['pose']['origin'], 'quat': e['pose']['quat'], 'model': nm(e['xModel'], 'XModel'), 'destroyedModel': nm(e['destroyedxModel'], 'XModel'),
				'brushModel': e['brushModel'], 'physicsBrushModel': e['physicsBrushModel'], 'destroyFx': nm(e['destroyFx'], 'FxEffectDef'), 'physPreset': nm(e['physPreset'], 'PhysPreset'),
				'health': e['health'], 'flags': e['flags'], 'contents': e['contents'], 'physConstraints': e['physConstraints']})
	res = [e for e in res if e['model'] or e['brushModel'] or any(e['origin']['v'])]  # the table is allocated larger than what is placed
	json.dump(clean(res), open(os.path.join(out, 'dynents.json'), 'w'))
	return len(res)


def triggers_and_submodels(w, cm, g, out):
	subs = []
	gm = w.arr('GfxBrushModel', g['models'], g['modelCount'])
	for i, m in enumerate(w.arr('cmodel_t', cm['cmodels'], cm['numSubModels'])):
		subs.append({'i': i, 'mins': m['mins'], 'maxs': m['maxs'], 'radius': m['radius'], 'gfx': gm[i]['bounds'] if i < len(gm) else None})
	json.dump(clean(subs), open(os.path.join(out, 'submodels.json'), 'w'))
	triggers_and_submodels.subs = subs
	res = {}
	try:
		me = w.Z.struct('MapEnts', cm['mapEnts']) if cm['mapEnts'] else None
		if me:
			t = me['trigger']
			models = w.arr('TriggerModel', t['models'], t['count']); hulls = w.arr('TriggerHull', t['hulls'], t['hullCount']); slabs = w.arr('TriggerSlab', t['slabs'], t['slabCount'])
			res = {'models': models, 'hulls': hulls, 'slabs': slabs}
			json.dump(clean(res), open(os.path.join(out, 'triggers.json'), 'w'))
	except MemoryError:
		res = {}
	return '%d trigger models, %d submodels' % (len(res.get('models', [])), len(subs))


def ropes(w, cm, ents, out):
	cons = w.arr('PhysConstraint', cm['constraints'], cm['num_constraints'], 100000)
	json.dump(clean(cons), open(os.path.join(out, 'constraints.json'), 'w'))
	live = []
	if cm['ropes'] and 0 < cm['max_ropes'] < 5000:
		for r in w.arr('rope_t', cm['ropes'], cm['max_ropes']):
			if r['m_in_use']: live.append({k: r[k] for k in ('m_num_particles', 'm_num_constraints', 'm_min', 'm_max', 'm_start', 'm_end', 'm_flags', 'm_dist_constraint')})
	rope_ents = [e for e in ents if e.get('classname') in ('rope', 'rope_end', 'script_rope') or 'rope' in e.get('targetname', '') and e.get('classname', '').startswith('script_')]
	json.dump(clean({'runtimeRopes': live, 'maxRopes': cm['max_ropes'], 'entities': rope_ents}), open(os.path.join(out, 'ropes.json'), 'w'))
	return len(cons), len(live), len(rope_ents)


def parse_ents(text):
	return [dict(re.findall(r'"([^"]+)" "([^"]*)"', b)) for b in re.findall(r'\{(.*?)\n\}', text, re.S) if b.strip()]


def xyz(s):
	try: return [float(x) for x in s.split()]
	except Exception: return None


def vehicle_paths(ents, out):
	nodes = [(i, e) for i, e in enumerate(ents) if e.get('classname', '').startswith('info_vehicle_node')]
	veh = [e for e in ents if e.get('classname') in ('script_vehicle', 'script_vehicle_collmap') or e.get('classname', '').startswith('script_vehicle')]
	if not nodes and not veh: return 0, 0
	idx = {}
	for k, (i, e) in enumerate(nodes):
		if e.get('targetname'): idx.setdefault(e['targetname'], []).append(k)
	edges = []
	for k, (i, e) in enumerate(nodes):
		for t in idx.get(e.get('target', ''), []): edges.append([k, t])
	incoming = collections.Counter(b for a, b in edges)
	out_edges = collections.defaultdict(list)
	for a, b in edges: out_edges[a].append(b)
	paths, seen = [], set()
	starts = [k for k in range(len(nodes)) if incoming[k] == 0 and out_edges[k]]
	for s in starts + [k for k in range(len(nodes)) if k not in set(starts)]:
		if s in seen: continue
		path = [s]; seen.add(s); cur = s; loop = False
		while out_edges[cur]:
			nxt = out_edges[cur][0]
			if nxt in seen:
				loop = nxt == path[0]; break
			path.append(nxt); seen.add(nxt); cur = nxt
		if len(path) > 1:
			pts = [xyz(nodes[k][1].get('origin', '')) for k in path]
			length = sum(math.dist(a, b) for a, b in zip(pts, pts[1:]) if a and b)
			paths.append({'nodes': path, 'start': nodes[path[0]][1].get('targetname'), 'loop': loop, 'length': round(length, 1), 'branches': sum(1 for k in path if len(out_edges[k]) > 1)})
	nd = []
	for k, (i, e) in enumerate(nodes):
		o = {kk: v for kk, v in e.items() if kk not in ('origin', 'angles', 'guid')}; o['origin'] = xyz(e.get('origin', '')); o['angles'] = xyz(e.get('angles', '')) if 'angles' in e else None; o['ent'] = i; nd.append(o)
	vs = [{kk: (xyz(v) if kk in ('origin', 'angles') else v) for kk, v in e.items()} for e in veh]
	json.dump({'nodes': nd, 'edges': edges, 'paths': sorted(paths, key=lambda p: -p['length']), 'vehicles': vs}, open(os.path.join(out, 'vehicle_paths.json'), 'w'))
	return len(nd), len(paths)


def volumes(ents, subs, out):
	res = []
	for e in ents:
		m = e.get('model', '')
		if m.startswith('*') and m[1:].isdigit() and int(m[1:]) < len(subs):
			s = subs[int(m[1:])]
			o = {k: v for k, v in e.items() if k not in ('guid',)}
			o['bounds'] = [s['mins'], s['maxs']]; o['origin'] = xyz(e['origin']) if 'origin' in e else None
			if o['origin']: o['worldBounds'] = [[a + b for a, b in zip(o['origin'], s['mins']['v'] if isinstance(s['mins'], dict) else s['mins'])], [a + b for a, b in zip(o['origin'], s['maxs']['v'] if isinstance(s['maxs'], dict) else s['maxs'])]]
			res.append(o)
	json.dump(res, open(os.path.join(out, 'volumes.json'), 'w'))
	return len(res)


def extract(zone, ff, bo2, out_root):
	S = Structs(); t0 = time.time()
	out = os.path.join(out_root, zone)
	notes = []
	files = [os.path.join(dp, f) for dp, _, fs in os.walk(os.path.join(G.LIB, 'zones', zone, 'maps')) for f in fs if f.endswith('.ents')]
	ents = parse_ents(open(files[0], encoding='latin-1').read()) if files else []
	with Capture(ff, G.OAT, bo2) as c:
		Z = G.Zone(c, S)
		ga, name_addr = Z.find_world()
		if not ga: return False
		g = Z.struct('GfxWorld', ga); w = W(Z, S, c)
		cm = None
		cmaddr = Z.find_clipmap(name_addr)
		if cmaddr: cm = Z.struct('clipMap_t', cmaddr)
		def step(label, fn):
			try: r = fn(); notes.append('%s %s' % (label, r)); return r
			except Exception as e: notes.append('%s FAILED %s' % (label, type(e).__name__)); return None
		step('lightgrid', lambda: light(w, g, out))
		step('lighting', lambda: len(lighting(w, g, out)))
		step('cells', lambda: cells(w, g, out))
		subs = []
		if cm:
			step('dynents', lambda: dynents(w, cm, out))
			step('triggers', lambda: triggers_and_submodels(w, cm, g, out))
			subs = getattr(triggers_and_submodels, 'subs', [])
			step('ropes/constraints', lambda: ropes(w, cm, ents, out))
	# entity based parts (no memory needed)
	if ents:
		json.dump(ents, open(os.path.join(out, 'entities_full.json'), 'w'))
		notes.append('vehicle nodes/paths %s' % (vehicle_paths(ents, out),))
		notes.append('volumes %d' % volumes(ents, subs, out))
	print('%-30s %s  %.1fs' % (zone, '; '.join(notes), time.time() - t0), flush=True)
	return True


if __name__ == '__main__':
	ap = argparse.ArgumentParser(); ap.add_argument('zones', nargs='*'); ap.add_argument('--all', action='store_true'); ap.add_argument('--bo2')
	a = ap.parse_args(); bo2 = a.bo2 or G.find_bo2()
	zones = {f[:-3]: os.path.join(bo2, 'zone', 'all', f) for f in sorted(os.listdir(os.path.join(bo2, 'zone', 'all'))) if f.endswith('.ff')}
	maps = os.path.join(G.LIB, 'maps')
	todo = list(zones) if a.all else a.zones
	if a.all: todo = [z for z in todo if os.path.isdir(os.path.join(maps, z))]
	for z in todo:
		try: extract(z, zones[z], bo2, maps)
		except Exception as e: print('%-30s FAILED %s: %s' % (z, type(e).__name__, e), flush=True)

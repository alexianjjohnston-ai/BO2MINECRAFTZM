"""Asset types the Unlinker cannot write, read out of its memory (see bo2_proc.py) into <lib>/misc/<zone>.json:
  glasses          breakable glass panes of a map: pane origin / angles / bounds, glass type (health, shard sizes, materials, sounds, effects)
  destructibles    destructible definitions: model, pieces, per-stage break health, effects, sounds, spawned models, physics presets
  impact_tables    bullet impact effects per impact type and surface
  fonts            glyph metrics (letter, offsets, advance, size, uv) and kerning pairs of every font
  xglobals         zone globals (gump / overlay zone sizes, cinematic limits)
  python tools/bo2_misc.py <zone> [...]   |   --all"""
import argparse, json, os, re, struct, sys, time
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from bo2_proc import Capture
from bo2_structs import Structs
import bo2_geometry as G
import bo2_extra as E
from bo2_world2 import clean, W

SURFACES = E.SURFACES


def find_named(c, S, struct_name, name, test=None):
	for h in c.scan(name.encode() + b'\0'):
		for q in c.find_ptrs(h):
			try: t = S.read(c.read, struct_name, q)
			except MemoryError: continue
			if c.cstr(t['name'] if 'name' in t else t['fontName']) == name and (test is None or test(t)): return q, t
	return None, None


def extract(zone, ff, bo2, out_root):
	S = Structs(); t0 = time.time()
	kinds = {k: E.listed(ff, k) for k in ('glasses', 'destructibledef', 'fximpacttable', 'font', 'xglobals')}
	if not any(kinds.values()): return False
	res = {}
	with Capture(ff, G.OAT, bo2) as c:
		Z = G.Zone(c, S); w = W(Z, S, c)
		fxn = lambda p: w.name_of(p, 'FxEffectDef')
		# glasses
		for nm in kinds['glasses']:
			q, t = find_named(c, S, 'Glasses', re.sub(r'\(n=\d+\)$', '', nm) if False else nm)
			if not t: continue
			defs, items = {}, []
			for gl in w.arr('Glass', t['glasses'], t['numGlasses']):
				p = gl['glassDef']
				if p not in defs:
					gd = Z.struct('GlassDef', p)
					defs[p] = {'name': w.s(gd['name']), 'maxHealth': gd['maxHealth'], 'thickness': gd['thickness'], 'minShardSize': gd['minShardSize'], 'maxShardSize': gd['maxShardSize'],
						'shardLifeProbability': gd['shardLifeProbablility'], 'maxShards': gd['maxShards'], 'pristineMaterial': w.mat(gd['pristineMaterial']), 'crackedMaterial': w.mat(gd['crackedMaterial']),
						'shardMaterial': w.mat(gd['shardMaterial']), 'crackSound': w.s(gd['crackSound']), 'shatterSound': w.s(gd['shatterShound']), 'autoShatterSound': w.s(gd['autoShatterShound']),
						'crackEffect': fxn(gd['crackEffect']), 'shatterEffect': fxn(gd['shatterEffect'])}
				outline = []
				if gl['outline'] and gl['numOutlineVerts']:
					outline = [list(x) for x in struct.iter_unpack('<2f', c.read(gl['outline'], gl['numOutlineVerts'] * 8))]
				items.append({'def': defs[p]['name'], 'index': gl['index'], 'brushModel': gl['brushModel'], 'origin': gl['origin'], 'angles': gl['angles'], 'absmin': gl['absmin'], 'absmax': gl['absmax'],
					'planar': gl['isPlanar'], 'outline': outline, 'outlineAxis': gl['outlineAxis'], 'outlineOrigin': gl['outlineOrigin'], 'uvScale': gl['uvScale'], 'thickness': gl['thickness']})
			res.setdefault('glasses', {})[nm] = {'defs': list(defs.values()), 'panes': items, 'maxShards': t['maxShards'], 'maxGroups': t['maxGroups']}
		# destructibles
		for nm in kinds['destructibledef']:
			q, t = find_named(c, S, 'DestructibleDef', nm)
			if not t: continue
			pieces = []
			for pc in w.arr('DestructiblePiece', t['pieces'], t['numPieces'], 500):
				stages = []
				for st in pc['stages']:
					if not (st['breakHealth'] or st['showBone'] or st['breakEffect'] or st['spawnModel'][0]): continue
					stages.append({'showBone': st['showBone'], 'breakHealth': st['breakHealth'], 'maxTime': st['maxTime'], 'flags': st['flags'], 'breakEffect': fxn(st['breakEffect']),
						'breakSound': w.s(st['breakSound']), 'breakNotify': w.s(st['breakNotify']), 'loopSound': w.s(st['loopSound']),
						'spawnModels': [w.name_of(m) for m in st['spawnModel'] if m], 'physPreset': w.name_of(st['physPreset'], 'PhysPreset')})
				pieces.append({'stages': stages, 'parentPiece': pc['parentPiece'], 'parentDamagePercent': pc['parentDamagePercent'], 'bulletDamageScale': pc['bulletDamageScale'],
					'explosiveDamageScale': pc['explosiveDamageScale'], 'meleeDamageScale': pc['meleeDamageScale'], 'impactDamageScale': pc['impactDamageScale'], 'entityDamageTransfer': pc['entityDamageTransfer'],
					'health': pc['health'], 'damageSound': w.s(pc['damageSound']), 'burnEffect': fxn(pc['burnEffect']), 'burnSound': w.s(pc['burnSound']), 'hideBones': pc['hideBones']})
			res.setdefault('destructibles', {})[nm] = {'model': w.name_of(t['model']), 'pristineModel': w.name_of(t['pristineModel']), 'clientOnly': t['clientOnly'], 'pieces': pieces}
		# impact tables
		names = sorted([(v, k) for k, v in S.enums.items() if k.startswith('IMPACT_TYPE_') and k != 'IMPACT_TYPE_COUNT'])
		ncount = S.enums.get('IMPACT_TYPE_COUNT', len(names))
		for nm in kinds['fximpacttable']:
			q, t = find_named(c, S, 'FxImpactTable', nm)
			if not t or not t['table']: continue
			table = {}
			for i, e in enumerate(w.arr('FxImpactEntry', t['table'], ncount, 200)):
				row = {SURFACES[s] if s < len(SURFACES) else s: fxn(p) for s, p in enumerate(e['nonflesh']) if p and fxn(p)}
				flesh = [fxn(p) for p in e['flesh']]
				if row or any(flesh): table[names[i][1][12:].lower() if i < len(names) else i] = {'surfaces': row, 'flesh': flesh}
			res.setdefault('impact_tables', {})[nm] = table
		# fonts
		for nm in kinds['font']:
			q, t = find_named(c, S, 'Font_s', nm)
			if not t: continue
			glyphs = [[g['letter'], g['x0'], g['y0'], g['dx'], g['pixelWidth'], g['pixelHeight'], round(g['s0'], 5), round(g['t0'], 5), round(g['s1'], 5), round(g['t1'], 5)] for g in w.arr('Glyph', t['glyphs'], t['glyphCount'], 70000)]
			kern = [[k['wFirst'], k['wSecond'], k['iKernAmount']] for k in w.arr('KerningPairs', t['kerningPairs'], t['kerningPairsCount'], 200000)]
			res.setdefault('fonts', {})[nm] = {'pixelHeight': t['pixelHeight'], 'scaling': t['isScalingAllowed'], 'material': w.mat(t['material']), 'glowMaterial': w.mat(t['glowMaterial']),
				'glyphColumns': ['letter', 'x0', 'y0', 'dx', 'width', 'height', 's0', 't0', 's1', 't1'], 'glyphs': glyphs, 'kerning': kern}
		# xglobals
		for nm in kinds['xglobals']:
			q, t = find_named(c, S, 'XGlobals', nm)
			if t:
				res['xglobals'] = {'xanimStreamBufferSize': t['xanimStreamBufferSize'], 'cinematicMax': [t['cinematicMaxWidth'], t['cinematicMaxHeight']], 'extracamResolution': t['extracamResolution'],
					'screenClearColor': t['screenClearColor'], 'gumps': [{'name': w.s(g['name']), 'size': g['size']} for g in t['gumps'][:t['gumpsCount']]], 'bigestGumpSize': t['bigestGumpSize'],
					'overlayCount': t['overlayCount']}
	if not res: return False
	os.makedirs(out_root, exist_ok=True)
	json.dump(clean(res), open(os.path.join(out_root, zone + '.json'), 'w'))
	print('%-32s %s  %.1fs' % (zone, ', '.join('%s %d' % (k, len(v) if hasattr(v, '__len__') else 1) for k, v in res.items()), time.time() - t0), flush=True)
	return True


if __name__ == '__main__':
	ap = argparse.ArgumentParser(); ap.add_argument('zones', nargs='*'); ap.add_argument('--all', action='store_true'); ap.add_argument('--bo2')
	a = ap.parse_args(); bo2 = a.bo2 or G.find_bo2()
	zones = {f[:-3]: os.path.join(bo2, 'zone', 'all', f) for f in sorted(os.listdir(os.path.join(bo2, 'zone', 'all'))) if f.endswith('.ff')}
	for z in (list(zones) if a.all else a.zones):
		try: extract(z, zones[z], bo2, os.path.join(G.LIB, 'misc'))
		except Exception as e: print('%-32s FAILED %s: %s' % (z, type(e).__name__, e), flush=True)

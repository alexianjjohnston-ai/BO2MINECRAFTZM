"""Particle / effect definitions (FxEffectDef) of a Black Ops II zone, read out of the Unlinker's memory (see bo2_proc.py). OAT cannot write them to disk.
Dev tool: output goes to the local library (<lib>/fx/<zone>.json), never into the repo.
  python tools/bo2_fx.py <zone> [<zone> ...]   |   python tools/bo2_fx.py --all
Each effect: name, flags, life, bounding box and its elements (sprites, models, lights, sounds, nested effects) with spawn rates, lifespans, velocities,
colour/size over time, atlas and the names of the materials / models / effects / sounds they use."""
import argparse, json, math, os, struct, sys, time
import numpy as np
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from bo2_proc import Capture
from bo2_structs import Structs

LIB = os.environ.get('BO2_LIB', r'C:\Users\alexi\bo2-dump\library')
OAT = os.environ.get('BO2_OAT', r'C:\Users\alexi\bo2-dump\oat\Unlinker.exe')
TYPES = ['sprite_billboard', 'sprite_oriented', 'sprite_rotated', 'tail', 'line', 'trail', 'cloud', 'model', 'omni_light', 'spot_light', 'sound', 'decal', 'runner', 'spark_cloud', 'spark_fountain', 'particle_sim_animation']


def clean(o):
	if isinstance(o, dict):
		if set(o) == {'x', 'y', 'z', 'v'}: return [round(x, 4) for x in o['v']]
		return {k: clean(v) for k, v in o.items()}
	if isinstance(o, list): return [clean(x) for x in o]
	if isinstance(o, float): return None if math.isnan(o) or math.isinf(o) else round(o, 5)
	if isinstance(o, (bytes, bytearray)): return list(o)
	return o


class Dump:
	def __init__(self, c, S): self.c, self.S = c, S

	def candidates(self):
		"""Every aligned address that looks like an FxEffectDef header (checked vectorised, then by its name string)."""
		out = []
		for base, size in self.c.iter_regions():
			size -= size % 4
			if size < 36: continue
			data = self.c.try_read(base, size)
			if data is None: continue
			w = np.frombuffer(data, dtype='<u4')
			n = len(w) - 9
			if n <= 0: continue
			s16 = lambda k, hi: ((w[k:k + n] >> (16 * hi)) & 0xFFFF).astype(np.int32)
			loop, shot, emi = s16(2, 0), s16(2, 1), s16(3, 0)
			ok = (loop < 200) & (shot < 200) & (emi < 200) & ((loop + shot + emi) > 0)
			ok &= (w[4:4 + n] > 0) & (w[4:4 + n] < 4_000_000)
			ok &= (w[7:7 + n] > 0x01000000) & (w[7:7 + n] < 0xF0000000) & (w[7:7 + n] % 4 == 0)
			ok &= (w[0:n] > 0x01000000) & (w[0:n] < 0xF0000000)
			ok &= (w[1:1 + n] & 0xFFFF) < 0x4000
			for i in np.nonzero(ok)[0]: out.append(base + int(i) * 4)
		return out

	def effect(self, addr):
		S, c = self.S, self.c
		fx = S.read(c.read, 'FxEffectDef', addr)
		name = c.cstr(fx['name'], 200)
		if not name or not all(32 < ord(ch) < 127 for ch in name): return None
		total = fx['elemDefCountLooping'] + fx['elemDefCountOneShot'] + fx['elemDefCountEmission']
		if not (0 < total < 200) or not fx['elemDefs']: return None
		et = S.layout('FxElemDef')
		raw = c.try_read(fx['elemDefs'], et.size * total)
		if raw is None: return None
		elems = []
		for i in range(total):
			e = S._decode(et, raw, i * et.size)
			ty = e['elemType']
			if ty >= len(TYPES): return None
			vis = self.visuals(e, ty)
			vel = self.samples('FxElemVelStateSample', e['velSamples'], e['velIntervalCount'] + 1)
			vs = self.samples('FxElemVisStateSample', e['visSamples'], e['visStateIntervalCount'] + 1)
			el = {'type': TYPES[ty], 'group': 'looping' if i < fx['elemDefCountLooping'] else 'oneShot' if i < fx['elemDefCountLooping'] + fx['elemDefCountOneShot'] else 'emission',
				'flags': e['flags'], 'spawn': e['spawn'], 'spawnRange': e['spawnRange'], 'fadeIn': e['fadeInRange'], 'fadeOut': e['fadeOutRange'],
				'spawnDelayMsec': e['spawnDelayMsec'], 'lifeSpanMsec': e['lifeSpanMsec'], 'spawnOrigin': e['spawnOrigin'], 'spawnOffsetRadius': e['spawnOffsetRadius'],
				'spawnOffsetHeight': e['spawnOffsetHeight'], 'spawnAngles': e['spawnAngles'], 'angularVelocity': e['angularVelocity'], 'initialRotation': e['initialRotation'],
				'gravity': e['gravity'], 'reflectionFactor': e['reflectionFactor'], 'atlas': e['atlas'], 'windInfluence': e['windInfluence'], 'visuals': vis,
				'velocity': vel, 'visualState': vs, 'collMins': e['collMins'], 'collMaxs': e['collMaxs'], 'emitDist': e['emitDist'], 'emitDistVariance': e['emitDistVariance'],
				'sortOrder': e['sortOrder'], 'lightingFrac': e['lightingFrac'], 'billboardPivot': e['billboardPivot'], 'spawnSound': c.cstr(e['spawnSound'].get('spawnSound', 0) if isinstance(e['spawnSound'], dict) else 0)}
			for k in ('effectOnImpact', 'effectOnDeath', 'effectEmitted', 'effectAttached'):
				el[k] = self.fxref(e[k])
			elems.append(el)
		return clean({'name': name, 'flags': fx['flags'], 'priority': fx['efPriority'], 'elemsLooping': fx['elemDefCountLooping'], 'elemsOneShot': fx['elemDefCountOneShot'],
			'elemsEmission': fx['elemDefCountEmission'], 'msecLoopingLife': fx['msecLoopingLife'], 'msecNonLoopingLife': fx['msecNonLoopingLife'],
			'boundingBoxDim': fx['boundingBoxDim'], 'boundingBoxCentre': fx['boundingBoxCentre'], 'elements': elems})

	def fxref(self, ref):
		p = ref.get('name') or ref.get('handle') if isinstance(ref, dict) else ref
		if not p: return None
		return (self.c.cstr(p, 200) or '').lstrip(',') or None

	def samples(self, name, ptr, n):
		if not ptr or n <= 0 or n > 64: return []
		try: return self.S.read(self.c.read, self.S.layout(name), ptr) if False else self._arr(name, ptr, n)
		except MemoryError: return []

	def _arr(self, name, ptr, n):
		t = self.S.layout(name); raw = self.c.read(ptr, t.size * n)
		return [self.S._decode(t, raw, i * t.size) for i in range(n)]

	def visuals(self, e, ty):
		c = self.c; n = e['visualCount']
		v = e['visuals']; ptrs = []
		if n == 0: return []
		if n == 1: ptrs = [list(v.values())[0] if isinstance(v, dict) else v]
		else:
			arr = v.get('array') if isinstance(v, dict) else v
			if not arr: return []
			raw = c.try_read(arr, 4 * n)
			if raw is None: return []
			ptrs = list(struct.unpack('<%dI' % n, raw))
		out = []
		for p in ptrs:
			if not p: out.append(None); continue
			try:
				if TYPES[ty] == 'model': out.append(c.cstr(self.S.read(c.read, 'XModel', p)['name']))
				elif TYPES[ty] in ('sound',): out.append(c.cstr(p))
				elif TYPES[ty] in ('runner',): out.append(c.cstr(p, 200))
				elif TYPES[ty] in ('omni_light', 'spot_light'): out.append(c.cstr(self.S.read(c.read, 'GfxLightDef', p)['name']))
				else: out.append(c.cstr(self.S.read(c.read, 'Material', p)['info']['name']))
			except Exception: out.append(None)
		return [o.lstrip(',') if isinstance(o, str) else o for o in out]  # a leading comma marks a reference to an asset kept in another zone


def extract(zone, ff, bo2, out_root):
	S = Structs(); t0 = time.time()
	with Capture(ff, OAT, bo2, assets='rawfile') as c:
		D = Dump(c, S)
		fxs = {}
		for a in D.candidates():
			try: f = D.effect(a)
			except Exception: f = None
			if f and f['name'] not in fxs: fxs[f['name']] = f
	if not fxs:
		print('%-34s no effects' % zone, flush=True); return False
	os.makedirs(out_root, exist_ok=True)
	json.dump(fxs, open(os.path.join(out_root, zone + '.json'), 'w'))
	print('%-34s %4d effects, %5d elements  %.1fs' % (zone, len(fxs), sum(len(f['elements']) for f in fxs.values()), time.time() - t0), flush=True)
	return True


if __name__ == '__main__':
	import bo2_library
	ap = argparse.ArgumentParser(); ap.add_argument('zones', nargs='*'); ap.add_argument('--all', action='store_true'); ap.add_argument('--bo2')
	a = ap.parse_args(); bo2 = a.bo2 or bo2_library.find_bo2()
	zones = {f[:-3]: os.path.join(bo2, 'zone', 'all', f) for f in sorted(os.listdir(os.path.join(bo2, 'zone', 'all'))) if f.endswith('.ff')}
	for z in (list(zones) if a.all else a.zones):
		try: extract(z, zones[z], bo2, os.path.join(LIB, 'fx'))
		except Exception as e: print('%-34s FAILED %s: %s' % (z, type(e).__name__, e), flush=True)

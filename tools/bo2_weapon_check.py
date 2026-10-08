"""Checks that every zombie weapon (base and Pack-a-Punch) has all its art in the local library: view / world models, their materials and images, attachments,
the HUD icons, and the Pack-a-Punch camo (mtl_weapon_camo_zombies*) with its images. Writes <lib>/weapons_report.json and prints what is missing.
  python tools/bo2_weapon_check.py [--zone zm_transit]"""
import argparse, glob, json, os, re, sys
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from bo2_paths import LIB

KEYS = ('gunModel', 'worldModel', 'worldClipModel', 'handModel', 'knifeModel', 'projectileModel', 'attachViewModel1', 'attachViewModel2', 'attachViewModel3', 'attachViewModel4',
	'attachViewModel5', 'attachViewModel6', 'attachViewModel7', 'attachViewModel8', 'attachWorldModel1', 'attachWorldModel2', 'attachWorldModel3', 'attachWorldModel4',
	'attachWorldModel5', 'attachWorldModel6', 'attachWorldModel7', 'attachWorldModel8')
ICONS = ('hudIcon', 'ammoCounterIcon', 'killIcon', 'dpadIcon', 'hudIconNoAmmo')
PLACEHOLDERS = ('tag_flash', 'tag_brass', 'viewmodel_hands_no_model', 'defaultweapon', 'viewmodel_default', 'defaultvehicle', 'None', 'none')


def weapon_fields(path):
	t = open(path, 'rb').read().decode('latin1')
	p = t.split('\\')
	return {p[i]: p[i + 1] for i in range(1, len(p) - 1, 2)}


def main():
	ap = argparse.ArgumentParser(); ap.add_argument('--zone', default='zm_transit'); a = ap.parse_args()
	zone = os.path.join(LIB, 'zones', a.zone)
	images = set()
	for f in glob.glob(os.path.join(LIB, 'zones', '*', 'images', '*.dds')): images.add(os.path.basename(f)[:-4].lower())
	models = {}
	for d in ('zm_transit', 'so_zclassic_zm_transit', 'common_zm', 'patch_zm', 'zm_transit_patch'):
		for f in glob.glob(os.path.join(LIB, 'zones', d, 'xmodel', '*.json')): models.setdefault(os.path.basename(f)[:-5], f)
	exports = {}
	for f in glob.glob(os.path.join(LIB, 'zones', '*', 'model_export', '*.xmodel_export')): exports.setdefault(os.path.basename(f).lower(), f)
	mats = {}
	for f in glob.glob(os.path.join(LIB, 'zones', '*', 'materials', '**', '*.json'), recursive=True):
		mats.setdefault(os.path.relpath(f, os.path.join(os.path.dirname(os.path.dirname(f)))).replace('\\', '/').split('materials/', 1)[-1][:-5], f)

	def model_images(name):
		"""(lod0 file found, [material], [image]) of a model"""
		j = models.get(name)
		if not j: return False, [], []
		lods = json.load(open(j)).get('lods', [])
		if not lods: return False, [], []
		ex = exports.get(os.path.basename(lods[0]['file']).lower())
		if not ex: return False, [], []
		ms, ims = [], []
		for l in open(ex, encoding='latin1'):
			m = re.match(r'MATERIAL \d+ "([^"]+)" "[^"]*" "([^"]+)"', l)
			if m: ms.append(m.group(1)); ims.append(os.path.basename(m.group(2))[:-4])
		return True, ms, ims

	def material_images(mname):
		f = mats.get(mname)
		if not f: return None
		return [t['image'] for t in json.load(open(f)).get('textures', []) if t.get('image')]

	report, missing = {}, []
	wfiles = {}
	for d in ('zm_transit', 'so_zclassic_zm_transit', 'common_zm', 'patch_zm'):
		for f in glob.glob(os.path.join(LIB, 'zones', d, 'weapons', '*')):
			wfiles.setdefault(os.path.basename(f), f)
	for name, f in sorted(wfiles.items()):
		try: w = weapon_fields(f)
		except Exception: continue
		if not w.get('gunModel') and not w.get('worldModel'): continue
		entry = {'models': {}, 'icons': {}, 'pap': name.endswith('_upgraded_zm') or 'upgraded' in name}
		for k in KEYS:
			m = w.get(k)
			if m and m not in PLACEHOLDERS:
				found, ms, ims = model_images(m)
				bad = [i for i in ims if i.lower() not in images]
				entry['models'][m] = {'found': found, 'materials': ms, 'images': ims, 'missingImages': bad}
				if not found: missing.append((name, 'model', m))
				missing += [(name, 'image', i) for i in bad]
		for k in ICONS:
			m = w.get(k)
			if m and m not in PLACEHOLDERS:
				ok = m.lower() in images or os.path.exists(os.path.join(LIB, 'zones', a.zone, 'materials', m + '.json'))
				mat = material_images(m); ok = ok or bool(mat and all(i.lower() in images for i in mat))
				entry['icons'][m] = ok
				if not ok: missing.append((name, 'icon', m))
		report[name] = entry
	# Pack-a-Punch camo: every camo material + the images it references, and the camo set pattern images
	camo = {}
	for f in glob.glob(os.path.join(zone, 'camo', '*.json')):
		j = json.load(open(f))
		for c in j['camoMaterials']:
			for m in c['materials']:
				for o in m['materialOverrides']:
					for mn in (o['baseMaterial'], o['camoMaterial']):
						if mn in camo: continue
						ims = material_images(mn)
						camo[mn] = {'found': ims is not None, 'images': ims or [], 'missingImages': [i for i in (ims or []) if i.lower() not in images]}
		for s in j['camoSets']:
			for k in ('patternCamoImage', 'solidCamoImage'):
				if s.get(k) and s[k].lower() not in images: missing.append(('camo set', 'image', s[k]))
	for mn, v in camo.items():
		if not v['found']: missing.append(('camo', 'material', mn))
		missing += [('camo ' + mn, 'image', i) for i in v['missingImages']]
	# Pack-a-Punch camo per weapon: the 'zombies' camo overrides (base material -> camo material -> the images that camo uses)
	pap = {}
	for f in sorted(glob.glob(os.path.join(zone, 'camo', '*.json'))):
		j = json.load(open(f)); rows = []
		for c in j['camoMaterials']:
			for m in c['materials']:
				for o in m['materialOverrides']:
					if 'camo_zombies' in o['camoMaterial']:
						rows.append({'baseMaterial': o['baseMaterial'], 'baseImages': material_images(o['baseMaterial']) or [], 'camoMaterial': o['camoMaterial'], 'camoImages': material_images(o['camoMaterial']) or [],
							'shaderConsts': m['shaderConsts'], 'useColorMap': m['useColorMap'], 'useNormalMap': m['useNormalMap'], 'useSpecularMap': m['useSpecularMap']})
		if rows: pap[os.path.basename(f)[5:-5]] = rows
	json.dump(pap, open(os.path.join(LIB, 'pap_camo.json'), 'w'), indent=0)
	print('Pack-a-Punch camo for %d weapon families -> pap_camo.json' % len(pap))
	json.dump({'weapons': report, 'camoMaterials': camo, 'missing': sorted(set(missing))}, open(os.path.join(LIB, 'weapons_report.json'), 'w'), indent=0)
	paps = [n for n, e in report.items() if e['pap']]
	print('%d weapons (%d Pack-a-Punch), %d camo materials, %d distinct images referenced' % (len(report), len(paps), len(camo), len({i for e in report.values() for m in e['models'].values() for i in m['images']})))
	print('missing:', len(set(missing)))
	for m in sorted(set(missing))[:60]: print('  ', m)


if __name__ == '__main__':
	main()

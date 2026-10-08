"""Copy the small, non-art map data of chosen maps from the local library into the repo (mapdata/<zone>/*.json.gz) so a clone of the repo can plan maps without the game,
and write the asset NAME catalogue (catalog/names.tsv.gz: kind, zone, name, size; no content).
Only placement / gameplay data is exported: entities, spawns, zone and trigger volumes, path nodes, vehicle paths, static model placements, lights, rope constraints, footstep
tables, sun / fog settings. Never exported: meshes, textures, models, sounds, videos, collision or any other art (those you rebuild on your own PC with tools/bo2_setup.py).
  python tools/export_mapdata.py zm_transit [more zones]      python tools/export_mapdata.py --catalog"""
import argparse, gzip, json, os, shutil, sqlite3, sys
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from bo2_paths import LIB, ROOT

FILES = ['meta.json', 'spawns.json', 'volumes.json', 'vehicle_paths.json', 'pathnodes.json', 'entities_full.json', 'smodels.json', 'lights.json', 'lighting.json', 'constraints.json',
	'ropes.json', 'footsteps.json', 'dynents.json', 'submodels.json']


def export_zone(zone):
	src = os.path.join(LIB, 'maps', zone)
	dst = os.path.join(ROOT, 'mapdata', zone)
	if not os.path.isdir(src): sys.exit('no such map in the library: ' + zone)
	os.makedirs(dst, exist_ok=True)
	total = 0
	for f in FILES:
		p = os.path.join(src, f)
		if not os.path.isfile(p): continue
		d = json.load(open(p, encoding='utf-8'))
		with gzip.open(os.path.join(dst, f + '.gz'), 'wt', encoding='utf-8', compresslevel=9) as o: json.dump(d, o, separators=(',', ':'))
		total += os.path.getsize(os.path.join(dst, f + '.gz'))
	print('%-24s %d files, %.1f MB' % (zone, len(os.listdir(dst)), total / 1e6))


def export_catalog():
	db = os.path.join(LIB, 'index.db')
	if not os.path.isfile(db): sys.exit('build the library index first (python tools/bo2_library.py index)')
	out = os.path.join(ROOT, 'catalog'); os.makedirs(out, exist_ok=True)
	con = sqlite3.connect(db)
	n = 0
	with gzip.open(os.path.join(out, 'names.tsv.gz'), 'wt', encoding='utf-8', compresslevel=9) as f:
		f.write('kind\tzone\tname\tsize\n')
		for kind, zone, name, size in con.execute("select kind, zone, name, size from assets where kind not in ('worldmap','gsc','misc','video','extra','ai') order by kind, zone, name"):
			f.write('%s\t%s\t%s\t%d\n' % (kind, zone, name.replace('\t', ' '), size)); n += 1
	# pack-a-punch camo map (names only)
	for name in ('pap_camo.json', 'weapons_report.json'):
		p = os.path.join(LIB, name)
		if os.path.isfile(p):
			with gzip.open(os.path.join(out, name + '.gz'), 'wt', encoding='utf-8') as o: json.dump(json.load(open(p)), o, separators=(',', ':'))
	counts = con.execute("select kind, count(*) from assets group by kind order by 2 desc").fetchall()
	open(os.path.join(out, 'README.md'), 'w', encoding='utf-8').write(
		'# Black Ops II asset name catalogue\n\nNames and sizes only, no game content. `names.tsv.gz`: kind, zone, name, size of everything the local library holds (%d rows).\n'
		'`pap_camo.json.gz`: which camo material / images each weapon family uses when Pack-a-Punched. `weapons_report.json.gz`: per weapon, its models, materials and images.\n\n'
		'| kind | rows |\n|---|---|\n%s\n' % (n, '\n'.join('| %s | %d |' % c for c in counts)))
	print('catalog: %d names' % n)


if __name__ == '__main__':
	ap = argparse.ArgumentParser(); ap.add_argument('zones', nargs='*'); ap.add_argument('--catalog', action='store_true')
	a = ap.parse_args()
	for z in a.zones: export_zone(z)
	if a.catalog: export_catalog()

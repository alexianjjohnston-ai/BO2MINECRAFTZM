"""Set up and rebuild the local Black Ops II library on any machine that owns the game. Nothing from the game is ever committed: the library is built on YOUR PC from YOUR install.
  python tools/bo2_setup.py                  check what is there / missing (BO2 install, OpenAssetTools Unlinker, struct header, library, optional scripts)
  python tools/bo2_setup.py --fetch-header   download OpenAssetTools' T6_Assets.h (GPL-3.0 source text, 190 KB) into <lib>/tools/ so the memory readers know the struct layouts
  python tools/bo2_setup.py --all            build the whole library (about 1 to 2 hours, ~50 GB): zones, audio, map geometry, collision, path nodes, lights, effects, AI data, weapons check, index
  python tools/bo2_setup.py --all --tier zm  only the zombies zones + their maps (much faster)
You provide: the game (Steam) and the Unlinker from https://github.com/Laupetin/OpenAssetTools/releases (put it in ~/bo2-dump/oat/ or write its path into .bo2oat).
Where things live: tools/bo2_paths.py. Documentation: docs/BO2_LIBRARY.md"""
import argparse, os, subprocess, sys, time, urllib.request
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import bo2_paths as P

HEADER_URL = 'https://raw.githubusercontent.com/Laupetin/OpenAssetTools/main/src/Common/Game/T6/T6_Assets.h'
PY = sys.executable
T = os.path.dirname(os.path.abspath(__file__))


def status():
	dirs = P.bo2_dirs()
	rows = [('Black Ops II install', dirs[0] if dirs else None, 'install the game on Steam, or write its folder into .bo2dir'),
		('OpenAssetTools Unlinker', P.OAT if os.path.isfile(P.OAT) else None, 'download Unlinker from the OpenAssetTools releases page; put it in ~/bo2-dump/oat/ or write the path into .bo2oat'),
		('T6_Assets.h (struct layouts)', P.HEADER if os.path.isfile(P.HEADER) else None, 'run: python tools/bo2_setup.py --fetch-header'),
		('Library folder', P.LIB if os.path.isdir(P.LIB) else None, 'created by --all (set BO2_LIB or .bo2lib to put it elsewhere; needs ~50 GB)'),
		('Decompiled scripts (optional)', P.SCRIPTS if os.path.isdir(P.SCRIPTS) else None, 'optional: only used to copy the GSC scripts into the library (BO2_SCRIPTS / .bo2scripts)')]
	ok = True
	for name, val, hint in rows:
		print('%-32s %s' % (name, val or 'MISSING  -> ' + hint))
		if not val and 'optional' not in hint and 'Library' not in name: ok = False
	idx = os.path.join(P.LIB, 'index.db')
	print('%-32s %s' % ('Library index', idx if os.path.isfile(idx) else 'not built yet'))
	return ok


def fetch_header():
	dst = os.path.join(P.LIB, 'tools', 'T6_Assets.h')
	os.makedirs(os.path.dirname(dst), exist_ok=True)
	with urllib.request.urlopen(HEADER_URL) as r, open(dst, 'wb') as f: f.write(r.read())
	print('wrote', dst, os.path.getsize(dst), 'bytes (OpenAssetTools, GPL-3.0; keep it out of the repo)')


def run(*args):
	print('\n==> python tools/%s' % ' '.join(args), flush=True)
	t = time.time()
	r = subprocess.run([PY, os.path.join(T, args[0])] + list(args[1:]))
	print('    exit %d, %.0fs' % (r.returncode, time.time() - t), flush=True)
	return r.returncode


def build_all(tier):
	sel = ['--all'] if tier == 'all' else {'zm': ['zm_transit', 'common_zm'], 'mp': ['--all'], 'sp': ['--all']}[tier]  # the zombies tier only needs the Tranzit world
	steps = [('bo2_library.py', 'build', '--tier', tier), ('bo2_library.py', 'audio'), ('bo2_geometry.py', *sel), ('bo2_extra.py', *sel), ('bo2_fx.py', *sel),
		('bo2_world2.py', *sel), ('bo2_misc.py', *sel), ('bo2_accel.py', *sel), ('bo2_ai.py',), ('bo2_weapon_check.py',), ('bo2_library.py', 'index')]
	for s in steps: run(*s)
	print('\nlibrary ready at', P.LIB, '- try: python tools/bo2_library.py find "ai_zombie_walk" --type xanim')


if __name__ == '__main__':
	ap = argparse.ArgumentParser()
	ap.add_argument('--fetch-header', action='store_true'); ap.add_argument('--all', action='store_true'); ap.add_argument('--tier', default='all', choices=['zm', 'mp', 'sp', 'all'])
	a = ap.parse_args()
	if a.fetch_header: fetch_header()
	ready = status()
	if a.all:
		if not ready: sys.exit('fix the MISSING items first')
		build_all(a.tier)

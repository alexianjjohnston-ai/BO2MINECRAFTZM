"""Where the BO2 tools find things, in one place. Every value can be set by an environment variable or a one-line file in the repo root (both git-ignored):
  BO2_LIB     / .bo2lib      the local library folder (everything extracted from your own BO2 install; never committed)        default ~/bo2-dump/library
  BO2_OAT     / .bo2oat      OpenAssetTools Unlinker executable (https://github.com/Laupetin/OpenAssetTools/releases, GPL-3.0)  default ~/bo2-dump/oat/Unlinker.exe, tools/oat/
  BO2_DIR     / .bo2dir      the Black Ops II install (folder that contains zone/ and sound/)                                    default: searched on every drive
  BO2_T6_HEADER              OpenAssetTools' T6_Assets.h (run tools/bo2_setup.py --fetch-header)                               default <lib>/tools/T6_Assets.h
  BO2_SCRIPTS / .bo2scripts  decompiled BO2 scripts (optional, for tools/bo2_ai.py)                                           default ~/Downloads/t6-scripts-main/t6-scripts-main"""
import glob, os

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
HOME = os.path.expanduser('~')


def _memo(name):
	p = os.path.join(ROOT, name)
	if os.path.isfile(p):
		v = open(p, encoding='utf-8').read().strip()
		if v: return v
	return None


def _pick(env, memo, candidates, must_exist=True):
	for v in (os.environ.get(env), _memo(memo) if memo else None):
		if v: return v
	for c in candidates:
		if not must_exist or os.path.exists(c): return c
	return candidates[0]


LIB = _pick('BO2_LIB', '.bo2lib', [os.path.join(HOME, 'bo2-dump', 'library'), os.path.join(HOME, 'bo2-library')])
OAT = _pick('BO2_OAT', '.bo2oat', [os.path.join(HOME, 'bo2-dump', 'oat', 'Unlinker.exe'), os.path.join(HERE, 'oat', 'Unlinker.exe'), os.path.join(ROOT, 'mod', 'run', 'zombiecraft', 'tools', 'oat', 'Unlinker.exe'),
	os.path.join(HOME, 'bo2-dump', 'oat', 'Unlinker')])
HEADER = _pick('BO2_T6_HEADER', None, [os.path.join(LIB, 'tools', 'T6_Assets.h'), os.path.join(HERE, 'third_party', 'T6_Assets.h')])
SCRIPTS = _pick('BO2_SCRIPTS', '.bo2scripts', [os.path.join(HOME, 'Downloads', 't6-scripts-main', 't6-scripts-main')])


def bo2_dirs():
	"""Every folder that looks like a BO2 install (has sound/zmb_common.all.sabl), best (most files) first"""
	cands = [os.environ.get('BO2_DIR'), os.environ.get('ZOMBIECRAFT_BO2_DIR'), _memo('.bo2dir')]
	for d in 'CDEFGHIJ':
		root = d + ':\\'
		if not os.path.isdir(root): continue
		for pat in ('steamapps\\common\\Call of Duty Black Ops II', '*\\steamapps\\common\\Call of Duty Black Ops II', '*\\*\\steamapps\\common\\Call of Duty Black Ops II', 'Games\\Call of Duty Black Ops II'):
			cands += glob.glob(os.path.join(root, pat))
	for base in ('~/.steam/steam/steamapps/common', '~/Library/Application Support/Steam/steamapps/common', '~/.local/share/Steam/steamapps/common'):
		cands.append(os.path.join(os.path.expanduser(base), 'Call of Duty Black Ops II'))
	out = []
	for c in cands:
		if c and os.path.isfile(os.path.join(c, 'sound', 'zmb_common.all.sabl')) and c not in [o[1] for o in out]:
			n = sum(len(os.listdir(os.path.join(c, s))) for s in ('zone/all', 'zone/english', 'sound') if os.path.isdir(os.path.join(c, s)))
			out.append((n, c))
	return [c for n, c in sorted(out, reverse=True)]

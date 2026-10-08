"""One local library of everything in the player's own Black Ops II install, so nothing has to be dug out of the game twice.
Dev tool only: the library lives OUTSIDE the repo (default C:\\Users\\alexi\\bo2-dump\\library, or BO2_LIB) and is never committed or shipped.

  python tools/bo2_library.py build [--tier zm|mp|sp|all] [--only zone,zone] [--force] [--jobs N]   unlink every zone (OpenAssetTools Unlinker)
  python tools/bo2_library.py audio                                                                    every sound bank entry -> .flac/.wav, named by alias
  python tools/bo2_library.py index                                                                    (re)build index.db + INDEX.md from what is on disk
  python tools/bo2_library.py find <regex> [--type image|model|xmodel|xanim|material|weapon|sound|map|...] [--zone zone] [--limit N]
  python tools/bo2_library.py status

Layout:  <lib>/zones/<zone>/{images,model_export,xmodel,xanim,materials,techsets,weapons,maps,soundbank,stringtable,...}  (OAT dump per zone)
         <lib>/audio/<bank>/...  + audio/aliases.json (alias name -> files)     <lib>/index.db (sqlite)     <lib>/INDEX.md
Identical files across zones are hard-linked through <lib>/_pool, so the same texture dumped by 30 zones costs its size once.
What OAT v0.33 cannot dump (BSP: gfxworld/clipmap/comworld, fx) is listed in INDEX.md under "Not extractable"."""
import argparse, collections, concurrent.futures as cf, csv, glob, hashlib, json, os, re, shutil, sqlite3, struct, subprocess, sys, tempfile, threading, time

LIB = os.environ.get('BO2_LIB', r'C:\Users\alexi\bo2-dump\library')
OAT = os.environ.get('BO2_OAT', r'C:\Users\alexi\bo2-dump\oat\Unlinker.exe')
RATES = [8000, 12000, 16000, 24000, 32000, 44100, 48000, 96000, 192000]
POOL_MIN = 16 * 1024  # smaller files are not worth a hard link
lock = threading.Lock()


def find_bo2(explicit=None):
	"""The install with the most zone files (a partial copy next to the full one must lose)."""
	cands = [explicit, os.environ.get('BO2_DIR'), os.environ.get('ZOMBIECRAFT_BO2_DIR')]
	memo = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '.bo2dir')
	if os.path.exists(memo): cands.append(open(memo).read().strip())
	for d in 'CDEFGH':
		for base in (d + ':\\SteamLibrary', d + ':\\Steam', d + ':\\Program Files (x86)\\Steam', d + ':\\New folder (2)', d + ':\\Games'):
			cands.append(os.path.join(base, 'steamapps', 'common', 'Call of Duty Black Ops II'))
	best, score = None, -1
	for c in cands:
		if not c or not os.path.isfile(os.path.join(c, 'sound', 'zmb_common.all.sabl')): continue
		n = sum(len(os.listdir(os.path.join(c, s))) for s in ('zone\\all', 'zone\\english', 'sound') if os.path.isdir(os.path.join(c, s)))
		if n > score: best, score = c, n
	if not best: sys.exit('Black Ops II install not found: pass --bo2 <dir> or set BO2_DIR')
	return best


def tier_of(zone):
	z = zone[3:] if zone.startswith('en_') else zone
	if re.search(r'(^zm_|_zm$|_zm_|^so_z|^common$|^patch$|^code_(pre|post)_gfx$|^frontend|^weapon|^dlc0_load)', z) and not re.search(r'_mp$', z): return 'zm'
	if re.search(r'(^mp_|_mp$|^faction_|_mp_|^su_)', z): return 'mp'
	return 'sp'


def zones_of(bo2):
	out = {}
	for sub, pre in (('zone\\all', ''), ('zone\\english', '')):
		for f in sorted(os.listdir(os.path.join(bo2, sub))):
			if f.endswith('.ff'): out[f[:-3]] = os.path.join(bo2, sub, f)
	return out


def family_ipak(zone, bo2):
	"""The shared ipak a zone streams its images from. OAT only picks zm.ipak up for zones named zm_*, so other zones get a link named after themselves."""
	z = zone[3:] if zone.startswith('en_') else zone
	fam = {'zm': 'zm', 'mp': 'mp'}.get(tier_of(zone), 'sp')
	if z.startswith('so_') and not tier_of(zone) == 'zm': fam = 'so'
	if z.startswith(fam + '_'): return None
	p = os.path.join(bo2, 'zone', 'all', fam + '.ipak')
	return p if os.path.isfile(p) else None


def dedupe(zdir):
	pool = os.path.join(LIB, '_pool')
	n = 0
	for root, _, files in os.walk(zdir):
		for f in files:
			p = os.path.join(root, f)
			try:
				if os.path.getsize(p) < POOL_MIN or os.stat(p).st_nlink > 1: continue
				h = hashlib.sha1(open(p, 'rb').read()).hexdigest()
				q = os.path.join(pool, h[:2], h)
				with lock:
					if os.path.exists(q):
						os.remove(p); os.link(q, p); n += 1
					else:
						os.makedirs(os.path.dirname(q), exist_ok=True); os.link(p, q)
			except OSError:
				pass
	return n


def build_zone(zone, ff, bo2, force):
	zdir = os.path.join(LIB, 'zones', zone)
	done = os.path.join(zdir, '.done')
	if os.path.exists(done) and not force: return zone, 'skip', 0, 0.0
	os.makedirs(os.path.join(LIB, 'logs'), exist_ok=True)
	if os.path.isdir(zdir): shutil.rmtree(zdir, ignore_errors=True)
	t0 = time.time()
	tmp = None
	paths = [os.path.join(bo2, 'zone', 'all'), os.path.join(bo2, 'zone', 'english'), os.path.join(bo2, 'main'), os.path.join(bo2, 'sound')]
	ip = family_ipak(zone, bo2)
	if ip:
		tmp = tempfile.mkdtemp(prefix='bo2lib_', dir=os.path.splitdrive(bo2)[0] + '\\')
		link = os.path.join(tmp, zone + '.ipak')
		try: os.link(ip, link)
		except OSError: shutil.copyfile(ip, link)
		paths.append(tmp)
	cmd = [OAT, '--no-color', '--model-format', 'XMODEL_EXPORT', '--image-format', 'DDS', '--search-path', ';'.join(paths),
		'-o', os.path.join(LIB, 'zones', '?zone?'), ff]
	try:
		with open(os.path.join(LIB, 'logs', zone + '.log'), 'w', encoding='utf-8', errors='replace') as log:
			r = subprocess.run(cmd, stdout=log, stderr=subprocess.STDOUT, cwd=os.path.dirname(OAT), timeout=3600)
	finally:
		if tmp: shutil.rmtree(tmp, ignore_errors=True)
	if r.returncode != 0 or not os.path.isdir(zdir):
		return zone, 'FAILED (exit %s, see logs/%s.log)' % (r.returncode, zone), 0, time.time() - t0
	linked = dedupe(zdir)
	open(done, 'w').write(time.strftime('%Y-%m-%d %H:%M:%S'))
	return zone, 'ok', linked, time.time() - t0


def cmd_build(a):
	bo2 = find_bo2(a.bo2)
	print('BO2:', bo2, '\nlibrary:', LIB)
	zs = zones_of(bo2)
	order = {'zm': 0, 'mp': 1, 'sp': 2}
	want = [z for z in zs if (a.tier == 'all' or tier_of(z) == a.tier) and (not a.only or z in a.only.split(','))]
	want.sort(key=lambda z: (order[tier_of(z)], z))
	os.makedirs(LIB, exist_ok=True)
	print(len(want), 'zones')
	with cf.ThreadPoolExecutor(a.jobs) as ex:
		for zone, st, linked, dt in ex.map(lambda z: build_zone(z, zs[z], bo2, a.force), want):
			print('%-34s %-8s %-4s %5.0fs  hardlinked %d' % (zone, tier_of(zone), st, dt, linked), flush=True)


# ---- audio ----------------------------------------------------------------

def snd_hash(s):
	h = 0x1505
	for c in s.encode():
		if 65 <= c <= 90: c += 32
		h = (c + 0x1003F * h) & 0xFFFFFFFF
	return h or 1


def read_bank(path):
	f = open(path, 'rb'); h = f.read(0x800)
	if h[:4] != b'2UX#': return None
	esz = struct.unpack_from('<I', h, 8)[0]; count = struct.unpack_from('<I', h, 0x14)[0]; eoff = struct.unpack_from('<Q', h, 0x28)[0]
	f.seek(eoff); raw = f.read(count * esz); ents = []
	for i in range(count):
		id_, size, off, frames, rate, ch, loop, fmt = struct.unpack_from('<IIIIBBBB', raw, i * esz)
		ents.append(dict(id=id_, size=size, off=off, frames=frames, rate=rate, ch=ch, loop=loop, fmt=fmt))
	return f, ents


def wav(pcm, rate, ch):
	return b'RIFF' + struct.pack('<I', 36 + len(pcm)) + b'WAVEfmt ' + struct.pack('<IHHIIHH', 16, 1, ch, rate, rate * ch * 2, ch * 2, 16) + b'data' + struct.pack('<I', len(pcm)) + pcm


def cmd_audio(a):
	bo2 = find_bo2(a.bo2)
	snd = os.path.join(bo2, 'sound')
	# alias csvs come from the zone dumps (zones/<zone>/soundbank/<bank>.aliases.csv): FileSource is what the entry id was hashed from
	srcs = collections.defaultdict(dict)  # id -> {FileSource}
	names = collections.defaultdict(list)  # id -> [alias names]
	meta = {}
	for csvp in glob.glob(os.path.join(LIB, 'zones', '*', 'soundbank', '*.aliases.csv')):
		for r in csv.DictReader(open(csvp, encoding='utf-8', errors='replace')):
			fs = (r.get('FileSource') or '').strip()
			if not fs: continue
			for c in (fs, re.sub(r'\.flac$', '', fs, flags=re.I), re.sub(r'\.wav$', '', fs, flags=re.I)):
				srcs[snd_hash(c)][c] = 1
				if r['Name'] not in names[snd_hash(c)]: names[snd_hash(c)].append(r['Name'])
	print(len(srcs), 'sound sources known from alias tables')
	out_root = os.path.join(LIB, 'audio'); os.makedirs(out_root, exist_ok=True)
	alias_files = collections.defaultdict(list)
	total = named = 0
	for p in sorted(glob.glob(os.path.join(snd, '*.sab?'))):
		bank = os.path.basename(p)
		rb = read_bank(p)
		if not rb: print('skip (not a bank):', bank); continue
		f, ents = rb
		odir = os.path.join(out_root, bank.replace('.', '_'))
		os.makedirs(odir, exist_ok=True)
		seen = collections.Counter()
		for e in ents:
			src = next(iter(srcs[e['id']]), None) if e['id'] in srcs else None
			if src:
				base = re.sub(r'\.[A-Za-z]{2}\d+\.pc\.snd$', '', src.replace('\\', '/')).replace('raw/sound/', '').strip('/')
				named += 1
			else:
				base = 'unnamed/%08x' % e['id']
			seen[base] += 1
			if seen[base] > 1: base += '_%d' % seen[base]
			ext = '.flac' if e['fmt'] == 8 else '.wav'
			dest = os.path.join(odir, base + ext)
			os.makedirs(os.path.dirname(dest), exist_ok=True)
			f.seek(e['off']); data = f.read(e['size'])
			if e['fmt'] == 0: data = wav(data, RATES[min(e['rate'], len(RATES) - 1)], max(1, e['ch']))
			elif e['fmt'] != 8: ext = '.bin(fmt%d)' % e['fmt']; dest = os.path.join(odir, base + '.fmt%d.bin' % e['fmt'])
			open(dest, 'wb').write(data)
			total += 1
			for n in names.get(e['id'], []): alias_files[n].append(os.path.relpath(dest, out_root).replace('\\', '/'))
		f.close()
		print('%-40s %6d entries' % (bank, len(ents)), flush=True)
	json.dump(alias_files, open(os.path.join(out_root, 'aliases.json'), 'w'), indent=0)
	print('audio files:', total, '| with a real name:', named, '| alias names:', len(alias_files))


# ---- index ----------------------------------------------------------------
KIND = {'images': 'image', 'model_export': 'model', 'xmodel': 'xmodel', 'xanim': 'xanim', 'materials': 'material', 'techsets': 'techset',
	'weapons': 'weapon', 'attachment': 'attachment', 'attachmentunique': 'attachment', 'camo': 'camo', 'maps': 'map', 'mapents': 'map', 'lights': 'light',
	'physic': 'physics', 'vehicles': 'vehicle', 'zbarrier': 'zbarrier', 'tracer': 'tracer', 'vision': 'vision', 'sun': 'sun', 'shock': 'shock',
	'rumble': 'rumble', 'soundbank': 'soundbank', 'stringtable': 'table', 'animtrees': 'animtree', 'clientscripts': 'script', 'raw': 'rawfile',
	'accuracy': 'accuracy', 'zone_source': 'zone', 'mp': 'config', 'techniques': 'technique', 'shader_bin': 'shader'}
SKIP_KINDS = {'technique', 'shader'}  # 6500 tiny shader fragments per zone: kept on disk, left out of the index


def cmd_index(a):
	db = os.path.join(LIB, 'index.db')
	if os.path.exists(db): os.remove(db)
	con = sqlite3.connect(db)
	con.execute('create table assets(kind text, name text, zone text, rel text, size int)')
	zdir = os.path.join(LIB, 'zones')
	counts = collections.defaultdict(lambda: collections.Counter())
	rows = []
	for zone in sorted(os.listdir(zdir)):
		zp = os.path.join(zdir, zone)
		if not os.path.isfile(os.path.join(zp, '.done')): continue
		for root, _, files in os.walk(zp):
			rel_root = os.path.relpath(root, zp).replace('\\', '/')
			top = rel_root.split('/')[0]
			kind = KIND.get(top, top if top != '.' else 'misc')
			if kind in SKIP_KINDS: continue
			for f in files:
				if f == '.done': continue
				name = os.path.splitext(f)[0] if kind != 'model' else f
				rows.append((kind, name, zone, (rel_root + '/' + f).lstrip('./'), os.path.getsize(os.path.join(root, f))))
				counts[kind][zone] += 1
	ar = os.path.join(LIB, 'audio')
	if os.path.isdir(ar):
		for root, _, files in os.walk(ar):
			for f in files:
				if f.endswith(('.flac', '.wav', '.bin')):
					rel = os.path.relpath(os.path.join(root, f), LIB).replace('\\', '/')
					rows.append(('sound', os.path.splitext(f)[0], rel.split('/')[1], rel, os.path.getsize(os.path.join(root, f))))
					counts['sound'][rel.split('/')[1]] += 1
	mr = os.path.join(LIB, 'maps')  # written by bo2_geometry.py: world mesh, static models, lights, collision per zone
	if os.path.isdir(mr):
		for zone in sorted(os.listdir(mr)):
			for f in sorted(os.listdir(os.path.join(mr, zone))):
				rows.append(('worldmap', f, zone, 'maps/%s/%s' % (zone, f), os.path.getsize(os.path.join(mr, zone, f))))
				counts['worldmap'][zone] += 1
	fr = os.path.join(LIB, 'fx')  # written by bo2_fx.py: effect definitions per zone
	if os.path.isdir(fr):
		for f in sorted(os.listdir(fr)):
			zone = f[:-5]
			for name in json.load(open(os.path.join(fr, f))):
				rows.append(('fx', name, zone, 'fx/' + f, 0)); counts['fx'][zone] += 1
	sr = os.path.join(LIB, 'scripts')  # decompiled GSC/CSC, copied in by bo2_ai.py
	if os.path.isdir(sr):
		for root, _, files in os.walk(sr):
			for f in files:
				if f.endswith(('.gsc', '.csc')):
					rel = os.path.relpath(os.path.join(root, f), LIB).replace(os.sep, '/')
					rows.append(('gsc', os.path.splitext(f)[0], rel.split('/')[2] if rel.count('/') > 2 else 'scripts', rel, os.path.getsize(os.path.join(root, f))))
					counts['gsc']['scripts'] += 1
	ai = os.path.join(LIB, 'ai')
	if os.path.isdir(ai):
		for f in sorted(os.listdir(ai)):
			rows.append(('ai', f, 'ai', 'ai/' + f, os.path.getsize(os.path.join(ai, f)))); counts['ai']['ai'] += 1
	af = os.path.join(ar, 'aliases.json')  # sound alias name (what the game scripts play) -> first file
	if os.path.isfile(af):
		for alias, fl in json.load(open(af)).items():
			rows.append(('alias', alias, fl[0].split('/')[0], 'audio/' + fl[0], os.path.getsize(os.path.join(ar, fl[0]))))
			counts['alias'][fl[0].split('/')[0]] += 1
	con.executemany('insert into assets values(?,?,?,?,?)', rows)
	con.execute('create index i_name on assets(name)'); con.execute('create index i_kind on assets(kind)')
	con.commit()
	lines = ['# Black Ops II local library', '', 'Generated ' + time.strftime('%Y-%m-%d %H:%M') + ' by tools/bo2_library.py. Files live under `zones/<zone>/` and `audio/`; look things up with `python tools/bo2_library.py find <regex>`.', '',
		'| kind | files | zones |', '|---|---|---|']
	for k in sorted(counts, key=lambda k: -sum(counts[k].values())):
		lines.append('| %s | %d | %d |' % (k, sum(counts[k].values()), len(counts[k])))
	lines += ['', '## Beyond what OpenAssetTools v0.33 writes', '- `maps/<zone>/` (tools/bo2_geometry.py): the BSP world mesh (world.obj/.npz, materials with their images), every placed static model, lights, collision triangles and brush boxes. Read out of the memory of the Unlinker.',
		'- `fx/<zone>.json` (tools/bo2_fx.py): effect definitions (elements, spawn, lifespan, velocity, colour/size over time, materials, models, sounds). Same method.',
		'- `maps/<zone>/pathnodes.json`, `collision_hulls.*`, `footsteps.json` (tools/bo2_extra.py), `entities.json` + `spawns.json` (tools/bo2_ai.py), `ai/` (animation states, AI types, zombie assets, README) and `scripts/` (decompiled GSC of every map and the zombies core).', '', '## Zones', '| zone | tier | images | models | anims | sounds |', '|---|---|---|---|---|---|']
	for zone in sorted({z for k in counts for z in counts[k]}):
		lines.append('| %s | %s | %d | %d | %d | %d |' % (zone, tier_of(zone), counts['image'][zone], counts['model'][zone], counts['xanim'][zone], counts['sound'][zone]))
	open(os.path.join(LIB, 'INDEX.md'), 'w', encoding='utf-8').write('\n'.join(lines) + '\n')
	print(len(rows), 'entries indexed ->', db)
	for k in sorted(counts, key=lambda k: -sum(counts[k].values())): print('  %-12s %7d' % (k, sum(counts[k].values())))


def cmd_find(a):
	con = sqlite3.connect(os.path.join(LIB, 'index.db'))
	rx = re.compile(a.pattern, re.I)
	con.create_function('rx', 1, lambda s: 1 if rx.search(s or '') else 0)
	q, args = 'select kind, zone, name, rel, size from assets where rx(name)', []
	if a.type: q += ' and kind=?'; args.append(a.type)
	if a.zone: q += ' and zone=?'; args.append(a.zone)
	n = 0
	for kind, zone, name, rel, size in con.execute(q + ' order by kind, zone, name limit ?', args + [a.limit]):
		print('%-9s %-28s %s  (%d B)' % (kind, zone, os.path.join(LIB, rel if kind in ('sound', 'alias', 'worldmap', 'fx', 'gsc', 'ai') else 'zones/' + zone + '/' + rel), size)); n += 1
	print(n, 'hits')


def cmd_status(a):
	zs = glob.glob(os.path.join(LIB, 'zones', '*'))
	done = [z for z in zs if os.path.exists(os.path.join(z, '.done'))]
	print('library:', LIB, '\nzones dumped: %d done / %d folders' % (len(done), len(zs)), '\naudio banks:', len(glob.glob(os.path.join(LIB, 'audio', '*_sab?'))))
	print('index.db:', os.path.exists(os.path.join(LIB, 'index.db')))


if __name__ == '__main__':
	ap = argparse.ArgumentParser()
	sub = ap.add_subparsers(dest='cmd', required=True)
	for n in ('build', 'audio'):
		s = sub.add_parser(n); s.add_argument('--bo2')
	b = sub.choices['build']; b.add_argument('--tier', default='all', choices=['zm', 'mp', 'sp', 'all']); b.add_argument('--only'); b.add_argument('--force', action='store_true'); b.add_argument('--jobs', type=int, default=3)
	sub.add_parser('index'); sub.add_parser('status')
	f = sub.add_parser('find'); f.add_argument('pattern'); f.add_argument('--type'); f.add_argument('--zone'); f.add_argument('--limit', type=int, default=200)
	a = ap.parse_args()
	{'build': cmd_build, 'audio': cmd_audio, 'index': cmd_index, 'find': cmd_find, 'status': cmd_status}[a.cmd](a)

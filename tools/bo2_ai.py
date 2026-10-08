"""Spawn locations, entity lists and zombie / AI data of the local Black Ops II library (reads <lib>/zones, <lib>/maps; copies the decompiled scripts in).
  python tools/bo2_ai.py            writes
    <lib>/maps/<zone>/entities.json   every map entity (classname, origin, angles, targetname, script_noteworthy ...) parsed from the map's .ents
    <lib>/maps/<zone>/spawns.json     the entities that matter for play, grouped: player starts, initial/respawn points, zombie spawn locations by zone, special-AI locations
                                      (dogs, screechers, avogadro), barricades (exterior_goal), perk machines, wall weapons, pack-a-punch, doors ...
    <lib>/maps/<zone>/pathnodes.json   gets `ent` on nodes that have an entity (negotiation / traversal nodes: animscript, targetname, target ...)
    <lib>/ai/animstates.json          animation state defs (.asd): per AI file, state -> flags, notify and alias -> animation
    <lib>/ai/aitypes.json             AI type / character definitions: file -> readable strings (model, weapon, health ... as stored in the compiled script)
    <lib>/ai/zombie_assets.json       zombie xmodels, animations, weapons and sounds present in the library
    <lib>/scripts/                    the decompiled GSC/CSC of every map and the zombies core (copied from SCRIPTS_SRC), plus ai/README.md with where each AI rule lives"""
import collections, glob, json, os, re, shutil, sys

LIB = os.environ.get('BO2_LIB', r'C:\Users\alexi\bo2-dump\library')
SCRIPTS_SRC = os.environ.get('BO2_SCRIPTS', r'C:\Users\alexi\Downloads\t6-scripts-main\t6-scripts-main')


def parse_ents(path):
	txt = open(path, encoding='latin-1').read()
	ents = []
	for blk in re.findall(r'\{(.*?)\n\}', txt, re.S):
		d = dict(re.findall(r'"([^"]+)" "([^"]*)"', blk))
		if d: ents.append(d)
	return ents


def xyz(s):
	try: return [float(x) for x in s.split()]
	except Exception: return None


def slim(e):
	keep = ('classname', 'targetname', 'target', 'script_noteworthy', 'script_string', 'script_int', 'script_flag', 'zombie_zone', 'script_label', 'model', 'radius', 'spawnflags',
		'script_linkname', 'script_linkTo', 'script_unitrigger_type', 'zombie_cost', 'script_vector', 'script_float', 'zombie_weapon_upgrade', 'zombie_weapon', 'script_trigger_flag')
	o = {k: e[k] for k in keep if k in e}
	o['origin'] = xyz(e['origin']) if 'origin' in e else None
	if 'angles' in e: o['angles'] = xyz(e['angles'])
	return o


SPAWN_RULES = [  # (group, predicate)
	('player_start', lambda e: e.get('classname') in ('info_player_start', 'mp_global_intermission') or re.match(r'mp_.*_spawn(_start|_axis_start|_allies_start)?$', e.get('classname', '')) and 'dm' not in e.get('classname', '')),
	('initial_spawn', lambda e: 'initial_spawn' in (e.get('script_noteworthy', ''), e.get('targetname', ''))),
	('player_respawn', lambda e: re.search(r'respawn', e.get('targetname', '')) is not None or 'player_respawn' in e.get('script_noteworthy', '')),
	('zombie_spawn_location', lambda e: re.search(r'spawn_location|riser_location', e.get('script_noteworthy', '')) is not None),
	('dog_location', lambda e: 'dog_location' in e.get('script_noteworthy', '')),
	('screecher_location', lambda e: 'screecher_location' in e.get('script_noteworthy', '')),
	('avogadro_location', lambda e: 'avogadro_location' in e.get('script_noteworthy', '')),
	('inert_location', lambda e: 'inert_location' in e.get('script_noteworthy', '')),
	('zone_spawners', lambda e: re.search(r'zone_.*spawners$|_spawners$', e.get('targetname', '')) is not None),
	('barricade', lambda e: e.get('targetname') == 'exterior_goal' or e.get('classname', '').startswith('zbarrier_zmcore_BasicWoodBarrier')),
	('buswindow', lambda e: e.get('classname', '').startswith('zbarrier_zb_BusWindow')),
	('mystery_box', lambda e: e.get('classname', '').startswith('zbarrier_zmcore_MagicBox') or e.get('targetname') == 'treasure_chest_use'),
	('perk_machine', lambda e: e.get('targetname') == 'zm_perk_machine' or e.get('script_noteworthy', '').startswith('specialty_')),
	('pack_a_punch', lambda e: e.get('script_noteworthy') == 'specialty_weapupgrade' or 'packapunch' in e.get('model', '')),
	('wall_weapon', lambda e: e.get('targetname') == 'weapon_upgrade' or e.get('classname') == 'weapon_upgrade'),
	('door', lambda e: e.get('targetname') in ('zombie_door', 'zombie_debris', 'zombie_airlock_buy') or e.get('script_noteworthy') in ('electric_door', 'local_electric_door', 'electric_buyable_door')),
	('game_mode_object', lambda e: e.get('targetname') == 'game_mode_object'),
	('player_spawn_generic', lambda e: re.search(r'spawn|start', e.get('classname', '')) is not None and e.get('classname', '').startswith(('mp_', 'info_', 'actor_'))),
]


def spawns_of(ents):
	out = collections.defaultdict(list)
	for e in ents:
		for g, pred in SPAWN_RULES:
			try: hit = pred(e)
			except Exception: hit = False
			if hit: out[g].append(slim(e)); break
	# zombie spawn locations by zone
	zones = collections.defaultdict(list)
	for e in ents:
		tn = e.get('targetname', '')
		if re.search(r'_spawners$', tn) and e.get('script_noteworthy') != 'player_respawn_point': zones[tn].append(slim(e))
	res = {k: v for k, v in out.items()}
	if zones: res['zombie_spawners_by_zone'] = dict(zones)
	return res


def main():
	maps = os.path.join(LIB, 'maps')
	for zdir in sorted(glob.glob(os.path.join(LIB, 'zones', '*'))):
		zone = os.path.basename(zdir)
		files = glob.glob(os.path.join(zdir, 'maps', '**', '*.ents'), recursive=True)
		if not files: continue
		ents = parse_ents(files[0])
		out = os.path.join(maps, zone); os.makedirs(out, exist_ok=True)
		json.dump([slim(e) | {'guid': e.get('guid')} for e in ents], open(os.path.join(out, 'entities.json'), 'w'))
		sp = spawns_of(ents)
		json.dump(sp, open(os.path.join(out, 'spawns.json'), 'w'), indent=0)
		extra = ''
		pn = os.path.join(out, 'pathnodes.json')
		if os.path.exists(pn):
			j = json.load(open(pn)); by = {}
			for e in ents:
				if e.get('classname', '').startswith('node_') and 'origin' in e: by[tuple(round(x) for x in (xyz(e['origin']) or []))] = e
			n = 0
			for nd in j['nodes']:
				e = by.get(tuple(round(x) for x in nd['origin']))
				if e and nd['type'] != 'path' or (e and any(k in e for k in ('targetname', 'target', 'animscript', 'script_noteworthy'))):
					nd['ent'] = {k: v for k, v in e.items() if k not in ('origin', 'guid', 'classname')}; n += 1
			json.dump(j, open(pn, 'w')); extra = ', %d named path nodes' % n
		print('%-30s %5d entities  %s%s' % (zone, len(ents), ', '.join('%s %d' % (k, len(v) if isinstance(v, list) else sum(len(x) for x in v.values())) for k, v in sp.items()), extra), flush=True)

	# ---- AI data ----
	ai = os.path.join(LIB, 'ai'); os.makedirs(ai, exist_ok=True)
	states = {}
	for f in glob.glob(os.path.join(LIB, 'zones', '*', 'animstatedefs', '*.asd')):
		states.setdefault(os.path.basename(f), {'zones': []})['zones'].append(os.path.basename(os.path.dirname(os.path.dirname(f))))
		if 'states' in states[os.path.basename(f)]: continue
		txt = open(f, encoding='latin-1').read(); st = {}
		for m in re.finditer(r'^([A-Za-z0-9_]+)\s*:?\s*([^\n{]*)\n\{(.*?)\n\}', txt, re.S | re.M):
			name, head, body = m.groups()
			anims = {}
			for ln in body.strip().splitlines():
				p = ln.split()
				if len(p) == 2: anims[p[0]] = p[1]
				elif len(p) == 1: anims[p[0]] = p[0]
			st[name] = {'flags': head.strip(), 'anims': anims}
		states[os.path.basename(f)]['states'] = st
	json.dump(states, open(os.path.join(ai, 'animstates.json'), 'w'), indent=0)
	print('animstate files:', len(states))
	types = {}
	for sub in ('aitype', 'character'):
		for f in glob.glob(os.path.join(LIB, 'zones', '*', sub, '*.gsc')):
			b = open(f, 'rb').read()
			s = [x.decode('latin1') for x in re.findall(rb'[ -~]{4,}', b)]
			types.setdefault(sub + '/' + os.path.basename(f), {'zones': [], 'strings': s})['zones'].append(os.path.basename(os.path.dirname(os.path.dirname(f))))
	json.dump(types, open(os.path.join(ai, 'aitypes.json'), 'w'), indent=0)
	print('ai/character definitions:', len(types))
	z = {'xmodels': set(), 'xanims': set(), 'weapons': set()}
	for f in glob.glob(os.path.join(LIB, 'zones', '*', 'xmodel', '*.json')):
		n = os.path.basename(f)[:-5]
		if re.search(r'zom|zombie|dog|avogadro|screecher|crawler', n): z['xmodels'].add(n)
	for f in glob.glob(os.path.join(LIB, 'zones', '*', 'xanim', '*')):
		n = os.path.basename(f)
		if re.search(r'zombie|zom_|dog|avogadro|screecher|crawl', n): z['xanims'].add(n)
	for f in glob.glob(os.path.join(LIB, 'zones', '*', 'weapons', '*')):
		n = os.path.basename(f)
		if re.search(r'zombie|zm_melee|dog|avogadro|screecher', n): z['weapons'].add(n)
	json.dump({k: sorted(v) for k, v in z.items()}, open(os.path.join(ai, 'zombie_assets.json'), 'w'), indent=0)
	print('zombie assets:', {k: len(v) for k, v in z.items()})

	# ---- scripts ----
	dst = os.path.join(LIB, 'scripts')
	if os.path.isdir(SCRIPTS_SRC) and not os.path.isdir(dst):
		shutil.copytree(SCRIPTS_SRC, dst)
	if os.path.isdir(dst):
		n = sum(len(fs) for _, _, fs in os.walk(dst)); print('scripts copied:', n, 'files')


if __name__ == '__main__':
	main()

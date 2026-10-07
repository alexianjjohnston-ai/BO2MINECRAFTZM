#!/usr/bin/env python3
"""Which vanilla block textures do the maps still show without a Black Ops II image?
Reads every block of the map files (maps_local/*.json.gz) and of the sheets (map_ops / windows / doors), resolves each block to its texture files through the
vanilla blockstates + models in the Minecraft jar, and lists the textures that sheets/textures.json has no row for, most used first.
  python tools/texture_gaps.py [--json out.json]     (--all lists covered ones too)"""
import collections, glob, gzip, json, os, re, sys, zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.join(HERE, '..')
JAR = glob.glob(os.path.join(os.path.expanduser('~'), '.gradle', 'caches', 'fabric-loom', 'minecraftMaven', 'net', 'minecraft', 'minecraft-clientonly',
                             '1.21.4*', 'minecraft-clientonly-1.21.4*.jar'))
JAR = [j for j in JAR if 'intermediary' not in j and 'sources' not in j][0]
Z = zipfile.ZipFile(JAR)
NAMES = set(Z.namelist())


def load(path):
    try: return json.loads(Z.read(path))
    except KeyError: return None


def model_textures(ref, seen=None):
    """texture ids of a model (resolving parents), excluding the particle."""
    ref = ref.replace('minecraft:', '')
    d = load(f'assets/minecraft/models/{ref}.json')
    if not d: return set()
    out = set()
    tex = {k: v for k, v in d.get('textures', {}).items() if k != 'particle'}
    for k, v in tex.items():
        if not v.startswith('#'): out.add(v.replace('minecraft:', ''))
    if 'parent' in d: out |= model_textures(d['parent'])
    return out


def block_textures(name):
    d = load(f'assets/minecraft/blockstates/{name}.json')
    if not d: return set()
    models = []
    def grab(x):
        if isinstance(x, list): [grab(i) for i in x]
        elif isinstance(x, dict) and 'model' in x: models.append(x['model'])
    for v in d.get('variants', {}).values(): grab(v)
    for p in d.get('multipart', []): grab(p.get('apply'))
    out = set()
    for m in models: out |= model_textures(m)
    return {t for t in out if f'assets/minecraft/textures/{t}.png' in NAMES}


def used_blocks():
    use = collections.Counter()
    for f in glob.glob(os.path.join(ROOT, 'maps_local', '*.json.gz')):
        d = json.load(gzip.open(f, 'rt'))
        cnt = collections.Counter()
        for i, c in d['rle']: cnt[d['palette'][i].split('[')[0]] += c
        for k, v in cnt.items(): use[k] += v
    for f in glob.glob(os.path.join(ROOT, 'sheets', '**', 'map_*.json'), recursive=True):
        try: rows = json.load(open(f, encoding='utf-8'))
        except Exception: continue
        for r in rows if isinstance(rows, list) else []:
            if not isinstance(r, dict): continue
            for k in ('block', 'block2', 'boardBlock', 'sillBlock', 'fallback'):
                v = r.get(k)
                if isinstance(v, str) and ':' in v and r.get('op') != 'import': use[v.split('[')[0]] += 50
    return use


def covered():
    rows = json.load(open(os.path.join(ROOT, 'sheets', 'textures.json'), encoding='utf-8'))
    return {r['texture'].replace('minecraft:', '') for r in rows if not str(r.get('note', '')).startswith('auto')}  # the auto rows are what texture_auto.py makes: they do not count as covered


if __name__ == '__main__':
    use = used_blocks(); cov = covered()
    bytex = collections.defaultdict(lambda: [0, set()])
    for b, n in use.items():
        if b.endswith(':air') or b.split(':')[1] in ('air', 'cave_air', 'void_air', 'light', 'barrier', 'structure_void'): continue
        if b.startswith('zombiecraft:'): continue  # our own blocks: their art comes from the mod (placeholders) and rows keyed zombiecraft:...
        for t in block_textures(b.split(':')[1]):
            e = bytex[t]; e[0] += n; e[1].add(b.split(':')[1])
    miss = sorted(((n, t, sorted(bl)) for t, (n, bl) in bytex.items() if t.replace('block/', '') not in cov and t not in cov), reverse=True)
    tot = sum(n for n, _, _ in miss)
    print(len(bytex), 'textures in use,', len(miss), 'without a hand-picked BO2 row (tools/texture_auto.py covers them)')
    for n, t, bl in miss[:int(os.environ.get('N', 120))]: print(f'{n:9d}  {t:48s} {",".join(bl)[:70]}')
    if '--json' in sys.argv: json.dump([dict(count=n, texture=t, blocks=bl) for n, t, bl in miss], open(sys.argv[sys.argv.index('--json') + 1], 'w'), indent=1)

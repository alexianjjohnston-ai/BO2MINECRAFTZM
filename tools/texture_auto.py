#!/usr/bin/env python3
"""Gives every vanilla block texture the maps still use a Black Ops II image (so no block shows vanilla art), written to tools/texture_auto.json,
which gen_textures.py appends to sheets/textures.json (the rows it adds are marked "auto").
  python tools/texture_auto.py            (needs the BO2 dump on this PC: BO2_DUMP, default C:\\Users\\alexi\\bo2-dump; only names and average colours are kept)
Per texture: the vanilla texture is classified by name (grass, leaves, log bark, planks, stone, brick, ore, glass, cloth, metal ...), the BO2 images of that kind are
ranked by how close their average colour is to the vanilla one after a darkening tint, and the best one is used. Cut-out textures (leaves, glass, plants, doors, chains ...)
get mask=true: the shape (alpha) stays the vanilla one, the pixels come from the BO2 image."""
import collections, glob, json, os, re, subprocess, sys, zipfile
import numpy as np
from PIL import Image
import io

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.join(HERE, '..')
sys.path.insert(0, HERE)
import texture_gaps as TG

DUMP = os.environ.get('BO2_DUMP', r'C:\Users\alexi\bo2-dump')
ZONES = [os.path.join(DUMP, 'out4', 'zm_transit')]  # the map zone only: common_zm holds UI art
CACHE = os.path.join(HERE, 'texture_catalog.json')
OUT = os.path.join(HERE, 'texture_auto.json')
BAD = re.compile(r'(reflection|lightmap|_n$|_nml|_norm|_spc|_spec|_s$|_r$|normal|^fx|fxt_|decal|chalk|blood|bullet|demo_|^zm_sign|sign|font|hud|ui_|damage|mask|camo|^_|^\$|default|swatch|cube|clan|color_chart|dollycam|weapon|wpn|mtl_t6|_ir$|skull|data_recorder|battery|bulb|monitor|button|screen|mirror|please_wait|socket|monkey|morphine|zombie_eye|limbs|ray_gun|usa_|shells|ballistic|zombie_vending|pack_a_punch|p6_zm_|vault_door|lanternbody|couch|pool_table|school_desk|bike|tractor|dumpster|sedan|hatchback|hub_caps|locomotive|phonebooth|food_prep|restaurant|sink|stool|chair|cabinet|bench|_cap)', re.I)


def catalogue():
    if os.path.exists(CACHE): return json.load(open(CACHE))
    out = {}
    for z in ZONES:
        for f in glob.glob(os.path.join(z, 'images', '*.dds')):
            b = os.path.basename(f)[:-4]
            name = b[3:] if b.startswith('~-g') else b
            if any(c in name for c in '~&#$') or BAD.search(name) or name in out: continue
            if not (name.endswith(('_c', '_col', '_d', '_clr')) or b.startswith('~-g') or re.search(r'(crater|rubble|dirt|ground|asphalt|brick|plaster|concrete|foliage|bark|rock)', name)): continue
            try:
                im = Image.open(f).convert('RGBA'); w, h = im.size
                im = im.crop(((w - min(w, h)) // 2, (h - min(w, h)) // 2, (w + min(w, h)) // 2, (h + min(w, h)) // 2)).resize((16, 16))
                a = np.asarray(im).astype(float); al = a[..., 3] / 255
                if al.mean() < 0.93: continue  # decals and cut-outs flatten to one colour
                avg = (a[..., :3] * al[..., None]).sum((0, 1)) / max(al.sum(), 1)
                out[name] = [round(float(v)) for v in avg] + [round(float(a[..., :3].std()), 1), os.path.basename(z)]
            except Exception: pass
    json.dump(out, open(CACHE, 'w'))
    return out


# vanilla texture name -> (category, BO2 name regex in order of preference)
CATS = [
    (r'(grass_block_top|^grass|short_grass|tall_grass|fern|vine|moss|clover|bush)', 'grass', r'(grass|foliage|plant|pine|bush)'),
    (r'(leaves|azalea|flowering)', 'leaves', r'(foliage|pine|bush|plant|tree|grass)'),
    (r'(dandelion|poppy|azure|daisy|cornflower|allium|tulip|orchid|bluet|flower|rose|lily|peony|sunflower|lilac)', 'flower', r'(foliage|grass|plant|pine)'),
    (r'(_log|stripped|_wood$|bark|stem|hyphae)', 'bark', r'(bark|trunk|tree)'),
    (r'(planks|door|trapdoor|bookshelf|barrel|crafting|chest|ladder|fence|sign|scaffold|bamboo|composter|lectern|jukebox|note_block|loom|cartography)', 'wood', r'(plank|barn|cardboard|wood_floor|board)'),
    (r'(ore|spawner|raw_|amethyst|geode)', 'rock', r'(rubble|rocky|crater|dirt|ground|asphalt|debris|brick_grey|concrete)'),
    (r'(dirt|mud|clay|podzol|coarse|rooted|farmland|mycelium|soil|path|snow|sand)', 'dirt', r'(dirt|ground|rocky|crater|rubble|asphalt)'),
    (r'(gravel|tuff|basalt|diorite|granite|andesite|calcite|dripstone|cobble|deepslate|blackstone|stone|netherrack|obsidian|end_stone|terracotta_)', 'stone', r'(rubble|rocky|crater|brick_grey|asphalt|concrete|debris|ground)'),
    (r'(bricks?|brick_)', 'brick', r'(ug_wall_brick)'),
    (r'(terracotta|concrete|quartz|smooth|polished|sandstone|purpur|prismarine|wool|carpet|banner|bed|cloth)', 'paint', r'(aluminum01_painted|paintchip_gray|metal_grey01|wall_metalpanels|rus_metal_grey_c)'),
    (r'(glass|pane)', 'glass', r'(glass|window|pane)'),
    (r'(iron|chain|lantern|rail|lever|end_rod|anvil|bell|hopper|cauldron|piston|dispenser|dropper|observer|furnace|smoker|blast|grindstone|stonecutter|torch|repeater|comparator|daylight|redstone|tripwire|bars|copper|gold|netherite|lightning)', 'metal', r'(metal|corrug|steel|panel|grate|vent|duct|pipe|wire)'),
    (r'(.*)', 'generic', r'(wall|concrete|plaster|metal|floor)'),
]
CUTOUT_TOKENS = ('leaves', 'grass', 'fern', 'vine', 'flower', 'dandelion', 'poppy', 'azure', 'daisy', 'cornflower', 'allium', 'tulip', 'orchid', 'glass', 'pane', 'door', 'trapdoor', 'chain',
                 'lantern', 'rail', 'lever', 'end_rod', 'torch', 'repeater', 'comparator', 'bars', 'spawner', 'ladder', 'sapling', 'cobweb', 'tripwire', 'azalea', 'mangrove_roots', 'sign')


def vanilla_info(tex):
    p = f'assets/minecraft/textures/{tex}.png'
    im = Image.open(io.BytesIO(TG.Z.read(p))).convert('RGBA')
    w, h = im.size
    if h > w: im = im.crop((0, 0, w, w))  # animated strip: first frame
    a = np.asarray(im).astype(float); al = a[..., 3] / 255
    avg = (a[..., :3] * al[..., None]).sum((0, 1)) / max(al.sum(), 1)
    return avg, float((al < 0.9).mean())


def classify(tex):
    n = tex.split('/')[-1]
    for pat, cat, bo2 in CATS:
        if re.search(pat, n): return cat, bo2
    return 'generic', CATS[-1][2]


USES = collections.Counter()


def pick(avg, cat_re, cat, cat_cache, cut):
    cands = [(k, v) for k, v in cat_cache.items() if re.search(cat_re, k)]
    if len(cands) < (1 if cat in ('brick',) else 4): cands = [(k, v) for k, v in cat_cache.items() if re.search(r'(rubble|rocky|crater|asphalt|concrete|debris|brick_grey|ground|aluminum|wall_metalpanels|corrug)', k)]
    best = None
    for k, v in cands:
        b = np.array(v[:3], float)
        t = np.clip(avg / np.maximum(b, 1), 0, 1)
        err = float((((b * t) - avg) ** 2).sum()) ** .5 + 120 * float((1 - t).mean()) + (0 if v[3] > 14 else 20) + 22 * USES[k]  # prefer images with real detail, avoid heavy darkening, spread the choices
        if best is None or err < best[0]: best = (err, k, t)
    USES[best[1]] += 1
    return best


def main():
    cat = catalogue()
    print(len(cat), 'BO2 colour images in the catalogue')
    gaps = json.load(open(os.path.join('/tmp' if os.name != 'nt' else os.environ.get('TEMP', '.'), 'gaps.json'))) if '--cached' in sys.argv else None
    use = TG.used_blocks(); cov = TG.covered()
    bytex = collections.defaultdict(int)
    for b, n in use.items():
        if b.split(':')[1] in ('air', 'cave_air', 'void_air', 'light', 'barrier', 'structure_void') or b.startswith('zombiecraft:'): continue
        for t in TG.block_textures(b.split(':')[1]): bytex[t] += n
    rows = []
    for t in sorted(bytex, key=lambda x: -bytex[x]):
        short = t.replace('block/', '')
        if short in cov: continue
        avg, transp = vanilla_info(t)
        c, rx = classify(t)
        cut = transp > 0.08 or any(k in short for k in CUTOUT_TOKENS)
        err, name, tint = pick(avg, rx, c, cat, cut)
        row = dict(id=f'*:minecraft:{short}', map='*', texture=f'minecraft:{short}', bo2=name, opaque=not cut, note=f'auto: {c}')
        if (tint < 0.97).any(): row['tint'] = '#%02X%02X%02X' % tuple(int(round(v * 255)) for v in tint)
        if cut: row['mask'] = True
        rows.append(row)
    json.dump(rows, open(OUT, 'w'), indent=1)
    print(len(rows), 'rows ->', OUT)
    for r in rows[:25]: print(' ', r['texture'][10:], '->', r['bo2'], r.get('tint', ''), 'mask' if r.get('mask') else '', r['note'])


if __name__ == '__main__':
    main()

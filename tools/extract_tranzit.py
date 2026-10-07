"""Cut the locations of the Tranzit Reimagined world save (Planet Minecraft) out into one block file each, for use as standalone maps.
The save is the user's own download; nothing from it is committed (output goes to maps_local/, git-ignored), like the BO2 assets.
Needs: pip install nbtlib numpy pillow.
  python tools/extract_tranzit.py "<Tranzit Download dir or .zip>" [region ...]
Reads the 26.1 region files itself (no Minecraft needed), renames blocks that 1.21.4 does not have, and writes per region
maps_local/<id>.json.gz  {size, groundY, palette, rle (y, z, x order), spawners, entities}; x/z are relative to the box corner, y=0 is the grass layer
maps_local/<id>.png      top-down preview, maps_local/overview.png all regions together."""
import collections, gzip, hashlib, io, json, os, struct, sys, tempfile, zipfile, zlib
import numpy as np
import nbtlib
from PIL import Image, ImageDraw

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, '..', 'maps_local')
RENAME = {'iron_chain': 'chain', 'oxidized_lightning_rod': 'lightning_rod'}  # 26.x names -> 1.21.4
FOLIAGE = ('leaves', 'short_grass', 'tall_grass', 'fern', 'vine', 'dandelion', 'poppy', 'azure', 'daisy', 'cornflower')


def region_dir(src):
	if os.path.isdir(src):
		base = src
	else:
		base = os.path.join(tempfile.gettempdir(), 'tranzit_' + hashlib.md5(src.encode()).hexdigest()[:8])
		if not os.path.isdir(base):
			zipfile.ZipFile(src).extractall(base)
	best = None
	for root, _, files in os.walk(base):  # the zip holds a decoy nested copy: take the region folder that has the data
		if root.endswith(os.path.join('overworld', 'region')) and files:
			best = root
	if not best:
		sys.exit('no overworld/region found in ' + src)
	return best


def chunks(fn):
	b = open(fn, 'rb').read()
	for i in range(1024):
		o = int.from_bytes(b[i * 4:i * 4 + 3], 'big')
		if not o: continue
		ln, ct = struct.unpack('>IB', b[o * 4096:o * 4096 + 5])
		d = b[o * 4096 + 5:o * 4096 + 4 + ln]
		yield i % 32, i // 32, nbtlib.File.parse(io.BytesIO(zlib.decompress(d) if ct == 2 else d))


def state(p):
	n = str(p['Name']).replace('minecraft:', '')
	s = 'minecraft:' + RENAME.get(n, n)
	pr = p.get('Properties')
	if pr: s += '[' + ','.join(f'{k}={v}' for k, v in sorted(pr.items())) + ']'
	return s


def cut(rdir, box):
	x1, z1, x2, z2 = box
	sx, sz = x2 - x1 + 1, z2 - z1 + 1
	cols = {}
	pal, pid, ents = ['minecraft:air'], {'minecraft:air': 0}, []
	for cx in range(x1 >> 4, (x2 >> 4) + 1):
		for cz in range(z1 >> 4, (z2 >> 4) + 1):
			fn = f'{rdir}/r.{cx >> 5}.{cz >> 5}.mca'
			if not os.path.exists(fn): continue
			for lx, lz, c in chunks(fn):
				if (lx, lz) != (cx & 31, cz & 31): continue
				for s in c['sections']:
					sy = int(s['Y']); bs = s['block_states']
					names = [state(p) for p in bs['palette']]
					for n in names:
						if n not in pid: pid[n] = len(pal); pal.append(n)
					if len(names) == 1:
						idx = np.zeros(4096, np.int64)
					else:
						b = max(4, (len(names) - 1).bit_length()); per = 64 // b
						d = np.array(bs['data'], dtype=np.int64).view(np.uint64)
						sh = (np.arange(4096) % per * b).astype(np.uint64)
						idx = ((d[np.arange(4096) // per] >> sh) & np.uint64((1 << b) - 1)).astype(np.int64)
					m = np.array([pid[n] for n in names])[idx].reshape(16, 16, 16)  # y, z, x
					a = cols.setdefault(sy, np.zeros((16, sz, sx), np.int32))
					for z in range(16):
						for x in range(16):
							wx, wz = cx * 16 + x, cz * 16 + z
							if x1 <= wx <= x2 and z1 <= wz <= z2: a[:, wz - z1, wx - x1] = m[:, z, x]
				for be in c.get('block_entities', []):
					x, y, z = int(be['x']), int(be['y']), int(be['z'])
					if x1 <= x <= x2 and z1 <= z <= z2:
						e = {'id': str(be['id']).replace('minecraft:', ''), 'x': x - x1, 'y': y, 'z': z - z1}
						if 'front_text' in be: e['text'] = [str(m).strip('"') for m in be['front_text']['messages']]
						ents.append(e)
	ys = sorted(cols)
	grid = np.concatenate([cols[y] for y in range(ys[0], ys[-1] + 1)], axis=0)  # y, z, x
	return grid, ys[0] * 16, pal, ents


def preview(grid, pal, ground):
	foliage = [i for i, p in enumerate(pal) if any(k in p for k in FOLIAGE)] + [0]
	vis = ~np.isin(grid, foliage)
	top = grid.shape[0] - 1 - np.argmax(vis[::-1], axis=0)
	im = Image.new('RGB', (grid.shape[2], grid.shape[1]))
	px = im.load()
	for z in range(grid.shape[1]):
		for x in range(grid.shape[2]):
			n = pal[grid[top[z, x], z, x]].split('[')[0][10:]
			c = tuple(hashlib.md5(n.encode()).digest()[:3])
			if n == 'grass_block': c = (50, 120, 45)
			if n in ('stone', 'dirt', 'bedrock', 'air'): c = (90, 90, 90)
			k = max(.5, min(1.3, .7 + (top[z, x] - ground) / 24))
			px[x, z] = tuple(min(255, int(v * k)) for v in c)
	return im.resize((im.width * 4, im.height * 4), Image.NEAREST)


def lift_floors(grid, pal, gr):
	"""The game stands everything on y=0 (the grass layer): wherever the floor or the ground is lower (a sunken building floor, a ditch, a lawn round a lowered yard), fill up to y=0 with the surface block."""
	solid = np.array([not (p.startswith('minecraft:air') or any(k in p for k in FOLIAGE) or 'water' in p) for p in pal])
	notleaf = np.array(['leaves' not in p for p in pal])
	n = 0
	for z in range(grid.shape[1]):
		for x in range(grid.shape[2]):
			col = grid[:, z, x]
			if solid[col[gr]] or solid[col[gr + 1]] or solid[col[gr + 2]]: continue
			for y in range(gr - 1, max(-1, gr - 7), -1):
				if solid[col[y]]:
					grid[y + 1:gr + 1, z, x] = col[y]; n += 1; break
	return n


def main():
	src, only = sys.argv[1], sys.argv[2:]
	regions = json.load(open(os.path.join(HERE, 'tranzit_regions.json')))
	rdir = region_dir(src)
	os.makedirs(OUT, exist_ok=True)
	tiles = []
	for rid, r in regions.items():
		if rid.startswith('_') or (only and rid not in only): continue
		grid, y0, pal, ents = cut(rdir, r['box'])
		pn = np.array([p.split('[')[0][10:] for p in pal])
		top = grid.shape[0] - 1 - np.argmax((grid != 0)[::-1], axis=0)
		topn = pn[np.take_along_axis(grid, top[None], 0)[0]]
		g = [y0 + int(t) for t, n in zip(top.ravel(), topn.ravel()) if n == 'grass_block']
		ground = collections.Counter(g).most_common(1)[0][0] if g else y0
		lo = max(0, ground - 14 - y0)  # drop deep natural fill
		hi = int(np.flatnonzero((grid != 0).any(axis=(1, 2)))[-1]) + 1  # drop the empty sky sections
		grid = grid[lo:hi]; y0 += lo
		lifted = lift_floors(grid, pal, ground - y0)
		used = sorted(set(np.unique(grid).tolist()) | {0})
		lut = np.zeros(len(pal), np.int32)
		for i, o in enumerate(used): lut[o] = i
		flat = lut[grid].ravel()
		starts = np.concatenate([[0], np.flatnonzero(np.diff(flat)) + 1])
		counts = np.diff(np.concatenate([starts, [len(flat)]]))
		for e in ents: e['y'] -= ground
		doc = {'id': rid, 'label': r['label'], 'size': [grid.shape[2], grid.shape[0], grid.shape[1]], 'groundRow': ground - y0,
			'worldBox': r['box'], 'palette': [pal[o] for o in used],
			'rle': [[int(flat[s]), int(c)] for s, c in zip(starts, counts)],
			'entities': ents, 'spawners': [[e['x'], e['y'], e['z']] for e in ents if e['id'] == 'mob_spawner']}
		with gzip.open(os.path.join(OUT, rid + '.json.gz'), 'wt') as f: json.dump(doc, f, separators=(',', ':'))
		im = preview(lut[grid], doc['palette'], ground - y0)
		ImageDraw.Draw(im).text((6, 6), rid, fill=(255, 255, 255))
		im.save(os.path.join(OUT, rid + '.png'))
		tiles.append(im)
		print(rid, 'sunken floor columns lifted', lifted, doc['size'], 'grass layer row', doc['groundRow'], 'palette', len(used), 'spawners', len(doc['spawners']),
			'signs', [e['text'] for e in ents if 'text' in e])
	if tiles:
		W = 3000; sheet = Image.new('RGB', (W, 4000)); x = y = rh = 0
		for t in tiles:
			if x + t.width > W: x, y, rh = 0, y + rh, 0
			sheet.paste(t, (x, y)); x += t.width + 8; rh = max(rh, t.height + 8)
		sheet.crop((0, 0, W, y + rh)).save(os.path.join(OUT, 'overview.png'))


if __name__ == '__main__':
	main()

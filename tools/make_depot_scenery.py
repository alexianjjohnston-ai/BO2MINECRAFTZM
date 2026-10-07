#!/usr/bin/env python3
"""Crop the wide Bus Depot cut of Tranzit Reimagined (maps_local/depot_wide.json.gz, from tools/extract_tranzit.py depot_wide) down to the scenery
that surrounds the BO2 depot: roads, forest, hills. Written to maps_local/depot_scenery.json.gz, which sheets/maps/bo2_depot pastes under the play area
with the "scenery" op (air is skipped, so it is cheap) and which the barrier ring then closes in. The owner of Tranzit Reimagined allows sharing it, so this file is committed.
Crop box is in depot_wide coordinates: the compound of the original depot starts at (100, 160)."""
import gzip, json, os
import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
SRC = os.path.join(HERE, '..', 'maps_local', 'depot_wide.json.gz')
OUT = os.path.join(HERE, '..', 'maps_local', 'depot_scenery.json.gz')
BOX = (20, 80, 251, 296)  # x1, z1, x2, z2 (inclusive): ~80 blocks of margin round the compound, more than the fog shows

doc = json.load(gzip.open(SRC, 'rt'))
SX, SY, SZ = doc['size']
grid = np.concatenate([np.full(c, i, np.int32) for i, c in doc['rle']]).reshape(SY, SZ, SX)
x1, z1, x2, z2 = BOX
grid = grid[:, z1:z2 + 1, x1:x2 + 1]
used = sorted(set(np.unique(grid).tolist()) | {0})
lut = np.zeros(len(doc['palette']), np.int32)
for i, o in enumerate(used): lut[o] = i
flat = lut[grid].ravel()
starts = np.concatenate([[0], np.flatnonzero(np.diff(flat)) + 1])
counts = np.diff(np.concatenate([starts, [len(flat)]]))
out = {'id': 'depot_scenery', 'label': 'scenery round the BO2 Bus Depot, from Tranzit Reimagined', 'size': [grid.shape[2], grid.shape[0], grid.shape[1]],
       'groundRow': doc['groundRow'], 'worldBox': doc.get('worldBox'), 'cropBox': list(BOX), 'palette': [doc['palette'][o] for o in used],
       'rle': [[int(flat[s]), int(c)] for s, c in zip(starts, counts)], 'entities': [], 'spawners': []}
with gzip.open(OUT, 'wt') as f: json.dump(out, f, separators=(',', ':'))
print(out['size'], 'palette', len(used), 'runs', len(out['rle']), os.path.getsize(OUT), 'bytes')

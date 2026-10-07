#!/usr/bin/env python3
"""Contact sheet of tools/texture_auto.json: vanilla texture | what the pack will make from the BO2 image (tint, mask applied). python tools/texture_preview.py -> maps_local/texture_preview.png"""
import io, json, os, sys
import numpy as np
from PIL import Image, ImageDraw
HERE = os.path.dirname(os.path.abspath(__file__)); sys.path.insert(0, HERE)
import texture_gaps as TG
import texture_auto as TA
rows = json.load(open(os.path.join(HERE, 'texture_auto.json')))
cat = json.load(open(TA.CACHE))
def bo2(name):
    for z in TA.ZONES:
        for f in ('~-g' + name + '.dds', name + '.dds'):
            p = os.path.join(z, 'images', f)
            if os.path.exists(p): return Image.open(p).convert('RGBA')
S = 56; cols = 6; N = len(rows)
sheet = Image.new('RGB', (cols * (S * 2 + 150), (N // cols + 1) * (S + 4)), (30, 30, 30)); d = ImageDraw.Draw(sheet)
for i, r in enumerate(rows):
    x0, y0 = (i % cols) * (S * 2 + 150), (i // cols) * (S + 4)
    t = r['texture'][10:]
    v = Image.open(io.BytesIO(TG.Z.read(f'assets/minecraft/textures/block/{t}.png'))).convert('RGBA'); w, h = v.size
    if h > w: v = v.crop((0, 0, w, w))
    sheet.paste(v.resize((S, S), Image.NEAREST), (x0, y0))
    im = bo2(r['bo2'])
    if im:
        w, h = im.size; m = min(w, h); im = im.crop(((w - m) // 2, (h - m) // 2, (w + m) // 2, (h + m) // 2)).resize((S, S))
        a = np.asarray(im).astype(float); al0 = a[..., 3] / 255; m_ = al0 > .63
        avg = a[..., :3][m_].mean(0) if m_.any() else np.array([90., 90, 90])
        a[..., :3] = a[..., :3] * al0[..., None] + avg * (1 - al0[..., None]); a[..., 3] = 255
        if r.get('tint'):
            tc = [int(r['tint'][k:k + 2], 16) / 255 for k in (1, 3, 5)]; a[..., :3] *= tc
        al = np.asarray(v.resize((S, S), Image.NEAREST))[..., 3] if r.get('mask') else 255
        out = Image.fromarray(a.astype(np.uint8)).convert('RGBA')
        if r.get('mask'): out.putalpha(Image.fromarray(np.asarray(al).astype(np.uint8)))
        bg = Image.new('RGBA', (S, S), (60, 0, 60, 255)); bg.alpha_composite(out); sheet.paste(bg.convert('RGB'), (x0 + S, y0))
    d.text((x0 + S * 2 + 4, y0 + 2), t[:24], fill=(255, 255, 255)); d.text((x0 + S * 2 + 4, y0 + 14), r['bo2'][:24], fill=(180, 220, 255))
sheet.save(os.path.join(HERE, '..', 'maps_local', 'texture_preview.png')); print(sheet.size)

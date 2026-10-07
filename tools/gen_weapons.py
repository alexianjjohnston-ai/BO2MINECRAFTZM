#!/usr/bin/env python3
"""Writes sheets/weapons.json from BO2's own weapon files (dumped read-only with OpenAssetTools into the dev folder).
Numbers are facts read from the game: damage, falloff, fire time, reload times, clip, magazines, projectile and explosion values.
Inches -> blocks: x0.0254 (1 block = 1 m). The row for an upgraded gun is separate, exactly like BO2's *_upgraded_zm."""
import json, os

BS = chr(92)
DUMP = os.environ.get('WEAPON_DUMP', r'C:\Users\alexi\bo2-dump\out3\zm_transit\weapons')
SHEETS = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', 'sheets')
IN = 0.0254

def parse(name):
    t = open(os.path.join(DUMP, name), 'rb').read().decode('latin1')
    parts = t.split(BS)
    d = {}
    i = 1
    while i + 1 < len(parts):
        d[parts[i]] = parts[i + 1]; i += 2
    return d

def num(d, k, default=0.0):
    try: return float(d.get(k, default) or default)
    except ValueError: return default

# id, display name, bo2 base file, bo2 upgraded file, kind, shape, colour, wall cost, box weight, start, pap name, spread, reload cue names
GUNS = [
    ('m1911',      'M1911',   'm1911_zm',      'm1911_upgraded_zm',  'pistol',   'pistol',   '#7a7a7a', None, 0.0, True,  'Mustang and Sally',   0.6, ('fly_1911_mag_out', 'fly_1911_mag_in', 'fly_1911_slide_forward'), 'wpn_1911_dryfire_plr'),
    ('rottweil72', 'Olympia', 'rottweil72_zm', 'rottweil72_upgraded_zm', 'shotgun', 'shotgun',  '#8b5a2b', 500, 1.0, False, 'Hades',               4.5, ('wpn_rottweil72_open', 'wpn_rottweil72_shell_in', 'wpn_rottweil72_close'), 'wpn_rottweil72_dryfire_plr'),
    ('mp5k',       'MP5K',    'mp5k_zm',       'mp5k_upgraded_zm',   'smg',      'smg',      '#4a4a4a', 1000, 1.0, False, 'MP115 Kollider',      1.5, ('fly_mp5k_mag_out', 'fly_mp5k_mag_in', 'fly_mp5k_charge'), 'fly_fire_select_button'),
    ('ak74u',      'AK74u',   'ak74u_zm',      'ak74u_upgraded_zm',  'smg',      'smg',      '#5b4a2e', 1200, 1.0, False, 'AK-74fu2',            1.5, ('fly_ak74u_mag_out', 'fly_ak74u_mag_in', 'fly_ak74u_charge'), 'wpn_ak74u_dryfire_plr'),
    ('m14',        'M14',     'm14_zm',        'm14_upgraded_zm',    'rifle',    'rifle',    '#6b4f2a', 500, 1.0, False, 'Mnesia',               0.5, ('fly_m14_mag_out', 'fly_m14_mag_in', 'fly_m14_bolt_release'), 'wpn_m14_dryfire_plr'),
    ('m16',        'M16',     'm16_zm',        'm16_gl_upgraded_zm', 'rifle',    'rifle',    '#3d4a3a', 1200, 1.0, False, 'Skullcrusher',        1.0, ('fly_m16_mag_out', 'fly_m16_mag_in', 'fly_m16_charge'), 'wpn_m16_dryfire_plr'),
    ('galil',      'Galil',   'galil_zm',      'galil_upgraded_zm',  'rifle',    'rifle',    '#556b2f', None, 1.0, False, 'Lamentation',         1.0, ('fly_galil_mag_out', 'fly_galil_mag_in', 'fly_galil_charge'), 'wpn_galil_dryfire_plr'),
    ('python',     'Python',  'python_zm',     'python_upgraded_zm', 'pistol',   'revolver', '#9a9a9a', None, 1.0, False, 'Cobra',               0.5, ('fly_python_open', 'fly_python_load', 'fly_python_close'), 'wpn_python_dryfire_plr'),
    ('fnfal',      'FAL',     'fnfal_zm',      'fnfal_upgraded_zm',  'rifle',    'rifle',    '#4a4a52', None, 1.0, False, 'FAL PaP',             1.0, ('fly_fnfal_mag_out', 'fly_fnfal_mag_in', 'fly_fnfal_charge'), 'wpn_fnfal_dryfire_plr'),
    ('saiga12',    'Saiga 12', 'saiga12_zm',   'saiga12_upgraded_zm', 'shotgun', 'shotgun',  '#5a4a3a', None, 1.0, False, 'Saiga 12 PaP',        4.5, ('fly_saiga12_mag_out', 'fly_saiga12_mag_in', 'fly_saiga12_release'), 'wpn_saiga12_dryfire_plr'),
    ('rpd',        'RPD',     'rpd_zm',        'rpd_upgraded_zm',    'rifle',    'rifle',    '#3d4538', None, 1.0, False, 'RPD PaP',             2.5, ('fly_rpd_mag_out', 'fly_rpd_mag_in', 'fly_rpd_close'), 'wpn_rpd_dryfire_plr'),
    ('judge',      'Executioner', 'judge_zm',  'judge_upgraded_zm',  'pistol',   'revolver', '#8a8a8a', None, 1.0, False, 'Executioner PaP',     4.0, ('fly_judge_open', 'fly_judge_load', 'fly_judge_close'), 'wpn_judge_dryfire_plr'),
    ('ray_gun',    'Ray Gun', 'ray_gun_zm',    'ray_gun_upgraded_zm', 'raygun',  'raygun',   '#3fb86b', None, 0.35, False, "Porter's X2 Ray Gun", 0.0, ('wpn_ray_reload_battery_out', 'wpn_ray_reload_battery_in', 'wpn_ray_reload_close'), 'wpn_1911_dryfire_plr'),
]

def row(id_, name, bo2, d, kind, shape, color, wall, boxw, start, papname, papid, upgrade, spread, reload_cues, dry, base_clip_for_reserve=None):
    mode = {'Single Shot': 'semi', 'Full Auto': 'auto', '3-Round Burst': 'burst'}.get(d.get('fireType'), 'semi')
    projectile = d.get('weaponType') == 'projectile'
    pellets = int(num(d, 'shotCount', 1)) if d.get('weaponClass') == 'spread' else 1
    if pellets == 0: pellets = 1
    clip = int(num(d, 'clipSize', 1))
    mags = int(num(d, 'maxAmmo', 1))
    reserve = mags if d.get('weaponType') == 'projectile' else clip * mags   # projectile weapons list total ammo, bullet weapons list magazines
    dmg = num(d, 'damage'); dmin = num(d, 'minDamage')
    if projectile:
        dmg = num(d, 'explosionInnerDamage'); dmin = num(d, 'explosionOuterDamage')
    return dict(
        id=id_, name=name, bo2Id=bo2, kind=kind, fireMode=('semi' if projectile and mode == 'semi' else mode),
        damage=dmg, damageMin=dmin, rangeFull=round(num(d, 'maxDamageRange') * IN, 1) if not projectile else 0.0,
        rangeMin=round(num(d, 'minDamageRange') * IN, 1) if not projectile else 0.0,
        headMult=1.5 if pellets > 1 else (1.0 if projectile else 3.0), pellets=pellets, spreadDeg=spread,
        fireTime=num(d, 'fireTime', 0.1), burstCount=3 if mode == 'burst' else 1, burstGap=num(d, 'burstFireDelay') if mode == 'burst' else 0.0,
        mag=clip, reserve=reserve, startReserve=(int(num(d, 'startAmmo', mags)) * (1 if projectile else clip)), reloadTime=num(d, 'reloadTime', 2.0), reloadEmptyTime=num(d, 'reloadEmptyTime', num(d, 'reloadTime', 2.0)),
        projectile=projectile, projSpeed=round(num(d, 'projectileSpeed') * IN, 1), explRadius=round(num(d, 'explosionRadius') * IN, 2),
        range=64.0,
        wallCost=wall, boxWeight=boxw, start=start, upgrade=upgrade, papId=(papid or None), papName=papname,
        cueFire=d.get('fireSoundPlayer') or d.get('fireSound'), cueDry=dry,
        cueReloadOut=reload_cues[0], cueReloadIn=reload_cues[1], cueReloadEnd=reload_cues[2],
        cueProjectile=('wpn_rgun_loop' if projectile and 'rgun' in (d.get('fireSoundPlayer') or '') else None),
        cueExplosion=('wpn_rgun_exp' if projectile and 'rgun' in (d.get('fireSoundPlayer') or '') else None),
        iconShape=shape, iconColor=color, status='weapon-file')

rows = []
for (id_, name, base, up, kind, shape, color, wall, boxw, start, papname, spread, rc, dry) in GUNS:
    b = parse(base); u = parse(up)
    rows.append(row(id_, name, base, b, kind, shape, color, wall, boxw, start, papname, id_ + '_pap', False, spread, rc, dry))
    rows.append(row(id_ + '_pap', papname, up, u, kind, shape, color, None, 0.0, False, papname, '', True, spread, rc, dry))
json.dump(rows, open(os.path.join(SHEETS, '_next', 'weapons_bo2.json'), 'w', encoding='utf-8'), indent=1)
print(len(rows), 'weapon rows')
for r in rows:
    print(f"{r['id']:14s} dmg {r['damage']:6.0f}/{r['damageMin']:5.0f} range {r['rangeFull']:5.1f}-{r['rangeMin']:5.1f} t={r['fireTime']:.3f} clip {r['mag']:3d} res {r['reserve']:4d} rel {r['reloadTime']:.2f}/{r['reloadEmptyTime']:.2f} {r['fireMode']:6s} pellets {r['pellets']} proj={r['projectile']} {r['cueFire']}")

pool = [dict(id='pool_' + r['id'], weaponId=r['id'], weight=r['boxWeight'], source='uniform draw as in _zm_magicbox.gsc; the Ray Gun is rarer (tuned)')
        for r in rows if not r['upgrade'] and r['boxWeight'] > 0]
json.dump(pool, open(os.path.join(SHEETS, 'box_pool.json'), 'w', encoding='utf-8'), indent=1)
print(len(pool), 'box pool rows')

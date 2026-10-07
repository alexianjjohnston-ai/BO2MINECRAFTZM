import json, collections
ents = json.load(open('assets_src/depot/ents.json'))
X0,X1,Y0,Y1 = -8400,-5600,3500,6900
def org(e):
    try: return tuple(float(v) for v in e['origin'].split())
    except Exception: return None
inb = [e for e in ents if (o:=org(e)) and X0<=o[0]<=X1 and Y0<=o[1]<=Y1]
json.dump(inb, open('assets_src/depot/depot_ents.json','w'))
print(len(inb), 'entities in depot box')
for k,v in collections.Counter(e['classname'] for e in inb).most_common(): print(v,k)
nodes=[org(e) for e in inb if e['classname']=='node_pathnode']
zs=[n[2] for n in nodes]; xs=[n[0] for n in nodes]; ys=[n[1] for n in nodes]
print('nodes x',min(xs),max(xs),'y',min(ys),max(ys),'z',min(zs),max(zs))
print('--- models'); 
for e in inb:
    if e['classname']=='script_model': print(e.get('model'), e['origin'], e.get('targetname'), e.get('script_noteworthy'))
print('--- triggers/doors/perks')
for e in inb:
    if e['classname'] in('trigger_use','trigger_use_touch','script_brushmodel','zbarrier_zmcore_MagicBox','zbarrier_zb_BusWindow5','zbarrier_zb_BusWindow0','info_player_start','info_volume'):
        print(e['classname'], e['origin'], {k:v for k,v in e.items() if k in('targetname','script_noteworthy','script_string','zombie_cost','target','script_flag','model')})

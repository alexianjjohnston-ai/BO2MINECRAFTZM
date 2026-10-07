import json
from PIL import Image, ImageDraw
ents=json.load(open('assets_src/depot/depot_ents.json'))
X0,Y1,S=-8400,6900,40; W,H=70,86; PX=10
def org(e): return [float(v) for v in e['origin'].split()]
def tb(o): return ((o[0]-X0)/S, (Y1-o[1])/S)
im=Image.new('RGB',(W*PX,H*PX),(20,20,20)); d=ImageDraw.Draw(im)
for g in range(0,W,10): d.line([(g*PX,0),(g*PX,H*PX)],fill=(50,50,50)); d.text((g*PX+2,2),str(g),fill=(150,150,150))
for g in range(0,H,10): d.line([(0,g*PX),(W*PX,g*PX)],fill=(50,50,50)); d.text((2,g*PX+2),str(g),fill=(150,150,150))
for e in ents:
    if e['classname']=='node_pathnode':
        x,z=tb(org(e)); h=org(e)[2]; c=int(max(0,min(255,(h+174)/380*255)))
        d.rectangle([x*PX-2,z*PX-2,x*PX+2,z*PX+2],fill=(c,200-c//2,255-c))
cols={'zbarrier_zmcore_BasicWoodBarrier':(255,160,0),'zbarrier_zb_BusWindow5':(255,255,0),'zbarrier_zmcore_MagicBox':(255,0,255),'info_player_start':(255,255,255)}
for e in ents:
    c=cols.get(e['classname'])
    if c:
        x,z=tb(org(e)); d.ellipse([x*PX-5,z*PX-5,x*PX+5,z*PX+5],outline=c,width=2)
    if e['classname']=='script_struct':
        x,z=tb(org(e)); t=e.get('targetname') or ''
        if t=='initial_spawn_points': d.ellipse([x*PX-4,z*PX-4,x*PX+4,z*PX+4],fill=(0,255,0))
        elif 'spawners' in t: d.rectangle([x*PX-3,z*PX-3,x*PX+3,z*PX+3],outline=(255,60,60))
        elif t=='weapon_upgrade': d.text((x*PX,z*PX),'W',fill=(255,255,255))
        elif t=='zm_perk_machine': d.text((x*PX,z*PX),'P',fill=(0,255,255))
    if e['classname']=='info_volume' and e.get('script_noteworthy')=='lava_volume':
        x,z=tb(org(e)); d.text((x*PX,z*PX),'L',fill=(255,80,0))
im.save('assets_src/depot/plot.png'); print(im.size)

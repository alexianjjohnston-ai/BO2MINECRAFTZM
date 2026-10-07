"""Fill sheets/audio.json and sheets/audio_files.json from the DEV alias map built from the creator's own BO2 install.
Only metadata is written (alias name, bank file name, entry id, size, format). No audio ever enters the project."""
import json,sys,os,glob
HERE=os.path.dirname(os.path.abspath(__file__)); SH=os.path.join(HERE,'..','sheets')
BO2=os.environ.get('BO2_DIR','D:/SteamLibrary/steamapps/common/Call of Duty Black Ops II')
ALIASMAP=os.environ.get('ALIAS_MAP','C:/Users/alexi/bo2-dump/work/alias_map_transit.json')
sys.path.insert(0,'C:/Users/alexi/bo2-dump/work')
from sab import read_bank
RATES=[8000,12000,16000,24000,32000,44100,48000,96000,192000]
seed=json.load(open(os.path.join(HERE,'audio_seed.json')))
amap=json.load(open(ALIASMAP))
cues={}  # alias -> settings
for a,s in seed['base'].items(): cues[a]=dict(s)
def load(n): return json.load(open(os.path.join(SH,n)))
for sheet in ('weapons.json','zombies.json'):
    for row in load(sheet):
        for col,val in row.items():
            if col in seed['roles'] and val and val not in cues:
                cues[val]=dict(seed['roles'][col])
banks={}
def ent(bank,id_):
    if bank not in banks: banks[bank]={e['id']:e for e in read_bank(os.path.join(BO2,'sound',bank))['ents']}
    return banks[bank][id_]
audio=[];files=[]
for alias in sorted(cues):
    s=cues[alias]
    if alias not in amap: print('MISSING alias',alias); continue
    n=0; chans=set(); loop=0
    for v in amap[alias]:
        bank=v['banks'][0]; e=ent(bank,v['id'])
        files.append(dict(id=f"{alias}#{n}",cue=alias,bank=bank,entryId=v['id'],size=e['size'],format="flac" if e['fmt']==8 else "pcm16",
                          channels=e['ch'],rateHz=RATES[e['rate_idx']],frames=e['frames']))
        chans.add(e['ch']); loop=max(loop,e['loop']); n+=1
    if 'loop' in s: loop = s['loop']
    positional = (s['category'] in ('world','vox','weapon')) and chans=={1}
    audio.append(dict(cue=alias,category=s['category'],soundSource=s['soundSource'],volume=s['volume'],positional=positional,
                      loop=bool(loop),variants=n,fallback=s['fallback']))
json.dump(audio,open(os.path.join(SH,'audio.json'),'w'),indent=1)
json.dump(files,open(os.path.join(SH,'audio_files.json'),'w'),indent=1)
print(len(audio),'cues,',len(files),'files')

import re, sys, json, collections
p = sys.argv[1]
txt = open(p, encoding='latin-1').read()
ents = []
for blk in re.findall(r'\{(.*?)\n\}', txt, re.S):
    d = dict(re.findall(r'"([^"]+)" "([^"]*)"', blk))
    if d: ents.append(d)
json.dump(ents, open(sys.argv[2], 'w'))
print(len(ents), 'entities')
c = collections.Counter(e.get('classname') for e in ents)
for k, v in c.most_common(40): print(v, k)
print('--- noteworthy / targetname matching depot/bus/station')
for e in ents:
    s = ' '.join(e.values()).lower()
    if re.search(r'depot|busstation|bus_station|station', s) and e.get('classname','').startswith(('script_','trigger','info_','zbarrier')):
        print(e.get('classname'), e.get('origin'), e.get('targetname'), e.get('script_noteworthy'), e.get('script_string'), e.get('zombie_zone'))

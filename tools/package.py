#!/usr/bin/env python3
"""Builds the release zip: a portable Prism Launcher with a ready Zombiecraft instance (Minecraft 1.21.4 + Fabric + our mod).

  python tools/package.py            # uses mod/build/libs/zombiecraft-<version>.jar (run gradle build first)
Output: dist/Zombiecraft-<version>.zip, dist/package.json (size, sha256, entries)

Everything third-party comes from its official release page and is listed in the credits folder inside the zip.
No Minecraft files, no Black Ops II files, no secrets are included.
"""
import hashlib, json, os, shutil, sys, urllib.request, zipfile

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), '..'))
DL = os.environ.get('ZC_DOWNLOADS', 'C:/Users/alexi/bo2-dump/downloads')
gp = dict(l.strip().split('=', 1) for l in open(os.path.join(ROOT, 'mod', 'gradle.properties')) if '=' in l and not l.startswith('#'))
VERSION = gp['version']; MC = gp['minecraft_version']; LOADER = gp['loader_version']; FAPI = gp['fabric_api_version']
PRISM_VER = '11.1.1'
PRISM_URL = f'https://github.com/PrismLauncher/PrismLauncher/releases/download/{PRISM_VER}/PrismLauncher-Windows-MinGW-w64-Portable-{PRISM_VER}.zip'
FAPI_URL = 'https://maven.fabricmc.net/net/fabricmc/fabric-api/fabric-api/' + FAPI.replace('+', '%2B') + '/fabric-api-' + FAPI.replace('+', '%2B') + '.jar'
MOD_JAR = os.path.join(ROOT, 'mod', 'build', 'libs', f'zombiecraft-{VERSION}.jar')
STAGE = os.path.join(ROOT, 'dist', 'stage')
OUT = os.path.join(ROOT, 'dist', f'Zombiecraft-{VERSION}.zip')

def fetch(url, name):
    os.makedirs(DL, exist_ok=True)
    p = os.path.join(DL, name)
    if not os.path.exists(p):
        print('downloading', url)
        urllib.request.urlretrieve(url, p)
    return p

def sha256(p):
    h = hashlib.sha256()
    with open(p, 'rb') as f:
        for b in iter(lambda: f.read(1 << 20), b''): h.update(b)
    return h.hexdigest()

if not os.path.exists(MOD_JAR): sys.exit(f'build the mod first: {MOD_JAR} is missing')
prism_zip = fetch(PRISM_URL, f'PrismLauncher-MinGW-w64-Portable-{PRISM_VER}.zip')
fapi_jar = fetch(FAPI_URL, f'fabric-api-{FAPI}.jar')
prism_lic = fetch(f'https://raw.githubusercontent.com/PrismLauncher/PrismLauncher/{PRISM_VER}/COPYING.md', f'PrismLauncher-{PRISM_VER}-COPYING.md')

shutil.rmtree(STAGE, ignore_errors=True)
prism = os.path.join(STAGE, 'Prism')
os.makedirs(prism)
with zipfile.ZipFile(prism_zip) as z: z.extractall(prism)
# the portable build is a flat folder; make sure portable mode is on
open(os.path.join(prism, 'portable.txt'), 'a').close()

# Prism settings: skip every setup page except the Microsoft sign-in (language, theme and Java download are preset)
open(os.path.join(prism, 'prismlauncher.cfg'), 'w', newline='\n').write('\n'.join([
    '[General]', 'ConfigVersion=1.2', 'Language=en_US', 'ApplicationTheme=system', 'IconTheme=pe_colored',
    'AutomaticJavaDownload=true', 'AutomaticJavaSwitch=true', 'UserAskedAboutAutomaticJavaDownload=true', 'IgnoreJavaWizard=true',
    'InstanceDir=instances', 'MaxMemAlloc=3072', 'MinMemAlloc=1024', 'ShowConsole=false', 'ShowConsoleOnError=true', 'CloseAfterLaunch=false', 'MenuBarInsteadOfToolbar=false', ''
]))

inst = os.path.join(prism, 'instances', 'Zombiecraft')
mc = os.path.join(inst, 'minecraft')
os.makedirs(os.path.join(mc, 'mods')); os.makedirs(os.path.join(mc, 'config'))
open(os.path.join(prism, 'instances', 'instgroups.json'), 'w').write(json.dumps({"formatVersion": "1", "groups": {}}))
open(os.path.join(inst, 'instance.cfg'), 'w', newline='\n').write('\n'.join([
    '[General]', 'ConfigVersion=1.2', 'InstanceType=OneSix', 'iconKey=default', 'name=Zombiecraft', f'notes=Zombiecraft {VERSION}',
    'OverrideMemory=true', 'MaxMemAlloc=3072', 'MinMemAlloc=1024',
    'OverrideConsole=true', 'ShowConsole=false', 'ShowConsoleOnError=true', 'AutoCloseConsole=true', 'RecordGameTime=false', ''
]))
json.dump({"components": [
    {"cachedName": "LWJGL 3", "cachedVersion": "3.3.3", "cachedVolatile": True, "dependencyOnly": True, "uid": "org.lwjgl3", "version": "3.3.3"},
    {"cachedName": "Minecraft", "cachedRequires": [{"suggests": "3.3.3", "uid": "org.lwjgl3"}], "cachedVersion": MC, "important": True, "uid": "net.minecraft", "version": MC},
    {"cachedName": "Intermediary Mappings", "cachedRequires": [{"equals": MC, "uid": "net.minecraft"}], "cachedVersion": MC, "cachedVolatile": True, "dependencyOnly": True, "uid": "net.fabricmc.intermediary", "version": MC},
    {"cachedName": "Fabric Loader", "cachedRequires": [{"uid": "net.fabricmc.intermediary"}], "cachedVersion": LOADER, "uid": "net.fabricmc.fabric-loader", "version": LOADER}],
    "formatVersion": 1}, open(os.path.join(inst, 'mmc-pack.json'), 'w'), indent=2)
shutil.copy(MOD_JAR, os.path.join(mc, 'mods', f'zombiecraft-{VERSION}.jar'))
shutil.copy(fapi_jar, os.path.join(mc, 'mods', os.path.basename(fapi_jar).replace('%2B', '+')))
# options: no vanilla music over the BO2 stings, no multiplayer warning, windowed, and hands off the F key's default job
open(os.path.join(mc, 'options.txt'), 'w', newline='\n').write('\n'.join([
    'version:4189', 'lang:en_us', 'onboardAccessibility:false', 'skipMultiplayerWarning:true', 'tutorialStep:none', 'joinedFirstServer:true',
    'pauseOnLostFocus:true', 'soundCategory_music:0.0', 'renderDistance:10', 'fov:0.5', 'fullscreen:false', 'key_key.swapOffhand:key.keyboard.unknown', ''
]))

# credits and licences (GPL needs the source pointer for the bundled launcher)
cr = os.path.join(STAGE, 'Credits')
os.makedirs(cr)
open(os.path.join(cr, 'README.txt'), 'w', newline='\n').write(f"""Zombiecraft {VERSION}: Zombies-style survival in Minecraft: Java Edition.
An unofficial fan project. Not made by or affiliated with Activision, Treyarch, Mojang or Microsoft. Call of Duty and Black Ops are trademarks of Activision.

What is inside this download:
- Zombiecraft (mod code, sheets, placeholder art): MIT licence, see MOD-LICENSE.txt.
- Prism Launcher {PRISM_VER} (portable): GPL-3.0, https://github.com/PrismLauncher/PrismLauncher (source and licence are there; the licence is also in Prism/ next to the program).
- Fabric API {FAPI} (Apache-2.0): https://github.com/FabricMC/fabric-api  Fabric Loader (Apache-2.0) is downloaded by Prism on first launch.
- Minecraft itself is NOT included: Prism downloads it from Mojang for the account that signs in (you need to own Minecraft: Java Edition).

What is NOT inside:
- No Black Ops II files. The sounds are read from YOUR OWN Black Ops II install on your PC the first time you play, and saved as a private cache
  in this game's folder. Nothing is changed in Black Ops II, and it is never started.
- No passwords, keys or tokens.

First start: Prism asks you to sign in with the Microsoft account that owns Minecraft. After that it downloads Java and Minecraft by itself.
""")
mit = f"""MIT License

Copyright (c) 2026 the Zombiecraft authors

Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
"""
open(os.path.join(cr, 'MOD-LICENSE.txt'), 'w', newline='\n').write(mit)
shutil.copy(prism_lic, os.path.join(cr, 'PRISM-LAUNCHER-LICENSE.md'))

# zip it
os.makedirs(os.path.dirname(OUT), exist_ok=True)
if os.path.exists(OUT): os.remove(OUT)
entries = []
with zipfile.ZipFile(OUT, 'w', zipfile.ZIP_DEFLATED, compresslevel=6) as z:
    for dp, dn, fn in os.walk(STAGE):
        for f in sorted(fn):
            full = os.path.join(dp, f)
            rel = os.path.relpath(full, STAGE).replace(os.sep, '/')
            z.write(full, rel)
            entries.append({"path": rel, "size": os.path.getsize(full)})
info = {"fileName": os.path.basename(OUT), "size": os.path.getsize(OUT), "sha256": sha256(OUT), "entries": entries}
json.dump(info, open(os.path.join(ROOT, 'dist', 'package.json'), 'w'), indent=1)
print(f"{info['fileName']}: {info['size']/1e6:.1f} MB, {len(entries)} files, sha256 {info['sha256'][:16]}...")

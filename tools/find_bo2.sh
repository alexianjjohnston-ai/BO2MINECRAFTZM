#!/bin/bash
# Finds the Black Ops II files and prints their folder (nothing if not found). Read-only except the remembered choice in .bo2dir.
# Black Ops II has no Mac or Linux version, so the files come from a Steam library copied over, a Windows Steam inside a Wine bottle
# (CrossOver, Whisky, Heroic, Bottles), or a copy on an external drive. Order: remembered choice -> Steam libraries -> Wine bottles ->
# external drives -> a shallow scan of the usual folders -> folder picker. Pass --no-prompt to skip the picker.
root="$(cd "$(dirname "$0")/.." && pwd)"
memo="$root/.bo2dir"
marker="sound/zmb_common.all.sabl"
ok() { [ -n "$1" ] && [ -f "$1/$marker" ]; }
done_() { printf '%s\n' "$1"; exit 0; }

if [ -f "$memo" ]; then p="$(head -n 1 "$memo")"; ok "$p" && done_ "$p"; fi

names=("Call of Duty Black Ops II" "Call of Duty - Black Ops II" "Call of Duty Black Ops 2" "Black Ops II" "Black Ops 2")
libs=()
add() { [ -d "$1" ] && libs+=("$1"); }

# Steam on this machine, then every library its libraryfolders.vdf lists
for s in "$HOME/Library/Application Support/Steam" "$HOME/.steam/steam" "$HOME/.local/share/Steam" "$HOME/.var/app/com.valvesoftware.Steam/.local/share/Steam"; do
  add "$s"
  vdf="$s/steamapps/libraryfolders.vdf"
  if [ -f "$vdf" ]; then
    while IFS= read -r l; do add "$l"; done < <(sed -n 's/.*"path"[[:space:]]*"\([^"]*\)".*/\1/p' "$vdf" | sed 's/\\\\/\\/g')
  fi
done
# Wine prefixes: a bottle's drive_c is a normal Windows layout
for b in "$HOME/Library/Application Support/CrossOver/Bottles" "$HOME/Library/Containers/com.isaacmarovitz.Whisky/Bottles" "$HOME/Games/Heroic/Prefixes" "$HOME/.local/share/bottles/bottles" "$HOME/.wine"; do
  for pre in "$b" "$b"/*; do
    for rel in "drive_c/Program Files (x86)/Steam" "drive_c/Program Files/Steam" "drive_c/GOG Games" "drive_c/Program Files (x86)/GOG Galaxy/Games"; do add "$pre/$rel"; done
  done
done
# external drives and the usual places a copied Steam library ends up
for m in /Volumes/* /media/* /mnt/* "/run/media/$USER"/*; do add "$m"; add "$m/SteamLibrary"; add "$m/Steam"; add "$m/Games"; done
for d in "$HOME/Games" "$HOME/SteamLibrary" "$HOME/Steam" "$HOME/Desktop" "$HOME/Downloads" "$HOME/Documents"; do add "$d"; add "$d/SteamLibrary"; add "$d/Steam"; done

for l in "${libs[@]}"; do
  for n in "${names[@]}"; do
    ok "$l/steamapps/common/$n" && done_ "$l/steamapps/common/$n"
    ok "$l/$n" && done_ "$l/$n"
  done
done

# Shallow scan: a folder up to 4 levels deep whose name looks like Black Ops II
while IFS= read -r c; do ok "$c" && done_ "$c"; done < <(find "$HOME" /Volumes /media /mnt -maxdepth 4 \( -path "$HOME/Library" -o -path "$HOME/.Trash" \) -prune -o -type d \( -iname '*black*ops*2*' -o -iname '*black*ops*ii*' \) -print 2>/dev/null)

[ "$1" = "--no-prompt" ] && exit 0

# Not found: let the user pick the folder, and remember it
echo "[bo2] Could not find Black Ops II automatically. Pick its folder (the one containing the \"sound\" folder)." >&2
while true; do
  if [ "$(uname -s)" = "Darwin" ] && command -v osascript >/dev/null 2>&1; then
    f="$(osascript -e 'POSIX path of (choose folder with prompt "Select your Call of Duty Black Ops II folder (contains sound/zmb_common.all.sabl). Cancel to skip.")' 2>/dev/null)" || exit 0
  elif [ -t 0 ]; then
    read -r -p "Black Ops II folder (empty to skip): " f </dev/tty || exit 0
    [ -z "$f" ] && exit 0
  else
    exit 0
  fi
  f="${f%/}"
  if ok "$f"; then printf '%s\n' "$f" > "$memo"; done_ "$f"; fi
  echo "[bo2] That folder does not contain $marker. Pick the Black Ops II folder itself." >&2
done

#!/bin/bash
# macOS only: prints the path of a Wine binary for running the Windows OpenAssetTools Unlinker (it has no Mac build). Installs a
# prebuilt Wine (Gcenx/macOS_Wine_builds, about 190 MB) into ./.wine-app with curl, so no Homebrew and no admin rights are needed,
# and Rosetta on Apple Silicon. Messages go to stderr so stdout is just the path. Exit 1 if there is no Wine at the end.
root="$(cd "$(dirname "$0")/.." && pwd)"
[ "$(uname -s)" = "Darwin" ] || exit 1

find_wine() {
  local c
  for c in "$root"/.wine-app/*/Contents/Resources/wine/bin/wine "/Applications/Wine Stable.app/Contents/Resources/wine/bin/wine" \
           "/Applications/Wine Devel.app/Contents/Resources/wine/bin/wine" "/Applications/Wine Staging.app/Contents/Resources/wine/bin/wine" \
           /opt/homebrew/bin/wine /usr/local/bin/wine; do
    if [ -x "$c" ]; then echo "$c"; return 0; fi
  done
  return 1
}
find_wine && exit 0

# Wine here is an Intel program: Apple Silicon needs Rosetta to run it
if [ "$(uname -m)" = "arm64" ] && ! arch -x86_64 /usr/bin/true 2>/dev/null; then
  echo "[wine] Installing Rosetta (asks for your password once)..." >&2
  softwareupdate --install-rosetta --agree-to-license >&2 || sudo softwareupdate --install-rosetta --agree-to-license >&2
fi

api="https://api.github.com/repos/Gcenx/macOS_Wine_builds/releases/latest"
url="$(curl -fsSL "$api" | grep -o 'https://[^"]*wine-devel-[^"]*osx64\.tar\.xz' | head -n 1)"
[ -n "$url" ] || url="https://github.com/Gcenx/macOS_Wine_builds/releases/download/11.18/wine-devel-11.18-osx64.tar.xz"
echo "[wine] Downloading Wine (about 190 MB)..." >&2
mkdir -p "$root/.wine-app" || exit 1
tmp="$(mktemp -d "${TMPDIR:-/tmp}/zc-wine.XXXXXX")" || exit 1
if curl -fL --progress-bar "$url" -o "$tmp/wine.tar.xz" >&2 && tar -xf "$tmp/wine.tar.xz" -C "$root/.wine-app" >&2; then
  echo "[wine] Installed." >&2
else
  echo "[wine] Could not install Wine. The models, menu art and block textures need it (or a copy of the finished files from a PC, see the README)." >&2
fi
rm -rf "$tmp"
find_wine

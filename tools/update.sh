#!/bin/bash
# Run by play.command on every start. Keeps the game current with GitHub and fetches the converter tool the game needs.
# Never stops the launch: every failure just prints a note and play.command carries on.
root="$(cd "$(dirname "$0")/.." && pwd)"
repo="alexianjjohnston-ai/BO2MINECRAFTZM"

sha() { if command -v shasum >/dev/null 2>&1; then shasum -a 256 "$1" | cut -d' ' -f1; else sha256sum "$1" | cut -d' ' -f1; fi; }

sync_git() {
  if git -C "$root" pull --ff-only; then echo "[sync] Version $(git -C "$root" rev-parse --short HEAD)"
  else echo "[sync] Could not fast-forward (local changes in the way or no upstream). Continuing with the current version."; fi
}

# Downloaded the project as a zip (no .git folder): fetch the zip again and copy it over, leaving the player's saves and cache alone.
sync_zip() {
  local tmp zip src new
  tmp="$(mktemp -d "${TMPDIR:-/tmp}/zc-update.XXXXXX")" || return 1
  zip="$tmp/main.zip"
  if ! curl -fsSL "https://github.com/$repo/archive/refs/heads/main.zip" -o "$zip"; then
    echo "[sync] Skipped (download failed). If the repository is private a plain zip can't update itself: install Git, run  git clone https://github.com/$repo  (sign in once) and play from that folder."
    rm -rf "$tmp"; return 0
  fi
  unzip -q "$zip" -d "$tmp" || { rm -rf "$tmp"; return 0; }
  src="$(find "$tmp" -mindepth 1 -maxdepth 1 -type d | head -n 1)"
  new="$(sha "$zip")"
  if [ -f "$root/.zc-version" ] && [ "$(cat "$root/.zc-version")" = "$new" ]; then echo "[sync] Already up to date."; rm -rf "$tmp"; return 0; fi
  # play.command is running right now, so a new copy goes beside it as play.command.new instead of over it
  rsync -a --exclude=run --exclude=.gradle --exclude=build --exclude=.git --exclude=play.command "$src/" "$root/"
  if [ -f "$src/play.command" ] && ! cmp -s "$src/play.command" "$root/play.command"; then
    cp "$src/play.command" "$root/play.command.new"
    echo "[sync] A newer play.command was saved as play.command.new and replaces this one on the next start."
  fi
  echo "$new" > "$root/.zc-version"
  echo "[sync] Updated from GitHub."
  rm -rf "$tmp"
}

if [ -d "$root/.git" ] && command -v git >/dev/null 2>&1; then sync_git; else sync_zip; fi
chmod +x "$root"/play.command "$root"/tools/*.sh "$root"/mod/gradlew 2>/dev/null

# OpenAssetTools Unlinker (separate GPL-3.0 program from its official release). The game uses it to turn the models and menu art in YOUR
# Black Ops II install into Minecraft-readable files on your computer. Without it the game falls back to plain shapes.
# Only Windows and Linux builds exist. On a Mac the Windows build is fetched: the game runs it through Wine when Wine is installed.
oat="$root/mod/run/zombiecraft/tools/oat"
if [ -n "$ZOMBIECRAFT_OAT_DIR" ] && { [ -f "$ZOMBIECRAFT_OAT_DIR/Unlinker" ] || [ -f "$ZOMBIECRAFT_OAT_DIR/Unlinker.exe" ]; }; then
  echo "[oat] Using $ZOMBIECRAFT_OAT_DIR"
elif [ -f "$oat/Unlinker" ] || [ -f "$oat/Unlinker.exe" ]; then
  echo "[oat] OpenAssetTools ready."
else
  tmp="$(mktemp -d "${TMPDIR:-/tmp}/zc-oat.XXXXXX")"
  mkdir -p "$oat"
  base="https://github.com/Laupetin/OpenAssetTools/releases/download/v0.33.0"
  echo "[oat] Downloading OpenAssetTools v0.33.0 (about 8 MB)..."
  if [ "$(uname -s)" = "Linux" ]; then
    curl -fsSL "$base/oat-linux.tar.gz" -o "$tmp/oat.tgz" && tar -xzf "$tmp/oat.tgz" -C "$oat" && chmod +x "$oat/Unlinker" && echo "[oat] Installed."
  else
    curl -fsSL "$base/oat-windows.zip" -o "$tmp/oat.zip" && unzip -qo "$tmp/oat.zip" -d "$oat" && echo "[oat] Installed (Windows build; needs Wine on a Mac)."
  fi || echo "[oat] Could not get OpenAssetTools. Models and menu art will use plain shapes."
  rm -rf "$tmp"
fi
exit 0

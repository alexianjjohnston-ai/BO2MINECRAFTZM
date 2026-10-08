#!/bin/bash
# Block Ops 2 launcher for macOS (also works on Linux). Double-click it in Finder, or run ./play.command in a terminal.
# Safe to download on its own: put this one file anywhere and run it. Every start it
#   1. updates the game from GitHub (git pull or plain download, no git needed),
#   2. installs what is missing (JDK 25 for the build tool, OpenAssetTools for models/menu art),
#   3. looks for your Black Ops II files, then starts the game.
# First run downloads Minecraft and the build tools (a few hundred MB) and takes several minutes.
# Everything lives in a function so the whole file is read before it runs: the updater may replace this file while it is running.

main() {
  cd "$(dirname "$0")" || return 1

  # A newer launcher saved by the updater replaces this file, then restarts
  if [ -f play.command.new ]; then mv -f play.command.new play.command && chmod +x play.command && exec ./play.command "$@"; fi

  # First run from a lone play.command: fetch the project into this folder
  if [ ! -f tools/update.sh ]; then
    echo "[setup] Downloading the game files..."
    local tmp; tmp="$(mktemp -d "${TMPDIR:-/tmp}/zc-setup.XXXXXX")"
    if curl -fsSL "https://github.com/alexianjjohnston-ai/BO2MINECRAFTZM/archive/refs/heads/main.zip" -o "$tmp/m.zip" && unzip -q "$tmp/m.zip" -d "$tmp"; then
      rsync -a --exclude=play.command "$(find "$tmp" -mindepth 1 -maxdepth 1 -type d | head -n 1)/" ./
    fi
    rm -rf "$tmp"
  fi
  if [ ! -f tools/update.sh ]; then echo "Could not download the game. Check your internet connection and try again."; return 1; fi
  chmod +x tools/*.sh mod/gradlew 2>/dev/null

  echo "[sync] Checking GitHub for updates..."
  bash tools/update.sh
  if [ -f play.command.new ]; then mv -f play.command.new play.command && chmod +x play.command && exec ./play.command "$@"; fi

  # JDK 25 (only runs the build tool; the game itself targets Java 21)
  JAVA_HOME="$(bash tools/jdk.sh)"
  if [ -z "$JAVA_HOME" ]; then echo "Java 25 could not be found or installed. Install a JDK 25 (for example from https://adoptium.net) or set JAVA_HOME, then run this again."; return 1; fi
  export JAVA_HOME
  echo "Using $JAVA_HOME"

  # Black Ops II files (read-only). The game does its own search too; this reports and passes a hint.
  if [ -z "$ZOMBIECRAFT_BO2_DIR" ]; then
    ZOMBIECRAFT_BO2_DIR="$(bash tools/find_bo2.sh)"
    export ZOMBIECRAFT_BO2_DIR
  fi
  if [ -n "$ZOMBIECRAFT_BO2_DIR" ]; then echo "[bo2] Found Black Ops II: $ZOMBIECRAFT_BO2_DIR"
  else echo "[bo2] Not set; the game will use Minecraft sounds. Set ZOMBIECRAFT_BO2_DIR to force a path."; fi

  cd mod && sh ./gradlew runClient --console=plain
}

main "$@"
status=$?
echo
echo "The game has stopped. If it closed with an error, copy the text above and send it to whoever made this."
# keep a double-clicked window open (always after an error), so the text can be read and copied
if [ -t 0 ] && { [ "$status" -ne 0 ] || [ "$TERM_PROGRAM" = "Apple_Terminal" ]; }; then read -n 1 -s -r -p "Press any key to close..."; echo; fi
exit $status

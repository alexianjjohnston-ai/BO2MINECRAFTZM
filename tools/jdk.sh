#!/bin/bash
# Prints the folder of a JDK 25 (it only runs the build tool; the game itself targets Java 21). When none is found, Temurin 25 is
# installed into ./.jdk (no admin rights). Messages go to stderr so stdout is just the path. Exit 1 if there is no JDK 25 at the end.
root="$(cd "$(dirname "$0")/.." && pwd)"

is25() { [ -x "$1/bin/java" ] && "$1/bin/java" -version 2>&1 | head -1 | grep -q '"25[."]'; }

find_jdk() {
  local c
  for c in "$JAVA_HOME" "$root"/.jdk/jdk-25*/Contents/Home "$root"/.jdk/jdk-25* "$(/usr/libexec/java_home -v 25 2>/dev/null)" \
           /Library/Java/JavaVirtualMachines/*/Contents/Home /opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home \
           /usr/local/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home /usr/lib/jvm/*; do
    if is25 "$c"; then echo "$c"; return 0; fi
  done
  return 1
}

find_jdk && exit 0

case "$(uname -s)" in Darwin) os=mac ;; Linux) os=linux ;; *) echo "[java] Unsupported system $(uname -s)." >&2; exit 1 ;; esac
case "$(uname -m)" in arm64|aarch64) arch=aarch64 ;; *) arch=x64 ;; esac
echo "[java] Downloading JDK 25 for $os/$arch (about 200 MB)..." >&2
mkdir -p "$root/.jdk" || exit 1
tmp="$(mktemp -d "${TMPDIR:-/tmp}/zc-jdk.XXXXXX")" || exit 1
if curl -fL --progress-bar "https://api.adoptium.net/v3/binary/latest/25/ga/$os/$arch/jdk/hotspot/normal/eclipse" -o "$tmp/jdk.tgz" >&2 \
   && tar -xzf "$tmp/jdk.tgz" -C "$root/.jdk" >&2; then
  echo "[java] Installed." >&2
else
  echo "[java] Could not install JDK 25. Install one yourself (for example from https://adoptium.net) or set JAVA_HOME." >&2
fi
rm -rf "$tmp"
find_jdk

#!/usr/bin/env bash
#
# Capture edits made in the native/dosbox-x submodule working tree as a patch
# file in native/patches/, so they re-apply on top of upstream on the next build.
#
# Typical loop:
#   1. edit files under native/dosbox-x/ until it builds for Android
#   2. ./native/regen-patch.sh 0002 fix-bionic-foo
#        -> writes native/patches/0002-fix-bionic-foo.patch
#   3. re-run ./native/build-android.sh to confirm it applies cleanly
#
# With no args it dumps the full working-tree diff to stdout so you can inspect
# it before deciding how to split it into numbered patches.
#
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
SRC="$HERE/dosbox-x"

if [ $# -eq 0 ]; then
  git -C "$SRC" diff
  exit 0
fi

NUM="$1"; shift
NAME="${*:-changes}"; NAME="${NAME// /-}"
OUT="$HERE/patches/${NUM}-${NAME}.patch"
mkdir -p "$HERE/patches"
git -C "$SRC" diff > "$OUT"
[ -s "$OUT" ] || { echo "no changes in the submodule working tree — nothing written"; rm -f "$OUT"; exit 1; }
echo "wrote $OUT ($(grep -c '^+++' "$OUT") file(s))"
echo "verify it re-applies cleanly:  DBX_NO_UPDATE=1 ./native/build-android.sh"

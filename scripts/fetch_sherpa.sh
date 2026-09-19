#!/usr/bin/env bash
# Download the prebuilt sherpa-onnx Android native libraries into
# app/src/main/jniLibs/ (gitignored). Used by local builds and by CI.
#
# The version and archive checksum are pinned together. The Kotlin JNI
# bindings vendored under app/src/main/kotlin/com/k2fsa/sherpa/onnx/ must come
# from the SAME release: a mismatch builds fine and crashes on the phone.
# Bump all three together.
#
# Only libsherpa-onnx-jni.so and libonnxruntime.so are copied; the c-api and
# cxx-api libraries are for non-JNI users (see the bundle's README.md).
#
# Usage: scripts/fetch_sherpa.sh   (idempotent; re-run after a version bump)
set -euo pipefail

SHERPA_VERSION="1.12.28"
SHERPA_SHA256="8a7342a6b2081baf93d85e1e4e5578e9d269e6bbeae2d749d978a1f15ae6278c"
ABI="arm64-v8a"
LIBS=(libsherpa-onnx-jni.so libonnxruntime.so)

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DEST="$ROOT/app/src/main/jniLibs/$ABI"
STAMP="$DEST/.sherpa-version"

if [[ -f "$STAMP" && "$(cat "$STAMP")" == "$SHERPA_VERSION" ]]; then
  echo "fetch_sherpa.sh: sherpa-onnx $SHERPA_VERSION already present"
  exit 0
fi

ARCHIVE="sherpa-onnx-v${SHERPA_VERSION}-android.tar.bz2"
URL="https://github.com/k2-fsa/sherpa-onnx/releases/download/v${SHERPA_VERSION}/${ARCHIVE}"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

echo "fetch_sherpa.sh: downloading $URL"
curl -fsSL "$URL" -o "$WORK/$ARCHIVE"

ACTUAL="$(sha256sum "$WORK/$ARCHIVE" | cut -d' ' -f1)"
if [[ "$ACTUAL" != "$SHERPA_SHA256" ]]; then
  echo "fetch_sherpa.sh: checksum mismatch for $ARCHIVE" >&2
  echo "  expected $SHERPA_SHA256" >&2
  echo "  actual   $ACTUAL" >&2
  exit 1
fi

tar xjf "$WORK/$ARCHIVE" -C "$WORK" "./jniLibs/$ABI"
mkdir -p "$DEST"
rm -f "$DEST"/*.so
for lib in "${LIBS[@]}"; do
  cp "$WORK/jniLibs/$ABI/$lib" "$DEST/$lib"
done
echo "$SHERPA_VERSION" > "$STAMP"

# Report ELF LOAD segment alignment. Android 15+ devices with 16 KB pages
# refuse to load libraries aligned below 16384. Report only for now:
# 4 KB-page phones are unaffected, and Play is not a target yet.
# On Windows, python3 is often the Microsoft Store stub, so probe that it runs.
PYTHON=""
for candidate in python3 python; do
  if "$candidate" -c "pass" >/dev/null 2>&1; then PYTHON="$candidate"; break; fi
done
: "${PYTHON:?fetch_sherpa.sh: no working python found}"
LIB_PATHS=()
for lib in "${LIBS[@]}"; do
  path="$DEST/$lib"
  # Windows-native python cannot open Git Bash /d/... paths.
  if command -v cygpath >/dev/null; then path="$(cygpath -w "$path")"; fi
  LIB_PATHS+=("$path")
done
SCRIPT="$ROOT/scripts/check_elf_align.py"
if command -v cygpath >/dev/null; then SCRIPT="$(cygpath -w "$SCRIPT")"; fi
"$PYTHON" "$SCRIPT" "${LIB_PATHS[@]}"

echo "fetch_sherpa.sh: installed sherpa-onnx $SHERPA_VERSION into $DEST"

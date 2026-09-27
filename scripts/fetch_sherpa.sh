#!/usr/bin/env bash
# Download what the wake word needs and is too big to commit:
#
#   - the sherpa-onnx Android JNI library (arm64-v8a only) into
#     app/src/main/jniLibs/arm64-v8a/, and
#   - the keyword-spotting model into app/src/main/assets/wake/model/.
#
# Both destinations are gitignored. Local builds and CI run this before Gradle;
# the build fails with a pointer here when the files are missing.
#
# Everything is pinned. SHERPA_VERSION must match the Kotlin bindings vendored
# under app/src/main/kotlin/com/k2fsa/sherpa/onnx/: a mismatch builds fine and
# crashes on the phone. Bump the version, its checksum, and the bindings
# together.
#
# The "static-link-onnxruntime" bundle is used because it is one library
# (onnxruntime linked in) and smaller than libsherpa-onnx-jni.so plus
# libonnxruntime.so from the regular bundle.
#
# Usage: scripts/fetch_sherpa.sh   (idempotent; re-run after a version bump)
set -euo pipefail

SHERPA_VERSION="1.13.8"
SHERPA_ARCHIVE="sherpa-onnx-v${SHERPA_VERSION}-android-static-link-onnxruntime.tar.bz2"
SHERPA_SHA256="7583ca385ae7d981e65468455c2ea2c9f2da383921dccfc5658d0dc19d309e6f"
ABI="arm64-v8a"
LIB="libsherpa-onnx-jni.so"

# Phone-based (CMU ARPAbet) English and Chinese keyword spotter, about 5 MB used.
# Phones let the keywords list accent variants directly (CLAUDE, CLOUD, CLOD).
MODEL_NAME="sherpa-onnx-kws-zipformer-zh-en-3M-2025-12-20"
MODEL_SHA256="68447f4fbc67e70eee3a93961f36e81e98f47aef73ce7e7ca00885c6cd3616a6"
# Archive member -> name under assets/wake/model/.
MODEL_FILES=(
  "encoder-epoch-13-avg-2-chunk-16-left-64.int8.onnx:encoder.onnx"
  "decoder-epoch-13-avg-2-chunk-16-left-64.onnx:decoder.onnx"
  "joiner-epoch-13-avg-2-chunk-16-left-64.int8.onnx:joiner.onnx"
  "tokens.txt:tokens.txt"
)

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LIB_DEST="$ROOT/app/src/main/jniLibs/$ABI"
MODEL_DEST="$ROOT/app/src/main/assets/wake/model"
STAMP_TEXT="sherpa-onnx $SHERPA_VERSION + $MODEL_NAME"
STAMP="$MODEL_DEST/.fetched"

if [[ -f "$STAMP" && "$(cat "$STAMP")" == "$STAMP_TEXT" && -f "$LIB_DEST/$LIB" ]]; then
  echo "fetch_sherpa.sh: $STAMP_TEXT already present"
  exit 0
fi

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

fetch() { # url file sha256
  echo "fetch_sherpa.sh: downloading $1"
  curl -fsSL "$1" -o "$WORK/$2"
  local actual
  actual="$(sha256sum "$WORK/$2" | cut -d' ' -f1)"
  if [[ "$actual" != "$3" ]]; then
    echo "fetch_sherpa.sh: checksum mismatch for $2" >&2
    echo "  expected $3" >&2
    echo "  actual   $actual" >&2
    exit 1
  fi
}

fetch "https://github.com/k2-fsa/sherpa-onnx/releases/download/v${SHERPA_VERSION}/${SHERPA_ARCHIVE}" \
  "$SHERPA_ARCHIVE" "$SHERPA_SHA256"
fetch "https://github.com/k2-fsa/sherpa-onnx/releases/download/kws-models/${MODEL_NAME}.tar.bz2" \
  "${MODEL_NAME}.tar.bz2" "$MODEL_SHA256"

tar xjf "$WORK/$SHERPA_ARCHIVE" -C "$WORK" "./jniLibs/$ABI/$LIB"
mkdir -p "$LIB_DEST"
rm -f "$LIB_DEST"/*.so
cp "$WORK/jniLibs/$ABI/$LIB" "$LIB_DEST/$LIB"

members=()
for pair in "${MODEL_FILES[@]}"; do members+=("$MODEL_NAME/${pair%%:*}"); done
tar xjf "$WORK/${MODEL_NAME}.tar.bz2" -C "$WORK" "${members[@]}"
rm -rf "$MODEL_DEST"
mkdir -p "$MODEL_DEST"
for pair in "${MODEL_FILES[@]}"; do
  cp "$WORK/$MODEL_NAME/${pair%%:*}" "$MODEL_DEST/${pair##*:}"
done

# Android 15+ devices with 16 KB memory pages refuse to load native libraries
# whose LOAD segments are aligned below 16384, so fail on that here.
# On Windows, python3 is often the Microsoft Store stub, so probe that it runs.
PYTHON=""
for candidate in python3 python; do
  if "$candidate" -c "pass" >/dev/null 2>&1; then PYTHON="$candidate"; break; fi
done
: "${PYTHON:?fetch_sherpa.sh: no working python found}"
LIB_PATH="$LIB_DEST/$LIB"
SCRIPT="$ROOT/scripts/check_elf_align.py"
# Windows-native python cannot open Git Bash /d/... paths.
if command -v cygpath >/dev/null; then
  LIB_PATH="$(cygpath -w "$LIB_PATH")"
  SCRIPT="$(cygpath -w "$SCRIPT")"
fi
"$PYTHON" "$SCRIPT" --strict "$LIB_PATH"

echo "$STAMP_TEXT" > "$STAMP"
echo "fetch_sherpa.sh: installed $STAMP_TEXT"

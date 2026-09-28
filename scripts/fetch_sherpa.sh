#!/usr/bin/env bash
# Put in place what the wake word needs and is too big to commit:
#
#   - the keyword-spotting model, downloaded from the sherpa-onnx releases into
#     app/src/main/assets/wake/model/, and
#   - the sherpa-onnx JNI library (arm64-v8a only), built by CI from source
#     without text-to-speech (scripts/build_sherpa.sh), into
#     app/src/main/jniLibs/arm64-v8a/.
#
# Both destinations are gitignored. Local builds and CI run this before Gradle;
# the build fails with a pointer here when the files are missing.
#
# The library: CI passes the one it just built in SHERPA_LIB. Otherwise this
# downloads the newest unexpired CI build that matches this checkout's
# scripts/build_sherpa.sh, with the GitHub CLI (gh, logged in). CI keeps that
# artifact for 3 days; when there is none, start a build with
#   gh workflow run sherpa-build.yml --ref <your branch>
# and run this again once it finishes. Either way the library is refused if it
# contains espeak-ng (upstream's prebuilt one does) or is not 16 KB aligned.
#
# The model is pinned and checksummed here. The sherpa-onnx version is read
# from scripts/build_sherpa.sh and must match the Kotlin bindings vendored
# under app/src/main/kotlin/com/k2fsa/sherpa/onnx/.
#
# Usage: scripts/fetch_sherpa.sh   (idempotent; re-run after a version bump)
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SHERPA_VERSION="$(sed -n 's/^SHERPA_VERSION="\(.*\)"$/\1/p' "$ROOT/scripts/build_sherpa.sh")"
: "${SHERPA_VERSION:?fetch_sherpa.sh: no SHERPA_VERSION in scripts/build_sherpa.sh}"
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

LIB_DEST="$ROOT/app/src/main/jniLibs/$ABI"
MODEL_DEST="$ROOT/app/src/main/assets/wake/model"
MODEL_STAMP="$MODEL_DEST/.fetched"
MODEL_STAMP_TEXT="$MODEL_NAME"
# Which library build is installed. Kept next to the model stamp: hidden files
# in assets are not packaged, while anything in jniLibs might be.
LIB_STAMP="$MODEL_DEST/.library"

# The artifact name sherpa-build.yml gives the library built from this
# checkout's scripts/build_sherpa.sh.
LIB_ARTIFACT=""
if blob="$(git -C "$ROOT" rev-parse HEAD:scripts/build_sherpa.sh 2>/dev/null)"; then
  LIB_ARTIFACT="sherpa-onnx-v${SHERPA_VERSION}-kws-${ABI}-${blob:0:12}"
fi

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

# --- Model -------------------------------------------------------------------

if [[ -f "$MODEL_STAMP" && "$(cat "$MODEL_STAMP")" == "$MODEL_STAMP_TEXT" ]]; then
  echo "fetch_sherpa.sh: model $MODEL_NAME already present"
else
  url="https://github.com/k2-fsa/sherpa-onnx/releases/download/kws-models/${MODEL_NAME}.tar.bz2"
  echo "fetch_sherpa.sh: downloading $url"
  curl -fsSL "$url" -o "$WORK/model.tar.bz2"
  actual="$(sha256sum "$WORK/model.tar.bz2" | cut -d' ' -f1)"
  if [[ "$actual" != "$MODEL_SHA256" ]]; then
    echo "fetch_sherpa.sh: checksum mismatch for ${MODEL_NAME}.tar.bz2" >&2
    echo "  expected $MODEL_SHA256" >&2
    echo "  actual   $actual" >&2
    exit 1
  fi
  members=()
  for pair in "${MODEL_FILES[@]}"; do members+=("$MODEL_NAME/${pair%%:*}"); done
  tar xjf "$WORK/model.tar.bz2" -C "$WORK" "${members[@]}"
  rm -rf "$MODEL_DEST"
  mkdir -p "$MODEL_DEST"
  for pair in "${MODEL_FILES[@]}"; do
    cp "$WORK/$MODEL_NAME/${pair%%:*}" "$MODEL_DEST/${pair##*:}"
  done
  echo "$MODEL_STAMP_TEXT" > "$MODEL_STAMP"
  echo "fetch_sherpa.sh: installed model $MODEL_NAME"
fi

# --- Library -----------------------------------------------------------------

download_library() { # -> path of the downloaded library
  if [[ -z "$LIB_ARTIFACT" ]]; then
    echo "fetch_sherpa.sh: not a git checkout, so the CI build to download is unknown" >&2
    exit 1
  fi
  if ! command -v gh >/dev/null; then
    echo "fetch_sherpa.sh: the GitHub CLI (gh) is needed to download $LIB_ARTIFACT" >&2
    exit 1
  fi
  local repo run_id
  repo="$(gh repo view --json nameWithOwner --jq .nameWithOwner)"
  # Only runs of this repository's own branches: a fork PR runs its own copy of
  # sherpa-build.yml and could upload anything under the same artifact name.
  run_id="$(gh api "repos/$repo/actions/artifacts?name=$LIB_ARTIFACT&per_page=20" \
    --jq '[.artifacts[] | select(.expired == false
             and .workflow_run.head_repository_id == .workflow_run.repository_id)][0].workflow_run.id // empty')"
  if [[ -z "$run_id" ]]; then
    echo "fetch_sherpa.sh: no unexpired CI build named $LIB_ARTIFACT." >&2
    echo "  Start one with: gh workflow run sherpa-build.yml --ref <your branch>" >&2
    echo "  then run this script again once it has finished." >&2
    exit 1
  fi
  echo "fetch_sherpa.sh: downloading $LIB_ARTIFACT from workflow run $run_id" >&2
  gh run download "$run_id" --repo "$repo" --name "$LIB_ARTIFACT" --dir "$WORK/lib" >&2
  echo "$WORK/lib/$LIB"
}

if [[ -n "${SHERPA_LIB:-}" ]]; then
  source_lib="$SHERPA_LIB"
  lib_stamp_text="${LIB_ARTIFACT:-provided}"
elif [[ -f "$LIB_DEST/$LIB" && -f "$LIB_STAMP" && -n "$LIB_ARTIFACT" \
        && "$(cat "$LIB_STAMP")" == "$LIB_ARTIFACT" ]]; then
  echo "fetch_sherpa.sh: library $LIB_ARTIFACT already present"
  exit 0
else
  source_lib="$(download_library)"
  lib_stamp_text="$LIB_ARTIFACT"
fi

if [[ ! -s "$source_lib" ]]; then
  echo "fetch_sherpa.sh: $source_lib is missing or empty" >&2
  exit 1
fi

# Case-sensitive, and "wespeaker" (speaker embeddings, kept) is not espeak-ng.
if grep -a -q -E '(^|[^w])espeak' "$source_lib"; then
  echo "fetch_sherpa.sh: $source_lib contains espeak-ng (GPL); use the CI build from scripts/build_sherpa.sh" >&2
  exit 1
fi

# Android 15+ devices with 16 KB memory pages refuse to load native libraries
# whose LOAD segments are aligned below 16384, so fail on that here.
# On Windows, python3 is often the Microsoft Store stub, so probe that it runs.
PYTHON=""
for candidate in python3 python; do
  if "$candidate" -c "pass" >/dev/null 2>&1; then PYTHON="$candidate"; break; fi
done
: "${PYTHON:?fetch_sherpa.sh: no working python found}"
check_path="$source_lib"
SCRIPT="$ROOT/scripts/check_elf_align.py"
# Windows-native python cannot open Git Bash /d/... paths.
if command -v cygpath >/dev/null; then
  check_path="$(cygpath -w "$check_path")"
  SCRIPT="$(cygpath -w "$SCRIPT")"
fi
"$PYTHON" "$SCRIPT" --strict "$check_path"

mkdir -p "$LIB_DEST"
rm -f "$LIB_DEST"/*.so
cp "$source_lib" "$LIB_DEST/$LIB"
echo "$lib_stamp_text" > "$LIB_STAMP"
echo "fetch_sherpa.sh: installed library $lib_stamp_text ($(wc -c < "$LIB_DEST/$LIB") bytes)"

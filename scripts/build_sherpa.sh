#!/usr/bin/env bash
# Build the wake word's native library, libsherpa-onnx-jni.so for arm64-v8a,
# from the sherpa-onnx source with only what keyword spotting needs.
#
# Upstream's prebuilt Android library is built with text-to-speech and speaker
# diarization on, which statically links espeak-ng (GPL-3.0-or-later),
# piper-phonemize and hclust-cpp. Voice Relay only calls keyword spotting, so
# this build turns those parts off: the library it produces contains no GPL
# code and is smaller.
#
# It runs upstream's own build-android-arm64-v8a.sh at the pinned tag, with
# its options overridden through the environment variables that script reads.
# ONNX Runtime stays the prebuilt static library upstream uses, downloaded and
# checksummed here, and is linked into the one .so.
#
# CI runs this (.github/workflows/sherpa-build.yml) and caches the result on
# this file's git blob hash, so any change here (version, options, NDK) builds
# a fresh library. It needs Linux or macOS with cmake, make, git, curl, unzip
# and the Android NDK below; it is not meant for Windows.
#
# SHERPA_VERSION must match the Kotlin bindings vendored under
# app/src/main/kotlin/com/k2fsa/sherpa/onnx/: a mismatch builds fine and
# crashes on the phone. scripts/fetch_sherpa.sh reads the version from here.
#
# Usage: ANDROID_NDK=/path/to/ndk scripts/build_sherpa.sh OUT_DIR
set -euo pipefail

SHERPA_VERSION="1.13.8"
# The commit the tag pointed to when this was written; a moved tag fails here.
SHERPA_COMMIT="11afbd009a7f8c08f4bcf2fc1b265d0df4670fbf"

# The ONNX Runtime upstream's build script and CMake pin for this version
# (cmake/onnxruntime-android-aarch64-static.cmake), with the same checksum.
ORT_VERSION="1.28.2"
ORT_ZIP="onnxruntime-android-arm64-v8a-static_lib-${ORT_VERSION}.zip"
ORT_SHA256="bc68410f35f13df82c260830bf476ee5565f5efd0ec7622297829a929613c6c0"

# NDK r28c. The workflow installs this exact version.
NDK_VERSION="28.2.13676358"

# Everything keyword spotting does not use is off. BUILD_SHARED_LIBS=OFF links
# ONNX Runtime statically into libsherpa-onnx-jni.so, like upstream's
# "static-link-onnxruntime" bundle the app used before.
export BUILD_SHARED_LIBS=OFF
export SHERPA_ONNX_ENABLE_TTS=OFF
export SHERPA_ONNX_ENABLE_SPEAKER_DIARIZATION=OFF
export SHERPA_ONNX_ENABLE_BINARY=OFF
export SHERPA_ONNX_ENABLE_C_API=OFF
export SHERPA_ONNX_ENABLE_JNI=ON
export SHERPA_ONNX_ENABLE_RKNN=OFF
export SHERPA_ONNX_ENABLE_QNN=OFF

OUT="${1:?usage: scripts/build_sherpa.sh OUT_DIR}"
: "${ANDROID_NDK:?set ANDROID_NDK to the Android NDK $NDK_VERSION directory}"

ndk_revision="$(sed -n 's/^Pkg.Revision *= *//p' "$ANDROID_NDK/source.properties" | tr -d '\r')"
if [[ "$ndk_revision" != "$NDK_VERSION" ]]; then
  echo "build_sherpa.sh: expected NDK $NDK_VERSION, found ${ndk_revision:-none} at $ANDROID_NDK" >&2
  exit 1
fi

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

echo "build_sherpa.sh: cloning sherpa-onnx v$SHERPA_VERSION"
git clone -q --depth 1 --branch "v$SHERPA_VERSION" https://github.com/k2-fsa/sherpa-onnx.git "$WORK/src"
actual_commit="$(git -C "$WORK/src" rev-parse HEAD)"
if [[ "$actual_commit" != "$SHERPA_COMMIT" ]]; then
  echo "build_sherpa.sh: tag v$SHERPA_VERSION is $actual_commit, expected $SHERPA_COMMIT" >&2
  exit 1
fi

echo "build_sherpa.sh: downloading ONNX Runtime $ORT_VERSION"
curl -fsSL "https://github.com/csukuangfj/onnxruntime-libs/releases/download/v${ORT_VERSION}/${ORT_ZIP}" \
  -o "$WORK/$ORT_ZIP"
actual_sha="$(sha256sum "$WORK/$ORT_ZIP" | cut -d' ' -f1)"
if [[ "$actual_sha" != "$ORT_SHA256" ]]; then
  echo "build_sherpa.sh: checksum mismatch for $ORT_ZIP: $actual_sha" >&2
  exit 1
fi
unzip -q "$WORK/$ORT_ZIP" -d "$WORK/ort"
ORT_DIR="$WORK/ort/${ORT_ZIP%.zip}"
export SHERPA_ONNXRUNTIME_LIB_DIR="$ORT_DIR/lib"
export SHERPA_ONNXRUNTIME_INCLUDE_DIR="$ORT_DIR/include"
export SHERPA_ONNX_ONNXRUNTIME_VERSION="$ORT_VERSION"

(cd "$WORK/src" && ./build-android-arm64-v8a.sh)

BUILD="$WORK/src/build-android-arm64-v8a-static"
mkdir -p "$OUT"
rm -f "$OUT"/*
cp "$BUILD/install/lib/libsherpa-onnx-jni.so" "$OUT/"

# What went into the library, for THIRD_PARTY_NOTICES.md.
{
  echo "sherpa-onnx v$SHERPA_VERSION ($SHERPA_COMMIT)"
  echo "ONNX Runtime $ORT_VERSION ($ORT_ZIP, sha256 $ORT_SHA256)"
  echo "Android NDK $NDK_VERSION"
  echo
  echo "Options:"
  env | grep -E '^(BUILD_SHARED_LIBS|SHERPA_ONNX_ENABLE_[A-Z_]+)=' | sort
  echo
  echo "Downloaded CMake dependencies (_deps):"
  ls "$BUILD/_deps" | sed -n 's/-src$//p' | sort
  echo
  echo "Other shared libraries installed by the build (not shipped):"
  ls "$BUILD/install/lib" | grep -v '^libsherpa-onnx-jni.so$' || echo "(none)"
} > "$OUT/BUILD_INFO.txt"
cat "$OUT/BUILD_INFO.txt"
ls -l "$OUT"

#!/usr/bin/env bash
# Check a libsherpa-onnx-jni.so before the app ships it:
#
#   1. no text-to-speech or speaker-diarization code: no espeak-ng,
#      piper-phonemize, cppjieba or hclust-cpp (fastcluster) strings
#      (espeak-ng is GPL-3.0-or-later and must not be in the APK);
#   2. every native method the vendored Kotlin bindings declare
#      (app/src/main/kotlin/com/k2fsa/sherpa/onnx/) is exported, so the wake
#      word does not fail with UnsatisfiedLinkError on the phone;
#   3. LOAD segments are 16 KB aligned (scripts/check_elf_align.py).
#
# Needs llvm-nm from the Android NDK (ANDROID_NDK) or on PATH.
#
# Usage: scripts/verify_sherpa_lib.sh path/to/libsherpa-onnx-jni.so
set -euo pipefail

LIB="${1:?usage: scripts/verify_sherpa_lib.sh LIB.so}"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BINDINGS="$ROOT/app/src/main/kotlin/com/k2fsa/sherpa/onnx"

NM="$(command -v llvm-nm || true)"
if [[ -z "$NM" && -n "${ANDROID_NDK:-}" ]]; then
  NM="$(ls "$ANDROID_NDK"/toolchains/llvm/prebuilt/*/bin/llvm-nm 2>/dev/null | head -n1)"
fi
: "${NM:?verify_sherpa_lib.sh: llvm-nm not found; set ANDROID_NDK}"

status=0
echo "verify_sherpa_lib.sh: $LIB ($(wc -c < "$LIB") bytes)"

# Case-sensitive: "OfflineSpeaker" contains "eSpeak", and the speaker
# embedding code (kept) names the WeSpeaker project, so "wespeaker" is allowed.
check_absent() { # label regex
  local hits
  hits="$(grep -a -o -E -- "$2" "$LIB" | tr -c '[:print:]\n' . | sort | uniq -c || true)"
  if [[ -n "$hits" ]]; then
    echo "  FAIL: $1 found:"
    sed 's/^/      /' <<< "$hits"
    status=1
  else
    echo "  ok: no $1"
  fi
}
check_absent "espeak-ng" '.{0,24}(^|[^w])espeak.{0,24}'
check_absent "piper-phonemize" '.{0,24}piper.phonemize.{0,24}'
check_absent "cppjieba" '.{0,24}cppjieba.{0,24}'
check_absent "hclust-cpp (fastcluster)" '.{0,24}fastcluster.{0,24}'

exported="$("$NM" -D --defined-only "$LIB" | awk '{print $NF}')"
missing=0
checked=0
for kt in "$BINDINGS"/*.kt; do
  cls="$(basename "$kt" .kt)"
  # Instance natives only; the one companion native (OnlineRecognizer's QNN
  # helper) is not used by the app.
  while read -r name; do
    sym="Java_com_k2fsa_sherpa_onnx_${cls}_${name}"
    checked=$((checked + 1))
    if ! grep -qx -- "$sym" <<< "$exported"; then
      echo "  FAIL: missing JNI symbol $sym"
      missing=1
    fi
  done < <(sed -n 's/^ *private external fun \([A-Za-z0-9_]*\).*/\1/p' "$kt")
done
if [[ "$missing" == 0 ]]; then
  echo "  ok: all $checked JNI methods the Kotlin bindings declare are exported"
else
  status=1
fi

python3 "$ROOT/scripts/check_elf_align.py" --strict "$LIB" || status=1

exit "$status"

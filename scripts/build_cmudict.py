"""Build the wake word's pronunciation dictionary from CMUdict.

The app turns a typed wake phrase into the keyword spotter's phones (CMU
ARPAbet with stress) by looking each word up in the CMU Pronouncing
Dictionary. This script downloads CMUdict at a pinned commit, checks its
checksum, and writes app/src/main/assets/wake/cmudict.txt.gzip, which is
committed. It also refreshes licenses/cmudict-LICENSE.txt from the same commit.

What changes from upstream's cmudict.dict:
  - the "(2)", "(3)" markers on alternate pronunciations are dropped: every
    pronunciation of a word is its own "word PHONES" line, in upstream order,
  - trailing "# ..." comments are dropped,
  - entries with a phone outside the 69 ARPAbet symbols are skipped (none are
    today),
  - a ";;;" header line records the source.

The text is pinned: its SHA-256 (TEXT_SHA256) is checked before anything is
written, so a rebuild either reproduces the committed dictionary word for word
or fails. The gzip bytes around it are not pinned. They are written with a
fixed timestamp and no file name, but the compressed stream depends on the
zlib that Python links (some builds use zlib-ng), so a rebuild elsewhere can
give a file with a different checksum and the same content. To check the
committed file, compare the SHA-256 of its decompressed text with TEXT_SHA256.

About 3.5 MB of text becomes about 0.9 MB. The extension is
.gzip, not .gz, on purpose: the Android build un-gzips .gz assets while
packaging, and the app would then not find the file it opens.

Usage: python scripts/build_cmudict.py   (stdlib only; needs network access)
"""

import gzip
import hashlib
import io
import re
import sys
import urllib.request
from pathlib import Path

COMMIT = "74790861f652b15e4ac49015a90074ad62a27690"
DICT_SHA256 = "81917843c7f44ce2b094ac63873c2c7a4cf802040792c455ba3ca406891c3d22"
BASE = f"https://raw.githubusercontent.com/cmusphinx/cmudict/{COMMIT}"
# SHA-256 of the dictionary text this script writes, before gzip.
TEXT_SHA256 = "a0884381aa019aa3822222aa520e7407718d45f4d892894d3b0f6d7c0ad7c4e1"

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "app" / "src" / "main" / "assets" / "wake" / "cmudict.txt.gzip"
LICENSE_OUT = ROOT / "licenses" / "cmudict-LICENSE.txt"

VOWELS = "AA AE AH AO AW AY EH ER EY IH IY OW OY UH UW".split()
CONSONANTS = "B CH D DH F G HH JH K L M N NG P R S SH T TH V W Y Z ZH".split()
PHONES = {v + s for v in VOWELS for s in "012"} | set(CONSONANTS)


def fetch(name: str) -> bytes:
    with urllib.request.urlopen(f"{BASE}/{name}", timeout=60) as r:
        return r.read()


def main() -> int:
    raw = fetch("cmudict.dict")
    actual = hashlib.sha256(raw).hexdigest()
    if actual != DICT_SHA256:
        print(f"build_cmudict.py: checksum mismatch\n  expected {DICT_SHA256}\n  actual   {actual}", file=sys.stderr)
        return 1

    lines = [f";;; CMU Pronouncing Dictionary, github.com/cmusphinx/cmudict commit {COMMIT[:12]}. "
             "BSD-2-Clause: see licenses/cmudict-LICENSE.txt."]
    skipped = 0
    for line in raw.decode("utf-8").splitlines():
        line = line.split("#", 1)[0].strip()
        if not line:
            continue
        word, *phones = line.split()
        word = re.sub(r"\(\d+\)$", "", word)
        if not phones or any(p not in PHONES for p in phones):
            skipped += 1
            continue
        lines.append(f"{word} {' '.join(phones)}")

    text = ("\n".join(lines) + "\n").encode("utf-8")
    text_sha = hashlib.sha256(text).hexdigest()
    if text_sha != TEXT_SHA256:
        print(f"build_cmudict.py: the dictionary text changed\n  expected {TEXT_SHA256}\n  actual   {text_sha}\n"
              "  If that is intended, update TEXT_SHA256.", file=sys.stderr)
        return 1
    buf = io.BytesIO()
    with gzip.GzipFile(filename="", fileobj=buf, mode="wb", compresslevel=9, mtime=0) as f:
        f.write(text)
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_bytes(buf.getvalue())
    LICENSE_OUT.write_bytes(fetch("LICENSE"))
    print(f"build_cmudict.py: {len(lines) - 1} pronunciations ({skipped} skipped), "
          f"{len(text)} bytes -> {OUT.stat().st_size} bytes in {OUT.relative_to(ROOT).as_posix()}")
    return 0


if __name__ == "__main__":
    sys.exit(main())

"""Report the smallest ELF LOAD segment alignment of 64-bit shared libraries.

Android 15+ devices that use 16 KB memory pages refuse to load native
libraries whose LOAD segments are aligned below 16384 bytes, and Play requires
16 KB alignment for apps targeting Android 15+.

Usage: python check_elf_align.py [--strict] LIB.so [LIB.so ...]
Exit status is 1 with --strict when any library is below 16 KB.
"""

import struct
import sys
from pathlib import Path

PT_LOAD = 1
PAGE_16K = 16384


def min_load_align(path: Path) -> int:
    data = path.read_bytes()
    if data[:4] != b"\x7fELF" or data[4] != 2:
        raise ValueError(f"{path.name}: not a 64-bit ELF file")
    (phoff,) = struct.unpack_from("<Q", data, 0x20)
    phentsize, phnum = struct.unpack_from("<HH", data, 0x36)
    aligns = []
    for i in range(phnum):
        off = phoff + i * phentsize
        (p_type,) = struct.unpack_from("<I", data, off)
        if p_type == PT_LOAD:
            aligns.append(struct.unpack_from("<Q", data, off + 48)[0])
    return min(aligns)


def main(argv: list[str]) -> int:
    strict = "--strict" in argv
    paths = [Path(a) for a in argv if a != "--strict"]
    failed = False
    for path in paths:
        align = min_load_align(path)
        ok = align >= PAGE_16K
        failed |= not ok
        print(f"{path.name}: LOAD align {align} ({'ok' if ok else 'below 16 KB'})")
    return 1 if strict and failed else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))

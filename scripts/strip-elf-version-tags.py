#!/usr/bin/env python3
"""Remove stale DT_VERSYM / DT_VERNEED / DT_VERNEEDNUM after llvm-objcopy drops sections."""
from __future__ import annotations

import struct
import sys
from pathlib import Path

DT_NULL = 0
DT_VERSYM = 0x6FFFFFF0
DT_VERNEED = 0x6FFFFFFE
DT_VERNEEDNUM = 0x6FFFFFFF
STRIP_TAGS = frozenset({DT_VERSYM, DT_VERNEED, DT_VERNEEDNUM})

ELF_MAGIC = b"\x7fELF"
ELFCLASS64 = 2
ELFDATA2LSB = 1
PT_DYNAMIC = 2


def u16(data: bytes, off: int) -> int:
    return struct.unpack_from("<H", data, off)[0]


def u32(data: bytes, off: int) -> int:
    return struct.unpack_from("<I", data, off)[0]


def u64(data: bytes, off: int) -> int:
    return struct.unpack_from("<Q", data, off)[0]


def p64(data: bytearray, off: int, val: int) -> None:
    struct.pack_into("<Q", data, off, val)


def find_dynamic(data: bytes) -> tuple[int, int] | None:
    if data[:4] != ELF_MAGIC or data[4] != ELFCLASS64 or data[5] != ELFDATA2LSB:
        raise ValueError("expected little-endian ELF64")
    e_phoff = u64(data, 0x20)
    e_phentsize = u16(data, 0x36)
    e_phnum = u16(data, 0x38)
    for i in range(e_phnum):
        off = e_phoff + i * e_phentsize
        if u32(data, off) != PT_DYNAMIC:
            continue
        return u64(data, off + 8), u64(data, off + 32)
    return None


def strip_dynamic_tags(path: Path) -> bool:
    raw = bytearray(path.read_bytes())
    found = find_dynamic(raw)
    if not found:
        return False
    dyn_off, dyn_filesz = found
    if dyn_filesz % 16 != 0:
        raise ValueError(f"{path}: dynamic segment size not aligned")

    entries: list[tuple[int, int]] = []
    pos = dyn_off
    end = dyn_off + dyn_filesz
    while pos < end:
        tag, val = u64(raw, pos), u64(raw, pos + 8)
        pos += 16
        if tag == DT_NULL:
            entries.append((tag, val))
            break
        if tag not in STRIP_TAGS:
            entries.append((tag, val))
    else:
        raise ValueError(f"{path}: DT_NULL missing in .dynamic")

    max_entries = dyn_filesz // 16
    if len(entries) > max_entries:
        raise ValueError(f"{path}: too many dynamic entries after strip")

    while len(entries) < max_entries:
        entries.append((DT_NULL, 0))

    out_off = dyn_off
    for tag, val in entries:
        p64(raw, out_off, tag)
        p64(raw, out_off + 8, val)
        out_off += 16

    path.write_bytes(raw)
    return True


def main(argv: list[str]) -> int:
    if len(argv) < 2:
        print(f"usage: {argv[0]} <elf>...", file=sys.stderr)
        return 2
    rc = 0
    for arg in argv[1:]:
        path = Path(arg)
        try:
            if strip_dynamic_tags(path):
                print(f"stripped version dynamic tags: {path}")
        except Exception as exc:  # noqa: BLE001
            print(f"error: {path}: {exc}", file=sys.stderr)
            rc = 1
    return rc


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))

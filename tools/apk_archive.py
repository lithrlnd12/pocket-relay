"""Verify local ZIP entries if Windows aapt crashes during archive finalization; align APK entries."""
import argparse
import pathlib
import struct
import zipfile
import zlib


def write_aligned(path, entries):
    with zipfile.ZipFile(path, "w", compression=zipfile.ZIP_STORED) as out:
        for name, data in entries:
            info = zipfile.ZipInfo(name, (2026, 10, 7, 0, 0, 0))
            base = out.fp.tell() + 30 + len(name.encode("utf-8"))
            if base % 4:
                padding = (-(base + 4)) % 4
                info.extra = struct.pack("<HH", 0xCAFE, padding) + bytes(padding)
            out.writestr(info, data)
    with zipfile.ZipFile(path) as check:
        assert check.testzip() is None, "Archive CRC failure"
        for info in check.infolist():
            with open(path, "rb") as raw:
                raw.seek(info.header_offset)
                h = struct.unpack("<4s5H3I2H", raw.read(30))
                assert (info.header_offset + 30 + h[-2] + h[-1]) % 4 == 0, "Unaligned entry"


def recover(path):
    raw = pathlib.Path(path).read_bytes()
    entries = []
    offset = 0
    while offset < len(raw):
        if offset + 30 > len(raw):
            raise ValueError("Truncated resource archive")
        h = struct.unpack_from("<4s5H3I2H", raw, offset)
        magic, _, flags, method, _, _, crc, compressed, uncompressed, name_len, extra_len = h
        if magic != b"PK\x03\x04" or flags & (1 | 8) or method not in (0, 8):
            raise ValueError("Cannot safely recover this resource archive")
        name = raw[offset + 30:offset + 30 + name_len].decode("utf-8")
        start = offset + 30 + name_len + extra_len
        end = start + compressed
        if end > len(raw):
            raise ValueError("Truncated resource entry")
        data = zlib.decompress(raw[start:end], -15) if method == 8 else raw[start:end]
        if len(data) != uncompressed or zlib.crc32(data) != crc:
            raise ValueError("Resource length or CRC failure")
        entries.append((name, data))
        offset = end
    expected = {"AndroidManifest.xml", "resources.arsc", "res/drawable/ic_launcher.xml"}
    if {name for name, _ in entries} != expected or len(entries) != len(expected):
        raise ValueError("Incomplete or unexpected resource set; refusing recovery")
    temporary = str(path) + ".recovered"
    write_aligned(temporary, entries)
    pathlib.Path(temporary).replace(path)
    print("Verified all 3 compiled resource entries and reconstructed the APK archive.")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("mode", choices=["recover", "align"])
    parser.add_argument("source")
    parser.add_argument("destination", nargs="?")
    args = parser.parse_args()
    if args.mode == "recover":
        recover(args.source)
    else:
        with zipfile.ZipFile(args.source) as source:
            if source.testzip() is not None:
                raise ValueError("Source archive CRC failure")
            entries = [(info.filename, source.read(info.filename)) for info in source.infolist()]
        write_aligned(args.destination, entries)
        print("APK entries aligned and CRC-verified.")

#!/usr/bin/env python3
"""Assemblage d'APK sans Gradle.
  apkpack.py merge base.apk out.apk ROOT      ajoute a base.apk (sortie aapt2 link) les fichiers de ROOT (classes.dex, lib/...)
  apkpack.py align in.apk out.apk             equivalent zipalign : donnees stockees alignees sur 4 (4096 pour les .so)
"""
import os
import struct
import sys
import zipfile


def merge(base, out, root):
    with zipfile.ZipFile(base) as zi, zipfile.ZipFile(out, "w") as zo:
        for it in zi.infolist():
            zo.writestr(it, zi.read(it.filename))
        for d, _, files in os.walk(root):
            for f in sorted(files):
                p = os.path.join(d, f)
                name = os.path.relpath(p, root).replace(os.sep, "/")
                comp = zipfile.ZIP_STORED if name.endswith(".so") else zipfile.ZIP_DEFLATED
                zo.write(p, name, compress_type=comp)


def align(src, dst):
    with zipfile.ZipFile(src) as zi:
        items = [(it, zi.read(it.filename)) for it in zi.infolist()]
    with open(dst, "wb") as fo:
        central = []
        for it, data in items:
            name = it.filename.encode("utf-8")
            if it.compress_type == zipfile.ZIP_STORED:
                comp = data
            else:
                import zlib
                c = zlib.compressobj(9, zlib.DEFLATED, -15)
                comp = c.compress(data) + c.flush()
            off = fo.tell()
            extra = b""
            if it.compress_type == zipfile.ZIP_STORED:
                a = 4096 if it.filename.endswith(".so") else 4
                pad = (-(off + 30 + len(name))) % a
                extra = b"\x00" * pad
            crc = zipfile.crc32(data) & 0xFFFFFFFF
            dt = it.date_time
            dosdate = ((dt[0] - 1980) << 9) | (dt[1] << 5) | dt[2]
            dostime = (dt[3] << 11) | (dt[4] << 5) | (dt[5] // 2)
            hdr = struct.pack("<IHHHHHIIIHH", 0x04034B50, 20, 0x0800, it.compress_type, dostime, dosdate,
                              crc, len(comp), len(data), len(name), len(extra))
            fo.write(hdr + name + extra + comp)
            central.append(struct.pack("<IHHHHHHIIIHHHHHII", 0x02014B50, 20, 20, 0x0800, it.compress_type, dostime,
                                       dosdate, crc, len(comp), len(data), len(name), 0, 0, 0, 0,
                                       (it.external_attr or 0), off) + name)
        cd = fo.tell()
        for c in central:
            fo.write(c)
        size = fo.tell() - cd
        fo.write(struct.pack("<IHHHHIIH", 0x06054B50, 0, 0, len(central), len(central), size, cd, 0))


if __name__ == "__main__":
    if len(sys.argv) == 5 and sys.argv[1] == "merge":
        merge(sys.argv[2], sys.argv[3], sys.argv[4])
    elif len(sys.argv) == 4 and sys.argv[1] == "align":
        align(sys.argv[2], sys.argv[3])
    else:
        sys.exit(__doc__)

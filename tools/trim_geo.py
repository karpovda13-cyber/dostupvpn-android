#!/usr/bin/env python3
"""
Оставляет в geoip.dat / geosite.dat только российские списки: ~28 МБ -> меньше 1 МБ.
Файлы .dat — protobuf (GeoIPList / GeoSiteList); записи копируются как есть, без пересборки.

Использование (источник — assets из libv2ray.aar нужной версии):
    unzip -j libv2ray.aar 'assets/geoip.dat' 'assets/geosite.dat' -d /tmp/geo-src
    python3 tools/trim_geo.py /tmp/geo-src app/src/main/assets/geo

Если нужного списка нет в источнике — скрипт завершается с ошибкой (лучше упасть при
подготовке, чем получить приложение, которое не запускается из-за неизвестного geosite:код).
"""
import sys
from pathlib import Path

GEOIP_KEEP = {"RU", "PRIVATE"}
GEOSITE_KEEP = {
    "CATEGORY-RU", "TLD-RU", "CATEGORY-GOV-RU", "CATEGORY-BANK-RU", "CATEGORY-ECOMMERCE-RU",
    "CATEGORY-RETAIL-RU", "CATEGORY-MEDIA-RU", "CATEGORY-MEDICINE-RU", "CATEGORY-TRAVEL-RU",
    "CATEGORY-EDUCATION-RU", "CATEGORY-ENTERTAINMENT-RU", "YANDEX", "DZEN", "2GIS", "VK",
    "MAILRU", "OK", "SBER", "OZON", "AVITO", "WILDBERRIES", "MEGAFON", "MTS-RU", "T2-RU",
    "TBANK-RU", "KINOPOISK", "RUTUBE",
}


def varint(b: bytes, i: int):
    r = s = 0
    while True:
        c = b[i]
        i += 1
        r |= (c & 0x7F) << s
        s += 7
        if not c & 0x80:
            return r, i


def enc_varint(n: int) -> bytes:
    out = bytearray()
    while True:
        c = n & 0x7F
        n >>= 7
        out.append(c | (0x80 if n else 0))
        if not n:
            return bytes(out)


def fields(b: bytes):
    i = 0
    while i < len(b):
        k, i = varint(b, i)
        f, w = k >> 3, k & 7
        if w == 0:
            v, i = varint(b, i)
        elif w == 2:
            ln, i = varint(b, i)
            v = b[i:i + ln]
            i += ln
        elif w == 1:
            v = b[i:i + 8]
            i += 8
        elif w == 5:
            v = b[i:i + 4]
            i += 4
        else:
            raise ValueError(f"unsupported wire type {w}")
        yield f, w, v


def trim(src: Path, dst: Path, keep: set) -> None:
    kept, found = [], set()
    for f, _, raw in fields(src.read_bytes()):
        if f != 1:
            continue
        code = next(v.decode() for f2, _, v in fields(raw) if f2 == 1)
        if code in keep:
            kept.append(raw)
            found.add(code)
    missing = keep - found
    if missing:
        sys.exit(f"{src.name}: в источнике нет списков: {sorted(missing)}")
    dst.write_bytes(b"".join(b"\x0a" + enc_varint(len(r)) + r for r in kept))
    print(f"{dst.name}: {len(kept)} списков, {dst.stat().st_size / 1024:.0f} КБ")


if __name__ == "__main__":
    if len(sys.argv) != 3:
        sys.exit(__doc__)
    src_dir, dst_dir = Path(sys.argv[1]), Path(sys.argv[2])
    dst_dir.mkdir(parents=True, exist_ok=True)
    trim(src_dir / "geoip.dat", dst_dir / "geoip.dat", GEOIP_KEEP)
    trim(src_dir / "geosite.dat", dst_dir / "geosite.dat", GEOSITE_KEEP)

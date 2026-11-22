#!/usr/bin/env python3
"""PPM(P6) -> PNG 純 Python 轉檔（雲端沒有 Pillow 也能用）。
用法：python3 ppm2png.py in.ppm out.png
"""
import struct
import sys
import zlib


def main():
    src, dst = sys.argv[1], sys.argv[2]
    with open(src, "rb") as f:
        data = f.read()
    # 解析 header：P6\n<w> <h>\n255\n
    parts = data.split(b"\n", 3)
    assert parts[0].startswith(b"P6"), "not P6 ppm"
    w, h = map(int, parts[1].split())
    maxval = int(parts[2])
    assert maxval == 255
    pixels = parts[3]
    raw = b"".join(b"\x00" + pixels[y * w * 3:(y + 1) * w * 3] for y in range(h))

    def chunk(tag, payload):
        c = struct.pack(">I", len(payload)) + tag + payload
        return c + struct.pack(">I", zlib.crc32(tag + payload) & 0xFFFFFFFF)

    png = b"\x89PNG\r\n\x1a\n"
    png += chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 2, 0, 0, 0))
    png += chunk(b"IDAT", zlib.compress(raw, 6))
    png += chunk(b"IEND", b"")
    with open(dst, "wb") as f:
        f.write(png)
    print(f"{dst}: {w}x{h}")


if __name__ == "__main__":
    main()

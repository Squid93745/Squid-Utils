"""Draw the Squid Utils mod icon: a colossal squid, 128x128 PNG.

No image library available, so this writes the PNG bytes directly and draws by
evaluating shape coverage per pixel at 4x supersampling, then box-downsampling.
Slower than a real rasteriser and entirely fast enough for one 128px icon.

Colossal squids really are a deep reddish-magenta with famously enormous eyes,
so the palette leans that way rather than the cartoon-teal a generic squid gets.
"""

from __future__ import annotations

import math
import struct
import zlib
from pathlib import Path

SIZE = 128
SS = 4                      # supersample factor
W = SIZE * SS

# --- palette ---------------------------------------------------------------
BG_INNER = (18, 32, 52)
BG_OUTER = (7, 11, 20)
BODY_DARK = (112, 30, 58)
BODY_MID = (183, 55, 92)
BODY_LIGHT = (226, 118, 150)
FIN = (150, 42, 78)
EYE_WHITE = (245, 234, 214)
EYE_DARK = (16, 12, 20)
GLINT = (255, 255, 255)


def lerp(a, b, t):
    return tuple(int(round(a[i] + (b[i] - a[i]) * t)) for i in range(3))


def dist_to_segment(px, py, ax, ay, bx, by):
    vx, vy = bx - ax, by - ay
    wx, wy = px - ax, py - ay
    L2 = vx * vx + vy * vy
    t = 0.0 if L2 == 0 else max(0.0, min(1.0, (wx * vx + wy * vy) / L2))
    cx, cy = ax + t * vx, ay + t * vy
    return math.hypot(px - cx, py - cy), t


def arm_curve(angle, length, bend, n=14):
    """Points of one arm, splaying from the head and curling outward."""
    pts = []
    for i in range(n + 1):
        t = i / n
        r = length * t
        a = angle + bend * (t ** 1.6)
        pts.append((0.5 + math.sin(a) * r * 0.75, 0.63 + math.cos(a) * r))
    return pts


# Eight arms plus two long hunting tentacles, which is what makes a squid a
# squid rather than an octopus.
ARMS = []
for i, ang in enumerate((-0.95, -0.62, -0.30, -0.08, 0.08, 0.30, 0.62, 0.95)):
    ARMS.append((arm_curve(ang, 0.30 + 0.035 * (2 - abs(i - 3.5)), ang * 0.85),
                 0.030, False))
TENTACLES = [
    (arm_curve(-1.22, 0.40, -0.55), 0.020, True),
    (arm_curve(1.22, 0.40, 0.55), 0.020, True),
]


def mantle_halfwidth(t):
    """Half width of the mantle at normalised height t in [0, 1]."""
    if t < 0 or t > 1:
        return -1
    return 0.205 * (t ** 0.75) * (1.0 - 0.20 * t)


def sample(x, y):
    """Colour at normalised (x, y), or None for background."""
    dx = x - 0.5

    # --- arms and tentacles (behind the body) ---
    for pts, width, is_tentacle in TENTACLES + ARMS:
        for i in range(len(pts) - 1):
            ax, ay = pts[i]
            bx, by = pts[i + 1]
            d, t = dist_to_segment(x, y, ax, ay, bx, by)
            frac = (i + t) / (len(pts) - 1)
            w = width * (1.0 - 0.72 * frac)
            if is_tentacle and frac > 0.78:      # the club at the tip
                w = width * 1.55 * (1.0 - (frac - 0.78) * 2.2)
            if d < w:
                shade = 0.35 + 0.5 * (1.0 - frac)
                edge = d / max(w, 1e-6)
                return lerp(BODY_MID, BODY_DARK, min(1.0, edge * 0.9 + (1 - shade) * 0.4))

    # --- fins ---
    # Start below the mantle tip so the silhouette comes to a point rather than
    # being clipped flat across the top.
    ft = (y - 0.145) / 0.20
    if 0.0 <= ft <= 1.0:
        span = 0.245 * math.sin(math.pi * ft) ** 0.85
        if abs(dx) < span:
            inner = mantle_halfwidth((y - 0.07) / 0.46)
            if abs(dx) > inner:
                return lerp(FIN, BODY_DARK, abs(dx) / max(span, 1e-6))

    # --- mantle ---
    mt = (y - 0.07) / 0.46
    hw = mantle_halfwidth(mt)
    if hw > 0 and abs(dx) < hw:
        # Light from the upper left, so the body reads as a tube not a blob.
        shade = (dx / hw) * 0.5 + 0.5
        c = lerp(BODY_LIGHT, BODY_MID, min(1.0, shade * 1.15))
        return lerp(c, BODY_DARK, max(0.0, (abs(dx) / hw) ** 3))

    # --- head ---
    hy = (y - 0.50) / 0.16
    if 0.0 <= hy <= 1.0:
        hwid = 0.175 * (1.0 - 0.30 * hy ** 2)
        if abs(dx) < hwid:
            shade = (dx / hwid) * 0.5 + 0.5
            return lerp(BODY_LIGHT, BODY_MID, min(1.0, shade * 1.2))

    return None


def eye(x, y):
    """The defining feature: proportionally enormous."""
    for side in (-1, 1):
        ex, ey = 0.5 + side * 0.082, 0.575
        d = math.hypot(x - ex, (y - ey) * 1.06)
        if d < 0.056:
            if d > 0.047:
                return EYE_DARK
            pd = math.hypot(x - ex, (y - ey) * 1.06)
            if pd < 0.026:
                return EYE_DARK
            gx, gy = ex - side * 0.016, ey - 0.018
            if math.hypot(x - gx, y - gy) < 0.011:
                return GLINT
            return EYE_WHITE
    return None


def render() -> list[list[int]]:
    rows = []
    cx = cy = 0.5
    for py in range(W):
        row = []
        for px in range(W):
            x = (px + 0.5) / W
            y = (py + 0.5) / W

            c = eye(x, y) or sample(x, y)
            if c is None:
                r = math.hypot(x - cx, y - cy) / 0.72
                c = lerp(BG_INNER, BG_OUTER, min(1.0, r))
            row.extend(c)
            row.append(255)
        rows.append(row)
    return rows


def downsample(rows: list[list[int]]) -> list[bytearray]:
    out = []
    for y in range(SIZE):
        row = bytearray()
        for x in range(SIZE):
            acc = [0, 0, 0, 0]
            for dy in range(SS):
                src = rows[y * SS + dy]
                base = (x * SS) * 4
                for dx in range(SS):
                    o = base + dx * 4
                    acc[0] += src[o]
                    acc[1] += src[o + 1]
                    acc[2] += src[o + 2]
                    acc[3] += src[o + 3]
            n = SS * SS
            row.extend(bytes(v // n for v in acc))
        out.append(row)
    return out


def write_png(path: Path, rows: list[bytearray]) -> None:
    raw = b"".join(b"\x00" + bytes(r) for r in rows)

    def chunk(tag: bytes, data: bytes) -> bytes:
        return (struct.pack(">I", len(data)) + tag + data
                + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF))

    ihdr = struct.pack(">IIBBBBB", SIZE, SIZE, 8, 6, 0, 0, 0)
    png = (b"\x89PNG\r\n\x1a\n"
           + chunk(b"IHDR", ihdr)
           + chunk(b"IDAT", zlib.compress(raw, 9))
           + chunk(b"IEND", b""))
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(png)


if __name__ == "__main__":
    root = Path(__file__).resolve().parent.parent
    out = root / "mod" / "src" / "main" / "resources" / "assets" / "squidutils" / "icon.png"
    write_png(out, downsample(render()))
    print(f"wrote {out}  ({out.stat().st_size:,} bytes, {SIZE}x{SIZE})")

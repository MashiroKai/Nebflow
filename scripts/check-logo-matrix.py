#!/usr/bin/env python3
"""check-logo-matrix.py -- anti-drift gate for the installer banner logo matrix.

Single source of truth: the pixil design file's embedded layer PNG
(.nebflow/uploads/bright-4.pixil, canvas declared 6x5; its layer content is
the 4x3 mark below -- canvas row 0/4 and col 0/5 are transparent padding,
NOT rendered: zero phantom rows/columns, author ruling 2026-09-06 v2).

Cross-validation on record (scripts run 2026-09-06, pixel-exact):
  - dark.png / bright.png   ink = exact 4x3 grid of 48px cells, origin (16,40)
  - dark-4.png / bright-4.png ink = exact 4x3 grid of 56px cells, origin (0,28)
  - all four agree with the pixil content shape cell-for-cell.

The gate:
  1. derives the truth matrix from the pixil artifact when present
     (stdlib-only PNG decode: base64 -> zlib -> unfilter) and asserts it
     equals the embedded TRUTH constant;
  2. parses BANNER_MASK out of release/install.sh and $BannerMask out of
     release/install.ps1 and asserts both equal TRUTH;
  3. asserts scripts/preview-brand-ui.sh defines NO mask of its own (it
     must extract the BRAND-UI block from install.sh -> single source);
  4. with PIL available, re-derives the shape from the four PNG exports
     and asserts agreement (skipped gracefully when PIL/files absent).

Exit 0 = all green, 1 = drift detected (prints per-cell diff).
"""
import base64
import json
import os
import re
import struct
import sys
import zlib

# ---------------------------------------------------------------------------
# TRUTH -- embedded constant, kept byte-identical to the pixil layer content.
# Update ONLY together with the pixil source, never by hand-editing here.
# ---------------------------------------------------------------------------
TRUTH = ["G.WW", ".W.W", ".W.W"]

GREEN_RGB = {(7, 193, 96), (76, 175, 80)}  # #07C160 (exports) / #4CAF50 (pixil)

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
INSTALL_SH = os.path.join(ROOT, "release", "install.sh")
INSTALL_PS1 = os.path.join(ROOT, "release", "install.ps1")
PREVIEW_SH = os.path.join(ROOT, "scripts", "preview-brand-ui.sh")
LOGO_DIR = os.path.expanduser(
    "~/.nebflow/docs/Nebflow/assets/logo")


def find_pixil():
    """Locate bright-4.pixil: repo-local .nebflow/uploads first, then parent
    dirs (git worktrees keep the runtime home at the main repo root)."""
    d = ROOT
    while True:
        cand = os.path.join(d, ".nebflow", "uploads", "bright-4.pixil")
        if os.path.exists(cand):
            return cand
        parent = os.path.dirname(d)
        if parent == d:
            return None
        d = parent


PIXIL = find_pixil()


# --- minimal stdlib PNG decode (enough for small 8-bit RGBA images) --------
def _paeth(a, b, c):
    p = a + b - c
    pa, pb, pc = abs(p - a), abs(p - b), abs(p - c)
    if pa <= pb and pa <= pc:
        return a
    return b if pb <= pc else c


def decode_png_rgba(data):
    """Decode an 8-bit non-interlaced RGBA or RGB PNG to (width, height, pixels)."""
    if data[:8] != b"\x89PNG\r\n\x1a\n":
        raise ValueError("not a PNG")
    pos, idat, meta = 8, b"", None
    while pos + 8 <= len(data):
        (length,) = struct.unpack(">I", data[pos:pos + 4])
        ctype = data[pos + 4:pos + 8]
        body = data[pos + 8:pos + 8 + length]
        if ctype == b"IHDR":
            w, h, depth, color = struct.unpack(">IIBB", body[:10])
            meta = (w, h, depth, color)
        elif ctype == b"IDAT":
            idat += body
        elif ctype == b"IEND":
            break
        pos += 12 + length
    w, h, depth, color = meta
    if depth != 8 or color not in (2, 6):
        raise ValueError(f"unsupported PNG: depth={depth} colortype={color}")
    bpp = 4 if color == 6 else 3
    raw = zlib.decompress(idat)
    stride = w * bpp
    out = bytearray()
    prev = bytearray(stride)
    i = 0
    for _ in range(h):
        f = raw[i]
        i += 1
        line = bytearray(raw[i:i + stride])
        i += stride
        if f == 1:
            for x in range(bpp, stride):
                line[x] = (line[x] + line[x - bpp]) & 0xFF
        elif f == 2:
            for x in range(stride):
                line[x] = (line[x] + prev[x]) & 0xFF
        elif f == 3:
            for x in range(stride):
                left = line[x - bpp] if x >= bpp else 0
                line[x] = (line[x] + ((left + prev[x]) >> 1)) & 0xFF
        elif f == 4:
            for x in range(stride):
                left = line[x - bpp] if x >= bpp else 0
                ul = prev[x - bpp] if x >= bpp else 0
                line[x] = (line[x] + _paeth(left, prev[x], ul)) & 0xFF
        elif f != 0:
            raise ValueError(f"bad filter {f}")
        out += line
        prev = line
    px = []
    for y in range(h):
        row = []
        for x in range(w):
            o = (y * w + x) * bpp
            row.append(tuple(out[o:o + 3]) + ((out[o + 3],) if bpp == 4 else (255,)))
        px.append(row)
    return w, h, px


def classify(r, g, b, a):
    if a == 0:
        return "."
    if (r, g, b) in GREEN_RGB:
        return "G"
    return "W"


def truth_from_pixil(path):
    """Derive the truth matrix from the pixil JSON's embedded layer PNG."""
    with open(path, "rb") as f:
        doc = json.loads(f.read())
    w, h = doc["width"], doc["height"]
    src = doc["frames"][0]["layers"][0]["src"]
    # scheme string is corrupted in the wild; payload after the first comma
    # is an intact base64 PNG (iVBORw0KGgo...).
    png = base64.b64decode(src[src.find(",") + 1:])
    lw, lh, px = decode_png_rgba(png)
    if (lw, lh) != (w, h):
        raise ValueError(f"layer size {lw}x{lh} != declared canvas {w}x{h}")
    grid = ["".join(classify(*px[y][x]) for x in range(w)) for y in range(h)]
    # crop transparent border rows/cols -> the content mark, zero padding
    rows = [r for r in grid if r.strip(".")]
    if not rows:
        raise ValueError("pixil layer is empty")
    cols = [i for i in range(w) if any(r[i] != "." for r in grid)]
    return [r[min(cols):max(cols) + 1] for r in rows], grid


# --- mask extraction from the three files ----------------------------------
def parse_bash_mask(text):
    m = re.search(r"BANNER_MASK=\(\n((?:\s*\"[^\"]*\"\n)+)\s*\)", text)
    if not m:
        raise ValueError("BANNER_MASK=( ... ) not found in install.sh")
    return re.findall(r"\"([^\"]*)\"", m.group(1))


def parse_ps1_mask(text):
    m = re.search(r"\$BannerMask = @\(\n((?:\s*\"[^\"]*\",?\n)+)\s*\)", text)
    if not m:
        raise ValueError("$BannerMask = @( ... ) not found in install.ps1")
    return re.findall(r"\"([^\"]*)\"", m.group(1))


def diff(got, want):
    lines = []
    for r in range(max(len(got), len(want))):
        g = got[r] if r < len(got) else "<missing row>"
        w = want[r] if r < len(want) else "<missing row>"
        if g == w:
            lines.append(f"    row{r}: '{g}'  ok")
            continue
        lines.append(f"    row{r}: got '{g}'  want '{w}'")
        for c in range(max(len(g), len(w))):
            gc = g[c] if c < len(g) else "<none>"
            wc = w[c] if c < len(w) else "<none>"
            if gc != wc:
                lines.append(f"      (row{r},col{c}): got '{gc}' want '{wc}'")
    return lines


def main():
    failures = []

    # 1. pixil artifact -> truth (when present)
    if PIXIL:
        try:
            derived, grid6x5 = truth_from_pixil(PIXIL)
            print(f"[pixil] {os.path.relpath(PIXIL, ROOT)} canvas 6x5 verbatim:")
            for r in grid6x5:
                print(f"    {r.replace('.', chr(0xB7)).replace('W', chr(0x2588)).replace('G', 'G')}")
            print(f"[pixil] content mark: {derived}")
            if derived != TRUTH:
                failures.append("[pixil] derived content != embedded TRUTH:\n" +
                                "\n".join(diff(derived, TRUTH)))
            else:
                print("[pixil] derived == TRUTH  [OK]")
        except Exception as e:  # noqa: BLE001 - gate must report, not crash
            failures.append(f"[pixil] decode failed: {e}")
    else:
        print(f"[pixil] artifact not found ({PIXIL}) - skipping re-derivation, "
              "TRUTH constant used as-is")

    # 2. installers
    for label, path, parser in (
            ("install.sh", INSTALL_SH, parse_bash_mask),
            ("install.ps1", INSTALL_PS1, parse_ps1_mask)):
        with open(path, "r", encoding="utf-8-sig" if label.endswith(".ps1") else "utf-8") as f:
            mask = parser(f.read())
        if mask == TRUTH:
            print(f"[{label}] mask == TRUTH  [OK]  {mask}")
        else:
            failures.append(f"[{label}] mask drift:\n" + "\n".join(diff(mask, TRUTH)))

    # 3. preview-brand-ui.sh must not carry its own matrix
    with open(PREVIEW_SH, "r", encoding="utf-8") as f:
        prev = f.read()
    if "BRAND-UI-BEGIN" not in prev:
        failures.append("[preview-brand-ui.sh] does not extract the BRAND-UI block "
                        "from install.sh (single-source chain broken)")
    else:
        print("[preview-brand-ui.sh] extracts BRAND-UI block from install.sh  [OK]")
    embedded = re.findall(r"[\"']([GW.]{3,})[\"']", prev)
    if embedded:
        failures.append(f"[preview-brand-ui.sh] embeds its own mask literal(s): {embedded}")
    else:
        print("[preview-brand-ui.sh] no independent mask literal  [OK]")

    # 4. optional PNG cross-check (needs PIL + home-dir assets)
    try:
        from PIL import Image  # noqa: PLC0415 - optional dependency
        pngs = sorted(os.listdir(LOGO_DIR)) if os.path.isdir(LOGO_DIR) else []
        if not pngs:
            print("[png] assets not present - cross-check skipped")
        else:
            for name, (gw, gh, ox, oy, cw, ch) in (
                    ("dark.png", (4, 3, 16, 40, 48, 48)),
                    ("bright.png", (4, 3, 16, 40, 48, 48)),
                    ("dark-4.png", (4, 3, 0, 28, 56, 56)),
                    ("bright-4.png", (4, 3, 0, 28, 56, 56))):
                im = Image.open(os.path.join(LOGO_DIR, name)).convert("RGBA")
                p = im.load()
                shape = []
                for r in range(gh):
                    row = ""
                    for c in range(gw):
                        ink = [p[x, y] for y in range(oy + r * ch, oy + (r + 1) * ch)
                               for x in range(ox + c * cw, ox + (c + 1) * cw)]
                        solid = [q for q in ink if q[3] > 0]
                        if not solid:
                            row += "."
                        elif any((q[0], q[1], q[2]) in GREEN_RGB for q in solid):
                            row += "G"
                        else:
                            row += "W"
                    shape.append(row)
                if shape == TRUTH:
                    print(f"[png] {name} @ {gw}x{gh} {cw}px origin({ox},{oy}) == TRUTH  [OK]")
                else:
                    failures.append(f"[png] {name} drift:\n" + "\n".join(diff(shape, TRUTH)))
    except ImportError:
        print("[png] PIL unavailable - cross-check skipped")

    if failures:
        print("\n== LOGO MATRIX GATE: FAIL ==")
        for f_ in failures:
            print(f_)
        return 1
    print("\n== LOGO MATRIX GATE: all green ==")
    return 0


if __name__ == "__main__":
    sys.exit(main())

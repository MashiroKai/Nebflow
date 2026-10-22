#!/usr/bin/env python3
"""render-install-banner.py -- sampler that turns the OFFICIAL stand logo kit
into the terminal banner payload embedded in release/install.sh + install.ps1.

Author mandate (installer-startup-logo batch, 2026-10-22): the old 4x3
green-pixel "S" mask does not match the brand. The banner must reproduce the
official logo + wordmark, auto-sampled from the authoritative vector assets -
hand-drawn approximations are forbidden.

Source of record
----------------
The Illustrator kit SVGs, vendored byte-identically in TWO places (asserted
by `--check-sources`):
  - this repo : src/main/resources/web/brand/stand/illustrator/svg/
  - website   : <website-repo>/public/brand/stand/   (--website for the check)
The website published set was sha256-verified at the 2026-10-19 official-logo
batch; the repo copies are the same bytes, so the sampler reads the repo set
by default (--svgdir to override).

Art choice: stand-horizontal (symbol + wordmark lockup) - the composition the
website navbar renders (BrandAsset variant="horizontal"). Rendering the whole
lockup in one raster reproduces the official symbol/wordmark proportion with
zero manual layout.

What is embedded
----------------
Two render paths carry the same official art (dual-path batch 2026-10-22):

  image path (iTerm2/WezTerm/mintty OSC 1337 + sixel: Windows Terminal,
  foot, xterm+...): the REAL logo raster, base64 PNG + pre-encoded sixel:

    BANNER_PNG_DARK/LIGHT   base64 of the white/black-ink SVG rasterized at
                            PNG_PX_W (natural aspect). Emitted as one OSC 1337
                            "File=name=...;inline=1;width=72;height=9:<b64>" -
                            CELL units: 72x9 cells is physically 4:1 (a cell
                            is ~1:2), the lockup's own aspect, on the same
                            footprint as the half-block art (72 cols x 9
                            half-block rows).
    BANNER_SIXEL_DARK/LIGHT sixel payload BODY (raster attrs + palette + RLE
                            data, NO DCS/ST framing - the DCS introducer and
                            ST terminator are runtime bytes, never embedded,
                            so the file stays pure printable ASCII). Canvas
                            SIXEL_PX_W x natural raster height (~144): at the
                            usual ~8x16 px cell that lands on the SAME 72x9-
                            cell footprint as the half-block art, and the
                            4.007:1 canvas aspect keeps the official
                            proportions pixel-native. Palette color 1 = theme
                            ink (0-100 units per the sixel spec); P2=1 keeps
                            unpainted pixels transparent.

  block-art path (old conhost / dumb pipes / every fallback): a 1-bit
  coverage grid of the lockup (ink vs transparent - the kit is pure
  monochrome ink: fill #000000 black-ink variant / #FFFFFF white-ink
  variant), run-length encoded as ASCII and identical in both installers:

    BANNER_ART   rows joined with ';', tokens = <count><cell>
                 cell: '.' empty | 'u' ink top-half (U+2580 BLOCK)
                       | 'n' ink bottom-half (U+2584) | '#' ink full (U+2588)
    BANNER_INK_DARK  truecolor RGB of the white-ink SVG (dark terminals)
    BANNER_INK_LIGHT truecolor RGB of the black-ink SVG (light terminals)

The renderer maps u/n/# to real U+2580/U+2584/U+2588 glyphs painted with the
theme's ink FOREGROUND color (background stays the terminal's own - the art
keeps its transparency). Mono degrade renders '#' per cell. Every embedded
payload is pure ASCII in both languages (glyphs/escapes are produced at
runtime), keeping install.ps1's ASCII-only invariant; the sixel bodies are
single-quoted in BOTH languages ($ ' " ` \\ never occur in the 0x3F-0x7E
sixel charset - the runtime framing owns every escape byte).

Pipeline (deterministic, byte-stable across runs)
-------------------------------------------------
  rsvg-convert (SVG -> PNG at WIDTH*SUPERSAMPLE) -> PIL BOX downscale to the
  even-height terminal grid -> alpha coverage threshold -> half-block cells ->
  RLE. Same inputs always produce the same payload (the anti-drift gate in
  scripts/check-logo-matrix.py re-derives and compares).

Dev-time only: needs rsvg-convert + PIL. The INSTALLERS never invoke this -
they carry the generated constants and zero dependencies.

Usage
-----
  render-install-banner.py                 print the generated blocks + stats
  render-install-banner.py --update        rewrite the marked blocks in
                                           release/install.sh + install.ps1
  render-install-banner.py --check         exit 0 when embedded == regenerated
  render-install-banner.py --preview --level 3 --theme dark
                                           print the ANSI banner (eyeball /
                                           terminal screenshots; --level 0..3)
  render-install-banner.py --check-sources --website /path/to/stand-website
                                           assert repo SVGs == website SVGs

Exit codes: 0 ok, 1 drift/mismatch, 2 missing tool/input.
"""
import argparse
import os
import re
import subprocess
import sys
import tempfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SVG_DIR_DEFAULT = os.path.join(
    ROOT, "src", "main", "resources", "web", "brand", "stand", "illustrator", "svg")
INSTALL_SH = os.path.join(ROOT, "release", "install.sh")
INSTALL_PS1 = os.path.join(ROOT, "release", "install.ps1")

ART = "stand-horizontal"          # official symbol+wordmark lockup
BLACK_SVG = f"{ART}-black.svg"    # black ink  -> light terminals
WHITE_SVG = f"{ART}-white.svg"    # white ink  -> dark terminals

WIDTH = 72          # art columns; +2 indent = 74 < 80-col floor
THRESHOLD = 0.40    # alpha coverage >= this counts as ink
SUPERSAMPLE = 32    # raster width multiplier before BOX downscale

PNG_PX_W = 576      # OSC 1337 raster: 8 px per art column, natural aspect
SIXEL_PX_W = 576    # sixel canvas: 8 px per art column (assumes ~8x16 cell);
                    # natural raster height (~144 px = 9 cells) keeps the
                    # canvas on the same 72x9-cell footprint as the block art
                    # with the official 4.007:1 aspect pixel-native

BEGIN_SH = "# >>> BANNER-ART-BEGIN (generated by scripts/render-install-banner.py"
END_SH = "# <<< BANNER-ART-END"
BEGIN_PS1 = "# --- BANNER-ART-BEGIN (generated by scripts/render-install-banner.py"
END_PS1 = "# --- BANNER-ART-END"

GLYPHS = {"u": "\u2580", "n": "\u2584", "#": "\u2588"}  # runtime only


def die(msg, code=2):
    print(f"render-install-banner: {msg}", file=sys.stderr)
    sys.exit(code)


def parse_view_box(text):
    m = re.search(r'viewBox="([\d.]+)\s+([\d.]+)\s+([\d.]+)\s+([\d.]+)"', text)
    if not m:
        die(f"viewBox not found in {ART} SVG")
    return tuple(float(v) for v in m.group(1, 2, 3, 4))


def parse_fill(text, path):
    m = re.search(r'fill="#([0-9A-Fa-f]{6})"', text)
    if not m:
        die(f"ink fill not found in {path}")
    v = m.group(1)
    return str(int(v[0:2], 16)), str(int(v[2:4], 16)), str(int(v[4:6], 16))


def rasterize(svg_path, px_width, out_png):
    try:
        subprocess.run(
            ["rsvg-convert", "-w", str(px_width), svg_path, "-o", out_png],
            check=True, capture_output=True)
    except FileNotFoundError:
        die("rsvg-convert not on PATH (dev-time dependency of this sampler; "
            "the installers themselves need nothing)")
    except subprocess.CalledProcessError as e:
        die(f"rsvg-convert failed: {e.stderr.decode(errors='replace').strip()}")


def coverage_grid(svg_path, width, threshold):
    """SVG -> 1-bit grid (list of '01' strings), even row count."""
    from PIL import Image
    with open(svg_path, "r", encoding="utf-8") as f:
        vb = parse_view_box(f.read())
    vw, vh = vb[2], vb[3]
    height = 2 * round(width * vh / vw / 2)      # even: half-block pairs
    with tempfile.TemporaryDirectory() as td:
        png = os.path.join(td, "art.png")
        rasterize(svg_path, width * SUPERSAMPLE, png)
        img = Image.open(png).convert("RGBA")
    # BOX = deterministic area average; exact target grid (aspect from viewBox)
    img = img.resize((width, height), Image.Resampling.BOX)
    px = img.load()
    cut = round(threshold * 255)
    return ["".join("1" if px[x, y][3] >= cut else "0"
                    for x in range(width)) for y in range(height)]


def grid_to_cells(grid):
    """Pair pixel rows into half-block cells; odd tail row -> bottom empty."""
    if len(grid) % 2:
        grid = grid + ["0" * len(grid[0])]
    rows = []
    for y in range(0, len(grid), 2):
        top, bot = grid[y], grid[y + 1]
        row = []
        for x in range(len(top)):
            row.append({(0, 0): ".", (1, 0): "u", (0, 1): "n", (1, 1): "#"}[
                (top[x] == "1", bot[x] == "1")])
        rows.append("".join(row))
    return rows


def rle(row):
    out, i = [], 0
    while i < len(row):
        j = i
        while j < len(row) and row[j] == row[i]:
            j += 1
        out.append(f"{j - i}{row[i]}")
        i = j
    return "".join(out)


def payload(cells):
    return ";".join(rle(r) for r in cells)


def png_b64(svg_path, px_width):
    """SVG -> base64 PNG at the given pixel width (natural aspect height)."""
    import base64
    with tempfile.TemporaryDirectory() as td:
        png = os.path.join(td, "logo.png")
        rasterize(svg_path, px_width, png)
        with open(png, "rb") as f:
            return base64.b64encode(f.read()).decode("ascii")


def sixel_body(svg_path, ink_rgb, px_width, threshold):
    """SVG -> sixel payload BODY (no DCS/ST framing).

    Rasterize at px_width (natural aspect height h), alpha-threshold to 1 bit,
    pack 6-row bands into the sixel charset (0x3F + bitmask) with !n runs.
    The canvas keeps the source aspect: at the usual ~8x16 px cell it lands
    on the same 72x9-cell footprint as the half-block art. Raster attrs
    "1;1;<w>;<h>, palette color 1 = the ink in 0-100 sixel units, P2=1
    transparency is declared by the runtime DCS (ESC P0;1;0q).
    """
    rows = coverage_grid(svg_path, px_width, threshold)
    while len(rows) % 6:
        rows.append("0" * px_width)
    r100 = lambda v: str(round(int(v) * 100 / 255))  # sixel colors are 0-100
    if isinstance(ink_rgb, str):
        ink_rgb = ink_rgb.split(";")
    pal = "#1;2;%s" % ";".join(r100(v) for v in ink_rgb)
    bands = []
    for band_start in range(0, len(rows), 6):
        chars = []
        for x in range(px_width):
            v = 0
            for i in range(6):
                if rows[band_start + i][x] == "1":
                    v |= 1 << i
            chars.append(chr(0x3F + v))
        enc, i = [], 0
        while i < len(chars):
            j = i
            while j < len(chars) and chars[j] == chars[i]:
                j += 1
            n = j - i
            enc.append(f"!{n}{chars[i]}" if n > 3 else "".join(chars[i:j]))
            i = j
        bands.append("".join(enc))
    # raster attrs + palette + bands: the palette must flow DIRECTLY into the
    # first data band (a '-' would shift every band down one cell row); all
    # canvas rows are carried so h sixel rows = the full natural height.
    return '"1;1;%d;%d%s-%s' % (px_width, len(rows), pal, "-".join(bands))


def build(svgdir, width=WIDTH, threshold=THRESHOLD):
    """Rasterize both ink variants; return the full payload set + stats."""
    black = os.path.join(svgdir, BLACK_SVG)
    white = os.path.join(svgdir, WHITE_SVG)
    for p in (black, white):
        if not os.path.exists(p):
            die(f"authoritative asset missing: {p}")
    with open(black, "r", encoding="utf-8") as f:
        ink_light = ";".join(parse_fill(f.read(), black))  # black -> light theme
    with open(white, "r", encoding="utf-8") as f:
        ink_dark = ";".join(parse_fill(f.read(), white))   # white -> dark theme
    g_black = grid_to_cells(coverage_grid(black, width, threshold))
    g_white = grid_to_cells(coverage_grid(white, width, threshold))
    if g_black != g_white:
        die("black-ink and white-ink kit variants rasterize to different "
            "shapes - the ink pair is inconsistent, refusing to embed")
    art = ";".join(rle(r) for r in g_black)
    # image payloads: same kit, same shape invariants per ink variant
    png_dark = png_b64(white, PNG_PX_W)
    png_light = png_b64(black, PNG_PX_W)
    sixel_dark = sixel_body(white, ink_dark, SIXEL_PX_W, threshold)
    sixel_light = sixel_body(black, ink_light, SIXEL_PX_W, threshold)
    # the sixel canvases of the two ink variants must carry the same ink shape
    def _sixel_shape(body):
        return re.sub(r"#1;2;[\d;]+", "", body)
    if _sixel_shape(sixel_dark) != _sixel_shape(sixel_light):
        die("black-ink and white-ink sixel bodies differ beyond the palette - "
            "the ink pair is inconsistent, refusing to embed")
    return {"art": art, "ink_dark": ink_dark, "ink_light": ink_light,
            "png_dark": png_dark, "png_light": png_light,
            "sixel_dark": sixel_dark, "sixel_light": sixel_light,
            "stats": {"width": width, "rows": len(g_black),
                      "cells": sum(len(r) for r in g_black),
                      "ink_cells": sum(r.count("u") + r.count("n") + r.count("#")
                                       for r in g_black),
                      "png_dark_b64": len(png_dark), "png_light_b64": len(png_light),
                      "sixel_dark": len(sixel_dark), "sixel_light": len(sixel_light)}}


def sh_block(p):
    return (f"{BEGIN_SH} -- do not hand-edit) >>>\n"
            f"# Official stand-horizontal lockup (symbol + wordmark), auto-sampled\n"
            f"# from src/main/resources/web/brand/stand/illustrator/svg/ by\n"
            f"# scripts/render-install-banner.py (1-bit coverage, RLE cells:\n"
            f"# '.' empty | 'u' {chr(0x2580)} top | 'n' {chr(0x2584)} bottom | '#' {chr(0x2588)} full; rows joined ';').\n"
            f"BANNER_ART=\"{p['art']}\"\n"
            f"BANNER_INK_DARK=\"{p['ink_dark']}\"    # white-ink kit variant (dark terminals)\n"
            f"BANNER_INK_LIGHT=\"{p['ink_light']}\"      # black-ink kit variant (light terminals)\n"
            f"# image path payloads (iTerm2/WezTerm/mintty/konsole OSC 1337 + sixel:\n"
            f"# Windows Terminal 1.22+/foot/...; old conhost never sees them - the\n"
            f"# block art above is its fallback). Single-quoted: the sixel charset\n"
            f"# (0x3F-0x7E) can never hold a quote, the DCS/ST framing is added at\n"
            f"# runtime so this file stays pure printable ASCII.\n"
            f"BANNER_PNG_DARK=\"{p['png_dark']}\"\n"
            f"BANNER_PNG_LIGHT=\"{p['png_light']}\"\n"
            f"BANNER_SIXEL_DARK='{p['sixel_dark']}'\n"
            f"BANNER_SIXEL_LIGHT='{p['sixel_light']}'\n"
            f"{END_SH}")


def ps1_block(p):
    return (f"{BEGIN_PS1} -- do not hand-edit) ---\n"
            f"# Official stand-horizontal lockup (symbol + wordmark), auto-sampled\n"
            f"# from src/main/resources/web/brand/stand/illustrator/svg/ by\n"
            f"# scripts/render-install-banner.py (1-bit coverage, RLE cells:\n"
            f"# '.' empty | 'u' top | 'n' bottom | '#' full; rows joined ';').\n"
            f"# Pure-ASCII invariant holds: the u/n/# payload never contains the\n"
            f"# block glyphs themselves - Write-Banner emits them via [char]; the\n"
            f"# image payloads are base64/printable-ASCII, the OSC/DCS escape bytes\n"
            f"# are produced at runtime via [char]27.\n"
            f"$BannerArt = \"{p['art']}\"\n"
            f"$BannerInkDark = \"{p['ink_dark']}\"    # white-ink kit variant (dark terminals)\n"
            f"$BannerInkLight = \"{p['ink_light']}\"      # black-ink kit variant (light terminals)\n"
            f"$BannerPngDark = \"{p['png_dark']}\"\n"
            f"$BannerPngLight = \"{p['png_light']}\"\n"
            f"$BannerSixelDark = '{p['sixel_dark']}'\n"
            f"$BannerSixelLight = '{p['sixel_light']}'\n"
            f"{END_PS1}")


def replace_marked(text, begin, end, block):
    """Replace the text between begin/end markers (inclusive)."""
    b = text.find(begin)
    e = text.find(end, b + 1)
    if b < 0 or e < 0:
        die(f"marked block not found (begin={begin[:24]!r}... end={end!r})")
    return text[:b] + block + text[e + len(end):]


def parse_embedded(path, begin, end):
    """Extract the seven payload constants; both languages, both quote styles."""
    with open(path, "r", encoding="utf-8-sig") as f:
        text = f.read()
    b = text.find(begin)
    e = text.find(end, b + 1)
    if b < 0 or e < 0:
        return None
    seg = text[b:e]
    def val(*names):
        for name in names:
            m = re.search(rf'^\s*\$?{name}\s*=\s*(["\'])(.*?)\1', seg, re.M)
            if m:
                return m.group(2)
        return None
    keys = (("art", "BANNER_ART", "BannerArt"),
            ("ink_dark", "BANNER_INK_DARK", "BannerInkDark"),
            ("ink_light", "BANNER_INK_LIGHT", "BannerInkLight"),
            ("png_dark", "BANNER_PNG_DARK", "BannerPngDark"),
            ("png_light", "BANNER_PNG_LIGHT", "BannerPngLight"),
            ("sixel_dark", "BANNER_SIXEL_DARK", "BannerSixelDark"),
            ("sixel_light", "BANNER_SIXEL_LIGHT", "BannerSixelLight"))
    got = {k: val(*names) for k, *names in keys}
    return None if any(v is None for v in got.values()) else got


def cmd_preview(level, theme):
    p = build(SVG_DIR_DEFAULT)
    art = p["art"]
    ink = p["ink_dark"] if theme == "dark" else p["ink_light"]
    rst, dim = "\033[0m", "\033[2m"
    if level >= 1:
        fg = {"3": f"\033[38;2;{ink}m", "2": ("\033[38;5;231m" if theme == "dark"
                                              else "\033[38;5;16m"),
              "1": ("\033[97m" if theme == "dark" else "\033[30m")}[str(level)]
    else:
        fg = ""
    print()
    for row in art.split(";"):
        line, i = [], 0
        while i < len(row):
            m = re.match(r"(\d+)([.un#])", row[i:])
            count, cell = int(m.group(1)), m.group(2)
            i += m.end()
            if level == 0:
                line.append(("#" if cell != "." else " ") * count)
            else:
                line.append("".join(GLYPHS.get(c, " ") for c in cell * count))
        print("  " + fg + "".join(line) + (rst if fg else ""))
    print("  " + dim + "2026.10.21 installer (beta)" + rst)
    print()


def main():
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--svgdir", default=SVG_DIR_DEFAULT)
    ap.add_argument("--width", type=int, default=WIDTH)
    ap.add_argument("--threshold", type=float, default=THRESHOLD)
    ap.add_argument("--update", action="store_true",
                    help="rewrite the marked blocks in release/install.sh + .ps1")
    ap.add_argument("--check", action="store_true",
                    help="verify embedded blocks == regenerated payload")
    ap.add_argument("--preview", action="store_true",
                    help="print the ANSI banner to stdout")
    ap.add_argument("--level", type=int, default=3, choices=[0, 1, 2, 3],
                    help="preview degrade level (with --preview)")
    ap.add_argument("--theme", default="dark", choices=["dark", "light"])
    ap.add_argument("--check-sources", metavar="WEBSITE_DIR",
                    help="assert repo kit SVGs are byte-identical to the "
                         "website's public/brand/stand/ set")
    a = ap.parse_args()

    if a.check_sources:
        web = os.path.join(a.check_sources, "public", "brand", "stand")
        bad = 0
        for name in sorted(os.listdir(a.svgdir)):
            if not name.endswith(".svg"):
                continue
            repo = os.path.join(a.svgdir, name)
            site = os.path.join(web, name)
            if not os.path.exists(site):
                print(f"  MISSING on website side: {name}"); bad += 1; continue
            with open(repo, "rb") as f1, open(site, "rb") as f2:
                same = f1.read() == f2.read()
            print(f"  {'IDENTICAL' if same else 'DIFFERS  '} {name}")
            bad += 0 if same else 1
        sys.exit(1 if bad else 0)

    if a.preview:
        cmd_preview(a.level, a.theme)
        return

    p = build(a.svgdir, a.width, a.threshold)
    sb, pb = sh_block(p), ps1_block(p)

    if a.update:
        for path, block, begin, end in ((INSTALL_SH, sb, BEGIN_SH, END_SH),
                                        (INSTALL_PS1, pb, BEGIN_PS1, END_PS1)):
            with open(path, "r", encoding="utf-8-sig") as f:
                text = f.read()
            # Compute the replacement BEFORE opening for write: open("w")
            # truncates at open time, and a die() inside replace_marked must
            # never leave a half-written installer behind.
            new_text = replace_marked(text, begin, end, block)
            with open(path, "w", encoding="utf-8-sig" if path.endswith(".ps1")
                      else "utf-8", newline="") as f:
                f.write(new_text)
            print(f"updated {os.path.relpath(path, ROOT)}")
    elif a.check:
        ok = True
        want_all = {k: p[k] for k in ("art", "ink_dark", "ink_light",
                                      "png_dark", "png_light",
                                      "sixel_dark", "sixel_light")}
        for path, begin, end in ((INSTALL_SH, BEGIN_SH, END_SH),
                                 (INSTALL_PS1, BEGIN_PS1, END_PS1)):
            rel = os.path.relpath(path, ROOT)
            got = parse_embedded(path, begin, end)
            if got is None:
                print(f"[{rel}] BANNER-ART block not found (or constants missing)")
                ok = False
                continue
            drift = {k for k in want_all if got[k] != want_all[k]}
            if not drift:
                print(f"[{rel}] all 7 payloads == regenerated  [OK]")
            else:
                print(f"[{rel}] drift in: {sorted(drift)}")
                for k in sorted(drift):
                    head = f"    {k}: "
                    print(f"{head}embedded {got[k]!r}\n{head}wanted   {want_all[k]!r}")
                ok = False
        sys.exit(0 if ok else 1)
    else:
        print(sb); print(); print(pb); print()
        print(f"stats: {p['stats']}")


if __name__ == "__main__":
    main()

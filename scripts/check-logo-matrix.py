#!/usr/bin/env python3
"""check-logo-matrix.py -- anti-drift gate for the installer banner artwork.

Single source of truth (official-logo batch, author mandate 2026-10-22): the
Illustrator-kit vector assets vendored at
  src/main/resources/web/brand/stand/illustrator/svg/
(byte-identical to the website's public/brand/stand/ set -- the sha256-verified
publication of the 2026-10-19 official-logo batch). The banner art is the
stand-horizontal lockup (symbol + wordmark), auto-sampled by
scripts/render-install-banner.py into a 1-bit half-block payload
(BANNER_ART / $BannerArt) plus the two ink variants' true fill values
(BANNER_INK_DARK / BANNER_INK_LIGHT). The retired 2026-10-20 4x3 green-pixel
"S" mask (pixil-era BANNER_MASK / $BannerMask, design/stand-mark.pixil) is
GONE from the installers; this gate fails if any of it ever comes back.

The gate:
  1. (stdlib) parses the embedded payloads out of BOTH release/install.sh
     and release/install.ps1 and asserts the art + ink constants equal the
     TRUTH constants below and ALL SEVEN payloads (art, 2 inks, 2 base64
     PNGs, 2 sixel bodies) are byte-identical across the two installers -
     installer-vs-installer drift is caught with zero dependencies;
  2. (stdlib) asserts the ink constants match the actual `fill="#..."` values
     in the black-ink / white-ink kit SVGs (the "official true color" rule);
  3. (stdlib) asserts no retired pixil-era face (BANNER_MASK / $BannerMask /
     _px / Write-LogoPixel / design/stand-mark.pixil TRUTH) reappears in the
     installers;
  4. (needs rsvg-convert + PIL, skipped gracefully when absent) re-derives
     ALL payloads from the kit SVGs via scripts/render-install-banner.py and
     asserts they equal both installers' embedded sets - catches the
     constants and the sampler drifting together;
  5. (stdlib) asserts scripts/preview-brand-ui.sh still extracts the
     BRAND-UI block from install.sh and defines NO art payload of its own
     (single-source chain).

Exit 0 = all green, 1 = drift detected (prints what moved), 2 = missing file.
"""
import os
import re
import subprocess
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
INSTALL_SH = os.path.join(ROOT, "release", "install.sh")
INSTALL_PS1 = os.path.join(ROOT, "release", "install.ps1")
PREVIEW_SH = os.path.join(ROOT, "scripts", "preview-brand-ui.sh")
SVG_DIR = os.path.join(
    ROOT, "src", "main", "resources", "web", "brand", "stand", "illustrator", "svg")
SAMPLER = os.path.join(ROOT, "scripts", "render-install-banner.py")

BLACK_SVG = os.path.join(SVG_DIR, "stand-horizontal-black.svg")
WHITE_SVG = os.path.join(SVG_DIR, "stand-horizontal-white.svg")

# ---------------------------------------------------------------------------
# TRUTH -- embedded constants, regenerated ONLY via
#   python3 scripts/render-install-banner.py --update
# never by hand. Keep byte-identical to the installers' BANNER-ART blocks.
# ---------------------------------------------------------------------------
TRUTH_ART = ("15.5n52.;9.3n5#2u53.;5.2n4#3u11.7n2.8n3.2n5.1n6.2n1.8n2.;"
             "2.1n5#1u15.2#5u2.3u2#3u2.1n3#1n4.3#1n3.2#1.2#5u2#1.;"
             "2.5#3.3u4#2n5.6u2#1n3.2#4.2#1u2.2#1n2.2#2u1#2n2#1.2#5.2#1.;"
             "3.7u3.1n5#4.2n7#1u3.2#2.1n1#1u5.1u1#1n1.2#3.1u3#1.8#1u1.;"
             "9.2n5#2u54.;3.2n6#3u58.;2.5u65.")
TRUTH_INK_DARK = "255;255;255"   # white-ink kit variant (dark terminals)
TRUTH_INK_LIGHT = "0;0;0"        # black-ink kit variant (light terminals)

RETIRED_FACES = ("BANNER_MASK", "BannerMask", "Write-LogoPixel")

# Logical payload keys, in sh-name / ps1-name order (both installers MUST
# agree byte-for-byte on every one - the dual-path batch invariant).
PAYLOAD_KEYS = ("art", "ink_dark", "ink_light",
                "png_dark", "png_light", "sixel_dark", "sixel_light")


def read(path, bom=False):
    with open(path, "r", encoding="utf-8-sig" if bom else "utf-8") as f:
        return f.read()


def parse_sh(text):
    def val(name):
        m = re.search(rf'^{name}=(["\'])(.*?)\1', text, re.M)
        return m.group(2) if m else None
    return {k: val(n) for k, n in zip(
        PAYLOAD_KEYS,
        ("BANNER_ART", "BANNER_INK_DARK", "BANNER_INK_LIGHT",
         "BANNER_PNG_DARK", "BANNER_PNG_LIGHT",
         "BANNER_SIXEL_DARK", "BANNER_SIXEL_LIGHT"))}


def parse_ps1(text):
    def val(name):
        m = re.search(rf'^\${name} = (["\'])(.*?)\1', text, re.M)
        return m.group(2) if m else None
    return {k: val(n) for k, n in zip(
        PAYLOAD_KEYS,
        ("BannerArt", "BannerInkDark", "BannerInkLight",
         "BannerPngDark", "BannerPngLight",
         "BannerSixelDark", "BannerSixelLight"))}


def svg_fill(path):
    m = re.search(r'fill="#([0-9A-Fa-f]{6})"', read(path))
    if not m:
        return None
    v = m.group(1)
    return "%d;%d;%d" % (int(v[0:2], 16), int(v[2:4], 16), int(v[4:6], 16))


def main():
    if not all(os.path.exists(p) for p in (INSTALL_SH, INSTALL_PS1, PREVIEW_SH)):
        print("gate input file missing", file=sys.stderr)
        return 2
    failures = []

    # ---- leg 1: both installers == TRUTH (stdlib) --------------------------
    embedded = {}
    for label, path, parser in (("install.sh", INSTALL_SH, parse_sh),
                                ("install.ps1", INSTALL_PS1, parse_ps1)):
        got = parser(read(path, bom=(label == "install.ps1")))
        embedded[label] = got
        problems = []
        if got["art"] is None or got["ink_dark"] is None or got["ink_light"] is None:
            problems.append("art/ink constants unparsable")
        elif (got["art"], got["ink_dark"], got["ink_light"]) != \
                (TRUTH_ART, TRUTH_INK_DARK, TRUTH_INK_LIGHT):
            problems.append("art/ink drift vs TRUTH")
        for k in ("png_dark", "png_light", "sixel_dark", "sixel_light"):
            if got[k] is None:
                problems.append(f"{k} unparsable")
        if problems:
            failures.append(f"[{label}] {'; '.join(problems)}:\n"
                            f"    -> regenerate: python3 scripts/render-install-banner.py --update")
        else:
            print(f"[{label}] art/inks == TRUTH, image payloads parse  [OK]")
    # cross-installer byte-identity: every payload the dual-path batch ships
    # must be the SAME bytes in sh and ps1 (independent of the sampler).
    if all(embedded[l][k] is not None for l in embedded for k in PAYLOAD_KEYS):
        drifted = [k for k in PAYLOAD_KEYS
                   if embedded["install.sh"][k] != embedded["install.ps1"][k]]
        if drifted:
            failures.append(f"[cross] sh vs ps1 payload drift: {drifted}")
        else:
            print("[cross] all 7 payloads byte-identical sh == ps1  [OK]")

    # ---- leg 2: ink constants == kit SVG fills (stdlib) --------------------
    try:
        black_fill, white_fill = svg_fill(BLACK_SVG), svg_fill(WHITE_SVG)
    except OSError as e:
        black_fill = white_fill = None
        failures.append(f"[svg] kit SVG unreadable: {e}")
    if black_fill != TRUTH_INK_LIGHT:
        failures.append(f"[svg] black-ink fill {black_fill!r} != "
                        f"BANNER_INK_LIGHT {TRUTH_INK_LIGHT!r}")
    else:
        print(f"[svg] black-ink fill == BANNER_INK_LIGHT ({TRUTH_INK_LIGHT})  [OK]")
    if white_fill != TRUTH_INK_DARK:
        failures.append(f"[svg] white-ink fill {white_fill!r} != "
                        f"BANNER_INK_DARK {TRUTH_INK_DARK!r}")
    else:
        print(f"[svg] white-ink fill == BANNER_INK_DARK ({TRUTH_INK_DARK})  [OK]")

    # ---- leg 3: retired pixil-era faces stay retired -----------------------
    for label, path, bom in (("install.sh", INSTALL_SH, False),
                             ("install.ps1", INSTALL_PS1, True)):
        text = read(path, bom=bom)
        back = [face for face in RETIRED_FACES if face in text]
        if back:
            failures.append(f"[{label}] retired pixil-era face(s) reappeared: {back}")
        else:
            print(f"[{label}] no retired mask face  [OK]")
    if os.path.exists(os.path.join(ROOT, "design", "stand-mark.pixil")):
        print("[pixil] design/stand-mark.pixil still present but unreferenced "
              "- the vector pipeline is the logo truth (informational)")

    # ---- leg 4: re-derive from the kit SVGs (needs rsvg-convert + PIL) -----
    try:
        proc = subprocess.run(
            [sys.executable, SAMPLER, "--svgdir", SVG_DIR],
            capture_output=True, text=True, timeout=120)
    except FileNotFoundError:
        proc = None
    if proc is None:
        print("[regen] python unavailable - skipped")
    elif proc.returncode != 0:
        # rsvg-convert missing -> the sampler exits 2 with a clear message;
        # degrade to a skip (the same policy as the retired pixil leg), never
        # a false red.
        if "rsvg-convert" in proc.stderr:
            print("[regen] rsvg-convert not available - re-derivation skipped")
        else:
            failures.append(f"[regen] sampler failed: {proc.stderr.strip()}")
    else:
        # parse the regenerated set out of the sampler's printed sh block
        # (quote-flexible: png/base64 double-quoted, sixel bodies single-)
        def sampler_val(name):
            m = re.search(rf'^{name}=(["\'])(.*?)\1', proc.stdout, re.M)
            return m.group(2) if m else None
        regen = {k: sampler_val(n) for k, n in zip(
            PAYLOAD_KEYS,
            ("BANNER_ART", "BANNER_INK_DARK", "BANNER_INK_LIGHT",
             "BANNER_PNG_DARK", "BANNER_PNG_LIGHT",
             "BANNER_SIXEL_DARK", "BANNER_SIXEL_LIGHT"))}
        missing = [k for k, v in regen.items() if v is None]
        if missing:
            failures.append(f"[regen] sampler produced no {missing}")
        else:
            bad = []
            if (regen["art"], regen["ink_dark"], regen["ink_light"]) != \
                    (TRUTH_ART, TRUTH_INK_DARK, TRUTH_INK_LIGHT):
                bad.append("art/inks vs TRUTH")
            for label in embedded:
                bad += [f"{label}:{k}" for k in PAYLOAD_KEYS
                        if embedded[label][k] != regen[k]]
            if bad:
                failures.append("[regen] drift: " + ", ".join(bad) +
                                " (rerun 'python3 scripts/render-install-banner.py"
                                " --update' and review the diff)")
            else:
                print("[regen] all 7 payloads re-derived from kit SVGs == TRUTH == both installers  [OK]")

    # ---- leg 5: preview script single-source -------------------------------
    prev = read(PREVIEW_SH)
    if "BRAND-UI-BEGIN" not in prev:
        failures.append("[preview-brand-ui.sh] does not extract the BRAND-UI block "
                        "from install.sh (single-source chain broken)")
    else:
        print("[preview-brand-ui.sh] extracts BRAND-UI block from install.sh  [OK]")
    if re.search(r'BANNER_ART\s*=', prev):
        failures.append("[preview-brand-ui.sh] embeds its own banner payload - "
                        "it must source the BRAND-UI block only")
    else:
        print("[preview-brand-ui.sh] no independent payload literal  [OK]")

    if failures:
        print("\n== LOGO MATRIX GATE: FAIL ==")
        for f_ in failures:
            print(f_)
        return 1
    print("\n== LOGO MATRIX GATE: all green ==")
    return 0


if __name__ == "__main__":
    sys.exit(main())

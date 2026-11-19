#!/usr/bin/env python3
"""check-logo-matrix.py -- anti-drift gate for CLI banner artwork.

The official stand-horizontal SVG lockup is the artwork source of truth.
The generated JVM resource and both installers must carry identical art,
ink colors, PNGs and SIXEL bodies. Shell wrappers delegate artwork to the
runtime, and the Scala renderer loads the generated resource rather than
embedding standalone symbol artwork.

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
     (single-source chain);
  6. (stdlib) checks the runtime Properties resource against both installers,
     and rejects independent shell/Scala artwork implementations.

Exit 0 = all green, 1 = drift detected (prints what moved), 2 = missing file.
"""
import importlib.util
import os
from pathlib import Path
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
BANNER_RESOURCE = os.path.join(
    ROOT, "src", "main", "resources", "brand", "stand-terminal-banner.properties")
SPLASH = os.path.join(ROOT, "src", "main", "scala", "nebflow", "cli", "splash.scala")

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


def parse_runtime(text):
    spec = importlib.util.spec_from_file_location("stand_banner_sampler", SAMPLER)
    sampler = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(sampler)
    return sampler.parse_properties(text)


def independent_artwork(text, shell=False):
    """Detect executable artwork rather than matching documentation prose."""
    code = "\n".join(line for line in text.splitlines()
                     if not line.lstrip().startswith("#" if shell else "//"))
    problems = []
    if shell and re.search(r"\bstand_banner_print\b", code):
        problems.append("shell-owned banner renderer")
    # Runs of block glyphs include the standalone S and compact shell variants,
    # but not individual glyphs used to decode the generated half-block payload.
    if re.search(r"[\u2580-\u259f]{2,}", code):
        problems.append("inline block artwork")
    if shell and re.search(r"(?:BANNER_ART|BANNER_MASK|BannerArt|BannerMask)\s*=", code):
        problems.append("independent shell artwork payload")
    return problems


def check_runtime_sources(root=ROOT):
    problems = []
    ops = Path(root) / "ops"
    # Production wrappers only: lifecycle tests may quote forbidden examples.
    paths = list(ops.glob("*.sh")) + list((ops / "bin").glob("*"))
    for path in sorted(paths):
        if path.is_file() and (path.suffix in ("", ".sh", ".ps1", ".cmd", ".bat")):
            for problem in independent_artwork(read(path), shell=True):
                problems.append(f"[{path.relative_to(root)}] {problem}")
    cli = Path(root) / "src/main/scala/nebflow/cli"
    for path in sorted(cli.glob("*.scala")):
        for problem in independent_artwork(read(path)):
            problems.append(f"[{path.relative_to(root)}] {problem}")
    splash = Path(root) / "src/main/scala/nebflow/cli/splash.scala"
    if not splash.is_file() or "brand/stand-terminal-banner.properties" not in read(splash):
        problems.append("[splash.scala] renderer must load the shared banner resource")
    return problems


def svg_fill(path):
    m = re.search(r'fill="#([0-9A-Fa-f]{6})"', read(path))
    if not m:
        return None
    v = m.group(1)
    return "%d;%d;%d" % (int(v[0:2], 16), int(v[2:4], 16), int(v[4:6], 16))


def main():
    if not all(os.path.exists(p) for p in (
            INSTALL_SH, INSTALL_PS1, PREVIEW_SH, BANNER_RESOURCE, SPLASH)):
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

    # The resource must remain ASCII for Properties.load(InputStream).
    try:
        with open(BANNER_RESOURCE, "r", encoding="ascii") as f:
            runtime = parse_runtime(f.read())
        expected_keys = set(PAYLOAD_KEYS) | {"columns", "rows"}
        if set(runtime) != expected_keys:
            failures.append("[runtime] unexpected or missing property keys")
        if (runtime.get("columns"), runtime.get("rows")) != ("72", "9"):
            failures.append("[runtime] dimensions must be 72 columns by 9 rows")
        embedded["runtime"] = {key: runtime.get(key) for key in PAYLOAD_KEYS}
        drifted = [key for key in PAYLOAD_KEYS
                   if runtime.get(key) is None or any(
                       runtime.get(key) != embedded[label][key]
                       for label in ("install.sh", "install.ps1"))]
        if drifted:
            failures.append(f"[runtime] payload drift vs installers: {drifted}")
        else:
            print("[runtime] all 7 payloads byte-identical to both installers  [OK]")
    except (UnicodeError, ValueError, OSError) as e:
        failures.append(f"[runtime] invalid Properties resource: {e}")

    source_problems = check_runtime_sources()
    failures.extend(source_problems)
    if not source_problems:
        print("[sources] shell delegation and shared Scala artwork  [OK]")

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
    except subprocess.TimeoutExpired:
        proc = subprocess.CompletedProcess(
            args=[sys.executable, SAMPLER], returncode=1,
            stdout="", stderr="sampler exceeded the 120-second timeout")
    if proc is None:
        print("[regen] python unavailable - skipped")
    elif proc.returncode != 0:
        # rsvg-convert missing -> the sampler exits 2 with a clear message;
        # degrade to a skip (the same policy as the retired pixil leg), never
        # a false red.
        if "rsvg-convert not on PATH" in proc.stderr or "No module named 'PIL'" in proc.stderr:
            print("[regen] rsvg-convert or PIL not available - re-derivation skipped")
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
                print("[regen] all 7 payloads re-derived from kit SVGs == TRUTH == installers and runtime  [OK]")

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

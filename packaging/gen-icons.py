#!/usr/bin/env python3
# ----------------------------------------------------------------------------
# [FROZEN 2026-09-06] Desktop packaging sealed (script-install-release v2, batch 5)
# Zero code changes below this header - file kept byte-identical for the
# certificate-era restore. See:
#   - .nebflow/Spec/desktop-trust-and-icp-filing-plan.md  (trust chain / restore gates)
#   - .nebflow/Spec/script-install-release-v2.md section 2.7  (sealing scope)
# CI package jobs (release.yml / auto-release.yml) are disabled via `if: false`.
# Restore = certificates + CI notarytool/signtool integration, then unseal CI.
# G3 audit note: AutoStartService only branches on `.app/Contents` (jpackage
# bundle detection); under the script/jar layout it falls back to the classic
# `java -jar` template - no jpackage launcher hard-coupling, backward
# compatible, no runtime changes needed for the freeze.
# ----------------------------------------------------------------------------
"""Generate desktop app icons from the nebflow logo (pixel-art PNG).

Source is a 7x7-block pixel logo (224x224 => 32px blocks). We scale with
NEAREST at integer ratios only, padded to ~12.5% total margin, so every
block stays crisp at every icon size.

Outputs (under --out, default packaging/icons/):
  nebflow.icns   macOS app icon (full iconset: 16...1024@2x)
  nebflow.ico    Windows (16/24/32/48/64/128/256)
  nebflow.png    Linux (512, for jpackage --icon)

Usage: python3 packaging/gen-icons.py <source.png> [--out packaging/icons]
The source variant choice (bright/dark) is caller-side policy: bright is
the dock/taskbar default (matches the 2026-08-25 logo ruling).
"""
import sys
import math
import tempfile
from pathlib import Path
from PIL import Image

ICONSET_SIZES = [16, 32, 64, 128, 256, 512, 1024]  # + @2x pairs built below
ICO_SIZES = [16, 24, 32, 48, 64, 128, 256]
MARGIN_RATIO = 1 / 16  # each side => 12.5% total whitespace


def best_scale(src_size: int, art_size: int) -> float:
    return art_size / src_size


def render_padded(src: Image.Image, target: int) -> Image.Image:
    """Scale src to target*7/8 with integer-friendly nearest, center on
    a transparent target canvas."""
    art = int(round(target * (1 - 2 * MARGIN_RATIO)))
    # Integer ratios keep pixel art clean; tiny sizes may deviate slightly
    # (imperceptible below 48px).
    scaled = src.resize((art, art), Image.NEAREST)
    canvas = Image.new("RGBA", (target, target), (0, 0, 0, 0))
    offset = (target - art) // 2
    canvas.paste(scaled, (offset, offset))
    return canvas


def build_icns(src: Image.Image, out_dir: Path) -> Path:
    with tempfile.TemporaryDirectory() as td:
        iconset = Path(td) / "nebflow.iconset"
        iconset.mkdir()
        for s in ICONSET_SIZES:
            img = render_padded(src, s)
            img.save(iconset / f"icon_{s}x{s}.png")
            if s <= 512:  # @2x variants exist up to 512x512@2x (=1024)
                img2 = render_padded(src, s * 2)
                img2.save(iconset / f"icon_{s}x{s}@2x.png")
        icns = out_dir / "nebflow.icns"
        import subprocess
        subprocess.run(["iconutil", "-c", "icns", "-o", str(icns),
                        str(iconset)], check=True)
    return icns


def build_ico(src: Image.Image, out_dir: Path) -> Path:
    frames = [render_padded(src, s) for s in ICO_SIZES]
    ico = out_dir / "nebflow.ico"
    frames[-1].save(ico, append_images=frames[:-1], sizes=[(s, s) for s in ICO_SIZES])
    return ico


def build_png(src: Image.Image, out_dir: Path) -> Path:
    png = out_dir / "nebflow.png"
    render_padded(src, 512).save(png)
    return png


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        sys.exit(2)
    source = Path(sys.argv[1]).expanduser()
    out_dir = (Path(sys.argv[3]) if len(sys.argv) > 3 and sys.argv[2] == "--out"
               else Path("packaging/icons"))
    out_dir.mkdir(parents=True, exist_ok=True)
    src = Image.open(source).convert("RGBA")
    print(f"source: {source} {src.size}")
    print(f"icns:  {build_icns(src, out_dir)}")
    print(f"ico:   {build_ico(src, out_dir)}")
    print(f"png:   {build_png(src, out_dir)}")


if __name__ == "__main__":
    main()

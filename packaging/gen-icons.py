#!/usr/bin/env python3
# ----------------------------------------------------------------------------
# [UNSEALED 2026-09-20] Artwork/margin edit of this generator only.
# The 2026-09-06 seal (script-install-release v2, batch 5) was lifted for the
# favicon/logo batch (author ruling 1-A + unseal, chain-logo-favicon) because
# the desktop/window icon tier had to be re-derived with the canonical margin
# policy (see MARGIN_RATIO below).
# Scope of this unseal = packaging/gen-icons.py ONLY. The rest of the packaging
# chain (build-dmg/msi/linux, app-version, jlink-modules, upload-release-assets)
# and the CI package jobs (release.yml / auto-release.yml `if: false`) stay
# sealed: restoring CI = certificates + notarytool/signtool gate, a separate
# decision. Original seal header, for provenance:
#   [FROZEN 2026-09-06] Desktop packaging sealed (script-install-release v2,
#   batch 5): zero code changes below this header, file kept byte-identical for
#   the certificate-era restore; CI package jobs disabled via `if: false`;
#   restore = certificates + CI notarytool/signtool integration, then unseal CI.
# ----------------------------------------------------------------------------
"""Generate desktop app icons from the nebflow logo (pixel-art PNG).

Source is the canonical pixel mark (224x224 canvas: a 4x3 grid of 48px cells
= 192x144 ink at origin (16,40), plus the CSS colour #07C160 accent). We scale
with NEAREST (point) resampling from that source, so no antialiasing is ever
introduced and every shape edge lands on a whole output pixel.

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
# Canonical margin policy (favicon/logo batch, author ruling 1-A, 2026-09-20):
# the mark already carries its own browse-safe border inside the 224 canvas
# (16 left/right + 40 top/bottom around the 192x144 ink), so this generator must
# add NO padding of its own - the window/desktop tier then lands on the same ink
# ratio as the tab tier and the website canonical (85.71% x 64.29%). The old
# value 1/16 stacked a second 12.5%/side margin on top of that border and left
# the tier at 75.00% x 56.25% (-10.71pt wide / -8.04pt high vs canonical).
MARGIN_RATIO = 0


def best_scale(src_size: int, art_size: int) -> float:
    return art_size / src_size


def render_padded(src: Image.Image, target: int) -> Image.Image:
    """Scale src to target*(1-2*MARGIN_RATIO) with NEAREST, centered on a
    transparent target canvas. With the canonical policy (MARGIN_RATIO = 0)
    that is a straight source -> target derivation and offset 0, i.e. only the
    mark's own border remains."""
    art = int(round(target * (1 - 2 * MARGIN_RATIO)))
    # NEAREST keeps the pixel art clean: colours stay pure (no grey seams) and
    # each cell edge falls on a whole output pixel. A non-integer target/source
    # ratio distributes one extra pixel across the 4x3 cells (proportional
    # rounding, not a rendering defect).
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

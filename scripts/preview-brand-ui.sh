#!/usr/bin/env bash
# preview-brand-ui.sh — render the brand-UI block of release/install.sh
# (banner + download progress) across the full degrade matrix into ONE
# self-contained SVG for author review.
#
#   banner : 4 color levels (truecolor / 256 / 8-color / mono) x 2 themes
#            (dark / light) = 8 panels
#   progress: 3 frames (mid-download / unknown-length spinner / complete)
#
# The script sources NOTHING from the network: it extracts the
# >>> BRAND-UI-BEGIN ... <<< BRAND-UI-END <<< block out of install.sh,
# force-renders it via NEBFLOW_UI_LEVEL / NEBFLOW_BANNER_THEME overrides,
# and maps the captured ANSI stream onto SVG (dev-time python, never a
# runtime dependency of the installer).
#
# Usage: scripts/preview-brand-ui.sh [output.svg]   (default /tmp/nb-brand-preview.svg)
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="${1:-/tmp/nb-brand-preview.svg}"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

BLOCK="$TMP/brand-ui.sh"
sed -n '/>>> BRAND-UI-BEGIN/,/<<< BRAND-UI-END/p' "$ROOT/release/install.sh" > "$BLOCK"
grep -q "BRAND-UI-BEGIN" "$BLOCK" || { echo "preview: brand-ui markers not found in install.sh" >&2; exit 1; }

# --- banner captures: level x theme -----------------------------------------
LEVELS=(3 2 1 0)
THEMES=(dark light)
for lvl in "${LEVELS[@]}"; do
  for theme in "${THEMES[@]}"; do
    NEBFLOW_UI_LEVEL=$lvl NEBFLOW_BANNER_THEME=$theme \
      bash -c 'source "$1"; LOWER_NAME=nebflow; VERSION=2.0.0; CHANNEL=stable; print_banner' \
      _ "$BLOCK" > "$TMP/banner-$lvl-$theme.txt"
  done
done

# --- progress frames ----------------------------------------------------------
NEBFLOW_UI_LEVEL=3 NEBFLOW_BANNER_THEME=dark COLUMNS=90 \
  bash -c '
    source "$1"; ui_detect
    _progress_line 13000000 26100000 "[2/6] encoder_model_quantized.onnx" 3300000 0; echo
    _progress_line 47185920 -1 "nebflow-assembly-1.4.1.jar" 5200000 2; echo
    _progress_line 26100000 26100000 "[2/6] encoder_model_quantized.onnx" -1 0; echo
  ' _ "$BLOCK" > "$TMP/progress.txt"

NEBFLOW_UI_LEVEL=0 NEBFLOW_BANNER_THEME=dark COLUMNS=80 LC_ALL=C \
  bash -c '
    source "$1"; ui_detect
    _progress_line 13000000 26100000 "[2/6] encoder_model_quantized.onnx" 3300000 0; echo
  ' _ "$BLOCK" > "$TMP/progress-mono.txt"

# --- ANSI -> SVG ---------------------------------------------------------------
TMPDIR_ENV="$TMP" OUT_ENV="$OUT" python3 - <<'PYEOF'
import os, re, html

TMP = os.environ["TMPDIR_ENV"]; OUT = os.environ["OUT_ENV"]

XTERM16 = ["#000000","#cd0000","#00cd00","#cdcd00","#1e90ff","#cd00cd","#00cdcd","#e5e5e5",
           "#7f7f7f","#ff0000","#00ff00","#ffff00","#5c5cff","#ff00ff","#00ffff","#ffffff"]
def xterm256(n):
    if n < 16: return XTERM16[n]
    if n < 232:
        n -= 16; vals = [0,95,135,175,215,255]
        r, g, b = vals[n//36], vals[(n//6)%6], vals[n%6]
        return f"#{r:02x}{g:02x}{b:02x}"
    v = 8 + (n-232)*10
    return f"#{v:02x}{v:02x}{v:02x}"

SGR_RE = re.compile(r"\033\[([0-9;]*)m")

def parse(text):
    """Return list of lines; each line is a list of (char, fg, bg, bold, dim)."""
    lines, cur = [], []
    fg = bg = None; bold = dim = False
    i = 0
    while i < len(text):
        ch = text[i]
        if ch == "\033":
            m = SGR_RE.match(text, i)
            if m:
                codes = [int(c) if c else 0 for c in m.group(1).split(";")]
                j = 0
                while j < len(codes):
                    c = codes[j]
                    if c == 0: fg = bg = None; bold = dim = False
                    elif c == 1: bold = True
                    elif c == 2: dim = True
                    elif 30 <= c <= 37: fg = XTERM16[c-30]
                    elif c == 38 and j+2 < len(codes) and codes[j+1] == 2:
                        fg = f"#{codes[j+2]:02x}{codes[j+3]:02x}{codes[j+4]:02x}"; j += 4
                    elif c == 38 and j+2 <= len(codes) and codes[j+1] == 5:
                        fg = xterm256(codes[j+2]); j += 2
                    elif 40 <= c <= 47: bg = XTERM16[c-40]
                    elif c == 48 and j+2 < len(codes) and codes[j+1] == 2:
                        bg = f"#{codes[j+2]:02x}{codes[j+3]:02x}{codes[j+4]:02x}"; j += 4
                    elif c == 48 and j+2 <= len(codes) and codes[j+1] == 5:
                        bg = xterm256(codes[j+2]); j += 2
                    j += 1
                i = m.end(); continue
            i += 1; continue
        if ch == "\n":
            lines.append(cur); cur = []; i += 1; continue
        if ch == "\r":
            i += 1; continue
        cur.append((ch, fg, bg, bold, dim)); i += 1
    if cur: lines.append(cur)
    return lines

CW, LH, FS, PAD = 7.8, 17, 13, 14
FONT = "Menlo, Consolas, monospace"

def panel_size(lines):
    w = max((len(l) for l in lines), default=0) * CW + 2*PAD
    h = len(lines) * LH + 2*PAD
    return w, h

def render_panel(lines, x, y, bgfill):
    out = [f'<rect x="{x}" y="{y}" rx="8" ry="8" fill="{bgfill}"']
    w, h = panel_size(lines)
    out[0] = f'<rect x="{x}" y="{y}" width="{w:.0f}" height="{h:.0f}" rx="8" ry="8" fill="{bgfill}"/>'
    for li, line in enumerate(lines):
        cy = y + PAD + li*LH
        # background runs
        cx = x + PAD
        run_color, run_start, run_len = None, 0, 0
        def flush():
            if run_color:
                out.append(f'<rect x="{run_start:.1f}" y="{cy+2:.1f}" width="{run_len*CW:.1f}" height="{LH-3}" fill="{run_color}"/>')
        for cell in line + [("\n", None, None, False, False)]:
            ch, fg, bg, bold, dim = cell
            if bg != run_color:
                flush(); run_color, run_start, run_len = bg, cx, 0
            run_len += 1; cx += CW
        flush()
        # text runs
        text, tfg, tbold, tdim, tx = "", None, False, False, x + PAD
        def ftush():
            nonlocal text, tfg, tbold, tdim, tx
            if text.strip("\n"):
                style = ' font-weight="bold"' if tbold else ""
                op = ' opacity="0.55"' if tdim else ""
                fill = tfg or ("#c9d1d9" if bgfill != "#f5f5f5" else "#24292f")
                out.append(f'<text x="{tx:.1f}" y="{cy+FS:.1f}" font-family="{FONT}" font-size="{FS}" fill="{fill}"{style}{op}>{html.escape(text)}</text>')
            tx += len(text)*CW; text = ""
        for ch, fg, bg2, bold, dim in line:
            if ch == " " and bg2:
                ftush(); tx += CW; continue
            if (fg, bold, dim) != (tfg, tbold, tdim):
                ftush(); tfg, tbold, tdim = fg, bold, dim
            text += ch
        ftush()
    return "\n".join(out), w, h

LEVEL_NAMES = {3: "truecolor (24bit)", 2: "xterm-256", 1: "8-color", 0: "mono (NO_COLOR/pipe)"}
THEME_BG = {"dark": "#14161a", "light": "#f5f5f5"}
TITLE_C = "#8b949e"

panels = {}
maxw, toth = 0, 0
for lvl in (3, 2, 1, 0):
    for theme in ("dark", "light"):
        with open(f"{TMP}/banner-{lvl}-{theme}.txt", encoding="utf-8") as f:
            lines = parse(f.read())
        panels[(lvl, theme)] = lines
        w, h = panel_size(lines)
        maxw = max(maxw, w); toth = max(toth, h)

with open(f"{TMP}/progress.txt", encoding="utf-8") as f:
    prog_lines = parse(f.read())
with open(f"{TMP}/progress-mono.txt", encoding="utf-8") as f:
    prog_mono = parse(f.read())

LABEL_H = 22
col_x = [30, 30 + maxw + 24]
svg = []
y = 30
svg.append(f'<text x="30" y="{y}" font-family="{FONT}" font-size="16" fill="{TITLE_C}">Nebflow installer — brand UI degrade matrix (batch 4 redesign)</text>')
y += 20
for lvl in (3, 2, 1, 0):
    row_h = 0
    for ci, theme in enumerate(("dark", "light")):
        x = col_x[ci]
        svg.append(f'<text x="{x}" y="{y + 12}" font-family="{FONT}" font-size="12" fill="{TITLE_C}">{LEVEL_NAMES[lvl]} · {theme}</text>')
        body, w, h = render_panel(panels[(lvl, theme)], x, y + LABEL_H, THEME_BG[theme])
        svg.append(body)
        row_h = max(row_h, h + LABEL_H)
    y += row_h + 16

# progress section
y += 6
svg.append(f'<text x="30" y="{y + 12}" font-family="{FONT}" font-size="12" fill="{TITLE_C}">download progress — truecolor dark: 49% mid-download / unknown-length spinner / complete</text>')
body, w, h = render_panel(prog_lines, 30, y + LABEL_H, THEME_BG["dark"])
svg.append(body)
y += h + LABEL_H + 12
svg.append(f'<text x="30" y="{y + 12}" font-family="{FONT}" font-size="12" fill="{TITLE_C}">download progress — mono degrade (LC_ALL=C / pipe)</text>')
body, w, h = render_panel(prog_mono, 30, y + LABEL_H, THEME_BG["dark"])
svg.append(body)
y += h + LABEL_H + 20

W = int(col_x[1] + maxw + 30)
H = int(y)
doc = (f'<svg xmlns="http://www.w3.org/2000/svg" width="{W}" height="{H}" '
       f'viewBox="0 0 {W} {H}"><rect width="{W}" height="{H}" fill="#0d1117"/>'
       + "\n".join(svg) + "</svg>")
with open(OUT, "w", encoding="utf-8") as f:
    f.write(doc)
print(f"preview written: {OUT} ({W}x{H})")
PYEOF

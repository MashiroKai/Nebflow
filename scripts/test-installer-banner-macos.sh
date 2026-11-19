#!/usr/bin/env bash
# test-installer-banner-macos.sh -- drive the REAL terminal render of the
# installer brand banner on macOS and capture screenshot evidence.
#
# Two verification vehicles (dual-path batch 2026-10-22):
#   iterm2 : the IMAGE path - iTerm2 renders the OSC 1337 inline PNG
#            ($BannerPngDark/Light via TERM_PROGRAM=iTerm.app auto-detect,
#            or NEBFLOW_BANNER_IMAGE=osc force).
#   terminal: the ART fallback path - Terminal.app has no image protocol,
#            so the U+2580/84/88 half-block art renders (same bytes old
#            conhost gets).
#
# Usage:
#   scripts/test-installer-banner-macos.sh <mode> <out.png>
#     mode = iterm2-dark | iterm2-light | terminal-dark | terminal-light
#
# Requires: iTerm2 (for iterm2 modes), Terminal.app, screencapture access
# (Screen Recording permission). Dev-time harness only - the installers
# never invoke this.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
MODE="${1:?usage: test-installer-banner-macos.sh <mode> <out.png>}"
OUT="${2:?usage: test-installer-banner-macos.sh <mode> <out.png>}"
case "$MODE" in
    iterm2-dark|terminal-dark) THEME=dark ;;
    iterm2-light|terminal-light) THEME=light ;;
    *) echo "unknown mode: $MODE" >&2; exit 2 ;;
esac
[ -f "$ROOT/release/install.sh" ] || { echo "run from the repo (release/install.sh missing)" >&2; exit 2; }

# The banner command: standalone render (zero network/side effects), then a
# long sleep so the window stays put for the capture.
CMD="NEBFLOW_BANNER_THEME=$THEME bash '$ROOT/release/install.sh' --banner-only && sleep 120"

close_window() {
    case "$MODE" in
        iterm2*) osascript -e 'tell application "iTerm" to close current window' >/dev/null 2>&1 || true ;;
        terminal*) osascript -e 'tell application "Terminal" to close front window' >/dev/null 2>&1 || true ;;
    esac
}
trap close_window EXIT

case "$MODE" in
    iterm2*)
        osascript <<EOF
tell application "iTerm"
    activate
    create window with default profile
    tell current window
        set bounds to {60, 60, 760, 380}
    end tell
    tell current session of current window
        write text "$CMD"
    end tell
end tell
EOF
        if [ "$THEME" = "light" ]; then
            # one-off light background for the black-ink variant shot
            osascript -e 'tell application "iTerm" to tell current session of current window to set background color to {65535, 65535, 65535}' || \
                echo "warn: could not set light background (session property may be read-only)" >&2
        fi
        osascript -e 'tell application "iTerm" to tell current session of current window to write text ""' >/dev/null
        ;;
    terminal*)
        osascript <<EOF
tell application "Terminal"
    activate
    do script "$CMD"
    tell front window to set bounds to {60, 60, 760, 380}
EOF
        if [ "$THEME" = "light" ]; then
            osascript -e 'tell application "Terminal" to tell front window to set background color to "white"' || true
        fi
        ;;
esac

sleep 3   # let the terminal render + window come forward
mkdir -p "$(dirname "$OUT")"
screencapture -x "$OUT"
echo "captured $OUT"

#!/usr/bin/env python3
"""Quality gate for the STAND Logto email templates (dual-mode zero-mixing).

Static assertions, stdlib only. The defect class this gate exists for: the
prefers-color-scheme branches paired to the WRONG palette/lockup (author
symptom 2026-10-20: dark mode email rendered light, light mode rendered dark).

Run: python3 test_email_templates.py [--net]
  --net also re-verifies the served lockup PNG ink colors (light=black ink,
  dark=white ink) over HTTP.
"""
import argparse
import json
import re
import struct
import sys
import urllib.request
import zlib
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
TPL_DIR = ROOT / "src/main/resources/web/brand/stand/email/templates"
USAGES = ["SignIn", "Register", "ForgotPassword", "Generic"]

FAILS = []
CHECKS = [0]


def check(name: str, ok: bool, detail: str = "") -> None:
    CHECKS[0] += 1
    if not ok:
        FAILS.append(f"{name}{': ' + detail if detail else ''}")
        print(f"  FAIL {name} {detail}")


def load_spec() -> dict:
    return json.loads((HERE / "template_spec.json").read_text())


def dark_block(html: str) -> str:
    m = re.search(r"@media \(prefers-color-scheme: dark\) \{(.*?)\n  \}", html, re.S)
    assert m, "dark media block missing"
    return m.group(1)


def body_without_dark_block(html: str) -> str:
    return re.sub(r"@media \(prefers-color-scheme: dark\) \{.*?\n  \}", "", html, flags=re.S)


def css_rules(block: str) -> dict:
    rules = {}
    for sel, val in re.findall(r"([^\{\}]+)\{([^\}]*)\}", block):
        rules.setdefault(" ".join(sel.split()), "").join("")
        rules[" ".join(sel.split())] = val.strip()
    return rules


def png_ink(path_or_bytes) -> str:
    d = path_or_bytes if isinstance(path_or_bytes, bytes) else Path(path_or_bytes).read_bytes()
    pos, idat = 8, b""
    w = h = ct = None
    while pos < len(d):
        ln = struct.unpack(">I", d[pos:pos + 4])[0]
        typ = d[pos + 4:pos + 8]
        if typ == b"IHDR":
            w, h, _, ct = struct.unpack(">IIBB", d[pos + 8:pos + 18])
        elif typ == b"IDAT":
            idat += d[pos + 8:pos + 8 + ln]
        pos += 12 + ln
    raw = zlib.decompress(idat)
    ch = {0: 1, 2: 3, 6: 4}.get(ct, 4)
    stride = w * ch
    out = bytearray()
    prev = bytearray(stride)
    p = 0
    for _ in range(h):
        f = raw[p]; p += 1
        line = bytearray(raw[p:p + stride]); p += stride
        for x in range(stride):
            a = line[x - ch] if x >= ch else 0
            b = prev[x]
            c = prev[x - ch] if x >= ch else 0
            if f == 1:
                line[x] = (line[x] + a) & 0xFF
            elif f == 2:
                line[x] = (line[x] + b) & 0xFF
            elif f == 3:
                line[x] = (line[x] + (a + b) // 2) & 0xFF
            elif f == 4:
                pp = a + b - c
                pa, pb, pc = abs(pp - a), abs(pp - b), abs(pp - c)
                pr = a if (pa <= pb and pa <= pc) else (b if pb <= pc else c)
                line[x] = (line[x] + pr) & 0xFF
        out += line
        prev = line
    n = lit = 0
    for i in range(0, len(out), ch):
        if (out[i + 3] if ch == 4 else 255) > 128:
            n += 1
            if 0.2126 * out[i] + 0.7152 * out[i + 1] + 0.0722 * out[i + 2] > 128:
                lit += 1
    return "white" if lit > n * 0.9 else ("black" if lit < n * 0.1 else "mixed")


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--net", action="store_true")
    a = ap.parse_args()
    spec = load_spec()
    pal = spec["palette"]
    light_hexes = {v for k, v in pal["light"].items() if k != "lockupInk"}
    dark_hexes = {v for k, v in pal["dark"].items() if k != "lockupInk"}

    print("[1/6] spec regeneration drift")
    import subprocess
    r = subprocess.run([sys.executable, str(HERE / "build_templates.py"), "--check"],
                       capture_output=True, text=True)
    check("build_templates --check", r.returncode == 0, r.stdout + r.stderr)

    print("[2/6] per-template branch pairing")
    for usage in USAGES:
        html = (TPL_DIR / f"{usage}.html").read_text()
        dk, body = dark_block(html), body_without_dark_block(html)
        rules = css_rules(dk)
        # dark branch: every pinned surface/text carries the DARK hex and ONLY dark hexes
        for cls, key in [("body, table.nf-bg", "pageBg"), ("table.nf-card", "cardBg"),
                         (".nf-title", "titleInk"), (".nf-body", "bodyInk"),
                         (".nf-meta", "metaInk"), (".nf-code", "codeInk"),
                         (".nf-codepanel", "codepanelBg"), (".nf-divider", "dividerBg")]:
            want = pal["dark"][key]
            got = rules.get(cls, "")
            check(f"{usage}: dark {cls} == {want}", f"background-color:{want}" in got or f"color:{want}" in got, f"got={got!r}")
        for sel in rules:
            for hx in re.findall(r"#[0-9a-fA-F]{6}", rules[sel]):
                check(f"{usage}: dark block hex {sel} {hx} is dark-face", hx in dark_hexes, f"light hex in dark branch = mixing")
        check(f"{usage}: dark body text pin", "color:#f2f2f4" in rules.get("body", ""), rules.get("body", ""))
        # lockup pairing in dark branch
        check(f"{usage}: dark hides lockup-light", "display:none" in rules.get(".nf-lockup-light", ""))
        check(f"{usage}: dark shows lockup-dark", "display:inline-block" in rules.get(".nf-lockup-dark", ""))
        # light face (outside dark block): only light hexes
        for hx in re.findall(r"#[0-9a-fA-F]{6}", body):
            check(f"{usage}: light-face hex {hx} is light-face", hx in light_hexes, f"dark hex outside dark branch = mixing")
        # lockup pairing in light face: light shown by default, dark hidden
        img_l = re.search(r'<img src="[^"]*lockup-light\.png"[^>]*class="nf-lockup-light"[^>]*style="([^"]*)"', html)
        img_d = re.search(r'<img src="[^"]*lockup-dark\.png"[^>]*class="nf-lockup-dark"[^>]*style="([^"]*)"', html)
        check(f"{usage}: lockup-light default visible", img_l and "display:inline-block" in img_l.group(1))
        check(f"{usage}: lockup-dark default hidden", img_d and "display:none" in img_d.group(1))
        check(f"{usage}: lockup URLs correct",
              bool(re.search(r'src="https://mystand\.space/brand/stand/email/lockup-light\.png"', html)) and
              bool(re.search(r'src="https://mystand\.space/brand/stand/email/lockup-dark\.png"', html)))
        # hardening pins
        check(f"{usage}: meta color-scheme", 'name="color-scheme" content="light dark"' in html)
        check(f"{usage}: meta supported-color-schemes", 'name="supported-color-schemes" content="light dark"' in html)
        for surf in ['class="nf-bg"', 'class="nf-card"', 'class="nf-codepanel"']:
            row = re.search(r'<table[^>]*' + re.escape(surf) + r'[^>]*>', html)
            check(f"{usage}: {surf} has bgcolor attr", row and "bgcolor=" in row.group(0))
            check(f"{usage}: {surf} has inline bg", row and "background-color:" in row.group(0))
        check(f"{usage}: body inline bg pin", 'background-color:#f5f6f7;' in html)
        check(f"{usage}: body inline text pin", '<body style="margin:0;padding:0;background-color:#f5f6f7;color:#141416;">' in html)
        # code placeholder
        # code placeholder: exactly one in the visible code span + one in the preheader
        span = re.search(r'class="nf-code"[^>]*>([^<]*)</span>', html)
        pre = re.search(r'overflow:hidden;opacity:0;">([^<]*)</div>', html)
        check(f"{usage}: code span has exactly one {{{{code}}}}", span and span.group(1).count("{{code}}") == 1)
        check(f"{usage}: preheader has exactly one {{{{code}}}}", pre and pre.group(1).count("{{code}}") == 1)

    print("[3/6] set completeness + subjects")
    for t in spec["templates"]:
        html = (TPL_DIR / f"{t['usageType']}.html").read_text()
        check(f"{t['usageType']}: title", f">{t['title']}<" in html)
        check(f"{t['usageType']}: lead", t["lead"] in html)
        check(f"{t['usageType']}: preheader", t["preheader"] in html)
        check(f"{t['usageType']}: title tag", f"<title>{t['titleTag']}</title>" in html)

    print("[4/6] chrome invariance across the four templates")
    def chrome(h: str) -> str:
        for t in spec["templates"]:
            for v in {t["title"], t["lead"], t["preheader"], t["titleTag"]}:
                h = h.replace(v, "§")
        return h
    chs = {u: chrome((TPL_DIR / f"{u}.html").read_text()) for u in USAGES}
    for u in USAGES[1:]:
        check(f"chrome({u}) == chrome(SignIn)", chs[u] == chs["SignIn"], "unexpected template divergence")

    print("[5/6] light↔dark hex disjointness")
    check("light/dark hex sets disjoint", not (light_hexes & dark_hexes), str(light_hexes & dark_hexes))

    print("[6/6] lockup ink colors" + (" (--net)" if a.net else " (cached)"))
    cache = HERE / ".lockup-cache"
    cache.mkdir(exist_ok=True)
    for face, want in [("light", "black"), ("dark", "white")]:
        f = cache / f"lockup-{face}.png"
        if a.net or not f.exists():
            data = urllib.request.urlopen(
                f"https://mystand.space/brand/stand/email/lockup-{face}.png", timeout=20).read()
            f.write_bytes(data)
        got = png_ink(f)
        check(f"lockup-{face}.png ink == {want}", got == want, f"got={got}")

    print(f"\n{CHECKS[0]} checks, {len(FAILS)} failed")
    if FAILS:
        print("\n".join("FAIL: " + f for f in FAILS))
        return 1
    print("GREEN: dual-mode pairing correct, zero mixing, pins in place")
    return 0


if __name__ == "__main__":
    sys.exit(main())

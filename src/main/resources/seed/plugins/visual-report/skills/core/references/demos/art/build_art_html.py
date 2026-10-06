#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""p5.js 生成艺术 · 自包含 HTML 构建器（纯标准库，无需联网）

把本地化的 p5.min.js（demos/art/lib/）与用户 sketch 依次内联进单文件 HTML：
产物双击即开、离线可用——p5 通路不硬依赖网络（CDN 不可达时的「内联库代码」降级）。

用法:
  <venv>/python.exe build_art_html.py --sketch gen_art.js --out <绝对路径.html> --title 标题
"""
import argparse
from pathlib import Path

HERE = Path(__file__).resolve().parent
LIB = HERE / "lib" / "p5.min.js"

TEMPLATE = """<!doctype html>
<html lang="zh">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>@@TITLE@@</title>
<style>
  :root { --color-bg:#f2f0ea; --color-text:#1b1e26; --color-muted:#8a8f98; }
  @media (prefers-color-scheme: dark) {
    :root { --color-bg:#14161c; --color-text:#e8eaed; --color-muted:#8a8f98; }
  }
  html,body{margin:0;min-height:100%;background:var(--color-bg);color:var(--color-text);
            font-family:system-ui,'Segoe UI','Microsoft YaHei',sans-serif}
  #wrap{display:flex;flex-direction:column;align-items:center;gap:10px;padding:22px 0 30px}
  .badge{font-size:13px;color:var(--color-muted);user-select:none}
  canvas{box-shadow:0 10px 34px rgba(27,30,38,.16);border-radius:2px}
</style>
</head>
<body>
<div id="wrap">
  <div class="badge">@@TITLE@@ · p5.js 自包含内联（离线可用）</div>
</div>
<script>
@@P5@@
</script>
<script>
@@SKETCH@@
</script>
</body>
</html>
"""


def main() -> None:
    ap = argparse.ArgumentParser(description="p5.js 自包含 HTML 构建器")
    ap.add_argument("--sketch", required=True, help="sketch 源码（gen_art.js）")
    ap.add_argument("--out", required=True, help="输出 HTML 绝对路径")
    ap.add_argument("--title", default="p5.js 生成艺术", help="页面标题/角标")
    a = ap.parse_args()

    lib = LIB.read_text(encoding="utf-8")
    if "</script" in lib.lower():
        raise SystemExit("p5.min.js 含 </script>，直接内联会截断 HTML——先做转义")
    sketch = Path(a.sketch).read_text(encoding="utf-8")

    html = (TEMPLATE
            .replace("@@TITLE@@", a.title)
            .replace("@@P5@@", lib)
            .replace("@@SKETCH@@", sketch))
    out = Path(a.out)
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(html, encoding="utf-8")
    print(f"[build_art_html] {out} ({out.stat().st_size/1024:.0f} KB, p5 内联 {len(lib)/1024:.0f} KB)")


if __name__ == "__main__":
    main()

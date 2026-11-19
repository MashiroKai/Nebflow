#!/usr/bin/env python3
"""motion-toolkit · three.js 自包含 HTML 构建器（降级链内的「three 可用」通路）

把 npm 本地化的 three 构建产物整体内联进单个 HTML（importmap + data: URL），
产物双击即可交互、不依赖网络；场景代码保持 `import * as THREE from 'three'` 不变。

three >= r167 的 build/three.module.js 会相对导入 ./three.core.js —— data: URL 里
相对导入无法解析，本脚本把相对导入递归改写为指向内联源码的 data: URL（见 _inline）。

用法（viz-venv python，纯标准库，无需联网）:
  <venv>/python.exe build_three_html.py --scene scene_default.js --out out.html --title 标题
  <venv>/python.exe build_three_html.py --scene my_scene.js --out out.html --cdn   # 网络可靠时改 CDN 引用
"""
import argparse
import base64
import json
import re
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
# 命中 `from './x.js'` / `import './x.js'` 形式的相对导入（非 min 源码带空格与引号两种）
REL_IMPORT = re.compile(r"""(from\s*|import\s*|import\()(["'])(\.{1,2}/[^"')]+)\2""")

TEMPLATE = """<!doctype html>
<html lang="zh">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>@@TITLE@@</title>
<style>
  :root { --color-bg:#ffffff; --color-card:#ffffff; --color-text:#1b1e26;
          --color-muted:#8a8f98; --color-accent:#5e6ad2; --color-border:#e8eaed; }
  @media (prefers-color-scheme: dark) {
    :root { --color-bg:#14161c; --color-card:#1b1e26; --color-text:#e8eaed;
            --color-muted:#8a8f98; --color-accent:#a0a8e8; --color-border:#2a2e38; }
  }
  html,body{margin:0;height:100%;overflow:hidden;background:var(--color-bg);
            color:var(--color-text);font-family:system-ui,'Segoe UI','Microsoft YaHei',sans-serif}
  #stage{position:fixed;inset:0}
  #stage canvas{display:block}
  .badge{position:fixed;left:16px;top:14px;font-size:13px;color:var(--color-muted);
         pointer-events:none;user-select:none}
  .err{position:fixed;left:16px;bottom:14px;right:16px;font-size:12px;color:#e5484d;
       white-space:pre-wrap;pointer-events:none}
</style>
</head>
<body>
<div id="stage"></div>
<div class="badge">@@TITLE@@ · motion-toolkit three.js 自包含模板（拖拽旋转）</div>
<div class="err" id="err"></div>
<script>
window.addEventListener('error', function (e) {
  document.getElementById('err').textContent = 'Error: ' + e.message;
});
</script>
<script type="importmap">{"imports":@@IMPORTMAP@@}</script>
<script type="module">
@@SCENE@@
</script>
</body>
</html>
"""


def _inline(src: Path, seen: frozenset) -> str:
    """读一个 ESM 源文件，把其相对导入递归换成 data: URL，返回 data: URL 字符串。"""
    real = src.resolve()
    if real in seen:
        sys.exit(f"循环导入: {real}")
    text = src.read_text(encoding="utf-8")

    def repl(m: re.Match) -> str:
        target = (src.parent / m.group(3)).resolve()
        if not target.exists():
            sys.exit(f"内联失败: {src} 导入的 {m.group(3)} 不存在")
        url = _inline(target, seen | {real})
        return f"{m.group(1)}{m.group(2)}{url}{m.group(2)}"

    patched = REL_IMPORT.sub(repl, text)
    b64 = base64.b64encode(patched.encode("utf-8")).decode("ascii")
    return "data:text/javascript;base64," + b64


def main() -> None:
    ap = argparse.ArgumentParser(description="three.js 自包含 HTML 构建器")
    ap.add_argument("--scene", default=str(HERE / "scene_default.js"), help="场景源码（ESM）")
    ap.add_argument("--out", required=True, help="输出 HTML 路径")
    ap.add_argument("--title", default="three.js 动效", help="页面标题/角标")
    ap.add_argument("--cdn", action="store_true", help="不内联，importmap 指 jsdelivr CDN（需联网）")
    a = ap.parse_args()

    nm = HERE / "node_modules" / "three"
    scene_src = Path(a.scene).read_text(encoding="utf-8")
    if a.cdn:
        ver = json.loads((nm / "package.json").read_text(encoding="utf-8"))["version"]
        three_map = f"https://cdn.jsdelivr.net/npm/three@{ver}/build/three.module.js"
    else:
        entry = nm / "build" / "three.module.js"
        if not entry.exists():
            sys.exit("未找到 three 构建产物：先在本目录跑 setup.cmd（npmmirror 装 three）")
        three_map = _inline(entry, frozenset())
        print(f"[build_three_html] three 内联完成（含相对导入改写）")

    html = (
        TEMPLATE.replace("@@TITLE@@", a.title)
        .replace("@@IMPORTMAP@@", json.dumps({"three": three_map}))
        .replace("@@SCENE@@", scene_src)
    )
    out = Path(a.out)
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(html, encoding="utf-8")
    print(f"[build_three_html] {out} ({out.stat().st_size/1024:.0f} KB)")


if __name__ == "__main__":
    main()

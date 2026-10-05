# -*- coding: utf-8 -*-
"""education-motion demo · RC 充电电路原理图（schemdraw 0.23 真实渲染）

同一主题三形态对照（静态分步图 / three.js 交互 / Remotion 视频）共用的电路锚点。
教学示例值（显著声明）：R = 1 kΩ，C = 1 µF，U = 1 V ⇒ τ = RC = 1 ms。

输出（core §2 白名单 ~/.nebflow/plots/**）：
  20261004_edu_rc_charge_schematic.svg       —— 亮色主题（页面嵌入，注入暗色 @media 块）
  20261004_edu_rc_charge_schematic_dark.png  —— 暗色透明底（Remotion 视频内嵌用）
"""
import os
from pathlib import Path

# 套件全局 matplotlibrc（YaHei 中文字体）接线，必须在 import matplotlib 前（2026-10-04 实测节点 shell 该变量为空）
os.environ.setdefault("MPLCONFIGDIR", r"C:\Users\Kai\.nebflow\tools\mplconfig")

OUT_DIR = Path(r"C:\Users\Kai\.nebflow\plots\education-motion")

DARK_RULES = """  text { fill: #e8eaed !important; }
  path, line, circle, ellipse, polygon, rect { stroke: #e8eaed !important; fill: none !important; }"""


def build_svg():
    import matplotlib

    matplotlib.use("Agg")
    import matplotlib.pyplot as plt
    import schemdraw
    import schemdraw.elements as elm

    plt.rcParams["svg.fonttype"] = "none"
    plt.rcParams["text.color"] = "#1b1e26"
    plt.rcParams["font.sans-serif"] = ["Microsoft YaHei", "SimHei", "DejaVu Sans"]
    plt.rcParams["font.family"] = "sans-serif"

    schemdraw.config(font="Microsoft YaHei", fontsize=13)
    d = schemdraw.Drawing(show=False)
    start = d.here
    d += elm.Battery().up().label("U = 1 V")
    d += elm.Line().right(1.6)
    d += elm.Switch().right().label("开关（t = 0 闭合）")
    d += elm.Line().right(1.2)
    d += elm.Resistor().right().label("R = 1 kΩ")
    d += elm.Line().down()
    d += elm.Capacitor().down().label("C = 1 µF")
    d += elm.Line().left().tox(start[0])
    svg_path = OUT_DIR / "20261004_edu_rc_charge_schematic.svg"
    d.save(str(svg_path))
    print(f"[rc_schematic] svg = {svg_path} ({svg_path.stat().st_size} B)")
    return svg_path


def inject_dark(svg_path: Path) -> None:
    style = (
        '<style type="text/css">\n@media (prefers-color-scheme: dark) {\n'
        + DARK_RULES
        + "\n}\n</style>"
    )
    txt = svg_path.read_text(encoding="utf-8")
    i = txt.index(">", txt.index("<svg")) + 1
    svg_path.write_text(txt[:i] + style + txt[i:], encoding="utf-8")
    print(f"[rc_schematic] dark @media 注入完成")


def build_dark_png():
    """Remotion 视频内嵌：暗色笔画 + 透明底 PNG（schemdraw element/label 统一 color）。"""
    import schemdraw
    import schemdraw.elements as elm

    schemdraw.config(font="Microsoft YaHei", fontsize=13)
    color = "#e8eaed"
    d = schemdraw.Drawing(show=False)
    start = d.here
    d += elm.Battery().up().color(color).label("U = 1 V", color=color)
    d += elm.Line().right(1.6).color(color)
    d += elm.Switch().right().color(color).label("开关（t = 0 闭合）", color=color)
    d += elm.Line().right(1.2).color(color)
    d += elm.Resistor().right().color(color).label("R = 1 kΩ", color=color)
    d += elm.Line().down().color(color)
    d += elm.Capacitor().down().color(color).label("C = 1 µF", color=color)
    d += elm.Line().left().tox(start[0]).color(color)
    png_path = OUT_DIR / "20261004_edu_rc_charge_schematic_dark.png"
    d.save(str(png_path), transparent=True)
    print(f"[rc_schematic] dark png = {png_path} ({png_path.stat().st_size} B)")
    return png_path


if __name__ == "__main__":
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    svg = build_svg()
    inject_dark(svg)
    build_dark_png()

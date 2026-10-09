# -*- coding: utf-8 -*-
"""education-motion demo · 形态① 静态分步图（viz-venv matplotlib 3.11.2 真实渲染）

RC 电路充电，t = 0 / τ / 2τ / 3τ / 4τ / 5τ 逐步充电压阶跃分步，①–⑥ 编号与
对照页（demos/education-motion.md）分步说明一一对应。
教学示例值（显著声明）：R = 1 kΩ，C = 1 µF，U = 1 V ⇒ τ = RC = 1 ms。
一切数值由 u_C(t) = U(1 − e^(−t/τ)) 实时计算（core §1.2 数值逐字，不硬编码）。

输出（core §2 白名单 ~/.nebflow/plots/**）：
  20261004_edu_rc_charge_steps_static.svg —— 页面嵌入主图（注入暗色 @media 块）
  20261004_edu_rc_charge_steps_static.png —— 备用位图
"""
import math
import os
from pathlib import Path

os.environ.setdefault("MPLCONFIGDIR", r"C:\Users\Kai\.nebflow\tools\mplconfig")

OUT_DIR = Path(r"C:\Users\Kai\.nebflow\plots\education-motion")

# 教学示例值
R, C, U = 1e3, 1e-6, 1.0
TAU = R * C * 1e3  # → 1.0 ms（以 ms 计）

STEP_META = [
    ("①", 0, "t = 0：开关闭合"),
    ("②", 1, "t = 1τ = 1 ms"),
    ("③", 2, "t = 2τ = 2 ms"),
    ("④", 3, "t = 3τ = 3 ms"),
    ("⑤", 4, "t = 4τ = 4 ms"),
    ("⑥", 5, "t = 5τ = 5 ms"),
]


def uc(t_ms: float) -> float:
    """u_C(t) = U(1 − e^(−t/τ))，t 以 ms 计。"""
    return U * (1.0 - math.exp(-t_ms / TAU))


def main() -> None:
    import matplotlib

    matplotlib.use("Agg")
    import matplotlib.pyplot as plt
    import numpy as np

    plt.rcParams["svg.fonttype"] = "none"
    plt.rcParams["text.color"] = "#1b1e26"
    plt.rcParams["axes.edgecolor"] = "#8a8f98"
    plt.rcParams["font.sans-serif"] = ["Microsoft YaHei", "SimHei", "DejaVu Sans"]
    plt.rcParams["font.family"] = "sans-serif"

    t_full = np.linspace(0.0, 6.0, 601)
    u_full = U * (1.0 - np.exp(-t_full / TAU))

    fig, axes = plt.subplots(2, 3, figsize=(13.8, 7.6), dpi=150, sharex=True, sharey=True)
    fig.suptitle(
        "RC 电路充电分步图　$u_C(t) = U\\,(1-e^{-t/\\tau})$",
        fontsize=17, y=0.965,
    )
    fig.text(
        0.5, 0.915,
        "R = 1 kΩ，C = 1 µF，τ = RC = 1 ms，U = 1 V（教学示例值）",
        ha="center", fontsize=12, color="#5e6ad2",
    )

    for idx, (badge, t_k, title) in enumerate(STEP_META):
        ax = axes[idx // 3][idx % 3]
        u_k = uc(t_k)
        i_k = (U - u_k) / R * 1e3  # mA

        # 完整响应（浅灰底稿，暗色主题换色走 #curve_full 规则）
        (ln_full,) = ax.plot(t_full, u_full, lw=1.3, color="#b6bcc9")
        ln_full.set_gid("curve_full")
        # 已充电段（强调色）
        t_part = t_full[t_full <= t_k] if t_k > 0 else t_full[:2]
        u_part = U * (1.0 - np.exp(-t_part / TAU))
        (ln_part,) = ax.plot(t_part, u_part, lw=2.6, color="#5e6ad2", solid_capstyle="round")
        ln_part.set_gid("curve_part")

        # 目标值虚线 U = 1 V
        (ln_u,) = ax.plot([0, 6], [U, U], ls="--", lw=1.0, color="#8a8f98")
        ln_u.set_gid("uline")
        ax.text(5.9, U + 0.03, "$u_{in}$ = 1 V", ha="right", fontsize=9, color="#8a8f98")

        # 历史步点（小空心）+ 当前步点（大实心）
        for j, (_, t_j, _) in enumerate(STEP_META[:idx]):
            ax.plot(t_j, uc(t_j), "o", ms=4.5, mfc="none", mec="#8a8f98", mew=1.1)
        (mk,) = ax.plot(t_k, u_k, "o", ms=8, color="#5e6ad2")
        mk.set_gid("uc_marker")

        ax.set_title(f"{badge} {title}", fontsize=12.5)
        ax.set_xlim(0, 6)
        ax.set_ylim(0, 1.14)
        ax.grid(True, color="#8a8f98", alpha=0.22, lw=0.7)
        for s in ax.spines.values():
            s.set_color("#8a8f98")
        ax.tick_params(colors="#8a8f98", labelcolor="#1b1e26")

        note = (
            f"$u_C$ = {u_k:.3f} V（{u_k * 100:.1f}%）\n"
            f"$i$ = U/R = {i_k:.3f} mA" if t_k == 0 else
            f"$u_C$ = {u_k:.3f} V（{u_k * 100:.1f}%）\n$i$ = (U−$u_C$)/R = {i_k:.3f} mA"
        )
        ax.annotate(
            note, xy=(t_k, u_k), xytext=(t_k + 0.55, 0.28),
            fontsize=10, ha="left",
            bbox=dict(boxstyle="round,pad=0.45", fc="none", ec="#8a8f98", lw=0.9),
            arrowprops=dict(arrowstyle="->", color="#8a8f98", lw=1.0),
        )
        if idx // 3 == 1:
            ax.set_xlabel("t (ms)", fontsize=10.5)
        if idx % 3 == 0:
            ax.set_ylabel("$u_C$ (V)", fontsize=10.5)

    fig.tight_layout(rect=(0, 0, 1, 0.89))

    svg_path = OUT_DIR / "20261004_edu_rc_charge_steps_static.svg"
    png_path = OUT_DIR / "20261004_edu_rc_charge_steps_static.png"
    fig.savefig(svg_path)
    fig.savefig(png_path)
    plt.close(fig)

    inject_dark(svg_path)
    print(f"[rc_static_steps] svg = {svg_path} ({svg_path.stat().st_size} B)")
    print(f"[rc_static_steps] png = {png_path} ({png_path.stat().st_size} B)")


def inject_dark(svg_path: Path) -> None:
    """暗色 @media 块：!important 压过 inline style（既有图无 !important，本图修正）。"""
    rules = (
        "  text { fill: #e8eaed !important; }\n"
        "  #curve_full { stroke: #4a5164 !important; }\n"
        "  #curve_part { stroke: #a0a8e8 !important; }\n"
        "  #uc_marker { stroke: #a0a8e8 !important; fill: #a0a8e8 !important; }\n"
        "  #uline { stroke: #4a5164 !important; }"
    )
    style = '<style type="text/css">\n@media (prefers-color-scheme: dark) {\n' + rules + "\n}\n</style>"
    txt = svg_path.read_text(encoding="utf-8")
    i = txt.index(">", txt.index("<svg")) + 1
    svg_path.write_text(txt[:i] + style + txt[i:], encoding="utf-8")
    print("[rc_static_steps] dark @media 注入完成")


if __name__ == "__main__":
    main()

#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""两层坡顶小宅「拾光居」· 体块轴测图（静态降级通路实证，示例方案合成数据）

与 three.js 体块模型同源的米制数据（轴线 9600×8400、层高 3000×2、檐口 6.0 m、屋脊 8.4 m），
用 matplotlib patches 以等轴测近似投影（u=(x−y)cos30°, v=z+(x+y)sin30°）绘制，
墙面/山墙按深度排序后绘屋面（painter's algorithm）——three.js 不可用时的静态替代通路。

输出: C:/Users/Kai/.nebflow/plots/vrs2-archart/20261004_arch_axono.png
"""
import os
os.environ.setdefault("MPLCONFIGDIR", r"C:\Users\Kai\.nebflow\tools\mplconfig")
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
from matplotlib.patches import Polygon

plt.rcParams["font.sans-serif"] = ["Microsoft YaHei"]
plt.rcParams["axes.unicode_minus"] = False

OUT = r"C:\Users\Kai\.nebflow\plots\vrs2-archart\20261004_arch_axono.png"
C30, S30 = 0.8660254, 0.5
INK, MUT = "#2a2e38", "#6b7078"


def P(x, y, z):
    """米制坐标 → 等轴测近似投影"""
    return ((x - y) * C30, z + (x + y) * S30)


# 体块面（米制）：多边形顶点 + 填充 + 分组（0=墙/山墙先绘，1=屋面后绘）
XF, YF, H2, OV = 9.6, 8.4, 6.0, 0.4          # 轴线 9600×8400、檐口 6.0、挑檐 0.4
RISE = 2.4                                    # 屋脊抬高（檐口 6.0 → 屋脊 8.4）
ZR = H2 + RISE                                # 屋脊 8.4
ZE = H2 - OV * RISE / (YF / 2)                # 挑檐端点高 5.771（沿坡面挑出）
YRID = YF / 2                                 # 屋脊位于进深中线 4.2

def _f(poly, fc):
    return (poly, fc)

FACES = [
    # 北坡（主 x∈[0,9.6] + 东西挑檐带）
    _f([(0, YF + OV, ZE), (XF, YF + OV, ZE), (XF, YRID, ZR), (0, YRID, ZR)], "#454e8f"),
    _f([(XF, YF + OV, ZE), (XF + OV, YF + OV, ZE), (XF + OV, YRID, ZR), (XF, YRID, ZR)], "#454e8f"),
    _f([(0 - OV, YF + OV, ZE), (0, YF + OV, ZE), (0, YRID, ZR), (0 - OV, YRID, ZR)], "#454e8f"),
    # 南坡（主 + 东西挑檐带）
    _f([(0, -OV, ZE), (XF, -OV, ZE), (XF, YRID, ZR), (0, YRID, ZR)], "#5e6ad2"),
    _f([(XF, -OV, ZE), (XF + OV, -OV, ZE), (XF + OV, YRID, ZR), (XF, YRID, ZR)], "#5e6ad2"),
    _f([(0 - OV, -OV, ZE), (0, -OV, ZE), (0, YRID, ZR), (0 - OV, YRID, ZR)], "#5e6ad2"),
    # 墙体与山墙
    _f([(XF, 0, 0), (XF, YF, 0), (XF, YF, H2), (XF, 0, H2)], "#b9b6ad"),        # 右墙
    _f([(XF, 0, H2), (XF, YF, H2), (XF, YRID, ZR)], "#b9b6ad"),                 # 右山墙
    _f([(0, YF, 0), (XF, YF, 0), (XF, YF, H2), (0, YF, H2)], "#c9c6bd"),        # 背墙
    _f([(0, 0, 0), (XF, 0, 0), (XF, 0, H2), (0, 0, H2)], "#eceade"),            # 前墙
    _f([(0, 0, H2), (0, YF, H2), (0, YRID, ZR)], "#dedbd2"),                    # 左山墙
    _f([(0, 0, 0), (0, YF, 0), (0, YF, H2), (0, 0, H2)], "#dedbd2"),            # 左墙
]
# 前墙门窗（y=0 平面上的矩形：x0,x1,z0,z1, 填充）
FRONT_OPEN = [
    (1.2, 2.4, 0.9, 2.1, "#afc3dd"),   # 卧室1 窗
    (4.9, 5.7, 0.0, 2.1, "#8a4a32"),   # 入户门
    (7.5, 8.3, 1.2, 2.1, "#afc3dd"),   # 卫生间 窗
    (0.9, 3.9, 3.6, 5.2, "#afc3dd"),   # 二层 主卧窗（前墙上半段）
]


def depth(poly3):
    return sum(p[0] + p[1] for p in poly3) / len(poly3)


def main():
    fig, ax = plt.subplots(figsize=(11.5, 7.2), dpi=200)
    fig.patch.set_facecolor("#fdfcfa")
    ax.set_facecolor("#fdfcfa")

    # 场地垫层
    pad = [(-1.0, -1.0, 0), (XF + 1.0, -1.0, 0), (XF + 1.0, YF + 1.0, 0), (-1.0, YF + 1.0, 0)]
    ax.add_patch(Polygon([P(*p) for p in pad], facecolor="#c6c2b2", edgecolor=MUT,
                         lw=0.6, zorder=0))

    for poly3, fc in sorted(FACES, key=lambda f: -depth(f[0])):
        ax.add_patch(Polygon([P(*p) for p in poly3], facecolor=fc,
                             edgecolor=INK, lw=0.8, zorder=1))

    # 前墙门窗（投影矩形）
    for x0, x1, z0, z1, fc in FRONT_OPEN:
        q = [(x0, 0, z0), (x1, 0, z0), (x1, 0, z1), (x0, 0, z1)]
        ax.add_patch(Polygon([P(*p) for p in q], facecolor=fc, edgecolor=INK,
                             lw=0.7, zorder=2))

    # 高度标注
    ax.annotate("檐口 +6.000", xy=P(0, 0, 6.0), xytext=P(-2.6, 3.4, 5.2),
                fontsize=9, color=INK, ha="center",
                arrowprops=dict(arrowstyle="-", color=MUT, lw=0.7))
    ax.annotate("屋脊 +8.400", xy=P(0, YRID, 8.4), xytext=P(-3.4, 2.2, 8.6),
                fontsize=9, color=INK, ha="center",
                arrowprops=dict(arrowstyle="-", color=MUT, lw=0.7))
    ax.annotate("室内外高差 ±0.000（示意）", xy=P(4.8, -1.0, 0), xytext=P(6.4, -4.6, -0.6),
                fontsize=8.5, color=MUT, ha="center",
                arrowprops=dict(arrowstyle="-", color=MUT, lw=0.7))
    # 轴线尺寸（沿投影边）
    ax.text(*P(4.8, -0.6, -0.75), "9600", fontsize=8.5, color=MUT, ha="center")
    ax.text(*P(-0.6, 4.2, -0.75), "8400", fontsize=8.5, color=MUT,
            ha="center", rotation=58)

    ax.set_title("两层坡顶小宅「拾光居」· 体块轴测（示例方案，合成数据）",
                 fontsize=14, color=INK, weight="bold", pad=12)
    ax.text(0.5, 0.015,
            "matplotlib 等轴测近似投影（与 three.js 交互模型同一份米制数据）· "
            "此图即 three.js 不可用时的静态降级通路实证 · 单位 mm/高程 m",
            transform=ax.transAxes, ha="center", fontsize=8.5, color=MUT)
    ax.set_aspect("equal")
    ax.axis("off")
    ax.autoscale()
    fig.tight_layout()
    fig.savefig(OUT, facecolor=fig.get_facecolor())
    print("saved:", OUT, "| ZE =", round(ZE, 3))


if __name__ == "__main__":
    main()

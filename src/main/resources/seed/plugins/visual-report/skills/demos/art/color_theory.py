#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""艺术场景 · 色彩理论图（色轮 / 互补色 / 明度阶）——示例教学数据

- 十二色相轮采用 RYB（伊顿）艺术家色轮的近似 Hex 值（教学示意，非实测色度数据）
- 互补关系 = 色轮上相差 180°（相隔 6 格）的两色；互补混合趋灰在 RGB 下用均值近似演示
- 明度阶：固定色相（套件强调色 #5e6ad2，HLS 色 H≈0.618）与固定饱和度，仅步进 HSL 亮度 L

输出:
  C:/Users/Kai/.nebflow/plots/vrs2-archart/20261004_art_color_wheel.png   (色轮+互补)
  C:/Users/Kai/.nebflow/plots/vrs2-archart/20261004_art_color_value.png   (明度阶)
"""
import os
os.environ.setdefault("MPLCONFIGDIR", r"C:\Users\Kai\.nebflow\tools\mplconfig")
import colorsys
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
from matplotlib.patches import Wedge, Rectangle

plt.rcParams["font.sans-serif"] = ["Microsoft YaHei"]
plt.rcParams["axes.unicode_minus"] = False

OUT_WHEEL = r"C:\Users\Kai\.nebflow\plots\vrs2-archart\20261004_art_color_wheel.png"
OUT_VALUE = r"C:\Users\Kai\.nebflow\plots\vrs2-archart\20261004_art_color_value.png"

INK, MUT, PAPER = "#2a2e38", "#6b7078", "#fdfcfa"

# RYB 十二色相（近似示例值）
HUES = ["红", "橙红", "橙", "黄橙", "黄", "黄绿", "绿", "蓝绿", "蓝", "蓝紫", "紫", "紫红"]
HEXS = ["#D9403A", "#F07845", "#F2A03D", "#F5C242", "#F7D94C", "#A8C545",
        "#5CA653", "#3E9B8F", "#3B6FB6", "#6A5FB5", "#8E4F9E", "#C24B7E"]
ACCENT = "#5e6ad2"  # 套件强调色：明度阶的示例色相


def rgb(hexs):
    hexs = hexs.lstrip("#")
    return tuple(int(hexs[i:i + 2], 16) / 255 for i in (0, 2, 4))


def draw_wheel(ax):
    n = 12
    for i in range(n):
        a1 = 90 - i * 30 - 30 + 1.2      # 顶部起顺时针，每格 30°，留缝
        a2 = 90 - i * 30 - 1.2
        ax.add_patch(Wedge((0, 0), 1.0, a1, a2, width=0.42,
                           facecolor=HEXS[i], edgecolor="none"))
        am = 90 - i * 30 - 15
        lx, ly = 1.22 * __import__("math").cos(__import__("math").radians(am)), \
            1.22 * __import__("math").sin(__import__("math").radians(am))
        ax.text(lx, ly, HUES[i], ha="center", va="center", fontsize=10.5, color=INK)
    # 互补连线（i — i+6）
    for i in range(6):
        am = __import__("math").radians(90 - i * 30 - 15)
        x1, y1 = 0.76 * __import__("math").cos(am), 0.76 * __import__("math").sin(am)
        ax.plot([x1, -x1], [y1, -y1], color=MUT, lw=0.9, alpha=0.85, zorder=0)
    ax.text(0, -0.05, "互补色\n相隔 180°\n（对角连线）", ha="center", va="center",
            fontsize=9, color=MUT)
    ax.set_title("十二色相轮（RYB 近似值）与互补连线", fontsize=12.5, color=INK,
                 weight="bold", pad=10)
    ax.set_xlim(-1.55, 1.55)
    ax.set_ylim(-1.5, 1.5)
    ax.set_aspect("equal")
    ax.axis("off")


def draw_comp(ax):
    """互补对照：6 对，每行 [色A][色B][1:1 均值混合]（RGB 近似演示「补色趋灰」）"""
    rows = 6
    h = 1.0 / rows
    for i in range(6):
        y = 1.0 - (i + 1) * h
        c1, c2 = rgb(HEXS[i]), rgb(HEXS[i + 6])
        mix = tuple((a + b) / 2 for a, b in zip(c1, c2))
        cells = ((c1, HUES[i], 0.06), (c2, HUES[i + 6], 0.40),
                 (mix, "1:1 混合", 0.74))
        for c, lab, x0 in cells:
            ax.add_patch(Rectangle((x0, y + 0.045), 0.26, h - 0.09,
                                   facecolor=c, edgecolor="none"))
            ax.text(x0 + 0.13, y + h / 2, lab, ha="center", va="center",
                    fontsize=8.6, color="#ffffff" if sum(c) < 1.35 else INK,
                    weight="bold")
    ax.text(0.19, 1.06, "色 A", ha="center", fontsize=9.5, color=INK)
    ax.text(0.53, 1.06, "色 B（互补）", ha="center", fontsize=9.5, color=INK)
    ax.text(0.87, 1.06, "均值混合（趋灰）", ha="center", fontsize=9.5, color=INK)
    ax.set_title("互补色对照与混合趋灰（RGB 均值近似）", fontsize=12.5, color=INK,
                 weight="bold", pad=10)
    ax.set_xlim(0.02, 1.02)
    ax.set_ylim(-0.04, 1.14)
    ax.axis("off")


def draw_value(ax):
    """明度阶：固定色相与饱和度，仅步进 HSL 亮度 L；下方同 L 中性灰对照"""
    h, s = colorsys.rgb_to_hls(*rgb(ACCENT))[0], 0.45
    n = 9
    ls = [round(0.15 + i * (0.92 - 0.15) / (n - 1), 3) for i in range(n)]
    w = 1.0 / n
    for i, L in enumerate(ls):
        c = colorsys.hls_to_rgb(h, L, s)
        g = (L, L, L)
        ax.add_patch(Rectangle((i * w + 0.008, 0.56), w - 0.016, 0.36,
                               facecolor=c, edgecolor="none"))
        ax.add_patch(Rectangle((i * w + 0.008, 0.12), w - 0.016, 0.36,
                               facecolor=g, edgecolor="none"))
        ax.text(i * w + w / 2, 0.985, f"{L:.3f}", ha="center", va="top",
                fontsize=8.3, color=INK)
    ax.text(0.5, 1.10, "HSL 亮度 L 步进（左深右浅）· 上：强调色相 #5e6ad2（S=0.45）· 下：同 L 中性灰",
            ha="center", fontsize=9.5, color=INK)
    ax.set_xlim(0, 1)
    ax.set_ylim(0, 1.16)
    ax.axis("off")
    return ls


def main():
    # 图 A：色轮 + 互补
    fig, axes = plt.subplots(1, 2, figsize=(12.6, 5.2), dpi=200,
                             gridspec_kw={"width_ratios": [1.15, 1]})
    fig.patch.set_facecolor(PAPER)
    draw_wheel(axes[0])
    draw_comp(axes[1])
    fig.suptitle("色彩系统 · 色轮与互补色（示例教学数据：RYB 十二色相近似 Hex）",
                 fontsize=14, color=INK, weight="bold", y=0.99)
    fig.tight_layout(rect=(0, 0, 1, 0.93))
    fig.savefig(OUT_WHEEL, facecolor=fig.get_facecolor())
    print("saved:", OUT_WHEEL)

    # 图 B：明度阶
    fig, ax = plt.subplots(figsize=(11.5, 2.9), dpi=200)
    fig.patch.set_facecolor(PAPER)
    ls = draw_value(ax)
    fig.tight_layout()
    fig.savefig(OUT_VALUE, facecolor=fig.get_facecolor())
    print("saved:", OUT_VALUE)
    print("L 阶:", ls)


if __name__ == "__main__":
    main()

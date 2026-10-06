#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""两层坡顶小宅「拾光居」· 平面图（示例方案，合成数据，非实测项目）

matplotlib patches 按真实米制坐标绘制。
- 轴线：开间 3300+3000+3300 = 9600 mm；进深 4200+4200 = 8400 mm
- 墙厚：外墙 240 / 内墙 120（居轴线布置）
- 层高 3.0 m × 2，坡屋面檐口 6.0 m、屋脊 8.4 m（配合 three.js 体块模型同源数据）
- 面积按轴线尺寸计

输出: C:/Users/Kai/.nebflow/plots/vrs2-archart/20261004_arch_plan.png
"""
import os
os.environ.setdefault("MPLCONFIGDIR", r"C:\Users\Kai\.nebflow\tools\mplconfig")
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
from matplotlib.patches import Rectangle, Arc, Circle

plt.rcParams["font.sans-serif"] = ["Microsoft YaHei"]
plt.rcParams["axes.unicode_minus"] = False

OUT = r"C:\Users\Kai\.nebflow\plots\vrs2-archart\20261004_arch_plan.png"

INK, MUT, PAPER = "#2a2e38", "#6b7078", "#f2f4f8"
WIN, DOOR = "#4a6fa5", "#8a4a32"
FILL = {"live": "#f3ead8", "bed": "#e3e9f6", "wet": "#dfeee9", "circ": "#efeef4"}
T_EXT, T_INT = 0.24, 0.12          # 外墙/内墙厚（m）
XV, YV = 9.6, 8.4                  # 轴线总尺寸（m）

# 房间: (x0,y0,x1,y1, 名称, 填充)
ROOMS = [
    (0.0, 0.0, 3.3, 4.2, "卧室1", "bed"),
    (3.3, 0.0, 6.3, 4.2, "玄关·楼梯", "circ"),
    (6.3, 0.0, 9.6, 4.2, "卫生间", "wet"),
    (0.0, 4.2, 5.4, 8.4, "客厅", "live"),
    (5.4, 4.2, 9.6, 8.4, "餐厨", "live"),
]
ROOMS2 = [
    (0.0, 0.0, 3.3, 4.2, "卧室3", "bed"),
    (3.3, 0.0, 6.3, 4.2, "楼梯间", "circ"),
    (6.3, 0.0, 9.6, 4.2, "卫生间2", "wet"),
    (0.0, 4.2, 5.4, 8.4, "主卧", "bed"),
    (5.4, 4.2, 9.6, 8.4, "卧室2", "bed"),
]

# 墙: (x1,y1,x2,y2, 厚, 开洞列表[(沿墙起点m, 宽m, 类型)])  类型: win/door/open
WALLS = [
    # 外墙 240
    (0, 0, 9.6, 0, T_EXT, [(1.2, 1.2, "win"), (4.9, 0.8, "door"), (7.5, 0.8, "win")]),
    (0, 8.4, 9.6, 8.4, T_EXT, [(1.5, 2.4, "win"), (6.6, 1.8, "win")]),
    (0, 0, 0, 8.4, T_EXT, [(1.4, 1.2, "win"), (5.6, 1.6, "win")]),
    (9.6, 0, 9.6, 8.4, T_EXT, [(1.6, 1.0, "win"), (5.8, 1.6, "win")]),
    # 内墙 120
    (0, 4.2, 9.6, 4.2, T_INT, []),
    (5.4, 4.2, 5.4, 8.4, T_INT, [(1.0, 1.6, "open")]),
    (3.3, 0, 3.3, 4.2, T_INT, []),
    (6.3, 0, 6.3, 4.2, T_INT, []),
]
# 门扇: 墙索引 -> [(沿墙起点, 宽, 铰端0/1, 旋向+1逆时针/-1顺时针)]  旋向0=洞口无扇
DOORS = {
    0: [(4.9, 0.8, 0, +1)],             # 入户门（一层 y=0，向内开）
    4: [(4.4, 0.9, 0, +1)],             # 一层 玄关→客厅（向客厅开）
    6: [(2.7, 0.9, 0, +1)],             # 玄关→卧室1（向卧室开）
    7: [(2.7, 0.8, 0, -1)],             # 玄关→卫生间（向卫生间开）
}
DOORS2 = {
    4: [(3.8, 0.9, 0, +1), (5.4, 0.9, 0, +1)],   # 二层 楼梯间→主卧/卧室2
    6: [(2.7, 0.9, 0, +1)],             # 楼梯间→卧室3（向卧室开）
    7: [(2.7, 0.8, 0, -1)],             # 楼梯间→卫生间2（向卫生间开）
}


def draw_wall(ax, w, doors):
    x1, y1, x2, y2, t, ops = w
    dx, dy = x2 - x1, y2 - y1
    L = (dx * dx + dy * dy) ** 0.5
    ux, uy = dx / L, dy / L           # 沿墙单位向量
    nx, ny = -uy, ux                  # 法向
    # 墙体矩形（四角按半厚偏移）
    h = t / 2
    ax.add_patch(Rectangle((x1 - nx * h, y1 - ny * h), L * ux + nx * t, L * uy + ny * t,
                           facecolor=INK, edgecolor="none", zorder=3))
    # 开洞
    for s, wd, kind in ops:
        px, py = x1 + ux * s, y1 + uy * s           # 洞起点
        qx, qy = x1 + ux * (s + wd), y1 + uy * (s + wd)
        # 擦除墙体
        ax.add_patch(Rectangle((px - nx * h, py - ny * h), wd * ux + nx * t, wd * uy + ny * t,
                               facecolor=PAPER, edgecolor="none", zorder=4))
        if kind == "win":  # 窗：玻璃带 + 三线
            for f in (0.5, 0.25, 0.75):
                ax.plot([px + nx * t * f + ux * 0.0, qx + nx * t * f],
                        [py + ny * t * f, qy + ny * t * f],
                        color=WIN, lw=1.1, zorder=5, solid_capstyle="butt")
        # 两端墙垛线（门窗通用）
        for (jx, jy) in ((px, py), (qx, qy)):
            ax.plot([jx - nx * h, jx + nx * h], [jy - ny * h, jy + ny * h],
                    color=INK, lw=1.2, zorder=5)
    # 门扇 + 开启弧
    for s, wd, hinge0, rot in doors:
        hx, hy = x1 + ux * (s if hinge0 == 0 else s + wd), y1 + uy * (s if hinge0 == 0 else s + wd)
        # 关闭位方向：从铰链指向另一门垛
        cx, cy = (ux, uy) if hinge0 == 0 else (-ux, -uy)
        # 扇叶 = 关闭位旋转 ±90°
        lx, ly = (-cy * rot, cx * rot) if rot else (0, 0)
        if rot == 0:
            continue  # 洞口无门扇
        ax.plot([hx, hx + lx * wd], [hy, hy + ly * wd], color=DOOR, lw=1.4, zorder=6)
        # 开启弧：恒为 90°，从关闭位沿旋向扫到扇叶位（避免角度回绕画出大弧）
        import math
        a = math.degrees(math.atan2(cy, cx))
        t1, t2 = (a, a + 90) if rot > 0 else (a - 90, a)
        ax.add_patch(Arc((hx, hy), 2 * wd, 2 * wd, theta1=t1, theta2=t2,
                         color=DOOR, lw=0.9, zorder=6))


def draw_rooms(ax, rooms):
    for x0, y0, x1, y1, name, key in rooms:
        ax.add_patch(Rectangle((x0, y0), x1 - x0, y1 - y0,
                               facecolor=FILL[key], edgecolor="none", zorder=1))
        cx, cy = (x0 + x1) / 2, (y0 + y1) / 2
        area = (x1 - x0) * (y1 - y0)
        if "楼梯" in name or "玄关" in name:   # 避开梯段：标签放走廊侧右下
            cx, cy = x0 + (x1 - x0) * 0.76, 1.35
        ax.text(cx, cy + 0.18, name, ha="center", va="center",
                fontsize=10.5, color=INK, weight="bold", zorder=2)
        ax.text(cx, cy - 0.24, f"{area:.2f} m²", ha="center", va="center",
                fontsize=8.5, color=MUT, zorder=2)
    return {n: (x1 - x0) * (y1 - y0) for x0, y0, x1, y1, n, _ in rooms}


def draw_stairs(ax, up=True):
    x0, x1 = 3.42, 4.80                      # 梯段宽 1.38 m（轴线 3.3~6.3 内净宽）
    y0, y1 = 0.70, 3.55
    for i in range(10):                       # 10 步踏步
        y = y0 + i * (y1 - y0) / 9
        ax.plot([x0, x1], [y, y], color=INK, lw=1.0, zorder=2)
    for x in (x0, x1):
        ax.plot([x, x], [y0, y1], color=INK, lw=1.2, zorder=2)
    xm = (x0 + x1) / 2
    ax.annotate("", xy=(xm, y1 - 0.1), xytext=(xm, y0 + 0.25),
                arrowprops=dict(arrowstyle="-|>", color=DOOR, lw=1.4), zorder=6)
    ax.text(xm + 0.14, y1 - 0.5, "上" if up else "下", fontsize=9, color=DOOR, zorder=6)


def dim_chain(ax, pts, fixed, horiz, total_txt, off_txt=0.14):
    """尺寸链：pts 为轴线坐标序列，fixed 为链线所在坐标（horiz=True 时是 y 值）"""
    for a, b in zip(pts[:-1], pts[1:]):
        seg = b - a
        label = f"{seg*1000:.0f}"
        if horiz:
            ax.plot([a, b], [fixed, fixed], color=MUT, lw=0.7, zorder=2)
            ax.plot([a, a], [fixed - 0.09, fixed + 0.09], color=MUT, lw=0.9, zorder=2)
            ax.plot([b, b], [fixed - 0.09, fixed + 0.09], color=MUT, lw=0.9, zorder=2)
            ax.text((a + b) / 2, fixed + off_txt, label, ha="center", va="bottom",
                    fontsize=8, color=MUT, zorder=2)
        else:
            ax.plot([fixed, fixed], [a, b], color=MUT, lw=0.7, zorder=2)
            ax.plot([fixed - 0.09, fixed + 0.09], [a, a], color=MUT, lw=0.9, zorder=2)
            ax.plot([fixed - 0.09, fixed + 0.09], [b, b], color=MUT, lw=0.9, zorder=2)
            ax.text(fixed - off_txt, (a + b) / 2, label, ha="right", va="center",
                    fontsize=8, color=MUT, rotation=90, zorder=2)
    a, b = pts[0], pts[-1]
    if horiz:
        ax.plot([a, b], [fixed - 0.42, fixed - 0.42], color=MUT, lw=0.7, zorder=2)
        ax.plot([a, a], [fixed - 0.51, fixed - 0.33], color=MUT, lw=0.9, zorder=2)
        ax.plot([b, b], [fixed - 0.51, fixed - 0.33], color=MUT, lw=0.9, zorder=2)
        ax.text((a + b) / 2, fixed - 0.30, total_txt, ha="center", va="bottom",
                fontsize=8.5, color=INK, zorder=2)
    else:
        ax.plot([fixed - 0.42, fixed - 0.42], [a, b], color=MUT, lw=0.7, zorder=2)
        ax.plot([fixed - 0.51, fixed - 0.33], [a, a], color=MUT, lw=0.9, zorder=2)
        ax.plot([fixed - 0.51, fixed - 0.33], [b, b], color=MUT, lw=0.9, zorder=2)
        ax.text(fixed - 0.30, (a + b) / 2, total_txt, ha="right", va="center",
                fontsize=8.5, color=INK, rotation=90, zorder=2)


def north(ax):
    cx, cy, r = 10.9, 8.9, 0.38
    ax.add_patch(Circle((cx, cy), r, facecolor="none", edgecolor=MUT, lw=1.0, zorder=2))
    ax.annotate("", xy=(cx, cy + r * 0.85), xytext=(cx, cy - r * 0.6),
                arrowprops=dict(arrowstyle="-|>", color=INK, lw=1.3), zorder=2)
    ax.text(cx, cy + r + 0.14, "N", ha="center", fontsize=9, color=INK, zorder=2)


def draw_floor(ax, rooms, doors_map, title, up):
    for _, _, _, _, _, key in []:  # noqa — 房间填充在 draw_rooms
        pass
    areas = draw_rooms(ax, rooms)
    for i, w in enumerate(WALLS):
        draw_wall(ax, w, doors_map.get(i, []))
    draw_stairs(ax, up=up)
    dim_chain(ax, [0, 3.3, 6.3, 9.6], -0.75, True, "9600")
    dim_chain(ax, [0, 5.4, 9.6], 9.15, True, "9600")
    dim_chain(ax, [0, 4.2, 8.4], -0.75, False, "8400")
    north(ax)
    ax.set_title(title, fontsize=13, color=INK, weight="bold", pad=14)
    ax.text(0, -1.55, "轴线尺寸 单位:mm · 墙厚 外墙240/内墙120 · 面积按轴线计 · 层高3000",
            fontsize=8, color=MUT)
    ax.set_xlim(-2.1, 11.6)
    ax.set_ylim(-2.0, 9.9)
    ax.set_aspect("equal")
    ax.axis("off")
    return areas


def main():
    fig, axes = plt.subplots(1, 2, figsize=(13.6, 6.4), dpi=200)
    fig.patch.set_facecolor("#fdfcfa")
    a1 = draw_floor(axes[0], ROOMS, DOORS, "一层平面图 1:100", up=True)
    a2 = draw_floor(axes[1], ROOMS2, DOORS2, "二层平面图 1:100", up=False)
    s1, s2 = sum(a1.values()), sum(a2.values())
    fig.suptitle("两层坡顶小宅「拾光居」· 示例方案（合成数据，非实测项目）",
                 fontsize=15, color=INK, weight="bold", y=0.99)
    fig.text(0.5, 0.015,
             f"占地（轴线）{s1:.2f} m² × 2 层 = {s1+s2:.2f} m² · 檐口高 6.0 m · 屋脊高 8.4 m · "
             "交互体块模型见同目录 three.js 交付件",
             ha="center", fontsize=9, color=MUT)
    fig.subplots_adjust(left=0.02, right=0.98, top=0.86, bottom=0.07, wspace=0.04)
    fig.savefig(OUT, facecolor=fig.get_facecolor())
    print("saved:", OUT)
    print("一层分项:", {k: round(v, 2) for k, v in a1.items()}, "小计", round(s1, 2))
    print("二层分项:", {k: round(v, 2) for k, v in a2.items()}, "小计", round(s2, 2))
    print("合计:", round(s1 + s2, 2))


if __name__ == "__main__":
    main()

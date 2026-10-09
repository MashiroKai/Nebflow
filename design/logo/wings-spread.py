#!/usr/bin/env python3
"""手绘方案矢量化:双翼展开
左翼 = 后景(浅灰底/黑边, 透视收短), 右翼 = 前景(白底/黑边, 全开)
羽 = 以枢点为起点的透镜形(两端尖), 中段外弓 -> 展开感来自:
  1) 总张角 ~85° 的扇形散射  2) 羽间缝隙向外扩大  3) 羽干外弓(翼尖上挑)
调 FEATHERS_F / FEATHERS_B / STROKE 后重跑即可。
"""
import math

BLACK, WHITE, GRAY = "#111111", "#ffffff", "#d8d8d8"
STROKE = 10          # 黑边宽度
PIVOT = (0, 0)

# (角度°, 长度, 宽度, 弓度)  角度为相对水平的张角, 弓度为长度的百分比
FEATHERS_F = [       # 前景右翼: 细长切片, 向右展开
    (25, 410, 40, 0.070),
    (37, 440, 38, 0.065),
    (49, 385, 36, 0.060),
    (60, 300, 34, 0.055),
]
FEATHERS_B = [       # 后景左翼: 越过垂直线向左倾, 与右翼相背展开
    (96, 360, 36, 0.035),
    (107, 330, 33, 0.030),
    (116, 270, 30, 0.025),
]

def feather_path(ang, length, width, bow):
    px, py = PIVOT
    a = math.radians(ang)
    dx, dy = math.cos(a), -math.sin(a)          # y 向下
    tip = (px + dx*length, py + dy*length)
    mid = (px + dx*length/2, py + dy*length/2)
    n1, n2 = (-dy, dx), (dy, -dx)
    n = n1 if n1[1] < 0 else n2                 # 弓向屏幕上方 -> 翼尖上挑
    bl = bow * length
    co = (mid[0] + n[0]*2*(bl + width/2), mid[1] + n[1]*2*(bl + width/2))
    ci = (mid[0] + n[0]*2*max(bl - width/2, 2), mid[1] + n[1]*2*max(bl - width/2, 2))
    return (f"M {px} {py} Q {co[0]:.1f} {co[1]:.1f} {tip[0]:.1f} {tip[1]:.1f} "
            f"Q {ci[0]:.1f} {ci[1]:.1f} {px} {py} Z")

def qpoint(p, c, t, q):
    return ((1-t)**2*p[0] + 2*(1-t)*t*c[0] + t**2*q[0],
            (1-t)**2*p[1] + 2*(1-t)*t*c[1] + t**2*q[1])

def bbox(paths):
    pts = []
    for ang, length, width, bow in paths:
        a = math.radians(ang)
        dx, dy = math.cos(a), -math.sin(a)
        tip = (dx*length, dy*length); mid = (dx*length/2, dy*length/2)
        n1, n2 = (-dy, dx), (dy, -dx)
        n = n1 if n1[1] < 0 else n2
        bl = bow * length
        co = (mid[0]+n[0]*2*(bl+width/2), mid[1]+n[1]*2*(bl+width/2))
        ci = (mid[0]+n[0]*2*(bl-width/2),  mid[1]+n[1]*2*(bl-width/2))
        for c in (co, ci):
            pts += [qpoint((0,0), c, t, tip) for t in (i/24 for i in range(25))]
    xs, ys = [p[0] for p in pts], [p[1] for p in pts]
    return min(xs), min(ys), max(xs), max(ys)

def svg():
    xf = ['<path d="%s"/>' % feather_path(*f) for f in FEATHERS_F]
    xb = ['<path d="%s"/>' % feather_path(*f) for f in FEATHERS_B]
    x0, y0, x1, y1 = bbox(FEATHERS_F + FEATHERS_B)
    pad = 90
    vw, vh = x1-x0+2*pad, y1-y0+2*pad
    ox, oy = -x0+pad, -y0+pad
    body_b = "\n      ".join(xb)
    body_f = "\n      ".join(xf)
    return (f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {vw:.0f} {vh:.0f}">\n'
            f'  <title>wings spread</title>\n'
            f'  <g transform="translate({ox:.0f},{oy:.0f})">\n'
            f'    <g fill="{GRAY}" stroke="{BLACK}" stroke-width="{STROKE}" stroke-linejoin="round">\n      {body_b}\n    </g>\n'
            f'    <g fill="{WHITE}" stroke="{BLACK}" stroke-width="{STROKE}" stroke-linejoin="round">\n      {body_f}\n    </g>\n'
            f'  </g>\n</svg>\n')

here = __file__.rsplit("/", 1)[0]
open(f"{here}/wings-spread.svg", "w").write(svg())
print("written wings-spread.svg")

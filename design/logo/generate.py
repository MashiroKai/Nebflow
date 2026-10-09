#!/usr/bin/env python3
"""双翼展开 · ASCII 风 logo 生成器
思路:logo 建立在终端字符网格上(格宽:格高 = 1:2),每个字符格渲染为一个矩形,
横向同色格合并成一条 <rect>,因此输出是纯矢量 SVG,边缘自带字符阶梯感。
改动 BARS / CORE / U / V 即可调形调比例,重跑本脚本重新生成两版 SVG。
"""
U, V = 12, 24          # 字符格尺寸: 宽 U, 高 V (V=2U 即终端 1:2 比例)
PAD = 2                # 画布四周留白(字符格数)

# 每根羽毛 = (行, 起始列, 长度);左右翼自动镜像,中线留白 2 格
BARS = [
    (1, 10, 9),
    (3,  6, 12),
    (5,  2, 15),
    (7,  0, 16),
]
CORE = (8, 19, 2, 2)   # 翼下悬浮的方块核 (行, 列, 高格数, 宽格数) —— 终端块光标

W, H = 40, 10

def build():
    g = [[0] * W for _ in range(H)]
    def put(r, c):
        if 0 <= r < H and 0 <= c < W: g[r][c] = 1
    for r, c0, l in BARS:
        for c in range(c0, c0 + l): put(r, c)
    for r in range(H):                       # 右翼镜像
        for c in range(W):
            if g[r][c]: put(r, W - 1 - c)
    return g

def core_rect():
    """核单独输出为一条 rect(格内逐行画会在接缝处出现细线)"""
    cr, cc, ch, cw = CORE
    return (cc + PAD) * U, (cr + PAD) * V, cw * U, ch * V

def runs(row):
    out, c = [], 0
    while c < len(row):
        if row[c]:
            s = c
            while c < len(row) and row[c]: c += 1
            out.append((s, c - s))
        else: c += 1
    return out

def svg(g, fill):
    vw, vh = (W + 2 * PAD) * U, (H + 2 * PAD) * V
    x, y, w, h = core_rect()
    body = [f'<rect x="{(c0+PAD)*U}" y="{(r+PAD)*V}" width="{ln*U}" height="{V}"/>'
            for r, row in enumerate(g) for c0, ln in runs(row)]
    body.append(f'<rect x="{x}" y="{y}" width="{w}" height="{h}"/>')
    return ('\n<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 %d %d">\n'
            '  <title>wings - ascii-style emblem</title>\n'
            '  <g fill="%s">\n    %s\n  </g>\n</svg>\n'
            % (vw, vh, fill, "\n    ".join(body)))

here = __file__.rsplit("/", 1)[0]
open(f"{here}/wings-ascii.svg", "w").write(svg(build(), "#000000"))
open(f"{here}/wings-ascii-dark.svg", "w").write(svg(build(), "#ffffff"))
print("written wings-ascii.svg / wings-ascii-dark.svg")

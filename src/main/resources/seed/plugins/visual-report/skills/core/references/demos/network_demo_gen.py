# -*- coding: utf-8 -*-
"""network 场景 demo 图表生成 —— networkx 真算 30 节点小世界网络（固定种子，可复现）。

图 1：spring 布局静态图（社区着色 + 度定大小）→ plots/vrs2-netfin/ SVG（暗色 media query 注入）
交互版：pyvis 自包含 HTML（cdn_resources='inlined'）同目录落盘 + 页框暗色 CSS 注入
数据：nx.connected_watts_strogatz_graph(n=30, k=4, p=0.3, seed=42) —— 全部指标真实计算，无占位。
对照：同边数 Erdős–Rényi 随机图（seed=42），量化「高聚类 + 短路径」的小世界特征。
"""
import os
os.environ.setdefault('MPLCONFIGDIR', r'C:\Users\Kai\.nebflow\tools\mplconfig')
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
from matplotlib.lines import Line2D
from matplotlib.patches import Patch
import networkx as nx

SEED = 42
N, K, P = 30, 4, 0.3
plt.rcParams.update({
    'font.sans-serif': ['Microsoft YaHei', 'SimHei', 'sans-serif'],
    'axes.unicode_minus': False,
    'font.size': 12,
    'axes.titlesize': 14,
    'text.color': '#1b1e26',
    'axes.labelcolor': '#1b1e26',
    'xtick.color': '#1b1e26',
    'ytick.color': '#1b1e26',
    'axes.edgecolor': '#1b1e26',
    'svg.fonttype': 'none',
})
NEUTRAL, ACCENT = '#8a8f98', '#5e6ad2'
PAL = ['#5e6ad2', '#2e9e5b', '#e08c3a', '#c94f7c', '#3a9bbf']  # 中亮度，亮暗两底可读
OUT = r'C:\Users\Kai\.nebflow\plots\vrs2-netfin'
TMP = r'C:\Users\Kai\.nebflow\projects\general\.nebflow\tmp'
os.makedirs(OUT, exist_ok=True)
STAMP = '20261004_231500'
SVG_NAME = f'{STAMP}_net_smallworld_static.svg'
HTML_NAME = f'{STAMP}_net_smallworld_interactive.html'

# ---------- 真实计算（全部指标由 networkx 算出，print 出的字符串逐字进 demo 文档） ----------
G = nx.connected_watts_strogatz_graph(N, K, P, seed=SEED)
deg = dict(G.degree())
m_edges = G.number_of_edges()
avg_deg = sum(deg.values()) / N
C_ws = nx.average_clustering(G)
L_ws = nx.average_shortest_path_length(G)
diam = nx.diameter(G)
comms = sorted(nx.community.greedy_modularity_communities(G), key=len, reverse=True)  # 贪心法，确定性
Q = nx.community.modularity(G, comms)
G_er = nx.gnm_random_graph(N, m_edges, seed=SEED)
if nx.is_connected(G_er):
    C_er = nx.average_clustering(G_er)
    L_er = nx.average_shortest_path_length(G_er)
    er_note = '全图连通'
else:
    giant = G_er.subgraph(max(nx.connected_components(G_er), key=len))
    C_er = nx.average_clustering(giant)
    L_er = nx.average_shortest_path_length(giant)
    er_note = f'非连通，取最大连通片 {giant.number_of_nodes()} 节点'
comm_sizes = [len(c) for c in comms]
deg_min, deg_max = min(deg.values()), max(deg.values())

print('===== DEMO-VALUES（以下字符串逐字用于 demo 文档与图内标注） =====')
print(f'[图] n={N} k={K} p={P} seed={SEED} 边数m={m_edges} 平均度={avg_deg:.2f}')
print(f'[图] 平均聚类系数C={C_ws:.4f} 平均最短路径L={L_ws:.4f} 直径={diam}')
print(f'[图] 模块度Q={Q:.4f} 社区数={len(comms)} 社区规模={comm_sizes}')
print(f'[图] 度范围={deg_min}~{deg_max}')
print(f'[对照] ER同边数: C={C_er:.4f} L={L_er:.4f} ({er_note})')
print(f'[对照] C比={C_ws / C_er:.1f}倍 L比={L_ws / L_er:.2f}倍')

comm_of = {}
for ci, cset in enumerate(comms):
    for v in cset:
        comm_of[v] = ci
node_colors = [PAL[comm_of[v] % len(PAL)] for v in G.nodes()]
pos = nx.spring_layout(G, seed=SEED, k=0.45, iterations=300)  # 布局同样固定种子


def draw(dark: bool, path: str) -> None:
    """亮 / 暗双份渲染；暗份参数与注入 SVG 的 dark CSS 一一对应，供目检。"""
    if dark:
        bg, axbg, fg, edge_c, grid_c = '#14161c', '#1b1e26', '#e8eaed', '#4a5164', '#33384a'
    else:
        bg, axbg, fg, edge_c, grid_c = '#ffffff', '#ffffff', '#1b1e26', '#1b1e26', '#d9dce3'
    fig, ax = plt.subplots(figsize=(9.4, 6.2), facecolor=bg)
    ax.set_facecolor(axbg)
    nx.draw_networkx_edges(G, pos, ax=ax, edge_color=edge_c, alpha=0.45 if not dark else 0.6,
                           width=1.1)
    sizes = [90 + 78 * (deg[v] - deg_min) for v in G.nodes()]
    nx.draw_networkx_nodes(G, pos, ax=ax, node_color=node_colors, node_size=sizes,
                           edgecolors=edge_c, linewidths=0.9)
    nx.draw_networkx_labels(G, pos, ax=ax, font_size=7.2, font_color=fg)
    ax.set_title(f'30 节点小世界网络（Watts–Strogatz n={N}, k={K}, p={P}；seed={SEED} 真实计算）',
                 color=fg)
    ax.text(0.01, 0.015,
            f'平均聚类系数 C = {C_ws:.4f}    平均最短路径 L = {L_ws:.4f}    模块度 Q = {Q:.4f}\n'
            f'模块度社区 {len(comms)} 个（{" / ".join(str(s) for s in comm_sizes)} 节点）    布局 spring_layout(seed={SEED})',
            transform=ax.transAxes, fontsize=9.5, color=fg, va='bottom')
    handles = [Patch(facecolor=PAL[i % len(PAL)], label=f'社区 {i + 1}（{len(c)} 节点）')
               for i, c in enumerate(comms)]
    handles.append(Line2D([0], [0], marker='o', color='none', markerfacecolor=NEUTRAL,
                          markersize=9, label='节点面积 ∝ 度'))
    ax.legend(handles=handles, loc='upper left', bbox_to_anchor=(1.01, 1.0), frameon=False,
              labelcolor=fg, fontsize=10)
    ax.axis('off')
    fig.tight_layout(pad=1.4)
    fig.savefig(path, facecolor=bg)
    plt.close(fig)


# 亮色 SVG → plots（随后注入 dark media query）；亮/暗 PNG → tmp 目检
draw(False, os.path.join(TMP, 'net_smallworld_light_check.png'))
draw(True, os.path.join(TMP, 'net_smallworld_dark_check.png'))
draw(False, os.path.join(OUT, SVG_NAME))

DARK_CSS = ('<style type="text/css">/* vrs2-netfin dark */\n'
            '@media (prefers-color-scheme: dark) {\n'
            '  path[style*="fill: #ffffff"] { fill: #1b1e26 !important; }\n'
            '  rect[style*="fill: #ffffff"] { fill: #1b1e26 !important; }\n'
            '  text { fill: #e8eaed !important; }\n'
            '  path[style*="stroke: #1b1e26"] { stroke: #4a5164 !important; }\n'
            '  use[style*="stroke: #1b1e26"] { stroke: #4a5164 !important; }\n'
            '  path[style*="fill: #1b1e26"] { fill: #e8eaed !important; }\n'
            '  use[style*="fill: #1b1e26"] { fill: #e8eaed !important; }\n'
            '  path[style*="stroke: #d9dce3"], path[style*="stroke: #e8eaed"] '
            '{ stroke: #33384a !important; }\n'
            '}\n</style>')

svg = open(os.path.join(OUT, SVG_NAME), encoding='utf-8').read()
assert '</svg>' in svg and '@media' not in svg
open(os.path.join(OUT, SVG_NAME), 'w', encoding='utf-8').write(svg.replace('</svg>', DARK_CSS + '</svg>', 1))

# ---------- pyvis 交互 HTML（自包含单文件：cdn_resources='inlined'） ----------
from pyvis.network import Network

Gp = G.copy()
for v in Gp.nodes():
    Gp.nodes[v]['color'] = PAL[comm_of[v] % len(PAL)]
    Gp.nodes[v]['size'] = 9 + 2.6 * (deg[v] - deg_min)
    Gp.nodes[v]['label'] = str(v)
    Gp.nodes[v]['title'] = f'节点 {v}｜度 {deg[v]}｜社区 {comm_of[v] + 1}'
net = Network(height='640px', width='100%', bgcolor='#ffffff', font_color='#1b1e26',
              cdn_resources='in_line', directed=False, select_menu=False, filter_menu=False)
net.from_nx(Gp)
net.barnes_hut(gravity=-4000, spring_length=120, spring_strength=0.015, damping=0.55)
net.write_html(os.path.join(OUT, HTML_NAME), open_browser=False)

html = open(os.path.join(OUT, HTML_NAME), encoding='utf-8').read()
chrome = ('<style>\n:root { --color-bg: #ffffff; --color-card: #ffffff; --color-text: #1b1e26;'
          ' --color-muted: #8a8f98; --color-accent: #5e6ad2; --color-border: #e8eaed; }\n'
          '@media (prefers-color-scheme: dark) {\n  :root { --color-bg: #14161c; --color-card: #1b1e26;'
          ' --color-text: #e8eaed; --color-muted: #8a8f98; --color-accent: #a0a8e8;'
          ' --color-border: #2a2e38; }\n}\n'
          'body { background: var(--color-bg); color: var(--color-text); }\n</style>\n')
assert '</head>' in html
open(os.path.join(OUT, HTML_NAME), 'w', encoding='utf-8').write(
    html.replace('</head>', chrome + '</head>', 1))

print('===== 产件 =====')
for p in (os.path.join(OUT, SVG_NAME), os.path.join(OUT, HTML_NAME)):
    print(p, os.path.getsize(p), 'bytes')

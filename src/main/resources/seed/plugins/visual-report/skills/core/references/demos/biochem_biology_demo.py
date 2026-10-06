# -*- coding: utf-8 -*-
"""biology 场景 demo 生成脚本 — visual-report-suite · rdkit 真实渲染（禁手画 SVG）

分子与数据来源（公开常见分子，PubChem PUG REST 权威记录，2026-10-04 逐字取得）：
  阿司匹林 Aspirin   CID 2244  SMILES CC(=O)OC1=CC=CC=C1C(=O)O   C9H8O4    MW 180.16
  咖啡因   Caffeine  CID 2519  SMILES CN1C=NC2=C1C(=O)N(C(=O)N2C)C C8H10N4O2 MW 194.19
  验证 URL：https://pubchem.ncbi.nlm.nih.gov/rest/pug/compound/cid/<CID>/property/Title,IsomericSMILES,MolecularFormula,MolecularWeight/JSON

运行（viz-venv）：
  C:\\Users\\Kai\\.nebflow\\tools\\viz-venv\\Scripts\\python.exe 本文件
输出（出图落 core §7 默认位 + vrs2-biochem 子目录）：
  20261004_bio_structures.svg          阿司匹林+咖啡因 2D 结构网格（rdkit MolDraw2DSVG）
  20261004_bio_lipinski_light/dark.png Lipinski 描述符性质对比（matplotlib 双主题）
  20261004_bio_aspirin_3d_light/dark.png 阿司匹林 3D 构象（ETKDGv3+MMFF94s 坐标，matplotlib 渲染）
"""
from datetime import date

import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot  # noqa: F401  显式加载 pyplot（新版不隐式绑定）
matplotlib.rc_file('C:/Users/Kai/.nebflow/tools/mplconfig/matplotlibrc')  # YaHei 全局配置
matplotlib.rcParams['axes.unicode_minus'] = False

from rdkit import Chem, RDLogger
from rdkit.Chem import AllChem, Crippen, Descriptors, Draw, Lipinski, rdMolDescriptors
from rdkit.Chem.Draw import rdMolDraw2D

RDLogger.DisableLog('rdApp.*')

OUTDIR = 'C:/Users/Kai/.nebflow/plots/vrs2-biochem'
STAMP = date.today().strftime('%Y%m%d')

# ── PubChem 权威 SMILES（逐字，PUG REST 2026-10-04）────────────────────────
ASPIRIN_SMILES = 'CC(=O)OC1=CC=CC=C1C(=O)O'      # CID 2244
CAFFEINE_SMILES = 'CN1C=NC2=C1C(=O)N(C(=O)N2C)C'  # CID 2519

aspirin = Chem.MolFromSmiles(ASPIRIN_SMILES)
caffeine = Chem.MolFromSmiles(CAFFEINE_SMILES)
assert aspirin and caffeine, 'SMILES 解析失败'


def desc_row(mol):
    """描述符集合（每个数值给格式化字符串，图表与正文共用，保证逐字一致）。"""
    return {
        'MolWt': f"{Descriptors.MolWt(mol):.3f}",
        'cLogP': f"{Crippen.MolLogP(mol):.4f}",
        'TPSA': f"{rdMolDescriptors.CalcTPSA(mol):.2f}",
        'HBD': f"{Lipinski.NumHDonors(mol)}",
        'HBA': f"{Lipinski.NumHAcceptors(mol)}",
        'RotB': f"{rdMolDescriptors.CalcNumRotatableBonds(mol)}",
        'HeavyAtoms': f"{mol.GetNumHeavyAtoms()}",
        'AromRings': f"{rdMolDescriptors.CalcNumAromaticRings(mol)}",
    }


rows = {'阿司匹林 Aspirin (CID 2244)': desc_row(aspirin),
        '咖啡因 Caffeine (CID 2519)': desc_row(caffeine)}

print('== PubChem 数据来源（2026-10-04 PUG REST 逐字）==')
print(f"Aspirin   CID 2244  {Chem.MolFormula(aspirin) if hasattr(Chem,'MolFormula') else rdMolDescriptors.CalcMolFormula(aspirin)}  SMILES {ASPIRIN_SMILES}")
print(f"Caffeine  CID 2519  {rdMolDescriptors.CalcMolFormula(caffeine)}  SMILES {CAFFEINE_SMILES}")
print('\n== rdkit 描述符（viz-venv rdkit 口径，脚本逐字输出）==')
for name, row in rows.items():
    print(name + '  ' + '  '.join(f"{k}={v}" for k, v in row.items()))

# ── 图 1：2D 结构网格（rdkit MolsToGridImage SVG 真实渲染，白面板硬编码＝纸面图）──
def grid_svg(mols, legends, cell_w, cell_h, cols):
    opts = rdMolDraw2D.MolDrawOptions()
    opts.setBackgroundColour((1, 1, 1))
    opts.legendFontSize = 20
    try:
        img = Draw.MolsToGridImage(mols, molsPerRow=cols, subImgSize=(cell_w, cell_h),
                                   legends=legends, drawOptions=opts, useSVG=True)
    except TypeError:  # 旧版无 drawOptions 参数
        img = Draw.MolsToGridImage(mols, molsPerRow=cols, subImgSize=(cell_w, cell_h),
                                   legends=legends, useSVG=True)
    return img.data if hasattr(img, 'data') else str(img)

svg = grid_svg([aspirin, caffeine],
               ['Aspirin (CID 2244)', 'Caffeine (CID 2519)'],
               cell_w=360, cell_h=300, cols=2)
p1 = f'{OUTDIR}/{STAMP}_bio_structures.svg'
with open(p1, 'w', encoding='utf-8') as f:
    f.write(svg)
print(f'\n[written] {p1}  ({len(svg.encode("utf-8"))} bytes)')

# ── 图 2：Lipinski 描述符性质对比（matplotlib 双主题，阈值线为 Ro5 文献口径）─
THRESH = {'MolWt': 500.0, 'cLogP': 5.0, 'TPSA': 140.0, 'HBD': 5, 'HBA': 10}
PANELS = ['MolWt', 'cLogP', 'TPSA', 'HBD', 'HBA', 'RotB']
NAMES = list(rows.keys())
ACCENT, NEUTRAL = '#5e6ad2', '#8a8f98'   # core §3 调色板常量


def lipinski_chart(theme):
    if theme == 'light':
        bg, fg, muted, grid = '#ffffff', '#1b1e26', '#8a8f98', '#e8eaed'
    else:
        bg, fg, muted, grid = '#14161c', '#e8eaed', '#8a8f98', '#2a2e38'
    fig, axes = matplotlib.pyplot.subplots(2, 3, figsize=(11.5, 6.4))
    fig.patch.set_facecolor(bg)
    for ax, key in zip(axes.flat, PANELS):
        vals = [float(rows[n][key]) for n in NAMES]
        bars = ax.bar(range(len(NAMES)), vals, width=0.55,
                      color=[ACCENT, NEUTRAL], edgecolor='none')
        if key in THRESH:
            ax.axhline(THRESH[key], ls='--', lw=1.2,
                       color='#c0392b', zorder=0)
            ax.text(1.48, THRESH[key], f'Ro5 阈值 {THRESH[key]:g}', ha='right',
                    va='bottom', fontsize=8.5, color='#c0392b')
        for x, v in zip(range(len(NAMES)), vals):
            ax.text(x, v, rows[NAMES[x]][key], ha='center',
                    va='bottom' if v >= 0 else 'top', fontsize=10, color=fg)
        ax.set_title(key, fontsize=12, color=fg)
        ax.set_xticks(range(len(NAMES)))
        ax.set_xticklabels(['Aspirin', 'Caffeine'], fontsize=10, color=fg)
        ax.tick_params(colors=muted)
        for s in ax.spines.values():
            s.set_color(grid)
        ax.set_facecolor(bg)
        ax.grid(axis='y', color=grid, lw=0.6, alpha=0.6)
        ax.set_axisbelow(True)
        ax.axhline(0, color=grid, lw=0.8)
        top = max(max(vals), THRESH.get(key, 0))
        bottom = min(0.0, min(vals)) * 1.35 if min(vals) < 0 else 0.0
        ax.set_ylim(bottom, top * 1.22)
    fig.suptitle('Lipinski Ro5 描述符对比：阿司匹林 vs 咖啡因（rdkit 2026.03.6 计算，viz-venv）',
                 fontsize=13, color=fg)
    fig.tight_layout(rect=(0, 0, 1, 0.95))
    p = f'{OUTDIR}/{STAMP}_bio_lipinski_{theme}.png'
    fig.savefig(p, dpi=150, facecolor=bg)
    matplotlib.pyplot.close(fig)
    return p

print('\n[written] ' + lipinski_chart('light'))
print('[written] ' + lipinski_chart('dark'))

# ── 图 3：阿司匹林 3D 构象（ETKDGv3 嵌入 + MMFF94s 优化；坐标全部 rdkit 生成）─
mol3d = Chem.AddHs(aspirin)
ps = AllChem.ETKDGv3()
ps.randomSeed = 42
cid = AllChem.EmbedMolecule(mol3d, ps)
assert cid == 0, '构象嵌入失败'
assert AllChem.MMFFHasAllMoleculeParams(mol3d), 'MMFF 参数缺失'
AllChem.MMFFOptimizeMolecule(mol3d, mmffVariant='MMFF94s', maxIters=2000)
mmff_props = AllChem.MMFFGetMoleculeProperties(mol3d, mmffVariant='MMFF94s')
energy = AllChem.MMFFGetMoleculeForceField(mol3d, mmff_props).CalcEnergy()
pos = mol3d.GetConformer().GetPositions()
n_atoms, n_bonds = mol3d.GetNumAtoms(), mol3d.GetNumBonds()
print(f'\n== 阿司匹林 3D 构象（rdkit ETKDGv3 seed=42 + MMFF94s 优化）==')
print(f'atoms={n_atoms} bonds={n_bonds} MMFF94s_energy={energy:.4f} kcal/mol')

ELEM_STYLE = {'light': {'C': '#44444a', 'O': '#d9534f', 'H': '#9aa0a8'},
              'dark':  {'C': '#c8ccd4', 'O': '#ff7b6e', 'H': '#6a6f78'}}


def conformer_3d(theme):
    if theme == 'light':
        bg, fg, muted, pane = '#ffffff', '#1b1e26', '#8a8f98', '#e8eaed'
    else:
        bg, fg, muted, pane = '#14161c', '#e8eaed', '#8a8f98', '#2a2e38'
    es = ELEM_STYLE[theme]
    fig = matplotlib.pyplot.figure(figsize=(7.6, 6.2))
    fig.patch.set_facecolor(bg)
    ax = fig.add_subplot(111, projection='3d')
    ax.set_facecolor(bg)
    elems = [a.GetSymbol() for a in mol3d.GetAtoms()]
    sizes = [110 if e != 'H' else 40 for e in elems]
    colors = [es[e] for e in elems]
    for b in mol3d.GetBonds():
        i, j = b.GetBeginAtomIdx(), b.GetEndAtomIdx()
        lw = 1.6 if b.GetEndAtom().GetSymbol() != 'H' and b.GetBeginAtom().GetSymbol() != 'H' else 0.7
        ax.plot(*zip(pos[i], pos[j]), color=muted, lw=lw, zorder=1)
    ax.scatter(pos[:, 0], pos[:, 1], pos[:, 2], s=sizes, c=colors,
               depthshade=True, edgecolors='none', zorder=2)
    for idx, e in enumerate(elems):
        if e != 'H':
            ax.text(*pos[idx], f' {e}{idx}', fontsize=8, color=es[e])
    ax.set_title(f'阿司匹林 3D 构象（ETKDGv3 seed=42 + MMFF94s，E = {energy:.4f} kcal/mol）',
                 fontsize=12, color=fg, pad=2)
    ax.set_axis_off()
    import numpy as np
    spans = np.ptp(pos, axis=0)
    ax.set_box_aspect(tuple(spans))
    from matplotlib.lines import Line2D
    handles = [Line2D([0], [0], marker='o', ls='', color=es[k], label=k,
                      markersize=8) for k in ('C', 'O', 'H')]
    ax.legend(handles=handles, loc='upper left', frameon=False,
              labelcolor=fg, fontsize=10)
    p = f'{OUTDIR}/{STAMP}_bio_aspirin_3d_{theme}.png'
    fig.savefig(p, dpi=150, facecolor=bg)
    matplotlib.pyplot.close(fig)
    return p

print('\n[written] ' + conformer_3d('light'))
print('[written] ' + conformer_3d('dark'))

# ── Lipinski Ro5 判定（阈值：MW<500 / cLogP<5 / HBD≤5 / HBA≤10）──────────────
print('\n== Lipinski Ro5 判定 ==')
for name, row in rows.items():
    ok = (float(row['MolWt']) < 500 and float(row['cLogP']) < 5
          and int(row['HBD']) <= 5 and int(row['HBA']) <= 10)
    print(f"{name}: {'全过 4/4' if ok else '违反 Ro5'}（MW={row['MolWt']}<500, cLogP={row['cLogP']}<5, HBD={row['HBD']}<=5, HBA={row['HBA']}<=10）")

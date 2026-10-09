# -*- coding: utf-8 -*-
"""chemistry 场景 demo 生成脚本 — visual-report-suite · rdkit 反应引擎 + 3Dmol.js 自包含交互件

反应与数据来源（教科书经典反应：水杨酸乙酰化合成阿司匹林；分子数据 PubChem PUG REST，2026-10-04 逐字取得）：
  Salicylic Acid    CID 338  SMILES C1=CC=C(C(=C1)C(=O)O)O   C7H6O3  MW 138.12
  Acetic Anhydride  CID 7918 SMILES CC(=O)OC(=O)C            C4H6O3  MW 102.09
  Aspirin           CID 2244 SMILES CC(=O)OC1=CC=CC=C1C(=O)O C9H8O4  MW 180.16
  Acetic Acid       CID 176  SMILES CC(=O)O                  C2H4O2  MW 60.05
  3Dmol.js v2.5.5 库本体（537,792 bytes）来自 https://cdn.jsdelivr.net/npm/3dmol@2.5.5/build/3Dmol-min.js
  （node --check 语法验证通过；与本脚本生成的 HTML 内联，产物自包含、断网可用）

运行（viz-venv）：
  C:\\Users\\Kai\\.nebflow\\tools\\viz-venv\\Scripts\\python.exe 本文件
输出：
  20261004_chem_rxn_aspirin.svg    反应式（rdkit DrawReaction）
  20261004_chem_species_grid.svg   四物种 2D 结构网格
  20261004_chem_aspirin_3dmol.html 自包含可旋转 3D 交互件（3Dmol.js 内联）
"""
import json
import math
import re
from datetime import date

from rdkit import Chem, RDLogger
from rdkit.Chem import AllChem, Descriptors, Draw, rdChemReactions, rdMolDescriptors
from rdkit.Chem.Draw import rdMolDraw2D

RDLogger.DisableLog('rdApp.*')

OUTDIR = 'C:/Users/Kai/.nebflow/plots/vrs2-biochem'
STAMP = date.today().strftime('%Y%m%d')
LIB = f'{OUTDIR}/3Dmol-min.js'

# ── PubChem 权威 SMILES（逐字）──────────────────────────────────────────────
SPECIES = {  # name: (CID, SMILES, PubChem MW, PubChem formula)
    'Salicylic Acid':   ('CID 338',  'C1=CC=C(C(=C1)C(=O)O)O',   '138.12', 'C7H6O3'),
    'Acetic Anhydride': ('CID 7918', 'CC(=O)OC(=O)C',           '102.09', 'C4H6O3'),
    'Aspirin':          ('CID 2244', 'CC(=O)OC1=CC=CC=C1C(=O)O', '180.16', 'C9H8O4'),
    'Acetic Acid':      ('CID 176',  'CC(=O)O',                  '60.05',  'C2H4O2'),
}
RXN_SMILES = ('C1=CC=C(C(=C1)C(=O)O)O.CC(=O)OC(=O)C>>'
              'CC(=O)OC1=CC=CC=C1C(=O)O.CC(=O)O')
# 全映射反应模板（17 重原子全映射，供反应引擎验证用；Validate 目标 (0,0)）：
RXN_MAPPED = ('[C:2]1=[C:3][C:4]=[C:5]([C:6](=[C:7]1)[C:8](=[O:9])[OH:10])[OH:1].'
              '[CH3:14][C:11](=[O:12])[O:13][C:15](=[O:16])[CH3:17]>>'
              '[C:2]1=[C:3][C:4]=[C:5]([C:6](=[C:7]1)[C:8](=[O:9])[OH:10])[O:1][C:11](=[O:12])[CH3:14].'
              '[CH3:17][C:15](=[O:16])[OH:13]')

mols = {n: Chem.MolFromSmiles(s) for n, (_, s, _, _) in SPECIES.items()}
assert all(mols.values()), 'SMILES 解析失败'
rxn = rdChemReactions.ReactionFromSmarts(RXN_SMILES, useSmiles=True)
rxn_mapped = rdChemReactions.ReactionFromSmarts(RXN_MAPPED, useSmiles=True)
v_errors, v_warnings = rxn_mapped.Validate()
assert (v_errors, v_warnings) == (0, 0), f'映射模板校验失败: {(v_errors, v_warnings)}'

print('== PubChem 数据来源（2026-10-04 PUG REST 逐字）==')
for n, (cid, smi, mw, formula) in SPECIES.items():
    print(f'{n:18s} {cid:9s} {formula:8s} MW={mw}  SMILES {smi}')

# 产物同一性校验：映射模板真实运行 ⇒ 产物 ≡ PubChem 阿司匹林（canonical SMILES 同一性）
sal_kek = Chem.Mol(mols['Salicylic Acid'])
Chem.Kekulize(sal_kek, clearAromaticFlags=True)  # 模板为 kekulé 形式，反应物须同形式匹配
prods = rxn_mapped.RunReactants((sal_kek, mols['Acetic Anhydride']))
assert prods, '反应未发生'
prod_aspirin = prods[0][0]
prod_acid = prods[0][1]
canon_prod = Chem.MolToSmiles(Chem.RemoveHs(prod_aspirin))
canon_pubchem = Chem.MolToSmiles(mols['Aspirin'])
print(f'\n== 反应引擎验证（全映射模板 Validate=(0,0)，RunReactants 实跑）==')
print(f'产物组数: {len(prods)}（乙酰基对称 ⇒ 2 组等价映射）')
print(f'产物 aspirin canonical: {canon_prod}')
print(f'PubChem CID 2244 canonical: {canon_pubchem}')
print(f'同一性: {canon_prod == canon_pubchem}；副产物 acetic acid canonical: {Chem.MolToSmiles(Chem.RemoveHs(prod_acid))}')

# 质量守恒核对（PubChem MW 口径，1:1:1:1 计量比）
lhs = float(SPECIES['Salicylic Acid'][2]) + float(SPECIES['Acetic Anhydride'][2])
rhs = float(SPECIES['Aspirin'][2]) + float(SPECIES['Acetic Acid'][2])
print(f'\n== 计量核对（1:1:1:1，PubChem MW 口径）==\n反应物 {SPECIES["Salicylic Acid"][2]} + {SPECIES["Acetic Anhydride"][2]} = {lhs:.2f} g/mol\n产物   {SPECIES["Aspirin"][2]} + {SPECIES["Acetic Acid"][2]} = {rhs:.2f} g/mol\n守恒: {abs(lhs - rhs) < 1e-9}')

# ── 图 1：反应式（rdkit ReactionToImage SVG 真实渲染，白面板硬编码）──────────
opts = rdMolDraw2D.MolDrawOptions()
opts.setBackgroundColour((1, 1, 1))
opts.legendFontSize = 18
svg_rxn = Draw.ReactionToImage(rxn, subImgSize=(280, 230), useSVG=True, drawOptions=opts)
if hasattr(svg_rxn, 'data'):
    svg_rxn = svg_rxn.data
svg_rxn = str(svg_rxn)
p1 = f'{OUTDIR}/{STAMP}_chem_rxn_aspirin.svg'
with open(p1, 'w', encoding='utf-8') as f:
    f.write(svg_rxn)
print(f'\n[written] {p1}  ({len(svg_rxn.encode("utf-8"))} bytes)')

# ── 图 2：四物种 2D 网格（rdkit MolsToGridImage SVG 真实渲染）────────────────
order = ['Salicylic Acid', 'Acetic Anhydride', 'Aspirin', 'Acetic Acid']
grid_mols = [mols[n] for n in order]
legends = [f'{n} ({SPECIES[n][0]})' for n in order]
opts2 = rdMolDraw2D.MolDrawOptions()
opts2.setBackgroundColour((1, 1, 1))
opts2.legendFontSize = 16
try:
    img2 = Draw.MolsToGridImage(grid_mols, molsPerRow=2, subImgSize=(300, 260),
                                legends=legends, drawOptions=opts2, useSVG=True)
except TypeError:
    img2 = Draw.MolsToGridImage(grid_mols, molsPerRow=2, subImgSize=(300, 260),
                                legends=legends, useSVG=True)
svg_grid = img2.data if hasattr(img2, 'data') else str(img2)
p2 = f'{OUTDIR}/{STAMP}_chem_species_grid.svg'
with open(p2, 'w', encoding='utf-8') as f:
    f.write(svg_grid)
print(f'[written] {p2}  ({len(svg_grid.encode("utf-8"))} bytes)')

# ── 图 3：阿司匹林 3D 构象 + 3Dmol.js 自包含交互 HTML ───────────────────────
mol3d = Chem.AddHs(mols['Aspirin'])
ps = AllChem.ETKDGv3()
ps.randomSeed = 42
assert AllChem.EmbedMolecule(mol3d, ps) == 0
assert AllChem.MMFFHasAllMoleculeParams(mol3d)
AllChem.MMFFOptimizeMolecule(mol3d, mmffVariant='MMFF94s', maxIters=2000)
props = AllChem.MMFFGetMoleculeProperties(mol3d, mmffVariant='MMFF94s')
energy = AllChem.MMFFGetMoleculeForceField(mol3d, props).CalcEnergy()
molblock = Chem.MolToMolBlock(mol3d, kekulize=True)
n_atoms, n_bonds = mol3d.GetNumAtoms(), mol3d.GetNumBonds()
print(f'\n== 阿司匹林 3D 构象（ETKDGv3 seed=42 + MMFF94s）==')
print(f'atoms={n_atoms} bonds={n_bonds} MMFF94s_energy={energy:.4f} kcal/mol')

lib = open(LIB, 'r', encoding='utf-8').read()
assert '</script' not in lib, '库内含 </script，需转义'
molblock_js = json.dumps(molblock)

HTML = """<!DOCTYPE html>
<html lang="zh">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>阿司匹林 3D 构象 · 3Dmol.js 自包含交互件</title>
<style>
:root {
  --color-bg: #ffffff; --color-card: #ffffff; --color-text: #1b1e26;
  --color-muted: #8a8f98; --color-accent: #5e6ad2; --color-border: #e8eaed;
}
@media (prefers-color-scheme: dark) {
  :root {
    --color-bg: #14161c; --color-card: #1b1e26; --color-text: #e8eaed;
    --color-muted: #8a8f98; --color-accent: #a0a8e8; --color-border: #2a2e38;
  }
}
html, body { margin: 0; height: 100%; background: var(--color-bg); color: var(--color-text);
  font-family: "Microsoft YaHei", "PingFang SC", sans-serif; }
body { display: flex; flex-direction: column; }
header { padding: 10px 16px 6px; border-bottom: 1px solid var(--color-border); flex: 0 0 auto; }
header h1 { font-size: 16px; margin: 0 0 4px; }
header p { font-size: 12px; margin: 2px 0; color: var(--color-muted); line-height: 1.6; }
#viewer { flex: 1 1 auto; min-height: 0; width: 100%; position: relative; }
footer { padding: 6px 16px; font-size: 11px; color: var(--color-muted);
  border-top: 1px solid var(--color-border); flex: 0 0 auto; }
</style>
<script>
/* 3Dmol.js v2.5.5（npm 3dmol@2.5.5，MIT）内联 — 产物自包含，断网可用 */
__LIB__
</script>
</head>
<body>
<header>
  <h1>阿司匹林（Aspirin，CID 2244）3D 构象 — 可旋转 · 可缩放</h1>
  <p>坐标：rdkit ETKDGv3（seed=42）嵌入 + MMFF94s 力场优化，E = __E__ kcal/mol；__NA__ 原子 / __NB__ 键（含氢）。</p>
  <p>操作：左键拖动旋转 · 滚轮缩放 · 右键平移。样式：stick + sphere（球棍）。</p>
</header>
<div id="viewer"></div>
<footer>数据来源：PubChem CID 2244（SMILES 逐字）· 3Dmol.js v2.5.5 内联自包含 · visual-report-suite chemistry demo</footer>
<script>
var dark = window.matchMedia && window.matchMedia('(prefers-color-scheme: dark)').matches;
var moldata = __MOLBLOCK__;
var viewer = $3Dmol.createViewer('viewer', { backgroundColor: dark ? '#14161c' : '#ffffff' });
viewer.addModel(moldata, 'mol');
viewer.setStyle({}, { stick: { radius: 0.18 }, sphere: { scale: 0.25 } });
viewer.zoomTo();
viewer.render();
viewer.zoom(1.35, 600);
</script>
</body>
</html>
"""

html = (HTML.replace('__LIB__', lib)
            .replace('__E__', f'{energy:.4f}')
            .replace('__NA__', str(n_atoms))
            .replace('__NB__', str(n_bonds))
            .replace('__MOLBLOCK__', molblock_js))
p3 = f'{OUTDIR}/{STAMP}_chem_aspirin_3dmol.html'
with open(p3, 'w', encoding='utf-8') as f:
    f.write(html)

# 自包含验证：加载期不得有活动外链（script src / link / img）；库内 http 字符串为
# download 功能端点与许可注释的惰性代码字面量，不构成网络请求，如实计数不粉饰。
scripts = re.findall(r'<script\b[^>]*>', html)
assert all('src=' not in s for s in scripts), '存在外链 script 标签'
assert '<link ' not in html, '存在 <link> 外链'
assert '<img ' not in html, '存在 <img> 外链'
n_http_literals = len(re.findall(r'https?://', html))
print(f'\n== 交互件自包含验证 ==\n[written] {p3}  ({len(html.encode("utf-8"))} bytes)')
print(f'script 标签数: {len(scripts)}（含 src= 外链: 0）；<link>/<img> 外链: 0')
print(f'库内 http 字面量: {n_http_literals} 处（3Dmol.js download 功能端点/许可注释，非加载期请求）')
print('结论: 3Dmol.js v2.5.5 已内联，产物断网可用')

import py3Dmol
print(f'\npy3Dmol（3Dmol.js Python 封装）版本: {py3Dmol.__version__}')

# 阿司匹林与咖啡因——分子结构、描述符与构象小样

> 本文件是 `../biology/SKILL.md` 的配套示例：rdkit 真实渲染（2D 骨架 / 描述符 / 3D 构象），≤3 图预算（本文 3 图），全部数值逐字可复算。公共规范见 `../core/SKILL.md`。

## 结论先行

阿司匹林与咖啡因都轻松满足 Lipinski Ro5（4/4 项全过）：两者分子量相近（180.159 / 194.194，rdkit 口径），但极性构成相反——阿司匹林亲脂（cLogP = 1.3101）而咖啡因强亲水（cLogP = -1.0293），这解释了两者截然不同的给药与吸收行为量级。

## 结构呈现

数据来源（公开常见分子，权威记录逐字引用）：PubChem PUG REST，2026-10-04 取得——阿司匹林 CID 2244，`CC(=O)OC1=CC=CC=C1C(=O)O`，C9H8O4，MW 180.16（PubChem 口径）；咖啡因 CID 2519，`CN1C=NC2=C1C(=O)N(C(=O)N2C)C`，C8H10N4O2，MW 194.19（PubChem 口径）。2D 结构由 rdkit MolDraw2DSVG 渲染（图内图例用拉丁名：rdkit 内置字体不含 CJK 字形，中文名见图注）：

![阿司匹林与咖啡因 2D 结构](~/.nebflow/plots/vrs2-biochem/20261004_bio_structures.svg)

左：阿司匹林（乙酰氧基与羧基邻位双取代苯环）；右：咖啡因（嘌呤-2,6-二酮骨架，3 个 N-甲基）。

## 描述符定量

rdkit 2026.03.6 计算（viz-venv 实跑逐字输出；阈值列 Lipinski Ro5 文献口径）：

| 描述符 | 阿司匹林 | 咖啡因 | Ro5 阈值 |
|---|---|---|---|
| MolWt（rdkit） | 180.159 | 194.194 | < 500 |
| cLogP（Crippen，rdkit） | 1.3101 | -1.0293 | < 5 |
| TPSA（Å²，rdkit） | 63.60 | 61.82 | ≤ 140 |
| HBD（rdkit） | 1 | 0 | ≤ 5 |
| HBA（rdkit） | 3 | 3 | ≤ 10 |
| RotB（rdkit） | 2 | 0 | — |
| 重原子数 | 13 | 14 | — |
| 芳环数 | 1 | 2 | — |
| Ro5 判定 | 4/4 过 | 4/4 过 | — |

口径注：MW 与氢键计数的工具定义不同——PubChem 口径 MW 为 180.16 / 194.19（上表为 rdkit 逐字值）；氢键受体阿司匹林 PubChem=4 而 rdkit=3（RDKit 的 Lipinski 受体 SMARTS 不计酯氧），咖啡因两源一致为 3。同表内不混用口径，故表列全部采用 rdkit。

![Lipinski Ro5 描述符对比（亮色主题）](~/.nebflow/plots/vrs2-biochem/20261004_bio_lipinski_light.png)

![Lipinski Ro5 描述符对比（暗色主题）](~/.nebflow/plots/vrs2-biochem/20261004_bio_lipinski_dark.png)

读图要点：红色虚线为 Ro5 阈值（500 / 5 / 140 / 5 / 10）；两分子全部远离阈值线，唯一负值是咖啡因的 cLogP = -1.0293（柱向下方延伸）。

## 构象 / 3D

阿司匹林 3D 坐标由 rdkit ETKDGv3（randomSeed=42，可复现）嵌入 + MMFF94s 力场优化（maxIters=2000）生成：21 原子 / 21 键（含 8 个显式氢），优化后力场能量 E = 18.9098 kcal/mol（MMFF94s 口径绝对值，只可与同力场结果比较）。坐标逐值落图，matplotlib 仅作绘图层：

![阿司匹林 3D 构象（亮色主题）](~/.nebflow/plots/vrs2-biochem/20261004_bio_aspirin_3d_light.png)

![阿司匹林 3D 构象（暗色主题）](~/.nebflow/plots/vrs2-biochem/20261004_bio_aspirin_3d_dark.png)

读图要点：乙酰酯链（C0 甲基 → C1 羰基 → O3 → O11 芳环氧）与芳环近似共面，羧基（O12 羰基氧 / O2 羟基氧）因邻位取代与酯基反向排布——这是 MMFF94s 极小值给出的几何，不是示意摆位。

## 数据来源与复算说明

- SMILES / 分子式 / PubChem MW：PubChem PUG REST（CID 2244 / 2519，2026-10-04 逐字取得）；
- 描述符 / 3D 坐标 / 能量：rdkit 2026.03.6（viz-venv，Python 3.12.9）；
- 一键复算（stdout 逐字重印上表数值并重新出图，seed 固定故能量与坐标逐位复现）：
  `C:\Users\Kai\.nebflow\tools\viz-venv\Scripts\python.exe C:\Users\Kai\.nebflow\skills\visual-report-suite\demos\biochem_biology_demo.py`
- 源脚本：`../demos/biochem_biology_demo.py`（demos 目录内）；出图落 `~/.nebflow/plots/vrs2-biochem/`。

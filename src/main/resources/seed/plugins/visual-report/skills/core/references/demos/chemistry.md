# 阿司匹林合成——反应式、引擎验证与自包含 3D 交互件小样

> 本文件是 `../../../chemistry/SKILL.md` 的配套示例：rdkit 反应引擎真实渲染与验证 + 3Dmol.js 自包含交互 HTML，≤3 图槽（本文：反应式 / 物种网格 / 3D 交互件各 1），全部数值逐字可复算。公共规范见 `../../../core/SKILL.md`。

## 结论先行

水杨酸与乙酸酐 1:1 乙酰化生成阿司匹林与乙酸（教科书经典反应）：式量守恒核对 138.12 + 102.09 = 240.21 = 180.16 + 60.05 g/mol（PubChem MW 口径），反应模板 `Validate()==(0,0)`，实跑产物与 PubChem 阿司匹林 canonical 同一。

## 反应式

数据来源：四个物种的 SMILES / 分子式 / MW 逐字取自 PubChem PUG REST（2026-10-04）——Salicylic Acid CID 338 `C1=CC=C(C(=C1)C(=O)O)O`（C7H6O3，138.12）、Acetic Anhydride CID 7918 `CC(=O)OC(=O)C`（C4H6O3，102.09）、Aspirin CID 2244 `CC(=O)OC1=CC=CC=C1C(=O)O`（C9H8O4，180.16）、Acetic Acid CID 176 `CC(=O)O`（C2H4O2，60.05）。画图用反应 SMILES（未映射式，rdkit `ReactionToImage` 渲染）：

`C1=CC=C(C(=C1)C(=O)O)O.CC(=O)OC(=O)C>>CC(=O)OC1=CC=CC=C1C(=O)O.CC(=O)O`

![水杨酸乙酰化反应式](~/.nebflow/plots/vrs2-biochem/20261004_chem_rxn_aspirin.svg)

## 反应验证与计量核对

同一反应用**全原子映射模板**（17 个重原子全映射，demo 内 `RXN_MAPPED`）过引擎：

| 验证项 | 实测结果（rdkit 2026.03.6 逐字输出） |
|---|---|
| 映射模板 Validate | (errors, warnings) = (0, 0) |
| RunReactants 实跑 | 产物组数 2（乙酰基对称 ⇒ 2 组等价映射） |
| 产物 aspirin canonical | `CC(=O)Oc1ccccc1C(=O)O` |
| PubChem CID 2244 canonical | `CC(=O)Oc1ccccc1C(=O)O` |
| 同一性 | True（副产物 acetic acid canonical `CC(=O)O`） |
| 计量核对（1:1:1:1） | 138.12 + 102.09 = 240.21；180.16 + 60.05 = 240.21（g/mol，守恒 True） |

## 结构与 3D 交互件

四物种 2D 网格（rdkit 渲染，图例拉丁名 + CID）：

![四物种 2D 结构网格](~/.nebflow/plots/vrs2-biochem/20261004_chem_species_grid.svg)

产物阿司匹林 3D 构象交付为**自包含可旋转交互件**（3Dmol.js v2.5.5 库内联，WebGL；左键旋转 / 滚轮缩放 / 右键平移，亮暗主题随系统 `prefers-color-scheme` 切换）：

- 交互件（541,973 字节，磁盘实测）：`~/.nebflow/plots/vrs2-biochem/20261004_chem_aspirin_3dmol.html`
- 构象数据：rdkit ETKDGv3（seed=42）+ MMFF94s，E = 18.9098 kcal/mol，21 原子 / 21 键（与 biology demo 同一分子同一种子，能量逐位一致）
- 自包含验证（脚本逐项断言）：`<script>` 标签 2 个且含 `src=` 外链 0；`<link>`/`<img>` 外链 0；库内 http 字面量 15 处（3Dmol.js download 功能端点与许可注释的惰性字符串，非加载期请求）
- 渲染证据：Chrome（headless）以 `file://` 直开截图，亮 / 暗两主题目检通过——断网环境可正常交互

## 数据来源与复算说明

- 物种记录：PubChem PUG REST（CID 338 / 7918 / 2244 / 176，2026-10-04 逐字取得）；
- 反应引擎 / 渲染 / 3D 坐标：rdkit 2026.03.6（viz-venv，Python 3.12.9）；3Dmol.js v2.5.5（npm `3dmol@2.5.5`，库本体本地缓存 `~/.nebflow/plots/vrs2-biochem/3Dmol-min.js`，node --check 语法验证通过）；
- 一键复算（stdout 逐字重印上表并重建全部产物）：
  `C:\Users\Kai\.nebflow\tools\viz-venv\Scripts\python.exe C:\Users\Kai\.nebflow\skills\visual-report-suite\demos\biochem_chemistry_demo.py`
- 源脚本：`./biochem_chemistry_demo.py`（demos 目录内）；位图与交互件落 `~/.nebflow/plots/vrs2-biochem/`。

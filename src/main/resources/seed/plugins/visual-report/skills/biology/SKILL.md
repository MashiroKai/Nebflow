---
name: biology
description: 生物/药物分子报告（rdkit 结构与描述符）；分子结构与性质任务使用。
---

# visual-report · biology — 生物 / 药物分子场景

> 上游公共规范：`${SKILL_DIR}/../core/SKILL.md`（铁律 / 嵌入路径 / 暗色 / 渲染器兼容 / 缺装降级总则 / 自检模板）。本文件只写 biology 场景增量，通用规则一律引用 core，不复制漂移。工具链现状与全量链接验证见 `${SKILL_DIR}/../README.md`。

**为何值得独立成 skill**：分子报告有一套任何其他场景都不具备的图语汇——分子骨架图（2D 结构排版）、构象几何（3D 坐标 + 力场优化）、描述符性质谱（MW / LogP / TPSA / 氢键供受体等成药性量化），且这些图必须由化学信息学引擎（rdkit）从 SMILES 真实解析生成，手画必然失真、通用绘图工具无法代劳。独特的图语汇 + 独有的工具链（rdkit / py3Dmol），与 education（函数曲线）、eda（电路语汇）完全不可互替，故独立成场景。

## 1. 触发条件

命中任一即用本场景骨架交付：

- **分子结构呈现**：药物 / 天然产物 / 生物代谢物的结构说明，需 2D 骨架图或 3D 构象图；
- **分子性质定量报告**：分子量 / 亲脂性 / 极性表面积 / 氢键供受体 / Ro5 成药性等描述符分析与对比；
- **构象 / 几何问题**：3D 形状、键长几何、力场能量等需要真实坐标生成的讨论。

不命中即换场景：反应式 / 反应网络 / 可旋转 3D 交互件用 chemistry；一般概念讲解用 education；纯结论汇报用 office。

## 2. 报告骨架模板（五段，顺序固定）

```markdown
# <分子/体系名>——<一句话结论>

## 结论先行
（这个分子是什么、关键性质量级、一句话判断；第一段就给结论）

## 结构呈现
（2D 骨架图（rdkit 真实渲染）+ 必要时 3D 构象图；图注给 SMILES 来源与 CID）

## 描述符定量
（描述符表：每列标注计算口径（rdkit / PubChem）；对比时配 matplotlib 图，阈值线标文献口径）

## 构象 / 3D
（3D 坐标生成方式：ETKDG 种子 + 力场与变体、能量绝对值口径；能量只在同力场内可比）

## 数据来源与复算说明
（SMILES 逐字出处（PubChem CID + 取得日期）+ 复算命令行；读者可逐值验证）
```

图预算：结构图 1 张 + 描述符对比 1 张 + 构象图 1 张为标准配置，共 3 张（core §1.3 硬性）；超出部分降级表格并加「图预算超限」声明。

## 3. 工具链与自检

rdkit 是本场景主引擎：SMILES 解析、2D 排版（MolDraw2DSVG）、3D 坐标（ETKDGv3 + MMFF94s）、描述符（Descriptors / Crippen / Lipinski）全链路；matplotlib 只做性质谱的绘图层。**2026-10-04 实测**（viz-venv = `C:\Users\Kai\.nebflow\tools\viz-venv\Scripts`，Python 3.12.9）：

| 工具 | 用途 | 本机检测命令（实测 2026-10-04） | 官方链接 | 链接验证记录 | 缺装降级策略 |
|---|---|---|---|---|---|
| rdkit | SMILES 解析 / 2D SVG / 3D 构象 / 描述符 | `<venv>\python.exe -c "import rdkit; print(rdkit.__version__)"` → 2026.03.6 | https://www.rdkit.org/ · https://pypi.org/project/rdkit/ | HTTP 200 · 200（2026-10-04 实测） | 无等效替代（化学信息学引擎是本场景存在前提）；缺则 `pip install rdkit` 装回 viz-venv（core §5），装不上整场景降级为文字描述并如实声明 |
| py3Dmol | 3Dmol.js 的 Python 封装（notebook 内嵌 3D） | `<venv>\python.exe -c "import py3Dmol; print(py3Dmol.__version__)"` → 2.5.5 | https://pypi.org/project/py3Dmol/ | HTTP 200（2026-10-04 实测） | 静态 3D 用 matplotlib 3D 散点（坐标仍是 rdkit 生成）；交互件走 chemistry 场景的自包含 HTML 方案 |
| matplotlib | 描述符性质谱绘图层 | `<venv>\python.exe -c "import matplotlib; print(matplotlib.__version__)"` → 3.11.2 | https://matplotlib.org/ | HTTP 200（2026-10-04，README 链接验证表） | plotly（viz-venv 已装）或纯数值表 |

数据源（分子 SMILES / 分子式的权威出处，逐字引用）：

| 数据源 | 检测 / 验证 | 链接验证记录 | 说明 |
|---|---|---|---|
| PubChem PUG REST | `https://pubchem.ncbi.nlm.nih.gov/rest/pug/compound/cid/<CID>/property/Title,IsomericSMILES,MolecularFormula,MolecularWeight/JSON` | 站点 HTTP 200（2026-10-04 实测） | 按 CID 取 Title / IsomericSMILES / 分子式 / MW；SMILES 必须带 CID 引用，禁止凭记忆写（见 §4.1） |

场景补充约定：

- **图内文本用 ASCII（拉丁名 / 化学式）**：rdkit 内置字体不含 CJK 字形，中文标注会出豆腐块；中文名写在 md 图注与正文（core §1.4「语言跟随用户」在本场景的实现例外，已在此声明）；
- MSYS 传 Windows 路径参数加 `MSYS2_ARG_CONV_EXCL='*'`；Python 包只装进 viz-venv（core §5）；
- 出图默认落 `~/.nebflow/plots/`，本场景批量件建议收 `~/.nebflow/plots/vrs2-biochem/`（白名单内，core §2）。

## 4. 质量红线

1. **结构正确性第一红线**：SMILES 必须逐字来自权威记录（PubChem CID + 取得日期），禁止凭记忆写 SMILES、禁止改写权威串；解析失败不得静默换分子。
2. **数值逐字 + 口径标注**（core §1.2 场景化）：描述符必须标注计算口径——rdkit 与 PubChem 的 HBA/HBD/MW 定义不同（实测例：阿司匹林 HBA，rdkit Lipinski=3，PubChem=4；咖啡因两源一致=3；MW rdkit=180.159，PubChem=180.16）。同一表内混用口径禁止；能量是力场口径的绝对值（MMFF94s kcal/mol），只在同力场数值间比较。
3. **3D 坐标必须真实生成**：一律 ETKDG（固定 randomSeed 保证可复现）+ 力场优化；禁止摆坐标、禁止手画分子。3D 绘图层可用 matplotlib，坐标来源必须仍是 rdkit。
4. **禁止手画 SVG**（core §1.1）：分子骨架图只能由 rdkit 渲染落盘，先落盘后嵌入（core §2 白名单路径）。
5. 单文档 ≤3 图、marked v12 兼容（core §1.3/§4）：`## ` 带空格、GFM 表分隔行列数==表头列数、只用 `$...$`、段落间空行。

## 5. 示例

- demo 文档：`${SKILL_DIR}/../core/references/demos/biology.md` —— 阿司匹林 + 咖啡因：2D 结构网格（rdkit SVG）、Lipinski Ro5 描述符对比（matplotlib 亮/暗双主题）、阿司匹林 3D 构象（ETKDGv3 seed=42 + MMFF94s，E = 18.9098 kcal/mol，matplotlib 亮/暗双主题），全部数值逐字可复算。
- 复现脚本：`${SKILL_DIR}/../core/references/demos/biochem_biology_demo.py`（viz-venv 直跑，rdkit 2026.03.6）。

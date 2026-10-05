---
name: chemistry
description: 化学反应报告（rdkit 验证+3Dmol 交互）；反应与分子 3D 任务使用。
---

# visual-report-suite · chemistry — 化学反应 / 3D 交互场景

> 上游公共规范：`../core/SKILL.md`（铁律 / 嵌入路径 / 暗色 / 渲染器兼容 / 缺装降级总则 / 自检模板）。本文件只写 chemistry 场景增量，通用规则一律引用 core，不复制漂移。工具链现状与全量链接验证见 `../README.md`。

**为何值得独立成 skill**：本场景的独有图语汇是**反应式与反应网络**（反应物 → 试剂 → 产物的规范排版）和**可旋转缩放的 3D 分子交互件**——前者必须由 rdkit 反应引擎从反应 SMILES 真实解析渲染并跑通验证（映射模板 Validate=(0,0) + 产物 canonical 同一性），后者必须由 3Dmol.js 内联库驱动 WebGL 呈现。两者都没有「通用绘图工具平替」路径：手画反应式必然违反排版规范，CDN 外链的 3D 页面在断网环境直接失效。独特的图语汇 + 独有的工具链（rdkit 反应引擎 / 3Dmol.js），与 biology（描述符/构象静态呈现）分工明确，故独立成场景。

## 1. 触发条件

命中任一即用本场景骨架交付：

- **反应说明**：某个化学反应的转化关系、反应式排版、反应网络（多步 / 多分支）；
- **3D 交互交付**：用户需要可旋转 / 缩放的分子 3D 呈现（自包含 HTML 交付件）；
- **结构-反应混合报告**：以结构式和反应关系为主体的化学报告。

不命中即换场景：单分子性质 / 描述符 / 构象静态图用 biology；一般概念讲解用 education。

## 2. 报告骨架模板（五段，顺序固定）

```markdown
# <反应/体系名>——<一句话结论>

## 结论先行
（什么变成了什么、关键计量与条件量级；第一段就给结论）

## 反应式
（rdkit 反应式渲染图 + 反应 SMILES 逐字文本；多步反应用反应网络图）

## 反应验证与计量核对
（映射模板 Validate=(0,0)；RunReactants 实跑产物 canonical ≡ 权威记录；质量守恒核对表）

## 结构与 3D 交互件
（关键物种 2D 网格图 + 自包含 3D 交互 HTML 的绝对路径与自包含验证记录）

## 数据来源与复算说明
（各物种 PubChem CID + 取得日期 + 复算命令行）
```

图预算：反应式 1 张 + 物种 2D 网格 1 张 + 3D 交互件 1 件为标准配置，共 3 个图槽（core §1.3 硬性）；交互 HTML 不作为静态图嵌入 md（marked 不渲染、Pop 只挂静态图），以绝对路径文本交付。

## 3. 工具链与自检

**2026-10-04 实测**（viz-venv = `C:\Users\Kai\.nebflow\tools\viz-venv\Scripts`，Python 3.12.9）：

| 工具 | 用途 | 本机检测命令（实测 2026-10-04） | 官方链接 | 链接验证记录 | 缺装降级策略 |
|---|---|---|---|---|---|
| rdkit | 结构解析 / 反应引擎（ReactionFromSmarts + Validate + RunReactants）/ 反应式与 2D 渲染 | `<venv>\python.exe -c "import rdkit; print(rdkit.__version__)"` → 2026.03.6 | https://www.rdkit.org/ · https://pypi.org/project/rdkit/ | HTTP 200 · 200（2026-10-04 实测） | 无等效替代（反应引擎是本场景存在前提）；缺则 `pip install rdkit` 装回 viz-venv（core §5），装不上整场景降级为文字反应式并如实声明 |
| 3Dmol.js v2.5.5 | 自包含 3D 交互 HTML（WebGL，可旋转缩放） | 库本体已缓存：`~/.nebflow/plots/vrs2-biochem/3Dmol-min.js`（537,792 bytes）；`node --check 3Dmol-min.js` → 通过 | https://3dmol.csb.pitt.edu/ · 构建 https://3dmol.csb.pitt.edu/build/3Dmol-min.js · 镜像 https://cdn.jsdelivr.net/npm/3dmol@2.5.5/build/3Dmol-min.js | 官网 HEAD 200 · 200（2026-10-04）；镜像 GET 下载实测成功（23:01，537,792 B 到手）但同日稍后 HEAD 3 次超时——本网络持续吞吐不可靠，**库本体必须本地缓存复用，勿现场拉取** | 降级为 biology 场景的 matplotlib 3D 静态图（坐标 rdkit 生成）+ 文字操作描述；不得交付 CDN 外链 HTML 冒充自包含 |
| py3Dmol | 3Dmol.js 的 Python 封装（notebook 内嵌 3D，版本随 3Dmol.js 2.5.5） | `<venv>\python.exe -c "import py3Dmol; print(py3Dmol.__version__)"` → 2.5.5 | https://pypi.org/project/py3Dmol/ | HTTP 200（2026-10-04 实测） | 手写内联 HTML（demo 即此模式：读缓存库文件 → 内联 → addModel） |
| matplotlib | 反应网络图 / 补充图表（graphviz 亦可，全路径调用） | `<venv>\python.exe -c "import matplotlib; print(matplotlib.__version__)"` → 3.11.2 | https://matplotlib.org/ | HTTP 200（2026-10-04，README 链接验证表） | plotly（已装）或表格 |

数据源（物种 SMILES / MW 的权威出处，逐字引用）：PubChem PUG REST（站点 HTTP 200，2026-10-04 实测），`https://pubchem.ncbi.nlm.nih.gov/rest/pug/compound/cid/<CID>/property/Title,IsomericSMILES,MolecularFormula,MolecularWeight/JSON`。

场景补充约定：

- **反应模板必须全原子映射**：无映射的反应 SMILES 有固有 validation errors（产物原子无法回溯到反应物模板），不能过 `Validate()==(0,0)`；写法见 demo 的 `RXN_MAPPED`（17 个重原子全映射）；
- **模板与反应物的键级形式须一致**：kekulé 模板匹配 kekulé 反应物（`Chem.Kekulize(mol, clearAromaticFlags=True)` 后再 `RunReactants`）；
- **自包含 HTML 三查**：`<script src=` 外链 0、`<link>`/`<img>` 外链 0、库内 http 字面量如实计数（download 功能端点字符串，非加载期请求）；
- 图内文本 ASCII（rdkit 内置字体无 CJK，见 biology §3 同款约定）；MSYS 路径参数加 `MSYS2_ARG_CONV_EXCL='*'`；出图落 `~/.nebflow/plots/vrs2-biochem/`（白名单，core §2）。

## 4. 质量红线

1. **反应式必须引擎验证**：画出来的反应 SMILES 必须同时以全映射模板过 `Validate()==(0,0)` 并 `RunReactants` 实跑，产物 canonical SMILES 与权威记录（PubChem CID）同一性为 True；只画不跑不算 chemistry 交付。
2. **计量必须守恒核对**：反应物与产物的式量之和逐字相等（PubChem MW 口径；demo 实测 138.12 + 102.09 = 240.21 = 180.16 + 60.05 g/mol）；不等即反应式写错，禁止带病交付。
3. **交互件必须自包含**：3Dmol.js 库内联（本地缓存本体）、`matchMedia` 适配亮暗主题、断网可用；禁止 `<script src>` CDN 外链；交付时附 Chrome headless 截图或等价渲染证据。
4. **结构正确性**：SMILES 逐字来自权威记录（CID + 取得日期），禁止凭记忆写。
5. **数值逐字 + 口径标注**（core §1.2）：MW 标 PubChem 口径、能量标力场口径（MMFF94s kcal/mol，绝对值只在同力场内比较）；文件大小引用磁盘字节数（Windows 文本写入有 CRLF 换行转换，字符串长度 ≠ 磁盘字节数，以 `ls` 为准）。
6. **禁止手画 SVG**（core §1.1）：反应式与结构图只能由 rdkit 渲染落盘；单文档 ≤3 图槽、marked v12 兼容（core §1.3/§4）。

## 5. 示例

- demo 文档：`../demos/chemistry.md` —— 阿司匹林合成（水杨酸 + 乙酸酐 → 阿司匹林 + 乙酸）：反应式 SVG（rdkit 渲染）、全映射模板 Validate=(0,0) + RunReactants 产物 canonical ≡ PubChem CID 2244、质量守恒 240.21 g/mol、四物种 2D 网格、3Dmol.js v2.5.5 内联自包含交互 HTML（亮暗双主题 Chrome headless 截图验证）。
- 复现脚本：`../demos/biochem_chemistry_demo.py`（viz-venv 直跑，rdkit 2026.03.6；库缓存 `~/.nebflow/plots/vrs2-biochem/3Dmol-min.js`）。

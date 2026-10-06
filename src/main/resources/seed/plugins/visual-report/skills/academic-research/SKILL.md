---
name: academic-research
description: 学术调研报告与论文原图图集（pymupdf 两遍法裁图）；文献调研、综述配图任务使用。
---

# visual-report · academic-research — 学术调研报告（论文图集）

场景定位：以**论文原图裁片**为主体的学术调研 / 文献调研报告。本文件只写场景增量；公共铁律、嵌入路径、dark-mode、渲染器兼容、缺装降级总则一律见 [`${SKILL_DIR}/../core/SKILL.md`](${SKILL_DIR}/../core/SKILL.md)，六工具链接验证记录见 [`${SKILL_DIR}/../README.md`](${SKILL_DIR}/../README.md)，不复制、不漂移。

## 1. 触发条件

命中任意一条即走本场景：

- **论文图集**：从一批落盘论文 PDF 高清裁原图，按方案 / 技术路线汇编「图为主体、文字退为注解」的结构图集；
- **文献调研**：多篇文献的装置 / 结构 / 流程 / 数据对比，以原文图为第一证据、原文图注为主要引文；
- **报告主体是论文原图**（裁片）而非自绘图；自绘图只作少量汇总补充，且必须显著标注「自绘」。

**图预算口径裁决**：core §1 铁律 3 的「单文档 ≤3 图」指**自绘可视化图表**（数据 / 时间线 / 流程 / 对比 / 结构等生成物）。本场景以论文原图为主体，**原图裁片不计自绘图预算**，受两条替代约束：① 原图按类别分组嵌入（detector → readout → asic → install），单方案节正文要点 ≤5 行；② 单文档自绘汇总图仍 ≤3 张。全量范例（158 张原图 + 0 自绘）：`~/.nebflow/projects/Sipm/reports/20261004_095255_星际航行辐射监测_探测器与读出结构图集.md`。

## 2. 报告骨架模板

### 2.1 章节结构（图集型，先图后文）

| 节 | 内容 |
|---|---|
| `# 报告标题` | 次行导读块：本报告怎么读 / 图源与红线（全部裁自落盘 PDF 原图、无自绘、图注逐字）/ 配套索引 |
| `## §0 图库总账` | GFM 表：图库总数 / 批次分布 / 类别分布 / 有图·无图方案数 |
| `## §1 总览图墙` | 每条技术路线一行代表图 + 一句「一眼看点」 |
| `## §2 图集主体` | 每方案一节：`#### 方案名｜基线引用 · 研制状态` → 结构图（detector）→ 读出框图（readout）→ ASIC 结构（asic）→ 安装图（install）→ 要点 ≤5 行 |
| `## §3 无图方案清单` | 逐篇注因（论文未获取 / 原文本无此类图），如实标注不凑数 |
| `## §4 订正口径对照`＋尾注 | 相对基线报告的全部改写点；交付自测清单 |

单方案缩影（demo 形态）= 取 §2 的一个方案节独立成文：方案档案表 → 分类别图组（每图四件套）→ 方案要点 → 资产与预算表。

### 2.2 图注四件套（每图必齐，缺一即返工）

标准形（即 demo 实例）：

```markdown
**`C-06_detector_01.png`** ｜ C-06-1 · Figure 1 · p4

![C-06_detector_01](~/.nebflow/projects/Sipm/papers/figs/C/C-06_detector_01.png)

> - **英文图注（逐字）**：Figure 1: Geometry of the PDOZ. …
> - **中译**：PDOZ 几何：闪烁体为紫/蓝/绿三色，每闪烁体底部 2 个 SiPM 为红色
> - **出处**：C-06-1 · Figure 1 · p4（Salvi et al., JINST 18 P08010, 2023，DOI 10.1088/1748-0221/18/08/P08010）
> - **看图要点**：一句话——图里看什么、怎么读、与结论的关系
```

四件套规则：① **英文图注逐字**复制原文 caption（含拼写 / 大小写 / 标点，不修正不节选）；② **中译**一句话，器件型号与缩写照抄不译；③ **出处**= 论文编号 · Figure N · 页码，且走通回溯链「编号 → `papers/INDEX.md`（作者 / 年份 / 期刊 / DOI / PDF 路径）→ 落盘 PDF 原文页」；④ **看图要点**一句话，复用旧库时注明「旧图 ××× 的同图重制高清版」。嵌入路径按 core §2：`~/...` 绝对路径、服务面白名单内。

### 2.3 渲染器兼容落笔清单（marked v12，本部署）

按 core §4 全条执行（`## ` 后带空格 / GFM 分隔行列数==表头列数 / 数学只用 `$...$` / `breaks:true` 段间空行）。场景补三条：① 文件名行、图片行、图注块（`>` 引用）三行结构之间各空一行；② 引用块内四条 bullet 连续书写，不空行断开；③ 行内公式一律 `$...$`，如 $H^*(10)$。

## 3. 工具链与自检

### 3.1 裁图管线：pymupdf 两遍法（区域高清渲染裁图）

环境优先 viz-venv（core §7）。流程：

1. **第一遍 · 整页定界**：zoom 渲整页 PNG → Read 该 PNG，读出目标图的像素边界（渲染坐标，左上–右下）；
2. **换算**：PDF 点坐标 = 渲染像素 ÷ zoom；
3. **第二遍 · 区域裁切**：clip 矩形渲出成片，Read 复核边界（无图注串入、无邻文混入）。

成片红线：宽 ≥1500 px、zoom ≥2，宽不够就加大 zoom 重裁。裁片入库 `papers/figs/<批>/<方案>_<detector|readout|asic|install>_NN.png`，并逐张登记 `FIGS_INDEX.md`（文件名 / 类别 / 论文编号 / Figure / 页码 / 英文图注 / 中译 / 看图要点）。

命令样例（脚本 = 本目录 [`crop_paper_figure.py`](crop_paper_figure.py)；下列两条命令与输出注释 2026-10-04 逐字实测，PDF = C-06-1 JINST 18 P08010 p4，目标 Figure 1）：

```bash
# 第一遍·整页定界（实测输出：page 4/17: 612.0 x 792.0 pt / wrote page_full.png: 1530x1980 px [width OK >=1500]）
MSYS2_ARG_CONV_EXCL='*' 'C:\Users\Kai\.nebflow\tools\viz-venv\Scripts\python.exe' \
  'C:\Users\Kai\.nebflow\skills\visual-report-suite\academic-research\crop_paper_figure.py' \
  'C:\Users\Kai\.nebflow\projects\Sipm\papers\2023_Salvi_PDOZ_personal_dosimeter_SiPM.pdf' \
  4 2.5 page_full.png

# 第二遍·区域裁切（Read 整页图定界 ≈305,443–1145,1045 px ÷2.5 → clip 122,177–458,418 pt；
#   实测输出：wrote C-06_detector_01.png: 1512x1085 px [width OK >=1500]）
MSYS2_ARG_CONV_EXCL='*' 'C:\Users\Kai\.nebflow\tools\viz-venv\Scripts\python.exe' \
  'C:\Users\Kai\.nebflow\skills\visual-report-suite\academic-research\crop_paper_figure.py' \
  'C:\Users\Kai\.nebflow\projects\Sipm\papers\2023_Salvi_PDOZ_personal_dosimeter_SiPM.pdf' \
  4 4.5 C-06_detector_01.png 122,177,458,418
```

既有替代管线：`~/.nebflow/projects/Sipm/papers/figs/_tools/render.mjs`（node + pdfjs-dist，同两遍法，`--crop` 直用渲染像素坐标，自带 ≥1500 px 校验）——207 png 裁图库即其产物，缺 Python 环境时可顶。

### 3.2 工具自检（core §6 模板；检测命令 2026-10-04 实测，链接验证记录引自插件 README 同日表）

| 工具 | 用途 | 本机检测命令（实测） | 官方下载链接 | 链接验证记录 | 缺装降级策略 |
|---|---|---|---|---|---|
| pymupdf | PDF 解析 + 区域高清裁图（主力） | `<venv>\python.exe -m pip show pymupdf` → 1.28.2 | https://pypi.org/project/PyMuPDF/ · https://pymupdf.readthedocs.io/en/latest/ | 200 · 200（2026-10-04，README 表） | pip 装回 viz-venv（core §5，清华镜像加速）；或切 render.mjs 管线 |
| graphviz (dot) | 自绘汇总图（方案谱系 / 读出链框图，须标「自绘」） | `C:\Users\Kai\.nebflow\tools\graphviz\bin\dot.exe -V` → 16.1.0 | https://graphviz.org/download/ | 200（2026-10-04，README 表） | mermaid-cli（npx 直调，未装）或文字层级列表 |
| matplotlib | 自绘数据图（仅当数值逐字可抄录时） | `<venv>\python.exe -c "import matplotlib; print(matplotlib.__version__)"` → 3.11.2 | https://matplotlib.org/ · https://matplotlib.org/stable/users/installing/index.html | 200 · 200（2026-10-04，README 表） | plotly（已装）或文字表格 |

### 3.3 降级策略（不得空转）

- **无 PDF 可裁**（论文未落盘 / 只有题录 / 扫描件不可渲染）：降级为**纯文献综述骨架**——保留 §2.1 章节结构，四件套中以「出处 / 中译（综述转述）/ 看图要点」的文字综述代替图，图位写「缺图注记：方案 + 原因」；不得放占位空框、不得拿自绘图冒充原图、不得空转等 PDF；
- pymupdf 缺装：core §5 装回 viz-venv，或切 render.mjs（node 管线已验证）；
- 自绘汇总图工具缺失：graphviz ↔ mermaid-cli ↔ 文字层级（core §5 替换序，不得手画 SVG）。

## 4. 质量红线（场景四条，违反即返工）

1. **禁止自绘图冒充论文原图**：原图裁片必须带四件套与出处回溯链；自绘汇总图必须显著标注「自绘」并独立归类，不得与原图混排冒充。
2. **图注英文逐字复制**：含原文拼写、大小写、标点；不修正、不改写、不节选、不把翻译混进英文行。
3. **出处页码可回溯**：每张图走通「编号 · Figure · 页码 → papers/INDEX.md → 落盘 PDF 原文页」；缺任何一级就如实降级标注（如「DOI 未获取」），不编造。
4. **数值逐字**（core §1 铁律 2 的场景落点）：正文要点与图注中数值与原文逐字一致，不四舍五入、不脑补、不改口径；原文没有的数不写；抄录上一轮提取报告时按其订正口径，不继承已订正错误。

## 5. 示例

- **单方案缩影（本场景 demo，验收基准）**：[`${SKILL_DIR}/../core/references/demos/academic-research.md`](${SKILL_DIR}/../core/references/demos/academic-research.md) —— C-06 PDOZ（塑闪 + 双 SiPM 符合个人剂量计）：复用既有裁图库 2 张 png（未重新裁图），四件套齐全，marked v12 兼容。
- **全量图集范例**：`~/.nebflow/projects/Sipm/reports/20261004_095255_星际航行辐射监测_探测器与读出结构图集.md`（158 张原图 / 26 方案 / 四路线图墙 / §3 无图方案注因）。
- **裁图库与登记表**：`~/.nebflow/projects/Sipm/papers/figs/`（库内 207 png，在册成品 158 张）＋ `papers/figs/FIGS_INDEX.md`。

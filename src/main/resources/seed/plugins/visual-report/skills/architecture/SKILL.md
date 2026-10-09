---
name: architecture
description: 建筑空间报告（米制平面/轴测/体块交互）；平面、体块与方案表达任务使用。
---

# architecture — 建筑·空间表达场景 skill

> 上游公共规范：`${SKILL_DIR}/../core/SKILL.md`（铁律 / 嵌入路径 / 暗色 / 渲染器兼容 / 缺装降级总则 / 自检模板）。本文件只写 architecture 场景增量，通用规则一律引用 core，不复制漂移。3D 交互与动效一律经插件共享模块 `${SKILL_DIR}/../motion-toolkit/SKILL.md`，场景内不重复环境说明（防漂移）。

## 触发条件

命中任一即用本场景骨架交付：

- **平面/空间表达**：户型图、总平面示意、房间布局与面积分配、家具/动线排布；
- **建筑体块推敲**：体量方案、坡屋顶/层数/朝向研究、可旋转交互体块模型；
- **轴测/三视图**：等轴测体块图、平面-立面-剖面的静态三视表达；
- **小建筑方案汇报**：一个小型建筑（宅、亭、展亭）从数据到图的完整表达。

不命中即换场景：结构与施工细节用 engineering；概念科普用 education；数据图表用其余场景。

## 独立成 skill 论证（一句）

建筑表达有一套通用数据图表不承载的专属图语汇——平面（正投影 + 尺寸链 + 门窗图例）、立面/剖面（标高体系）、体块轴测（比例与尺度语义），一切几何绑定真实米制坐标且多图之间必须同源，这是通用可视化场景没有的约束体系，故独立成 skill。

## 报告骨架模板（五段，顺序固定）

**任务与数据声明 → 几何数据表 → 图解 → 校核与误区 → 结论与交付清单**

| 段 | 必含内容 | 硬约束 |
|---|---|---|
| 任务与数据声明 | 表达对象、图用途；**数据性质一行声明**（实测 / 方案 / 示例合成） | 合成/示例数据在图题与正文首段**双重声明**，不得只写一处 |
| 几何数据表 | 轴网尺寸、层高/檐口/屋脊标高、房间面积表 | 米制真实坐标；面积逐字（core §1.2）且各房间 Σ 必须等于占地 |
| 图解 | 平面 → 立面/剖面/轴测 → 体块交互，单文档 ≤3 图（core §1.3） | 全部专业工具真实渲染落盘；**同一建筑各图共用同一份米制数据** |
| 校核与误区 | 校核表（尺寸链闭合 / 面积闭合 / 指北与朝向 / 门窗可达）+ 误区对照表 | 每条校核给出可验算的数值依据，不许只写「已校核」 |
| 结论与交付清单 | 静态图嵌入 + 交互件绝对路径 + 降级记录（若走了降级） | `~/...` 绝对路径（core §2）；交互 HTML 文件交付、不进 Pop（core §3） |

骨架即验收单：缺任一段或段序颠倒，不算 architecture 交付。

## 工具链与自检

### 工具选择

- **主渲染器 viz-venv matplotlib patches**：平面/立面/剖面/轴测静态全部覆盖——墙线用 `Rectangle`（真实墙厚居轴线）、门扇+开启弧用 `Arc`、尺寸链手工 tick、轴测用等轴测投影 + 深度排序填充（`Polygon`）；`ax.set_aspect("equal")` 强制 1:1 比例；
- **体块/交互 3D：three.js 一律经 `${SKILL_DIR}/../motion-toolkit`**（其 SKILL.md §3.2 + `build_three_html.py` + 场景 JS），场景代码保持 `import * as THREE from "three"`；禁止本场景另写 3D 环境说明、禁止手写 `<canvas>`/手画 SVG（core §1.1）；
- 场景源工程落 `demos/architecture/`（脚本 + three 场景 JS），产件落 `~/.nebflow/plots/`。

### 制图规范（本场景第一红线组）

- 单位约定：坐标与墙厚用米、尺寸链标注用毫米（建筑制图惯例，图内注明）；面积按轴线尺寸计并注明口径；
- 墙 240 / 内墙 120 居轴线布置；门画开启弧、窗画三线、指北针必画（平面图）；
- **多图同源**：平面、轴测、three.js 体块读取同一组数字（轴线、层高、檐口、屋脊），改一处必须全部同步——这是建筑图区别于示意画的底线。

### 工具自检（实测 2026-10-04）

| 工具 | 用途 | 本机检测命令（实测） | 官方下载链接 | 链接验证记录 | 缺装降级策略 |
|---|---|---|---|---|---|
| matplotlib | 平面/立面/轴测主渲染器 | `<venv>\python.exe -c "import matplotlib; print(matplotlib.__version__)"` → 3.11.2 | https://matplotlib.org/ | HTTP 200（2026-10-04） | plotly（已装）或文字几何描述+数据表；不得手画 |
| numpy | 面积/几何计算 | `<venv>\python.exe -c "import numpy; print(numpy.__version__)"` → 2.5.3 | https://pypi.org/project/numpy/ | HTTP 200（2026-10-04） | 纯 Python `math` 模块 |
| three.js（经 motion-toolkit） | 体块交互 HTML | `node -e "console.log(require('C:/Users/Kai/.nebflow/skills/visual-report-suite/motion-toolkit/scripts/three-template/node_modules/three/package.json').version)"` → 0.186.1 | https://threejs.org/ | HTTP 200（2026-10-04 复测） | **matplotlib 轴测投影/三视图静态图**（`demos/architecture/axon_house.py` 即此通路的实证样张）；再不行文字几何表 |
| Chrome（headless 实拍） | WebGL 出图证明 / 交互件复核 | `"C:\Program Files\Google\Chrome\Application\chrome.exe" --headless=new --screenshot=out.png --window-size=1280,900 --virtual-time-budget=6000 file:///<html>` → 40-64 KB PNG | —（google.com/chrome 本网不可达，README 网络基线；以本机已装 Chrome 154.0.8037.93 为准） | 本机在位（实测可出 WebGL 截图） | Edge `msedge.exe` 后备（motion-toolkit §2）；再不行交互件只交 HTML 不附实拍 |

全基础工具链接验证表见插件 README（含 matplotlib/numpy 行），直接引用不复制。`<venv>` = `C:\Users\Kai\.nebflow\tools\viz-venv\Scripts`；装包只进 viz-venv（core §5）。

### 降级策略

- three.js 不可用（node_modules 缺失且 npm 镜像不通）⇒ **matplotlib 轴测投影/三视图静态图**（同一份米制数据，painter's algorithm 深度排序）⇒ 仍不可用 ⇒ 文字几何描述 + 数据表；任何一步不空转、不手画 SVG（core §1.1/§5）；
- 中文乱码 ⇒ 脚本内 `os.environ.setdefault("MPLCONFIGDIR", r"C:\Users\Kai\.nebflow\tools\mplconfig")` + `rcParams["font.sans-serif"]=["Microsoft YaHei"]`（motion-toolkit §2 同源做法）。

## 质量红线

1. **米制真实坐标**：一切几何按真实数值画，禁止「占位框 / 示意方块」——图中每条墙、每个门洞都能在数据表里查到；
2. **多图同源**：平面/轴测/体块共用同一份数据，数值逐字一致（core §1.2）；
3. **合成/示例数据显著声明**：图题 + 正文首段双重声明，不得含糊；
4. 校核可验算：尺寸链闭合（分尺寸和=总尺寸）、面积闭合（Σ房间=占地）、门窗洞口不越轴线；
5. 单文档 ≤3 图、marked v12 兼容（core §1.3/§4）：`## ` 带空格、GFM 表列数对齐、只用 `$...$`、段间空行；
6. 3D 只经 motion-toolkit；禁手画 SVG、禁调 Pop（展示权归 Nebula，core §1.5）。

## 示例

- `${SKILL_DIR}/../core/references/demos/architecture.md` —— 两层坡顶小宅「拾光居」小样：平面双联图（轴线 3300+3000+3300 × 4200+4200，外墙 240/内墙 120）+ 轴测降级通路实证图 + three.js 体块交互 HTML（同一份米制数据：层高 3.0×2、檐口 +6.0、屋脊 +8.4；面积 80.64×2 = 161.28 m²），合成示例数据双声明。

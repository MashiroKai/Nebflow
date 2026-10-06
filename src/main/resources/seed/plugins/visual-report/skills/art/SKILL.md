---
name: art
description: 生成艺术与色彩分析（p5.js 可复现算法）；生成艺术、色彩理论任务使用。
---

# art — 艺术·生成与色彩场景 skill

> 上游公共规范：`${SKILL_DIR}/../core/SKILL.md`（铁律 / 嵌入路径 / 暗色 / 渲染器兼容 / 缺装降级总则 / 自检模板）。本文件只写 art 场景增量，通用规则一律引用 core，不复制漂移。3D/动效一律经 `${SKILL_DIR}/../motion-toolkit/SKILL.md`。

## 触发条件

命中任一即用本场景骨架交付：

- **生成艺术/算法艺术**：流场、粒子、规则系统（元胞自动机/L-system）等程序化作品；
- **色彩系统说明**：配色方案、色轮/互补/明度阶等色彩理论图、品牌/主题色板推导；
- **视觉风格探索**：同一算法不同参数的效果对照、可复现的风格小样。

不命中即换场景：数据可视化为目的的图用其余场景；纯装饰配图不属于插件交付。

## 独立成 skill 论证（一句）

生成艺术与色彩系统有专属图语汇——作品的本质是「算法 + 种子 + 参数」（必须可复现、可参数化对照），色轮/互补/明度阶是自成体系的「色彩数据图」，两者都与以数据为中心的通用可视化语义不同，故独立成 skill。

## 报告骨架模板（五段，顺序固定）

**概念与算法 → 参数与色彩数据表 → 作品呈现 → 解读与误区 → 复现步骤**

| 段 | 必含内容 | 硬约束 |
|---|---|---|
| 概念与算法 | 生成规则用文字+短伪代码说清（场/粒子/规则系统各是什么） | **随机源必须固定种子并写明**；库与版本锁定；随机维度（random/noise）各自说明 |
| 参数与色彩数据表 | 参数表（画布/数量/步长/速度/调色板 hex）；色彩图附色值表 | hex 逐字与图中一致（core §1.2）；示例/合成作品在此声明 |
| 作品呈现 | 静态 PNG 嵌入（headless 实拍为证）+ 交互 HTML 文件交付，单文档 ≤3 图（core §1.3） | 全部真实渲染落盘；交互件不进 Pop（core §3） |
| 解读与误区 | 效果-参数对照（哪个参数控制画面哪个性质）+ 误区对照表 | 每条可复核（写出改参数后的预期方向），不许只写「效果很好」 |
| 复现步骤 | 打开方式、种子号、库版本、复现实测结论 | 复现结论要有实测依据（如双渲染像素 diff 逐位一致），不许凭记忆写「可复现」 |

骨架即验收单：缺任一段或段序颠倒，不算 art 交付。

## 工具链与自检

### 工具选择

- **p5.js 生成艺术主栈**：库**已本地化**——`demos/art/lib/p5.min.js`（1,057,103 B，2026-10-04 自 jsdelivr 实拉数秒完成，v1.11.10）；经 `demos/art/build_art_html.py` 把库与 sketch 依次内联进单文件 HTML——**不硬依赖网络**，双击即开、离线可用（CDN 不可达时走「内联库代码」降级，本插件已把它做成默认路径）；
- **matplotlib 色彩理论图**：色轮用 `patches.Wedge`（十二色相、互补对角连线）、互补对照用 `Rectangle` 色样 + RGB 均值混合条、明度阶用 `colorsys.hls_to_rgb` 固定 H/S 步进 L；
- **降级艺术通路**：numpy+matplotlib 生成艺术（流场线积分静态图 / 奇怪吸引子散点，同样固定 numpy `np.random.seed`）；
- 场景源工程落 `demos/art/`（sketch JS + 构建器 + lib），产件落 `~/.nebflow/plots/`。

### 生成纪律（本场景第一红线组）

- **固定种子**：`randomSeed()` + `noiseSeed()` 写死在 sketch 头部，并把种子号 + 库版本印进画面戳记（可复现性声明内嵌于作品）；
- **版本锁定**：库版本写进戳记与文档——p5 2.x 与 1.x API 不兼容，本插件锁 **1.11.10**；
- **复现实测**：交付前双渲染像素 diff（PIL `ImageChops.difference(...).getbbox()` 为 None）才算「可复现」。

### 工具自检（实测 2026-10-04）

| 工具 | 用途 | 本机检测命令（实测） | 官方下载链接 | 链接验证记录 | 缺装降级策略 |
|---|---|---|---|---|---|
| p5.js（本地化内联） | 生成艺术主栈 | `ls -la demos/art/lib/p5.min.js` → 1,057,103 B（v1.11.10） | https://p5js.org/ · https://p5js.org/download/ | 200 · 200（2026-10-04） | ①内联本地库（默认，已落地）②numpy+matplotlib 生成艺术 |
| jsdelivr CDN | p5 备选在线引用 | `curl -sIL https://cdn.jsdelivr.net/npm/p5@1.11.10/lib/p5.min.js` → 末位 HTTP/1.1 200 | https://cdn.jsdelivr.net/npm/p5@1.11.10/lib/p5.min.js | 200（2026-10-04；实拉 1,057,103 B 数秒完成，HEAD 200 之外吞吐也验证过） | 本地内联 |
| unpkg CDN | p5 第二备选 | `curl -sIL https://unpkg.com/p5@1.11.10/lib/p5.min.js` → 200 | https://unpkg.com/p5@1.11.10/lib/p5.min.js | 200（2026-10-04） | jsdelivr → 本地内联 |
| matplotlib | 色彩理论图 + 降级艺术 | `<venv>\python.exe -c "import matplotlib; print(matplotlib.__version__)"` → 3.11.2 | https://matplotlib.org/ | 200（2026-10-04，README 链接验证表） | plotly（已装）或文字色值表 |
| numpy | 降级艺术/数值生成 | `<venv>\python.exe -c "import numpy; print(numpy.__version__)"` → 2.5.3 | https://pypi.org/project/numpy/ | 200（2026-10-04） | 纯 Python `math`/`random` |
| Chrome（headless 实拍） | 渲染实证截图 / 双渲染复现校验 | `chrome.exe --headless=new --screenshot=... --window-size=1124,1580 --virtual-time-budget=8000 file:///<html>` → 875-912 KB PNG | —（google.com/chrome 本网不可达；本机 Chrome 154.0.8037.93 为准） | 本机在位 | Edge `msedge.exe` 后备 |

全基础工具链接验证表见插件 README，直接引用不复制。`<venv>` = `C:\Users\Kai\.nebflow\tools\viz-venv\Scripts`；装包只进 viz-venv（core §5）。

### 降级策略

- p5 不可用（lib 丢失且两个 CDN 都不通）⇒ **numpy+matplotlib 生成艺术**（`np.random.seed` 固定种子，流场线积分/吸引子散点）⇒ 仍不行 ⇒ matplotlib patches 静态色彩构图；任何一步不空转、不手画 SVG（core §1.1/§5）；
- 字形缺失（如 U+2194 ↔ 不在 YaHei）⇒ 换 em dash 等在位字形，渲染后无 warning 再交付（实测踩过：2026-10-04）。

## 质量红线

1. **种子不固定 = 不算生成艺术交付**；复现结论必须来自实测 diff，不许写「应该可复现」；
2. 参数表—画面戳记—文档三处数值逐字一致（core §1.2）；
3. 示例/合成作品显著声明（图题 + 正文）；
4. 单文档 ≤3 图、marked v12 兼容（core §1.3/§4）；
5. 交互 HTML 走 `~/...` 绝对路径文件交付（core §2 白名单），不进 Pop（core §3）；
6. 禁手画 SVG（p5/matplotlib 程序化生成不算）、禁调 Pop。

## 示例

- `${SKILL_DIR}/../core/references/demos/art.md` —— 《溪山流霭》seed 20261004（p5 1.11.10 内联自包含 HTML + headless 实拍，双渲染逐位一致实测）+ 色轮/互补对照图 + 明度阶图（RYB 十二色相近似 Hex 示例教学数据）。

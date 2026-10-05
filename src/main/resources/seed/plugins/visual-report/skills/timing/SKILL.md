---
name: timing
description: 接口/总线时序报告（WaveDrom 波形）；时序图任务使用。
---

# timing — 时序图场景 skill（WaveDrom / WaveJSON）

接口、总线、握手类时序说明报告的专用场景。公共规范（嵌入路径 / 暗色 / 渲染器兼容 / 缺装降级总则）一律见 `core/SKILL.md`，本文只写场景增量；工具链接验证全表见套件 `README.md`。

- [1. 触发条件](#1-触发条件)
- [2. 报告骨架模板](#2-报告骨架模板)
- [3. 工具链与自检](#3-工具链与自检)
- [4. 质量红线](#4-质量红线)
- [5. 示例](#5-示例)

## 1. 触发条件

报告需要向读者解释「信号在时间轴上谁先谁后、谁引发谁、窗口多宽」即命中本场景。典型命中：

| 命中类型 | 例子 |
|---|---|
| 数字接口时序 | SPI / I²C / UART / QSPI / JTAG / 并行总线读写 |
| 采样与转换握手 | ADC / DAC：CONVST / BUSY / DRDY / EOC / CS / SCLK |
| 电源与复位 | 上电次序、复位释放、使能因果、看门狗窗口 |
| 协议事务 | 片选→命令→地址→数据的总线事务分解 |

不命中（只有数据趋势、结构关系，无时间因果）→ 回 `core/SKILL.md` 通用选型，不强行画时序图。

## 2. 报告骨架模板

固定三段式，顺序不变：**信号表 → 时序图 → 逐段解读（建立 / 保持 / 因果）**。

```markdown
## 信号表

| 信号 | 驱动方 | 有效电平/边沿 | 功能 |
|---|---|---|---|
| CONVST | 主机 | 下降沿触发 | 启动采样；上升沿保持并启动转换 |

## 时序图

（≤3 图；每图 = 渲染出的 SVG 用 ![...](~/...) 嵌入 + wavejson 源码完整附后）

## 逐段解读

（按图的分段时间窗逐段：建立 / 保持窗口核对；因果链 A↓ → B↑；每段给「图上值 vs 参数表值」）
```

骨架要求：信号表先行（读者先认识每根线再看图）；解读逐段对应图的分段时间窗；时间分辨率（1 slot = 多少 ns）写入图 caption；长阶段断轴压缩须在 caption 与解读双处声明。单文档 ≤3 图（core 铁律 3）。

## 3. 工具链与自检

> 实测日期 2026-10-04；`<venv>` = `C:\Users\Kai\.nebflow\tools\viz-venv\Scripts`。链接验证记录统一引用套件 `README.md` 全表（同日实测，引用前超 30 天须复测）。

| 工具 | 用途 | 本机检测命令（实测） | 官方下载链接 | 链接验证记录 | 缺装降级策略 |
|---|---|---|---|---|---|
| wavedrom-cli（npx，主途径） | WaveJSON → SVG/PNG | `npx -y wavedrom-cli --version` → 3.2.0；`-i a.json -s a.svg` 渲染实测落盘 | https://wavedrom.com/ · https://wavedrom.com/tutorial.html · 安装端点 https://registry.npmmirror.com/wavedrom | 200 · 200 · 200（README 全表） | python wavedrom → matplotlib 阶梯图 → 文本时序表 |
| wavedrom（python，viz-venv 备途径） | WaveJSON → SVG（库直调） | `<venv>\python.exe -c "import wavedrom; print(wavedrom.version)"` → 2.0.3.post3；`render_write(fin, fout)`（入/出均为已打开文件对象）实测落盘 | https://pypi.org/project/wavedrom/ | 200（README 全表） | matplotlib 阶梯图 → 文本时序表 |
| matplotlib（降级一） | 阶梯图近似时序（每信号一行、垂直跳变 `where='post'`） | `<venv>\python.exe -c "import matplotlib; print(matplotlib.__version__)"` → 3.11.2 | https://matplotlib.org/ | 200（README 全表） | 文本时序表 |
| 纯文本 ASCII 波形（降级二，末级） | 无任何图形工具时 | 任何环境可用 | — | — | 等宽代码块内画，仍须与文字零矛盾 |

渲染管线（实测命令，node 24.14.0 在位，npx 无需安装）：

```bash
# 主途径
npx -y wavedrom-cli -i <wavejson>.json -s <出图>.svg   # SVG 交付物
npx -y wavedrom-cli -i <wavejson>.json -p <预览>.png   # 仅 PNG 预览 / 目检
# 暗色适配（core §3 要求；幂等，只注入 CSS 不动几何）
C:\Users\Kai\.nebflow\tools\viz-venv\Scripts\python.exe C:\Users\Kai\.nebflow\skills\visual-report-suite\timing\wavedrom_dark.py <出图>.svg
# 备途径（python 2.0.3.post3）
<venv>\python.exe -c "import wavedrom; fin=open(r'<wavejson>.json',encoding='utf-8'); fout=open(r'<出图>.svg','w',encoding='utf-8'); wavedrom.render_write(fin,fout)"
```

已知坑（2026-10-04 实测，写图前必读）：

- **平电平段必须用 `.` 重复，禁止连写电平字符**：`"111111"` / `"000000"` 会在每个 slot 边界画出刻度凹口（引擎对显式重复字符改用带凹口的 `#1m1` / `#0m0` mark）；写 `"1....."` / `"0...."` 才是干净直线。
- 每 slot = 时钟恰一个周期（`p` 为 50% 占空比）。标注时长的安全写法 = slot 数 × 周期（如 16 slot × 50 ns = 800 ns）。
- MSYS 侧 `/tmp` 对 python / node 不可见：用 Windows 绝对路径或先 `cd`；传 Windows 路径参数加 `MSYS2_ARG_CONV_EXCL='*'`。
- CairoSVG 在本机缺原生 cairo DLL 不可用——PNG 目检用 wavedrom-cli `-p`，不要绕道 CairoSVG。
- `|` 断轴仅供长阶段压缩，必须配 caption 声明「非等比例」。

WaveJSON 速查：`1/0` 电平、`p/n` 时钟、`x` 不定态、`z` 高阻、`.` 重复上一字符、`|` 断轴、`=`/`2-9` 数据框（配 `data` 标签）、`node` 定位 + `edge` 标注（`A<->B` 双向、`A~>B` 单向，箭头串后文字即标签）；完整语法见官方教程 https://wavedrom.com/tutorial.html 。

## 4. 质量红线

1. **图文零矛盾（场景第一红线）**：时序先后、边沿对齐、覆盖窗口、因果关系不得与正文矛盾；图上每个 edge 标注数值必须能在信号表 / 参数表中逐字找到；派生数值给出精确算式，不四舍五入。
2. **禁止手画 SVG（core 铁律 1）**：时序图只能由 wavedrom / matplotlib 生成落盘；缺工具走 §3 降级链，不空转。
3. **必须真实渲染出文件再嵌入**：渲染命令跑完 → 核对文件存在且含 `<svg` → 才允许 `![](~/...)` 嵌入；渲染失败的 wavejson 不得进交付文档。出图落 `~/.nebflow/plots/**`（core §2 白名单）。
4. **wavejson 源码随文档完整交付**：每图源码以 json 代码块附在图后，保证可复现、可 diff。
5. **img 引用的 SVG 必须过暗色注入**（`wavedrom_dark.py`，core §3）。
6. **数值逐字（core 铁律 2）**：参数表是唯一数据源；源里没有的参数不标注——需要就先补进参数表。
7. **marked v12 兼容（core §4）**：`## ` 后带空格、GFM 表格列数齐、段落间空行。
8. **时间分辨率声明**：1 slot = 多少 ns 写进 caption；亚 slot 量（如半周期建立 / 保持 25 ns）以文字精确值为准，不在图上强行标注。

## 5. 示例

- `C:\Users\Kai\.nebflow\skills\visual-report-suite\demos\timing.md` —— ADC 采样 / SPI 读回握手 demo：信号表 → 双图（转换触发 + SPI 读回，wavedrom-cli 3.2.0 实渲染）→ 逐段解读（采样窗 / 因果链 / 建立保持核对）→ 完整 wavejson 源码 → 复现命令。
- WaveJSON 官方教程：https://wavedrom.com/tutorial.html（README 验证 200，2026-10-04）。

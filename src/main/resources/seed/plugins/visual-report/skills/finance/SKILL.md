---
name: finance
description: 行情复盘报告（mplfinance K线量价）；行情、K线、量价任务使用。
---

# finance — 金融·行情图场景 skill

> 上游公共规范：`../core/SKILL.md`（铁律 / 嵌入路径 / 暗色 / 渲染器兼容 / 缺装降级总则 / 自检模板）。本文件只写 finance 场景增量，通用规则一律引用 core，不复制漂移。

**独立成 skill 论证（一句）**：行情数据的核心图式是 OHLC 四值一体（开/高/低/收）+ 成交量 + 指标叠加的「K线/量价语汇」——拆成通用折线丢影线信息、拆成多图断量价联动，必须由专用渲染器（mplfinance）整图表达，故独立成场景。

## 触发条件

命中任一即用本场景骨架交付：

- **K线/量价图**：需求涉及蜡烛图、成交量、技术指标叠加（MA/MACD/BOLL 等）；
- **行情时序报告**：涨跌回顾、量价关系、波动率描述等以 OHLC 数据为源的交付；
- **模拟/教学行情演示**：合成 OHLC 数据的图式讲解与演示。

不命中即换场景：收盘价/净值等单序列趋势用 eda/office 通用折线；统计报表口径用 office；多标的相关矩阵用 eda。

## 报告骨架模板（五段，顺序固定）

**数据口径声明 → 数据准备 → 主图（K线+量+指标）→ 辅助图（可选）→ 读图要点与误区**

| 段 | 必含内容 | 硬约束 |
|---|---|---|
| 数据口径声明 | 数据来源：真实（来源+取数区间+复权口径）或模拟（合成模型+seed） | **模拟数据必须在正文首屏显著声明「模拟数据，非真实行情」**，写明合成模型与参数；真实数据缺口径不得交付 |
| 数据准备 | OHLCV 字段、区间、交易日数 | OHLC 合法性自检入脚本断言：High ≥ max(O,C)、Low ≤ min(O,C)、Volume > 0 |
| 主图 | mplfinance K线+成交量+指标（参数显式：MA20 就写 20 日简单移动平均） | 涨跌色约定写明（默认 A股红涨绿跌）；「模拟数据」声明进图内 title |
| 辅助图（可选） | 收盘线/回撤/分布等 matplotlib 辅助图 | 计入 ≤3 图预算（core §1.3），可从简或用文字读数替代 |
| 读图要点与误区 | 关键读数 + 误读对照表 | 数值逐字（core §1.2），全部可由脚本复算；指标滞后性等误区必须点破 |

骨架即验收单：缺任一段或段序颠倒，不算 finance 交付。

## 工具链与自检

### 工具选择

- **主渲染器：mplfinance**——K线/成交量/均线一格双栏整图表达（`mpf.plot(df, type='candle', volume=True, mav=20)`），`make_marketcolors` / `make_mpf_style` 控涨跌色与网格；
- **辅助：matplotlib**——收盘线、回撤、收益分布等非 OHLC 图式；pandas 负责整理与指标计算（`rolling`）；
- matplotlib 全局配置（YaHei 中文）与出图落位走 core §7 / core §2。

### 工具自检（实测 2026-10-04 晚）

| 工具 | 用途 | 本机检测命令（实测） | 官方下载链接 | 链接验证记录 | 缺装降级策略 |
|---|---|---|---|---|---|
| mplfinance | K线/成交量/指标主渲染 | `<venv>\python.exe -c "import mplfinance; print(mplfinance.__version__)"` → 0.12.10b0 | https://pypi.org/project/mplfinance/ · https://github.com/matplotlib/mplfinance | HTTP 200 · 200（2026-10-04 实测） | **matplotlib 程序化绘制蜡烛并封装**（影线 `vlines` + 实体 `Rectangle`/`bar`，量价 gridspec 双栏同构），属合法程序化绘制，非手画 SVG |
| matplotlib | 辅助图（收盘线/回撤/分布） | `<venv>\python.exe -c "import matplotlib; print(matplotlib.__version__)"` → 3.11.2 | https://matplotlib.org/ | HTTP 200（2026-10-04 实测，README 链接验证表同） | plotly（viz-venv 已装，含 candlestick）；再缺 ⇒ 文字行情表，不得手画 |
| pandas | OHLCV 整理/指标计算（rolling） | `<venv>\python.exe -c "import pandas; print(pandas.__version__)"` → 3.0.5 | https://pypi.org/project/pandas/ | HTTP 200（2026-10-04，README 链接验证表） | 纯 csv 读取 + 手写滑动均值循环（小数据量可行） |

numpy（2.5.3）为套件共用基线，实测记录见套件 README 链接验证表。`<venv>` = `C:\Users\Kai\.nebflow\tools\viz-venv\Scripts`。装包只进 viz-venv（core §5）。API 名注意：0.12.10b0 是 `mpf.make_marketcolors` / `mpf.make_mpf_style`（无下划线别名不存在）。

### 场景规范（本场景红线细则）

- **数据诚实第一**：模拟数据「正文首屏 + 图内 title」双声明；模拟数据禁止挂真实标的代码/名称，标题含「演示/模拟」字样；
- **涨跌色约定随市场惯例并显式注明**：默认 A股/东亚红涨绿跌（本套件用 `#d64541`/`#2e9e5b`，两主题可读）；用户为美股语境换绿涨红跌并声明；
- **指标参数显式**：MA20、MACD(12,26,9) 之类写全参数；MA 前 N-1 日无效不画线（起点写明）；
- **真实数据必写复权口径**（前权/后权/不复权）与取数时间戳；模拟数据写明「无复权概念」。

### 降级策略

- mplfinance 不可用 ⇒ matplotlib 程序化蜡烛封装 `draw_ohlcv()`：影线 `vlines`、实体 `Rectangle`、成交量副栏 gridspec、红涨绿跌同构——合法程序化绘制（core §1.1 禁的是手写 `<path>`/文本框箭头伪画，不是数据驱动的程序绘图）；
- pandas 不可用 ⇒ 纯 csv + 手写均值；matplotlib 不可用 ⇒ plotly candlestick ⇒ 文字行情表；任何一级不空转、不手画。

## 质量红线

1. **数据来源双声明**：模拟（首屏+图内）/真实（来源+区间+复权口径）缺任一不算交付；
2. **OHLC 合法性断言**（High≥max(O,C)、Low≤min(O,C)、Volume>0）入脚本；
3. **指标参数显式、色约定声明、均线无效起点不留白线**（见场景规范）；
4. **数值逐字**（core §1.2）：正文关键读数必须可由脚本复算；
5. **禁止手画 SVG**（core §1.1）；单文档 ≤3 图、marked v12 兼容（core §1.3/§4）；出图落 core §2 白名单。

## 示例

- `../demos/finance.md` —— 合成 OHLC 小样：seed=42 几何随机游走 60 个交易日，mplfinance K线+成交量+MA20 整图（红涨绿跌，图内 title 与底注双「模拟数据」声明，亮暗双主题适配）；OHLC 合法性断言通过，全部读数脚本可复算（脚本 `demos/finance_demo_gen.py`）。

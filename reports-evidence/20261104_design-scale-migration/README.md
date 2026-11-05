# design-scale 迁移批 · 证据目录（2026-11-04）

判据唯一来源：`~/.stand/projects/stand/.stand/docs/20261104_143000_design-scale-spec__delegate-nebflow-be8349d0.md`（设计规格刻度定版）。
本批性质：**等值重构**——裸字面量 → 解析后 computed 值**完全相同**的令牌。零像素变化是硬要求，
所以「换不到同值令牌的一律不动」，进「需作者裁定」清单。

## 一句话结论

| 面 | 改前裸字面量 | 改后 | 已迁移 | 需作者裁定（未动） |
|---|---|---|---|---|
| 字号 font-size | 776 | 200 | **576** 条声明 | 154 处（10px×59 / 14px×46 / 12.5px×24 / 11.5px×23 …） |
| 圆角 border-radius | 847 | 272 | **577** 条声明 | 96 处（7px×25 / 3px×21 / 5px×16 / 9999px×14 …）+ 形态档 175 处 |
| 间距 padding/margin/gap | 3215 原子 | 761 原子 | **2463** 个原子 / 1877 条声明 | 757 原子（3px×75 / 5px×61 / 1px×44 / 14px×29 / 7px×28 …） |
| **合计** | | | **3616 处** | |

> 计尺口径差异（如实记）：`measure-tokens.mjs` 按「实例/原子」计数，`codemod` 按「声明」计数；
> 圆角两侧差 2 条 = 组合值 `12px 12px 0 0` 被计尺按首 token 计入 + 同行多声明的切分差。
> 改后已无任何**单值**圆角裸字面量可迁（残留「可迁移 1」就是那条组合值本身）。

## 为什么本批不是 525 处

任务书写的 525 = 定版文档 §5 的**收敛**清单（7px 归 8px 档、10px 归 11px 档那种）。
那会改像素，与「零像素变化」这条第一硬要求直接冲突。按零像素判据改判后：

- **可无损迁移集 = 3616**（比 525 大得多，因为「档内值」的令牌化此前从未做过）；
- **改造值集（原 525 的口径）→ 全进「需作者裁定」清单**，一处没动。

## 文件

| 文件 | 是什么 |
|---|---|
| `measure-tokens.mjs` | 计尺：四类裸字面量逐值直方图，支持 `--web-root` 对着纯净树量（所以「改前/改后」用同一把尺） |
| `codemod-design-scale.mjs` | 迁移器：`--class fs|radius|space`，缺省 dry-run，`--apply` 落盘；`--web-root` 打纯净树出「改前有多少处该迁」 |
| `premap-{fs,radius,space}.json` | 迁移器打在**纯净 HEAD 树**上的 dry-run 明细（逐声明 from→to + 需裁定清单）。映射表的数据源 |
| `needs-ruling-{fs,radius,space}.json` | 当前树的需裁定明细（逐文件逐行） |
| `mapping-table.md` | **迁移映射表**：原始值 → 令牌名 → 解析值 → 出现处数（逐类），附需裁定清单 |
| `verify-zero-pixel.mjs` | 零像素判据器（见下「判据」） |
| `compare-visual.mjs` | 两目录逐字节/逐像素对照（归因实验用） |
| `ab-visual-attribution.sh` | A/B 归因装置：用 `--web-root` 指向纯净 HEAD 树，交替采集 A/B |
| `gate-exits.txt` + `gate-*.log` | 八道门禁的 exit code 与原始输出 |
| `visual-verdict-seg3.log` | 段3（含全量）的最终视觉判词 |
| `visual/` | 原始截图（**不入库**，15MB；脚本可复算） |

## 判据：零像素怎么证的

`make check-visual VISUAL_MAX_RATIO=0` 在本机**改前就已经不是全绿**：darwin-chromium-151 入库基准
与当前渲染本就有存量差异（canvas-code-tab-dark diff=63px）且 16 个场景无本平台基准。
拿它判「本批有没有改像素」会把存量噪声算到本批头上。故改用**同一台机、前后两拍**的对照：

1. **改前参照**：纯净树连拍两拍（`pre-change-run1/2`）→ 顺带量出本机噪声带；
2. **A/B 归因**：`--web-root` 指向 `git archive HEAD` 抽出的纯净 web 树，与迁移树**交替**采集
   （`ab-A1/B1/A2/B2`、`ab2-A3/A4/B3/B4`）——这样「同树两拍」与「跨树两拍」可以分开比；
3. **判词三级**（`verify-zero-pixel.mjs`）：
   - `SHA-SAME` 前后 sha256 逐字节相同（最强证据）；
   - `NOISE-BOUND` 该场景**在同一棵树连拍两拍时也抖过**（5–97px），且前后差异 ≤ 该场景同树抖动上限；
   - `MULTI-STATE` 迁移树的某一拍**逐字节复现了改前那一拍** ⇒ 渲染多态（迁移若真改了像素，
     复现改前字节在逻辑上不可能）；
   - `DRIFT` 以上都不满足 ⇒ 真漂移，必须解释或回退。

本机实测噪声带（同一棵树、零代码改动的两拍之间就会抖）：

| 场景 | 抖动量 | 性质 |
|---|---|---|
| canvas-markdown-tab-dark / code-tab-dark / **-light** | 5–16px | 底边 y=860..864 的滚动条角 AA |
| canvas-txt-tab-dark | 9px | 同上 |
| canvas-html-tab-dark / -light | 17–29px | monaco 整个 iframe 的 AA 抖动（bbox 覆盖整帧） |
| settings-llm-empty-light | 97px | (941,747)-(966,768) 光标/动效闪烁 |

**决定性一条**：`canvas-html-tab-light` 迁移后某一拍与**改前那一拍逐字节相同**（sha `2e74685343ca`），
而同一棵迁移树的另一拍差 17px；同一棵纯净树的 dark 两拍之间也差 29px。⇒ 该场景渲染与代码无关。

## 三段各自的收口

| 段 | 迁移量 | 门禁 | 视觉判词 |
|---|---|---|---|
| 段1 字号 | 576 声明 | 全 exit 0 | 28 场景：SHA-SAME 25 · NOISE-BOUND 3 · DRIFT 0 |
| 段2 圆角 | 577 声明 | 全 exit 0 | 28 场景：SHA-SAME 24 · NOISE-BOUND 4 · DRIFT 0 |
| 段3 间距 | 1877 声明 / 2463 原子 | 全 exit 0 | 28 场景：SHA-SAME 22 · NOISE-BOUND 6 · DRIFT 0 |

## 已知坑（留给后来人）

1. **`--update` 会直接写 `tests/visual/baseline/<platform>/`**（不是 Makefile 注释里说的
   `.nebflow/` 评审区）。本批每次采集后立刻 `git restore --source=HEAD` + 删未跟踪文件复原，
   `git status tests/visual` 收尾为 0 改动。要在别的批里跑 `--update`，先想清楚这一点。
2. **JS 样式串以 `', '` 分隔时，一条声明会把整行剩余部分吞进自己的值**——迁移器第一版因此
   漏迁了 `staleTabHint.js` 一类写法。修法：值在第一个引号处截断，并把扫描游标拨回值尾。
3. **负间距**（`-1px` 等 21 处）无法写成 `var()`，必须 `calc(-1 * var(--ds-space-1))`；
   本批不碰（属作者裁定）。
4. `check-js-types` 在本机跑不起来（`node_modules/typescript` 未装，exit 2），不是本批引入。

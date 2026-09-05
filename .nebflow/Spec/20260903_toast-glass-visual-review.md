> 关联资产仍在 ~/.nebflow/docs/Nebflow/assets/（暂留）

# 右下角通知（toast）毛玻璃重设计——视觉验收报告

- 日期：2026-09-03 21:40
- 评审人：design-engineer（视觉评审关卡，下游合并闸门）
- 评审对象：toast 毛玻璃重设计（分支 `toast-glass`，commit `bd76d669`，merge commit `7ba589f3`）
- 实施报告：`~/.nebflow/docs/Nebflow/20260903_toast-glass-impl.md`
- 评审依据：① `.glass-control` 材质语言（sapphire.css:137-147 + token 定义 :110-135）；② 作者裁定三条（2026-09-03 21:09：毛玻璃方案 / 去左侧强调条 / 类型语义图标+文字色，克制不花哨）；③ 暗亮双主题

## 〇、评审方式与取证说明（独立性声明）

- **不信任上游自评**：本人亲自 Read 全部 4 张截图逐张过目（上游 2 张 + 评审补拍 2 张），并独立起 8100 端口静态服务（非 8080，用毕核验 PID 85962 cwd=/tmp/toast-review 非宿主后回收）渲染**真实 modal.js/modal.css**（main@7ba589f3 只读复制到 /tmp，主仓零改动）自采截图 + 计算样式取证。
- **补拍动因**：上游截图仅含 success/error 两类型，info 类型无视觉证据——评审补拍三类型 × 双主题，并同步取计算样式做 DOM 级交叉验证。
- **状态差异记录**：节点书称「实施分支在 worktree `.nebflow/worktrees/toast-glass`」，实际查证：代码已合并进 main（HEAD=`7ba589f3`），worktree 与分支已删除。评审改为对 main 实码只读核对 + /tmp 副本渲染，取证对象与合并结果完全一致，结论等效。
- 交叉验证引用：上游 spec `tests/toast-glass.spec.mjs` 27/27（含图标计算色/border-left-width/backdrop blur DOM 断言）；本评审独立复测计算样式 6 组（3 类型 × 2 主题）全部吻合。

## 一、逐项评审表

| # | 评审项 | 判定 | 证据描述 |
|---|---|---|---|
| 1 | 毛玻璃质感成立（半透明透后景 / blur 生效 / 细边框+内缘 / 非实心色块） | **PASS** | 4 张截图一致：toast 体下后景斜条纹明显透出且被柔化（frost 成立），非实心色块。实测计算样式：暗色 `background: rgba(255,255,255,0.05)` / 亮色 `rgba(255,255,255,0.45)`，`backdrop-filter: blur(10px) saturate(1.2)`，与 sapphire.css token 定义逐值一致；1px 细边框与内缘高光在目视下可辨（亮主题上缘高光带清晰）。对照 sendbtn/PR #44 实心化一票否决先例——本件无实心化迹象 |
| 2 | 左侧 accent strip 确认消失 | **PASS** | 实码 grep：modal.css:1079-1103 仅 `border: 1px solid var(--glass-control-border)`，三条 `border-left: 3px` 规则已删；实测 6 组计算样式 `border-left-width` 全部 = `1px solid`；目视左缘与其余三缘同质，无任何类型下出现色条 |
| 3 | 类型语义不依赖色条仍一眼可辨（图标色正确 / 正文可读性） | **PASS** | 图标存在且字形正确：error `✕` / info `!` / success `✓`；实测计算色 error `rgb(244,67,54)`（=#f44336 裁定值）、info `rgb(7,193,96)`（=--color-primary）、success `rgb(76,175,80)`（=--color-success），逐值吻合。正文可读性：暗色 `--color-text` 实测 `rgb(232,234,237)` 不发灰、亮色 `rgb(27,30,38)` 不刺眼，玻璃底上双主题均清晰。克制性：仅前导字符图标着色，无底色浸染无花哨装饰，符合裁定 |
| 4 | 布局与边界（右下定位 / 圆角 / 内边距 / 长文案 / link 变体） | **PASS** | 实测 `position: fixed`（bottom/right 20px 实码 :1081-1082）、`border-radius: 12px`、padding 12px 16px、max-width 400px；评审补拍含中文长文案「任务已创建：toast 毛玻璃重设计」单行容纳无溢出。`.nebflow-toast-link` 变体无截图——实码原样保留（:1118-1126 零改动）+ 上游 spec A3 DOM 断言（inline-block/pointer/blur 生效）交叉验证，回归风险判无（规则未触碰） |
| 5 | 双主题一致性 | **PASS** | 同一 `--glass-control-*` token 族双主题各自成立：暗色适配由 token 自身媒体查询（sapphire.css:122-135）承载，组件级暗色覆盖块删除属正确简化而非妥协；两主题截图设计语言统一（同圆角/同边框/同图标/同排版），无单主题专属补丁 |
| 6 | 上游自评与实图/实码一致性抽查 | **PASS** | 逐项核对无矛盾：token 选值声称 vs modal.css:1092-1101 实码 ✓；图标方案声称（✕/!/✓、14px/600、margin-right 8px、aria-hidden）vs modal.js:148-171 + modal.css:1109-1117 实码 ✓；「blur(10px) saturate(1.2)」声称 vs 评审独立实测 ✓；「border-left 1px」声称 vs 实测 ✓；生命周期/调用方零改动声称 vs modal.js:154-171 实码（4000+300ms 原样）✓。一处流程性差异（worktree 已删/已合并进 main）如实记录于 §〇，不构成视觉缺陷 |

## 二、逐张截图核验记录

| 截图 | 已过目 | 要点 |
|---|---|---|
| `~/.nebflow/docs/Nebflow/20260903_toast-glass-dark.png`（上游） | ✓ | 暗色，success（绿 ✓「已在文件浏览器中打开」）+ error（红 ✕「连接已断开，正在重试」）；蓝灰玻璃体透条纹、细边框可见、无色条、白字清晰 |
| `~/.nebflow/docs/Nebflow/20260903_toast-glass-light.png`（上游） | ✓ | 亮色，同两条目；白色半透明玻璃 frost 明显、深字不刺眼、左缘无色条 |
| `~/.nebflow/docs/Nebflow/assets/toast-review-dark.png`（评审补拍） | ✓ | 暗色三类型齐备：红 ✕ / 绿 ! / 绿 ✓；info 类型玻璃质感与可读性与另两类一致 |
| `~/.nebflow/docs/Nebflow/assets/toast-review-light.png`（评审补拍） | ✓ | 亮色三类型齐备；三条左缘逐条核对均无色条；圆角/内边距/间距协调 |

## 三、修正建议（数值级，全部非阻塞）

1. **info 与 success 图标色同为绿色系**（#07c160 vs #4caf50，色差 Δ 小）：语义区分实际由字形（`!` vs `✓`）承载。此为作者裁定指定方案（info=--color-primary），**合规不阻塞**；若后续觉混淆，数值级备选：info 图标色改 `--color-text-muted`（#8b8e96 / 暗色 #7b7e88）或字形换 `i`，需另行裁定，本评审不擅自要求。
2. **error 图标色 #f44336 未做 WCAG 校验**（上游遗留 3）：暗色下玻璃等效底近黑，目视对比充足；建议后续统一错误色 token 时一并校验，目标对比度 ≥4.5:1。
3. **info `!` 字形偏细**（14px/600 下笔画窄）：目视可辨、合规；如增强可辨度可 `font-weight: 600→700`，非阻塞。
4. 多条 toast 同位重叠为现状行为（上游遗留 1），不在本裁定范围内，不扣减判定。

## 四、判定与依据

清单 1-6 **全部 PASS**：毛玻璃材质对齐 `.glass-control` 标准且逐值可溯（无自造色值）、accent strip 彻底移除（实码+实测+目视三重确认）、类型语义图标化按裁定逐字落地、双主题一致性成立、上游自评与实码实图零矛盾。无实心化一票否决项，无阻塞缺陷。

**最终判定：PASS（可合并）**

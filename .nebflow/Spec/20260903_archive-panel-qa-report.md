# Flow Map 归档面板 v3 — QA 独立验收报告（闸门触发：未验收）

- 日期：2026-09-04 · 验收人：qa-frontend（barrier 节点「QA验收-归档面板」）
- 验收对象：worktree `flowmap-archive-panel` 分支 @ `b4a831a2`（3987dd1e 实施 → 0000de17 修复 → b4a831a2 spec），**零改动**
- 上游：视觉评审-归档面板对照原型（n-f5724bf5）· **最终判定 FAIL** · 报告 `20260903_archive-panel-visual-review.md` · ~/.nebflow commit `d3fea7e`
- 规格依据：`20260903_flowmap-archive-panel-spec.md`（frozen，§17 C1–C9 / §18 C10–C13）

---

## 1. 闸门触发记录

按节点协议（barrier 闸门）：**上游视觉评审最终判定 = FAIL → 不执行验收、不动分支任何文件、原样逐条转述缺陷清单 + 「未验收」声明后直接结束。**

- 验收清单 1–6（代码审查 / 88 断言独立重跑 / 回归复跑 / 数据链路复核 / 边界断言 / 既有失败归因核对）**全部未执行**。
- 88 断言未独立重跑（无重跑数字）；回归（flowmap-realtime 等既有 spec、check-js-types、verify-i18n-sweep、node --check）未复跑。
- 无 [verify-fix] commit；分支 flowmap-archive-panel 未产生任何新提交；未合并、未 push。

## 2. 分支状态取证（只读）

- `git status --short` → 空（clean，无未提交改动）
- `git log -1` → `b4a831a2 test(flowmap-archive): 88 断言移植的产品 Playwright spec + 夹具 + 降级静态断言`（与上游评审对象 commit 一致）
- 本节点对分支零文件改动、零提交。

## 3. 上游视觉评审缺陷清单（原样转述，逐条）

- **D1 · 375px 详情/面板左裁 ~49px**（评审清单 5）：`.fm-archive-panel` 与 `.fm-detail` 的 `max-width: calc(100vw - 24px)` 以视口为口径，而定位上下文（host）仅 318px 宽且自身右缘溢出视口 16px → 面板左缘 24px 落在 host 左缘（73px）之外被裁。上游「A20.1/A20.2 系 container-type 实验扰动已回退」的声称经评审在当前提交（b4a831a2）独立复跑证伪——**375 破版为现状**，对照原型 narrow-375 完整可见为明确回归。
- **D2 · host<780px 时 dock-left 失效**（评审清单 4）：`@media (max-width: 799px)` 按视口判定，但并排可行性取决于 host 宽（需求 = 380 面板 + 8 间隙 + 360 详情 + 16+16 边距 = 780px）。host≈470px（就地分栏常见布局）下 `right:404px` 把详情推出 host 被裁/被盖，§5.8b「详情面板必须完整可见」不成立。**需作者拍板**（dock 口径改 host 宽度是 §5.8b 的修订）。
- **D3 · 悬浮钮/徽章遮挡头部摘要**（评审清单 2）：钮区 [hostRight−56 .. hostRight] 与右对齐摘要常驻重叠（实测重叠 10px），t1/t4/t5/t7 均见「链未齐终态保留 3」尾字被遮——被遮的恰是 v3 新增的 retained 计数。原型摘要左对齐无此碰撞。

### 3.1 与原型差异表（转述）

| 项 | 原型（视觉基准） | 产品实现 | 判定 |
|---|---|---|---|
| 悬浮钮 vs 摘要 | 摘要左对齐，钮区无碰撞 | 摘要右对齐延伸至右缘，与钮/徽章重叠 | 缺陷（D3） |
| dock-left 并排 | 1440 全宽画布并排零重叠 | host≥780px 成立；host≈470px 就地分栏失效 | 条件性缺陷（D2） |
| 375 窄视口 | 详情完整可见、无破版 | 左裁 ~49px、内容残缺 | 缺陷（D1） |
| 面板/条目/徽章/玻璃材质 | mailbox 链条目、玻璃、无遮罩 | 一致（含暗色、reduced-motion、TTL 标签） | 一致 |
| 计数口径 | 徽章 10（原型演示夹具） | 徽章 8（产品夹具 8 链 21 节点） | 数据差异，非缺陷 |

### 3.2 数值级修正建议（转述上游）

- **D1**：`.fm-archive-panel` / `.fm-detail` 的 `max-width: calc(100vw - 24px)` → `calc(100% - 32px)`（% 相对 host；right:16 不变）。按实测推算改后 375 下详情 [89..375] 完整落入可视区。附带排查 `.flowmap-view-body` 375 下右缘溢出视口 16px（分栏 min-width，超出本特性面）。
- **D2**：`updateDetailDock` 改按 `ctx.host.clientWidth >= 780` 切 `.dock-left`（JS 口径），CSS 视口媒体查询删除；host<780 → 详情同位 right:16 浮前。**需作者拍板**。
- **D3**（推荐）：`.flowmap-card-header` 加 `padding-right: 56px`（40 钮 + 16 间距），保住 §4.1 的 16/16 锚定；备选 fab `top:16→56px`（偏离口径，不推荐）。

上游评审另附证据纪律备注（不计缺陷）：`t2-detail-dock.png` 图名声称 dock 实为非 dock 态；降级断言引用的「首轮 PASS 证据」须可复跑佐证（A20.1/A20.2 即因此暴露）。

## 4. 未验收声明

本次 QA 验收**未执行**。本报告不含任何 QA 自测结果；验收清单 1–6 的 PASS/FAIL 判定**均不可由本节点给出**（闸门短路）。上游评审中已 PASS 的项（主图过滤链语义、mailbox 面板形态、hover 联动、双主题/reduced-motion）仅为视觉评审结论，不构成 QA 验收结论。

## 5. 最终判定

**最终判定：FAIL（不可合并）**

理由：上游视觉评审存在未关闭缺陷 D1 / D2 / D3（详 §3），按闸门规则 QA 不验收。下游「合并-归档面板进main」应**拒绝合并**分支 `flowmap-archive-panel`，直至三缺陷修复（D1/D3 数值级可修）+ D2 经作者拍板，并重新走「视觉评审 → QA 验收」链路。

---

## 附：产出结果文本（节点输出）

- 逐项验收表：1–6 全部 **未执行（上游 FAIL 闸门短路）**，无证据生成
- [verify-fix] commit：无（分支零改动）
- 88 断言重跑数字：未重跑
- 回归结论：未复跑
- 报告路径：`~/.nebflow/docs/Nebflow/20260903_archive-panel-qa-report.md`
- ~/.nebflow commit：见会话输出
- **最终判定：FAIL（不可合并）**
- 上游视觉评审要点一句话：纯前端整链归档引擎与三层悬浮 UI 已按 frozen 规格落地、功能语义大体忠实，但三处布局几何缺陷（375 详情左裁 ~49px / host<780 时 dock-left 失效 / 悬浮钮遮挡 retained 摘要）未关闭，其中 375 破版经评审独立复跑证实为现状而非上游声称的实验扰动。

# Flow Map 归档面板 v3 — 合并收编报告（闸门触发：未合并）

- 日期：2026-09-04 · 执行：Coder（barrier 节点「合并-归档面板进main」）
- 上游：QA验收-归档面板（n-fa034df9）· 报告 `20260903_archive-panel-qa-report.md` · ~/.nebflow commit `e2b090d`
- 上游上游：视觉评审-归档面板对照原型（n-f5724bf5）· 报告 `20260903_archive-panel-visual-review.md` · ~/.nebflow commit `d3fea7e`
- 合并对象（未合并）：分支 `flowmap-archive-panel` @ `b4a831a2`（3987dd1e 实施 → 0000de17 修复 → b4a831a2 spec）

---

## 1. 判定复述（双闸）

- **视觉评审闸（n-f5724bf5）：FAIL** — 3 缺陷未关闭（D1 375px 详情/面板左裁 ~49px；D2 host<780px 时 dock-left 失效需作者拍板；D3 悬浮钮/徽章遮挡头部摘要遮蔽 v3 新增 retained 计数尾字），且 375 破版经评审在 b4a831a2 独立复跑证实为现状。
- **QA 验收闸（n-fa034df9）：FAIL（不可合并）** — 闸门短路，验收清单 1–6 全部未执行，无 QA 自测证据；按闸门规则拒绝合并，直至缺陷修复并重走「视觉评审 → QA 验收」链路。

## 2. 本轮铁律执行声明

**最终判定 FAIL → 未合并。** 本节点对主仓 `/Users/dev/Claude code/Nebflow` **零文件改动、零 merge、零 commit、零清理、未 push、未碰 origin**；严禁自作修正后合并的铁律已执行。未做任何修正（修正属作者侧后续修复任务，非本合并节点职责）。

## 3. 缺陷清单（原样转述上游，逐条）

- **D1 · 375px 详情/面板左裁 ~49px**：`.fm-archive-panel` 与 `.fm-detail` 的 `max-width: calc(100vw - 24px)` 以视口为口径，而定位上下文（host）仅 318px 宽且自身右缘溢出视口 16px → 面板左缘落在 host 左缘之外被裁。上游「container-type 实验扰动已回退、首轮 PASS」的声称经评审在当前提交独立复跑**证伪**——375 破版为现状，对照原型 narrow-375 完整可见为明确回归。
- **D2 · host<780px 时 dock-left 失效**：`@media (max-width: 799px)` 按视口判定，但并排可行性取决于 host 宽（需求 = 380 面板 + 8 间隙 + 360 详情 + 16+16 边距 = 780px）。host≈470px（就地分栏常见布局）下 `right:404px` 把详情推出可视区被裁/被盖，§5.8b「详情面板必须完整可见」不成立。**需作者拍板**。
- **D3 · 悬浮钮/徽章遮挡头部摘要**：钮区与右对齐摘要常驻重叠（实测重叠 10px），t1/t4/t5/t7 均见「链未齐终态保留 3」尾字被遮——被遮的恰是 v3 新增的 retained 计数。原型摘要左对齐无此碰撞。

数值级修正建议（转述上游）：D1 `max-width` → `calc(100% - 32px)`；D2 `updateDetailDock` 改按 `ctx.host.clientWidth >= 780` 切 dock（JS 口径，CSS 视口媒体查询删除），需作者拍板；D3（推荐）`.flowmap-card-header` 加 `padding-right: 56px`。

## 4. 冲突预判 vs 实际 / 子集复跑 / 清理记录

- **三点冲突预判与逐文件融合**：**未执行**（闸门短路——未发起 `git merge`，无冲突面产生，无融合决策可记）。
- **合并后 spec 子集复跑**：**未执行**（无合并即无复跑对象；分支上全绿记录不因本节点变化）。
- **清理记录：全部保留，未执行清理**——只读取证快照（2026-09-04）：
  - 主仓 HEAD：`main` @ `f1e8b5a3`（本节点在其上零改动）
  - 分支 `flowmap-archive-panel` @ `b4a831a2`（与评审/验收对象一致，分支保留）
  - worktree `.nebflow/worktrees/flowmap-archive-panel`：`git status --porcelain` 干净，目录保留
  - 软链 `.nebflow/flowmap-archive-panel → worktrees/flowmap-archive-panel`：存在，保留未触碰
  - `git worktree list` 中该 worktree 登记在册，未 remove

## 5. 生效说明

未合并 → 无任何生效事项：v3 代码未进 main，主仓前端产物与宿主行为零变化。后续修复版真正合并后，生效仍需前端产物重建 + 宿主重启（随重启包，本批不做）。

## 6. 遗留问题与后续路径

1. D1 / D3 数值级修复（建议值见 §3，作者侧在分支 worktree 内完成并落 [verify-fix] commit）。
2. D2 需作者拍板（dock 口径改 host 宽度构成对 §5.8b 的修订）。
3. 修复后重走「视觉评审对照原型 → QA 独立验收」双闸链路，双 PASS 后由下游合并节点重新执行合并 + 冲突预判 + 子集复跑 + 清理（本报告模板可复用）。
4. 在飞并行节点（proj-archive-btn / toast-glass / 超时链）届时可能已落 main，合并时以当时 HEAD 重新做三点冲突预判。

## 7. 上游 QA 验收要点一句话转述

纯前端整链归档引擎（120s 聚簇 + 24h TTL + 墓碑防复活）与三层悬浮 UI 已按 frozen 规格落地、功能语义大体忠实，但三处布局几何缺陷（375 详情左裁 / 窄 host dock-left 失效 / 钮撞 retained 摘要）未关闭，且 375 破版经独立复跑证实为现状，故 QA 闸门短路未验收、拒绝合并。

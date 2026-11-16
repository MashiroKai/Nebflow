# canvas-inline-diff-review 回执

## 分支与提交

- 分支：`feat/canvas-inline-diff-review`（只落分支，未合并、未推送）
- 最新 commit：`246cb0f16`（17 files changed, +1904 / −1099）

## 改动文件清单

后端：
- `src/main/scala/nebflow/core/ask/AskEditReviews.scala`（新）——直接落盘账本单源：applyChange/accept/reject/dismiss、hunksOf（difflib + 相邻未变行 ≤2 并块）、stale 闸、原子写
- `src/main/scala/nebflow/core/tools/AskEditTools.scala`（重写）——ask_edit/ask_write 直接落盘 + pushAppliedFrame
- `src/main/scala/nebflow/gateway/WsAskHandlers.scala`——resolve 三腿（accept/reject/dismiss）+ askState.review
- `src/main/scala/nebflow/core/ask/AskEditProposals.scala`（删）——提案制整体退役
- `src/main/scala/nebflow/core/ask/AskContextAssembler.scala`、`AskSessions.scala`、`agent/AgentSessionExecution.scala`——提示词工具面描述/注释更新
- `src/test/scala/nebflow/core/ask/AskEditReviewSpec.scala`（新）／`AskEditProposalSpec.scala`（删）

前端：
- `src/main/resources/web/js/askEditReview.js`（新）——canvas 审阅页签（原文档内联 diff、折叠、逐块/全量、快捷键、冲突条）
- `src/main/resources/web/js/askDock.js`——摘除 diff 卡路径（INTERNAL_CLASSES 9 类、click 委托、edits 账、diffLines/editCardNode/upsertEdit/renderEditsIfActive、askState edits 对账），askEdit* 帧改路由 askEditReview
- `src/main/resources/web/js/main.js`——initAskEditReview 接线
- `src/main/resources/web/css/ask.css`——删 .ask-edit-* 块；`css/diffView.css`——加 .nf-ar-* 族

测试/交付：
- `tests/canvas-ask-inline-review.spec.mjs`（新）／`tests/canvas-ask-edit-diff.spec.mjs`（删）
- `delivery/canvas-inline-diff-review/research-inline-diff.md`（A 段调研）

## 与 feat/ui-unify-panels 重叠

零文件重叠（`git diff --name-only main...两分支` 求交为空，两分支各 17 文件）。本批未触碰面板/三区带/待办/去地址面。

## 测试

- 前端 spec 红→绿：红 = HEAD 基线树（/tmp/ar-red symlink 农场 + `git show` 基线四文件）驱动同 spec → 审阅页签超时决定性 FAIL（evidence/canvas-ask-inline-review/spec-red-baseline.log）；绿 = 工作树 26/26 断言（spec-green.log）。红绿两次复跑均复现。
- 本地门禁：eslint（3 个改动 JS）零告警；check-circular PASS；check-component-contract 本批文件零命中（现存 2 项 FAIL 属 `js/gesture/backSwipe.js`，HEAD 既有，非本批引入）。
- 后端：**未跑**（会话无 sbt 门禁权限）——AskEditReviewSpec.scala 编译与红绿、scalafmt 格式，请代跑门禁。

## 截图（均为夹具，非运行态）

`evidence/canvas-ask-inline-review/shots/`：r1-applied.png（审阅页签呈现）、r2-hunk-accepted.png（逐块接受）、r3-accept-all-cleared.png（全部接受后清账）、r5-hunk-rejected.png（逐块拒绝）、r6-conflict.png（冲突过期显式条）。

## 未完成项与已知边界

1. **运行态截图与冲突路径运行态验证未做**：本机无引擎进程、无法 sbt 起沙箱实例。冲突路径已有夹具级验证（R6：error → 冲突条 → 重试重发 → dismiss 收口）；运行态验证需上游起实例（`sbt "run --home /tmp/<home> --port <port> --no-browser"`）后补。
2. **后端 spec 待门禁**：编译错误或断言失败由代跑循环续修。
3. dismiss 清账会对文件页签走一次 refreshFileTabByPath（呈现盘上现状，零写盘）——语义如此设计，非缺陷。
4. spec 驱动面为 mock WS（结构断言，不卡像素）；spec 截图 harness 会与 R7 清账腿的 300ms 文件刷新防抖竞态，已在 spec 内用 settle sleep 消解（真实现无此交错问题，R1-R6 各态验证均过）。

---
type: implementation
status: landed
title: 阅读器 P2 大文件批——PDF 打开闸拆分解禁 1 GiB + 视口懒渲染四护栏
date: 2026-10-30
batch: delegate-nebflow-f8da1d4a
tags: [reader, pdf, large-file, lazy-render, gate-split, guardrails, a7, a8]
related:
  - "[[20261030_181655_reader-experience-design__delegate-nebflow-38afccc8.zh-CN]]"
  - "[[20261030_background-task-lifecycle.zh-CN]]"
---

# 阅读器 P2 大文件批（实施回帖）

> **批性质**　实施批（作者已全批设计稿）。锚 [[20261030_181655_reader-experience-design__delegate-nebflow-38afccc8.zh-CN]] §五 PDF 大文件（§5.1 闸门定案 / §5.2 懒渲染 / §5.3 护栏）+ 验收表 A7/A8。范围只碰 PDF 查看链路与路由闸，EPUB 面相交点零触碰（P0 批在飞）。

## 实施清单（逐条对锚）

| 锚 | 落地 | 位置 |
|---|---|---|
| §5.1 文本/二进制闸拆分 | `pop.readFile` 先按扩展名做 `FileTypeRegistry` 二值判定再选闸：文本维持 100MB，二进制查看腿（nf-file 族）= `AttachContract.MaxFileBytes`（1 GiB，**引用**非字面量）；新增纯函数 `popReadGateLimit(isBinary)` | `TextStreamSearch.scala`（`MaxViewerFileBytes`/`popReadGateLimit`）、`WsFileOpsHandlers.handlePopReadFile` |
| §5.1 前端镜像拆分 | `MAX_TEXT_BYTES`（100MB，不动）/ 新增 `MAX_VIEWER_BYTES = 1,073,741,824`，两个 BLOB 腿（`previewBlob` + `runProgressAttempt`）超限走既有可见降级 | `attachmentPreview.js` |
| §5.2-1 视口驱动渲染 | `IntersectionObserver`（root=`.pdf-pages`，rootMargin=±2 屏）命中入队、离开即 `releasePage`（撤 width/height 属性 + `page.cleanup()`，pdf.js 官方 viewer 同范式） | `viewers/pdf.js` |
| §5.2-2 渲染队列 | 视口距离优先级排序、并发 ≤2、`renderToken` 失效竞态纪律沿用；另加每 canvas 代际（`canvasGen`）防缩放后同页双渲染竞争 | `viewers/pdf.js` |
| §5.2-3 页尺寸预占 | 开书先 `getPage` 遍历量 `view` 尺寸（只读元数据，量完即 cleanup）→ style 预占布局；>2000 页抽样 50 页均高估算（加载中允许 ±5% 跳动） | `viewers/pdf.js` |
| §5.2-4 缩放只重渲可见页 | 120ms debounce 沿用；新档位 = 全部旧位图失效 + 全部占位按新 scale 重排 + 只重渲当前可见 ±2 屏，离屏下次入视口补渲；pivot 补偿挂队列静止回调（防悬挂） | `viewers/pdf.js` |
| §5.3 护栏①常驻 ≤6 | 准入即预算（rendered+inFlight 视为未来位图）+ **先让位再准入**（释放比候选离视口更远的已渲染页）⇒ 「带 width 属性的 canvas ≤6」任意时刻恒成立（含瞬时） | `viewers/pdf.js` |
| §5.3-⑤ 失败降级 | 「重试一次 + 占位可见」逐字保留 | `viewers/pdf.js` |

## 护栏四条件断言落点（全绿证据）

1. **常驻位图 ≤6（DOM 计数）**：`tests/pdf-lazy-render.spec.mjs` 站1b/2a/3c/5b/5d/5g — 首屏/滚动态/缩放后/大文件全程每一位图数断言 ≤6。
2. **单页并发 ≤2（队列断言 + 代码审）**：站4b 源码钉；准入循环 `inFlight.size >= MAX_RENDER_CONCURRENCY` 截断 + 同页串行（在途不重复准入）。
3. **首屏 ≤3s（QA 计时）**：站5a — 109MB 稀疏 50 页夹具实测 **1.3s 内出首屏静止**（3 次连跑 1066/1270/1496ms）。
4. **JVM 零整件堆读（既有口径回归）**：`WsFileOpsHandlers` 二进制腿不读字节注释/结构保留（站4q）；Scala `PopReadGateSpec` 5 例 + `TextStreamSearchSpec`/`TextStreamSpec`/`AskUserAttachmentRoutesSpec` 共 39 例全绿。

**大文件实跑（任务书「造 100MB+ 测试 PDF 验内存不涨」）**：109MB 稀疏 50 页真 PDF（每页 2.3MB Flate 随机图，Range 静态服务，pdf.js 真 Range 取数）——主线程 JS 堆 15MB 全程**四轮读数持平**；二遍通读增量 ≤32MB 断言内（取数缓存已暖）；位图 ≤6 全程恒定。A8 判据（100MB+1 PDF 放行 / 100MB+1 文本仍拒）由 `PopReadGateSpec` 以 `FileTypeRegistry` 真实 binary 位穿透钉死。

## 自查发现的两个真缺陷（实施中自抓自修）

- **常驻预算先超后收**：初版让位发生在渲染完成后，观测到位图数瞬时 7（>6）——改为准入前先行让位，DOM 判据任意时刻恒成立。
- **缩放同页双渲染**：旧 scale 在途渲染与新时代渲染可竞争同一 canvas 留下僵尸位图——加每 canvas 代际所有权（过时渲染仅在无人接管时自回滚）。

## 挂账（本批不争）

- `check-js-types.mjs` 基线三分支漂移（`bgMergedPanel.js`/`plugins.js`「新文件」、`sidebar.js` 65>62）系 `183c375c2` 重锚后他批引入，与本批无关（本批未触碰三文件，且总错数 260→259 反降 1）——建议后续扫尾批 `--update` 重锚。
- A8 端到端（真实例 nf-file 族 100MB+1 打开）待 CI instance-backed 面或 QA 真机腿；本批以 Scala 纯函数 spec + 源码接线钉 + 前端镜像钉覆盖。

## 验证命令

```
node tests/pdf-lazy-render.spec.mjs   # 41 checks, 0 failed（本批三连跑绿）
sbt "testOnly nebflow.gateway.PopReadGateSpec nebflow.gateway.TextStreamSearchSpec nebflow.gateway.TextStreamSpec nebflow.gateway.AskUserAttachmentRoutesSpec"  # 39/39
node scripts/check-js-types.mjs       # 本批文件零新增错；total 259 < baseline 260
```

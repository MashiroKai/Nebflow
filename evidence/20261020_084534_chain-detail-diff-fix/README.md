# 修复批 · ⑫B 委托链节点详情 Changes 段静默 404（chain-detail-diff-fix）

- 日期：2026-10-20 ｜ 分支：`delegate/delegate-nebflow-2f29c239` ｜ 基线：USTC 审计报告（`.nebflow/evidence/20261018_ustc-merge-feature-audit/report.md` §B / §四.1 / §五）
- 病情：flowmap 引擎面（`51372abd4` 起）委托链节点详情拉 diff 时 project 名传 scope 名 `delegate-chain:<key>` ⇒ `/api/projects/delegate-chain%3A…/flow-map/nodes/<id>/file-changes` 404 ⇒ `diffView.js` 按「缺失静默跳过」设计 ⇒ Changes 段静默缺席（无报错无日志）。

## 修法（报告处方①：真实项目名透传）

数据单源 = 行挂载项目名（taskList rowMeta `rowMetaOf().project` 同一字段），在投影单点透传，沿渲染管线进详情悬浮层 ctx：

1. `delegateFlowMap.js` `buildDelegateFm`：`meta.diffProject = row.project`（'' = 无真名）。
2. `flowMapTab.js` `renderFlowMap`：从原始 fm store 读 `meta.diffProject`，经 `syncArchiveUi(container, project, { diffProject })` 透传（增量/全量两路径均覆盖；WS 增量路径经同一 renderFlowMap 入口）。
3. `flowMapArchive.js`：`ensureArchiveLayer` 新 opts 缝（仅显式带键时刷新 `ctx.diffProject`，详情开窗的 ctxFor 重入不回抹）；新 `diffProjectFor(ctx)` 单点判定——经典作用域=自身（行为逐字节不变）、delegate 作用域=`ctx.diffProject`；详情挂载点改为「无真名 ⇒ **显式跳过 diff 段**（零网络调用）」。
4. `diffView.js` `mountFileChangesSection`：显式 `projectName` 参数（'' = 不适用直接返回，双保险）。

孤儿模块 `chainDetailEntry.js` 维持 `scripts/build-web.mjs` 备案（exemptions: 5，零复活）；透传方案实证不需要它。

## 触碰文件（产物码 4 + 验收 spec 1）

| 文件 | 改动 |
|---|---|
| `src/main/resources/web/js/delegateFlowMap.js` | `buildDelegateFm` meta 透传 `diffProject`（+注释） |
| `src/main/resources/web/js/flowMapTab.js` | `renderFlowMap` 读 store 透传两处 `syncArchiveUi` |
| `src/main/resources/web/js/flowMapArchive.js` | LayerCtx.diffProject 槽 + ensureArchiveLayer opts 缝 + `diffProjectFor` 单点 + 挂载点显式判定 |
| `src/main/resources/web/js/diffView.js` | `mountFileChangesSection(ctx, seq, nodeId, projectName)` 显式项目名 |
| `tests/chain-detail-diff.spec.mjs` | 新验收 spec（T1/T2/T3，见下） |

## 验收矩阵

| # | 验收项 | 结果 |
|---|---|---|
| ① | T1 真名链（row.project='Nebflow'）：completed 节点详情 → REST 调用带真实项目名（恰 1 笔，URL 不含 `delegate-chain%3A`）且 Changes 段渲染（`Changes · 1 file +2 −1` 头、modified 徽标、路径、±计数、展开后 hunk 头 `@@ -3,3 +3,4 @@`、+/− 行 RGB 通道着色 g>r / r>g） | ✅ |
| ① | T2 无真名链（row.project=''）：详情照开、diff 段明示跳过、**零** `/file-changes` 网络调用（含 scope 键 URL 也不发） | ✅ |
| ① | T3 经典项目面回归：per-project flowmap completed 节点仍按自身项目名拉取（identity 腿逐字节不变） | ✅ |
| ② | §四.1 运行时复现复跑（探针 `network-probe.spec.mjs`，同场景修复前后各一次）：before=`delegate-chain:41`→**404**、段缺席；after=`Nebflow`→**200**、段挂载 | ✅（见 before/） |
| ③ | flowmap/tasklist 家族回归（`tasklist-chain-blocks` / `delegate-chain-stub` / `flowmap-task-slim-migration` / `delegate-ui-twolayer` + 新 spec） | ✅（retries 吸收既有像素抖动，见下「已知无关失败」） |
| ④ | before/after Network 对照 + 详情面板截图 | ✅ 本目录 |

spec 反向验证（bug 敏感性）：stash 修复后跑新 spec ⇒ T1 红（Changes 段缺席）+ T2 红（3 笔 scope 键网络调用），T3 绿 —— 证明断言真实钉住病灶而非恒真。

## before/after Network 对照（§四.1 同场景）

**before（修复前，stash 复跑）** — `before/network.json` + `detail-before.png`：
```
GET /api/projects/delegate-chain:41/flow-map/nodes/s1/file-changes → 404
changesSectionMounted: false   （Changes 段静默缺席——截图同卡无 Changes 行）
```

**after（修复后）** — `after/network.json` + `detail-after.png`：
```
GET /api/projects/Nebflow/flow-map/nodes/s1/file-changes → 200
changesSectionMounted: "src/main/resources/web/js/flowMapArchive.js"
（截图：Changes · 1 file +2 −1，modified 徽标 + 路径 + git 源标签）
```

## 复现命令

```bash
R="/Users/kaiyu/Claude code/Stand/.nebflow/worktrees/delegate-nebflow-2f29c239"
cd "$R" && ln -sfn "/Users/kaiyu/Claude code/Stand/node_modules" node_modules   # 共享 pinned 依赖
# 验收 spec（①③）：
node node_modules/@playwright/test/cli.js test tests/chain-detail-diff.spec.mjs --reporter=line
# §四.1 探针（②④）：
EV=evidence/20261020_084534_chain-detail-diff-fix
CDD_OUT=$PWD/$EV/after/network.json node node_modules/@playwright/test/cli.js test $EV/network-probe.spec.mjs --reporter=line
# 家族回归（③）：
node node_modules/@playwright/test/cli.js test tests/tasklist-chain-blocks.spec.mjs tests/delegate-chain-stub.spec.mjs tests/flowmap-task-slim-migration.spec.mjs tests/delegate-ui-twolayer.spec.mjs --retries=3 --reporter=line
# 静态门：
node scripts/check-js-types.mjs     # 258 < 基线 273；触碰文件零报错（见下）
node scripts/build-web.mjs          # OK，exemptions: 5（chainDetailEntry 备案原样）
```

## 已知无关失败（申报）

- `tasklist-chain-blocks.spec.mjs` T1 的 2px 像素对齐断言（`iconBox.y - glyphBox.y < 2`）为**既有抖动**：main 检出上 3 跑 1 绿 2 红（2.3–2.5px 抖动）；本 worktree 上 stash 本修后同样复红 ⇒ 与本修无关。家族回归以 `--retries=3` 收敛为绿。
- `scripts/check-js-types.mjs` 在 main 当前 HEAD 即有 2 个 gate failure（`agentProfile.js` / `mobile/floatController.js`，`a8f7c1234` mobile-v3 批新增文件未烧基线）——main 检出与本 worktree 输出逐字相同；本修触碰的 4 文件 checkJs 零报错（错误总数 258 < 基线 273）。

## 申报

- **前端改动刷新即生效**（web/js 静态资源，无构建产物变更、无引擎/REST 面 touches，无需重启网关）。
- 零 push；交付 = 本分支提交，经合并队列并 main。

# 合并-归档面板进main·重派3 · 全程记录

- 日期：2026-09-04 06:1x-06:3x · 执行：Coder（合并节点·重派3 收口环）
- 主仓：/Users/dev/Claude code/Nebflow · 分支 main · 全程零 push、零 origin 接触
- 宿主 PID 77854 / 端口 8080：零接触

## 0. 双闸判定（合并前交叉核对）

| 闸门 | 记录位置 | 首行判定 |
|---|---|---|
| QA·重派3（注入） | 节点结果首行 | **最终判定：PASS（可合并）** |
| QA·重派3（落盘报告 §0） | /tmp/nb-qa3-record/deliverables/docs/Nebflow/20260904_archive-panel-v3-dqa1-qa3-report.md L13 | **PASS（可合并）** |
| 视觉评审·D-QA1修复版（重派3 第二环） | /tmp/nb-visual3-record/deliverables/docs/Nebflow/20260904_archive-panel-v3-dqa1-visual3-review.md L13 | **PASS**（D-QA1 双灭，下游闸门放行） |

双闸一致 PASS → 放行合并。

## 1. 合并前置：分支 ref 对齐（QA §6-① 交付完整性命令）

- 对齐前主仓 ref：flowmap-archive-panel = 25bed80a（重派2 收口点，缺本轮 3 commit）
- 修复链仅在 worktree 平行 GIT_DIR `.sandbox-git/`（上游节点对主仓 .git Operation not permitted 所致）
- 执行：`git fetch .sandbox-git refs/heads/flowmap-archive-panel:refs/heads/qa3-tmp` →
  `git update-ref refs/heads/flowmap-archive-panel refs/heads/qa3-tmp` → `git branch -D qa3-tmp`
- 对齐后验证：tip = **15c171b7**，链序 15c171b7 / f7fbeccd / dfae15ce —— 与 QA 预期逐字一致 ✓

## 2. 盘点（git log --oneline main..flowmap-archive-panel）

9 commits，逐条与注入链核对一致：
```
15c171b7 test(flowmap-archive): 新增 resize-follow 动态断言 spec——补 D-QA1 覆盖缺口   ← 本轮 spec
f7fbeccd fix(flowmap-archive): D-QA1 dock 判定改 ResizeObserver 落定回调               ← 本轮修复 (+54/−7)
dfae15ce Merge branch 'main' into flowmap-archive-panel                              ← 本轮合流 main@815d2519
25bed80a test(flowmap-archive): spec 语义同步 §5.8b 修订 + dock/零遮挡探针 + 静态护栏  ← 重派2 收口点
73d1a2bf fix(flowmap-archive): 视觉评审三缺陷数值级修复 + §5.10② 拖拽豁免修复           ┐
b0a1fbe4 Merge branch 'main' into flowmap-archive-panel                              │ 前两轮
b4a831a2 test(flowmap-archive): 88 断言移植的产品 Playwright spec + 夹具 + 降级静态断言 │ 已知
0000de17 fix(flowmap-archive): refreshChains identical 判定改为成员 id 集合语义        │ 提交
3987dd1e Flow Map 整链归档 v3 前端实施（规格 frozen 版）                               ┘
```

## 3. 主仓状态与合并

- merge-base(main, 分支) = **815d2519 = main tip** → 零冲突面（指令预判的 locales/*、
  flowMapTab.js、projectPanel.css 冲突落空：main 侧无分支未含的新提交，QA「fast-forward
  等价」预判证实）。**冲突解决记录：无需解决，零冲突。**
- 主仓 `git status --porcelain` 空 ✓（未预期改动零）
- 合并：`git merge --no-ff -m "Merge flowmap-archive-panel: FlowMap 归档面板 v3（D-QA1 resize
  落定重判修复，三轮双闸 PASS）"` → **791c5b08**，ort 策略，10 文件 +3040/−130
- 注：仓库 post-merge 钩子触发 [Nebflow QC] 自动评审，因沙箱 EPERM（~/.claude.json 不可读）
  未启动——钩子自身环境问题，合并事实不受影响，记录在案
- worktree 新 ref 口径下 status 的 MM/D/?? 条目：主 gitdir 旧 index 化石（早期节点在
  worktree 内用主 gitdir 暂存残留），已用「sandbox 口径 status 空 + 3 个 ?? 文件与
  15c171b7 逐字节 diff 相等」证伪为真实改动；merge 读分支 tip tree，不受影响

## 4. 合并后复跑（8 项）

| # | 项 | 命令 | 预期 | 实测 | 判定 |
|---|---|---|---|---|---|
| 1 | 归档 spec | pw test tests/flowmap-archive-panel.spec.mjs --workers=1 | 8p/1f（T6 既有红） | 8 passed / 1 failed（唯一红 A17+A18=T6） | ✓ |
| 2 | 静态护栏 | node tests/flowmap-archive-panel.static.mjs | 26/26 | 26 passed, 0 failed | ✓ |
| 3 | resize-follow | pw test tests/flowmap-archive-resize-follow.spec.mjs | 2 passed | 2 passed (35.4s) | ✓ |
| 4 | tasklist-nodes | pw test tests/tasklist-nodes.spec.mjs | 6/6 | 6 passed (31.9s) | ✓ |
| 5 | canvas-html-interactive | pw test tests/canvas-html-interactive.spec.mjs | 17/17 | 17 PASS / 0 FAIL | ✓ |
| 6 | flowmap-realtime（重写前基线） | pw test tests/flowmap-realtime.spec.mjs | 1f(T1 既有)/1p | 1 failed (T1 spec:214) / 1 passed (T2) | ✓ 与文档化既有红逐字一致 |
| 7 | check-js-types | node scripts/check-js-types.mjs | 0 新增（baseline 326） | 0 errors, PASS (-326) | ✓ |
| 8 | i18n sweep | 8387 临时静态服务器 + node scripts/verify-i18n-sweep.cjs 8387 | 11/11 parity 相等 | 11/11 PASS，zh=954=en=954 | ✓ |

- i18n 口径注：指令基线 949=949 为 815d2519 前口径；main 归档按钮自带 +5 对键 →
  954=954 为等价基线（QA v3 §3 [verify-fix·口径注] 同结论）
- 端口纪律：8387 起服前 lsof 预检（空闲）；8080/8091/8092 仅检不碰（宿主 77854 在 8080）；
  服务器 PID 84827（自起非宿主），用毕 kill 并 lsof 验证释放 ✓
- Playwright 零意外失败，未触发降级条款

## 5. flowmap-realtime T1 重写（合并后，独立 commit 403e0274）

- 红因（QA v2 §2.5 代码级 traced + 本节点代码复核一致）：v3 下 n3 完成即批次链
  {n1,n2,n3} 链齐 → `animateChainExit` 整链同帧 fm-exit（350ms）→ 统一渲染整链移除；
  旧步骤 3 断言「nodeRemoved 后 n3 出现 fm-exit/opacity<1 再 detach」的时点在退场完成
  之后，永假。尾段 L222-230（n1 探针存活 / count=2）同为 pre-v3 TTL 驻留前提，v3 下
  n1 已随链移除，不可能绿。
- 重写范围：**仅 T1 尾段**（L211-230 → v3 语义块）；T2 整体与 T1 前段 L124-209
  （淡入/接线重绘/完成过渡/delivered，本轮实测全绿）一字不动；L232-234 原样保留
- 三处对齐：
  ① 步骤 3 → 链齐退场 steady-state 哨兵（n3 detach + 边随链移除 + 整链清场 .fm-node=0）
  ② nodeRemoved 出库 → 墓碑记账语义 + 零复活哨兵（防已归档成员被退场动画复活回主图，
     refreshChains 成员集幂等跳过回归位）
  ③ n1 探针身份核验前移至退场窗口前（nodeCreated+接线重绘多轮增量后），对账兜底断言
     改 v3 口径稳态零复活
- 提交：**403e0274** `[merge] flowmap-realtime T1 断言重写对齐 v3`（单文件 +23/−14）
- 复跑：**flowmap-realtime 2 passed (32.1s)** —— T1 转绿，T2 未动仍绿，零遗留红

## 6. 清理（安全核验先行）

- 核验：f7fbeccd/dfae15ce/15c171b7 对象已在主仓（cat-file -e）✓；
  `git merge-base --is-ancestor flowmap-archive-panel main` ✓；
  worktree 磁盘 == 15c171b7（sandbox 口径 status 空 + 3 个 ?? 文件 diff 相等）✓
- `git worktree remove --force .nebflow/worktrees/flowmap-archive-panel`
  （--force 理由：主 gitdir 旧 index 化石态 MM/D/??；独有内容已证零，全部对象在主仓）
- `rm .nebflow/flowmap-archive-panel`（软链）✓
- `git branch -d flowmap-archive-panel` → 已删除（曾为 15c171b7），-d 成功 ✓
- 终态：main @ **403e0274**，status 干净；他者 worktree（node-agentfile/ci/window-shell/
  archive-mesh-sync/pekko-only/refactor）零触碰

## 7. 生效说明

本次合并仅落**仓库源码**（src/main/resources/web/*）。前端产物（production bundle）重建
与宿主重启**本批未做、严禁由本节点做**（宿主 PID 77854 / 端口 8080 零接触）——生效需
Nebula 侧另行安排 `node scripts/build-web.mjs` 重建 + 宿主重启。

## 8. 产物清单（本目录）

- RECORD.md（本文档）
- logs-archive-spec.txt / logs-static-guard.txt / logs-resize-follow.txt /
  logs-tasklist-nodes.txt / logs-canvas-html.txt / logs-flowmap-realtime-prerewrite.txt /
  logs-flowmap-realtime-postrewrite.txt / logs-check-js-types.txt / logs-i18n-sweep.txt
- i18n-server.log（8387 服务器输出）

# FlowMap 归档面板 v3 D-QA1 修复版 · QA 全量独立验收报告 v3（重派3·第三环）

- 日期：2026-09-04 05:5x · 验收人：Coder（QA·重派3，下游合并节点唯一闸门）
- 验收对象（内容口径）：worktree `.nebflow/worktrees/flowmap-archive-panel` @ **15c171b7**
  = 25bed80a（重派2 收口点）→ dfae15ce（merge main@815d2519）→ **f7fbeccd**（D-QA1 RO 落定重判修复，flowMapArchive.js +54/−7 单文件）→ **15c171b7**（resize-follow 动态断言 spec 新增 +236 行）
- 前置闸门：上游「视觉评审-归档面板D-QA1修复版」**PASS** → 按协议执行全量独立验收（证据全部第一方自采，未采信上游结论）
- 测试纪律：产品 spec/probe 全程零端口（route 拦截 localhost:1）；唯一临时端口 8387（i18n sweep，两轮起停，lsof 验 PID≠77854 后 kill，端口已释放）；未触 8080/8091/8092/宿主进程

---

## 0. 判定

**PASS（可合并）** —— D-QA1 双锁死形态经三路独立实证全部转绿；六项验收全过；既有红（T6/T1）失败形态与 QA v2 记载逐字一致、归因不变。

**合并前置条件（交付完整性，[verify-fix] 最高优先级）**：真实分支 ref `refs/heads/flowmap-archive-panel` 仍停在 **25bed80a**（不含本轮修复）。f7fbeccd/15c171b7/dfae15ce 三个 commit 目前只存在于 worktree 内的平行 GIT_DIR `.sandbox-git/`（上游修复节点因节点会话对主仓 `.git` 整体 `Operation not permitted` 而被迫使用，本会话实测复现同阻，`.sgit` 辅助脚本为其自建包装）。**合并节点若按分支 ref 直接合并将漏掉 D-QA1 修复**。宿主侧对齐命令见 §6-①，一条 fetch + 一条 update-ref 即完成，合并随即等价 fast-forward（dfae15ce 第二亲代即当前 main tip 815d2519，合并净内容 = 修复 + spec，零冲突面）。

## 1. 验收① 代码审查 ✓

**分支对 main 净增量**（`GIT_DIR=.sandbox-git git diff --stat main 15c171b7`）：10 文件 +3040/−130 = flowMap.css(321+)/projectPanel.css(5±)/flowMapArchive.js(932 新)/flowMapTab.js(320±)/locales en+zh(31±×2)/tests×4（panel spec 1008/static 101/**resize-follow 236 新**/fixture 185）。提交链 25bed80a..15c171b7 与上上游修复节点自述**逐字一致**（dfae15ce 仅合流 main、f7fbeccd 单文件 +54/−7、15c171b7 单新文件）；proj-archive（main 815d2519 自带）零重复零越界，**无任何 Scala/无关特性夹带**。

**f7fbeccd 逐 hunk 审查（5 hunks，对照 QA v2 §1 处置要求）**：
- **ResizeObserver 落定重判**：`hostRo = new ResizeObserver(rejudgeAllDocks)` 模块单例，`ensureArchiveLayer` 时 `observeHost(ctx)` 挂到 `ctx.layer.parentElement`；RO 纯作触发时机、宽度仍读 live `clientWidth`，`DOCK_MIN_HOST_W=780` 语义未动，`updateDetailDock` 判定式零触碰——与 QA v2 建议方向（RO 落定回调）一致 ✓。RO 每帧布局后回调、过渡终帧即最终宽度，天然免疫 0.32s flex-basis 过渡时序；附带覆盖 col-resizer 钉宽不派发 resize 的同类缺口 ✓。
- **观察者生命周期**：三处 `layerCtxs.delete` 调用点（click/keydown/interval 清扫）全部改 `dropCtx`（unobserve + 出册）；`unobserveHost` 同 host 多 ctx 存活保留判断在位 ✓。[verify-fix·小] 现有调用点均以 `!layer.isConnected` 为前提，detached 子树场景外 `parentElement` 为 null 早退，unobserve 分支实际几乎不可达——但 hostRo 观察集 = 去重 host 数（observe 幂等），有界良性，不阻塞。
- **特性检测降级**：`typeof ResizeObserver === 'undefined'` 才启用降级分支；RO 可用时整体旁路（避免事件时刻旧值再判引入中间翻转抖动）。降级 = resize 事件即时重判 + 双 rAF + `transitionend`（propertyName==='flex-basis' 过滤）——与 QA v2 建议的双 rAF/transitionend 兜底一致，且被动态用例真实执行验证（见 §2）✓。
- **最小改动**：54 插入/7 删除全部落于上述语义区，机械替换仅 3 处 delete→dropCtx ✓。

**§17 C1-C9 零回归**：本批 diff 与 C1/C4①/C6/§5.10②/C8/C10/C12/C13 对应区（refreshChains 成员 id 集合判定、hidden 显式保护、hover in/deps/out、expiredIds 派生排除、purgeExpired、expandedEntry）**零交集**；动态侧归档 spec 8 passed（A5.2/A8/A11.3/A12/A18 系列全在其中）+ 静态 26/26 双重复确认 ✓。

## 2. 验收② 产品 spec 独立重跑 ✓

| 项 | 结果 | 判定 |
|---|---|---|
| `flowmap-archive-panel.spec.mjs`（含 T8 375 A20.1/A20.2） | **8 passed / 1 failed**，唯一红 = A17+A18 重载恢复态（T6） | 预期吻合 ✓ |
| `flowmap-archive-panel.static.mjs` | **26 passed / 0 failed**（含 D1a/b/c、D3、D3b、A9.6b、A17a/b、A18a-d） | ✓ |
| `flowmap-archive-resize-follow.spec.mjs`（15c171b7 新增） | **2 passed**：①RO 主路径 1280→1920→1280 每步落定断言；②强制降级（delete window.ResizeObserver）单发最大化/还原 | ✓ |

两轮全量重跑（间隔 ~15 分钟）结果逐位一致。T6 归因见 §5。

## 3. 验收③ 回归五件套 ✓

| 项 | 结果 |
|---|---|
| flowmap-realtime | 1 failed / 1 passed——T1 既有过期断言红（签名核对见 §5），T2 重连收敛绿 |
| tasklist-nodes | **6/6 PASS** |
| canvas-html-interactive | **17/17 PASS** |
| check-js-types | **PASS：0 errors（baseline 326，零新增）** |
| i18n sweep（8387 临时静态服务器） | **11/11 PASS，zh=en parity 954=954** |
| node --check 分支改动 JS ×4 | 全部 OK |

[verify-fix·口径注] parity 由 QA v2 的 949=949 → **954=954**：+5 对键来自 main 815d2519（proj-archive-btn 特性 locales），等价性成立、零缺键，非本批回归。

## 4. 验收④ D-QA1 专项复验 ✓（三路独立实证）

**路 A——QA v2 探针原样复跑**（源码零改动，仅 SHOT 输出目录改指本 QA 产物目录）：**1 passed**。关键原始数据：
- QA-2 @1920：`{hostW:790, dockLeft:true, right:"404px", overlap:false, detailInHost:true, detailCenterHitSelf:true}`——症状①（该并排不并排）**转绿**；eventSamples 证实事件时刻仍读旧宽 470、+400ms 后 790（时序机制与修复设计吻合，RO 在落定后回调补判）。
- QA-3 回 1280：`{hostW:470, dockLeft:false, right:"16px", detailInHost:true, hit:true}`，探针自记「dock 撤除正常（未复现锁死）」——症状②（dock 锁死悬出）**转绿**。原 RESIZE-LATCH-BUG 截图位点现拍得健康形态。
- QA-4 Esc 分层（详情先关/面板保持/再 Esc 关面板）✓；QA-5 @375 hostW 318 零左裁零横滚 ✓。
- 旁证：window resize 监听注册数 5→4——旧 resize 重判 handler 已按设计被 RO 路径取代。

**路 B——本 QA 全新独立探针**（`qa3-independent-probe.spec.mjs`，断言逻辑与既有探针零共享）：**4 passed**。断言哲学不同——不硬编码期望形态，落定后验证语义规则 **「docked === (hostW ≥ 780)」+ 形态自洽几何**（elementFromPoint 命中用 `detail.contains(hit)` 判定）+ 落定后 500ms 双采样**无振荡**复核：
- ①往返 1280→1920→1280：470 overlay → 790 dock(404px/零重叠/在 host/命中) → 470 overlay(16px/完整/命中)，ruleConsistent 全 true，无振荡 ✓
- ②dock 基线单发还原：1920 dock 基线 → 单发 1280 → overlay 正确 ✓
- ③阈值扫掠 vp1550/1580/1620/1660（hostW 605/620/640/660 全 overlay 侧）+ ①②的 470↔790 双向穿越，规则在阈两侧零失配 ✓
- ④reduced-motion + 暗色主题：往返跟随成立、dock 形态正确（截图）✓
- [verify-fix·记录] v1 探针两轮失败均为本 QA 自身缺陷并已修：三目真分支赋值表达式值污染 settle 记忆（永不收敛）；RO 子类包装 + 每次 fetch 新鲜载荷的 boot 管线致 host 宽对 resize 冻结（伪影定性：产品码在套件标准管线下三路探针全部正常）。v2 改用与 resize-follow spec 同构 boot（固定载荷），断言逻辑保持独立。

**路 C——上游 resize-follow spec 独立复跑**（2 passed，含强制降级路径）：见 §2。

## 5. 验收⑥ 既有失败归因核对 ✓（均非本批引入）

| 失败 | 本轮独立核对 |
|---|---|
| 归档 spec T6（A17+A18 重载恢复态） | 失败签名与 QA v2 §2.5 逐字一致：`Test timeout of 90000ms exceeded` @ `.project-card[data-project="alpha"] [data-open-flowmap="alpha"]` dispatchEvent。归属代码 `projectTab.js` 与 main **逐字节一致**（diff 为空，实测）；本批 diff 与其零交集；降级静态 A17a/b + A18a-d 26/26 中全绿。维持既有文档化红定性。 |
| flowmap-realtime T1（实时动画五连断言） | 失败签名同点复现：n3 期望 `fm-exit`/opacity<1 的 waitForFunction 800ms 超时（spec:214）——v3 整链退场语义下永假的过期断言（QA v2 §2.5 已代码级 traced）。spec 文件与 main 逐字节一致（diff 为空）；本批 diff（flowMapArchive.js/spec 新文件）与 flowMapTab.js 零交集 → 失败形态不因本批变化。合并节点须继续携带 T1 重写义务（QA v2 既定）。 |

## 6. 宿主侧落地命令（合并节点/Nebula 执行）

**① 合并前置（必须先做）——分支 ref 对齐 15c171b7：**
```bash
cd "/Users/dev/Claude code/Nebflow/.nebflow/worktrees/flowmap-archive-panel"
git fetch .sandbox-git refs/heads/flowmap-archive-panel:refs/heads/qa3-tmp
git update-ref refs/heads/flowmap-archive-panel refs/heads/qa3-tmp
git branch -D qa3-tmp
git log --oneline -3   # 预期：15c171b7 / f7fbeccd / dfae15ce；git status 应 clean（相对 sandbox HEAD）
```
（此后 main 合并 flowmap-archive-panel = fast-forward 等价，净内容即 f7fbeccd+15c171b7。）

**② QA v3 交付物落地（报告 + 12 截图 + 3 探针/源码 + 日志）：**
```bash
mkdir -p ~/.nebflow/docs/Nebflow
cp /tmp/nb-qa3-record/deliverables/docs/Nebflow/20260904_archive-panel-v3-dqa1-qa3-report.md ~/.nebflow/docs/Nebflow/
cp /tmp/nb-qa3-record/deliverables/docs/Nebflow/qa3-*.png ~/.nebflow/docs/Nebflow/
cp /tmp/nb-qa3-record/deliverables/docs/Nebflow/20260903_archive-panel-qa-*.png ~/.nebflow/docs/Nebflow/
cp /tmp/nb-qa3-record/deliverables/docs/Nebflow/qa3-independent-probe.spec.mjs \
   /tmp/nb-qa3-record/deliverables/docs/Nebflow/qa3-qa-v2-probe-rerun.spec.mjs \
   /tmp/nb-qa3-record/deliverables/docs/Nebflow/qa-probe.spec.mjs.orig-copy ~/.nebflow/docs/Nebflow/
cp -r /tmp/nb-qa3-record/logs ~/.nebflow/docs/Nebflow/20260904_archive-panel-v3-qa3-run-logs
cd ~/.nebflow && git add \
  docs/Nebflow/20260904_archive-panel-v3-dqa1-qa3-report.md \
  docs/Nebflow/qa3-indep-roundtrip-1920-dock.png \
  docs/Nebflow/qa3-indep-roundtrip-1280-overlay.png \
  docs/Nebflow/qa3-indep-restore-fromdock-1280.png \
  docs/Nebflow/qa3-indep-dark-rm-1920-dock.png \
  docs/Nebflow/qa3-resizefollow-1920-dock.png \
  docs/Nebflow/qa3-resizefollow-1280-overlay.png \
  docs/Nebflow/qa3-resizefallback-1920-dock.png \
  docs/Nebflow/qa3-resizefallback-1280-overlay.png \
  docs/Nebflow/20260903_archive-panel-qa-overlay470.png \
  docs/Nebflow/20260903_archive-panel-qa-dock790.png \
  docs/Nebflow/20260903_archive-panel-qa-RESIZE-LATCH-BUG.png \
  docs/Nebflow/20260903_archive-panel-qa-375.png \
  docs/Nebflow/qa3-independent-probe.spec.mjs \
  docs/Nebflow/qa3-qa-v2-probe-rerun.spec.mjs \
  docs/Nebflow/qa-probe.spec.mjs.orig-copy \
  docs/Nebflow/20260904_archive-panel-v3-qa3-run-logs \
  && git commit -m "归档面板 v3 D-QA1 修复版 QA 独立验收 v3 PASS（重派3）：双锁死三路实证转绿 + 六项验收全过 + 交付完整性 ref 对齐命令（报告+12截图+探针+运行日志）"
```
**③ INDEX.md 追加一行**（可并入 ② 的 commit）：
```
| [20260904_archive-panel-v3-dqa1-qa3-report.md](20260904_archive-panel-v3-dqa1-qa3-report.md) | 归档面板 v3 D-QA1 修复版 QA 独立验收 v3（重派3）：PASS——双锁死形态三路独立实证转绿（QA v2 探针复跑 1 passed + 全新一致性规则探针 4 passed + resize-follow spec 2 passed 含强制降级路径）、归档 spec 8/9（T6 既有红）、静态 26/26、回归全绿（parity 954=954）、T6/T1 失败签名与既有归因逐字一致；⚠ 合并前置：分支 ref 仍 @25bed80a，修复 commit 在 worktree .sandbox-git 平行 GIT_DIR，宿主侧 fetch+update-ref 对齐后合并（命令在报告 §6） | PASS（可合并，含 ref 对齐前置） | 2026-09-04 |
```
（严禁 git add -A / .；不 push。）

## 7. 证据清单（/tmp/nb-qa3-record/deliverables/docs/Nebflow/，全部第一方）

| 文件 | 内容 |
|---|---|
| 本报告 md | 六项验收全记录 |
| qa3-indep-roundtrip-1920-dock.png / -1280-overlay.png | 独立探针①往返落定态（dock/overlay） |
| qa3-indep-restore-fromdock-1280.png | 独立探针②dock 基线单发还原 |
| qa3-indep-dark-rm-1920-dock.png | reduced-motion + 暗色 dock 形态 |
| qa3-resizefollow-* / qa3-resizefallback-*.png ×4 | 上游 resize-follow spec 两用例四落定态截图 |
| 20260903_archive-panel-qa-{overlay470,dock790,RESIZE-LATCH-BUG,375}.png | QA v2 探针复跑四截图（RESIZE-LATCH-BUG 位点现拍为健康态） |
| qa3-independent-probe.spec.mjs / qa3-qa-v2-probe-rerun.spec.mjs / qa-probe.spec.mjs.orig-copy | 探针源码（独立/复跑/QA v2 原件） |
| ../logs/ ×9 | archive-spec/static/resize-follow/realtime/tasklist/canvas/check-js-types/i18n-sweep/node-check 运行日志 + ../qa-probe-rerun-output.txt、qa3-independent-probe-output.txt 探针原始输出 |

**遗留（不阻塞，定性不变）**：T6 重载恢复态专项；flowmap-realtime T1 随 v3 语义重写（合并节点携带）；375 host 右缘溢出视口 16px（首轮附带项）。**[verify-fix] 汇总**：①分支 ref 对齐（§6-①，合并前置）；②unobserveHost 分支实际近不可达（有界良性，可随下次重构顺手收口）；③i18n parity 基线 949→954（main 携带，口径更新）。

# Node「暂停/人在回路」MVP（方案 B「节点 hold」）实施报告

- 日期：2026-09-03
- 任务：按设计文档 `20260903_node-pause-human-in-loop-design.md`（~/.nebflow commit a3e052a，作者 §6 七决策点全部按建议 a 落实）实施 + 测试 + 合并回本地 main
- 实施分支：node-hold（worktree .nebflow/worktrees/node-hold，已清理）；实现 commit `f6a0b846`；merge commit `a97d69b0`（main，--no-ff，未 push）

---

## 一、开工基线与上游两链合并盘点

| 项 | 值 |
|---|---|
| 分发器预建基线 | main@3c6f9864（worktree node-hold 分支起点） |
| 开工动作 | `git merge main` → fast-forward 至 **7ba589f3**（零冲突：3c6f9864 本就是 main 祖先） |
| 上游链 1（排序在前） | result-delivery-fix（五向量收尾）→ merge commit **2b064487**（`git merge-base --is-ancestor` 实核在 main） |
| 上游链 2 | wait-timeout-fix（R1-R4）→ merge commit **4ba50fa0**（实核在 main） |
| merge 后基线 | 7ba589f3（toast-glass，纯前端域，与本次 scala 域零交集） |
| 合并时 main 是否又前进 | 否——合并回 main 前复检 HEAD 仍 7ba589f3，worktree 内 `git merge main` 返回「已经是最新的」，无新冲突面 |
| NodeEngine 同文件冲突预判 | 五向量 V8 改 deliverOut/deliverToNebula 记账域 + redeliverUnconsumedNebulaResults；本次改 completeNode 分流/heldNode/releaseNode/startNode 幂等列表——函数域不交叠，实际合并 **零冲突**（逐 hunk 复核成立） |

## 二、改动清单（文件/函数级，对照设计 §2.1–§2.3 / §2.5）

### 2.1 数据模型（ProjectTypes.scala，~14 行）——对照设计 §2.1

| 改动 | 落点 |
|---|---|
| `NodeLifecycle.Held = "held"` | Terminal 集合**不加** held（设计红线：非终态是全部既有机械零修改正确工作的关键——sweepExpired 不扫、整链归档判定天然排除、deps 闸门不触发、startNode 幂等跳过） |
| `NodeDef.hold: Boolean = false` | deps 字段之后（设计建议位）；withDefaults 旧 flow-map.json 无键解码 false，零迁移 |
| `NodePayload.buildNodeJson` hold 条件序列化 | `if node.hold then List("hold" -> …)`，与 deps 条件字段同构——NodeEventPushSpec 字段集断言对无 hold 节点零影响（NodeHoldSpec T1 双向断言锁定） |

### 2.2 完成路径（NodeEngine.scala，~120 行）——对照设计 §2.2

| 改动 | 落点 |
|---|---|
| `completeNode` 分流 | 语义优先级：BlockedReader 分流（**blocked 优先于 hold**，BLOCKED 锚定即使 hold 节点也走 blockedNode+FeedbackRouter 重入，不被 held 吞掉）→ hold 分支（fresh 快照判定 `status==Running && hold`）→ `completedNode` 原路径 |
| `completedNode`（新私有方法） | 既有 completed 原路径原样提取（hold 分流落点），语义零改动——diff 审查可证逐行等价 |
| `heldNode`（新私有方法，与 blockedNode 同构四动作） | ①事务内 fresh-read 守卫（`status==Running` 才写，拒写已消失/状态已变——R2 纪律同款）→ status=Held、result=**全文**、completedAt=now、ttlExpireAt=None（永不过期=blocked「待办语义」先例）②emitEvent nodeUpdated（NodePayload 同构载荷，不加新 WS 事件类型）③不调 deliverOut/settleDeps（传播停止=blocked 同款）④deliverToNebula（"[Node 'X' completed — held, awaiting release]\n<全文>"，eventType="held"）+ FlowMapEventLog "held" 摘要 |
| `startNode` 幂等跳过列表 | 追加 `Held`（防御性一行，纵深防御风格） |
| `NodeEngine.ReleaseNoteMarker` 常量 | `"== 用户补充（放行时注入） =="`——releaseNode 与测试共用单点 |
| FlowMapEventLog 文档 | type 枚举补 held/released |

### 2.3 放行（NodeEngine.releaseNode + NodeTools.NodeEdit，~120 行）——对照设计 §2.3

`releaseNode(nodeId, note): IO[Either[String, String]]`（引擎单点执行序列）：

1. **fresh-read 守卫**：节点存在且 status==Held，否则 `Node 'X' is not held (status=…) — release only applies to held nodes`
2. **单事务 mutate**（同一 FlowMapState 原子改两节点）：本节点 Held→Completed + ttlExpireAt=now+TtlDisplayMs（**completedAt 保留 held 时刻**——工作完成时刻；显示倒计时从放行起算）+ note 非空时 out 目标（≠Nebula）且 status∈{wiring,pending} → target.task += "\n\n== 用户补充（放行时注入） ==\n<note>"（目标不可能已运行：其 in 含本节点而本节点从未投递 barrier 永不归零；守卫仅纵深防御，异常时不注入并在返回文本 WARNING 说明）
3. **事务后传播链**：emitUpdated 同步 → deliverOut(completed) → settleDeps(completed) **fork 到独立 fiber**（`.start` + handleErrorWith 日志）——startNode 同步等下游终态，直接调用会把 NodeEdit 工具 fiber 卡到下游链条跑完（runDetached 教训同款）；下游启动前全部闸门（目标自身 deps、barrier 复核）由 startNode 原样把关
4. **FlowMapEventLog "released"** + note 摘要

注：held 通知为 fire-and-forget（不传 nodeId 不进 V8 nebulaDeliveredAt 记账）——设计文档签定稿无记账参数；held 通知是状态通报非结果投递本体，且 held ∉ {Completed, Failed} 重投扫描本就不覆盖，不记账避免对「held 期改接 out=Nebula 后放行」的记账污染（防 completed 结果被旧账跳过补投）。

### 2.4 NodeTools.scala（校验八条对照设计 §2.5，逐条落地）

| # | 设计校验 | 落地 |
|---|---|---|
| 1 | hold=true 要求 out 为节点 id（out=Nebula 拒） | create 路径 `hold && out.contains("Nebula")` 拒；edit 路径按**最终 out**（finalOut）判定（同调用改接出节点边也认）；文案逐字对齐文档 |
| 2 | hold 只能 wiring/pending/running；completed/终态/held 拒（设或撤） | editNode earlyReject 链 `holdStatusOk` 检查（blocked 属终态族同拒——重激活后另设）；写回 mutate 内状态守卫双重保险 |
| 3 | release 仅接受 held；与 task/agent/in/deps/out/abandon 任一同传拒 | release 分支 conflicts 检查（决策④「整调用拒绝」从宽覆盖全部编辑参数：task/agent/in/deps/out/abandon/skill/mcp/worktree/preset/maxRetries/hold）；held 判定在 releaseNode fresh 守卫单点；可行动文案指引「先 release 再单独 NodeEdit 改接」 |
| 4 | note 仅与 release 同用，单独出现拒 | editNode 最前 `note.isDefined && !release` 拒（先于 abandon 分支，杜绝任何误组合静默吞参） |
| 5 | hold 编辑 running 节点合法 | holdStatusOk 显式含 Running；写回不受「输入冻结」检查影响（完成行为开关不属冻结域） |
| 6 | findDuplicateDispatch 追加 Held | 状态集 `Running ∥ Held ∥ Terminal`——held 任务未完，同 agent+task 重派仍报「疑似重复派发」 |
| 7 | abandon 接受域追加 held | 机械零分支改动（fresh 守卫 `status != Running` 天然放行 held→cancelled+TTL+审计）；abandonNode 文档、误杀防护文案、NodeEdit description/schema abandon 描述同步注明 held |
| 8 | NodeCancel 对 held 既有 no-op 分支零改动 | 确认：`status != Running → "not running (status=held) — no-op"`（T8 锁定）；NodeCancel description 注明 held 出口是 release/abandon 而非 cancel |

其余同步：NodeEdit description 增 hold/release/note 参数段 + Hold/Release 语义段（自包含契约，分发器 LLM 可见）；create 路径支持 hold=true 建链时预置闸点（校验①同样拦截）；归档分支 forbidden 集扩展（hold/release/note 不适用于归档——held 非终态永不归档，归档必为终态）。

## 三、与设计的偏差（逐条）

| # | 偏差 | 性质与理由 |
|---|---|---|
| 1 | held 通知不进 V8 nebulaDeliveredAt 记账（fire-and-forget） | 有意取舍非遗漏：设计文档成稿于 V8 之前（签名无 nodeId）；记账会造成「held 期改接 out=Nebula→放行时 completed 结果被旧账挡住补投扫描」的边角缺陷。结果不丢——结果本体滞留节点，release→deliverOut 走 completed 路径的 at-least-once 记账 |
| 2 | release 冲突参数集从宽（§2.5 #3 列六参数，实现覆盖全部编辑参数） | 决策④「整调用拒绝」字面优先：skill/mcp/worktree/preset/maxRetries 同传同拒，防任何半放行半编辑歧义 |
| 3 | 事务后传播链在 releaseNode 内部 fork（引擎方法自带 .start） | 设计 §2.3「同一 detached fiber 顺序推进」的落地选择：引擎单点要求（NodeTools 只转发）+ 工具 fiber 立即返回（runDetached 教训）双约束下的最小实现；NodeTools 侧无需再包 runDetached |
| 4 | 其余零偏差——状态命名 held/全文通知/仅 NodeEdit release/不设上限/方案 B/LoopNode 不处理，全部按拍板 a 落实 | — |

## 四、测试结果（NodeHoldSpec 十用例，全绿）

| # | 用例 | 结果 | 关键断言 |
|---|---|---|---|
| T1 | hold 完成→held | ✅ | status=held；result 全文落库（`hold-result-ALPHA`）；ttlExpireAt=None；completedAt 有值；下游 deliveredTo 空+未启动（CaptureLlm 无 `=== Node hold-a ===` 输入）；deps 依赖者仍 wiring；Nebula 恰收 1 条 eventType="held" 全文通知；NodePayload hold 条件序列化双向断言（held 带键/无 hold 不带键） |
| T2 | release→投递链 | ✅ | held→completed+TTL 起算；放行前 C 已投/A 未投/barrier 未归零；放行后下游收两份结果并启动，输入含两段 `=== Node … ===` 且含 held 结果全文 |
| T3 | release+note | ✅ | 下游 task 含 marker+note 文本（单事务原子）；下游输入（buildInput 沿 task）携带追加段与上游结果 |
| T4 | BLOCKED 优先 | ✅ | status=Blocked 非 Held；blockCount=1；blockedFeedback 结构化落库；下游未收（传播停止）；FeedbackRouter 路由被调证据=escalate-only 档升级气泡（eventType="blocked"）且无 held 气泡 |
| T5 | 重启恢复 | ✅ | 重建 store（open 重载）→ held/result 全文/hold 标志/ttlExpireAt=None 原样；新引擎 releaseNode 照常成功→下游收投并启动 |
| T6 | 校验负向五连 | ✅ | hold+out=Nebula 拒（"nothing to hold"）/completed 设 hold 拒（"before completion"）/release 非 held 拒（"not held"）/release+task 同传拒（"standalone action"）/note 单独拒（"only valid together with release"）；负向零副作用（节点状态/hold 标志未被翻转） |
| T7 | dup-dispatch | ✅ | held 同 agent+task 重派拒「疑似重复派发」 |
| T8 | abandon held | ✅ | abandon→cancelled+TTL+审计事件（flow-map-events.jsonl）；NodeCancel 对 held 命中 no-op（"not running (status=held)"）且状态原样 |
| T9 | held 改接后 release | ✅ | held 期 NodeEdit out→新目标成功且不触发投递；release 后新目标收结果并完成、输入含结果全文；旧目标全程未收未启动 |
| T10 | 归档排除 | ✅ | `Terminal.contains(Held)`=false 机制根断言；sweepExpired 恰扫走过期 completed（归档区可查）而 held 活动区原地不动 |

单跑耗时 11s。测试基建复用 NodeBarrierDeliverySpec 模式（CaptureLlm/临时 dataRoot/种子 wiring 节点）+ NebulaDeliveryRedeliverySpec 的根会话 recorder 模式（AgentRecord 注册，held/escalated 通知可断言）。

## 五、变异验红（红绿证据原文）

**M1（任务指定变异）：去掉 completeNode hold 分支（直落 completed 原路径，复现「完成即投递」旧行为）**

- 变异方式：completeNode 的 `case None =>` 分支替换为直接 `completedNode(nodeId, resultText)`（删 hold 分流getNode 预读）
- 红证据（sbt testOnly 输出原文摘录）：
  ```
  [error] Failed: Total 10, Failed 8, Errors 0, Passed 2
  ==> X …T1: hold node completes → held … 15.441s java.lang.AssertionError: waitUntil: condition not met in time
  ==> X …T2: release → held to completed, delivery chain runs… 15.156s (同上)
  ==> X …T3: release+note … 15.108s (同上)
  ==> X …T5: restart recovery … 15.108s (同上)
  ==> X …T7: held node same agent+task re-dispatch refused … 15.064s (同上)
  ==> X …T8: abandon held … 15.109s (同上)
  ==> X …T9: rewire held node to a NEW target … 15.149s (同上)
  ==> X …T10: held chains excluded from archival … 15.134s (同上)
  ```
  8 红=全部依赖 hold 分支的用例（hold 节点完成即投递、永不进入 held）；T2/T3/T9 的 release 因「not held」失败，T8 的 NodeCancel no-op 断言失败（status=completed）——红点均在预期断言上。
- 绿的说明：T4/T6 在 M1 下保持绿——T4 锁的是 BlockedReader 分支（M1 不触碰），T6 是工具层校验（不受引擎分支影响），符合变异靶向。

**M2（补充变异）：分流顺序反转（hold 判定提到 BLOCKED 解析之前，复现「hold 吞掉 BLOCKED」）**

- 动机：设计 §2.2 的核心不变量是「blocked 优先于 hold」的**顺序**，M1 触不到它——T4 的牙齿需独立验证
- 红证据：
  ```
  ==> X …T4: BLOCKED anchor wins over hold → blocked (not held), blockCount+1, FeedbackRouter invoked  15.121s java.lang.AssertionError: waitUntil: condition not met in time
  [error] Failed: Total 10, Failed 1, Errors 0, Passed 9
  ```
  T4 单独红（BLOCKED 输出被 held 吞掉→waitStatus Blocked 超时），其余 9 绿——顺序不变量精确锁定。

**恢复后绿**：`git checkout -- NodeEngine.scala` 复原 → NodeHoldSpec 10/10 绿（11s）。两变异均未提交（工作区临时态，git checkout 恢复，提交历史干净）。

## 六、全量 sbt（worktree 内前台真实跑）

```
Passed: Total 2191, Failed 0, Errors 0, Passed 2191, Ignored 7
Total time: 491 s (0:08:11.0)
```

Bash timeout 3600000ms 一次跑完，无 background、无中间汇报、无锁等待重试（一次成功）。

## 七、分支与 merge commit

| 项 | hash |
|---|---|
| node-hold 分支实现 commit | `f6a0b846`（5 文件：ProjectTypes/NodeEngine/NodeTools/FlowMapEventLog/NodeHoldSpec，+957/−53） |
| **merge commit（main，--no-ff）** | **`a97d69b0`**（零冲突；未 push，origin 未动） |
| 基线链 | 3c6f9864（预建）→ merge main → 7ba589f3（含 2b064487 五向量 + 4ba50fa0 R1-R4）→ f6a0b846 → a97d69b0 |

## 八、合并后子集复跑（主仓）

NodeHoldSpec + NodeDepsSpec + NodeBarrierDeliverySpec + NodeEdgeRepairSpec + NodeConnectionPolicySpec：

```
Passed: Total 41, Failed 0, Errors 0, Passed 41
```

## 九、清理确认

- `git worktree remove .nebflow/worktrees/node-hold` ✅（git worktree list 无 node-hold）
- `rm .nebflow/node-hold` 软链 ✅（ls 确认不存在）
- `git branch -d node-hold` ✅（历史经 --no-ff 保全在 main）
- 主仓工作区干净（git status --porcelain 空）；全程未 push、未动 origin
- 附注：主仓 merge 钩子触发被动 QC 审查（无 gh 权限自动暂停，历次合并同款，无阻塞裁决）

## 十、生效说明

**运行时生效需前端产物重建 + 宿主重启——本次未做任何重启动作（纪律红线）。** 现有运行宿主行为不变：分发器不使用 hold/release/note 参数前零行为差异（加性字段缺省即旧行为；held 状态只有显式 hold=true 才会产生）。合并进 main 即交付。

## 十一、遗留

1. **前端第 2 批**：held 状态色/⏸ 徽标（琥珀）、气泡 label "held" 映射、（可选）held 卡片放行按钮+REST 端点（REST 调同一 engine.releaseNode）——本批严禁顺手做，未做
2. 7 天未放行重提醒（TtlTick 顺带 + heldNotifiedAt 去重）= P2 可选，按拍板不做
3. held 通知为 fire-and-forget：根会话恰缺的崩溃窗口内该条通报会丢（Nebula 仍可经 NodeList 看到全量 held 清单；结果本体无损）——如需 at-least-once 通报可列后续评估
4. 校验② 当前拒绝 blocked 节点上直接设 hold（含同时重激活的混用调用）——需「重激活后另设 hold」两次调用；如分发器实践反馈繁琐可再放宽

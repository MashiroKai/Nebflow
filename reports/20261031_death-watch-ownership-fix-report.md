# death-watch 归属缺陷修复批报告（台账 #1193）——2026-10-31

批：death-watch 归属缺陷修复（作者令 2026-10-31 最高优先：一小时内同一缺陷连埋两个 914 项全量回归，阻塞液态玻璃实现批，canvas 批 + ask mode 批的派发闸门）。
分支：`delegate/delegate-nebflow-10b6f701`。纪律：独立 commit，不 push。

## 一、根因（一句话）

**后台任务归属被绑在了「ShellSession 实体」上，而 ShellSession 的陈旧判定只看 Bash 调用活动（30min TTL）不看会话死活**——delegate 回合结束 idle 等结果期间零 Bash 调用，TTL 超时后下一回合第一个 Bash 触发 `doGetOrCreate` 的 stale 分支 `replace`：`old.kill()` 把仍在跑的全量回归进程树杀掉、任务图清空，新空会话复用同一 sessionId；BgTaskRegistry 行残留（sessionId 相同、会话活着），death-watch 探针 `probeJobLiveness` 返回 None ⇒ 判 `Dead("not owned by session")` ⇒ 收割记账——作者看到的「owner lost: not owned by session」是**收割记账消息**，真正的进程被杀发生在前的**会话静默替换**（零日志）。

## 二、实证链（stand.log + delegate 转录 + 留存区 meta，三段互证）

- job **83907ab5**（真·误埋）：09:58:13 启动（delegate-nebflow-8b2f50d3，全量回归）→ 10:02:36 查询 "running" → **10:03:00 回合结束 idle（bg-turn-end-barrier waiting=1）** → 42 分钟零 Bash 调用（30min TTL 于 ~10:32 超时）→ 10:45:16 下一回合**首个工具调用即查询本任务**，370ms（replace 杀进程树的耗时），回执 `[Background job pending]`（新会话任务图无此任务）→ 10:45:40.534 death-watch 收割「not owned by session」。留存区 `83907ab5.meta.json`：`status=cancelled, errorHint="owner lost: not owned by session"`，日志定格在 **[816/914]**（与台账「跑到 816」逐字吻合），mtime=10:45。
- job **f25b9f1f/8641d0d5/afd8e45b/01a4391d**（次·假证）：delegate 转录（`~/.stand/sessions/delegate-nebflow-8b2f50d3.json`）显示均为**模型显式 cancel**（`cancel_background_job: true`，如 "Cancel redundant rerun; base comparison is the critical path"）。但 BashTool/面板的本地取消腿**只出会话任务图、不取 registry 行** → 行残留 → death-watch 5~30s 后以「owner lost」二次收割，账本/WS 帧留下「引擎埋任务」假证——作者目睹的第二个「被 death-watch 终止」实为**取消后的二次收割消息**（进程死于模型自己的取消，非引擎）。

## 三、修复（diff 摘要，+69/−6）

| 文件 | 改动 |
|---|---|
| `core/tools/shell.scala` | **根修**：新增 `hasLiveBackgroundWork`（任务图内存在 deferred 未落的后台任务；读面纪律同 probeJobLiveness——不 touch/不 checkAlive/不取锁）+ spec 缝 `backdateLastAccessedForSpec`。`doGetOrCreate` stale 分支：有在飞工作 ⇒ **续约复用**（touch + 同实例返回，info 日志 `[bg-ownership] … renewed in place (idle != dead, #1193)`）；真无在飞工作才走既有 `replace`。`replace` 补 info 日志（原零日志，本次事故全程无迹可循的盲区）。**终态收殓语义不变**：`reclaimSession`/`destroySession` 不经本判定，会话真死照杀照收 |
| `core/tools/BashTool.scala` | cancel 路径：cancel 成功 ⇒ `BgTaskRegistry.unregisterReturning(jobId)`（first-terminal-wins 幂等）+ `BgTaskOutputStore.finalizeTask(cancelled, "Cancelled by agent (tool call)")`——消灭「显式取消后 owner-lost 二次收割假证」。回执文案 `[Background job cancelled] Job ID: X` 逐字不变 |
| `gateway/WsSystemHandlers.scala` | 面板本地取消腿同补 `unregisterReturning`（remote 腿经 BgTaskCancel.cancelOne 本就取行；此前仅本地腿漏） |
| `test/.../BgOwnershipSessionLifecycleSpec.scala` | 新增 4 用例（见 §四） |

修复语义对照要求：后台任务归属随**会话**生命周期（会话实体在在飞工作存续期间不得被 TTL 回收）✓；回合结束不转移不死亡 ✓；会话真死（终态）才进 death-watch/收殓 ✓（reclaimSession 路径未动）。

## 四、回归证据（分批前台跑，遵守 #1193 绕过纪律）

- `Test/compile` 绿（fatal-warnings 下零告警）。
- 新 spec `BgOwnershipSessionLifecycleSpec` **4/4 绿**：
  - ① 全链「起后台任务 → 回合 idle 超 TTL → 下回合 forSession」：会话实体同实例续命、任务图保留、进程仍活、`BgTaskLiveness.probe = Alive`、`reapDead` 零收割、行在册；
  - ② 陈旧且无在飞工作 ⇒ 仍 replace（空会话回收语义不回归）；
  - ③ `reclaimSession` 真终态照杀照注销（收口语义不回退）；
  - ④ BashTool 显式取消 e2e：行取走、death-watch 无二次收割、账本零 owner-lost 假证、留存区 cancelled、回执文案契约逐字不变。
- 受影响族 batch 1（bg-task/shell/BashTool 12 spec）：**75 passed / 0 failed**。
- 受影响族 batch 2（NodeBgReclaim / NodeBgCompletionGate / NodeDeadSessionAutoReap / BgWaitCapLedger / DelegateBgWaitHold / TaskStuckWatcher / AgentControlTool / BashActivityBridge 8 spec）：**68 passed / 0 failed**。
- 合计：**147 passed / 0 failed / 0 errors**（含新 spec 4 用例）。

## 五、遗留观察（本批不动）

- 模型在 83907ab5「pending」后自行开 rerun 又连环取消（f25b9f1f→8641d0d5→afd8e45b→01a4391d→5089c1bd），属模型行为面；本修复后取消不再产生 owner-lost 假证，作者可辨。
- `BgTaskLiveness` 探针「会话活着但任务不在任务图 ⇒ Dead」的判定本身保留——它是**绝路清道夫**（真·owner-lost 形态仍需收割）；配合根修后只在真绝路时触发。

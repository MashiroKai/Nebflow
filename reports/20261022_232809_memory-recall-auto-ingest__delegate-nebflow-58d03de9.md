# 记忆检索库自动入库（引擎守护挂接）—— 交付报告

交付物：主 agent 会话消息落盘后**自动增量进库**——引擎侧驻留守护按节流窗调
`tools/stand-recall --update --source auto`，写入 `<dataRoot>/memory-db/`；
人工触发从此不再是必经路径（作者令：「这个进库 应该要是我们使用stand自动的能力」）。

改动面（4 文件 + 1 新 spec）：

| 文件 | 性质 | 内容 |
|---|---|---|
| `src/main/scala/nebflow/core/MemoryRecallDaemon.scala` | **新增** | 驻留守护：定时脏探测 + 节流 + 子进程 `stand-recall --update` |
| `src/main/scala/nebflow/shared/Defaults.scala` | 追加 | kill-switch `nebflow.memoryRecall.enabled`（默认 true）+ 节拍/节流窗/超时常量 |
| `src/main/scala/nebflow/gateway/GatewayMain.scala` | 1 行装配 | boot 链 `hostWakeSensor *> memoryRecallDaemon *> …`（其他启动扫描器邻位） |
| `tools/stand-recall` | 加性增强 | `--source {manual,auto}`（默认 manual，语义零变）+ `STAND_DATA_ROOT` env 回退 + `--stats` 来源行 |
| `src/test/scala/nebflow/core/MemoryRecallDaemonSpec.scala` | **新增** | 8 用例回归钉（含端到端假数据根注入验证） |

## 1. 挂接点选择依据（本批核心裁定）

候选对照：

| 候选 | 侵入面 | 评价 |
|---|---|---|
| **A. 独立驻留守护（定时脏探测）——采用** | 持久化路径 **0 处改动**，仅 boot 装配点 1 行 | 触发延迟 ≤ 一拍（30s），对「记忆入库」粒度绰绰有余 |
| B. 消息落盘事件埋点（`saveMessagesForSession` 尾挂） | 全仓十余处调用点（AgentProcessing/AgentFinishTurn/ExternalSessionRunner/…） | 广侵入 + 热路径耦合；且「每条消息都触发」反而违背节流要求 |
| C. SessionStore 单点事件（append 后回调） | 1 处，但把守护生命周期接进 SessionStore 构造 | 耦合换不来收益——落盘 mtime 本身就是免费的事件源 |

**裁定 = A（作者令明示「优先独立守护……进程内 fs2 驻留任务即可，别造重框架」）**。
实现形态照抄仓内既有三先例（零新依赖、零新框架）：

- 驻留纤维 = `WakeSensor.launch` 同款（`IO.sleep *> tick *> loop`，`.start` 后台，纤维自愈 handleError）；
- boot 装配 = `projectTtlScanner` / `taskIndexBackfill` 邻位（server listen 前就绪，永不阻塞启动）；
- 单飞幂等写 = `INSERT OR IGNORE`（stand-recall 原有，守护不复制第二份口径）。

单拍门序（`MemoryRecallDaemon.tickRun`）：
kill-switch → **节流窗**（默认 ≥5 分钟，按尝试计）→ 工具解析 → **脏探测**（sessions/
stat 指纹，无变化 = `CleanSkip` 零子进程）→ 执行 `--update --source auto`。
稳态成本：每 30s 一拍数百次 stat（毫秒级）；真实 `--update` 实测 2.84s/10140 条，
占空比 ~1%。boot 首拍即补扫（停机窗内落盘的消息启动后 30s 内补齐，与
`taskIndexBackfill` 启动补扫同语义）。

失败纪律（作者令「失败静默重试不吵日志」）：首败 WARN 一行（带退出码/超时 + 输出尾），
连败降 debug，成功复位；失败占节流窗 ⇒ 重试频率上界 = 每窗一次，无重试风暴；
stand-recall 幂等 ⇒ 最坏后果 =「入库略陈旧」，下窗自愈，绝不污染主链路。

## 2. 数据根 / 排除面 / CLI 语义

- **数据根**：守护逐拍现读 `PathUtil.dataRoot`（CLI --home / STAND_HOME /
  NEBFLOW_HOME / 默认 ~/.stand）传 `--data-root`，并给子进程注入
  `STAND_DATA_ROOT` 同值 env——隔离实例只进自己的根，双实例（8080/8090）共享
  同根时并发 `--update` 由 sqlite WAL + busy_timeout + INSERT OR IGNORE 兜底。
- **排除面**：外部执行器（`external-`）/ team 子代理会话不入库——由 stand-recall
  既有分类规则**单点**承载，守护零复制零改动（spec 用例 4 实证）。
- **CLI**：`--source` 只写元数据（meta 表 `last_update_source` + manifest 同名字段），
  分类/排除/检索语义逐字节未动；无旗标 = `manual`，旧调用零变化。
  数据根解析加了一层 env 回退：`--data-root > STAND_DATA_ROOT > ~/.stand`。
- **`--stats` 来源标记**：新增一行 `source: auto|manual`（旧库无此 meta = manual）。

## 3. 验证输出（全部实跑）

### 3.1 端到端（引擎形态：假数据根 + 注入消息，不依赖真网关）

`MemoryRecallDaemonSpec`（sbt testOnly，真实守护纤维 + 真实 stand-recall 子进程，
8 用例全绿，连跑两轮稳定）：

```
MemoryRecallDaemonSpec: 8/8 passed
  + auto 入库端到端：假数据根+注入消息 → 守护纤维 → 节流窗内库自动前进且 source=auto
  + 节流窗：同窗内第二次拍 Throttled（不重复起子进程）
  + 脏探测：指纹无变化 → CleanSkip（零子进程）；会话文件有写入 → 再跑 Updated
  + 排除面保持既有：external- 执行器会话与 team 子代理会话不入库
  + NoData：空数据根（无 _index.json）→ NoData，不产生 memory-db
  + NoTool：三处候选全落空 → NoTool（不占节流窗，工具补上后下拍即可恢复）
  + kill-switch：nebflow.memoryRecall.enabled=false → tickRun Disabled 且 launch 零纤维空转
  + 工具解析：prop 显式覆盖 > <dataRoot>/tools > <cwd>/tools（仓根兜底命中真脚本）
```

端到端关键证据（失败取证面实测值，正式通过轮同路径）：
守护纤维首拍 `Updated(newMsgs=1, totalMsgs=1, 98ms)` —— 假数据根注入的用户消息
200ms 内入库；第二拍起 `Throttled`（节流生效）；节流窗外指纹无变化 `CleanSkip`
（零子进程）；注入第二条消息后库计数前进、meta `last_update_source=auto`、manifest
同名字段同步——即「主会话发消息 → 等节流窗 → --update 状态自动前进」的完整闭环。

验证期间排掉的一处 **spec 侧**（非产品）缺陷：测试体纤维的 `IO.sleep` 在本 harness
下被实测搁置 ~60s（守护纤维的 sleep 正常，298 拍准点），轮询律改为
`IO.blocking` 真线程 `Thread.sleep` + 同步查询后稳定——产品代码零涉及。

### 3.2 既有 10140 条数据零损坏

对**真库副本**（绝不触生产库）跑新版 `--update --source auto`，与原库逐面比对：

```
orig messages: 10140  copy: 10148     ← 只增（+8 = 上次人工更新后新增消息）
orig sessions: 197    copy: 197
orig attachments: 347 copy: 347
sessions with data loss: NONE
spot-check msg#5000 intact: True
FTS still functional on copy: True
copy meta: {schema_version: '1', last_update: '2026-10-22T23:12:10', last_update_source: 'auto'}
--stats 尾行: last_update: … / source: auto   ← 「auto」来源标记可见 ✓
```

`--doctor`（真根只读）：1007 条分类明细全数输出，645 排除（与 manifest 一致），
`✓ [main-current] d7c3cb54 agentName=Nebula（当前根会话）` 在列——分类/排除面完好。

### 3.3 构建

`sbt Compile/compile Test/compile` → `[success]`（`-Xfatal-warnings` 下零警告零错误）；
`MemoryRecallDaemonSpec` 连跑两轮全绿（见 3.1）。scalafmt/CI 口径：本批文件按
`.scalafmt.conf`（3.8.6/scala3/120 列）与 `.scalafix.conf` OrganizeImports 分组手排；
仓内既有的 scalafmt/scalafix 漂移（Main.scala、HealthMonitor 等与本批无关文件，
scalafmtAll 实测会重排 ~230 个文件）**不属本批**，未夹带。

## 4. 回滚与运维

- 回滚 = `nebflow.memoryRecall.enabled=false`（system prop，kill-switch 先例）+
  重启宿主 ⇒ 零纤维零子进程，完全回到人工 `--update` 现状（无半恢复态残留）。
- 工具定位三处候选：`-Dnebflow.memoryRecall.toolPath=`（显式）>
  `<dataRoot>/tools/stand-recall`（数据根惯例，~/.stand/bin/rg 同款）>
  `<cwd>/tools/stand-recall`（仓内开发形态；生产实例实测 cwd = 仓根，已命中）。
  三处全空 = 单行 WARN 后休眠（不刷屏），脚本出现后下一拍自愈。
- 节流窗/节拍/超时调参：`Defaults.MemoryRecall{MinIntervalMs,TickSec,TimeoutSec}。

## 5. 未做 / 边界（如实登记）

- 全量 GatewayMain 启动级 e2e 未做：boot 装配是编译验证的 1 行链式追加（与
  hostWakeSensor 同位同形态），守护行为由 spec 级真实子进程 e2e 承载——按作者令
  「测试用假数据根+注入消息，不依赖真网关」口径执行。
- 双实例共享同根场景未做专门 spec（并发安全由 sqlite 锁 + 幂等 upsert 结构性保证，
  见 §2）。
- CLI 未加「每 N 条消息」节流形态——作者令为二选一（≥5 分钟间隔**或**每 N 条），
  取前者（更简单且无需消息计数状态）。

—— delegate-nebflow-58d03de9 · 2026-10-22

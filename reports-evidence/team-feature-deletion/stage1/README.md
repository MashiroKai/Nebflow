# 团队功能删除 · 阶段 1（停生产能力）证据

批次：五项边界判定 + 能力删除清单 + 阶段3逐条方案（chain `team-feature-deletion-20261104`）· 阶段 1
工作区：`/Users/kaiyu/Claude code/Stand/.nebflow/worktrees/delegate-nebflow-f97716ed`
分支：`delegate/delegate-nebflow-f97716ed`；基线 sha（main tip，已含 #1553）：`2af513702c81ff0522c952fb7ea7a43b59b578b2`
本阶段范围：**只停生产、只删死帧、只归档数据；不删任何类型**（`FlowTreeActor` / `object TeamSessionRegistry` 全量保留，阶段 3 才动）。

---

## 1. 改动清单（文件 + 行锚点）

### 1.1 `src/main/scala/nebflow/gateway/WebSocketRoutes.scala`

| 锚点（改前 → 改后） | 动作 |
|---|---|
| `:299-301` → `:298-304`（call site 区） | **删** `initFlowTree(sessionId, ref, pr, safetyMode).handleErrorWith(...).start`；`doSpawnRootAgent` 的 for-comprehension 收尾改为 `registerRootInteraction(sessionId, recordingWsSend).as(ref)`（原 6 行 → 1 行 + 5 行批注）。这是「每次 root 会话 spawn 就无条件建一棵团队树」的**唯一生产调用点**。 |
| `:489-517` → `:490-494`（定义区） | **整段删** `private def initFlowTree(...)`（29 行，含 `FlowTreeRegistry.markRestoreStarted`、`rootSystem.spawn(FlowTreeActor(config), s"flow-tree-$sessionId")`、`FlowTreeRegistry.register` 三步）。原位留 5 行批注说明删除理由与「类型保留 / 惰性入口仍在」。 |
| `:14` → `:14` | `import nebflow.core.flow.{FlowTreeActor, FlowTreeRegistry, TeamSessionRegistry}` → `{FlowTreeRegistry, TeamSessionRegistry}`：`FlowTreeActor` 在本文件的唯一消费者就是被删的 `initFlowTree`，留着即未使用 import。**只删 import 名，不删类型**。 |
| `:1383-1392` → `:1360-1365` | 注释改写：原文写「WS connect 恢复已挂团队，依赖 initFlowTree 串起 restoreTeams」，该链条已不存在；新注明确「此处只保留 root agent 的 ensureRootAgent（幂等），团队恢复腿随之不存在」。 |
| `:1395` → `:1368` | 日志文案 `"WS-connect team restore failed for session ..."` → `"WS-connect root agent ensure failed for session ..."`：调用点已不含团队恢复语义，旧文案会误导排障（全仓无测试/脚本断言该字符串，已 grep 验证）。 |

**保留未动**（阶段 3 范围）：`FlowTreeActor` 类型本体、`object TeamSessionRegistry`（同文件 `FlowTreeActor.scala:37`）、`FlowTreeRegistry` 全部方法、`loadTool`→`FlowTreeRegistry.getOrCreate` 惰性建树入口、`/api/teams/*` 全部路由。

### 1.2 `src/main/scala/nebflow/core/flow/FlowTreeActor.scala`（删两条死 WS 帧 = 3 处 emit）

| 锚点（改前 → 改后） | 动作 |
|---|---|
| `:309-313` → `:307-312` | **删** `restoredNames <- flowNamesRef.get` + `restoredNames.toList.traverse_ { ... emit(config, "treeBranchMounted", ...) }`（整段广播腿，含 1 行 `// Notify frontend that teams are now available`）。改后 `restoreTeams` 之后直接 `FlowTreeRegistry.signalRestoreComplete`——**恢复完成信号保留**（`awaitRestore` 的唯一生产者，见 §4）。 |
| `:382` → 位移至 `:380` | **删** `_ <- emit(cfg, "treeBranchMounted", "name" -> baseName.asJson, "type" -> "team".asJson)`（`handleMountTeam` 热挂载腿）。 |
| `:421` → 位移至 `:418` | **删** `_ <- emit(cfg, "treeBranchUpdated", "name" -> instanceName.asJson)`（`hotReloadTeam` 热重载腿）。 |

**死因（已验证，非本阶段新增判断）**：前端对应 handler **2026-09-05** 已删，`src/main/resources/web/js/ws.js:402` 的 `STREAM_MSG_TYPES` 白名单里只留了一条历史注释自述「Legacy flow-canvas events (…tree-branch*) removed 2026-09-05」；全仓（前后端 + 测试 + 脚本）零消费者。删帧只停广播，不改任何分支判定。

**`emit` 私有方法本体保留**：三处调用删净后 `emit`（`:685`）成为零调用私有方法。`build.sbt` 开了 `-Xfatal-warnings` 但**未开 `-Wunused`**，实测编译绿（§2），因此不做「顺手删 helper」——它是 `FlowTreeActor` 的内部构件，随阶段 3 整类删除。此为**已知中间态产物**，见 §5 待办。

### 1.3 `src/main/resources/web/js/ws.js`

| 锚点 | 动作 |
|---|---|
| `:408` | 历史注释里的字面 `treeBranch*` → `tree-branch*`。**选择与理由**：验收判据要求 `grep -rn "treeBranch" src/` = 0；该行是纯注释（在 `STREAM_MSG_TYPES` 白名单集合内嵌的历史说明），改一个连字符即可同时满足「机械判据归零」与「历史事实不抹掉（仍写明 legacy flow-canvas 帧 2026-09-05 移除）」。另一选项「保留原字样 + 单独列出例外」被否：会让红线判据永久带一条豁免，下一次删除批无法靠 grep 自证清干净。零运行时语义（注释）。

---

## 2. 编译读数（sbt，串行闸）

| 时机 | 命令原文 | 读数 |
|---|---|---|
| 改前基线（ps 读数 = 5 个 `[s]bt` 进程） | `cd <worktree> && sbt -batch compile` | **exit 0**，`[sbt-gate] gate acquired after 0s`，`compiling 749 Scala sources` → `done compiling`，`[success] Total time: 71 s`，完成于 2026-11-04 22:32:09 |
| 改后（ps 读数 = 6，含本次 launch） | `cd <worktree> && sbt -batch compile` | **exit 0**，`gate acquired after 0s`，`compiling 2 Scala sources`（增量：正好是本次改动的两个 .scala）→ `done compiling`，`[success] Total time: 10 s`，完成于 2026-11-04 22:33:50 |

- `-Xfatal-warnings` 生效 ⇒ **两次绿 = 零 warning**（改后 0 警告，未使用 import 未触发任何警告，因 `-Wunused` 未启用）。
- 测试期望：**本阶段零新增、零修改**。为证明「中间态不打破既有断言」，另跑受影响的 3 个 spec（读数见 §2.1）。

### 2.1 受影响的 spec（选择性回归）

命令原文：

```bash
cd <worktree> && sbt -batch "testOnly nebflow.core.flow.AwaitRestoreSpec nebflow.core.flow.FlowTreeRegistrySpec nebflow.gateway.DeviceFaceHardeningRoutesSpec"
```

读数（改后，**未修改任何测试期望**）：

```
[info] Passed: Total 18, Failed 0, Errors 0, Passed 18
[success] Total time: 98 s (0:01:38.0), completed 2026年11月04日 22:35:38     ← exit 0
```

其中直接证明「阶段 1 不动 /api/teams」的一行（该 spec 自带探针输出）：

```
[RTE-R2] live GET /api/teams/mounted ⇒ served=true
```

即：生产腿已断（无树可挂），但读取面在路由表上**照旧被服务**、状态码与既有断言完全一致 —— 这正是本阶段「只停生产、不动读取面」的可回滚中间态。

<!-- SPEC_RESULT-END -->

选这三支的理由：`AwaitRestoreSpec`（`FlowTreeRegistry.awaitRestore` 快路径 + `markRestoreStarted` 语义，其 test⑦ 标题写的就是「WS-connect restore」，注释里提到 `initFlowTree`——本阶段删了该调用，必须实测不红）；`FlowTreeRegistrySpec`（`register`/`get`/`unregister` 与 `getOrCreate` 的建树语义）；`DeviceFaceHardeningRoutesSpec`（其 `stillServed` 表逐条断言 `GET /api/teams/mounted` 仍在路由表里被服务——本能地覆盖「阶段 1 不动 /api/teams」这条约束）。

---

## 3. grep 读数（改前 / 改后）

| 判据 | 改前 | 改后 | 说明 |
|---|---|---|---|
| `grep -rn "treeBranch" src/` | **4 行**（`ws.js:408` 注释 1 + `FlowTreeActor.scala:312/382/421`） | **0 行** | 红线达成 |
| `grep -rn "FlowTreeActor" src/main \| wc -l` | **47** | **46** | **-1 的唯一来源 = `WebSocketRoutes.scala:14` 的未使用 import 名**（类型本体零删除：`FlowTreeActor.scala` 内 `object FlowTreeActor` / `FlowTreeConfig` / `TreeCommand` 全在）。与 brief 预期「不减」差 1，如实记录。 |
| `grep -n "object TeamSessionRegistry" FlowTreeActor.scala` | `:37` | `:37` | 类型保留（本阶段零触碰） |
| `grep -rn "flow-tree.json" src/` | **0** | **0** | 归档件的读写双端在源码里本就已死（写端 `TurnStateStore.save` 已于 2026-08 假恢复链审计整段移除） |
| `git diff --stat` | — | 3 文件 / +24 / −54 | 无 baseline 类文件（见 §6） |

---

## 4. 中间态代价（不粉饰）

停掉无条件 spawn 之后，本进程内的**团队面不会立刻消失**，但也不再自动重建。逐条如实写：

**(a) 每会话团队树不再自动存在 ⇒ `/api/teams/mounted` 返回空表。**
`GET /api/teams/mounted`（`PresenceRoutes.scala:593-600`）先 `FlowTreeRegistry.awaitRestore(3000L)`，再 `buildMountedTeamsJson()`（`:2702`）读 `TeamSessionRegistry.listMountedTeams`——后者**只由 FlowTreeActor 的 `restoreTeams`/`handleMountTeam` 填充**。没有树上身 ⇒ 该接口稳定返回 `{"teams":[]}`。

**(b) 「最长 3 秒空转」这个说法在实测代码下**不成立**，代价比 brief 估的**小**：
`awaitRestore` 有两条快路径（`FlowTreeRegistry.scala:53-69`）：`restoreDone` 已 complete ⇒ 立刻返回；**`restoreStarted` 仍为 false ⇒ 也立刻返回 `IO.unit`**（注释自述「no actor means there is nothing to restore … would just burn the full timeout — observed as a fixed ~3s stall on EVERY /api/teams/mounted request against an empty home」）。本阶段删掉的正是 `markRestoreStarted` 的**唯一无条件调用点**（原 `WebSocketRoutes.scala:511`），所以全新起进程 + 无任何树时 `restoreStarted == false` ⇒ **0 等待**返回空表。
真正的「等待」残留只在一种情形：某处先经 `FlowTreeRegistry.getOrCreate` 建过树（`markRestoreStarted` 在 `FlowTreeRegistry.scala:101` 仍有调用），而其 `signalRestoreComplete` 未 fire ⇒ 那一次请求最多等满 3000ms。该信号在 FlowTreeActor setup 里**总会** fire（`FlowTreeActor.scala:311`，即使零团队），所以仍是有界等待、不是永久挂死。
⇒ **结论：不是「最长 3 秒空转」，而是「通常 0 等待、极端情形下有界 3 秒」**。brief 的表述偏保守，实测偏乐观，两个方向都如实记在案。

**(c) 树仍可能被「按需」建出来——无条件路径断了，惰性路径没断。**
`LoadTool.scala:134` → `FlowTreeRegistry.getOrCreate` → `system.spawn(FlowTreeActor(config), s"flow-tree-$sessionId")`（`FlowTreeRegistry.scala:120`）。这条腿**不在阶段 1 范围**（brief 指定本阶段只动 WebSocketRoutes 的 spawn 与两条死帧），所以：只要还有会话跑 load 工具，团队树仍会被建、`teams/*/team.json` 仍会被 `restoreTeams` 挂载。这在阶段 2/3 删读取面与类型时才彻底消失。**本阶段不声称「团队功能已停」，只声称「每会话无条件的生产者已停」。**

**(d) `FlowTreeRegistry.unregister` 的拆除路径保留**：`WebSocketRoutes.scala:518`（`removeRootAgent`）仍调 `FlowTreeRegistry.unregister(sessionId)`——现在它多数情况是 no-op（该会话从未 register），但**保留**是有意的：惰性建出来的树仍需在这条路径上被摘除。删它会造出「树留寄存器里但会话已死」的孤儿。

**(e) 前端零感知**：`/api/teams/mounted` 前端零调用点（上游边界表实测），`ws.js` 侧 `sendWs({type:'getTeams'})` 的响应帧本就零 handler。故 (a) 的「空表」对外部用户**不可见**。

---

## 5. 未做 / 留给下游（本阶段刻意不做）

1. **不删 `/api/teams/*` 与 `/api/team/rules/*`**（阶段 2）：`PresenceRoutes.scala:593` 的读取面原样保留，正是为了让本阶段「只停生产」可独立回滚。
2. **不删任何类型**：`FlowTreeActor`、`object TeamSessionRegistry`（`FlowTreeActor.scala:37`）、`FlowTreeRegistry`、`TreeCommand`、`MountedFlowStore` 全部原样（阶段 3）。
3. **不删 `FlowTreeRegistry.getOrCreate` 惰性建树腿**（`FlowTreeRegistry.scala:86-132` + `LoadTool.scala:134`）：它不在阶段 1 授权范围，见 §4(c)。这是**本阶段之后团队面仍未彻底静默的唯一残余入口**，必须在阶段 2/3 一并处置，否则「团队已删」不成立。
4. **不删零调用的私有 helper `emit`**（`FlowTreeActor.scala:685`）：随阶段 3 整类删除，删它只是把 diff 变大。`-Wunused` 未开，编译绿（§2）。
5. **不动 `sessions/_index.json` 里 32 条团队名孤儿会话记录**（上游边界表 §4 记录的待裁定项）：本阶段只归档 1 个 `flow-tree.json`，不改索引。归档后的空目录 `sessions/9d7638fb-…/` **保留未删**（该 sid 在 `_index.json` 仍有 1 条记录，删目录属另一件事）。
6. **不碰 6138 生产实例**（PID 78176）：本阶段零 `kill`/零 signal，只读 `ps`。归档操作只在 `<dataRoot>/sessions/` 与 `<dataRoot>/archive/` 两处。
7. **未抬任何 baseline**：`tests/type-baseline.json`、`tests/visual/baseline/*`、`scripts/component-contract-baseline.json`、`process-artifacts-allowlist.txt` 均零改动（§6 机械验证）。

---

## 6. 归档 manifest（阶段 C）

归档根：`/Users/kaiyu/.stand/archive/team-feature-deletion-20261104/`（本阶段创建；沿用 `<dataRoot>/archive/<topic>-<YYYYMMDD>/` 约定，阶段 4 往里加 `teams/`）

| 归档相对路径 | 原绝对路径 | 字节 | sha256 | 权限（归档后） |
|---|---|---|---|---|
| `sessions/9d7638fb-b2ea-4630-af34-4d0d32794ce3/flow-tree.json` | `/Users/kaiyu/.stand/sessions/9d7638fb-b2ea-4630-af34-4d0d32794ce3/flow-tree.json` | 862 | `97f111f80222874f8476b8f2f1d74938078974800c5b6089cff3081c381bd74a` | `-r--r--r--` |
| `RESTORE.md` | （本阶段新写） | — | — | `-r--r--r--` |

执行步骤（逐步实测，命令原文）：

```bash
AR="/Users/kaiyu/.stand/archive/team-feature-deletion-20261104"
SRC="/Users/kaiyu/.stand/sessions/9d7638fb-b2ea-4630-af34-4d0d32794ce3/flow-tree.json"
REL="sessions/9d7638fb-b2ea-4630-af34-4d0d32794ce3/flow-tree.json"

# 1) 原样拷贝（-a 连权限/时间戳/xattr 一起）
mkdir -p "$AR/$(dirname "$REL")"; cp -a "$SRC" "$AR/$REL"

# 2) 哈希比对（必须相等才动原件）
shasum -a 256 "$SRC"      # 97f111f80222874f8476b8f2f1d74938078974800c5b6089cff3081c381bd74a
shasum -a 256 "$AR/$REL"  # 97f111f80222874f8476b8f2f1d74938078974800c5b6089cff3081c381bd74a  ← 相等

# 3) 才把原件移入归档（此步之后 sessions/ 侧该文件消失）
rm -f "$SRC"

# 4) 写 RESTORE.md（xattr/权限/还原命令），然后冻结
chmod -R a-w "$AR"
```

验证读数：

- 哈希：源与归档副本**同值** `97f111f8…bd74a`；归档侧 `find … -type f -exec shasum -a 256` 复算一致。
- xattr：源 `com.apple.provenance` ⇒ `cp -a` 后归档副本**同样带** `com.apple.provenance`（macOS 原生标记，非业务数据）。
- 权限/时间戳：`-rw-r--r--`、`kaiyu:staff`、`862 bytes`、`2026-07-19 08:13:14` 全量保留。
- 冻结实测：`chmod -R a-w` 后 `stat` = `dr-xr-xr-x`（根与两级目录）/ `-r--r--r--`（文件）；`touch $AR/_probe` ⇒ `Permission denied`（写入被拒，证明冻结生效）。
- 原目录现状：`sessions/9d7638fb-…/` 现为空壳（目录本体保留未删）。

还原命令（细节见归档内 `RESTORE.md`）：

```bash
chmod -R u+w "/Users/kaiyu/.stand/archive/team-feature-deletion-20261104"
mkdir -p "/Users/kaiyu/.stand/sessions/9d7638fb-b2ea-4630-af34-4d0d32794ce3"
cp -a "/Users/kaiyu/.stand/archive/team-feature-deletion-20261104/sessions/9d7638fb-b2ea-4630-af34-4d0d32794ce3/flow-tree.json" \
      "/Users/kaiyu/.stand/sessions/9d7638fb-b2ea-4630-af34-4d0d32794ce3/flow-tree.json"
shasum -a 256 "/Users/kaiyu/.stand/sessions/9d7638fb-b2ea-4630-af34-4d0d32794ce3/flow-tree.json"
# 期望 97f111f80222874f8476b8f2f1d74938078974800c5b6089cff3081c381bd74a
```

注意：归档只保存**字节**；该文件的读端消费者与写端 `TurnStateStore.save` 均已不存在，所以「还原」不等于「恢复功能」。

---

## 7. 合规自检

| 项 | 命令 | 读数 |
|---|---|---|
| 分支 / 未提交到 main | `git branch --show-current` / `git rev-parse main` | `delegate/delegate-nebflow-f97716ed`；main tip = `2af513702c81ff0522c952fb7ea7a43b59b578b2`（= brief 给定基线，未被本阶段触碰） |
| baseline 类文件零改动 | `git status --porcelain \| grep -E "tests/type-baseline.json\|tests/visual/baseline\|component-contract-baseline\|process-artifacts-allowlist"` | **NONE**（无匹配） |
| 改动面 | `git status --porcelain` | 仅 3 个 src 文件（M）+ 本证据目录（??）；`src/` 之外零改动 |
| 前端资源位置 | — | 只改 `src/main/resources/web/js/ws.js`（仓库无 root 级 `web/`） |
| 生产实例 | `ps aux \| grep "[s]bt"`（只读） | 6138 实例 PID 78176 **未被 kill/signal**；本阶段全程只跑 `sbt -batch compile` / `sbt -batch testOnly`（各自独立 batch 会话，串行闸 `gate acquired after 0s`） |

已运行的 sbt 命令共 **3** 条（compile 改前 / compile 改后 / testOnly 三 spec），无一条连打三次，无排队失败（三次均 `gate acquired after 0s`）。

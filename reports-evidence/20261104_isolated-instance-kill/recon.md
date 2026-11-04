# 隔离 canary 误杀生产实例 — 解析链取证（只读）

事故：2026-11-03 22:59:30，`STAND_HOME=/tmp/seal-canary-home` 的隔离 canary 批执行 stop 动词，
把生产实例 pid 44339（6138）优雅停机。以下六块为静态解析链，每条一行、path:line 可复核。

## 1. shell 停机命令链（真凶）

结论：端口解析只认 `--port` / `STAND_GATEWAY_PORT` / 品牌缺省 6138，**完全不看 STAND_HOME**；身份门只判「是不是本品牌的 jar」，因此生产的 stand 实例完美通过 ⇒ 隔离 home 的 stop 打到 6138 生产进程。

- `stand stop` → `stand_cli_main` 的 `stop)` 分支直接调 `cli_stop_impl "$@"`（`--tree` 才绕行）— ops/bin/stand-cli-lib.sh:644↔652
- 端口来源三档 `--port > ${BRAND}_GATEWAY_PORT > 品牌缺省(stand=6138)`，无 home 参与 — ops/bin/stand-cli-lib.sh:70↔78（缺省值 :73）
- 目标集合 = 端口 LISTEN pids ∪ `$STAND_HOME/.pid`（隔离只隔离了第二腿）— ops/bin/stand-cli-lib.sh:198↔208（lsof :80↔82，pid 文件 :202↔208）
- 身份门 `stand_is_ours`：stand 品牌只匹配 `*stand-assembly*|*sbt*`，通过即放行，无「被停 pid 的 home == 调用 home」断言 — ops/bin/stand-cli-lib.sh:95↔103
- 杀点：先 `kill`（TERM），10s 宽限后 `kill -9` 残余 — ops/bin/stand-cli-lib.sh:234 / :247
- 一句话判决：**home 只决定记账文件（.pid），不决定打谁；`STAND_HOME=/tmp/x stand stop` 的杀伤半径 = 6138 端口上的任何 stand/sbt 进程 = 生产实例。**

## 2. env 继承腿

结论：入口层会把解析出的端口**写进**子进程 env（两个品牌名同值），故 canary 的子批 Bash 天然继承 6138 这个数；即便 env 缺失，品牌缺省仍是 6138，隔离 home 从未进入端口决定式。

- `cli_pin_child_env` 在 start 放行路径被调，导 `STAND_GATEWAY_PORT` + `NEBFLOW_GATEWAY_PORT` 同值、并 unset 对家 home — ops/bin/stand-cli-lib.sh:527↔550（start 调用点 :617）
- 引擎进程 env 里的 `NEBFLOW_GATEWAY_PORT=6138` 被派生子批 Bash 继承；stand 品牌读 `STAND_GATEWAY_PORT`，缺则回落缺省 6138 — 同址 :73↔74 / :542↔548
- 因此 canary 得到「6138」的路径有两条（继承值 / 缺省值），彼此不互斥；两条都不经 STAND_HOME。

## 3. Scala 停机面

结论：Scala 侧**同样没有**「目标 pid 的 home == 调用 home」断言；唯一的家隔离只在 `.pid` 记账路径（`PathUtil.dataRoot`），端口侧一律回落品牌缺省 / 端口实况。

- `pidFile = PathUtil.dataRoot / ".pid"`：记账腿天然随 STAND_HOME 隔离 — src/main/scala/nebflow/cli/ProcessManager.scala:10
- 全文无 home 比较断言（`home` 仅出现在 :10 与注释 :42）⇒ 「被停进程是不是我这个家的」无人问 — 同文件 :10 / :42
- degraded 链端口回落 `GatewayClient.readPort` → `Defaults.brandDefaultPort`（=6138），隔离 home 不参与 — src/main/scala/nebflow/cli/ProcessManager.scala:287↔292
- 权限门只判 startedBy（launcher/sbt/restart-script）与受保护端口，**不判家** — 同文件 :294↔326（:317↔326）
- `restart --detached` 的端口 = 活 pid **实听端口**，pid 不可读才回落 `cfg.port`（配置端口，仍非 home 派生）— src/main/scala/nebflow/cli/RestartCommand.scala:110↔119（:117↔118）
- DetachedApply 渲染脚本：`port_pids(){ lsof -ti tcp:$PORT }` 纯按口杀；`export STAND_HOME=<plan.dataRoot>` 只影响记账与拉起，不影响「杀谁」— src/main/scala/nebflow/cli/DetachedApply.scala:428↔430 / :443↔444 / :463↔473

## 4. 引擎启动守卫（起不来 vs 借道）

结论：唯一跨家判据是 `otherInstanceRoot`，它只认 `.stand` / `.nebflow` 两个字面品牌根 —— 对 `/tmp/seal-canary-home` 这类隔离根完全盲；所以「隔离实例起在生产口」会走 `classify` 的 stale 分支直接 `destroyForcibly` 生产；而「生产起在隔离口」时隔离实例不被认出，反向亦然。

- `ensureSingleInstance`：其他实例判据先跑，未命中即进 `classify`；命中 stale ⇒ `destroyForcibly`（零 home 复核）— src/main/scala/nebflow/gateway/GatewayMain.scala:54↔107（:80↔95）
- 跨家判据只认 `.stand`/`.nebflow` 字面根：`cmd.contains(s"/$root/") && !own.contains(...)` — src/main/scala/nebflow/gateway/StaleProcessGuard.scala:74↔81
- stale 判据只看命令行标记（`nebflow.Main`/`GatewayMain`/`*-assembly`/`nebflow.jar` …），**不含 home** — 同文件 :44↔56 / :166↔181
- 推演 A（隔离实例起在 6138）：生产 cmdline 带 `/Users/kaiyu/.stand/.../stand-assembly-*.jar`，本实例 dataRoot 是 `/tmp/...` ⇒ `!own.contains("/.stand")` 为真 ⇒ 判为「异实例」→ 拒启（零信号，不会杀）；但若生产跑的是 `sbt`/不带品牌根路径的裸 jar 形式，`otherInstanceRoot` 返回 None ⇒ 落 `classify` ⇒ **destroyForcibly 生产** — 同址 :58↔68 + :84↔95
- 推演 B（生产起在隔离口）：隔离实例 cmdline 在 `/tmp/seal-canary-home` 下，`otherInstanceRoot` 看不见 ⇒ 生产侧 `classify` 判其为自家 stale ⇒ **杀掉隔离实例**（反向误杀同样成立，只是受害者换了）。
- `ensureSingleInstance` 自身无「监听者 home == 本 home」断言（home 仅在 `PathUtil.dataRoot` 记账与错误文本）— GatewayMain.scala:67↔76
- `portGateThen`：端口活着且探到已知品牌 ⇒ `SingleInstanceGuard.NebflowInstance ⇒ focus existing + exit 0`，即**静默借用另一个 home 的实例**，退出码 0 无告警 — src/main/scala/nebflow/Main.scala:266↔290
- `SingleInstanceGuard` 分类面：`PortFree` / `NebflowInstance(url)` / `OtherInstance(brand,url)` / `ForeignOccupant(detail)` — src/main/scala/nebflow/cli/SingleInstanceGuard.scala:34↔53
- 分类依据 = 健康面 `product` 字段（品牌词即身份）；同品牌时再以 `otherRootOccupant` 路径证据兜底升级为 `OtherInstance`，路径证据仍只认品牌根 — 同文件 :113↔133 / :143↔156

## 5. console-takeover

结论：`swept N stale instance(s)` 是**死实例记账清理**（零活连接且超 TTL 才摘账，活连接永不清），不是活实例顶替；与本次误杀无关，属旁观项。
- 判据 `i.conns.isEmpty && (now - i.lastSeen) > staleTtlMs`，只摘账不发信号 — src/main/scala/nebflow/gateway/ConsoleTakeover.scala:415↔429（日志 :426）
- 活实例顶替走的是另一条 `claimAndReverseKick`（翻位+反向踢帧），与该日志无涉 — 同文件 :437↔445

## 6. 同族入口清单（回执④用）

| 入口 | 判据 | home 隔离 | 风险 |
|---|---|---|---|
| `stand start`（sbt run 同 home 同口） | 端口 LISTEN + `stand_is_ours`（仅 jar/sbt 标记）— ops/bin/stand-cli-lib.sh:614↔641 | 无（端口腿不看 home） | 高 |
| `stand stop` / `stand restart`（cli_stop_impl 复用） | 同 §1：端口 ∪ `$HOME/.pid`，身份门只判本品牌 — :189↔257 | 仅记账腿隔离 | **高（本次事故）** |
| `stand restart --detached`（`cli_restart_arm`） | 双 fork 孤儿化 + `.restart-detached.pid` 守卫；停旧用同 `cli_stop_impl` 语义 — :284↔311 | 无（孤儿化只为脱离进程树） | 高 |
| DetachedApply OS 一次性作业 | 渲染脚本 `port_pids` 按口杀 + `LAUNCHER stop`；`export STAND_HOME=plan.dataRoot` — DetachedApply.scala:428↔444 / :463↔473 | 部分（home 传给拉起，杀仍按口） | 中 |
| `stand update --from <jar>` | 引擎在跑则注册 `Kind.Update` 同款 apply plan（端口取 `GatewayConfig`）— SystemCommands.scala:142↔164 | 部分（同上） | 中 |
| 热重启 SuccessorGate | `validate` 要求 `ctx.home == data root` **且** port/host 一致 + 60s 新鲜度 ⇒ 唯一真正按 home 拒绝的闸 — SuccessorGate.scala:179↔194 | 有（全链唯一） | 低 |
| 引擎 `ensureSingleInstance` 起机闸 | 跨家只认 `.stand`/`.nebflow`，其余按 stale 强杀 — GatewayMain.scala:54↔107 | 部分（字面根白名单） | 高 |

## 一句话总判决

隔离**只管记账（`.pid` / 日志 / 状态相），不管杀伤半径**：所有生命周期动词的目标集合都由「端口 ∪ 本品牌 jar 标记」决定，home 从未进入判据；`/tmp/*` 隔离根连跨家白名单都不在，于是既可被误杀、也可误杀。


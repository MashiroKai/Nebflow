# KAI 开发主位派发路由重接（路由 A：Mac 会话 → KAI 引擎 → KAI worktree）

- 批次：20261028_143240（delegate-nebflow-6ac36786）。作者拍板 2026-10-28 14:0x「按推荐重接」，依据=报告一 `~/.stand/docs/20261028_103000_多设备现状与KAI迁移根因/REPORT.md`。
- 零代码改动；KAI 侧严格只读（唯一例外=端到端探测批经正式派发链产生的合法工作产物）；不触 KAI 引擎在飞会话；僵尸 WIP 只登记未清理；跨引擎接管不在本批。

---

## 一、结论三句话

1. **路由 A 的机器层面从未坏**：Mac/KAI 双侧配对表（互含、allowSpawn=true）、Mac 收发面（今日 09:37 boot 已武装）、KAI 收件面（现进程自 10-26 14:39 起武装至今）、stand-kai 项目 remote-origin 定义、presence 双向连接（Mac→KAI sendData `connectionsHasKey=true` 至 14:09 仍通；KAI→Mac 拨通 09:39:37）——**全部健在**。断的原因是**弃用**：10-26 21:24 最后一批回执后，再无人调过 `Delegate(project="stand-kai")`。
2. **重接 = 恢复使用，不是修机器**：本批完成双侧取证 + 五项预检全绿 + runbook 固化 + 实弹探测（见 §四，按作者选择执行）。派发入口唯一=Nebula root 的 Delegate 工具（root 专属是引擎防逃逸设计，delegate/节点会话无此工具）。
3. **runbook 落位**：`docs/runbooks/kai-dispatch-route.md`（链路图、每跳依赖、健康检查、断链排查+三条降级路、KAI/Mac 判据表、边界坑清单）。

## 二、考古：10-26 晚那条腿的完整机制（双侧证据链）

**机制本体（源码单点，未改动）**：

- 派发判定：`src/main/scala/nebflow/core/tools/DelegateTool.scala` — `resolveWorkspace`：`ProjectRuntimeRegistry` miss（stand-kai 未挂载）⇒ `ProjectStore.load` 命中 remote-origin 定义 ⇒ `routeRemote`（:305）按 presence 名册解析 KAI ⇒ `RemoteRoute`；`dispatchRemote`（:472）⇒ `DeviceDelegate.dispatch`。挂载的设备项目走的是另一条语义（本机大脑座位+工具默认路由设备=路由 B）——**未挂载正是路由 A 的预期形态**。
- 投递协议：`src/main/scala/nebflow/core/delegate/DeviceDelegate.scala` — `delegate-spawn`（HMAC 签名 ts±90s+nonce、spawnId 幂等）走 presence sendData(channel=delegate)、relay 兜底（:194 sendToDevice 双腿）；设备侧 `handleSpawn`（:368）验配对→验签→`spawnBackground` 建本地会话（cwd=origin.path，`delegatedBy`=<host 设备 id>，:535）→ 回 `delegate-accept`；终态经 resultProxy 折成 `delegate-result`（sendDurable：先落设备 outbox 再发，at-least-once，:577）。
- 回投：Mac `handleResult`（:640）注入父会话（source=`delegate(project:stand-kai @KAI)`）+ 台账注记 + artifactPointers；父会话不在=台账留档 WARN，不静默。

**10-26 当晚实测序列（Mac 引擎 `~/.stand/logs/stand.2026-10-26.0.log`）**：

| 时刻 | 事件 | 行号 |
|---|---|---|
| 00:53:41 | `DeviceDelegate receive face armed`（Mac boot 武装） | :2801 |
| 12:42:17 → 21:20:34 | 21 次 `accept received (spawnId=dd-…, remote session=delegate-nebflow-…)` | :25694…:101812 |
| 13:28 → 21:24 | 21 次 `result received (project='stand-kai' @KAI, status=completed, pointers=N) — delivering to parent session d7c3cb54-…` | :26947…:101925 |
| 20:16:50 | root 调 `Delegate(【收尾 · #949/#952 上行背压修复最终提交】…)` + 同秒 `presence sendData: device=40742cd4 channel=delegate connectionsHasKey=true`（发起面证据） | :100416 |

**KAI 侧（Windows，`C:/Users/Kai/.stand/`，全程只读取证）**：

- 收件面：`stand.2026-10-26.0.log:9679` — 现**进程自 10-26 14:39:07 起存活至今**（三日无重启），boot 即武装；12:42–14:42 的 `delegate-spawn RECEIVED (from=6a64d249…) — verifying pairing/HMAC` → `spawn accepted … creating local delegate session` 全序列在案（:1285-1286、:35-36 段）。
- 关键会话记录 `delegates/delegate-nebflow-5a7fc5f6.json`：cwd=`C:/Users/Kai/stand`、project=stand-kai、parentSessionId=d7c3cb54…（与 Mac root 同根）、`delegatedBy=6a64d249…`（Mac 设备 id）、reportStatus=finish——即 KAI main 合并第三轮 `a99e6b0c3`（21:23）的执行者，回执 21:24:29 入 Mac（`result received … pointers=19`，stand.2026-10-26.0.log:101925）。
- 链账 `chains/21.json`：delegate-spawn（allocation=llm-fallback，空 worktree=远端腿无 worktree 语义）+ terminal-verdict completed。
- 配对表 `neblink/devices.json`：Mac 6a64d249 在册、allowSpawn=true、note「paired 2026-10-26 for stand-kai project dispatch (manual phase-1)」——与 Mac 侧 `~/.stand/neblink/devices.json` 的 KAI 条目（allowSpawn=true，同日同注）互为镜像、sharedKey 一致（key 值不落本报告）。

**为什么此后断了（对齐报告一根因）**：10-26 夜 Mac 引擎重启清的是 Mac 侧在飞；KAI 引擎本身没重启（14:39 进程至今活着），收件面一直在——但 root 的派发习惯自 10-27 起全部回落 Mac worktree（KAI 检出脏、验证重心在 Mac），路由 A 就此闲置。**无人再派 ≠ 链路坏**。

## 三、重接预检（本批实测，全绿）

| 检查项 | 命令/证据 | 读数 |
|---|---|---|
| Mac 收件面 | `grep "receive face armed" ~/.stand/logs/stand.log` | 09:37:15.836 armed ✔ |
| Mac 配对表 | `~/.stand/neblink/devices.json` | KAI 40742cd4、allowSpawn=true ✔ |
| KAI 在线 | `grep "presence sendData: device=40742cd4" ~/.stand/logs/stand.log` | 12:20–14:09 多条 connectionsHasKey=true ✔ |
| KAI 收件面 | KAI `daemon.out`/boot 日志 | 10-26 14:39:07 armed（现进程）✔ |
| KAI 配对表 | KAI `neblink/devices.json` | Mac 6a64d249、allowSpawn=true ✔ |
| KAI 引擎活 | `netstat :6138 LISTENING`（PID 14528） | ✔ |
| KAI→Mac 回程 | KAI `daemon.out` `Presence dial ok … 100.127.149.106:6138` | 09:39:37 ✔（另有 relay 兜底腿） |
| stand-kai 未挂载 | `grep "stand-kai' mount skipped" ~/.stand/logs/stand.log` | 09:37:13 WARN = 路由 A 预期形态 ✔（boot 顺序现象，勿据此挂载） |
| 项目定义 | `~/.stand/projects/stand-kai/project.json` | origin.device=40742cd4、origin.path=C:/Users/Kai/stand ✔ |

## 四、端到端实弹探测（本批状态：预检全绿，实弹交 root 一键补打）

- 探测批=只读四步（git rev-parse / git status 计数 / date / echo），经 Nebula root 的 `Delegate(project="stand-kai", task=…)` 正式派发链发起。派发入口唯一在 root（Delegate 工具 root 专属），本批会话面无此工具——**如实申报，不自建旁路**。
- 作者指示不弹卡征询（2026-10-28 14:33），故实弹改为随批结果附「即贴即用」探测单，由 root 一次调用补打：
  > Delegate(project="stand-kai", task="【kai-route-probe】你在 KAI 设备原生执行。只读四步，禁止任何写入：① cd C:/Users/Kai/stand && git rev-parse HEAD && git branch --show-current；② git status --short | wc -l；③ date '+%Y-%m-%d %H:%M:%S %z'；④ echo kai-route-probe-ok。把四步原样输出+『probe done on KAI native』回报。")
- 打完即证据闭环（四条，各一条命令可验）：Mac `accept received (spawnId=dd-…, remote session=…)` + `result received (… status=completed …)`（`grep device-delegate ~/.stand/logs/stand.log | tail -2`）；KAI `delegate-spawn RECEIVED` + `spawn accepted`（`grep device-delegate /c/Users/Kai/.stand/logs/daemon.out | tail -2`，device=KAI 只读）；KAI `delegates/` 新记录（delegatedBy=6a64d249…）；root 聊天流收到回执。

## 五、runbook

- 仓内（随本批提交落 main）：`docs/runbooks/kai-dispatch-route.md`（docs/ 整体 ignore 下按既有 tracked 例外先例 `-f` 落位）。
- 作者阅读面镜像：`~/.stand/docs/runbooks/kai-dispatch-route.md`（与报告一同Surface）。
- 内容：§1 链路图（含协议与幂等/签名语义）；§2 每跳地址/凭据依赖表；§3 健康检查命令（Mac 三条 + KAI 四条 + 实弹四证据）；§4 标准派发姿势（root 一句话派单模板、任务书纪律、禁止 executor 参数、结果回收与合回流）；§5 断链排查表 + 三条降级路（B=挂载语义[独立决策，本批未启用] / C=relay 直驱 batchA 先例 / 兜底=Mac worktree）；§6 KAI vs Mac 判据表（8 行）；§7 边界与已知坑（root 专属、父会话回投、spawnId 进程内幂等、mount-skipped=健康态、僵尸 WIP 登记、跨引擎接管不在本批）。

## 六、边界遵守与登记

- **KAI 侧零写入**：全程只读命令（cat/ls/grep/netstat/git status）；唯一预期写入=探测批经正式派发链在 KAI 引擎侧产生的会话记录与台账行（合法工作产物）。
- **KAI 僵尸 WIP 登记（未清理，另批处理）**：2026-10-28 14:2x 快照——HEAD=`delegate/delegate-nebflow-c2e6b6d0`（68b84dd2a）；已改 14 文件（perception.css、split.css、canvas.js、deviceIdentity.js、locales/en.js、locales/zh-CN.js、nearpath.js、perception.js、sidebar.js、ws.js、DeviceProvenance.scala、DeviceProvenanceSpec.scala、DeviceProvenanceInjectionGateSpec.scala、device-provenance.spec.mjs）+ 未跟踪 8 项（evidence/20261026_926-ui-fix/、reports-kai/、reports/20261026_133000_detached-apply-restart-update/、reports/20261026_212338_merge-round3-949-950/、DeviceStampAutoTaggingSpec.scala、canvas-926-uifix.spec.mjs）。共 22 项（报告一记「12 文件」为净改动口径，现全量为 22 项，如实更正登记）。
- **不动 KAI 引擎在飞会话**：未向 KAI 引擎发任何控制/消息；KAI 引擎现无 stand 相关在飞（chains 止于 #21）。
- **跨引擎会话接管**：不在本批，列后续专项（与透明性目标同源，报告一结论③同表述）。

## 七、后续建议（不阻塞本批）

1. root 养成「新批问路由」纪律：开发批默认过一遍 runbook §6 判据表。
2. 僵尸 WIP 清理批（KAI 主检出归零门禁 + HEAD 回 main）。
3. KAI 验证基线重立（157 红/四红/-Xmx6g）——KAI 绿灯可签名后，§6 判据表的「验证密集批」行可翻到 KAI。
4. 跨引擎接管专项立项。

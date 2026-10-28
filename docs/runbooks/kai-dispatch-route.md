# Runbook：KAI 派发路由（Mac → KAI 引擎 device-delegate 腿）

- 批次：20261028 派发路由重接批（作者拍板 2026-10-28 14:0x「按推荐重接」；依据报告一 `~/.stand/docs/20261028_103000_多设备现状与KAI迁移根因/REPORT.md` 结论①）。
- 用途：主会话（Nebula root）今后把开发批派到 KAI 设备原生执行时，按本手册走、按本手册体检。
- 零代码承诺：本路由是引擎既有设施（v2.1 §A.3「项目分布执行」），本批未改任何引擎代码；本手册只固化使用与体检姿势。

---

## 1. 链路图

```
Mac 引擎 (device 6a64d249, :6138)                     KAI 引擎 (device 40742cd4, Windows, :6138)
════════════════════════════════                    ══════════════════════════════════════════
Nebula root 会话
  │  Delegate(project="stand-kai", task=…)   ← 唯一派发入口（Delegate 工具 root 专属）
  ▼
DelegateTool.resolveWorkspace
  ├─ ProjectRuntimeRegistry miss（stand-kai 未挂载 = 路由 A 的预期健康态）
  ├─ ProjectStore.load 命中 remote-origin 定义（origin.device=40742cd4）
  └─ routeRemote：presence 名册查 KAI 在线（离线 ⇒ DELEGATE_DEVICE_OFFLINE，绝不静默落本机）
  ▼
DeviceDelegate.dispatch
  ├─ spawnId = dd-xxxxxxxx（幂等键）
  ├─ HMAC 签名（devices.json sharedKey；ts ±90s + nonce 防重放）
  ├─ 主腿：presence sendData(channel=delegate)
  └─ 兜底：relayNotify（relay portal，同一 KAI 收件入口）
  │                                    ──────────────▶  DeviceDelegate.handleSpawn
  │                                                        ├─ 验配对（devices.json 双侧互含、allowSpawn=true）
  │                                                        ├─ 验签 + spawnId 去重
  │                                                        └─ spawnBackground → KAI 本地 delegate 会话
  │                                                            cwd=C:/Users/Kai/stand，delegatedBy=<Mac设备id>
  │ ◀──────────────  delegate-accept（对端 sessionId 回执） ──┘
  │                                                    （会话在 KAI 引擎原生执行，用自己的执行器集）
  │ ◀──────  delegate-result（status + result 文本 + artifactPointers）
  │           KAI 侧 sendDurable：先落设备 outbox 再发（at-least-once，宁重勿丢）
  ▼
handleResult（Mac 侧）
  ├─ 回投父会话（发起派发的那个 root 聊天流，source=delegate(project:stand-kai @KAI)）
  ├─ 台账注记 + artifactPointers 登记（KAI 盘路径，字节不回传）
  └─ 父会话已不在 ⇒ 台账留档 + WARN（不静默丢）；按 spawnId 判重不重复注入
```

10-26 晚真跑实证：12:42–21:24 共 21 个 KAI 侧 delegate 会话（`C:/Users/Kai/.stand/delegates/`），最后一单 5a7fc5f6（合并第三轮，产出 KAI main `a99e6b0c3`，21:24 回执 completed）。

## 2. 每跳的地址 / 凭据依赖

| # | 跳 | 依赖 | 位置 | 健康态 |
|---|---|---|---|---|
| 1 | 派发入口 | **Nebula root 会话**（Delegate 工具 root 专属；delegate/节点/内核会话调不到是引擎防逃逸设计，不是故障） | root 聊天 | root 活着 |
| 2 | 项目解析 | `stand-kai` 项目定义：`origin.device=40742cd4-…`、`origin.path=C:/Users/Kai/stand` | Mac `~/.stand/projects/stand-kai/project.json` | 文件在、**未挂载** |
| 3 | 名册在线 | presence 名册含 KAI（Mac→KAI 连接存活） | Mac `~/.stand/logs/stand.log` 的 `presence sendData: device=40742cd4… connectionsHasKey=true` | 近 1h 内有命中 |
| 4 | 配对密钥 | 双侧 `neblink/devices.json` 互含对方、`allowSpawn=true`、同一 sharedKey | Mac `~/.stand/neblink/devices.json`；KAI `C:/Users/Kai/.stand/neblink/devices.json` | 各含一条对方记录（**sharedKey 值不得抄进任何文档/聊天**） |
| 5 | KAI 收件面 | KAI 引擎 boot 时武装（neblinkService 就绪才会打这行） | KAI 日志 `DeviceDelegate receive face armed (channel: delegate-spawn/result/accept)` | 当前进程 boot 日志里有 |
| 6 | KAI 执行位 | `C:/Users/Kai/stand` 主检出 | KAI 盘 | ⚠ 现存 22 项僵尸 WIP（2026-10-28 登记，见 §7） |
| 7 | 回程 | KAI→Mac 的 presence（KAI 侧拨号成功）或 relay 兜底 | KAI `daemon.out` 的 `Presence dial ok … via http://100.x.x.x:6138` | 拨通或有 relay |

## 3. 健康检查命令

Mac 侧（本机，只读）：

```bash
# ① 收件面武装（本次 boot）
grep "receive face armed" ~/.stand/logs/stand.log | tail -1
# ② stand-kai 未挂载 = 路由 A 形态（这行 WARN 命中才是健康）
grep "stand-kai' mount skipped" ~/.stand/logs/stand.log | tail -1
# ③ 配对表有 KAI 且 allowSpawn=true（不要打印 sharedKey）
python3 - <<'EOF'
import json,os
e=[x for x in json.load(open(os.path.expanduser('~/.stand/neblink/devices.json'))) if x['deviceId'].startswith('40742cd4')]
print('KAI paired, allowSpawn=', e[0]['allowSpawn'] if e else 'MISSING')
EOF
# ④ KAI 在线（近 1h 有 connectionsHasKey=true 的 sendData）
grep "presence sendData: device=40742cd4" ~/.stand/logs/stand.log | tail -3
```

KAI 侧（`Bash(device="KAI")`，**严格只读**）：

```bash
# ⑤ 引擎活着（6138 LISTENING）
netstat -ano | grep ":6138" | grep LISTEN
# ⑥ 收件面武装（armed 行打在 boot 当天的日志；stand.log 按日滚动）
grep "receive face armed" /c/Users/Kai/.stand/logs/daemon.out | tail -1
# ⑦ 配对表有 Mac
grep -c "6a64d249" /c/Users/Kai/.stand/neblink/devices.json
# ⑧ Mac 可达（拨通记录）
grep "Presence dial ok" /c/Users/Kai/.stand/logs/daemon.out | tail -2
```

端到端实弹探测（**唯一权威验证**，需 root，见 §4）：

四条证据齐 = 全链通：
- Mac：`accept received (spawnId=dd-…, remote session=delegate-nebflow-…)` + `result received (… status=completed …)`
- KAI：`delegate-spawn RECEIVED … verifying pairing/HMAC` + `spawn accepted … creating local delegate session`
- KAI：`~/.stand/delegates/` 新增记录（`delegatedBy`=6a64d249…）
- root 聊天流收到回执（`delegate(project:stand-kai @KAI)`）

## 4. 标准派发姿势（root 会话）

1. root 聊天里一句话派单，例（2026-10-28 重接探测批原文，可复用为探针）：

   > 向 stand-kai 派一个只读路由探测批（走 device-delegate 腿，**不要带 executor 参数**）：
   > `Delegate(project="stand-kai", task="【kai-route-probe】你在 KAI 设备原生执行。只读四步，禁止任何写入：① cd C:/Users/Kai/stand && git rev-parse HEAD && git branch --show-current；② git status --short | wc -l；③ date '+%Y-%m-%d %H:%M:%S %z'；④ echo kai-route-probe-ok。把四步原样输出+『probe done on KAI native』回报。")`

   派出后让 root 把回执要点 Mail 给发起核验的 delegate 会话（如 `delegate:delegate-nebflow-6ac36786`），证据闭环。
2. 任务书纪律：绝对路径（`C:/Users/Kai/stand/...`）、自足、写明「在 KAI 设备原生执行」；产物落 KAI 盘 `reports/`。
3. **不要带 `executor` 参数**——显式执行器与远端路由冲突 = fail-closed（`DELEGATE_EXECUTOR_REMOTE_CONFLICT`）；对端用自己的执行器集原生执行。
4. 结果回收：回执进 root 聊天 + Mac 台账注记 + artifactPointers（KAI 盘路径清单）。合回 Mac main 走既有 bundle 同步链（`ops/README.md` 三步流）或 KAI 侧 merge + `ops/bin/stand-jar-pull`。
5. 注意：KAI 腿会话**不进 Mac 的 worktree/合并窗体系**（远端腿 judgeWorkspace=false，无合并窗语义）。分支纪律在任务书里自行约定（10-26 先例：KAI 侧临时 linked worktree 合并，完事即删）。

## 5. 断链排查（按跳）+ 三条降级路

| 症状（报错原文关键词） | 断点 | 处置 |
|---|---|---|
| `DELEGATE_PROJECT_UNMOUNTED` | 跳2 项目定义丢 | 查 `~/.stand/projects/stand-kai/project.json`；**修复=恢复定义文件，不是去挂载** |
| `DELEGATE_DEVICE_OFFLINE`（在线设备列表无 KAI） | 跳3 KAI 引擎没开/presence 断 | KAI 机起引擎；等 Mac 侧 sendData `connectionsHasKey=true` 再派 |
| `device dispatch failed — not PAIRED` | 跳4 配对丢 | 双侧手工重配对（phase-1 手法，同 key 双写 devices.json） |
| KAI 侧 `spawn REJECTED (stale ts / signature mismatch / allowSpawn=false)` | 跳4/5 | 双机校时（ts 窗 ±90s）；核对配对表未被改 |
| accept 到了但迟迟无 result | 执行中 | 看 KAI 侧该会话日志行（`subagent-nebflow [device:…]`）；非链路问题 |

降级路（路由 A 不可用时，按序）：

- **路由 B（挂载语义，作者原设计）**：把 stand-kai 挂到 Mac 引擎 = 本机大脑座位 + 六件工具默认路由 KAI 设备。现状：boot 期挂载预检被 skip（预检先于 neblink 就绪约 2s，boot 顺序现象，报文见 stand.log `stand-kai' mount skipped`）。**重挂载是独立决策**：语义会从「整会话派设备」换成「Mac 宿主大脑」，且挂载预检会对 KAI 盘做骨架落备（写操作）——本批未启用、未测。
- **路由 C（relay 直驱，batchA 先例）**：Mac 任意会话 `Bash(device="KAI")` 手工造 worktree（命名 `stand-<批名>`）直驱。零引擎簿记、随 Mac 引擎生死、摩擦大——仅当路由 A 不可用且活很小。
- **兜底**：Mac worktree 本地批（现状默认）；KAI 不可用时构建腿走 `ops/README.md` 的「Mac 紧急兜底出包」。

## 6. 判据表：什么活路由去 KAI、什么活留 Mac

| 活的形态 | 去向 | 理由 |
|---|---|---|
| 引擎出包（`stand start` 的 jar） | KAI 构建链（既有三步流，非本路由） | 构建位已定版 KAI（phase2 写死） |
| Windows 差异复现/测试（字符集、path 分隔、写后读链） | **KAI 路由 A** | 只有 KAI 有真实 Windows 语义 |
| 作者指名「开发位=KAI」的代码批 | **KAI 路由 A** | 总纲定版（10-26 作者令） |
| KAI 原生盘依赖（node_modules、playwright chromium-1243/1248 已装） | KAI 路由 A | 环境在 KAI 侧已备 |
| 验证密集批（test6 电池 / sbt 全量编译） | 暂留 Mac | KAI 基线 157 红未清、PerceptionCoreSpec 四红根因未挖、sbt 冷树 OOM（需 -Xmx6g）未固化——KAI 绿灯尚不可签名 |
| 依赖合并窗 / worktree-per-batch 纪律的批 | 留 Mac | 远端腿无合并窗语义 |
| 在飞可恢复性重要的长批 | 留 Mac | 跨引擎接管未立项：Mac 引擎重启后 KAI 在飞结果只能落台账留档，不能续聊接管（后续专项） |
| 任何要写 `C:/Users/Kai/stand` 主检出的活 | 先清僵尸（另批）再派 | 主检出 22 项 WIP + HEAD 停在旧 delegate 分支（2026-10-28 登记在案），直写会叠脏 |

## 7. 边界与已知坑（如实声明）

- **Delegate 工具 root 专属**：其他会话（delegate/节点/内核）调不到是引擎防逃逸双保险，不是故障；探测/派单只能经 root。
- **结果回投父会话 = 派发时的那个 root 会话**：root 换代（Mac 引擎重启）后，迟到的 result 落台账注记 + WARN，不丢但不注入；KAI 引擎重启则清 KAI 侧在飞会话（boot 收殓腿会把「这件丢了+部分结果」落设备 outbox 补投一次）。
- **spawnId 幂等是进程内的**：KAI 引擎重启后同一 spawnId 重投会再执行一次（诚实边界，与 NonceCache 同级）。
- **「stand-kai mount skipped」WARN 是健康态**：不要因排障去挂载 stand-kai（会把派发语义翻转成路由 B 且对 KAI 盘做骨架写）。
- **KAI 主检出僵尸 WIP（本批只登记未清理）**：2026-10-28 快照——HEAD=`delegate/delegate-nebflow-c2e6b6d0`（68b84dd2a），14 个已改文件（perception/split css、canvas/deviceIdentity/nearpath/perception/sidebar/ws js、en/zh-CN locales、DeviceProvenance.scala + 双 spec）+ 8 项未跟踪（evidence/20261026_926-ui-fix/、reports-kai/、reports/20261026_133000…、reports/20261026_212338…、DeviceStampAutoTaggingSpec.scala、canvas-926-uifix.spec.mjs）。清理归另批。
- **跨引擎会话接管（ownership 转移）不在本批**：与透明性目标同源，值得单独立项（报告一结论③同款表述）。

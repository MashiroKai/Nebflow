---
type: design
status: implemented
related: "[[perception]]"
---

# 感知进程形态改造：独立 daemon 与心跳进程合并为统一 daemon 管理进程

> 作者裁定 2026-10-29 13:02 逐字：「独立daemon吧 正好和我们心跳进程合并 那就是管daemon的」。
> 红线变更：推翻 2026-10-26 02:00「撤销心跳与感知合并」裁定（7a91a3253 还原件）——
> 本批按新裁定执行；其余红线照旧（零超时设计、在产零触碰、不 push）。
> 基座：`delegate/delegate-nebflow-c58a5773`（c30d88ed7+c3e3d806b，感知缺口批，spec 47/47+16/16）。
> 相关件：[[20261027_cli-isolation-and-detached-restart-spec.zh-CN]]（daemon 子进程/隔离冒烟家族同纪律——本批 dock 命令面与 `scripts/perception-daemon-smoke.sh` 承其惯例）；[[glossary]]（感知/心跳词条——本批同步改「感知」条为统一 daemon 管理进程）。

## 0. 术语对表（仓内实体 ↔ 作者口语）

| 作者口语 | 仓内实体 | 权威落点 |
|---|---|---|
| 心跳进程 | 守护进程管理子系统（DaemonService + `/api/daemons` + `#daemon-btn`「心跳进程」面板，标题 "Heartbeat"） | `src/main/scala/nebflow/core/daemon/DaemonService.scala`；`web/js/daemons.js`；`index.html:269` |
| 管 daemon 的 | 同上——daemon 家族的管理者（起停/watchdog/健康面） | 同上 |
| 感知 | 感知域（IMAP 收信→L0/L1 判定→投递/记账/OTP/草稿） | `src/main/scala/nebflow/perception/` |
| 折中态 | 感知五循环在引擎进程内 boot 链 fiber（`perceptionBoot`，P2-1 记录的「需作者再裁」面） | `GatewayMain.scala` 旧 L974-980 |

## 1. 目标形态（进程拓扑一句话）

**引擎进程（GatewayMain）经既有 DaemonService 声明托管（daemons.json 托管条目）
拉起「统一 daemon 管理进程」（`PerceptionDaemonMain` 独立 JVM：单进程双职责 =
感知五循环 + MailImportanceJudge 判定逻辑 + 自心跳 watchdog/健康面），起停随引擎
走（autoStart 家族 / stopAll / 崩溃自动重启 / pid marker 回收——全部现行心跳语义），
引擎面板 API 经代理对接 daemon，daemon 投递/WS 事件/LLM 判定 transport 经引擎
内部 ingest 端点回注。**

```
┌─ 引擎进程 GatewayMain ──────────────────┐          ┌─ 统一 daemon 管理进程 PerceptionDaemonMain ─┐
│ DaemonService（心跳照旧，零改动）          │  spawn    │ 职责A 感知五循环 poll/sweep/digest/spool/idle │
│  ├ upsert 托管条目（autoStart/restart/   │ ────────→ │ 职责B MailImportanceJudge 判定逻辑 +        │
│  │  port 健康探针/pid marker/stopAll）    │           │        自心跳 watchdog + /health 健康面      │
│  ├ 握手：/health 轮询（10s 有界，WARN-only）│  127.0.0.1 │ PerceptionCore（判定/窗/草稿/账单/spool 状态）│
│  ├ /api/perception/* → 代理（会话门照旧）  │ ←───────→ │  Ember HTTP（token 门）：                    │
│  ├ EmailSend/AskUser 工具腿 → 客户端      │  token 门  │   /health + 面板 11 端点（与代理同形）        │
│  └ 内部 ingest（deliver/broadcast/llm）  │ ←─────────│   deliver/WS 事件/LLM 判定 transport 出域腿  │
└────────────────────────────────────────┘  token 门   └────────────────────────────────────────────┘
```

## 2. 设计裁决（逐条对应任务令）

1. **抽进程**：五循环 + 判定逻辑全部在 daemon 进程；引擎进程零感知循环残留。
   `PerceptionCore` 的最后两处引擎耦合（`SharedResources` 未用参数、
   `injectEnvelope` 内 `MailTool.resolveRoots`）改为显式 seam（`deliverEnvelope`），
   进程可移植；spec 直构面同步（47 测试零语义变化）。
2. **与心跳合并 = 归入 DaemonService 家族托管**（r2 裁决 4「daemon 声明托管 +
   复用 daemon 守护与自启，零新机制」的落地）：统一 daemon 以托管条目
   （id=`perception-daemon`）进 `daemons.json`——autoStart=true（一起起停）、
   restartOnExit=true + 指数退避 + 5 次上限（一起被 watchdog 管）、healthCheckSec
   端口探针（崩溃检测）、pid marker（孤儿回收）、`/api/daemons` 面板行（一起写
   健康面）。**DaemonService 本体零改动**——「心跳照旧」逐字成立。
   单进程双职责：进程内五循环（职责A）+ 自心跳 watchdog（30s 拍活性日志 +
   循环存活面，进 /health）——daemon 家族的心跳语义在本进程内自持。
3. **boot 链**：GatewayMain 旧 `perceptionBoot` 腿删除；dock 腿 =
   写 0600 token 文件 → upsert 托管条目 → 既有 autoStart（2s 延迟腿原样）→
   `/health` 握手轮询（10s 有界，失败只 WARN 绝不阻塞启动——原 perceptionBoot
   失败语义逐字保持）。引擎重启处理 = 现行心跳语义逐字对齐：干净停机 stopAll
   随引擎走；异常死亡 → 下次 boot reclaim 回收重启（DaemonService 既有）。
4. **UI 语义不动**：前端五件（`#daemon-btn`/daemons.js/perception.js/index.html/
   perception.css）零字节改动。面板 API 形状由 daemon 单源自出、引擎代理逐字
   转发（无第二实现可漂移）。`/perception/mail/preset` 保持引擎本地（纯函数，
   daemon 停机时两问配置向导仍可用）。
5. **保留独立可运行性**：`java -cp <jar> nebflow.perception.PerceptionDaemonMain
   --home <root> --port N` 独立成活（隔离实例冒烟不回退）。

## 3. 通道与信任面

- **边界**：全部引擎↔daemon 通道走 `127.0.0.1` + 共享 boot token
  （`<dataRoot>/perception/daemon.token`，0600，每引擎 boot 轮换——daemon 由
  stopAll/reclaim 随引擎走，无旧 token 存活窗）。token 走文件不进
  daemons.json/command/env（授权码不入明文配置的同纪律）。
- **引擎→daemon**：面板 API 代理（无人工超时——daemon 侧操作自持既有界：
  L1 判定 30s 等；连接测试/发送本就长操作）+ 工具腿（openOtpWindow /
  hasAccount / createDraft / attachRequest，5s 有界 + 优雅降级——daemon 停机窗
  内 ask 无窗可开，OTP 信走 L1/白名单/spool 既有路径，watchdog 秒级拉回）。
- **daemon→引擎**（ingest，token 门，非会话门）：
  `POST /api/perception/internal/deliver`（引擎侧 resolveRoots+ImmediateInput，
  不可达应 `delivered:false` ⇒ daemon 本地 spool——「引擎停机不丢信」语义原样）；
  `POST /api/perception/internal/broadcast`（wsHub 出站，3 次退避重试后放弃——
  只影响推送优化，窗内命中状态在 daemon 持有，hasHit 补渲染腿兜底）；
  `POST /api/perception/internal/llm`（judge transport：引擎现有 LLM 栈代答，
  判定解析逻辑留在 daemon；40s 有界 > 判定 30s 界）。
- **日志单写者**：daemon 进程以编程式 logback 重配置落
  `<dataRoot>/logs/perception-daemon/logs/stand.log`（引擎 stand.log 单写者
  不变量不破）+ console 面（DaemonService 环形缓冲 = 面板 recentOutput 健康面）。

## 4. 开放问题（不擅自改，列报作者）

- 面板行对 perception-daemon 的 stop 语义 = 任意 daemon 同款（面板停 → 引擎
  重启后 autoStart 拉回）；若作者要「停用后不再自启」的持久开关，另批。
- 若「合并」实指 DaemonService 本体搬出引擎进新进程（/api/daemons 改代理），
  与「心跳照旧」冲突且回归面大，本批未采；如需，另立批。
- OTP 开窗时 daemon 恰处停机窗（秒级）：该 ask 无窗，验证码走判定/spool 兜底。

## 5. 验证口径

- `PerceptionCoreSpec` 47/47（基线零回退，仅构造点 seam 同步）；
  daemon 生命周期新 spec（条目 upsert/命令面/token/端口/真实 spawn+健康面+干净退出
  + 握手有界路径）；AuthGate 2/2；playwright 16/16（前端零触碰自证）。
- LIVE 冒烟 `scripts/perception-daemon-smoke.sh`（boot-smoke 家族同款隔离纪律）：
  隔离实例 → 引擎 boot → daemon 子进程起 → /health 200（token 门 401 对照）→
  五循环 live 日志 → 心跳腿 live（/api/daemons 托管行 Running+portOpen）→
  干净退出零孤儿。

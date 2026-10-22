# 多设备算力分摊 · 实施规格书（ARCH_SPEC）

> 面向工程 Agent，代码优先、无歧义。人类决策依据见同目录 `MULTI_DEVICE_ARCH_SUMMARY.md`。
> 本文档为**设计交付**：接口/类型/命令均为实施目标态，C1 阶段仅 §5.1、§6 附录为「即刻可用」项。

---

## 1. 配置常量（CONFIG）

```scala
object ComputeConfig {
  // —— 复用既有常量（不改，仅引用；锚点为实施时对照用）——
  val chunkTransportMaxFileBytes: Long = 1_073_741_824L      // 既有 AttachContract.scala:35
  val chunkSize: Int                   = 4 * 1024 * 1024     // 既有 AttachContract.scala:53
  val artifactInlinePullLimit: Long    = 10L * 1024 * 1024   // 既有 NEBFLOW_PULL 门 RemoteExecutor.scala:514
  val inlinePullMaxPerCall: Int        = 3                   // 既有 DeviceProfile.scala:474
  val remoteBgTimeoutSec: Int          = 3600                // 既有 RemoteExecutor.scala:213（>1h 作业拆段依据）

  // —— 本设计新增 ——
  val computeProbeTtlSec: Int        = 600      // 计算探针新鲜度（默认 profile 24h 对计算太长）
  val leaseTtlSec: Int               = 180      // = 3 × 心跳 30s（GatewayMain.scala:320-332）+ 余量
  val maxConcurrentJobsPerDevice: Int = 1       // C2 初值；DESKTOP-IJBBEOT 可到 2
  val suspectBackoffMin: Int         = 10       // 复用既有负探针退避 DeviceProfile.scala:140-143
  val jobDirOnNode: String           = "~/nebflow-compute"          // 节点侧作业根（各节点一致）
  val cacheDirOnNode: String         = "~/nebflow-compute/.cache"   // 节点侧依赖缓存根
  val gatewayInboxDir: String        = "<dataRoot>/compute/inbox"   // 网关侧产物回收根

  // 同步排除表（tar/缓存共用；MINGW64 与 GNU tar 语义一致）
  val syncExclude: Seq[String] = Seq(".git", "target", "node_modules", ".nebflow",
    ".bloop", ".bsp", ".metals", "dist", "release", "*.log")
}
```

`nebflow.json` 新键（C2 起，网关侧；C1 不需要任何配置）：

```json
{
  "compute": {
    "enabled": true,
    "role": "gateway",
    "affinity": [
      { "kind": "windows-test",  "prefer": ["DESKTOP-IJBBEOT"], "fallback": ["fpga"] },
      { "kind": "jvm-heavy",     "prefer": ["dongfeng"],        "fallback": ["DESKTOP-IJBBEOT", "fpga"] },
      { "kind": "fpga-synth",    "prefer": ["fpga"],            "fallback": [] },
      { "kind": "release-verify","prefer": ["dongfeng"],        "fallback": ["DESKTOP-IJBBEOT"] },
      { "kind": "micro",         "prefer": ["local"],           "fallback": [] }
    ],
    "pins": { "jdk": "21.0.11", "sbt": "1.x", "node": ">=20" }
  }
}
```

---

## 2. 类型定义（Scala，实施目标态）

```scala
// ---- 作业 ----
final case class ComputeJob(
  id: String,                    // `<yyyymmdd-hhmmss>-<kind>-<rand4>`，重派保持同 id（幂等键）
  kind: String,                  // 亲和矩阵键：windows-test / jvm-heavy / fpga-synth / release-verify / micro
  deviceConstraint: Option[String], // C1b：显式指定设备名（native 名或 ssh 别名）；None = C2 自动
  affinityFallback: Boolean,     // constraint 设备不可用时是否允许 fallback（默认 false，显式 blocked）
  idempotent: Boolean,           // 可重派前提：作业运行器保证同 id 重跑先清理旧现场
  commands: Seq[String],         // 在节点 jobDir 内逐条执行；每条记录 exit/tokens/时长
  inputManifest: Manifest,       // 同步快照的清单（ADR-7 回执对象）
  artifacts: Seq[ArtifactSpec],  // 声明的产物（相对 jobDir）；未声明文件不回收
  createdAt: Instant, deadlineHint: Option[Duration] = None // hint 仅展示，永不作 kill 依据（零超时纪律）
)

final case class Manifest(rootHash: String, files: Long, bytes: Long,
  fingerprint: String)           // fingerprint = sha256(build.sbt ‖ project/** ‖ package-lock.json) 前16位（缓存键）

final case class ArtifactSpec(path: String, required: Boolean)
final case class ArtifactRecord(path: String, sha256: String, bytes: Long)

// ---- 设备能力档案（device-profiles.json 的 compute 扩展键族）----
final case class DeviceCapability(
  deviceId: String, deviceName: String,
  tier: TrustTier,                              // T0Gateway / T1PairedPeer / T2RegisteredNode
  toolchains: Map[String, String],              // "jdk" -> "21.0.11" 等，探针实测
  cores: Int, memGb: Int, diskFreeGb: Long,
  onlineRate30d: Double,                        // presence lastSeen 滚动窗口（R9：客户端自记）
  probeAt: Instant, probeHash: String,          // probeHash = 本次探针输入的 sha256（结果可比对）
  suspect: Boolean, suspectReason: Option[String])

sealed trait TrustTier; case object T0Gateway extends TrustTier
case object T1PairedPeer extends TrustTier; case object T2RegisteredNode extends TrustTier

// ---- 租约与结果 ----
final case class Lease(jobId: String, deviceId: String,
  claimedAt: Instant, ttlSec: Int, lastHeartbeatAt: Instant)   // heartbeat = presence 事件刷新

final case class JobResult(
  jobId: String, deviceId: String,
  inputManifestHash: String,                     // 节点回执，网关比对 dispatch 时值
  profileSnapshot: DeviceCapability,             // 执行时刻环境（ADR-7）
  commands: Seq[CommandRecord],
  exitCode: Int,
  artifacts: Seq[ArtifactRecord],                // 即 RESULT.json 的 artifacts 数组
  wallMs: Long, startedAt: Instant, finishedAt: Instant)

final case class CommandRecord(cmd: String, exitCode: Int, stdoutTail: String, ms: Long)
```

作业状态机（`queued → leased → running → returning → done`；异常支线 `orphaned → (idempotent) queued[重派] / blocked`）落 `<dataRoot>/compute/queue.jsonl`，与 BgTaskRegistry 的节点完成门闸对齐（`tools/BgTaskRegistry.scala:17-27`：节点挂起直至等待型后台作业回 call）。

---

## 3. 接口定义

### 3.1 作业运行器（节点侧 shell 契约，POSIX + MINGW64 双兼容）

随同步套件分发为 `~/nebflow-compute/nb_job_run.sh`，语义固定：

```sh
#!/usr/bin/env bash
# nb_job_run.sh <jobId> <inputManifestHash> <cmd> [<cmd>...]   （在 jobDir 内执行）
# 职责：
#   1) mkdir -p 与幂等清理：存在 .lock 且存活 → exit 97（作业进行中）；
#      存在 RESULT.json 且 inputManifestHash 相同 → 幂等短路（exit 0，直接复用）；
#      否则清空旧现场重建（同 id 重派语义）
#   2) 逐条执行 commands，记录 exit/tokens/耗时 到 stdout.log（节流追加）
#   3) 终态写 RESULT.json（JobResult 结构，artifacts 由 artifacts/ 实际文件+sha256 生成）
#   4) 释放 .lock；非零 exit 不重试（重试是网关侧调度决策，节点不自主重试）
set -uo pipefail
JOB_ID="$1"; INPUT_HASH="$2"; shift 2
DIR="$HOME/nebflow-compute/$JOB_ID"; mkdir -p "$DIR/artifacts"; cd "$DIR"
# ...（lock/幂等/逐条执行/RESULT.json，实现约 60 行，C1 交付于 tools/compute-sync.sh 内）
```

### 3.2 同步套件（C1 交付为 `tools/compute-sync.sh`，网关侧）

```sh
# 打包 worktree 快照（排除依赖目录）→ 单件 ≤1 GiB（超出即拆包，实测源树远小于此）
compute pack   <srcDir> <out.tgz>                    # tar czf + MANIFEST(rootHash/files/bytes/fingerprint)
# native 腿投递：走 SendMessage attachments（ChunkTransport：4MiB+双侧sha256+续传，落对端 targetDir）
compute push   --device <name> <tgz> [<tgz>...]      # ≤9 文件/消息（AttachContract.scala:38）
# ssh 腿投递：管道直灌，不落中转盘
compute push-ssh <host> <port> <tgz>                 # 见 §6.2 真实命令
# 产物回捞声明：节点侧脚本输出 NEBFLOW_PULL 行（≤10MiB/件、≤3 件/调用）；
# 大件走 ChunkTransport 反向（T1 peer push）或网关 FileTransfer get / scp（ssh 节点）
compute pull   --device <name> <remotePath>          # native：remote-exec FileTransfer get
compute pull-ssh <host> <port> <remotePath> <localDir>
```

### 3.3 调度器（C2，网关内模块 `nebflow/compute/`）

```scala
trait ComputeScheduler {
  def enqueue(job: ComputeJob): IO[JobId]
  def tick: IO[Unit]                       // 状态机推进：租约 TTL 判死（判定 orphan，非 kill 进程）/
                                           // 幂等重派；由派发/回执/presence 事件与既有 sweep 节拍驱动，无新增常驻定时器
  def claimEligible(job: ComputeJob): IO[Either[Blocked, DeviceCapability]]
  // 选择算法：tier 许可(§3.4) ∧ 工具链 pin 合 ∧ disk/mem 达标 ∧ !suspect ∧ 在线
  //           → 打分：affinity命中(主/备) > onlineRate30d > diskFree > cores
  //           → 租约：同设备并发 ≤ maxConcurrentJobsPerDevice，FIFO
  def onLeaseExpired(lease: Lease): IO[RedispatchOr]  // orphaned：idempotent→同id重派下一合格设备；否则 blocked+通知
  def onProbeDrift(dev: DeviceCapability, reason: String): IO[Unit] // suspect=true，停派；金丝雀过闸解除
}
// 金丝雀锚任务：kind=micro 固定输入（fixture 源文件）固定命令（编译+断言输出哈希），金标哈希入配对表
```

API 面（网关 REST，C3 消费）：`POST /api/compute/jobs`、`GET /api/compute/queue`、`GET /api/compute/devices`、`POST /api/compute/devices/<id>/canary`。

### 3.4 安全闸（实施为纯谓词，无新机制）

```
mayDispatch(job, device) =
  device.tier 接受该 kind                    // T0：仅 micro/秘密步骤；T1：windows-test 等；T2：jvm-heavy/release-verify/fpga-synth
  ∧ pairing.allowSpawn(deviceId) == true     // 既有总闸 DeviceRegistry.scala:34
  ∧ job 无秘密步骤                            // 秘密步骤在 T0 内执行（硬规则，不提供旁路）
  ∧ job.commands 逐条过既有审计               // C2 起 remote-exec 命令落 audit/<deviceId>.jsonl
```

nf-ticket **不进**对端路径：`gateway/NfFileRoutes.scala` 为浏览器面（WsAuth 会话），对端认证只有 `X-Neblink-Device` HMAC（P2P）与 relay bearer（`shared/PeerModels.scala:9-18`）——实现时不得为算力面新开文件鉴权通道。

---

## 4. 目录结构（C2 目标态；C1 仅 tools/ 与 docs/ 两项）

```
<dataRoot>/compute/                  # 网关侧（T0）
  queue.jsonl                        # 作业状态机追加台账（审计即状态）
  jobs/<jobId>/job.json              # 派发快照（commands/inputManifest/artifacts 声明）
  inbox/<deviceId>/<jobId>/          # 产物回收落点（对端 jobDir/artifacts 的镜像）
  audit/<deviceId>.jsonl             # remote-exec 命令审计（C2 起）
  cache-index.json                   # 各设备 fingerprint 缓存登记（内容在节点侧，不回传）
<workspace>/
  tools/compute-sync.sh              # C1：同步套件 + nb_job_run（单文件，双平台 bash）
  docs/compute-nodes.zh-CN.md        # C1：节点启用手册（建户/工具链/探针/验收）
src/main/scala/nebflow/compute/      # C2：Scheduler.scala / Capability.scala / Lease.scala / Queue.scala
节点侧（每台）：
  ~/nebflow-compute/<jobId>/{stdout.log, RESULT.json, artifacts/}
  ~/nebflow-compute/.cache/<projectId>/<fp8>.tgz   # 依赖缓存（target / node_modules 打包体）
```

---

## 5. 实施任务清单

### C1a（零引擎代码，1 个脚本 + 1 篇手册）
- [ ] `tools/compute-sync.sh`：pack / push / push-ssh / pull / pull-ssh 五个子命令 + `nb_job_run` 运行器（§3.1-3.2 契约）
- [ ] `docs/compute-nodes.zh-CN.md`：两型节点启用手册（native：配对即用；ssh：建户+工具链+`~/.ssh/config` 条目）
- [ ] DESKTOP-IJBBEOT 腿验收（SUMMARY §7-C1-A1：Windows 测试 + NEBFLOW_PULL 回流归档）
- [ ] 东风云腿验收（SUMMARY §7-C1-A2；前置 = 生产迁移完成，作者条件）

### C1b（最小引擎面：`device` 约束字段）
- [ ] `DelegateRegistry`/delegate 派发入参 + `NodeDef` 增可选 `device: Option[String]`（仅记录与注入，不做调度）
- [ ] 引擎派发前在线预检：离线 → 显式 blocked，文案复用 `RemoteExecutor.scala:1471-1481` 原句式
- [ ] brief 注入约束块：「本腿所有执行类工具调用必须携带 `device: "<name>"`；产物按 §3.2 回捞」
- [ ] 审计：约束声明与预检结果入 flow-map-events（既有事件面）

### C2
- [ ] 能力档案 compute 键族扩展 + 计算探针（TTL 10min；§2.2 字段）
- [ ] `nebflow.json compute.*` 键装载（热载沿用 nfFile.ticketTtlSeconds 的 mtime 门先例）
- [ ] `ComputeScheduler`（§3.3 全部接口）+ queue.jsonl 台账 + audit jsonl
- [ ] 幂等重派 + 金丝雀锚任务 + suspect 停派
- [ ] 面板卡：设备池/队列/在线率/最近探针
- [ ] fingerprint 缓存：pack target|node_modules ↔ 恢复钩子进 nb_job_run

### C3
- [ ] REST 面（§3.3）+ 流水线视图（腿-设备映射）
- [ ] push/manifest 事件触发；产物契约对齐 upd:142-149（URL+sha256 manifest）
- [ ] CI lint 断言 FROZEN 作业仍 `if: false`（ci:37）

---

## 6. 依赖和工具链（真实设备，真实命令）

### 6.1 各节点要求

| 节点 | 形态 | 腿型 | 一次性准备 | 基线探针命令 |
|---|---|---|---|---|
| Mac 主力 | darwin arm64，生产实例宿主 | T0（本机） | 无（已有） | `java -version && sbt --script-version && node --version && sysctl -n hw.ncpu && df -h .` |
| DESKTOP-IJBBEOT | MINGW64_NT-10.0-26200，bash 5.3.15 | native（已配对） | JDK 21.0.11 / node / sbt **已备**（ci:17）；无需 Clash（同步走 P2P） | `Bash(command="java -version && sbt --script-version && nproc && df -h .", device="DESKTOP-IJBBEOT")` |
| 东风云 | Ubuntu，8C16G，114.214.241.93:10022 | ssh（→C2 注册为 T2） | `useradd -m -s /bin/bash nebflow`（无 sudo）；`apt install openjdk-21-jdk sbt`；`~/.ssh/config` 加 `Host dongfeng`（HostName 114.214.241.93 / Port 10022 / User nebflow） | `ssh dongfeng 'java -version && sbt --script-version && nproc && free -m && df -h ~'` |
| fpga | Windows，Tailscale 100.92.65.22 | ssh（→C2 注册为 T2） | `~/.ssh/config` 加 `Host fpga`（HostName 100.92.65.22）；对端 Git-for-Windows bash + JDK/sbt（Vivado 版本单独 pin） | `ssh fpga "java -version 2>&1 && nproc && df -h ."` |

注意：MINGW64 无 rsync（ADR-3 弃 rsync 的直接依据）；两型 Windows 的 tar/ssh 由 Git-for-Windows 内置提供。

### 6.2 真实命令示例（C1 全套可用，逐条可复制）

**① 快照打包（网关 Mac，delegate worktree 内）：**
```bash
tar czf /tmp/wt-job123.tgz --exclude='.git' --exclude='target' --exclude='node_modules' \
  --exclude='.nebflow' --exclude='.bloop' --exclude='.bsp' --exclude='.metals' .
```

**② 投递到已配对 peer（native 腿）——SendMessage attachments（≤9 件/消息，1 GiB/件，自动分块+校验+续传；落点 `~/Downloads` 或受控 targetDir）：**
```
SendMessage(address="node:<target>", attachments=["/tmp/wt-job123.tgz"], message="compute sync wt-job123")
Bash(command="mkdir -p ~/nebflow-compute/wt-job123 && tar xzf ~/Downloads/wt-job123.tgz -C ~/nebflow-compute/wt-job123",
     device="DESKTOP-IJBBEOT")
```

**②' 投递到 ssh 节点（管道直灌，不落中转盘）：**
```bash
ssh dongfeng 'mkdir -p ~/nebflow-compute/wt-job123' && \
  cat /tmp/wt-job123.tgz | ssh dongfeng 'tar xzf - -C ~/nebflow-compute/wt-job123'
```

**③ 节点执行（native 腿，后台；>1h 作业拆成 compile/test 两段，各过 3600s 门）：**
```
Bash(command="cd ~/nebflow-compute/wt-job123 && sbt Test/compile 2>&1 | tail -30 && sbt test 2>&1 | tail -60",
     device="DESKTOP-IJBBEOT", run_in_background=true)
```
**③' ssh 腿执行：**
```bash
ssh dongfeng 'cd ~/nebflow-compute/wt-job123 && sbt Test/compile 2>&1 | tail -30'
```

**④ 产物回捞（**native 腿专用**——NEBFLOW_PULL 扫描只发生在 `device:` 路由的 Bash 输出上，ssh 腿用 ④'）：**
```bash
# 在对端命令内 echo（≤10MiB/件、≤3 件/调用；由 RemoteExecutor 扫描回捞至 <dataRoot>/neblink/captures/）
echo "NEBFLOW_PULL:$HOME/nebflow-compute/wt-job123/artifacts/test-report.html"
echo "NEBFLOW_PULL:$HOME/nebflow-compute/wt-job123/RESULT.json"
```
**④' ssh 腿回捞（显式拉取，无扫描魔法）：**
```bash
scp -P 10022 dongfeng:'~/nebflow-compute/wt-job123/{RESULT.json,artifacts/*}' /tmp/job123-artifacts/
```
大件（>10 MiB，如 assembly jar）：T1 peer 用 SendMessage attachments 反向 push 回网关；ssh 节点 `scp -P 10022 dongfeng:~/nebflow-compute/wt-job123/target/scala-*/standalone.jar /tmp/`。

**⑤ 依赖缓存（fingerprint 命中时，②之前插入恢复；未命中则作业后回填）：**
```bash
ssh dongfeng 'tar xzf ~/nebflow-compute/.cache/stand/<fp8>.tgz -C ~/nebflow-compute/wt-job123'   # 恢复 target/
ssh dongfeng 'tar czf ~/nebflow-compute/.cache/stand/<fp8>.tgz -C ~/nebflow-compute/wt-job123 target'  # 回填
```

**⑥ 幂等回执（RESULT.json 首字段必须回带派发时 inputManifestHash，网关比对不符即拒收）：**
```bash
ssh dongfeng 'head -c 200 ~/nebflow-compute/wt-job123/RESULT.json'
```

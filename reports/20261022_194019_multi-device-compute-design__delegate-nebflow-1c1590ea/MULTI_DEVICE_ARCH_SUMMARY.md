# 多设备算力分摊（分布式构建/测试/CI）· 架构设计 · 人类摘要

> 设计批交付（只设计不实施，零代码改动）。实施规格书见同目录 `MULTI_DEVICE_ARCH_SPEC.md`；
> 架构图：`/Users/kaiyu/Claude code/Stand/.nebflow/worktrees/delegate-nebflow-1c1590ea/reports/20261022_194019_multi-device-compute-design__delegate-nebflow-1c1590ea/topology.svg`（源 `topology.dot`）。

---

## 1. 执行摘要（TL;DR）

**不建新通道、不建对端常驻代理。** 把「算力腿」定义为本已存在的三样跨设备原语之上的一层薄约定：`device:` 远程执行（P2P HMAC + relay 兜底）、ChunkTransport 分块文件面（4 MiB 块 + 双侧 sha256 + 断点续传）、NebLink 注册表（心跳/在线）。在此之上新增的只有：**设备能力档案 + 任务亲和矩阵 + 租约式调度**（C1 手动指定 → C2 自动分摊 → C3 面板化），以及保证结果可信的**作业三件套**（输入清单哈希回执、环境探针快照、产物 MANIFEST）。秘密永不出 T0 网关机；「纯 CI 机」戒律（ci:104）原样继承。

阶段命名 **C1/C2/C3**（Compute）：统一更新系统与本地加速通道已占用 M1/M2/M3 标签，本设计换名空间避免歧义。

---

## 2. 背景与动机

**作者令（2026-10-22 19:2x，微信，逐字）**：「我们有很多的设备 未来我们比较耗资源的 比如测试 CI CD 能否用我们的多设备能力 让其他设备分摊压力？」

| 压力源 | 事实 | 出处 |
|---|---|---|
| CI 配额耗尽 | GitHub Actions 配额用尽；自托管选型已落地方案 B（Mac 本机 runner），方案 A（Windows 台式）因 Clash 未开、GitHub 大文件面超时降级 | `reports/20261021_230000_ci-selfhosted-selection__delegate-nebflow-4e75e4bc.md:8-15` |
| Mac 双重身份 | 生产 Nebflow 实例 + keystore 宿主 + 开发节点；重编译/大测试与生产抢 CPU；服务器迁移可行性已裁定 Mac 不做 7×24 服务器、只保留开发节点 | 同上 :25-26；`reports/20261022_125446_server-migration-feasibility/REPORT.md:11-17,133` |
| 垂直扩展到头 | 今晚 12 delegate 多 worktree 并行已实证；本机 CPU/内存是天花板，设备维度的水平扩展完全未开发 | 2026-10-22 delegate 批次 |
| 算力闲置 | 东风云 Ubuntu 8C16G（免费至 2031-07-23）已被迁移报告点名为「富余（可再跑 CI/算力型任务）」；FPGA 构建机日常空置 | `REPORT.md:118`；本批作者令 |

---

## 3. 现状盘点（本设计消费的既有底子，勿重新发明）

| # | 原语 | 现状（已可用） | 代码锚点 |
|---|---|---|---|
| 1 | `device:` 跨设备执行 | Bash/Read/Write/Edit/Glob/Grep 六工具带 `device` 参数；P2P 直连 `POST <peer>/api/neblink/remote-exec`（HMAC-SHA256 签名），失败/403 自动 relay 兜底 `POST {server}/api/relay/<deviceId>/exec`；对端网关真执行该工具（`isRemoteExec=true`）；同步 120s / 后台 3600s 超时 | `src/main/scala/nebflow/core/tools/RemoteExecutor.scala:1491,286-333,1057-1126,1399-1401`；`gateway/NeblinkRoutes.scala:923-966` |
| 2 | 分块文件通道 | SendMessage attachments / DropboxService：≤9 文件/消息、单文件 ≤1 GiB、4 MiB 块（自适应 256 KiB–4 MiB）、逐块 sha256 + 整件双侧 sha256、断点续传（prefixSha256）；P2P 主 + relay 兜底 | `src/main/scala/nebflow/shared/AttachContract.scala:35,38,53`；`dropbox/ChunkTransport.scala:38-110` |
| 3 | 设备注册表与在线 | 身份 `~/.nebflow/device.json`；配对表 `<dataRoot>/neblink/devices.json`（`DeviceEntry(deviceId, deviceName, sharedKey, allowSpawn)`）；服务器名册心跳 30s 刷新，P2P presence 拨测 45s；寻址按 deviceId/deviceName（精确→前缀/包含模糊→歧义报错） | `core/device/DeviceRegistry.scala:28-45`；`gateway/GatewayMain.scala:320-332`；`neblink/NeblinkPresenceService.scala:1319`；`RemoteExecutor.scala:1458-1485` |
| 4 | 设备能力探针 | `device-profiles.json` 一次性只读 Bash 探针（cwd/kernel/machine/bash/msys/path/browser/shot），24h TTL，失败负缓存 10min 退避；手填 `capabilities` 写入 device.json | `core/tools/DeviceProfile.scala:146,158,349-367`；`neblink/NeblinkModel.scala:76` |
| 5 | 产物回捞 | 对端 Bash 输出中 `NEBFLOW_PULL:<abs-path>` 行被扫描（每次 ≤3 个、单件 ≤10 MiB），经 FileTransfer get 拉回落 `<dataRoot>/neblink/captures/<deviceId>/`；远程图片另有 `[image: ...]` 标记通道 | `RemoteExecutor.scala:421-443,472-519,514-515` |
| 6 | 跨设备 delegate | DeviceDelegate：presence-WS 数据频道 `delegate-spawn/accept/result`，HMAC（ts±90s+nonce），`allowSpawn` 闸门；对端起**自己的**本地 delegate 会话，终态只回传**产物指针清单，字节不回传** | `core/delegate/DeviceDelegate.scala:13-55` |
| 7 | delegate/worktree/合并 | 分支恒 `delegate/<id>`；MergeQueue 每项目 FIFO 单飞行；引擎注入 merge-turn brief 由 delegate 自己跑 `git merge --no-ff` | `core/delegate/DelegateWorkspace.scala:89-146`；`MergeQueue.scala:13-76,291-378` |
| 8 | 后台作业台账 | BgTaskRegistry 跨会话登记本地+远程后台作业，是节点完成门闸；远程后台作业启动即回执、完成经 wsSend 推回 | `tools/BgTaskRegistry.scala:10-80`；`RemoteExecutor.scala:292,758-803` |
| 9 | 现成设备 | Mac 主力（网关/T0）；DESKTOP-IJBBEOT（MINGW64 已配对 peer，JDK 21.0.11/node/sbt 已备，ci:17）；FPGA 构建机（Windows，Tailscale 100.92.65.22，`ssh fpga` 可达，未注册）；东风云 Ubuntu（114.214.241.93:10022，生产迁移完成后启用，作者条件） | 本批作者令；`ci:17,56` |

**关键网络洞察**：算力分摊的同步面走 P2P（Tailscale/LAN 直连，192.168.2.100 已实测）与 relay，**完全不经 GitHub 大文件面**——DESKTOP-IJBBEOT 的网络短板（objects./raw.githubusercontent.com 超时，ci:29）对本路线**不构成阻塞**。这是本路线相对「自托管 runner 方案 A」的结构性优势。

---

## 4. 目标与非目标

**目标**
- G1 设备可声明、可发现、可调度：任务腿能用真名指到真设备，能力档案自动维护。
- G2 作业可信：结果可回答「哪台设备、什么环境、哪个输入、产物哈希」。
- G3 失效可恢复：设备掉线作业可判活、可重派，无静默丢失。
- G4 秘密不外溢：秘密步骤永不出 T0；对端零秘密落盘。
- G5 Mac 减负可量化：重活外移后 Mac 负载下降可测量。

**非目标**
- 不做通用分布式编译缓存（sbt remote cache / Gradle build cache 另立课题，本设计只留 fingerprint 接口）。
- 不改 CI 既有迁移路径：GitHub 自托管 runner 路线继续按 ci:35-37 走；四个 FROZEN 打包作业保持冻结（ci:37）。
- 不在对端引入常驻新进程：复用 Nebflow 引擎自身（对端本来就是一个引擎）。
- 不重建任何传输/注册/执行通道（禁双实现，upd:86 纪律继承）。

---

## 5. 核心决策（ADR）

| ADR | 决策 | 一句话理由 | 代价 |
|---|---|---|---|
| 1 | 执行面复用 `device:` 双腿（P2P remote-exec + relay 兜底），不建新执行通道 | 原语已在生产（12 delegate 实证），HMAC 信任、超时、错误文案全套现成 | 作业 >1h 需按 3600s 后台门拆段或走 relay 观察 |
| 2 | 节点分两等：**已配对 peer**（native 腿）与 **ssh 节点**（未注册算力机）；演进方向「能配对则配对」 | fpga/东风云今天只有 ssh 可达；native 腿才有 presence/探针/回捞全套 | ssh 腿缺 presence，租约判活降级为探活命令（C1 接受） |
| 3 | 同步主通道 = worktree 快照 tar（排除依赖目录）+ 既有 ChunkTransport；git 远端拉取为 C2 优化项 | 作业测的必须是**未提交的 WIP 状态**，git archive-from-HEAD 语义错误；ChunkTransport 完整性/续传现成 | 源树每次全量打包（排除后通常 ≪1 GiB，可接受） |
| 4 | 依赖缓存：设备侧持久缓存，fingerprint 键控，冷启自建；C1 不做跨设备缓存复制 | target/node_modules 过网线毫无意义；fingerprint（build.sbt+project/+package-lock sha256）复用 sbt 自身缓存语义 | 各设备首作业冷启慢一次（记录预期，不瞒报） |
| 5 | 产物回收三层：NEBFLOW_PULL（≤10 MiB 自动回捞）/ ChunkTransport（大件）/ ssh 反向 tar；结果必带 MANIFEST（路径+sha256+size） | 三层全是既有通道；MANIFEST 是可信度闭环的落点 | 作业脚本需多写 3 行清单（约定固化进作业运行器） |
| 6 | 失败语义 = 租约（lease TTL）+ 幂等重派 + **零超时纪律**（无 wall-clock kill，staleness/progress 判活） | 继承 2026-10-19 零超时裁定（upd:50,244；g1:19）；掉线重派需要幂等作业 id | 非幂等作业（副作用型）只能 blocked 等人 |
| 7 | 可信度三件套：输入清单哈希回执 + 环境探针快照（计算 TTL 10min）+ 工具链 pin 比对；漂移→fail-fast→设备 suspect→金丝雀过闸复用 | 假绿比失败更贵；探针/负缓存机制现成，只调 TTL 与新增比对 | 每作业多一次秒级探针（可忽略） |
| 8 | 安全三级：T0 网关（全权+秘密）/ T1 配对 peer（无秘密）/ T2 注册算力节点（专用低权用户）；`allowSpawn` 为总闸；nf-ticket 不进对端路径（它是浏览器面，对端认证走 HMAC/relay bearer） | ci:25-26,104 的隔离排序与「纯 CI 机」戒律直接继承 | T2 需一次性建户/容器（一次性成本） |
| 9 | 调度演进三段：C1 手动声明（约束进 brief + 在线预检）→ C2 能力档案+亲和矩阵+租约队列 → C3 面板化+事件触发 | 先用起来再自动化；pull-on-online 台账模式继承自统一更新系统（upd:126,198） | C1 仍靠人脑选设备（设计意图，非缺陷） |
| 10 | 大件回传方向：默认**网关侧拉**（remote-exec FileTransfer get / scp），T1 peer 亦可主动 ChunkTransport push；C3 才考虑心跳领取式（对端引擎本就是 pull 方） | 对端在 NAT 后，push 不保证可达——统一更新系统同一结论 | 网关必须在线回收（网关本来就在线） |

### ADR-1 详解：为什么复用 `device:` 而不是造作业代理

- **方案 A（选定）**：`device:` 腿。作业就是若干带 `device` 参数的工具调用；信任走既有配对 HMAC；产物走 NEBFLOW_PULL/ChunkTransport。新代码 ≈ 0（C1）/ 一个调度模块（C2）。
- **方案 B（否决）**：对端常驻 job-agent。理由：禁双实现（upd:86）——对端已有完整引擎，再造一个瘦执行器是第二套 compare/install/restart；且 agent 分发/升级立刻牵扯统一更新系统 M3。
- **方案 C（否决为唯一腿，保留为过渡腿）**：纯 ssh。丢失 HMAC 信任面、presence、能力探针、MSYS 命令改写、NEBFLOW_PULL 回捞；仅用于尚未注册的 fpga/东风云。
- **超时代价与对策**：远程后台作业 3600s 门（`RemoteExecutor.scala:213`）。>1h 作业在 C1 拆为「编译腿 + 测试腿」两段（各自 <1h）；C2 由调度器持有 relay 观察腿，绕开单调用门。

### ADR-3 详解：同步三方案对比

| 维度 | git 远端拉取 | 增量 rsync | **tar 快照 + ChunkTransport（选定）** |
|---|---|---|---|
| 覆盖未提交 WIP | ✗（commit 粒度） | ✓ | ✓ |
| 对端前置 | git + 凭据 + 可达远端 | rsync 二进制（MINGW64 默认**无**） | 仅 tar（两平台皆内置） |
| 完整性契约 | git 对象校验 | 无 | 逐块+整件 sha256，断点续传（现成） |
| Windows 大文件面 | 依赖 GitHub 可达（ci:29 现状死） | — | **不经 GitHub**，P2P LAN/Tailscale 直连 |
| 新增基础设施 | 中央远端 + deploy key | — | 0 |
| 结论 | C2 优化项（canonical HEAD 类作业） | 弃 | **C1 主通道** |

---

## 6. 设备能力档案与亲和矩阵（真实设备）

### 6.1 能力档案字段（`device-profiles.json` 扩展 `compute` 键族）

探针既有键（cwd/kernel/machine/bash/msys/path/browser/shot）之外新增：`toolchains`（jdk/sbt/node/python 版本串）、`cores`、`memGb`、`diskFreeGb`、`tier`（T0/T1/T2）、`onlineRate`（presence lastSeen 滚动窗口统计）、`suspect`（漂移标记）。计算场景探针 TTL 从 24h 收紧到 **10 分钟**（ADR-7）。字段定义见 SPEC §2（`DeviceCapability`），各节点基线探针命令见 SPEC §6.1。

### 6.2 亲和矩阵（任务类型 → 设备）

| 任务类型 | 首选 | 次选 | 理由 | 禁区/注意 |
|---|---|---|---|---|
| Windows 测试 / E2E / 截图证据 | **DESKTOP-IJBBEOT**（T1） | fpga（T2） | 唯一已配对 Windows peer；capture=powershell-dotnet；Edge 在位 | 无 Clash 时走 P2P 直连同步（不经 GitHub） |
| sbt 全量重编译 / 大测试套件 | **东风云**（T2，迁移完成后） | DESKTOP-IJBBEOT / fpga | 8C16G 空闲算力，Linux 与发布目标同族；mig:118 已提名 | 启用以生产迁移完成为前置（作者条件） |
| Release 打包验证（assembly+sha256） | **东风云** | DESKTOP-IJBBEOT | 同上；产物契约对齐统一更新系统（upd:142-149） | 冻结打包作业不因此解冻 |
| FPGA 综合/实现 | **fpga**（T2） | — | Vivado 工具链仅此一家 | 未注册期走 ssh 腿；长作业按 ADR-1 拆段 |
| 依赖冷缓存预热 | 目标设备本机 | — | 缓存留在设备侧（ADR-4） | 跨设备复制 C1 不做 |
| <2 分钟小作业 / 秘密步骤 / 审核 | **本机 Mac**（T0） | — | 派发开销 > 收益；秘密不出 T0 | Mac 不接重活（本设计的动机本身） |
| 跨设备 delegate 级流程（设备驻留工作流） | DeviceDelegate 整会话外派 | — | 既有机制：对端起自己的会话，回传产物指针（`DeviceDelegate.scala:33-40`） | 与「单腿作业」分工：有状态多步→外派会话；单件无状态→算力腿 |

---

## 7. 分阶段里程碑与验收标准

### C1 = Phase 1 · 手动指定设备腿（≈0 行引擎代码 + 1 个小字段）

**C1a（零代码，纯用法约定，今天即可用）**：delegate/节点 brief 里写明「此腿在 `<device>` 执行」，会话用 `device:` 参数或 ssh 包装执行；同步用 `tools/compute-sync.sh`（打包/回传脚本，契约见 SPEC §3.2）。
**C1b（最小引擎面）**：delegate 派发与 ChainWrite/NodeEdit 节点新增可选 `device` 约束字段 → 引擎三件事：① 约束块注入 brief（「所有执行工具必须携带该 device」）；② 派发前在线预检（离线 → 显式 blocked，附既有报错文案）；③ DelegateRegistry 记录约束。

**验收标准**
- [ ] A1 Windows 腿：brief 声明约束 → DESKTOP-IJBBEOT 跑 `sbt test`（或 npm test）→ junit 报告 + 截图经 NEBFLOW_PULL 回流并归档 reports/。
- [ ] A2 Linux 腿（东风云，迁移完成后）：tar 快照同步 → `sbt Test/compile` + assembly → 回传 jar + sha256，与 Mac 本地同输入产物哈希比对（JVM 差异已记录可解释）。
- [ ] A3 离线行为：目标设备离线时腿显式 blocked（报错文案：`Device 'X' not found among N peer(s)...` / `Cannot reach X...`），无静默失败。
- [ ] A4 安全：全程零秘密落对端；`allowSpawn=false` 设备派发被拒。
- [ ] A5 交付物：`docs/compute-nodes.zh-CN.md` + `tools/compute-sync.sh` + C1b 字段（改动面见 SPEC §5-C1b 清单）。

### C2 = Phase 2 · 自动分摊调度器

能力档案扩展（6.1）+ 亲和矩阵配置化（`nebflow.json` `compute.affinity`）+ 网关侧租约队列（筛选→打分→租约→执行→回收）。作业状态机：`queued → leased → running → returning → done / orphaned → redispatched / failed / blocked`。

**验收标准**
- [ ] A1 节点只声明 `compute: linux-ci` 类亲和约束，调度器自动选中合格设备执行。
- [ ] A2 掉线重派：作业中拔设备 → 租约过期判 orphaned → 幂等作业自动换次选设备重跑，全程审计留痕（audit jsonl）。
- [ ] A3 漂移防护：JDK 版本与 pin 不符 → fail-fast + 设备 suspect 停派；金丝雀锚任务（固定输入固定产出的微作业）通过后才复用。
- [ ] A4 缓存：同 fingerprint 第二次作业命中设备侧缓存（耗时对比归档）。
- [ ] A5 面板卡：设备池 + 队列 + 在线率 + 最近探针时间可见。
- [ ] A6 规模：≥3 设备、并发 ≤4、连续 20 作业零静默丢失。

### C3 = Phase 3 · CI 面板化

**验收标准**
- [ ] A1 push / manifest 事件触发流水线视图，腿-设备映射实时可见。
- [ ] A2 失败从面板直达日志与产物下载（本地回放走 nf-ticket 浏览器面——合法用途）。
- [ ] A3 release 验证作业产出符合统一更新系统产物契约（URL + sha256 manifest，upd:142-149），可被更新 M1 直接消费。
- [ ] A4 与 GitHub 自托管 runner 路线并存不互斥；CI lint 断言 FROZEN 作业仍 `if: false`（ci:37）。

---

## 8. 风险表

| # | 风险 | 概率 | 影响 | 缓解 | 残余 |
|---|---|---|---|---|---|
| R1 | DESKTOP-IJBBEOT 无 Clash 时 GitHub 大文件面死（ci:29） | 高（现状） | 同步/回传慢 | 本设计同步面走 P2P 直连/relay，不经 GitHub（§3 洞察） | 低 |
| R2 | 东风云生产迁移延期 / 443 入站被阻（mig:101,137） | 中 | C1 的 Linux 腿无落地 | C1 Linux 验收显式以迁移完成为前置；过渡期 sbt 重活可落 DESKTOP-IJBBEOT（JDK 已备） | 中 |
| R3 | 工具链漂移（对端 JDK/sbt 版本 ≠ 仓基线）造成假绿 | 中 | 高 | pin 比对 fail-fast + 金丝雀（ADR-7） | 低 |
| R4 | 依赖冷启动：每台设备首次作业全量编译 | 高 | 单次作业 10× 慢 | fingerprint 缓存持久化；预期管理（首跑慢是诚实数据） | 低（只付一次） |
| R5 | 设备作业中途掉线 | 中 | 作业时长损失 | 租约 + 幂等重派（ADR-6）；非幂等作业 blocked 等人 | 低 |
| R6 | 结果不可信（环境隐性差异，如换行符/路径分隔符） | 中 | 中 | 作业运行器统一 `core.autocrlf=false`、MSYS 路径改写复用既有机制；差异在 MANIFEST 元数据暴露 | 低-中 |
| R7 | 秘密外溢到对端 | 低 | 高 | 秘密步骤 T0 内执行（硬规则）；审计 jsonl 留痕；对端不存任何凭据 | 极低 |
| R8 | ssh 腿无 presence，判活滞后 | 中（C1） | 低 | C1 接受；C2 native 化后消除（ADR-2） | — |
| R9 | neblink-server 在线率统计在服务端、客户端无计数 | 低 | 低 | C2 客户端自记 lastSeen 滚动窗口（探针顺带） | 低 |
| R10 | 重活外移后 Mac 仍被占（调度不纪律） | 中 | 中（本设计动机落空） | 亲和矩阵把 Mac 定为「小作业/秘密/审核」专属；调度器打分排除 Mac 接重活 | 低 |
| R11 | 冻结打包作业被误解冻 | 低 | 高 | 维持 ci:37 冻结决定；C3-A4 lint 断言 | 极低 |
| R12 | 对端引擎版本漂移导致 remote-exec 行为差异 | 低 | 中 | 统一更新系统 M2/M3 的版本台账直接复用为档案字段（§9） | 低 |

---

## 9. 与现有系统的边界（复用/并行，不重叠）

| 现有系统 | 关系 | 明确边界 |
|---|---|---|
| **本地加速通道（#642，数据面 v1+v2）** | **复用（作为下层）** | 其 locality 位（local/device/server）与 M2 分块传输腿（4 MiB+sha256+续传）就是本设计的**传输层**；本设计是数据面的**算力消费者**，不重建任何传输。注：#642 编号在本 worktree 内不可考，按命名惯例推断为本地加速通道的作者侧台账号（假设，非仓内事实） |
| **统一更新系统 M1-M3** | **复用（互为供需）** | 产物契约（URL+sha256 manifest）= 本设计 release 验证作业的**输出格式**；其 M2 设备版本台账 = 本设计能力档案的**引擎版本字段**；其 M3 收敛循环保证对端引擎版本一致（治 R12）。更新系统明确把 CI/发布编排留白（upd:30）——本设计接手编排，但走同一产物契约 |
| **link-server 设备注册表（Neblink 栈）** | **复用（唯一事实源）** | 身份/在线/endpoints/配对信任全以它为准；能力档案是 `device-profiles.json` + `capabilities` 的**扩展**，不建第二注册表。在线率统计本设计客户端自记（R9） |
| **CI 自托管选型（已落地事实）** | **并行（面不同）** | GitHub Actions self-hosted runner = GitHub 面的执行器（Mac 已落地、Windows 备用）；本设计算力池 = Nebflow 面的执行面。C3 两者可由同一事件触发；runner 迁移路径（ci:35-37 四重作业）与 FROZEN 冻结（ci:37）均不被本设计改动 |
| **服务器迁移（#722）** | **参数供给** | 东风云 RTT 5-30ms、8C16G、可用期至 2031-07 为调度打分参数；迁移完成 = 东风云启用前置条件（作者条件）。迁移若走 B1/B2 分支，本设计不受阻（ssh 腿照常） |
| **DeviceDelegate（跨设备 delegate）** | **并行（粒度不同）** | 整会话外派（对端起自己的引擎会话，回传产物指针）用于设备驻留多步流程；算力腿 = 单件无状态作业。选择规则：有状态多步→DeviceDelegate；单件→算力腿 |

---

## 10. 下一步行动（给作者/后续实施批）

1. **今天**：C1a 零代码试用——挑一条 Windows 测试作业按 SPEC §6.2 命令示例在 DESKTOP-IJBBEOT 跑通一轮（同步→执行→NEBFLOW_PULL 回捞）。
2. 东风云生产迁移完成后：建 `nebflow` 低权用户 → C1-A2 Linux 腿验收。
3. C1b 最小引擎面（`device` 约束字段 + 在线预检）单独立批实施，改动面见 SPEC §5。
4. C2 调度器实施前先跑一周 C1 手动腿，用真实作业数据校准亲和矩阵与缓存 fingerprint 粒度。
5. 全程不动：FROZEN 打包作业、GitHub runner 迁移路径、本地加速通道数据面实现。

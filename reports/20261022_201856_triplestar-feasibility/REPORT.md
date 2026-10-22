# 结合 triple-star 评估多设备算力分摊需求 · 可行性分析

> **批次**：分析批交付（只分析不实施；零代码改动）。triple-star 仓只读克隆于 `/tmp/triple-star-ro`（worktree 外隔离目录；HEAD `e752ca1`，2026-10-22 13:22 +0800，2099 文件；全程零推送零写入）。
> **输入三方**：① triple-star 仓（张安齐支线）；② 主线设计稿 `reports/20261022_194019_multi-device-compute-design__delegate-nebflow-1c1590ea/`（SUMMARY+SPEC，已合入主线 `284cd6f5`）；③ 张安齐的容器化建议（作者微信转述：「系统就会自动把构建 打包为容器 然后找一个系统资源多的机器去跑」）。
> **证据标注**：【亲验】＝本报告直读核对过原文；【转引】＝探查腿给出锚点、方向可信但未逐行复核。仓内路径省略 `/tmp/triple-star-ro/` 前缀。

---

## 0. 结论（TL;DR）

**可行，且两条线是互补物、不是竞争者。** triple-star 交付的是「**单机内**」的算力治理——执行单元隔离（lite/chroot 与 Firecracker microVM 两档）、资源准入（govd 按本机 procfs 水位判收拒）、内核执法（eBPF LSM）——但它**没有也不做「跨机」算力分摊**：无中央调度器、无节点发现/注册/心跳（bridge-host 自钉「不是机器代表」，bridge-host.ps1:7【亲验】），远程设备面排在复现 P4 未落码（replication-map.md P4【亲验】）。跨机放置恰是主线 C1/C2 设计的领地；反过来主线刻意不造的「节点侧强隔离执行单元与容器化打包」恰是 triple-star 的强项。张安齐一句话里的两半——「打包为容器」（triple-star 有）＋「挑资源多的机器」（两家都没有）——本设计稿的 C2 调度器正是缺失的那一半。

**分档裁定**：C1 按原 SPEC 今天就能做（对 triple-star **零依赖**）；容器化执行采纳为 **C2 期 Linux 腿（东风云）专属优化**；Windows 两台（DESKTOP-IJBBEOT / fpga）保持同步+原生；triple-star 作为「T2 之上的执行单元层」登记为 **C2+ 可选适配**，接缝＝ssh 命令面（govd 闭集动词），**不做源级依赖**（守两线独立开发纪律）。

| 问 | 一句话裁定 |
|---|---|
| **A** 可借什么 | 概念/纪律层可借 6 件（准入预演、闭集执行器、心跳 K 拍判死、digest 产物清单、能力合成键、fail-closed）；代码直接复用价值低（Rust vs Scala、内核信任面 vs HMAC 配对面）；与 C2 调度器＝**互补**：它管单机准入与隔离，C2 管跨机放置 |
| **B** 分工与当腿 | 主线当脑（注册表/租约/审计/秘密），triple-star 节点当「隔离执行岛」；**「T2 之上再长一层」可行**（限 Linux 腿、C2+、可选）；「平行方案」今天不可行（跨机面 P4 未落码、Windows 件是 WSL 本机对且无认证） |
| **C** 容器 vs 原生 | 东风云（Linux）＝容器（C2 起，工具链镜像+tar 注入 WIP）；DESKTOP-IJBBEOT / fpga＝同步+原生（容器在无 Clash Windows 上添乱，工具链已备/Vivado 仅此一家）；Mac＝不变（只跑小作业） |
| **D** 可行性 | **现在就能做**：C1 全套。**要补**：原 C1a/C1b 清单不变＋东风云容器基座（C2）。**不可行/不做**：整体采用 triple-star 当分摊系统、Windows 侧容器化、东风云 microVM 档（嵌套 KVM 存疑）、全局实时负载均衡（C1/C2 是规则放置，诚实命名） |

---

## 1. triple-star 架构速览

### 1.1 它是什么：主线自己的「基底分叉探索支」

triple-star 不是第三方项目，是 Stand/Nebflow 的支线：2026-10-16 由作者两道令开支（docs/process/proc-branch-triple-star-2026-10-16.md:13,15【亲验】）：

- **第一令**：「帮我新建一个分支，不从零开始构建虚拟机，而是尝试基于容器化虚拟机，轻量化虚拟机或者linux构建一个agent原生的附属系统。具体目标参考triple路线。分支代号triple*」
- **第二令**：「我的想法是扩展linux内核，提供系统级agent编排功能。在此基础上，支持分层分权限系统层自修改，以及分布式部署」
- 第三令（10-17）：类型化 agent 范式＋用户态编译器物化成内核可执行代码；wasm 不一定再需要（:17【亲验】）。

⇒ **「分布式部署」写在该支的开支令里**——这与本报告的问题直接同源，但该支把分布式读作「多节点各为扩展内核节点」的部署形态，而非「跨机算力调度」。

**与主线的关系（effect oracle）**：独立开发令全套——main 由「设计权威」降为「**效果 oracle**」，对拍门/语料/自期望＝唯一接口，零写主线仓；对拍适用面注：治理/装载/生命周期等**效果面**可对拍，**执行基底与内核面 main 无对应物 ⇒ 登记 oracle 不适用**（proc-branch…md:40【亲验】；定义单点＝decision-026 §B-①【转引 decision-026-2026-10-12.md:22】）。对拍口径＝可观测**效果等价**，非实现同构【转引 replication-map.md:3-4】。

**工程现状**：支内门禁全量 E2E 888/888（26 脚本）、checker 26/26、15 镜像已推 USTC、relay `wss://` 已落码（proc-branch…md:126-137【亲验】）；**未做**：跨设备 linkd 传输（:138-139【亲验】）。

### 1.2 层模型与 agent 形态

star 支落地层图【转引 docs/design/layers.md:9-49】：L0＝Linux 内核＋BPF 强制/观测件＋SCX 调度器；L1＝入口件（**star-govd 治理核**、browser 壳、term 联邦、bridge、bundle）；L2＝agent 应用＝**bundle**（manifest 治理面＋logic 逻辑面＋ui 界面面）；L3＝运行中的 triple* 实例（WSL2/VZ/Linux 载体）；L4＝运行数据实例层（事件流/journal/vols/store）。

agent 实体＝**OS 进程**（bundle logic，一 unit 一进程）；**不采 actor 模型作为一等机制**——「actor」在库内只是对主线 `ActorSystem.scala` 的分析对象与历史裁定「任务级并发、绿色任务/轻量 actor」的基线读法【转引 docs/design/sub/sub-driver-concurrency-and-distribution.md:191】。⇒ 作者「actor 能派上很多用场」的直觉，在 triple-star 词表里的对应物是**联邦席位路由/快照迁移**（terminal 联邦草案，未裁【转引 proc-star-terminal-federation-2026-10-17.md:2-33】）与单机内核 cgroup 域调度，而不是一个可派生的 actor 框架。

### 1.3 分布面：权威模型「设计有」、跨机调度「落码无」

- **有（设计）**：真源单点＋一圈投影；路由＝可重建纯函数 `hash(sessionId) mod N`；失效由第三方按真源侧心跳行**连丢 K 拍判死**；跨机一跳＝核侧发起的同步请求-响应，无服务器推送（docs/design/05-distribution.md:17-35,56-73【亲验】）。
- **无（落码）**：
  - **无中央算力调度器**：唯一的「调度」件全是单机内——进程协作 fair 调度（triple-driver，进程内【转引 crates/triple-driver/src/assembly.rs:180,353】）、cron（cap/crates/schedd「v0 不自带执行器」【转引 lib.rs:1-6】）、cgroup 域调度（SCX，单机内核面【转引 layers.md:46,86】）；
  - **无节点发现/注册/心跳**：bridge-host.ps1:7 自钉「不是机器代表（无注册/无发现/无心跳语义）」【亲验】；cap-relay 用静态 devices.json【转引 cap/crates/cap-relay/src/lib.rs:1-33】；
  - **govd 资源准入只看本机**：`DEVICE-CAPACITY` 读 procfs MemAvailable/MemTotal（probe/star-govd/src/main.rs:2245,2390【亲验】），`DEVICE-ADMIT` 准入预演与实际启动同一判定函数（:3206【亲验】），guest 水位门拒新设备（:141【亲验】）——全是**单机容量闸**；
  - **tres**（逆向分析服务）有 gateway worker 池/队列/配额，但**跨节点调度列为非目标**【转引 docs/design/tres.md:48-72】；
  - **远程设备面排在复现 P4**：「social/远程设备（NebLink 对位＝联邦线）」，未落码（replication-map.md P4【亲验】）。
- **已落码的唯一多机件＝relay 隧道**：「每台电脑装独立 server，中心只做反向隧道+主机名路由」，本地只出站、relay 按 Host 左一标签转流（docs/design/relay-tunnel.md:5-16【亲验】）——定位是**浏览器访问路由**，不是算力面，但它是「节点连接/寻址」的现成雏形。

### 1.4 执行单元与隔离（它的强项所在）

- **四种执行形全为真代码**【转引】：`star-run-lite`（unshare+chroot+cgroup 轻运行时，诚实边界：无 seccomp/user-ns，probe/star-run-lite/src/main.rs:1-6）；`star-run-vm`（Firecracker microVM，vsock/串口 READY-DONE，:1-4,22-23）；`star-run-cf`（Cuttlefish Android 设备单元，adb+pidfd）；`star-run-host`（**macOS** Seatbelt+rlimit，`serve` 行 JSON 守护与 bridge 协议兼容，main.rs:29,488-489；非 macOS fail-closed，backends.rs:1-2）。
- **eOCI 制品线**：OCI layout 摄入＋全 digest 校验＋`org.star.*` 治理注解（crates/star-oci/src/lib.rs:44-60【转引】）；bundle v1＝manifest/logic/ui【转引 star-bundle lib.rs:1-8】；内容寻址资产库（star-assets，`name@digest` 钉扎【转引 lib.rs:1-7】）。两档单元：**轻档**＝宿主进程+cgroup 键执法；**强隔离档**＝每单元一 KVM microVM（Firecracker，rootfs 只读/默认无网）【转引 docs/design/oci-superset.md:1-69】。
- **内核执法**：eBPF 观测（execve/exit tracepoint→ringbuf）＋ LSM `file_open` 强制＋策略 map 热换；**LSM 档需自编 star1 内核**，观测档任意内核可用【转引 probe/README.md:9-13】。
- **载体矩阵**：Linux 原生；macOS＝Lima/Apple-VZ 载体**已验证**（BPF-LSM 链在 guest 内跑通）；**Windows＝WSL2 载体仅脚手架、从未在 Windows 实跑**【转引 deploy/README.md:8-12；deploy/windows/README.md:17-21】。native bridge-host.ps1 只服务 WSL 节点↔Windows 宿主的本机对（绑 vEthernet 172.22.16.1，bridge-host.ps1:15【亲验】），操作闭集白名单（host_ping/env/write/read/log）、零执法、无认证（bridge-host.ps1:1-12【亲验】）。

---

## 2. 四问裁定（A/B/C/D）

### A. triple-star 对「多设备算力分摊」可借什么；与 C2 是同类还是互补

**裁定：互补，不同层。** triple-star 的全部「调度」词表（进程 fair 调度/cron/SCX cgroup 域/水位准入）都在**单机内**；跨机维度的它的答案是「**层携带分布性：L4 只能被控机本地执行**」——计算放置由层位推出，而非全局拓扑调度【转引 05-distribution.md:196-214】。即：**它把计算放置问题消解在层语义里，根本不设跨机放置器**；主线 C2（亲和矩阵+租约+打分）恰好就是它没有的那个跨机放置器。同名为「调度」，层位不同，不是同类竞争物。

可借清单（按借法分两档）：

| # | 可借 | 出处 | 借到主线哪里 |
|---|---|---|---|
| 1 | **准入预演模式**：准入判定做成纯函数，另有 `DEVICE-ADMIT` 只读预演动词，与实际启动同一判定函数 | star-govd main.rs:3206【亲验】 | C2 `claimEligible` 拆出纯谓词＋增加「预演」查询（派发前回答「此刻派这台会不会收」），与 SPEC §3.3/§3.4 安全闸天然同构 |
| 2 | **闭集执行器＋节点零执法**：节点只执行白名单操作并回执，准入/裁决全在治理侧 | bridge-host.ps1:1-9【亲验】 | C1 `nb_job_run.sh` 纪律表述：节点只做 清理/执行/记录/RESULT.json 四件事，零调度逻辑——与 SPEC §3.1 一致，写进 docs/compute-nodes.zh-CN.md |
| 3 | **心跳 K 拍判死**：失效由第三方按真源侧心跳行连丢 K 拍判定 | 05-distribution.md:31【亲验】 | 与 SPEC §1 `leaseTtlSec=180 = 3×心跳 30s` **收敛同构**——保留原值，SPEC 注释补「K=3，与 triple-star decision-004 §A-3 同构」作交叉佐证 |
| 4 | **digest 产物清单**：内容寻址（sha256 树/`name@digest` 钉扎）与只读共享 | star-assets lib.rs:1-7【转引】 | RESULT.json artifacts[] 已带 sha256（SPEC §3.2）；对齐命名纪律，不新造 |
| 5 | **能力条目合成键 `(deviceId, capability, scope)`，多来源不合并不丢弃** | 05-distribution.md:29-30【亲验】 | C2 DeviceCapability 登记规则：配对档案与 ssh 探针对同一设备并存时不合并（保留冲突可见），择信源优先级而非合并值 |
| 6 | **fail-closed 纪律**：后端能力不足显式拒绝，不静默降级 | star-run-host backends.rs:1-2【转引】 | 主线本就有（A3 离线显式 blocked）；交叉引用，不改设计 |
| 7 | （暂存）**macOS 原生沙箱执行器**：star-run-host serve 是全家族唯一非 Linux 原生执行机制 | main.rs:29,488-489【转引】 | 主线暂无需求（Mac=T0 只跑小作业/秘密）；登记 backlog：若未来 Mac 也要接隔离作业，先评估它而不是新造 |

**代码直接复用：价值低，不建议**——语言（Rust vs 主线 Scala）、信任面（内核 LSM/cgroup vs 主线 HMAC 配对+relay bearer）、工程纪律（零第三方 vs 主线栈）三重异构；强行源级复用会打破两线独立。**接口面复用（命令面互操作）才是正确接缝**（见 B）。

### B. effect-oracle 模型下的分工；triple-star 节点能否直接当计算腿

**分工图（建议）**：

```
主线 stand（脑）：NebLink 注册表 / 租约队列 / 亲和打分 / 审计 / 秘密（永不出 T0）
      │  跨机腿 = 既有 device: 远程执行（P2P HMAC + relay 兜底）或 ssh
      ▼
triple-star 节点（执行岛，仅 Linux）：govd 治理核（准入闸 DEVICE-ADMIT）
      └─ 执行单元两档：lite（chroot+cgroup）/ microVM（Firecracker）＋ LSM 内核执法（可选强档）
```

- **「平行方案」（用 triple-star 自己长出多设备系统）＝今天不可行**：它的跨机面（节点发现/心跳/委派/远程设备）整体排在复现 P4 未落码【亲验 replication-map.md:37；proc:138-139】，Windows 载体从未实跑【转引 deploy/windows:17-21】——选它等于等别人的未建工程，而主线 C1 今天零新增即可用。
- **「T2 ssh 节点之上再长一层」＝可行，且是推荐的最终形态（Linux 腿限定、C2+ 可选项）**：C1/C2 的 ssh 腿照旧由主线驱动；节点内部把「裸跑 sbt」升级为「govd 受理＋隔离单元内执行」。集成接缝＝**ssh 命令面上的闭集动词**（如 `govd DEVICE-ADMIT …` 预演→`DEPLOY/START` 单元），主线对 triple-star **零源级依赖、零运行时链接**——两线各自独立发展，effect-oracle 的单向关系（主线=行为基准）不被打破【亲验 proc:40】。
- **前置与硬限制**：govd 为 Rust 实码、普通 Ubuntu 内核即可跑 lite 档（chroot/unshare/cgroup 是内核标配）；**LSM 强制档需刷 star1 内核——C1/C2 期不引入**（第一方 T2 节点的信任问题已由「秘密不出 T0」硬规则圈住，刷内核的运维代价不成比例）；**microVM 档需 /dev/kvm**，东风云是云 VM，嵌套 KVM 大概率不可用，启用前探针判定（§5 开放项 O1）。
- **纪律注**：主线若要把 triple-star 代码取进主线仓，属跨线引用——独立开发令文本约束的是 triple-star 零写主线、主线=效果 oracle【亲验 proc:40】；反向取用未被禁止，但精神是两线独立。本报告建议：**只借模式与独立叶子件，任何更深复用交作者裁定**（不擅裁）。

### C. 容器化执行 vs 同步+原生：按节点分

先把张安齐的建议拆成两半：**「打包为容器」**（制品化：环境+依赖钉进不可变制品）＋**「挑资源多的机器」**（放置：跨机选择）。triple-star 有前一半（eOCI/两档单元），**没有后一半**（§1.3）；主线设计有后一半（C2 亲和+打分+租约），刻意不做前一半（ADR-3 tar 快照、ADR-4 设备侧缓存）。两家拼起来才等于那句话的全集。

| 节点 | 形态 | 裁定 | 理由 |
|---|---|---|---|
| 东风云 | Ubuntu 8C16G，ssh→T2 | **容器化（C2 起）**：基础镜像＝pin 死的 jdk21+sbt 工具链；**WIP 用 tar 注入挂载**，产物落卷回捞 | 同族 Linux、镜像分发路径现成（USTC 镜像仓已被 triple-star 实用，15 镜像已推【亲验 proc:129,163-165】）；环境漂移风险（R3）被镜像钉死；前提＝生产迁移完成（作者条件）＋`docker info`/权限探针（§5 O1） |
| DESKTOP-IJBBEOT | MINGW64，已配对 T1 | **同步+原生（原案不变）** | JDK/sbt/node 已备（ci:17）；容器=装 Docker Desktop+WSL2 后端，且无 Clash 时镜像拉取面存疑（ci:29 同族风险）；triple-star Windows 件零可借（WSL 本机对、无认证【亲验 bridge-host.ps1:15】） |
| fpga | Windows，Tailscale ssh→T2 | **同步+原生** | Vivado 工具链仅此一家且授权/体积不适合容器化；triple-star 在 Windows 无内核机制可借 |
| Mac 主力 | darwin，T0 | **不变**：只跑小作业/秘密/审核 | 容器化对 Mac 减负目标无意义；未来若要隔离作业再评估 star-run-host（§A-7） |

**WIP 语义警告（重要）**：本设计作业测的是**未提交的 WIP 状态**（ADR-3：git-archive-from-HEAD 语义错误）。容器化的正确形是「**工具链进镜像、WIP 用 tar 注入**」，而不是「每次从 repo 构一个镜像」——后者比要测的构建本身还慢，且语义仍错。张安齐的口述模型（「把构建打包为容器」）天然适配 git-repo 粒度的 CI 作业；落到我们 C1 的 WIP 作业上必须改读为制品分层。

### D. 可行性判定

**现在就能做（C1，原 SPEC 原样，对 triple-star 零依赖）**
- C1a：`tools/compute-sync.sh` + `docs/compute-nodes.zh-CN.md`（SPEC §5-C1a）；DESKTOP-IJBBEOT 腿验收今天可跑（工具链已备）。
- C1b：`device` 约束字段＋在线预检（SPEC §5-C1b）。
- 依据：所需全部原语（device: 双腿/ChunkTransport/NebLink 注册表/能力探针/NEBFLOW_PULL 回捞）在生产在位（SUMMARY §3 表，锚点齐备）；triple-star 不提供其中任何一件、也不阻塞任何一件。

**要补的（清单，按时序）**
1. C1a/C1b 原清单（不变）。
2. 东风云启用前置：生产迁移完成（作者条件）→ 建低权户 → `docker info`、`ls /dev/kvm`、注册表可达性三探针（§5 O1）→ C1-A2 Linux 腿验收。
3. C2 原清单＋两条增量：① `claimEligible` 拆纯谓词＋准入预演查询（借 govd 模式，§A-1）；② Linux 腿容器执行形（工具链镜像+tar 注入，§C）——镜像仓库选型启用时定（USTC 仓为默认候选，需作者确认权限）。
4. C2+（登记不排期）：triple-star 执行岛适配——东风云部署 govd，ssh 腿动词面从裸命令升级为「ADMIT 预演→DEPLOY/START 单元」；以 C1 跑一周真实作业的数据为启动条件（同 SUMMARY §10-4 纪律）。

**不可行/不做的（及原因）**
1. **整体采用 triple-star 当多设备分摊系统**：跨机面未落码（P4）＋无跨机调度（设计消解于层语义）＋Windows 载体未实跑——它今天回答不了这个问题。
2. **Windows 两台容器化**：工具链已备/已配对，容器引入的运维面（Docker Desktop/WSL2/镜像拉取网络）全是净增成本。
3. **东风云 microVM 强隔离档**：嵌套 KVM 存疑（云 VM），且 C1/C2 期威胁模型（第一方节点+秘密不出 T0）不需要 microVM 级边界；lite/容器档已够。
4. **「全局实时负载均衡」式调度**：C2 打分输入是在线率/磁盘/核数等**准静态档案**＋亲和规则，不是实时负载遥测——诚实命名为「规则放置」。真·负载驱动需资源遥测通道（govd 的 `DEVICE-CAPACITY` 上报形可作 C3 参考），本期不做。
5. **内核执法档（star1 刷内核）**：不引入（理由见 B）。

---

## 3. 三方对比表

| 维度 | 主线设计（C1/C2/C3） | triple-star（支线现状） | 张安齐建议（口述） |
|---|---|---|---|
| 问题定位 | 跨机算力**放置**与作业可信 | 单机算力**治理**与执行隔离 | 「打包为容器＋挑资源多的机器」愿景 |
| 调度面 | 亲和矩阵+租约队列+幂等重派（设计待实施） | **无跨机调度**；单机内：fair 调度/cron/SCX 域/水位准入（部分已落码） | 有愿景无机制 |
| 执行单元 | 节点裸工具链（tar 同步+原生跑） | lite / microVM / Cuttlefish / macOS Seatbelt 四形（真代码）；eOCI 制品线 | 容器（形制未定） |
| 传输面 | device: 双腿（P2P HMAC+relay 兜底，**生产在位**）+ssh | relay 隧道（已落码，访问路由定位）；bridge 本机对（proof 级） | 未涉及 |
| 节点发现/心跳 | NebLink 注册表+presence（**生产在位**） | **无**（bridge-host 自钉无注册/发现/心跳） | 未涉及 |
| 信任模型 | T0/T1/T2 三级+allowSpawn 闸+秘密不出 T0 | 内核执法（LSM/BPF）+manifest 声明+govd 判定 | 未涉及 |
| 覆盖 OS | Mac(生产)/Windows(已配对+ssh)/Linux(ssh) | Linux 原生；macOS 载体已验证；**Windows 载体未实跑** | 未涉及 |
| 成熟度 | 设计交付完毕，C1 零代码可用 | 单机线工程成熟（888/888 E2E），跨机线 P4 未动工 | 一句话 |
| 与「算力分摊」的距离 | **一步之遥**（C1 即用） | 隔一层（缺跨机放置与发现） | 隔两层（无机制） |

---

## 4. 建议采纳清单

**采纳（7 条，每条给落点与时机）**

| # | 采纳 | 落点 | 时机 |
|---|---|---|---|
| 1 | 准入判定纯函数化＋`ADMIT` 只读预演动词（govd 模式） | SPEC §3.3 `claimEligible` 拆谓词＋新增预演查询 | C2 |
| 2 | 「闭集执行器＋节点零执法」写进节点手册纪律 | docs/compute-nodes.zh-CN.md（nb_job_run 四件事） | C1 |
| 3 | 租约 TTL=3×心跳 与「K 拍判死」互注（同构佐证） | SPEC §1 leaseTtlSec 注释 | C1 文档 |
| 4 | 能力登记合成键 `(deviceId, capability, scope)`、多来源不合并 | C2 DeviceCapability 登记规则 | C2 |
| 5 | Linux 腿容器执行形：工具链镜像（pin jdk21+sbt）+tar 注入 WIP+产物卷回捞 | SPEC §3.2 增 `compute exec-container` 形；东风云专属 | C2 |
| 6 | USTC 镜像仓作为计算基础镜像分发默认候选 | C2 镜像选型（作者确认权限后定） | C2 |
| 7 | triple-star 执行岛适配登记 backlog（govd 上节点，ssh 动词面升级） | 本报告 §2-B 形态图；触发条件=C1 真实作业一周数据 | C2+ |

**不采纳（3 条）**

| # | 不采纳 | 原因 |
|---|---|---|
| 1 | bridge-host.ps1 作为 Windows 腿执行器 | 无认证、绑 WSL vEthernet 本机对、闭集过窄；主线 device: 腿（HMAC+审计+relay 兜底）全面占优【亲验 bridge-host.ps1:1-16】 |
| 2 | relay 隧道替代 NebLink 传输 | 定位不同（浏览器访问路由 vs 设备执行通道）；主线双腿已在生产；禁双实现（upd:86） |
| 3 | 「actor 化」重构期待 | triple-star 不采 actor 一等机制（§1.2）；主线 ActorSystem 留在进程内，多设备并发由 delegate/租约面承担，无需新范式 |

---

## 5. 风险与开放项

**风险增量（相对 SUMMARY §8 风险表只列新增/变化项）**

| # | 风险 | 概率 | 影响 | 缓解 |
|---|---|---|---|---|
| RN1 | 东风云无 Docker/无权限/嵌套 KVM 不可用 | 中 | C2 容器腿落空 | 启用探针前置（O1）；退化路径＝tar+原生（C1 形不依赖容器） |
| RN2 | USTC 镜像仓权限/可达性未确认 | 中 | 镜像分发改道 | 备选：本地 `docker save`+ChunkTransport 推送（复用既有块通道） |
| RN3 | 执行岛适配（govd）维护成本外溢进主线 | 低 | 双线耦合 | 接缝只走 ssh 命令面闭集动词；govd 版本钉扎进能力档案（借 ADR-7 pin 比对） |
| RN4 | 把「C2 规则放置」误读为「负载均衡」导致预期落空 | 中 | 作者预期管理 | 本报告 §2-D-4 诚实命名；面板展示打分依据而非负载曲线（C3） |

**开放项（启用时逐条探针/裁定）**

- **O1** 东风云三探针：`docker info`（在位与权限）、`ls /dev/kvm`（嵌套虚拟化）、`curl <USTC-registry>/v2/`（镜像仓可达）。前置＝生产迁移完成（作者条件）。
- **O2** USTC 镜像仓作者权限与命名空间（triple-star 15 镜像已推该仓，路径已验通，但主线侧推送权限未确认）。
- **O3** star1 内核/LSM 强制档是否进 T2 基线——本报告建议**不进**（§2-D-5）；如作者要强隔离档再裁。
- **O4** 主线是否引 triple-star 代码入仓——本报告建议只借模式；任何源级复用交作者裁定（不擅裁）。

---

## 6. 证据锚点表

**triple-star 侧（亲验，直读核对）**

| 论断 | 锚点 |
|---|---|
| 两/三道开支令逐字 | docs/process/proc-branch-triple-star-2026-10-16.md:13,15,17 |
| main=效果 oracle；对拍唯一接口；内核面 oracle 不适用 | 同上 :39-41 |
| E2E 888/888、15 镜像推 USTC、relay wss 落码；未做跨设备 linkd | 同上 :120-140,163-165 |
| bridge-host 闭集白名单/零执法/无注册发现心跳/绑 WSL vEthernet | probe/bridge-host/bridge-host.ps1:1-16 |
| govd 容量读 procfs MemAvailable/Total；DEVICE-ADMIT 预演与启动同判定；guest 水位门 | probe/star-govd/src/main.rs:141,2245,2390,3178,3206 |
| 真源单点+投影；hash(sessionId) mod N；心跳 K 拍判死 | docs/design/05-distribution.md:17-31 |
| 跨机一跳=核侧发起同步请求-响应、无推送 | 同上 :56-73 |
| 能力合成键 (deviceId, capability, scope) 不合并不丢弃 | 同上 :29-30 |
| 远程设备（NebLink 对位=联邦线）排在 P4 | docs/design/replication-map.md（P4 节） |
| relay 隧道=反向隧道+Host 路由、本地只出站 | docs/design/relay-tunnel.md:5-16 |
| 克隆基线：HEAD e752ca1（2026-10-22 13:22 +0800，feat(onelook)），2099 文件 | /tmp/triple-star-ro git log |

**triple-star 侧（转引，探查腿锚点、未逐行复核）**

| 论断 | 锚点 |
|---|---|
| star-noded 为 mock；govd 为真治理核（BPF 装卸/cgroup/容量台账） | probe/star-noded/src/main.rs:1-7,47；probe/star-govd/src/main.rs:1-29 |
| 四执行形（lite/vm/cf/host）真代码及边界 | probe/star-run-{lite,vm,cf,host}/src/{main.rs,lib.rs} 各头部；star-run-host/src/backends.rs:1-2 |
| eOCI 摄入+digest+org.star.*；两档单元（轻档/每单元一 microVM） | crates/star-oci/src/lib.rs:44-60；docs/design/oci-superset.md:1-69 |
| LSM 需 star1 内核；观测档任意内核 | probe/README.md:9-13 |
| Windows=WSL2 脚手架未实跑；macOS Lima/VZ 已验证 | deploy/README.md:8-12；deploy/windows/README.md:17-21；deploy/macos/PROBE-ARM64.md:1-4 |
| 无跨机调度：driver=进程内 fair；schedd=cron 无执行器；tres 跨节点列为非目标 | crates/triple-driver/src/assembly.rs:180,353；cap/crates/schedd/src/lib.rs:1-6；docs/design/tres.md:48-72 |
| 不采 actor 一等机制；agent=OS 进程 | docs/design/sub/sub-driver-concurrency-and-distribution.md:191；docs/design/replication-map.md:23 |
| oracle 定义单点/对拍=效果等价非同构 | docs/decisions/decision-026-2026-10-12.md:22；docs/design/replication-map.md:3-4 |
| 终端联邦草案（席位粘性+租约接管+快照迁移，未裁） | docs/process/proc-star-terminal-federation-2026-10-17.md:2-33 |

**主线侧（转引自设计稿，其代码锚点以设计稿勘验为准）**

| 论断 | 锚点 |
|---|---|
| C1/C2/C3 分段与验收 | reports/20261022_194019_multi-device-compute-design__delegate-nebflow-1c1590ea/MULTI_DEVICE_ARCH_SUMMARY.md §7 |
| tar 快照选定/WIP 语义（ADR-3）；安全三级（ADR-8） | 同上 ADR 表 |
| leaseTtl=180=3×心跳 30s；nb_job_run 契约；claimEligible；安全闸 | 同目录 MULTI_DEVICE_ARCH_SPEC.md §1,§3.1,§3.3,§3.4 |
| 既有原语在位（device:双腿/ChunkTransport/NebLink/探针/回捞） | SUMMARY §3 表（RemoteExecutor/ChunkTransport/DeviceRegistry 等锚点） |

---

## 7. 方法注

机器高载（load 28 批次同晚）：读仓采用「定点 Grep/Read＋两条并行探查腿（代码模块面/设计文档面）」，亲验覆盖所有承重论断（开支令原文、oracle 定义、调度缺失面、准入机制、Windows 现状）；转引条目均已给出可复核锚点。triple-star 仓全程只读（clone 至 /tmp 隔离目录，零推送零写入）；主线仓仅新增本报告，零代码改动。

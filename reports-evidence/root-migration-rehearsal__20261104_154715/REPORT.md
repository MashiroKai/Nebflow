# B1 三层数据根 · 真机迁移演练报告（零风险副本演练）

> 批次：20261104 root-migration-rehearsal ｜ 基线 main `77afefc77`（含 B1 能力腿），演练期间 main 已前进至 `3047906d0`（对本次结论无影响，见 §9）
> 铁律落实：真根 `/Users/kaiyu/.stand` **只读**；未重启、未 kill 任何进程；凭据条目**未**进入副本（见 §2.3）。

---

## 0. 先回答最要紧的一条：「下次重启会不会动真数据」——**不会**

派单 brief 的前提（"下次重启会触发一次性迁移"）与代码事实不符，已按作者更正口径重写本节。三条独立证据：

| # | 证据 | 位置 |
|---|---|---|
| A | `RootLayout.migrate` / `RootLayout.rollback` 在 **`src/main/scala` 全树零调用点** | `data/no-wiring-proof.txt` 段 B；复验：`git grep -n "RootLayout.migrate\|RootLayout.rollback" main -- src/main/scala` 为空 |
| B | 生产面对 root 包的全部引用都是**只读**：`PathUtil.resolvedLayer` → `RootLadder.resolve`（纯读）、`AutoStartService` 与 `NeblinkModel` 读 `RootLadder.current` | `paths.scala:278-290`；`AutoStartService.scala:195`；`NeblinkModel.scala:267,290` |
| C | 六个布局物在真根**一个都不存在**（3 个标记：`layout.json` / `active-account` / `migration-report.json`；3 个目录：`accounts/` / `unauthenticated/` / `pending-claim/`）⇒ 阶梯落第 3 档 flat ⇒ `dataRoot == machineRoot` 逐字节相同 | `RootLadder.scala:97-102`；实测见 §2.1、§9 |

**推论**：今天的重启 = 与今日行为完全一致 —— 不迁移、不建 `accounts/`、不写 `layout.json`、不产生 `pending-claim/`。B1 目前只是一层能力（含只读快照接入），触发点按契约 §4.1 明确排除 boot（`RootLayout.scala:18-20`：「触发点 = 显式动作，绝不在 boot / `RootLadder.resolve` 里自动跑」）。

**但这不等于"接线后也安全"** —— 见 §5（双写判定）与 §7（接线后 checklist）。

---

## 1. 演练做了什么

在真根的副本上跑完整三条路径：正向迁移 → 启动路径（幂等性）→ 回滚；逐文件 sha256 由**独立 python 实现**核对（不用被测 JVM 的代码路径算账，避免同义反复）。

```
真根 (只读) --rsync--> 副本 /Volumes/rehearsal/root --RootLayout.migrate--> pending-claim/<ts>/
                                                \--RootLayout.rollback--> 原件恢复 flat
```

驱动：`data/RehearsalDriver.scala`（**临时测试文件，不入库**，仅调用 B1 既有公开 API，零生产逻辑改动）。

---

## 2. 环境与副本口径

### 2.1 真根实测（before 指纹，14:12-14:18）

| 指标 | 值 |
|---|---|
| 对象数（文件+目录+链接） | 1,401,898 |
| 普通文件数 | 1,274,665 |
| 内容字节 | 93,638,744,539 B（93.6 GB / 87.2 GiB） |
| 顶层条目 | **159**（82 目录 / 76 文件 / 1 符号链接 `jdk-21`） |
| 布局物是否已在真根 | `accounts` / `unauthenticated` / `pending-claim` / `active-account` / `layout.json` / `migration-report.json` —— **六者全缺** |

⇒ 若在此机器触发 `migrate`，被搬的顶层条目 = **全部 159 个**（`RootLayout.scala:76-80` 只排除六个布局物）。

### 2.2 副本

| 指标 | 值 |
|---|---|
| 位置 | `/Volumes/rehearsal`（APFS sparse bundle，宿主 `/Volumes/KINGSTON` 外置盘） |
| 体量 | 42 GB ／ 556,904 文件 ／ **105 个顶层条目（105/105 全在，零缺条目）** |
| 耗时 | 1002 s（16.7 min） |
| rsync 退出码 | 1 —— 5 条 `sessions/` 活文件在读取瞬间被运行中的生产实例改写/删除（live churn，预期内），不是结构性失败 |

**为什么用外置盘做副本**：内部盘当前 **11 GiB 空闲 / 99% 占用**，放不下 42 GB 副本，更放不下「副本 + 迁移产物」双份；把内部盘写满会直接危及正在运行的生产实例（pid 78176）。因此副本落在 110 GiB 的 APFS 卷（sparse bundle 寄宿于外置盘），**保留 POSIX 权限与符号链接语义**，`migrate()` 看到的文件系统语义与真机一致。

### 2.3 副本没有包含什么（诚实标注，影响面已评估）

**(a) 整条目排除（54 个顶层条目，全部是凭据/配置/存活物）** —— 凭据卫生铁律要求不把 token/key 落到我新建的任何文件里，而副本正是这样的文件；`nebflow.json` 经只读扫描含 5 处 key 形态字段，故一并排除：
`secrets/`、`lt-secrets/`、`secrets-catalog.md`、`auth.json`、`device.json`、`client-id`、`feishu.json`、`gateway-sessions.json`、`.seed-state.json`、`nebflow.json(+.bak*)`、`models.json`、`model-chains.json`、`model-presets.json*`、`*.env`（8 个）、`mcp/`、`plugin-data/`、`.pid`、`.pid.meta`、`nebflow.pid`、`run/`、`shutdown-marker.json`、`daemons.json(+.bak*)`、`install-manifest.json.bak*`、`.DS_Store`、`.git`、`.gitignore`

影响面：`migrate` 对这些条目**没有特殊分支**（都走同一条 `copyTree`），排除它们不改变被测逻辑；但意味着「真机迁移会把它们一并复制进账号根」这件事是**代码事实**而非本次实测。

**(b) 子目录剪枝（体积，非逻辑）**：`node_modules`、`worktrees`、`.git`、`target`、`.vercel`、`.next`、`.turbo`、`playwright-report`、`test-results`、`.cache` 任意层级。105 个顶层**条目**仍在，只是其内部这些叶子目录不含。

因此 sha256 结论的覆盖域 = **副本里实际存在的 556,904 个文件，逐条**（不是抽样）。

---

## 3. 演练读数

### 3.1 正向迁移

副本先去风险（移除 19 条断链/越界链接，见 §4-F2/F3），因此迁移对象 = **104 个顶层条目**（105 − `jdk-21`）。

| 项 | 读数 |
|---|---|
| 迁移字节 / 源文件 | **40.57 GiB**（43,546,868,993 B）／556,904 个文件 |
| `migrate` 耗时 | **1119.3 s（18.7 min）**（wall 1121 s） |
| 吞吐 | **38.9 MB/s、497 文件/s**（写侧是外置盘 sparse bundle，见 §8 外推） |
| 落点 | `pending-claim/20261104-150021/` —— 无账号在 ⇒ 待认领区，符合契约 §4.2 |
| `migration-report.json` | **109.2 MiB**，`entries` 104 条 + `files` **556,912** 条 |
| `files` 比源文件多 8 条 | 8 条**根内符号链接被解引用成实体文件**（§3.4 的证明） |
| `layout.json` | 已写 ✔ |
| 指针 | **未写** ✔（待认领分岔不写指针，契约 §4.2） |
| 迁移后启动路径 | `RootLadder.resolve` ⇒ **`unauthenticated`**（0.0 s，纯读），有效根 = `…/unauthenticated` |

断言（`logs/06-migrate.log`，全 PASS）：`movedEntries` 覆盖迁移前全部 104 个顶层条目；报告落机器层；不写指针；无账号时不建 `accounts/`。

### 3.2 幂等（「再跑一次启动路径」）

| 判据 | 读数 |
|---|---|
| 启动路径是否重复迁移 | **否**。启动序只调 `RootLadder.resolve`（纯读），实测 `pending-claim/` 下**始终只有 1 个** 目录（`dirs=ArraySeq(20261104-150021)`） |
| 是否产生第二份副本 | **否** |
| 指针是否被改动 | **否**（迁移前后都无指针） |

但**API 层面没有幂等守卫** —— 见 §4-F1：直接连调两次 `migrate` ⇒ 两份完整副本（合成根实测 `pending-claim/20261104-142003` 与 `-142004`）。「启动路径幂等」成立靠的是「boot 根本不调 migrate」这条代码事实，而不是 migrate 自身的守卫。

### 3.3 回滚

| 项 | 读数 |
|---|---|
| `rollback` 耗时 | **893.4 s（14.9 min）** |
| 校验面 | **556,912 个副本逐条 sha256**（先全部校验一致才动手删——不一致会拒删并点名） |
| 副本 | 全删 ✔ |
| `pending-claim/<ts>` 容器 | 删除 ✔ |
| `layout.json` | 删除 ✔ |
| 指针 | 无（本就未写）✔ |
| 阶梯 | 回滚后 `flat`，有效根 = 机器根 ✔ |
| 原件 | **逐条不变**（§3.4-c）✔ |
| `migration-report.json` | **保留**（审计留痕，符合实现注释「report kept」） |

### 3.4 sha256 逐条一致结论

**(a) 逐文件（权威面）** —— `logs/06-compare.log`：

```
[cmp] report files=556912
[cmp] file digest mismatches: 0
[cmp] unlisted files in target: 0
```

556,912 条记录逐条重算 sha256 与产物文件比对：**0 条不一致、0 条缺失**；同时反向扫描产物目录：**0 个未登记文件**（双向完整）。这一步由独立 python 实现（`02-compare.py`）完成，不使用被测 JVM 的摘要代码。

**(b) 条目级** —— `logs/07-entry-digest.log`：用 `RootLayout` 自己的方案（`RootLayout.scala:99-102`：文件条目 = 文件自身 sha256；目录条目 = sha256(排序后的 `path:sha256` 行)）重算 104 条：

```
[entry] report entries=104 matched=102 explained(symlink-bearing)=2 unexplained=0
```

两条差异（`backups`、`projects`）已**证明**为符号链接解引用，不是数据错：

| 条目 | 实测字节差 | 8 条被解引用链接的目标字节之和 |
|---|---|---|
| `projects` | 19,116,619,468 − 19,103,139,000 = **13,480,468** | 2,879,512+3,844,296+3,844,296+2,879,512+1,942+6,680+24,230 = **13,480,468** |
| `backups` | +1,942 | `Agent.md -> ../AGENTS.md`（1,942 B） |

逐个核对（`logs/` 内已留痕）：8 条链接在 `migration-report.json` 里都变成了**同名实体文件**记录，sha256 = 目标文件的内容摘要。这正是 §4-F2 的行为在真数据上的体现。

> 诚实留痕：`logs/06-compare.log` 的**条目级**段落当时报了 37 条不符，经查**全部**是我方聚合器（`fingerprint.py --aggregate`）的边界缺口 —— 它把顶层**文件条目**也按「路径:摘要」行哈希（而实现是文件自身摘要）、把**空目录条目**记成 `n/a`（实现是空串摘要）。同一份日志里**逐文件面当时就是 0 不符**。修正后的条目级对照器是 `07-entry-digest.py`（上表读数即由它产出）。这是检查工具的问题，不是 `RootLayout` 的问题。

**(c) 原件不变（两次独立验证）**

| 场景 | 比对 | 结果 |
|---|---|---|
| 中断跑（phase A，未去风险副本） | 基线 vs 中断后 | `changed=0 added=0 removed=0`，仅 2,577 个受管路径（半截副本）出现 —— **PASS** |
| 完整跑 → 回滚（phase B） | 基线 vs 回滚后 | `changed=0 added=0 removed=0`，仅 `migration-report.json` 出现 —— **PASS** |

---

## 4. 风险清单（按严重度排序）

### F1（严重 · 接线前必须解决）迁移**没有幂等守卫**
`RootLayout.migrate` 从不检查 `layout.json` / 已有 `pending-claim/` / 已有 `accounts/`（`RootLayout.scala:74-90` 只有"列条目→挑目标→复制"）。实测 S1：对同一根连调两次 ⇒ `pending-claim/20261104-142003/` 与 `pending-claim/20261104-142004/` **两份完整副本**。
更危险的推论（接线后）：账号根已在用数日、原件仍在机器根（复制语义！）。此时若用户 `stand logout` → 阶梯落 `unauthenticated`（`RootLadder.scala:99-102`：有 `layout.json` + 无指针）→ 用户再 `login`；若登录腿**无条件**调 `migrate` ⇒ 用**陈旧的 flat 原件快照覆盖回账号根**（`copyTree` 用 `os.write.over`）⇒ **用旧数据覆盖新数据**。
接线要求：登录腿必须先判「本机是否已迁移过」（`layout.json` 存在 / 指针存在 / `accounts/` 非空即跳过），或把 migrate 收窄为一次性 `--first-login-only` 动作。

### F2（严重）迁移**不保留符号链接**，且会崩溃或越界取材
`copyTree` 用 `os.isDir` / `os.read.bytes`，二者**跟随**链接（`RootLayout.scala:221-233`）。实测（S4，合成根）：
| 链接形态 | migrate 行为 |
|---|---|
| 断链（目标不存在） | 抛 `NoSuchFileException`，**中止** |
| 自指/环 | 抛 `FileSystemException: Too many levels of symbolic links`，中止 |
| 指向 unix socket | 抛 `FileSystemException: Operation not supported on socket`，中止 |
| 指向根**外**的普通文件 | **成功**，把根外文件的字节复制进数据根（`/etc/hosts` 被复制成账号根里的普通文件） |
| 指向根内 | 成功，链接变成目标内容的一份**实体副本**（目录链接 ⇒ 整棵子树重复复制） |

### F3（严重 · 真机特定）真根今天就有 19 条断链 ⇒ **真机迁移必然中途失败**
真根全树 2,500 条符号链接实测（清单见 `data/real-root-symlink-census.json`）：**断链 19**、越界 39、根内可解析 2,442、socket 0。

19 条断链（真机迁移会在**第一条**上中止；副本上实测命中的正是第 19 条 `backups/…/Agent.md`）：
| # | 位置 | 指向 |
|---|---|---|
| 1-5 | `projects/stand-website/.nebflow/evidence/20261017_stand-favicon-pure/prod/shots/profile-light-yG2aN4/{RunningChromeVersion,SingletonSocket,SingletonCookie,SingletonLock,node_modules}` | Chrome profile 残留（`156.0.8078.4:1`、已消失的 `/var/folders/…`、`node_modules→~/.nebflow` 旧品牌路径） |
| 6-10 | `projects/stand-website/.nebflow/{worktrees/site-main,worktrees/login-social-quick,reports/evidence/prod-*}/…` | 旧品牌 `~/.nebflow/…` 路径（已不存在） |
| 11-13 | `projects/link-server/.nebflow/{evidence,scratch}/…` | `~/.nebflow/projects/neblink-server/…`、work-env node_modules |
| 14-18 | `browser-data/RunningChromeVersion`、`browser-use/profiles/website-staging/{RunningChromeVersion,SingletonSocket,SingletonCookie,SingletonLock}` | Chrome 版本戳 / 已消失的临时目录 socket |
| 19 | `backups/pre-migrate-20261010T100730/projects/slideblocks/.nebflow/Agent.md` | `../AGENTS.md`（备份树自身的悬空相对链接） |

**演练实测**：在副本上（剪枝后仍有 14 条断链）`migrate` 在复制到第 206 MB 时抛出
`java.nio.file.NoSuchFileException: …/backups/pre-migrate-20261010T100730/projects/slideblocks/.nebflow/Agent.md`
（`RootLayout.scala:226` → `os.read.bytes`），**中止**。真机同样会中止，只是命中顺序可能不同。

⇒ 真机要迁移，**必须先清掉这 19 条断链**：
```
find ~/.stand -type l ! -exec test -e {} \; -print    # 列出全部断链（读操作）
```
（处置：删除，或把指向旧品牌 `~/.nebflow/…` 的链接改成新路径。）

### F4（严重 · 真机特定）磁盘空间不够 = 迁移必然 ENOSPC
复制语义 ⇒ 需要 **≥ 1× 数据量**的空闲空间（原件全部保留 + 副本新增）。真根内容 93.6 GB，当前空闲 **11 GiB**。中途 ENOSPC 的后果见 §5.1（contained：不写 `layout.json` ⇒ 仍是 flat ⇒ 不丢数据；但**已写入的部分副本会继续占盘**，可能把系统盘吃满，进而拖垮正在运行的生产实例）。

### F5（中）`rollback` 无条件清指针
`rollback` 末尾 `AccountRoot.clearPointer` 不看指针是否由本次迁移写入（`RootLayout.scala:185`）。实测 S3：迁移前就存在的指针也被清掉 ⇒ 「回滚」顺带把用户登出。
要求：rollback 只在「本次 migrate 写了指针」时清（当前 migrate 根本不写指针，所以这个清动作在 pending-claim 分岔下是多余的、在带指针分岔下是有害的）。

### F6（中）`logout` 之后的阶梯不是 flat
有 `layout.json` + 无指针 ⇒ 第 2 档 `unauthenticated`，有效根变成 `machineRoot/unauthenticated`（**空目录**）。原件全都还在机器根上，但对外"看不见了"。若此时有代码往 `unauthenticated/` 写（登录面），用户观感是"数据丢了"。
要求：登出面文档化说明，或让未登录层只读。

### F7（中）`migration-report.json` 在真机规模下体积与解析成本
逐文件记账（`files[]`）：真机 1,274,665 条记录 ⇒ 数十万到数百 MB 的 JSON；`AtomicJson` 原子写（tmp+rename）峰值翻倍；`rollback` 用 circe 整份 parse 进内存（`readManifest`，`RootLayout.scala:242-267`）。
本次副本 556,904 文件实测见 §3.1/§3.3 的记账文件大小与耗时。

### F8（低）`/api/health.rootLayer` 未接线
`rootLayer` 只出现在注释（`RootLadder.scala:31,36`），HTTP 面无实现 ⇒ 重启后只能靠文件系统判定档位（§6 给了命令）。

---

## 5. 迁移时机 · 双写 · 句柄失效 · 写丢失（本批最重要一条）

### 5.1 迁移发生在引擎启动的哪个阶段？—— **今天：不在任何阶段**
`Main` / `PathUtil` 启动序里没有任何 `migrate` 调用（§0 证据 A/B）。启动序只做一次**纯读**定档：`PathUtil.resolvedLayer` → `RootLadder.resolve`（`paths.scala:278-290`，`RootLadder.scala:84-102` 全部是 `os.exists`/内容读）。因此：
- 今天的重启：**零迁移、零写盘**（`resolve` 不建目录、不写标记）。
- 契约里的位置（§4.1）：迁移属**登录腿**（"平铺机器首次登录" ⇒ 先 migrate 再写指针），是显式动作。

### 5.2 接线后：旧实例仍在跑同一根，新实例启动即迁移 —— 存在吗？
代码事实：`LoginGuard.checkLoginAllowed` 在**机器层无指针时直接放行**（`Guards.scala:66-67`：`case None => IO.pure(Right(()))`），**不看**端口上有没有活实例。而"平铺机器第一次登录"恰恰就是无指针状态 ⇒ **登录/迁移可以与正在运行的旧实例重叠**。

三个失效面逐一判定：

| 面 | 判定 | 依据 |
|---|---|---|
| **双写** | **不成立**（不会有同一文件被两个写入者交错写） | 复制不是移动：旧实例继续写**原件**，副本是独立文件；`copyTree` 只写 `dst`，源只读 |
| **句柄失效** | **不成立** | `copyTree` 每文件 `os.read.bytes`→`os.write.over` 打开即关，不重命名/不移动任何原件，旧进程的 fd 与锁不受影响 |
| **写丢失** | **有条件成立（真风险）** | 迁移期间的窗口：旧实例持续往 flat 原件写（会话、任务账本、记忆、mailbox），而这些写入**不在副本里**。指针写上 + 重启后，新进程服务账号根 ⇒ 窗口内的写入在生效根上"消失"（原件仍在，但不是当前生效根）⇒ 用户看到的是"重启丢了最近一段时间的数据" |
| 附加：**读到撕裂文件** | 成立 | 引擎正在写的 JSON 账本被 `os.read.bytes` 读取时可能是半截 ⇒ 副本里留下不可解析的文件（本次副本里就有 5 个 `sessions/` 活文件因此落到异常路径） |

### 5.3 规避次序（接线批必须写进流程）

> **先停旧实例 → 再触发登录/迁移 → 校验 → 再起新实例。迁移期间不得有任何引擎进程持有该根。**

理由：从上表，唯一真正的数据风险是「迁移窗口内的写入不进副本」。只要窗口内没有旧实例在写，窗口就消失；句柄与双写面本来就不成立，所以只需一条时序约束即可闭合。

落地方式（三选一，建议 ②）：
1. 让 `LoginGuard.checkLoginAllowed` 在「端口上有本品牌活实例」时**无条件拒绝**登录（现有实现只在账号不同时拒）；
2. 迁移腿自己先探活：`SingleInstanceGuard.checkPort(...)` 命中 `NebflowInstance` ⇒ 拒绝迁移并提示「先 `stand stop`」；
3. 迁移前对机器根加排他锁（`run/` 下），引擎启动时争锁失败即拒启。

### 5.4 失败容错（实测，令人放心的一条）
`migrate` 的写序是**先复制、最后才写 `layout.json`**（`RootLayout.scala:93-111`）。因此任何复制期异常（断链、ENOSPC、撕裂读、被 kill）= **不写 `layout.json`** ⇒ 下次启动仍解析为 flat ⇒ 原件照旧服务，**不丢数据**。代价是：副本残留（可能占满磁盘，见 F4）+ 无 `migration-report.json` ⇒ **无法用 rollback 自动清理**（rollback 需要 manifest），只能人工 `rm -rf` 目标目录。

---

## 6. Checklist A —— 「今天就能重启」（当前未接线状态，不会触发迁移）

照做即可，逐条都有判据。

1. **重启前记录基线**（30 秒）
   ```
   ls -A ~/.stand | grep -E '^(accounts|unauthenticated|pending-claim|active-account|layout\.json|migration-report\.json)$'
   ```
   **期望：无输出**（六个布局物都不存在）。有输出 ⇒ 停手，说明已发生迁移，别重启，先回来。
2. **用官方工具重启**：`ops/stand-restart.sh`（v4.4）。三条使用纪律（脚本头注）：
   - **必须以路径直接调用**（例 `~/.stand/bin/stand-restart.sh --hot`），禁止 `source`/`eval`；
   - **裸调用 = 只检测不动作**（`--check`），任何重启动作都要显式指定模式；
   - 它自带「sbt 编译预检（编不过就不动旧实例）+ 隔离 canary」护栏链 —— 这正是"绝不杀了起不来"的保障。
   除本脚本外，**不要**对 sbt/java/nebflow 进程 `kill`/`pkill`/发信号（本演练全程未做过）。
3. **启动引擎**（照常）。
4. **重启后立即核验三条**：
   ```
   # (a) 布局物仍然一个都没有 ⇒ 没有发生迁移
   ls -A ~/.stand | grep -E '^(accounts|unauthenticated|pending-claim|active-account|layout\.json|migration-report\.json)$'
   # (b) 数据仍在 flat 位置（抽查）
   ls ~/.stand/projects | head -3 ; ls ~/.stand/tasks-v2.json
   # (c) 引擎确实在跑
   ps -p <pid> -o pid,etime,command
   ```
   **期望**：(a) 无输出；(b) 项目/账本照旧；(c) 新进程活着。
5. **（可选）日志抽查**：不应出现 `[migrate]`、`ROOT-POINTER-DAMAGED`、`UNAUTHENTICATED-LAYER` 行。若出现 `[migrate]` ⇒ 说明有别的批把迁移接线合进来了，按 §5.4 的容错口径核（不写 `layout.json` ⇒ 仍是 flat），并清掉 `pending-claim/` 残片后再回来复核本 checklist。

> 判据总结：**重启前后 `ls` 的六个布局物都必须为空，且 `~/.stand/projects` 内容不变** ⇒ 证明零行为变化。

---

## 7. Checklist B —— 「接线批完成后」的重启/迁移流程（**待接线批完成后适用**）

前置条件（缺一不可，否则不要开始）：
- [ ] P1 磁盘空闲 ≥ **1.1 × 数据量**（今天 93.6 GB ⇒ 需 ~103 GB 空闲；当前只有 11 GiB ⇒ **现在就不满足**）
- [ ] P2 F3 的 19 条断链已处理（删除或修复）：`find ~/.stand -type l ! -exec test -e {} \; -print`
- [ ] P3 越界链接（39 条）已决定处置（保留=接受把根外内容复制进根；删除=更干净）
- [ ] P4 接线批已加入「迁移期无活实例」闸（§5.3 的 ①②③ 任一）
- [ ] P5 账本已备份（`tar` 到外部盘：`tasks-v2.json`、`memory/`、`sessions/`、`projects/*/project.json`）

执行次序（顺序即安全边界）：
1. **停止旧实例**：用 `stand stop`（`CliRouter` 的 stop 命令；需要时 `stand stop --force`）。**注意** `ops/stand-restart.sh` 没有「只停不起」模式（其模式只有 `check|hot|canary|watch|force|wait-idle|restart-if-idle|preflight-only`），所以这一步不要拿它凑。
   然后确认端口上无本品牌实例：
   ```
   lsof -nP -iTCP:<port> -sTCP:LISTEN    # 期望：无输出
   ```
2. **记录迁移前指纹**（可选但强烈建议，用于事后逐条核对）
   ```
   python3 reports-evidence/root-migration-rehearsal__20261104_154715/fingerprint.py ~/.stand \
           --out /Volumes/<外部盘>/real-before.tsv
   ```
3. **触发首次登录**（登录腿内先 migrate 再写指针）。**此步期间不要启动引擎**。
4. **校验迁移产物**（四条一起看）：
   ```
   ls -l ~/.stand/migration-report.json          # 存在
   cat ~/.stand/layout.json                      # {"layout":"accounts-v1"}
   ls ~/.stand/pending-claim   或者   ls ~/.stand/accounts   # 二选一存在
   jq '.entries|length, (.files|length)' ~/.stand/migration-report.json   # 与预期条目数/文件数比对
   ```
   再用 `02-compare.py` 逐条核对（把 `migration-report.json`、迁移前指纹、目标目录喂给它）——**必须 PASS**（每条 sha256 一致）。
5. **确认原件仍在**（作者裁定：复制不是移动）：
   ```
   ls ~/.stand/projects | head -3 ; ls ~/.stand/tasks-v2.json
   ```
   期望：与迁移前完全一致。
6. **启动新实例**，核验生效根：
   ```
   ps -p <newpid> -o pid,command        # 应带账号根或机器根
   ls -A ~/.stand | grep -E '^(active-account)$'   # 指针在（已登录）
   ```
   （接线批合入 `/api/health.rootLayer` 后，改用 `curl -s localhost:<port>/api/health | jq .rootLayer`，期望 `"account"`。）
7. **观察 10 分钟**：日志无异常、`~/.stand/logs/` 不再增长（日志已迁到账号根），会话/任务账本可正常写入账号根。
8. **失败处置**：
   - 迁移期异常（断链/ENOSPC/被 kill）⇒ 见 §5.4：不写 `layout.json` ⇒ 仍是 flat；人工清 `pending-claim/<ts>/` 或 `accounts/<dir>/` 里的残片，然后回来修前置条件（P1/P2）。
   - 迁移成功但想退回 ⇒ `RootLayout.rollback`（逐条验副本 sha256 → 删副本 → 清指针 → 删 `layout.json`），或手工：
     ```
     rm -rf ~/.stand/pending-claim          # 或 accounts/<dir>/<迁移进来的条目>
     rm -f  ~/.stand/layout.json ~/.stand/active-account
     ```
     **原件不动，所以退回只删副本，绝不删原件。**

---

## 8. 真机预期行为（一句话版）

| 问题 | 答案 |
|---|---|
| **迁哪些** | 顶层条目**全部 159 个**（`RootLayout.scala:76-80` 只排除六个布局物，今天六者皆无） |
| **迁到哪** | 有账号 ⇒ `~/.stand/accounts/<dirNameOf(accountSub)>/`；无账号（**今天就是这种**）⇒ `~/.stand/pending-claim/<yyyyMMdd-HHmmss>/` |
| **原件的命运** | 一个字节不动（复制不是移动）⇒ 可回滚 |
| **多久** | 副本实测：`migrate` **1119 s**（40.57 GiB / 556,904 文件，写侧外置盘 38.9 MB/s）。外推到真机（93.6 GB / 1,274,665 文件，×2.15 按字节、×2.29 按文件数）⇒ **约 40–45 min**，这是**上界**（外置卷吞吐比内部 SSD 低）；下界约 10–15 min（真根全量「只读+哈希」实测 5 min，加内部盘写入）。回滚同法外推 **约 30–35 min**（实测 893 s） |
| **要多少磁盘** | 副本 ≈ 93.6 GB ⇒ 需 ≥ 103 GB 空闲（今天 11 GiB ⇒ **不满足**） |
| **会不会崩** | 会：19 条断链 ⇒ `NoSuchFileException` 中止（不丢数据，见 §5.4） |
| **怎么退** | `RootLayout.rollback`（验副本 sha256→删副本→清指针→删 `layout.json`），或手工删副本目录 + `layout.json` |
| **迁移后阶梯** | 有指针 ⇒ `account`；无指针 ⇒ `unauthenticated`（**不是** flat） |

---

## 9. 真根零改动证明

方法：演练**开始前**（14:12–14:18）与**真根读取全部结束后**（14:47–14:52）各做一次真根**全量**指纹（每个文件读全字节算 sha256），两侧逐条比对；演练收尾时（**15:45:46**）再做一次定点复核（`rehearsal-evidence/final-realroot-checkpoint.txt`）。脚本 `05-realroot-diff.py`，输出 `logs/08-realroot-diff.log`。

> 时间口径：两次全量指纹之间的 15:00–15:19 是**副本**上的 `migrate` 窗口 —— 那段时间的写入全部发生在 `/Volumes/rehearsal`，真根没有再被写过（15:45 的定点复核六项仍全为空，可证）。

| 读点 | 对象数 | 文件数 | 内容字节 |
|---|---|---|---|
| before | 1,401,898 | 1,274,665 | 93,638,744,539 |
| after | 1,402,990 | 1,275,637 | 93,724,772,562 |

### 9.1 核心判据：真根上没有发生过任何迁移

```
[real] (a) layout-managed paths present in the AFTER snapshot: 0 []
```
六个布局物（`accounts` / `unauthenticated` / `pending-claim` / `active-account` / `layout.json` / `migration-report.json`）在演练结束后的真根里**一个都没有出现** ⇒ 真根上不存在任何迁移产物。这一条本身就是「我没在真根上跑过 migrate」的充分证据 —— 迁移只要跑到中途就会留下 `pending-claim/`，只要跑完就会留下 `layout.json`；两者都没有。

### 9.2 差异归属（真根有差异，但每一条都能归到**别的**并发主体，不是我的动作）

演练窗口内真根确实有变动：新增 1,250、消失 158、内容变化 220 个对象。逐条归类：

| 顶层条目 | 变动对象 | 归属（时间戳为证） |
|---|---|---|
| `history` / `sessions` / `bg-task-logs` / `tmp` / `logs` / `tool-results` / `chains` / `delegates` / `subagent-tasks` / `cache` / `memory-db` | 1,228 | **生产实例 pid 78176 自己的持续写入**（会话、历史、日志、后台任务）—— 我在演练期间恰好是它的下游 |
| `projects/*/.git/objects/…`（347） | 347 | **其它并发 delegate 会话的提交**（git 对象/index/HEAD，不含工作树源码） |
| `plugin-repos/stand-dev/{.git,Plugin.md,plugin.json,SKILL.md}`、`plugins/{stand-dev,website}/…`（31） | 31 | **并发会话的插件同步**（14:16–14:20，同期 main 上落下 seed 插件改动，有 [base-moved] 通知为证） |
| `merge-quarantine/Stand/20261104_141527/…`（12）、`merge-channel/*.json`（8） | 20 | **并发会话的合并队列/隔离区**（14:15–14:47，同期 main 前进了 6 次合并） |
| `bin/sbt` + `bin/sbt.bak-20261104-143858`（2） | 2 | **sbt-gate 包装器自更新**（14:38:58；`bin/sbt` 现指向 `~/.local/share/nebflow-local/bin/sbt-gate`）。我在 14:30 之后没有再调用 sbt |

### 9.3 非活跃条目的逐字节比对（最强的一条）

把上述「引擎/并发主体会写」的条目与六个布局物排除后，对真根里**其余全部文件**做逐条 sha256 比对：

```
[real] (c) non-live files compared=1046175 byte-identical=1046067 mismatched=108
```

108 条不一致 **全部**落上表的并发主体条目内（`bin/sbt`、`plugin-repos/stand-dev/*`、`plugins/*`），**没有任何一条**落在数据面（`projects/*` 的非 git 内容、`memory/`、`tasks-v2.json`、`secrets/`、`docs/`、`agents/`、`skills/` 等）上。

### 9.4 我的写入面（只有这三处）

| 位置 | 用途 |
|---|---|
| `.nebflow/worktrees/delegate-nebflow-24f88015/` | 本批证据与报告（git 分支内） |
| `/Volumes/rehearsal/` | 副本与迁移产物 |
| `/Volumes/KINGSTON/rehearsal-evidence/` | 大件清单（TSV/JSON，仓库外，避免把 1.4M 行清单塞进 git） |

结论：**真根零改动成立** —— 没有迁移产物、数据面逐字节未变、所有差异可归因于并发主体。演练全程未重启、未 kill 任何进程（生产实例 pid 78176 在收尾时已连续运行 03:03:32），`~/.stand/run` 下的锁与 pid 文件未被触碰（`run/sbt.lock` mtime 仍是 13:32，早于本次演练）。

收尾定点复核（15:45:46，全部为「不存在」）：

```
checkpoint at 2026-11-04 15:45:46
absent  accounts
absent  unauthenticated
absent  pending-claim
absent  active-account
absent  layout.json
absent  migration-report.json
top-level entries: 159
pid 78176: 03:03:32 uptime
```

---

## 10. 未做 / 假设 / 与派单的偏差

**未做**
- 没有在真根上跑 migrate / rollback / 写指针 / 建 accounts（铁律）。
- 没有重启、没有 kill 任何进程；`~/.stand/run` 下的锁与 pid 未被读取以外的任何操作触碰。
- F1-F8 **一个都没修**（按派单：发现风险只带回，不动手）。

**遗留在盘上的东西（作者可自行清理）**
- `/Volumes/KINGSTON/nebflow-root-rehearsal.sparsebundle` —— 副本卷，**已卸载，占 87 GB**；删除命令见 `README.md`。
- `/Volumes/KINGSTON/rehearsal-evidence/` —— 大件清单（真根前后全量指纹、副本各阶段指纹、`migration-report.sample.json` 109 MB 等，约 1.7 GB）；删除前建议先按 §9 复核真根。
- 内部盘没有留下任何东西（`/tmp` 未使用）。

**与 brief 的偏差（都已在上文标注理由）**
1. brief 说"下次重启会触发一次性迁移" —— 与代码不符，作者已发更正；本报告按更正口径写（§0）。
2. brief 建议 `/tmp/root-migration-rehearsal-<ts>/` —— 内部盘仅 11 GiB 空闲/99% 占用，放不下且会危及生产实例；改用外置盘上的 110 GiB APFS sparse bundle（§2.2）。
3. brief 允许排除 `worktrees/`、`node_modules/`、`logs/`、`shell-spill/`、`memory-db/` —— 实际按代码事实（`RootLayout.managedNames`）判定"迁移对象 = 全部顶层条目"，因此**保留全部 105 个可复制顶层条目**，只在条目内部剪枝体积型叶子目录；另外为凭据卫生排除了 54 个凭据/存活类条目（§2.3）。
4. 报告落在 `reports-evidence/`（派单指定的台账/报告位），**没有**落到 `.stand/docs` 项目 vault —— 因为后者位于真根内部，写入即违反"真根零改动"铁律。

**关键假设**
- 副本的 filesystem 语义（APFS：POSIX 权限 + 符号链接）与真根一致 ⇒ 迁移逻辑读数可直接外推。
- 副本的写入侧（外置盘 sparse bundle）比真机内部 SSD 慢 ⇒ §3 的耗时是真机耗时的**上界**。
- 演练期间生产实例正常运行 ⇒ 副本含少量活文件窗口差异（rsync exit=1），不影响逻辑结论。

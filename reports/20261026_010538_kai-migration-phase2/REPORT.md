# KAI 迁移批·第二阶段：构建主线正式切换

批次：20261026_010538_kai-migration-phase2
作者令：00:55「把STAND的开发转移到Kai上去」（第一阶段基线 20261026_002700，jar 流转工具链已演练就绪）
状态：**切换完成**——KAI 增量同步 / KAI 正式出包 / Mac 构建位真替换 / 运转约定写死（ops/README.md）四步全落地。**在产 daemon（pid 72160）与 6138 全程零触碰**，新包下次 stop/start 生效。

## 1. 新基线（写死）

- **切换基线 = `38b81eb4fd890ec673f8b401596adf3536f4d8e9`**（Mac 本地 main HEAD，2026-10-26 00:56 读数；含今晚回执去重 `815cad90c` + phase-1 落库 `d4f490b56` 等 6 commit）。
- origin/main 落后本地 78 commit（本地 main 为权威，同 phase-1 口径）。
- KAI 同步前 HEAD = `86e67a91f`（phase-1 基线，基线的祖先 ⇒ 快进可行，`merge-base --is-ancestor` 预核通过）。

## 2. KAI 增量同步（bundle 通道，phase-1 同款）

- 薄包：`git bundle create /tmp/kai-mig-p2.bundle 86e67a91f..main` → **36,247 B**（6 commit / 14 files）→ `scp` → `C:/Users/Kai/kai-phase2/kai-mig-p2.bundle`。
- KAI 侧：`git fetch <bundle> main` + `git merge --ff-only FETCH_HEAD` → **`86e67a91f` → `38b81eb4f` 快进落位，零冲突**（930 insertions / 72 deletions；变更面：receipt-dedup 修复 + phase-1 ops 工具入库 + 两批报告）。
- 同步后 `git status` 干净（仅既有未跟踪 `reports-kai/`）。`.sbtrc` 毒文件隔离延续（home/仓内无活跃 `.sbtrc`）。

## 3. KAI 正式出包（@38b81eb4f）

单 sbt 会话 `SBT_OPTS=-Xmx4g sbt -batch`：compile → testOnly(5 spec) → assembly；日志与心跳落 `C:\Users\Kai\kai-phase2\{p2-build,p2-heartbeat,p2-assembly}.log`（心跳 30s 落盘，全称活性可查）。

- **compile**：绿（增量，自 86e67a91f 构建态）。
- **三代表族快验：Total 83 / Failed 4 / Passed 79**——
  - `ImageGenSpec` **20/20 全绿**；
  - **agent 三锚全绿**：`RootSixBaseToolsSpec` 2/2、`FriendsSealSpec` 10/10、`Phase2dToolRefactorSpec` 17/17；
  - `PerceptionCoreSpec` **30/34，4 红**（复测见 §5）。
  - 算术闭合：20+10+2+17+30 = 79 ✓（与 phase-1 @86e67a91f 完全同读数——6 个新 commit 零测试面扰动；其变更文件亦不含 perception 路径）。
- **assembly**（testOnly 红按 sbt -batch fail-fast 短路，单独补跑）：**EXIT=0，11s**（01:02:32）。
- **产物**：`C:/Users/Kai/stand/target/scala-3.5.2/stand-assembly-2026.10.21-beta.2.jar`，**106,360,135 B**，
  **SHA256 = `fa4590112ff45abb21b1c9b23c31da32a68d388608364b9ccd5bd56f09375b7b`**（certutil；sbt 侧另报 "Jar hash 278cdedb…" 为 sbt 内部口径，对账以 certutil↔shasum 为准）。版名来自仓 VERSION（2026.10.21-beta.2，未 bump——版名与旧 Mac 包同名，见 §4 备份机制消化）。

## 4. Mac 真替换（`ops/bin/stand-jar-pull` 正式跑，01:03:57）

**前后对照表**：

| 项 | 替换前（01:02 只读对照） | 替换后（01:04 复核） |
|---|---|---|
| current-version | `2026.10.25-dev.8ae0e5317` | **`2026.10.21-beta.2`** |
| previous-version | `2026.9.19` | **`2026.10.25-dev.8ae0e5317`** |
| 指针解析 | `…/versions/2026.10.25-dev.8ae0e5317/stand-assembly-2026.10.25-dev.8ae0e5317.jar` | **`…/versions/2026.10.21-beta.2/stand-assembly-2026.10.21-beta.2.jar`** |
| `2026.10.21-beta.2` 位 jar | 旧 Mac 构建（10-22），110,274,860 B，SHA `502fcf0495…c7661` | **KAI 新包**，106,360,135 B，**SHA `fa4590112f…75b7b`** |

- **流程全走通**：`--verify` 零写入预检（KAI SHA 与 Mac 独立 certutil 读数预比对一致）→ scp 下载至同卷 `.part` → **SHA256 对账一致**（KAI certutil ↔ Mac shasum 同值 `fa459011…`）→ `mv` 原子落位 → 指针临时文件+`mv` 原子切换 → **`stand start` 指针解析只读复演命中新包**（106,360,135 B；与 ops/bin/stand POINTER-SELECT 同源逻辑，未 exec jar）。
- **旧包时间戳备份**：`~/.stand/bin/versions/2026.10.21-beta.2/stand-assembly-2026.10.21-beta.2.jar.bak-20261026-010357`（110,274,860 B，复核 SHA `502fcf04…` = 替换前原值，备份完好）。回滚一行：把 `current-version` 写回 `2026.10.25-dev.8ae0e5317` 即回 dev 包；要回旧 beta.2 则从 `.bak-20261026-010357` 恢复文件名。
- **在产零触碰**：端口 6138 listener（pid 72160，00:53:15 起）全程未动——脚本只打 advisory，重启生效留给显式 `stand stop && stand start`。
- **顺手修正（正式跑暴露）**：scp 自 Windows 落盘 600 权限，与目录 644 惯例不合——脚本补 `chmod 644`（本次落位 jar 已手工归一），补丁后 `--dry-run` 全管道复验 **EXIT=0**（沙箱 root，真实构建位零触碰）。

## 5. PerceptionCoreSpec 4 红复测（如实记录）

**仍红，且与 phase-1 完全同 4 例**（非本批引入；本批 6 commit 变更面无 perception 文件）：

| spec 行 | 用例 | 症状 |
|---|---|---|
| 233 | `store: 账户落盘无授权码值；草稿/审计/spool/账单缓冲全链路` | `assertion failed` @ `assert(store.saveAccounts(List(acc)).isRight)`——saveAccounts 返回 Left |
| 392 | `core: 草稿快照一致性——编辑后 mtime > snapshotAt 判 stale` | `NoSuchElementException: None.get` @ `createDraft(...).toOption.get`——createDraft 返回 Left |
| 408 | `core: sendDraft 附件拒绝` | 同上 None.get（createDraft 失败） |
| 418 | `core: abandonDraft 置态并删文件` | 同上 None.get（createDraft 失败） |

- **定因候选**（未深挖，留白天 test7 顺带）：PerceptionStore 持久化写路径在 Windows 环境返回失败——① SecretsStore 秘密写/校验（`authCodeSecret` 名派生合法但落盘面可能未初始化）；② os-lib 文件语义 Windows 差（路径/编码/锁/mtime）；③ 测试 tmp 根目录创建差。四例集中在「写后读」链路，纯内存用例全绿。
- **判读不变**：KAI Windows 环境差、非合并回归——同 spec Mac 侧 34/34 全绿（phase-1 §3 引 recut 批分支证据），树内 perception 面自 `127ff2c59` 后零触碰。

## 6. 日常流（自本批起，已写死 ops/README.md）

> **引擎构建一律 KAI，三步固定顺序**：
> ① **bundle 同步**：Mac 读 main HEAD → `git bundle create /tmp/kai-sync.bundle <KAI_HEAD>..main` → scp → KAI `git fetch` + `--ff-only` → `git status` 核干净；
> ② **KAI 出包**：KAI `sbt -batch compile [快验面] assembly`（前台+心跳落盘；testOnly 红 fail-fast 短路则 assembly 单独补跑）→ 固定出包位 `C:/Users/Kai/stand/target/scala-3.5.2/stand-assembly-<VERSION>.jar`；
> ③ **Mac 拉取**：仓内 `ops/bin/stand-jar-pull`（SHA 双端对账 → .part 原子落位 → 旧包 `.bak-时间戳` 备份 → 指针原子切换 → start 解析复演；**绝不触碰在产 daemon**，新包下次 stop/start 生效）。
>
> **Mac 本地构建退役**，仅 KAI 不可用时紧急兜底（`ops/release.sh stage` 老路）。**白天全量电池在 KAI 跑**（批名 test7，对照基线：phase-1 18:24 终验账 Total 1365/Failed 157 @`4c866d61d` + 本批快验族读数 @`38b81eb4f`）；Mac 侧不再跑全量电池。

## 7. 未做 / 留白

- **引擎重启未做**（按令：不重启不杀在产 daemon）——新包已就位，下次显式 stop/start 生效。
- **白天全量电池 test7 未跑**（半夜，按令留白天；KAI 骨架 `/c/Users/Kai/kai-verify/run.sh` 可复用改批面）。
- PerceptionCoreSpec 4 红 Windows 定因未深挖（候选见 §5）。
- KAI daemon 部署位/数据根迁移仍未立项（KAI 定位纯构建机不变）。

## 附：本批产物与证据位

- 仓内：`ops/README.md`（+「KAI 主线构建约定」一节）、`ops/bin/stand-jar-pull`（+chmod 644 一行，dry-run 复验过）
- KAI 日志：`C:\Users\Kai\kai-phase2\`（p2-build.log 全量 sbt 会话 / p2-heartbeat.log 30s 心跳 / p2-assembly.log 补跑段 / kai-mig-p2.bundle 同步薄包）
- Mac 构建位：`~/.stand/bin/current-version`（=2026.10.21-beta.2）、`previous-version`（=2026.10.25-dev.8ae0e5317）、旧包备份 `.bak-20261026-010357`
- 本报告：`reports/20261026_010538_kai-migration-phase2/REPORT.md`

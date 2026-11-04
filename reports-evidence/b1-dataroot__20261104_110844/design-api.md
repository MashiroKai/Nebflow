# 三层数据根 —— API 契约（B1 第一腿定稿，实施腿勿改名）

- 依据：作者 2026-11-04 三层数据根裁定（逐字要点见本批派单）+ 本腿对 `src/main` 的全量盘点
  （见同目录 `design-inventory.md`，490 处引用逐行分级）。
- 本文件是**唯一契约权威**：后续三条实现腿（V1 根阶梯+未登录层常驻、V2 登录/登出/owner 链、
  V3 迁移与 autostart）照此实现，**类型名 / 方法名 / 字段名一律不得改**；如确需改，先改本文件并回写裁定。
- 只读面：本腿未改任何 `src/main` 生产逻辑、未起实例、未碰生产根 `~/.stand`、未跑 sbt。
- 标注约定：`[裁]` = 派单已定名（逐字保留）；`[+]` = 本腿为实现可行性必须补的成员（**同样不得改名**，
  理由附在该条末尾，供裁定方否决时定点撤回）。

---

## 0. 契约总则（三条不可动）

1. **无 layout 标记的平铺根，一切字节不变。** 无 `layout.json` ⇒ `dataRoot == machineRoot`，
   `machineRoot` 的解析链与今日 `PathUtil.dataRoot` 的解析链逐字相同（`--home` > `STAND_HOME` >
   `NEBFLOW_HOME` > 默认 `~/.stand`，含 legacy `~/.nebflow` 回落 + 一行提示）。
   这是 V7 不回归的前提，也是既有 713 处测试引用 `setDataRoot` 的存活前提。
2. **进程级单值，禁热切。** `PathUtil.dataRoot` 在一次进程生命周期内定案一次；登录成功后
   **不换根，改为写指针 + 重启进程**。任何「进程内二次换根」的实现都判违规。
3. **机器层先于账号层可写。** 定根、日志、pid、单实例门全部走 `machineRoot`，即使账号根不存在 /
   不可读 / 未登录也必须能起来并说清楚原因。

---

## 1. `PathUtil`（`src/main/scala/nebflow/shared/paths.scala`）

```scala
object PathUtil:
  /** [裁][+] 机器层根 = --home / STAND_HOME / NEBFLOW_HOME / 默认 ~/.stand（今日 dataRoot 的解析链原样搬来）。 */
  def machineRoot: os.Path

  /** [裁] 有效根：账号层根 / 未登录层根 / 平铺根（见 §2 阶梯）。语义自本批起改为「有效根」。 */
  def dataRoot: os.Path

  /** [裁] 保留：测试与隔离实例的换根面，签名不变，可重复赋值（713 处测试引用依赖此语义）。 */
  def setDataRoot(path: os.Path): Unit

  /** [裁][+] 生产启动序唯一调用点：只钉机器层根，有效根由 §2 阶梯派生。 */
  def setMachineRoot(path: os.Path): Unit

  /** [裁] 不变：`{{data_root}}` 渲染值（默认 home ⇒ `~/<homeDirName>`，否则绝对路径）。 */
  def dataRootRenderValue: String
  private[shared] def renderDataRootValue(root: os.Path): String                 // 不变
  def substituteDataRoot(text: String): String                                   // 不变

  def configJsonReadPath(dir: os.Path): os.Path                                 // 不变
  def configJsonWritePath(dir: os.Path): os.Path                                // 不变
  def resolveRootRef(root: os.Path, ref: String): os.Path                       // 不变
  private[shared] def resolveDefaultDataRoot(home: os.Path, homeDirName: String): os.Path // 不变（现为机器层解析）
  val LegacyHomeDirName: String = ".nebflow"                                    // 不变
```

实现要点（逐条给实现腿）:

- `machineRoot = _machineRootOverride.getOrElse(<今日 dataRoot 的 getOrElse 分支逐字>)`。
- `dataRoot = _effectiveOverride.getOrElse(resolvedEffectiveRoot)`，
  `resolvedEffectiveRoot` 是 **lazy val = `RootLadder.resolve(machineRoot).root`**（只读一次 ⇒
  「写指针后本进程不认」是设计而非缺陷；登录后重启生效）。
- `setDataRoot(p)`：`_machineRootOverride = Some(p); _effectiveOverride = Some(p); <invalidate lazy>`。
  **即平铺钉死语义**：一个值同时当机器层与有效根 —— 713 处测试、`--home` 隔离实例、canary 全部
  零字节变化（它们从未有账号层）。
- `setMachineRoot(p)`：`_machineRootOverride = Some(p); _effectiveOverride = None; <invalidate lazy>`
  —— 有效根交给阶梯。两 setter 都**允许**重复调用（测试面需要）；「禁热切」是**生产启动面**的约束，
  由 §5 `LoginGuard` 在 CLI/网关入口判，不在 `PathUtil` 里硬拦。
- `dataRootRenderValue` 语义**不变**（仍是「默认 home ⇒ `~/.stand` 字面」的判定）。注意：
  `renderDataRootValue` 的默认档判据是 `root == os.home / Branding.homeDirName`，账号层下恒不相等 ⇒
  渲染绝对路径。这是期望行为（提示词里必须出现真实可点的路径），但**会改变默认安装用户在账号模式下的
  提示词字节**（`~/.stand/...` → `/Users/x/.stand/accounts/<sub>/...`）。文案面不在本批回归红线内，
  记录在 `design-inventory.md` §7 风险 R4，需 B5 提示词腿复核。

---

## 2. `RootLadder` / `RootLayer` —— 解析阶梯（新增文件 `src/main/scala/nebflow/core/root/RootLadder.scala`）

```scala
package nebflow.core.root

sealed trait RootLayer:
  def machineRoot: os.Path
  def root: os.Path
  def name: String                        // "account" | "unauthenticated" | "flat"

object RootLayer:
  final case class Account(machineRoot: os.Path, accountSub: String, root: os.Path) extends RootLayer
  final case class Unauthenticated(machineRoot: os.Path, root: os.Path)             extends RootLayer
  final case class Flat(machineRoot: os.Path, root: os.Path)                        extends RootLayer

object RootLadder:
  /** [裁][+] 纯读解析（只做存在性探测，零写盘）。生产面：Main 启动序唯一调用点。 */
  def resolve(machineRoot: os.Path): RootLayer

  /** [+] 纯结构推断：`.../accounts/<sub>` ⇒ `parents(2)`；其余原样返回。零 IO。 */
  def machineRootOf(raw: os.Path): os.Path

  /** [+] 进程级只读快照（定案后不再变；测试面 reset）。 */
  def current: RootLayer
```

`resolve` 判定阶梯（**顺序即契约**，实现腿不得调整）:

| # | 条件（只读探测） | 结果 `root` | `name` |
|---|---|---|---|
| 0 | `machineRoot/profile.json` 存在 | `machineRoot`（该目录**自身**即账号根；机器层 = `parents(2)`） | `account` |
| 1 | `machineRoot/active-account` 指针可解析且指向的账号根存在 | `machineRoot/accounts/<dir>` | `account` |
| 2 | `machineRoot/layout.json` 存在（`{"layout":"accounts-v1"}`）且无可用指针 | `machineRoot/unauthenticated` | `unauthenticated` |
| 3 | 其余（含：无 layout 标记的老平铺根；指针存在但目标缺失；指针损坏） | `machineRoot` | `flat` |

- 第 0 档是「直接钉账号根」档，存在理由见 §8.2（`DaemonSpawn` / autostart 传的是**已解析好的根**）。
  它与第 1 档互补：`--home <machineRoot>`（shell 启动腿）走 1，`--home <accountRoot>`（jar 自喷
  子进程、autostart 产物）走 0。两档都**不加新 CLI 旗标**，`--home` 语义零变更。
- 第 3 档的「指针损坏」**必须**降级为 `flat` 而不是 `unauthenticated`：指针坏档时若判成未登录层，
  用户会被踢回登录页且原数据根「消失」——这是把一个坏 JSON 放大成数据不可见的事故。
  正确动作 = 按平铺根起来 + 一条 WARN（`ROOT-POINTER-DAMAGED`），由 B3 登出面处置。
- 阶梯结果必须能被**断言**：见 §6.3（`/api/health` 的 `rootLayer` 字段 + 日志标记）。

---

## 3. `AccountRoot` —— 指针 / 档案 / 目录（新增 `src/main/scala/nebflow/core/root/AccountRoot.scala`）

```scala
package nebflow.core.root

final case class ActiveAccount(accountSub: String, email: String, switchedAt: String)

final case class AccountProfile(
  accountSub: String,
  email: String,
  createdAt: String,
  hostPromptDeclinedAt: Option[String]
)

final case class LayoutMarker(layout: String)          // 当前唯一值："accounts-v1"

object AccountRoot:
  /** [裁] 机器层指针文件：`machineRoot/active-account`。 */
  def pointerPath(machineRoot: os.Path): os.Path
  /** [裁] 读指针；坏档 / 缺档 ⇒ None（坏档另打 WARN `ROOT-POINTER-DAMAGED`）。 */
  def readPointer(machineRoot: os.Path): Option[ActiveAccount]
  /** [裁] 原子写指针（tmp + rename，0600）。 */
  def writePointer(machineRoot: os.Path, acct: ActiveAccount): Unit
  /** [裁] 清指针（登出面用；只删文件，不删账号目录）。 */
  def clearPointer(machineRoot: os.Path): Unit

  /** [裁] `machineRoot/accounts/<dirNameOf(sub)>` —— 纯路径，不建目录。 */
  def accountRoot(machineRoot: os.Path, sub: String): os.Path
  /** [裁] `makeDir.all` + 0700（`CredentialFileAcl.restrictDirectory`）；返回账号根。幂等。 */
  def ensureAccountDir(machineRoot: os.Path, sub: String): os.Path

  /** [裁] profile.json 读写 `{accountSub, email, createdAt, hostPromptDeclinedAt}`。 */
  def readProfile(accountRoot: os.Path): Option[AccountProfile]
  def writeProfile(accountRoot: os.Path, p: AccountProfile): Unit      // 原子写 + 0600

  /** [裁] layout 标记 `machineRoot/layout.json` = `{"layout":"accounts-v1"}`。 */
  def layoutMarkerPath(machineRoot: os.Path): os.Path
  def readLayoutMarker(machineRoot: os.Path): Option[LayoutMarker]
  def writeLayoutMarker(machineRoot: os.Path): Unit

  /** [+] 账号根判据 = `profile.json` 存在（§2 第 0 档与迁移腿共同依赖）。 */
  def isAccountRoot(dir: os.Path): Boolean

  /** [+] accountSub → 目录名映射（**Windows 合法性 + 防穿越**，见下）。 */
  def dirNameOf(sub: String): String

  /** [+] 反查：按 accountSub 在 `accounts/*/profile.json` 里找回账号根（账号数 = 个位数，允许扫描）。 */
  def findBySub(machineRoot: os.Path, sub: String): Option[os.Path]
```

### 3.1 `dirNameOf` —— 为什么不能直接拿 accountSub 当目录名（[+] 必须实现）

现有 `accountSub` 的**线上真实形态**是 Logto 的 `sub`，仓内既有断言就是 `"logto|user-42"`
（`GatewaySessionStoreSpec.scala:30`、`ConsoleTicketStoreSpec.scala:26`）。`|` 在 Windows 文件名里
**非法**（`<>:"/\|?*`），`/` 更是路径分隔符（穿越）。故目录名必须经映射，权威值仍存 `profile.json.accountSub`：

```
dirNameOf(sub) =
  if sub matches ^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$  → sub                       （可读、可人判）
  else                                              → "x-" + sha256(sub).hex.take(24)
```

- 映射必须**稳定**（同 sub 恒同目录名）且**单射**（不同 sub 不同名；`x-` 前缀避开可读档的命名空间）。
- `dirNameOf` 不得含 `/`、`\`、`..`、空白；实现里再断言一次（fail-closed，抛 `IllegalArgumentException`）。
- `AccountRoot.accountRoot` 里的 `<accountSub>` **一律**指 `dirNameOf(sub)` 的结果；`profile.json`
  与指针里的 `accountSub` 保持原文。实现腿别在别处再手写这段映射。

### 3.2 目录权限

- 账号根 `0700`：`os.makeDir.all` 后调 `CredentialFileAcl.restrictDirectory`（`PosixDirMode = "rwx------"`，
  Windows 侧 no-op，与既有 `neblink/`、`secrets/` 同规：`CredentialFileAcl.scala:74/664`）。
- `profile.json` / `active-account` / `layout.json`：`0600`（`CredentialFileAcl.restrict`）。
- 幂等：已是 owner-only 的目录不再 chmod（`needsNarrowing` 已有此判据）。

---

## 4. `RootLayout` —— 平铺根迁入账号布局 / 退回（新增 `src/main/scala/nebflow/core/root/RootLayout.scala`）

```scala
final case class MigrationReport(
  machineRoot: os.Path,
  accountSub: Option[String],      // 迁移时已知的首个登录账号；未登录 ⇒ None
  accountRoot: os.Path,            // accountSub 有 ⇒ 该账号根；无 ⇒ 待认领区目录
  pendingClaim: Boolean,           // true ⇒ accountRoot 落在 machineRoot/pending-claim/<ts>/
  movedEntries: List[String],      // 已迁入的顶层条目名（manivest 摘要见 report）
  reportPath: os.Path,              // machineRoot/migration-report.json
)

object RootLayout:
  /** [裁] 平铺根 → 账号布局。未登录 ⇒ 待认领区。返回首个登录账号目录。 */
  def migrate(machineRoot: os.Path): MigrationReport
  /** [裁] 退回平铺布局：删掉本次迁移产出的副本，清 layout.json 与指针（**原件不删**）。 */
  def rollback(report: MigrationReport): Unit
```

语义（照作者裁定的三句话展开，实现腿按此写）:

1. **触发点 = 显式动作，绝不在 boot 里自动跑。** `migrate` 只在两处被调用：
   (a) 平铺机器上的**首次登录**（`RootLadder` 判 `flat` + 登录成功 ⇒ 先 `migrate` 再写指针）；
   (b) 显式命令面（B5/D6 之外的自救面，本批不实现 UI）。
   **绝不在 `resolve` / boot 里跑**：既有平铺机器升级后第一次启动必须仍是 flat + 零写盘，
   否则 V7 直接崩。
2. **未登录 ⇒ 待认领区**：`machineRoot/pending-claim/<ts>/`（`ts` = 本地 `yyyyMMdd-HHmmss`）。
   现有平铺数据整体**复制**进该目录，`layout.json` 写上，指针不写。下一次登录（任一账号）把这个待认领区
   的内容认领进该账号根（认领动作由 V2 登录腿调用 `migrate` 的 (a) 分支完成）。
3. **原件不删**（作者裁定）：`migrate` = **复制 + 逐条 sha256 记账**，源目录一个字节不动；
   `rollback(report)` 先按 manifest 校验副本摘要，**一致才删副本**，不一致 ⇒ 拒删并点名（防搬丢），
   然后 `clearPointer` + 删 `layout.json` ⇒ 下次进程起来回到 flat。平铺根 → 账号根的**收尾清理**
   （删原件）不在本批：属后续「迁移传输」腿（B5 边界外），本契约只保证 `migrate` 之后
   平铺根仍是完整可回退的真源。
4. **写 `layout.json` + `migration-report.json`**（后者含：时间、机器根、目标账号根/待认领区、
   逐条 `{name, kind(file|dir), bytes, sha256}`、引擎版本）。两个文件都在机器层。
5. 磁盘成本必须在日志里如实报一行（复制 GB 级 `sessions/` 的场景）：`[migrate] copying <n> entries, <size> total`。

> 实现腿注意：既有 `scripts/data-root-migration/`（precopy/finalize/verify/rollback）是**换品牌 home**
> 的外部工具箱，与本契约的「平铺 → 账号布局」是两件事，别复用其脚本语义；但**术语对齐**
> （precopy ≈ migrate、rollback ≈ rollback、finalize = 删原件，本批不做）。

---

## 5. `OwnerGuard` / `LoginGuard`（新增 `src/main/scala/nebflow/core/root/Guards.scala`，两对象同文件）

```scala
final case class Refusal(code: String, message: String)   // 双守卫共用；message 必须可行动

object OwnerGuard:
  /** [裁] owner 双点名校验：本引擎服务的账号 vs 凭据里的 owner。 */
  def check(accountSub: String, credentialSub: Option[String]): Either[Refusal, Unit]

object LoginGuard:
  /** [裁] `stand login` 前置门：本机已有活实例且目标账号 ≠ 当前账号 ⇒ 拒。 */
  def checkLoginAllowed(targetAccountSub: String): IO[Either[Refusal, Unit]]
  /** [裁] 禁热切：进程内二次换根 ⇒ 拒（生产启动面调用）。 */
  def checkHotSwitch(previous: os.Path, next: os.Path): Either[Refusal, Unit]
```

- `OwnerGuard.check`：`credentialSub` 为 `None` ⇒ `Right(())`（**fail-open**，与 `OwnerIdentity`
  既有的「读不到锚 ⇒ 不冒认 owner」同一条纪律：`OwnerIdentity.scala:20-22`；无锚时不新增拒绝面）。
  `Some(sub)` 且 `sub != accountSub` ⇒ `Left(Refusal("owner-account-mismatch", …))`，
  文案**必须同时点名两个值 + 行动建议**，逐字模板（实现腿照抄，占位符替换）：
  > `This engine serves account '<accountSub>' but the stored credential belongs to '<owner>' — run 'stand logout', then 'stand login' to sign in again. (owner-account-mismatch)`
  `code` 落 `owner-account-mismatch`（沿用既有稳定码风格：`LoginCommand.scala:151` 的 `SignInFailure` 闭集）。
- `LoginGuard.checkLoginAllowed`：判据 = 端口上有活实例（复用 `SingleInstanceGuard.checkPort`）+ 该实例的
  有效根解析出的 `accountSub`（读 `machineRoot/active-account`）≠ 目标 ⇒
  `Left(Refusal("account-switch-requires-logout", …))`，文案点名两个账号 + `stand logout` 建议。
  无活实例 / 无指针 / 同账号 ⇒ `Right(())`。
  **理由**：单实例单端口，登录即换根；不先登出就登录只会得到「旧实例还占着端口」的谎话报错。
- `LoginGuard.checkHotSwitch`：`previous != next` ⇒ `Left(Refusal("hot-switch-forbidden", …))`。
  生产调用点 = `Main` 启动序（`machineRoot` 定案前后各取一次）与 `LoginCommand` 的根切换路径；
  **不得**放进 `PathUtil.setDataRoot`（测试要反复换根）。

---

## 6. 未登录层（无指针 + 有 layout 标记）

### 6.1 位置与内容

- 根 = `machineRoot/unauthenticated/`（`RootLadder` 第 2 档）。
- 该层只服务「登录页」：引擎正常起（单实例单端口、日志、pid 全在机器层），HTTP 面**收窄为登录所需**
  （`/api/health`、`/api/neblink/auth/*`、`/api/neblink/status`、静态登录页），其余
  API/WS/工具面一律拒绝（错误码沿用既有 `EMPTY_NODE_CONNECTION` 风格的可自纠文案）。
  哪些具体路由留、哪些拒，属 **B2（登录卡视觉改向）** 的裁定范围，本契约只钉死
  「数据根 = 未登录层根」与下面三条硬约束。
- 该层允许落盘的最小集：`gateway-sessions.json`（登录页会话）、`nebflow.json`
  （`Branding.configFileName` 的稳定值，见 `brand.conf:22`；读端口等只读配置）。生命周期日志
  仍在机器层 `machineRoot/logs/`（§9.1），不落本层。

### 6.2 不铸 deviceId（硬约束 + 守卫）

- `NeblinkModel.loadOrCreate`（`src/main/scala/nebflow/neblink/NeblinkModel.scala`，`devicePath` 在 :122）
  入口加**唯一守卫**：

  ```
  if RootLadder.current.name == "unauthenticated" then
    throw new IllegalStateException("UNAUTHENTICATED-LAYER: refusing to mint device identity (no account root); sign in first")
  ```

  fail-closed 抛，**不得**静默返回临时 id（否则一次未登录启动就在服务端留一条幽灵设备行 ——
  正是 `NeblinkModel.scala:178-212` 记录的历史病根）。
- `readDeviceIdOnly`（既有只读面）在未登录层必须返回 `None`（不铸造、不读别的层的 id）。

### 6.3 可断言的标记（两处，都要）

1. **日志**：boot 时**恰好一条** WARN，逐字含稳定标记串
   `[root] UNAUTHENTICATED-LAYER active — no active-account pointer; login-only surface; device identity NOT minted`
   再加同样的标记串在拒绝铸造时抛出的异常消息里（§6.2）。测试面按子串 `UNAUTHENTICATED-LAYER` 断言，
   并用计数断言「恰好一次」（沿用 `DeviceCredentialStore.legacyTokenWarnCount` 的计数先例）。
2. **健康面**：`GET /api/health` 响应新增字段 `rootLayer` ∈ `"account" | "unauthenticated" | "flat"`
   （另可给 `accountSub`，仅当 `account` 档；**不得**给绝对路径，避免把 home 泄给任意本地探针）。
   E2E / V1 用该字段断言未登录层，不必解析日志。

---

## 7. deviceId scope（`NeblinkModel`，命名冻结）

```scala
private[neblink] def deviceIdScope: String
```

判定顺序（**顺序即契约**）：

| # | 条件 | scope 值 |
|---|---|---|
| 1 | `RootLadder.current` 是 `Account(sub)` 且 `machineRoot` 是默认根 | `acct\|<accountSub>` |
| 2 | `RootLadder.current` 是 `Account(sub)` 且 `machineRoot` 非默认根（canary/`--home`/`STAND_HOME`） | `acct\|<accountSub>\|iso\|<uuidV5(DeviceIdNamespace,"iso-scope\|"+normalizedMachineRoot)>` |
| 3 | 其余且非默认 home（今日 `isNonDefaultHome`） | `isolatedHomeScope(PathUtil.dataRoot)`（**逐字不动**） |
| 4 | 其余且默认 home | `DefaultScope` = `"default"`（**逐字不动**） |

- 第 1/2 档共同点：**不用路径哈希当主键**（作者裁定：scope 改 `acct|<accountSub>`）。
  第 2 档追加的 `|iso|<hash>` 是**隔离保持**用的后缀，不参与账号识别 —— 理由：`NeblinkModel.scala:190-207`
  记录的两起事故（隔离实例与主客户端同 `device_id` ⇒ 服务端 `one-live-session-per-(device,network)`
  把主客户端踢下线）。若 canary 用与生产同一个 `acct|<sub>`，**每个账号第一次跑 canary 就会踢掉作者自己**。
  这是本腿对裁定的唯一**加严**（不是替换），已列入 `design-inventory.md` §7 口径分歧 P1 待裁。
- 既有 `device.json` **可解码即沿用**（`loadOrCreate` 既有分支不动）⇒ 老用户 id 零漂移。
  `flat` 档 scope 与今日完全一致 ⇒ `DeviceIdentitySpec` 的钉死断言零改动。
- 未登录层（§6）不铸 ⇒ 该档不进上表。

---

## 8. 启动序 / autostart / 子进程传递（`Main` · `DaemonSpawn` · `AutoStartService`）

### 8.1 启动序（`Main.run`，`Main.scala:128-161` 为唯一落点）

```
1. parseGlobalFlags                       // --home/--port/--takeover 均不变，不新增旗标
2. machineRoot = homeOpt | PathUtil.machineRoot（今日解析链）
3. PathUtil.setMachineRoot(machineRoot)   // 只钉机器层
4. RootLadder.current 定案（lazy，一次）  // account / unauthenticated / flat
5. sys.props("NEBFLOW_HOME") = machineRoot.toString    // ← 日志落机器层（见 §9.2）
6. 若 current 是 Unauthenticated ⇒ 打 §6.3 的标记日志 + 收窄面
7. 其余同今日（port gate / dispatch / boot）
```

- 第 5 步的改动是**一处一行**：今日写的是 `root`（= 有效根），改成 `machineRoot`。
  `sys.props("NEBFLOW_HOME")` 全仓**只有 logback 读**（`src/main/resources/logback.xml:24/29`；
  `Branding.env` 只读 `sys.env`，见 `branding.scala:200-210`）⇒ 对数据根解析零影响，纯日志落位。
- `BgTaskOutputStore.enablePersistence(root)`（`Main.scala:157`）保持传**有效根**（后台任务输出属账号内容）。

### 8.2 子进程与 autostart 传什么（一处定死）

| 启动腿 | 传的 `--home` | 落到的阶梯档 |
|---|---|---|
| shell `ops/bin/stand` | `$STAND_HOME`（机器根，`~/.stand`） | 1/2/3（指针 / 未登录 / 平铺） |
| jar `stand start` → `DaemonSpawn.buildChildArgs` | **父进程已解析的 `PathUtil.dataRoot`**（今日就如此，`DaemonSpawn.scala:97-110`） | 0（账号根，见 `profile.json`） |
| autostart（plist / schtasks / .desktop） | **`<账号根>`**（作者裁定逐字） | 0 |
| 热重启后继（`HotRestart.scala:559/569`） | `PathUtil.dataRoot`（今日就如此） | 0 |

⇒ **不加新旗标**：`--home` 同时接受「机器根」与「账号根」，由阶梯第 0 档（`profile.json` 存在）
判别。这样 `--home` 的既有全部调用点（测试、canary、隔离批、shell、ps1）语义零变化，
而作者裁定的「autostart 追加 `--home <账号根>`」也**逐字成立**。
`RootLadder.machineRootOf` 负责把账号根还原成机器层根（`parents(2)`，零 IO）。

`AutoStartService` 改动面（`AutoStartService.scala`）：

- `programArguments(javaBin, jarPath)` → `programArguments(javaBin, jarPath, accountHome: Option[os.Path])`，
  在有账号根时尾部追加 `"--home", home.toString`；`None` ⇒ 逐字今日输出（老 plist / 未登录 autostart 不变）。
  实现腿：**签名可加参，行为必须保持 `None` 分支逐字节等于今日**（`AutoStartServiceSpec` 之类钉死断言不许改）。
- `renderPlist` / `renderDesktopEntry` / `enableWindows` 的 `schtasks /tr` cmd 同步追加；
  `renderDesktopEntry` 的 `Exec=` 行同样是含 `--home` 的形态。
- `logsDir`（:190 / :228）改 `PathUtil.machineRoot / "logs"`（autostart.log 是生命周期日志）。
- ⚠ 已存在的 plist / schtasks 任务**不会自动更新**：autostart 要考虑「升级后第一次登录」时重写一次
  产物（幂等：内容比对后仅在差异时重写 + 一行 INFO）。这条属 V3 实现腿判定，本契约只标出必要面。

---

## 9. 机器层文件清单（谁搬去哪里 —— 实现腿照表改）

### 9.1 必须搬到 `machineRoot` 的（改：`PathUtil.dataRoot` → `PathUtil.machineRoot`）

| 面 | 位置 | 理由 |
|---|---|---|
| `.pid` | `cli/ProcessManager.scala:10` | shell 停机面 `ops/bin/stand-cli-lib.sh:265/277/354` 读的正是 `$STAND_HOME/.pid`（机器根）⇒ 不改则 `stand stop` 归属证明断链 |
| `.pid.meta` | `cli/PidMeta.scala:55` | 同上（pid 归属记账） |
| `auth.json` | `gateway/auth.scala:18`、`cli/GatewayClient.scala:140` | 作者裁定：机器层「破玻璃」令牌 |
| `daemons.json` / `daemon-pids.json` | `core/daemon/DaemonStore.scala:9`、`DaemonService.scala:32`、`DaemonPanelStore.scala:33`、`core/secrets/SecretsStore.scala:73`、`gateway/PerceptionDaemonDock.scala:162/178` | 守护进程是本机进程，跨账号共享；写入方在未登录层也要能写 |
| `logs/restart.log` | `core/RestartHelper.scala:52/65/107` | 重启诊断必须在账号根坏掉时也可写 |
| `logs/`（后继进程日志） | `core/hotrestart/HotRestart.scala:913` | 同上 |
| `logs/autostart.log` | `core/AutoStartService.scala:190/228` | 开机自启诊断 = 机器层 |
| `bin/`（提示文案里的路径） | `core/tools/RgHelper.scala:128` | 作者裁定：bin/ 机器层 |
| `layout.json` / `active-account` / `migration-report.json` / `accounts/` / `unauthenticated/` / `pending-claim/` | 新增（§3/§4/§6） | 三层布局本体 |

### 9.2 保留在有效根（**不改**）但要注意分层判据的

- **内容日志**（含 prompt / 工具正文 / 会话标识）→ 账号层：`logs/router/*`（`LlmLogWriter.scala:18/104/113`）、
  `logs/tools/*`（`ToolsLogWriter.scala:19/83/89`）、`logs/watchdog/*`（`WatchdogEventLog.scala:23/58`）、
  `logs/jev-*.jsonl`（`JevFallback.scala:82`、`JevShadow.scala:65`）、`logs/plugin-*.jsonl`
  （`PluginBlockPolicy.scala:190`、`PluginDispatchPolicy.scala:288`、`PluginRemovePolicy.scala:179`、
  `PluginSourceSync.scala:62`）、`logs/relay-exec-audit.jsonl`（`RelayExecAudit.scala:101`）。
  这是对作者裁定「logs/ 归机器层」的**分置加严**（口径分歧 P2）—— 机器层只放「账号根不可解析时也必须可写」
  的生命周期日志；含正文的日志跨账号合流 = 泄漏。
- 其余 ~430 处数据面（`agents/ teams/ flows/ tools/ plugins/ skills/ projects/ sessions/ tasks*.json
  secrets/ memory(User.md/Soul.md/memory-backups) dropbox/ uploads/ tool-results/ history/ cache/
  index/ imagegen/ perception/ delegates/ chains/ merge-*/ usage-log/ pat.json/ canvas_tabs.json/
  input_history* / onboarding.json / gateway-sessions.json / models.json / model-presets.json /
  neblink/*` 等）**保持 `PathUtil.dataRoot`** ⇒ 账号层下自动进账号根，平铺根下自动等于今日，
  **零改动**（这正是 490 处盘点的核心结论，见 `design-inventory.md` §4）。

### 9.3 实例身份 / 端口：必须用 `machineRoot`（改，但**不是**数据面）

| 面 | 位置 |
|---|---|
| 端口判定的「是否默认根」 | `shared/InstanceScope.scala:57`（`callerOf(PathUtil.dataRoot, …)` ⇒ 改 `machineRoot`） |
| 端口解析入参 `root` | `gateway/gatewayConfig.scala:96` |
| `proveHome` / `homeRefusal` / `otherInstanceRoot` / `OtherHomeInstance` 文案 | `cli/ProcessManager.scala:65/155/295`、`cli/RestartCommand.scala:133/139`、`cli/SingleInstanceGuard.scala:128/254`、`gateway/GatewayMain.scala:69/78/98/109`、`Main.scala:115/336/345` |

**为什么（这条最容易做错）**：`InstanceScope.callerOf` 把「不是默认根」判成 `overridden = true`，
而 `overridden` 会**拒绝回落到品牌缺省口 6138**（该文件头注：`R 被覆盖 ⇒ 继承来的端口不算显式，
配置也没有 ⇒ 拒绝并要求显式 --port`）。账号根 `~/.stand/accounts/<sub>` 恒 ≠ 默认根 ⇒ 若沿用
`dataRoot`，**所有账号登录后的实例都会拒绝在 6138 上启动**（除非每个账号的配置里手写 port）。
端口 = 机器级属性（裁定：6138 全局唯一），故此处一律 `machineRoot`。
同理，`proveHome` 的「命令行里出现调用者根路径」判据会因 `--home <机器根>` 与 `--home <账号根>`
两种拼法而不一致 ⇒ 传机器层根，并在比较前用 `RootLadder.machineRootOf` 归一。

### 9.4 相对路径基准（改：`os.Path(x, PathUtil.dataRoot)` 一律不放 `dataRoot`）

**32 处**把 `dataRoot` 当**相对路径基准**（不是落盘根）：`os.Path(workspace, PathUtil.dataRoot)` 之类
（`core/project/*` 主力、`gateway/ProjectsRoutes.scala:461/480`、`core/tools/ProjectTool.scala:261`、
`neblink/EgressWhitelist.scala:69` 等，全部逐条列在 `design-inventory.md` §4.C）。判定式：
`grep -rn 'os\.Path(' --include=*.scala src/main | grep 'PathUtil\.dataRoot *[,)]'`（恰 32 行）。基准变到 `accounts/<sub>` 会让**相对 workspace 的解析产生漂移**
（历史配置里的相对 workspace 会指到账号目录下）。契约：这些点改为
`os.Path(x, os.pwd)`（缺省基准，与今日默认 home 下的行为等价）或显式传项目根；
**不得**改用 `machineRoot`（那会改变另一种默认）。逐行表给出全部候选，实现腿逐条确认后再动。

---

## 10. 验收断言清单（V1/V2/V3 与 V7 共用）

| # | 断言 | 手段 |
|---|---|---|
| A1 | 无 `layout.json` 的平铺根：`machineRoot == dataRoot`，且 `stand start` 后既有测试套件全绿 | 现有套件（713 处 `setDataRoot`）+ `PathUtilSpec` 新增一条 |
| A2 | 有 `layout.json`、无指针 ⇒ `dataRoot == machineRoot/unauthenticated` | 夹具根 + 纯解析断言（不跑 Main） |
| A3 | 有指针 ⇒ `dataRoot == machineRoot/accounts/<dirNameOf(sub)>`，目录 `0700` | `stat -f %Lp` / `Files.getPosixFilePermissions` |
| A4 | 未登录层：`/api/health` 的 `rootLayer == "unauthenticated"`，且日志恰一条 `UNAUTHENTICATED-LAYER` | 隔离实例 + curl + grep |
| A5 | 未登录层**不产生** `device.json`（任何层） | `find <machineRoot> -name device.json` |
| A6 | 指针坏档 ⇒ 降级 flat（不落未登录层）+ 一条 `ROOT-POINTER-DAMAGED` | 夹具：写 `{` 进 `active-account` |
| A7 | `flat` 档 `deviceIdScope` 逐字等于今日；`account` 档 = `acct\|<sub>`（含 `\|iso\|` 后缀的条件） | `DeviceIdentitySpec` 既有断言 + 新增两条 |
| A8 | 账号模式实例能在 6138 起（不因 `overridden` 被拒） | 隔离实例起一次 |
| A9 | `stand stop` 在账号模式下仍能证明归属（`.pid` 在机器根） | 隔离实例 + `stand stop` |
| A10 | `migrate` 后原件逐条 sha256 不变；`rollback` 后回到 flat 且数据可读 | 夹具根 + 摘要对比 |
| A11 | `OwnerGuard.check(sub, Some(other))` 文案含两个账号与 `stand logout` | 纯函数 spec |
| A12 | autostart 产物含 `--home <账号根>`；`None` 分支逐字节等于今日 | `AutoStartService` 纯函数 spec |

---

## 11. 术语待入表清单（供 glossary 腿写入）

> **口径差异（必须先说）**：裁定 9 指向 `docs/glossary.md`，本仓**没有 `docs/` 目录**；
> 唯一权威表是仓库根的 `GLOSSARY.md`（202 行，格式：`| 术语 | English/代码拼写 | 定义 | 权威落点 |`，
> 头注规定「只增不改、无落点不进表」）。glossary 腿请写进 `GLOSSARY.md` 新增一节（建议 `## 数据根三层`），
> 不要新建 `docs/glossary.md` —— 那会造出第二张权威表。

| 术语 | 一行定义 | 权威落点（仓内相对路径） |
|---|---|---|
| 三层数据根 | 机器层 / 账号层 / 未登录层三档「有效根」的统称，由启动序按指针与 layout 标记定档。 | `src/main/scala/nebflow/core/root/RootLadder.scala` |
| 机器层 | 全机共享、与账号无关的根：`bin/`、jar、`install-manifest.json`、生命周期 `logs/`、`.pid`/`.pid.meta`、`daemons*.json`、`auth.json`、`layout.json`、`active-account`。 | `PathUtil.machineRoot`（`src/main/scala/nebflow/shared/paths.scala`） |
| 账号层 | 一个账号的私有根 `machineRoot/accounts/<dir>`（0700）：设备身份、`neblink/`、`secrets/`、`projects/`、`sessions/`、`tasks-v2.json`、memory、模型与所有 `.env`。 | `AccountRoot.accountRoot`（`src/main/scala/nebflow/core/root/AccountRoot.scala`） |
| 未登录层 | 装机到首次登录之间的有效根 `machineRoot/unauthenticated/`：只开登录页、**不铸** `device.json`。 | `RootLayer.Unauthenticated` + `NeblinkModel.loadOrCreate` 守卫 |
| active-account 指针 | 机器层单行指针，指明当前账号；缺失 + 有 layout 标记 ⇒ 未登录层。 | `AccountRoot.pointerPath` / `readPointer` |
| profile.json | 账号根下的账号档案 `{accountSub, email, createdAt, hostPromptDeclinedAt}`，也是「本目录是账号根」的判据。 | `AccountProfile` / `AccountRoot.isAccountRoot` |
| layout.json | 机器层布局标记 `{"layout":"accounts-v1"}`；无它 ⇒ 一律按老平铺根处理（兼容红线）。 | `LayoutMarker` / `AccountRoot.readLayoutMarker` |
| 待认领区 | `machineRoot/pending-claim/<ts>/`：平铺数据在「尚未登录」时迁入的中转位置，登录后认领进账号根。 | `RootLayout.migrate`（`MigrationReport.pendingClaim`） |
| owner 账号校验 | 「本引擎服务的账号 == 凭据里的 owner」的等值判定，不等 ⇒ 拒服务并点名双方 + 建议 `stand logout`。 | `OwnerGuard.check` |
| deviceId scope | 设备 id 派生里的账号域成分：`acct\|<accountSub>`（非默认机器根再带 `\|iso\|<hash>`）；平铺/隔离档保持 `default` / `home\|<hash>` 不变。 | `NeblinkModel.deviceIdScope` |

---

## 12. 口径分歧与待裁（不阻塞实现，按「默认值」先行）

| # | 分歧 | 本契约默认 | 备选 / 请裁定方点头的对象 |
|---|---|---|---|
| P1 | `acct\|<sub>` scope 在非默认机器根（canary）上会与生产同账号撞 id，服务端踢掉主客户端 | 非默认机器根追加 `\|iso\|<hash>`（§7 第 2 档） | 若坚持逐字 `acct\|<sub>`：需接受「跑 canary 会踢掉作者主客户端」 |
| P2 | 「logs/ 归机器层」是整目录裁定；含正文的日志跨账号合流 = 泄漏 | 生命周期 4 类留机器层，内容日志留账号层（§9.1/§9.2） | 若坚持整目录机器层：需接受同机其他账号可读 prompt/工具正文 |
| P3 | `accountSub` 原文不能当目录名（`\|` Windows 非法） | 目录名 = `dirNameOf(sub)`，权威值留 `profile.json` | 若坚持目录名 = accountSub 原文：Windows 平台直接不可用 |
| P4 | `--home` 同时兼「机器根」与「账号根」两种输入 | 阶梯第 0 档用 `profile.json` 判别（§2） | 备选：新增 `--account <sub>` 旗标（多一处旗标面 + 全套启动腿改一遍） |

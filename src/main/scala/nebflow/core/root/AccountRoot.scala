package nebflow.core.root

import io.circe.Json
import io.circe.parser.parse
import io.circe.syntax.*
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

import nebflow.core.{AtomicJson, CredentialFileAcl}
import nebflow.shared.NebflowLogger

/**
 * 三层数据根 · 账号层本体（B1，契约 `reports-evidence/20261104_b1-dataroot/design-api.md` §3）。
 *
 * 机器层（全机共享）上的四个布局物：
 *  - `active-account`：单行指针，指明「本机当前是哪个账号」——缺失 + 有布局标记 ⇒ 未登录层；
 *  - `layout.json`：布局标记 `{"layout":"accounts-v1"}`——**没有它一律按老平铺根处理**（兼容红线）；
 *  - `accounts/<dir>/`：账号根（0700），内容 = 一个账号的全部私有数据面；
 *  - `migration-report.json`：迁移记账（见 [[RootLayout]]）。
 *
 * 账号根内的 `profile.json` 既是档案（`accountSub` / `email` / `createdAt` /
 * `hostPromptDeclinedAt`），也是「本目录是账号根」的**唯一判据**（[[isAccountRoot]]，
 * 阶梯第 0 档与迁移腿共同依赖）。
 *
 * == 目录名为什么不是 accountSub 原文（契约 §3.1）==
 * 线上 `accountSub` 的真实形态是 Logto 的 `sub`（仓内既有断言 = `"logto|user-42"`）。
 * `|` 在 Windows 文件名里非法，`/` 更是路径分隔符（穿越）⇒ 目录名一律经 [[dirNameOf]]
 * 映射（可读档原样、其余 `x-<sha256 前 24>`），权威值永远留在 `profile.json.accountSub`。
 */
final case class ActiveAccount(accountSub: String, email: String, switchedAt: String)

final case class AccountProfile(
  accountSub: String,
  email: String,
  createdAt: String,
  hostPromptDeclinedAt: Option[String]
)

final case class LayoutMarker(layout: String)

object AccountRoot:

  private val logger = NebflowLogger.forName("nebflow.root.account")

  /** 当前唯一合法的布局标记值。 */
  val LayoutAccountsV1: String = "accounts-v1"

  /** 指针文件名（机器层，0600）。 */
  val PointerFileName: String = "active-account"

  /** 布局标记文件名（机器层）。 */
  val LayoutFileName: String = "layout.json"

  /** 账号档案文件名（账号根内，0600）——同时是「本目录是账号根」的判据。 */
  val ProfileFileName: String = "profile.json"

  /** 可读目录名判据：首字符字母数字、总长 ≤ 64、只含 `[A-Za-z0-9._-]`。 */
  private val ReadableDirName = "^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$".r

  /** 映射分支前缀（避开可读档的命名空间）。 */
  private val MappedPrefix = "x-"

  // ── 机器层指针 ─────────────────────────────────────────────────────────

  def pointerPath(machineRoot: os.Path): os.Path = machineRoot / PointerFileName

  /**
   * 读指针（契约 §3）。缺档 / 坏档 / 字段不全 ⇒ `None`；坏档另打**一条** WARN
   * `ROOT-POINTER-DAMAGED`（同一路径只留痕一次，避免每次启动刷屏）。
   *
   * 坏档 ⇒ `None` 会让阶梯降到 `flat`（数据仍可见）而不是 `unauthenticated`（数据根消失）——
   * 契约 §2 第 3 档的刻意选择。
   */
  def readPointer(machineRoot: os.Path): Option[ActiveAccount] =
    val path = pointerPath(machineRoot)
    if !os.exists(path) then None
    else
      parse(os.read(path)) match
        case Right(json) =>
          val c = json.hcursor
          val decoded = for
            sub <- c.get[String]("accountSub")
            email <- c.get[String]("email")
            at <- c.get[String]("switchedAt")
          yield ActiveAccount(sub, email, at)
          decoded.toOption match
            case some @ Some(_) => some
            case None =>
              warnDamaged(path, "missing accountSub/email/switchedAt")
              None
        case Left(err) =>
          warnDamaged(path, err.message)
          None

    end if

  end readPointer

  /** 原子写指针（tmp + rename，0600）。 */
  def writePointer(machineRoot: os.Path, acct: ActiveAccount): Unit =
    AtomicJson.writeSyncRestricted(
      pointerPath(machineRoot),
      Json
        .obj(
          "accountSub" -> acct.accountSub.asJson,
          "email" -> acct.email.asJson,
          "switchedAt" -> acct.switchedAt.asJson
        )
        .spaces2
    )

  /** 清指针（登出面）：只删文件，**绝不**删账号目录（登出 ≠ 删数据）。 */
  def clearPointer(machineRoot: os.Path): Unit =
    val path = pointerPath(machineRoot)
    if os.exists(path) then os.remove(path)

  // ── 账号根目录 ─────────────────────────────────────────────────────────

  /** `machineRoot/accounts`（容器；不建目录）。 */
  def accountsDir(machineRoot: os.Path): os.Path = machineRoot / RootLadder.AccountsDirName

  /** `machineRoot/accounts/<dirNameOf(sub)>` —— 纯路径，**不建目录**（契约 §3）。 */
  def accountRoot(machineRoot: os.Path, sub: String): os.Path =
    accountsDir(machineRoot) / dirNameOf(sub)

  /**
   * 建账号根目录（`makeDir.all` + **0700**，契约 §3.2）并返回。幂等：已是 owner-only 的
   * 目录不再 chmod（`CredentialFileAcl.restrictDirectory` 自带 `needsNarrowing` 判据；
   * Windows 侧该面是 no-op，权限走 DACL —— 既有纪律，不在本批扩面）。
   */
  def ensureAccountDir(machineRoot: os.Path, sub: String): os.Path =
    val dir = accountRoot(machineRoot, sub)
    os.makeDir.all(dir)
    CredentialFileAcl.restrictDirectory(dir.toNIO)
    dir

  /**
   * `accountSub` → 目录名（契约 §3.1，**稳定性 + 单射**）：
   *  - 可读形态（`^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$` 且不含 `..`）⇒ 原样（人判优先）；
   *  - 其余 ⇒ `x-` + sha256(sub) 十六进制前 24 位。
   *
   * 尾部断言是 fail-closed：映射结果绝不含 `/`、`\`、`..`、空白（防穿越）。
   */
  def dirNameOf(sub: String): String =
    val mapped =
      if ReadableDirName.matches(sub) && !sub.contains("..") then sub
      else MappedPrefix + sha256Hex(sub).take(24)
    val unusable =
      mapped.isEmpty || mapped.contains('/') || mapped.contains('\\') ||
        mapped.contains("..") || mapped.exists(_.isWhitespace) || mapped == "."
    if unusable then
      throw new IllegalArgumentException(
        s"accountSub is not usable as a directory name (fail-closed): '$mapped' derived from '$sub'"
      )
    mapped

  /** 账号根判据 = `profile.json` 存在（阶梯第 0 档与迁移腿共同依赖）。 */
  def isAccountRoot(dir: os.Path): Boolean = os.exists(dir / ProfileFileName)

  /** 按 `accountSub` 反查账号根（扫每个 `accounts/<dir>` 下的 profile.json；账号数 = 个位数，允许扫描）。 */
  def findBySub(machineRoot: os.Path, sub: String): Option[os.Path] =
    val dirs =
      val base = accountsDir(machineRoot)
      if os.exists(base) then os.list(base).filter(os.isDir).toList.sortBy(_.last) else Nil
    dirs
      .find(d => readProfile(d).exists(_.accountSub == sub))
      .orElse {
        val direct = accountRoot(machineRoot, sub)
        if os.isDir(direct) && readProfile(direct).exists(_.accountSub == sub) then Some(direct) else None
      }

  // ── 账号档案 ───────────────────────────────────────────────────────────

  def readProfile(accountRoot: os.Path): Option[AccountProfile] =
    val path = accountRoot / ProfileFileName
    if !os.exists(path) then None
    else
      parse(os.read(path)) match
        case Right(json) =>
          val c = json.hcursor
          (for
            sub <- c.get[String]("accountSub")
            email <- c.get[String]("email")
            createdAt <- c.get[String]("createdAt")
          yield AccountProfile(
            sub,
            email,
            createdAt,
            c.get[Option[String]]("hostPromptDeclinedAt").toOption.flatten
          )).toOption
        case Left(_) => None

    end if

  end readProfile

  /** 原子写档案（0600）。`hostPromptDeclinedAt = None` ⇒ 键不落盘（缺键读回同样是 None）。 */
  def writeProfile(accountRoot: os.Path, p: AccountProfile): Unit =
    val fields = List(
      Some("accountSub" -> p.accountSub.asJson),
      Some("email" -> p.email.asJson),
      Some("createdAt" -> p.createdAt.asJson),
      p.hostPromptDeclinedAt.map(v => "hostPromptDeclinedAt" -> v.asJson)
    ).flatten
    AtomicJson.writeSyncRestricted(accountRoot / ProfileFileName, Json.fromFields(fields).spaces2)

  // ── 布局标记 ───────────────────────────────────────────────────────────

  def layoutMarkerPath(machineRoot: os.Path): os.Path = machineRoot / LayoutFileName

  def readLayoutMarker(machineRoot: os.Path): Option[LayoutMarker] =
    val path = layoutMarkerPath(machineRoot)
    if !os.exists(path) then None
    else
      parse(os.read(path)).toOption
        .flatMap(_.hcursor.get[String]("layout").toOption)
        .filter(_.nonEmpty)
        .map(LayoutMarker.apply)

  def writeLayoutMarker(machineRoot: os.Path): Unit =
    AtomicJson.writeSyncRestricted(
      layoutMarkerPath(machineRoot),
      Json.obj("layout" -> LayoutAccountsV1.asJson).spaces2
    )

  // ── 内部 ───────────────────────────────────────────────────────────────

  private val damagedWarned = java.util.concurrent.ConcurrentHashMap.newKeySet[String]()

  private def warnDamaged(path: os.Path, why: String): Unit =
    if damagedWarned.add(path.toString) then
      logger.warnSync(
        s"[root] ROOT-POINTER-DAMAGED — $path is not a decodable active-account pointer ($why); " +
          "serving the flat data root instead of hiding it (sign in again to rewrite the pointer)"
      )

  /** 十六进制 sha256（`dirNameOf` / 迁移记账共用一份实现）。 */
  private[root] def sha256Hex(s: String): String =
    MessageDigest
      .getInstance("SHA-256")
      .digest(s.getBytes(StandardCharsets.UTF_8))
      .map("%02x".format(_))
      .mkString

  private[root] def sha256Hex(bytes: Array[Byte]): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).map("%02x".format(_)).mkString

end AccountRoot

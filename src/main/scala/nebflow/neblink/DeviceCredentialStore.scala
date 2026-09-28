package nebflow.neblink

import cats.effect.IO
import io.circe.generic.semiauto.*
import io.circe.parser.decode
import io.circe.syntax.*
import io.circe.{Decoder, Encoder, Json, JsonObject}
import nebflow.core.{AtomicJson, CredentialFileAcl, NebflowLogger, PathUtil}
import nebflow.neblink.CredentialDiagnostics.{CredentialStoreError, Diagnostic, Op}

/**
 * Long-lived per-device NebLink credential, persisted to
 * `~/.nebflow/neblink/device.json` with owner-only access control (POSIX
 * `rw-------`; on Windows a DACL holding a single ACE for the current user —
 * see [[nebflow.core.CredentialFileAcl]] for the Q2/2026-09-11 rationale).
 *
 * Produced by pairing-code enrollment (`POST /api/device/enroll`), the
 * device flow, or the Logto AC+PKCE callback. The raw `deviceToken` is the
 * secret; only its SHA-256 hash is stored on the server.
 *
 * 🔴 **本文件不是出站凭据的来源**（kaiauth 修法批 ②，2026-09-16 作者「治本」已批；
 * 诊断报告 §2/§5）：出站 `/api/device/session` 发的那一枚 `deviceToken` **只**来自
 * `<dataRoot>/neblink/config.json` 的 `neblinkServer.deviceToken`
 * （`NeblinkClient` 构造参 ← `NeblinkConfig.load`），本文件的 `deviceToken` 字段
 * **历史上一度**被当作第二份副本写入，**零读点**（4 个 `DeviceCredential.load` 调用
 * 面都只取 `logto` 块）⇒ 写它等于没写，2026-09-16 的排障正被这份死副本误导。
 * 本批起**写侧停写**该字段（见 [[DeviceCredential]] 的 DEPRECATED 注记），本文件
 * 只承载 `logto` 块 + 身份面字段（`serverUrl` / `networkId` / `deviceId`）。
 *
 * `logto` (stage 2, 2026-08-28): the provider-side login credential carried
 * in this file. Since O5 (2026-09-11) the authorize request no longer asks
 * for `offline_access`, so a NEW login receives an id_token but NO refresh
 * token: the block is therefore present when EITHER half exists, and the
 * identity half (see [[LogtoRefresh]]) must not be gated on the refresh half.
 * Optional + backward compatible (older files without the block decode
 * unchanged). Logto ROTATES refresh tokens — every refresh must write the
 * latest value back.
 */
case class DeviceCredential(
  serverUrl: String,
  networkId: String,
  deviceId: String,
  /** ⚠️ **DEPRECATED（注释级 · 2026-09-16 kaiauth 修法批 ②）—— 本字段非出站来源，
   * 仅为历史残留。**
   *
   *   - **写面**：本类编码器自本批起**不再写出**该键（新旧写入都不含它）；
   *     出站 `deviceToken` 的**唯一权威写面** = `neblink/config.json` 的
   *     `neblinkServer.deviceToken`（由 `NeblinkEnrollment.persist` /
   *     `RestApiRoutes` 的 enroll 路径经 `ms.updateConfig` 写）。
   *   - **读面**：为**零删除纪律（迁移式）**而保留 —— 旧文件（含该键）照旧可解码，
   *     值在读取时**仅忽略**（[[DeviceCredential.load]] 会打**一次** WARN）；
   *     键缺席（新写入形态）也照旧可解码。**没有任何出站消费者读它**。
   *   - 🔴 刻意**不**加 `@deprecated` 注解：本仓 `scalacOptions` 带
   *     `-Xfatal-warnings`，注解会把既有构造点/测试变成编译错误（= 变相删字段，
   *     违反零删除纪律）⇒ deprecated 语义落在**注释层**（作者口径「注释级」）。
   *   - 旧文件里的该键**不清洗**（本批不碰）；清理另列作者面。 */
  deviceToken: String,
  logto: Option[LogtoRefresh] = None
)

/** Provider login credential (Logto) stored in `device.json` — TWO independent
  * halves:
  *
  *  - `refreshToken` + `updatedAt`: the silent re-login credential (rotation
  *    bookkeeping). `refreshToken == ""` is the EXPLICIT "this device holds no
  *    refresh token" marker (see [[LogtoRefresh.of]]); the key itself is still
  *    written, deliberately, so a decoder that predates this convention keeps
  *    reading the file instead of dropping the whole credential (the schema
  *    only becomes optional on the READ side — see the decoder).
  *  - `idToken`: the raw id_token from the token response. It is the ONLY
  *    account-identity source reachable by the web client (`email` /
  *    `displayName` on `/api/neblink/status`, read-only claim decode) and the
  *    `id_token_hint` of the end-session handoff (a valid hint skips the
  *    provider's logout confirmation page; a fabricated one is rejected with
  *    400, probed 2026-09-06).
  *
  * INDEPENDENCE (2026-09-11, O5 companion fix): identity persistence and the
  * refresh credential are decoupled. O5 removed `offline_access` from the
  * authorize request (`LogtoAuthCode.authorizeUrl`), so every new login
  * persists an id_token WITHOUT a refresh token; a block in that state is the
  * EXPECTED post-O5 shape, not an anomaly (it cannot silent-relogin, and the
  * identity / logout-hint halves work exactly as before).
  *
  * Backward compatible: files written before `idToken` existed decode with
  * None; files written before the O5 fix decode with a real refresh token and
  * keep rotating until the provider invalidates it. */
case class LogtoRefresh(
  refreshToken: String,
  updatedAt: Long,
  idToken: Option[String] = None
):
  /** True when this block actually carries something (at least one of the two
    * halves). Information-free blocks are never written and are normalised
    * away on read, so `DeviceCredential.logto.isDefined` means "there IS a
    * stored login credential". */
  def hasContent: Boolean = refreshToken.nonEmpty || idToken.exists(_.nonEmpty)

object LogtoRefresh:
  /** Build a block from the two independent halves (used by the enrollment
    * path, which is the only writer that can carry either). `None` when
    * neither is known — an information-free block is never persisted. Empty
    * strings are treated as absent (see the case class). */
  def of(
    refreshToken: Option[String],
    idToken: Option[String],
    nowMs: Long = System.currentTimeMillis()
  ): Option[LogtoRefresh] =
    val rt = refreshToken.filter(_.nonEmpty)
    val id = idToken.filter(_.nonEmpty)
    if rt.isDefined || id.isDefined then Some(LogtoRefresh(rt.getOrElse(""), nowMs, id)) else None

  /** Hand-written encoder mirrors the DeviceCredential style: absent optional
    * fields stay absent (no nulls in the credential file), BUT `refreshToken`
    * is always written — an empty string when no token is held. Why not drop
    * the key: a pre-fix decoder requires it (`.as[String]`), so dropping it
    * would make the whole credential undecodable for such a reader. */
  given Encoder[LogtoRefresh] = Encoder.instance { r =>
    val base = JsonObject(
      "refreshToken" -> r.refreshToken.asJson,
      "updatedAt" -> r.updatedAt.asJson
    )
    Json.fromJsonObject(
      r.idToken.fold(base)(v => base.add("idToken", v.asJson))
    )
  }

  /** Read side is OPTIONAL (schema compatibility, 2026-09-11): a `logto`
    * object without `refreshToken` (the shape an identity-only writer would
    * produce) decodes with the empty marker instead of failing the whole
    * file. */
  given Decoder[LogtoRefresh] = Decoder.instance { c =>
    for
      refreshToken <- c.downField("refreshToken").as[Option[String]]
      updatedAt <- c.downField("updatedAt").as[Long]
      idToken <- c.downField("idToken").as[Option[String]]
    yield LogtoRefresh(refreshToken.getOrElse(""), updatedAt, idToken)
  }

object DeviceCredential:
  /** Encoder omits the `logto` block when absent (clean legacy-shape files).
    * Post-O5 the block is written for identity-only credentials too (id_token
    * present, refresh token absent) — see [[LogtoRefresh]].
    *
    * 🔴 **本批起也省略 `deviceToken`**（kaiauth 修法批 ②，2026-09-16）：让「写侧
    * 单源化」在**编码器**这一层结构性成立 —— 不论哪个调用面构造 `DeviceCredential`
    * （`NeblinkEnrollment.persist`、`RestApiRoutes` 的配对码 enroll、测试），
    * 落盘结果都不再含该键 ⇒ 「文件里有第二份凭据」这一误导形态**从机制上不可能**
    * 复现。出站凭据的唯一权威写面 = `neblink/config.json`。
    *
    * 🔴 这是「停写」而**不是**「删字段」（零删除纪律）：字段仍在类上（旧文件的解码
    * 目标）、旧文件照旧可解码；只有**写出去**的 JSON 不含它。 */
  given Encoder[DeviceCredential] = Encoder.instance { c =>
    val base = JsonObject(
      "serverUrl" -> c.serverUrl.asJson,
      "networkId" -> c.networkId.asJson,
      "deviceId" -> c.deviceId.asJson
    )
    Json.fromJsonObject(
      c.logto.fold(base)(v => base.add("logto", v.asJson))
    )
  }

  /** Decoder tolerates files without `logto` (pre-stage-2), and normalises an
    * information-free block away: a `logto` object carrying neither a refresh
    * token nor an id_token stores nothing, and keeping it would make
    * `logto.isDefined` a false "there is a stored credential" signal for every
    * reader (status identity, logout hint, silent re-login).
    *
    * kaiauth 修法批 ② 配套（2026-09-16）：`deviceToken` 改为**可选读**
    * （缺席 ⇒ 空串）—— 两个方向都必须成立：旧文件（含该键，pre-本批写入）照旧解码
    * （**向后兼容**，这是硬要求），新文件（不含该键）也必须能解码回来（否则
    * 本批自己写的文件自己读不了 ⇒ `load` 恒 None ⇒ 身份面/refresh 腿全断）。 */
  given Decoder[DeviceCredential] = Decoder.instance { c =>
    for
      serverUrl <- c.downField("serverUrl").as[String]
      networkId <- c.downField("networkId").as[String]
      deviceId <- c.downField("deviceId").as[String]
      deviceToken <- c.downField("deviceToken").as[Option[String]].map(_.getOrElse(""))
      logto <- c.downField("logto").as[Option[LogtoRefresh]]
    yield DeviceCredential(serverUrl, networkId, deviceId, deviceToken, logto.filter(_.hasContent))
  }

  private val log = NebflowLogger.forName("nebflow.neblink.devicecred")

  /** 「旧文件仍带被停写字段」的**一次**告警闩（作者口径「必要时一次 WARN」：
    * 一次就够 —— 排障人拿到的是一条可 grep 的线索，不是每拍一行的噪音）。
    * 计数而非布尔：测试面需要「恰好一次」这个可二值读的读数。 */
  private val legacyTokenWarns = new java.util.concurrent.atomic.AtomicInteger(0)

  /** 观测面（测试用）：本 JVM 内该 WARN 已发出的次数（设计上 ≤ 1）。 */
  private[neblink] def legacyTokenWarnCount: Int = legacyTokenWarns.get()

  /** 测试隔离：重开告警闩（跨 suite 共享 JVM，否则第二个 suite 观测不到 WARN）。 */
  private[neblink] def resetLegacyTokenWarnForTest(): Unit = legacyTokenWarns.set(0)

  // def, not val: PathUtil.dataRoot is redirectable (setDataRoot); a val would
  // freeze the path at object-init and break per-test data roots (f1cd3709 rule).
  private def credPath = PathUtil.dataRoot / "neblink" / "device.json"

  /** 读盘 + **只忽略**被停写的 `deviceToken`（值既不采用、也不清洗）。
    *
    * 旧文件（本批之前写入的形态）带该键 ⇒ 打一次 WARN（可 grep 归因），其余一切
    * 照旧：返回值、`logto` 块、身份字段、ACL 均不变。
    *
    * 🔴 失败面（缺陷 A / 上游 §8.2 第 1 项）：本方法**永不抛**。
    *  - 读不开（权限/占用）⇒ `Left(credential-unreadable)`；
    *  - 解码坏 ⇒ `Left(credential-undecodable)`；
    *  - 两者都先走**自愈**（[[selfHeal]]：备份改名 + 当作无凭据继续，形制对齐身份件
    *    `NeblinkModel.backupCorruptFile`），并各打**一条**带分类码的 WARN。
    * `[[load]]` 是「只要值」的兼容面（失败 ⇒ `None`）；需要分类的调用点用本方法。 */
  def loadDiagnosed: IO[Either[Diagnostic, Option[DeviceCredential]]] =
    IO.blocking(readClassified()).flatMap { outcome =>
      outcome.warnings
        .foldLeft(IO.unit)((acc, w) => acc *> log.warn(w, "code" -> outcome.code))
        .as(outcome.result)
    }

  /** 兼容面：读失败/解码坏 ⇒ `None`（永不抛）。语义 = 「本机此刻没有可用凭据」。 */
  def load: IO[Option[DeviceCredential]] =
    loadDiagnosed
      .map(_.getOrElse(None))
      .flatTap {
        case Some(cred) if cred.deviceToken.nonEmpty => warnLegacyTokenOnce
        case _                                       => IO.unit
      }

  /** 读盘的分类型结果（`warnings` = 本次要发的、**至多一条**的 WARN）。 */
  private case class ReadOutcome(
    result: Either[Diagnostic, Option[DeviceCredential]],
    warnings: List[String],
    code: String
  )

  /** 读盘本体（`IO.blocking` 内跑；只做文件系统 + 组装，不发射日志）。
    *
    * 分派顺序：文件缺席（正常空态）→ 读（分类：读不开）→ 解码（分类：解码坏）。
    * 两种坏形态都**先自愈再返回**：坏件改名留档（不删，迁移式纪律），本次读按
    * 「无凭据」继续 —— 这正是「一次坏了就永久坏」的出口（上游 §6.1）。 */
  private def readClassified(): ReadOutcome =
    val path = credPath
    if !os.exists(path) then ReadOutcome(Right(None), Nil, "")
    else
      val raw =
        try Right(os.read(path))
        catch case e: Exception => Left(e)
      raw match
        case Left(e) =>
          // 读不开（权限 / 占用）—— 上游 R1/R2/R3 三条入口共用的那一条腿。
          finish(
            CredentialFailure.CredentialUnreadable,
            CredentialDiagnostics.describe(e, path.toString),
            path,
            "credential read failed"
          )
        case Right(content) =>
          decode[DeviceCredential](content) match
            case Right(cred) => ReadOutcome(Right(Some(cred)), Nil, "")
            case Left(err) =>
              // 解码坏（半截 JSON / schema 漂移）：修前**完全静默**（连日志都没有，上游 S2）。
              finish(
                CredentialFailure.CredentialUndecodable,
                CredentialDiagnostics.describe(err, path.toString),
                path,
                "credential decode failed"
              )

  /** 坏件的**一次性**自愈 + 一条 WARN（同 `(path, code)` 只做一次：状态拍每 10s 一拍，
    * 无闩会变成日志风暴 —— 与身份件的「一次 WARN」口径同源）。
    *
    * 返回形态：`Left(分类诊断)` —— 自愈**不**吞掉分类（状态面仍能看到「本机凭据出过事」），
    * 只是让本次调用按「无凭据」继续。 */
  private def finish(
    failure: CredentialFailure,
    detail: String,
    path: os.Path,
    why: String
  ): ReadOutcome =
    val diagnostic = CredentialDiagnostics.diagnosticOf(failure, detail)
    if !markFirstAttempt(path, diagnostic.code) then
      // 已自愈过（或已尝试过）：不再改名、不再刷日志；分类读数照旧返回。
      ReadOutcome(Left(diagnostic), Nil, diagnostic.code)
    else
      val backup = selfHeal(path)
      ReadOutcome(
        Left(diagnostic),
        List(diagnostic.logLine(s"$why at $path — self-heal: $backup")),
        diagnostic.code
      )

  /** 自愈（作者 2026-09-18 已裁的既定修法）：把坏件**改名留档**（不删），下次读即
    * 干净空态。形制对齐身份件 `NeblinkModel.backupCorruptFile`（同后缀 `corrupt-<ts>`，
    * 落在同目录 ⇒ 不跨目录、不动别家文件）。失败**不致命**（改名只需父目录权限，
    * 通常可成；失败时如实记入 WARN 的读数）。 */
  private def selfHeal(path: os.Path): String =
    val ts = java.time.format.DateTimeFormatter
      .ofPattern("yyyyMMdd-HHmmss-SSS")
      .withZone(java.time.ZoneId.systemDefault())
      .format(java.time.Instant.now())
    val backup = path / os.up / s"${path.last}.corrupt-$ts"
    try
      os.move(path, backup)
      backup.toString
    catch case e: Exception => s"(backup failed: ${CredentialDiagnostics.describe(e, path.toString)})"

  /** 一次性闩：同 `(path, code)` 的自愈/告警只做一次（计数可读，测试面可复位）。 */
  private val healAttempts = new java.util.concurrent.ConcurrentHashMap[String, java.lang.Boolean]()

  private def markFirstAttempt(path: os.Path, code: String): Boolean =
    healAttempts.putIfAbsent(s"$path|$code", java.lang.Boolean.TRUE) == null

  /** 观测面（测试用）：已做过的自愈尝试次数（设计上每个坏件 1 次）。 */
  private[neblink] def selfHealAttemptsForTest: Int = healAttempts.size

  /** 测试隔离（跨 suite 共享 JVM ⇒ 第二个 suite 观测不到 WARN/备份，除非复位）。 */
  private[neblink] def resetSelfHealForTest(): Unit = healAttempts.clear()

  private def warnLegacyTokenOnce: IO[Unit] =
    // 计数 = **已发出的 WARN 次数**（0→1 只会成功一次），不是 load 次数。
    IO(legacyTokenWarns.compareAndSet(0, 1)).flatMap { first =>
      if first then
        log.warn(
          "device.json still carries the deprecated `deviceToken` field — it is NOT the outbound " +
            "credential source (neblink/config.json is, see NeblinkClient) and its value is " +
            "ignored on read; nothing is deleted (migration-style), cleanup is a separate batch"
        )
      else IO.unit
    }

  def save(cred: DeviceCredential): IO[Unit] =
    save(cred, CredentialFileAcl.systemPort, CredentialFileAcl.currentOsName)

  /** Seam overload (T3, 2026-09-11): `aclPort` + `osName` are parameters so the
    * branch selection is unit-testable on macOS (no NTFS ACL view, no icacls).
    * Public [[save]] delegates with the production port and the live `os.name`,
    * so production behaviour is identical.
    *
    * DESTRUCTIVE BY DESIGN: the whole file is replaced, so
    * fields absent from `cred` disappear. Callers that only know PART of the
    * credential (enrollment: it receives the login's refresh token / id_token
    * as two independent options) must merge with [[load]] first; the merge rule
    * lives in `NeblinkEnrollment.persist`.
    *
    * 🔴 写面（缺陷 A / 上游 §8.2 第 1 项）两笔：
    *  - **原子写**（复用 `core/AtomicJson`：tmp + `ATOMIC_MOVE`）：断电/并发不再留半截
    *    JSON（半截 JSON ⇒ 解码坏 ⇒ 旧代码静默 `None`，上游 S1）。形制对齐身份件。
    *  - **失败必带分类**：写失败 ⇒ 打一条带分类码的 WARN + 抛 [[CredentialStoreError]]
    *    （**不是**裸 `IOException`）⇒ `NeblinkEnrollment` 把它收敛进 `Left` 通道，
    *    与隔离护栏拒绝同通道、同文案层（禁裸异常直透登录框）。
    *
    * The ACL failure path is deliberately non-fatal (the credential is already
    * on disk) but never silent: a warning is logged, because "could not narrow
    * the ACL" is exactly the state in which the device token is readable by
    * other principals (Q2 defect).
    *
    * `aclLadder` (2026-09-19, credaacl 批) is the Windows self-check/repair
    * ladder — the seam that closes the residual "落盘即单向锁死" defect (see
    * [[nebflow.core.CredentialFileAcl.WindowsLadder]]). The DEFAULT is the
    * production ladder, so every existing caller is byte-for-byte unchanged; it
    * is a parameter only so the call-face spec (T4-R7…R9b) can drive a
    * locked-form ladder without a Windows host. No behaviour switches on it. */
  private[neblink] def save(
    cred: DeviceCredential,
    aclPort: CredentialFileAcl.Port,
    osName: String,
    aclLadder: CredentialFileAcl.WindowsLadder = CredentialFileAcl.systemLadder
  ): IO[Unit] =
    IO.blocking(AtomicJson.writeSync(credPath, cred.asJson.spaces2))
      .handleErrorWith { e =>
        val diag = CredentialDiagnostics
          .diagnosticOf(CredentialDiagnostics.classify(Op.Write), CredentialDiagnostics.describe(e, credPath.toString))
        log.warn(diag.logLine("device.json write failed"), "code" -> diag.code) *>
          IO.raiseError(new CredentialStoreError(diag))
      }
      .flatMap { _ =>
        IO.blocking {
          // Owner-only access control: rw------- on POSIX, single-owner DACL on
          // Windows (the platform where the old POSIX call was a silent no-op).
          try
            CredentialFileAcl.restrict(
              java.nio.file.Paths.get(credPath.toString),
              osName,
              aclPort,
              aclLadder
            )
            None
          catch
            case e: Exception =>
              Some(s"os=$osName ${e.getClass.getSimpleName}: ${Option(e.getMessage).getOrElse("")}")
        }.flatMap {
          case None => IO.unit
          case Some(msg) =>
            log.warn(
              s"[${CredentialDiagnostics.classify(Op.Acl).code}] device.json owner-only ACL not applied ($msg) — " +
                "the credential file may be readable by principals other than the current user",
              "code" -> CredentialDiagnostics.classify(Op.Acl).code
            )
        }
      }

  /** Write back the latest rotated refresh token (no-op when nothing is
    * persisted yet — enrollment owns the first write). `newIdToken` REPLACES
    * the stored hint when the provider issued a fresh id_token (refresh
    * grant response) and KEEPS the previous one when absent. An
    * identity-only block (no refresh token) is filled in place: rotation is
    * the only way such a block can gain a refresh credential.
    *
    * NOTE (2026-09-11): this write is a FULL-FILE rewrite
    * ([[save]] → `AtomicJson`), so any writer that rebuilds the credential
    * from scratch must merge with the stored file instead of dropping what it
    * was not given — see `NeblinkEnrollment.persist`.
    *
    * 🔴 失败面（缺陷 A / 上游 §8.2 第 1 项）：本腿**只进日志**（上游 R5：用户可见性 =
    * 仅日志）⇒ 读失败/写失败都打一条**带分类码**的 WARN 后返回，**不再抛**。修前是
    * 异常裸冒泡（调用点 `LogtoSilentRelogin.scala:150` 在 IO 链里）⇒ 该腿整条失败且
    * 用户侧无因。 */
  def updateLogtoRefresh(refreshToken: String, newIdToken: Option[String] = None): IO[Unit] =
    val write =
      load.flatMap {
        case Some(cred) =>
          val next = cred.logto match
            case Some(prev) =>
              prev.copy(refreshToken = refreshToken, updatedAt = System.currentTimeMillis(),
                idToken = newIdToken.orElse(prev.idToken))
            case None => LogtoRefresh(refreshToken, System.currentTimeMillis(), newIdToken)
          save(cred.copy(logto = Some(next)))
        case None => IO.unit
      }
    write.handleErrorWith { e =>
      val diagnostic = CredentialDiagnostics.classifyFailure(e, CredentialFailure.CredentialWriteDenied)
      log.warn(
        diagnostic.logLine("refresh-token rotation write-back failed (silent re-login leg)"),
        "code" -> diagnostic.code
      )
    }

  /** Clear the persisted credential (e.g. when unpairing).
    *
    * 🔴 删面（缺陷 A / 上游 §8.2 第 1 项 + 判据 G5）：**永不抛** —— 登出腿必须始终
    * 完成本地拆除（`performLocalLogout` 的既有契约「Always completes locally」），修前
    * 「删不掉 ⇒ 整条登出 500」正是用户无法自救的那条口子（上游 S3）。
    *
    * 失败时的两级处置：① 尝试**改名留档**（自愈，同 [[selfHeal]]：改名只需父目录权限，
    * 文件被 ACL 锁死时仍常可成 ⇒「清理并重登」入口在锁定态下真的能清理）；
    * ② 都不成 ⇒ 一条带分类码的 WARN（`credential-delete-denied`），拆除继续。 */
  def clear: IO[Unit] =
    IO.blocking {
      if os.exists(credPath) then os.remove(credPath)
    }.handleErrorWith { e =>
      val detail = CredentialDiagnostics.describe(e, credPath.toString)
      val fallback = IO.blocking(selfHeal(credPath)).flatMap { moved =>
        val diagnostic = CredentialDiagnostics.diagnosticOf(CredentialFailure.CredentialDeleteDenied, detail)
        log.warn(diagnostic.logLine(s"credential delete failed — rename-aside fallback: $moved"),
          "code" -> diagnostic.code)
      }
      fallback
    }.void
end DeviceCredential

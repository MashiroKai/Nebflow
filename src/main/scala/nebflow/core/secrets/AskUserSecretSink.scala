package nebflow.core.secrets

import cats.effect.IO
import io.circe.syntax.*
import io.circe.{Json, JsonObject}
import nebflow.core.CredentialFileAcl
import nebflow.shared.Branding

import java.nio.charset.StandardCharsets
import java.nio.file.attribute.{PosixFilePermission, PosixFilePermissions}
import java.nio.file.{Files, Path, StandardCopyOption, StandardOpenOption}
import scala.jdk.CollectionConverters.*

/** secret 型 AskUser 的落库Sink（askuser-secret 批，2026-10-08 · 设计规格 §2/§4.4）。
  *
  * AskUser 密码题的值在引擎内的**唯一落点**：网关 `handleAskUserAnswer` 从
  * `secretSlots` 专用 WS 键里取出值后调 [[store]]；值从不进会话消息 / `.ui.json` /
  * 审计 / 载荷任何字段。本对象是 [[SecretsStore]] 之外的一个**独立受管命名空间**：
  *
  *   - `.askuser/`  —— 长期 secret（落盘名缺省 `askuser-<requestId>-<题序>`）；
  *   - `.otp/`      —— ephemeral 验证码（缺省 `otp-<requestId>-<题序>`，TTL + 读后即焚）。
  *
  * 两个子目录**在 secrets 目录之内**（验收口径「secrets 出 0600 文件」），但
  * `SecretsStore.list` / `SecretsCatalog.rows` 只枚举顶层普通文件 ⇒ 受管项**结构性地**
  * 不进面板 / 目录册（「面板不列受管项」）；`SecretsStore.delete` 的名字寻址被
  * `validateName` 的「名字不得含 `/`」挡在顶层 ⇒ 受管项**结构性地**不可被面板误删
  * （规格 §5.5「受管命名空间防面板误删」；SecretsStore 本体零改动——[[managedName]]
  * 是本对象自己的单一判据面）。
  *
  * 🔴 写纪律 = F1 写序逐条复刻（`SecretsStore.writeThrough` 是 private，本批裁定
  * SecretsStore 本体禁改 ⇒ 纪律在**此处**重演，ACL 逻辑本体仍全部走共享
  * [[CredentialFileAcl]]，无第二份 ACL 副本）：
  *   ① 新目标从第一个字节起 `rw-------`（mode 来自创建属性，无「先写后收窄」窗口）；
   *   ② 已存在目标先收窄再落明文；③ 原地写（TRUNCATE_EXISTING，inode 不换）；④
   *   `CredentialFileAcl.restrict` 为权威收窄步 + postVerify：未落在 0600 ⇒ 删除并拒存。
  *   命名空间闸与 SecretsStore 同款：先 best-effort `restrictDirectory` 再读态判定，
  *   非 0700 ⇒ 拒写（fail-closed doubles）。
  *
  * 🔴 零值暴露（与 SecretsStore 同一红线）：日志 / 失败载荷 / 回执只带**名字 + 路径 +
  * 异常类名**，永不带值；本文件唯二读值的点 = [[consumeEphemeral]]（验证码消费，
  * 值只进**当条 Bash 命令的环境变量**）与 `consumeOtpReferences` 的内部传递，均不落日志。
  *
  * 无 actor 面：所有效果在调用方的 IO/直接调用里完成，失败走 `Either[Failure, *]`
  * （复用 `SecretsStore.Failure` 词表——规格 §2「对齐 SecretsStore.Failure 词表」）。
  */
object AskUserSecretSink:

  /** 失败词表 = `SecretsStore.Failure` 原样复用（规格 §2「对齐 SecretsStore.Failure
    * 词表」；SecretsStore 本体禁改 ⇒ 不扩枚举）：过期与已消费同归 `UnknownSecret`
    * ——从消费方看两者都是「值已不可得」的同一幂等终态，区分只进拒绝文案。 */
  import SecretsStore.Failure

  // ── 命名空间 ───────────────────────────────────────────────────────────

  /** 长期 secret 子目录（secrets 目录内、顶层枚举不可见）。 */
  val AskUserDirName = ".askuser"

  /** ephemeral 验证码子目录。 */
  val OtpDirName = ".otp"

  /** ephemeral 缺省 TTL（秒）：10 分钟（规格 §1）。 */
  val DefaultTtlSec: Int = 600

  /** ttlSec 钳位边界。 */
  val MinTtlSec: Int = 10
  val MaxTtlSec: Int = 86400

  /** 受管命名的单一判据：`askuser-` / `otp-` 前缀（规格 §4.4「managedNamespace 加
    * askuser-/otp- 前缀」在本对象内的落点——SecretsStore 本体禁改，判据单一于此，
    * 由 AskUserSecretSinkSpec 钉住）。 */
  def managedName(name: String): Boolean =
    name.startsWith("askuser-") || name.startsWith("otp-")

  /** 一条回执（网关 → hub `stored[]` → Agent 指针文本的唯一数据源；零值字段）。 */
  final case class Receipt(
    keyRef: String,
    location: String,
    variableName: String,
    ephemeral: Boolean
  )

  /** 落库入参。 */
  final case class StoreRequest(
    requestId: String,
    questionIndex: Int,
    value: String,
    nameHint: Option[String] = None,
    ephemeral: Boolean = false,
    ttlSec: Option[Int] = None
  )

  /** secrets 目录（默认安装 = `~/.nebflow/secrets`）。 */
  def secretsDir(dataRoot: os.Path): os.Path = dataRoot / "secrets"

  /** 与 `SecretsCatalog.renderedLocation` 同一条渲染规则（缺省安装渲染 `~/.nebflow`，
    * 隔离 root 渲染绝对路径）——消费指引里给 Agent 的路径与存盘路径一致。 */
  def renderedLocation(dataRoot: os.Path, subDirName: String, name: String): String =
    val root =
      if dataRoot == os.home / Branding.homeDirName then s"~/${Branding.homeDirName}"
      else dataRoot.toString
    s"$root/secrets/$subDirName/$name"

  /** 落盘名：显式 hint 合法则用之；否则由 requestId+题序派生。派生与 hint 都过
    * `SecretsStore.NamePattern`（公开常量——同一命名契约，禁第二份正则）；hint 非法
    * ⇒ 回落派生名（发起方笔误不炸整卡；「这是落库侧的一次纠正」，回执照发）。 */
  def resolveName(req: StoreRequest): String =
    val derived =
      val prefix = if req.ephemeral then "otp-" else "askuser-"
      s"$prefix${sanitizeFragment(req.requestId)}-${req.questionIndex}"
    req.nameHint match
      case Some(h) if SecretsStore.NamePattern.matcher(h).matches() => h
      case _ => derived

  /** requestId → 名字片段：charset 外字符折 `_`，裁到合法长度。只产出小写安全集。 */
  private def sanitizeFragment(raw: String): String =
    val folded = raw.toLowerCase.map(c => if (c.isLetterOrDigit && c < 128) || c == '-' || c == '.' || c == '_' then c else '_')
    val trimmed = folded.take(40)
    if trimmed.isEmpty then "x" else trimmed

  // ── 写面 ───────────────────────────────────────────────────────────────

  /** 值落库（网关 `handleAskUserAnswer` 的唯一入口）。成功 = secrets 内出现 0600
    * 文件并返回指针回执；失败 = `SecretsStore.Failure` 词表（含路径，不含值）。
    * IO 包装仅为与调用侧（cats-effect 网关）同形；内部全部阻塞语义。 */
  def store(dataRoot: os.Path, req: StoreRequest): IO[Either[Failure, Receipt]] =
    IO.blocking(storeSync(dataRoot, req)).handleErrorWith(e =>
      IO.pure(Left(Failure.Io(describe(e, req.requestId)))))

  /** 同步落库（spec 直测面）。 */
  def storeSync(dataRoot: os.Path, req: StoreRequest): Either[Failure, Receipt] =
    storeSyncWith(dataRoot, req, p => CredentialFileAcl.restrict(p))

  /** 同步落库（注入 seam 面，spec 专用）：`restrict` = 权威收窄步，注入失败形态
    * 以复验 fail-closed double（同 `SecretsStoreSpec` 的注入模式；生产恒走共享
    * CredentialFileAcl，ACL 逻辑零第二副本）。 */
  private[secrets] def storeSyncWith(
    dataRoot: os.Path,
    req: StoreRequest,
    restrict: Path => Unit
  ): Either[Failure, Receipt] =
    val name = resolveName(req)
    SecretsStore.NamePattern.matcher(name).matches() match
      case false => Left(Failure.InvalidName(name, "must match ^[a-z0-9][a-z0-9._-]{0,63}$ (lowercase charset)"))
      case true if req.value.getBytes(StandardCharsets.UTF_8).length > SecretsStore.MaxValueBytes =>
        Left(Failure.InvalidValue(name, s"value exceeds the ${SecretsStore.MaxValueBytes}-byte limit"))
      case true =>
        val sub = if req.ephemeral then OtpDirName else AskUserDirName
        val dir = secretsDir(dataRoot) / sub
        writeWithDiscipline(dir, name, req.value, restrict) match
          case Left(f) => Left(f)
          case Right(_) =>
            if req.ephemeral then recordDeadline(dir, name, ttlOf(req))
            Right(
              Receipt(
                keyRef = name,
                location = renderedLocation(dataRoot, sub, name),
                variableName = SecretsCatalog.deriveVariableName(name),
                ephemeral = req.ephemeral
              )
            )

  private def ttlOf(req: StoreRequest): Int =
    req.ttlSec.fold(DefaultTtlSec)(t => t.max(MinTtlSec).min(MaxTtlSec))

  /** F1 四步写序（见类注）。`ours` 语义与 SecretsStore.writeThrough 同款：本调用
    * 创建/写入过的目标在失败时清除；预存且未写入的文件原样保留。 */
  private def writeWithDiscipline(dir: os.Path, name: String, plain: String, restrict: Path => Unit): Either[Failure, Unit] =
    val target = (dir / name).toNIO
    var ours = false
    try
      Files.createDirectories(dir.toNIO)
      restrictDirectoryIfPresent(dir)
      // 命名空间闸（fail-closed）：收窄后仍非 0700 ⇒ 拒写。
      if !dirIsTight(dir.toNIO) then
        Left(Failure.SecretMode(name, s"secrets namespace permissions are too permissive; refusing to write (path=$dir)"))
      else if Files.exists(target) then
        restrict(target) // ② 先收窄已存文件（seam 可注入失败形态）
        ours = true
        Files.write(target, plain.getBytes(StandardCharsets.UTF_8),
          StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)
        restrict(target) // ④
        postVerify(name, target)
      else
        createOwnerOnly(target) // ① mode 来自创建属性
        ours = true
        Files.write(target, plain.getBytes(StandardCharsets.UTF_8),
          StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)
        restrict(target) // ④
        postVerify(name, target)
    catch
      case e: Exception =>
        val residual =
          if ours then
            try if Files.deleteIfExists(target) then "deleted" else "absent"
            catch case _: Exception => "undeletable"
          else "pre-existing-untouched"
        Left(Failure.SecretMode(name,
          s"could not store secret '$name': ${e.getClass.getSimpleName}: " +
            s"${Option(e.getMessage).getOrElse("")} (path=$target residual=$residual)"))

  /** post-write fail-closed double：未落在 0600 ⇒ 删除并拒存（明文绝不停留在
    * 收窄失败的文件里）。 */
  private def postVerify(name: String, target: Path): Either[Failure, Unit] =
    if fileIsTight(target) then Right(())
    else
      try Files.deleteIfExists(target)
      catch case _: Exception => ()
      Left(Failure.SecretMode(name,
        s"could not restrict permissions on secret '$name'; refusing to store it (path=$target)"))

  private def createOwnerOnly(path: Path): Unit =
    try
      Files.createFile(path,
        PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString(CredentialFileAcl.PosixMode)))
    catch
      case _: UnsupportedOperationException =>
        try Files.deleteIfExists(path)
        catch case _: Exception => ()
        Files.createFile(path)

  private def restrictDirectoryIfPresent(dir: os.Path): Unit =
    try if Files.exists(dir.toNIO) then CredentialFileAcl.restrictDirectory(dir.toNIO)
    catch case _: Exception => ()

  private def dirIsTight(p: Path): Boolean =
    try
      if !Files.exists(p) then true
      else
        val perms = Files.getPosixFilePermissions(p).asScala.toSet
        perms == Set(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
          PosixFilePermission.OWNER_EXECUTE)
    catch case _: Throwable => true

  private def fileIsTight(p: Path): Boolean =
    try
      Files.getPosixFilePermissions(p).asScala.toSet ==
        Set(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)
    catch case _: Throwable => false

  private def describe(e: Throwable, name: String): String =
    s"${e.getClass.getSimpleName} (name=$name)"

  // ── ephemeral：TTL 台账 + 惰性过期 + 清扫 ─────────────────────────────

  /** 每目录一份 TTL 台账（名字 → 到期 epochMs）。值不在台账里——台账本身只是
    * 元数据；放 `.otp/` 内（该目录整体不进任何清单）。 */
  private def deadlinesPath(dir: os.Path): os.Path = dir / ".ttl.json"

  private def recordDeadline(dir: os.Path, name: String, ttlSec: Int): Unit =
    try
      val current = loadDeadlines(dir)
      val next = current.updated(name, System.currentTimeMillis() + ttlSec * 1000L)
      atomicWriteJson(deadlinesPath(dir), Json.obj("version" -> Json.fromInt(1), "deadlines" -> next.asJson))
    catch case _: Exception => () // 台账失败不炸落库：惰性过期（mtime+缺省 TTL）兜底

  /** 台账读面（`private[secrets]`：TTL 过期/清扫臂的 spec 直测面——同
    * `InteractionHub.PendingRequest` 的放宽先例，零生产消费面）。 */
  private[secrets] def loadDeadlines(dir: os.Path): Map[String, Long] =
    val p = deadlinesPath(dir)
    if !Files.exists(p.toNIO) then Map.empty
    else
      try
        io.circe.parser.parse(os.read(p)).toOption
          .flatMap(_.hcursor.downField("deadlines").focus)
          .flatMap(_.asObject)
          .map(_.toMap.flatMap { case (k, v) => v.as[Long].toOption.map(k -> _) })
          .getOrElse(Map.empty)
      catch case _: Throwable => Map.empty

  /** 台账原子整写（spec 直测面，见 [[loadDeadlines]] 注）。 */
  private[secrets] def writeDeadlines(dir: os.Path, deadlines: Map[String, Long]): Unit =
    atomicWriteJson(deadlinesPath(dir), Json.obj("version" -> Json.fromInt(1), "deadlines" -> deadlines.asJson))

  private def atomicWriteJson(target: os.Path, json: Json): Unit =
    Files.createDirectories((target / os.up).toNIO)
    val tmp = (target / os.up) / s".${target.last}.tmp-${java.util.UUID.randomUUID().toString.take(8)}"
    try
      os.write.over(tmp, json.spaces2 + "\n", createFolders = true)
      CredentialFileAcl.restrict(tmp.toNIO)
      Files.move(tmp.toNIO, target.toNIO, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    catch
      case e: Throwable =>
        try Files.deleteIfExists(tmp.toNIO)
        catch case _: Exception => ()
        throw e

  /** 惰性过期判定：台账有到期时间用台账；缺条目回退 mtime + 缺省 TTL。 */
  private def expired(dir: os.Path, name: String, deadlines: Map[String, Long]): Boolean =
    val deadline = deadlines.get(name).getOrElse {
      val mtime =
        try Files.getLastModifiedTime((dir / name).toNIO).toMillis
        catch case _: Throwable => 0L
      mtime + DefaultTtlSec * 1000L
    }
    System.currentTimeMillis() > deadline

  /** TTL 清扫：删除过期验证码与其台账条目、以及「文件已不在」的孤儿台账。清扫
    * 失败静默（清扫是兜底，不是正确性依赖——消费点的惰性过期才是闸）。 */
  def sweepExpired(dataRoot: os.Path): Unit =
    val dir = secretsDir(dataRoot) / OtpDirName
    try
      if Files.isDirectory(dir.toNIO) then
        val deadlines = loadDeadlines(dir)
        val stale = deadlines.filter { case (n, _) => !Files.exists((dir / n).toNIO) }
        val expiredOnes = deadlines.filter { case (n, _) => expired(dir, n, deadlines) }
        val victims = (stale.keySet ++ expiredOnes.keySet).filter(n => SecretsStore.NamePattern.matcher(n).matches())
        victims.foreach { n =>
          try Files.deleteIfExists((dir / n).toNIO)
          catch case _: Exception => ()
        }
        if victims.nonEmpty then
          atomicWriteJson(deadlinesPath(dir), Json.obj("version" -> Json.fromInt(1),
            "deadlines" -> (deadlines -- victims).asJson))
    catch case _: Exception => ()

  // ── 消费面（一次性验证码） ────────────────────────────────────────────

  /** 消费一条验证码：**原子 rename 出目录再读**（规格 §5.6）——目录项消失是原子
    * 事件，两个并发 Bash 同时到达时只有一个能 rename 成功，另一个拿到
    * `UnknownSecret`（已消费/过期/不存在同一幂等终态——过期明文绝不回读）。
    * 成功值只返回给调用方（BashTool 注入**当条命令**的环境变量），本对象不留副本、
    * 不落日志。 */
  def consumeEphemeral(dataRoot: os.Path, name: String): Either[Failure, String] =
    if !SecretsStore.NamePattern.matcher(name).matches() then
      Left(Failure.InvalidName(name, "must match ^[a-z0-9][a-z0-9._-]{0,63}$ (lowercase charset)"))
    else
      val dir = secretsDir(dataRoot) / OtpDirName
      val src = (dir / name).toNIO
      val consumed = (dir / s".consumed-${name}-${java.util.UUID.randomUUID().toString.take(8)}").toNIO
      try
        if !Files.exists(src) then Left(Failure.UnknownSecret(name))
        else
          // 原子 rename-out：第二个消费者的 move 抛 NoSuchFile ⇒ 同一「已消费」面。
          Files.move(src, consumed)
          try
            // 惰性过期：到期的值已 rename 出来，这里销毁并给同一「不可得」终态
            //（过期明文不回读——TTL 是承诺，不是建议）。
            if expired(dir, name, loadDeadlines(dir)) then Left(Failure.UnknownSecret(name))
            else
              val bytes = Files.readAllBytes(consumed)
              Right(new String(bytes, StandardCharsets.UTF_8))
          finally
            try Files.deleteIfExists(consumed)
            catch case _: Exception => ()
      catch
        case _: java.nio.file.NoSuchFileException => Left(Failure.UnknownSecret(name))
        case e: Exception => Left(Failure.Io(describe(e, name)))

  // ── Bash 命令消费面（一次性语义的落地点） ─────────────────────────────

  /** Bash 命令里 `.otp` 引用的识别标记（渲染位置路径的稳定中段）。 */
  private val OtpMarker = "secrets/.otp/"

  /** 命令内 `$(cat <otp路径>)` 形态（cat 与路径间允许引号/空白变体）。 */
  private val CatForm = """\$\(\s*cat\s+"?([^)"\n]*secrets/\.otp/[a-z0-9][a-z0-9._-]{0,63})"?\s*\)""".r

  /** 一次性验证码的**命令消费面**（规格 §2「读取 = 原子 rename 出目录再读」的
    * 落地点）：扫出命令文本里引用的 `.otp` 文件，逐个 [[consumeEphemeral]]，把值
    * 折进**当条命令的环境变量**（`NEBFLOW_OTP_<n>`），并把命令文本里的引用改写成
    * 变量引用——改写后的命令文本**零值**（进日志/卡面的只有变量名）。任一引用
    * 消费失败（已消费/过期）⇒ `Left` 拒绝整条命令（不执行、不部分消费——先全部
    * 消费成功才放行）。
    *
    * 返回 `(改写后命令, 环境变量增量)`；命令不含 otp 引用 ⇒ `(原命令, Map.empty)`
    * 逐字节透传。 */
  def consumeOtpReferences(
    command: String,
    dataRoot: os.Path
  ): Either[String, (String, Map[String, String])] =
    if !command.contains(OtpMarker) then
      sweepExpiredQuietly(dataRoot)
      Right((command, Map.empty))
    else
      val referenced = referencedOtpNames(command)
      if referenced.isEmpty then
        sweepExpiredQuietly(dataRoot)
        Right((command, Map.empty))
      else
        sweepExpiredQuietly(dataRoot)
        // 先全部消费，成功才改写（不部分消费：一半真一半假比全拒更糟）。
        val consumed: Either[String, List[(String, String)]] =
          referenced.foldLeft[Either[String, List[(String, String)]]](Right(Nil)) { (acc, name) =>
            acc.flatMap(done => consumeEphemeral(dataRoot, name) match
              case Right(value) => Right(done :+ (name, value))
              case Left(f) => Left(otpRefusal(name, f)))
          }
        consumed.map { pairs =>
          val env = pairs.zipWithIndex.map { case ((name, value), i) => s"NEBFLOW_OTP_$i" -> value }.toMap
          val byName = pairs.map(_._1).zipWithIndex.map { case (n, i) => n -> s"NEBFLOW_OTP_$i" }.toMap
          val rewritten = rewriteOtpReferences(command, byName)
          (rewritten, env)
        }

  /** 拒绝文案：只含名字、路径与失败类别（SecretsStore.Failure 词表），无值。 */
  private def otpRefusal(name: String, f: Failure): String =
    val why = f match
      case _: Failure.UnknownSecret => "already consumed, expired, or never stored"
      case _: Failure.InvalidName   => "invalid reference name"
      case _                        => "unreadable"
    s"One-time verification code '$name' is no longer available ($why) — the value is deleted after its " +
      "first read. Ask the user for a fresh code; do not retry."

  /** 扫出命令引用的 otp 名字（去重保序）。单一识别标记 = `secrets/.otp/` 中段：
    * 绝对路径（`~/.nebflow/…` 或隔离 root）与相对引用都携带它，故不需要第二套
    * 前缀腿（禁与渲染规则漂移的第二路径源）。 */
  private def referencedOtpNames(command: String): List[String] =
    val found = scala.collection.mutable.LinkedHashSet.empty[String]
    var idx = command.indexOf(OtpMarker)
    while idx >= 0 do
      val rest = command.substring(idx + OtpMarker.length)
      val name = rest.takeWhile(c => (c.isLetterOrDigit && c < 128) || c == '.' || c == '_' || c == '-')
      val cleaned = name.stripSuffix(".") // 句尾标点不吃进名字
      if SecretsStore.NamePattern.matcher(cleaned).matches() then found += cleaned
      idx = command.indexOf(OtpMarker, idx + OtpMarker.length)
    found.toList

  /** 改写命令：先折 `$(cat <路径>)` 整形（值直达），再折残余裸路径（env 变量在
    * shell 展开为值）。改写只碰 otp 引用，其余字节不动。 */
  private def rewriteOtpReferences(command: String, byName: Map[String, String]): String =
    var out = command
    // ① cat 形态 → ${VAR}
    CatForm.findAllMatchIn(out).toList.foreach { m =>
      nameOf(m.group(1)).foreach { n =>
        byName.get(n).foreach { v => out = out.replace(m.group(0), "${" + v + "}") }
      }
    }
    // ② 残余裸路径 → ${VAR}：从 scanFrom 起顺序扫（替换段消失 ⇒ 原位重扫；
    // 不可映射段前移扫描位，禁死循环）。
    var scanFrom = 0
    var guard = 0
    var scanning = true
    while scanning && guard < 64 do
      guard += 1
      out.indexOf(OtpMarker, scanFrom) match
        case -1 => scanning = false
        case i =>
          val rest = out.substring(i + OtpMarker.length)
          val name = rest.takeWhile(c => (c.isLetterOrDigit && c < 128) || c == '.' || c == '_' || c == '-').stripSuffix(".")
          byName.get(name) match
            case Some(v) =>
              val whole = out.substring(i, i + OtpMarker.length + name.length)
              out = out.replace(whole, "${" + v + "}") // 同名引用一并折掉
            case None => scanFrom = i + OtpMarker.length // 理论不可达（referenced=byName 键集）；前移防死循环
    out

  private def nameOf(path: String): Option[String] =
    path.indexOf(OtpMarker) match
      case -1 => None
      case i =>
        val after = path.substring(i + OtpMarker.length)
        val cleaned = after.takeWhile(c => (c.isLetterOrDigit && c < 128) || c == '.' || c == '_' || c == '-').stripSuffix(".")
        if SecretsStore.NamePattern.matcher(cleaned).matches() then Some(cleaned) else None

  private def sweepExpiredQuietly(dataRoot: os.Path): Unit =
    try sweepExpired(dataRoot)
    catch case _: Exception => ()

end AskUserSecretSink

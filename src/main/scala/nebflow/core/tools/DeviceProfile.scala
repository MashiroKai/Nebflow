package nebflow.core.tools

import cats.effect.IO
import io.circe.parser.decode
import io.circe.syntax.*
import io.circe.{Decoder, Encoder, Json}
import nebflow.core.{NebflowLogger, PathUtil}
import nebflow.neblink.PeerInfo

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, StandardCopyOption, StandardOpenOption}
import scala.util.matching.Regex

// ═══════════════════════════════════════════════════════════════════════
// 跨端执行「像本地一样自然」实施批（xdev-impl，2026-09-15）
//
// 本文件是**数据件 + 纯逻辑件**：设备画像 store（①）、探测输出解析（①）、
// 手填 capabilities 的 source/stale 语义（①，作者口径逐字实现）、以及
// 默认关的白名单等价改写层（⑤）。
//
// 🔴 零新工具红线：本文件**不定义任何 Tool**、不改任何工具 schema、不新增
// 任何 wire/JSON 契约——落盘面只有两个运行态数据件（schema 单列申报见批
// 报告）：
//   1. `<dataRoot>/neblink/device-profiles.json`（画像，deviceId 主键）
//   2. `<dataRoot>/neblink/captures/<deviceId>/`（回拉件，TTL 7 天）
// 两者都在 `~/.nebflow`（dataRoot）运行面 ⇒ 天然不进任何 git 仓库。
// ═══════════════════════════════════════════════════════════════════════

/** 单个实测字段的值 + 探测时刻。`source` 恒为 "auto"（结构即声明，不另设键）。 */
case class ProfileField(value: String, probedAt: Long)
object ProfileField:
  given Encoder[ProfileField] = io.circe.generic.semiauto.deriveEncoder
  given Decoder[ProfileField] = Decoder.instance { c =>
    for
      v <- c.downField("value").as[String]
      t <- c.downField("probedAt").as[Long]
    yield ProfileField(v, t)
  }

/** 用户**手填** capabilities 的单字段留痕。作者口径（逐字）：
  * 「自动检测**不覆盖**用户手填值；逐字段标 source（hand-filled / auto）+ 时刻；
  * **冲突以实测为准并标 stale**（实测值生效 + 手填字段标 stale 留痕，
  * 🔴 禁静默丢弃手填值）」。 ⇒ 手填值**永远保留**在 `value` 里；冲突只是把
  * `stale` 置真 + 记 `staleAt`。 */
case class HandCapField(value: String, stale: Boolean = false, staleAt: Option[Long] = None)
object HandCapField:
  given Encoder[HandCapField] = io.circe.generic.semiauto.deriveEncoder
  given Decoder[HandCapField] = Decoder.instance { c =>
    for
      v <- c.downField("value").as[String]
      s <- c.downField("stale").as[Option[Boolean]].map(_.getOrElse(false))
      t <- c.downField("staleAt").as[Option[Option[Long]]].map(_.flatten)
    yield HandCapField(v, s, t)
  }

/** 一台对端设备的画像条目。**主键 = deviceId**（机器码派生、换机必变——按 id
  * 存画像不会跨机污染；判红① 的机械基础）。 */
case class DeviceProfileEntry(
  deviceId: String,
  /** 快照，仅人读；与当前 peer.deviceName 不符 ⇒ 强制重探（判红①）。 */
  deviceName: String,
  /** 对端自报 platform（与实测 kernel 交叉校验用，不单独判定）。 */
  platform: String,
  /** 地址集指纹（同 [[P2pPathDecision.addressKey]] 语义）：地址集变 ⇒ 画像失效。 */
  addressKey: String,
  /** 过期判定唯一依据。 */
  probedAt: Long,
  /** 字段集升级时 +1 ⇒ 旧条目失效重探。 */
  probeVersion: Int,
  /** false = 负条目（上次探测失败）：[[DeviceProfile.NegativeRetryMs]] 内不重试。 */
  available: Boolean,
  /** 实测字段（source=auto）。键集固定在 [[DeviceProfile.KnownKeys]] 白名单内。 */
  fields: Map[String, ProfileField] = Map.empty,
  /** 手填 capabilities 快照（source=hand-filled），冲突标 stale。 */
  handCaps: Map[String, HandCapField] = Map.empty,
  /** 负条目的「最早可重试时刻」。 */
  negativeUntil: Option[Long] = None
)

object DeviceProfileEntry:
  given Encoder[DeviceProfileEntry] = io.circe.generic.semiauto.deriveEncoder
  // 手写 Decoder：缺字段容错（学 DeviceIdentity 的教训——deriveDecoder 对缺省
  // 字段仍必选，手写文件少一个键就整条 decode 失败 ⇒ fail-closed 语义被架空）。
  given Decoder[DeviceProfileEntry] = Decoder.instance { c =>
    for
      deviceId <- c.downField("deviceId").as[String]
      deviceName <- c.downField("deviceName").as[Option[String]].map(_.getOrElse(""))
      platform <- c.downField("platform").as[Option[String]].map(_.getOrElse(""))
      addressKey <- c.downField("addressKey").as[Option[String]].map(_.getOrElse(""))
      probedAt <- c.downField("probedAt").as[Long]
      probeVersion <- c.downField("probeVersion").as[Option[Int]].map(_.getOrElse(0))
      available <- c.downField("available").as[Option[Boolean]].map(_.getOrElse(true))
      fields <- c.downField("fields").as[Option[Map[String, ProfileField]]].map(_.getOrElse(Map.empty))
      handCaps <- c.downField("handCaps").as[Option[Map[String, HandCapField]]].map(_.getOrElse(Map.empty))
      negativeUntil <- c.downField("negativeUntil").as[Option[Option[Long]]].map(_.flatten)
    yield DeviceProfileEntry(deviceId, deviceName, platform, addressKey, probedAt, probeVersion, available, fields, handCaps, negativeUntil)
  }

/** store 文件外层形态（带 version，schema 演进锚点）。 */
case class DeviceProfilesFile(version: Int, profiles: Map[String, DeviceProfileEntry])
object DeviceProfilesFile:
  given Encoder[DeviceProfilesFile] = io.circe.generic.semiauto.deriveEncoder
  given Decoder[DeviceProfilesFile] = Decoder.instance { c =>
    for
      v <- c.downField("version").as[Option[Int]].map(_.getOrElse(0))
      p <- c.downField("profiles").as[Option[Map[String, DeviceProfileEntry]]].map(_.getOrElse(Map.empty))
    yield DeviceProfilesFile(v, p)
  }

/** 设备画像：探测解析、store、手填冲突语义、# Devices 摘要渲染。 */
object DeviceProfile:

  private val logger = NebflowLogger.forName("nebflow.xdev.profile")

  /** store schema 版本（= 单列申报节的主版本）。 */
  val SchemaVersion = 1

  /** 画像 TTL：超过后渲染面标 ⚠stale（**不删条目**——历史留痕，重探即刷新）。 */
  val ProfileTtlMs: Long = 24L * 3600 * 1000

  /** 探测失败负条目的静默期：期内不重探（负控 3：负条目不重探）。 */
  val NegativeRetryMs: Long = 10L * 60 * 1000

  /** 实测字段白名单——对端输出里出现名单外的键一律丢弃（防注入任意键）。 */
  val KnownKeys: Set[String] = Set("cwd", "kernel", "machine", "bash", "msys", "path", "browser", "shot")

  /** 手填 capabilities 里参与冲突判定的已知键（其余手填键原样保留、永不判 stale）。 */
  private val ComparableHandKeys: Set[String] = Set("cwd", "browser", "shell", "os")

  // ── store ───────────────────────────────────────────────────────────

  /** 🔴 落位：`<dataRoot>/neblink/device-profiles.json`（root 令指定；方案卡
    * O-1 按「同族先例」曾荐 dataRoot 顶层，root 令裁定收编进 neblink/ 子目录）。
    * `def` not `val`：dataRoot 可重定向（f1cd3709 rule），隔离实例测试各自独立。 */
  private def storePath = PathUtil.dataRoot / "neblink" / "device-profiles.json"

  /** 损坏 ⇒ fail-closed 到空 Map（行为回到「无画像」= 现状），WARN 留痕。 */
  def load: IO[Map[String, DeviceProfileEntry]] = IO.blocking {
    if os.exists(storePath) then
      decode[DeviceProfilesFile](os.read(storePath)) match
        case Right(f) if f.version <= SchemaVersion => f.profiles
        case Right(f) =>
          logger.warn(s"[device-profile] store version ${f.version} > known $SchemaVersion; ignoring")
          Map.empty
        case Left(err) =>
          logger.warn(s"[device-profile] store decode failed (fail-closed to empty): ${err.getMessage}")
          Map.empty
    else Map.empty
  }

  /** 单进程内写串行锁（save 用；见下）。 */
  private val writeLock = new Object

  /** 原子写（tmp + rename）。并发写策略：单进程内 `writeLock` 串行；跨进程无锁
    * （与同族先例 peer-descriptions.json 同口径——单写者 = 网关进程）。 */
  private def save(entries: Map[String, DeviceProfileEntry]): IO[Unit] = IO.blocking {
    val file = storePath
    val payload = DeviceProfilesFile(SchemaVersion, entries).asJson.spaces2
    writeLock.synchronized {
      Files.createDirectories(file.toNIO.getParent)
      val tmp = file.toNIO.resolveSibling(file.toNIO.getFileName.toString + s".tmp-${System.nanoTime()}")
      Files.write(tmp, payload.getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)
      try Files.move(tmp, file.toNIO, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
      catch { case _: java.nio.file.AtomicMoveNotSupportedException => Files.move(tmp, file.toNIO, StandardCopyOption.REPLACE_EXISTING) }
    }
  }

  /** 探测成功落条目（含手填 capabilities 冲突判定）。 */
  def recordProbe(
    peer: PeerInfo,
    measured: Map[String, String],
    handCaps: Map[String, String],
    now: Long
  ): IO[Option[DeviceProfileEntry]] =
    val known = measured.view.filterKeys(KnownKeys).map((k, v) => k -> ProfileField(v, now)).toMap
    val entry = DeviceProfileEntry(
      deviceId = peer.deviceId,
      deviceName = peer.deviceName,
      platform = peer.platform,
      addressKey = P2pPathDecision.addressKey(peer),
      probedAt = now,
      probeVersion = SchemaVersion,
      available = true,
      fields = known,
      handCaps = mergeHandCaps(handCaps, known, now)
    )
    load.flatMap(m => save(m.updated(peer.deviceId, entry)).as(Some(entry)))
      .handleErrorWith(e => logger.warn(s"[device-profile] save failed: ${e.getMessage}").as(None))

  /** 探测失败 ⇒ 负条目（不阻塞原调用， NegativeRetryMs 内不重试）。 */
  def recordProbeFailed(peer: PeerInfo, now: Long): IO[Option[DeviceProfileEntry]] =
    val entry = DeviceProfileEntry(
      deviceId = peer.deviceId,
      deviceName = peer.deviceName,
      platform = peer.platform,
      addressKey = P2pPathDecision.addressKey(peer),
      probedAt = now,
      probeVersion = SchemaVersion,
      available = false,
      negativeUntil = Some(now + NegativeRetryMs)
    )
    load.flatMap(m => save(m.updated(peer.deviceId, entry)).as(Some(entry)))
      .handleErrorWith(e => logger.warn(s"[device-profile] save(negative) failed: ${e.getMessage}").as(None))

  // ── 手填冲突语义（作者口径） ────────────────────────────────────────

  /** 手填 capabilities ↔ 实测字段合并。手填键无对应实测字段 ⇒ 原样保留
    * （source=hand-filled，无冲突）；有对应且值一致 ⇒ stale=false；**不一致 ⇒
    * 实测生效 + 手填标 stale 留痕**（值不丢）。 */
  def mergeHandCaps(handCaps: Map[String, String], measured: Map[String, ProfileField], now: Long): Map[String, HandCapField] =
    handCaps.map { (k, hv) =>
      val field =
        if !ComparableHandKeys.contains(k) then HandCapField(hv, stale = false, None)
        else
          measured.get(k) match
            case None => HandCapField(hv, stale = false, None)
            case Some(mf) =>
              val normalizedHand = normalizeFor(k, hv)
              val normalizedMeasured = normalizeFor(k, mf.value)
              if normalizedHand.isEmpty || normalizedMeasured.isEmpty || agrees(normalizedHand, normalizedMeasured)
              then HandCapField(hv, stale = false, None)
              else HandCapField(hv, stale = true, Some(now))
      k -> field
    }

  private def normalizeFor(key: String, v: String): String =
    val trimmed = v.trim.toLowerCase
    if key == "cwd" then trimmed.replaceAll("/+$", "").replaceAll("\\\\+$", "") else trimmed

  /** 宽一致判定：互为包含即视为一致（手填短名 `edge` ↔ 实测注册表串
    * `...msedge.exe...` 不算冲突——冲突判定禁误报，误报会错误覆盖 stale 语义）。 */
  private def agrees(hand: String, measured: String): Boolean =
    hand.contains(measured) || measured.contains(hand)

  // ── 时效判定 ────────────────────────────────────────────────────────

  /** 是否需要（重）探测：缺失 / 负条目静默期已过 / TTL 过期 / probeVersion 落后 /
    * 地址集变化 / deviceName 变化。 */
  def needsProbe(entryOpt: Option[DeviceProfileEntry], peer: PeerInfo, now: Long): Boolean =
    entryOpt match
      case None => true
      case Some(e) if !e.available => now >= e.negativeUntil.getOrElse(0L)
      case Some(e) =>
        e.probeVersion < SchemaVersion ||
          e.probedAt < now - ProfileTtlMs ||
          e.addressKey != P2pPathDecision.addressKey(peer) ||
          e.deviceName != peer.deviceName

  private def isStale(e: DeviceProfileEntry, peer: PeerInfo, now: Long): Boolean =
    needsProbe(Some(e), peer, now) // 同一判据：任一失效轴被触发即 stale（负条目走专门分支，不达此处）

  // ── # Devices 摘要渲染（1-2 行/台；只读缓存，绝不在此发起探测） ────

  /** 单台对端的画像摘要（追加在 deviceName 行尾）。画像缺失 ⇒ 空串（覆盖率
    * 诚实性：未知就显示未知 = 现状形态，不装已知）。 */
  def renderSummary(peer: PeerInfo, profiles: Map[String, DeviceProfileEntry], now: Long): String =
    profiles.get(peer.deviceId) match
      case None => ""
      case Some(e) if !e.available => " [profile: probe failed]"
      case Some(e) =>
        val f = e.fields
        val parts = List(
          f.get("cwd").map(_.value).map(v => s"cwd=$v"),
          f.get("kernel").map(_.value).filter(_.nonEmpty).map(v => s"os=$v"),
          f.get("bash").map(_.value).filter(_ != "unknown").map(v => s"shell=bash $v"),
          f.get("browser").map(_.value).filter(v => v.nonEmpty && v != "unknown").map(v => s"browser=$v"),
          f.get("shot").map(_.value).filter(v => v.nonEmpty && v != "none").map(v => s"capture=$v")
        ).flatten
        val staleMark = if isStale(e, peer, now) then " ⚠stale" else ""
        val staleHand = e.handCaps.filter(_._2.stale).keys.toList.sorted.mkString(",")
        val handMark = if staleHand.nonEmpty then s" ⚠stale-hand:$staleHand" else ""
        if parts.isEmpty && staleMark.isBlank && handMark.isEmpty then ""
        else if parts.isEmpty then s" [profile: minimal$staleMark$handMark]"
        else s" [${parts.mkString(" | ")}$staleMark$handMark]"

  /** 回拉约定提示（仅对「已确证可截图」的对端渲染）：告诉模型
    * ① 对端截图命令族已探明 ② 打印 NEBFLOW_PULL 标记即可自动回拉。
    * 🔴 不是工具、不是命令表——一行约定提示。 */
  def renderCaptureHint(peer: PeerInfo, profiles: Map[String, DeviceProfileEntry]): String =
    profiles.get(peer.deviceId).flatMap(e => e.fields.get("shot").map(_.value)) match
      case None | Some("none") => ""
      case Some(method) =>
        s"\n  capture: screenshot via Bash(device=...) with the '$method' method on this peer; after saving the PNG, print a line 'NEBFLOW_PULL:<abs-png-path>' and the engine pulls it to local captures/ (Read it there)."

  /** deviceInfoBlock 的同步读入口（deviceInfoBlock 本身已在 unsafeRunSync 语境；
    * 本地文件读 + 30s 缓存兜底）。失败 ⇒ 空 Map（渲染回现状形态）。 */
  def loadSyncSafe(): Map[String, DeviceProfileEntry] =
    import cats.effect.unsafe.implicits.global
    try load.unsafeRunSync()
    catch { case _: Exception => Map.empty }

end DeviceProfile

/** 探测命令 + 输出解析。命令约束（root 令 KAI 授权边界）：全只读、幂等、
  * 无副作用（不写盘 / 不改注册表 / 不联网）、单条下发、POSIX-bash 跨平台
  * （对端 shell = shell.scala 已选定的 bash 家族）。 */
object DeviceProfileProbe:

  /** 单条只读探测命令。分段：cwd / 内核 / 架构 / bash 版本 / MSYS 判定 /
    * PATH 头 300 字符 / 平台分支（默认浏览器只读查询 + 截图能力探测）。
    * Windows 浏览器查询 = `reg query`（只读注册表）；`//ve` 双斜杠是 MSYS
    * 路径转换规避（#275 铁律同源）。 */
  val Command: String =
    """echo "cwd=$(pwd)"
      |echo "kernel=$(uname -s 2>/dev/null)"
      |echo "machine=$(uname -m 2>/dev/null)"
      |echo "bash=${BASH_VERSION:-unknown}"
      |echo "msys=$(uname -o 2>/dev/null || echo unknown)"
      |echo "path=$(echo "$PATH" | head -c 300)"
      |case "$(uname -s 2>/dev/null)" in
      |  MINGW*|MSYS*|CYGWIN*)
      |    echo "browser=$(reg query 'HKCR\http\shell\open\command' //ve 2>/dev/null | tr -d '\r\n' | sed 's/.*REG_SZ *//')"
      |    if [ -f /c/Windows/System32/WindowsPowerShell/v1.0/powershell.exe ]; then echo "shot=powershell-dotnet"; else echo "shot=none"; fi ;;
      |  Darwin*)
      |    echo "browser=unknown"
      |    if command -v screencapture >/dev/null 2>&1; then echo "shot=screencapture"; else echo "shot=none"; fi ;;
      |  *)
      |    echo "browser=$(xdg-settings get default-web-browser 2>/dev/null || echo unknown)"
      |    for s in scrot gnome-screenshot import; do if command -v "$s" >/dev/null 2>&1; then echo "shot=$s"; break; fi; done
      |    case "$shot" in ""|none) echo "shot=none";; esac ;;
      |esac""".stripMargin

  /** 解析 `key=value` 行；键限白名单、值截断 300。非匹配行（对端 banner 等）忽略。 */
  def parse(out: String): Map[String, String] =
    out.linesIterator.flatMap { l =>
      l.trim.split("=", 2) match
        case Array(k, v) if DeviceProfile.KnownKeys.contains(k.trim) =>
          Some(k.trim -> v.trim.take(300))
        case _ => None
    }.toMap

end DeviceProfileProbe

/** ⑤ 自动改写层——**默认关**（root 令；方案卡 O-5(b)）。开关 = 环境变量
  * `NEBFLOW_XDEV_REWRITE`（无既有配置面 ⇒ env 开关并申报；缺省/0/false ⇒ 关）。
  *
  * 开启后的全部行为（白名单式，🔴 禁猜测性改写）：
  *   - 仅当 ①开关开 ②画像确证对端为 MSYS bash ③命令出现 `cmd /c`（词边界）
  *     ④命令不含禁改特征 ⇒ `cmd /c` → `cmd //c`（MSYS 路径转换等价）。
  *   - 命中出现 `cmd /c` 但命中禁改特征 ⇒ **原样透传 + 警告**（警告进工具结果
  *     尾部 = 模型可见；WARN 日志带 `[xdev-rewrite]` = 可 grep）。
  *   - 其余命令（白名单形态外）⇒ 原样透传零动作（无警告——对每条普通命令都
  *     警告 = 噪声；警告只对「已检出的已知错形态」发）。
  *   - 幂等：已含 `cmd //c`（已改写形态）⇒ 短路不再改。
  *   - 改写必留痕：返回说明串由调用方拼进工具结果 + INFO 日志（可审计）。
  */
object XdevRewrite:

  private val logger = NebflowLogger.forName("nebflow.xdev.rewrite")

  val EnvFlag = "NEBFLOW_XDEV_REWRITE"

  def enabled: Boolean =
    sys.env.get(EnvFlag).exists(v => v.nonEmpty && v != "0" && !v.equalsIgnoreCase("false"))

  /** `cmd /c` 词边界形态（前置不允许是字母数字或 `/`——排除路径里的 `cmd /c` 误判
    * 与已改写形态 `cmd //c`）。 */
  private val CmdC: Regex = raw"""(?<![/\w])(cmd\s+)/c\b""".r

  /** 禁改特征：分隔符 / 重定向 / 引号 / 命令替换 / 换行 / cmd 转义字符。
    * 任一命中 ⇒ MSYS 参数转换的等价性不再有保证 ⇒ 保守不改（fail-closed）。
    * （普通三引号串：`\$` 转义 dollar，避免 raw 串里 `$(` 触发插值解析。） */
  private val Forbidden: Regex = """[;|&<>`'^%]|\$\(|\"|'|\n""".r

  /** 画像确证对端 MSYS（fields.msys 含 Msys/MINGW/MSYS——`uname -o` 输出）。 */
  def msysConfirmed(profOpt: Option[DeviceProfileEntry]): Boolean =
    profOpt.flatMap(_.fields.get("msys")).map(_.value).exists { v =>
      v.contains("Msys") || v.contains("MINGW") || v.contains("MSYS")
    }

  /** (最终命令, 说明)。说明 Some ⇒ 调用方必须留痕（结果回显 + 日志）。
    * 核心逻辑在 [[rewriteOnce]]（enabled 参数化 ⇒ 纯函数可测）；本入口读 env 开关。 */
  def apply(command: String, msys: Boolean): (String, Option[String]) =
    rewriteOnce(command, msys, enabled)

  def rewriteOnce(command: String, msys: Boolean, enabledFlag: Boolean): (String, Option[String]) =
    if !enabledFlag || !msys then (command, None)
    else if !command.contains("/c") then (command, None)
    else if command.contains("cmd //c") || command.contains("cmd ///c") then (command, None) // 幂等短路
    else if !CmdC.findFirstIn(command).isDefined then (command, None)
    else if Forbidden.findFirstIn(command).isDefined then
      val hit = Forbidden.findFirstIn(command).getOrElse("?")
      (command, Some(s"NOT rewritten: 'cmd /c' detected but command hits forbidden char (${escapeForLog(hit)}) — under MSYS this may fake-succeed (exit 0 + banner); verify the output"))
    else
      val rewritten = CmdC.replaceAllIn(command, m => m.group(1) + "//c")
      (rewritten, Some("rewritten 'cmd /c' -> 'cmd //c' (MSYS whitelist equivalence)"))

  private def escapeForLog(s: String): String =
    s.flatMap {
      case '\n' => "\\n"
      case '\t' => "\\t"
      case c    => c.toString
    }

  /** 日志辅助：改写/警告落一行（`[xdev-rewrite]` 前缀可 grep）。 */
  def logNote(deviceName: String, note: String): IO[Unit] =
    if note.startsWith("rewritten") then logger.info(s"[xdev-rewrite] $deviceName: $note")
    else logger.warn(s"[xdev-rewrite] $deviceName: $note")

end XdevRewrite

/** ② ④ 回拉件落盘 + TTL 清扫。落位：`<dataRoot>/neblink/captures/<deviceId>/`
  * （root 令指定；不在 [[nebflow.gateway.WebSocketRoutes.NfDataRootAllowlist]]
  * ⇒ 网关 HTTP fail-closed 拒服务）。TTL 7 天（作者裁定 A）；清扫逐件 INFO
  * 留痕（`[captures-ttl]` 可 grep，🔴 禁静默删）。 */
object CapturePull:

  private val logger = NebflowLogger.forName("nebflow.xdev.capture")

  /** 工具结果里的回拉标记（模型在对端命令里 `echo NEBFLOW_PULL:<abs-path>`）。 */
  val PullMarker = "NEBFLOW_PULL:"

  /** 单次调用最多回拉件数（防滥用；超出部分忽略并留痕）。 */
  val MaxPullsPerCall = 3

  /** 单件大小闸（与 ImageInject.MAX_IMAGE_BYTES 同族口径：10 MiB）。 */
  val MaxCaptureBytes: Long = 10L * 1024 * 1024

  /** TTL：7 天（作者裁定 A）。 */
  val CaptureTtlMs: Long = 7L * 24 * 3600 * 1000

  /** 扫描输出里的回拉路径（行级，最多 [[MaxPullsPerCall]] 个）。 */
  def scanPullPaths(output: String): List[String] =
    output.linesIterator
      .filter(_.contains(PullMarker))
      .map(l => l.substring(l.indexOf(PullMarker) + PullMarker.length).trim)
      .filter(_.nonEmpty)
      .take(MaxPullsPerCall)
      .toList

  def capturesRoot = PathUtil.dataRoot / "neblink" / "captures"

  /** 落盘：`captures/<deviceId>/<yyyymmdd-hhmmss>-<basename>`。basename 取自
    * 远端路径末段并再消毒（防路径穿越/非法字符进本机文件名）。 */
  def saveCapture(deviceId: String, remotePath: String, content: Array[Byte]): os.Path =
    val dir = capturesRoot / deviceId
    os.makeDir.all(dir)
    val ts = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
    val base = remotePath.replaceAll("""[/\\]""", "/").split("/").lastOption.getOrElse("capture")
    val safe = base.replaceAll("""[^A-Za-z0-9._\-]""", "_").take(80)
    val name = s"$ts-$safe"
    val f = dir / name
    os.write(f, content, createFolders = true)
    f

  /** TTL 清扫：逐件按 mtime 判过期（过 [[CaptureTtlMs]] 即删），**每删一件落一行
    * INFO**（`[captures-ttl]`，🔴 禁静默删）。返回删除件数。顺带清空目录。 */
  def sweepExpired(now: Long = System.currentTimeMillis()): IO[Int] = IO.blocking {
    val root = capturesRoot
    if !os.exists(root) then 0
    else
      var removed = 0
      os.walk(root).filter(os.isFile(_)).foreach { f =>
        val age = now - os.mtime(f)
        if age > CaptureTtlMs then
          val device = f.relativeTo(root).segments.headOption.getOrElse("?")
          os.remove(f)
          removed += 1
          logger.info(s"[captures-ttl] removed ${f.last} (age=${age / 86400000L}d > 7d TTL, device=$device)")
      }
      // 清掉空掉的设备目录（根目录保留）
      os.walk(root).foreach(d => if os.isDir(d) && d != root && os.list(d).isEmpty then os.remove(d))
      removed
  }.handleErrorWith { e =>
    logger.warn(s"[captures-ttl] sweep failed: ${e.getMessage}").as(0)
  }

end CapturePull

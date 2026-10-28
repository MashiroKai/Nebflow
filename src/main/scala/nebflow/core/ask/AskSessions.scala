package nebflow.core.ask

import cats.effect.IO
import io.circe.Json
import io.circe.syntax.*
import nebflow.core.AtomicJson
import nebflow.shared.{NebflowLogger, PathUtil}

import java.security.MessageDigest
import scala.util.control.NonFatal

/* ═══════════════════════════════════════════════════════════════════════════
 * Canvas 文件 ask mode · 会话身份与文件绑定（机制稿 §2/§4，实施批 2026-10-28）
 *
 * 设计稿 docs/design/20261028_canvas-ask-mode-design.zh-CN.md：
 *   - 每文件独立 session，会话键 = `ask:<hash(文件路径)>`（作者令⑦：切卡即切、
 *     收起不销毁）；本单点把键收敛为引擎侧 sessionId `ask-<8hex>`（sha256 前 8
 *     位，避免 4 位碰撞面），展示键 `ask:<4hex>` 与样张 v2 徽标同形。
 *   - 会话历史持久化口径沿用主会话面（`<dataRoot>/sessions/<askId>.json` +
 *     `.ui.json`，AgentFinishTurn 逐轮落盘）——ask 会话不进 `_index.json` 索引
 *     （同 delegate 口径：listSessionsIncludeUnindexed 已排除非 UUID 形态 id），
 *     文件绑定表单独落 `<dataRoot>/ask/index.json`（跨重启复现绑定的最小状态）。
 *   - 重组冷却（§3.3）：触发源置待重组标记（合并语义 = 单布尔），10 分钟冷却
 *     期满后的下一次交互（askOpen / askQuestion）前重组，不主动打断。纯函数
 *     `AskCooldown.plan` 是唯一判定面（spec 可穷举）。
 * ═══════════════════════════════════════════════════════════════════════════ */

object AskSessions:

  /** 会话 id 前缀单点——工具面/提示词/spawn 判据全部经本判据，禁写第二份。 */
  val Prefix = "ask-"

  /**
   * 白名单封闭枚举（机制稿 §5）：ask 会话唯一工具面，白名单外一律不下发。
   * 名字 = 机制稿 §5 契约名（snake_case 逐字），buildAllowedToolSet 早退集
   * 与工具注册名同源（AskTools 两对象常量回指本集成员）。
   */
  val AllowedTools: Set[String] = Set("message_record_search", "send_message_to_main_chat")

  /** 重组冷却档（§3.3：10 分钟）。 */
  val ReassemblyCooldownMs: Long = 10L * 60L * 1000L

  /** 判据单点：`ask-` 前缀 = ask 会话（delegate- 前缀同款先例 DelegateJudge）。 */
  def isAskSession(sessionId: String): Boolean =
    sessionId != null && sessionId.startsWith(Prefix)

  /** Option 面（AgentSessionExecution 调用点与 DelegateSessions.isDelegateSession 同形）。 */
  def isAskSession(sessionId: Option[String]): Boolean =
    sessionId.exists(isAskSession)

  /** sha256 前 8 位 hex（会话 id 用；展示再截前 4 位）。 */
  def fileHash(canonicalPath: String): String =
    val digest = MessageDigest.getInstance("SHA-256")
    val bytes = digest.digest(canonicalPath.getBytes(java.nio.charset.StandardCharsets.UTF_8))
    bytes.take(4).map(b => f"${b & 0xff}%02x").mkString

  /** 会话 id 单点：`ask-<8hex>`。同一文件恒同 id（幂等绑定）。 */
  def sessionIdFor(canonicalPath: String): String =
    Prefix + fileHash(canonicalPath)

  /** 展示键（样张 v2 徽标形态 `ask:7f3a`）。 */
  def displayKey(canonicalPath: String): String =
    "ask:" + fileHash(canonicalPath).take(4)

end AskSessions

/** 一个 ask 绑定：文件 ↔ 会话 ↔ 最近已知的控制台主会话（转交目标，§7）。 */
final case class AskBinding(
  id: String,
  file: String,
  fileName: String,
  createdAt: Long,
  /** 转交目标主会话（askOpen 时由前端随帧上报刷新；缺省空 = 转交不可用）。 */
  mainSessionId: String = ""
):

  def displayKey: String = AskSessions.displayKey(file)
end AskBinding

/** 上下文指纹（§3.3 触发源读数）：任一变化 ⇒ 置待重组。 */
final case class AskFingerprint(
  fileMtime: Long = 0L,
  fileSize: Long = 0L,
  recallDbMtime: Long = 0L,
  graphVersion: String = ""
):

  def sameAs(other: AskFingerprint): Boolean = this == other
end AskFingerprint

/** 重组调度状态（每文件一份，内存态；重启后按「未组装」重新起步——首次
 *  askOpen 必组装，冷却语义不受影响）。 */
final case class AskAssemblyState(
  lastAssembledAt: Long = 0L,
  fingerprint: AskFingerprint = AskFingerprint(),
  /** 触发源已置待重组（连续触发合并 = 布尔单标记，§3.3 防抖语义）。 */
  pending: Boolean = false
)

object AskCooldown:

  sealed trait Plan
  object Plan:
    /** 冷却期内 / 无触发：保持现状（重组不发生）。 */
    case object Hold extends Plan
    /** 首次组装（尚无上下文）。 */
    case object Assemble extends Plan
    /** 冷却期满 + 待重组：重组（对话历史不动，§3.3）。 */
    case object Reassemble extends Plan
  end Plan

  /**
   * 唯一判定面（纯函数）：
   *   - 从未组装 ⇒ Assemble（开窗前三路齐备，§8-4）；
   *   - 指纹变化 ⇒ pending 置位（触发合并语义由调用方持有单标记实现）；
   *   - pending ∧ 冷却期满 ⇒ Reassemble；
   *   - 其余 ⇒ Hold。
   */
  def plan(now: Long, state: AskAssemblyState, current: AskFingerprint): Plan =
    val seen = state.lastAssembledAt > 0L
    if !seen then Plan.Assemble
    else
      val dirty = state.pending || !state.fingerprint.sameAs(current)
      if !dirty then Plan.Hold
      else if now - state.lastAssembledAt >= AskSessions.ReassemblyCooldownMs then Plan.Reassemble
      else Plan.Hold
  end plan

  /** plan = Assemble/Reassemble 后的新状态（组装完成时刻落账）。 */
  def afterAssemble(now: Long, current: AskFingerprint): AskAssemblyState =
    AskAssemblyState(lastAssembledAt = now, fingerprint = current, pending = false)
end AskCooldown

/**
 * 文件绑定表（跨重启复现的最小持久态）。线程安全：单 Ref + 读写全在 IO。
 * 持久化 = `<dataRoot>/ask/index.json`（AtomicJson 原子写，先例 saveIndex）。
 */
final class AskSessionRegistry(dataRoot: os.Path):
  private val logger = NebflowLogger.forName("nebflow.ask")

  private val file: os.Path = dataRoot / "ask" / "index.json"

  /** 内存权威；启动时懒加载（首个 bind/find 前读盘一次）。 */
  private val bindingsRef = {
    val initial = loadFromDisk()
    new java.util.concurrent.atomic.AtomicReference[Map[String, AskBinding]](initial)
  }

  private def loadFromDisk(): Map[String, AskBinding] =
    try
      if !os.exists(file) then Map.empty
      else
        val text = os.read(file)
        io.circe.parser.parse(text) match
          case Right(json) =>
            json.hcursor.downField("bindings").as[List[Json]].toOption match
              case Some(items) =>
                items.flatMap { item =>
                  for
                    id <- item.hcursor.downField("id").as[String].toOption
                    f <- item.hcursor.downField("file").as[String].toOption
                    n <- item.hcursor.downField("fileName").as[String].toOption
                    c <- item.hcursor.downField("createdAt").as[Long].toOption
                    m = item.hcursor.downField("mainSessionId").as[String].toOption.getOrElse("")
                  yield AskBinding(id, f, n, c, m)
                }.map(b => b.id -> b).toMap
              case None => Map.empty
          case Left(_) => Map.empty
    catch
      case NonFatal(e) =>
        logger.warn(s"ask index load failed (non-fatal, starting empty): ${e.getMessage}")
        Map.empty

  private def persist(bindings: Map[String, AskBinding]): Unit =
    try
      val json = Json.obj(
        "bindings" -> bindings.values.toList
          .sortBy(b => (b.file, b.createdAt))
          .map(b =>
            Json.obj(
              "id" -> b.id.asJson,
              "file" -> b.file.asJson,
              "fileName" -> b.fileName.asJson,
              "createdAt" -> b.createdAt.asJson,
              "mainSessionId" -> b.mainSessionId.asJson
            )
          )
          .asJson
      )
      AtomicJson.writeSync(file, json.spaces2)
    catch
      case NonFatal(e) => logger.warn(s"ask index persist failed (non-fatal): ${e.getMessage}")

  /** 幂等绑定：同文件恒同会话 id（askOpen/askQuestion 前置调用）。 */
  def bind(canonicalFile: String, fileName: String): IO[AskBinding] =
    val id = AskSessions.sessionIdFor(canonicalFile)
    IO {
      bindingsRef.get.get(id) match
        case Some(existing) =>
          // 文件名漂移（重命名后同路径不会发生；同名不同大小写等）时刷新显示名。
          if existing.fileName != fileName then
            val updated = existing.copy(fileName = fileName)
            bindingsRef.set(bindingsRef.get + (id -> updated))
            persist(bindingsRef.get)
            updated
          else existing
        case None =>
          val fresh = AskBinding(id, canonicalFile, fileName, System.currentTimeMillis())
          bindingsRef.set(bindingsRef.get + (id -> fresh))
          persist(bindingsRef.get)
          fresh
    }

  def byId(id: String): IO[Option[AskBinding]] = IO(bindingsRef.get.get(id))

  def all: IO[List[AskBinding]] = IO(bindingsRef.get.values.toList.sorted(using Ordering.by[AskBinding, String](_.file)))

  /** 转交目标主会话刷新（askOpen 随帧上报；§7 转交与回问的投递根）。 */
  def refreshMainSession(id: String, mainSessionId: String): IO[Unit] =
    IO {
      bindingsRef.get.get(id).foreach { b =>
        if b.mainSessionId != mainSessionId then
          bindingsRef.set(bindingsRef.get + (id -> b.copy(mainSessionId = mainSessionId)))
          persist(bindingsRef.get)
      }
    }

  /** 重组调度状态（内存态，每会话一份）。 */
  private val assemblyRef =
    new java.util.concurrent.atomic.AtomicReference[Map[String, AskAssemblyState]](Map.empty)

  def assemblyState(id: String): IO[AskAssemblyState] =
    IO(assemblyRef.get.getOrElse(id, AskAssemblyState()))

  def setAssemblyState(id: String, st: AskAssemblyState): IO[Unit] =
    IO(assemblyRef.set(assemblyRef.get + (id -> st)))

  /** 触发源置待重组（§3.3：记忆/文件/图谱任一变化；连续触发合并）。 */
  def markDirty(id: String): IO[Unit] =
    IO {
      assemblyRef.get.get(id) match
        case Some(st) if !st.pending => assemblyRef.set(assemblyRef.get + (id -> st.copy(pending = true)))
        case None => assemblyRef.set(assemblyRef.get + (id -> AskAssemblyState(pending = true)))
        case _ => () // 已 pending：合并
    }
end AskSessionRegistry

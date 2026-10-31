/* 消息身份牌机制（msgtokens 批，2026-10-31 作者四项拍板全推荐项）：
 * ①牌格式 {{msg:sid8-seq6}}（会话短码8 = sessionId 的 SHA-256 前 8 位十六进制，
 *   序号6 = 会话内零填充 6 位）②映射缺失 = 显式报错行注入（不静默错引、不回退路径引用）
 * ③msgTokens.enabled 默认开 ④社交腿（微信/飞书出站）整腿二期，本批只做引擎内。
 *
 * 引擎侧纯机制：agent 拿不到任何消解工具——铸牌（真人输入入队/直投时急切铸）、
 * 经 system-reminder 管线向本会话宣告、五个投递组包点（MailTool 派发 / DelegateTool
 * brief / NodeDelivery 运行中注入+待启动补充 / NodeStarter.buildInput）投递前消解为全文。
 * 牌为实例内机制：sid8 前缀 + 会话内序号，跨实例不可解；root 未见过的牌 = 映射缺失 = 报错行。
 *
 * 存储：会话级 JSONL <dataRoot>/sessions/<sid>/msg-tokens.jsonl（append-only + 启动全量
 * 对账重建内存索引；压缩只重写 <sid>.json 消息数组，旁挂 JSONL 原样存活 ⇒ 压缩后映射存活）。
 * 双写序：铸牌先 append 映射行，随后会话消息数组才经 saveMessagesForSession 覆写落盘。
 * 单 JVM 唯一写者纪律（UsageRecordStore 先例）：ReentrantLock 包执行体 + 坏行 decode 容忍。 */
package nebflow.core.msgtokens

import cats.effect.IO
import io.circe.{Decoder, Encoder}
import io.circe.parser.decode
import io.circe.syntax.*
import nebflow.shared.{NebflowLogger, PathUtil, SystemReminder}

import java.security.MessageDigest
import java.util.concurrent.locks.ReentrantLock
import scala.collection.concurrent.TrieMap
import scala.util.matching.Regex

/** 一条牌→全文映射（JSONL 行模型 + 内存索引值）。 */
final case class MsgTokenMapping(
  token: String,
  sessionId: String,
  /** 会话短码8（sessionId 的 SHA-256 前 8 位 hex）——牌面自携带，消解无需发送方会话号。 */
  sid8: String,
  /** 会话内序号（1 起，牌面零填充 6 位）。 */
  seq: Long,
  /** 用户消息全文（映射目标；消解时整段内联替换牌）。 */
  text: String,
  /** 铸牌时刻 epoch millis（审计）。 */
  ts: Long,
  /**
   * 幂等键（WS clientMessageId）——busy 入队铸牌 + turn 边界转发 idle 腿重投同一条
   * 消息时防双铸；None = 无幂等面（ImmediateInput 腿，天然单路径）。重启后从 JSONL
   * 重建。缺席键（旧行/它源）解码为 None，行为兼容。
   */
  mintKey: Option[String] = None
)

object MsgTokenMapping:
  given Encoder[MsgTokenMapping] = io.circe.generic.semiauto.deriveEncoder
  given Decoder[MsgTokenMapping] = io.circe.generic.semiauto.deriveDecoder

/**
 * 消息身份牌注册表：铸牌 / 消解 / 提醒反查 / 启动对账。
 *
 * @param sessionsDir   会话目录（<dataRoot>/sessions）；每会话旁挂
 *                      `<sid>/msg-tokens.jsonl`
 * @param enabledHot    热读开关（默认读 nebflow.json `msgTokens.enabled`，缺席/出错 =
 *                      开——与 plugins.enabled 同向，作者拍板③）。每次铸牌/消解执行期求值，
 *                      配置 PATCH 后下个调用即生效，无需重启。
 */
final class MsgTokenRegistry(
  val sessionsDir: os.Path,
  val enabledHot: () => Boolean = () => MsgTokenRegistry.enabledFromConfig()
):

  private val logger = NebflowLogger.forName("nebflow.msgtokens")

  /** 每会话内存态（惰性从 JSONL 全量装载；装载后置 loaded）。 */
  private final class SessionState:
    val lock = new ReentrantLock()
    @volatile var loaded: Boolean = false
    @volatile var byToken: Map[String, MsgTokenMapping] = Map.empty
    /** 文本 → 该文本**首枚**牌（同文重复输入各有其牌；提醒反查取首枚即可——内容相同）。 */
    @volatile var byText: Map[String, MsgTokenMapping] = Map.empty
    @volatile var lastMinted: Option[MsgTokenMapping] = None
    @volatile var nextSeq: Long = 1L
    /** 已铸幂等键（clientMessageId）集合——入队+转发双路径防双铸；重启后从 JSONL 重建。 */
    @volatile var seenKeys: Set[String] = Set.empty

  /** sessionId → 会话态。 */
  private val states = TrieMap.empty[String, SessionState]
  /** sid8 → sessionId（牌面自携带 sid8 ⇒ 消解全局可达，无需发送方会话上下文）。 */
  private val sidIndex = TrieMap.empty[String, String]

  // ── 牌面 ──────────────────────────────────────────────────────────────────

  /** 会话短码8：sessionId 的 SHA-256 前 4 字节十六进制（小写、确定性）。 */
  def shortCode(sessionId: String): String =
    val digest = MessageDigest.getInstance("SHA-256").digest(sessionId.getBytes("UTF-8"))
    digest.take(4).map(b => f"$b%02x").mkString

  def fileFor(sessionId: String): os.Path = sessionsDir / sessionId / "msg-tokens.jsonl"

  // ── 铸牌 ──────────────────────────────────────────────────────────────────

  /**
   * 急切铸牌：真人用户消息到达（入队/直投）时调用。先 append 映射行（双写序之先），
   * 内存索引随锁更新；会话数组的持久化由既有 saveMessagesForSession 路径随后完成。
   * 禁铸条件（静默无牌，零副作用）：机制关 / 无会话号 / 空文本 / 幂等键已铸（busy 入队
   * 铸牌后经 turn 边界转发 idle 腿重投同一条消息——同 clientMessageId 只铸一次，
   * 「每条用户消息铸唯一牌」）。失败只 WARN 不阻断输入链。
   *
   * @param dedupKey WS clientMessageId（Some = UserInput 腿双路径幂等面）；None = 无幂等面
   */
  def mint(sessionIdOpt: Option[String], text: String, dedupKey: Option[String] = None): IO[Option[MsgTokenMapping]] =
    sessionIdOpt match
      case None => IO.pure(None)
      case Some(sid) =>
        IO.blocking {
          if !enabledHot() || text.isEmpty then None
          else
            val st = states.getOrElseUpdate(sid, new SessionState)
            st.lock.lock()
            try
              if !st.loaded then loadSessionLocked(sid, st)
              if dedupKey.exists(st.seenKeys.contains) then None
              else
                val seq = st.nextSeq
                st.nextSeq = seq + 1
                val sid8 = shortCode(sid)
                val token = s"{{msg:$sid8-${f"$seq%06d"}}}"
                val mapping = MsgTokenMapping(token, sid, sid8, seq, text, System.currentTimeMillis(), dedupKey)
                appendLineSync(fileFor(sid), mapping)
                st.byToken = st.byToken + (token -> mapping)
                if !st.byText.contains(text) then st.byText = st.byText + (text -> mapping)
                st.lastMinted = Some(mapping)
                dedupKey.foreach(k => st.seenKeys = st.seenKeys + k)
                sidIndex.put(sid8, sid)
                logger.info(s"minted $token (session=${sid.take(8)}, textLen=${text.length})")
                Some(mapping)
            catch
              case e: Exception =>
                logger.warn(s"msg-token mint failed for session ${sid.take(8)}: ${e.getMessage}")
                None
            finally st.lock.unlock()
        }

  // ── 消解（五个投递组包点共用）─────────────────────────────────────────────

  /**
   * 投递前消解：文中出现的每枚 {{msg:sid8-seq6}} 替换为映射全文；映射缺失 = 原地注入
   * 显式报错行（[[MsgTokenRegistry.missingErrorLine]]）——不静默错引、不回退路径引用。
   * 机制关闭 = 原样透传（零行为变化）。接收方零工具调用。
   */
  def resolve(text: String): IO[String] =
    if text.isEmpty || MsgTokenRegistry.TokenRegex.findFirstIn(text).isEmpty then IO.pure(text)
    else
      IO.blocking {
        if !enabledHot() then text
        else resolveSync(text)
      }

  private def resolveSync(text: String): String =
    MsgTokenRegistry.TokenRegex.replaceAllIn(text, m =>
      val token = m.matched
      val sid8 = m.group(1)
      val mappingOpt = sessionStateFor(sid8) match
        case Some((sid, st)) =>
          st.lock.lock()
          try
            if !st.loaded then loadSessionLocked(sid, st)
            st.byToken.get(token)
          finally st.lock.unlock()
        case None => None
      mappingOpt match
        case Some(mapping) => mapping.text
        case None =>
          logger.warn(s"msg-token resolve MISS for $token — injecting explicit error line")
          MsgTokenRegistry.missingErrorLine(token)
    )

  /**
   * sid8 → 会话态：索引命中直取；miss 时扫会话目录按短码定位（shortCode 是 sessionId
   * 的确定性哈希 ⇒ 目录名可反查），命中即回填 sidIndex——重启后 reconcile 未跑/失败的
   * 窗口内派发引牌仍可达（不依赖 boot 对账的完成）。扫描仅发生在未知 sid8（每次每
   * 会话至多一次，结果缓存）。
   */
  private def sessionStateFor(sid8: String): Option[(String, SessionState)] =
    sidIndex.get(sid8) match
      case Some(sid) => Some((sid, states.getOrElseUpdate(sid, new SessionState)))
      case None =>
        val found =
          if os.exists(sessionsDir) then
            os.list(sessionsDir).find(p => os.isDir(p) && shortCode(p.last) == sid8).map(_.last)
          else None
        found.map { sid =>
          sidIndex.put(sid8, sid)
          (sid, states.getOrElseUpdate(sid, new SessionState))
        }

  // ── 提醒反查（system-reminder 管线）──────────────────────────────────────

  /**
   * 真用户轮的牌宣告：按本条用户消息文本反查其牌（先 lastMinted 命中，否则 byText 首枚），
   * 命中即产请求级 reminder（不落盘——映射在 JSONL，压缩/重启后仍可按文本反查）。
   * 未命中（机制关闭 / 旧会话无牌史）= None = 零注入零噪声。
   */
  def reminderFor(sessionId: String, text: String): IO[Option[SystemReminder]] =
    IO.blocking {
      if !enabledHot() || text.isEmpty then None
      else
        val st = states.getOrElseUpdate(sessionId, new SessionState)
        st.lock.lock()
        try
          if !st.loaded then loadSessionLocked(sessionId, st)
          val mOpt = st.lastMinted.filter(_.text == text).orElse(st.byText.get(text))
          mOpt.map(m => SystemReminder(MsgTokenRegistry.ReminderCategory, MsgTokenRegistry.reminderContent(m.token)))
        finally st.lock.unlock()
    }

  // ── 启动对账 ──────────────────────────────────────────────────────────────

  /** 启动对账报告（boot 日志 + 测试断言面）。 */
  final case class ReconcileReport(
    sessionsScanned: Int,
    mappingsLoaded: Int,
    /** 所属会话已删除（<sid>.json 不在）而被清除的映射文件数。 */
    prunedSessions: Int,
    prunedMappings: Int
  ):
    def summary: String =
      s"scanned=$sessionsScanned loaded=$mappingsLoaded prunedSessions=$prunedSessions prunedMappings=$prunedMappings"

  /**
   * boot 单次对账（MergeQueueBootSweep 同款纪律：绝不阻塞启动、失败只 WARN——失败处理
   * 在调用侧 .attempt）：扫描全部 `<sid>/msg-tokens.jsonl` —— 所属会话已删 ⇒ 映射文件
   * 清除（孤儿不收容）；在册会话 ⇒ 全量载入内存索引并重建 nextSeq（重启后铸牌序号续走、
   * 派发即解，无需等首次铸牌触发的惰性装载）。
   */
  def reconcileAll(): IO[ReconcileReport] = IO.blocking {
    val sessionDirs =
      if os.exists(sessionsDir) then os.list(sessionsDir).filter(p => os.isDir(p)) else Nil
    var scanned = 0
    var loaded = 0
    var prunedSessions = 0
    var prunedMappings = 0
    sessionDirs.foreach { dir =>
      val file = dir / "msg-tokens.jsonl"
      if os.exists(file) then
        scanned += 1
        val sid = dir.last
        if !os.exists(sessionsDir / s"$sid.json") then
          val count = readLinesSync(file).size
          os.remove(file)
          prunedSessions += 1
          prunedMappings += count
          states.remove(sid)
          sidIndex.remove(shortCode(sid))
          logger.info(s"reconcile: pruned $count orphan mapping(s) for deleted session ${sid.take(8)}")
        else
          val st = states.getOrElseUpdate(sid, new SessionState)
          st.lock.lock()
          try
            if !st.loaded then
              loadSessionLocked(sid, st)
              loaded += st.byToken.size
          finally st.lock.unlock()
    }
    ReconcileReport(scanned, loaded, prunedSessions, prunedMappings)
  }

  // ── 内部：JSONL 读写（单 JVM 唯一写者纪律）───────────────────────────────

  /** 锁内调用：从磁盘全量装载会话映射（坏行静默跳过——torn-tail/手写污染容忍）。 */
  private def loadSessionLocked(sid: String, st: SessionState): Unit =
    val file = fileFor(sid)
    val lines = readLinesSync(file)
    var byToken = Map.empty[String, MsgTokenMapping]
    var byText = Map.empty[String, MsgTokenMapping]
    var keys = Set.empty[String]
    var maxSeq = 0L
    var last: Option[MsgTokenMapping] = None
    lines.foreach { line =>
      decode[MsgTokenMapping](line).toOption.foreach { m =>
        if !byToken.contains(m.token) then byToken = byToken + (m.token -> m)
        if !byText.contains(m.text) then byText = byText + (m.text -> m)
        m.mintKey.foreach(k => keys = keys + k)
        maxSeq = math.max(maxSeq, m.seq)
        last = Some(m)
      }
    }
    st.byToken = byToken
    st.byText = byText
    st.nextSeq = maxSeq + 1
    st.lastMinted = last
    st.seenKeys = keys
    st.loaded = true
    sidIndex.put(shortCode(sid), sid)

  private def readLinesSync(file: os.Path): List[String] =
    if !os.exists(file) then Nil
    else
      try os.read.lines(file).toList
      catch case _: Exception => Nil

  private def appendLineSync(file: os.Path, mapping: MsgTokenMapping): Unit =
    os.write.append(file, mapping.asJson.noSpaces + "\n", createFolders = true)

end MsgTokenRegistry

object MsgTokenRegistry:

  /** 牌面：{{msg:<sid8 hex8>-<seq 6 位>}}。 */
  val TokenRegex: Regex = """\{\{msg:([0-9a-f]{8})-(\d{6})\}\}""".r

  /** reminder 类别（context 族：请求级、非持久化、compact/ask 轮被注入闸抑制）。 */
  val ReminderCategory: String = "msgtoken"

  /** 全局单例（生产装配缺省；惰性绑定 PathUtil.dataRoot——测试应自建实例隔离）。 */
  lazy val global: MsgTokenRegistry = new MsgTokenRegistry(PathUtil.dataRoot / "sessions")

  /**
   * 热读 `msgTokens.enabled`（GlobalSafety.defaultMode 同款形态）：缺席 / 解析失败 /
   * 读盘异常 = 开（作者拍板③：默认开；与 plugins.enabled 同向——安全面不依赖默认关）。
   */
  def enabledFromConfig(): Boolean =
    try
      val p = PathUtil.configJsonReadPath(PathUtil.dataRoot)
      if !os.exists(p) then true
      else
        io.circe.parser
          .parse(os.read(p))
          .toOption
          .flatMap(_.hcursor.downField("msgTokens").downField("enabled").as[Boolean].toOption)
          .getOrElse(true)
    catch case _: Exception => true

  /** 映射缺失 = 显式报错行（作者拍板②：不静默错引、不回退路径引用）。 */
  def missingErrorLine(token: String): String =
    s"[msg-token 解析失败] $token：映射缺失——该身份牌不存在、所属会话已删除，或铸牌后映射已被清理。请改贴消息全文。"

  /** 真用户轮的牌宣告文本（system-reminder content）。 */
  def reminderContent(token: String): String =
    s"消息身份牌：本条用户消息已铸牌 $token。向其它会话派发（Mail / Delegate / node 消息）时在文本中写入该牌，" +
      s"接收方会在投递前自动展开为消息全文；映射缺失时接收方看到显式报错行。牌仅在本实例内有效，随会话归档保存。"

end MsgTokenRegistry

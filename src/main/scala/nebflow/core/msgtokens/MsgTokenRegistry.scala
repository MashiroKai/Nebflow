/* 消息身份牌机制（msgtokens 批，2026-10-31 作者四项拍板全推荐项）：
 * ①牌格式 {{msg:sid8-seq6}}（会话短码8 = sessionId 的 SHA-256 前 8 位十六进制，
 *   序号6 = 会话内零填充 6 位）②映射缺失 = 显式报错行注入（不静默错引、不回退路径引用）
 * ③msgTokens.enabled 默认开 ④社交腿（微信/飞书出站）整腿二期，本批只做引擎内。
 *
 * 带短名引用形态（msgchip 批，2026-11-01 作者令「消息身份牌系统升级」）：回复者可在
 * 牌面后接「|短名」写作 {{msg:sid8-seq6|短名}}——短名 = 回复者给该消息起的简短名字，
 * **agent 侧的引用抬头**（msgcard-agent-expand 批起：派发展开时进 `[引用 · <短名>]`
 * 抬头，见下），不进映射键、不参与消解语义：
 *   - 消解：带名形态与裸形态同值替换（目标 = 映射全文）；短名**只进抬头、不进正文**
 *     （正文恒为源消息全文逐字）。
 *   - 语法：短名字符集 = 除 `{` `}` `|` 换行外任意可见字符，1..40 字；超长/空名/
 *     坏 sid8/坏 seq = 不成牌（见下「畸形牌」条）。
 *   - 旧裸形态 {{msg:sid8-seq6}} 消解语义不变（铸造/消解/reminder 全链）。
 *
 * ## msgcard-agent-expand 批（2026-11-05 作者裁定「修：发给 agent 就展开全文」）
 *
 * 现场病灶：作者在 delegate brief / Mail 里写牌，delegate 收到的是**字面量**
 * （既没展开也没剥掉），回执时又原样吐出，作者在聊天里看到一串 `{{…}}`；另有
 * 作者发出的**格式残缺**写法（少 `msg:` 前缀），连解析机会都没有。
 *
 * 本批三条硬规则（面向 agent 的四条注入腿共用同一解析器 [[resolveForAgent]]）：
 *  ①**一律展开**：Mail / Delegate brief / node 消息 / node spawn 输入（+ Subagent
 *    与 Workflow 步的 prompt）在投递前把牌展开成**完整原文**，抬头带短名/作者/时间/
 *    字数，尾部带结束标记；**永不静默截断**（超长引用 = 全文保留 + 醒目注记）。
 *  ②**作者侧不变**：聊天窗的牌 chip 是前端渲染（web/js/chat/msgTokenChip.js），
 *    引擎只服务 agent 投递腿——`resolveForAgent` 的调用点全部在派发侧，聊天转录
 *    （会话 JSON 原文）一字不改，chip 渲染零回退。
 *  ③**失败必须显式**：映射缺失 / 格式残缺（如缺 `msg:` 前缀）/ 跨实例不可达 ⇒
 *    替换成 `[引用未能解析：<原 token>] 原因：…`，绝不原样递送；同时 WARN 一条
 *    可 grep 的诊断行（`MSG-TOKEN-FORMAT`）并计入 `malformedSeen` / `missingSeen`。
 *
 * 引擎侧纯机制：agent 拿不到任何消解工具——铸牌（真人输入入队/直投时急切铸）、
 * 经 system-reminder 管线向本会话宣告、六条投递组包点（MailTool 派发 / DelegateTool
 * brief / NodeDelivery 运行中注入+待启动补充 / NodeStarter.buildInput / BlockingSubagent
 * 子会话 prompt）投递前消解为完整原文块。牌为实例内机制：sid8 前缀 + 会话内序号，
 * 跨实例不可解；root 未见过的牌 = 映射缺失 = 显式标记。
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
  mintKey: Option[String] = None,
  /**
   * 原文作者（msgcard-agent-expand 批 2026-11-05）：铸牌时的发送者名。真人输入腿
   * 恒 None（= 作者本人，展开时写作「来自 作者」）；社交/转发腿可带名。缺席键
   * （旧行）解码为 None，行为兼容。
   */
  authorName: Option[String] = None
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
  def mint(
    sessionIdOpt: Option[String],
    text: String,
    dedupKey: Option[String] = None,
    authorName: Option[String] = None
  ): IO[Option[MsgTokenMapping]] =
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
                val mapping = MsgTokenMapping(token, sid, sid8, seq, text, System.currentTimeMillis(), dedupKey, authorName)
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

  // ── 查询（msgchip 批：GET /api/sessions/msg-token/{core} 只读端点的引擎腿）──

  /**
   * 按牌取映射（**只读**，铸牌零涉入）：入参接受裸核心 `sid8-seq6` 或完整牌面
   * `{{msg:sid8-seq6(|短名)?}}`——两种形态都剥到核心再查。惰性装载与消解同缝
   * （sessionStateFor miss 扫描 + loadSessionLocked），未铸/已删/畸形 = None。
   * 前端 chip popover 的跨会话取数腿走此查询（同会话前端本地优先，本查询是权威回退）。
   */
  def lookup(tokenOrCore: String): IO[Option[MsgTokenMapping]] =
    val core = tokenOrCore.trim match
      case MsgTokenRegistry.CoreRegex(sid8, seq) => s"$sid8-$seq"
      case _ =>
        MsgTokenRegistry.TokenRegex.findFirstMatchIn(tokenOrCore).flatMap { m =>
          for sid8 <- Option(m.group(1)); seq <- Option(m.group(2)) yield s"$sid8-$seq"
        }.getOrElse("")
    if core.isEmpty then IO.pure(None)
    else
      IO.blocking {
        sessionStateFor(core.take(8)) match
          case Some((sid, st)) =>
            st.lock.lock()
            try
              if !st.loaded then loadSessionLocked(sid, st)
              st.byToken.get(s"{{msg:$core}}")
            finally st.lock.unlock()
          case None => None
      }

  // ── 消解（六条 agent 投递组包点共用）──────────────────────────────────────

  /**
   * **面向 agent 的投递前消解（唯一权威入口）**：把文中每枚牌展开成**完整原文块**
   * （[[MsgTokenRegistry.expansionBlock]]）。三类输入各有确定结果：
   *
   *  - 严格牌面 `{{msg:sid8-seq6}}` / `{{msg:sid8-seq6|短名}}`：命中映射 ⇒ 抬头
   *    （短名 · 作者 · 时间 · 字数）+ 全文 + 结束标记；映射缺失（牌不存在 / 会话已删 /
   *    跨实例）⇒ `[引用未能解析：<原 token>] 原因：映射缺失…`（[[missingErrorLine]]）。
   *  - **格式残缺但显然是牌**（缺 `msg:` 前缀 / 序号非 6 位 / 空核心 / 多余空格等）⇒
   *    `[引用未能解析：<原 token>] 原因：格式残缺——…`（[[malformedErrorLine]]）
   *    + 一条 `MSG-TOKEN-FORMAT` WARN（发送方笔误的可诊断面）+ 计数。
   *  - 与牌无关的文本（含 `{{…}}` 的模板/代码片段）⇒ **逐字节原样**（零噪声）。
   *
   * **永不静默截断**：超长原文全文保留，只在抬头标注字数（> LongQuoteThreshold
   * 另标「超长引用·全文保留」）。作者侧聊天渲染不经此函数（前端 chip 面不变）。
   * 机制关闭（`msgTokens.enabled=false`）= 全量原样透传（零行为变化）。
   */
  def resolveForAgent(text: String): IO[String] =
    if text.isEmpty || !text.contains("{{") then IO.pure(text)
    else
      IO.blocking {
        if !enabledHot() then text
        else resolveSync(text)
      }

  /** 兼容别名（既有四条腿的调用名）；语义 = [[resolveForAgent]]。 */
  def resolve(text: String): IO[String] = resolveForAgent(text)

  /** 诊断计数：格式残缺牌被替换的次数（可观测面，测试断言 + 运维 grep）。 */
  private val malformedCount = new java.util.concurrent.atomic.AtomicLong(0)

  /** 诊断计数：映射缺失牌被替换的次数。 */
  private val missingCount = new java.util.concurrent.atomic.AtomicLong(0)

  def malformedSeen: Long = malformedCount.get()

  def missingSeen: Long = missingCount.get()

  /**
   * 幂等面（多腿复消解）：Mail 的 `node:` 腿先经 MailTool 消解、再在 NodeDelivery 消解
   * 一遍 ⇒ 同一段文本可能过两遍。已在显式标记里的原 token（`[引用未能解析：{{…}}]` 内）
   * 第二遍**原样保留**——不叠第二层标记（标记文案也刻意不含可再解析的花括号形态）。
   */
  private def insideMarker(text: String, start: Int): Boolean =
    val from = math.max(0, start - MsgTokenRegistry.UnresolvedMarker.length)
    text.substring(from, start).endsWith(MsgTokenRegistry.UnresolvedMarker)

  private def resolveSync(text: String): String =
    MsgTokenRegistry.BraceSpan.replaceAllIn(
      text,
      m =>
        val inner = Option(m.group(1)).getOrElse("")
        val written = m.matched
        if insideMarker(text, m.start) then written
        else MsgTokenRegistry.classifyInner(inner) match
          case MsgTokenRegistry.SpanShape.Exact(sid8, seq, shortName) =>
            expandExact(sid8, seq, shortName, written)
          case MsgTokenRegistry.SpanShape.Attempt(reason) =>
            malformedCount.incrementAndGet()
            logger.warn(
              s"MSG-TOKEN-FORMAT malformed card $written — $reason；已替换为显式标记（不原样递送）。" +
                s"正确写法：{{msg:<sid8 8位小写hex>-<seq 6位数字>}}，短名可选加 |短名。"
            )
            MsgTokenRegistry.malformedErrorLine(written, reason)
          case MsgTokenRegistry.SpanShape.Plain =>
            written
    )

  /** 严格牌面：查映射（带名形态与裸形态同键）。命中 ⇒ 原文块；缺失 ⇒ 显式标记。 */
  private def expandExact(sid8: String, seq: String, shortName: Option[String], written: String): String =
    val token = s"{{msg:$sid8-$seq}}"
    val mappingOpt = sessionStateFor(sid8) match
      case Some((sid, st)) =>
        st.lock.lock()
        try
          if !st.loaded then loadSessionLocked(sid, st)
          st.byToken.get(token)
        finally st.lock.unlock()
      case None => None
    mappingOpt match
      case Some(mapping) => MsgTokenRegistry.expansionBlock(mapping, shortName)
      case None =>
        missingCount.incrementAndGet()
        logger.warn(s"msg-token resolve MISS for $written — 注入显式标记（不原样递送）")
        MsgTokenRegistry.missingErrorLine(written)

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

  /**
   * 牌面：{{msg:<sid8 hex8>-<seq 6 位>}}（msgchip 批起容忍可选「|短名」后缀）。
   * 组1 = sid8，组2 = seq，组3 = 短名（可缺席）。本正则仍是**牌面语法的权威**：
   * 前端 chip 渲染、[[MsgTokenRegistry.lookup]] 与铸牌侧都以它为准。
   */
  val TokenRegex: Regex = """\{\{msg:([0-9a-f]{8})-(\d{6})(?:\|([^{}\n|]{1,40}))?\}\}""".r

  /** 裸核心（sid8-seq6）：[[MsgTokenRegistry.lookup]] 的入参形态之一。 */
  val CoreRegex: Regex = """^([0-9a-f]{8})-(\d{6})$""".r

  /** 短名上限（与正则 {1,40} 同源；前端牌面显示层再自行截断）。 */
  val ShortNameMaxLen: Int = 40

  /** reminder 类别（context 族：请求级、非持久化、compact/ask 轮被注入闸抑制）。 */
  val ReminderCategory: String = "msgtoken"

  /**
   * 超长引用门槛（字，msgcard-agent-expand 批）：超过只看抬头加「超长引用·全文保留」
   * 注记——**正文永不截断**（作者裁定：不许静默截断；真截断必须写明剩余字数）。
   */
  val LongQuoteThreshold: Int = 2000

  /** 解析失败标记的开头（幂等面：已在标记内的原 token 不二次包装）。 */
  val UnresolvedMarker: String = "[引用未能解析："

  /** 花括号跨度扫描（消解第一步）：`{{…}}`，跨度内禁 `{` `}` 换行，≤300 字。 */
  val BraceSpan: Regex = """\{\{([^{}\n]{1,300})\}\}""".r

  /** 严格牌面内核（BraceSpan 的组1 与整段比对）。 */
  private val ExactInner: Regex = """^msg:([0-9a-f]{8})-(\d{6})(?:\|([^{}\n|]{1,40}))?$""".r

  /** 残缺尝试①：只有牌核、缺 `msg:` 前缀（作者现场写法 `{{11c13555-000101}}`）。 */
  private val AttemptMissingPrefix: Regex = """^\s*[0-9a-fA-F]{6,17}\s*[-–_]\s*\d{1,12}(?:\s*\|.*)?\s*$""".r

  /**
   * 残缺尝试②：有 `msg:` 前缀但牌核残缺（坏 sid8 / 序号非 6 位 / 名非法 / 多余空格）。
   * 尾部 `.*$` 必须保留：[[classifyInner]] 用 `Regex.matches`（整段匹配、隐式锚尾），
   * 该判据是「前缀 + 牌核形状」的**形状检测**而非严格匹配。
   */
  private val AttemptPrefixedBroken: Regex = """(?i)^\s*msg\s*:[^\n]*?[0-9a-zA-Z]{0,17}\s*[-–_]\s*\d.*$""".r

  /** 残缺尝试③：只有 `msg:` 前缀、核心为空。 */
  private val AttemptEmptyCore: Regex = """(?i)^\s*msg\s*:\s*$""".r

  /** 花括号跨度的分类结果（[[classifyInner]] 的产物，纯函数、可单测）。 */
  enum SpanShape:
    /** 合法牌面：sid8 / 6 位序号 / 可选短名。 */
    case Exact(sid8: String, seq: String, shortName: Option[String])

    /** 显然是牌但写坏了：reason 是人类可读诊断。 */
    case Attempt(reason: String)

    /** 与牌无关的普通文本（模板/代码片段）——逐字节原样。 */
    case Plain

  /**
   * 花括号跨度分类（msgcard-agent-expand 批判据③的实现核心）：宁可放过普通
   * `{{…}}` 文本（Plain = 零噪声），也不许把**显然想写牌**的残缺串原样递给 agent。
   * 四条判据顺序固定：严格牌面 → 空核心 → 缺前缀 → 前缀在但核坏 → 其余 Plain。
   */
  def classifyInner(inner: String): SpanShape =
    inner match
      case ExactInner(sid8, seq, name) => SpanShape.Exact(sid8, seq, Option(name).filter(_.nonEmpty))
      case _ if AttemptEmptyCore.matches(inner) =>
        SpanShape.Attempt("牌面核心为空（`msg:` 后必须接 `sid8-seq6`）")
      case _ if AttemptMissingPrefix.matches(inner) =>
        SpanShape.Attempt("缺少 `msg:` 前缀（牌面 = 双花括号 + `msg:` + `sid8-seq6`）")
      case _ if AttemptPrefixedBroken.matches(inner) =>
        SpanShape.Attempt(
          "牌面核心格式不符：须 `msg:` + 8 位小写十六进制会话短码 + `-` + 6 位序号" +
            "（可选 `|短名`，短名 1-40 字、禁花括号/竖线/换行）"
        )
      case _ => SpanShape.Plain

  /**
   * 展开块（面向 agent 的唯一投递形态，判据①的可读面）：
   *
   * {{{
   * [引用 · 输入统一令 · 来自 作者 · 2026-11-05 20:19 · 共 37 字]
   * <源消息全文，逐字、不截断>
   * [引用结束 · 11c13555-000101 · 共 37 字]
   * }}}
   *
   * 抬头三段职责：短名（发送方给的引用名；缺省 = `消息 <core>`）、原作者、时间；
   * 字数两处（抬头 + 结束标记）便于收信方核对「有没有被截」。超长（> 门槛）时抬头
   * 追加「超长引用 N 字（全文保留，未截断）」——**正文恒为全文**。
   * 抬头/尾注只用裸核心 `<sid8>-<seq6>`（不用 `{{…}}` 花括号），避免二次消解。
   */
  def expansionBlock(mapping: MsgTokenMapping, shortName: Option[String]): String =
    val core = s"${mapping.sid8}-${f"${mapping.seq}%06d"}"
    val label = shortName.map(_.trim).filter(_.nonEmpty).map(n => s"引用 · $n").getOrElse(s"引用 · 消息 $core")
    val author = mapping.authorName.map(_.trim).filter(_.nonEmpty).getOrElse("作者")
    val when = formatTs(mapping.ts)
    val len = mapping.text.length
    val longNote = if len > LongQuoteThreshold then s" · 超长引用 ${len} 字（全文保留，未截断）" else ""
    s"[$label · 来自 $author · $when · 共 $len 字$longNote]\n${mapping.text}\n[引用结束 · $core · 共 $len 字]"

  /** 引用时间戳（epoch millis → `yyyy-MM-dd HH:mm`，系统默认时区）。 */
  def formatTs(ts: Long): String =
    java.time.Instant
      .ofEpochMilli(ts)
      .atZone(java.time.ZoneId.systemDefault())
      .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))

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

  /**
   * 映射缺失 = 显式标记（作者判定③：不静默放过原串、不回退路径引用）。形态固定为
   * `[引用未能解析：<原 token>] 原因：…`——收信方据此知道这里本该有一条引用。
   */
  def missingErrorLine(token: String): String =
    s"[引用未能解析：$token] 原因：映射缺失——该身份牌不在本实例的映射表中" +
      s"（牌不存在、所属会话已删除或被清理，或牌出自另一实例；牌仅铸牌实例内有效）。请让发送方改贴消息全文。"

  /** 格式残缺 = 显式标记 + 可诊断原因（发送方笔误面）。 */
  def malformedErrorLine(token: String, reason: String): String =
    s"[引用未能解析：$token] 原因：格式残缺——$reason。请让发送方按「双花括号 + msg:sid8-seq6」重写。"

  /** 真用户轮的牌宣告文本（system-reminder content）。msgchip 批：补一句短名引用
    * 形态的教学；msgcard-agent-expand 批：声明「发给 agent 一律展开全文」与
    * 「写坏了的牌会被换成 [引用未能解析] 标记」两条硬规则。 */
  def reminderContent(token: String): String =
    s"消息身份牌：本条用户消息已铸牌 $token。向其它会话派发（Mail / Delegate brief / node 消息 / 子代理 prompt）时在文本中写入该牌，" +
      s"接收方会在投递前自动展开为**完整原文**（抬头带短名/作者/时间/字数，正文逐字不截断）；映射缺失或格式残缺时接收方看到 `[引用未能解析：…]` 标记（不会看到裸牌，也绝不静默放过）。" +
      s"写法必须是 `{{msg:<sid8>-<seq6>}}`（少 `msg:` 前缀 = 格式残缺 = 被替换成显式标记）；牌仅在本实例内有效，随会话归档保存。" +
      s"在本会话回复里引用这条消息时，可在牌面后接「|短名」起个简短名字（如 {{msg:abcd1234-000001|输入统一令}}）——" +
      s"前端会把它渲染成显示短名的小牌，点击可看原文；派发文本里带短名亦可（进展开抬头）。"

end MsgTokenRegistry

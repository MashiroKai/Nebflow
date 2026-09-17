package nebflow.core.compact

import cats.effect.IO
import nebflow.agent.SharedResources
import nebflow.core.NebflowLogger
import nebflow.core.tools.MemoryQueue
import nebflow.service.{MemoryBudget, MemoryStore}
import nebflow.shared.*

import java.nio.charset.StandardCharsets

/**
 * Pre-compaction hook for the Root agent (Nebula, depth 0).
 *
 * Replaces DreamMode's idle timer: instead of a 5-minute polling cycle,
 * durable facts are extracted from the conversation right before it is
 * compacted.
 *
 * **事实抽取已停（promptgov 批 3 · 落法 ii「连机制一起停」）**：本 hook 不再做
 * dream 抽取——`extractFacts` 的 LLM 轮与 `DreamMode.DreamPrompt` 随 DreamMode
 * 机制一并退役（原因见下「本批停用面」）。本 hook 现存职责 = 压缩后置位整理提醒
 * 信号（[[nebflow.agent.MemoryHygieneSignal.markCompacted]]）。
 *
 * **本批停用面（逐条，`DreamMode` 机制连带）**：
 *   - 抽取轮（原 `extractFacts` + `DreamMode.DreamPrompt`）——已删；
 *   - 落点节名（原 `sectionOf`：user 面 `Some("## Dream Extract")`）——已删，
 *     队列条目的 `section` 恒 `None`（文件尾追加）；
 *   - DreamMode 侧的合并核 / 渲染器 / 节名常量 / T3 生命周期核——已删，
 *     详见 [[DreamMode]] 的对象注释。
 *
 * **待决（如实登记，本批未自选一边）**：生产者（抽取轮）停止后，本文件的
 * 入队辅助（`enqueueFacts`）与面余量分流（`decideRoute` / `faceRoom` /
 * `pendingBytesByFace`）在生产链路上**已无调用方**；它们连同其 spec
 * （[[nebflow.core.compact.NebulaMemoryHookRouteSpec]]）作为「队列写入面 + 面分流
 * 判据」的既有断言面保留，删除与否留待后续批（禁删既有测试文件的批次纪律之下，
 * 单方删除会把两枚 spec 一并架空）。
 *
 * ═══════════════════════════════════════════════════════════════════════
 * **2026-09-14 P0-c 批（作者 09-14 裁定 ③）——收窄 dream 生产者：按目标面 / 预算分流**
 * ═══════════════════════════════════════════════════════════════════════
 *
 * 改动前（现状）：`enqueueFacts` 不论目标面余量**一律** `target="user"` ⇒ 该面已满时
 * 每轮 dream 产出持续灌进队列、由 plan 侧的硬顶停点逐条扣发（`MemoryQueue.plan`
 * `:794-795`（逐条停点，`projected > caps._2 ⇒ stopped += n.target`）/
 * `:772`（后续同面全停，`stopped.contains(n.target) ⇒ WouldDefer`）；行号**现取**，非抄件）⇒
 * 队列只增不落（净消费≈0），
 * 到顶即按 `atMs` 自最老淘汰（淘汰顺序与价值无关）。
 *
 * 改动（三支，判词单一 = **面余量 ≥ 本条目字节**）：
 *   ① **该投 user 的仍投 user**（首选面；无落点节，见下「落点节」段，零行为漂移）；
 *   ② user 面无余量而 agent 面（`~/.nebflow/agents/Nebula/memory.md`）有余量 ⇒ **改投 agent 面**；
 *   ③ 两面皆无余量 ⇒ **停投**，且**逐条落 WARN 记录**（`[memory-route] code=drop-no-landable-face …`）。
 *
 * 🔴 **禁静默丢弃（作者要求 (b)）**：任何被分流放弃 / 被预算拒绝 / 入队失败的条目都逐条留痕
 * （改投 `code=reroute-budget`、停投 `code=drop-no-landable-face` 带条目全文、入队失败带 ref），
 * 判据 = **能逐条回答「这条为什么没落」**。记录用 `warnSync`（`warn` 返回 IO，在
 * `IO.blocking` 的裸语句位会被丢弃 = 死日志；本批同时修掉原实现那处死日志，见 `enqueueFacts`）。
 *
 * **「不做会怎样」（作者要求 (a)）**：不给分流则每轮 dream 产出持续灌向已满的 `User.md`，
 * 队列只增不落（诊断时点 207/500、净消费≈0），到顶即自最老淘汰。
 *
 * ── 判据的同源与边界 ────────────────────────────────────────────────────
 * 面余量读数（**只读**、零写记忆文件、零新建文件/节）：
 *   `硬顶 − (现文件字节 + 该面 pending 追加字节)`
 * 硬顶取 [[MemoryBudget]]（**判据同源、数值不复制；本批 MemoryBudget 零改动**）。
 * pending 计入的理由 = 与 [[MemoryQueue.plan]] 的模拟同序：plan 从**现文件内容**出发、
 * 按序累加 pending 后比对硬顶 ⇒ 只看文件现值会把「已被前序 pending 占满」的面误判为
 * 有余量，投进去仍是**注定被扣发**（正是本次要消除的现状）。
 * 保守口径：pending 只累加 `append`（**不**减 remove/replace_section 的收缩）⇒ 只会更早
 * 停投，不会把注定被扣发的条目投进去。
 *
 * **为什么不按 fact 类别分面**：抽取轮自带的五个类别（USER_PREFERENCE / ROUTING_RULE /
 * ENVIRONMENT / PATTERN / DECISION）与两个面的**现场内容面互相重叠**——`User.md` 有
 * `## 工具环境`、`## 工作风格`、`## 产品决策`，`agents/Nebula/memory.md` 也有
 * `## 工作风格（用户强烈偏好）`、`## 工具系统`、`## Scala / Pekko 编程坑` ⇒ 类别→面是一张
 * **无源码支撑的杜撰映射**；且既有断言把 PATTERN/DECISION 钉在 user 面
 * （`MemoryTrackSpec`「facts 入队…」逐字要求 `target==Vector("user")`，
 * 本批**禁弱化既有断言**）。故按**面余量**分流 —— 即诊断件 §7 P0-c 的原话
 * 「对满文件停投或改投有余量的层」，验证口径「`pending by trigger` 中 dream 占比下降」。
 *
 * **项目面（`project:<name>`）为何不在分流目标内**：[[MemoryQueue]] 的
 * `targetLayerHasFile` 确实支持 `project:<name>`（源码可达），但本 hook 的入参
 * （`run(messages, agentName, sessionId, teamName, resources)`，调用点
 * `AgentCore.scala:439-443` 传 `teamName = None`）**不含项目身份** ⇒ 从本 hook 无法解析
 * 目标项目；把项目身份接进来要改 hook 签名 + 调用链（扩面，本批禁）⇒ 本批分流目标面 =
 * user / agent 两枚，项目面登记为**后续批**（需作者裁定的接口面改动）。
 *
 * **落点节 = 无（`None`，两枚面一律）**：引擎不再拥有任何具名节——DreamMode 停用后
 * `## Dream Extract` 节既不被创建也不被瞄准（常数已删）。`section=None` 的 `append`
 * 落在目标文件**文件尾**，节归属由整理 agent 按自己的方法论重排（提示词纪律，非机制闸）。
 * 理由：带**缺节**的 append 会被 plan 判 `locate-miss: section not found — retryable`
 * **永不能落**；而具名节已随本批退役（「系统默认创建文件时也不要有这个 section」）。
 * **已知后果（如实登记）**：旧 `User.md` 的存量 `## Dream Extract` 节**不迁移**（无接收方），
 * 其内容按普通文件内容处理；T3 兜底淘汰（14 天 TTL / 60 条 FIFO）随机制停用而消失。
 *
 * **本批零改动面**：`MemoryBudget`（硬顶/软线/verdict 一律未动）、记忆文件本体
 * （`User.md` / `agents/<agent>/memory.md` / 项目 memory）、`queue.jsonl`（读写口径仍在
 * [[MemoryQueue]]，本 hook 只调它的公开 `enqueue`）。
 */
object NebulaMemoryHook extends PreCompactionHook:
  private val logger = NebflowLogger.forName("nebflow.prehook.nebula")

  /** 入队 actor 标识（引擎侧派生：抽取者身份）。 */
  val Actor: String = "dream"

  // ── P0-c：目标面 / 预算分流（2026-09-14 裁定 ③）────────────────────────

  /** User.md 面（queue target 词汇，与 [[MemoryQueue]] 同源）。 */
  private val UserFace: String = "user"

  /** `~/.nebflow/agents/Nebula/memory.md` 面（[[MemoryQueue.targetLayerHasFile]] 亦以
    * Nebula 解析 "agent" 面 ⇒ 判据不另起一套）。 */
  private val AgentFace: String = "agent"

  /** 分流记录的稳定机读码（日志 grep / 证据引用用；判词不随文案漂移）。 */
  private[nebflow] object RouteCode:
    /** 首选面（user）有余量 ⇒ 原样投（**零记录**：既有行为，零噪声）。 */
    val Preferred = "preferred-face"

    /** 首选面满、agent 面有余量 ⇒ 改投 agent 面（记录）。 */
    val Reroute = "reroute-budget"

    /** 面余量读数不可得 ⇒ fail-open 到首选面（记录；plan 侧预算闸仍是权威闸）。 */
    val FailOpen = "fail-open-unreadable"

    /** 两面皆无余量 ⇒ 停投（**非静默**：逐条 WARN 记录，带条目全文）。 */
    val Drop = "drop-no-landable-face"

  /** 分流裁决（纯值）。 */
  private[nebflow] enum RouteDecision:
    /** `face` = 落点面；`normal = false` ⇒ 该条分流/偏差**必落记录**。
      * 无落点节字段：引擎不再拥有具名节（见类头注「落点节」段），条目恒文件尾追加。 */
    case Send(face: String, code: String, normal: Boolean)

    /** 两面皆无余量 ⇒ 停投；`userRoom`/`agentRoom` = 现场余量读数（≤ 0 = 无余量/已超顶）。 */
    case Drop(code: String, userRoom: Long, agentRoom: Long)

  /** **纯函数分流裁决**（零 IO，可直测）：首选面 = user（现状默认）；备用面 = agent。
    *
    * `faceRoom(face)` = 该面余量字节（`Some` = 读数 / `None` = 读不到）。判据一律
    * 「本条目字节 ≤ 余量」。
    *
    * **读数不可得绝不丢**（fail-open 到首选面 + 落记录）：丢一条不可复得的事实比投进一个
    * 可能在 plan 侧被扣发的面更坏，且 plan 侧预算闸仍是权威闸（本函数只是**生产者侧**止损）。
    * ⇒ `Drop` 只可能在**两面都测到无余量**时发生。 */
  private[nebflow] def decideRoute(entryBytes: Long, faceRoom: String => Option[Long]): RouteDecision =
    def fits(face: String): Option[Boolean] = faceRoom(face).map(room => entryBytes <= room)
    fits(UserFace) match
      case Some(true) => RouteDecision.Send(UserFace, RouteCode.Preferred, true)
      case None       => RouteDecision.Send(UserFace, RouteCode.FailOpen, false)
      case Some(false) =>
        val userRoom = faceRoom(UserFace).getOrElse(0L)
        fits(AgentFace) match
          case Some(true) =>
            RouteDecision.Send(AgentFace, RouteCode.Reroute, false)
          case Some(false) =>
            RouteDecision.Drop(RouteCode.Drop, userRoom, faceRoom(AgentFace).getOrElse(0L))
          case None =>
            RouteDecision.Send(UserFace, RouteCode.FailOpen, false)

  /** 面余量读数（**只读**，零写记忆文件、零新建）：`硬顶 − (现文件字节 + 该面 pending 追加字节)`。
    * 硬顶取 [[MemoryBudget]]（同源）；目标文件不存在 ⇒ `None`（A′「零新建」口径：不投也不建）；
    * 读数异常 ⇒ `None`（fail-open，由 [[decideRoute]] 处置）。 */
  private[nebflow] def faceRoom(face: String, pendingBytes: Long): Option[Long] =
    try
      val path =
        if face == UserFace then MemoryStore.userMemoryPath
        else MemoryStore.agentMemoryPath("Nebula")
      val hard =
        if face == UserFace then MemoryBudget.UserHardBytes
        else MemoryBudget.AgentHardBytes
      if !os.exists(path) then None
      else Some(hard - (utf8Bytes(os.read(path)) + pendingBytes))
    catch case _: Exception => None

  /** 各面 pending 追加字节（一次读全队列、逐面求和；口径见类头注「判据的同源与边界」：
    * 只累加 `append`，不减收缩 ⇒ 保守）。读不到 ⇒ 空表（⇒ [[faceRoom]] 传 0，
    * fail-open 由 [[decideRoute]] 处置）。 */
  private[nebflow] def pendingBytesByFace(): Map[String, Long] =
    try
      MemoryQueue
        .pendingNotes()
        .filter(n => n.action == "append" && n.content.exists(_.nonEmpty))
        .groupBy(_.target)
        .view
        .mapValues(ns => ns.map(n => utf8Bytes(n.content.getOrElse("")) + 1L).sum)
        .toMap
    catch case _: Exception => Map.empty[String, Long]

  /** 条目识别子（sha256 前缀 12 hex；与 [[DreamMode.entryHash]] 同源 ⇒ 同文本可跨批对齐）。 */
  private[nebflow] def refOf(text: String): String = DreamMode.entryHash(text).take(12)

  private def utf8Bytes(s: String): Long =
    s.getBytes(StandardCharsets.UTF_8).length.toLong

  // ── 入队 ────────────────────────────────────────────────────────────────

  def run(
    messages: List[Message],
    agentName: String,
    sessionId: Option[String],
    teamName: Option[String],
    resources: SharedResources
  ): IO[Unit] =
    if messages.size < 20 then IO.unit
    else
      // 抽取轮已停（见类头注「事实抽取已停」）：本 hook 不再发 LLM 请求、不再入队。
      // 生命周期触发（§6.2-2.5）保留：压缩完成后置位整理提醒信号 —— 消费方
      // ContextRefresher.buildMemoryBlock 在压缩后的首个 Nebula 注入里带
      // 「T2/T3 清扫提示」。仅置位一行。
      IO(nebflow.agent.MemoryHygieneSignal.markCompacted())

  /** facts 逐条**先按面余量分流**（P0-c）、再入队：落点 `append` + `- [CATEGORY] text`
    * （类别以 in-band 形式写进条目；`section` 恒 `None` = 文件尾追加，见类头注「落点节」段，
    * 类别信息不丢）。
    *
    * ⚠️ **生产链路当前无调用方**（抽取轮停用后）：本方法与面余量分流
    * （`decideRoute`/`faceRoom`/`pendingBytesByFace`）作为「队列写入面 + 面分流判据」的
    * 既有断言面保留，删除与否留待后续批（见类头注「待决」段）。
    *
    * 记录面（🔴 禁静默丢）：改投 / fail-open / 停投 / 入队失败**逐条** `warnSync`；
    * 停投记录**带全文**（该条未入队 ⇒ 这行是它唯一的存在面 ⇒ 才能「逐条回答为什么没落」）；
    * 改投记录不带全文（内容已在队列留痕：`queue.jsonl` + [[nebflow.core.tools.MemoryHistory]]）。
    *
    * 记录一律 `warnSync`：[[NebflowLogger]] 的 `warn` 返回 `IO[Unit]`，在 `IO.blocking` 的
    * 裸语句位会被丢弃（死日志家族，`scripts/check-dead-logging.sh` 的已知边界外）。 */
  private[nebflow] def enqueueFacts(
    facts: List[String],
    sessionId: Option[String]
  ): IO[Unit] =
    IO.blocking {
      val pendingBytes = pendingBytesByFace()
      def room(face: String): Option[Long] = faceRoom(face, pendingBytes.getOrElse(face, 0L))
      facts.flatMap(DreamMode.parseFact).distinct.foreach { (cat, text) =>
        val content = s"- [$cat] $text"
        val bytes   = utf8Bytes(content) + 1L
        decideRoute(bytes, room) match
          case RouteDecision.Send(face, code, normal) =>
            if !normal then
              logger.warnSync(
                s"[memory-route] code=$code target=$face " +
                  s"section=<file-tail> ref=${refOf(text)} bytes=$bytes cat=$cat"
              )
            MemoryQueue.enqueue(
              target = face,
              action = "append",
              // 无具名节（引擎不再拥有节）：文件尾追加。
              section = None,
              matchText = None,
              content = Some(content),
              sessionId = sessionId,
              trigger = MemoryQueue.TriggerDream,
              actor = Actor
            ) match
              case Right(_) => ()
              case Left(reason) =>
                // 入队失败 = 这条也没落 ⇒ 逐条留痕（原判词保留 + 识别子）
                logger.warnSync(
                  s"Fact enqueue failed: $reason (ref=${refOf(text)} bytes=$bytes cat=$cat target=$face)"
                )
          case RouteDecision.Drop(code, userRoom, agentRoom) =>
            logger.warnSync(
              s"[memory-route] code=$code ref=${refOf(text)} bytes=$bytes cat=$cat " +
                s"userRoom=$userRoom agentRoom=$agentRoom " +
                s"(hard: user=${MemoryBudget.UserHardBytes} agent=${MemoryBudget.AgentHardBytes}) text=$text"
            )
      }
    }

end NebulaMemoryHook

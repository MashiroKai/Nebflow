package nebflow.gateway

import cats.effect.{IO, Ref}
import cats.syntax.all.*
import io.circe.Json
import nebflow.core.SessionStore
import nebflow.shared.{NebflowLogger, UiMessage}

import scala.concurrent.duration.FiniteDuration

/**
 * 进程级 ProcessRecorder（process-recorder 批 2026-11-01 F1，设计稿
 * `docs/design/20261101_192000_process-recorder-design__delegate-nebflow-24e5c4c3`）。
 *
 * == 病根与方案（设计稿 §0/§2.1 案 A） ==
 * 面板正文（ai/tool/thinking 三类 `.ui.json` 行）的写者曾经是「腿的行李」——只有
 * 恰好被塞了录制 send（`makeRecordingWsSend`）的腿才落盘，六条腿里三条（项目引擎
 * 启动挂载 ④、boot 重挂 ⑤、设备腿 ⑥）的帧「出进程就没人录」。本类把录制升格为
 * **进程的基础设施**：挂在 `wsHub.registerListener` 监听者位（全部腿的帧无论谁
 * 派出、有没有连接，最后都汇聚的**唯一**广播点），按帧内 `nodeSessionId` 懒建缓冲、
 * 按既有行规则落盘——连接无关、腿无关、与挂载腿的生灭彻底解耦。
 *
 * == 单写者移交（设计稿 §2.3，禁共存） ==
 * agent* 四分支从 `makeRecordingWsSend` **整体迁除**（同一提交），录制唯一写者 =
 * 本类；root 帧族仍由既有包装按 ctor 键落盘（根会话录制健康，F1 不陪跑）。共存
 * 必然双写（同一条 agent 帧落两次 ⇒ 面板双气泡），禁任何「临时共存加去重」方案；
 * 回潮 = P1 源码钉红。
 *
 * == 语义单一来源（R4，逐字搬运禁改写） ==
 * 四分支的缓冲/冲刷/终态语义**逐字**来自 `makeRecordingWsSend`（迁除前
 * WebSocketRoutes.scala :2947-3070 的 agentTextDelta / agentThinking / agentToolEnd /
 * agentDone）：落行粒度 = 工具轮（`agentToolEnd` 前冲刷正文：Ai 行在上、Tool 行在
 * 下；`agentDone` 落 terminal=true 终态 Ai 行或段感知 `markRoundTerminal` 回填），
 * `team-` 前缀规整语义同源（MailTool.activateAgent 的前端路由标记不是真会话 id）。
 * 本类是这些语义的**唯一**宿主，F3（如做）复用同一投影，禁第三份。
 *
 * == 崩溃安全（§2.4）与背压（§2.5） ==
 * 写路径全走 `SessionStore.appendUiMessages` / `markRoundTerminal` 既有面（500ms
 * 去抖 + `AtomicJson` tmp+ATOMIC_MOVE rename——崩溃可丢尾批行、绝不损文件；不引入
 * per-frame fsync，链台账纪律不适用于流式 ui 行）。listener 在 `broadcast` 内联
 * 执行 ⇒ 快路径只做「type 嗅探」（未在册类型一次 cursor 读即返回）；逐 token 的
 * delta 只累积内存 Ref，不产生同步磁盘 IO；失败纪律 = 全吞只 WARN，**绝不反噬
 * 广播**（与 wrapper :3146 同律；WsHub 对监听者本就 handleError 吞）。
 *
 * == 孤儿缓冲（§2.2 F1 内容③） ==
 * 崩溃/断流残留（`agentTextDelta` 等累积后永不到终态帧）由孤儿扫描兜底：周期扫
 * `lastTouchMs`，超 idle 阈值（缺省 24h）的会话把残余 text/thinking 冲刷成 Ai 行
 * （与 toolStart 冲刷同形，不写 terminal——轮从未完成，无 duration/model 事实）
 * 后清除缓冲。[[launchOrphanSweep]] 由 GatewayMain boot 链 `.start`（ttlScanner
 * 同款 sleep 循环形态），进程生命周期。
 *
 * 注册点：GatewayMain boot 链 `recorderSetup`（先于 `startupMount`——boot 重挂帧
 * 从恢复第一帧起就有录制）；进程生命周期，**永不注销**（WsHub 两注册表共用 id
 * 空间，不持 id 即无注销面）。
 */
class ProcessRecorder(sessionStore: SessionStore):

  private val logger = NebflowLogger.forName("nebflow.process-recorder")

  // ── per-会话缓冲（键 = 规整后 nodeSessionId；语义同 wrapper 既有三缓冲）──────
  /** Per-session accumulator for text deltas (emitted one-by-one, saved on tool round / done). */
  private val textBuffers: Ref[IO, Map[String, String]] =
    Ref.unsafe[IO, Map[String, String]](Map.empty)

  /** Per-session accumulator for thinking deltas. */
  private val thinkingBuffers: Ref[IO, Map[String, String]] =
    Ref.unsafe[IO, Map[String, String]](Map.empty)

  /** Per-session turn start time — set on first streaming event, consumed on agentDone. */
  private val turnStarts: Ref[IO, Map[String, Long]] =
    Ref.unsafe[IO, Map[String, Long]](Map.empty)

  /** Per-session last-touched timestamp — the orphan sweep's idle clock（孤儿扫兜底专用读数）. */
  private val lastTouchMs: Ref[IO, Map[String, Long]] =
    Ref.unsafe[IO, Map[String, Long]](Map.empty)

  // ── 耗时读数（R1：listener 内联，警戒 p99 > 1ms；挂载点 /api/health 邻位留作者裁）──
  private val framesHandled = new java.util.concurrent.atomic.AtomicLong(0)
  private val totalNanos    = new java.util.concurrent.atomic.AtomicLong(0)
  private val maxNanos      = new java.util.concurrent.atomic.AtomicLong(0)

  def stats: IO[ProcessRecorder.Stats] = IO.delay(
    ProcessRecorder.Stats(framesHandled.get(), totalNanos.get(), maxNanos.get())
  )

  private def noteElapsed(elapsedNanos: Long): Unit =
    framesHandled.incrementAndGet()
    totalNanos.addAndGet(elapsedNanos)
    maxNanos.getAndUpdate(m => if elapsedNanos > m then elapsedNanos else m)

  /**
   * Hub 监听者入口（签名与 `wsHub.registerListener` 对齐）。
   * 快路径 = 一次 `type` 嗅探：未在册帧（root 帧族 / 面板控制帧 / Flow Map 事件…）
   * 零缓冲触碰即返回（微秒级内联纪律，§2.5）。
   */
  def apply(json: Json): IO[Unit] =
    json.hcursor.downField("type").as[String].getOrElse("") match
      case "agentTextDelta" | "agentThinking" | "agentToolEnd" | "agentDone" => record(json)
      case _                                                                 => IO.unit

  /**
   * 在册四帧的落盘语义——**逐字**迁自 `makeRecordingWsSend` 的 agent* 分支（单一
   * 来源，见类注）：归属键 = 帧内 `nodeSessionId`（`team-` 前缀规整），无键/空键
   * 的帧一律忽略；`agentToolEnd` 前冲刷正文（Ai 在上、Tool 在下），`agentDone`
   * 落 terminal Ai 行或段感知 `markRoundTerminal` 回填。全程吞错只 WARN。
   */
  private def record(json: Json): IO[Unit] =
    val hc = json.hcursor
    val eventType = hc.downField("type").as[String].getOrElse("")
    // "team-" is a frontend routing marker injected by MailTool.activateAgent,
    // not a real session id. Strip it so Mail-activated team agent streams
    // persist to the team session's own ui.json instead of a phantom
    // "team-<id>" session.（语义逐字自 wrapper 迁入，禁两处漂移）
    def normalizeNodeSessionId(nsid: String): String =
      if nsid.startsWith("team-") then nsid.drop(5) else nsid
    val nodeSessionId =
      hc.downField("nodeSessionId").as[String].toOption.filter(_.nonEmpty).map(normalizeNodeSessionId)
    val record0: IO[Unit] = eventType match
      case "agentTextDelta" =>
        val delta = hc.downField("delta").as[String].getOrElse("")
        nodeSessionId match
          case Some(nsid) if delta.nonEmpty =>
            // Record turn start for this flow agent so agentDone can compute a
            // duration for the ✻ duration badge on its final AI message.
            turnStarts
              .update(m => if m.contains(nsid) then m else m.updated(nsid, System.currentTimeMillis())) *>
              textBuffers.update(m => m.updatedWith(nsid)(_.map(_ + delta).orElse(Some(delta))))
          case _ => IO.unit

      case "agentThinking" =>
        // Mark turn start on the first thinking token too (some turns emit
        // thinking before any text). Also accumulate the delta into the
        // sub-agent's own thinkingBuffers so agentToolEnd/agentDone
        // can persist thinking into ui.json.
        nodeSessionId match
          case Some(nsid) =>
            val delta = hc.downField("delta").as[String].getOrElse("")
            val recordThinking =
              if delta.nonEmpty then
                thinkingBuffers.update(m => m.updatedWith(nsid)(_.map(_ + delta).orElse(Some(delta))))
              else IO.unit
            recordThinking *> turnStarts
              .update(m => if m.contains(nsid) then m else m.updated(nsid, System.currentTimeMillis()))
          case None => IO.unit

      case "agentToolEnd" =>
        val label = hc.downField("label").as[String].getOrElse("")
        nodeSessionId match
          case Some(nsid) if label.nonEmpty =>
            val summary = hc.downField("summary").as[String].getOrElse("")
            val content = hc.downField("content").as[String].getOrElse("")
            val isError = hc.downField("isError").as[Boolean].getOrElse(false)
            val input = hc.downField("input").as[Json].getOrElse(Json.Null).noSpaces
            // Flush any accumulated text + thinking before recording the tool, so the AI
            // message bubble (with thinking) appears above the tool card in history.
            textBuffers
              .modify(m => (m - nsid, m.getOrElse(nsid, "")))
              .flatMap { text =>
                thinkingBuffers
                  .modify(m => (m - nsid, m.getOrElse(nsid, "")))
                  .flatMap { thinking =>
                    val flushMsg =
                      if text.nonEmpty then
                        sessionStore.appendUiMessages(
                          nsid,
                          List(
                            UiMessage.Ai(
                              text,
                              None,
                              None,
                              Option.when(thinking.nonEmpty)(thinking),
                              System.currentTimeMillis()
                            )
                          )
                        )
                      else IO.unit
                    flushMsg *> sessionStore.appendUiMessages(
                      nsid,
                      List(UiMessage.Tool(label, summary, content, isError, input))
                    )
                  }
              }
          case _ => IO.unit
        end match

      case "agentDone" =>
        nodeSessionId match
          case Some(nsid) =>
            // Flush any remaining accumulated text + thinking as a final AI bubble, with
            // duration (from the turn start) and model so the popup renders the
            // ✻ duration badge on the agent's last reply.
            val model = hc.downField("model").as[Option[String]].getOrElse(None)
            turnStarts
              .modify(m => (m - nsid, m.getOrElse(nsid, 0L)))
              .flatMap { startTime =>
                val durationMs = if startTime > 0 then Some(System.currentTimeMillis() - startTime) else None
                textBuffers
                  .modify(m => (m - nsid, m.getOrElse(nsid, "")))
                  .flatMap { text =>
                    thinkingBuffers
                      .modify(m => (m - nsid, m.getOrElse(nsid, "")))
                      .flatMap { thinking =>
                        val thinkingOpt = Option.when(thinking.nonEmpty)(thinking)
                        if text.nonEmpty || thinkingOpt.isDefined then
                          sessionStore.appendUiMessages(
                            nsid,
                            // replay-terminal 批（2026-10-28，案一「A 根治」）：
                            // agentDone = 节点会话轮终态——terminal=true 与
                            // durationMs 同批落盘（弹窗回放腿同判据）。
                            List(UiMessage.Ai(text, durationMs, model, thinkingOpt, System.currentTimeMillis(), terminal = Some(true)))
                          )
                        else
                          // No text (flushed earlier by agentToolEnd) — backfill
                          // model + timestamp onto the round's last carrier. We no
                          // longer gate on durationMs.isDefined: when turnStarts
                          // was never set (startTime=0 → durationMs=None), team agent
                          // AI messages still need model and timestamp so the popup
                          // renders the badge (model name + time). durationMs stays
                          // None when unknown; the frontend falls back to a simpler
                          // badge (model + timestamp, no thinking phrase).
                          // replay-terminal 批：agentDone 亦为轮终态——回填批一律
                          // 盖 terminal=true。卫生批（2026-10-28，残余①根治）：改走
                          // 段感知 markRoundTerminal——段内零 ai 行的退化全工具轮盖
                          // 末行 Tool（等价终态面），不再越段改写上一轮的行。
                          sessionStore.markRoundTerminal(
                            nsid,
                            durationMs,
                            model,
                            System.currentTimeMillis()
                          )
                        end if
                      }
                  }
              }
          case _ => IO.unit
        end match

      case _ => IO.unit

    // 触碰读数 + 耗时读数 + 失败纪律（吞错只 WARN，绝不反噬广播——wrapper :3146 同律）。
    nodeSessionId.fold(IO.unit)(nsid =>
      lastTouchMs.update(m => m.updated(nsid, System.currentTimeMillis()))
    ) *> {
      val t0 = System.nanoTime()
      record0
        .handleErrorWith(e =>
          logger.warn(s"[process-recorder] failed to record node session frame ($eventType): ${e.getMessage}")
        )
        .flatMap(u => IO.delay(noteElapsed(System.nanoTime() - t0)).as(u))
    }
  end record

  // ── 孤儿缓冲扫描（F1 内容③；设计稿 §2.2）───────────────────────────────────

  /**
   * 冲刷并清除 idle 超过 `maxIdleMs` 的残留缓冲。冲刷形态 = toolStart 前冲刷同形
   * （Ai 行携带 thinking；**不写 terminal**——轮从未完成，无 duration/model 事实）。
   * 返回被收殓的会话数。
   */
  def sweepOrphans(maxIdleMs: Long, nowMs: Long = System.currentTimeMillis()): IO[Int] =
    lastTouchMs.modify { m =>
      val stale = m.collect { case (id, ts) if nowMs - ts > maxIdleMs => id }.toList
      (m -- stale, stale)
    }.flatMap { stale =>
      if stale.isEmpty then IO.pure(0)
      else
        turnStarts.update(_ -- stale) *>
          stale.traverse_ { id =>
            for
              text <- textBuffers.modify(m => (m - id, m.getOrElse(id, "")))
              thinking <- thinkingBuffers.modify(m => (m - id, m.getOrElse(id, "")))
              _ <-
                if text.nonEmpty || thinking.nonEmpty then
                  sessionStore.appendUiMessages(
                    id,
                    List(
                      UiMessage.Ai(
                        text,
                        None,
                        None,
                        Option.when(thinking.nonEmpty)(thinking),
                        System.currentTimeMillis()
                      )
                    )
                  )
                else IO.unit
            yield ()
          }.as(stale.size)
    }

  /**
   * 孤儿扫描睡眠循环（ttlScanner 同款形态；`*> IO.defer(loop)` 显式延迟到执行期，
   * ProjectActor.ttlScanner 的 SOE 先例）。由 GatewayMain boot 链 `.start` 起纤维，
   * 失败只 WARN 不中断循环。
   */
  def launchOrphanSweep(interval: FiniteDuration, maxIdle: FiniteDuration): IO[Unit] =
    def loop: IO[Unit] =
      IO.sleep(interval) *>
        sweepOrphans(maxIdle.toMillis)
          .handleErrorWith(e =>
            logger.warn(s"[process-recorder] orphan sweep failed: ${e.getMessage}").void
          ) *>
        IO.defer(loop)
    loop

end ProcessRecorder

object ProcessRecorder:
  /** 耗时读数（R1 读数面；/api/health 邻位挂载点留作者裁——设计稿开放项 #3）。 */
  final case class Stats(framesHandled: Long, totalNanos: Long, maxNanos: Long)
end ProcessRecorder

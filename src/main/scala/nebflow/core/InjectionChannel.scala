package nebflow.core

import cats.effect.IO
import io.circe.{Json, JsonObject}
import io.circe.syntax.*
import nebflow.shared.{NebflowLogger, UiMessage}

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import scala.collection.mutable

/**
 * InjectionChannel —— 外部注入行（浅蓝气泡）的**唯一投递入口**
 * （外部注入统一通道批 2026-11-05，作者 08:44 报障逐字：「所有的外部注入消息应该有
 * 统一的途径和蓝色气泡通知，我刚刚看到你好多条消息触发你，我都看不到是什么」）。
 *
 * == 缺口（改前事实表，逐条可核）==
 * 「一条外部注入到达前端」原本由**多个腿各写一份**：
 *   · `AgentActor#emitInjectedUserEvent`（agent 侧，13 处调用点）—— 手拼帧 JSON +
 *     `appendUiMessages` + `wsSend`；
 *   · `ScheduledTaskActor#injectScheduleCard` / `#injectTodoDigest`（core 侧，2 处）
 *     —— 各自手拼另一份帧 JSON + 另一处 `appendUiMessages` + 另一处 `broadcast`。
 * 三份实现意味着「落盘了但没广播」「广播了但没落盘」「字段拼错漏一腿」三型缺口
 * 各自独立漂移，且新增投递腿无处可循 ⇒ 前端永远会漏（作者口径「没有统一入口，
 * 前端就永远漏」）。
 *
 * == 本件做什么（只做通道与可见化，不做注入时机）==
 *  1. **单点投递**：`deliver` = 去重闸 → 落盘（`appendUiMessages`）→ 广播（`wsSend`）。
 *     帧形态（`frame`）= 改前 `emitInjectedUserEvent` 的逐 keys 同形（字段序不变、
 *     缺席即不落键），因此既有消费者的读数逐字节不变。
 *  2. **去重（防蓝泡刷屏）**：见 [[dedupKey]] / [[admit]]。键 = 会话 × 来源 ×
 *     事件类型 × 发送方 × 正文指纹；**同一键在窗口内重复 ⇒ 折叠（只留第一条）**，
 *     正文一变（状态变化）⇒ 指纹变 ⇒ 照常追加。默认窗口 [[DefaultWindowMs]]（10min），
 *     `-Dnebflow.inject.dedup.windowMs` 可覆盖（测试/演练用）。
 *  3. **不做的事**：不改任何注入时机（回合末 drain / 工具边界注入的判定不在本件）、
 *     不改行语义（`UiMessage.User` 字段一字未动）、不新造协议键。
 *
 * == 与既有纪律的接缝 ==
 *  · 去重是**呈现级**补充，不替代 `DeliveredReceiptLedger`（那是投递级去重，挡的是
 *    同一事件两次进 LLM 上下文）；两层正交，本件只挡「同一条通知第二次出现在气泡流里」。
 *  · `dedup` 由**调用点显式表态**（默认 false）：只有「外部事件可见化」四站点置 true。
 *    Mail / skill / ask 等族保持改前行为逐字不变（同一发送方重复正文在 Mail 面是
 *    合法业务,不得被呈现层吞掉）。
 *  · 落盘判据沿用旧录制层口径：`text` 非空才落盘（空文本注入只广播不落盘）。
 */
object InjectionChannel:

  private val logger = NebflowLogger.forName("nebflow.core.injection-channel")

  /** Read-only diagnostic; never records event payloads. Disable with -Dnebflow.injection.decisionLog=false. */
  def logSuppressed(
    sessionId: String,
    source: String,
    eventType: String,
    sourceId: String,
    gate: String,
    basis: JsonObject
  ): IO[Unit] =
    if sys.props.get("nebflow.injection.decisionLog").contains("false") then IO.unit
    else
      val row = Json.obj(
        "timestampMs" -> System.currentTimeMillis().asJson,
        "sessionId" -> sessionId.asJson,
        "source" -> source.asJson,
        "eventType" -> eventType.asJson,
        "sourceId" -> sourceId.asJson,
        "gate" -> gate.asJson,
        "basis" -> Json.fromJsonObject(basis)
      )
      logger.info(s"[injection-decision] ${row.noSpaces}").handleError(_ => ())

  /** 投递结果（审计/测试读数面）。 */
  enum Outcome:
    /** 已落盘 + 已广播。 */
    case Emitted

    /** One or both delivery legs failed; successful append is cache acceptance, not a disk flush. */
    case Failed(appendFailed: Boolean, broadcastFailed: Boolean)

    /** 去重命中 ⇒ 本轮**零落盘零广播**（同一条通知的第二、三次）。 */
    case Collapsed
  end Outcome

  /** 去重窗口缺省值：10 分钟（同一成员同一状态的重试提示按 care tick 只留一条）。 */
  val DefaultWindowMs: Long = 10L * 60L * 1000L

  /** 窗口上限的取值面（system property 优先；缺省 [[DefaultWindowMs]]）。 */
  val WindowProp: String = "nebflow.inject.dedup.windowMs"

  /** 生效窗口（毫秒）；<= 0 = 关闸（逐条放行，用于变异验红）。 */
  def windowMs: Long =
    sys.props.get(WindowProp).flatMap(v => v.trim.toLongOption).getOrElse(DefaultWindowMs)

  /** 记账表上限（超限丢最旧条目 ⇒ 进程内存有界，长跑实例不会无界增长）。 */
  val MaxEntries: Int = 512

  /**
   * 去重键：`会话 ∪ 来源 ∪ 事件类型 ∪ 发送方 ∪ 发送团队 ∪ 收件判别 ∪ 已渲染 header
   * ∪ 正文指纹`。
   *
   * 判据（作者口径「同名成员 + 同一状态只留一条，状态变化才追加」）：
   *  · **成员身份** 落在 `sender` / `header` 段（delegate 通知的 sender = 子代理名，
   *    merge-queue 的 header 携成员与状态段）；
   *  · **状态** 落在 `eventType` + **正文指纹**段 —— 状态变化 ⇒ 正文必然变化
   *    （payload 携带原因/分支/tip 等） ⇒ 指纹变 ⇒ 追加；同状态重试 ⇒ 正文逐字相同
   *    ⇒ 命中键 ⇒ 折叠。
   */
  def dedupKey(sessionId: String, row: UiMessage.User): String =
    val parts = List(
      sessionId,
      row.source.getOrElse(""),
      row.eventType.getOrElse(""),
      row.sender.getOrElse(""),
      row.senderTeam.getOrElse(""),
      row.intake.getOrElse(""),
      row.header.getOrElse(""),
      fingerprint(row.text)
    )
    parts.mkString("\u0000")

  /** 正文指纹（SHA-256 前 8 字节的十六进制；纯函数、可复算、不依赖 JDK 版本差异）。 */
  def fingerprint(text: String): String =
    val md = MessageDigest.getInstance("SHA-256")
    val bytes = md.digest(text.getBytes(StandardCharsets.UTF_8))
    bytes.take(8).map(b => String.format("%02x", Integer.valueOf(b & 0xff))).mkString

  /** 去重记账（进程内；`key -> 首次放行时刻`，插入序 = 淘汰序）。 */
  private val seen: mutable.LinkedHashMap[String, Long] = mutable.LinkedHashMap.empty

  /**
   * 去重闸（纯逻辑 + 记账）：true = 放行（并记时刻），false = 折叠。
   *
   * 过期条目在每次调用时就地清理（窗口滑动）；表满则按插入序淘汰最旧条目。
   */
  def admit(key: String, now: Long = System.currentTimeMillis()): Boolean =
    val win = windowMs
    if win <= 0 then true
    else
      seen.synchronized {
        purge(now - win)
        seen.get(key) match
          case Some(_) => false
          case None =>
            if seen.size >= MaxEntries then seen.remove(seen.head._1)
            seen.put(key, now)
            true
      }

  /** 清理所有早于 `cutoff` 的条目（调用方持锁）。 */
  private def purge(cutoff: Long): Unit =
    val stale = seen.iterator.takeWhile(_._2 < cutoff).map(_._1).toList
    stale.foreach(seen.remove)

  /** 当前记账条目数（测试读数）。 */
  def size: Int = seen.synchronized(seen.size)

  /** 清空记账（测试隔离；生产零调用方）。 */
  def resetForTest(): Unit = seen.synchronized(seen.clear())

  /**
   * 注入帧（WS `user` 帧）：**改前 `AgentActor#emitInjectedUserEvent` 的逐 keys 同形**
   * ——键序 = type / text / injected / source / sessionId / eventType / sender /
   * senderTeam / delivery / intake / header / fileChanges / askAddress，其后接
   * `extra`（调用点专属段，如定时任务卡的 `timestamp` + `schedule`）。缺席即不落键
   * （旧帧字节形态逐字不变；intake/header 先例同款条件编码）。
   */
  def frame(sessionId: String, row: UiMessage.User, extra: JsonObject = JsonObject.empty): Json =
    val base = Json.obj(
      "type" -> "user".asJson,
      "text" -> row.text.asJson,
      "injected" -> true.asJson,
      "source" -> row.source.getOrElse("").asJson,
      "sessionId" -> sessionId.asJson
    )
    val withEt = row.eventType.fold(base)(et => base.deepMerge(Json.obj("eventType" -> et.asJson)))
    val withSender = row.sender.fold(withEt)(s => withEt.deepMerge(Json.obj("sender" -> s.asJson)))
    val withTeam = row.senderTeam.fold(withSender)(t => withSender.deepMerge(Json.obj("senderTeam" -> t.asJson)))
    val withDelivery = row.delivery.fold(withTeam)(d => withTeam.deepMerge(Json.obj("delivery" -> d.asJson)))
    val withIntake = row.intake.fold(withDelivery)(i => withDelivery.deepMerge(Json.obj("intake" -> i.asJson)))
    val withHeader = row.header.fold(withIntake)(h => withIntake.deepMerge(Json.obj("header" -> h.asJson)))
    val withDiff = row.fileChanges.fold(withHeader)(fc => withHeader.deepMerge(Json.obj("fileChanges" -> fc)))
    // ask-mail 批（2026-11-16）：ask 会话的 Mail 续聊地址——缺席即不落键
    // ⇒ 非 ask 帧字节形态逐字不变。
    val withAskAddr = row.askAddress.fold(withDiff)(a => withDiff.deepMerge(Json.obj("askAddress" -> a.asJson)))
    if extra.isEmpty then withAskAddr else withAskAddr.deepMerge(Json.fromJsonObject(extra))
  end frame

  /** 落盘面（窄端口 ⇒ 本件可在无 gateway 的测试里用真 `SessionStore` 或假实现直测）。 */
  type Store = SessionStorePort

  /** 广播面（gateway `wsHub.broadcast` / 会话 `wsSend` 同签名）。 */
  type Send = Json => IO[Unit]

  /**
   * **唯一投递入口**：去重闸 → 落盘 → 广播。
   *
   * 纪律（逐条与改前单腿行为等价，禁回退）：
   *  · 落盘失败/广播失败各自 WARN 并返回 Failed，不向上抛（注入路径不因呈现面失败中断 turn）；
   *  · 顺序恒为「先落盘、后广播」（live 帧时序与 `.ui.json` 行序同前）；
   *  · `text` 为空 ⇒ 只广播不落盘（旧录制层同款判据）；
   *  · `dedup = true` 且命中 ⇒ 零落盘零广播（[[Outcome.Collapsed]]）。
   *
   * @param extra 调用点专属帧段（缺省空；定时任务卡传 `timestamp` + `schedule`）
   */
  def deliver(
    store: Store,
    send: Send,
    sessionId: String,
    row: UiMessage.User,
    extra: JsonObject = JsonObject.empty,
    dedup: Boolean = false
  ): IO[Outcome] =
    val hasText = row.text.nonEmpty
    val admitted =
      if dedup && hasText then admit(dedupKey(sessionId, row))
      else true
    if !admitted then
      logSuppressed(
        sessionId,
        row.source.getOrElse(""),
        row.eventType.getOrElse(""),
        row.sender.getOrElse(""),
        "injection-window-dedup",
        JsonObject("windowMs" -> windowMs.asJson, "fingerprint" -> fingerprint(row.text).asJson)
      ).as(Outcome.Collapsed)
    else
      val persist =
        if hasText then
          IO.defer(store.appendUiMessages(sessionId, List(row))).attempt.flatMap {
            case Right(_) => IO.pure(false)
            case Left(e) => logger.warn(s"injected user event persist failed: ${e.getMessage}").as(true)
          }
        else IO.pure(false)
      persist.flatMap { appendFailed =>
        IO.defer(send(frame(sessionId, row, extra))).attempt.flatMap {
          case Right(_) => IO.pure(if appendFailed then Outcome.Failed(true, false) else Outcome.Emitted)
          case Left(e) =>
            logger
              .warn(s"injected user event failed: ${e.getMessage}")
              .as(Outcome.Failed(appendFailed, true))
        }
      }
    end if
  end deliver

end InjectionChannel

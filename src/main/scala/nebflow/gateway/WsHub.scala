package nebflow.gateway

import cats.effect.{IO, Ref}
import cats.syntax.all.*
import io.circe.Json
import nebflow.core.{EventSink, WsHubPort}

/**
 * WebSocket multicast hub — decouples root agents from individual connections.
 * All WebSocket connections register their per-connection send callback here;
 * root agents broadcast events to every registered connection.
 */
// Phase 5 解耦:混入 core 窄端口(实现原地不搬;core 的 TaskStuckWatcher 只面向
// broadcast 这一个成员编程)。
//
// == 扇出成本(perf-481 A1) ==
// 注册表存的是「已序列化文本 → 写该连接」的回调,而**不是** `Json => IO[Unit]`。
// 原因:`broadcast` 原先把同一个 `Json` 交给 N 个回调,每个回调各自跑一遍
// `json.noSpaces` ⇒ 单次事件的序列化成本 ∝ N(实测 N=16 时 p50 31.3 ms,帧 93 551 B)。
// 序列化移到本方法内**只做一次**,每连接只剩 `WebSocketFrame.Text(str)` 包装 +
// 入队 ⇒ 成本与 N 解耦。语义零变化(同一帧、同一字节序列,只是不再重算 N 次)。
//
// 🔴 本次只落这一半(R1a)。**未**实现按会话/项目过滤(R1b):连接 ↔ 会话归属是
// 契约面,无契约下自行过滤会错投/漏投(计划 OD-3 裁定后批)。
//
// == 两类注册表(缓存带分类,perf-481 A2 前置) ==
// ① **连接**(`register`):只负责把字节写出去,收 (已序列化文本, 是否流式帧)。
//    「是否流式帧」正是出站队列溢出策略(计划 OD-1)要用的分类 ⇒ 由本中枢在
//    **每个事件算一次**(与序列化同一处提升),而不是让 N 个连接各判一遍。
// ② **事件监听者**(`registerListener`):要**读**事件字段(TurnEndpoint 的完成信号、
//    GatewayMain 的 bridge 派发)⇒ 仍收 `Json`。两类语义不同,故两个入口,
//    禁把二者并成一个「什么都收」的回调。
class WsHub extends EventSink, WsHubPort:

  private val connsRef: Ref[IO, Map[String, (String, Boolean) => IO[Unit]]] =
    Ref.unsafe[IO, Map[String, (String, Boolean) => IO[Unit]]](Map.empty)

  private val listenersRef: Ref[IO, Map[String, Json => IO[Unit]]] =
    Ref.unsafe[IO, Map[String, Json => IO[Unit]]](Map.empty)

  /** Register a connection; returns a handle for later unregister.
   *
   *  @param wsSend takes the **already serialized** frame text plus the frame
   *                class (`isStreamFrame`) — see the class note: serialization
   *                and classification are the hub's job, done once per event.
   */
  def register(wsSend: (String, Boolean) => IO[Unit]): IO[String] =
    val id = java.util.UUID.randomUUID().toString.take(8)
    connsRef.update(_ + (id -> wsSend)) *> IO.pure(id)

  /** Register a non-connection event listener (inspects the event JSON). */
  def registerListener(onEvent: Json => IO[Unit]): IO[String] =
    val id = java.util.UUID.randomUUID().toString.take(8)
    listenersRef.update(_ + (id -> onEvent)) *> IO.pure(id)

  /** Remove a connection. */
  def unregister(id: String): IO[Unit] =
    connsRef.update(_ - id)

  /** Broadcast a JSON message to every registered connection.
   *
   *  The JSON is serialized ONCE here (and its frame class computed once) and
   *  the same string is handed to every connection — the per-connection work is
   *  frame construction + offer only.
   */
  def broadcast(json: Json): IO[Unit] =
    // Lazy: an instance with only listeners registered never pays the
    // serialization cost (and the common case still pays it exactly once).
    lazy val text = json.noSpaces
    lazy val isStream = WsHub.isStreamFrame(json)
    connsRef.get.flatMap { conns =>
      conns.values.toList.traverse_(send => send(text, isStream).handleErrorWith(_ => IO.unit))
    } *> listenersRef.get.flatMap { ls =>
      ls.values.toList.traverse_(send => send(json).handleErrorWith(_ => IO.unit))
    }

  /** Send to a single connection by its handle. */
  def sendTo(id: String, json: Json): IO[Unit] =
    val text = json.noSpaces
    val isStream = WsHub.isStreamFrame(json)
    connsRef.get.flatMap(_.get(id).traverse_(send => send(text, isStream).handleErrorWith(_ => IO.unit)))
end WsHub

object WsHub:

  /**
   * **流式增量帧类型集**(计划 OD-1 的分级依据 / A2 溢出策略)。
   *
   * 判据口径 = 该帧是否为「可丢的流式增量」:它承载的是一段持续被后续帧**取代**
   * 的正文/思考增量 ⇒ 丢最旧不损失终态(失的是中间态)。集中在此一处,禁各调用点
   * 自列一份(第二份判据 = 漂移源)。
   *
   * 🔴 未在册的类型一律按**非流式**处理(= 控制/状态帧,超限即断开该连接):这是
   * fail-closed 方向 —— 宁可断开也不静默丢控制帧(丢一帧 `configUpdated` 会让前端
   * 陷入「已保存」假象,而丢一段思考增量最多少显示几个字)。
   */
  private val StreamFrameTypes: Set[String] = Set(
    "textDelta",
    "thinkingDelta",
    "thinkingSignature",
    "agentThinking",
    "toolArgDelta",
    "askTextDelta"
  )

  /** Frame class of an event: true = droppable streaming increment. */
  def isStreamFrame(json: Json): Boolean =
    json.hcursor.downField("type").as[String].toOption.exists(StreamFrameTypes.contains)

  // ── 出站溢出观测量(计划 §5.2/A2 的「溢出计数非零」面)──────────────────
  // 只增不减的进程级计数:慢订阅者触发出站队列满时 +1。与日志并存(日志可能被
  // 采样,计数器是机械可读面 —— 探针/规格直接读它)。
  private val outboundOverflowDrops = new java.util.concurrent.atomic.AtomicLong(0)
  private val outboundOverflowCloses = new java.util.concurrent.atomic.AtomicLong(0)

  /** Frames evicted (oldest-first) because a connection's outbound queue was full. */
  def overflowDropCount: Long = outboundOverflowDrops.get()

  /** Connections torn down because a NON-stream frame hit a full outbound queue. */
  def overflowCloseCount: Long = outboundOverflowCloses.get()

  private[gateway] def noteOverflowDrop(): Unit = outboundOverflowDrops.incrementAndGet()
  private[gateway] def noteOverflowClose(): Unit = outboundOverflowCloses.incrementAndGet()
end WsHub


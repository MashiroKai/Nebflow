package nebflow.gateway

import cats.effect.{IO, Ref}
import cats.syntax.all.*
import io.circe.Json
import io.circe.syntax.*
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
//
// 🔴 **两个注册表共用同一个 id 空间,卸载面必须是同一个 `unregister`**(A1 返工)。
//    见 [[unregister]] 的判词 —— 只摘一个面 = 每次 headless turn 永久漏一个
//    监听者,是随进程存活期单调增长的功能+性能双回归。
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

  /** Remove a registration — **both** faces.
   *
   *  == 为什么必须两面都摘（perf-481 A1 返工；先前只摘 `connsRef`）==
   *  两个注册表共用**同一个 id 空间**（`register` / `registerListener` 都是
   *  `UUID.take(8)`），且调用侧**只持有一个 id**、只调 `unregister`。只摘
   *  `connsRef` ⇒ `listenersRef` 里的条目**永不消失**：`TurnEndpoint` 每个 headless
   *  turn 注册一个监听者，turn 结束调 `unregister` ⇒ 每个 turn 永久漏一个回调，
   *  并在**此后每一次 broadcast** 被调用（实测 20 turn ⇒ 20 个实例零回收；8000
   *  泄漏 ⇒ 扇出 p50 0.067 → 3.442 ms）。这是**随进程存活期单调增长**的
   *  功能+性能双回归，测试网结构性抓不到（无断言覆盖「注销后不再被调用」）。
   *
   *  ⇒ 本方法 = 两个注册表的**唯一卸载面**（对称性靠机械保证，不靠调用侧记性）。
   *  `-` 对不存在的键是 no-op ⇒ 幂等（既有 504 路径与测试的重复注销语义不变）。
   */
  def unregister(id: String): IO[Unit] =
    connsRef.update(_ - id) *> listenersRef.update(_ - id)

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

  // ── 注册面读数(perf-481 A1 的机械判据面)──────────────────────────────
  // 🔴 这两个读点是 A1 的**验收面本身**,不是调试便利:「注销后不再被调用」必须
  // 能被外部机械观测(测试 / 探针),否则同型回归(卸载面不对称)结构性抓不到。

  /** Live connection count. */
  def connectionCount: IO[Int] = connsRef.get.map(_.size)

  /**
   * Live listener count. A steady-state process holds exactly the boot-time
   * listeners (`GatewayMain`'s bridge dispatch); a headless turn adds one for
   * its duration only. A count that grows once per turn = the A1 leak.
   */
  def listenerCount: IO[Int] = listenersRef.get.map(_.size)

  // ── 出站溢出观测量(计划 §5.2/A2 的「溢出计数非零」面)──────────────────
  // 🔴 **实例级**(非 object 级 static):读数面与「哪个 hub 实例」一一对应,
  // 测试可造一个独立 hub 断言自己的计数;/api/health/conn 读的就是生产实例这一个。
  // 只增不减的进程存活期计数:慢订阅者触发出站队列满时 +1。
  private val outboundOverflowDrops = new java.util.concurrent.atomic.AtomicLong(0)
  private val outboundOverflowCloses = new java.util.concurrent.atomic.AtomicLong(0)

  /**
   * Outbound-queue overflow readings — the mechanical read point for A2's
   * criterion 「溢出计数非零 ∧ 队列长度有界」.
   *
   * @param capacity      per-connection outbound queue bound, in frames
   * @param overflowDrops droppable (stream) frames evicted to make room
   * @param overflowCloses connections torn down because a control frame could
   *                      not be delivered without dropping another control frame
   */
  def outboundStats(capacity: Int): WsHub.OutboundStats =
    WsHub.OutboundStats(
      capacity = capacity,
      overflowDrops = outboundOverflowDrops.get(),
      overflowCloses = outboundOverflowCloses.get()
    )

  /**
   * [[outboundStats]] as the JSON fragment the readout endpoints merge in.
   * Single construction point: `/api/health/conn` and any future readout share
   * this shape, so a field cannot appear on one face and drift on another.
   */
  def outboundStatsJson(capacity: Int): Json =
    val s = outboundStats(capacity)
    Json.obj(
      "capacity" -> s.capacity.asJson,
      "overflowDrops" -> s.overflowDrops.asJson,
      "overflowCloses" -> s.overflowCloses.asJson
    )

  private[gateway] def noteOverflowDrop(): Unit = outboundOverflowDrops.incrementAndGet()
  private[gateway] def noteOverflowClose(): Unit = outboundOverflowCloses.incrementAndGet()
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

  /** See [[WsHub.outboundStats]]. */
  final case class OutboundStats(capacity: Int, overflowDrops: Long, overflowCloses: Long)
end WsHub

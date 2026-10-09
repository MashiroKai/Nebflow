package nebflow.core.delegate

import io.circe.Json
import io.circe.syntax.*
import nebflow.core.AtomicJson
import nebflow.shared.PathUtil

import java.util.concurrent.atomic.AtomicReference

/**
 * ChainStore —— 新轻量 per-task 链存储（⑤A 定稿，worktree-judge-fifo 批 #1224
 * 增补 §3.5：**本批建存储本体＋写入行**）.
 *
 * 与 [[DelegateRegistry]] 对偶的持久账本：每 task 一个小 JSON 文件
 * （`<dataRoot>/chains/<taskId>.json`，跨重启存活），内容 = 按时间序的
 * **事件行**（`kind` + `at` + 自由 `payload`）。本批写入三行族：
 *
 *   - `delegate-spawn`     —— delegate spawn 行（branch / worktree / cwd / 基分支）
 *   - `terminal-verdict`   —— 终态三态判定行（completed / blocked / ready-to-merge）
 *   - `merge-queue`        —— 合并队列状态行（enqueued / reminder-sent / merged / …）
 *
 * 上层消费（N1 于本存储之上落整链登记/S3/S4 校验面；#1228 per-task 视图）只依赖
 * 本文件的**通用行形态**，不感知三行族的语义——新行种类零迁移。
 *
 * 纪律（镜像 DelegateRegistry）：
 *   - 读 = 每次现读盘（热读）；坏形/缺失 → None / Nil，绝不抛；
 *   - 写 = 追加-重写-原子落盘（[[AtomicJson.writeSync]]，读-改-写单点收口在本
 *     object 内，进程内经 [[writeLock]] 串行——量级 = 每 task 一次 delegate 生命
 *     周期几行，无性能面）;
 *   - `FlowMapStore / FlowMapEventLog / ChainLedger` **不在本件维护范围**——照
 *     #1229 清单删（⑤A：不选 B/C 案），本件与旧三件零引用。
 */
object ChainStore:

  /** 一条事件行。`payload` 自由 JSON——行种类自己的契约，本件不解读。 */
  final case class Entry(kind: String, at: Long, payload: Json)

  /** 一个 task 的链记录（文件形态 = 本 case class 的 JSON）。 */
  final case class Record(taskId: String, entries: List[Entry])

  private def dir: os.Path = PathUtil.dataRoot / "chains"

  private def fileOf(key: String): os.Path = dir / s"$key.json"

  /** 键安全：复用 DelegateRegistry 的文件名白名单（防路径穿越）。 */
  def validKey(key: String): Boolean = DelegateRegistry.validId(key)

  /**
   * 存储键归一：有台账号用台账号（task #N 的持久锚），派发期台账失败
   * （fail-soft）时回落 delegate 会话 id——两形态都落同一命名空间，行语义由
   * payload 自带（N1 的整链登记面在此之上按 kind 关联）。
   */
  def keyFor(taskId: Option[String], delegateId: String): String =
    taskId.map(_.trim).filter(k => k.nonEmpty && validKey(k)).getOrElse(delegateId)

  // 进程内串行化：读-改-写的临界区（文件级锁的轻量替代；跨进程无锁——与
  // DelegateRegistry 同一口径：单实例进程内写面）。
  private val writeLock = new Object

  /** 追加一行（同步原子落盘）。键非法 / 落盘失败 = 静默容错（WARN 已在调用侧）：
    * 账本是审计/下游数据源，绝不阻断 delegate 主链路。 */
  def appendSync(key: String, kind: String, payload: Json): Unit =
    if !validKey(key) then ()
    else
      writeLock.synchronized {
        try
          os.makeDir.all(dir)
          val current = readSync(key).getOrElse(Record(key, Nil))
          val updated = current.copy(entries = current.entries :+ Entry(kind, System.currentTimeMillis(), payload))
          AtomicJson.writeSync(fileOf(key), updated.asJson.noSpaces)
        catch case _: Throwable => ()
      }

  /** [[appendSync]] 的 IO 形态（blocking 池）。 */
  def append(key: String, kind: String, payload: Json): Either[Throwable, Unit] =
    scala.util.Try(appendSync(key, kind, payload)).toEither

  /** 现读一个 task 的链记录（坏形/缺失 → None）。 */
  def readSync(key: String): Option[Record] =
    if !validKey(key) then None
    else
      try
        val p = fileOf(key)
        if !os.exists(p) then None
        else io.circe.parser.parse(os.read(p)).toOption.flatMap(_.as[Record].toOption)
      catch case _: Throwable => None

  /** 全量列出（按首行时间倒序；坏文件跳过）。 */
  def listSync(): List[Record] =
    try
      if !os.exists(dir) then Nil
      else
        os.list(dir)
          .filter(_.ext == "json")
          .flatMap(p => io.circe.parser.parse(os.read(p)).toOption.flatMap(_.as[Record].toOption))
          .toList
          .sortBy(_.entries.headOption.map(-_.at).getOrElse(Long.MaxValue))
    catch case _: Throwable => Nil

  // ── circe 编解码（字段即 case class 形态；Entry.kind/at 是承重字段） ──
  given io.circe.Encoder[Entry] = io.circe.generic.semiauto.deriveEncoder
  given io.circe.Decoder[Entry] = io.circe.generic.semiauto.deriveDecoder
  given io.circe.Encoder[Record] = io.circe.generic.semiauto.deriveEncoder
  given io.circe.Decoder[Record] = io.circe.generic.semiauto.deriveDecoder

end ChainStore

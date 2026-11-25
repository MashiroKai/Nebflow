package nebflow.core.tools

import cats.effect.IO
import cats.syntax.all.*
import io.circe.Json
import nebflow.dropbox.ChunkedTransfer
import nebflow.shared.AttachContract

import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

import scala.concurrent.duration.*

/**
 * BrowseGetCursor —— 浏览取件**分块拉取腿**的收端游标（M2 · 设计 v1 §7 M2-1）。
 *
 * 作者令「Windows 下载进度条」的正主腿：改前浏览取件 = `RemoteExecutor.transferGetScoped`
 * 单请求整件 base64（无分块/无进度/无断点/无整件校验）；本游标把**接收侧**改为
 * 「逐块拉取 + 逐块自算摘要校验 + 落盘断点续传 + 整件 sha256 收口」。
 *
 * == 协议（零 wire 破坏） ==
 *
 *   - 请求：既有 `FileTransfer direction=get, scope=browse` 追加**可选**键
 *     `chunkIndex` / `chunkSize` / `wantWholeSha256`。旧对端忽略未知键 ⇒ 走 legacy
 *     整件 get（响应无 `chunkSha256` ⇒ 本侧识别为 legacy 回应，整件消费，零回归）。
 *   - 响应（分块形态）：`{content, size(=整件字节数), chunkIndex, chunkBytes,
 *     chunkSha256(对端自算), totalChunks, wholeSha256?(wantWholeSha256=1 时)}`。
 *   - 校验纪律与分块 put 腿同源（`ChunkedTransfer` 契约）：**本侧（接收端）自算**块
 *     摘要与整件摘要，对端回传值只作比对基准（禁自证）；整件摘要不符 ⇒ 删落盘件。
 *
 * == 取消/进度 ==
 *
 *   - 拉取协议下**对端从不自发推字节**——每块都由本侧请求 ⇒ 取消 = 停止发下一块
 *     （Q2 开放点「新增专用 op」在拉取形态下退化为本地旗标；WS `fileTransferCancel`
 *     只翻旗标，对端零新消息族）。粒度 = 块间（≤ 一块的多余字节在途）。
 *   - 进度 = 每块落盘后回调（`onProgress(bytes, total, lastChunkMs)`）；**权威读数
 *     = 落盘实际长度**，禁本地假进度（与附件腿「服务端确认才推进」同纪律）。
 *
 * == 零超时纪律 ==
 *
 * 本游标**没有整体超时**：慢链路 = 块变小 + 每块一次有界往返（发送侧死线由
 * `AttachContract.chunkDeadline` 派生），失败可见、可续传重试；永不定时杀整件。
 */
object BrowseGetCursor:

  /** 对端单块响应的解析形态（分块与 legacy 整件两态）。 */
  final case class ChunkReply(
    chunked: Boolean,
    chunkIndex: Int,
    chunkBytes: Int,
    chunkSha256: String,
    totalBytes: Long,
    totalChunks: Long,
    wholeSha256: Option[String],
    contentB64: String
  )

  /**
   * 解析对端 `direction=get` 响应。判据 = `chunkSha256` 在场 ⇒ 分块形态；缺席 ⇒
   * legacy 整件（`content`+`size`）。解析不出结构化字段 = 显式错误（禁静默）。
   */
  def parseReply(json: Json): Either[String, ChunkReply] =
    val hc = json.hcursor
    hc.downField("chunkSha256").as[String].toOption.filter(_.nonEmpty) match
      case Some(sha) =>
        for
          idx <- hc.downField("chunkIndex").as[Int].toOption.toRight("reply missing chunkIndex")
          cbytes <- hc.downField("chunkBytes").as[Int].toOption.toRight("reply missing chunkBytes")
          total <- hc.downField("size").as[Long].toOption.toRight("reply missing size")
          content <- hc.downField("content").as[String].toOption.toRight("reply missing content")
          chunks = hc.downField("totalChunks").as[Long].toOption.getOrElse(0L)
        yield ChunkReply(
          chunked = true,
          chunkIndex = idx,
          chunkBytes = cbytes,
          chunkSha256 = sha,
          totalBytes = total,
          totalChunks = chunks,
          wholeSha256 = hc.downField("wholeSha256").as[String].toOption.filter(_.nonEmpty),
          contentB64 = content
        )
      case _ =>
        for
          total <- hc.downField("size").as[Long].toOption.toRight("reply missing size")
          content <- hc.downField("content").as[String].toOption.toRight("reply missing content")
        yield ChunkReply(
          chunked = false,
          chunkIndex = 0,
          chunkBytes = 0,
          chunkSha256 = "",
          totalBytes = total,
          totalChunks = 1,
          wholeSha256 = None,
          contentB64 = content
        )
    end match
  end parseReply

  /**
   * 取消哨兵（拉取循环被 `fileTransferCancel` 停止时的显式失败值；消费方据此出
   * 「已取消」终态帧，而不是通用错误）。
   */
  val Cancelled: String = "browse fetch cancelled by user"

  /** 整件摘要不符的显式错误（落盘件已删——与分块 put 收端同纪律）。 */
  def wholeMismatch(declared: String, computed: String): String =
    s"WHOLE_DIGEST_MISMATCH: declared $declared, receiver computed $computed (local pickup deleted)"

  /** 块摘要不符的显式错误（不写盘）。 */
  def chunkMismatch(index: Int, declared: String, computed: String): String =
    s"CHUNK_DIGEST_MISMATCH at chunk $index: declared $declared, computed $computed"

end BrowseGetCursor

/**
 * 接收侧游标（进程内、无持久化状态）：**权威 offset = 落盘实际长度**（恢复纪律与
 * 分块 put 收端同款，不信任何持久化字段）。所有写盘走「先截断到块边界、再追加」——
 * 非整块尾（上次中断）物理不可续传的坑在 put 腿已修过（xferb P0-2），本腿同款。
 *
 * @param target      落盘件（`PointerPickup.pickupPath` 单点算名）
 * @param totalBytes  整件字节数（对端首响应供给）
 * @param chunkSize   块大小（本侧拉取节奏，进程内自适应；不上 wire 判据）
 */
final class BrowseGetCursor(
  val target: os.Path,
  val totalBytes: Long,
  val chunkSize: Int
):
  private var bytes: Long = 0L
  private var declaredWhole: String = ""

  /** 已落盘字节数（权威 offset）。 */
  def bytesReceived: Long = bytes

  /** 对端整件摘要（会话内获得后非空）。 */
  def wholeSha256: String = declaredWhole

  def noteWholeSha(sha: String): Unit =
    if sha.nonEmpty then declaredWhole = sha

  /** 期望的下一块 index（由落盘长度推导；禁本侧自由填 offset——同 put 契约）。 */
  def expectedIndex: Int = AttachContract.indexForOffset(bytes, chunkSize)

  /** 恢复：读落盘实际长度并夹到整件内（非整块尾在下次 `applyChunk` 截断）。 */
  def restore(): Unit =
    val onDisk = if os.exists(target) && os.isFile(target) then os.size(target) else 0L
    bytes = math.min(onDisk, totalBytes)

  /**
   * 应用一块：**先校验（本侧自算）、再落盘、后推进**。幂等（index < 期望）⇒ no-op；
   * gap（index > 期望）⇒ 显式错误（顺序拉取下不该发生，发生 = 协议错）。返回新 offset。
   */
  def applyChunk(index: Int, payload: Array[Byte], declaredChunkSha: String): Either[String, Long] =
    val expected = expectedIndex
    if index < expected then Right(bytes) // 幂等重放：不写不报
    else if index > expected then
      Left(s"OFFSET_OUT_OF_RANGE: chunk $index arrives, expected $expected (bytesReceived=$bytes)")
    else
      val computed = ChunkedTransfer.sha256Hex(payload)
      if computed != declaredChunkSha then Left(BrowseGetCursor.chunkMismatch(index, declaredChunkSha, computed))
      else
        val offset = AttachContract.offsetForIndex(index, chunkSize)
        val planned = math.min(chunkSize.toLong, totalBytes - offset)
        if planned <= 0 then Left(s"OFFSET_OUT_OF_RANGE: chunk $index offset $offset past totalBytes $totalBytes")
        else if payload.length.toLong != planned then
          Left(s"chunk $index carries ${payload.length} bytes but the plan says $planned")
        else
          os.makeDir.all(target / os.up)
          // 先截断到块边界（处理上次中断的非整块尾），再追加。
          if os.exists(target) && os.size(target) > offset then
            val raf = new java.io.RandomAccessFile(target.toNIO.toFile, "rw")
            try raf.setLength(offset)
            finally raf.close()
          os.write.append(target, payload)
          bytes = offset + payload.length
          Right(bytes)

      end if

    end if

  end applyChunk

  /**
   * 整件收口：本侧流式自算 vs 对端声明；不符 ⇒ 删落盘件 + 显式错误。对端没给摘要
   * （极旧对端的 legacy 回应）＝无比对基准，如实返回本侧算值（不装验过）。
   */
  def verifyWhole(): Either[String, String] =
    val computed = ChunkedTransfer.hashFileStreaming(target)
    if declaredWhole.isEmpty then Right(computed)
    else if computed == declaredWhole then Right(computed)
    else
      try if os.exists(target) then os.remove(target)
      catch case _: Exception => ()
      Left(BrowseGetCursor.wholeMismatch(declaredWhole, computed))

end BrowseGetCursor

/** 在途浏览取件的取消旗标表（进程内，会话周期账：register/guarantee 注销，不留过夜）。 */
object BrowseFetchRegistry:

  private val flags = new ConcurrentHashMap[String, AtomicBoolean]()

  private def key(deviceId: String, path: String): String = s"$deviceId|$path"

  /** 登记一条在途取件；返回（旗标, 注销 IO）。 */
  def register(deviceId: String, path: String): (AtomicBoolean, IO[Unit]) =
    val f = new AtomicBoolean(false)
    flags.put(key(deviceId, path), f)
    val unregister = IO {
      val cur = flags.get(key(deviceId, path))
      if cur eq f then flags.remove(key(deviceId, path))
    }
    (f, unregister)

  /** 翻取消旗标。true = 有一条在途取件被叫停；false = 无在途（幂等 no-op）。 */
  def cancel(deviceId: String, path: String): Boolean =
    val f = flags.get(key(deviceId, path))
    if f eq null then false
    else
      f.set(true); true

  /** 读数面（spec/诊断）：该 (device, path) 是否有登记的在途取件。 */
  def isActive(deviceId: String, path: String): Boolean = flags.containsKey(key(deviceId, path))

end BrowseFetchRegistry

/**
 * 块长与流水线窗口的判据（纯函数，spec 直测；**唯一出处**——禁在调用点散落魔数）。
 *
 * 两条轴，都在「内存上界」下取：
 *
 *   - **块长按文件大小**：小件用 512 KiB（取消/进度粒度细，用户点取消最多丢一块），
 *     中件 1 MiB，大件 2 MiB（relay 链路上块太少会让 RTT 又变成主项，故不取 4 MiB
 *     上限——`AttachContract.MaxChunkSize` 只是对端接受上限，不是最优节奏）。
 *   - **窗口按块长反比**：`window × chunkSize ≤ InFlightByteBudget`，再夹到
 *     `[MinWindow, MaxWindow]`——即「同时在飞的字节」有界，与文件大小无关。
 */
object ChunkSizing:

  /** 在飞字节预算（8 MiB）：一波最多压这么多字节在内存里（含对端回帧的 base64）。 */
  val InFlightByteBudget: Long = 8L * 1024 * 1024

  /** 窗口硬下限：1 = 改前的纯串行；生产下限取 2（否则流水线等于没开）。 */
  val MinWindow: Int = 2

  /** 窗口硬上限：再宽只会放大内存与失败时的浪费，不改善 RTT 摊销。 */
  val MaxWindow: Int = 8

  /** 块长的三档（升序）。 */
  val SmallChunk: Int = 512 * 1024
  val MediumChunk: Int = 1024 * 1024
  val LargeChunk: Int = 2 * 1024 * 1024

  /**
   * 会话块长（整会话恒定——`AttachContract.indexForOffset` 的索引算术依赖它）。
   * 入参是**整件字节数**；未知（0/负数）⇒ 慢启动档 512 KiB。
   */
  def chunkSizeFor(totalBytes: Long): Int =
    if totalBytes <= 0L then SmallChunk
    else if totalBytes <= 4L * 1024 * 1024 then SmallChunk // ≤4 MiB：最多 8 块，粒度优先
    else if totalBytes <= 16L * 1024 * 1024 then MediumChunk // ≤16 MiB：最多 16 块
    else LargeChunk

  /** 流水线窗口（在飞字节预算 / 块长，夹到 `[MinWindow, MaxWindow]`）。 */
  def windowFor(chunkSize: Int): Int =
    if chunkSize <= 0 then MinWindow
    else
      val byBudget = (InFlightByteBudget / chunkSize.toLong).toInt
      math.max(MinWindow, math.min(MaxWindow, byBudget))

end ChunkSizing

/**
 * 分块拉取循环（**唯一驱动点**，`RemoteExecutor.transferGetScopedChunked` 消费）。
 *
 * `fetch` = 单块取数效应（调用方注入 P2P/relay 双腿 dispatch——本循环零传输面依赖，
 * spec 以纯效应替身驱动）。同一 index 最多重试 `retriesPerChunk` 次——**两类**重试：
 * ① 传输失败（fetch 回 Left，有 1s 间隔拍）；② 块摘要不符（不符块零写盘，同块重拉）。
 * 续传语义使重试安全：`applyChunk` 幂等对齐；取消旗标在每块前后各查一次。
 *
 * == 流水线（砍串行搬运开销，作者令 2026-11-04） ==
 *
 * 改前是**纯串行**：`fetch(0) → 落盘 → fetch(1) → …`，每块一次完整往返 ⇒ 链路
 * RTT 直接乘块数（relay 腿 RTT 实测 43 ms 量级，5 MiB / 512 KiB = 10 块）。
 * 改后按**窗口**并发：一波内 `window` 块同时在飞（`parTraverse`，受
 * [[ChunkSizing.InFlightByteBudget]] 与 [[ChunkSizing.MaxWindow]] 双重上限），
 * 全部回来再**按 index 升序**逐块校验落盘（顺序写盘语义与改前逐字节相同——续传
 * 判据 `expectedIndex` 不容乱序），然后进入下一波。
 *
 * 为什么是「波次」而不是「连续流水」：落盘必须严格有序，而 `applyChunk` 的目标是
 * 单文件追加；波次把「并发取数」与「顺序落盘」解耦，实现小、语义可读、背压天然
 * （一波未回不发下一波）——代价是波内最慢块决定波时长，收益上限 = 窗口宽度。
 *
 * 内存上界 = `window × chunkSize ≤ InFlightByteBudget`（8 MiB 量级），与文件大小无关。
 *
 * 整件流程：首请求 = 起始 index + `wantWholeSha256`（拿整件元数据 + 对端整件摘要，
 * 会话级一次付清）→ `restore` 对齐既有部分件 → 逐波 → 字节齐 ⇒ `verifyWhole` 收口。
 * legacy 回应（旧对端，无 `chunkSha256`）= 整件单发消费（大小闸照把——保留引导大件
 * 走 dropbox 通道的既有行为），同样回整件摘要（本侧自算——hash 账本副键的免费来源）。
 *
 * 返回 `Right((落盘件, 字节数, 整件 sha256))`；`Left(BrowseGetCursor.Cancelled)` =
 * 用户取消（区别于传输失败的终态）。
 *
 * @param startChunkSize 拉取节奏（缺省 512KiB 慢启动起点）；生产恒缺省，spec/未来
 *   自适应调速以参数注入——块长不上 wire 判据，对端只按 index 切片。
 * @param window 同时在飞的块数（`0` ⇒ 由 [[ChunkSizing.windowFor]] 按块长与内存预算派生；
 *   `1` = 改前的纯串行语义，spec 用它做 A/B 对照）。
 */
object BrowseGetLoop:

  /** 慢启动起点 = 附件腿在未知链路（假设速率）上的自适应取值同族（512KiB）。 */
  val StartChunkSize: Int = 512 * 1024

  def run(
    target: os.Path,
    fetch: (Int, Int, Boolean) => IO[Either[String, BrowseGetCursor.ChunkReply]], // (index, chunkSize, wantWholeSha)
    onProgress: (Long, Long, Long) => IO[Unit],
    cancelled: AtomicBoolean,
    retriesPerChunk: Int = 2,
    startChunkSize: Int = BrowseGetLoop.StartChunkSize,
    window: Int = 0
  ): IO[Either[String, (os.Path, Long, String)]] =
    val effWindow = if window > 0 then window else ChunkSizing.windowFor(startChunkSize)
    IO.blocking {
      if os.exists(target) && os.isFile(target) then os.size(target) else 0L
    }.flatMap { existingSize =>
      if cancelled.get then IO.pure(Left(BrowseGetCursor.Cancelled))
      else
        // 会话首请求 = 续传块 + wantWholeSha（拿整件元数据 + 对端整件摘要，会话级一次
        // 付清）。index 由**本侧已知量**推导（落盘长度 ÷ 块大小，floor）——对既有部分件
        // 只重取对齐尾块，不从 0 重拉。
        val firstIdx = (existingSize / startChunkSize).toInt
        attemptChunk(fetch, firstIdx, startChunkSize, wantWhole = true, cancelled, attemptsLeft = retriesPerChunk + 1)
          .flatMap {
            case Left(err) => IO.pure(Left(err))
            case Right((first, ms)) =>
              if first.chunked && first.totalBytes <= 0 then IO.pure(Left("remote file is empty (0 bytes)"))
              else if first.totalBytes > RemoteExecutor.MaxScopedFetchBytes then
                // 大小闸**不区分形态**（与改前 transferGetScoped 同判：legacy 回应照样把，
                // 超限显式报错引导显式批量/dropbox 通道，零回归）。
                IO.pure(
                  Left(
                    s"file too large for single fetch: ${first.totalBytes} bytes > ${RemoteExecutor.MaxScopedFetchBytes} " +
                      "limit — use explicit batch transfer (dropbox) instead"
                  )
                )
              else
                val cursor = new BrowseGetCursor(target, first.totalBytes, startChunkSize)
                IO.blocking(cursor.restore()).flatMap { _ =>
                  if !first.chunked then legacyWholeApply(cursor, first, onProgress)
                  else
                    cursor.noteWholeSha(first.wholeSha256.getOrElse(""))
                    // 已有字节 ≥ 整件（上会话已传完、只缺收口）⇒ 跳过应用直接整件校验。
                    if cursor.bytesReceived >= cursor.totalBytes then finish(cursor, onProgress)
                    else
                      applyChunkReply(cursor, first).flatMap {
                        case Left(err) => IO.pure(Left(err))
                        case Right(_) =>
                          onProgress(cursor.bytesReceived, cursor.totalBytes, ms).flatMap { _ =>
                            loop(
                              cursor,
                              fetch,
                              onProgress,
                              cancelled,
                              retriesPerChunk,
                              retriesPerChunk,
                              startChunkSize,
                              effWindow
                            )
                          }
                      }
                    end if
                }
          }
    }

  end run

  /**
   * 一波流水线：`window` 块同时在飞（并发取数）→ 按 index 升序逐块校验落盘 →
   * 下一波。顺序落盘是硬约束（`BrowseGetCursor.expectedIndex` 只接受下一个位置），
   * 因此并发面**只在取数**，不在写盘。
   */
  private def loop(
    cursor: BrowseGetCursor,
    fetch: (Int, Int, Boolean) => IO[Either[String, BrowseGetCursor.ChunkReply]],
    onProgress: (Long, Long, Long) => IO[Unit],
    cancelled: AtomicBoolean,
    retriesPerChunk: Int,
    digestRetriesLeft: Int,
    chunkSize: Int,
    window: Int
  ): IO[Either[String, (os.Path, Long, String)]] =
    if cursor.bytesReceived >= cursor.totalBytes then finish(cursor, onProgress)
    else if cancelled.get then IO.pure(Left(BrowseGetCursor.Cancelled))
    else
      val remainingChunks = ((cursor.totalBytes - cursor.bytesReceived) + chunkSize - 1) / chunkSize
      val width = math.max(1, math.min(window.toLong, remainingChunks)).toInt
      val firstIdx = cursor.expectedIndex
      val indexes = (firstIdx until firstIdx + width).toList
      // 并发取数（`parTraverse` 保序返回）：一波即一次流水线窗口。
      indexes
        .parTraverse(idx =>
          attemptChunk(fetch, idx, chunkSize, wantWhole = false, cancelled, attemptsLeft = retriesPerChunk + 1)
        )
        .flatMap { replies =>
          applyWave(
            cursor,
            replies,
            fetch,
            onProgress,
            cancelled,
            retriesPerChunk,
            digestRetriesLeft,
            chunkSize
          ).flatMap {
            case Left(err) => IO.pure(Left(err))
            case Right(_) if cancelled.get => IO.pure(Left(BrowseGetCursor.Cancelled))
            case Right(_) =>
              loop(cursor, fetch, onProgress, cancelled, retriesPerChunk, retriesPerChunk, chunkSize, window)
          }
        }

  /** 按 index 升序应用一波的块；摘要不符 ⇒ 同块重拉（上限 `retriesPerChunk` 次）。 */
  private def applyWave(
    cursor: BrowseGetCursor,
    replies: List[Either[String, (BrowseGetCursor.ChunkReply, Long)]],
    fetch: (Int, Int, Boolean) => IO[Either[String, BrowseGetCursor.ChunkReply]],
    onProgress: (Long, Long, Long) => IO[Unit],
    cancelled: AtomicBoolean,
    retriesPerChunk: Int,
    digestRetriesLeft: Int,
    chunkSize: Int
  ): IO[Either[String, Unit]] =
    def step(rest: List[Either[String, (BrowseGetCursor.ChunkReply, Long)]]): IO[Either[String, Unit]] =
      rest match
        case Nil => IO.pure(Right(()))
        case Left(err) :: _ => IO.pure(Left(err))
        case Right((reply, ms)) :: tail =>
          cursor.noteWholeSha(reply.wholeSha256.getOrElse(""))
          applyChunkReply(cursor, reply).flatMap {
            case Right(_) =>
              onProgress(cursor.bytesReceived, cursor.totalBytes, ms).flatMap(_ => step(tail))
            case Left(err) if err.startsWith("CHUNK_DIGEST_MISMATCH") && digestRetriesLeft > 0 && !cancelled.get =>
              // 块摘要不符（传输损伤）＝**同块重拉**（续传语义下安全：applyChunk 先校验
              // 后落盘——不符块零字节写盘，重拉从同一续传点继续）。上限 retriesPerChunk
              // 次/波，防对端持续坏块打转。
              attemptChunk(fetch, reply.chunkIndex, chunkSize, wantWhole = false, cancelled, retriesPerChunk + 1)
                .flatMap {
                  case Left(err2) => IO.pure(Left(err2))
                  case Right((again, ms2)) =>
                    applyChunkReply(cursor, again).flatMap {
                      case Left(err3) => IO.pure(Left(err3))
                      case Right(_) =>
                        onProgress(cursor.bytesReceived, cursor.totalBytes, ms2).flatMap(_ => step(tail))
                    }
                }
            case Left(err) => IO.pure(Left(err))
          }
    step(replies)

  end applyWave

  private def attemptChunk(
    fetch: (Int, Int, Boolean) => IO[Either[String, BrowseGetCursor.ChunkReply]],
    index: Int,
    chunkSize: Int,
    wantWhole: Boolean,
    cancelled: AtomicBoolean,
    attemptsLeft: Int
  ): IO[Either[String, (BrowseGetCursor.ChunkReply, Long)]] =
    if cancelled.get then IO.pure(Left(BrowseGetCursor.Cancelled))
    else
      val t0 = System.nanoTime()
      fetch(index, chunkSize, wantWhole).flatMap {
        case Right(reply) => IO.pure(Right((reply, (System.nanoTime() - t0) / 1000000L)))
        case Left(err) if attemptsLeft > 1 && !cancelled.get =>
          // 瞬时失败重试同块（续传语义下安全：applyChunk 幂等对齐）；间隔一拍（1s）。
          IO.sleep(1.second) *> attemptChunk(fetch, index, chunkSize, wantWhole, cancelled, attemptsLeft - 1)
        case Left(err) => IO.pure(Left(s"chunk $index failed: $err"))
      }

  private def applyChunkReply(cursor: BrowseGetCursor, reply: BrowseGetCursor.ChunkReply): IO[Either[String, Long]] =
    IO.blocking {
      val payload = Base64.getDecoder.decode(reply.contentB64)
      cursor.applyChunk(reply.chunkIndex, payload, reply.chunkSha256)
    }

  private def legacyWholeApply(
    cursor: BrowseGetCursor,
    reply: BrowseGetCursor.ChunkReply,
    onProgress: (Long, Long, Long) => IO[Unit]
  ): IO[Either[String, (os.Path, Long, String)]] =
    IO.blocking {
      val bytes = Base64.getDecoder.decode(reply.contentB64)
      // legacy 响应 = 整件：幂等覆盖（PointerPickup 缓存语义），大小闸在上方已把。
      os.makeDir.all(cursor.target / os.up)
      os.write.over(cursor.target, bytes)
      val sha = ChunkedTransfer.sha256Hex(bytes)
      (cursor.target, bytes.length.toLong, sha)
    }.flatMap { case (p, n, sha) =>
      onProgress(n, n, 0L).as(Right((p, n, sha)))
    }

  private def finish(
    cursor: BrowseGetCursor,
    onProgress: (Long, Long, Long) => IO[Unit]
  ): IO[Either[String, (os.Path, Long, String)]] =
    IO.blocking(cursor.verifyWhole()).flatMap {
      case Left(err) => IO.pure(Left(err))
      case Right(sha) =>
        onProgress(cursor.bytesReceived, cursor.totalBytes, 0L).as(
          Right((cursor.target, cursor.bytesReceived, sha))
        )
    }

end BrowseGetLoop

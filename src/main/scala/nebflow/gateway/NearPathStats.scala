package nebflow.gateway

import cats.effect.IO
import io.circe.Json
import io.circe.syntax.*
import nebflow.shared.NebflowLogger
import org.http4s.*
import org.http4s.dsl.io.*

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

import scala.jdk.CollectionConverters.*

/**
 * 本机近路「命中 / 回落」引擎侧可观测面（近路批，2026-11-04；审计
 * `20261104_103020_local-acceleration-audit.md` §2.4「今天近路 0 命中」＋ §8 建议 1
 * 「先把 miss 原因回传引擎」的落点；回执④的取证手段）。
 *
 * **修的就是「靠猜」**：本批之前近路 miss 只落在浏览器
 * `sessionStorage['stand_ws_nearpath_loopback_miss']`（`web/js/ws.js`），引擎侧只有
 * 一条 `nearpath: loopback-info served`（见 [[HealthRoutes]]），于是「到底有没有走
 * 上去」只能靠人肉翻浏览器存储。现在两个面：
 *
 *  - `POST /api/health/nearpath-event` —— 浏览器（隧道腿/环回腿两侧都可能来打）报
 *    一次 `hit` / `miss` + 归一化原因 token；每次调用打**一行 INFO**
 *    `nearpath event: <event> reason=<reason> port=<p>`（这行就是「以后可读」）；
 *  - `GET /api/health/nearpath` —— 只读计数端点（hit/miss/reasons/lastAt/环回兑换数）。
 *
 * 契约纪律（与前端节点逐字对接，不许改形）：
 *  - **零凭据面**：与 `/api/health/conn` 同级，无 `withAuth`（浏览器页面在入座前后都
 *    需要能报；载荷只有计数，无身份/无内容面）；
 *  - **只许新增**：不碰 `/api/health`、`/api/health/conn`、`/api/health/loopback-info`
 *    的任何既有键与语义（本对象只读自己的内存；路由挂在 [[HealthRoutes]] 的新臂上）；
 *  - **不落盘、不读文件、无新依赖**：进程内 `AtomicLong` + `ConcurrentHashMap`，随进程
 *    生死（重启即归零 = 这正是「本进程有没有走近路」的口径）；
 *  - **吃不完的不报错**：body 坏 / event 非 hit|miss / 超每 IP 每分钟上限 ⇒ 一律
 *    204 静默丢弃（观测面永不成为故障面；作者口径「超限静默丢弃」）。
 */
object NearPathStats:

  private val logger = NebflowLogger.forName("nebflow.nearpath")

  /** 每 IP 每分钟上限（超出静默丢弃；一次页面生命周期最多几条，120 已极宽）。 */
  private[gateway] val MaxEventsPerMinute: Int = 120

  /** `reasons` 键数上限（防恶意 token 扇出撑大内存；溢出桶并入 `other`）。 */
  private val MaxReasonKeys: Int = 128

  /** per-IP 窗口表上限（hyper 连接复位时换源地址不会无限长；溢出整表清空重来）。 */
  private val MaxWindows: Int = 4096

  /** 原因 token 合法形（归一化目标；不合法一律记 `other`）。 */
  private val ReasonPattern = java.util.regex.Pattern.compile("^[a-z0-9_.:-]{1,64}$")

  /** 合法事件名（其余一律静默丢弃）。 */
  private val EventNames = Set("hit", "miss")

  private val hitCount = new AtomicLong(0L)
  private val missCount = new AtomicLong(0L)
  private val redeemedCount = new AtomicLong(0L)

  /** 最近一次同向事件的时间戳（epoch ms；0 = 从无）。 */
  private val hitLastAt = new AtomicLong(0L)
  private val missLastAt = new AtomicLong(0L)

  /** 归一化原因 → 次数（hit/miss 合并在同一张表：作者要的是「为什么没走上去」的分布）。 */
  private val reasons = new ConcurrentHashMap[String, AtomicLong]()

  /** per-IP 滑动分钟窗：值 = `minute * 1000 + count`（count 上限 999，锁自由）。 */
  private val windows = new ConcurrentHashMap[String, AtomicLong]()

  // ── 事件入口（POST /api/health/nearpath-event）────────────────────────────

  /**
   * 处理一次事件上报。**恒** 204（观测面不产生错误码）；只有「限内 ∧ 合法」的调用
   * 才计数 + 打日志。CORS/PNA 头由调用方（[[HealthRoutes]]）按 probeCorsHeaders 家族
   * 叠加——本对象不 import gateway 路由，方向单向。
   *
   * body 读法**不挑 Content-Type**（`bodyText` + `parser.parse`）：上报方可能有
   * `application/json`，也可能被简化成 `text/plain`（`navigator.sendBeacon` /
   * keepalive fetch 的真实形态，二者都会绕过 JSON 媒体类型但字节仍是 JSON）。
   * 用媒体类型严格的 `req.as[Json]` 会把这一类**静默吞掉**——那正是本批要消灭的
   * 「无痕」形态。
   */
  def event(req: Request[IO]): IO[Response[IO]] =
    val ip = remoteIp(req)
    if !allowRate(ip) then IO.pure(Response[IO](Status.NoContent))
    else
      req.bodyText.compile.string.attempt.map {
        case Left(_) => Response[IO](Status.NoContent) // 读 body 失败：静默丢弃
        case Right(text) =>
          io.circe.parser.parse(text).toOption match
            case None => Response[IO](Status.NoContent) // 坏 JSON：静默丢弃
            case Some(body) =>
              val c = body.hcursor
              val ev = c.downField("event").as[String].toOption.map(_.trim.toLowerCase).getOrElse("")
              if !EventNames.contains(ev) then Response[IO](Status.NoContent)
              else
                val reason = normalizeReason(c.downField("reason").as[String].toOption.getOrElse(""))
                val port = c.downField("port").as[Int].toOption
                // `ua` 在契约里是可选短串（前端排障线索）。本面**不落任何客户端串**
                // （日志零 PII 纪律）：`ua` 只作线上兼容被接收，不入计数、不入日志。
                accept(ev, reason, port)
                Response[IO](Status.NoContent)
      }

    end if

  end event

  /** 计数 + INFO 行（单增量；日志是同步面，不改变应答语义）。 */
  private def accept(event: String, reason: String, port: Option[Int]): Unit =
    val now = System.currentTimeMillis()
    val line =
      s"nearpath event: $event reason=$reason port=${port.fold("-")(_.toString)}"
    if event == "hit" then
      hitCount.incrementAndGet()
      hitLastAt.set(now)
    else
      missCount.incrementAndGet()
      missLastAt.set(now)
    bumpReason(reason)
    // `NebflowLogger.info` 是 IO（不跑 = 无痕）。这里必须同步落地：调用方是 http4s
    // 路由（已经在线程池里），用 `unsafeRunAndForget` 之外的唯一零风险选择是同步版
    // `infoSync`（同 ConnGuard/GatewayMain 既有的同步日志面）。
    logger.infoSync(line)

  end accept

  private def bumpReason(reason: String): Unit =
    val key =
      if reasons.containsKey(reason) || reasons.size() < MaxReasonKeys then reason else "other"
    val cell = reasons.computeIfAbsent(key, _ => new AtomicLong(0L))
    cell.incrementAndGet()

  /** 原因归一化：trim + 小写，长度/字符集不合 `^[a-z0-9_.:-]{1,64}$` ⇒ `other`。 */
  private[gateway] def normalizeReason(raw: String): String =
    val trimmed = Option(raw).getOrElse("").trim.toLowerCase
    if ReasonPattern.matcher(trimmed).matches() then trimmed else "other"

  /**
   * per-IP 每分钟闸（锁自由）：窗口值 = `minute * 1000 + count`，跨分钟整体重置。
   * 并发下最坏情形是多算几条，不误伤（计数面不需要强一致）。
   */
  private def allowRate(ip: String): Boolean =
    val nowMin = System.currentTimeMillis() / 60000L
    if windows.size() > MaxWindows then windows.clear()
    val cell = windows.computeIfAbsent(ip, _ => new AtomicLong(nowMin * 1000L))
    val updated = cell.updateAndGet { v =>
      val m = v / 1000L
      if m != nowMin then nowMin * 1000L + 1L
      else
        val c = v - m * 1000L
        if c >= 999L then v else v + 1L // 封顶（避免低位溢出到下一分钟）
    }
    val m = updated / 1000L
    val count = updated - m * 1000L
    count <= MaxEventsPerMinute

  /** 客户端 IP（服务端 socket 事实；无连接信息 = `unknown`，与 LoopbackLeg 同归一口径）。 */
  private def remoteIp(req: Request[IO]): String =
    req.remoteAddr.fold("unknown")(a => a.collapseMappedV4.toString)

  // ── 读数（GET /api/health/nearpath）──────────────────────────────────────

  /**
   * 只读载荷（键集合是契约，前端并行节点按此对接）：
   * `{"hit":n,"miss":n,"reasons":{"<reason>":n},"hitLastAt":epochMs|null,
   *   "missLastAt":epochMs|null,"redeemedLoopback":n}`
   */
  def payload: Json =
    // 键序固定（按 token 字典序）= 载荷对同一状态逐字节稳定（拍快照/对比不抖）。
    val reasonsJson = Json.fromFields(
      reasons.asScala.toList.sortBy(_._1).map((k, v) => k -> v.get().asJson)
    )
    Json.obj(
      "hit" -> hitCount.get().asJson,
      "miss" -> missCount.get().asJson,
      "reasons" -> reasonsJson,
      "hitLastAt" -> epochOrNull(hitLastAt.get()),
      "missLastAt" -> epochOrNull(missLastAt.get()),
      "redeemedLoopback" -> redeemedCount.get().asJson
    )

  private def epochOrNull(v: Long): Json =
    if v <= 0L then Json.Null else Json.fromLong(v)

  // ── 正交计数（best-effort，不动任何判定语义）────────────────────────────

  /**
   * 控制台票在**环回腿**兑换成功 +1（[[ConsoleTicketRoutes]] 的既有兑换日志旁边一行）。
   * 「近路真的走通了」的最硬证据不是浏览器自报的 hit，而是本机票在真实 socket 环回
   * 腿上被兑换；与 hit 计数正交、互不推导。best-effort：只加一，不参与任何判定。
   */
  def noteRedeemedLoopback(): Unit = redeemedCount.incrementAndGet()

  /** 测试专用：清空全部进程内计数（生产代码零调用点）。 */
  private[gateway] def resetForTest(): Unit =
    hitCount.set(0L)
    missCount.set(0L)
    redeemedCount.set(0L)
    hitLastAt.set(0L)
    missLastAt.set(0L)
    reasons.clear()
    windows.clear()

end NearPathStats

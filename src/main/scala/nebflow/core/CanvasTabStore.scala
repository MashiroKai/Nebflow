package nebflow.core

import cats.effect.IO
import io.circe.{Json, JsonObject, parser}
import nebflow.shared.NebflowLogger

/**
 * 服务端持久化的 Canvas 标签 v2 存档（F1 根治，2026-08-30）。
 *
 * 作者报告「JVM 重启后标签页（含 file 文档标签）丢失」——根因=浏览器
 * localStorage 不可靠（Safari 无痕/存储未落盘、多窗口 closeTab removeItem
 * 清空共享存档竞态、readFile 失败静默丢弃）。本存储把标签 v2 元数据落盘
 * `~/.nebflow/canvas_tabs.json`，与 sessionStore 同生命周期（dataRoot 下），
 * JVM 重启保留；前端 restoreTabs 优先拉服务端存档，localStorage 仅作缓存。
 *
 * 字段契约（与前端 js/canvas.js persistTabs/restoreTabs 的 v2 schema 对齐，
 * 2026-08-30 实测）：
 *   { "v": 2,
 *     "tabs": [ { "id", "title", "type", "absPath"?, "pinned"?, "closable"?,
 *                 "openedAt"? } ],
 *     "activeTabId"?,
 *     "updatedAt"? }
 * - v 必须为数字 2
 * - tabs 必须是数组；id/type/title 必填 string（id 非空）；
 *   absPath 可选 null|string；pinned/closable 可选 boolean；
 *   openedAt 可选 number（标签打开事件毫秒戳，跨设备 per-tab LWW 基线）；
 *   activeTabId 可选 null|string
 * - updatedAt 可选 number（存档级 LWW 戳，跨设备收敛批 2026-10-24：
 *   关闭/打开 = 一次带戳权威写，服务端按戳拒绝陈旧写并广播收敛）
 * - 未知字段忽略（宽容，向前兼容）
 */
object CanvasTabs:

  /** PUT body 上限（防滥用）——256 KiB。 */
  val MaxBodyBytes: Int = 256 * 1024

  /** 存档级 LWW 戳（顶层 updatedAt，毫秒）。缺失/非数 → None。 */
  def stampOf(json: Json): Option[Long] =
    json.hcursor.downField("updatedAt").as[Long].toOption

  /** 单标签打开事件戳（tabs[i].openedAt，毫秒）。缺失/非数 → None。 */
  def openedAtOf(tab: Json): Option[Long] =
    tab.hcursor.downField("openedAt").as[Long].toOption

  /**
   * LWW 写穿裁决（跨设备收敛批 2026-10-24）。规则：
   *   - 无存档 → 接受（Right）。落盘件补 `updatedAt = nowMs`（来方无戳时），
   *     此后所有写都有可比的戳。
   *   - 来方无戳（老前端）→ 接受（向后兼容），落盘件补 nowMs 戳。
   *   - 双方有戳：来方戳 >= 存档戳 → 接受（存档逐字用来方戳——客户端事件时序
   *     是权威，服务端不改写）；来方戳更旧 → 拒绝（Left(权威存档)——路由层转
   *     409 并回存档，让陈旧写方即时收敛，而不是覆盖掉他端更新的关闭事件）。
   * 纯函数，便于单测。
   */
  def lwwVerdict(stored: Option[Json], incoming: Json, nowMs: Long): Either[Option[Json], Json] =
    val stamped = if (stampOf(incoming).isDefined) incoming
    else Json.fromJsonObject(
      incoming.asObject.getOrElse(JsonObject.empty)
        .add("updatedAt", Json.fromLong(nowMs))
    )
    stored match
      case None         => Right(stamped)
      case Some(kernel) =>
        stampOf(incoming) match
          case Some(s) if s >= stampOf(kernel).getOrElse(0L) => Right(incoming)
          case Some(_)                                       => Left(Some(kernel))
          case None                                          => Right(stamped)

  /**
   * 校验 v2 存档 JSON。成功返回规范化后的 Json（原样透传，不做字段裁剪），
   * 失败返回人类可读错误信息（HTTP 400 载体）。
   */
  def validate(json: Json): Either[String, Json] =
    val hc = json.hcursor
    for
      _ <- Either.cond(json.isObject, (), "top-level must be a JSON object")
      v <- hc.downField("v").as[Int].left.map(_ => "missing or invalid 'v' (must be numeric 2)")
      _ <- Either.cond(v == 2, (), s"unsupported schema version $v (expected 2)")
      tabs <- hc.downField("tabs").as[Json].left.map(_ => "missing 'tabs'").flatMap { t =>
        Either.cond(t.isArray, t, "'tabs' must be an array")
      }
      _ <- tabs.asArray
        .getOrElse(Vector.empty)
        .zipWithIndex
        .map { case (tab, i) => validateTab(tab).left.map(err => s"tabs[$i]: $err") }
        .collectFirst { case Left(e) => Left(e) }
        .getOrElse(Right(()))
      _ <- hc
        .downField("activeTabId")
        .as[Option[String]]
        .left
        .map(_ => "'activeTabId' must be a string or null")
      _ <- hc
        .downField("updatedAt")
        .as[Option[Long]]
        .left
        .map(_ => "'updatedAt' must be a number (ms epoch LWW stamp)")
    yield json

    end for

  end validate

  private def validateTab(tab: Json): Either[String, Unit] =
    val hc = tab.hcursor
    for
      _ <- Either.cond(tab.isObject, (), "tab entry must be an object")
      id <- hc.downField("id").as[String].left.map(_ => "missing or invalid 'id' (must be string)")
      _ <- Either.cond(id.nonEmpty, (), "'id' must be non-empty")
      _ <- hc.downField("type").as[String].left.map(_ => "missing or invalid 'type' (must be string)")
      _ <- hc.downField("title").as[String].left.map(_ => "missing or invalid 'title' (must be string)")
      _ <- hc.downField("absPath").as[Option[String]].left.map(_ => "'absPath' must be a string or null")
      _ <- hc.downField("pinned").as[Option[Boolean]].left.map(_ => "'pinned' must be a boolean")
      _ <- hc.downField("closable").as[Option[Boolean]].left.map(_ => "'closable' must be a boolean")
      _ <- hc
        .downField("openedAt")
        .as[Option[Long]]
        .left
        .map(_ => "'openedAt' must be a number (ms epoch open-event stamp)")
    yield ()

  /**
   * PUT body 处理链：长度限制 → UTF-8 解码 → JSON 解析 → schema 校验。
   * 返回 Right(校验通过的 Json) 或 Left((HTTP status, 错误信息))。
   * 纯函数，便于单测；路由层只负责 auth + 读 body + 落盘。
   */
  def parseBody(body: Array[Byte], maxBytes: Int = MaxBodyBytes): Either[(Int, String), Json] =
    if body.length > maxBytes then Left((413, s"canvas tabs payload exceeds $maxBytes bytes limit"))
    else
      val text = new String(body, java.nio.charset.StandardCharsets.UTF_8)
      parser.parse(text) match
        case Left(err) => Left((400, s"invalid JSON: ${err.message}"))
        case Right(json) =>
          validate(json) match
            case Left(msg) => Left((400, msg))
            case Right(ok) => Right(ok)

end CanvasTabs

/** 磁盘存档读写。路径由调用方传入（生产=dataRoot/canvas_tabs.json，测试=temp）。 */
class CanvasTabStore(path: os.Path):
  import CanvasTabs.*

  /**
   * 读取存档。None = 无存档或存档损坏（损坏时 warn 日志，视同无存档——
   * 前端 fallback 到 localStorage，下次 PUT 重建）。
   */
  def load(): IO[Option[Json]] = IO.blocking {
    if !os.exists(path) then None
    else
      val text = os.read(path)
      parser.parse(text) match
        case Right(json) if validate(json).isRight => Some(json)
        case _ =>
          NebflowLogger
            .forName("nebflow.canvas-tabs")
            .warnSync(s"canvas_tabs.json corrupt or invalid — treating as no archive ($path)")
          None
  }

  /** 原子写存档（tmp + ATOMIC_MOVE，崩溃安全；写失败抛错由调用方转 500）。 */
  def save(json: Json): IO[Unit] =
    IO.blocking {
      AtomicJson.writeSync(path, json.noSpaces)
    }
end CanvasTabStore

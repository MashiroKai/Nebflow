package nebflow.core

import cats.effect.IO
import io.circe.{Json, parser}
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
 *     "tabs": [ { "id", "title", "type", "absPath"?, "pinned"?, "closable"? } ],
 *     "tombstones"?: [ { "id", "closedAt", "dev"?, "reopenedAt"? } ],
 *     "activeTabId"? }
 * - v 必须为数字 2
 * - tabs 必须是数组；id/type/title 必填 string（id 非空）；
 *   absPath 可选 null|string；pinned/closable 可选 boolean；
 *   activeTabId 可选 null|string
 * - tombstones（#910 加法扩展，2026-10-26）可选数组；条目 id 必填非空
 *   string、closedAt 必填非负数字（epoch ms）、dev/reopenedAt 可选；
 *   PUT 载荷还可附带可选数组 reopened: [{ id, reopenedAt }]（显式重开账）
 * - 未知字段忽略（宽容，向前兼容）
 */
object CanvasTabs:

  /** PUT body 上限（防滥用）——256 KiB。 */
  val MaxBodyBytes: Int = 256 * 1024

  // ── #910 墓碑语义（2026-10-26 作者裁定「找少的那个认吧」＝关闭意图优先）──
  //
  // 症状：多窗口/多设备下，A 端关闭的标签被 B 端陈旧全量 PUT 复活（旧语义
  // = 整档覆盖，关闭动作在「缺席」里无法与「从未见过」区分，也没有独立
  // 表达）。修复：关闭意图显式落墓碑，合并时墓碑并集压过开放集；显式重开
  // （reopenedAt 晚于 closedAt）才恢复。存档文件格式仍 v2——墓碑/重开账是
  // 加法可选字段，老引擎按「未知字段忽略」契约不受影响，老前端 GET 照常
  // 读 v:2 + tabs。跨设备收敛在两端引擎都跑本代码后，经各自存档的
  // tombstones 账目表达（传输面=开放项，见 reports #910）。

  /** 墓碑 TTL：latestTs 早于 now-Ttl 的墓碑在合并时回收（防无限膨胀）。30 天。 */
  val TombstoneTtlMs: Long = 30L * 24 * 60 * 60 * 1000

  /** 存档内墓碑记录数量上限（超出淘汰 latestTs 最旧的）。 */
  val MaxTombstones: Int = 500

  /** 单次 PUT 载荷 tombstones/reopened 数组长度上限（超限 400，防滥用）。 */
  val MaxTombstoneInput: Int = 2000

  /** 一条墓碑记录：关闭意图（id, closedAt, 源设备?）+ 最近的显式重开?。 */
  final case class Tomb(id: String, closedAt: Long, dev: Option[String], reopenedAt: Option[Long]):
    def latestTs: Long = math.max(closedAt, reopenedAt.getOrElse(Long.MinValue))

    /**
     * 生效墓碑：closedAt 晚于 reopenedAt。重开过的老墓碑保持惰性（不删除）——
     * 旧 PUT 重放既无法复活「已重开又被关」的标签，也无法把已重开的标签
     * 压回关闭态（时间戳裁决，见 merge）。
     */
    def active: Boolean = closedAt > reopenedAt.getOrElse(Long.MinValue)

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
      _ <- validateTombArray(hc, "tombstones", "closedAt")
      _ <- validateTombArray(hc, "reopened", "reopenedAt")
    yield json

    end for

  end validate

  /** 墓碑/重开账数组校验（字段可选；在场则必须是合法数组，条目见 validateTombEntry）。 */
  private def validateTombArray(hc: io.circe.ACursor, field: String, tsField: String): Either[String, Unit] =
    hc.downField(field).as[Option[Vector[Json]]] match
      case Left(_) => Left(s"'$field' must be an array when present")
      case Right(None) => Right(())
      case Right(Some(arr)) =>
        if arr.length > MaxTombstoneInput then Left(s"'$field' exceeds $MaxTombstoneInput entries")
        else
          arr.zipWithIndex
            .map { case (e, i) => validateTombEntry(e, tsField).left.map(err => s"$field[$i]: $err") }
            .collectFirst { case Left(err) => Left(err) }
            .getOrElse(Right(()))

  private def validateTombEntry(e: Json, tsField: String): Either[String, Unit] =
    val hc = e.hcursor
    for
      _ <- Either.cond(e.isObject, (), "entry must be an object")
      id <- hc.downField("id").as[String].left.map(_ => "missing or invalid 'id' (must be string)")
      _ <- Either.cond(id.nonEmpty, (), "'id' must be non-empty")
      ts <- hc.downField(tsField).as[Long].left.map(_ => s"missing or invalid '$tsField' (must be number)")
      _ <- Either.cond(ts >= 0, (), s"'$tsField' must be non-negative")
      _ <- hc.downField("dev").as[Option[String]].left.map(_ => "'dev' must be a string")
    yield ()

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

  // ── #910 合并语义（纯函数，全量单测）──────────────────────────────────

  private case class ReopenEv(id: String, reopenedAt: Long)

  /** 取对象数组字段（缺字段/坏类型 → 空 Vector；显式 match 固定类型，不走 lub 推断）。 */
  private def arrField(j: Json, field: String): Vector[Json] =
    j.hcursor.downField(field).as[Option[Vector[Json]]] match
      case Right(Some(arr)) => arr
      case _ => Vector.empty

  /** 宽松解析墓碑数组（merge 只吃已过 validate 的载荷，坏条目静默跳过）。 */
  private def parseTombstones(j: Json): Vector[Tomb] =
    arrField(j, "tombstones").flatMap { e =>
      val hc = e.hcursor
      for
        id <- hc.downField("id").as[String].toOption if id.nonEmpty
        closedAt <- hc.downField("closedAt").as[Long].toOption if closedAt >= 0
        dev = hc.downField("dev").as[Option[String]].toOption.flatten
        reopenedAt = hc.downField("reopenedAt").as[Option[Long]].toOption.filter(_.exists(_ >= 0)).flatten
      yield Tomb(id, closedAt, dev, reopenedAt)
    }

  private def parseReopens(j: Json): Vector[ReopenEv] =
    arrField(j, "reopened").flatMap { e =>
      val hc = e.hcursor
      for
        id <- hc.downField("id").as[String].toOption if id.nonEmpty
        reopenedAt <- hc.downField("reopenedAt").as[Long].toOption if reopenedAt >= 0
      yield ReopenEv(id, reopenedAt)
    }

  /** TTL 回收 + 数量上限（淘汰 latestTs 最旧）。 */
  private def gcTombstones(map: scala.collection.mutable.LinkedHashMap[String, Tomb], nowMs: Long): Unit =
    map.filterInPlace((_, t) => t.latestTs >= nowMs - TombstoneTtlMs)
    if map.size > MaxTombstones then
      map.values.toVector.sortBy(_.latestTs).take(map.size - MaxTombstones).foreach(t => map.remove(t.id))

  /**
   * #910 合并：把一次 PUT 的开放集 + 关闭/重开意图账并入现有存档，返回
   * 落盘的新存档（含 tombstones 字段，即便为空——格式自描述）。
   *
   * 规则（作者裁定 2026-10-26「找少的那个认吧」＝关闭意图优先）：
   *   1. 墓碑并集：PUT 的 tombstones 并入现有记录；同 id 取更晚 closedAt、
   *      dev 缺省沿用旧值；
   *   2. 显式重开：PUT 的 reopened 只作用于已有墓碑（未知 id 忽略，不凭空
   *      造记录），reopenedAt 更晚才生效；老墓碑保持惰性不删除——旧 PUT
   *      重放的 closedAt 老账无法盖过新重开（时间戳裁决）；
   *   3. 关闭压过开放集：tabs 命中生效墓碑的 id 一律滤除——老客户端全量
   *      PUT、陈旧窗口重放都无法复活已关标签；
   *   4. TTL + 上限：合并前后各跑一次 gc；
   *   5. tabs 同 id 重复取首个；activeTabId 命中生效墓碑则丢弃（PUT 带了
   *      用 PUT 的，否则沿用 prev 的）。
   */
  def merge(prev: Option[Json], put: Json, nowMs: Long): Json =
    val map = scala.collection.mutable.LinkedHashMap[String, Tomb]()
    prev.foreach(p => parseTombstones(p).foreach(t => map(t.id) = t))
    gcTombstones(map, nowMs)
    parseTombstones(put).foreach { t =>
      map(t.id) = map.get(t.id) match
        case Some(old) => old.copy(closedAt = math.max(old.closedAt, t.closedAt), dev = t.dev.orElse(old.dev))
        case None => t
    }
    parseReopens(put).foreach { r =>
      map.get(r.id).foreach { old =>
        map(r.id) = old.copy(reopenedAt = Some(math.max(old.reopenedAt.getOrElse(Long.MinValue), r.reopenedAt)))
      }
    }
    gcTombstones(map, nowMs)
    val activeIds = map.values.filter(_.active).map(_.id).toSet
    val seen = scala.collection.mutable.LinkedHashSet[String]()
    val putTabs = arrField(put, "tabs")
    val kept = scala.collection.mutable.ArrayBuffer.empty[Json]
    for t <- putTabs do
      t.hcursor.downField("id").as[String].toOption match
        case Some(id) if id.nonEmpty && !activeIds(id) && !seen(id) =>
          seen.add(id)
          kept.addOne(t)
        case _ => ()
    val tabs = kept.toVector
    val prevActive =
      prev.flatMap(_.hcursor.downField("activeTabId").as[Option[String]].toOption.flatten)
    val activeId = put.hcursor
      .downField("activeTabId")
      .as[Option[String]]
      .toOption
      .flatten
      .orElse(prevActive)
      .filter(id => !activeIds(id) && id.nonEmpty)
    val tombJson = map.values.toVector.sortBy(_.latestTs).map { t =>
      Json
        .obj("id" -> Json.fromString(t.id), "closedAt" -> Json.fromLong(t.closedAt))
        .deepMerge(t.dev.fold(Json.obj())(d => Json.obj("dev" -> Json.fromString(d))))
        .deepMerge(t.reopenedAt.fold(Json.obj())(r => Json.obj("reopenedAt" -> Json.fromLong(r))))
    }
    val fields = scala.collection.mutable.LinkedHashMap[String, Json]()
    fields("v") = Json.fromInt(2)
    fields("tabs") = Json.fromValues(tabs)
    fields("tombstones") = Json.fromValues(tombJson)
    activeId.foreach(id => fields("activeTabId") = Json.fromString(id))
    Json.fromJsonObject(io.circe.JsonObject.fromIterable(fields))
  end merge

end CanvasTabs

/** 磁盘存档读写。路径由调用方传入（生产=dataRoot/canvas_tabs.json，测试=temp）。 */
object CanvasTabStore:

  /**
   * #910 进程级合并闸（公平 FIFO）：canvas_tabs.json 是单文件权威存档，
   * load→merge→save 必须全进程串行——两个窗口并发 PUT 时，后写者的 merge
   * 若基于旧 prev 读不到先写者的墓碑，会把关闭意图整段丢掉（锁闭合该
   * 读-改-写竞态；save 的原子写是崩溃安全层，不解决此竞态）。
   */
  private[core] val mergeGate = new java.util.concurrent.Semaphore(1, true)

end CanvasTabStore

class CanvasTabStore(path: os.Path):
  import CanvasTabs.*

  /** 同步读存档（load 的阻塞体，mergeSave 复用）。None = 无存档或损坏。 */
  private def loadSync(): Option[Json] =
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

  /**
   * 读取存档。None = 无存档或存档损坏（损坏时 warn 日志，视同无存档——
   * 前端 fallback 到 localStorage，下次 PUT 重建）。
   */
  def load(): IO[Option[Json]] = IO.blocking(loadSync())

  /** 原子写存档（tmp + ATOMIC_MOVE，崩溃安全；写失败抛错由调用方转 500）。 */
  def save(json: Json): IO[Unit] =
    IO.blocking {
      AtomicJson.writeSync(path, json.noSpaces)
    }

  /**
   * #910 合并落盘（路由层 PUT 唯一入口）：进程级合并闸内 读旧存档 → merge
   * （墓碑并集压过开放集 + 显式重开 + TTL/上限回收）→ 原子写。返回合并后
   * 的存档。单个 IO.blocking 包住整个临界区（阻塞池线程，绝不占计算池）。
   */
  def mergeSave(put: Json, nowMs: Long = System.currentTimeMillis()): IO[Json] =
    IO.blocking {
      CanvasTabStore.mergeGate.acquire()
      try
        val merged = CanvasTabs.merge(loadSync(), put, nowMs)
        AtomicJson.writeSync(path, merged.noSpaces)
        merged
      finally CanvasTabStore.mergeGate.release()
    }
end CanvasTabStore

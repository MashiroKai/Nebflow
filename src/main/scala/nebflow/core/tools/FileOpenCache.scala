package nebflow.core.tools

import java.util.concurrent.atomic.AtomicLong

import scala.collection.mutable
import scala.util.Try

import io.circe.Json
import io.circe.parser.decode
import io.circe.syntax.*

import nebflow.shared.{NebflowLogger, PathUtil}

/** 索引条目（持久层账面；`last` 变量字段供 LRU touch 原地更新）。 */
private[tools] final case class FileOpenCacheMeta(
  key: String,
  file: String, // blobs/ 下相对文件名
  bytes: Long,
  fpSize: Long,
  fpMtime: Long,
  sha16: String,
  at: Long,
  @volatile var last: Long,
  kind: String, // "local" | "dev"
  @volatile var matSize: Long = 0L, // pickup 落位读数（0 = 从未落位）
  @volatile var matMtime: Long = 0L
):
  def fpMatch(size: Long, mtimeMs: Long): Boolean = fpSize == size && fpMtime == mtimeMs

/**
 * FileOpenCache —— 文件打开三级缓存（台账 #902 · 收口批 F，作者 10-26 晨令：
 * 本机秒开 / 持久缓存 / 跨设备近端优先）。
 *
 * 三级结构：
 *   ① 进程内热缓存 —— 指纹键（路径 + mtime + size）命中的字节直出，二次打开
 *      零磁盘读（`diskReads` 计数器 = spec 钉「二次打开不走全量取数」断言面）。
 *   ② 持久缓存 —— `<dataRoot>/cache/fileopen/v1/`（`index.json` + `blobs/<键hash>.bin`），
 *      全局 LRU + 容量上限（缺省 512 MiB），tmp+move 原子写，blob 与索引 0600；
 *      引擎重启后命中持久层（`diskHits`）依然免取数。
 *   ③ 跨设备层 —— 同一持久层里的 `dev:<deviceId>:<path>` 键：对端取件落缓存，
 *      再开同件时先做一次父目录元数据探测（`transferListScoped`，零字节流动）
 *      拿 {size, mtime} 对指纹 —— 命中即跳过全量分块拉取（`remotePullSkips`），
 *      字节从本机缓存落位 pickup（`ensurePickup`）后按原帧形回发。
 *
 * 语义红线（作者口径）：
 *   - 缓存只对「指纹未变」的读生效：本地读每次开盘 stat（size+mtime），远端读
 *     每次开探测元数据——指纹不符 = 条目立即淘汰 + 走全量取数，**绝不显示陈旧
 *     内容**（mtime+size 双元组是作者钦定指纹；同毫秒同尺寸改写为申报取舍，
 *     引擎写路径经 [[NfCacheInvalidator]] 写穿失效补齐）。
 *   - 写穿：`NfCacheInvalidator.invalidated`（Write/Edit/MultiEdit/createFile/
 *     deletePath/movePath/writeFile 的单一变更事实发射点）同步失效本缓存。
 *   - 权限：缓存文件 0600（POSIX；Windows 跳过，同仓内既有惯例）。
 *
 * 并发模型：单监视器锁（`lock`）护全部账面与磁盘突变——文件打开是低频人面
 * 操作，简单正确优先；调用方一律 `IO.blocking` 包裹。
 *
 * 配置（缺省即用；`nebflow.fileOpenCache.*` 系统属性可覆写）：
 *   enabled=true / hotBytes=64MiB / diskBytes=512MiB / maxEntryBytes=96MiB。
 * maxEntryBytes ≥ MaxScopedFetchBytes(50MiB) ⇒ 凡可拉取件皆可入缓存。
 */
object FileOpenCache:

  private val logger = NebflowLogger.forName("nebflow.core.tools.file-open-cache")

  /** 内容指纹（作者口径：路径进键、mtime+size 进指纹；sha16 是跨设备副键读数）。 */
  final case class Fingerprint(size: Long, mtimeMs: Long)

  /** 跨设备命中读数：字节 + 整件 sha 前 16 hex（与分块拉取腿的 `wholeSha256` 同源）。 */
  final case class RemoteHit(bytes: Array[Byte], sha16: String)

  /** 本机整件读穿读数：`fromCache=false` = 本轮走了真实磁盘读（计数器面）。 */
  final case class LocalRead(content: String, fromCache: Boolean)

  /** 计数器快照（spec 钉的断言面；全部单调递增）。 */
  final case class Stats(
    diskReads: Long,
    hotHits: Long,
    diskHits: Long,
    remotePullSkips: Long,
    puts: Long,
    invalidations: Long
  )

  // ---- 计数器 ----
  private val cDiskReads = AtomicLong(0) // 真实全量磁盘读（本机 miss 读 / 探测后仍拉取）
  private val cHotHits = AtomicLong(0) // ① 热层命中
  private val cDiskHits = AtomicLong(0) // ② 持久层命中
  private val cRemotePullSkips = AtomicLong(0) // ③ 探测指纹命中 ⇒ 跳过对端全量拉取
  private val cPuts = AtomicLong(0)
  private val cInvalidations = AtomicLong(0)

  def statsSnapshot: Stats = Stats(
    cDiskReads.get(),
    cHotHits.get(),
    cDiskHits.get(),
    cRemotePullSkips.get(),
    cPuts.get(),
    cInvalidations.get()
  )

  // ---- 配置（系统属性可覆写；只读一次不碍事，def 保 spec 内可预测）----
  private def confBool(name: String, dflt: Boolean): Boolean =
    val v = System.getProperty(s"nebflow.fileOpenCache.$name")
    if v == null then dflt
    else Try(v.trim.toBoolean).getOrElse(dflt)

  private def confBytes(name: String, dflt: Long): Long =
    val v = System.getProperty(s"nebflow.fileOpenCache.$name")
    if v == null then dflt
    else Try(v.trim.toLong).filter(_ > 0).getOrElse(dflt)

  private def enabled = confBool("enabled", true)
  private def hotCapBytes = confBytes("hotBytes", 64L * 1024 * 1024)
  private def diskCapBytes = confBytes("diskBytes", 512L * 1024 * 1024)
  private def maxEntryBytes = confBytes("maxEntryBytes", 96L * 1024 * 1024)

  // ---- 账面（全在 lock 内读写）----
  private val lock = new Object
  private val index = mutable.LinkedHashMap[String, FileOpenCacheMeta]() // 插入序 = LRU 序（touch = remove+put）
  private val hot = mutable.LinkedHashMap[String, (Array[Byte], Fingerprint)]()
  private var indexBytesTotal = 0L
  private var hotBytesTotal = 0L
  private var loadedRoot: os.Path = null

  /** 持久层落位（def 非 val：dataRoot 可重定向——f1cd3709 规则）。 */
  private def diskRoot: os.Path = PathUtil.dataRoot / "cache" / "fileopen" / "v1"
  private def blobsRoot: os.Path = diskRoot / "blobs"
  private def indexFile: os.Path = diskRoot / "index.json"

  // ==== 键 ====
  private def localKey(p: os.Path): String = "local:" + p.toString

  private def remoteKey(deviceId: String, remotePath: String): String =
    "dev:" + deviceId + ":" + remotePath.replaceAll("""[/\\]+""", "/")

  /** 键 → blob 文件名（sha256(键) 前 32 hex；文件名里不落任何路径明文）。 */
  private def blobName(key: String): String =
    val d = java.security.MessageDigest.getInstance("SHA-256")
    d.digest(key.getBytes("UTF-8")).map(b => f"${b & 0xff}%02x").mkString.take(32) + ".bin"

  private def isWindows: Boolean = System.getProperty("os.name", "").toLowerCase.contains("win")

  private def setPerms0600(p: os.Path): Unit =
    if !isWindows then Try(os.perms.set(p, "rw-------"))

  /** 懒加载持久层账面（dataRoot 变了即重载——spec 隔离面）。索引坏 = 重建空账（缓存是尽力而为面，读侧指纹兜底）。 */
  private def ensureLoaded(): Unit =
    val root = diskRoot
    if loadedRoot == null || loadedRoot != root then
      loadedRoot = root
      index.clear(); indexBytesTotal = 0L
      Try {
        os.makeDir.all(blobsRoot)
        if os.exists(indexFile) then
          setPerms0600(indexFile)
          decode[Json](os.read(indexFile)) match
            case Right(js) =>
              js.hcursor.downField("entries").as[Vector[Json]].getOrElse(Vector.empty).foreach { e =>
                parseMeta(e).foreach { m =>
                  val f = blobsRoot / m.file
                  if os.exists(f) && os.size(f) == m.bytes then
                    index(m.key) = m
                    indexBytesTotal += m.bytes
                }
              }
            case Left(err) =>
              logger.warn(s"[file-open-cache] index unreadable, rebuilding empty: ${err.getMessage}")
        // 孤儿清扫：账外 blob 回收（崩溃窗口的 .tmp / 已淘汰残件）
        val known = index.values.map(_.file).toSet
        os.list(blobsRoot).foreach { f =>
          if !known.contains(f.last) then Try(os.remove(f))
        }
      }.failed.foreach(err => logger.warn(s"[file-open-cache] lazy load degraded: ${err.getMessage}"))
    end if
  end ensureLoaded

  private def parseMeta(e: Json): Option[FileOpenCacheMeta] =
    val c = e.hcursor
    for
      k <- c.downField("k").as[String].toOption
      f <- c.downField("f").as[String].toOption
      by <- c.downField("size").as[Long].toOption
      fs <- c.downField("fpSize").as[Long].toOption
      fm <- c.downField("fpMtime").as[Long].toOption
      sh <- c.downField("sha16").as[String].toOption
      at <- c.downField("at").as[Long].toOption
      la <- c.downField("last").as[Long].toOption
      kd <- c.downField("kind").as[String].toOption
      ms <- Right(c.downField("matSize").as[Long].toOption.getOrElse(0L)).toOption
      mm <- Right(c.downField("matMtime").as[Long].toOption.getOrElse(0L)).toOption
    yield FileOpenCacheMeta(k, f, by, fs, fm, sh, at, la, kd, ms, mm)

  end parseMeta

  private def metaToJson(m: FileOpenCacheMeta): Json = Json.obj(
    "k" -> m.key.asJson,
    "f" -> m.file.asJson,
    "size" -> m.bytes.asJson,
    "fpSize" -> m.fpSize.asJson,
    "fpMtime" -> m.fpMtime.asJson,
    "sha16" -> m.sha16.asJson,
    "at" -> m.at.asJson,
    "last" -> m.last.asJson,
    "kind" -> m.kind.asJson,
    "matSize" -> m.matSize.asJson,
    "matMtime" -> m.matMtime.asJson
  )

  /** 索引原子落盘（tmp + move；0600）。失败 = WARN 不外抛（读侧指纹兜底）。 */
  private def saveIndexLocked(): Unit =
    Try {
      val json = Json.obj("v" -> 1.asJson, "entries" -> index.values.toVector.map(metaToJson).asJson)
      val tmp = indexFile / os.up / s"index.json.tmp-${System.nanoTime()}"
      os.write.over(tmp, json.noSpaces.getBytes("UTF-8"))
      os.move(tmp, indexFile, replaceExisting = true)
      setPerms0600(indexFile)
    }.failed.foreach(err => logger.warn(s"[file-open-cache] index save failed: ${err.getMessage}"))

  /** blob 原子落盘（tmp + move；0600）。 */
  private def writeBlobLocked(file: os.Path, bytes: Array[Byte]): Unit =
    val tmp = file / os.up / s"${file.last}.tmp-${System.nanoTime()}"
    os.write.over(tmp, bytes)
    os.move(tmp, file, replaceExisting = true)
    setPerms0600(file)

  private def evictOneLocked(): Boolean =
    index.headOption match
      case None => false
      case Some((k, m)) =>
        index.remove(k); indexBytesTotal -= m.bytes
        val freedHot = hot.remove(k).map(e => e._1.length.toLong).getOrElse(0L)
        hotBytesTotal = hotBytesTotal - freedHot
        Try(os.remove(blobsRoot / m.file))
        true

  /** 超限即按 LRU（最久未用）淘汰到上限内。 */
  private def evictToCapLocked(): Unit =
    while indexBytesTotal > diskCapBytes && evictOneLocked() do ()

  private def hotPutLocked(key: String, bytes: Array[Byte], fp: Fingerprint): Unit =
    hot.remove(key).foreach(old => hotBytesTotal -= old._1.length)
    hot(key) = (bytes, fp)
    hotBytesTotal += bytes.length
    while hotBytesTotal > hotCapBytes && hot.nonEmpty do
      val first = hot.head
      hot.remove(first._1)
      hotBytesTotal -= first._2._1.length

  /** 入账（写 blob + 索引 + 热）。超 maxEntryBytes 的件不入（无部分件缓存——缓存字节必须恒等于真件内容）。 */
  private def putEntry(key: String, fp: Fingerprint, bytes: Array[Byte], sha16: String, kind: String): Unit =
    lock.synchronized {
      if enabled && bytes.length <= maxEntryBytes then
        ensureLoaded()
        val name = blobName(key)
        Try(writeBlobLocked(blobsRoot / name, bytes)).failed.foreach(err =>
          logger.warn(s"[file-open-cache] blob write failed: ${err.getMessage}")
        )
        index.remove(key).foreach(old => indexBytesTotal -= old.bytes)
        val now = System.currentTimeMillis()
        index(key) = FileOpenCacheMeta(key, name, bytes.length, fp.size, fp.mtimeMs, sha16, now, now, kind)
        indexBytesTotal += bytes.length
        evictToCapLocked()
        hotPutLocked(key, bytes, fp)
        cPuts.incrementAndGet()
        saveIndexLocked()
      ()
    }

  /**
   * 本机整件读穿（台账 #902 ①②）：指纹（size+mtime，调用腿开盘现值）比对——
   * 未变：热层 → 持久层直出（零磁盘读）；变了：条目立即淘汰 + 全量读 + 重新入账。
   * `fromCache=false` 即真实磁盘读发生（= `diskReads` 计数）。
   */
  def readWholeThrough(path: os.Path, fp: Fingerprint): LocalRead =
    if !enabled then
      cDiskReads.incrementAndGet()
      LocalRead(os.read(path), fromCache = false)
    else
      val key = localKey(path)
      val hitBytes: Option[Array[Byte]] = lock.synchronized {
        ensureLoaded()
        index.get(key) match
          case Some(m) if !m.fpMatch(fp.size, fp.mtimeMs) => evictLocked(key) // 指纹变 = 立即失效
          case _ => ()
        hot.get(key) match
          case Some((b, _)) => cHotHits.incrementAndGet(); Some(b)
          case None =>
            index.get(key).flatMap { m =>
              Try(os.read.bytes(blobsRoot / m.file)).toOption.map { b =>
                hotPutLocked(key, b, fp)
                cDiskHits.incrementAndGet()
                touchLocked(key, m)
                b
              }
            }
      }
      hitBytes match
        case Some(b) =>
          // 与 os.read 同源解码（UTF-8；存取同字符集，字节恒等往返）。
          LocalRead(new String(b, java.nio.charset.StandardCharsets.UTF_8), fromCache = true)
        case None =>
          val content = os.read(path)
          cDiskReads.incrementAndGet()
          putEntry(key, fp, content.getBytes(java.nio.charset.StandardCharsets.UTF_8), sha16 = "", kind = "local")
          LocalRead(content, fromCache = false)
  end readWholeThrough

  private def evictLocked(key: String): Unit =
    index.remove(key).foreach { m =>
      indexBytesTotal -= m.bytes
      val freedHot = hot.remove(key).map(e => e._1.length.toLong).getOrElse(0L)
      hotBytesTotal = hotBytesTotal - freedHot
      Try(os.remove(blobsRoot / m.file))
      cInvalidations.incrementAndGet()
      saveIndexLocked()
    }

  private def touchLocked(key: String, m: FileOpenCacheMeta): Unit =
    index.remove(key); m.last = System.currentTimeMillis(); index(key) = m

  /**
   * 跨设备查（台账 #902 ③）：探测到的对端指纹比对——命中返回缓存字节（调用方
   * 跳过全量分块拉取）；指纹不符 = 条目立即淘汰 + None（走全量拉取重入账）。
   */
  def lookupRemote(deviceId: String, remotePath: String, fp: Fingerprint): Option[RemoteHit] =
    if !enabled then None
    else
      val key = remoteKey(deviceId, remotePath)
      lock.synchronized {
        ensureLoaded()
        index.get(key) match
          case Some(m) if !m.fpMatch(fp.size, fp.mtimeMs) => evictLocked(key); None
          case Some(m) =>
            hot.get(key) match
              case Some((b, _)) => cHotHits.incrementAndGet(); Some(RemoteHit(b, m.sha16))
              case None =>
                Try(os.read.bytes(blobsRoot / m.file)).toOption.map { b =>
                  hotPutLocked(key, b, fp)
                  cDiskHits.incrementAndGet()
                  touchLocked(key, m)
                  RemoteHit(b, m.sha16)
                }
          case None => None
      }

  /** 跨设备入账：对端取件成功后把整件字节入本机持久层（下次同件探测命中即免拉）。 */
  def putRemote(deviceId: String, remotePath: String, fp: Fingerprint, bytes: Array[Byte], sha16: String): Unit =
    putEntry(remoteKey(deviceId, remotePath), fp, bytes, if sha16 == null then "" else sha16.take(16), kind = "dev")

  /**
   * 取件落位（近端优先）：缓存命中的字节在 [[PointerPickup]] 单点落位——幂等：
   * 上次落位读数（matFp）仍吻合即零写直返；二进制腿由此保住 `/api/nf-file`
   * `absPath` 取回契约。必须在锁内做 stat+写（与账面突变同原子窗）。
   */
  def ensurePickup(deviceId: String, remotePath: String, bytes: Array[Byte]): os.Path =
    val target = PointerPickup.pickupPath(deviceId, remotePath)
    lock.synchronized {
      val key = remoteKey(deviceId, remotePath)
      val fresh = index.get(key).exists { m =>
        m.matMtime != 0L && os.exists(target) &&
        Try(os.size(target)).toOption.contains(m.matSize) &&
        Try(os.mtime(target)).toOption.contains(m.matMtime)
      }
      if fresh then target
      else
        Try {
          os.makeDir.all(target / os.up)
          os.write.over(target, bytes)
          index.get(key).foreach { m =>
            m.matSize = os.size(target)
            m.matMtime = os.mtime(target)
          }
        }.failed.foreach(err => logger.warn(s"[file-open-cache] pickup materialize failed: ${err.getMessage}"))
        target
    }

  end ensurePickup

  /**
   * 远端列表应答 → 单件指纹（纯函数，gateway 探测腿消费）。要求 size≥0 且
   * mtime>0 齐备——缺任一即 None（旁路走全量拉取，绝不以残缺指纹放行命中）。
   */
  def fingerprintFromListing(listJson: Json, fileName: String): Option[Fingerprint] =
    val entries = listJson.hcursor.downField("entries").as[Vector[Json]].getOrElse(Vector.empty)
    entries.view
      .flatMap { e =>
        val c = e.hcursor
        for
          n <- c.downField("name").as[String].toOption
          if n == fileName || n.equalsIgnoreCase(fileName)
          sz <- c.downField("size").as[Long].toOption
          mt <- c.downField("mtime").as[Long].toOption
          if sz >= 0L && mt > 0L
        yield Fingerprint(sz, mt)
      }
      .take(1)
      .toList
      .headOption

  end fingerprintFromListing

  /** 写穿失效（本机路径；`NfCacheInvalidator` 单点消费）。目录删除走前缀扫。 */
  def invalidateLocalPath(pathStr: String): Unit =
    if pathStr != null && pathStr.nonEmpty then
      lock.synchronized {
        if loadedRoot != null then
          evictLocked(localKey(os.Path(pathStr, os.pwd)))
          val prefix = "local:" + pathStr.replaceAll("""[/\\]+""", "/")
          val victims = index.keys.filter(k => k.startsWith(prefix + "/")).toList
          victims.foreach(evictLocked)
        ()
      }

  /** 写穿失效（跨设备键；远端 scaffold 写 / 远端删除的消费口）。 */
  def invalidateRemote(deviceId: String, remotePath: String): Unit =
    lock.synchronized(evictLocked(remoteKey(deviceId, remotePath)))

  /** ③ 探测指纹命中 ⇒ 跳过对端全量分块拉取的显式计数（gateway 命中腿外发帧前调用）。 */
  def noteRemotePullSkip(): Unit =
    cRemotePullSkips.incrementAndGet()
    ()

  /** spec 专用：清进程态（热层 + 账面重载旗标）模拟引擎重启——持久层原样。 */
  def dropProcessStateForTest(): Unit =
    lock.synchronized {
      hot.clear(); hotBytesTotal = 0L; loadedRoot = null; ()
    }

end FileOpenCache

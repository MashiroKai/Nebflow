package nebflow.core.tools

import cats.effect.IO
import nebflow.shared.{NebflowLogger, PathUtil}

/**
 * PointerPickup —— 浏览面单件取件的本地落盘（v2.1 阶段 1 §C.2「按需取件」的
 * 落位单点；产物指针登记不迁移语义的本地读数端）。
 *
 * WS 文件浏览器 `device` 腿（handleReadFile）把对端单件经 scope=browse 通道
 * （RemoteExecutor.transferGetScoped——出口白名单在对端闸）取回字节后落在这里，
 * 帧回 `absPath` = 落盘件：文本腿内联 content；二进制腿由前端经 `/api/nf-file`
 * 取回本地落盘件。
 *
 * **落位 = `<dataRoot>/uploads/pickups/<pointerHash>/<name>`**（作者裁定记录）：
 * `uploads` 既是 NfDataRootAllowlist 既有成员（/api/nf-file 对二进制腿放行——
 * 取件本来就是用户点击要看的面），也是 EgressWhitelist 既有固定根——零策略表
 * 改动。子目录 `pickups/` 与网页上传件隔离；被上传清扫误删也无害（指针可重取，
 * 见下）。
 *
 * `pointerHash` = sha256(`<deviceId>|<remotePath>`) 前 16 hex——指针幂等键：
 * 同一 (device, path) 重取**覆盖**落盘件（缓存语义：新字节胜出，mtime 刷新 =
 * TTL 滑窗）。**字节不迁移**：登记面（TaskLedger 注记 / ExternalEvent
 * artifactPointers）只记 (device, path) 指针，落盘件是按需取件的临时可读副本。
 *
 * TTL 清扫 = [[CapturePull]] 同族（7 天、逐件 INFO `[pickups-ttl]` 留痕、
 * 🔴 禁静默删；空目录回收，根保留）。清扫 fiber 挂在 RemoteExecutor.initialize
 * （与 captures 清扫同一条 6h 周期）。
 */
object PointerPickup:

  private val logger = NebflowLogger.forName("nebflow.core.tools.pointer-pickup")

  /** TTL：7 天（与 CapturePull.CaptureTtlMs 同值同族；单点对齐禁两处漂移——引用它）。 */
  val PickupTtlMs: Long = CapturePull.CaptureTtlMs

  /** 单件大小闸的对端（发送侧）口径 = RemoteExecutor.MaxScopedFetchBytes；本侧不再设闸。 */

  /** 落盘根（def 非 val：dataRoot 可重定向——f1cd3709 规则）。 */
  def pickupsRoot: os.Path = PathUtil.dataRoot / "uploads" / "pickups"

  /** 指针幂等键：sha256(`<deviceId>|<remotePath>`) 前 16 hex。 */
  def pointerHash(deviceId: String, remotePath: String): String =
    val d = java.security.MessageDigest.getInstance("SHA-256")
    d.digest(s"$deviceId|$remotePath".getBytes("UTF-8")).map(b => f"${b & 0xff}%02x").mkString.take(16)

  /**
   * 落盘路径单点：`pickups/<pointerHash>/<消毒后 basename>`（消毒序与 [[save]] 同款）。
   *
   * 2026-10-20（M2 分块取件腿）：从 `save` 提出为独立入口——分块拉取腿（BrowseGetCursor）
   * 要在**首块之前**就知道落点（对既有部分件做续传对齐），不能再等整件到齐才算名。
   * 与 `save` 同一消毒序（同一条 basename 正则），禁第二份实现。
   */
  def pickupPath(deviceId: String, remotePath: String): os.Path =
    val dir = pickupsRoot / pointerHash(deviceId, remotePath)
    val base = remotePath.replaceAll("""[/\\]""", "/").split("/").lastOption.getOrElse("pickup")
    val safe = base.replaceAll("""[^A-Za-z0-9._\-]""", "_").take(80)
    dir / safe

  /**
   * 落盘：`pickups/<pointerHash>/<消毒后 basename>`。同名重取覆盖（缓存语义）；
   * basename 消毒防穿越/非法字符（消毒序单点 = [[pickupPath]]）。
   */
  def save(deviceId: String, remotePath: String, content: Array[Byte]): os.Path =
    val dir = pickupsRoot / pointerHash(deviceId, remotePath)
    os.makeDir.all(dir)
    val f = pickupPath(deviceId, remotePath)
    os.write.over(f, content)
    logger.info(s"[pickup] saved remote pointer $deviceId:$remotePath -> $f (${content.length} bytes)")
    f

  /**
   * TTL 清扫（CapturePull.sweepExpired 同族）：逐件按 mtime 判过期即删，每删一件
   * INFO 一行（`[pickups-ttl]`）；空目录回收（根保留）。返回删除件数。
   */
  def sweepExpired(now: Long = System.currentTimeMillis()): IO[Int] = IO
    .blocking {
      val root = pickupsRoot
      if !os.exists(root) then 0
      else
        var removed = 0
        os.walk(root).filter(os.isFile(_)).foreach { f =>
          val age = now - os.mtime(f)
          if age > PickupTtlMs then
            val bucket = f.relativeTo(root).segments.headOption.getOrElse("?")
            os.remove(f)
            removed += 1
            logger.info(s"[pickups-ttl] removed ${f.last} (age=${age / 86400000L}d > 7d TTL, pointer=$bucket)")
        }
        // 清掉空掉的指针目录（根目录保留）
        os.walk(root).foreach(d => if os.isDir(d) && d != root && os.list(d).isEmpty then os.remove(d))
        removed
    }
    .handleErrorWith { e =>
      logger.warn(s"[pickups-ttl] sweep failed: ${e.getMessage}").as(0)
    }

end PointerPickup

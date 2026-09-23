package nebflow.core.hotupdate

import cats.effect.IO

import nebflow.core.Branding

/**
 * 版本指针读取与比对——**单一实现**。
 *
 * 复用点（设计附录 B #29）：既有设置页版本检查（`WebSocketRoutes.scala:3622-3662`）的
 * 「读镜像版本指针 → 剥前导 v → 与当前版本比对」逻辑本体抽出到此处，使更新编排器的
 * 「检查」相位与设置页检查按钮走**同一实现**——禁第二套比对（双实现必然漂移）。
 * 网络语义与本批前逐字节一致：连接与读取各 5 秒超时、任何异常 ⇒ None（失败静默）。
 */
object VersionCheck:

  /** 正式通道版本指针地址（桶名走 `Branding.cosBucket` 派生，禁硬编码桶名）。 */
  def pointerUrl: String =
    s"https://${Branding.cosBucket}.oss-cn-hangzhou.aliyuncs.com/latest-version.txt"

  /**
   * 读版本指针原文（前导 `v` 由调用方决定剥不剥——设置页把原文当发布名回传，
   * 更新编排器剥前缀后当版本号）。连接与读取各 5 秒超时；异常 / 空响应 ⇒ None。
   */
  def fetchRaw(url: String = pointerUrl): IO[Option[String]] =
    IO.blocking {
      try
        val conn = java.net.URI.create(url).toURL.openConnection()
        conn.setConnectTimeout(5000)
        conn.setReadTimeout(5000)
        val raw =
          new String(conn.getInputStream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).trim
        if raw.nonEmpty then Some(raw) else None
      catch case _: Exception => None
    }

  /** 最新版本号（剥前导 `v`；无指针 / 不可达 ⇒ None）。 */
  def fetchLatestVersion(url: String = pointerUrl): IO[Option[String]] =
    fetchRaw(url).map(_.map(_.stripPrefix("v")))

  /** 是否有更新（异值即有更新——与既有检查链同口径）。 */
  def hasUpdate(current: String, latest: String): Boolean = latest != current
end VersionCheck

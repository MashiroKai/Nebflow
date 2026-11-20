/*
 * AssetVersions — 静态资源缓存击穿批（2026-10-25 作者令④）：dev 树静态资源的
 * 内容寻址版本指纹。
 *
 * 症状（作者 2026-10-25 00:36 审计）：修好的前端到不了作者手机 —— 手机浏览器
 * （及隧道 relay）把 /css、/js、/vendor 的旧字节按长效缓存钉死，引擎侧新内容
 * 推不进去（服务端对既有缓存键无失效手段）。本件给 dev 树静态资源引入
 * **内容寻址版本化 URL**：index.html 里每个 css/…、vendor/…、js/… 引用改写为
 * `<path>?v=<sha-256 前 16 hex>`，配套路由面（jsRoutes / vendorSubtree /
 * WebSocketRoutes 的 css/vendor/favicon 族）对带版本参数的请求回
 * `Cache-Control: public, max-age=31536000, immutable`（内容变 ⇒ URL 变 ⇒
 * 缓存自然失效，immutable 永远安全）；**裸路径请求保持 no-cache**（ES module
 * 图内的相对 import 不带版本参数，靠既有 sw.js 索引指纹链 + no-cache 重验证
 * 保新鲜——本面不改模块图字节，不重写 import 指示符）。
 *
 * 新鲜度判据不变：index.html 仍 no-cache（AssetsRoutesSpec W2 既有契约），
 * 是唯一的新鲜裁判；版本参数只服务「index 引用的资产可安全 immutable」。
 *
 * 失效时机：hash 按 classpath 资源字节现算，file: 形态资源以 mtime 作缓存键
 * （sbt run 热编辑 ⇒ 重新拷贝 ⇒ mtime 变 ⇒ hash 重算 ⇒ index 引用即变）；
 * jar/其它协议形态资源在 JVM 生命周期内字节不可变 ⇒ 每 boot 算一次即终身有效。
 * 任何读取意外（缺文件/IO 错）→ None → 调用面原 tag 原样回落（加速面不是
 * 语义面，与 packedIndexHtml 的回落纪律同族）。
 */
package nebflow.gateway

import java.util.concurrent.ConcurrentHashMap

object AssetVersions:

  /** 版本指纹 = SHA-256 前 16 hex（碰撞概率对静态资源集可忽略；URL 短）。 */
  val HexLen: Int = 16

  private case class Entry(mtimeMs: Long, hash: String)

  private val cache = new ConcurrentHashMap[String, Entry]()

  /**
   * `classpathName`（如 "web/css/base.css"）的当前内容指纹。资源不存在 / 读取
   * 失败 ⇒ None（调用面回落为不版本化）。file: 资源 mtime 变化即重算（热编辑
   * 生效面）；非 file: 资源（jar）按不可变处理，每 boot 只算一次。
   */
  def versionOf(classpathName: String): Option[String] =
    val cl = getClass.getClassLoader
    val url = cl.getResource(classpathName)
    if url == null then None
    else
      val mtime: Long =
        if url.getProtocol == "file" then
          try
            val path = java.nio.file.Paths.get(url.toURI)
            java.nio.file.Files.getLastModifiedTime(path).toMillis
          catch case _: Throwable => -1L
        else -1L // jar:/bundle 资源在 JVM 生命周期内字节不变
      val hit = cache.get(classpathName)
      if hit != null && hit.mtimeMs == mtime then Some(hit.hash)
      else
        val in = cl.getResourceAsStream(classpathName)
        if in == null then None
        else
          try
            val md = java.security.MessageDigest.getInstance("SHA-256")
            val digest = md.digest(in.readAllBytes())
            val hash = digest.map(b => f"${b & 0xff}%02x").mkString.take(HexLen)
            cache.put(classpathName, Entry(mtime, hash))
            Some(hash)
          catch case _: Throwable => None
          finally in.close()
    end if
  end versionOf
end AssetVersions

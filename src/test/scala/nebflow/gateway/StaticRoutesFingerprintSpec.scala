package nebflow.gateway

import munit.FunSuite

/**
 * 陈旧 SPA 指纹提示批（方案 B 小刀③，2026-10-28）——服务端半面的结构钉。
 *
 * 钉三件事：① `fingerprintOf` 纯函数面（SHA-256 前 16 hex、确定性、字节敏感）；
 * ② `webFingerprint` 惰性单值在本仓 static tree 上非空（三 serve 脸同源取数——
 * 空串 = 计算失败，客户端真值守卫静默跳过）；③ 指纹注入脚本形态（brand 注入同
 * 点进 served body 的载体；空指纹不注入 = 测试 harness 静态树零假阳）。
 */
class StaticRoutesFingerprintSpec extends FunSuite:

  test("fingerprintOf: deterministic 16-hex digest, byte-sensitive, prefix-of-sha256") {
    val a = StaticRoutes.fingerprintOf("<html>alpha</html>")
    val b = StaticRoutes.fingerprintOf("<html>alpha</html>")
    val c = StaticRoutes.fingerprintOf("<html>alphA</html>")
    assertEquals(a, b, "同字节 ⇒ 同指纹（确定性）")
    assertNotEquals(a, c, "一字节之差 ⇒ 指纹必变（新鲜度判据）")
    assertEquals(a.length, 16, "16 hex = 64 bit 显示面")
    assert(a.matches("[0-9a-f]{16}"), a)
    val md = java.security.MessageDigest.getInstance("SHA-256")
    val full = md.digest("<html>alpha</html>".getBytes(java.nio.charset.StandardCharsets.UTF_8))
    assertEquals(a, full.take(8).map(b => f"${b & 0xff}%02x").mkString, "指纹 = SHA-256 前 8 字节 hex")
  }

  test("webFingerprint: non-empty on this repo's static tree (lazy single-value)") {
    assert(StaticRoutes.webFingerprint.nonEmpty, "本仓 web/ 树在位 ⇒ 指纹必可算")
    assert(StaticRoutes.webFingerprint.matches("[0-9a-f]{16}"), StaticRoutes.webFingerprint)
    // 惰性单值：二次读同值（JVM 生命周期内恒定，同引擎所有脸/所有帧同源）。
    assertEquals(StaticRoutes.webFingerprint, StaticRoutes.webFingerprint)
  }

  test("fingerprint script tag: carries the webFingerprint value, shape window.__WEB_FINGERPRINT__") {
    val tag = StaticRoutes.fingerprintScriptTag
    val fp = StaticRoutes.webFingerprint
    assertEquals(tag, s"""<script>window.__WEB_FINGERPRINT__="$fp";</script>""", "注入形态钉死（与 ws/main 消费面配对）")
  }

package nebflow.core.browser

import munit.FunSuite

import java.nio.file.{Files, Path}

/*
 * cubu-round2 批三 · secret 引用解析契约（纯函数，临时目录，不触碰用户真实 secrets 库）。
 *
 * 钉死：
 *  1. 名称白名单：合法名通过；路径分隔符、`..`、大写、空、超长、前导点一律拒绝。
 *  2. 解析：存在且可读 → 明文；缺失 / 非法 / 非引用语法 → 统一 Refusal，文案不含名称与值。
 *  3. 非引用文本不进入解析（isRef 判定）。
 */
class BrowserSecretRefSpec extends FunSuite:

  private def tmpSecrets(files: (String, String)*): Path =
    val dir = Files.createTempDirectory("cubu-secrets")
    files.foreach { case (n, v) => Files.writeString(dir.resolve(n), v) }
    dir

  test("名称白名单：合法名通过；分隔符 / .. / 大写 / 空 / 超长 / 前导点拒绝") {
    assert(BrowserSecretRef.validName("github-pat"))
    assert(BrowserSecretRef.validName("sep_login.1"))
    assert(!BrowserSecretRef.validName("../x"))
    assert(!BrowserSecretRef.validName("a/b"))
    assert(!BrowserSecretRef.validName("a..b"))
    assert(!BrowserSecretRef.validName("Upper"))
    assert(!BrowserSecretRef.validName(""))
    assert(!BrowserSecretRef.validName(".hidden"))
    assert(!BrowserSecretRef.validName("a" * 65))
  }

  test("引用语法判定：仅前缀判定（去首尾空白后以 secret: 开头）") {
    assert(BrowserSecretRef.isRef("secret:github-pat"))
    assert(BrowserSecretRef.isRef("  secret:x "))
    assert(!BrowserSecretRef.isRef("hello secret:x"))
    assert(!BrowserSecretRef.isRef("plain"))
  }

  test("解析：存在的命名 secret → 明文（仅此返回值携带）") {
    val dir = tmpSecrets("github-pat" -> "s3cret-value")
    assertEquals(BrowserSecretRef.resolve(dir, "secret:github-pat"), Right("s3cret-value"))
  }

  test("解析失败统一拒绝：缺失 / 路径穿越 / 非法名 / 非引用 —— 文案不含名称与值") {
    val dir = tmpSecrets("github-pat" -> "s3cret-value")
    List("secret:missing", "secret:../github-pat", "secret:Upper", "secret:", "plain text").foreach { in =>
      BrowserSecretRef.resolve(dir, in) match
        case Right(v) => fail(s"should refuse `$in` but got a value")
        case Left(msg) =>
          assertEquals(msg, BrowserSecretRef.Refusal)
          assert(!msg.contains("s3cret-value"), "refusal must not echo the value")
          assert(!msg.contains("missing") && !msg.contains("github-pat"), "refusal must not echo the name")
    }
  }

  test("守卫文案：保留前缀文本即使到达最终层也拒绝（防二次引用展开）") {
    assert(BrowserPolicy.fillRefusal("secret:x").isDefined)
    assertEquals(BrowserPolicy.fillRefusal("hello"), None)
  }

end BrowserSecretRefSpec

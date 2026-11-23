package nebflow.core.browser

import cats.effect.IO
import munit.CatsEffectSuite

import java.nio.file.Files

/*
 * cubu-round2 批三 · secret 注入 password 框的真浏览器端到端（门控：NEBFLOW_BROWSER_E2E=1 且本机有 Chrome）。
 *
 * 值只放在临时目录（不写用户真实 secrets 库）；页面按注入值长度回写 LEN=n，断言长度而不打印值。
 * 同时断言：模型直接键入（allowPassword=false）到 password 框仍被拒。
 */
class BrowserSecretInjectSpec extends CatsEffectSuite:

  override def munitIOTimeout: scala.concurrent.duration.Duration =
    scala.concurrent.duration.Duration(5, "min")

  private val refRe = """\[ref=(e\d+)\]""".r
  private def refsOf(snap: String, role: String): List[String] =
    snap.linesIterator
      .filter(_.contains(s"- $role "))
      .flatMap(line => refRe.findFirstMatchIn(line).map(_.group(1)))
      .toList

  test("E2E：secret 引用注入 password 框（allowPassword）成功；直接键入 password 仍拒绝") {
    assume(sys.env.contains("NEBFLOW_BROWSER_E2E"), "live Chrome e2e disabled (set NEBFLOW_BROWSER_E2E=1)")
    val home = Files.createTempDirectory("bsi-home")
    val secrets = Files.createTempDirectory("bsi-secrets")
    val secretValue = "cubu-probe-value-12"
    Files.writeString(secrets.resolve("cubu-probe"), secretValue)
    val page = home.resolve("login.html")
    Files.writeString(
      page,
      "<html><head><title>bsi</title></head><body>" +
        "<label>Plain <input type=\"text\" id=\"t\"></label>" +
        "<label>Pass <input type=\"password\" id=\"p\"></label>" +
        "<button onclick=\"var v=document.getElementById('p').value;" +
        "document.body.insertAdjacentText('beforeend','LEN='+v.length)\">Check</button></body></html>"
    )
    val name = "bsi-e2e"
    val url = page.toUri.toString
    val pass: IO[Unit] =
      for
        _ <- IO.blocking(BrowserSessions.open(name, Some(url), home))
        snap <- IO.blocking(BrowserSessions.snapshot(name, 50, home))
        // DOM 顺序：第一个 textbox = 普通文本框，第二个 = password 框。
        tRef = refsOf(snap, "textbox").headOption.getOrElse("")
        pRef = refsOf(snap, "textbox").lift(1).getOrElse("")
        bRef = refsOf(snap, "button").headOption.getOrElse("")
        // 直接键入 password 框：必须拒绝（模型不能键入凭据）。
        direct <- IO.blocking(scala.util.Try(BrowserSessions.fill(name, pRef, "typed-by-model", home)))
        _ <- IO.blocking(assert(direct.isFailure, "typed password must be refused"))
        resolved <- IO.blocking(BrowserSecretRef.resolve(secrets, "secret:cubu-probe"))
        value <- IO.fromEither(resolved.left.map(new IllegalStateException(_)))
        // secret 注入普通文本框：必须拒绝（凭据不得进入普通输入框）。
        toPlain <- IO.blocking(scala.util.Try(BrowserSessions.fill(name, tRef, value, home, allowPassword = true)))
        _ <- IO.blocking(assert(toPlain.isFailure, "secret into plain textbox must be refused"))
        // 经 secret 引用注入 password 框：允许。
        _ <- IO.blocking(BrowserSessions.fill(name, pRef, value, home, allowPassword = true))
        _ <- IO.blocking(BrowserSessions.click(name, bRef, home))
        waited <- IO.blocking(BrowserSessions.wait(name, Some(s"LEN=${secretValue.length}"), None, Some(5000L), home))
        _ <- IO.blocking(BrowserSessions.close(name, home))
      yield assert(waited.nonEmpty, "injected secret length not observed on page")
    pass
  }

end BrowserSecretInjectSpec

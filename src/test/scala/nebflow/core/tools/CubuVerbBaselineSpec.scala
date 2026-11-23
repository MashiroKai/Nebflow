package nebflow.core.tools

import cats.effect.IO
import io.circe.syntax.*
import io.circe.{Json, JsonObject}
import munit.CatsEffectSuite

import java.nio.file.{Files, Paths}

/*
 * cubu-round2 · 经动词的端到端基线（A 步 / 对比矩阵的「改前/改后」同一矩阵）。
 *
 * 不直调 BrowserSessions / DesktopObserve：每个动词都经 ToolRegistry.TOOL_MAP(name).call(input, ctx)，
 * 即与模型直调同一路径（身份闸 → 参数校验 → 执行 → 错误收口）。
 * 默认跳过；设 CUBU_BASELINE=1 才真跑（会开真实 Chrome、读真实桌面，仅只读 + desktop_open 后台启动 Calculator）。
 * CUBU_LABEL=baseline|after 决定输出文件名；CUBU_EVIDENCE_DIR 覆盖证据目录。
 * 输出 JSONL：verb / scenario / ok / ms / cls（失败分类）/ detail（截断，剪贴板仅记长度，不落内容）。
 */
class CubuVerbBaselineSpec extends CatsEffectSuite:

  // 真浏览器 + 真桌面的整轮耗时远超 munit 默认 30s，单独放宽。
  override def munitIOTimeout: scala.concurrent.duration.Duration =
    scala.concurrent.duration.Duration(15, "min")

  private val enabled: Boolean = sys.env.contains("CUBU_BASELINE")
  private val label: String = sys.env.getOrElse("CUBU_LABEL", "baseline")
  private val outDir = Paths.get(
    sys.env.getOrElse("CUBU_EVIDENCE_DIR", "/Users/kaiyu/Claude code/Stand/.stand/tmp/cubu-round2/evidence")
  )

  // 非空会话身份即过身份基本校验（插件分发裁定）；anon 用于匿名负向用例。
  private val ctx: ToolContext = ToolContext(projectRoot = "/tmp", sessionId = Some("cubu-baseline-probe"))
  private val anon: ToolContext = ToolContext(projectRoot = "/tmp")

  private val session = "cubu-base"

  final case class Row(verb: String, scenario: String, ok: Boolean, ms: Long, cls: String, detail: String)
  private val rows = scala.collection.mutable.ListBuffer[Row]()

  private def classify(msg: String): String =
    val m = msg.toLowerCase
    if msg.contains("HOST-PROTECTED") then "HOST_PROTECTED"
    else if msg.contains("BROWSER_FORBIDDEN") || m.contains("permission denied") then "PERMISSION"
    else if msg.contains("secret:") then "REFUSED_SECRET"
    else if m.contains("invalid session name") then "INVALID_INPUT"
    else if m.contains("timed out") || m.contains("timeout") then "TIMEOUT"
    else if m.contains("accessibility") || m.contains("screen recording") then "TCC_DENIED"
    else "OTHER"

  private def run(
      verb: String,
      scenario: String,
      input: JsonObject,
      c: ToolContext = ctx,
      quiet: Boolean = false
  ): IO[Either[ToolError, String]] =
    IO.defer {
      val t0 = System.nanoTime()
      ToolRegistry.TOOL_MAP(verb).call(input, c).map { r =>
        val ms = (System.nanoTime() - t0) / 1000000L
        val (ok, cls, detail) = r match
          case Right(out) => (true, "OK", if quiet then s"len=${out.length}" else out.take(160))
          case Left(e) => (false, classify(e.message), e.message.take(160))
        rows += Row(verb, scenario, ok, ms, cls, detail)
        r
      }
    }

  private def refOf(snap: String, role: String): Option[String] =
    snap.linesIterator
      .find(_.contains(s"- $role "))
      .flatMap(line => """\[ref=(e\d+)\]""".r.findFirstMatchIn(line).map(_.group(1)))

  test("CUBU 基线：九浏览器动词 + 六桌面动词 + 负向守卫，全部经 TOOL_MAP.call") {
    assume(enabled, "set CUBU_BASELINE=1 to run the live verb baseline")

    val home = Files.createTempDirectory("cubu-base")
    val page = home.resolve("index.html")
    Files.writeString(
      page,
      "<html><head><title>cubu-base</title></head><body>" +
        "<label>Name <input type=\"text\" id=\"n\"></label>" +
        "<button onclick=\"document.body.insertAdjacentText('beforeend','CLICKED')\">Go</button>" +
        // file:// 下 download 属性不触发下载（Chrome 当导航），改用 data: URL 作为可下载夹具。
        "<a href=\"data:text/plain;charset=utf-8,cubu-baseline-download-fixture\" download=\"data.txt\">Get data</a></body></html>"
    )
    val url = page.toUri.toString

    def js(kv: (String, Json)*): JsonObject = JsonObject(kv*)
    def str(s: String): Json = s.asJson
    // 浏览器动词一律显式带会话名（默认 `default` 会串会话）。
    def bjs(kv: (String, Json)*): JsonObject = js(("session" -> str(session)) +: kv*)

    for
      // ── 浏览器九动词（主流程）──
      _ <- run("browser_open", "open-local-page", bjs("url" -> str(url)))
      snap1 <- run("browser_snapshot", "snapshot", bjs())
      _ <- run("browser_state", "state-after-open", bjs())
      textRef = snap1.toOption.flatMap(refOf(_, "textbox")).getOrElse("e0")
      btnRef = snap1.toOption.flatMap(refOf(_, "button")).getOrElse("e0")
      _ <- run("browser_fill", "fill-textbox", bjs("ref" -> str(textRef), "text" -> str("cubu probe")))
      _ <- run("browser_click", "click-button", bjs("ref" -> str(btnRef)))
      _ <- run("browser_wait", "wait-body-text", bjs("text" -> str("CLICKED"), "ms" -> 3000.asJson))
      _ <- run("browser_wait", "wait-url", bjs("url_contains" -> str("index.html"), "ms" -> 2000.asJson))
      _ <- run("browser_screenshot", "screenshot-viewport", bjs())
      snap2 <- run("browser_snapshot", "snapshot-for-download", bjs())
      linkRef = snap2.toOption.flatMap(refOf(_, "link")).getOrElse("e0")
      _ <- run("browser_download", "download-fixture-link", bjs("ref" -> str(linkRef), "timeout_ms" -> 30000.asJson))
      _ <- run("browser_close", "close-session", bjs())
      // ── 浏览器负向守卫 ──
      // 名称确定不存在：负向用例不依赖用户 secrets 库内容（库内若有同名项则会解析通过，见 batch-3 发现）。
      _ <- run("browser_fill", "neg-secret-missing", bjs("ref" -> str("e1"), "text" -> str("secret:cubu-no-such-secret-xyz")))
      _ <- run("browser_snapshot", "neg-anonymous", bjs(), anon)
      _ <- run("browser_state", "neg-invalid-session", js("session" -> str("../x")))

      // ── 桌面六动词（观测 + 后台启动）──
      _ <- run("desktop_list_apps", "list-apps", js())
      _ <- run("desktop_list_windows", "list-windows", js())
      _ <- run("desktop_displays", "displays", js())
      _ <- run("desktop_clipboard_read", "clipboard-len-only", js(), quiet = true)
      _ <- run("desktop_screenshot", "region-400x300", js("mode" -> str("region"), "region" -> str("0,0,400,300")))
      _ <- run("desktop_screenshot", "window-terminal", js("mode" -> str("window"), "app" -> str("Terminal")))
      _ <- run("desktop_open", "open-calculator-bg", js("app" -> str("Calculator")))
      // ── 桌面负向守卫（宿主永拒 / 未授权 / 不支持档）──
      _ <- run("desktop_screenshot", "neg-host-window-stand", js("mode" -> str("window"), "app" -> str("Stand")))
      _ <- run("desktop_open", "neg-host-open-java", js("app" -> str("java")))
      _ <- run("desktop_list_apps", "neg-anonymous", js(), anon)
      _ <- run("desktop_screenshot", "neg-full-unavailable", js("mode" -> str("full")))
    yield
      val lines = rows.toList.map { r =>
        Json
          .obj(
            "verb" -> r.verb.asJson,
            "scenario" -> r.scenario.asJson,
            "ok" -> r.ok.asJson,
            "ms" -> r.ms.asJson,
            "cls" -> r.cls.asJson,
            "detail" -> r.detail.asJson
          )
          .noSpaces
      }
      Files.createDirectories(outDir)
      val out = outDir.resolve(s"verbs-$label.jsonl")
      Files.writeString(out, lines.mkString("", "\n", "\n"))
      println(s"[cubu-baseline] wrote ${rows.size} rows to $out")
      rows.foreach(r => println(s"[cubu-baseline] ${r.verb} / ${r.scenario} ok=${r.ok} ${r.ms}ms ${r.cls}"))
      assert(rows.nonEmpty, "no verb rows recorded")
  }

end CubuVerbBaselineSpec

package nebflow.gateway

import munit.FunSuite

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import scala.jdk.CollectionConverters.*

/**
 * 用户可见面·过程态封存钉（作者令 2026-10-29 11:11 console「这个警告我不是让
 * 隐藏吗？回复丢失告警：bridge-session-dead ×1（将自动补投）」）。
 *
 * 判定（作者令）：过程性自愈状态对用户**零显示**——bridge 会话死亡→自动补投、
 * 屏障扣留→回合边界自动 drain，都是用户无杠杆的内部机制状态，只进引擎面
 * （插件 ERROR/WARN 日志、`channels[].health` JSON、`heldReceipts` WS 帧——三者
 * 均未动）；仅**终态失败**（补投也丢、催注穷尽）保留用户提示。
 *
 * 本批摘除面：
 *   · 社交渠道卡 hint 的健康面分支（`social.hint.health`，zh+en 文案同步退役）；
 *   · 头部 #held-receipts-indicator 胶囊的 held 过程态（胶囊只渲染终态 unread）。
 * 确需用户知晓的失败终态走既有面：bridge 死亡的扫码重绑指引仍发微信会话
 * （bridgeDeadNotice），屏障催注穷尽仍由胶囊 unread 行承载。
 *
 * 钉的是**交付给浏览器的资源字面**（WebJsModuleSyntaxSpec 同源读法；sbt 进程
 * cwd = 仓库根）：
 *   A. 双语言 locale 无这两个键的键定义（退役注释允许提及键名）；
 *   B. 整棵 web 文本资源树零出现过程文案 token：「回复丢失告警」「Reply-loss
 *      alert」「将自动补投」「auto-redelivery armed」「bridge-session-dead」
 *      （含注释——注释层面的复发也算复发，作者看到的字面必须在树里绝迹）；
 *   C. 渲染面不再读过程态：socialPanel.js 无 `cfg.health`/`social.hint.health`
 *      引用；main.js 胶囊无 `heldReceipts.held` 引用；
 *   D. 终态提示**保留**：`heldReceipts.unread`（催注穷尽）双语言仍在且仍被
 *      main.js 使用——防未来清扫误杀终态面。
 */
class UserFaceProcessStateSealSpec extends FunSuite:

  private val webDir: Path =
    Paths.get(os.pwd.toString).resolve("src").resolve("main").resolve("resources").resolve("web")

  private val localesDir = webDir.resolve("js").resolve("locales")

  /** The sealed process-copy tokens — none may appear anywhere in the tree. */
  private val sealedTokens = Seq(
    "回复丢失告警",
    "Reply-loss alert",
    "将自动补投",
    "auto-redelivery armed",
    "bridge-session-dead"
  )

  /** Text faces only — the delivered UI surface; binaries can't carry copy. */
  private val textExts = Set("js", "mjs", "css", "html", "json", "svg")

  private def read(p: Path): String =
    new String(Files.readAllBytes(p), StandardCharsets.UTF_8)

  private def walkText(root: Path): Seq[Path] =
    if !Files.isDirectory(root) then Seq.empty
    else
      val stream = Files.walk(root)
      try
        stream.iterator().asScala.toSeq.filter { p =>
          Files.isRegularFile(p) &&
          Option(p.getFileName.toString).exists(f => textExts.contains(f.substring(f.lastIndexOf('.') + 1).toLowerCase))
        }
      finally stream.close()

  test("floor: the sealed faces exist (a missing tree is a vacuous green)"):
    assert(Files.isDirectory(webDir), s"web tree missing: $webDir (cwd=${os.pwd})")
    for f <- Seq("zh-CN.js", "en.js") do
      val p = localesDir.resolve(f)
      assert(Files.isRegularFile(p), s"locale missing: $p")
      assert(read(p).contains("heldReceipts.unread"), s"$f lost its heldReceipts block?")
    for f <- Seq("socialPanel.js", "main.js") do
      val p = webDir.resolve("js").resolve(f)
      assert(Files.isRegularFile(p) && read(p).nonEmpty, s"js face missing: $p")
    assert(walkText(webDir).size >= 100, "web text tree implausibly small — not looking at the real tree")

  test("locales define neither sealed key (retirement comments may still name them)"):
    for f <- Seq("zh-CN.js", "en.js") do
      val src = read(localesDir.resolve(f))
      for key <- Seq("social.hint.health", "heldReceipts.held") do
        val defines = raw"""(?m)^\s*'$key'\s*:""".r
        assert(
          defines.findFirstIn(src).isEmpty,
          s"$f still defines '$key' — the reply-loss process copy is sealed out of the user face (author ruling 2026-10-29)"
        )

  test("the delivered web tree carries zero process-alert copy tokens"):
    val hits =
      for
        p <- walkText(webDir)
        tok <- sealedTokens
        if read(p).contains(tok)
      yield s"$p :: $tok"
    assert(
      hits.isEmpty,
      s"process self-heal copy reached the user face (author ruling 2026-10-29):\n${hits.mkString("\n")}"
    )

  test("the render faces no longer read the process states"):
    val panel = read(webDir.resolve("js").resolve("socialPanel.js"))
    assert(
      !panel.contains("cfg.health") && !panel.contains("social.hint.health"),
      "socialPanel.js still wires the health-strike hint branch (sealed 2026-10-29)"
    )
    val mainJs = read(webDir.resolve("js").resolve("main.js"))
    assert(
      !mainJs.contains("heldReceipts.held"),
      "main.js still renders the barrier-hold (process) side of the capsule (sealed 2026-10-29)"
    )

  test("the terminal-failure hint survives (retry-exhausted stays user-visible)"):
    for f <- Seq("zh-CN.js", "en.js") do
      val src = read(localesDir.resolve(f))
      val defines = raw"""(?m)^\s*'heldReceipts\.unread'\s*:""".r
      assert(defines.findFirstIn(src).isDefined, s"$f lost the terminal unread hint")
    assert(
      read(webDir.resolve("js").resolve("main.js")).contains("heldReceipts.unread"),
      "main.js no longer surfaces the terminal unread hint"
    )

end UserFaceProcessStateSealSpec

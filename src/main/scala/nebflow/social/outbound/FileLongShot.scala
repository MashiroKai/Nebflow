package nebflow.social.outbound

import cats.effect.IO

import java.nio.file.Files
import scala.util.Try

/**
 * 文件类产物（md/markdown/html/htm）的社交出站「单张长图」腿（pop-social-files
 * batch, 2026-10-20）。
 *
 * 背景（作者令 2026-10-20，承接 2026-10-09 实证口径）：Pop 的文件类产物投递
 * 社交腿 = md/html → 单张长图 PNG（微信/飞书可看——飞书聊天预览不了 md，微信
 * 文件消息实测零到达）；转不动的形态降级为「文件名+本地路径+一句话摘要」文本
 * 卡；去重防轰炸（同一文件多次 Pop 不重复推）；两渠道同口径。
 *
 * 考古结论（三快照 S0=a0f2edfc7 / S1=4c8f79e4c / S2=main）：md 长图转换**从未
 * 实现**（S0 与 main 的 social 包零 diff——早期历史合并未弄丢任何东西，属「从未
 * 实现则按口径补齐」）；html 曾有「文件直发、截图兜底」腿（social-webshot
 * batch 2026-10-09 同日裁定），本批按新裁定**整体退役**——转换升为主腿。
 *
 * 结构（沿用 [[WebPageShot]] 的纪律，逐条对应）：
 *
 *   - 真实进程、真实产物：生产实现 [[NodeShot]] 复用 [[WebPageShot.NodePlaywright]]
 *     的同一进程缝——html/htm 走仓内 `scripts/social-html-shot.mjs`，md/markdown
 *     走仓内 `scripts/social-md-shot.mjs`（marked v12 渲染——与聊天渲染同一份
 *     vendor 库，同一渲染口径）。没有合成图、没有占位图。
 *   - 有界前台：与 webshot 同款 30s 墙钟预算，超时 `destroyForcibly`，不留活进程。
 *   - 产物判定：沿用 `NodePlaywright.shot` 内部的 `RichRenderer.inspect`
 *     （PNG magic + 可解码 + 非纯色下限）。
 *   - 封闭原因词汇：`Left` 只携带 [[RichDegrade.Reason]] 的值；机制细节全部进
 *     日志，用户侧只见文本卡。
 *   - 🔴 离线纪律：spec 永远注入 fake/[[Unavailable]]，不启动 node。
 *
 * 去重台账（[[Dedup]]）：进程级、有界（LRU 512），键 = 绝对路径 + size + mtime
 * ——同字节的重复 Pop 被抑制；文件被重新生成（size/mtime 变化）后照常重推。
 * 引擎重启清零（防轰炸是会话内语义，不需要持久化）。
 */
object FileLongShot:

  /**
   * 可转换的文件类扩展名（小写、不带点）。html/htm 沿 [[WebPageShot.Extensions]]
   * 的既有判定位（不复制第二份），md/markdown 是本批新增。不入 [[RichKind]]
   * （分类层的两张权威表不因降级能力而扩表——与 webshot batch 同一决定：本腿
   * 只在 Unknown 臂被读取）。
   */
  val Extensions: Set[String] = WebPageShot.Extensions ++ Set("md", "markdown")

  /** 小写扩展名（不带点），无扩展名 ⇒ ""。与 [[RichKind.of]] 同一读取口径。 */
  def extensionOf(name: String): String = WebPageShot.extensionOf(name)

  def isConvertible(p: os.Path): Boolean = Extensions.contains(extensionOf(p.toString))

  /** 一枪的墙钟预算（毫秒）。与 [[WebPageShot.DefaultTimeoutMs]] 同档。 */
  val DefaultTimeoutMs: Long = WebPageShot.DefaultTimeoutMs

  /**
   * 截图缝：`(原件, 输出 png) → Either[封闭原因, 实际产物]`。生产 =
   * [[NodeShot]]；离线 spec 注入 fake；[[Unavailable]] 是「本机不可用」载体
   * （直接落 Left，调用方退文本卡）。失败永不 raise——调用方要的是降级。
   */
  trait Renderer:
    def available: Boolean
    def shot(file: os.Path, outPng: os.Path): IO[Either[String, os.Path]]

  /**
   * 「本机没有转换能力」载体（HC-5 同款：响亮降级，不假装成功）。离线 spec
   * 的默认注入面。
   */
  object Unavailable extends Renderer:
    def available: Boolean = false

    def shot(file: os.Path, outPng: os.Path): IO[Either[String, os.Path]] =
      IO.pure(Left(RichDegrade.Reason.EngineUnavailable))

  /**
   * 生产实现：按扩展名挑仓内 node playwright 脚本，进程缝整体复用
   * [[WebPageShot.NodePlaywright]]（发现/预算/产物判定/排空/kill 全同款——
   * 零第二份进程管理）。
   */
  final class NodeShot(
    nodeBin: String = "node",
    timeoutMs: Long = DefaultTimeoutMs
  ) extends Renderer:

    private val htmlScript = WebPageShot.NodePlaywright.detectScriptNamed("social-html-shot.mjs")
    private val mdScript = WebPageShot.NodePlaywright.detectScriptNamed("social-md-shot.mjs")
    private val htmlLeg = new WebPageShot.NodePlaywright(htmlScript, nodeBin, timeoutMs)
    private val mdLeg = new WebPageShot.NodePlaywright(mdScript, nodeBin, timeoutMs)

    /** 两个脚本都在仓内被发现时可用（打包形态缺脚本 = 不可用，正常降级）。 */
    def available: Boolean = htmlScript.isDefined && mdScript.isDefined

    def shot(file: os.Path, outPng: os.Path): IO[Either[String, os.Path]] =
      extensionOf(file.toString) match
        case "html" | "htm" => htmlLeg.shot(file, outPng)
        case "md" | "markdown" => mdLeg.shot(file, outPng)
        case other =>
          IO.pure(Left(s"not a convertible file class: ${if other.isEmpty then "<none>" else other}"))
  end NodeShot

  // ───────────────────────── 去重台账（防轰炸） ─────────────────────────

  /**
   * 进程级去重台账：键 = `绝对路径|size|mtime`。`seen` 纯读（是否已推过），
   * `record` 在**渠道发送成功后**落账——转换失败/发送失败不落账，下一次 Pop
   * 同一文件仍可重试（防轰炸不吞投递）。两渠道共用一个进程级台账，但跨渠道
   * 不互斥：同一文件在微信、飞书各推一次是正确形态（每渠道各有首推）。
   *
   * 线程模型：`synchronized` 的有界 LinkedHashMap（进程内共享小状态，条目
   * 上限 512 LRU 淘汰）；pacer 已把单渠道投递串行化，`seen`→`record` 的窗口
   * 内不存在同渠道并发项。
   */
  object Dedup:

    /** 台账容量上限（LRU）。512 个文件指纹，防轰炸语义下绰绰有余。 */
    val MaxEntries: Int = 512

    private var ledger: java.util.LinkedHashMap[String, java.lang.Long] =
      new java.util.LinkedHashMap[String, java.lang.Long]()

    /** 文件的去重指纹；读不到 size/mtime（文件消失）⇒ None（不可去重，照常投递）。 */
    def keyOf(p: os.Path): Option[String] =
      try
        val nio = p.toNIO
        val size = Files.size(nio)
        val mtime = Files.getLastModifiedTime(nio).toMillis
        Some(s"$p|$size|$mtime")
      catch case _: Throwable => None

    /** 是否已经推送过该指纹（纯读，不落账）。 */
    def seen(key: String): Boolean = synchronized(ledger.containsKey(key))

    /** 落账（发送成功后调用）；超限淘汰最旧条目。 */
    def record(key: String): Unit = synchronized {
      ledger.remove(key)
      ledger.put(key, java.lang.Long.valueOf(System.currentTimeMillis()))
      while ledger.size() > MaxEntries do
        val eldest = ledger.keySet().iterator().next()
        ledger.remove(eldest)
    }

    /** spec 面：清空台账（进程级状态在套件间必须可重置）。 */
    def reset(): Unit = synchronized(ledger.clear())

    /** spec/诊断面：当前条目数。 */
    def size: Int = synchronized(ledger.size())

  end Dedup

  // ───────────────────────── 一句话摘要（文本卡用） ─────────────────────────

  /**
   * 「一句话摘要」：文本卡降级时的内容摘要。best-effort——读不动/读不出 ⇒
   * None（文本卡省略摘要行，绝不因此失败）。md 取首个非空行去 markdown 记号；
   * html 取 `<title>` 或去标签首行；截 48 字符。
   */
  def summarize(p: os.Path): Option[String] =
    Try {
      val bytes: Array[Byte] =
        Try(Files.newInputStream(p.toNIO)).toOption.fold(Array.emptyByteArray) { in =>
          try
            val buf = new Array[Byte](4096)
            val n = in.read(buf)
            if n <= 0 then Array.emptyByteArray else java.util.Arrays.copyOf(buf, n)
          finally in.close()
        }
      val text = new String(bytes, "UTF-8")
      val ext = extensionOf(p.toString)
      val raw =
        if ext == "html" || ext == "htm" then titleOf(text).orElse(firstLineOf(stripTags(text)))
        else firstLineOf(text)
      raw.map(_.take(48).trim).filter(_.nonEmpty)
    }.toOption.flatten

  /** html `<title>`（无则 None）。 */
  private def titleOf(html: String): Option[String] =
    val m = java.util.regex.Pattern
      .compile("(?is)<title[^>]*>(.*?)</title>")
      .matcher(html)
    if m.find() then Some(m.group(1).trim).filter(_.nonEmpty) else None

  /** 去掉 html 标签后的首个非空行。 */
  private def stripTags(s: String): String =
    s.replaceAll("(?s)<[^>]*>", " ").replaceAll("&[a-z]+;", " ")

  /** 首个非空行，去掉 markdown 记号（#/列表/强调/行内代码/链接语法）。 */
  private def firstLineOf(text: String): Option[String] =
    text.linesIterator
      .map(_.trim)
      .filter(_.nonEmpty)
      .map(_.replaceAll("^#{1,6}\\s+", ""))
      .map(_.replaceAll("^[-*>+\\s]+", ""))
      .map(_.replaceAll("`", ""))
      .map(_.replaceAll("!\\[([^]]*)]\\([^)]*\\)", "$1"))
      .map(_.replaceAll("\\[([^]]*)]\\([^)]*\\)", "$1"))
      .map(_.replaceAll("[*_]", ""))
      .find(_.nonEmpty)

end FileLongShot

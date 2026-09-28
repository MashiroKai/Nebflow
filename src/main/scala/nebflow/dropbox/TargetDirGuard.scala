package nebflow.dropbox

import io.circe.parser.decode
import nebflow.core.sandbox.SandboxPolicy
import nebflow.shared.{AttachContract, PathUtil}

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.*
import java.text.Normalizer

/**
 * 接收端 `targetDir` 判定链 —— **不变量 I-DIR 的唯一实现点**。
 *
 * > 接收端不得被发送端指定到任意目录。发送端的 `targetDir` 是**请求**，不是**指令**；
 * > 落点由接收端独立判定。判定不通过 ⇒ 拒 + **零副作用**（不建目录、不落文件、
 * > 不删既有、不改 offset）。
 *
 * 契约来源：`.nebflow/Spec/20260914_162723_devattach-targetdir-contract-upgrade__chain-n-d623bb5b.md`
 * §③（机制 / 判定算法 / 18 条必拒样例 / 零副作用口径）。本节算法为 spec 自陈的
 * **唯一权威链**（§3.2「实现位逐字照做」），步骤顺序即优先级，任一步拒即**立即返回**。
 *
 * 复用先例（**不新造第二套**）：`SandboxPolicy.canonicalize`（realpath 归一）与
 * `SandboxPolicy.contains`（NIO `startsWith` 逐段比较，天然带分隔符边界）。
 *
 * 🔴 本判定 **不得**依赖任何路径构造器抛异常当闸位（`os.Path / String` 的
 * `PathError.InvalidSegment` 是**单段**口的天然行为，而多段口 `os.Path(str, base)` /
 * `PathUtil.resolvePath` **不拒** `..`）——必须先用本文件的显式判定把字符串钉死，
 * 再交给构造器。所有异常在此**收成结构化码**，不让 `InvalidPathException` 逃逸。
 */
object TargetDirGuard:

  // ===== 允许根（机制主体，只存在于接收端）=====

  /** 缺省允许根清单 = `[接收端 downloadsDir]`（spec §3.1 机制 1）。 */
  def defaultAllowRoots: List[os.Path] = List(DropboxUtil.downloadsDir)

  /**
   * 接收端本地配置扩展的额外允许根（`nebflow.json` 的 `dropbox.allowTargetDirRoots`）。
   *
   * **只来自接收端本地** —— 发送端无法影响该清单（它只存在于接收端，永不出现在 wire 上）。
   * fail-safe：配置缺席 / 非法 / 读盘失败 / 非绝对路径一律**丢弃**（只收紧，不放过）。
   *
   * 本参数即 spec §3.2 签名里的 `locallyConfirmed`：本批口径 = 「允许根命中 = 已确认」
   * （自动确认，§3.1 机制 2），故该集合的语义是**接收端本地确认的额外允许根**；
   * 逐次人工确认（弹卡）不在本批（见 spec §3.1 机制 2 与 §⑤ 的回退粒度理由）。
   */
  def configuredAllowRoots: Set[os.Path] = loadConfiguredRoots()

  private def loadConfiguredRoots(): Set[os.Path] =
    try
      val cfgPath = PathUtil.configJsonReadPath(PathUtil.dataRoot)
      if !os.exists(cfgPath) then Set.empty
      else
        decode[io.circe.Json](os.read(cfgPath)).toOption
          .flatMap(
            _.hcursor.downField("dropbox").downField("allowTargetDirRoots").as[Option[List[String]]].toOption.flatten
          )
          .getOrElse(Nil)
          .map(_.trim)
          .filter(_.nonEmpty)
          .flatMap { s =>
            try
              val p = Paths.get(s)
              if p.isAbsolute then Some(os.Path(p)) else None
            catch case _: Exception => None
          }
          .toSet
      end if
    catch case _: Exception => Set.empty

  /** 生产入口：缺省允许根 + 接收端本地配置扩展。 */
  def resolveFor(requested: String): Either[AttachContract.AttachError, os.Path] =
    resolve(requested, defaultAllowRoots, configuredAllowRoots)

  // ===== 判定链 =====

  /**
   * NFC 归一化（判定前先做；**落盘与回显一律用归一化后的形态**）。
   *
   * macOS 卷按 NFD 存储，不做归一化则「同字形不同码位」可绕前缀白名单
   * （同一目录两个码位表示 ⇒ 两套等价串）。
   */
  def normalize(s: String): String = Normalizer.normalize(s, Normalizer.Form.NFC)

  private def utf8Len(s: String): Int = s.getBytes(UTF_8).length

  /** 截断回显（错误体里的 `path` 不得因为回显而放大到无界）。 */
  private def echo(s: String, max: Int = 256): String =
    if s.length <= max then s else s.take(max) + s"…(${s.length} chars)"

  private def invalid(raw: String, why: String): Either[AttachContract.AttachError, os.Path] =
    Left(
      AttachContract.AttachError(
        AttachContract.Codes.TargetDirInvalid,
        s"targetDir rejected: $why",
        phase = "offer",
        path = Some(echo(raw))
      )
    )

  private def notAllowed(
    target: Path,
    roots: List[Path]
  ): Either[AttachContract.AttachError, os.Path] =
    Left(
      AttachContract.AttachError(
        AttachContract.Codes.TargetDirNotAllowed,
        s"targetDir '$target' is outside the receiver's allowed roots — the receiver decides where files land",
        phase = "offer",
        path = Some(target.toString),
        expected = Some(roots.map(_.toString).mkString(", "))
      )
    )

  private def notFound(target: Path, why: String): Either[AttachContract.AttachError, os.Path] =
    Left(
      AttachContract.AttachError(
        AttachContract.Codes.TargetDirNotFound,
        s"targetDir '$target' is not a usable directory: $why",
        phase = "offer",
        path = Some(target.toString)
      )
    )

  private def notWritable(target: Path, why: String): Either[AttachContract.AttachError, os.Path] =
    Left(
      AttachContract.AttachError(
        AttachContract.Codes.TargetDirNotWritable,
        s"targetDir '$target' is not writable: $why",
        phase = "offer",
        path = Some(target.toString)
      )
    )

  /**
   * Windows 盘符（`C:\…`）或 UNC（`\\server\share`）形态。
   *
   * `PathUtil.isAbsolute` **明确接受**这两种形态（`core/paths.scala:21-24`），而 POSIX 上
   * `os.Path("C:\\x", pwd)` 会把它们当**相对段**拼出怪路径 ⇒ 必须显式驳回。
   */
  private def looksWindowsOrUnc(s: String): Boolean =
    s.startsWith("\\\\") || (s.length >= 2 && s.charAt(1) == ':')

  /**
   * 唯一判定入口。返回**canonical 落点**（已存在部分 realpath + 不存在尾段拼回）。
   *
   * @param requested        发送端请求的原始串（wire 输入，完全不可信）
   * @param allowRoots       接收端允许根（机制主体，必选）
   * @param locallyConfirmed 接收端本地确认的额外允许根（**永不来自 wire**）；缺省空集
   */
  def resolve(
    requested: String,
    allowRoots: List[os.Path],
    locallyConfirmed: Set[os.Path] = Set.empty
  ): Either[AttachContract.AttachError, os.Path] =
    val raw = Option(requested).getOrElse("")
    if raw.trim.isEmpty then invalid(raw, "empty or whitespace-only")
    // 步骤 1：形态拒（词法，未触盘）。先拒成结构化码，不让 InvalidPathException 逃逸。
    else if raw.indexOf('\u0000') >= 0 then invalid(raw, "contains NUL (\\u0000)")
    else if utf8Len(raw) > AttachContract.MaxTargetDirBytes then
      invalid(raw, s"${utf8Len(raw)} UTF-8 bytes exceeds the ${AttachContract.MaxTargetDirBytes}-byte limit")
    else if raw.startsWith("~") then
      // 禁 `~` 展开：`PathUtil.expandTilde` 把 `~` 解析成**接收端**的 home，
      // 等于把「接收端 home」暴露给发送端字符串拼接。
      invalid(raw, "`~` is forbidden (it would expand to the receiver's home directory)")
    else if looksWindowsOrUnc(raw) then invalid(raw, "Windows drive / UNC form is not a POSIX path")
    else if !raw.startsWith("/") then invalid(raw, "must be an absolute POSIX path (starting with `/`)")
    else
      // 步骤 2：NFC 归一化（后续判定与落盘一律用归一化形态）。
      val nfc = normalize(raw)
      // 步骤 3：词法 `..` / `.` 一律拒（**禁自动折叠** —— 折叠是穿越陷阱面；
      // `os.Path(str, base)` 的 `..` 拒是弱闸：仅当 count(..) > nameCount/2 才抛）。
      val segments = nfc.split("/", -1).toList
      if segments.contains("..") then
        invalid(raw, "contains a `..` segment (lexical rejection — folding is a traversal trap)")
      else if segments.contains(".") then invalid(raw, "contains a `.` segment")
      else
        try
          // 步骤 4：canonicalize（触盘，跟随符号链接）。两侧同待遇 ⇒ 避免
          // 「根是链、目标是真名」的伪不匹配。
          val targetCanonical = SandboxPolicy.canonicalize(Paths.get(nfc))
          val roots = (allowRoots.map(_.toNIO) ++ locallyConfirmed.map(_.toNIO)).map(SandboxPolicy.canonicalize)
          // 步骤 5：包含判定（NIO startsWith 逐段比较，防 `/foo/bar-baz` 伪匹配 `/foo/bar`）。
          // 空清单 = 全拒（fail-closed）。
          if !roots.exists(r => SandboxPolicy.contains(r, targetCanonical)) then notAllowed(targetCanonical, roots)
          else
            // 步骤 6：可写性探查（只读，不落文件）。步骤 7（mkdir -p + 写 temp）由调用方
            // 在**第 6 步全绿之后**执行 —— 本函数零副作用。
            probe(targetCanonical)
        catch
          case e: Exception =>
            invalid(raw, s"unresolvable path (${e.getClass.getSimpleName}: ${e.getMessage})") // 异常收成结构化码，不逃逸
      end if
    end if
  end resolve

  /**
   * 步骤 6：目标存在 ⇒ 必须是目录 + 可写；目标不存在 ⇒ 最深**存在**祖先必须是目录且可写。
   *
   * 允许在允许根内新建目录（调用方按 `os.makeDir.all` 建）；跨越允许根的新建不可能
   * （步骤 5 已保证）。
   *
   * ⚠️ spec 内部口径歧义（已登记上报，未自裁）：§3.2 步骤 6 括注「目标存在 ⇒ 必须是目录
   * （否则 `TARGET_DIR_NOT_FOUND`）」，而 §2.3 的码表把「目标存在但是文件」列在
   * `TARGET_DIR_NOT_WRITABLE` 行、且把 `TARGET_DIR_NOT_FOUND` 定义为「目标目录**不存在**
   * 且其最深存在祖先不是一个目录」。二者对「目标存在但是文件」这一格的码不同。
   * 本实现取 **§2.3 码表**（按**枚举定义**）：对一个**存在**的路径报 `NOT_FOUND` 与码名
   * 自描述冲突（I3「失败必须自描述」），故「存在但非目录」落 `TARGET_DIR_NOT_WRITABLE`，
   * `TARGET_DIR_NOT_FOUND` 只用于「目标不存在且最深存在祖先不是目录」。
   * 两种读法都**拒**且**零副作用**（不变量 I-DIR 不受影响）；spec 的 18 条样例逐条读数
   * 与本选择无关（样例 #16 命中的是**祖先**为文件，两读皆判 `TARGET_DIR_NOT_FOUND`）。
   */
  private def probe(target: Path): Either[AttachContract.AttachError, os.Path] =
    if Files.exists(target) then
      if !Files.isDirectory(target) then notWritable(target, "the path exists but is not a directory")
      else if !Files.isWritable(target) then notWritable(target, "no write permission (or read-only volume)")
      else Right(os.Path(target))
    else
      // 最深**存在**祖先（NOFOLLOW：悬空链也算「存在」，与 canonicalize 同口径）。
      var base = target
      while base.getParent != null && !Files.exists(base, LinkOption.NOFOLLOW_LINKS) do base = base.getParent
      if !Files.isDirectory(base) then notFound(target, s"the deepest existing ancestor '$base' is not a directory")
      else if !Files.isWritable(base) then notWritable(target, s"the deepest existing ancestor '$base' is not writable")
      else Right(os.Path(target))

end TargetDirGuard

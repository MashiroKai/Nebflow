package nebflow.core.plugin

import nebflow.core.NebflowLogger

import java.net.JarURLConnection
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicReference
import scala.jdk.CollectionConverters.*

/**
 * OfficialPackages —— 「官方」身份的机制化（基线 §2.4；roadmap §5.2 P0-3 行）。
 *
 * 基线 §2.4 逐字口径（本件 = 其装载层实现，验收 = BU A3「冒名拒载」）：
 *  - **保留前缀**：`nebflow-` 前缀 plugin 名 = 官方保留 namespace —— 装载层校验：
 *    前缀匹配但 digest ∉ 分发内置官方允许列表 ⇒ **拒载**（错误码 `OFFICIAL_IMPERSONATION`）；
 *  - **digest 预置信任**：产品分发内置官方包目录 + digest 允许列表（随分发载体走资源）；
 *    官方包按 digest 受信（免用户审批），**用户手改官方包目录 ⇒ digest 失配 ⇒ 落回默认
 *    拒绝**（fail-safe）；第三方包判定路径零变化。
 *
 * ==载体现读（分发内置官方包目录）==
 * `src/main/resources/seed/plugins/`（classpath 资源树，sbt 期 = `target/<scala>/classes/seed/plugins/`，
 * 分发期 = jar 内 `seed/plugins/`）——该目录**就是**产品分发的内置官方包目录：
 * `SeedService` 从**同一棵资源树**安装官方包到 `~/.nebflow/plugins/` 并落 Trusted 审计记录
 * （`PluginRegistry.approve`）。允许列表 = 对其中每个包**现算目录内容 digest**，算法与
 * `PluginRegistry.computeDigest` 逐字同款（按相对路径排序，逐文件 `rel\0<bytes>\0` 喂 SHA-256）；
 * 等价性由 `OfficialIdentitySpec` 的「算法等价」用例对**真实内置包逐包**断言（不靠注释保证）。
 *
 * ==为什么是「现算」而不是「另存一份静态清单」==
 * 允许列表与内置包目录**同源同版本**（同一份分发资源）⇒ **零漂移**：包内容随产品升级改变时
 * 允许列表自动跟着变（基线 §2.4「产品升级重播允许列表」由分发载体本身承载），不会出现
 * 「清单写死 → 升级后官方包反被自家闸拒载」的自伤。安全性不降级：允许列表的信任根 = 分发载体
 * （dmg/AppImage/jar 的签名与公证），与静态清单同源；且静态清单需要一处「谁在何时重算」的
 * 额外机制，而现算不需要。**目录缺失/不可读 ⇒ 空表 ⇒ 保留前缀包一律拒载**（fail-closed）。
 *
 * ==与既有装载错误面的一致性（禁自造第四种错误面）==
 * 本件只产出**拒载原因字符串**（含逐字错误码 `OFFICIAL_IMPERSONATION`），由
 * `PluginRegistry.loadPlugin` 的左值进入既有装载错误面：逐包 `logger.warnSync("Plugin '$n'
 * rejected: …")` + 目录段尾缺席注记（`AbsenceKind.LoadFailed`）+ 启动/重扫健康摘要
 * （`[load-failed] <name>: <reason>`）+ `listWithRejected`（REST/CLI 拒载清单）。
 * 不新增日志通道 / 不抛错 / 不落目录标记 / 不新增面板面。
 *
 * ==代价（如实登记，交验证位与作者裁决）==
 * 本闸对**保留前缀**是硬闸：存量 home 里任何非分发内置的 `nebflow-` 前缀包（第三方自命名）
 * 都会被拒载，须改名后重装。这是基线 §2.4 的明文代价（「第三方包用其他名」），不是实现选择；
 * 本批不代作者决定存量包的命名迁移（见交付报告「开放项」段）。
 */
object OfficialPackages:

  /** 官方保留 namespace 前缀（基线 §2.4 逐字；🔴 禁收窄/改写）。 */
  val ReservedPrefix = "nebflow-"

  /** 拒载错误码（基线 §2.4 / BU A3 逐字；🔴 禁改名）。 */
  val ErrorCode = "OFFICIAL_IMPERSONATION"

  /** 分发内置官方包目录（classpath 资源根）。 */
  val BuiltinRoot = "seed/plugins"

  /** 锚点资源——用于定位资源根，覆盖「jar 无目录条目」场景；file:/jar: 双协议。
    * （同款手法先例：`SeedService.resourceDirList`，锚定一个保证存在的文件。） */
  private val Anchor = "seed/manifest.json"

  private val logger = NebflowLogger.forName("nebflow.plugin.official")

  /** 允许列表 memo（进程内一次；资源树在进程生命周期内不变）。 */
  private val builtinCache = new AtomicReference[Option[Map[String, String]]](None)

  /** 允许列表覆盖——**仅测试钩子**（生产恒 `None`）。存在即优先于现算结果。 */
  private val allowlistOverride = new AtomicReference[Option[Map[String, String]]](None)

  /** 资源定位 classloader —— 生产恒 `None` ⇒ = 本类自身 loader；**仅测试钩子**可临时替换，
    * 用于以**真 jar 形态**（`jar:` 协议资源树）驱动同一条 `builtinPackages()` 分支。 */
  private val loaderOverride = new AtomicReference[Option[ClassLoader]](None)

  private def resourceLoader: ClassLoader = loaderOverride.get().getOrElse(getClass.getClassLoader)

  /** 官方保留名前缀判定（纯函数，零 IO）。 */
  def isReserved(name: String): Boolean = name.startsWith(ReservedPrefix)

  /** 分发内置官方允许列表：包名 → 目录内容 digest。
    *
    * 键 = 该包 manifest 的 `name`（与 `PluginRegistry.loadPlugin` 的判定键同源；
    * manifest 不可读时退回目录名）。值 = 目录内容树 SHA-256（见类注释）。
    * 目录缺失/枚举失败 ⇒ 空表（fail-closed：保留前缀包一律拒载，绝不 fail-open）。 */
  def allowlist(): Map[String, String] =
    allowlistOverride.get() match
      case Some(overridden) => overridden
      case None =>
        builtinCache.get() match
          case Some(cached) => cached
          case None =>
            val computed =
              try computeAllowlist()
              catch
                case e: Exception =>
                  logger.warnSync(
                    s"official allowlist unavailable (${e.getClass.getSimpleName}: ${e.getMessage}) — " +
                      s"treating the built-in official package directory as empty (fail-closed: every '$ReservedPrefix'-prefixed package is refused)")
                  Map.empty[String, String]
            builtinCache.set(Some(computed))
            computed

  /** 装载层判定（**纯函数**，零 IO；本批的判定单点）：
    *  - 非保留前缀 ⇒ 恒 `true` —— 第三方包判定路径逐字不变（对照臂）；
    *  - 保留前缀 ⇒ 必须在允许列表中**且 digest 逐字相等**（失配 = 用户手改/版本不符 ⇒ 拒载）。 */
  def admits(name: String, digest: String): Boolean =
    !isReserved(name) || allowlist().get(name).contains(digest)

  /** 该保留前缀包是否出现在分发内置允许列表中（**仅供错误文案分流与报告读数**，
    * 不参与准入判定——准入唯一判据是 [[admits]]）。 */
  def knownOfficial(name: String): Boolean = allowlist().contains(name)

  /** 拒载原因文案（进既有装载错误面；🔴 末段括注错误码逐字 `OFFICIAL_IMPERSONATION`）。
    * 文案必须说清：错在哪（保留前缀被冒用）、期望是什么（允许列表中的 digest）、能给修法就给。 */
  def rejectionReason(name: String, digest: String): String =
    val table = allowlist()
    table.get(name) match
      case Some(expected) =>
        s"'$name' uses the reserved official namespace '$ReservedPrefix' but its content digest does not match the " +
          s"shipped official package (built-in ${expected.take(12)}… vs on disk ${digest.take(12)}…). The package directory " +
          "was modified after installation (or the running product no longer ships this exact package). Official packages " +
          "are trusted by digest only — restore the shipped package, or install it under a non-reserved name. " +
          s"(${ErrorCode})"
      case None =>
        val suggestion = name.stripPrefix(ReservedPrefix)
        val rename = if suggestion.nonEmpty then s" (e.g. '$suggestion')" else ""
        s"'$name' uses the reserved official namespace '$ReservedPrefix' but is not among the distribution's built-in " +
          s"official packages (on-disk digest ${digest.take(12)}…, allowlist holds ${table.size} package(s)). The " +
          s"'$ReservedPrefix' prefix is reserved for official packages — rename this package to a non-reserved name$rename " +
          s"and re-install it. (${ErrorCode})"

  /** 清 memo（测试钩子；与 [[PluginRegistry.invalidateCache]] 同族）。 */
  def invalidateCache(): Unit = builtinCache.set(None)

  // ── 允许列表现算 ────────────────────────────────────────────

  /** 内置包目录 → `name -> digest`。包级容错：单个包读取失败只丢该包（其余照常进表）。 */
  private def computeAllowlist(): Map[String, String] =
    builtinPackages().flatMap { case (dirName, files) =>
      if files.isEmpty then None
      else
        try Some(manifestName(files).getOrElse(dirName) -> digestOf(files))
        catch
          case e: Exception =>
            logger.warnSync(s"official package '$dirName' unreadable (${e.getMessage}) — excluded from the allowlist")
            None
    }.toMap

  /** 分发内置官方包目录的资源树枚举：包目录名 → `[(相对路径, 字节)]`。
    * 锚点 = `seed/manifest.json`（保证存在；file: = sbt/源码形态，jar: = 分发形态）。 */
  private def builtinPackages(): List[(String, List[(String, Array[Byte])])] =
    val loader = resourceLoader
    Option(loader.getResource(Anchor)).toList.flatMap { url =>
      url.getProtocol match
        case "file" =>
          val anchorFile = os.Path(java.nio.file.Paths.get(url.toURI))
          val root = anchorFile / os.up / "plugins"
          if !os.isDir(root) then Nil
          else
            os.list(root).filter(os.isDir).toList.sortBy(_.last).map { pkg =>
              val files = os.walk(pkg).filter(os.isFile).toList.sortBy(_.toString).map { f =>
                f.relativeTo(pkg).toString -> os.read.bytes(f)
              }
              pkg.last -> files
            }
        case "jar" =>
          val conn = url.openConnection().asInstanceOf[JarURLConnection]
          val base = s"$BuiltinRoot/"
          val rels = conn.getJarFile.entries().asScala.toList
            .filter(e => !e.isDirectory && e.getName.startsWith(base))
            .map(_.getName.stripPrefix(base))
            .filter(_.contains("/"))
          rels.groupBy(_.takeWhile(_ != '/')).toList.sortBy(_._1).map { (pkgDir, rs) =>
            // jar 分支要与 file: 分支**同一 allowlist 判定结果**，两条必须同时成立：
            //  ① 资源名 = `$base$rel`：`rel` 自身已含包目录名（上面刚 `stripPrefix(base)` 过）
            //     ⇒ 只能拼一次 `base`。再拼一次 `pkgDir` 会得到 `seed/plugins/<pkgDir>/<pkgDir>/…`
            //     （jar 内不存在）⇒ `getResourceAsStream` 恒 null ⇒ 逐包 `files.isEmpty`
            //     ⇒ 允许列表恒为空表 ⇒ 分发形态下官方包整体被拒载。
            //  ② 喂 digest 的路径必须是**包内相对路径**：`rel` 需去掉首段包目录名，与 file: 分支的
            //     `f.relativeTo(pkg)` 同形。余着包目录名会让 jar 形态 digest 与装载层权威
            //     `PluginRegistry.computeDigest` 不等 ⇒ 表非空但每个官方包都被判成「装后被人改过」
            //     而拒载（且 manifest 的 `name` 键也退化为目录名）。
            val files = rs.sorted.flatMap { rel =>
              Option(loader.getResourceAsStream(s"$base$rel")).map { in =>
                try rel.stripPrefix(s"$pkgDir/") -> in.readAllBytes()
                finally in.close()
              }
            }
            pkgDir -> files
          }
        case _ => Nil
    }

  /** 包 manifest 的 `name`（不可读/缺失/非字符串 ⇒ None，调用方退回目录名）。 */
  private def manifestName(files: List[(String, Array[Byte])]): Option[String] =
    files.find(_._1 == "plugin.json").flatMap { (_, bytes) =>
      io.circe.parser
        .parse(new String(bytes, java.nio.charset.StandardCharsets.UTF_8))
        .toOption
        .flatMap(_.hcursor.downField("name").as[String].toOption)
        .map(_.trim)
        .filter(_.nonEmpty)
    }

  /** 目录内容树 digest —— 与 `PluginRegistry.computeDigest` 同算法：
    * 按相对路径排序，逐文件 `rel\0<bytes>\0` 喂 SHA-256，输出小写 hex。
    * `private[plugin]` 以便 spec 直接做「算法等价」断言（对真实内置包逐包比对）。 */
  private[plugin] def digestOf(files: List[(String, Array[Byte])]): String =
    val md = MessageDigest.getInstance("SHA-256")
    files.sortBy(_._1).foreach { (rel, bytes) =>
      md.update(s"$rel\u0000".getBytes("UTF-8"))
      md.update(bytes)
      md.update("\u0000".getBytes("UTF-8"))
    }
    md.digest().map("%02x".format(_)).mkString

  // ── 测试钩子（生产零调用）─────────────────────────────────

  /** 允许列表覆盖（**仅测试**）：`None` = 恢复现算。 */
  private[plugin] def setAllowlistForTest(table: Option[Map[String, String]]): Unit =
    allowlistOverride.set(table)
    builtinCache.set(None)

  /** 作用域内覆盖允许列表，退出时**无条件**恢复（含异常路径）。 */
  private[plugin] def withAllowlistForTest[A](table: Map[String, String])(body: => A): A =
    setAllowlistForTest(Some(table))
    try body
    finally setAllowlistForTest(None)

  /** 作用域内替换资源定位 classloader（**仅测试**：真 jar 形态资源树 + 独立 URLClassLoader），
    * 退出时**无条件**恢复（含异常路径），并清 memo（换 loader = 换资源树）。 */
  private[plugin] def withClassLoaderForTest[A](loader: ClassLoader)(body: => A): A =
    val prev = loaderOverride.get()
    loaderOverride.set(Some(loader))
    builtinCache.set(None)
    try body
    finally
      loaderOverride.set(prev)
      builtinCache.set(None)

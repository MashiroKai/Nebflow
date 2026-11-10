package nebflow.core.plugin

import nebflow.shared.{NebflowLogger, PathUtil}

import scala.util.control.NonFatal

/**
 * PluginSourceCatalog —— 「自研插件走远端源、不进 seed」的**指针单点**（作者令 2026-11-01 22:55）。
 *
 * ==作者令与判定==
 * 「stand-dev 插件应该不进 seed 吧？运行时插件不是应该要能热更新吗，立即使用我们最新的版本」。
 * 判定：**成立**。seed 树的语义是「字节随引擎分发载体走」（`src/main/resources/seed/plugins/`
 * → jar → boot 期 `SeedService` 抄到 `<dataRoot>/plugins/`，见 `SeedService` 类注释与
 * `OfficialPackages`「载体现读」段），于是插件内容的任何前进都被绑死在**引擎发版**上：
 *  - 不发版 ⇒ 实例永远停在 jar 里那一份（今天实证：seed/运行时 v1.1.0，USTC 源 v1.2.0）；
 *  - 发版 ⇒ 靠 boot 期 digest 仲裁覆盖（`SeedService.reconcilePlugin`），而仲裁基准是
 *    approve 时刻的 fingerprint ⇒ 用户改过就永不收敛（`SeedService` 类注释已登记该缺口）。
 * 自研插件（stand-dev 等）的 canonical 副本在**我们自己的 git 仓**（USTC `kaiyu/stand-dev`），
 * 不在引擎仓 ⇒ 正确形态 = **指针随引擎分发、字节随源**：本件只带「包名 → 源 URL + 分支」，
 * 字节由 [[PluginSourceSync]] 按需拉取并在运行时原子换装（零重启生效）。
 *
 * ==与设计稿的关系==
 * 这不是新发明：`docs/design/20261029_174545_plugin-remote-source-design__delegate-nebflow-585363ad.zh-CN.md`
 * §2.2（源引用三态）/§4（拉取式安装，P2 已落 `PluginSourceInstall`）/§5（更新流，P3）/
 * §7 T1-T2（seed 退役过渡）的**最小接地**：本批落 P3 里的「git 源 + 用户驱动更新 + 面板可见」
 * 一条腿，官方 index/OSS 腿（P5）不动。诚实申报：设计稿 §8 的 9 条裁定点本批**未逐一请示**，
 * 因为本批不触信任面（`via=git` 的包不是 `nebflow-` 保留前缀，`OfficialPackages` 闸不参与）、
 * 不改装载语义（在位即信任）、不改 seed 的其他包。
 *
 * ==两源合流（代码内建 ∪ 用户配置）==
 *  - **代码内建** = [[Builtins]]（随引擎分发；作者令「自研插件走远端源」的落地面）。内建条目
 *    必须 `preinstall = true`（新装/缺失 home 自愈装回，验收 1），且**不得**再出现在
 *    `seed/manifest.json`（由 `PluginSourceCatalogSpec` 钉死，防两套分发面并存）；
 *  - **用户配置** = `nebflow.json → plugins.sources.<name>`（热读；可覆盖内建 url/branch，
 *    也可新增第三方自研包），字段见 [[SourceDecl]]。
 *
 * ==浮动 HEAD 与 pin 的关系（不破 `PluginSourceInstall` 的 §2.2B 铁律）==
 * §2.2B「git 源 pin 必填、禁浮动 HEAD」约束的是**信任面**：落盘的必须是一个可复算 digest
 * 的不可变 commit。本件跟踪的是**分支 tip**，但每次 check 都把 tip 解成 40-hex commit、
 * 每次 update 都把该 commit 写成账本 pin（`plugin-sources/<name>.json → pin.commit`）
 * ⇒ 盘上永远对应一个确定 commit，信任面零变化。分支只是「往哪看」的指针，不是信任依据。
 */
object PluginSourceCatalog:

  private val logger = NebflowLogger.forName("nebflow.plugin.sources")

  /** 单条源声明（代码内建与用户配置同形）。 */
  final case class SourceDecl(
      name: String,
      /** `git`（远端仓）| `local`（本机目录，开发态）——与 `PluginSourceLedger.Kind` 的 closed set 对齐。 */
      kind: String,
      /** git 源 = remote URL；local 源 = 绝对目录路径。 */
      url: String,
      /** git 分支（默认 `main`）；local 源忽略。 */
      branch: String,
      /** git 源 = 仓内包目录（缺省 = 仓根）。 */
      path: Option[String],
      /** 缺失即自愈（新装 home / 用户删掉后重启）：只有内建自研包为 true。 */
      preinstall: Boolean,
      /** 人读一句话（面板与审计行共用）。 */
      note: String
  ):
    def isGit: Boolean = kind == "git"
    def isLocal: Boolean = kind == "local"

  object SourceDecl:
    val DefaultBranch = "main"

  // ── 代码内建声明（作者令 2026-11-01 22:55「自研插件走远端源，不进 seed」）──

  /** USTC 自研插件仓命名空间（`kaiyu/` 名下，单仓单包）。🔴 只在此处出现，禁散落字面。 */
  val UstcBase = "git@git.ustc.edu.cn:kaiyu"

  /**
   * 内建源清单。**加包规则**：自研（来源在我们自己的仓）⇒ 进本表 + `preinstall=true`，
   * **不进** `seed/manifest.json` / `seed/plugins/`；官方第三方包（随引擎分发的出厂集）⇒
   * 继续走 seed。两者并存会产生两套分发面（同一包两份字节、谁覆盖谁的仲裁问题），
   * `PluginSourceCatalogSpec` 对「内建源 ∧ seed 树」交集断言为空。
   *
   * `stand-dev`（2026-11-01 本批迁出 seed）：统一开发知识包，canonical 副本 =
   * `git@git.ustc.edu.cn:kaiyu/stand-dev.git` @ `main`；包在仓根（无子目录）。
   */
  val Builtins: List[SourceDecl] = List(
    SourceDecl(
      name = "stand-dev",
      kind = "git",
      url = s"$UstcBase/stand-dev.git",
      branch = SourceDecl.DefaultBranch,
      path = None,
      preinstall = true,
      note = "stand 本体开发统一知识包（自研仓，不进 seed：内容前进只推仓，不等引擎发版）"
    )
  )

  // ── 配置读取（热读；`plugins.sources.<name>`）─────────────────────

  private def configPath: os.Path = PathUtil.configJsonReadPath(PathUtil.dataRoot)

  /**
   * 用户配置段的**完整**读面：生效声明表 + 被 `enabled:false` 关掉的名单。
   * 分开返回是必要的：`declarations()` 是「内建 ∪ 用户」，用户想关掉一条**内建**声明
   * （典型：本机不想拉 stand-dev）时的唯一手段就是 `enabled:false`——若只做「并集后覆写」，
   * 这条关闭意图会被内建条目原样顶回来（关不掉 = 静默无效）。故关闭集合必须显式带出来做减法。
   */
  private def userConfig(): (Map[String, SourceDecl], Set[String]) =
    if !os.exists(configPath) then (Map.empty, Set.empty)
    else
      io.circe.parser.parse(os.read(configPath)).toOption match
        case None =>
          logger.warnSync(
            s"config file unreadable (${configPath}) — plugin source declarations fall back to the built-in catalog"
          )
          (Map.empty, Set.empty)
        case Some(json) =>
          json.hcursor.downField("plugins").downField("sources").focus.flatMap(_.asObject) match
            case None => (Map.empty, Set.empty)
            case Some(obj) =>
              val enabled = Map.newBuilder[String, SourceDecl]
              val disabled = Set.newBuilder[String]
              obj.toMap.foreach { (name, declJson) =>
                val on = declJson.hcursor.downField("enabled").as[Boolean].toOption.getOrElse(true)
                if !on then disabled += name
                else
                  parseDecl(name, declJson) match
                    case Right(d) => enabled += (name -> d)
                    case Left(err) => logger.warnSync(s"plugin source declaration '$name' ignored: $err")
              }
              (enabled.result(), disabled.result())

  private def parseDecl(name: String, json: io.circe.Json): Either[String, SourceDecl] =
    if !PluginRegistry.validPluginName(name) then Left(s"invalid plugin name '$name' (plugin source key)")
    else
      val c = json.hcursor
      val kind = c.downField("kind").as[String].toOption.map(_.trim.toLowerCase).getOrElse("git")
      val url = c.downField("url").as[String].toOption.map(_.trim).filter(_.nonEmpty)
      if !SourceDeclKind.isKnown(kind) then
        Left(s"unknown source kind '$kind' (expected: git | local)")
      else
        url match
          case None => Left("missing 'url' (git remote URL, or an absolute directory for kind=local)")
          case Some(u) =>
            Right(
              SourceDecl(
                name = name,
                kind = kind,
                url = u,
                branch = c.downField("branch").as[String].toOption.map(_.trim).filter(_.nonEmpty)
                  .getOrElse(SourceDecl.DefaultBranch),
                path = c.downField("path").as[String].toOption.map(_.trim).filter(_.nonEmpty),
                preinstall = c.downField("preinstall").as[Boolean].toOption.getOrElse(false),
                note = c.downField("note").as[String].toOption.getOrElse("user-declared plugin source")
              )
            )

  /** 闭集：本件认得的两态（与 `PluginSourceLedger.Kind` 的 git/local 同域，禁第二套判据）。 */
  private object SourceDeclKind:
    private val Known = Set("git", "local")
    def isKnown(k: String): Boolean = Known.contains(k)

  // ── 合流读面 ─────────────────────────────────────────────────

  /**
   * 生效声明表 = 内建 ∪ 用户配置（用户同名字段整条覆盖内建；`enabled:false` 从**并集后**
   * 的表中删掉该条——包括关掉一条内建声明）。
   * 热读：每次调用现读 `nebflow.json`（`PluginsConfig` 同款纪律）。
   */
  def declarations(): Map[String, SourceDecl] =
    val builtins = Builtins.map(d => d.name -> d).toMap
    val (users, disabled) = userConfig()
    (builtins ++ users) -- disabled

  /** 单包声明（面板/审计/CLI 的查询单点）。 */
  def declaration(name: String): Option[SourceDecl] = declarations().get(name)

  /** 内建（随引擎分发）的自研包名集合——`preinstall` 自愈面与文档口径共用。 */
  def builtinNames: Set[String] = Builtins.map(_.name).toSet

  /**
   * 展示/审计用的一句源描述（面板卡片「来源」行、审计行、错误文案共用）。
   * 🔴 形如 `git · host/path @branch` / `local <dir>`，**不重复 `kind` 字面**：调用点常写
   * `s"git ls-remote failed for ${describe(...)}"`，若这里再带一个 `git `，实测文案就成了
   * 「git git@…」（本批 USTC 实测读到过）。去协议/凭据面同 [[shortLabel]]。
   */
  def describe(d: SourceDecl): String = shortLabel(d)

  /** 面板「来源」短标签：`git · git.ustc.edu.cn/kaiyu/stand-dev @main`（去协议/凭据面）。 */
  def shortLabel(d: SourceDecl): String =
    if !d.isGit then d.url
    else
      val hostPath = d.url
        .stripPrefix("git@").replace(':', '/')
        .stripPrefix("https://").stripPrefix("http://")
        .stripSuffix(".git")
      s"git · $hostPath @${d.branch}"

  /** best-effort 末段清洗（错误文案里禁回显可能含凭据的 URL 片段；本批零凭据面，形态守住）。 */
  def sanitize(msg: String): String =
    try msg.replaceAll("://[^/@\\s]+@", "://<credentials>@")
    catch case NonFatal(_) => msg

end PluginSourceCatalog

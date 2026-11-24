package nebflow.core.plugin

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import io.circe.syntax.*
import io.circe.{Json, JsonObject}
import nebflow.shared.{NebflowLogger, PathUtil}
import PluginSourceLedger.{Kind, Pin, Via}

import scala.util.control.NonFatal

/**
 * PluginSourceSync —— 源化插件的**运行时热更新链**（检查 / 拉取 / 生效；作者令 2026-11-01 22:55）。
 *
 * ==作者令==
 * 「运行时插件不是应该要能热更新吗，立即使用我们最新的版本」。本件落这条链：源声明
 * （[[PluginSourceCatalog]]）→ 远端 tip 解析 → 暂存拉取 → 校验 → 备份 → **原子换装** →
 * 账本落 pin → 审计行；面板/CLI/REST 只驱动它，不各写一套。
 *
 * ==为什么「不必重启引擎」（装载时机取证）==
 * 引擎的插件读取是**按需 + mtime 缓存**：`PluginRegistry.snapshot()` 每次读面（`scan` /
 * `resolve` / 面板 `GET /api/plugins` / 分发器目录）都比对 `plugins/` 全树的
 * `(路径, mtime)` 签名，签名一变就整表重扫（`PluginRegistry.scala:502-547`）。换装 =
 * 目录替换 ⇒ mtime 变 ⇒ **下一条读命令即见新内容**，零重启。三个诚实的边界：
 *  - 注入面：`<injected-plugins>` 块在**节点 spawn 时**取 `Plugin.md` 全文
 *    （`PluginInjection`），换装不影响**已在飞**节点（与「内容漂移不停飞」的既有口径
 *    一致，`PluginMcpManager.revalidate` 同款）；新派发/新节点拿到新版。
 *  - `Plugin.md` 内容无内存缓存层，新派发即刻拿到新提示词。
 *  - 面板版本号走 `GET /api/plugins`（同一条 `snapshot()`）⇒ 换装后下一拍轮询即翻新。
 *
 * ==离线 / 远端不可达纪律（验收 3）==
 * 换装的**原子性**是兜底的机制面：任何失败都发生在「旧版仍在原位」时——
 *  - 拉取失败 ⇒ 零触碰（`plugins/<name>/` 既没删也没写）；
 *  - 校验失败（无 plugin.json / name 不符 / digest 现算失败）⇒ 暂存会话 finally 清空；
 *  - 换装失败（rename 失败）⇒ 回滚：把备份目录搬回原位（best-effort，失败则点名报错）。
 * 所以「远端挂了」的后果只有一个：**版本停在本地那一份**，插件照常装载、照常派发，
 * 面板给一条可行动提示（`unreachable` 状态 + 原因）。**绝不因拉取失败让插件消失。**
 *
 * ==信任面（与 `PluginSourceInstall` 的 §2.2B 一致，不开口子）==
 * 浮动的是「往哪看」（分支），落在盘上的是**确定的 commit**：每次成功换装都把
 * `pin = {commit}` 写进 `plugin-sources/<name>.json`，digest 由 `computeDigest` 现算。
 * `expectCommit` 参数把「换到指定 commit」做成可复现动作（测试与回滚腿用）。
 */
object PluginSourceSync:

  private val logger = NebflowLogger.forName("nebflow.plugin.sync")

  // ── 稳定错误码（🔴 禁改名——REST/CLI/测试逐字断言）──────────────

  val CodeUnknownName = "SOURCE_SYNC_UNKNOWN_NAME" // 名字不在源声明表里
  val CodeUnreachable = "SOURCE_SYNC_UNREACHABLE" // 远端/本地源不可达
  val CodeNoBranch = "SOURCE_SYNC_NO_BRANCH" // 分支不存在
  val CodeLayout = "SOURCE_SYNC_LAYOUT" // 拉下来的树里没有合法 plugin.json / name 不符
  val CodeSwapFailed = "SOURCE_SYNC_SWAP_FAILED" // 原子换装失败（已尝试回滚）
  val CodeLocalMissing = "SOURCE_SYNC_LOCAL_MISSING" // kind=local 源路径不存在

  // ── 路径（与 PluginRegistry.pluginsDir / Install.stagingRoot 同根派生）──

  private def pluginsDir: os.Path = PathUtil.dataRoot / "plugins"
  private def stagingRoot: os.Path = PathUtil.dataRoot / ".plugin-staging"
  private def backupRoot: os.Path = PathUtil.dataRoot / "plugins-backups"
  private def auditFile: os.Path = PathUtil.dataRoot / "logs" / "plugin-source-sync.jsonl"

  /** 拉取预算：用户驱动动作，给足时间但不许挂死。 */
  private val CloneTimeoutMs = 180_000L

  // ── 类型面 ─────────────────────────────────────────────────

  object State:
    val Current = "current"
    val Outdated = "outdated"
    val Unreachable = "unreachable"
    val Unknown = "unknown"
    val NotInstalled = "not-installed"

  /** 一次「检查更新」的读数（面板/CLI/REST 三面共用的冻结形状）。 */
  final case class Status(
    name: String,
    sourceLabel: String,
    kind: String,
    installedVersion: String,
    installedCommit: Option[String],
    remoteVersion: Option[String],
    remoteCommit: Option[String],
    updateAvailable: Boolean,
    /**
     * `current` 已是最新 | `outdated` 有新版 | `unreachable` 远端不可达（本地版本照常可用） |
     * `unknown` 名字不在声明表 | `not-installed` 声明在但本地没装
     */
    state: String,
    checkedAt: Long,
    detail: String
  )

  /** 一次「拉取并生效」的结果。`planId` = §6.3 统一生命周期计划身份（对账键）。 */
  final case class UpdateResult(
    name: String,
    fromVersion: String,
    toVersion: String,
    commit: String,
    digest: String,
    backupDir: Option[String],
    planId: String,
    effective: String
  )

  // ── 读面：安装态（本地）───────────────────────────────────────

  private def installedManifest(name: String): Option[Json] =
    val f = pluginsDir / name / "plugin.json"
    if !os.isFile(f) then None
    else io.circe.parser.parse(os.read(f)).toOption

  private def installedVersion(name: String): String =
    installedManifest(name)
      .flatMap(_.hcursor.downField("version").as[String].toOption)
      .map(_.trim)
      .filter(_.nonEmpty)
      .getOrElse("")

  /** 账本里的已装 commit（git 源才有；local 源 / 回填包 ⇒ None）。 */
  private def installedCommit(name: String): Option[String] =
    PluginSourceLedger.query(name) match
      case PluginSourceLedger.Known(entry) =>
        entry.pin.flatMap {
          case Pin.Commit(_, commit) => Some(commit)
          case _ => None
        }
      case _ => None

  // ── 远端探针 ───────────────────────────────────────────────

  private final case class Probe(commit: String, version: Option[String])

  /**
   * 解析远端分支 tip → commit，并读出该 commit 上包 manifest 的 version。
   * `ls-remote`（廉价，判「有没有变」）→ 变了才浅克隆读版本号（用户驱动动作，可接受；
   * 零后台轮询纪律不变）。
   */
  private def probeRemote(decl: PluginSourceCatalog.SourceDecl): Either[String, Probe] =
    if decl.isLocal then
      // 本地源：版本号直接读目录，无需任何网络
      val pkg = os.Path(decl.url, os.pwd) match
        case p => decl.path.fold(p)(rel => p / os.SubPath(rel))
      if !os.isDir(pkg) then Left(s"local source directory not found: ${decl.url} (${CodeLocalMissing})")
      else
        val version = io.circe.parser
          .parse(os.read(pkg / "plugin.json"))
          .toOption
          .flatMap(_.hcursor.downField("version").as[String].toOption)
          .map(_.trim)
          .filter(_.nonEmpty)
        Right(Probe("local", version))
    else
      lsRemoteCommit(decl) match
        case Left(err) => Left(err)
        case Right(tip) =>
          // 需要版本号 ⇒ 一次浅克隆 + 单文件读（不落 plugins/，暂存会话内自清）
          withStaging(s"probe-${decl.name}") { dir =>
            fetchInto(decl, dir) match
              case Left(err) => Left(err)
              case Right(commit) =>
                val pkg = packageRoot(dir, decl)
                val version =
                  if !os.isFile(pkg / "plugin.json") then None
                  else
                    io.circe.parser
                      .parse(os.read(pkg / "plugin.json"))
                      .toOption
                      .flatMap(_.hcursor.downField("version").as[String].toOption)
                      .map(_.trim)
                      .filter(_.nonEmpty)
                Right(Probe(commit, version))
          }

  /** `git ls-remote <url> refs/heads/<branch>` → commit（40-hex）。 */
  private def lsRemoteCommit(decl: PluginSourceCatalog.SourceDecl): Either[String, String] =
    try
      val res = git(List("ls-remote", decl.url, s"refs/heads/${decl.branch}"))
      if res.exitCode != 0 then
        Left(
          s"git ls-remote failed for ${PluginSourceCatalog.describe(decl)} (exit ${res.exitCode}): ${errTail(res)} " +
            s"(${CodeUnreachable})"
        )
      else
        res.out
          .text()
          .linesIterator
          .find(_.contains(s"refs/heads/${decl.branch}"))
          .map(_.takeWhile(_ != '\t').trim)
          .filter(_.matches("[0-9a-f]{40}")) match
          case Some(c) => Right(c)
          case None =>
            Left(
              s"branch '${decl.branch}' not found on ${decl.url} — push the branch, or fix " +
                s"plugins.sources.${decl.name}.branch (${CodeNoBranch})"
            )
      end if
    catch
      case NonFatal(e) =>
        Left(s"git ls-remote failed (${e.getClass.getSimpleName}: ${e.getMessage}) (${CodeUnreachable})")

  /** 拉取到 `dest`（git = 浅克隆分支；local = 拷贝目录），返回实际 commit / "local"。 */
  private def fetchInto(decl: PluginSourceCatalog.SourceDecl, dest: os.Path): Either[String, String] =
    if decl.isLocal then
      val src = os.Path(decl.url, os.pwd)
      if !os.isDir(src) then Left(s"local source directory not found: ${decl.url} (${CodeLocalMissing})")
      else
        try
          os.copy(src, dest, createFolders = true, mergeFolders = true, replaceExisting = true)
          stripVcsMetadata(dest)
          Right("local")
        catch
          case NonFatal(e) =>
            Left(s"local copy failed (${e.getClass.getSimpleName}: ${e.getMessage}) (${CodeLocalMissing})")
    else
      val res = git(
        List("clone", "--quiet", "--depth", "1", "--branch", decl.branch, decl.url, dest.toString),
        timeoutMs = CloneTimeoutMs
      )
      if res.exitCode != 0 then
        Left(
          s"git clone failed for ${PluginSourceCatalog.describe(decl)} (exit ${res.exitCode}): ${errTail(res)} " +
            s"(${CodeUnreachable})"
        )
      else
        val head = git(List("-C", dest.toString, "rev-parse", "HEAD"))
        val commit = head.out.text().trim
        if head.exitCode == 0 && commit.matches("[0-9a-f]{40}") then
          stripVcsMetadata(dest)
          Right(commit)
        else Left(s"git rev-parse HEAD failed after clone (exit ${head.exitCode}) (${CodeUnreachable})")

  /** 包根：`path` 子目录或仓根。 */
  private def packageRoot(repoRoot: os.Path, decl: PluginSourceCatalog.SourceDecl): os.Path =
    decl.path.fold(repoRoot)(rel => repoRoot / os.SubPath(rel))

  /**
   * 剥掉 VCS 元数据（`.git`）：**装进去的不是一个仓，是一个包**。
   *
   * 两条硬理由（本批实测抓到）：
   *  1. **digest 必须只由包内容决定**。`.git`（浅克隆的 packed-refs / index / hooks 样本 /
   *     时间戳）会让同一份 v1.2.0 内容的两次安装算出不同 digest（实测 `6b0aa2c…` vs
   *     `906a8c9…`）⇒ 信任记录刷新与「内容是否漂移」的仲裁全部失真；
   *  2. **装载面不该带仓**。`PluginRegistry` 会 walk 全树（digest / 文件计数 / 资产围栏），
   *     `.git` 是几百个与包无关的文件；`plugin remove`、备份、面板列目录也会把它当包内容。
   *
   * 失败不致命：拉取已经成功，脏元数据最多让 digest 不稳 ⇒ 报 WARN，不吞这次更新。
   */
  private def stripVcsMetadata(root: os.Path): Unit =
    try if os.exists(root / ".git") then os.remove.all(root / ".git")
    catch
      case NonFatal(e) =>
        logger.warnSync(s"[plugin-sync] could not strip .git from the pulled tree ($root): ${e.getMessage}")

  /**
   * 统一 git 调用：禁交互提示（无 TTY 时必须失败而不是挂住）+ 硬超时。
   *
   * 🔴 `"git"` 前缀在这里加、**不在调用点各自加**：调用点传的是 git 的**子命令**参数
   * （`List("clone", …)`），而 `os.proc` 的第一个元素就是可执行文件——漏了这层前缀，
   * 进程会去找名为 `clone` / `ls-remote` 的程序。实测踩过：boot 自愈发出一行
   * `Cannot run program "clone"`，插件静默没装（local 源的 spec 不经过这条腿，只有
   * 真 git 源能抓到，即本批的内网仓源实测）。
   */
  private def git(gitArgs: List[String], timeoutMs: Long = 60_000L): os.CommandResult =
    os.proc(("git" :: gitArgs).map(a => a: os.Shellable)*)
      .call(
        check = false,
        timeout = timeoutMs,
        env = Map("GIT_TERMINAL_PROMPT" -> "0", "GIT_ASKPASS" -> "true"),
        stdout = os.Pipe,
        stderr = os.Pipe
      )

  private def errTail(res: os.CommandResult): String =
    try res.err.text().linesIterator.toList.takeRight(3).mkString("; ")
    catch case NonFatal(_) => "(no stderr)"

  // ── 检查更新（用户驱动；零后台轮询）──────────────────────────

  /**
   * 单包检查。**永不抛**：远端不可达 ⇒ `state=unreachable` + 原因（本地版本照常可用）；
   * 名字不在声明表 ⇒ `state=unknown`（不是错误——面板据此区分「seed 包」与「源化包」）。
   */
  def check(name: String): IO[Status] =
    IO.blocking {
      val now = System.currentTimeMillis() / 1000L
      PluginSourceCatalog.declaration(name) match
        case None =>
          Status(
            name,
            "",
            "",
            installedVersion(name),
            installedCommit(name),
            None,
            None,
            false,
            State.Unknown,
            now,
            s"no source declaration for '$name' (not a source-managed package)"
          )
        case Some(decl) =>
          val label = PluginSourceCatalog.shortLabel(decl)
          if !os.isDir(pluginsDir / name) then
            Status(
              name,
              label,
              decl.kind,
              "",
              None,
              None,
              None,
              true,
              State.NotInstalled,
              now,
              s"declared source ${PluginSourceCatalog.describe(decl)} but the package is not installed locally — " +
                "POST /api/plugins/" + name + "/source/update (or CLI 'stand plugin update " + name + "') installs it"
            )
          else
            val curVer = installedVersion(name)
            val curCommit = installedCommit(name)
            probeRemote(decl) match
              case Left(err) =>
                Status(
                  name,
                  label,
                  decl.kind,
                  curVer,
                  curCommit,
                  None,
                  None,
                  false,
                  State.Unreachable,
                  now,
                  s"${PluginSourceCatalog.sanitize(err)} — keeping the locally installed v$curVer (the plugin stays active "
                    + "and dispatchable)"
                )
              case Right(probe) =>
                val differs = sourceDiffers(decl, name, probe, curCommit, curVer)
                Status(
                  name,
                  label,
                  decl.kind,
                  curVer,
                  curCommit,
                  probe.version,
                  Some(probe.commit),
                  differs,
                  if differs then State.Outdated else State.Current,
                  now,
                  if differs then
                    s"remote ${decl.branch} is at ${probe.commit.take(12)}…${probe.version.fold("")(v => s" (v$v)")}; " +
                      s"installed v$curVer${curCommit.fold(" (no recorded commit)")(c => s" at ${c.take(12)}…")}"
                  else s"up to date — both at ${probe.commit.take(12)}… (v$curVer)"
                )
            end match
          end if
      end match
    }.handleError(e =>
      Status(
        name,
        "",
        "",
        installedVersion(name),
        None,
        None,
        None,
        false,
        State.Unreachable,
        System.currentTimeMillis() / 1000L,
        s"probe failed (${e.getClass.getSimpleName}: ${Option(e.getMessage).getOrElse("")}) — keeping the locally installed version"
      )
    )

  /**
   * 「远端是否领先本地」判据。
   *
   * git 源的 normal case 是**有账本 commit**（`update` 每次都把实测 commit 写进 pin）⇒
   * 直接比 commit（最硬：内容 hash 级等价）。但两种诚实情形不能靠 commit 判：
   *  - **账本缺 commit**（本批之前装的老包 / 手放的目录 / 账本被删）⇒ 无基准可判
   *    「同一份」，此时只要**远端与本地版本相同**就视为已是最新——否则每次打开面板都
   *    喊「有新版本」而点下去拉回来的是一模一样的内容（假更新 = 误导动作）。版本不同
   *    则确实有新版，照常报 outdated。
   *  - **local 源**（开发态目录）无 commit 语义 ⇒ 同样按版本号判（内容变了但版本号没动
   *    的本地迭代，用户手动点更新即可——`update` 不看本函数，它是无条件拉取）。
   */
  private def sourceDiffers(
    decl: PluginSourceCatalog.SourceDecl,
    name: String,
    probe: Probe,
    curCommit: Option[String],
    curVersion: String
  ): Boolean =
    if decl.isLocal then probe.version.forall(_ != curVersion)
    else
      curCommit match
        case Some(c) => c != probe.commit
        case None => probe.version.forall(_ != curVersion)

  /** 全量状态（面板一次拿全）。只对**声明表**里的包探远端——seed 包零网络。 */
  def checkAll(): IO[List[Status]] =
    IO.blocking(PluginSourceCatalog.declarations().keys.toList.sorted).flatMap { names =>
      names.foldLeft(IO.pure(List.empty[Status])) { (acc, n) =>
        acc.flatMap(list => check(n).map(list :+ _))
      }
    }

  /** 声明表 × 状态的 JSON 投影（REST/CLI 共用冻结形状）。 */
  def statusJson(s: Status): Json =
    Json.fromJsonObject(
      JsonObject.empty
        .add("name", s.name.asJson)
        .add("state", s.state.asJson)
        .add("kind", s.kind.asJson)
        .add("source", s.sourceLabel.asJson)
        .add("installedVersion", s.installedVersion.asJson)
        .add("installedCommit", s.installedCommit.asJson)
        .add("remoteVersion", s.remoteVersion.asJson)
        .add("remoteCommit", s.remoteCommit.asJson)
        .add("updateAvailable", s.updateAvailable.asJson)
        .add("checkedAt", s.checkedAt.asJson)
        .add("detail", s.detail.asJson)
    )

  // ── 拉取并生效（更新链主腿）──────────────────────────────────

  /**
   * 拉取并生效。`expectCommit` 给定时只接受该 commit（可复现动作 / 测试 / 回滚腿）；
   * 缺省 = 跟声明里的分支 tip。
   *
   * 步骤（每一步失败都不动在位版本，见类注释「离线纪律」）：① 声明查找 → ② 浅克隆到
   * **暂存会话**（`<dataRoot>/.plugin-staging/`，在 `plugins/` 之外 ⇒ 半装态永不进扫描面）
   * → ③ 布局与身份校验（`plugin.json` 在包根、`name` 与声明一致）→ ④ 现算 digest
   * → ⑤ 备份旧版（`plugins-backups/<ts>_pre-update-<name>/` + `PRE-SHA256.txt`）
   * → ⑥ 原子换装 → ⑦ 缓存失效 + 账本落 pin + 审计 + 信任记录刷新。
   *
   * 返回 [[UpdateResult]]；失败 ⇒ `Left(人读原因（含稳定错误码）)`，**在位版本零变化**。
   */
  def update(
    name: String,
    expectCommit: Option[String] = None,
    by: String = "panel/rest"
  ): IO[Either[String, UpdateResult]] =
    IO.blocking {
      PluginSourceCatalog.declaration(name) match
        case None =>
          Left(s"'$name' is not a source-managed package — nothing to update (${CodeUnknownName})")
        case Some(decl) =>
          val fromVersion = installedVersion(name)
          val hadDir = os.isDir(pluginsDir / name)
          withStaging(s"update-$name") { staged =>
            runUpdate(decl, staged, name, fromVersion, hadDir, expectCommit, by)
          }.left.map { msg =>
            auditFailure(name, by, msg)
            msg
          }
    }

  private def runUpdate(
    decl: PluginSourceCatalog.SourceDecl,
    staged: os.Path,
    name: String,
    fromVersion: String,
    hadDir: Boolean,
    expectCommit: Option[String],
    by: String
  ): Either[String, UpdateResult] =
    for
      fetched <- expectCommit match
        case Some(c) if !c.matches("[0-9a-f]{40}") =>
          Left(s"expectCommit '$c' is not a 40-hex commit (${CodeLayout})")
        case Some(c) if decl.isLocal =>
          // 本地源 = 目录拷贝：pin 住某个 commit 无意义（无仓语义）⇒ 只做 pull
          fetchInto(decl, staged).map(_ => c)
        case Some(c) =>
          // 指定 commit：clone 分支后 depth 不足以取任意 commit ⇒ 全量 clone + checkout
          fetchCommit(decl, staged, c)
        case None => fetchInto(decl, staged)
      _ <- validateLayout(staged, decl)
      digestCount <- PluginRegistry
        .computeDigest(packageRoot(staged, decl))
        .left
        .map(err => s"digest computation failed on the freshly pulled tree: $err (${CodeLayout})")
      (digest, _) = digestCount
      toVersion = versionOf(staged, decl)
      // 统一生命周期计划（§6.3/§9 A 行）：swap 前锁定在位世代两面 + 作者策略
      prior = priorFaces(name, hadDir)
      policy = PluginSourceLedger.query(name) match
        case PluginSourceLedger.Known(e) => e.updatePolicy
        case _ => PluginDistributionTrust.UpdatePolicy.Check
      plan = PluginLifecyclePlan.Plan(
        operation = if hadDir then PluginLifecyclePlan.Operation.Update else PluginLifecyclePlan.Operation.Install,
        name = name,
        candidateVersion = toVersion,
        candidateDigest = digest,
        currentVersion = prior._1,
        currentDigest = prior._2,
        policyVersion = PluginLifecyclePlan.policyVersionOf(policy)
      )
      backup = backupInstalled(name, hadDir)
      _ <- swapIn(name, packageRoot(staged, decl))
      _ = PluginRegistry.invalidateCache()
      _ = recordProvenance(decl, name, fetched, toVersion, digest, by)
      _ = audit(name, by, fromVersion, toVersion, fetched, digest, backup, PluginLifecyclePlan.planId(plan))
      _ = refreshTrustRecord(name)
    yield UpdateResult(
      name = name,
      fromVersion = fromVersion,
      toVersion = toVersion,
      commit = fetched,
      digest = digest,
      backupDir = backup,
      planId = PluginLifecyclePlan.planId(plan),
      effective =
        "active on the next scan (presence = trust; zero restart). New dispatches get the new content; nodes already " +
          "running keep what they were injected with."
    )

  /** swap 前在位世代两面（版本/摘要；目录缺席 ⇒ None——自愈缺失装回 = install 计划面）。 */
  private def priorFaces(name: String, hadDir: Boolean): (Option[String], Option[String]) =
    if !hadDir then (None, None)
    else
      val v = installedVersion(name)
      PluginRegistry.computeDigest(pluginsDir / name) match
        case Right((d, _)) => (Option.when(v.nonEmpty)(v), Some(d))
        case Left(_) => (Option.when(v.nonEmpty)(v), None)

  /** 指定 commit 的拉取：完整 clone（浅克隆取不到任意 commit）+ `checkout <commit>`。 */
  private def fetchCommit(
    decl: PluginSourceCatalog.SourceDecl,
    dest: os.Path,
    commit: String
  ): Either[String, String] =
    val res = git(List("clone", "--quiet", decl.url, dest.toString), timeoutMs = CloneTimeoutMs)
    if res.exitCode != 0 then
      Left(
        s"git clone failed for ${PluginSourceCatalog.describe(decl)} (exit ${res.exitCode}): ${errTail(res)} " +
          s"(${CodeUnreachable})"
      )
    else
      val co = git(List("-C", dest.toString, "checkout", "--quiet", commit))
      if co.exitCode != 0 then Left(s"git checkout ${commit.take(12)}… failed: ${errTail(co)} (${CodeLayout})")
      else
        stripVcsMetadata(dest)
        Right(commit)

  end fetchCommit

  private def validateLayout(root: os.Path, decl: PluginSourceCatalog.SourceDecl): Either[String, Unit] =
    val pkg = packageRoot(root, decl)
    val manifest = pkg / "plugin.json"
    if !os.isDir(pkg) then
      Left(
        s"pulled tree has no package root at '${decl.path.getOrElse("<repo root>")}' — check " +
          s"plugins.sources.${decl.name}.path (${CodeLayout})"
      )
    else if !os.isFile(manifest) then
      Left(s"pulled package root has no plugin.json — a plugin package root must be flat (${CodeLayout})")
    else
      io.circe.parser.parse(os.read(manifest)) match
        case Left(err) => Left(s"pulled plugin.json unparseable: ${err.message} (${CodeLayout})")
        case Right(json) =>
          json.hcursor.downField("name").as[String].toOption.map(_.trim).filter(_.nonEmpty) match
            case Some(n) if n == decl.name => Right(())
            case Some(other) =>
              Left(
                s"pulled manifest declares name '$other' but the source declaration is for '${decl.name}' — refusing " +
                  s"to swap (a source may only ever replace the package it names) (${CodeLayout})"
              )
            case None => Left(s"pulled manifest has no usable 'name' (${CodeLayout})")

    end if

  end validateLayout

  private def versionOf(root: os.Path, decl: PluginSourceCatalog.SourceDecl): String =
    io.circe.parser
      .parse(os.read(packageRoot(root, decl) / "plugin.json"))
      .toOption
      .flatMap(_.hcursor.downField("version").as[String].toOption)
      .map(_.trim)
      .getOrElse("")

  /** 覆盖前必备份（回滚材料完整性；与 `SeedService.refreshFromSeed` 同族纪律）。 */
  private def backupInstalled(name: String, hadDir: Boolean): Option[String] =
    if !hadDir then None
    else
      try
        val stamp = java.time.format.DateTimeFormatter
          .ofPattern("yyyyMMdd_HHmmss")
          .format(java.time.LocalDateTime.now())
        val dir = backupRoot / s"${stamp}_pre-update-$name"
        val src = pluginsDir / name
        os.makeDir.all(dir)
        os.walk(src).filter(os.isFile).foreach { f =>
          val dest = dir / os.SubPath(f.relativeTo(src).toString)
          os.makeDir.all(dest / os.up)
          os.copy.over(f, dest)
        }
        val shas = os.walk(src).filter(os.isFile).toList.sortBy(_.toString).map { f =>
          val rel = f.relativeTo(src).toString
          s"${sha256Hex(os.read.bytes(f))}  $rel"
        }
        os.write.over(dir / "PRE-SHA256.txt", s"# plugin=$name  sampled=$stamp\n" + shas.mkString("\n") + "\n")
        Some(dir.toString)
      catch
        case NonFatal(e) =>
          logger.warnSync(
            s"pre-update backup of '$name' failed (${e.getClass.getSimpleName}: ${e.getMessage}) — continuing " +
              "(the swap itself is still atomic)"
          )
          None

  /**
   * 原子换装：旧树先挪去暂存区（同分区 rename）→ 新树 rename 到位 → 删旧树残骸。
   * 任何一步失败 ⇒ 旧树搬回原位（best-effort 回滚）+ 点名报错；绝不留下「两半都在」。
   */
  private def swapIn(name: String, newTree: os.Path): Either[String, Unit] =
    val target = pluginsDir / name
    val aside = stagingRoot / s"aside-$name-${System.currentTimeMillis()}"
    try
      os.makeDir.all(pluginsDir)
      os.makeDir.all(stagingRoot)
      if os.exists(target) then os.move(target, aside)
      try
        os.move(newTree, target)
        try os.remove.all(aside)
        catch case NonFatal(_) => ()
        Right(())
      catch
        case NonFatal(e) =>
          try if os.exists(aside) then os.move(aside, target)
          catch
            case NonFatal(re) =>
              logger.warnSync(
                s"rollback of '$name' failed after a failed swap (${re.getMessage}) — the previous version remains at $aside"
              )
          Left(
            s"atomic swap failed (${e.getClass.getSimpleName}: ${e.getMessage}); the previously installed version was " +
              s"restored (${CodeSwapFailed})"
          )
      end try
    catch
      case NonFatal(e) =>
        Left(s"swap preparation failed (${e.getClass.getSimpleName}: ${e.getMessage}) (${CodeSwapFailed})")

    end try

  end swapIn

  /**
   * 账本落账：`kind` = 声明态、`via` = 对应枚举、`pin` = **实测 commit**（浮动分支在这里被
   * 钉成不可变锚，§2.2B 零妥协）。落账 best-effort：失败只 WARN，不回滚换装
   * （在位即信任），该包 provenance 查询面降级 unknown-source（诚实降级）。
   */
  private def recordProvenance(
    decl: PluginSourceCatalog.SourceDecl,
    name: String,
    commit: String,
    version: String,
    digest: String,
    by: String
  ): Unit =
    val (kind, via, pin) =
      if decl.isGit then (Kind.Git, Via.Git, Some(Pin.Commit(None, commit)))
      else (Kind.Local, Via.Local, None)
    PluginSourceLedger
      .recordInstall(
        name = name,
        kind = kind,
        url = Some(decl.url),
        pin = pin,
        version = version,
        digest = digest,
        via = via,
        meta = PluginSourceLedger.InstallMeta(validationSummary = Some("sync-swap@1"))
      )
      .left
      .foreach(err =>
        logger.warnSync(
          s"[plugin-sync] provenance recording failed for '$name' after update by $by ($err) — the package stays " +
            "installed at the new version; its source query degrades to unknown-source"
        )
      )

  end recordProvenance

  /** 信任记录刷新 = 审计基准前移（**不是**装载闸；换装后 `contentChanged` 应为 false）。 */
  private def refreshTrustRecord(name: String): Unit =
    try PluginRegistry.approve(name).unsafeRunSync()
    catch case NonFatal(e) => logger.warnSync(s"trust record refresh for '$name' failed (${e.getMessage})")

  // ── 自愈（缺失 ⇒ 按声明装回；验收 1 的机制面）────────────────────

  /**
   * 缺失自愈：声明表里 `preinstall=true` 的包在本地缺失 ⇒ 走同一条 [[update]] 腿装回。
   * 远端不可达 ⇒ 该包保持缺失 + 一条点名 WARN（可行动：配置/网络），绝不阻断启动、
   * 绝不影响其他包。
   *
   * **用户卸载优先**（与 seed 自愈同一条纪律，`PluginRemovePolicy` / `SeedService` 的
   * 墓碑语义）：`plugins.removed.<name>` 命中 ⇒ 不装回（显式卸载是意图，不是缺失）。
   * 重装走显式动作（面板/CLI `plugin add`、或本条 update 端点），会自动复职。
   */
  def selfHeal(): IO[List[(String, Either[String, String])]] =
    IO.blocking(
      (PluginSourceCatalog.declarations().filter(_._2.preinstall).keys.toList.sorted, PluginRemovePolicy.removedNames())
    ).flatMap { (names, removed) =>
      names.foldLeft(IO.pure(List.empty[(String, Either[String, String])])) { (acc, name) =>
        acc.flatMap { list =>
          if os.isDir(pluginsDir / name) then IO.pure(list :+ (name -> Right("already present")))
          else if removed.contains(name) then
            logger.infoSync(
              s"[plugin-sync] '$name' is declared preinstall but carries the uninstall marker (plugins.removed) — " +
                "not reinstalling (explicit removal wins; reinstall explicitly to bring it back)"
            )
            IO.pure(list :+ (name -> Right("skipped: uninstalled by the user")))
          else
            update(name, None, "boot-self-heal").map {
              case Right(r) =>
                logger.infoSync(
                  s"[plugin-sync] '$name' installed from its source — v${r.toVersion} at ${r.commit.take(12)}… " +
                    s"(source: ${PluginSourceCatalog.declaration(name).map(PluginSourceCatalog.describe).getOrElse("?")})"
                )
                list :+ (name -> (Right(s"installed v${r.toVersion}"): Either[String, String]))
              case Left(err) =>
                logger.warnSync(
                  s"[plugin-sync] '$name' is declared preinstall but could not be fetched ($err) — the package stays " +
                    s"absent on this home (no seed copy exists any more). Fix the source " +
                    s"(stand.json plugins.sources.$name) or install manually: stand plugin add <git-url>#<tag>. " +
                    "Other plugins are unaffected."
                )
                list :+ (name -> (Left(err): Either[String, String]))
            }
        }
      }
    }

  // ── 暂存会话（在 plugins/ 之外 ⇒ 半装态零扫描面）────────────────

  private def withStaging[A](label: String)(body: os.Path => Either[String, A]): Either[String, A] =
    sweepStaleStaging()
    val safe = label.replaceAll("[^A-Za-z0-9._-]", "_")
    val session = stagingRoot / s"$safe-${System.currentTimeMillis()}-${java.util.UUID.randomUUID().toString.take(8)}"
    try
      os.makeDir.all(session)
      body(session / "pkg")
    catch case NonFatal(e) => Left(s"staging session failed (${e.getClass.getSimpleName}: ${e.getMessage})")
    finally
      try os.remove.all(session)
      catch case NonFatal(_) => ()

  private def sweepStaleStaging(): Unit =
    try
      if os.isDir(stagingRoot) then
        val cutoff = System.currentTimeMillis() - 24L * 3600 * 1000
        os.list(stagingRoot).foreach { p =>
          if os.mtime(p) < cutoff then
            try os.remove.all(p)
            catch case NonFatal(_) => ()
        }
    catch case NonFatal(_) => ()

  // ── 审计（append-only；谁/何时/从哪/到哪/结果）──────────────────

  private def audit(
    name: String,
    by: String,
    fromVersion: String,
    toVersion: String,
    commit: String,
    digest: String,
    backupDir: Option[String],
    planId: String
  ): Unit =
    appendAudit(
      Json.fromJsonObject(
        JsonObject.empty
          .add("ts", System.currentTimeMillis().asJson)
          .add("event", "updated".asJson)
          .add("name", name.asJson)
          .add("by", by.asJson)
          .add("fromVersion", fromVersion.asJson)
          .add("toVersion", toVersion.asJson)
          .add("commit", commit.asJson)
          .add("digest", digest.asJson)
          .add("planId", planId.asJson)
          .add("backup", backupDir.getOrElse("").asJson)
      )
    )

  private def auditFailure(name: String, by: String, reason: String): Unit =
    logger.warnSync(s"[plugin-sync] update of '$name' failed (by $by): ${PluginSourceCatalog.sanitize(reason)}")
    appendAudit(
      Json.fromJsonObject(
        JsonObject.empty
          .add("ts", System.currentTimeMillis().asJson)
          .add("event", "update-failed".asJson)
          .add("name", name.asJson)
          .add("by", by.asJson)
          .add("reason", PluginSourceCatalog.sanitize(reason).asJson)
      )
    )

  private def appendAudit(json: Json): Unit =
    try os.write.append(auditFile, json.noSpaces + "\n", createFolders = true)
    catch case NonFatal(e) => logger.warnSync(s"plugin source sync audit append failed: ${e.getMessage}")

  private def sha256Hex(bytes: Array[Byte]): String =
    java.security.MessageDigest.getInstance("SHA-256").digest(bytes).map("%02x".format(_)).mkString

end PluginSourceSync

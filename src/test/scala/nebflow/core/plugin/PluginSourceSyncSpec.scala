package nebflow.core.plugin

import cats.effect.unsafe.implicits.global
import io.circe.Json
import munit.FunSuite
import nebflow.shared.PathUtil

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files

/**
 * 源化插件运行时链（2026-11-01 源化批）定向验证。
 *
 * 作者令逐字：「stand-dev 插件应该不进 seed 吧？运行时插件不是应该要能热更新吗，立即使用
 * 我们最新的版本」。本 spec 钉死四件事（全部**离线**可跑——零网络、零真实远端）：
 *
 *  ① 声明面：内建源清单（`PluginSourceCatalog.Builtins`）出得来、用户配置能覆盖/新增/关掉；
 *  ② 热更新链：local 源（kind=local）从 v1 → v2 的**零重启**换装——换装后 `PluginRegistry`
 *     的下一条读命令即见新版本（mtime 缓存自感知），且账本 pin / 审计行 / 备份目录齐备；
 *  ③ **离线兜底（验收 3）**：远端/本地源不可达 ⇒ `update` 返回 Left、`check` 报
 *     `unreachable`，而 `plugins/<name>/` **逐字节不变**、`PluginRegistry.resolve` 仍为 Right
 *     ——「插件不因拉取失败消失」是断言出来的，不是注释承诺的；
 *  ④ 自愈面（验收 1）：`preinstall` 包在本地缺失 ⇒ `selfHeal()` 按声明装回；用户显式卸载
 *     （`plugins.removed` 标记）优先，不自愈装回。
 *
 * 为什么用 local 源而不是真 git 远端：本 spec 必须确定性别红绿、不进 CI 网络依赖。git 腿的
 * 解析逻辑（`ls-remote` → commit → 浅克隆）与 local 腿共用同一条 `runUpdate` 管线
 * （校验 → 备份 → 原子换装 → 落账 → 审计），所以换装/回滚/落账/last-mile 语义全被覆盖；
 * 真远端那条腿由作者侧的实测（批文档里的 USTC 1.1.0 → 1.2.0 读数）承担。
 */
class PluginSourceSyncSpec extends FunSuite:

  private var prevRoot: os.Path = os.Path("/tmp")
  private var home: os.Path = os.Path("/tmp")

  override def beforeEach(context: BeforeEach): Unit =
    prevRoot = PathUtil.dataRoot
    home = os.Path(Files.createTempDirectory("nb-plugin-source-sync"))
    PathUtil.setDataRoot(home)
    PluginRegistry.invalidateCache()

  override def afterEach(context: AfterEach): Unit =
    PathUtil.setDataRoot(prevRoot)
    try os.remove.all(home)
    catch case _: Exception => ()

  // ── fixtures ───────────────────────────────────────────────

  private def pluginsDir: os.Path = home / "plugins"
  private def target: os.Path = pluginsDir / Target
  private def ledgerPath: os.Path = home / "plugin-sources" / s"$Target.json"
  private def auditPath: os.Path = home / "logs" / "plugin-source-sync.jsonl"

  /**
   * 本 spec 的受测包名（真名 `stand-dev`——与内建声明的名字一致，避免「测试专用名」造成
   * 第二套形态；`plugins.sources.stand-dev` 在这条 home 上被重指到临时 local 源）。
   */
  private val Target = "stand-dev"

  /**
   * 夹具包：一个**合法**插件包（plugin.json + skills/<id>/SKILL.md），版本号可指定。
   * 内容随版本变（SKILL.md 里塞版本标记）⇒ 换装后能逐字节断言「新内容真的到位了」。
   */
  private def makePackage(dir: os.Path, version: String, marker: String = ""): os.Path =
    os.makeDir.all(dir / "skills" / "stand-dev")
    os.write.over(
      dir / "plugin.json",
      Json
        .obj(
          "$schema" -> Json.fromString(PluginRegistry.CanonicalSchema),
          "name" -> Json.fromString(Target),
          "version" -> Json.fromString(version),
          "description" -> Json.fromString(s"fixture package v$version for the plugin source sync spec"),
          "author" -> Json.obj("name" -> Json.fromString("Nebflow"))
        )
        .noSpaces
    )
    os.write.over(
      dir / "skills" / "stand-dev" / "SKILL.md",
      s"""---
         |name: stand-dev
         |description: fixture skill v$version
         |---
         |
         |# fixture v$version
         |$marker
         |""".stripMargin
    )
    dir

  end makePackage

  /** 把 `nebflow.json` 的 `plugins.sources.<Target>` 写成一条 local 源声明。 */
  private def declareLocalSource(url: String, preinstall: Boolean = true, enabled: Boolean = true): Unit =
    writeSourceDecl("local", url, preinstall, enabled)

  /** 把 `nebflow.json` 的 `plugins.sources.<Target>` 写成一条 git 源声明（`url` = 本机一个真 git 仓）。 */
  private def declareGitSource(url: String, branch: String = "main", preinstall: Boolean = true): Unit =
    writeSourceDecl("git", url, preinstall, enabled = true, branch = branch)

  private def writeSourceDecl(
    kind: String,
    url: String,
    preinstall: Boolean,
    enabled: Boolean,
    branch: String = "main"
  ): Unit =
    val cfg = home / nebflow.shared.Branding.configFileName
    val decl = Json.obj(
      "kind" -> Json.fromString(kind),
      "url" -> Json.fromString(url),
      "branch" -> Json.fromString(branch),
      "preinstall" -> Json.fromBoolean(preinstall),
      "enabled" -> Json.fromBoolean(enabled)
    )
    val json =
      if os.exists(cfg) then
        io.circe.parser
          .parse(os.read(cfg))
          .toOption
          .getOrElse(Json.obj())
          .mapObject(_.add("plugins", Json.obj("sources" -> Json.obj(Target -> decl))))
      else Json.obj("plugins" -> Json.obj("sources" -> Json.obj(Target -> decl)))
    os.write.over(cfg, json.noSpaces)

  end writeSourceDecl

  /** 造一个**真 git 仓**（本机路径，无网络）：一次提交在 `main` 上，返回仓路径。 */
  private def makeGitRepo(scratch: os.Path, version: String, marker: String): os.Path =
    val repo = makePackage(scratch / "repo", version, marker)
    def gitx(args: String*): Unit =
      os.proc(("git" :: args.toList).map(a => a: os.Shellable)*)
        .call(
          cwd = repo,
          check = true,
          env = Map(
            "GIT_AUTHOR_NAME" -> "spec",
            "GIT_AUTHOR_EMAIL" -> "spec@example.invalid",
            "GIT_COMMITTER_NAME" -> "spec",
            "GIT_COMMITTER_EMAIL" -> "spec@example.invalid"
          ),
          stdout = os.Pipe,
          stderr = os.Pipe
        )
    gitx("init", "--quiet", "--initial-branch", "main", ".")
    gitx("add", "-A")
    gitx("commit", "--quiet", "-m", s"v$version")
    repo

  end makeGitRepo

  private def installedVersion: String =
    io.circe.parser
      .parse(os.read(target / "plugin.json"))
      .toOption
      .flatMap(_.hcursor.downField("version").as[String].toOption)
      .getOrElse("")

  private def skillText: String =
    new String(os.read.bytes(target / "skills" / "stand-dev" / "SKILL.md"), UTF_8)

  private def dirDigest(p: os.Path): String = PluginRegistry.computeDigest(p).toOption.map(_._1).getOrElse("")

  private def treeAsText(p: os.Path): Map[String, String] =
    os.walk(p)
      .filter(os.isFile)
      .map(f => f.relativeTo(p).toString -> new String(os.read.bytes(f), UTF_8))
      .toMap

  // ── ① 声明面 ────────────────────────────────────────────────

  test(
    "①a builtin catalog declares stand-dev as a preinstall git source (self-authored packages ride the remote, not the seed)"
  ):
    val decl = PluginSourceCatalog.declaration("stand-dev")
    assert(decl.isDefined, "stand-dev must be a source-managed builtin")
    val d = decl.get
    assertEquals(d.kind, "git", "built-in declaration is a git source")
    assert(d.preinstall, "built-in self-authored packages are preinstalled (missing home self-heals)")
    assert(d.url.contains("stand-dev.git"), s"git URL points at the stand-dev repository, got ${d.url}")
    assert(PluginSourceCatalog.builtinNames.contains("stand-dev"))

  test("①b a user declaration overrides url/branch and can switch a declaration off"):
    declareLocalSource("/tmp/somewhere")
    val d1 = PluginSourceCatalog.declaration("stand-dev").getOrElse(fail("declaration must resolve"))
    assertEquals(d1.kind, "local", "user config overrides the builtin kind")
    assertEquals(d1.url, "/tmp/somewhere")

    // enabled:false ⇒ 该条整体消失（含内建覆盖）
    declareLocalSource("/tmp/somewhere", enabled = false)
    assert(
      PluginSourceCatalog.declaration("stand-dev").isEmpty,
      "enabled:false removes the declaration entirely (builtin included) — an off switch without deleting config"
    )

  test("①c a malformed declaration is dropped with a warning, the rest of the table survives"):
    os.write.over(
      home / nebflow.shared.Branding.configFileName,
      Json
        .obj(
          "plugins" -> Json.obj(
            "sources" -> Json.obj(
              "broken-plugin" -> Json.obj("kind" -> Json.fromString("ftp")), // unknown kind
              Target -> Json.obj("kind" -> Json.fromString("local"), "url" -> Json.fromString("/tmp/x"))
            )
          )
        )
        .noSpaces
    )
    val table = PluginSourceCatalog.declarations()
    assert(!table.contains("broken-plugin"), "unknown kind ⇒ that entry is dropped")
    assert(table.contains(Target), "valid sibling entry survives (single-entry isolation)")

  // ── ② 热更新链（零重启）─────────────────────────────────────

  test(
    "②a local source v1 → v2: the swap lands new bytes, refreshes the ledger pin, writes an audit line and keeps a backup"
  ):
    val v1 = makePackage(os.Path(Files.createTempDirectory("nb-src-v1").toString), "1.0.0", "V1-BODY")
    val v2 = makePackage(os.Path(Files.createTempDirectory("nb-src-v2").toString), "2.0.0", "V2-BODY")

    declareLocalSource(v1.toString)
    // 首次安装（缺失 ⇒ 走同一条 update 腿）
    val first = PluginSourceSync.update(Target, None, "spec-install").unsafeRunSync()
    assert(first.isRight, s"initial install must succeed: $first")
    assertEquals(installedVersion, "1.0.0")
    assert(skillText.contains("V1-BODY"), "v1 content is on disk")

    // 换源到 v2 ⇒ 更新
    declareLocalSource(v2.toString)
    val second = PluginSourceSync.update(Target, None, "spec-update").unsafeRunSync()
    assert(second.isRight, s"update must succeed: $second")
    val res = second.toOption.get
    assertEquals(res.fromVersion, "1.0.0")
    assertEquals(res.toVersion, "2.0.0")

    // ① 新内容真的到位了（逐字节）
    assertEquals(installedVersion, "2.0.0", "plugin.json now declares v2")
    assert(skillText.contains("V2-BODY"), "v2 body is on disk (content actually swapped, not just a version bump)")
    assert(!skillText.contains("V1-BODY"), "v1 body is gone (no half-merge of the two trees)")

    // ② 备份材料在（回滚面）：旧版逐文件 + PRE-SHA256.txt
    val backups = os.list(home / "plugins-backups").map(_.last).filter(_.contains("_pre-update-"))
    assertEquals(backups.size, 1, s"exactly one pre-update backup, got: ${backups.mkString(", ")}")
    val backupDir = home / "plugins-backups" / backups.head
    assert(os.exists(backupDir / "PRE-SHA256.txt"), "backup carries a per-file sha256 manifest")
    assert(
      new String(os.read.bytes(backupDir / "skills" / "stand-dev" / "SKILL.md"), UTF_8).contains("V1-BODY"),
      "the backup holds the PREVIOUS (v1) content"
    )

    // ③ 账本：pin = 实测 commit / digest 现算；via=local
    assert(os.exists(ledgerPath), "provenance ledger entry written")
    val ledger = io.circe.parser.parse(os.read(ledgerPath)).toOption.getOrElse(fail("ledger must parse"))
    assertEquals(ledger.hcursor.downField("name").as[String].toOption, Some(Target))
    assertEquals(ledger.hcursor.downField("version").as[String].toOption, Some("2.0.0"))
    assertEquals(ledger.hcursor.downField("kind").as[String].toOption, Some("local"))
    assertEquals(ledger.hcursor.downField("via").as[String].toOption, Some("local"))
    assertEquals(
      ledger.hcursor.downField("digest").as[String].toOption,
      Some(dirDigest(target)),
      "ledger digest == the on-disk content digest (现算，不信任声明值)"
    )

    // ④ 审计：一次 updated 行，含 from/to/commit
    assert(os.exists(auditPath), "audit log written")
    val lines = os.read.lines(auditPath).toList
    assert(lines.exists(_.contains("\"event\":\"updated\"")), "an 'updated' audit line exists")
    val upd = lines.find(l => l.contains("\"event\":\"updated\"") && l.contains("\"toVersion\":\"2.0.0\""))
    assert(upd.isDefined, "the audit line records the target version")

  test("②b zero restart: the very next registry read sees the new version (mtime cache self-invalidates)"):
    val v1 = makePackage(os.Path(Files.createTempDirectory("nb-src-r1").toString), "1.0.0", "R1")
    val v2 = makePackage(os.Path(Files.createTempDirectory("nb-src-r2").toString), "2.0.0", "R2")
    declareLocalSource(v1.toString)
    assert(PluginSourceSync.update(Target, None, "spec").unsafeRunSync().isRight)

    // 读一次，让扫描缓存**建立**在 v1 上（这是「缓存陈旧」的最坏起点）
    assertEquals(PluginRegistry.resolve(Target).unsafeRunSync().toOption.map(_.version), Some("1.0.0"))

    declareLocalSource(v2.toString)
    assert(PluginSourceSync.update(Target, None, "spec").unsafeRunSync().isRight)

    // 不重启、不显式 invalidateCache：下一条读命令就必须看到 v2
    assertEquals(
      PluginRegistry.resolve(Target).unsafeRunSync().toOption.map(_.version),
      Some("2.0.0"),
      "the next read after the swap sees the new version (zero restart — mtime signature changed)"
    )

    // 注入面同源：节点 spawn 时读的那份 Plugin.md 内容也随换装更新
    assertEquals(
      PluginRegistry.scan().unsafeRunSync().find(_.name == Target).map(_.version),
      Some("2.0.0"),
      "scan() (the dispatcher catalog + panel source) agrees"
    )

  test("②c the swap never leaves two trees behind and never keeps a half-staged package in plugins/"):
    val v1 = makePackage(os.Path(Files.createTempDirectory("nb-src-a1").toString), "1.0.0", "A1")
    declareLocalSource(v1.toString)
    assert(PluginSourceSync.update(Target, None, "spec").unsafeRunSync().isRight)
    assertEquals(os.list(pluginsDir).filter(os.isDir).map(_.last).toList, List(Target))
    // 暂存会话在 plugins/ 之外，且结束后自清（零残留）
    val staging = home / ".plugin-staging"
    assert(
      !os.isDir(staging) || os.list(staging).isEmpty,
      "no staging residue after a successful swap (staging root lives outside plugins/)"
    )

  // ── ③ 离线 / 源不可达兜底（验收 3）────────────────────────────

  test("③a unreachable source: update fails cleanly, the installed package stays byte-identical and still resolves"):
    val v1 = makePackage(os.Path(Files.createTempDirectory("nb-src-off1").toString), "1.0.0", "STEADY")
    declareLocalSource(v1.toString)
    assert(PluginSourceSync.update(Target, None, "spec").unsafeRunSync().isRight)
    val before = treeAsText(target)
    val beforeDigest = dirDigest(target)

    // 源消失（= 远端不可达的 local 等价面）
    os.remove.all(v1)
    val fail = PluginSourceSync.update(Target, None, "spec").unsafeRunSync()
    assert(fail.isLeft, "update must fail when the source is unreachable")
    val msg = fail.left.toOption.getOrElse("")
    assert(
      msg.contains(PluginSourceSync.CodeLocalMissing) || msg.contains(PluginSourceSync.CodeUnreachable),
      s"the failure names a stable error code, got: $msg"
    )

    // 🔴 铁律：插件**不因拉取失败消失**——内容逐字节不变、仍可解析为 trusted
    assertEquals(treeAsText(target), before, "installed tree is byte-identical after a failed update")
    assertEquals(dirDigest(target), beforeDigest, "digest unchanged")
    val resolved = PluginRegistry.resolve(Target).unsafeRunSync()
    assert(resolved.isRight, s"the package still resolves (stays dispatchable): $resolved")
    assertEquals(resolved.toOption.map(_.version), Some("1.0.0"), "version stays at the last good one")

  test("③b unreachable source: `check` reports state=unreachable with an actionable reason, never an exception"):
    val v1 = makePackage(os.Path(Files.createTempDirectory("nb-src-off2").toString), "1.0.0", "KEEP")
    declareLocalSource(v1.toString)
    assert(PluginSourceSync.update(Target, None, "spec").unsafeRunSync().isRight)
    os.remove.all(v1)

    val st = PluginSourceSync.check(Target).unsafeRunSync()
    assertEquals(st.state, PluginSourceSync.State.Unreachable)
    assertEquals(st.installedVersion, "1.0.0", "the status still reports the locally installed version")
    assert(
      st.detail.contains("keeping the locally installed"),
      s"the detail says the local version is kept, got: ${st.detail}"
    )
    assert(!st.updateAvailable, "no update is claimable while the source is unreachable")

  test("③c a source tree that declares another package name is refused before any swap"):
    val v1 = makePackage(os.Path(Files.createTempDirectory("nb-src-name").toString), "1.0.0", "GOOD")
    declareLocalSource(v1.toString)
    assert(PluginSourceSync.update(Target, None, "spec").unsafeRunSync().isRight)
    val before = treeAsText(target)

    // 换一条**名字不符**的源（manifest name = other-plugin）
    val bad = os.Path(Files.createTempDirectory("nb-src-bad").toString)
    os.makeDir.all(bad / "skills" / "other")
    os.write.over(
      bad / "plugin.json",
      Json
        .obj(
          "$schema" -> Json.fromString(PluginRegistry.CanonicalSchema),
          "name" -> Json.fromString("other-plugin"),
          "version" -> Json.fromString("9.9.9"),
          "description" -> Json.fromString("wrong package")
        )
        .noSpaces
    )
    os.write.over(bad / "skills" / "other" / "SKILL.md", "---\nname: other\ndescription: x\n---\n")
    declareLocalSource(bad.toString)

    val res = PluginSourceSync.update(Target, None, "spec").unsafeRunSync()
    assert(res.isLeft, "a source naming a different package must be refused")
    assert(
      res.left.toOption.getOrElse("").contains(PluginSourceSync.CodeLayout),
      s"refusal names the layout/identity code, got: ${res.left.toOption.getOrElse("")}"
    )
    assertEquals(treeAsText(target), before, "no swap happened — the installed tree is untouched")

  // ── ④ 自愈（验收 1）───────────────────────────────────────────

  test("④a a preinstall declaration self-heals a missing package from its source"):
    val v1 = makePackage(os.Path(Files.createTempDirectory("nb-src-heal").toString), "1.0.0", "HEALED")
    declareLocalSource(v1.toString, preinstall = true)
    assert(!os.isDir(target), "precondition: not installed")

    val report = PluginSourceSync.selfHeal().unsafeRunSync()
    val mine = report.find(_._1 == Target).getOrElse(fail("selfHeal must report the declared package"))
    assert(mine._2.isRight, s"self-heal installs from the source: ${mine._2}")
    assert(os.exists(target / "plugin.json"), "package directory restored")
    assertEquals(installedVersion, "1.0.0")
    assert(skillText.contains("HEALED"), "content came from the source, not from a seed copy")

    // 幂等：第二次直接跳过（在位零触碰）
    val second = PluginSourceSync.selfHeal().unsafeRunSync()
    assertEquals(second.find(_._1 == Target).map(_._2), Some(Right("already present")))

  test("④b an explicitly uninstalled package is NOT self-healed (explicit removal wins over preinstall)"):
    val v1 = makePackage(os.Path(Files.createTempDirectory("nb-src-rm").toString), "1.0.0", "NOPE")
    declareLocalSource(v1.toString, preinstall = true)
    // 写卸载标记（= PluginRemovePolicy 的 plugins.removed.<name>）
    os.write.over(
      home / nebflow.shared.Branding.configFileName,
      Json
        .obj(
          "plugins" -> Json.obj(
            "sources" -> Json.obj(
              Target -> Json.obj(
                "kind" -> Json.fromString("local"),
                "url" -> Json.fromString(v1.toString),
                "preinstall" -> Json.fromBoolean(true)
              )
            ),
            "removed" -> Json.obj(Target -> Json.obj("at" -> Json.fromLong(1L), "by" -> Json.fromString("spec")))
          )
        )
        .noSpaces
    )
    val report = PluginSourceSync.selfHeal().unsafeRunSync()
    assert(
      report.exists((n, r) => n == Target && r.exists(_.contains("uninstalled"))),
      s"removal marker respected, got: $report"
    )
    assert(!os.isDir(target), "the package stays uninstalled (no silent resurrection)")

  test("④c self-heal never overwrites an already-present package directory"):
    val src = makePackage(os.Path(Files.createTempDirectory("nb-src-keep").toString), "1.0.0", "SOURCE")
    declareLocalSource(src.toString, preinstall = true)
    // 本地已有一份**用户改过**的版本（版本号更高、内容不同）
    os.makeDir.all(target / "skills" / "stand-dev")
    os.write.over(
      target / "plugin.json",
      Json
        .obj(
          "$schema" -> Json.fromString(PluginRegistry.CanonicalSchema),
          "name" -> Json.fromString(Target),
          "version" -> Json.fromString("9.9.9"),
          "description" -> Json.fromString("user version")
        )
        .noSpaces
    )
    os.write.over(
      target / "skills" / "stand-dev" / "SKILL.md",
      "---\nname: stand-dev\ndescription: user\n---\nUSER-BODY\n"
    )
    val before = treeAsText(target)

    val report = PluginSourceSync.selfHeal().unsafeRunSync()
    assertEquals(report.find(_._1 == Target).map(_._2), Some(Right("already present")))
    assertEquals(treeAsText(target), before, "the in-place package is untouched by self-heal (presence wins)")

  // ── ⑤ 状态形状（面板/CLI/REST 共用）──────────────────────────

  test("⑤ check reports current/outdated correctly and statusJson carries the frozen field set"):
    val v1 = makePackage(os.Path(Files.createTempDirectory("nb-src-st1").toString), "1.0.0", "S1")
    declareLocalSource(v1.toString)
    assert(PluginSourceSync.update(Target, None, "spec").unsafeRunSync().isRight)

    val cur = PluginSourceSync.check(Target).unsafeRunSync()
    assertEquals(cur.state, PluginSourceSync.State.Current)
    assertEquals(cur.installedVersion, "1.0.0")
    assertEquals(cur.remoteVersion, Some("1.0.0"))

    // 源前进到 v2 ⇒ outdated + updateAvailable
    val v2 = makePackage(os.Path(Files.createTempDirectory("nb-src-st2").toString), "2.0.0", "S2")
    declareLocalSource(v2.toString)
    val out = PluginSourceSync.check(Target).unsafeRunSync()
    assertEquals(out.state, PluginSourceSync.State.Outdated)
    assert(out.updateAvailable)
    assertEquals(out.remoteVersion, Some("2.0.0"))

    val json = PluginSourceSync.statusJson(out)
    for key <- List(
        "name",
        "state",
        "kind",
        "source",
        "installedVersion",
        "installedCommit",
        "remoteVersion",
        "remoteCommit",
        "updateAvailable",
        "checkedAt",
        "detail"
      )
    do assert(json.hcursor.downField(key).focus.isDefined, s"status payload carries '$key'")

  test("⑤b a name with no declaration is 'unknown', not an error (seed packages are legitimately unknown here)"):
    val st = PluginSourceSync.check("visual-report").unsafeRunSync()
    assertEquals(st.state, PluginSourceSync.State.Unknown)
    assertEquals(
      PluginSourceSync
        .update("visual-report", None, "spec")
        .unsafeRunSync()
        .left
        .toOption
        .map(_.contains(PluginSourceSync.CodeUnknownName)),
      Some(true)
    )

  test(
    "②d the git leg really shells out to `git` (a path-backed repo clones, pins its commit, then updates to a newer commit)"
  ):
    // 🔴 回归锚：本批 boot 实测抓到过「`git()` 把子命令当可执行文件」的 bug
    // （`Cannot run program "clone"`）。local 源的测试全走不到那条腿，只有真 git 源能钉住它，
    // 所以这条用**本机路径的真仓**（零网络、确定性）覆盖 clone / rev-parse / checkout 三个调用点。
    val scratch = os.Path(Files.createTempDirectory("nb-src-git").toString)
    val repo = makeGitRepo(scratch, "3.0.0", "G3")
    declareGitSource(repo.toString)

    PluginSourceSync.update(Target, None, "spec").unsafeRunSync() match
      case Left(err) => fail(s"git-source update must succeed against a path-backed repo, got: $err")
      case Right(res) =>
        assertEquals(res.fromVersion, "", "first install has no previous version")
        assertEquals(res.toVersion, "3.0.0")
        assert(res.commit.matches("[0-9a-f]{40}"), s"the pin is a real commit, got '${res.commit}'")
        assertEquals(installedVersion, "3.0.0")
        assert(skillText.contains("G3"), "the cloned bytes are the repo's bytes")
        // 🔴 装进去的是**包**，不是一个仓：`.git` 留在树里会让同一份内容的两次安装算出不同
        // digest（实测 6b0aa2c… vs 906a8c9…），信任记录与漂移仲裁随之失真。
        assert(!os.exists(target / ".git"), "the installed tree must carry no VCS metadata")

    // 仓库前进到 v4 ⇒ check 报 outdated（比 commit），update 换装到 v4
    os.write.over(
      repo / "plugin.json",
      os.read(repo / "plugin.json").replace("\"3.0.0\"", "\"4.0.0\"")
    )
    os.write.over(repo / "skills" / "stand-dev" / "SKILL.md", "# fixture v4.0.0\nG4\n")
    def gitx(args: String*): Unit =
      os.proc(("git" :: args.toList).map(a => a: os.Shellable)*)
        .call(
          cwd = repo,
          check = true,
          env = Map(
            "GIT_AUTHOR_NAME" -> "spec",
            "GIT_AUTHOR_EMAIL" -> "spec@example.invalid",
            "GIT_COMMITTER_NAME" -> "spec",
            "GIT_COMMITTER_EMAIL" -> "spec@example.invalid"
          ),
          stdout = os.Pipe,
          stderr = os.Pipe
        )
    gitx("add", "-A")
    gitx("commit", "--quiet", "-m", "v4.0.0")

    val st = PluginSourceSync.check(Target).unsafeRunSync()
    assertEquals(st.state, PluginSourceSync.State.Outdated, "a newer commit on the branch means outdated")
    assertEquals(st.remoteVersion, Some("4.0.0"))

    val res2 = PluginSourceSync.update(Target, None, "spec").unsafeRunSync()
    assert(res2.isRight, s"update to the new commit must succeed, got $res2")
    assertEquals(res2.toOption.map(_.toVersion), Some("4.0.0"))
    assertEquals(installedVersion, "4.0.0")
    assert(skillText.contains("G4"), "new commit's bytes landed")

    // 换装后再 check ⇒ current（账本 pin 已跟着走，不喊假更新）
    val after = PluginSourceSync.check(Target).unsafeRunSync()
    assertEquals(after.state, PluginSourceSync.State.Current)
    assertEquals(after.installedCommit, res2.toOption.map(_.commit))

    // 🔴 digest 只由包内容决定：同一条 commit 再装一次，digest 必须逐字相同
    // （带 .git 时这一条会红——浅克隆的 index/packed-refs 带时间戳）。
    val digestFirst = dirDigest(target)
    val again = PluginSourceSync.update(Target, res2.toOption.map(_.commit), "spec").unsafeRunSync()
    assertEquals(again.toOption.map(_.digest), Some(digestFirst), "same content ⇒ same digest")

    // 遥不可及的仓 ⇒ unreachable，本地版本原地不动（git 腿的离线兜底）
    declareGitSource((scratch / "no-such-repo").toString)
    val gone = PluginSourceSync.check(Target).unsafeRunSync()
    assertEquals(gone.state, PluginSourceSync.State.Unreachable)
    assert(PluginSourceSync.update(Target, None, "spec").unsafeRunSync().isLeft)
    assertEquals(installedVersion, "4.0.0", "an unreachable git source never uninstalls the local copy")

end PluginSourceSyncSpec

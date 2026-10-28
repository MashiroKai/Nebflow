package nebflow.core.delegate

import munit.FunSuite
import nebflow.shared.PathUtil

import scala.util.Try

/**
 * MergeArtifactCare 判性面判读（台账 #943 · 2026-10-26 作者裁定「合并队列回
 * 纯排队+通知」顺带②「自动判性」）：
 *
 *  - ① **同 blob = 已在册**：untracked 文件与 delegate 分支 tip 的某 blob 完全
 *    相同 ⇒ 陈旧复本清理（内容分支保真，合并落地自然回来）；
 *  - ② **路径外无关 untracked** ⇒ 自动移隔离区（repo 外 dataRoot/merge-quarantine/
 *    ，保相对路径 + PROVENANCE.json 记录来源）——不再堵队、零丢失；
 *  - ③ 白名单内、分支上无同内容的 untracked 仍走**收编**（交付物语义不变）；
 *  - ④ 白名单内已跟踪覆写仍还原（10-23 批语义）；白名单外 tracked 脏一概不碰
 *    （如实进 remainingDirty——#943 起调用方只 WARN 放行）；
 *  - ⑤ 整目录折叠形 `?? dir/`（porcelain 目录条目）路径外 ⇒ 整体隔离。
 *
 * 夹具：真 git 仓（真 blob/porcelain 面）。
 */
class MergeArtifactCareSpec extends FunSuite:

  private val tempRoot: os.Path = os.pwd / "target" / "test-merge-artifact-care"
  private val originalRoot = PathUtil.dataRoot

  PathUtil.setDataRoot(tempRoot)
  removeRetry(tempRoot)
  os.makeDir.all(tempRoot)

  override def afterAll(): Unit = PathUtil.setDataRoot(originalRoot)

  /**
   * git 松散对象带只读位（444）——Windows Files.delete 拒删只读文件，
   * `os.remove.all` 半途 Abort（「Windows 文件锁 flake」存量在案的真形态）。
   * 先清只读属性再删；只读位仅在 Windows 有此语义。
   */
  private def removeForce(p: os.Path): Unit =
    if os.exists(p) then
      if scala.util.Properties.isWin then
        try
          os.proc("cmd", "/c", "attrib", "-R", s"${p.toString}\\*", "/S", "/D")
            .call(check = false, stdout = os.Pipe, stderr = os.Pipe)
        catch case _: Throwable => ()
        try os.proc("cmd", "/c", "attrib", "-R", p.toString).call(check = false, stdout = os.Pipe, stderr = os.Pipe)
        catch case _: Throwable => ()
      try os.remove.all(p)
      catch case _: Throwable => ()

  /** AV/索引器短命句柄兜底：清属性删除 × 重试。 */
  private def removeRetry(p: os.Path): Unit =
    var attempt = 0
    while os.exists(p) && attempt < 8 do
      removeForce(p)
      if os.exists(p) then
        attempt += 1
        Thread.sleep(250)

  override def beforeEach(context: munit.BeforeEach): Unit =
    removeRetry(MergeArtifactCare.quarantineRoot)

  /** 真 git 基座：main（base.txt + evidence/tracked.txt）+ 分支 b1（同 blob 件在册）。每用例独立目录（不跨用例复删带只读位的仓）。 */
  private def mkRepo(dir: os.Path): os.Path =
    removeRetry(dir)
    os.makeDir.all(dir)
    os.proc("git", "init", "-b", "main", dir.toString).call(check = true, stdout = os.Pipe, stderr = os.Pipe)
    def g(args: String*): os.CommandResult =
      os.proc(Seq("git", "-C", dir.toString) ++ args).call(check = true, stdout = os.Pipe, stderr = os.Pipe)
    g("config", "user.email", "spec@nebflow.local")
    g("config", "user.name", "spec")
    os.write(dir / "base.txt", "base")
    os.write(dir / "evidence" / "tracked.txt", "tracked-v1", createFolders = true)
    g("add", ".")
    g("commit", "-m", "base")
    g("checkout", "-b", "b1")
    os.write(dir / "evidence" / "same.txt", "same-content-blob")
    g("add", ".")
    g("commit", "-m", "branch deliverable")
    g("checkout", "main")
    dir
  end mkRepo

  private def ctx = Some(MergeArtifactCare.CareContext("d-943", "b1", "main", "proj-943"))

  test("① triage removes an untracked copy whose blob is identical to the delegate branch (content preserved there)") {
    val repo = mkRepo(tempRoot / "mqac-same")
    os.write(repo / "evidence" / "same.txt", "same-content-blob") // untracked on main, in-branch on b1
    val report = MergeArtifactCare.hygiene(repo, ctx)
    assertEquals(report.triagedRemoved, List("evidence/same.txt"), "the stale same-blob copy is removed")
    assertEquals(os.exists(repo / "evidence" / "same.txt"), false, "the copy is really gone from the tree")
    assertEquals(report.error, None)
  }

  test("② out-of-path unrelated untracked is quarantined outside the repo with a PROVENANCE record — nothing deleted") {
    val repo = mkRepo(tempRoot / "mqac-quarantine")
    os.write(repo / "notes" / "scratch.txt", "someone's stray note", createFolders = true)
    val report = MergeArtifactCare.hygiene(repo, ctx)
    // porcelain 把整目录未跟踪折叠成 `?? notes/`——判性按折叠形整目录隔离。
    assertEquals(
      report.triagedQuarantined,
      List("notes"),
      "the stray out-of-path untracked dir is quarantined (collapsed form)"
    )
    assertEquals(os.exists(repo / "notes"), false, "the repo tree is clean of it")
    val dir = report.quarantineDir.map(os.Path(_)).getOrElse(fail("quarantineDir must be recorded"))
    assert(
      dir.toString.startsWith(MergeArtifactCare.quarantineRoot.toString),
      "quarantine lives under the data root, OUTSIDE the repo"
    )
    val provenance = os.read(dir / "PROVENANCE.json")
    assert(provenance.contains("notes"), s"PROVENANCE names the original path: $provenance")
    assert(provenance.contains(repo.toString.replace("\\", "\\\\")), "PROVENANCE records the origin workspace")
    assert(
      provenance.contains("d-943") && provenance.contains("b1"),
      "PROVENANCE records the delegate/branch attribution"
    )
    assertEquals(
      os.read(dir / "notes" / "scratch.txt"),
      "someone's stray note",
      "the quarantined file is intact (nothing lost)"
    )
  }

  test("③ whitelisted untracked NOT on the branch is still absorbed (deliverable semantics unchanged)") {
    val repo = mkRepo(tempRoot / "mqac-absorb")
    os.write(repo / "reports" / "fresh-report.md", "fresh deliverable", createFolders = true)
    val report = MergeArtifactCare.hygiene(repo, ctx)
    // porcelain 折叠形 `?? reports/`——收编按折叠形整目录 git add（既有语义）。
    assert(report.absorbed.exists(_.startsWith("reports")), s"the fresh artifact is absorbed: ${report.absorbed}")
    assert(report.absorbCommit.exists(_.nonEmpty), "the absorption commit is recorded")
    assertEquals(report.triagedRemoved, Nil, "nothing was triaged away")
    assertEquals(report.triagedQuarantined, Nil, "nothing was quarantined")
    assertEquals(report.remainingDirty, Nil, "the tree is clean after the absorb commit")
  }

  test(
    "④ whitelisted tracked overwrite is restored (10-23 semantics); out-of-path tracked dirt is NEVER touched (honest remainingDirty)"
  ) {
    val repo = mkRepo(tempRoot / "mqac-tracked")
    os.write.over(repo / "evidence" / "tracked.txt", "dirty overwrite") // whitelisted tracked dirt
    os.write.over(repo / "base.txt", "source dirt") // out-of-path tracked dirt — never touched
    val report = MergeArtifactCare.hygiene(repo, ctx)
    assertEquals(report.restored, List("evidence/tracked.txt"), "the whitelisted overwrite is restored")
    assertEquals(os.read(repo / "evidence" / "tracked.txt"), "tracked-v1")
    assert(
      report.remainingDirty.contains("base.txt"),
      s"out-of-path dirt is honestly reported: ${report.remainingDirty}"
    )
    assertEquals(os.read(repo / "base.txt"), "source dirt", "out-of-path tracked dirt is never modified")
  }

  test("⑤ a collapsed untracked DIRECTORY outside the whitelist is quarantined whole (porcelain `?? dir/` form)") {
    val repo = mkRepo(tempRoot / "mqac-dircollapse")
    os.write(repo / "build-cache" / "chunk.bin", "binary junk", createFolders = true)
    val report = MergeArtifactCare.hygiene(repo, ctx)
    assertEquals(
      report.triagedQuarantined,
      List("build-cache"),
      "the collapsed dir entry is quarantined (normalized, no trailing slash)"
    )
    assertEquals(os.exists(repo / "build-cache"), false, "the dir is gone from the tree")
    val dir = report.quarantineDir.map(os.Path(_)).getOrElse(fail("quarantineDir must be recorded"))
    assertEquals(
      Try(os.read(dir / "build-cache" / "chunk.bin")).toOption,
      Some("binary junk"),
      "the quarantined dir is intact"
    )
  }

  test("⑥ fail-soft: a non-repo path reports an error and never throws") {
    // 注意：`rev-parse --is-inside-work-tree` 沿父链上行——stand/target 下的目录
    // 都在 stand 仓工作树内；「非 repo」夹具必须落在系统临时区（仓链之外）。
    val notARepo = os.Path(java.nio.file.Files.createTempDirectory("mqac-not-a-repo"))
    try
      val report = MergeArtifactCare.hygiene(notARepo, ctx)
      assert(report.error.exists(_.contains("not a git repo")), s"the error is carried: ${report.error}")
      assertEquals(report.triagedRemoved, Nil)
      assertEquals(report.triagedQuarantined, Nil)
    finally removeRetry(notARepo)
  }

end MergeArtifactCareSpec

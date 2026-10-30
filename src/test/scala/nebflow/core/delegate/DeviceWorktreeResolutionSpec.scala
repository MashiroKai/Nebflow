package nebflow.core.delegate

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import munit.FunSuite
import nebflow.core.GitWorktrees

/**
 * 跨设备 worktree 归属修复批（2026-10-30，作者报障：设备项目「跨设备测试」@ KAI
 * 的 delegate worktree 被建在编排机 brain 下）判读——[[DelegateWorkspace.prepareDevice]]
 * 三态断言 + spawn 行 additive 键契约，fake 面零网络零真机：
 *
 *  - 态① 设备仓**有 git** ⇒ binding 落设备侧座位：worktree/cwd =
 *    `<设备ws>/.stand-worktrees/<sanitized-id>` 形态（归属设备 workspace，绝不
 *    在编排机 brain 下）、branch = `delegate/<id>`、baseWs = 设备 ws、brief 行 =
 *    判定机对应变体（判是 = 交付提交措辞；判否 = 只读座位措辞）；
 *  - 态② 设备仓**无 git** ⇒ None（不建 worktree——空转建权消灭；delegate 直用
 *    workspace，面板经 workspaceSeat 键标注「直接使用工作区」）；
 *  - 态③ **不可达**（isGitRepo 通道失败 Left）⇒ None 回落（WARN 语义，派发不阻断）；
 *  - 态③b 有 git 但**建败**（createWorktree Left）⇒ None 回落（同直用 workspace 形态）；
 *  - spawn 行契约：`workspaceSeat` 键 additive——传则落键、缺省则旧行字节零漂移
 *    （键缺席）；本机项目调用方恒传 None；
 *  - [[DeviceGitPort.sq]] shell 转义（含单引号/空格路径——设备本地路径禁裸拼）。
 *
 * 本地项目回归 = [[DelegateWorkspaceWorktreeLegSpec]] 全量（本批对其零改动，跑绿即
 * 「本地项目行为零变化」的 spec 钉）。
 */
class DeviceWorktreeResolutionSpec extends FunSuite:

  private val devName = "KAI"
  private val devWs   = "D:/Cadence/SPB_Data/cross-test"

  /** fake 设备 git 面：三态由参数钉死，调用记录供断言（真身 = RemoteExecutor）。 */
  private final class FakeFace(
    gitAnswer: Either[String, Boolean],
    wtAnswer: Either[String, String] = Right(""),
    refs: (Option[String], Option[String]) = (Some("main"), Some("abc1234"))
  ) extends DeviceGitPort.Face:
    val calls = scala.collection.mutable.ListBuffer.empty[(String, List[String])]
    def isGitRepo(deviceName: String, workspace: String): IO[Either[String, Boolean]] =
      IO { calls += (("isGitRepo", List(deviceName, workspace))) }.as(gitAnswer)
    def refInfo(deviceName: String, workspace: String): IO[(Option[String], Option[String])] =
      IO { calls += (("refInfo", List(deviceName, workspace))) }.as(refs)
    def createWorktree(
      deviceName: String,
      workspace: String,
      branch: String,
      dirBase: String
    ): IO[Either[String, String]] =
      IO { calls += (("createWorktree", List(deviceName, workspace, branch, dirBase))) }.as(wtAnswer)

  private def withDeviceGit[A](face: DeviceGitPort.Face)(io: IO[A]): IO[A] =
    IO(DeviceGitPort.install(face)) *> io <* IO(DeviceGitPort.clear())

  /** fake 判定机（钉死判词），用毕卸载（端口是全局注册面——测试间零残留）。 */
  private def withJudge[A](answer: Option[Boolean])(io: IO[A]): IO[A] =
    IO {
      DelegateJudge.DelegateJudgePort.install(
        new DelegateJudge.DelegateJudgePort.Face:
          def decideWorktree(task: String): IO[Option[Boolean]] = IO.pure(answer)
          def judgeTerminal(input: DelegateJudge.TerminalInput): IO[Option[DelegateJudge.Verdict]] = IO.pure(None)
      )
    } *> io <* IO(DelegateJudge.DelegateJudgePort.clear())

  test("态① 设备仓有 git: worktree 座位落在设备 workspace 的 .stand-worktrees 下（判是腿 = 交付提交 brief 变体）") {
    val id    = "delegate-nebflow-a1b2c3d4"
    val wtPath = s"$devWs/.stand-worktrees/${GitWorktrees.sanitizeRefName(id)}"
    val face  = new FakeFace(gitAnswer = Right(true), wtAnswer = Right(wtPath))
    val b = withDeviceGit(face) {
      withJudge(Some(true))(
        DelegateWorkspace.prepareDevice(devName, devWs, "implement the fix", id)
      )
    }.unsafeRunSync()
    val binding = b.getOrElse(fail("device git leg must produce a binding with a device-side worktree seat"))
    assertEquals(binding.branch, s"delegate/$id")
    val seat = binding.worktree.getOrElse(fail("device git leg must carry a worktree seat"))
    assert(seat.replace('\\', '/').startsWith(devWs.replace('\\', '/') + "/.stand-worktrees/"),
      s"worktree must live inside the DEVICE workspace .stand-worktrees (never the orchestrator brain), got: $seat")
    assertEquals(binding.cwd, seat)
    assertEquals(binding.baseWs, devWs, "baseWs = the device workspace (merge/audit anchor)")
    assertEquals(binding.baseBranch, Some("main"))
    assertEquals(binding.baseSha, Some("abc1234"))
    assertEquals(binding.worktreeJudged, Some(true))
    val line = binding.briefLine.getOrElse(fail("brief line"))
    assert(line.contains(s"[working branch] delegate/$id"), line)
    assert(line.contains("commit your deliverable commits here"), line)
    assert(!line.contains("read-only seat"), "judged-yes leg must NOT carry the read-only variant")
    // 建权参数面：分支纪律同本机腿（delegate/<id>），目录种子 = sanitize(id)。
    val wtCall = face.calls.collectFirst { case ("createWorktree", a) => a }
      .getOrElse(fail("createWorktree must be invoked on the device face"))
    assertEquals(wtCall(1), devWs)
    assertEquals(wtCall(2), s"delegate/$id")
    assertEquals(wtCall(3), GitWorktrees.sanitizeRefName(id))
  }

  test("态①b 设备仓有 git + 判否: 同一条建权路径，brief 行走只读座位变体") {
    val id     = "delegate-nebflow-r5o6p7q8"
    val wtPath = s"$devWs/.stand-worktrees/${GitWorktrees.sanitizeRefName(id)}"
    val face   = new FakeFace(gitAnswer = Right(true), wtAnswer = Right(wtPath))
    val b = withDeviceGit(face) {
      withJudge(Some(false))(
        DelegateWorkspace.prepareDevice(devName, devWs, "read-only inspection", id)
      )
    }.unsafeRunSync()
    val binding = b.getOrElse(fail("judged-no leg must still produce a binding (read-only device seat)"))
    assert(binding.worktree.isDefined, "judged-no leg = same creation path (read-only seat), not None")
    assertEquals(binding.worktreeJudged, Some(false))
    val line = binding.briefLine.getOrElse(fail("brief line"))
    assert(line.contains("read-only seat"), line)
    assert(line.contains("do NOT create commits"), line)
  }

  test("态② 设备仓无 git: 不建 worktree（None）——空转建权消灭，delegate 直用 workspace") {
    val face = new FakeFace(gitAnswer = Right(false))
    val b = withDeviceGit(face) {
      DelegateWorkspace.prepareDevice(devName, devWs, "any task", "delegate-nebflow-n0g1t2")
    }.unsafeRunSync()
    assertEquals(b, None)
    assert(!face.calls.exists(_._1 == "createWorktree"), "no-git device workspace must never attempt worktree creation")
    assert(!face.calls.exists(_._1 == "refInfo"), "no-git leg must not even read base refs")
  }

  test("态③ 设备不可达（git 探测通道失败）: WARN 语义回落 = None（不建 worktree 直用 workspace，派发不阻断）") {
    val face = new FakeFace(gitAnswer = Left("device 'KAI' unreachable"))
    val b = withDeviceGit(face) {
      DelegateWorkspace.prepareDevice(devName, devWs, "any task", "delegate-nebflow-u1n2r3")
    }.unsafeRunSync()
    assertEquals(b, None, "unreachable device = fallback to direct workspace seat, never a rejected dispatch")
    assert(!face.calls.exists(_._1 == "createWorktree"), "unreachable leg must never attempt worktree creation")
  }

  test("态③b 有 git 但建败: 回落 = None（直用 workspace 形态，fail-open 合同）") {
    val face = new FakeFace(gitAnswer = Right(true), wtAnswer = Left("NEBFLOW_WT=FAIL_GIT"))
    val b = withDeviceGit(face) {
      withJudge(Some(true))(
        DelegateWorkspace.prepareDevice(devName, devWs, "task", "delegate-nebflow-f4l5b6")
      )
    }.unsafeRunSync()
    assertEquals(b, None, "device worktree creation failure falls back to direct workspace seat (no half-binding)")
  }

  test("spawn 行契约: workspaceSeat 键 additive——传则落键，缺省则键缺席（旧行字节零漂移）") {
    val withSeat = DelegateWorkspace.spawnPayload(
      "delegate-x", "nebflow", Some("跨设备测试"), None,
      workspaceSeat = Some(devWs)
    )
    assertEquals(withSeat.hcursor.downField("workspaceSeat").as[String].toOption, Some(devWs))
    // 设备 git 腿有 binding 时不落 workspaceSeat（worktree/cwd 已是设备座位）。
    val legacy = DelegateWorkspace.spawnPayload("delegate-x", "nebflow", None, None)
    assert(!legacy.hcursor.downField("workspaceSeat").succeeded, "absent param = absent key (byte-identical legacy rows)")
    assertEquals(legacy.hcursor.downField("branch").as[String].toOption, Some(""))
  }

  test("sq shell 转义: 空格/单引号路径内嵌安全（设备本地路径禁裸拼的唯一转义点）") {
    assertEquals(DeviceGitPort.sq("D:/Cadence/SPB_Data/cross-test"), "'D:/Cadence/SPB_Data/cross-test'")
    assertEquals(DeviceGitPort.sq("/tmp/it's-here"), "'/tmp/it'\\''s-here'")
  }

end DeviceWorktreeResolutionSpec

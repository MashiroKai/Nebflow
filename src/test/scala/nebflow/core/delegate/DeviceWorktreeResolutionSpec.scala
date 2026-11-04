package nebflow.core.delegate

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import io.circe.Json
import munit.FunSuite

/**
 * 设备项目的**座位归属** spec（原「跨设备 worktree 归属修复批 2026-10-30」；
 * delegate-param-decisions 批 2026-11-04 重写）：
 *
 * 语义变更——`worktree` 参数现在是**参数决定论**（不填 = 零分配；填了 = 按名建树），
 * 而设备项目的座位在**设备盘**上，本机没有任何树可建、可复用：
 *   - 设备项目 ⇒ 座位腿**不建 worktree**（binding=None，delegate 直用设备 workspace，
 *     面板经 spawn 行 `workspaceSeat` 键标注「直接使用工作区」）；
 *   - 设备项目 + `worktree` 参数 ⇒ `Delegate` 工具**显式拒**
 *     （`DELEGATE_WORKTREE_REMOTE`——文案说清出路：drop the parameter to run in place on the device）；
 *   - 本机座位腿对设备 git 通道**零调用**（判定机与设备建树腿都已删除——下面用
 *     计数 fake 面机械钉住）。
 *
 * 保留：[[DeviceGitPort.sq]] shell 转义（含单引号/空格路径——设备本地路径禁裸拼）。
 */
class DeviceWorktreeResolutionSpec extends FunSuite:

  private val devName = "KAI"
  private val devWs   = "D:/Cadence/SPB_Data/cross-test"

  /** fake 设备 git 面：只计数（证明座位腿不再问设备 git）。 */
  private final class CountingFace extends DeviceGitPort.Face:
    val calls = scala.collection.mutable.ListBuffer.empty[String]
    def isGitRepo(deviceName: String, workspace: String): IO[Either[String, Boolean]] =
      IO { calls += "isGitRepo"; Right(true) }
    def refInfo(deviceName: String, workspace: String): IO[(Option[String], Option[String])] =
      IO { calls += "refInfo"; (Some("main"), Some("abc1234")) }
    def createWorktree(
      deviceName: String,
      workspace: String,
      branch: String,
      dirBase: String
    ): IO[Either[String, String]] =
      IO { calls += "createWorktree"; Right("D:/Cadence/SPB_Data/cross-test/.stand-worktrees/x") }

  private def withDeviceGit[A](face: DeviceGitPort.Face)(io: IO[A]): IO[A] =
    IO(DeviceGitPort.install(face)) *> io <* IO(DeviceGitPort.clear())

  test("态① 设备项目的座位腿不再问设备 git：本机不建任何树（判定机与设备建树腿均已删除）") {
    val face = new CountingFace
    val plain = os.temp.dir(prefix = "dev-seat")
    withDeviceGit(face) {
      IO {
        // 本机座位腿（零分配）跑一遍——设备通道一次都不许被问。
        DelegateWorkspace.prepareInPlace(plain.toString, "delegate-nebflow-dev1").unsafeRunSync()
      }
    }.unsafeRunSync()
    assertEquals(face.calls.toList, Nil, "the seat leg must not touch the device git channel any more")
  }

  test("态② 设备项目 + worktree 参数 = 显式拒（文案含 DELEGATE_WORKTREE_REMOTE 与出路）") {
    // 文案单点：internalSpawn 的拒绝语句（源码级判据——真调用需要设备在线，
    // 而拒绝是**参数语义**，与设备可达性无关）。
    val src = os.read(os.pwd / "src" / "main" / "scala" / "nebflow" / "core" / "tools" / "DelegateTool.scala")
    assert(src.contains("DELEGATE_WORKTREE_REMOTE"), "the device leg-refusal code must stay wired")
    assert(
      src.contains("Drop the parameter to run in place on the device"),
      "the refusal must name the way out (run in place on the device)"
    )
    assert(
      !src.contains("prepareDevice"),
      "the device worktree creation leg (prepareDevice) must be gone — device projects never get a host-side worktree"
    )
  }

  test("spawn 行契约：workspaceSeat 键 additive——传则落键，缺省则键缺席（旧行字节零漂移）") {
    val withSeat = DelegateWorkspace.spawnPayload("delegate-nebflow-ws1", "nebflow", Some("p"), None, workspaceSeat = Some(devWs))
    assertEquals(withSeat.hcursor.downField("workspaceSeat").as[String].toOption, Some(devWs))
    val without = DelegateWorkspace.spawnPayload("delegate-nebflow-ws2", "nebflow", Some("p"), None)
    assert(!without.asObject.exists(_.contains("workspaceSeat")), "absent by default (old rows byte-identical)")
  }

  test("spawn 行契约：座位来源键 seat / seatName（本批新增——命中哪条规则可审计）") {
    val b = DelegateWorkspace
      .Binding(
        branch = "delegate/abc",
        worktree = Some("/tmp/wt"),
        cwd = "/tmp/wt",
        baseWs = "/tmp/ws",
        baseBranch = Some("main"),
        baseSha = None,
        briefLine = None,
        seatSource = DelegateWorkspace.SeatSource.ExactName,
        seatName = Some("abc")
      )
    val payload = DelegateWorkspace.spawnPayload("delegate-nebflow-ws3", "nebflow", None, Some(b))
    assertEquals(payload.hcursor.downField("seat").as[String].toOption, Some("exact-name"))
    assertEquals(payload.hcursor.downField("seatName").as[String].toOption, Some("abc"))
    // 判定机的两个审计键停止写入（删了判定机还写就是假话）。
    assert(!payload.asObject.exists(_.contains("worktreeJudged")), "judge keys are never written again")
    assert(!payload.asObject.exists(_.contains("judgeLeg")), "judge keys are never written again")
  }

  test("sq shell 转义: 空格/单引号路径内嵌安全（设备本地路径禁裸拼的唯一转义点）") {
    assertEquals(DeviceGitPort.sq("D:/Cadence/SPB_Data/cross-test"), "'D:/Cadence/SPB_Data/cross-test'")
    assertEquals(DeviceGitPort.sq("/tmp/it's here"), "'/tmp/it'\\''s here'")
  }

end DeviceWorktreeResolutionSpec

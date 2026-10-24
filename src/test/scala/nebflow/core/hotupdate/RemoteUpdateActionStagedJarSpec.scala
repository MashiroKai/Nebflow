package nebflow.core.hotupdate

import cats.effect.{IO, Ref}
import cats.syntax.all.*
import munit.CatsEffectSuite

import scala.concurrent.duration.*

import io.circe.Json
import nebflow.neblink.NeblinkUpdateTarget
import nebflow.neblink.NeblinkUpdateConvergence

/**
 * 统一更新系统 M3 ③ 定向验收：**采纳闸（领账）** + **校验失败拒装**。
 *
 * 覆盖（质量闸 spec 清单 ②③④「领账 / 校验失败拒装 / 通道拒采」）：
 *  - 跨通道拒采（beta 不拖 stable，反向同理）——编排器**零调用**；
 *  - 前向-only：等值（已收敛）/ 更旧（服务端回拨）⇒ 不装；日历序判新
 *    （2026.10 vs 2026.9 的数值序，非字符串序）；
 *  - 过闸 = 自动受理（编排器 request 真实到达）；同 target 重复心跳只在
 *    **状态变更**时广播一次；
 *  - 编排器未装配 ⇒ 可见决策（orchestrator-missing），绝不静默；
 *  - RemoteUpdateAction：manifest jar sha256 / sizeBytes 任一不符 ⇒ Left 拒装
 *    （注入的良性安装命令**不起跑**——在役包零接触）；校验通过 ⇒ 交接环境变量
 *    在场；manifest jar 字段不齐 ⇒ 降级 legacy；minCompatibleEngine 低于 ⇒ 拒装；
 *    交接临时件用后即删。
 *
 * 全程 file:// fixture + 良性注入命令，零真实网络、零真实安装。
 */
class RemoteUpdateActionStagedJarSpec extends CatsEffectSuite:

  override def munitIOTimeout: FiniteDuration = 60.seconds

  private val manifestProp = "nebflow.updates.manifestUrl"
  private val installProp = "nebflow.hotupdate.installCommand"

  private def withProps(p: (String, String)*)(body: IO[?]): IO[?] =
    val saved = p.map((k, _) => k -> Option(System.getProperty(k)))
    IO(p.foreach((k, v) => System.setProperty(k, v))) *>
      body.guarantee(IO {
        saved.foreach {
          case (k, Some(v)) => System.setProperty(k, v)
          case (k, None) => System.clearProperty(k)
        }
      })

  private def writeFile(name: String, content: String): os.Path =
    val f = os.temp(prefix = s"$name-", suffix = ".json")
    os.write.over(f, content)
    f

  private def sha256Of(p: os.Path): String =
    val digest = java.security.MessageDigest.getInstance("SHA-256")
    val in = java.nio.file.Files.newInputStream(p.toNIO)
    try
      val buf = new Array[Byte](8192)
      var n = 0
      while { n = in.read(buf); n != -1 } do digest.update(buf, 0, n)
    finally in.close()
    digest.digest().map("%02x" format _).mkString

  /** 伪 jar（内容任意——校验面只看字节哈希与长度）。 */
  private lazy val fakeJar: os.Path =
    val f = os.temp(prefix = "fake-jar-", suffix = ".jar")
    os.write.over(f, Array.fill[Byte](1024)(7))
    f

  private def manifestFor(
    version: String = "2099.1.1",
    jarUrl: Option[String] = Some(fakeJar.toNIO.toUri.toString),
    sha: Option[String] = Some(sha256Of(fakeJar)),
    size: Option[Long] = Some(os.size(fakeJar)),
    minCompatible: Option[String] = None
  ): String =
    val jarJson = (jarUrl, sha, size) match
      case (Some(u), Some(s), Some(z)) => s""""jar": {"url": "$u", "sha256": "$s", "sizeBytes": $z}"""
      case _ => """"installSh": {"url": "https://x/install.sh", "sha256": null}"""
    val minJson = minCompatible.map(m => s""", "minCompatibleEngine": "$m\"""").getOrElse("")
    s"""{"schema":1,"channel":"stable","version":"$version","build":"deadbeef","artifacts": {$jarJson}$minJson}"""

  // ── 采纳闸（领账）────────────────────────────────────────────────

  /** 收敛组件 + 独立帧收集（编排器帧/收敛帧**分账**——防相位帧污染计数断言）。 */
  private def newConvergence(
    current: String,
    channel: String,
    orchestrator: Option[UpdateOrchestrator] = None
  ): (NeblinkUpdateConvergence, Ref[IO, Int], Ref[IO, Vector[Json]]) =
    val installCalls = Ref.unsafe[IO, Int](0)
    val convFrames = Ref.unsafe[IO, Vector[Json]](Vector.empty)
    val orch = orchestrator.getOrElse(
      new UpdateOrchestrator(
        None, // 无热重启编排器 ⇒ 链在冻结相位即中止（不跑安装），request 受理面真实
        _ => IO.unit,
        currentVersion = () => current,
        latestVersion = () => IO.pure(None),
        install = _ => installCalls.update(_ + 1).as(Right("ok"))
      )
    )
    val conv = new NeblinkUpdateConvergence(
      Some(orch),
      json => convFrames.update(_ :+ json),
      currentVersion = () => current,
      selfChannel = () => channel
    )
    (conv, installCalls, convFrames)

  end newConvergence

  private def decisionOf(conv: NeblinkUpdateConvergence): IO[Option[String]] =
    conv.stateJson.map(_.flatMap(_.hcursor.downField("decision").downField("outcome").as[String].toOption))

  test("通道拒采: target=beta / 本机=stable ⇒ refused，编排器零调用") {
    val (conv, installCalls, frames) = newConvergence("2026.10.20", "stable")
    for
      _ <- conv.onHeartbeatTarget(NeblinkUpdateTarget("2099.1.1", None, "beta", None, None))
      d <- decisionOf(conv)
      calls <- installCalls.get
      n <- frames.get
    yield
      assertEquals(d, Some("refused"))
      assertEquals(calls, 0)
      assertEquals(n.size, 1) // 仅 updateState 一帧（决策可见），无编排器相位帧
  }

  test("通道拒采(反向): target=stable / 本机=beta ⇒ 同样拒采") {
    val (conv, installCalls, _) = newConvergence("1.4.1-beta.58", "beta")
    for
      _ <- conv.onHeartbeatTarget(NeblinkUpdateTarget("2099.1.1", None, "stable", None, None))
      d <- decisionOf(conv)
      calls <- installCalls.get
    yield
      assertEquals(d, Some("refused"))
      assertEquals(calls, 0)
  }

  test("前向-only: 等值 ⇒ insync 不装；更旧 ⇒ insync 不装") {
    val (conv, installCalls, _) = newConvergence("2026.10.20", "stable")
    for
      _ <- conv.onHeartbeatTarget(NeblinkUpdateTarget("2026.10.20", None, "stable", None, None))
      d1 <- decisionOf(conv)
      _ <- conv.onHeartbeatTarget(NeblinkUpdateTarget("2026.9.1", None, "stable", None, None))
      d2 <- decisionOf(conv)
      calls <- installCalls.get
    yield
      assertEquals(d1, Some("insync"))
      assertEquals(d2, Some("insync"))
      assertEquals(calls, 0)
  }

  test("前向-only: 日历数值序判新（2026.9.20 → 2026.10.1 是前向，字符串序会误判）⇒ adopted") {
    val (conv, installCalls, _) = newConvergence("2026.9.20", "stable")
    for
      _ <- conv.onHeartbeatTarget(NeblinkUpdateTarget("2026.10.1", None, "stable", None, None))
      d <- decisionOf(conv)
      calls <- installCalls.get
    yield
      assertEquals(d, Some("adopted"))
      // 冻结即中止（无热重启编排器）⇒ 安装动作不被触达——执行链中止腿的真实行为
      assertEquals(calls, 0)
  }

  test("过闸自动受理: 同 target 重复心跳只在变更时广播一次（幂等/防噪）") {
    val (conv, _, frames) = newConvergence("2026.10.20", "stable")
    for
      _ <- conv.onHeartbeatTarget(NeblinkUpdateTarget("2099.1.1", Some("deadbeef"), "stable", None, None))
      n1 <- frames.get
      _ <- conv.onHeartbeatTarget(NeblinkUpdateTarget("2099.1.1", Some("deadbeef"), "stable", None, None))
      n2 <- frames.get
    yield
      assertEquals(n1.size, 1)
      assertEquals(n2.size, 1) // 第二拍 target 未变 ⇒ 不重推
  }

  test("编排器未装配 ⇒ 决策可见（orchestrator-missing），不静默") {
    val convFrames = Ref.unsafe[IO, Vector[Json]](Vector.empty)
    val conv = new NeblinkUpdateConvergence(
      None,
      json => convFrames.update(_ :+ json),
      currentVersion = () => "2026.10.20",
      selfChannel = () => "stable"
    )
    for
      _ <- conv.onHeartbeatTarget(NeblinkUpdateTarget("2099.1.1", None, "stable", None, None))
      d <- decisionOf(conv)
    yield assertEquals(d, Some("orchestrator-missing"))
  }

  // ── 校验失败拒装（RemoteUpdateAction）────────────────────────────

  test("manifest 可读 + jar 字段齐 + 校验通过 ⇒ 交接环境变量在场，安装命令收到") {
    val marker = os.temp(prefix = "staged-marker-", suffix = ".log")
    os.remove(marker)
    val manifestUrl = writeFile("m-good", manifestFor())
    withProps(
      manifestProp -> manifestUrl.toNIO.toUri.toString,
      installProp -> s"echo staged=$$NEBFLOW_STAGED_JAR sha=$$NEBFLOW_EXPECTED_JAR_SHA256 > $marker"
    ) {
      RemoteUpdateAction.runInstallScript(beta = false).flatMap { result =>
        IO {
          assert(result.isRight, s"expected success, got: $result")
          assert(os.exists(marker), "injected install command never ran")
          val out = os.read(marker)
          assert(out.contains(s"sha=${sha256Of(fakeJar)}"), s"expected sha env, got: $out")
          assert(out.contains("staged=/"), s"expected staged jar path env, got: $out")
        }
      }
    }
  }

  test("sha256 不符 ⇒ Left 拒装，安装命令**不起跑**（在役包零接触）") {
    val marker = os.temp(prefix = "refused-marker-", suffix = ".log")
    os.remove(marker)
    val manifestUrl = writeFile("m-badsha", manifestFor(sha = Some("0" * 64)))
    withProps(manifestProp -> manifestUrl.toNIO.toUri.toString, installProp -> s"touch $marker") {
      RemoteUpdateAction.runInstallScript(beta = false).flatMap { result =>
        IO {
          assert(result.isLeft, s"expected refusal, got: $result")
          assert(!os.exists(marker), "install command must NOT run on verification failure")
          assert(result.left.exists(_.contains("REFUSED")), result)
        }
      }
    }
  }

  test("sizeBytes 不符 ⇒ Left 拒装") {
    val manifestUrl = writeFile("m-badsize", manifestFor(size = Some(os.size(fakeJar) + 1)))
    withProps(manifestProp -> manifestUrl.toNIO.toUri.toString, installProp -> "true") {
      RemoteUpdateAction
        .runInstallScript(beta = false)
        .map(result => assert(result.left.exists(_.contains("size mismatch")), result))
    }
  }

  test("jar 不可达 ⇒ Left 拒装（宣告了却取不到 = 不装）") {
    val manifestUrl = writeFile("m-unreachable", manifestFor(jarUrl = Some("file:///nonexistent/nope.jar")))
    withProps(manifestProp -> manifestUrl.toNIO.toUri.toString, installProp -> "true") {
      RemoteUpdateAction
        .runInstallScript(beta = false)
        .map(result => assert(result.left.exists(_.contains("fetch failed")), result))
    }
  }

  test("manifest jar 字段不齐 ⇒ 降级 legacy 路径（Right，安装命令照跑）") {
    val marker = os.temp(prefix = "legacy-marker-", suffix = ".log")
    os.remove(marker)
    val manifestUrl = writeFile("m-nojar", manifestFor(jarUrl = None, sha = None, size = None))
    withProps(manifestProp -> manifestUrl.toNIO.toUri.toString, installProp -> s"touch $marker") {
      RemoteUpdateAction.runInstallScript(beta = false).flatMap { result =>
        IO {
          assert(result.isRight, s"expected legacy path success, got: $result")
          assert(os.exists(marker), "legacy install command must run")
        }
      }
    }
  }

  test("minCompatibleEngine 低于当前 ⇒ 拒装 + 「需先手动跨版」可见解释") {
    val manifestUrl = writeFile("m-mincompat", manifestFor(minCompatible = Some("2099.1.1")))
    withProps(manifestProp -> manifestUrl.toNIO.toUri.toString, installProp -> "true") {
      RemoteUpdateAction
        .runInstallScript(beta = false)
        .map(result =>
          assert(
            result.left.exists(l => l.contains("minCompatibleEngine") && l.contains("manual cross-version")),
            result
          )
        )
    }
  }

  test("交接临时件用后即删（guarantee 清理，零泄漏）") {
    val tmp = os.Path(java.lang.System.getProperty("java.io.tmpdir"))
    val before = os.walk(tmp).count(_.last.startsWith("stand-staged-jar-"))
    val manifestUrl = writeFile("m-clean", manifestFor())
    withProps(manifestProp -> manifestUrl.toNIO.toUri.toString, installProp -> "true") {
      RemoteUpdateAction.runInstallScript(beta = false).flatMap { _ =>
        IO {
          val after = os.walk(tmp).count(_.last.startsWith("stand-staged-jar-"))
          assertEquals(after, before)
        }
      }
    }
  }
end RemoteUpdateActionStagedJarSpec

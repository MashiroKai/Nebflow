package nebflow.core.hotupdate

import cats.effect.IO
import cats.syntax.all.*
import io.circe.Json
import io.circe.syntax.*
import nebflow.core.AtomicJson
import nebflow.core.hotrestart.*
import nebflow.shared.{NebflowLogger, PathUtil}

/**
 * 指针化回滚（hotupdate 批 2 G4 · 设计 §6②）。
 *
 * **回滚动作**（逐条对齐设计）：① 指针切回上一版（[[InstallPointer.flip]]——承重件：
 * 下一次开机由启动器按指针起上一版）；② 用**既有纯函数命令构造**
 * `HotRestart.buildCommand`（`HotRestart.scala`）**只替换包路径**（`jarOverride` =
 * 上一版的包），把上一版**复起**——复用后继协议（`--succeed <意图位>`）：上一版走既有
 * `succeedPortGate` 的端口让渡等待，故**不存在**「回滚进程与正在退出的坏版抢端口」的
 * 竞态（零新增机制）；③ 回滚后状态恢复**零新增**：复起的进程是普通 boot 链上的后继
 * （崩溃恢复 / 开机重入一律既有件）。
 *
 * **触发点（两条，见调用方读数）**：① 健康自检失败（门口四档，`HotRestart.begin` 的
 * [7.5]）；② 交接后短窗口内的判据（后继绑定后自检，[[nebflow.core.hotrestart.HealthCheck.afterBind]]）。
 *
 * **幂等/一次尝试**：回滚是**一次尝试零重试**（既有口径）——不做重试风暴；回滚也失败时
 * 的最小可用态是安全启动路径（G5，组合既有开关，人工可达）。
 */
final class Rollback(
  /** 安装目录（版本目录 + 指针所在；默认 = 生产安装目录）。 */
  installDir: os.Path = InstallPointer.defaultInstallDir(),
  /** 数据根（意图位/回滚记录落位；与 `SuccessorGate` 同一根）。 */
  dataRoot: os.Path = PathUtil.dataRoot,
  /** 复起派生（与 `HotRestart.defaultSpawn` 同一实现，只把包路径换成上一版）。 */
  spawnPrevious: (HotRestartIntent, String) => IO[Either[String, HotRestart.SpawnedProcess]] = (intent, jar) =>
    HotRestart.defaultSpawnWithJar(intent, Some(jar)),
  host: String = "0.0.0.0",
  port: Int = 8080
):

  private val logger = NebflowLogger.forName("nebflow.hotupdate")

  private def intentFile: os.Path = SuccessorGate.intentPath(dataRoot)
  private def recordFile: os.Path = SuccessorGate.restartDir(dataRoot) / "rollback.json"

  /**
   * 立即回滚：`Right(outcome)` = 指针已切回且上一版已派起；`Left(reason)` = 未回滚
   * （无上一版基座 / 指针不可读 / 派生失败），调用方据此走安全启动路径（G5）。
   */
  def run(reason: String): IO[Either[String, Rollback.Outcome]] =
    InstallPointer
      .readCurrent(installDir)
      .flatMap { current =>
        InstallPointer.readPrevious(installDir).flatMap { previous =>
          (current, previous) match
            case (None, _) =>
              IO.pure(
                Left(
                  s"no current-version pointer under ${installDir.toString} — rollback base absent (pre-G4 install?)"
                )
              )
            case (_, None) =>
              IO.pure(Left(s"no previous-version pointer under ${installDir.toString} — no retained previous version"))
            case (Some(bad), Some(target)) => rollbackTo(bad, target, reason)
        }
      }
      .handleErrorWith(e => IO.pure(Left(s"rollback raised: ${Option(e.getMessage).getOrElse(e.toString)}")))

  /** 指针切换 + 复起上上一版（`bad` = 当前指针指向的坏版，`target` = 回滚目标）。 */
  private def rollbackTo(bad: String, target: String, reason: String): IO[Either[String, Rollback.Outcome]] =
    InstallPointer.jarIn(installDir, target).flatMap {
      case Left(err) => IO.pure(Left(err))
      case Right(jar) =>
        val now = System.currentTimeMillis()
        val form = HotRestart.detectForm
        val cmdPreview =
          HotRestart.buildCommand(
            form,
            nebflow.core.RestartHelper.resolveJavaBin(),
            Some(jar),
            intentFile.toString,
            dataRoot.toString,
            port
          ) match
            case Right(cmd) => cmd.mkString(" ")
            case Left(msg) => msg
        val intent = HotRestartIntent(
          generation = now,
          oldPid = ProcessHandle.current.pid,
          host = host,
          port = port,
          home = dataRoot.toString,
          form = form,
          spawnCmd = cmdPreview,
          triggerSource = s"rollback:${reason.take(120)}",
          phase = "spawned",
          ts = now
        )
        for
          _ <- SuccessorGate.writeIntent(intentFile, intent)
          flipped <- InstallPointer.flip(installDir)
          _ <- flipped match
            case Left(err) => logger.error(s"[hotupdate] ROLLBACK pointer flip FAILED: $err")
            case Right((to, from)) =>
              logger.warn(
                s"[hotupdate] ROLLBACK: current-version $from -> $to (bad version out of the selection face; previous version retained on disk)"
              )
          spawned <- spawnPrevious(intent, jar)
          outcome = Rollback.Outcome(
            fromVersion = bad,
            toVersion = target,
            jar = jar,
            spawnedPid = spawned.toOption.map(_.pid),
            reason = reason,
            ts = now
          )
          _ <- AtomicJson.write(recordFile, outcome.asJson.noSpaces)
          _ <- spawned match
            case Left(err) =>
              logger.error(
                s"[hotupdate] ROLLBACK relaunch failed: $err — pointers already flipped, the next boot starts $target (existing boot chain)"
              )
            case Right(p) =>
              logger.warn(s"[hotupdate] ROLLBACK relaunch: previous version $target spawning (pid ${p.pid}, jar $jar)")
        yield spawned match
          case Right(_) => Right(outcome)
          case Left(e) => Left(s"rollback relaunch failed (pointers already flipped to $target): $e")
        end for
    }

  /** 回滚记录（诊断面：`<dataRoot>/restart/rollback.json`）。 */
  def readRecord: IO[Option[Json]] =
    IO.blocking(if os.exists(recordFile) then Some(os.read(recordFile)) else None)
      .flatMap {
        case None => IO.pure(None)
        case Some(raw) => IO.pure(io.circe.parser.parse(raw).toOption)
      }
      .handleErrorWith(_ => IO.pure(None))

end Rollback

object Rollback:

  /** 回滚结果（盘上记录 + 帧内诊断明细；字段名即契约）。 */
  final case class Outcome(
    fromVersion: String,
    toVersion: String,
    jar: String,
    spawnedPid: Option[Long],
    reason: String,
    ts: Long
  )

  object Outcome:
    given io.circe.Encoder[Outcome] = io.circe.Encoder.derived

end Rollback

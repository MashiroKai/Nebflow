package nebflow.core.tools

import cats.effect.IO
import io.circe.JsonObject
import io.circe.syntax.*
import munit.CatsEffectSuite

import scala.concurrent.duration.*

/**
 * bash overhaul T7 红相（作者令 2026-11-04「组织优化 bash，用业界成熟做法完整
 * 实现，一步到位」）：**设备腿（remote-exec）前台长命令必须与本地腿对齐**——
 * 到预算转后台并回 `[Background job started]`，而不是 30s 硬超时报错。
 *
 * 事实源：`docs/20261104_110500_our-bash-tool-defects.md` 缺陷 D9 ——
 * `BashTool.scala:691-709`：设备腿（`ctx.isRemoteExec`）**同步等 HTTP 返回**，
 * 超时 = `explicitTimeoutMs.getOrElse(DEFAULT_TIMEOUT = 30_000ms)`
 * （`BashTool.scala:18`），到点 `Left(ToolError("[Command timed out after Nms]"))`
 * ——即 `isError = true`，还会喂 S1 同败 streak（8 次 → L1）。本地腿同一时刻早已
 * 自动转后台（`:791-827`），两条腿语义分叉。
 *
 * 机械表达方式（诚实说明）：真正的设备腿 = 两个实例（host `RemoteExecutor`
 * → peer `NeblinkRoutes` / `NeblinkRelayTunnel` 的
 * `ToolContext(isRemoteExec = true)`）。缺陷代码就在 peer 侧这一半
 * （`BashTool.scala:691-709`），故本 spec 直接以 `isRemoteExec = true` 构造生产同形
 * 上下文，并用 `timeout` 入参压前台等待（显式入参即等待上限，2026-11-10 起
 * 不再经 `ctx.bashConfig.foregroundWaitBudgetMs` min 钳制；`BashForegroundBudgetSpec`
 * 同款手法，零全局配置污染）。host 侧的 HTTP 分发/等待上限分别由
 * `DeviceBgTaskSpec` / `DeviceChannelListSkewSpec` 钉，本 spec 不重复造远端 harness。
 *
 * 验收面：
 *   1. 回执是 `[Background job started]`（不是 `[Command timed out after …]`）；
 *   2. 返回 `Right`（`isError = false`）；
 *   3. 任务行进 `BgTaskRegistry`（统一账本行存在）。
 */
class DeviceBashAutoBackgroundSpec extends CatsEffectSuite:

  override def munitIOTimeout: Duration = 120.seconds

  private def newSessionId(): String = s"device-bg-red-${java.util.UUID.randomUUID().toString.take(8)}"

  private def jobIdOf(receipt: String): Option[String] =
    """Job ID: (\w+)""".r.findFirstMatchIn(receipt).map(_.group(1))

  test("T7: remote-exec foreground leg auto-backgrounds at the compressed budget (not a 30s-class timed-out error)") {
    val sid = newSessionId()
    val input = JsonObject(
      "command" -> "sleep 12 && echo device-bg-done".asJson,
      "timeout" -> 2000.asJson, // 压缩后的前台预算（生产面同一钳制面）
      "description" -> "device-fg-budget".asJson
    )
    // peer 侧上下文：无 agentActorRef（远端没有本机 actor）、isRemoteExec = true。
    val ctx = ToolContext(projectRoot = "/tmp", sessionId = Some(sid), isRemoteExec = true)
    (for
      result <- BashTool.call(input, ctx)
      out <- IO {
        result match
          case Right(o) => o
          case Left(e) =>
            fail(
              s"device/remote foreground leg must auto-background at the budget, got the timed-out error path: ${e.message.take(300)}"
            )
      }
      _ <- IO {
        assert(
          out.contains("[Background job started]"),
          s"device leg receipt must be the background receipt, got: ${out.take(400)}"
        )
        assert(
          !out.contains("[Command timed out after"),
          s"device leg must NOT return the 30s-class timed-out error: ${out.take(400)}"
        )
      }
      jobId <- IO(jobIdOf(out).getOrElse(fail(s"receipt must carry a job id: ${out.take(400)}")))
      row <- BgTaskRegistry.find(jobId)
      _ <- IO(
        assert(row.isDefined, s"the auto-backgrounded device job must be tracked in BgTaskRegistry (jobId=$jobId)")
      )
      // 清场：经工具面取消（杀进程树）+ 取走账本行 + 销毁会话（防后台残留）。
      _ <- BashTool
        .call(JsonObject("background_job_id" -> jobId.asJson, "cancel_background_job" -> true.asJson), ctx)
        .attempt
      _ <- BgTaskRegistry.unregister(jobId).attempt.void
      _ <- ShellSession.destroySession(sid).attempt.void
    yield ())
  }

end DeviceBashAutoBackgroundSpec

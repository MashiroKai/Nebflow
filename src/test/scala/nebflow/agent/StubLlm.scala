/* Phase 3 去重(行为保持重构,2026-09-25)。 */
package nebflow.agent

import cats.effect.IO
import fs2.Stream
import nebflow.shared.{FallbackAttempt, LlmHandle, LlmRequest, LlmResponse, StreamChunk}

/**
 * 最小 echo 桩 LLM——原 8 份逐字相同的 `private class StubLlm` 的唯一公共实现
 * (AbandonDetachSpec / CancelDeadlockFixSpec / CancelLoopTargetNoMarkerSpec /
 * ChainCancelSpec / ChainCascadeSpec / MergeDesignGapSpec / VerifierRouteGuardSpec /
 * WatchdogSelfMonitorSpec)。
 *
 * 语义(与被替换的 8 份逐字等价,零参数零状态):
 *  - `send`:恒抛 `RuntimeException("send not expected")`(这些 spec 只走流式腿);
 *  - `sendStream`:忽略 `req`/`onAttempt`,固定回 `TextDelta("ok")` + `Done(None, None)`。
 *
 * 不并入的站点(语义真不同,留原地):
 *  - NodeReportReminderSpec / NoderptVerifyProbeSpec 的同名行为桩(turn 计数、
 *    node_report 注册副作用、绑定各自 suite 的 tempRoot);
 *  - 异名同职桩 BgStubLlm ×3 / FailReportLlm ×2。
 */
class StubLlm:

  def handle: LlmHandle[IO] = new LlmHandle[IO]:
    def send(req: LlmRequest): IO[LlmResponse] = IO.raiseError(new RuntimeException("send not expected"))
    def sendStream(
      req: LlmRequest,
      onAttempt: Option[FallbackAttempt => IO[Unit]] = None
    ): Stream[IO, StreamChunk] =
      Stream(StreamChunk.TextDelta("ok"), StreamChunk.Done(None, None))

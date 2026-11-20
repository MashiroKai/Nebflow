package nebflow.shared

import cats.effect.IO

import scala.concurrent.duration.FiniteDuration

/**
 * Periodic driver for [[EvidenceTemp.sweep]].
 *
 * The startup sweep in `GatewayMain` runs once per boot; a long-lived gateway
 * would otherwise never enforce the evidence-temp TTL again until restart.
 * Failures warn and never break the loop (same shape as
 * `ProcessRecorder.launchOrphanSweep`).
 */
object EvidenceTempSweep:
  private val logger = NebflowLogger.forName("nebflow.shared.evidence-temp")

  /** First sweep fires after one full `interval`; the returned IO is meant to be `.start`ed by the caller. */
  def launch(interval: FiniteDuration): IO[Unit] =
    def loop: IO[Unit] =
      IO.sleep(interval) *>
        IO.blocking(EvidenceTemp.sweep())
          .flatMap(removed =>
            if removed > 0 then logger.info(s"[evidence-temp] periodic sweep removed $removed expired evidence batches")
            else IO.unit
          )
          .handleErrorWith(e => logger.warn(s"[evidence-temp] periodic sweep failed: ${e.getMessage}")) *>
        IO.defer(loop)
    loop
end EvidenceTempSweep

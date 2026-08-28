package nebflow.cli

import cats.effect.IO
import io.circe.syntax.given
import nebflow.core.AutoStartService

object AutoStartCommand extends CliCommand:
  def name = "autostart"
  def description = "Enable or disable auto-start on boot"
  def subcommands = List(AutoStartEnable, AutoStartDisable, AutoStartStatus)
  def examples = List("nebflow autostart enable", "nebflow autostart disable", "nebflow autostart status")

  // Core logic lives in core/AutoStartService (shared with the gateway
  // settings-panel WS toggle, F2). The CLI is a thin shell over it.

  private object AutoStartEnable extends CliSubcommand:
    def name = "enable"
    def description = "Enable auto-start on boot"
    def params = Nil

    def run(ctx: CliContext): IO[CliResult] =
      AutoStartService.enable().map(toCliResult)

  private object AutoStartDisable extends CliSubcommand:
    def name = "disable"
    def description = "Disable auto-start on boot"
    def params = Nil

    def run(ctx: CliContext): IO[CliResult] =
      AutoStartService.disable().map(toCliResult)

  private object AutoStartStatus extends CliSubcommand:
    def name = "status"
    def description = "Check if auto-start is enabled"
    def params = Nil

    def run(ctx: CliContext): IO[CliResult] =
      AutoStartService.status().map { st =>
        if ctx.json then
          CliResult.Json(
            io.circe.Json.obj(
              "enabled" -> st.enabled.asJson,
              "supported" -> st.supported.asJson,
              "reason" -> st.reason.asJson
            )
          )
        else if !st.supported then
          CliResult.text(
            "Auto-start: unavailable",
            st.reason.getOrElse("")
          )
        else CliResult.text(if st.enabled then "Auto-start: enabled" else "Auto-start: disabled")
      }

  private def toCliResult(res: AutoStartService.OpResult): CliResult =
    if res.ok then CliResult.text((res.message :: res.detail)*)
    else CliResult.Error(res.message)

end AutoStartCommand

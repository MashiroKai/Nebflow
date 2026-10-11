package nebflow.cli

import cats.effect.IO
import io.circe.Json
import io.circe.syntax.*

/**
 * D6 binding-transfer guard, host face (phase 3c plan §8 component ①):
 *
 *  - `nebflow bind confirm` — run on the OLD host while it is still online:
 *    the gateway mints a 6-digit confirmation code and reports it up the
 *    relay control plane; the code is printed here exactly once for the
 *    operator to type into the portal during the re-bind. Re-running the
 *    command mints a fresh code and invalidates the previous one (newest
 *    report wins on both ends).
 *
 * Honest-delivery boundary (the copy's load-bearing rule): a successful run
 * means the report frame was handed to a LIVE RELAY SOCKET — nothing on
 * this face observes whether the server staged the code. Portal acceptance
 * additionally depends on the server's confirmation-staging face, which the
 * portal wire contract (v1.2 §3.3) defers to the phase-5 freeze; on any
 * server without that face the printed code can never match. The `pending`
 * copy therefore names that dependency and the mismatch fallback explicitly
 * instead of promising portal acceptance.
 *
 * When the old host has no live relay tunnel, no code is minted: the
 * confirm-code path requires the old host online (plan §8.1①) and the
 * operator is routed to the portal directly — once the old host has been
 * offline beyond the server-side grace period (default 72h) the transfer
 * needs no code at all.
 *
 * The code is the ONLY secret this command ever displays (zero-echo
 * otherwise): the file token, session material and server responses are
 * never echoed. Gateway-required by design — the minting object is the live
 * gateway and its relay tunnel, exactly like `token print`.
 */
object BindCommand extends CliCommand:
  def name = "bind"
  def description = "Host-seat binding transfer: mint the confirmation code (D6 guard)"
  def subcommands = List(BindConfirm)
  def examples = List("nebflow bind confirm")

/** `bind confirm` — mint + report the 6-digit transfer confirmation code (online). */
private[cli] object BindConfirm extends CliSubcommand:
  def name = "confirm"
  def description =
    "Mint a 6-digit binding-transfer confirmation code on this (old) host; enter it in the portal to re-bind"
  def params = Nil

  def run(ctx: CliContext): IO[CliResult] =
    ctx.client match
      case None => IO.pure(CliResult.error("Gateway not running — the confirm-code path needs this host online"))
      case Some(client) =>
        client.post("/api/neblink/bind/confirm", Json.obj()).map(render)

  /**
   * Pure: gateway JSON → CLI result. The three `kind` branches mirror the
   * gateway face one-for-one; a missing/unknown kind is an error, never a
   * guessed success.
   */
  private[cli] def render(json: Json): CliResult =
    val kind = json.hcursor.downField("kind").as[String].toOption.getOrElse("")
    kind match
      case "pending" =>
        val code = json.hcursor.downField("code").as[String].toOption.getOrElse("")
        val expiresInSec = json.hcursor.downField("expiresInSec").as[Long].toOption.getOrElse(0L)
        if code.isEmpty then CliResult.error("Gateway returned no confirmation code")
        else if code.length != 6 || !code.forall(_.isDigit) then
          CliResult.error("Gateway returned a malformed confirmation code (expected 6 digits)")
        else
          CliResult.Text(
            List(
              "Binding-transfer confirmation code:",
              code,
              s"(valid for ${expiresInSec}s; running this command again invalidates it and mints a new one.)",
              "Enter this code in the NebLink portal when re-binding the host seat.",
              "",
              "Handed to the NebLink server over the relay. Portal acceptance also requires the",
              "server's confirmation-staging face (phase-5 contract item): if the portal reports",
              "a mismatch although every digit was typed correctly, that face is not deployed",
              "yet and no code can be accepted — retry after the server upgrade, or use the",
              "portal's grace-period path (old host offline beyond the grace window, default 72h)."
            )
          )
      case "offline" =>
        CliResult.Text(
          List(
            "No live relay connection to the NebLink server — no code was minted.",
            "The confirmation-code path requires the OLD host to be online.",
            "Do the transfer in the NebLink portal directly: if this host has been offline",
            "beyond the grace period (default 72h), the portal lets you re-bind without a code."
          )
        )
      case "send_failed" =>
        val reason = json.hcursor.downField("reason").as[String].toOption.getOrElse("").trim
        val detail = if reason.isEmpty then "" else s" ($reason)"
        CliResult.error(s"Failed to report the code to the server — nothing became pending; try again.$detail")
      case other =>
        CliResult.error(s"Unexpected gateway response (kind=$other)")

end BindConfirm

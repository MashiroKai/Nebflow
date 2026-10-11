package nebflow.cli

import cats.effect.IO
import cats.syntax.all.*
import io.circe.Json
import io.circe.syntax.*

/**
 * Phase 3b console-credential commands (plan §6.4):
 *
 *  - `nebflow token` / `nebflow token print` — break-glass: mint a ONE-TIME
 *    console ticket via the AuthAdmin face (`POST /api/auth/console-ticket`)
 *    and print it to the terminal exactly once. The ticket is the ONLY
 *    payload — the long-lived file token is never echoed (v3 :165).
 *  - `nebflow token rotate` — dual-form by design (plan §2.6/T-8-5): when the
 *    gateway is reachable, rotate through `POST /api/auth/token/rotate`
 *    (file rewrite + in-memory swap; the old value dies on the RUNNING
 *    gateway); when it is not, rewrite auth.json locally with the SAME
 *    atomic write the gateway uses — leak containment must not depend on the
 *    gateway being alive. The new token value is NEVER printed.
 *  - `nebflow sessions revoke` — revoke console sessions (`--all`, or
 *    `--id <masked-id prefix>`), via `POST /api/auth/sessions/revoke`.
 *    "Console sessions" (plural command name) is deliberately distinct from
 *    the chat `session` command (singular) and the `/api/sessions` chat
 *    domain.
 *
 * All three faces authenticate with the file-token Bearer header
 * GatewayClient already carries on every request — the AuthAdmin face accepts
 * ONLY that rail, so the CLI needs no changes.
 */
object TokenCommand extends CliCommand:
  def name = "token"
  def description = "Break-glass console ticket and gateway token rotation"
  def subcommands = List(TokenPrint, TokenRotate)
  def examples = List("nebflow token", "nebflow token rotate", "nebflow sessions revoke --all")
  // Bare `nebflow token` mints a ticket — print is the default subcommand.
  override def defaultSubcommand = Some("print")

/** `token print` — mint a one-time break-glass console ticket (online; a live store is the minting object). */
private[cli] object TokenPrint extends CliSubcommand:
  def name = "print"
  def description = "Mint a one-time console ticket (break-glass entry; printed once, never logged)"
  def params = Nil

  def run(ctx: CliContext): IO[CliResult] =
    ctx.client match
      case None => IO.pure(CliResult.error("Gateway not running — a console ticket needs the live gateway"))
      case Some(client) =>
        client.post("/api/auth/console-ticket", Json.obj()).flatMap { json =>
          val ticket = json.hcursor.downField("ticket").as[String].toOption.getOrElse("")
          val expiresIn = json.hcursor.downField("expiresInSec").as[Long].toOption.getOrElse(0L)
          if ticket.isEmpty then IO.pure(CliResult.error("Gateway returned no ticket"))
          else
            IO.pure(
              CliResult.Text(
                List(
                  "One-time console ticket (paste it into the sign-in card, or open /?ticket=<ticket>):",
                  ticket,
                  s"(valid for ${expiresIn}s; single use — it is destroyed on first redemption)"
                )
              )
            )
        }

/** `token rotate` — dual-form rotation (gateway face when reachable, local atomic rewrite otherwise). */
private[cli] object TokenRotate extends CliSubcommand:
  def name = "rotate"
  def description = "Rotate the gateway token (online: takes effect immediately; offline: rewrites auth.json for the next start)"
  def params = Nil

  def run(ctx: CliContext): IO[CliResult] =
    GatewayClient.create.flatMap {
      case Some(client) =>
        client.post("/api/auth/token/rotate", Json.obj()).flatMap { _ =>
          // The gateway rewrote auth.json (same data root) atomically; the new
          // value is picked up by reading the file — never echoed over the wire.
          readAuthJson.flatMap {
            case Some(_) =>
              IO.pure(
                CliResult.Text(
                  List(
                    "Token rotated via the gateway: auth.json rewritten and the old value is already",
                    "invalid on the running gateway (live sessions are unaffected)."
                  )
                )
              )
            case None => IO.pure(CliResult.error("Rotation acknowledged but auth.json could not be re-read"))
          }
        }
      case None =>
        // Offline form: leak containment without a live gateway. Same atomic
        // write the gateway face uses (tmp + chmod-before-rename + ATOMIC_MOVE).
        readAuthJson.flatMap {
          case None => IO.pure(CliResult.error("No gateway token found (auth.json missing); nothing to rotate"))
          case Some(current) =>
            nebflow.gateway.Auth.generateToken.flatMap { next =>
              nebflow.gateway.Auth.writeTokenAtomic(next).as(
                CliResult.Text(
                  List(
                    "Token rotated offline: auth.json rewritten atomically.",
                    "The old value keeps working on a gateway that is already running; the new value",
                    "takes effect at the next gateway start (old value dies then)."
                  )
                )
              )
            }
        }
    }

  private def readAuthJson: IO[Option[String]] = GatewayClient.readToken

end TokenRotate

/**
 * `sessions revoke` — console-session revocation (online; a live store is the
 * only revocable object). `--all` or `--id <masked-id prefix>`.
 */
object SessionsCommand extends CliCommand:
  def name = "sessions"
  def description = "Console sessions (browser sign-ins): revoke them; distinct from the chat `session` command"
  def subcommands = List(SessionsRevoke)
  def examples = List("nebflow sessions revoke --all", "nebflow sessions revoke --id 1a2b3c4d")

private[cli] object SessionsRevoke extends CliSubcommand:
  def name = "revoke"
  def description = "Revoke console sessions: all (--all) or by masked-id prefix (--id)"
  def params = List(
    CliParam("all", description = "Revoke ALL console sessions", isFlag = true),
    CliParam("id", description = "Revoke sessions whose masked id starts with this prefix (see `GET /api/auth/sessions`)")
  )

  def run(ctx: CliContext): IO[CliResult] =
    ctx.client match
      case None => IO.pure(CliResult.error("Gateway not running — a live store is the only revocable object"))
      case Some(client) =>
        revokeBody(ctx.args.get("all").contains("true"), ctx.args.get("id").map(_.trim).filter(_.nonEmpty)) match
          case Left(err) => IO.pure(CliResult.error(err))
          case Right(body) =>
            client.post("/api/auth/sessions/revoke", body).flatMap { json =>
              val n = json.hcursor.downField("revoked").as[Int].toOption.getOrElse(0)
              IO.pure(CliResult.text(s"Revoked $n console session(s)"))
            }

  /**
   * Pure: the request body for a revoke invocation (`Left` = usage error).
   * Exactly one of `--all` / `--id` — both given is refused (ambiguous), so
   * the operator can never be unsure which scope ran.
   */
  private[cli] def revokeBody(all: Boolean, idPrefix: Option[String]): Either[String, Json] =
    (all, idPrefix) match
      case (true, Some(_)) => Left("Pass either --all or --id, not both")
      case (false, None)   => Left("Nothing to revoke: pass --all or --id <prefix>")
      case (true, None)    => Right(Json.obj("all" -> true.asJson))
      case (false, Some(p)) =>
        if p.length < 4 then Left("Masked-id prefix too short (minimum 4 characters)")
        else Right(Json.obj("id" -> p.asJson))

end SessionsRevoke

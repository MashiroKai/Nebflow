package nebflow.core

import cats.effect.IO
import io.circe.{Json, JsonObject}
import nebflow.core.tools.RemoteExecutor
import nebflow.shared.NebflowLogger
import scala.concurrent.duration.*

/** Fail-closed delivery check. The checkout and checker are inspected on the same device. */
object ManifestGate:
  private val logger = NebflowLogger.forName("nebflow.manifest.gate")
  private val pass = "__MANIFEST_GATE_PASS__"
  private val skip = "__MANIFEST_GATE_SKIP__"
  private def quote(s: String): String = "'" + s.replace("'", "'\\''") + "'"

  def checkDelegate(id: String): IO[Either[String, String]] =
    nebflow.core.delegate.DelegateRegistry.find(id) match
      case None => IO.pure(Left(s"manifest-missing: delegate $id workspace unavailable; cannot determine checkout type"))
      case Some(rec) =>
        rec.project match
          case None => check(rec.cwd, None)
          case Some(name) => nebflow.core.project.ProjectStore.load(name).flatMap {
            case None => IO.pure(Left(s"manifest-missing: project $name unavailable; cannot determine device or workspace"))
            case Some(project) => nebflow.core.project.ProjectStore.deviceOrigin(project) match
              case Some(origin) =>
                if rec.worktree.nonEmpty then IO.pure(Left(s"manifest-missing: remote worktree path unavailable on ${origin.device}; cannot verify MANIFEST"))
                else check(origin.path, Some(origin.device))
              case None => check(rec.cwd, None)
          }

  def check(path: String, device: Option[String]): IO[Either[String, String]] =
    val command = s"""cd -- ${quote(path)} || exit 31
root=$$(git rev-parse --show-toplevel) || exit 32
gitdir=$$(git rev-parse --path-format=absolute --git-dir) || exit 33
common=$$(git rev-parse --path-format=absolute --git-common-dir) || exit 34
if [ "$$gitdir" = "$$common" ]; then printf '%s\\n' $skip; exit 0; fi
if [ ! -f "$$root/scripts/check-delivery.py" ]; then printf '%s\\n' "missing checker: $$root/scripts/check-delivery.py"; exit 35; fi
python3 -B "$$root/scripts/check-delivery.py" --manifest-only --root "$$root" || exit 36
printf '%s\\n' $pass"""
    val run: IO[Either[String, String]] = device match
      case Some(d) => RemoteExecutor.current match
        case None => IO.pure(Left(s"device channel unavailable: $d"))
        case Some(executor) => executor.execute(d, "Bash", JsonObject("command" -> Json.fromString(command), "timeout" -> Json.fromInt(30000)))
          .map(_.left.map(_.toString))
      case None => IO.blocking {
        val p = new ProcessBuilder("bash", "-c", command).redirectErrorStream(true).start()
        val output = new String(p.getInputStream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)
        val code = p.waitFor()
        if code == 0 then Right(output) else Left(output + s" (exit $code)")
      }
    run.timeout(40.seconds).attempt.flatMap { attempted =>
      val result = attempted.left.map(e => Option(e.getMessage).getOrElse(e.toString)).flatMap(identity)
      result match
        case Right(out) if out.linesIterator.exists(_ == skip) =>
          logger.info(s"Manifest gate SKIP primary checkout: $path") *> IO.pure(Right("SKIP primary checkout"))
        case Right(out) if out.linesIterator.exists(_ == pass) => IO.pure(Right(out))
        case other =>
          val detail = other.fold(identity, identity).trim
          IO.pure(Left(s"manifest-missing: delivery rejected for workspace $path${device.fold("")(d => s" on device $d")}. Missing or invalid MANIFEST, checker unavailable, or checkout type unknown. Check .stand/tmp/<worktree>/MANIFEST.md in the primary checkout and run python3 -B scripts/stand-wt.py audit <worktree>. Details: $detail"))
    }

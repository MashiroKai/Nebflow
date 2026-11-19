package nebflow.core.tools

/**
 * Launchd-context probe (envfix batch 2026-09-21). Runs in a child JVM with a
 * minimal service-context PATH (launchd: /usr/bin:/bin:/usr/sbin:/sbin) and an
 * isolated -Duser.home, and prints what RgHelper resolves outside a login
 * shell: one RESOLVED=<path> line, or RESOLVED=NONE plus the verbatim
 * ToolError message a user would see. Deliberately uses only the pre-existing
 * public surface (resolvedPath / runRg) so it compiles against both the
 * pre-fix and post-fix RgHelper — the same probe gives the red and the green
 * runtime readings.
 */
object RgHelperLaunchdProbe:

  def main(args: Array[String]): Unit =
    RgHelper.resolvedPath match
      case Some(p) => println(s"RESOLVED=$p")
      case None =>
        println("RESOLVED=NONE")
        RgHelper.runRg(List("--version"), ".") match
          case Left(err) => println(s"ERROR=${err.message}")
          case Right(_) => println("ERROR=none-but-runRg-succeeded")

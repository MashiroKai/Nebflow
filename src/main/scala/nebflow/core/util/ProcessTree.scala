package nebflow.core.util

import cats.effect.IO
import scala.jdk.StreamConverters.*
import java.util.concurrent.TimeUnit

object ProcessTree:
  /**
   * Kill a process and all its descendants: SIGTERM children -> SIGTERM parent
   * -> wait 5s -> SIGKILL children -> SIGKILL parent.
   * Snapshot descendants BEFORE destroying anything (dead parent's children
   * get reparented and vanish from the live view).
   * Logic adapted from DaemonService.killProcessTree (9118cf73).
   */
  def killProcessTree(p: Process): IO[Unit] =
    IO.blocking {
      try
        val descendants = p.descendants().toScala(List)
        descendants.foreach(_.destroy())   // SIGTERM children
        p.destroy()                        // SIGTERM parent
        if !p.waitFor(5, TimeUnit.SECONDS) then
          descendants.foreach(_.destroyForcibly())  // SIGKILL children
          p.destroyForcibly()                        // SIGKILL parent
      catch case _: Exception => ()
    }

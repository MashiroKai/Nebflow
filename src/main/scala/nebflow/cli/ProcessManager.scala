package nebflow.cli

import cats.effect.IO

object ProcessManager:
  private val PidFile: os.Path = os.home / ".nebflow" / ".pid"

  def readPid(): Option[Long] =
    if os.exists(PidFile) then
      try Some(os.read(PidFile).trim.toLong)
      catch case _: Exception => None
    else None

  def writePid(pid: Long): Unit =
    os.write.over(PidFile, pid.toString, createFolders = true)

  def removePid(): Unit =
    if os.exists(PidFile) then os.remove(PidFile)

  def isRunning(pid: Long): Boolean =
    java.lang.ProcessHandle.of(pid).map(_.isAlive).orElse(false)

  def stop(): IO[Unit] = IO.blocking {
    readPid() match
      case None =>
        println("nebflow is not running")
      case Some(pid) if !isRunning(pid) =>
        println("nebflow is not running (removing stale pid file)")
        removePid()
      case Some(pid) =>
        println(s"Stopping nebflow (pid: $pid)...")
        val handleOpt = java.lang.ProcessHandle.of(pid)
        handleOpt.ifPresent(_.destroy())
        var waited = 0
        while isRunning(pid) && waited < 50 do
          Thread.sleep(100)
          waited += 1
        if isRunning(pid) then
          println("Force killing...")
          handleOpt.ifPresent(_.destroyForcibly())
          while isRunning(pid) && waited < 100 do
            Thread.sleep(100)
            waited += 1
        removePid()
        if isRunning(pid) then
          println("Failed to stop nebflow")
        else
          println("nebflow stopped")
  }
end ProcessManager

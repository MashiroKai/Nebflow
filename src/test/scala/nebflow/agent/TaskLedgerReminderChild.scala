package nebflow.agent

import java.nio.file.{Files, Path}

/** OS child used only by TaskLedgerPassiveReminderSpec cross-process locking checks. */
object TaskLedgerReminderChild:

  def main(args: Array[String]): Unit =
    val Array(dataFile, session, barrier, resultFile, readyFile) = args
    val gate = Path.of(barrier)
    Files.createFile(Path.of(readyFile))
    val deadline = System.currentTimeMillis() + 30_000L
    while !Files.exists(gate) && System.currentTimeMillis() < deadline do Thread.`yield`()
    if !Files.exists(gate) then throw new IllegalStateException("synchronization barrier timed out")
    val file = os.Path(dataFile)
    if session == "abnormal-halt" then
      TaskLedgerPassiveReminder.dueAtWithReplace(
        file,
        session,
        123456L,
        "abrupt exit",
        (_, _) => (),
        (_, _) => Runtime.getRuntime.halt(17)
      )
    else
      val result = TaskLedgerPassiveReminder.dueAt(file, session, 123456L, s"summary-$session", (_, _) => ()).isDefined
      Files.writeString(Path.of(resultFile), if result then "DELIVERED" else "SUPPRESSED")
  end main
end TaskLedgerReminderChild

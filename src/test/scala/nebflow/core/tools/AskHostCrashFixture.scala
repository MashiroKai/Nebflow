package nebflow.core.tools

import cats.effect.unsafe.implicits.global
import io.circe.JsonObject
import nebflow.actor.AgentCommand
import nebflow.core.ResumeHandoff
import nebflow.core.mail.SessionMailbox
import nebflow.shared.{ContentBlock, Message, MessageRole, PathUtil}

/** Isolated ledger/mailbox subprocess fixture: no gateway, network or production data. */
object AskHostCrashFixture:

  def main(args: Array[String]): Unit =
    val root = os.Path(args(0))
    PathUtil.setDataRoot(root)
    val worker = "crash-worker"
    val call = "original-call"
    if args(1) == "seed" then
      val (row, _) = AskHostLedger.beginForCall(worker, "root", "test", "question", call).unsafeRunSync()
      val event =
        AgentCommand.ExternalEvent("askhost", "request", "question", JsonObject.empty, Some(row.correlationId))
      SessionMailbox
        .mailOnce("root", event, "askhost-request", row.correlationId, deduplicateReceipts = true)
        .unsafeRunSync()
      println(s"READY pid=${ProcessHandle.current().pid()} correlation=${row.correlationId}")
      System.out.flush()
      new java.util.concurrent.CountDownLatch(1).await()
    else
      val row = AskHostLedger.recover.unsafeRunSync().head
      val (same, _) = AskHostLedger.beginForCall(worker, "root", "test", "question", call).unsafeRunSync()
      require(row.pending.correlationId == same.correlationId)
      val event =
        AgentCommand.ExternalEvent("askhost", "request", "question", JsonObject.empty, Some(same.correlationId))
      val replay = SessionMailbox
        .mailOnce("root", event, "askhost-request", same.correlationId, deduplicateReceipts = true)
        .unsafeRunSync()
      require(replay == SessionMailbox.MailResult.AlreadyPresent)
      val transcript = List(
        Message(MessageRole.Assistant, Right(List(ContentBlock.ToolUse(call, "AskHost", JsonObject.empty))))
      )
      val plan = ResumeHandoff.plan(transcript, ResumeHandoff.Cause.EngineRestart)
      require(AskHostLedger.answer(same.correlationId, "approved").unsafeRunSync())
      val resumed = ResumeHandoff.resolveAskHost(worker, plan).unsafeRunSync()
      val blocks = resumed.injectBlocks.get
      require(blocks.exists {
        case r: ContentBlock.ToolResult => r.toolUseId == call && r.content == "approved"; case _ => false
      })
      AskHostLedger.closeCommittedResults(worker, List(Message(MessageRole.User, Right(blocks)))).unsafeRunSync()
      val duplicate = AskHostLedger.answer(same.correlationId, "approved").unsafeRunSync()
      println(
        s"RECOVERED correlation=${same.correlationId} replay=$replay original-call=approved terminal=${AskHostLedger.recover.unsafeRunSync().isEmpty} duplicateAccepted=$duplicate"
      )
      require(!duplicate)
    end if
  end main
end AskHostCrashFixture

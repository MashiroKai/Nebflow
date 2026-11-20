package nebflow.core.executor

import cats.effect.{IO, Ref, Resource}
import cats.effect.std.Dispatcher
import cats.syntax.all.*
import munit.CatsEffectSuite
import nebflow.actor.ActorSystem
import nebflow.agent.{AgentLibrary, SharedResources, SubAgentTaskStore}
import nebflow.core.{FileChangeTracker, RateLimiter, SessionStore}
import nebflow.core.delegate.{ChainStore, DelegateRegistry}
import nebflow.core.mail.SessionMailbox
import nebflow.core.task.FileTaskStore
import nebflow.core.tools.FileLockManager
import nebflow.db.DbHost
import nebflow.llm.ModelCandidate
import nebflow.shared.{MessageRole, PathUtil, ThinkingConfig}

import scala.concurrent.duration.*

/** External execution uses the supplied workspace and prompt without allocating a git seat. */
class ExternalLegPrepareSpec extends CatsEffectSuite:

  override val munitIOTimeout: FiniteDuration = 45.seconds

  private def resources(root: os.Path): Resource[IO, SharedResources] =
    for
      dispatcher <- Dispatcher.parallel[IO]
      system <- Resource.make(IO(ActorSystem("external-leg-prepare")))(_.stopAll)
      shared <- Resource.eval {
        for
          rateLimiter <- RateLimiter.create()
          tracker <- FileChangeTracker.create(root.toString)
          fileLocks <- FileLockManager.create
          thinking <- Ref.of[IO, ThinkingConfig](ThinkingConfig())
          models <- Ref.of[IO, Map[String, ModelCandidate]](Map.empty)
          muted <- Ref.of[IO, Boolean](false)
        yield SharedResources(
          llm = null,
          dispatcher = dispatcher,
          sessionStore = SessionStore(root / "sessions", root / "tasks"),
          projectRoot = root,
          thinkingConfigRef = thinking,
          rateLimiter = rateLimiter,
          fileChangeTracker = tracker,
          contextWindow = 100_000,
          agentLibrary = new AgentLibrary(root / "agents"),
          taskStore = FileTaskStore,
          historyArchiver = null,
          fileLockManager = fileLocks,
          sessionModelOverrides = models,
          providerRegistry = null,
          healthMonitor = null,
          actorSystem = system,
          subAgentTaskStore = new SubAgentTaskStore(root / "subagent-tasks"),
          voiceMutedRef = muted
        )
      }
    yield shared

  private def isolatedRoot: Resource[IO, os.Path] =
    Resource
      .make(IO.blocking {
        assume(!ExecutorRegistry.isWindows, "The echo fixture requires POSIX")
        val original = PathUtil.dataRoot
        val root = os.temp.dir(prefix = "external-leg-prepare-")
        PathUtil.setDataRoot(root)
        (original, root)
      }) { case (original, root) =>
        IO.blocking(DbHost.release(root.toNIO))
          .guarantee(IO.blocking {
            PathUtil.setDataRoot(original)
            os.remove.all(root)
          })
      }
      .map(_._2)

  private def mkRepo(dir: os.Path): IO[os.Path] = IO.blocking {
    os.makeDir.all(dir)
    os.proc("git", "init", "-b", "main", dir.toString).call(stdout = os.Pipe, stderr = os.Pipe)
    os.proc("git", "-C", dir.toString, "config", "user.email", "spec@nebflow.local").call()
    os.proc("git", "-C", dir.toString, "config", "user.name", "spec").call()
    os.proc("git", "-C", dir.toString, "commit", "--allow-empty", "-m", "Initialize fixture")
      .call(stdout = os.Pipe, stderr = os.Pipe)
    dir
  }

  private def git(repo: os.Path, args: String*): IO[String] = IO.blocking {
    os.proc(Seq("git", "-C", repo.toString) ++ args).call(stdout = os.Pipe, stderr = os.Pipe).out.trim()
  }

  private def pollUntil(label: String)(condition: IO[Boolean]): IO[Unit] =
    def loop: IO[Unit] = condition.flatMap { ready =>
      if ready then IO.unit else IO.sleep(20.millis) *> IO.defer(loop)
    }
    loop.timeoutTo(12.seconds, IO.raiseError(new AssertionError(s"Timed out waiting for $label")))

  test("external spawn preserves main and the original prompt; an empty protocol fails incomplete") {
    isolatedRoot.use { root =>
      resources(root).use { shared =>
        val prompt = "read the code and report"
        val parent = "external-leg-parent"
        for
          repo <- mkRepo(root / "repo")
          headBefore <- git(repo, "rev-parse", "HEAD")
          branchesBefore <- git(repo, "for-each-ref", "--format=%(refname)", "refs/heads/")
          worktreesBefore <- git(repo, "worktree", "list", "--porcelain")
          spawnSpec = ExternalSessionRunner.SpawnSpec(
            executorId = "claude-code",
            // Echo exits zero but emits no recognized protocol frames.
            binPath = "/bin/echo",
            task = prompt,
            title = "external leg task",
            workspace = repo.toString,
            project = None,
            taskId = Some("91"),
            parentSessionId = parent,
            rootSessionId = parent,
            parentRef = None,
            wsSend = None,
            resources = shared
          )
          _ <- Resource
            .make(
              ExternalSessionRunner
                .spawn(spawnSpec)
                .flatMap(result => IO.fromEither(result.left.map(error => new AssertionError(s"Spawn failed: $error"))))
            ) { case (id, _) =>
              ExternalOutputStore.processOf(id).flatMap(_.traverse_(ExternalOutputStore.stopOwnedProcess)) *>
                pollUntil("runner cleanup")(shared.agentRegistry.get.map(!_.contains(id)))
            }
            .use { case (id, ack) =>
              for
                _ <- pollUntil("durable failed terminal and mailbox enqueue")(
                  shared.subAgentTaskStore
                    .findByTaskId(id)
                    .map(
                      _.exists(task =>
                        task.executionStatus.contains("failed") && task.notificationPending.contains(false)
                      )
                    )
                )
                _ <- pollUntil("runner detachment and process exit")(
                  (shared.agentRegistry.get, ExternalOutputStore.processOf(id)).mapN { (registry, process) =>
                    !registry.contains(id) && process.forall(p => !p.isAlive)
                  }
                )
                task <- shared.subAgentTaskStore.findByTaskId(id).map(_.getOrElse(fail("Missing task")))
                messages <- shared.sessionStore.loadMessagesForSession(id)
                mailbox <- SessionMailbox.scanPending(parent)
                output <- ExternalOutputStore.read(id, 0L)
                headAfter <- git(repo, "rev-parse", "HEAD")
                branchAfter <- git(repo, "branch", "--show-current")
                branchesAfter <- git(repo, "for-each-ref", "--format=%(refname)", "refs/heads/")
                worktreesAfter <- git(repo, "worktree", "list", "--porcelain")
                _ <- IO.blocking {
                  val record = DelegateRegistry.find(id).getOrElse(fail("Missing delegate record"))
                  assert(ack.contains(id))
                  assertEquals(record.cwd, repo.toString)
                  assertEquals(record.branch, None)
                  assertEquals(record.worktree, None)
                  assertEquals(record.baseBranch, None)
                  assertEquals(record.projectWorkspace, None)
                  assertEquals(record.worktreeJudged, None)
                  assertEquals(headAfter, headBefore)
                  assertEquals(branchAfter, "main")
                  assertEquals(branchesAfter, branchesBefore)
                  assertEquals(worktreesAfter, worktreesBefore)
                  assert(!os.exists(repo / ".nebflow" / "worktrees"))

                  val spawn = ChainStore
                    .listSync()
                    .flatMap(_.entries)
                    .find(entry =>
                      entry.kind == "delegate-spawn" &&
                        entry.payload.hcursor.get[String]("delegateId").toOption.contains(id)
                    )
                    .getOrElse(fail("Missing delegate-spawn chain entry"))
                  val cursor = spawn.payload.hcursor
                  assertEquals(cursor.get[String]("executor").toOption, Some("claude-code"))
                  assertEquals(cursor.get[String]("cwd").toOption, Some(repo.toString))
                  List("branch", "worktree", "baseWorkspace", "baseBranch", "baseSha").foreach { key =>
                    assertEquals(cursor.get[String](key).toOption, Some(""), key)
                  }
                  assert(cursor.get[Boolean]("worktreeJudged").toOption.isEmpty)
                  assert(cursor.get[String]("judgeLeg").toOption.isEmpty)

                  assertEquals(messages.filter(_.role == MessageRole.User).map(_.textContent), List(prompt))
                  assertEquals(messages.filter(_.role == MessageRole.Assistant), Nil)
                  assert(messages.forall(_.runId == task.runId))
                  assertEquals(task.status, "failed")
                  assertEquals(task.incomplete, Some(true))
                  assert(task.terminalReason.exists(_.contains("Result collection incomplete")))
                  assert(task.diagnostic.exists(_.contains("without a protocol terminal")))
                  assertEquals(output.map(_.exitCode), Some(Some(0)))
                  assertEquals(output.map(_.status), Some("failed"))
                  assertEquals(mailbox.size, 1)
                  val result = mailbox.head
                  assertEquals(result.identity, task.notificationId.getOrElse(fail("Missing notification id")))
                  assertEquals(result.event.eventType, "failed")
                  assertEquals(result.event.metadata("retryable").flatMap(_.asBoolean), Some(false))
                  assertEquals(result.event.metadata("completionGeneration").flatMap(_.asString), task.runId)
                }
              yield ()
            }
        yield ()
        end for
      }
    }
  }
end ExternalLegPrepareSpec

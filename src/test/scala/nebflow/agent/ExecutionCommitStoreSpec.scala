package nebflow.agent

import cats.effect.{Deferred, IO}
import scala.concurrent.duration.*
import cats.syntax.all.*
import io.circe.{Json, JsonObject}
import io.circe.parser.parse
import io.circe.syntax.*
import munit.CatsEffectSuite
import nebflow.shared.*

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files

class ExecutionCommitStoreSpec extends CatsEffectSuite:

  private val admission = ExecutionAdmission(
    "session",
    "run",
    "commit",
    "request",
    "attempt",
    "incarnation",
    "owner",
    "business",
    "audit",
    Json.obj("message" -> Json.fromString("hello")),
    "initial-input"
  )

  private def isolated[A](f: java.nio.file.Path => IO[A]): IO[A] =
    IO.blocking(os.temp.dir(prefix = "execution-admission-"))
      .bracket(dir => f(dir.toNIO.toRealPath()))(dir => IO.blocking(os.remove.all(dir)))

  test("parent admission closure serializes with admission and persists after reopen") {
    isolated { root =>
      val child = admission.copy(sessionId = "child", ownerBinding = "parent")
      for
        _ <- ExecutionCommitStore.resource(root).use { store =>
          for
            entered <- Deferred[IO, Unit]
            release <- Deferred[IO, Unit]
            closure <- store
              .withClosedParentAdmission("parent")(entered.complete(()).void *> release.get.timeout(2.seconds))
              .start
            _ <- entered.get.timeout(2.seconds)
            finished <- Deferred[IO, Unit]
            attempted <- Deferred[IO, Unit]
            pending <- (attempted
              .complete(())
              .void *> store.admit(child).flatTap(_ => finished.complete(()).void)).start
            _ <- attempted.get.timeout(2.seconds)
            _ <- IO.sleep(50.millis)
            before <- finished.tryGet
            _ = assertEquals(before, None)
            _ <- release.complete(())
            _ <- closure.joinWithNever.timeout(2.seconds)
            rejected <- pending.joinWithNever.timeout(2.seconds)
            _ = assertEquals(rejected, ExecutionCommitResult.Rejected("parent-admission-closed"))
            unrelated <- store.admit(
              child.copy(
                sessionId = "other-child",
                executionId = "other-run",
                commitId = "other-commit",
                requestId = "other-request",
                ownerBinding = "other-parent"
              )
            )
            _ = assert(unrelated.isInstanceOf[ExecutionCommitResult.Committed])
          yield ()
        }
        _ <- ExecutionCommitStore.resource(root).use { store =>
          store
            .admit(child)
            .map(result => assertEquals(result, ExecutionCommitResult.Rejected("parent-admission-closed")))
        }
      yield ()
      end for
    }
  }

  test("ambiguous retry charge blocks the live model decision") {
    isolated { root =>
      val fault = new java.util.concurrent.atomic.AtomicBoolean(false)
      ExecutionCommitStore
        .resourceWithFault(
          root,
          point =>
            if point == "before-acknowledgement" && fault.compareAndSet(true, false) then
              throw new java.io.IOException("lost retry acknowledgement")
        )
        .use { store =>
          def command(ref: ExecutionCommitRef, id: String, event: ExecutionEvent) =
            ExecutionTransition("session", "run", id, id, ref, "attempt", "incarnation", event)
          for
            admitted <- store.admit(admission)
            start <- store.transition(
              command(
                admitted.asInstanceOf[ExecutionCommitResult.Committed].ref,
                "attempt-start",
                ExecutionEvent.AttemptStarted
              )
            )
            _ <- store.registerRunner(ExecutionRunner.Attempt("session", "run", "attempt", "incarnation")).use {
              registered =>
                val runner = registered.toOption.get
                for
                  release <- Deferred[IO, Unit]
                  model <- runner.startModel(
                    command(
                      start.asInstanceOf[ExecutionCommitResult.Committed].ref,
                      "model",
                      ExecutionEvent.ModelRequested(Json.Null, 1)
                    )
                  )(release.get)
                  (requested, receipt) = model.toOption.get
                  input <- store.acceptInput(
                    command(requested, "input-busy", ExecutionEvent.InputAccepted("busy", Json.fromString("queued")))
                  )
                  _ = assert(input.isInstanceOf[ExecutionCommitResult.Committed])
                  _ <- IO(fault.set(true))
                  charge <- runner.chargeModelRetry(
                    command(requested, "charge", ExecutionEvent.ModelRetryCharged("classified-transient-provider"))
                  )
                  _ = assert(charge.isInstanceOf[ExecutionCommitResult.CommitUnknown])
                  _ <- release.complete(())
                  decision <- runner.modelDecision(
                    command(requested, "decision", ExecutionEvent.ModelDecided(ExecutionModelDecision(Json.Null, Nil))),
                    receipt
                  )
                  _ = assertEquals(decision, ExecutionCommitResult.Rejected("commit-unknown"))
                yield ()
                end for
            }
          yield ()
          end for
        }
    }
  }

  test("terminal force failure and lost acknowledgement reconcile the same envelope and reject stale writers") {
    isolated { root =>
      val fail = new java.util.concurrent.atomic.AtomicReference[String]("")
      val fault: String => Unit =
        point => if fail.get() == point && fail.getAndSet("") == point then throw new java.io.IOException(point)
      ExecutionCommitStore.resourceWithFault(root, fault).use { store =>
        def command(ref: ExecutionCommitRef, id: String, event: ExecutionEvent) =
          ExecutionTransition("session", "run", id, id, ref, "attempt", "incarnation", event)
        val envelope = Json.obj(
          "source" -> Json.fromString("delegate"),
          "eventType" -> Json.fromString("completed"),
          "payload" -> Json.fromString("done"),
          "metadata" -> Json.obj(),
          "correlationId" -> Json.fromString("session"),
          "blocks" -> Json.Null
        )
        val tasks = new SubAgentTaskStore(os.Path(root) / "tasks")
        val row = SubAgentTask(
          "session",
          "owner",
          "agent",
          "initial",
          "task",
          "running",
          0,
          System.currentTimeMillis(),
          None,
          None,
          "delegate"
        )
        for
          _ <- tasks.beginRun(row, "run", "owner", "nebflow")
          _ <- IO(tasks.installExecutionAuthority(store))
          admitted <- nebflow.core.ExecutionCommitPort.admitHandle(store, admission)
          handle = admitted.toOption.get
          started <- store.transition(command(handle.ref, "start", ExecutionEvent.AttemptStarted))
          _ <- store.registerRunner(ExecutionRunner.Attempt("session", "run", "attempt", "incarnation")).use {
            registered =>
              val runner = registered.toOption.get
              for
                model <- runner.startModel(
                  command(
                    started.asInstanceOf[ExecutionCommitResult.Committed].ref,
                    "model",
                    ExecutionEvent.ModelRequested(Json.Null, 0)
                  )
                )(IO.unit)
                (modelRef, receipt) = model.toOption.get
                decision <- runner.modelDecision(
                  command(modelRef, "decision", ExecutionEvent.ModelDecided(ExecutionModelDecision(Json.Null, Nil))),
                  receipt
                )
                round <- store.commitRound(
                  command(
                    decision.asInstanceOf[ExecutionCommitResult.Committed].ref,
                    "round",
                    ExecutionEvent.RoundCommitted(List("initial-input"), Json.obj())
                  )
                )
                terminal = command(
                  round.asInstanceOf[ExecutionCommitResult.Committed].ref,
                  "terminal-attempt",
                  ExecutionEvent.TerminalInstallPrepared("", envelope)
                )
                stale <- runner.installTerminal(terminal.copy(attemptId = "old"), envelope)
                _ = assertEquals(stale, ExecutionCommitResult.Rejected("runner-authority-required"))
                _ <- IO(fail.set("before-terminal-confirm"))
                failed <- ProtectedAgentExecution.installTerminal(handle, runner, envelope).attempt
                _ = assert(failed.isLeft)
                unpublished <- store.committedTerminal("session", "run")
                _ = assert(unpublished.isLeft)
                afterFailure <- store.observeChain("session", "run")
                _ = assertEquals(
                  afterFailure.asInstanceOf[ExecutionChainObservation.Verified].state.phase,
                  "terminal-prepared"
                )
                path = root.resolve("session").resolve("terminal-cnVu.json")
                _ <- IO.blocking(
                  Files.writeString(path, envelope.deepMerge(Json.obj("payload" -> Json.fromString("other"))).noSpaces)
                )
                conflict <- runner.installTerminal(terminal, envelope)
                _ = assertEquals(conflict, ExecutionCommitResult.Rejected("terminal-envelope-conflict"))
                _ <- IO.blocking(Files.writeString(path, envelope.noSpaces))
                _ <- IO(fail.set("after-terminal-confirm"))
                lost <- runner.installTerminal(terminal, envelope)
                _ = assert(lost.isInstanceOf[ExecutionCommitResult.CommitUnknown])
                _ <- IO(fail.set("before-terminal-acknowledgement"))
                lostAck <- runner.installTerminal(terminal, envelope)
                _ = assert(lostAck.isInstanceOf[ExecutionCommitResult.CommitUnknown])
                confirmed <- ProtectedAgentExecution.installTerminal(handle, runner, envelope)
                _ = assertEquals(confirmed, path.toString)
                state <- store.observeChain("session", "run")
                _ = assertEquals(state.asInstanceOf[ExecutionChainObservation.Verified].state.phase, "terminal")
                loaded <- nebflow.core.SessionStore(os.Path(root), os.Path(root)).loadTerminalEnvelope("session", "run")
                _ = assertEquals(loaded.map(_.payload), Some("done"))
                rejected <- tasks.completeRun("owner", "session", "run", "failed").attempt
                _ = assert(rejected.isLeft)
                other = new SubAgentTaskStore(os.Path(root) / "tasks")
                rejectedOther <- other.completeRun("owner", "session", "run", "failed").attempt
                _ = assert(rejectedOther.isLeft)
              yield ()
              end for
          }
          repaired <- store.repairTerminalProjection("session", "run", tasks)
          _ = assertEquals(
            repaired.toOption.flatMap(_.resultRef),
            Some(root.resolve("session").resolve("terminal-cnVu.json").toString)
          )
          duplicate <- store.repairTerminalProjection("session", "run", tasks)
          _ = assertEquals(duplicate, repaired)
          _ <- IO(tasks.releaseExecutionAuthority(store))
        yield ()
        end for
      } *> ExecutionCommitStore.resource(root).use { reopened =>
        val reopenedTasks = new SubAgentTaskStore(os.Path(root) / "tasks")
        reopened
          .repairTerminalProjection("session", "run", reopenedTasks)
          .map(result => assertEquals(result.toOption.flatMap(_.executionStatus), Some("finished")))
      }
    }
  }

  test("owned model decision is fenced after revocation and waits for work completion") {
    isolated { root =>
      ExecutionCommitStore.resource(root).use { store =>
        def command(ref: ExecutionCommitRef, id: String, event: ExecutionEvent) =
          ExecutionTransition("session", "run", id, id, ref, "attempt", "incarnation", event)
        for
          admitted <- store.admit(admission)
          initial = admitted.asInstanceOf[ExecutionCommitResult.Committed].ref
          started <- store.transition(command(initial, "attempt-start", ExecutionEvent.AttemptStarted))
          active = started.asInstanceOf[ExecutionCommitResult.Committed].ref
          _ <- store.registerRunner(ExecutionRunner.Attempt("session", "run", "attempt", "incarnation")).use {
            registered =>
              val runner = registered.toOption.get
              for
                entered <- Deferred[IO, Unit]
                release <- Deferred[IO, Unit]
                request <- runner.startModel(
                  command(active, "model-request", ExecutionEvent.ModelRequested(Json.Null, 1))
                )(
                  entered.complete(()).void *> release.get
                )
                (ref, receipt) = request.toOption.get
                _ <- entered.get.timeout(5.seconds)
                decision = command(
                  ref,
                  "model-decision",
                  ExecutionEvent.ModelDecided(ExecutionModelDecision(Json.Null, Nil))
                )
                waiting <- runner.modelDecision(decision, receipt).start
                before <- store.observeChain("session", "run")
                _ = assertEquals(before.asInstanceOf[ExecutionChainObservation.Verified].state.phase, "model")
                queued <- store.acceptInput(
                  command(ref, "busy-input", ExecutionEvent.InputAccepted("busy", Json.fromString("queued")))
                )
                _ = assert(queued.isInstanceOf[ExecutionCommitResult.Committed])
                _ <- release.complete(())
                committed <- waiting.joinWithNever.timeout(5.seconds)
                _ = assert(committed.isInstanceOf[ExecutionCommitResult.Committed])
                after <- store.observeChain("session", "run")
                _ = assertEquals(
                  after.asInstanceOf[ExecutionChainObservation.Verified].state.pendingInputs,
                  List("initial-input", "busy")
                )
                _ <- runner.revoke
                late <- runner.modelDecision(decision, receipt)
                _ = assertEquals(late, ExecutionCommitResult.Rejected("runner-revoked"))
              yield ()
              end for
          }
        yield ()
        end for
      }
    }
  }

  test("protected intake retains distinct inputs and deduplicates stable identities") {
    isolated { root =>
      ExecutionCommitStore.resource(root).use { store =>
        for
          bound <- nebflow.core.ExecutionCommitPort.admitHandle(store, admission)
          handle = bound.toOption.get
          first <- InputLanding.protectedUser(
            handle,
            nebflow.actor.AgentCommand.UserInput(
              text = "queued",
              replyTo = None,
              clientMessageId = Some("queued-id")
            )
          )
          duplicate <- InputLanding.protectedUser(
            handle,
            nebflow.actor.AgentCommand.UserInput(
              text = "queued",
              replyTo = None,
              clientMessageId = Some("queued-id")
            )
          )
          conflict <- InputLanding.protectedUser(
            handle,
            nebflow.actor.AgentCommand.UserInput(
              text = "different",
              replyTo = None,
              clientMessageId = Some("queued-id")
            )
          )
          observed <- store.observeChain("session", "run")
        yield
          assertEquals(first, Right(true))
          assertEquals(duplicate, Right(false))
          assert(conflict.isLeft)
          val state = observed.asInstanceOf[ExecutionChainObservation.Verified].state
          assertEquals(state.pendingInputs, List("initial-input", "queued-id"))
      }
    }
  }

  test("concurrent protected intake serializes inputs without stale-revision rejection") {
    isolated { root =>
      ExecutionCommitStore.resource(root).use { store =>
        for
          bound <- nebflow.core.ExecutionCommitPort.admitHandle(store, admission)
          handle = bound.toOption.get
          results <- List("input-a", "input-b").parTraverse { id =>
            InputLanding.protectedUser(
              handle,
              nebflow.actor.AgentCommand.UserInput(
                text = id,
                replyTo = None,
                clientMessageId = Some(id)
              )
            )
          }
          observation <- store.observeChain("session", "run")
        yield
          assertEquals(results, List(Right(true), Right(true)))
          val state = observation.asInstanceOf[ExecutionChainObservation.Verified].state
          assertEquals(state.pendingInputs.toSet, Set("initial-input", "input-a", "input-b"))
      }
    }
  }

  test("semantic input retry retains its original receipt after another input advances the chain") {
    isolated { root =>
      ExecutionCommitStore.resource(root).use { store =>
        for
          admitted <- store.admit(admission)
          ref = admitted.asInstanceOf[ExecutionCommitResult.Committed].ref
          firstCommand = ExecutionTransition(
            "session",
            "run",
            "input-commit",
            "input-request",
            ref,
            "attempt",
            "incarnation",
            ExecutionEvent.InputAccepted("first", Json.fromString("one"))
          )
          first <- store.acceptInput(firstCommand)
          second <- store.acceptInput(
            firstCommand.copy(
              commitId = "second-commit",
              requestId = "second-request",
              event = ExecutionEvent.InputAccepted("second", Json.fromString("two"))
            )
          )
          retry <- store.acceptInput(firstCommand)
          conflict <- store.acceptInput(
            firstCommand.copy(event = ExecutionEvent.InputAccepted("first", Json.fromString("changed")))
          )
        yield
          assert(second.isInstanceOf[ExecutionCommitResult.Committed])
          assertEquals(retry, ExecutionCommitResult.Duplicate(first.asInstanceOf[ExecutionCommitResult.Committed].ref))
          assertEquals(conflict, ExecutionCommitResult.Rejected("commit-id-conflict"))
      }
    }
  }

  test("cross-run initial input returns original receipt or content conflict without creating an admission") {
    isolated { root =>
      ExecutionCommitStore.resource(root).use { store =>
        val other = admission.copy(executionId = "other-run", commitId = "other-commit", requestId = "other-request")
        for
          first <- store.admit(admission)
          duplicate <- store.admit(other)
          conflict <- store.admit(other.copy(initialInput = Json.fromString("different")))
          absent <- store.observe(other.sessionId, other.executionId)
          retry <- store.admit(other.copy(initialInputId = "new-input"))
        yield
          val ref = first.asInstanceOf[ExecutionCommitResult.Committed].ref
          assertEquals(duplicate, ExecutionCommitResult.DuplicateInput(admission.executionId, ref))
          assertEquals(conflict, ExecutionCommitResult.Rejected("session-input-conflict"))
          assertEquals(absent, ExecutionAdmissionObservation.Missing)
          assert(retry.isInstanceOf[ExecutionCommitResult.Committed])
      }
    }
  }

  test("strict admission survives reopen and matching duplicate is confirmed") {
    isolated { root =>
      for
        first <- ExecutionCommitStore.resource(root).use(_.admit(admission))
        observed <- ExecutionCommitStore.resource(root).use(_.observe("session", "run"))
        duplicate <- ExecutionCommitStore.resource(root).use(_.admit(admission))
      yield
        assert(first.isInstanceOf[ExecutionCommitResult.Committed])
        assert(duplicate.isInstanceOf[ExecutionCommitResult.Duplicate])
        assert(observed match
          case ExecutionAdmissionObservation.Verified(record, input) =>
            record.sessionId == "session" && record.executionId == "run" &&
            input == Json.obj("inputId" -> admission.initialInputId.asJson, "content" -> admission.initialInput)
          case _ => false)
    }
  }

  test("lost acknowledgement locks the live owner and reopened duplicate is reconfirmed") {
    isolated { root =>
      val fault: String => Unit =
        phase => if phase == "before-acknowledgement" then throw new java.io.IOException("injected")
      for
        first <- ExecutionCommitStore.resourceWithFault(root, fault).use { store =>
          for
            unknown <- store.admit(admission)
            blocked <- store.admit(admission)
          yield (unknown, blocked)
        }
        duplicate <- ExecutionCommitStore.resource(root).use(_.admit(admission))
      yield
        assert(first._1.isInstanceOf[ExecutionCommitResult.CommitUnknown])
        assertEquals(first._2, ExecutionCommitResult.Rejected("commit-unknown"))
        assert(duplicate.isInstanceOf[ExecutionCommitResult.Duplicate])
    }
  }

  test("same owner reconciles only exact installed admission after acknowledgement loss") {
    isolated { root =>
      val fault: String => Unit =
        phase => if phase == "before-acknowledgement" then throw new java.io.IOException("injected")
      ExecutionCommitStore.resourceWithFault(root, fault).use { store =>
        for
          unknown <- store.admit(admission)
          conflict <- store.reconcileAdmission(admission.copy(requestId = "other"))
          resolved <- store.reconcileAdmission(admission)
          duplicate <- store.admit(admission)
        yield
          assert(unknown.isInstanceOf[ExecutionCommitResult.CommitUnknown])
          assertEquals(conflict, ExecutionCommitResult.Rejected("unknown-or-conflicting-request"))
          assert(resolved.isInstanceOf[ExecutionCommitResult.Duplicate])
          assert(duplicate.isInstanceOf[ExecutionCommitResult.Duplicate])
      }
    }
  }

  test("missing installed record remains unknown during reconciliation") {
    isolated { root =>
      val fault: String => Unit = phase => if phase == "after-file-force" then throw new java.io.IOException("injected")
      ExecutionCommitStore.resourceWithFault(root, fault).use { store =>
        for
          unknown <- store.admit(admission)
          unresolved <- store.reconcileAdmission(admission)
          blocked <- store.admit(admission)
        yield
          assert(unknown.isInstanceOf[ExecutionCommitResult.CommitUnknown])
          assertEquals(unresolved, ExecutionCommitResult.CommitUnknown("commit", "reconciliation-unconfirmed"))
          assertEquals(blocked, ExecutionCommitResult.Rejected("commit-unknown"))
      }
    }
  }

  test("each installation fault holds admission unresolved until a record can be confirmed") {
    List("after-file-force", "after-install", "after-directory-force").traverse_ { phase =>
      isolated { root =>
        var calls = 0
        val fault: String => Unit = point =>
          if point == phase then
            calls += 1
            if calls == 2 then throw new java.io.IOException("injected record fault")
        ExecutionCommitStore.resourceWithFault(root, fault).use { store =>
          for
            unknown <- store.admit(admission)
            resolved <- store.reconcileAdmission(admission)
          yield
            assert(unknown.isInstanceOf[ExecutionCommitResult.CommitUnknown])
            if phase == "after-file-force" then
              assertEquals(resolved, ExecutionCommitResult.CommitUnknown("commit", "reconciliation-unconfirmed"))
            else assert(resolved.isInstanceOf[ExecutionCommitResult.Duplicate])
        }
      }
    }
  }

  test("directory fsync capability is POSIX-only so Windows boot does not fail") {
    // 2026-11-21 cross-machine E2E defect #3: FileChannel.open(<dir>, READ) is a
    // POSIX capability. On Windows it threw AccessDeniedException inside the
    // first-run config path (MainBoot.ensureFirstRunConfig -> shell.scala:1665),
    // so the engine could not boot on Windows at all. The predicate is pure, so
    // both platforms are asserted here regardless of the host running the suite.
    assert(ExecutionCommitStore.directoryForceSupported("Mac OS X"))
    assert(ExecutionCommitStore.directoryForceSupported("Linux"))
    assert(ExecutionCommitStore.directoryForceSupported("FreeBSD"))
    assert(!ExecutionCommitStore.directoryForceSupported("Windows 11"))
    assert(!ExecutionCommitStore.directoryForceSupported("Windows Server 2022"))
    assert(!ExecutionCommitStore.directoryForceSupported("windows"))
    assert(ExecutionCommitStore.directoryForceSupported("")) // 未知平台 = POSIX 口径（既有语义不变）
  }

  test("directory fsync stays best-effort: an unusable directory never fails the write legs") {
    isolated { root =>
      val missing = root.resolve("no-such-directory")
      IO.blocking {
        // 空目录/不存在目录不再抛：best-effort 语义与 AtomicJson.forceParentDir 同族
        ExecutionCommitStore.forceDirectory(missing)
        ExecutionCommitStore.forceDirectory(root)
      }
    }
  }

  test("different admission cannot replace the original") {
    isolated { root =>
      ExecutionCommitStore.resource(root).use { store =>
        for
          _ <- store.admit(admission)
          conflict <- store.admit(admission.copy(commitId = "other"))
          observed <- store.observe("session", "run")
        yield
          assertEquals(conflict, ExecutionCommitResult.Rejected("conflicting-or-corrupt-admission"))
          assert(observed.isInstanceOf[ExecutionAdmissionObservation.Verified])
      }
    }
  }

  test("corrupt payload preserves the record and requires review") {
    isolated { root =>
      ExecutionCommitStore.resource(root).use { store =>
        for
          _ <- store.admit(admission)
          payload = root.resolve("session/executions/cnVu/payloads")
          files <- IO.blocking {
            val stream = Files.list(payload)
            try
              val iterator = stream.iterator()
              iterator.next()
            finally stream.close()
          }
          _ <- IO.blocking(Files.write(files, "corrupt".getBytes(UTF_8)))
          observed <- store.observe("session", "run")
          duplicate <- store.admit(admission)
        yield
          assert(observed.isInstanceOf[ExecutionAdmissionObservation.ReviewRequired])
          assertEquals(duplicate, ExecutionCommitResult.Rejected("conflicting-or-corrupt-admission"))
          assert(Files.exists(root.resolve("session/executions/cnVu/commits/00000000000000000001.json")))
      }
    }
  }

  test("rehashing malformed admission bindings cannot make the record valid") {
    isolated { root =>
      ExecutionCommitStore.resource(root).use { store =>
        for
          _ <- store.admit(admission)
          _ <- IO.blocking {
            val file = root.resolve("session/executions/cnVu/commits/00000000000000000001.json")
            val original = parse(Files.readString(file)).toOption.get
            val unsigned = original.mapObject(_.remove("recordHash").add("attemptId", Json.fromString("../bad")))
            def sorted(value: Json): Json = value.arrayOrObject(
              value,
              values => Json.fromValues(values.map(sorted)),
              obj =>
                Json.fromJsonObject(JsonObject.fromIterable(obj.toList.sortBy(_._1).map { case (k, v) =>
                  k -> sorted(v)
                }))
            )
            val digest = java.security.MessageDigest
              .getInstance("SHA-256")
              .digest(sorted(unsigned).noSpaces.getBytes(UTF_8))
              .map(b => f"${b & 0xff}%02x")
              .mkString
            val malformed = unsigned.mapObject(_.add("recordHash", digest.asJson))
            Files.writeString(file, sorted(malformed).noSpaces)
          }
          observed <- store.observe("session", "run")
        yield assertEquals(observed, ExecutionAdmissionObservation.ReviewRequired("invalid-record-binding"))
      }
    }
  }

  test("second live authority cannot acquire the same root") {
    isolated { root =>
      ExecutionCommitStore.resource(root).use { _ =>
        ExecutionCommitStore.resource(root).use(_ => IO.unit).attempt.map(result => assert(result.isLeft))
      }
    }
  }

  test("transition chain reopens with queued inputs and fences stale attempts") {
    isolated { root =>
      for
        first <- ExecutionCommitStore.resource(root).use { store =>
          for
            admitted <- store.admit(admission)
            ref = admitted.asInstanceOf[ExecutionCommitResult.Committed].ref
            started <- store.transition(
              ExecutionTransition(
                "session",
                "run",
                "c2",
                "r2",
                ref,
                "attempt",
                "incarnation",
                ExecutionEvent.AttemptStarted
              )
            )
            ref2 = started.asInstanceOf[ExecutionCommitResult.Committed].ref
            input <- store.transition(
              ExecutionTransition(
                "session",
                "run",
                "c3",
                "r3",
                ref2,
                "attempt",
                "incarnation",
                ExecutionEvent.InputAccepted("input", Json.fromString("hello"))
              )
            )
          yield input
        }
        observed <- ExecutionCommitStore.resource(root).use(_.observeChain("session", "run"))
        stale <- ExecutionCommitStore.resource(root).use { store =>
          val ref = first.asInstanceOf[ExecutionCommitResult.Committed].ref
          store.transition(
            ExecutionTransition(
              "session",
              "run",
              "c4",
              "r4",
              ref,
              "old",
              "incarnation",
              ExecutionEvent.ModelRequested(Json.Null, 0)
            )
          )
        }
      yield
        assert(observed match
          case ExecutionChainObservation.Verified(state) =>
            state.pendingInputs == List("initial-input", "input") && state.phase == "active"
          case _ => false)
        assertEquals(stale, ExecutionCommitResult.Rejected("stale-attempt"))
        assert(!Files.exists(root.resolve("session/executions/cnVu/commits/00000000000000000004.json")))
        assert(!Files.exists(root.resolve("session/executions/cnVu/intents/00000000000000000004.json")))
    }
  }

  test("stale transition cannot install an intent payload or record") {
    isolated { root =>
      ExecutionCommitStore.resource(root).use { store =>
        def files(dir: java.nio.file.Path): Set[String] =
          if !Files.exists(dir) then Set.empty
          else
            val stream = Files.list(dir)
            try
              val iterator = stream.iterator()
              val names = Set.newBuilder[String]
              while iterator.hasNext do names += iterator.next().getFileName.toString
              names.result()
            finally stream.close()
        for
          admitted <- store.admit(admission)
          initial = admitted.asInstanceOf[ExecutionCommitResult.Committed].ref
          started <- store.transition(
            ExecutionTransition(
              "session",
              "run",
              "c2",
              "r2",
              initial,
              "attempt",
              "incarnation",
              ExecutionEvent.AttemptStarted
            )
          )
          before <- IO.blocking {
            val dir = root.resolve("session/executions/cnVu")
            List("intents", "payloads", "commits").map(name => files(dir.resolve(name)))
          }
          stale <- store.transition(
            ExecutionTransition(
              "session",
              "run",
              "c3",
              "r3",
              initial,
              "attempt",
              "incarnation",
              ExecutionEvent.ModelRequested(Json.fromString("stale-payload"), 0)
            )
          )
          after <- IO.blocking {
            val dir = root.resolve("session/executions/cnVu")
            List("intents", "payloads", "commits").map(name => files(dir.resolve(name)))
          }
          observed <- store.observeChain("session", "run")
        yield
          assert(started.isInstanceOf[ExecutionCommitResult.Committed])
          assertEquals(stale, ExecutionCommitResult.Rejected("stale-revision"))
          assertEquals(after, before)
          assert(observed match
            case ExecutionChainObservation.Verified(state) =>
              state.ref == started.asInstanceOf[ExecutionCommitResult.Committed].ref
            case _ => false)
        end for
      }
    }
  }

  test("lost transition acknowledgement is reconfirmed after reopen") {
    isolated { root =>
      for
        admitted <- ExecutionCommitStore.resource(root).use(_.admit(admission))
        ref = admitted.asInstanceOf[ExecutionCommitResult.Committed].ref
        command = ExecutionTransition(
          "session",
          "run",
          "c2",
          "r2",
          ref,
          "attempt",
          "incarnation",
          ExecutionEvent.AttemptStarted
        )
        unknown <- ExecutionCommitStore
          .resourceWithFault(
            root,
            phase => if phase == "before-acknowledgement" then throw new java.io.IOException("injected")
          )
          .use(_.transition(command))
        resolved <- ExecutionCommitStore.resource(root).use(_.reconcileTransition(command))
        observed <- ExecutionCommitStore.resource(root).use(_.observeChain("session", "run"))
      yield
        assert(unknown.isInstanceOf[ExecutionCommitResult.CommitUnknown])
        assert(resolved.isInstanceOf[ExecutionCommitResult.Duplicate])
        assert(observed.isInstanceOf[ExecutionChainObservation.Verified])
    }
  }

  test("unresolved staged transition blocks the reopened chain") {
    isolated { root =>
      for
        admitted <- ExecutionCommitStore.resource(root).use(_.admit(admission))
        ref = admitted.asInstanceOf[ExecutionCommitResult.Committed].ref
        command = ExecutionTransition(
          "session",
          "run",
          "c2",
          "r2",
          ref,
          "attempt",
          "incarnation",
          ExecutionEvent.AttemptStarted
        )
        unknown <- ExecutionCommitStore
          .resourceWithFault(root, phase => if phase == "after-install" then throw new java.io.IOException("injected"))
          .use(_.transition(command))
        observed <- ExecutionCommitStore.resource(root).use(_.observeChain("session", "run"))
        resolved <- ExecutionCommitStore.resource(root).use(_.reconcileTransition(command))
      yield
        assert(unknown.isInstanceOf[ExecutionCommitResult.CommitUnknown])
        assert(observed.isInstanceOf[ExecutionChainObservation.ReviewRequired])
        assert(resolved.isInstanceOf[ExecutionCommitResult.CommitUnknown])
    }
  }

  test("reopen blocks advancement until the previous chain is reconfirmed") {
    isolated { root =>
      for
        admitted <- ExecutionCommitStore.resource(root).use(_.admit(admission))
        ref = admitted.asInstanceOf[ExecutionCommitResult.Committed].ref
        command = ExecutionTransition(
          "session",
          "run",
          "c2",
          "r2",
          ref,
          "attempt",
          "incarnation",
          ExecutionEvent.AttemptStarted
        )
        unknown <- ExecutionCommitStore
          .resourceWithFault(
            root,
            phase => if phase == "before-acknowledgement" then throw new java.io.IOException("lost acknowledgement")
          )
          .use(_.transition(command))
        nextRef = unknown match
          case ExecutionCommitResult.CommitUnknown(_, _) => ()
          case _ => fail("expected unknown commit")
        blocked <- ExecutionCommitStore
          .resourceWithFault(
            root,
            phase => if phase == "before-chain-reconfirmation" then throw new java.io.IOException("force unavailable")
          )
          .use { store =>
            for
              observed <- store.observeChain("session", "run")
              result <- observed match
                case ExecutionChainObservation.ReviewRequired(Some(state), _) =>
                  store.transition(
                    ExecutionTransition(
                      "session",
                      "run",
                      "c3",
                      "r3",
                      state.ref,
                      "attempt",
                      "incarnation",
                      ExecutionEvent.ModelRequested(Json.Null, 0)
                    )
                  )
                case _ => IO.raiseError(new IllegalStateException("expected verified prefix"))
            yield (observed, result)
          }
        confirmed <- ExecutionCommitStore.resource(root).use(_.observeChain("session", "run"))
      yield
        assertEquals(nextRef, ())
        assert(blocked._1.isInstanceOf[ExecutionChainObservation.ReviewRequired])
        assert(blocked._2.isInstanceOf[ExecutionCommitResult.Rejected])
        assert(confirmed.isInstanceOf[ExecutionChainObservation.Verified])
    }
  }

  test("declared model operations must finish before round commit") {
    isolated { root =>
      ExecutionCommitStore.resource(root).use { store =>
        def send(ref: ExecutionCommitRef, commit: String, event: ExecutionEvent): IO[ExecutionCommitResult] =
          store.transition(
            ExecutionTransition("session", "run", commit, s"request-$commit", ref, "attempt", "incarnation", event)
          )
        for
          admitted <- store.admit(admission)
          ref1 = admitted.asInstanceOf[ExecutionCommitResult.Committed].ref
          started <- send(ref1, "c2", ExecutionEvent.AttemptStarted)
          ref2 = started.asInstanceOf[ExecutionCommitResult.Committed].ref
          requested <- send(ref2, "c3", ExecutionEvent.ModelRequested(Json.Null, 2))
          ref3 = requested.asInstanceOf[ExecutionCommitResult.Committed].ref
          retry <- send(ref3, "c4", ExecutionEvent.ModelRetryCharged("unclassified"))
          intent = ExecutionToolIntent("op", "call", "Read", Json.obj())
          second = ExecutionToolIntent("op2", "call2", "Read", Json.obj())
          decided <- send(
            ref3,
            "c5",
            ExecutionEvent.ModelDecided(ExecutionModelDecision(Json.Null, List(intent, second)))
          )
          ref5 = decided.asInstanceOf[ExecutionCommitResult.Committed].ref
          premature <- send(ref5, "c6", ExecutionEvent.RoundCommitted(Nil, Json.Null))
          mismatch <- send(ref5, "c6b", ExecutionEvent.ToolPrepared("op", Json.Null))
          prepared <- send(ref5, "c7", ExecutionEvent.ToolPrepared("op", intent.asJson))
          ref7 = prepared.asInstanceOf[ExecutionCommitResult.Committed].ref
          result <- store.registerRunner(ExecutionRunner.Attempt("session", "run", "attempt", "incarnation")).use {
            registration =>
              val runner = registration.toOption.get
              def command(ref: ExecutionCommitRef, id: String, event: ExecutionEvent) =
                ExecutionTransition("session", "run", id, s"request-$id", ref, "attempt", "incarnation", event)
              for
                early <- runner.dispatch(command(ref7, "early", ExecutionEvent.ToolStarted("op")), intent)(IO.unit)
                prepared2 <- send(ref7, "c7b", ExecutionEvent.ToolPrepared("op2", second.asJson))
                ref7b = prepared2.asInstanceOf[ExecutionCommitResult.Committed].ref
                wrongIntent <- runner.dispatch(
                  command(ref7b, "wrong", ExecutionEvent.ToolStarted("op")),
                  intent.copy(arguments = Json.fromString("changed"))
                )(IO.raiseError(new IllegalStateException("must not run")))
                _ = assertEquals(
                  wrongIntent.left.toOption,
                  Some(ExecutionCommitResult.Rejected("intent-mismatch-or-unverified"))
                )
                running <- runner.dispatch(command(ref7b, "c8", ExecutionEvent.ToolStarted("op")), intent)(IO.unit)
                (ref8, receipt) = running.toOption.get
                unknown <- send(ref8, "c9", ExecutionEvent.RoundCommitted(Nil, Json.Null))
                outcome <- runner.outcome(
                  command(ref8, "c10", ExecutionEvent.ToolOutcome("op", Json.fromString("result"))),
                  receipt
                )
                ref10 = outcome.asInstanceOf[ExecutionCommitResult.Committed].ref
                running2 <- runner.dispatch(command(ref10, "c11", ExecutionEvent.ToolStarted("op2")), second)(IO.unit)
                (ref11, receipt2) = running2.toOption.get
                outcome2 <- runner.outcome(
                  command(ref11, "c12", ExecutionEvent.ToolOutcome("op2", Json.fromString("result2"))),
                  receipt2
                )
                ref12 = outcome2.asInstanceOf[ExecutionCommitResult.Committed].ref
                beforeRound <- store.observeChain("session", "run")
                checkpoint = Json.obj("transcript" -> Json.fromString("round"))
                committed <- send(ref12, "c13", ExecutionEvent.RoundCommitted(Nil, checkpoint))
                afterRound <- store.observeChain("session", "run")
              yield
                val decisionState = beforeRound.asInstanceOf[ExecutionChainObservation.Verified].state
                assertEquals(decisionState.decision, Some(ExecutionModelDecision(Json.Null, List(intent, second))))
                assertEquals(
                  decisionState.outcomes,
                  Map("op" -> Json.fromString("result"), "op2" -> Json.fromString("result2"))
                )
                assertEquals(
                  afterRound.asInstanceOf[ExecutionChainObservation.Verified].state.checkpoint,
                  Some(checkpoint)
                )
                assert(early.isLeft)
                assert(unknown.isInstanceOf[ExecutionCommitResult.Rejected])
                assert(committed.isInstanceOf[ExecutionCommitResult.Committed])
              end for
          }
        yield
          assertEquals(retry, ExecutionCommitResult.Rejected("retry-classification-or-budget-invalid"))
          assert(premature.isInstanceOf[ExecutionCommitResult.Rejected])
          assert(mismatch.isInstanceOf[ExecutionCommitResult.Rejected])
          result
        end for
      }
    }
  }

  test("round checkpoint is reconstructed after authority reopen") {
    isolated { root =>
      val checkpoint = Json.obj("messages" -> Json.arr(Json.fromString("response")))
      for
        _ <- ExecutionCommitStore.resource(root).use { store =>
          def send(ref: ExecutionCommitRef, id: String, event: ExecutionEvent): IO[ExecutionCommitRef] =
            store
              .transition(
                ExecutionTransition("session", "run", id, s"request-$id", ref, "attempt", "incarnation", event)
              )
              .map(_.asInstanceOf[ExecutionCommitResult.Committed].ref)
          for
            admitted <- store.admit(admission)
            ref1 = admitted.asInstanceOf[ExecutionCommitResult.Committed].ref
            ref2 <- send(ref1, "start", ExecutionEvent.AttemptStarted)
            ref3 <- send(ref2, "request", ExecutionEvent.ModelRequested(Json.Null, 0))
            ref4 <- send(
              ref3,
              "decision",
              ExecutionEvent.ModelDecided(ExecutionModelDecision(Json.fromString("answer"), Nil))
            )
            _ <- send(ref4, "round", ExecutionEvent.RoundCommitted(List("initial-input"), checkpoint))
          yield ()
        }
        reopened <- ExecutionCommitStore.resource(root).use(_.observeChain("session", "run"))
      yield
        val state = reopened.asInstanceOf[ExecutionChainObservation.Verified].state
        assertEquals(state.checkpoint, Some(checkpoint))
        assertEquals(state.pendingInputs, Nil)
      end for
    }
  }

  test("input dedup does not accept a conflicting request identity") {
    isolated { root =>
      ExecutionCommitStore.resource(root).use { store =>
        for
          admitted <- store.admit(admission)
          ref = admitted.asInstanceOf[ExecutionCommitResult.Committed].ref
          input <- store.transition(
            ExecutionTransition(
              "session",
              "run",
              "c2",
              "r2",
              ref,
              "attempt",
              "incarnation",
              ExecutionEvent.InputAccepted("input", Json.fromString("content"))
            )
          )
          ref2 = input.asInstanceOf[ExecutionCommitResult.Committed].ref
          conflict <- store.transition(
            ExecutionTransition(
              "session",
              "run",
              "c3",
              "r2",
              ref2,
              "attempt",
              "incarnation",
              ExecutionEvent.InputAccepted("input", Json.fromString("content"))
            )
          )
        yield assertEquals(conflict, ExecutionCommitResult.Rejected("request-id-conflict"))
      }
    }
  }

  test("input identity conflicts remain fenced across runs after authority reopen") {
    isolated { root =>
      val otherAdmission = admission.copy(
        executionId = "run2",
        commitId = "admission2",
        requestId = "request2",
        initialInputId = "initial-input2"
      )
      for
        original <- ExecutionCommitStore.resource(root).use { store =>
          for
            admitted <- store.admit(admission)
            ref = admitted.asInstanceOf[ExecutionCommitResult.Committed].ref
            accepted <- store.transition(
              ExecutionTransition(
                "session",
                "run",
                "c2",
                "r2",
                ref,
                "attempt",
                "incarnation",
                ExecutionEvent.InputAccepted("stable-input", Json.fromString("original"))
              )
            )
          yield accepted
        }
        result <- ExecutionCommitStore.resource(root).use { store =>
          for
            admitted <- store.admit(otherAdmission)
            ref = admitted.asInstanceOf[ExecutionCommitResult.Committed].ref
            conflict <- store.transition(
              ExecutionTransition(
                "session",
                "run2",
                "other-c2",
                "other-r2",
                ref,
                "attempt",
                "incarnation",
                ExecutionEvent.InputAccepted("stable-input", Json.fromString("changed"))
              )
            )
            duplicate <- store.transition(
              ExecutionTransition(
                "session",
                "run2",
                "other-c3",
                "other-r3",
                ref,
                "attempt",
                "incarnation",
                ExecutionEvent.InputAccepted("stable-input", Json.fromString("original"))
              )
            )
            originalState <- store.observeChain("session", "run")
            otherState <- store.observeChain("session", "run2")
          yield (conflict, originalState, otherState, duplicate)
        }
      yield
        assert(original.isInstanceOf[ExecutionCommitResult.Committed])
        assertEquals(result._1, ExecutionCommitResult.Rejected("session-input-conflict"))
        assert(result._4 match
          case ExecutionCommitResult.DuplicateInput("run", ref) =>
            ref == original.asInstanceOf[ExecutionCommitResult.Committed].ref
          case _ => false)
        assert(result._2 match
          case ExecutionChainObservation.Verified(state) =>
            state.inputs.get("stable-input").contains(Json.fromString("original")) &&
            state.pendingInputs == List("initial-input", "stable-input")
          case _ => false)
        assert(result._3 match
          case ExecutionChainObservation.Verified(state) =>
            !state.inputs.contains("stable-input") && state.pendingInputs == List(
              "initial-input2"
            ) && state.ref.revision == 1L
          case _ => false)
      end for
    }
  }

  test("initial input identity remains visible to another run after reopen") {
    isolated { root =>
      val other = admission.copy(
        executionId = "run2",
        commitId = "admission2",
        requestId = "request2",
        initialInputId = "other-input"
      )
      for
        first <- ExecutionCommitStore.resource(root).use(_.admit(admission))
        result <- ExecutionCommitStore.resource(root).use { store =>
          for
            second <- store.admit(other)
            ref = second.asInstanceOf[ExecutionCommitResult.Committed].ref
            duplicate <- store.transition(
              ExecutionTransition(
                "session",
                "run2",
                "c2",
                "r2",
                ref,
                "attempt",
                "incarnation",
                ExecutionEvent.InputAccepted(admission.initialInputId, admission.initialInput)
              )
            )
            conflict <- store.transition(
              ExecutionTransition(
                "session",
                "run2",
                "c3",
                "r3",
                ref,
                "attempt",
                "incarnation",
                ExecutionEvent.InputAccepted(admission.initialInputId, Json.Null)
              )
            )
          yield (duplicate, conflict)
        }
      yield
        assertEquals(
          result._1,
          ExecutionCommitResult.DuplicateInput("run", first.asInstanceOf[ExecutionCommitResult.Committed].ref)
        )
        assertEquals(result._2, ExecutionCommitResult.Rejected("session-input-conflict"))
      end for
    }
  }

  test("runner stop receipt requires owned work and finalizers to finish") {
    isolated { root =>
      ExecutionCommitStore.resource(root).use { store =>
        def command(ref: ExecutionCommitRef, id: String, event: ExecutionEvent) =
          ExecutionTransition("session", "run", id, s"request-$id", ref, "attempt", "incarnation", event)
        def ref(result: ExecutionCommitResult) = result.asInstanceOf[ExecutionCommitResult.Committed].ref
        for
          admitted <- store.admit(admission)
          started <- store.transition(command(ref(admitted), "start", ExecutionEvent.AttemptStarted))
          _ <- store.registerRunner(ExecutionRunner.Attempt("session", "run", "attempt", "incarnation")).use {
            registered =>
              val runner = registered.toOption.get
              for
                entered <- Deferred[IO, Unit]
                finalizing <- Deferred[IO, Unit]
                release <- Deferred[IO, Unit]
                _ <- (for
                  running <- runner.startModel(
                    command(ref(started), "model", ExecutionEvent.ModelRequested(Json.Null, 0))
                  )(
                    (entered.complete(()).void *> IO.never[Unit]).guarantee(finalizing.complete(()).void *> release.get)
                  )
                  (modelRef, receipt) = running.toOption.get
                  _ <- entered.get.timeout(1.second)
                  _ <- runner.revoke
                  _ <- finalizing.get.timeout(1.second)
                  suspended <- store.transition(command(modelRef, "suspend", ExecutionEvent.Suspended("interrupted")))
                  before <- runner.confirmStopped(
                    command(ref(suspended), "stop-before", ExecutionEvent.RunnerStopped),
                    50.millis
                  )
                  _ = assertEquals(before, ExecutionCommitResult.Rejected("runner-stop-unconfirmed"))
                  _ <- release.complete(())
                  _ <- receipt.await.timeout(1.second)
                  stopped <- runner.confirmStopped(
                    command(ref(suspended), "stop-after", ExecutionEvent.RunnerStopped),
                    1.second
                  )
                  chain <- store.observeChain("session", "run")
                yield
                  assert(stopped.isInstanceOf[ExecutionCommitResult.Committed])
                  assert(chain.asInstanceOf[ExecutionChainObservation.Verified].state.runnerStopped)
                ).guarantee(release.complete(()).void)
              yield ()
              end for
          }
        yield ()
        end for
      }
    }
  }

  test("owned quiescent resume retains run and rejects stale observation") {
    isolated { root =>
      ExecutionCommitStore.resource(root).use { store =>
        def cmd(ref: ExecutionCommitRef, id: String, event: ExecutionEvent) =
          ExecutionTransition("session", "run", id, id, ref, "attempt", "incarnation", event)
        def ref(result: ExecutionCommitResult) = result.asInstanceOf[ExecutionCommitResult.Committed].ref
        for
          admitted <- store.admit(admission)
          started <- store.transition(cmd(ref(admitted), "start-resume", ExecutionEvent.AttemptStarted))
          _ <- store.registerRunner(ExecutionRunner.Attempt("session", "run", "attempt", "incarnation")).use { owned =>
            val runner = owned.toOption.get
            for
              model <- runner.startModel(
                cmd(ref(started), "resume-model", ExecutionEvent.ModelRequested(Json.Null, 0))
              )(IO.unit)
              (modelRef, receipt) = model.toOption.get
              decision <- runner.modelDecision(
                cmd(modelRef, "resume-decision", ExecutionEvent.ModelDecided(ExecutionModelDecision(Json.Null, Nil))),
                receipt
              )
              checkpoint = ProtectedAgentExecution.checkpoint(nebflow.actor.AgentState()).toOption.get
              round <- store.commitRound(
                cmd(ref(decision), "resume-round", ExecutionEvent.RoundCommitted(List("initial-input"), checkpoint))
              )
              _ <- runner.revoke
              suspended <- store.transition(
                cmd(ref(round), "resume-suspended", ExecutionEvent.Suspended("interrupted"))
              )
              stopped <- runner.confirmStopped(
                cmd(ref(suspended), "resume-stopped", ExecutionEvent.RunnerStopped),
                1.second
              )
              event = ExecutionEvent.ResumeAuthorized("attempt2", "incarnation2", "operator")
              request = cmd(ref(stopped), "resume-request", event)
              stale <- runner.authorizeResume(request.copy(expected = ref(suspended)), "audit", 1.second)
              _ = assert(stale.isInstanceOf[ExecutionCommitResult.Rejected])
              wrongConfig <- runner.authorizeResume(request, "other", 1.second)
              _ = assert(wrongConfig.isInstanceOf[ExecutionCommitResult.Rejected])
              accepted <- runner.authorizeResume(request, "audit", 1.second)
              _ = assert(accepted.isInstanceOf[ExecutionCommitResult.Committed])
              duplicate <- runner.authorizeResume(request, "audit", 1.second)
              _ = assertEquals(duplicate, ExecutionCommitResult.Duplicate(ref(accepted)))
              chain <- store.observeChain("session", "run")
              state = chain.asInstanceOf[ExecutionChainObservation.Verified].state
              _ = assertEquals(state.attemptId, "attempt2")
              _ = assertEquals(state.incarnationId, "incarnation2")
              _ = assertEquals(state.checkpoint, Some(checkpoint))
              restarted <- store.transition(
                ExecutionTransition(
                  "session",
                  "run",
                  "attempt2-start",
                  "attempt2-start",
                  state.ref,
                  "attempt2",
                  "incarnation2",
                  ExecutionEvent.AttemptStarted
                )
              )
              _ = assert(restarted.isInstanceOf[ExecutionCommitResult.Committed])
              _ <- store
                .registerRunner(ExecutionRunner.Attempt("session", "run", "attempt2", "incarnation2"))
                .use(next => IO(assert(next.isRight)))
            yield ()
            end for
          }
        yield ()
        end for
      }
    }
  }

  test("a runner stopped event alone cannot authorize same run resume") {
    isolated { root =>
      ExecutionCommitStore.resource(root).use { store =>
        for
          admitted <- store.admit(admission)
          ref = admitted.asInstanceOf[ExecutionCommitResult.Committed].ref
          started <- store.transition(
            ExecutionTransition(
              "session",
              "run",
              "c2",
              "r2",
              ref,
              "attempt",
              "incarnation",
              ExecutionEvent.AttemptStarted
            )
          )
          ref2 = started.asInstanceOf[ExecutionCommitResult.Committed].ref
          suspended <- store.transition(
            ExecutionTransition(
              "session",
              "run",
              "c3",
              "r3",
              ref2,
              "attempt",
              "incarnation",
              ExecutionEvent.Suspended("interrupted")
            )
          )
          ref3 = suspended.asInstanceOf[ExecutionCommitResult.Committed].ref
          stopped <- store.transition(
            ExecutionTransition(
              "session",
              "run",
              "c4",
              "r4",
              ref3,
              "attempt",
              "incarnation",
              ExecutionEvent.RunnerStopped
            )
          )
          resume <- store.transition(
            ExecutionTransition(
              "session",
              "run",
              "c5",
              "r5",
              ref3,
              "attempt",
              "incarnation",
              ExecutionEvent.ResumeAuthorized("attempt2", "incarnation2", "operator")
            )
          )
        yield
          assertEquals(stopped, ExecutionCommitResult.Rejected("runner-authority-required"))
          assertEquals(resume, ExecutionCommitResult.Rejected("runner-authority-required"))
      }
    }
  }

  test("cancelled chain rejects a subsequent tool start without installing its record") {
    isolated { root =>
      ExecutionCommitStore.resource(root).use { store =>
        def command(ref: ExecutionCommitRef, id: String, event: ExecutionEvent) =
          ExecutionTransition("session", "run", id, s"request-$id", ref, "attempt", "incarnation", event)
        def ref(result: ExecutionCommitResult): ExecutionCommitRef =
          result.asInstanceOf[ExecutionCommitResult.Committed].ref
        val intent = ExecutionToolIntent("op", "call", "Read", Json.obj())
        for
          admitted <- store.admit(admission)
          started <- store.transition(command(ref(admitted), "start", ExecutionEvent.AttemptStarted))
          requested <- store.transition(command(ref(started), "model", ExecutionEvent.ModelRequested(Json.Null, 0)))
          decided <- store.transition(
            command(
              ref(requested),
              "decision",
              ExecutionEvent.ModelDecided(ExecutionModelDecision(Json.Null, List(intent)))
            )
          )
          prepared <- store.transition(
            command(ref(decided), "prepare", ExecutionEvent.ToolPrepared("op", intent.asJson))
          )
          cancelled <- store.transition(command(ref(prepared), "cancel", ExecutionEvent.CancelRequested("operator")))
          denied <- store.transition(command(ref(cancelled), "dispatch", ExecutionEvent.ToolStarted("op")))
          observed <- store.observeChain("session", "run")
        yield
          assertEquals(denied, ExecutionCommitResult.Rejected("runner-authority-required"))
          assertEquals(observed.asInstanceOf[ExecutionChainObservation.Verified].state.phase, "cancelled")
          assert(!Files.exists(root.resolve("session/executions/cnVu/commits/00000000000000000007.json")))
        end for
      }
    }
  }

  test("round commit merges only inputs accepted after its observed revision") {
    isolated { root =>
      ExecutionCommitStore.resource(root).use { store =>
        def command(ref: ExecutionCommitRef, id: String, event: ExecutionEvent) =
          ExecutionTransition("session", "run", id, s"request-$id", ref, "attempt", "incarnation", event)
        def committed(result: ExecutionCommitResult): ExecutionCommitRef =
          result.asInstanceOf[ExecutionCommitResult.Committed].ref
        for
          admissionResult <- store.admit(admission)
          started <- store.transition(command(committed(admissionResult), "start", ExecutionEvent.AttemptStarted))
          requested <- store.transition(
            command(committed(started), "model", ExecutionEvent.ModelRequested(Json.Null, 0))
          )
          decided <- store.transition(
            command(
              committed(requested),
              "decision",
              ExecutionEvent.ModelDecided(ExecutionModelDecision(Json.Null, Nil))
            )
          )
          selected = committed(decided)
          queued <- store.acceptInput(
            command(selected, "queued", ExecutionEvent.InputAccepted("later", Json.fromString("queued")))
          )
          round = command(
            selected,
            "round",
            ExecutionEvent.RoundCommitted(List("initial-input"), Json.fromString("checkpoint"))
          )
          installed <- store.commitRound(round)
          duplicate <- store.commitRound(round)
          stale <- store.commitRound(command(selected, "other-round", ExecutionEvent.RoundCommitted(Nil, Json.Null)))
          state <- store.observeChain("session", "run")
        yield
          assert(queued.isInstanceOf[ExecutionCommitResult.Committed])
          assert(installed.isInstanceOf[ExecutionCommitResult.Committed])
          assertEquals(duplicate, ExecutionCommitResult.Duplicate(committed(installed)))
          assertEquals(stale, ExecutionCommitResult.Rejected("stale-revision"))
          assertEquals(state.asInstanceOf[ExecutionChainObservation.Verified].state.pendingInputs, List("later"))
        end for
      }
    }
  }

  test("candidate scan discovers orphan admission without a task projection after reopen") {
    isolated { root =>
      for
        _ <- ExecutionCommitStore.resource(root).use(_.admit(admission))
        candidates <- ExecutionCommitStore.resource(root).use(_.candidates)
      yield assertEquals(
        candidates,
        Right(List(ExecutionCandidate("session", ExecutionProtection.Protected(List("run")))))
      )
    }
  }

  test("model retry charge is bounded and retained after reopen") {
    isolated { root =>
      def command(ref: ExecutionCommitRef, id: String, event: ExecutionEvent) =
        ExecutionTransition("session", "run", id, s"request-$id", ref, "attempt", "incarnation", event)
      def ref(result: ExecutionCommitResult): ExecutionCommitRef =
        result.asInstanceOf[ExecutionCommitResult.Committed].ref
      for
        charged <- ExecutionCommitStore.resource(root).use { store =>
          for
            admitted <- store.admit(admission)
            started <- store.transition(command(ref(admitted), "start", ExecutionEvent.AttemptStarted))
            requested <- store.transition(command(ref(started), "model", ExecutionEvent.ModelRequested(Json.Null, 1)))
            charged <- store.transition(
              command(ref(requested), "charge", ExecutionEvent.ModelRetryCharged("classified-transient-provider"))
            )
          yield ref(charged)
        }
        result <- ExecutionCommitStore.resource(root).use { store =>
          for
            observed <- store.observeChain("session", "run")
            rejected <- store.transition(
              command(charged, "extra-charge", ExecutionEvent.ModelRetryCharged("classified-transient-provider"))
            )
          yield (observed, rejected)
        }
      yield
        assertEquals(result._1.asInstanceOf[ExecutionChainObservation.Verified].state.retryCharges, 1L)
        assertEquals(result._2, ExecutionCommitResult.Rejected("retry-classification-or-budget-invalid"))
      end for
    }
  }

  test("candidate scan serializes with concurrent admissions") {
    isolated { root =>
      ExecutionCommitStore.resource(root).use { store =>
        for
          pair <- (store.admit(admission), store.candidates).parTupled
          after <- store.candidates
        yield
          assert(pair._1.isInstanceOf[ExecutionCommitResult.Committed])
          val candidate = ExecutionCandidate("session", ExecutionProtection.Protected(List("run")))
          assert(pair._2 == Right(Nil) || pair._2 == Right(List(candidate)))
          assertEquals(after, Right(List(candidate)))
      }
    }
  }

  test("candidate lookup survives reopen and rejects a corrupt candidate") {
    isolated { root =>
      for
        _ <- ExecutionCommitStore.resource(root).use(_.admit(admission))
        found <- ExecutionCommitStore.resource(root).use(_.protection("session"))
        _ <- IO.blocking(
          Files.writeString(root.resolve("session/executions/cnVu/commits/00000000000000000001.json"), "corrupt")
        )
        corrupt <- ExecutionCommitStore.resource(root).use(_.protection("session"))
        absent <- ExecutionCommitStore.resource(root).use(_.protection("other"))
      yield
        assertEquals(found, ExecutionProtection.Protected(List("run")))
        assert(corrupt.isInstanceOf[ExecutionProtection.Unknown])
        assertEquals(absent, ExecutionProtection.Unprotected)
    }
  }

  test("revoked runner cannot prepare tools or install a round checkpoint") {
    isolated { root =>
      ExecutionCommitStore.resource(root).use { store =>
        def command(ref: ExecutionCommitRef, id: String, event: ExecutionEvent) =
          ExecutionTransition("session", "run", id, s"request-$id", ref, "attempt", "incarnation", event)
        def ref(result: ExecutionCommitResult) = result.asInstanceOf[ExecutionCommitResult.Committed].ref
        val intent = ExecutionToolIntent("op", "call", "Read", Json.obj())
        for
          admitted <- store.admit(admission)
          started <- store.transition(command(ref(admitted), "start", ExecutionEvent.AttemptStarted))
          requested <- store.transition(command(ref(started), "model", ExecutionEvent.ModelRequested(Json.Null, 0)))
          decided <- store.transition(
            command(
              ref(requested),
              "decision",
              ExecutionEvent.ModelDecided(ExecutionModelDecision(Json.Null, List(intent)))
            )
          )
          _ <- store.registerRunner(ExecutionRunner.Attempt("session", "run", "attempt", "incarnation")).use {
            registered =>
              for
                _ <- registered.toOption.get.revoke
                before <- store.observeChain("session", "run")
                prepared <- store.transition(
                  command(ref(decided), "late-prepare", ExecutionEvent.ToolPrepared("op", intent.asJson))
                )
                round <- store.commitRound(
                  command(ref(decided), "late-round", ExecutionEvent.RoundCommitted(Nil, Json.Null))
                )
                after <- store.observeChain("session", "run")
              yield
                assertEquals(prepared, ExecutionCommitResult.Rejected("runner-revoked"))
                assertEquals(round, ExecutionCommitResult.Rejected("runner-revoked"))
                assertEquals(after, before)
          }
        yield ()
        end for
      }
    }
  }

  test("authority cancel and dispatch are ordered and held cleanup stays unconfirmed") {
    List(false, true).traverse_ { permitFirst =>
      isolated { root =>
        ExecutionCommitStore.resource(root).use { store =>
          def command(ref: ExecutionCommitRef, id: String, event: ExecutionEvent) =
            ExecutionTransition("session", "run", id, s"request-$id", ref, "attempt", "incarnation", event)
          def ref(result: ExecutionCommitResult) = result.asInstanceOf[ExecutionCommitResult.Committed].ref
          val intent = ExecutionToolIntent("op", "call", "Read", Json.obj())
          for
            admitted <- store.admit(admission)
            started <- store.transition(command(ref(admitted), "start", ExecutionEvent.AttemptStarted))
            requested <- store.transition(command(ref(started), "model", ExecutionEvent.ModelRequested(Json.Null, 0)))
            decided <- store.transition(
              command(
                ref(requested),
                "decision",
                ExecutionEvent.ModelDecided(ExecutionModelDecision(Json.Null, List(intent)))
              )
            )
            prepared <- store.transition(
              command(ref(decided), "prepare", ExecutionEvent.ToolPrepared("op", intent.asJson))
            )
            _ <- store.registerRunner(ExecutionRunner.Attempt("session", "run", "attempt", "incarnation")).use {
              registered =>
                val runner = registered.toOption.get
                for
                  entered <- Deferred[IO, Unit]
                  finalizing <- Deferred[IO, Unit]
                  release <- Deferred[IO, Unit]
                  _ <-
                    if permitFirst then
                      for
                        dispatch <- runner.dispatch(
                          command(ref(prepared), "dispatch", ExecutionEvent.ToolStarted("op")),
                          intent
                        )(
                          (entered.complete(()).void *> IO.never[Unit])
                            .guarantee(finalizing.complete(()).void *> release.get)
                        )
                        (startedRef, receipt) = dispatch.toOption.get
                        _ <- entered.get
                        cancelled <- runner.cancel(
                          command(startedRef, "cancel", ExecutionEvent.CancelRequested("operator"))
                        )
                        _ = assert(cancelled.isInstanceOf[ExecutionCommitResult.Committed])
                        _ <- finalizing.get.timeout(1.second)
                        stopped <- runner.awaitStopped(50.millis)
                        _ = assert(!stopped)
                        _ <- release.complete(())
                        _ <- receipt.await.timeout(1.second)
                        stale <- runner.outcome(
                          command(ref(cancelled), "outcome", ExecutionEvent.ToolOutcome("op", Json.Null)),
                          receipt
                        )
                        _ = assert(stale.isInstanceOf[ExecutionCommitResult.Rejected])
                        confirmed <- runner.awaitStopped(1.second)
                        _ = assert(confirmed)
                        observed <- store.observeChain("session", "run")
                        _ = assertEquals(
                          observed.asInstanceOf[ExecutionChainObservation.Verified].state.operations.get("op"),
                          Some("started")
                        )
                      yield ()
                    else
                      for
                        cancelled <- runner.cancel(
                          command(ref(prepared), "cancel", ExecutionEvent.CancelRequested("operator"))
                        )
                        dispatch <- runner.dispatch(
                          command(ref(cancelled), "dispatch", ExecutionEvent.ToolStarted("op")),
                          intent
                        )(entered.complete(()).void)
                        _ = assertEquals(dispatch.left.toOption, Some(ExecutionCommitResult.Rejected("runner-revoked")))
                        untouched <- entered.tryGet
                        _ = assertEquals(untouched, None)
                      yield ()
                yield ()
                end for
            }
          yield ()
          end for
        }
      }
    }
  }

  test("owned tool merges concurrent input receipts without dispatching twice") {
    isolated { root =>
      ExecutionCommitStore.resource(root).use { store =>
        def command(ref: ExecutionCommitRef, id: String, event: ExecutionEvent) =
          ExecutionTransition("session", "run", id, s"request-$id", ref, "attempt", "incarnation", event)
        def ref(result: ExecutionCommitResult) = result.asInstanceOf[ExecutionCommitResult.Committed].ref
        val intent = ExecutionToolIntent("op", "call", "Read", Json.obj())
        for
          admitted <- store.admit(admission)
          started <- store.transition(command(ref(admitted), "start", ExecutionEvent.AttemptStarted))
          requested <- store.transition(command(ref(started), "model", ExecutionEvent.ModelRequested(Json.Null, 0)))
          decided <- store.transition(
            command(
              ref(requested),
              "decision",
              ExecutionEvent.ModelDecided(ExecutionModelDecision(Json.Null, List(intent)))
            )
          )
          prepared <- store.transition(
            command(ref(decided), "prepare", ExecutionEvent.ToolPrepared("op", intent.asJson))
          )
          _ <- store.registerRunner(ExecutionRunner.Attempt("session", "run", "attempt", "incarnation")).use {
            registered =>
              val runner = registered.toOption.get
              for
                count <- cats.effect.Ref.of[IO, Int](0)
                accepted <- store.acceptInput(
                  command(ref(prepared), "input-one", ExecutionEvent.InputAccepted("one", Json.fromString("one")))
                )
                _ = assert(accepted.isInstanceOf[ExecutionCommitResult.Committed])
                dispatched <- runner.dispatch(
                  command(ref(prepared), "dispatch", ExecutionEvent.ToolStarted("op")),
                  intent
                )(count.update(_ + 1))
                (startedRef, receipt) = dispatched.toOption.get
                _ <- receipt.await
                acceptedTwo <- store.acceptInput(
                  command(startedRef, "input-two", ExecutionEvent.InputAccepted("two", Json.fromString("two")))
                )
                _ = assert(acceptedTwo.isInstanceOf[ExecutionCommitResult.Committed])
                result <- runner.outcome(
                  command(startedRef, "outcome", ExecutionEvent.ToolOutcome("op", Json.fromString("done"))),
                  receipt
                )
                duplicate <- runner.dispatch(
                  command(ref(result), "duplicate", ExecutionEvent.ToolStarted("op")),
                  intent
                )(count.update(_ + 1))
                calls <- count.get
                chain <- store.observeChain("session", "run")
              yield
                assert(result.isInstanceOf[ExecutionCommitResult.Committed])
                assert(duplicate.isLeft)
                assertEquals(calls, 1)
                assertEquals(
                  chain.asInstanceOf[ExecutionChainObservation.Verified].state.pendingInputs,
                  List("initial-input", "one", "two")
                )
              end for
          }
        yield ()
        end for
      }
    }
  }

  test("failed owned work after an effect remains started and cannot advance after reopen") {
    isolated { root =>
      def command(ref: ExecutionCommitRef, id: String, event: ExecutionEvent) =
        ExecutionTransition("session", "run", id, s"request-$id", ref, "attempt", "incarnation", event)
      def ref(result: ExecutionCommitResult) = result.asInstanceOf[ExecutionCommitResult.Committed].ref
      val intent = ExecutionToolIntent("op", "call", "Read", Json.obj())
      val effect = root.resolve("observed-effect")
      for
        startedRef <- ExecutionCommitStore.resource(root).use { store =>
          for
            admitted <- store.admit(admission)
            started <- store.transition(command(ref(admitted), "start", ExecutionEvent.AttemptStarted))
            requested <- store.transition(command(ref(started), "model", ExecutionEvent.ModelRequested(Json.Null, 0)))
            decided <- store.transition(
              command(
                ref(requested),
                "decision",
                ExecutionEvent.ModelDecided(ExecutionModelDecision(Json.Null, List(intent)))
              )
            )
            prepared <- store.transition(
              command(ref(decided), "prepare", ExecutionEvent.ToolPrepared("op", intent.asJson))
            )
            result <- store.registerRunner(ExecutionRunner.Attempt("session", "run", "attempt", "incarnation")).use {
              registered =>
                val runner = registered.toOption.get
                for
                  dispatched <- runner.dispatch(
                    command(ref(prepared), "dispatch", ExecutionEvent.ToolStarted("op")),
                    intent
                  )(
                    IO.blocking(Files.writeString(effect, "effect")) *> IO
                      .raiseError[Unit](new RuntimeException("lost result"))
                  )
                  (dispatchRef, receipt) = dispatched.toOption.get
                  outcome <- runner.outcome(
                    command(dispatchRef, "outcome", ExecutionEvent.ToolOutcome("op", Json.fromString("success"))),
                    receipt
                  )
                  _ = assertEquals(outcome, ExecutionCommitResult.Rejected("operation-unconfirmed-review-required"))
                yield dispatchRef
                end for
            }
          yield result
        }
        _ <- ExecutionCommitStore.resource(root).use { store =>
          for
            before <- store.observeChain("session", "run")
            next <- store.transition(command(startedRef, "next-model", ExecutionEvent.ModelRequested(Json.Null, 1)))
            after <- store.observeChain("session", "run")
            contents <- IO.blocking(Files.readString(effect))
          yield
            assertEquals(contents, "effect")
            assertEquals(
              before.asInstanceOf[ExecutionChainObservation.Verified].state.operations.get("op"),
              Some("started")
            )
            assert(next.isInstanceOf[ExecutionCommitResult.Rejected])
            assertEquals(after, before)
        }
      yield ()
      end for
    }
  }

  test("released runner remains owned until cleanup and rejects stale outcome") {
    isolated { root =>
      ExecutionCommitStore.resource(root).use { store =>
        def command(ref: ExecutionCommitRef, id: String, event: ExecutionEvent) =
          ExecutionTransition("session", "run", id, s"request-$id", ref, "attempt", "incarnation", event)
        def ref(result: ExecutionCommitResult) = result.asInstanceOf[ExecutionCommitResult.Committed].ref
        val intent = ExecutionToolIntent("op", "call", "Read", Json.obj())
        val attempt = ExecutionRunner.Attempt("session", "run", "attempt", "incarnation")
        for
          admitted <- store.admit(admission)
          started <- store.transition(command(ref(admitted), "start", ExecutionEvent.AttemptStarted))
          requested <- store.transition(command(ref(started), "model", ExecutionEvent.ModelRequested(Json.Null, 0)))
          decided <- store.transition(
            command(
              ref(requested),
              "decision",
              ExecutionEvent.ModelDecided(ExecutionModelDecision(Json.Null, List(intent)))
            )
          )
          prepared <- store.transition(
            command(ref(decided), "prepare", ExecutionEvent.ToolPrepared("op", intent.asJson))
          )
          allocated <- store.registerRunner(attempt).allocated
          (registered, releaseResource) = allocated
          runner = registered.toOption.get
          dispatch <- runner.dispatch(command(ref(prepared), "dispatch", ExecutionEvent.ToolStarted("op")), intent)(
            IO.unit
          )
          (dispatchRef, receipt) = dispatch.toOption.get
          _ <- receipt.await
          before <- IO.blocking {
            val execution = os.Path(root) / "session" / "executions" / "cnVu"
            os.walk(execution)
              .filter(os.isFile)
              .map(path => path.relativeTo(execution).toString -> os.read.bytes(path).toList)
              .toMap
          }
          stale <- runner.outcome(
            command(dispatchRef, "stale", ExecutionEvent.ToolOutcome("op", Json.Null)).copy(attemptId = "old"),
            receipt
          )
          _ = assertEquals(stale, ExecutionCommitResult.Rejected("invalid-runner-command"))
          afterStale <- IO.blocking {
            val execution = os.Path(root) / "session" / "executions" / "cnVu"
            os.walk(execution)
              .filter(os.isFile)
              .map(path => path.relativeTo(execution).toString -> os.read.bytes(path).toList)
              .toMap
          }
          _ = assertEquals(afterStale, before)
          _ <- releaseResource
          stopped <- runner.awaitStopped(1.second)
          _ = assert(stopped)
          again <- store.registerRunner(attempt).use(IO.pure)
          _ = assertEquals(again.left.toOption, Some("runner-already-owned"))
          beforeRevoked <- IO.blocking {
            val execution = os.Path(root) / "session" / "executions" / "cnVu"
            os.walk(execution)
              .filter(os.isFile)
              .map(path => path.relativeTo(execution).toString -> os.read.bytes(path).toList)
              .toMap
          }
          after <- runner.outcome(
            command(dispatchRef, "after-release", ExecutionEvent.ToolOutcome("op", Json.Null)),
            receipt
          )
          _ = assertEquals(after, ExecutionCommitResult.Rejected("runner-revoked"))
          afterRevoked <- IO.blocking {
            val execution = os.Path(root) / "session" / "executions" / "cnVu"
            os.walk(execution)
              .filter(os.isFile)
              .map(path => path.relativeTo(execution).toString -> os.read.bytes(path).toList)
              .toMap
          }
          _ = assertEquals(afterRevoked, beforeRevoked)
        yield ()
        end for
      }
    }
  }

  test("authority lock stays held while an owned finalizer is blocked") {
    isolated { root =>
      def command(ref: ExecutionCommitRef, id: String, event: ExecutionEvent) =
        ExecutionTransition("session", "run", id, s"request-$id", ref, "attempt", "incarnation", event)
      def ref(result: ExecutionCommitResult) = result.asInstanceOf[ExecutionCommitResult.Committed].ref
      val intent = ExecutionToolIntent("op", "call", "Read", Json.obj())
      for
        allocated <- ExecutionCommitStore.resource(root).allocated
        (store, releaseStore) = allocated
        entered <- Deferred[IO, Unit]
        finalizing <- Deferred[IO, Unit]
        finish <- Deferred[IO, Unit]
        admitted <- store.admit(admission)
        started <- store.transition(command(ref(admitted), "start", ExecutionEvent.AttemptStarted))
        requested <- store.transition(command(ref(started), "model", ExecutionEvent.ModelRequested(Json.Null, 0)))
        decided <- store.transition(
          command(
            ref(requested),
            "decision",
            ExecutionEvent.ModelDecided(ExecutionModelDecision(Json.Null, List(intent)))
          )
        )
        prepared <- store.transition(command(ref(decided), "prepare", ExecutionEvent.ToolPrepared("op", intent.asJson)))
        owned <- store.registerRunner(ExecutionRunner.Attempt("session", "run", "attempt", "incarnation")).allocated
        (registration, releaseRunner) = owned
        runner = registration.toOption.get
        _ <- (for
          dispatched <- runner.dispatch(command(ref(prepared), "dispatch", ExecutionEvent.ToolStarted("op")), intent)(
            (entered.complete(()).void *> IO.never[Unit]).guarantee(finalizing.complete(()).void *> finish.get)
          )
          _ = assert(dispatched.isRight)
          _ <- entered.get
          _ <- releaseRunner
          _ <- finalizing.get.timeout(2.seconds)
          observed <- store.observeChain("session", "run")
          current = observed.asInstanceOf[ExecutionChainObservation.Verified].state.ref
          resume <- store.transition(
            command(
              current,
              "resume-held-finalizer",
              ExecutionEvent.ResumeAuthorized("attempt2", "incarnation2", "operator")
            )
          )
          _ = assert(resume.isInstanceOf[ExecutionCommitResult.Rejected])
          afterResume <- store.observeChain("session", "run")
          _ = assertEquals(afterResume, observed)
          _ <- releaseStore.timeout(3.seconds)
          blocked <- ExecutionCommitStore.resource(root).use(_ => IO.unit).attempt
          _ = assert(blocked.isLeft)
          _ <- finish.complete(())
          _ <-
            def reopen: IO[Unit] = ExecutionCommitStore.resource(root).use(_ => IO.unit).attempt.flatMap {
              case Right(_) => IO.unit
              case Left(_) => IO.sleep(20.millis) *> reopen
            }
            reopen.timeout(3.seconds)
        yield ()).guarantee(finish.complete(()).void *> releaseRunner *> releaseStore)
      yield ()
      end for
    }
  }

  test("legacy session deletion and terminal writer refuse a durable protected admission") {
    isolated { root =>
      val sessions = os.Path(root)
      val store = nebflow.core.SessionStore(sessions, sessions / "tasks")
      ExecutionCommitStore.resource(root).use { authority =>
        for
          _ <- IO(store.installExecutionAuthority(authority))
          admitted <- authority.admit(admission)
          deleted <- store.deleteSession(admission.sessionId).attempt
          written <- store
            .saveTerminalEnvelope(
              admission.sessionId,
              admission.executionId,
              nebflow.actor.AgentCommand.ExternalEvent("delegate", "completed", "result")
            )
            .attempt
          msg = nebflow.shared.Message(nebflow.shared.MessageRole.User, Left("late"))
          append <- store.appendDurableMessage(admission.sessionId, msg).attempt
          swap <- store.durableSwapMessages(admission.sessionId, List(msg)).attempt
          queued <- store.saveMessagesForSession(admission.sessionId, List(msg)).attempt
          found <- authority.observeChain(admission.sessionId, admission.executionId)
        yield
          assert(admitted.isInstanceOf[ExecutionCommitResult.Committed])
          assert(deleted.isLeft)
          assert(written.isLeft)
          assert(append.isLeft)
          assert(swap.isLeft)
          assert(queued.isLeft)
          assert(found.isInstanceOf[ExecutionChainObservation.Verified])
      }
    }
  }

  test("an empty execution marker remains unknown after reopen and blocks deletion") {
    isolated { root =>
      val sessions = os.Path(root)
      val sessionStore = nebflow.core.SessionStore(sessions, sessions / "tasks")
      for
        _ <- IO.blocking(os.makeDir.all(sessions / "session" / "executions"))
        _ <- ExecutionCommitStore.resource(root).use { authority =>
          for
            _ <- IO(sessionStore.installExecutionAuthority(authority))
            state <- authority.protection("session")
            deleted <- sessionStore.deleteSession("session").attempt
            _ = assertEquals(state, ExecutionProtection.Unknown("empty-executions-directory"))
            _ = assert(deleted.isLeft)
          yield ()
        }
        observed <- ExecutionCommitStore.resource(root).use(_.protection("session"))
      yield assertEquals(observed, ExecutionProtection.Unknown("empty-executions-directory"))
    }
  }

  test("released authority leaves the session writer fail-closed") {
    isolated { root =>
      val sessions = os.Path(root)
      val store = nebflow.core.SessionStore(sessions, sessions / "tasks")
      for
        _ <- ExecutionCommitStore.resource(root).use { authority =>
          IO(store.installExecutionAuthority(authority)) *> authority.admit(admission).void *>
            IO(store.releaseExecutionAuthority(authority))
        }
        protectedWrite <- store
          .saveTerminalEnvelope(
            admission.sessionId,
            admission.executionId,
            nebflow.actor.AgentCommand.ExternalEvent("delegate", "completed", "result")
          )
          .attempt
        unknownWrite <- store
          .saveTerminalEnvelope(
            "another-session",
            "run",
            nebflow.actor.AgentCommand.ExternalEvent("delegate", "completed", "result")
          )
          .attempt
        reopened <- ExecutionCommitStore.resource(root).use(_.protection(admission.sessionId))
      yield
        assert(protectedWrite.isLeft)
        assert(unknownWrite.isLeft)
        assert(reopened.isInstanceOf[ExecutionProtection.Protected])
      end for
    }
  }

  test("legacy write gate orders admission after the guarded operation") {
    isolated { root =>
      ExecutionCommitStore.resource(root).use { authority =>
        for
          entered <- Deferred[IO, Unit]
          release <- Deferred[IO, Unit]
          guarded <- authority
            .withUnprotectedSession(admission.sessionId)(entered.complete(()).void *> release.get)
            .start
          _ <- entered.get
          admissionFiber <- authority.admit(admission).start
          before <- authority.observe(admission.sessionId, admission.executionId)
          _ = assertEquals(before, ExecutionAdmissionObservation.Missing)
          _ <- release.complete(())
          _ <- guarded.joinWithNever
          admitted <- admissionFiber.joinWithNever
          _ = assert(admitted.isInstanceOf[ExecutionCommitResult.Committed])
          refused <- authority.withUnprotectedSession(admission.sessionId)(IO.unit).attempt
        yield assert(refused.isLeft)
      }
    }
  }

  test("invalid identities are rejected without creating an execution") {
    isolated { root =>
      ExecutionCommitStore.resource(root).use { store =>
        for
          result <- store.admit(admission.copy(sessionId = "../outside"))
          observed <- store.observe("../outside", "run")
        yield
          assertEquals(result, ExecutionCommitResult.Rejected("invalid-admission"))
          assert(observed.isInstanceOf[ExecutionAdmissionObservation.ReviewRequired])
      }
    }
  }
end ExecutionCommitStoreSpec

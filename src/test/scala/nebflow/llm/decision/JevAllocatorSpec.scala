package nebflow.llm.decision

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import munit.CatsEffectSuite
import nebflow.core.jev.{CatalogEntry, JevAllocation, JevFallbackKind}
import nebflow.shared.PathUtil

import scala.concurrent.duration.*

/**
 * P3 methodology transfer: the state composition, the question set and the
 * membership rule.
 *
 * The transferred source is the 21x20-round scorer (`setscore_one.mjs`), and
 * the transfer is scoped to three things: the `state` template (`:59-67`,
 * preamble + brief + catalog section), the `need_`/`fit_` question pairs
 * (`:71-85`, one batched call, catalog order, the scorer's own wording), and
 * the membership rule `probabilities["yes"] >= 0.5` (`:86`).
 *
 * Lives in the `llm` package because the question and state builders are
 * `private[decision]` — the point of that visibility is that the wire shape
 * has exactly one producer.
 */
class JevAllocatorSpec extends CatsEffectSuite:

  override val munitIOTimeout = 120.seconds

  private val catalog = List(
    CatalogEntry("backend-dev", "Scala backend development"),
    CatalogEntry("visual-report", "human-facing reports and decks"),
    CatalogEntry("mail-ops", "outbound mail handling")
  )

  private def withTempDataRoot[A](f: os.Path => IO[A]): IO[A] =
    val original = PathUtil.dataRoot
    val tmp = os.temp.dir(prefix = "nb-jev-alloc-")
    PathUtil.setDataRoot(tmp)
    f(tmp).guarantee(IO(PathUtil.setDataRoot(original)) *> IO(os.remove.all(tmp)))

  // ── the question set (#2, #7) ──────────────────────────────────────────

  test("the question set is one batched call with need_/fit_ pairs") {
    val alloc = new JevAllocator(NoopProvider, 15000L)
    val qs = alloc.questionsFor(catalog)
    assertEquals(qs.size, catalog.size * 2, "one need_ + one fit_ per catalog entry")
    assertEquals(
      qs.map(_.id),
      List(
        "need_backend-dev", "fit_backend-dev",
        "need_visual-report", "fit_visual-report",
        "need_mail-ops", "fit_mail-ops"
      )
    )
    // need_ is a CHOICE question whose criteria is a MAP — the shape the
    // official pages specify and the on-disk scorer used.
    val need = qs.head.asInstanceOf[DecisionQuestion.Choice]
    assertEquals(need.criteria.keySet, Set("yes", "no"))
    // fit_ is an ORDERED score list, low -> high.
    val fit = qs(1).asInstanceOf[DecisionQuestion.Score]
    assertEquals(fit.criteria, List("poor", "partial", "good"))
  }

  test("the whole set rides ONE request (the batch property)") {
    val alloc = new JevAllocator(NoopProvider, 15000L)
    val body = JevWireCodec.requestBody(DecisionRequest("t", alloc.questionsFor(catalog)), "jev-latest")
    val questions = body.hcursor.downField("questions").focus.flatMap(_.asObject)
    assertEquals(questions.map(_.size), Some(6), "all six questions in one body")
  }

  // ── membership (#7) ────────────────────────────────────────────────────

  test("membership is probabilities[yes] >= 0.5 and is read from the choice answer") {
    val alloc = new JevAllocator(NoopProvider, 15000L)
    val resp = DecisionResponse(
      model = Some("jev-1.13.0"),
      answers = Map(
        "need_backend-dev" -> DecisionAnswer.Choice("yes", Some(1.0), Map("yes" -> 1.0, "no" -> 0.0)),
        // Exactly at the threshold: included (>= is the R2 main rule).
        "need_visual-report" -> DecisionAnswer.Choice("yes", Some(0.6), Map("yes" -> 0.5, "no" -> 0.5)),
        "need_mail-ops" -> DecisionAnswer.Choice("no", Some(1.0), Map("yes" -> 0.2, "no" -> 0.8))
      ),
      usage = None
    )
    assertEquals(alloc.select(resp, catalog), List("backend-dev", "visual-report"))
  }

  test("a fit score is never used as a membership threshold") {
    // The measured trap: using fit >= 1.0 as the threshold collapsed exactness
    // to 5%. Even a maximal fit score must not add a member whose need_ answer
    // says no.
    val alloc = new JevAllocator(NoopProvider, 15000L)
    val resp = DecisionResponse(
      model = None,
      answers = Map(
        "need_backend-dev" -> DecisionAnswer.Choice("no", Some(1.0), Map("yes" -> 0.0, "no" -> 1.0)),
        "fit_backend-dev" -> DecisionAnswer.Score(2, Some(1.0), Map("2" -> "good"), Map("2" -> 1.0))
      ),
      usage = None
    )
    assertEquals(alloc.select(resp, catalog), Nil, "a perfect fit must not override a 'not needed' answer")
  }

  test("a noul answer can never satisfy a need_ question") {
    // Structural: the accessor returns None across the type boundary, so a
    // noul — however high — cannot be read as a yes.
    val alloc = new JevAllocator(NoopProvider, 15000L)
    val resp = DecisionResponse(None, Map("need_backend-dev" -> DecisionAnswer.Noul(0.99)), None)
    assertEquals(alloc.select(resp, catalog), Nil)
    assertEquals(DecisionAnswer.pYes(DecisionAnswer.Noul(0.99)), None)
  }

  test("a missing need_ answer shrinks the set conservatively rather than granting") {
    val alloc = new JevAllocator(NoopProvider, 15000L)
    assertEquals(alloc.select(DecisionResponse(None, Map.empty, None), catalog), Nil)
  }

  // ── the empty catalog is a legal state, not a failure ──────────────────

  test("an empty catalog yields the empty set without spending a call") {
    val alloc = new JevAllocator(NoopProvider, 15000L)
    withTempDataRoot { tmp =>
      val before = NoopProvider.calls
      for
        out <- alloc.allocate("task", Nil)
        _ = assertEquals(out, JevAllocation.Allocated(Nil))
        _ = assertEquals(NoopProvider.calls, before, "an empty catalog must not spend a call")
        // No FALLBACK event: the empty catalog is a normal state (an empty set
        // is explicitly legal), so only the success record may exist.
        lines <- IO(
          if os.exists(tmp / "logs" / "jev-allocation.jsonl") then
            os.read(tmp / "logs" / "jev-allocation.jsonl").linesIterator.toList
          else Nil
        )
        _ = assert(lines.forall(_.contains("\"event\":\"allocated\"")), s"no fallback event expected: $lines")
      yield ()
    }
  }

  // ── fail-open at the allocator boundary (#3) ───────────────────────────

  test("a provider failure falls back and never escapes as an exception") {
    withTempDataRoot { tmp =>
      val failing = new DecisionProvider[IO]:
        def id: String = "typesafe-jev"
        def predict(req: DecisionRequest) = IO.pure(Left(DecisionError.Server("HTTP 500")))
      for
        out <- new JevAllocator(failing, 15000L).allocate("some task", catalog)
        _ = assert(out.isInstanceOf[JevAllocation.FellBack], s"expected a fallback, got: $out")
        lines <- IO(os.read(tmp / "logs" / "jev-allocation.jsonl").trim.linesIterator.toList)
        _ = assertEquals(lines.size, 1, "a fallback must leave exactly one visibility event")
      yield ()
    }
  }

  test("a provider timeout is classified as a timeout fallback") {
    withTempDataRoot { _ =>
      val timingOut = new DecisionProvider[IO]:
        def id: String = "typesafe-jev"
        def predict(req: DecisionRequest) = IO.pure(Left(DecisionError.Timeout("read timed out")))
      new JevAllocator(timingOut, 15000L).allocate("t", catalog).map { out =>
        assertEquals(out, JevAllocation.FellBack(JevFallbackKind.Timeout, "decision API timeout: read timed out"))
      }
    }
  }

  test("fallback kind is decided by the typed error, not by matching its wording") {
    // A Server error whose text happens to contain "timeout" must NOT be
    // classified as a timeout; and a Timeout whose text does not contain the
    // word must still be one. This is the guard for the string-sniffing trap.
    withTempDataRoot { _ =>
      val misleading = new DecisionProvider[IO]:
        def id: String = "typesafe-jev"
        def predict(req: DecisionRequest) = IO.pure(Left(DecisionError.Server("upstream timeout while reading")))
      val silent = new DecisionProvider[IO]:
        def id: String = "typesafe-jev"
        def predict(req: DecisionRequest) = IO.pure(Left(DecisionError.Timeout("read deadline exceeded")))
      for
        a <- new JevAllocator(misleading, 15000L).allocate("t", catalog)
        b <- new JevAllocator(silent, 15000L).allocate("t", catalog)
      yield
        assertEquals(a.asInstanceOf[JevAllocation.FellBack].kind, JevFallbackKind.Failure)
        assertEquals(b.asInstanceOf[JevAllocation.FellBack].kind, JevFallbackKind.Timeout)
    }
  }

  test("a thrown defect still lands on the fallback rather than the caller") {
    withTempDataRoot { _ =>
      val exploding = new DecisionProvider[IO]:
        def id: String = "typesafe-jev"
        def predict(req: DecisionRequest) = IO.raiseError(new RuntimeException("boom"))
      new JevAllocator(exploding, 15000L).allocate("t", catalog).map { out =>
        assert(out.isInstanceOf[JevAllocation.FellBack], s"a defect must not escape: $out")
      }
    }
  }

  test("a successful allocation records the chosen set (the #8 'action happened' evidence)") {
    withTempDataRoot { tmp =>
      val fixed = new DecisionProvider[IO]:
        def id: String = "typesafe-jev"
        def predict(req: DecisionRequest) = IO.pure(
          Right(
            DecisionResponse(
              None,
              Map(
                "need_backend-dev" -> DecisionAnswer.Choice("yes", None, Map("yes" -> 1.0)),
                "need_visual-report" -> DecisionAnswer.Choice("no", None, Map("yes" -> 0.0)),
                "need_mail-ops" -> DecisionAnswer.Choice("no", None, Map("yes" -> 0.0))
              ),
              None
            )
          )
        )
      for
        out <- new JevAllocator(fixed, 15000L).allocate("t", catalog)
        _ = assertEquals(out, JevAllocation.Allocated(List("backend-dev")))
        lines <- IO(os.read(tmp / "logs" / "jev-allocation.jsonl").trim.linesIterator.toList)
        _ = assert(lines.head.contains("\"event\":\"allocated\""), s"success must be recorded: ${lines.head}")
        _ = assert(lines.head.contains("backend-dev"), s"the chosen set must be recorded: ${lines.head}")
      yield ()
    }
  }

  // ── the state composition (§R-1: the catalog MUST reach the model) ──────

  test("the state carries the capability catalog, each entry as name + description") {
    // The regression this pins: `state` used to be the bare task text, so
    // `CatalogEntry.description` had ZERO consumers and the model judged a
    // package by its NAME alone — a different methodology from the one the
    // measured numbers came from, and invisible to a truncation-only spec.
    val seen = new java.util.concurrent.atomic.AtomicReference[String]("")
    val capture = capturing(seen)
    withTempDataRoot { _ =>
      new JevAllocator(capture, 15000L, stateCapChars = 100000).allocate("fix the Scala actor", catalog).map { _ =>
        val state = seen.get()
        for e <- catalog do
          assert(
            state.contains(s"### ${e.name}\n${e.description}"),
            s"catalog entry '${e.name}' must reach the model as a name+description block:\n$state"
          )
        // The block form is the transferred scorer's (`setscore_one.mjs:59-61`):
        // `### <name>` on its own line with the description under it.
        assert(state.contains("### backend-dev\nScala backend development"), s"block form:\n$state")
      }
    }
  }

  test("the catalog keeps catalog order and both section headers are present") {
    val seen = new java.util.concurrent.atomic.AtomicReference[String]("")
    withTempDataRoot { _ =>
      new JevAllocator(capturing(seen), 15000L, stateCapChars = 100000).allocate("t", catalog).map { _ =>
        val state = seen.get()
        assert(state.contains("=== INSTALLED PLUGIN CAPABILITY CATALOG ==="), s"catalog header missing:\n$state")
        assert(state.contains("=== TASK BRIEF ==="), s"brief header missing:\n$state")
        // Order is load-bearing: the scorer renders the catalog in file order,
        // and the question ids follow the same order.
        val positions = catalog.map(e => state.indexOf(s"### ${e.name}"))
        assertEquals(positions.sorted, positions, s"catalog entries must keep catalog order:\n$state")
      }
    }
  }

  test("the brief cap bounds the BRIEF and cannot crowd out the catalog") {
    // The cap is a brief budget, not a whole-state budget: a task text far over
    // the cap must still produce a state carrying every catalog description.
    // Composing the state as "truncate the whole thing" would drop the catalog
    // for exactly the long tasks that need allocation most.
    //
    // Asserted on the BRIEF SECTION, not on a global character count: the
    // preamble legitimately contains the probe character inside ordinary words
    // ("text", "executed"), so a whole-state count measures the wrong thing.
    val seen = new java.util.concurrent.atomic.AtomicReference[String]("")
    withTempDataRoot { _ =>
      new JevAllocator(capturing(seen), 15000L, stateCapChars = 100).allocate("x" * 5000, catalog).map { _ =>
        val state = seen.get()
        assertEquals(briefOf(state), "x" * 100, s"the brief must be capped at the budget:\n$state")
        for e <- catalog do
          assert(state.contains(e.description), s"the catalog must survive the brief cap ('${e.name}'):\n$state")
      }
    }
  }

  test("the question instructions are the transferred scorer's words, character for character") {
    // The instructions are model input: the scorer's sentences ARE the method.
    // Pinned verbatim so a later paraphrase fails here instead of silently
    // moving the methodology while leaving every other assertion green.
    val alloc = new JevAllocator(NoopProvider, 15000L)
    val qs = alloc.questionsFor(List(CatalogEntry("backend-dev", "Scala backend development")))
    assertEquals(
      qs.head.instructions,
      "Does executing this task's primary purpose REQUIRE the 'backend-dev' capability package - " +
        "i.e., the task cannot be properly executed without the capability it provides? " +
        "Judge this one package independently, by the task text alone; ignore boilerplate."
    )
    assertEquals(
      qs(1).instructions,
      "How well does the 'backend-dev' capability package fit this task's primary purpose, " +
        "by the task text alone?"
    )
  }

  test("the need_ criteria label is the transferred scorer's wording, not a paraphrase") {
    // `setscore_one.mjs:75-78` spells the no-label in full. The label is part of
    // the question the model answers, so a shortened variant is a methodology
    // change wearing the clothes of a copy-edit.
    val alloc = new JevAllocator(NoopProvider, 15000L)
    val need = alloc.questionsFor(catalog).head.asInstanceOf[DecisionQuestion.Choice]
    assertEquals(
      need.criteria,
      Map(
        "yes" -> Some("required for this task's primary purpose"),
        "no" -> Some("not required for this task's primary purpose")
      )
    )
  }

  test("the state handed over is capped so a huge task cannot blow the budget") {
    val seen = new java.util.concurrent.atomic.AtomicReference[String]("")
    val alloc = new JevAllocator(capturing(seen), 15000L, stateCapChars = 100)
    withTempDataRoot { _ =>
      alloc.allocate("x" * 5000, catalog).map { _ =>
        assertEquals(briefOf(seen.get()), "x" * 100, "the brief must be truncated to the cap")
      }
    }
  }

  /**
   * Extract the brief section from a composed `state`.
   *
   * Structural, not a whole-string character count: the preamble and the
   * catalog are composed AROUND the brief, so only the section between the two
   * headers answers "how long is the brief". A count over the whole state would
   * also pick up the probe character where it occurs inside ordinary prose.
   */
  private def briefOf(state: String): String =
    val start = state.indexOf(JevAllocator.BriefHeader)
    val end = state.indexOf(JevAllocator.CatalogHeader)
    assert(start >= 0 && end > start, s"both section headers must be present:\n$state")
    state.substring(start + JevAllocator.BriefHeader.length, end).stripLineEnd.stripPrefix("\n")

  /** A provider that records the `state` it was handed and answers emptily. */
  private def capturing(seen: java.util.concurrent.atomic.AtomicReference[String]): DecisionProvider[IO] =
    new DecisionProvider[IO]:
      def id: String = "typesafe-jev"
      def predict(req: DecisionRequest) =
        IO { seen.set(req.state); Right(DecisionResponse(None, Map.empty, None)) }

/** A provider that must never be called; counts calls to prove it. */
object NoopProvider extends DecisionProvider[IO]:
  @volatile var calls: Int = 0
  def id: String = "typesafe-jev"
  def predict(req: DecisionRequest): IO[Either[DecisionError, DecisionResponse]] =
    IO { calls += 1; Right(DecisionResponse(None, Map.empty, None)) }

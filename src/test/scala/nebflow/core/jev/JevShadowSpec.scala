package nebflow.core.jev

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import munit.CatsEffectSuite
import nebflow.llm.decision.*
import nebflow.shared.PathUtil

import scala.concurrent.duration.*

/**
 * P3 (Face B①) acceptance: declaration-duty semantics, the shadow evaluator,
 * and the declarative arming boundary.
 *
 * ==The P0 form this file pins (#8)==
 * The declaration duty moves from "the parameter key exists" to "an allocation
 * ACTION happened", and an EMPTY allocation counts as SATISFIED — because
 * `plugins=[]` is already an explicit legal terminal state on both consumption
 * paths (`prepareNodePlugins` returns `PluginPreparation.empty` for it;
 * `dispatchFaceCheck` returns a zero-cost `Right`). The one failing state is
 * an ABSENT allocation path, which is the risk the moved obligation targets.
 *
 * ==The offline evaluation==
 * The card's verification means for P3 is a scorer replay over the 20-round
 * jev-test2 samples with the exact/macro baseline. That corpus lives OUTSIDE
 * this repo, so the historical comparison is reported as an open item with the
 * reproduction command rather than silently claimed. What IS verified here is
 * the methodology the replay would use (see `JevAllocatorSpec` in the llm
 * package) plus the shadow's own bookkeeping.
 */
class JevShadowSpec extends CatsEffectSuite:

  override val munitIOTimeout = 120.seconds

  private def withTempDataRoot[A](f: os.Path => IO[A]): IO[A] =
    val original = PathUtil.dataRoot
    val tmp = os.temp.dir(prefix = "nb-jev-shadow-")
    PathUtil.setDataRoot(tmp)
    f(tmp).guarantee(IO(PathUtil.setDataRoot(original)) *> IO(os.remove.all(tmp)))

  private val catalog = List(
    CatalogEntry("backend-dev", "Scala backend development"),
    CatalogEntry("visual-report", "human-facing reports and decks"),
    CatalogEntry("mail-ops", "outbound mail handling")
  )

  private def yesProvider(yes: Set[String]): DecisionProvider[IO] = new DecisionProvider[IO]:
    def id: String = "typesafe-jev"
    def predict(req: DecisionRequest) =
      val answers = catalog.map { e =>
        val need = if yes.contains(e.name) then "yes" else "no"
        s"need_${e.name}" -> (DecisionAnswer.Choice(need, None, Map("yes" -> (if need == "yes" then 1.0 else 0.0))) : DecisionAnswer)
      }.toMap
      IO.pure(Right(DecisionResponse(None, answers, None)))

  // ── #8 declaration-duty semantics ──────────────────────────────────────

  test("an empty allocation SATISFIES the duty (#8: empty means declared-empty)") {
    val duty = JevDeclarationDuty.evaluate(allocated = true, capabilities = Nil, fallbackReason = None)
    assertEquals(duty, JevDeclarationDuty.Allocated(empty = true))
    assert(JevDeclarationDuty.isSatisfied(duty), "plugins=[] is an explicit legal terminal state")
  }

  test("an allocation that ran and produced a set satisfies the duty") {
    val duty = JevDeclarationDuty.evaluate(allocated = true, capabilities = List("backend-dev"), fallbackReason = None)
    assertEquals(duty, JevDeclarationDuty.Allocated(empty = false))
    assert(JevDeclarationDuty.isSatisfied(duty))
  }

  test("an ABSENT allocation path is the one failing state — the moved obligation") {
    val duty = JevDeclarationDuty.evaluate(allocated = false, capabilities = Nil, fallbackReason = None)
    assertEquals(duty, JevDeclarationDuty.AllocationMissing)
    assert(!JevDeclarationDuty.isSatisfied(duty), "the gate now catches 'nobody allocated', not 'no key'")
  }

  test("a fallback still counts as the allocation action having happened (fail-open #3)") {
    val duty = JevDeclarationDuty.evaluate(allocated = true, capabilities = Nil, fallbackReason = Some("timeout"))
    assertEquals(duty, JevDeclarationDuty.AllocatedViaFallback("timeout"))
    assert(JevDeclarationDuty.isSatisfied(duty), "a fallback is the adjudicated outcome, not a missing declaration")
  }

  test("the legacy duty is key presence — the two rules differ exactly where #8 says they do") {
    // The divergence this whole shadow exists to measure: a node that declared
    // nothing but WAS allocated (empty) flips from unsatisfied to satisfied.
    assert(!JevLegacyDeclarationDuty.isSatisfied(keyProvided = false), "legacy: no key => refused")
    assert(JevDeclarationDuty.isSatisfied(JevDeclarationDuty.Allocated(empty = true)), "new: allocated-empty => satisfied")
    assert(!JevDeclarationDuty.isSatisfied(JevDeclarationDuty.AllocationMissing))
    // Both agree on the ordinary cases.
    assert(JevLegacyDeclarationDuty.isSatisfied(keyProvided = true))
    assert(
      JevDeclarationDuty.isSatisfied(
        JevDeclarationDuty.evaluate(allocated = true, capabilities = List("x"), fallbackReason = None)
      )
    )
  }

  // ── the shadow: evidence without authority ─────────────────────────────

  test("the shadow records both arms and flags divergence, changing nothing") {
    withTempDataRoot { tmp =>
      val shadow = new JevShadowEvaluator(Some(new JevAllocator(yesProvider(Set("backend-dev")), 15000L)), enabled = true)
      for
        rec <- shadow.evaluate("n-1", "task", catalog, declaredCapabilities = Nil, keyProvided = false)
        _ = assert(rec.isDefined, "the shadow must produce a record when enabled")
        r = rec.get
        _ = assertEquals(r.jevCapabilities, List("backend-dev"))
        _ = assertEquals(r.declaredCapabilities, Nil)
        _ = assertEquals(r.legacySatisfied, false)
        _ = assertEquals(r.jevSatisfied, true)
        _ = assert(r.divergent, "the two arms differ here, and that is the measured signal")
        all <- shadow.readAll
        _ = assertEquals(all.size, 1, "exactly one record on disk")
      yield ()
    }
  }

  test("agreement is recorded as non-divergence when the declared set matches") {
    withTempDataRoot { _ =>
      val shadow = new JevShadowEvaluator(Some(new JevAllocator(yesProvider(Set("backend-dev")), 15000L)), enabled = true)
      shadow.evaluate("n-1", "t", catalog, declaredCapabilities = List("backend-dev"), keyProvided = true).map { rec =>
        val r = rec.get
        assertEquals(r.legacySatisfied, true)
        assertEquals(r.jevSatisfied, true)
        assert(!r.divergent, "the arms agree here")
      }
    }
  }

  test("a disabled shadow writes nothing") {
    withTempDataRoot { _ =>
      val shadow = new JevShadowEvaluator(Some(new JevAllocator(yesProvider(Set.empty), 15000L)), enabled = false)
      for
        rec <- shadow.evaluate("n", "t", catalog, Nil, keyProvided = true)
        _ = assertEquals(rec, None)
        all <- shadow.readAll
        _ = assertEquals(all, Nil)
      yield ()
    }
  }

  test("with no allocator available the shadow records nothing rather than a half record") {
    withTempDataRoot { _ =>
      val shadow = new JevShadowEvaluator(None, enabled = true)
      shadow.evaluate("n", "t", catalog, Nil, keyProvided = true).map(rec => assertEquals(rec, None))
    }
  }

  test("the shadow never breaks a create: a failing allocator produces a record and raises nothing") {
    withTempDataRoot { _ =>
      val exploding = new DecisionProvider[IO]:
        def id: String = "typesafe-jev"
        def predict(req: DecisionRequest) = IO.raiseError(new RuntimeException("shadow boom"))
      val shadow = new JevShadowEvaluator(Some(new JevAllocator(exploding, 15000L)), enabled = true)
      for
        rec <- shadow.evaluate("n", "t", catalog, Nil, keyProvided = true)
        // The allocator already fails open, so a record IS produced — the point
        // is that no exception escapes either way.
        _ = assert(rec.isDefined, "a failed allocation is still an allocation action, so a record exists")
        r = rec.get
        _ = assert(r.fallbackReason.isDefined, s"the fallback must be recorded: $r")
      yield ()
    }
  }

  // ── the declarative stub boundary ──────────────────────────────────────

  test("the arming preconditions are declared, and the point is shadow-only") {
    assert(JevAllocationStub.isShadowOnly, "the allocation point must not be armed in this batch")
    assertEquals(JevAllocationStub.ArmingPreconditions.size, 4)
    assert(JevAllocationStub.ArmingPreconditions.exists(_.contains("§16")), "the §16 gate must be named")
    assert(JevAllocationStub.ArmingPreconditions.exists(_.contains("P4")), "the P4 dependency must be named")
  }

  test("the stub's observation never returns an authority") {
    // `observe` returns Unit on purpose: a caller cannot branch on it, so the
    // stub cannot silently become the allocation point.
    withTempDataRoot { _ =>
      JevAllocationStub.observe("n", wouldAllocate = true).map(_ => ())
    }
  }

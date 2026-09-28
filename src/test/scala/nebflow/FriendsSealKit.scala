package nebflow

import cats.effect.IO
import cats.syntax.all.*
import nebflow.core.FriendsSeal

/** friendseal batch (2026-09-25) — shared spec seam for the friends-seal latch.
  *
  *  - `expectedNebulaSize`: the SINGLE derivation point for the flag-aware
  *    delivery-size assertion (plan card §九 discipline): the expectation is
  *    computed from the SAME delivered-set snapshot that is being asserted —
  *    constant − (sealed ? 1 : 0). Never a bare number, never a relaxed
  *    assertion, and never a second latch read between the fact and its
  *    expectation (race-free against suites that lift the seal concurrently).
  *  - `withUnsealed` / `withUnsealedSync`: ref-counted latch lift for behavior
  *    specs that exercise the friend/group/local legs. Concurrency-safe under
  *    sbt's parallel suites — a plain boolean setter would let one suite's
  *    restore flip the latch under another still-running suite. Every lift is
  *    restored via guarantee/finally (all paths).
  */
object FriendsSealKit:

  /** constant − (sealed ? 1 : 0), derived from the delivered snapshot itself. */
  def expectedNebulaSize(delivered: Set[String]): Int =
    val constant = nebflow.agent.AgentCore.NebulaOrchestrationToolsExpectedSize
    if delivered.contains("ListFriends") then constant else constant - 1

  /** Lift the seal for the duration of `ioa` (ref-counted, restored on every
    * path). */
  def withUnsealed[A](ioa: IO[A]): IO[A] =
    IO(FriendsSeal.testUnseal()) *> ioa.guarantee(IO(FriendsSeal.testReseal()))

  /** Non-IO variant for plain FunSuite behavior probes. */
  def withUnsealedSync[A](a: => A): A =
    FriendsSeal.testUnseal()
    try a
    finally FriendsSeal.testReseal()
end FriendsSealKit

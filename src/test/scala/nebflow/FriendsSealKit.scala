package nebflow

import cats.effect.IO
import cats.syntax.all.*
import nebflow.core.FriendsSeal

/**
 * friendseal batch (2026-09-25) — shared spec seam for the friends-seal latch.
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

  /**
   * constant − (friendsSealed ? 1 : 0) − (imageGenSealed ? 1 : 0)，全部由交付
   * 快照自身推导（presence-based，零裸数字、零二次读锁——imagegen 双层批
   * 2026-10-24 把 generate_image 的编译期 seal 剥离面并进了同一个单点推导：
   * 静态集与件数常量不动，seal 状态只在这个消费点读一次）。
   */
  def expectedNebulaSize(delivered: Set[String]): Int =
    val constant = nebflow.agent.AgentCore.RootOrchestrationToolsExpectedSize
    val friends = if delivered.contains("ListFriends") then 0 else 1
    val imageGen = if delivered.contains("generate_image") then 0 else 1
    constant - friends - imageGen

  /**
   * Lift the seal for the duration of `ioa` (ref-counted, restored on every
   * path).
   */
  def withUnsealed[A](ioa: IO[A]): IO[A] =
    IO(FriendsSeal.testUnseal()) *> ioa.guarantee(IO(FriendsSeal.testReseal()))

  /** Non-IO variant for plain FunSuite behavior probes. */
  def withUnsealedSync[A](a: => A): A =
    FriendsSeal.testUnseal()
    try a
    finally FriendsSeal.testReseal()
end FriendsSealKit

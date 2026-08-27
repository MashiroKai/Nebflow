package nebflow.core.tools

import munit.FunSuite
import cats.effect.IO
import cats.effect.unsafe.implicits.global

import java.util.UUID
import scala.concurrent.duration.*

/**
 * 2026-08-28 restart 恢复修复：Bash shell 会话在 restart/Stop 杀进程后必须可
 * 惰性重建（slideblocks Frontend restart 后无法跑命令、收尾卡死的根因）。
 *
 * 根因：killSessionProcesses（#391 机制 E）只杀进程 + isAlive=false，会话滞留
 * ShellSession 注册表 map → 恢复后 forSession 命中 Some(死) → touch 原样返回 →
 * 首个 Bash 调用 checkAlive 抛 "Session has been destroyed"，永不自愈。
 *
 * 修复两层（变异验红各自独立）：
 *   A. killSessionProcesses 从注册表移除（与 destroySession 对称）——根因修复；
 *      恢复路径走 get-or-create 的 None 分支惰性重建。
 *   B. doGetOrCreate 命中死会话时替换重建——防御自愈（任何路径留下的死会话）。
 *
 * 注意：ShellSession 注册表是 object 单例——每个测试用唯一 sessionId，防跨
 * 测试污染（TeamSessionRegistry 同族教训）。
 */
class ShellSessionRestartSpec extends FunSuite:

  private def sid(prefix: String): String =
    s"$prefix-${UUID.randomUUID().toString.take(8)}"

  override def munitTimeout: scala.concurrent.duration.FiniteDuration =
    scala.concurrent.duration.FiniteDuration(60, "s")

  // ---------- A：killSessionProcesses 后惰性重建（根因修复） ----------

  test("restart 恢复：killSessionProcesses 后 forSession 返回新活会话，首个 Bash 可用"):
    val id = sid("rs-a")
    val program = for
      s1 <- ShellSession.forSession(id)
      _ <- s1.execute("echo warmup", 30.seconds) // 确保旧会话真实存在且可用
      _ <- ShellSession.killSessionProcesses(Some(id)) // AgentActor:678 restart/Stop 链路同一调用
      s2 <- ShellSession.forSession(id) // member checkpoint 恢复后的首个 Bash
      dead <- s2.isDead
      res <- s2.execute("echo restart-ok", 30.seconds)
    yield (s1, s2, dead, res)

    val (s1, s2, dead, res) = program.unsafeRunSync()
    assert(s1 ne s2, "post-restart session must be a NEW session, not the killed one")
    assert(!dead, "post-restart session must be alive")
    assert(res.stdout.contains("restart-ok"), s"first Bash after restart must succeed: ${res.stdout.take(80)}")

  test("killSessionProcesses(None) 与未知 id：幂等 no-op"):
    (ShellSession.killSessionProcesses(None) *>
      ShellSession.killSessionProcesses(Some(sid("rs-unknown")))).unsafeRunSync()
    // 不抛错即通过

  // ---------- B：滞留死会话自愈（防御层，独立于 A） ----------

  test("滞留死会话自愈：kill() 不出 map 时 forSession 替换重建"):
    val id = sid("rs-b")
    val program = for
      s1 <- ShellSession.forSession(id)
      _ <- s1.kill() // 直接 kill（不出 map）——模拟任意路径留下的滞留死会话
      deadConfirmed <- s1.isDead
      _ <- IO.println(s"[SPEC] after kill: s1.isDead=$deadConfirmed obj=${System.identityHashCode(s1)}")
      s2 <- ShellSession.forSession(id) // 必须自愈，而非返回死会话
      _ <- IO.println(s"[SPEC] healed: s2 obj=${System.identityHashCode(s2)}")
      dead2 <- s2.isDead
      res <- s2.execute("echo heal-ok", 30.seconds)
    yield (s1, deadConfirmed, s2, dead2, res)

    val (s1, deadConfirmed, s2, dead2, res) = program.unsafeRunSync()
    assert(deadConfirmed, "precondition: the old session must be dead")
    assert(s1 ne s2, "registry must replace the dead session, not return it")
    assert(!dead2, "replacement session must be alive")
    assert(res.stdout.contains("heal-ok"), s"healed session must execute: ${res.stdout.take(80)}")

  // ---------- 正常路径回归 ----------

  test("正常复用：活会话两次 forSession 返回同一实例"):
    val id = sid("rs-c")
    val program = for
      s1 <- ShellSession.forSession(id)
      s2 <- ShellSession.forSession(id)
    yield (s1, s2)
    val (s1, s2) = program.unsafeRunSync()
    assert(s1 eq s2, "live session must be reused (touch), not recreated")

  test("destroySession 后 forSession 重建（既有语义回归）"):
    val id = sid("rs-d")
    val program = for
      s1 <- ShellSession.forSession(id)
      _ <- ShellSession.destroySession(id)
      s2 <- ShellSession.forSession(id)
      dead <- s2.isDead
    yield (s1, s2, dead)
    val (s1, s2, dead) = program.unsafeRunSync()
    assert(s1 ne s2, "post-destroy forSession must rebuild")
    assert(!dead, "rebuilt session must be alive")

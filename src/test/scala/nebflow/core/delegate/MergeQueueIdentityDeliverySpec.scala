package nebflow.core.delegate

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import munit.CatsEffectSuite
import nebflow.shared.PathUtil

import scala.collection.mutable.ListBuffer

/**
 * P1 投递身份化批（2026-11-05）验收 spec：**重启后可直接向持窗者投递**。
 *
 * 判红逻辑（逐条对应作者判据）：
 *   - ① 成员**不带任何进程内闭包**（P1 已把 `internal` / `external` 删掉），
 *     放行腿仍把合并指令投给 `ownerSessionId`——身份是账本数据。
 *   - ② **重启模拟**（`dropMemoryStateForTest()`：内存清零、盘上账在）之后，
 *     同一成员再走一次放行，**照样投得出去**，收件身份与重启前一致。
 *     （改前：闭包随进程蒸发 ⇒ 这条腿只会落到 `Deferred` / `owner-gone`。）
 *   - ③ 投递端口未装配 ⇒ `Failed`（owner-gone 腿），**绝不假装投出去了**。
 */
class MergeQueueIdentityDeliverySpec extends CatsEffectSuite:

  private val tempRoot = PathUtil.dataRoot / "spec-tmp-merge-identity"

  override def beforeEach(context: munit.BeforeEach): Unit =
    MergeQueue.reset()

  override def afterEach(context: munit.AfterEach): Unit =
    MergeQueue.reset()
    MergeQueueTestPorts.clear()
    MergeQueue.OwnerGonePort.clear()
    MergeQueue.BlockNotifyPort.clear()

  private def mkRepo(dir: os.Path, branch: String): os.Path =
    if os.exists(dir) then os.remove.all(dir)
    os.makeDir.all(dir)
    os.proc("git", "init", "-b", "main", dir.toString).call(check = true, stdout = os.Pipe, stderr = os.Pipe)
    def g(args: String*): os.CommandResult =
      os.proc(Seq("git", "-C", dir.toString) ++ args).call(check = true, stdout = os.Pipe, stderr = os.Pipe)
    g("config", "user.email", "spec@nebflow.local")
    g("config", "user.name", "spec")
    g("commit", "--allow-empty", "-m", "init")
    g("checkout", "-b", branch)
    os.write(dir / "deliverable.txt", "deliverable")
    g("add", ".")
    g("commit", "-m", "deliverable")
    g("checkout", "main")
    dir

  private def memberOf(delegateId: String, repo: os.Path, owner: String): MergeQueue.Member =
    MergeQueue.Member(
      delegateId = delegateId,
      branch = s"delegate/$delegateId",
      baseWs = repo.toString,
      baseBranch = "main",
      worktree = false,
      projectKey = s"p-identity-$delegateId",
      taskId = Some("71"),
      parentSessionId = "nebula-root",
      description = s"identity delivery $delegateId",
      readyAt = 1L,
      declaredAt = Some(1_000L),
      declareSource = Some(MergeDeclaration.Source.ReportMerge),
      channelKind = "identity",
      // 唯一投递依据：收件身份（持久数据）。
      ownerSessionId = owner
    )

  test("① 无闭包放行：指令按 ownerSessionId 投给属主（不是任何 actor ref）") {
    val sent = ListBuffer.empty[MergeQueueTestPorts.Sent]
    MergeQueueTestPorts.recording(s => sent += s)
    val repo = mkRepo(tempRoot / "ident-1", branch = "delegate/d-id-1")
    val m = memberOf("d-id-1", repo, owner = "owner-session-1")
    MergeQueue.enqueue(m)
    val deliveries = sent.toList.filter(_.kind == "merge-turn")
    assertEquals(deliveries.size, 1, s"exactly one merge-turn was delivered (got ${sent.toList.map(_.kind)})")
    assertEquals(deliveries.head.ownerSessionId, "owner-session-1", "the delivery target is the OWNER IDENTITY, not an actor ref")
    assert(deliveries.head.brief.contains("merge"), s"the brief carries the merge instruction: ${deliveries.head.brief.take(120)}")
    assertEquals(MergeQueue.find("d-id-1").map(_.state), Some(MergeQueue.State.TurnGranted), "the window is held by that member")
  }

  test("② 重启后照样投得出去：内存清零（闭包/进程态全没）→ 再放行 → 同一身份收到指令") {
    val sent = ListBuffer.empty[MergeQueueTestPorts.Sent]
    MergeQueueTestPorts.recording(s => sent += s)
    val repo = mkRepo(tempRoot / "ident-2", branch = "delegate/d-id-2")
    val m = memberOf("d-id-2", repo, owner = "owner-session-2")
    MergeQueue.enqueue(m)
    val beforeRestart = sent.toList.filter(_.kind == "merge-turn")
    assertEquals(beforeRestart.size, 1, "first delivery before the restart")
    // ── 重启模拟：进程内一切归零，只剩盘上账本（身份是账本字段） ──
    MergeQueue.dropMemoryStateForTest()
    val reloaded = MergeQueue.find("d-id-2")
    assertEquals(reloaded.map(_.state), Some(MergeQueue.State.TurnGranted), "the held window reloads from disk")
    assertEquals(reloaded.map(_.ownerSession), Some("owner-session-2"), "the delivery identity survives the restart (persisted data, not a closure)")
    // 把窗让回后重新放行：这次投递发生在「重启之后」，没有任何进程内闭包可用。
    MergeQueue.requeueForLane("d-id-2", "spec: force a second grant after the restart")
    MergeQueue.pump("p-identity-d-id-2")
    val afterRestart = sent.toList.filter(_.kind == "merge-turn")
    assertEquals(afterRestart.size, 2, s"a second merge-turn landed AFTER the restart (timeline: ${sent.toList.map(_.kind)})")
    assertEquals(afterRestart.last.ownerSessionId, "owner-session-2", "post-restart delivery goes to the SAME persisted identity")
    assertEquals(MergeQueue.find("d-id-2").map(_.state), Some(MergeQueue.State.TurnGranted), "the post-restart grant holds the window again")
  }

  test("③ 端口未装配 / 会话已删：投不出去就如实失败（owner-gone），绝不假装送达") {
    val escalations = ListBuffer.empty[(String, String)]
    MergeQueue.OwnerGonePort.install((m, reason) => IO { escalations += ((m.delegateId, reason)) })
    // 端口未装配 = 投不出去
    MergeQueueTestPorts.clear()
    val repo = mkRepo(tempRoot / "ident-3", branch = "delegate/d-id-3")
    val m = memberOf("d-id-3", repo, owner = "owner-session-3")
    MergeQueue.enqueue(m)
    assertEquals(MergeQueue.find("d-id-3").map(_.state), Some(MergeQueue.State.OwnerGone), "an undeliverable turn closes as owner-gone (never silently parked)")
    assert(
      escalations.exists(_._1 == "d-id-3"),
      s"the owner-gone close-out escalates through OwnerGonePort: ${escalations.toList}"
    )
    // 端口在装配且投递返回失败（会话已删）——同样判 owner-gone
    MergeQueueTestPorts.install(deliverResult = Left("parent-session-gone"))
    val repo2 = mkRepo(tempRoot / "ident-3b", branch = "delegate/d-id-3b")
    MergeQueue.enqueue(memberOf("d-id-3b", repo2, owner = "owner-session-3b"))
    assertEquals(MergeQueue.find("d-id-3b").map(_.state), Some(MergeQueue.State.OwnerGone), "a delivery failure (session deleted) also closes as owner-gone")
  }
end MergeQueueIdentityDeliverySpec

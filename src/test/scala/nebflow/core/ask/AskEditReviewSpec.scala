package nebflow.core.ask

import cats.effect.IO
import io.circe.JsonObject
import io.circe.syntax.*
import munit.CatsEffectSuite
import nebflow.core.tools.{AskEditTool, AskWriteTool, ToolContext}
import nebflow.core.ask.AskEditReviews.ResolveResult

/**
 * canvas-inline-diff-review 绿面：直接落盘 + 聚合审阅生命周期（apply →
 * accept/reject（全部/逐块）/dismiss / stale 显式拒 / rebase）+ 路径纪律 +
 * 工具面身份闸。
 */
final class AskEditReviewSpec extends CatsEffectSuite:

  private val original = "alpha\nbeta\ngamma\n"

  private def setup(name: String, content: String = original): (os.Path, os.Path, AskSessionRegistry, AskBinding) =
    val root = os.Path(os.temp.dir(prefix = s"ask-review-$name-").toNIO.toRealPath())
    val dir = root / "documents"
    os.makeDir.all(dir)
    val file = dir / "notes.md"
    os.write(file, content)
    val registry = new AskSessionRegistry(root)
    val binding = registry.bind(file.toString, file.last).unsafeRunSync()
    AskEditReviews.resetForTest(binding.id)
    (root, dir, registry, binding)

  // ── 直接落盘 + 聚合审阅 ──────────────────────────────────────────────────
  test("applyChange writes the disk immediately and records the aggregate review") {
    val (_, _, _, binding) = setup("apply")
    val file = os.Path(binding.file)
    AskEditReviews
      .applyChange(binding.id, binding, binding.file, "ask_write", s => Right(s.replace("beta", "BETA")))
      .map {
        case Right(review) =>
          assertEquals(os.read(file), "alpha\nBETA\ngamma\n", "apply must write the disk immediately (Cursor-style)")
          assertEquals(review.baseText, original, "review base = pre-edit snapshot")
          assertEquals(review.currentText, "alpha\nBETA\ngamma\n")
          assertEquals(review.hunks.length, 1, "one reviewable block")
          assertEquals(review.hunks.head.added, 1)
          assertEquals(review.hunks.head.removed, 1)
          assertEquals(AskEditReviews.reviewFor(binding.id).map(_.changes.length), Some(1))
        case Left(why) => fail(why)
      }
  }

  test("two applies aggregate: base = original, current = latest, chain counted") {
    val (_, _, _, binding) = setup("chain")
    val file = os.Path(binding.file)
    val run = for
      one <- AskEditReviews.applyChange(
        binding.id,
        binding,
        binding.file,
        "ask_write",
        s => Right(s.replace("beta", "BETA"))
      )
      two <- AskEditReviews.applyChange(
        binding.id,
        binding,
        binding.file,
        "ask_write",
        s => Right(s.replace("gamma", "GAMMA"))
      )
    yield (one, two)
    run.map { case (one, two) =>
      assert(one.isRight, one.toString)
      two match
        case Right(review) =>
          assertEquals(os.read(file), "alpha\nBETA\nGAMMA\n")
          assertEquals(review.baseText, original, "base stays the chain origin")
          assertEquals(review.currentText, "alpha\nBETA\nGAMMA\n")
          assertEquals(review.changes.length, 2)
          assertEquals(review.hunks.length, 1, "adjacent edits (0 unchanged lines between) merge into one block")
        case Left(why) => fail(why)
    }
  }

  test("hunksOf merges deltas separated by <= gap and splits beyond it") {
    // l1/l2 相邻改动（一个 delta）与 l6 间隔 3 行未变 → 分块。
    val base = "l1\nl2\nl3\nl4\nl5\nl6\nl7\nl8\n"
    val curr = "L1\nL2\nl3\nl4\nl5\nL6\nl7\nl8\n"
    val hunks = AskEditReviews.hunksOf(base, curr)
    assertEquals(hunks.map(_.id), List("h0", "h1"), "gap of 3 unchanged lines splits blocks")
    // a 与 b 间隔 1 行未变（≤ gap）→ 并块。
    val merged = AskEditReviews.hunksOf("a\nx\nb\n", "A\nx\nB\n")
    assertEquals(merged.map(_.id), List("h0"), "deltas separated by 1 unchanged line merge into one block")
  }

  // ── resolve：全部/逐块 ───────────────────────────────────────────────────
  test("accept-all finalizes with zero writes (disk keeps the applied content)") {
    val (_, _, _, binding) = setup("accept-all")
    val file = os.Path(binding.file)
    val run = for
      applied <- AskEditReviews.applyChange(
        binding.id,
        binding,
        binding.file,
        "ask_write",
        s => Right(s.replace("gamma", "GAMMA"))
      )
      accepted <- AskEditReviews.accept(binding.id, None)
    yield (applied, accepted)
    run.map { case (applied, accepted) =>
      assert(applied.isRight, applied.toString)
      accepted match
        case Right(ResolveResult.Cleared(_)) =>
          assertEquals(os.read(file), "alpha\nbeta\nGAMMA\n", "accept-all keeps the applied content")
          assertEquals(AskEditReviews.reviewFor(binding.id), None, "review cleared")
        case other => fail(s"unexpected: $other")
    }
  }

  test("reject-all reverts the file to the review base") {
    val (_, _, _, binding) = setup("reject-all")
    val file = os.Path(binding.file)
    val run = for
      applied <- AskEditReviews.applyChange(
        binding.id,
        binding,
        binding.file,
        "ask_write",
        s => Right(s.replace("alpha", "ALPHA") + "delta\n")
      )
      rejected <- AskEditReviews.reject(binding.id, None)
    yield (applied, rejected)
    run.map { case (applied, rejected) =>
      assert(applied.isRight, applied.toString)
      rejected match
        case Right(ResolveResult.Cleared(_)) =>
          assertEquals(os.read(file), original, "reject-all must revert to base")
          assertEquals(AskEditReviews.reviewFor(binding.id), None)
        case other => fail(s"unexpected: $other")
    }
  }

  // 两块几何：old = 7 行（alpha + 5 个隔离行 + 尾空行），改首行 + 尾部追加，
  // 中间 5 行未变 > HunkMergeGap(2) ⇒ 两个独立块 h0（首行）/h1（尾部）。
  private val twoBlockOld = "alpha\nbeta\ngamma\ngap1\ngap2\ngap3\n"
  private val twoBlockNew = "ALPHA\nbeta\ngamma\ngap1\ngap2\ngap3\ndelta\n"

  test("accept(hunk) marks that block reviewed without writing; the rest stays pending") {
    val (_, _, _, binding) = setup("accept-hunk", twoBlockOld)
    val file = os.Path(binding.file)
    val run = for
      applied <- AskEditReviews.applyChange(binding.id, binding, binding.file, "ask_write", _ => Right(twoBlockNew))
      accepted <- applied.fold(_ => IO.pure(Left("?")), _ => AskEditReviews.accept(binding.id, Some("h0")))
    yield (applied, accepted)
    run.map { case (applied, accepted) =>
      assert(applied.isRight, applied.toString)
      accepted match
        case Right(ResolveResult.Kept(review)) =>
          assertEquals(os.read(file), twoBlockNew, "accept(hunk) performs zero disk writes")
          assertEquals(
            review.baseText,
            "ALPHA\nbeta\ngamma\ngap1\ngap2\ngap3\n",
            "base advanced past the accepted block"
          )
          assertEquals(review.hunks.map(_.id), List("h0"), "only the tail block remains pending")
        case other => fail(s"unexpected: $other")
    }
  }

  test("reject(hunk) reverts exactly that block on disk and keeps the rest pending") {
    val (_, _, _, binding) = setup("reject-hunk", twoBlockOld)
    val file = os.Path(binding.file)
    val run = for
      applied <- AskEditReviews.applyChange(binding.id, binding, binding.file, "ask_write", _ => Right(twoBlockNew))
      rejected <- applied.fold(_ => IO.pure(Left("?")), _ => AskEditReviews.reject(binding.id, Some("h0")))
    yield (applied, rejected)
    run.map { case (applied, rejected) =>
      assert(applied.isRight, applied.toString)
      rejected match
        case Right(ResolveResult.Kept(review)) =>
          assertEquals(
            os.read(file),
            "alpha\nbeta\ngamma\ngap1\ngap2\ngap3\ndelta\n",
            "first block reverted, tail addition kept"
          )
          assertEquals(review.currentText, "alpha\nbeta\ngamma\ngap1\ngap2\ngap3\ndelta\n")
          assertEquals(review.hunks.map(_.id), List("h0"), "the tail block remains pending")
        case other => fail(s"unexpected: $other")
    }
  }

  test("accepting the last pending hunk clears the review") {
    val (_, _, _, binding) = setup("last-hunk")
    val run = for
      applied <- AskEditReviews.applyChange(binding.id, binding, binding.file, "ask_write", s => Right(s + "delta\n"))
      accepted <- applied.fold(_ => IO.pure(Left("?")), _ => AskEditReviews.accept(binding.id, Some("h0")))
    yield accepted
    run.map { accepted =>
      accepted match
        case Right(ResolveResult.Cleared(_)) => assertEquals(AskEditReviews.reviewFor(binding.id), None)
        case other => fail(s"unexpected: $other")
    }
  }

  // ── 过期/并发闸（显式冲突，绝不静默覆盖）──────────────────────────────────
  test("accept and reject both refuse explicitly when the file changed during review; dismiss is the escape") {
    val (_, _, _, binding) = setup("stale")
    val file = os.Path(binding.file)
    val run = for
      applied <- AskEditReviews.applyChange(
        binding.id,
        binding,
        binding.file,
        "ask_write",
        s => Right(s.replace("alpha", "ALPHA"))
      )
      _ <- IO.blocking(os.write.over(file, "externally changed\n")) // 审阅期间外部改动
      staleAccept <- applied.fold(_ => IO.pure(Left("?")), _ => AskEditReviews.accept(binding.id, None))
      staleReject <- applied.fold(_ => IO.pure(Left("?")), _ => AskEditReviews.reject(binding.id, None))
      _ <- IO {
        assert(staleAccept.left.exists(_.contains("stale")), staleAccept.toString)
        assert(staleReject.left.exists(_.contains("stale")), staleReject.toString)
        assertEquals(os.read(file), "externally changed\n", "stale actions must not overwrite the external change")
        assert(AskEditReviews.reviewFor(binding.id).isDefined, "review survives a stale refusal (retry path)")
      }
      dismissed <- AskEditReviews.dismiss(binding.id)
    yield dismissed
    run.map { dismissed =>
      dismissed match
        case Right(ResolveResult.Cleared(_)) =>
          assertEquals(os.read(file), "externally changed\n", "dismiss keeps the on-disk state")
          assertEquals(AskEditReviews.reviewFor(binding.id), None)
        case other => fail(s"unexpected: $other")
    }
  }

  test("an apply after an external change rebases the review base") {
    val (_, _, _, binding) = setup("rebase")
    val file = os.Path(binding.file)
    val run = for
      one <- AskEditReviews.applyChange(
        binding.id,
        binding,
        binding.file,
        "ask_write",
        s => Right(s.replace("beta", "BETA"))
      )
      _ <- IO.blocking(os.write.over(file, "externally changed\n"))
      two <- AskEditReviews.applyChange(
        binding.id,
        binding,
        binding.file,
        "ask_write",
        s => Right(s.replace("changed", "CHANGED"))
      )
    yield (one, two)
    run.map { case (one, two) =>
      assert(one.isRight, one.toString)
      two match
        case Right(review) =>
          assertEquals(os.read(file), "externally CHANGED\n")
          assertEquals(review.baseText, "externally changed\n", "base rebases to the externally modified content")
        case Left(why) => fail(why)
    }
  }

  test("unknown hunks, empty reviews and identical content are refused") {
    val (_, _, _, binding) = setup("edges")
    val run = for
      same <- AskEditReviews.applyChange(binding.id, binding, binding.file, "ask_write", s => Right(s))
      ghostReview <- AskEditReviews.accept(binding.id, None)
      applied <- AskEditReviews.applyChange(
        binding.id,
        binding,
        binding.file,
        "ask_write",
        s => Right(s.replace("beta", "BETA"))
      )
      ghostHunk <- applied.fold(_ => IO.pure(Left("?")), _ => AskEditReviews.accept(binding.id, Some("h9")))
    yield (same, ghostReview, ghostHunk)
    run.map { case (same, ghostReview, ghostHunk) =>
      assert(same.left.exists(_.contains("no change")), same.toString)
      assert(ghostReview.left.exists(_.contains("no pending review")), ghostReview.toString)
      assert(ghostHunk.left.exists(_.contains("no such hunk")), ghostHunk.toString)
    }
  }

  // ── 路径纪律（ask-scope 批 2026-11-18 改判于「身份归一」，见 AskPathBoundary）──
  // 旧判据「逐字相等 + 一律拒 `..` + 一律拒软链」换成：归一后**同一份文档**放行，
  // 其余全拒。本 spec 断言消费点（AskEditReviews）上的行为；穷举矩阵在
  // AskWriteScopeSpec。
  test("capture refuses other documents and foreign sessions, and normalises `..` / relative spellings") {
    val (root, dir, _, binding) = setup("paths")
    val other = root / "documents" / "other.md"
    os.write(other, "other\n")
    val foreignSid = AskSessions.sessionIdFor(other.toString)
    val cases = List(
      (binding.id, other.toString, "different document than the bound one"),
      (binding.id, "", "`path` is required"),
      (foreignSid, binding.file, "does not own")
    )
    val refusals = cases.foldLeft(IO.unit) { case (acc, (sid, path, marker)) =>
      acc *> AskEditReviews.applyChange(sid, binding, path, "ask_write", s => Right(s + "x")).map { res =>
        assert(res.left.exists(_.contains(marker)), s"[$marker] ${res.toString}")
        assert(
          res.left.exists(_.contains(AskPathBoundary.HandoffHint)),
          s"[$marker] refusal must carry the handoff suggestion: ${res.toString}"
        )
      }
    }
    // 归一后同一份文档的拼写 ⇒ 放行（`..` 往返 / 相对拼写；旧判据在此必红）
    val allowed = for
      a <- AskEditReviews.capture(binding.id, binding, s"$dir/../documents/${binding.fileName}", "ask_write")
      b <- AskEditReviews.capture(binding.id, binding, binding.fileName, "ask_write")
    yield (a, b)
    (refusals *> allowed).map { case (a, b) =>
      assert(a.isRight, s"`..` round-trip normalises to the bound document: $a")
      assert(b.isRight, s"the relative spelling resolves against the bound directory: $b")
      assertEquals(AskEditReviews.reviewFor(binding.id), None, "refused captures must not record a review")
      assertEquals(os.read(other), "other\n", "other files must be untouched")
    }
  }

  test("capture follows identity through symlinks: same document passes, another target is refused") {
    val (root, _, registry, _) = setup("refusals")
    val real = root / "documents" / "real.md"
    os.write(real, "real\n")
    val link = root / "documents" / "link.md"
    os.symlink(link, real)
    val elsewhere = root / "documents" / "elsewhere.md"
    os.write(elsewhere, "elsewhere\n")
    val linkElsewhere = root / "documents" / "link-elsewhere.md"
    os.symlink(linkElsewhere, elsewhere)
    val binary = root / "documents" / "binary.md"
    os.write(binary, Array[Byte](0x61, 0x00, 0x62)) // NUL 字节
    val missing = root / "documents" / "missing.md"
    val run = for
      linkBinding <- registry.bind(link.toString, link.last)
      binBinding <- registry.bind(binary.toString, binary.last)
      missBinding <- registry.bind(missing.toString, missing.last)
      // 软链指向**同一份文档** ⇒ 归一后同一身份 ⇒ 放行（直接落盘）
      l <- AskEditReviews.applyChange(linkBinding.id, linkBinding, link.toString, "ask_write", s => Right(s + "x"))
      // 软链指向**别处** ⇒ 拒，且目标文件一个字节不动
      le <- AskEditReviews.applyChange(
        binBinding.id,
        binBinding,
        linkElsewhere.toString,
        "ask_write",
        s => Right("HACK\n")
      )
      b <- AskEditReviews.applyChange(binBinding.id, binBinding, binary.toString, "ask_write", s => Right(s + "x"))
      m <- AskEditReviews.applyChange(missBinding.id, missBinding, missing.toString, "ask_write", s => Right(s + "x"))
    yield (l, le, b, m)
    run.map { case (l, le, b, m) =>
      assert(l.isRight, s"a symlink to the bound document is the same document: $l")
      assert(le.left.exists(_.contains("different document than the bound one")), le.toString)
      assert(le.left.exists(_.contains(AskPathBoundary.HandoffHint)), le.toString)
      assert(b.left.exists(_.contains("binary")), b.toString)
      assert(m.left.exists(_.contains("does not exist")), m.toString)
      assertEquals(os.read(elsewhere), "elsewhere\n", "the symlink's foreign target must be untouched")
    }
  }

  // ── 工具面身份闸（双保险的内侧，判据不变）─────────────────────────────────
  private def ctxFor(sid: Option[String], root: os.Path): ToolContext =
    ToolContext(projectRoot = root.toString, sessionId = sid)

  test("ask_edit/ask_write refuse non-ask sessions and ask sessions without a binding") {
    val (root, _, _, _) = setup("toolgates")
    val askSid = Some("ask-toolgate")
    val plainSid = Some("ordinary-session")
    val editInput = JsonObject("path" -> "/tmp/x.md".asJson, "old_str" -> "a".asJson, "new_str" -> "b".asJson)
    val writeInput = JsonObject("path" -> "/tmp/x.md".asJson, "content" -> "b".asJson)
    for
      e1 <- AskEditTool.call(editInput, ctxFor(plainSid, root))
      w1 <- AskWriteTool.call(writeInput, ctxFor(plainSid, root))
      e2 <- AskEditTool.call(editInput, ctxFor(askSid, root))
      e3 <- AskEditTool.call(
        JsonObject("path" -> "/tmp/x.md".asJson, "old_str" -> "".asJson, "new_str" -> "b".asJson),
        ctxFor(askSid, root)
      )
      e4 <- AskEditTool.call(
        JsonObject("path" -> "/tmp/x.md".asJson, "old_str" -> "same".asJson, "new_str" -> "same".asJson),
        ctxFor(askSid, root)
      )
      w2 <- AskWriteTool.call(writeInput, ctxFor(askSid, root))
    yield
      assert(e1.left.exists(_.message.contains("ASK_EDIT_FORBIDDEN")), e1.toString)
      assert(w1.left.exists(_.message.contains("ASK_EDIT_FORBIDDEN")), w1.toString)
      assert(e2.left.exists(_.message.contains("ASK_EDIT_NO_BINDING")), e2.toString)
      assert(e3.left.exists(_.message.contains("old_str")), e3.toString)
      assert(e4.left.exists(_.message.contains("identical")), e4.toString)
      assert(w2.left.exists(_.message.contains("ASK_EDIT_NO_BINDING")), w2.toString)
    end for
  }
end AskEditReviewSpec

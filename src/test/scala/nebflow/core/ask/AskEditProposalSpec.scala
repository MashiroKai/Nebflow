package nebflow.core.ask

import cats.effect.IO
import io.circe.JsonObject
import io.circe.syntax.*
import munit.CatsEffectSuite
import nebflow.core.tools.{AskEditTool, AskWriteTool, ToolContext}

/** canvas-ask-restricted-edit（CAE-REQ-001）绿面：提案生命周期（propose →
  * Accept 写盘 / Reject 丢弃 / stale 拒）+ 路径纪律（逐字相等、无 ..、非软链、
  * 非二进制）+ 工具面身份闸。 */
final class AskEditProposalSpec extends CatsEffectSuite:

  private val original = "alpha\nbeta\ngamma\n"

  private def setup(name: String, content: String = original): (os.Path, os.Path, AskSessionRegistry, AskBinding) =
    val root = os.Path(os.temp.dir(prefix = s"ask-edit-$name-").toNIO.toRealPath())
    val dir = root / "documents"
    os.makeDir.all(dir)
    val file = dir / "notes.md"
    os.write(file, content)
    val registry = new AskSessionRegistry(root)
    // bind 是纯记录（无 IO 副作用路径）；同步求值即可。
    val binding = registry.bind(file.toString, file.last).unsafeRunSync()
    AskEditProposals.resetForTest(binding.id)
    (root, dir, registry, binding)

  // ── 生命周期 ────────────────────────────────────────────────────────────
  test("propose holds a snapshot-based proposal and never writes the disk") {
    val (_, _, _, binding) = setup("propose")
    val file = os.Path(binding.file)
    AskEditProposals
      .propose(binding.id, binding, binding.file, s => Right(s.replace("beta", "BETA")))
      .map {
        case Right(p) =>
          assertEquals(p.oldText, original, "snapshot = disk content at propose time")
          assertEquals(p.newText, "alpha\nBETA\ngamma\n")
          assertEquals(p.path, binding.file)
          assertEquals(AskEditProposals.pendingFor(binding.id).size, 1)
          assertEquals(os.read(file), original, "propose must not write the disk")
        case Left(why) => fail(why)
      }
  }

  test("accept writes the proposed content atomically and clears the pending list") {
    val (_, _, _, binding) = setup("accept")
    val file = os.Path(binding.file)
    val run = for
      proposed <- AskEditProposals.propose(binding.id, binding, binding.file, s => Right(s.replace("gamma", "GAMMA")))
      accepted <- AskEditProposals.accept(binding.id, proposed.fold(_ => "?", _.id))
    yield (proposed, accepted)
    run.map { case (proposed, accepted) =>
      assert(proposed.isRight, proposed.toString)
      accepted match
        case Right(p) =>
          assertEquals(os.read(file), "alpha\nbeta\nGAMMA\n", "accept must write the new content")
          assertEquals(AskEditProposals.pendingFor(binding.id), Nil, "accept must dequeue the proposal")
        case Left(why) => fail(why)
    }
  }

  test("reject discards the proposal with zero disk writes") {
    val (_, _, _, binding) = setup("reject")
    val file = os.Path(binding.file)
    val run = for
      proposed <- AskEditProposals.propose(binding.id, binding, binding.file, s => Right(s + "delta\n"))
      rejected <- AskEditProposals.reject(binding.id, proposed.fold(_ => "?", _.id))
    yield (proposed, rejected)
    run.map { case (proposed, rejected) =>
      assert(proposed.isRight, proposed.toString)
      assert(rejected.isRight, rejected.toString)
      assertEquals(os.read(file), original, "reject must leave the file unchanged")
      assertEquals(AskEditProposals.pendingFor(binding.id), Nil)
    }
  }

  test("accept refuses when the file changed after the snapshot (stale) and recovers when fresh") {
    val (_, _, _, binding) = setup("stale")
    val file = os.Path(binding.file)
    val run = for
      proposed <- AskEditProposals.propose(binding.id, binding, binding.file, s => Right(s.replace("alpha", "ALPHA")))
      _ <- IO.blocking(os.write.over(file, "externally changed\n")) // 外部改动：快照过期
      stale <- proposed.fold(_ => IO.pure(Left("?")), p => AskEditProposals.accept(binding.id, p.id))
      // stale 拒必须在回滚前验：此刻盘上必须仍是外部内容（拒写不覆盖外部改动）。
      _ <- IO {
        assert(stale.left.exists(_.contains("stale")), stale.toString)
        assertEquals(os.read(file), "externally changed\n", "stale accept must not overwrite the external change")
      }
      _ <- IO.blocking(os.write.over(file, original)) // 外部改动回滚 = 快照重新成立
      fresh <- proposed.fold(_ => IO.pure(Left("?")), p => AskEditProposals.accept(binding.id, p.id))
    yield (proposed, fresh)
    run.map { case (proposed, fresh) =>
      assert(proposed.isRight, proposed.toString)
      assert(fresh.isRight, fresh.toString)
      assertEquals(os.read(file), "alpha\nbeta\ngamma\n".replace("alpha", "ALPHA"))
    }
  }

  test("identical content and unknown proposal ids are refused") {
    val (_, _, _, binding) = setup("edges")
    val run = for
      same <- AskEditProposals.propose(binding.id, binding, binding.file, s => Right(s))
      ghostAccept <- AskEditProposals.accept(binding.id, "e999999")
      ghostReject <- AskEditProposals.reject(binding.id, "e999999")
    yield (same, ghostAccept, ghostReject)
    run.map { case (same, ghostAccept, ghostReject) =>
      assert(same.left.exists(_.contains("no change")), same.toString)
      assert(ghostAccept.isLeft)
      assert(ghostReject.isLeft)
    }
  }

  // ── 路径纪律（CAE-SCN-002） ─────────────────────────────────────────────
  test("capture refuses other paths, relative paths, traversal and foreign sessions") {
    val (root, dir, _, binding) = setup("paths")
    val other = root / "documents" / "other.md"
    os.write(other, "other\n")
    val foreignSid = AskSessions.sessionIdFor(other.toString)
    val cases = List(
      (binding.id, other.toString, "only the bound file"),
      (binding.id, "documents/notes.md", "must be absolute"),
      // 字面 `..` 段（即使解析后指回同一文件）也在相等闸之前被拒。
      (binding.id, s"$dir/../${binding.fileName}", "traversal"),
      (binding.id, "", "path is required"),
      (foreignSid, binding.file, "does not own")
    )
    cases.foldLeft(IO.unit) { case (acc, (sid, path, marker)) =>
      acc *> AskEditProposals.propose(sid, binding, path, s => Right(s + "x")).map { res =>
        assert(res.left.exists(_.contains(marker)), s"[$marker] ${res.toString}")
      }
    } *> IO {
      assertEquals(AskEditProposals.pendingFor(binding.id), Nil, "refused captures must not enqueue")
      assertEquals(os.read(other), "other\n", "other files must be untouched")
    }
  }

  test("capture refuses symlinks, binary content and missing files") {
    val (root, _, registry, _) = setup("refusals")
    val real = root / "documents" / "real.md"
    os.write(real, "real\n")
    val link = root / "documents" / "link.md"
    os.symlink(link, real)
    val binary = root / "documents" / "binary.md"
    os.write(binary, Array[Byte](0x61, 0x00, 0x62)) // NUL 字节
    val missing = root / "documents" / "missing.md"
    val run = for
      linkBinding <- registry.bind(link.toString, link.last)
      binBinding <- registry.bind(binary.toString, binary.last)
      missBinding <- registry.bind(missing.toString, missing.last)
      l <- AskEditProposals.propose(linkBinding.id, linkBinding, link.toString, s => Right(s + "x"))
      b <- AskEditProposals.propose(binBinding.id, binBinding, binary.toString, s => Right(s + "x"))
      m <- AskEditProposals.propose(missBinding.id, missBinding, missing.toString, s => Right(s + "x"))
    yield (l, b, m)
    run.map { case (l, b, m) =>
      assert(l.left.exists(_.contains("symbolic links")), l.toString)
      assert(b.left.exists(_.contains("binary")), b.toString)
      assert(m.left.exists(_.contains("no longer exists")), m.toString)
      assertEquals(os.read(real), "real\n", "symlink target must be untouched")
    }
  }

  // ── 工具面身份闸（双保险的内侧） ────────────────────────────────────────
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
  }
end AskEditProposalSpec

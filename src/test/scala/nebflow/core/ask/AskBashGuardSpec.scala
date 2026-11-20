package nebflow.core.ask

import cats.effect.IO
import munit.CatsEffectSuite

import scala.concurrent.duration.*

/**
 * canvas-ask-restricted-edit（CAE-REQ-002）绿面：只读白名单校验器 + argv 直执行器
 * 的穷举判定。每类危险命令一名代表 + 每类白名单绿样本；解析失败一律拒绝。
 */
final class AskBashGuardSpec extends CatsEffectSuite:

  override def munitIOTimeout: FiniteDuration = 30.seconds

  // ── 校验器：绿（白名单查询） ─────────────────────────────────────────────
  test("validator accepts every allowlisted read command") {
    val greens = List(
      "grep -n foo notes.md",
      "rg pattern src/",
      "ls -la",
      "cat notes.md",
      "head -n 5 notes.md",
      "tail -n 5 notes.md",
      "find . -name '*.md'",
      "wc -l notes.md",
      "git log --oneline",
      "git show HEAD:notes.md",
      "git diff HEAD~1",
      "git status",
      "stand-recall 检索词",
      "stand-messages --session abc"
    )
    greens.foreach { cmd =>
      AskBashGuard.validate(cmd) match
        case Right(v) => assertEquals(v.pipeline.head.head, cmd.split(" ").head, cmd)
        case Left(why) => fail(s"[$cmd] must validate, refused: $why")
    }
  }

  test("validator accepts pipelines of allowlisted segments and quoted pipe literals") {
    // 未加引号 | 分段：两段各过白名单。
    AskBashGuard.validate("cat notes.md | grep -c MARKER") match
      case Right(v) => assertEquals(v.pipeline.size, 2)
      case Left(why) => fail(s"pipeline must validate, refused: $why")
    // 引号内 | 是正则字面量：不分段、token 原样保留。
    AskBashGuard.validate("""grep -E "foo|bar" notes.md""") match
      case Right(v) =>
        assertEquals(v.pipeline.size, 1, "quoted pipe must not split segments")
        assert(v.pipeline.head.contains("foo|bar"), v.toString)
      case Left(why) => fail(s"quoted pipe must validate, refused: $why")
  }

  // ── 校验器：红（每类危险命令一名代表） ───────────────────────────────────
  test("validator refuses unknown and write commands") {
    List(
      "rm victim.md",
      "node script.js",
      "python3 -c print(1)",
      "tee out.txt",
      "touch x.md",
      "chmod +x x.sh",
      "mv a.md b.md"
    ).foreach { cmd =>
      AskBashGuard.validate(cmd) match
        case Right(_) => fail(s"[$cmd] must be refused")
        case Left(why) => assert(why.nonEmpty)
    }
  }

  test("validator refuses git write subcommands") {
    List(
      "git push",
      "git add .",
      "git commit -m x",
      "git checkout main",
      "git clean -fd",
      "git",
      "git config user.name x"
    )
      .foreach { cmd =>
        AskBashGuard.validate(cmd) match
          case Right(_) => fail(s"[$cmd] must be refused")
          case Left(why) => assert(why.contains("git"), why)
      }
  }

  test("validator refuses expansion and combination metacharacters anywhere") {
    List(
      "echo $(rm victim.md)",
      "echo `rm victim.md`",
      "ls; rm victim.md",
      "grep x notes.md && rm victim.md",
      "grep x notes.md || cat notes.md",
      "grep x notes.md & ls",
      "HOME=$HOME ls",
      "grep '$x' notes.md"
    ).foreach { cmd =>
      AskBashGuard.validate(cmd) match
        case Right(_) => fail(s"[$cmd] must be refused")
        // `||` 走 empty-segment 拒绝径（`|` 本身是合法管道分隔符，两根相邻
        // ⇒ 中段为空）——拒绝本身是硬性判据，径名两类都认。
        case Left(why) =>
          assert(why.contains("metacharacter") || why.contains("empty pipeline segment"), why)
    }
  }

  test("validator refuses all redirection forms") {
    List(
      "grep MARKER notes.md > leak.txt",
      "grep MARKER notes.md >> leak.txt",
      "grep MARKER notes.md < notes.md",
      "cat notes.md | tee leak.txt",
      "> leak.txt"
    ).foreach { cmd =>
      AskBashGuard.validate(cmd) match
        case Right(_) => fail(s"[$cmd] must be refused")
        case Left(why) => assert(why.nonEmpty)
    }
  }

  test("validator refuses the find -exec family and rg --pre family by argument blacklist") {
    List(
      "find . -name victim.md -exec rm {} +",
      "find . -name victim.md -execdir rm {} ;",
      "find . -ok rm {} ;",
      "find . -okdir rm {} ;",
      "rg --pre cat MARKER notes.md",
      "rg --pre=cat MARKER notes.md",
      "rg --pre-command cat MARKER notes.md",
      "rg --pre-command=cat MARKER notes.md"
    ).foreach { cmd =>
      AskBashGuard.validate(cmd) match
        case Right(_) => fail(s"[$cmd] must be refused")
        case Left(why) => assert(why.contains("refused"), why)
    }
  }

  test("validator is fail-closed on parse failures and limits") {
    List(
      "",
      "   ",
      "| cat notes.md",
      "cat notes.md |",
      "cat notes.md | | grep x",
      "'unterminated",
      "\"unterminated",
      "grep a\\",
      "grep x notes.md\nrm victim.md",
      "grep " + ("a" * 2500)
    ).foreach { cmd =>
      AskBashGuard.validate(cmd) match
        case Right(_) => fail(s"[${cmd.take(30)}…] must be refused")
        case Left(why) => assert(why.nonEmpty)
    }
    // 超管道段数上限（8）。
    val nine = (1 to 9).map(_ => "cat notes.md").mkString(" | ")
    assert(AskBashGuard.validate(nine).isLeft)
    // 超单段 token 上限（64）。
    val fat = "grep " + (1 to 70).map(i => s"w$i").mkString(" ")
    assert(AskBashGuard.validate(fat).isLeft)
  }

  // ── 执行器：argv 直执行（cwd / 管道 / 环境钉 / 失败面） ───────────────────
  private def tempDir(name: String): os.Path =
    os.Path(os.temp.dir(prefix = s"ask-guard-$name-").toNIO.toRealPath())

  test("executor runs an allowlisted command in the given cwd") {
    val dir = tempDir("cwd")
    // -r：输出回显 ./notes.md 路径——裸相对名命中即 cwd 证据（单文件 grep
    // 不回显文件名，-rn 直查目录才带）。
    val run = for
      _ <- IO.blocking(os.write(dir / "notes.md", "line A\nline B\n"))
      res <- AskBashGuard.validate("grep -rn line .") match
        case Right(v) => AskBashExecutor.run(v, Some(dir.toString), 5000)
        case Left(why) => IO.pure(Left(why))
    yield res match
      case Right(out) =>
        assert(out.contains("line A"), out)
        assert(out.contains("notes.md"), out)
      case Left(why) => fail(why)
    run
  }

  test("executor chains pipeline segments stdout to stdin") {
    val dir = tempDir("pipe")
    val run = for
      _ <- IO.blocking(os.write(dir / "notes.md", "MARK\nplain\nMARK\n"))
      res <- AskBashGuard.validate("cat notes.md | grep -c MARK") match
        case Right(v) => AskBashExecutor.run(v, Some(dir.toString), 5000)
        case Left(why) => IO.pure(Left(why))
    yield res match
      case Right(out) => assertEquals(out.trim, "2", out)
      case Left(why) => fail(why)
    run
  }

  test("executor runs read-only git subcommands with the pager pinned off") {
    val dir = tempDir("git")
    val setup = IO.blocking {
      os.proc("git", "init", "-q").call(cwd = dir)
      os.proc("git", "config", "user.email", "spec@stand.test").call(cwd = dir)
      os.proc("git", "config", "user.name", "spec").call(cwd = dir)
      os.write(dir / "notes.md", "committed\n")
      os.proc("git", "add", "notes.md").call(cwd = dir)
      os.proc("git", "commit", "-q", "-m", "ASK-GIT-EVIDENCE").call(cwd = dir)
    }
    val run = for
      _ <- setup
      res <- AskBashGuard.validate("git log --oneline") match
        case Right(v) => AskBashExecutor.run(v, Some(dir.toString), 10000)
        case Left(why) => IO.pure(Left(why))
    yield res match
      case Right(out) =>
        assert(out.contains("ASK-GIT-EVIDENCE"), out)
        assert(!out.toLowerCase.contains("pager"), out)
      case Left(why) => fail(why)
    run
  }

  test("executor reports non-zero exits as explicit failures") {
    val dir = tempDir("fail")
    val run = AskBashGuard.validate("cat missing-file.md") match
      case Right(v) => AskBashExecutor.run(v, Some(dir.toString), 5000)
      case Left(why) => IO.pure(Left(why))
    run.map { res =>
      assert(res.isLeft)
      assert(res.left.exists(_.contains("exited")), res.toString)
      assert(!os.exists(dir / "missing-file.md"))
    }
  }
end AskBashGuardSpec

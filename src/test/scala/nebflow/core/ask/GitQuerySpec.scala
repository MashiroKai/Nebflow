package nebflow.core.ask

import cats.effect.unsafe.implicits.global
import io.circe.syntax.*
import io.circe.{Json, JsonObject}
import nebflow.core.tools.{GitQueryTool, ToolContext, ToolRegistry}

import scala.util.Try

/* ═══════════════════════════════════════════════════════════════════════════
 * GitQuery spec（gitquery 批 2026-11-05，作者令「ask 会话新增只读 GitQuery 工具」）。
 *
 * 七条腿**逐条独立**（作者口径「逐条都要有，不许合并成一条」）：
 *   T1 非 git 目录 / 路径不存在 → 明确拒绝（可读回执，非裸异常）
 *   T2 白名单外子命令 → 拒绝；schema 面零 argv 透传（enum 四件 / 无 args 键 /
 *      additionalProperties=false）+ 永不进白名单枚举与子命令零交集
 *   T3 非 ask 会话身份调用 → 拒绝（DUAL 风格显式错误）；ask 身份 → 放行（正控）
 *   T4 截断真的生效：行闸 200 与字节闸 8KB 各给边界读数
 *   T5 merge-base 布尔两态各一例（正/负），且回执不提裸 exit code
 *   T6 `--no-ext-diff` / `--no-textconv` 实际生效：**armed trap 反证**——刻意配
 *      `diff.external` + 逐路径 diff driver + `core.fsmonitor` 的临时仓，外部命令
 *      在裸执行面确实可被执行（负控打出证据），而 GitQuery 的每一个形态都不触发
 *   T7 回执首行恒为「解析到的仓根」+ `log -n` 上限钳制读数 + device 面
 *
 * 全部用真 git 临时仓（os.proc 直调 git 造数据，工具本身经 GitQueryTool.call 走
 * 完整调用面），不 mock、不替身。
 * ═══════════════════════════════════════════════════════════════════════════ */

final class GitQuerySpec extends munit.FunSuite:

  private val AskSid = "ask-3f2b7c19"

  private def ctx(projectRoot: os.Path, sessionId: Option[String] = Some(AskSid)): ToolContext =
    ToolContext(projectRoot = projectRoot.toString, sessionId = sessionId)

  private def call(input: JsonObject, c: ToolContext): Either[String, String] =
    GitQueryTool.call(input, c).attempt.unsafeRunSync() match
      case Left(err) => fail(s"GitQuery 抛出裸异常（应当给可读回执）：${err.getClass.getSimpleName}: ${err.getMessage}")
      case Right(Left(toolErr)) => Left(toolErr.message)
      case Right(Right(text))   => Right(text)

  private def raw(sub: String, extra: (String, Json)*): JsonObject =
    JsonObject.fromIterable((("subcommand" -> sub.asJson) :: extra.toList).map((k, v) => k -> v))

  /* ── git 夹具（真仓；os.proc 直调，工具外零 mock）───────────────────── */

  private def git(dir: os.Path, args: String*): String =
    val res = os.proc(Seq("git", "-C", dir.toString) ++ args).call(cwd = dir, check = false, mergeErrIntoOut = true)
    if res.exitCode != 0 then fail(s"fixture git ${args.mkString(" ")} failed: ${res.out.text()}")
    res.out.text()

  private def newRepo(prefix: String, init: Boolean = true): os.Path =
    val dir = os.temp.dir(prefix = prefix)
    if init then
      git(dir, "init", "-q", ".")
      git(dir, "config", "user.email", "spec@example.test")
      git(dir, "config", "user.name", "GitQuerySpec")
    dir

  private def commit(dir: os.Path, msg: String, files: (String, String)*): String =
    files.foreach { (name, body) => os.write.over(dir / os.RelPath(name), body, createFolders = true) }
    git(dir, "add", "-A")
    git(dir, "commit", "-q", "-m", msg)
    git(dir, "rev-parse", "HEAD").trim

  /* ═══════════ T1 非 git 目录 / 路径不存在 → 明确拒绝 ═══════════ */

  test("T1a 非 git 目录 → GITQUERY_NOT_A_REPO（可读回执，首行写明解析目标）") {
    val plain = newRepo("gq-nogit-", init = false)
    os.write.over(plain / "note.md", "hello\n")
    val res = call(raw("log", "repo" -> plain.toString.asJson), ctx(plain))
    assert(res.isLeft, clue(res))
    val msg = res.swap.getOrElse("")
    assert(msg.contains("GITQUERY_NOT_A_REPO"), clue(msg))
    assert(msg.linesIterator.next().startsWith("repo: "), clue(msg))
    assert(msg.contains(plain.toString) || msg.contains("gq-nogit-"), clue(msg))
  }

  test("T1b 路径不存在 → GITQUERY_REPO_NOT_FOUND（不抛裸异常）") {
    val root = newRepo("gq-ghost-")
    val ghost = root / "does-not-exist"
    val res = call(raw("log", "repo" -> ghost.toString.asJson), ctx(root))
    assert(res.isLeft, clue(res))
    val msg = res.swap.getOrElse("")
    assert(msg.contains("GITQUERY_REPO_NOT_FOUND"), clue(msg))
    assert(msg.linesIterator.next().startsWith("repo: "), clue(msg))
  }

  test("T1c repo 指向文件（非目录）→ GITQUERY_REPO_NOT_DIR") {
    val root = newRepo("gq-file-")
    commit(root, "c1", "a.txt" -> "a\n")
    val res = call(raw("log", "repo" -> (root / "a.txt").toString.asJson), ctx(root))
    assert(res.isLeft, clue(res))
    assert(res.swap.getOrElse("").contains("GITQUERY_REPO_NOT_DIR"), clue(res))
  }

  /* ═══════════ T2 白名单外子命令 → 拒绝 + schema 零 argv 透传 ═══════════ */

  test("T2a 白名单外子命令 → GITQUERY_BAD_SUBCOMMAND（写面/网络面逐条拒）") {
    val root = newRepo("gq-sub-")
    commit(root, "c1", "a.txt" -> "a\n")
    List("push", "config", "checkout", "status", "rev-parse", "bash", "").foreach { sub =>
      val res = call(raw(sub), ctx(root))
      assert(res.isLeft, clue(sub, res))
      val msg = res.swap.getOrElse("")
      assert(msg.contains("GITQUERY_BAD_SUBCOMMAND"), clue(sub, msg))
      assert(msg.contains("log, show, diff, merge-base"), clue(sub, msg))
    }
  }

  test("T2b schema 面零自由 argv：enum 恰四件 / 无 args-argv-cmd 键 / additionalProperties=false") {
    val schema = GitQueryTool.inputSchema
    val propsObj = schema("properties").flatMap(_.asObject)
    val props = propsObj.map(_.keys.toSet).getOrElse(Set.empty)
    assertEquals(props, Set("subcommand", "repo", "path", "rev", "rev2", "limit", "format", "device"))
    List("args", "argv", "cmd", "command", "options").foreach { k =>
      assert(!props.contains(k), clue(s"must not expose free-form argv key '$k'"))
    }
    val subObj = propsObj.flatMap(_.apply("subcommand")).flatMap(_.asObject)
    val enumVals = subObj.flatMap(_.apply("enum")).flatMap(_.asArray).map(_.toList).getOrElse(Nil)
    assertEquals(enumVals.flatMap(_.asString), GitQueryTool.Subcommands)
    assertEquals(GitQueryTool.Subcommands, List("log", "show", "diff", "merge-base"))
    assertEquals(schema("additionalProperties").flatMap(_.asBoolean), Some(false))
    assertEquals(schema("required").flatMap(_.asArray).map(_.toList.flatMap(_.asString)), Some(List("subcommand")))
    // 永不进白名单枚举：与 v1 子命令零交集；v1.5 候选不混进 v1 enum
    assert(GitQueryTool.NeverAllow.intersect(GitQueryTool.Subcommands.toSet).isEmpty, clue(GitQueryTool.NeverAllow))
    assert(GitQueryTool.V15Candidates.forall(c => !GitQueryTool.Subcommands.contains(c)), clue(GitQueryTool.V15Candidates))
    // 注册面在册（否则 ask 早退集解析不出 schema）
    assert(ToolRegistry.ALL_TOOLS.exists(_.name == "GitQuery"), "GitQuery 必须在 ToolRegistry 在册")
    // 挂载面单点
    assert(AskSessions.AllowedTools.contains("GitQuery"), clue(AskSessions.AllowedTools))
    assert(AskSessions.AskExclusiveTools.contains("GitQuery"), clue(AskSessions.AskExclusiveTools))
  }

  test("T2c 逃逸面的参数级封堵：前导 '-' / '::' / 未知键 / 非 log 用 limit 一律拒绝") {
    val root = newRepo("gq-escape-")
    commit(root, "c1", "a.txt" -> "a\n")
    val cases: List[(JsonObject, String)] = List(
      raw("log", "rev" -> "--exec-path=/tmp".asJson) -> "must not start with '-'",
      raw("log", "path" -> "-c".asJson) -> "must not start with '-'",
      raw("log", "path" -> "ext::sh -c touch /tmp/pwned".asJson) -> "'::'",
      raw("log", "args" -> Json.arr("-c".asJson)) -> "unknown parameter",
      raw("show", "rev" -> "HEAD:a.txt".asJson) -> "not a plain revision expression",
      raw("show", "limit" -> 5.asJson) -> "`limit` applies to `log` only"
    )
    cases.foreach { (input, needle) =>
      val res = call(input, ctx(root))
      assert(res.isLeft, clue(input, res))
      val msg = res.swap.getOrElse("")
      assert(msg.contains("GITQUERY_BAD_ARG"), clue(input, msg))
      assert(msg.contains(needle), clue(input, msg))
    }
  }

  /* ═══════════ T3 非 ask 会话身份 → 拒绝（DUAL 风格显式错误）═══════════ */

  test("T3a 非 ask 会话身份 → GITQUERY_FORBIDDEN（delegate / root / 相似前缀 / None 全拒）") {
    val root = newRepo("gq-ident-")
    commit(root, "c1", "a.txt" -> "a\n")
    List(
      Some("delegate-delegate-gitquery"),
      Some("f1a2b3c4-0000-0000-0000-000000000000"),
      Some("subagent-ask-3f2b7c19"), // 前缀相似但不是 ask 身份（子代理克隆）
      None // REST 直调 / 非会话上下文
    ).foreach { sid =>
      val res = call(raw("log"), ctx(root, sid))
      assert(res.isLeft, clue(sid, res))
      val msg = res.swap.getOrElse("")
      assert(msg.contains("GITQUERY_FORBIDDEN"), clue(sid, msg))
      assert(msg.contains("exclusive to file-ask sessions"), clue(sid, msg))
    }
  }

  test("T3b 正控：ask 身份放行（同一调用，仅换 identity）") {
    val root = newRepo("gq-ident-ok-")
    commit(root, "c1", "a.txt" -> "a\n")
    val ok = call(raw("log"), ctx(root, Some("ask-3f2b7c19")))
    assert(ok.isRight, clue(ok))
    assert(ok.getOrElse("").contains("repo: "))
  }

  /* ═══════════ T4 截断真的生效（边界读数）═══════════ */

  test("T4a 行闸：400 行 blob → 恰 200 行正文 + 读数行（200 of 400 lines）") {
    val root = newRepo("gq-clip-lines-")
    val body = (1 to 400).map(i => s"line $i").mkString("", "\n", "\n")
    commit(root, "c1", "big.txt" -> body)
    val text = call(raw("show", "rev" -> "HEAD".asJson, "path" -> "big.txt".asJson), ctx(root))
      .getOrElse(fail("show rev:path 应当成功"))
    val lines = text.linesIterator.toList
    // 首两行 = repo: / cmd:，其后 200 行正文，末行 = 截断读数
    assert(lines.head.startsWith("repo: "), clue(lines.take(3)))
    assert(lines(1).startsWith("cmd: "), clue(lines.take(3)))
    val notice = lines.last
    assert(notice.startsWith("[GitQuery truncated:"), clue(notice))
    assert(notice.contains("200 of 400 lines"), clue(notice))
    assert(notice.contains(s"caps: ${GitQueryTool.MaxOutputLines} lines / ${GitQueryTool.MaxOutputBytes} bytes"), clue(notice))
    assertEquals(lines.count(_.startsWith("line ")), GitQueryTool.MaxOutputLines)
    assertEquals(GitQueryTool.MaxOutputLines, 200)
  }

  test("T4b 字节闸：单行 30000 字符 → 正文 ≤ 8192 字节 + 读数行（bytes 边界）") {
    val root = newRepo("gq-clip-bytes-")
    commit(root, "c1", "wide.txt" -> ("x" * 30000 + "\n"))
    val text = call(raw("show", "rev" -> "HEAD".asJson, "path" -> "wide.txt".asJson), ctx(root))
      .getOrElse(fail("show rev:path 应当成功"))
    val lines = text.linesIterator.toList
    val notice = lines.last
    assert(notice.startsWith("[GitQuery truncated:"), clue(notice.take(200)))
    assert(notice.contains("8192 bytes"), clue(notice))
    assert(notice.contains("of 30000"), clue(notice))
    // 正文（repo/cmd 头 + 读数行之外）字节数不得超上限；且贴着上限（边界读数）
    val bodyBytes = lines.drop(2).dropRight(1).mkString("\n").getBytes(java.nio.charset.StandardCharsets.UTF_8).length
    assert(bodyBytes <= GitQueryTool.MaxOutputBytes, clue(bodyBytes))
    assert(bodyBytes > GitQueryTool.MaxOutputBytes - 8, clue(bodyBytes))
    assertEquals(GitQueryTool.MaxOutputBytes, 8192)
  }

  test("T4c 未截断时不得出现读数行（假截断反证）") {
    val root = newRepo("gq-clip-none-")
    commit(root, "c1", "small.txt" -> (1 to 5).map(i => s"l$i").mkString("\n"))
    val text = call(raw("show", "rev" -> "HEAD".asJson, "path" -> "small.txt".asJson), ctx(root))
      .getOrElse(fail("show rev:path 应当成功"))
    assert(!text.contains("[GitQuery truncated:"), clue(text))
    assert(text.contains("l5"), clue(text))
  }

  /* ═══════════ T5 merge-base 布尔两态 ═══════════ */

  test("T5a merge-base 正例：祖先 → is-ancestor: true（不出裸 exit code）") {
    val root = newRepo("gq-mb-true-")
    val c1 = commit(root, "c1", "a.txt" -> "1\n")
    val c2 = commit(root, "c2", "a.txt" -> "2\n")
    val text = call(raw("merge-base", "rev" -> c1.take(9).asJson, "rev2" -> c2.take(9).asJson), ctx(root))
      .getOrElse(fail("merge-base 应当成功"))
    assert(text.contains("is-ancestor: true"), clue(text))
    assert(!text.contains("is-ancestor: false"), clue(text))
    assert(!text.contains("exit"), clue("不得把裸 exit code 甩给模型"))
  }

  test("T5b merge-base 反例：非祖先 → is-ancestor: false（exit 1 不是错误）") {
    val root = newRepo("gq-mb-false-")
    val c1 = commit(root, "c1", "a.txt" -> "1\n")
    val c2 = commit(root, "c2", "a.txt" -> "2\n")
    val text = call(raw("merge-base", "rev" -> c2.take(9).asJson, "rev2" -> c1.take(9).asJson), ctx(root))
      .getOrElse(fail("merge-base 应当成功"))
    assert(text.contains("is-ancestor: false"), clue(text))
    assert(!text.contains("is-ancestor: true"), clue(text))
    assert(!text.contains("GITQUERY_GIT_ERROR"), clue("exit 1 = 布尔假，不是错误"))
  }

  test("T5c merge-base 缺 rev → 明确拒绝（GITQUERY_BAD_ARG）") {
    val root = newRepo("gq-mb-bad-")
    commit(root, "c1", "a.txt" -> "1\n")
    val res = call(raw("merge-base"), ctx(root))
    assert(res.isLeft, clue(res))
    assert(res.swap.getOrElse("").contains("requires `rev`"), clue(res))
  }

  /* ═══════════ T6 --no-ext-diff / --no-textconv 实际生效（armed trap）═══════════ */

  /** armed trap 夹具：仓库级 `diff.external` + 逐路径 external diff driver +
   *  `core.fsmonitor` 三条可执行通道，各造一个哨兵（触发即落盘）。 */
  private def armedRepo(prefix: String): (os.Path, List[os.Path]) =
    val root = newRepo(prefix)
    os.write.over(root / ".gitattributes", "*.md diff=evil_driver\n")
    commit(root, "c1", "doc.md" -> "one\n")
    commit(root, "c2", "doc.md" -> "one\ntwo\n")
    val extSentinel = root / "PWNED_ext_diff_external"
    val drvSentinel = root / "PWNED_ext_diff_driver"
    val fsSentinel = root / "PWNED_fsmonitor"
    git(root, "config", "diff.external", s"sh -c 'touch $extSentinel; echo EXT-DIFF-EXTERNAL-RAN; true'")
    git(root, "config", "diff.evil_driver.command", s"sh -c 'touch $drvSentinel; echo EXT-DIFF-DRIVER-RAN; true'")
    val hook = root / "fsmonitor.sh"
    os.write.over(hook, "#!/bin/sh\ntouch '" + fsSentinel.toString + "'\necho \"\"\n")
    os.perms.set(hook, "rwxr-xr-x")
    git(root, "config", "core.fsmonitor", hook.toString)
    (root, List(extSentinel, drvSentinel, fsSentinel))

  private def sentinelsTouched(sentinels: List[os.Path]): List[String] =
    sentinels.filter(os.exists(_)).map(_.last)

  test("T6a 负控（trap 有效性）：裸 `git diff` 打补丁**确会**执行外部命令 ⇒ 逃逸面是真的") {
    val (root, sentinels) = armedRepo("gq-trap-live-")
    val res = os
      .proc("git", "-C", root.toString, "--no-pager", "diff", "HEAD~1", "HEAD")
      .call(cwd = root, check = false, mergeErrIntoOut = true)
    assert(res.exitCode == 0, clue(res.out.text()))
    val touched = sentinelsTouched(sentinels)
    assert(touched.nonEmpty, "armed trap 未触发 ⇒ 本 spec 的反证无效（trap 未 arm）")
    assert(res.out.text().contains("EXT-DIFF"), clue(res.out.text().take(300)))
  }

  test("T6b diff --stat / --name-status 在 armed 仓不执行任何外部命令（--no-ext-diff 生效）") {
    val (root, sentinels) = armedRepo("gq-trap-stat-")
    val stat = call(raw("diff", "rev" -> "HEAD~1".asJson, "rev2" -> "HEAD".asJson), ctx(root))
      .getOrElse(fail("diff --stat 应当成功"))
    assert(stat.contains("doc.md") || stat.contains("1 file changed"), clue(stat))
    val ns = call(
      raw("diff", "rev" -> "HEAD~1".asJson, "rev2" -> "HEAD".asJson, "format" -> "name-status".asJson),
      ctx(root)
    ).getOrElse(fail("diff --name-status 应当成功"))
    assert(ns.contains("doc.md"), clue(ns))
    assertEquals(sentinelsTouched(sentinels), Nil, "GitQuery 的任何 diff 形态都不得触发外部命令")
  }

  test("T6c diff 的 worktree 形态（rev vs 工作区）也不得有逃逸（含 core.fsmonitor 面）") {
    val (root, sentinels) = armedRepo("gq-trap-wt-")
    os.write.over(root / "doc.md", "one\ntwo\nthree\n")
    val text = call(raw("diff", "rev" -> "HEAD".asJson), ctx(root)).getOrElse(fail("diff rev..worktree 应当成功"))
    assert(text.contains("doc.md"), clue(text))
    assertEquals(sentinelsTouched(sentinels), Nil, "core.fsmonitor / diff.external / diff driver 全部不得执行")
  }

  test("T6d show --stat 同仓不执行外部命令；argv 面逐字带 --no-ext-diff --no-textconv") {
    val (root, sentinels) = armedRepo("gq-trap-show-")
    val text = call(raw("show", "rev" -> "HEAD".asJson), ctx(root)).getOrElse(fail("show --stat 应当成功"))
    assert(text.contains("doc.md"), clue(text))
    assertEquals(sentinelsTouched(sentinels), Nil)

    // argv 钉死（结构面证明）：diff/show 的 diff 机制闸恒在位，且零自由 token 面
    val rootPath = os.Path(root.toString)
    List("stat", "name-status").foreach { fmt =>
      val argv = GitQueryTool
        .argvFor("diff", rootPath, None, Some("HEAD~1"), Some("HEAD"), GitQueryTool.DefaultLogLimit, fmt)
        .getOrElse(fail("argv 应当成立"))
      assert(argv.contains("--no-ext-diff"), clue(argv))
      assert(argv.contains("--no-textconv"), clue(argv))
      assert(!argv.contains("-c"), clue(argv))
      assert(!argv.exists(_.startsWith("--exec-path")), clue(argv))
      assert(!argv.exists(_.startsWith("--upload-pack")), clue(argv))
      assert(!argv.exists(_.contains("::")), clue(argv))
      assert(argv.contains(s"--$fmt"), clue(argv)) // 档位 = 枚举值映射，不是模型给的 flag 直通
    }
    val showArgv = GitQueryTool
      .argvFor("show", rootPath, None, Some("HEAD"), None, GitQueryTool.DefaultLogLimit, "stat")
      .getOrElse(fail("show argv 应当成立"))
    assert(showArgv.contains("--no-ext-diff") && showArgv.contains("--no-textconv"), clue(showArgv))
    // pinned env（第二道硬化面）逐字在位
    val env = GitQueryTool.PinnedEnv
    assertEquals(env.get("GIT_CONFIG_NOSYSTEM"), Some("1"))
    assertEquals(env.get("GIT_CONFIG_KEY_0"), Some("core.fsmonitor"))
    assertEquals(env.get("GIT_CONFIG_VALUE_0"), Some("false"))
    assertEquals(env.get("GIT_NO_REPLACE_OBJECTS"), Some("1"))
  }

  /* ═══════════ T7 首行仓根 + log 上限钳制 + device 面 ═══════════ */

  test("T7a 每次回执首行 = 解析到的仓根（子目录入参也解析到 worktree 根）") {
    val root = newRepo("gq-head-")
    commit(root, "c1", "sub/dir/a.txt" -> "a\n")
    val subdir = root / "sub" / "dir"
    val text = call(raw("log", "repo" -> subdir.toString.asJson), ctx(root)).getOrElse(fail("log 应当成功"))
    val first = text.linesIterator.next()
    assert(first.startsWith("repo: "), clue(first))
    // 仓根 = `git rev-parse --show-toplevel` 的形态（macOS 上 /private 等符号链接会被 git 归一）
    assert(first.contains("gq-head-"), clue(first))
    assert(!first.contains("/sub/dir"), clue(first))
  }

  test("T7b log -n 上限：requested 999 钳到 50 并给读数；缺省 20；固定 format 档在位") {
    val root = newRepo("gq-limit-")
    (1 to 3).foreach(i => commit(root, s"c$i", "a.txt" -> s"$i\n"))
    val capped = call(raw("log", "limit" -> 999.asJson), ctx(root)).getOrElse(fail("log 应当成功"))
    assert(capped.contains("limit: 50 (requested 999, hard cap 50)"), clue(capped.take(300)))
    assert(capped.linesIterator.toList(1).startsWith("cmd: "), clue(capped.take(200)))
    assert(capped.linesIterator.toList(1).contains("-n 50"), clue(capped.take(200)))
    val deflt = call(raw("log"), ctx(root)).getOrElse(fail("log 应当成功"))
    assert(deflt.linesIterator.toList(1).contains(s"-n ${GitQueryTool.DefaultLogLimit}"), clue(deflt.take(200)))
    assert(!deflt.contains("limit: "), "未超上限不得出现钳制读数")
    assert(deflt.linesIterator.toList(1).contains("--format=%h %ad %an %s"), clue(deflt.take(200)))
    assertEquals(GitQueryTool.MaxLogLimit, 50)
  }

  test("T7c log 的 path 收窄生效（无匹配返回 (no output) 而不报错）") {
    val root = newRepo("gq-path-")
    commit(root, "c1", "a.txt" -> "a\n")
    commit(root, "c2", "b.txt" -> "b\n")
    val onlyB = call(raw("log", "path" -> "b.txt".asJson), ctx(root)).getOrElse(fail("log 应当成功"))
    assert(onlyB.contains("c2"), clue(onlyB))
    assert(!onlyB.contains("c1"), clue(onlyB))
    val none = call(raw("log", "path" -> "nope.txt".asJson), ctx(root)).getOrElse(fail("log 应当成功"))
    assert(none.contains("(no output)"), clue(none))
  }

  test("T7d device 面：local 放行 / 其它设备显式拒绝（v2 指引）；不进 remoteableTools") {
    val root = newRepo("gq-device-")
    commit(root, "c1", "a.txt" -> "a\n")
    val local = call(raw("log", "device" -> "local".asJson), ctx(root))
    assert(local.isRight, clue(local))
    val remote = call(raw("log", "device" -> "DESKTOP-IJBBEOT".asJson), ctx(root))
    assert(remote.isLeft, clue(remote))
    val msg = remote.swap.getOrElse("")
    assert(msg.contains("GITQUERY_DEVICE_UNSUPPORTED"), clue(msg))
    assert(msg.contains("v2"), clue(msg))
    assert(!nebflow.core.tools.RemoteExecutor.remoteableTools.contains("GitQuery"))
  }

  test("T7e 失败路径不抛裸异常：不存在的 rev 给可读回执（no stack / no java.lang）") {
    val root = newRepo("gq-badrev-")
    commit(root, "c1", "a.txt" -> "a\n")
    val bad = call(raw("show", "rev" -> "no-such-rev-xyz".asJson), ctx(root))
    assert(bad.isLeft, clue(bad))
    val msg = bad.swap.getOrElse("")
    assert(msg.contains("GITQUERY_GIT_ERROR"), clue(msg))
    assert(Seq("Exception", "at nebflow.", "java.lang").forall(s => !msg.contains(s)), clue(msg.take(300)))
    assert(Try(GitQueryTool.resolveRoot(root)).toOption.exists(_.isRight), "正控：仓本身可解析")
  }

end GitQuerySpec

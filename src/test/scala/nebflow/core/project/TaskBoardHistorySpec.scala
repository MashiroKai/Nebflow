package nebflow.core.project

import io.circe.parser.decode
import munit.FunSuite

import java.nio.file.Files
import java.security.MessageDigest

/**
 * TaskBoardHistory spec（TaskBoard 升级批 · R7 变更史独立落盘）。
 *
 * 覆盖（D1 口径全量）：
 *  - per-workspace 落盘：`<workspace>/.nebflow/task-history.jsonl`（一项目一板面一史
 *    文件，**不是**全局单档）；两个 workspace 完全隔离；
 *  - append → readFor 往返（升序、字段保真、只取该 id、limit 记账、bad 行跳过计数）；
 *  - 容错：坏行跳过不崩；尾字节非换行时先补 `\n`（crash 半行不粘连）；
 *  - 轮转：> 20,000 行 / > 5 MiB 触发；覆盖上一代（只留 2 代 ⇒ 磁盘硬顶 ≈10 MiB）；
 *    新活动文件首行写 `rotate` 事件；跨代仍可读；
 *  - 读路径零写盘（前后 sha256 一致——「读不写盘」红线）；
 *  - 手动清理无感（删文件后下次写自建）；
 *  - 常量口径（5 MiB / 20,000 行 / 2 代）与未知 kind/未知键容忍（注册式扩展）。
 *
 * 隔离纪律（RK-2）：全部用例只碰 `Files.createTempDirectory` 造的临时 workspace；
 * 零接触作者活板面与 `~/.nebflow`（本文件不调 PathUtil.setDataRoot）。
 */
class TaskBoardHistorySpec extends FunSuite:

  private def tmpWs(): os.Path = os.Path(Files.createTempDirectory("nb-tbhist-spec"))

  private def hist(ws: os.Path): TaskBoardHistory = TaskBoardHistory.open(ws.toString)

  private def ev(id: String, kind: String, actor: String, text: Option[String] = None): TaskBoardEvent =
    TaskBoardEvent(at = "2026-09-11T09:00:00Z", kind = kind, id = Some(id), actor = actor, text = text)

  private def sha(p: os.Path): String =
    MessageDigest.getInstance("SHA-256").digest(os.read.bytes(p)).map("%02x".format(_)).mkString

  private def lineJson(id: String, marker: String): String =
    s"""{"at":"2026-09-11T09:00:00Z","id":"$id","actor":"system","kind":"create","detail":"$marker"}"""

  private def bigFile(p: os.Path, lines: Int, marker: String): Unit =
    val sb = new StringBuilder(lines * 96)
    (1 to lines).foreach(i => sb.append(lineJson((i % 7 + 1).toString, s"$marker-$i")).append('\n'))
    os.write.over(p, sb.toString, createFolders = true)

  // ===== ① per-workspace 落盘（RK-7：一项目一板面一史文件）=====

  test("落盘路径 = <workspace>/.nebflow/task-history.jsonl（per-workspace，非全局单档）"):
    val a = tmpWs(); val b = tmpWs()
    try
      val ha = hist(a)
      assertEquals(ha.file, a / ".nebflow" / "task-history.jsonl")
      assertEquals(ha.archiveFile, a / ".nebflow" / "task-history.1.jsonl")
      assertEquals(ha.appendSync(ev("1", "create", "dispatcher")), None)
      assert(os.exists(ha.file), "史文件落在本人 workspace 内")
      assert(!os.exists(b / ".nebflow" / "task-history.jsonl"), "另一 workspace 零污染")
      assertEquals(hist(b).readFor("1").total, 0)
      assertEquals(hist(a).readFor("1").total, 1)
      // 本用例不写 ~/.nebflow（路径全部由 workspace 派生）
      assert(ha.file.toString.startsWith(a.toString), ha.file.toString)
    finally { os.remove.all(a); os.remove.all(b) }

  // ===== ② append → readFor 往返 =====

  test("append → readFor：升序返回、字段保真、只取该 id、limit 记账 truncated"):
    val ws = tmpWs()
    try
      val h = hist(ws)
      assertEquals(h.appendSync(TaskBoardEvent(at = "2026-09-11T09:00:00Z", kind = "create",
        id = Some("1"), actor = "dispatcher", detail = Some("title=甲"))), None)
      assertEquals(h.appendSync(TaskBoardEvent(at = "2026-09-11T09:01:00Z", kind = "update",
        id = Some("1"), actor = "node", field = Some("status"), from = Some("open"),
        to = Some("in_progress"))), None)
      assertEquals(h.appendSync(TaskBoardEvent(at = "2026-09-11T09:02:00Z", kind = "log",
        id = Some("1"), actor = "node", text = Some("第一段记录"), links = List("/tmp/a.md"))), None)
      assertEquals(h.appendSync(TaskBoardEvent(at = "2026-09-11T09:03:00Z", kind = "create",
        id = Some("2"), actor = "dispatcher")), None) // 另一条目——预筛须排除

      val r = h.readFor("1")
      assertEquals(r.events.map(_.kind), List("create", "update", "log"), "升序 = 文件行序")
      assertEquals(r.total, 3)
      assertEquals(r.skipped, 0)
      assert(!r.truncated)
      val log = r.events.last
      assertEquals(log.text, Some("第一段记录"))
      assertEquals(log.links, List("/tmp/a.md"))
      assertEquals(log.actor, "node")
      assertEquals(log.at, "2026-09-11T09:02:00Z")
      assertEquals(r.events(1).field, Some("status"))
      assertEquals(r.events(1).from, Some("open"))
      assertEquals(r.events(1).to, Some("in_progress"))

      val one = h.readFor("1", limit = 1)
      assertEquals(one.events.map(_.kind), List("log"), "limit 取最近 N 条")
      assertEquals(one.total, 3, "total 记全部命中（含窗口外）")
      assert(one.truncated, "窗口外有事件 ⇒ truncated")

      assertEquals(h.readFor("9").total, 0, "不存在的 id 零命中（不与其他 id 串味）")
    finally os.remove.all(ws)

  // ===== ③ 容错：坏行跳过 + 尾换行补齐 =====

  test("坏行跳过不崩并计数：预筛命中的不可解析行 → skipped+1，好行照读"):
    val ws = tmpWs()
    try
      val h = hist(ws)
      os.write.over(h.file, lineJson("1", "good-1") + "\n" +
        """{"id":"1","kind":"update","broken""" + "\n" + // 非法 JSON 但含 "id":"1" 预筛命中
        lineJson("1", "good-2") + "\n", createFolders = true)
      val r = h.readFor("1")
      assertEquals(r.events.map(_.detail), List(Some("good-1"), Some("good-2")))
      assertEquals(r.total, 2)
      assertEquals(r.skipped, 1, "坏行计数（show 末尾可见）+ 不崩")
      // 坏行不阻塞后续追加
      assertEquals(h.appendSync(ev("1", "log", "node")), None)
      assertEquals(h.readFor("1").total, 3)
    finally os.remove.all(ws)

  test("尾字节非换行（crash 半行）→ 追加前先补 \\n，两行均独立可解析"):
    val ws = tmpWs()
    try
      val h = hist(ws)
      os.write.over(h.file, lineJson("1", "no-trailing-newline"), createFolders = true)
      assertEquals(h.appendSync(ev("1", "log", "node")), None)
      val raw = os.read(h.file)
      assertEquals(raw.count(_ == '\n'), 2, "半行与下一条之间被补齐换行")
      val r = h.readFor("1")
      assertEquals(r.events.map(_.kind), List("create", "log"))
      assertEquals(r.skipped, 0, "无粘连 ⇒ 零坏行")
    finally os.remove.all(ws)

  // ===== ④ 轮转（覆盖上一代）=====

  test("轮转（行数）：活动文件 > 20,000 行 → 改名覆盖 .1 + 新文件首行 rotate 事件"):
    val ws = tmpWs()
    try
      val h = hist(ws)
      bigFile(h.file, TaskBoardHistory.RotationMaxLines + 1, "gen1")
      val before = os.read.lines(h.file).size
      assert(before > TaskBoardHistory.RotationMaxLines, before.toString)
      assertEquals(h.appendSync(TaskBoardEvent(at = "2026-09-11T10:00:00Z", kind = "log",
        id = Some("1"), actor = "node", text = Some("轮转后第一笔"))), None)

      assert(os.exists(h.archiveFile), "上一代归档存在")
      assertEquals(os.read.lines(h.archiveFile).size, before, "归档 = 轮转前的活动文件全文")
      val first = decode[TaskBoardEvent](os.read.lines(h.file).head).toOption.get
      assertEquals(first.kind, "rotate", "新活动文件首行 = rotate 事件")
      assert(first.detail.exists(_.contains("bytes=")), first.detail.toString)
      assertEquals(first.actor, "system")
      // 跨代读取：归档里的 id=1 事件 + 轮转后的新事件都要能读回
      val r = h.readFor("1")
      assert(r.total > 1, s"跨代读取（归档 + 活动）: $r")
      assertEquals(r.events.last.text, Some("轮转后第一笔"), "升序：归档在前、活动在后")
    finally os.remove.all(ws)

  test("轮转（字节）：活动文件 > 5 MiB → 轮转（与行数先到为准）"):
    val ws = tmpWs()
    try
      val h = hist(ws)
      val bytes = TaskBoardHistory.RotationMaxBytes + 200_000L
      val marker = "x" * 200
      val perLine = lineJson("1", marker).length + 1
      bigFile(h.file, math.ceil(bytes.toDouble / perLine).toInt, marker)
      assert(os.size(h.file) > TaskBoardHistory.RotationMaxBytes, os.size(h.file).toString)
      val linesBefore = os.read.lines(h.file).size
      assertEquals(h.appendSync(ev("1", "log", "node")), None)
      assert(os.exists(h.archiveFile), "字节越限同样轮转")
      assertEquals(os.read.lines(h.archiveFile).size, linesBefore)
      assertEquals(os.read.lines(h.file).size, 2, "新活动文件 = rotate 事件 + 本次事件")
    finally os.remove.all(ws)

  test("只保留上一代：连续两次轮转后第一代被覆盖（磁盘硬顶 ≈2 代 / 10 MiB）"):
    val ws = tmpWs()
    try
      val h = hist(ws)
      bigFile(h.file, TaskBoardHistory.RotationMaxLines + 1, "gen1")
      assertEquals(h.appendSync(ev("1", "log", "node")), None) // 第 1 次轮转 → archive = gen1
      assert(os.read(h.archiveFile).contains("gen1"))
      os.write.append(h.file, (1 to TaskBoardHistory.RotationMaxLines + 1)
        .map(i => lineJson("2", s"gen2-$i")).mkString("\n") + "\n")
      assertEquals(h.appendSync(ev("2", "log", "node")), None) // 第 2 次轮转 → archive = gen2
      val arch = os.read(h.archiveFile)
      assert(arch.contains("gen2"), "归档 = 最近一代")
      assert(!arch.contains("gen1"), "更早一代被覆盖丢弃（只保留上一代）")
      assertEquals(os.list(ws / ".nebflow").count(_.last.startsWith("task-history")).toInt, 2,
        "磁盘上恒为 2 个史文件（活动 + 上一代）")
    finally os.remove.all(ws)

  // ===== ⑤ 读路径零写盘 + 手动清理无感 =====

  test("读路径零写盘：readFor 前后 sha256 与文件数一致（不轮转、不改文件）"):
    val ws = tmpWs()
    try
      val h = hist(ws)
      bigFile(h.file, TaskBoardHistory.RotationMaxLines + 1, "gen1") // 已越轮转阈值
      val before = sha(h.file)
      val filesBefore = os.list(ws / ".nebflow").size
      val r = h.readFor("1", limit = 5)
      assert(r.total > 0)
      assertEquals(sha(h.file), before, "读不写盘（轮转只在写路径触发）")
      assertEquals(os.list(ws / ".nebflow").size, filesBefore, "读路径不新增/不改名文件")
      assert(!os.exists(h.archiveFile), "读路径不产生归档")
    finally os.remove.all(ws)

  test("手动清理无感：删掉活动文件与归档后，下一次写自建、旧史不再可见"):
    val ws = tmpWs()
    try
      val h = hist(ws)
      assertEquals(h.appendSync(ev("1", "create", "dispatcher")), None)
      os.remove(h.file)
      if os.exists(h.archiveFile) then os.remove(h.archiveFile)
      assertEquals(h.readFor("1").total, 0, "手动清理后旧史不可见（人工归档/删除是治理路径）")
      assertEquals(h.appendSync(ev("1", "log", "node")), None, "下次写自建（工具无感）")
      assertEquals(h.readFor("1").total, 1)
    finally os.remove.all(ws)

  // ===== ⑥ 常量口径 + 注册式扩展 =====

  test("常量口径：5 MiB / 20,000 行 / 探测门槛 1 MiB（2 万行 ≥ 1.4 MiB 的下界保证）"):
    assertEquals(TaskBoardHistory.RotationMaxBytes, 5L * 1024 * 1024)
    assertEquals(TaskBoardHistory.RotationMaxLines, 20_000)
    assertEquals(TaskBoardHistory.LineCountProbeBytes, 1L * 1024 * 1024)
    assertEquals(TaskBoardHistory.FileName, "task-history.jsonl")
    assertEquals(TaskBoardHistory.ArchiveFileName, "task-history.1.jsonl")

  test("注册式扩展：未知 kind 与未知键照收不拒（旧行零迁移、新字段向前兼容）"):
    val ws = tmpWs()
    try
      val h = hist(ws)
      os.write.over(h.file,
        """{"at":"2026-09-11T09:00:00Z","id":"1","actor":"system","kind":"future-kind","unknownKey":42}""" + "\n",
        createFolders = true)
      val r = h.readFor("1")
      assertEquals(r.skipped, 0, "未知 kind/未知键不是坏行")
      assertEquals(r.events.head.kind, "future-kind")
      assertEquals(r.events.head.links, Nil, "缺键回落默认")
      assertEquals(r.events.head.actor, "system")
    finally os.remove.all(ws)

  test("note 类 / 状态类分类单点（作者口径：历史的主体 = note 内容变更）"):
    val note = List(
      TaskBoardEvent(at = "t", kind = "log", id = Some("1"), text = Some("x")),
      TaskBoardEvent(at = "t", kind = "update", id = Some("1"), field = Some("note"), prev = Some("a"), next = Some("b")),
      TaskBoardEvent(at = "t", kind = "close", id = Some("1"), prev = Some("a"), next = Some("a\n[done] b")))
    val state = List(
      TaskBoardEvent(at = "t", kind = "create", id = Some("1")),
      TaskBoardEvent(at = "t", kind = "update", id = Some("1"), field = Some("status"), from = Some("open"), to = Some("in_progress")),
      TaskBoardEvent(at = "t", kind = "update", id = Some("1"), field = Some("title"), prev = Some("a"), next = Some("b")),
      TaskBoardEvent(at = "t", kind = "close", id = Some("1"), from = Some("open"), to = Some("done")),
      TaskBoardEvent(at = "t", kind = "prune", id = Some("1")),
      TaskBoardEvent(at = "t", kind = "quarantine"),
      TaskBoardEvent(at = "t", kind = "rotate"))
    note.foreach(e => assert(TaskBoardHistory.isNoteChange(e), s"note 类: $e"))
    state.foreach(e => assert(!TaskBoardHistory.isNoteChange(e), s"状态类: $e"))

  test("readFor 分类过滤：两区各自独立窗口（状态类不占 note 主线的窗口）+ countLinesFor 廉价探针"):
    val ws = tmpWs()
    try
      val h = hist(ws)
      // 5 条状态 + 3 条 note，交错写入
      (1 to 5).foreach { i =>
        assertEquals(h.appendSync(TaskBoardEvent(at = s"t$i", kind = "update", id = Some("1"),
          field = Some("status"), from = Some("open"), to = Some("in_progress"))), None)
        if i <= 3 then
          assertEquals(h.appendSync(TaskBoardEvent(at = s"t$i-note", kind = "update", id = Some("1"),
            field = Some("note"), prev = Some(s"p$i"), next = Some(s"n$i"))), None)
      }
      val notes = h.readFor("1", limit = 2, only = TaskBoardHistory.isNoteChange)
      assertEquals(notes.total, 3, "只数 note 类")
      assertEquals(notes.events.map(_.next), List(Some("n2"), Some("n3")), "note 窗口独立于状态类")
      assert(notes.truncated)
      val states = h.readFor("1", limit = 30, only = ev => !TaskBoardHistory.isNoteChange(ev))
      assertEquals(states.total, 5, "只数状态类")
      assertEquals(h.countLinesFor("1"), 8, "廉价探针 = 出现的行数（不解析）")
      assertEquals(h.countLinesFor("2"), 0)
    finally os.remove.all(ws)

end TaskBoardHistorySpec

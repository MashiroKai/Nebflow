package nebflow.gateway

import cats.effect.unsafe.implicits.global
import ch.qos.logback.classic.Logger as LogbackLogger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import org.slf4j.LoggerFactory

import scala.jdk.CollectionConverters.*

/**
 * #159/#176（wtsurv 批，2026-09-14）：**UI 文件浏览器删除成功分支的审计留痕**验收。
 *
 * 为什么这条日志是本批的交付物（取证件 `20260913_100008_worktree-vanish-forensics.md`
 * §1.4 第 1 条 / §6.1 第 2 条）：WS `deletePath` / `deletePaths` 走 `os.remove.all`
 * **递归删目录、不碰 `.git/worktrees/<name>` 注册** ⇒ 精确制造「目录消失 + 注册残留 =
 * prunable」签名（J1）；而**成功路径零日志**（失败才 warn）⇒ 该类路径在取证面根本
 * 不存在，这正是「2026-09-11 两例 worktree 消失**直接删除者未证**」的最大盲区。
 *
 * 本 spec 钉两条契约：
 *   ① **正控**：成功删除（单文件 / 递归目录）各写 **1 行 INFO**，含 **canonical 被删
 *      路径** + **来源会话** + 形态 + 通道名；
 *   ② **负控**：被守卫拒绝 / 不存在的路径**不得**写成功日志，且仍按原语义落 `failed`
 *      （零行为变更）。
 *
 * 日志捕获 = logback `ListAppender` 挂到生产同名 logger `nebflow.ws`（`NebflowLogger`
 * 就经 `LoggerFactory` 取同名 logger ⇒ 捕获的是**生产写点本体**，不是替身）。
 */
class DeletePathAuditLogSpec extends munit.FunSuite:

  private def capturedLogs[A](body: => A): (A, List[String]) =
    val lg = LoggerFactory.getLogger("nebflow.ws").asInstanceOf[LogbackLogger]
    val app = new ListAppender[ILoggingEvent]()
    app.start()
    lg.addAppender(app)
    try
      val out = body
      (out, app.list.asScala.toList.map(_.getFormattedMessage))
    finally lg.detachAppender(app)

  /** 只取本批新增的删除审计行（`channel=ui-file-explorer` 是单一 marker）。 */
  private def auditLines(lines: List[String]): List[String] =
    lines.filter(_.contains("channel=ui-file-explorer"))

  test("④(正控) 批量删除成功分支写审计 INFO：canonical 路径 + 来源会话 + 形态") {
    val root = os.temp.dir(prefix = "wtsurv-del-audit")
    os.write(root / "a.txt", "x")
    os.makeDir.all(root / "sub")
    os.write(root / "sub" / "b.txt", "y")
    val (out, logs) = capturedLogs {
      WebSocketRoutes
        .deletePathsSafely(List("a.txt", "sub"), root, "sess-audit-1")
        .unsafeRunSync()
    }
    val (deleted, failed) = out
    assertEquals(deleted, List("a.txt", "sub"), "成功分支语义不得改变（既有 BatchDeleteSpec 契约）")
    assertEquals(failed, List.empty[(String, String)])
    val mine = auditLines(logs)
    assertEquals(mine.size, 2, s"每个成功删除各 1 行审计，实得 $mine")
    assert(
      mine.exists(l =>
        l.contains("deletePaths: removed '") && l.contains("a.txt") &&
          l.contains("kind=file") && l.contains("session=sess-audit-1")
      ),
      mine.toString
    )
    assert(
      mine.exists(l =>
        l.contains((root / "sub").toString) && l.contains("kind=dir-recursive") &&
          l.contains("session=sess-audit-1")
      ),
      mine.toString
    )
    os.remove.all(root)
  }

  test("④(负控) 守卫拒绝 / 不存在的路径：不写成功日志，且仍落 failed（零行为变更）") {
    val root = os.temp.dir(prefix = "wtsurv-del-audit-neg")
    os.write(root / "keep.txt", "k")
    val (out, logs) = capturedLogs {
      WebSocketRoutes
        .deletePathsSafely(List("../outside.txt", ".", "vanished.txt"), root, "sess-audit-2")
        .unsafeRunSync()
    }
    val (deleted, failed) = out
    assertEquals(deleted, List("vanished.txt"), "不存在的路径仍是「no-op 成功」（既有 BatchDeleteSpec 契约，零行为变更）")
    assertEquals(failed.size, 2, s"越界与项目根自身各落一条 failed，实得 $failed")
    assert(failed.map(_._1).toSet == Set("../outside.txt", "."), failed.toString)
    assert(auditLines(logs).isEmpty, s"失败 / 未实存路径不得写成功日志（不误报），实得 ${auditLines(logs)}")
    assert(os.exists(root / "keep.txt"), "被拒绝的删除不得触碰同级文件")
    os.remove.all(root)
  }

  test("④(文案单点) deleteAuditLine：单删与批删同源同形（防两处漂移）") {
    val one = WebSocketRoutes.deleteAuditLine("deletePath", "/p/x.txt", "s1", isDir = false)
    val many = WebSocketRoutes.deleteAuditLine("deletePaths", "/p/x.txt", "s1", isDir = false)
    assert(one.startsWith("deletePath: removed '/p/x.txt'"), one)
    assert(many.startsWith("deletePaths: removed '/p/x.txt'"), many)
    assert(one.contains("(session=s1, kind=file, channel=ui-file-explorer)"), one)
    assert(many.contains("(session=s1, kind=file, channel=ui-file-explorer)"), many)
    val dir = WebSocketRoutes.deleteAuditLine("deletePath", "/p/d", "s2", isDir = true)
    assert(dir.contains("kind=dir-recursive"), dir)
  }
end DeletePathAuditLogSpec

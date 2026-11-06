package nebflow.core.ask

import cats.effect.IO
import nebflow.shared.{NebflowLogger, PathUtil}

import java.nio.charset.StandardCharsets
import scala.util.control.NonFatal

/* ═══════════════════════════════════════════════════════════════════════════
 * Canvas 记事本（note）持久层 —— 引擎侧真源（持久化缺陷批 2026-11-05）
 *
 * 报障（作者 2026-11-05）：「Canvas Note 记录的内容会丢」。改前实测（见批报告
 * 复现表）：内容只写浏览器 localStorage（`<brand>_note:<sid>`），**引擎侧零写点**
 * ⇒ 换浏览器/换设备/换 origin（localhost ↔ 127.0.0.1、换端口、手机层另一台设备）
 * 一律读不到；清站点数据即彻底丢失。localStorage 是「按 origin × 设备」的私有
 * 易失层，做不了「随文件」的语义。
 *
 * 本件的定位 = 记事本内容的**权威落点**，与 ask 会话同一套文件身份：
 *   sid = `ask-<sha256(absPath) 前 8 hex>`（AskSessions.sessionIdFor，单点同源）
 *   ⇒ 同一文件在任何设备/任何浏览器上派生同一个 sid ⇒ 读到同一份记事本。
 * 落盘：`<dataRoot>/ask/notes/<sid>.md`
 *   · 与 ask 绑定表同目录族（`<dataRoot>/ask/index.json`），一起属于用户数据；
 *   · 原子写（AtomicJson.writeSync：临时件 + ATOMIC_MOVE），进程被杀不留半截；
 *   · 内容 = 纯文本原文（.md 后缀只表达「这是文档类速记」，不做任何格式解析）。
 *
 * 容量与清理策略（明确口径，不静默）：
 *   · 单件上限 MaxBytes = 256 KiB（UTF-8 字节）—— 记事本是「随手记」，超限即拒写
 *     并回执 too-large（前端显示可读状态行，不静默截断用户文本）；
 *   · 总量 = 单件上限 × 文件数，无全局强制淘汰：**用户内容不自动删**（记事本正文
 *     删掉就找不回来，比占盘严重得多）。真要回收 = 用户自己清空该文件的记事本
 *     （空文本 ⇒ 本件删除文件，不留空壳）。
 *
 * 并发口径（多标签页/多设备）：**最后写者胜**——本件只返回 supersededTs 供上层
 * 判断「本次覆盖了别人更新的版本」，不加锁（单机单进程下 os 写是原子的；跨设备
 * 并发与 ask 转录「服务端为权威」同律，前端负责打开面板即重读、失焦/切回即对账）。
 * ═══════════════════════════════════════════════════════════════════════════ */

/** 一次读盘结果。`ts` = 文件 mtime（毫秒，0 = 不存在）；`exists` 显式给前端做判据。 */
final case class AskNoteSnapshot(text: String, ts: Long, exists: Boolean):
  def isEmpty: Boolean = !exists || text.isEmpty

/** 写结果（sealed：调用方必须逐支处理，禁「顺手当成功」）。 */
enum AskNoteWrite:
  /** 已落盘。supersededTs > 0 ⇒ 本次覆盖了盘上 mtime = 该值的旧版本（并发写信号）。 */
  case Ok(ts: Long, supersededTs: Long)

  /** 超出单件上限（字节）。**未写盘**（盘上旧值原样保留）。 */
  case TooLarge(bytes: Int, limit: Int)

  /** 会话 id 形态非法（防路径穿越 / 防任意文件名）。**未写盘**。 */
  case BadSessionId(id: String)

  /** 落盘失败（磁盘满 / 权限 / 目录不可建）。**未写盘**。 */
  case Failed(message: String)

/**
 * 记事本落盘单点。无内存缓存（每次读盘）：记事本写入频率是「人手打字」，性能
 * 面完全不敏感，而**不缓存**换来「多设备/多进程写立刻互相可见」这个正确性。
 */
final class AskNoteStore(dataRoot: os.Path):
  private val logger = NebflowLogger.forName("nebflow.ask.notes")

  /** 单件上限（字节，UTF-8）。见文件头「容量与清理策略」。 */
  val MaxBytes: Int = 256 * 1024

  /** 落点目录：`<dataRoot>/ask/notes`（与 ask/index.json 同族）。 */
  val dir: os.Path = dataRoot / "ask" / "notes"

  /** sid 硬闸：`ask-` + 8 hex（AskSessions.sessionIdFor 的产物形态）才允许落盘——
   *  这条同时是路径穿越防线（`../`、绝对路径、盘符一律不匹配）。 */
  private val SidPattern = "^ask-[0-9a-f]{8}$".r

  def isValidSid(sid: String): Boolean =
    sid != null && SidPattern.matches(sid)

  /** sid → 记事本文件路径（调用前必须已过 [[isValidSid]]）。 */
  def pathFor(sid: String): os.Path = dir / s"$sid.md"

  /** 读某文件记事本。缺件/bad sid/读失败 ⇒ 空快照（exists=false），不抛。 */
  def read(sid: String): IO[AskNoteSnapshot] =
    IO.blocking {
      if !isValidSid(sid) then AskNoteSnapshot("", 0L, false)
      else
        val p = pathFor(sid)
        try
          if !os.exists(p) then AskNoteSnapshot("", 0L, false)
          else AskNoteSnapshot(os.read(p), os.mtime(p), true)
        catch
          case NonFatal(e) =>
            logger.warn(s"ask note read failed (non-fatal, treated as empty): $sid: ${e.getMessage}")
            AskNoteSnapshot("", 0L, false)
    }

  /**
   * 写某文件记事本。空文本 ⇒ 删件（不留空壳，与前端「空 = 删键」同律）。
   * 逐支结果见 [[AskNoteWrite]]；本方法**不抛**（失败走 Failed）。
   */
  def write(sid: String, text: String): IO[AskNoteWrite] =
    IO.blocking {
      if !isValidSid(sid) then AskNoteWrite.BadSessionId(String.valueOf(sid))
      else
        val p = pathFor(sid)
        val body = if text == null then "" else text
        val bytes = body.getBytes(StandardCharsets.UTF_8).length
        if bytes > MaxBytes then AskNoteWrite.TooLarge(bytes, MaxBytes)
        else
          try
            val previousTs = if os.exists(p) then os.mtime(p) else 0L
            if body.isEmpty then
              if previousTs > 0L then os.remove(p)
              AskNoteWrite.Ok(System.currentTimeMillis(), previousTs)
            else
              nebflow.core.AtomicJson.writeSync(p, body)
              AskNoteWrite.Ok(os.mtime(p), previousTs)
          catch
            case NonFatal(e) =>
              logger.warn(s"ask note write failed: $sid: ${e.getMessage}")
              AskNoteWrite.Failed(e.getMessage)
    }
end AskNoteStore

object AskNoteStore:

  /** 现读 dataRoot 构造（PathUtil.dataRoot 是 live def：隔离实例/--home/测试换根后
   *  下一次调用自动指向新根——与 auth.scala tokenPath 同律，不缓存实例）。 */
  def current: AskNoteStore = new AskNoteStore(PathUtil.dataRoot)
end AskNoteStore

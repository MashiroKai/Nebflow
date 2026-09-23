package nebflow.service

import nebflow.core.PathUtil
import os.Path

import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 记忆写前快照（snapshot-on-write，dream-agent 批 2026-09-05）。
 *
 * 快照先行=硬护栏：记忆白名单写路径（MemoryNoteTool 四动作）在落盘前必须先经过本对象
 * ——把【当前磁盘内容】备份到
 * `<dataRoot>/memory-backups/<时间戳>/`，备份失败则写入整体中止（fail-closed）。
 * 「绕不过去」的结构依据：两文件的全部写入面单点（MemoryStore.saveUserMemory /
 * saveAgentMemory 的调用方只有 MemoryNoteTool 与 WS `saveMemory` 旁路；原
 * DreamMode.updateMemory 直写路径已随其机制停用退役——git ls-files 实测
 * User.md 与 agents/Nebula/memory.md 均不在 ~/.nebflow 跟踪层且被 .gitignore
 * 显式排除（`/*` 白名单 + `**/memory.md`），git 留史不可依赖，快照是唯一回滚锚）。
 *
 * 与既有备份的关系：NebflowBackup（~/.nebBackup 全 home 日备、保留 7 份）是
 * 24h 节奏的灾备层，两次日备之间的记忆写它看不见；本对象是【每次写】粒度的
 * 细粒度回滚层，两者互补不替代。保留策略：每目标文件最近 KeepPerFile 份
 * （20 份 × ~150KB 最坏情形 ≈ 3MB/文件上界）。
 *
 * 快照内容 = 写前磁盘真身（本对象自读磁盘，不吃调用方可能过期的行缓存）；
 * 目标不存在（首次写入）→ 无可备份，直接放行。目录时间戳格式
 * `yyyyMMdd-HHmmss-SSS-<seq>`，同毫秒冲突由 seq 递增消解；备份文件名 =
 * 相对 dataRoot 路径折叠（`agents__Nebula__memory.md`），自描述且免歧义。
 */
object MemorySnapshot:

  /** 每目标文件保留的快照份数（超出按时间戳目录名升序淘汰最老）。 */
  val KeepPerFile: Int = 20

  /**
   * 人工（钉住）快照子树名（R8-B，2026-09-13 记忆归档批落地）。
   *
   * 淘汰键是**目录名字符串序**（见 [[prune]]）：人工目录（`manual-*` / `slim-*` / `*_manual`）
   * 与自动时间戳目录同池参与排序 ⇒ 既顶掉自动快照，自己也会被更晚日期的目录淘汰。
   * 人工快照语义 = 「钉住」（永不自动淘汰）⇒ 约定落 `memory-backups/pinned/` 子树；
   * [[prune]] 只扫快照根的**直接子目录**，故 pinned 子树天然不在槽位内——下面的显式
   * 过滤把该语义钉在代码里（防未来把 pin 目录改成直接子目录时静默回退）。
   */
  val PinnedDirName: String = "pinned"

  /** 人工快照根（pinned 子树）；gate 脚本 / 整理批的人工快照落此处，不计槽位。 */
  def pinnedRoot(root: Path = backupRoot): Path = root / PinnedDirName

  private val fmt: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS")

  /** 快照根（def 而非 val：跟随 setDataRoot——spec 钉临时目录即生效）。 */
  def backupRoot: Path = PathUtil.dataRoot / "memory-backups"

  /**
   * 快照文件名：目标路径相对 dataRoot 折叠（/ → __，双下划线防段内单下划线歧义）。
   * dataRoot 外路径按绝对路径折叠（防御性——当前调用面只有 dataRoot 内两文件）。
   */
  def backupFileName(target: Path): String =
    val rel =
      try target.relativeTo(PathUtil.dataRoot).toString
      catch case _: Exception => target.toString
    rel.replace("/", "__").replace("\\", "__")

  /**
   * 写前快照（fail-closed）：Right(备份路径) = 已备份可写；Left(原因) = 调用方
   * 必须中止写入。root 参数仅供 spec 注入（生产调用方用默认 backupRoot）。
   */
  def snapshotBeforeWrite(target: Path, root: Path = backupRoot): Either[String, Path] =
    try
      if !os.exists(target) then Right(target) // 首次写入：无可备份，放行
      else
        val content = os.read(target)
        val dir = allocateDir(root)
        val dest = dir / backupFileName(target)
        os.write(dest, content, createFolders = true)
        prune(target, root)
        Right(dest)
    catch
      case e: Exception =>
        Left(s"${e.getClass.getSimpleName}: ${Option(e.getMessage).getOrElse("(no message)")}")

  /**
   * 落地前快照闸（fail-closed，2026-09-13 缺失自愈批 / 方案 §6 补齐项「把落地前快照做成
   * 可执行前置闸」）。
   *
   * 动机：`MemorySnapshot` 自诞生起只有单文件 [[snapshotBeforeWrite]]，而记忆整理的
   * **直接写通道**（通用 Edit/Write）不经过它 ⇒ 唯一回滚锚只剩「动笔前手动快照」这条
   * **纪律**（无 fail-closed）。本方法把那条纪律升级成引擎侧**可执行前置闸**：一次落地
   * 前把全部目标文件（三层记忆 + 队列 + 变更史）逐文件备份 + 落一张 sha256 断言表，并在
   * 写表后用 `sha(副本) == sha(源)` **逐文件复核**。任何一步失败（不可写、读回不一致、
   * 表写不出）⇒ `Left`，调用方**必须中止落地**（零文件写）。
   *
   * 与 [[snapshotBeforeWrite]] 的分工：后者是单文件写前快照（写路径调用方自持）；
   * 本方法是**批次闸**（多目标 + 断言表 + 读回复核）。
   *
   * 目标不存在（首次写入 / 本轮没点名它）⇒ 记为 `absent`，不阻断（无可回滚对象）。
   */
  final case class GateFile(path: String, sha256: String, bytes: Long, absent: Boolean)
  final case class GateSet(dir: Path, files: Vector[GateFile], label: String)

  def snapshotGate(
    targets: Vector[Path],
    label: String,
    root: Path = backupRoot
  ): Either[String, GateSet] =
    try
      val dir = allocateDir(root)
      val rows = targets.distinct.map { t =>
        if !os.exists(t) then GateFile(t.toString, "", 0L, absent = true)
        else
          val content = os.read.bytes(t)
          val src = sha256(content)
          val dest = dir / backupFileName(t)
          os.write(dest, content, createFolders = true)
          val copy = sha256(os.read.bytes(dest))
          if copy != src then
            throw new IllegalStateException(s"snapshot sha mismatch for ${t.toString} ($src != $copy)")
          GateFile(t.toString, src, content.length.toLong, absent = false)
      }
      val table = dir / "SNAPSHOT-SHA256.txt"
      val header =
        s"# memory-track pre-landing snapshot  label=$label  at=${LocalDateTime.now(ZoneId.systemDefault())}\n" +
          s"# files=${rows.count(!_.absent)}  absent=${rows.count(_.absent)}  (absent = nothing to back up: the target does not exist yet)\n"
      os.write.over(
        table,
        header + rows
          .map(f => s"${if f.absent then "(absent)" else f.sha256}  ${f.bytes}  ${f.path}")
          .mkString("\n") + "\n"
      )
      if !os.exists(table) then throw new IllegalStateException("snapshot assertion table not written")
      rows.filterNot(_.absent).foreach(t => prune(os.Path(t.path), root))
      Right(GateSet(dir, rows, label))
    catch
      case e: Exception =>
        Left(s"${e.getClass.getSimpleName}: ${Option(e.getMessage).getOrElse("(no message)")}")

  private def sha256(bytes: Array[Byte]): String =
    java.security.MessageDigest.getInstance("SHA-256").digest(bytes).map("%02x".format(_)).mkString

  /** 时间戳目录名分配：同毫秒冲突时 seq 递增直到不存在（防互覆）。 */
  private def allocateDir(root: Path): Path =
    val base = LocalDateTime.now(ZoneId.systemDefault()).format(fmt)
    var seq = 0
    var dir = root / s"$base-$seq"
    while os.exists(dir) do
      seq += 1
      dir = root / s"$base-$seq"
    dir

  /**
   * 保留修剪：按目录名（时间戳序）倒序，仅统计含本目标快照的目录，
   * 超出 KeepPerFile 的最老目录整目录删除（目录内单文件，一比一）。
   * `pinned/` 子树（人工快照，R8-B）不计槽位、永不自动淘汰。
   */
  private def prune(target: Path, root: Path): Unit =
    if os.exists(root) then
      val key = backupFileName(target)
      val mine = os
        .list(root)
        .filter(os.isDir(_))
        .filter(_.last != PinnedDirName) // R8-B：人工快照（pinned 子树）不进滚动槽位
        .filter(d => os.exists(d / key))
        .sortBy(_.last)
        .reverse
      mine.drop(KeepPerFile).foreach { d =>
        try os.remove.all(d)
        catch case _: Exception => () // 修剪尽力而为，不影响主流程
      }

  /** 测试钩子（spec 直接驱动修剪；生产路径由 snapshotBeforeWrite 内部调用）。 */
  private[service] def pruneForTest(target: Path, root: Path): Unit = prune(target, root)
end MemorySnapshot

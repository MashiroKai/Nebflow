package nebflow.service

import nebflow.core.PathUtil
import os.Path

import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 记忆写前快照（snapshot-on-write，dream-agent 批 2026-09-05）。
 *
 * 快照先行=硬护栏：记忆白名单写路径（MemoryEditTool 四动作、DreamMode.updateMemory
 * hook）在落盘前必须先经过本对象——把【当前磁盘内容】备份到
 * `<dataRoot>/memory-backups/<时间戳>/`，备份失败则写入整体中止（fail-closed）。
 * 「绕不过去」的结构依据：两文件的全部写入面单点（MemoryStore.saveUserMemory /
 * saveAgentMemory 的调用方只有 MemoryEditTool 与 DreamMode——git ls-files 实测
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

  private val fmt: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS")

  /** 快照根（def 而非 val：跟随 setDataRoot——spec 钉临时目录即生效）。 */
  def backupRoot: Path = PathUtil.dataRoot / "memory-backups"

  /** 快照文件名：目标路径相对 dataRoot 折叠（/ → __，双下划线防段内单下划线歧义）。
    * dataRoot 外路径按绝对路径折叠（防御性——当前调用面只有 dataRoot 内两文件）。 */
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
    catch case e: Exception =>
      Left(s"${e.getClass.getSimpleName}: ${Option(e.getMessage).getOrElse("(no message)")}")

  /** 时间戳目录名分配：同毫秒冲突时 seq 递增直到不存在（防互覆）。 */
  private def allocateDir(root: Path): Path =
    val base = LocalDateTime.now(ZoneId.systemDefault()).format(fmt)
    var seq = 0
    var dir = root / s"$base-$seq"
    while os.exists(dir) do
      seq += 1
      dir = root / s"$base-$seq"
    dir

  /** 保留修剪：按目录名（时间戳序）倒序，仅统计含本目标快照的目录，
    * 超出 KeepPerFile 的最老目录整目录删除（目录内单文件，一比一）。 */
  private def prune(target: Path, root: Path): Unit =
    if os.exists(root) then
      val key = backupFileName(target)
      val mine = os.list(root).filter(os.isDir(_)).filter(d => os.exists(d / key)).sortBy(_.last).reverse
      mine.drop(KeepPerFile).foreach { d =>
        try os.remove.all(d)
        catch case _: Exception => () // 修剪尽力而为，不影响主流程
      }

  /** 测试钩子（spec 直接驱动修剪；生产路径由 snapshotBeforeWrite 内部调用）。 */
  private[service] def pruneForTest(target: Path, root: Path): Unit = prune(target, root)
end MemorySnapshot

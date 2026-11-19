package nebflow.shared

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import io.circe.syntax.*
import nebflow.shared.{MtimeCache, MtimeFileCache}

import java.util.concurrent.ConcurrentHashMap

import scala.jdk.CollectionConverters.*

/**
 * Two-level memory store backed by Markdown files.
 *
 * Levels (personal-agent 批 2026-10-04 — memory is Nebula-only):
 *   - User:  ~/.nebflow/User.md                      (global)
 *   - Soul:  ~/.nebflow/Soul.md                      (global, root layer)
 *
 * `Soul.md` (我是谁) sits at the data root,级 with `User.md` (你是谁) ——
 * personal-agent 批把 agent 级记忆从 `agents/Nebula/memory.md` 上收到根层单文件
 * （方案 §2.1，作者 2026-10-04 09:02 裁定①）。**双读过渡**：
 * [[soulMemoryPath]] 优先，缺省回落 [[legacyAgentMemoryPath]]（避免既有记忆失联）。
 * 启动迁移腿（[[nebflow.core.SoulMigration]]）把旧文件搬进新位，旧文件保留只读。
 *
 * Team agent memory (~/.nebflow/teams/<team>/agents/<name>/memory.md) has been
 * removed; the read helpers below still exist so the memory modal degrades to
 * empty for team sessions, but nothing writes team memory anymore.
 *
 * Memory files are injected into the system prompt every turn by
 * ContextRefresher.buildMemoryBlock. Agents update them directly using Edit/Write.
 *
 * All reads use mtime-based caching.
 */
object MemoryStore:

  // --- Paths ---

  def userMemoryPath: os.Path = PathUtil.dataRoot / "User.md"

  /** `Soul.md` —— 新位（根层，与 User.md 同级）。personal-agent 批起的权威 agent 记忆面。 */
  def soulMemoryPath: os.Path = PathUtil.dataRoot / "Soul.md"

  /** 旧位：`agents/<name>/memory.md`。双读过渡的回落腿 + 迁移源。 */
  def legacyAgentMemoryPath(agentName: String): os.Path =
    PathUtil.dataRoot / "agents" / agentName / "memory.md"

  /**
   * `agents/<name>/memory.md` —— **旧位别名**（`memory.md` 文件名常量保留在此，
   * 供沙箱负向规则 / 变更通知 / 归档脚本引用；消费点接快速退役到 [[soulMemoryPath]]）。
   *
   * 保留本方法（而非调用方各自拼路径）的理由：[[nebflow.core.sandbox.SandboxPolicy]]
   * 的 `agents/<name>/memory.md` 负向规则与 [[legacyAgentMemoryPath]] 必须指向同一
   * 字符串，否则「旧文件保留只读」的读写判据会漂移。
   * （🔴 注释安全坑：本仓禁用 `agents/` + 双星号 + `/` 的字面写法——Scala 块注释
   * 会嵌套，那个序列会开一层永不闭合的注释，直接把文件编译废掉。）
   */
  def agentMemoryPath(agentName: String): os.Path = legacyAgentMemoryPath(agentName)

  def teamAgentMemoryPath(teamName: String, agentName: String): os.Path =
    PathUtil.dataRoot / "teams" / teamName / "agents" / agentName / "memory.md"

  // --- Mtime-cached file reads ---

  private def parseMemory(content: String): Option[String] =
    val trimmed = content.trim
    if trimmed.nonEmpty then Some(trimmed) else None

  /**
   * Data-root binding guard.
   *
   * The mtime caches below hold an **os.Path captured at construction**. In
   * production `PathUtil.dataRoot` never moves, so the binding is permanent and
   * this guard is a no-op. In tests every spec swaps `dataRoot` to its own temp
   * tree, and a cache bound to a previous spec's (deleted) tree would silently
   * answer from the wrong home — `invalidate*` clears the cached VALUE but not
   * the bound PATH, so it cannot repair that. Re-binding whenever the root
   * changes makes each accessor read the current root, which is the semantics
   * the callers already assume.
   */
  @volatile private var boundRoot: os.Path = PathUtil.dataRoot

  private var userCache = MtimeCache.file[Option[String]](userMemoryPath, parseMemory)

  private var soulCache = MtimeCache.file[Option[String]](soulMemoryPath, parseMemory)

  private val agentCaches = new ConcurrentHashMap[String, MtimeFileCache[Option[String]]]()

  private val teamAgentCaches = new ConcurrentHashMap[String, MtimeFileCache[Option[String]]]()

  /** Re-bind caches to the current `dataRoot` when it moved (test isolation). */
  private def rebindIfRootChanged(): Unit =
    val root = PathUtil.dataRoot
    if root != boundRoot then
      boundRoot = root
      userCache = MtimeCache.file(userMemoryPath, parseMemory)
      soulCache = MtimeCache.file(soulMemoryPath, parseMemory)
      agentCaches.clear()
      teamAgentCaches.clear()

  private def getAgentCache(agentName: String): MtimeFileCache[Option[String]] =
    rebindIfRootChanged()
    agentCaches.asScala.getOrElseUpdate(agentName, MtimeCache.file(agentMemoryPath(agentName), parseMemory))

  private def teamCacheKey(teamName: String, agentName: String): String = s"$teamName/$agentName"

  private def getTeamAgentCache(teamName: String, agentName: String): MtimeFileCache[Option[String]] =
    rebindIfRootChanged()
    val key = teamCacheKey(teamName, agentName)
    teamAgentCaches.asScala.getOrElseUpdate(key, MtimeCache.file(teamAgentMemoryPath(teamName, agentName), parseMemory))

  // --- Load (mtime-cached) — injected into system prompts ---

  def loadUserMemory: Option[String] =
    rebindIfRootChanged()
    userCache.get.unsafeRunSync().flatten

  /**
   * **Soul 记忆双读**（personal-agent 批）：`~/.nebflow/Soul.md` 优先，缺省回落
   * `~/.nebflow/agents/<agentName>/memory.md`（既有记忆不失联）。
   *
   * 语义判据（二值、可测）：`Soul.md` 存在且非空 ⇒ 取它；否则取旧位；两者皆空 ⇒ None。
   * 两条腿各自 mtime 缓存（未变更只付一次 stat），因此「回落」不引入每轮读盘。
   * 迁移完成后旧位仍在（保留只读备份）⇒ 本回落腿是**过渡**而非临时兜底：它保证
   * 「已迁移 + 未迁移」两种 home 都拿到同一份内容。
   *
   * `agentName` 由调用方传入（注入闸只放行根 agent ⇒ 实际恒为
   * `RootAgentIdentity.Name`）：**本层不硬编码该名**——`shared` 不依赖 `actor`（层序
   * shared ← actor ← agent ← core ← gateway），且根名是机制键单点，不得出现第二份字面。
   */
  def loadSoulMemory(agentName: String): Option[String] =
    rebindIfRootChanged()
    soulCache.get.unsafeRunSync().flatten.orElse(getAgentCache(agentName).get.unsafeRunSync().flatten)

  /**
   * 旧位读取（`agents/<name>/memory.md`）——**归档/诊断面**用（孤儿记忆清单、
   * 迁移前快照）。注入路径请用 [[loadSoulMemory]]（含回落语义）。
   */
  def loadAgentMemory(agentName: String): Option[String] =
    getAgentCache(agentName).get.unsafeRunSync().flatten

  def loadTeamAgentMemory(teamName: String, agentName: String): Option[String] =
    getTeamAgentCache(teamName, agentName).get.unsafeRunSync().flatten

  // --- Save (落盘单点：过闸 → 写 → 失效缓存) ---
  //
  // M4（2026-09-13 作者立项）：预算闸 + 写前快照闸**下沉到本单点**（[[MemoryWriteGate]]），
  // 故「过闸」是落盘的必要条件而不取决于调用方。今天唯一的生产调用方 = WS `saveMemory`
  // 旁路（`WebSocketRoutes:3265/3271`）。**不覆盖** Write / Edit / Bash 直写路径（边界与理由
  // 见 MemoryWriteGate 头注）——本单点只管经它落盘的写入。
  // 闸序（作者 2026-09-14 v2 裁定）= **预算 → 快照 → 落盘**：拒绝路径**零文件写**——不落
  // 目标文件、也不落备份面（`backups/`）；快照唯一触发点 = 预算放行、即将落盘。
  // 拒绝/失败走 IO 错误通道（`MemoryWriteGate.Rejected`）且必须由调用方暴露。

  private def saveFile(path: os.Path, target: String, content: String, invalidateCache: () => IO[Unit]): IO[Unit] =
    MemoryWriteGate.guard(target, path, content) *>
      IO.blocking(os.write.over(path, content, createFolders = true)) *> invalidateCache()

  def saveUserMemory(content: String): IO[Unit] =
    IO.delay(rebindIfRootChanged()) *>
      saveFile(userMemoryPath, "user", content, () => userCache.invalidate)

  /**
   * 落盘 `~/.nebflow/Soul.md`（personal-agent 批起 agent 记忆的**新位**）。
   * target 口径仍为 `"agent"`：预算常量与闸判据按「agent 级」定价（30KB/24KB），
   * 改名不改预算语义（方案 §2.1 裁定「沿用 agent / user 两级预算」）。
   */
  def saveSoulMemory(content: String): IO[Unit] =
    IO.delay(rebindIfRootChanged()) *>
      saveFile(soulMemoryPath, "agent", content, () => soulCache.invalidate)

  /** 旧位写入（`agents/<name>/memory.md`）——迁移腿与归档脚本用，注入路径已改新位。 */
  def saveAgentMemory(agentName: String, content: String): IO[Unit] =
    IO.delay(rebindIfRootChanged()) *>
      saveFile(agentMemoryPath(agentName), "agent", content, () => getAgentCache(agentName).invalidate)

  // --- Cache invalidation ---

  def invalidateUserCache(): Unit =
    rebindIfRootChanged()
    userCache.invalidate.unsafeRunSync()

  def invalidateSoulCache(): Unit =
    rebindIfRootChanged()
    soulCache.invalidate.unsafeRunSync()

  def invalidateAgentCache(agentName: String): Unit =
    rebindIfRootChanged()
    getAgentCache(agentName).invalidate.unsafeRunSync()

  // --- Preview (first non-heading, non-empty line, max 80 chars) ---

  def preview(path: os.Path): Option[String] =
    if !os.exists(path) then None
    else
      val content = os.read(path).trim
      if content.isEmpty then None
      else
        Some(
          content.linesIterator
            .dropWhile(l => l.trim.isEmpty || l.trim.startsWith("#"))
            .find(_.trim.nonEmpty)
            .map(_.trim.take(80))
            .getOrElse("(empty)")
        )

  def userPreview: Option[String] = preview(userMemoryPath)
  def agentPreview(agentName: String): Option[String] = preview(agentMemoryPath(agentName))

  /** Soul 预览（双读，与 [[loadSoulMemory]] 同判据）。 */
  def soulPreview(agentName: String): Option[String] =
    preview(soulMemoryPath).orElse(preview(agentMemoryPath(agentName)))

  def teamAgentPreview(teamName: String, agentName: String): Option[String] =
    preview(teamAgentMemoryPath(teamName, agentName))

  // --- Exists check ---

  def fileExists(path: os.Path): Boolean =
    os.exists(path) && os.stat(path).size > 0

  def userExists: Boolean = fileExists(userMemoryPath)
  def agentExists(agentName: String): Boolean = fileExists(agentMemoryPath(agentName))

  /** Soul 存在性（双读）。 */
  def soulExists(agentName: String): Boolean =
    fileExists(soulMemoryPath) || fileExists(agentMemoryPath(agentName))

  def teamAgentExists(teamName: String, agentName: String): Boolean =
    fileExists(teamAgentMemoryPath(teamName, agentName))

end MemoryStore

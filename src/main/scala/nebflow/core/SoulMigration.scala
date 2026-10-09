package nebflow.core

import nebflow.shared.{MemorySnapshot, MemoryStore, NebflowLogger, PathUtil}

/**
 * `Soul.md` 启动一次性迁移腿（personal-agent 批 2026-10-04，方案 §2.1 / 裁定①）。
 *
 * **搬什么**：`~/.nebflow/agents/Nebula/memory.md` → `~/.nebflow/Soul.md`。
 * agent 级记忆上收到根层单文件，与 `User.md` 同级（双 demo 口径 + 作者裁定①）。
 *
 * **判据（幂等，二值可测）**：
 *   - `Soul.md` 不存在 ∧ 旧文件非空 ⇒ **迁移**；
 *   - `Soul.md` 已存在（含空文件）⇒ **跳过**（永不覆盖新位——用户的 Soul 优先于任何自动动作）；
 *   - 旧文件不存在或为空 ⇒ **跳过**。
 * 已迁移则再跑零副作用（不重写 `Soul.md`、不动旧文件、不落新快照）。
 *
 * **安全序**（方案 §2.1「老数据不丢」）：写前经 [[MemorySnapshot]] 落一张快照 ⇒
 * 任何一次迁移都可回滚。快照失败 ⇒ 整体中止（零文件写，fail-closed；与
 * [[nebflow.shared.MemoryWriteGate]] 的闸序同款纪律）。
 *
 * **原文件保留为只读备份（不删）** —— 回滚路径 = 把 `Soul.md` 内容写回
 * `agents/Nebula/memory.md`（或反向：删掉 `Soul.md`，双读回落立即接上旧文件）。
 * 旧文件同时受「agents 子树内 memory.md 写拒」沙箱负向规则与外层归档动作管辖。
 *
 * **不写真实 home**：本腿只由 gateway boot 调用（`GatewayMain`）；测试与端到端验证
 * 在隔离 `HOME`（`setDataRoot` / `--home`）内跑。调用方负责在 boot 处 try/catch——
 * 迁移失败不得阻断启动（与 `PresetStore.migrateGlobalModelChain` 同款纪律）。
 *
 * 形态参照 [[ModelChainMigration]]（启动一次性、幂等、失败不阻断）。
 */
object SoulMigration:

  private val logger = NebflowLogger.forName("nebflow.soul.migration")

  /**
   * 迁移源 agent 名。**这是旧数据位面**（历史 home 的目录名），不是身份判据键：
   * 身份键单点 = `RootAgentIdentity.Name`（`actor` 层）；`shared` 不依赖 `actor`
   * 层序，故此处由本腿（`core` 层，可依赖 `actor`）显式引用单点，不写第二份字面。
   */
  private def rootAgentName: String = nebflow.actor.RootAgentIdentity.Name

  /** 迁移结果（供 boot 日志与 spec 断言；`Migrated` 携带字节数便于读数）。 */
  enum Outcome:
    /** 已完成迁移；bytes = 写入 Soul.md 的字节数。 */
    case Migrated(bytes: Long)

    /** Soul.md 已存在 ⇒ 跳过（幂等；含用户手写的情况）。 */
    case SkippedSoulExists

    /** 旧文件缺失或为空 ⇒ 无可迁移内容。 */
    case SkippedNoSource

    /** 快照失败 ⇒ 整体中止，零文件写。 */
    case Aborted(reason: String)

  /**
   * 执行迁移腿。**幂等**：二次调用返回 [[Outcome.SkippedSoulExists]]（首次成功迁移后）
   * 或 [[Outcome.SkippedNoSource]]（本就无源），且不产生任何文件写。
   */
  def run(): Outcome =
    val soul = MemoryStore.soulMemoryPath
    val legacy = MemoryStore.agentMemoryPath(rootAgentName)
    if os.exists(soul) then Outcome.SkippedSoulExists
    else if !os.exists(legacy) then Outcome.SkippedNoSource
    else
      val content = os.read(legacy)
      if content.trim.isEmpty then Outcome.SkippedNoSource
      else
        // 写前快照（fail-closed）：源文件进 `memory-backups/<ts>/`，失败即中止。
        MemorySnapshot.snapshotBeforeWrite(legacy) match
          case Left(reason) => Outcome.Aborted(reason)
          case Right(_) =>
            os.write.over(soul, content, createFolders = true)
            val bytes = content.getBytes(java.nio.charset.StandardCharsets.UTF_8).length.toLong
            MemoryStore.invalidateSoulCache()
            Outcome.Migrated(bytes)

  /**
   * Boot 入口：执行并落一行 WARN/INFO 读数（**不抛**——迁移失败不得阻断启动）。
   * `GatewayMain` 在既有迁移块内调用本方法。
   */
  def runAtBoot(): Unit =
    try
      run() match
        case Outcome.Migrated(bytes) =>
          logger.infoSync(s"[soul-migration] migrated $bytes bytes to ${MemoryStore.soulMemoryPath} (legacy kept read-only)")
        case Outcome.SkippedSoulExists => () // 幂等静默：健康 home 每 boot 走这条
        case Outcome.SkippedNoSource   => () // 全新安装无旧数据：静默
        case Outcome.Aborted(reason) =>
          logger.warnSync(s"[soul-migration] aborted (snapshot failed), zero writes: $reason")
    catch case e: Exception => logger.warnSync(s"[soul-migration] failed: ${e.getMessage}")

  // ---------------------------------------------------------------
  // 孤儿 agent 记忆归档（方案 §2.1 裁定：显式步骤、不删）
  // ---------------------------------------------------------------

  /** 归档根：`~/.nebflow/memory-archive/agents-<ts>/`（不删，只搬）。 */
  def archiveRoot(stamp: String): os.Path =
    PathUtil.dataRoot / "memory-archive" / s"agents-$stamp"

  /**
   * 列出 `~/.nebflow/agents/` 下的**孤儿 agent 记忆**（`<name>/memory.md`，排除根 agent）。
   *
   * 孤儿 = 注入闸（`shouldInjectMemory`）从不放行的 agent 级记忆文件：注入面只认根
   * agent ⇒ 其余目录的 memory.md 是历史数据（相互独立、不被任何会话读到）。
   * 本方法只**列**（零写），归档动作由 [[archiveOrphans]] 显式执行。
   */
  def listOrphans(): List[(String, os.Path, Long)] =
    val agentsDir = PathUtil.dataRoot / "agents"
    if !os.isDir(agentsDir) then Nil
    else
      os.list(agentsDir)
        .filter(os.isDir)
        .filter(_.last != rootAgentName)
        .flatMap { dir =>
          val mem = dir / "memory.md"
          if os.isFile(mem) then Some((dir.last, mem, os.size(mem).toLong)) else None
        }
        .toList

  /**
   * 孤儿记忆归档（**迁移不删**）：把 `<agents>/<name>/memory.md` 复制到
   * [[archiveRoot]] 下的同名折叠路径，**原文件保留原位**（只读备份语义 = 不搬走、
   * 不删除 ⇒ 任何按原路径的诊断仍然可读）。
   *
   * 返回 `(已归档条数, 归档根)`。幂等：重复执行覆盖同一批副本（同名同内容）。
   * 调用面 = 显式运维动作（脚本 / 人工），**不在 boot 自动跑**——孤儿归档是数据处置，
   * 不是启动健康所必需（与 Soul 迁移腿的分工）。
   */
  def archiveOrphans(stamp: String): (Int, os.Path) =
    val root = archiveRoot(stamp)
    val orphans = listOrphans()
    orphans.foreach { (name, mem, _) =>
      os.write.over(root / name / "memory.md", os.read.bytes(mem), createFolders = true)
    }
    (orphans.size, root)

end SoulMigration

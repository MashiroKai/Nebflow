package nebflow.core.project

import cats.effect.IO
import nebflow.core.PathUtil
import nebflow.service.MemoryBudget

/**
 * ProjectMemory —— 项目级记忆文件（project-memory 批 2026-09-05）单点。
 *
 * 文件落位：`<workspace>/.nebflow/memory.md`（每项目 workspace 一份）。命名
 * 定稿依据：与全局 agent 级记忆文件同名（memory.md）+ 靠 `.nebflow/` 目录位
 * 区分层级——全局两级在 `~/.nebflow/`（User.md / agents/Nebula/memory.md），
 * 项目级在项目 workspace 的 `.nebflow/` 内；同名使「memory.md=记忆文件」的
 * 工具/文档/前端心智一致，异目录使三层互不歧义。
 *
 * git 处置（显式决定）：**运行时层不进 git**——项目 workspace 根 .gitignore
 * 由 ProjectStore.create 写入 `.nebflow/`（R6），memory.md 天然被排除；各项目
 * repo **不加** memory.md 的 git 豁免行。理由：运行时状态免评审噪音，git
 * 留史由快照机制承担（与主仓「.nebflow 全忽略、仅 Spec 子目录进 track」配方
 * 同向：Spec 层进 git、运行时层不进）。
 *
 * 快照机制划界：本批**不改** SandboxPolicy/FileSandbox——全局记忆
 * snapshot-on-write 由 dream 批实现（其对象面已含项目记忆的路径模式扩展），
 * 项目记忆快照随 dream 批机制落地自动覆盖；在 dream 批落地前，项目记忆无
 * git/快照留史（E2E 等写操作自行备份对照）。
 *
 * 注入语义（本批核心）：
 *   - **默认注入只含全局记忆**——ContextRefresher 注入路径零改动（瘦身），
 *     项目记忆绝不进入 Nebula 常驻系统提示词；
 *   - 派发项目任务 → 该项目分发器 prompt（ProjectActor newTaskPrompt/
 *     reentryPrompt）+ 该项目节点首条消息（NodeEngine.buildInput）自动注入
 *     本文件内容；
 *   - 注入形态三态（共用 MemoryBudget project 判据）：预算内全文（KB 级
 *     可承受）；软警区（>80%）全文+WARN 脚注；超硬顶只注头部+字节/条目
 *     统计+整理指引（不注正文——超限文件的内容质量已不可信，全文注入是税）。
 *
 * Nebula 本人在场处理项目事务时**不自动注入**（全局上下文珍贵），按需
 * `Read <workspace>/.nebflow/memory.md` 单文件即可；写入口 = MemoryEdit
 * `target=project:<name>`（预算闸与全局同纪律、常量独立）。
 */
object ProjectMemory:

  /** 文件名（定稿依据见类 doc）。 */
  val FileName: String = "memory.md"

  /** 项目记忆文件绝对路径：`<workspace>/.nebflow/memory.md`。workspace 相对
    * 形式以 dataRoot 为基准解析（与 ProjectStore.create 的 os.Path(workspace,
    * PathUtil.dataRoot) 同规）。 */
  def path(workspace: String): os.Path =
    os.Path(workspace, PathUtil.dataRoot) / ".nebflow" / FileName

  /** 读（read-fresh，无缓存——注入点频度为每次分发器 spawn/每节点启动一次，
    * 单个小文件读可承受；省一层 mtime 缓存失效面）。文件缺失/空 → None。 */
  def load(p: os.Path): Option[String] =
    if !os.isFile(p) then None
    else
      val c = os.read(p).trim
      if c.isEmpty then None else Some(c)

  /** 写（MemoryEdit project 目标的落盘面）。createFolders 兜底存量 workspace
    * 无 `.nebflow/` 的边缘态（ProjectStore.create 恒建目录，此处纵深防御）。 */
  def save(p: os.Path, content: String): IO[Unit] =
    IO.blocking(os.write.over(p, content, createFolders = true))

  /** 注入块渲染（ProjectActor 分发器 prompt 与 NodeEngine 节点首条消息共用
    * 单点——两处格式/三态行为由本函数唯一决定）。
    *
    * 返回 ""：文件缺失/空（调用方不注空段）。三态见类 doc。 */
  def injectionBlock(workspace: String, projectName: String): IO[String] =
    IO.blocking {
      val p = path(workspace)
      load(p) match
        case None => ""
        case Some(content) =>
          val bytes = content.getBytes(java.nio.charset.StandardCharsets.UTF_8).length.toLong
          MemoryBudget.verdict("project", bytes) match
            case MemoryBudget.Within =>
              s"""# Project Memory — $projectName（<$p>；项目状态/口径/教训，MemoryEdit target=project:$projectName 维护）
                 |
                 |$content""".stripMargin
            case MemoryBudget.Warn(_, _, _) =>
              s"""# Project Memory — $projectName（<$p>；项目状态/口径/教训，MemoryEdit target=project:$projectName 维护）
                 |
                 |$content
                 |
                 |（WARN: over the 80% soft line of ${MemoryBudget.ProjectSoftBytes} bytes — schedule a consolidation pass, MEMORYEDIT_BUDGET_WARN）"""
            case MemoryBudget.Exceeded(_, hard) =>
              val entries = content.linesIterator.count(_.trim.startsWith("- "))
              s"""# Project Memory — $projectName（<$p>）
                 |
                 |（file OVER the ${hard}-byte hard budget: $bytes bytes, $entries entries — full text NOT inlined this session. Largest sections:
                 |${MemoryBudget.topSections(content)}
                 |Consolidate first via MemoryEdit target=project:$projectName remove/replace_section.）"""
  }
end ProjectMemory

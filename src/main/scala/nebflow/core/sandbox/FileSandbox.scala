package nebflow.core.sandbox

import java.nio.file.Path

import nebflow.core.tools.{ToolContext, ToolError, ToolPathUtil}

/**
 * 文件工具层路径接缝（阶段 2a 沙箱 → 沙箱拆围栏批 S2 退役形态）。
 *
 * **[沙箱拆围栏批 S2，2026-09-10] 本对象已不再做围栏判定**：写根（`writableRoots`
 * contain）与读拒（`readDenied`）判定全部退役——六个调用点（`WriteTool` /
 * `EditTool` / `MultiEditTool` / `ReadTool` / `GlobTool` / `GrepTool`）不再因文件闸
 * 返回 `SANDBOX_DENIED`，宿主文件面对 JVM 文件工具全部放开。
 *
 * 保留的**非围栏职能**（R5=e2「拆闸保解析」，判据一律取 `policy.pathRoot` 会话根
 * 信号而非 `policy.enabled` 围栏总闸）：
 *  - **路径解析**：相对路径以会话根为基准 + `~` 展开（逻辑在
 *    [[nebflow.core.tools.ToolPathUtil]]）——拆掉它会回归「Glob/Grep 默认根 =
 *    JVM user.dir」旧缺陷（本文件头注释旧文自陈这是沙箱修掉的问题）；
 *  - **canonical 返回值**：rg/os 从 canonical 路径起跑，检查对象与执行对象一致；
 *  - **`agents/<agent>/memory.md` 写拒**（R3=c1 写侧例外）：一条与写根无关的
 *    **数据完整性**不变量（防 agent A 覆写 agent B 的私有记忆／防经 symlink 间接
 *    写入），不是围栏。读侧已放开（R3=c1 读拆写留）——读侧今日已被 Bash `cat`
 *    绕穿，保留只制造「有保护」的错觉（design §1 F3/F9）。
 *
 * **回退点不删**（design §4.5）：`SandboxPolicy.off` / `forRoot` 的
 * `if !cfg.enabled then off` 保留——回旧行为 = 一个配置项。无会话根（`pathRoot`
 * 为空，即 off / 非项目会话）时本对象按旧行为逐字节处置：仅接受绝对路径、原样返回。
 *
 * **留待清理批（②/③）的点名项**：`deniedMessage` 仍带退役前的「Writable roots /
 * Readable roots / outside sandbox root」文案（当前唯一消费者 = memory.md 写拒，
 * 文案改写属测试与文案清理批）；`SandboxPolicy.memoryGlobExcludes` /
 * `readDeniedWith` 已无生产消费点（定义保留，防扩大改动面）。
 *
 * 本围栏（历史上）是 containment 不是内核安全边界（§A.1 如实声明）；进程面围栏
 * （Bash 子进程树）由 `SandboxBackend.Seatbelt` 承担，其宿主路径的拆除属 S3。
 */
object FileSandbox:

  /**
   * 写路径解析（Write/Edit/MultiEdit）。返回 canonical 路径——调用方必须用返回值
   * 执行写操作，不得回用原始输入路径。
   *
   * S2 起不再做写根 contain 判定；保留 `agents/<agent>/memory.md` 写拒（R3=c1）。
   * 无会话根 = 回退点旧行为（仅绝对路径，不做 `~` 展开 / canonicalize / 记忆校验）。
   */
  def checkWrite(ctx: ToolContext, rawPath: String): Either[ToolError, Path] =
    val policy = ctx.sandbox
    ToolPathUtil.resolveAgainstToolRoot(policy, rawPath) match
      case Left(err) => Left(err)
      case Right(resolved) =>
        if policy.pathRoot.isEmpty then Right(resolved)
        else
          // 与写根无关的独立例外（数据完整性规则）：agents 子树内非 Nebula 份
          // memory.md 写拒——同一条规则原读/写双闸消费，读侧随 S2 退役，写侧保留
          // （例外集 = §4.2-B 审计只读白名单，Nebula 自身 memory.md 精确豁免）。
          // canonical 域比较（symlink 间接路径同样拦截）。
          val canonical = SandboxPolicy.canonicalize(resolved)
          if SandboxPolicy.readDenied(canonical) then
            Left(ToolError(deniedMessage(
              "write", rawPath, canonical, policy,
              reason = Some("path matches the private-memory rule (agents/<agent>/memory.md is denied; the only exception is the audit-read-only whitelist for Nebula's own memory.md)")
            )))
          else Right(canonical)

  /**
   * 读路径解析（Read）。[R3=c1 读侧放开] 读拒判定（`readDenied`）与 `readableRoots`
   * contain 均已退役——读面全盘（2026-09-06 读宽批）的语义后果就是读侧无闸可拆，
   * 保留负向规则只制造「有保护」错觉。保留解析 + canonical 返回值。
   * 无会话根 = 回退点旧行为（仅绝对路径）。
   */
  def checkRead(ctx: ToolContext, rawPath: String): Either[ToolError, Path] =
    val policy = ctx.sandbox
    ToolPathUtil.resolveAgainstToolRoot(policy, rawPath) match
      case Left(err) => Left(err)
      case Right(resolved) =>
        if policy.pathRoot.isEmpty then Right(resolved)
        else Right(SandboxPolicy.canonicalize(resolved))

  /**
   * 读路径解析（Glob/Grep 搜索根）：输入已是 os.Path（工具内 os-lib 解析产物），
   * canonicalize 后以 canonical 形态返回——rg 从 canonical 根起跑，检查对象与
   * 执行对象一致。[R3=c1 读侧放开] 同时承载的 `readDenied` 判定已退役。
   * 无会话根 = 回退点旧行为（原样返回）。
   */
  def checkReadRoot(ctx: ToolContext, root: os.Path): Either[ToolError, os.Path] =
    val policy = ctx.sandbox
    if policy.pathRoot.isEmpty then Right(root)
    else Right(os.Path(SandboxPolicy.canonicalize(root.wrapped)))

  /** 私有记忆写拒的结构化错误（§A.5 模板）。[沙箱拆围栏批 S2] 当前唯一消费者 =
    * checkWrite 的 memory.md 写拒——写根/读根列表已是退役概念的残留文案，改写属
   * 清理批（②/③），本批按最小改动不扩面。 */
  private def deniedMessage(op: String, raw: String, canonical: Path, policy: SandboxPolicy, reason: Option[String] = None): String =
    val writable = SandboxPolicy.writableRoots(policy).mkString(", ")
    val readable = SandboxPolicy.readableRoots(policy).mkString(", ")
    val reasonLine = reason.map(r => s"\nReason: $r").getOrElse("")
    s"""SANDBOX_DENIED
       |[sandbox: file access denied under workspace-write mode]
       |cannot $op "$raw" (resolves to $canonical, outside sandbox root ${policy.root}).$reasonLine
       |Writable roots: $writable. Readable roots: $readable.
       |Write within the sandbox root, or report to the dispatcher if the task genuinely requires a path outside the project.""".stripMargin

end FileSandbox

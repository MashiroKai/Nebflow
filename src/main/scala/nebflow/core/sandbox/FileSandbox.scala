package nebflow.core.sandbox

import io.circe.JsonObject

import java.nio.file.{Files, Path, Paths}

import nebflow.core.tools.ToolContext
import nebflow.core.tools.ToolError

/**
 * 阶段 2a 沙箱（设计文档 §A.3）：文件工具层 JVM 进程内围栏。
 *
 * 五族文件工具（Read/Write/Edit/MultiEdit + Glob/Grep 的搜索根）全部过闸：
 * - 写操作：resolve → writableRoots contain → 此刻 re-resolve（fresh）→ 返回
 *   fresh 路径供工具执行（dsh checkedTarget 模式，消灭 check-then-use TOCTOU）。
 *   readDenied 负向规则先于 contain 一票优先（agents/**/memory.md 非 Nebula 份
 *   拒写）——2026-09-05 数据根入可写面后红线不随写面扩大（同一规则读/写双闸
 *   消费，非新增 deny）。
 * - 读操作：resolve → readableRoots contain → 返回 canonical 路径。
 *   [2026-09-06 读宽批] readableRoots 恒为全盘根（"/"）——contain 对一切绝对
 *   路径恒真，读拒绝的唯一来源 = readDenied 负向规则（agents/**/memory.md 非
 *   Nebula 份一票优先）；写闸零变化。
 * - 相对路径：以 ctx.sandbox.root（节点 projectRoot，§A.6）为基准解析——修掉
 *   Glob/Grep 默认根=JVM user.dir 的现状。沙箱关闭时保持旧行为（四件套拒绝
 *   相对路径、Glob/Grep 用 user.dir）。
 * - contain：NIO startsWith 逐段比较，天然带分隔符边界（SandboxPolicy.contains）。
 *
 * 本围栏是 containment 不是内核安全边界（§A.1 如实声明）；不可信代码隔离由
 * Bash 层 Seatbelt 承担（SandboxBackend）。
 */
object FileSandbox:

  /**
   * 写闸门（Write/Edit/MultiEdit）。返回 fresh canonical 路径——调用方必须用
   * 返回值执行写操作，不得回用原始输入路径。
   */
  def checkWrite(ctx: ToolContext, rawPath: String): Either[ToolError, Path] =
    val policy = ctx.sandbox
    if !policy.enabled then
      // 旧行为（sandbox.enabled=false / 非 project 会话）：仅要求绝对路径。
      if !nebflow.core.PathUtil.isAbsolute(rawPath) then
        Left(ToolError(s"Path must be absolute, got: $rawPath"))
      else Right(Paths.get(rawPath))
    else
      val resolved = resolveAgainstPolicy(policy, rawPath)
      val canonical = SandboxPolicy.canonicalize(resolved)
      val writable = SandboxPolicy.writableRoots(policy).map(_.wrapped)
      // 第二次解析此刻 fresh 执行——第一次检查到这里的窗口内 symlink 可被替换
      // （§A.3 dsh checkedTarget）。
      val fresh = SandboxPolicy.canonicalize(resolved)
      // 凭据红线负向规则对写闸同等一票优先（2026-09-05 数据根入写面批）：数据根
      // 整目录可写后，agents/**/memory.md 非 Nebula 份不得因落在可写数据根内而
      // 变可写——同一既有规则的读/写双闸消费（红线延续，非新增 deny）；例外集与
      // 读闸同源 = §4.2-B 审计只读白名单（Nebula memory.md 精确路径，其写放行
      // 由数据根写面承载，属批次报告钉死的残留风险）。canonical+fresh 双查与
      // contain 检查同构（root 内 symlink 指向私有记忆的间接路径同样拦截）。
      if SandboxPolicy.readDenied(canonical) || SandboxPolicy.readDenied(fresh) then
        Left(ToolError(deniedMessage(
          "write", rawPath, fresh, policy,
          reason = Some("path matches the private-memory deny rule (agents/**/memory.md is denied; the only exception is the audit-read-only whitelist for Nebula's own memory.md)")
        )))
      else
        (writable.find(SandboxPolicy.contains(_, canonical)), writable.find(SandboxPolicy.contains(_, fresh))) match
          case (Some(_), Some(_)) => Right(fresh)
          case _ => Left(ToolError(deniedMessage("write", rawPath, fresh, policy)))

  /**
   * 读闸门（Read）。同样返回 canonical 路径。[2026-09-06 读宽批] readableRoots
   * 全盘化后读放行面 = 整盘：root 内 symlink 指外不再因越界被拒（canonicalize
   * 仍解析去向，readDenied 负向规则照常一票优先）；写闸越界语义零变化。
   */
  def checkRead(ctx: ToolContext, rawPath: String): Either[ToolError, Path] =
    val policy = ctx.sandbox
    if !policy.enabled then
      if !nebflow.core.PathUtil.isAbsolute(rawPath) then
        Left(ToolError(s"Path must be absolute, got: $rawPath"))
      else Right(Paths.get(rawPath))
    else
      val canonical = SandboxPolicy.canonicalize(resolveAgainstPolicy(policy, rawPath))
      checkCanonicalRead(policy, rawPath, canonical)

  /**
   * 读闸门（Glob/Grep 搜索根）：输入已是 os.Path（工具内 os-lib 解析产物），
   * canonicalize + readableRoots contain 后以 canonical 形态返回——rg 从
   * canonical 根起跑，检查对象与执行对象一致。
   */
  def checkReadRoot(ctx: ToolContext, root: os.Path): Either[ToolError, os.Path] =
    val policy = ctx.sandbox
    if !policy.enabled then Right(root)
    else
      val canonical = SandboxPolicy.canonicalize(root.wrapped)
      checkCanonicalRead(policy, root.toString, canonical).map(os.Path(_))

  private def checkCanonicalRead(policy: SandboxPolicy, raw: String, canonical: Path): Either[ToolError, Path] =
    // 凭据红线负向规则一票优先：命中即拒，不看 readableRoots。[2026-09-06 读宽
    // 批] readableRoots 全盘化后本规则是读拒绝的唯一来源——agents/**/memory.md
    // （agent 私有记忆）在全盘读面下仍拒；唯一例外 = §4.2-B 审计只读放行的
    // Nebula memory.md 精确路径（readDenied 内部豁免）。
    if SandboxPolicy.readDenied(canonical) then
      Left(
        ToolError(deniedMessage(
          "read", raw, canonical, policy,
          reason = Some("path matches the private-memory deny rule (agents/**/memory.md is denied; the only exception is the audit-read-only whitelist for Nebula's own memory.md)")
        ))
      )
    else
      val readable = SandboxPolicy.readableRoots(policy).map(_.wrapped)
      if readable.exists(SandboxPolicy.contains(_, canonical)) then Right(canonical)
      else Left(ToolError(deniedMessage("read", raw, canonical, policy)))

  /**
   * 入参解析：绝对路径原样；相对路径以沙箱根为基准（NIO resolve 保留 `..` 段，
   * 不做词法折叠——canonicalize 再按内核语义解析）；`~` 展开（skill scripts
   * 路径惯例；展开后落入 ~/.nebflow 白名单外仍会被拒，白名单语义不受影响）。
   */
  private def resolveAgainstPolicy(policy: SandboxPolicy, raw: String): Path =
    val expanded = nebflow.core.PathUtil.expandTilde(raw)
    val p = Paths.get(expanded)
    if p.isAbsolute then p
    else policy.root.wrapped.resolve(p)

  /** SANDBOX_DENIED 结构化错误（§A.5 模板）：canonical 路径 + 根列表 + 自纠指引。
    * 根列表从 readableRoots/writableRoots 动态推导（无硬编码）。[2026-09-06 读宽
    * 批] Readable roots 段恒为全盘根 "/"；本错误在写闸 = 越界语义、在读闸 =
    * 负向规则命中（reason 解释行）。 */
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

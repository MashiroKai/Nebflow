package nebflow.core.tools

import java.nio.file.{Path, Paths}

import nebflow.core.PathUtil
import nebflow.core.sandbox.SandboxPolicy

/**
 * 文件工具的路径语义 helper（沙箱拆围栏批 S2 / R5=e2「拆闸保解析」）。
 *
 * 背景：阶段 2a 把「闸门」（写根/读根 contain 判定）与「路径解析」混装在
 * `FileSandbox` 内，两者共享同一个 `policy.root`。拆围栏批只拆闸门，解析能力必须
 * 留下——它修掉的是「Grep/Glob 缺省根 = JVM `user.dir`」这一旧缺陷（`FileSandbox`
 * 头注释自陈），拆掉会静默回归到「节点搜索默认落在引擎进程 cwd」。
 *
 * 本对象是解析能力的独立落点：
 *  - [[searchBaseDir]]：Grep/Glob 缺省搜索根 = 会话根（无会话根才回落 `user.dir`）；
 *  - [[resolveAgainstToolRoot]]：相对路径以会话根为基准 + `~` 展开
 *    （`PathUtil.expandTilde` 等价能力）。
 *
 * 判据一律取 [[SandboxPolicy.pathRoot]]（会话根信号），**不取** `policy.enabled`
 * （围栏总闸）：闸门退役/回退开关拨动都不得改变路径语义（design §4.4 S2 验收：
 * Glob/Grep 默认根不得回归 `user.dir`）。
 *
 * 无会话根（`pathRoot.isEmpty`）时保持旧行为逐字节不变：相对路径一律拒（含 `~/x`
 * 形态——闸门开启前的四件套语义），`~` 也不展开。
 */
object ToolPathUtil:

  /**
   * 缺省搜索根（Grep/Glob 共用）：有会话根 → 会话根；无会话根 → JVM `user.dir`
   * （旧行为）。`userDir` 由调用方显式传入（生产 = `System.getProperty("user.dir")`）
   * ——便于定向单测断言两条分支。
   */
  def searchBaseDir(sandbox: SandboxPolicy, userDir: String): os.Path =
    sandbox.pathRoot.getOrElse(os.Path(userDir))

  /**
   * 相对路径基准解析（原 `FileSandbox.resolveAgainstPolicy`，语义逐字保留）：
   * `~` 展开 → 绝对路径原样；相对路径以会话根为基准（NIO `resolve` 保留 `..` 段，
   * 不做词法折叠——canonicalize 再按内核语义解析）。
   *
   * 无会话根 = 旧行为：仅接受绝对路径，原样返回（不做 `~` 展开 / canonicalize）。
   */
  def resolveAgainstToolRoot(sandbox: SandboxPolicy, raw: String): Either[ToolError, Path] =
    sandbox.pathRoot match
      case Some(root) => Right(resolveAgainst(root, raw))
      case None =>
        if !PathUtil.isAbsolute(raw) then Left(ToolError(s"Path must be absolute, got: $raw"))
        else Right(Paths.get(raw))

  /** 以显式会话根解析相对路径（helper 的纯函数形态：root 由调用方给出）。 */
  def resolveAgainst(root: os.Path, raw: String): Path =
    val expanded = PathUtil.expandTilde(raw)
    val p = Paths.get(expanded)
    if p.isAbsolute then p else root.wrapped.resolve(p)

end ToolPathUtil

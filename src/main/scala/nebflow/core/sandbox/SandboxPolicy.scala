package nebflow.core.sandbox

import io.circe.Json
import io.circe.Decoder

import java.nio.file.{Files, LinkOption, Path, Paths}

import scala.jdk.CollectionConverters.*

import nebflow.core.PathUtil

/**
 * 阶段 2a 沙箱（设计文档 §A.2）：单一策略源。
 *
 * root = 节点 projectRoot（worktree 或 workspace，NodeEngine.scala 已算出；分发器
 * = project workspace，H-5①），会话级不可变、构造时即 canonicalize（worktree 布局
 * 可能含 symlink → 采用 canonicalize 代替拒绝，§A.6）。
 *
 * writableRoots()/readableRoots() 是全部根集合的唯一推导点——JVM 围栏
 * （FileSandbox）与 Seatbelt profile（SandboxBackend.Seatbelt）都从这里取根，
 * 永不漂移（§A.1 dsh 教训）。写 ⊆ 读是不变量：任何可写根必须可读。
 *
 * readExtras（系统只读面，§A.2/H-10①/H-12①）：/usr /System /opt/homebrew
 * /private/etc /private/var + ~/.nebflow/{skills,prompts,docs} 三个子目录。
 * ~/.nebflow 根层（auth.json/device.json/logto-*.env 等凭据）不在任何白名单——
 * 读写均拒；~/.nebflow 整体不在可写根（裁定 3：agent 只在 project 内写）。
 *
 * enabled=false（nebflow.json sandbox.enabled=false 或非 project 会话）即回旧行为
 * （§G.1 回滚语义）：所有闸门短路过行，工具表现与沙箱引入前完全一致。
 */
case class SandboxPolicy(
  /** canonical 后的沙箱根。 */
  root: os.Path,
  /** 只读扩展面（系统工具链 + ~/.nebflow 白名单子目录）。 */
  readExtras: List[os.Path] = Nil,
  /** additionalRoots（H-5 预留③）：跨仓显式可写根，默认空=关。本批只留配置解析
    * 位，不做 UI/NodeEdit 参数。语义：追加进 writableRoots 与 readableRoots。 */
  extraWritable: List[os.Path] = Nil,
  /** 总开关（feature flag §G.1）：false = 旧行为，全部闸门旁路。 */
  enabled: Boolean = true,
  /** sandbox.bash.failIfUnavailable（§A.4-4）：probe 失败时 Bash fail-closed
    * （默认 true=报 SANDBOX_UNAVAILABLE 不执行）；false = 显式降级 WARN +
    * [unsandboxed] 前缀。 */
  bashFailIfUnavailable: Boolean = true
)

object SandboxPolicy:

  /** 关闭态策略：闸门全部旁路。root 仅为占位（不可达——所有闸门先查 enabled）。 */
  val off: SandboxPolicy = SandboxPolicy(os.Path("/"), Nil, Nil, enabled = false)

  /** 系统只读面（§A.2）：够编译器/工具链/系统命令使用。 */
  def systemReadExtras: List[os.Path] =
    List("/usr", "/System", "/opt/homebrew", "/private/etc", "/private/var").map(os.Path(_))

  /** ~/.nebflow 读取白名单（H-12①）：仅 skills/prompts/docs 三子目录。取
    * PathUtil.dataRoot（rebrand/测试 setDataRoot 均生效）。 */
  def nebflowReadExtras: List[os.Path] =
    List("skills", "prompts", "docs").map(s => PathUtil.dataRoot / s)

  def defaultReadExtras: List[os.Path] = systemReadExtras ++ nebflowReadExtras

  /**
   * 从节点 projectRoot + 配置构造会话策略（AgentCore 每次 spawn 调一次）。
   * root/readExtras/extraWritable 全部在此 canonicalize——后续推导可重入。
   *
   * [verify-fix] 2026-09-03 独立验证节点（隔离实例 E2E 实证）：cfg.enabled=false
   * 必须短路返回 off——原实现硬编码 enabled=true，全局 flag 只停了 Bash probe
   * （SandboxRuntime.init），文件五工具闸门照常激活，§G.1「enabled=false 一键回
   * 旧行为」回滚语义失效。回归断言见 SandboxSpec「G.1 回滚」用例。
   */
  def forRoot(root: os.Path, cfg: SandboxConfig): SandboxPolicy =
    if !cfg.enabled then off
    else
      SandboxPolicy(
        root = os.Path(canonicalize(root.wrapped)),
        readExtras = (defaultReadExtras ++ cfg.additionalRootsAsRead).map(p => os.Path(canonicalize(p.wrapped))).distinct,
        extraWritable = cfg.additionalRootsAsWrite.map(p => os.Path(canonicalize(p.wrapped))),
        enabled = true,
        bashFailIfUnavailable = cfg.bashFailIfUnavailable
      )

  /** 临时根：/private/tmp、/tmp（符号链接形态，Seatbelt 需两种拼写）、java.io.tmpdir。 */
  private def tempRoots: List[os.Path] =
    List(os.Path("/private/tmp"), os.Path("/tmp"), os.Path(Paths.get(sys.props.getOrElse("java.io.tmpdir", "/tmp"))))

  /**
   * 可写根（唯一推导）。macOS 上 /tmp→/private/tmp 归一后通常剩 root +
   * /private/tmp + java.io.tmpdir（/private/var/folders/...）去重后的集合。
   */
  def writableRoots(p: SandboxPolicy): List[os.Path] =
    (p.root :: p.extraWritable ::: tempRoots).map(x => os.Path(canonicalize(x.wrapped))).distinct

  /** 可读根（唯一推导）。写 ⊆ 读：extraWritable 与 tempRoots 同时进入读面。 */
  def readableRoots(p: SandboxPolicy): List[os.Path] =
    (p.root :: p.extraWritable ::: p.readExtras ::: tempRoots)
      .map(x => os.Path(canonicalize(x.wrapped)))
      .distinct

  /**
   * canonicalize：NIO toRealPath 等价语义（§A.2）。
   *
   * - 已存在部分（跟随目录项，悬空 symlink 也算存在）realpath——正确解析符号
   *   链接与 `..`（内核语义），绝不自行折叠 `..`。
   * - 新文件：向上找最深存在祖先，realpath 后再拼回剩余段。
   * - 拼回段中的 `..`/`.`：只对我们自己拼的、已证明不存在的段做折叠——这是
     * contain 检查安全的必要条件（/w/x/../.. 词法上必须折出界才判得出拒绝），
     * 与「禁止自己折叠 ..」的禁令（禁的是对已存在部分做词法 realpath）不冲突。
   * - 悬空 symlink 作为最深存在项：toRealPath 失败 → 按此刻词法形态判定
     * （§A.3「新文件祖先为 symlink → 按此刻解析结果判定」），真正的执行期
     * 兜底是写闸门的 fresh re-resolve + Bash 层 Seatbelt。
   */
  def canonicalize(path: Path): Path =
    var base = path.toAbsolutePath
    var suffixRev: List[String] = Nil // 自底向上收集，cons 后恰为原始顺序
    while base.getParent != null && !Files.exists(base, LinkOption.NOFOLLOW_LINKS) do
      suffixRev = base.getFileName.toString :: suffixRev
      base = base.getParent
    val realBase =
      try base.toRealPath()
      catch case _: Exception => base
    suffixRev.foldLeft(realBase) { (acc, seg) =>
      seg match
        case ".." => Option(acc.getParent).getOrElse(acc)
        case "." | "" => acc
        case other => acc.resolve(other)
    }

  /** contain 检查（§A.3）：NIO startsWith 是逐段比较——天然带分隔符边界，
    * 防 /foo/bar-baz 伪匹配 /foo/bar。两侧都必须先 canonicalize。 */
  def contains(root: Path, target: Path): Boolean =
    target.equals(root) || target.startsWith(root)

end SandboxPolicy

/**
 * nebflow.json 顶层 sandbox 节（§G.1 feature flag + H-5 预留③ + §A.4-4）：
 *
 * {{{
 * "sandbox": {
 *   "enabled": true,                      // 默认 true=合并后生效；false 一键回旧行为
 *   "additionalRoots": [],                // H-5 预留③：跨仓显式可写根，默认空=关
 *   "bash": { "failIfUnavailable": true } // probe 失败 fail-closed（默认）/降级
 * }
 * }}}
 *
 * Fail-safe 加载（镜像 ToolResultTtlConfig.load）：absent/非法 → 默认值
 * （enabled=true）。注意 SharedResources 字段默认值即本默认——但闸门激活还需
 * 会话级 sandboxEnabled=true（仅 project 节点/分发器 spawn 置位），存量测试
 * （无 project 上下文）不受影响。
 */
final case class SandboxConfig(
  enabled: Boolean = true,
  additionalRoots: List[String] = Nil,
  bashFailIfUnavailable: Boolean = true
):
  /** additionalRoots 只应为绝对目录路径——非法项 fail-safe 丢弃（不 fail 启动）。 */
  private def validAdditionalRoots: List[os.Path] =
    additionalRoots.filter(_.nonEmpty).flatMap { s =>
      try
        val expanded = PathUtil.expandTilde(s)
        if Paths.get(expanded).isAbsolute then Some(os.Path(expanded)) else None
      catch case _: Exception => None
    }

  def additionalRootsAsWrite: List[os.Path] = validAdditionalRoots
  def additionalRootsAsRead: List[os.Path] = validAdditionalRoots

object SandboxConfig:
  given Decoder[SandboxConfig] = Decoder.instance { c =>
    for
      enabled <- c.downField("enabled").as[Option[Boolean]].map(_.getOrElse(true))
      additionalRoots <- c.downField("additionalRoots").as[Option[List[String]]].map(_.getOrElse(Nil))
      bashFailIfUnavailable <- c
        .downField("bash")
        .as[Option[Map[String, Boolean]]]
        .map(_.flatMap(_.get("failIfUnavailable")).getOrElse(true))
    yield SandboxConfig(enabled, additionalRoots, bashFailIfUnavailable)
  }

  /** Fail-safe load from the raw config node（镜像 FreezeSchedule.load /
    * ToolResultTtlConfig.load）：absent / 非法 / garbage → 默认（enabled=true）。 */
  def load(json: Option[Json]): SandboxConfig =
    json.flatMap(_.as[SandboxConfig].toOption).getOrElse(SandboxConfig())
end SandboxConfig

package nebflow.core.sandbox

import io.circe.Json
import io.circe.Decoder

import java.nio.file.{Files, LinkOption, Path, Paths}

import scala.jdk.CollectionConverters.*

import nebflow.core.PathUtil

/**
 * 阶段 2a 沙箱（设计文档 §A.2）：单一策略源。
 *
 * root = 会话沙箱根（sessionRoot 唯一推导）。[2026-09-05 21:05 作者裁定——worktree
 * 节点继承项目沙箱]：worktree 节点 root = 所属项目工作区根（不再收窄到 worktree
 * 目录自身——主仓 .git/worktrees/<name>/ 元数据在工作区内，git commit 可直写），
 * 由 NodeEngine spawn 点把工作区根作为独立信号（SessionContext.sandboxRoot）传入，
 * 不按路径形态硬猜 workspace 布局；分发器 = project workspace（H-5①，本就是
 * 继承态）。root 语义只放宽「可写边界」，节点 cwd / projectRoot 工具语义不动。
 * 会话级不可变、构造时即 canonicalize（worktree 布局可能含 symlink → 采用
 * canonicalize 代替拒绝，§A.6）。
 *
 * writableRoots()/readableRoots() 是全部根集合的唯一推导点——JVM 围栏
 * （FileSandbox）与 Seatbelt profile（SandboxBackend.Seatbelt）都从这里取根，
 * 永不漂移（§A.1 dsh 教训）。写 ⊆ 读是不变量：任何可写根必须可读（2026-09-06
 * 读宽后读面全盘，该不变量自动成立）。
 *
 * [2026-09-06 作者裁定——读宽写窄对齐业界标准]：会话读面全盘放开——
 * readableRoots 恒为全盘根（"/"），语义上取代 09-05/06 裁定中「Nebula 读根
 * 不含主仓」半条（读主仓 docs/assets 等会话根外路径不再 SANDBOX_DENIED）；
 * 写根部分全部维持。业界共识对齐（Claude Code Seatbelt 整盘可读+仅工作区
 * 可写；Codex workspace-write 读全盘写 workspace+tmp）：读是低风险面放开，
 * 写才是损害面收窄。既有读面白名单（readExtras/systemReadExtras/
 * nebflowReadExtras/auditReadableFiles）被全盘读吸收：定义保留、退出读面
 * 承重；auditReadableFiles 残留唯一承重 = readDenied 负向规则例外集（Nebula
 * memory.md 精确豁免）。凭据纪律不变（不建 deny 名单，08-19 裁定维持）；
 * agents/**/memory.md 非 Nebula 份读/写双闸一票拒红线延续（非新增 deny）。
 *
 * readExtras（系统只读面，§A.2/H-10①/H-12①）：/usr /System /opt/homebrew
 * /private/etc /private/var + ~/.nebflow 白名单子目录（skills/prompts/docs +
 * 系统运行数据目录 tool-results/uploads/logs/sessions/projects/agents——节点读
 * 自己的工具结果缓存/上传附件/日志/会话/项目元数据/agent 定义是正常工作需求）。
 * §4.2-B 审计只读例外（2026-09-05 memory-management 批次二机制四，作者裁定：
 * 全局白名单加两路径）：~/.nebflow/User.md + ~/.nebflow/agents/Nebula/memory.md
 * 两个【精确文件路径】进读面（审计节点直读记忆真身，替代日志重建通道）——
 * 严格只读（只进 readableRoots，写面零变化），且 agents/**/memory.md 负向规则
 * 对其余一切 agent 记忆不变（readDenied 只精确豁免 Nebula 一个）。
 * [2026-09-05 20:24 作者裁定——数据根入可写面]：~/.nebflow 数据根整体进会话
 * 可写根（writableRoots/readableRoots 推导点追加 PathUtil.dataRoot，取代早期
 * 「裁定 3：agent 只在 project 内写」）——节点直接写项目仓 git commit、
 * plugin/agent 定义层、项目记忆、docs 归档，不再逐笔走宿主命令。整目录放行即
 * 终态：不加新 deny、不建新配置面，根层凭据（auth.json/vps.env/nebflow.json
 * 等）随之可读写（残留风险靠纪律约束，见批次报告）；九子目录白名单与
 * auditReadableFiles 被 dataRoot 整体覆盖属预期（readExtras 原样保留，contains
 * 包含关系下冗余无害）。既有 readDenied 负向规则语义不动：agents/**/memory.md
 * 非 Nebula 份仍一票拒读，且写闸（FileSandbox.checkWrite）同等消费同一规则——
 * 数据根入写面不扩大红线。
 *
 * enabled=false（nebflow.json sandbox.enabled=false 或非 project 会话）即回旧行为
 * （§G.1 回滚语义）：所有闸门短路过行，工具表现与沙箱引入前完全一致
 * （pathRoot=None 保证路径语义亦随之一并回旧）。
 *
 * [沙箱拆围栏批 S1/S2，2026-09-10] 本批只做「解耦」不改能力：①新增 pathRoot
 * （路径语义载体，见字段注释）——路径解析不再挂在围栏总闸上；②会话形态信号
 * projectSession（SessionContext）接手 AGENTS.md 注入判据，sandboxEnabled 不再是
 * 「project 会话」的代名词（design §4.4 S1 / R8）；③FileSandbox 的写根/读拒判定
 * 退役、解析保留（S2 / R5=e2），`agents/<agent>/memory.md` 写拒（R3=c1）保留。`off` 与
 * `forRoot` 的 cfg.enabled 回退点按 §4.5 保留不删。
 *
 * [沙箱拆围栏批 S3，2026-09-10] 宿主 Bash 包裹退场（F6/F7/F8 宿主路径）+ provider
 * 语义落地（R4=d2）：`enabled` 由「围栏总闸」退化为**旧行为回退开关**（仍被
 * `forRoot` 消费，§4.5 回退点不删）；`bashFailIfUnavailable` 字段**退役**（宿主路径
 * 不再 fail-closed；非宿主 provider 不可用一律显式失败，无降级档）。
 * 本 case class 的 root/pathRoot 语义**不变**（路径解析与写根推导原料）。
 *
 * Nebula 根会话（2026-09-05 作者裁定 13:09：Nebula 会话沙箱启用 + 写根
 * =~/.nebflow 数据根，让 Nebula 直接处理定义层与运行时配置）：置位点 =
 * WebSocketRoutes.doSpawnRootAgent（depth=0 全仓唯一 spawn 点）；root =
 * PathUtil.dataRoot——与 nebflowReadExtras 同一数据根推导（NEBFLOW_HOME /
 * CLI --home / setDataRoot 重定向自动跟随，绝不硬编码 os.home）。AgentCore 的
 * sandboxPolicy 构造按 isNebulaRootSession 特判取 dataRoot；节点/分发器会话
 * 仍取 projectRoot 零变化。旁路语义：AGENTS.md 注入 gating（ContextRefresher
 * .agentsMdEnabledFor）按 agentName 排除 Nebula——sandboxEnabled 信号自本批起
 * 不再独占「project 会话」语义。[沙箱拆围栏批 S1, 2026-09-10] 注入判据已彻底换轨
 * 到 SessionContext.projectSession（会话形态信号），与 sandboxEnabled 解耦——
 * sandboxEnabled 自本批起只承载「围栏总闸」（root 推导 + 闸门开关），退役路径见
 * design §4.4 S1/S3。
 */
case class SandboxPolicy(
  /** canonical 后的沙箱根。 */
  root: os.Path,
  /** 只读扩展面（系统工具链 + ~/.nebflow 白名单子目录）。[2026-09-06 读宽批]
    * readableRoots 全盘化后本字段退出读面承重（全盘读吸收一切白名单条目）；
    * 定义保留 = forRoot 构造链与 SUBSUME 类变异用例的快照锚点，不删。 */
  readExtras: List[os.Path] = Nil,
  /** additionalRoots（H-5 预留③）：跨仓显式可写根，默认空=关。语义：追加进
    * writableRoots。[S3] 残留承重 = provider=local-process 的 Seatbelt profile
    * 白名单扩口（JVM 写闸已随 S2 退役；宿主直跑下本字段无消费点）。 */
  extraWritable: List[os.Path] = Nil,
  /** 总开关（§G.1 回退语义）。[拆围栏批 S3，2026-09-10] **不再是围栏总闸**——
    * 围栏已拆（S1/S2/S3），本字段的残留承重 = design §4.5 的「回旧行为」回退点：
    * `false` ⇒ `forRoot` 短路 `off`（无会话根、无相对路径基准）、`SandboxRuntime.init`
    * 不 probe 不包裹、Bash 不拦。**保留不删**（§4.5 硬要求：拆围栏 = 改缺省行为，
    * 不是能力删除）。 */
  enabled: Boolean = true,
  /** 会话根（路径语义载体）。[沙箱拆围栏批 S1/R8 解耦，2026-09-10]
    *
    * 语义：Some = 本会话有工作根 —— 文件工具的**路径语义**锚定它（相对路径基准、
    * Grep/Glob 缺省搜索根等，消费点 ToolPathUtil）；None = 无会话根 = 旧行为
    * （四件套拒相对路径、Glob/Grep 用 JVM user.dir）。与 `root` 的区别：`root`
    * 是根集合推导的原料（写根/Seatbelt profile），本字段只承载路径解析基准。
    *
    * 为什么必须与 `enabled` 分开：`enabled` 是「围栏总闸」——拆围栏批把它退化为
    * 回旧行为的开关（§4.5 回退点，保留不删），而路径解析是**非围栏职能**，拆围栏
    * 要拆的是闸、不是解析（R5=e2「拆闸保解析」）。路径语义若继续挂在 enabled 上，
    * 拨动回退开关会连带把 Grep/Glob 缺省根打回 JVM user.dir——正是 FileSandbox
    * 头注释自陈修掉的旧缺陷（design §8.1 X-1/X-2/X-9 的耦合面）。
    *
    * 取值：forRoot 内与 root 同源（同一次 canonicalize 双写），故有会话根时
    * pathRoot == Some(root)；off / cfg.enabled=false 时为 None（此时 root="/" 仅是
    * 占位，不得当路径基准用）。 */
  pathRoot: Option[os.Path] = None
)

object SandboxPolicy:

  /** 关闭态策略：闸门全部旁路。root 仅为占位（不可达——所有闸门先查 enabled）；
    * pathRoot=None（无会话根 ⇒ 路径语义亦回旧行为：相对路径拒、Grep/Glob 用
    * JVM user.dir）。 */
  val off: SandboxPolicy = SandboxPolicy(os.Path("/"), Nil, Nil, enabled = false)

  /** 系统只读面（§A.2）：够编译器/工具链/系统命令使用。[2026-09-06 读宽批]
    * 被全盘读吸收（定义保留，退出读面承重）。 */
  def systemReadExtras: List[os.Path] =
    List("/usr", "/System", "/opt/homebrew", "/private/etc", "/private/var").map(os.Path(_))

  /** ~/.nebflow 读取白名单（H-12① + 读白名单补全）：skills/prompts/docs 三子目录
    * + 系统运行数据目录 tool-results/uploads/logs/sessions/projects/agents（只读，
    * 不进可写根）。取 PathUtil.dataRoot（rebrand/测试 setDataRoot 均生效）。
    * [2026-09-06 读宽批] 被全盘读吸收（定义保留，退出读面承重）。 */
  def nebflowReadExtras: List[os.Path] =
    List("skills", "prompts", "docs", "tool-results", "uploads", "logs", "sessions", "projects", "agents")
      .map(s => PathUtil.dataRoot / s)

  /**
   * §4.2-B 审计只读例外（2026-09-05 批次二机制四）：两个记忆文件的【精确路径】
   * 进读面——审计类节点 Read 直读记忆真身（日志重建通道 A 的稳态替代）。
   * 只精确放行这两个文件：读面以 contains(file, file)=equals 成立；写面零变化
   * （本列表只进 readableRoots）；与 MemoryStore.userMemoryPath/agentMemoryPath
   * 的路径契约由 SandboxSpec 断言（防漂移）。def 而非 val：跟随 setDataRoot。
   *
   * [2026-09-06 读宽批] 全盘读后「进读面」语义被吸收；本列表残留唯一承重 =
   * readDenied 负向规则的例外集（Nebula memory.md 精确豁免一票拒），定义保留。
   */
  def auditReadableFiles: List[os.Path] =
    List(
      PathUtil.dataRoot / "User.md",
      PathUtil.dataRoot / "agents" / "Nebula" / "memory.md"
    )

  /** agents 子树根（负向规则锚点）。 */
  private def agentsReadRoot: os.Path = PathUtil.dataRoot / "agents"

  /**
   * 文件级读负向规则（凭据红线，优先于 readableRoots——FileSandbox 先查本函数）：
   * agents/ 子树内一切 memory.md（agent 私有记忆，Nebula 与 team agent 同规）——
   * 【唯一例外】§4.2-B 审计只读放行的 Nebula memory.md 精确路径（auditReadableFiles，
   * canonical 域比较）。其余 agents/**/memory.md 负向规则不变。
   * 输入必须是 canonical 路径（与 readableRoots 同一 canonical 域比较）。
   */
  def readDenied(canonical: Path): Boolean =
    // 例外集随调用点动态解析（def 语义与 readableRoots 一致，setDataRoot 生效）
    val exceptions = auditReadableFiles
      .filter(_.toString.endsWith("memory.md"))
      .map(p => canonicalize(p.wrapped))
      .toSet
    readDeniedWith(canonical, exceptions)

  /** 可注入例外集形态（spec 变异验红面：空集 = 旧行为，Nebula memory.md 也拒读）。 */
  def readDeniedWith(canonical: Path, auditExceptions: Set[Path]): Boolean =
    val agents = os.Path(canonicalize(agentsReadRoot.wrapped))
    val underAgentsMemory =
      canonical.startsWith(agents.wrapped) && canonical.getFileName.toString == "memory.md"
    if underAgentsMemory then !auditExceptions.contains(canonical)
    else false

  /**
   * Grep/Glob 遍历排除（红线覆盖遍历面）：搜索根落在 agents 子树内时，rg 追加
   * basename 级排除（任意深度 memory.md）——文件级负向规则管不住 rg 目录遍历，
   * 不排除则白名单内搜索会把 memory.md 内容扫进结果。返回 rg 参数片段（命中时）。
   */
  def memoryGlobExcludes(canonicalRoot: Path): List[String] =
    val agents = os.Path(canonicalize(agentsReadRoot.wrapped))
    if canonicalRoot.startsWith(agents.wrapped) then List("--glob", "!memory.md") else Nil

  /** forRoot readExtras 快照原料。[2026-09-06 读宽批] readableRoots 不再消费
    * readExtras（全盘读吸收），本推导保留供构造链与变异用例；语义 = 建议性
    * 快照，非承重面。 */
  def defaultReadExtras: List[os.Path] = systemReadExtras ++ nebflowReadExtras ++ auditReadableFiles

  /**
   * Nebula 根会话判定（2026-09-05 作者裁定 13:09）：sandboxEnabled ∧ depth==0 ∧
   * agentName=="Nebula"。
   *
   * 三个分量缺一不可的取舍：
   *  - depth==0：WS 根会话（WebSocketRoutes.doSpawnRootAgent 全仓唯一 depth=0
   *    spawn 点）。排除 NodeDef.agent="Nebula" 声明的节点会话（depth=1，root
   *    必须留在 projectRoot/worktree——节点写根语义 §A.6 零回归）。
   *  - agentName=="Nebula"：排除 standalone 非 Nebula 聊天 / team Manager / flow
   *    入口等其余 WS 根会话（它们 sandboxEnabled 保持 false，双保险）。名字判据
   *    与 shouldInjectMemory/skillCatalogEnabledFor 同款先例（agentLibrary 以名
   *    为唯一键，"Nebula" 键即 Nebula 本体）。
   *  - sandboxEnabled：总开关（spawn 置位 × nebflow.json feature flag），本判定
   *    不绕过 §G.1 回滚语义。
   *
   * 公开供 spec 断言（agentsMdEnabledFor 同文件先例）；AgentCore root 推导与
   * 本函数是 Nebula 沙箱根的唯一裁决点。
   */
  def isNebulaRootSession(sandboxEnabled: Boolean, depth: Int, agentName: String): Boolean =
    sandboxEnabled && depth == 0 && agentName == "Nebula"

  /**
   * 会话沙箱根推导（AgentCore sandboxPolicy 构造唯一调用点）：Nebula 根会话 →
   * PathUtil.dataRoot（数据根，与 nebflowReadExtras 同源——NEBFLOW_HOME /
   * CLI --home / setDataRoot 重定向自动跟随，绝不硬编码 os.home）；其余会话 →
   * 显式 sandboxRoot（worktree 节点继承项目工作区根，2026-09-05 21:05 作者裁定
   * ——NodeEngine spawn 点直接传入，禁止按路径形态硬猜 workspace 布局）优先，
   * 缺省回落 projectRoot（分发器 workspace / 未接线节点，§A.6 既有语义零变化），
   * 再缺省回落 effectiveProjectRoot 既有语义不变。
   *
   * 优先级次序（Nebula 特判 > sandboxRoot > projectRoot > fallback）零回归约束：
   * Nebula 分支在前保证 depth==0 根会话不被节点信号误覆盖（节点会话 depth=1
   * 不命中 Nebula 判据，两分支天然不相交）；sandboxRoot 仅由 NodeEngine /
   * ProjectActor 两个 sandboxEnabled=true 置位点传入，其余调用方缺省 None =
   * 旧行为逐字节不变。
   *
   * 公开供 spec 断言：root 跟随 setDataRoot 的断言即「隔离实例写真 ~/.nebflow
   * 不可能」的机制证明（spec beforeEach 钉 dataRoot 到一次性目录，断言 root ==
   * 钉住目录）。
   */
  def sessionRoot(
    sandboxEnabled: Boolean,
    depth: Int,
    agentName: String,
    projectRoot: Option[String],
    fallbackProjectRoot: String,
    sandboxRoot: Option[String] = None
  ): String =
    if isNebulaRootSession(sandboxEnabled, depth, agentName) then PathUtil.dataRoot.toString
    else
      sandboxRoot
        .filter(_.nonEmpty)
        .orElse(projectRoot.filter(_.nonEmpty))
        .getOrElse(fallbackProjectRoot)

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
      val canonicalRoot = os.Path(canonicalize(root.wrapped))
      SandboxPolicy(
        root = canonicalRoot,
        readExtras = (defaultReadExtras ++ cfg.additionalRootsAsRead).map(p => os.Path(canonicalize(p.wrapped))).distinct,
        extraWritable = cfg.additionalRootsAsWrite.map(p => os.Path(canonicalize(p.wrapped))),
        enabled = true,
        // 路径语义载体与 root 同源（拆围栏批 S1 解耦）：闸门退役不动解析基准。
        pathRoot = Some(canonicalRoot)
      )

  /**
   * 数据根（运行形态 =~/.nebflow）。2026-09-05 20:24 作者裁定起进会话可写根——
   * 项目仓 git commit、plugin/agent 定义层、项目记忆、docs 归档由节点直接落盘。
   * 推导唯一正源 = PathUtil.dataRoot（NEBFLOW_HOME / CLI --home / setDataRoot
   * 重定向自动跟随），严禁硬编码 os.home：隔离测试实例（--home /tmp/...）下
   * 可写根落在隔离 HOME，机制上写不穿真 ~/.nebflow。def 而非 val：跟随
   * setDataRoot 每次重解析（与 nebflowReadExtras/auditReadableFiles 同款语义）。
   */
  def nebflowDataRoot: os.Path = PathUtil.dataRoot

  /** 临时根：/private/tmp、/tmp（符号链接形态，Seatbelt 需两种拼写）、java.io.tmpdir。 */
  private def tempRoots: List[os.Path] =
    List(os.Path("/private/tmp"), os.Path("/tmp"), os.Path(Paths.get(sys.props.getOrElse("java.io.tmpdir", "/tmp"))))

  /**
   * 可写根（唯一推导）。macOS 上 /tmp→/private/tmp 归一后通常剩 root +
   * 数据根 + /private/tmp + java.io.tmpdir（/private/var/folders/...）去重后的
   * 集合（root=dataRoot 的 Nebula 会话去重后数据根只出现一次）。
   */
  def writableRoots(p: SandboxPolicy): List[os.Path] =
    (p.root :: nebflowDataRoot :: p.extraWritable ::: tempRoots)
      .map(x => os.Path(canonicalize(x.wrapped)))
      .distinct

  /**
   * 可读根（唯一推导）。[2026-09-06 作者裁定——读宽写窄对齐业界标准]：恒为
   * 全盘根 List("/")——contains 逐段比较对一切绝对路径天然恒真，canonical 域
   * 比较不变（os.Path("/") 即 canonical 形态）。语义取代 2026-09-05/06 裁定中
   * 「读根不含主仓」半条；写根部分（writableRoots）维持不变——读宽写窄不对称
   * 即本批语义核心，写 ⊆ 读不变量自动成立。
   *
   * p 参数保留（签名稳定：FileSandbox/GlobTool/GrepTool 调用点零改动；未来若
   * 引入读面例外可直接从 policy 取），当前推导不消费。
   */
  def readableRoots(p: SandboxPolicy): List[os.Path] =
    List(os.Path("/"))

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
 * nebflow.json 顶层 sandbox 节（§G.1 + H-5 预留③ + **拆围栏批 S3 / R4=d2 provider**）：
 *
 * {{{
 * "sandbox": {
 *   "provider": "host",                   // 执行环境 provider，缺省 host（宿主直跑）
 *   "enabled": true,                      // 旧行为回退点（§4.5）：false = 拆围栏前旧行为
 *   "additionalRoots": [],                // H-5 预留③：跨仓显式可写根，默认空=关
 *   "bash": { "failIfUnavailable": true } // [S3 已退役] 不再被消费，见字段注释
 * }
 * }}}
 *
 * [拆围栏批 S3，2026-09-10] 语义迁移逐项：
 *  - **新增 `provider`（执行环境 provider）**，缺省 `host`——取代 docker 批 A2
 *    「缺省 seatbelt」（design §4.2 表 + §6.4 取代项 6）。取值/失败语义见
 *    `SandboxProvider`；U7：未实现或不可用的取值**显式失败，绝不静默回落 host**。
 *  - `enabled`：从「围栏总闸」退化为**旧行为回退开关**（§4.5 硬要求保留）。
 *  - `additionalRoots`：写白名单扩口（写闸已随 F1 退役）——残留承重 = provider=
 *    local-process 的 Seatbelt profile 白名单扩口（`writableRoots`），保留。
 *  - `bash.failIfUnavailable`：**失去对象**（宿主路径不再 fail-closed；非宿主
 *    provider 不可用一律显式失败，无「降级到无围栏」档）⇒ 字段与键名保留（源码
 *    兼容：存量 spec 具名参数），但**不再被任何代码消费**，启动时 WARN 提示退役。
 *
 * Fail-safe 加载（镜像 ToolResultTtlConfig.load）：absent/非法 → 默认值
 * （provider=host / enabled=true）。注意 SharedResources 字段默认值即本默认。
 * **例外（U7）**：`provider` 键存在但取值非法/类型错误时**不静默当 host**——落
 * Host 值但把可读原因带在 `providerError` 上，由 `SandboxRuntime.init` 转成 Bash
 * 的显式失败。
 */
final case class SandboxConfig(
  enabled: Boolean = true,
  additionalRoots: List[String] = Nil,
  /** [S3 退役：不再被消费] sandbox.bash.failIfUnavailable（§A.4-4 旧语义：probe
    * 失败时降级跑 + `[unsandboxed]` 前缀）。新语义下 provider 不可用一律显式失败
    * （U7），无「降级」档 ⇒ 本字段与其配置键保留仅为源码兼容，启动时 WARN。 */
  bashFailIfUnavailable: Boolean = true,
  /** 执行环境 provider（design §4.2 / R4=d2 缺省 `host`）。 */
  provider: SandboxProvider = SandboxProvider.Host,
  /** provider 取值非法时的**显式失败原因**（U7：绝不静默回落 host）；None = 配置可解释。 */
  providerError: Option[String] = None
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
      providerJson <- c.downField("provider").as[Option[Json]]
    yield
      // provider 解析（S3 / R4=d2）：缺省 host；**非法取值或错误类型不静默回落
      // host**（U7）——值落 Host（保证失败路径有根可用），原因带在 providerError 上。
      val (provider, providerError) = providerJson match
        case None => (SandboxProvider.Host, None)
        case Some(json) =>
          json.asString match
            case Some(raw) =>
              SandboxProvider.parse(raw) match
                case Right(p)   => (p, None)
                case Left(msg)  => (SandboxProvider.Host, Some(msg))
            case None =>
              (
                SandboxProvider.Host,
                Some(s""""sandbox.provider" 必须是字符串（收到 ${json.noSpaces.take(40)}）。""")
              )
      SandboxConfig(enabled, additionalRoots, bashFailIfUnavailable, provider, providerError)
  }

  /** Fail-safe load from the raw config node（镜像 FreezeSchedule.load /
    * ToolResultTtlConfig.load）：absent / 非法 / garbage → 默认（provider=host,
    * enabled=true）。provider **取值**非法不经此路（decoder 内转 providerError，
    * 见 U7：不静默回落 host）。 */
  def load(json: Option[Json]): SandboxConfig =
    json.flatMap(_.as[SandboxConfig].toOption).getOrElse(SandboxConfig())
end SandboxConfig

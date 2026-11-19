package nebflow.shared

/**
 * 记忆面「全局层」的路径基（单一实现，供 [[nebflow.core.tools.MemoryDirectWriteGuard]]
 * 与 [[nebflow.core.tools.MemoryChangeNotifier]] 共用）。
 *
 * personal-agent 批 2026-10-04：Soul 记忆上收到根层（`<root>/Soul.md`，与 `User.md`
 * 同级）后，这两处的路径基准从 `user.home` 换成 `PathUtil.dataRoot`，为的是隔离实例
 * （`--home` / CLI 换根）下判得中。
 *
 * ⚠ 但「只认运行时根」会引入一个新脆点：`PathUtil.dataRoot` 是**进程级可换根**的
 * （`Main` 只在启动时解析一次 `--home`；测试里 `setDataRoot` 遍布各 suite，多数
 * 不还原），而调用点收到的 pathStr 与进程级的最后一次换根不必同源。实测后果：
 * `MailQueueRootSpec` 在类体里 `setDataRoot` 且不还原，紧随其后的
 * `MemoryDirectWriteGuardSpec`（按 `user.home` 造路径）把三层**全部**判成 `None`
 * ⇒ 闸门 fail-open 且表现为跨 suite 顺序相关的假红。
 *
 * 因此这里并认**全部合法基**，判定与换根顺序解耦：
 *  1. 运行时数据根（`PathUtil.dataRoot`）—— 隔离实例下的真实位置；
 *  2. 默认 home 根（`<user.home>/<brand.homeDirName>`）—— 主实例位置（数据根
 *     指向切换批 2026-10 起为 `~/.stand`）；
 *  3. legacy 根（`<user.home>/.nebflow`，PathUtil.LegacyHomeDirName 硬编码）——
 *     切换批后仍是合法字面位：老机器在迁移工具箱跑完前数据根落这里，运行时
 *     根在新根的机器上 agent 也可能仍按旧字面引用（过渡期双读）。
 * 生产端三基多数等价或并存（唯一换根点是 `Main` 的 `--home` 解析，canary 各自
 * 独立进程）⇒ 保护面只增不减；测试端起「顺序无关」的作用。
 *
 * 基相等时去重（返回一份，调用端语义不变）。
 */
object MemoryPaths:

  private def normalize(p: String): String = p.replace("\\", "/").replaceAll("/+$", "")

  /** 全局记忆层的合法基（已归一：分隔符统一 `/`、去掉尾斜杠）。 */
  def globalBases: List[String] =
    val runtime = normalize(PathUtil.dataRoot.toString)
    val defaultHome = normalize((os.home / Branding.homeDirName).toString)
    val legacy = normalize((os.home / PathUtil.LegacyHomeDirName).toString)
    List(runtime, defaultHome, legacy).distinct

  /** `pathStr` 是否落在任一全局基之下（用于「全局 home 域内 vs 任意 workspace」判别）。 */
  def isUnderGlobalBase(pathStr: String, bases: List[String]): Boolean =
    val normalized = pathStr.replace("\\", "/")
    bases.exists(b => normalized.startsWith(s"$b/"))

  /** 归一化（供调用点复用同一口径）。 */
  def normalizePath(pathStr: String): String = pathStr.replace("\\", "/")

end MemoryPaths

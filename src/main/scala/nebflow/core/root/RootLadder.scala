package nebflow.core.root

import nebflow.shared.PathUtil

/**
 * 三层数据根 · 解析阶梯（B1，契约 `reports-evidence/20261104_b1-dataroot/design-api.md` §2）。
 *
 * 三档「有效根」是全机数据面的唯一分档口径：
 *  - [[RootLayer.Account]]：已登录（机器层有 `active-account` 指针且目标账号根在，或入口目录
 *    自身就是账号根 —— `profile.json` 在场）。有效根 = 账号目录（0700）。
 *  - [[RootLayer.Unauthenticated]]：装了但没登录（机器层有 `layout.json` 布局标记且**没有**
 *    指针文件）。有效根 = `<machineRoot>/unauthenticated`，只服务登录页且**不铸**设备身份。
 *  - [[RootLayer.Flat]]：老平铺根（无布局标记），以及一切「说不清」的形态。有效根 == 机器层根，
 *    与今日 `PathUtil.dataRoot` **逐字节相同**（兼容红线：老机器升级后第一次启动仍走这档、零写盘）。
 *
 * == 三条不可动的总则（契约 §0）==
 *  1. 无 `layout.json` 的平铺根，一切字节不变；
 *  2. 进程级单值、禁热切 —— 登录成功后写指针 + 重启进程，进程内不二次换根；
 *  3. 机器层先于账号层可写 —— 定根 / 日志 / pid / 单实例门全走机器层。
 *
 * [[resolve]] 是**纯读**（只做存在性探测 + 指针/标记的内容读，零写盘），生产面由
 * [[nebflow.shared.PathUtil]] 在进程定根时调用**一次**；[[current]] 是那个定案值的快照。
 */
sealed trait RootLayer:
  /** 机器层根（全机共享：`bin/`、生命周期 `logs/`、`.pid`、`daemons*.json`、`layout.json`）。 */
  def machineRoot: os.Path

  /** 有效根 —— 一切「账号内容」面的落盘基准（今日 `PathUtil.dataRoot` 的语义）。 */
  def root: os.Path

  /** 档名：`"account" | "unauthenticated" | "flat"`（也是 `/api/health.rootLayer` 的取值）。 */
  def name: String

object RootLayer:

  // 档名（也是 `/api/health.rootLayer` 的取值域）与未登录层的稳定标记串 ——
  // 名字只在这里拼一次，日志 / 异常 / 健康面共用，避免三处各写一份字面。
  val AccountName: String = "account"
  val UnauthenticatedName: String = "unauthenticated"
  val FlatName: String = "flat"

  /** 未登录层稳定标记串（契约 §6.3：boot WARN 与拒绝铸造的异常消息都用它，测试按子串断言）。 */
  val UnauthenticatedMarker: String = "UNAUTHENTICATED-LAYER"

  /** 已登录档。`accountSub` 保 `accountSub` **原文**（不是目录名，见 `AccountRoot.dirNameOf`）。 */
  final case class Account(machineRoot: os.Path, accountSub: String, root: os.Path) extends RootLayer:
    def name: String = AccountName

  /** 未登录档：只开登录面，不铸设备身份（契约 §6）。 */
  final case class Unauthenticated(machineRoot: os.Path, root: os.Path) extends RootLayer:
    def name: String = UnauthenticatedName

  /** 平铺档：无布局标记的老根（以及指针坏档/目标缺失的降级档）。 */
  final case class Flat(machineRoot: os.Path, root: os.Path) extends RootLayer:
    def name: String = FlatName

end RootLayer

object RootLadder:

  /** 未登录层目录名（`machineRoot/unauthenticated`）。 */
  val UnauthenticatedDirName: String = "unauthenticated"

  /** 账号目录容器名（`machineRoot/accounts/<dir>`）。 */
  val AccountsDirName: String = "accounts"

  /**
   * 纯读解析（契约 §2，**顺序即契约**，不得调整）：
   *
   * | # | 条件 | 结果 |
   * |---|---|---|
   * | 0 | `machineRoot/profile.json` 在场 ⇒ 该目录**自身**即账号根；机器层 = `parents(2)` | `Account` |
   * | 1 | 指针可解析且指向的账号根存在 | `Account` |
   * | 2 | `layout.json` 在场且**指针文件不存在** | `Unauthenticated` |
   * | 3 | 其余（老平铺根 / 指针坏档 / 指针指向缺失目录） | `Flat` |
   *
   * 第 0 档的存在理由（契约 §8.2）：`DaemonSpawn` / autostart 传的是**已解析好的账号根**，
   * `--home` 同时接受机器根与账号根，由 `profile.json` 判别，**不新增旗标**。
   *
   * 第 3 档必须收「指针坏档」：判成未登录层会把一个坏 JSON 放大成「用户数据根消失」+ 踢回
   * 登录页；正确动作是按平铺根起来（数据仍可见）+ 一条 `ROOT-POINTER-DAMAGED` WARN，
   * 由登出面处置（`AccountRoot.readPointer` 已留痕）。
   */
  def resolve(machineRoot: os.Path): RootLayer =
    if AccountRoot.isAccountRoot(machineRoot) then
      // 第 0 档：入口目录自身就是账号根（autostart / 子进程传的是已解析好的根）。
      val sub = AccountRoot.readProfile(machineRoot).map(_.accountSub).getOrElse(machineRoot.last)
      RootLayer.Account(machineRootOf(machineRoot), sub, machineRoot)
    else
      AccountRoot.readPointer(machineRoot) match
        case Some(active) =>
          val dir = AccountRoot.accountRoot(machineRoot, active.accountSub)
          if os.isDir(dir) then RootLayer.Account(machineRoot, active.accountSub, dir)
          else
            // 指针存在但目标缺失 ⇒ 第 3 档 flat（绝不落未登录层：数据根仍在机器根上）。
            RootLayer.Flat(machineRoot, machineRoot)
        case None =>
          val pointerFile = AccountRoot.pointerPath(machineRoot)
          if AccountRoot.readLayoutMarker(machineRoot).isDefined && !os.exists(pointerFile) then
            // 第 2 档：装了 + 有布局标记 + 从未登录过。
            RootLayer.Unauthenticated(machineRoot, machineRoot / UnauthenticatedDirName)
          else RootLayer.Flat(machineRoot, machineRoot)

  /**
   * 纯结构推断（零 IO）：`.../accounts/<dir>` ⇒ `parents(2)`；其余原样返回。
   *
   * 用途 = 把「账号根」（autostart / 子进程 / 热重启后继传进来的 `--home`）还原成机器层根，
   * 供单实例门 / 端口归属 / 日志落位的同口径比较（契约 §8.2 / §9.3）。
   */
  def machineRootOf(raw: os.Path): os.Path =
    val parent = raw / os.up
    if parent.last == AccountsDirName then parent / os.up else raw

  /**
   * 进程级只读快照（契约 §2）：定案后不再变（「写指针后本进程不认」是设计而非缺陷 ——
   * 登录成功后重启进程生效）。测试面的 reset 由 [[PathUtil.setDataRoot]] /
   * [[PathUtil.setMachineRoot]] 触发（它们就是换根搬手）。
   */
  def current: RootLayer = PathUtil.resolvedLayer

end RootLadder

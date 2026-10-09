package nebflow.neblink

import cats.effect.IO
import nebflow.core.project.ProjectStore
import nebflow.shared.{NebflowLogger, PathUtil}

import scala.util.Try

/**
 * 浏览面**出口白名单**（v2 §C.4 / v2.1 阶段 1 §C.1-3）。
 *
 * `scope = "browse"` 的 FileTransfer 请求（direction=list 元数据列举 / direction=get
 * 单件取件）只服务白名单根内的路径——「防止被编排设备的任意盘读取」（v2 §C.4 原文
 * 判据）。缺省根（spec 定值）：
 *   1. 登记过的项目 workspace 目录——本机定义（origin 缺省/self）+ **绑到本机的**
 *      origin（`origin.device == 本机 deviceId` 或字面 `self`）；
 *   2. `captures/`（CapturePull 落盘根）；
 *   3. `uploads/`（网页附件上传根，`dataRoot/uploads`）。
 *
 * 双轨纪律：白名单**只对显式 `scope="browse"` 生效**——无 scope 键的既有调用
 * （capture 回拉 / A1 读图回拉 / 既有 FileTransfer get/put/probe）逐字节不变，
 * 存量零回归。可配追加根 = nebflow.json 顶层 `deviceShare.extraPaths`（字符串数组，
 * 宽容解码）。
 *
 * 判据核 = 纯函数（segment 前缀匹配 + canonical 单点规整），`isAllowed` 直接可测；
 * 根集 `collectRoots` 是 IO（项目注册表热读），拒绝理由**不外泄**白名单表内容。
 */
object EgressWhitelist:

  private val log = NebflowLogger.forName("nebflow.neblink.egress")

  /** captures 根（与 `CapturePull.capturesRoot` 同值——彼处 private[tools]，此处按 spec 定值复刻并加锚点注释）。 */
  def capturesRoot: os.Path = PathUtil.dataRoot / "neblink" / "captures"

  /** uploads 根（WebSocketRoutes 上传腿 `Config.NebflowHome / "uploads"` 同值）。 */
  def uploadsRoot: os.Path = PathUtil.dataRoot / "uploads"

  /** 可配追加根（nebflow.json 顶层 `deviceShare.extraPaths`；宽容解码 = Nil）。 */
  def extraPaths: List[String] =
    try
      val p = PathUtil.configJsonReadPath(PathUtil.dataRoot)
      if !os.exists(p) then Nil
      else
        io.circe.parser
          .parse(os.read(p))
          .toOption
          .flatMap(_.hcursor.downField("deviceShare").focus)
          .flatMap(_.hcursor.downField("extraPaths").as[List[String]].toOption)
          .getOrElse(Nil)
    catch case _: Throwable => Nil

  /**
   * 白名单根集（每次现算——项目注册表与配置都是热读面；量级小，无缓存需求）。
   * 不可解析的根（Try 失败）跳过并留 WARN——禁静默扩大、也禁整表失败。
   */
  def collectRoots: IO[List[os.Path]] =
    ProjectStore.listAll().flatMap { defs =>
      IO.blocking {
        val selfId = DeviceIdentity.readDeviceIdOnly
        val projectRoots = defs.flatMap { pd =>
          pd.origin match
            case None => Some(pd.workspace)
            case Some(o) if nebflow.core.project.ProjectOrigin.isSelfLiteral(o.device) => Some(o.path)
            case Some(o) if selfId.contains(o.device) => Some(o.path)
            case Some(_) => None // 绑在别的设备上的项目：其 origin.path 不在本机白名单
        }
        val fixed = List(capturesRoot.toString, uploadsRoot.toString)
        (projectRoots ++ fixed ++ extraPaths).flatMap { p =>
          Try(os.Path(PathUtil.expandTilde(p), PathUtil.dataRoot)).toOption match
            case Some(dir) => Some(normalize(dir))
            case None =>
              log.warnSync(s"[egress] whitelist root unresolvable, skipped: $p")
              None
        }
      }
    }
  end collectRoots

  /**
   * 判据（纯函数核，`allowedRoots` 注入供 spec 驱动）：路径的 segment 前缀等于某根
   * 或与某根相同 ⇒ 放行。segment 比较（os-lib 平台归一）= 目录前缀语义，天然防
   * `..`/后缀串编（`/a/bc` 不是 `/a/b` 的前缀命中）。
   */
  /**
   * 判据（纯函数核，`allowedRoots` 注入供 spec 驱动）：路径的 segment 前缀等于某根
   * 或与某根相同 ⇒ 放行。segment 比较（os-lib 平台归一）= 目录前缀语义，天然防
   * `..`/后缀串编（`/a/bc` 不是 `/a/b` 的前缀命中）。
   *
   * 🔴 实施批修正（2026-10-09，spec 承重断言抓到的真缺陷）：os-lib 的
   * `Path.segments` 是**一次性 iterator 视图**——视图 `take(...) == 视图` 是
   * 两个不同实例的恒 false 比较 ⇒ 修正前的 isAllowed 对**一切**路径（含白名单
   * 内）返回 false。修正 = 两侧 `toList` 物化后比较（ProbeIsAllowedSpec 实证
   * `taken == segs = false`、`toList == toList = true`）。
   */
  def isAllowed(path: os.Path, allowedRoots: List[os.Path]): Boolean =
    val real = normalize(path).segments.toList
    allowedRoots.exists { root =>
      val r = normalize(root).segments.toList
      real.take(r.length) == r
    }
  end isAllowed

  /**
   * browse 请求闸（FileTransferAction 的 scope=browse 分支单点）。
   * `None` = 放行；`Some(reason)` = 拒绝（可读、不外泄白名单表）。
   */
  def checkBrowseAccess(path: os.Path): IO[Option[String]] =
    collectRoots.map { roots =>
      if isAllowed(path, roots) then None
      else
        log.warnSync(s"[egress] browse request DENIED (path outside share whitelist): ${normalize(path)}")
        Some(
          s"path is outside the device share whitelist: ${normalize(path)} " +
            "(browse scope is limited to registered project workspaces, captures and uploads)"
        )
    }

  /** 单点规整：canonical 化（解 `..`/symlink 差异归一，前缀比较防穿越的既定手法）。 */
  def normalize(p: os.Path): os.Path =
    Try(os.Path(p.toIO.getCanonicalPath)).getOrElse(p)

end EgressWhitelist

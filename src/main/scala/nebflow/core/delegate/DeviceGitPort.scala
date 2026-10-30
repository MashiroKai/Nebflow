package nebflow.core.delegate

import cats.effect.IO
import cats.syntax.all.*
import io.circe.JsonObject
import io.circe.syntax.*
import nebflow.core.tools.RemoteExecutor
import nebflow.shared.NebflowLogger

/**
 * DeviceGitPort —— 设备项目的**设备侧 git 面**（跨设备 worktree 归属修复批
 * 2026-10-30，作者报障：设备项目 delegate 的 worktree 被建在编排机 brain 下）。
 *
 * 语义 = [[DelegateWorkspace.prepareDevice]] 的两态裁量的执行通道：
 *   - 真身（RemoteFace）= 设备通道 Bash 下发（[[RemoteExecutor]]）——探测设备
 *     workspace 是否 git 仓、读基分支/基 SHA、在 `<设备workspace>/.stand-worktrees/`
 *     下建 worktree（分支纪律同本机腿：`delegate/<id>`）；
 *   - 端口注册形态同 DelegateJudgePort / DelegateBudgetPort（M2 同款）：spec 以
 *     fake 面钉「有 git / 无 git / 不可达」三态断言，零网络零真机；
 *   - 所有命令恒 `exit 0` + 标记行（NEBFLOW_DEVGIT / NEBFLOW_BR / NEBFLOW_SHA /
 *     NEBFLOW_WT_OK / NEBFLOW_WT=…）——Bash 下发面不保证非零退出的回传形态，
 *     成败一律走标记解析；通道级失败（execute Left）= Left（不可达回落判据）。
 *
 * 路径内嵌的唯一转义点 = [[sq]]（shell 单引号）——设备 workspace 是设备本地路径
 * （可能含空格/引号），禁裸拼。
 */
object DeviceGitPort:

  private val logger = NebflowLogger.forName("nebflow.core.delegate.devicegit")

  /** 设备侧 git 面（真身 = 设备通道；spec 以 fake 面替换）。 */
  trait Face:

    /** 设备 workspace 是否在 git 仓内。Left = 通道失败（设备不可达/下发错）——
     *  调用方 WARN + 回落「不建 worktree 直用 workspace」，不阻断派发。 */
    def isGitRepo(deviceName: String, workspace: String): IO[Either[String, Boolean]]

    /** 基分支 + 基 HEAD（short sha）——设备仓读数；None = detached/未知（fail-open）。 */
    def refInfo(deviceName: String, workspace: String): IO[(Option[String], Option[String])]

    /**
     * 在设备 workspace 内建 worktree：目录候选 `<dirBase>`、`<dirBase>-2`…
     * （与本机腿同名候选律一致，上限 99），`git worktree add -b <branch> <dir> HEAD`。
     * 成功 = worktree 设备本地全路径；Left = 失败诊断（建败回落判据）。
     */
    def createWorktree(
      deviceName: String,
      workspace: String,
      branch: String,
      dirBase: String
    ): IO[Either[String, String]]

  end Face

  /** shell 单引号转义（设备通道 Bash 参数内嵌路径/分支名的唯一转义点）。 */
  private[delegate] def sq(s: String): String = "'" + s.replace("'", "'\\''") + "'"

  /** 真身：设备通道 Bash 下发（RemoteExecutor 单例缺席 = 无设备通道 ⇒ Left）。 */
  private object RemoteFace extends Face:

    private def bash(
      deviceName: String,
      command: String,
      description: String,
      timeoutMs: Long = 30000L
    ): IO[Either[String, String]] =
      RemoteExecutor.current match
        case None =>
          IO.pure(Left("RemoteExecutor not initialized on this host — no device channel"))
        case Some(re) =>
          re
            .execute(
              deviceName,
              "Bash",
              JsonObject(
                "command"   -> command.asJson,
                "timeout"   -> timeoutMs.asJson,
                "description" -> description.asJson
              ),
              None
            )
            .map(_.leftMap(_.message))

    /** 输出里取 `NEBFLOW_K=v` 形态的标记值（首命中；缺 = None）。 */
    private def marker(out: String, key: String): Option[String] =
      out.linesIterator
        .collectFirst { case l if l.startsWith(key + "=") => l.stripPrefix(key + "=").trim }
        .filter(_.nonEmpty)

    def isGitRepo(deviceName: String, workspace: String): IO[Either[String, Boolean]] =
      val cmd =
        s"if git -C ${sq(workspace)} rev-parse --is-inside-work-tree 2>/dev/null | grep -q '^true$$'; " +
          "then echo NEBFLOW_DEVGIT=1; else echo NEBFLOW_DEVGIT=0; fi"
      bash(deviceName, cmd, "device worktree leg: git probe", timeoutMs = 20000L).map {
        case Left(err)    => Left(err)
        case Right(out)   => Right(out.contains("NEBFLOW_DEVGIT=1"))
      }

    def refInfo(deviceName: String, workspace: String): IO[(Option[String], Option[String])] =
      val q = sq(workspace)
      val cmd =
        s"b=$$(git -C $q rev-parse --abbrev-ref HEAD 2>/dev/null); " +
          s"s=$$(git -C $q rev-parse --short HEAD 2>/dev/null); " +
          "echo NEBFLOW_BR=$b; echo NEBFLOW_SHA=$s"
      bash(deviceName, cmd, "device worktree leg: base ref probe", timeoutMs = 20000L).map {
        case Left(err) =>
          logger.warn(s"device ref probe failed on '$deviceName' ($err) — baseBranch/baseSha degraded to None")
          (None, None)
        case Right(out) =>
          val br = marker(out, "NEBFLOW_BR").filter(b => b != "HEAD")
          (br, marker(out, "NEBFLOW_SHA"))
      }

    def createWorktree(
      deviceName: String,
      workspace: String,
      branch: String,
      dirBase: String
    ): IO[Either[String, String]] =
      val q  = sq(workspace)
      val qb = sq(branch)
      val qd = sq(dirBase)
      // 恒 exit 0 + 标记解析：cd 失败/目录候选耗尽/git 失败各有标记。验证步 =
      // 建后 rev-parse —— 成功才回 NEBFLOW_WT_OK=<设备本地全路径>。
      // ⚠ s 串内字面 $ 一律写 $$（Scala 插值转义律）。
      val cmd =
        s"""cd $q || { echo NEBFLOW_WT=FAIL_CD; exit 0; }
mkdir -p .stand-worktrees || { echo NEBFLOW_WT=FAIL_MKDIR; exit 0; }
base=$qd; name="$$base"; i=1
while [ -e ".stand-worktrees/$$name" ] && [ "$$i" -lt 99 ]; do i=$$((i+1)); name="$$base-$$i"; done
if [ -e ".stand-worktrees/$$name" ]; then echo NEBFLOW_WT=EXHAUSTED; exit 0; fi
git worktree add -b $qb ".stand-worktrees/$$name" HEAD >/dev/null 2>&1
if git -C ".stand-worktrees/$$name" rev-parse --is-inside-work-tree >/dev/null 2>&1; then
  echo "NEBFLOW_WT_OK=$$(pwd)/.stand-worktrees/$$name"
else
  echo NEBFLOW_WT=FAIL_GIT
fi
exit 0"""
      bash(deviceName, cmd, "device worktree leg: git worktree add", timeoutMs = 60000L).map {
        case Left(err) => Left(err)
        case Right(out) =>
          marker(out, "NEBFLOW_WT_OK") match
            case Some(path) => Right(path)
            case None       => Left("device git worktree add failed: " + marker(out, "NEBFLOW_WT").getOrElse("unknown"))
      }

  end RemoteFace

  @volatile private var installed: Face = RemoteFace

  private[delegate] def current: Face = installed

  /** 安装 fake 面（spec 用；端口是全局注册面——用毕 clear，测试间零残留）。 */
  private[nebflow] def install(face: Face): Unit = synchronized { installed = face }

  private[nebflow] def clear(): Unit = synchronized { installed = RemoteFace }

end DeviceGitPort

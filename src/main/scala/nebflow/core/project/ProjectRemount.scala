package nebflow.core.project

import cats.effect.IO
import io.circe.Json
import io.circe.syntax.*
import nebflow.actor.ActorSystem
import nebflow.core.AgentRuntimePort
import nebflow.shared.NebflowLogger

/**
 * Project remount（卸载态项目的恢复挂载）——unmount-console 面板批 2026-11-02。
 *
 * == 为什么单点 ==
 * 控制台「已卸载」分组的「重新加载」按钮（REST 腿）与编排侧 Project 工具
 * `action="remount"`（工具腿）必须走**同一条引擎通路**（同一 API、同一校验序、
 * 同一审计形状）——与 unmount 面的既有收口纪律（ProjectUnmount 单点 + 双面薄壳）
 * 严格同构，禁两套行为。
 *
 * == 序（校验 → 摘键 → 挂载 → 帧 → 审计；任何一步失败即中止，前面已写的键随
 * 项目留在盘面、重试安全——摘键幂等（无键 = 零写）、挂载幂等（已挂 = 返回既有）） ==
 *
 *  '''①校验盘面定义'''：`ProjectStore.listAll()` 大小写不敏感解析 canonical 名——
 *    不在盘 ⇒ Left（nothing to remount）；`archived=true` ⇒ Left（归档保持单程
 *    语义——remount 不复活归档项目，可行动指路手工删两键，与 create 面拒挂分支
 *    同文案族）。工作区存在性校验不在此重复：[[ProjectRuntimeRegistry.mount]] 对
 *    本机项目经 DeviceProjectMount/mountAtWorkspace 的既有铁律把关（不存在即
 *    IO.raiseError → Left），设备项目经设备通道 stat——零新造预检。
 *
 *  '''②摘卸载标记'''：`unmounted=true` 在场 ⇒ `ProjectStore.clearUnmounted`
 *    （手术式删两键）；本来就没标记（定义在盘但未挂载的重启残留态）⇒ 跳过（零写）。
 *
 *  '''③挂载'''：`ProjectRuntimeRegistry.mount`（幂等：已挂载返回既有 runtime）。
 *    rootSessionId 由调用方给**真顶层根**（REST 腿 = ensureActiveAgentSession 产物，
 *    与 startupMount 唯一真源同源；工具腿 = 调用会话的 rootSessionId——与 create
 *    面 mountDefinition 的「禁凭空造根」守卫同款：无会话上下文的 remount 显式拒绝，
 *    由调用面负责，本对象不做二次守卫）。
 *
 *  '''④帧'''：成功腿广播 `projectRemounted` 帧（帧形单点 =
 *    [[ProjectActor.projectRemountedFrame]]，row 与 GET /api/projects 行同构 +
 *    unmountedAt/remountedAt）——前端据它定点恢复卡片。幂等腿（已挂载）不发帧。
 *
 *  '''⑤审计'''：flow-map-events.jsonl 追加一行 `project-remounted`（谁 / 何时 /
 *   哪个项目——actor 进 summary）。审计失败只 WARN 不回滚（best-effort 与 unmount
 *   腿同纪律）。
 *
 * == 零删除红线 ==
 * 本对象**零** `os.remove`/`os.move`/写工作区路径：唯一写盘面 = project.json 的
 * 手术式摘键（[[ProjectStore.clearUnmounted]]）与 .nebflow 内的审计 append——
 * workspace 与全部文件原样。
 */
object ProjectRemount:

  private val logger = NebflowLogger.forName("nebflow.project.remount")

  /** remount 结果读数（工具结果句与 spec 断言共用）。 */
  final case class RemountReport(
    project: String,
    remountedAt: Long,
    /** 幂等腿：项目本就挂载中 ⇒ true（零副作用、零帧、零审计行）。 */
    alreadyMounted: Boolean = false,
    /** 卸载标记的既有时刻（定义带标记时回读；本来无标记 = None）。 */
    unmountedAt: Option[Long] = None
  )

  /**
   * remount 入口。`name` 大小写不敏感解析（与 ProjectUnmount.unmount 同法）。
   *
   * @param name          项目名
   * @param system        actor 系统（挂载面必需）
   * @param resources     共享运行时（挂载面必需）
   * @param rootSessionId 真顶层会话根（投递根；调用方负责非空——startupMount 同源）
   * @param wsSend        既有广播通道（None = no-op，与 mount 面 fold(IO.unit) 同款）
   * @param actor         审计动作方（REST 腿 "console"，工具腿调用会话 id）
   */
  def remount(
    name: String,
    system: ActorSystem,
    resources: AgentRuntimePort,
    rootSessionId: String,
    wsSend: Option[Json => IO[Unit]],
    actor: String
  ): IO[Either[String, RemountReport]] =
    if rootSessionId.trim.isEmpty then
      IO.pure(
        Left(
          "remount refused: no rootSessionId — the delivery root cannot be attributed and inventing " +
            "a placeholder root is not allowed (mirror of the create face's mount guard). " +
            "Re-invoke from an agent session or the console (which resolves the true root)."
        )
      )
    else
      ProjectStore.listAll().flatMap { defs =>
        defs.find(d => d.name.equalsIgnoreCase(name)) match
          case None =>
            IO.pure(Left(s"Project '$name' not found — nothing to remount"))
          case Some(pd) =>
            val canonical = pd.name
            if pd.archived.contains(true) then
              IO.pure(
                Left(
                  s"Project '$canonical' is archived (remount does not revive archived projects). " +
                    s"Remove the 'archived'/'archivedAt' keys in projects/$canonical/project.json to restore it first."
                )
              )
            else
              ProjectRuntimeRegistry.get(canonical).flatMap {
                case Some(_) =>
                  // 幂等腿：已在挂载（零副作用——不摘键、不发帧、不审计；报告声明 no-op）。
                  IO.pure(Right(RemountReport(canonical, System.currentTimeMillis(), alreadyMounted = true)))
                case None =>
                  val unmountedAt = pd.unmountedAt.filter(_ => pd.unmounted.contains(true))
                  val at = System.currentTimeMillis()
                  (for
                    _ <- ProjectStore.clearUnmounted(canonical).flatMap {
                      case Left(err) => IO.raiseError(new RuntimeException(s"clear unmount marker failed: $err"))
                      case Right(_) => IO.unit
                    }
                    fresh <- ProjectStore
                      .load(canonical)
                      .map(
                        _.getOrElse(
                          throw new IllegalStateException(s"remount: definition '$canonical' vanished mid-flight")
                        )
                      )
                    _ <- ProjectRuntimeRegistry.mount(fresh, system, resources, wsSend, rootSessionId)
                  yield fresh).attempt.flatMap {
                    case Left(e) =>
                      IO.pure(
                        Left(
                          s"remount failed (project stays unmounted on the list face; retry is safe): ${e.getMessage}"
                        )
                      )
                    case Right(fresh) =>
                      wsSend
                        .fold(IO.unit)(send => send(ProjectActor.projectRemountedFrame(fresh, at)))
                        .handleErrorWith(e =>
                          logger.warn(s"[$canonical] remount: projectRemounted broadcast failed: ${e.getMessage}")
                        ) *>
                        FlowMapEventLog
                          .append(
                            ProjectStore.brainWorkspace(fresh),
                            canonical,
                            "",
                            ProjectUnmount.AuditTypeRemounted,
                            s"remounted by $actor at $at (unmount marker cleared; workspace files kept byte-for-byte)"
                          )
                          .handleErrorWith(e =>
                            logger.warn(s"[$canonical] remount audit append failed: ${e.getMessage}")
                          )
                          .as(Right(RemountReport(canonical, at, alreadyMounted = false, unmountedAt = unmountedAt)))
                  }
              }
            end if
      }
  end remount

end ProjectRemount

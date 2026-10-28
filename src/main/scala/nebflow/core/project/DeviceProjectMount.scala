package nebflow.core.project

import cats.effect.IO
import io.circe.Json
import nebflow.core.AgentRuntimePort
import nebflow.core.tools.RemoteExecutor
import nebflow.shared.PeerInfo

/**
 * 设备项目挂载编排（跨设备项目挂载批 2026-10-25）。
 *
 * 语义（作者令：项目挂载到设备 KAI 验证跨设备项目功能）：项目 workspace 本体在
 * **对端设备本盘**，引擎（大脑）在本机——挂载预检/骨架/AGENTS.md 镜像经**设备
 * 通道**落在目标设备真实路径，FlowMap/TaskBoard/台账等全部引擎存储锚在本机
 * [[ProjectStore.brainWorkspace]]（大脑在 Mac，账本一本）。
 *
 * 序（全部步在本侧**零磁盘写**——写盘点只有 ④ 的本机大脑根建目录）：
 *   ① 通道在场判（NebLink 未配置 / RemoteExecutor 未初始化 ⇒ 结构化报错）；
 *   ② 名册解析（origin.device = deviceId 精确优先，回落名册解析单点；
 *      离线 = 显式失败**列在线设备**，绝不静默落本机）；
 *   ③ 存在性 stat（`direction=list` 元数据，零字节）：不可达/非目录 ⇒ 结构化
 *      报错（[[ProjectMountPreflight.deviceRefusal]] 指明设备与路径）——
 *      🔴 铁律：workspace 必须在声明设备上真实存在才准挂，绝不静默造骨架；
 *   ④ 骨架落设备真实路径（AGENTS.md / .gitignore，缺件即补、既有永不覆盖——
 *      与本地 ensureScaffold 同纪律；复用 v2.1 §A.5 的远端脚手架腿）；
 *   ⑤ AGENTS.md 镜像回本机大脑根（context 注入面读本机文件——镜像缺失时建空
 *      件，取件失败 WARN 不阻断挂载：注入面全族 best-effort 先例）。
 *
 * 设备侧常驻执行器（agent 会话整体跑在设备上）明确 out-of-scope：挂载语义 =
 * 引擎侧会话 + 工具跨通道（sessionDevice 默认路由，见 ToolContext）；DeviceDelegate
 * 的「整会话派设备」仍服务未挂载 remote-origin 项目（旧语义，hook 保留）。
 */
private[project] object DeviceProjectMount:

  /** 预检产物：对端 peer + 会话默认设备名 + 本机大脑根（[[ProjectStore.brainWorkspace]]）。 */
  final case class Prepared(peer: PeerInfo, brainWorkspace: String, scaffoldNote: String)

  /**
   * 挂载预检 + 设备侧落备。失败 = `IO.raiseError(RuntimeException(结构化文案))`
   * ——挂载调用方（ProjectRuntimeRegistry.mount）既有 fail-fast 语义原样生效：
   * 运行时创建腿显式报错、startupMount 的 #46 fail-soft WARN 跳过。
   */
  def prepare(pd: ProjectDef, resources: AgentRuntimePort): IO[Prepared] =
    val (device, devicePath) =
      ProjectStore.deviceOrigin(pd).fold(("", ""))(o => (o.device, o.path))
    val neblinkOpt = resources.neblinkService
    (neblinkOpt, RemoteExecutor.current) match
      case (None, _) =>
        IO.raiseError(
          new RuntimeException(
            ProjectMountPreflight.deviceRefusal(
              device,
              devicePath,
              "NebLink is not configured on this host (no device network)"
            )
          )
        )
      case (_, None) =>
        IO.raiseError(
          new RuntimeException(
            ProjectMountPreflight.deviceRefusal(
              device,
              devicePath,
              "the device executor is not initialized on this host"
            )
          )
        )
      case (Some(neblink), Some(executor)) =>
        neblink.peers.flatMap { peers =>
          // ② 名册解析：origin.device = deviceId（创建面写入的稳定标识）精确优先，
          //    回落名册解析单点（按名同解析，手工改写 project.json 的形态防御一致）。
          val peer = peers
            .find(_.deviceId == device)
            .orElse(
              RemoteExecutor.resolvePeer(device, peers).toOption
            )
          peer match
            case None =>
              val online = peers.map(_.deviceName).mkString(", ")
              IO.raiseError(
                new RuntimeException(
                  ProjectMountPreflight.deviceRefusal(
                    device,
                    devicePath,
                    s"device is offline or unknown (online devices: ${if online.isEmpty then "（无）" else online})"
                  )
                )
              )
            case Some(p) =>
              // ③ 存在性 stat（元数据列举，零字节）。
              executor.transferListScoped(p, devicePath).flatMap {
                case Left(err) =>
                  // 版本偏斜诊断（台账 #901，2026-10-26）：对端引擎早于 list 方向
                  // （2026-10-10）⇒ 签名命中时追加升级指路（挂载腿与创建腿同诊）；
                  // 签名不中 ⇒ 报错文本逐字节不变。
                  val msg = RemoteExecutor
                    .listDirectionSkewNote(err, p.deviceName)
                    .map(n => s"$err — $n")
                    .getOrElse(err)
                  IO.raiseError(
                    new RuntimeException(ProjectMountPreflight.deviceRefusal(p.deviceName, devicePath, msg))
                  )
                case Right(listing) =>
                  val isDir = listing.hcursor.downField("entries").focus.isDefined
                  if !isDir then
                    IO.raiseError(
                      new RuntimeException(
                        ProjectMountPreflight
                          .deviceRefusal(p.deviceName, devicePath, "path exists but is not a directory")
                      )
                    )
                  else
                    // ④ 骨架落设备真实路径（缺件即补、既有永不覆盖）。复用 ③ 列举
                    // （同 peer 同路径二次 list 去重，2026-10-26 #901 批）。
                    scaffold(executor, p, devicePath, listing).flatMap {
                      case Left(err) =>
                        IO.raiseError(
                          new RuntimeException(
                            ProjectMountPreflight.deviceRefusal(
                              p.deviceName,
                              devicePath,
                              s"scaffold write failed: $err (retry is safe: missing pieces are backfilled, existing files are never overwritten)"
                            )
                          )
                        )
                      case Right(note) =>
                        val brainWs = ProjectStore.brainWorkspace(pd)
                        // ⑤ AGENTS.md 镜像 + 本机大脑根建目录（唯一的本机写盘点）。
                        mirrorAgentsMd(executor, p, devicePath, brainWs).flatMap { _ =>
                          IO.blocking(os.makeDir.all(os.Path(brainWs) / ".nebflow"))
                            .as(
                              Prepared(p, brainWs, note)
                            )
                        }
                    }
                  end if
              }
          end match
        }
    end match
  end prepare

  /** origin 元组便利（避免 Option[ProjectOrigin] 双 fold 取值走样）。 */

  /**
   * 设备侧骨架（AGENTS.md + .gitignore 的 `.nebflow/` 行）：**缺件即补、既有永不
   * 覆盖**。与 ProjectCreateService.scaffoldRemote 同纪律同形状——挂载腿复用同一
   * 判定（目录元数据由 ③ 传入——2026-10-26 #901 批去重：本腿不再自发 list，同
   * peer 同路径二次往返删除；缺失才 Write），不共享实现（创建腿属
   * ProjectCreateService 私有；两处消费同一 RemoteExecutor 原语，抽公共件留待
   * 第三次出现）。
   */
  private def scaffold(
    executor: RemoteExecutor,
    peer: PeerInfo,
    devicePath: String,
    listing: Json
  ): IO[Either[String, String]] =
    val existing: Set[String] =
      listing.hcursor
        .downField("entries")
        .as[List[Json]]
        .toOption
        .getOrElse(Nil)
        .flatMap { e =>
          e.hcursor.downField("name").as[String].toOption
        }
        .toSet
    val wanted: List[(String, String)] = List(
      ("AGENTS.md", ProjectCreateService.AgentMdTemplateFull),
      (".gitignore", ".nebflow/\n")
    )
    val missing = wanted.filterNot((name, _) => existing.contains(name))
    if missing.isEmpty then IO.pure(Right("scaffold on device: nothing to do (all pieces already present)"))
    else
      missing
        .foldLeft(IO.pure(Right(Nil): Either[String, List[String]])) { (acc, item) =>
          acc.flatMap {
            case l @ Left(_) => IO.pure(l)
            case Right(done) =>
              executor
                .writeRemoteFileForScaffold(
                  peer,
                  s"${ProjectCreateService.stripTrailingSlashes(devicePath)}/${item._1}",
                  item._2
                )
                .map {
                  case Right(_) => Right(done :+ item._1)
                  case Left(err) => Left(err)
                }
          }
        }
        .map {
          case Right(done) => Right(s"scaffold on device: created ${done.mkString(", ")}")
          case Left(err) => Left(err)
        }
    end if
  end scaffold

  /**
   * AGENTS.md 镜像（设备 → 本机大脑根）：context 注入面（resolveAgentsMd）读
   * 本机文件，镜像让它对设备项目照常工作。设备为权威（挂载时点取件）；取件失败
   * 或超闸 ⇒ WARN + 空镜像（不阻断挂载——注入面 best-effort 全族先例）。
   */
  private def mirrorAgentsMd(
    executor: RemoteExecutor,
    peer: PeerInfo,
    devicePath: String,
    brainWorkspace: String
  ): IO[Unit] =
    val brainAgents = os.Path(brainWorkspace) / "AGENTS.md"
    executor
      .transferGetScoped(peer, s"${ProjectCreateService.stripTrailingSlashes(devicePath)}/AGENTS.md")
      .flatMap {
        case Right(bytes) =>
          IO.blocking {
            os.makeDir.all(brainAgents / os.up)
            os.write.over(brainAgents, bytes)
          } *>
            IO(
              nebflow.shared.NebflowLogger
                .forName("nebflow.project.devicemount")
                .infoSync(
                  s"[device-mount] ${peer.deviceName}:$devicePath AGENTS.md mirrored to $brainAgents (${bytes.length} bytes)"
                )
            )
        case Left(err) =>
          IO.blocking {
            os.makeDir.all(brainAgents / os.up)
            if !os.exists(brainAgents) then os.write.over(brainAgents, "")
          } *>
            IO(
              nebflow.shared.NebflowLogger
                .forName("nebflow.project.devicemount")
                .warnSync(
                  s"[device-mount] AGENTS.md mirror pull failed for ${peer.deviceName}:$devicePath ($err) — empty local mirror kept; mount proceeds"
                )
            )
      }
  end mirrorAgentsMd

end DeviceProjectMount

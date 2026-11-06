package nebflow.neblink

import nebflow.neblink.core.NeblinkService

import cats.effect.IO
import cats.syntax.all.*
import io.circe.syntax.*
import nebflow.shared.{NebflowLogger, PeerInfo}

import scala.concurrent.duration.*

/**
 * 远端更新动作的**单一派发实现**（fleet 批 2026-11-01 收口）。
 *
 * 修前仓内有三处「P2P `/api/neblink/update` → 失败回落中继 `relayUpdate`」的同构代码
 * （`NeblinkRoutes.doRemoteUpdate` / `WsSystemHandlers.handleRemoteUpdate` 的 P2P 段 /
 * DeviceList 入口）。fleet 批要按名册批量推送，若再写一份就是第四份 ⇒ 本件收口为
 * 唯一派发实现：**找设备 → P2P → 回落中继 → 统一 `Either[String, String]`**。
 *
 * 纪律（与既有实现逐字同）：
 *   · 隧道动作 `RemoteUpdate` 的参数集由跨仓契约钉死（契约 §B.1.3）= `{beta}`，本件
 *     不扩参；`clientRequestId` 只随 **P2P 载荷**（加法字段，老端忽略），中继腿不带；
 *   · P2P 读超时 180s（既有值）——远端装包在请求内同步发生；
 *   · 不打印任何凭据：日志只有设备名与地址（地址本身不是凭据）。
 *
 * 本件不含任何安装/重启语义——那是设备侧既有安装动作本体（`RemoteUpdateAction`）
 * 与既有关机重启链的事。
 */
object RemoteUpdateDispatch:

  private val logger = NebflowLogger.forName("nebflow.neblink.update-dispatch")

  /**
   * 按设备名派发（既有入口的语义：name 等值优先，其次包含匹配——逐字保留）。
   * `Either`：`Left` = 找不到设备 / 无地址 / P2P 与中继都失败。
   */
  def dispatchByName(
    ns: NeblinkService,
    targetDevice: String,
    beta: Boolean,
    clientRequestId: Option[String] = None
  ): IO[Either[String, String]] =
    dispatchByNameResolved(ns, targetDevice, beta, clientRequestId).map(_.map(_._2))

  /**
   * 同 [[dispatchByName]]，但 `Right` 带回**解析到的对端名**——WS 的
   * `remoteUpdateResult` 帧历来用对端自报名（不是调用方传进来的字串；后者大小写/包含
   * 匹配后可能不同），前端状态机按该名挂号 ⇒ 名称口径必须与原实现逐字一致。
   */
  def dispatchByNameResolved(
    ns: NeblinkService,
    targetDevice: String,
    beta: Boolean,
    clientRequestId: Option[String] = None
  ): IO[Either[String, (String, String)]] =
    ns.peers.flatMap { peers =>
      peers.find(p =>
        p.deviceName.equalsIgnoreCase(targetDevice) ||
          p.deviceName.toLowerCase.contains(targetDevice.toLowerCase)
      ) match
        case None => IO.pure(Left(s"Device '$targetDevice' not found"))
        case Some(peer) =>
          dispatchToPeer(ns, peer, beta, clientRequestId).map(_.map(peer.deviceName -> _))
    }

  /** 按 peer 派发（fleet 批用：名册里已经有 deviceId ⇒ 不再按名反查）。 */
  def dispatchToPeer(
    ns: NeblinkService,
    peer: PeerInfo,
    beta: Boolean,
    clientRequestId: Option[String] = None
  ): IO[Either[String, String]] =
    if peer.address.isEmpty then IO.pure(Left(s"Device '${peer.deviceName}' has no address"))
    else
      logger.info(s"Remote update via P2P: ${peer.deviceName} at ${peer.address} (beta=$beta)") *>
        IO.blocking {
          import sttp.client4.*
          val fields = List("beta" -> beta.asJson)
            ++ clientRequestId.map(id => "clientRequestId" -> id.asJson)
          val body = io.circe.Json.obj(fields*).noSpaces
          basicRequest
            .post(sttp.model.Uri.unsafeParse(s"${peer.address}/api/neblink/update"))
            .contentType("application/json")
            .body(body)
            .readTimeout(180.seconds)
            .response(asStringAlways)
            .send(ns.httpBackend)
        }.flatMap { resp =>
          if !resp.code.isSuccess then relayFallback(ns, peer, beta, s"P2P HTTP ${resp.code}")
          else
            // 🔴 200 **不等于装成功**：设备侧安装动作失败时 `/api/neblink/update` 回
            // 200 + `{ok:false,error}`（既有契约，见 NeblinkRoutes 该 case 的注释）。
            // 只看状态码会把「拒装 / 校验不过 / 脚本失败」当成「已受理并重启」，
            // 然后白白等满自检窗再判成自检没过——**失败分类全错**（e2e 实测抓到过）。
            // 因此 200 必须解 body：`ok:false` ⇒ Left(设备给的原因)，且**不回落中继**
            // （设备明明在线并回答了——这是设备拒绝，不是通道不通）。
            val bodyOpt = io.circe.parser.parse(resp.body).toOption
            val refused = bodyOpt.flatMap { j =>
              val c = j.hcursor
              if c.downField("ok").as[Boolean].toOption.contains(false) then
                Some(c.downField("error").as[String].toOption.orElse(c.downField("message").as[String].toOption)
                  .getOrElse("device refused the update"))
              else None
            }
            refused match
              case Some(err) => IO.pure(Left(err))
              case None      => IO.pure(Right("Update installed, device is restarting..."))
        }.handleErrorWith { e =>
          relayFallback(ns, peer, beta, s"P2P unreachable: ${e.getMessage}")
        }

  /** 中继腿（与既有 `relayUpdateFallback` 逐字同构）。 */
  private def relayFallback(
    ns: NeblinkService,
    peer: PeerInfo,
    beta: Boolean,
    p2pError: String
  ): IO[Either[String, String]] =
    ns.relayClientOpt match
      case Some(client) =>
        logger.info(s"P2P update failed ($p2pError), trying relay to ${peer.deviceName}") *>
          client.relayUpdate(peer.deviceId, beta)
      case None => IO.pure(Left(p2pError))

end RemoteUpdateDispatch

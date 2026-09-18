package nebflow.dropbox

import cats.effect.IO
import cats.syntax.all.*
import io.circe.Json
import io.circe.parser.decode
import io.circe.syntax.*
import nebflow.core.NebflowLogger
import nebflow.neblink.NeblinkClient

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.util.Base64

import scala.concurrent.duration.*

/**
 * P2P 分块传输腿（Dropbox 主腿）—— 直连对端网关的 HTTP 端点。
 *
 * 端点（`RestApiRoutes`）：
 *   - `POST /api/neblink/dropbox/transfer/<tid>` + 分块头 ⇒ 应用一块；
 *   - `GET  /api/neblink/dropbox/probe/<tid>`         ⇒ 断点续传探针。
 *
 * 头集合（分块语义只走头，body 保持裸字节 —— 不 base64、不整件驻留）：
 *   `X-Dropbox-Proto` / `X-Dropbox-Index` / `X-Dropbox-Total-Bytes` / `X-Dropbox-Chunk-Size` /
 *   `X-Dropbox-Chunk-Sha256` / `X-Dropbox-Whole-Sha256`。
 *
 * 超时：**块级**、按调用传（建议值 60 s/块，LAN 直连；见设计件 §9 P-3 同族）。
 * 既有整件路径的 30 min 超时保留给 legacy 模式，不被本类改动。
 */
final class P2PChunkTransport(
  peerAddress: String,
  chunkTimeout: FiniteDuration = 60.seconds
) extends ChunkTransport:

  private val logger = NebflowLogger.forName("nebflow.dropbox")

  def leg: String = "p2p"

  def put(
    target: FileTransfer,
    frame: ChunkedTransfer.ChunkFrame,
    payload: Array[Byte]
  ): IO[Either[AttachContract.AttachError, ChunkedTransfer.ChunkAck]] =
    if peerAddress.isEmpty then
      IO.pure(Left(AttachContract.AttachError(AttachContract.Codes.PeerUnreachable, "Peer address is empty — no P2P route", phase = "transfer", path = Some("p2p"))))
    else
      IO.blocking {
        val client = HttpClient.newBuilder().proxy(java.net.ProxySelector.of(null)).build()
        val request = HttpRequest
          .newBuilder()
          .uri(URI.create(s"$peerAddress/api/neblink/dropbox/transfer/${frame.transferId}"))
          .header("Content-Type", "application/octet-stream")
          // 🔴 `X-Dropbox-Proto` 头**恒为 1**，**不得**随契约升版改成 2：接收端
          // `parseDropboxChunkHeaders` 的判据是**等值**（`== ProtoChunked`），头升 2 会让
          // **旧接收端** guard 为假 ⇒ 走 legacy 整件路径 ⇒ 把第一块当整件落盘（静默数据损坏）。
          // 版本数值轴（`AttachContract.ProtoAssignDir = 2`）**只走 JSON 面**
          // （`file-offer` / `file-response`）。常绿钉：`AttachProtoHeaderPinSpec`。
          .header("X-Dropbox-Proto", AttachContract.ProtoChunked.toString)
          .header("X-Dropbox-Index", frame.chunkIndex.toString)
          .header("X-Dropbox-Total-Bytes", frame.totalBytes.toString)
          .header("X-Dropbox-Chunk-Size", frame.chunkSize.toString)
          .header("X-Dropbox-Chunk-Sha256", frame.chunkSha256)
          .header("X-Dropbox-Whole-Sha256", frame.wholeSha256)
          .timeout(java.time.Duration.ofMillis(chunkTimeout.toMillis))
          .POST(HttpRequest.BodyPublishers.ofByteArray(payload))
          .build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        (response.statusCode(), response.body())
      }.attempt.map {
        case Left(e) =>
          Left(
            AttachContract.AttachError(
              AttachContract.Codes.PeerUnreachable,
              s"P2P chunk ${frame.chunkIndex} failed: ${Option(e.getMessage).getOrElse(e.getClass.getSimpleName)}",
              phase = "transfer",
              chunkIndex = Some(frame.chunkIndex),
              path = Some("p2p")
            )
          )
        case Right((status, body)) =>
          parseAck(frame, status, body, "p2p")
      }

  def probe(target: FileTransfer): IO[Either[AttachContract.AttachError, ChunkedTransfer.ReceiveState]] =
    if peerAddress.isEmpty then
      IO.pure(Left(AttachContract.AttachError(AttachContract.Codes.PeerUnreachable, "Peer address is empty — no P2P route", phase = "transfer", path = Some("p2p"))))
    else
      IO.blocking {
        val client = HttpClient.newBuilder().proxy(java.net.ProxySelector.of(null)).build()
        val request = HttpRequest
          .newBuilder()
          .uri(URI.create(s"$peerAddress/api/neblink/dropbox/probe/${target.transferId}"))
          .timeout(java.time.Duration.ofMillis(chunkTimeout.toMillis))
          .GET()
          .build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        (response.statusCode(), response.body())
      }.attempt.map {
        case Left(e) =>
          Left(
            AttachContract.AttachError(
              AttachContract.Codes.PeerUnreachable,
              s"P2P probe failed: ${Option(e.getMessage).getOrElse(e.getClass.getSimpleName)}",
              phase = "transfer",
              path = Some("p2p")
            )
          )
        case Right((status, body)) => parseProbe(target, status, body, "p2p")
      }

  private def parseAck(
    frame: ChunkedTransfer.ChunkFrame,
    status: Int,
    body: String,
    legName: String
  ): Either[AttachContract.AttachError, ChunkedTransfer.ChunkAck] =
    if status != 200 then
      Left(
        AttachContract.AttachError(
          AttachContract.Codes.PeerUnreachable,
          s"Peer returned HTTP $status for chunk ${frame.chunkIndex}: $body",
          phase = "transfer",
          chunkIndex = Some(frame.chunkIndex),
          path = Some(legName),
          actual = Some(status.toLong)
        )
      )
    else
      decode[Json](body).toOption match
        case None =>
          Left(
            AttachContract.AttachError(
              AttachContract.Codes.PeerUnreachable,
              s"Peer returned 200 with an undecodable body: $body",
              phase = "transfer",
              chunkIndex = Some(frame.chunkIndex),
              path = Some(legName)
            )
          )
        case Some(json) =>
          val hc = json.hcursor
          if !hc.downField("ok").as[Boolean].getOrElse(false) then
            decodeError(json, frame.chunkIndex, legName)
          else
            Right(
              ChunkedTransfer.ChunkAck(
                transferId = frame.transferId,
                chunkIndex = frame.chunkIndex,
                bytesReceived = hc.downField("bytesReceived").as[Long].getOrElse(frame.offset + frame.bytes),
                // 接收端**自算**的块摘要（禁自证，R4）：缺失 ⇒ 回落到本地摘要（= 请求头回显）
                // 会让发送端的 `ack.chunkSha256 == frame.chunkSha256` 比对恒真 ——
                // 故此处与 relay 腿同口径，回落空串 ⇒ 显式 `CHUNK_DIGEST_MISMATCH`。
                chunkSha256 = hc.downField("chunkSha256").as[String].getOrElse(""),
                wholeSha256 = hc.downField("wholeSha256").as[String].toOption
              )
            )

  private def parseProbe(
    target: FileTransfer,
    status: Int,
    body: String,
    legName: String
  ): Either[AttachContract.AttachError, ChunkedTransfer.ReceiveState] =
    if status != 200 then
      Left(
        AttachContract.AttachError(
          AttachContract.Codes.SessionNotFound,
          s"Peer returned HTTP $status for probe: $body",
          phase = "transfer",
          path = Some(legName),
          actual = Some(status.toLong)
        )
      )
    else
      decode[Json](body).toOption match
        case None =>
          Left(
            AttachContract.AttachError(
              AttachContract.Codes.SessionNotFound,
              s"Peer returned 200 with an undecodable probe body: $body",
              phase = "transfer",
              path = Some(legName)
            )
          )
        case Some(json) =>
          val hc = json.hcursor
          Right(
            ChunkedTransfer.ReceiveState(
              transferId = target.transferId,
              bytesReceived = hc.downField("bytesReceived").as[Long].getOrElse(0L),
              totalBytes = hc.downField("totalBytes").as[Long].getOrElse(target.totalBytes),
              prefixSha256 = hc.downField("prefixSha256").as[String].getOrElse("")
            )
          )

  private def decodeError(
    json: Json,
    chunkIndex: Int,
    legName: String
  ): Either[AttachContract.AttachError, ChunkedTransfer.ChunkAck] =
    val errJson = json.hcursor.downField("error")
    Left(
      AttachContract.AttachError(
        errJson.downField("code").as[String].getOrElse("PEER_REJECTED"),
        errJson.downField("message").as[String].getOrElse(json.noSpaces),
        phase = errJson.downField("phase").as[String].getOrElse("transfer"),
        chunkIndex = Some(chunkIndex),
        path = Some(legName)
      )
    )

end P2PChunkTransport

/**
 * relay 兜底分块腿（**共面**：作者排期口径「Dropbox 流式腿为底座 + relay 兜底分块」）。
 *
 * 今天 relay 腿的缺陷（本类**禁复现**）：
 *   - `DropboxService:678` `case Right(_) => Right(hash) // same content — hash trivially matches`
 *     ⇒ 校验**自证**、`matchResult` 恒真（I2）；
 *   - 整件 base64（`os.read.bytes` 全量驻留，I4）；
 *   - `NeblinkClient.sendRequest` 全局 10 s 硬超时（长件必失败）。
 * 本类改为：**逐块 base64**（单块有界）+ **接收端自算摘要回传** + **按调用传超时**。
 */
final class RelayChunkTransport(
  client: NeblinkClient,
  chunkTimeout: FiniteDuration = 30.seconds
) extends ChunkTransport:

  def leg: String = "relay"

  def put(
    target: FileTransfer,
    frame: ChunkedTransfer.ChunkFrame,
    payload: Array[Byte]
  ): IO[Either[AttachContract.AttachError, ChunkedTransfer.ChunkAck]] =
    val b64 = Base64.getEncoder.encodeToString(payload)
    client
      .relayTransferPutChunk(
        targetDeviceId = target.peerDeviceId,
        // dropnam 批（真漏 B 根修）：写**同一 transfer 的确定性 temp 名**，不再写对端
        // `~/Downloads/<裸名>`（旧形态下撞名会 append 到别人件上、整件摘要不符时还会
        // 被接收端 `os.remove.all` **删掉**）。落点由 `DropboxUtil.relayLandingPath`
        // 唯一一处算（目标目录已获接受时用接收端裁定的 `targetDir`）。
        path = DropboxUtil.relayLandingPath(target),
        contentB64 = b64,
        chunkIndex = frame.chunkIndex,
        totalBytes = frame.totalBytes,
        chunkSize = frame.chunkSize,
        chunkSha256 = frame.chunkSha256,
        wholeSha256 = frame.wholeSha256,
        overwrite = true,
        timeout = chunkTimeout,
        // 归属 token（可选键）：路径里内嵌的 `<tid8>` 由接收端**自证** ⇒ 该路径上「本次的续传」
        // 与「别人的件」可判，收端才敢 append/清理自己的 temp。🔴 门控与落点**同一判据**
        // （`peerSupportsRelayTemp`）：旧接收端那一格不带上本键 ⇒ 该格 wire 形态逐字节同今天。
        transferId = Option.when(DropboxUtil.peerSupportsRelayTemp(target))(target.transferId)
      )
      .map {
        case Right(json) =>
          val hc = json.hcursor
          Right(
            ChunkedTransfer.ChunkAck(
              transferId = frame.transferId,
              chunkIndex = frame.chunkIndex,
              bytesReceived = hc.downField("bytesReceived").as[Long].getOrElse(frame.offset + frame.bytes),
              // 接收端**自算**的块摘要（禁自证）：缺失 ⇒ 回落到本地摘要会让校验形同虚设，
              // 故此处显式要求存在，否则报错。
              chunkSha256 = hc.downField("chunkSha256").as[String].getOrElse(""),
              wholeSha256 = hc.downField("wholeSha256").as[String].toOption
            )
          )
        case Left(err) =>
          Left(
            AttachContract.AttachError(
              AttachContract.Codes.PeerUnreachable,
              s"Relay chunk ${frame.chunkIndex} failed: $err",
              phase = "transfer",
              chunkIndex = Some(frame.chunkIndex),
              path = Some("relay")
            )
          )
      }
      .handleErrorWith(e =>
        IO.pure(
          Left(
            AttachContract.AttachError(
              AttachContract.Codes.PeerUnreachable,
              s"Relay chunk ${frame.chunkIndex} threw: ${Option(e.getMessage).getOrElse(e.getClass.getSimpleName)}",
              phase = "transfer",
              chunkIndex = Some(frame.chunkIndex),
              path = Some("relay")
            )
          )
        )
      )

  def probe(target: FileTransfer): IO[Either[AttachContract.AttachError, ChunkedTransfer.ReceiveState]] =
    client
      // 探针必须与 `put` 同落点（同一 `relayLandingPath`）—— 否则续传 offset 取自**另一个文件**。
      .relayTransferProbe(target.peerDeviceId, DropboxUtil.relayLandingPath(target), timeout = chunkTimeout)
      .map {
        case Right(json) =>
          val hc = json.hcursor
          Right(
            ChunkedTransfer.ReceiveState(
              transferId = target.transferId,
              bytesReceived = hc.downField("bytesReceived").as[Long].getOrElse(0L),
              totalBytes = hc.downField("totalBytes").as[Long].getOrElse(target.totalBytes),
              prefixSha256 = hc.downField("prefixSha256").as[String].getOrElse("")
            )
          )
        case Left(err) =>
          Left(
            AttachContract.AttachError(
              AttachContract.Codes.PeerUnreachable,
              s"Relay probe failed: $err",
              phase = "transfer",
              path = Some("relay")
            )
          )
      }
      .handleErrorWith(e =>
        IO.pure(
          Left(
            AttachContract.AttachError(
              AttachContract.Codes.PeerUnreachable,
              s"Relay probe threw: ${Option(e.getMessage).getOrElse(e.getClass.getSimpleName)}",
              phase = "transfer",
              path = Some("relay")
            )
          )
        )
      )

end RelayChunkTransport

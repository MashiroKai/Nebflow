package nebflow.perception

import cats.effect.IO
import cats.syntax.all.*
import io.circe.Json
import io.circe.syntax.*
import org.http4s.*
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.dsl.io.*

/**
 * 感知面板 API 处理表（统一 daemon 批 2026-10-29 自 PerceptionRoutes 整体迁入，
 * 请求/响应形状逐字节保持——单一实现防双端漂移）。
 *
 * 挂载两侧：
 *  - **daemon 进程**（权威实现，[[PerceptionDaemonMain]]）：token 门 + 真核
 *    （`resolve = PerceptionService.coreNow`）。
 *  - 引擎进程：不再有第二实现——`/api/perception/…`  由 PerceptionRoutes 代理
 *    逐字转发到 daemon（会话门 withAuth 在引擎侧照旧）。
 *
 * `resolve` 为 None（daemon 未 boot / 引擎代理抵达但 daemon 停机窗）⇒ 503
 * `daemon-unavailable`（前端既有错误文案路径；原「首访防御性 ensureBoot」由
 * 引擎 dock 腿的 autoStart + 握手取代）。
 */
object PerceptionPanelRoutes:

  def panel(resolve: IO[Option[PerceptionCore]]): HttpRoutes[IO] =
    HttpRoutes.of[IO] {

      case req @ GET -> Root / "entries" =>
        gated(resolve) { c =>
          c.entriesPayload.flatMap(p =>
            Ok(Json.obj("entries" -> p.entries.asJson, "serverTime" -> p.serverTime.asJson))
          )
        }

      // ── 两问配置：连接 = 测试 + 保存一体 ──────────────────────────────
      case req @ POST -> Root / "mail" / "connect" =>
        gated(resolve) { c =>
          req.as[Json].flatMap { body =>
            val address = str(body, "address")
            val authCode = str(body, "authCode")
            c.connectAccount(
              address = address,
              authCode = authCode,
              imapHost = optStr(body, "imapHost"),
              imapPort = optInt(body, "imapPort"),
              smtpHost = optStr(body, "smtpHost"),
              smtpPort = optInt(body, "smtpPort"),
              smtpImplicitTls = optBool(body, "smtpImplicitTls"),
              displayName = optStr(body, "displayName"),
              trustedSenders = optStrList(body, "trustedSenders")
            ).flatMap {
              case Right((acc, stats)) =>
                Ok(Json.obj(
                  "ok" -> true.asJson,
                  "account" -> Json.obj(
                    "id" -> acc.id.asJson,
                    "address" -> acc.address.asJson,
                    "provider" -> acc.provider.asJson,
                    "enabled" -> acc.enabled.asJson
                  ),
                  // 连接成功态统计（报障 2026-10-28：连接卡显示「已连接：
                  // 收件箱 N 封、M 封未读」；unseen 缺席 = null，前端降级文案）
                  "mailbox" -> Json.obj(
                    "exists" -> stats.exists.asJson,
                    "unseen" -> stats.unseen.asJson
                  )
                ))
              case Left(err) => BadRequest(Json.obj("ok" -> false.asJson, "error" -> err.asJson))
            }
          }
        }

      case req @ POST -> Root / "mail" / "toggle" =>
        gated(resolve) { c =>
          req.as[Json].flatMap { body =>
            c.toggleAccount(str(body, "id"), bool(body, "enabled")).flatMap {
              case Right(_)  => Ok(Json.obj("ok" -> true.asJson))
              case Left(err) => BadRequest(Json.obj("ok" -> false.asJson, "error" -> err.asJson))
            }
          }
        }

      case req @ POST -> Root / "mail" / "remove" =>
        gated(resolve) { c =>
          req.as[Json].flatMap { body =>
            c.removeAccount(str(body, "id")).flatMap {
              case Right(_)  => Ok(Json.obj("ok" -> true.asJson))
              case Left(err) => BadRequest(Json.obj("ok" -> false.asJson, "error" -> err.asJson))
            }
          }
        }

      // ── OTP 窗口（引擎在 ask 发射点开窗为主腿；前端渲染腿同 requestId 复用
      // 同一窗——幂等。响应带槽内命中元数据（无码值）：渲染晚于命中的卡面
      // （console 重开/刷新）也能立即补渲染码片）──
      case req @ POST -> Root / "otp" / "window" / "open" =>
        gated(resolve) { c =>
          req.as[Json].flatMap { body =>
            val requestId = str(body, "requestId")
            val windowSec = optInt(body, "windowSec").getOrElse(PerceptionService.OtpWindowSec)
            if requestId.isBlank then BadRequest(Json.obj("error" -> "requestId required".asJson))
            else c.openOtpWindow(requestId, math.max(30, math.min(windowSec, 3600))).flatMap { w =>
              c.windowHitMeta(w.windowId).flatMap { hit =>
                val fields: Vector[(String, Json)] = Vector(
                  "windowId" -> w.windowId.asJson,
                  "expiresAt" -> w.expiresAt.asJson,
                  "hasHit" -> hit.isDefined.asJson
                ) ++ hit.map(h => Vector(
                  "from" -> h.from.asJson,
                  "fromDomain" -> h.fromDomain.asJson,
                  "receivedAt" -> h.receivedAt.asJson
                )).getOrElse(Vector.empty)
                Ok(Json.fromJsonObject(io.circe.JsonObject.fromIterable(fields)))
              }
            }
          }
        }

      // 单码一次性消费（点击码片 → 取值 → 槽清空）
      case req @ GET -> Root / "otp" / "consume" =>
        gated(resolve) { c =>
          val windowId = req.params.getOrElse("windowId", "")
          c.consumeOtp(windowId).flatMap {
            case Some(hit) =>
              Ok(Json.obj(
                "code" -> hit.code.asJson,
                "from" -> hit.from.asJson,
                "fromDomain" -> hit.fromDomain.asJson,
                "receivedAt" -> hit.receivedAt.asJson
              ))
            case None => NotFound(Json.obj("error" -> "no code".asJson))
          }
        }

      // ── 草稿（画布编辑器数据面）──────────────────────────────────────
      case req @ GET -> Root / "mail" / "drafts" / draftId =>
        gated(resolve) { c =>
          c.draftView(draftId).flatMap {
            case None => NotFound(Json.obj("error" -> "draft not found".asJson))
            case Some(v) =>
              Ok(Json.obj(
                "draftId" -> v.record.draftId.asJson,
                "state" -> v.record.state.asJson,
                "content" -> v.content.asJson,
                "mtimeMs" -> v.mtimeMs.asJson,
                "snapshotAt" -> v.record.snapshotAt.asJson,
                "stale" -> v.stale.asJson,
                "doc" -> (v.doc.toOption match
                  case Some(d) => Json.obj(
                      "to" -> d.to.asJson,
                      "cc" -> d.cc.asJson,
                      "bcc" -> d.bcc.asJson,
                      "subject" -> d.subject.asJson,
                      "bodyHtml" -> d.bodyHtml.asJson,
                      "quoteHtml" -> d.quoteHtml.getOrElse("").asJson,
                      "attachments" -> d.attachments.asJson
                    )
                  case None => Json.Null
                ),
                // 附件在位性 + 大小（快照卡/画布 chip 的标红与禁发判据，r2 §7.3；
                // 旧引擎/降级形态缺字段 = 前端按未知大小降级展示）。
                "attachmentStats" -> v.attachmentStats.asJson
              ))
          }
        }

      case req @ PUT -> Root / "mail" / "drafts" / draftId =>
        gated(resolve) { c =>
          req.as[Json].flatMap { body =>
            c.saveDraft(draftId, str(body, "content")).flatMap {
              case Right(mtime) => Ok(Json.obj("ok" -> true.asJson, "mtimeMs" -> mtime.asJson))
              case Left(err)    => Conflict(Json.obj("ok" -> false.asJson, "error" -> err.asJson))
            }
          }
        }

      case req @ POST -> Root / "mail" / "drafts" / draftId / "abandon" =>
        gated(resolve) { c =>
          c.abandonDraft(draftId).flatMap {
            case Right(_)  => Ok(Json.obj("ok" -> true.asJson))
            case Left(err) => Conflict(Json.obj("ok" -> false.asJson, "error" -> err.asJson))
          }
        }

      // ── 发送（画布「发送」与快照卡「照此发送」同一端点）──────────────
      case req @ POST -> Root / "mail" / "send" =>
        gated(resolve) { c =>
          req.as[Json].flatMap { body =>
            val draftId = str(body, "draftId")
            val snapshot = bool(body, "snapshot")
            c.sendDraft(draftId, snapshot).flatMap {
              case Right(rec) =>
                Ok(Json.obj(
                  "ok" -> true.asJson,
                  "messageId" -> rec.messageId.asJson,
                  "sentAt" -> rec.sentAt.asJson,
                  "to" -> rec.toSummary.asJson,
                  "subject" -> rec.subject.asJson,
                  "attachments" -> rec.attachmentCount.asJson
                ))
              case Left(f) =>
                val (status, code) = f match
                  case PerceptionModel.SendFailure.Stale      => (Status.Conflict, "stale_snapshot")
                  case PerceptionModel.SendFailure.AuthFailed => (Status.Conflict, "auth_failed")
                  case _: PerceptionModel.SendFailure.Invalid => (Status.BadRequest, "invalid")
                  case _                                      => (Status.BadGateway, "send_failed")
                Response[IO](status).withEntity(Json.obj(
                  "ok" -> false.asJson,
                  "code" -> code.asJson,
                  "error" -> PerceptionModel.SendFailure.readable(f).asJson
                )).pure[IO]
            }
          }
        }

    }

  /** None ⇒ 503 daemon-unavailable（面板停机窗统一面）。 */
  private def gated(resolve: IO[Option[PerceptionCore]])(
    f: PerceptionCore => IO[Response[IO]]
  ): IO[Response[IO]] =
    resolve.flatMap {
      case Some(c) => f(c)
      case None =>
        ServiceUnavailable(Json.obj("ok" -> false.asJson, "error" -> "感知守护进程未运行（daemon-unavailable）".asJson))
    }

  private def str(j: Json, k: String): String =
    j.hcursor.downField(k).as[String].getOrElse("")

  private def optStr(j: Json, k: String): Option[String] =
    j.hcursor.downField(k).as[String].toOption.map(_.trim).filter(_.nonEmpty)

  private def optInt(j: Json, k: String): Option[Int] =
    j.hcursor.downField(k).as[Int].toOption

  private def optBool(j: Json, k: String): Option[Boolean] =
    j.hcursor.downField(k).as[Boolean].toOption

  private def optStrList(j: Json, k: String): List[String] =
    j.hcursor.downField(k).as[List[String]].toOption.getOrElse(Nil)

  private def bool(j: Json, k: String): Boolean =
    j.hcursor.downField(k).as[Boolean].getOrElse(false)

end PerceptionPanelRoutes

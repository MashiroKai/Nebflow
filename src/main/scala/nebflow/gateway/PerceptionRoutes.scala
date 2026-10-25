package nebflow.gateway

import cats.effect.IO
import cats.syntax.all.*
import io.circe.Json
import io.circe.syntax.*
import nebflow.perception.{PerceptionCore, PerceptionModel, PerceptionService}
import org.http4s.*
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.dsl.io.*

/**
 * 感知域 REST 面（r2 §13 / r3 修订：面板条目 / 两问配置 / OTP 消费 / 草稿 /
 * 发送）。挂载于 `/api/perception` 前缀，全部走 withAuth 会话门。
 *
 * 服务自举：首访防御性 ensureBoot（GatewayMain 启动链已有正式 boot，此处
 * 幂等 no-op），spec/未启场景安全。
 */
private[gateway] object PerceptionRoutes:

  def routes(ctx: RestApiCtx): HttpRoutes[IO] =
    import ctx.*
    HttpRoutes.of[IO] {

      case req @ GET -> Root / "perception" / "entries" =>
        withAuth(req) {
          core(ctx).flatMap { c =>
            c.entriesPayload.flatMap(p =>
              Ok(Json.obj("entries" -> p.entries.asJson, "serverTime" -> p.serverTime.asJson))
            )
          }
        }

      // ── 两问配置：连接 = 测试 + 保存一体 ──────────────────────────────
      case req @ POST -> Root / "perception" / "mail" / "connect" =>
        withAuth(req) {
          core(ctx).flatMap { c =>
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
                case Right(acc) =>
                  Ok(Json.obj(
                    "ok" -> true.asJson,
                    "account" -> Json.obj(
                      "id" -> acc.id.asJson,
                      "address" -> acc.address.asJson,
                      "provider" -> acc.provider.asJson,
                      "enabled" -> acc.enabled.asJson
                    )
                  ))
                case Left(err) => BadRequest(Json.obj("ok" -> false.asJson, "error" -> err.asJson))
              }
            }
          }
        }

      case req @ POST -> Root / "perception" / "mail" / "toggle" =>
        withAuth(req) {
          core(ctx).flatMap { c =>
            req.as[Json].flatMap { body =>
              c.toggleAccount(str(body, "id"), bool(body, "enabled")).flatMap {
                case Right(_)  => Ok(Json.obj("ok" -> true.asJson))
                case Left(err) => BadRequest(Json.obj("ok" -> false.asJson, "error" -> err.asJson))
              }
            }
          }
        }

      case req @ POST -> Root / "perception" / "mail" / "remove" =>
        withAuth(req) {
          core(ctx).flatMap { c =>
            req.as[Json].flatMap { body =>
              c.removeAccount(str(body, "id")).flatMap {
                case Right(_)  => Ok(Json.obj("ok" -> true.asJson))
                case Left(err) => BadRequest(Json.obj("ok" -> false.asJson, "error" -> err.asJson))
              }
            }
          }
        }

      // 域 → 服务商自动识别（配置卡一句说明的数据源）
      case req @ GET -> Root / "perception" / "mail" / "preset" =>
        withAuth(req) {
          val address = req.params.getOrElse("address", "")
          val preset = nebflow.perception.MailboxPresets.forAddress(address)
          Ok(Json.obj(
            "recognized" -> preset.isDefined.asJson,
            "provider" -> preset.map(_.provider).getOrElse("").asJson,
            "authCodeHint" -> preset.exists(_.authCodeHint).asJson
          ))
        }

      // ── OTP 窗口（前端在 OTP 意图的 secret ask 渲染时打开；过程态零 UI）──
      case req @ POST -> Root / "perception" / "otp" / "window" / "open" =>
        withAuth(req) {
          core(ctx).flatMap { c =>
            req.as[Json].flatMap { body =>
              val requestId = str(body, "requestId")
              val windowSec = optInt(body, "windowSec").getOrElse(PerceptionService.OtpWindowSec)
              if requestId.isBlank then BadRequest(Json.obj("error" -> "requestId required".asJson))
              else c.openOtpWindow(requestId, math.max(30, math.min(windowSec, 3600))).map { w =>
                Json.obj("windowId" -> w.windowId.asJson, "expiresAt" -> w.expiresAt.asJson)
              }.flatMap(Ok(_))
            }
          }
        }

      // 单码一次性消费（点击码片 → 取值 → 槽清空）
      case req @ GET -> Root / "perception" / "otp" / "consume" =>
        withAuth(req) {
          core(ctx).flatMap { c =>
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
        }

      // ── 草稿（画布编辑器数据面）──────────────────────────────────────
      case req @ GET -> Root / "perception" / "mail" / "drafts" / draftId =>
        withAuth(req) {
          core(ctx).flatMap { c =>
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
                  )
                ))
            }
          }
        }

      case req @ PUT -> Root / "perception" / "mail" / "drafts" / draftId =>
        withAuth(req) {
          core(ctx).flatMap { c =>
            req.as[Json].flatMap { body =>
              c.saveDraft(draftId, str(body, "content")).flatMap {
                case Right(mtime) => Ok(Json.obj("ok" -> true.asJson, "mtimeMs" -> mtime.asJson))
                case Left(err)    => Conflict(Json.obj("ok" -> false.asJson, "error" -> err.asJson))
              }
            }
          }
        }

      case req @ POST -> Root / "perception" / "mail" / "drafts" / draftId / "abandon" =>
        withAuth(req) {
          core(ctx).flatMap { c =>
            c.abandonDraft(draftId).flatMap {
              case Right(_)  => Ok(Json.obj("ok" -> true.asJson))
              case Left(err) => Conflict(Json.obj("ok" -> false.asJson, "error" -> err.asJson))
            }
          }
        }

      // ── 发送（画布「发送」与快照卡「照此发送」同一端点）──────────────
      case req @ POST -> Root / "perception" / "mail" / "send" =>
        withAuth(req) {
          core(ctx).flatMap { c =>
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
                    case PerceptionModel.SendFailure.Stale     => (Status.Conflict, "stale_snapshot")
                    case PerceptionModel.SendFailure.AuthFailed => (Status.Conflict, "auth_failed")
                    case _: PerceptionModel.SendFailure.Invalid => (Status.BadRequest, "invalid")
                    case _ => (Status.BadGateway, "send_failed")
                  Response[IO](status).withEntity(Json.obj(
                    "ok" -> false.asJson,
                    "code" -> code.asJson,
                    "error" -> PerceptionModel.SendFailure.readable(f).asJson
                  )).pure[IO]
              }
            }
          }
        }

    }

  private def core(ctx: RestApiCtx): IO[PerceptionCore] =
    PerceptionService.ensureBoot(json => ctx.wsHub.broadcast(json), ctx.sharedResources)

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

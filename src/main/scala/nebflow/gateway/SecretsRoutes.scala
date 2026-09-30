/* 从 RestApiRoutes 迁出(re-anchor 落位,2026-09-28)。 */
package nebflow.gateway

import cats.effect.IO
import cats.syntax.all.*
import io.circe.Json
import io.circe.syntax.*
import nebflow.shared.PathUtil
import org.http4s.*
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.dsl.io.*

/**
 * 密钥域(secrets,secrets-panel backend leg 2026-09-27):统一密钥面板的 4 条 REST
 * 端点(`/secrets` 列表 / POST 写 / PUT 覆盖 / DELETE 删)。本文件是「重锚落位」的
 * 产物 —— 旧支把这段正文写在 `RestApiRoutes.scala` 的巨型 match 内,main 侧 PR48
 * 重构已把该正文搬出为 26 个域文件 + 108 行编排根,故此处按 main 的体例(每域一
 * 文件,导出 `def routes(ctx: RestApiCtx)`;先例 `SocialRoutes` / `ConfigRoutes` /
 * `SessionRoutes`)重建落点,正文逐字保持,经 `RestApiRoutes.routes` 级联挂载。
 * `withAuth` / `checkAuth` 取 `RestApiCtx` 上的单一实现(`import ctx.*`)。
 *
 * 🔴 零值回显:列表只答元数据 + 引用 id,写/删响应不回显载荷,本面刻意**无**读值
 * 端点;受管命名空间(`social-*` / `daemon-*`)可见元数据但写被拒。
 */
private[gateway] object SecretsRoutes:

  def routes(ctx: RestApiCtx): HttpRoutes[IO] =
    import ctx.*

    // ── Secrets panel helpers (secrets-panel backend leg, 2026-09-27) ───────

    /** The secrets store of THIS instance. The directory is the instance's own
      * `secrets/` under the live data root; the reference scans read the same
      * root's `nebflow.json` / `daemons.json`. Constructed per request like the
      * other per-request stores on this face (`PresetStore` shape) so a test
      * that swaps `PathUtil.dataRoot` never sees a stale root. */
    def secretsStore: nebflow.core.secrets.SecretsStore = new nebflow.core.secrets.SecretsStore(
      PathUtil.dataRoot / "secrets"
    )

    /** One consumption pointer as wire JSON. 🔴 `location` is a PATH — the
      * field carries where to query, never what is stored there. */
    def pointerJson(p: nebflow.core.secrets.SecretsStore.Pointer): Json =
      Json.obj(
        "name" -> p.name.asJson,
        "variableName" -> p.variableName.asJson,
        "description" -> p.description.asJson,
        "location" -> p.location.asJson
      )

    /** Parse the metadata body of `PATCH /secrets/{name}`.
      *
      * Contract: the body REPLACES the two non-value catalogue fields — the
      * values it carries are the values the secret ends up with, and a field
      * that is absent or `null` is cleared (the derived variable name / an
      * empty description). A wholesale replace is deliberately preferred over a
      * three-state set/clear/leave merge: a body whose meaning depends on which
      * keys the client happened to omit is exactly the kind of contract that
      * silently half-applies an edit.
      *
      * A body that is not an object is `400 invalid_metadata`; the reason names
      * the SHAPE, never any content. */
    def secretsMetadataBody(req: Request[IO])(
      f: (Option[String], Option[String]) => IO[Response[IO]]
    ): IO[Response[IO]] =
      val shapeReason =
        "request body must be a JSON object carrying 'variableName' and 'description'"
      req.as[Json].attempt.flatMap {
        case Left(_) =>
          BadRequest(Json.obj("error" -> "invalid_metadata".asJson, "reason" -> shapeReason.asJson))
        case Right(body) if !body.isObject =>
          BadRequest(Json.obj("error" -> "invalid_metadata".asJson, "reason" -> shapeReason.asJson))
        case Right(body) =>
          def field(key: String): Option[String] =
            body.hcursor.downField(key).as[String].toOption.filter(_.nonEmpty)
          f(field("variableName"), field("description"))
      }

    /** Parse the `{"value": "…"}` write body. A non-object / non-string body is
      * `400 invalid_value` — the reason names the SHAPE, never the content. */
    def secretsBody(req: Request[IO])(f: String => IO[Response[IO]]): IO[Response[IO]] =
      req.as[Json].attempt.flatMap {
        case Left(_) =>
          BadRequest(Json.obj("error" -> "invalid_value".asJson,
            "reason" -> "request body must be a JSON object with a string 'value'".asJson))
        case Right(body) =>
          body.hcursor.downField("value").as[String].toOption.filter(_.nonEmpty) match
            case Some(value) =>
              // 64 KiB cap (author ruling ⑦) — refused BEFORE the store is
              // consulted, so no byte of an oversized payload ever lands.
              if value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length >
                nebflow.core.secrets.SecretsStore.MaxValueBytes then
                BadRequest(Json.obj("error" -> "invalid_value".asJson,
                  "reason" -> s"value exceeds the ${nebflow.core.secrets.SecretsStore.MaxValueBytes}-byte limit"
                    .asJson))
              else f(value)
            case None =>
              BadRequest(Json.obj("error" -> "invalid_value".asJson,
                "reason" -> "body must carry a non-empty string 'value'".asJson))
      }

    /** Error-code mapping for the secrets endpoints (plan card 问2; the family
      * mirrors the social face above). Bodies carry the NAME (+ referrer ids /
      * reason) and the code — 🔴 never a value. */
    def secretsErrorResponse(err: nebflow.core.secrets.SecretsStore.Failure): IO[Response[IO]] =
      import nebflow.core.secrets.SecretsStore.Failure
      err match
        case Failure.InvalidName(name, reason) =>
          BadRequest(Json.obj("error" -> "invalid_name".asJson, "name" -> name.asJson,
            "reason" -> reason.asJson))
        case Failure.InvalidValue(name, reason) =>
          BadRequest(Json.obj("error" -> "invalid_value".asJson, "name" -> name.asJson,
            "reason" -> reason.asJson))
        case Failure.InvalidVariableName(name, variableName) =>
          BadRequest(Json.obj("error" -> "invalid_variable_name".asJson, "name" -> name.asJson,
            "variableName" -> variableName.asJson,
            "reason" -> "must match ^[A-Za-z_][A-Za-z0-9_]*$ (a shell identifier)".asJson))
        case Failure.UnknownSecret(name) =>
          NotFound(Json.obj("error" -> "unknown_secret".asJson, "name" -> name.asJson))
        case Failure.AlreadyExists(name) =>
          Conflict(Json.obj("error" -> "already_exists".asJson, "name" -> name.asJson))
        case Failure.Referenced(name, refs) =>
          Conflict(Json.obj("error" -> "referenced".asJson, "name" -> name.asJson,
            "refs" -> refs.asJson))
        case Failure.ManagedNamespace(name) =>
          Forbidden(Json.obj("error" -> "managed_namespace".asJson, "name" -> name.asJson,
            "reason" -> "managed by its own panel (social-* -> channel cards, daemon-* -> daemon panel)"
              .asJson))
        case Failure.SecretMode(name, reason) =>
          Forbidden(Json.obj("error" -> "secret_mode".asJson, "name" -> name.asJson,
            "reason" -> reason.asJson))
        case Failure.Io(reason) =>
          InternalServerError(Json.obj("error" -> "io".asJson, "reason" -> reason.asJson))

    // ── Unified secrets panel (secrets-panel backend leg, 2026-09-27) ─────
    // One management face over the EXISTING `~/.nebflow/secrets/` namespace —
    // same directory the social cards and daemon panels write, zero storage
    // change. 🔴 No response ever carries a secret value: the list answers
    // metadata + referrer ids only, write/delete answers never echo the
    // payload, and there is deliberately NO read-value endpoint on this face.
    // Managed namespaces (`social-*`, `daemon-*`) are VISIBLE here (metadata +
    // refs) but every write is refused — their own panels own the lifecycle.
    // All four endpoints sit behind the shared auth gate (withAuth); failure
    // to authenticate is indistinguishable 403 {"error":"Unauthorized"}.
    HttpRoutes.of[IO] {
      case req @ GET -> Root / "secrets" =>
        withAuth(req) {
          secretsStore.list().flatMap {
            case Right(entries) =>
              Ok(Json.obj("secrets" -> entries.map { e =>
                Json.obj(
                  "name" -> e.name.asJson,
                  "size" -> e.size.asJson,
                  "mtime" -> e.mtime.asJson,
                  "mode" -> e.mode.asJson,
                  "refs" -> e.refs.asJson,
                  // Catalogue fields (secrets-consumption batch): ADDITIVE —
                  // an existing panel ignores unknown keys, and neither field
                  // is derived from a stored value.
                  "variableName" -> e.variableName.asJson,
                  "description" -> e.description.asJson
                )
              }.asJson))
            case Left(err) => secretsErrorResponse(err)
          }
        }

      // Consumption face (secrets-consumption batch, 2026-10-01). 🔴 Pointer
      // semantics: this answers "where do I query this credential", by PATH.
      // There is deliberately still NO endpoint that returns a stored value —
      // the whole point of the C-case model is that a consumer is told the
      // location, not handed the secret.
      case req @ GET -> Root / "secrets" / "catalog" =>
        withAuth(req) {
          secretsStore.pointers().flatMap {
            case Right(ps) => Ok(Json.obj("secrets" -> ps.map(pointerJson).asJson))
            case Left(err) => secretsErrorResponse(err)
          }
        }

      case req @ GET -> Root / "secrets" / secretName / "pointer" =>
        withAuth(req) {
          secretsStore.pointer(secretName).flatMap {
            case Right(p)  => Ok(pointerJson(p))
            case Left(err) => secretsErrorResponse(err)
          }
        }

      // Metadata edit: the two non-value catalogue fields. The value reaches
      // this face nowhere — the body schema has no value field, and the store
      // call it lands on writes the sidecar only.
      case req @ PATCH -> Root / "secrets" / secretName =>
        withAuth(req) {
          secretsMetadataBody(req) { (variableName, description) =>
            secretsStore.setMetadata(secretName, variableName, description).flatMap {
              case Right(_) =>
                Ok(Json.obj("ok" -> true.asJson, "name" -> secretName.asJson))
              case Left(err) => secretsErrorResponse(err)
            }
          }
        }

      case req @ POST -> Root / "secrets" / secretName =>
        withAuth(req) {
          secretsBody(req) { value =>
            secretsStore.create(secretName, value).flatMap {
              case Right(_)  => Ok(Json.obj("ok" -> true.asJson, "name" -> secretName.asJson))
              case Left(err) => secretsErrorResponse(err)
            }
          }
        }

      case req @ PUT -> Root / "secrets" / secretName =>
        withAuth(req) {
          secretsBody(req) { value =>
            secretsStore.overwrite(secretName, value).flatMap {
              case Right(_)  => Ok(Json.obj("ok" -> true.asJson, "name" -> secretName.asJson))
              case Left(err) => secretsErrorResponse(err)
            }
          }
        }

      case req @ DELETE -> Root / "secrets" / secretName =>
        withAuth(req) {
          val confirm = req.params.getOrElse("confirm", "")
          if confirm != secretName then
            BadRequest(Json.obj("error" -> "confirm_mismatch".asJson, "name" -> secretName.asJson))
          else
            secretsStore.referencedBy(secretName).flatMap { refs =>
              secretsStore.delete(secretName, refs).flatMap {
                case Right(_)  => Ok(Json.obj("ok" -> true.asJson, "name" -> secretName.asJson))
                case Left(err) => secretsErrorResponse(err)
              }
            }
        }
    }

end SecretsRoutes

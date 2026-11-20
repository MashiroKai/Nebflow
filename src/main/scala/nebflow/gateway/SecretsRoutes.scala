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
 * 值回显纪律(2026-10-09 读回裁定修订):列表/目录/指针只答元数据,写/删响应不
 * 回显载荷;唯一的读值口 = `GET /secrets/{name}/value`——**鉴权**、单条、显式打开
 * 才调,响应恰为 `{name, value}`,日志只记名不记值;其余端点维持零值。
 * 受管命名空间(`social-*` / `daemon-*`)可见元数据也可读值,但写/删仍被拒。
 */
private[gateway] object SecretsRoutes:

  def routes(ctx: RestApiCtx): HttpRoutes[IO] =
    import ctx.*

    // ── Secrets panel helpers (secrets-panel backend leg, 2026-09-27) ───────

    /**
     * The secrets store of THIS instance. The directory is the instance's own
     * `secrets/` under the live data root; the reference scans read the same
     * root's `nebflow.json` / `daemons.json`. Constructed per request like the
     * other per-request stores on this face (`PresetStore` shape) so a test
     * that swaps `PathUtil.dataRoot` never sees a stale root.
     */
    def secretsStore: nebflow.core.secrets.SecretsStore = new nebflow.core.secrets.SecretsStore(
      PathUtil.dataRoot / "secrets"
    )

    /**
     * One consumption pointer as wire JSON. 🔴 `location` is a PATH — the
     * field carries where to query, never what is stored there.
     */
    def pointerJson(p: nebflow.core.secrets.SecretsStore.Pointer): Json =
      Json.obj(
        "name" -> p.name.asJson,
        "variableName" -> p.variableName.asJson,
        "description" -> p.description.asJson,
        "location" -> p.location.asJson
      )

    /**
     * Parse the metadata body of `PATCH /secrets/{name}`.
     *
     * Contract: the body REPLACES the three non-value catalogue fields — the
     * values it carries are the values the secret ends up with, and a field
     * that is absent or `null` is cleared (the derived variable name / an
     * empty description / no tags). A wholesale replace is deliberately
     * preferred over a three-state set/clear/leave merge: a body whose meaning
     * depends on which keys the client happened to omit is exactly the kind of
     * contract that silently half-applies an edit.
     *
     * `tags`（可查询批 2026-11-18）accepts a JSON array of strings (canonical)
     * or one comma/space separated string (the CLI and the panel's tag input
     * both speak that form). Either way the list lands on
     * `SecretsStore.setMetadata`, which folds every token through
     * `SecretsCatalog.normalizeTags` — the route never normalizes a second
     * time.
     *
     * A body that is not an object is `400 invalid_metadata`; the reason names
     * the SHAPE, never any content.
     */
    def secretsMetadataBody(req: Request[IO])(
      f: (Option[String], Option[String], List[String]) => IO[Response[IO]]
    ): IO[Response[IO]] =
      val shapeReason =
        "request body must be a JSON object carrying 'variableName', 'description' and 'tags'"
      req.as[Json].attempt.flatMap {
        case Left(_) =>
          BadRequest(Json.obj("error" -> "invalid_metadata".asJson, "reason" -> shapeReason.asJson))
        case Right(body) if !body.isObject =>
          BadRequest(Json.obj("error" -> "invalid_metadata".asJson, "reason" -> shapeReason.asJson))
        case Right(body) =>
          def field(key: String): Option[String] =
            body.hcursor.downField(key).as[String].toOption.filter(_.nonEmpty)
          def tagsField(): List[String] =
            val focus = body.hcursor.downField("tags").focus
            focus match
              case Some(j) if j.isArray =>
                nebflow.core.secrets.SecretsCatalog.normalizeTags(
                  j.asArray.getOrElse(Vector.empty).flatMap(_.asString)
                )
              case Some(j) if j.isString =>
                nebflow.core.secrets.SecretsCatalog.parseTags(j.asString.getOrElse(""))
              case _ => Nil
          f(field("variableName"), field("description"), tagsField())
      }
    end secretsMetadataBody

    /**
     * Parse the `{"value": "…"}` write body. A non-object / non-string body is
     * `400 invalid_value` — the reason names the SHAPE, never the content.
     */
    def secretsBody(req: Request[IO])(f: String => IO[Response[IO]]): IO[Response[IO]] =
      req.as[Json].attempt.flatMap {
        case Left(_) =>
          BadRequest(
            Json.obj(
              "error" -> "invalid_value".asJson,
              "reason" -> "request body must be a JSON object with a string 'value'".asJson
            )
          )
        case Right(body) =>
          body.hcursor.downField("value").as[String].toOption.filter(_.nonEmpty) match
            case Some(value) =>
              // 64 KiB cap (author ruling ⑦) — refused BEFORE the store is
              // consulted, so no byte of an oversized payload ever lands.
              if value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length >
                  nebflow.core.secrets.SecretsStore.MaxValueBytes
              then
                BadRequest(
                  Json.obj(
                    "error" -> "invalid_value".asJson,
                    "reason" -> s"value exceeds the ${nebflow.core.secrets.SecretsStore.MaxValueBytes}-byte limit".asJson
                  )
                )
              else f(value)
            case None =>
              BadRequest(
                Json.obj(
                  "error" -> "invalid_value".asJson,
                  "reason" -> "body must carry a non-empty string 'value'".asJson
                )
              )
      }

    /**
     * Parse the CREATE body: the `{"value": "…"}` shape of [[secretsBody]]
     * PLUS two OPTIONAL metadata fields（可查询批，作者令 2026-11-18 21:31
     * 「新建 secret 时可带说明与标签」）— `description` (string) and `tags`
     * (array of strings, or one comma separated string).
     *
     * Why create carries metadata but PUT does not: `PUT /secrets/{name}` is
     * the panel's value-write path and sends `{value}` only — if it accepted
     * metadata with whole-replace semantics, every value save would silently
     * wipe the description and tags. So the create leg takes the two fields
     * as an initial value (absent ⇒ none set), the value leg touches the
     * value alone, and the metadata leg (PATCH) is the only whole-replace
     * face. Three verbs, three honest contracts.
     */
    def secretsCreateBody(req: Request[IO])(
      f: (String, Option[String], List[String]) => IO[Response[IO]]
    ): IO[Response[IO]] =
      req.as[Json].attempt.flatMap {
        case Left(_) =>
          BadRequest(
            Json.obj(
              "error" -> "invalid_value".asJson,
              "reason" -> "request body must be a JSON object with a string 'value'".asJson
            )
          )
        case Right(body) =>
          body.hcursor.downField("value").as[String].toOption.filter(_.nonEmpty) match
            case Some(value) =>
              if value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length >
                  nebflow.core.secrets.SecretsStore.MaxValueBytes
              then
                BadRequest(
                  Json.obj(
                    "error" -> "invalid_value".asJson,
                    "reason" -> s"value exceeds the ${nebflow.core.secrets.SecretsStore.MaxValueBytes}-byte limit".asJson
                  )
                )
              else
                val description = body.hcursor.downField("description").as[String].toOption.filter(_.nonEmpty)
                val tags = body.hcursor.downField("tags").focus match
                  case Some(j) if j.isArray =>
                    nebflow.core.secrets.SecretsCatalog.normalizeTags(
                      j.asArray.getOrElse(Vector.empty).flatMap(_.asString)
                    )
                  case Some(j) if j.isString =>
                    nebflow.core.secrets.SecretsCatalog.parseTags(j.asString.getOrElse(""))
                  case _ => Nil
                f(value, description, tags)
            case None =>
              BadRequest(
                Json.obj(
                  "error" -> "invalid_value".asJson,
                  "reason" -> "body must carry a non-empty string 'value'".asJson
                )
              )
      }

    /**
     * Error-code mapping for the secrets endpoints (plan card 问2; the family
     * mirrors the social face above). Bodies carry the NAME (+ referrer ids /
     * reason) and the code — 🔴 never a value.
     */
    def secretsErrorResponse(err: nebflow.core.secrets.SecretsStore.Failure): IO[Response[IO]] =
      import nebflow.core.secrets.SecretsStore.Failure
      err match
        case Failure.InvalidName(name, reason) =>
          BadRequest(Json.obj("error" -> "invalid_name".asJson, "name" -> name.asJson, "reason" -> reason.asJson))
        case Failure.InvalidValue(name, reason) =>
          BadRequest(Json.obj("error" -> "invalid_value".asJson, "name" -> name.asJson, "reason" -> reason.asJson))
        case Failure.InvalidVariableName(name, variableName) =>
          BadRequest(
            Json.obj(
              "error" -> "invalid_variable_name".asJson,
              "name" -> name.asJson,
              "variableName" -> variableName.asJson,
              "reason" -> "must match ^[A-Za-z_][A-Za-z0-9_]*$ (a shell identifier)".asJson
            )
          )
        case Failure.UnknownSecret(name) =>
          NotFound(Json.obj("error" -> "unknown_secret".asJson, "name" -> name.asJson))
        case Failure.AlreadyExists(name) =>
          Conflict(Json.obj("error" -> "already_exists".asJson, "name" -> name.asJson))
        case Failure.Referenced(name, refs) =>
          Conflict(Json.obj("error" -> "referenced".asJson, "name" -> name.asJson, "refs" -> refs.asJson))
        case Failure.ManagedNamespace(name) =>
          Forbidden(
            Json.obj(
              "error" -> "managed_namespace".asJson,
              "name" -> name.asJson,
              "reason" -> "managed by its own panel (social-* -> channel cards, daemon-* -> daemon panel)".asJson
            )
          )
        case Failure.SecretMode(name, reason) =>
          Forbidden(Json.obj("error" -> "secret_mode".asJson, "name" -> name.asJson, "reason" -> reason.asJson))
        case Failure.Io(reason) =>
          InternalServerError(Json.obj("error" -> "io".asJson, "reason" -> reason.asJson))
      end match
    end secretsErrorResponse

    /**
     * One-line diagnostic for the readback helper, same protocol as
     * `SecretsStore.describe`: exception CLASS + name + path, never a message
     * body (a message could echo content).
     */
    def secretsDescribe(e: Throwable, name: String): String =
      s"${e.getClass.getSimpleName} (name=$name path=${secretsStore.secretsDir})"

    def secretsLogger: nebflow.shared.NebflowLogger =
      nebflow.shared.NebflowLogger.forName("nebflow.gateway.secrets")

    /**
     * Read ONE stored value back (secrets-readback batch, 2026-10-09). The
     * single value-returning helper on this face, used only by the explicit
     * single-name route below. Reads the file the way the consumption face
     * does (`SecretInjection.materialize`): raw bytes, UTF-8, ONE trailing
     * CRLF/LF stripped — so what the editor prefills is byte-identical to
     * what a consumer materializes.
     *
     * Refusals, in order: a missing target is `404 unknown_secret`; a stored
     * file LARGER than the write face's own 64 KiB cap is `400 invalid_value`
     * (the write path could never have produced it — fail closed rather than
     * hand out an unbounded payload). `SecretsStore.scala` stays untouched:
     * the store's zero-read-back rule is intact; this face borrows only its
     * public `secretsDir` / `validateName` / failure vocabulary. Managed
     * namespaces stay READ-visible (the list shows them; only their lifecycle
     * writes are owned elsewhere).
     */
    def readSecretValue(
      name: String
    ): IO[Either[nebflow.core.secrets.SecretsStore.Failure, String]] =
      import nebflow.core.secrets.SecretsStore.{Failure, MaxValueBytes}
      val dir = secretsStore.secretsDir
      IO.blocking {
        val target = dir / name
        if !java.nio.file.Files.isRegularFile(target.toNIO) then Left(Failure.UnknownSecret(name))
        else if java.nio.file.Files.size(target.toNIO) > MaxValueBytes then
          Left(Failure.InvalidValue(name, s"stored file exceeds the $MaxValueBytes-byte cap"))
        else
          val raw = new String(
            java.nio.file.Files.readAllBytes(target.toNIO),
            java.nio.charset.StandardCharsets.UTF_8
          )
          val value =
            if raw.endsWith("\r\n") then raw.dropRight(2)
            else if raw.endsWith("\n") then raw.dropRight(1)
            else raw
          Right(value)
      }.handleErrorWith(e => IO.pure(Left(Failure.Io(secretsDescribe(e, name)))))
    end readSecretValue

    // ── Unified secrets panel (secrets-panel backend leg, 2026-09-27) ─────
    // One management face over the EXISTING `~/.nebflow/secrets/` namespace —
    // same directory the social cards and daemon panels write, zero storage
    // change. 🔴 Value exposure: the list / catalog / pointer arms answer
    // metadata (+ referrer ids) only, write/delete answers never echo the
    // payload, and the ONE read-value endpoint (GET /secrets/{name}/value,
    // secrets-readback batch 2026-10-09) is authenticated, single-name and
    // explicit-open only — its response is exactly {name, value} and its log
    // line names the secret, never the value. Managed namespaces (`social-*`,
    // `daemon-*`) are VISIBLE here (metadata + readback) but every lifecycle
    // write is refused — their own panels own it. All endpoints sit behind
    // the shared auth gate (withAuth); failure to authenticate is
    // indistinguishable 403 {"error":"Unauthorized"}.
    HttpRoutes.of[IO] {
      case req @ GET -> Root / "secrets" =>
        withAuth(req) {
          // 可查询批（2026-11-18）：`?q=<keyword>` + `?tag=<t>`（可重复）narrow
          // the SAME metadata list through the SAME matcher the CLI and the
          // panel use (`SecretsQuery`) — one predicate, three faces. Both
          // params are optional and additive: a request without them behaves
          // exactly as before (the response shape is unchanged).
          val terms = nebflow.core.secrets.SecretsQuery.of(
            req.params.get("q").orElse(req.params.get("keyword")),
            req.multiParams.getOrElse("tag", Nil)
          )
          secretsStore.list().flatMap {
            case Right(entries) =>
              val rows = nebflow.core.secrets.SecretsQuery.filter(entries, terms)
              Ok(Json.obj("secrets" -> rows.map { e =>
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
                  "description" -> e.description.asJson,
                  // 可查询批：the keyword facet the panel's chips and search
                  // box filter on. A list of authored labels — never a value,
                  // never a value fragment.
                  "tags" -> e.tags.asJson,
                  // 可查询批：the consumption PATH (same renderer the CLI and
                  // the pointer face use) so the panel can show where a
                  // credential is queried from without guessing the data
                  // root. A path — never what is stored at it.
                  "path" -> nebflow.core.secrets.SecretsCatalog
                    .renderedLocation(PathUtil.dataRoot, secretsStore.secretsDir, e.name)
                    .asJson
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
            case Right(p) => Ok(pointerJson(p))
            case Left(err) => secretsErrorResponse(err)
          }
        }

      // Single-secret value readback (secrets-readback batch, 2026-10-09,
      // author ruling 07:03: the panel editor opens with the STORED value
      // prefilled). The ONLY value-returning arm on this face:
      //   · behind withAuth, exactly like every arm above;
      //   · ONE name per call — issued by the client only when the editor
      //     explicitly opens on that name; the list / catalog / pointer arms
      //     above stay value-free (their specs pin it, and the readback spec
      //     re-asserts list+catalog value-freedom in the same run);
      //   · the response carries EXACTLY {name, value} — no refs, no
      //     metadata sidecar, no second channel;
      //   · the log line records WHICH name was read, never the value — the
      //     value's single appearance is the response body.
      case req @ GET -> Root / "secrets" / secretName / "value" =>
        withAuth(req) {
          secretsStore.validateName(secretName) match
            case Left(err) => secretsErrorResponse(err)
            case Right(name) =>
              readSecretValue(name).flatMap {
                case Right(value) =>
                  // Name only in the log — the value never enters it.
                  secretsLogger.info(s"Secret value read: $name").flatMap { _ =>
                    Ok(Json.obj("name" -> name.asJson, "value" -> value.asJson))
                      .map(_.putHeaders(org.http4s.Header.Raw(org.typelevel.ci.CIString("Cache-Control"), "no-store")))
                  }
                case Left(err) => secretsErrorResponse(err)
              }
        }

      // Metadata edit: the three non-value catalogue fields (variableName,
      // description, tags). The value reaches this face nowhere — the body
      // schema has no value field, and the store call it lands on writes the
      // sidecar only.
      case req @ PATCH -> Root / "secrets" / secretName =>
        withAuth(req) {
          secretsMetadataBody(req) { (variableName, description, tags) =>
            secretsStore.setMetadata(secretName, variableName, description, tags).flatMap {
              case Right(_) =>
                Ok(Json.obj("ok" -> true.asJson, "name" -> secretName.asJson))
              case Left(err) => secretsErrorResponse(err)
            }
          }
        }

      // 可查询批（2026-11-18）：one secret's METADATA record — the REST twin of
      // `stand secrets show`. Value-free like every read arm above; a name
      // with only a sidecar entry (managed `.askuser/` namespace) answers
      // `stored: false` instead of 404.
      case req @ GET -> Root / "secrets" / secretName / "meta" =>
        withAuth(req) {
          secretsStore.show(secretName).flatMap {
            case Right(e) =>
              Ok(
                Json.obj(
                  "name" -> e.name.asJson,
                  "variableName" -> e.variableName.asJson,
                  "description" -> e.description.asJson,
                  "tags" -> e.tags.asJson,
                  "size" -> e.size.asJson,
                  "mtime" -> e.mtime.asJson,
                  "mode" -> e.mode.asJson,
                  "refs" -> e.refs.asJson,
                  "location" -> nebflow.core.secrets.SecretsCatalog
                    .renderedLocation(PathUtil.dataRoot, secretsStore.secretsDir, e.name)
                    .asJson,
                  "stored" -> e.stored.asJson
                )
              )
            case Left(err) => secretsErrorResponse(err)
          }
        }

      case req @ POST -> Root / "secrets" / secretName =>
        withAuth(req) {
          secretsCreateBody(req) { (value, description, tags) =>
            secretsStore.create(secretName, value).flatMap {
              case Left(err) => secretsErrorResponse(err)
              case Right(_) if description.isEmpty && tags.isEmpty =>
                Ok(Json.obj("ok" -> true.asJson, "name" -> secretName.asJson))
              case Right(_) =>
                // Create-with-metadata（新建时可带说明与标签）: the value is
                // already stored; the two catalogue fields land on the
                // sidecar through the same store call the panel's PATCH uses.
                // A metadata failure is reported (never swallowed): the
                // caller asked for both, and claiming only half applied is
                // the worse lie.
                secretsStore.setMetadata(secretName, None, description, tags).flatMap {
                  case Right(_) => Ok(Json.obj("ok" -> true.asJson, "name" -> secretName.asJson))
                  case Left(err) => secretsErrorResponse(err)
                }
            }
          }
        }

      case req @ PUT -> Root / "secrets" / secretName =>
        withAuth(req) {
          secretsBody(req) { value =>
            secretsStore.overwrite(secretName, value).flatMap {
              case Right(_) => Ok(Json.obj("ok" -> true.asJson, "name" -> secretName.asJson))
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
                case Right(_) => Ok(Json.obj("ok" -> true.asJson, "name" -> secretName.asJson))
                case Left(err) => secretsErrorResponse(err)
              }
            }
        }
    }
  end routes

end SecretsRoutes

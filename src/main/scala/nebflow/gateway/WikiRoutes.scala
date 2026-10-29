/* Wiki graph domain (docs vaults across projects, local + NebLink peers). */
package nebflow.gateway

import cats.effect.IO
import io.circe.Json
import io.circe.syntax.*
import nebflow.core.wiki.WikiService
import org.http4s.*
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.dsl.io.*

/**
 * `GET /api/wiki/graph`                — merged document graph over every project's
 *                                        `<workspace>/docs` ROOT vault (pre-#744-B
 *                                        shape, byte-compatible). Projects bound to
 *                                        peer devices are scanned on the peer via the
 *                                        scoped browse channel and degrade to stub
 *                                        nodes when unreachable.
 * `GET /api/wiki/graph?project=X`      — project X's isolated vault graph (#744-B),
 *                                        with `childVaults` aggregates for sub
 *                                        projects (`SUB/docs` dirs inside the vault).
 * `GET /api/wiki/graph?project=X&vault=sub/docs` — drill-in to a sub vault
 *                                        (breadcrumb back to the root in `view`).
 * `GET /api/wiki/graph?project=__user__` — C-batch reserved (user history graph);
 *                                        returns an empty placeholder view.
 * `GET /api/wiki/vaults`               — lightweight manifest (project → vault
 *                                        path + local presence) for the panel
 *                                        project switcher; no content scan.
 * `GET /api/wiki/paragraph`            — paragraph snapshot for one anchored reference
 *                                        (`project` + `rel` + `anchor`; 段落级引用批):
 *                                        paragraph index → best-effort stand-recall →
 *                                        doc-level fallback (the xerj integration seam;
 *                                        the graph itself never depends on memory-db).
 * `GET /api/wiki/term`                 — term registry lookup (`name` required,
 *                                        `project` optional; 词条引用批): glossary/
 *                                        doc-intro term entry + definition + location
 *                                        anchor. Unique hit → `{found, resolved, term}`;
 *                                        multiple same-tier hits → `{resolved:false,
 *                                        ambiguous, candidates}`; unregistered → 404.
 *                                        Serves from the graph TTL cache (30s).
 * `GET /api/wiki/remote-doc`           — single-file fetch from a peer device
 *                                        (`device` + `path` query params) for opening
 *                                        remote docs read-only.
 * `POST /api/wiki/vault`               — idempotently scaffold `<workspace>/docs/attachments`
 *                                        for a local project (`{"project": name}`).
 *
 * All routes need auth (withAuth). Mounted via RestApiRoutes.routes; the first
 * path segment ("wiki") is disjoint from every sibling domain.
 */
private[gateway] object WikiRoutes:

  def routes(ctx: RestApiCtx): HttpRoutes[IO] =
    import ctx.*
    val service = WikiService.current(neblinkService)
    HttpRoutes.of[IO] {
      case req @ GET -> Root / "wiki" / "graph" =>
        withAuth(req) {
          val force = req.params.get("force").exists(v => v == "1" || v.equalsIgnoreCase("true"))
          val project = req.params.get("project").map(_.trim).filter(_.nonEmpty)
          val vault = req.params.get("vault").map(_.trim).filter(_.nonEmpty)
          service.graph(project, vault, force).flatMap(Ok(_))
        }

      case req @ GET -> Root / "wiki" / "vaults" =>
        withAuth(req) {
          service.vaults.flatMap(Ok(_))
        }

      case req @ GET -> Root / "wiki" / "paragraph" =>
        withAuth(req) {
          val project = req.params.getOrElse("project", "")
          val rel = req.params.getOrElse("rel", "")
          val anchor = req.params.getOrElse("anchor", "")
          if project.isBlank || rel.isBlank || anchor.isBlank then
            BadRequest(Json.obj("error" -> "project, rel and anchor are required".asJson))
          else
            service.paragraph(project, rel, anchor).flatMap {
              case Left(err) => NotFound(Json.obj("error" -> err.asJson))
              case Right(json) => Ok(json)
            }
        }

      case req @ GET -> Root / "wiki" / "term" =>
        withAuth(req) {
          val name = req.params.getOrElse("name", "")
          val project = req.params.get("project").map(_.trim).filter(_.nonEmpty)
          // md/html 类型硬约束闸（第三批）：`doc` = 宿主文档（可选）。带上即按
          // MdHtmlGate 单点闸，非四扩展名 → 400 + 明确原因（不静默、不 404）。
          val doc = req.params.get("doc").map(_.trim).filter(_.nonEmpty)
          if name.isBlank then BadRequest(Json.obj("error" -> "name is required".asJson))
          else
            service.term(name, project, doc).flatMap {
              case Left(err) if err.startsWith(nebflow.core.wiki.MdHtmlGate.RejectPrefix) =>
                BadRequest(Json.obj(
                  "error" -> err.asJson,
                  "reason" -> "not-md-html".asJson,
                  "doc" -> doc.getOrElse("").asJson
                ))
              case Left(err) => NotFound(Json.obj("error" -> err.asJson))
              case Right(json) => Ok(json)
            }
        }

      case req @ GET -> Root / "wiki" / "remote-doc" =>
        withAuth(req) {
          val device = req.params.getOrElse("device", "")
          val path = req.params.getOrElse("path", "")
          if device.isBlank then BadRequest(Json.obj("error" -> "device is required".asJson))
          else if path.isBlank then BadRequest(Json.obj("error" -> "path is required".asJson))
          else
            service.remoteDoc(device, path).flatMap {
              case Left(err) => NotFound(Json.obj("error" -> err.asJson))
              case Right(json) => Ok(json)
            }
        }

      case req @ POST -> Root / "wiki" / "vault" =>
        withAuth(req) {
          req.as[Json].flatMap { body =>
            val project = body.hcursor.downField("project").as[String].getOrElse("")
            if project.isBlank then BadRequest(Json.obj("error" -> "project is required".asJson))
            else
              service.ensureVault(project).flatMap {
                case Left(err) => BadRequest(Json.obj("error" -> err.asJson))
                case Right(json) => Ok(json)
              }
          }
        }
    }
  end routes
end WikiRoutes

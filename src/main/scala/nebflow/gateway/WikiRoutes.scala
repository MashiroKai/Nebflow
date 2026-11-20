/* Wiki graph domain (docs vaults across projects, local + NebLink peers). */
package nebflow.gateway

import cats.effect.IO
import io.circe.Json
import io.circe.syntax.*
import nebflow.core.wiki.{TermDb, WikiService}
import org.http4s.*
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.dsl.io.*

/**
 * `GET /api/wiki/graph`                — merged document graph over every project's
 *                                        ROOT vault (resolved by
 *                                        `ProjectStore.localVault` =
 *                                        `projects/<name>/.stand/docs`, falling back
 *                                        to the workspace `docs/` for not-yet-migrated
 *                                        projects; pre-#744-B shape, byte-compatible).
 *                                        Projects bound to
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
 * `GET /api/wiki/terms`                — 词条索引库检索（term-system v2 · ②）：
 *                                        `q`（子串，命中主名或别名）/ `project`
 *                                        （`#proj/<slug>`）/ `topic`
 *                                        （`#topic/<slug>`）/ `doc`（vault 相对路径
 *                                        或 basename）/ `limit`（默认 50、上限 500）。
 *                                        每条结果带**原文档信息**（project/rel/title/
 *                                        doc_type/status/abs_path）+ 三桶 tags +
 *                                        ref_count + updated_at。库缺失 / schema 不认识
 *                                        / 读失败 ⇒ 200 空列表 + `available:false`
 *                                        （降级，绝不 500；详情见 `TermDb`）。
 * `GET /api/wiki/terms/lookup`         — 按名点查库（`name` required）：主名优先、
 *                                        退到别名，返回全部命中 + 每条的引用位置
 *                                        `refs[]`；库可用但零命中 ⇒ 404。
 * `GET /api/wiki/terms/stats`          — 库的汇总面（文档/词条/别名/标签/引用计数）。
 * `GET /api/wiki/remote-doc`           — single-file fetch from a peer device
 *                                        (`device` + `path` query params) for opening
 *                                        remote docs read-only.
 * `POST /api/wiki/vault`               — idempotently scaffold the vault
 *                                        (`projects/<name>/.stand/docs`) and its
 *                                        `attachments/` for a local project
 *                                        (`{"project": name}`).
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
                BadRequest(
                  Json.obj(
                    "error" -> err.asJson,
                    "reason" -> "not-md-html".asJson,
                    "doc" -> doc.getOrElse("").asJson
                  )
                )
              case Left(err) => NotFound(Json.obj("error" -> err.asJson))
              case Right(json) => Ok(json)
            }
        }

      // ── 词条索引库读侧（term-system v2 · ②）──────────────────────────
      // 与上面的 `/wiki/term` 分工：那条走「文档即事实源」的注册表解析链（带
      // 消解裁定），这条走 CLI 建出来的**派生物**库（按名/标签/文档检索 + 原文档
      // 信息）。库是背景面：开不了就降级成空列表，不让检索接口变红。
      case req @ GET -> Root / "wiki" / "terms" / "lookup" =>
        withAuth(req) {
          val name = req.params.getOrElse("name", "")
          if name.isBlank then BadRequest(Json.obj("error" -> "name is required".asJson))
          else
            val db = TermDb.current
            db.termByName(name).flatMap {
              case Nil =>
                // 降级与「确实没有」必须分得开：降级时库根本没打开，不能报 404。
                db.stats.flatMap {
                  case None =>
                    Ok(
                      Json.obj(
                        "available" -> false.asJson,
                        "db" -> db.dbPath.toString.asJson,
                        "name" -> name.asJson,
                        "count" -> 0.asJson,
                        "results" -> Json.arr()
                      )
                    )
                  case Some(_) =>
                    NotFound(
                      Json.obj(
                        "available" -> true.asJson,
                        "db" -> db.dbPath.toString.asJson,
                        "name" -> name.asJson,
                        "count" -> 0.asJson,
                        "results" -> Json.arr(),
                        "error" -> s"term '$name' not found in term index".asJson
                      )
                    )
                }
              case hits =>
                Ok(
                  Json.obj(
                    "available" -> true.asJson,
                    "db" -> db.dbPath.toString.asJson,
                    "name" -> name.asJson,
                    "count" -> hits.size.asJson,
                    "results" -> hits.map(_.asJson).asJson
                  )
                )
            }
          end if
        }

      case req @ GET -> Root / "wiki" / "terms" / "stats" =>
        withAuth(req) {
          val db = TermDb.current
          db.stats.flatMap {
            case Some(s) => Ok(s.asJson)
            case None =>
              Ok(
                Json.obj(
                  "available" -> false.asJson,
                  "db" -> db.dbPath.toString.asJson,
                  "error" -> "term index unavailable (missing db or unknown schema_version)".asJson
                )
              )
          }
        }

      case req @ GET -> Root / "wiki" / "terms" =>
        withAuth(req) {
          val db = TermDb.current
          val q = req.params.get("q").map(_.trim).filter(_.nonEmpty)
          val project = req.params.get("project").map(_.trim).filter(_.nonEmpty)
          val topic = req.params.get("topic").map(_.trim).filter(_.nonEmpty)
          val doc = req.params.get("doc").map(_.trim).filter(_.nonEmpty)
          val limit = req.params.get("limit").flatMap(_.trim.toIntOption).getOrElse(TermDb.DefaultLimit)
          db.terms(q, project, topic, doc, limit).flatMap { res =>
            Ok(
              res.asJson.deepMerge(
                Json.obj(
                  "db" -> db.dbPath.toString.asJson,
                  "filters" -> Json.obj(
                    "q" -> q.map(_.asJson).getOrElse(Json.Null),
                    "project" -> project.map(_.asJson).getOrElse(Json.Null),
                    "topic" -> topic.map(_.asJson).getOrElse(Json.Null),
                    "doc" -> doc.map(_.asJson).getOrElse(Json.Null),
                    "limit" -> limit.asJson
                  )
                )
              )
            )
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

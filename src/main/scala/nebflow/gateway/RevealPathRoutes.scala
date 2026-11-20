/* canvas-reveal-in-finder 批（2026-11-19；作者令逐字 19:39「电脑端的canvas标签页
 * 右键，多一个选项，在访达/文件浏览器打开。」）—— 本 REST 域 = 该动作的**唯一
 * 入口**：`GET /api/reveal-path`（平台能力，前端据此挑文案）+ `POST /api/reveal-path`
 * （动作；`dryRun:true` = 只判不执行，供右键菜单做「文件还在吗」的置灰探测）。
 *
 * 为什么分成「能力面 / 动作面」两条而不是一条：文案必须在**点之前**就定下来
 * （任务书「平台由引擎侧返回，不在前端猜 navigator.platform」），而动作必须在
 * **点之后**才可能发生 —— 合成一条就得给 GET 加执行语义（禁止）。
 *
 * 与同族先例的关系：`POST /api/projects/<name>/open-in-app`（ProjectsRoutes）＝
 * 「用指定应用打开工作区」；本域＝「在系统文件管理器里定位一个文件」，两者同属
 * **本地动作通道**：`withAuth` 门 + shell-free `ProcessBuilder(argv*)` + realpath
 * 判据 + 结构化错误码（判据实现见 [[RevealPath]]）。
 *
 * 错误码表（`{ok:false, code, error}`，`code` 稳定、`error` 人读）：
 *   | HTTP | code                 | 何时                                                |
 *   |------|----------------------|-----------------------------------------------------|
 *   | 403  | path-refused         | 凭据位置 / 指向凭据 inode 的硬链接（nf-file 同判据）  |
 *   | 404  | not-found            | 路径不存在，或不是常规文件（含悬空链接、已被删除）    |
 *   | 400  | missing-path         | body 里没有 `path`                                   |
 *   | 409  | not-a-file           | realpath 存在但不是常规文件（目录 / 设备 / 竞态替换） |
 *   | 501  | unsupported-platform | 本机没有该平台的打开器（无 os.name / 无 xdg-open）    |
 *   | 500  | spawn-failed         | 打开器起不来（异常原文进 `error`）                    |
 *   | 502  | opener-failed        | 打开器退出码非 0（码数与 argv 一并回报）              |
 *   | 403  | Unauthorized         | 未通过 `withAuth`（引擎既有 X-Nf-Reason 头）          |
 */
package nebflow.gateway

import cats.effect.IO
import io.circe.Json
import io.circe.syntax.*
import org.http4s.*
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.dsl.io.*

private[gateway] object RevealPathRoutes:

  private def refusalBody(r: RevealPath.Refusal): Json =
    Json.obj("ok" -> false.asJson, "code" -> r.code.asJson, "error" -> r.message.asJson)

  private def refusalResponse(r: RevealPath.Refusal): Response[IO] =
    Response[IO](r.status).withEntity(refusalBody(r))

  private def unsupported(reason: String): Response[IO] =
    Response[IO](Status.NotImplemented).withEntity(
      Json.obj("ok" -> false.asJson, "code" -> "unsupported-platform".asJson, "error" -> reason.asJson)
    )

  def routes(ctx: RestApiCtx): HttpRoutes[IO] =
    import ctx.*
    HttpRoutes.of[IO] {
      // ── 能力面：这台机器能把文件「在系统文件管理器里定位」吗？用哪个文案？ ──
      // 无路径参数、无执行、无副作用：只答 { supported, platform, target }。
      // 前端据此决定是否渲染菜单项（以及挑 finder / explorer / file-manager 文案），
      // 因此 `platform` 永远由引擎判定，前端不猜 navigator.platform。
      case req @ GET -> Root / "reveal-path" =>
        withAuth(req) {
          RevealPath.platform() match
            case Some(p) if RevealPath.opener(p).isDefined =>
              Ok(
                Json.obj(
                  "supported" -> true.asJson,
                  "platform" -> p.id.asJson,
                  "target" -> p.target.asJson
                )
              )
            case Some(p) =>
              Ok(
                Json.obj(
                  "supported" -> false.asJson,
                  "platform" -> p.id.asJson,
                  "target" -> Json.Null,
                  "reason" -> s"no file-manager opener on this host (${p.id})".asJson
                )
              )
            case None =>
              Ok(
                Json.obj(
                  "supported" -> false.asJson,
                  "platform" -> Json.Null,
                  "target" -> Json.Null,
                  "reason" -> s"unrecognised host OS: ${sys.props.getOrElse("os.name", "")}".asJson
                )
              )
        }

      // ── 动作面：body { path, dryRun? } ────────────────────────────────────
      // dryRun=true ⇒ 只跑判据、**不执行任何进程**（右键菜单的置灰探测腿：
      // 「文件已不存在」必须在点之前就能显示）。判据两条腿共用同一份实现，
      // 所以「探测说在、点下去却失败」只可能来自探测与点击之间的真实竞态。
      case req @ POST -> Root / "reveal-path" =>
        withAuth(req) {
          req.as[Json].flatMap { body =>
            val rawPath = body.hcursor.get[String]("path").getOrElse("").trim
            val dryRun = body.hcursor.get[Boolean]("dryRun").getOrElse(false)
            RevealPath.platform() match
              case None =>
                IO.pure(unsupported(s"unrecognised host OS: ${sys.props.getOrElse("os.name", "")}"))
              case Some(p) if RevealPath.opener(p).isEmpty =>
                IO.pure(unsupported(s"no file-manager opener on this host (${p.id})"))
              case Some(p) =>
                RevealPath.resolvePath(rawPath).flatMap {
                  case Left(refusal) => IO.pure(refusalResponse(refusal))
                  case Right(real) =>
                    RevealPath.argvFor(p, real) match
                      case None => IO.pure(unsupported(s"no file-manager opener on this host (${p.id})"))
                      case Some(argv) =>
                        // 回报面把「平台 / 文案归属 / realpath / argv」一起给出：证据面
                        // 无需再猜命令是什么（且 argv 里的路径就是执行用的那一份）。
                        val descriptor = Json.obj(
                          "platform" -> p.id.asJson,
                          "target" -> p.target.asJson,
                          "path" -> real.toString.asJson,
                          "command" -> argv.asJson
                        )
                        if dryRun then
                          Ok(
                            descriptor.deepMerge(
                              Json.obj(
                                "ok" -> true.asJson,
                                "exists" -> true.asJson,
                                "regularFile" -> true.asJson,
                                "dryRun" -> true.asJson
                              )
                            )
                          )
                        else
                          RevealPath.launch(argv).attempt.flatMap {
                            case Right(0) =>
                              Ok(descriptor.deepMerge(Json.obj("ok" -> true.asJson, "revealed" -> true.asJson)))
                            case Right(code) =>
                              IO.pure(
                                Response[IO](Status.BadGateway).withEntity(
                                  descriptor.deepMerge(
                                    Json.obj(
                                      "ok" -> false.asJson,
                                      "code" -> "opener-failed".asJson,
                                      "error" -> s"file-manager opener exited with code $code".asJson
                                    )
                                  )
                                )
                              )
                            case Left(e) =>
                              IO.pure(
                                Response[IO](Status.InternalServerError).withEntity(
                                  descriptor.deepMerge(
                                    Json.obj(
                                      "ok" -> false.asJson,
                                      "code" -> "spawn-failed".asJson,
                                      "error" -> s"could not start the file-manager opener: ${Option(e.getMessage).getOrElse(e.getClass.getName)}".asJson
                                    )
                                  )
                                )
                              )
                          }
                        end if
                }
            end match
          }
        }
    }
  end routes
end RevealPathRoutes

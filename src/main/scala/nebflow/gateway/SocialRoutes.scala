/* 从 RestApiRoutes 迁出(行为保持重构,2026-09-24)。 */
package nebflow.gateway

import cats.effect.IO
import cats.syntax.all.*
import io.circe.syntax.*
import io.circe.{Json, parser}
import nebflow.neblink.*
import nebflow.neblink.FriendCodecs.given
import org.http4s.*
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.dsl.io.*
import org.typelevel.ci.CIString

/**
 * 社交域(social,D 步 2026-09-24):原 RestApiRoutes.routes 巨型 match 的
 * /friends、/conversations、/attachments、/users、/groups 全族 case 逐字迁入,
 * 行为保持;经 RestApiRoutes.routes 尾部 `<+> SocialRoutes.routes(ctx)` 挂载,
 * 各臂首段路径字面量与类内剩余 case 互不重叠。社交域专属助手 friendResult /
 * friendResultRaw / groupProxy / groupSendProxy 随迁为 routes 局部 def(闭包直取
 * `import ctx.*` 面);跨域共用助手 friendErr / groupProxyResult / rawBody /
 * encSeg(类内 /avatars、/devices 面仍调用)留守 RestApiRoutes 类内、经
 * RestApiCtx 委托引用 —— 全仓单一实现,禁两处各写一套。`origin` 闸判据
 * GroupSendOriginVerdict 随本文件迁(仍为文件级 object,包内可见性不变)。
 */
private[gateway] object SocialRoutes:

  def routes(ctx: RestApiCtx): HttpRoutes[IO] =
    import ctx.*

    /**
     * Uniform A2A endpoint result mapping: upstream Left → 401/404/502 by the
     * single judgement above (was: always 502).
     */
    def friendResult(result: Either[String, io.circe.Json]): IO[Response[IO]] =
      result match
        case Right(json) => Ok(json)
        case Left(err) => friendErr(err)

    /**
     * Raw-string upstream results (decline/remove/read): parse the body as JSON
     * when possible, else wrap as {ok, message}.
     */
    def friendResultRaw(result: Either[String, String]): IO[Response[IO]] =
      result match
        case Right(body) =>
          parser.parse(body) match
            case Right(json) => Ok(json)
            case Left(_) => Ok(ApiJson.okMessage(body))
        case Left(err) => friendErr(err)

    // ===== 群代理腿的单一实现（gwroutes 批，2026-09-15）=====
    //
    // 12 条群路由**共用**本实现（禁各写一套 —— 与 `friendErr` 是「好友域上游错误的
    // 单一判据」同构：群域的状态码判据也只有这一处）。

    /**
     * 群请求转发（唯一入口）。**语义分三层**：
     *
     *  ① **鉴权在先**：`withAuth` 先于任何上游往返（无 token ⇒ 403，零外发）；
     *  ② **未配置即 404**：`friendService` 缺席 ⇒ `404 NebLink not enabled`。这是
     *     **fail-closed 的承重墙**：客户端 `friendsApi.errKind` 把 404 读作
     *     `neblinkOff` ⇒ `friendGroups.markAvailability(false)` ⇒ 群入口隐藏
     *     （主卡 G-2）。改成 502/空成功都会把「群不可用」伪装成「群是空的」；
     *  ③ **身份透传**：与全部既有 friends / conversations 代理**逐字一致** ——
     *     身份**只**由 `NeblinkServerUrl + Bearer device session token` 承载，
     *     本层**不发** `sender` / `uid` 类自定义头（客户端不得自报身份；服务端
     *     `require_user` 从 token 解身份，`groups.rs:50-54`）。
     *
     * 请求体**按原文转发**（见 [[rawBody]]）：不解析、不重编码 —— 解析后再编码会
     * 重排键并丢掉未知键，等于替冻结契约改了形态。
     */
    def groupProxy(req: Request[IO], method: String, upstreamPath: String): IO[Response[IO]] =
      withAuth(req) {
        sharedResources.friendService match
          case None => NotFound(Json.obj("error" -> "NebLink not enabled".asJson))
          case Some(fs) =>
            rawBody(req).flatMap(body => fs.groupProxy(method, upstreamPath, body).flatMap(groupProxyResult))
      }

    /**
     * **UI 身份直发的群消息路由**（`POST /api/groups/{groupId}/messages`）——
     * 代理 + 一道 `origin` 闸。
     *
     * 🔴 为什么需要闸（补充卡 §6.5 + §8.1 判红面 ①「标识伪造面」）：本路由是
     * **用户身份**直发面（前端唯一可达的群发送入口）。代理腿的默认形态是逐字转发
     * 请求体（不解析、不重编码），但那样 web 前端就能塞一个 `origin:"agent"`
     * 一路到服务端并**落库为 agent 代发** —— 而 §8.1(a) 的判红信号正是「非 agent
     * 通道的消息被存成 `origin='agent'`」。⇒ 本路由是**唯一**在转发前读请求体的群
     * 路由，且**只判 `origin` 一个键**：缺席 / 逐字 `"user"` ⇒ 原文转发（= 服务端
     * 缺省语义，字节零变化）；**其他任何值** ⇒ `400` 显式拒绝，**零上游往返**。
     *
     * 处置形态的选择（两条都登记在其后的「为什么不」里）：
     *  - **显式拒绝，不静默改写**：剔键 / 改写为 `"user"` 会让一次越界自报**静默消失**
     *    （调用方以为生效了、实际没有）——本仓明令禁止的缺陷族（静默不达）。
     *  - **不按补充卡 §6.5 的字面机制「只读 `body` 一个字段重建请求体」**：服务端已把
     *   附件纳入一期群发（作者指令），而 UI 腿正在飞 ⇒ 重建会把 UI 后续携带的
     *   加性键（`attachments` 等）**静默丢弃**。本批取「保住判据目标（UI 面不可能产出
     *   `origin='agent'`）+ 不静默丢键」，字面机制差异作为**待作者裁**项单列上报
     *   （实施报告「待作者拍板」节，非本节点自裁）。
     *
     * 身份面既有纪律不变：本层**不发** `sender`/`uid` 类自定义头（身份**只**由
     * `NebLinkServerUrl + Bearer device session token` 承载）。
     */
    def groupSendProxy(req: Request[IO], groupId: String): IO[Response[IO]] =
      withAuth(req) {
        sharedResources.friendService match
          case None => NotFound(Json.obj("error" -> "NebLink not enabled".asJson))
          case Some(fs) =>
            rawBody(req).flatMap { body =>
              GroupSendOriginVerdict.check(body) match
                case Left(reason) => BadRequest(Json.obj("error" -> reason.asJson))
                case Right(()) =>
                  fs.groupProxy("POST", s"/api/groups/${encSeg(groupId)}/messages", body)
                    .flatMap(groupProxyResult)
            }
      }

    HttpRoutes.of[IO] {
      // ===== A2A 好友与消息端点（spec §6.1 客户端 UI 代理层） =====
      // 全部经 FriendService → NeblinkClient 代理到 neblink-server（Bearer device
      // session token）。friendService 仅在 NebLink Server 配置时存在。

      /** 好友列表 + 双向 pending 请求。 */
      case req @ GET -> Root / "friends" =>
        withAuth(req) {
          sharedResources.friendService match
            case None => NotFound(Json.obj("error" -> "NebLink not enabled".asJson))
            case Some(fs) =>
              // F4 (2026-09-10 friend-search batch): list loads go through the
              // DIRECT path — upstream Left folds to 502 so the frontend can
              // tell "no friends yet" apart from "load failed". The folded
              // empty-list variant (FriendService.refreshFriends) stays for the
              // background refresh chain only.
              fs.listFriends.flatMap {
                case Right(resp) => Ok(resp.asJson)
                // 2026-09-11：上游 Left 走单一判据（未登录 → 401/404，其余 → 502）。
                case Left(err) => friendErr(err)
              }
        }

      /**
       * 待处理请求分组（incoming / outgoing）。
       *
       * 2026-09-11 明写「不改（无消费者）」：本端点走折叠版
       * `FriendService.refreshFriends`（上游 Left 在服务内折成空表 ⇒ 恒 200），
       * 前端 `friendsApi.js` 对该路径只有 POST，incoming/outgoing 全部来自
       * `GET /api/friends`（含本面板）⇒ 不接 friendErr、不扩大改动面。
       */
      case req @ GET -> Root / "friends" / "requests" =>
        withAuth(req) {
          sharedResources.friendService match
            case None => NotFound(Json.obj("error" -> "NebLink not enabled".asJson))
            case Some(fs) =>
              fs.refreshFriends().flatMap { resp =>
                Ok(
                  Json.obj(
                    "incoming" -> resp.incoming.asJson,
                    "outgoing" -> resp.outgoing.asJson
                  )
                )
              }
        }

      /** 发好友请求（按 NebLink 号寻址）。body: {query, note?} */
      case req @ POST -> Root / "friends" / "requests" =>
        withAuth(req) {
          sharedResources.friendService match
            case None => NotFound(Json.obj("error" -> "NebLink not enabled".asJson))
            case Some(fs) =>
              req.as[Json].flatMap { body =>
                val query = body.hcursor.downField("query").as[String].getOrElse("")
                val note = body.hcursor.downField("note").as[Option[String]].toOption.flatten
                if query.isEmpty then BadRequest(Json.obj("error" -> "Missing query".asJson))
                else fs.sendFriendRequest(query, note).flatMap(friendResult)
              }
        }

      case req @ POST -> Root / "friends" / "requests" / requestId / "accept" =>
        withAuth(req) {
          sharedResources.friendService match
            case None => NotFound(Json.obj("error" -> "NebLink not enabled".asJson))
            case Some(fs) => fs.acceptFriendRequest(requestId).flatMap(friendResult)
        }

      case req @ POST -> Root / "friends" / "requests" / requestId / "decline" =>
        withAuth(req) {
          sharedResources.friendService match
            case None => NotFound(Json.obj("error" -> "NebLink not enabled".asJson))
            case Some(fs) => fs.declineFriendRequest(requestId).flatMap(friendResultRaw)
        }

      /**
       * 发消息给好友（用户身份——UI 输入框直发，无 agent 权限档位）。
       *
       * body: `{body, attachments?, clientMsgId?}`（attachcl / P2-b 两批加性扩面）。
       *
       * `attachments` = **已上传**的附件 id 列表（顺序 = 展示顺序），由本路由**逐字**
       * 转给 `FriendService.sendAsUser` → `NeblinkClient.sendFriendMessage`。🔴 本层
       * 只搬运 id、**不**判权限（关系闸在服务端 E1/E2/E3）、**不**做上传（字节面 =
       * `POST /api/attachments`，同一分块驱动）。
       *
       * `clientMsgId`（P2-b）= 客户端**发送动作**侧的幂等键（同动作重试复用同键）。
       * 本路由**只做**「读出 + 透传」两件事：不判重、不去重、不缓存、不改状态码
       * —— 幂等判定**全在服务端**（§8.6：同键重复仍是 201、`existing:true` 仅表示
       * 回放原行）。🔴 加性判据：键缺席 / 空串 / 非字符串 ⇒ `None` ⇒ 转发形态与今天
       * **逐字节同形**（旧客户端零变化；`NeblinkClient` 只在 `Some` 时发该键）。
       * 🔴 本路由**是**在转发前读请求体的既有腿（`{body, attachments}` 早已如此），
       * 但**只读**这几个键、**原样**取值——不重建请求体、不重排、不丢未知键。
       *
       * 正文闸的加性放开：`body` 为空**仅当** `attachments` 非空时允许（服务端 §B.4
       * 有附件时生成占位正文）——这是**拓宽**而不是收紧：无附件时空正文仍逐字 400
       * （旧行为不变，与群路由的服务端校验序同源）。
       *
       * `replyToMessageId`（quotejump 批加性扩面 · 作者裁 (c) 双写双读）= 被引消息的
       * `messages.id`（**整数**）。本路由**只做**「读出 + 透传」两件事，与上面三键同款：
       * 不校验坐标、不解析会话归属、不改状态码 —— 🔴 坐标合法性与会话归属**全在服务端**
       * 写事务内判定（异会话 / 无此行 ⇒ **400 `REPLY_TARGET_INVALID`**，零副作用），
       * 本层自行「校验」即造出第二套真相。🔴 加性判据：键缺席 / `null` / 非正整数 ⇒
       * `None` ⇒ 转发形态与今天**逐字节同形**（旧客户端零变化；`NeblinkClient` 只在
       * `Some` 时发该键）。🔴 **本路由只读已列出的键、逐字取值，不重建请求体** ——
       * 下游 `NeblinkClient` 的 `Json.fromFields(fields)` 才是事实上的出站白名单
       * （新键若不在这三处显式出现就会在网关**静默消失**，表现 = 跨会话跳转不可达而
       * 同会话仍可跳，缺陷隐蔽）。🔴 群腿不受影响：群发送是**原文转发**（见本文件
       * `groupSendProxy`），外仓群/设备体刻意不收该键（D-6），本批禁为群腿硬塞字段。
       */
      case req @ POST -> Root / "friends" / friendUserId / "messages" =>
        withAuth(req) {
          sharedResources.friendService match
            case None => NotFound(Json.obj("error" -> "NebLink not enabled".asJson))
            case Some(fs) =>
              req.as[Json].flatMap { body =>
                val text = body.hcursor.downField("body").as[String].getOrElse("")
                val attachmentIds = body.hcursor
                  .downField("attachments")
                  .as[List[String]]
                  .getOrElse(Nil)
                  .filter(_.nonEmpty)
                val clientMsgId = body.hcursor
                  .downField("clientMsgId")
                  .as[String]
                  .toOption
                  .filter(_.nonEmpty)
                val replyToMessageId = body.hcursor
                  .downField("replyToMessageId")
                  .as[Long]
                  .toOption
                  .filter(_ > 0)
                if text.isEmpty && attachmentIds.isEmpty then BadRequest(Json.obj("error" -> "Missing body".asJson))
                else
                  // rcptcode 批（好友腿终态码 502 折叠修复 · 折叠点 ②）：本路由改走
                  // **保留状态码**通道 —— `sendAsUserWithStatus` + 既有唯一映射器
                  // `groupProxyResult`（上游状态码逐字 + 体优先 JSON；`Left` 仍走
                  // `friendErr` ⇒ 传输失败 502 / 未登录三态**逐字不变**）。
                  // 🔴 为什么不新写第二套映射：群腿 `groupSendProxy` 与附件腿已各自透传，
                  // `(status, body) ⇒ Response` 只有 `groupProxyResult` 这一个事实源。
                  // 🔴 本路由是好友域**唯一**采用该通道的腿（其余 20+ 好友路由继续折叠 ——
                  // 是否推广是另一刀；`GET /friends` 的 502 是 F4 有意为之，勿顺手改）。
                  fs.sendAsUserWithStatus(friendUserId, text, attachmentIds, clientMsgId, replyToMessageId)
                    .flatMap(groupProxyResult)
              }
        }

      /** 删除好友。 */
      case req @ DELETE -> Root / "friends" / friendUserId =>
        withAuth(req) {
          sharedResources.friendService match
            case None => NotFound(Json.obj("error" -> "NebLink not enabled".asJson))
            case Some(fs) => fs.removeFriend(friendUserId).flatMap(friendResultRaw)
        }

      /** 拉黑好友（#290 §1.2 WeChat 式黑名单）。 */
      case req @ POST -> Root / "friends" / friendUserId / "block" =>
        withAuth(req) {
          sharedResources.friendService match
            case None => NotFound(Json.obj("error" -> "NebLink not enabled".asJson))
            case Some(fs) => fs.blockFriend(friendUserId).flatMap(friendResultRaw)
        }

      /** 移出黑名单（仅拉黑方；上游非拉黑方 403 not_blocker → BadGateway 透传错误）。 */
      case req @ POST -> Root / "friends" / friendUserId / "unblock" =>
        withAuth(req) {
          sharedResources.friendService match
            case None => NotFound(Json.obj("error" -> "NebLink not enabled".asJson))
            case Some(fs) => fs.unblockFriend(friendUserId).flatMap(friendResultRaw)
        }

      /**
       * 设置 / 清除好友备注（⑦，2026-09-12）。body `{remark}` → 200 `{ok:true}`。
       *
       * 逐行镜像 `PUT /neblink/peer-description` 的形态（withAuth + 缺参 400），
       * 差别只有一处：**备注是 home 本地态、不触上游** ⇒ 本端点**没有** 502 /
       * `friendErr` 分态（唯一失败面 = 400 缺参；未认证 = withAuth 403）。
       * 语义：`trim` 后空串 = 清除（删键）；`remark` 键缺席 / null / 非字符串 =
       * 缺参 400（冻结契约形 `{"remark":"<string>"}`——清备注用 `""`，不用 null）。
       * 回显：响应恒 `{ok:true}`，不回带 remark（前端本地已有值，无二次真相源）。
       */
      case req @ PUT -> Root / "friends" / friendUserId / "remark" =>
        withAuth(req) {
          sharedResources.friendService match
            case None => NotFound(Json.obj("error" -> "NebLink not enabled".asJson))
            case Some(fs) =>
              req.as[Json].flatMap { body =>
                body.hcursor.downField("remark").as[String].toOption match
                  case Some(remark) =>
                    fs.setRemark(friendUserId, remark) *> Ok(ApiJson.ok)
                  case None => BadRequest(Json.obj("error" -> "Missing remark".asJson))
              }
        }

      /** 会话列表（按 last_message_id 倒序，含 unreadCount）。 */
      case req @ GET -> Root / "conversations" =>
        withAuth(req) {
          sharedResources.friendService match
            case None => NotFound(Json.obj("error" -> "NebLink not enabled".asJson))
            case Some(fs) =>
              fs.refreshConversations().flatMap(convs => Ok(convs.asJson))
        }

      /** keyset 分页拉消息。?after=N&limit=N */
      case req @ GET -> Root / "conversations" / conversationId / "messages" =>
        withAuth(req) {
          sharedResources.friendService match
            case None => NotFound(Json.obj("error" -> "NebLink not enabled".asJson))
            case Some(fs) =>
              val after = req.params.get("after").flatMap(_.toLongOption).getOrElse(0L)
              val limit = req.params.get("limit").flatMap(_.toIntOption).getOrElse(50)
              fs.listMessages(conversationId, after, limit).flatMap(r => friendResult(r.map(_.asJson)))
        }

      /**
       * 附件下载（4b 腿 A-3，裁定②：「下载面走**应用内鉴权路由**」）。
       *
       * **唯一取字节入口**：前端（`friendsApi.js#downloadFriendAttachment`）只能经本路由
       * 取字节，拿不到服务端地址/凭证；服务端附件目录**不挂 Caddy**（§D.1、§A.2 N3）
       * ⇒ 全链路不存在任何静态/公开 URL 面（🔴 红线：禁直出静态 URL 绕过鉴权）。
       *
       * 鉴权 = 本网关的既有 `withAuth`（同其余 `/friends*`、`/conversations*` 面）；
       * 关系闸（`friendship_accepted`，含拉黑）在服务端 E3 上，本层**不复制**第二套
       * 权限判定（禁双实现）。
       *
       * 状态码**逐字透传**上游，不折叠：`410` = 附件已过期（**终态**，§B.7 ③ 要求客户端
       * 能把「已过期」与「下载失败」分开）；`404` = 不存在/对调用方不可见；`403` = 非好友。
       * 其余（含 5xx）⇒ 502 + 逐字原因（可重试态）。
       */
      case req @ GET -> Root / "friends" / "attachments" / attachmentId =>
        withAuth(req) {
          sharedResources.friendService match
            case None => NotFound(Json.obj("error" -> "NebLink not enabled".asJson))
            case Some(fs) =>
              fs.downloadAttachment(attachmentId).flatMap {
                case Left(err) => friendErr(err)
                case Right(fetch) =>
                  fetch.status match
                    case 200 =>
                      // 显式字节流实体（同 /neblink/avatar 先例：裸 Ok(Array[Byte]) 会命中
                      // circe 的 byte 数组编码器，把字节变成 JSON 数字数组 ⇒ 文件损坏）。
                      val attHeaders = Headers(
                        List(
                          Some(Header.Raw(CIString("Content-Type"), "application/octet-stream")),
                          fetch.contentDisposition.map(d => Header.Raw(CIString("Content-Disposition"), d)),
                          fetch.sha256Header.map(h => Header.Raw(CIString("X-Attachment-Sha256"), h))
                        ).flatten
                      )
                      IO.pure(
                        Response[IO](Status.Ok)
                          .withEntity(fs2.Stream.emits(fetch.bytes).covary[IO])
                          .withHeaders(attHeaders)
                      )
                    case 410 => Gone(Json.obj("error" -> "attachment_expired".asJson))
                    case 404 => NotFound(Json.obj("error" -> "attachment_not_found".asJson))
                    case 403 => Forbidden(Json.obj("error" -> "not_friends".asJson))
                    case other =>
                      BadGateway(Json.obj("error" -> s"attachment download failed upstream: HTTP $other".asJson))
              }
        }

      /**
       * **附件上传**（attachcl 批，2026-09-16）——网页腿的**唯一**字节入口。
       *
       * 上传形态（A1 = ②）：**网页整件一次请求 → 网关 → 复用桌面分块驱动**。
       * 浏览器把整件放进请求体（`fetch(..., {body: file})`，Chromium 自带流式发送），
       * 本路由把请求体**流式**落临时件（`streamToFileWithHashBounded`，上限 1 GiB 在
       * **读的过程中**生效），然后把临时件交给 [[nebflow.neblink.AttachUpload.pushFile]]
       * —— **与桌面腿同一份** E1+E2×n 链（块大小仍是 `AttachContract.plan` 的 4 MiB，
       * 单块峰值内存与文件大小无关）。
       *
       * 🔴 **硬钉①（禁整件缓冲）机械判据**：本方法体里**不出现** `req.as[Array[Byte]]` /
       * `bodyText.compile.string` / `req.as[String]` / `req.as[Json]` —— 请求体只以
       * `req.body`（`Stream[IO, Byte]`）形态被消费一次；单块字节的 `Array[Byte]` 只出现在
       * [[nebflow.neblink.AttachUpload.pushChunks]] 的 4 MiB `readRange` 里。
       *
       * 🔴 **硬钉②（禁假进度 / 失败可见）**：进度只由 [[nebflow.neblink.AttachUpload.Hooks.onChunk]]
       * 在**服务端确认一块之后**广播（WS 帧 `attach-upload-progress`），因此不存在
       * 「到点 100%」；成败**一律**落在本次响应的 `ok` 上（失败 ⇒ 非 2xx + 可判读
       * `code`/`error`，取消 ⇒ `409` + `code:"cancelled"`）——绝不把拒绝塞进 2xx。
       *
       * 🔴 **E1 早拒（1 GiB，不得晚于传输前）**：`X-Attach-Size`（缺省用 `Content-Length`）
       * 超限 ⇒ 在**读请求体之前** `413`；声明缺失/撒谎 ⇒ 流内上限兜底（同样早于任何
       * 上游字节：落盘阶段就断）。
       *
       * 会话寻址 `conversationId` = 好友 userId / 群会话 id（服务端 E1 对这段段做
       * `friendship_accepted` ∨ `is_member_gated`）⇒ **好友与群同一路由、同一驱动、同一渲染**
       * （A4：两面同批）。
       *
       * 临时件在所有出口删除；取消见下一条路由。
       */
      case req @ POST -> Root / "attachments" =>
        withAuth(req) {
          sharedResources.friendService match
            case None => NotFound(Json.obj("error" -> "NebLink not enabled".asJson))
            case Some(fs) =>
              val conversationId = req.params.getOrElse("conversationId", "")
              val name = req.params.getOrElse("name", "")
              val rawUploadId = req.params.getOrElse("uploadId", "")
              // 🔴 **形态闸（attachid 批）**：`uploadId` 是**客户端可控**的查询参数，且会被当作
              // **单个路径段**消费（`FriendService.uploadStream` 拼临时件路径）⇒ 非空时先过
              // **唯一**判定点 [[nebflow.neblink.AttachUploadId]]。判定在**任何路径拼接 / 登记 /
              // 读请求体之前** ⇒ 非法 id 零落盘 / 零上游调用 / 零临时件（复核位 A1 的原始缺陷：
              // 含 `/` 的 id ⇒ 500 + 空体）。
              // 🔴 **缺席 / 空白仍是合法语义**（本次上传不可取消、服务端自造 `anon-…`）⇒ 只在
              // 非空时判，该面逐字不变（既有合法路径零回归）。
              val uploadIdForm: Either[String, Option[String]] =
                val trimmed = rawUploadId.trim
                if trimmed.isEmpty then Right(None) else AttachUploadId.validate(trimmed).map(Some(_))
              val declared = req.headers
                .get(CIString("X-Attach-Size"))
                .map(_.head.value.trim.toLongOption)
                .getOrElse(req.contentLength)
              if conversationId.trim.isEmpty then
                // 未走 ApiJson 信封助手:ok+code 族({ok:false,code,error})按裁定单列不合并,code 键属客户端可见契约,保持手写(2026-09-25)
                BadRequest(
                  Json.obj(
                    "ok" -> false.asJson,
                    "code" -> "invalid_argument".asJson,
                    "error" -> "Missing conversationId".asJson
                  )
                )
              else if name.trim.isEmpty then
                // 未走 ApiJson 信封助手:ok+code 族({ok:false,code,error})按裁定单列不合并,code 键属客户端可见契约,保持手写(2026-09-25)
                BadRequest(
                  Json.obj("ok" -> false.asJson, "code" -> "invalid_argument".asJson, "error" -> "Missing name".asJson)
                )
              else if uploadIdForm.isLeft then
                // 与相邻两条 400 **逐字同形**（`{ok:false, code:"invalid_argument", error}`）：
                // 网关既有 4xx 信封先例就在本路由，**不新造第二套错误形状**；`error` 文案由闸
                // 单点给出（自描述 + 实际值回显）。
                // 未走 ApiJson 信封助手:ok+code 族按裁定单列不合并,保持手写(2026-09-25)
                BadRequest(
                  Json.obj(
                    "ok" -> false.asJson,
                    "code" -> AttachUploadId.ErrorCode.asJson,
                    "error" -> uploadIdForm.left.toOption.getOrElse("").asJson
                  )
                )
              else
                // 早拒三段（全部**先于**读请求体）：声明超限 / 声明非正 / 无声明但 Content-Length 超限。
                declared match
                  case Some(size) if size > nebflow.dropbox.AttachContract.MaxFileBytes =>
                    // 未走 ApiJson 信封助手:{ok:false,code,actual,limit,error} 五键结构化拒因,非 ok 信封同形,保持手写(2026-09-25)
                    IO.pure(
                      Response[IO](Status.PayloadTooLarge).withEntity(
                        Json.obj(
                          "ok" -> false.asJson,
                          "code" -> "attach_too_large".asJson,
                          "actual" -> size.asJson,
                          "limit" -> nebflow.dropbox.AttachContract.MaxFileBytes.asJson,
                          "error" -> s"Attachment too large: $size bytes exceeds the ${nebflow.dropbox.AttachContract.MaxFileBytesLabel} limit. Nothing was uploaded.".asJson
                        )
                      )
                    )
                  case Some(size) if size <= 0L =>
                    // 未走 ApiJson 信封助手:ok+code 族({ok:false,code,error})按裁定单列不合并,保持手写(2026-09-25)
                    IO.pure(
                      Response[IO](Status.UnprocessableEntity).withEntity(
                        Json.obj(
                          "ok" -> false.asJson,
                          "code" -> "empty_file".asJson,
                          "error" -> "Attachment gate rejected: empty file (0 bytes) — nothing was uploaded.".asJson
                        )
                      )
                    )
                  case _ =>
                    // uploadId = 取消键（客户端生成）。缺席 ⇒ 本次上传不可取消（仍可用），
                    // 服务端生成一枚仅供进度帧关联，**不**登记取消位（禁伪造可取消面）。
                    // 🔴 非空时**必须**用闸后的值（`uploadIdForm` 的右侧），禁用原始入参：
                    // 登记键 / 临时件名 / 进度帧字段 / 响应字段必须是同一枚**已判合法**的串。
                    val checked = uploadIdForm.getOrElse(None)
                    val uploadId = checked.getOrElse(s"anon-${java.util.UUID.randomUUID().toString}")
                    val cancellable = checked.isDefined
                    val registry = sharedResources.attachUploads
                    val hooks = nebflow.neblink.AttachUpload.Hooks(
                      onChunk = (pr: nebflow.neblink.AttachUpload.Progress) =>
                        wsHub.broadcast(
                          // 🔴 帧形状与同族 `dropbox-file-progress` 逐字同构（`type` + 会话键 +
                          // **嵌套 `msg`**）：载荷在 `msg` 下，前端读点 = `msg.uploadId` 等。
                          // （本批实测踩到：扁平帧前端读不到 ⇒ 进度永不推进的「看起来对的错」。）
                          Json.obj(
                            "type" -> "attach-upload-progress".asJson,
                            "conversationId" -> conversationId.asJson,
                            "msg" -> Json.obj(
                              "uploadId" -> uploadId.asJson,
                              "conversationId" -> conversationId.asJson,
                              "name" -> name.asJson,
                              "chunkIndex" -> pr.chunkIndex.asJson,
                              "bytesSent" -> pr.bytesSent.asJson,
                              "totalBytes" -> pr.totalBytes.asJson
                            )
                          )
                        ),
                      cancelled = if cancellable then registry.isCancelled(uploadId) else IO.pure(false)
                    )
                    val run =
                      if cancellable then registry.register(uploadId) else IO.unit
                    (run *> fs.uploadStream(conversationId, name, uploadId, req.body, hooks))
                      .guarantee(if cancellable then registry.release(uploadId) else IO.unit)
                      .flatMap {
                        case Right(up) =>
                          wsHub.broadcast(
                            Json.obj(
                              "type" -> "attach-upload-done".asJson,
                              "conversationId" -> conversationId.asJson,
                              "msg" -> Json.obj(
                                "uploadId" -> uploadId.asJson,
                                "conversationId" -> conversationId.asJson,
                                "ok" -> true.asJson,
                                "attachmentId" -> up.attachmentId.asJson
                              )
                            )
                          ) *>
                            IO.pure(
                              // 未走 ApiJson 信封助手:{ok:true,attachmentId,name,size,sha256} 带业务字段,非裸 ok 信封同形,保持手写(2026-09-25)
                              Response[IO](Status.Created).withEntity(
                                Json.obj(
                                  "ok" -> true.asJson,
                                  "attachmentId" -> up.attachmentId.asJson,
                                  "name" -> up.name.asJson,
                                  "size" -> up.size.asJson,
                                  "sha256" -> up.sha256.asJson
                                )
                              )
                            )
                        case Left((code, message)) =>
                          val status =
                            if code == "cancelled" then Status.Conflict
                            // 形态闸拒因（attachid 批）：`FriendService.uploadStream` 的兜底闸把
                            // 非法 id 收成结构化 Left ⇒ 这里必须映射成**可判读 4xx**（而不是落进
                            // 末尾的 `Status.BadGateway` 兜底 —— 那会把「入参错」报成「上游错」）。
                            else if code == AttachUploadId.ErrorCode then Status.BadRequest
                            else if code == "attach_too_large" then Status.PayloadTooLarge
                            else if code == "empty_file" || code == "invalid_attachment" then Status.UnprocessableEntity
                            else if code == "forbidden" then Status.Forbidden
                            else if code == "not_logged_in" then Status.Forbidden
                            else if code == "attachment_unsupported" then Status.NotImplemented
                            else if code == "rate_limited" || code == "quota_exceeded" then Status.TooManyRequests
                            else if code == "upstream_error" then Status.BadGateway
                            else Status.BadGateway
                          wsHub.broadcast(
                            Json.obj(
                              "type" -> "attach-upload-done".asJson,
                              "conversationId" -> conversationId.asJson,
                              "msg" -> Json.obj(
                                "uploadId" -> uploadId.asJson,
                                "conversationId" -> conversationId.asJson,
                                "ok" -> false.asJson,
                                "code" -> code.asJson,
                                "error" -> message.asJson
                              )
                            )
                          ) *> IO.pure(
                            // 未走 ApiJson 信封助手:ok+code 族({ok:false,code,error})按裁定单列不合并,code 键属客户端可见契约,保持手写(2026-09-25)
                            Response[IO](status).withEntity(
                              Json.obj("ok" -> false.asJson, "code" -> code.asJson, "error" -> message.asJson)
                            )
                          )
                      }
              end if
        }

      /**
       * **取消在飞上传**（attachcl 批）：把取消位翻起来 ⇒
       * [[nebflow.neblink.AttachUpload.pushChunks]] 在**下一块发出前**读到它、停止后续
       * 分块，并以 [[nebflow.neblink.AttachUpload.Failure.Cancelled]] 收尾（**不报完成**）。
       *
       * 未登记过的 `uploadId` ⇒ `200 {cancelled:false}`（不新造位、不谎报成功：客户端
       * 据此如实显示「已结束/无法取消」而不是假的「已取消」）。已登记 ⇒ `{cancelled:true}`
       * （终态由上传请求自身的响应给出，本路由只负责翻转信号）。
       */
      case req @ POST -> Root / "attachments" / uploadId / "cancel" =>
        withAuth(req) {
          // 🔴 **同一道形态闸**（attachid 批）：取消键与上传键是**同一名字空间**，非法形态在任何
          // 消费点都必须被**同一个**判定拒掉 ⇒ 这里复用 [[nebflow.neblink.AttachUploadId]]，
          // **不**各写一份（禁多点漂移）。判定不触盘、不触上游：非法 id 的读数恒为
          // 4xx + 非空体、零落盘、零上游调用、零临时件。
          AttachUploadId.validate(uploadId) match
            case Left(reason) =>
              // 未走 ApiJson 信封助手:ok+code 族({ok:false,code,error})按裁定单列不合并,保持手写(2026-09-25)
              BadRequest(
                Json.obj(
                  "ok" -> false.asJson,
                  "code" -> AttachUploadId.ErrorCode.asJson,
                  "error" -> reason.asJson
                )
              )
            case Right(id) =>
              // 形态合法 ⇒ 既有语义**逐字不变**：未登记过 ⇒ `200 {cancelled:false}`
              // （不新造位、不谎报成功）；已登记 ⇒ `{cancelled:true}`。
              sharedResources.attachUploads.cancel(id).flatMap { flipped =>
                // 未走 ApiJson 信封助手:{ok:true,cancelled,uploadId} 带业务字段,非 {ok,message} 同形,保持手写(2026-09-25)
                Ok(Json.obj("ok" -> true.asJson, "cancelled" -> flipped.asJson, "uploadId" -> id.asJson))
              }
        }

      /**
       * 附件接收完毕回执（补件批 4b1 · §B.1 E4 / §F.1b）。
       *
       * 与上一条 E3 下载路由**同族**：前端拿不到服务端地址/凭证 ⇒ 回执也**只能**走本网关的
       * 应用内鉴权路由（`withAuth`，同其余 `/friends*` 面；服务端侧对应 `POST /api/attachments/{id}/received`）。
       * 关系闸（§F.1b 规则 5：`received` 只对「能读该件的人」开放）在**服务端** E4 上，本层**不复制**
       * 第二套权限判定（禁双实现）。
       *
       * 🔴 **判定不在此层**：本路由只把请求体整理成 [[AttachmentAck.Evidence]] 交给
       * `FriendService.ackAttachmentReceived`；fail-closed 判定只有一处实现（[[AttachmentAck.decide]]），
       * 前端只上报证据（`friendsApi.js#ackAttachmentReceived`）。缺证据 / sha 不符 / 未申报落盘
       * ⇒ 上游**不发** E4 ⇒ 服务端 blob 不动（24 h TTL 兜底）。
       *
       * 🔴 **永不改变用户面**：无论结局是 acknowledged / skipped / failed，一律 `200` + 结局体
       * `{"ack": …, "reason": …}`；调用方（前端）**不 await、不看**该结果 ⇒ 回执失败不影响
       * 下载/保存/UI（失败静默容忍，§F.1b 规则 4）。「E4 失败 ⇒ 用户面与成功路径逐字相同」
       * 因此是**结构保证**：两条路径的唯一差异位就在本体的 `ack`/`reason` 字段。
       */
      case req @ POST -> Root / "friends" / "attachments" / attachmentId / "received" =>
        withAuth(req) {
          sharedResources.friendService match
            case None => NotFound(Json.obj("error" -> "NebLink not enabled".asJson))
            case Some(fs) =>
              req.as[Json].attempt.flatMap {
                case Left(_) =>
                  // 证据体不可解析 ⇒ 按「无法验证」处理（fail-closed：零 E4），且不改用户面。
                  Ok(Json.obj("ack" -> "skipped".asJson, "reason" -> "malformed-evidence".asJson))
                case Right(body) =>
                  val h = body.hcursor
                  val evidence = AttachmentAck.Evidence(
                    localSha256 = h.get[String]("wholeSha256").toOption,
                    declaredSha256 = h.get[String]("declaredSha256").toOption.getOrElse(""),
                    receivedBytes = h.get[Long]("receivedBytes").toOption,
                    expectedBytes = h.get[Long]("expectedBytes").toOption,
                    landedFinal = h.get[Boolean]("landedFinal").toOption.getOrElse(false)
                  )
                  fs.ackAttachmentReceived(attachmentId, evidence).flatMap {
                    case AttachmentAck.Result.Acknowledged => Ok(Json.obj("ack" -> "acknowledged".asJson))
                    case AttachmentAck.Result.Skipped(reason) =>
                      Ok(Json.obj("ack" -> "skipped".asJson, "reason" -> reason.asJson))
                    case AttachmentAck.Result.Failed(reason) =>
                      Ok(Json.obj("ack" -> "failed".asJson, "reason" -> reason.asJson))
                  }
              }
        }

      /** 标记已读。body: {lastReadMessageId} */
      case req @ POST -> Root / "conversations" / conversationId / "read" =>
        withAuth(req) {
          sharedResources.friendService match
            case None => NotFound(Json.obj("error" -> "NebLink not enabled".asJson))
            case Some(fs) =>
              req.as[Json].flatMap { body =>
                val lastRead = body.hcursor.downField("lastReadMessageId").as[Long].getOrElse(-1L)
                if lastRead < 0 then BadRequest(Json.obj("error" -> "Missing lastReadMessageId".asJson))
                else fs.markRead(conversationId, lastRead).flatMap(friendResultRaw)
              }
        }

      /**
       * MVP-2 设备会话域统一（2026-09-15）：**会话级回执读面**
       * （`GET /api/conversations/{id}/receipts` → neblink-server
       * `src/friends.rs:1712 conversation_receipts`）。
       *
       * 路径与既有 `conversations` 面**同族**（同段数、同 auth、同 id 编码）；设备会话
       * 与 legacy 直聊会话**共用本路由**——服务端按 `conversations.kind` 自行分派到
       * `device_message_receipts` / `message_receipts`，且**响应形状逐字同源**
       * （契约 §8.7；服务端逐字「so one client parser reads both faces」）。本层
       * **不判 kind**、不复制第二套分派，禁双实现。
       *
       * 🔴 **状态码逐字透传**（走 [[groupProxyResult]] = 本文件唯一的 `(status, body)`
       * 映射器）：`403 device_identity_required` 是契约 §8.7 的**可判读终态**
       * （「本次凭证没有设备身份」），折叠成 502 后客户端只能解析字符串分态。
       *
       * 🔴 身份面既有纪律不变：身份**只**由 `Bearer device session token` 承载，
       * 本层**不发** `sender`/`uid` 类自定义头（客户端不得自报身份）。
       */
      case req @ GET -> Root / "conversations" / conversationId / "receipts" =>
        withAuth(req) {
          sharedResources.friendService match
            case None => NotFound(Json.obj("error" -> "NebLink not enabled".asJson))
            case Some(fs) =>
              fs.conversationReceipts(conversationId).flatMap(groupProxyResult)
        }

      /** 查号（精确匹配 neblink_id，大小写不敏感）。?q=... */
      case req @ GET -> Root / "users" / "lookup" =>
        withAuth(req) {
          sharedResources.friendService match
            case None => NotFound(Json.obj("error" -> "NebLink not enabled".asJson))
            case Some(fs) =>
              req.params.get("q") match
                case None | Some("") => BadRequest(Json.obj("error" -> "Missing q".asJson))
                case Some(q) => fs.lookupUser(q).flatMap(friendResult)
        }

      /**
       * 搜索（friend-search-contract §4.1 唯一入口）：username OR email 双键 NOCASE
       * 精确。?q=... → 命中 {found:true,user:{username,display_name,avatar},
       * relation_status} / 未命中 {found:false}；透传上游不变形（返回结构与前端
       * friendsApi.normalizeSearch 归一语义严格一致——纯代理，不字段映射）。
       */
      case req @ GET -> Root / "users" / "search" =>
        withAuth(req) {
          sharedResources.friendService match
            case None => NotFound(Json.obj("error" -> "NebLink not enabled".asJson))
            case Some(fs) =>
              req.params.get("q") match
                case None | Some("") => BadRequest(Json.obj("error" -> "Missing q".asJson))
                case Some(q) => fs.searchUser(q).flatMap(friendResult)
        }

      /**
       * [U3] 自定义 NebLink 号。body: {neblinkId} → 200 {neblinkId}；上游 409
       * taken / 422 invalid 由 NeblinkClient 折叠为 Left → 网关 502 + error 透传
       * （web 端以 available 预检 + 本地正则兜底，409/422 仅竞态兜底面）。
       */
      case req @ PUT -> Root / "users" / "me" / "neblink-id" =>
        withAuth(req) {
          sharedResources.friendService match
            case None => NotFound(Json.obj("error" -> "NebLink not enabled".asJson))
            case Some(fs) =>
              req.as[Json].flatMap { body =>
                val id = body.hcursor.downField("neblinkId").as[String].getOrElse("").trim
                if id.isEmpty then BadRequest(Json.obj("error" -> "Missing neblinkId".asJson))
                else fs.setNeblinkId(id).flatMap(friendResult)
              }
        }

      /** [U3] 号可用性实时检测（供 NL 号自定义 UI 即时反馈）。?q=... → {available, reason?} */
      case req @ GET -> Root / "users" / "me" / "neblink-id" / "available" =>
        withAuth(req) {
          sharedResources.friendService match
            case None => NotFound(Json.obj("error" -> "NebLink not enabled".asJson))
            case Some(fs) =>
              req.params.get("q") match
                case None | Some("") => BadRequest(Json.obj("error" -> "Missing q".asJson))
                case Some(q) => fs.neblinkIdAvailable(q).flatMap(friendResult)
        }

      // ===== 群组一期代理面（gwroutes 批，2026-09-15）=====
      //
      // 契约真源 = 跨仓 neblink-server `main`@`9e811ffc77349ae26af3d34ca790cee12ef216b2`
      // 的 `src/groups.rs:616-639`：**11 条 `.route()` / 12 个 method+path 对**（第 1 条
      // `.route("/api/groups", post(group_create).get(group_list))` 一条注册两个方法）。
      // 逐条对表（server → 本层）见报告 §2；本层 = **纯代理**：路径 / 方法 / 请求体
      // 逐字转发，响应 status + body 逐字回传 —— **不**做字段映射、**不**拆信封、
      // **不**裁剪字段（任何 reshape 都会给冻结契约造出第二个真相源）。
      //
      // 🔴 为什么不能复用 `friendResult` / `friendErr`（既有好友面的折叠判据）：那条路
      // 把上游非 2xx 统一折成 502，而群域的 `404 group_not_found` / `403 group_disbanded`
      // / `403 not_member` 是**群终态**（客户端 `web/js/friendGroups.js#groupErrToast`
      // 按语义码分态，`messages.groupNotFound` / `groupDisbanded` / `groupKicked` 三文案）。
      // 折叠 ⇒ 客户端再也分不出「群不存在 / 群已解散 / 我被踢了」⇒ 语义净丢失。
      // 同族先例 = 本文件 E3 附件下载路由（`410`/`404`/`403` 逐字透传不折叠）。
      //
      // 静态段优先：`/groups/invites` 必须排在 `/groups/{group_id}` 之前（与
      // `groups.rs:611-615` 的 axum 静态段优先级**同构**；本文件 `/friends/requests`
      // 先于 `/friends/{friendUserId}` 是同一既有先例）。
      //
      // 本层对每个 method+path 对**只登记一条**：多出的形态（如 `GET /groups/{id}`）
      // 服务端没有 ⇒ 不注册（对表判据「网关多出 server 无」在报告 §2 逐条给读数）。

      /**
       * GET/POST /api/groups —— 我的群列表 / 建群。
       * 列表 = **裸数组** `[GroupSummary]`（`groups.rs:170-176`，同 GET
       * /api/conversations 约定；`model.rs:740-757` `rename_all="camelCase"`）；
       * 建群 201 `{groupId,title,createdAt}` / 422 `invalid_title` / 429 `rate_limited`。
       */
      case req @ GET -> Root / "groups" =>
        groupProxy(req, "GET", "/api/groups")

      case req @ POST -> Root / "groups" =>
        groupProxy(req, "POST", "/api/groups")

      /**
       * GET /api/groups/invites —— **邀请发现面**（加性端点，`groups.rs:178-200`）。
       * 出参 `{"incoming":[GroupInviteEntry]}`（`model.rs:831-836`）。@静态段先于
       * `/groups/{groupId}` 的 GET 形态——服务端无 `GET /groups/{id}`，本层亦不注册。
       */
      case req @ GET -> Root / "groups" / "invites" =>
        groupProxy(req, "GET", "/api/groups/invites")

      /**
       * POST /api/groups/{groupId}/invites —— owner 邀请一人（A-4：被邀请人 accept 后
       * 才入群）。上游 201 `{inviteId,groupId,inviteeUserId,status,createdAt}` /
       * 404 `user_not_found` / 409 `already_member` / 409 `group_full` /
       * 409 `invite_pending` / 400 `self_invite` / 422 `invalid_request`。
       */
      case req @ POST -> Root / "groups" / groupId / "invites" =>
        groupProxy(req, "POST", s"/api/groups/${encSeg(groupId)}/invites")

      /**
       * POST .../invites/{inviteId}/accept —— 仅被邀请人。上游 200
       * `{ok:true,groupId,title}` / 404 `not_found` / 403 `not_invitee` /
       * 409 `not_pending` / 403 `group_disbanded` / 409 `group_full`。
       */
      case req @ POST -> Root / "groups" / groupId / "invites" / inviteId / "accept" =>
        groupProxy(req, "POST", s"/api/groups/${encSeg(groupId)}/invites/${encSeg(inviteId)}/accept")

      case req @ POST -> Root / "groups" / groupId / "invites" / inviteId / "decline" =>
        groupProxy(req, "POST", s"/api/groups/${encSeg(groupId)}/invites/${encSeg(inviteId)}/decline")

      /**
       * GET /api/groups/{groupId}/members —— 成员闸（非成员 403 `not_member`）。
       * 出参 `{"members":[{...FriendPublic,role,joinedAt}]}`（`model.rs:762-776`；
       * 档案字段沿用 FriendPublic 的 snake_case 钉法，**本层不动**）。
       */
      case req @ GET -> Root / "groups" / groupId / "members" =>
        groupProxy(req, "GET", s"/api/groups/${encSeg(groupId)}/members")

      /**
       * POST .../members/{userId}/kick —— owner only。上游 403 `not_owner` /
       * 403 `not_member` / 403 `owner_cannot_leave`（自踢）/ 404 `member_not_found`。
       */
      case req @ POST -> Root / "groups" / groupId / "members" / userId / "kick" =>
        groupProxy(req, "POST", s"/api/groups/${encSeg(groupId)}/members/${encSeg(userId)}/kick")

      /**
       * POST /api/groups/{groupId}/messages —— **冻结群发契约**（`groups.rs:403-524`）。
       * 校验序服务端冻结（auth → 群存在且未解散 → 成员 → 长度 → 限速 → origin →
       * 附件），本层**不复制**任何一条判定（禁双实现）。上游 201 SendMessageResponse
       * 同形 / 404 `group_not_found` / 403 `group_disbanded` / 403 `not_member` /
       * 422 `invalid_length` / 422 `invalid_origin` / 429 `rate_limited`。
       *
       * 🔴 **本路由与其余 11 条群路由的唯一差别**：转发前多过一道 `origin` 闸
       * （见 [[groupSendProxy]]）——它是「UI 身份直发」面，而 agent 代发走的是
       * 进程内腿（`FriendService.sendGroupAsAgent`，不经本路由）⇒ 两腿**共用同一上游
       * 端点**，但只有进程内腿能写 `origin="agent"`。
       */
      case req @ POST -> Root / "groups" / groupId / "messages" =>
        groupSendProxy(req, groupId)

      /**
       * POST /api/groups/{groupId}/leave —— 成员退群；owner 禁退群（上游
       * 403 `owner_cannot_leave`，O⑨）。
       */
      case req @ POST -> Root / "groups" / groupId / "leave" =>
        groupProxy(req, "POST", s"/api/groups/${encSeg(groupId)}/leave")

      /**
       * PUT /api/groups/{groupId}/title —— owner 改名。上游 200 `{ok:true,title}` /
       * 422 `invalid_title`（trim 后非空且 ≤64 字符）。
       */
      case req @ PUT -> Root / "groups" / groupId / "title" =>
        groupProxy(req, "PUT", s"/api/groups/${encSeg(groupId)}/title")

      /**
       * DELETE /api/groups/{groupId} —— owner 解散（**软标记** `group_disbanded`，
       * 消息行永不删；`groups.rs:592-607`）。
       */
      case req @ DELETE -> Root / "groups" / groupId =>
        groupProxy(req, "DELETE", s"/api/groups/${encSeg(groupId)}")

    }

  end routes

end SocialRoutes

/**
 * 群发路由的 `origin` 闸判据（gmsgsend 批 · 补充卡 §6.5 + §8.1(a)）。
 *
 * 🔴 **纯函数 + 单点**：路由（[[RestApiRoutes.groupSendProxy]]）与 spec 都读这一份
 * 判据，禁两处各写一套（本仓「第二实现」缺陷族）。判据只认**逐字** `"user"`：
 *
 *  - `origin` 缺席 / `null` / `"user"` ⇒ `Right(())`（放行 ⇒ 原文转发，字节零变化：
 *    这三种形态在服务端都是「用户身份」，闸不误伤、不改写）；
 *  - 任何其他值（`"agent"` / 大小写变体 / 非字符串）⇒ `Left(理由)`（路由据此答 400，
 *    **零上游往返**）；
 *  - 体为空 / 非 JSON ⇒ `Right(())`：本层**不复制**服务端的 JSON 校验（禁双实现），
 *    非法体到服务端自然被其校验序拒（400/422）。
 *
 * 大小写变体（`"User"`）**拒绝**而非放行：它不是服务端枚举值（服务端会答 422），
 * 拒绝给出更早、更明确的原因；两条路径都不产生 `origin='agent'` 的落库行。
 */
private[gateway] object GroupSendOriginVerdict:

  /** 拒绝理由（对调用方可判读：点名 `origin` + 说明本路由不得设它 + 给出正确做法）。 */
  val RefusalReason: String =
    "This route is the user-identity send path: `origin` is the server's own label and " +
      "cannot be set here — omit it (the server records \"user\" by default)."

  def check(body: String): Either[String, Unit] =
    if body.isBlank then Right(())
    else
      io.circe.parser.parse(body).toOption match
        case None => Right(()) // 非 JSON：交给服务端校验序（本层不复制它）
        case Some(json) =>
          json.hcursor.downField("origin").focus match
            case None => Right(())
            case Some(v) if v.isNull => Right(())
            case Some(v) if v.asString.contains("user") => Right(())
            case Some(_) => Left(RefusalReason)
end GroupSendOriginVerdict

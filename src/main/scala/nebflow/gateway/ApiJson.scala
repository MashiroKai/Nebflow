/* Phase 3 去重(行为保持重构,2026-09-25)。 */
package nebflow.gateway

import io.circe.Json
import io.circe.syntax.*

/**
 * REST/WS 响应信封构造助手(Phase 3 去重,行为保持重构,2026-09-25):只收敛三类
 * 经 grep 实证**完全同形**的手拼 Json.obj——REST 裸 `{ok:true}`(8 处)、REST
 * `{ok:true,message}`(7 处)与 WS `{type:configUpdateFailed,message}`
 * (WsConfigHandlers 8 处)。边界(镜像 WsDispatch.InboundEnvelope 先例;
 * 各保留站点有一行注释):
 *  - 只构 Json body,**绝不**构 http4s Response——状态码留在站点(NeblinkRoutes
 *    的 ok=false 族是 Ok(200)+ok=false 的既有客户端可见契约,顺手统一即改契约);
 *  - ok=false/error 族、ok+code 族(SocialRoutes 附件闸,单列不合并)、
 *    ok+业务字段族(ok+path+size、ok+networkId 等)与 success±message 族
 *    (neblink update/remote-update)形态互异,一律保持手写;
 *  - WS 其余具名 type 帧(configUpdated/probeResult/cancelAgentResult 等各有
 *    专属 type+载荷)非信封族,不经本助手。
 */
private[gateway] object ApiJson:

  /** REST 裸成功信封 `{ok:true}`(收敛前 8 处逐字同形)。 */
  private[gateway] val ok: Json = Json.obj("ok" -> true.asJson)

  /** REST 成功信封 `{ok:true,message:<string>}`(收敛前 7 处同形,字段序一致)。 */
  private[gateway] def okMessage(message: String): Json =
    Json.obj("ok" -> true.asJson, "message" -> message.asJson)

  /** WS 配置域失败帧 `{type:configUpdateFailed,message:<string>}`(收敛前 8 处同形)。 */
  private[gateway] def configUpdateFailed(message: String): Json =
    Json.obj("type" -> "configUpdateFailed".asJson, "message" -> message.asJson)

end ApiJson

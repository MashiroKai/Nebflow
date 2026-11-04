package nebflow.pilogic

import io.circe.Json

/** 模型边界（设计 §Ⅳ Provider.scala）：ProviderPort[F]、请求/响应/流块、AbortSignal[F]。
  * llm 适配经接线层实现进来——pilogic 禁反向依赖 `nebflow.llm.*`。
  */

/** 停止原因（对位 pi ai/types.ts:443 七值穷举）。
  * 契约（§Ⅱ.4(2)，照抄 pi StreamFn 契约 types.ts:26-31）：中断编码为 Aborted 而【非】抛异常；
  * error|aborted 是硬退出路径——此路径不读 finishTurn 返回值。 */
enum StopReason:
  case Pending
  case Stop
  case Length
  case ToolUse
  case Error
  case Aborted
  case Deferred

/** 中止信号：贯穿 provider 流与工具执行（实现归装配层；循环内 5 处检查点对位 pi :572/:610/:638/:732/:751）。 */
trait AbortSignal[F[_]]:
  def aborted: F[Boolean]

/** 请求装配产物（prepareRequest 面：不可追加消息的约束属 16 签名全量轮）。 */
final case class ProviderRequest(
    model: String,
    messages: Vector[LoopMessage],
    options: Json,
)

/** 应答（message 定稿 + 停止原因）。 */
final case class ProviderResponse(
    message: LoopMessage,
    stopReason: StopReason,
)

/** 流式块（ProviderChunked 观察位载荷形状；L-11 背压档分位点声明属下一增量轮）。 */
final case class ProviderChunk(delta: Json)

/** 模型边界端口：接线层把现有 llm adapter 适配到此处，循环只认此口。 */
trait ProviderPort[F[_]]:
  def stream(req: ProviderRequest, signal: AbortSignal[F]): F[ProviderResponse]

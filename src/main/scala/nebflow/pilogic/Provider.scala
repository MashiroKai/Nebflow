package nebflow.pilogic

import io.circe.Json

/**
 * 模型边界（设计 §Ⅳ Provider.scala）：ProviderPort[F]、请求/响应/流块、AbortSignal[F]。
 * llm 适配经接线层实现进来——pilogic 禁反向依赖 `nebflow.llm.*`。
 */

/**
 * 停止原因（对位 pi ai/types.ts:443 七值穷举）。
 * 契约（§Ⅱ.4(2)，照抄 pi StreamFn 契约 types.ts:26-31）：中断编码为 Aborted 而【非】抛异常；
 * error|aborted 是硬退出路径——此路径不读 finishTurn 返回值。
 */
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

/**
 * 请求装配产物（prepareRequest 面；#64「不可追加消息」约束由循环在 providerPort.Payload
 * 位强制：整块替换不得改变消息条数）。system=system 消息内容（null=无）；headers=provider
 * 边界头表（JsonObject，#16 位改写对象）。
 */
final case class ProviderRequest(
  model: String,
  system: Option[Json],
  messages: Vector[LoopMessage],
  options: Json,
  headers: Json
)

/** 应答（message 定稿 + 停止原因 + 抽取好的工具调用——stopReason=ToolUse 时非空）。 */
final case class ProviderResponse(
  message: LoopMessage,
  stopReason: StopReason,
  toolCalls: Vector[ToolCall] = Vector.empty
)

/** 流式块（ProviderChunked 观察位载荷形状；L-11 拍板=该位点 Dropping 丢弃档）。 */
final case class ProviderChunk(delta: Json)

/**
 * provider 边界产物：有序块序列 + 终响应（16 签名增量轮形状——适配层内部真流式，
 * 循环侧契约=有序块+终态；fs2 升级为接线期裁量，不焊进本面）。
 */
final case class ProviderStream(
  chunks: Vector[ProviderChunk] = Vector.empty,
  response: ProviderResponse
)

/**
 * 模型边界端口：接线层把现有 llm adapter 适配到此处，循环只认此口。
 * 流式腿契约：返回有序块序列+终响应；循环逐块转发（message.Delta 事件+providerPort.Chunked
 * 观察位）后消费终响应。中止编码为 stopReason=Aborted（契约同上）；抛出异常=可重试失败
 * （RetryPolicy 管），与 stopReason=Error（provider 自报失败、硬退出不重试）分道。
 */
trait ProviderPort[F[_]]:
  def stream(req: ProviderRequest, signal: AbortSignal[F]): F[ProviderStream]

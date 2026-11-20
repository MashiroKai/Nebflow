package nebflow.pilogic

import io.circe.Json
import io.circe.syntax.*

/**
 * 工具面（设计 §Ⅳ 数据谁持有：工具是数据 N3；执行器由接线层注入——pilogic 只管
 * 批编排、L-5 重校验与 terminate 语义）。
 *
 * == L-5 落点（ToolSpec.validateArgs） ==
 * ToolPre 改参后的重校验用 ToolSet.specs 内该工具的 validateArgs；无 spec ⇒ 该工具
 * 声明为不可校验（原始参与改参同权放行——校验是逐工具显式能力，不是引擎内建）。
 */

/** 一次工具调用（provider 抽取形；args=模型自传参 JSON）。 */
final case class ToolCall(callId: String, name: String, args: Json)

object ToolCall:

  def toJson(c: ToolCall): Json = Json.obj(
    "callId" -> c.callId.asJson,
    "name" -> c.name.asJson,
    "args" -> c.args
  )

/** 工具执行结果（isError=true = 错误结果仍是结果——不进 F 错误通道）。 */
final case class ToolExecutionResult(output: Json, isError: Boolean)

object ToolExecutionResult:
  def ok(output: Json): ToolExecutionResult = ToolExecutionResult(output, isError = false)

  def error(message: String): ToolExecutionResult =
    ToolExecutionResult(Json.fromString(message), isError = true)

  def toJson(call: ToolCall, r: ToolExecutionResult): Json = Json.obj(
    "callId" -> call.callId.asJson,
    "name" -> call.name.asJson,
    "output" -> r.output,
    "isError" -> r.isError.asJson
  )

/** 工具规约（名字 + 参数校验件——L-5 重校验的校验来源；由装配层随 ToolSet 注入）。 */
final case class ToolSpec(
  name: String,
  validateArgs: Json => Either[String, Unit]
)

/**
 * 工具执行器端口（接线层注入）：执行本体不在 pilogic。
 *
 * 契约：
 *   - onDelta = 进度回调（循环据此发 ToolExecutionDelta 事件 + 分派观察位）；
 *   - 中止：实现应在 signal.aborted 后尽快返回 isError=true 的中止结果（不抛异常）；
 *   - 灾难性失败可抛——循环按工具错误结果收编（isError=true，错误文案=异常消息），
 *     不进 run 级错误通道（一个工具炸不炸整轮由 terminate 语义决定，不由异常决定）。
 */
trait ToolExecutor[F[_]]:
  def execute(call: ToolCall, signal: AbortSignal[F], onDelta: Json => F[Unit]): F[ToolExecutionResult]

object ToolExecutor:

  /** 全部调用恒失败的占位执行器（测试/装配前缺省）。 */
  def failing[F[_]: cats.Applicative](message: String): ToolExecutor[F] =
    (_, _, _) => cats.Applicative[F].pure(ToolExecutionResult.error(message))

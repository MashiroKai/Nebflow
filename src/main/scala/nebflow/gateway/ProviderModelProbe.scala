/* settings-panel-jev-imagegen 批（2026-10-28）——模型连接性测试探针（B 件）。 */
package nebflow.gateway

import io.circe.Json
import io.circe.syntax.*
import nebflow.neblink.OutboundHttpClients
import nebflow.shared.LlmProtocol

import java.util.concurrent.atomic.AtomicBoolean

/**
 * 每模型连接性测试（作者令 2026-10-28 21:38 B 件：「在 provider 每一个模型旁边，
 * 加一个小按钮，用于连接性测试，测试模型是否可用」）——设置页模型行尾测试钮的
 * 引擎侧最小真实调用。
 *
 * 形态照 [[ProviderProbe]]（模型列举探针）同族：JDK HttpClient
 * （`OutboundHttpClients.Policy.SystemProxy10s`——HTTP/1.1 强制 + 系统代理，
 * 与真实补全同网络路径）、按协议面拼端点与鉴权头。**刻意不取道
 * ProviderRegistry/适配器流式栈**：诊断一击不得污染 HealthMonitor 的
 * UP/DOWN 台账，也不为 1 token 拉起整套消息机器。
 *
 * 🔴 有界等待（本批任务书明定）：20s 操作超时——这是**诊断操作超时**，属显式
 * 有界面，不属「设计逻辑零超时」纪律范畴（该纪律管业务链路不设 wall-clock，
 * 诊断操作无界 = 按钮永久转圈，正是纪律要防的用户面事故）。
 *
 * 🔴 脱敏纪律：错误文案在离开本对象前经 [[sanitize]] 把 key 值的任何出现
 * 抹成 `***`；Authorization/x-api-key 头永不进日志/错误面（本对象零日志）。
 */
private[gateway] object ProviderModelProbe:

  /** 有界诊断超时（20s 量级，任务书 B 件 15–30s 区间中值）。 */
  val TimeoutMs: Long = 20_000

  sealed trait Verdict extends Product with Serializable

  object Verdict:
    final case class Ok(latencyMs: Long, replyPreview: String) extends Verdict
    final case class Failed(detail: String) extends Verdict

  /**
   * POST target for the minimal test call — same normalization as the two
   * adapters (AnthropicAdapter.scala:31-33 `{base}/v1/messages`,
   * OpenAiAdapter.scala:49-50 `{base}/chat/completions`).
   */
  def endpointFor(protocol: LlmProtocol, baseUrl: String): String =
    val base = baseUrl.trim.replaceAll("/+$", "")
    protocol match
      case LlmProtocol.Anthropic => if base.endsWith("/v1/messages") then base else s"$base/v1/messages"
      case LlmProtocol.OpenAI => if base.endsWith("/chat/completions") then base else s"$base/chat/completions"

  /**
   * Minimal 1-token request body per protocol face (tens of tokens of real
   * quota — the bounded cost an author-approved diagnosis may spend).
   */
  def requestBody(protocol: LlmProtocol, model: String): Json =
    val messages = Json.arr(Json.obj("role" -> "user".asJson, "content" -> "ping".asJson))
    protocol match
      case LlmProtocol.Anthropic =>
        Json.obj("model" -> model.asJson, "max_tokens" -> 1.asJson, "messages" -> messages)
      case LlmProtocol.OpenAI =>
        Json.obj("model" -> model.asJson, "max_tokens" -> 1.asJson, "messages" -> messages)

  /** Protocol auth headers (mirrors the adapters / ProviderProbe posture). */
  def authHeaders(protocol: LlmProtocol, apiKey: String): List[(String, String)] =
    protocol match
      case LlmProtocol.Anthropic =>
        List("x-api-key" -> apiKey, "anthropic-version" -> "2023-06-01")
      case LlmProtocol.OpenAI =>
        List("Authorization" -> s"Bearer $apiKey")

  /**
   * Scrub any occurrence of the secret material out of user-facing text
   * (defense in depth: provider error bodies occasionally echo request
   * material). Empty secret = identity.
   */
  def sanitize(text: String, secrets: String*): String =
    secrets.foldLeft(text) { (acc, s) =>
      if s.trim.isEmpty then acc else acc.replace(s, "***")
    }

  /**
   * The one-shot probe. Runs the blocking HTTP call (caller wraps in
   * IO.blocking / timeout). `Verdict.Failed` detail is ALREADY sanitized
   * against `apiKey`; transport errors carry exception class + message only.
   * Re-entry is allowed but pointless (each call is independent); `inFlight`
   * is exposed for the route's double-click guard.
   */
  def call(
    protocol: LlmProtocol,
    baseUrl: String,
    apiKey: String,
    model: String
  ): Verdict =
    val started = System.currentTimeMillis()
    val url = endpointFor(protocol, baseUrl)
    try
      val builder = java.net.http.HttpRequest
        .newBuilder()
        .uri(java.net.URI.create(url))
        .timeout(java.time.Duration.ofMillis(TimeoutMs))
        .header("Content-Type", "application/json")
        .POST(java.net.http.HttpRequest.BodyPublishers.ofString(requestBody(protocol, model).noSpaces))
      authHeaders(protocol, apiKey).foreach { case (k, v) => builder.header(k, v) }
      val client = OutboundHttpClients.client(OutboundHttpClients.Policy.SystemProxy10s)
      val response = client.send(builder.build(), java.net.http.HttpResponse.BodyHandlers.ofString())
      val latency = System.currentTimeMillis() - started
      val body = Option(response.body()).getOrElse("")
      val status = response.statusCode()
      if status >= 200 && status < 300 then
        // 2xx = the model answered. A provider-side error envelope under 200
        // still counts as reachable-but-failing: surface it as a failure with
        // the provider's own message (zhipu 200-carrier precedent).
        ProviderProbe.errEnvelopeMessage(body) match
          case Some(msg) => Verdict.Failed(sanitize(s"HTTP $status: $msg", apiKey))
          case None => Verdict.Ok(latency, sanitize(body.take(120), apiKey))
      else
        val detail = ProviderProbe.errBodyMessage(body).getOrElse(body.take(200))
        Verdict.Failed(sanitize(s"HTTP $status: $detail", apiKey))
    catch
      case e: java.net.http.HttpTimeoutException =>
        Verdict.Failed(s"timeout after ${TimeoutMs}ms (bounded diagnosis wait)")
      case e: InterruptedException =>
        Thread.currentThread().interrupt()
        Verdict.Failed("interrupted")
      case e: Exception =>
        Verdict.Failed(sanitize(Option(e.getMessage).filter(_.nonEmpty).getOrElse(e.getClass.getSimpleName), apiKey))

    end try

  end call

  /** Per-provider one-flight guard for the route (double-click). */
  final class Gate:
    private val busy = AtomicBoolean(false)
    def tryAcquire(): Boolean = busy.compareAndSet(false, true)
    def release(): Unit = busy.set(false)
end ProviderModelProbe

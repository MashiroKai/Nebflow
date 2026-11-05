/* settings-panel-jev-imagegen 批 ②件（2026-10-29 作者令逐字：「然后 Jev/生图，
 * 也要像我们LLM这样，有个按钮来测试可用性」）——JeV 分配通道连接性探针。
 *
 * 形态照 [[ProviderModelProbe]] 同族：有界诊断超时（20s——诊断操作超时属显式
 * 有界面，同 B 件口径；业务链路零超时纪律不适用）、错误文案离手前脱敏
 * （token 任何出现抹成 ***）、单飞闸由路由侧持有（复用 ProviderModelProbe.Gate）。
 *
 * 探活分级（任务书口径「探活优先 metadata/models 类调用，无则极小补全」）：
 *   · laya     → metadata 腿优先：GET {endpoint /predict → /health}
 *                （LayaProvider.probeHealth 同款推导，卡 §A.2.3 官方探活面）；
 *   · typesafe-jev → 线协议无 metadata 面（wire contract 只有一个 POST 端点）
 *                → 极小真实补全：1 个 noul 问题（"ping"），几十 token 级配额
 *                （ProviderModelProbe 的 1-token 成本口径同源）。
 *
 * 🔴 刻意不取道 TypesafeJevProvider/LayaProvider 实例栈：诊断一击不污染
 * DecisionHealthMonitor 台账、不拉起判定机（ProviderModelProbe 同款裁定）。
 */
package nebflow.gateway

import io.circe.Json
import io.circe.syntax.*
import nebflow.llm.decision.{DecisionProvider, JevWireCodec}
import nebflow.shared.{PathUtil, SharedBackend}
import sttp.client4.*

import scala.concurrent.duration.*

private[gateway] object JevProbe:

  /** 有界诊断超时（ProviderModelProbe.TimeoutMs 同档）。 */
  val TimeoutMs: Long = 20_000

  sealed trait Verdict extends Product with Serializable
  object Verdict:
    final case class Ok(latencyMs: Long, replyPreview: String) extends Verdict
    final case class Failed(detail: String) extends Verdict

  /** laya 的 metadata 探活端点（/predict → /health，LayaProvider.probeHealth 同款）。 */
  def healthEndpointFor(endpoint: String): String =
    endpoint.replaceAll("/predict/?$", "/health")

  /**
   * typesafe 极小补全体（wire contract 钉形，JevWireCodec.scala:16-24）：
   * 1 个 noul 问题 = 该端点接受的最小真实问题集。零凭据材料进 body。
   */
  def minimalRequestBody(model: String): Json =
    Json.obj(
      "state" -> "ping".asJson,
      "model" -> model.asJson,
      "questions" -> Json.obj(
        "q1" -> Json.obj("type" -> "noul".asJson, "instructions" -> "ping".asJson)
      )
    )

  /** 脱敏（ProviderModelProbe.sanitize 同款防回显面）。 */
  def sanitize(text: String, secrets: String*): String =
    secrets.foldLeft(text) { (acc, s) =>
      if s.trim.isEmpty then acc else acc.replace(s, "***")
    }

  /** keyRef → token（JevSettings 的 secrets 现读口径：存在且非空才算在位）。 */
  def secretToken(keyRef: Option[String]): Option[String] =
    keyRef.filter(_.trim.nonEmpty).flatMap { name =>
      try
        val p = PathUtil.dataRoot / "secrets" / name.trim
        if !os.exists(p) then None
        else
          val v = os.read(p).trim
          if v.isEmpty then None else Some(v)
      catch case _: Exception => None
    }

  /**
   * 探活路由的 token 解析（jev-imagegen-align-llm 批 2026-11-02 契约 §I-4 方案 A
   * ——**与 [[nebflow.llm.imagegen.ImageGenProbe.resolveToken]] 同源的三腿模型**）：
   *
   *   ① 表单 **`apiKey` 覆写**：设置面已删掉 keyRef UI，用户在卡片上直接填值、
   *      不必先保存就能点「测试」⇒ 覆写腿最优先（生图面同判据）；
   *   ② **已存 keyRef** 指向的 secret 现读（旧配置/旧 UI 的腿，逐字保留）；
   *   ③ **无**（keyRef 未配置）= 不期望凭据的形态 ⇒ 空 token 直发（laya 本地
   *      端点同款）——`Right("")` 表达之，`call` 对它不加 Authorization 头。
   *
   * `Left` = 配置了 keyRef 但 secret 缺席（「待录入」语义，jev 四修③ 的腿关判据
   * 同源）——消息原样进 ❌ 文案，**只含 keyRef 名，绝不含值**。
   */
  def resolveToken(apiKeyOverride: Option[String], storedKeyRef: Option[String]): Either[String, String] =
    apiKeyOverride.map(_.trim).filter(_.nonEmpty) match
      case Some(k) => Right(k)
      case None =>
        storedKeyRef.filter(_.trim.nonEmpty) match
          case Some(name) =>
            secretToken(Some(name)) match
              case Some(token) => Right(token)
              case None =>
                Left(s"keyRef '$name' resolves to no readable secret (expected at <dataRoot>/secrets/$name)")
          case None => Right("")

  /**
   * 探活入口（路由用，覆写腿优先）：`apiKey` 覆写 > 已存 keyRef secret > 无。
   * 三腿解析单点在 [[resolveToken]]；本函数只把解析结果接到 [[call]]。
   */
  def callWithToken(
    provider: String,
    endpoint: String,
    model: String,
    apiKeyOverride: Option[String],
    storedKeyRef: Option[String]
  ): Verdict =
    resolveToken(apiKeyOverride, storedKeyRef) match
      case Right(token) => call(provider, endpoint, model, token)
      case Left(err)    => Verdict.Failed(err)

  /**
   * 探活入口（旧调用形状，行为逐字保留）：keyRef 在位性先判——配置了 keyRef 而
   * secret 缺席 = 直接 Failed（「待录入」语义，jev 四修③的腿关判据同源），不发
   * 注定 401 的 HTTP 击；keyRef 未配置 = 不期望凭据的形态（token 空直发，laya
   * 本地端点同款）。实现收敛到 [[callWithToken]]（无覆写腿），断言与文案不变。
   */
  def callWithKeyRef(provider: String, endpoint: String, model: String, keyRef: Option[String]): Verdict =
    callWithToken(provider, endpoint, model, None, keyRef)

  /**
   * 一击探活。`provider` ∈ DecisionProvider.AllIds（路由侧已校验）。
   * 失败 detail 已脱敏（token 任何出现 = ***）；HTTP 状态码原样保留
   * （作者令口径：状态码要原样可见——401 = 厂家侧死 key 直接可读）。
   */
  def call(provider: String, endpoint: String, model: String, token: String): Verdict =
    val started = System.currentTimeMillis()
    try
      val base = provider match
        case DecisionProvider.Laya =>
          basicRequest.get(uri"${healthEndpointFor(endpoint)}")
        case _ =>
          basicRequest
            .post(uri"$endpoint")
            .header("Content-Type", "application/json")
            .body(minimalRequestBody(model).noSpaces)
      val request =
        if token.trim.isEmpty then base.readTimeout(TimeoutMs.millis)
        else base.header("Authorization", s"Bearer $token").readTimeout(TimeoutMs.millis)
      val resp = request.response(asStringAlways).send(SharedBackend.instance)
      val latency = System.currentTimeMillis() - started
      val body = resp.body
      val status = resp.code.code
      if status >= 200 && status < 300 then
        provider match
          case DecisionProvider.Laya =>
            // health 面无 answers 壳——2xx 即可用，body 片段作预览。
            Verdict.Ok(latency, sanitize(body.take(120), token))
          case _ =>
            JevWireCodec.parseResponse(body) match
              case Right(parsed) =>
                Verdict.Ok(latency, sanitize(s"model=${parsed.model.getOrElse("?")}", token))
              case Left(err) =>
                Verdict.Failed(sanitize(s"HTTP $status: response unusable (${err.message})", token))
      else
        Verdict.Failed(sanitize(s"HTTP $status: ${body.take(200)}", token))
    catch
      case e: InterruptedException =>
        Thread.currentThread().interrupt()
        Verdict.Failed("interrupted")
      case e: Exception =>
        val msg = Option(e.getMessage).filter(_.nonEmpty).getOrElse(e.getClass.getSimpleName)
        val lower = msg.toLowerCase
        if lower.contains("timed out") || lower.contains("timeout") then
          Verdict.Failed(s"timeout after ${TimeoutMs}ms (bounded diagnosis wait)")
        else Verdict.Failed(sanitize(msg.take(300), token))

  /** 单飞闸（路由侧双击防线——ProviderModelProbe.Gate 逐字复用）。 */
  final class Gate:
    private val busy = new java.util.concurrent.atomic.AtomicBoolean(false)
    def tryAcquire(): Boolean = busy.compareAndSet(false, true)
    def release(): Unit = busy.set(false)
end JevProbe

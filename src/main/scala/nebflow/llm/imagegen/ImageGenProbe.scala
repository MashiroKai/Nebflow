/* settings-panel-jev-imagegen 批 ③件（2026-10-29 作者令逐字：「然后 Jev/生图，
 * 也要像我们LLM这样，有个按钮来测试可用性」）——生图 provider 两级探针。
 *
 * 两级（任务书口径）：
 *   a) 轻探活（默认）：GET {base}/models（OpenAI images / cogview / Gemini
 *      三协议共有的 metadata 面）+ 适配器鉴权头——key 在位性 + 上游可达 +
 *      鉴权真伪（401/403 = 厂家侧死 key，状态码原样可见）。models 面缺失
 *      （404/405）= 上游可达但该面未提供：判「可达」，modelsFaceAbsent 带回
 *      给前端转译「以实跑生成为准」——不谎报鉴权已验证。
 *   b) 实跑一次最小生成：size 最小（256x256，适配器口径内吸附）、质量最低
 *      （quality=low，ImageGenRequest 自由字段——适配器今日不消费，预留），
 *      真 POST 生成端点 → 真图字节。产物落**临时目录**（os.temp.dir，系统
 *      临时区 nebflow-imagegen-test-* 前缀），绝不进 <dataRoot>/imagegen 正式归档。
 *
 * 🔴 归一判据与 LLM 测试钮同源：2xx 且产物可用 = 可用；非 2xx = Failed 带
 * `HTTP <code>: <摘要>`（状态码原样保留）；错误文案离手前脱敏（token → ***）。
 * 🔴 有界诊断超时：轻探活 20s；实跑生成 120s（生成本身是分钟内操作——
 * ImageGenService.DefaultTimeoutMs 同档，诊断不许永久转圈）。
 *
 * 可见性：object 本体放公开位——唯一跨包调用方 = gateway 的探活路由
 * （PresenceRoutes POST /imagegen/test），而 materialize 复用要求本件留在
 * imagegen 包内（ProviderModelProbe 的 private[gateway] 形态在这里行不通）。
 */
package nebflow.llm.imagegen

import io.circe.parser.parse
import nebflow.llm.imagegen.ImageGenTypes.*
import nebflow.shared.SharedBackend
import sttp.client4.*

import java.util.Base64
import scala.concurrent.duration.*

object ImageGenProbe:

  /** 轻探活有界超时。 */
  val ProbeTimeoutMs: Long = 20_000

  /** 实跑生成有界超时（生图腿真实超时同档——诊断不比业务腿更窄）。 */
  val GenTimeoutMs: Long = ImageGenService.DefaultTimeoutMs

  /** 缩略预览的数据 URL 上限（超过只报字节数/格式，不带预览）。 */
  val PreviewCapBytes: Int = 4 * 1024 * 1024

  sealed trait Verdict extends Product with Serializable
  object Verdict:
    /** 轻探活可用。`modelsFaceAbsent` = models 面缺失（HTTP 404/405）时携带
      * 该状态码——上游可达为真，鉴权未经验证为真，前端如实转译。 */
    final case class Ok(latencyMs: Long, modelsFaceAbsent: Option[Int] = None) extends Verdict
    /** 实跑生成可用：真实字节数 + 真实格式 + 临时目录产物路径 + 可选预览。 */
    final case class Generated(latencyMs: Long, bytes: Int, ext: String, tempPath: String, dataUrl: Option[String]) extends Verdict
    final case class Failed(detail: String) extends Verdict

  /** 脱敏（ProviderModelProbe.sanitize 同款防回显面，gateway 包外自持一份）。 */
  def sanitize(text: String, secrets: String*): String =
    secrets.foldLeft(text) { (acc, s) =>
      if s.trim.isEmpty then acc else acc.replace(s, "***")
    }

  private def trimBase(baseUrl: String): String = baseUrl.trim.replaceAll("/+$", "")

  def mimeOf(ext: String): String = ext match
    case "png"  => "image/png"
    case "jpeg" => "image/jpeg"
    case "webp" => "image/webp"
    case "gif"  => "image/gif"
    case other  => s"image/$other"

  /**
   * a) 轻探活一击：GET {base}/models + 适配器鉴权头。detail 已脱敏；
   * HTTP 状态码原样进 detail（401 = 死 key 直接可读）。
   */
  def lightCall(adapter: ImageGenAdapter, baseUrl: String, token: String): Verdict =
    val started = System.currentTimeMillis()
    try
      val (hName, hValue) = adapter.authHeader(token)
      val request = basicRequest
        .get(uri"${trimBase(baseUrl)}/models")
        .header(hName, hValue)
        .readTimeout(ProbeTimeoutMs.millis)
        .response(asStringAlways)
      val resp = request.send(SharedBackend.instance)
      val latency = System.currentTimeMillis() - started
      val status = resp.code.code
      val body = resp.body
      if status >= 200 && status < 300 then Verdict.Ok(latency)
      else if status == 404 || status == 405 then Verdict.Ok(latency, modelsFaceAbsent = Some(status))
      else Verdict.Failed(sanitize(s"HTTP $status: ${body.take(200)}", token))
    catch
      case e: InterruptedException =>
        Thread.currentThread().interrupt()
        Verdict.Failed("interrupted")
      case e: Exception =>
        val msg = Option(e.getMessage).filter(_.nonEmpty).getOrElse(e.getClass.getSimpleName)
        val lower = msg.toLowerCase
        if lower.contains("timed out") || lower.contains("timeout") then
          Verdict.Failed(s"timeout after ${ProbeTimeoutMs}ms (bounded diagnosis wait)")
        else Verdict.Failed(sanitize(msg.take(300), token))

  /** b) 的产物落盘（系统临时目录，一次探活一个独立子目录）——单独成函数，
    * spec 钉「不污染归档」。 */
  def parkTempArtifact(bytes: Array[Byte], ext: String): os.Path =
    val dir = os.temp.dir(prefix = "nebflow-imagegen-test-")
    val target = dir / s"probe-${bytes.length}.$ext"
    os.write(target, bytes)
    target

  /** data URL（≤ PreviewCapBytes 才带预览——大图只报字节/格式）。 */
  def dataUrlOf(bytes: Array[Byte], ext: String): Option[String] =
    if bytes.length > PreviewCapBytes then None
    else Some(s"data:${mimeOf(ext)};base64,${Base64.getEncoder.encodeToString(bytes)}")

  /**
   * 探活路由的 token 解析（凭据面无感化同源——前端永不知道 keyRef 名）：
   * 表单覆写 key > 存储 keyRef > 派生名 `imagegen-<name>-key`
   * （imagegenSection.keyRefFor 前端派生口径的引擎侧镜像，永不漂移）。
   * Left = key 在位性不满足（MissingCredential 消息原样进 ❌ 文案）。
   */
  def resolveToken(
    apiKeyOverride: Option[String],
    storedKeyRef: Option[String],
    providerName: String
  ): Either[String, String] =
    apiKeyOverride.map(_.trim).filter(_.nonEmpty) match
      case Some(k) => Right(k)
      case None =>
        val keyRef = storedKeyRef.filter(_.trim.nonEmpty).orElse(Some(s"imagegen-$providerName-key"))
        ImageGenService.readSecret(keyRef) match
          case Right(token) => Right(token)
          case Left(err)    => Left(err.message)

  /**
   * b) 实跑一次最小生成：真 POST 生成端点 → 真图字节 → 临时目录落盘 →
   * 产物路径 + dataUrl 随判决返回。body 由既有适配器纯函数拼装（零协议
   * 第二拼写点）；落盘只在 os.TempDir，绝不写 <dataRoot>/imagegen。
   */
  def generateCall(
    adapter: ImageGenAdapter,
    baseUrl: String,
    model: String,
    token: String
  ): Verdict =
    val started = System.currentTimeMillis()
    val resolved = ResolvedProvider(
      name = "imagegen-test",
      adapter = adapter,
      baseUrl = trimBase(baseUrl),
      model = model,
      token = token,
      timeoutMs = GenTimeoutMs
    )
    val body = adapter.buildBody(model, ImageGenRequest(prompt = "ping", size = Some("256x256"), quality = Some("low")))
    try
      val (hName, hValue) = adapter.authHeader(token)
      val request = basicRequest
        .post(uri"${ImageGenAdapters.endpointFor(adapter, resolved.baseUrl, model)}")
        .header(hName, hValue)
        .header("Content-Type", "application/json")
        .body(body.noSpaces)
        .readTimeout(GenTimeoutMs.millis)
        .response(asStringAlways)
      val resp = request.send(SharedBackend.instance)
      val latency = System.currentTimeMillis() - started
      val status = resp.code.code
      val outcome: Either[String, Verdict.Generated] =
        if !(status >= 200 && status < 300) then
          Left(s"HTTP $status: ${resp.body.take(200)}")
        else
          parse(resp.body) match
            case Left(_) =>
              Left(s"HTTP $status: non-JSON body (${resp.body.take(120)})")
            case Right(json) =>
              adapter.parseResult(json) match
                case Left(err) => Left(s"HTTP $status: ${err.message}")
                case Right(ref) =>
                  ImageGenService.materialize(ref, resolved) match
                    case Left(err) => Left(err.message)
                    case Right(bytes) =>
                      val ext = ImageGenService.sniffExt(bytes)
                      val path = parkTempArtifact(bytes, ext)
                      Right(Verdict.Generated(latency, bytes.length, ext, path.toString, dataUrlOf(bytes, ext)))
      outcome match
        case Left(detail)  => Verdict.Failed(sanitize(detail, token))
        case Right(gen)    => gen
    catch
      case e: InterruptedException =>
        Thread.currentThread().interrupt()
        Verdict.Failed("interrupted")
      case e: Exception =>
        val msg = Option(e.getMessage).filter(_.nonEmpty).getOrElse(e.getClass.getSimpleName)
        Verdict.Failed(sanitize(msg.take(300), token))

  /** 单飞闸（路由侧双击防线）。 */
  final class Gate:
    private val busy = new java.util.concurrent.atomic.AtomicBoolean(false)
    def tryAcquire(): Boolean = busy.compareAndSet(false, true)
    def release(): Unit = busy.set(false)
end ImageGenProbe

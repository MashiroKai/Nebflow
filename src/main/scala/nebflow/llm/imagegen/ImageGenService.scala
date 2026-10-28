package nebflow.llm.imagegen

import cats.effect.IO
import io.circe.parser.parse
import nebflow.llm.imagegen.ImageGenTypes.*
import nebflow.llm.imagegen.ImageGenTypes.ImageGenError as E
import nebflow.shared.{Config, NebflowLogger, PathUtil, SharedBackend}
import sttp.client4.*

import java.util.Base64
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/**
 * 生图服务层（imagegen 双层批 2026-10-24）——配置解析（热读）+ 适配器分发 +
 * HTTP + URL 下载/ base64 解码 + PNG 落盘。腿①（内置工具，非阻塞 job 的生成段）
 * 与腿②（本机 `/api/imagegen` 路由，阻塞）共用本层，一行 provider 代码不重复。
 *
 * 凭据纪律（jev/embedding 同先例）：`keyRef` = 数据根 secrets/ 下的名字；
 * token 只进请求 header，永不写日志、永不进任何返回值。
 */
object ImageGenService:

  private val logger = NebflowLogger.forName("nebflow.imagegen")

  val DefaultTimeoutMs: Long = 120_000

  // ── 配置解析（热读，每次调用现读——JevSettings 同款口径）───────────────────

  /**
   * 解析出生成一击所需的 provider。`Left` = 本击不应发生（面关/无此 provider/
   * 凭据缺席——凭据缺席即腿自动关，jev 四修③同口径，WARN 落案）。
   */
  def resolve(req: ImageGenRequest, configPath: Option[String] = None): Either[ImageGenError, ResolvedProvider] =
    val cfg = Config.loadServiceConfig(configPath).imagegen
    cfg match
      case None => Left(E.Disabled)
      case Some(c) if !c.isEnabled => Left(E.Disabled)
      case Some(c) =>
        val name = req.provider.orElse(c.defaultProvider).getOrElse("")
        c.providers.getOrElse(Map.empty).get(name) match
          case None => Left(if name.isEmpty then E.Disabled else E.UnknownProvider(name))
          case Some(pc) =>
            ImageGenAdapters.byType(pc.`type`) match
              case None => Left(E.UnknownProvider(s"$name (type '${pc.`type`}' has no adapter)"))
              case Some(adapter) =>
                readSecret(pc.keyRef) match
                  case Left(err) => Left(err)
                  case Right(token) =>
                    Right(
                      ResolvedProvider(
                        name = name,
                        adapter = adapter,
                        baseUrl = pc.baseUrl.filter(_.nonEmpty).getOrElse(adapter.defaultBaseUrl),
                        model = pc.model,
                        token = token,
                        timeoutMs = pc.timeoutMs.getOrElse(DefaultTimeoutMs)
                      )
                    )

  /** keyRef 未配置 = 不期望凭据的形态（缺省空 token 直传——本地网关类端点）；配置了但缺件/空/不可读 = 腿关。 */
  private[imagegen] def readSecret(keyRef: Option[String]): Either[ImageGenError, String] =
    keyRef.filter(_.trim.nonEmpty) match
      case None => Right("")
      case Some(name) =>
        val path = PathUtil.dataRoot / "secrets" / name.trim
        try
          if !os.exists(path) then
            logger.warnSync(s"imagegen keyRef '$name' has no secret file — provider leg closed")
            Left(E.MissingCredential(name, Some(name)))
          else
            val v = os.read(path).trim
            if v.isEmpty then
              logger.warnSync(s"imagegen keyRef '$name' resolves to an empty secret — provider leg closed")
              Left(E.MissingCredential(name, Some(name)))
            else Right(v)
        catch
          case e: Exception =>
            logger.warnSync(s"imagegen keyRef '$name' unreadable (${e.getClass.getSimpleName}) — provider leg closed")
            Left(E.MissingCredential(name, Some(name)))

  // ── 生成一击 ──────────────────────────────────────────────

  /** 同步 HTTP 壳（SharedBackend 连接池），包进 IO.blocking 由调用方调度。 */
  private def httpPostJson(
    resolved: ResolvedProvider,
    body: io.circe.Json
  ): Either[ImageGenError, io.circe.Json] =
    val (hName, hValue) = resolved.adapter.authHeader(resolved.token)
    val request = basicRequest
      .post(uri"${ImageGenAdapters.endpointFor(resolved.adapter, resolved.baseUrl, resolved.model)}")
      .header(hName, hValue)
      .header("Content-Type", "application/json")
      .body(body.noSpaces)
      .readTimeout(resolved.timeoutMs.millis)
    try
      val response = request.send(SharedBackend.instance)
      response.body match
        case Left(errBody) => Left(E.HttpStatus(resolved.name, response.code.code, errBody))
        case Right(text) =>
          parse(text) match
            case Left(_)       => Left(E.BadResponse(resolved.name, s"non-JSON body (${text.take(120)})"))
            case Right(json)   => Right(json)
    catch
      case e: sttp.client4.SttpClientException =>
        Left(E.Transport(resolved.name, Option(e.getMessage).getOrElse(e.getClass.getSimpleName).take(300)))
      case e: Exception =>
        Left(E.Transport(resolved.name, Option(e.getMessage).getOrElse(e.getClass.getSimpleName).take(300)))

  private def httpGetBytes(url: String, timeoutMs: Long): Either[ImageGenError, Array[Byte]] =
    try
      val response = basicRequest.get(uri"$url").readTimeout(timeoutMs.millis).response(asByteArrayAlways).send(SharedBackend.instance)
      if response.code.isSuccess then Right(response.body)
      else Left(E.HttpStatus("download", response.code.code, s"image URL fetch failed for $url"))
    catch
      case e: Exception => Left(E.Transport("download", Option(e.getMessage).getOrElse(e.getClass.getSimpleName).take(300)))

  /** b64/url 引用统一收束为 PNG 字节（空/超帽即 BadResponse）。 */
  private[imagegen] def materialize(ref: ImageRef, resolved: ResolvedProvider): Either[ImageGenError, Array[Byte]] =
    val bytesEither = ref match
      case ImageRef.B64(b64) =>
        try Right(Base64.getDecoder.decode(b64))
        catch case _: IllegalArgumentException => Left(E.BadResponse(resolved.name, "invalid base64 payload"))
      case ImageRef.Url(url) => httpGetBytes(url, resolved.timeoutMs)
    bytesEither.flatMap { bytes =>
      if bytes.isEmpty then Left(E.BadResponse(resolved.name, "empty image payload"))
      else if bytes.length > MaxImageBytes then Left(E.BadResponse(resolved.name, s"image payload exceeds ${MaxImageBytes} bytes"))
      else Right(bytes)
    }

  val MaxImageBytes: Int = 20 * 1024 * 1024

  /**
   * 完整一击：resolve → POST → parse → materialize。返回 PNG 字节。
   * 调用方决定阻塞等（腿②路由）还是丢后台 fiber（腿①工具）。
   */
  def generate(req: ImageGenRequest): IO[Either[ImageGenError, Array[Byte]]] =
    IO.blocking {
      resolve(req) match
        case Left(err) => Left(err)
        case Right(resolved) =>
          httpPostJson(resolved, resolved.adapter.buildBody(resolved.model, req)) match
            case Left(err) => Left(err)
            case Right(json) =>
              resolved.adapter.parseResult(json) match
                case Left(err) => Left(err)
                case Right(ref) => materialize(ref, resolved)
    }

  // ── 图片落盘（两腿共用同一路径：<dataRoot>/imagegen/<name>.<ext>）──────────
  //
  // 🔴 格式不臆造：cogview-3-flash 实测回 JPEG（非文档暗示的 PNG）——按 magic
  // bytes 嗅探真实格式定扩展名（png/jpeg/webp/gif），前端 nf-file 链按字节服务，
  // 扩展名只影响落盘名。

  def outputDir: os.Path = PathUtil.dataRoot / "imagegen"

  private val Magics: List[(String, Array[Byte])] = List(
    "png" -> Array(0x89.toByte, 'P'.toByte, 'N'.toByte, 'G'.toByte),
    "jpeg" -> Array(0xff.toByte, 0xd8.toByte, 0xff.toByte),
    "webp" -> Array('R'.toByte, 'I'.toByte, 'F'.toByte, 'F'.toByte),
    "gif" -> Array('G'.toByte, 'I'.toByte, 'F'.toByte)
  )

  /** magic-bytes 嗅探；未知格式按 png 落（img 标签按内容渲染，扩展名仅供参考）。 */
  def sniffExt(bytes: Array[Byte]): String =
    Magics.find((_, magic) => bytes.length >= magic.length && magic.indices.forall(i => bytes(i) == magic(i)))
      .map(_._1)
      .getOrElse("png")

  /** 落盘并回绝对路径；IO 失败抛——调用方包 handleError 成业务错误。 */
  def saveImage(name: String, bytes: Array[Byte]): IO[String] = IO.blocking {
    os.makeDir.all(outputDir)
    val target = outputDir / s"$name.${sniffExt(bytes)}"
    os.write.over(target, bytes)
    target.toString
  }
end ImageGenService

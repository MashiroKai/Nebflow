package nebflow.llm.imagegen

/**
 * 生图能力共享层——imagegen 双层批（2026-10-24，作者裁定双层架构）的 provider 抽象。
 *
 * 两条腿共用本层：
 *  腿① 内置工具 `generate_image`（Nebula 专属，非阻塞 job，前端占位动画）；
 *  腿② 插件 imagegen（MCP，阻塞返回，无前端语义）——经本机引擎
 *      `POST /api/imagegen` 路由调用同一实现。
 *
 * 广兼容口径（适配「现在比较新的生图模型」）：
 *  适配器形态按**协议**划分而非按厂商划分——一切 OpenAI images 兼容端点
 *  （gpt-image-1、豆包 Seedream/火山方舟兼容模式、各类代理）走同一个
 *  [[ImageGenAdapters.OpenAiImages]]；智谱 CogView 走 [[ImageGenAdapters.CogView]]
 *  （OpenAI 兼容但其 size 枚举/鉴权面有自家形状）；Imagen 预留位
 *  [[ImageGenAdapters.Imagen]]（Gemini generateImages 协议，标记未验证）。
 */
object ImageGenTypes:

  sealed trait ImageGenError extends Product with Serializable:
    def message: String

  object ImageGenError:
    /** 节缺失 / enabled=false —— 面整体关闭（零迁移安全缺省方向）。 */
    case object Disabled extends ImageGenError:
      val message = "imagegen face is disabled or unconfigured (nebflow.json top-level `imagegen` section, enabled=true required)"
    final case class UnknownProvider(name: String) extends ImageGenError:
      def message = s"unknown imagegen provider '$name'"
    final case class MissingCredential(provider: String, keyRef: Option[String]) extends ImageGenError:
      def message =
        s"provider '$provider' has no usable credential (keyRef ${keyRef.getOrElse("<unset>")} resolves to no readable secret under the data-root secrets/ dir)"
    final case class HttpStatus(provider: String, status: Int, body: String) extends ImageGenError:
      def message =
        s"provider '$provider' returned HTTP $status: ${body.take(300)}"
    final case class BadResponse(provider: String, detail: String) extends ImageGenError:
      def message = s"provider '$provider' returned an unparseable response: $detail"
    final case class Transport(provider: String, detail: String) extends ImageGenError:
      def message = s"provider '$provider' transport error: $detail"
  end ImageGenError

  /** 归一化后的生图请求（size 已按目标适配器口径换算前原样传入）。 */
  final case class ImageGenRequest(
    prompt: String,
    size: Option[String] = None,
    provider: Option[String] = None,
    quality: Option[String] = None
  )

  /** 一次成功生成的产物：PNG 字节（全部适配器统一落 PNG）。 */
  final case class GeneratedImage(bytes: Array[Byte], width: Int, height: Int)

  /** 解析完毕、可执行的 provider（凭据已读出，绝不进日志/返回值）。 */
  final case class ResolvedProvider(
    name: String,
    adapter: ImageGenAdapter,
    baseUrl: String,
    model: String,
    token: String,
    timeoutMs: Long
  )

  /**
   * 适配器协议：build* 为纯函数（可单测），HTTP 壳在 [[ImageGenService]]。
   *
   * 适配器产出「图片引用」（URL 或 base64）——各家两种形态都给（gpt-image-1 只给
   * b64；cogview/seedream-4 默认给 url），下载/解码统一由服务层收尾。
   */
  trait ImageGenAdapter:
    def `type`: String
    /** 该协议缺省 baseUrl（config 未给 baseUrl 时的回落值）。 */
    def defaultBaseUrl: String
    /** 请求体（纯函数；token 只进 header 不进 body）。 */
    def buildBody(model: String, req: ImageGenRequest): io.circe.Json
    def authHeader(token: String): (String, String)
    /** 从响应 JSON 提取图片引用。 */
    def parseResult(json: io.circe.Json): Either[ImageGenError, ImageRef]

  sealed trait ImageRef extends Product with Serializable
  object ImageRef:
    final case class Url(url: String) extends ImageRef
    final case class B64(base64: String) extends ImageRef
end ImageGenTypes

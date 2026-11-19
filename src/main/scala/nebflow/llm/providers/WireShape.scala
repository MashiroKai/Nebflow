package nebflow.llm.providers

import io.circe.Json
import io.circe.parser.parse
import nebflow.shared.*

/**
 * 协议归一化层（protocol-compat 批，2026-11-01）——**两种协议面共用的形状
 * 语言与诊断词汇**，不含任何发请求逻辑。
 *
 * 为什么单独成文件：这一批的核心不是"再造一个翻译器"，而是把**已经存在的
 * 两个协议适配器**（[[AnthropicAdapter]] / [[OpenAiAdapter]]，各自承担内部
 * 统一消息模型 ↔ 线上形状的翻译）收口到同一套判据上——同一块内容在两面
 * 降级方式一致、同一类失败在两面的诊断串一致。谁违反判据，谁就破坏
 * "openai / anthropic 随时切换不出错"的契约。
 *
 * 三条判据（作者令 2026-11-01 17:45/17:49）：
 *
 *  ① **不认的块不判死**（`classifyBlock`）：任何携带文本的未知内容块都必须
 *     降级成 Text / Thinking 之一，绝不丢弃、绝不抛错；未知事件 / 未知 delta
 *     一律跳过。provider 的块类型目录是开放的（redacted_thinking、
 *     server_tool_use、web_search_tool_result、各家自造的 reasoning 变体），
 *     引擎的映射表是封闭的——封闭表遇到开放集，正确动作是降级不是崩。
 *
 *  ② **失败必可诊断**（`routeLine` / `requestShape` / `responseShape` /
 *     `diagnose`）：`Format` 三个字母不是诊断。任何形状类失败都要说清
 *     provider、model、我们发出去的消息块类型序列、以及对方的响应类型序列。
 *
 *  ③ **路由自述**（`routeBanner`）：会话里跑的是哪条腿（provider / model）
 *     必须能被这批自己读到——写进每请求的 dynamic system 段（尾部，不碰
 *     稳定前缀的 prompt cache），也写进每个失败串。
 *
 * 纯函数、零 IO、零配置读取 ⇒ 全部可单测（WireShapeSpec）。
 */
object WireShape:

  // ── ③ 路由自述 ──────────────────────────────────────────────────────

  /**
   * 每请求注入 dynamic system 段的路由横幅。刻意放在 dynamic 段（稳定前缀
   * 之后）——fallback 会在会话中途换腿，若写进 stable 段，一次换腿就击穿
   * 该会话的 prompt cache 前缀（cache 纪律：稳定段是跨会话共享前缀）。
   *
   * 形状固定为一行 `provider=<id> model=<id>`：批自己（以及任何 grep 日志的
   * 人）都能直接读，不依赖面板。作者冒烟实证：缺这一行时批只能答
   * "provider=unknown model=unknown"。
   */
  def routeBanner(providerId: String, model: String): String =
    val p = if providerId == null || providerId.isBlank then "unknown" else providerId
    val m = if model == null || model.isBlank then "unknown" else model
    s"## Active route\n- provider=$p model=$m"

  /** 诊断串里的同款路由标识（单行，便于 grep）。 */
  def routeLine(providerId: String, model: String): String =
    val p = if providerId == null || providerId.isBlank then "unknown" else providerId
    val m = if model == null || model.isBlank then "unknown" else model
    s"provider=$p model=$m"

  // ── ① 未知块降级 ────────────────────────────────────────────────────

  /** 内容块归一化产物：能保留的都保留，判不出类型的才丢弃。 */
  enum Block:
    case Text(text: String)
    case Thinking(text: String, signature: Option[String])
    /** 判不出来、也不带文本——只计数，用于诊断。 */
    case Opaque(kind: String)
  end Block

  /**
   * 未知/插入型内容块的降级判据（**两面共用**，多协议面唯一口径）。
   *
   * 判序（先文本，后推理，最后丢弃）：
   *   1. `text` 字段非空 → Text（openai 的 `content` 数组元素、anthropic 的
   *      text 块、各家自造的 `{"type":"output_text","text":...}`）。
   *   2. `thinking` / `reasoning_content` / `reasoning` 字段非空
   *      → Thinking（DeepSeek `reasoning_content`、GLM `thinking`、
   *      o 系列 `reasoning`，以及各类 reasoning 变体）。
   *   3. 其余 → Opaque(原 type 字符串截断)，**不抛错**，由调用方计入诊断。
   *
   * 明确不认但绝不降级成 Text 的类型：`tool_use` / `tool_result` /
   * `image` / `image_url`（它们有专属结构化处理，误判成文本会破坏工具配对
   * 与图像通道）。
   */
  def classifyBlock(block: Json): Block =
    val hc = block.hcursor
    val kind = hc.downField("type").as[String].toOption.orElse {
      // 无 type 字段但带文本的形状（部分兼容端点的 content 数组元素）
      hc.downField("text").as[String].toOption.map(_ => "text")
    }
    // A block can be one streaming fragment: blank text still carries separators.
    def str(f: String): Option[String] = hc.downField(f).as[String].toOption.filter(_.nonEmpty)
    kind match
      case Some("tool_use") | Some("tool_result") | Some("image") | Some("image_url") =>
        Block.Opaque(kind.getOrElse("?"))
      case Some("thinking") | Some("redacted_thinking") | Some("reasoning") | Some("reasoning_content") =>
        str("thinking")
          .orElse(str("reasoning_content"))
          .orElse(str("reasoning"))
          .orElse(str("text"))
          .map(t => Block.Thinking(t, str("signature")))
          .getOrElse(Block.Opaque(kind.getOrElse("?")))
      case _ =>
        // 未知类型：先看文本（保内容），再看推理，最后记 opaque
        str("text")
          .map(Block.Text.apply)
          .orElse {
            hc.downField("content").focus.filter(_.isString).flatMap(_.asString).filter(_.nonEmpty)
              .map(Block.Text.apply)
          }
          .orElse(
            str("thinking")
              .orElse(str("reasoning_content"))
              .orElse(str("reasoning"))
              .map(t => Block.Thinking(t, str("signature")))
          )
          .getOrElse(Block.Opaque(kind.getOrElse("unknown")))

  // ── ② 诊断词汇 ──────────────────────────────────────────────────────

  /** 消息块的类型字母表（诊断用短名）——一眼看出"我们发出去的是什么形状"。 */
  private def blockTag(b: ContentBlock): String = b match
    case ContentBlock.Text(_)             => "t"
    case ContentBlock.Image(_, _)         => "i"
    case ContentBlock.ToolUse(_, _, _)    => "u"
    case ContentBlock.ToolResult(_, _, _) => "r"
    case ContentBlock.Thinking(_, _)      => "k"

  /**
   * 请求侧形状指纹：每条消息 `role:tags`，逗号分隔，最多 `maxMsgs` 条
   * （超出记 `+N`）。例：`u:t,a:ku,r:t,u:t` = 用户文本 → 助手(思考+工具)
   * → 工具结果 → 用户文本。**只记类型不记内容**——诊断串会进日志与面板。
   */
  def requestShape(messages: List[Message], maxMsgs: Int = 12): String =
    val tags = messages.map { m =>
      val role = m.role match
        case MessageRole.User      => "u"
        case MessageRole.Assistant => "a"
        case MessageRole.System    => "s"
        case null                  => "?"
      val body = m.content match
        case Left(_)       => "t"
        case Right(blocks) => blocks.map(blockTag).mkString
      s"$role:${if body.isEmpty then "-" else body}"
    }
    val shown = tags.take(maxMsgs)
    val tail = if tags.size > maxMsgs then s",+${tags.size - maxMsgs}" else ""
    shown.mkString(",") + tail

  /**
   * 响应侧形状序列：把"对方到底回了什么类型"压成一行。
   *
   * 覆盖已知形态，未知形态回落到顶层键名（仍然有信息量）：
   *   - 错误体 `{"type":"error","error":{"type":...,"code":...}}`
   *     → `error/<error.type>(code=..)`
   *   - anthropic 成功体 `{"content":[{"type":"thinking"},{"type":"text"}]}`
   *     → `thinking>text`
   *   - openai 非流 `{"choices":[{"message":{...,"tool_calls":..}}]}`
   *     → `message:content+tool_calls`
   *   - openai 流单帧 → `delta:reasoning_content+tool_calls`
   *   - 非 JSON（HTML 网关页、纯文本） → `non-json(<len>):<head>`
   */
  def responseShape(body: String, maxItems: Int = 8): String =
    if body == null || body.isBlank then "empty"
    else
      parse(body) match
        case Left(_) =>
          val head = body.trim.take(80).replaceAll("\\s+", " ")
          s"non-json(${body.length}):$head"
        case Right(json) =>
          val hc = json.hcursor
          def types(path: io.circe.ACursor, field: String): List[String] =
            path.downField(field).as[List[Json]].toOption.getOrElse(Nil).map { b =>
              b.hcursor.downField("type").as[String].toOption.getOrElse("?")
            }
          val err = hc.downField("error")
          if err.succeeded then
            val t = err.downField("type").as[String].toOption.orElse {
              err.as[String].toOption.map(_ => "string")
            }.getOrElse("?")
            val code = err.downField("code").as[String].toOption.getOrElse("-")
            s"error/$t(code=$code)"
          else
            val contentTypes = types(hc, "content")
            if contentTypes.nonEmpty then contentTypes.take(maxItems).mkString(">")
            else
              val choice = hc.downField("choices").downN(0)
              val msg = choice.downField("message")
              val delta = choice.downField("delta")
              val msgKeys =
                if msg.succeeded then
                  msg.as[Json].toOption
                    .flatMap(_.asObject)
                    .map(_.keys.toList.filter(k => k != "role" && k != "content").sorted)
                    .getOrElse(Nil)
                else Nil
              val deltaKeys =
                if delta.succeeded then
                  delta.as[Json].toOption
                    .flatMap(_.asObject)
                    .map(_.keys.toList.filter(k => k != "role").sorted)
                    .getOrElse(Nil)
                else Nil
              if msg.succeeded then s"message:${("content" :: msgKeys).mkString("+")}"
              else if delta.succeeded then s"delta:${deltaKeys.mkString("+")}"
              else json.asObject.map(_.keys.toList.sorted.mkString("|")).getOrElse("scalar")
      end match

  /**
   * 统一的形状失败诊断串（**面板 / 日志 / 错误文案共用一份**）。
   *
   * 作者要的三件事实一次说全：谁的腿（provider/model）、我们发了什么形状、
   * 对方回了什么形状。
   */
  def diagnose(
    providerId: String,
    model: String,
    request: List[Message],
    status: Option[Int],
    body: String
  ): String =
    val statusPart = status.map(c => s" HTTP $c").getOrElse("")
    s"[wire]$statusPart ${routeLine(providerId, model)} " +
      s"| request=${requestShape(request)} " +
      s"| response=${responseShape(body)}"

  /**
   * 上游原话摘录（截断 + 压空白）：诊断串里唯一带对方文本的部分，便于人一眼
   * 认出"模型不支持"/"额度用尽"/"thinking 必须回传"这类语义。
   */
  def upstreamExcerpt(body: String, max: Int = 240): String =
    if body == null || body.isBlank then ""
    else
      parse(body) match
        case Right(json) =>
          val msg = json.hcursor.downField("error").downField("message").as[String].toOption
            .orElse(json.hcursor.downField("message").as[String].toOption)
            .getOrElse(json.noSpaces)
          msg.replaceAll("\\s+", " ").take(max)
        case Left(_) =>
          body.replaceAll("\\s+", " ").take(max)

  /**
   * 可自愈的请求形状失败判据：上游明确说"思考块必须回传"——这不是 provider
   * 坏了，是我们发出去的思考键形态不对（缺键 = 对方默认进思考模式）。
   * 命中 ⇒ 适配器做一次**降级重试**（显式 thinking:disabled），而不是判死整批。
   *
   * 判据 = 状态码 400 + 上游原话（各家措辞不同），不猜客户端内部状态。
   */
  def isThinkingPassbackRejection(status: Int, body: String): Boolean =
    if status != 400 then false
    else
      val low = Option(body).getOrElse("").toLowerCase
      (low.contains("thinking") || low.contains("reasoning")) &&
        (low.contains("must be passed back") || low.contains("passed back to the api") ||
          low.contains("content[].thinking") || low.contains("reasoning_content"))

end WireShape

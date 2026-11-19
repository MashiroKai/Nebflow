package nebflow.shared

/**
 * 能力不匹配判定：**「上游明确说这个模型不收图」**（flysin 收口批，2026-11-04；
 * 作者报障 2026-11-03 22:23/22:36）。
 *
 * 为什么单独成一件：这类失败的机制面与「请求形状写错」一样都是 HTTP 400（引擎侧
 * 归 `FailoverReason.Format`），但**处置与文案完全不同**——
 *   · 形状错 = 我们的翻译层有 bug，重试/换 provider 也许能过；
 *   · 能力不匹配 = 选错了腿（纯文本模型收到带图消息），重试一百次也不会好，
 *     唯一正确的动作是换 vision 模型或去掉图片。
 * 把它们归到同一个 `[format]` 族里，作者读到的就是「provider 失败」这种笼统面
 * （本批要消除的正是这一层）。
 *
 * 判据纪律（沿用 visionfix 甲案的教训：删掉了 `isVisionError` 的裸词启发式）：
 *   - **必须带 `image` / `vision` 名词**才算候选，绝不认「not support」这类裸词
 *     （历史误判：`endpoint 不支持该模型` 被判成「不支持看图」）；
 *   - 只认上游原话里**能力语义**的短语（`does not support image input` /
 *     `choose a vision-capable model` / `image_url is not supported` …）；
 *   - 纯函数、零 IO ⇒ 可单测（[[ImageCapabilityRejectionSpec]]）。
 *
 * 生产原话（2026-11-03，逐字）：
 * `upstream=model deepseek-v4-1-flash-261017 does not support image input; choose a
 *  vision-capable model or remove image content (request id: …)`
 */
object ImageCapabilityRejection:

  /**
   * 上游原话里的能力语义短语（小写比较）。全部要求与 `image` / `vision`
   * 同时出现，避免与形状类 400（thinking 回传、input too long、InvalidParameter）
   * 混淆。
   */
  private val Phrases: List[String] = List(
    "does not support image",
    "doesn't support image",
    "do not support image",
    "not support image",
    "image input is not supported",
    "does not support vision",
    "vision is not supported",
    "unsupported image",
    "image_url is not supported",
    "vision-capable",
    "vision capable",
    "remove image content"
  )

  /**
   * 该失败串是否证明「所选模型不接受图片输入」。
   *
   * @param text 上游错误体 / 引擎诊断串（`[wire] … | upstream=…` 形态亦可）
   */
  def isRejection(text: String): Boolean =
    if text == null || text.isBlank then false
    else
      val low = text.toLowerCase
      val mentionsImage = low.contains("image") || low.contains("vision") || low.contains("看图") ||
        low.contains("图片")
      mentionsImage && Phrases.exists(low.contains)

  /**
   * 链上所有「不收图」的 attempt 的 `provider/model` 标识（去重、保持顺序）——
   * 作者可见文案与日志都点名到这里，而不是泛泛说「provider 失败」。
   */
  def rejectingModels(attempts: List[FallbackAttempt]): List[String] =
    attempts
      .filter(a => isRejection(a.message.getOrElse("")))
      .map(a => s"${a.providerId}/${a.model}")
      .distinct

  /** [[rejectingModels]] 的 Boolean 短路形（调用点只想分流时用）。 */
  def anyRejection(attempts: List[FallbackAttempt]): Boolean = attempts.exists(a => isRejection(a.message.getOrElse("")))

  /**
   * 日志侧留痕（**provider/model 字段必带**）：接在既有 permanent-error WARN 行
   * 尾部即可 grep 出「这一族失败到底是哪条腿不收图」，无需另开日志面。
   */
  def logNote(providerId: String, model: String, text: String): String =
    if !isRejection(text) then ""
    else s" | capability=image-input-unsupported provider=$providerId model=$model"

  /**
   * 作者可见文案（`NebflowError.LlmFailed` 消费）：点名模型 + 给出两条可行动作。
   * 与「当前模型链没有一个支持看图」那句（链级、不点名）的区别：这里是**已知
   * 是谁拒绝**，所以直接点名。
   */
  def userText(models: List[String]): String =
    val who = if models.nonEmpty then models.mkString("、") else "当前模型"
    s"这条消息里的图片没能送出去：$who 不支持图片输入（纯文本模型）。" +
      "请改用支持看图（vision）的模型，或去掉图片后重发。"

end ImageCapabilityRejection

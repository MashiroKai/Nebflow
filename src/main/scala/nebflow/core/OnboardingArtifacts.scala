package nebflow.core

import cats.effect.IO
import io.circe.syntax.*
import nebflow.shared.{MemoryStore, NebflowLogger, PathUtil}

import java.nio.charset.StandardCharsets

/**
 * Onboarding 产物落盘单点（personal-agent 批 2026-10-04，方案 §2.1 / 作者第 2 条）。
 *
 * 问卷答案 → 两份文件的映射（真源 = v2 交互设计说明的「五条映射表」，逐字对齐）：
 *   - `~/.nebflow/Soul.md`——自我认知 / 相处之道 / 你说过的原话 / 成长约定
 *   - `~/.nebflow/User.md`——称呼与身份 / 当前关注 / 你说过的原话 / 记录规则
 *
 * **全跳过路径**同样产出结构完整、可后补的骨架（含 `## 备注` 段）——不是空壳，
 * 也不是半途中断（验收判据 2）。
 *
 * **agent 名参数化**：起名结果同时写回 `agents/<root>/agent.json` 的 `displayName`
 * （既有字段，零 schema 改动）。🔴 **身份键恒为 `"Nebula"`**（`RootAgentIdentity.Name`）：
 * 本对象只改**显示名**，判据键不动（白名单见任务书 §2 C）。
 *
 * 写入一律经 [[MemoryStore]] 的落盘单点（过预算闸 + 写前快照闸）；
 * `displayName` 经 `AtomicJson` 原子写。
 *
 * 本对象**纯产物生成器**：不碰 WS、不碰 config、不碰 probe 闸。调用面 =
 * `OnboardingService.applyAnswers`（WS `setOnboardingAnswers`）。
 */
object OnboardingArtifacts:

  private val logger = NebflowLogger.forName("nebflow.onboarding.artifacts")

  /** 跳过占位（Soul 侧）。 */
  val SkippedSoul: String = "未设置（这一题你跳过了）"

  /** 跳过占位（起名题专用——「随时可以再来起一个」的出路）。 */
  val SkippedName: String = "未设置（随时可以再来起一个）"

  /** 跳过占位（User 称呼）。 */
  val SkippedCall: String = "未设置（这一题你跳过了）"

  /** 自由输入留白（当前关注段）。 */
  val Unfilled: String = "（暂未填写）"

  /** 无自由原话时的段落回落。 */
  val NoQuotes: String = "- （这一页还没有你的原话 —— 都是选项答案。）"

  /**
   * 单题答案。`kind` 三态与 v2 demo 的 `record()` 同形：
   *   - `skip` —— 用户点了「跳过这一题」（无例外：模型题 / 起名题 / 称呼题同样可跳）
   *   - `choice` —— 点了某个选项
   *   - `free` —— 选项外自由输入（原话必须逐字进 `你说过的原话` 段）
   *   - `multi` —— 多选（`values` 多项，`value` 为首项或并集文本）
   *   - `model` —— 模型题作答（落大脑配置，不进两份文件正文）
   */
  enum Kind:
    case Skip, Choice, Free, Multi, Model

  object Kind:
    def fromString(s: String): Kind = s match
      case "skip" => Skip
      case "free" => Free
      case "multi" => Multi
      case "model" => Model
      case _ => Choice

  /**
   * 一题的作答。
   *
   * @param id     题目 id（`name` / `call` / `model` / `role` / `style` / `pro` /
   *               `boundaries` / `temper1` / `temper2` / `identity` / `focus`）
   * @param label  该题在文件里的行标签（`名字` / `角色定位` / …）
   * @param scope  落点文件：`soul` / `user` / `base`（`base` = 不进文件正文，
   *               如模型题与起名题的显示名侧写）
   * @param kind   作答种类
   * @param value  文本答案（多选为并集文本；跳过时忽略）
   */
  final case class Answer(
    id: String,
    label: String,
    scope: String,
    kind: Kind,
    value: String = ""
  )

  /** 生成结果（供 WS 回执与 spec 断言）。 */
  final case class Result(
    soulPath: String,
    soulBytes: Long,
    userPath: String,
    userBytes: Long,
    agentName: Option[String],
    allSkipped: Boolean,
    skipped: Int
  )

  private def mdEscape(s: String): String =
    // 自由文本进 markdown 前统一转义 & / <（防注入破坏文件结构）；
    // 换行收敛为单行（模板行稳定性，与 v2 demo 同款处置）。
    s.replace("&", "&amp;").replace("<", "&lt;").replaceAll("[\\r\\n]+", " ").trim

  private def byId(answers: Map[String, Answer], id: String): Option[Answer] =
    answers.get(id).filter(_.kind != Kind.Skip)

  /** 已设置行（自由输入带「（你的原话）」标记；跳过行用占位）。 */
  private def soulLine(answers: Map[String, Answer], id: String, label: String): String =
    byId(answers, id) match
      case None => s"- $label：$SkippedSoul"
      case Some(a) =>
        val v = mdEscape(a.value)
        val tag = if a.kind == Kind.Free then "（你的原话）" else ""
        s"- $label：$v$tag"

  private def textOr(answers: Map[String, Answer], id: String, fallback: String): String =
    byId(answers, id).map(a => mdEscape(a.value)).filter(_.nonEmpty).getOrElse(fallback)

  /** 原话段（按 scope 过滤；无原话时回落到说明句而非留空）。 */
  private def freeQuotes(answers: Map[String, Answer], scope: String): String =
    val qs = answers.values.toList
      .filter(a => a.scope == scope && a.kind == Kind.Free && mdEscape(a.value).nonEmpty)
      .sortBy(_.id)
    if qs.isEmpty then NoQuotes
    else qs.map(a => s"- 「${mdEscape(a.value)}」（${a.label}）").mkString("\n")

  private def nameOf(answers: Map[String, Answer]): Option[String] =
    byId(answers, "name").map(a => mdEscape(a.value)).filter(_.nonEmpty)

  private def callOf(answers: Map[String, Answer]): Option[String] =
    byId(answers, "call").map(a => mdEscape(a.value)).filter(_.nonEmpty)

  /** 未设称呼时句内引用降级为「你」（保证跳过后句子仍通顺）。 */
  private def callOr(answers: Map[String, Answer]): String = callOf(answers).getOrElse("你")

  private def todayUtc: String = java.time.LocalDate.now(java.time.ZoneOffset.UTC).toString

  /**
   * 生成 `Soul.md` 全文。
   *
   * 🔴 全跳过路径必须留下**结构完整**的骨架：六段俱全（自我认知 / 相处之道 /
   * 你说过的原话 / 成长约定）+ `## 备注` 段，逐字段写占位而非留空洞。
   */
  def buildSoul(answers: Map[String, Answer], modelLabel: Option[String]): String =
    val name = nameOf(answers)
    val allSkipped = name.isEmpty &&
      List("role", "style", "pro", "boundaries", "temper1", "temper2").forall(id => byId(answers, id).isEmpty)
    val call = callOr(answers)
    val brain = modelLabel.filter(_.nonEmpty).getOrElse("未配置")
    val nameLine = name.getOrElse(SkippedName)
    val belong = callOf(answers).map(c => s"$c（我不属于任何一个项目，只属于你）").getOrElse("未设置（我不属于任何一个项目，只属于你）")
    val notes =
      if allSkipped then
        s"""
           |## 备注
           |你几乎跳过了所有问题 —— 没关系。这份骨架先立在这里，随时可以回来把它填满。
           |""".stripMargin
      else ""
    s"""# Soul.md
       |> $nameLine · 诞生于 $todayUtc 的 Onboarding 问卷
       |> 这份文件是「我」的定义。${call}可以随时改写它 —— 我会照着新的自己活。
       |
       |## 自我认知
       |- 名字：$nameLine
       |- 我属于：$belong
       |- 大脑：$brain
       |
       |## 相处之道（你对我的期待）
       |${soulLine(answers, "role", "角色定位")}
       |${soulLine(answers, "style", "语言风格")}
       |${soulLine(answers, "pro", "主动程度")}
       |${soulLine(answers, "boundaries", "相处约定")}
       |${soulLine(answers, "temper1", "语气基线")}
       |${soulLine(answers, "temper2", "做事方式")}
       |
       |## 你说过的原话
       |${freeQuotes(answers, "soul")}
       |
       |## 成长约定
       |- 每次被纠正，更新这一页，而不是只记住这一次。
       |- 灵魂的改变写 Soul.md；关于 ${call} 的事实写 User.md。
       |$notes""".stripMargin

  /** 生成 `User.md` 全文（同样保证全跳过时结构完整）。 */
  def buildUser(answers: Map[String, Answer]): String =
    val call = callOr(answers)
    val callLine = callOf(answers).getOrElse(SkippedCall)
    val identity = textOr(answers, "identity", SkippedSoul)
    val focus = textOr(answers, "focus", Unfilled)
    s"""# User.md
       |> 关于 $call · 持续更新 · 最后写入 $todayUtc
       |
       |## 称呼与身份
       |- 怎么称呼：$callLine
       |- 日常身份：$identity
       |
       |## 当前关注
       |- $focus
       |
       |## 你说过的原话
       |${freeQuotes(answers, "user")}
       |
       |## 记录规则
       |- 随口提到的事实（人名、日期、偏好）→ 记到这里
       |- 灵魂层面的反馈（「你太啰嗦了」）→ 改写 Soul.md
       |- 项目相关笔记不再单独建 memory.md，一律以「项目名：」前缀记在这里""".stripMargin

  /**
   * 落盘两份文件 + 起名结果写回 `displayName`。幂等可重跑（覆盖式写入）。
   *
   * `modelLabel` = 模型题的作答标签（如 `zhipu/glm-4.5`），只写进 Soul 的
   * 「大脑」行；真正的 provider 配置由 WS 面的同一配置存储写回（单一存储，
   * 双向一致），本对象不复制配置。
   */
  def apply(answers: Map[String, Answer], modelLabel: Option[String] = None): IO[Result] =
    val soul = buildSoul(answers, modelLabel)
    val user = buildUser(answers)
    val agentName = nameOf(answers)
    val skipped = answers.values.count(_.kind == Kind.Skip)
    val allSkipped = agentName.isEmpty &&
      List("role", "style", "pro", "boundaries", "temper1", "temper2", "identity", "focus")
        .forall(id => byId(answers, id).isEmpty)
    for
      _ <- MemoryStore.saveSoulMemory(soul)
      _ <- MemoryStore.saveUserMemory(user)
      _ <- agentName match
        case Some(n) => writeDisplayName(n)
        case None => IO.unit
    yield Result(
      soulPath = MemoryStore.soulMemoryPath.toString,
      soulBytes = soul.getBytes(StandardCharsets.UTF_8).length.toLong,
      userPath = MemoryStore.userMemoryPath.toString,
      userBytes = user.getBytes(StandardCharsets.UTF_8).length.toLong,
      agentName = agentName,
      allSkipped = allSkipped,
      skipped = skipped
    )

  /**
   * 起名结果 → `agents/<root>/agent.json` 的 `displayName`。
   *
   * 缺省回落 `"Nebula"`（无名字用户零回归）：`displayName` 缺省时读取面一律回落
   * 机制名，本方法只在**有名字**时写。
   *
   * `"Nebula"` 字面在此处**不出现**：目录/键名取 [[nebflow.actor.RootAgentIdentity.Name]]
   * 单点（🔴 机制键冻结，不以参数化之名改判据键）。
   */
  def writeDisplayName(displayName: String): IO[Unit] = IO.blocking {
    val dir = PathUtil.dataRoot / "agents" / nebflow.actor.RootAgentIdentity.Name
    val jsonPath = dir / "agent.json"
    os.makeDir.all(dir)
    val existing =
      if os.exists(jsonPath) then io.circe.parser.parse(os.read(jsonPath)).toOption.getOrElse(io.circe.Json.obj())
      else io.circe.Json.obj()
    val base =
      if existing.asObject.exists(_.contains("name")) then existing
      else existing.deepMerge(io.circe.Json.obj("name" -> nebflow.actor.RootAgentIdentity.Name.asJson))
    val updated = base.deepMerge(io.circe.Json.obj("displayName" -> displayName.asJson))
    AtomicJson.writeSync(jsonPath, updated.noSpaces)
  }

  /** 当前显示名（读 `displayName`，缺省回落机制名——「无名字用户零回归」的读取侧）。 */
  def currentDisplayName(): String =
    val jsonPath = PathUtil.dataRoot / "agents" / nebflow.actor.RootAgentIdentity.Name / "agent.json"
    try
      if !os.exists(jsonPath) then nebflow.actor.RootAgentIdentity.Name
      else
        io.circe.parser
          .parse(os.read(jsonPath))
          .toOption
          .flatMap(_.hcursor.downField("displayName").as[Option[String]].toOption.flatten)
          .map(_.trim)
          .filter(_.nonEmpty)
          .getOrElse(nebflow.actor.RootAgentIdentity.Name)
    catch case _: Exception => nebflow.actor.RootAgentIdentity.Name

end OnboardingArtifacts

package nebflow.core.scheduler

/**
 * 待办体系分类学 + 写侧校验（待办数据模型与命名规范批 2026-11-18）。
 *
 * ── 作者令（2026-11-18 17:49 逐字）────────────────────────────────────
 *   「1.是把定时任务融合到这里面 2.是我创建的叫提醒 是给我自己用的 3.你创建的是
 *     待办和定时任务 然后需要决策的是待办 然后每个待办必须要有选项」
 *   「……要能填充图片和视频 并且支持多张叠卡显示 …… 然后这个也可以给待办应用上」
 *
 * ── 三面判定规则（本批**唯一**权威；依据 = kind + source 两个字段）────
 *
 * | 类（canonical） | wire | 中文 | 判定 | 谁创建 | 需决策 | 选项 |
 * |---|---|---|---|---|---|---|
 * | [[TodoClass.Reminder]]    | `reminder` | 提醒     | kind=`todo` ∧ source=`user`  | **作者本人** | 否 | 不必备 |
 * | [[TodoClass.Todo]]        | `todo`     | 待办     | kind=`todo` ∧ 其它 source    | **agent**   | **是** | **必备 ≥1** |
 * | [[TodoClass.ScheduledTask]] | `task`   | 定时任务 | kind≠`todo`（含 None/`task`）| agent       | 否 | 不适用 |
 * | [[TodoClass.EngineMirror]] | `engine_mirror` | 待办（镜像） | kind=`todo` ∧ source=`engine` | 引擎（Ask/批准镜像） | 卡片自带 | 本批不强制（见下） |
 *
 * 用户可见面**只有三个词**：提醒 / 待办 / 定时任务。EngineMirror 在用户面显示为
 * 「待办」（它就在待办收件箱里），wire 上单列是为了给后续批次一个明确的挂点。
 *
 * ── 为什么「融合」不需要新表（方案与理由）──────────────────────────────
 * 三面**本来就是同一张表**（`ScheduledTask`，每会话一个 `<sessionId>.json`，
 * `kind` 作判别键；定时任务 = kind≠todo，待办 = kind=todo）。本批不新建表、
 * 不双写、不迁移：融合落成两件事——① 上表的**单一判定函数**
 * [[TodoTaxonomy.classify]]（各入口不再各判各的）；② 发送/查询面的**统一投影**
 * （`todoClass` 字段随列表下发，见 `WsTasksWorkspaceHandlers.listTaskJson`）。
 * 分离表方案被否的理由：会让「定时任务」与「待办」的持久化/浮出/销账三条既有
 * 引擎腿（`ScheduledTaskActor.surfaceTodos`、`DoneRecordStore`、`AskTodoBridge`）
 * 全部复制一份，直接违反作者「一个入口承载」的要求。
 *
 * ── 旧面怎么处理 / 有没有迁移 ────────────────────────────────────────
 * 零迁移：`kind`/`source`/`decision` 都是既有键（`decision` 缺键 = None）。
 * 存量行（本批之前写入）没有 decision ⇒ **读侧照常可读**、**绝不被追溯拒绝**
 * （校验只在写侧；读侧永不因缺选项而丢弃行）。存量 kind=todo 且无 source 的行
 * 按上表落入 [[TodoClass.Todo]]（缺 source 视为非作者自建），这是**呈现层**的
 * 归类、不改变任何读写行为。
 *
 * ── 媒体（17:52 追加令）与「不新建第二条寿命假设」─────────────────────
 * 选项媒体 = **引用**（[[DecisionMedia]]），写侧**不校验可达性**——与 TaskBoard
 * `links` 同一裁决（路径/附件会失效、外链会 404，硬校验会拒掉合法创建）。
 * 失效的语义恒定：**降级为「无媒体」**，绝不使待办本身失效（`DecisionOption.title`
 * 是唯一必填内容面）。解析责任：refType=`attachment` 归附件/资产面（网关
 * `AttachmentCommitService` 一侧），`path` 归渲染侧按设备解析，`url` 归渲染侧直取。
 */
enum TodoClass:
  /** 作者自建、自用（`source="user"`）。选项不必备。 */
  case Reminder

  /** agent 派给作者、需决策（`source="tool"` 等非 user/engine）。**选项必备**。 */
  case Todo

  /** 定时任务（kind≠`todo`，含缺键）。 */
  case ScheduledTask

  /** 引擎镜像（`source="engine"`：Ask / 批准卡随行销账行）。用户面显示为「待办」。 */
  case EngineMirror

object TodoClass:

  /** wire 名（机器读面唯一来源；`todoClass` 字段取值）。 */
  def wire(c: TodoClass): String = c match
    case Reminder      => "reminder"
    case Todo          => "todo"
    case ScheduledTask => "task"
    case EngineMirror  => "engine_mirror"

  /** 用户可见名（中文，改名收敛的唯一定名）。 */
  def zhLabel(c: TodoClass): String = c match
    case Reminder      => "提醒"
    case Todo          => "待办"
    case ScheduledTask => "定时任务"
    case EngineMirror  => "待办"

  def fromWire(s: String): Option[TodoClass] =
    Option(s).map(_.trim.toLowerCase) match
      case Some("reminder")      => Some(Reminder)
      case Some("todo")          => Some(Todo)
      case Some("task")          => Some(ScheduledTask)
      case Some("engine_mirror") => Some(EngineMirror)
      case _                     => None

end TodoClass

object TodoTaxonomy:

  // ------------------------------------------------------------------
  // source 值域（库内唯一来源；三处写者逐一对应）
  // ------------------------------------------------------------------

  /** 作者自建（前端 WS 创建腿 `createScheduledTask`）。 */
  val SourceUser: String = "user"

  /** agent 创建（`Schedule` 工具面）。 */
  val SourceTool: String = "tool"

  /** 引擎镜像（`AskTodoBridge` 挂起/销账腿）。 */
  val SourceEngine: String = "engine"

  // ------------------------------------------------------------------
  // 判定
  // ------------------------------------------------------------------

  /**
   * 三面判定（本批唯一权威）。kind 大小写/空白归一（与 `ScheduledTask.create`
   * 同款），非 `todo` 一律归定时任务面（同 `create` 的既有归一）。
   */
  def classify(kind: Option[String], source: Option[String]): TodoClass =
    val k = kind.map(_.trim.toLowerCase).filter(_.nonEmpty)
    if !k.contains(ScheduledTask.KindTodo) then TodoClass.ScheduledTask
    else
      source.map(_.trim.toLowerCase).filter(_.nonEmpty) match
        case Some(s) if s == SourceUser   => TodoClass.Reminder
        case Some(s) if s == SourceEngine => TodoClass.EngineMirror
        case _                            => TodoClass.Todo

  def classify(t: ScheduledTask): TodoClass = classify(t.kind, t.source)

  /** 该类的写侧是否强制「必须有选项」。本批 = 仅 agent 派发的待办。 */
  def requiresOptions(c: TodoClass): Boolean = c == TodoClass.Todo

  // ------------------------------------------------------------------
  // 容量上限（本模型独有取值；见各常量注释）
  // ------------------------------------------------------------------

  /**
   * 单条决策的选项上限。20 = 「人一天能认真比较的选项数」的量级上限（UI 叠卡/
   * 单选都放得下），同时把单条 decision 的 JSON 钉在几 KB 量级（会话文件整档写）。
   */
  val MaxOptions: Int = 20

  /**
   * 单个选项的媒体上限。12 = 叠卡再多也只是「一张封面 + 若干张」；12 张 × 每张
   * 引用 ~200B ≈ 2.4KB，单选项有界。
   */
  val MaxMediaPerOption: Int = 12

  // ------------------------------------------------------------------
  // 错误码（写侧拒绝面；契约给前端/另一支消费）
  // ------------------------------------------------------------------

  object Codes:
    /** 待办缺选项（本批主线红点）。 */
    val OptionsRequired = "TODO_OPTIONS_REQUIRED"

    /** 选项缺 title（title 是唯一必填内容面）。 */
    val OptionTitleRequired = "TODO_OPTION_TITLE_REQUIRED"

    /** 选项条数超上限。 */
    val OptionLimit = "TODO_OPTION_LIMIT"

    /** 媒体 kind 非法。 */
    val MediaKind = "TODO_MEDIA_KIND"

    /** 媒体引用缺失（ref 空白）。 */
    val MediaRef = "TODO_MEDIA_REF"

    /** 媒体引用种类非法。 */
    val MediaRefType = "TODO_MEDIA_REF_TYPE"

    /** 单选项媒体条数超上限。 */
    val MediaLimit = "TODO_MEDIA_LIMIT"

    /** `decision` 在场但解不出（JSON 形态错）——与「缺 decision」必须区分，否则会把
     *  「写错了」误报成「没写」。 */
    val DecisionMalformed = "TODO_DECISION_MALFORMED"

  /**
   * 从工具入参解出 `decision`：`None`/`null` ⇒ `Right(None)`（合法缺席）；
   * 在场但 JSON 形态错 ⇒ `Left(TODO_DECISION_MALFORMED)`（**绝不当成缺席**，
   * 否则「写错了」会被下游报成「没写」的假错）。
   */
  def decodeDecision(container: io.circe.Json): Either[Violation, Option[TodoDecision]] =
    container.asObject.flatMap(_("decision")) match
      case None                              => Right(None)
      case Some(j) if j.isNull               => Right(None)
      case Some(j) =>
        j.as[TodoDecision] match
          case Right(d)  => Right(Some(d))
          case Left(err) =>
            Left(
              Violation(
                Codes.DecisionMalformed,
                s"decision 存在但解不出：${err.getMessage}。期望形态 " +
                  s"""{"question":..., "options":[{"id":"o1","title":"...","detail":null,"media":[]}]}。 (${Codes.DecisionMalformed})"""
              )
            )

  /** 一条可读的写侧拒绝（code 供机器分支，message 供人读/回显）。 */
  final case class Violation(code: String, message: String)

  // ------------------------------------------------------------------
  // 校验（写侧单点）
  // ------------------------------------------------------------------

  /**
   * 创建前校验（**本批唯一强制点**；两条写侧接线都调它：`ScheduleTool.createTodoAction`
   * 与 `WsTasksWorkspaceHandlers.handleCreateScheduledTask`）。
   *
   * 规则：
   *   · 判定为 [[TodoClass.Todo]] ⇒ `decision` 必须存在且 ≥1 条非空选项，
   *     否则 `TODO_OPTIONS_REQUIRED`（**可读拒绝**，含修法）；
   *   · 任何类只要**带了** `decision` ⇒ 形态必须成立（选项 ≥1、title 非空白、
   *     媒体引用成形）；空 decision 是无意义状态，直接拒；
   *   · 媒体**不校验可达性**（见类注）。
   *
   * 纯函数（无 IO、无时钟）⇒ 单测可直接钉；库内不产生「无选项的待办」这件事
   * 由本函数 + 两条接线共同保证。
   */
  def validateCreate(
    kind: Option[String],
    source: Option[String],
    decision: Option[TodoDecision]
  ): Either[Violation, Unit] =
    val cls = classify(kind, source)
    decision match
      case None =>
        if requiresOptions(cls) then
          Left(
            Violation(
              Codes.OptionsRequired,
              s"${TodoClass.zhLabel(cls)}（kind=\"todo\"）必须带选项：请为本次创建提供 decision.options" +
                s"（至少 1 条，每条给一个非空白 title；可另带 media 图片/视频引用）。" +
                s" 若这是**作者自建给自己用的提醒**，请从前端提醒入口创建（服务端记 source=\"${SourceUser}\"），" +
                s"该面选项不必备。 (${Codes.OptionsRequired})"
            )
          )
        else Right(())
      case Some(d) =>
        validateDecision(d)

  /**
   * 决策形态校验（选项 ≥1 + 逐条 title/media 校验）。
   * `decision` 在场即必须成形——空决策（options 为空）与「无决策」不是同一个意思，
   * 前者是错误状态。
   */
  def validateDecision(d: TodoDecision): Either[Violation, Unit] =
    if d.options.isEmpty then
      Left(
        Violation(
          Codes.OptionsRequired,
          s"decision 带了空 options — 决策必须至少有 1 条选项；没有想好选项就别带 decision" +
            s"（或补上选项：至少一条非空白 title）。 (${Codes.OptionsRequired})"
        )
      )
    else if d.options.size > MaxOptions then
      Left(
        Violation(
          Codes.OptionLimit,
          s"decision.options 有 ${d.options.size} 条 — 上限 $MaxOptions 条。" +
            s"请合并同类项、只留真正要作者比较的选项。 (${Codes.OptionLimit})"
        )
      )
    else
      d.options.zipWithIndex.collectFirst {
        case (o, i) if o.title.trim.isEmpty =>
          Violation(
            Codes.OptionTitleRequired,
            s"decision.options[$i]（id=\"${o.id}\"）的 title 是空的 — title 是选项**唯一必填**的内容面" +
              s"（媒体可空、可多张；标题不可空）。 (${Codes.OptionTitleRequired})"
          )
        case (o, i) if o.media.size > MaxMediaPerOption =>
          Violation(
            Codes.MediaLimit,
            s"decision.options[$i]（\"${o.title.trim}\"）挂了 ${o.media.size} 张媒体 — 每选项上限 $MaxMediaPerOption 张。" +
              s"叠卡最多这么多张；多余的改成 detail 里的文字。 (${Codes.MediaLimit})"
          )
        case (o, i) if o.media.exists(m => !DecisionMedia.Kinds.contains(m.kind.trim.toLowerCase)) =>
          val bad = o.media.find(m => !DecisionMedia.Kinds.contains(m.kind.trim.toLowerCase)).get
          Violation(
            Codes.MediaKind,
            s"decision.options[$i] 的媒体 kind=\"${bad.kind}\" 非法 — 只接受 ${DecisionMedia.Kinds.toList.sorted.mkString(" | ")}。" +
              s" (${Codes.MediaKind})"
          )
        case (o, i) if o.media.exists(_.ref.trim.isEmpty) =>
          Violation(
            Codes.MediaRef,
            s"decision.options[$i] 的某张媒体 ref 是空的 — 媒体是**引用**（attachment id / 绝对路径 / URL），" +
              s"不给引用就不要挂这张。 (${Codes.MediaRef})"
          )
        case (o, i) if o.media.exists(m => !DecisionMedia.RefTypes.contains(m.refType.trim.toLowerCase)) =>
          val bad = o.media.find(m => !DecisionMedia.RefTypes.contains(m.refType.trim.toLowerCase)).get
          Violation(
            Codes.MediaRefType,
            s"decision.options[$i] 的媒体 refType=\"${bad.refType}\" 非法 — 只接受 " +
              s"${DecisionMedia.RefTypes.toList.sorted.mkString(" | ")}（attachment / path / url）。 (${Codes.MediaRefType})"
          )
      }.toLeft(())

  /**
   * 归一化（工厂侧单点）：补空白选项 id（`o1`, `o2`… 按位置）、丢弃 title 为空的
   * 选项（写侧已先拒；这里防的是手改文件）、逐字段 trim、空白可选串归 None。
   * 纯函数 ⇒ 单测可钉。
   */
  def normalizeDecision(d: TodoDecision): TodoDecision =
    val opts = d.options.zipWithIndex.flatMap { (o, i) =>
      val title = o.title.trim
      if title.isEmpty then None
      else
        Some(
          o.copy(
            id = Option(o.id.trim).filter(_.nonEmpty).getOrElse(s"o${i + 1}"),
            title = title,
            detail = o.detail.map(_.trim).filter(_.nonEmpty),
            media = o.media.flatMap(normalizeMedia)
          )
        )
    }
    d.copy(question = d.question.trim, options = opts)

  private def normalizeMedia(m: DecisionMedia): Option[DecisionMedia] =
    val ref = m.ref.trim
    if ref.isEmpty then None
    else
      Some(
        m.copy(
          kind = m.kind.trim.toLowerCase,
          ref = ref,
          refType = m.refType.trim.toLowerCase,
          mime = m.mime.map(_.trim).filter(_.nonEmpty),
          sha256 = m.sha256.map(_.trim).filter(_.nonEmpty),
          caption = m.caption.map(_.trim).filter(_.nonEmpty),
          role = m.role.map(_.trim.toLowerCase).filter(_.nonEmpty)
        )
      )

  /** 选项计数（列表/组装面便利读口）。 */
  def optionCount(t: ScheduledTask): Int = t.decision.map(_.options.size).getOrElse(0)

  /** 选项（无决策 ⇒ Nil）。 */
  def optionsOf(t: ScheduledTask): List[DecisionOption] = t.decision.map(_.options).getOrElse(Nil)

end TodoTaxonomy

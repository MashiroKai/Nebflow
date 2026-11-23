package nebflow.llm.rules

import nebflow.shared.PathUtil

/**
 * 规则脚本存储（P3 接线用的只读加载件；P6 的保存/版本化另行实现）。
 *
 * 文件：`<dataRoot>/model-rules.dsl`（设计 §8 第 8 条：独立脚本文件）。
 *   - 文件不存在 ⇒ 空脚本（无规则 ⇒ 请求链不变，P1 行为）。
 *   - 文件存在但解析/校验失败 ⇒ 返回 `Left`，调用方以「无规则」回落并在 trace 记录
 *     （保存期校验是 P6 的职责；请求期不得因坏脚本中断模型选择）。
 *   - 按 mtime + size 缓存，避免每次请求重解析。
 */
object ModelRuleStore:

  final case class Loaded(script: ModelRuleDsl.Script, error: Option[String])

  val EmptyLoaded: Loaded = Loaded(ModelRuleDsl.Script(Nil), None)

  @volatile private var cache: Option[(Long, Long, Loaded)] = None

  def fileOf(root: os.Path): os.Path = root / "model-rules.dsl"

  /** 从给定数据根加载（纯读取 + 缓存）。 */
  def load(root: os.Path): Loaded =
    val f = fileOf(root)
    if !os.exists(f) then EmptyLoaded
    else
      val mtime = os.mtime(f)
      val size = os.size(f)
      cache match
        case Some((m, s, l)) if m == mtime && s == size => l
        case _ =>
          val src = os.read(f)
          val loaded = ModelRuleDsl.parse(src) match
            case Right(script) => Loaded(script, None)
            case Left(errs) =>
              val first = errs.headOption.map(e => s"line ${e.line}: ${e.message}").getOrElse("parse error")
              Loaded(ModelRuleDsl.Script(Nil), Some(first))
          cache = Some((mtime, size, loaded))
          loaded

  /** 当前数据根的规则（生产入口）。 */
  def current: Loaded = load(PathUtil.dataRoot)

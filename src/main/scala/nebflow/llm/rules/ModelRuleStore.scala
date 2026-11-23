package nebflow.llm.rules

import nebflow.shared.PathUtil

/**
 * 规则脚本存储（P6）：脚本文件 + 三态模式侧车，原子写，保存期经 [[ModelRuleDsl.check]] 校验。
 *
 * 文件（均在数据根下）：
 *   - `model-rules.dsl`  规则脚本（设计 §8 第 8 条：独立脚本文件；stand.json 只存引用）。
 *   - `model-rules.mode` 三态 switch：`follow`（全部跟随）/ `slots`（分开配置·槽）/ `rules`（规则接管）。
 *
 * 语义：只有 `mode == rules` 时规则才改写请求候选（设计 §4.5）。其余两态请求链与 P1 完全相同。
 * 脚本缺失 ⇒ 空脚本；解析失败 ⇒ `Loaded.error`，求值侧不应用（fail-closed，不中断选择）。
 */
object ModelRuleStore:

  final case class Loaded(script: ModelRuleDsl.Script, error: Option[String])

  val EmptyLoaded: Loaded = Loaded(ModelRuleDsl.Script(Nil), None)

  val Modes: List[String] = List("follow", "slots", "rules")
  val DefaultMode: String = "follow"

  @volatile private var cache: Option[(Long, Long, Loaded)] = None

  def scriptOf(root: os.Path): os.Path = root / "model-rules.dsl"
  def modeOf(root: os.Path): os.Path = root / "model-rules.mode"

  /** 读取模式（缺失或非法值 ⇒ 默认 follow）。 */
  def mode(root: os.Path): String =
    val f = modeOf(root)
    if !os.exists(f) then DefaultMode
    else
      val m = os.read(f).trim
      if Modes.contains(m) then m else DefaultMode

  /** 三态门：仅 rules 态向求值侧暴露脚本。 */
  def effective(root: os.Path, l: Loaded): Loaded =
    if mode(root) == "rules" then l else EmptyLoaded

  /** 从给定数据根加载脚本（纯读取 + mtime/size 缓存）。 */
  def load(root: os.Path): Loaded =
    val f = scriptOf(root)
    if !os.exists(f) then EmptyLoaded
    else
      val mtime = os.mtime(f)
      val size = os.size(f)
      cache match
        case Some((m, s, l)) if m == mtime && s == size => l
        case _ =>
          val loaded = ModelRuleDsl.parse(os.read(f)) match
            case Right(script) => Loaded(script, None)
            case Left(errs) =>
              val first = errs.headOption.map(e => s"line ${e.line}: ${e.message}").getOrElse("parse error")
              Loaded(ModelRuleDsl.Script(Nil), Some(first))
          cache = Some((mtime, size, loaded))
          loaded

  /** 生产入口：当前数据根的脚本，经三态门。 */
  def current: Loaded = effective(PathUtil.dataRoot, load(PathUtil.dataRoot))

  /** 原子写（同目录临时文件 + move）。 */
  def atomicWrite(f: os.Path, content: String): Unit =
    os.makeDir.all(f / os.up)
    val tmp = f / os.up / s".${f.last}.tmp"
    os.write.over(tmp, content)
    os.move(tmp, f, replaceExisting = true, atomicMove = true)

  /** 保存校验 + 落盘。Left = 行号化错误列表（不写盘）。 */
  def save(root: os.Path, source: String, catalog: ModelRuleDsl.Catalog): Either[List[ModelRuleDsl.RuleError], Unit] =
    ModelRuleDsl.check(source, catalog).map { _ =>
      atomicWrite(scriptOf(root), source)
      cache = None
    }

  def setMode(root: os.Path, m: String): Either[String, Unit] =
    if !Modes.contains(m) then Left(s"unknown mode '$m' — valid: ${Modes.mkString(", ")}")
    else
      atomicWrite(modeOf(root), m + "\n")
      Right(())

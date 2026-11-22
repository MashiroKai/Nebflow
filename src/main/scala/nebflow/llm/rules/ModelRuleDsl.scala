package nebflow.llm.rules

import java.time.LocalDateTime

/**
 * 模型分配规则 DSL（模型分配规则引擎 P2）：语法、解析、校验、求值、框图 JSON 导出。
 *
 * 纯模块：无 UI、无 IO、无请求路径接线（接线与旧字段迁移属 P3）。
 * 设计正本：`model-rule-engine-and-panel-design`（§3 语法/求值/框图，§8 N1 调用点）。
 *
 * 语义要点（与设计稿一致）：
 *   - 命中 = `when` 全部为真，且 `level` / `slot` / `call` 各维度（若写）包含会话取值；
 *   - `when` 的跨午夜归属与 `ModelAvailability.covers`（availability.scala:43-54）同口径：
 *     清晨腿（m < to）归属于前一日的星期；
 *   - `model` 链首 = 命中规则中 priority 最高者（同级按文件序）；`overflow` 只记录、不进请求链
 *     （约束 A：仅派发时新 actor，由调用方使用）；
 *   - `freeze` 作用域 = 命中规则的 when ∩ level ∩ slot ∩ call ∩ freeze model 子句（缺省 = 全部模型）；
 *   - `unavailable` 取并集过滤；`switch` 只产生提议（N2：未确认不生效）；
 *   - 无命中 ⇒ 候选链原样返回（与 P1 无规则行为相同）。
 */
object ModelRuleDsl:

  val LevelValues: Set[String] = Set("root", "team", "subtask", "node")
  val SlotValues: Set[String] = Set("main", "dispatcher", "kernel", "node", "subagent", "small-llm")
  val CallValues: Set[String] = Set(
    "main-chat", "delegate", "subagent", "canvas-ask", "selection-ask",
    "model-config-ask", "small-llm", "decision-route"
  )
  /** 星期 1..7（ISO：周一=1，周日=7）。 */
  val DayCount: Int = 7
  val MinutesPerDay: Int = 1440
  private val AllCells: Set[Int] = (0 until DayCount * MinutesPerDay).toSet

  // ── 错误 / 目录 ───────────────────────────────────────────────────────────

  final case class RuleError(line: Int, message: String)

  /** 保存期校验用的 provider → models 目录（未知 provider / model 报错）。 */
  final case class Catalog(models: Map[String, Set[String]])

  // ── AST ──────────────────────────────────────────────────────────────────

  /** 时段 `[from,to)`，分钟粒度（0..1440）；from != to。 */
  final case class TimeCond(from: Int, to: Int)

  sealed trait Clause:
    def line: Int
    def kind: String

  final case class Priority(value: Int, line: Int) extends Clause:
    def kind: String = "priority"
  final case class When(time: Option[TimeCond], weekdays: Option[Set[Int]], line: Int) extends Clause:
    def kind: String = "when"
  final case class Level(values: List[String], line: Int) extends Clause:
    def kind: String = "level"
  final case class Slot(values: List[String], line: Int) extends Clause:
    def kind: String = "slot"
  final case class Call(values: List[String], line: Int) extends Clause:
    def kind: String = "call"
  /** 动作：链首模型，值为 `provider/model`。 */
  final case class ModelAct(ref: String, line: Int) extends Clause:
    def kind: String = "model"
  final case class Concurrency(provider: String, limit: Int, line: Int) extends Clause:
    def kind: String = "concurrency"
  /** 动作：新 agent actor 超并发去处（仅派发时，约束 A）。 */
  final case class OverflowAct(ref: String, line: Int) extends Clause:
    def kind: String = "overflow"
  /** 动作：该时段不可用的模式（`provider/*` 或 `provider/model`）。 */
  final case class Unavailable(patterns: List[String], line: Int) extends Clause:
    def kind: String = "unavailable"
  /** 动作：计划性冻结；None = 全部模型（`*`）。 */
  final case class Freeze(patterns: Option[List[String]], line: Int) extends Clause:
    def kind: String = "freeze"
  /** 动作：会话内切模型提议（N2，必须 confirm）。 */
  final case class SwitchAct(ref: String, line: Int) extends Clause:
    def kind: String = "switch"

  final case class Rule(name: String, line: Int, clauses: List[Clause]):
    def priority: Int = clauses.collectFirst { case Priority(v, _) => v }.getOrElse(0)

  final case class Script(rules: List[Rule])

  // ── 词法 ─────────────────────────────────────────────────────────────────

  private final case class Tok(text: String, line: Int)

  private def tokenize(src: String): List[Tok] =
    src.linesIterator.zipWithIndex.toList.flatMap { case (raw, i) =>
      val code = raw.indexOf('#') match
        case -1 => raw
        case k => raw.substring(0, k)
      val spaced = code
        .replace("{", " { ")
        .replace("}", " } ")
        .replace(",", " , ")
        .replace("=", " = ")
      spaced.split("\\s+").toList.filter(_.nonEmpty).map(t => Tok(t, i + 1))
    }

  // ── 解析 ─────────────────────────────────────────────────────────────────

  private final class ParseFail(val line: Int, message: String) extends RuntimeException(message)

  private val SpanRe = """^(\d{1,2}):(\d{2})\.\.(\d{1,2}):(\d{2})$""".r

  private final class Parser(toks: Vector[Tok]):
    private var pos = 0

    private def peekText: Option[String] = toks.lift(pos).map(_.text)

    private def fail(line: Int, msg: String): Nothing = throw new ParseFail(line, msg)

    private def next(what: String): Tok =
      if pos >= toks.size then fail(toks.lastOption.map(_.line).getOrElse(1), s"脚本意外结束，缺少$what")
      else
        val t = toks(pos)
        pos += 1
        t

    private def expect(s: String): Unit =
      val t = next(s"'$s'")
      if t.text != s then fail(t.line, s"期望 '$s'，实际 '${t.text}'")

    /** 逗号分隔的列表（`a, b, c`）；列表在无逗号处结束。 */
    private def commaList(what: String): List[Tok] =
      val buf = List.newBuilder[Tok]
      buf += next(what)
      while peekText.contains(",") do
        pos += 1
        buf += next(what)
      buf.result()

    private def intOf(t: Tok): Int =
      t.text.toIntOption.getOrElse(fail(t.line, s"期望整数，实际 '${t.text}'"))

    private def refOf(t: Tok): String =
      val k = t.text.indexOf('/')
      if k <= 0 || k >= t.text.length - 1 then fail(t.line, s"引用格式应为 provider/model：'${t.text}'")
      t.text

    private def patternOf(t: Tok): String =
      if t.text == "*" || (t.text.endsWith("/*") && t.text.indexOf('/') > 0) then t.text
      else refOf(t)

    private def dimValues(kind: String, allowed: Set[String]): List[String] =
      commaList(kind).map { t =>
        if !allowed.contains(t.text) then fail(t.line, s"未知 $kind 值 '${t.text}'")
        t.text
      }

    private def parseTimeSpan(t: Tok): TimeCond = t.text match
      case SpanRe(h1, m1, h2, m2) =>
        val (hh1, mm1, hh2, mm2) = (h1.toInt, m1.toInt, h2.toInt, m2.toInt)
        if hh1 > 24 || hh2 > 24 || mm1 > 59 || mm2 > 59 then fail(t.line, s"时段超出范围：'${t.text}'")
        if (hh1 == 24 && mm1 != 0) || (hh2 == 24 && mm2 != 0) then fail(t.line, s"24 点只能写 24:00：'${t.text}'")
        val from = hh1 * 60 + mm1
        val to = hh2 * 60 + mm2
        if from >= MinutesPerDay then fail(t.line, s"时段起点必须早于 24:00：'${t.text}'")
        if from == to then fail(t.line, s"零长度时段（from == to）：'${t.text}'")
        TimeCond(from, to)
      case _ => fail(t.line, s"时段格式应为 HH:mm..HH:mm：'${t.text}'")

    private def parseWhen(line: Int): When =
      var time: Option[TimeCond] = None
      var days: Option[Set[Int]] = None
      while peekText.contains("time") || peekText.contains("weekday") do
        val k = next("条件")
        if k.text == "time" then
          if time.isDefined then fail(k.line, "when 中 time 重复")
          time = Some(parseTimeSpan(next("时段 HH:mm..HH:mm")))
        else
          if days.isDefined then fail(k.line, "when 中 weekday 重复")
          days = Some(commaList("星期").map { t =>
            val d = intOf(t)
            if d < 1 || d > DayCount then fail(t.line, s"星期应为 1..7：'${t.text}'")
            d
          }.toSet)
      if time.isEmpty && days.isEmpty then fail(line, "when 缺少 time / weekday 条件")
      When(time, days, line)

    private def parseClause(): Clause =
      val t = next("子句")
      val ln = t.line
      t.text match
        case "priority" => Priority(intOf(next("priority 数值")), ln)
        case "when" => parseWhen(ln)
        case "level" => Level(dimValues("level", LevelValues), ln)
        case "slot" => Slot(dimValues("slot", SlotValues), ln)
        case "call" => Call(dimValues("call", CallValues), ln)
        case "model" => ModelAct(refOf(next("REF provider/model")), ln)
        case "overflow" => OverflowAct(refOf(next("REF provider/model")), ln)
        case "concurrency" =>
          val p = next("provider")
          if p.text.contains('/') || p.text == "=" || p.text == "," then fail(p.line, s"concurrency 的 provider 非法：'${p.text}'")
          expect("=")
          val n = intOf(next("并发上限"))
          if n < 1 then fail(ln, s"并发上限须 ≥ 1：$n")
          Concurrency(p.text, n, ln)
        case "unavailable" => Unavailable(commaList("PATTERN").map(patternOf), ln)
        case "freeze" =>
          if peekText.contains("model") then
            pos += 1
            Freeze(Some(commaList("PATTERN").map(patternOf)), ln)
          else Freeze(None, ln)
        case "switch" =>
          val ref = refOf(next("REF provider/model"))
          val c = next("confirm")
          if c.text != "confirm" then fail(c.line, "switch 必须以 confirm 声明（N2：未确认不生效）")
          SwitchAct(ref, ln)
        case other => fail(ln, s"未知子句 '$other'")

    private def parseRule(): Rule =
      val kw = next("'rule'")
      if kw.text != "rule" then fail(kw.line, s"顶层只允许 rule 块，实际 '${kw.text}'")
      val nm = next("规则名")
      if Set("{", "}", ",", "=").contains(nm.text) then fail(nm.line, "规则名缺失")
      expect("{")
      val buf = List.newBuilder[Clause]
      while peekText.exists(_ != "}") do buf += parseClause()
      expect("}")
      Rule(nm.text, kw.line, buf.result())

    def parseAll(): List[Rule] =
      val buf = List.newBuilder[Rule]
      while pos < toks.size do buf += parseRule()
      buf.result()

  /** 解析脚本文本（语法层）。失败为首个错误（fail-fast）。 */
  def parse(src: String): Either[List[RuleError], Script] =
    try
      Right(Script(new Parser(tokenize(src).toVector).parseAll()))
    catch {
      case e: ParseFail => Left(List(RuleError(e.line, e.getMessage)))
    }

  // ── 校验（保存期）────────────────────────────────────────────────────────

  private val SingleKinds: Set[String] =
    Set("priority", "when", "level", "slot", "call", "model", "overflow", "switch")
  private val ActionKinds: Set[String] = Set("model", "overflow", "switch", "concurrency")

  /** 单个调用点 / 动作的目录校验（未知 provider / model）。 */
  private def catalogErrors(c: Clause, cat: Catalog): List[RuleError] =
    def providerErr(p: String, ln: Int): List[RuleError] =
      if cat.models.contains(p) then Nil else List(RuleError(ln, s"未知 provider '$p'"))
    def refErr(ref: String, ln: Int): List[RuleError] =
      val k = ref.indexOf('/')
      val p = ref.take(k)
      val m = ref.drop(k + 1)
      cat.models.get(p) match
        case None => List(RuleError(ln, s"未知 provider '$p'"))
        case Some(ms) if !ms.contains(m) => List(RuleError(ln, s"未知 model '$ref'"))
        case Some(_) => Nil
    def patErr(pat: String, ln: Int): List[RuleError] =
      if pat == "*" then Nil
      else if pat.endsWith("/*") then providerErr(pat.dropRight(2), ln)
      else refErr(pat, ln)
    c match
      case ModelAct(ref, ln) => refErr(ref, ln)
      case OverflowAct(ref, ln) => refErr(ref, ln)
      case SwitchAct(ref, ln) => refErr(ref, ln)
      case Concurrency(p, _, ln) => providerErr(p, ln)
      case Unavailable(ps, ln) => ps.flatMap(patErr(_, ln))
      case Freeze(Some(ps), ln) => ps.flatMap(patErr(_, ln))
      case _ => Nil

  /** 规则的作用域（when ∩ level ∩ slot ∩ call）用于冲突判定。 */
  private final case class Scope(
    cells: Set[Int],
    levels: Option[Set[String]],
    slots: Option[Set[String]],
    calls: Option[Set[String]]
  )

  /** 第 `w`（1..7）日 `m`（0..1439）分钟是否被 when 覆盖（跨午夜归属同 availability.covers）。 */
  private def coversCell(wh: When, w: Int, m: Int): Boolean =
    val prev = if w == 1 then DayCount else w - 1
    def dayOk(d: Int): Boolean = wh.weekdays.forall(_.contains(d))
    wh.time match
      case None => dayOk(w)
      case Some(TimeCond(f, t)) =>
        if f < t then dayOk(w) && m >= f && m < t
        else (dayOk(w) && m >= f) || (dayOk(prev) && m < t)

  private def cellsOf(wh: Option[When]): Set[Int] = wh match
    case None => AllCells
    case Some(w) =>
      (0 until DayCount * MinutesPerDay).filter { c =>
        coversCell(w, c / MinutesPerDay + 1, c % MinutesPerDay)
      }.toSet

  private def scopeOf(r: Rule): Scope = Scope(
    cells = cellsOf(r.clauses.collectFirst { case w: When => w }),
    levels = r.clauses.collectFirst { case Level(vs, _) => vs.toSet },
    slots = r.clauses.collectFirst { case Slot(vs, _) => vs.toSet },
    calls = r.clauses.collectFirst { case Call(vs, _) => vs.toSet }
  )

  private def optOverlap[A](a: Option[Set[A]], b: Option[Set[A]]): Boolean = (a, b) match
    case (Some(x), Some(y)) => x.intersect(y).nonEmpty
    case _ => true

  private def scopesOverlap(a: Scope, b: Scope): Boolean =
    a.cells.intersect(b.cells).nonEmpty &&
      optOverlap(a.levels, b.levels) &&
      optOverlap(a.slots, b.slots) &&
      optOverlap(a.calls, b.calls)

  /** 两条动作子句是否冲突：同 priority、同动作类型、作用域重叠（concurrency 另需同 provider）。 */
  private def actionsConflict(
    ca: Clause, cb: Clause,
    sa: Scope, sb: Scope,
    pa: Int, pb: Int
  ): Boolean =
    val sameProvider = (ca, cb) match
      case (Concurrency(x, _, _), Concurrency(y, _, _)) => x == y
      case _ => true
    ca.kind == cb.kind && pa == pb && sameProvider && scopesOverlap(sa, sb)

  private def conflictErrors(rules: List[Rule]): List[RuleError] =
    val occ: Vector[(Int, Rule, Clause)] = rules.zipWithIndex.flatMap { case (r, ri) =>
      r.clauses.filter(c => ActionKinds.contains(c.kind)).map(c => (ri, r, c))
    }.toVector
    val scopes: Vector[Scope] = rules.map(scopeOf).toVector
    val pairs = for
      i <- occ.indices.toList
      j <- ((i + 1) until occ.size).toList
      if occ(i)._1 != occ(j)._1 || (occ(i)._3.kind == "concurrency" && occ(j)._3.kind == "concurrency")
      if actionsConflict(occ(i)._3, occ(j)._3, scopes(occ(i)._1), scopes(occ(j)._1), occ(i)._2.priority, occ(j)._2.priority)
    yield (occ(i), occ(j))
    pairs.map { case ((_, ra, ca), (_, _, cb)) =>
      RuleError(
        cb.line,
        s"与规则 '${ra.name}'（第 ${ca.line} 行）冲突：同一 priority、同一动作（${ca.kind}）且作用域重叠"
      )
    }

  /** 保存期校验：重名、单值子句重复、目录（provider/model）、同 priority 同动作作用域冲突。 */
  def validate(script: Script, catalog: Catalog): List[RuleError] =
    val rules = script.rules
    val dupNames = rules.zipWithIndex.flatMap { case (r, i) =>
      if rules.take(i).exists(_.name == r.name) then List(RuleError(r.line, s"规则名重复：'${r.name}'"))
      else Nil
    }
    val dupClauses = rules.flatMap { r =>
      val (_, errs) = r.clauses.foldLeft((Set.empty[String], List.empty[RuleError])) { case ((seen, acc), c) =>
        if SingleKinds.contains(c.kind) && seen.contains(c.kind) then
          (seen, acc :+ RuleError(c.line, s"规则 '${r.name}' 中子句 ${c.kind} 重复（每条规则至多一次）"))
        else (seen + c.kind, acc)
      }
      errs
    }
    val catErrs = rules.flatMap(_.clauses.flatMap(catalogErrors(_, catalog)))
    dupNames ++ dupClauses ++ catErrs ++ conflictErrors(rules)

  /** 保存入口：解析 + 校验。任一失败即 Left（fail-closed，不靠求值顺序碰运气）。 */
  def check(src: String, catalog: Catalog): Either[List[RuleError], Script] =
    parse(src).flatMap { s =>
      val errs = validate(s, catalog)
      if errs.isEmpty then Right(s) else Left(errs)
    }

  // ── 求值（纯函数）────────────────────────────────────────────────────────

  /** 会话取值。level / slot 为 None 时，写了对应维度的规则不命中。 */
  final case class Session(level: Option[String], slot: Option[String], call: String, at: LocalDateTime)

  /** 一步命中依据（trace 元素）。 */
  final case class Step(rule: String, action: String, detail: String)

  /** N2：切模型提议（未确认不生效，本函数不改候选链）。 */
  final case class SwitchProposal(rule: String, ref: String)

  final case class Outcome(
    candidates: List[String],
    head: Option[String],
    overflow: Option[String],
    concurrency: Map[String, Int],
    switches: List[SwitchProposal],
    matched: List[String],
    trace: List[Step]
  ):
    def noRuleHit: Boolean = matched.isEmpty

  /** 模式匹配：`*` 全部；`p/*` 同 provider；否则精确 `provider/model`。 */
  def patternMatches(pattern: String, ref: String): Boolean =
    if pattern == "*" then true
    else if pattern.endsWith("/*") then ref.startsWith(pattern.dropRight(1))
    else pattern == ref

  private def matchesRule(r: Rule, s: Session, call: String, weekday: Int, minute: Int): Boolean =
    r.clauses.forall {
      case w: When => coversCell(w, weekday, minute)
      case Level(vs, _) => s.level.exists(vs.contains)
      case Slot(vs, _) => s.slot.exists(vs.contains)
      case Call(vs, _) => vs.contains(call)
      case _ => true
    }

  private def excluded(
    chain: List[String],
    pats: List[(String, String)],
    action: String
  ): (List[String], List[Step]) =
    chain.foldLeft((List.empty[String], List.empty[Step])) { case ((kept, steps), ref) =>
      pats.find { case (_, p) => patternMatches(p, ref) } match
        case Some((rn, p)) => (kept, steps :+ Step(rn, action, s"$ref 被 $p 排除"))
        case None => (kept :+ ref, steps)
    }

  /**
   * 求值：对会话给出候选链。`base` = 无规则时的现行候选链（P1 解析结果，`provider/model` 列表）。
   * 无命中 ⇒ `candidates == base`（逐项相同）。
   * 未知调用点（不在 [[CallValues]]）⇒ 按 small-llm 语义 fail-closed，trace 记 `unknown-call-point`（N1）。
   */
  def evaluate(script: Script, s: Session, base: List[String]): Outcome =
    val unknownCall = !CallValues.contains(s.call)
    val call = if unknownCall then "small-llm" else s.call
    val unknownSteps =
      if unknownCall then List(Step("-", "unknown-call-point", s"'${s.call}' fail-closed → small-llm")) else Nil
    val weekday = s.at.getDayOfWeek.getValue
    val minute = s.at.getHour * 60 + s.at.getMinute
    val hits: List[Rule] = script.rules.zipWithIndex
      .filter { case (r, _) => matchesRule(r, s, call, weekday, minute) }
      .sortBy { case (r, i) => (-r.priority, i) }
      .map(_._1)

    val heads: List[(String, String)] = hits.flatMap(r => r.clauses.collect { case ModelAct(ref, _) => (r.name, ref) })
    val overflows: List[(String, String)] = hits.flatMap(r => r.clauses.collect { case OverflowAct(ref, _) => (r.name, ref) })
    val concs: Map[String, (Int, String)] = hits
      .flatMap(r => r.clauses.collect { case Concurrency(p, n, _) => (r.name, p, n) })
      .foldLeft(Map.empty[String, (Int, String)]) { case (acc, (rn, p, n)) =>
        if acc.contains(p) then acc else acc.updated(p, (n, rn))
      }
    val switches: List[SwitchProposal] =
      hits.flatMap(r => r.clauses.collect { case SwitchAct(ref, _) => SwitchProposal(r.name, ref) })
    val unav: List[(String, String)] =
      hits.flatMap(r => r.clauses.collect { case Unavailable(ps, _) => ps.map(p => (r.name, p)) }.flatten)
    val frz: List[(String, String)] =
      hits.flatMap(r => r.clauses.collect { case Freeze(ps, _) => ps.getOrElse(List("*")).map(p => (r.name, p)) }.flatten)

    val headOpt = heads.headOption
    val withHead: List[String] = headOpt match
      case Some((_, ref)) => ref :: base.filterNot(_ == ref)
      case None => base
    val (afterFreeze, frozenSteps) = excluded(withHead, frz, "freeze")
    val (afterUnav, unavSteps) = excluded(afterFreeze, unav, "unavailable")

    val headSteps = headOpt.toList.map { case (rn, ref) => Step(rn, "model", s"链首 $ref") }
    val overflowSteps = overflows.headOption.toList.map { case (rn, ref) =>
      Step(rn, "overflow", s"派发时新 actor 去向 $ref（不进请求链）")
    }
    val concSteps = concs.toList.sortBy(_._1).map { case (p, (n, rn)) => Step(rn, "concurrency", s"$p ≤ $n") }
    val switchSteps = switches.map(sp => Step(sp.rule, "switch", s"提议 ${sp.ref}（N2：未确认不生效）"))

    Outcome(
      candidates = afterUnav,
      head = headOpt.map(_._2),
      overflow = overflows.headOption.map(_._2),
      concurrency = concs.map { case (p, (n, _)) => p -> n },
      switches = switches,
      matched = hits.map(_.name),
      trace = unknownSteps ++ headSteps ++ overflowSteps ++ concSteps ++ switchSteps ++ frozenSteps ++ unavSteps
    )

  // ── 框图 JSON 导出（DSL → 图）──────────────────────────────────────────

  /** 框图节点：每个子句一个节点（when 的 time / weekday 各一个）。 */
  final case class Node(id: String, kind: String, label: String, ruleId: String, srcLine: Int)
  final case class Edge(from: String, to: String, kind: String)

  private def fmtMin(min: Int): String = "%02d:%02d".format(min / 60, min % 60)

  private def specsOf(c: Clause): List[(String, String, String, Int)] = c match
    case Priority(v, ln) => List(("prio", "priority", s"priority $v", ln))
    case w: When =>
      w.time.map(t => ("when", "when-time", s"${fmtMin(t.from)}–${fmtMin(t.to)}", w.line)).toList ++
        w.weekdays.map(d => ("weekday", "when-weekday", d.toList.sorted.mkString(", "), w.line)).toList
    case Level(vs, ln) => List(("level", "level", vs.mkString(", "), ln))
    case Slot(vs, ln) => List(("slot", "slot", vs.mkString(", "), ln))
    case Call(vs, ln) => List(("call", "call", vs.mkString(", "), ln))
    case ModelAct(ref, ln) => List(("model", "model", ref, ln))
    case Concurrency(p, n, ln) => List(("conc", "concurrency", s"$p ≤ $n", ln))
    case OverflowAct(ref, ln) => List(("over", "overflow", ref, ln))
    case Unavailable(ps, ln) => List(("unavail", "unavailable", ps.mkString(", "), ln))
    case Freeze(ps, ln) => List(("freeze", "freeze", ps.fold("全部模型")(_.mkString(", ")), ln))
    case SwitchAct(ref, ln) => List(("switch", "switch", s"$ref confirm", ln))

  /** 图：节点按规则、子句顺序排列；同规则相邻节点连 `match` 边，指向 overflow 的边为 `overflow`。 */
  def graph(script: Script): (List[Node], List[Edge]) =
    val perRule: List[(List[Node], List[Edge])] = script.rules.zipWithIndex.map { case (r, ri) =>
      val raw = r.clauses.flatMap(specsOf)
      val (nodes, _) = raw.foldLeft((List.empty[Node], Map.empty[String, Int])) {
        case ((acc, seen), (short, kind, label, ln)) =>
          val n = seen.getOrElse(short, 0) + 1
          val id = if n == 1 then s"r${ri + 1}.$short" else s"r${ri + 1}.$short$n"
          (acc :+ Node(id, kind, label, r.name, ln), seen.updated(short, n))
      }
      val edges = nodes.sliding(2).toList.collect { case List(a, b) =>
        Edge(a.id, b.id, if b.kind == "overflow" then "overflow" else "match")
      }
      (nodes, edges)
    }
    (perRule.flatMap(_._1), perRule.flatMap(_._2))

  private def jstr(s: String): String =
    val sb = new StringBuilder("\"")
    s.foreach {
      case '"' => sb.append('\\').append('"')
      case '\\' => sb.append('\\').append('\\')
      case '\n' => sb.append('\\').append('n')
      case '\r' => sb.append('\\').append('r')
      case '\t' => sb.append('\\').append('t')
      case c if c < ' ' => sb.append('\\').append('u').append("%04x".format(c.toInt))
      case c => sb.append(c)
    }
    sb.append('"').toString

  private def jobj(fields: List[(String, String)]): String =
    fields.map { case (k, v) => jstr(k) + ":" + v }.mkString("{", ",", "}")

  /** 框图 JSON：`{"nodes":[{id,kind,label,ruleId,srcLine}],"edges":[{from,to,kind}]}`。 */
  def diagramJson(script: Script): String =
    val (nodes, edges) = graph(script)
    val ns = nodes.map { n =>
      jobj(List(
        "id" -> jstr(n.id),
        "kind" -> jstr(n.kind),
        "label" -> jstr(n.label),
        "ruleId" -> jstr(n.ruleId),
        "srcLine" -> n.srcLine.toString
      ))
    }
    val es = edges.map { e =>
      jobj(List("from" -> jstr(e.from), "to" -> jstr(e.to), "kind" -> jstr(e.kind)))
    }
    "{" + jstr("nodes") + ":[" + ns.mkString(",") + "]," + jstr("edges") + ":[" + es.mkString(",") + "]}"

end ModelRuleDsl

package nebflow.core.scheduler

import munit.FunSuite

import scala.io.Source
import scala.util.matching.Regex

import nebflow.actor.InjectionAttribution

/**
 * schedcard 批（2026-10-29，作者令「定时任务卡片防退化 + stand 配色」）——
 * 「schedule 标记 → 专属卡片渲染分支」的**纯文本契约门**（同
 * `InjectionSourceContractSpec` 形态：不起服务、不读运行时状态，改线即红）。
 *
 * 症状与根因（作者 2026-10-29 13:41 报）：定时任务到点触发注入会话的指令，在
 * console 消息流里渲染成了普通绿色（用户侧）消息气泡——与作者手打消息无法区分。
 * 根因 = 触发行落盘/广播**零标记**（裸 `UiMessage.User` + 死帧 `userMessage`：
 * 前端零消费者，卡片只有刷新后才以绿泡形态出现）。修法 = 后端标记
 * （`injected=true, source="schedule"` + `schedule` 卡片载荷）+ 前端
 * `buildInjectedRow` 专属卡分支（live 与两条史恢复路径同 builder 同构）。
 *
 * 本 spec 钉死：
 *  ① **防退化主钉**：前端 builder 的 `source === 'schedule'` 显式分支**先于**
 *     通用注入泡组装（带标记注入行永不落通用分支——分支先 return）；卡类名
 *     `.schedule-card` + `data-nf-schedule` 在场。
 *  ② **无标记消息回归不破**：通用注入泡组装原样保留（非 schedule 源照走）；
 *     无标记 user 行的绿泡分支（`bubble user`）在两条史恢复路径原样保留
 *     （旧历史行按现状渲染，不追溯）。
 *  ③ **标记字段全链贯通**：后端写点（ScheduledTaskActor 落盘 + 广播 `user` 帧）
 *     ⇄ live 消费（main.js `msg.schedule`）⇄ 史恢复（persistence.js 两腿
 *     `m.schedule`）⇄ 缓存落键（saveMsg `schedule`）。
 *  ④ **源词表登记**：`BackendNamedSources` 含 "schedule"（呈现面定名；逐字 pin
 *     在 `InjectionSourceContractSpec` ①，此处做包含性直读让本门自洽）。
 *  ⑤ **双显防复发**：旧瞬态 toast 注入腿已退役（`reminder-trigger-bubble`
 *     在 js 源码零命中——卡片唯一呈现 = 注入帧专属卡分支）。
 *  ⑥ **i18n 键面**：卡片 chip/时间行的双语键在两 locale 同批落位。
 */
class ScheduleCardContractSpec extends FunSuite:

  private val repoRoot = os.pwd

  private def read(rel: String): String =
    // os-lib 不接受带 `/` 的单段路径 —— 逐段折叠（InjectionSourceContractSpec 同款）。
    val path = rel.split("/").foldLeft(repoRoot)(_ / _)
    assert(os.exists(path), s"源码不存在：$rel（本 spec 必须在仓根运行）")
    val src = Source.fromFile(path.toIO, "UTF-8")
    try src.mkString
    finally src.close()

  private lazy val injectedRowsJs = read("src/main/resources/web/js/chat/injectedRows.js")
  private lazy val persistenceJs = read("src/main/resources/web/js/persistence.js")
  private lazy val mainJs = read("src/main/resources/web/js/main.js")
  private lazy val scheduledTaskJs = read("src/main/resources/web/js/scheduled-task.js")
  private lazy val actorScala =
    read("src/main/scala/nebflow/core/scheduler/ScheduledTaskActor.scala")
  private lazy val zhLocale = read("src/main/resources/web/js/locales/zh-CN.js")
  private lazy val enLocale = read("src/main/resources/web/js/locales/en.js")

  /** `buildInjectedRow` 函数体（自声明到下一个顶层 `export`/`function`）。 */
  private lazy val builderBody: String =
    val start = injectedRowsJs.indexOf("export function buildInjectedRow")
    assert(start >= 0, "injectedRows.js 里找不到 buildInjectedRow —— 前端呈现入口被搬迁/改名？")
    val nextDecl = injectedRowsJs.indexOf("\nfunction ", start + 1)
    injectedRowsJs.substring(start, if nextDecl < 0 then injectedRowsJs.length else nextDecl)

  // ── ① 防退化主钉：专属卡分支先于通用组装 ──────────────────────────────

  test("① schedule 分支显式在场，且先于通用注入泡组装（先 return，永不落通用分支）"):
    val branchIdx = builderBody.indexOf("source === 'schedule'")
    assert(branchIdx >= 0, "buildInjectedRow 缺 source === 'schedule' 专属卡分支 —— 防退化钉面被拆？")
    val genericIdx = builderBody.indexOf("bubble.className = 'bubble injected'")
    assert(genericIdx > branchIdx,
      "通用注入泡组装出现在 schedule 分支之前 —— 带标记行会先落通用形态（防退化被击穿）")
    assert(builderBody.contains("buildScheduleCardRow(row"),
      "schedule 分支必须走 buildScheduleCardRow（专属卡 builder 单点）")
    assert(builderBody.contains(".schedule-card") || injectedRowsJs.contains(".schedule-card"),
      "卡类名 schedule-card 缺席 —— 专属卡视觉挂点被改名？")
    assert(injectedRowsJs.contains("data-nf-schedule") || injectedRowsJs.contains("dataset.nfSchedule"),
      "data-nf-schedule 数据锚缺席 —— spec/测试锚点被拆？")

  test("①b 卡片结构面：chip/任务名/重复标/时间行/引用行 builder 在场"):
    for (needle, what) <- Seq(
      ("schedule-card-chip", "「定时任务」chip"),
      ("schedule-card-name", "任务名（有 name 才显示）"),
      ("schedule-card-repeat", "重复小标（循环任务）"),
      ("schedule-card-time", "预定时间行"),
      ("schedule-card-ref", "参考文档行"),
      ("schedule-card-body", "正文容器")
    )
    do assert(injectedRowsJs.contains(needle), s"卡片结构缺 $what（$needle）")

  // ── ② 无标记消息回归不破 ──────────────────────────────────────────────

  test("② 通用注入泡组装原样保留（非 schedule 源照走既有分支）"):
    assert(builderBody.contains("bubble.className = 'bubble injected'"),
      "通用注入泡组装被移除 —— 非 schedule 源（mail/delegate/…）回归被破")
    assert(builderBody.contains("bindCollapsibleToggle"),
      "通用注入泡折叠交互被移除 —— 既有注入行形态回归被破")
    assert(builderBody.contains("injectedSourceLabel("),
      "通用标签组装被移除 —— 词表外 source 回落渲染回归被破")

  test("②b 无标记 user 行的绿泡分支在两条史恢复路径原样保留（旧历史行不追溯）"):
    // restoreFromStorage（localStorage 腿）与 buildHistoryRow（后端历史分页腿）：
    // 无标记行（!m.injected || !m.source）照旧走 `.bubble user` 绿泡分支。
    assert(persistenceJs.contains("bubble.className = 'bubble user'"),
      "史恢复绿泡分支被移除 —— 无标记消息回归被破")
    val injectedGuardCount = "m\\.injected && m\\.source".r
      .findAllMatchIn(persistenceJs).size
    assert(injectedGuardCount >= 2,
      s"注入行判据（m.injected && m.source）应覆盖两条史恢复腿，got $injectedGuardCount")

  // ── ③ 标记字段全链贯通 ────────────────────────────────────────────────

  test("③ 后端写点：ScheduledTaskActor 落盘带标记 + 卡片载荷，广播为注入 user 帧"):
    assert(actorScala.contains("source = Some(\"schedule\")"),
      "落盘行缺 source=schedule 标记 —— 前端分支永远不触发（退化根源）")
    assert(actorScala.contains("injected = true"),
      "落盘行缺 injected=true —— 无标记行走绿泡分支（退化根源）")
    assert(actorScala.contains("schedule = Some("),
      "落盘行缺 schedule 卡片载荷 —— 卡片降级为无载荷形态")
    assert(actorScala.contains("\"type\" -> \"user\""),
      "广播帧不是注入 user 帧 —— 触发即渲染腿断裂（须刷新才见卡）")
    assert(!actorScala.contains("\"userMessage\""),
      "死帧 userMessage 广播仍在 —— 双轨发射面未收口（schedcard 批收口令）")

  test("③b live 消费：main.js 读 msg.schedule（缓存落键 + 渲染透传双读点）"):
    assert(mainJs.contains("msg.schedule"),
      "main.js 注入帧消费点未读 msg.schedule —— live 卡降级为无载荷形态")

  test("③c 史恢复：persistence.js 两腿透传 m.schedule"):
    val count = "m\\.schedule".r.findAllMatchIn(persistenceJs).size
    assert(count >= 2, s"persistence.js 应有 ≥2 处 m.schedule（localStorage 腿 + 后端历史腿），got $count")

  // ── ④ 源词表登记 ──────────────────────────────────────────────────────

  test("④ BackendNamedSources 登记 schedule（逐字 pin 在 InjectionSourceContractSpec ①）"):
    assert(InjectionAttribution.BackendNamedSources.contains("schedule"),
      "schedule 未入后端自定名词表 —— 前端登记门（表项/显式分支）失去覆盖对象")

  // ── ⑤ 双显防复发 ──────────────────────────────────────────────────────

  test("⑤ 旧瞬态 toast 注入腿已退役（卡片唯一呈现 = 注入帧专属卡分支）"):
    assert(!scheduledTaskJs.contains("reminder-trigger-bubble"),
      "scheduled-task.js 仍在手拼瞬态 toast —— 与专属卡双显（复发）")
    assert(!scheduledTaskJs.contains("chat.appendChild"),
      "scheduled-task.js 仍有聊天流 DOM 注入 —— 卡片呈现面必须唯一收口在注入帧分支")

  // ── ⑥ i18n 键面 ──────────────────────────────────────────────────────

  test("⑥ 卡片文案双语键同批落位（zh-CN + en）"):
    for (key, what) <- Seq(("'task.cardChip'", "chip 文案"), ("'task.cardScheduledAt'", "预定时间文案"))
    do
      assert(zhLocale.contains(key), s"zh-CN 缺 $what（$key）")
      assert(enLocale.contains(key), s"en 缺 $what（$key）")

end ScheduleCardContractSpec

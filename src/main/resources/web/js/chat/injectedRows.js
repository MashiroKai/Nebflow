// chat.js 拆分(FE组件化批次三 2026-09-27):注入行显示(source 标签表 + 注入行 builder + live 渲染) 可复用模块(行为保持)。
import { activeView } from '../chatView.js';
import { t } from '../i18n.js';
import { createIconsIn, renderMarkdownWithMath, shouldFollowBottom } from '../utils.js';
import { parseTaskReturnText, buildTaskRefLine } from '../reference.js';
// Root agent DISPLAY name（单读点 rootName.js；A 侧 deviceMail 标签批本簇改动随簇迁入）。
// Nebula 退役批（作者令 2026-11-01）追加：ROOT_AGENT_KEY 用于把「机制名段」在显示层
// 换成用户给它起的名字（引擎 header 的 PROJECT 段与 sender 都可能携带机制名）。
import { rootDisplayName, ROOT_AGENT_KEY } from '../rootName.js';
import { bindCollapsibleToggle } from './collapsible.js';
import { createMsgFooterBadge } from './durationBadges.js';
// diff 拆面板批（2026-10-22 作者令「摘要上移+左右分体」）：交付消息顶部摘要条
// （chip）点击单点 = diffPanel.toggleDiffBatch——左栏 #panel-filechanges 出文件
// 列表、Canvas 出选中文件 diff（旧 master-detail 一体大面板退役）。本 builder
// 只建 chip DOM 与点击接线；面板/canvas 两面都在 diffPanel.js 收口。
import { toggleDiffBatch } from '../diffPanel.js';
// quote-message 一期（spec §7）：主聊天区右键引用 —— 挂点收口在本 builder
// （chat.js 只 re-export，不复制逻辑）。
import { attachQuoteMenu, nextChatMsgId } from '../msgQuote.js';
// schedcard 批（2026-10-29）：卡片「预定时间」读全局时钟偏好面（12h/24h + 日分层
// ——今天纯时钟、昨天/星期/日期分层），与消息 footer 同一实现（timeFormat 单源）。
import { formatFooterTime } from '../timeFormat.js';
// 来源链批（作者令 2026-11-01 19:23）：完工卡归属链上层段读数（bgSourceChain
// 单点——收件 delegate 的批次短名；解析不到 = ''，卡面零变化）。
import { chainNameOf } from '../bgSourceChain.js';

/** 完工卡上层段（来源链批）：收件会话是 delegate（注册行镜像有批次短名）⇒ 该短名
 *  =「挂在谁下面」上层段；根会话/无名旧记录 ⇒ ''（链不造空段——卡面逐字节不变）。 */
function receiptUpperName(receiverSid) {
  const nm = chainNameOf(String(receiverSid || ''));
  const sid = String(receiverSid || '').replace(/^team-/, '');
  return (/^delegate-/.test(sid) && nm) ? nm : '';
}

// ---------- Injected bubble (task P+Q: tool-injected prompts & result notifications) ----------
// Mail/Delegate/SubTask/Skill/Flow/ExternalEvent injections arrive as WS
// {type:"user", injected:true, source} and are persisted as UiMessage.User
// with source. Rendered as a light-blue bubble on the user side (right),
// visually distinct from the user's own green bubble.

/** Map backend injection source → display label. Sources are tool/protocol
 *  names (proper nouns) — no i18n needed. Unknown sources are capitalized.
 *
 *  task / dispatch（Q2-B2，2026-09-11 任务分发器收件规则批）：后端对分发器收件的
 *  两种入口权威定名——`task` = Task 入口（TriggerDispatcher 默认源：重入 /
 *  spawn 首条 prompt；Mail(→project) 触发腿已拆除），`dispatch` = 回流通知
 *  （DispatchNotify）。二者显式登记，
 *  **不靠首字母大写兜底**（兜底对多词源名/大小写变体不设防）。 */
const INJECTED_SOURCE_LABELS = {
  mail: 'Mail', delegate: 'Delegate', subtask: 'SubTask', skill: 'Skill',
  ask: 'Ask', flow: 'Flow', tool: 'Tool', api: 'API',
  task: 'Task', dispatch: 'Dispatch',
  // bluebubble 批（2026-09-12）：后端 `InjectionAttribution.BackendNamedSources`
  // 是唯一定名源（`src/main/scala/nebflow/agent/protocol.scala`）。system /
  // background 此前靠 `charAt(0).toUpperCase()` 兜底——偶然正确但不受保护
  // （兜底对多词源名/大小写变体不设防，且后端加新源会静默以错误标签展示），
  // 故显式登记。登记面 ⊇ 后端集合由 `InjectionSourceContractSpec` 硬门守住。
  // `node` 不在本表：它在 injectedSourceLabel 里有专用格式分支（NODE · 项目 ·
  // 节点 · 状态），spec 认「表项 ∪ 显式分支」为已登记。
  // `deviceMail`（device-mail 批，2026-09-15）同 `node`：专用 i18n 分支
  // （「来自 <from_device> 的 Nebula」，sender 携带 from_device），故不改本表——
  // 契约门 InjectionSourceContractSpec 的显式分支集合同样认它。
  system: 'System', background: 'Background',
  // b64 批（2026-09-13）：链级摘要投根通道（NodeEngine.deliverChainSummary，
  // source="chain"，`FlowMapStore.ChainSummarySource`）。后端自定名 ⇒ 必须显式登记
  // （禁首字母大写兜底，契约门 InjectionSourceContractSpec）。
  // ── 全降级列表态批（2026-09-16，作者裁定「全部降级列表态」）后的**改接口径** ──
  // 引擎侧链腿**已停发**（零投主对话 ⇒ 无即时气泡、不进 LLM 上下文）：本表项不再有
  // live 帧来源。**保留登记（不删）**的两条硬理由：
  //   ① **存量历史行仍在渲染**（非死码）：宿主 sessions 面现取 ≈49 处 `source:"chain"`（随轮转漂移），
  //      且不带 `header` 键（宿主建早于 header 批）⇒ 逐行都走本表项 → 标签 "Chain"；
  //   ② 契约门 `InjectionSourceContractSpec` ② 要求前端登记面 ⊇
  //      `InjectionAttribution.BackendNamedSources`（仍含 "chain"）；删表项须连带删
  //      后端词表 + NotificationHeader.KindLabels 两处既有 pin（未取侧，见批报告）。
  // 链级聚合信息的**现役承载面** = 链级列表/明细面（Flow Map 归档面板
  // `flowMapArchive.js`：链条目 → 成员行 → 详情窗按需取结果全文），不再经本表。
  chain: 'Chain',
  // schedcard 批（2026-10-29）：定时任务触发腿（ScheduledTaskService.triggerTask）。
  // 呈现面走 `buildInjectedRow` 的**专属卡分支**（见下），本表项是契约门 ② 的显式
  // 登记 + 兜底标签（凡走通用注入泡渲染的边角——如引用菜单 senderName——不落
  // 「首字母大写兜底」）。
  schedule: 'Schedule',
  // 待办浮出腿（#822 复活批「Schedule 双面化」2026-10-29，单口合并时登记）：
  // 每日 09:00 的待办汇总 digest（`ScheduledTaskService.surfaceDigest`，
  // `injected=true, source="todo", eventType="digest"`）走**通用注入泡**
  // （标签「Todo · Digest」），刻意不进上面的定时任务专属卡分支——待办汇总是
  // 「今天要办的事」清单，不是到点触发的单任务卡。
  todo: 'Todo',
};

/** Map backend eventType → display suffix for the source label.
 *  Shown as 'SOURCE · EventType' in the injected bubble header.
 *  `cancelled`（全降级列表态批 2026-09-16 补齐）：链级状态段本批拓三元后
 *  `FlowMapStore.ChainSummaryEventCancelled` 取值为 `cancelled`。**登记项**：既有
 *  shape 靠 `charAt(0).toUpperCase()` 兜底得 `Cancelled`（字节同值），此处显式登记以
 *  去掉兜底依赖；与节点腿 `NODE_STATUS_LABELS.cancelled='CANCELED'` 的**大小写差异是
 *  既存口径**（节点腿全大写、通用面 Title case），本批不合并两表（合并会改节点腿既有
 *  呈现，超出裁定面）。 */
const EVENT_TYPE_LABELS = {
  completed: 'Completed', failed: 'Failed', cancelled: 'Cancelled',
  crashed: 'Crashed',
  trigger: 'Triggered', inject: 'Injected',
  info: 'Info', result: 'Result', interrupt: 'Interrupt',
  follow_up: 'Follow-up', parallel: 'Parallel',
};

/** Node 完成通知状态段（NODE · 项目 · 节点 · 状态）：状态显示为全大写值
 *  （COMPLETED / FAILED / CANCELED）；未知状态原样大写。 */
const NODE_STATUS_LABELS = {
  completed: 'COMPLETED', failed: 'FAILED', cancelled: 'CANCELED',
};

function nodeStatusLabel(eventType) {
  return NODE_STATUS_LABELS[eventType] || String(eventType).toUpperCase();
}

/** Delegate 结果头部口径（作者令 2026-10-28 17:3x 信息提纯批，2026-10-31 完工
 *  通知卡重设计批承继）：已知执行器（sender = `nebflow` / zcode / claude-code /
 *  codex / hermes，引擎 `ExecutorRegistry` 全集）⇒ 执行器段一律 `INTERNAL` /
 *  各自 id 全大写（报障原文：「DELEGATE · NEBULA · NEBFLOW · COMPLETED」中
 *  NEBFLOW 段无信息量）；PROJECT 段不进头部；自定义 agent 委托（sender =
 *  agentDef.name，如 `Explorer`）与 sender 缺席（旧历史行）⇒ 逐字保留。
 * 只改**显示文案的组装**——帧/落盘行上的 header、sender、project 字段一律
 *  原样保留，不删数据。重设计批起由 [[delegateReceiptName]] /
 *  [[delegateReceiptVerdict]] / [[delegateReceiptSummary]] 三纯函数承载
 *  （判词从串尾段移入单色徽标，摘要独立成行）。 */
/** 已知执行器 id 集合：sender 命中其一 ⇒ 执行器段重排为 INTERNAL / 各自 id；
 *  自定义 agent 委托与 sender 缺席行 ⇒ 逐字保留。 */
const DELEGATE_EXECUTOR_IDS = new Set(['nebflow', 'zcode', 'claude-code', 'codex', 'hermes']);

/** 合并轮工作结果附页的 merge status 行标（[[DelegateWorkReport]] 面文案——
 *  合并轮 completed 终态的 payload 恒携该行（model/用户可见文案，§16 待过目）；
 *  判定 merged 判词客户端侧唯一可判据（帧面无结构化 merge 结论字段）。 */
const DELEGATE_MERGE_STATUS_MARKER = '[delegate-finish] merge status:';

/** 完工通知卡判词徽标（2026-10-31 重设计批）：merged = completed × merge status
 *  行在场（客户端推导）；其余终态逐字大写（词表同 [[NODE_STATUS_LABELS]] 面）。 */
function delegateReceiptVerdict(eventType, text, state) {
  if (eventType === 'completed' && String(text || '').includes(DELEGATE_MERGE_STATUS_MARKER)) return 'MERGED';
  return state || (eventType ? String(eventType).toUpperCase() : '');
}

/** 从 header 串解出「批次短名」段（纯函数）：已知执行器 ⇒ `KIND · INTERNAL|ID`
 *  （信息提纯批 2026-10-28 口径逐字保持）；自定义 agent / sender 缺席（旧历史行）
 *  ⇒ header 去掉尾段判词后逐字保留（PROJECT/SUBJECT 段有信息量）。eventType 缺席
 *  ⇒ 无判词段可剥 ⇒ 整串逐字保留。 */
function delegateReceiptName(headerText, sender, eventType) {
  const exec = sender == null ? '' : String(sender).trim();
  const segs = String(headerText || '').split(' · ');
  const kind = (segs[0] || 'DELEGATE').trim();
  if (exec && DELEGATE_EXECUTOR_IDS.has(exec.toLowerCase())) {
    return [kind, exec.toLowerCase() === 'nebflow' ? 'INTERNAL' : exec.toUpperCase()].join(' · ');
  }
  const rest = eventType && segs.length >= 2 ? segs.slice(0, -1) : segs;
  return rest.join(' · ');
}

/** 一句话结果摘要（纯函数，钉面可单测）：
 *  merged ⇒ merge status 行正文 + 短哈希（7-40 位 hex 取 7——渲染层右缘不省略
 *    号截断，判词摘要恒带得到 merge commit 短哈希是作者令硬判据）。去重口径：
 *    哈希在**串尾**（含括号包裹，模型 finish 申报的典型形态「merged … (hash)」）
 *    ⇒ 尾段剥出进独立哈希段，正文不重复；哈希在串中 ⇒ 正文原样（哈希本可见），
 *    不另立哈希段；status 行无哈希 ⇒ 只给正文。
 *  其余 ⇒ 首条非空且非引擎注记行（`[delegate-judge]` / `[delegate-finish]` 是
 *    判词/附记机器行，不是结果摘要）——completed = 模型自写摘要首行，
 *    failed/cancelled = 失败原因首行。摘要恒一行，CSS 省略号截断。 */
function delegateReceiptSummary(text, merged) {
  const lines = String(text || '').split('\n');
  if (merged) {
    const line = lines.find((l) => l.trimStart().startsWith(DELEGATE_MERGE_STATUS_MARKER));
    const status = line == null ? '' : line.trim().slice(DELEGATE_MERGE_STATUS_MARKER.length).trim();
    if (status) {
      const tail = /\s*[\(\[]?([0-9a-f]{7,40})[\)\]]?\s*$/i.exec(status);
      if (tail) {
        const body = status.slice(0, tail.index).trimEnd();
        // 尾段剥空（status 只有一枚哈希）⇒ 正文回退整串，哈希段不再重复立。
        if (body) return { text: body, hash: tail[1].slice(0, 7) };
      }
      return { text: status, hash: '' };
    }
  }
  const first = lines
    .map((l) => l.trim())
    .find((l) => l && !l.startsWith('[delegate-judge]') && !l.startsWith('[delegate-finish]'));
  return { text: first || '', hash: '' };
}

/** Build the source label text, optionally combining with sender and eventType.
 *  e.g. source='mail', sender='Manager', eventType='result' → 'Mail · Manager · Result'
 *  sender is optional (backward compatible): absent → 'SOURCE · EventType'.
 *  sourceTeam (optional, backward compatible): present → the message came from a
 *  Team agent; the label shows the team path instead of the SOURCE prefix,
 *  e.g. 'nebflow-project/Backend · Result'.
 *  intake (optional, mailbadge batch 2026-09-13 — author ruling「必须显示 MAIL」,
 *  option C): the **intake-channel discriminant** the backend stamps next to
 *  `source` when the injection arrived through a specific receipt channel
 *  (`Mail(address="project:…")` at the dispatcher face — that trigger leg is
 *  demolished (2026-10-18); the intake value + label survive only to render
 *  legacy rows — ⇒ `intake='mail'`, while
 *  `source` stays 'task' — the bridge's consumption accounting key, unchanged by
 *  author decree). The intake value comes from the same backend-named source
 *  vocabulary, so it resolves through the same explicitly-registered table. It
 *  takes **presentation priority**; absent ⇒ the fallback path is byte-identical
 *  to before (old history rows without the field, node face, non-project Mail,
 *  dispatch notifications all render exactly as before). */
/** 显示层身份段：机制名（root agent 的 key，大小写不敏感——引擎
 *  `NotificationHeader` 会把 PROJECT 段全大写归一，`RootProject` 亦由此而来）⇒
 *  用户给它起的名字；其余值（项目/节点/团队/设备/后台 agent 自身身份）原样。
 *  Nebula 退役批（作者令 2026-11-01「界面里不该再有 Nebula 的身影」）：只换显示，
 *  帧与落盘行上的字段一律不动（协议面零改动）。 */
export function displayIdentitySegment(seg) {
  const v = String(seg == null ? '' : seg).trim();
  if (!v) return '';
  return v.toLowerCase() === ROOT_AGENT_KEY.toLowerCase() ? rootDisplayName() : v;
}

export function injectedSourceLabel(source, eventType, sender, sourceTeam, intake) {
  // 未知/畸形 source 防御（全降级列表态批 2026-09-16，J6「未知 source 不得异常」）：
  // 非字符串来源（历史脏行 / 未来新源的畸形值）先串化，禁让下方 `charAt`/`slice`
  // 抛异常把整条注入行渲染打断；空值仍走上行早退（逐字节不变）。**登记读数**：
  // 探针覆盖 'chain'（存量历史源）/ 'unknown-source' / '' / null / undefined / 数字
  // ⇒ 六例零异常（见批证据 30-liststate-readings.json 的 `j6Probe.cases`）。
  if (source != null && typeof source !== 'string') source = String(source);
  if (sourceTeam != null && typeof sourceTeam !== 'string') sourceTeam = String(sourceTeam);
  if (!source && !sourceTeam) return '';
  // Node 完成通知专用格式（唯一 Node 类注入消息，source="node" 仅 NodeEngine
  // deliverToNebula 发出）：NODE · <项目名> · <节点名> · <状态>。后端把项目名
  // 与节点名打包在 sender = "<projectName>/<nodeName>"（路径约定，同 sourceTeam
  // team/agent 惯例）；旧历史行 sender="node"（无 '/'）→ 优雅降级为
  // 'NODE · <状态>'。状态段走全大写 nodeStatusLabel（COMPLETED/FAILED/CANCELED）。
  // 不改其他消息类型格式。
  if (source === 'node') {
    const parts = ['NODE'];
    if (sender) {
      const sep = sender.indexOf('/');
      if (sep > 0 && sep < sender.length - 1) {
        parts.push(sender.slice(0, sep), sender.slice(sep + 1));
      }
    }
    if (eventType) parts.push(nodeStatusLabel(eventType));
    return parts.join(' · ');
  }
  const parts = [];
  // 跨设备 Nebula 邮件（device-mail 批，2026-09-15）：后端 source='deviceMail'
  // （`DeviceMail.SourceDeviceMail`，唯一发射点 = DeviceMailInbox 的会话注入）
  // ⇒ 标签 = i18n「来自 <from_device> 的 Nebula」双语（sender 携带 from_device）。
  // 复用同一徽章位/同一蓝气泡样式；eventType 段（INFO）照常追加。
  if (source === 'deviceMail') {
    parts.push(t('deviceMail.fromDevice', { device: sender || '?', agent: rootDisplayName() }));
  } else if (sourceTeam) {
    parts.push(sender ? `${sourceTeam}/${sender}` : sourceTeam);
  } else {
    // 收件判别字段优先（缺席 ⇒ key = source，回落路径逐字不变）。
    const key = intake || source;
    const base = INJECTED_SOURCE_LABELS[key] || key.charAt(0).toUpperCase() + key.slice(1);
    parts.push(base);
    if (sender) parts.push(displayIdentitySegment(sender));
  }
  if (eventType) {
    const et = EVENT_TYPE_LABELS[eventType] || eventType.charAt(0).toUpperCase() + eventType.slice(1);
    parts.push(et);
  }
  return parts.join(' · ');
}

/** Mail delivery modes from the backend contract ('queue'|'immediate').
 *  Badges are appended to the injected source label when the WS event /
 *  UiMessage carries a `delivery` field; old messages lack it → no badge
 *  (the former 'ask' mode was removed 2026-08-27 — legacy history rows that
 *  still carry it render without a badge). */
const DELIVERY_MODES = new Set(['queue', 'immediate']);

/** Append a delivery-mode badge to the label element (no-op when the field
 *  is absent or not a known mode — backward compatible with old history). */
function appendDeliveryBadge(label, delivery) {
  if (!delivery || !DELIVERY_MODES.has(delivery)) return;
  const badge = document.createElement('span');
  badge.className = `delivery-badge delivery-${delivery}`;
  badge.textContent = t(`mailDelivery.${delivery}`);
  badge.title = t(`mailDelivery.${delivery}Title`);
  label.appendChild(badge);
}

/** 循环任务重复标签（与 scheduled-task.js#repeatLabel 同一 i18n 键面；本地副本
 *  理由：scheduled-task.js 是运行时面板模块（带面板状态），卡 builder 不反向依赖
 *  运行时面板——键名即契约，漂移由 ScheduleCardContractSpec 键面读数钉住）。 */
function scheduleRepeatLabel(r) {
  if (r === 'hourly') return t('task.repeatHourly');
  if (r === 'daily') return t('task.repeatDaily');
  if (r === 'weekly') return t('task.repeatWeekly');
  return '';
}

/** 卡正文：剥掉 payload 尾行的引擎面注记「（实际触发 …，预定 …）」——它写给 LLM
 *  看的触发对账注记，卡片有自己的结构化时间行，双显即重复。仅剥**恰好位于尾部**
 *  且形态匹配的一行；任何变体（用户正文里真的含这串字样的非尾行）不动。 */
function scheduleCardBodyText(text) {
  return String(text || '').replace(/\n?（实际触发[^）]*，预定[^）]*）\s*$/, '');
}

/** Build the dedicated schedule-card row content into `row` (pure builder — the
 *  caller owns row class/append). schedcard 批 2026-10-29。Structure:
 *  .bubble.injected.schedule-card[data-nf-schedule]
 *    .schedule-card-head  (bell-ring icon + chip + name? + repeat?)
 *    .schedule-card-body  (markdown body, trailing engine note stripped)
 *    .schedule-card-ref?  (referencePath file line)
 *    .schedule-card-time  (clock icon + scheduled time)
 *  + unified footer pill + quote menu (same as the generic injected family).
 *  meta 缺席（旧载荷/降级）⇒ 卡照渲染（无 name/无重复标，时间回落行 timestamp）。 */
function buildScheduleCardRow(row, text, timestamp, deferFn, schedule) {
  const meta = (schedule && typeof schedule === 'object' && !Array.isArray(schedule)) ? schedule : {};
  const bubble = document.createElement('div');
  bubble.className = 'bubble injected schedule-card';
  bubble.dataset.nfSchedule = '1';

  const head = document.createElement('div');
  head.className = 'schedule-card-head';
  const icon = document.createElement('i');
  icon.setAttribute('data-lucide', 'bell-ring');
  head.appendChild(icon);
  const chip = document.createElement('span');
  chip.className = 'schedule-card-chip';
  chip.textContent = t('task.cardChip');
  head.appendChild(chip);
  const name = typeof meta.name === 'string' ? meta.name.trim() : '';
  if (name) {
    const nameEl = document.createElement('span');
    nameEl.className = 'schedule-card-name';
    nameEl.textContent = name;
    head.appendChild(nameEl);
  }
  const repeat = scheduleRepeatLabel(meta.repeat);
  if (repeat) {
    const repEl = document.createElement('span');
    repEl.className = 'schedule-card-repeat';
    repEl.textContent = repeat;
    head.appendChild(repEl);
  }
  bubble.appendChild(head);

  const displayText = scheduleCardBodyText(text);
  const body = document.createElement('div');
  body.className = 'schedule-card-body';
  if (deferFn) deferFn(body, displayText);
  else body.innerHTML = renderMarkdownWithMath(displayText, false);
  bubble.appendChild(body);

  const refPath = typeof meta.referencePath === 'string' ? meta.referencePath.trim() : '';
  if (refPath) {
    const ref = document.createElement('div');
    ref.className = 'schedule-card-ref';
    const refIcon = document.createElement('i');
    refIcon.setAttribute('data-lucide', 'file-text');
    const refText = document.createElement('span');
    refText.textContent = refPath;
    ref.append(refIcon, refText);
    bubble.appendChild(ref);
  }

  const triggerAt = Number(meta.triggerAt) > 0 ? Number(meta.triggerAt) : (timestamp > 0 ? timestamp : 0);
  if (triggerAt > 0) {
    const timeRow = document.createElement('div');
    timeRow.className = 'schedule-card-time';
    const clock = document.createElement('i');
    clock.setAttribute('data-lucide', 'clock');
    const timeText = document.createElement('span');
    timeText.textContent = t('task.cardScheduledAt', { time: formatFooterTime(triggerAt) });
    timeRow.append(clock, timeText);
    bubble.appendChild(timeRow);
  }

  row.appendChild(bubble);
  createIconsIn(bubble);

  // Unified footer pill + quote menu — same contract as the generic injected
  // branch below (timestamp/copy affordance, right-click 引用).
  const trimmed = (text || '').trim();
  if ((timestamp && timestamp > 0) || trimmed) {
    row.appendChild(createMsgFooterBadge(timestamp, trimmed));
  }
  row.dataset.msgId = nextChatMsgId();
  attachQuoteMenu(row, {
    kind: 'text', text: trimmed,
    messageId: row.dataset.msgId,
    senderRole: 'agent', senderName: t('task.cardChip'),
    ts: timestamp || Date.now(),
  });
  return row;
}

/** Build a row element for an injected message (pure builder — no DOM append,
 *  no scroll). Shared by live render (renderInjectedBubble) and history
 *  restore (persistence.js) so both paths render identically.
 *  deferFn (optional): batch-restore path passes persistence.js's deferMd to
 *  defer markdown rendering into post-append rAF batches — prevents a
 *  synchronous markdown storm when restoring long histories (P0-2).
 *  sourceTeam (optional): Team name for Team-agent messages — shown in the
 *  source label as 'team/agent' (see injectedSourceLabel).
 *  delivery (optional): Mail delivery mode 'queue'|'immediate' — shown
 *  as a badge in the label; absent on old messages → hidden.
 *  intake (optional): intake-channel discriminant (mailbadge batch) — takes
 *  label priority over `source`; absent → `source` renders as before.
 *  header (optional, 气泡四段式统一批 2026-09-15 — author ruling): the
 *  **already-rendered** four-segment header `KIND · PROJECT · SUBJECT · STATE`
 *  produced by the engine's single formatter
 *  (`nebflow.core.project.NotificationHeader`, called once at the one emission
 *  point `AgentActor#emitInjectedUserEvent`) and shipped on the WS frame /
 *  persisted in the .ui.json row. Present ⇒ rendered **verbatim** (the engine
 *  is the single source; the frontend must NOT concatenate a second time).
 *  Absent (old history rows, sources outside the engine KIND vocabulary,
 *  e.g. the in-flight device-mail source) ⇒ falls back to
 *  `injectedSourceLabel` above, byte-identical to before.
 *  fileChanges (optional, file-diff → 委托结果行 bug #58 修 2026-10-09；2026-10-10
 *  展开改 Canvas；2026-10-22 拆面板批「摘要上移+左右分体」换代；2026-10-31 完工
 *  通知卡重设计批 + 22:29 单行化批改形态「diff 胶囊退役；+a −r 灰字小注入单卡面
 *  行右缘组（字面「| +a −r」）」): the per-batch changed-file diff payload carried
 *  by delegate terminal events (engine `FileChangeReport.delegateBatchMetadata` —
 *  same wire shape as the REST file-changes face). Present with files ⇒ the quiet
 *  +a −r gray note joins the single-line receipt face at the card's right edge
 *  (folded-state visible; NOT a chip/capsule per author ruling; its own block-hover
 *  segment) — click reveals the diff file list in the LEFT Side Bar panel
 *  (#panel-filechanges) and the focused file's diff in the Canvas reader
 *  (diffPanel.js / diffCanvas.js); re-clicking an open batch collapses the
 *  Side Bar (existing panel toggle semantics); click/keydown stopPropagation so
 *  the in-line note never double-fires the head's collapse toggle. The bubble
 *  body keeps zero diff rows. Absent (zero-change batch / old history) ⇒ no note.
 *  schedule (optional, schedcard 批 2026-10-29): 定时任务触发行的卡片载荷
 *  （引擎 `UiMessage.User.schedule` 同源：{id, name?, triggerAt, repeat?,
 *  referencePath?}）。`source==='schedule'` ⇒ 走**专属卡分支**（见下），载荷
 *  缺席（旧历史行）也能渲染卡（降级：无 name/无重复标/时间回落行 timestamp）。
 *  receiverSid (optional, 来源链批 2026-11-01 19:23 作者令): 收件会话 id——完工卡
 *  归属链的上层段判据：收件会话是 delegate（有批次短名）⇒ 该 delegate 名即
 *  「挂在谁下面」的上层段（pill 首段前插「父短名 · DELEGATE · INTERNAL」形）；
 *  根会话收件 = 无上层链（作者令「不造空段」）⇒ 缺省/空 = 卡面逐字节不变。 */
export function buildInjectedRow(text, source, timestamp, eventType, sender, sourceTeam, deferFn, delivery, intake, header, fileChanges, schedule, receiverSid) {
  const row = document.createElement('div');
  row.className = 'row user';

  // ── Schedule 专属卡分支（schedcard 批 2026-10-29，作者令「定时任务卡片防退化 +
  // stand 配色」）────────────────────────────────────────────────────────
  // 防退化钉面：带 schedule 标记的注入行（引擎 ScheduledTaskService.triggerTask
  // 落盘/广播 `injected:true, source:"schedule"`）**永不走**通用用户气泡（绿泡）
  // 渲染——本分支在通用注入泡组装之前接管，渲染专属卡：
  //   头行  铃铛图标 + 「定时任务」chip + 任务名（有 name 才显示）+ 重复小标（循环任务）
  //   正文  任务内容（markdown；尾行「（实际触发 …，预定 …）」为 LLM 面注记，
  //         卡片有自己的结构化时间行 ⇒ 显示层剥掉，剥不动（旧 payload 变体）⇒ 原样）
  //   引用  referencePath 在场 ⇒ 文件引用行（同旧 toast 的 ref 行）
  //   时间  预定触发时间（schedule.triggerAt 缺席回落行 timestamp；formatFooterTime
  //         同源时钟偏好面）
  // 卡仍保留 `.bubble.injected` 基类 ⇒ 注入行族消费者（turnGroup.isInjectedRow /
  // workline 行线锚点 / chatSearch / bgAgentPopup）零改动照常识别；`.schedule-card`
  // 修饰类接管视觉（stand 配色，见 reminder.css）。历史行兼容：无标记旧行不进本
  // 分支，按现状渲染（绿泡），不追溯。
  if (source === 'schedule') {
    return buildScheduleCardRow(row, text, timestamp, deferFn, schedule);
  }

  const bubble = document.createElement('div');
  bubble.className = 'bubble injected';
  const label = document.createElement('div');
  label.className = 'ask-label injected-source-label';
  const trimmed = (text || '').trim();
  const content = document.createElement('div');
  // 类名供展开态间距 CSS 选择（.nf-rcpt-* + .injected-content / 同线行旧选择器
  // 已随 chip 退役移除）；折叠态 content display:none ⇒ 零高度贡献（尺寸回归批 2026-10-28）。
  content.className = 'injected-content';
  // 打回注入块收敛 (2026-08-27 user ruling): the backend [打回任务 #id: title]
  // injection collapses to the one-line `#<任务号> <任务标题>` form with a
  // one-line-truncated opinion. 描述/产出 never render. Both the live dispatch
  // and BOTH history-restore paths funnel through this single builder, so every
  // entry renders byte-identically (AC d).
  const retMsg = parseTaskReturnText(trimmed);
  if (retMsg) {
    content.appendChild(buildTaskRefLine(retMsg));
  } else {
    if (deferFn) deferFn(content, trimmed);
    else content.innerHTML = renderMarkdownWithMath(trimmed, false);
  }

  // Default-collapsed (2026-08-23 ruling): blue injected bubbles show only the
  // category header (SOURCE · AGENT · EVENT_TYPE); the body expands on click.
  // Same product thought as #346: the stream stays clean, observability is
  // on-demand. Expansion is not persisted — refresh returns to collapsed.
  // Only collapses when there IS content; empty injected markers stay flat.
  // Expand affordance (2026-08-24 ruling): NO chevron icon — the quiet muted
  // label itself is the toggle (old「思考过程」interaction: cursor + hover
  // opacity only), which also fixes the icon's vertical misalignment.
  const collapsible = trimmed.length > 0;
  if (collapsible) {
    content.style.display = 'none'; // collapsed default
  }
  // 引擎单一来源优先（气泡四段式统一批 2026-09-15）：帧/落盘行带 `header` ⇒ 逐字
  // 渲染该串（**禁二次拼接**——引擎 `NotificationHeader` 是唯一格式化实现）；
  // 缺席 ⇒ 回落既有 `injectedSourceLabel`（旧历史行与词表外 source 逐字节不变）。
  // Nebula 退役批（作者令 2026-11-01）：引擎 header 逐字渲染的口径不变，唯一例外 =
  // 携带机制名的段（PROJECT 段缺项目时会回落 `NotificationHeader.RootProject`）在
  // 显示层换成用户给它起的名字——判据见 displayIdentitySegment（只认机制名，其余
  // 段逐字保留：节点/链/团队/后台 agent 自身身份一律不动）。
  const rawHeader = (header && String(header).trim()) ? String(header) : '';
  const renderedLabel = rawHeader
    ? rawHeader.split(' · ').map(displayIdentitySegment).join(' · ')
    : injectedSourceLabel(source, eventType, sender, sourceTeam, intake);

  // ── Delegate 完工通知卡（2026-10-31 重设计批 → 22:29 单行化+hover 批 → 11-01
  //   完工卡 diff 统计恢复批，作者令版式四条）：
  //   ①恢复 diff 统计——卡面显示该批**合并**的代码量变化（+新增 −删除）。数据源 =
  //     载荷 `merge` 锚键（后端现算的合并提交第一亲 diff 统计，BackoffSupervisor
  //     .terminalDiffMetadata——修旧数据链两盲区：worktree 提交后 git status 面看
  //     不见已提交工作、Bash 侧写盘 ledger 记不到）；载荷无 merge 键（旧历史行/
  //     非合并轮）回落 files 加删和（原数据链）。有统计的批出「+N −M」；零改动/
  //     无统计的批只有归因块（无 pipe 无 diff 块——两种卡面形态）。
  //   ②版式——归因+状态「DELEGATE · INTERNAL · COMPLETED」合成**一颗胶囊芯片**
  //     （.nf-rcpt-pill：短名分段 span + 字面 ' · ' + 判词同块；pill textContent
  //     逐字该口径）；diff 统计**旁边单独一颗块**（.nf-rcpt-note），两块之间「|」
  //     字面分隔段（.nf-rcpt-pipe，aria-hidden）；22:29 批的右缘贴卡对齐退役
  //     （旁边相邻组语义）。
  //   ③diff 块内部 +N/−M 同块异色（+ 绿 / − 红——--status-success-icon /
  //     --status-error-icon 语义 token，14:14 语义色批口径承继，双主题 ≥4.5:1），
  //     hover 不再褪成正文色（语义色恒驻）。
  //   ④hover——两块各有 hover 态（c29b35be7 批样式家族：块底 sapphire tint +
  //     描边微变；行底 0.08 / 块底更深一档，同屏可辨）。整条 hover 可点击感保留
  //     （卡头 = 折叠切换面）。
  //   摘要行仍收进展开态（点开详情语义不动）；小注点击 = diff 面板入口（files 面
  //   在场才可点——merge-only 载荷无 files = 静态色块）；stopPropagation/键盘
  //   不冒泡 ⇒ 不叠加卡头折叠切换。非 delegate 源 ⇒ 通用 label 路径逐字节不动。
  let receiptTop = null;
  if (source === 'delegate') {
    // 判词段 = eventType 词表（[[nodeStatusLabel]] 面同引擎 NotificationHeader
    // .StateLabels 拼写——含 cancelled→CANCELED 单 L 历史口径），不从 header 串
    // 反解（回落标签是 TitleCase，反解会泄大小写）。
    const verdict = delegateReceiptVerdict(eventType, trimmed, eventType ? nodeStatusLabel(eventType) : '');
    receiptTop = document.createElement('div');
    receiptTop.className = 'nf-rcpt-top';
    const head = document.createElement('div');
    head.className = 'nf-rcpt-head';
    // ── 块一：归因+状态胶囊芯片（作者令版式②）────────────────────────
    const pill = document.createElement('span');
    pill.className = 'nf-rcpt-pill';
    const name = document.createElement('span');
    name.className = 'nf-rcpt-name';
    // 来源链批（作者令 2026-11-01 19:23）：收件会话是 delegate ⇒ 上层段 =
    // 该 delegate 批次短名（bgSourceChain 单点读数）——嵌套 delegate 的完工卡
    // 「挂在谁下面」上屏；根会话收件（无链）⇒ 段缺席，卡面逐字节不变。
    // 分段 span 承载：textContent 逐字节维持「KIND · EXECUTOR」口径（A1 契约面）。
    const upperSeg = receiptUpperName(receiverSid);
    const receiptSegs = delegateReceiptName(renderedLabel, sender, eventType).split(' · ');
    const pillSegs = upperSeg ? [upperSeg, ...receiptSegs] : receiptSegs;
    pillSegs.forEach((segText, i) => {
      if (i > 0) name.appendChild(document.createTextNode(' · '));
      const seg = document.createElement('span');
      seg.className = 'nf-rcpt-seg';
      seg.textContent = segText;
      name.appendChild(seg);
    });
    pill.appendChild(name);
    if (verdict) {
      // 字面 ' · ' 文本节点：pill textContent = 「DELEGATE · INTERNAL · COMPLETED」
      // （作者令版式②引字口径）。
      pill.appendChild(document.createTextNode(' · '));
      const badge = document.createElement('span');
      badge.className = 'nf-rcpt-badge';
      badge.textContent = verdict;
      pill.appendChild(badge);
    }
    head.appendChild(pill);
    // ── diff 统计块（作者令①③）：合并提交统计优先（该批**合并**的代码量），
    //    files 加删和回落（旧历史行）；两者皆无 ⇒ 无 pipe 无 diff 块。
    const filesSum = Array.isArray(fileChanges?.files)
      ? fileChanges.files.reduce((acc, f) => {
          acc.a += Number(f?.added) || 0; acc.r += Number(f?.removed) || 0; return acc;
        }, { a: 0, r: 0 })
      : { a: 0, r: 0 };
    const mg = fileChanges && typeof fileChanges.merge === 'object' && !Array.isArray(fileChanges.merge)
      ? fileChanges.merge
      : null;
    const mergeSum = mg ? { a: Number(mg.added) || 0, r: Number(mg.removed) || 0 } : null;
    const stat = mergeSum && (mergeSum.a > 0 || mergeSum.r > 0)
      ? mergeSum
      : (filesSum.a > 0 || filesSum.r > 0 ? filesSum : null);
    if (stat) {
      const pipe = document.createElement('span');
      pipe.className = 'nf-rcpt-pipe';
      pipe.setAttribute('aria-hidden', 'true');
      pipe.textContent = '|';
      head.appendChild(pipe);
      // 点击 = diff 面板入口：仅 files 面在场（面板有内容可开）⇒ button 语义
      // （aria-pressed/dataset/stopPropagation 承继）；merge-only 载荷（合成最小
      // 载荷）⇒ span 静态色块（hover 态在场，无点击面）。
      const clickable = Array.isArray(fileChanges?.files) && fileChanges.files.length > 0;
      const note = document.createElement(clickable ? 'button' : 'span');
      note.className = 'nf-rcpt-note';
      if (clickable) {
        if (note instanceof HTMLButtonElement) note.type = 'button';
        note.setAttribute('aria-pressed', 'false');
        note.dataset.fcBatch = String(fileChanges.nodeId || fileChanges.generatedAt || 'adhoc');
        note.addEventListener('click', (e) => { e.stopPropagation(); toggleDiffBatch(fileChanges); });
        note.addEventListener('keydown', (e) => e.stopPropagation());
      }
      const add = document.createElement('span');
      add.className = 'nf-rcpt-note-add';
      add.textContent = `+${stat.a}`;
      const del = document.createElement('span');
      del.className = 'nf-rcpt-note-del';
      del.textContent = `−${stat.r}`;
      note.append(add, document.createTextNode(' '), del);
      head.appendChild(note);
    }
    receiptTop.appendChild(head);
    bubble.appendChild(receiptTop);
    const summary = delegateReceiptSummary(trimmed, verdict === 'MERGED');
    if (summary.text) {
      const line = document.createElement('div');
      line.className = 'nf-rcpt-summary';
      const txt = document.createElement('span');
      txt.className = 'nf-rcpt-summary-text';
      txt.textContent = summary.text;
      line.appendChild(txt);
      if (summary.hash) {
        const hash = document.createElement('span');
        hash.className = 'nf-rcpt-hash';
        hash.textContent = summary.hash;
        line.appendChild(hash);
      }
      // 摘要行收进展开态：紧随卡头行挂（CSS [aria-expanded='true'] + 相邻兄弟
      // 选择器驱动显隐）；折叠 display:none ⇒ 零高度贡献（卡面恒单行）。
      bubble.appendChild(line);
    }
  } else {
    label.appendChild(document.createTextNode(renderedLabel));
    appendDeliveryBadge(label, delivery);
    bubble.appendChild(label);
  }
  if (collapsible) {
    bindCollapsibleToggle(source === 'delegate' ? receiptTop : label, () => content);
  }
  // （diff 块挂载点在 delegate 卡分支内胶囊旁相邻组——见上「diff 统计块」段；
  //   载荷/点击/aria-pressed 语义承继，右缘贴卡对齐随版式批②退役。）
  bubble.appendChild(content);
  row.appendChild(bubble);

  // Timestamp + copy button (unified v1.2 footer pill). History restore may
  // lack a timestamp → copy-only footer; empty injected markers (no time AND
  // no text) stay footer-less.
  if ((timestamp && timestamp > 0) || trimmed) {
    row.appendChild(createMsgFooterBadge(timestamp, trimmed));
  }
  // quote-message 一期（spec §2.1/§2.5）：注入通知行可引用。挂点在本纯 builder
  // ⇒ live（renderInjectedBubble）与历史恢复（persistence.js）同构获得 data-msg-id
  // 与右键菜单（复制 = 正文纯文本；引用摘要 = header 或正文首行，senderRole='agent'）。
  row.dataset.msgId = nextChatMsgId();
  attachQuoteMenu(row, {
    kind: 'text', text: trimmed,
    messageId: row.dataset.msgId,
    senderRole: 'agent', senderName: injectedSourceLabel(source, eventType, sender, sourceTeam, intake),
    ts: timestamp || Date.now(),
  });
  return row;
}

/** Live-render an injected message into the active view.
 *  A-branch (2026-09-11): the scroll is CONDITIONAL — this used to jump to the
 *  bottom unconditionally, which yanked the user out of history they were
 *  reading (`chat.scrollTop = chat.scrollHeight`). It now follows the shared
 *  near-bottom judgement (utils.js shouldFollowBottom) so a notification that
 *  arrives while the user is scrolled up stays put and raises the ↓ N pill.
 *  fileChanges (optional): delegate 终态 diff 载荷透传（bug #58 修）——live 帧
 *  与历史恢复（persistence.js）走同一 builder，渲染逐字同构。 */
export function renderInjectedBubble(text, source, timestamp, eventType, sender, sourceTeam, delivery, intake, header, fileChanges, schedule) {
  const chat = activeView.dom.chat;
  // 来源链批（2026-11-01 19:23）：收件会话 id 随 builder 下传（完工卡归属链上层段）。
  const row = buildInjectedRow(text, source, timestamp || Date.now(), eventType, sender, sourceTeam, undefined, delivery, intake, header, fileChanges, schedule, activeView?.sessionId);
  // A 侧 #491 单点落序（chat.js appendChatRow = `chat.append(row)`）随簇同位落此：
  // 纯 doc-order 追加，等价语义、同为唯一追加语义（禁反向 import chat.js 成环）。
  chat.append(row);
  if (shouldFollowBottom(activeView, chat)) chat.scrollTop = chat.scrollHeight;
}

// chat.js 拆分(FE组件化批次三 2026-09-27):注入行显示(source 标签表 + 注入行 builder + live 渲染) 可复用模块(行为保持)。
import { activeView } from '../chatView.js';
import { t } from '../i18n.js';
import { renderMarkdownWithMath, shouldFollowBottom } from '../utils.js';
import { parseTaskReturnText, buildTaskRefLine } from '../reference.js';
// Root agent DISPLAY name（单读点 rootName.js；A 侧 deviceMail 标签批本簇改动随簇迁入）。
import { rootDisplayName } from '../rootName.js';
import { bindCollapsibleToggle } from './collapsible.js';
import { createMsgFooterBadge } from './durationBadges.js';

// ---------- Injected bubble (task P+Q: tool-injected prompts & result notifications) ----------
// Mail/Delegate/SubTask/Skill/Flow/ExternalEvent injections arrive as WS
// {type:"user", injected:true, source} and are persisted as UiMessage.User
// with source. Rendered as a light-blue bubble on the user side (right),
// visually distinct from the user's own green bubble.

/** Map backend injection source → display label. Sources are tool/protocol
 *  names (proper nouns) — no i18n needed. Unknown sources are capitalized.
 *
 *  task / dispatch（Q2-B2，2026-09-11 任务分发器收件规则批）：后端对分发器收件的
 *  两种入口权威定名——`task` = Task 入口（Task 工具 / Mail(→project) / 重入 /
 *  spawn 首条 prompt），`dispatch` = 回流通知（DispatchNotify）。二者显式登记，
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

/** Build the source label text, optionally combining with sender and eventType.
 *  e.g. source='mail', sender='Manager', eventType='result' → 'Mail · Manager · Result'
 *  sender is optional (backward compatible): absent → 'SOURCE · EventType'.
 *  sourceTeam (optional, backward compatible): present → the message came from a
 *  Team agent; the label shows the team path instead of the SOURCE prefix,
 *  e.g. 'nebflow-project/Backend · Result'.
 *  intake (optional, mailbadge batch 2026-09-13 — author ruling「必须显示 MAIL」,
 *  option C): the **intake-channel discriminant** the backend stamps next to
 *  `source` when the injection arrived through a specific receipt channel
 *  (`Mail(address="project:…")` at the dispatcher face ⇒ `intake='mail'`, while
 *  `source` stays 'task' — the bridge's consumption accounting key, unchanged by
 *  author decree). The intake value comes from the same backend-named source
 *  vocabulary, so it resolves through the same explicitly-registered table. It
 *  takes **presentation priority**; absent ⇒ the fallback path is byte-identical
 *  to before (old history rows without the field, node face, non-project Mail,
 *  dispatch notifications all render exactly as before). */
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
    if (sender) parts.push(sender);
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
 *  `injectedSourceLabel` above, byte-identical to before. */
export function buildInjectedRow(text, source, timestamp, eventType, sender, sourceTeam, deferFn, delivery, intake, header) {
  const row = document.createElement('div');
  row.className = 'row user';

  const bubble = document.createElement('div');
  bubble.className = 'bubble injected';
  const label = document.createElement('div');
  label.className = 'ask-label injected-source-label';
  const trimmed = (text || '').trim();
  const content = document.createElement('div');
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
    bindCollapsibleToggle(label, () => content);
  }
  // 引擎单一来源优先（气泡四段式统一批 2026-09-15）：帧/落盘行带 `header` ⇒ 逐字
  // 渲染该串（**禁二次拼接**——引擎 `NotificationHeader` 是唯一格式化实现）；
  // 缺席 ⇒ 回落既有 `injectedSourceLabel`（旧历史行与词表外 source 逐字节不变）。
  label.appendChild(document.createTextNode(
    (header && String(header).trim()) ? String(header) : injectedSourceLabel(source, eventType, sender, sourceTeam, intake)
  ));
  appendDeliveryBadge(label, delivery);
  bubble.appendChild(label);
  bubble.appendChild(content);
  row.appendChild(bubble);

  // Timestamp + copy button (unified v1.2 footer pill). History restore may
  // lack a timestamp → copy-only footer; empty injected markers (no time AND
  // no text) stay footer-less.
  if ((timestamp && timestamp > 0) || trimmed) {
    row.appendChild(createMsgFooterBadge(timestamp, trimmed));
  }
  return row;
}

/** Live-render an injected message into the active view.
 *  A-branch (2026-09-11): the scroll is CONDITIONAL — this used to jump to the
 *  bottom unconditionally, which yanked the user out of history they were
 *  reading (`chat.scrollTop = chat.scrollHeight`). It now follows the shared
 *  near-bottom judgement (utils.js shouldFollowBottom) so a notification that
 *  arrives while the user is scrolled up stays put and raises the ↓ N pill. */
export function renderInjectedBubble(text, source, timestamp, eventType, sender, sourceTeam, delivery, intake, header) {
  const chat = activeView.dom.chat;
  const row = buildInjectedRow(text, source, timestamp || Date.now(), eventType, sender, sourceTeam, undefined, delivery, intake, header);
  // A 侧 #491 单点落序（chat.js appendChatRow = `chat.append(row)`）随簇同位落此：
  // 纯 doc-order 追加，等价语义、同为唯一追加语义（禁反向 import chat.js 成环）。
  chat.append(row);
  if (shouldFollowBottom(activeView, chat)) chat.scrollTop = chat.scrollHeight;
}

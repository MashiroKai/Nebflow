// msgQuote.js — 主聊天区「消息右键引用」（微信式）一期的唯一新前端模块
// （设计规格 20261009_quote-message-design-spec §7.1：收口渲染器，避免 chat.js 再膨胀）。
//
// 组成（全部复用既有组件，零第二套形态）：
//   · 右键菜单   = contextMenu.js showPopupMenu（好友窗/联系人行同一玻璃面板）
//   · 引用 ref   = reference.js makeReference 的 refType:'chat-message'（镜像 friend-message）
//   · 引用 chip  = renderRefBlock(mode:'input', closeStyle:'disc')（好友窗同款 ✕ 圆盘；
//                  2026-10-09 用户反馈改版：落 #attachment-preview 附件条、与附件 chip
//                  同条带同大小档 —— 尺寸作用域规则在 input.css，颜色/图标沿用现状）
//   · 引用块     = .msg-quote-block（friends.css .fm-quote-block D1 同值材质，两行 clamp）
//   · 跳转高亮   = 1.8s 一次性整块 fade（fmQuoteTargetFlash 同值 --sapphire-tint-15）
//
// 引用态数据面 = activeView.pendingAttachments 里 **至多一条** type:'ref' +
// refType:'chat-message' 的附件（spec §7.4）：随既有 refs wire / 排队 / 草稿链路
// 原样走（input.js send :835、queueMessage、chatView saveDraft 全部零改动复用），
// 本模块只负责「放进/取出 + 渲染」。
//
// id 方案（spec §3.6 一期，纯前端）：
//   · 用户自己发送的行 = clientMessageId（input.js send 生成，与引擎帧同键）
//   · 其余 live 行     = c-<rand>（nextChatMsgId）
//   · 历史重放行       = h<pageIndex>-<mount4>（页内 index + 每次恢复调用的随机
//     mount 尾巴 —— prepend 分页对整页重跑 restoreFromBackendHistory，页内 index
//     会重置，裸 h<index> 会在同一 DOM 里撞号；mount 尾巴既防撞号，也让「跨刷新 /
//     跨重挂载」的旧引用天然失配 ⇒ 退化为快照态（判据缺席、无死点击），与验收口径
//     「一期同屏完整、跨刷新退化」逐字一致）。
//
// 降级矩阵（spec §3.4）：目标行在当前 DOM ⇒ data-quote-jump="ready" 可点；
// 不在（被删/被裁剪/跨刷新 mount 失配）⇒ 无指针 + 「原消息已删除」注记。

import { t } from './i18n.js';
import { showPopupMenu } from './contextMenu.js';
import { makeReference } from './reference.js';
import state from './state.js';

// ── id ──────────────────────────────────────────────────────────────────

let _cSeq = 0;

/** live 非 user 行的消息 id（spec §3.6：c-<rand>）。 */
export function nextChatMsgId() {
  _cSeq += 1;
  return `c-${Date.now().toString(36)}-${_cSeq.toString(36)}${Math.random().toString(36).slice(2, 6)}`;
}

let _hMount = null;
/** 历史重放行的 id 前缀尾巴：每次 restoreFromBackendHistory 调用一个新 mount token。 */
function hMountToken() {
  if (!_hMount) _hMount = Math.random().toString(36).slice(2, 6);
  return _hMount;
}

/** 历史重放行 id（页内 index + mount token，见文件头「id 方案」）。 */
export function historyMsgId(pageIndex) {
  return `h${pageIndex}-${hMountToken()}`;
}

/** 一次历史恢复的开始（persistence.js restoreFromBackendHistory 入口调用）：
 *  换新 mount token ⇒ 上一次恢复的 id 全部作废（跨重挂载诚实失配）。 */
export function beginHistoryMount() {
  _hMount = Math.random().toString(36).slice(2, 6);
}

// ── 摘要规则（spec §2.5 表）─────────────────────────────────────────────

/** 首个非空行 + 60 字符截断（显示摘要口径）。 */
function firstLineExcerpt(text, max = 60) {
  const line = String(text || '').split(/\r?\n/).find(l => l.trim().length > 0) || '';
  const s = line.trim();
  return s.length > max ? s.slice(0, max) + '…' : s;
}

function truncate(s, n) {
  const v = String(s || '');
  return v.length > n ? v.slice(0, n) + '…' : v;
}

/**
 * 行描述符 → 引用摘要/注入正文（§2.5 表 + §3.1 fullText 组装规则）。
 * d.kind: 'text'（用户/助手/注入文本行）| 'tool' | 'ask' | 'image' | 'file'
 *   d.text      — 文本行正文（text/agent）
 *   d.label/d.summary — 工具卡（引擎已产出一行摘要）
 *   d.question  — AskUser 首个 question（调用方先剥标签，同 askUserQuestionText 先例）
 *   d.fileName/d.path — 图片/文件
 *   d.messageId/d.senderRole/d.senderName/d.ts — 身份（§3.1 source）
 */
function excerptOf(d) {
  switch (d.kind) {
    case 'tool': return `${t('chat.quoteTypeTool')} ${truncate(d.label || '', 40)}${d.summary ? ' · ' + truncate(d.summary, 40) : ''}`;
    case 'ask': return `${t('chat.quoteTypeAsk')} ${firstLineExcerpt(d.question)}`;
    case 'image': return `${t('chat.quoteTypeImage')} ${d.fileName || ''}`.trim();
    case 'file': return `${t('chat.quoteTypeFile')} ${d.fileName || ''}`.trim();
    default: return firstLineExcerpt(d.text);
  }
}

function fullTextOf(d) {
  const pathTail = d.path ? ` · ${d.path}` : '';
  switch (d.kind) {
    case 'tool': return excerptOf(d);
    case 'ask': return excerptOf(d);
    case 'image': return `${t('chat.quoteTypeImage')} ${d.fileName || ''}${pathTail}`.trim();
    case 'file': return `${t('chat.quoteTypeFile')} ${d.fileName || ''}${pathTail}`.trim();
    default: return String(d.text || '').slice(0, 1200);
  }
}

/** 行描述符 → makeReference 输入（refType:'chat-message'，§3.1 wire 形状）。 */
export function chatMessageRefInput(d) {
  return {
    refType: 'chat-message',
    source: {
      kind: 'chat-message',
      sessionId: d.sessionId || '',
      messageId: d.messageId || '',
      senderRole: d.senderRole || 'user',
      senderName: d.senderName || '',
    },
    content: { excerpt: excerptOf(d), fullText: fullTextOf(d) },
    meta: { ts: d.ts || 0 },
  };
}

// ── footer 引用按钮（2026-10-09 用户原话：footer 复制钮旁加双引号引用钮，
//    「既可以右键也可以点按钮」）────────────────────────────────────────────
//
// 原则：**按钮 ⊆ footer**。引用按钮只长在**已有 footer 徽章**（.duration-badge，
// 复制钮右侧、同族 .msg-copy 材质）里；右键可用但无 footer 的行（工具卡 / AskUser
// 活卡 / 媒体行 —— beta.56 裁定「footer 仅消息类」，本批不翻案、相邻族零新增视觉）
// 保持右键单入口。挂点复用 attachQuoteMenu（「右键在哪可用」的同一收口），零新挂点。
//
// 时序契约：attachQuoteMenu 挂菜单时徽章可能尚未入行（persistence.js 历史腿与
// finishAi 都是先挂菜单后补徽章，但都在**同一个同步任务**内）⇒ 挂钮走 rAF 延迟
// 收敛（本文件下方 scheduleQuoteButtonMount），帧内徽章必已在场；徽章级幂等
// （已带 .msg-quote-btn 的徽章跳过），round 边界二次 finishAi 追加的新徽章由
// chat.js tagQuotableRow 重入时再排一次收敛补齐。

/** 双引号图标（lucide `quote` 双路径原样；包裹属性与 utils.js COPY_SVG 同一约定
 *  —— 12×12 / 24 viewBox / stroke currentColor / round，视觉与复制钮同族）。 */
const QUOTE_SVG = '<svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M16 3a2 2 0 0 0-2 2v6a2 2 0 0 0 2 2 1 1 0 0 1 1 1v1a2 2 0 0 1-2 2 1 1 0 0 0-1 1v2a1 1 0 0 0 1 1 6 6 0 0 0 6-6V5a2 2 0 0 0-2-2z"/><path d="M5 3a2 2 0 0 0-2 2v6a2 2 0 0 0 2 2 1 1 0 0 1 1 1v1a2 2 0 0 1-2 2 1 1 0 0 0-1 1v2a1 1 0 0 0 1 1 6 6 0 0 0 6-6V5a2 2 0 0 0-2-2z"/></svg>';

/**
 * footer 引用按钮（与右键「引用」完全同动作）。tooltip 复用既有键
 * `messages.quote`（引用/Quote，右键菜单同款 —— 零新 i18n 键）；样式族复用
 * `.msg-copy`（opacity 0.35 常驻、hover 0.6、徽章内 svg 11px —— 零新 CSS 规则），
 * 另挂 `.msg-quote-btn` 作探针/未来特化钩。
 * 点击时的行身份解析与 attachQuoteMenu 的菜单项逐字同链：描述符优先，
 * 缺席回落 `row.dataset.msgId` / activeView.sessionId（描述符取**点击时**的
 * `row.__nfQuoteDesc` —— round 边界二次 finishAi 刷新摘要后按钮引到新文）。
 */
export function createMsgQuoteButton() {
  const btn = document.createElement('button');
  btn.className = 'msg-copy msg-quote-btn';
  btn.title = t('messages.quote');
  btn.setAttribute('aria-label', t('messages.quote'));
  btn.innerHTML = QUOTE_SVG;
  btn.addEventListener('click', (e) => {
    e.stopPropagation();
    const row = btn.closest('.row');
    const d = (row && row.__nfQuoteDesc) || {};
    const view = state.getActiveView && state.getActiveView();
    startQuote({
      ...d,
      messageId: d.messageId || (row && row.dataset.msgId) || '',
      sessionId: d.sessionId || (view && view.sessionId) || '',
    });
  });
  return btn;
}

/** 徽章收敛：行内每个直属 footer 徽章补一枚引用按钮（复制钮右侧；徽章级幂等）。
 *  无徽章的行（工具卡/AskUser 活卡/媒体行）不动 —— 按钮 ⊆ footer（见文件头顶注）。 */
export function mountQuoteFooterButton(rowEl) {
  if (!rowEl || !rowEl.__nfQuoteDesc) return;
  rowEl.querySelectorAll(':scope > .duration-badge').forEach((badge) => {
    if (badge.querySelector('.msg-quote-btn')) return;
    badge.appendChild(createMsgQuoteButton());
  });
}

let _btnMountRaf = 0;
const _btnMountRows = new Set();

/** rAF 合流的延迟挂钮（同一帧多行只排一次回调）。行被移除 ⇒ 收敛时跳过。 */
export function scheduleQuoteButtonMount(rowEl) {
  if (!rowEl) return;
  _btnMountRows.add(rowEl);
  if (_btnMountRaf) return;
  _btnMountRaf = requestAnimationFrame(() => {
    _btnMountRaf = 0;
    const rows = Array.from(_btnMountRows);
    _btnMountRows.clear();
    rows.forEach((row) => { if (row.isConnected) mountQuoteFooterButton(row); });
  });
}

// ── 引用态（composer 附件条内引用 chip；2026-10-09 用户反馈改版：与附件 chip
//    同条带 = #attachment-preview、输入框上方，原「输入框下方整宽引用条」退役）──

function attsOf(target) {
  if (target && Array.isArray(target.attachments)) return target.attachments;
  const view = state.getActiveView && state.getActiveView();
  return (view && Array.isArray(view.pendingAttachments)) ? view.pendingAttachments : [];
}

/** pendingAttachments 里的引用态（至多一条；多条取首条）。 */
export function quoteAttOf(atts) {
  return (atts || []).find(a => a && a.type === 'ref' && a.refType === 'chat-message') || null;
}

/**
 * 进入引用态（右键菜单「引用」/footer 引用按钮）。微信语义：一条回复只带一条引用
 * ⇒ 已有引用态做**替换**（换引，不是追加）。popups（无附件条宿主 = dom.attPreview
 * 缺席）不进引用态。
 */
export function startQuote(d) {
  if (!d) return false;
  const view = state.getActiveView && state.getActiveView();
  if (!view || !Array.isArray(view.pendingAttachments)) return false;
  if (!view.dom || !view.dom.attPreview) return false;   // 无附件条宿主 ⇒ 不进引用态
  const ref = makeReference(chatMessageRefInput(d));
  if (!ref) return false;
  view.pendingAttachments = view.pendingAttachments.filter(a => !(a && a.type === 'ref' && a.refType === 'chat-message'));
  view.pendingAttachments.push(ref);
  renderQuoteStrip();
  return true;
}

/** 取消/消费引用态（✕ 键、发送完成、排队三条路径共用）。 */
export function clearQuote() {
  const view = state.getActiveView && state.getActiveView();
  if (view && Array.isArray(view.pendingAttachments)) {
    view.pendingAttachments = view.pendingAttachments.filter(a => !(a && a.type === 'ref' && a.refType === 'chat-message'));
  }
  renderQuoteStrip();
}

/** 引用 chip 落面（宿主 = activeView 附件条 #attachment-preview，与附件同条带）。
 *  数据单点 = target/activeView 的 pendingAttachments；渲染单点 = chat.js
 *  renderAttachmentPreview（引用 chip 已并入同一重建循环）—— 本函数只负责在引用态
 *  增删后触发同一次重渲。动态 import：chat.js 静态依赖本模块，禁静态环
 *  （chatView.js restoreDraft 同款动态导入先例）。排队撤回（recallQueuedItem）、
 *  草稿恢复（chatView.restoreDraft）等既有附件链路回填后调一次即可复原。 */
export function renderQuoteStrip(target) {
  const atts = attsOf(target);
  const host = (target && target.attPreviewEl) || null;
  import('./chat.js').then(({ renderAttachmentPreview }) => {
    renderAttachmentPreview(host ? { attPreviewEl: host, attachments: atts } : { attachments: atts });
  }).catch(() => {});
}

// ── 右键菜单 ────────────────────────────────────────────────────────────

/** 行挂右键菜单（spec §2.1）。items：有正文（d.text）⇒ 复制 + 引用；其余 ⇒ 仅引用。
 *  与好友窗 msgmenu 同壳（showPopupMenu），零第二套菜单形态。
 *  🔴 messageId/sessionId 单点注入：所有挂点（chat.js/persistence.js/popArtifacts.js）
 *  只描述「行内容」，行身份统一从 rowEl.dataset.msgId 取（缺席时回落 activeView
 *  的 sessionId）——挂点漏传 id 不会产生无锚引用。 */
export function attachQuoteMenu(rowEl, d) {
  if (!rowEl || !d) return;
  // footer 引用按钮与右键菜单同一挂点（「右键在哪可用」单收口）：描述符存行上
  // （按钮点击时读取），rAF 内收敛徽章（时序契约见「footer 引用按钮」头注）。
  rowEl.__nfQuoteDesc = d;
  scheduleQuoteButtonMount(rowEl);
  rowEl.addEventListener('contextmenu', (e) => {
    e.preventDefault();
    const items = [];
    const body = String(d.text || '');
    if (body.trim()) {
      items.push({ label: t('messages.copy'), onClick: () => { navigator.clipboard?.writeText(body).catch(() => {}); } });
    }
    items.push({ label: t('messages.quote'), onClick: () => {
      const view = state.getActiveView && state.getActiveView();
      startQuote({
        ...d,
        messageId: d.messageId || rowEl.dataset.msgId || '',
        sessionId: d.sessionId || (view && view.sessionId) || '',
      });
    } });
    showPopupMenu(e.clientX, e.clientY, items);
  });
}

// ── 气泡引用块（live 与历史重放同一渲染器，spec §3.4）────────────────────

/** UiMessage.User.quote / 引用 ref → 引用块的统一视图。 */
function quoteViewOf(q) {
  if (!q || typeof q !== 'object') return null;
  return {
    refMessageId: String(q.refMessageId || (q.source && q.source.messageId) || ''),
    excerpt: String(q.excerpt || (q.content && q.content.excerpt) || ''),
    senderRole: String(q.senderRole || (q.source && q.source.senderRole) || ''),
    senderName: String(q.senderName || (q.source && q.source.senderName) || ''),
    deleted: q.deleted === true,
  };
}

function senderLabelOf(q) {
  if (q.senderName) return q.senderName;
  if (q.senderRole === 'user') return t('chat.quoteSenderMe');
  return '';
}

function quoteTargetRow(box) {
  const id = box.dataset.refMessageId || '';
  if (!id) return null;
  const root = box.closest('#chat') || document;
  return root.querySelector(`.row[data-msg-id="${CSS.escape(id)}"]`) || null;
}

/** 引用块可点性 + 降级注记落地（**单点**；spec §3.4 判据矩阵）。
 *  挂载时目标行可能尚未渲染（恢复腿里引用块先于页内靠后的目标行创建）⇒
 *  restoreFromBackendHistory 末尾 / renderUserBubble 末尾各收敛一次。 */
export function syncQuoteJumpStates(root) {
  const scope = root || document;
  scope.querySelectorAll('.msg-quote-block[data-ref-message-id]').forEach((box) => {
    if (!box.isConnected) return;
    const hit = !!quoteTargetRow(box);
    box.dataset.quoteJump = hit ? 'ready' : 'none';
    if (hit) {
      box.setAttribute('role', 'button');
      box.setAttribute('tabindex', '0');
      box.title = t('chat.quoteJumpHint');
      box.setAttribute('aria-label', t('messages.quoteJumpAria', { text: box.textContent || '' }));
    } else {
      box.removeAttribute('role');
      box.removeAttribute('tabindex');
      box.removeAttribute('title');
      box.removeAttribute('aria-label');
    }
    let note = box.querySelector('.msg-quote-note');
    if (!note) {
      note = document.createElement('div');
      note.className = 'msg-quote-note';
      box.appendChild(note);
    }
    note.textContent = hit ? '' : t('chat.quoteDeleted');
  });
}

/** 气泡引用块渲染器（唯一实现）。quote = UiMessage.User.quote 形状
 *  （{refMessageId, excerpt, senderRole, senderName, ts?, deleted?}）或引用 ref 本体
 *  （live/排队腿从附件推导）。可点性由 syncQuoteJumpStates 统一收敛。 */
export function renderQuoteBlock(quote) {
  const q = quoteViewOf(quote);
  const box = document.createElement('div');
  box.className = 'msg-quote-block';
  if (!q || (!q.excerpt && !q.refMessageId)) {
    box.dataset.quoteJump = 'none';
    const note = document.createElement('div');
    note.className = 'msg-quote-note';
    note.textContent = t('chat.quoteDeleted');
    box.appendChild(note);
    return box;
  }
  box.dataset.refMessageId = q.refMessageId;
  const line = document.createElement('div');
  line.className = 'msg-quote-text';
  const sender = document.createElement('span');
  sender.className = 'msg-quote-sender';
  sender.textContent = senderLabelOf(q);
  line.appendChild(sender);
  line.appendChild(document.createTextNode(sender.textContent ? `：${q.excerpt}` : q.excerpt));
  box.appendChild(line);
  // 点击不消费菜单/不触发行上其它交互。
  box.addEventListener('click', (e) => { e.stopPropagation(); jumpToQuote(box); });
  box.addEventListener('keydown', (e) => {
    if (e.key !== 'Enter' && e.key !== ' ' && e.key !== 'Spacebar') return;
    e.preventDefault();
    jumpToQuote(box);
  });
  return box;
}

// ── 跳转 + 高亮（同屏；fmQuoteTargetFlash 同款 1.8s 整块 fade）───────────

let _flashTimer = 0;

/** 滚动到目标行（在其滚动容器内滚，不写任意外层祖先 —— revealQuoteTarget 同纪律）
 *  + 一次性 1.8s 高亮（宿主 = 整行；prefers-reduced-motion 由 CSS 降为 0.01s）。 */
export function jumpToQuote(box) {
  if (!box || box.dataset.quoteJump !== 'ready') return false;
  const row = quoteTargetRow(box);
  if (!row) return false;
  // 就近滚动容器（overflow-y auto/scroll 的最近祖先）。
  let scroller = row.parentElement;
  while (scroller && scroller !== document.body) {
    const oy = getComputedStyle(scroller).overflowY;
    if (oy === 'auto' || oy === 'scroll') break;
    scroller = scroller.parentElement;
  }
  if (scroller && scroller !== document.body) {
    const fr = scroller.getBoundingClientRect();
    const nr = row.getBoundingClientRect();
    const max = Math.max(0, scroller.scrollHeight - scroller.clientHeight);
    const delta = (nr.top - fr.top) - Math.max(0, (scroller.clientHeight - nr.height) / 2);
    scroller.scrollTop = Math.max(0, Math.min(max, scroller.scrollTop + delta));
  }
  row.classList.remove('quote-target-flash');
  void row.offsetWidth; // 重放动画（同一行连点两次）
  row.classList.add('quote-target-flash');
  if (_flashTimer) clearTimeout(_flashTimer);
  _flashTimer = setTimeout(() => row.classList.remove('quote-target-flash'), 1800);
  return true;
}

/** 引用 ref（wire/pendingAttachments 形状）→ renderQuoteBlock 入参视图。 */
export function quoteFromRef(ref) {
  if (!ref || ref.refType !== 'chat-message') return null;
  return {
    refMessageId: (ref.source && ref.source.messageId) || '',
    excerpt: (ref.content && ref.content.excerpt) || '',
    senderRole: (ref.source && ref.source.senderRole) || 'user',
    senderName: (ref.source && ref.source.senderName) || '',
  };
}

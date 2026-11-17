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
// 循环门禁修复批（2026-11-04）：本模块只要**模型**（makeReference = 行描述符 →
// Reference 对象），故从叶子 refModel.js 取，不再静态 import reference.js 的渲染面
// ——后者会连带把 popArtifacts 拉进本模块的静态闭包，与
// popArtifacts → msgQuote 的有意边成 SCC[3]（scripts/check-circular.mjs）。
// reference.js 保留 makeReference 同名 re-export 作兼容面。
import { makeReference } from './refModel.js';
import state from './state.js';
// 引用牌图标化批（2026-11-02 11:00 作者令）：气泡引用块与 composer chip 的「类型
// 图标」同一实现（图片=真缩略 / 文档=.fc-icon 原图标 / 文字=引用入口图标），单点 =
// quoteLogo.js（对两个宿主都是叶子方向 ⇒ 引用面静态闭包不增边）。footer 引用键的
// 字形也从这里取（QUOTE_ENTRY_ICON，图标统一批 2026-11-02 12:58：键与引用牌同一枚，
// 零第二份 path）。
import { quoteTypeIcon, QUOTE_ENTRY_ICON } from './quoteLogo.js';

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

/** 叠卡单张位置标注（叠卡内单图引用批 2026-11-02 作者令「让叠卡的图片可以分开
 *  引用」）：d.itemIndex（0 基）+ d.itemTotal（该组总张数，≥2）⇒「第 N/total 张」
 *  后缀。任一项缺席/非法 ⇒ ''（= 消息级引用，**零张数 UI**：单图行与普通文本/
 *  工具行退化路径逐字不变，验收项④）。 */
function quoteItemPosOf(d) {
  const i = Number(d && d.itemIndex);
  const n = Number(d && d.itemTotal);
  if (!Number.isInteger(i) || i < 0 || !Number.isInteger(n) || n < 2) return '';
  return t('chat.quoteImagePos', { n: i + 1, total: n });
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
    // 叠卡单张（itemIndex 在场）⇒ 摘要带「第 N/total 张」：三张图的引用牌
    // 因此**互不相同**（验收项②），引用对象仍是该图自己的身份（图片名 + 位置）。
    // 位置放**文件名之前**：引用块是两行 clamp（窄气泡时更短），尾部的标注会被
    // 截掉 —— 标注必须在看得见的位置（作者令「引用牌要能区分」的可读落地）。
    case 'image': return `${t('chat.quoteTypeImage')} ${quoteItemPosOf(d) ? quoteItemPosOf(d) + ' · ' : ''}${d.fileName || ''}`.trim();
    case 'file': return `${t('chat.quoteTypeFile')} ${d.fileName || ''}`.trim();
    default: return firstLineExcerpt(d.text);
  }
}

function fullTextOf(d) {
  const pathTail = d.path ? ` · ${d.path}` : '';
  switch (d.kind) {
    case 'tool': return excerptOf(d);
    case 'ask': return excerptOf(d);
    // 注入正文（fullText，模型读的语义面）同样带位置：引用「第 2 张」与「整组」
    // 在上下文里必须可区分。
    case 'image': {
      const pos = quoteItemPosOf(d);
      return `${t('chat.quoteTypeImage')} ${pos ? pos + ' · ' : ''}${d.fileName || ''}${pathTail}`.trim();
    }
    case 'file': return `${t('chat.quoteTypeFile')} ${d.fileName || ''}${pathTail}`.trim();
    default: return String(d.text || '');
  }
}

/** 行描述符 → makeReference 输入（refType:'chat-message'，§3.1 wire 形状）。
 *  附件批（2026-10-31 作者令「引用要支持你发的文件 图片等」）：描述符 d.attachments
 *  （媒体行全量附件 / 文本行引用时兄弟媒体行合入）随 content.attachments 上 wire，
 *  live 腿引用块据此渲染附件缩略；后端落盘 quote 快照白名单化 type/name/path
 *  （WebSocketRoutes.quoteFromRefs），preview（data: 内联字节）只活在前端内存。
 *
 *  **叠卡单张引用批（2026-11-02 作者令「让叠卡的图片可以分开引用」）——数据面
 *  只做**扩展**，不换引用目标模型（申报见文档 §数据模型两方案）：
 *   · 引用目标仍 = 该行的 messageId（不变；深链寻址/就绪判据/降级矩阵零改动）；
 *   · 新增 content.itemIndex（0 基）+ content.itemTotal（该组张数）＝「这张在第
 *     几位、组里共几张」——引用牌据此标注「第 N/total 张」（互不相同的判据）；
 *   · 单张引用时 content.attachments 只带**被引那张**（一张起引用牌的缩略/文件名
 *     就是那张的，不再是整组）；缺席 itemIndex ⇒ 既有全量语义逐字节不变。
 *  代价 = 后端 quote 快照白名单需放行两个整数字段（旧载荷零变化，旧行照旧渲染）。 */
export function chatMessageRefInput(d) {
  const atts = Array.isArray(d.attachments) ? d.attachments : [];
  const content = {
    excerpt: excerptOf(d),
    fullText: fullTextOf(d),
    fullTextComplete: d.kind === 'text' || d.kind === 'image' || d.kind === 'file',
    attachments: atts.slice(0, MAX_QUOTE_ATTS).map(a => ({
      type: a.type === 'image' || a.type === 'video' ? a.type : 'file',
      name: String(a.name || '').slice(0, 200),
      path: String(a.path || '').slice(0, 600),
      preview: (typeof a.preview === 'string' && a.preview.startsWith('data:')) ? a.preview : undefined,
    })),
  };
  const idx = Number(d.itemIndex);
  const total = Number(d.itemTotal);
  if (Number.isInteger(idx) && idx >= 0) {
    content.itemIndex = idx;
    if (Number.isInteger(total) && total > idx) content.itemTotal = total;
  }
  return {
    refType: 'chat-message',
    source: {
      kind: 'chat-message',
      sessionId: d.sessionId || '',
      messageId: d.messageId || '',
      senderRole: d.senderRole || 'user',
      senderName: d.senderName || '',
    },
    content,
    meta: { ts: d.ts || 0 },
  };
}

/** 引用快照随附附件的上限（引用块缩略面带，防失控行的 wire/落盘膨胀）。 */
export const MAX_QUOTE_ATTS = 8;

// ── footer 引用按钮（2026-10-09 用户原话：footer 复制钮旁加双引号引用钮，
//    「既可以右键也可以点按钮」）────────────────────────────────────────────
//
// 原则：**按钮 ⊆ footer**。引用按钮只长在**已有 footer 徽章**（.duration-badge，
// 复制钮右侧、同族 .msg-copy 材质）里；右键可用但无 footer 的行（工具卡 / AskUser
// 活卡）保持右键单入口。媒体行原也无徽章（beta.56 裁定「footer 仅消息类」）——
// 手机端长按退役批（2026-10-31 作者令）翻案该面：媒体行补 footer 信息条
// （popArtifacts.buildUserMediaRow，时间/下载/引用钮同族），触屏触达不再依赖
// 已退役的长按。挂点复用 attachQuoteMenu（「右键在哪可用」的同一收口），零新挂点。
//
// 时序契约：attachQuoteMenu 挂菜单时徽章可能尚未入行（persistence.js 历史腿与
// finishAi 都是先挂菜单后补徽章，但都在**同一个同步任务**内）⇒ 挂钮走 rAF 延迟
// 收敛（本文件下方 scheduleQuoteButtonMount），帧内徽章必已在场；徽章级幂等
// （已带 .msg-quote-btn 的徽章跳过），round 边界二次 finishAi 追加的新徽章由
// chat.js tagQuotableRow 重入时再排一次收敛补齐。

/** 双引号图标 = **引用入口图标**（单点在 quoteLogo.js）。字形统一批（作者令
 *  2026-11-02 12:58「图标就用跟我们footer那个引用图标一样的吧」）把此前内联在本文件
 *  的 `QUOTE_SVG` 上提到 quoteLogo.js 的 `QUOTE_ENTRY_ICON`：footer 徽章引用钮、
 *  气泡引用牌、composer 引用 chip 三处同源同一份 path（零第二份抄写）。
 *  （2026-11-03 10:11 ② 之后 footer 动作行引用键退役，字形族不变——三处照旧同源。）
 *  保留 `QUOTE_SVG` 这个名字给既有调用点/测试（同名 re-export，语义不变）。 */
const QUOTE_SVG = QUOTE_ENTRY_ICON;
// 字形导出（叠卡单张引用批 2026-11-02）：叠卡展开行的单张引用钮与 footer 引用钮
// 同一枚字形（禁第二份拷贝 —— 字形漂移即第二套样式）。
export { QUOTE_SVG };

/**
 * footer 引用按钮（与右键「引用」完全同动作）。tooltip 复用既有键
 * `messages.quote`（引用/Quote，右键菜单同款 —— 零新 i18n 键）；样式族复用
 * `.msg-copy`（opacity 0.35 常驻、hover 0.6、徽章内 svg 11px —— 零新 CSS 规则），
 * 另挂 `.msg-quote-btn` 作探针/未来特化钩。
 * 点击时的行身份解析与 attachQuoteMenu 的菜单项逐字同链：描述符优先，
 * 缺席回落 `row.dataset.msgId` / activeView.sessionId（描述符取**点击时**的
 * `row.__nfQuoteDesc` —— round 边界二次 finishAi 刷新摘要后按钮引到新文）。
 */
export function createMsgQuoteButton(itemDesc = null) {
  const btn = document.createElement('button');
  btn.className = itemDesc ? 'msg-copy msg-quote-item-btn' : 'msg-copy msg-quote-btn';
  btn.title = t('messages.quote');
  btn.setAttribute('aria-label', t('messages.quote'));
  btn.innerHTML = QUOTE_SVG;
  btn.addEventListener('click', (e) => {
    e.stopPropagation();
    // __nfQuoteDesc 是本模块挂在行元素上的自定义载荷（expando）——lib.dom 无此
    // 属性面，行引用按 any 视图读取（dataset 同视图）。
    const row = /** @type {any} */ (btn.closest('.row'));
    const d = (row && row.__nfQuoteDesc) || {};
    if (typeof itemDesc === 'function') {
      const item = itemDesc(row);
      if (item) startItemQuote(row, item);
    } else if (itemDesc) startItemQuote(row, itemDesc);
    else startQuote(resolveQuoteDesc(row, d));
  });
  return btn;
}

/** 行内**单件**引用（叠卡单张引用批 2026-11-02 作者令「让叠卡的图片可以分开
 *  引用」）：宿主面板只描述「这一件是什么」（itemDesc），行身份（messageId /
 *  sessionId / 发送方）与行级描述符仍在**这里**统一解析 —— 挂点漏传身份也不会
 *  产生无锚引用（attachQuoteMenu 的同一纪律）。itemDesc 覆盖行级描述符的同名键
 *  （kind/fileName/path/attachments），并带 itemIndex/itemTotal 作位置标注。 */
export function startItemQuote(rowEl, itemDesc) {
  if (!itemDesc) return false;
  const base = (rowEl && /** @type {any} */ (rowEl).__nfQuoteDesc) || {};
  return startQuote(resolveQuoteDesc(rowEl, { ...base, ...itemDesc }));
}

/** 徽章收敛：行内每个直属 footer 徽章补一枚引用按钮（复制钮右侧；徽章级幂等）。
 *  无徽章的行（工具卡/AskUser 活卡）不动 —— 按钮 ⊆ footer（见文件头顶注）。 */
export function mountQuoteFooterButton(rowEl) {
  if (!rowEl || !rowEl.__nfQuoteDesc) return;
  rowEl.querySelectorAll(':scope > .duration-badge').forEach((badge) => {
    if (badge.querySelector('.msg-quote-btn')) return;
    const btn = createMsgQuoteButton();
    btn.hidden = Array.from(rowEl.querySelectorAll('.pop-stack-area')).some(stack => stack.popExpanded?.());
    badge.appendChild(btn);
  });
}

// ── 🔴 composer footer 引用入口键整链退役（作者令 2026-11-03 10:11 ②）──────────
//    作者原意逐字：「引用消息时用图标替代长文本展示」——**不是**在输入框加一个
//    引用按钮。2026-11-02 11:00「引用入口移入 footer」批把叠卡单张引用做成了
//    composer 动作行的一枚 `.icon-btn`（`#quote-item-btn`，动作对象 = 最近一条叠卡
//    的当前张，靠 MutationObserver + scroll + resize 现算显隐）。本批按令**整链
//    摘除**：DOM 键（index.html）、CSS 冻结档（input.css）、冻结名单（main/freeze.js）、
//    接线（main.js）、以及这里的装配面（initQuoteItemButton / refreshQuoteItemButton /
//    currentQuoteStackTarget / _qib* 观察器）全部退役，**不留替代按钮**。
//    叠卡单张引用的能力面（startItemQuote）原样保留：它现在没有 UI 入口，等「引用
//    消息图标化」语义核准后再设计入口（本批只做移除，不新造入口）。
//    防复活钉 = ~/.stand/evidence/ui-unification-20261103/input-fixes/
//    verify-five-fixes.mjs ② 腿（五处 #quote-item-btn / initQuoteItemButton 0 命中）。

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

/** 兄弟媒体行附件合入（附件批 2026-10-31）：用户文本行与它的媒体卡行共用同一
 *  data-msg-id（chat.js renderUserBubble 随行挂），右键**文本行**时把媒体行
 *  描述符里的附件并入引用 —— 引用对象 = 整条消息（微信语义），不是半条。 */
function siblingMediaAttsOf(rowEl) {
  const id = rowEl && rowEl.dataset ? rowEl.dataset.msgId : '';
  if (!id) return null;
  const root = rowEl.closest('#chat') || document;
  const mediaRow = root.querySelector(`.row.user-media[data-msg-id="${CSS.escape(id)}"]`);
  const md = mediaRow && mediaRow.__nfQuoteDesc;
  return (md && Array.isArray(md.attachments) && md.attachments.length) ? md.attachments : null;
}

/** 行身份 + 附件的统一解析（右键菜单项 / footer 引用按钮同一链）：messageId/
 *  sessionId 单点注入口径不变，附件 = 描述符自带 ⇢ 兄弟媒体行合入。 */
function resolveQuoteDesc(rowEl, d) {
  const view = state.getActiveView && state.getActiveView();
  return {
    ...d,
    messageId: d.messageId || (rowEl && rowEl.dataset.msgId) || '',
    sessionId: d.sessionId || (view && view.sessionId) || '',
    attachments: (d && Array.isArray(d.attachments) && d.attachments.length) ? d.attachments : siblingMediaAttsOf(rowEl),
  };
}

/** 行上下文菜单的**单点出口**（桌面右键；手机端长按腿已随手机端长按退役批
 *  2026-10-31 作者令整条退役——触屏触达改走消息 footer 信息条：时间/下载/引用钮）：
 *  有正文 ⇒ 复制 + 引用，其余 ⇒ 仅引用；壳 = showPopupMenu（好友窗/联系人行
 *  同一玻璃面板）。 */
export function openQuoteMenuForRow(rowEl, x, y) {
  const d = (rowEl && rowEl.__nfQuoteDesc) || null;
  if (!rowEl || !d) return false;
  const items = [];
  const body = String(d.text || '');
  if (body.trim()) {
    items.push({ label: t('messages.copy'), onClick: () => { navigator.clipboard?.writeText(body).catch(() => {}); } });
  }
  items.push({ label: t('messages.quote'), onClick: () => startQuote(resolveQuoteDesc(rowEl, d)) });
  showPopupMenu(x, y, items);
  return true;
}

/** 行挂右键菜单（spec §2.1）。items：有正文（d.text）⇒ 复制 + 引用；其余 ⇒ 仅引用。
 *  与好友窗 msgmenu 同壳（showPopupMenu），零第二套菜单形态。
 *  🔴 messageId/sessionId 单点注入：所有挂点（chat.js/persistence.js/popArtifacts.js）
 *  只描述「行内容」，行身份统一从 rowEl.dataset.msgId 取（缺席时回落 activeView
 *  的 sessionId）——挂点漏传 id 不会产生无锚引用。
 *  （输入消息排队回退批 2026-10-23：气泡右键菜单队列动作（立即发送/撤回/移除）
 *  随 10-21 排队面回归一并退役——队列动作回到排队条行按钮，见 chatQueue.js。） */
export function attachQuoteMenu(rowEl, d) {
  if (!rowEl || !d) return;
  // footer 引用按钮与右键菜单同一挂点（「右键在哪可用」单收口）：描述符存行上
  // （按钮点击时读取），rAF 内收敛徽章（时序契约见「footer 引用按钮」头注）。
  rowEl.__nfQuoteDesc = d;
  scheduleQuoteButtonMount(rowEl);
  rowEl.addEventListener('nf-pop-expanded', () => {
    const expanded = Array.from(rowEl.querySelectorAll('.pop-stack-area')).some(stack => stack.popExpanded?.());
    rowEl.querySelectorAll(':scope > .duration-badge > .msg-quote-btn').forEach(btn => { btn.hidden = expanded; });
  });
  rowEl.addEventListener('contextmenu', (e) => {
    e.preventDefault();
    // 手机端长按退役批（2026-10-31 作者令）：触屏长按不再弹引用/复制菜单——
    // Android/iOS 长按会原生补发 contextmenu，这里按**事件时**判手机层直接放行
    // （视口跨档重排也零误伤）；桌面视口逐字不动（含 preventDefault 抑制原生菜单）。
    if (document.body.classList.contains('mobile-layer')) return;
    openQuoteMenuForRow(rowEl, e.clientX, e.clientY);
  });
}

// ── 气泡引用块（live 与历史重放同一渲染器，spec §3.4）────────────────────

/** UiMessage.User.quote / 引用 ref → 引用块的统一视图。
 *  attachments（附件批 2026-10-31）：被引消息的附件缩略清单
 *  [{type:'image'|'video'|'file', name, path, preview?}] —— live 腿来自 ref
 *  content.attachments（含 data: 内联 preview），历史腿来自落盘 quote 快照
 *  （后端白名单 type/name/path，无 preview）。 */
function quoteViewOf(q) {
  if (!q || typeof q !== 'object') return null;
  const raw = Array.isArray(q.attachments) ? q.attachments : [];
  // 叠卡单张（2026-11-02）：itemIndex/itemTotal 从快照（历史腿，顶层）或
  // content（live 腿 ref 本体）读；非法/缺席 ⇒ null（消息级引用，零张数 UI）。
  const iRaw = q.itemIndex != null ? q.itemIndex : (q.content && q.content.itemIndex);
  const nRaw = q.itemTotal != null ? q.itemTotal : (q.content && q.content.itemTotal);
  const itemIndex = Number.isInteger(Number(iRaw)) && Number(iRaw) >= 0 && iRaw !== null && iRaw !== undefined
    ? Number(iRaw) : null;
  const itemTotal = itemIndex !== null && Number.isInteger(Number(nRaw)) && Number(nRaw) > itemIndex
    ? Number(nRaw) : null;
  return {
    refMessageId: String(q.refMessageId || (q.source && q.source.messageId) || ''),
    excerpt: String(q.excerpt || (q.content && q.content.excerpt) || ''),
    senderRole: String(q.senderRole || (q.source && q.source.senderRole) || ''),
    senderName: String(q.senderName || (q.source && q.source.senderName) || ''),
    attachments: raw.slice(0, MAX_QUOTE_ATTS).map(a => ({
      type: a.type === 'image' || a.type === 'video' ? a.type : 'file',
      name: String(a.name || ''),
      path: String(a.path || ''),
      preview: (typeof a.preview === 'string' && a.preview.startsWith('data:')) ? a.preview : '',
    })),
    itemIndex,
    itemTotal,
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

/** 单件媒体缩略（引用块附件风格）：48px 方缩略，字节腿与 Pop 脸同一 hydrateMedia；
 *  纯展示，不提供大图/展开动作。 */
function quoteMediaCell(pop, a) {
  const item = { kind: a.type, name: a.name, src: a.preview, path: a.path || '' };
  const cell = document.createElement('div');
  cell.className = 'msg-quote-att-media';
  const media = document.createElement(a.type === 'video' ? 'video' : 'img');
  media.className = 'msg-quote-att-img';
  if (a.type === 'video') { const v = /** @type {HTMLVideoElement} */ (media); v.muted = true; v.playsInline = true; v.preload = 'metadata'; }
  else { const img = /** @type {HTMLImageElement} */ (media); img.alt = a.name || ''; img.draggable = false; }
  pop.hydrateMedia(media, item, null);
  cell.appendChild(media);
  // 引用内缩略只展示，不提供展开/大图点击；卡片外层仍负责消息引用跳转。
  cell.title = a.name || '';
  return cell;
}

/** 非媒体附件 chip（图标 + 文件名；有 path 才挂打开，零死点击）。 */
function quoteFileChip(pop, a) {
  const chip = document.createElement('div');
  chip.className = 'msg-quote-att-file';
  const icon = document.createElement('span');
  icon.className = 'msg-quote-att-file-icon';
  icon.textContent = (a.name || '').split('.').pop() ? (a.name.split('.').pop() || '').slice(0, 4).toUpperCase() : 'FILE';
  const label = document.createElement('span');
  label.className = 'msg-quote-att-file-name';
  label.textContent = a.name || (a.path ? a.path.split('/').pop() : '') || '';
  chip.append(icon, label);
  chip.title = a.path || a.name || '';
  if (a.path) {
    chip.setAttribute('role', 'button');
    chip.addEventListener('click', (e) => {
      e.stopPropagation();
      pop.openPopArtifact(a.path, a.name || a.path.split('/').pop());
    });
  }
  return chip;
}

/** 引用块附件缩略带：图片/视频 = 48px 纯展示缩略，文件 = 图标 + 文件名 chip。
 *  动态 import：popArtifacts.js 静态依赖本模块（attachQuoteMenu/nextChatMsgId），
 *  本模块禁静态回指（renderQuoteStrip 动态导入 chat.js 同款先例）。
 */
function renderQuoteAttStrip(atts) {
  const strip = document.createElement('div');
  strip.className = 'msg-quote-atts';
  const list = Array.isArray(atts) ? atts : [];
  import('./popArtifacts.js').then((pop) => {
    if (!strip.isConnected) return;
    const media = list.filter(a => a && (a.type === 'image' || a.type === 'video'));
    const files = list.filter(a => a && a.type !== 'image' && a.type !== 'video');
    // 引用内容只作附件式缩略预览：无叠卡/展开/大图控件，每件独立小缩略。
    media.forEach(a => strip.appendChild(quoteMediaCell(pop, a)));
    files.forEach((a) => strip.appendChild(quoteFileChip(pop, a)));
  }).catch(() => {});
  return strip;
}

/** 气泡引用块渲染器（唯一实现）。quote = UiMessage.User.quote 形状
 *  （{refMessageId, excerpt, senderRole, senderName, ts?, deleted?, attachments?}）
 *  或引用 ref 本体（live/排队腿从附件推导）。可点性由 syncQuoteJumpStates 统一收敛。
 *  附件批（2026-10-31）：attachments 在场 ⇒ 摘要行下挂缩略带（文件卡/图片预览，
 *  点击可开），只兜文字的现状退役。 */
export function renderQuoteBlock(quote) {
  const q = quoteViewOf(quote);
  const box = document.createElement('div');
  // glass-unify 批 2026-11-05：引用卡/胶囊挂唯一毛玻璃工具类（作者令「所有需要
  // 毛玻璃的面板/浮层/卡片/胶囊一律用它」；实拍图1 那张半透明引用牌就是这里）。
  box.className = 'msg-quote-block nf-glass';
  if (!q || (!q.excerpt && !q.refMessageId)) {
    box.dataset.quoteJump = 'none';
    const note = document.createElement('div');
    note.className = 'msg-quote-note';
    note.textContent = t('chat.quoteDeleted');
    box.appendChild(note);
    return box;
  }
  box.dataset.refMessageId = q.refMessageId;
  // 叠卡单张（2026-11-02）：位置钉在引用块上 ⇒ 跳转读得到「展开到第几张」，
  // 探针也读得到（验收项③/④的读数面）。
  if (q.itemIndex !== null && q.itemIndex !== undefined) box.dataset.quoteItemIndex = String(q.itemIndex);
  // 引用牌图标化（2026-11-02 11:00 作者令「引用的消息也需要图标化，文档和图片就是
  // 原图标和图片缩略，文字的话就单独设计一个logo」）：图标是摘要行的**前置件** ——
  // `.msg-quote-head` 只装图标，`.msg-quote-text` 保持逐字不变（发送方名 + `：` +
  // 摘要，两行 clamp 与既有断言面「text = 摘要全文」零回归）。
  const head = document.createElement('div');
  head.className = 'msg-quote-head';
  head.appendChild(quoteTypeIcon(q, 'msg-quote-icon'));
  box.appendChild(head);
  const line = document.createElement('div');
  line.className = 'msg-quote-text';
  const sender = document.createElement('span');
  sender.className = 'msg-quote-sender';
  sender.textContent = senderLabelOf(q);
  line.appendChild(sender);
  line.appendChild(document.createTextNode(sender.textContent ? `：${q.excerpt}` : q.excerpt));
  box.appendChild(line);
  // 位置标注只有一处 UI = 摘要行尾的「第 N/total 张」（excerptOf 拼好，见该处）：
  // 不再挂第二枚标记元素（同一信息不重复上屏）。消息级引用（无 index）连这个
  // 后缀都没有 ⇒ 验收项④「单图退化无张数 UI」的机械判据 = 摘要不含该串 +
  // 引用块无 data-quote-item-index。
  if (q.attachments.length) box.appendChild(renderQuoteAttStrip(q.attachments));
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
let _itemFlashTimer = 0;

/** 叠卡单图深链（2026-11-02 作者令「让叠卡的图片可以分开引用」验收项④/⑤）：
 *  引用块带 data-quote-item-index ⇒ 跳转**定位到那一张**：
 *   ① 目标行的叠卡若处于收起态 ⇒ 先展开（`pill.click()` = 用户手势同一条路，
 *      零第二套开关；展开后每行是自己的真实比，能看清是哪一张）；
 *   ② 展开列里第 N 行打 `.quote-item-flash`（1.8s，与行级 flash 同节奏同色阶）；
 *   ③ 再按「该行是否已在视口内」把滚动目标从整行切到那一行（比整行更准）。
 *  卡片暴露的钩子是 `area.popFaces`（index → 折叠层/展开层元素，见
 *  popArtifacts.buildMediaStack 头注），不新增 DOM 契约；拿不到钩子（旧宿主/
 *  纯内联腿）⇒ 静默退回整行定位（零死点击、零异常）。 */
function revealQuoteItem(row, index) {
  if (!row || !Number.isInteger(index) || index < 0) return null;
  const area = row.querySelector('.pop-stack-area');
  if (!area) return null;
  const faces = Array.isArray(area.popFaces) ? area.popFaces : [];
  const face = faces[index];
  const expandLayer = face && Array.isArray(face.els)
    ? face.els.find(el => el && el.closest && el.closest('.pop-expand-view'))
    : null;
  const layer = expandLayer ? expandLayer.closest('.media-layer') : null;
  if (!layer) return null;
  const pill = area.querySelector('.pill');
  if (pill && !area.querySelector('.pop-expand-view.show')) pill.click(); // 收起态 ⇒ 展开
  layer.classList.remove('quote-item-flash');
  void layer.offsetWidth; // 重放动画
  layer.classList.add('quote-item-flash');
  if (_itemFlashTimer) clearTimeout(_itemFlashTimer);
  _itemFlashTimer = setTimeout(() => {
    document.querySelectorAll('.media-layer.quote-item-flash').forEach(el => el.classList.remove('quote-item-flash'));
  }, 1800);
  return layer;
}

/** 同一 msg-id 的全部行（用户「气泡行 + 媒体卡行」共用同一 id，见 chat.js
 *  renderUserBubble：mediaRow.dataset.msgId = row.dataset.msgId）——单图跳转要挑
 *  出**带叠卡的那一行**，不能停在文档序第一行（气泡行没有叠卡）。 */
function targetRows(box) {
  const id = box.dataset.refMessageId || '';
  if (!id) return [];
  const root = box.closest('#chat') || document;
  return Array.from(root.querySelectorAll(`.row[data-msg-id="${CSS.escape(id)}"]`));
}

/** 滚动到目标行（在其滚动容器内滚，不写任意外层祖先 —— revealQuoteTarget 同纪律）
 *  + 一次性 1.8s 高亮（宿主 = 整行；prefers-reduced-motion 由 CSS 降为 0.01s）。 */
export function jumpToQuote(box) {
  if (!box || box.dataset.quoteJump !== 'ready') return false;
  const rows = targetRows(box);
  if (!rows.length) return false;
  const idx = Number(box.dataset.quoteItemIndex ?? NaN);
  // 叠卡单图（2026-11-02）：带叠卡的那一行优先（展开 + 高亮第 N 张），
  // 滚动目标优先用那张本身（比整行更准）；拿不到叠卡/旧宿主 ⇒ 退回整行。
  const stackRow = Number.isInteger(idx) ? (rows.find(r => r.querySelector('.pop-stack-area')) || rows[0]) : rows[0];
  const itemLayer = revealQuoteItem(stackRow, idx);
  const row = stackRow;
  const target = itemLayer || row;
  // 就近滚动容器（overflow-y auto/scroll 的最近祖先）。
  let scroller = target.parentElement;
  while (scroller && scroller !== document.body) {
    const oy = getComputedStyle(scroller).overflowY;
    if (oy === 'auto' || oy === 'scroll') break;
    scroller = scroller.parentElement;
  }
  if (scroller && scroller !== document.body) {
    const fr = scroller.getBoundingClientRect();
    const nr = target.getBoundingClientRect();
    const max = Math.max(0, scroller.scrollHeight - scroller.clientHeight);
    const delta = (nr.top - fr.top) - Math.max(0, (scroller.clientHeight - nr.height) / 2);
    scroller.scrollTop = Math.max(0, Math.min(max, scroller.scrollTop + delta));
  }
  row.classList.remove('quote-target-flash');
  // 同一 msg-id 的兄弟行（用户「气泡行 + 媒体卡行」）一并高亮：引用对象是整条
  // 消息，高亮只落在其中一行会让「点回去」看起来只到一半（单图引用批 2026-11-02）。
  rows.forEach(r => {
    r.classList.remove('quote-target-flash');
    void r.offsetWidth; // 重放动画（同一行连点两次）
    r.classList.add('quote-target-flash');
  });
  if (_flashTimer) clearTimeout(_flashTimer);
  _flashTimer = setTimeout(() => {
    document.querySelectorAll('.row.quote-target-flash').forEach(el => el.classList.remove('quote-target-flash'));
  }, 1800);
  return true;
}

/** 引用 ref（wire/pendingAttachments 形状）→ renderQuoteBlock 入参视图。
 *  附件批：ref.content.attachments 随带（live 腿，含内联 preview）。
 *  叠卡单张：content.itemIndex/itemTotal 一并透传（quoteViewOf 消费）。 */
export function quoteFromRef(ref) {
  if (!ref || ref.refType !== 'chat-message') return null;
  return {
    refMessageId: (ref.source && ref.source.messageId) || '',
    excerpt: (ref.content && ref.content.excerpt) || '',
    senderRole: (ref.source && ref.source.senderRole) || 'user',
    senderName: (ref.source && ref.source.senderName) || '',
    attachments: Array.isArray(ref.content && ref.content.attachments) ? ref.content.attachments : [],
    itemIndex: ref.content ? ref.content.itemIndex : undefined,
    itemTotal: ref.content ? ref.content.itemTotal : undefined,
  };
}

// reference.js — Global Reference 统一引用模型（规格 20260825_global-reference-spec v1.1）
//
// 所有入口（文件浏览器右键 / 拖拽到输入框 / Canvas 选择 / 任务栏打回 / 标签页拖拽）
// 最后都调用 makeReference() 产出统一的 Reference 对象（§2.1），渲染统一走
// renderRefBlock(ref, {mode})。不允许多个入口各自拼不同结构（§2.4 统一性保障点）。
//
// Reference（JS 态，存入 pendingAttachments）：
//   { type:'ref', refType:'file'|'document'|'task'|'html-element', id,
//     source:{kind,path?,url?,fileName?,title?,taskId?,sessionId?},
//     anchor:{kind,pageStart?,pageEnd?,lineStart?,lineEnd?,sheet?,cellRange?,text?,selector?},
//     meta:{mimeType?,sizeBytes?,icon,typeLabel}, display:{label,preview,pageBadge?,lineBadge?} }
//
// P0 范围（v1.1）：输入框引用块定高/截断/可展开（A1-A6）+ 各入口产出引用（B 组）+ 拉到根目录（E 组）。
// 消息内引用卡片（mode='message'）+ 点击跳转 = P1/P2，本模块预留 renderRefBlock mode 分支。
import { t } from './i18n.js';
import { showDocumentTermCard } from './documentTermCard.js';
// 循环门禁修复批（2026-11-04）：**模型层**（makeReference + 它用到的纯补全助手）
// 抽到叶子模块 refModel.js。缘由：msgQuote.js 只需要「行描述符 → Reference 对象」
// 这一步模型转换，静态 import 本模块却会把整条**渲染**链（本文件 → popArtifacts
// 的媒体卡）一起拉进来，于是 popArtifacts → msgQuote → reference → popArtifacts
// 成环（scripts/check-circular.mjs SCC[3]）。模型落叶子后 msgQuote 直取模型，
// 环上最薄的边（1 符号 / 1 调用点）消失；本轮不动下面两条**有意**的边。
// 兼容面：makeReference / extLabel / pageBadge 在本模块同名 re-export（既有 9 个
// import 点逐字不动，同 fileIcon.js 抽叶时的惯例）。
import { makeReference, extLabel, pageBadge } from './refModel.js';
export { makeReference } from './refModel.js';
// imageref-media（2026-10-09 修复批）：图片文件引用的消息内面 = Pop 媒体预览卡
// （构建器单点在 popArtifacts —— 本模块**向** popArtifacts 单向借用，反方向由
// refModel.js 抽叶后的 msgQuote 腿兜住：popArtifacts 只经 msgQuote 触达模型）。
// 卡片语言统一批（2026-10-26）：文件引用卡再复用同一 `fileIconStyle` 图标规范
// （扩展名色块档 = 引擎侧 .file-card 同源，零第二套图标语言）。
import { buildImageRefMediaFace, fileIconStyle } from './popArtifacts.js';
// 引用牌图标化批（2026-11-02 11:00 作者令）：composer 引用 chip 的类型图标与气泡
// 引用块同源（quoteLogo.js 单点 —— 图片=真缩略 / 文档=.fc-icon 原图标 / 文字=
// 引用入口图标 QUOTE_ENTRY_ICON，即 footer 引用键那颗，作者令 2026-11-02 12:58）。
import { quoteTypeIcon, quoteShortNameOf } from './quoteLogo.js';

/** Legacy taskRef → normalized Reference (向后兼容，§2.4 不推翻 taskRef). */
export function normalizeTaskRef(att) {
  return makeReference({
    refType: 'task',
    source: { kind: 'task', taskId: att.taskId, sessionId: att.sessionId, title: att.subject || '' },
  });
}

/** Short at-mention text for aria/display (§2.3 ④). */
export function refMentionText(ref) {
  if (!ref) return '';
  if (ref.refType === 'task') return ref.display?.label || `@#${ref.source?.taskId ?? ''}`;
  if (ref.refType === 'file') return `@${ref.source?.path || ref.source?.fileName || ''}`;
  if (ref.refType === 'document') {
    const p = ref.anchor?.kind === 'page' && ref.anchor.pageStart != null
      ? `:p${ref.anchor.pageStart}${ref.anchor.pageEnd && ref.anchor.pageEnd !== ref.anchor.pageStart ? `-${ref.anchor.pageEnd}` : ''}` : '';
    return `@${ref.source?.path || ''}${p}`;
  }
  if (ref.refType === 'html-element') return `@${ref.source?.url || ''}#${ref.anchor?.selector || ''}`;
  if (ref.refType === 'friend-message') return `@${ref.display?.label || ref.source?.friendName || ''}`;
  return '';
}

// ── renderRefBlock ──────────────────────────────────────────────────────
//
// mode='input': 输入框引用块（§3.5 基座 + 2026-09-02 精简裁定）——单行紧凑 chip
//   高 28px、面上仅 ①refType 类型图标 ②短标题；元信息（完整路径/页码/来源域）
//   入 hover title tooltip 与点击 .expanded 预览行（A4 机制沿用，≤72px 可滚动）、
//   ✕ 本地删除。A1 定高/A2 截断/A4 展开断言基准不变。
// mode='message': 消息内引用块（§4）——块级卡片（P1/P2 消息流使用，本次未动）。

const EXPAND_SVG = '<svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" style="flex-shrink:0"><polyline points="15 3 21 3 21 9"/><polyline points="9 21 3 21 3 15"/><line x1="21" y1="3" x2="14" y2="10"/><line x1="3" y1="21" x2="10" y2="14"/></svg>';
const COLLAPSE_SVG = '<svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" style="flex-shrink:0"><polyline points="4 14 10 14 10 20"/><polyline points="20 10 14 10 14 4"/><line x1="14" y1="10" x2="21" y2="3"/><line x1="3" y1="21" x2="10" y2="14"/></svg>';
/**
 * 待清件「清除」键的 ✕ 字形 —— **单一定义，两处消费者**（作者 2026-09-19 04:22
 * 修正②「主窗口图片附件的 ❌ 有未居中感 ⇒ 修复居中」/ 修正①「引用条的 ❌ 参考主窗口」）：
 *   ① 主窗口图片/文件附件 ❌（`js/chat.js::renderAttachmentPreview`，class `.att-remove`）
 *   ② 好友消息面引用条 ❌（`renderFriendInputRef` 的 `closeStyle:'disc'`，class `.fm-quote-remove`）
 * 🔴 为什么是 SVG 而不是文本 `x`：flex 居中的对象是**行盒**，行盒含上侧 ascent 空白
 * ⇒ x-height 字形的视觉中心落在圆盘中线**下方**（作者截图 2 实测 1px / 16px = 6.25%）；
 * 本字形在 16×16 viewBox 内由两条对角线构成，形心 =(8,8) 恒等于 viewBox 中心，
 * `display:block` + flex 居中 ⇒ 图形中心 == 圆盘中心（逐值相等，容差 0）。
 */
export const CLOSE_X_SVG = '<svg viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" aria-hidden="true" focusable="false"><path d="M4 4 L12 12"/><path d="M12 4 L4 12"/></svg>';
const ICONS = {
  file: '<svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" style="flex-shrink:0"><path d="M13 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V9z"/><polyline points="13 2 13 9 20 9"/></svg>',
  document: '<svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" style="flex-shrink:0"><rect x="3" y="3" width="18" height="18" rx="2"/><line x1="3" y1="9" x2="21" y2="9"/><line x1="9" y1="21" x2="9" y2="9"/></svg>',
  clipboard: '<svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" style="flex-shrink:0"><rect x="8" y="2" width="8" height="4" rx="1" ry="1"/><path d="M16 4h2a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2h2"/><path d="M12 11h4"/><path d="M12 16h4"/><path d="M8 11h.01"/><path d="M8 16h.01"/></svg>',
  code: '<svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" style="flex-shrink:0"><polyline points="16 18 22 12 16 6"/><polyline points="8 6 2 12 8 18"/></svg>',
  table: '<svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" style="flex-shrink:0"><rect x="3" y="3" width="18" height="18" rx="2"/><line x1="3" y1="9" x2="21" y2="9"/><line x1="3" y1="15" x2="21" y2="15"/><line x1="12" y1="3" x2="12" y2="21"/></svg>',
  'message-circle': '<svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" style="flex-shrink:0"><path d="M21 11.5a8.38 8.38 0 0 1-.9 3.8 8.5 8.5 0 0 1-7.6 4.7 8.38 8.38 0 0 1-3.8-.9L3 21l1.9-5.7a8.38 8.38 0 0 1-.9-3.8 8.5 8.5 0 0 1 4.7-7.6 8.38 8.38 0 0 1 3.8-.9h.5a8.48 8.48 0 0 1 8 8v.5z"/></svg>',
};
function refIcon(ref) {
  return ICONS[ref.meta?.icon] || ICONS.file;
}

/** Truncate a preview to N chars (意见预览只取输入框首行 + 截断在 ~180). */
function truncate(str, n) {
  const s = String(str || '');
  return s.length > n ? s.slice(0, n) + '…' : s;
}

/**
 * Build the reference block element.
 * @param {Object} ref  unified Reference
 * @param {{mode?:'input'|'message', closeStyle?:'glyph'|'disc', compact?:boolean,
 *          order?:number, onOpen?:(ref:Object)=>boolean}} [opts]
 *   closeStyle（input 模式）：`'glyph'`（缺省）= 既有 18×18 透明描边 ✕（主窗口输入框
 *   引用卡 `.att-ref-remove`）；`'disc'` = 主窗口同类的**实心圆白 ✕** 清除键
 *   （`.fm-quote-remove`，形态与主窗口图片/文件附件 ❌ 同款 —— 作者 2026-09-19
 *   04:22 修正①：「引用条的 ❌ 与样式一律参考主窗口既有模式」）。
 *   compact（多选引用批 2026-11-22）：选中 ≥2 条引用 ⇒ 引用牌自动缩成**图标 chip**
 *   （`.att-ref.is-compact`：类型图标 + 顺序号 + 定长短名 + ✕）；单条仍走整卡预览
 *   （本分支不参与，逐字现状）。order = 选择顺序（1 基，卡片顺序 = 选择顺序）。
 *   onOpen = 点牌回看原消息的落点（返回 false ⇒ 目标不在页内 ⇒ 就地展开摘要降级）。
 * @param {(ref:Object)=>void} [onRemove]  input-mode local remove callback
 * @returns {HTMLElement}
 */
export function renderRefBlock(ref, { mode = 'input', closeStyle = 'glyph', compact = false, order = 0, onOpen = null } = {}, onRemove) {
  if (mode === 'message') return renderMessageRef(ref);
  // 紧凑档只对 chat-message 引用牌开放（其余 refType 的多卡形态不存在：一条回复
  // 只引消息），且必须显式请求 —— 默认面零变化。
  if (compact && ref && ref.refType === 'chat-message') return renderCompactInputRef(ref, onRemove, order, onOpen);
  return renderInputRef(ref, onRemove, closeStyle);
}

/**
 * 紧凑引用牌（多选引用批 2026-11-22 作者令「多选的就自动缩小为小图标」）。
 *
 * 形态：类型图标（与整卡/气泡引用块同源 `quoteTypeIcon`）+ 顺序号 + **定长短名**
 * （`quoteShortNameOf`，零 CSS 省略）+ ✕（实心圆白，与整卡同族）。几何由
 * css/input.css 的 `.att-ref.is-compact` 段承担（28px 档，与附件 chip 同条带）。
 *
 * 交互：牌面是可焦点真钮（回看原消息 = `onOpen`，与气泡引用块点击同一条定位/高亮
 * 实现）；目标不在页内（跨刷新/被裁剪）⇒ 就地展开 `.expanded` 显示快照摘要
 * （整卡同款降级，零死点击）。✕ 单独一枚钮（牌面内不嵌套交互面，读屏语义干净）。
 */
/** CLOSE_X_SVG → 真实节点（零 `innerHTML` 写面）。缘由：component-contract 的
 *  bare-innerhtml 台账只许减不许增，故新面用 Range 解析同一份字符串常量。 */
function closeXNode() {
  const frag = document.createRange().createContextualFragment(CLOSE_X_SVG);
  return frag.firstElementChild;
}

function renderCompactInputRef(ref, onRemove, order, onOpen) {
  const wrap = document.createElement('div');
  wrap.className = 'att-ref is-compact';
  wrap.dataset.refType = 'chat-message';
  wrap.dataset.refId = ref.id || '';
  wrap.dataset.quoteOrder = String(order || '');
  wrap.setAttribute('role', 'group');
  wrap.setAttribute('aria-expanded', 'false');
  const shortName = quoteShortNameOf({
    senderRole: ref.source?.senderRole,
    senderName: ref.source?.senderName,
    excerpt: ref.content?.excerpt,
    attachments: ref.content?.attachments,
    itemIndex: ref.content?.itemIndex,
  });
  const orderText = order > 0 ? String(order) : '';
  const chipLabel = [orderText, shortName].filter(Boolean).join(' · ');
  const summary = String(ref.content?.excerpt || ref.display?.preview || '');
  const senderName = String(ref.source?.senderName || shortName);
  wrap.setAttribute('aria-label', t('chat.quoteCompactAria', { n: orderText || '1', name: shortName }));
  wrap.title = [senderName && summary ? `${senderName}：${summary}` : summary, t('chat.quoteCompactHint')]
    .filter(Boolean).join('\n');

  // 牌面钮（回看原消息）：图标 + 顺序号 + 短名，全部定长内容 —— 不设 max-width，
  // 因此不需要任何 text-overflow（见 input.css 同名段）。
  const open = document.createElement('button');
  open.type = 'button';
  open.className = 'att-ref-open';
  open.setAttribute('aria-label', t('chat.quoteCompactOpenAria', { name: shortName }));
  open.title = wrap.title;
  const icon = document.createElement('span');
  icon.className = 'att-ref-icon';
  icon.appendChild(quoteTypeIcon(
    { excerpt: ref.content?.excerpt, attachments: ref.content?.attachments, itemIndex: ref.content?.itemIndex },
    'att-ref-qicon',
  ));
  icon.setAttribute('aria-hidden', 'true');
  const label = document.createElement('span');
  label.className = 'att-ref-label';
  label.textContent = chipLabel;
  open.append(icon, label);
  open.addEventListener('click', (e) => {
    e.stopPropagation();
    const jumped = typeof onOpen === 'function' ? onOpen(ref) : false;
    if (jumped) return;
    // 降级：目标行不在页内 ⇒ 就地展开快照摘要（整卡 .expanded 同款语义）。
    const expanded = wrap.classList.toggle('expanded');
    wrap.setAttribute('aria-expanded', String(expanded));
  });

  // 快照摘要行（面上不占位；仅降级展开时可见 —— 与整卡 `.att-ref-meta` 同族）。
  const meta = document.createElement('span');
  meta.className = 'att-ref-meta';
  meta.textContent = summary;

  const rm = document.createElement('button');
  rm.type = 'button';
  rm.className = 'att-ref-remove fm-quote-remove';
  // ✕ 字形走 closeXNode()（同一份 CLOSE_X_SVG，零第二份拷贝）；**不用 innerHTML**
  // —— component-contract 的 bare-innerhtml 台账只许减不许增，本文件既有 8 处已
  // 入账，新面（紧凑牌）不再加第 9 处。
  rm.appendChild(closeXNode());
  rm.title = t('ref.remove');
  rm.setAttribute('aria-label', t('ref.remove'));
  rm.addEventListener('click', (e) => { e.stopPropagation(); onRemove?.(ref); });

  wrap.append(open, meta, rm);
  return wrap;
}

// ── 转发消息引用块「按需展开按钮」溢出重估基座（作者 2026-09-14 17:26 裁定）──
// 触发面 = ①元素尺寸变化（ResizeObserver：视口/容器宽度变 ⇒ 折行数变；图片
// 加载完成、字体度量变最终也落成盒尺寸变化）②窗口 resize（无 RO 环境兜底）
// ③主题切换（亮⇄暗，字体度量可能变）④字体就绪（document.fonts.ready）。
// 形态 = 模块级单例监听器 + wrap→sync 登记表；遍历时按 isConnected 剔除（chip
// 随 renderAttachmentPreview 的 innerHTML 重渲被丢弃 ⇒ 零监听/观测泄漏）。
// 重估 idempotent、无副作用、不打断用户已展开态（sync 在 .expanded 早退）。
const fmOverflowSyncs = new Map();
let fmOverflowArmed = false;
let fmOverflowRO = null;
function runFmOverflowSyncs() {
  for (const [el, sync] of fmOverflowSyncs) {
    if (!el.isConnected) {
      fmOverflowSyncs.delete(el);
      if (fmOverflowRO) fmOverflowRO.unobserve(el);
      continue;
    }
    sync();
  }
}
/** Arm the shared listeners (once) + observe this chip, then measure on the
 *  next frame — renderRefBlock returns a DETACHED node (the caller appends it),
 *  so the first real measurement must wait for layout. */
function armFmOverflow(wrap, sync) {
  // 天然回收点：新建 chip 时旧 chip 已被 renderAttachmentPreview 的整条重渲剔除，
  // 在此清掉已断连条目（新 wrap 此刻尚未挂载，故先 prune 后 set）。
  for (const [el] of fmOverflowSyncs) {
    if (!el.isConnected) {
      fmOverflowSyncs.delete(el);
      if (fmOverflowRO) fmOverflowRO.unobserve(el);
    }
  }
  fmOverflowSyncs.set(wrap, sync);
  if (!fmOverflowArmed) {
    fmOverflowArmed = true;
    window.addEventListener('resize', runFmOverflowSyncs, { passive: true });
    window.matchMedia?.('(prefers-color-scheme: dark)')?.addEventListener?.('change', runFmOverflowSyncs);
    document.fonts?.ready?.then?.(() => runFmOverflowSyncs());
    if (typeof ResizeObserver !== 'undefined') fmOverflowRO = new ResizeObserver(runFmOverflowSyncs);
  }
  if (fmOverflowRO) fmOverflowRO.observe(wrap);
  if (typeof requestAnimationFrame === 'function') {
    // 第 1 帧（挂载同 task 完成时 → 移除发生在首绘之前，无可见闪烁），
    // 第 2 帧兜底（挂载晚于本调用 / 字体与图片度量尚未落定）。
    requestAnimationFrame(() => { runFmOverflowSyncs(); requestAnimationFrame(runFmOverflowSyncs); });
  }
}

// #290 A2A (addendum §3.4): friend-message input block - etched surface,
// header 来自 {好友名} + date corner badge, body 2-line clamp, expand = full
// text (content.fullText). Fixed footprint, zero new color tokens.
function renderFriendInputRef(ref, onRemove, closeStyle) {
  const wrap = document.createElement('div');
  wrap.className = 'att-ref att-ref-fm';
  wrap.dataset.refType = 'friend-message';
  wrap.dataset.refId = ref.id || '';
  const label = ref.display?.label || '';
  wrap.setAttribute('role', 'group');
  wrap.setAttribute('aria-label', `${t('ref.cardAria')}: ${label}`.trim());
  wrap.title = label;

  const icon = document.createElement('span');
  icon.className = 'att-ref-icon';
  icon.innerHTML = refIcon(ref);
  icon.setAttribute('aria-hidden', 'true');

  const body = document.createElement('span');
  body.className = 'att-ref-fm-body';
  const head = document.createElement('span');
  head.className = 'att-ref-fm-head';
  const nameEl = document.createElement('span');
  nameEl.className = 'att-ref-fm-name';
  nameEl.textContent = label;
  head.appendChild(nameEl);
  const date = ref.meta?.date || ref.display?.pageBadge || '';
  if (date) {
    const dateEl = document.createElement('span');
    dateEl.className = 'att-ref-fm-date';
    dateEl.textContent = date;
    head.appendChild(dateEl);
  }
  const text = document.createElement('span');
  text.className = 'att-ref-fm-text';
  text.textContent = ref.content?.preview || ref.display?.preview || '';
  body.append(head, text);

  const expand = document.createElement('button');
  expand.type = 'button';
  expand.className = 'att-ref-expand';
  expand.title = t('ref.expand');
  expand.setAttribute('aria-expanded', 'false');
  expand.innerHTML = EXPAND_SVG;
  const fullText = ref.content?.fullText || '';

  // 作者 2026-09-14 17:26 裁定（逐字）：「从好友那转发过来的消息，有一个展开的
  // 按钮，这个按钮应该是转发的消息过多的时候才显示呀，位置够的情况下不用显示。」
  // ⇒ 本控件**按需渲染**：装得下 ⇒ DOM 里不出现该控件（不是 visibility/透明占位）。
  // 判定一律**渲染后实测**：折叠态（-webkit-line-clamp:2）下 text.scrollHeight
  // 超过 clientHeight 才算溢出——长英文与 CJK 宽度不同，**禁按字符数静态猜测**。
  // 初始**不渲染**该控件（连一帧的预置都不给：DOM 里从头就不出现），溢出时由
  // 落定后的实测补上；未挂载/未布局（clientHeight=0）⇒ 不可判（null）⇒ 维持现状
  // 等下一次重估（rAF/RO/字体就绪/resize/主题）。
  const overflowNow = () => {
    if (!text.isConnected) return null;
    const clampedH = text.clientHeight;
    if (clampedH <= 0) return null;
    return text.scrollHeight > clampedH + 1;   // +1 = 亚像素取整容差
  };
  const syncExpand = () => {
    // 已展开 ⇒ 按钮语义 = 收起，必须在场（展开态 line-clamp 解除，实测恒不溢出）。
    if (wrap.classList.contains('expanded')) return;
    const over = overflowNow();
    if (over === true) {
      if (!expand.isConnected) wrap.insertBefore(expand, rm);  // 次序：body 之后、✕ 之前
    } else if (over === false && expand.isConnected) {
      expand.remove();
    }
  };
  const toggle = (e) => {
    if (e && e.stopPropagation) e.stopPropagation();
    const expanded = wrap.classList.toggle('expanded');
    expand.setAttribute('aria-expanded', String(expanded));
    expand.title = expanded ? t('ref.collapse') : t('ref.expand');
    expand.innerHTML = expanded ? COLLAPSE_SVG : EXPAND_SVG;
    text.textContent = expanded ? fullText : (ref.content?.preview || '');
    if (!expanded) syncExpand();               // 收起后重估（幂等）
  };
  expand.addEventListener('click', toggle);
  wrap.addEventListener('click', toggle);

  const rm = document.createElement('button');
  rm.type = 'button';
  // 修正①（2026-09-19 04:22）：引用条的 ❌ 取**主窗口同类别**形态 —— 实心圆白 ✕
  //（`.fm-quote-remove`，与主窗口附件 ❌ 同一个声明块、同一个 `CLOSE_X_SVG` 字形）。
  // 缺省仍是本卡原来的 18px 描边 ✕（`.att-ref-remove`）⇒ 主窗口输入框零变化。
  const disc = closeStyle === 'disc';
  rm.className = disc ? 'att-ref-remove fm-quote-remove' : 'att-ref-remove';
  if (disc) rm.innerHTML = CLOSE_X_SVG; else rm.textContent = '×';
  rm.title = t('ref.remove');
  rm.setAttribute('aria-label', t('ref.remove'));
  rm.addEventListener('click', (e) => { e.stopPropagation(); onRemove?.(ref); });

  wrap.append(icon, body, rm);                 // expand 按需挂载（溢出才进 DOM）
  armFmOverflow(wrap, syncExpand);             // 渲染后实测 ⇒ 落定即补正显隐
  return wrap;
}

// ── Input chip: 单行紧凑形态（2026-09-02 作者裁定「引用块标签精简——单行紧凑
// chip、只留类型图标+短标题」）──
// 面上只留两类元素：① refType 类型图标（来源类型一眼区分）②短标题（独有标识）。
// 完整路径/页码范围/来源域名等元信息不在面上占位——hover 走原生 title tooltip
// （全 app 既有 tooltip 机制），点击整卡走 .expanded 预览行（A4 机制沿用）。
// 纯展示层：不改 makeReference 产出（display.label 等是 wire 载荷），不碰入口链路。

/** refType → 面上类型图标。按来源类型字段映射（裁定①），非扩展名子型——
 *  扩展名子型图标只用于消息内卡片（refIcon / meta.icon 路径，不在本裁定域）。 */
function chipIcon(ref) {
  switch (ref.refType) {
    case 'document': return ICONS.document;
    case 'task': return ICONS.clipboard;
    case 'html-element': return ICONS.code;
    case 'chat-message': return ICONS['message-circle'];
    default: return ICONS.file;
  }
}

/** 短标题（裁定②）——各来源类型的独有标识，全部取自既有字段：
 *  file→文件名；document(Canvas/页面)→标题；task→任务号+标题；
 *  html-element→页面标题+<tag>（完整 selector 留给 tooltip/预览行）。 */
function chipTitle(ref) {
  const src = ref.source || {};
  const anc = ref.anchor || {};
  if (ref.refType === 'task') {
    return (`#${src.taskId != null ? src.taskId : ''} ${src.title || ''}`).trim();
  }
  if (ref.refType === 'chat-message') {
    // quote-message 一期（spec §7.2）：标题 = 被引用者名：摘要（ref.display.label
    // 已按此拼好；名字空时回落纯摘要）。
    return ref.display?.label || ref.content?.excerpt || '';
  }
  if (ref.refType === 'document') {
    return src.title || src.fileName || (src.path || '').split('/').pop() || '';
  }
  if (ref.refType === 'html-element') {
    const tag = anc.tag ? `<${anc.tag}>` : '';
    return [src.title, tag].filter(Boolean).join(' ') || anc.selector || src.url || '';
  }
  return src.fileName || (src.path || '').split('/').pop() || src.title || '';
}

/** 元信息多行文本（tooltip 与 .expanded 预览行共用，信息不丢）：
 *  行1 = 类型标签 · 完整来源（路径/URL/session）；行2 = 锚点（页码/行号/selector）；
 *  行3 = 内容摘要（display.preview，有则附）。 */
function chipMetaText(ref) {
  const src = ref.source || {};
  const anc = ref.anchor || {};
  let sourceLine = '';
  if (ref.refType === 'task') sourceLine = src.sessionId ? `session ${src.sessionId}` : '';
  else sourceLine = src.url || src.path || '';
  const lines = [[ref.meta?.typeLabel || '', sourceLine].filter(Boolean).join(' · ')];
  if (ref.refType === 'html-element' && anc.selector) lines.push(`selector: ${anc.selector}`);
  else if (pageBadge(anc)) lines.push(pageBadge(anc));
  if (ref.display?.preview) lines.push(ref.display.preview);
  return lines.filter(Boolean).join('\n');
}

function renderInputRef(ref, onRemove, closeStyle) {
  if (ref.refType === 'friend-message') return renderFriendInputRef(ref, onRemove, closeStyle);
  const wrap = document.createElement('div');
  wrap.className = 'att-ref';
  wrap.dataset.refType = ref.refType || '';
  wrap.dataset.refId = ref.id || '';
  const title = chipTitle(ref);
  const mention = refMentionText(ref);
  wrap.setAttribute('role', 'group');
  wrap.setAttribute('aria-label', `${t('ref.cardAria')}: ${title || mention}`.trim());
  wrap.setAttribute('aria-expanded', 'false');
  // Hover tooltip：完整元信息（原生 title，多行；WebKit/Chromium 逐行渲染）。
  wrap.title = [title, chipMetaText(ref)].filter(Boolean).join('\n');

  const icon = document.createElement('span');
  icon.className = 'att-ref-icon';
  // 引用牌图标化批（2026-11-02 11:00 作者令「引用的消息也需要图标化，文档和图片就是
  // 原图标和图片缩略，文字的话就单独设计一个logo」）：**只**改 chat-message 分支
  // （主聊天区的消息引用），其余 refType（file/document/task/html-element）逐字节
  // 原样 —— 本函数同时被历史 ref 卡（persistence.js mode:'message' 不走这里，但
  // 好友窗 pending chip messages.js:4020 走）消费，作用域收在 refType 上。
  // 类型判定/字形/缩略取用同一单点 = quoteLogo.quoteTypeIcon（与气泡引用块同源）。
  if (ref.refType === 'chat-message') {
    icon.appendChild(quoteTypeIcon(
      { excerpt: ref.content?.excerpt, attachments: ref.content?.attachments, itemIndex: ref.content?.itemIndex },
      'att-ref-qicon',
    ));
  } else {
    icon.innerHTML = chipIcon(ref);
  }
  icon.setAttribute('aria-hidden', 'true');

  const body = document.createElement('span');
  body.className = 'att-ref-body';
  const labelEl = document.createElement('span');
  labelEl.className = 'att-ref-label';
  labelEl.textContent = title;
  // 元信息行：面上 display:none（裁定②），仅 .expanded 点击预览态展开。
  const metaEl = document.createElement('span');
  metaEl.className = 'att-ref-meta';
  metaEl.textContent = chipMetaText(ref);
  body.append(labelEl, metaEl);

  // ✕ remove (local-only, C16 draft semantics — 删除链路不变)
  const rm = document.createElement('button');
  rm.type = 'button';
  const disc = closeStyle === 'disc';
  rm.className = disc ? 'att-ref-remove fm-quote-remove' : 'att-ref-remove';
  if (disc) rm.innerHTML = CLOSE_X_SVG; else rm.textContent = '×';
  rm.title = t('ref.remove');
  rm.setAttribute('aria-label', t('ref.remove'));
  rm.addEventListener('click', (e) => { e.stopPropagation(); onRemove?.(ref); });

  // 点击整卡 = 预览开合（A4 沿用；⤢ 按钮按裁定离开标签面，cursor:pointer 提示可点）。
  wrap.addEventListener('click', () => {
    const expanded = wrap.classList.toggle('expanded');
    wrap.setAttribute('aria-expanded', String(expanded));
  });

  wrap.append(icon, body, rm);
  return wrap;
}

/** Normalized short source line for the message-card aux row (path dir / url).
 *  20260903 filename-dup fix: file/document aux line = DIRECTORY only — the
 *  card head already shows the basename once, so the last segment (the file
 *  name itself) is dropped here. Deep dirs abbreviate the head (「…/」+ last 2
 *  segments, 「目录深时首部省略」); a bare-filename path yields '' (no dir info). */
function normalizeSource(ref) {
  if (ref.refType === 'task') return '';
  if (ref.refType === 'friend-message') return ref.source?.friendNeblinkId || '';
  if (ref.source?.url) return ref.source.url;
  const p = ref.source?.path || '';
  if (!p) return '';
  const segs = p.split('/').filter(Boolean);
  segs.pop();                       // drop the basename — title row owns it
  if (!segs.length) return '';
  return segs.length > 2 ? '…/' + segs.slice(-2).join('/') : segs.join('/');
}

/** Parse the backend return-injection text "[打回任务 #id: title]" (the only
 *  marker the return flow emits today). Non-matching text returns null and the
 *  caller falls through to normal markdown - zero behavior change elsewhere.
 *  NOTE sync point: the wording is owned by Task.returnInjectionBlock; if the
 *  payload wording changes this recognizer must follow (Manager 2026-08-27:
 *  frontend/backend changes are independent - worst case here is graceful
 *  fallback to the old full-text rendering, never broken UI).
 * @param {string} text
 * @returns {{taskId: string, title: string, opinion: string}|null}
 */
export function parseTaskReturnText(text) {
  const trimmed = String(text || '').trim();
  const lines = trimmed.split(/\r?\n/);
  const head = /^\[打回任务\s*#([^:\]]+):\s*([^\]]*)\]\s*$/.exec(lines[0] || '');
  if (!head) return null;
  let opinion = '';
  for (const l of lines.slice(1)) {
    if (/^用户意见/.test(l)) { opinion = l.replace(/^用户意见\s*[:：]\s*/, ''); break; }
  }
  if (opinion === '（未附意见）') opinion = '';
  return { taskId: head[1].trim(), title: (head[2] || '').trim(), opinion };
}

/** The converged one-line task-return node (2026-08-27 user ruling "只显任务名"):
 *  clipboard glyph + "#<id> <title>" (+ optional one-line-truncated opinion).
 *  Description/output NEVER render here regardless of what the payload hints.
 * @param {{taskId?: string|number|null, title?: string, opinion?: string}} p
 * @returns {HTMLElement}
 */
export function buildTaskRefLine(p) {
  const line = document.createElement('div');
  line.className = 'att-task-line';
  line.dataset.refType = 'task';
  const id = p.taskId != null ? String(p.taskId) : '';
  const title = String(p.title || '');
  const ariaLabel = (`#${id} ${title}`).trim();
  line.setAttribute('role', 'group');
  line.setAttribute('aria-label', ariaLabel);
  const icon = document.createElement('span');
  icon.className = 'att-task-line-icon';
  icon.innerHTML = ICONS.clipboard;
  icon.setAttribute('aria-hidden', 'true');
  const name = document.createElement('span');
  name.className = 'att-task-line-name';
  name.textContent = ariaLabel || `#${id}`;
  line.append(icon, name);
  const opinion = String(p.opinion || '').split('\n')[0].trim();
  if (opinion && opinion !== '（未附意见）') {
    const op = document.createElement('span');
    op.className = 'att-task-line-opinion';
    op.textContent = truncate(opinion, 80);
    line.appendChild(op);
  }
  return line;
}

/** imageref-media（2026-10-09 修复批）：文件引用是否指向图片。与 extLabel 同一
 *  扩展名族（png/jpg/jpeg/gif/webp，reference.js:40 的 IMG 表）＋ mimeType 图像
 *  前缀兜底（canvas 标签引用可能 fileName 无扩展名）。非图片恒 false——文件卡
 *  只给非图片。 */
function isImageFileRef(ref) {
  if (!ref || ref.refType !== 'file') return false;
  const src = ref.source || {};
  const base = String(src.fileName || src.title || src.path || '').split('/').pop() || '';
  const dot = base.lastIndexOf('.');
  const ext = dot > 0 ? base.slice(dot + 1).toLowerCase() : '';
  if (['png', 'jpg', 'jpeg', 'gif', 'webp'].includes(ext)) return true;
  const mime = String(src.mimeType || (ref.meta && ref.meta.mimeType) || '');
  return mime.startsWith('image/');
}

/** 文件/文档引用的统一卡面（卡片语言统一批 2026-10-26）：与引擎侧 `.file-card`
 *  同一套语言 —— `.fc-icon` 扩展名色块（fileIconStyle 单源）+ `.fc-name` 文件名
 *  + `.fc-sub` 来源路径（截断）；preview 有值时保留两行钳制内容行（引用特有的
 *  信息面，字号随 sub 档）。类型角标（badge）并入图标块（MD/PDF… 即角标），
 *  不再双写。dataset/click 语义与旧卡逐字节一致（jumpRef 单点）。 */
function buildFileLangRefCard(ref) {
  const src = ref.source || {};
  const path = typeof src.path === 'string' ? src.path : '';
  const rawName = String(src.fileName || src.title || ref.display?.label || '');
  const base = rawName.split('/').pop() || rawName;
  const card = document.createElement('div');
  card.className = 'att-ref-card file-lang';
  // 色族 = 扩展名档（fileIconStyle 单源；基础名无扩展名时回落路径扩展名 ——
  // canvas 页签引用的 fileName 常无点，路径才是真类型源，makeReference typeLabel
  // 同一口径）；块内文本 = meta.typeLabel（旧 badge 的同一来源，零信息丢失 ——
  // SCALA/MD 全称保留），无 typeLabel 才回落 fileIconStyle 截断档。
  const extSrc = (() => {
    const d = base.lastIndexOf('.');
    if (d > 0) return base.slice(d + 1);
    const pd = path.lastIndexOf('.');
    return pd > path.lastIndexOf('/') ? path.slice(pd + 1).toLowerCase() : '';
  })();
  const icon = fileIconStyle(extSrc);
  const iconEl = document.createElement('div');
  iconEl.className = 'fc-icon ' + icon.cls;
  iconEl.textContent = ref.meta?.typeLabel || icon.label;
  const meta = document.createElement('div');
  meta.className = 'fc-meta';
  const name = document.createElement('div');
  name.className = 'fc-name';
  name.textContent = ref.display?.label || base || path.split('/').pop() || path || extLabel(base, src.mimeType);
  meta.appendChild(name);
  const sourceLine = normalizeSource(ref);
  if (sourceLine) {               // 20260903 fix: bare-filename path → no dir, no aux row
    const sub = document.createElement('div');
    sub.className = 'fc-sub att-ref-card-source';
    sub.textContent = sourceLine;
    meta.appendChild(sub);
  }
  const body = document.createElement('div');
  body.className = 'att-ref-card-content';
  body.textContent = ref.display?.preview || '';
  if (body.textContent) meta.appendChild(body);
  card.append(iconEl, meta);
  card.dataset.refType = ref.refType || '';
  card.dataset.refPath = path;
  const isDocument = ref.refType === 'document' || /\.(md|markdown|html?)$/i.test(path);
  const activate = () => {
    if (isDocument && path) showDocumentTermCard(card, { path, title: name.textContent, openDocument: resolvedPath => jumpRef(ref, resolvedPath) });
    else jumpRef(ref);
  };
  card.tabIndex = 0;
  card.setAttribute('role', 'button');
  card.addEventListener('click', activate);
  card.addEventListener('keydown', e => {
    if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); activate(); }
  });
  return card;
}

function renderMessageRef(ref) {
  // imageref-media（2026-10-09 修复批）：图片文件引用先分流到 Pop 媒体预览卡
  // （buildImageRefMediaFace）。修复前本函数对 refType:'file' 一律出 att-ref-card
  // ——图片被渲染成「文档图标 + 文件名 + IMG 角标 + 截断路径」的文件卡（用户
  // 2026-10-09 07:45 现场报；文档图标本身 = meta.icon 'image' 无对应 ICONS 条目、
  // refIcon 回落 ICONS.file 的二次症状）。引用身份以 dataset 留在媒体卡上
  // （refType/refPath），点击 = openPopArtifact 开 Canvas（与 jumpRef 同族）。
  if (isImageFileRef(ref)) return buildImageRefMediaFace(ref);
  // Task references converge to the one-line form above (same shape as the
  // injected [打回任务] collapse); file/document keep the card.
  if (ref.refType === 'task') {
    return buildTaskRefLine({
      taskId: ref.source?.taskId ?? '',
      title: ref.source?.title || '',
      opinion: '',
    });
  }
  // 卡片语言统一批（作者令 2026-10-26「用户发送的附件卡片与引擎发送的文件卡片
  // 统一为同一套卡片语言」）：file/document 引用走 `.file-lang` 面 —— 图标 =
  // popArtifacts.fileIconStyle 同一扩展名色块档（.fc-icon 单一来源）、字级/圆角/
  // 底色与 .file-card 逐项同规（CSS 单点 .att-ref-card.file-lang）。跳转动作
  // （jumpRef）/dataset 引用身份不变；html-element 引用维持原 svg 图标卡
  // （无扩展名可言，旧面即对的语言）。
  if (ref.refType === 'file' || ref.refType === 'document') {
    return buildFileLangRefCard(ref);
  }
  const card = document.createElement('div');
  card.className = 'att-ref-card';
  const label = ref.display?.label || ref.source?.title || '';
  const head = document.createElement('div');
  head.className = 'att-ref-card-head';
  const icon = document.createElement('span');
  icon.className = 'att-ref-card-icon';
  icon.innerHTML = refIcon(ref);
  head.appendChild(icon);
  const title = document.createElement('span');
  title.className = 'att-ref-card-title';
  title.textContent = label;
  head.appendChild(title);
  const badge = document.createElement('span');
  badge.className = 'att-ref-card-badge';
  badge.textContent = ref.display?.pageBadge || ref.meta?.typeLabel || '';
  head.appendChild(badge);
  card.appendChild(head);
  const sourceLine = normalizeSource(ref);
  if (sourceLine) {               // 20260903 fix: bare-filename path → no dir, no aux row
    const src = document.createElement('div');
    src.className = 'att-ref-card-source';
    src.textContent = sourceLine;
    card.appendChild(src);
  }
  const body = document.createElement('div');
  body.className = 'att-ref-card-content';
  body.textContent = ref.display?.preview || '';
  card.appendChild(body);
  card.dataset.refType = ref.refType || '';
  card.dataset.refPath = ref.source?.path || '';
  // #303 C2/C3: click the message card to jump to the referenced content
  // (open the file/URL, or reveal the task). Canvas is loaded lazily via a
  // dynamic import() — canvas.js statically imports reference.js, so a static
  // import here would create a reference→canvas→reference cycle.
  card.addEventListener('click', () => jumpRef(ref));
  return card;
}

// ── C2/C3 reference jump ─────────────────────────────────────────────────────
// Route by refType. File/document: open in Canvas (empty content + absPath →
// canvas fetches via readFile and picks the right viewer). html-element: open
// the source URL. task: referral records are back-references — no silent
// navigation, but let the task panel reveal it if it owns the id (future); for
// now this is a non-navigating record (the return already happened).
function jumpRef(ref, resolvedPath = '') {
  if (!ref) return;
  const rt = ref.refType;
  const src = ref.source || {};
  const path = resolvedPath || src.path || '';
  if (rt === 'file' || rt === 'document') {
    if (!path) return;
    const title = ref.display?.label || src.title || path.split('/').pop();
    // #303 C3: carry the page anchor through to the pdf viewer (canvas.js
    // stashes it across the readFile round trip; pdf.js scrolls after render).
    const pg = ref.anchor && ref.anchor.pageStart;
    const anchor = Number.isFinite(pg) ? { pageStart: pg } : undefined;
    window.dispatchEvent(new CustomEvent('workspace-open-item', {
      detail: { id: `file:${path}`, title, itemType: '', content: '', absPath: path, path, pinned: false, anchor },
    }));
    return;
  }
  if (rt === 'html-element') {
    // #303 B6: srcdoc previews have no usable URL (about:srcdoc) — open the
    // source file path instead; cross-origin URL pages jump by URL (C5).
    if (path) {
      const title = ref.display?.label || src.title || path.split('/').pop();
      window.dispatchEvent(new CustomEvent('workspace-open-item', {
        detail: { id: `file:${path}`, title, itemType: '', content: '', absPath: path, path, pinned: false },
      }));
      return;
    }
    if (src.url) {
      window.dispatchEvent(new CustomEvent('workspace-open-item', {
        detail: { id: `url:${src.url}`, itemType: 'url', title: ref.display?.label || src.url, url: src.url, pinned: false },
      }));
    }
    return;
  }
  // task / friend-message / unknown → non-navigating record (the friend
  // message content already rides the payload - there is nothing to open).
}

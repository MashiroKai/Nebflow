// chat/msgTokenChip.js — 消息身份牌「带短名引用」chip 的交互与取数腿（msgchip 批，
// 2026-11-01 作者令「消息身份牌系统升级」②③条；渲染面在 utils.js 单管线内）。
//
// 组成：
//   · chip 渲染    = utils.js renderMarkdownWithMath 管线（提取/恢复与 MATHBLOCK 同构；
//                    只认带名形态 {{msg:sid8-seq6|短名}}，旧裸牌现状渲染逐字节不变）
//   · 点击委托    = 本模块文档级单点安装（innerHTML 重建不掉 listener——流式/历史/
//                    重放全渲染腿天然存活）
//   · popover     = 玻璃小窗（.glass 材质；🔴 无 overlay 遮罩层——visual-style 铁律 1，
//                    「点外关闭」走文档级 pointerdown 判定，零背景暗化/模糊）
//   · 原消息取数  = 同会话前端本地查优先（会话消息已在内存：activeView 聊天 DOM 的
//                    真实用户文本行即内存态，按铸牌序号 seq 对位）→ 引擎只读端点
//                    GET /api/sessions/msg-token/{sid8-seq6} 权威回退（跨会话/后台
//                    agent 牌必走）→ 双腿皆 miss = 「原消息不可达」降级（零崩溃面）。
//
// 纪律边界：
//   · 牌号铸造逻辑不动——本模块只**读** chip data-msg-token 上的引擎实铸牌面，绝不
//     自造牌号；sid8 判同会话用的短码 = sessionId SHA-256 前 8 位 hex，与引擎
//     MsgTokenRegistry.shortCode 同一确定性函数的浏览器侧镜像（只作判同，不作铸造）。
//   · 引擎端点是映射权威：本地候选先上屏（同会话零等待），引擎应答落地后覆盖渲染
//     （权威校正——本地 DOM 对位是启发式，附件/去重边角可能错位）。
//   · 取数失败/离线 = 降级注记，不许崩（与「渲染器永不抛」冷启动批同纪律）。

import { t } from '../i18n.js';
import state from '../state.js';
import { authHeaders } from '../flowHelpers.js';
import { formatFooterTime } from '../timeFormat.js';
// 渠道来源注记剥离单点（msgchip-provenance 批 2026-11-01）：牌窗与主会话气泡共用
// 同一实现——判据按位置+结构（渠道前缀有固定位置与固定模板），绝不按关键词。
import { stripChannelProvenance } from '../channelProvenance.js';

// ── 语法面 ──────────────────────────────────────────────────────────────────

/** 完整牌面读取器（与 utils.js RE_MSG_TOKEN_NAMED / 引擎 TokenRegex 同一条规则的
 *  严格形：sid8=8 hex、seq=6 位、短名=除 {} | 换行外 1..40 字、短名可缺席）。 */
const RE_TOKEN_PARSE = /^\{\{msg:([0-9a-f]{8})-(\d{6})(?:\|([^{}\n|]{1,40}))?\}\}$/;

// ── sid8 镜像（判同会话专用，非铸造）────────────────────────────────────────

const sid8Cache = new Map();

/** sessionId → 会话短码8（SHA-256 前 4 字节 hex）。引擎 MsgTokenRegistry.shortCode
 *  的同函数浏览器侧镜像。crypto.subtle 需 secure context——非安全源（LAN http）
 *  静默返回 ''（本地腿自动熄灭，只剩引擎端点腿，功能不降级）。 */
export async function sid8Of(sessionId) {
  if (!sessionId) return '';
  if (sid8Cache.has(sessionId)) return sid8Cache.get(sessionId);
  try {
    if (!crypto || !crypto.subtle) return '';
    const buf = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(String(sessionId)));
    const hex = Array.from(new Uint8Array(buf))
      .slice(0, 4)
      .map(b => b.toString(16).padStart(2, '0'))
      .join('');
    sid8Cache.set(sessionId, hex);
    return hex;
  } catch {
    return '';
  }
}

// ── 同会话本地查（前端内存 = 聊天 DOM 的真实用户文本行）──────────────────────

/**
 * 活动视图聊天 DOM 里的**可铸牌用户行**（引擎铸牌条件的前端对位镜像，按行序 =
 * 铸牌序）：真实用户文本行（排除注入行 .bubble.injected / 定时卡、排除无正文的
 * 纯媒体行、排除带兄弟媒体卡行的文本+附件混合行——引擎对带 blocks 的输入禁铸）。
 * 返回 [{text, ts, row}]。判据读数全部来自既有渲染面（__nfQuoteDesc / data-ts），
 * 不新增渲染契约。
 */
export function localUserMessages() {
  const view = state.getActiveView && state.getActiveView();
  const chat = view && view.dom && view.dom.chat;
  if (!chat || !chat.isConnected) return [];
  const out = [];
  chat.querySelectorAll('.row.user').forEach(row => {
    const bubble = row.querySelector('.bubble.user');
    if (!bubble) return;
    if (bubble.classList.contains('injected') || bubble.classList.contains('schedule-card')) return;
    const desc = /** @type {any} */ (row).__nfQuoteDesc;
    const text = desc && typeof desc.text === 'string' ? desc.text : '';
    if (!text.trim()) return;
    const id = row.dataset.msgId || '';
    if (id && chat.querySelector('.row.user-media[data-msg-id="' + CSS.escape(id) + '"]')) return;
    const badge = row.querySelector('.duration-badge-time');
    const ts = badge && badge.dataset ? Number(badge.dataset.ts) || 0 : 0;
    out.push({ text, ts, row });
  });
  return out;
}

// ── 取数（本地优先 + 引擎权威回退 + 缓存）───────────────────────────────────

/** token → {status:'ok', data} | {status:'miss'} 页内缓存（映射先于回复存在：
 *  busy 入队即铸 ⇒ chip 出现时映射必已在册；miss 可安全缓存）。 */
const resolveCache = new Map();

/** 清缓存（spec 探针专用；生产零调用）。 */
export function resetResolveCacheForTest() {
  resolveCache.clear();
}

/** 引擎只读端点腿：GET /api/sessions/msg-token/{core}（SessionRoutes msgchip 批）。
 *  非 200 / found!==true / 无 text = null（不可达）。 */
export async function lookupEngine(core) {
  try {
    const r = await fetch('/api/sessions/msg-token/' + encodeURIComponent(core), {
      headers: authHeaders(),
    });
    if (!r.ok) return null;
    const j = await r.json();
    if (!j || j.found !== true || typeof j.text !== 'string') return null;
    return {
      text: j.text,
      ts: Number(j.ts) || 0,
      sessionId: String(j.sessionId || ''),
      sessionName: String(j.sessionName || ''),
      local: false,
    };
  } catch {
    return null;
  }
}

/** 同会话本地腿：token 的 sid8 与活动会话判同 ⇒ 按铸牌序号对位内存用户行。
 *  跨会话 / 非安全源 / 窗口外（分页未载入）= null。 */
export async function lookupLocal(token) {
  const m = RE_TOKEN_PARSE.exec(String(token || '').trim());
  if (!m) return null;
  const view = state.getActiveView && state.getActiveView();
  const sid8 = await sid8Of(view && view.sessionId);
  if (!sid8 || m[1] !== sid8) return null;
  const seq = parseInt(m[2], 10);
  const rows = localUserMessages();
  const hit = rows[seq - 1];
  if (!hit) return null;
  return {
    text: hit.text,
    ts: hit.ts,
    sessionId: (view && view.sessionId) || '',
    sessionName: '',
    local: true,
  };
}

/**
 * 解析编排：引擎腿立即起（权威）；本地腿可即时可用 ⇒ 先经 onUpdate 上屏（同会话
 * 零等待）；引擎应答落地后以引擎为准覆盖（本地是启发式对位）。双腿皆空 = miss。
 * 结果进页内缓存；onUpdate 至多两次（local → final）。
 * @returns {Promise<{status:'ok', data:{text:string,ts:number,sessionId:string,sessionName:string,local:boolean}}|{status:'miss'}>}
 */
export async function resolveMsgToken(token, onUpdate) {
  const raw = String(token || '').trim();
  if (resolveCache.has(raw)) return resolveCache.get(raw);
  const m = RE_TOKEN_PARSE.exec(raw);
  if (!m) return { status: 'miss' };
  const enginePromise = lookupEngine(m[1] + '-' + m[2]);
  const localPromise = lookupLocal(raw);
  let local = null;
  try {
    local = await localPromise;
  } catch {
    local = null;
  }
  if (local && typeof onUpdate === 'function') onUpdate(local);
  let engine = null;
  try {
    engine = await enginePromise;
  } catch {
    engine = null;
  }
  /** @type {{status:'ok', data:{text:string,ts:number,sessionId:string,sessionName:string,local:boolean}}|{status:'miss'}} */
  const result = engine
    ? { status: 'ok', data: engine }
    : local
      ? { status: 'ok', data: local }
      : { status: 'miss' };
  resolveCache.set(raw, result);
  if (typeof onUpdate === 'function') onUpdate(result.status === 'ok' ? result.data : null);
  return result;
}

// ── popover（玻璃小窗；无 overlay 遮罩——点外关闭走 pointerdown 判定）────────

let popEl = null;
let popCurrentChip = null;
let popOutsideHandler = null;
let popKeyHandler = null;
let popResizeHandler = null;

/** 关闭当前 popover（幂等；拆卸全部文档级监听）。 */
export function closeMsgTokenPopover() {
  if (popOutsideHandler) {
    document.removeEventListener('pointerdown', popOutsideHandler, true);
    popOutsideHandler = null;
  }
  if (popKeyHandler) {
    document.removeEventListener('keydown', popKeyHandler, true);
    popKeyHandler = null;
  }
  if (popResizeHandler) {
    window.removeEventListener('resize', popResizeHandler);
    popResizeHandler = null;
  }
  if (popEl && popEl.isConnected) popEl.remove();
  popEl = null;
  popCurrentChip = null;
}

/** popover 内部渲染状态（onUpdate 消费）：data=null ⇒ 不可达降级注记。 */
function renderPopBody(data) {
  if (!popEl || !popEl.isConnected) return;
  const body = popEl.querySelector('.msg-token-pop-body');
  const foot = popEl.querySelector('.msg-token-pop-foot');
  const toggleBtn = popEl.querySelector('.msg-token-pop-toggle');
  if (!body) return;
  if (!data || typeof data.text !== 'string') {
    popEl.dataset.state = 'miss';
    body.textContent = t('chat.msgTokenUnreachable');
    body.classList.add('msg-token-pop-miss');
    if (toggleBtn) toggleBtn.hidden = true;
    if (foot) foot.hidden = true;
    return;
  }
  popEl.dataset.state = 'ok';
  body.classList.remove('msg-token-pop-miss');
  // msgchip-provenance 批（2026-11-01 作者令）：牌窗只显示**作者原生输入**——渠道来源
  // 块（`<system-reminder>以下消息来自…`）、注入头行（`[NODE-MESSAGE]` / `[mail chainId:]`）、
  // 附件路径指针行一律剥除（单点实现 channelProvenance.stripChannelProvenance，判据 =
  // 位置+结构，正文里作者自己写的同类句子原样保留）。附件另起一条简短指示，不塞路径。
  const stripped = stripChannelProvenance(data.text);
  body.textContent = stripped.text;
  // 附件指示（独立节点，在正文之外 ⇒ body.textContent 仍是纯正文，牌窗不出现路径噪声）。
  let attachNote = popEl.querySelector('.msg-token-pop-attach');
  if (stripped.attachments > 0) {
    if (!attachNote) {
      attachNote = document.createElement('div');
      attachNote.className = 'msg-token-pop-attach';
      body.insertAdjacentElement('afterend', attachNote);
    }
    attachNote.textContent = t('chat.msgTokenAttachNote', { n: stripped.attachments });
    attachNote.hidden = false;
  } else if (attachNote) {
    attachNote.hidden = true;
  }
  // 长文截断 + 展开：超过阈值（≈12 行 / 400 字）先夹取，按钮展开/收起（按**剥后**正文量）。
  const long = stripped.text.length > 400 || stripped.text.split('\n').length > 12;
  body.classList.toggle('clamped', long);
  if (toggleBtn) {
    toggleBtn.hidden = !long;
    toggleBtn.textContent = t('chat.msgTokenExpand');
    toggleBtn.setAttribute('aria-expanded', 'false');
  }
  if (foot) {
    foot.hidden = false;
    foot.textContent = String(popEl.dataset.token || '');
  }
  // 头部时间/会话补齐（本地腿 ts 常缺 ⇒ 引擎应答后回填）；时制 = 既有分层
  // footer formatter（formatFooterTime，与消息行 footer 同一读数）。
  const timeEl = popEl.querySelector('.msg-token-pop-time');
  if (timeEl && data.ts) timeEl.textContent = formatFooterTime(data.ts);
  const sessEl = popEl.querySelector('.msg-token-pop-session');
  if (sessEl && data.sessionName) {
    sessEl.textContent = data.sessionName;
    sessEl.hidden = false;
  }
}

/** 挂点定位：默认 chip 正下方；下放不下翻上方；双向夹取进视口（手机 375 档自适应）。 */
function positionPop(chip) {
  if (!popEl) return;
  const cr = chip.getBoundingClientRect();
  const vw = window.innerWidth;
  const vh = window.innerHeight;
  const width = Math.min(360, vw - 24);
  popEl.style.width = width + 'px';
  // 先在 0,0 量高再落位（避免布局抖动两跳）。
  popEl.style.visibility = 'hidden';
  popEl.style.left = '0px';
  popEl.style.top = '0px';
  const ph = popEl.offsetHeight;
  const gap = 8;
  let top = cr.bottom + gap;
  if (top + ph > vh - 8) {
    top = cr.top - ph - gap;
    if (top < 8) top = Math.max(8, Math.min(cr.bottom + gap, vh - ph - 8));
  }
  let left = cr.left;
  if (left + width > vw - 8) left = vw - width - 8;
  if (left < 8) left = 8;
  popEl.style.left = Math.round(left) + 'px';
  popEl.style.top = Math.round(Math.max(8, top)) + 'px';
  popEl.style.visibility = '';
}

/** 打开 popover（同一 chip 再点 = 关闭；换 chip = 换内容重开）。 */
export function openMsgTokenPopover(chip, token) {
  if (popEl && popCurrentChip === chip) {
    closeMsgTokenPopover();
    return;
  }
  closeMsgTokenPopover();
  const raw = String(token || '').trim();
  if (!RE_TOKEN_PARSE.test(raw)) return; // 畸形牌面不弹窗（零崩溃面）
  popEl = document.createElement('div');
  popEl.className = 'msg-token-pop glass';
  popEl.setAttribute('role', 'dialog');
  popEl.setAttribute('aria-label', t('chat.msgTokenPopTitle'));
  popEl.dataset.token = raw;
  popEl.dataset.state = 'loading';

  const head = document.createElement('div');
  head.className = 'msg-token-pop-head';
  const sender = document.createElement('span');
  sender.className = 'msg-token-pop-sender';
  sender.textContent = t('chat.quoteSenderMe'); // 铸牌恒为真人输入 ⇒ 发送者 = 作者
  const sess = document.createElement('span');
  sess.className = 'msg-token-pop-session';
  sess.hidden = true; // 跨会话引擎应答后回填
  const time = document.createElement('span');
  time.className = 'msg-token-pop-time';
  head.append(sender, sess, time);

  const body = document.createElement('div');
  body.className = 'msg-token-pop-body';
  body.textContent = t('chat.msgTokenLoading');

  const toggleBtn = document.createElement('button');
  toggleBtn.type = 'button';
  toggleBtn.className = 'msg-token-pop-toggle';
  toggleBtn.hidden = true;
  toggleBtn.addEventListener('click', (e) => {
    e.stopPropagation();
    const b = popEl && popEl.querySelector('.msg-token-pop-body');
    if (!b) return;
    const expand = b.classList.toggle('clamped') === false;
    toggleBtn.textContent = expand ? t('chat.msgTokenCollapse') : t('chat.msgTokenExpand');
    toggleBtn.setAttribute('aria-expanded', expand ? 'true' : 'false');
  });

  const foot = document.createElement('div');
  foot.className = 'msg-token-pop-foot';
  foot.hidden = true;

  popEl.append(head, body, toggleBtn, foot);
  document.body.appendChild(popEl);
  popCurrentChip = chip;
  positionPop(chip);

  // 点外关闭（capture 判定；点 chip 本体 = toggle 语义由委托腿处理）。
  popOutsideHandler = (e) => {
    if (!popEl) return;
    const target = e.target instanceof Element ? e.target : null;
    if (target && (popEl.contains(target) || (popCurrentChip && popCurrentChip.contains(target)))) return;
    closeMsgTokenPopover();
  };
  document.addEventListener('pointerdown', popOutsideHandler, true);
  // Esc 独占（capture + stopPropagation：popover 开着时这一下归它，不串宿主窗）。
  popKeyHandler = (e) => {
    if (e.key !== 'Escape') return;
    if (!popEl) return;
    e.stopPropagation();
    closeMsgTokenPopover();
  };
  document.addEventListener('keydown', popKeyHandler, true);
  popResizeHandler = () => closeMsgTokenPopover();
  window.addEventListener('resize', popResizeHandler);

  resolveMsgToken(raw, (data) => renderPopBody(data));
}

// ── 文档级委托（单点安装；innerHTML 重建不掉 listener）──────────────────────

let installed = false;

/** 安装 chip 点击委托（main.js 组合根调用一次；幂等）。 */
export function installMsgTokenChip() {
  if (installed) return;
  installed = true;
  document.addEventListener('click', (e) => {
    if (!(e.target instanceof Element)) return;
    const chip = /** @type {HTMLElement|null} */ (e.target.closest('.msg-token-chip'));
    if (!chip) return;
    e.preventDefault();
    e.stopPropagation();
    openMsgTokenPopover(chip, chip.dataset.msgToken || '');
  });
}

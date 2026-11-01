// askDock.js — Canvas 文件 ask mode（2026-10-28 实施批；同日 22:42 v2.1 简化修订；
// 2026-10-29 三修批②：FAB 通知点改绿点未读条件显示）。
//
// 文档会话持久批（2026-11-01 作者令「现在文档的会话 我切文档之后会消失」）：
// 每文档转录 localStorage write-through（键 = branding 命名空间 + ask:sess:<sid>），
// 建账即注水（切回即现，不等回放）。动机链：会话 Map 是页内内存态，重载/重连
// 后只剩 askOpen 回放一条恢复腿；而重连置脏 stateSent ⇒ 发问补发 askOpen ⇒ 回放
// 快照与本地问答竞态，旧合并守卫只保最后一条 user 问 —— AI 答与更早轮次被快照
// 整表抹掉（「切文档再切回来记录没了」的实感 = 被抹后的页内存态随切卡一路呈现）。
// 修复双件：① 本地持久（重载/切卡往返零依赖回放时序）；② askState 合并改
// 「永不缩水」——ask 转录是只追加流（dock 面零编辑零删除），本地比快照长 ⇒
// 快照必是过期残影，保留本地；服务端真源仍由 textDelta/done 与下一次完整回放
// 追平。持久化是体验加速不是正确性依赖（私密模式 try/catch 静默；服务端
// .ui.json 仍是权威）。
//
// 三修②（作者令 2026-10-29「小点应为绿色通知点，只在有（未读）消息时显示」）：
// 旧「has-sess = 会话有过消息 → 紫点常显」退役。新口径 = 未读通知：AI/注入帧
// 到达时用户不在看着（dock 收起 ∨ 非当前文件）→ session.unread 置位 → 绿点
// （--color-primary）+ pulse 唤回提示；打开该会话（open / 开态 askState 回放）
// 即读 → 点熄。判定单点 syncUnreadDot()。
// 形态 = 样张 v2.1（作者八条令 + v2.1 减法令「简约一点。不需要两个箭头，就右侧
// 一个发送按钮就行，显示内容也尽量删掉不必要的消息」）：canvas 右下角最靠右
// 入口钮 → 毛玻璃输入条（输入 + 右侧唯一发送钮，转交钮自 UI 撤除）→ 气泡列从
// 按钮位置长出（弹幕式升腾、按龄渐隐、顶部渐变模糊过渡）→ 零面板/零弹窗/零遮罩。
// v2.1 显示面减法：气泡面只出 user/ai 两族——ghost 提示行、session 徽标、转交
// 回执等非问答呈现删掉或收进不可见处（转录数据仍存、服务端为权威，渲染面永久
// 过滤 sys；错误文本落助手气泡槽；主会话回问照 §7 以用户消息形态呈现）。
// 机制（docs/design/20261028_canvas-ask-mode-design.zh-CN.md）：
//   - 每文件独立 session（`ask-<hash(absPath)>`，切卡即切该文件的会话/上下文
//     ——作者令⑦）；收起保留、再开复现（§2 收起三通路 + §4 持久化）；
//   - 后端 WS 域（WsAskHandlers）：askOpen（绑定+历史回放）/askQuestion
//     （dispatchUserText 同腿流式）/askHandoff（§7 转交——v2.1 起 UI 无入口，
//     由 ask agent 工具腿发起）/askReply（§7 回问）；
//   - 流式帧（textDelta/done/error 等）经 ws.js ask 拦截面（sessionId 前缀
//     `ask-`）路由进本模块，与主会话视图零交集（§2 z 序/视图排他同律）。
// 可见性闸（作者缺陷报 2026-10-29「Askmode怎么在插件面板里也有？」）：入口钮
//   只在文件型标签页（有真实文件 backing 的 canvas tab = active entry 携
//   absPath）挂载显示——插件面板/工具面板/图谱等非文件型 surface 零挂载零残
//   留，切卡离开文件 tab 即摘（syncGate → css .gate-off display:none）。
// md/html 类型硬约束闸（设计稿 §2「类型硬约束闸」，作者 2026-10-29 11:46 自裁）：
//   ask 能力仅 .md/.markdown/.html/.htm 四扩展名——非白名单文件 tab 与插件
//   面板同待遇（currentFile 赋值处过滤 → 入口不挂/隐藏）；后端 askOpen 同闸
//   拒答（AskSessions.isAskable 单点）——前端藏 + 后端拒双层防呆；存量绑定
//   仅闸新问题不清理（自然衰减 §9-8）。白名单前端单点 = mdHtmlGate.js。
// 移动（#988 同律）：气泡列 touch-action: pan-y 原生纵滚；canvasGestures 的
// zoneOf 对 .ask-dock/.ask-fab 返回 ''（surface 归属制，横滑零切卡）。

import { key } from './branding.js';
import state from './state.js';
import { sendWs, setAskFrameSink, onReconnect } from './ws.js';
import { activeCanvasEntry } from './canvas.js';
// 修复批 2026-10-31（作者令 ①）：ai 气泡渲染复用主会话渲染腿
// utils.renderMarkdownWithMath（代码块/表格/列表/数学全套，含 marked+KaTeX
// 护栏与 LRU）——渲染腿零重写；user 气泡维持 textContent 纯文本面。
import { renderMarkdownWithMath } from './utils.js';
import { isAskableFile } from './mdHtmlGate.js';

// ── 文案（i18n 键 = key('locale')，navController tt 同源）─────────────────
function tt(zh, en) {
  let lang = '';
  try { lang = localStorage.getItem(key('locale')) || ''; } catch { /* private mode */ }
  if (!lang) lang = document.documentElement.lang || navigator.language || '';
  return /^zh/i.test(lang) ? zh : en;
}

const ICONS = {
  chat: '<svg class="ask-fab-ic-chat" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M21 11.5a8.38 8.38 0 0 1-.9 3.8 8.5 8.5 0 0 1-7.6 4.7 8.38 8.38 0 0 1-3.8-.9L3 21l1.9-5.7a8.38 8.38 0 0 1-.9-3.8 8.5 8.5 0 0 1 4.7-7.6 8.38 8.38 0 0 1 3.8-.9h.5a8.48 8.48 0 0 1 8 8v.5z"/></svg>',
  close: '<svg class="ask-fab-ic-close" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"><path d="m18 15-6-6-6 6"/></svg>',
  send: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round"><path d="M12 19V5M5 12l7-7 7 7"/></svg>',
};

/** @type {Map<string, {sid: string, fileName: string, msgs: Array<{role: string, text: string}>, stateSent: boolean, unread: boolean}>} */
const sessions = new Map(); // key: absPath
let el = null; // { fab, dock, col, input, send }（v2.1：ghost/sess/handoff 件随减法退场）
let currentFile = ''; // absPath of the active file entry ('' = 非文件卡)
let inited = false;

// ── 每文档会话持久（2026-11-01 作者令批；头注「文档会话持久批」）────────────
// 键形 = key('ask:sess:<sid>')（branding 命名空间同源）；值 = {ts, msgs:[user/ai]}。
// 容量闸：每会话尾留 60 行（= 服务端回放档 200 的常用子集，localStorage 行小）、
// 全局至多 24 份会话（ts 最旧先淘汰——切卡即触碰，活跃文档自然留驻）。
const SESS_STORE_PREFIX = 'ask:sess:';
const SESS_MAX_MSGS = 60;
const SESS_MAX_SESSIONS = 24;

/** 读某会话的本地转录（无/坏/私密模式 ⇒ null——调用方回落空账）。 */
function loadPersisted(sid) {
  if (!sid) return null;
  try {
    const raw = localStorage.getItem(key(SESS_STORE_PREFIX + sid));
    if (!raw) return null;
    const v = JSON.parse(raw);
    if (!v || !Array.isArray(v.msgs)) return null;
    const msgs = v.msgs
      .filter((m) => m && typeof m.text === 'string' && (m.role === 'user' || m.role === 'ai'))
      .map((m) => ({ role: m.role, text: m.text }));
    return msgs;
  } catch { return null; }
}

/** 写某会话的本地转录（write-through 单点；任何突变后调用）。 */
function persistSession(s) {
  if (!s || !s.sid) return;
  try {
    const rows = s.msgs
      .filter((m) => m.role === 'user' || m.role === 'ai')
      .slice(-SESS_MAX_MSGS)
      .map((m) => ({ role: m.role, text: m.text }));
    localStorage.setItem(key(SESS_STORE_PREFIX + s.sid), JSON.stringify({ ts: Date.now(), msgs: rows }));
    pruneSessionStore();
  } catch { /* 私密模式 / quota —— 体验加速面，静默降级 */ }
}

/** 超额淘汰：ts 最旧的会话先出（活跃文档写时即触碰 = ts 刷新，自然留驻）。 */
function pruneSessionStore() {
  try {
    const found = [];
    const prefix = key(SESS_STORE_PREFIX);
    for (let i = 0; i < localStorage.length; i++) {
      const k = localStorage.key(i);
      if (!k || !k.startsWith(prefix)) continue;
      let ts = 0;
      try { const v = JSON.parse(localStorage.getItem(k)); if (v && typeof v.ts === 'number') ts = v.ts; } catch { /* 坏行按最旧算 */ }
      found.push({ k, ts });
    }
    if (found.length <= SESS_MAX_SESSIONS) return;
    found.sort((a, b) => a.ts - b.ts);
    for (const e of found.slice(0, found.length - SESS_MAX_SESSIONS)) {
      try { localStorage.removeItem(e.k); } catch { /* ignore */ }
    }
  } catch { /* ignore */ }
}

// 会话 id = 后端 AskSessions 单点（sha256(absPath) 前 8 hex，前缀 ask-）——
// 客户端同式派生（crypto.subtle），绑定面零额外往返。
const sidPromises = new Map();
function ensureSid(absPath) {
  if (!sidPromises.has(absPath)) {
    sidPromises.set(
      absPath,
      crypto.subtle
        .digest('SHA-256', new TextEncoder().encode(absPath))
        .then((buf) => {
          const hex = Array.from(new Uint8Array(buf).slice(0, 4)).map((b) => b.toString(16).padStart(2, '0')).join('');
          return 'ask-' + hex;
        })
        .catch(() => ''),
    );
  }
  return sidPromises.get(absPath);
}

async function sessionFor(absPath, fileName) {
  let s = sessions.get(absPath);
  if (!s) {
    // 建账即注水（持久批 2026-11-01）：本地转录先上台，切回即现——askOpen 回放
    // 只是后续对账（服务端为权威），不再是恢复的唯一腿。
    const sid = await ensureSid(absPath);
    s = {
      sid,
      fileName: fileName || absPath.split('/').pop() || absPath,
      msgs: loadPersisted(sid) || [],
      stateSent: false,
      unread: false,
    };
    sessions.set(absPath, s);
  }
  if (fileName && s.fileName !== fileName) s.fileName = fileName;
  return s;
}

/** 同步取当前文件 session（未派生完 = null——渲染面忽略，流式面按 sid 反查）。 */
function activeSession() {
  if (!currentFile) return null;
  return sessions.get(currentFile) || null;
}

/** 打开/发问前确保 session 记录就绪（sid 派生 + 记录入 map）。 */
async function ensureActiveSession() {
  if (!currentFile) return null;
  const entry = activeCanvasEntry();
  return sessionFor(currentFile, entry ? entry.title : '');
}

// ── 挂载（幂等；桌面 = #canvas-panel 内右下角；手机层 = body 级 fixed——
//    Canvas 改造批⑨，2026-10-31 作者令「修 bug：标签页对话按钮消失」：
//    #canvas-panel 手机层 display:none，旧挂载面让 FAB 连带隐身 = 消失 bug；
//    手机层改挂 body（fixed 钉视口右下、底 dock 上方），层切换经 body class
//    观察器自动迁移宿主，桌面/移动两腿同一对元素零重建）────────────────────

/** 当前应挂载的宿主：手机层 = body（fixed 定位语境）；桌面 = #canvas-panel。 */
function mountHost() {
  return document.body.classList.contains('mobile-layer')
    ? document.body
    : document.getElementById('canvas-panel');
}

function mount() {
  const host = mountHost();
  if (!host || host.querySelector(':scope > .ask-fab')) return;

  const fab = document.createElement('button');
  fab.type = 'button';
  fab.className = 'ask-fab';
  fab.title = tt('问这份文件（ask mode）', 'Ask this file (ask mode)');
  fab.setAttribute('aria-label', tt('问这份文件', 'Ask this file'));
  fab.innerHTML = ICONS.chat + ICONS.close + '<span class="ask-sess-dot" aria-hidden="true"></span>';

  const dock = document.createElement('div');
  dock.className = 'ask-dock closed';
  // v2.1 减法（作者令 2026-10-28 22:42）：输入条只留右侧一枚发送钮；ghost 提示
  // 行 / session 徽标不渲染（显示面删掉不必要的消息）。
  dock.innerHTML =
    '<div class="ask-colwrap"><div class="ask-col"></div><div class="ask-veil" aria-hidden="true"></div></div>' +
    '<div class="ask-input">' +
    '<input type="text" placeholder="' + tt('针对这份文件提问…', 'Ask about this file…') + '" aria-label="' + tt('提问', 'Question') + '">' +
    '<button type="button" class="ask-send" aria-label="' + tt('发送', 'Send') + '">' + ICONS.send + '</button>' +
    '</div>';

  host.appendChild(fab);
  host.appendChild(dock);

  el = {
    fab,
    dock,
    col: dock.querySelector('.ask-col'),
    input: dock.querySelector('input'),
    send: dock.querySelector('.ask-send'),
  };

  fab.addEventListener('click', (e) => { e.stopPropagation(); toggle(); });
  dock.addEventListener('click', (e) => e.stopPropagation());
  dock.addEventListener('pointerdown', (e) => e.stopPropagation());
  el.send.addEventListener('click', () => submit());
  el.input.addEventListener('keydown', (e) => { if (e.key === 'Enter') submit(); });
  el.input.addEventListener('focus', syncSeat);

  // 收起三通路之一：点击画布空白（#canvas-content 本体 = 无浮层处；卡片内
  // 点击/文本选择不受扰——panes/浮件自有 target）。
  const content = document.getElementById('canvas-content');
  if (content) {
    content.addEventListener('click', (e) => {
      if (e.target === content && isOpen()) close();
    });
  }
  // 收起三通路之二：Esc。
  document.addEventListener('keydown', (e) => {
    if (e.key === 'Escape' && isOpen() && isCanvasVisible()) { close(); }
  });

  document.addEventListener('canvas-tab-switched', onTabSwitch);
  document.addEventListener('canvas-tab-closed', onTabClosed);
  window.addEventListener('resize', syncSeat);
  // 批⑨：手机层根页切换（nf:mobile-nav）重落可见性闸——离开 canvas 页即隐。
  document.addEventListener('nf:mobile-nav', () => syncGate());
  // 批⑩：阅读器底栏挂载完成 → 座位重算（底栏入角落浮件占用集，见 syncSeat）。
  document.addEventListener('nf-reader-mounted', () => syncSeat());
  // 批⑨：手机层开/关（body.mobile-layer 起落）迁移 FAB/dock 宿主——同一对
  // 元素在 #canvas-panel（absolute 锚面板）与 body（fixed 钉视口）间搬移。
  if (typeof MutationObserver === 'function') {
    let mobileOn = document.body.classList.contains('mobile-layer');
    new MutationObserver(() => {
      const now = document.body.classList.contains('mobile-layer');
      if (now === mobileOn || !el) return;
      mobileOn = now;
      const h = mountHost();
      if (h && el.fab.parentElement !== h) { h.appendChild(el.fab); h.appendChild(el.dock); }
      syncGate();
      syncSeat();
    }).observe(document.body, { attributes: true, attributeFilter: ['class'] });
  }
  if (typeof ResizeObserver === 'function') {
    // 画布开合（宽度过渡）会移动右下角座位——面板尺寸变化即重算。
    const panel = document.getElementById('canvas-panel');
    if (panel) new ResizeObserver(() => syncSeat()).observe(panel);
  }
  if (typeof MutationObserver === 'function') {
    // 排布批③：角落圆钮簇晚于切卡事件挂载（readFile 异步渲染腿把查找/源码钮
    // 插进 pane）——文档树 childList 观察兜底重算座位（rAF 合帧，一帧至多一
    // 趟；座步行写入幂等同值，无回环面）。
    let seatRaf = 0;
    new MutationObserver(() => {
      if (!document.body.classList.contains('mobile-layer') || seatRaf) return;
      seatRaf = requestAnimationFrame(() => { seatRaf = 0; syncSeat(); });
    }).observe(document.body, { childList: true, subtree: true });
  }
  onReconnect(() => {
    // WS 重连：会话状态标脏，下次打开时 askOpen 重新回放（服务端为权威）。
    for (const s of sessions.values()) s.stateSent = false;
  });

  syncSeat();
}

function isCanvasVisible() {
  return document.body.classList.contains('canvas-open');
}

// ── 可见性闸（作者缺陷报 2026-10-29「Askmode怎么在插件面板里也有？」）──────
// 入口钮（连带 dock）只在文件型标签页在场：active entry 携 absPath = 有真实
// 文件 backing 的 canvas tab。插件面板/工具面板/图谱等非文件型 surface 零挂
// 载零残留——.gate-off = display:none（出布局出命中盒，非视觉弱化）。闸随
// syncToActiveTab 每拍落位：切卡/关卡/批量恢复的 active 变更全经 setActiveTab
// → canvas-tab-switched（或 canvas-tab-closed）事件到齐，初始无卡态同样吃闸。
// md/html 类型硬约束闸（设计稿 §2；作者 2026-10-29 11:46 自裁）：currentFile
// 赋值处即过滤非白名单扩展名——非 md/html 文件 tab 与插件面板同待遇（入口
// 不挂/隐藏，双层防呆之后端 isAskable 拒答）；白名单单点 = mdHtmlGate.js。
function syncGate() {
  if (!el) return;
  const mobile = document.body.classList.contains('mobile-layer');
  // 批⑨：手机层 FAB 只在 canvas 页在场时亮——active 文件卡不因根页切换而
  // 变，缺本闸聊天根/工作台根会浮一颗无上下文对话钮。
  const mobilePageOk = !mobile ||
    !!(typeof window !== 'undefined' && (/** @type {any} */ (window)).__nfMobileNav
      && (/** @type {any} */ (window)).__nfMobileNav.top === 'canvas');
  const off = !currentFile || readerPanelOpen || !mobilePageOk;
  el.fab.classList.toggle('gate-off', off);
  el.dock.classList.toggle('gate-off', off);
}

// ── 阅读器面板让位（阅读器 P1 批，设计稿 §2.2：阅读菜单/TOC 抽屉开时 ──────
// ask-fab 与角落圆钮簇让位隐藏）——readerChrome.js 派发 nf-reader-panel-toggle
// （detail.open）；闸与可见性闸合成：syncGate off = 非文件卡 或 阅读面板开。
// 面板关/切书由 chrome 的对应 false 事件复位（其卸载路径必发）。
let readerPanelOpen = false;
document.addEventListener('nf-reader-panel-toggle', (e) => {
  const d = (/** @type {CustomEvent<{open?: boolean}>} */ (e)).detail;
  readerPanelOpen = !!(d && d.open);
  syncGate();
});

function syncToActiveTab() {
  const entry = activeCanvasEntry();
  // 可问面硬闸在赋值处生效（批⑩ 2026-10-31：md/html + 电子书 epub/mobi/azw3；
  // §7-A4 同律）：非白名单扩展名 = 视同非文件卡（''）。
  const file = entry && entry.absPath && isAskableFile(entry.absPath) ? entry.absPath : '';
  const switched = file !== currentFile;
  currentFile = file;
  syncGate(); // 可见性闸每拍落位（幂等；含初始无卡态与切回文件卡复现）
  if (!switched) { syncSeat(); return; }
  syncUnreadDot(); // 通知点随 active session 的未读态落位（绿点只在有未读时显示）
  if (isOpen()) {
    if (currentFile) {
      render();
      // 切卡即切 session（作者令⑦）：新文件的会话未回放过 → 即刻 askOpen
      // （绑定 + 历史回放；服务端为权威，重复帧幂等）。
      ensureActiveSession().then((loaded) => {
        if (isOpen() && loaded && !loaded.stateSent && currentFile) askOpen(loaded);
      }).catch(() => {});
    } else close();
  }
  syncSeat();
}

function onTabSwitch(e) {
  const id = e.detail && e.detail.id;
  if (id) { syncToActiveTab(); }
}

function onTabClosed() {
  // 关掉的卡的 session 留在 map（服务端持久，重开同文件即复现）；活动卡变化
  // 即同步（含「最后一张关掉 → 无文件」情形）。
  syncToActiveTab();
}

// ── 座位（右下角让位：缩放条/刷新钮等角落圆钮簇实测让位，seatRefreshButton
//    同思路；移动端让位切换条）──────────────────────────────────────────────

function syncSeat() {
  if (!el) return;
  const mobile = document.body.classList.contains('mobile-layer');
  if (mobile) {
    // 批⑨：手机层 = body 级 fixed（ask.css 移动档钉位：底 dock 上方），
    // 面板锚让位计算整腿退役。
    // 批⑩：活动卡挂电子书阅读器时，底栏 ⋯ 右端与 fab 同角——fab 抬到底栏
    // 上方（nf-reader-mounted 触发本重算；reader-p1 移动触滑腿实证拦截）。
    const pane = document.querySelector('.canvas-tab-pane.active');
    const nav = pane && (/** @type {HTMLElement|null} */ (pane.querySelector('.epub-reader-nav')));
    if (nav && nav.offsetParent !== null) {
      const nr = nav.getBoundingClientRect();
      if (nr.width > 0 && nr.height > 0) {
        el.fab.style.bottom = Math.max(10, window.innerHeight - nr.top + 8) + 'px';
        return;
      }
    }
    // 悬浮控件排布批③（2026-10-31 作者令 23:35「两个浮动控件互相重叠」截图
    // 实证）：文档 pane 角落圆钮簇（查找/源码/引用，pane 内 absolute bottom:16
    // 右缘）与 body 级 fab（底 dock 上方 right:16）在手机层同角同带——fab 钉
    // 位恰压住源码钮。桌面 occupied 同律换算视口系：簇可见时 fab 抬到簇上缘
    // 之上 12px（输入条跟随同距，保持钮-条联动）；无簇回落 css 钉位。
    const floats = pane
      ? pane.querySelectorAll('.canvas-source-toggle, .canvas-find-toggle, .canvas-ref-select')
      : [];
    let occupied = 0;
    floats.forEach((f) => {
      const h = /** @type {HTMLElement} */ (f);
      if (!(h instanceof HTMLElement) || h.hidden || h.offsetParent === null) return;
      const r = h.getBoundingClientRect();
      if (r.width === 0 && r.height === 0) return;
      occupied = Math.max(occupied, window.innerHeight - r.top);
    });
    if (occupied > 0) {
      el.fab.style.bottom = (occupied + 12) + 'px';
      el.dock.style.bottom = (occupied + 12 + 48 + 10) + 'px';
      return;
    }
    el.fab.style.bottom = '';
    el.dock.style.bottom = '';
    return;
  }
  const panel = document.getElementById('canvas-panel');
  if (!panel) return;
  const panelRect = panel.getBoundingClientRect();
  let bottomPx = 14;
  // 角落浮件（可见者取最上沿）：缩放条 / 壳刷新钮 / pane 内源码-引用钮族 /
  // 电子书阅读器底栏（批⑩ 2026-10-31：epub/mobi/azw3 并入可问面后 fab 同样上
  // 阅读器页——底栏 ⋯ 钮在右端，fab 不抬必拦截其命中，reader-p1 A5 实证）。
  const floats = panel.querySelectorAll('.canvas-zoom-bar, .canvas-refresh-toggle, .canvas-source-toggle, .canvas-ref-toggle, .epub-reader-nav');
  let occupied = 0;
  floats.forEach((f) => {
    const h = /** @type {HTMLElement} */ (f);
    if (!(h instanceof HTMLElement) || h.hidden || h.offsetParent === null) return;
    const r = h.getBoundingClientRect();
    if (r.width === 0 && r.height === 0) return;
    occupied = Math.max(occupied, panelRect.bottom - r.top);
  });
  bottomPx = occupied > 0 ? occupied + 12 : 14;
  panel.style.setProperty('--ask-fab-bottom', bottomPx + 'px');
}

// ── 开合（作者令⑧：transform-origin 锚定按钮圆心，开合同一曲线）───────────

function isOpen() { return !!el && !el.dock.classList.contains('closed'); }

// ── FAB 通知点（作者令 2026-10-29「三修②」：绿点=未读通知，无未读不显示）──
// 旧口径「has-sess = 会话有过消息 → 紫点常显」退役。未读判定单点：AI/注入帧
// 到达时用户不在看着（dock 收起 ∨ 非当前文件）即记未读；打开该会话即读。
function syncUnreadDot() {
  if (!el) return;
  const s = activeSession();
  const unread = !!(s && s.unread);
  el.fab.classList.toggle('has-unread', unread);
  if (!isOpen()) el.fab.classList.toggle('pulse', unread); // pulse=未读唤回提示（已读收起不再脉动）
}

async function open() {
  // 非文件卡入口已被可见性闸摘除；可问面硬闸复核（批⑩ 同律：md/html +
  // 电子书扩展名白名单；§7-A4 冗余守卫双层防呆）。
  if (!currentFile || !isAskableFile(currentFile)) return;
  const s = await ensureActiveSession();
  if (!s) return;
  s.unread = false; // 打开即读：通知点熄灭（ask 三修 2026-10-29 未读语义）
  el.dock.classList.remove('closed');
  el.fab.classList.add('open');
  el.fab.classList.remove('pulse');
  render();
  askOpen(s);
  syncSeat();
  setTimeout(() => { try { el.input.focus(); } catch { /* detached */ } }, 260);
}

function close() {
  if (!el) return;
  el.dock.classList.add('closed');
  el.fab.classList.remove('open');
  syncUnreadDot(); // 已读收起 → 点/pulse 不亮；有未读 → pulse 提示（仅未读时）
}

function toggle() {
  if (isOpen()) close();
  else open();
}

// ── 渲染（弹幕式升腾 + 按龄渐隐 + 顶带渐隐由 CSS mask 承担）────────────────

function render() {
  if (!el) return;
  const s = activeSession();
  el.col.textContent = '';
  if (!s) return;
  // v2.1 显示面减法：只出 user/ai 两族——sys（回执/状态噪声类）转录仍存但永不
  // 呈现（收进不可见处；主会话回问在 consumeFrame 已按 §7 落 user 族）。
  const vis = s.msgs.filter((m) => m.role !== 'sys');
  vis.forEach((m, i) => {
    const b = document.createElement('div');
    b.className = 'ask-bub ' + (m.role === 'user' ? 'user' : 'ai');
    // ① 修复批 2026-10-31：ai 族走主会话渲染腿（markdown 全套）；user 族
    // 维持 textContent（用户输入纯文本面，与主会话一致）。渲染腿复用不重写。
    if (m.role === 'user') b.textContent = m.text;
    else b.innerHTML = renderMarkdownWithMath(m.text);
    // 弹幕感：越靠上（越旧）越透明（样张按龄渐隐同律）。
    const d = vis.length - 1 - i;
    b.style.setProperty('--ask-age-alpha', String(Math.max(0.3, 1 - d * 0.13)));
    b.setAttribute('data-age', String(d));
    el.col.appendChild(b);
  });
  requestAnimationFrame(() => { el.col.scrollTop = el.col.scrollHeight; });
}

/** 流式增量更新（不整列重渲——textDelta 高频）。 */
function updateStreamingBubble(s) {
  if (!el || s !== activeSession()) return;
  const last = s.msgs[s.msgs.length - 1];
  let node = el.col.lastElementChild;
  if (!node || !node.classList.contains('ai')) {
    render();
    return;
  }
  // 主会话流式同律：cache:false 直解析（每帧重解析增长文本，不进 LRU 挤占）。
  node.innerHTML = last ? renderMarkdownWithMath(last.text, true, { cache: false }) : '';
  el.col.scrollTop = el.col.scrollHeight;
}

// ── 后端 WS 域（发 + 收）───────────────────────────────────────────────────

function askOpen(s) {
  s.stateSent = true;
  sendWs({
    type: 'askOpen',
    file: { absPath: currentFile, name: s.fileName },
    mainSessionId: state.activeSessionId || '',
  });
}

async function submit() {
  if (!el || !currentFile || !isAskableFile(currentFile)) return; // 硬闸复核（冗余守卫；批⑩ 判据 = md/html + 电子书并集）
  const s = await ensureActiveSession();
  if (!s) return;
  const v = el.input.value.trim();
  if (!v) return;
  el.input.value = '';
  // ④ 双泡修复批 2026-10-31（作者截图：等待小气泡上方叠多余空泡）：发问时
  // **不再预压空 ai 泡**——等待期只有 typing 一件；首个 textDelta 落 ai 泡时
  // removeTyping() 单点换泡（双泡根因 = 空 ai 泡 + typing 泡并存）。
  s.msgs.push({ role: 'user', text: v });
  persistSession(s); // 发问即落盘（持久批：重载/竞态现场问题不丢）
  render();
  appendTyping();
  if (!s.stateSent) askOpen(s); // 未绑定先补一拍（历史回放与流式都依赖 askState）
  sendWs({ type: 'askQuestion', sessionId: s.sid, content: v, mainSessionId: state.activeSessionId || '' });
}

let typingNode = null;
let streamPersistAt = 0; // 流式落盘节流拍（持久批；800ms 一写，done 终局兜底）
function appendTyping() {
  if (!el) return;
  removeTyping();
  const tp = document.createElement('div');
  tp.className = 'ask-bub typing';
  tp.innerHTML = '<i></i><i></i><i></i>';
  el.col.appendChild(tp);
  el.col.scrollTop = el.col.scrollHeight;
  typingNode = tp;
}
function removeTyping() {
  if (typingNode && typingNode.isConnected) typingNode.remove();
  typingNode = null;
}

/** ws.js ask 拦截面：sessionId 前缀 `ask-` 的帧全部进这里（主视图零交集）。 */
function consumeFrame(msg) {
  const sid = msg.sessionId || '';
  // 绑定面：sid 客户端同式派生（sha256 前 8 hex），map 内按 sid 反查即得文件。
  let entry = null;
  let file = '';
  for (const [f, s] of sessions) {
    if (s.sid && s.sid === sid) { entry = s; file = f; break; }
  }
  if (!entry) return; // 本页未见过的 ask 会话（其它设备/页面）——忽略。

  switch (msg.type) {
    case 'askState': {
      entry.sid = msg.sessionId || entry.sid;
      entry.fileName = msg.fileName || entry.fileName;
      // ④ 双泡修复批伴随守卫：submit 先 askOpen 后 askQuestion，回放帧快照可能
      // 早于本拍提问落账——整表替换会把刚发的 user 泡抹掉（视觉丢问）。
      // 持久批 2026-11-01 升级为「永不缩水」合并（头注动机链）：ask 转录是
      // 只追加流，本地比快照长 ⇒ 快照必是过期/竞态残影（WS 重连重放 + 发问
      // 竞态现场），保留本地不换血；本地空或快照更长 ⇒ 采纳快照（服务端对账
      // 追平，含他机续问）。旧守卫「只保最后一条 user 问」的 AI 答整表抹除
      // 即本批根治点。
      const serverMsgs = Array.isArray(msg.messages)
        ? msg.messages
            .filter((m) => m && typeof m.text === 'string')
            .map((m) => ({
              role: m.role === 'user' ? 'user' : m.role === 'sys' ? 'sys' : 'ai',
              text: m.text,
            }))
        : entry.msgs;
      const localLastUser = entry.msgs.filter((m) => m.role === 'user').slice(-1)[0];
      const adopted = serverMsgs.length >= entry.msgs.length ? serverMsgs : entry.msgs;
      const adoptedLastUser = adopted.filter((m) => m.role === 'user').slice(-1)[0];
      entry.msgs = adopted;
      // 本地发问尚不在账（回放快照早于本拍提问落账）⇒ 补挂到尾。幂等双闸：
      // 末位问文本相同不挂；问已在账内任意位（服务端已落账）不挂。
      if (
        localLastUser &&
        localLastUser.role === 'user' &&
        (!adoptedLastUser || adoptedLastUser.text !== localLastUser.text) &&
        !entry.msgs.some((m) => m.role === 'user' && m.text === localLastUser.text)
      ) {
        entry.msgs = entry.msgs.concat([localLastUser]);
      }
      persistSession(entry);
      if (file === currentFile) {
        // 回放即读（用户此刻正看着该会话）：未读清零 + 通知点落位。
        if (isOpen()) { entry.unread = false; removeTyping(); render(); }
        syncUnreadDot();
      }
      break;
    }
    case 'textDelta': {
      // 未读判定（ask 三修②）：到达时用户不在看着（dock 收起 ∨ 后台文件）即记未读。
      if (!(entry === activeSession() && isOpen())) entry.unread = true;
      const last = entry.msgs[entry.msgs.length - 1];
      if (!last || last.role !== 'ai') {
        entry.msgs.push({ role: 'ai', text: '' });
        if (entry === activeSession() && isOpen()) removeTyping();
      }
      const ai = entry.msgs[entry.msgs.length - 1];
      ai.text += msg.delta || '';
      // 流式落盘节流（持久批）：textDelta 高频，800ms 一拍写 localStorage；
      // 终局由 done 全量落盘兜底。
      const nowMs = Date.now();
      if (nowMs - streamPersistAt > 800) { streamPersistAt = nowMs; persistSession(entry); }
      if (entry === activeSession()) updateStreamingBubble(entry);
      if (entry === activeSession()) syncUnreadDot();
      break;
    }
    case 'done': {
      if (!(entry === activeSession() && isOpen())) entry.unread = true;
      const last = entry.msgs[entry.msgs.length - 1];
      if (last && last.role === 'ai' && !last.text && msg.finalText) last.text = msg.finalText;
      persistSession(entry);
      if (entry === activeSession()) {
        if (isOpen()) { removeTyping(); render(); }
        syncUnreadDot();
      }
      break;
    }
    case 'error': {
      // v2.1：错误不再起 sys 气泡——文本落助手气泡槽（问答面只出两族）。
      if (!(entry === activeSession() && isOpen())) entry.unread = true;
      const last = entry.msgs[entry.msgs.length - 1];
      const errText = tt('出错了：', 'Error: ') + (msg.message || 'unknown');
      if (last && last.role === 'ai' && !last.text) last.text = errText;
      else entry.msgs.push({ role: 'ai', text: errText });
      persistSession(entry);
      if (entry === activeSession()) {
        if (isOpen()) { removeTyping(); render(); }
        syncUnreadDot();
      }
      break;
    }
    case 'askHandoffAck': {
      // v2.1：转交回执收进不可见处——不呈现（§7 转交机制不变，ack 静默消费；
      // 主会话侧 injected 气泡承担转交可见性）。
      break;
    }
    case 'user': {
      // 注入族（injected:true）：主会话回问腿（后端 WsAskHandlers.askReply 以
      // ExternalEvent(source=main) 注入 ask 会话 → AgentActor 发 injected user
      // 帧）。§7 契约即「以用户消息形态呈现」（前缀「主会话回问：」由后端注入
      // 文本携带）——v2.1 起直接进 user 族气泡（问答面两族制，零 sys 呈现）。
      if (msg.injected) {
        if (!(entry === activeSession() && isOpen())) entry.unread = true;
        entry.msgs.push({ role: 'user', text: msg.text || '' });
        persistSession(entry);
        if (entry === activeSession()) {
          if (isOpen()) { removeTyping(); render(); }
          syncUnreadDot();
        }
      }
      break;
    }
    default:
      // thinking / tool* / usageUpdate / compact* 等过程帧：透明纪律（§6）
      // 不进气泡列，静默消费。
      break;
  }
}

// ── init（main.js 调用；幂等）─────────────────────────────────────────────

export function initAskDock() {
  if (inited) return;
  inited = true;
  mount();
  if (el) syncToActiveTab();
  setAskFrameSink(consumeFrame);
}

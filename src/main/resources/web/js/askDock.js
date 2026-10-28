// askDock.js — Canvas 文件 ask mode（2026-10-28 实施批；同日 22:42 v2.1 简化修订）。
//
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
// 移动（#988 同律）：气泡列 touch-action: pan-y 原生纵滚；canvasGestures 的
// zoneOf 对 .ask-dock/.ask-fab 返回 ''（surface 归属制，横滑零切卡）。

import { key } from './branding.js';
import state from './state.js';
import { sendWs, setAskFrameSink, onReconnect } from './ws.js';
import { activeCanvasEntry } from './canvas.js';

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

/** @type {Map<string, {sid: string, key: string, fileName: string, msgs: Array<{role: string, text: string, receipt?: boolean}>, stateSent: boolean}>} */
const sessions = new Map(); // key: absPath
let el = null; // { fab, dock, col, input, send }（v2.1：ghost/sess/handoff 件随减法退场）
let currentFile = ''; // absPath of the active file entry ('' = 非文件卡)
let inited = false;

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
    s = {
      sid: await ensureSid(absPath),
      key: 'ask:' + (await ensureSid(absPath)).slice(4, 8),
      fileName: fileName || absPath.split('/').pop() || absPath,
      msgs: [],
      stateSent: false,
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

// ── 挂载（幂等；#canvas-panel 内，右下角）─────────────────────────────────

function mount() {
  const panel = document.getElementById('canvas-panel');
  if (!panel || panel.querySelector(':scope > .ask-fab')) return;

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

  panel.appendChild(fab);
  panel.appendChild(dock);

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
  if (typeof ResizeObserver === 'function') {
    // 画布开合（宽度过渡）会移动右下角座位——面板尺寸变化即重算。
    new ResizeObserver(() => syncSeat()).observe(panel);
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

function syncToActiveTab() {
  const entry = activeCanvasEntry();
  const file = entry && entry.absPath ? entry.absPath : '';
  if (file === currentFile) { syncSeat(); return; }
  currentFile = file;
  const s = activeSession();
  const hasMsgs = !!(s && s.msgs.length);
  el.fab.classList.toggle('has-sess', hasMsgs);
  if (!isOpen() && hasMsgs) el.fab.classList.add('pulse');
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
  const panel = document.getElementById('canvas-panel');
  if (!panel) return;
  const panelRect = panel.getBoundingClientRect();
  let bottomPx = 14;
  const mobile = document.body.classList.contains('mobile-layer');
  if (mobile) {
    const bar = /** @type {HTMLElement|null} */ (panel.querySelector('.mobile-card-switchbar'));
    if (bar && bar.offsetParent !== null) {
      const r = bar.getBoundingClientRect();
      bottomPx = Math.max(bottomPx, panelRect.bottom - r.top + 12);
    } else {
      bottomPx = 56;
    }
  } else {
    // 角落浮件（可见者取最上沿）：缩放条 / 壳刷新钮 / pane 内源码-引用钮族。
    const floats = panel.querySelectorAll('.canvas-zoom-bar, .canvas-refresh-toggle, .canvas-source-toggle, .canvas-ref-toggle');
    let occupied = 0;
    floats.forEach((f) => {
      const h = /** @type {HTMLElement} */ (f);
      if (!(h instanceof HTMLElement) || h.hidden || h.offsetParent === null) return;
      const r = h.getBoundingClientRect();
      if (r.width === 0 && r.height === 0) return;
      occupied = Math.max(occupied, panelRect.bottom - r.top);
    });
    bottomPx = occupied > 0 ? occupied + 12 : 14;
  }
  panel.style.setProperty('--ask-fab-bottom', bottomPx + 'px');
}

// ── 开合（作者令⑧：transform-origin 锚定按钮圆心，开合同一曲线）───────────

function isOpen() { return !!el && !el.dock.classList.contains('closed'); }

async function open() {
  if (!currentFile) return; // 非文件卡无 ask 面（入口常驻，点了无效即静默）
  const s = await ensureActiveSession();
  if (!s) return;
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
  const s = activeSession();
  if (s && s.msgs.length) el.fab.classList.add('pulse');
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
    b.textContent = m.text;
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
  node.textContent = last ? last.text : '';
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
  if (!el || !currentFile) return;
  const s = await ensureActiveSession();
  if (!s) return;
  const v = el.input.value.trim();
  if (!v) return;
  el.input.value = '';
  s.msgs.push({ role: 'user', text: v });
  s.msgs.push({ role: 'ai', text: '' });
  el.fab.classList.add('has-sess');
  render();
  appendTyping();
  if (!s.stateSent) askOpen(s); // 未绑定先补一拍（历史回放与流式都依赖 askState）
  sendWs({ type: 'askQuestion', sessionId: s.sid, content: v, mainSessionId: state.activeSessionId || '' });
}

let typingNode = null;
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
      entry.key = msg.sessionKey || '';
      entry.fileName = msg.fileName || entry.fileName;
      entry.msgs = Array.isArray(msg.messages)
        ? msg.messages
            .filter((m) => m && typeof m.text === 'string')
            .map((m) => ({
              role: m.role === 'user' ? 'user' : m.role === 'sys' ? 'sys' : 'ai',
              text: m.text,
              receipt: false,
            }))
        : entry.msgs;
      if (file === currentFile) {
        el.fab.classList.toggle('has-sess', entry.msgs.length > 0);
        if (isOpen()) { removeTyping(); render(); }
      }
      break;
    }
    case 'textDelta': {
      const last = entry.msgs[entry.msgs.length - 1];
      if (!last || last.role !== 'ai') {
        entry.msgs.push({ role: 'ai', text: '' });
        if (entry === activeSession() && isOpen()) removeTyping();
      }
      const ai = entry.msgs[entry.msgs.length - 1];
      ai.text += msg.delta || '';
      if (entry === activeSession()) updateStreamingBubble(entry);
      markSess(entry, file);
      break;
    }
    case 'done': {
      const last = entry.msgs[entry.msgs.length - 1];
      if (last && last.role === 'ai' && !last.text && msg.finalText) last.text = msg.finalText;
      if (entry === activeSession() && isOpen()) { removeTyping(); render(); }
      markSess(entry, file);
      break;
    }
    case 'error': {
      // v2.1：错误不再起 sys 气泡——文本落助手气泡槽（问答面只出两族）。
      const last = entry.msgs[entry.msgs.length - 1];
      const errText = tt('出错了：', 'Error: ') + (msg.message || 'unknown');
      if (last && last.role === 'ai' && !last.text) last.text = errText;
      else entry.msgs.push({ role: 'ai', text: errText });
      if (entry === activeSession() && isOpen()) { removeTyping(); render(); }
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
        entry.msgs.push({ role: 'user', text: msg.text || '' });
        if (entry === activeSession() && isOpen()) { removeTyping(); render(); }
      }
      break;
    }
    default:
      // thinking / tool* / usageUpdate / compact* 等过程帧：透明纪律（§6）
      // 不进气泡列，静默消费。
      break;
  }
}

function markSess(s, file) {
  if (file === currentFile) el.fab.classList.add('has-sess');
  else { /* 后台会话活跃：徽标在切回时由 syncToActiveTab 补点 */ }
}

// ── init（main.js 调用；幂等）─────────────────────────────────────────────

export function initAskDock() {
  if (inited) return;
  inited = true;
  mount();
  if (el) syncToActiveTab();
  setAskFrameSink(consumeFrame);
}

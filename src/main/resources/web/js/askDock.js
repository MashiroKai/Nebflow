// askDock.js — Canvas 文件 ask mode（2026-10-28 实施批；同日 22:42 v2.1 简化修订；
// 2026-10-29 三修批②：FAB 通知点改绿点未读条件显示）。
//
// 记事本批（作者令 2026-11-05 11:38「给 canvas 支持 ask 模式的文件，再加一个
// 按钮，放 ask 按钮旁边，同样风格的一个 note 按钮，方便用户点击记录一些思路的
// 一个记事本就行。不需要复杂功能，风格和 ask 的保持一致。然后也和 ask 一样点击
// 出来一个窗口，左上角可以调整窗口大小」）：本模块同时承载 `.note-fab` 与
// `.note-dock`——**同一组件、同一份样式、同一段尺寸状态机**，不是第二套浮窗：
//   · 钮：class = `ask-fab note-fab` ⇒ glass.css 圆钮级浮件档成员全部继承（材质/
//     hover/active/focus-visible/手机 48 档/`.gate-off` 闸），ask.css 只补一条
//     落位（margin-right 让开 ask 钮一格 8px，同一行同一中线）；
//   · 窗：class = `note-dock` ⇒ 座位/开合曲线/宽档 `--ask-dock-w`/`.closed`/
//     `.is-resizing`/手机档全部**以选择器成员身份**复用 `.ask-dock` 的既有规则；
//     刻意不挂 `.ask-dock` 身份类（那是既有 spec 的判据选择器，同一页第二个同身份
//     节点会让「ask 窗现在开没开」这类 querySelector 判断失真）；
//   · 拖柄：design-system.css `.ds-resize-handle` 原件（44 热区 / 直角弧），落位
//     复用 ask 面板左上外挂 14px 那条规则；
//   · 尺寸状态机：resizeLimits / applySize / bindResize **一份实现两个入参**（尺寸
//     面 surface：panel / handle / track / footer / varW / varH / onApply）。
// 内容 = 纯文本（无富文本/无页签/无分类），**每文件一份草稿**（与 ask 每文件一会
// 话同律；入口钮的可见性闸本来就只在文件卡亮），localStorage write-through
// （键 = branding 命名空间 + `note:<sid>`，sid 与 ask 同式 = sha256(absPath) 前
// 8 hex）⇒ 刷新/切卡往返不丢，收起再开还在；私密模式/quota 静默降级（与 ask
// 转录持久同律：体验加速面，不是正确性依赖）。
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
//
// ══ 组件化范式试点批（ask-paradigm-a，2026-11-03）══════════════════════════
// 本文件是「vanilla 组件范式」的**唯一试点面**（不扩散到任何其它面）：形态从
// 「一段自己拼 DOM 的插件代码」改成真组件 `<nf-ask-dock>`（light DOM）——
//   · 宿主 = `<nf-ask-dock>`，挂在原 mountHost() 决定的宿主里（桌面 #canvas-panel /
//     手机层 body，判据原样）；原 `.ask-fab` 与 `.ask-dock` **两个节点移进组件内部**，
//     仍是宿主里的兄弟关系（light DOM，`document.querySelector('.ask-fab')` 照旧命中）。
//   · 宿主零布局盒：css/ask.css 的 `nf-ask-dock { display: contents; }`——它不出盒、
//     不建定位包含块，所以 `.ask-fab` / `.ask-dock` 的绝对定位包含块仍是
//     #canvas-panel，几何与改前逐值一致（批报告附改前后 getBoundingClientRect 读数）。
//   · 对外只三个方法：open() / close() / syncSession()（语义 = 原 open / close /
//     syncToActiveTab）。模块导出面收敛为 initAskDock() + INTERNAL_CLASSES。
//   · 内部渲染一律走 js/dom.js 的 h()/frag()（禁 HTML 串）；**唯一例外**是 markdown
//     渲染腿（renderMarkdownWithMath 的返回值落到 ai 气泡），见 render() 的注释。
//   · 拖拽改尺寸的几何状态机（bindResize 起）**保持命令式原样**——它正是要验证的
//     「组件范式与命令式几何共存」：状态机只管 el 引用与 CSS 变量，不碰渲染树。
//   · 生命周期口径（component.js）：connectedCallback 清空 → template() 返回值
//     appendChild → mount(el, ctx)。**关键设计**：template 返回的是**同一对节点**
//     （模块级 el 缓存），所以宿主迁移（手机层 ↔ 桌面层，见 wireOnce 的 body
//     class 观察器）走的是「节点搬家」而不是「重建」——拖过的尺寸、已渲染的转录、
//     流式中的气泡、绑定过的监听全部原样保留，与改前「appendChild 搬两个节点」
//     逐字节同义。ctx.on 登记的全局监听随断开自动拆、重连自动挂回。

import { key } from './branding.js';
import state from './state.js';
import { sendWs, setAskFrameSink, onReconnect } from './ws.js';
import { activeCanvasEntry } from './canvas.js';
// 修复批 2026-10-31（作者令 ①）：ai 气泡渲染复用主会话渲染腿
// utils.renderMarkdownWithMath（代码块/表格/列表/数学全套，含 marked+KaTeX
// 护栏与 LRU）——渲染腿零重写；user 气泡维持 textContent 纯文本面。
import { renderMarkdownWithMath } from './utils.js';
import { isAskableFile } from './mdHtmlGate.js';
// 输入行为包批（2026-11-02 作者令「文字输入框的逻辑做成一个包，参考主 agent
// 会话窗口的输入模式」）：键盘/IME 组字态/发送判定/中断状态机 = input/composer.js
// 统一实现（主会话同源）——组字期 Enter 只确认候选不发送、组字结束后才发送、
// Shift+Enter 换行；生成中发送钮转中断钮（与主会话 send/stop 同律）。
import { bindComposerKeys, createTurnToggle, requestTurnInterrupt, isImeComposing } from './input/composer.js';
// 斜杠命令先审（ask 面三修③ 2026-11-11）：dock 发送此前把一切文本当 askQuestion
// 发送，`/model` 被误发成提问（叠加 dock 自动抢焦点 = 「/model 无反应」的成因链）。
// handleSlash = 主会话 send 同一单源（input.js 同构位调用它）。
import { handleSlash } from './input/slashCommands.js';
// 组件化范式试点批（ask-paradigm-a）：DOM 构形走 dom.js 的 h()/frag()（零 HTML 串），
// 元素注册与生命周期走 component.js 的 define()（light DOM，禁开阴影根）。
import { h, frag } from './dom.js';
import { define } from './component.js';

/**
 * 组件内部结构类名（单点声明；门禁 scripts/check-component-contract.mjs R4 的解析面）。
 * 对外只有一个方法面（open/close/syncSession）——**定义模块以外**的 web/js/** 文件
 * 拿这些类名当选择器即红（tests/** 除外：15 个既有 spec 直查它们是验收面，不是违规）。
 * @type {string[]}
 */
export const INTERNAL_CLASSES = [
  'ask-fab', 'ask-dock', 'ask-col', 'ask-colwrap', 'ask-input',
  'ask-bub', 'ask-send', 'ask-stop', 'ask-sess-dot',
  // 记事本批（2026-11-05）：同一组件的第二对节点，同样只在本模块内建/查。
  'note-fab', 'note-dock', 'note-body',
];

// ── 文案（i18n 键 = key('locale')，navController tt 同源）─────────────────
function tt(zh, en) {
  let lang = '';
  try { lang = localStorage.getItem(key('locale')) || ''; } catch { /* private mode */ }
  if (!lang) lang = document.documentElement.lang || navigator.language || '';
  return /^zh/i.test(lang) ? zh : en;
}

// ── 图标（lucide 同族线性图标；SVG 命名空间构形）────────────────────────
// 为什么不用 h()：dom.js 的 h() 走 document.createElement，只能造 **HTML 命名空间**
// 元素——`<svg>`/`<path>` 落在 HTML 命名空间里不参与 SVG 绘制（图标会整块消失）。
// 因此这里用一个同口径的微型构造器：createElementNS + setAttribute + appendChild，
// 同样零 HTML 串、零 innerHTML（门禁 R5 计数为 0）。
const SVG_NS = 'http://www.w3.org/2000/svg';

/**
 * 造一个 SVG 命名空间节点。
 * @param {string} tag SVG 标签名
 * @param {Record<string, string>} [attrs] 属性表（viewBox 等大小写敏感名原样写）
 * @param {Node[]} [children] 子节点
 * @returns {SVGElement}
 */
function svgNode(tag, attrs, children) {
  const node = document.createElementNS(SVG_NS, tag);
  if (attrs) for (const [name, value] of Object.entries(attrs)) node.setAttribute(name, value);
  if (children) for (const child of children) node.appendChild(child);
  return node;
}

const STROKE = { fill: 'none', stroke: 'currentColor', 'stroke-linecap': 'round', 'stroke-linejoin': 'round' };

const ICONS = {
  chat: () => svgNode('svg', { class: 'ask-fab-ic-chat', viewBox: '0 0 24 24', 'stroke-width': '2', ...STROKE },
    [svgNode('path', { d: 'M21 11.5a8.38 8.38 0 0 1-.9 3.8 8.5 8.5 0 0 1-7.6 4.7 8.38 8.38 0 0 1-3.8-.9L3 21l1.9-5.7a8.38 8.38 0 0 1-.9-3.8 8.5 8.5 0 0 1 4.7-7.6 8.38 8.38 0 0 1 3.8-.9h.5a8.48 8.48 0 0 1 8 8v.5z' })]),
  close: () => svgNode('svg', { class: 'ask-fab-ic-close', viewBox: '0 0 24 24', 'stroke-width': '2.2', ...STROKE },
    [svgNode('path', { d: 'm18 15-6-6-6 6' })]),
  send: () => svgNode('svg', { viewBox: '0 0 24 24', 'stroke-width': '2.4', ...STROKE },
    [svgNode('path', { d: 'M12 19V5M5 12l7-7 7 7' })]),
  // 中断钮（输入行为包批）：与主会话 #stop-btn 同语（lucide square 族）——红圆白方停标。
  stop: () => svgNode('svg', { viewBox: '0 0 24 24', 'stroke-width': '2.4', ...STROKE },
    [svgNode('rect', { x: '6', y: '6', width: '12', height: '12', rx: '2' })]),
  // 记事本批：便签/记事本语义（与 chat/close 同一线性图标族、同一 24 视框与
  // stroke-width 2 —— 18px 下读得出「一页带横线的纸」，不引入第二套图标语言）。
  note: () => svgNode('svg', { class: 'note-fab-ic', viewBox: '0 0 24 24', 'stroke-width': '2', ...STROKE },
    [
      svgNode('rect', { x: '4.5', y: '3.5', width: '15', height: '17', rx: '2.5' }),
      svgNode('path', { d: 'M8.5 8.5h7M8.5 12h7M8.5 15.5h4' }),
    ]),
};

/** @type {Map<string, {sid: string, fileName: string, msgs: Array<{role: string, text: string}>, stateSent: boolean, unread: boolean, busy?: boolean, lastFrameAt?: number}>} */
const sessions = new Map(); // key: absPath
let el = null; // 组件内部节点引用 { fab, dock, col, colwrap, inputRow, resize, input, send, stop }（建一次，跨宿主迁移复用）
let root = null; // 组件宿主元素 <nf-ask-dock>（迁移时搬它）
let wired = false; // 元素级监听/观察器已绑（节点复用 ⇒ 只绑一次）
let turnToggle = null; // 中断状态机（createTurnToggle；建一次，单 dock 对）
let currentFile = ''; // absPath of the active file entry ('' = 非文件卡)
let inited = false;
// 记事本批（2026-11-05）：当前草稿归属（absPath + 与 ask 同式的 sid）与落盘防抖拍。
let notePath = '';
let noteSid = '';
let noteTimer = 0;
// 持久化缺陷批（2026-11-05）：两层持久的状态位。本地镜像可用性 / 未落服务端的
// 脏编辑 / 最近一次推送内容（回执对账用）/ 服务端最近已知 mtime / 状态行 kind。
let noteLocalOk = true;
const noteEntries = new Map();
let noteWriteSequence = 0;
let noteStatus = '';

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

// ── 组件内部 DOM（建一次；跨宿主迁移复用同一对节点）──────────────────────

/** 入口钮（.ask-fab）：chat/close 两枚图标 + 未读绿点。 */
function buildFab() {
  return h('button', {
    type: 'button',
    class: 'ask-fab',
    title: tt('问这份文件（ask mode）', 'Ask this file (ask mode)'),
    'aria-label': tt('问这份文件', 'Ask this file'),
  }, [
    ICONS.chat(),
    ICONS.close(),
    h('span', { class: 'ask-sess-dot', 'aria-hidden': 'true' }),
  ]);
}

/**
 * 对话框（.ask-dock）：拖柄 + 气泡列 + 输入条。
 * v2.1 减法（作者令 2026-10-28 22:42）：输入条只留右侧发送钮；ghost 提示行 /
 * session 徽标不渲染。输入行为包批（2026-11-02）：textarea rows=1 承载 Shift+Enter；
 * + 中断钮 .ask-stop（busy 态与发送钮互换）。顶缘渐隐 = 内容级 mask（2026-11-04
 * ask-fade-content-opacity 批）——面板内不渲染任何渐隐元素，渐隐本体在 ask.css
 * .ask-col 的 mask-image。
 * 类序契约：发送/中断两钮的 className **首 token** 必须是身份钩子 .ask-send /
 * .ask-stop（canvas-ask-dock spec T6a 读首 token），其后才是主会话组件类
 * `ds-turn-btn ds-turn-btn--send|--stop`（design-system.css §①b 单源）。
 *
 * 组件化范式试点批：两个按钮的 class 串从标记属性改成 h() 的 attrs 落
 * setAttribute，**串内容逐字不变**。下面这两行是原标记形态的**逐字引文**（不是
 * 构造点，构造点是 buildDock 里的 h() 调用）：canvas-ask-resize.spec 的 c7 判据
 * 在**源码文本面**按这两串逐字核对「面板 DOM 挂同一组件类」，而该 spec 属本批
 * 不许改动的验收面 —— 故在此保留逐字引用，改 class 串必须两处同改：
 *   <button class="ask-send ds-turn-btn ds-turn-btn--send">
 *   <button class="ask-stop ds-turn-btn ds-turn-btn--stop">
 */
function buildDock() {
  // ── 单小边框批（作者令 2026-11-05 09:32「左上角那个拖动按钮，只要一个小边框
  //    就行，不要那个叠起来的正方形」）──
  // 旧 DOM 在把手内挂了一枚 `.ds-resize-grip`，内里三枚 `<i>` 各画一条 L 形
  // 描边（translate 2/4/6px、opacity .55/.35/.2）⇒ 与 ::before 的直角弧在同一
  // 角上共四条线，叠成「两层方块」。整族退役：**可见描边只剩 ::before 那一条
  // 直角弧**（形态单源 = design-system.css .ds-resize-handle::before），把手本体
  // 仍是 44×44 透明热区（HIG 下限不缩）。
  const handle = h('div', {
    class: 'ds-resize-handle',
    'data-ask-resize': true,
    role: 'separator',
    tabindex: '0',
    'aria-label': tt('拖拽调整面板大小', 'Resize panel'),
    title: tt('拖拽调整面板大小（←→ 改宽 / ↑↓ 改高）', 'Drag to resize (←→ width / ↑↓ height)'),
  });

  const input = h('textarea', {
    rows: '1',
    placeholder: tt('针对这份文件提问…', 'Ask about this file…'),
    'aria-label': tt('提问', 'Question'),
  });
  const send = h('button', {
    type: 'button',
    class: 'ask-send ds-turn-btn ds-turn-btn--send',
    'aria-label': tt('发送', 'Send'),
  }, [ICONS.send()]);
  const stop = h('button', {
    type: 'button',
    class: 'ask-stop ds-turn-btn ds-turn-btn--stop',
    'aria-label': tt('停止回答', 'Stop answering'),
  }, [ICONS.stop()]);

  return h('div', { class: 'ask-dock closed' }, [
    handle,
    // main（ask-fade-content-opacity 批）已把 .ask-veil 遮罩条退役——渐隐归
    // .ask-col 的 mask-image，本组件不复活任何渐隐元素。
    h('div', { class: 'ask-colwrap' }, [h('div', { class: 'ask-col' })]),
    // 输入条 = 毛玻璃唯一工具类 `.nf-glass`（glass-unify 批）：材质/描边/内高光/
    // 模糊全部来自 stand-tokens.css 的 --nf-glass-* 单源，本组件零本地材质声明。
    // `ask-input` 身份类保留（既有 spec 选择器），它在本文件只承载几何与焦点态。
    h('div', { class: 'ask-input nf-glass' }, [input, send, stop]),
  ]);
}

/**
 * 记事本入口钮（记事本批 2026-11-05）：class = `ask-fab note-fab` ——
 * **全部形态/材质/状态/手机档继承 .ask-fab**（glass.css 圆钮级浮件档成员 +
 * ask.css 的 48×48/圆/图标档/`.gate-off`），本批只加一条落位规则让它坐在 ask
 * 钮左邻（css/ask.css `.ask-fab.note-fab`）；DOM 顺序在 ask 钮之后（既有 spec
 * 以 `document.querySelector('.ask-fab')` 取 ask 钮，先者不动）。
 * 开态**不挂 `.open`**（那是 ask 钮的图标互换档）：本钮图标恒定、开态用
 * `aria-pressed` 表达——状态类不叠加，既有 `.ask-fab.open` 判据零歧义。
 */
function buildNoteFab() {
  return h('button', {
    type: 'button',
    class: 'ask-fab note-fab',
    title: tt('记事本（记录思路）', 'Notes'),
    'aria-label': tt('记事本', 'Notes'),
    'aria-pressed': 'false',
  }, [ICONS.note()]);
}

/**
 * 记事本浮窗（记事本批）：class = `note-dock` + `closed`。
 * 座位/开合曲线（锚定 100% 100% 的同一 transition）/宽档 `--ask-dock-w`/`.closed`/
 * `.is-resizing`/`.gate-off` 闸/手机档**全部以「成员」身份复用 `.ask-dock` 的既有
 * 规则**（ask.css 那些规则的选择器名单里加了 `.note-dock`，值仍是单源一份）；
 * 本批新增的只有内容盒 `.note-body`（材质 = glass.css 面级浮件档成员，与本组件
 * 输入条同源）与一个纯文本 textarea。
 * ⚠ 这里**刻意不挂 `.ask-dock` 身份类**：`.ask-dock.closed` /
 * `.ask-dock:not(.closed)` 是既有 15 个 spec 的判据选择器（如 ask-input-composer
 * 的 dockClosed），同一页出现第二个 `.ask-dock` 节点会让「ask 窗现在开没开」这类
 * querySelector 判断失真。复用走选择器成员，不走类名叠加。
 * 拖柄与 ask 面板**同一个类、同一段落位**（`.ds-resize-handle` + 左上外挂 14px，
 * 可见形态 = design-system.css 那一条 ::before 直角弧，单小边框批同源），区别只是
 * data 钩子（`data-note-resize`）——ask 的 `[data-ask-resize]` 保持唯一（既有 spec
 * 直查它，本批零触碰）。
 */
function buildNoteDock() {
  const handle = h('div', {
    class: 'ds-resize-handle',
    'data-note-resize': true,
    role: 'separator',
    tabindex: '0',
    'aria-label': tt('拖拽调整记事本大小', 'Resize note'),
    title: tt('拖拽调整记事本大小（←→ 改宽 / ↑↓ 改高）', 'Drag to resize (←→ width / ↑↓ height)'),
  });
  const text = h('textarea', {
    placeholder: tt('随手记点什么…', 'Jot something down…'),
    'aria-label': tt('记事本', 'Notepad'),
    spellcheck: 'false',
  });
  // 持久化缺陷批（2026-11-05）：存储面状态行（默认隐藏；只有在本地镜像不可用 /
  // 引擎断连 / 超上限时才出现，文案是**事实句**不教学不打路径）。
  const status = h('div', { class: 'note-status', 'data-note-status': true, role: 'status', 'aria-live': 'polite' });
  status.hidden = true;
  return h('div', { class: 'note-dock closed' }, [
    handle,
    h('div', { class: 'note-body' }, [text]),
    status,
  ]);
}

/** 造内部 DOM + 绑元素级监听（只跑一次；节点跨宿主迁移复用）。 */
function ensureInner() {
  if (el) return;
  const fab = buildFab();
  const noteFab = buildNoteFab();
  const dock = buildDock();
  const noteDock = buildNoteDock();
  // 尺寸面（两个面板 = 同一段状态机的两个入参，见头注「记事本批」）：
  //   ask  = 列高受控（--ask-col-maxh），底部件输入条占高；
  //   note = 本体高受控（--note-h），无底部件。
  /** @type {Surface} */
  const askSurface = {
    name: 'ask',
    panel: dock,
    handle: dock.querySelector('[data-ask-resize]'),
    track: dock.querySelector('.ask-col'),
    footer: dock.querySelector('.ask-input'),
    varW: '--ask-dock-w',
    varH: '--ask-col-maxh',
    onApply: null,
    lastSize: null,
  };
  /** @type {Surface} */
  const noteSurface = {
    name: 'note',
    panel: noteDock,
    handle: noteDock.querySelector('[data-note-resize]'),
    track: noteDock.querySelector('.note-body'),
    footer: null,
    varW: '--ask-dock-w',
    varH: '--note-h',
    onApply: null,
    lastSize: null,
  };
  el = {
    fab,
    noteFab,
    dock,
    noteDock,
    col: dock.querySelector('.ask-col'),
    colwrap: dock.querySelector('.ask-colwrap'),
    inputRow: dock.querySelector('.ask-input'),
    resize: askSurface.handle,
    input: dock.querySelector('textarea'),
    send: dock.querySelector('.ask-send'),
    stop: dock.querySelector('.ask-stop'),
    noteResize: noteSurface.handle,
    noteBody: noteSurface.track,
    noteText: noteDock.querySelector('textarea'),
    noteStatus: noteDock.querySelector('[data-note-status]'),
    askSurface,
    noteSurface,
  };
  bindResize(el.askSurface);
  bindResize(el.noteSurface);
  // 缩放空白批（2026-11-05 09:32）：截断线的 dock ResizeObserver 随状态机一并
  // 退役——底缘收口已是 .ask-col 的百分比 mask（与面板高无关），不再需要任何
  // 跟随实测盒的写入点（见 syncTruncLine 退役注）。
  wireElements();
}

/** 组件模板：返回**同一批节点**（frag 只是把它们搬进 fragment，不复制）。 */
function template() {
  ensureInner();
  return frag(el.fab, el.noteFab, el.dock, el.noteDock);
}

/**
 * 挂载组件宿主：在 mountHost() 决定的宿主里确保 `<nf-ask-dock>` 在场（幂等）。
 * appendChild 那一拍内 connectedCallback 同步跑完（template → mount），所以返回时
 * el 引用已就绪。
 * @returns {any} 组件宿主元素（null = 宿主面不存在）
 */
function mountRoot() {
  const host = mountHost();
  if (!host) return null;
  let node = host.querySelector(':scope > nf-ask-dock');
  if (!node) {
    node = document.createElement('nf-ask-dock');
    host.appendChild(node);
  }
  root = node;
  return node;
}

// ── 拖拽改尺寸（作者令 2026-11-04「允许用户交互拖拽调整对话框大小（拖柄可见、
//    热区 ≥44px，按 Apple HIG）」）─────────────────────────────────────────
// 设计取舍（样张 canvas-ask-resize/mockup.html 即基线）：
//   · **一个把手**在面板左上角，同时改宽（向左拖 = 变宽）与高（向上拖 = 变高）：
//     面板右缘/下缘是钉位（右下角锚定），所以只有左上角是「自由角」——放一个把
//     手就够，不必条形分隔件（少一个可点面 = 少一处误触）。
//   · **桌面与手机同一段实现、同一组 CSS 变量**（宽 = --ask-dock-w，列高 =
//     --ask-col-maxh）。两档的差别只来自视口实测算出的上下限，不是两套代码。
//   · 上下限**每次手势现算**（不缓存）：面板钉位随视口/手机底 dock 变化，
//     缓存的极限值迟早过期。四条硬约束：① 宽不小于 --ds-resize-min-w；
//     ② 左缘不越过视口 --ds-resize-safe 缝；③ 面板顶距视口顶不小于
//     --ds-resize-top-guard（44 = 拖柄热区边长）；④ 把手**不与画布头带浮钮
//     叠压**（`#canvas-head-close` / `#deck-fab` 是 z 250 的 body 级 fixed，
//     压在面板之上 —— 把手一旦钻进去，那一段就是死区，44 热区名存实亡）
//     ⇒ 把手本体永远 100% 可点、且输入条与底部安全区在面板自己的 flex 列里
//     恒在最下方，永不被列压住。
//   · 一切形态/配色走 --ds-* 槽（design-system.css `.ds-resize-handle`），本段只
//     做几何与状态机。
// 组件化范式试点批：**本段原样保留命令式形态**（直接读写 el 与 CSS 变量，不经过
// 渲染树）——它正是试点要验证的「组件范式与命令式几何状态机共存」那一面。

// 记事本批（2026-11-05）：**本段是 ask 面板与记事本共用的唯一一份尺寸状态机**
// ——「被拖的盒子」抽成尺寸面 surface（见 ensureInner 的两个入参），本段只按
// surface 读几何/写变量，不认识任何一个具体的面板。

/**
 * 尺寸面（surface）：拖拽改尺寸的最小描述。
 *   · name   = 该面的持久化身份（`'ask'` | `'note'`，见 sizeKeyOf ⇒ 键
 *     `dock:size:<name>`；缺省按 'ask' 兜底）。**运行时真值，不是注解字段**：
 *     两个面板共用同一段尺寸状态机，唯一区分就是它。
 *   · panel  = 面板本体（绝对定位、右/下缘钉位 ⇒ 自由角只有左上）
 *   · handle = 左上角拖柄（design-system.css `.ds-resize-handle` 单源）
 *   · track  = 高度受控的可变盒（ask = 气泡列；note = 记文本体）
 *   · footer = 面板内固定高、不可被列压住的底部件（ask = 输入条；note = 无）
 *   · varW/varH = 落尺寸的两条 CSS 变量名（各面自己的）
 *   · onApply = 落尺寸后的钩子（ask 截断线状态机已随缩放空白批退役 ⇒ 两面皆 null；
 *     保留这个槽是为了下一次要写派生量时有单点落位，不必再改状态机）
 *   · lastSize = 最近一次落下的尺寸（视口/层切换时按同一目标重夹）
 * @typedef {{name: string, panel: any, handle: any, track: any, footer: any,
 *   varW: string, varH: string, onApply: (() => void)|null,
 *   lastSize: {w: number, h: number}|null}} Surface
 */

/** 读某尺寸面的一个 px 令牌（缺/非数值回落 fallback）。 */
function dsPx(surface, name, fallback) {
  const raw = surface && surface.panel ? getComputedStyle(surface.panel).getPropertyValue(name) : '';
  const v = parseFloat(raw);
  return Number.isFinite(v) ? v : fallback;
}

/** 现算上下限（手势每次重算；见上注三条硬约束）。 @param {Surface} surface */
function resizeLimits(surface) {
  const r = surface.panel.getBoundingClientRect();
  const gap = parseFloat(getComputedStyle(surface.panel).rowGap) || 7; // 面板的列缝
  const inputH = surface.footer ? surface.footer.getBoundingClientRect().height : 0;
  const safe = dsPx(surface, '--ds-resize-safe', 12);
  const topGuard = dsPx(surface, '--ds-resize-top-guard', 44);
  const minW = dsPx(surface, '--ds-resize-min-w', 260);
  const minH = dsPx(surface, '--ds-resize-min-h', 120);
  // 把手外挂量（ask.css 把它挂到面板左上角外侧 14px）：**上限必须把它算进去**，
  // 否则面板左缘贴到安全缝时把手会被顶出视口（本批实测 b2c 抓到：dock.left=12
  // 而 handle.left=−2 ⇒ 命中全丢）。外挂量从 DOM 实测，不在 JS 里复制 14。
  const hr = surface.handle ? surface.handle.getBoundingClientRect() : null;
  const overshoot = hr && hr.width > 0 ? Math.max(0, r.left - hr.left) : 0;
  // 左侧/顶侧硬界：桌面 = **#canvas-panel 的内容框**（面板 overflow:hidden +
  // 顶行 z 30 > dock 24「永不入顶行」）——拖过面板上缘会被裁剪，把手随即离开
  // 命中面（本批实测 b2c：dock.top=44 时把手落进顶行带，五点全丢）。手机层
  // dock 是 body 级 fixed（offsetParent = null），走视口安全缝。
  let minLeft = safe;
  let minTop = topGuard;
  const host = /** @type {HTMLElement|null} */ (surface.panel.offsetParent);
  if (host && host.id === 'canvas-panel') {
    const pr = host.getBoundingClientRect();
    const inset = 8; // 面板圆角 20 的内切安全档（把手左上角落在圆角内）
    minLeft = Math.max(minLeft, pr.left + inset);
    minTop = Math.max(minTop, pr.top + inset);
  }
  // 顶侧再让开画布自己的头带（.canvas-header 浮层胶囊 + #canvas-tab-bar）：
  // 实测 b2c 抓到把手顶缘落到 44 时被 `#canvas-head-close.g2-fab` 压住 ⇒ 命中全丢。
  // 以 #canvas-content 的上缘为「内容区起点」（它就在头带之下），把手必须完整
  // 落在其下 —— 与 dock z 24 < 顶行 30「永不入顶行」同一口径。
  const content = surface.panel.offsetParent && document.getElementById('canvas-content');
  if (content && content.offsetParent !== null && !document.body.classList.contains('mobile-layer')) {
    const cr = content.getBoundingClientRect();
    if (cr.height > 0) minTop = Math.max(minTop, cr.top + 4);
  }
  // 宽：右缘是钉位 ⇒ 左缘（含把手外挂）只需不越过左侧硬界。
  const maxW = Math.max(minW, Math.floor(r.right - (minLeft + overshoot)));
  // 高：底缘是钉位 ⇒ 列高上限 = 底缘 − 顶侧硬界 − 输入条 − 列缝
  //（顶侧硬界 44 > 把手外挂 14 ⇒ 把手顶缘恒完整在界内）。
  const maxHAt = (topLimit) => Math.max(minH, Math.floor(r.bottom - (topLimit + overshoot) - inputH - gap));
  /** 给定落定宽度 W 的高度上限：除上述顶侧硬界外，再让开与之叠压的头带浮钮。 */
  const maxHFor = (W) => {
    let cap = maxHAt(minTop);
    // 把手在宽度 W 下的水平跨度（右缘钉位 ⇒ 左缘 = 右缘 − W − 外挂）。
    const hl = r.right - W - overshoot;
    const hrr = hl + dsPx(surface, '--ds-resize-hit', 44);
    for (const ob of headerObstacles()) {
      // 只在与浮钮**水平相交**时让位：面板没拖到那一侧就零代价，
      // 不为不存在的冲突白扣最大高度（否则窄面板会被凭空压矮一截）。
      if (hrr > ob.left + 1 && hl < ob.right - 1) cap = Math.min(cap, maxHAt(ob.bottom + 2));
    }
    return cap;
  };
  return { minW, maxW, minH, maxH: maxHAt(minTop), maxHFor };
}

/** 画布头带浮钮盒（可见者才计入；display:none 的 0 盒自动跳过 —— 手机层两件都隐）。 */
function headerObstacles() {
  const out = [];
  for (const id of ['canvas-head-close', 'deck-fab']) {
    const n = document.getElementById(id);
    if (!n) continue;
    const b = n.getBoundingClientRect();
    if (b.width > 0 && b.height > 0) out.push({ left: b.left, right: b.right, bottom: b.bottom });
  }
  return out;
}

/** 落尺寸（夹取后写该面两条 CSS 变量；返回实际生效值，供断言/复位用）。
 * @param {Surface} surface @param {number} w @param {number} hgt */
function applySize(surface, w, hgt) {
  if (!surface || !surface.panel) return null;
  const L = resizeLimits(surface);
  const W = Math.round(Math.min(Math.max(w, L.minW), L.maxW));
  // 高度上限依赖**落定宽度**（把手位置决定它与哪个浮钮叠压）⇒ 先定宽再夹高，
  // 一遍算清，不做「夹宽→夹高→回头再夹宽」的收敛循环。
  const H = Math.round(Math.min(Math.max(hgt, L.minH), L.maxHFor(W)));
  surface.panel.style.setProperty(surface.varW, W + 'px');
  surface.panel.style.setProperty(surface.varH, H + 'px');
  surface.lastSize = { w: W, h: H };
  if (surface.onApply) surface.onApply();
  return surface.lastSize;
}

// ── 尺寸偏好持久（持久化缺陷批 2026-11-05）───────────────────────────────
// 作者批要求「resize 尺寸是否同批持久」：**改前两面板都不持久**（applySize 只写
// 内存里的 CSS 变量，刷新即回默认档）——ask 没有先例可抄，所以本批给**同一段
// 实现两个入参**（与 bindResize/applySize 同一条复用律，零第二套）。
// 键 = `dock:size:<ask|note>`（branding 命名空间）：**尺寸是人的界面偏好，不是
// 文件的属性**——随文件会让每次切卡窗口尺寸乱跳（同一块屏、同一双手想要的仍是
// 那一档尺寸），故全局一份，不挂 sid。
// 写点 = 拖拽结束（pointerup/cancel）与键盘改尺寸；读点 = 首次挂载与每次开窗
// （收起态带 scale(0.6)，bbox 不能当夹取输入 ⇒ 入场后按实测几何重夹一档）。
const SIZE_STORE_PREFIX = 'dock:size:';

/** 某尺寸面的持久键（name 缺省 = ask：先例面）。 */
function sizeKeyOf(surface) {
  return key(SIZE_STORE_PREFIX + (surface && surface.name ? surface.name : 'ask'));
}

/** 读尺寸偏好（无/坏/存储不可用 ⇒ null = 走 CSS 默认档）。 */
function loadSize(surface) {
  try {
    const raw = localStorage.getItem(sizeKeyOf(surface));
    if (!raw) return null;
    const v = JSON.parse(raw);
    if (!v || typeof v.w !== 'number' || typeof v.h !== 'number') return null;
    if (!(v.w > 0) || !(v.h > 0)) return null;
    return { w: v.w, h: v.h };
  } catch { return null; }
}

/** 写尺寸偏好（静默降级：存不下 = 回到默认档，内容另有持久层故不提示）。 */
function persistSize(surface) {
  if (!surface || !surface.lastSize) return;
  try {
    localStorage.setItem(sizeKeyOf(surface), JSON.stringify({ w: surface.lastSize.w, h: surface.lastSize.h }));
  } catch { /* 存储不可用：尺寸退回默认档（内容持久面不受影响，不弹提示） */ }
}

/** 恢复尺寸偏好（开窗/首挂时调；入场动效落定后按实测几何重夹，越界不越视口）。 */
function restoreSize(surface) {
  if (!surface || !surface.panel) return;
  const s = loadSize(surface);
  if (!s) return;
  surface.panel.style.setProperty(surface.varW, s.w + 'px');
  surface.panel.style.setProperty(surface.varH, s.h + 'px');
  surface.lastSize = { w: s.w, h: s.h };
  setTimeout(() => {
    if (!el || surface.panel.classList.contains('closed')) return;
    applySize(surface, s.w, s.h); // 夹取（换屏/转屏后同一目标不越界）
    persistSize(surface); // 夹取后的落定值回写（下次开窗直接对）
  }, 360);
}

/**
 * 缩放空白批（作者令 2026-11-05 09:32「现在调整窗口大小会导致对话框和内容直接
 * 有一大段空白」）：**截断线状态机整体退役**。
 * 退役面 = `syncTruncLine()` 与它写的三条变量（--ask-trunc-h / --ask-trunc-from
 * / --ask-col-overhang）+ 只服务于它的 dock ResizeObserver。
 * 病根（本批改前实测，见批报告）：截断线 = 面板高 × 50%，却画在**列**上；
 * 面板高含输入条与列缝 ⇒ 列底那一截恒被 alpha 0 遮掉，而最新气泡锚在列底
 * （flex-end）⇒ 内容与输入条之间恒有一大段空白（默认档 208px）；且它是首帧/
 * RO 触发的缓存 px，窗口尺寸变了要等下一次 RO 才改写（实测：三次 resize 里
 * 有两次仍读 108px 旧值）。
 * 现态：底缘收口 = .ask-col 自己的百分比 mask 停点（ask.css 注释），与面板高
 * 彻底解耦 ⇒ 本函数无存在必要，删掉等于消灭这一类 bug 的产生面（不是把缓存
 * 刷新得更勤）。列/输入条的间距恒 = dock 的 row-gap（--ds-space-1h）。
 */


/** 拖柄状态机（pointer capture + 键盘；桌面/手机/两个面板同一段）。
 * @param {Surface} surface */
function bindResize(surface) {
  const handle = surface.handle;
  if (!handle) return;
  let active = false;
  let grabX = 0, grabY = 0, baseW = 0, baseH = 0;

  handle.addEventListener('pointerdown', (e) => {
    if (e.button !== undefined && e.button !== 0) return;
    const r = surface.panel.getBoundingClientRect();
    active = true;
    grabX = e.clientX;
    grabY = e.clientY;
    baseW = r.width;
    // 基线高 = 可变盒的**实测**高（内容短时 < 声明档，用 CSS 声明值会让第一次
    // 拖动就跳一大截）。
    baseH = surface.track.getBoundingClientRect().height;
    try { handle.setPointerCapture(e.pointerId); } catch { /* 合成事件无 pointerId */ }
    handle.dataset.resizing = 'true';
    surface.panel.classList.add('is-resizing');
    e.preventDefault();
    e.stopPropagation();
  });

  handle.addEventListener('pointermove', (e) => {
    if (!active) return;
    // 右/下钉位 ⇒ 鼠标向左移（dx < 0）变宽、向上移（dy < 0）变高。
    applySize(surface, baseW + (grabX - e.clientX), baseH + (grabY - e.clientY));
    e.preventDefault();
  });

  const end = (e) => {
    if (!active) return;
    active = false;
    if (handle.hasPointerCapture && e && e.pointerId !== undefined) {
      try { handle.releasePointerCapture(e.pointerId); } catch { /* 已释放 */ }
    }
    delete handle.dataset.resizing;
    // 液态形变批（作者令 2026-11-07 12:22）：`is-resizing` 的摘除必须**推迟一帧**。
    // 本帧内 pointermove 已经把 --ask-dock-w / --note-h 写到落定值；若在同一帧里
    // 把类摘掉，样式重算只会看到「过渡属性已恢复 + 尺寸变了一大段」这一个事实 ——
    // 浏览器于是补一段开合过渡（实测：canvas-note-panel N4e 拖 −120/−140 后 140ms
    // 读到 358.9 而非落定的 380，增量-100 判据忽绿忽红）。推迟到下一帧：本帧重算
    // 仍命中「.is-resizing 期零补间」，尺寸直落；下一帧摘类时值不再变化 ⇒ 不起补间。
    // 期间若又开始新一次拖拽（active 复位为 true），本 rAF 让位不摘。
    const panel = surface.panel;
    requestAnimationFrame(() => { if (!active) panel.classList.remove('is-resizing'); });
    persistSize(surface); // 持久化缺陷批：拖拽落定即存尺寸偏好（拖拽中不写）
  };
  handle.addEventListener('pointerup', end);
  handle.addEventListener('pointercancel', end);

  // 键盘同源（←→ 改宽 / ↑↓ 改高）：拖柄是 tabindex=0 的 separator，键盘用户
  // 拿得到的必须是**同一个**尺寸状态机，不是第二套。
  handle.addEventListener('keydown', (e) => {
    const step = dsPx(surface, '--ds-resize-step', 16);
    const r = surface.panel.getBoundingClientRect();
    const cur = surface.lastSize || { w: r.width, h: surface.track.getBoundingClientRect().height };
    let { w, h: hh } = cur;
    if (e.key === 'ArrowLeft') w += step;
    else if (e.key === 'ArrowRight') w -= step;
    else if (e.key === 'ArrowUp') hh += step;
    else if (e.key === 'ArrowDown') hh -= step;
    else return;
    e.preventDefault();
    applySize(surface, w, hh);
    persistSize(surface); // 键盘与指针同一份偏好（同一个落定即存口径）
  });
}

/** 视口/座位变化后按同一目标重夹（拖到极限再转屏不可把面板顶出视口）。 */
function reclampSize() {
  if (!el) return;
  for (const surface of [el.askSurface, el.noteSurface]) {
    if (surface && surface.lastSize) applySize(surface, surface.lastSize.w, surface.lastSize.h);
  }
}

// ── 元素级监听（只绑一次：节点跨宿主迁移复用，重绑会叠双份）─────────────────

function wireElements() {
  el.fab.addEventListener('click', (e) => { e.stopPropagation(); toggle(); });
  el.dock.addEventListener('click', (e) => e.stopPropagation());
  el.dock.addEventListener('pointerdown', (e) => e.stopPropagation());
  // 中断状态机实例（包工厂）：busy()/idle() 驱动发送↔中断钮互换（applyTurnChrome
  // 与主会话 setBusy/clearBusy 同一实现）。
  turnToggle = createTurnToggle({
    sendBtn: /** @type {HTMLElement} */ (el.send),
    stopBtn: /** @type {HTMLElement} */ (el.stop),
  });
  el.send.addEventListener('click', () => submit());
  el.stop.addEventListener('click', () => stopAsk());
  // 多行草稿自动长高（主会话 textarea 同式：auto → scrollHeight，封顶留 dock 紧凑形态）。
  el.input.addEventListener('input', () => {
    el.input.style.height = 'auto';
    el.input.style.height = Math.min(el.input.scrollHeight, 120) + 'px';
  });
  // 键盘/IME/发送判定 = 主会话同一实现（包装配；组字期 Enter 只确认候选）。
  bindComposerKeys(el.input, { onSend: () => { submit(); } });
  el.input.addEventListener('focus', syncSeat);
  // ── 记事本批（2026-11-05）：入口钮 / 浮窗自身的事件 ─────────────────────
  // 开合与 ask 同律：钮再点即收；窗内点击/按下不外溢（画布空白点击的收起腿只
  // 认 #canvas-content 本体，这里再挡一道，与 ask dock 同写法）。
  el.noteFab.addEventListener('click', (e) => { e.stopPropagation(); toggleNote(); });
  el.noteDock.addEventListener('click', (e) => e.stopPropagation());
  el.noteDock.addEventListener('pointerdown', (e) => e.stopPropagation());
  // 纯文本草稿：write-through 防抖 250ms（输入即存，切换卡/关窗/失焦都 flush）。
  el.noteText.addEventListener('input', noteInputChanged);
  el.noteText.addEventListener('blur', flushNote);
  // 持久化缺陷批：同浏览器多标签页直通（另一标签写同一文件 ⇒ 本页未编辑即采纳）。
  // 浏览器原生 storage 事件只在**其它**标签页写时派发，本页自己写不回流，无回环。
  window.addEventListener('storage', onNoteStorage);
}

/** 全局监听（每次连入登记；断开由基座 ctx.on 自动拆——迁移不留双份）。 */
function wireGlobals(ctx) {
  // 收起三通路之二：Esc。（输入行为包批：组字期 Esc = 输入法取消候选——键属
  // 输入法，不收起 dock；键位契约与主会话一致——主会话 Esc 同样让渡组字键。）
  // 记事本批：两个浮窗同一段判据，开着的那个先收——组字态取**各自**的输入面。
  ctx.on(document, 'keydown', (e) => {
    if (!el) return;
    const composer = isNoteOpen() ? el.noteText : el.input;
    if (isImeComposing(e, composer)) return;
    if (e.key !== 'Escape' || !isCanvasVisible()) return;
    if (isNoteOpen()) { closeNote(); return; }
    if (isOpen()) close();
  });
  ctx.on(document, 'canvas-tab-switched', onTabSwitch);
  ctx.on(document, 'canvas-tab-closed', onTabClosed);
  // 持久化缺陷批：页面关闭/隐藏前把最后一段输入同步落本地镜像（服务端推送会随
  // 连接一起走，下次打开由「本地 ts 更新 ⇒ 回推」对账补齐，不丢字）。
  ctx.on(window, 'pagehide', () => { flushNote(); });
  ctx.on(window, 'resize', () => { syncSeat(); reclampSize(); });
  // 批⑨：手机层根页切换（nf:mobile-nav）重落可见性闸——离开 canvas 页即隐。
  ctx.on(document, 'nf:mobile-nav', () => syncGate());
  // 批⑩：阅读器底栏挂载完成 → 座位重算（底栏入角落浮件占用集，见 syncSeat）。
  ctx.on(document, 'nf-reader-mounted', () => syncSeat());
  // 收起三通路之一：点击画布空白（#canvas-content 本体 = 无浮层处；卡片内
  // 点击/文本选择不受扰——panes/浮件自有 target）。
  const content = document.getElementById('canvas-content');
  if (content) {
    ctx.on(content, 'click', (e) => {
      if (e.target !== content) return;
      if (isOpen()) close();
      if (isNoteOpen()) closeNote(); // 记事本批：同一条收起通路
    });
  }
}

// ── 一次性观察器（应用级生命周期；不问组件连接态）─────────────────────────

function wireObserversOnce() {
  // 批⑨：手机层开/关（body.mobile-layer 起落）迁移组件宿主——同一对元素在
  // #canvas-panel（absolute 锚面板）与 body（fixed 钉视口）间搬移。搬的是
  // `<nf-ask-dock>` 本身；基座的断开/连入走「同一对节点」路径（见 template），
  // 因此尺寸/转录/流式气泡/监听全部原样保留。
  if (typeof MutationObserver === 'function') {
    let mobileOn = document.body.classList.contains('mobile-layer');
    new MutationObserver(() => {
      const now = document.body.classList.contains('mobile-layer');
      if (now === mobileOn || !root) return;
      mobileOn = now;
      const host = mountHost();
      if (host && root.parentElement !== host) host.appendChild(root);
      syncGate();
      syncSeat();
      reclampSize(); // 层切换 = 钉位/视口语境变化，已拖过的尺寸按新上下限重夹
    }).observe(document.body, { attributes: true, attributeFilter: ['class'] });
  }
  if (typeof ResizeObserver === 'function') {
    // 画布开合（宽度过渡）会移动右下角座位——面板尺寸变化即重算。
    const panel = document.getElementById('canvas-panel');
    if (panel) new ResizeObserver(() => syncSeat()).observe(panel);
    // ── 气泡穿入输入条批（作者令 2026-11-05 16:50）─────────────────────────
    // 「气泡消息要能从输入框底下穿过，截止位置为输入框中间部分」。
    // 几何契约：气泡列的**盒底** = 输入条垂直中线（见 ask.css .ask-colwrap
    // 的负 margin），输入条以更高 z 序盖在气泡上 ⇒ 视觉上气泡钻到输入条下面
    // 被它截住，而不是在它上面 6px 就断层。
    // 中线随内容变（textarea 从 1 行长到 120px、手机档 44/48 钮、安全区内距），
    // 所以不能写死 px —— 由本 RO 实测输入条高度写 `--ask-under = h / 2`。
    const measureUnder = () => {
      if (!el || !el.inputRow) return;
      // 下一帧再量：RO 回调可能落在「输入条刚插入、发送钮/字号还没落位」的那一拍，
      // 量到的是半成品高度（实测 34.8 而非 58 ⇒ 中线偏 6px）。
      requestAnimationFrame(() => {
        // ⚠ 必须用 offsetHeight（布局高度），不能用 getBoundingClientRect()：
        // 偏移/位移腿（液态形变批起：收起态玻璃面 = 位移到 fab 圆心的种子）会让
        // bbox 返回**变换后**的几何，量到的不是输入条布局高 ⇒ 中线偏。
        // 而 ResizeObserver 观察的是 border-box（不受 transform 影响），高度不会
        // 再变 ⇒ 这个错值不会被第二次回调纠正。布局高度对此免疫。
        //
        // 液态形变批（作者令 2026-11-07 12:22）：收起态输入条 = --ask-fab-size
        // 正圆种子，展开首帧还在形变中途 —— 这两段量到的都不是稳态输入条高度，
        // 写了会把气泡列底缘垫错。稳态（无 .closed）才写，形变结束后 RO 会再响。
        if (!el || !el.dock || el.dock.classList.contains('closed')) return;
        const h = el.inputRow.offsetHeight;
        if (h > 0) el.dock.style.setProperty('--ask-under', (h / 2).toFixed(2) + 'px');
      });
    };
    if (el && el.inputRow) {
      measureUnder();
      new ResizeObserver(measureUnder).observe(el.inputRow);
    }
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
    // 持久化缺陷批：断连期间没推上去的记事本编辑在重连后补推（含「本机离线写过
    // 内容」这一常见形态——不补推就等于那个文件在引擎侧永远是空的）。
    resyncNotesAfterReconnect();
  });
}

/**
 * 组件挂载钩子（基座在 connectedCallback 里调用；宿主迁移时也会再进一次）。
 * @param {any} host 组件宿主 `<nf-ask-dock>`（实例方法面挂在这里）
 * @param {any} ctx 基座实例上下文（ctx.on 登记的监听随断开自动拆）
 */
function onMount(host, ctx) {
  root = host;
  // 对外方法面：**只此三个**（语义 = 原 open / close / syncToActiveTab）。
  // 内部结构（类名/节点）不出组件门——外部一律走这三条腿。
  host.open = open;
  host.close = close;
  host.syncSession = syncToActiveTab;
  const reattached = wired; // 二次连入 = 宿主迁移（手机层 ↔ 桌面层）
  if (!wired) {
    wired = true;
    wireObserversOnce();
  }
  wireGlobals(ctx);
  if (!reattached) {
    // 持久化缺陷批：首挂即恢复尺寸偏好（两个面板同一段实现；收起态只落变量，
    // 入场后再按实测几何重夹——见 restoreSize 注）。
    restoreSize(el.askSurface);
    restoreSize(el.noteSurface);
    syncSeat();
    return; // 首挂：与改前 mount() 尾部逐字同义
  }
  // 宿主迁移后补一拍（同一批节点、同一份会话状态；全部幂等）
  syncGate();
  syncUnreadDot();
  syncSeat();
  reclampSize();
  if (isOpen()) {
    render();
  }
  if (turnToggle) {
    const s = activeSession();
    if (s && s.busy) turnToggle.busy();
    else turnToggle.idle();
  }
}

// 组件注册（light DOM；tag 必须 nf- 前缀，由 component.js.define 硬闸）。
define({ tag: 'nf-ask-dock', template, mount: onMount });

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
  // 记事本批：同一闸同一拍（入口钮 + 窗一起摘——非文件卡/阅读面板开/非 canvas 页
  // 零残留；`.ask-fab.gate-off` / `.ask-dock|.note-dock.gate-off` 三条既有规则覆盖）。
  el.noteFab.classList.toggle('gate-off', off);
  el.noteDock.classList.toggle('gate-off', off);
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
  syncNoteForFile(); // 记事本批：切卡即切记事本（每文件一份草稿；旧草稿先 flush）
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

// ── 座位（单行排布批 2026-11-01 作者令 16:18「功能钮靠左、ask 单独靠右，
//    一行两端对齐；对话框落在按钮行上一行」：文档工具簇已左锚化（split.css
//    .canvas-source-toggle 注）——与右缘 fab 不再同角，簇实测让位整腿退役；
//    fab 与簇行共中线。canvas 控件尺寸对齐批 2026-11-02（作者追加令 09:41）：
//    簇钮 40→48 ⇒ pane 底 16+壳底 8 → 面板系行带 [24..72]、中线 48px；fab 48
//    时 bottom = 48−24 = **24**（原 22 是 44 钮的旧中线值，中线本身不动）。
//    仍需让位的只剩通栏底栏 = 电子书阅读器 nav（fab 抬其上，输入条联动同距）。
//    移动端让位切换条同口径。）

function syncSeat() {
  if (!el) return;
  const mobile = document.body.classList.contains('mobile-layer');
  if (mobile) {
    // 批⑨：手机层 = body 级 fixed（ask.css 移动档钉位：底 dock 上方），
    // 面板锚让位计算整腿退役。
    // 批⑩：活动卡挂电子书阅读器时，底栏 ⋯ 右端与 fab 同角——fab 抬到底栏
    // 上方（nf-reader-mounted 触发本重算；reader-p1 移动触滑腿实证拦截）；
    // 输入条跟随同距（单行排布批 2026-11-01：dock 一并抬，钉 fab 上方）。
    const pane = document.querySelector('.canvas-tab-pane.active');
    const nav = pane && (/** @type {HTMLElement|null} */ (pane.querySelector('.epub-reader-nav')));
    if (nav && nav.offsetParent !== null) {
      const nr = nav.getBoundingClientRect();
      if (nr.width > 0 && nr.height > 0) {
        const fabBottom = Math.max(10, window.innerHeight - nr.top + 8);
        el.fab.style.bottom = fabBottom + 'px';
        el.dock.style.bottom = (fabBottom + 48 + 10) + 'px';
        // 记事本批：两个入口钮同一行同一中线、两个窗同一条「fab 上一行」——
        // 让位值一份，跟着 fab/dock 一起抬（不是第二套座位计算）。
        el.noteFab.style.bottom = fabBottom + 'px';
        el.noteDock.style.bottom = (fabBottom + 48 + 10) + 'px';
        return;
      }
    }
    // 单行排布批（2026-11-01）：文档工具簇（查找/源码/引用）左锚化后与 body 级
    // fab（右缘钉位）异侧分行零交叠——簇实测让位腿退役；fab 回 css 钉位。
    el.fab.style.bottom = '';
    el.dock.style.bottom = '';
    el.noteFab.style.bottom = '';
    el.noteDock.style.bottom = '';
    return;
  }
  const panel = document.getElementById('canvas-panel');
  if (!panel) return;
  const panelRect = panel.getBoundingClientRect();
  // 基准 = 与左锚文档工具簇行共中线。件3 批（2026-11-05）：中线改**实测派生**——
  // 簇钮档从 48 收敛回顶部圆钮档 40（--cv-size-circle-lg）后「面板系中线 48 − fab
  // 半高 24 = 24」这条写死的中线数学会漂 4px（T1f 共中线判据 ≤2.5px 实测 dy=4 变红）。
  // 现口径：中线 = 面板底 → 簇钮盒中线（实测），fab 底距 = 中线 − fab 半高 ⇒ 尺寸档
  // 再改也不会漂；簇钮缺席（无活动内容页签）时按 token 兜底 壳底垫 8 + 行底距 16 + 半钮高。
  const fabH = (el.fab && el.fab.getBoundingClientRect().height) || 48;
  const rowBtn = panel.querySelector('.canvas-source-toggle, .canvas-ref-select, .canvas-find-toggle, .canvas-refresh-toggle');
  const rowR = rowBtn ? rowBtn.getBoundingClientRect() : null;
  const tokenSize = parseFloat(getComputedStyle(panel).getPropertyValue('--cv-size-circle-lg')) || 40;
  const rowMid = rowR && rowR.height > 0 ? panelRect.bottom - (rowR.top + rowR.height / 2) : (24 + tokenSize / 2);
  const FAB_BOTTOM_BASE = Math.max(8, Math.round(rowMid - fabH / 2));
  let bottomPx = FAB_BOTTOM_BASE;
  // 通栏底栏（可见者取最上沿）：电子书阅读器底栏——批⑩ 2026-10-31：epub/mobi/
  // azw3 可问面 fab 上阅读器页（底栏 ⋯ 钮在右端，fab 不抬必拦截其命中，
  // reader-p1 A5 实证）。左锚工具簇与右缘 fab 异侧，不再入让位实测集。
  const floats = panel.querySelectorAll('.epub-reader-nav');
  let occupied = 0;
  floats.forEach((f) => {
    const box = /** @type {HTMLElement} */ (f);
    if (!(box instanceof HTMLElement) || box.hidden || box.offsetParent === null) return;
    const r = box.getBoundingClientRect();
    if (r.width === 0 && r.height === 0) return;
    occupied = Math.max(occupied, panelRect.bottom - r.top);
  });
  bottomPx = occupied > 0 ? occupied + 12 : FAB_BOTTOM_BASE;
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

/** 组件方法 open()：打开对话框（原型 open() 的对外契约，签名不变）。 */
async function open() {
  // 非文件卡入口已被可见性闸摘除；可问面硬闸复核（批⑩ 同律：md/html +
  // 电子书扩展名白名单；§7-A4 冗余守卫双层防呆）。
  if (!currentFile || !isAskableFile(currentFile)) return;
  if (isNoteOpen()) closeNote(); // 记事本批：视图排他（两个浮窗同座位，不叠压）
  const s = await ensureActiveSession();
  if (!s) return;
  s.unread = false; // 打开即读：通知点熄灭（ask 三修 2026-10-29 未读语义）
  restoreSize(el.askSurface); // 持久化缺陷批：尺寸偏好与记事本同一条恢复腿
  el.dock.classList.remove('closed');
  el.fab.classList.add('open');
  el.fab.classList.remove('pulse');
  render();
  askOpen(s);
  syncSeat();
  setTimeout(() => { try { el.input.focus(); } catch { /* detached */ } }, 260);
}

/** 组件方法 close()：收起对话框（原型 close() 的对外契约，签名不变）。 */
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

// ── 记事本（作者令 2026-11-05 11:38；机制见头注「记事本批」）───────────────
// 与 ask 面板的三处同律，逐条对齐而非另起一套：
//   ① 开合 = 钮再点 / Esc / 画布空白点击（wireElements/wireGlobals 同两条腿）；
//   ② 视图排他 = 一个座位只坐一个浮窗（open↔openNote 互相先收对方）；
//   ③ 可见性闸 = 同一 syncGate 同一拍（非文件卡/阅读面板开/非 canvas 页一起摘）。
// 语义 = **每个文件一份纯文本草稿**（sid = sha256(absPath) 前 8 hex，与 ask 同式）。
//
// ══ 持久化缺陷批（2026-11-05 修，作者报障「Canvas Note 记录的内容会丢」）══
// 改前实测结论（见批报告复现表）：**内容只落 localStorage，引擎侧零写点**——
//   写点 = flushNote → localStorage.setItem(`<brand>_note:<sid>`)；读点 = openNote
//   → localStorage.getItem(同键)。字面上「写盘」了，但介质是「按 origin × 设备」
//   的易失层：换浏览器/换设备/换 origin（localhost ↔ 127.0.0.1、换端口、手机层
//   在另一台设备上）一律读不到，清站点数据即彻底丢失 —— 这是「没做到随文件」的
//   语义缺口，不是偶发 bug。
// 改后两层（真源 + 镜像）：
//   ① 本地镜像（localStorage，同步、零延迟、离线可读）= 快显层，不是真源；
//   ② 引擎侧真源（WS `askNoteGet`/`askNotePut` → `<dataRoot>/ask/notes/<sid>.md`，
//      单点 core/ask/AskNotes.scala）= 跨设备/跨浏览器/跨引擎重启的权威层。
// 读序：先画本地镜像（不等网络）→ 发 askNoteGet 对账；写序：镜像立即写 +
// 服务端防抖 300ms 推送（人手打字频率，不必每击键一趟）。
// 冲突判据（多标签页/多设备）：本地写时刻（`note:ts:<sid>` 单键）vs 服务端
// mtime —— 服务端更新且本地无未落盘编辑 ⇒ 采纳服务端；本地更新（离线写入没推
// 上去）⇒ 回推服务端；**平局取服务端**（与 ask 转录「服务端为权威」同律）。
// 同浏览器多标签页另有 `storage` 事件直通（本地无脏编辑即采纳对端写入）。
// 降级可读（禁静默）：本地写失败（隐私模式/quota）或引擎断连时，窗内状态行
// 直说事实（见 NOTE_STATUS_TEXT），不再「静默当作存好了」。

const NOTE_STORE_PREFIX = 'note:';
/** 本地写时刻（与正文分键：正文值保持纯字符串 —— 旧键原样可读，迁移零义）。 */
const NOTE_TS_PREFIX = 'note:ts:';
/** 服务端推送防抖（人手打字频率；关窗/切卡/失焦都立即 flush+推）。 */
const NOTE_PUSH_DELAY_MS = 300;

/** 状态行文案（事实句：只描述存储面状态与后果；空串 = 不显示）。 */
const NOTE_STATUS_TEXT = {
  'mirror-off': '本地缓存不可用：内容随引擎保存',
  unsaved: '引擎未连接：内容只在本机',
  'no-store': '存储不可用：关窗后内容会丢',
  'too-large': '超出单条上限（256 KB），未保存到引擎',
  'read-failed': '读取失败，本地草稿已保留，未写入引擎',
  conflict: '记事本存在冲突：本地草稿和引擎版本均已保留',
};

/** 读某文件的本地镜像（无/坏/私密模式 ⇒ 空；与 ask 转录持久同一降级口径）。 */
function loadNoteLocal(sid) {
  if (!sid) return { text: '', ts: 0 };
  try {
    const text = localStorage.getItem(key(NOTE_STORE_PREFIX + sid)) || '';
    const raw = localStorage.getItem(key(NOTE_TS_PREFIX + sid));
    const ts = raw ? Number(raw) : 0;
    return { text, ts: Number.isFinite(ts) ? ts : 0 };
  } catch { return { text: '', ts: 0 }; }
}

/** 读正文（短签名保留：调用方只要文本时不必解构）。 */
function loadNote(sid) { return loadNoteLocal(sid).text; }

/** 写本地镜像（正文 + 写时刻；空文本 = 删两键，不留空壳）。
 *  @returns {boolean} 是否写成功（false ⇒ 调用方落可读降级状态，不静默） */
function persistNoteLocal(sid, text, ts) {
  if (!sid) return false;
  try {
    if (text) {
      localStorage.setItem(key(NOTE_STORE_PREFIX + sid), text);
      localStorage.setItem(key(NOTE_TS_PREFIX + sid), String(ts || Date.now()));
    } else {
      localStorage.removeItem(key(NOTE_STORE_PREFIX + sid));
      localStorage.removeItem(key(NOTE_TS_PREFIX + sid));
    }
    return true;
  } catch { return false; }
}

/** 窗内状态行（可读降级）：kind = '' 隐藏；其余见 NOTE_STATUS_TEXT。 */
function setNoteStatus(kind) {
  noteStatus = kind || '';
  if (!el || !el.noteStatus) return;
  const text = NOTE_STATUS_TEXT[noteStatus] || '';
  el.noteStatus.textContent = text;
  el.noteStatus.hidden = !text;
}

/** 引擎侧真源：读（对账回包 = consumeFrame 的 askNoteState）。 */
function requestNote(sid) {
  if (!sid) return;
  sendWs({ type: 'askNoteGet', sessionId: sid });
}

// Draft text and its base are persisted together, including an empty unsent clear.
const NOTE_DRAFT_PREFIX = 'note:draft:';
function saveNoteEntry(sid, entry) {
  try {
    localStorage.setItem(key(NOTE_DRAFT_PREFIX + sid), JSON.stringify({ text: entry.text, version: entry.version, dirty: entry.dirty, held: entry.held, remote: entry.remote || null }));
    return persistNoteLocal(sid, entry.text);
  } catch { return false; }
}
function noteEntry(sid) {
  if (!noteEntries.has(sid)) {
    let saved = null;
    try { saved = JSON.parse(localStorage.getItem(key(NOTE_DRAFT_PREFIX + sid)) || 'null'); } catch { /* unavailable mirror */ }
    const valid = saved && typeof saved.text === 'string';
    noteEntries.set(sid, { text: valid ? saved.text : loadNote(sid), dirty: valid ? !!saved.dirty : !!loadNote(sid), version: valid && typeof saved.version === 'number' ? saved.version : null, timer: 0, flight: null, readFailed: false, held: !!(valid && saved.held), remote: valid ? saved.remote : null });
  }
  return noteEntries.get(sid);
}
/** Input handler body（命名函数：可执行测试切片可直接驱动它，不依赖事件绑定）。 */
function noteInputChanged() {
  if (noteSid) { const entry = noteEntry(noteSid); entry.text = el.noteText.value; entry.dirty = true; saveNoteEntry(noteSid, entry); }
  if (noteTimer) clearTimeout(noteTimer);
  noteTimer = setTimeout(() => { noteTimer = 0; flushNote(); }, 250);
}
/** 重连后的记事本对账（命名函数：清 flight、把离线脏稿重新读一遍再补推）。 */
function resyncNotesAfterReconnect() {
  for (const [sid, entry] of noteEntries) { entry.flight = null; if (entry.dirty) requestNote(sid); }
}
function showNoteConflict(sid) {
  if (sid !== noteSid || !el || !el.noteStatus) return;
  const entry = noteEntry(sid);
  setNoteStatus('conflict');
  if (!entry.remote) return;
  const preview = document.createElement('pre');
  preview.textContent = entry.remote.text || tt('（服务端已清空）', '(Cleared on server)');
  const mine = document.createElement('button');
  mine.type = 'button'; mine.textContent = tt('保留本地', 'Keep local');
  const theirs = document.createElement('button');
  theirs.type = 'button'; theirs.textContent = tt('使用服务端', 'Use server');
  mine.addEventListener('click', () => resolveNoteConflict(sid, 'local'));
  theirs.addEventListener('click', () => resolveNoteConflict(sid, 'remote'));
  el.noteStatus.append(preview, mine, theirs);
}
function resolveNoteConflict(sid, choice) {
  const entry = noteEntry(sid);
  if (!entry.held || !entry.remote) return;
  const remote = entry.remote;
  if (choice === 'remote') { entry.text = remote.text; entry.dirty = false; }
  else if (choice === 'local') entry.dirty = true;
  else return;
  entry.version = remote.version; entry.held = false; entry.remote = null;
  saveNoteEntry(sid, entry);
  if (sid === noteSid && el) { el.noteText.value = entry.text; setNoteStatus(''); }
  if (entry.dirty) scheduleNotePush(sid, entry.text, 0);
}
function scheduleNotePush(sid, text, delay) {
  if (!sid) return;
  const entry = noteEntry(sid);
  entry.text = text; entry.dirty = true;
  saveNoteEntry(sid, entry);
  if (entry.timer) clearTimeout(entry.timer);
  entry.timer = setTimeout(() => {
    entry.timer = 0;
    if (entry.version === null || entry.readFailed || entry.held || entry.flight) return;
    const writeId = String(++noteWriteSequence);
    entry.flight = { writeId, text: entry.text };
    if (!sendWs({ type: 'askNotePut', sessionId: sid, text: entry.text, baseVersion: entry.version, writeId }) && sid === noteSid) setNoteStatus('unsaved');
  }, typeof delay === 'number' ? delay : NOTE_PUSH_DELAY_MS);
}
function flushNote() {
  if (noteTimer) { clearTimeout(noteTimer); noteTimer = 0; }
  if (!el || !notePath || !noteSid) return;
  const entry = noteEntry(noteSid);
  if (!entry.dirty) return;
  entry.text = el.noteText.value;
  noteLocalOk = saveNoteEntry(noteSid, entry);
  scheduleNotePush(noteSid, entry.text);
  if (!noteLocalOk) setNoteStatus('mirror-off');
}
function applyServerNote(msg) {
  const sid = msg.sessionId || '';
  if (!sid || !noteEntries.has(sid)) return;
  const entry = noteEntry(sid);
  if (msg.error || typeof msg.version !== 'number' || typeof msg.text !== 'string') {
    entry.readFailed = true;
    if (sid === noteSid) setNoteStatus('read-failed');
    return;
  }
  if (entry.version !== null && msg.version < entry.version) return;
  entry.readFailed = false;
  if (entry.text !== msg.text && (entry.held || (entry.dirty && (entry.version === null || entry.version !== msg.version)))) {
    entry.held = true;
    entry.remote = { text: msg.text, version: msg.version };
    saveNoteEntry(sid, entry); showNoteConflict(sid); return;
  }
  entry.version = msg.version;
  if (entry.dirty && entry.text !== msg.text) { saveNoteEntry(sid, entry); scheduleNotePush(sid, entry.text, 0); return; }
  entry.dirty = false; entry.held = false; entry.remote = null; entry.text = msg.text;
  saveNoteEntry(sid, entry);
  if (sid === noteSid && el) { el.noteText.value = msg.text; setNoteStatus(''); }
}
function applyNoteAck(msg) {
  const sid = msg.sessionId || '';
  const entry = noteEntries.get(sid);
  if (!entry || !entry.flight || msg.writeId !== entry.flight.writeId) return;
  const sent = entry.flight.text;
  entry.flight = null;
  if (msg.ok && typeof msg.version === 'number') {
    entry.version = msg.version;
    if (entry.text === sent) entry.dirty = false;
    saveNoteEntry(sid, entry);
    if (entry.dirty) scheduleNotePush(sid, entry.text, 0);
    if (sid === noteSid) setNoteStatus('');
  } else if ((msg.error === 'conflict' || msg.error === 'version-required') && typeof msg.version === 'number' && typeof msg.text === 'string') {
    // 迟到/同一文本的回执：服务端已经是我们要写的文本（例：显式选了服务端副本后，
    // 在途写的 conflict 回执才到；或未知基线的旧页面对上了同一文本）⇒ 无需再让用户
    // 选择，直接采纳该版本。文本不同才保留两边并展示选择。
    if (msg.text === entry.text) {
      entry.version = msg.version; entry.dirty = false; entry.held = false; entry.remote = null;
      saveNoteEntry(sid, entry);
      if (sid === noteSid) setNoteStatus('');
    } else {
      entry.held = true; entry.remote = { text: msg.text, version: msg.version };
      saveNoteEntry(sid, entry); showNoteConflict(sid);
    }
  } else {
    if (msg.error === 'read-failed') entry.readFailed = true;
    if (sid === noteSid) setNoteStatus(msg.error === 'too-large' ? 'too-large' : msg.error === 'read-failed' ? 'read-failed' : 'unsaved');
  }
}
function onNoteStorage(e) {
  if (!el || !noteSid || e.key !== key(NOTE_STORE_PREFIX + noteSid)) return;
  const entry = noteEntry(noteSid);
  if (!entry.dirty && !entry.flight) requestNote(noteSid);
}

function isNoteOpen() { return !!el && !el.noteDock.classList.contains('closed'); }

/** 打开记事本（与 ask 同一条开合曲线：摘 .closed 即入场）。 */
async function openNote() {
  if (!currentFile || !isAskableFile(currentFile)) return;
  if (isOpen()) close(); // 视图排他（同座位只坐一个浮窗）
  const path = currentFile;
  const sid = await ensureSid(path);
  if (!sid || currentFile !== path) return; // 派生期间切卡/关卡：让 syncNoteForFile 收口
  notePath = path;
  noteSid = sid;
  const entry = noteEntry(sid);
  el.noteText.value = entry.text; // ① 本地镜像先上台（不等网络）
  if (entry.held) showNoteConflict(sid); else setNoteStatus(noteLocalOk ? '' : 'mirror-off');
  requestNote(sid); // ② 引擎侧真源对账（回包 = consumeFrame 的 askNoteState）
  restoreSize(el.noteSurface); // ③ 尺寸偏好（与 ask 同一条恢复腿）
  el.noteDock.classList.remove('closed');
  el.noteFab.setAttribute('aria-pressed', 'true');
  syncSeat();
  // 自动获得焦点（作者令「点击出来一个窗口」= 直接可写；与 ask 输入条同拍，
  // 等开合动效落定再聚，避免动效中途聚焦把首帧撑开）。
  setTimeout(() => { try { el.noteText.focus(); } catch { /* detached */ } }, 260);
}

/** 收起记事本（内容已在 write-through 里，收起来只是摘类，不丢字）。 */
function closeNote() {
  if (!el) return;
  flushNote();
  el.noteDock.classList.add('closed');
  el.noteFab.setAttribute('aria-pressed', 'false');
}

function toggleNote() {
  if (isNoteOpen()) closeNote();
  else openNote();
}

/** 切卡即切记事本（每文件一份草稿）：旧草稿先 flush，再按新文件的 sid 注水。 */
async function syncNoteForFile() {
  if (!el) return;
  flushNote();
  if (!currentFile) {
    // 无文件卡：清空内存面（草稿本身已按文件落盘，不丢），窗一起收。
    notePath = '';
    noteSid = '';
    el.noteText.value = '';
    closeNote();
    return;
  }
  const path = currentFile;
  const sid = await ensureSid(path);
  if (!sid || currentFile !== path) return; // 派生期间又切卡：以最新一拍为准
  // 串写防线（持久化缺陷批实测）：派生是异步的，这段 await 期间用户可能又敲了
  // 字（内容仍属**旧**文件）。搬 sid 之前再收一次口——否则下一拍 flush 会把旧
  // 文件的文本写进新文件的键（改前存在的跨文件串写点）。
  if (notePath && notePath !== path) flushNote();
  notePath = path;
  noteSid = sid;
  const entry = noteEntry(sid);
  el.noteText.value = entry.text;
  if (entry.held) showNoteConflict(sid); else setNoteStatus(noteLocalOk ? '' : 'mirror-off');
  requestNote(sid); // 引擎侧真源对账（每文件独立生命周期，与问答回放零同步）
}

// ── 渲染（升腾入场 + 顶带 12px 内容级渐隐由 CSS mask 承担）────────────────
// glass-unify 批 2026-11-05：原「按龄渐隐」腿已退役（见下方 render() 内注释）。

function render() {
  if (!el) return;
  const s = activeSession();
  el.col.textContent = '';
  if (!s) return;
  // v2.1 显示面减法：只出 user/ai 两族——sys（回执/状态噪声类）转录仍存但永不
  // 呈现（收进不可见处；主会话回问在 consumeFrame 已按 §7 落 user 族）。
  const vis = s.msgs.filter((m) => m.role !== 'sys');
  vis.forEach((m) => {
    // glass-unify 批 2026-11-05：气泡挂**唯一毛玻璃工具类** `.nf-glass`
    // （作者令「这三处全部吃你这一批正在立的唯一毛玻璃标准，不许为 ask 气泡
    // 另造一套等效样式」）。材质/描边/内高光/模糊全部来自 --nf-glass-* 单源，
    // 本组件零本地材质声明；`ask-bub` / `user` / `ai` 三个身份类只承载几何与
    // 语义色（气泡最大宽度、圆角、user 侧 sapphire tint）。
    const b = h('div', { class: 'ask-bub nf-glass ' + (m.role === 'user' ? 'user' : 'ai') });
    // ① 修复批 2026-10-31：ai 族走主会话渲染腿（markdown 全套）；user 族
    // 维持 textContent（用户输入纯文本面，与主会话一致）。渲染腿复用不重写。
    // ⚠ **本模块唯一的 HTML 落点 = markdown 渲染腿**（renderMarkdownWithMath 的
    // 返回值只能经元素 innerHTML 落到 ai 气泡：它是既有渲染腿的产物，自带
    // marked+KaTeX 护栏）；业务 DOM（气泡壳、图标、拖柄、输入条）一律走 h()/frag()。
    if (m.role === 'user') b.textContent = m.text;
    else b.innerHTML = renderMarkdownWithMath(m.text);
    // History bubbles never change opacity: not by age, not by scroll position. A bubble
    // that crosses the column's top edge is clipped by the column, so every visible part
    // of it stays fully legible. (FCC-REQ-004)
    el.col.appendChild(b);
  });
  requestAnimationFrame(() => { el.col.scrollTop = el.col.scrollHeight; });
}

/** 流式增量更新（不整列重渲——textDelta 高频）。 */
function updateStreamingBubble(s) {
  if (!el || s !== activeSession()) return;
  const last = s.msgs[s.msgs.length - 1];
  const node = el.col.lastElementChild;
  if (!node || !node.classList.contains('ai')) {
    render();
    return;
  }
  // 主会话流式同律：cache:false 直解析（每帧重解析增长文本，不进 LRU 挤占）。
  // （同一 HTML 落点：markdown 渲染腿，见 render() 的注释。）
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
  // 斜杠先审（ask 面三修③ 2026-11-11）：白名单命中（现仅 UI-only 的 /model）
  // ⇒ 本地开面板、清输入、零 WS 零气泡；未命中 ⇒ '/' 仍是纯文本（封存面语义
  // 不扩，SLASH_ALLOWED 未被本调用面放大），原样走 ask 链 —— 主会话 send 的
  // handleSlash 先审同律。
  if (el) {
    const slashText = el.input.value.trim();
    if (slashText && handleSlash(slashText)) {
      el.input.value = '';
      el.input.style.height = 'auto'; // 多行草稿清空后回落单行（下方发送同式）
      return;
    }
  }
  if (!el || !currentFile || !isAskableFile(currentFile)) return; // 硬闸复核（冗余守卫；批⑩ 判据 = md/html + 电子书并集）
  const s = await ensureActiveSession();
  if (!s) return;
  const v = el.input.value.trim();
  if (!v) return;
  el.input.value = '';
  el.input.style.height = 'auto'; // 多行草稿发送后回落单行（主会话 send 同律）
  // ④ 双泡修复批 2026-10-31（作者截图：等待小气泡上方叠多余空泡）：发问时
  // **不再预压空 ai 泡**——等待期只有 typing 一件；首个 textDelta 落 ai 泡时
  // removeTyping() 单点换泡（双泡根因 = 空 ai 泡 + typing 泡并存）。
  s.msgs.push({ role: 'user', text: v });
  persistSession(s); // 发问即落盘（持久批：重载/竞态现场问题不丢）
  render();
  appendTyping();
  armTurn(s); // 中断状态机：生成中 = 发送钮让位中断钮（输入行为包批）
  if (!s.stateSent) askOpen(s); // 未绑定先补一拍（历史回放与流式都依赖 askState）
  sendWs({ type: 'askQuestion', sessionId: s.sid, content: v, mainSessionId: state.activeSessionId || '' });
}

// ── 中断状态机（输入行为包批 2026-11-02；与主会话 send/stop 同律）────────────
// busy 面 = 发送钮让位中断钮（包 applyTurnChrome 语句同源）；臂点 = submit 与
// 首个流帧（他机发问现场——本页没发过 askQuestion 也可能收到 textDelta）；收点 =
// done / error / interrupted 终态帧 + 用户点停（本地先收，终态帧幂等再收）+
// askState 回放见「转录已答完」（断线中 turn 已终了的形态）+ 静默看门（终态帧
// 丢失兜底——纯 UI 收口，不掐服务端，主会话看门同哲学）。已生成部分保留、可
// 继续追问（主会话中断后 UI 态同律）。

/** 臂：该会话 turn 在飞 → 中断钮上位（活动会话才动 chrome；幂等）。 */
function armTurn(s) {
  s.busy = true;
  s.lastFrameAt = Date.now();
  if (s === activeSession() && turnToggle) turnToggle.busy();
}

/** 收：终态（busy 清 + 中断钮让位；幂等——重复终态帧/重复点击零副作用）。 */
function settleTurn(s) {
  if (!s) return;
  s.busy = false;
  if (s === activeSession() && turnToggle) turnToggle.idle();
}

/** 用户点停：帧出口走包单点（与主会话 #stop-btn 逐字节同帧）；UI 即刻收口
 *  （主会话 stop 同律「reset UI immediately」）——typing 撤除、已生成 ai 泡保留。 */
function stopAsk() {
  const s = activeSession();
  if (!s || !s.busy) return;
  requestTurnInterrupt(s.sid);
  settleTurn(s);
  if (isOpen()) { removeTyping(); render(); }
}

// 静默看门：busy 期间 180s 无任何流帧 ⇒ 本地收口（终态帧丢失形态）。帧到即续臂
// （lastFrameAt 刷新），活跃流永不误收；不 server-interrupt（freezetimeout B2
// 「到点只呈现，不发 interrupt」同哲学——这里连呈现都不用，只还按钮）。
setInterval(() => {
  const now = Date.now();
  for (const s of sessions.values()) {
    if (s.busy && now - (s.lastFrameAt || 0) > 180000) settleTurn(s);
  }
}, 5000);

let typingNode = null;
let streamPersistAt = 0; // 流式落盘节流拍（持久批；800ms 一写，done 终局兜底）
function appendTyping() {
  if (!el) return;
  removeTyping();
  // One transient line reuses the existing Ask stream; it is status-only, never expandable.
  const tp = h('div', {
    class: 'ask-stream-line',
    role: 'status',
    'aria-live': 'polite',
    'aria-label': tt('正在生成回答', 'Generating answer'),
  }, tt('正在生成…', 'Generating…'));
  el.col.appendChild(tp);
  el.col.scrollTop = el.col.scrollHeight;
  typingNode = tp;
}
function updateTypingText(text) {
  if (!typingNode || !typingNode.isConnected || typeof text !== 'string') return;
  typingNode.textContent = text;
  // Keep the newest generated tail in view without making the line interactive.
  typingNode.scrollLeft = typingNode.scrollWidth;
}
function removeTyping() {
  if (typingNode && typingNode.isConnected) typingNode.remove();
  typingNode = null;
}

/** ws.js ask 拦截面：sessionId 前缀 `ask-` 的帧全部进这里（主视图零交集）。 */
function consumeFrame(msg) {
  const sid = msg.sessionId || '';
  // 记事本持久批（2026-11-05）：记事本帧**不依赖 ask 会话账**——记事本与问答是
  // 两个独立入口/独立生命周期（作者令 2026-11-05 11:38），故在 sessions 反查
  // 之前早退处理（页面刚打开、会话账还没建时也要能收对账回包）。
  if (msg.type === 'askNoteState') { applyServerNote(msg); return; }
  if (msg.type === 'askNoteAck') { applyNoteAck(msg); return; }
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
      // 断线中 turn 已终了的形态（输入行为包批）：回放转录末条已是 ai 答复而本地
      // 仍臂着 busy ⇒ 服务端为权威，即刻收口（幂等；turn 真在飞时转录末条是 user 问）。
      if (entry.busy && adopted.length && adopted[adopted.length - 1].role === 'ai') settleTurn(entry);
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
      armTurn(entry); // 流帧即活性（臂 + 看门续臂；他机发问现场也臂上中断态）
      const last = entry.msgs[entry.msgs.length - 1];
      if (!last || last.role !== 'ai') entry.msgs.push({ role: 'ai', text: '' });
      const ai = entry.msgs[entry.msgs.length - 1];
      ai.text += msg.delta || '';
      if (entry === activeSession() && isOpen()) {
        updateTypingText(ai.text);
        el.col.scrollTop = el.col.scrollHeight;
      }
      // 流式落盘节流（持久批）：textDelta 高频，800ms 一拍写 localStorage；
      // 终局由 done 全量落盘兜底。
      const nowMs = Date.now();
      if (nowMs - streamPersistAt > 800) { streamPersistAt = nowMs; persistSession(entry); }
      if (entry === activeSession()) syncUnreadDot();
      break;
    }
    case 'done': {
      if (!(entry === activeSession() && isOpen())) entry.unread = true;
      settleTurn(entry); // 终态收口（输入行为包批：中断钮让位，幂等）
      const last = entry.msgs[entry.msgs.length - 1];
      if (last && last.role === 'ai' && !last.text && msg.finalText) last.text = msg.finalText;
      persistSession(entry);
      if (entry === activeSession()) {
        if (isOpen()) { removeTyping(); render(); }
        syncUnreadDot();
      }
      break;
    }
    case 'interrupted': {
      // 中断终态（输入行为包批）：点停后服务端回执到达——本地多半已收口（幂等
      // 再收）；已生成部分保留（partial ai 泡在转录里，不撤不重置），可继续追问
      // （主会话中断后 UI 态同律）。非在看点收未读（部分答案也值得唤回）。
      if (!(entry === activeSession() && isOpen())) entry.unread = true;
      settleTurn(entry);
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
      settleTurn(entry); // 终态收口（同 done）
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
  mountRoot();
  if (el) syncToActiveTab();
  setAskFrameSink(consumeFrame);
}

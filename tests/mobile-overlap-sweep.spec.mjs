#!/usr/bin/env node
// Mobile truncation / overlap sweep gate (截断巡检闸, author order 2026-11-24 09:54).
//
// Boots the REAL src/main/resources/web tree at 390x844 (touch, DPR 1, visual-harness
// zero-port装置) and asserts, per panel family, the geometric invariants that the
// author's two phone screenshots violated:
//   O1 无内容穿 header：滚动主体的首行/任一行不得落在悬浮窗头之下（顶部让位）
//   O2 无控制钮压内容：悬浮圆钮（终止钮 / 加号）与列表/消息行的矩形不得相交
//   O3 无元素越出容器：气泡/行的左右边界不得越出其行（max-width + 换行规范）
//   O4 长单行正确截断或换行：标注为省略的行必须真的溢出被截（scrollWidth > clientWidth
//      时 text-overflow:ellipsis 生效），不得出现「未截断却被裁掉」的字
// Panels covered (family list = 截断巡检批清册「一次建全」，作者继续令 2026-11-25)：
//   P1 发起群聊（friendGroups.openCreateGroupDialog，手机）
//   P2 后台 agent 详情对话框（bgAgentPopup.openStepPopup，终止悬浮钮 + 消息流）
//   P3 主会话消息流（含长文本系统气泡：BACKGROUND / BG-LIFECYCLE 注入行）
//   P4 联系人（#fm-contacts-body；顶部搜索坞让位 + 底 dock 让位，30 人名册撑出滚动）
//   P5 设置（#settings-content；返回钮让位 + 底 dock 让位）
//   P6 待办列表（todo-apple-list；左快速决策 / 右加号双悬浮钮让位）
//   P7 看板文档阅读面（canvas-md-scroll；find 键 / ask / note 悬浮钮让位）
//   P8 AskUser 决策卡（ask 面板族在流内的载体 .option-box；长题面 + 4 选项，
//      流内末件不被输入钮压住；选区 ask dock 属可关闭对话框，既有浮件清单
//      已有「source occlusion exempt」豁免注记，不重复建面）
//   D1-D2 桌面 1280×800 抽检同面板族（发起群聊窗头让位 / 主会话消息流）
//
// 负对照（必须红）：每个面板再跑一遍「还原修前形态」的注入样式（窗头让位归零 / 流
// 底让位归零），断言闸必须报出对应 O 项违例；若负对照反而绿 ⇒ 闸失明 ⇒ FAIL。
// 注入分两路：css（addStyleTag，覆盖样式表让位规则）与 js（页面内 inline !important
// 覆盖——main.js 对 #chat padding-bottom 走 inline important，样式表压不过它）。
//
// Exit: 0 = 全部正对照绿且负对照红；1 = 任一违例（正对照）或负对照未红；2 = 环境缺失（SKIP）。
//
// 用法：node tests/mobile-overlap-sweep.spec.mjs [--web-root <dir>] [--out <dir>]
import { mkdirSync } from 'node:fs';
import { join, isAbsolute } from 'node:path';
import { ROOT, loadPlaywright, launchBrowser, createSession, settle, setWebRoot } from '../scripts/lib/visual-harness.mjs';
import { evidenceOut } from '../scripts/lib/evidence-batch.mjs';

const argv = process.argv.slice(2);
const valOf = (f, d = null) => { const i = argv.indexOf(f); return i >= 0 && argv[i + 1] ? argv[i + 1] : d; };
const WEB_ROOT = valOf('--web-root', 'src/main/resources/web');
setWebRoot(isAbsolute(WEB_ROOT) ? WEB_ROOT : join(ROOT, WEB_ROOT));
const OUT = valOf('--out') || evidenceOut(process.env.MOBILE_SWEEP_SHOTS_DIR, 'mobile-overlap-sweep', 'runtime');
mkdirSync(OUT, { recursive: true });

const CLOCK = 1793940000000;
const ROOT_SID = 'visual-root';
const BG_SID = 'delegate-sweep-1';
const VIEW = { width: 390, height: 844 };

// 名册 30 人：联系人面板（P4）与发起群聊列表（P1）都要撑出**真滚动**，
// 否则 O2（末端对 dock/钮）空转——闸只认滚到底之后的几何。
const FRIEND_NAMES = ['RouRou', 'Jwin', 'Arrogant', '4bytes', 'ugn87esu7i5g', 'AzarAI', 'Katagorri', 'puerhile', '安之则'];
const FRIENDS = Array.from({ length: 30 }, (_, i) => ({
  userId: `u${i + 1}`,
  username: FRIEND_NAMES[i % FRIEND_NAMES.length] + (i >= FRIEND_NAMES.length ? ` ${Math.floor(i / FRIEND_NAMES.length) + 1}` : ''),
  display_name: FRIEND_NAMES[i % FRIEND_NAMES.length] + (i >= FRIEND_NAMES.length ? ` ${Math.floor(i / FRIEND_NAMES.length) + 1}` : ''),
  avatar: '',
}));

const LONG_BG = '[Background task completed] "Run full sbt test-mem2600 frontend backendsuite" (exit 0 · 2h 2m):\n[info] 1474 tests passed, 0 failed, no OOM';
const LONG_NUDGE = '[Background-task health] Your background task has been running for 2h 2m without output — check whether it is still making progress or waiting on a lock.';
// AskUser 决策卡夹具（P8）：长题面 + 4 选项 —— 卡是流内末件时最易被输入钮压住。
const ASK_ITEMS = [{
  question: '这是一个较长的决策问题描述，用来检验选项卡在消息流末端不被悬浮输入钮压住、不越出视口、题面完整换行不被裁字。'.repeat(2),
  options: ['选项 A —— 说明文字较长用于撑宽卡片看换行', '选项 B', '选项 C', '选项 D'],
  multiSelect: false,
}];
const HISTORY = [
  ...Array.from({ length: 10 }, (_, i) => ({ type: i % 2 ? 'ai' : 'user', text: `第 ${i + 1} 条消息：用于撑高消息流并量几何。`, timestamp: CLOCK - 900000 + i * 1000 })),
  { type: 'user', injected: true, source: 'background', eventType: 'completed', header: 'BACKGROUND · SPIDER · COMPLETED', text: LONG_BG, timestamp: CLOCK - 300000 },
  { type: 'user', injected: true, source: 'bg-lifecycle', eventType: 'nudge', header: 'BG-LIFECYCLE · SPIDER · NUDGE', text: LONG_NUDGE, timestamp: CLOCK - 60000 },
];

/** 页内几何读数：只读，零假设选择器（缺失即 null，由断言侧判违例）。 */
const MEASURE = ({ scrollSel, headerSel, firstSel, fabSels = [], rowSel, bubbleSel }) => {
  const R = (el) => { const b = el.getBoundingClientRect(); return { t: b.top, b: b.bottom, l: b.left, r: b.right }; };
  const out = { violations: [], measured: 0 };
  const scroll = document.querySelector(scrollSel);
  if (!scroll) { out.violations.push({ rule: 'O0', what: 'scroll container missing', sel: scrollSel }); return out; }
  // 量测前提：程序化滚动必须瞬时到位（联系人等面板带 scroll-behavior:smooth，
  // 不关掉则 scrollTop 赋值后立刻读矩形 = 读到滚动中途的幻影行，O1/O2 全失真）。
  scroll.style.scrollBehavior = 'auto';
  scroll.scrollTop = 0;
  const header = headerSel ? document.querySelector(headerSel) : null;
  const rows = [...scroll.querySelectorAll(rowSel)].filter((e) => e.getClientRects().length);
  out.measured = rows.length;
  // O0 非空闸：没有可量内容 = 闸空转（会让负对照与正对照同时「绿」）⇒ 直接红。
  if (!rows.length) out.violations.push({ rule: 'O0', what: 'no content rows measured (vacuous)', rowSel });
  // O1：窗头之下不得有首行/首项（顶部让位）。窗头 = 悬浮条，首项顶必须 ≥ 窗头底。
  if (header) {
    const hb = R(header).b;
    const firsts = [...document.querySelectorAll(firstSel || rowSel)].filter((e) => e.getClientRects().length && scroll.contains(e));
    for (const f of firsts) {
      const fr = R(f);
      if (fr.t < hb - 0.5) { out.violations.push({ rule: 'O1', what: 'first item under header', cls: (f.className || '').slice(0, 60), itemTop: +fr.t.toFixed(1), headerBottom: +hb.toFixed(1) }); break; }
    }
  }
  // 滚到底再量末端（O2/O3/O4 取末端态，即最易被 dock/钮压住的状态）
  scroll.scrollTop = scroll.scrollHeight;
  const endRows = [...scroll.querySelectorAll(rowSel)].filter((e) => e.getClientRects().length);
  for (const fabSel of fabSels) {
    const fab = document.querySelector(fabSel);
    // O0 非空闸（控件侧）：声明的悬浮控件不可见 = 闸空转，直接红（不许静默跳过）。
    if (!(fab && fab.getClientRects().length)) { out.violations.push({ rule: 'O0', what: 'declared floating control not visible (vacuous)', fabSel }); continue; }
    if (!endRows.length) continue;
    const f = R(fab);
    for (const row of endRows) {
      const r = R(row);
      const hit = r.l < f.r && r.r > f.l && r.t < f.b && r.b > f.t;
      if (hit && r.b > f.t + 0.5) { out.violations.push({ rule: 'O2', what: 'control overlaps content', fabSel, row: (row.className || '').slice(0, 60), rowBottom: +r.b.toFixed(1), fabTop: +f.t.toFixed(1) }); break; }
    }
  }
  // O3：气泡不得越出其行（左右）。
  const bubbles = bubbleSel ? [...scroll.querySelectorAll(bubbleSel)].filter((e) => e.getClientRects().length) : [];
  for (const b of bubbles) {
    const row = b.closest(rowSel) || b.parentElement;
    const br = R(b), rr = R(row);
    const vw = document.documentElement.clientWidth;
    if (br.l < rr.l - 0.5 || br.r > rr.r + 0.5 || br.r > vw + 0.5 || br.l < -0.5) {
      out.violations.push({ rule: 'O3', what: 'bubble escapes row/viewport', cls: (b.className || '').slice(0, 60), bubble: [br.l, br.r].map((v) => +v.toFixed(1)), row: [rr.l, rr.r].map((v) => +v.toFixed(1)) });
      break;
    }
  }
  // O4：标注省略的文字必须真的溢出（否则是「未截断却被裁」的假省略）；
  // 且溢出的字必须有省略（scrollWidth > clientWidth ⇒ text-overflow 为 ellipsis）。
  for (const el of scroll.querySelectorAll(bubbleSel + ' *')) {
    if (!el.getClientRects().length) continue;
    const cs = getComputedStyle(el);
    if (cs.overflow === 'hidden' && cs.textOverflow === 'ellipsis' && el.scrollWidth > el.clientWidth + 1 && cs.whiteSpace !== 'nowrap') {
      out.violations.push({ rule: 'O4', what: 'clipped without nowrap ellipsis', cls: (el.className || '').slice(0, 60) });
      break;
    }
  }
  return out;
};

async function runPanel(browser, panel, negative) {
  const view = panel.view || VIEW;
  const s = await createSession(browser, {
    width: view.width, height: view.height, colorScheme: 'light', clock: CLOCK,
    apiStubs: {
      '/api/neblink/status': { body: { loggedIn: true, device: { name: 'sweep' }, peers: [] } },
      '/api/friends': { body: { friends: FRIENDS, incoming: [], outgoing: [] } },
      '/api/groups': { body: { conversations: [] } },
      '/api/groups/invites': { body: { invites: [] } },
      '/api/delegates': { body: { record: { id: BG_SID, executor: 'claude', title: 'sweep', createdAt: CLOCK, name: 'sweep' }, live: { status: 'running' }, verdict: null, task: null } },
    },
  });
  s.page.on('pageerror', (e) => { throw new Error(`[${panel.name}] pageerror ${e.message}`); });
  await s.boot({ config: { llm: { providers: {} }, features: {} }, sessionId: ROOT_SID, messages: HISTORY });
  await panel.setup(s.page, s);
  // 控件就位的前提帧由 setup 自带；此处仅等待渲染落定。
  await settle(s.page, 400);
  if (negative) {
    // 字符串 = css 注入；函数 = 页面内 inline !important 覆盖（main.js 的 #chat
    // padding-bottom 是 inline important，样式表 !important 压不过它，只能同级后写）。
    if (typeof negative === 'string') await s.page.addStyleTag({ content: negative });
    else await negative(s.page);
  }
  await settle(s.page, 150);
  const res = await s.page.evaluate(MEASURE, panel.cfg);
  await s.page.screenshot({ path: join(OUT, `${panel.name}${negative ? '-negative' : ''}.png`), animations: 'disabled', scale: 'css' });
  await s.close();
  return res;
}

const openCreateGroup = (page) => page.evaluate(async () => {
  const m = await import('/js/friendGroups.js');
  m.openCreateGroupDialog();
}).then(() => new Promise((r) => setTimeout(r, 900)));

// 待办列表进面（同 noclip-floating-geometry 装置）：tasks 页 → 点首行 → apple-list。
const openTodoList = async (page) => {
  await page.evaluate(async () => {
    const m = await import('/js/todoStore.js');
    m.todoStore.__seed({ tagList: [{ id: 'tw', name: '工作', color: 'rgb(0,122,255)' }], itemList: Array.from({ length: 30 }, (_, i) => ({ id: `i${i}`, tagId: 'tw', title: `待办事项 ${i}`, due: null, priority: 0, completed: false })) });
    const nav = await import('/js/mobile/navController.js');
    nav.pushPage('tasks');
  });
  await page.waitForSelector('.mobile-page[data-page="tasks"].live', { timeout: 15000 });
  await page.locator('.mobile-page[data-page="tasks"] .ta-rows .ta-row').first().click();
  await page.waitForSelector('.mobile-page[data-page="todo-apple-list"].live', { timeout: 10000 });
  await new Promise((r) => setTimeout(r, 300));
};

// 看板文档阅读面：workspace-open-item 开一张长文 md 卡 → dockTo('canvas')。
const openCanvasDoc = async (page) => {
  await page.evaluate(async (paras) => {
    const text = `# 巡检文档\n\n${paras.map((p) => `${p} 这是一段较长的正文，用来把阅读面撑出滚动并量末端几何。`).join('\n\n')}`;
    window.dispatchEvent(new CustomEvent('workspace-open-item', { detail: { id: 'file:/tmp/sweep-doc.md', itemType: 'markdown', title: 'sweep-doc', content: text, pinned: true, absPath: '/tmp/sweep-doc.md' } }));
    (await import('/js/mobile/navController.js')).dockTo('canvas');
  }, Array.from({ length: 40 }, (_, i) => `第 ${i + 1} 段`));
  await page.waitForSelector('.mf-cv-host .canvas-md-scroll', { timeout: 15000 });
  await new Promise((r) => setTimeout(r, 400));
};

const PANELS = [
  {
    name: 'P1-create-group',
    setup: openCreateGroup,
    // firstSel = 窗体内首个可见项（群名输入 / 章节标签）——修前它正被窗头盖住一半。
    cfg: { scrollSel: '#fm-group-create-overlay .fm-pick-body', headerSel: '#fm-group-create-overlay .fm-pick-header', firstSel: '#fm-group-create-overlay .fm-gs-rename-input, #fm-group-create-overlay .fm-gs-title', fabSels: ['#fm-group-create-overlay .fm-pick-fab'], rowSel: '.fm-pick-row', bubbleSel: '.fm-pick-row .__none__' },
    // 修前形态：主体顶部让位归零（首行落在窗头之下）。
    negative: '#fm-group-create-overlay .fm-pick-body { padding-top: 2px !important; }',
  },
  {
    name: 'P2-bg-agent-popup',
    setup: async (page, s) => {
      // 运行中快照帧（终止悬浮钮据 status 上位；同 mobile-bgagent-chrome 装置）。
      await s.frame({
        type: 'activeAgents',
        agents: [{ rootSessionId: 'visual-root', sessionId: BG_SID, agentId: BG_SID, name: 'sweep', task: 'sweep', kind: 'Delegate', status: 'Processing', startedAt: CLOCK - 7200000, retryCount: 0 }],
      });
      // 弹窗自取历史（getHistory）：应答器回注入行夹具（同 mobile-bgagent-chrome 装置）。
      await page.evaluate(([sid, msgs]) => {
        window.__histReplies = 0;
        setInterval(() => {
          const sent = window.__wsSent || [];
          const pend = sent.filter((f) => f.type === 'getHistory' && f.sessionId === sid);
          if (pend.length > window.__histReplies) {
            window.__histReplies = pend.length;
            window.__wsMock.onmessage({ data: JSON.stringify({ type: 'historyPage', sessionId: sid, messages: msgs, total: msgs.length, offset: 0, hasMore: false }) });
          }
        }, 40);
      }, [BG_SID, HISTORY]);
      await page.evaluate(async (sid) => {
        const m = await import('/js/bgAgentPopup.js');
        m.openStepPopup(sid, 'sweep', 'sweep');
      }, BG_SID);
      await new Promise((r) => setTimeout(r, 1200));
    },
    cfg: { scrollSel: '.bgagent-overlay .flow-agent-chat', headerSel: null, fabSels: ['.bgagent-overlay .bgagent-stop-fab'], rowSel: '.row', bubbleSel: '.bubble' },
    // 修前形态：流底让位归零（终止钮压末条）。
    negative: '.bgagent-overlay .flow-agent-chat { padding-bottom: 12px !important; scroll-padding-bottom: 12px !important; }',
  },
  {
    name: 'P3-message-stream',
    setup: (page) => page.waitForSelector('#chat .row', { timeout: 15000 }),
    cfg: { scrollSel: '#chat', headerSel: null, fabSels: ['#send-btn'], rowSel: '.row', bubbleSel: '.bubble' },
    // 修前形态：#chat 底让位归零（main.js inline important，须页内同级后写才能压过）。
    negative: async (page) => page.evaluate(() => document.getElementById('chat').style.setProperty('padding-bottom', '8px', 'important')),
  },
  {
    name: 'P4-contacts',
    setup: async (page) => {
      await page.evaluate(async () => { (await import('/js/mobile/navController.js')).navigateTo('contacts'); });
      await page.waitForSelector('#fm-contacts-body .fm-row', { timeout: 15000 });
    },
    // 窗头 = 顶部搜索坞（.mh-search-dock 悬浮，内容按设计从玻璃背后穿过 ——
    // O1 只管「首项让位」；O2 的对照控件只声明**底部**浮件（dock），顶部浮件
    // （搜索坞 / reminder 加号）不进 fabSels，否则「滚到底后中段行从顶部玻璃
    // 下穿过」会被误判成压内容。
    // firstSel = 滚动体**首个可见内容块**（.fm-nf-entry 名册入口卡；首个 .fm-row
    // 在它下方约 180px —— 只看行会漏掉「入口卡穿坞」这一档违例）。
    cfg: { scrollSel: '#fm-contacts-body', headerSel: '.mobile-page[data-page="contacts"] .mh-search-dock', firstSel: '#fm-contacts-body .fm-nf-entry', fabSels: ['#mobile-dock'], rowSel: '.fm-row', bubbleSel: '.__none__' },
    // 修前形态：页体顶让位归零（入口卡顶 24 < 搜索坞底 80 ⇒ O1 必红）。
    negative: '#fm-contacts-body { padding-top: 12px !important; }',
  },
  {
    name: 'P5-settings',
    setup: async (page) => {
      await page.evaluate(async () => { (await import('/js/mobile/navController.js')).navigateTo('settings'); });
      await page.waitForSelector('#settings-content .set-card', { timeout: 15000 });
    },
    // 窗头 = 返回圆钮（悬浮）；底 = dock；行 = set-card（settingsRows 单源组件）。
    cfg: { scrollSel: '#settings-content', headerSel: '.mobile-page[data-page="settings"] .mobile-back-btn', fabSels: ['#mobile-dock'], rowSel: '.set-card', bubbleSel: '.__none__' },
    negative: '#settings-content { padding-bottom: 12px !important; scroll-padding-bottom: 12px !important; }',
  },
  {
    name: 'P6-todo-list',
    setup: openTodoList,
    // 列表页自身无悬浮钮（.ta-fab 双钮属 tasks 页，push 后仍挂在下层但被本页
    // 不透明页体盖住 —— 选择器必须限定在本页可见控件，跨页取矩形 = 误报）。
    // 本页底部浮件 = dock；负对照（页体底垫归零）由 dock 这条 O2 接住。
    cfg: { scrollSel: '.mobile-page[data-page="todo-apple-list"] .mobile-page-body', headerSel: null, fabSels: ['#mobile-dock'], rowSel: '.ta-item', bubbleSel: '.__none__' },
    // 修前形态：**作用层**底让位归零 —— 列表页的滚动末端净空落在他内层
    // `.ta-root { padding-bottom: var(--mobile-scroll-end) }`（= 72px），
    // 页体那条 72 是 dock 净空；只归零页体测不到（净空叠着两层）。
    negative: '.mobile-page[data-page="todo-apple-list"] .ta-root { padding-bottom: 12px !important; scroll-padding-bottom: 12px !important; }',
  },
  {
    name: 'P7-canvas-doc',
    setup: openCanvasDoc,
    cfg: { scrollSel: '.mf-cv-host .canvas-md-scroll', headerSel: null, fabSels: ['.mf-cv-host .canvas-find-toggle', '.ask-fab:not(.note-fab)', '.ask-fab.note-fab'], rowSel: 'p', bubbleSel: '.__none__' },
    // 修前形态（同 canvas-hit-clearance 闸的负对照面）：阅读面贴底、无底让位。
    negative: '.mf-cv-host .canvas-md-scroll { bottom: 0px !important; padding-bottom: 8px !important; }',
  },
  {
    name: 'P8-ask-card',
    setup: async (page) => {
      // AskUser 决策卡（renderAskUser 挂 activeView.dom.chat = #chat 内，卡行
      // .row.ai > .bubble.ob-ghost-seat > .option-box）——同 noclip-floating-geometry
      // 的 mobile-chat-ask-card 装置；state.ws 换哑 send 防渲染期真实外发。
      await page.waitForSelector('#chat .row', { timeout: 15000 });
      await page.evaluate((items) => (async () => {
        const { renderAskUser } = await import('/js/chat.js');
        const { chatViews, setActiveView } = await import('/js/chatView.js');
        const state = (await import('/js/state.js')).default;
        setActiveView(chatViews.primary);
        state.ws = { readyState: 1, send: () => {} };
        renderAskUser(items, 'visual-root', 'Nebula', 'sweep-ask');
      })(), ASK_ITEMS);
      await page.waitForSelector('#chat .option-box', { timeout: 15000 });
    },
    // 卡为流内末件面：O2 对照 = 输入钮（send-btn），O0/O2 的行 = .option-box。
    cfg: { scrollSel: '#chat', headerSel: null, fabSels: ['#send-btn'], rowSel: '.option-box', bubbleSel: '.bubble' },
    // 修前形态：流底让位归零 ⇒ 末件（此处 = 决策卡自身）落进输入钮足迹（O2 红）。
    negative: async (page) => page.evaluate(() => document.getElementById('chat').style.setProperty('padding-bottom', '8px', 'important')),
  },
  // ── 桌面 1280×800 抽检（同类缺陷桌面也在；作者继续令「桌面视口对同面板族抽检」）──
  {
    name: 'D1-create-group-desktop',
    view: { width: 1280, height: 800 },
    setup: openCreateGroup,
    cfg: { scrollSel: '#fm-group-create-overlay .fm-pick-body', headerSel: '#fm-group-create-overlay .fm-pick-header', firstSel: '#fm-group-create-overlay .fm-gs-rename-input, #fm-group-create-overlay .fm-gs-title', fabSels: ['#fm-group-create-overlay .fm-pick-fab'], rowSel: '.fm-pick-row', bubbleSel: '.fm-pick-row .__none__' },
    // 桌面窗头 116px 档（--fm-head-h 桌面覆写）；让位归零 ⇒ 首行必须落进窗头之下。
    // 判据量的是 **scrollTop=0 静止态**（首项 ≥ 窗头底）；截图发生在 MEASURE 滚到
    // 底之后，行从悬浮玻璃头下穿过是设计内行为（同联系人搜索坞），不构成违例，
    // 读图时勿把「滚动态截图」当修前形态。修前实测（feat/base CSS）：pick-body
    // padding-top=2px、gs-title t83 < 窗头底 197；修后（本分支）= var(--fm-head-clear)
    // ⇒ 126px、title t207 > 197 —— 同一修法双形态（.fm-pick-modal 骨架/带名册）
    // 都命中，桌面零新增 CSS。
    negative: '#fm-group-create-overlay .fm-pick-body { padding-top: 2px !important; }',
  },
  {
    name: 'D2-message-stream-desktop',
    view: { width: 1280, height: 800 },
    setup: (page) => page.waitForSelector('#chat .row', { timeout: 15000 }),
    cfg: { scrollSel: '#chat', headerSel: null, fabSels: ['#send-btn'], rowSel: '.row', bubbleSel: '.bubble' },
    // 修前形态同 P3：流底让位归零（inline 同级后写压过 main.js 的 important）。
    negative: async (page) => page.evaluate(() => document.getElementById('chat').style.setProperty('padding-bottom', '8px', 'important')),
  },
  {
    // 文档收藏块（2026-11-23 补齐验收面）：工作台根页「收藏」分组，长标题 + 失效行。
    // 行 = 块内 .canvas-fav-row；bubbleSel 取行本体，使 O4 遍历行内文字子元素（标题/元信息）。
    name: 'P9-workbench-favorites',
    setup: async (page) => {
      await page.evaluate(async () => {
        const m = await import('/js/canvasFavorites.js');
        m.toggleFavoritePath({ path: '/w/sweep-long.md', title: 'A-very-long-document-title-that-must-wrap-without-truncation-in-the-workbench-favorites-block.md' });
        m.toggleFavoritePath({ path: '/w/sweep-short.md', title: 'short.md' });
        (await import('/js/mobile/navController.js')).dockTo('workbench');
      });
      await page.waitForSelector('.mobile-page[data-page="workbench"].live .mf-hub-fav .canvas-fav-row', { timeout: 15000 });
      await new Promise((r) => setTimeout(r, 400));
    },
    cfg: { scrollSel: '.mobile-page[data-page="workbench"] .mobile-page-body', headerSel: null, fabSels: [], rowSel: '.mf-hub-fav .canvas-fav-row', bubbleSel: '.mf-hub-fav .canvas-fav-row' },
    // 修前形态：标题改为不换行且裁切（pre + overflow），须被 O4 判红（假省略/溢出被裁）。
    negative: '.mf-hub-fav .canvas-fav-title { width: 40px !important; white-space: pre !important; overflow: hidden !important; text-overflow: ellipsis !important; }',
  },
];

async function main() {
  const pwRes = loadPlaywright();
  const pw = pwRes.mod.playwright || pwRes.mod;
  const { browser, label } = await launchBrowser(pw);
  console.log(`[mobile-overlap-sweep] browser=${label} root=${WEB_ROOT} view=${VIEW.width}x${VIEW.height}`);
  let failed = 0;
  for (const p of PANELS) {
    const res = await runPanel(browser, p, null);
    if (res.violations.length) {
      failed++;
      console.error(`FAIL ${p.name}: ${res.violations.length} violation(s)`);
      console.error(JSON.stringify(res.violations, null, 1));
    } else {
      console.log(`PASS ${p.name}`);
    }
    if (p.negative) {
      const neg = await runPanel(browser, p, p.negative);
      if (neg.violations.length) {
        console.log(`PASS negative-control ${p.name} (red as expected: ${neg.violations.map((v) => v.rule).join(',')})`);
      } else {
        failed++;
        console.error(`FAIL negative-control ${p.name}: gate is blind (no violation on the pre-fix form)`);
      }
    }
  }
  await browser.close();
  if (failed) { console.error(`mobile-overlap-sweep: ${failed} failure(s)`); process.exit(1); }
  console.log('mobile-overlap-sweep: green');
}

main().catch((e) => { console.error(`mobile-overlap-sweep: ${e && e.message ? e.message.split('\n')[0] : e}`); process.exit(e && /playwright|browser|Executable/i.test(String(e.message)) ? 2 : 1); });

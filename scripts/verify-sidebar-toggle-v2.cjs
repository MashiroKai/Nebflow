// verify-sidebar-toggle-v2.cjs — 侧边栏按钮交互 v2 验收（作者 2026-08-30 01:31 精确化）
//   T1 状态 1: 标签页未开 -> 点击=打开（Canvas 关则同时展开）+ 按下
//   T2 状态 2: 已开+Canvas 开+当前显示 -> 点击=关闭标签页（toggle；末标签关 Canvas=既有语义）
//   T3 状态 3: 已开+Canvas 开+显示别页 -> 点击=切换显示该页（切走按钮非按下）
//   T4 状态 4: 已开+Canvas 关 -> 点击=展开 Canvas 显示该页 + 按下
//   T5 按下态实时跟随标签栏显示切换
//   T6 头部 canvas-toggle 关 -> 三按钮全非按下；重开 -> 当前按下
//   T7 X 关标签 -> 邻标签接管按下；末标签关 -> Canvas 收起 + 非按下
//   T8 boot 恢复回归: 只有 activeTabId 对应按钮亮（旧 bug 双亮）
//   T9 boot 无存档自动开 Teams -> 按下
// 用法: node scripts/verify-sidebar-toggle-v2.cjs [port]
//   port = 静态 serve web/ 根的端口（默认 8388）
const { chromium } = require('playwright');

const PORT = process.argv[2] || '8388';
const BASE = `http://127.0.0.1:${PORT}/index.html`;
const results = [];
function assert(name, cond, extra) {
  results.push({ name, pass: !!cond, extra });
  console.log((cond ? 'PASS' : 'FAIL') + '  ' + name + (cond ? '' : '  ' + JSON.stringify(extra)));
}

const INIT = (seedTabs) => `
  localStorage.setItem('nebflow_token', 'harness-token');
  ${seedTabs ? `localStorage.setItem('nebflow_canvas_tabs', ${JSON.stringify(JSON.stringify(seedTabs))});` : ''}
  window.__wsSent = [];
  class MockWS {
    static OPEN = 1; static CONNECTING = 0; static CLOSING = 2; static CLOSED = 3;
    constructor(url) {
      this.url = url; this.readyState = 0;
      window.__wsMock = this;
    }
    send(data) {
      window.__wsSent.push(JSON.parse(data));
      try {
        const m = JSON.parse(data);
        if (m.type === 'ping' && this.onmessage) {
          this.onmessage({ data: JSON.stringify({ type: 'pong' }) });
        }
      } catch (e) {}
    }
    close() { this.readyState = 3; if (this.onclose) this.onclose(); }
  }
  window.WebSocket = MockWS;
`;

async function boot(page, seedTabs) {
  await page.goto(BASE, { waitUntil: 'domcontentloaded' });
  await page.waitForFunction(() => window.__wsMock, null, { timeout: 8000 });
  await page.evaluate(() => {
    const ws = window.__wsMock;
    ws.readyState = 1;
    if (ws.onopen) ws.onopen();
    const feed = (obj) => ws.onmessage({ data: JSON.stringify(obj) });
    feed({ type: 'serverConfig', config: { configured: true, onboarding: 'done' } });
    feed({ type: 'configData', config: JSON.stringify({ providers: {} }) });
    feed({ type: 'sessionList', sessions: [{ id: 'sess1', title: 'S1', agentName: 'Nebula', createdAt: Date.now() }], activeId: 'sess1' });
    feed({ type: 'agentList', agents: [] });
  });
  await page.waitForTimeout(300);
}

// Button/tab state snapshot for one panel.
async function snap(page, btnId, tabId) {
  return page.evaluate(({ btnId, tabId }) => {
    const btn = document.getElementById(btnId);
    const tab = document.querySelector('.canvas-tab[data-tab-id="' + tabId + '"]');
    const pane = document.querySelector('.canvas-tab-pane[data-tab-id="' + tabId + '"]');
    return {
      canvasOpen: document.body.classList.contains('canvas-open'),
      pressed: btn ? btn.classList.contains('active') : null,
      aria: btn ? btn.getAttribute('aria-pressed') : null,
      tabExists: !!tab,
      tabActive: tab ? tab.classList.contains('active') : false,
      paneActive: pane ? pane.classList.contains('active') : false,
    };
  }, { btnId, tabId });
}

const PANELS = [
  { btn: 'teams-btn', tab: 'teams' },
  { btn: 'flows-btn', tab: 'flows' },
  { btn: 'agents-btn', tab: 'agents' },
];

(async () => {
  const browser = await chromium.launch();
  const errors = [];

  async function freshPage(seedTabs) {
    const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 } });
    await ctx.addInitScript(INIT(seedTabs || null));
    const page = await ctx.newPage();
    page.on('pageerror', (e) => errors.push('pageerror: ' + e.message));
    page.on('console', (m) => {
      if (m.type() === 'error' && !/Failed to load resource|404|net::/i.test(m.text())) {
        errors.push('console: ' + m.text());
      }
    });
    await boot(page, seedTabs);
    return { ctx, page };
  }

  // ── T1: state 1 (tab missing → click opens + expands + pressed) ──
  for (const p of PANELS) {
    const { ctx, page } = await freshPage(null);
    // boot auto-opened Teams when no saved tabs — close all tabs first.
    for (let i = 0; i < 5; i++) {
      const x = await page.$('.canvas-tab .canvas-tab-close');
      if (!x) break;
      await x.click();
      await page.waitForTimeout(300);
    }
    let s = await snap(page, p.btn, p.tab);
    assert('T1-pre ' + p.tab + ' tab closed', !s.tabExists, s);
    await page.click('#' + p.btn);
    await page.waitForTimeout(400);
    s = await snap(page, p.btn, p.tab);
    assert('T1 ' + p.tab + ' S1 open+expand+pressed',
      s.canvasOpen && s.tabExists && s.paneActive && s.pressed && s.aria === 'true', s);
    await ctx.close();
  }

  // ── T2: state 2 (open + canvas open + current → click closes tab) ──
  for (const p of PANELS) {
    const { ctx, page } = await freshPage(null);
    // ensure only this tab: close others
    for (let i = 0; i < 5; i++) {
      const x = await page.$('.canvas-tab:not([data-tab-id="' + p.tab + '"]) .canvas-tab-close');
      if (!x) break;
      await x.click();
      await page.waitForTimeout(300);
    }
    // ensure precondition: tab open + current + canvas open (teams is
    // auto-opened at boot — clicking its button would toggle it OFF).
    let pre = await snap(page, p.btn, p.tab);
    if (!pre.tabExists || !pre.paneActive || !pre.canvasOpen) {
      await page.click('#' + p.btn);
      await page.waitForTimeout(400);
    }
    let s = await snap(page, p.btn, p.tab);
    assert('T2-pre ' + p.tab + ' open+current+pressed', s.tabExists && s.paneActive && s.pressed, s);
    await page.click('#' + p.btn); // toggle off
    await page.waitForTimeout(400);
    s = await snap(page, p.btn, p.tab);
    assert('T2 ' + p.tab + ' S2 click closes tab + unpressed',
      !s.tabExists && s.pressed === false && s.aria === 'false', s);
    assert('T2 ' + p.tab + ' S2 last tab closes canvas (existing semantics)', s.canvasOpen === false, s);
    await ctx.close();
  }

  // ── T3: state 3 (open + canvas open + showing other → click switches) ──
  {
    const { ctx, page } = await freshPage(null);
    await page.click('#flows-btn'); // boot had teams; now open flows -> flows current
    await page.waitForTimeout(400);
    let st = await snap(page, 'teams-btn', 'teams');
    let sf = await snap(page, 'flows-btn', 'flows');
    assert('T3-pre flows current pressed, teams not', sf.paneActive && sf.pressed && !st.pressed && st.tabExists, { st, sf });
    await page.click('#teams-btn'); // switch back to teams
    await page.waitForTimeout(300);
    st = await snap(page, 'teams-btn', 'teams');
    sf = await snap(page, 'flows-btn', 'flows');
    assert('T3 S3 click switches display', st.paneActive && st.pressed && st.aria === 'true', { st });
    assert('T3 S3 switched-away button unpressed (tab still exists)', sf.tabExists && !sf.pressed && sf.aria === 'false', { sf });
    await ctx.close();
  }

  // ── T4: state 4 (tab open + canvas closed → click expands + shows) ──
  for (const p of PANELS) {
    const { ctx, page } = await freshPage(null);
    // ensure target tab open + current (boot auto-opens teams)
    let pre4 = await snap(page, p.btn, p.tab);
    if (!pre4.tabExists || !pre4.paneActive || !pre4.canvasOpen) {
      await page.click('#' + p.btn);
      await page.waitForTimeout(400);
    }
    await page.click('#canvas-close-btn'); // close canvas, tab preserved
    await page.waitForTimeout(450);
    let s = await snap(page, p.btn, p.tab);
    assert('T4-pre ' + p.tab + ' canvas closed + unpressed + tab kept',
      !s.canvasOpen && s.pressed === false && s.tabExists, s);
    await page.click('#' + p.btn);
    await page.waitForTimeout(400);
    s = await snap(page, p.btn, p.tab);
    assert('T4 ' + p.tab + ' S4 click expands canvas + shows + pressed',
      s.canvasOpen && s.paneActive && s.pressed && s.aria === 'true', s);
    await ctx.close();
  }

  // ── T5: pressed state follows tab-bar display switches in real time ──
  {
    const { ctx, page } = await freshPage(null);
    await page.click('#flows-btn');
    await page.waitForTimeout(400);
    // switch via the canvas tab bar (not the activity bar)
    await page.click('.canvas-tab[data-tab-id="teams"]');
    await page.waitForTimeout(200);
    let st = await snap(page, 'teams-btn', 'teams');
    let sf = await snap(page, 'flows-btn', 'flows');
    assert('T5 tab-bar switch -> teams pressed / flows unpressed',
      st.pressed && !sf.pressed && st.paneActive, { st, sf });
    await page.click('.canvas-tab[data-tab-id="flows"]');
    await page.waitForTimeout(200);
    st = await snap(page, 'teams-btn', 'teams');
    sf = await snap(page, 'flows-btn', 'flows');
    assert('T5 tab-bar switch back -> flows pressed / teams unpressed',
      sf.pressed && !st.pressed && sf.paneActive, { st, sf });
    await ctx.close();
  }

  // ── T6: header canvas toggle closes -> all unpressed; reopen -> current pressed ──
  {
    const { ctx, page } = await freshPage(null); // boot: teams open+current
    await page.click('#canvas-toggle-btn'); // close
    await page.waitForTimeout(450);
    const all = await page.evaluate(() => ['teams-btn', 'flows-btn', 'agents-btn'].map((id) => {
      const b = document.getElementById(id);
      return { id, pressed: b.classList.contains('active'), aria: b.getAttribute('aria-pressed') };
    }));
    assert('T6 canvas closed -> all three unpressed',
      all.every((b) => !b.pressed && b.aria === 'false') && !await page.evaluate(() => document.body.classList.contains('canvas-open')), all);
    await page.click('#canvas-toggle-btn'); // reopen
    await page.waitForTimeout(450);
    const st = await snap(page, 'teams-btn', 'teams');
    assert('T6 canvas reopened -> current tab button pressed again',
      st.canvasOpen && st.pressed && st.paneActive, st);
    await ctx.close();
  }

  // ── T7: close via tab X -> unpressed; last-tab close -> canvas closes ──
  {
    const { ctx, page } = await freshPage(null);
    await page.click('#flows-btn');
    await page.waitForTimeout(400); // flows current
    await page.click('.canvas-tab[data-tab-id="flows"] .canvas-tab-close');
    await page.waitForTimeout(400);
    let st = await snap(page, 'teams-btn', 'teams');
    let sf = await snap(page, 'flows-btn', 'flows');
    assert('T7 X-close flows -> flows unpressed, teams becomes current+pressed',
      !sf.tabExists && !sf.pressed && st.pressed && st.paneActive, { st, sf });
    await page.click('.canvas-tab[data-tab-id="teams"] .canvas-tab-close');
    await page.waitForTimeout(400);
    st = await snap(page, 'teams-btn', 'teams');
    assert('T7 X-close last tab -> canvas closed + unpressed',
      !st.tabExists && !st.pressed && !st.canvasOpen, st);
    await ctx.close();
  }

  // ── T8: boot restore regression — only the ACTIVE restored tab is pressed ──
  {
    const seed = { v: 2, tabs: [
      { id: 'teams', title: 'Teams', type: 'teams', absPath: null, pinned: true, closable: true },
      { id: 'flows', title: 'Flows', type: 'flow', absPath: null, pinned: true, closable: true },
    ], activeTabId: 'flows' };
    const { ctx, page } = await freshPage(seed);
    await page.waitForTimeout(500);
    const st = await snap(page, 'teams-btn', 'teams');
    const sf = await snap(page, 'flows-btn', 'flows');
    assert('T8 restore: activeTabId=flows -> flows pressed, teams NOT (old bug lit both)',
      sf.pressed && sf.paneActive && st.tabExists && !st.pressed, { st, sf });
    await ctx.close();
  }

  // ── T9: boot with no saved tabs -> auto-open Teams, pressed (it IS visible) ──
  {
    const { ctx, page } = await freshPage(null);
    const s = await snap(page, 'teams-btn', 'teams');
    assert('T9 boot auto-open teams -> canvas open + teams pressed', s.canvasOpen && s.pressed && s.paneActive, s);
    await ctx.close();
  }

  await browser.close();

  const failed = results.filter((r) => !r.pass);
  console.log('\n=== ' + (results.length - failed.length) + '/' + results.length + ' PASS ===');
  if (errors.length) { console.log('PAGE ERRORS:'); errors.forEach((e) => console.log('  ' + e)); }
  process.exit(failed.length || errors.length ? 1 : 0);
})().catch((e) => { console.error('HARNESS ERROR', e); process.exit(2); });

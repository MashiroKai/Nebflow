// measure-mobile-bottoms.mjs — 逐页「滚到底 → 最后一行内容底缘 vs dock 上缘」读数器
// 用法： node scripts/measure-mobile-bottoms.mjs <label> <webRoot> [outJson]
//
// 逐页目标 = 每页的**真滚域**（reserve owner 同表登记）；对每个真滚域先注入填充
// 撑到溢出，再滚到底读数 ⇒ 与内容长短无关、可复现。
// 输出每页：dockTop / 真滚域盒与裁剪底 / passUnder（滚域是否过底）/ 滚到底末行底缘
//          / 与 dock 的相交 px 与间隙 px / dock 带内卡死件 / 末端净空 token 值
import { chromium } from 'playwright';
import { readFileSync, writeFileSync, mkdirSync } from 'node:fs';
import { join, extname, normalize, dirname, resolve } from 'node:path';

const LABEL = process.argv[2] || 'run';
const WEB = resolve(process.argv[3] || join(import.meta.dirname, '..', 'src', 'main', 'resources', 'web'));
const OUT = process.argv[4] ? resolve(process.argv[4]) : null;

const MIME = {
  '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.svg': 'image/svg+xml',
  '.png': 'image/png', '.ico': 'image/x-icon', '.json': 'application/json',
  '.woff2': 'font/woff2', '.woff': 'font/woff', '.ttf': 'font/ttf',
};
const SID = 'overlay-glass-root-session';
const SEED = 16;

// 逐页目标表：face = 真滚域（末端净空的实际承载体）；owner = 净空挂在哪（用 token 断言）
export const TARGETS = [
  { id: 'messages', face: '.mobile-page[data-page="messages"] #fm-conversations', owner: 'face' },
  { id: 'contacts', face: '.mobile-page[data-page="contacts"] #fm-contacts-body', owner: 'face' },
  { id: 'workbench', face: '.mobile-page[data-page="workbench"] .mobile-page-body', owner: 'self' },
  { id: 'settings', face: '.mobile-page[data-page="settings"] #settings-content', owner: 'face' },
  { id: 'projects', face: '.mobile-page[data-page="projects"] .team-scroll', owner: 'face' },
  { id: 'plugins', face: '.mobile-page[data-page="plugins"] #plugins-content', owner: 'face' },
  { id: 'usage', face: '.mobile-page[data-page="usage"] #usage-dashboard', owner: 'face' },
  { id: 'tasks', face: '.mobile-page[data-page="tasks"] .mobile-page-body', owner: '.ta-root', inject: '.ta-root' },
  { id: 'todo-apple-list', face: '.mobile-page[data-page="todo-apple-list"] .mobile-page-body', owner: '.ta-root', inject: '.ta-root' },
  { id: 'todo-apple-item', face: '.mobile-page[data-page="todo-apple-item"] .mobile-page-body', owner: '.ta-root', inject: '.ta-root' },
  { id: 'search', face: '.mobile-page[data-page="search"] #search-results', owner: 'face' },
  { id: 'reminder', face: '.mobile-page[data-page="reminder"] .reminder-panel-body', owner: 'face' },
  { id: 'daemon', face: '.mobile-page[data-page="daemon"] .daemon-panel-body', owner: 'face' },
  { id: 'background', face: '.mobile-page[data-page="background"] #bg-dropdown', owner: 'face' },
  { id: 'profile', face: '.mobile-page[data-page="profile"] .ap-pane', owner: 'face' },
  { id: 'conversation', face: '.mobile-page[data-page="conversation"] .fm-flow', owner: 'face' },
  { id: 'me', face: '.mobile-page[data-page="me"] .mobile-page-body', owner: 'self' },
  // 不入表（逐页理由，作者令「不该有净空的逐页写清理由」）：
  //   canvas —— 阅读面滚域是收编 pane 内的 .canvas-md-scroll（模拟环境里无文档，
  //             该面不存在）；其末端净空由 canvas 侧既有单源（--mf-ctrl-row +
  //             呼吸，L4915 块）承担，本批不重复造第二套。
  //   graph  —— 全屏画布视图（pan/zoom），无滚动内容、无末行 ⇒ 净空无对象。
];

async function boot(page) {
  await page.emulateMedia({ colorScheme: 'dark' });
  await page.addInitScript(({ n }) => {
    localStorage.setItem('nebflow_token', 'e2e-token');
    localStorage.setItem('nebflow_locale', 'zh-CN');
    Object.defineProperty(navigator.serviceWorker, 'register', { value: () => Promise.resolve(), configurable: true });
    localStorage.setItem('fm_api_mock', '1');
    localStorage.setItem('fm_api_mock_seed', JSON.stringify({
      self: { userId: 'me', neblinkId: 'me@example.com', name: 'Me', avatarUrl: '' },
      users: [],
      friends: [{ userId: 'u-p', neblinkId: 'pagfriend', name: '分页君', avatarUrl: '', since: Date.now() - 86400e3 }],
      incoming: [], outgoing: [],
      conversations: [{
        conversationId: 'c-p',
        friend: { userId: 'u-p', neblinkId: 'pagfriend', name: '分页君', avatarUrl: '' },
        lastMessage: { id: 220, senderId: 'u-p', kind: 'text', body: '历史消息 #220', createdAt: Date.now() - 60e3 },
        unreadCount: 0, pendingAskCount: 0,
      }],
      messages: {
        'c-p': Array.from({ length: n }, (_, i) => ({
          id: 200 + i, senderId: i % 2 ? 'me' : 'u-p', kind: 'text',
          body: `结论与范围 #${200 + i} — 历史输入有三项已修复错误，但剂量口径仍需复核；这是一条足够长的正文用于观察被浮层裁切的边界。`,
          createdAt: Date.now() - (600 - i * 10) * 1e3,
        })),
      },
    }));
  }, { n: SEED });
  await page.route('**/*', (route) => {
    const url = new URL(route.request().url());
    let p = decodeURIComponent(url.pathname);
    if (p === '/') p = '/index.html';
    if (p === '/agents/Nebula/agent.json') return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
    if (p.startsWith('/api/')) {
      if (p === '/api/sessions') return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ sessions: [{ id: SID, name: 'root', agentName: 'Nebula' }], activeId: SID }) });
      if (p === '/api/neblink/status') return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ loggedIn: true, device: { id: 'd1', name: '本机', platform: 'macos', userDescription: '', avatarUrl: '', email: 'me@example.com', displayName: 'Mashiro' }, peers: [] }) });
      return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
    }
    const file = normalize(join(WEB, p));
    if (!file.startsWith(WEB)) return route.fulfill({ status: 403, body: 'forbidden' });
    try { return route.fulfill({ status: 200, contentType: MIME[extname(file)] || 'application/octet-stream', body: readFileSync(file) }); }
    catch { return route.fulfill({ status: 404, body: 'not found' }); }
  });
  await page.routeWebSocket(/\/ws/, (ws) => {
    const cfg = () => ws.send(JSON.stringify({ type: 'configData', config: '{"features":{"friends":true}}', configured: true, onboarding: 'done' }));
    const sess = () => ws.send(JSON.stringify({ type: 'sessionList', sessions: [{ id: SID, name: 'root', agentName: 'Nebula' }], folders: [], activeId: SID }));
    cfg(); sess();
    ws.onMessage((raw) => {
      let m; try { m = JSON.parse(raw); } catch { return; }
      if (m.type === 'getConfig') cfg();
      else if (m.type === 'getSessions' || m.type === 'listSessions') sess();
      else if (m.type === 'getHistory') ws.send(JSON.stringify({ type: 'historyPage', sessionId: m.sessionId, messages: [], hasMore: false, offset: 0 }));
    });
  });
  await page.goto('http://localhost:1/');
  await page.waitForFunction(async (sid) => {
    const s = (await import('/js/state.js')).default;
    return s.activeSessionId === sid && s.ws && s.ws.readyState === 1;
  }, SID, { timeout: 20000 });
  await page.waitForTimeout(400);
}

// 页面内：向指定真滚域注入填充至溢出，滚到底，读数；随后清理
const MEASURE_FACE = async ({ faceSel, ownerSel, injectSel }) => {
  const vh = window.innerHeight;
  const dock = document.querySelector('#mobile-dock');
  const dr = dock.getBoundingClientRect();
  const page = document.querySelector('.mobile-page.live');
  const face = document.querySelector(faceSel);
  if (!face) return { error: 'face not found: ' + faceSel };
  // injectSel 仅用于登记「净空承载体」（owner）在面内的相对位置
  const injHost = injectSel ? (face.querySelector(injectSel) || face) : face;
  // token 数值（声明是 calc 串 ⇒ 用探针元素取计算值）
  const tokenProbe = document.createElement('div');
  tokenProbe.style.cssText = 'position:absolute;visibility:hidden;padding-left:var(--mobile-scroll-end)';
  page.appendChild(tokenProbe);
  const tokenPx = parseFloat(getComputedStyle(tokenProbe).paddingLeft);
  tokenProbe.remove();
  const pr = page.getBoundingClientRect();
  const clipBottomOf = (el) => {
    let b = Infinity;
    for (let n = el; n && n !== document.body; n = n.parentElement) {
      const cs = getComputedStyle(n);
      if (cs.overflowY !== 'visible' || cs.overflowX !== 'visible') {
        const r = n.getBoundingClientRect();
        b = Math.min(b, r.bottom - (parseFloat(cs.borderBottomWidth) || 0));
      }
    }
    return b === Infinity ? vh : b;
  };
  const lastVisibleTextBottom = (root) => {
    let best = -Infinity, who = null;
    const walk = (n) => {
      const cs = getComputedStyle(n);
      if (cs.position === 'fixed') return;   // 固定件不是滚动内容
      const r = n.getBoundingClientRect();
      if (r.height > 0 && r.width > 0) {
        const vis = Math.min(r.bottom, clipBottomOf(n));
        if (vis > r.top + 0.5) {
          for (const c of n.childNodes) {
            if (c.nodeType === 3 && c.textContent.trim()) { if (vis > best) { best = vis; who = n; } break; }
          }
        }
        for (const c of n.children) walk(c);
      }
    };
    walk(root);
    return { bottom: best === -Infinity ? null : best, who: who ? String(who.className || who.tagName) : null };
  };
  const before = {
    box: (() => { const r = face.getBoundingClientRect(); return { top: +r.top.toFixed(1), bottom: +r.bottom.toFixed(1), h: +r.height.toFixed(1) }; })(),
    clipBottom: +clipBottomOf(face).toFixed(1),
    clientH: face.clientHeight, scrollH: face.scrollHeight,
    padBottom: getComputedStyle(face).paddingBottom,
    scrollEnd: getComputedStyle(page).getPropertyValue('--mobile-scroll-end').trim(),
    scrollEndPx: tokenPx,
    restLastBottom: (() => { const t = lastVisibleTextBottom(face); return t.bottom === null ? null : +t.bottom.toFixed(1); })(),
  };
  // 注入：把填充块插在**真滚域内容的首件之前**（撑到溢出）——内容尾端几何原样保留，
  // 因此「滚到底末行底缘 vs dock 上缘」测的就是真实的末端净空（与内容长短无关）。
  const spacer = document.createElement('div');
  spacer.setAttribute('data-neb-filler', '1');
  spacer.style.cssText = 'height:700px;flex:0 0 auto;';
  spacer.textContent = '填充块 — 撑高真滚域用于末端净空读数';
  face.insertBefore(spacer, face.firstChild);
  void face.scrollHeight;
  const fillerCount = 1;
  face.style.scrollBehavior = 'auto';
  face.scrollTop = face.scrollHeight;
  await new Promise((r) => requestAnimationFrame(() => requestAnimationFrame(r)));
  if (face.scrollTop < face.scrollHeight - face.clientHeight - 2) {
    face.scrollTop = face.scrollHeight;
    await new Promise((r) => setTimeout(r, 250));
  }
  if (face.scrollTop < face.scrollHeight - face.clientHeight - 2) {
    face.scrollTop = face.scrollHeight;
    await new Promise((r) => setTimeout(r, 600));
  }
  const atMax = face.scrollTop;
  const maxLast = lastVisibleTextBottom(face);
  const after = {
    box: (() => { const r = face.getBoundingClientRect(); return { top: +r.top.toFixed(1), bottom: +r.bottom.toFixed(1), h: +r.height.toFixed(1) }; })(),
    clipBottom: +clipBottomOf(face).toFixed(1),
    clientH: face.clientHeight, scrollH: face.scrollHeight,
    scrollTopAtMax: +atMax.toFixed(1),
    maxScrollLastBottom: maxLast.bottom === null ? null : +maxLast.bottom.toFixed(1),
    maxScrollLastWho: maxLast.who,
  };
  // owner（净空承载件）的 padding-bottom —— 单源消费断言用
  const ownerEl = ownerSel === 'self' ? face : face.querySelector(ownerSel) || document.querySelector(ownerSel);
  const owner = ownerEl ? {
    sel: (ownerEl.id ? '#' + ownerEl.id : '') + (typeof ownerEl.className === 'string' && ownerEl.className.trim() ? '.' + ownerEl.className.trim().split(/\s+/).slice(0, 2).join('.') : ''),
    padBottom: getComputedStyle(ownerEl).paddingBottom,
  } : null;
  // dock 带内卡死件（不在滚域里的可视件；滚域里的行滚动态属正常）
  const all = [];
  const collect = (n) => { all.push(n); for (const c of n.children) collect(c); };
  collect(page);
  const scrollerAnc = (el) => {
    for (let n = el.parentElement; n && n !== page; n = n.parentElement) {
      const cs = getComputedStyle(n);
      if ((cs.overflowY === 'auto' || cs.overflowY === 'scroll') && n.scrollHeight > n.clientHeight + 1) return n;
    }
    return null;
  };
  const belowDock = [];
  for (const el of all) {
    const r = el.getBoundingClientRect();
    if (r.width < 2 || r.height < 2 || r.bottom <= dr.top + 1) continue;
    const cs = getComputedStyle(el);
    if (cs.visibility === 'hidden' || cs.opacity === '0' || cs.display === 'none') continue;
    if (scrollerAnc(el)) continue;
    let hasText = false;
    for (const c of el.childNodes) if (c.nodeType === 3 && c.textContent.trim()) { hasText = true; break; }
    const isBtn = el.tagName === 'BUTTON' || el.getAttribute('role') === 'button'
      || /fab|btn|button|nav|key|foot|footer/i.test(String(el.className));
    if (!hasText && !isBtn) continue;
    belowDock.push({
      sel: (el.id ? '#' + el.id : el.tagName.toLowerCase()) + (typeof el.className === 'string' && el.className.trim() ? '.' + el.className.trim().split(/\s+/).slice(0, 2).join('.') : ''),
      top: +r.top.toFixed(1), bottom: +r.bottom.toFixed(1), text: hasText,
    });
  }
  // 清理填充
  for (const d of document.querySelectorAll('[data-neb-filler]')) d.remove();
  face.scrollTop = 0;
  return {
    page: page.getAttribute('data-page'), faceSel,
    viewportBottom: vh, dockTop: +dr.top.toFixed(1), dockBottom: +dr.bottom.toFixed(1),
    pageBox: { top: +pr.top.toFixed(1), bottom: +pr.bottom.toFixed(1), w: +pr.width.toFixed(1) },
    fillerCount, before, after, owner, belowDock,
  };
};

const browser = await chromium.launch();
const ctx = await browser.newContext({
  viewport: { width: 375, height: 812 }, hasTouch: true, deviceScaleFactor: 2, colorScheme: 'dark',
});
const page = await ctx.newPage();
await boot(page);
await page.locator('#mobile-dock .dock-key[data-dock="workbench"]').click();
await page.waitForFunction(() => !!document.querySelector('.mobile-page[data-page="workbench"].live'));
await page.waitForTimeout(300);

const results = [];
for (const t of TARGETS) {
  try {
    if (t.id === 'conversation') {
      await page.evaluate(async () => { (await import('/js/mobile/navController.js')).navigateTo('messages'); });
      await page.waitForFunction(() => !!document.querySelector('.mobile-page[data-page="messages"].live'));
      await page.waitForTimeout(600);
      const row = page.locator('.mobile-page[data-page="messages"] .mobile-page-body #fm-conversations .fm-conv-row').first();
      if (await row.count()) await row.click();
      await page.waitForFunction(() => !!document.querySelector('.mobile-page[data-page="conversation"].live'), null, { timeout: 8000 });
    } else {
      await page.evaluate(async (pid) => { (await import('/js/mobile/navController.js')).navigateTo(pid); }, t.id);
      await page.waitForFunction((pid) => !!document.querySelector(`.mobile-page[data-page="${pid}"].live`), t.id, { timeout: 8000 });
    }
    await page.waitForTimeout(900);
    const r = await page.evaluate(MEASURE_FACE, { faceSel: t.face, ownerSel: t.owner });
    r.id = t.id; r.ownerSpec = t.owner;
    r.passUnder = r.error ? null : r.after.clipBottom > r.dockTop;
    if (!r.error) {
      r.overlapPx = r.after.maxScrollLastBottom === null ? null : +(r.after.maxScrollLastBottom - r.dockTop).toFixed(1);
      r.gapPx = r.after.maxScrollLastBottom === null ? null : +(r.dockTop - r.after.maxScrollLastBottom).toFixed(1);
      r.restOverlapPx = r.before.restLastBottom === null ? null : +(r.before.restLastBottom - r.dockTop).toFixed(1);
      r.restGapPx = r.before.restLastBottom === null ? null : +(r.dockTop - r.before.restLastBottom).toFixed(1);
    }
    results.push(r);
    if (r.error) { console.log(`[${t.id}] ERROR ${r.error}`); continue; }
    console.log(`[${t.id}] dock=${r.dockTop}-${r.dockBottom} face=${r.faceSel}`);
    console.log(`    rest:   box=${r.before.box.top}-${r.before.box.bottom} clip=${r.before.clipBottom} dockTop=${r.dockTop} passUnder=${r.before.clipBottom > r.dockTop} padB=${r.before.padBottom} tokenPx=${r.before.scrollEndPx} lastBottom=${r.before.restLastBottom} overlap=${r.restOverlapPx}`);
    console.log(`    max:    spacer=${r.fillerCount} scrollH=${r.after.scrollH}/${r.after.clientH} scrollTop=${r.after.scrollTopAtMax} lastBottom=${r.after.maxScrollLastBottom}(${r.after.maxScrollLastWho}) overlap=${r.overlapPx} gap=${r.gapPx}`);
    console.log(`    owner:  ${r.owner ? r.owner.sel + ' padB=' + r.owner.padBottom : '-'}`);
    if (r.belowDock.length) console.log(`    BELOW-DOCK: ` + r.belowDock.map((b) => `${b.sel} ${b.top}-${b.bottom}`).join(' | '));
  } catch (e) {
    results.push({ id: t.id, error: String(e).slice(0, 200) });
    console.log(`[${t.id}] ERROR ${String(e).slice(0, 160)}`);
  }
}
await browser.close();
const payload = { label: LABEL, webRoot: WEB, measuredAt: new Date().toISOString(), results };
if (OUT) { mkdirSync(dirname(OUT), { recursive: true }); writeFileSync(OUT, JSON.stringify(payload, null, 2)); console.log('wrote ' + OUT); }

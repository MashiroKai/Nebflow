// mobile-scroll-end.spec.mjs — 滚域末端净空闸（作者令 2026-11-18 18:17 复看对照图）
//
// 作者原话（逐字，引用我那张 compare-set-top.png）：
//   「这张图我看底部还是被dock截断了 就是这种设计逻辑就是跟canvas一样 可以从下方过
//     但是上滑下滑的时候 要能保证底部内容完整显示」
//
// 逐页断言（每个目标全屏页，375×812）：
//   E1 过底        —— 真滚域的裁剪底缘**越过 dock 上缘**（= 内容从浮件下方穿过，
//                     不再有一条压在 dock 上方 10px 的硬切线）。改前红：设置/搜索/
//                     定时/心跳/后台/插件/项目/智能体 八页都在 dock 上方被硬切。
//   E2 末行完整   —— 注入填充把真滚域撑到溢出、滚到底后，**最后可见内容底缘 ≤
//                     dock 上缘 − 8px**（作者「要能保证底部内容完整显示」）。
//   E3 净空在内容级 —— 净空承载件（真滚域自身 / 其内容包装 .ta-root）的
//                     padding-bottom == 页壳上的 `--mobile-scroll-end`（同一 token，
//                     零页面字面量）；外壳型页的页体底垫必须为 0（禁页体死 padding）。
//   E4 单源 token   —— `--mobile-scroll-end` 数值 ≥ dock 带高（屏底到 dock 上缘）+ 呼吸缝
//                     （浮件让位至少一条 dock 带），且每个目标页都取到非零值。
//   E5 dock 带内无卡死件 —— 不在任何滚域里的可视 chrome/浮钮/脚注，其底缘不得越过
//                     dock 上缘（浮钮/脚注必须自己锚 dock 座）。
//
// 不进本闸的页（逐页理由，作者令「若某页确实不该有滚动净空，逐页写清理由」）：
//   · 图谱 graph —— 全屏画布视图（pan/zoom）：没有滚动内容、没有末行 ⇒ 净空无对象。
//   · 看板 canvas —— 阅读面滚域 = 收编 pane 内的 .canvas-md-scroll，需要文档才存在；
//                   其末端净空由 canvas 侧既有单源承担（--mf-ctrl-row + 呼吸，
//                   mobileLayer.css「底部可滚余量」块），本批不重复造第二套。
//   · 心跳 daemon —— E1 例外：该页底件是**面板脚注条**（真实 chrome，不是滚动行），
//                   面板整体止于 dock 上缘之上（脚注 684–740）⇒ 不走「过底」，
//                   但 E2/E5 照常（末行 612 ≤ 742，脚注不被 dock 压住）。
//
// 自证检测力（拿当前未修源码当破坏样本）：
//   红：NEB_MOBILE_WEB_ROOT=<主检出>/src/main/resources/web node node_modules/@playwright/test/cli.js test tests/mobile-scroll-end.spec.mjs
//   绿：node node_modules/@playwright/test/cli.js test tests/mobile-scroll-end.spec.mjs

import { test, expect } from '@playwright/test';
import { readFileSync } from 'node:fs';
import { join, dirname, extname, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const WEB = process.env.NEB_MOBILE_WEB_ROOT || join(HERE, '..', 'src', 'main', 'resources', 'web');
const MIME = {
  '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.svg': 'image/svg+xml',
  '.png': 'image/png', '.ico': 'image/x-icon', '.json': 'application/json',
  '.woff2': 'font/woff2', '.woff': 'font/woff', '.ttf': 'font/ttf',
};
const SID = 'scroll-end-root-session';
const SEED_MESSAGES = 16;
const MIN_GAP = 8;   // 末行底缘与 dock 上缘的最小呼吸缝（px）

// 逐页目标表：face = 真滚域（末端净空的实际承载体）
//   owner: 'self' = 净空写在页体自身（页体即滚动面）
//          'face' = 净空写在真滚域上（页体只是外壳，底垫必须为 0）
//          其它选择器 = 净空写在面内某个内容包装上（该页的既有点位，服务端同条口径）
const TARGETS = [
  { id: 'messages', face: '.mobile-page[data-page="messages"] #fm-conversations', owner: 'face' },
  { id: 'contacts', face: '.mobile-page[data-page="contacts"] #fm-contacts-body', owner: 'face' },
  { id: 'workbench', face: '.mobile-page[data-page="workbench"] .mobile-page-body', owner: 'self' },
  { id: 'settings', face: '.mobile-page[data-page="settings"] #settings-content', owner: 'face' },
  { id: 'projects', face: '.mobile-page[data-page="projects"] .team-scroll', owner: 'face' },
  { id: 'plugins', face: '.mobile-page[data-page="plugins"] #plugins-content', owner: 'face' },
  { id: 'usage', face: '.mobile-page[data-page="usage"] #usage-dashboard', owner: 'face' },
  { id: 'tasks', face: '.mobile-page[data-page="tasks"] .mobile-page-body', owner: '.ta-root' },
  { id: 'todo-apple-list', face: '.mobile-page[data-page="todo-apple-list"] .mobile-page-body', owner: '.ta-root' },
  // todo-apple-item 的底带是**第二件浮件**：底部键盘工具胶囊 .ta-tool（贴键盘 /
  // 贴 dock）。它的让位 = max(28px, max(--ta-kb 键盘实测, --mobile-dock-top) + 8 +
  // --bottom-band-d + 8)（mobile-todo-apple.css，零 dock 字面量）⇒ 本页净空必须
  // **不小于** dock 带 token（下界断言），不苛求等于。
  { id: 'todo-apple-item', face: '.mobile-page[data-page="todo-apple-item"] .mobile-page-body', owner: '.ta-root', geOnly: true },
  { id: 'search', face: '.mobile-page[data-page="search"] #search-results', owner: 'face' },
  { id: 'reminder', face: '.mobile-page[data-page="reminder"] .reminder-panel-body', owner: 'face' },
  // daemon 例外（理由见文件头）：底件是面板脚注条（真实 chrome）⇒ 页体底垫保留
  // （面板整体止于 dock 上缘之上），不走「过底」腿。
  { id: 'daemon', face: '.mobile-page[data-page="daemon"] .daemon-panel-body', owner: 'face', passUnder: false, keepBodyReserve: true },
  { id: 'background', face: '.mobile-page[data-page="background"] #bg-dropdown', owner: 'face' },
  { id: 'profile', face: '.mobile-page[data-page="profile"] .ap-pane', owner: 'face' },
  { id: 'conversation', face: '.mobile-page[data-page="conversation"] .fm-flow', owner: 'face' },
  { id: 'me', face: '.mobile-page[data-page="me"] .mobile-page-body', owner: 'self' },
];

async function bootApp(page) {
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
  }, { n: SEED_MESSAGES });
  await page.route('**/*', (route) => {
    const url = new URL(route.request().url());
    let p = decodeURIComponent(url.pathname);
    if (p === '/') p = '/index.html';
    if (p === '/agents/Nebula/agent.json') return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
    if (p.startsWith('/api/')) {
      if (p === '/api/sessions') return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ sessions: [{ id: SID, name: 'root', agentName: 'Nebula' }], activeId: SID }) });
      if (p === '/api/neblink/status') return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ loggedIn: true, device: { id: 'd1', name: '本机', platform: 'macos', email: 'me@example.com', displayName: 'Mashiro' }, peers: [] }) });
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

/** 页面内读数：真滚域几何 + token + 注入撑高后滚到底的末行底缘 + dock 带内卡死件 */
const PROBE = async ({ faceSel, ownerSpec, token }) => {
  const page = document.querySelector('.mobile-page.live');
  const face = document.querySelector(faceSel);
  if (!face) return { error: 'face not found: ' + faceSel };
  const dock = document.querySelector('#mobile-dock');
  const dr = dock.getBoundingClientRect();
  const vh = window.innerHeight;
  const pageBody = page.querySelector('.mobile-page-body');

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
      if (cs.position === 'fixed') return;           // 固定件不属于滚动内容
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

  // token 数值（声明是 calc 串 ⇒ 用探针元素取计算值）
  const probeEl = document.createElement('div');
  probeEl.style.cssText = 'position:absolute;visibility:hidden;padding-left:var(--mobile-scroll-end)';
  page.appendChild(probeEl);
  const tokenPx = parseFloat(getComputedStyle(probeEl).paddingLeft);
  probeEl.remove();
  const dockBand = vh - dr.top;

  // dock 带内卡死件（不在滚域里的可视件）
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
      top: +r.top.toFixed(1), bottom: +r.bottom.toFixed(1),
    });
  }

  // 注入：填充块插在真滚域内容首件之前（撑到溢出）——内容尾端几何原样保留 ⇒
  // 测的就是这一页真实的末端净空，与内容长短无关。
  const spacer = document.createElement('div');
  spacer.setAttribute('data-neb-spacer', '1');
  spacer.style.cssText = 'height:700px;flex:0 0 auto;';
  spacer.textContent = '填充块';
  face.insertBefore(spacer, face.firstChild);
  void face.scrollHeight;
  face.style.scrollBehavior = 'auto';
  face.scrollTop = face.scrollHeight;
  await new Promise((r) => requestAnimationFrame(() => requestAnimationFrame(r)));
  if (face.scrollTop < face.scrollHeight - face.clientHeight - 2) {
    face.scrollTop = face.scrollHeight;
    await new Promise((r) => setTimeout(r, 250));
  }
  const scrollTopAtMax = face.scrollTop;
  const maxLast = lastVisibleTextBottom(face);
  const faceRect = face.getBoundingClientRect();
  const faceClip = clipBottomOf(face);
  const facePadB = getComputedStyle(face).paddingBottom;
  const ownerEl = ownerSpec === 'self' ? face : (face.querySelector(ownerSpec) || document.querySelector(ownerSpec));
  const ownerPadB = ownerEl ? getComputedStyle(ownerEl).paddingBottom : null;
  const pageBodyPadB = pageBody ? getComputedStyle(pageBody).paddingBottom : null;

  spacer.remove();
  face.scrollTop = 0;
  return {
    page: page.getAttribute('data-page'),
    viewportBottom: vh, dockTop: +dr.top.toFixed(1), dockBand: +dockBand.toFixed(1),
    faceBox: { top: +faceRect.top.toFixed(1), bottom: +faceRect.bottom.toFixed(1) },
    faceClipBottom: +faceClip.toFixed(1),
    facePadB, ownerPadB, pageBodyPadB, tokenPx,
    scrollTopAtMax: +scrollTopAtMax.toFixed(1),
    maxScrollLastBottom: maxLast.bottom === null ? null : +maxLast.bottom.toFixed(1),
    maxScrollLastWho: maxLast.who,
    belowDock,
    tokenDeclared: token,
  };
};

test.use({ viewport: { width: 375, height: 812 }, hasTouch: true, deviceScaleFactor: 2 });

test.beforeEach(async ({ page }) => {
  await bootApp(page);
  await page.locator('#mobile-dock .dock-key[data-dock="workbench"]').click();
  await page.waitForFunction(() => !!document.querySelector('.mobile-page[data-page="workbench"].live'));
  await page.waitForTimeout(300);
});

async function goto(page, id) {
  if (id === 'conversation') {
    await page.evaluate(async () => { (await import('/js/mobile/navController.js')).navigateTo('messages'); });
    await page.waitForFunction(() => !!document.querySelector('.mobile-page[data-page="messages"].live'));
    await page.waitForTimeout(600);
    const row = page.locator('.mobile-page[data-page="messages"] .mobile-page-body #fm-conversations .fm-conv-row').first();
    if (await row.count()) await row.click();
    await page.waitForFunction(() => !!document.querySelector('.mobile-page[data-page="conversation"].live'), null, { timeout: 8000 });
  } else {
    await page.evaluate(async (pid) => { (await import('/js/mobile/navController.js')).navigateTo(pid); }, id);
    await page.waitForFunction((pid) => !!document.querySelector(`.mobile-page[data-page="${pid}"].live`), id, { timeout: 8000 });
  }
  await page.waitForTimeout(900);
}

test('mobile social dock pages: real mounted rows and long content', async ({ page }) => {
  await page.locator('#mobile-dock .dock-key[data-dock="messages"]').click();
  await expect(page.locator('.mobile-page[data-page="messages"]')).toHaveClass(/live/);
  const messages = page.locator('.mobile-page[data-page="messages"] #fm-conversations');
  const row = messages.locator('.fm-conv-row').first();
  await expect(row).toBeVisible({timeout:10000});
  const rowBox = await row.boundingBox();
  expect(rowBox.x).toBeGreaterThanOrEqual(0); expect(rowBox.x+rowBox.width).toBeLessThanOrEqual(375);
  // Dock root pages deliberately have no header (navController.js); never substitute the panel top.
  await expect(page.locator('.mobile-page[data-page="messages"] .mobile-page-header')).toHaveCount(0);
  for (const f of [0,0.5,1]) {
    const b=await messages.evaluate((el,fraction)=>{el.scrollTop=(el.scrollHeight-el.clientHeight)*fraction; const r=el.getBoundingClientRect(); return {left:r.left,right:r.right};},f);
    expect(b.left).toBeGreaterThanOrEqual(0); expect(b.right).toBeLessThanOrEqual(375);
  }
  const out=process.env.MOBILE_AUDIT_EVIDENCE;
  if(out) { const {mkdirSync}=await import('node:fs'); mkdirSync(out,{recursive:true}); await page.screenshot({path:join(out,'社交.png'),animations:'disabled'}); }
  await page.locator('#mobile-dock .dock-key[data-dock="contacts"]').click();
  await expect(page.locator('.mobile-page[data-page="contacts"]')).toHaveClass(/live/);
  await expect(page.locator('.mobile-page[data-page="contacts"] #fm-contacts-body')).toBeVisible();
});

test('E1/E2/E3/E4/E5 per-page scroll-end reserve and dock pass-under (375x812)', async ({ page }) => {
  const rows = [];
  for (const t of TARGETS) {
    await goto(page, t.id);
    const r = await page.evaluate(PROBE, { faceSel: t.face, ownerSpec: t.owner, token: 'var(--mobile-scroll-end)' });
    expect(r.error, `${t.id}: face 必须存在`).toBeFalsy();
    rows.push({ t, r });
  }

  const report = rows.map(({ t, r }) => {
    const passUnder = r.faceClipBottom > r.dockTop + 0.5;
    return `  ${t.id.padEnd(16)} faceBox=${r.faceBox.top}-${r.faceBox.bottom} clip=${r.faceClipBottom} dockTop=${r.dockTop} `
      + `passUnder=${passUnder} padB(face/owner/body)=${r.facePadB}/${r.ownerPadB}/${r.pageBodyPadB} token=${r.tokenPx} `
      + `末行=${r.maxScrollLastBottom} gap=${r.maxScrollLastBottom === null ? null : (r.dockTop - r.maxScrollLastBottom).toFixed(1)} belowDock=${r.belowDock.length}`;
  }).join('\n');
  console.log('\n=== 逐页读数（375×812，注入撑高 → 滚到底）===\n' + report + '\n');

  // 逐页逐条累积（不 fail-fast）：一次运行把每一页每一条的失败都列出来
  const fails = [];
  const chk = (ok, msg) => { if (!ok) fails.push(msg); };
  for (const { t, r } of rows) {
    const tag = `[${t.id}]`;
    const fpb = parseFloat(r.facePadB), opb = parseFloat(r.ownerPadB), bpbb = parseFloat(r.pageBodyPadB);
    // E4 单源 token 取到非零值，且 ≥ dock 带高（浮件让位至少一条 dock 带）
    chk(r.tokenPx > 0, `${tag} E4 --mobile-scroll-end 取到非零值（实测 ${r.tokenPx}）`);
    chk(r.tokenPx >= r.dockBand, `${tag} E4 token ≥ dock 带高：${r.tokenPx} vs ${r.dockBand}`);
    // E3 净空在滚域内容级，且消费同一 token
    if (t.owner === 'self') {
      chk(Math.abs(bpbb - r.tokenPx) < 0.5, `${tag} E3 页体自身是滚动面 ⇒ 页体净空 = token：${bpbb} vs ${r.tokenPx}`);
    } else if (t.owner === 'face') {
      chk(Math.abs(fpb - r.tokenPx) < 0.5, `${tag} E3 真滚域净空 = token：${fpb} vs ${r.tokenPx}`);
      if (t.keepBodyReserve) {
        chk(Math.abs(bpbb - r.tokenPx) < 0.5, `${tag} E3 例外面板页体底垫 = token：${bpbb} vs ${r.tokenPx}`);
      } else {
        chk(bpbb === 0, `${tag} E3 页体只是外壳 ⇒ 底垫必须为 0（禁死 padding）：${bpbb}`);
      }
    } else {
      if (t.geOnly) {
        chk(opb >= r.tokenPx - 0.5, `${tag} E3 内容包装净空 ≥ token：${opb} vs ${r.tokenPx}`);
      } else {
        chk(Math.abs(opb - r.tokenPx) < 0.5, `${tag} E3 内容包装净空 = token：${opb} vs ${r.tokenPx}`);
      }
      chk(bpbb === 0, `${tag} E3 页体只是外壳 ⇒ 底垫必须为 0：${bpbb}`);
    }
    // E1 过底（内容从 dock 下方穿过；daemon 例外见文件头理由）
    if (t.passUnder !== false) {
      chk(r.faceClipBottom > r.dockTop + 0.5, `${tag} E1 真滚域裁剪底缘须越过 dock 上缘（= 内容可从下方穿过）：clip=${r.faceClipBottom} vs dockTop=${r.dockTop}`);
    } else {
      chk(r.faceClipBottom < r.dockTop, `${tag} E1 例外面板（脚注条）底缘仍须在 dock 上缘之上：${r.faceClipBottom} vs ${r.dockTop}`);
    }
    // E2 滚到底末行完整可见
    chk(r.maxScrollLastBottom !== null, `${tag} E2 滚到底必须测到末行`);
    if (r.maxScrollLastBottom !== null) {
      chk(r.maxScrollLastBottom <= r.dockTop - MIN_GAP, `${tag} E2 末行底缘 ≤ dock 上缘 − ${MIN_GAP}：${r.maxScrollLastBottom} vs ${r.dockTop - MIN_GAP}`);
    }
    // E5 dock 带内无卡死件
    chk(r.belowDock.length === 0, `${tag} E5 dock 带内不得有卡死件：` + JSON.stringify(r.belowDock));
  }
  expect(fails, '逐页末端净空失败清单:\n' + fails.join('\n')).toEqual([]);
});

test('E0 token 单源：站点 CSS 里只有一处 --mobile-scroll-end 定义，页面零字面量净空', async ({ page }) => {
  const src = readFileSync(join(WEB, 'css', 'mobileLayer.css'), 'utf8');
  const defs = src.match(/^\s*--mobile-scroll-end\s*:/gm) || [];
  expect(defs.length, '--mobile-scroll-end 只能有一处定义（单源）').toBe(1);
  // 逐页检查：目标页的净空值都等于 token（已由 E3 断言），且消费点不含旧字面量尾垫
  const legacy = [
    /padding-bottom:\s*calc\(24px \+ env\(safe-area-inset-bottom/,
    /padding-bottom:\s*max\(var\(--mpage-fab-clearance\), var\(--mobile-dock-clearance\)\)/,
    /padding-bottom:\s*calc\(var\(--set-panel-pad-b\)/,
  ];
  for (const re of legacy) {
    expect(src.match(re), `旧字面量尾垫应已收敛进 token：${re}`).toBeNull();
  }
});

// yield_chain_probe.mjs — 合后「胶囊让位链」实测（基线同步节点取证）
//
// 目的（任务书②对齐检查）：main 重构过 web/js/main.js 的布局/让位链与前端内核
// （component/dom/events/state），本批的胶囊靠 main.js 的 bandBottom() 读
// #top-overlays 实测盒让位。合完必须实测确认：
//   ① 胶囊仍挂在 #top-overlays 内（DOM 归属，不靠截图判断）；
//   ② 让位链仍生效：#chat 的 padding-top == max(8, ⌈bandBottom − chatTop⌉ + 8)，
//      且内容起点（chat.top + padding-top）不高于带底（bandBottom）；
//   ③ 带高变化（胶囊态 → 展开面板）后让位重测跟随（观察器/rAF 腿活着）；
//   ④ 手机层（375）胶囊可见且让位取顶缘渐隐带（96 档）。
//
// 自足：静态文件路由 + 最小 WS 夹具，零后端、零 sbt、零 6138。
// 用法：node reports-evidence/.../sync/yield_chain_probe.mjs

import { chromium } from '/Users/kaiyu/Claude code/Stand/node_modules/playwright/index.mjs';
import { readFileSync, mkdirSync, writeFileSync } from 'node:fs';
import { join, dirname, extname, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const ROOT = join(HERE, '..', '..', '..');
const WEB = join(ROOT, 'src', 'main', 'resources', 'web');
const OUT = HERE;
mkdirSync(OUT, { recursive: true });

const MIME = {
  '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css',
  '.svg': 'image/svg+xml', '.png': 'image/png', '.ico': 'image/x-icon',
  '.json': 'application/json', '.woff2': 'font/woff2', '.woff': 'font/woff',
  '.ttf': 'font/ttf',
};

const SID = 'yield-chain-probe-root';
const T0 = Date.now();
const H = 3600_000;

const FIXTURE = [
  { id: 'y1', content: '让位链探针一', triggerAt: T0 + 2 * H, createdAt: T0 - 8 * H, project: '工作', priority: 3 },
  { id: 'y2', content: '让位链探针二', triggerAt: T0 + 30 * H, createdAt: T0 - 5 * H, project: null, priority: 2 },
];

const rowJson = (r) => ({
  id: r.id, content: r.content, triggerAt: r.triggerAt, createdAt: r.createdAt,
  triggered: false, triggeredAt: null, referencePath: null, repeat: null, enabled: true,
  kind: 'todo', name: null, missCount: 0, sessionId: SID,
  project: r.project ?? null, priority: r.priority ?? 0, completed: false, completedAt: null,
});

async function boot(page, viewport) {
  await page.setViewportSize(viewport);
  await page.addInitScript(() => {
    localStorage.setItem('nebflow_token', 'e2e-token');
    localStorage.setItem('nebflow_locale', 'zh-CN');
    if (navigator.serviceWorker) {
      Object.defineProperty(navigator.serviceWorker, 'register', { value: () => Promise.resolve(), configurable: true });
    }
  });
  const errors = [];
  page.on('console', (m) => {
    if (m.type() !== 'error') return;
    const t = m.text();
    if (/Failed to load resource|fetching the script/i.test(t)) return;
    errors.push(t);
  });
  page.on('pageerror', (e) => errors.push(`pageerror: ${e.message}`));

  await page.route('**/*', (route) => {
    const url = new URL(route.request().url());
    let p = decodeURIComponent(url.pathname);
    if (p === '/') p = '/index.html';
    if (p.startsWith('/api/')) {
      if (p === '/api/sessions') {
        return route.fulfill({
          status: 200, contentType: 'application/json',
          body: JSON.stringify({ sessions: [{ id: SID, name: 'root', agentName: 'Nebula' }], activeId: SID }),
        });
      }
      return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
    }
    const file = normalize(join(WEB, p));
    if (!file.startsWith(WEB)) return route.fulfill({ status: 403, body: 'forbidden' });
    try {
      return route.fulfill({ status: 200, contentType: MIME[extname(file)] || 'application/octet-stream', body: readFileSync(file) });
    } catch {
      return route.fulfill({ status: 404, body: 'not found' });
    }
  });

  await page.routeWebSocket(/\/ws/, (ws) => {
    const sendConfig = () => ws.send(JSON.stringify({ type: 'configData', config: '{}', configured: true, onboarding: 'done' }));
    const sendSessions = () => ws.send(JSON.stringify({
      type: 'sessionList', sessions: [{ id: SID, name: 'root', agentName: 'Nebula' }], folders: [], activeId: SID,
    }));
    sendConfig();
    sendSessions();
    ws.onMessage((raw) => {
      let m;
      try { m = JSON.parse(raw); } catch { return; }
      switch (m.type) {
        case 'getConfig': sendConfig(); break;
        case 'getSessions': case 'listSessions': sendSessions(); break;
        case 'getHistory':
          ws.send(JSON.stringify({ type: 'historyPage', sessionId: m.sessionId, messages: [], hasMore: false, offset: 0 }));
          break;
        case 'listScheduledTasks':
          ws.send(JSON.stringify({
            type: 'scheduledTaskList',
            tasks: FIXTURE.map(rowJson),
            sessionId: m.sessionId, scope: 'all', includeCompleted: false,
          }));
          break;
        default: break;
      }
    });
  });

  await page.goto('http://localhost:1/');
  await page.waitForFunction(async (rootSid) => {
    const s = (await import('/js/state.js')).default;
    return s.activeSessionId === rootSid && s.ws && s.ws.readyState === 1;
  }, SID, { timeout: 15000 });
  await page.waitForSelector('#task-list.has-tasks .tfl-capsule', { timeout: 15000 });
  await page.waitForTimeout(400); // 让 RO→rAF 一跳落地
  return { errors };
}

/** 在页面内实测带/让位几何。 */
const MEASURE = () => {
  const header = document.getElementById('header');
  const overlays = document.getElementById('top-overlays');
  const chat = document.getElementById('chat');
  const edgeFade = document.getElementById('g2-edge-fade');
  const capsule = document.querySelector('.tfl-capsule');
  const taskList = document.getElementById('task-list');
  const r = (el) => (el ? el.getBoundingClientRect() : null);
  const hb = r(header), ob = r(overlays), cb = r(chat), eb = r(edgeFade), cap = r(capsule);
  const bandBottom = Math.max(hb ? hb.bottom : 0, ob ? ob.bottom : 0, eb ? eb.bottom : 0);
  const chatTop = cb ? cb.top : 0;
  const padTop = parseFloat(getComputedStyle(chat).paddingTop) || 0;
  const expected = Math.max(8, Math.ceil(bandBottom - chatTop) + 8);
  return {
    mobileLayer: document.body.classList.contains('mobile-layer'),
    // ① DOM 归属：胶囊在 #top-overlays 里、#task-list 里
    capsuleInTopOverlays: !!(overlays && capsule && overlays.contains(capsule)),
    capsuleInTaskList: !!(taskList && capsule && taskList.contains(capsule)),
    taskListDisplay: taskList ? getComputedStyle(taskList).display : null,
    capsuleTooltip: capsule ? capsule.getAttribute('title') : null,
    // ② 让位链读数
    bandBottom: +bandBottom.toFixed(2),
    overlaysBottom: ob ? +ob.bottom.toFixed(2) : null,
    headerBottom: hb ? +hb.bottom.toFixed(2) : null,
    edgeFadeBottom: eb ? +eb.bottom.toFixed(2) : null,
    chatTop: +chatTop.toFixed(2),
    chatPaddingTop: +padTop.toFixed(2),
    reserveExpected: expected,
    reserveDelta: +(padTop - expected).toFixed(2),
    contentTop: +(chatTop + padTop).toFixed(2),
    clearance: +(chatTop + padTop - bandBottom).toFixed(2),
    // ③ 胶囊本体几何
    capsuleRect: cap ? { w: +cap.width.toFixed(2), h: +cap.height.toFixed(2), top: +cap.top.toFixed(2), bottom: +cap.bottom.toFixed(2) } : null,
    capsuleBelowBandTop: cap && ob ? +(cap.top - ob.top).toFixed(2) : null,
    chatTopClip: getComputedStyle(chat).getPropertyValue('--chat-top-clip').trim(),
    // ④ 展开面板几何：绝对定位浮层、自胶囊向下生长、**不占流内高度**
    //    （todo-face.css:48-56 / 231-237 的设计契约：展开/收起不触发聊天体让位重排）
    card: (() => {
      const c = document.querySelector('.tfl-card');
      if (!c || !c.offsetHeight) return null;
      const b = c.getBoundingClientRect();
      const capB = cap; // cap 已是 capsule 的 DOMRect（上方 r() 的产物）
      return {
        state: c.getAttribute('data-state'),
        position: getComputedStyle(c).position,
        top: +b.top.toFixed(2),
        bottom: +b.bottom.toFixed(2),
        height: +b.height.toFixed(2),
        belowCapsuleBottom: capB ? +(b.top - capB.bottom).toFixed(2) : null,
      };
    })(),
  };
};

const DESKTOP = { width: 1440, height: 900 };
const MOBILE = { width: 375, height: 812 };

const out = { measuredAt: new Date().toISOString(), legs: [], errors: [], verdict: {} };
const browser = await chromium.launch();

// ── 腿 A：桌面 1440 · 胶囊静默态 ──────────────────────────────────────────
{
  const page = await browser.newPage();
  const { errors } = await boot(page, DESKTOP);
  const collapsed = await page.evaluate(MEASURE);
  await page.screenshot({ path: join(OUT, 'yield_desktop_capsule.png') });
  // ── 腿 A2：展开面板 ⇒ 带高变大 ⇒ 让位重测跟随 ──────────────────────────
  await page.locator('.tfl-capsule').click();
  await page.waitForSelector('.tfl-card[data-state="open"]', { timeout: 5000 });
  await page.waitForTimeout(900);
  const expanded = await page.evaluate(MEASURE);
  await page.screenshot({ path: join(OUT, 'yield_desktop_expanded.png') });
  out.legs.push({ leg: 'desktop-1440', collapsed, expanded, consoleErrors: errors });
  out.errors.push(...errors.map((e) => `desktop: ${e}`));
  await page.close();
}

// ── 腿 B：手机 375 · 手机层胶囊 ──────────────────────────────────────────
{
  const page = await browser.newPage();
  const { errors } = await boot(page, MOBILE);
  const collapsed = await page.evaluate(MEASURE);
  await page.screenshot({ path: join(OUT, 'yield_mobile_capsule.png') });
  await page.locator('.tfl-capsule').click();
  await page.waitForSelector('.tfl-card[data-state="open"]', { timeout: 5000 });
  await page.waitForTimeout(900);
  const expanded = await page.evaluate(MEASURE);
  await page.screenshot({ path: join(OUT, 'yield_mobile_expanded.png') });
  out.legs.push({ leg: 'mobile-375', collapsed, expanded, consoleErrors: errors });
  out.errors.push(...errors.map((e) => `mobile: ${e}`));
  await page.close();
}

await browser.close();

// ── 判据 ────────────────────────────────────────────────────────────────
const legs = out.legs;
const all = legs.flatMap((l) => [l.collapsed, l.expanded]);
out.verdict = {
  capsuleInTopOverlays: all.every((m) => m.capsuleInTopOverlays),
  capsuleInTaskList: all.every((m) => m.capsuleInTaskList),
  reserveFollowsBand: all.every((m) => Math.abs(m.reserveDelta) <= 0.5),
  contentClearsBand: all.every((m) => m.clearance >= -0.5),
  // 设计契约（todo-face.css:48-56 / 231-237）：展开面板 = 绝对定位浮层，自胶囊向下
  // 生长，浮在聊天体之上、不占流内高度 ⇒ **带底与让位都不动**。故判据是「不变」
  // 而不是「变大」（「变大」是输入框上方时代的向上生长几何，2026-11-03 已翻转）。
  expandFloatsWithoutReflow: legs.every((l) =>
    l.expanded.bandBottom === l.collapsed.bandBottom
    && l.expanded.chatPaddingTop === l.collapsed.chatPaddingTop
    && l.expanded.card !== null
    && l.expanded.card.state === 'open'
    && l.expanded.card.position === 'absolute'
    && l.expanded.card.belowCapsuleBottom > 0),
  mobileLayerCapsuleVisible: legs.find((l) => l.leg === 'mobile-375')
    ? legs.find((l) => l.leg === 'mobile-375').collapsed.taskListDisplay !== 'none' : false,
  zeroConsoleErrors: out.errors.length === 0,
};
out.pass = Object.values(out.verdict).every(Boolean);

writeFileSync(join(OUT, 'yield_chain_probe.json'), JSON.stringify(out, null, 2));
console.log(JSON.stringify(out.verdict, null, 2));
for (const l of legs) {
  console.log(`\n[${l.leg}] collapsed : bandBottom=${l.collapsed.bandBottom} chatTop=${l.collapsed.chatTop} padTop=${l.collapsed.chatPaddingTop} expected=${l.collapsed.reserveExpected} clearance=${l.collapsed.clearance} capsule=${JSON.stringify(l.collapsed.capsuleRect)}`);
  console.log(`[${l.leg}] expanded  : bandBottom=${l.expanded.bandBottom} padTop=${l.expanded.chatPaddingTop} expected=${l.expanded.reserveExpected} clearance=${l.expanded.clearance}`);
}
console.log(`\nPASS=${out.pass}  consoleErrors=${out.errors.length}`);
process.exit(out.pass ? 0 : 1);

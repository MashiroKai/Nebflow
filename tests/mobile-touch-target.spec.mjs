#!/usr/bin/env node
// mobile-touch-target.spec.mjs — 手机端交互标准 · 触控热区机械断言
//（作者令 2026-11-02「触控热区机械断言（脚本量出所有可点元素 ≥44×44，
//  列出仍不达标的并说明理由）」）
//
// 口径：
//   · 视口 375×812 @2x 触屏 + body.mobile-layer 激活（真实手机视口）；
//   · 逐面遍历：任务 / 定时任务 / 心跳 / 设置（+ 各子面板）/ 项目 / token 用量 /
//     待办 / 插件 / 后台任务 / 消息 / 联系人 / 工作台 / 个人主页 / 看板 / 搜索；
//   · 「可点元素」判据 = 真实可交互件：button / a[href] / [role=button|switch|tab]
//     / input[type=checkbox|radio] / label[for] / [onclick] / 显式 cursor:pointer
//     —— 但**排除**：display:none / visibility:hidden / 零面积 / 纯装饰件
//     （pointer-events:none）/ 屏幕外（rect 完全落在视口外）。
//   · 热区量法 = 元素自身 rect 与 ::before/::after 外扩后的**并集**（命中盒语义：
//     浏览器把伪元素上的指针事件算给宿主，故外扩生效）。
//   · 输出：逐面 PASS/FAIL 清单 + 仍不达标项（含 class/rect/理由），落盘 JSON。
//
// 用法：
//   node tests/mobile-touch-target.spec.mjs [--web-root <dir>] [--out <json>]
//     默认 web-root = 本仓 src/main/resources/web；「修前」对照传 HEAD 临时树。
//   退出码 0 = 全部达标（或仅存在豁免项）；1 = 存在未豁免的不达标项。
//
// 豁免白名单（EXEMPT）：每条都必须写明理由，且只允许「本体即图形语义件、
// 放大破坏版式、且已有 ≥44 命中盒或确属非用户可点」三类。

import { chromium } from '@playwright/test';
import { readFileSync, writeFileSync, mkdirSync } from 'node:fs';
import { join, dirname, extname, normalize, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const args = process.argv.slice(2);
const arg = (n, d) => {
  const i = args.indexOf(n);
  return i >= 0 && args[i + 1] ? args[i + 1] : d;
};
const WEB = resolve(arg('--web-root', join(dirname(fileURLToPath(import.meta.url)), '..', 'src', 'main', 'resources', 'web')));
const OUT_JSON = resolve(arg('--out', join(dirname(fileURLToPath(import.meta.url)), '..', 'evidence', 'mobile-interaction-standard', 'touch-targets.json')));

const MIME = {
  '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css',
  '.svg': 'image/svg+xml', '.png': 'image/png', '.ico': 'image/x-icon',
  '.json': 'application/json', '.woff2': 'font/woff2', '.woff': 'font/woff',
  '.ttf': 'font/ttf',
};
const SID = 'tt-root-session';
const MIN = 44;

/**
 * 豁免白名单：selector 子串 → 理由。仅在「量出来确实不达标」时用于说明，
 * 不会把真实不达标项静默放过（报告里单列「豁免」段）。
 */
const EXEMPT = [
  {
    match: 'ud-cell',
    why: '用量热力图格子（52 周 × 7 天日历全览）。几何上不可达 44：52 列 × 44px'
      + ' = 2288px，是 375px 视宽的 6.1 倍 —— 照 HIG 放大后整图失去「一年一览」'
      + ' 语义（热力图全部价值就是同屏可比）。本批已做的缓解：① 手机层格子'
      + ' 11 → 20px（--ud-cell，可读性与命中率翻倍，横滚保留）；② 命中面再外扩'
      + ' 4px ⇒ 实测 28×28；③ 点击代理 = closest(.ud-cell[data-date])（点在格间'
      + ' 缝隙也命中最近格），等效热区 > 实际格面；④ 精确读数走钻取表（本批已把'
      + ' 表字号提到 --mfs-min），正式操作面不经格子。',
  },
];

const browser = await chromium.launch();
const context = await browser.newContext({
  viewport: { width: 375, height: 812 }, deviceScaleFactor: 2, hasTouch: true, colorScheme: 'light',
});
const page = await context.newPage();
const errors = [];
page.on('console', (m) => { if (m.type() === 'error') errors.push(m.text()); });
page.on('pageerror', (e) => errors.push(String(e)));

await page.addInitScript(() => {
  localStorage.setItem('nebflow_token', 'tt-token');
  localStorage.setItem('nebflow_locale', 'zh-CN');
  Object.defineProperty(navigator.serviceWorker, 'register', { value: () => Promise.resolve() });
});

const T0 = Date.now();
await page.route('**/*', (route) => {
  const url = new URL(route.request().url());
  let p = decodeURIComponent(url.pathname);
  if (p === '/') p = '/index.html';
  if (url.pathname === '/favicon.ico') return route.fulfill({ status: 200, contentType: 'image/x-icon', body: '' });
  if (p === '/agents/Nebula/agent.json') {
    return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ displayName: '笨笨' }) });
  }
  if (p.startsWith('/api/sessions')) {
    return route.fulfill({
      status: 200, contentType: 'application/json',
      body: JSON.stringify({ sessions: [{ id: SID, name: 'root', agentName: 'Nebula' }], activeId: SID }),
    });
  }
  if (p.startsWith('/api/projects')) {
    return route.fulfill({
      status: 200, contentType: 'application/json',
      body: JSON.stringify({
        projects: [
          { name: 'CdZnTe 探测器研究', workspace: '/Users/kaiyu/.stand/projects/CdZnTe', description: '探测器制备与能谱表征', agentFile: true, createdAt: T0 - 86400_000 },
          { name: 'SlideBlocks Studio', workspace: '/Users/kaiyu/Claude code/SlideBlocksStudio', description: '演示引擎与课件编辑器', agentFile: true, createdAt: T0 - 7200_000 },
        ],
        unmounted: [],
      }),
    });
  }
  if (p.startsWith('/api/')) return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
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
    type: 'sessionList',
    sessions: [{ id: SID, name: '笨笨的会话', agentName: 'Nebula' }],
    folders: [], activeId: SID,
  }));
  const sendBgTasks = () => ws.send(JSON.stringify({
    type: 'activeBgTasks',
    tasks: {
      [SID]: [
        { taskId: 'tt-1', description: '跑手机端触控回归', status: 'running', startedAt: T0 - 65_000, sessionId: SID, kind: 'local', origin: 'nebula', originLabel: 'Nebula', heartbeat: { idleMs: 3000, outputLines: 14 } },
      ],
    },
  }));
  const sendTasks = () => ws.send(JSON.stringify({
    type: 'scheduledTaskList',
    tasks: [
      { id: 't1', content: '每小时巡检构建', triggerAt: T0 + 3_600_000, repeat: 'hourly', enabled: true, triggered: false, lastTriggered: T0 - 3_600_000 },
      { id: 't2', content: '每天晨报摘要', triggerAt: T0 + 86_400_000, repeat: 'daily', enabled: true, triggered: false },
      { id: 't3', content: '单次：回看第 3 节', triggerAt: T0 + 1_800_000, enabled: true, triggered: false },
      { id: 't4', content: '已停用的旧任务', triggerAt: T0 + 999_999_999, enabled: false, triggered: false },
    ],
  }));
  sendConfig(); sendSessions(); sendBgTasks(); sendTasks();
  ws.onMessage((raw) => {
    let m; try { m = JSON.parse(raw); } catch { return; }
    if (m.type === 'getConfig') sendConfig();
    else if (m.type === 'getSessions' || m.type === 'listSessions') sendSessions();
    else if (m.type === 'getMemory') ws.send(JSON.stringify({ type: 'memoryData', scope: m.scope, content: '触控标准自验样例。' }));
    else if (m.type === 'listScheduledTasks') sendTasks();
    else if (m.type === 'getHistory') ws.send(JSON.stringify({ type: 'historyPage', sessionId: m.sessionId, messages: [], hasMore: false, offset: 0 }));
  });
});

await page.goto('http://localhost:1/');
await page.waitForFunction(async (sid) => {
  const s = (await import('/js/state.js')).default;
  return s.activeSessionId === sid && s.ws && s.ws.readyState === 1;
}, SID, { timeout: 20000 });
await page.waitForTimeout(900);

/** 装扮夹具：让各面都有真实内容（否则空态没有可点件可量）。 */
async function seedFixtures() {
  await page.evaluate(async (sid) => {
    const st = (await import('/js/state.js')).default;
    // 后台任务（合并面板行 + dock 徽标）
    st.sessionBgAgents[sid] = {
      'delegate-tt-1': { name: '触控批实现', kind: 'Delegate', sessionId: 'delegate-tt-1', status: 'running', startedAt: Date.now() - 95_000 },
    };
    st.updateBgAgentIndicator?.();
    // 看板：两张文档卡（chip 条 + 堆叠面）
    const cv = await import('/js/canvas.js');
    cv.openTab('tt-a', '甲文档', { type: 'tt-a' });
    cv.openTab('tt-b', '乙文档', { type: 'tt-b' });
  }, SID).catch(() => {});
  await page.waitForTimeout(400);
}

await seedFixtures();

/** 登录卡退场（夹具卫生）：空态登录模态是全屏拦截层（login-idle），会吃掉
 *  后续所有导航点击。它本身先在「login」面单独量过标准，随后从 DOM 摘除。 */
async function dismissLoginModal() {
  await page.evaluate(() => {
    const m = document.getElementById('nebflow-login-modal');
    if (m) m.remove();
    document.body.classList.remove('login-gate');
  });
  await page.waitForTimeout(150);
}

// ── 量测探针：一页之内把所有可交互件的命中盒量出来 ──────────────────────
const probeScript = () => {
  const MIN = 44;
  const SEL = 'button, a[href], [role="button"], [role="switch"], [role="tab"],'
    + ' input[type="checkbox"], input[type="radio"], label[for], [onclick]';
  const out = [];
  const seen = new Set();
  const vw = window.innerWidth, vh = window.innerHeight;

  const hitBox = (el) => {
    // 本体 rect ∪ ::before/::after 外扩 rect（伪元素指针事件归宿主）。
    let r = el.getBoundingClientRect();
    let box = { l: r.left, t: r.top, r: r.right, b: r.bottom };
    for (const pe of ['::before', '::after']) {
      const cs = getComputedStyle(el, pe);
      if (cs.content === 'none' || cs.content === 'normal') continue;
      if (cs.position !== 'absolute' && cs.position !== 'fixed') continue;
      const w = parseFloat(cs.width), h = parseFloat(cs.height);
      const insL = parseFloat(cs.left), insR = parseFloat(cs.right);
      const insT = parseFloat(cs.top), insB = parseFloat(cs.bottom);
      if (!Number.isFinite(w) || !Number.isFinite(h)) continue;
      // 绝对定位伪元素：按 left/right/top/bottom 推算相对宿主 padding box 的位置。
      const pr = el.getBoundingClientRect();
      let l, t, ww, hh;
      if (Number.isFinite(insL)) l = pr.left + insL;
      else if (Number.isFinite(insR)) l = pr.right - insR - w;
      else l = pr.left + (pr.width - w) / 2;
      if (Number.isFinite(insT)) t = pr.top + insT;
      else if (Number.isFinite(insB)) t = pr.bottom - insB - h;
      else t = pr.top + (pr.height - h) / 2;
      ww = w; hh = h;
      box = {
        l: Math.min(box.l, l), t: Math.min(box.t, t),
        r: Math.max(box.r, l + ww), b: Math.max(box.b, t + hh),
      };
    }
    return { w: box.r - box.l, h: box.b - box.t, box };
  };

  const candidates = [...document.querySelectorAll(SEL)];
  // 显式 cursor:pointer 的额外候选（自定义点击件）。
  for (const el of document.querySelectorAll('div, span, li, section, article')) {
    if (getComputedStyle(el).cursor === 'pointer') candidates.push(el);
  }

  // 去嵌套：命中盒只算**最外层**可点宿主。button 内部的 span/svg（图标位、
  // 文本标签）不是独立触控目标 —— 指针事件冒泡到宿主，量它们只会把宿主的
  // 达标读数摊成一堆小方块误报。
  // 宿主判定（触控目标的唯一口径）：
  //   ① 语义宿主 = button / a[href] / [role=button|switch|tab|menuitem] /
  //      input[type=checkbox|radio] / label[for] / [onclick] / summary；
  //   ② 自定义宿主 = 非内联的 div/span/li/section/article 且**自己显式声明**
  //      cursor:pointer、且祖先链上没有任何语义宿主（否则它就是那个宿主身上
  //      的一个图标位/文本位 —— 指针事件冒泡给宿主，量它只会把宿主读数拆成
  //      一堆小方块误报）。
  const SEMANTIC = 'button, a[href], [role="button"], [role="switch"], [role="tab"],'
    + ' [role="menuitem"], input[type="checkbox"], input[type="radio"], label[for],'
    + ' [onclick], summary';
  const hostOf = (el) => {
    let n = el;
    while (n && n !== document.body) {
      if (n.matches?.(SEMANTIC)) return n;
      n = n.parentElement;
    }
    // 无语义祖先 ⇒ 才看自定义 pointer 宿主。`cursor` 是继承属性，所以判据是
    // 「**最外层** pointer 块」：自己 pointer 而父级不是 pointer —— 否则任何
    // 可点行内部的文字块都会跟着算出 pointer，被误当成独立目标。
    let c = el;
    while (c && c !== document.body) {
      if (c.matches?.('div, span, li, section, article, i')) {
        const d = getComputedStyle(c).display;
        const own = getComputedStyle(c).cursor;
        const par = c.parentElement ? getComputedStyle(c.parentElement).cursor : 'auto';
        if (d !== 'inline' && d !== 'inline-block' && d !== 'contents'
          && own === 'pointer' && par !== 'pointer') return c;
      }
      c = c.parentElement;
    }
    return el;
  };

  for (const raw of candidates) {
    if (!raw || !raw.isConnected) continue;
    const el = hostOf(raw);
    if (el !== raw) continue; // 只量宿主本人，图标位/文本位后代跳过
    const cs = getComputedStyle(el);
    if (cs.display === 'none' || cs.visibility === 'hidden' || cs.opacity === '0') continue;
    if (cs.pointerEvents === 'none') continue;
    const r0 = el.getBoundingClientRect();
    if (r0.width < 1 || r0.height < 1) continue;
    // 只量视口内可见件（页面栈里非 live 页不可见，已被 display/visibility 过滤；
    // 这里再排除完全落在视口外的行）。
    if (r0.bottom < 0 || r0.top > vh || r0.right < 0 || r0.left > vw) continue;
    if (el.closest('[hidden]')) continue;
    if (el.disabled) continue; // 禁用件不接指针，不算触控目标
    const key = el.tagName + '|' + (el.className || '') + '|' + Math.round(r0.left) + ',' + Math.round(r0.top);
    if (seen.has(key)) continue;
    seen.add(key);
    const hb = hitBox(el);
    const label = (el.getAttribute('aria-label') || el.getAttribute('title') || el.textContent || '')
      .trim().replace(/\s+/g, ' ').slice(0, 42);
    out.push({
      tag: el.tagName.toLowerCase(),
      cls: typeof el.className === 'string' ? el.className : '',
      id: el.id || '',
      role: el.getAttribute('role') || '',
      label,
      w: Math.round(hb.w * 10) / 10,
      h: Math.round(hb.h * 10) / 10,
      ok: hb.w >= MIN - 0.5 && hb.h >= MIN - 0.5,
    });
  }
  return out;
};

/** 打开一个面（真实 UI 路径），量完返回结果。 */
const results = [];
async function measurePanel(panelId, openFn) {
  try {
    await page.evaluate(() => window.scrollTo(0, 0));
    await openFn();
    await page.waitForTimeout(500);
    const items = await page.evaluate(probeScript);
    const bad = items.filter((i) => !i.ok);
    results.push({ panel: panelId, total: items.length, bad, all: items });
    return { total: items.length, bad };
  } catch (e) {
    results.push({ panel: panelId, total: 0, bad: [], skipped: String(e).slice(0, 160) });
    return { total: 0, bad: [] };
  }
}

const dockTo = async (id) => {
  await page.evaluate(() => document.getElementById('nebflow-login-modal')?.remove());
  await page.locator(`#mobile-dock .dock-key[data-dock="${id}"]`).click();
  await page.waitForFunction((pid) => document.querySelector(`.mobile-page[data-page="${pid}"]`)?.classList.contains('live'), id, { timeout: 8000 }).catch(() => {});
  await page.waitForTimeout(250);
};
/** 工作台 hub 行 → 详情页。行不存在（如 todo 无 hub 行，只能从消息页顶行 /
 *  nf:mobile-todo-entry 事件进）时回落真实导航 API —— 与用户路径同一条
 *  pushPage 链，不是伪造 DOM。 */
const hubTo = async (face) => {
  await dockTo('workbench');
  const row = page.locator(`.mobile-page[data-page="workbench"] .mf-hub-row[data-hub-page="${face}"]`);
  if (await row.count()) {
    await row.first().click().catch(() => {});
  } else {
    await page.evaluate(async (pid) => {
      const nav = await import('/js/mobile/navController.js');
      nav.pushPage(pid);
    }, face);
  }
  await page.waitForFunction((pid) => document.querySelector(`.mobile-page[data-page="${pid}"]`)?.classList.contains('live'), face, { timeout: 8000 }).catch(() => {});
  await page.waitForTimeout(400);
};

// ── 逐面遍历 ─────────────────────────────────────────────────────────
// login 面先量（它在场时挡着导航），量完摘除。
await measurePanel('login', async () => { await page.waitForTimeout(150); });
await dismissLoginModal();
await measurePanel('chat', async () => { await page.waitForTimeout(200); });
// ⛔ 入口收敛批 todo-unify-entry-collapse 2026-11-04：'tasks' 前（聊天头左上任务
// 圆钮 → #mobile-task-panel 任务页）与 'todo' 页（#todo-page）两腿整面退役 ⇒
// 无面可量，腿退役（脚本保留，恢复入口即可复用）：
// await measurePanel('tasks', async () => {
//   await page.locator('.mh-chrome-btn[data-chrome="tasks"]').click();
//   await page.waitForTimeout(400);
// });
await measurePanel('messages', () => dockTo('messages'));
await measurePanel('contacts', () => dockTo('contacts'));
await measurePanel('canvas', () => dockTo('canvas'));
await measurePanel('workbench', () => dockTo('workbench'));
await measurePanel('me', async () => {
  await dockTo('workbench');
  await page.locator('.mobile-page[data-page="workbench"] .mf-me-entry').click();
  await page.waitForTimeout(400);
});
await measurePanel('settings', () => hubTo('settings'));
await measurePanel('reminder', () => hubTo('reminder'));
await measurePanel('daemon', () => hubTo('daemon'));
await measurePanel('background', () => hubTo('background'));
await measurePanel('plugins', async () => {
  await hubTo('plugins');
  const add = page.locator('.mobile-page.live [data-plugins-add]');
  await add.waitFor({ state: 'visible' });
  const hit = await add.evaluate((el) => {
    const r = el.getBoundingClientRect();
    const target = document.elementFromPoint(r.left + r.width / 2, r.top + r.height / 2);
    return target === el || el.contains(target);
  });
  if (!hit) throw new Error('Add plugin center is obstructed');
  await add.click();
  await page.locator('.mobile-page.live [data-plugins-add-dialog]').waitFor({ state: 'visible' });
  await page.locator('.mobile-page.live [data-plugins-add-close]').click();
});
await measurePanel('usage', () => hubTo('usage'));
// ⛔ 入口收敛批 2026-11-04：'todo' 页（#todo-page）整面退役 ⇒ 无面可量，腿退役。
// await measurePanel('todo', () => hubTo('todo'));
await measurePanel('projects', () => hubTo('projects'));
await measurePanel('graph', () => hubTo('graph'));
await measurePanel('search', async () => {
  await dockTo('chat');
  await page.locator('.mobile-chrome-btn[data-chrome="search"], .mh-chrome-btn[data-chrome="search"]').first().click().catch(() => {});
  await page.waitForTimeout(400);
});

// ── 汇总 ─────────────────────────────────────────────────────────────
const exemptHit = (item) => EXEMPT.find((e) => (item.cls || '').includes(e.match));

const report = {
  generatedAt: new Date().toISOString(),
  viewport: '375x812@2x',
  minTarget: MIN,
  webRoot: WEB,
  panels: results.map((r) => ({
    panel: r.panel,
    total: r.total,
    ok: r.total - r.bad.length,
    skipped: r.skipped || null,
    pass: !r.skipped && r.bad.every((item) => !!exemptHit(item)),
    bad: r.bad.map((b) => {
      const ex = exemptHit(b);
      return {
        tag: b.tag, id: b.id, cls: b.cls, role: b.role, label: b.label,
        w: b.w, h: b.h,
        exempt: !!ex, reason: ex ? ex.why : '未豁免：需补热区或登记豁免理由',
      };
    }),
  })),
  consoleErrors: errors,
};
report.summary = {
  panelsAttempted: report.panels.length,
  panelsMeasured: report.panels.filter((p) => !p.skipped).length,
  panelsSkipped: report.panels.filter((p) => p.skipped).length,
  panelsPassing: report.panels.filter((p) => p.pass).length,
  totalTargets: report.panels.reduce((a, p) => a + p.total, 0),
  failingTargets: report.panels.reduce((a, p) => a + p.bad.filter((b) => !b.exempt).length, 0),
  exemptTargets: report.panels.reduce((a, p) => a + p.bad.filter((b) => b.exempt).length, 0),
};

mkdirSync(dirname(OUT_JSON), { recursive: true });
writeFileSync(OUT_JSON, JSON.stringify(report, null, 2));

console.log(`\n触控热区机械断言（最小 ${MIN}×${MIN}，视口 375×812@2x）`);
console.log(`web-root: ${WEB}\n`);
console.log('面板'.padEnd(14) + '可点件'.padStart(8) + '达标'.padStart(8) + '不达标'.padStart(8) + '  结论');
console.log('─'.repeat(56));
for (const p of report.panels) {
  const nb = p.bad.filter((b) => !b.exempt).length;
  const ne = p.bad.filter((b) => b.exempt).length;
  console.log(
    p.panel.padEnd(14)
    + String(p.total).padStart(8)
    + String(p.ok).padStart(8)
    + String(nb + (ne ? `(+${ne}豁免)` : '')).padStart(10)
    + `  ${p.skipped ? `SKIP: ${p.skipped}` : nb === 0 ? 'PASS' : 'FAIL'}`,
  );
}
console.log('─'.repeat(56));
console.log(JSON.stringify(report.summary, null, 2));
console.log(`\nconsole errors: ${errors.length}`);
if (errors.length) console.log(errors.slice(0, 5).join('\n'));

const failing = report.panels.flatMap((p) => p.bad.filter((b) => !b.exempt).map((b) => ({ panel: p.panel, ...b })));
if (failing.length) {
  console.log('\n未达标项（需修）：');
  for (const f of failing) {
    console.log(`  [${f.panel}] ${f.tag}${f.id ? '#' + f.id : ''} .${f.cls} "${f.label}" → ${f.w}×${f.h}`);
  }
}
const exempted = report.panels.flatMap((p) => p.bad.filter((b) => b.exempt).map((b) => ({ panel: p.panel, ...b })));
if (exempted.length) {
  console.log('\n豁免项（已登记理由）：');
  for (const f of exempted) console.log(`  [${f.panel}] .${f.cls} → ${f.w}×${f.h}`);
}
console.log(`\nJSON: ${OUT_JSON}`);

await browser.close();
process.exit(failing.length === 0 && errors.length === 0 && report.summary.panelsSkipped === 0 ? 0 : 1);

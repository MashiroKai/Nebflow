#!/usr/bin/env node
// canvas-mobile-fix5-shots.mjs — 手机端 canvas 修复批证据抓取（作者令
// 2026-10-31 23:29/23:35 五件：①双页叠开收口 ②标签条胶囊同族 ③悬浮控件
// 让位 ④canvas→看板 ⑤输入框错误态极简）。
//
// 面板（暗/亮双主题；暗为主对照作者实拍截图）：
//   1. canvas 页整体（胶囊 chip 条 + 工具条 + 文档 pane + dock「看板」标签）
//   2. 横滑切卡动画帧 ×4（跟手位移 + 拖向侧玻璃圆环进深 = 批②「左右横滑」）
//   3. ask 悬浮钮让位角落圆钮簇（批③：fab 抬到簇上缘上方，零重叠）
//   4. 双页叠开收口终态（批①：锚邻位破坏扰动后 host 恒单 pane）
//   5. 输入框错误态（批⑤：队列失败项 + 内联错误行，红=左缘+图标两处载体）
// 每帧附 JSON 几何探针（host pane 数 / fab 座位 / chip-浮钮命中盒相交）。
//
// 用法：node tests/canvas-mobile-fix5-shots.mjs [outDir]
import { chromium } from 'playwright-core';
import { readFileSync, mkdirSync, writeFileSync, copyFileSync } from 'node:fs';
import { join, dirname, extname, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const WEB = join(HERE, '..', 'src', 'main', 'resources', 'web');
const OUT = process.argv[2] || join(HERE, '..', 'evidence', '20261031_233500_mobile-canvas-fix5__delegate-nebflow-30f2400d');
mkdirSync(OUT, { recursive: true });
mkdirSync(join(OUT, 'swipe-frames'), { recursive: true });
const MIME = { '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.svg': 'image/svg+xml', '.png': 'image/png', '.json': 'application/json', '.woff2': 'font/woff2', '.ico': 'image/x-icon' };
const SID = 'fix5-shots';
const NOW = Date.now();
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

// 作者实拍截图（双页叠开 bug 的 before 实证）归档进证据目录。
try {
  copyFileSync(
    '/Users/kaiyu/.stand/uploads/d7c3cb54-dd90-4f3f-bbf6-e7898c425cff/608896893813875_IMG_1653.jpg',
    join(OUT, 'before-author-report_IMG_1653.jpg'),
  );
  console.log('archived: before-author-report_IMG_1653.jpg');
} catch { /* 上传件不在本机时跳过（CI） */ }

const browser = await chromium.launch();
const consoleErrors = [];

async function makePage(colorScheme) {
  const ctx = await browser.newContext({
    viewport: { width: 390, height: 844 }, colorScheme, deviceScaleFactor: 2, hasTouch: true,
  });
  const page = await ctx.newPage();
  await page.addInitScript(() => {
    localStorage.setItem('nebflow_token', 'smoke-token');
    localStorage.setItem('nebflow_locale', 'zh-CN');
    Object.defineProperty(navigator.serviceWorker, 'register', { value: () => Promise.resolve() });
  });
  page.on('console', (m) => { if (m.type() === 'error') consoleErrors.push(`[${colorScheme}] ${m.text()}`); });
  page.on('pageerror', (e) => consoleErrors.push(`[${colorScheme}] pageerror: ${e.message}`));
  await page.route('**/*', (route) => {
    const url = new URL(route.request().url());
    let p = decodeURIComponent(url.pathname);
    if (p === '/') p = '/index.html';
    if (p === '/api/sessions') return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ sessions: [{ id: SID, name: '证据抓取', agentName: 'Nebula' }], activeId: SID }) });
    if (p.includes('/agents/Nebula/agent.json')) return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ displayName: '笨笨' }) });
    if (p.startsWith('/api/')) return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({}) });
    const file = normalize(join(WEB, p));
    try { return route.fulfill({ status: 200, contentType: MIME[extname(file)] || 'application/octet-stream', body: readFileSync(file) }); }
    catch { return route.fulfill({ status: 404, body: 'nf' }); }
  });
  await page.routeWebSocket(/\/ws/, (ws) => {
    const sendConfig = () => ws.send(JSON.stringify({ type: 'configData', config: '{}', configured: true, onboarding: 'done', models: [], defaults: {} }));
    const sendSessions = () => ws.send(JSON.stringify({ type: 'sessionList', sessions: [{ id: SID, name: '证据抓取', agentName: 'Nebula' }], folders: [], activeId: SID }));
    sendConfig();
    sendSessions();
    ws.onMessage((raw) => {
      let m; try { m = JSON.parse(raw); } catch { return; }
      if (m.type === 'getConfig') sendConfig();
      else if (m.type === 'getSessions' || m.type === 'listSessions') sendSessions();
      else if (m.type === 'getHistory') ws.send(JSON.stringify({ type: 'historyPage', sessionId: m.sessionId, messages: [], hasMore: false, offset: 0 }));
    });
  });
  await page.goto('http://localhost:1/');
  await page.waitForFunction(async (sid) => {
    const s = (await import('/js/state.js')).default;
    return s.activeSessionId === sid && s.ws && s.ws.readyState === 1;
  }, SID, { timeout: 20000 });
  await sleep(1000);
  return { ctx, page };
}

/** 灌入 N 张文档卡（独立预览组防互顶）+ 真实感文档内容。 */
async function seedDocs(page, docs) {
  await page.evaluate((list) => {
    import('/js/canvas.js').then((canvas) => {
      for (const [id, title, type, absPath] of list) canvas.openTab(id, title, { type, absPath: absPath || undefined });
    });
  }, docs);
  await sleep(400);
  await page.evaluate(() => {
    for (const pane of document.querySelectorAll('.canvas-tab-pane')) {
      if (pane.childElementCount) continue;
      pane.innerHTML = `
      <div style="height:100%;overflow-y:auto;padding:28px 22px 120px;font-size:15px;line-height:1.75">
        <h2 style="font-size:20px;margin:0 0 14px">后台任务生命周期管理（reconcile + 监督混合）</h2>
        <p style="color:var(--color-text-muted)">批性质　设计批（只设计不实施，呈作者拍板）。本稿给骨架定案 + 四事故逐条对策 + 参照四家对照 + 分期迁移。</p>
        <p style="border-left:2px solid var(--status-error-icon);padding-left:10px;color:var(--status-word)">红线照旧　零超时——正确性靠监测与退避表达，不设 wall-clock 判死。</p>
        <pre style="background:var(--glass-etched-bg);border:1px solid var(--glass-etched-border);border-radius:10px;padding:12px;font-size:12px;overflow-x:auto"><code>~/.stand/User.md — Kai 的形象（身份/审美/决策风格/渠道），不收事件</code></pre>
        <p>挂死检测走指数健康轨（空闲超基线 → nudge 升级），动作都不杀任务、不杀命令、不改终态语义。</p>
      </div>`;
    }
  });
  await sleep(200);
}

/** 几何探针：host pane 数 / fab 座位 / chip×浮钮相交 / dock 标签读数。 */
async function probe(page) {
  return page.evaluate(() => {
    const inter = (a, b) => !!a && !!b && !(a.right < b.left || b.right < a.left || a.bottom < b.top || b.bottom < a.top);
    const box = (el) => { if (!el) return null; const r = el.getBoundingClientRect(); return { left: +r.left.toFixed(1), top: +r.top.toFixed(1), right: +r.right.toFixed(1), bottom: +r.bottom.toFixed(1), w: +r.width.toFixed(1), h: +r.height.toFixed(1) }; };
    const chip = document.querySelector('.mf-strip-chip');
    return {
      hostPaneCount: document.querySelectorAll('.mf-cv-host > .canvas-tab-pane').length,
      chips: document.querySelectorAll('.mf-strip-chip').length,
      chipCapsule: chip ? getComputedStyle(chip).borderRadius : null,
      chipHitsToolbar: inter(box(chip), box(document.querySelector('.mf-cv-close'))) || inter(box(chip), box(document.querySelector('.mf-cv-stack'))),
      askFabBottom: document.querySelector('.ask-fab')?.style.bottom ?? null,
      clusterBox: box(document.querySelector('.mf-cv-host .canvas-source-toggle')),
      askFabBox: box(document.querySelector('.ask-fab')),
      fabOverlapsCluster: inter(box(document.querySelector('.ask-fab')), box(document.querySelector('.mf-cv-host .canvas-source-toggle'))),
      dockCanvasLabel: document.querySelector('#mobile-dock .dock-key[data-dock="canvas"] .dock-label')?.textContent ?? null,
    };
  });
}

async function shot(page, name, scheme, subdir = '') {
  const p = await probe(page);
  writeFileSync(join(OUT, subdir, `${name}-probe.json`), JSON.stringify(p, null, 2));
  await page.screenshot({ path: join(OUT, subdir, `${name}-${scheme}.png`), fullPage: false });
  console.log(`shot ${subdir}${name}-${scheme}: ${JSON.stringify(p)}`);
}

for (const scheme of ['dark', 'light']) {
  const { ctx, page } = await makePage(scheme);

  // 1. canvas 页整体：5 张卡（含超长名）→ 胶囊 chip 条 + 工具条 + 文档 + dock。
  await page.locator('#mobile-dock .dock-key[data-dock="canvas"]').click();
  await sleep(600);
  await seedDocs(page, [
    ['doc-profile', '单文件画像的现状形态', 'pg-1'],
    ['doc-bg', '20261030_background-task-design', 'pg-2'],
    ['doc-long', '7451 lugi 及其衍生命名规约汇编（超长标题截断档）', 'pg-3'],
    ['doc-memo', '会议纪要', 'pg-4'],
    ['doc-md', '说明.md', 'markdown', '/tmp/fix5/说明.md'],
  ]);
  await shot(page, '1-canvas-page', scheme);

  // 2. 横滑切卡动画帧：左滑拖程中逐段截图（跟手位移 + 圆环进深）。
  await page.locator('.mf-strip-chip[data-tab-id="doc-profile"]').click();
  await sleep(400);
  const host = await page.locator('.mf-cv-host').boundingBox();
  const midY = host.y + host.height / 2;
  const x0 = host.x + host.width - 50;
  const steps = [0, 0.3, 0.6, 0.95, 1.35];
  await page.mouse.move(x0, midY);
  await page.mouse.down();
  for (let i = 0; i < steps.length; i++) {
    await page.mouse.move(x0 - 120 * steps[i], midY);
    await sleep(90);
    await page.screenshot({ path: join(OUT, 'swipe-frames', `2-swipe-frame-${i + 1}-${scheme}.png`) });
  }
  const midProbe = await page.evaluate(() => {
    const pane = document.querySelector('.mf-cv-host > .canvas-tab-pane');
    const ring = document.querySelector('.mf-swipe-ring.right');
    return {
      paneTransform: pane?.style.transform || '',
      ringOpacity: ring?.style.opacity || getComputedStyle(ring).opacity,
    };
  });
  writeFileSync(join(OUT, 'swipe-frames', `2-swipe-mid-${scheme}.json`), JSON.stringify(midProbe, null, 2));
  await page.mouse.up();
  await sleep(400);
  console.log(`swipe mid ${scheme}: ${JSON.stringify(midProbe)}`);
  await shot(page, '2-swipe-settled', scheme);

  // 3. ask 悬浮钮让位角落圆钮簇（说明.md 卡 + 簇钮同 viewer 几何）。
  await page.locator('.mf-strip-chip[data-tab-id="doc-md"]').click();
  await sleep(300);
  await page.evaluate(() => {
    const pane = document.querySelector('.mf-cv-host > .canvas-tab-pane');
    const src = document.createElement('button');
    src.className = 'canvas-source-toggle';
    src.title = '查看源码';
    src.style.cssText = 'position:absolute;right:16px;bottom:16px;width:40px;height:40px;border-radius:50%;';
    const find = document.createElement('button');
    find.className = 'canvas-find-toggle';
    find.title = '在文件中查找';
    find.style.cssText = 'position:absolute;right:112px;bottom:16px;width:40px;height:40px;border-radius:50%;';
    pane.append(find, src);
  });
  await sleep(500);
  await shot(page, '3-ask-seat-yield', scheme);

  // 4. 双页叠开收口终态：锚邻位破坏扰动（内容区 pane 全摘）→ 换卡 → host 单 pane。
  await page.evaluate(() => {
    for (const p of [...document.getElementById('canvas-content').querySelectorAll(':scope > .canvas-tab-pane')]) p.remove();
  });
  await page.locator('.mf-strip-chip[data-tab-id="doc-bg"]').click();
  await sleep(500);
  await shot(page, '4-single-pane-after-sweep', scheme);

  // 5. 输入框错误态：聊天根输入区注入队列失败项 + 内联错误行（真实 input.css）。
  await page.locator('#mobile-dock .dock-key[data-dock="chat"]').click();
  await sleep(600);
  await page.evaluate(() => {
    const bar = document.querySelector('#input-area') || document.body;
    const wrap = document.createElement('div');
    wrap.style.cssText = 'position:fixed;left:8px;right:8px;bottom:170px;z-index:500;display:flex;flex-direction:column;gap:6px';
    wrap.innerHTML = `
      <div class="queue-item failed" data-queue-failed="1" style="display:flex;gap:8px;align-items:center;padding:8px 10px;border:1px solid var(--glass-control-border);border-radius:10px;background:var(--color-surface,#fff)">
        <span style="color:var(--color-text-muted)">⠿</span>
        <span class="queue-item-text" style="flex:1;font-size:13px">带附件的待发消息（载荷已丢失）</span>
        <span class="queue-item-failed-note"><span class="st-ic" aria-hidden="true"><svg viewBox="0 0 24 24" width="11" height="11" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="10"/><line x1="12" y1="8" x2="12" y2="12"/><line x1="12" y1="16" x2="12.01" y2="16"/></svg></span>附件丢失</span>
      </div>
      <div class="att-error" style="font-size:12px">无法附加 该文件 — 读取失败</div>`;
    bar.appendChild(wrap);
  });
  await sleep(300);
  await shot(page, '5-input-error', scheme);

  await ctx.close();
}

await browser.close();
console.log(`console errors: ${consoleErrors.length}`);
for (const e of consoleErrors) console.log('  ' + e);
console.log(`DONE → ${OUT}`);

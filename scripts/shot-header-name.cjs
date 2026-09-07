#!/usr/bin/env node
// shot-header-name.cjs — 顶栏 agent 名防截断批（2026-09-07）视觉验收 + 断言。
//
// 背景：作者 07:48 截图——窗口变窄时「Nebula」被 ellipsis 压成「N...」。
// 旧语义（#396 §8 A8）：name truncate-first / hide-last(P5)，且 need 仅按
// MIN_SESSION=28 估算（< 真实 name+gap+mem≈87px），safe∈[64,87) 时钳制把
// center 压到 name 无法容身 → ellipsis，链尽则整名隐藏。新语义（2026-09-07
// 作者裁定）：name 为固定保留项——任意窗口宽度完整显示、永不截断、不与
// 图标重叠；压力由 P1→P5 图标更早隐藏吸收。
//
// 自包含打桩（同 shot-node-detail-config.cjs 路线）：page.route 从磁盘服务
// 真实前端文件（零端口零进程，不碰 8080），MockWebSocket 注入真实 ws.js
// 分发路径，sessionList 帧走生产链路设名（sidebar.js updateHeaderSessionName）。
//
// 两种模式：
//   node scripts/shot-header-name.cjs             # after——磁盘当前树（修复后）
//   node scripts/shot-header-name.cjs --baseline  # before——chat.css/main.js
//                                                 #   以 git show db49c958:<path>
//                                                 #   内容应答（基线复现截断）
//
// 两个矩阵（每主题依次跑，截图取自 split 矩阵 canonical 宽度）：
//   split＝侧栏收起 + canvas 面板开（作者 07:48 压力形态——基线在
//   W960–600 复现 ellipsis 截断、W560 整名隐藏；after 断言 nameFull 全梯）。
//   main ＝侧栏收起 + canvas 关（窄窗聚焦对话形态，header 宽度充裕）。
//   断言规则（after 模式）：nameFull（可见 && Range 全文宽 ≤ 盒宽+1 && 无
//   ellipsis 溢出）全梯硬断言；noOverlap（name/memory 与 header-left/right
//   矩形无交集，1.5px 容差）在 mainW ≥ 190（固定簇+全名零重叠的物理地板
//   ≈34+87+34+padding/gap）时硬断言，低于则记为已知边界（此时左右固定簇
//   自身已无法入座，与本批 name 语义无关——基线同形态且更糟：还截断/
//   隐藏 name）。任一硬断言失败 → exit 1。
//   另有 sidebar-open 三宽度边界行（报告型，不截图不断言 noOverlap）。
//
// 产出：.nebflow/shots-headername/{before|after}-W<width>-{dark|light}.png
// （split 矩阵 canonical 宽度 1280/960/720/560 × 明暗）+
// {before|after}-open-W560-*.png 边界示意（gitignore 层，不进 repo）

const { chromium } = require('playwright');
const { readFileSync, mkdirSync } = require('node:fs');
const { join, dirname, extname, normalize } = require('node:path');
const { execFileSync } = require('node:child_process');

const ROOT = join(__dirname, '..');
const WEB = join(ROOT, 'src', 'main', 'resources', 'web');
const OUT_DIR = join(ROOT, '.nebflow', 'shots-headername');
const BASELINE_COMMIT = 'db49c958';
const MODE = process.argv.includes('--baseline') ? 'before' : 'after';
// 被本批修改、baseline 模式需用 git blob 应答的两个文件
const CHANGED = ['css/chat.css', 'js/main.js'];
const MIME = {
  '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css',
  '.svg': 'image/svg+xml', '.png': 'image/png', '.ico': 'image/x-icon',
  '.json': 'application/json', '.woff2': 'font/woff2', '.woff': 'font/woff', '.ttf': 'font/ttf',
};
const ROOT_SID = 'shot-root';
const LADDER = [1280, 1160, 1080, 1000, 960, 900, 840, 780, 720, 680, 640, 600, 560, 520, 480];
const OPEN_LADDER = [840, 720, 560];
const SHOT_WIDTHS = [1280, 960, 720, 560];

const baselineBlob = {};
if (MODE === 'before') {
  for (const rel of CHANGED) {
    baselineBlob['/' + rel] = execFileSync(
      'git', ['show', `${BASELINE_COMMIT}:src/main/resources/web/${rel}`],
      { cwd: ROOT, maxBuffer: 30 * 1024 * 1024 });
  }
}

async function measure(page) {
  return page.evaluate(() => {
    const session = document.getElementById('session-name');
    const header = document.getElementById('header');
    if (!session || !header) return { ok: false, why: 'no dom' };
    const cs = getComputedStyle(session);
    const visible = !!(session.offsetParent !== null && cs.display !== 'none' && !session.classList.contains('nb-header-hide'));
    const range = document.createRange();
    range.selectNodeContents(session);
    const rangeW = range.getBoundingClientRect().width;
    const clientW = session.getBoundingClientRect().width;
    const truncated = !visible || rangeW > clientW + 1 || session.scrollWidth > session.clientWidth + 1;
    const inter = (a, b) => a && b && a.left < b.right - 1.5 && b.left < a.right - 1.5
      && a.top < b.bottom - 1.5 && b.top < a.bottom - 1.5;
    const nameR = session.getBoundingClientRect();
    const memR = document.getElementById('memory-btn')?.getBoundingClientRect();
    const leftR = header.querySelector('.header-left')?.getBoundingClientRect();
    const rightR = header.querySelector('.header-right')?.getBoundingClientRect();
    const overlap = !!(inter(nameR, leftR) || inter(nameR, rightR) || inter(memR, leftR) || inter(memR, rightR));
    return {
      ok: true, visible, rangeW: Math.round(rangeW), clientW: Math.round(clientW),
      ellipsis: cs.textOverflow, truncated, overlap,
      hiddenIcons: header.querySelectorAll('.header-right .nb-header-hide').length,
      mainW: Math.round(document.getElementById('main').getBoundingClientRect().width),
    };
  });
}

(async () => {
  mkdirSync(OUT_DIR, { recursive: true });
  const browser = await chromium.launch();
  let hardFail = 0;
  for (const colorScheme of ['dark', 'light']) {
    const page = await browser.newPage({ viewport: { width: 1280, height: 800 }, colorScheme });
    await page.addInitScript(() => {
      localStorage.setItem('nebflow_token', 'shot-token');
      localStorage.setItem('nebflow_locale', 'zh-CN');
      window.__shotErrors = [];
      window.addEventListener('error', (e) => window.__shotErrors.push(String(e.message)));
      class MockWS {
        static CONNECTING = 0; static OPEN = 1; static CLOSING = 2; static CLOSED = 3;
        constructor(u) { this.url = u; this.readyState = MockWS.CONNECTING; window.__wsMock = this; }
        send() {} close() {} onopen = null; onmessage = null; onclose = null;
      }
      Object.defineProperty(window, 'WebSocket', { value: MockWS });
    });

    await page.route('**/*', (route) => {
      const url = new URL(route.request().url());
      let p = decodeURIComponent(url.pathname);
      if (p === '/') p = '/index.html';
      if (p.startsWith('/api/')) {
        return route.fulfill({ status: 404, contentType: 'application/json', body: '{"error":"not found"}' });
      }
      if (MODE === 'before' && baselineBlob[p]) {
        return route.fulfill({ status: 200, contentType: MIME[extname(p)] || 'application/octet-stream', body: baselineBlob[p] });
      }
      const file = normalize(join(WEB, p));
      if (!file.startsWith(WEB)) return route.fulfill({ status: 403, body: 'forbidden' });
      try {
        return route.fulfill({ status: 200, contentType: MIME[extname(file)] || 'application/octet-stream', body: readFileSync(file) });
      } catch {
        return route.fulfill({ status: 404, body: 'not found' });
      }
    });

    await page.goto('http://localhost:1/', { waitUntil: 'domcontentloaded' });
    await page.waitForFunction(() => window.__wsMock, null, { timeout: 15000 });
    await page.evaluate(() => { window.__wsMock.readyState = 1; window.__wsMock.onopen && window.__wsMock.onopen(); });
    await page.waitForTimeout(300);

    const df = (f) => page.evaluate((fr) => window.__wsMock.onmessage({ data: JSON.stringify(fr) }), f);
    await df({ type: 'sessionList', sessionId: ROOT_SID, activeId: ROOT_SID, sessions: [{ id: ROOT_SID, name: 'Nebula', agentName: 'Nebula' }], folders: [] });
    await df({ type: 'configData', config: '{}', configured: true, onboarding: 'done' });
    await df({ type: 'historyPage', sessionId: ROOT_SID, messages: [], hasMore: false, offset: 0 });
    await page.waitForTimeout(400);
    // 作者实例压力形态：memory-btn 在场（Nebula 有记忆，center 固定内容 +28px）
    await page.evaluate(() => { document.getElementById('memory-btn')?.removeAttribute('hidden'); });
    await page.waitForTimeout(200);

    // ── 边界行（报告型）：侧栏展开（fresh 默认态），随后收起走两矩阵 ──
    for (const W of OPEN_LADDER) {
      await page.setViewportSize({ width: W, height: 800 });
      await page.waitForTimeout(180);
      const m = await measure(page);
      const nameFull = m.ok && !m.truncated;
      console.log(`[${MODE} ${colorScheme} open  W${W}] ${JSON.stringify(m)} -> nameFull=${nameFull ? 'OK' : 'BROKEN'} (overlap=${m.overlap}; 已知边界: 侧栏展开把 #main 压至 60px 地板，固定簇自身无法入座)`);
      if (MODE === 'after' && !nameFull) hardFail++;
      if (W === 560) {
        const out = join(OUT_DIR, `${MODE}-open-W${W}-${colorScheme}.png`);
        await page.locator('#header').screenshot({ path: out });
        console.log(`  saved ${out}`);
      }
    }

    // ── 矩阵通用扫梯：nameFull 全梯硬断言；noOverlap 在 mainW≥190（物理
    //    地板：34+87+34+padding/gap 的零重叠下限）以上硬断言，以下记边界 ──
    const sweep = async (/** @type {string} */ tag, /** @type {number[]} */ widths, /** @type {boolean} */ shots) => {
      for (const W of widths) {
        await page.setViewportSize({ width: W, height: 800 });
        await page.waitForTimeout(180); // viewport resize → ResizeObserver → rAF layout()
        const m = await measure(page);
        const nameFull = m.ok && !m.truncated;
        const seatable = m.mainW >= 190;
        const hard = nameFull && (!seatable || !m.overlap);
        const note = seatable ? '' : ' [boundary: mainW<190 固定簇物理无法零重叠]';
        console.log(`[${MODE} ${colorScheme} ${tag} W${W}] ${JSON.stringify(m)} -> ${hard ? 'PASS' : 'FAIL'}${note}`);
        if (MODE === 'after' && !hard) hardFail++;
        if (shots && SHOT_WIDTHS.includes(W)) {
          const out = join(OUT_DIR, `${MODE}-W${W}-${colorScheme}.png`);
          await page.locator('#header').screenshot({ path: out });
          console.log(`  saved ${out}`);
        }
      }
    };

    // split 矩阵：侧栏收起（#sidebar-toggle，与 ⌘B 同一 API）、canvas 保持开
    await page.setViewportSize({ width: 1280, height: 800 });
    await page.click('#sidebar-toggle');
    await page.waitForTimeout(300);
    await sweep('split', LADDER, true);

    // main 矩阵：再关 canvas（canvas.js closeCanvas，生产 API）——对话形态
    await page.setViewportSize({ width: 1280, height: 800 });
    await page.evaluate(() => import('/js/canvas.js').then((m) => m.closeCanvas()));
    await page.waitForTimeout(300);
    await sweep('main ', LADDER, false);
    const errors = await page.evaluate(() => (window.__shotErrors || []));
    if (errors.length) { console.log(`[${MODE} ${colorScheme}] console errors:`, errors.slice(0, 5)); hardFail++; }
    await page.close();
  }
  await browser.close();
  if (MODE === 'after' && hardFail) { console.error(`shot-header-name: ${hardFail} assertion failure(s)`); process.exit(1); }
  console.log(`shot-header-name [${MODE}] done`);
})().catch((e) => { console.error(e); process.exit(1); });

// diff-filehead-capsule-shots.mjs — Canvas 粘顶文件头标题胶囊批（2026-10-22）
// 作者三令验收证据：①水平居中 ②全量 frosted token 材质 ③动画节奏放慢。
//
// 取证面（亮暗双版）：
//   ① 静止 closeup —— 胶囊居中 + frosted 材质（glass 底/描边/投影/折射线）
//      + 零重叠（vs 卡头胶囊 .canvas-header / deck fab #deck-fab，56px 让位带）
//   ② 吸顶 closeup —— 滚动 600px 钉住，diff 文字从胶囊底下穿过（blur 可见）
//      + 钉住偏移恒等读数（静止 = 钉住，防「sticky 约束矩形被容器 padding
//      撑偏」回归，前会话实测 112px=56+56 漂移量级）
//   ③ 出现动画中间帧 —— 面板行切换 → ~140ms 采样（半透明中间帧截图 +
//      opacity 读数）+ 终态归位
//   ④ 全景一张 —— 左栏面板 + canvas 阅读面上下文
//
// 用法：node tests/diff-filehead-capsule-shots.mjs
// 产物 = evidence/20261022_diff-filehead-capsule__delegate-nebflow-68166d0b/shots/*.png
//        + 本进程 stdout 断言清单。
// 夹具形制同 tests/diff-summary-split-shots.mjs（真 web 树 + 真 WS 桩）。
import { chromium } from 'playwright';
import { readFileSync, mkdirSync } from 'node:fs';
import { join, dirname, extname, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const WEB = join(HERE, '..', 'src', 'main', 'resources', 'web');
const OUT = join(HERE, '..', 'evidence', '20261022_diff-filehead-capsule__delegate-nebflow-68166d0b', 'shots');
mkdirSync(OUT, { recursive: true });
const MIME = {
  '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css',
  '.svg': 'image/svg+xml', '.png': 'image/png', '.ico': 'image/x-icon',
  '.json': 'application/json', '.woff2': 'font/woff2', '.woff': 'font/woff',
};
const SID = 'diff-headpill-root';

let failures = 0;
const ok = (name, cond, extra = '') => {
  console.log(`${cond ? 'PASS' : 'FAIL'}  ${name}${extra ? '  — ' + extra : ''}`);
  if (!cond) failures++;
};
const r2 = (n) => Math.round(n * 100) / 100;

// 批次 A：3 文件。f0 = 140 行可读 diff（吸顶滚动样本）；f1 = 8 行（切换样本，
// 出现动画重放）；f2 = 降级（健全态）。路径长短各一，居中读数取 f0。
const bodyMain = [
  '@@ -12,7 +12,140 @@ export function boot()',
  " import { initPanels } from './panels.js';",
  ...Array.from({ length: 138 }, (_, i) => `+// headpill line ${i + 1}: sticky capsule stays centered while diff scrolls beneath`),
].join('\n');
const PAYLOAD_A = {
  nodeId: 'deleg-headpill-a',
  generatedAt: 1791999200000,
  root: '/Users/kaiyu/Claude code/Stand',
  files: [
    { path: 'src/main/resources/web/css/diffView.css', changeType: 'modified', added: 138, removed: 6, source: 'git', diffText: bodyMain },
    { path: 'src/main/resources/web/js/diffCanvas.js', changeType: 'modified', added: 5, removed: 2, source: 'git', diffText: '@@ -88,6 +88,8 @@\n-  head.innerHTML = `<span class="nf-fc-path">…</span>`;\n+  head.innerHTML = `<div class="fcv-headpill">` +\n+    `<span class="nf-fc-path">…</span>` +\n+    `</div>`;' },
    { path: 'assets/generated/brand-mark.svg', changeType: 'added', added: 0, removed: 0, source: 'git', degradedReason: 'binary-file' },
  ],
};

const browser = await chromium.launch();

async function boot(width, height, scheme) {
  const page = await (await browser.newContext({
    viewport: { width, height }, colorScheme: scheme, deviceScaleFactor: 2,
  })).newPage();
  await page.addInitScript(() => {
    localStorage.setItem('nebflow_token', 'e2e-token');
    localStorage.setItem('nebflow_locale', 'zh-CN');
    localStorage.setItem('stand_input_history', '[]');
  });
  await page.route('**/*', (route) => {
    let p = decodeURIComponent(new URL(route.request().url()).pathname);
    if (p === '/') p = '/index.html';
    if (p.startsWith('/api/')) return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
    const file = normalize(join(WEB, p));
    if (!file.startsWith(WEB)) return route.fulfill({ status: 403, body: 'forbidden' });
    try {
      return route.fulfill({
        status: 200,
        contentType: MIME[extname(file)] || 'application/octet-stream',
        body: readFileSync(file),
      });
    } catch {
      return route.fulfill({ status: 404, body: 'not found' });
    }
  });
  await page.routeWebSocket(/\/ws/, (ws) => {
    const sendConfig = () => ws.send(JSON.stringify({ type: 'configData', config: '{}', configured: true, onboarding: 'done' }));
    const sendSessions = () => ws.send(JSON.stringify({ type: 'sessionList', sessions: [{ id: SID, name: 'root', agentName: 'Nebula' }], folders: [], activeId: SID }));
    sendConfig();
    sendSessions();
    ws.onMessage((raw) => {
      let m; try { m = JSON.parse(raw); } catch { return; }
      if (m.type === 'getConfig') sendConfig();
      else if (m.type === 'getSessions' || m.type === 'listSessions') sendSessions();
      else if (m.type === 'getHistory') ws.send(JSON.stringify({ type: 'historyPage', sessionId: m.sessionId, messages: [], hasMore: false, offset: 0 }));
      else if (m.type === 'ping') ws.send(JSON.stringify({ type: 'pong' }));
    });
  });
  await page.goto('http://localhost:1/');
  await page.waitForSelector('#input', { state: 'visible', timeout: 20000 });
  await page.waitForFunction(async (rootSid) => {
    const s = (await import('/js/state.js')).default;
    return s.activeSessionId === rootSid && s.ws && s.ws.readyState === 1;
  }, SID, { timeout: 15000 });
  await page.evaluate(() => {
    const chat = document.getElementById('chat');
    const mk = (cls, text) => {
      const row = document.createElement('div');
      row.className = `row ${cls}`;
      const b = document.createElement('div');
      b.className = 'bubble ' + (cls === 'user' ? 'user' : 'ai');
      b.textContent = text;
      row.appendChild(b);
      return row;
    };
    chat.appendChild(mk('user', 'Canvas 粘顶文件头标题胶囊：居中 + 毛玻璃 + 动画自然。'));
    chat.appendChild(mk('ai', '收到：定心层居中 + 全量 frosted token + 0.36s 出现/吸顶渐深。'));
  });
  await page.waitForTimeout(150);
  return page;
}

async function injectBatch(page, payload, header) {
  await page.evaluate(({ payload, header }) => import('/js/state.js').then((mod) => {
    const s = mod.default;
    s.ws.onmessage({
      data: JSON.stringify({
        type: 'user', injected: true, sessionId: s.activeSessionId,
        source: 'delegate', eventType: 'completed', header,
        text: '文件头标题胶囊批完成：居中 + frosted + 动画节奏。证据与 spec 见交付报告。',
        timestamp: Date.now(),
        fileChanges: payload,
      }),
    });
  }), { payload, header });
}

const readMat = (page) => page.evaluate(() => {
  const el = document.querySelector('.canvas-tab-pane[data-type="filechanges"] .fcv-headpill');
  if (!el) return null;
  const cs = getComputedStyle(el);
  const view = el.closest('.fcv-view');
  const vr = view.getBoundingClientRect();
  const pr = el.getBoundingClientRect();
  return {
    radius: cs.borderRadius,
    border: cs.borderTopWidth + ' ' + cs.borderTopStyle,
    borderColor: cs.borderTopColor,
    bg: cs.backgroundColor,
    backdrop: cs.backdropFilter || cs.webkitBackdropFilter || '',
    shadow: cs.boxShadow,
    gapL: pr.left - vr.left,
    gapR: vr.left + view.clientWidth - pr.right,
    offsetTop: pr.top - vr.top,
    pillTopInView: pr.top - vr.top,
    opacity: parseFloat(cs.opacity),
    animName: cs.animationName,
    animDur: cs.animationDuration,
    transDur: cs.transitionDuration,
  };
});

const raf = (page) => page.evaluate(() => new Promise((r) => requestAnimationFrame(() => requestAnimationFrame(r))));

async function closeupHeader(page, path) {
  const vb = await page.locator('.canvas-tab-pane[data-type="filechanges"] .fcv-view').boundingBox();
  await page.screenshot({ path, clip: { x: Math.max(vb.x - 10, 0), y: Math.max(vb.y - 10, 0), width: vb.width + 20, height: 170 } });
}

// ── 亮暗双版主链路 ──
for (const scheme of ['dark', 'light']) {
  const page = await boot(1440, 860, scheme);
  await injectBatch(page, PAYLOAD_A, 'DELEGATE · nebflow · headpill · COMPLETED');
  await raf(page);
  await page.locator('.bubble.injected .nf-diff-chip').click();
  await page.waitForSelector('#panel-filechanges.active', { timeout: 8000 });
  await page.waitForSelector('.canvas-tab-pane[data-type="filechanges"] .fcv-headpill', { timeout: 8000 });
  await page.waitForTimeout(600); // 出现动画走完再采材质/几何

  // ① 材质（作者令②）：全量 frosted token
  const mat = await readMat(page);
  ok(`[${scheme}] ① 材质 = glass token（bg 亮暗档 + blur saturate + 描边 + 投影 + 999 圆角）`,
    !!mat && mat.radius === '999px' && mat.border === '1px solid'
    && mat.borderColor !== 'rgba(0, 0, 0, 0)'
    && (scheme === 'dark' ? mat.bg === 'rgba(24, 28, 38, 0.68)' : mat.bg === 'rgba(255, 255, 255, 0.55)')
    && mat.backdrop.includes('blur(24px)') && mat.backdrop.includes('saturate(1.15)')
    && mat.shadow !== 'none',
    `bg=${mat && mat.bg} backdrop=${mat && mat.backdrop}`);
  ok(`[${scheme}] ① 动画慢档（0.36s 出现 + 0.4s 过渡 + 吸顶 scroll 时间线档）`,
    !!mat && mat.animName.includes('fcv-head-in') && mat.animName.includes('fcv-head-stuck')
    && mat.animDur.split(',').map((s) => parseFloat(s)).includes(0.36)
    && mat.transDur.split(',').map((s) => parseFloat(s)).every((s) => s >= 0.3),
    `anim=${mat && mat.animName} dur=${mat && mat.animDur} trans=${mat && mat.transDur}`);

  // ① 居中几何（作者令①）：左缝 ≈ 右缝 ≤1px（clientWidth 不含滚动条）
  const centered = mat && Math.abs(mat.gapL - mat.gapR) <= 1;
  ok(`[${scheme}] ① 水平居中（|左缝−右缝| ≤1px）`, centered,
    mat ? `gapL=${r2(mat.gapL)} gapR=${r2(mat.gapR)}` : 'no pill');
  ok(`[${scheme}] ① 静止偏移在让位带内（50-80 = 56 带 + 6 定心层内缩）`,
    !!mat && mat.offsetTop > 50 && mat.offsetTop < 80, `offset=${mat && r2(mat.offsetTop)}`);
  // 零重叠（作者追加令）：文件头胶囊 vs 卡头胶囊（浮层 top10/left14）与
  // deck fab（fixed top10/right12+40 高）双轴不相交——56px 带布局层保证。
  const zl = await page.evaluate(() => {
    const p = document.querySelector('.canvas-tab-pane[data-type="filechanges"] .fcv-headpill');
    const cap = document.querySelector('#canvas-panel .canvas-header');
    const fab = document.getElementById('deck-fab');
    const hit = (a, b) => !(a.left >= b.right || b.left >= a.right
      || a.top >= b.bottom || b.top >= a.bottom);
    const pr = p.getBoundingClientRect();
    const visFab = fab && getComputedStyle(fab).display !== 'none'
      && fab.getBoundingClientRect().width > 0;
    return {
      cap: cap ? hit(pr, cap.getBoundingClientRect()) : false,
      fab: visFab ? hit(pr, fab.getBoundingClientRect()) : false,
    };
  });
  ok(`[${scheme}] ① 零重叠：文件头胶囊 vs 卡头胶囊/deck fab 双轴不相交`,
    !zl.cap && !zl.fab, `cap=${zl.cap} fab=${zl.fab}`);
  await closeupHeader(page, join(OUT, `1-rest-closeup-${scheme}.png`));

  // ② 吸顶：滚 600px → 钉住偏移恒等 + 材质仍在（文字穿底 blur）
  await page.locator('.canvas-tab-pane[data-type="filechanges"] .fcv-view').evaluate((el) => { el.scrollTop = 600; });
  await page.waitForTimeout(120);
  const stuck = await readMat(page);
  ok(`[${scheme}] ② 钉住偏移恒等（静止 = 钉住 ±1.5px）`,
    !!stuck && Math.abs(stuck.offsetTop - mat.offsetTop) <= 1.5,
    `rest=${mat && r2(mat.offsetTop)} stuck=${stuck && r2(stuck.offsetTop)}`);
  const firstLineBehind = await page.evaluate(() => {
    const v = document.querySelector('.canvas-tab-pane[data-type="filechanges"] .fcv-view');
    return v.scrollTop > 0;
  });
  ok(`[${scheme}] ② 滚程生效（diff 文字已从胶囊底下穿过）`, firstLineBehind);
  await closeupHeader(page, join(OUT, `2-stuck-closeup-${scheme}.png`));
  await page.locator('.canvas-tab-pane[data-type="filechanges"] .fcv-view').evaluate((el) => { el.scrollTop = 0; });
  await page.waitForTimeout(120);

  // ③ 出现动画中间帧：面板行 2 切换 → 先采样读数（快），再截 closeup
  //    （截图管线自身 ~150-250ms 延迟 ⇒ 落在动画后半段，帧面可见半透明）。
  await page.locator('#panel-filechanges .nfp-list .nf-fc').nth(1).click();
  await page.waitForTimeout(60);
  const mid = await readMat(page);
  ok(`[${scheme}] ③ 动画中间帧（采样 opacity < 0.92 = 非生硬瞬变）`,
    !!mid && mid.opacity < 0.92, `opacity@sample=${mid && r2(mid.opacity)}`);
  await closeupHeader(page, join(OUT, `3-anim-midframe-${scheme}.png`));
  await page.waitForTimeout(500);
  const settled = await readMat(page);
  ok(`[${scheme}] ③ 终态归位（opacity=1，focus 换 diffCanvas.js）`,
    !!settled && settled.opacity === 1
    && await page.locator('.canvas-tab-pane[data-type="filechanges"] .fcv-filehead').innerText().then((t) => /diffCanvas\.js/.test(t)),
    `opacity=${settled && settled.opacity}`);

  // ④ 全景（面板 + canvas 上下文）
  await page.screenshot({ path: join(OUT, `4-panel-canvas-${scheme}.png`) });
  await page.close();
}

// ── 手机宽度健全态（≤768 mobile-layer：阅读面 + 文件条共存，胶囊同式）──
{
  const page = await boot(390, 780, 'dark');
  await injectBatch(page, PAYLOAD_A, 'DELEGATE · nebflow · headpill · COMPLETED');
  await raf(page);
  await page.evaluate(() => import('/js/mobile/navController.js').then((m) => m.touchLayer()));
  await page.locator('.bubble.injected .nf-diff-chip').click();
  await page.waitForSelector('body.mf-cards-live', { timeout: 8000 });
  await page.waitForSelector('.canvas-tab-pane[data-type="filechanges"] .fcv-headpill', { timeout: 8000 });
  await page.waitForTimeout(600);
  const m = await readMat(page);
  ok(`[mobile] ⑧ 手机层胶囊居中 + 材质成立`, !!m && Math.abs(m.gapL - m.gapR) <= 1.5
    && m.bg === 'rgba(24, 28, 38, 0.68)' && m.backdrop.includes('saturate(1.15)'),
    `gapL=${m && r2(m.gapL)} gapR=${m && r2(m.gapR)}`);
  const stripRows = await page.locator('.canvas-tab-pane[data-type="filechanges"] .fcv-filestrip .nf-fc').count();
  ok(`[mobile] ⑧ 文件条在场（3 行，切换面未受扰）`, stripRows === 3, `rows=${stripRows}`);
  await page.screenshot({ path: join(OUT, '5-mobile-cards.png') });
  await page.close();
}

await browser.close();
console.log(failures === 0 ? 'ALL PASS' : `${failures} FAILURES`);
process.exit(failures === 0 ? 0 : 1);

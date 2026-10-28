// diff-chip-headline-shots.mjs — diff chip 同线微调批（2026-10-22 回访令）验收证据。
//
// 作者令：①「N FILES +X −Y」计数与 COMPLETED 同行（接在其后；窄面板允许截断
// 策略但常规宽必须一行）② chip 里 NEBFLOW 改 STAND。
//
// 实现：injectedRows.buildInjectedRow 把 header label + chip 装进同一 flex 行
// （.nf-diff-headline；label flex:1 + nowrap + ellipsis，chip flex:0 0 auto）；
// diffView.css 同步。夹具项目段改 stand（NEBFLOW→STAND 品牌归一，夹具即演示
// 数据；真实运行时该段 = 实际项目名，引擎 NotificationHeader 单一来源不动）。
//
// 断言（stdout）+ 截图（shots/）：
//   ① 同行：label 与 chip 垂直中心差 < 3px；行高 = chip 高（零换行）；chip 接在
//     label 之后（chip.left ≥ label.right）
//   ② 右对齐保持：chip 右缘贴气泡内容右缘（rightGap < 1px）
//   ③ 窄面板截断策略：气泡压到 320px ⇒ 仍一行 + label 省略号生效（scrollWidth >
//     clientWidth）+ chip 本征宽零变形
//   截图：1-chip-<scheme>.png（全景）/ 2-chip-<scheme>-closeup.png（特写，一行双件）
//        / 3-narrow-<scheme>.png（窄面板特写）
//
// 用法：node tests/diff-chip-headline-shots.mjs
// 夹具形制同 tests/diff-chip-align-shots.mjs（真 web 树 + 真 WS 桩，零伪造 state）。
import { chromium } from 'playwright';
import { readFileSync, mkdirSync } from 'node:fs';
import { join, dirname, extname, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const WEB = join(HERE, '..', 'src', 'main', 'resources', 'web');
const OUT = join(HERE, '..', 'evidence', '20261022_diff-chip-headline__delegate-nebflow-b6cabd75', 'shots');
mkdirSync(OUT, { recursive: true });
const MIME = {
  '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css',
  '.svg': 'image/svg+xml', '.png': 'image/png', '.ico': 'image/x-icon',
  '.json': 'application/json', '.woff2': 'font/woff2', '.woff': 'font/woff',
};
const SID = 'chip-headline-root';

let failures = 0;
const ok = (name, cond, extra = '') => {
  console.log(`${cond ? 'PASS' : 'FAIL'}  ${name}${extra ? '  — ' + extra : ''}`);
  if (!cond) failures++;
};

// 载荷 = 右对齐批同款批次 A（5 文件 +351 −140，特写可与其证据图直接对比）。
const bodyA = [
  '@@ -12,7 +12,9 @@ export function boot()',
  " import { initPanels } from './panels.js';",
  "+import { initDiffPanel } from './diffPanel.js';",
  " import { initCanvas } from './canvas.js';",
  ' ',
  '-// legacy mount order',
  '+// mount order v2: activity bar first, diff panel registered at boot',
  ' initActivityBar();',
  '+initDiffPanel();',
  ' initCanvas();',
  ' initColResizers();',
  ' ',
  '@@ -44,6 +46,14 @@ function bindShortcuts()',
  "   case 'b':",
  '     toggleSideBar();',
  '     break;',
  "+  case 'd':",
  '+    openDiffBatch(latestPayload);',
  '+    break;',
  '   default:',
  '     break;',
  ' }',
].join('\n');
const PAYLOAD_A = {
  nodeId: 'deleg-shot-batch-a',
  generatedAt: 1791999100000,
  root: '/Users/kaiyu/Claude code/Stand',
  files: [
    { path: 'src/main/resources/web/js/main.js', changeType: 'modified', added: 11, removed: 4, source: 'tool-ledger', diffText: bodyA },
    { path: 'src/main/resources/web/js/diffPanel.js', changeType: 'added', added: 231, removed: 0, source: 'tool-ledger', diffText: '@@ -0,0 +1,3 @@\n+import { t } from \'./i18n.js\';\n+\n+export function initDiffPanel() {}' },
    { path: 'src/main/resources/web/js/chat/injectedRows.js', changeType: 'modified', added: 30, removed: 40, source: 'git', diffText: '@@ -1,4 +1,4 @@\n-import { renderFileChangesHtml } from \'../diffRender.js\';\n-import { openFileChangesCanvas } from \'../diffCanvas.js\';\n+import { toggleDiffBatch } from \'../diffPanel.js\';\n+import { createIconsIn } from \'../utils.js\';' },
    { path: 'src/main/resources/web/css/diffView.css', changeType: 'modified', added: 79, removed: 96, source: 'git', diffText: '@@ -148,9 +148,7 @@\n-.fcv-nav {\n-  flex: 0 0 280px;\n-}\n+.fcv-view {\n+  flex: 1;\n+}' },
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
      return route.fulfill({ status: 200, contentType: MIME[extname(file)] || 'application/octet-stream', body: readFileSync(file) });
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
    chat.appendChild(mk('user', 'diff 胶囊跟 COMPLETED 放同一行吧，窄了截断没关系，宽必须一行。'));
    chat.appendChild(mk('ai', '收到：header 同行右缘挂摘要 chip，窄面板 label 省略号截断。'));
  });
  await page.waitForTimeout(150);
  return page;
}

const raf = (page) => page.evaluate(() => new Promise((r) => requestAnimationFrame(() => requestAnimationFrame(r))));

const readGeo = (page) => page.locator('.bubble.injected .nf-diff-chip').evaluate((el) => {
  const row = el.closest('.nf-diff-headline');
  const label = row.querySelector('.injected-source-label');
  const lr = label.getBoundingClientRect();
  const cr = el.getBoundingClientRect();
  const rr = row.getBoundingClientRect();
  const bubble = el.closest('.bubble');
  const bcs = getComputedStyle(bubble);
  return {
    sameLineCenterDelta: Math.abs((lr.top + lr.height / 2) - (cr.top + cr.height / 2)),
    // 单行判据（紧凑化批 2026-10-28 重钉）：旧代理「行高=chip 高」在 chip 不再
    // 撑行后失效（行高由 label 决定）——改钉通用形态「行高 = max(label,chip) 高」，
    // 语义不变（仍单行、零换行）。
    rowOneLineDelta: rr.height - Math.max(lr.height, cr.height),
    after: cr.left - lr.right,
    rightGap: bubble.getBoundingClientRect().right - parseFloat(bcs.paddingRight) - cr.right,
    labelScroll: label.scrollWidth,
    labelClient: label.clientWidth,
    chipWidth: cr.width,
    headerText: label.textContent,
  };
});

for (const scheme of ['dark', 'light']) {
  const page = await boot(1440, 860, scheme);
  await page.evaluate((payload) => import('/js/state.js').then((mod) => {
    const s = mod.default;
    s.ws.onmessage({
      data: JSON.stringify({
        type: 'user', injected: true, sessionId: s.activeSessionId,
        source: 'delegate', eventType: 'completed', header: 'DELEGATE · stand · diff-split · COMPLETED',
        text: 'diff 拆面板批完成：摘要上移 + 左右分体（同线微调批同帧可见）。',
        timestamp: Date.now(),
        fileChanges: payload,
      }),
    });
  }), PAYLOAD_A);
  await raf(page);

  // ① 常规宽：同线 + 一行 + 接在其后。
  const w = await readGeo(page);
  ok(`[${scheme}] ① header 与 chip 同行（垂直中心差<3px）`, w.sameLineCenterDelta < 3, `delta=${w.sameLineCenterDelta.toFixed(2)}px`);
  ok(`[${scheme}] ① 常规宽一行（行高=max(label,chip) 高，零换行）`, Math.abs(w.rowOneLineDelta) < 1, `row−max=${w.rowOneLineDelta.toFixed(2)}px`);
  ok(`[${scheme}] ① chip 接在 COMPLETED 段之后（chip.left≥label.right）`, w.after >= 0, `gap=${w.after.toFixed(2)}px`);
  ok(`[${scheme}] ① 品牌段 STAND（NEBFLOW→STAND）`, w.headerText.includes('stand'), `header="${w.headerText}"`);
  // ② 右对齐批语义保持：chip 右缘贴气泡内容右缘。
  ok(`[${scheme}] ② chip 右缘贴气泡内容右缘（rightGap<1px）`, w.rightGap < 1 && w.rightGap > -1, `rightGap=${w.rightGap.toFixed(2)}px`);

  await page.locator('#chat').screenshot({ path: join(OUT, `1-chip-${scheme}.png`) });
  await page.locator('.bubble.injected').screenshot({ path: join(OUT, `2-chip-${scheme}-closeup.png`) });

  // ③ 窄面板（气泡压到 320px 模拟）：仍一行 + label 省略号截断 + chip 本征宽零变形。
  await page.locator('.bubble.injected').evaluate((el) => { el.style.maxWidth = '320px'; });
  await raf(page);
  const n = await readGeo(page);
  ok(`[${scheme}] ③ 窄面板仍一行（行高=max(label,chip) 高）`, Math.abs(n.rowOneLineDelta) < 1, `row−max=${n.rowOneLineDelta.toFixed(2)}px`);
  ok(`[${scheme}] ③ 窄面板 label 省略号截断生效`, n.labelScroll > n.labelClient, `scroll=${n.labelScroll.toFixed(0)}px client=${n.labelClient.toFixed(0)}px`);
  ok(`[${scheme}] ③ chip 本征宽零变形`, Math.abs(n.chipWidth - w.chipWidth) < 1, `wide=${w.chipWidth.toFixed(1)}px narrow=${n.chipWidth.toFixed(1)}px`);
  await page.locator('.bubble.injected').screenshot({ path: join(OUT, `3-narrow-${scheme}.png`) });
  await page.close();
}

await browser.close();
console.log(failures === 0 ? 'ALL PASS' : `${failures} FAILURES`);
process.exit(failures === 0 ? 0 : 1);

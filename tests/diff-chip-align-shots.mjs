// diff-chip-align-shots.mjs — diff 摘要 chip 右对齐微调批（2026-10-22 回访令）验收证据。
//
// 作者令：「把diff胶囊靠右对齐」——交付气泡顶部的 diff 摘要 chip（.nf-diff-chip）
// 从左对齐改为靠右对齐（与交付气泡右缘对齐）。只动对齐：chip 形态/交互/其余不变。
//
// 实现 = diffView.css .nf-diff-chip：气泡（.bubble = block 容器）内块级 flex +
// width:fit-content + margin-left:auto ⇒ 右缘贴气泡内容右缘；pill 本体零变形。
//
// 断言（stdout）+ 截图（shots/）：
//   ① chip 右缘贴气泡内容右缘（rightGap < 1px）；左缘留白 > 8px（真右对齐非撑满）
//   ② chip 仍为收窄 pill（width < 气泡内容宽 50%，形态未变）
//   ③ 亮暗双版截图：1-chip-<scheme>.png（对话流全景）+ 2-chip-<scheme>-closeup.png（气泡特写）
//
// 用法：node tests/diff-chip-align-shots.mjs
// 夹具形制同 tests/diff-summary-split-shots.mjs（真 web 树 + 真 WS 桩，零伪造 state）。
import { chromium } from 'playwright';
import { readFileSync, mkdirSync } from 'node:fs';
import { join, dirname, extname, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const WEB = join(HERE, '..', 'src', 'main', 'resources', 'web');
const OUT = join(HERE, '..', 'evidence', '20261022_diff-chip-right-align__delegate-nebflow-7961c23f', 'shots');
mkdirSync(OUT, { recursive: true });
const MIME = {
  '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css',
  '.svg': 'image/svg+xml', '.png': 'image/png', '.ico': 'image/x-icon',
  '.json': 'application/json', '.woff2': 'font/woff2', '.woff': 'font/woff',
};
const SID = 'chip-align-root';

let failures = 0;
const ok = (name, cond, extra = '') => {
  console.log(`${cond ? 'PASS' : 'FAIL'}  ${name}${extra ? '  — ' + extra : ''}`);
  if (!cond) failures++;
};

// 载荷 = 拆面板批同款批次 A（5 文件，与 1-chip-light.png 现状参照图同构可对比）。
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
    chat.appendChild(mk('user', 'diff 胶囊再靠右对齐一下，跟气泡右缘齐。'));
    chat.appendChild(mk('ai', '收到：摘要 chip 右缘贴交付气泡右缘，形态与交互不动。'));
  });
  await page.waitForTimeout(150);
  return page;
}

const raf = (page) => page.evaluate(() => new Promise((r) => requestAnimationFrame(() => requestAnimationFrame(r))));

for (const scheme of ['dark', 'light']) {
  const page = await boot(1440, 860, scheme);
  await page.evaluate((payload) => import('/js/state.js').then((mod) => {
    const s = mod.default;
    s.ws.onmessage({
      data: JSON.stringify({
        type: 'user', injected: true, sessionId: s.activeSessionId,
        source: 'delegate', eventType: 'completed', header: 'DELEGATE · nebflow · diff-split · COMPLETED',
        text: 'diff 拆面板批完成：摘要上移 + 左右分体（chip 右对齐回访微调同帧可见）。',
        timestamp: Date.now(),
        fileChanges: payload,
      }),
    });
  }), PAYLOAD_A);
  await raf(page);

  // ① 几何断言：chip 右缘贴气泡内容右缘；左缘留白；pill 收窄形态未变。
  const m = await page.locator('.bubble.injected .nf-diff-chip').evaluate((el) => {
    const bubble = el.closest('.bubble');
    const bs = getComputedStyle(bubble);
    const bb = bubble.getBoundingClientRect();
    const cb = el.getBoundingClientRect();
    return {
      display: getComputedStyle(el).display,
      rightGap: bb.right - parseFloat(bs.paddingRight) - cb.right,
      leftGap: cb.left - (bb.left + parseFloat(bs.paddingLeft)),
      contentWidth: bb.width - parseFloat(bs.paddingLeft) - parseFloat(bs.paddingRight),
      chipWidth: cb.width,
    };
  });
  ok(`[${scheme}] ① chip 右缘贴气泡内容右缘（rightGap<1px）`, m.rightGap < 1 && m.rightGap > -1, `rightGap=${m.rightGap.toFixed(2)}px`);
  ok(`[${scheme}] ① 非撑满真右对齐（leftGap>8px）`, m.leftGap > 8, `leftGap=${m.leftGap.toFixed(2)}px`);
  // 收窄证明：fit-content 生效（pill 本征宽）且 auto margin 恰好吃掉全部剩余空
  // 间（leftGap ≈ contentWidth − chipWidth）——非拉伸、非百分比宽，形态未变。
  ok(`[${scheme}] ② pill 收窄形态未变（display=flex，auto margin 吸满剩余）`, m.display === 'flex'
    && Math.abs(m.contentWidth - m.chipWidth - m.leftGap) < 1,
  `chip=${m.chipWidth.toFixed(1)}px content=${m.contentWidth.toFixed(1)}px left=${m.leftGap.toFixed(1)}px`);

  await page.locator('#chat').screenshot({ path: join(OUT, `1-chip-${scheme}.png`) });
  await page.locator('.bubble.injected').screenshot({ path: join(OUT, `2-chip-${scheme}-closeup.png`) });
  await page.close();
}

await browser.close();
console.log(failures === 0 ? 'ALL PASS' : `${failures} FAILURES`);
process.exit(failures === 0 ? 0 : 1);

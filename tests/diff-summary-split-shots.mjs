// diff-summary-split-shots.mjs — diff 拆面板批（2026-10-22）验收证据。
//
// 作者令：「改为在最上面看，而且是一个小的摘要，点击了之后，在左侧面板出现，
// diff的文件列表，在canvas显示选中文件的diff。等于是把目前的这个面板拆为了
// 左右两个面板」——原对话流底部一体 master-detail 大面板退役。
//
// 三态 × 亮暗 = 本证据主体：
//   ① 摘要条形态（折叠态交付气泡顶部 chip）
//   ② 点击后左栏文件列表（#panel-filechanges 面板族形态）
//   ③ canvas 选中文件 diff（阅读面；旧 .fcv-nav 不在 DOM）
// 附：多委托切换（面板换批）+ 手机宽度（≤768 抽屉层）健全态。
//
// 用法：node tests/diff-summary-split-shots.mjs
// 产物 = evidence/20261022_diff-summary-split__delegate-nebflow-7961c23f/shots/*.png
//        + 本进程 stdout 断言清单。
// 夹具形制同 tests/attach-strip-shots.mjs（真 web 树 + 真 WS 桩，零伪造 state）。
import { chromium } from 'playwright';
import { readFileSync, mkdirSync } from 'node:fs';
import { join, dirname, extname, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const WEB = join(HERE, '..', 'src', 'main', 'resources', 'web');
const OUT = join(HERE, '..', 'evidence', '20261022_diff-summary-split__delegate-nebflow-7961c23f', 'shots');
mkdirSync(OUT, { recursive: true });
const MIME = {
  '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css',
  '.svg': 'image/svg+xml', '.png': 'image/png', '.ico': 'image/x-icon',
  '.json': 'application/json', '.woff2': 'font/woff2', '.woff': 'font/woff',
};
const SID = 'diff-split-root';

let failures = 0;
const ok = (name, cond, extra = '') => {
  console.log(`${cond ? 'PASS' : 'FAIL'}  ${name}${extra ? '  — ' + extra : ''}`);
  if (!cond) failures++;
};

// 批次 A：5 文件（modified ×3 / added ×2，git + tool-ledger 混源；主文件 46 行
// 可读 diff——截图可辨 +a/−a 着色与 hunk 头）。
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
  "+  case 'Escape':",
  '+    collapseOverlays();',
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
// 批次 B：2 文件（多委托切换样本）。
const PAYLOAD_B = {
  nodeId: 'deleg-shot-batch-b',
  generatedAt: 1791999100001,
  root: '/Users/kaiyu/Claude code/Stand',
  files: [
    { path: 'docs/spec/diff-split.md', changeType: 'added', added: 64, removed: 0, source: 'tool-ledger', diffText: '@@ -0,0 +1,4 @@\n+# diff 拆面板批\n+\n+## 目标形态\n+1. 摘要条：交付消息顶部' },
    { path: 'tests/chat-diff-canvas.spec.mjs', changeType: 'modified', added: 171, removed: 76, source: 'git', diffText: '@@ -1,5 +1,6 @@\n-// chat-diff-canvas.spec.mjs — 聊天卡 diff 展开改走 Canvas\n+// chat-diff-canvas.spec.mjs — 委托 diff「摘要上移 + 左右分体」验收\n+// （2026-10-22 作者令）' },
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
  // 铺两条历史消息让对话流有上下文，再注入 delegate 终态帧。
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
    chat.appendChild(mk('user', '帮我把委托交付的 diff 查看改成摘要上移、左右分体的形态。'));
    chat.appendChild(mk('ai', '收到：交付消息顶部放摘要条，点击后左栏出文件列表、canvas 出选中文件的 diff。'));
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
        text: 'diff 拆面板批完成：摘要上移 + 左右分体。证据与 spec 见交付报告。',
        timestamp: Date.now(),
        fileChanges: payload,
      }),
    });
  }), { payload, header });
}

const raf = (page) => page.evaluate(() => new Promise((r) => requestAnimationFrame(() => requestAnimationFrame(r))));

// ── 亮暗双版主链路：三态证据 ──
for (const scheme of ['dark', 'light']) {
  const page = await boot(1440, 860, scheme);

  // ① 摘要条形态（折叠态交付气泡）
  await injectBatch(page, PAYLOAD_A, 'DELEGATE · nebflow · diff-split · COMPLETED');
  await raf(page);
  const chipVisible = await page.locator('.bubble.injected .nf-diff-chip').isVisible();
  const chipText = (await page.locator('.bubble.injected .nf-diff-chip').innerText()).replace(/\s+/g, ' ').trim();
  ok(`[${scheme}] ① 摘要条折叠态可见且含文件数/增删`, chipVisible && /5 files/.test(chipText) && /\+351/.test(chipText) && /−140/.test(chipText), chipText);
  const hintGone = await page.locator('.nf-row-hint').count();
  ok(`[${scheme}] ① 旧标签内 hint 退役（零 .nf-row-hint）`, hintGone === 0);
  await page.locator('#chat').screenshot({ path: join(OUT, `1-chip-${scheme}.png`) });

  // ② 点击 → 左栏文件列表 + ③ canvas 阅读面（同帧双面板）
  await page.locator('.bubble.injected .nf-diff-chip').click();
  await page.waitForSelector('#panel-filechanges.active', { timeout: 8000 });
  await page.waitForSelector('.canvas-tab-pane[data-type="filechanges"] .fcv-filehead', { timeout: 8000 });
  await page.waitForTimeout(400); // 分块渲染排帧
  await raf(page);
  const rows = await page.locator('#panel-filechanges .nfp-list .nf-fc').count();
  ok(`[${scheme}] ② 左栏面板激活且 5 行`, rows === 5, `rows=${rows}`);
  const metaTxt = (await page.locator('#panel-filechanges .nfp-meta').innerText()).replace(/\s+/g, ' ');
  ok(`[${scheme}] ② 面板 meta 合计`, /5 files/.test(metaTxt) && /\+351/.test(metaTxt), metaTxt);
  const navGone = await page.locator('.canvas-tab-pane .fcv-nav').count();
  const fcvGone = await page.locator('.canvas-tab-pane .fcv').count();
  ok(`[${scheme}] ④ 旧 master-detail 不在 DOM（.fcv/.fcv-nav = 0）`, navGone === 0 && fcvGone === 0);
  const headTxt = await page.locator('.canvas-tab-pane[data-type="filechanges"] .fcv-filehead').innerText();
  ok(`[${scheme}] ③ canvas 焦点 = 首文件`, /main\.js/.test(headTxt), headTxt.replace(/\s+/g, ' '));
  const diffRows = await page.locator('.canvas-tab-pane[data-type="filechanges"] .nf-diff-row').count();
  ok(`[${scheme}] ③ diff 行在场（≥20）`, diffRows >= 20, `rows=${diffRows}`);
  await page.screenshot({ path: join(OUT, `2-panel-canvas-${scheme}.png`) });
  await page.locator('#canvas-panel').screenshot({ path: join(OUT, `3-canvas-${scheme}.png`) });

  // 降级文件 → 原因行
  await page.locator('#panel-filechanges .nfp-list .nf-fc').nth(4).click();
  await page.waitForTimeout(120);
  const viewTxt = await page.locator('.canvas-tab-pane[data-type="filechanges"] .fcv-view').innerText();
  ok(`[${scheme}] ③ 降级原因行（binary-file）`, /binary-file/.test(viewTxt));

  // 多委托切换（批次 B）
  await injectBatch(page, PAYLOAD_B, 'DELEGATE · nebflow · batch-b · COMPLETED');
  await page.locator('.bubble.injected .nf-diff-chip').nth(1).click();
  await page.waitForTimeout(300);
  const rowsB = await page.locator('#panel-filechanges .nfp-list .nf-fc').count();
  const headB = await page.locator('.canvas-tab-pane[data-type="filechanges"] .fcv-filehead').innerText();
  const paneCount = await page.locator('.canvas-tab-pane[data-type="filechanges"]').count();
  ok(`[${scheme}] ⑤ 批次切换：面板 2 行 + canvas 换焦点 + 面板恒 1`, rowsB === 2 && /diff-split\.md/.test(headB) && paneCount === 1, `rows=${rowsB} head=${headB.split('\n')[0]}`);
  const chipStates = await page.locator('.bubble.injected .nf-diff-chip').evaluateAll((els) => els.map((e) => e.getAttribute('aria-pressed')));
  ok(`[${scheme}] ⑤ chip 激活态随批走（false/true）`, chipStates.join(',') === 'false,true', chipStates.join(','));
  await page.screenshot({ path: join(OUT, `4-switch-batch-${scheme}.png`) });

  // 再点收起（现有面板 toggle 语义）
  await page.locator('.bubble.injected .nf-diff-chip').nth(1).click();
  await page.waitForTimeout(250);
  const collapsed = await page.evaluate(() => document.body.classList.contains('sidebar-collapsed'));
  ok(`[${scheme}] ⑥ 已打开批次再点 chip → 侧栏收起`, collapsed);
  await page.screenshot({ path: join(OUT, `5-recollapse-${scheme}.png`) });
  await page.close();
}

// ── 手机宽度（≤768 mobile-layer：#sidebar 整层退场 ⇒ 文件切换由阅读面内置
//    文件条承载；画板 = mf-cards-live 文档卡片层）──
{
  const page = await boot(390, 780, 'dark');
  await injectBatch(page, PAYLOAD_A, 'DELEGATE · nebflow · diff-split · COMPLETED');
  await raf(page);
  const chipVisible = await page.locator('.bubble.injected .nf-diff-chip').isVisible();
  ok(`[mobile] ⑦ chip 折叠态可见`, chipVisible);
  // 移动层「启动自动展开抑制」（layoutController.watchBootAutoOpen，r1 既定）：
  // 首触前 canvas-open 一律收起——既有形态（旧 master-detail 同此），非本批引入。
  // 证据脚本先标首触（等效真实用户先摸过移动层），再走 chip 打开链。
  await page.evaluate(() => import('/js/mobile/navController.js').then((m) => m.touchLayer()));
  await page.locator('.bubble.injected .nf-diff-chip').click();
  await page.waitForSelector('body.mf-cards-live', { timeout: 8000 });
  await page.waitForSelector('.canvas-tab-pane[data-type="filechanges"] .fcv-filestrip .nf-fc', { timeout: 8000 });
  await page.waitForTimeout(450); // 卡片层过渡
  const rowsM = await page.locator('.canvas-tab-pane[data-type="filechanges"] .fcv-filestrip .nf-fc').count();
  ok(`[mobile] ⑦ 卡片层文件条 5 行`, rowsM === 5, `rows=${rowsM}`);
  await page.locator('.canvas-tab-pane[data-type="filechanges"] .fcv-filestrip .nf-fc').nth(1).click();
  await page.waitForTimeout(250);
  const headM = await page.locator('.canvas-tab-pane[data-type="filechanges"] .fcv-filehead').innerText();
  ok(`[mobile] ⑦ 文件条切换 → 焦点换 diffPanel.js`, /diffPanel\.js/.test(headM), headM.split('\n')[0]);
  await page.screenshot({ path: join(OUT, '6-mobile-cards.png') });
  await page.close();
}

await browser.close();
console.log(failures === 0 ? 'ALL PASS' : `${failures} FAILURES`);
process.exit(failures === 0 ? 0 : 1);

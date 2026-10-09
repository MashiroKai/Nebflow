#!/usr/bin/env node
// before-shot.cjs — 当前 UI 实渲 before 截图（AskUser 重设计批 2026-10-06 加分件）。
//
// 工艺同族：scripts/shot-header-name.cjs（page.route 磁盘直服真前端 + MockWebSocket
// 注入真实 ws.js 分发路径）。零端口、零进程、绝不连接宿主 :8080；对实现树只读。
//
// 场景 = 未命名态冷启动第一屏：
//   ① sessionList/configData(onboarding:'pending') 真帧走生产链路 → v2 问卷自动开跑；
//   ② reducedMotion:'reduce' → 伪流式瞬时落定；
//   ③ 等 `.ask-card[data-q="name"]`（第一幕·第 1 题卡）；
//   ④ 注入一帧合成 askUser（requestId=before-shot-1）→ AskUser 通用卡 + 待办条；
//      agentName='Nebula' ⇒ 来源徽标按现状隐藏（renderAskUser 的 Nebula 判定）；
//   ⑤ 全页截图 → before/{light,dark}.png（1280×800 @2x）。
//
// 断言：两卡选择器在场 + 控制台零错误，否则 exit 1。

const { chromium } = require('playwright');
const { readFileSync, mkdirSync, statSync } = require('node:fs');
const { join, dirname, extname, normalize } = require('node:path');

const ROOT = join(__dirname, '..', '..', '..');
const WEB = join(ROOT, 'src', 'main', 'resources', 'web');
const OUT_DIR = join(__dirname, '..', 'before');
const MIME = {
  '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css',
  '.svg': 'image/svg+xml', '.png': 'image/png', '.ico': 'image/x-icon',
  '.json': 'application/json', '.woff2': 'font/woff2', '.woff': 'font/woff', '.ttf': 'font/ttf',
};
const ROOT_SID = 'shot-root';
const SIDEBAR_COLLAPSE = !process.argv.includes('--keep-sidebar');

(async () => {
  mkdirSync(OUT_DIR, { recursive: true });
  const browser = await chromium.launch();
  let hardFail = 0;
  for (const scheme of ['light', 'dark']) {
    const page = await browser.newPage({
      viewport: { width: 1280, height: 800 },
      deviceScaleFactor: 2,
      colorScheme: scheme,
      // 🔴 不用 reducedMotion:'reduce'：现实现下 reduce 路径有 TDZ 缺陷
      //    (streamBubble 的 onDone 同步触发 → resolve(row) 先于 const row 赋值
      //    → 流程死在第一个气泡)。截图走正常动画 + Esc 快进（产品自带的
      //    跳过打字通道，module 级 keydown → activeSim.finish()）。
    });
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
    // historyPage 先行：带 sessionId 的帧走 findViewBySessionId → setActiveView，
    // 之后 configData（GLOBAL 帧保活当前 view）触发的问卷流才能渲进 chat。
    await df({ type: 'historyPage', sessionId: ROOT_SID, messages: [], hasMore: false, offset: 0 });
    // onboarding:'pending' → initOnboarding 自动开跑 v2 问卷（生产入口 main.js configData 腿）
    await df({ type: 'configData', config: '{}', configured: true, onboarding: 'pending' });

    if (SIDEBAR_COLLAPSE) {
      await page.waitForTimeout(300);
      await page.click('#sidebar-toggle'); // 收起侧栏，对话区满幅（作者截图形态）
    }
    // 关 Canvas 面板（冷启动默认开着「插件」tab，404 噪声入镜）——生产 API，
    // 同 scripts/shot-header-name.cjs 的 main 矩阵做法。
    await page.evaluate(() => import('/js/canvas.js').then((m) => m.closeCanvas()).catch(() => {}));
    await page.waitForTimeout(300);
    // 打字机快进：Esc = 产品自带「跳过打字」（activeSim.finish()）。250ms 一拍
    // 连打直到第一题卡出现（卡片在题文流完后 +240ms beat 才挂载）。
    const escHammer = setInterval(() => { page.keyboard.press('Escape').catch(() => {}); }, 250);
    await page.waitForSelector('.ask-card[data-q="name"]', { timeout: 30000 });
    clearInterval(escHammer);

    // 合成 AskUser 帧 → 通用卡（问句/选项为演示内容；徽标按 Nebula 判定隐藏）
    await df({
      type: 'askUser', sessionId: ROOT_SID, requestId: 'before-shot-1', agentName: 'Nebula',
      items: [{
        question: '这是一张 AskUser 通用卡（before 对照用）。',
        options: [{ label: '选项一' }, { label: '选项二' }, { label: '选项三' }],
        multiple: false,
      }],
    });
    await page.waitForSelector('.option-box[data-request-id="before-shot-1"]', { timeout: 10000 });
    await page.waitForTimeout(600); // 动画/待办条落定

    const headerName = await page.evaluate(() => document.getElementById('session-name')?.textContent || '');
    const badge = await page.evaluate(() => {
      const rows = [...document.querySelectorAll('.row.ai .ask-user-source')];
      return rows.map((n) => n.textContent);
    });
    const out = join(OUT_DIR, `${scheme}.png`);
    await page.screenshot({ path: out, fullPage: false });

    const errors = await page.evaluate(() => (window.__shotErrors || []));
    if (errors.length) { console.log(`[${scheme}] console errors:`, errors.slice(0, 5)); hardFail++; }
    const bytes = statSync(out).size;
    console.log(`[${scheme}] ok -> ${out} (${bytes}B) | header #session-name = "${headerName}" | bubble badges = ${JSON.stringify(badge)}`);
    await page.close();
  }
  await browser.close();
  if (hardFail) { console.error(`before-shot: ${hardFail} failure(s)`); process.exit(1); }
  console.log('before-shot done');
})().catch((e) => { console.error(e); process.exit(1); });

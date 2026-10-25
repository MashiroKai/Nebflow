// chat-skeleton.spec.mjs — 初载骨架卡验收（audit ②27「加载慢且无 loading 卡片/
// placeholder」残余；2026-10-25 手机端 P1 批第②件）。
//
// 被测面 = 真渲染链（真实 web 树 + 真模块图；WS 层 = routeWebSocket 桩，getHistory
// 可按会话延时）：
//   ① boot 首进会话：sessionList → resetChatForActiveSession 清空聊天区 ⇒ 骨架卡
//     （.chat-skeleton，4 条渐变占位泡：AI 左/用户右）在场；historyPage 到场同帧
//     摘除（hideChatSkeleton 与渲染同任务帧 ⇒ 让位零跳变）+ 消息行顶上。
//   ② 空历史会话（0 条消息）：骨架卡同样在 historyPage 到场即摘（不滞留假加载）。
//   ③ 缓冲流内容恢复腿：sessionTexts 有 thinking/text 缓冲的会话直渲真内容，
//     不出骨架（占位不与真内容同帧闪现）。
//   ④ 手机层（375 视口）：同一挂载/摘除链在 mobile-layer 下等效。
//   ⑤ 源面静态钉：sidebar 挂载点 / main.js historyPage 摘除点 / historyIndicators
//     组件族（show/hide + clearHistoryIndicators 收口）。
//
// Run: node tests/chat-skeleton.spec.mjs
import { chromium } from 'playwright-core';
import { createServer } from 'node:http';
import { readFile } from 'node:fs/promises';
import { join, dirname, extname, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';

const WEB = join(dirname(fileURLToPath(import.meta.url)), '..', 'src', 'main', 'resources', 'web');
let failures = 0;
function ok(name, cond, extra = '') {
  console.log(`${cond ? 'PASS' : 'FAIL'}  ${name}${extra ? '  — ' + extra : ''}`);
  if (!cond) failures++;
}
const sleep = ms => new Promise(r => setTimeout(r, ms));
const MIME = { '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.svg': 'image/svg+xml', '.png': 'image/png', '.json': 'application/json', '.woff2': 'font/woff2' };

// ── 桩：getHistory 按会话延时（骨架窗可观测），内容可配 ──────────────
const SID_A = 'sk-session-a';
const SID_B = 'sk-session-b';
const SID_EMPTY = 'sk-session-empty';
const SID_BUF = 'sk-session-buffered';
const historyDelayMs = { [SID_A]: 900, [SID_B]: 700, [SID_EMPTY]: 700, [SID_BUF]: 700 };
const historyMsgs = {
  [SID_A]: [
    { type: 'user', text: '骨架前的第一条用户消息', timestamp: 1750000000001 },
    { type: 'ai', text: '骨架前的第一条助手回复', durationMs: 1200, model: 'mock-llm', timestamp: 1750000000002 },
  ],
  [SID_B]: [{ type: 'user', text: 'B 会话历史行', timestamp: 1750000000010 }],
  [SID_EMPTY]: [],
  [SID_BUF]: [{ type: 'user', text: '缓冲会话历史行', timestamp: 1750000000020 }],
};

function wireUp(ws) {
  ws.onMessage(raw => {
    let m; try { m = JSON.parse(raw); } catch { return; }
    if (m.type === 'ping') { ws.send(JSON.stringify({ type: 'pong' })); return; }
    if (m.type === 'getHistory') {
      const delay = historyDelayMs[m.sessionId] ?? 400;
      setTimeout(() => {
        try {
          ws.send(JSON.stringify({
            type: 'historyPage', sessionId: m.sessionId,
            messages: historyMsgs[m.sessionId] || [], hasMore: false, offset: 0, total: (historyMsgs[m.sessionId] || []).length,
          }));
        } catch { /* 连接已被关闭 */ }
      }, delay);
      return;
    }
  });
  ws.send(JSON.stringify({ type: 'configData', config: '{"features":{}}', configured: true, onboarding: 'done', models: [], defaults: {} }));
  ws.send(JSON.stringify({
    type: 'sessionList',
    sessions: [
      { id: SID_A, name: '骨架A' },
      { id: SID_B, name: '骨架B' },
      { id: SID_EMPTY, name: '空会话' },
      { id: SID_BUF, name: '缓冲会话' },
    ],
    activeId: SID_A, folders: [],
  }));
}

// ── 静态服务器 + 浏览器 ──────────────────────────────────────────────
const server = createServer(async (req, res) => {
  try {
    const path = decodeURIComponent(new URL(req.url, 'http://x').pathname);
    const file = normalize(join(WEB, path === '/' ? 'index.html' : path));
    const body = await readFile(file);
    res.writeHead(200, { 'Content-Type': MIME[extname(file)] || 'application/octet-stream' });
    res.end(body);
  } catch {
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end('{}');
  }
});
await new Promise(r => server.listen(0, '127.0.0.1', r));
const BASE = `http://127.0.0.1:${server.address().port}`;

const browser = await chromium.launch();
async function boot(viewport) {
  const ctx = await browser.newContext({ viewport });
  await ctx.addInitScript(() => {
    localStorage.setItem('nebflow_token', 't');
    localStorage.setItem('nebflow_locale', 'zh-CN');
    localStorage.setItem('stand_input_history', '[]');
  });
  const page = await ctx.newPage();
  const pageErrors = [];
  page.on('pageerror', e => pageErrors.push(e.message));
  await page.route('**/api/**', r => r.fulfill({ json: {} }));
  await page.routeWebSocket(/\/ws/, ws => wireUp(ws));
  await page.goto(BASE + '/index.html');
  await page.waitForSelector('#input', { state: 'visible', timeout: 20000 });
  return { ctx, page, pageErrors };
}
const skeletonCount = (page) => page.evaluate(() => document.querySelectorAll('.chat-skeleton').length);
const skeletonRows = (page) => page.evaluate(() => {
  const sk = document.querySelector('.chat-skeleton');
  if (!sk) return -1;
  return { rows: sk.querySelectorAll('.chat-sk-row').length, me: sk.querySelectorAll('.chat-sk-row-me').length };
});
const rowWithText = (page, txt) => page.evaluate((t) =>
  [...document.querySelectorAll('.row')].filter(r => (r.textContent || '').includes(t)).length, txt);
/** 会话切换入口：单会话架构下会话列表 DOM 已移除（sidebar.js「Session list DOM
 *  removed」注），切换走 switchToSession 模块入口（= nebflow-session-change +
 *  resetChatForActiveSession 同链，与 flow canvas 节点弹层同路）。 */
const switchSession = (page, sid) => page.evaluate(async (s) => {
  const m = await import('/js/sidebar.js');
  m.switchToSession(s);
}, sid);

try {
  // ══ ①-③ 桌面腿：boot 首进 / 空历史 / 缓冲恢复 ══════════════════════
  {
    const { ctx, page, pageErrors } = await boot({ width: 1440, height: 900 });
    await page.waitForSelector('#input', { state: 'visible', timeout: 20000 });
    await sleep(300); // sessionList 消化（resetChatForActiveSession 已跑，historyPage 在途）
    ok('1a boot 首进会话：骨架卡在场（历史未加载完）', (await skeletonCount(page)) === 1,
      `count=${await skeletonCount(page)}`);
    const shape = await skeletonRows(page);
    ok('1b 骨架形：4 条占位泡、AI 左/用户右混排（官网气泡语言）',
      shape.rows === 4 && shape.me === 1, JSON.stringify(shape));

    await sleep(1100); // SID_A 延时 900ms 到场
    ok('1c historyPage 到场：骨架卡让位（同帧摘除不滞留）', (await skeletonCount(page)) === 0,
      `count=${await skeletonCount(page)}`);
    ok('1d 真历史行顶上（占位→内容零空窗）', (await rowWithText(page, '骨架前的第一条助手回复')) === 1,
      `rows=${await rowWithText(page, '骨架前的第一条助手回复')}`);

    // ② 空历史：切到空会话 ⇒ 骨架卡同样即摘（不滞留假加载态）
    await switchSession(page, SID_EMPTY);
    await sleep(250);
    ok('2a 切空会话：骨架卡在场（请求在途）', (await skeletonCount(page)) === 1);
    await sleep(900);
    ok('2b 空历史 historyPage 到场：骨架卡即摘（无 0 消息假加载滞留）',
      (await skeletonCount(page)) === 0, `count=${await skeletonCount(page)}`);

    // ③ 缓冲流内容恢复腿：sessionTexts 有缓冲 ⇒ 直渲真内容，不出骨架
    await page.evaluate((sid) => {
      import('/js/state.js').then((s) => {
        s.default.sessionTexts[sid] = '缓冲中的助手文本';
        s.default.sessionThinkingBuffers[sid] = '';
      });
    }, SID_BUF);
    await switchSession(page, SID_BUF);
    await sleep(350);
    ok('3a 缓冲内容会话：不出骨架卡（真内容已恢复在场）',
      (await skeletonCount(page)) === 0 && (await rowWithText(page, '缓冲中的助手文本')) >= 1,
      `sk=${await skeletonCount(page)} rows=${await rowWithText(page, '缓冲中的助手文本')}`);
    await sleep(900); // historyPage 到场，收尾干净
    ok('3d 桌面腿零 pageerror', pageErrors.length === 0, JSON.stringify(pageErrors));
    await ctx.close();
  }

  // ══ ④ 手机层腿（375 视口，mobile-layer 激活）════════════════════════
  {
    const { ctx, page, pageErrors } = await boot({ width: 375, height: 812 });
    await page.waitForSelector('#input', { state: 'visible', timeout: 20000 });
    await sleep(300);
    const mobile = await page.evaluate(() => document.body.classList.contains('mobile-layer'));
    ok('4a 手机层：骨架卡在场（mobile-layer 判据成立）',
      mobile && (await skeletonCount(page)) === 1, `mobile=${mobile} sk=${await skeletonCount(page)}`);
    await sleep(1100);
    ok('4b 手机层：historyPage 到场骨架卡让位', (await skeletonCount(page)) === 0);
    // 切会话同链（switchToSession 模块入口）
    await switchSession(page, SID_B);
    await sleep(250);
    ok('4c 手机层：切会话骨架卡复现', (await skeletonCount(page)) === 1);
    await sleep(900);
    ok('4d 手机层：B 会话骨架卡让位 + 零 pageerror',
      (await skeletonCount(page)) === 0 && pageErrors.length === 0, JSON.stringify(pageErrors));
    await ctx.close();
  }

  // ══ ⑤ 源面静态钉 ═══════════════════════════════════════════════════
  const indSrc = await readFile(join(WEB, 'js', 'main', 'historyIndicators.js'), 'utf8');
  const sidebarSrc = await readFile(join(WEB, 'js', 'sidebar.js'), 'utf8');
  const mainSrc = await readFile(join(WEB, 'js', 'main.js'), 'utf8');
  const cssSrc = await readFile(join(WEB, 'css', 'chat.css'), 'utf8');
  ok('5a 组件钉：show/hideChatSkeleton 导出 + clearHistoryIndicators 收口',
    /export function showChatSkeleton/.test(indSrc) && /export function hideChatSkeleton/.test(indSrc)
      && /hideChatSkeleton\(\)/.test(indSrc));
  ok('5b 挂载钉：resetChatForActiveSession 无缓冲内容腿出骨架',
    /showChatSkeleton\(\)/.test(sidebarSrc) && /else if \(sid\)/.test(sidebarSrc));
  ok('5c 摘除钉：historyPage 处理器同帧摘骨架',
    /hideHistoryLoader\(\);\s*\n\s*hideChatSkeleton\(\);/.test(mainSrc));
  ok('5d 形钉：CSS 骨架组件在挂（占位泡 + shimmer 动画 + reduced-motion 降级）',
    /\.chat-skeleton/.test(cssSrc) && /\.chat-sk-bubble/.test(cssSrc)
      && /chat-sk-shimmer/.test(cssSrc) && /prefers-reduced-motion/.test(cssSrc));

  console.log(failures === 0 ? '\nALL PASS' : `\n${failures} FAILURE(S)`);
} catch (e) {
  failures++;
  console.error('SPEC ERROR:', e);
} finally {
  await browser.close().catch(() => {});
  server.close();
}
process.exit(failures === 0 ? 0 : 1);

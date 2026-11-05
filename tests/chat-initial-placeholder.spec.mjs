// chat-initial-placeholder.spec.mjs — 主会话「进会话占位」验收（作者令 2026-11-05
// 12:28 逐字：「我们目前的逻辑是通过一个卡片来显示加载过程 进来就应该是加载好的
// 这个占位逻辑多余了」/ 追问确认：「是主会话，一开始进入的时候，会有占位内容。就是
// 绿色的」）。
//
// 旧形态（已退役）= .chat-skeleton：消息流顶部 4 条大圆角渐变占位泡，i=1 为
// .chat-sk-row-me ⇒ 用户位**绿泡**（实拍 IMG_1744 即此）。
// 本 spec = 退役后的契约（真渲染链：真 web 树 + 真模块图，WS 用 routeWebSocket
// 桩，getHistory 按会话延时 ⇒ 加载窗可观测）：
//   ① 进会话且线程空 ⇒ **零占位块**（.chat-skeleton / .chat-sk-bubble 均不存在）；
//      只有一条细加载行 .history-loader（spinner + 「加载中…」；与翻页加载同一个件，
//      非卡片/非气泡形态）⇒ 「加载中」这一档仍在。
//   ② historyPage 到场 ⇒ 加载行摘除 + 真历史行在位（加载中 → 内容 无缝）。
//   ③ 重渲腿（旧形态的真 bug）：已有消息的会话重渲后**不得**再挂占位；旧实现
//      「占位与真内容同框」且没有新 historyPage 时占位永不退场。
//   ④ 空历史会话（0 条）⇒ 加载中仍是加载行；到场后零行（空 ≠ 加载中）。
//   ⑤ 手机层（375 视口）同链等效（mobile-layer 判据成立）。
//   ⑥ 源面静态钉：形态零残留（JS 无导出 / sidebar 挂载点 = 细加载行 + 空线程判据 /
//      main.js 只摘 loader / CSS 无骨架块）。
//
// Run: node tests/chat-initial-placeholder.spec.mjs
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

// ── 桩：getHistory 按会话延时（加载窗可观测），内容可配 ──────────────
const SID_A = 'sk-session-a';
const SID_B = 'sk-session-b';
const SID_EMPTY = 'sk-session-empty';
const historyDelayMs = { [SID_A]: 900, [SID_B]: 700, [SID_EMPTY]: 700 };
const historyMsgs = {
  [SID_A]: [
    { type: 'user', text: '骨架前的第一条用户消息', timestamp: 1750000000001 },
    { type: 'ai', text: '骨架前的第一条助手回复', durationMs: 1200, model: 'mock-llm', timestamp: 1750000000002 },
  ],
  [SID_B]: [{ type: 'user', text: 'B 会话历史行', timestamp: 1750000000010 }],
  [SID_EMPTY]: [],
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
            messages: historyMsgs[m.sessionId] || [], hasMore: false, offset: 0,
            total: (historyMsgs[m.sessionId] || []).length,
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
      { id: SID_A, name: '会话A' },
      { id: SID_B, name: '会话B' },
      { id: SID_EMPTY, name: '空会话' },
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

/** 占位形态探针：骨架泡（旧形态）与线程内「大块圆角占位」的机械判据。 */
const placeholderProbe = (page) => page.evaluate(() => {
  const chat = document.querySelector('.chat');
  const sk = document.querySelectorAll('.chat-skeleton, .chat-sk-row, .chat-sk-bubble').length;
  // 形态判据（不靠类名）：聊天流里「无文本、无交互、高度 ≥ 32 的块状节点」= 占位块。
  const blocks = chat ? [...chat.querySelectorAll('div')].filter((el) => {
    const txt = (el.textContent || '').trim();
    if (txt.length) return false;
    if (el.querySelector('img, a, button, input, textarea, svg, video, canvas')) return false;
    const r = el.getBoundingClientRect();
    return r.height >= 32 && r.width >= 80;
  }).map((el) => ({ cls: el.className, h: Math.round(el.getBoundingClientRect().height) })) : [];
  return { skeletonNodes: sk, blockPlaceholders: blocks, loaders: document.querySelectorAll('.history-loader').length };
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
  // ══ ①-④ 桌面腿 ═════════════════════════════════════════════════════
  {
    const { ctx, page, pageErrors } = await boot({ width: 1440, height: 900 });
    await page.waitForSelector('#input', { state: 'visible', timeout: 20000 });
    await sleep(300); // sessionList 消化（resetChatForActiveSession 已跑，historyPage 在途）
    const p1 = await placeholderProbe(page);
    ok('1a 进会话（历史在途、线程空）：零骨架占位泡（旧 .chat-skeleton 形态退役）',
      p1.skeletonNodes === 0, `skeletonNodes=${p1.skeletonNodes}`);
    ok('1b 进会话：零「无文本大块」占位块（机械形态判据，不靠类名）',
      p1.blockPlaceholders.length === 0, JSON.stringify(p1.blockPlaceholders));
    ok('1c 加载中这一档仍在 = 一条细加载行 .history-loader（非卡片/非气泡）',
      p1.loaders === 1, `loaders=${p1.loaders}`);

    await sleep(1100); // SID_A 延时 900ms 到场
    const p2 = await placeholderProbe(page);
    ok('2a historyPage 到场：加载行摘除', p2.loaders === 0, `loaders=${p2.loaders}`);
    ok('2b historyPage 到场：真历史行在位', (await rowWithText(page, '骨架前的第一条助手回复')) === 1);
    ok('2c 内容态零占位（加载中 → 内容无占位残留）', p2.skeletonNodes === 0 && p2.blockPlaceholders.length === 0,
      JSON.stringify(p2));

    // ③ 重渲腿 = 旧形态的真 bug：已有消息的会话重渲后不得挂占位
    await page.evaluate(() => {
      // 同一链路的第二次重渲（会话列表更新/复位都会走 resetChatForActiveSession）。
      return import('/js/sidebar.js').then((m) => m.resetChatForActiveSession && m.resetChatForActiveSession());
    });
    await sleep(150);
    const p3 = await placeholderProbe(page);
    ok('3a 已有消息的会话重渲后：仍零骨架占位泡（旧实现在此同框）',
      p3.skeletonNodes === 0, `skeletonNodes=${p3.skeletonNodes}`);
    ok('3b 重渲后：零「无文本大块」占位块', p3.blockPlaceholders.length === 0, JSON.stringify(p3.blockPlaceholders));

    // ④ 空历史会话：加载中仍是加载行；到场后零行（空 ≠ 加载中）
    await switchSession(page, SID_EMPTY);
    await sleep(250);
    const p4 = await placeholderProbe(page);
    ok('4a 切空会话（历史在途）：加载行在位、零占位泡',
      p4.loaders === 1 && p4.skeletonNodes === 0, JSON.stringify(p4));
    await sleep(900);
    const p5 = await placeholderProbe(page);
    ok('4b 空历史到场：加载行摘除、零占位（真空 ≠ 加载中）',
      p5.loaders === 0 && p5.skeletonNodes === 0 && p5.blockPlaceholders.length === 0, JSON.stringify(p5));
    ok('4c 桌面腿零 pageerror', pageErrors.length === 0, JSON.stringify(pageErrors));
    await ctx.close();
  }

  // ══ ⑤ 手机层腿（375 视口，mobile-layer 激活）════════════════════════
  {
    const { ctx, page, pageErrors } = await boot({ width: 375, height: 812 });
    await page.waitForSelector('#input', { state: 'visible', timeout: 20000 });
    await sleep(300);
    const mobile = await page.evaluate(() => document.body.classList.contains('mobile-layer'));
    const p1 = await placeholderProbe(page);
    ok('5a 手机层：mobile-layer 判据成立 + 进会话零占位泡',
      mobile && p1.skeletonNodes === 0, `mobile=${mobile} sk=${p1.skeletonNodes}`);
    ok('5b 手机层：加载中 = 一条细加载行', p1.loaders === 1, `loaders=${p1.loaders}`);
    await sleep(1100);
    const p2 = await placeholderProbe(page);
    ok('5c 手机层：historyPage 到场加载行让位 + 零占位残留',
      p2.loaders === 0 && p2.skeletonNodes === 0, JSON.stringify(p2));
    await switchSession(page, SID_B);
    await sleep(250);
    const p3 = await placeholderProbe(page);
    ok('5d 手机层：切会话不复活占位形态', p3.skeletonNodes === 0 && p3.blockPlaceholders.length === 0, JSON.stringify(p3));
    await sleep(900);
    ok('5e 手机层：B 会话加载行让位 + 零 pageerror',
      (await placeholderProbe(page)).loaders === 0 && pageErrors.length === 0, JSON.stringify(pageErrors));
    await ctx.close();
  }

  // ══ ⑥ 源面静态钉（形态零残留 + 三档判据的落点）════════════════════════
  const indSrc = await readFile(join(WEB, 'js', 'main', 'historyIndicators.js'), 'utf8');
  const sidebarSrc = await readFile(join(WEB, 'js', 'sidebar.js'), 'utf8');
  const mainSrc = await readFile(join(WEB, 'js', 'main.js'), 'utf8');
  const cssSrc = await readFile(join(WEB, 'css', 'chat.css'), 'utf8');
  ok('6a 组件钉：showChatSkeleton/hideChatSkeleton 已整件删除（零导出、零死码）',
    !/export function showChatSkeleton/.test(indSrc) && !/export function hideChatSkeleton/.test(indSrc));
  ok('6b 挂载钉：空线程才出细加载行（加载中这一档的落点）',
    /if \(!pv\.dom\.chat\.children\.length\) showHistoryLoader\(\);/.test(sidebarSrc));
  ok('6c 摘除钉：historyPage 处理器只摘 loader（不再有骨架摘除腿）',
    /hideHistoryLoader\(\);/.test(mainSrc) && !/hideChatSkeleton/.test(mainSrc));
  ok('6d 形钉：CSS 骨架规则零残留（占位泡 / shimmer / 宽度档全删；注释留档不算规则）',
    !/\.chat-skeleton\s*\{/.test(cssSrc) && !/\.chat-sk-bubble\s*\{/.test(cssSrc)
      && !/@keyframes\s+chat-sk-shimmer/.test(cssSrc) && !/\.chat-sk-w\d+\s*\{/.test(cssSrc));

  console.log(failures === 0 ? '\nALL PASS' : `\n${failures} FAILURE(S)`);
} catch (e) {
  failures++;
  console.error('SPEC ERROR:', e);
} finally {
  await browser.close().catch(() => {});
  server.close();
}
process.exit(failures === 0 ? 0 : 1);

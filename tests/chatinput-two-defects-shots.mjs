// chatinput-two-defects-shots.mjs — 聊天输入两缺陷批（2026-10-22）前后对照证据。
//
// 症状一（消息并泡）：两连发在会话 busy 时入队 —— 修后每次发送 = 立即自己的独立
//   气泡（带「待发送」时钟小标），drain 翻面不新增气泡。截图 = 两连发后的即时态。
// 症状二（附件大绿块）：用户发送的文件附件卡 —— 修前 = 气泡绿底无边框大卡，
//   修后 = 统一 chip 族（暗玻璃底 / 细边框 / 28px 类型图标档 / 文件名+大小）。
//   亮暗双主题各一版。
//
// 用法：node tests/chatinput-two-defects-shots.mjs <evidenceSubdir>
//   产物 = evidence/<subdir>/*.png + stdout 断言清单（零伪造 state：真 web 树 +
//   真 WS 桩，形制同 tests/f1-send-queue.spec.mjs）。
import { chromium } from 'playwright-core';
import { createServer } from 'node:http';
import { readFile } from 'node:fs/promises';
import { mkdirSync } from 'node:fs';
import { join, dirname, extname, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const WEB = join(HERE, '..', 'src', 'main', 'resources', 'web');
const SUB = process.argv[2] || '20261022_chatinput-two-defects';
const OUT = join(HERE, '..', 'evidence', SUB);
mkdirSync(OUT, { recursive: true });

const MIME = {
  '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css',
  '.svg': 'image/svg+xml', '.png': 'image/png', '.ico': 'image/x-icon',
  '.json': 'application/json', '.woff2': 'font/woff2', '.woff': 'font/woff',
};
const SID = 's-twodefects';

let failures = 0;
const ok = (name, cond, extra = '') => {
  console.log(`${cond ? 'PASS' : 'FAIL'}  ${name}${extra ? '  — ' + extra : ''}`);
  if (!cond) failures++;
};
const sleep = (ms) => new Promise(r => setTimeout(r, ms));

// ── WS 桩：boot 引导 + 受控 busy/done（形制同 f1-send-queue 桩）─────────────
let releaseBusy = false;
const accepted = []; // 受理的用户内容帧（drain 出线的）
function isUserContentFrame(m) {
  return !m.type && typeof m.content === 'string';
}
function wireUp(ws) {
  ws.onMessage((raw) => {
    let m; try { m = JSON.parse(raw); } catch { return; }
    if (m.type === 'ping') { ws.send(JSON.stringify({ type: 'pong' })); return; }
    if (m.type === 'getHistory') {
      ws.send(JSON.stringify({ type: 'historyPage', sessionId: m.sessionId, messages: [], hasMore: false, offset: 0, total: 0 }));
      return;
    }
    if (m.type === '__e2e_arm_busy') {
      // 场景 A 前置：先把会话顶成 busy ⇒ 两连发都走 busy 排队腿（被测面）。
      releaseBusy = false;
      ws.send(JSON.stringify({ type: 'sessionBusy', sessionId: SID, busy: true }));
      return;
    }
    if (m.type === '__e2e_release') {
      // 测试控制面：放行 drain（busy 清 + 终态帧）。
      releaseBusy = true;
      ws.send(JSON.stringify({ type: 'sessionBusy', sessionId: SID, busy: false }));
      ws.send(JSON.stringify({ type: 'done', sessionId: SID, model: 'mock-llm' }));
      return;
    }
    if (isUserContentFrame(m)) {
      accepted.push(m);
      // 受理即 busy（与引擎 idle 受理同形）；release 前不发终态帧 ⇒ 队列保持。
      ws.send(JSON.stringify({ type: 'sessionBusy', sessionId: m.sessionId, busy: true }));
      if (releaseBusy) {
        setTimeout(() => {
          try {
            ws.send(JSON.stringify({ type: 'sessionBusy', sessionId: m.sessionId, busy: false }));
            ws.send(JSON.stringify({ type: 'done', sessionId: m.sessionId, model: 'mock-llm' }));
          } catch { /* 连接已被测试关闭 */ }
        }, 150);
      }
    }
  });
  ws.send(JSON.stringify({ type: 'configData', config: '{"features":{}}', configured: true, onboarding: 'done', models: [], defaults: {} }));
  ws.send(JSON.stringify({ type: 'sessionList', sessions: [{ id: SID, name: '两缺陷E2E' }], activeId: SID, folders: [] }));
}

// ── 静态服务器 ──────────────────────────────────────────────────────
const server = createServer(async (req, res) => {
  try {
    const p = decodeURIComponent(new URL(req.url, 'http://x').pathname);
    const file = normalize(join(WEB, p === '/' ? 'index.html' : p));
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

async function boot(colorScheme) {
  const ctx = await browser.newContext({
    viewport: { width: 1280, height: 860 }, colorScheme, deviceScaleFactor: 2,
  });
  await ctx.addInitScript(() => {
    localStorage.setItem('nebflow_token', 't');
    localStorage.setItem('nebflow_locale', 'zh-CN');
    localStorage.setItem('stand_input_history', '[]');
  });
  const page = await ctx.newPage();
  await page.routeWebSocket(/\/ws/, ws => wireUp(ws));
  await page.goto(BASE + '/index.html');
  await page.waitForSelector('#input', { state: 'visible', timeout: 20000 });
  await sleep(1500);
  return { ctx, page };
}

// ── 场景 A：两连发（busy 中）→ 各自独立成泡 ─────────────────────────────
{
  const { ctx, page } = await boot('dark');
  // 会话置 busy（与引擎受理同形的帧 + 本地确认窗用流活动豁免——直接等 busy 位）。
  await page.evaluate(async () => {
    const ws = await import('/js/ws.js');
    ws.sendWs({ type: '__e2e_arm_busy' });
  });
  // 桩对未知控制帧不回应 —— busy 由发送腿乐观置位达成：直接发第一条即可。
  const input = page.locator('#input');
  await input.click();
  await input.fill('zsh: suspended (tty input)  sbt run —（模拟终端粘贴文本）');
  await input.press('Enter');
  await sleep(400);
  await input.fill('为什么刚刚stand被强制退出了，检查一下现在任务情况。');
  await input.press('Enter');
  await sleep(600);

  const state = await page.evaluate(() => {
    const rows = [...document.querySelectorAll('#chat .row.user')];
    return {
      bubbles: rows.map(r => ({
        msgId: r.dataset.msgId || '',
        text: (r.querySelector('.bubble.user')?.textContent || '').slice(0, 40),
        pending: r.querySelectorAll('.msg-pending-mark').length,
      })),
      mergedBubble: rows.some(r => {
        const t = r.querySelector('.bubble.user')?.textContent || '';
        return t.includes('终端粘贴文本') && t.includes('强制退出');
      }),
    };
  });
  ok('A1 两连发 = 两个独立用户气泡行', state.bubbles.length === 2, JSON.stringify(state.bubbles));
  ok('A2 任一气泡不含另一条文本（不并泡）', state.mergedBubble === false);
  ok('A3 两气泡裸上屏零小标（busy 排队腿——排队呈现回退批：已收到标退役）', state.bubbles.length === 2 && state.bubbles.every(b => b.pending === 0));
  ok('A4 两气泡 msgId 互异', state.bubbles.length === 2 && state.bubbles[0].msgId && state.bubbles[0].msgId !== state.bubbles[1].msgId);
  await page.screenshot({ path: join(OUT, 'A-two-sends-busy-dark.png') });

  // drain：放行 → 桩回 done 两次 ⇒ 两项依次出线；气泡不新增、小标翻面。
  await page.evaluate(async () => {
    const ws = await import('/js/ws.js');
    ws.sendWs({ type: '__e2e_release' });
  });
  await sleep(1800);
  const after = await page.evaluate(() => {
    const rows = [...document.querySelectorAll('#chat .row.user')];
    return {
      bubbles: rows.map(r => ({
        text: (r.querySelector('.bubble.user')?.textContent || '').slice(0, 30),
        pending: r.querySelectorAll('.msg-pending-mark').length,
      })),
    };
  });
  ok('A5 drain 后仍两气泡（排队项零新增呈现）', after.bubbles.length === 2, JSON.stringify(after.bubbles));
  ok('A6 drain 后小标翻面（已送达态）', after.bubbles.every(b => b.pending === 0));
  ok('A7 恰两帧出线（两次独立 send = 两帧）', accepted.length === 2, `accepted=${accepted.length}`);
  const ids = new Set(accepted.map(m => m.clientMessageId));
  ok('A8 两帧 clientMessageId 互异', ids.size === 2);
  await page.screenshot({ path: join(OUT, 'A-two-sends-after-drain-dark.png') });
  await ctx.close();
}

// ── 场景 B：用户文件附件卡（亮 / 暗双版）────────────────────────────────
for (const scheme of ['light', 'dark']) {
  const { ctx, page } = await boot(scheme);
  await page.evaluate(async () => {
    const chat = await import('/js/chat.js');
    chat.renderUserBubble('帮我把这两个ppt整理为模版，这样让未来agent做ppt可以用我的模版做出一样风格的ppt。', [
      { type: 'file', name: 'New template 16-9.pptx', size: 78000, mimeType: 'application/vnd.openxmlformats-officedocument.presentationml.presentation' },
      { type: 'file', name: 'New template.pptx', size: 79000, mimeType: 'application/vnd.openxmlformats-officedocument.presentationml.presentation' },
    ]);
  });
  await sleep(300);
  const cardPaint = await page.evaluate(() => {
    const card = document.querySelector('#chat .row.user-media .file-card');
    if (!card) return null;
    const cs = getComputedStyle(card);
    const icon = card.querySelector('.fc-icon');
    return {
      bg: cs.backgroundColor, border: cs.borderLeftWidth + ' ' + cs.borderLeftStyle,
      radius: cs.borderTopLeftRadius, width: cs.width,
      iconSize: icon ? getComputedStyle(icon).width : '',
      green: cs.backgroundColor === 'rgb(149, 236, 105)',
    };
  });
  ok(`B-${scheme} 文件卡在场`, !!cardPaint);
  if (cardPaint) {
    ok(`B-${scheme} 附件卡不落用户气泡绿`, cardPaint.green === false, `bg=${cardPaint.bg}`);
    ok(`B-${scheme} 细边框（1px solid）`, cardPaint.border.startsWith('1px solid'), cardPaint.border);
    ok(`B-${scheme} 紧凑宽（≤264px，非满宽）`, parseFloat(cardPaint.width) <= 265, `w=${cardPaint.width}`);
    ok(`B-${scheme} 图标 28px 档`, parseFloat(cardPaint.iconSize) === 28, cardPaint.iconSize);
  }
  await page.screenshot({ path: join(OUT, `B-file-chip-${scheme}.png`) });
  await ctx.close();
}

await browser.close();
await new Promise(r => server.close(r));
console.log(failures === 0 ? 'ALL PASS' : `${failures} FAILURES`);
process.exit(failures === 0 ? 0 : 1);

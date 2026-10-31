// output-tag.spec.mjs — <output> 用户可见块验收（output 块语法批，作者令
// 2026-10-31 16:13；16:16 修订：标签式 <output>…</output>，非 ```output 围栏）。
// 设计稿：docs/design/20261030_human-readable-presentation.zh-CN.md §11。
//
// 被测真实链路（照 human-face-render.spec.mjs 同一套替身纪律）：
//   · 管线透明面：utils.js renderMarkdownWithMath（源级提取 pass：平衡闸 →
//     占位符 → 恢复渲染；散标签转义字面化；无标签零改动）；
//   · 聊天摘除面：persistence.restoreFromBackendHistory（历史重建 = 冷启动
//     真实面）+ outputTag.js（applyOutputFace 只显块内）+ turnGroup.js
//     （turn-header 角标 toggle：展开 = 原文全量 + <output> 语法原样现形）。
//
// 判据：
//   R1 摘除：output 气泡正常态只显块内内容；框外正文/思考过程不可见
//   R2 角标展开：点 turn-header ⇒ 原文全量现形（含字面 <output> 标签）+
//      思考行揭开；再点收回
//   R3 降级：无 <output> 块的旧消息照常全显（不吞历史）
//   R4 零过程行的 output 轮也立 header（角标 = 框外原文唯一展开入口）
//   R5 管线透明面：标签不现形、内容渲染；散标签（流式中间态）字面化不吞文
//   R6 亮暗双主题截图：正常态（摘除）+ 展开态（语法现形）各 ×2
//   R7 全进程零 console 错误 / 零 pageerror
//
// 运行：node tests/output-tag.spec.mjs
// 可选 env：PORT=8181、SHOTS_DIR=<dir>（默认 evidence/20261031_output-tag/shots）

import { chromium } from 'playwright';
import { mkdirSync, readFileSync } from 'node:fs';
import { createServer } from 'node:http';
import { dirname, join, resolve, extname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { installTicketMock } from './nf-ticket-mock.mjs';

const HERE = dirname(fileURLToPath(import.meta.url));
const ROOT = resolve(HERE, '..');
const WEB_DIR = resolve(ROOT, 'src/main/resources/web');
const PORT = Number(process.env.PORT || 8181);
const BASE = `http://127.0.0.1:${PORT}`;
const SHOTS_DIR = process.env.SHOTS_DIR || join(ROOT, 'evidence', '20261031_output-tag', 'shots');
const VIEWPORT = { width: 1280, height: 900 };
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

/* ─────────────── 夹具消息 ─────────────── */
const OUTER_DETAIL = '机器面细节：完整读数、代码锚、验收过程——框外应被摘除。';
const THINKING_TEXT = '让我先想想从哪下手……（思考过程，默认折叠）';
const OUTPUT_TEXT = [
  '我先看了下链路，问题找到了。',
  '',
  '<output>',
  '改好了，登录缓存的失效 bug 已修复。',
  '',
  '动了 src/login.ts 一个文件，42 个测试全过，不用你动手。',
  '</output>',
  '',
  OUTER_DETAIL,
].join('\n');
const LEGACY_TEXT = '这是一条没有 output 块的老消息：全文都应该照常可见，一个字不摘。';
const OUTPUT_ONLY_TEXT = [
  '<output>',
  '结论先行：只包了一块，没有思考过程的一轮。',
  '</output>',
  '',
  OUTER_DETAIL,
].join('\n');

/* ─────────────── 静态服务（真 web/ 树；自有端口） ─────────────── */
const MIME = {
  '.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8',
  '.mjs': 'text/javascript; charset=utf-8', '.css': 'text/css; charset=utf-8',
  '.json': 'application/json', '.svg': 'image/svg+xml', '.png': 'image/png',
  '.md': 'text/markdown; charset=utf-8',
};
const webServer = createServer((req, res) => {
  const u = new URL(req.url, BASE);
  let p = decodeURIComponent(u.pathname);
  if (p === '/') p = '/index.html';
  const file = join(WEB_DIR, p);
  if (!file.startsWith(WEB_DIR)) { res.writeHead(403); res.end(); return; }
  try {
    const body = readFileSync(file);
    res.writeHead(200, { 'Content-Type': MIME[extname(file)] || 'application/octet-stream' });
    res.end(body);
  } catch {
    res.writeHead(404); res.end('not found');
  }
});
await new Promise((r) => webServer.listen(PORT, '127.0.0.1', r));

/* ─────────────── 应用引导 ─────────────── */
async function boot(browser) {
  const ctx = await browser.newContext({ viewport: VIEWPORT });
  await ctx.addInitScript(() => {
    localStorage.setItem('nebflow_token', 't'); localStorage.setItem('neblink_token', 't');
    localStorage.setItem('nebflow_locale', 'zh-CN'); localStorage.setItem('neblink_locale', 'zh-CN');
  });
  const page = await ctx.newPage();
  page.setDefaultTimeout(30000);
  const pageErrors = [];
  const consoleErrors = [];
  page.on('pageerror', (e) => pageErrors.push(String(e.message || e).slice(0, 300)));
  page.on('console', (m) => { if (m.type() === 'error') consoleErrors.push(String(m.text()).slice(0, 300)); });
  await page.route('**/api/**', (r) => r.fulfill({ json: {} }));
  await page.route('**/agents/**', (r) => r.fulfill({ json: {} }));
  await installTicketMock(page);
  await page.routeWebSocket(/\/ws/, (ws) => {
    ws.onMessage((raw) => {
      let m; try { m = JSON.parse(raw); } catch { return; }
      if (m.type === 'getHistory') {
        return ws.send(JSON.stringify({ type: 'historyPage', sessionId: m.sessionId, messages: [], hasMore: false, offset: 0 }));
      }
    });
    ws.send(JSON.stringify({ type: 'configData', configured: true, onboarding: 'done', models: [], defaults: {} }));
    ws.send(JSON.stringify({ type: 'sessionList', sessions: [{ id: 'sess-1', agentName: 'Nebula', title: 'T', updatedAt: Date.now() }], activeId: 'sess-1', folders: [] }));
  });
  await page.goto(BASE + '/index.html');
  await page.waitForSelector('#activity-bar', { timeout: 20000, state: 'attached' });
  await page.evaluate(() => {
    document.querySelectorAll('.onboarding-overlay').forEach((o) => o.remove());
    document.documentElement.classList.remove('ui-init');
  });
  await sleep(500);
  return { ctx, page, pageErrors, consoleErrors };
}

/* ─────────────── 断言 ─────────────── */
let failed = 0;
const ok = (cond, label) => {
  console.log(`${cond ? 'PASS' : 'FAIL'}  ${label}`);
  if (!cond) failed++;
};

const browser = await chromium.launch();
try {
  const { page, pageErrors, consoleErrors } = await boot(browser);

  // 历史重建（真实链路：buildHistoryRow → 升级批 → buildTurnSummariesForHistory）
  await page.evaluate((msgs) => {
    window.__otMsgs = msgs;
  }, [
    { type: 'user', text: '帮我修登录缓存', timestamp: Date.now() - 30000 },
    { type: 'ai', text: OUTPUT_TEXT, thinking: THINKING_TEXT, terminal: true, durationMs: 4200, model: 'stub-x', timestamp: Date.now() - 26000 },
    { type: 'user', text: '这条是老消息', timestamp: Date.now() - 20000 },
    { type: 'ai', text: LEGACY_TEXT, terminal: true, durationMs: 800, model: 'stub-x', timestamp: Date.now() - 19000 },
    { type: 'user', text: '再试一轮', timestamp: Date.now() - 10000 },
    { type: 'ai', text: OUTPUT_ONLY_TEXT, terminal: true, durationMs: 600, model: 'stub-x', timestamp: Date.now() - 9000 },
  ]);
  await page.evaluate(async () => {
    const { restoreFromBackendHistory } = await import('/js/persistence.js');
    restoreFromBackendHistory(window.__otMsgs, { scrollToBottom: false });
  });
  await sleep(1200); // 等 rAF 升级批把 output 面渲上

  // ── R1 摘除：output 气泡正常态只显块内 ──
  const r1 = await page.evaluate((outer) => {
    const chat = document.getElementById('chat');
    const bubbles = Array.from(chat.querySelectorAll('.bubble.ai:not(.thinking-bubble)'));
    const faced = bubbles.filter((b) => b.dataset.nfOutput === '1');
    if (!faced.length) return { faced: 0 };
    const b = faced[0];
    return {
      faced: faced.length,
      innerVisible: b.textContent.includes('改好了，登录缓存的失效 bug 已修复'),
      outerStripped: !b.textContent.includes(outer),
      tagsInvisible: !b.textContent.includes('<output>'),
      thinkingTucked: !!chat.querySelector('.row.thinking-row.nf-tucked'),
    };
  }, OUTER_DETAIL);
  ok(r1.faced >= 2, `R1 output 面盖章（data-nf-output 泡=${r1.faced}，期望 ≥2）`);
  ok(r1.innerVisible === true, 'R1 块内内容可见');
  ok(r1.outerStripped === true, 'R1 框外正文摘除（不可见）');
  ok(r1.tagsInvisible === true, 'R1 正常态标签语法不现形');
  ok(r1.thinkingTucked === true, 'R1 思考行保持折叠（过程摘除面）');

  // ── R2 角标展开：原文全量 + 语法现形 + 思考揭开；再点收回 ──
  const r2a = await page.evaluate((outer) => {
    const chat = document.getElementById('chat');
    const header = chat.querySelector('.turn-header');
    if (!header) return { header: false };
    header.click(); // 展开
    const faced = chat.querySelector('.bubble.ai[data-nf-output]');
    const thinking = chat.querySelector('.row.thinking-row');
    return {
      header: true,
      state: header.dataset.turnState,
      rawShown: faced.textContent.includes('<output>') && faced.textContent.includes(outer),
      rawPre: !!faced.querySelector('.nf-output-raw-pre'),
      thinkingVisible: thinking && !thinking.classList.contains('nf-tucked'),
    };
  }, OUTER_DETAIL);
  ok(r2a.header === true, 'R2 turn-header 角标在');
  ok(r2a.state === 'done-expanded', `R2 点开即展开态（${r2a.state}）`);
  ok(r2a.rawShown === true, 'R2 展开态原文全量（含框外正文与字面 <output> 标签）');
  ok(r2a.rawPre === true, 'R2 展开态为原文纯文本面（.nf-output-raw-pre）');
  ok(r2a.thinkingVisible === true, 'R2 思考行随展开揭开');
  const r2b = await page.evaluate(() => {
    const chat = document.getElementById('chat');
    const header = chat.querySelector('.turn-header');
    header.click(); // 收回
    const faced = chat.querySelector('.bubble.ai[data-nf-output]');
    const thinking = chat.querySelector('.row.thinking-row');
    return {
      state: header.dataset.turnState,
      backToInner: faced.textContent.includes('改好了，登录缓存的失效 bug 已修复') &&
        !faced.textContent.includes('<output>'),
      thinkingTucked: thinking && thinking.classList.contains('nf-tucked'),
    };
  });
  ok(r2b.state === 'done', `R2 再点收回（${r2b.state}）`);
  ok(r2b.backToInner === true, 'R2 收回态回块内-only 渲染');
  ok(r2b.thinkingTucked === true, 'R2 思考行随收回再折叠');

  // ── R3 降级：无 output 块的旧消息照常全显 ──
  const r3 = await page.evaluate(() => {
    const chat = document.getElementById('chat');
    const plain = Array.from(chat.querySelectorAll('.bubble.ai:not(.thinking-bubble)'))
      .filter((b) => b.dataset.nfOutput !== '1' && b.textContent.includes('老消息'));
    return { count: plain.length, full: plain.length > 0 && plain[0].textContent.includes('一个字不摘') };
  });
  ok(r3.count === 1 && r3.full === true, 'R3 无块旧消息全显（不吞历史）');

  // ── R4 零过程行的 output 轮也立 header（角标 = 唯一展开入口） ──
  const r4 = await page.evaluate((outer) => {
    const chat = document.getElementById('chat');
    const headers = Array.from(chat.querySelectorAll('.turn-header'));
    // 最后一轮（output-only）的 header = 最后一个
    const last = headers[headers.length - 1];
    if (!last) return { headers: 0 };
    last.click();
    const faced = Array.from(chat.querySelectorAll('.bubble.ai[data-nf-output]'));
    const lastFaced = faced[faced.length - 1];
    return {
      headers: headers.length,
      state: last.dataset.turnState,
      expanded: lastFaced.textContent.includes('<output>') && lastFaced.textContent.includes(outer),
    };
  }, OUTER_DETAIL);
  ok(r4.headers === 2, `R4 header 计数（=${r4.headers}：过程轮 + output-only 轮各一；纯文本旧轮不立）`);
  ok(r4.state === 'done-expanded' && r4.expanded === true, 'R4 零过程行 output 轮角标展开 = 原文全量现形');

  // ── R5 管线透明面（非聊天四面共享同 pass）──
  const r5 = await page.evaluate(async () => {
    const { renderMarkdownWithMath } = await import('/js/utils.js');
    const mk = (md) => {
      const host = document.createElement('div');
      host.className = 'r5-host';
      host.innerHTML = renderMarkdownWithMath(md, false, { cache: false });
      document.body.appendChild(host);
      return host;
    };
    // 5a 平衡对：标签不现形、内容渲染、框外内容仍在（透明面不做摘除——
    //    摘除是聊天面语义）
    const a = mk('前置说明。\n\n<output>\n**结论**：管线面内容照常渲染。\n</output>\n\n框外段落。');
    // 5b 散标签（流式中间态）：字面化、不吞文
    const b = mk('流式中：<output>半句结论还没闭合');
    // 5c 无标签：零改动
    const c = mk('普通段落，无标签。');
    return {
      aTagsHidden: !a.textContent.includes('<output>') && a.innerHTML.indexOf('&lt;output&gt;') === -1,
      aInner: a.querySelector('strong') !== null && a.textContent.includes('结论'),
      aOuterKept: a.textContent.includes('框外段落'),
      bLiteral: b.textContent.includes('<output>半句结论'),
      bSwallowSafe: b.textContent.includes('流式中'),
      cPlain: c.textContent.includes('普通段落，无标签。'),
    };
  });
  ok(r5.aTagsHidden === true, 'R5a 管线面标签不现形');
  ok(r5.aInner === true, 'R5a 块内 markdown 渲染（strong）');
  ok(r5.aOuterKept === true, 'R5a 透明面框外内容保留（非聊天面不摘除）');
  ok(r5.bLiteral === true, 'R5b 散标签流式中间态字面化可见');
  ok(r5.bSwallowSafe === true, 'R5b 散标签不吞后续文本');
  ok(r5.cPlain === true, 'R5c 无标签文本零改动');

  // ── R6 亮暗双主题截图：正常态（摘除）+ 展开态（语法现形）──
  mkdirSync(SHOTS_DIR, { recursive: true });
  // 撤掉 R5 管线宿主（自由 body 流会把 app 布局挤窄），截图只留真实聊天面；
  // R4 把第三轮留在了展开态——先收回，正常态截图必须是纯摘除面。
  await page.evaluate(() => {
    document.querySelectorAll('.r5-host').forEach((h) => h.remove());
    const headers = Array.from(document.querySelectorAll('#chat .turn-header'));
    const last = headers[headers.length - 1];
    if (last && last.dataset.turnState === 'done-expanded') last.click();
    document.getElementById('chat').scrollTop = 0;
  });
  await sleep(200);
  await page.screenshot({ path: join(SHOTS_DIR, 'output-normal-light.png') });
  await page.emulateMedia({ colorScheme: 'dark' });
  await sleep(600);
  await page.screenshot({ path: join(SHOTS_DIR, 'output-normal-dark.png') });
  // 展开第一轮流，截语法现形面
  await page.evaluate((outer) => {
    const chat = document.getElementById('chat');
    const header = chat.querySelector('.turn-header');
    if (header && header.dataset.turnState === 'done') header.click();
    chat.scrollTop = 0;
  }, OUTER_DETAIL);
  await sleep(300);
  await page.screenshot({ path: join(SHOTS_DIR, 'output-expanded-dark.png') });
  await page.emulateMedia({ colorScheme: 'light' });
  await sleep(600);
  await page.screenshot({ path: join(SHOTS_DIR, 'output-expanded-light.png') });
  const themed = await page.evaluate(() => {
    const b = document.querySelector('.bubble.ai[data-nf-output]');
    if (!b) return null;
    const cs = getComputedStyle(b);
    return { bg: cs.backgroundColor, fg: cs.color };
  });
  ok(!!themed, `R6 双主题截图×4 落盘（展开态 bg=${themed && themed.bg}）`);

  // ── R7 零错误铁律 ──
  ok(pageErrors.length === 0, `R7 页面零未捕获异常（${pageErrors.length}${pageErrors.length ? '：' + pageErrors[0] : ''}）`);
  ok(consoleErrors.length === 0, `R7 零 console 错误（${consoleErrors.length}${consoleErrors.length ? '：' + consoleErrors[0] : ''}）`);

  await page.context().close();
} finally {
  await browser.close();
  webServer.close();
}

console.log(failed === 0 ? '\nALL PASS' : `\nFAILED: ${failed}`);
process.exit(failed === 0 ? 0 : 1);

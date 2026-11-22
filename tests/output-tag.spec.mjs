// output-tag.spec.mjs — <report> 用户可见块验收（output 块语法批，作者令
// 2026-10-31 16:13；16:16 修订：标签式 <report>…</report>，非 ```output 围栏）。
// 设计稿：docs/design/20261030_human-readable-presentation.zh-CN.md §11。
//
// 被测真实链路（照 human-face-render.spec.mjs 同一套替身纪律）：
//   · 管线透明面：utils.js renderMarkdownWithMath（源级提取 pass：平衡闸 →
//     占位符 → 恢复渲染；散标签转义字面化；无标签零改动）；
//   · 聊天摘除面：persistence.restoreFromBackendHistory（历史重建 = 冷启动
//     真实面）+ outputTag.js（applyOutputFace 只显块内）+ turnGroup.js
//     （turn-header 角标 toggle：展开 = 原文全量 + <report> 语法原样现形）。
//
// 判据：
//   R1 摘除：report 气泡正常态只显块内内容；框外正文/思考过程不可见
//   R2 角标展开：点 turn-header ⇒ 原文全量现形（含字面 <report> 标签）+
//      思考行揭开；再点收回
//   R3 降级：无 <report> 块的旧消息照常全显（不吞历史）
//   R3b 范围：非 root 会话的行逐字保留原展示（全文 + 字面标签，零摘除）
//   R4 零过程行的 report 轮也立 header（角标 = 框外原文唯一展开入口）
//   R5 管线透明面：标签不现形、内容渲染；散标签（流式中间态）字面化不吞文；
//      非 root 面（不传 opts.report）标签保持字面文本
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
// WEB_ROOT 覆写 = 负控腿（拿本 spec 去打**未修**的 web 树，必须触红）。
const WEB_DIR = resolve(process.env.WEB_ROOT || join(ROOT, 'src/main/resources/web'));
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
  '<report>',
  '改好了，登录缓存的失效 bug 已修复。',
  '',
  '动了 src/login.ts 一个文件，42 个测试全过，不用你动手。',
  '</report>',
  '',
  OUTER_DETAIL,
].join('\n');
const LEGACY_TEXT = '这是一条没有 report 块的老消息：全文都应该照常可见，一个字不摘。';
// Non-root contrast: a child session's message. The visibility rule is root-only,
// so this row must keep its previous presentation — FULL text, tags literal.
const NONROOT_TEXT = [
  '子会话过程叙述不应被摘除。',
  '<report>',
  '子会话里的 report 块对非 root 面是普通文本。',
  '</report>',
  '子会话结尾说明。',
].join('\n');
const OUTPUT_ONLY_TEXT = [
  '<report>',
  '结论先行：只包了一块，没有思考过程的一轮。',
  '</report>',
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

  // 历史重建（真实链路：buildHistoryRow → 升级批 → buildTurnSummariesForHistory）。
  // root 行显式 `isRoot: true`（可见性规则按源头身份生效）；最后一条是**非 root**
  // 对照行（子会话），它必须逐字保留原展示。
  await page.evaluate((msgs) => {
    window.__otMsgs = msgs;
  }, [
    { type: 'user', text: '帮我修登录缓存', timestamp: Date.now() - 30000 },
    { type: 'ai', text: OUTPUT_TEXT, thinking: THINKING_TEXT, terminal: true, durationMs: 4200, model: 'stub-x', isRoot: true, timestamp: Date.now() - 26000 },
    { type: 'user', text: '这条是老消息', timestamp: Date.now() - 20000 },
    { type: 'ai', text: LEGACY_TEXT, terminal: true, durationMs: 800, model: 'stub-x', isRoot: true, timestamp: Date.now() - 19000 },
    { type: 'user', text: '再试一轮', timestamp: Date.now() - 10000 },
    { type: 'ai', text: OUTPUT_ONLY_TEXT, terminal: true, durationMs: 600, model: 'stub-x', isRoot: true, timestamp: Date.now() - 9000 },
    { type: 'user', text: '子会话给个进度', timestamp: Date.now() - 5000 },
    { type: 'ai', text: NONROOT_TEXT, terminal: true, durationMs: 300, model: 'stub-x', isRoot: false, timestamp: Date.now() - 4000 },
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
      tagsInvisible: !b.textContent.includes('<report>'),
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
      rawShown: faced.textContent.includes('<report>') && faced.textContent.includes(outer),
      rawPre: !!faced.querySelector('.nf-output-raw-pre'),
      thinkingVisible: thinking && !thinking.classList.contains('nf-tucked'),
    };
  }, OUTER_DETAIL);
  ok(r2a.header === true, 'R2 turn-header 角标在');
  ok(r2a.state === 'done-expanded', `R2 点开即展开态（${r2a.state}）`);
  ok(r2a.rawShown === true, 'R2 展开态原文全量（含框外正文与字面 <report> 标签）');
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
        !faced.textContent.includes('<report>'),
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

  // ── R3b 范围对照：非 root 会话的行逐字保留原展示 ──
  // 原展示 = 管线不吃 report 语义（改造前的缺省面）：标签原样留在 HTML 里（浏览器
  // 当未知元素解析，不成可见字面文本），正文一个字不摘。
  const r3b = await page.evaluate(() => {
    const chat = document.getElementById('chat');
    const rows = Array.from(chat.querySelectorAll('.bubble.ai:not(.thinking-bubble)'))
      .filter((b) => b.textContent.includes('子会话过程叙述'));
    if (!rows.length) return { found: 0 };
    const b = rows[0];
    return {
      found: rows.length,
      noFace: !b.dataset.nfOutput && !b.dataset.nfDeferOutput,
      fullText: b.textContent.includes('子会话过程叙述不应被摘除。') && b.textContent.includes('子会话结尾说明。'),
      tagsRaw: b.innerHTML.includes('<report>') && !b.innerHTML.includes('&lt;report&gt;'),
      innerText: b.textContent.includes('子会话里的 report 块对非 root 面是普通文本。'),
    };
  });
  ok(r3b.found === 1, `R3b 非 root 对照行在场（=${r3b.found}）`);
  ok(r3b.noFace === true, 'R3b 非 root 行不带 report 面标记（零摘除）');
  ok(r3b.fullText === true, 'R3b 非 root 行全文可见（框外叙述不被摘）');
  ok(r3b.tagsRaw === true, 'R3b 非 root 行标签原样透传（不转义、不成可见语法）');
  ok(r3b.innerText === true, 'R3b 非 root 行块内文本照常可见');

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
      expanded: lastFaced.textContent.includes('<report>') && lastFaced.textContent.includes(outer),
    };
  }, OUTER_DETAIL);
  ok(r4.headers === 2, `R4 header 计数（=${r4.headers}：过程轮 + output-only 轮各一；纯文本旧轮不立）`);
  ok(r4.state === 'done-expanded' && r4.expanded === true, 'R4 零过程行 output 轮角标展开 = 原文全量现形');

  // ── R5 管线透明面（root 面传 `opts.report`；非 root 面不传 = 改造前缺省）──
  const r5 = await page.evaluate(async () => {
    const { renderMarkdownWithMath } = await import('/js/utils.js');
    const mk = (md, opts) => {
      const host = document.createElement('div');
      host.className = 'r5-host';
      host.innerHTML = renderMarkdownWithMath(md, false, Object.assign({ cache: false }, opts));
      document.body.appendChild(host);
      return host;
    };
    // 5a 平衡对（root 面）：标签不现形、内容渲染、框外内容仍在（透明面不做摘除
    //    ——摘除是聊天面语义）
    const a = mk('前置说明。\n\n<report>\n**结论**：管线面内容照常渲染。\n</report>\n\n框外段落。', { report: true });
    // 5b 散标签（流式中间态，root 面）：字面化、不吞文
    const b = mk('流式中：<report>半句结论还没闭合', { report: true });
    // 5c 无标签：零改动
    const c = mk('普通段落，无标签。', { report: true });
    // 5d 非 root 面（不传 report）：与改造前逐字一致——标签原样透传（浏览器当
    //    未知元素解析，不成可见语法），块内文本照常渲染，零转义字符注入正文。
    const d = mk('前置说明。\n\n<report>\n**结论**：非 root 面照常渲染。\n</report>\n\n框外段落。');
    return {
      aTagsHidden: !a.textContent.includes('<report>') && a.innerHTML.indexOf('&lt;report&gt;') === -1,
      aInner: a.querySelector('strong') !== null && a.textContent.includes('结论'),
      aOuterKept: a.textContent.includes('框外段落'),
      bLiteral: b.textContent.includes('<report>半句结论'),
      bSwallowSafe: b.textContent.includes('流式中'),
      cPlain: c.textContent.includes('普通段落，无标签。'),
      dTagsRaw: d.innerHTML.includes('<report>') && !d.innerHTML.includes('&lt;report&gt;'),
      dInner: d.textContent.includes('非 root 面照常渲染'),
      // 改造前缺省面逐字保留：未知标签被 marked 当 HTML 块 ⇒ 块内 markdown
      // **不**加工（这正是既有展示，不是回归）。
      dNotProcessed: d.querySelector('strong') === null,
      dNoEscape: !d.textContent.includes('&lt;report&gt;'),
    };
  });
  ok(r5.aTagsHidden === true, 'R5a 管线面标签不现形（root 面）');
  ok(r5.aInner === true, 'R5a 块内 markdown 渲染（strong）');
  ok(r5.aOuterKept === true, 'R5a 透明面框外内容保留（非聊天面不摘除）');
  ok(r5.bLiteral === true, 'R5b 散标签流式中间态字面化可见（root 面）');
  ok(r5.bSwallowSafe === true, 'R5b 散标签不吞后续文本');
  ok(r5.cPlain === true, 'R5c 无标签文本零改动');
  ok(r5.dTagsRaw === true, 'R5d 非 root 面标签原样透传（不转义、与改造前逐字一致）');
  ok(r5.dInner === true, 'R5d 非 root 面块内文本照常可见');
  ok(r5.dNotProcessed === true, 'R5d 非 root 面块内 markdown 不加工作为既有展示保留');
  ok(r5.dNoEscape === true, 'R5d 非 root 面正文零转义字符注入');

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

  // ── R6b 非 root 对照截图：同一屏里 root 面摘除 vs 非 root 面全文 ──
  // 先把第一轮收回正常态（摘除面），再把视口滚到非 root 对照行——一张图里同时
  // 能看到「root 气泡只有块内」与「非 root 气泡全文 + 标签原样」。
  await page.evaluate(() => {
    const chat = document.getElementById('chat');
    const header = chat.querySelector('.turn-header');
    if (header && header.dataset.turnState === 'done-expanded') header.click();
    const lastAi = Array.from(chat.querySelectorAll('.row.ai')).pop();
    if (lastAi) lastAi.scrollIntoView({ block: 'end' });
  });
  await sleep(300);
  await page.screenshot({ path: join(SHOTS_DIR, 'nonroot-light.png') });
  const r6b = await page.evaluate(() => {
    const chat = document.getElementById('chat');
    const rootFaced = !!chat.querySelector('.bubble.ai[data-nf-output]');
    const nonRootRow = Array.from(chat.querySelectorAll('.bubble.ai'))
      .find((b) => b.textContent.includes('子会话过程叙述'));
    return {
      rootFaced,
      nonRootPlain: !!nonRootRow && !nonRootRow.dataset.nfOutput && !nonRootRow.dataset.nfDeferOutput,
      bothInDom: rootFaced && !!nonRootRow,
    };
  });
  ok(r6b.bothInDom === true, 'R6b 同屏对照：root 摘除面 + 非 root 全文行并存（截图 nonroot-light.png）');
  ok(r6b.nonRootPlain === true, 'R6b 非 root 行零 report 面标记');

  // ── R7 零错误铁律 ──
  ok(pageErrors.length === 0, `R7 页面零未捕获异常（${pageErrors.length}${pageErrors.length ? '：' + pageErrors[0] : ''}）`);
  ok(consoleErrors.length === 0, `R7 零 console 错误（${consoleErrors.length}${consoleErrors.length ? '：' + consoleErrors[0] : ''}）`);

  await page.context().close();
} catch (e) {
  // R1–R7 异常 ⇒ 收尾（浏览器/静态服务在 R8 段的 finally 里统一关，这里只兜底）。
  await browser.close().catch(() => {});
  webServer.close();
  throw e;
}

/* ─────────────────────────────────────────────────────────────────────────
   R8 两相渲染面 + 上滑分页腿（report-filter-gap 批，作者报障 2026-11-22 09:04
   「消息气泡退化为原 md report 块」实拍：DELEGATE 徽章下的气泡整段显示
   `<report> **…**`，标签裸露、markdown 未渲染）。

   根因（本批实测）：历史回放腿与上滑分页腿都是**两相**渲染 —— 先
   `el.textContent = 源文本`（persistence.js 的 deferMd），再经 rAF 升级批换成
   markdown/摘除面。手机上这一窗口可被拉长（后台标签 rAF 挂起 / 长历史跨数百帧
   / 分页批与手势抢帧），气泡就长时间停在「裸 <report> 标签 + 未渲染 markdown」
   的原报告块态 —— 作者截图正是这一相。第二处漏网：分页腿 deferMd 把第 4 参
   `opts` 就地丢掉 ⇒ 该腿 root 行连透明面都拿不到（裸标签以 HTML 元素落地、
   块内 markdown 被吞）。

   判据（R8）：
     R8a 单元负控：第一相收口是唯一开关 —— 不带 `{report:true}` 的调用方拿到
         **源文本原样**（含裸标签 = 改前第一相），带则不见标签；
     R8b 同步回放腿第一相（rAF 抑制 = 升级批未落地）：root 平衡块行的气泡文本
         不得含裸 `<report>`/`</report>`，也不得把它们当元素解析；
     R8c 分页挂载腿终相：root 平衡块行 = 摘除面（标签零暴露）；块内提到字面
         `<report>` 的零块行 = 透明面（markdown 照常渲染、标签是转义后的可见
         字面文本，**不是**被当 HTML 元素吞掉块内 markdown）；
     R8d 分页挂载腿在 rAF 抑制下的兜底路径同样零裸标签（该腿分帧推进带 120ms
         setTimeout 兜底；同步回放腿没有兜底 —— 这正是手机端窗口可长到被看见的
         原因，故第一相的可观测性由 R8b 承担）；
     R8e 两页零 console/pageerror。
   ───────────────────────────────────────────────────────────────────────── */
const R8_FULL = ['<report>', '**结论先行**：块内正文应当照常渲染。', '</report>', '', '框外叙述。'].join('\n');
const R8_ROWS = [
  { type: 'user', text: 'R8 分页腿对照输入', timestamp: Date.now() - 60000 },
  { type: 'ai', text: R8_FULL, terminal: true, durationMs: 4200, model: 'stub-r8', isRoot: true, timestamp: Date.now() - 50000 },
  { type: 'user', text: 'R8 零块对照输入', timestamp: Date.now() - 40000 },
  // 块内**提到**字面 <report> 的真实形态 ⇒ 平衡闸判嵌套 ⇒ 零块面（作者会话里
  // 真实存在的行；改前分页腿在此把标签当 HTML 元素、块内 markdown 全吞）。
  { type: 'ai', text: '<report>\n结论：**唯一用户可见面**是 <report> 语法本身。\n</report>', terminal: true, durationMs: 1200, model: 'stub-r8', isRoot: true, timestamp: Date.now() - 30000 },
];
/** 独立上下文：可抑制 rAF（模拟「升级批未落地」的手机现场）后注入 R8 画面。 */
async function bootR8(browser, suppressRaf, mode) {
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
    ws.send(JSON.stringify({ type: 'sessionList', sessions: [{ id: 'sess-r8', agentName: 'Nebula', title: 'R8', updatedAt: Date.now() }], activeId: 'sess-r8', folders: [] }));
  });
  await page.goto(BASE + '/index.html');
  await page.waitForSelector('#activity-bar', { timeout: 20000, state: 'attached' });
  await page.evaluate(() => {
    document.querySelectorAll('.onboarding-overlay').forEach((o) => o.remove());
    document.documentElement.classList.remove('ui-init');
  });
  // rAF 抑制 = 升级批永不落地（手机后台标签 / 长历史批未 drain 的现场）：
  // 之后注入的每一行都停在**第一相**纯文本 —— 本批要钉死的那一面。
  if (suppressRaf) await page.evaluate(() => { window.requestAnimationFrame = () => 0; });
  await sleep(300);
  if (mode === 'sync') {
    await page.evaluate((msgs) => {
      window.__r8Msgs = msgs;
    }, R8_ROWS);
    await page.evaluate(async () => {
      const { restoreFromBackendHistory } = await import('/js/persistence.js');
      restoreFromBackendHistory(window.__r8Msgs, { scrollToBottom: false });
    });
  } else {
    await page.evaluate((msgs) => {
      window.__r8Msgs = msgs;
    }, R8_ROWS);
    await page.evaluate(async () => {
      const { mountHistoryPageChunked } = await import('/js/persistence.js');
      await mountHistoryPageChunked(window.__r8Msgs, {}, {
        onBuilt: (container) => {
          const chat = document.getElementById('chat');
          chat.innerHTML = '';
          chat.appendChild(container);
          return true;
        },
      });
    });
  }
  await sleep(900);
  return { ctx, page, pageErrors, consoleErrors };
}

try {
  // R8a —— 单元负控：第一相收口是唯一开关（改前行为 = 源文本原样落地）。
  const { ctx: c8a, page: p8a, pageErrors: e8a, consoleErrors: ce8a } = await bootR8(browser, false, 'sync');
  const r8a = await p8a.evaluate(async (full) => {
    const mod = await import('/js/outputTag.js');
    // 负控腿（拿本 spec 打未修树）：模块里没有第一相收口 ⇒ 直接判红，不抛断链。
    if (typeof mod.outputFirstPaintText !== 'function') return { missing: true };
    const fn = mod.outputFirstPaintText;
    const raw = fn(full);
    const faced = fn(full, { report: true });
    return {
      rawHasTags: raw.includes('<report>') && raw.includes('</report>'),
      rawIsSource: raw === full,
      facedNoTags: !faced.includes('<report>') && !faced.includes('</report>'),
      facedHasInner: faced.includes('结论先行') && !faced.includes('框外叙述'),
      emptyOk: fn('', { report: true }) === '' && fn(null) === '',
    };
  }, R8_FULL);
  ok(r8a.missing !== true, 'R8a 第一相收口原语在场（outputFirstPaintText）');
  ok(r8a.rawIsSource === true, 'R8a 负控：不带 report 选项 ⇒ 第一相 = 源文本原样（含裸标签，= 改前行为）');
  ok(r8a.rawHasTags === true, 'R8a 负控：改前面文本确实带裸 <report> 标签');
  ok(r8a.facedNoTags === true, 'R8a 修复面：root 面第一相零标签');
  ok(r8a.facedHasInner === true, 'R8a 修复面：root 面第一相 = 块内正文（不带框外叙述）');
  ok(r8a.emptyOk === true, 'R8a 空值/null 不炸');
  await c8a.close();

  // R8b —— 同步回放腿**第一相**（rAF 抑制 = 升级批未落地）：不得含裸标签。
  const { ctx: c8b, page: p8b, pageErrors: e8b, consoleErrors: ce8b } = await bootR8(browser, true, 'sync');
  const r8b = await p8b.evaluate(() => {
    const b = Array.from(document.querySelectorAll('#chat .bubble.ai:not(.thinking-bubble)'))
      .find((x) => (x.textContent || '').includes('结论先行') || (x.textContent || '').includes('唯一用户可见面'));
    if (!b) return { found: false };
    return {
      found: true,
      text: b.textContent || '',
      showsTags: (b.textContent || '').includes('<report>') || (b.textContent || '').includes('</report>'),
      tagElement: !!b.querySelector('report'),
      defer: b.dataset.nfDeferOutput || '',
    };
  });
  ok(r8b.found === true, 'R8b 第一相气泡在场');
  ok(r8b.defer === '1', 'R8b 该行确为待升级的 root report 行（升级批未落地 = 手机现场）');
  ok(r8b.showsTags === false, `R8b 第一相零裸标签（气泡文本：${String(r8b.text).replace(/\s+/g, ' ').slice(0, 40)}）`);
  ok(r8b.tagElement === false, 'R8b 第一相无 <report> 元素残留');
  ok(e8b.length === 0 && ce8b.length === 0, `R8b 零异常（page=${e8b.length} console=${ce8b.length}）`);
  await c8b.close();

  // R8c —— 分页挂载腿终相：平衡块行摘除面；零块（嵌套/提及）行走透明面。
  const { ctx: c8c, page: p8c, pageErrors: e8c, consoleErrors: ce8c } = await bootR8(browser, false, 'mount');
  const r8c = await p8c.evaluate(() => {
    const pick = (needle) => Array.from(document.querySelectorAll('#chat .bubble.ai:not(.thinking-bubble)'))
      .find((x) => (x.textContent || '').includes(needle));
    const block = pick('结论先行');
    const mention = pick('唯一用户可见面');
    return {
      block: block ? {
        nfOutput: block.dataset.nfOutput || '',
        noTags: !(block.textContent || '').includes('<report>'),
        tagElement: !!block.querySelector('report'),
        bold: !!block.querySelector('strong'),
      } : null,
      mention: mention ? {
        tagElement: !!mention.querySelector('report'),
        bold: !!mention.querySelector('strong'),
        escapedTagText: (mention.textContent || '').includes('<report>'),
      } : null,
    };
  });
  ok(!!r8c.block, 'R8c 分页腿平衡块行在场');
  ok(r8c.block && r8c.block.nfOutput === '1', 'R8c 分页腿平衡块行走摘除面（data-nf-output）');
  ok(r8c.block && r8c.block.noTags === true, 'R8c 分页腿平衡块行零裸标签');
  ok(r8c.block && r8c.block.bold === true, 'R8c 分页腿平衡块行块内 markdown 照常渲染');
  ok(!!r8c.mention, 'R8c 分页腿零块（提及式）行在场');
  ok(r8c.mention && r8c.mention.tagElement === false,
    'R8c 分页腿零块行不再把 <report> 当 HTML 元素吞块内 markdown（改前 = 元素落地 + markdown 被吞）');
  ok(r8c.mention && r8c.mention.bold === true, 'R8c 分页腿零块行块内 markdown 渲染（透明面 opts 未被丢弃）');
  ok(e8c.length === 0 && ce8c.length === 0, `R8c 零异常（page=${e8c.length} console=${ce8c.length}）`);
  await c8c.close();

  // R8d —— 分页挂载腿在 rAF 抑制下的兜底：该腿的分帧推进带 120ms setTimeout
  // 兜底（nextMountFrame），后台标签 rAF 挂起时升级批仍会 drain ⇒ 断言「兜底路径
  // 走完也零裸标签」。第一相本身的可观测性由 R8b（同步回放腿，无兜底）承担。
  const { ctx: c8d, page: p8d, pageErrors: e8d, consoleErrors: ce8d } = await bootR8(browser, true, 'mount');
  const r8d = await p8d.evaluate(() => {
    const b = Array.from(document.querySelectorAll('#chat .bubble.ai:not(.thinking-bubble)'))
      .find((x) => (x.textContent || '').includes('结论先行'));
    if (!b) return { found: false };
    return {
      found: true,
      showsTags: (b.textContent || '').includes('<report>') || (b.textContent || '').includes('</report>'),
      tagElement: !!b.querySelector('report'),
      faced: b.dataset.nfOutput === '1',
    };
  });
  ok(r8d.found === true, 'R8d 分页腿（rAF 抑制）气泡在场');
  ok(r8d.showsTags === false, 'R8d 分页腿（rAF 抑制）零裸标签');
  ok(r8d.tagElement === false, 'R8d 分页腿（rAF 抑制）无 <report> 元素残留');
  ok(r8d.faced === true, 'R8d 分页腿（rAF 抑制）仍走到摘除面（nextMountFrame 120ms 兜底不饿死升级批）');
  ok(e8d.length === 0 && ce8d.length === 0, `R8d 零异常（page=${e8d.length} console=${ce8d.length}）`);
  await c8d.close();
} finally {
  await browser.close();
  webServer.close();
}

console.log(failed === 0 ? '\nALL PASS' : `\nFAILED: ${failed}`);
process.exit(failed === 0 ? 0 : 1);

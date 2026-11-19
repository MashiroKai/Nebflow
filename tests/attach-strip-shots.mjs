// attach-strip-shots.mjs — uifix 批 2026-10-22「附件条移到输入框上方独立行」验收证据。
//
// 作者令：「设计一批附件的ui优化。让附件放在对话框上面，不要遮挡文字。」
// 核心断言 = **文字零遮挡**：任意附件数量下，#attachment-preview 内全部 chip 的
// 几何盒都不得与 #input（textarea）相交（chip bottom ≤ input top + 1px 容差）。
// 场景 = 0 / 1 / 多附件（3 图 + 2 长名文件）× 长文字输入，视口 = 375 / 430 / 桌面 1280。
// 附带 = 发送清空动画腿（发送后 ~80ms 渐隐中间帧 + 落定后 strip 清空 +
// 占位符复位）与手机层「+N」折叠腿（375 多附件溢出）证据。
//
// 用法：node tests/attach-strip-shots.mjs
// 产物 = evidence/20261022_uifix-attach-strip/shots/*.png + 本进程 stdout 断言清单。
// 夹具形制同 tests/mobile-keybadge-shots.mjs（真 web 树 + 真 WS 桩，零伪造 state）。
import { chromium } from 'playwright';
import { readFileSync, mkdirSync } from 'node:fs';
import { join, dirname, extname, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const WEB = join(HERE, '..', 'src', 'main', 'resources', 'web');
const OUT = join(HERE, '..', 'evidence', '20261022_uifix-attach-strip', 'shots');
mkdirSync(OUT, { recursive: true });
const MIME = {
  '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css',
  '.svg': 'image/svg+xml', '.png': 'image/png', '.ico': 'image/x-icon',
  '.json': 'application/json', '.woff2': 'font/woff2', '.woff': 'font/woff',
};
const SID = 'uifix-attach-root';

let failures = 0;
const ok = (name, cond, extra = '') => {
  console.log(`${cond ? 'PASS' : 'FAIL'}  ${name}${extra ? '  — ' + extra : ''}`);
  if (!cond) failures++;
};

// 1×1 真 PNG（可解码；与 queue-image-refresh 夹具同源字面量）。
const B64_PNG = 'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==';
const LONG_TEXT = '这是一段用于遮挡判定的长文字：附件条必须完整位于输入区上方，任何附件数量下文字都不得被 chip 覆盖。' +
  'The quick brown fox jumps over the lazy dog 0123456789 —— 混排中英与标点，覆盖两行以上的换行形态。\n' +
  '第二行：继续填充到换行（textarea rows=1 起步，input 事件后自动增高），读数面取 textarea 的几何盒。';

const browser = await chromium.launch();

async function boot(width, height, touch) {
  const page = await (await browser.newContext({
    viewport: { width, height }, colorScheme: 'dark', deviceScaleFactor: 2,
    hasTouch: !!touch,
  })).newPage();
  await page.addInitScript(() => {
    localStorage.setItem('nebflow_token', 'e2e-token');
    localStorage.setItem('nebflow_locale', 'zh-CN');
    localStorage.setItem('stand_input_history', '[]');
  });
  await page.route('**/*', (route) => {
    let p = decodeURIComponent(new URL(route.request().url()).pathname);
    if (p === '/') p = '/index.html';
    if (p === '/api/sessions') {
      return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ sessions: [{ id: SID, name: 'root', agentName: 'Nebula' }], activeId: SID }) });
    }
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
      // 用户内容帧：受理回执静默（本证据链不需要引擎回帧）。
    });
  });
  await page.goto('http://localhost:1/');
  await page.waitForFunction((rootSid) => {
    const s = window.__stateProbe || null;
    return true; // 等待面见下方 waitForFunction（模块态）
  }, SID).catch(() => {});
  await page.waitForSelector('#input', { state: 'visible', timeout: 20000 });
  await page.waitForFunction(async (rootSid) => {
    const s = (await import('/js/state.js')).default;
    return s.activeSessionId === rootSid && s.ws && s.ws.readyState === 1;
  }, SID, { timeout: 15000 });
  await page.waitForTimeout(300);
  return page;
}

/** 注入附件（与 addFileAttachment 同数据形状）并走真实渲染单点。 */
const injectAtts = (page, atts) => page.evaluate(async (list) => {
  const cv = await import('/js/chatView.js');
  const chat = await import('/js/chat.js');
  const view = cv.activeView;
  view.pendingAttachments = list.map((a) => ({ ...a }));
  chat.renderAttachmentPreview({ attPreviewEl: view.dom.attPreview, attachments: view.pendingAttachments });
  await new Promise((r) => requestAnimationFrame(() => requestAnimationFrame(r)));
}, atts);

const setLongText = (page) => page.evaluate((text) => {
  const input = document.getElementById('input');
  input.value = text;
  input.dispatchEvent(new Event('input', { bubbles: true }));
}, LONG_TEXT);

/** 核心读数：chip 全集几何 vs textarea 几何（零遮挡判定面）。
 *  注：chip 集不含 .mh-att-more（+N 折叠键是行尾操作键，非附件件）；「横向溢出」
 *  读 chip 实际盒右缘（scrollWidth 会被 ✕ 圆盘 30px 命中域 ::after 的悬出污染
 *  —— 命中域不可见也不裁切，不算视觉溢出）。 */
const readGeometry = (page) => page.evaluate(() => {
  const strip = document.getElementById('attachment-preview');
  const input = document.getElementById('input');
  const ir = input.getBoundingClientRect();
  const sr = strip.getBoundingClientRect();
  const kids = Array.from(strip.querySelectorAll(':scope > *'))
    .filter((el) => !el.classList.contains('mh-att-more'));
  const chips = kids.map((el) => {
    const r = el.getBoundingClientRect();
    return { cls: el.className, top: r.top, bottom: r.bottom, left: r.left, right: r.right, h: r.height };
  });
  return {
    chipCount: chips.length,
    chips,
    strip: { top: sr.top, bottom: sr.bottom, width: sr.width, height: sr.height, visualOverflow: chips.length ? Math.max(...chips.map((c) => c.right)) - sr.left - strip.clientWidth : 0 },
    input: { top: ir.top, bottom: ir.bottom, value0: input.value.length > 0 },
    overlapCount: chips.filter((c) => c.bottom > ir.top + 1).length,
    moreBtn: document.querySelector('#attachment-preview .mh-att-more')?.textContent || '',
  };
});

const IMG = { type: 'image', mimeType: 'image/png', data: B64_PNG, name: `shot-img.png`, preview: 'data:image/png;base64,' + B64_PNG };
const FILE = (n) => ({ type: 'text', mimeType: 'text/plain', name: n, data: '', hash: '', size: 10 });

async function scenario(width, height, tag, touch) {
  const page = await boot(width, height, touch);

  // ── 0 附件 + 长文字 ──
  await setLongText(page);
  await page.waitForTimeout(150);
  let g = await readGeometry(page);
  ok(`[${tag}] 0附件 · strip 为空、长文字在盒`, g.chipCount === 0 && g.input.value0, `strip=${JSON.stringify(g.strip)}`);
  await page.screenshot({ path: join(OUT, `strip-${tag}-${width}-att0.png`) });

  // ── 1 图附件 + 长文字 ──
  await injectAtts(page, [{ ... IMG, name: 'paste-screenshot.png' }]);
  g = await readGeometry(page);
  ok(`[${tag}] 1附件 · 零遮挡`, g.chipCount === 1 && g.overlapCount === 0, JSON.stringify({ chips: g.chipCount, overlap: g.overlapCount, stripBottom: g.strip.bottom, inputTop: g.input.top }));
  await page.screenshot({ path: join(OUT, `strip-${tag}-${width}-att1.png`) });

  // ── 多附件（3 图 + 2 长名文件）+ 长文字 ──
  await injectAtts(page, [
    { ...IMG, name: 'a-01.png' }, { ...IMG, name: 'b-02.png' }, { ...IMG, name: 'c-03.png' },
    FILE('a-very-long-filename-xxxxxxxxxxxx.pdf'),
    FILE('design-review-notes-2026Q4-batch.md'),
  ]);
  g = await readGeometry(page);
  ok(`[${tag}] 多附件 · 零遮挡（核心断言）`, g.chipCount === 5 && g.overlapCount === 0,
    JSON.stringify({ chips: g.chipCount, overlap: g.overlapCount, strip: g.strip, inputTop: g.input.top, more: g.moreBtn }));
  ok(`[${tag}] 多附件 · 条带无视觉横向溢出`, g.strip.visualOverflow <= 1, `visualOverflow=${g.strip.visualOverflow}`);
  await page.screenshot({ path: join(OUT, `strip-${tag}-${width}-att5.png`) });
  const bar = await page.locator('#input-area').boundingBox();
  if (bar) {
    await page.screenshot({
      path: join(OUT, `strip-${tag}-${width}-att5-composer.png`),
      clip: { x: 0, y: Math.max(0, bar.y - 8), width, height: Math.min(height, bar.height + 16) },
    });
  }

  // ── 发送清空动画腿 ──
  await page.click('#send-btn');
  await page.waitForTimeout(80); // 渐隐中间帧（~160ms 动画中段）
  const mid = await page.evaluate(() => {
    const strip = document.getElementById('attachment-preview');
    const first = strip.querySelector(':scope > *');
    if (!first) return { kids: 0, op: null };
    const anims = first.getAnimations ? first.getAnimations() : [];
    return { kids: strip.childElementCount, op: anims.length ? getComputedStyle(first).opacity : null, clearing: strip.dataset.attClearing === '1' };
  });
  ok(`[${tag}] 发送 · 清空动画在播（中间帧）`, mid.kids > 0 && (mid.clearing === true || (mid.op !== null && Number(mid.op) < 1)), JSON.stringify(mid));
  await page.screenshot({ path: join(OUT, `strip-${tag}-${width}-clearing.png`) });
  await page.waitForTimeout(500);
  const fin = await page.evaluate(() => {
    const strip = document.getElementById('attachment-preview');
    return { kids: strip.childElementCount, ph: document.getElementById('input').placeholder, val: document.getElementById('input').value };
  });
  ok(`[${tag}] 发送 · strip 落定清空 + 输入复位`, fin.kids === 0 && fin.val === '' && fin.ph.length > 0, JSON.stringify(fin));
  await page.context().close();
}

// ── 手机两视口（触屏形制）+ 桌面对照 ──
await scenario(375, 812, 'mobile', true);
await scenario(430, 932, 'mobile', true);
await scenario(1280, 800, 'desktop', false);

console.log(failures === 0 ? '\nALL PASS' : `\n${failures} FAIL`);
process.exit(failures === 0 ? 0 : 1);

// capture-quote-multiselect.mjs — 多选引用批（2026-11-22）的**真实渲染**证据采集器。
//
// 口径（作者令：只收真实渲染像素，不收台架效果图）：
//   · 视口 = iPhone Safari 的 CSS 像素档 390×844 + hasTouch + body.mobile-layer
//     （产品真机姿态类；手机层样式同域 ⇒ 量到的是真机面，不是桌面缩放）。
//   · 驱动**真实模块**（chat.js / msgQuote.js / reference.js），入口 = 行 footer 引用
//     钮（手机层唯一入口，与真机同一条点击路径），零 stub、零打包。
//   · 同一份采集脚本跑两棵树：
//       before = feat/base tip 941de3b0a（改前）
//       after  = feat/quote-multiselect（改后）
//     读数与截图逐格对照，输出 JSON（逐格读数）+ PNG（帧）。
//
// 用法：
//   node tests/capture-quote-multiselect.mjs <repo-root> <before|after>
//
// 产出（绝对路径见最后打印的 evidence 清单）：
//   <assets>/390x844-<tag>-01-single.png    单选态（composer 条带 + 输入框）
//   <assets>/390x844-<tag>-02-multi.png     多选态（连引三条：改前只能留一条）
//   <assets>/390x844-<tag>-03-sent.png      发送后消息面（引用块渲染）
//   <assets>/390x844-<tag>-readings.json    逐格读数

import { spawn } from 'node:child_process';
import fs from 'node:fs';
import net from 'node:net';
import path from 'node:path';
import { chromium } from '@playwright/test';

const ROOT = path.resolve(process.argv[2] || '.');
const TAG = process.argv[3] || 'after';
const HARNESS = '/tests/fixtures/quote-message/harness.html';
const OUT = '/Users/kaiyu/.stand/projects/stand/.stand/docs/assets/20261122_quote-multiselect';

function freePort() {
  return new Promise((resolve, reject) => {
    const srv = net.createServer();
    srv.listen(0, '127.0.0.1', () => {
      const p = srv.address().port;
      srv.close(() => ([6138, 8080, 8097].includes(p) ? freePort().then(resolve, reject) : resolve(p)));
    });
    srv.on('error', reject);
  });
}

const serverScript = `import functools, http.server, sys, threading\nhandler = functools.partial(http.server.SimpleHTTPRequestHandler, directory=sys.argv[2])\nserver = http.server.ThreadingHTTPServer(('127.0.0.1', int(sys.argv[1])), handler)\nthread = threading.Thread(target=server.serve_forever)\nthread.start()\nprint('ready', flush=True)\nsys.stdin.readline()\nserver.shutdown()\nthread.join()\nserver.server_close()`;

/** 手机层唯一入口 = 行 footer 引用钮（长按/右键菜单在 body.mobile-layer 下不出）。 */
const clickQuote = async (page, rowId) => page.evaluate(async (id) => {
  await new Promise(r => requestAnimationFrame(() => requestAnimationFrame(r)));
  const row = document.querySelector(`#chat .row[data-msg-id="${CSS.escape(String(id))}"]`);
  if (!row) return { ok: false, why: 'no row' };
  const btn = row.querySelector('.duration-badge .msg-quote-btn');
  if (!btn) return { ok: false, why: 'no quote button' };
  btn.click();
  await new Promise(r => setTimeout(r, 60));
  return { ok: true };
}, rowId);

/** composer 条带读数（两种树都读同一组 DOM 面，逐格可比）。 */
const stripReadings = (page) => page.evaluate(() => {
  const host = document.getElementById('attachment-preview');
  const input = document.getElementById('input');
  const chips = host ? Array.from(host.querySelectorAll('[data-ref-type="chat-message"]')) : [];
  const rect = (el) => {
    if (!el) return null;
    const b = el.getBoundingClientRect();
    return { top: Math.round(b.top), bottom: Math.round(b.bottom), left: Math.round(b.left), right: Math.round(b.right), w: Math.round(b.width), h: Math.round(b.height) };
  };
  const ir = rect(input);
  const hr = rect(host);
  return {
    viewport: { w: window.innerWidth, h: window.innerHeight },
    mobileLayer: document.body.classList.contains('mobile-layer'),
    chipCount: chips.length,
    compactCount: chips.filter(c => c.classList.contains('is-compact')).length,
    chips: chips.map(c => {
      const r = c.getBoundingClientRect();
      const label = c.querySelector('.att-ref-label');
      return {
        height: Math.round(r.height),
        width: Math.round(r.width),
        text: (c.textContent || '').trim(),
        labelText: label ? label.textContent : '',
        labelTextOverflow: label ? getComputedStyle(label).textOverflow : '',
        labelClipped: label ? label.scrollWidth > label.clientWidth + 1 : false,
        aboveInputTop: !!(ir && r.bottom <= ir.top + 1),
        inHostBand: !!(hr && r.top >= hr.top - 1 && r.bottom <= hr.bottom + 1),
        hasOpenBtn: !!c.querySelector('.att-ref-open'),
        hasRemove: !!c.querySelector('.att-ref-remove'),
        bg: getComputedStyle(c).backgroundColor,
      };
    }),
    host: hr,
    input: ir,
    hostOverflowX: !!(host && host.scrollWidth > host.clientWidth + 1),
    hostBottomVsInputTop: (hr && ir) ? hr.bottom - ir.top : null,
    inputVisible: !!(ir && ir.h > 0 && ir.bottom <= window.innerHeight),
  };
});

/** 发送后消息面读数（引用块逐块：摘要/发送方/跳转态/是否同一条消息）。 */
const sentReadings = (page, rowId) => page.evaluate((id) => {
  const row = document.querySelector(`#chat .row[data-msg-id="${CSS.escape(String(id))}"]`);
  if (!row) return null;
  const blocks = Array.from(row.querySelectorAll('.msg-quote-block')).map((box, i) => ({
    order: i + 1,
    refMessageId: box.dataset.refMessageId || '',
    text: (box.querySelector('.msg-quote-text') || {}).textContent || '',
    sender: (box.querySelector('.msg-quote-sender') || {}).textContent || '',
    jump: box.dataset.quoteJump || '',
    note: (box.querySelector('.msg-quote-note') || {}).textContent || '',
    inComposite: !!box.closest('.msg-quote-composite'),
  }));
  const bubble = row.querySelector('.bubble.user:not(.att-bubble)');
  return {
    blockCount: blocks.length,
    blocks,
    bubbleText: bubble ? bubble.textContent : '',
    rowHeight: Math.round(row.getBoundingClientRect().height),
  };
}, rowId);

const EVID = [];
async function shot(page, selector, name) {
  const file = path.join(OUT, name);
  const loc = selector ? page.locator(selector) : page;
  await loc.screenshot({ path: file, animations: 'disabled' });
  EVID.push(file);
  return file;
}

fs.mkdirSync(OUT, { recursive: true });
const port = await freePort();
const server = spawn('python3', ['-u', '-c', serverScript, String(port), ROOT], { stdio: ['pipe', 'pipe', 'inherit'] });
await new Promise((resolve, reject) => {
  server.stdout.once('data', resolve);
  server.once('error', reject);
  server.once('exit', code => reject(new Error(`server exited: ${code}`)));
});

const browser = await chromium.launch();
const context = await browser.newContext({
  viewport: { width: 390, height: 844 },
  hasTouch: true,
  deviceScaleFactor: 2,
  reducedMotion: 'reduce',
  colorScheme: 'dark',
  locale: 'zh-CN',
});
const page = await context.newPage();
const pageErrors = [];
const consoleErrors = [];
page.on('pageerror', e => pageErrors.push(String(e && e.message ? e.message : e)));
page.on('console', m => { if (m.type() === 'error') consoleErrors.push(m.text()); });
await page.goto(`http://127.0.0.1:${port}${HARNESS}`);
await page.waitForFunction(() => window.__ready === true);
await page.evaluate(() => { document.body.classList.add('mobile-layer'); });
// 夹具几何归一（两棵树同口径）：harness 的 #input-bar/#chat 是 680px 固定宽夹具，
// 改前那版没有 max-width ⇒ 390 视口会横向溢出。这里对 before/after 双方注入同一条
// 覆盖，保证几何读数只反映产品 CSS/JS 的差异，不掺夹具差异。
await page.addStyleTag({ content: '#chat, #input-bar { width: 680px; max-width: 96vw; }' });

// 三条被引目标（作者自己的既往消息 —— 手机层自引可达性同批取证）
const ids = await page.evaluate(() => [
  window.__renderUserRow('我先说：把 A 和 B 两个目录都查一遍'),
  window.__renderUserRow('再补一句：C 目录也要看'),
  window.__renderUserRow('最后：结果按文件列出来'),
]);

const reading = { tag: TAG, root: ROOT, viewport: '390x844', startedAt: new Date().toISOString(), steps: {} };

// ① 单选态：引第一条 ⇒ 整卡预览（改前/改后同形）
reading.steps.single_afterClick = await clickQuote(page, ids[0]);
reading.steps.single = await stripReadings(page);
await page.waitForTimeout(120);
await shot(page, '#input-bar', `390x844-${TAG}-01-single.png`);

// ② 多选态：再引第二、第三条（改前 = 替换语义 ⇒ 恒 1 枚；改后 = 追加 ⇒ 3 枚紧凑档）
reading.steps.multi_add2 = await clickQuote(page, ids[1]);
reading.steps.multi_add3 = await clickQuote(page, ids[2]);
await page.waitForTimeout(120);
reading.steps.multi = await stripReadings(page);
await shot(page, '#input-bar', `390x844-${TAG}-02-multi.png`);

// ③ 发送后消息面：带当前引用态渲一条消息（真 renderUserBubble 的渲染半边）
const sentId = await page.evaluate(() => {
  const row = window.__renderUserRowWithQuote
    ? window.__renderUserRowWithQuote('收到，我按这个顺序处理', 'cm-evidence-1')
    : null;
  return row && row.dataset ? row.dataset.msgId : null;
});
await page.waitForTimeout(160);
reading.steps.sent = sentId ? await sentReadings(page, sentId) : null;
await shot(page, null, `390x844-${TAG}-03-sent.png`);

reading.pageErrors = pageErrors;
reading.consoleErrors = consoleErrors;
const readingsFile = path.join(OUT, `390x844-${TAG}-readings.json`);
fs.writeFileSync(readingsFile, JSON.stringify(reading, null, 2));
EVID.push(readingsFile);

await browser.close();
const exited = new Promise(resolve => server.once('exit', resolve));
server.stdin.end('quit\n');
await exited;

console.log(JSON.stringify({
  tag: TAG,
  chipCount: reading.steps.multi.chipCount,
  compactCount: reading.steps.multi.compactCount,
  singleChipHeight: reading.steps.single.chips[0] ? reading.steps.single.chips[0].height : null,
  hostBottomVsInputTop: reading.steps.multi.hostBottomVsInputTop,
  hostOverflowX: reading.steps.multi.hostOverflowX,
  sentBlockCount: reading.steps.sent ? reading.steps.sent.blockCount : null,
  pageErrors: pageErrors.length,
}, null, 2));
console.log('evidence:\n' + EVID.join('\n'));

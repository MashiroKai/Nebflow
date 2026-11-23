#!/usr/bin/env node
// ask-time-divider-shots.mjs — canvas Ask 时间分隔线 真 Chromium 证据（前后对照）。
//
// 做法：起一个静态服务挂 SHOTS_WEB（缺省本仓 web 根），在其根下提供 harness 页
// （/__ask-time-divider.html，链接同 index.html 的全部 css），harness 直接 import
// js/askDialog.js 的 createAskDialog —— 与 canvas ask 面板同一渲染腿。
// 用 page.clock 冻结「现在」= 2026-11-22 10:00（本地时间），使「今天/昨天/跨年」标签可复现。
//
// 场景（mock 时间戳）：
//   multi   多段会话：跨年 / 本年他日 / 昨天 / 今天 四种标签 + 段内 4 分钟不断段
//   legacy  无时间戳的旧历史行 ⇒ 零分隔线（零回归对照）
//
// 产出（落 $ASK_DIVIDER_EVIDENCE_DIR，缺省 本仓/.stand/tmp/ask-time-divider/evidence）：
//   <MODE>-<scenario>-desktop.png   整页截图
//   readings.json                   每个场景的分隔线数量与标签读数（断言用）
//
// Run:
//   SHOTS_WEB=<web 根> ASK_DIVIDER_MODE=after|before node tests/ask-time-divider-shots.mjs
//   缺省 MODE=after、SHOTS_WEB=本仓 web。MODE=before 只取证不断言（base 树无分隔线）。
import { chromium } from 'playwright';
import { mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { createServer } from 'node:http';
import { dirname, join, normalize, resolve, extname } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const WEB = process.env.SHOTS_WEB || resolve(HERE, '../src/main/resources/web');
const MODE = process.env.ASK_DIVIDER_MODE || 'after';
const OUT = process.env.ASK_DIVIDER_EVIDENCE_DIR
  || resolve(HERE, '../../../../.stand/tmp/ask-time-divider/evidence');
mkdirSync(OUT, { recursive: true });

const HARNESS = '/__ask-time-divider.html';
const indexHtml = readFileSync(join(WEB, 'index.html'), 'utf8');
const cssLinks = [...indexHtml.matchAll(/<link rel="stylesheet" href="(css\/[^"]+)">/g)]
  .map((m) => `<link rel="stylesheet" href="${m[1]}">`).join('\n  ');

const HARNESS_HTML = `<!doctype html><html><head><meta charset="utf-8">
  ${cssLinks}
  <style>body{margin:0;background:#f5f5f7;font-family:system-ui,sans-serif}
  #host{position:fixed;left:24px;top:24px;width:440px;height:640px}
  #host .ask-dock{position:relative;width:100%;height:100%;}</style>
</head><body><div id="host"></div>
<script type="module">
  import { createAskDialog } from './js/askDialog.js';
  window.__mount = (msgs) => {
    const S = { sid: 'ask-harness', msgs, busy: false, replyState: null };
    const dlg = createAskDialog({
      tt: (zh) => zh,
      placeholder: '提问',
      activeSession: () => S,
      canSubmit: () => true,
      ensureSession: async () => S,
      ensureBound: () => {},
      send: () => {},
      frameKind: () => null,
      onPersist: () => {},
    });
    dlg.wire();
    document.getElementById('host').appendChild(dlg.panel);
    dlg.openDom();
    dlg.render();
    return dlg.col.querySelectorAll('.ask-time-divider').length;
  };
  window.__ready = true;
</script></body></html>`;

const server = createServer((req, res) => {
  const url = new URL(req.url, 'http://x');
  if (url.pathname === HARNESS) {
    res.writeHead(200, { 'content-type': 'text/html; charset=utf-8' });
    res.end(HARNESS_HTML);
    return;
  }
  const rel = normalize(decodeURIComponent(url.pathname)).replace(/^([/\\])+/, '');
  const abs = join(WEB, rel);
  if (!abs.startsWith(WEB)) { res.writeHead(403); res.end(); return; }
  try {
    const body = readFileSync(abs);
    const type = { '.js': 'text/javascript', '.css': 'text/css', '.html': 'text/html', '.json': 'application/json', '.svg': 'image/svg+xml' }[extname(abs)] || 'application/octet-stream';
    res.writeHead(200, { 'content-type': type });
    res.end(body);
  } catch { res.writeHead(404); res.end(); }
});
await new Promise((r) => server.listen(0, '127.0.0.1', r));
const BASE = `http://127.0.0.1:${server.address().port}`;

// 冻结「现在」：2026-11-22 10:00 本地时间。
const NOW = new Date(2026, 10, 22, 10, 0, 0);
const at = (y, mo, d, h, mi) => new Date(y, mo, d, h, mi, 0).getTime();

const SCENARIOS = {
  multi: {
    msgs: [
      { role: 'user', text: '（跨年旧会话）这份合同的违约条款怎么看？', ts: at(2025, 11, 31, 16, 0) },
      { role: 'ai', text: '违约金按日计，超过三十日可解约。', ts: at(2025, 11, 31, 16, 1) },
      { role: 'user', text: '本年他日：再问一下解约的前置条件。', ts: at(2026, 10, 19, 22, 35) },
      { role: 'ai', text: '需先书面催告，宽限期届满仍未履行即可。', ts: at(2026, 10, 19, 22, 36) },
      { role: 'user', text: '同一段内 4 分钟后的追问（不应断段）。', ts: at(2026, 10, 19, 22, 40) },
      { role: 'user', text: '昨天 23:20 的新一段。', ts: at(2026, 10, 21, 23, 20) },
      { role: 'ai', text: '好的，已记录。', ts: at(2026, 10, 21, 23, 21) },
      { role: 'user', text: '今天 09:02 再问。', ts: at(2026, 10, 22, 9, 2) },
    ],
    // 期望：段界 = 行0(跨年)、行2(11-19)、行5(昨天)、行7(今天)；行4 不断段。
    expect: ['2025-12-31 16:00', '11-19 22:35', '昨天 23:20', '09:02'],
  },
  legacy: {
    msgs: [
      { role: 'user', text: '旧历史行：无时间戳。' },
      { role: 'ai', text: '旧回答：无时间戳。' },
    ],
    expect: [],
  },
};

const browser = await chromium.launch();
const readings = { mode: MODE, web: WEB, generatedAt: new Date().toISOString(), scenarios: {} };
let failed = 0;
try {
  for (const [name, sc] of Object.entries(SCENARIOS)) {
    const page = await browser.newPage({ viewport: { width: 1280, height: 800 } });
    const errors = [];
    page.on('pageerror', (e) => errors.push(String(e && e.message)));
    await page.clock.setFixedTime(NOW);
    await page.goto(BASE + HARNESS, { waitUntil: 'load' });
    await page.waitForFunction(() => window.__ready === true, null, { timeout: 15000 });
    await page.evaluate((msgs) => window.__mount(msgs), sc.msgs);
    const labels = await page.$$eval('.ask-time-divider .ask-time-divider-label', (els) => els.map((e) => e.textContent));
    const shot = join(OUT, `${MODE}-${name}-desktop.png`);
    await page.screenshot({ path: shot, fullPage: false });
    const ok = JSON.stringify(labels) === JSON.stringify(sc.expect);
    readings.scenarios[name] = { labels, expect: sc.expect, pass: ok, pageErrors: errors, screenshot: shot };
    console.log(`[${MODE}/${name}] labels=${JSON.stringify(labels)} expect=${JSON.stringify(sc.expect)} ${ok ? 'OK' : 'MISMATCH'} errors=${errors.length}`);
    if (MODE === 'after' && (!ok || errors.length)) failed++;
    await page.close();
  }
} finally {
  await browser.close();
  server.close();
}
writeFileSync(join(OUT, `readings-${MODE}.json`), JSON.stringify(readings, null, 2) + '\n');
if (failed) {
  console.error(`ask-time-divider-shots: ${failed} scenario(s) failed`);
  process.exit(1);
}
console.log(`ask-time-divider-shots: ${MODE} done → ${OUT}`);

#!/usr/bin/env node
// _bench-mobile-diff.mjs — 共享差异组件（js/diffView）D9 性能读数取证脚本
// （非验收闸；tests/_ 前缀 = 不进 playwright 收集面）。
//
// 同机、同上下文（375×812、触摸、DSF3）下，对多档行数实测「转换 + 挂载」耗时、
// 挂载后两连 rAF 帧间隔、文档行数 vs 已挂 DOM 行数（虚拟化）。输出 JSON 到
// NF_BENCH_OUT（缺省 stdout）。
//
// 用法：node tests/_bench-mobile-diff.mjs [--out <file>]

import { chromium } from 'playwright';
import { existsSync, readFileSync, writeFileSync } from 'node:fs';
import { createServer } from 'node:http';
import { dirname, extname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const REPO = resolve(HERE, '..');
const WEB_DIR = process.env.WEB_DIR || resolve(REPO, 'src/main/resources/web');
const HARNESS_SRC = resolve(HERE, 'fixtures/mobile-diff-baseline/harness.html');
const PORT = Number(process.env.PORT || 8313);
const BASE = `http://127.0.0.1:${PORT}`;
const MIME = { '.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8', '.css': 'text/css; charset=utf-8', '.json': 'application/json', '.svg': 'image/svg+xml', '.png': 'image/png' };

const server = createServer((req, res) => {
  const url = new URL(req.url, BASE);
  const p = decodeURIComponent(url.pathname);
  if (p === '/__harness.html') { res.writeHead(200, { 'Content-Type': MIME['.html'] }); res.end(readFileSync(HARNESS_SRC)); return; }
  const file = join(WEB_DIR, p);
  if (!file.startsWith(WEB_DIR) || !existsSync(file)) { res.writeHead(404); res.end('nf'); return; }
  res.writeHead(200, { 'Content-Type': MIME[extname(file)] || 'application/octet-stream' });
  res.end(readFileSync(file));
});
await new Promise((r) => server.listen(PORT, '127.0.0.1', r));

const outArg = process.argv.indexOf('--out');
const outFile = outArg >= 0 ? process.argv[outArg + 1] : process.env.NF_BENCH_OUT;

const browser = await chromium.launch();
const results = { context: { viewport: '375×812', dsf: 3, touch: true }, runs: [] };
try {
  const ctx = await browser.newContext({ viewport: { width: 375, height: 812 }, deviceScaleFactor: 3, isMobile: true, hasTouch: true, colorScheme: 'light' });
  const page = await ctx.newPage();
  await page.goto(BASE + '/__harness.html');
  await page.waitForFunction('window.__ready === true', null, { timeout: 20000 });
  // 预热：让 vendor 包与模块 JIT 就位（读数面 = 组件自身，不是首次编译）。
  await page.evaluate(() => window.__benchDelegate(200));
  for (const lines of [200, 651, 1380, 10000, 120000]) {
    const r = await page.evaluate((n) => window.__benchDelegate(n), lines);
    results.runs.push(r);
    console.log(JSON.stringify(r));
  }
} finally {
  await browser.close();
  server.close();
}
const json = JSON.stringify(results, null, 2);
if (outFile) { writeFileSync(outFile, json); console.log('wrote ' + outFile); } else { console.log(json); }

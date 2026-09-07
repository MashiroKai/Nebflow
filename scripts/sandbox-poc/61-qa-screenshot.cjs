#!/usr/bin/env node
// 61-qa-screenshot.cjs — PoC-d 宿主侧 playwright 截图（A7 先行形态：浏览器在宿主，打容器发布端口）
// usage: node 61-qa-screenshot.cjs [baseUrl] [outDir]
// 断言：HTTP 200 + 非空 title + 非空 body 渲染；产出 d-qa-root.png / d-qa-root-1280.png
// 注：playwright 解析沿目录树上溯至主仓 node_modules（worktree 嵌套于主仓 .nebflow/worktrees/ 下）。
const { chromium } = require('playwright');
const fs = require('node:fs');
const path = require('node:path');

const base = process.argv[2] || 'http://127.0.0.1:8098';
const outDir = process.argv[3] || __dirname;
fs.mkdirSync(outDir, { recursive: true });

(async () => {
  const browser = await chromium.launch({ headless: true });
  const page = await browser.newPage({ viewport: { width: 1440, height: 900 } });
  const resp = await page.goto(base + '/', { waitUntil: 'load', timeout: 30000 });
  await page.waitForTimeout(2500); // SPA boot + WS 连接
  const status = resp ? resp.status() : 0;
  const title = await page.title();
  const bodyLen = (await page.evaluate(() => document.body.innerText)).length;
  await page.screenshot({ path: path.join(outDir, 'd-qa-root.png') });
  await page.setViewportSize({ width: 1280, height: 800 });
  await page.waitForTimeout(300);
  await page.screenshot({ path: path.join(outDir, 'd-qa-root-1280.png') });
  await browser.close();

  console.log(JSON.stringify({ url: base, status, title, bodyLen }));
  if (status !== 200) { console.error('FAIL: http ' + status); process.exit(1); }
  if (!title) { console.error('FAIL: empty title'); process.exit(1); }
  if (bodyLen < 10) { console.error('FAIL: empty body'); process.exit(1); }
  console.log('PASS');
})().catch(e => { console.error('FAIL: ' + e.message); process.exit(1); });

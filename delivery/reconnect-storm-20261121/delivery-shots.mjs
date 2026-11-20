// delivery-shots.mjs — 交付件截图（重启重连风暴修复批 2026-11-21）。
//
// 同一台隔离引擎（--home 临时数据根 + 端口 8177，数据 = 生产盘只读复制）上，让浏览器
// 分别加载 **base commit 字节** 与 **本支字节** 的 delegateChainProvider.js（Playwright
// route 拦截只替换这一个模块；其余前端字节两侧按分支状态相同，本批不触碰），各跑
// 「控制台首屏」与「重连（带会话再开一次）」两跳并记录 /api 请求数，然后截图。
//
// 三条自检，避免举证工具自己成为不确定项：
//   · 每跳用**独立浏览器上下文**（HTTP 缓存在上下文内，否则第二跳会吃已缓存的模块
//     字节，被拦截的只有第一跳 ⇒ 那一腿实际跑的是另一份字节）；
//   · 拦截计数必须等于跳数，否则拒绝出读数；
//   · 首跳用**新铸的一次性控制台票据**换会话，第二跳复制 cookie 走会话（真机重连形态）。
//
// 图上只有一条字幕条是举证工具加的（annotation，不是产品 UI），内容就是本轮实测的
// 请求数——即本批要修的可见差异。
//
// 用法：node delivery-shots.mjs <baseUrl> <fileToken> <baseProvider> <branchProvider> <outDir>
import { chromium } from 'playwright';
import { execFileSync } from 'node:child_process';
import { readFileSync, writeFileSync } from 'node:fs';

const [baseUrl, token, baseProvider, branchProvider, outDir, viewportArg] = process.argv.slice(2);
const [VW, VH] = (viewportArg || '390x844').split('x').map(Number);
const BODY = {
  base: readFileSync(baseProvider, 'utf8'),
  branch: readFileSync(branchProvider, 'utf8'),
};

const mint = () => JSON.parse(execFileSync('curl', [
  '-s', '-m', '10', '-X', 'POST', '-H', `Authorization: Bearer ${token}`,
  `${baseUrl}/api/auth/console-ticket`,
], { encoding: 'utf8' })).ticket;

const tally = (list) => {
  const by = {};
  for (const url of list) {
    const p = new URL(url).pathname;
    const k = p.startsWith('/api/chains/') ? '/api/chains/<key>' : p;
    by[k] = (by[k] || 0) + 1;
  }
  return by;
};
const summary = (t) => ({
  rows: t['/api/delegates'] || 0,
  digests: t['/api/chain-digests'] || 0,
  detail: t['/api/chains/<key>'] || 0,
  children: Object.entries(t).filter(([k]) => k.endsWith('/children')).reduce((n, [, v]) => n + v, 0),
  total: Object.values(t).reduce((n, v) => n + v, 0),
});

/** 一跳：独立上下文 + 关缓存 + 只替换 provider 模块。返回 {summary, interceptions}。 */
const hop = async (browser, variant, url, storageState) => {
  const context = await browser.newContext({
    viewport: { width: VW, height: VH }, deviceScaleFactor: 2, isMobile: true, hasTouch: true, storageState,
  });
  const page = await context.newPage();
  const cdp = await context.newCDPSession(page);
  await cdp.send('Network.setCacheDisabled', { cacheDisabled: true });
  let interceptions = 0;
  await page.route(/\/js\/delegateChainProvider\.js/, async (route) => {
    interceptions++;
    await route.fulfill({
      status: 200,
      contentType: 'text/javascript; charset=utf-8',
      headers: { 'cache-control': 'no-store' },
      body: BODY[variant],
    });
  });
  const requests = [];
  page.on('request', (r) => { if (r.url().includes('/api/')) requests.push(r.url()); });
  await page.goto(url, { waitUntil: 'domcontentloaded' });
  await page.waitForTimeout(15000);
  const result = { summary: summary(tally(requests)), interceptions, page, context };
  return result;
};

const annotate = async (page, text, shotPath) => {
  await page.evaluate((t) => {
    const bar = document.createElement('div');
    bar.textContent = t;
    bar.setAttribute('style', [
      'position:fixed', 'left:0', 'right:0', 'bottom:0', 'z-index:2147483647',
      'background:rgba(17,17,17,.88)', 'color:#fff', 'font:13px/1.5 -apple-system,sans-serif',
      'padding:8px 14px', 'border-top:1px dashed #888', 'white-space:pre-wrap',
    ].join(';'));
    document.body.appendChild(bar);
  }, text);
  await page.screenshot({ path: shotPath, fullPage: false });
};

const leg = async (browser, variant, shotPath, caption) => {
  // 首跳：一次性票据换会话（环回腿）。
  const first = await hop(browser, variant, `${baseUrl}/?ticket=${encodeURIComponent(mint())}`);
  const storageState = await first.context.storageState();
  await annotate(first.page, `${caption} 本轮 /api 请求合计 ${first.summary.total} 条。`, shotPath);
  await first.context.close();
  // 第二跳：复制 cookie 走会话，独立上下文（缓存在上下文内 ⇒ 模块一定重新被拦截）。
  const second = await hop(browser, variant, `${baseUrl}/`, storageState);
  await second.context.close();
  if (first.interceptions !== 1 || second.interceptions !== 1) {
    throw new Error(`leg '${variant}' interception mismatch: cold=${first.interceptions} reconnect=${second.interceptions}`);
  }
  return { cold: first.summary, reconnect: second.summary, interceptions: 2 };
};

const run = async () => {
  const browser = await chromium.launch();
  const before = await leg(
    browser, 'base', `${outDir}/before.png`,
    '修复前（base 字节 provider）：一轮 = 逐键 /api/chains/<key> + 逐行 /children；无预算、无并发闸。',
  );
  const after = await leg(
    browser, 'branch', `${outDir}/after.png`,
    '修复后（本支字节）：一轮 = 1 行面 + 1 批量摘要 + 预算内详情腿（≤24）+ 子代理腿（≤16）；重连复用镜像。',
  );
  await browser.close();
  const out = { baseUrl, before, after };
  writeFileSync(`${outDir}/readings.json`, JSON.stringify(out, null, 2));
  console.log(JSON.stringify(out, null, 2));
};

run().catch((e) => { console.error(e); process.exit(1); });

// portal-readonly.mjs — 真实生产门户的只读取证（尽力项：撞登录墙就如实记录）。
//
// 纪律：
//   · 只读：绝不登出/不发消息/不改设置；只做一次页面加载 + 截图 + 控制台读数。
//   · profile 只用**副本**：源 ~/Library/Application Support/Google/Chrome/Default
//     复制到 /tmp 再 --user-data-dir，原 profile 一字节不动。
//   · 生产引擎（6138 / pid 41715）零接触：本脚本只打公网 https://mystand.space。
//
// 用法：node portal-readonly.mjs [deviceId]
// 产物：../red/portal-readonly-<ts>.json + screenshots/portal-readonly-*.png

import { chromium } from 'playwright';
import fs from 'node:fs';
import path from 'node:path';
import { execFileSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const RED = path.resolve(HERE, '..', 'red');
const SRC_PROFILE = '/Users/kaiyu/Library/Application Support/Google/Chrome/Default';
const COPY = '/tmp/nearpath-fire/chrome-profile-copy';
const DEVICE = process.argv[2] || '6a64d249-e526-52b1-b5b6-3b19ea029b26';
const URL = `https://mystand.space/portal?device=${DEVICE}`;

fs.mkdirSync(path.join(RED, 'screenshots'), { recursive: true });

// 1. profile 副本（源只读；rsync 只读拉取）
if (!fs.existsSync(COPY)) {
  fs.mkdirSync(COPY, { recursive: true });
  execFileSync('rsync', ['-a', SRC_PROFILE + '/', COPY + '/'], { stdio: 'inherit' });
  console.log('[profile] copied →', COPY);
} else {
  console.log('[profile] reuse copy →', COPY);
}

const ctx = await chromium.launchPersistentContext(COPY, {
  headless: true,
  ignoreHTTPSErrors: true,
  viewport: { width: 1440, height: 900 },
});

const page = ctx.pages()[0] || await ctx.newPage();
const consoleLines = [];
const failed = [];
page.on('console', (m) => consoleLines.push(`[${m.type()}] ${m.text().slice(0, 300)}`));
page.on('requestfailed', (r) => failed.push({ url: r.url().slice(0, 200), err: r.failure()?.errorText || '' }));

const t0 = Date.now();
let navError = null;
try { await page.goto(URL, { waitUntil: 'domcontentloaded', timeout: 45000 }); }
catch (e) { navError = String(e).slice(0, 300); }
await new Promise((r) => setTimeout(r, 6000));

const finalUrl = page.url();
const bodyText = (await page.evaluate(() => document.body?.innerText || '').catch(() => '')).slice(0, 4000);
const cookies = await ctx.cookies('https://mystand.space').catch(() => []);
const shot = path.join(RED, 'screenshots', `portal-readonly-${Date.now()}.png`);
await page.screenshot({ path: shot, fullPage: false }).catch(() => {});

const out = {
  at: new Date().toISOString(), url: URL, finalUrl, elapsedMs: Date.now() - t0, navError,
  cookieNames: cookies.map((c) => `${c.name}(domain=${c.domain})`),
  loginWall: /登录|sign in|log in|login/i.test(bodyText) && bodyText.length < 2000,
  bodyTextHead: bodyText.slice(0, 1200),
  consoleLines: consoleLines.slice(-40), requestFailed: failed.slice(-20),
  screenshot: path.basename(shot),
};
fs.writeFileSync(path.join(RED, `portal-readonly-${Date.now()}.json`), JSON.stringify(out, null, 2));
console.log(JSON.stringify({ finalUrl, loginWall: out.loginWall, cookies: out.cookieNames, bodyHead: out.bodyTextHead.slice(0, 200) }, null, 2));
await ctx.close();

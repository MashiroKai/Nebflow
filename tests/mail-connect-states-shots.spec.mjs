// mail-connect-states-shots.mjs — 邮箱连接三态实拍（根治批 2026-11-01）。
//
// 钉面（「Host did not answer in time」根治批 验收链）：
//   ① 连接表单态 —— 两问卡 + 自动配置说明行（默认文案）；
//   ② 自动配置显形 —— 输入 qq 地址 300ms 防抖后回显「已识别 …（imap.qq.com:993
//      · smtp.qq.com:465，SSL）」（高级设置里能看见实际将拨的 host:port）；
//   ③ 错误态（中转 504）—— relay「Host did not answer in time」不再裸上屏，
//      分类为中文提示（云端通道…请查看面板状态）；
//   ④ 成功态 —— 「已连接：收件箱 N 封、M 封未读」。
//
// 双引擎（电脑端 1280×860 / 手机端 390×844）各实拍一轮；夹具 = 真 perception.js
// + 真 i18n + 真 perception.css（静态夹具，fetch 全量 mock，零真实网络——
// 同 tests/mail-perception.spec.mjs 口径）。
//
// Run: node node_modules/@playwright/test/cli.js test tests/mail-connect-states-shots.mjs

import { test, expect } from '@playwright/test';
import http from 'node:http';
import { readFile } from 'node:fs/promises';
import net from 'node:net';
import fs from 'node:fs';
import path from 'node:path';
import { extname, join, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';

const REPO_ROOT = path.join(path.dirname(fileURLToPath(import.meta.url)), '..');
const HARNESS_PATH = '/tests/fixtures/mail-perception/harness.html';
const SHOT_DIR = process.env.MAIL_CONNECT_SHOTS
  || path.join(REPO_ROOT, '.nebflow', 'evidence', 'mail-connect-states');

let port;
const servers = [];

function freePort() {
  return new Promise((resolve, reject) => {
    const srv = net.createServer();
    srv.listen(0, '127.0.0.1', () => {
      const p = srv.address().port;
      srv.close(() => resolve(p));
    });
    srv.on('error', reject);
  });
}

function startStaticServer(p, dir) {
  const MIME = {
    '.html': 'text/html; charset=utf-8', '.js': 'text/javascript',
    '.css': 'text/css', '.png': 'image/png', '.svg': 'image/svg+xml', '.json': 'application/json',
  };
  const root = normalize(dir);
  const srv = http.createServer(async (req, res) => {
    try {
      let rel = decodeURIComponent(new URL(req.url, 'http://localhost').pathname);
      if (rel.endsWith('/')) rel += 'index.html';
      const abs = normalize(join(root, rel));
      if (!abs.startsWith(root)) { res.writeHead(403); res.end(); return; }
      const buf = await readFile(abs);
      res.writeHead(200, { 'Content-Type': MIME[extname(abs).toLowerCase()] || 'application/octet-stream' });
      res.end(buf);
    } catch {
      res.writeHead(404); res.end('not found');
    }
  });
  srv.listen(p, '127.0.0.1');
  return { kill: () => srv.close() };
}

async function waitUntilUp(url, tries = 50) {
  for (let i = 0; i < tries; i++) {
    try {
      const res = await fetch(url);
      if (res.ok) return;
    } catch { /* not up yet */ }
    await new Promise(r => setTimeout(r, 100));
  }
  throw new Error(`static server never came up at ${url}`);
}

test.beforeAll(async () => {
  fs.mkdirSync(SHOT_DIR, { recursive: true });
  port = await freePort();
  servers.push(startStaticServer(port, REPO_ROOT));
  await waitUntilUp(`http://127.0.0.1:${port}${HARNESS_PATH}`);
});

test.afterAll(async () => {
  for (const s of servers) s.kill('SIGTERM');
  servers.length = 0;
});

async function newPage(browser, viewport) {
  const context = await browser.newContext({ locale: 'zh-CN', viewport });
  const page = await context.newPage();
  const pageErrors = [];
  page.on('pageerror', (err) => pageErrors.push(err.message));
  await page.goto(`http://127.0.0.1:${port}${HARNESS_PATH}`);
  await page.waitForFunction(() => window.__ready === true, null, { timeout: 15000 });
  return { context, page, pageErrors };
}

async function openConfig(page) {
  await page.evaluate(() => window.__openConfig());
  const card = page.locator('.pp-cfg');
  await expect(card).toBeVisible();
  return card;
}

for (const [face, viewport] of [['desktop', { width: 1280, height: 860 }], ['mobile', { width: 390, height: 844 }]]) {
  test.describe(`连接三态 @ ${face}`, () => {
    test(`${face} ① 表单态 ② 自动配置显形`, async ({ browser }) => {
      const { context, page, pageErrors } = await newPage(browser, viewport);
      const card = await openConfig(page);
      // ① 默认自动配置说明行
      await expect(card.locator('#pp-cfg-autonote')).toHaveText('服务器与端口将按邮箱自动配置');
      await page.screenshot({ path: path.join(SHOT_DIR, `connect-1-form-${face}.png`) });
      // ② 输入 qq 地址 → preset 识别 → 显形
      await page.evaluate(() => {
        window.__routes.push({
          method: 'GET',
          match: (p) => p.startsWith('/api/perception/mail/preset'),
          data: { recognized: true, provider: 'QQ 邮箱', authCodeHint: true, imapHost: 'imap.qq.com', imapPort: 993, smtpHost: 'smtp.qq.com', smtpPort: 465, smtpImplicitTls: true },
        });
      });
      await card.locator('#pp-cfg-addr').fill('kaimashiro@qq.com');
      await expect(card.locator('#pp-cfg-autonote')).toContainText('已识别', { timeout: 5000 });
      await expect(card.locator('#pp-cfg-autonote')).toContainText('imap.qq.com:993');
      await expect(card.locator('#pp-cfg-autonote')).toContainText('smtp.qq.com:465');
      await card.locator('#pp-cfg-code').fill('mock-code-not-real');
      await page.screenshot({ path: path.join(SHOT_DIR, `connect-2-autoresolved-${face}.png`) });
      expect(pageErrors).toEqual([]);
      await context.close();
    });

    test(`${face} ③ 中转 504 错误态（英文裸串不再上屏）`, async ({ browser }) => {
      const { context, page, pageErrors } = await newPage(browser, viewport);
      const card = await openConfig(page);
      await page.evaluate(() => {
        window.__routes.push({
          method: 'POST',
          match: (p) => p === '/api/perception/mail/connect',
          status: 504,
          data: { error: 'Host did not answer in time', reason: 'portal_cycle_timeout' },
        });
      });
      await card.locator('#pp-cfg-addr').fill('kaimashiro@qq.com');
      await card.locator('#pp-cfg-code').fill('mock-code-not-real');
      await card.locator('#pp-cfg-connect').click();
      const result = card.locator('#pp-cfg-result');
      await expect(result).toContainText('云端通道', { timeout: 5000 });
      // 分类文案必带英文对照（作者要能对上原文），但不再是唯一内容
      await expect(result).toContainText('Host did not answer in time');
      await page.screenshot({ path: path.join(SHOT_DIR, `connect-3-relay504-${face}.png`) });
      expect(pageErrors).toEqual([]);
      await context.close();
    });

    test(`${face} ④ 成功态（已连接：收件箱 N 封、M 封未读）`, async ({ browser }) => {
      const { context, page, pageErrors } = await newPage(browser, viewport);
      const card = await openConfig(page);
      await page.evaluate(() => {
        window.__routes.push({
          method: 'GET',
          match: (p) => p.startsWith('/api/perception/mail/preset'),
          data: { recognized: true, provider: 'QQ 邮箱', authCodeHint: true, imapHost: 'imap.qq.com', imapPort: 993, smtpHost: 'smtp.qq.com', smtpPort: 465, smtpImplicitTls: true },
        });
        window.__routes.push({
          method: 'GET',
          match: (p) => p === '/api/perception/entries',
          data: { entries: [{ id: 'mail-1', icon: 'mail', name: '邮件', state: 'Idle', summary: 'kaimashiro@qq.com', notice: '', actions: [] }], serverTime: 1 },
        });
        window.__routes.push({
          method: 'POST',
          match: (p) => p === '/api/perception/mail/connect',
          data: { ok: true, account: { id: 'kaimashiroqqcom', address: 'kaimashiro@qq.com', provider: 'QQ 邮箱', enabled: true }, mailbox: { exists: 4497, unseen: 429 } },
        });
      });
      await card.locator('#pp-cfg-addr').fill('kaimashiro@qq.com');
      await card.locator('#pp-cfg-code').fill('mock-code-not-real');
      await card.locator('#pp-cfg-connect').click();
      const result = card.locator('#pp-cfg-result');
      await expect(result).toContainText('已连接：收件箱 4497 封、429 封未读', { timeout: 5000 });
      await page.screenshot({ path: path.join(SHOT_DIR, `connect-4-success-${face}.png`) });
      expect(pageErrors).toEqual([]);
      await context.close();
    });
  });
}

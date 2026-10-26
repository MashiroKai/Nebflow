// shots.spec.mjs — 感知 r3 落地面实截（收口批 B 截图证据）。
//
// 驱动 = tests/fixtures/mail-perception/harness.html（REAL perception.js +
// 真 canvas.js，fetch 全量 mock，WS 帧经 routePerceptionFrame 单点投递）——
// 与 tests/mail-perception.spec.mjs 同一载体，截的是落地代码的真实渲染面，
// 不是 r3 设计样张。
//
// Run: npx playwright test reports/20261027_054745_mail-perception-r3-land/shots.spec.mjs

import { test } from '@playwright/test';
import http from 'node:http';
import { readFile } from 'node:fs/promises';
import net from 'node:net';
import path from 'node:path';
import { extname, join, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';

const REPO_ROOT = path.join(path.dirname(fileURLToPath(import.meta.url)), '..', '..');
const HARNESS_PATH = '/tests/fixtures/mail-perception/harness.html';
const HERE = path.dirname(fileURLToPath(import.meta.url));

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
  const MIME = { '.html': 'text/html; charset=utf-8', '.js': 'text/javascript', '.css': 'text/css', '.png': 'image/png' };
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
    } catch { res.writeHead(404); res.end('not found'); }
  });
  srv.listen(p, '127.0.0.1');
  return srv;
}

const ENTRIES_CONNECTED = {
  entries: [{ id: 'acc-1', icon: 'mail', name: '邮件', state: 'Idle', summary: 'i@kaiyu.me · 已连接', notice: '', actions: ['config', 'toggle'] }],
};
const ENTRIES_NONE = { entries: [] };
const ENTRIES_AUTHFAIL = {
  entries: [{ id: 'acc-1', icon: 'mail', name: '邮件', state: 'AuthFailed', summary: 'i@kaiyu.me · 授权失效', notice: '授权码已失效，请重新连接邮箱', actions: ['config', 'toggle'] }],
};
const ENTRIES_BACKOFF = {
  entries: [{ id: 'acc-1', icon: 'mail', name: '邮件', state: 'Backoff', summary: 'i@kaiyu.me · 正在自动恢复', notice: '连接中断 · 稍后自动重试', actions: ['config', 'toggle'] }],
};

const DRAFT = {
  doc: {
    draftId: 'draft-e2e-1',
    to: ['billing@vendor.com'],
    cc: ['finance@kaiyu.me'],
    bcc: [],
    subject: '10 月服务对账与发票申请',
    bodyHtml: '<p>你好，<br>附上 10 月服务对账单，请查收并安排开票。</p>',
    attachments: ['/Users/kai/Documents/invoice/2026-10-invoice.pdf'],
    replyToMessageId: null,
    quoteHtml: null,
  },
  stale: false,
};

async function up(url) {
  for (let i = 0; i < 50; i++) {
    try { const r = await fetch(url); if (r.ok) return; } catch { /* retry */ }
    await new Promise(r => setTimeout(r, 100));
  }
  throw new Error('harness never up');
}

test.beforeAll(async () => {
  port = await freePort();
  servers.push(startStaticServer(port, REPO_ROOT));
  await up(`http://127.0.0.1:${port}${HARNESS_PATH}`);
});

test.afterAll(() => { for (const s of servers) s.close(); });

async function page_(browser, { dark = false, entries = ENTRIES_CONNECTED, vw = 900, vh = 1000 } = {}) {
  const context = await browser.newContext({ locale: 'zh-CN', viewport: { width: vw, height: vh } });
  const page = await context.newPage();
  await page.goto(`http://127.0.0.1:${port}${HARNESS_PATH}`);
  await page.waitForFunction(() => window.__ready === true, null, { timeout: 15000 });
  await page.evaluate(([dark, entries, draft]) => {
    document.documentElement.setAttribute('data-theme', dark ? 'dark' : 'light');
    window.__routes.push({ method: 'GET', match: (p) => p === '/api/perception/entries', data: entries });
    window.__routes.push({ method: 'GET', match: (p) => p.startsWith('/api/perception/mail/drafts/draft-e2e-1'), data: draft });
    window.__routes.push({ method: 'GET', match: (p) => p.startsWith('/api/perception/otp/consume'), data: { code: '824193' } });
    window.__routes.push({ method: 'POST', match: (p) => p.startsWith('/api/perception/otp/window/open'), data: { ok: true } });
    window.__routes.push({ method: 'POST', match: (p) => p === '/api/perception/mail/send', data: { draftId: 'draft-e2e-1', messageId: '<mail-2026-10-27-a1b2@kaiyu.me>', sentAt: Date.now() } });
  }, [dark, entries, DRAFT]);
  return { context, page };
}

async function openPanelExpanded(page) {
  await page.evaluate(() => window.__openPanel());
  await page.waitForSelector('#perception-panel .pp-entry', { timeout: 5000 });
  await page.waitForTimeout(450);                    // entries fetch + 重渲染落定
  const open = await page.$('#perception-panel .pp-entry.open');
  if (!open) {
    await page.click('#perception-panel .pp-entry-head');
    await page.waitForTimeout(300);
  }
}

// ── 面板：已连接展开（亮/暗） ───────────────────────────────────────────
test('panel-run-light+dark', async ({ browser }) => {
  {
    const { context, page } = await page_(browser, { entries: ENTRIES_CONNECTED });
    await openPanelExpanded(page);
    await page.screenshot({ path: path.join(HERE, 'shot-panel-run-light.png') });
    await context.close();
  }
  {
    const { context, page } = await page_(browser, { dark: true, entries: ENTRIES_CONNECTED });
    await openPanelExpanded(page);
    await page.screenshot({ path: path.join(HERE, 'shot-panel-run-dark.png') });
    await context.close();
  }
});

// ── 面板：未配置占位（两步 hero） ────────────────────────────────────────
test('panel-setup-light', async ({ browser }) => {
  const { context, page } = await page_(browser, { entries: ENTRIES_NONE });
  await openPanelExpanded(page);
  await page.screenshot({ path: path.join(HERE, 'shot-panel-setup-light.png') });
  await context.close();
});

// ── 面板：异常两态（授权失效 / 网络不稳） ───────────────────────────────
test('panel-error-auth+conn', async ({ browser }) => {
  {
    const { context, page } = await page_(browser, { entries: ENTRIES_AUTHFAIL });
    await openPanelExpanded(page);
    await page.screenshot({ path: path.join(HERE, 'shot-panel-error-auth-light.png') });
    await context.close();
  }
  {
    const { context, page } = await page_(browser, { entries: ENTRIES_BACKOFF });
    await openPanelExpanded(page);
    await page.screenshot({ path: path.join(HERE, 'shot-panel-error-conn-light.png') });
    await context.close();
  }
});

// ── 面板：collapsed（单行摘要，未展开） ─────────────────────────────────
test('panel-collapsed-light', async ({ browser }) => {
  const { context, page } = await page_(browser, { entries: ENTRIES_CONNECTED });
  await page.evaluate(() => window.__openPanel());
  await page.waitForSelector('#perception-panel .pp-entry', { timeout: 5000 });
  await page.waitForTimeout(250);
  await page.screenshot({ path: path.join(HERE, 'shot-panel-collapsed-light.png') });
  await context.close();
});

// ── 配置卡：两问 + 高级折叠展开 ─────────────────────────────────────────
test('panel-config-light+adv', async ({ browser }) => {
  const { context, page } = await page_(browser, { entries: ENTRIES_CONNECTED });
  await page.evaluate(() => window.__openConfig());
  await page.waitForSelector('#pp-cfg-overlay .pp-cfg', { timeout: 5000 });
  await page.waitForTimeout(200);
  await page.screenshot({ path: path.join(HERE, 'shot-panel-config-light.png') });
  await page.click('#pp-cfg-overlay .pp-adv summary');
  await page.waitForTimeout(200);
  await page.screenshot({ path: path.join(HERE, 'shot-panel-config-adv-light.png') });
  await context.close();
});

// ── OTP：码到出片 → 点击填入 ────────────────────────────────────────────
test('otp-chip+filled', async ({ browser }) => {
  const { context, page } = await page_(browser, {});
  await page.evaluate(() => {
    const bubble = window.__mkBubble(true);
    window.__enhance(bubble, [{ id: 'q1', question: '请把邮件里的验证码填进来', secret: true, options: [{ label: '确认' }] }], 'ask-otp', 'sess-1');
  });
  await page.evaluate(() => window.__frame({
    type: 'perception.otp.code', windowId: 'w1', requestId: 'ask-otp',
    from: 'no-reply@vendor.com', fromDomain: 'vendor.com', receivedAt: Date.now(),
  }));
  await page.waitForSelector('.otp-chip', { timeout: 5000 });
  await page.waitForTimeout(350);
  await page.screenshot({ path: path.join(HERE, 'shot-otp-chip-light.png') });
  await page.click('.otp-chip');
  await page.waitForTimeout(600);
  await page.screenshot({ path: path.join(HERE, 'shot-otp-filled-light.png') });
  await context.close();
});

// ── 快照卡 + 发送回执（收件人/附件数/Message-ID 口径） ──────────────────
test('snapshot+receipt', async ({ browser }) => {
  const { context, page } = await page_(browser, {});
  await page.evaluate(() => {
    const bubble = window.__mkBubble(false);
    window.__enhance(bubble, [{ id: 'perceptionDraft:draft-e2e-1', question: 'x', options: [{ label: 'a' }] }], 'ask-s1', 'sess-9');
  });
  await page.waitForSelector('.pdraft', { timeout: 5000 });
  await page.waitForTimeout(300);
  await page.screenshot({ path: path.join(HERE, 'shot-snapshot-light.png') });
  await page.click('.pdraft [data-act="send"]');
  await page.waitForSelector('.pdraft-receipt', { timeout: 5000 });
  await page.waitForTimeout(200);
  await page.screenshot({ path: path.join(HERE, 'shot-snapshot-receipt-light.png') });
  await context.close();
});

// ── Canvas 邮件编辑器（亮/暗） ──────────────────────────────────────────
test('canvas-light+dark', async ({ browser }) => {
  {
    const { context, page } = await page_(browser, { vw: 1200, vh: 900 });
    await page.evaluate(() => window.__openEditor('draft-e2e-1'));
    await page.waitForSelector('.mailcompose', { timeout: 5000 });
    await page.waitForTimeout(350);
    await page.screenshot({ path: path.join(HERE, 'shot-canvas-light.png') });
    await context.close();
  }
  {
    const { context, page } = await page_(browser, { dark: true, vw: 1200, vh: 900 });
    await page.evaluate(() => window.__openEditor('draft-e2e-1'));
    await page.waitForSelector('.mailcompose', { timeout: 5000 });
    await page.waitForTimeout(350);
    await page.screenshot({ path: path.join(HERE, 'shot-canvas-dark.png') });
    await context.close();
  }
});

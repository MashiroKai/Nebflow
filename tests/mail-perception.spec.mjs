// mail-perception.spec.mjs — 感知域三脸钉（mail-perception-impl 批）。
//
// 钉面（r3 已批 + 两加强 的验收核心链路）：
//   ① 面板零统计钉 —— 条目化面板无统计行（r3 裁决：条目 = id/icon/name/state/
//      summary/detail；无 run/计数类统计投影），预留位恰好两枚（文件/日历）；
//      两问配置卡必填恰好两个（地址 + 授权码），高级折叠默认收起。
//   ② 单码片钉 —— OTP 等待期零 UI；码到出片（帧不含码值）；点击一次性消费
//      （GET otp/consume 恰一次）填值入密码框且不自动提交；过期帧静默撤片。
//   ③ 快照卡钉 —— to/cc/主题/正文只读渲染 + 默认选项区接管；「照此发送」409
//      ⇒ stale 提示行；成功 ⇒ 回执行 + answerAsk 出站帧与 chat.js 卡面同形
//      （askUserAnswer + answers + requestId）。
//   ④ Canvas 编辑器钉 —— 真开标签（canvas.openTab 自建 DOM）：收件人 chips、
//      主题行、排版工具栏（bold/italic/两列表/引用/链接）、富文本正文、
//      发送回执行。
//
// 驱动：tests/fixtures/mail-perception/harness.html 引 REAL perception.js +
// 真 canvas.js，fetch 全量 mock、WS 帧经 routePerceptionFrame 单点投递、出站
// 帧落捕获桩。静态服务只起在 127.0.0.1 临时端口（🔴 绝非 8080 宿主），afterAll
// 全清。
//
// Run: node node_modules/@playwright/test/cli.js test tests/mail-perception.spec.mjs

import { test, expect } from '@playwright/test';
import { spawn } from 'node:child_process';
import net from 'node:net';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const REPO_ROOT = path.join(path.dirname(fileURLToPath(import.meta.url)), '..');
const HARNESS_PATH = '/tests/fixtures/mail-perception/harness.html';
const SHOT_DIR = process.env.MAIL_PERCEPTION_SHOTS
  || path.join(REPO_ROOT, '.nebflow', 'evidence', '20261025_mail-perception-impl');

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
  return spawn('python3', ['-m', 'http.server', String(p), '--bind', '127.0.0.1', '--directory', dir], {
    stdio: 'ignore',
  });
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

async function newPage(browser) {
  const context = await browser.newContext({ locale: 'zh-CN', viewport: { width: 900, height: 1000 } });
  const page = await context.newPage();
  const pageErrors = [];
  page.on('pageerror', (err) => pageErrors.push(err.message));
  await page.goto(`http://127.0.0.1:${port}${HARNESS_PATH}`);
  await page.waitForFunction(() => window.__ready === true, null, { timeout: 15000 });
  return { context, page, pageErrors };
}

const DRAFT = {
  doc: {
    draftId: 'draft-e2e-1',
    to: ['a@b.com', 'c@d.com'],
    cc: ['e@f.com'],
    bcc: [],
    subject: '十月对账',
    bodyHtml: '<p>正文一行</p>',
    attachments: [],
    replyToMessageId: null,
    quoteHtml: null,
  },
  stale: false,
};

// ============================================================
// ① 感知面板：条目化 + 预留位两枚 + 零统计 + 两问配置卡
// ============================================================
test.describe('面板脸', () => {
  test('P1 条目化渲染 + 预留位恰好文件/日历两枚 + 面板内无统计行', async ({ browser }) => {
    const { context, page, pageErrors } = await newPage(browser);
    await page.evaluate(() => {
      window.__routes.push({
        method: 'GET', match: (p) => p === '/api/perception/entries',
        data: { entries: [{ id: 'mail-1', icon: 'mail', name: '邮件', state: 'Idle', summary: 'demo@qq.com', notice: '', actions: [] }], serverTime: 1 },
      });
    });
    await page.evaluate(() => window.__openPanel());
    const panel = page.locator('#perception-panel');
    await expect(panel).toBeVisible();
    // 真实条目一枚 + 预留两枚
    await expect(panel.locator('.pp-entry:not(.reserved)')).toHaveCount(1);
    await expect(panel.locator('.pp-entry.reserved')).toHaveCount(2);
    const reservedNames = await panel.locator('.pp-entry.reserved .pp-e-name').allTextContents();
    expect(reservedNames, '预留位恰好 文件/日历').toEqual(['文件', '日历']);
    // 零统计：面板内不存在任何统计/计数类行（r3 裁决——面板只条目化）
    const statish = await panel.locator('[class*="stat"], [data-stat]').count();
    expect(statish, '面板内不得出现统计行').toBe(0);
    expect(pageErrors).toEqual([]);
    await page.screenshot({ path: path.join(SHOT_DIR, 'mail-perception-1-panel.png') });
    await context.close();
  });

  test('P2 未配置态占位 + 两问配置卡：必填恰好两个、高级折叠默认收起', async ({ browser }) => {
    const { context, page, pageErrors } = await newPage(browser);
    await page.evaluate(() => {
      window.__routes.push({ method: 'GET', match: (p) => p === '/api/perception/entries', data: { entries: [], serverTime: 1 } });
    });
    await page.evaluate(() => window.__openPanel());
    const panel = page.locator('#perception-panel');
    await expect(panel).toBeVisible();
    await expect(panel.locator('.pp-entry:not(.reserved)')).toHaveCount(1);
    await expect(panel.locator('.pp-entry .pp-e-sum').first()).toHaveText('尚未连接邮箱');
    // 两问配置卡：必填恰好 地址 + 授权码；高级 <details> 默认收起
    await page.evaluate(() => window.__openConfig());
    const cfg = page.locator('#pp-cfg-overlay');
    await expect(cfg).toBeVisible();
    await expect(cfg.locator('#pp-cfg-addr')).toBeVisible();
    await expect(cfg.locator('#pp-cfg-code')).toBeVisible();
    const advOpen = await cfg.locator('details.pp-adv').first().evaluate((d) => d.open);
    expect(advOpen, '高级折叠默认收起').toBe(false);
    // 两问语义：折叠区外的主面上可见输入框恰好 2 个
    const adv = cfg.locator('details.pp-adv').first();
    const visibleInputs = await cfg.locator('input').evaluateAll((els, advEl) =>
      els.filter((el) => !advEl.contains(el)).length, await adv.elementHandle());
    expect(visibleInputs, '主面输入恰好 地址+授权码 两个').toBe(2);
    expect(pageErrors).toEqual([]);
    await page.screenshot({ path: path.join(SHOT_DIR, 'mail-perception-2-config.png') });
    await context.close();
  });
});

// ============================================================
// ② OTP 单码片：等待期零 UI → 码到出片 → 点击即填 → 过期零残留
// ============================================================
test.describe('单码片脸', () => {
  test('O1 码到出片；点击一次性消费并填入密码框；不自动提交', async ({ browser }) => {
    const { context, page, pageErrors } = await newPage(browser);
    await page.evaluate(() => {
      window.__routes.push({ method: 'POST', match: (p) => p === '/api/perception/otp/window/open', data: { ok: true } });
      window.__routes.push({
        method: 'GET', match: (p) => p.startsWith('/api/perception/otp/consume'),
        data: () => { window.__consumeHits = (window.__consumeHits || 0) + 1; return { code: '552310' }; },
      });
    });
    const before = await page.evaluate(() => {
      const bubble = window.__mkBubble(true);
      window.__enhance(bubble, [{ id: 'q1', question: '请输入邮件里的验证码', secret: true }], 'ask-otp-1', 'sess-1');
      return bubble.querySelectorAll('.otp-chip').length;
    });
    expect(before, '等待期零 UI（开窗不出片）').toBe(0);
    // 码到：帧投递（帧内无码值——契约）
    const afterFrame = await page.evaluate(() => {
      const bubble = document.querySelector('#chat .msg-row');
      window.__frame({ type: 'perception.otp.code', windowId: 'w1', requestId: 'ask-otp-1', from: 'no-reply@service.com', fromDomain: 'service.com', receivedAt: 1 });
      const chip = bubble.querySelector('.otp-chip');
      return { present: !!chip, codeLeak: chip ? /\d{4,}/.test(chip.querySelector('.code')?.textContent || '') : false };
    });
    expect(afterFrame.present, '码到出片').toBe(true);
    expect(afterFrame.codeLeak, '卡面不落码值').toBe(false);
    // 点击：一次性消费 + 填值 + 片撤出
    const click = await page.evaluate(() => new Promise((resolve) => {
      document.querySelector('#chat .otp-chip').click();
      setTimeout(() => {
        const bubble = document.querySelector('#chat .msg-row');
        resolve({
          chipGone: !bubble.querySelector('.otp-chip'),
          inputValue: bubble.querySelector('input[type="password"]').value,
          consumeHits: window.__consumeHits || 0,
        });
      }, 600);
    }));
    expect(click.chipGone, '消费后码片撤出').toBe(true);
    expect(click.inputValue, '码值填入密码框').toBe('552310');
    expect(click.consumeHits, 'consume 恰好一次').toBe(1);
    // 不自动提交：无出站 askUserAnswer 帧（用户仍点确认——手输路径语义不变）
    const sent = await page.evaluate(() => window.__sent);
    expect(sent.filter((f) => f.type === 'askUserAnswer'), '点击只填值不自动提交').toEqual([]);
    expect(pageErrors).toEqual([]);
    await page.screenshot({ path: path.join(SHOT_DIR, 'mail-perception-3-otp-chip.png') });
    await context.close();
  });

  test('O2 过期帧静默撤片（零残留、零弹层）', async ({ browser }) => {
    const { context, page, pageErrors } = await newPage(browser);
    const state = await page.evaluate(() => {
      window.__routes.push({ method: 'POST', match: (p) => p === '/api/perception/otp/window/open', data: { ok: true } });
      const bubble = window.__mkBubble(true);
      window.__enhance(bubble, [{ id: 'q1', question: '输入 verification code', secret: true }], 'ask-otp-2', 'sess-1');
      window.__frame({ type: 'perception.otp.code', windowId: 'w2', requestId: 'ask-otp-2', from: 'x@y.com', fromDomain: 'y.com', receivedAt: 1 });
      const mid = document.querySelectorAll('#chat .otp-chip').length;
      window.__frame({ type: 'perception.otp.expired', requestId: 'ask-otp-2' });
      return {
        mid,
        after: document.querySelectorAll('#chat .otp-chip').length,
        alerts: document.querySelectorAll('[role="alertdialog"]').length,
      };
    });
    expect(state.mid, '过期前有片').toBe(1);
    expect(state.after, '过期零残留').toBe(0);
    expect(state.alerts, '过期零弹层').toBe(0);
    expect(pageErrors).toEqual([]);
    await context.close();
  });
});

// ============================================================
// ③ 快照卡：只读渲染 + 409 stale + 出站帧同形
// ============================================================
test.describe('快照卡脸', () => {
  test('S1 只读渲染 + 默认选项区接管 + 「照此发送」成功回执与同形出站帧', async ({ browser }) => {
    const { context, page, pageErrors } = await newPage(browser);
    await page.evaluate((draft) => {
      window.__routes.push({
        method: 'GET', match: (p) => p === '/api/perception/mail/drafts/draft-e2e-1', data: draft,
      });
      window.__routes.push({ method: 'POST', match: (p) => p === '/api/perception/mail/send', data: { draftId: 'draft-e2e-1', messageId: '<m1@x>', sentAt: 1700000000000 } });
    }, DRAFT);
    await page.evaluate(() => {
      const bubble = window.__mkBubble(false);
      window.__enhance(bubble, [{ id: 'perceptionDraft:draft-e2e-1', question: 'x', options: [{ label: 'a' }] }], 'ask-d1', 'sess-9');
    });
    await page.waitForTimeout(300); // loadDraft 异步填充
    const face = await page.evaluate(() => {
      const pd = document.querySelector('#chat .pdraft');
      const bubble = pd ? pd.closest('.msg-row') : null;
      return {
        present: !!pd,
        rows: pd ? [...pd.querySelectorAll('.pdraft-mlabel')].map((x) => x.textContent) : [],
        subject: pd?.querySelector('[data-f="subject"]')?.textContent || '',
        to: pd?.querySelector('[data-f="to"]')?.textContent || '',
        ccRowHidden: pd?.querySelector('[data-cc-row]')?.hidden ?? null,
        fallbackHidden: (() => { const b = bubble?.querySelector('.option-box'); return b ? getComputedStyle(b).display === 'none' : null; })(),
        btns: pd ? [...pd.querySelectorAll('.pp-btn')].map((b) => b.dataset.act) : [],
      };
    });
    expect(face.present, '快照面已挂载').toBe(true);
    expect(face.rows).toEqual(['收件人', '抄送', '主题']);
    expect(face.subject).toBe('十月对账');
    expect(face.to).toBe('a@b.com、c@d.com');
    expect(face.ccRowHidden, 'cc 有值不隐藏').toBe(false);
    expect(face.fallbackHidden, '默认选项区被接管隐藏').toBe(true);
    expect(face.btns).toEqual(['send', 'canvas', 'abandon']);
    // 「照此发送」：POST send 成功 → 回执行 + answerAsk 同形帧
    await page.evaluate(() => document.querySelector('#chat .pdraft [data-act="send"]').click());
    await page.waitForTimeout(500);
    const after = await page.evaluate(() => ({
      receipt: document.querySelector('#chat .pdraft .pdraft-receipt')?.textContent || '',
      sent: window.__sent,
    }));
    expect(after.receipt, '发送回执行出现').toContain('已发送');
    const answer = after.sent.find((f) => f.type === 'askUserAnswer');
    expect(answer, '出站 askUserAnswer 帧').toBeTruthy();
    expect(answer.sessionId).toBe('sess-9');
    expect(answer.requestId).toBe('ask-d1');
    expect(answer.answers).toEqual(['已照此发送']);
    expect(pageErrors).toEqual([]);
    await page.screenshot({ path: path.join(SHOT_DIR, 'mail-perception-4-snapshot-sent.png') });
    await context.close();
  });

  test('S2 「照此发送」409 ⇒ stale 提示行（一致性守则可见面）', async ({ browser }) => {
    const { context, page, pageErrors } = await newPage(browser);
    await page.evaluate((draft) => {
      window.__routes.push({
        method: 'GET', match: (p) => p === '/api/perception/mail/drafts/draft-e2e-1', data: draft,
      });
      window.__routes.push({
        method: 'POST', match: (p) => p === '/api/perception/mail/send', status: 409,
        data: { error: 'snapshot stale' },
      });
    }, DRAFT);
    await page.evaluate(() => {
      const bubble = window.__mkBubble(false);
      window.__enhance(bubble, [{ id: 'perceptionDraft:draft-e2e-1', question: 'x', options: [{ label: 'a' }] }], 'ask-d2', 'sess-9');
    });
    await page.waitForTimeout(300);
    await page.evaluate(() => document.querySelector('#chat .pdraft [data-act="send"]').click());
    await page.waitForTimeout(500);
    const stale = await page.evaluate(() => {
      const el = document.querySelector('#chat .pdraft .pdraft-stale');
      return { shown: !!el && el.classList.contains('show'), text: el?.textContent || '' };
    });
    expect(stale.shown, '409 后 stale 提示行显示').toBe(true);
    expect(stale.text, '提示文案指回画布确认').toContain('画布');
    // 出站帧：409 不作答（用户需先回画布）
    const sent = await page.evaluate(() => window.__sent);
    expect(sent.filter((f) => f.type === 'askUserAnswer'), '409 不出站作答帧').toEqual([]);
    expect(pageErrors).toEqual([]);
    await page.screenshot({ path: path.join(SHOT_DIR, 'mail-perception-5-snapshot-stale.png') });
    await context.close();
  });
});

// ============================================================
// ④ Canvas 编辑器：真开标签（openTab 自建 DOM）
// ============================================================
test.describe('Canvas 编辑器脸', () => {
  test('E1 chips/主题/工具栏/富文本正文齐备 + 发送回执行', async ({ browser }) => {
    const { context, page, pageErrors } = await newPage(browser);
    await page.evaluate((draft) => {
      window.__routes.push({
        method: 'GET', match: (p) => p === '/api/perception/mail/drafts/draft-e2e-1', data: draft,
      });
      window.__routes.push({ method: 'PUT', match: (p) => p === '/api/perception/mail/drafts/draft-e2e-1', data: { ok: true } });
      window.__routes.push({ method: 'POST', match: (p) => p === '/api/perception/mail/send', data: { draftId: 'draft-e2e-1', messageId: '<m2@x>', sentAt: 1700000000000 } });
    }, DRAFT);
    await page.evaluate(() => window.__openEditor('draft-e2e-1', {}));
    const pane = page.locator('#canvas-content .canvas-tab-pane[data-tab-id="perception-draft:draft-e2e-1"]');
    await expect(pane).toBeVisible({ timeout: 10000 });
    const ed = await pane.evaluate((root) => ({
      chips: root.querySelectorAll('.to-chip').length,
      ccShown: root.querySelector('.cc-row.show') !== null,
      subject: root.querySelector('.subject-input')?.value || '',
      toolbar: [...root.querySelectorAll('.mt-btn')].map((b) => b.dataset.cmd),
      hasBody: !!root.querySelector('.mail-body-edit[contenteditable="true"]'),
      bodyText: root.querySelector('.mail-body-edit')?.textContent || '',
    }));
    expect(ed.chips, '收件人 chips（2 收件 + 1 抄送）').toBe(3);
    expect(ed.ccShown, 'cc 行因有值而显示').toBe(true);
    expect(ed.subject).toBe('十月对账');
    expect(ed.toolbar, '排版工具栏命令齐备').toEqual(['bold', 'italic', 'insertUnorderedList', 'insertOrderedList', 'formatBlock', 'link']);
    expect(ed.hasBody, '富文本正文区').toBe(true);
    expect(ed.bodyText).toContain('正文一行');
    // 发送（画布发送 snapshot=false，无 stale 门）→ 回执行
    await pane.locator('[data-t="send"]').click();
    await page.waitForTimeout(600);
    const note = await pane.evaluate((root) => {
      const n = root.querySelector('.mail-send-note');
      return { shown: !!n && n.classList.contains('show'), ok: !!n && n.classList.contains('ok'), text: n?.textContent || '' };
    });
    expect(note.shown, '发送回执行显示').toBe(true);
    expect(note.ok, '成功为 ok 色调').toBe(true);
    expect(note.text).toContain('已发送');
    expect(pageErrors).toEqual([]);
    await page.screenshot({ path: path.join(SHOT_DIR, 'mail-perception-6-editor.png') });
    await context.close();
  });
});

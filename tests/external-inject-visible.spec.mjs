// external-inject-visible.spec.mjs — 外部注入统一通道批（2026-11-05）前端判据。
//
// 作者报障逐字：「所有的外部注入消息应该有统一的途径和蓝色气泡通知，我刚刚看到你好
// 多条消息触发你，我都看不到是什么」。
//
// 本 spec 把四条**可见性判据**落成 DOM 断言（逐条对应批报告 §判据）：
//   ① 会话里出现一条对应气泡行   → `.row.user > .bubble.injected` 计数 == 注入条数
//   ② 气泡带来源标识             → 来源行文本含该 source 的登记标签（MergeQueue 等）
//   ③ 正文与注入内容一致         → 摘要行 == 正文首条有效行；展开后正文 == 注入全文
//                                  （不是摘要替身、不是截断）
//   ④ 刷新后仍在                 → 历史恢复腿（restoreFromBackendHistory）与 live 腿
//                                  读数逐项相同（同一 builder 同构 = 落盘即可见）
// 外加「蓝而不绿」的视觉判据：注入泡底色与用户绿泡底色不同（沿用 --sapphire tint，
// 零新色值）。
//
// Drives the REAL render modules through tests/fixtures/external-inject-visible/
// harness.html over a throwaway static server (never the 6138 host).
//
// Run: npx playwright test tests/external-inject-visible.spec.mjs

import { test, expect } from '@playwright/test';
import { spawn } from 'node:child_process';
import net from 'node:net';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const REPO_ROOT = path.join(path.dirname(fileURLToPath(import.meta.url)), '..');
const HARNESS_PATH = '/tests/fixtures/external-inject-visible/harness.html';

const T0 = new Date(new Date().setHours(9, 12, 0, 0)).getTime();

/** 五行外部注入（改前**恒不可见**的五族；行形态 = InjectionChannel.deliver 落盘面）。 */
const EXT_ROWS = [
  {
    type: 'user', injected: true, source: 'merge-queue', eventType: 'failed', timestamp: T0,
    header: 'MERGE-QUEUE · NEBULA · DELEGATE-ALPHA · FAILED',
    text: '[merge-queue] delegate "alpha" — its merge membership was closed as OWNER-GONE: no live delivery channel after restart.\nNo merge rounds are revived. Merge the branch by hand or re-dispatch.',
  },
  {
    type: 'user', injected: true, source: 'bg-lifecycle', eventType: 'nudge', timestamp: T0,
    text: '[bg-lifecycle] background job JOB-77 has been running for 12 minutes with no progress output.\nCheck the job or cancel it with BgTaskCancel.',
  },
  {
    type: 'user', injected: true, source: 'waiting-dock-care', eventType: 'failed', timestamp: T0,
    text: '[waiting-dock-care] delegate "beta": waiting dock timed out. The task row was marked failed — needs dispatcher handling.',
  },
  {
    type: 'user', injected: true, source: 'chain-orphan', eventType: 'chain-orphaned', timestamp: T0,
    text: '[chain-orphan] chain chain-n-1a2b3c4d lost its owner session at restart; 3 nodes were left unwired.',
  },
  {
    type: 'user', injected: true, source: 'team', eventType: 'error-escalated', timestamp: T0,
    text: '[team] worker "gamma" entered the error escalation chain (attempt 2): upstream 429 after 3 retries.',
  },
  // 控制件：普通用户绿泡（判据「蓝而不绿」的对照底色）。
  { type: 'user', text: '这是用户自己发的一条消息。', timestamp: T0 },
];

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
  port = await freePort();
  expect(port).not.toBe(6138); // host discipline
  servers.push(startStaticServer(port, REPO_ROOT));
  await waitUntilUp(`http://127.0.0.1:${port}${HARNESS_PATH}`);
});

test.afterAll(async () => {
  for (const s of servers) s.kill('SIGTERM');
  servers.length = 0;
});

async function newPage(browser) {
  const context = await browser.newContext({ locale: 'zh-CN', viewport: { width: 900, height: 1200 } });
  const page = await context.newPage();
  const pageErrors = [];
  page.on('pageerror', (err) => pageErrors.push(err.message));
  await page.goto(`http://127.0.0.1:${port}${HARNESS_PATH}`);
  await page.waitForFunction(() => window.__ready === true, null, { timeout: 15000 });
  return { context, page, pageErrors };
}

/** 正文首条有效行（与前端 injectSummaryLine 同判据的**独立**实现——防两边同错）。 */
function firstBodyLine(text) {
  for (const raw of String(text).split('\n')) {
    const line = raw.trim();
    if (!line) continue;
    if (line === '<system-reminder>' || line === '</system-reminder>') continue;
    const body = line.replace(/^(?:[-*+]\s+|\d+[.)]\s+)/, '').trim();
    if (body) return body.length > 160 ? `${body.slice(0, 160)}…` : body;
  }
  return '';
}

const squash = (s) => String(s || '').replace(/\s+/g, '');

for (const leg of ['liveInject', 'replayBackend']) {
  test.describe(`leg: ${leg}`, () => {
    test('判据 ①：每条外部注入都出一条注入气泡行（改前 5 条零行）', async ({ browser }) => {
      const { page, pageErrors } = await newPage(browser);
      await page.evaluate((rows) => window.__liveInject(rows), EXT_ROWS.filter(r => r.injected));
      const c = await page.evaluate(() => window.__census());
      expect(c.injectedRows).toBe(5);
      expect(pageErrors).toEqual([]);
      await page.context().close();
    });

    test('判据 ②③：来源标识 + 一句话摘要 + 完整正文可展开', async ({ browser }) => {
      const { page } = await newPage(browser);
      await page.evaluate((rows) => window.__liveInject(rows), EXT_ROWS.filter(r => r.injected));
      const c = await page.evaluate(() => window.__census());

      expect(c.rows).toHaveLength(5);
      // 判据 ②：来源标识（第一条走引擎 header 逐字渲染，其余走 frontend 登记表）。
      // PROJECT 段的机制名在显示层换成用户给它起的名字（Nebula 退役批）⇒ 该段不钉字面量。
      expect(c.rows[0].label).toMatch(/^MERGE-QUEUE · .+ · DELEGATE-ALPHA · FAILED$/);
      expect(c.rows[1].label).toBe('BgLifecycle · Nudge');
      expect(c.rows[2].label).toBe('DockCare · Failed');
      expect(c.rows[3].label).toBe('ChainOrphan · Chain-orphaned');
      expect(c.rows[4].label).toBe('Team · Error-escalated');
      for (const r of c.rows) expect(r.labelVisible).toBe(true);

      // 判据 ③（折叠态）：摘要行 = 正文首条有效行，且是来源行的紧邻兄弟（CSS 依赖）
      const bodies = EXT_ROWS.filter(r => r.injected).map(r => r.text);
      c.rows.forEach((r, i) => {
        expect(r.summary).toBe(firstBodyLine(bodies[i]));
        expect(r.summaryVisible).toBe(true);
        expect(r.summaryIsNextSibling).toBe(true);
        expect(r.injected).toBe(true);
      });

      // 判据 ③（展开态）：正文是**全文**（不是摘要替身）
      await page.evaluate(() => window.__expand(0));
      const c2 = await page.evaluate(() => window.__census());
      expect(squash(c2.rows[0].body)).toBe(squash(bodies[0]));
      expect(c2.rows[0].expanded).toBe('true');
      expect(c2.rows[0].summaryVisible).toBe(false); // 展开后摘要让位（首行不重复）
      // 未展开的邻行不受影响
      expect(c2.rows[1].summaryVisible).toBe(true);
    });

    test('蓝而不绿：注入泡底色与用户绿泡底色不同（沿用 --sapphire tint，零新色值）', async ({ browser }) => {
      const { page } = await newPage(browser);
      if (leg === 'liveInject') {
        await page.evaluate((rows) => window.__liveInject(rows.filter(r => r.injected)), EXT_ROWS);
        await page.evaluate(() => window.__replayBackend([{
          type: 'user', text: '这是用户自己发的一条消息。', timestamp: Date.now(),
        }]));
      } else {
        await page.evaluate((rows) => window.__replayBackend(rows), EXT_ROWS);
      }
      const c = await page.evaluate(() => window.__census());
      const green = await page.evaluate(() => {
        const b = document.querySelector('#chat .row.user > .bubble.user:not(.injected)');
        return b ? getComputedStyle(b).backgroundColor : null;
      });
      expect(green).not.toBeNull();
      for (const r of c.rows) {
        expect(r.bg).not.toBe(green);
        expect(r.bg).not.toBe('rgba(0, 0, 0, 0)');
      }
    });

    test('判据 ④：刷新后（历史恢复腿）读数与 live 腿逐项相同', async ({ browser }) => {
      const { page } = await newPage(browser);
      await page.evaluate((rows) => window.__liveInject(rows), EXT_ROWS.filter(r => r.injected));
      const live = await page.evaluate(() => window.__census());

      await page.evaluate(() => window.__reset());
      await page.evaluate((rows) => window.__replayBackend(rows), EXT_ROWS.filter(r => r.injected));
      const replay = await page.evaluate(() => window.__census());

      expect(replay.injectedRows).toBe(live.injectedRows);
      expect(replay.rows.map(r => ({ label: r.label, summary: r.summary })))
        .toEqual(live.rows.map(r => ({ label: r.label, summary: r.summary })));
      const bodies = EXT_ROWS.filter(r => r.injected).map(r => r.text);
      replay.rows.forEach((r, i) => expect(squash(r.body)).toBe(squash(bodies[i])));
    });
  });
}

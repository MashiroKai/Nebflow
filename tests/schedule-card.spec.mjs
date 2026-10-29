// schedule-card.spec.mjs — schedcard 批（2026-10-29，作者令「定时任务卡片防退化
// + stand 配色」）前端回归：
//
//  ① 防退化主钉：带 schedule 标记的注入行（injected:true + source:'schedule'）
//    在**三条渲染腿**（localStorage 缓存恢复 / 后端历史恢复 / live WS 帧）都渲染
//    专属卡（.bubble.injected.schedule-card[data-nf-schedule]，chip + 任务名 +
//    重复小标 + 预定时间 + 引用行），**永不**落通用绿气泡分支。
//  ② 无标记消息回归不破：同内容**无标记**行（改批前落盘形态）照旧渲染绿泡
//    （.bubble.user）——旧历史行不追溯；非 schedule 源（mail）照走通用注入泡。
//  ③ 前后对比截图：绿气泡退化态 vs 专属卡片态，亮暗双模式（prefers-color-scheme
//    驱动，与 chat/reminder CSS 的暗档判定同机制）。
//
// Drives the REAL render modules through tests/fixtures/schedule-card/
// harness.html over a throwaway static server (never the 8080 host).
//
// Run: npx playwright test tests/schedule-card.spec.mjs

import { test, expect } from '@playwright/test';
import { spawn } from 'node:child_process';
import fs from 'node:fs';
import net from 'node:net';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const REPO_ROOT = path.join(path.dirname(fileURLToPath(import.meta.url)), '..');
const HARNESS_PATH = '/tests/fixtures/schedule-card/harness.html';
// 取证截图目录（受控参数 NB_SCHEDCARD_SHOT_DIR 可改道；默认 = 仓内证据位）。
const SHOT_DIR = process.env.NB_SCHEDCARD_SHOT_DIR ||
  path.join(REPO_ROOT, '.nebflow', 'evidence', '20261029_schedcard', 'shots');

const CONTENT = '每日站会提醒：到点了，把昨日纪要整理成三段式发出来。\n（实际触发 14:00，预定 14:00）';
const T0 = new Date(new Date().setHours(14, 0, 0, 0)).getTime();

// 改批前落盘形态（退化态）：裸 User 行，零标记。
const OLD_ROW = { type: 'user', text: CONTENT, timestamp: T0 };
// 新标记行（schedcard 批后 ScheduledTaskActor 落盘/广播形态）。
const NEW_ROW = {
  type: 'user', text: CONTENT, injected: true, source: 'schedule', eventType: 'trigger',
  timestamp: T0, schedule: { id: 'sc01', name: '每日站会', triggerAt: T0, repeat: 'daily' },
};
// 匿名单次任务（无 name/无 repeat）——降级形态卡照渲染。
const ANON_ROW = {
  type: 'user', text: '13:50 起立活动两分钟。', injected: true, source: 'schedule', eventType: 'trigger',
  timestamp: T0, schedule: { id: 'sc02', triggerAt: T0 },
};
// 带参考文档的任务。
const REF_ROW = {
  type: 'user', text: '审一遍 todays-notes.md，把待办抽出来。', injected: true, source: 'schedule', eventType: 'trigger',
  timestamp: T0, schedule: { id: 'sc03', triggerAt: T0, referencePath: '/notes/todays-notes.md' },
};
// 非 schedule 注入（mail）——通用注入泡回归锚。
const MAIL_ROW = {
  type: 'user', text: 'mail 件：通用注入泡回归锚。', injected: true, source: 'mail', eventType: 'info',
  timestamp: T0, sender: 'worker-a',
};

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
  expect(port).not.toBe(8080); // host discipline: never touch the host port
  servers.push(startStaticServer(port, REPO_ROOT));
  await waitUntilUp(`http://127.0.0.1:${port}${HARNESS_PATH}`);
});

test.afterAll(async () => {
  for (const s of servers) s.kill('SIGTERM');
  servers.length = 0;
});

async function newPage(browser, colorScheme = 'light') {
  const context = await browser.newContext({
    locale: 'zh-CN',
    colorScheme,
    viewport: { width: 900, height: 1000 },
  });
  const page = await context.newPage();
  const pageErrors = [];
  page.on('pageerror', (err) => pageErrors.push(err.message));
  await page.goto(`http://127.0.0.1:${port}${HARNESS_PATH}`);
  await page.waitForFunction(() => window.__ready === true, null, { timeout: 15000 });
  return { context, page, pageErrors };
}

const ALL_ROWS = [NEW_ROW, OLD_ROW, ANON_ROW, REF_ROW, MAIL_ROW];

for (const leg of ['replayCache', 'replayBackend', 'liveInject']) {
  test.describe(`leg: ${leg}`, () => {
    test('① 防退化主钉：标记行三腿都渲染专属卡（chip/任务名/重复标/时间），不落绿泡', async ({ browser }) => {
      const { page, pageErrors } = await newPage(browser);
      await page.evaluate((rows) => window['__' + rows.leg](rows.rows), { leg, rows: ALL_ROWS });
      const c = await page.evaluate(() => window.__census());

      // 专属卡三张（NEW/ANON/REF），每张保留注入行族基类 + 数据锚
      expect(c.scheduleCards).toHaveLength(3);
      for (const card of c.scheduleCards) {
        expect(card.injectedBase).toBe(true);   // 注入族语义（turnGroup/workline 消费）
        expect(card.dataAnchor).toBe(true);     // data-nf-schedule 锚
        expect(card.chip).toBe('定时任务');      // 「定时任务」chip
        expect(card.time).toContain('预定');     // 结构化时间行
      }
      const [named, anon, ref] = c.scheduleCards;
      expect(named.name).toBe('每日站会');       // 有 name 显示 name
      expect(named.repeat).toBe('每天');          // 循环任务重复小标
      expect(named.bodyHead.startsWith('每日站会提醒')).toBe(true); // 正文剥掉引擎注记尾行
      expect(named.bodyHead).not.toContain('实际触发');
      expect(anon.name).toBeNull();              // 无 name 不渲染名段
      expect(anon.repeat).toBeNull();            // 单次任务无重复标
      expect(ref.ref).toBe('/notes/todays-notes.md'); // 引用行
      // 带标记行永不落绿泡。绿泡集合 = 旧无标记行——仅恢复腿有（史行渲染路径）；
      // live 腿只消费注入帧（旧后端的 userMessage 广播是零消费者死帧 ⇒ live 零绿泡，
      // 这正是退化态「刷新后才见绿泡」的机理）。
      if (leg === 'liveInject') {
        expect(c.greenBubbles).toHaveLength(0);
      } else {
        expect(c.greenBubbles).toHaveLength(1);
        expect(c.greenBubbles[0]).toContain('每日站会提醒');
      }
      expect(pageErrors).toEqual([]);
    });

    test('② 无标记旧行回归不破（绿泡原样）+ 非 schedule 源照走通用注入泡', async ({ browser }) => {
      const { page, pageErrors } = await newPage(browser);
      await page.evaluate((rows) => window['__' + rows.leg](rows.rows), { leg, rows: ALL_ROWS });
      const c = await page.evaluate(() => window.__census());
      // 通用注入泡恰一张（mail 行）；schedule 卡不在通用注入泡集合内
      expect(c.genericInjected).toBe(1);
      expect(c.scheduleCards).toHaveLength(3);
      expect(pageErrors).toEqual([]);
    });
  });
}

test.describe('前后对比截图（退化态绿泡 vs 专属卡，亮暗双模式）', () => {
  for (const scheme of ['light', 'dark']) {
    test(`shot ${scheme}: before(green) + after(card)`, async ({ browser }) => {
      const { page } = await newPage(browser, scheme);
      // before：改批前落盘形态 → 通用绿气泡（退化态）
      await page.evaluate((rows) => window.__replayCache(rows), [OLD_ROW]);
      await page.waitForTimeout(400); // .row fadeIn 0.2s 走完再拍，证据不被动画污染
      await page.screenshot({ path: path.join(SHOT_DIR, `schedcard-before-${scheme}.png`) });
      await page.evaluate(() => window.__reset());
      // after：schedcard 批标记形态 → 专属卡
      await page.evaluate((rows) => window.__replayCache(rows), [NEW_ROW]);
      await page.waitForTimeout(400);
      await page.screenshot({ path: path.join(SHOT_DIR, `schedcard-after-${scheme}.png`) });
    });
  }
});

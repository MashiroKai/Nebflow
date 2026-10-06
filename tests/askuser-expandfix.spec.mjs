// askuser-expandfix.spec.mjs — AskUser 强制展开修令回归钉（作者 2026-10-06
// 19:29 令：主会话 AskUser 卡不得触发周边工具结果强制展开）。
//
// 病根（双树取证 .nebflow/evidence/20261006_askuser-expandfix-x1/）：网关把
// 卡片回答落盘为 User 行（answerOf，案 B 单一构造点）⇒ 历史重放
// buildTurnSummariesForHistory 在该行分裂轮次 ⇒ 问句前段拿不到终态 phrase
// badge（它骑在回答之后的最终 Ai 行上）⇒ summarizeSegment 判「未完成/失败」
// ⇒ 不建 header 不 tuck——卡前工具块全量裸露（作者所见「强行展开」），
// header 工具数少计。
//
// 修 = 回答行打标（data-nf-ask-answer，两重放腿 persistence.js）+ 轮边界
// 判定豁免（turnGroup.js isNonInjectedUserRow）+ 工作行锚豁免（workline.js
// turnAnchor——作答不是「开轮事件」，见 398dd72ac 同一裁决的措辞）。
//
// 本 spec 用 tests/fixtures/askuser-expand/harness.html（真实模块）锁四条：
//   A1 live 全链（工具→卡→作答→工具→终态）= 单 header、全 tucked（主 face
//      与无卡轮逐形相同——修令 §1）
//   A2 重放腿（含 answerOf 行）= 同上单 header 全 tucked + 回答行带标 +
//      header 落轮顶（修令 §1 的缺陷面）
//   A3 重放轮 header toggle 往返真实（scopeFromHeader 穿过豁免行正确止于
//      下一 header）＋ tucked 展开面语义不回归（修令 §2）
//   A4 旧数据形态（回答行无 answerOf，pre-案B）= 旧分裂行为逐字保留（本修
//      不越界改写存量语义）
// Run: npx playwright test tests/askuser-expandfix.spec.mjs

import { test, expect } from '@playwright/test';
import { spawn } from 'node:child_process';
import fs from 'node:fs';
import net from 'node:net';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const REPO_ROOT = path.join(path.dirname(fileURLToPath(import.meta.url)), '..');
const HARNESS = 'tests/fixtures/askuser-expand/harness.html';

let server, port, baseURL;

test.beforeAll(async () => {
  port = await new Promise((resolve, reject) => {
    const srv = net.createServer();
    srv.listen(0, '127.0.0.1', () => {
      const p = srv.address().port;
      srv.close(() => resolve(p));
    });
    srv.on('error', reject);
  });
  if (port === 8080) throw new Error('host port discipline');
  server = spawn('python3', ['-m', 'http.server', String(port), '--bind', '127.0.0.1', '--directory', REPO_ROOT], { stdio: 'ignore' });
  baseURL = `http://127.0.0.1:${port}`;
  for (let i = 0; i < 50; i++) {
    try { const r = await fetch(`${baseURL}/${HARNESS}`); if (r.ok) return; } catch {}
    await new Promise(r => setTimeout(r, 100));
  }
  throw new Error('harness never served');
});

test.afterAll(async () => { server.kill('SIGTERM'); });

const ASK_ITEMS = [{ question: '用哪个方向继续?', options: [{ label: '方向 A' }, { label: '方向 B' }] }];
const T0 = 1728200000000;

const HEAD_MSGS = [
  { type: 'user', text: '帮我看下仓库状态，然后问我一个方向问题', timestamp: T0 },
  { type: 'tool', label: 'Bash\n  (git status)', summary: '成功', content: 'clean', isError: false, input: { command: 'git status' }, timestamp: T0 + 1000 },
  { type: 'askUser', items: ASK_ITEMS, requestId: 'req-ax-1', agentName: 'Nebula' },
];
// 卡片回答之后、续跑之前：AskUserQuestion 工具自身的 toolEnd 落盘条目
// （真实历史形状，capture.mjs replayMsgs 同形）。
const ASK_TOOL_MSG = { type: 'tool', label: 'AskUserQuestion\n  (用哪个方向继续?)', summary: '已回答', content: null, isError: false, input: { question: '用哪个方向继续?' }, timestamp: T0 + 5100 };
const TAIL_MSGS = [
  { type: 'tool', label: 'Bash\n  (npm test)', summary: '成功', content: 'ok', isError: false, input: { command: 'npm test' }, timestamp: T0 + 6000 },
  { type: 'ai', text: '已确认方向 A，全部完成。', durationMs: 4200, model: 'test-model', timestamp: T0 + 7000 },
];

test.describe('AskUser 强制展开修令 (2026-10-06 19:29)', () => {
  test.beforeEach(async ({ page }) => {
    await page.goto(`${baseURL}/${HARNESS}`);
    await page.waitForFunction(() => window.__ready === true, null, { timeout: 15000 });
  });

  test('A1 live: 卡前卡后工具行全部 tucked、单 header（与无卡轮同形）', async ({ page }) => {
    await page.evaluate(() => window.__axUser('帮我看下仓库状态，然后问我一个方向问题'));
    await page.evaluate(() => window.__axCallStart('Bash'));
    await page.evaluate(() => window.__axDone('Bash\n  (git status)', '成功', 'clean', false, '{"command":"git status"}'));
    await page.evaluate(() => window.__axCallStart('AskUserQuestion'));
    await page.evaluate((items) => window.__axAsk(items, 'req-ax-1'), ASK_ITEMS);
    // 卡在场时：工具行不因卡片出现而展开（修令 §1 主句）
    const during = await page.evaluate(() => window.__axCensus());
    expect(during.askCards).toHaveLength(1);
    expect(during.tools.every(x => x.tucked)).toBe(true);
    expect(during.tools.every(x => !x.bodyOpenCls)).toBe(true);
    // 作答 → 续跑 → 终态
    await page.evaluate(() => window.__axDone('AskUserQuestion\n  (用哪个方向继续?)', '已回答', null, false, '{"question":"用哪个方向继续?"}'));
    await page.evaluate(() => window.__axCallStart('Bash'));
    await page.evaluate(() => window.__axDone('Bash\n  (npm test)', '成功', 'ok', false, '{"command":"npm test"}'));
    await page.evaluate(() => window.__axFinishTurn(4200, 'test-model', '已确认方向 A，全部完成。'));
    const settled = await page.evaluate(() => window.__axCensus());
    expect(settled.tools).toHaveLength(3);
    expect(settled.tools.every(x => x.tucked)).toBe(true);
    expect(settled.headers).toHaveLength(1);
    expect(settled.headers[0].text).toContain('工具 3 次'); // 整轮统计不被卡分裂
    expect(settled.childSeq[0]).toBe('user');
    expect(settled.childSeq[1]).toBe('header'); // header 落轮顶
  });

  test('A2 replay: answerOf 行不劈轮——单 header 全 tucked、回答行带标、header 落轮顶', async ({ page }) => {
    const msgs = [...HEAD_MSGS, { type: 'user', text: '方向 A', answerOf: 'req-ax-1', timestamp: T0 + 5000 }, ASK_TOOL_MSG, ...TAIL_MSGS];
    await page.evaluate((m) => window.__axReplay(m, {}), msgs);
    const c = await page.evaluate(() => window.__axCensus());
    expect(c.tools).toHaveLength(3);
    expect(c.tools.every(x => x.tucked)).toBe(true); // 修复点：卡前工具块不再裸露
    expect(c.tools.every(x => !x.bodyOpenCls)).toBe(true);
    expect(c.headers).toHaveLength(1);
    expect(c.headers[0].text).toContain('工具 3 次');
    expect(c.childSeq[0]).toBe('user');
    expect(c.childSeq[1]).toBe('header'); // 轮顶，不在回答行后
    expect(c.childSeq.filter(s => s === 'askcard')).toHaveLength(1); // 卡可见（交付物不收）
    expect(c.childSeq.filter(s => s.startsWith('user'))).toHaveLength(2); // 问句行 + 带标回答行
    // 标记本体：回答行带 data-nf-ask-answer 且值为被答 requestId
    const stamp = await page.evaluate(() => {
      const chat = document.getElementById('ax-chat') || document.querySelector('#ax-chat');
      const rows = Array.from(chat.children).filter(k => k.classList.contains('user'));
      return rows.map(r => r.getAttribute('data-nf-ask-answer'));
    });
    expect(stamp.filter(Boolean)).toEqual(['req-ax-1']);
  });

  test('A3 replay 轮 header toggle 往返真实（豁免行不破展开面语义）', async ({ page }) => {
    const msgs = [...HEAD_MSGS, { type: 'user', text: '方向 A', answerOf: 'req-ax-1', timestamp: T0 + 5000 }, ASK_TOOL_MSG, ...TAIL_MSGS];
    await page.evaluate((m) => window.__axReplay(m, {}), msgs);
    await page.evaluate(() => document.querySelector('.turn-header').click());
    let c = await page.evaluate(() => window.__axCensus());
    expect(c.headers[0].state).toBe('done-expanded');
    expect(c.tools.every(x => !x.tucked)).toBe(true); // 展开露全过程
    await page.evaluate(() => document.querySelector('.turn-header').click());
    c = await page.evaluate(() => window.__axCensus());
    expect(c.headers[0].state).toBe('done');
    expect(c.tools.every(x => x.tucked)).toBe(true); // 再收不漏
  });

  test('A4 旧数据（回答行无 answerOf）：旧分裂行为逐字保留（本修不越界）', async ({ page }) => {
    const msgs = [...HEAD_MSGS, { type: 'user', text: '方向 A', timestamp: T0 + 5000 }, ASK_TOOL_MSG, ...TAIL_MSGS];
    await page.evaluate((m) => window.__axReplay(m, {}), msgs);
    const c = await page.evaluate(() => window.__axCensus());
    expect(c.childSeq[1]).toBe('tool(FLAT)'); // 旧形态原样：卡前段无 header、工具裸露
    expect(c.headers).toHaveLength(1);
    expect(c.headers[0].text).toContain('工具 2 次'); // 仅后段入组（ask 工具 + Bash#2）
  });

  test('A5 busyTail 重载→live 续跑：工作行锚轮顶、终态整轮一枚 header', async ({ page }) => {
    const msgs = [...HEAD_MSGS, { type: 'user', text: '方向 A', answerOf: 'req-ax-1', timestamp: T0 + 5000 },
      { type: 'tool', label: 'Bash\n  (npm test)', summary: '成功', content: 'ok', isError: false, input: { command: 'npm test' } }];
    await page.evaluate((m) => window.__axReplay(m, { busyTail: true }), msgs);
    await page.evaluate(() => window.__axCallStart('Bash')); // live 续跑工具——工作行落位
    const lineUp = await page.evaluate(() => window.__axCensus());
    expect(lineUp.childSeq[1]).toBe('workline'); // 锚在问句行之后=轮顶（回答行不开轮）
    await page.evaluate(() => window.__axDone('Bash\n  (echo done)', '成功', 'done', false, '{"command":"echo done"}'));
    await page.evaluate(() => window.__axFinishTurn(900, 'test-model', '续跑完成。'));
    const c = await page.evaluate(() => window.__axCensus());
    expect(c.headers).toHaveLength(1);
    expect(c.headers[0].text).toContain('工具 3 次');
    expect(c.tools.every(x => x.tucked)).toBe(true);
    expect(c.childSeq[1]).toBe('header');
  });
});

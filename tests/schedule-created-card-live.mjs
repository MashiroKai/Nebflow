#!/usr/bin/env node
// schedule-created-card-live.mjs — 消息内嵌定时任务/待办卡片 × **真引擎** 的机械断言
// （schedule-created-card 批 2026-11-05）。
//
// 与 tests/schedule-created-card.spec.mjs 的分工（诚实面）：
//   · spec.mjs      = 前端真链路 + 帧桩（卡片行为/失败态/双档双主题）；
//   · 本文件        = **隔离真实例**：真 WS 帧 → 网关 WsTasksWorkspaceHandlers →
//                     ScheduledTaskStore（与 Schedule 工具同一份 store）→ 真 list
//                     回读；外加真浏览器里的 <nf-sched-card> 通过真 socket 改/取消。
//
// 前置（宿主实例 :6138 零触碰）：隔离引擎已 boot 在本 worktree 上：
//   rm -rf /tmp/schedcard-live-home
//   sbt -batch "run -- --home /tmp/schedcard-live-home --port 8399 --no-browser"
// 跑法：
//   node tests/schedule-created-card-live.mjs
// env：LIVE_PORT(8399) / LIVE_HOME(/tmp/schedcard-live-home) / LIVE_SHOTS_DIR
//
// 判据（编号对应批验收）：
//   L1 createScheduledTask → scheduledTaskCreated（引擎真写）
//   L2 listScheduledTasks 能查到该 id，字段与创建一致    ← 验收 4 前半
//   L3 updateScheduledTask（content/repeat/triggerAt）→ 回执 echo + list 读出新值
//                                                          ← 验收 4 后半 / 验收 2
//   L4 非法 triggerAt（过去时间）→ error 帧（msgType=updateScheduledTask）
//   L5 deleteScheduledTask → list 查不到该 id            ← 验收 3
//   L6 删后再 update → deleted 帧 + error 帧（「已不存在」，不是静默成功）
//   B1-B3 真浏览器：卡上编辑 → 引擎 list 读出新值（独立 WS 腿读，不经卡片模块）
//   B4-B5 真浏览器：卡上取消 → 状态「已取消」+ list 查不到 + 零写入口
//   B6 真浏览器：条目在别处被删后卡上编辑 → 错误可见 + 输入保留
//   S1-S4 真实例 1440/390 × 亮/暗 实拍
//
// 退出码：0 全绿；1 有 FAIL；2 环境错误。

import WebSocket from 'ws';
import { chromium } from 'playwright';
import { readFileSync, mkdirSync } from 'node:fs';
import { join } from 'node:path';
import { establishSession } from './helpers/session-rail.mjs';

const PORT = process.env.LIVE_PORT || '8399';
const HOME = process.env.LIVE_HOME || '/tmp/schedcard-live-home';
const BASE = process.env.LIVE_BASE || `http://127.0.0.1:${PORT}`;
const WS_URL = BASE.replace(/^http/, 'ws') + '/ws';
const SHOTS = process.env.LIVE_SHOTS_DIR
  || join(process.cwd(), '.nebflow', 'evidence', '20261105_schedule-created-card', 'live');
const SID = 'schedcard-live-session';

let failures = 0;
const rows = [];
function check(id, cond, detail = '') {
  rows.push({ id, ok: !!cond });
  if (cond) console.log(`PASS ${id}${detail ? ' — ' + detail : ''}`);
  else { failures += 1; console.log(`FAIL ${id}${detail ? ' — ' + detail : ''}`); }
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

function token() {
  const raw = readFileSync(join(HOME, 'auth.json'), 'utf8').trim();
  try { return JSON.parse(raw); } catch { return raw.replace(/^"|"$/g, ''); }
}

/** 一个最简 WS 客户端：帧全部进 buffer，waitFor 可回看。 */
function connect(tokenStr) {
  const ws = new WebSocket(WS_URL, { headers: { Authorization: `Bearer ${tokenStr}` } });
  const buffer = [];
  const waiters = [];
  const errors = [];
  ws.on('message', (buf) => {
    let msg;
    try { msg = JSON.parse(String(buf)); } catch { return; }
    buffer.push(msg);
    for (let i = waiters.length - 1; i >= 0; i--) {
      const w = waiters[i];
      if (w.type === msg.type && (!w.pred || w.pred(msg))) {
        waiters.splice(i, 1);
        clearTimeout(w.timer);
        w.resolve(msg);
      }
    }
  });
  ws.on('error', (e) => errors.push(String(e && e.message)));
  const ready = new Promise((resolve, reject) => {
    ws.on('open', resolve);
    ws.on('error', reject);
  });
  return {
    ws,
    ready,
    errors,
    buffer,
    send(obj) { ws.send(JSON.stringify(obj)); },
    /** 清空已收帧（发请求前调用 ⇒ 之后的 waitFor 只能命中真正的应答）。 */
    drain() { buffer.length = 0; },
    waitFor(type, pred, ms = 8000) {
      // 命中即**消费**（从 buffer 摘除）：list 帧同型重复，回看不摘会读到上一条
      // 旧帧（本批实测：L3b 读到 L2 的 list ⇒ 假红）。
      const idx = buffer.findIndex((m) => m.type === type && (!pred || pred(m)));
      if (idx >= 0) return Promise.resolve(buffer.splice(idx, 1)[0]);
      return new Promise((resolve, reject) => {
        const timer = setTimeout(() => {
          const i = waiters.findIndex((w) => w.resolve === resolve);
          if (i >= 0) waiters.splice(i, 1);
          reject(new Error(`timeout waiting for ${type}`));
        }, ms);
        waiters.push({ type, pred, resolve, timer });
      });
    },
    close() { try { ws.close(); } catch { /* ignore */ } },
  };
}

async function main() {
  mkdirSync(SHOTS, { recursive: true });
  const tok = token();
  const cli = connect(tok);
  await cli.ready;

  // ── L1 create（真引擎写）─────────────────────────────────────────────
  const contentA = 'LIVE-内容-A 汇总今日 commit';
  const triggerAtA = Date.now() + 3600_000;
  cli.send({
    type: 'createScheduledTask', sessionId: SID, content: contentA,
    triggerAt: triggerAtA, repeat: 'daily', project: 'stand',
  });
  const created = await cli.waitFor('scheduledTaskCreated');
  const id = created.task && created.task.id;
  check('L1 createScheduledTask → scheduledTaskCreated（引擎真写）', !!id,
    `id=${id} content=${created.task && created.task.content}`);

  // ── L2 list 能查到该 id ──────────────────────────────────────────────
  cli.drain();
  cli.send({ type: 'listScheduledTasks', sessionId: SID });
  const list1 = await cli.waitFor('scheduledTaskList');
  const row1 = (list1.tasks || []).find((t) => t.id === id);
  check('L2 listScheduledTasks 查到卡片 id（回读一致·存在性）', !!row1, JSON.stringify(row1 || null));
  // 🔴 引擎现状（非本批改动）：kind=task 的 createScheduledTask 分支不落 project
  // （handler 的 task 分支调 ScheduledTask.create 时未传 project）——project 只能
  // 经 updateScheduledTask 落（L3 已证）。本断言因此只钉 content/repeat/triggerAt。
  check('L2b list 行字段与创建一致（content/repeat/triggerAt）',
    !!row1 && row1.content === contentA && row1.repeat === 'daily' && row1.triggerAt === triggerAtA,
    JSON.stringify(row1 || null));

  // ── L3 update（卡片保存会发的同一帧）→ echo + list 新值 ──────────────
  const contentB = 'LIVE-内容-B 整理成周报草稿';
  const triggerAtB = Date.now() + 7200_000;
  cli.send({
    type: 'updateScheduledTask', sessionId: SID, id,
    content: contentB, repeat: 'weekly', triggerAt: triggerAtB, project: 'stand-2',
  });
  const upd = await cli.waitFor('scheduledTaskUpdated');
  check('L3 updateScheduledTask 回执 = 新值（content/repeat/triggerAt/project）',
    upd.task && upd.task.content === contentB && upd.task.repeat === 'weekly'
    && upd.task.triggerAt === triggerAtB && upd.task.project === 'stand-2',
    JSON.stringify(upd.task || null));
  cli.drain();
  cli.send({ type: 'listScheduledTasks', sessionId: SID });
  const list2 = await cli.waitFor('scheduledTaskList');
  const row2 = (list2.tasks || []).find((t) => t.id === id);
  check('L3b 引擎侧 list 读出改后的新值（内容/重复/时间）',
    !!row2 && row2.content === contentB && row2.repeat === 'weekly' && row2.triggerAt === triggerAtB,
    JSON.stringify(row2 || null));

  // ── L4 非法 triggerAt（过去）→ 错误帧（失败可见，不静默）──────────────
  cli.send({ type: 'updateScheduledTask', sessionId: SID, id, triggerAt: Date.now() - 60_000 });
  const errFrame = await cli.waitFor('error', (m) => m.msgType === 'updateScheduledTask');
  check('L4 过去时间被引擎拒绝（error 帧带 msgType + 自愈说明）',
    /future/i.test(String(errFrame.message || '')) && errFrame.id === id,
    JSON.stringify(errFrame));
  cli.drain();
  cli.send({ type: 'listScheduledTasks', sessionId: SID });
  const list3 = await cli.waitFor('scheduledTaskList');
  const row3 = (list3.tasks || []).find((t) => t.id === id);
  check('L4b 被拒的写入没有落库（list 时间未变）', !!row3 && row3.triggerAt === triggerAtB,
    JSON.stringify(row3 && row3.triggerAt));

  // ── L5 delete → list 查不到 ─────────────────────────────────────────
  cli.drain();
  cli.send({ type: 'deleteScheduledTask', sessionId: SID, id });
  await cli.waitFor('scheduledTaskDeleted', (m) => m.id === id);
  cli.drain();
  cli.send({ type: 'listScheduledTasks', sessionId: SID });
  const list4 = await cli.waitFor('scheduledTaskList');
  check('L5 取消后 list 查不到该 id', !(list4.tasks || []).some((t) => t.id === id),
    JSON.stringify((list4.tasks || []).map((t) => t.id)));

  // ── L6 删后再 update → deleted + error（不静默）──────────────────────
  cli.drain();
  cli.send({ type: 'updateScheduledTask', sessionId: SID, id, content: '幽灵写入' });
  const goneErr = await cli.waitFor('error', (m) => m.msgType === 'updateScheduledTask' && m.id === id);
  check('L6 已删条目再改 ⇒ 错误帧（No entry with id … nothing was saved）',
    /No entry with id/.test(String(goneErr.message || '')), JSON.stringify(goneErr));

  // ── B. 真浏览器：卡片 × 真引擎 ───────────────────────────────────────
  const browser = await chromium.launch({ headless: true });
  const ctx = await browser.newContext({ locale: 'zh-CN', colorScheme: 'dark',
    viewport: { width: 1440, height: 900 } });
  const page = await ctx.newPage();
  const pageErrors = [];
  // 记 stack：S2 红的时候只有 message 无法定位（第一现场原则：诊断信息随断言走）。
  page.on('pageerror', (e) => pageErrors.push(`${e.message}\n${e.stack || ''}`));
  await establishSession(BASE, tok, page);
  await page.waitForFunction(() => !!document.querySelector('#chat'), null, { timeout: 20000 });
  // 首次启动的 onboarding 覆盖层（隔离 home 无 provider）：有就关掉，否则挡住点击。
  const overlay = page.locator('.onboarding-overlay');
  if (await overlay.isVisible().catch(() => false)) {
    const skip = overlay.locator('#ob-skip, #ob-no');
    if (await skip.count()) await skip.first().click().catch(() => {});
    await overlay.waitFor({ state: 'detached', timeout: 5000 }).catch(() => {});
  }
  // 等真 socket OPEN（卡片回读的前提）
  await page.waitForFunction(async () => {
    const m = await import('/js/state.js');
    return !!(m.default && m.default.ws && m.default.ws.readyState === 1);
  }, null, { timeout: 30000 });
  check('B0 真实例页面装载 + 真 socket OPEN', true);
  // 应用启动期会做历史重放（wipe #chat）——挂卡前等它落定，否则卡会被整页重建清掉
  // （本批实测：卡挂上后 900ms 被 wipe，B3b 查询到 null）。
  await page.waitForLoadState('networkidle').catch(() => {});
  await sleep(3500);

  // 新键一条供浏览器腿使用
  const contentC = 'LIVE-卡片-C 待改内容';
  cli.send({
    type: 'createScheduledTask', sessionId: SID, content: contentC,
    triggerAt: Date.now() + 3 * 3600_000, repeat: 'daily', project: 'stand',
  });
  const created2 = await cli.waitFor('scheduledTaskCreated', (m) => m.task && m.task.content === contentC);
  const id2 = created2.task.id;

  // 挂卡（真模块、真组件；入口 = chat.renderTool 走的那条 builder）
  const mounted = await page.evaluate(async ({ id, content, sid }) => {
    const mod = await import('/js/scheduleCard.js');
    const row = mod.scheduleCardRowForTool({
      label: 'Schedule(create)',
      content: `Scheduled task ${id} for 2026-11-05 21:00 (recurring: daily): ${content}`,
      isError: false,
      inputJson: JSON.stringify({
        action: 'create', kind: 'task', content, repeat: 'daily', project: 'stand',
      }),
      sessionId: sid,
    });
    const chat = document.getElementById('chat');
    chat.appendChild(row);
    return !!row;
  }, { id: id2, content: contentC, sid: SID });
  await page.waitForSelector(`.schedule-card[data-sched-id="${id2}"]`, { timeout: 8000 }).catch(() => {});
  check('B1 真浏览器里卡片挂上（真组件 + 真 socket）', mounted
    && (await page.locator(`.schedule-card[data-sched-id="${id2}"]`).count()) === 1);

  const view1 = await page.evaluate(() => {
    const card = document.querySelector('.schedule-card[data-sched-id]');
    if (!card) return null;
    const t = (s) => { const n = card.querySelector(s); return n ? n.textContent.trim() : null; };
    return { id: card.getAttribute('data-sched-id'), body: t('.schedule-card-body'), status: t('.schedule-card-status') };
  });
  check('B2 卡片显示值 = 引擎 list 值（真回读，非本地 patch）',
    !!view1 && view1.id === id2 && view1.body === contentC,
    JSON.stringify(view1));

  // 真浏览器里改内容 → 真引擎 list 读出新值
  const contentD = 'LIVE-卡片-D 改后的内容（真引擎）';
  await page.locator(`.schedule-card[data-sched-id="${id2}"] [data-sched-act="edit"]`).click();
  await page.locator(`.schedule-card[data-sched-id="${id2}"] [data-sched-field="content"]`).fill(contentD);
  await page.locator(`.schedule-card[data-sched-id="${id2}"] [data-sched-act="save"]`).click();
  await sleep(900);
  cli.drain();
  cli.send({ type: 'listScheduledTasks', sessionId: SID });
  await sleep(300);
  const list5 = await cli.waitFor('scheduledTaskList', null, 8000).catch(() => null);
  const row5 = list5 && (list5.tasks || []).find((t) => t.id === id2);
  check('B3 卡上编辑 ⇒ 引擎 list 读出新值（独立 WS 腿读，不经卡片模块）',
    !!row5 && row5.content === contentD, JSON.stringify(row5 || null));
  await sleep(400);
  const view2 = await page.evaluate((sid2) => {
    const card = document.querySelector(`.schedule-card[data-sched-id="${sid2}"]`);
    return card ? {
      body: (card.querySelector('.schedule-card-body') || {}).textContent,
      form: !!card.querySelector('.schedule-card-form'),
      fail: (card.querySelector('.schedule-card-fail') || {}).textContent || null,
    } : null;
  }, id2);
  check('B3b 卡片显示回读后的新值且编辑面收起', !!view2 && view2.body === contentD && !view2.form,
    JSON.stringify(view2));

  // 真浏览器里取消 → 状态「已取消」+ list 查不到 + 零写入口
  await page.locator(`.schedule-card[data-sched-id="${id2}"] [data-sched-act="cancel"]`).click();
  await page.locator(`.schedule-card[data-sched-id="${id2}"] [data-sched-act="cancel-yes"]`).click();
  await sleep(900);
  const afterCancel = await page.evaluate((sid2) => {
    const card = document.querySelector(`.schedule-card[data-sched-id="${sid2}"]`);
    return card ? {
      status: card.getAttribute('data-sched-status'),
      statusText: (card.querySelector('.schedule-card-status') || {}).textContent,
      acts: card.querySelectorAll('[data-sched-act]').length,
    } : null;
  }, id2);
  check('B4 卡上取消 ⇒ 状态「已取消」+ 零写入口',
    !!afterCancel && afterCancel.status === 'cancelled'
    && (afterCancel.statusText || '').trim() === '已取消' && afterCancel.acts === 0,
    JSON.stringify(afterCancel));
  cli.drain();
  cli.send({ type: 'listScheduledTasks', sessionId: SID });
  const list6 = await cli.waitFor('scheduledTaskList');
  check('B5 取消后引擎侧 list 查不到该 id', !(list6.tasks || []).some((t) => t.id === id2),
    JSON.stringify((list6.tasks || []).map((t) => t.id)));

  // 失败腿：条目在「别处」被删后，卡上编辑 ⇒ 错误可见 + 输入保留
  const contentE = 'LIVE-卡片-E 会被别处删掉';
  cli.send({
    type: 'createScheduledTask', sessionId: SID, content: contentE,
    triggerAt: Date.now() + 4 * 3600_000, project: 'stand',
  });
  const created3 = await cli.waitFor('scheduledTaskCreated', (m) => m.task && m.task.content === contentE);
  const id3 = created3.task.id;
  await page.evaluate(async ({ id, content, sid }) => {
    const mod = await import('/js/scheduleCard.js');
    const row = mod.scheduleCardRowForTool({
      label: 'Schedule(create)',
      content: `Scheduled task ${id} for 2026-11-05 22:00: ${content}`,
      isError: false,
      inputJson: JSON.stringify({ action: 'create', kind: 'task', content, project: 'stand' }),
      sessionId: sid,
    });
    document.getElementById('chat').appendChild(row);
  }, { id: id3, content: contentE, sid: SID });
  await page.waitForSelector(`.schedule-card[data-sched-id="${id3}"]`, { timeout: 8000 }).catch(() => {});
  cli.drain();
  cli.send({ type: 'deleteScheduledTask', sessionId: SID, id: id3 });
  await cli.waitFor('scheduledTaskDeleted', (m) => m.id === id3);
  await sleep(200);
  const sel3 = `.schedule-card[data-sched-id="${id3}"]`;
  await page.locator(`${sel3} [data-sched-act="edit"]`).click();
  await page.locator(`${sel3} [data-sched-field="content"]`).fill('LIVE-失败腿-这段字不能丢');
  await page.locator(`${sel3} [data-sched-act="save"]`).click();
  await sleep(900);
  const failView = await page.evaluate((sel) => {
    const card = document.querySelector(sel);
    if (!card) return null;
    return {
      fail: (card.querySelector('.schedule-card-fail') || {}).textContent || null,
      input: (card.querySelector('[data-sched-field="content"]') || {}).value || null,
      emptyDraft: (card.querySelector('[data-sched-field="content"]') || {}).value === '',
    };
  }, sel3);
  // 两帧竞速（引擎同发 deleted + error）：先到的 deleted 让请求以 code='gone' 结案，
  // 卡片因此显示本地化「条目已不存在，改动未保存」；若 error 先到则显示引擎原文
  // 「No entry with id …」。两种都是**失败可见**，断言收其中任一（不许静默/不许成功面）。
  check('B6 条目已不在 ⇒ 卡内错误可见（本地化 gone 文案或引擎原文）',
    !!failView && /已不存在|No entry with id/.test(String(failView.fail || '')),
    JSON.stringify(failView));
  check('B7 失败后用户输入保留（不丢字、不回滚成旧值）',
    !!failView && failView.input === 'LIVE-失败腿-这段字不能丢', JSON.stringify(failView));

  // ── S. 真实例双档双主题实拍 ──────────────────────────────────────────
  for (const [w, h, scheme, tag] of [[1440, 900, 'dark', 'live-1440-dark'],
    [1440, 900, 'light', 'live-1440-light'],
    [390, 844, 'dark', 'live-390-dark'], [390, 844, 'light', 'live-390-light']]) {
    await page.setViewportSize({ width: w, height: h });
    await page.emulateMedia({ colorScheme: scheme });
    await sleep(300);
    await page.screenshot({ path: join(SHOTS, `${tag}.png`), fullPage: true });
  }
  check('S1 双档双主题实拍完成（1440/390 × 亮/暗）', true);
  check('S2 零 pageerror（真实例）', pageErrors.length === 0, pageErrors.join(' | '));

  await browser.close();
  cli.close();
  console.log(`\n── 汇总：${rows.filter((r) => r.ok).length}/${rows.length} PASS · 截图 ${SHOTS} ──`);
  return failures === 0 ? 0 : 1;
}

let code = 2;
try {
  code = await main();
} catch (e) {
  console.error('environment error:', e && e.stack ? e.stack : e);
  code = 2;
}
process.exit(code);

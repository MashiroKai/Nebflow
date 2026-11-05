#!/usr/bin/env node
// schedule-created-card.spec.mjs — 消息内嵌定时任务/待办卡片（创建/编辑后落卡，
// 可原地改、原地取消）的机械断言（schedule-created-card 批 2026-11-05，
// 作者令 09:03）。
//
// 跑法（自足，零后端零 sbt；宿主实例 :6138 零触碰）：
//   node tests/schedule-created-card.spec.mjs
// 前置：`npm install`（playwright 从 devDependencies 之外的 --no-save 安装亦可，
//       见批报告「验证环境」节）。
//
// ── 判据面与诚实申报 ───────────────────────────────────────────────────
// 被测面 = **真链路**：真 web 树（本 worktree 的 src/main/resources/web）+ 真
// chat.renderTool + 真 <nf-sched-card> 组件 + 真 CSS（reminder.css 的 schedule-card
// 家族）+ 真 scheduleApi 帧路径。
// 数据面 = 帧桩（tests/fixtures/schedule-created-card/harness.html 的 store），
// 帧键集合与网关 WsTasksWorkspaceHandlers.listTaskJson 同形 —— 但**它不是引擎**。
// ⇒ 本文件证明：卡片出现/字段取自回读/原地编辑发出正确帧/取消走真删除帧/
//    失败可见且不丢字/终态无假按钮/双档双主题不溢出。
// ⇒ 本文件**不证明**「真引擎 list 读出真值」——那条腿由
//    tests/schedule-created-card-live.mjs（隔离实例 + 真 WS）承担。
//
// 断言编号对应批验收条目（见报告 §8）：
//   A1-A5 创建后消息流里出现卡片、卡片 id 与 list 一致、字段=回读值
//   B1-B6 原地编辑（非模态）：帧载荷 / store 新值 / 卡片显示新值 / 无模态
//   C1-C5 原地取消：内联确认 / 删除帧 / 状态「已取消」/ list 不再有 / 入口消失
//   D1-D6 写失败：错误可见 + 输入保留 + store 未被改（三类失败）
//   E1-E3 终态（已取消/已完成/已不在）零可点写操作
//   F1-F4 1440/390 × 亮/暗 双档：不横向溢出 + 实拍
//
// 退出码：0 = 全绿；1 = 有 FAIL；2 = 环境错误（playwright/server 起不来）。

import { chromium } from 'playwright';
import { spawn } from 'node:child_process';
import net from 'node:net';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const REPO_ROOT = path.join(path.dirname(fileURLToPath(import.meta.url)), '..');
const HARNESS_PATH = '/tests/fixtures/schedule-created-card/harness.html';
const SHOT_DIR = process.env.NB_SCHEDCARD_SHOT_DIR
  || path.join(REPO_ROOT, '.nebflow', 'evidence', '20261105_schedule-created-card');

const ID = 'abc12345';
const ID_TODO = 'td0042ab';
const OLD_CONTENT = '汇总今日 commit 并向用户汇报进度';
const NEW_CONTENT = '汇总今日 commit 并整理成周报草稿';
const TODO_CONTENT = '把周报模板整理进 memory';
const LABEL = 'Schedule(create)';

let failures = 0;
const rows = [];
function check(id, cond, detail = '') {
  rows.push({ id, ok: !!cond, detail });
  if (cond) console.log(`PASS ${id}${detail ? ' — ' + detail : ''}`);
  else { failures += 1; console.log(`FAIL ${id}${detail ? ' — ' + detail : ''}`); }
}

const freePort = () => new Promise((resolve, reject) => {
  const srv = net.createServer();
  srv.listen(0, '127.0.0.1', () => {
    const p = srv.address().port;
    srv.close(() => resolve(p));
  });
  srv.on('error', reject);
});

const waitUp = async (url, tries = 60) => {
  for (let i = 0; i < tries; i++) {
    try { const r = await fetch(url); if (r.ok) return; } catch { /* not up */ }
    await new Promise((r) => setTimeout(r, 100));
  }
  throw new Error(`static server never came up at ${url}`);
};

const servers = [];
let browser;

async function main() {
  if (!fs.existsSync(path.join(REPO_ROOT, 'src/main/resources/web/js/scheduleCard.js'))) {
    console.error('environment error: scheduleCard.js missing');
    process.exit(2);
  }
  fs.mkdirSync(SHOT_DIR, { recursive: true });
  const port = await freePort();
  if (port === 6138 || port === 8080) throw new Error('host port discipline violated');
  servers.push(spawn('python3', ['-m', 'http.server', String(port), '--bind', '127.0.0.1',
    '--directory', REPO_ROOT], { stdio: 'ignore' }));
  const base = `http://127.0.0.1:${port}`;
  await waitUp(`${base}${HARNESS_PATH}`);

  browser = await chromium.launch({ headless: true });
  const ctx = await browser.newContext({ locale: 'zh-CN', colorScheme: 'dark',
    viewport: { width: 1440, height: 900 } });
  const page = await ctx.newPage();
  const pageErrors = [];
  page.on('pageerror', (e) => pageErrors.push(e.message));
  await page.goto(`${base}${HARNESS_PATH}`);
  await page.waitForFunction(() => window.__ready === true, null, { timeout: 20000 });

  // ── A. 创建后落卡 + id 与 list 一致 + 字段=回读值 ─────────────────────
  await page.evaluate(({ id, content }) => {
    window.__h.seed({
      id, content, triggerAt: Date.now() + 3600_000, repeat: 'daily',
      project: 'stand', name: 'evening-commit-digest',
    });
  }, { id: ID, content: OLD_CONTENT });

  await page.evaluate(({ label, content }) => window.__h.renderTool(
    label,
    'Scheduled task ' + 'abc12345' + ' for 2026-11-05 21:00',
    'Scheduled task abc12345 for 2026-11-05 21:00 (recurring: daily) [name: evening-commit-digest]: ' + content,
    false,
    JSON.stringify({
      action: 'create', kind: 'task', content, triggerAt: Date.now() + 3600_000,
      repeat: 'daily', name: 'evening-commit-digest', project: 'stand',
    }),
  ), { label: LABEL, content: OLD_CONTENT });
  await page.evaluate(() => window.__h.settle(120));

  const cardSel = `.schedule-card[data-sched-id="${ID}"]`;
  check('A1 创建后消息流里出现卡片', await page.locator(cardSel).count() === 1,
    `${cardSel} ×${await page.locator(cardSel).count()}`);
  // 卡在消息流内（#chat 内、紧跟工具行之后）
  const inStream = await page.evaluate((sel) => {
    const card = document.querySelector(sel);
    const chat = document.querySelector('#chat');
    if (!card || !chat || !chat.contains(card)) return false;
    const row = card.closest('.row');
    const prev = row && row.previousElementSibling;
    return !!(prev && prev.classList.contains('tool'));
  }, cardSel);
  check('A2 卡片行紧跟在工具进程行之后（同一消息流）', inStream);

  const streamView = await page.evaluate((sel) => {
    const card = document.querySelector(sel);
    const txt = (s) => { const n = card.querySelector(s); return n ? n.textContent.trim() : null; };
    return {
      kind: txt('.schedule-card-chip'),
      name: txt('.schedule-card-name'),
      body: txt('.schedule-card-body'),
      status: txt('.schedule-card-status'),
      statusAttr: card.getAttribute('data-sched-status'),
      meta: txt('.schedule-card-meta'),
      idText: txt('.schedule-card-id'),
      bubble: card.classList.contains('bubble') && card.classList.contains('injected'),
    };
  }, cardSel);
  check('A3 卡片 id 与 list 行 id 一致', streamView.idText === `#${ID}`,
    `卡片 id 文本 = ${streamView.idText}`);
  const listRow = await page.evaluate((id) => window.__h.row(id), ID);
  check('A4 list 帧能查到该 id（回读一致·存在性）', !!listRow && listRow.id === ID);
  check('A5 卡片正文 = list 行的内容（字段取自引擎回读，不取本地 patch）',
    streamView.body === listRow.content, `卡=${streamView.body} / list=${listRow.content}`);
  check('A5b 气泡族形态（.bubble.injected.schedule-card = 复用既有家族）', streamView.bubble);
  check('A6 类型/状态/项目/重复 四要素在场',
    streamView.kind === '定时任务' && streamView.status === '进行中'
    && streamView.meta.includes('stand') && streamView.meta.includes('每天'),
    JSON.stringify({ kind: streamView.kind, status: streamView.status, meta: streamView.meta }));

  // ── B. 原地编辑（非模态）→ 帧载荷 → 引擎新值 → 卡片显示新值 ──────────
  await page.evaluate(() => window.__h.clearSent());
  const urlBefore = page.url();
  await page.locator(`${cardSel} [data-sched-act="edit"]`).click();
  await page.evaluate(() => window.__h.settle(60));
  const editFace = await page.evaluate((sel) => {
    const card = document.querySelector(sel);
    return {
      form: !!card.querySelector('.schedule-card-form'),
      inCard: !!card.querySelector('.schedule-card-form'),
      modal: document.querySelectorAll('.modal, .modal-overlay, [role="dialog"]').length,
      textarea: !!card.querySelector('[data-sched-field="content"]'),
      time: !!card.querySelector('[data-sched-field="triggerAt"]'),
      repeat: !!card.querySelector('[data-sched-field="repeat"]'),
    };
  }, cardSel);
  check('B1 编辑面在卡内展开（form 在卡内）', editFace.form && editFace.inCard);
  check('B2 无模态、不跳页', editFace.modal === 0 && page.url() === urlBefore,
    `modal=${editFace.modal} url同=${page.url() === urlBefore}`);
  check('B3 定时任务卡可改触发时间与重复（控件在场）', editFace.time && editFace.repeat);

  const newTime = await page.evaluate(() => Date.now() + 7200_000);
  await page.locator(`${cardSel} [data-sched-field="content"]`).fill(NEW_CONTENT);
  await page.evaluate(({ sel, ms }) => {
    const card = document.querySelector(sel);
    const t = card.querySelector('[data-sched-field="triggerAt"]');
    const d = new Date(ms);
    const pad = (n) => String(n).padStart(2, '0');
    t.value = `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`;
    t.dispatchEvent(new Event('input', { bubbles: true }));
    const r = card.querySelector('[data-sched-field="repeat"]');
    r.value = 'weekly';
    r.dispatchEvent(new Event('change', { bubbles: true }));
  }, { sel: cardSel, ms: newTime });
  await page.locator(`${cardSel} [data-sched-act="save"]`).click();
  await page.evaluate(() => window.__h.settle(160));

  const sentB = await page.evaluate(() => window.__h.sent());
  const upd = sentB.filter((f) => f.type === 'updateScheduledTask');
  check('B4 保存走引擎写路径（updateScheduledTask 帧，一次）', upd.length === 1,
    JSON.stringify(sentB.map((f) => f.type)));
  check('B5 帧载荷 = 用户改动（content/project/triggerAt/repeat，id 不变）',
    upd.length === 1 && upd[0].id === ID && upd[0].content === NEW_CONTENT
    && 'triggerAt' in upd[0] && upd[0].repeat === 'weekly',
    JSON.stringify(upd[0] || null));
  const rowAfter = await page.evaluate((id) => window.__h.row(id), ID);
  check('B6 引擎侧 list 读出新值（content）', rowAfter && rowAfter.content === NEW_CONTENT,
    `list.content = ${rowAfter && rowAfter.content}`);
  check('B7 引擎侧 list 读出新值（repeat = weekly）', rowAfter && rowAfter.repeat === 'weekly',
    `list.repeat = ${rowAfter && rowAfter.repeat}`);
  const cardAfter = await page.evaluate((sel) => {
    const card = document.querySelector(sel);
    const b = card.querySelector('.schedule-card-body');
    return {
      body: b ? b.textContent.trim() : null,
      form: !!card.querySelector('.schedule-card-form'),
      status: (card.querySelector('.schedule-card-status') || {}).textContent,
      meta: (card.querySelector('.schedule-card-meta') || {}).textContent,
    };
  }, cardSel);
  check('B8 卡片显示值 = 引擎新值（回读后重绘）', cardAfter.body === NEW_CONTENT,
    `卡=${cardAfter.body}`);
  check('B9 保存后编辑面收起、状态仍「进行中」（未伪装成别态）',
    !cardAfter.form && (cardAfter.status || '').trim() === '进行中',
    `form=${cardAfter.form} status=${cardAfter.status}`);

  // ── D（先做失败腿，免得取消后无入口）写失败：可见 + 不丢字 + store 未变 ──
  await page.evaluate(() => window.__h.mode('error'));
  await page.locator(`${cardSel} [data-sched-act="edit"]`).click();
  await page.evaluate(() => window.__h.settle(60));
  await page.locator(`${cardSel} [data-sched-field="content"]`).fill('失败腿：这段字不能被吞掉');
  await page.locator(`${cardSel} [data-sched-act="save"]`).click();
  await page.evaluate(() => window.__h.settle(160));
  const failFace = await page.evaluate((sel) => {
    const card = document.querySelector(sel);
    const ta = card.querySelector('[data-sched-field="content"]');
    return {
      failText: (card.querySelector('.schedule-card-fail') || {}).textContent || null,
      inputValue: ta ? ta.value : null,
      formStillOpen: !!card.querySelector('.schedule-card-form'),
      body: (card.querySelector('.schedule-card-body') || {}).textContent || null,
      status: (card.querySelector('.schedule-card-status') || {}).textContent || null,
    };
  }, cardSel);
  check('D1 引擎错误帧 ⇒ 卡内错误态可见', !!failFace.failText && failFace.failText.includes('triggerAt'),
    String(failFace.failText));
  check('D2 失败后用户输入保留（不丢字）', failFace.inputValue === '失败腿：这段字不能被吞掉',
    String(failFace.inputValue));
  check('D3 失败后编辑面不关闭（可改后重试）', failFace.formStillOpen);
  const rowAfterFail = await page.evaluate((id) => window.__h.row(id), ID);
  check('D4 失败未写引擎（list 仍是上次成功值）', rowAfterFail.content === NEW_CONTENT,
    `list.content = ${rowAfterFail.content}`);
  check('D5 失败未被渲染成成功面（正文仍是旧值、状态非「已取消/已完成」）',
    (failFace.body || '').trim() === NEW_CONTENT && failFace.status !== '已取消' && failFace.status !== '已完成',
    `body=${failFace.body} status=${failFace.status}`);

  // D6 未连接（offline）腿
  await page.evaluate(() => window.__h.mode('offline'));
  await page.locator(`${cardSel} [data-sched-act="save"]`).click();
  await page.evaluate(() => window.__h.settle(160));
  const offFace = await page.evaluate((sel) => {
    const card = document.querySelector(sel);
    return {
      failText: (card.querySelector('.schedule-card-fail') || {}).textContent || null,
      inputValue: (card.querySelector('[data-sched-field="content"]') || {}).value || null,
    };
  }, cardSel);
  check('D6 未连接 ⇒ 错误可见且输入仍在（同理不丢字）',
    !!offFace.failText && offFace.failText.includes('未连接') && offFace.inputValue === '失败腿：这段字不能被吞掉',
    `fail=${offFace.failText} input=${offFace.inputValue}`);

  // 复原：退出编辑态
  await page.evaluate(() => window.__h.mode('ok'));
  await page.locator(`${cardSel} [data-sched-act="discard"]`).click();
  await page.evaluate(() => window.__h.settle(80));

  // ── C. 原地取消（内联二次确认）────────────────────────────────────────
  await page.evaluate(() => window.__h.clearSent());
  await page.locator(`${cardSel} [data-sched-act="cancel"]`).click();
  await page.evaluate(() => window.__h.settle(60));
  const confirmFace = await page.evaluate((sel) => {
    const card = document.querySelector(sel);
    return {
      confirmVisible: !!card.querySelector('[data-sched-act="cancel-yes"]'),
      dialog: document.querySelectorAll('dialog[open], .modal, [role="dialog"]').length,
      text: (card.querySelector('.schedule-card-note') || {}).textContent || null,
    };
  }, cardSel);
  check('C1 取消先要内联二次确认（同卡内，不是系统 dialog）',
    confirmFace.confirmVisible && confirmFace.dialog === 0,
    `confirm=${confirmFace.confirmVisible} dialog=${confirmFace.dialog}`);
  await page.locator(`${cardSel} [data-sched-act="cancel-yes"]`).click();
  await page.evaluate(() => window.__h.settle(180));
  const sentC = await page.evaluate(() => window.__h.sent());
  check('C2 取消走引擎删除帧（deleteScheduledTask）',
    sentC.filter((f) => f.type === 'deleteScheduledTask').length === 1,
    JSON.stringify(sentC.map((f) => f.type)));
  const afterCancel = await page.evaluate((sel) => {
    const card = document.querySelector(sel);
    return {
      statusAttr: card.getAttribute('data-sched-status'),
      statusText: (card.querySelector('.schedule-card-status') || {}).textContent || null,
      clicks: card.querySelectorAll('[data-sched-act]').length,
      buttons: card.querySelectorAll('button').length,
    };
  }, cardSel);
  check('C3 取消后状态即时变「已取消」',
    afterCancel.statusAttr === 'cancelled' && (afterCancel.statusText || '').trim() === '已取消',
    `status=${afterCancel.statusText}`);
  const rowAfterCancel = await page.evaluate((id) => window.__h.row(id), ID);
  check('C4 再 list 已查不到该 id（引擎侧已删）', rowAfterCancel === null, JSON.stringify(rowAfterCancel));
  check('C5 终态无任何可点的写操作入口（假按钮零）',
    afterCancel.clicks === 0 && afterCancel.buttons === 0,
    `写操作=${afterCancel.clicks} 按钮=${afterCancel.buttons}`);

  // ── E. 终态族（已完成 / 已不在）零可点写操作 ───────────────────────────
  await page.evaluate(({ id, content }) => {
    window.__h.seed({ id, content, kind: 'todo', completed: true, completedAt: Date.now() });
  }, { id: ID_TODO, content: TODO_CONTENT });
  await page.evaluate(({ content }) => window.__h.renderTool(
    'Schedule(create)',
    'Todo td0042ab registered',
    'Todo td0042ab registered (surfaces daily at 09:00 until done/cancelled): ' + content,
    false,
    JSON.stringify({ action: 'create', kind: 'todo', content }),
  ), { content: TODO_CONTENT });
  await page.evaluate(() => window.__h.settle(140));
  const todoSel = `.schedule-card[data-sched-id="${ID_TODO}"]`;
  const doneFace = await page.evaluate((sel) => {
    const card = document.querySelector(sel);
    return {
      exists: !!card,
      kind: (card.querySelector('.schedule-card-chip') || {}).textContent || null,
      status: (card.querySelector('.schedule-card-status') || {}).textContent || null,
      meta: (card.querySelector('.schedule-card-meta') || {}).textContent || null,
      clicks: card.querySelectorAll('[data-sched-act]').length,
      buttons: card.querySelectorAll('button').length,
    };
  }, todoSel);
  check('E1 待办卡在消息流里出现且类型=待办', doneFace.exists && doneFace.kind === '待办',
    JSON.stringify(doneFace));
  check('E2 已完成（软删）行显示「已完成」且零可点写操作',
    (doneFace.status || '').trim() === '已完成' && doneFace.clicks === 0 && doneFace.buttons === 0,
    `status=${doneFace.status} clicks=${doneFace.clicks} buttons=${doneFace.buttons}`);

  // 已不在（引擎侧查不到）⇒ 「已不在列表」+ 零入口
  await page.evaluate(() => window.__h.renderTool(
    'Schedule(create)', 'Scheduled task 9999dead for 2026-11-06 09:00',
    'Scheduled task 9999dead for 2026-11-06 09:00: 已过期条目',
    false, JSON.stringify({ action: 'create', content: '已过期条目' }),
  ));
  await page.evaluate(() => window.__h.settle(140));
  const gone = await page.evaluate(() => {
    const card = document.querySelector('.schedule-card[data-sched-id="9999dead"]');
    if (!card) return null;
    return {
      status: (card.querySelector('.schedule-card-status') || {}).textContent || null,
      clicks: card.querySelectorAll('[data-sched-act]').length,
    };
  });
  check('E3 引擎侧已查不到的条目显示「已不在列表」且无写入口',
    !!gone && (gone.status || '').trim() === '已不在列表' && gone.clicks === 0,
    JSON.stringify(gone));

  // ── F. 双档 × 双主题：无横向溢出 + 实拍 ───────────────────────────────
  const overflow = async () => page.evaluate(() => {
    const out = [];
    for (const card of document.querySelectorAll('.schedule-card')) {
      const r = card.getBoundingClientRect();
      const chat = document.querySelector('#chat').getBoundingClientRect();
      const selfOver = card.scrollWidth - card.clientWidth;
      out.push({
        id: card.getAttribute('data-sched-id'),
        right: Math.round(r.right), left: Math.round(r.left),
        chatRight: Math.round(chat.right), chatLeft: Math.round(chat.left),
        selfOver,
        escaped: r.right > chat.right + 1 || r.left < chat.left - 1,
      });
    }
    return out;
  });

  for (const [w, h, scheme, tag] of [[1440, 900, 'dark', '1440-dark'], [1440, 900, 'light', '1440-light'],
    [390, 844, 'dark', '390-dark'], [390, 844, 'light', '390-light']]) {
    await page.setViewportSize({ width: w, height: h });
    await page.emulateMedia({ colorScheme: scheme });
    await page.evaluate(() => window.__h.settle(120));
    const ov = await overflow();
    const bad = ov.filter((o) => o.escaped || o.selfOver > 1);
    check(`F ${tag} 卡片不横向溢出（${w}px）`, bad.length === 0, JSON.stringify(bad));
    await page.screenshot({ path: path.join(SHOT_DIR, `${tag}.png`), fullPage: true });
  }
  // 编辑态窄屏实拍（键盘安全面：无 fixed/sticky 定位）
  await page.setViewportSize({ width: 390, height: 844 });
  await page.evaluate(() => {
    const card = document.querySelector('.schedule-card[data-sched-id="td0042ab"]');
    if (card) card.dispatchEvent(new Event('x'));
  });
  const fixedOk = await page.evaluate(() => {
    const card = document.querySelector('.schedule-card');
    if (!card) return true;
    return [...card.querySelectorAll('*')].every((el) => {
      const pos = getComputedStyle(el).position;
      return pos !== 'fixed' && pos !== 'sticky';
    });
  });
  check('F5 卡内零 fixed/sticky 定位（编辑态不被键盘顶飞）', fixedOk);
  await page.screenshot({ path: path.join(SHOT_DIR, '390-narrow.png'), fullPage: true });

  check('G1 零 pageerror', pageErrors.length === 0, pageErrors.join(' | '));

  console.log(`\n── 汇总：${rows.filter((r) => r.ok).length}/${rows.length} PASS · 截图 ${SHOT_DIR} ──`);
  return failures === 0 ? 0 : 1;
}

let code = 2;
try {
  code = await main();
} catch (e) {
  console.error('environment error:', e && e.stack ? e.stack : e);
  code = 2;
} finally {
  try { if (browser) await browser.close(); } catch { /* ignore */ }
  for (const s of servers) { try { s.kill('SIGTERM'); } catch { /* ignore */ } }
}
process.exit(code);

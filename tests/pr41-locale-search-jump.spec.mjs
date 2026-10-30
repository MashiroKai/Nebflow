// pr41-locale-search-jump.spec.mjs — 搜索面工具行契约 + 浮窗定位回归
//（原 #41 locale-jump 守卫的换代版，2026-10-30 聊天记录搜索优化批作者令①）。
//
// 历史前提（#41, maintainer fix 18fc201f）：tool 卡片 DOM 本地化 label/summary，
// 搜索索引带原始英文 summary ⇒ 浮窗跳转按原文匹配。该回归腿原样守护了三年。
//
// 换代（作者令① 2026-10-30「搜索结果只搜对话」）：tool call/tool result 不入
// 索引不入结果——#41 的工具行关键词命中场景整体退役（浮窗对 tool 行的定位
// 仍经浏览流点击可达，kind 谓词单一判别点未动）。本文件换代为两条新判据：
//   S1  zh-CN：关键词只命中 tool 行文本（gamma/delta）⇒ 0 条结果——工具噪声
//       不再出现在搜索结果（既有 noResults 空态）；
//   S2  zh-CN + en：关键词命中 user 行 ⇒ 浮窗在命中处打开（.search-hit-flash
//       落在浮窗内，主会话零触碰）——浮窗定位机制度换代后仍活。
//
// Self-contained: route-intercepted static files + mocked REST/WS, no backend.
// Run: node tests/pr41-locale-search-jump.spec.mjs

import { chromium } from 'playwright-core';
import { readFileSync } from 'node:fs';
import { join, dirname, extname, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';

const WEB = join(dirname(fileURLToPath(import.meta.url)), '..', 'src', 'main', 'resources', 'web');
const MIME = { '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.svg': 'image/svg+xml', '.png': 'image/png', '.json': 'application/json', '.woff2': 'font/woff2' };

const SID = 'pr41-session-1';
const EDIT_LABEL = 'Edit\n  (/tmp/alpha/app.js)';
const EDIT_SUMMARY = 'File updated';   // raw backend summary — localized in the DOM under zh-CN
const EDIT_INPUT = JSON.stringify({
  file_path: '/tmp/alpha/app.js',
  old_string: 'const marker = 1;',
  new_string: 'const marker = 2;',
});
const EDIT_CONTENT = 'The file /tmp/alpha/app.js has been updated.\ngamma marker line replaced ok';
const BASH_LABEL = 'Bash\n  (grep)';
const BASH_SUMMARY = '2 lines';
const BASH_INPUT = JSON.stringify({ command: 'grep -n delta /tmp/delta.sh', description: 'find delta' });
const BASH_CONTENT = '3:delta value found';
const USER_TEXT = '请用 omega 关键词标记这条用户消息 omega-marker';

const SEED = [
  { type: 'user', text: '请帮我重构示例文件', timestamp: Date.now() - 3600000 },
  { type: 'ai', text: '好的，我先用 Edit 修改文件。', timestamp: Date.now() - 3500000 },
  { type: 'tool', label: EDIT_LABEL, summary: EDIT_SUMMARY, input: EDIT_INPUT, content: EDIT_CONTENT },
  { type: 'tool', label: BASH_LABEL, summary: BASH_SUMMARY, input: BASH_INPUT, content: BASH_CONTENT },
  { type: 'user', text: USER_TEXT, timestamp: Date.now() - 3400000 },
];

const checks = [];
function check(name, ok, extra = '') {
  checks.push({ name, ok });
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${extra ? '  — ' + extra : ''}`);
}

const browser = await chromium.launch();

/** One isolated page per locale; seed serves both the WS historyPage (chat
 *  DOM) and the REST history (search index), mirroring the real backend. */
async function newPage(locale) {
  const context = await browser.newContext({ viewport: { width: 1440, height: 900 } });
  const page = await context.newPage();
  const pageErrors = [];
  page.on('pageerror', (e) => pageErrors.push(e.message));
  await page.addInitScript((loc) => {
    localStorage.setItem('nebflow_token', 'smoke-token');
    localStorage.setItem('nebflow_locale', loc);
  }, locale);

  await page.route('**/*', (route) => {
    const url = new URL(route.request().url());
    let p = decodeURIComponent(url.pathname);
    if (p === '/') p = '/index.html';
    if (p === '/api/sessions') {
      return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ sessions: [{ id: SID, name: 'PR41', agentName: 'Nebula' }], activeId: SID }) });
    }
    if (p.startsWith(`/api/sessions/${SID}/history`)) {
      return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ messages: SEED, total: SEED.length, sessionId: SID }) });
    }
    if (p.startsWith('/api/')) return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
    const file = normalize(join(WEB, p));
    if (!file.startsWith(WEB)) return route.fulfill({ status: 403, body: 'forbidden' });
    try {
      return route.fulfill({ status: 200, contentType: MIME[extname(file)] || 'application/octet-stream', body: readFileSync(file) });
    } catch {
      return route.fulfill({ status: 404, body: 'not found' });
    }
  });

  await page.routeWebSocket(/\/ws/, (ws) => {
    const sendConfig = () => ws.send(JSON.stringify({ type: 'configData', config: '{}', configured: true, onboarding: 'done' }));
    const sendSessions = () => ws.send(JSON.stringify({ type: 'sessionList', sessions: [{ id: SID, name: 'PR41', agentName: 'Nebula' }], folders: [], activeId: SID }));
    sendConfig(); sendSessions();
    ws.onMessage((raw) => {
      let msg; try { msg = JSON.parse(raw); } catch { return; }
      if (msg.type === 'getConfig') sendConfig();
      else if (msg.type === 'getSessions' || msg.type === 'listSessions') sendSessions();
      else if (msg.type === 'getHistory') {
        ws.send(JSON.stringify({ type: 'historyPage', sessionId: msg.sessionId, messages: SEED, hasMore: false, offset: SEED.length, total: SEED.length }));
      }
    });
  });

  await page.goto('http://localhost:1/');
  await page.waitForFunction(async (sid) => {
    const s = (await import('/js/state.js')).default;
    return s.activeSessionId === sid && s.ws && s.ws.readyState === 1;
  }, SID, { timeout: 15000 });
  // Chat DOM must carry the tool cards (browse/浮窗定位仍消费 tool 行)。
  await page.waitForSelector('.row.tool .tool-card', { timeout: 10000 });
  await page.waitForTimeout(300);
  return { page, context, pageErrors };
}

/** Open the modal and search `kw`; resolve when the result list settles. */
async function search(page, kw) {
  await page.click('#search-btn');
  await page.waitForSelector('#search-overlay.on', { timeout: 5000 });
  await page.fill('#search-keyword', kw);
  await page.waitForTimeout(800);   // 300ms debounce + fetch + render
}

// ── S1: zh-CN — 只命中 tool 行文本的关键词 ⇒ 0 结果（作者令①）──
{
  const { page, context, pageErrors } = await newPage('zh-CN');
  // #41 原前提仍在：卡片 summary 被本地化，原始英文只在索引进文本——但工具
  // 行现已整体退出搜索结果， locale 差不再可达。
  const cardText = await page.locator('.row.tool .tool-card').first().textContent();
  check('S1 precondition: card label localized (文件已更新, not "File updated")', cardText.includes('文件已更新') && !cardText.includes('File updated'), cardText.slice(0, 80));

  for (const kw of ['gamma', 'delta']) {
    await search(page, kw);
    const state = await page.evaluate(() => ({
      rows: document.querySelectorAll('#search-results .search-result').length,
      toolRows: document.querySelectorAll('#search-results .search-result[data-kind="tool"]').length,
      hint: document.querySelector('#search-results .search-hint')?.textContent || '',
    }));
    check(`S1 zh-CN "${kw}": 0 结果（tool 行不入结果）`, state.rows === 0 && state.toolRows === 0, JSON.stringify(state));
    // 三段 Esc：① 日历（关着）② 关键词非空 → 清词 ③ 关面板。
    await page.keyboard.press('Escape');
    await page.waitForTimeout(300);
    await page.keyboard.press('Escape');
    await page.waitForTimeout(300);
    if (await page.evaluate(() => document.getElementById('search-overlay')?.classList.contains('on'))) {
      await page.keyboard.press('Escape');
      await page.waitForTimeout(300);
    }
  }
  check('S1 no page errors', pageErrors.length === 0, pageErrors.join('; '));
  await context.close();
}

// ── S2: 关键词命中 user 行 ⇒ 浮窗在命中处打开（定位机制度换代后仍活）──
async function floatJumpLeg(locale, label) {
  const { page, context, pageErrors } = await newPage(locale);
  await search(page, 'omega');
  const rows = await page.evaluate(() =>
    [...document.querySelectorAll('#search-results .search-result')].map((el) => el.dataset.kind));
  check(`${label}: 命中 = user 行（tool 行文本含 omega? 否——仅 user 种植行）`, rows.length === 1 && rows[0] === 'user', JSON.stringify(rows));

  await page.click('#search-results .search-result');
  // 浮窗打开：窗内闪击带 + 主会话不被触碰（浮窗契约 v2）。
  const flashed = await page.waitForFunction(() => {
    const el = document.querySelector('.nf-msgfloat .search-hit-flash');
    return !!el && el.textContent.includes('omega');
  }, { timeout: 8000 });
  check(`${label}: floating window flashed the hit row (omega) within 8s`, !!flashed);
  const mainUntouched = await page.evaluate(() =>
    !document.querySelector('#chat .search-hit-flash') &&
    !!document.getElementById('search-overlay')?.classList.contains('on'));
  check(`${label}: main chat untouched, modal stays open (float contract)`, mainUntouched);
  check(`${label}: no page errors`, pageErrors.length === 0, pageErrors.join('; '));
  await context.close();
}
await floatJumpLeg('zh-CN', 'S2 zh-CN user float');
await floatJumpLeg('en', 'S3 en user float');

await browser.close();

const failed = checks.filter(c => !c.ok);
console.log(`\n${checks.length - failed.length}/${checks.length} passed`);
process.exit(failed.length ? 1 : 0);

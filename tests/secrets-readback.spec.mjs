// secrets-readback.spec.mjs — 密钥面板「行内化二轮 · 原位展开编辑」验收 spec。
//
// 任务（用户拍板 2026-10-09 07:42 行内化；2026-10-09 08:32 二轮修型：「可以对
// Ui布局再进行优化一下 不用都放同一行，保持极简干净的布局，另外修改的时候
// 也不要影响布局，就在原位进行修改。…就是原位进行修改」— iPhone 提醒事项式；
// 2026-10-09 23:06 三轮微调：「密钥面板这个点点的长度跟这个密钥的长度不一致。
// 然后没有点击展开的时候，可以不显示点点」）：
//   ① 常态行极简：每行只留 名称（左）+ 权限徽标（仅权限过宽时）。三轮起
//      收起态不渲染值点点（占位 span 退役）；没有按钮、没有字段、没有
//      meta——眼睛/编辑器/删除在常态 DOM 中不存在。
//   ② 原位展开编辑：点行 → 该行原位向下展开成编辑区（值字段 + 眼睛 + meta +
//      保存/删除），下方各行自然下移；不弹窗、不重排全局布局；同时只开一行。
//      遮蔽占位长度保真（三轮 23:06）：展开字段的点点 = size 元数据逐字节
//      一颗点（ASCII 密钥逐字符精确；占位永不读值），超宽在单行字段内裁剪。
//   ③ 完成 = 保存/Enter（有改动才 PUT，成功收起 + 静态面闪「已保存 ✓」）、
//      点行外（有改动 = 提交并收起，同 Reminders 离开即存）、Esc（放弃并收起，
//      不发请求）；失败保留编辑区与文本可重试。
//   ④ 创建：列表尾部常驻虚线「＋ 新建密钥」行，点击原位展开草稿（名称+值），
//      Enter/保存钮提交走既有 POST，Esc 丢弃，成功后新行就位。
//   ⑤ 纪律红线：列表接口/DOM 常态零明文值（点点是占位非真值）；带值请求仅
//      眼睛触发、恰一次、带 Authorization；慢响应/快速切换用 token 守卫防串
//      行（揭示途中再点眼睛 = 取消，迟到回复必须被丢弃；收起行也会杀掉在途
//      揭示）；删除仍走玻璃二次确认 + DELETE ?confirm=<name>。
//
// SELF-CONTAINED（secrets-ui-redesign.spec.mjs 同款 harness）：隔离静态服务器
// 127.0.0.1:8191 + route mock API + routeWebSocket mock WS，零真实后端。
//
// 截图（亮/暗双主题：常态极简行 / 展开编辑 / 展开揭示 / 保存收起回原样 /
// 新建草稿 / 删除确认）→ /tmp/nebflow-coldstart/general/secrets-inline-shots/
// （v2- 前缀 = 二轮，一轮旧图同目录保留作前后对照）
// Run: node node_modules/@playwright/test/cli.js test tests/secrets-readback.spec.mjs --workers=1 --reporter=line

import { test, expect } from '@playwright/test';
import { createServer } from 'node:http';
import { readFile } from 'node:fs/promises';
import { join, dirname, extname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const WEB = resolve(HERE, '..', 'src', 'main', 'resources', 'web');
const PORT = 8191; // fixed, 8100+ per task discipline — never the host 8080
const SHOTS = '/tmp/nebflow-coldstart/general/secrets-inline-shots';
const MIME = { '.js': 'text/javascript', '.css': 'text/css', '.html': 'text/html', '.svg': 'image/svg+xml', '.png': 'image/png' };

const VALUE = 'sk-inline-9f83c1-SECRET-VALUE';
const NAME = 'api-token-prod';
const LIST = [
  // size = VALUE.length：遮蔽占位逐字节一颗点（三轮长度保真），占位串长度
  // 必须与揭示后的明文长度一致，断言才有牙齿。
  { name: NAME, size: VALUE.length, mtime: 1728383640000, mode: '0600', refs: ['jev.keyRef'], value: 'DECOY-ON-THE-WIRE' },
  { name: 'legacy-backup-key', size: 96, mtime: 1726140180000, mode: '0644', refs: [], value: 'DECOY-ON-THE-WIRE' },
];

/** Static server rooted at the web dir (path-traversal safe). */
function startServer() {
  return new Promise((resolveDone) => {
    const server = createServer(async (req, res) => {
      try {
        let p = decodeURIComponent(new URL(req.url, 'http://x').pathname);
        if (p === '/') p = '/index.html';
        const file = resolve(join(WEB, p));
        if (!file.startsWith(WEB)) { res.writeHead(403); res.end(); return; }
        const data = await readFile(file);
        res.writeHead(200, { 'content-type': MIME[extname(file)] || 'application/octet-stream' });
        res.end(data);
      } catch {
        res.writeHead(404); res.end('not found');
      }
    });
    server.listen(PORT, '127.0.0.1', () => resolveDone(server));
  });
}

let server;
const base = `http://127.0.0.1:${PORT}`;
test.beforeAll(async () => { server = await startServer(); });
test.afterAll(async () => { server.close(); });

/**
 * Boot the real shell against mocks.
 * @param {import('@playwright/test').Page} page
 * @param {{locale?: string, colorScheme?: 'light'|'dark', valueStatus?: number,
 *           valueDelayMs?: number}} opts
 */
async function bootPage(page, opts = {}) {
  const { locale = 'zh-CN', colorScheme = 'light', valueStatus = 200, valueDelayMs = 0 } = opts;
  await page.emulateMedia({ colorScheme });
  await page.addInitScript((l) => {
    localStorage.setItem('nebflow_token', 't');
    localStorage.setItem('neblink_token', 't');
    localStorage.setItem('nebflow_locale', l);
    localStorage.setItem('neblink_locale', l);
  }, locale);

  /** @type {{url: string, auth: string}[]} */ const valueCalls = [];
  /** @type {{method: string, url: string, body: string}[]} */ const mutations = [];
  let listFetches = 0;
  // Per-boot copy: a test that POSTs (create) folds the new row into THIS
  // boot's list only — the shared LIST fixture stays pristine for other tests.
  const list = LIST.map((s) => ({ ...s }));

  await page.route('**/api/**', (r) => r.fulfill({ json: {} })); // catch-all (lowest priority)
  await page.route('**/api/secrets', (r) => {
    listFetches += 1;
    // Rows deliberately carry a decoy `value` field that must NEVER render.
    return r.fulfill({ json: { secrets: list } });
  });
  await page.route(/\/api\/secrets\/[^/]+\/value$/, (r) => {
    const url = r.request().url();
    valueCalls.push({ url, auth: r.request().headers()['authorization'] || '' });
    const reply = () => {
      if (valueStatus !== 200) return r.fulfill({ status: valueStatus, body: 'boom' });
      return r.fulfill({ json: { name: NAME, value: VALUE } });
    };
    if (valueDelayMs > 0) setTimeout(reply, valueDelayMs); else reply();
  });
  await page.route(/\/api\/secrets\/[^/]+$/, (r) => {
    const req = r.request();
    if (req.method() === 'GET') return r.fulfill({ json: { secrets: list } });
    // POST = create: fold the new secret into this boot's list so the panel's
    // post-create re-read can actually land the new row (backend truth sim).
    if (req.method() === 'POST') {
      const m = req.url().match(/\/api\/secrets\/([^/?]+)/);
      if (m) list.push({ name: decodeURIComponent(m[1]), size: 20, mtime: 1728470040000, mode: '0600', refs: [], value: 'DECOY-ON-THE-WIRE' });
    }
    mutations.push({ method: req.method(), url: req.url(), body: req.postData() || '' });
    return r.fulfill({ json: { ok: true, name: NAME } });
  });

  const frames = [];
  let wsSend = () => {};
  await page.routeWebSocket(/\/ws/, (ws) => {
    wsSend = (obj) => ws.send(JSON.stringify(obj));
    ws.onMessage((raw) => {
      let m; try { m = JSON.parse(raw); } catch { return; }
      frames.push(m);
      if (m.type === 'getHistory') ws.send(JSON.stringify({ type: 'historyPage', sessionId: m.sessionId, messages: [], hasMore: false, offset: 0 }));
    });
    ws.send(JSON.stringify({
      type: 'configData', configured: true, onboarding: 'done', models: [], defaults: {},
      config: JSON.stringify({ enabled: false }),
    }));
    ws.send(JSON.stringify({ type: 'serverConfig', version: '1.4.0', streamTimeoutMs: 600000 }));
    ws.send(JSON.stringify({ type: 'sessionList', sessions: [], activeId: null, folders: [] }));
  });

  await page.goto(base + '/index.html');
  await page.waitForSelector('#activity-bar', { timeout: 15000 });
  return { valueCalls, mutations, listCount: () => listFetches };
}

async function openSettings(page) {
  await page.click('#settings-btn');
  await page.waitForSelector('#settings-content .settings-section', { timeout: 10000 });
}
async function openPanel(page) {
  await page.click('#btn-open-secrets');
  await page.waitForSelector('#secrets-overlay.on .secrets-row', { timeout: 5000 });
}
const rowSel = (name) => `.secrets-row[data-name="${name}"]`;
const headSel = (name) => `${rowSel(name)} [data-row-head]`;
const fieldSel = (name) => `${rowSel(name)} [data-edit]`;
const eyeSel = (name) => `${rowSel(name)} [data-eye]`;
const saveSel = (name) => `${rowSel(name)} [data-save]`;
const delSel = (name) => `${rowSel(name)} [data-delete]`;
/** 点行原位展开（二轮入口）；返回前等编辑区的值字段就位。 */
async function expandRow(page, name) {
  await page.click(headSel(name));
  await page.waitForSelector(fieldSel(name), { timeout: 5000 });
}

// ══ R1 · 常态行极简：只有 名称+徽标（三轮：无点点）；无按钮/无字段/无 meta；零读值 ══
test('R1 rows rest minimal: name + badge only (no dots) — no field, no eye, no delete, no meta; zero value reads', async ({ page }) => {
  const h = await bootPage(page);
  await openSettings(page);
  await openPanel(page);

  // The editor sub-dialog and the FAB are GONE from the shell (退役).
  const retired = await page.evaluate(() => ({
    form: document.getElementById('secrets-form'),
    fab: document.getElementById('secrets-fab'),
  }));
  expect(retired.form, '🔴 #secrets-form (editor modal) is retired').toBeNull();
  expect(retired.fab, '🔴 #secrets-fab is retired').toBeNull();

  const state = await page.evaluate(() => {
    const rows = [...document.querySelectorAll('#secrets-list .secrets-row')];
    return {
      rows: rows.map((row) => {
        const head = row.querySelector('[data-row-head]');
        const badge = row.querySelector('.secrets-mode-badge');
        return {
          name: row.dataset.name,
          headIsButton: head instanceof HTMLButtonElement,
          ariaExpanded: head?.getAttribute('aria-expanded'),
          badge: badge?.dataset.mode || '',
          noDots: !row.querySelector('.secrets-row-dots'),
          // 二轮红线：常态行不存在任何编辑/操作面
          noField: !row.querySelector('[data-edit]'),
          noEye: !row.querySelector('[data-eye]'),
          noDelete: !row.querySelector('[data-delete]'),
          noSave: !row.querySelector('[data-save]'),
          noDetail: !row.querySelector('.secrets-row-detail'),
          noMeta: !row.querySelector('.secrets-row-meta'),
          statusEmpty: (row.querySelector('.secrets-row-status')?.textContent || '') === '',
        };
      }),
      addRow: !!document.querySelector('#secrets-list .secrets-add-row .secrets-add-trigger'),
      addText: document.querySelector('#secrets-list .secrets-add-trigger')?.textContent?.trim() || '',
    };
  });
  expect(state.rows.length, 'two secret rows').toBe(2);
  for (const r of state.rows) {
    expect(r.headIsButton, `${r.name}: the whole resting row is one button`).toBe(true);
    expect(r.ariaExpanded, `${r.name}: resting head aria-expanded=false`).toBe('false');
    expect(r.noDots, `${r.name}: 🔴 收起态不渲染遮蔽点（三轮 23:06）`).toBe(true);
    expect(r.noField, `${r.name}: 🔴 no edit field in the resting DOM`).toBe(true);
    expect(r.noEye, `${r.name}: 🔴 no eye button at rest`).toBe(true);
    expect(r.noDelete, `${r.name}: 🔴 no delete button at rest`).toBe(true);
    expect(r.noSave, `${r.name}: no save button at rest`).toBe(true);
    expect(r.noDetail, `${r.name}: no expanded detail at rest`).toBe(true);
    expect(r.noMeta, `${r.name}: meta moved into the detail (常态极简)`).toBe(true);
    expect(r.statusEmpty, `${r.name}: status slot empty at rest`).toBe(true);
  }
  expect(state.rows.find((r) => r.name === NAME)?.badge, '0600 row carries the ok badge').toBe('ok');
  expect(state.rows.find((r) => r.name === 'legacy-backup-key')?.badge, '0644 row keeps the permissive warning on the list face').toBe('permissive');
  expect(state.addRow, 'dashed「＋ 新建密钥」tail row present').toBe(true);
  expect(state.addRow && state.addText, 'tail row copy').toContain('新建密钥');
  const leak = await page.evaluate((v) => document.body.textContent.includes(v), VALUE);
  expect(leak, '🔴 no fixture/decoy value anywhere in the DOM').toBe(false);
  expect(h.valueCalls.length, 'opening the list issues ZERO value reads').toBe(0);
});

// ══ R2 · 原位展开：展开不拉值；眼睛恰一次鉴权读值；回遮清空；收起焚毁 ═══
test('R2 expanding fetches nothing; the eye reveals via exactly one authenticated read; re-mask clears; collapsing burns the detail', async ({ page }) => {
  const h = await bootPage(page);
  await openSettings(page);
  await openPanel(page);

  await expandRow(page, NAME);
  expect(h.valueCalls.length, '🔴 expanding a row issues NO value read (揭示只归眼睛)').toBe(0);
  const opened = await page.evaluate((name) => {
    const row = document.querySelector(`.secrets-row[data-name="${name}"]`);
    const f = row?.querySelector('[data-edit]');
    return {
      detail: !!row?.querySelector('.secrets-row-detail'),
      open: row?.hasAttribute('data-open'),
      expanded: row?.querySelector('[data-row-head]')?.getAttribute('aria-expanded'),
      value: f instanceof HTMLTextAreaElement ? f.value : null,
      placeholder: f instanceof HTMLElement ? f.placeholder : '',
      security: f ? getComputedStyle(f).webkitTextSecurity : '',
      meta: !!row?.querySelector('.secrets-row-meta'),
      save: !!row?.querySelector('[data-save]'),
      del: !!row?.querySelector('[data-delete]'),
      focused: document.activeElement === f,
    };
  }, NAME);
  expect(opened.detail, 'the detail expanded in place').toBe(true);
  expect(opened.open, 'row carries data-open').toBe(true);
  expect(opened.expanded, 'head aria-expanded=true').toBe('true');
  expect(opened.value, 'field opens EMPTY (dots placeholder, no auto-fetch)').toBe('');
  expect(opened.placeholder, 'placeholder is the LENGTH-FAITHFUL dots face (size 逐字节一颗点)').toBe('•'.repeat(VALUE.length));
  expect(opened.security, 'field masked (disc)').toBe('disc');
  expect(opened.meta, 'meta (size/mtime/refs) lives in the detail').toBe(true);
  expect(opened.save && opened.del, '保存 + 删除 live in the detail').toBe(true);
  expect(opened.focused, 'the field takes focus (click row → ready to type)').toBe(true);

  await page.click(eyeSel(NAME));
  await page.waitForFunction((sel) => {
    const f = document.querySelector(sel);
    return f instanceof HTMLTextAreaElement && f.value.length > 0;
  }, fieldSel(NAME), { timeout: 5000 });
  const revealed = await page.evaluate((sel) => {
    const f = document.querySelector(sel);
    const eye = document.querySelector(sel.replace('[data-edit]', '[data-eye]'));
    return {
      value: f instanceof HTMLTextAreaElement ? f.value : '',
      security: f ? getComputedStyle(f).webkitTextSecurity : '',
      pressed: eye?.getAttribute('aria-pressed'),
      glyph: eye?.innerHTML || '',
      label: eye?.getAttribute('aria-label'),
    };
  }, fieldSel(NAME));
  expect(revealed.value, 'the fetched value shows in the detail field').toBe(VALUE);
  expect(revealed.security, 'revealed → security none').toBe('none');
  expect(revealed.pressed, 'aria-pressed=true while revealed').toBe('true');
  expect(revealed.glyph, 'glyph swaps to eye-off').toContain('10.733');
  expect(revealed.label, 'label names the hide action').toBe('隐藏值');
  expect(h.valueCalls.length, 'exactly ONE value read for the reveal').toBe(1);
  expect(h.valueCalls[0].auth, 'the read rides the panel auth header').toBe('Bearer t');
  expect(h.valueCalls[0].url, 'single-name endpoint').toContain(`/api/secrets/${NAME}/value`);

  // Re-mask = 清空回占位 (no plaintext lingers in the field).
  await page.click(eyeSel(NAME));
  const back = await page.evaluate((sel) => {
    const f = document.querySelector(sel);
    const eye = document.querySelector(sel.replace('[data-edit]', '[data-eye]'));
    return {
      value: f instanceof HTMLTextAreaElement ? f.value : '',
      placeholder: f instanceof HTMLElement ? f.placeholder : '',
      security: f ? getComputedStyle(f).webkitTextSecurity : '',
      pressed: eye?.getAttribute('aria-pressed'),
    };
  }, fieldSel(NAME));
  expect(back.value, '🔴 re-mask CLEARS the fetched plaintext (清空回占位)').toBe('');
  expect(back.placeholder, 'placeholder dots face is back (length-faithful)').toBe('•'.repeat(VALUE.length));
  expect(back.security, 'masked again (disc)').toBe('disc');
  expect(back.pressed, 'eye back to masked face').toBe('false');
  expect(h.valueCalls.length, 'masking triggers no request').toBe(1);

  // Collapse → the whole detail burns (stronger than clearing); re-expand →
  // dots again, and the next reveal fetches again (one per reveal).
  await page.keyboard.press('Escape');
  await page.waitForSelector(fieldSel(NAME), { state: 'detached', timeout: 5000 });
  const burned = await page.evaluate((v) => ({
    detail: !!document.querySelector(`${'.secrets-row[data-name="api-token-prod"]'} .secrets-row-detail`),
    open: document.querySelector('.secrets-row[data-name="api-token-prod"]')?.hasAttribute('data-open'),
    leak: document.body.textContent.includes(v),
  }), VALUE);
  expect(burned.detail, 'collapsing removes the detail (收起回常态行)').toBe(false);
  expect(burned.open, 'row back to the resting face').toBe(false);
  expect(burned.leak, '🔴 the plaintext left the DOM with the detail').toBe(false);

  await expandRow(page, NAME);
  await page.click(eyeSel(NAME));
  await page.waitForFunction((sel) => {
    const f = document.querySelector(sel);
    return f instanceof HTMLTextAreaElement && f.value.length > 0;
  }, fieldSel(NAME), { timeout: 5000 });
  const again = await page.evaluate((sel) => {
    const f = document.querySelector(sel);
    return f instanceof HTMLTextAreaElement ? f.value : '';
  }, fieldSel(NAME));
  expect(again, 'second reveal shows the value again').toBe(VALUE);
  expect(h.valueCalls.length, 'each reveal issues its own single read').toBe(2);
});

// ══ R3 · 慢响应/快速切换：token 守卫防串行，迟到回复被丢弃 ════════════════
test('R3 a slow value reply is dropped when the eye is toggled again before it lands (no cross-matching)', async ({ page }) => {
  const h = await bootPage(page, { valueDelayMs: 600 });
  await openSettings(page);
  await openPanel(page);

  await expandRow(page, NAME);
  await page.click(eyeSel(NAME));                 // fetch starts (slow)
  await page.click(eyeSel(NAME));                 // fast toggle = CANCEL the reveal
  await page.waitForTimeout(900);                 // the late reply arrives here
  const st = await page.evaluate((v) => ({
    value: document.querySelector('.secrets-row[data-name="api-token-prod"] [data-edit]')?.value ?? null,
    security: (() => {
      const f = document.querySelector('.secrets-row[data-name="api-token-prod"] [data-edit]');
      return f ? getComputedStyle(f).webkitTextSecurity : '';
    })(),
    leak: document.body.textContent.includes(v),
  }), VALUE);
  expect(st.value, '🔴 the cancelled reveal never lands in the field').toBe('');
  expect(st.security, 'field stays masked').toBe('disc');
  expect(st.leak, '🔴 the late plaintext never reaches the DOM').toBe(false);
  expect(h.valueCalls.length, 'only the one cancelled fetch fired').toBe(1);
});

// ══ R4 · 原位编辑完成语义：Enter/保存=提交并收起；点行外=改动即存；Esc=放弃 ═
test('R4a typing in the expanded field + Enter PUTs, collapses back to the resting row and flashes 已保存 ✓', async ({ page }) => {
  const h = await bootPage(page);
  await openSettings(page);
  await openPanel(page);

  const next = 'sk-rotated-inline-value';
  await expandRow(page, NAME);
  await page.fill(fieldSel(NAME), next); // masked state — direct-overwrite semantics
  await page.press(fieldSel(NAME), 'Enter');
  await page.waitForFunction(() => (document.querySelector('.secrets-row[data-name="api-token-prod"] .secrets-row-status')?.textContent || '').length > 0, undefined, { timeout: 5000 });
  const put = h.mutations.find((m) => m.method === 'PUT');
  expect(put, 'PUT fired').toBeTruthy();
  expect(put.url, 'PUT targets the existing secret').toContain(`/api/secrets/${NAME}`);
  expect(JSON.parse(put.body).value, 'the body carries the overwritten value').toBe(next);
  const after = await page.evaluate(() => {
    const row = document.querySelector('.secrets-row[data-name="api-token-prod"]');
    const st = row?.querySelector('.secrets-row-status');
    return {
      collapsed: !row?.querySelector('.secrets-row-detail') && !row?.hasAttribute('data-open'),
      noDots: !row?.querySelector('.secrets-row-dots'),
      status: st?.textContent || '',
      okIcon: !!st?.querySelector('svg.st-ok'),
    };
  });
  expect(after.collapsed, 'the row collapsed back to its resting face').toBe(true);
  expect(after.noDots, 'resting face carries no dots (收起无点点, 三轮 23:06)').toBe(true);
  expect(after.status, 'inline feedback shows 已保存 ✓ on the resting face').toContain('已保存 ✓');
  expect(after.okIcon, 'with the st-ok icon').toBe(true);
  await page.waitForFunction(() => (document.querySelector('.secrets-row[data-name="api-token-prod"] .secrets-row-status')?.textContent || '') === '', undefined, { timeout: 3000 });
});

test('R4b 保存 commits; click-outside commits a CHANGED field and collapses (no-change sends no PUT); Esc discards and collapses', async ({ page }) => {
  const h = await bootPage(page);
  await openSettings(page);
  await openPanel(page);

  // (1) Reveal, then click a neutral zone → no change → collapse, NO PUT.
  await expandRow(page, NAME);
  await page.click(eyeSel(NAME));
  await page.waitForFunction((sel) => {
    const f = document.querySelector(sel);
    return f instanceof HTMLTextAreaElement && f.value.length > 0;
  }, fieldSel(NAME), { timeout: 5000 });
  await page.click('#secrets-modal-title');
  await page.waitForSelector(fieldSel(NAME), { state: 'detached', timeout: 5000 });
  expect(h.mutations.filter((m) => m.method === 'PUT').length, 'no-change click-outside sends no PUT').toBe(0);

  // (2) Type on top of the revealed value, then 保存 → PUT carries the edit.
  const next = VALUE + '-SAVED';
  await expandRow(page, NAME);
  await page.click(eyeSel(NAME));
  await page.waitForFunction((sel) => {
    const f = document.querySelector(sel);
    return f instanceof HTMLTextAreaElement && f.value.length > 0;
  }, fieldSel(NAME), { timeout: 5000 });
  await page.fill(fieldSel(NAME), next);
  await page.click(saveSel(NAME));
  await expect.poll(() => h.mutations.filter((m) => m.method === 'PUT').length, { timeout: 5000 })
    .toBeGreaterThan(0);
  const put = h.mutations.find((m) => m.method === 'PUT');
  expect(JSON.parse(put?.body || '{}').value, 'the 保存 commit carries the edited value').toBe(next);
  await page.waitForSelector(fieldSel(NAME), { state: 'detached', timeout: 5000 });

  // (3) Esc discards a typed edit: collapses WITHOUT a PUT, dots come back.
  await expandRow(page, NAME);
  await page.fill(fieldSel(NAME), 'sk-typed-and-abandoned');
  await page.keyboard.press('Escape');
  await page.waitForSelector(fieldSel(NAME), { state: 'detached', timeout: 5000 });
  const dotsBack = await page.evaluate(() =>
    !!document.querySelector('.secrets-row[data-name="api-token-prod"] .secrets-row-dots'));
  expect(dotsBack, 'Esc collapses back to the DOTLESS resting face').toBe(false);
  expect(h.mutations.filter((m) => m.method === 'PUT').length, 'Esc sent no extra PUT').toBe(1);
});

// ══ R5 · 创建：虚线尾行 → 原位草稿 → POST → 新行就位；Esc 丢弃 ═══════════
test('R5 the dashed tail row expands into an inline draft; invalid name errors inline; valid draft POSTs and lands as a row', async ({ page }) => {
  const h = await bootPage(page);
  await openSettings(page);
  await openPanel(page);

  await page.click('.secrets-add-trigger');
  await page.waitForSelector('.secrets-add-row[data-open] [data-draft-name]', { timeout: 5000 });
  expect(await page.locator('.secrets-add-trigger').count(), 'trigger face swapped out').toBe(0);

  // Draft eye = presentation-only mask toggle on the TYPED value (no fetch).
  await page.fill('[data-draft-value]', 'sk-draft-typed');
  await page.click('[data-draft-eye]');
  const draftEye = await page.evaluate(() => {
    const f = document.querySelector('[data-draft-value]');
    return { value: f.value, security: getComputedStyle(f).webkitTextSecurity };
  });
  expect(draftEye.value, 'typed draft text stays (eye never burns local text)').toBe('sk-draft-typed');
  expect(draftEye.security, 'draft unmasked for review').toBe('none');
  expect(h.valueCalls.length, 'the draft eye never fetches').toBe(0);

  // Invalid name → inline error, no POST.
  await page.fill('[data-draft-name]', 'Bad Name!');
  await page.click('[data-draft-save]');
  await page.waitForSelector('.secrets-draft-error:not([hidden])', { timeout: 5000 });
  const errText = await page.textContent('.secrets-draft-error');
  expect(errText, 'name-shape error shows inline').toContain('只能用小写字母');
  expect(h.mutations.filter((m) => m.method === 'POST').length, 'no POST for an invalid name').toBe(0);

  // Valid draft → Enter submits (same path as the 保存 button).
  await page.fill('[data-draft-name]', 'fresh-inline-key');
  await page.fill('[data-draft-value]', 'sk-brand-new-value');
  await page.press('[data-draft-name]', 'Enter');
  await page.waitForFunction(() => !document.querySelector('.secrets-add-row[data-open]'), undefined, { timeout: 5000 });
  const post = h.mutations.find((m) => m.method === 'POST');
  expect(post, 'POST fired').toBeTruthy();
  expect(post.url, 'POST targets the new name').toContain('/api/secrets/fresh-inline-key');
  expect(JSON.parse(post.body).value, 'the body carries the draft value').toBe('sk-brand-new-value');
  await page.waitForSelector('.secrets-row[data-name="fresh-inline-key"]', { timeout: 5000 });
  expect(await page.locator('.secrets-add-trigger').count(), 'draft collapsed back to the dashed trigger').toBe(1);
  const newRow = await page.evaluate(() => {
    const row = document.querySelector('.secrets-row[data-name="fresh-inline-key"]');
    return {
      noDots: !row?.querySelector('.secrets-row-dots'),
      noField: !row?.querySelector('[data-edit]'),
    };
  });
  expect(newRow.noDots, '🔴 the new row rests DOTLESS (no value echo)').toBe(true);
  expect(newRow.noField, 'the new row carries no field at rest').toBe(true);
});

test('R5b Esc discards the draft without a POST', async ({ page }) => {
  const h = await bootPage(page);
  await openSettings(page);
  await openPanel(page);
  await page.click('.secrets-add-trigger');
  await page.waitForSelector('.secrets-add-row[data-open] [data-draft-name]', { timeout: 5000 });
  await page.fill('[data-draft-name]', 'abandoned-key');
  await page.fill('[data-draft-value]', 'sk-abandoned');
  await page.keyboard.press('Escape');
  await page.waitForSelector('.secrets-add-trigger', { timeout: 5000 });
  expect(await page.locator('.secrets-add-row[data-open]').count(), 'draft collapsed').toBe(0);
  expect(h.mutations.filter((m) => m.method === 'POST').length, 'Esc sent no POST').toBe(0);
});

// ══ R6 · 删除：只在展开态出现；danger hover；玻璃二次确认门 DELETE ═════════
test('R6 delete lives in the expanded detail only: right-aligned, danger tint on hover, glass confirm gates the DELETE', async ({ page }) => {
  const h = await bootPage(page);
  await openSettings(page);
  await openPanel(page);

  const resting = await page.evaluate(() => ({
    rows: document.querySelectorAll('#secrets-list .secrets-row').length,
    deletes: document.querySelectorAll('#secrets-list [data-delete]').length,
  }));
  expect(resting.rows, 'two rows').toBe(2);
  expect(resting.deletes, '🔴 no delete button exists on any RESTING row').toBe(0);

  await expandRow(page, NAME);
  const aligned = await page.evaluate(() =>
    getComputedStyle(document.querySelector('.secrets-row[data-name="api-token-prod"] .secrets-row-actions')).justifyContent);
  expect(aligned, 'detail actions right-aligned at row end').toBe('flex-end');

  const del = page.locator(delSel(NAME));
  const rest = await del.evaluate((el) => {
    const cs = getComputedStyle(el);
    return { bg: cs.backgroundColor, color: cs.color };
  });
  await del.hover();
  await page.waitForTimeout(300); // .cfg-btn transitions all 0.15s — let it settle
  const hover = await del.evaluate((el) => {
    const cs = getComputedStyle(el);
    return { bg: cs.backgroundColor, color: cs.color };
  });
  expect(hover.bg, 'hover applies a danger tint background').not.toBe(rest.bg);
  expect(hover.color, 'hover tints the ink toward the error token').not.toBe(rest.color);

  await del.click();
  await page.waitForSelector('#modal-overlay.on #delete-box', { timeout: 5000 });
  const confirm = await page.evaluate(() => ({
    msg: document.getElementById('delete-msg').textContent,
    confirmText: document.getElementById('delete-confirm').textContent.trim(),
    cancelText: document.getElementById('delete-cancel').textContent.trim(),
  }));
  expect(confirm.msg, 'the confirm copy names the secret + 不可恢复').toContain(NAME);
  expect(confirm.msg).toContain('删除后不可恢复');
  expect(confirm.confirmText, 'confirm button is 删除').toBe('删除');
  expect(confirm.cancelText, 'cancel button is 取消').toBe('取消');
  expect(h.mutations.filter((m) => m.method === 'DELETE').length, 'no bare delete before confirm').toBe(0);

  await page.click('#delete-cancel');
  await page.waitForFunction(() => !document.getElementById('modal-overlay').classList.contains('on'));
  expect(h.mutations.filter((m) => m.method === 'DELETE').length, 'cancel sends no DELETE').toBe(0);

  await del.click();
  await page.waitForSelector('#modal-overlay.on #delete-box', { timeout: 5000 });
  await page.click('#delete-confirm');
  await page.waitForFunction(() => !document.getElementById('modal-overlay').classList.contains('on'));
  const dels = h.mutations.filter((m) => m.method === 'DELETE');
  expect(dels.length, 'exactly one DELETE after confirm').toBe(1);
  expect(dels[0].url, 'DELETE rides ?confirm=<name>').toContain(`confirm=${NAME}`);
});

// ══ R7 · 读取失败 → toast + 字段留空遮蔽（不伪造值）══════════════════════
test('R7 a failed value read degrades to a toast with the field left masked and empty', async ({ page }) => {
  await bootPage(page, { valueStatus: 404 });
  await openSettings(page);
  await openPanel(page);
  await expandRow(page, NAME);
  await page.click(eyeSel(NAME));
  await page.waitForSelector('.nebflow-toast-error', { timeout: 5000 });
  const st = await page.evaluate(() => {
    const f = document.querySelector('.secrets-row[data-name="api-token-prod"] [data-edit]');
    return {
      toast: document.querySelector('.nebflow-toast-error')?.textContent || '',
      value: f instanceof HTMLTextAreaElement ? f.value : null,
      security: f ? getComputedStyle(f).webkitTextSecurity : '',
    };
  });
  expect(st.toast, '404 maps to the not-found copy').toContain('已不存在');
  expect(st.value, 'no faked value in the field').toBe('');
  expect(st.security, 'field stays masked').toBe('disc');
});

// ══ 双主题截图（常态极简行 / 展开编辑 / 展开揭示 / 保存收起回原样 / 新建草稿 /
//    删除确认）— v2- 前缀，一轮旧图同目录保留作前后对照 ═══════════════════
async function shots(colorScheme, tag) {
  test(`shots[${tag}] rest / expanded-edit / expanded-revealed / restored / draft / delete-confirm`, async ({ page }) => {
    const h = await bootPage(page, { locale: 'zh-CN', colorScheme });
    await openSettings(page);
    await openPanel(page);
    await page.waitForTimeout(400);
    // 1) 常态极简行（名称+徽标，无点点无按钮 — 三轮 23:06）
    await page.locator('#secrets-modal').screenshot({ path: `${SHOTS}/v2-rest-${tag}.png` });

    // 2) 原位展开编辑（值字段遮蔽聚焦，meta+保存+删除收在展开区，下行自然下移）
    await expandRow(page, NAME);
    await page.waitForTimeout(300);
    await page.locator('#secrets-modal').screenshot({ path: `${SHOTS}/v2-expanded-${tag}.png` });

    // 3) 展开态·眼睛揭示（红线留证：恰一次拉值）
    await page.click(eyeSel(NAME));
    await page.waitForFunction((sel) => {
      const f = document.querySelector(sel);
      return f instanceof HTMLTextAreaElement && f.value.length > 0;
    }, fieldSel(NAME), { timeout: 5000 });
    await page.waitForTimeout(250);
    await page.locator('#secrets-modal').screenshot({ path: `${SHOTS}/v2-expanded-revealed-${tag}.png` });

    // 4) 收起回原样：改值 → 保存 → 收起，常态行回归 + 「已保存 ✓」瞬闪
    await page.fill(fieldSel(NAME), 'sk-inline-EDIT-2026-rotated');
    await page.click(saveSel(NAME));
    await page.waitForSelector(fieldSel(NAME), { state: 'detached', timeout: 5000 });
    await page.waitForFunction(() => (document.querySelector('.secrets-row[data-name="api-token-prod"] .secrets-row-status')?.textContent || '').length > 0, undefined, { timeout: 5000 });
    await page.waitForTimeout(150);
    await page.locator('#secrets-modal').screenshot({ path: `${SHOTS}/v2-restored-${tag}.png` });
    await page.waitForTimeout(1800); // 让「已保存 ✓」衰减，回到纯常态

    // 5) 新建草稿行（虚线尾行原位展开）
    await page.click('.secrets-add-trigger');
    await page.waitForSelector('.secrets-add-row[data-open] [data-draft-name]', { timeout: 5000 });
    await page.fill('[data-draft-name]', 'fresh-inline-key');
    await page.fill('[data-draft-value]', 'sk-brand-new-value');
    await page.waitForTimeout(250);
    await page.locator('#secrets-modal').screenshot({ path: `${SHOTS}/v2-draft-${tag}.png` });
    await page.keyboard.press('Escape'); // discard
    await page.waitForTimeout(200);

    // 6) 删除确认（玻璃；删除钮在展开区内）
    await expandRow(page, NAME);
    await page.locator(delSel(NAME)).click();
    await page.waitForSelector('#modal-overlay.on #delete-box', { timeout: 5000 });
    await page.waitForTimeout(300);
    await page.screenshot({ path: `${SHOTS}/v2-delete-confirm-${tag}.png` });

    expect(h.valueCalls.length, 'the shot run fetched the value exactly once (the single reveal)').toBe(1);
    expect(h.mutations.filter((m) => m.method !== 'PUT' && m.method !== 'DELETE').length, 'shots only PUT the demo rotate (no create)').toBe(0);
  });
}
await shots('light', 'light');
await shots('dark', 'dark');

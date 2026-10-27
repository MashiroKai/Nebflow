// secrets-ui-redesign.spec.mjs — 密钥面板 & Jev 配置 UI 重设计验收 spec。
//
// 设计稿：/tmp/nebflow-coldstart/general/20261008_232800_secrets-ui-redesign-proto.md
// + proto-secrets/index.html（2026-10-08 批准方向：除创建/修改弹窗本身以外的全部项）。
//
// SELF-CONTAINED（settings-cleanup.spec.mjs 同款 harness）：隔离静态服务器
// 127.0.0.1:8187 + route mock API + routeWebSocket mock WS，零真实后端、零端口冲突。
//
// R1 i18n 裸键名回归 — settings.jevHealth / jevHealthUp / jevHealthDown /
//    jevHealthUnconfigured 四键 zh/en 均存在；健康行 label 渲染「分配通道」，
//    设置 DOM 不再出现 settings.jevHealth* 裸键名（t() 未命中回退键名的 bug）。
// R2 Jev 卡自含标题 — 外层 .settings-section-title 无 Jev；.cfg-card-title
//    承载标题，toggle 骑卡头；jev-panel-threefix（2026-10-09 三改①）改写：
//    收起态=标题+开关一行（enabled 也不展开），行体点击展开，折叠不清值。
// R3 keyRef 遮蔽 — 默认 -webkit-text-security:disc；点睛显/隐互换，
//    aria-label/aria-pressed 随态；hint 只剩安全关键句一行。
// R4 健康徽标三态 — up → [data-tone=ok]+activity+正常；down → err +
//    octagon-alert+异常+reason（.badge-reason 截断，title 全文）；
//    unconfigured → mut+circle-dashed+未配置；fetch 失败 → 「…」占位留存。
// R5 保存内联化 — PUT 成功 → #jev-save-state 行内「已保存 ✓」（st-ok 图标），
//    按钮文字恒为「保存」（不再换字），1.5s 后反馈清空；失败 → 「保存失败」。
// R6 密钥入口行 — 虚线满宽 .cfg-btn-add → .secrets-entry 玻璃行卡：
//    key 图标 +「密钥管理」+「N 个密钥」+ count pill + chevron；0 个 →
//    「尚无密钥」+ 0；点击开独立弹窗，Esc 关闭后焦点回入口行。
// R7 面板行卡重排 → 二轮极简 + 三轮微调（2026-10-09 23:06）：常态行 = 名称+
//    权限徽标（收起态不再渲染值点点）；点行原位展开后 meta 图标化
//    （box/clock/link）+ tabular-nums、遮蔽占位逐字节一颗点（长度保真）、
//    操作钮在展开区行尾右对齐（justify-content:flex-end）；wire 形状里的
//    value 字段绝不渲染（元数据 only 纪律不变）。
// R8 弹窗退役（行内化 2026-10-09 07:42；二轮 08:32 原位展开）— 编辑器子弹窗
//    #secrets-form 与 FAB #secrets-fab 不复存在；常态行无字段，点行才在原位
//    建出遮蔽空值字段；创建入口 = 列表尾部虚线「＋ 新建密钥」行。
//
// 截图（亮/暗双主题）→ /tmp/nb-secrets-redesign/shots/
// Run: npx playwright test tests/secrets-ui-redesign.spec.mjs

import { test, expect } from '@playwright/test';
import { createServer } from 'node:http';
import { readFile } from 'node:fs/promises';
import { join, dirname, extname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const WEB = resolve(HERE, '..', 'src', 'main', 'resources', 'web');
const PORT = 8187; // fixed, 8100+ per task discipline — never the host 8080
const SHOTS = '/tmp/nb-secrets-redesign/shots';
const MIME = { '.js': 'text/javascript', '.css': 'text/css', '.html': 'text/html', '.svg': 'image/svg+xml', '.png': 'image/png' };

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

/** @typedef {{status: 'up'|'down'|'unconfigured', reason?: string}} HealthState */

/**
 * Boot the real shell against mocks.
 * @param {import('@playwright/test').Page} page
 * @param {{locale?: string, colorScheme?: 'light'|'dark', jev?: object|null,
 *           health?: HealthState|null, healthFail?: boolean,
 *           secrets?: object[]|null, secretsFail?: boolean}} opts
 */
async function bootPage(page, opts = {}) {
  const {
    locale = 'zh-CN', colorScheme = 'light',
    jev = { enabled: true, provider: 'typesafe-jev', endpoint: 'https://api.typesafe.ai/v1/systemone', keyRef: 'typesafe-api-key' },
    health = { status: 'unconfigured' }, healthFail = false,
    secrets = null, secretsFail = false,
  } = opts;
  await page.emulateMedia({ colorScheme });
  await page.addInitScript((l) => {
    localStorage.setItem('nebflow_token', 't');
    localStorage.setItem('neblink_token', 't');
    localStorage.setItem('nebflow_locale', l);
    localStorage.setItem('neblink_locale', l);
  }, locale);
  await page.route('**/api/**', r => r.fulfill({ json: {} })); // catch-all first (lowest priority)
  await page.route('**/api/decision/health', r => {
    if (healthFail) return r.fulfill({ status: 500, body: 'boom' });
    return r.fulfill({ json: { decision: health } });
  });
  await page.route('**/api/secrets', r => {
    if (secretsFail) return r.fulfill({ status: 500, body: 'boom' });
    // rows deliberately carry a `value` field that must NEVER render (R7).
    return r.fulfill({ json: { secrets: secrets || [] } });
  });

  const frames = [];
  let wsSend = () => {};
  await page.routeWebSocket(/\/ws/, ws => {
    wsSend = obj => ws.send(JSON.stringify(obj));
    ws.onMessage(raw => {
      let m; try { m = JSON.parse(raw); } catch { return; }
      frames.push(m);
      if (m.type === 'getHistory') ws.send(JSON.stringify({ type: 'historyPage', sessionId: m.sessionId, messages: [], hasMore: false, offset: 0 }));
    });
    ws.send(JSON.stringify({
      type: 'configData', configured: true, onboarding: 'done', models: [], defaults: {},
      config: JSON.stringify({ jev }),
    }));
    ws.send(JSON.stringify({ type: 'serverConfig', version: '1.4.0', streamTimeoutMs: 600000 }));
    ws.send(JSON.stringify({ type: 'sessionList', sessions: [], activeId: null, folders: [] }));
  });

  await page.goto(base + '/index.html');
  await page.waitForSelector('#activity-bar', { timeout: 15000 });
  return { frames, send: obj => wsSend(obj) };
}

/** Open the settings modal and wait for render. */
async function openSettings(page) {
  await page.click('#settings-btn');
  await page.waitForSelector('#settings-content .settings-section', { timeout: 10000 });
}

// ══ R1 · i18n 裸键名回归 ═══════════════════════════════════
for (const [locale, exp] of [
  ['zh-CN', { health: '分配通道', up: '正常', down: '异常', unconf: '未配置', jevTitle: 'Jev（插件分配）' }],
  ['en', { health: 'Allocation channel', up: 'Up', down: 'Down', unconf: 'Not configured', jevTitle: 'Jev (plugin allocation)' }],
]) {
  test(`R1 [${locale}] jevHealth keys resolve — bare key names never reach the DOM`, async ({ page }) => {
    await bootPage(page, { locale, health: { status: 'down', reason: 'jev leg: 502 bad gateway' } });
    await openSettings(page);
    // The tri-state fetch is async — wait for the badge to resolve before reading it.
    await page.waitForFunction(() => (document.getElementById('settings-jev-health')?.textContent || '').trim() !== '…', undefined, { timeout: 5000 });
    const dump = await page.evaluate(async ([exp]) => {
      const zh = Object.keys((await import('/js/locales/zh-CN.js')).default);
      const en = Object.keys((await import('/js/locales/en.js')).default);
      const keys = ['settings.jevHealth', 'settings.jevHealthUp', 'settings.jevHealthDown', 'settings.jevHealthUnconfigured'];
      const label = [...document.querySelectorAll('#settings-content .cfg-row-label')]
        .find(e => e.textContent.trim() === exp.health);
      const leaked = /settings\.jevHealth/.test(document.getElementById('settings-content').textContent);
      return {
        keySets: { zh: keys.every(k => zh.includes(k)), en: keys.every(k => en.includes(k)) },
        labelThere: !!label,
        // The label's row badge reads the down word (not the key).
        rowText: label?.parentElement?.textContent || '',
        leaked,
        parity: { onlyZh: zh.filter(k => !en.includes(k)), onlyEn: en.filter(k => !zh.includes(k)) },
      };
    }, [exp]);
    expect(dump.keySets.zh, 'zh table carries all four keys').toBe(true);
    expect(dump.keySets.en, 'en table carries all four keys').toBe(true);
    expect(dump.parity.onlyZh, 'zh/en parity holds').toEqual([]);
    expect(dump.parity.onlyEn, 'zh/en parity holds').toEqual([]);
    expect(dump.labelThere, `health row label renders "${exp.health}"`).toBe(true);
    expect(dump.rowText, 'down word rendered, not the key').toContain(exp.down);
    expect(dump.leaked, 'no settings.jevHealth* bare key name anywhere in settings DOM').toBe(false);
  });
}

// ══ R2 · Jev 卡自含标题 + 折叠不清值 ═════════════════════════
// jev-panel-threefix（2026-10-09 用户反馈①）改写：收起态 = 标题+开关一行
// （enabled=true 也不展开）；展开唯一入口 = 卡头行体 #jev-head（开关解耦，
// 只作用法闸）。折叠不清值断言改走 keyRef 字段（端点随三改②对 typesafe-jev
// 钉死禁用，fill 不再适用）。
test('R2 Jev card is self-titled; head-row click expands (toggle decoupled); collapse never clears values', async ({ page }) => {
  await bootPage(page, { locale: 'zh-CN' });
  await openSettings(page);
  const head = await page.evaluate(() => {
    const titles = [...document.querySelectorAll('#settings-content .settings-section-title')].map(e => e.textContent.trim());
    const card = document.getElementById('jev-card');
    const title = card?.querySelector('.cfg-card-title');
    const toggle = card?.querySelector('.cfg-card-head #toggle-jev');
    const body = document.getElementById('jev-detail-body');
    return {
      titles, hasCard: !!card,
      cardTitle: title?.textContent.trim() || '',
      toggleInHead: !!toggle,
      bodyVisible: !!body && body.style.display !== 'none',
      headBtn: !!document.getElementById('jev-head'),
    };
  });
  expect(head.hasCard, 'the Jev glass card renders').toBe(true);
  expect(head.cardTitle, 'card title carries the Jev heading').toBe('Jev（插件分配）');
  expect(head.titles, 'the outer section title is gone (one title layer)').not.toContain('Jev（插件分配）');
  expect(head.toggleInHead, 'the toggle rides the card head').toBe(true);
  expect(head.headBtn, 'the head row is the expand hot zone (三改①)').toBe(true);
  expect(head.bodyVisible, '三改①: collapsed by default even when enabled (toggle-only resting row)').toBe(false);

  // Collapse ≠ clear: expand, edit, collapse, re-expand — the value survives.
  await page.click('#jev-head');
  await page.waitForFunction(() => document.getElementById('jev-detail-body')?.style.display === 'block');
  await page.fill('#cfg-jev-keyref', 'changed-key-name');
  await page.click('#jev-head');
  await page.waitForFunction(() => document.getElementById('jev-detail-body')?.style.display === 'none');
  await page.click('#jev-head');
  await page.waitForFunction(() => document.getElementById('jev-detail-body')?.style.display === 'block');
  const kept = await page.inputValue('#cfg-jev-keyref');
  expect(kept, 'collapse/expand keeps the input value in the DOM').toBe('changed-key-name');
});

// ══ R3 · keyRef 明文直显（作者令 2026-10-27）+ 精简 hint ══════════════════
test('R3 keyRef starts plaintext (author 2026-10-27), eye re-masks/unmasks, hint is one safety line', async ({ page }) => {
  await bootPage(page, { locale: 'zh-CN' });
  await openSettings(page);
  await page.click('#jev-head'); // jev-panel-threefix 三改①: fields live in the expanded detail
  await page.waitForFunction(() => document.getElementById('jev-detail-body')?.style.display === 'block');
  const initial = await page.evaluate(() => {
    const input = document.getElementById('cfg-jev-keyref');
    const eye = document.getElementById('jev-keyref-eye');
    const hints = [...document.querySelectorAll('#jev-card .cfg-hint')].map(e => e.textContent.trim());
    return {
      value: input?.value || '',
      security: input ? getComputedStyle(input).webkitTextSecurity : '',
      pressed: eye?.getAttribute('aria-pressed'),
      label: eye?.getAttribute('aria-label'),
      hints,
      card: !!document.getElementById('jev-card'),
    };
  });
  expect(initial.card, 'jev card present').toBe(true);
  expect(initial.value, 'seeded keyRef name survives the render').toBe('typesafe-api-key');
  expect(initial.security, '🔴 plaintext by default (author 2026-10-27 明文直显) — no mask').toBe('none');
  expect(initial.pressed, 'aria-pressed=true while revealed').toBe('true');
  expect(initial.label, 'aria-label names the hide action').toBe('隐藏密钥引用');
  expect(initial.hints, 'the hint is exactly the one safety-critical line')
    .toEqual(['keyRef 是 ~/.nebflow/secrets/ 下的密钥名（指针），永远不是密钥值。']);

  await page.click('#jev-keyref-eye');
  const masked = await page.evaluate(() => {
    const input = document.getElementById('cfg-jev-keyref');
    const eye = document.getElementById('jev-keyref-eye');
    return {
      value: input.value,
      security: getComputedStyle(input).webkitTextSecurity,
      pressed: eye.getAttribute('aria-pressed'),
      label: eye.getAttribute('aria-label'),
      glyph: eye.innerHTML,
    };
  });
  expect(masked.value, 'masking is presentation-only — the value stays in the field').toBe('typesafe-api-key');
  expect(masked.security, 'eye click → masked (disc)').toBe('disc');
  expect(masked.pressed, 'aria-pressed=false while masked').toBe('false');
  expect(masked.label, 'aria-label names the reveal action').toBe('显示密钥引用');
  expect(masked.glyph, 'glyph swaps to the plain eye (reveal affordance)').toContain('M1 12s4-8');

  await page.click('#jev-keyref-eye');
  const back = await page.evaluate(() => getComputedStyle(document.getElementById('cfg-jev-keyref')).webkitTextSecurity);
  expect(back, 'second click reveals again').toBe('none');
});

// ══ R4 · 健康徽标三态 ═══════════════════════════════════════
const HEALTH_CASES = [
  { name: 'up', health: { status: 'up' }, tone: 'ok', word: '正常', icon: 'activity', reason: false },
  { name: 'down', health: { status: 'down', reason: 'jev leg: 502 bad gateway' }, tone: 'err', word: '异常', icon: 'octagon-alert', reason: true },
  { name: 'unconfigured', health: { status: 'unconfigured' }, tone: 'mut', word: '未配置', icon: 'circle-dashed', reason: false },
];
for (const c of HEALTH_CASES) {
  test(`R4 health badge [${c.name}] → tone=${c.tone}, icon=${c.icon}${c.reason ? ' + truncated reason' : ''}`, async ({ page }) => {
    await bootPage(page, { locale: 'zh-CN', health: c.health });
    await openSettings(page);
    await page.waitForFunction(() => (document.getElementById('settings-jev-health')?.textContent || '').trim() !== '…');
    const dump = await page.evaluate(() => {
      const b = document.getElementById('settings-jev-health');
      const reasonEl = b.querySelector('.badge-reason');
      return {
        tone: b.dataset.tone,
        text: b.textContent.trim(),
        iconSvg: !!b.querySelector('svg.st-ic'),
        iconClass: b.querySelector('svg.st-ic')?.classList.contains('st-ok') ? 'st-ok'
          : b.querySelector('svg.st-ic')?.classList.contains('st-err') ? 'st-err' : '',
        reasonText: reasonEl ? reasonEl.textContent : '',
        title: b.title,
        truncate: reasonEl ? getComputedStyle(reasonEl).textOverflow : '',
      };
    });
    expect(dump.tone, `data-tone=${c.tone}`).toBe(c.tone);
    expect(dump.text, `word = ${c.word}`).toContain(c.word);
    expect(dump.iconSvg, 'a status icon renders').toBe(true);
    if (c.icon === 'activity') expect(dump.iconClass, 'activity rides st-ok').toBe('st-ok');
    if (c.icon === 'octagon-alert') expect(dump.iconClass, 'octagon-alert rides st-err').toBe('st-err');
    if (c.reason) {
      expect(dump.reasonText, 'reason rides the badge (truncation lane)').toContain('jev leg: 502 bad gateway');
      expect(dump.title, 'title carries the full reason').toBe('jev leg: 502 bad gateway');
      expect(dump.truncate, 'reason truncates with ellipsis').toBe('ellipsis');
    } else {
      expect(dump.reasonText, 'no reason tail').toBe('');
    }
  });
}
test('R4 health probe failure keeps the muted … placeholder', async ({ page }) => {
  await bootPage(page, { locale: 'zh-CN', healthFail: true });
  await openSettings(page);
  await page.waitForTimeout(600);
  const dump = await page.evaluate(() => {
    const b = document.getElementById('settings-jev-health');
    return { text: b.textContent.trim(), tone: b.dataset.tone };
  });
  expect(dump.text, 'placeholder survives the failed probe').toBe('…');
  expect(dump.tone, 'placeholder is muted').toBe('mut');
});

// ══ R5 · 保存内联化 ══════════════════════════════════════════
test('R5 save is inlined: role=status feedback, button text constant, 1.5s decay', async ({ page }) => {
  await bootPage(page, { locale: 'zh-CN' });
  let putMode = 'ok';
  await page.route('**/api/config/jev', r => {
    if (r.request().method() !== 'PUT') return r.fulfill({ json: {} });
    return r.fulfill({ status: putMode === 'ok' ? 200 : 500, json: {} });
  });
  await openSettings(page);
  await page.click('#jev-head'); // jev-panel-threefix 三改①: the save action lives in the expanded detail
  await page.waitForFunction(() => document.getElementById('jev-detail-body')?.style.display === 'block');
  await page.click('#btn-save-jev');
  await page.waitForFunction(() => (document.getElementById('jev-save-state')?.textContent || '').length > 0);
  const okState = await page.evaluate(() => {
    const s = document.getElementById('jev-save-state');
    const btn = document.getElementById('btn-save-jev');
    return {
      text: s.textContent.trim(), icon: !!s.querySelector('svg.st-ok'),
      role: s.getAttribute('role'), btnText: btn.textContent.trim(),
      btnPrimary: btn.classList.contains('cfg-btn-primary'),
      right: btn.getBoundingClientRect().right,
      stateLeft: s.getBoundingClientRect().left,
      row: btn.closest('.card-actions')?.className || '',
    };
  });
  expect(okState.text, 'inline feedback shows 已保存 ✓').toContain('已保存 ✓');
  expect(okState.icon, 'with the st-ok icon').toBe(true);
  expect(okState.role, 'feedback is a status live region').toBe('status');
  expect(okState.btnText, 'button text stays 保存 (no swap)').toBe('保存');
  expect(okState.btnPrimary, 'save button is the primary face').toBe(true);
  expect(okState.row, 'feedback sits in the card-bottom action row').toContain('card-actions');
  expect(okState.right, 'button right-aligned at the row end').toBeGreaterThan(okState.stateLeft);

  await page.waitForFunction(() => !(document.getElementById('jev-save-state')?.textContent || '').length, null, { timeout: 3000 });
  const decayed = await page.evaluate(() => document.getElementById('jev-save-state').textContent);
  expect(decayed, 'feedback clears after ~1.5s').toBe('');

  putMode = 'fail';
  await page.click('#btn-save-jev');
  await page.waitForFunction(() => (document.getElementById('jev-save-state')?.textContent || '').length > 0);
  const failState = await page.evaluate(() => ({
    text: document.getElementById('jev-save-state').textContent.trim(),
    icon: !!document.querySelector('#jev-save-state svg.st-err'),
  }));
  expect(failState.text, 'failure shows 保存失败 inline').toContain('保存失败');
  expect(failState.icon, 'with the st-err icon').toBe(true);
});

// ══ R6 · 密钥入口行 ══════════════════════════════════════════
const SECRETS_THREE = [
  { name: 'typesafe-api-key', size: 812, mtime: 1728383640000, mode: '0600', refs: ['jev.keyRef'], value: 'TOPSECRET-VALUE' },
  { name: 'social-x-auth', size: 1248, mtime: 1728295320000, mode: '0600', refs: [], value: 'TOPSECRET-VALUE' },
  { name: 'legacy-backup-key', size: 96, mtime: 1726140180000, mode: '0644', refs: [], value: 'TOPSECRET-VALUE' },
];
test('R6 entry row: glass row card with icon + name + count sub + pill + chevron', async ({ page }) => {
  await bootPage(page, { locale: 'zh-CN', secrets: SECRETS_THREE });
  await openSettings(page);
  await page.waitForFunction(() => document.getElementById('secrets-entry-pill')?.textContent === '3');
  const dump = await page.evaluate(() => {
    const e = document.getElementById('btn-open-secrets');
    return {
      isEntry: e.classList.contains('secrets-entry'),
      notDashed: !e.classList.contains('cfg-btn-add'),
      name: e.querySelector('.entry-name')?.textContent.trim(),
      sub: e.querySelector('.entry-sub')?.textContent.trim(),
      pill: document.getElementById('secrets-entry-pill')?.textContent,
      icons: e.querySelectorAll('svg.st-ic').length,
      title: [...document.querySelectorAll('#settings-content .settings-section-title')].map(t => t.textContent.trim()),
    };
  });
  expect(dump.isEntry, 'the entry is the glass row card').toBe(true);
  expect(dump.notDashed, 'the dashed full-width add-button is gone').toBe(true);
  expect(dump.name, 'row name = 密钥管理').toBe('密钥管理');
  expect(dump.sub, 'subtitle counts the secrets').toBe('3 个密钥');
  expect(dump.pill, 'count pill shows 3').toBe('3');
  expect(dump.icons, 'key icon + chevron').toBe(2);
  expect(dump.title, 'section keeps its 密钥 title').toContain('密钥');

  await page.click('#btn-open-secrets');
  await page.waitForSelector('#secrets-overlay.on', { timeout: 5000 });
  await page.keyboard.press('Escape');
  await page.waitForFunction(() => !document.getElementById('secrets-overlay').classList.contains('on'));
  const focusBack = await page.evaluate(() => document.activeElement?.id);
  expect(focusBack, 'focus returns to the entry row after close').toBe('btn-open-secrets');
});
test('R6 zero secrets → 尚无密钥 + 0 pill', async ({ page }) => {
  await bootPage(page, { locale: 'zh-CN', secrets: [] });
  await openSettings(page);
  await page.waitForFunction(() => document.getElementById('secrets-entry-pill')?.textContent === '0');
  const sub = await page.evaluate(() => document.getElementById('secrets-entry-sub')?.textContent.trim());
  expect(sub, 'empty wording').toBe('尚无密钥');
});

// ══ R7 · 面板行卡重排 ════════════════════════════════════════
test('R7 rows rest minimal (name+badge, no dots); meta + length-faithful mask + actions live in the in-place expanded detail; metadata only', async ({ page }) => {
  await bootPage(page, { locale: 'zh-CN', secrets: SECRETS_THREE });
  await openSettings(page);
  await page.click('#btn-open-secrets');
  await page.waitForSelector('#secrets-overlay.on .secrets-row', { timeout: 5000 });
  const dump = await page.evaluate(() => {
    const rows = [...document.querySelectorAll('#secrets-list .secrets-row')];
    return {
      count: rows.length,
      rows: rows.map(row => {
        const head = row.querySelector('[data-row-head]');
        return {
          name: row.dataset.name,
          badge: head?.querySelector('.secrets-mode-badge')?.dataset.mode || '',
          noDots: !head?.querySelector('.secrets-row-dots'),
          noMetaAtRest: !row.querySelector('.secrets-row-meta'),
          noActionsAtRest: !row.querySelector('.secrets-row-actions'),
          noFieldAtRest: !row.querySelector('[data-edit]'),
        };
      }),
      leak: document.body.textContent.includes('TOPSECRET-VALUE'),
    };
  });
  expect(dump.count, 'three rows render').toBe(3);
  const byName = Object.fromEntries(dump.rows.map(r => [r.name, r]));
  expect(byName['typesafe-api-key'].badge, '0600 → neutral ok badge on the resting line').toBe('ok');
  expect(byName['legacy-backup-key'].badge, '0644 → permissive badge stays on the list face').toBe('permissive');
  for (const r of dump.rows) {
    expect(r.noDots, `${r.name}: 🔴 收起态不渲染遮蔽点（三轮 23:06）`).toBe(true);
    expect(r.noMetaAtRest, `${r.name}: meta moved into the detail (常态极简)`).toBe(true);
    expect(r.noActionsAtRest, `${r.name}: no buttons on the resting row`).toBe(true);
    expect(r.noFieldAtRest, `${r.name}: no edit field on the resting row`).toBe(true);
  }

  // 展开一行：meta（box/clock/link 图标化、tabular-nums）与右对齐 actions 在原位展开区
  await page.click('[data-row-head="typesafe-api-key"]');
  await page.waitForSelector('.secrets-row[data-name="typesafe-api-key"] .secrets-row-detail', { timeout: 5000 });
  const open = await page.evaluate(() => {
    const row = document.querySelector('.secrets-row[data-name="typesafe-api-key"]');
    const meta = row?.querySelector('.secrets-row-meta');
    const actions = row?.querySelector('.secrets-row-actions');
    return {
      metaItems: meta?.querySelectorAll('.m').length,
      metaIcons: meta?.querySelectorAll('.m svg.st-ic').length,
      metaText: meta?.textContent.trim() || '',
      tabular: meta ? getComputedStyle(meta).fontVariantNumeric.includes('tabular-nums') : false,
      actionsAlign: actions ? getComputedStyle(actions).justifyContent : '',
      phLen: row?.querySelector('[data-edit]')?.placeholder?.length || 0,
    };
  });
  expect(open.metaItems, 'box + clock + link items').toBe(3);
  expect(open.metaIcons, 'each meta item carries its glyph').toBe(3);
  expect(open.metaText, 'meta carries size/mtime/reference names').toContain('812 B');
  expect(open.metaText).toContain('被引用：jev.keyRef');
  expect(open.tabular, 'meta figures are tabular-nums').toBe(true);
  expect(open.actionsAlign, 'actions right-aligned at detail end').toBe('flex-end');
  expect(open.phLen, 'expanded mask placeholder = one dot per stored byte (三轮 长度保真)').toBe(SECRETS_THREE[0].size);
  expect(dump.leak, '🔴 the wire `value` field never reaches any render').toBe(false);
});

// ══ R8 · 弹窗退役（行内化 2026-10-09 07:42）═════════════════════════════
test('R8 editor modal retired: no #secrets-form / #secrets-fab; resting rows carry no field — clicking builds the masked field in place', async ({ page }) => {
  await bootPage(page, { locale: 'zh-CN', secrets: SECRETS_THREE });
  await openSettings(page);
  await page.click('#btn-open-secrets');
  await page.waitForSelector('#secrets-overlay.on .secrets-row', { timeout: 5000 });
  const dump = await page.evaluate(() => ({
    form: document.getElementById('secrets-form'),
    fab: document.getElementById('secrets-fab'),
    fieldsAtRest: document.querySelectorAll('#secrets-list .secrets-row [data-edit]').length,
    addRow: !!document.querySelector('#secrets-list .secrets-add-row .secrets-add-trigger'),
  }));
  expect(dump.form, '🔴 the editor sub-dialog is retired').toBeNull();
  expect(dump.fab, '🔴 the FAB is retired').toBeNull();
  expect(dump.fieldsAtRest, '🔴 resting rows carry NO inline field (二轮极简)').toBe(0);
  expect(dump.addRow, 'creation is the dashed tail row').toBe(true);

  await page.click('[data-row-head="typesafe-api-key"]');
  await page.waitForSelector('.secrets-row[data-name="typesafe-api-key"] [data-edit]', { timeout: 5000 });
  const opened = await page.evaluate(() => ({
    only: document.querySelectorAll('#secrets-list .secrets-row [data-edit]').length,
    f: (() => { const x = document.querySelector('.secrets-row[data-name="typesafe-api-key"] [data-edit]'); return x instanceof HTMLTextAreaElement ? { masked: x.classList.contains('masked'), value: x.value } : null; })(),
  }));
  expect(opened.only, 'the expanded row builds exactly one field, in place').toBe(1);
  expect(opened.f?.masked && opened.f?.value === '', 'the built field starts masked + empty').toBe(true);
});

// ══ 双主题截图（健康徽标+遮蔽卡 / 入口行 / 面板行卡）══════════
async function shots(colorScheme, tag) {
  test(`shots[${tag}] jev card (masked + health down) / entry row / panel rows`, async ({ page }) => {
    // 1) Jev 卡：keyRef 遮蔽态 + 健康徽标 down 态
    await bootPage(page, {
      locale: 'zh-CN', colorScheme,
      health: { status: 'down', reason: 'jev leg: 502 bad gateway' },
      secrets: SECRETS_THREE,
    });
    await openSettings(page);
    await page.waitForFunction(() => (document.getElementById('settings-jev-health')?.textContent || '').trim() !== '…');
    await page.waitForFunction(() => document.getElementById('secrets-entry-pill')?.textContent === '3');
    // jev-panel-threefix（2026-10-09 三改①）：卡常驻收起一行——截图展开细节态。
    await page.click('#jev-head');
    await page.waitForFunction(() => document.getElementById('jev-detail-body')?.style.display === 'block');
    await page.locator('#jev-card').screenshot({ path: `${SHOTS}/jev-card-masked-health-${tag}.png` });

    // 2) 密钥入口行特写
    await page.locator('#btn-open-secrets').screenshot({ path: `${SHOTS}/secrets-entry-${tag}.png` });

    // 3) 面板行卡（重排后）
    await page.click('#btn-open-secrets');
    await page.waitForSelector('#secrets-overlay.on .secrets-row', { timeout: 5000 });
    await page.locator('#secrets-modal').screenshot({ path: `${SHOTS}/secrets-panel-rows-${tag}.png` });
  });
}
await shots('light', 'light');
await shots('dark', 'dark');

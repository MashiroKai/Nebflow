// 🔴 provider-add-tabs 批（2026-11-06 作者令「添加服务商统一成标签切换类型 +
// 单一服务商面板」）换代注：R2（Jev 卡折叠面）/ R3（keyRef 明文直显 + eye）/
// R5（#btn-save-jev 内联保存反馈）随旧 Jev 表单**整件退役**——keyRef 不再是
// 用户可编辑字段（统一表单 = 派生名对用户不可见，T2 fourfix「前端零 keyRef
// 字眼」口径推广到 JeV），保存反馈 = 统一 toast。存活语义改由别件守：开关解耦
// + 端点钉死 + 保存形状 → jev-panel-threefix.spec（R1'–R3b'）；健康徽标三态 →
// 同件 R4*。R1/R4 改走统一面板（Jev 标签选中后断言）。本文件余下测试（R6–R8
// 密钥面板自身）不受影响。
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
    // 统一面板（provider-add-tabs 批）：徽标与标签行在 Jev 标签选中态。
    await page.evaluate(() => document.querySelector('.nf-ptab[data-provider-type="jev"]')?.click());
    await page.waitForSelector('.nf-provider-panel[data-type="jev"]', { timeout: 5000 });
    // The tri-state fetch is async — wait for the badge to resolve before reading it.
    await page.waitForFunction(() => (document.getElementById('settings-jev-health')?.textContent || '').trim() !== '…', undefined, { timeout: 5000 });
    const dump = await page.evaluate(async ([exp]) => {
      const zh = Object.keys((await import('/js/locales/zh-CN.js')).default);
      const en = Object.keys((await import('/js/locales/en.js')).default);
      const keys = ['settings.jevHealth', 'settings.jevHealthUp', 'settings.jevHealthDown', 'settings.jevHealthUnconfigured'];
      const label = [...document.querySelectorAll('#settings-content .settings-label')]
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

// （R2 已随统一面板退役——见文件头 provider-add-tabs 换代注；语义承接 =
// jev-panel-threefix.spec R1'/R2'。）

// （R3 已随统一面板退役——keyRef 不再是用户可见字段，见文件头换代注；
// key 字段语义 = settings-key-reveal.spec T1（统一表单版）。）

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
    // 统一面板：徽标在 Jev 标签选中态。
    await page.evaluate(() => document.querySelector('.nf-ptab[data-provider-type="jev"]')?.click());
    await page.waitForSelector('.nf-provider-panel[data-type="jev"]', { timeout: 5000 });
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
  await page.evaluate(() => document.querySelector('.nf-ptab[data-provider-type="jev"]')?.click());
  await page.waitForSelector('.nf-provider-panel[data-type="jev"]', { timeout: 5000 });
  await page.waitForTimeout(600);
  const dump = await page.evaluate(() => {
    const b = document.getElementById('settings-jev-health');
    return { text: b.textContent.trim(), tone: b.dataset.tone };
  });
  expect(dump.text, 'placeholder survives the failed probe').toBe('…');
  expect(dump.tone, 'placeholder is muted').toBe('mut');
});

// （R5 已随统一面板退役——统一表单保存反馈 = toast『已保存』，见文件头换代注。）

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
// R7 换代注（provider-add-tabs 批 2026-11-06 收敛存量红）：本判据的「mode 徽标」
// 断言随作者裁定 2026-11-01「不要提示什么权限过宽…这徽标整概念退役」失效
// （secretsPanel.rowHTML 不再渲染 .secrets-mode-badge，wire mode 字段保留不读）；
// 「meta 在展开区」断言随同日网格化重排失效（meta 现常驻收起行头）。本件按现行
// 行契约改钉：收起行 = 名 + meta（size·mtime，tabular-nums）；展开区 = 长度保真
// 占位 + eye + 右对齐 actions + 被引用行；值面零渲染。
test('R7 rows rest minimal (name + meta line, no dots); expanded detail = length-faithful mask + actions; metadata only', async ({ page }) => {
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
          // 2026-11-01 作者裁定：权限徽标整概念退役（wire mode 不再上屏）。
          noModeBadge: !head?.querySelector('.secrets-mode-badge'),
          noDots: !head?.querySelector('.secrets-row-dots'),
          metaAtRest: !!row.querySelector('.secrets-row-meta'),
          metaText: row.querySelector('.secrets-row-meta')?.textContent.trim() || '',
          tabular: (() => { const m = row.querySelector('.secrets-row-meta'); return m ? getComputedStyle(m).fontVariantNumeric.includes('tabular-nums') : false; })(),
          noActionsAtRest: !row.querySelector('.secrets-row-actions'),
          noFieldAtRest: !row.querySelector('[data-edit]'),
        };
      }),
      leak: document.body.textContent.includes('TOPSECRET-VALUE'),
    };
  });
  expect(dump.count, 'three rows render').toBe(3);
  for (const r of dump.rows) {
    expect(r.noModeBadge, `${r.name}: 权限徽标零渲染（作者 2026-11-01 裁定）`).toBe(true);
    expect(r.noDots, `${r.name}: 🔴 收起态不渲染遮蔽点（三轮 23:06）`).toBe(true);
    expect(r.metaAtRest, `${r.name}: meta 常驻收起行（网格化批）`).toBe(true);
    expect(r.metaText, `${r.name}: meta carries size`).toContain('B');
    expect(r.tabular, `${r.name}: meta figures are tabular-nums`).toBe(true);
    expect(r.noActionsAtRest, `${r.name}: no buttons on the resting row`).toBe(true);
    expect(r.noFieldAtRest, `${r.name}: no edit field on the resting row`).toBe(true);
  }
  const zhipu = dump.rows.find(r => r.name === 'typesafe-api-key');
  expect(zhipu, 'seeded row present').toBeTruthy();

  // 展开一行：长度保真占位 + 被引用行 + 右对齐 actions 在原位展开区
  await page.click('[data-row-head="typesafe-api-key"]');
  await page.waitForSelector('.secrets-row[data-name="typesafe-api-key"] .secrets-row-detail', { timeout: 5000 });
  const open = await page.evaluate(() => {
    const row = document.querySelector('.secrets-row[data-name="typesafe-api-key"]');
    const detail = row?.querySelector('.secrets-row-detail');
    return {
      refsText: detail?.textContent.trim() || '',
      actionsAlign: detail ? getComputedStyle(row.querySelector('.secrets-row-actions') || detail).justifyContent : '',
      phLen: row?.querySelector('[data-edit]')?.placeholder?.length || 0,
    };
  });
  expect(open.refsText, 'detail carries the referenced-by line').toContain('jev.keyRef');
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
    // 1) 服务商面板（provider-add-tabs 批换代：统一面板 Jev 标签态 + 健康徽标 down）
    await bootPage(page, {
      locale: 'zh-CN', colorScheme,
      health: { status: 'down', reason: 'jev leg: 502 bad gateway' },
      secrets: SECRETS_THREE,
    });
    await openSettings(page);
    await page.evaluate(() => document.querySelector('.nf-ptab[data-provider-type="jev"]')?.click());
    await page.waitForSelector('.nf-provider-panel[data-type="jev"]', { timeout: 5000 });
    await page.waitForFunction(() => (document.getElementById('settings-jev-health')?.textContent || '').trim() !== '…');
    await page.waitForFunction(() => document.getElementById('secrets-entry-pill')?.textContent === '3');
    await page.locator('#provider-panel-host').screenshot({ path: `${SHOTS}/provider-panel-jev-health-${tag}.png` });

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

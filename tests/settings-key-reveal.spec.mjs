// settings-key-reveal.spec.mjs — 设置页 apikey 明文直显（作者令 2026-10-27
// 10:33「设置里配置的 apikey，直接显示就行了，比如 JeV 的以及我们 llm api 的」）
// 验收 spec。
//
// 钉四件事（任务 spec 钉逐条）：
//   ① JeV 配置节 keyRef：进入设置页展开即明文（webkitTextSecurity=none），
//      眼睛保留为可选遮蔽（首击上掩码、再击揭示，演示性切换不动值）。
//   ② LLM API 配置节：编辑弹窗的 API Key 字段 = 回读真值明文（owner 鉴权
//      GET /api/config/provider-keys，进页预取 + 卡点点击兜底竞态）；保存
//      逻辑不变——未编辑直存时 payload 里的 apiKey = 所见明文。
//   ③ 保存 → 重载设置页 → 字段明文等于所存值（T3 闭环；存进 parsedConfig 的
//      新值在重渲染后原样回显）。
//   ④ 未配置 = 空态而非掩码（无 key 的 provider 弹窗字段空 + 占位符；JeV 无
//      keyRef 同空态）；回读 403 → 弹窗退回空态（不伪造、不崩——服务端 403
//      门的本体钉在 ConfigKeyRevealSpec.scala）。
//
// SELF-CONTAINED（secrets-readback.spec.mjs 同款 harness）：隔离静态服务器
// 127.0.0.1:8237 + route mock API + routeWebSocket mock WS，零真实后端。
// Run: node node_modules/@playwright/test/cli.js test tests/settings-key-reveal.spec.mjs --workers=1 --reporter=line
//
// ── 存量红清欠 + 配置面风格统一批判据换代（2026-10-31）───────────────────
// T1/T4 旧锚（#jev-head / #jev-detail-body / #jev-keyref-eye）随 2026-10-28
// settings-panel C 批重排已死（现树零命中，main 上即红）。换代：JeV 节为层级
// 下钻面（configDrill.js 浮层收编 #jev-advance-body）——T1/T4 改走
// 「点 #jev-advance-toggle 进浮层」路径；eye 锚 = 现树 #jev-key-eye
// （key 值只读行，作者令 2026-10-28 21:30 明文直显面），并补 key 值回读路由
// _mock（GET /api/secrets/<keyRef>/value）把「值明文直显」一起钉住。

import { test, expect } from '@playwright/test';
import { createServer } from 'node:http';
import { readFile } from 'node:fs/promises';
import { join, dirname, extname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const WEB = resolve(HERE, '..', 'src', 'main', 'resources', 'web');
const PORT = 8237; // fixed, 8100+ per task discipline — never the host 8080
const MIME = { '.js': 'text/javascript', '.css': 'text/css', '.html': 'text/html', '.svg': 'image/svg+xml', '.png': 'image/png' };

const KIMI_KEY = 'sk-real-kimi-reveal-1';
const KIMI_KEY_ROTATED = 'sk-rotated-kimi-2026-reveal';
const PINNED = 'https://api.typesafe.ai/v1/systemone';
const JEV_KEYREF = 'typesafe-api-key';
const JEV_KEY_VALUE = 'ts-real-jev-key-reveal-1';

/** 静态配置底稿（configData 帧；apiKey 形态字段与引擎 getConfig 打码面同形 = "***"）。 */
function makeCfg({ withKimiKey = true, withJevKeyRef = true } = {}) {
  /** @type {any} */
  const cfg = {
    llm: {
      providers: {
        kimi: { baseUrl: 'https://api.kimi.example/v1/', apiKey: withKimiKey ? '***' : '', protocol: 'anthropic', models: [{ id: 'kimi-k2', contextWindow: 8192 }] },
        anon: { baseUrl: 'https://anon.example/v1/', apiKey: '***', protocol: 'openai', models: [{ id: 'anon-1', contextWindow: 4096 }] },
      },
    },
    jev: { enabled: true, provider: 'typesafe-jev', endpoint: PINNED, ...(withJevKeyRef ? { keyRef: JEV_KEYREF } : {}) },
  };
  return cfg;
}

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
 * @param {{locale?: string, revealStatus?: number, revealProviders?: Record<string, string>,
 *           withKimiKey?: boolean, withJevKeyRef?: boolean}} opts
 */
async function bootPage(page, opts = {}) {
  const {
    locale = 'zh-CN', revealStatus = 200,
    revealProviders = { kimi: KIMI_KEY },
    withKimiKey = true, withJevKeyRef = true,
  } = opts;
  await page.emulateMedia({ colorScheme: 'light' });
  await page.addInitScript((l) => {
    localStorage.setItem('nebflow_token', 't');
    localStorage.setItem('neblink_token', 't');
    localStorage.setItem('nebflow_locale', l);
    localStorage.setItem('neblink_locale', l);
  }, locale);

  // Disk-truth sim: tests mutate this to fold a save into the readback face.
  const store = { kimi: KIMI_KEY };
  /** @type {{auth: string, count: number}} */ const reveal = { auth: '', count: 0 };
  /** @type {any[]} */ const wsFrames = [];

  await page.route('**/api/**', (r) => r.fulfill({ json: {} })); // catch-all (lowest priority)
  await page.route('**/api/config/provider-keys', (r) => {
    reveal.count += 1;
    reveal.auth = r.request().headers()['authorization'] || '';
    if (revealStatus !== 200) return r.fulfill({ status: revealStatus, body: 'boom' });
    return r.fulfill({ json: { providers: { ...store, ...revealProviders } } });
  });

  await page.routeWebSocket(/\/ws/, (ws) => {
    ws.onMessage((raw) => {
      let m; try { m = JSON.parse(raw); } catch { return; }
      wsFrames.push(m);
      if (m.type === 'getHistory') ws.send(JSON.stringify({ type: 'historyPage', sessionId: m.sessionId, messages: [], hasMore: false, offset: 0 }));
    });
    ws.send(JSON.stringify({
      type: 'configData', configured: true, onboarding: 'done', models: [], defaults: {},
      config: JSON.stringify(makeCfg({ withKimiKey, withJevKeyRef })),
    }));
    ws.send(JSON.stringify({ type: 'serverConfig', version: '1.4.0', streamTimeoutMs: 600000 }));
    ws.send(JSON.stringify({ type: 'sessionList', sessions: [], activeId: null, folders: [] }));
  });

  await page.goto(base + '/index.html');
  await page.waitForSelector('#activity-bar', { timeout: 15000 });
  return {
    reveal, wsFrames, store,
    updateConfigFrames: () => wsFrames.filter((f) => f.type === 'updateConfig'),
  };
}

async function openSettings(page) {
  await page.click('#settings-btn');
  await page.waitForSelector('#settings-content .settings-section', { timeout: 10000 });
}
/** kimi 编辑弹窗打开（卡点点击自带回读兜底 ⇒ 弹窗出现即回读已落地）。 */
async function openKimiModal(page) {
  await page.click('.cfg-card[data-provider="kimi"]');
  await page.waitForSelector('#cfg-modal [data-field="apiKey"]', { timeout: 5000 });
}
async function keyFieldState(page) {
  return page.evaluate(() => {
    const f = document.querySelector('#cfg-modal [data-field="apiKey"]');
    const eye = document.querySelector('#cfg-modal .cfg-eye-btn');
    return {
      value: f instanceof HTMLInputElement ? f.value : null,
      placeholder: f instanceof HTMLElement ? f.placeholder : '',
      security: f ? getComputedStyle(f).webkitTextSecurity : '',
      eyeGlyph: eye ? eye.innerHTML : '',
    };
  });
}

// ══ T1 · JeV 配置节：进入即明文；眼睛 = 可选遮蔽（演示性切换不动值）═══════
// 配置面风格统一批判据换代（2026-10-31）：JeV 节为层级下钻面——点入口行开
// 浮层收编 #jev-advance-body（旧 #jev-head/#jev-detail-body 锚随 10-28 C 批
// 重排已死，本换代一并修红）；eye 锚 = 现树 #jev-key-eye（key 值只读行）。
test('T1 jev key shows plaintext on entry; the eye masks then reveals without touching the value', async ({ page }) => {
  await bootPage(page);
  // key 值回读面（owner 鉴权单读）——比 catch-all 更具体的路由后注册优先。
  await page.route('**/api/secrets/*/value', (r) => r.fulfill({ json: { value: JEV_KEY_VALUE } }));
  await openSettings(page);
  await page.click('#jev-advance-toggle');
  await page.waitForFunction(() => !!document.getElementById('jev-advance-body')?.closest('.config-drill-overlay'));

  const initial = await page.evaluate(() => {
    const keyRef = document.getElementById('cfg-jev-keyref');
    const value = document.getElementById('cfg-jev-key-value');
    const eye = document.getElementById('jev-key-eye');
    return {
      keyRefValue: keyRef?.value || '',
      keyRefSecurity: keyRef ? getComputedStyle(keyRef).webkitTextSecurity : '',
      valueReadonly: value?.readOnly === true,
      valueFace: value?.value || '',
      valueSecurity: value ? (value.style.webkitTextSecurity || 'none') : '',
      pressed: eye?.getAttribute('aria-pressed'),
      label: eye?.getAttribute('aria-label'),
    };
  });
  expect(initial.keyRefValue, '🔴 已配置的 keyRef 进页即明文（不再是掩码）').toBe(JEV_KEYREF);
  expect(initial.keyRefSecurity, 'keyRef 字段面 = 明文').toBe('none');
  expect(initial.valueReadonly, 'key 值行 = 只读直显行').toBe(true);
  expect(initial.valueFace, '🔴 key 值回读真值明文直显').toBe(JEV_KEY_VALUE);
  expect(initial.valueSecurity, '🔴 default face = plaintext (none), not disc').toBe('none');
  expect(initial.pressed, 'eye starts on the revealed face').toBe('false');
  expect(initial.label, 'eye label names the hide action').toBe('隐藏密钥引用');

  await page.click('#jev-key-eye');
  const masked = await page.evaluate(() => ({
    v: document.getElementById('cfg-jev-key-value').value,
    s: document.getElementById('cfg-jev-key-value').style.webkitTextSecurity,
  }));
  expect(masked.s, 'first eye click masks (disc)').toBe('disc');
  expect(masked.v, 'masking is presentation-only — the value stays').toBe(JEV_KEY_VALUE);

  await page.click('#jev-key-eye');
  const back = await page.evaluate(() => document.getElementById('cfg-jev-key-value').style.webkitTextSecurity);
  expect(back, 'second eye click reveals again').toBe('none');
});

// ══ T2 · LLM API 配置节：弹窗字段 = 回读真值明文；未编辑直存 = 明文进 payload ═
test('T2 provider edit modal prefills the REAL key in plaintext; untouched save sends it verbatim; the readback rides the auth header and fires once', async ({ page }) => {
  const h = await bootPage(page);
  await openSettings(page);
  // 进页预取（renderSettings → revealProviderApiKeys）：恰一次、带 owner 鉴权头。
  await expect.poll(() => h.reveal.count, { timeout: 5000 }).toBe(1);
  expect(h.reveal.auth, 'the readback rides the owner auth header').toBe('Bearer t');

  await openKimiModal(page);
  expect(h.reveal.count, 'card click reuses the prefetch (single-flight/cache, no second fetch)').toBe(1);

  const st = await keyFieldState(page);
  expect(st.value, '🔴 the edit field shows the stored key verbatim (明文直显)').toBe(KIMI_KEY);
  expect(st.security, 'field face is plaintext (no disc), eye = optional mask').toBe('none');
  expect(st.eyeGlyph, 'eye starts on the revealed face (eye-off glyph)').toContain('M17.94');

  // 保存逻辑不变：不动字段直接保存 → updateConfig payload 携带所见明文。
  await page.click('#cfg-modal-save');
  await page.waitForSelector('#cfg-modal', { state: 'detached', timeout: 5000 });
  const saves = h.updateConfigFrames();
  expect(saves.length, 'exactly one updateConfig flush').toBe(1);
  const sent = JSON.parse(saves[0].config);
  expect(sent.llm.providers.kimi.apiKey, '🔴 untouched save carries the displayed plaintext').toBe(KIMI_KEY);
});

// ══ T3 · 保存 → 重载设置页 → 字段明文等于所存值（闭环）═══════════════════
test('T3 save a rotated key, reload the settings page, the field shows the stored value in plaintext', async ({ page }) => {
  const h = await bootPage(page);
  await openSettings(page);
  await openKimiModal(page);

  await page.fill('#cfg-modal [data-field="apiKey"]', KIMI_KEY_ROTATED);
  await page.click('#cfg-modal-save');
  await page.waitForSelector('#cfg-modal', { state: 'detached', timeout: 5000 });
  const sent = JSON.parse(h.updateConfigFrames()[0].config);
  expect(sent.llm.providers.kimi.apiKey, 'the flush carried the rotated key').toBe(KIMI_KEY_ROTATED);

  // 盘上真相翻转（后端语义模拟：保存已落 nebflow.json，回读面返回新真值）。
  h.store.kimi = KIMI_KEY_ROTATED;

  // 重载设置页（Esc 关 → 开，renderSettings 重跑）→ 再开弹窗：明文 = 所存值。
  await page.keyboard.press('Escape'); // closeSettingsPanel（activityBar 钮被居中模态的 backdrop 盖住，Esc 是面板自身的关闭面）
  await page.waitForFunction(() => !document.getElementById('settings-overlay')?.classList.contains('on'), undefined, { timeout: 5000 });
  await openSettings(page);
  await openKimiModal(page);
  const st = await keyFieldState(page);
  expect(st.value, '🔴 after reload the field equals the stored value (plaintext)').toBe(KIMI_KEY_ROTATED);
  expect(st.security, 'still the plaintext face').toBe('none');
});

// ══ T4 · 未配置 = 空态而非掩码 ══════════════════════════════════════════
test('T4 unconfigured faces show the EMPTY state, not a mask: keyless provider → blank field + placeholder; missing jev keyRef → blank', async ({ page }) => {
  await bootPage(page, { revealProviders: {} , withJevKeyRef: false });
  await openSettings(page);

  await page.click('.cfg-card[data-provider="anon"]');
  await page.waitForSelector('#cfg-modal [data-field="apiKey"]', { timeout: 5000 });
  const anon = await keyFieldState(page);
  expect(anon.value, '🔴 no stored key → the field is EMPTY (never "***", never dots)').toBe('');
  expect(anon.placeholder, 'the placeholder carries the keep-*** hint, no fake value').toContain('***');
  expect(anon.security, 'empty state is the plaintext face').toBe('none');
  await page.evaluate(() => document.getElementById('cfg-modal')?.remove());

  // JeV 侧（配置面风格统一批：层级下钻浮层里断言——缺 keyRef = 空态非掩码）。
  await page.click('#jev-advance-toggle');
  await page.waitForFunction(() => !!document.getElementById('jev-advance-body')?.closest('.config-drill-overlay'));
  const jev = await page.evaluate(() => ({
    kr: document.getElementById('cfg-jev-keyref')?.value || '',
    krS: getComputedStyle(document.getElementById('cfg-jev-keyref')).webkitTextSecurity,
    kv: document.getElementById('cfg-jev-key-value')?.value || '',
    kvPh: document.getElementById('cfg-jev-key-value')?.placeholder || '',
    kvS: document.getElementById('cfg-jev-key-value')?.style.webkitTextSecurity || 'none',
  }));
  expect(jev.kr, 'missing jev keyRef → blank (empty state)').toBe('');
  expect(jev.krS, 'blank state is not a mask').toBe('none');
  expect(jev.kv, 'no keyRef ⇒ no value readback — stays blank').toBe('');
  expect(jev.kvS, 'key value blank state is not a mask either').toBe('none');
  expect(jev.kvPh.length, 'placeholder carries the no-ref explanation').toBeGreaterThan(0);
});

// ══ T5 · 回读被拒（403）→ 弹窗退回空态（不伪造、不崩）════════════════════
test('T5 a rejected (403) readback degrades to the legacy blank field — no faked value, no crash', async ({ page }) => {
  const h = await bootPage(page, { revealStatus: 403 });
  await openSettings(page);
  await expect.poll(() => h.reveal.count, { timeout: 5000 }).toBe(1);

  await page.click('.cfg-card[data-provider="kimi"]');
  await page.waitForSelector('#cfg-modal [data-field="apiKey"]', { timeout: 5000 });
  const st = await keyFieldState(page);
  expect(st.value, '🔴 no reveal → empty field (the "***" keep-stored path stays intact)').toBe('');
  // 空字段直存 = 旧语义闭环：payload 回 "***"（引擎保留旧值）。
  await page.click('#cfg-modal-save');
  await page.waitForSelector('#cfg-modal', { state: 'detached', timeout: 5000 });
  const sent = JSON.parse(h.updateConfigFrames()[0].config);
  expect(sent.llm.providers.kimi.apiKey, 'blank save falls back to the "***" keep-marker').toBe('***');
});

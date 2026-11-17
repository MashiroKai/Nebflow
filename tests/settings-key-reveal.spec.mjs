// settings-key-reveal.spec.mjs — 设置页 apikey 明文直显（作者令 2026-10-27
// 10:33「设置里配置的 apikey，直接显示就行了，比如 JeV 的以及我们 llm api 的」）
// 验收 spec。
//
// 钉四件事（任务 spec 钉逐条；①的 JeV 半已随 JeV 退役删除——jev-retire 批
// 2026-11-16）：
//   ② LLM API 配置节：编辑弹窗的 API Key 字段 = 回读真值明文（owner 鉴权
//      GET /api/config/provider-keys，进页预取 + 卡点点击兜底竞态）；保存
//      逻辑不变——未编辑直存时 payload 里的 apiKey = 所见明文。
//   ③ 保存 → 重载设置页 → 字段明文等于所存值（T3 闭环；存进 parsedConfig 的
//      新值在重渲染后原样回显）。
//   ④ 未配置 = 空态而非掩码（无 key 的 provider 弹窗字段空 + 占位符）；回读
//      403 → 弹窗退回空态（不伪造、不崩——服务端 403 门的本体钉在
//      ConfigKeyRevealSpec.scala）。
//
// SELF-CONTAINED（secrets-readback.spec.mjs 同款 harness）：隔离静态服务器
// 127.0.0.1:8237 + route mock API + routeWebSocket mock WS，零真实后端。
// Run: node node_modules/@playwright/test/cli.js test tests/settings-key-reveal.spec.mjs --workers=1 --reporter=line
//
// ── 存量红清欠 + 判据换代史 ──────────────────────────────────────────────
// 2026-10-31：T1/T4 旧锚（#jev-head / #jev-detail-body / #jev-keyref-eye）随
// settings-panel C 批重排换代为层级下钻面。
//
// 🔴 provider-add-tabs 批（2026-11-06 作者令「添加服务商统一成标签切换类型 +
// 单一服务商面板」）再换代：JeV 面并入统一服务商面板的统一表单骨架。JeV 的
// **存量密钥明文回读面**（GET /api/secrets/<keyRef>/value → 只读直显行 +
// #jev-key-eye）随旧表单退役——统一骨架的 key 字段语义 = 掩码输入 + typed-value
// eye + 「已配置——重新输入即覆盖」占位词（LLM 的 provider-keys 明文回读面不受
// 影响，T2/T3/T5 原样保留）。T1 改钉新语义：值不回显、回读零调用、eye 只动
// 表现；T4 的 JeV 半改钉统一表单空态。
//
// 🔴 jev-retire 批（2026-11-16）：T1（jev 统一表单 key 面）与 T4 的 JeV 半随
// JeV 全量退役**整件删除**——jev 域已不在统一服务商面板（llm/imagegen 两域），
// /api/config/jev 投影不复存在。T2/T3/T5（LLM provider-keys 明文回读腿）与
// T4 的 LLM 半（无 key → 空态非掩码）原样保留。

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

/** 静态配置底稿（configData 帧；apiKey 形态字段与引擎 getConfig 打码面同形 = "***"）。 */
function makeCfg({ withKimiKey = true } = {}) {
  /** @type {any} */
  const cfg = {
    llm: {
      providers: {
        kimi: { baseUrl: 'https://api.kimi.example/v1/', apiKey: withKimiKey ? '***' : '', protocol: 'anthropic', models: [{ id: 'kimi-k2', contextWindow: 8192 }] },
        anon: { baseUrl: 'https://anon.example/v1/', apiKey: '***', protocol: 'openai', models: [{ id: 'anon-1', contextWindow: 4096 }] },
      },
    },
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
 *           withKimiKey?: boolean}} opts
 */
async function bootPage(page, opts = {}) {
  const {
    locale = 'zh-CN', revealStatus = 200,
    revealProviders = { kimi: KIMI_KEY },
    withKimiKey = true,
  } = opts;
  await page.emulateMedia({ colorScheme: 'light' });
  await page.addInitScript((l) => {
    localStorage.setItem('nebflow_token', 't');
    localStorage.setItem('neblink_token', 't');
    localStorage.setItem('nebflow_locale', l);
    localStorage.setItem('neblink_locale', l);
    localStorage.setItem('stand_sw', '0');
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
  // 统一服务商面板投影（provider-add-tabs 批：生图读 GET 面；jev 域投影已随
  // JeV 退役摘除——jev-retire 批 2026-11-16）。
  await page.route('**/api/config/provider-types', (r) => r.fulfill({ json: {
    domains: [{ id: 'llm', enabledSwitch: false }, { id: 'imagegen', enabledSwitch: true }],
    adapters: { llm: ['anthropic', 'openai'], imagegen: ['cogview'] },
  } }));
  await page.route('**/api/config/imagegen', (r) => r.fulfill({ json: { compiledIn: true, enabled: true, providers: {} } }));

  await page.routeWebSocket(/\/ws/, (ws) => {
    ws.onMessage((raw) => {
      let m; try { m = JSON.parse(raw); } catch { return; }
      wsFrames.push(m);
      if (m.type === 'getHistory') ws.send(JSON.stringify({ type: 'historyPage', sessionId: m.sessionId, messages: [], hasMore: false, offset: 0 }));
      // The gateway answers a save with the id of that very request; the provider form closes only on it.
      if (m.type === 'updateConfig') ws.send(JSON.stringify({ type: 'configUpdated', success: true, requestId: m.requestId }));
    });
    ws.send(JSON.stringify({
      type: 'configData', configured: true, onboarding: 'done', models: [], defaults: {},
      config: JSON.stringify(makeCfg({ withKimiKey })),
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
  await page.waitForSelector('#settings-content .set-card', { timeout: 10000 });
  // Providers is a progressive-disclosure section: the cards exist only once the user expands it.
  await page.evaluate(() => {
    const toggle = document.querySelector('[data-settings-disclosure="providers"]');
    if (toggle && toggle.getAttribute('aria-expanded') !== 'true') toggle.click();
  });
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

// （T1 已随 JeV 退役删除——jev-retire 批 2026-11-16：jev 统一表单 key 面随
// jev 域下线；LLM 域的 key 回读/保存语义由 T2/T3/T5 守。）

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
// （JeV 半已随 JeV 退役删除——jev-retire 批 2026-11-16。）
test('T4 unconfigured faces show the EMPTY state, not a mask: keyless provider → blank field + placeholder', async ({ page }) => {
  await bootPage(page, { revealProviders: {} });
  await openSettings(page);

  await page.click('.cfg-card[data-provider="anon"]');
  await page.waitForSelector('#cfg-modal [data-field="apiKey"]', { timeout: 5000 });
  const anon = await keyFieldState(page);
  expect(anon.value, '🔴 no stored key → the field is EMPTY (never "***", never dots)').toBe('');
  expect(anon.placeholder, 'the placeholder carries the keep-*** hint, no fake value').toContain('***');
  expect(anon.security, 'empty state is the plaintext face').toBe('none');
  await page.evaluate(() => document.getElementById('cfg-modal')?.remove());
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

#!/usr/bin/env node
// shots.mjs — jev-imagegen-align-llm 批（2026-11-02 作者令）机械验收：
//   三面（Jev / 生图 / 模型切换）× {桌面, 手机} × {深, 浅} 截图 + DOM 断言。
//
// 自包含打桩（同 scripts/shot-bgtask-panel.cjs 路线）：page.route 从磁盘服务真实
// 前端文件（NG_WEB 指向哪棵树就验哪棵树——红向 = main 基线快照，绿向 = 本批树），
// MockWebSocket 走真实 ws.js 分发路径，API 面全部 route 打桩（不碰 6138）。
//
// 用法：
//   NG_WEB=<web 根> NG_OUT=<截图根> NG_LABEL=green|red node shots.mjs
// 退出码：0 = 全部断言通过；1 = 有断言失败（红向预期 1）。

const { chromium } = require('/Users/kaiyu/Claude code/Stand/node_modules/playwright');
const { readFileSync, mkdirSync } = require('node:fs');
const { join, extname, normalize } = require('node:path');

const WEB = process.env.NG_WEB || '/Users/kaiyu/Claude code/Stand/src/main/resources/web';
const OUT = process.env.NG_OUT || join(__dirname, 'shots');
const LABEL = process.env.NG_LABEL || 'green';

const MIME = {
  '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css',
  '.svg': 'image/svg+xml', '.png': 'image/png', '.ico': 'image/x-icon',
  '.json': 'application/json', '.woff2': 'font/woff2', '.woff': 'font/woff', '.ttf': 'font/ttf',
};

const ROOT_SID = 'shot-root';

const CFG = {
  jev: { enabled: true, provider: 'typesafe-jev' },
  imagegen: { enabled: true, defaultProvider: 'openai-img' },
};

// GET /api/config/jev —— ConfigService.jevStatus 形状（keyConfigured 只在位性）
const JEV_STATUS = {
  configured: true, enabled: true,
  provider: 'typesafe-jev', endpoint: 'https://jev.example.com/predict',
  model: 'jev-decision-v1', keyRef: 'typesafe-api-key', keyConfigured: true,
  providers: {
    'typesafe-jev': { type: 'http', endpoint: 'https://jev.example.com/predict', model: 'jev-decision-v1', keyRef: 'typesafe-api-key', keyConfigured: true },
    'laya': { type: 'http', endpoint: 'http://127.0.0.1:8080/predict', model: 'laya-v2', keyRef: 'laya-key', keyConfigured: false },
  },
};

// GET /api/config/jev —— 空态（零预填验收面：未配置任何提供方）
const JEV_EMPTY = {
  configured: false, enabled: false, provider: null, endpoint: null, model: null,
  keyRef: null, keyConfigured: false, providers: {},
};

// GET /api/config/imagegen —— ConfigService.imagegenStatus 形状
const IMAGEGEN_STATUS = {
  compiledIn: true, debugLabel: '', configured: true, enabled: true,
  defaultProvider: 'openai-img',
  providers: {
    'openai-img': { type: 'openai', model: 'gpt-image-1', baseUrl: 'https://api.example.com/v1', keyRef: 'imagegen-openai', keyConfigured: true },
    'local-sd': { type: 'openai', model: 'sdxl', baseUrl: 'http://127.0.0.1:7860/v1', keyRef: 'imagegen-local', keyConfigured: false },
  },
};

(async () => {
  mkdirSync(join(OUT, LABEL), { recursive: true });
  const browser = await chromium.launch();
  const results = [];
  const ok = (name, cond) => { results.push([`${LABEL}:${name}`, !!cond]); console.log(`${cond ? 'PASS' : 'FAIL'}: ${name}`); };

  /** 建页 + 打桩 + 引导（空态/已配置两种读数由 jevStatus 决定）。 */
  async function boot({ scheme, viewport, mobile, jevStatus }) {
    const page = await browser.newPage({ viewport, colorScheme: scheme, isMobile: !!mobile, hasTouch: !!mobile, deviceScaleFactor: mobile ? 2 : 1 });
    const errors = [];
    const puts = [];
    page.on('pageerror', (e) => errors.push(String(e)));
    page.on('console', (m) => { if (m.type() === 'error') errors.push(m.text()); });
    await page.addInitScript(() => {
      localStorage.setItem('nebflow_token', 'shot-token');
      localStorage.setItem('nebflow_locale', 'zh-CN');
      window.__wsSent = [];
      class MockWS {
        static CONNECTING = 0; static OPEN = 1; static CLOSING = 2; static CLOSED = 3;
        constructor(u) { this.url = u; this.readyState = MockWS.CONNECTING; window.__wsMock = this; }
        send(d) { try { window.__wsSent.push(JSON.parse(d)); } catch { window.__wsSent.push(d); } }
        close() {} onopen = null; onmessage = null; onclose = null;
      }
      Object.defineProperty(window, 'WebSocket', { value: MockWS });
    });

    await page.route('**/*', (route) => {
      const req = route.request();
      const url = new URL(req.url());
      let p = decodeURIComponent(url.pathname);
      if (p === '/') p = '/index.html';
      if (p.startsWith('/api/')) {
        if (req.method() !== 'GET') puts.push({ path: p, body: req.postData() });
        if (p === '/api/config/jev' && req.method() === 'GET') return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(jevStatus) });
        if (p === '/api/config/imagegen' && req.method() === 'GET') return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(IMAGEGEN_STATUS) });
        if (p === '/api/config' && req.method() === 'GET') return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(CFG) });
        if (p === '/api/secrets' && req.method() === 'GET') return route.fulfill({ status: 200, contentType: 'application/json', body: '[]' });
        return route.fulfill({ status: 200, contentType: 'application/json', body: '{"ok":true,"updated":true}' });
      }
      const file = normalize(join(WEB, p));
      if (!file.startsWith(WEB)) return route.fulfill({ status: 403, body: 'forbidden' });
      try {
        return route.fulfill({ status: 200, contentType: MIME[extname(file)] || 'application/octet-stream', body: readFileSync(file) });
      } catch {
        return route.fulfill({ status: 404, body: 'not found' });
      }
    });

    await page.goto('http://localhost:1/', { waitUntil: 'domcontentloaded' });
    await page.waitForFunction(() => window.__wsMock, null, { timeout: 15000 });
    await page.evaluate(() => { window.__wsMock.readyState = 1; window.__wsMock.onopen && window.__wsMock.onopen(); });
    await page.waitForTimeout(300);
    const df = (f) => page.evaluate((fr) => window.__wsMock.onmessage({ data: JSON.stringify(fr) }), f);
    await df({ type: 'sessionList', sessionId: ROOT_SID, activeId: ROOT_SID, sessions: [{ id: ROOT_SID, name: 'Nebula', agentName: 'Nebula' }], folders: [] });
    await df({ type: 'configData', config: JSON.stringify(CFG), configured: true, onboarding: 'done' });
    await df({ type: 'historyPage', sessionId: ROOT_SID, messages: [], hasMore: false, offset: 0 });
    await page.waitForTimeout(500);
    return { page, errors, puts, df };
  }

  async function shot(page, sel, name) {
    const file = join(OUT, LABEL, `${name}.png`);
    try {
      const loc = sel ? page.locator(sel).first() : null;
      if (loc) await loc.screenshot({ path: file });
      else await page.screenshot({ path: file });
    } catch {
      await page.screenshot({ path: file });
    }
    console.log(`saved ${file}`);
  }

  async function openSettings(page) {
    await page.evaluate(() => {
      document.querySelectorAll('.cfg-modal-overlay').forEach((o) => { if (o.id !== 'settings-overlay') o.remove(); });
    });
    const already = await page.evaluate(() => !!document.getElementById('settings-overlay')?.classList.contains('on'));
    if (already) return;
    // 程序化点（按钮在活动栏收缩态下可能不可见——取景与断言都不吃可见性判定）
    await page.evaluate(() => document.getElementById('settings-btn')?.click());
    await page.waitForSelector('#settings-overlay.on', { timeout: 8000 }).catch(() => {});
    await page.waitForTimeout(400);
  }

  async function openDrill(page, toggle, wrapSel, cardSel) {
    await page.evaluate((sel) => document.querySelector(sel)?.click(), toggle);
    try {
      await page.waitForSelector(`${wrapSel} ${cardSel}`, { timeout: 6000 });
    } catch {
      // 取景兜底（capture-only）：下钻面板未起时强开节点，断言仍按真实 DOM 判。
      await page.evaluate((sel) => {
        const el = document.querySelector(sel);
        if (el) { el.hidden = false; el.style.display = 'block'; }
      }, wrapSel);
      await page.waitForTimeout(300);
    }
    await page.waitForTimeout(350);
  }

  const DESKTOP = { viewport: { width: 1440, height: 900 }, mobile: false };
  const PHONE = { viewport: { width: 390, height: 844 }, mobile: true };

  // ── 主矩阵：三面 × {桌面,手机} × {深,浅} ──────────────────────────────
  for (const scheme of ['dark', 'light']) {
    for (const [tag, dev] of [['desktop', DESKTOP], ['phone', PHONE]]) {
      const { page, errors, puts } = await boot({ scheme, viewport: dev.viewport, mobile: dev.mobile, jevStatus: JEV_STATUS });

      // —— 面 1：Jev 设置节（已配置 = 卡片列表 + 表单）——
      await openSettings(page);
      await openDrill(page, '#jev-advance-toggle', '#jev-section-wrap', '.cfg-card');
      const jev = await page.evaluate(() => {
        const wrap = document.querySelector('#jev-section-wrap');
        const scope = wrap || document;
        const txt = (scope.innerText || '');
        const typeSel = document.querySelector('#cfg-jev-type');
        return {
          wrap: !!wrap,
          cards: scope.querySelectorAll('.cfg-card[data-face="jev"]').length,
          oldProviderSelect: !!document.querySelector('#cfg-jev-provider'),
          oldKeyRefInput: !!document.querySelector('#cfg-jev-keyref'),
          oldKeyValueRow: !!document.querySelector('#cfg-jev-key-value'),
          keyRefWord: /密钥引用|API 密钥引用/.test(txt),
          typePlaceholder: !!typeSel && typeSel.value === '',
          endpointEditable: (() => { const e = document.querySelector('#cfg-jev-endpoint'); return !!e && !e.disabled; })(),
          addBtn: !!document.querySelector('#btn-add-jev-provider'),
        };
      });
      ok(`${tag}/${scheme}: Jev 面 = 卡片列表（.cfg-card[data-face=jev] ≥ 2）`, jev.wrap && jev.cards >= 2);
      ok(`${tag}/${scheme}: Jev 旧预填下拉 #cfg-jev-provider 已退役`, !jev.oldProviderSelect);
      ok(`${tag}/${scheme}: Jev keyRef 输入行已退役（#cfg-jev-keyref 不存在）`, !jev.oldKeyRefInput);
      ok(`${tag}/${scheme}: Jev 明文回读行已退役（#cfg-jev-key-value 不存在）`, !jev.oldKeyValueRow);
      ok(`${tag}/${scheme}: Jev 面零「密钥引用」字眼`, !jev.keyRefWord);
      ok(`${tag}/${scheme}: Jev 端点可编辑（不再钉死预填）`, jev.endpointEditable);
      ok(`${tag}/${scheme}: Jev 新增提供方入口在位`, jev.addBtn);
      await shot(page, '#jev-section-wrap', `jev__${tag}__${scheme}`);

      // —— 面 2：生图设置节 ——
      await page.keyboard.press('Escape').catch(() => {});
      await page.waitForTimeout(300);
      await openSettings(page).catch(() => {});
      await openDrill(page, '#imagegen-advance-toggle', '#imagegen-section-wrap', '.cfg-card');
      const img = await page.evaluate(() => {
        const wrap = document.querySelector('#imagegen-section-wrap');
        const scope = wrap || document;
        return {
          wrap: !!wrap,
          cards: scope.querySelectorAll('.cfg-card[data-face="imagegen"]').length,
          sentinel: !!document.querySelector('#cfg-imagegen-newprovider'),
          addRow: !!scope.querySelector('[data-cfg-card-add], .cfg-card-add, #btn-add-imagegen-provider'),
          keyRefWord: /密钥引用|API 密钥引用/.test(scope.innerText || ''),
        };
      });
      ok(`${tag}/${scheme}: 生图面 = 卡片列表（.cfg-card[data-face=imagegen] ≥ 2）`, img.wrap && img.cards >= 2);
      ok(`${tag}/${scheme}: 生图 __add__ 哨兵下拉已退役`, !img.sentinel);
      ok(`${tag}/${scheme}: 生图面零「密钥引用」字眼`, !img.keyRefWord);
      await shot(page, '#imagegen-section-wrap', `imagegen__${tag}__${scheme}`);

      // —— 面 3：/model 面板的两面切换分组 ——
      await page.keyboard.press('Escape').catch(() => {});
      await page.evaluate(() => { const o = document.querySelector('#settings-overlay'); if (o) o.classList.remove('on'); }).catch(() => {});
      await page.waitForTimeout(250);
      await page.click('#input').catch(() => {});
      await page.fill('#input', '/model').catch(() => {});
      await page.keyboard.press('Enter');
      await page.waitForSelector('#model-panel-overlay .mp-group[data-group="jev"]', { timeout: 8000 }).catch(() => {});
      await page.waitForTimeout(500);
      const mod = await page.evaluate(() => {
        const ov = document.querySelector('#model-panel-overlay');
        const jevG = ov?.querySelector('.mp-group[data-group="jev"]');
        const imgG = ov?.querySelector('.mp-group[data-group="imagegen"]');
        const sel = jevG?.querySelector('select.mp-group-switch');
        return {
          panel: !!ov,
          groups: ov?.querySelectorAll('.mp-group').length || 0,
          jev: !!jevG,
          imagegen: !!imgG,
          jevOptions: sel ? [...sel.options].map((o) => o.value).filter(Boolean) : [],
          jevCurrent: jevG?.querySelector('.mp-group-cur')?.textContent || '',
        };
      });
      ok(`${tag}/${scheme}: /model 面板可打开`, mod.panel);
      ok(`${tag}/${scheme}: /model 面板渲染 Jev 分组（.mp-group[data-group=jev]）`, mod.jev);
      ok(`${tag}/${scheme}: /model 面板渲染生图分组（.mp-group[data-group=imagegen]）`, mod.imagegen);
      ok(`${tag}/${scheme}: Jev 分组切换器列出已配置提供方`, mod.jevOptions.includes('typesafe-jev') && mod.jevOptions.includes('laya'));
      await shot(page, '#model-panel-overlay', `model__${tag}__${scheme}`);

      // 切换写通道（只在 dark/desktop 跑一次，避免重复写）
      if (scheme === 'dark' && tag === 'desktop') {
        const before = puts.length;
        await page.evaluate(() => {
          const sel = document.querySelector('#model-panel-overlay .mp-group[data-group="jev"] select.mp-group-switch');
          if (!sel) return;
          sel.value = 'laya';
          sel.dispatchEvent(new Event('change', { bubbles: true }));
        });
        await page.waitForTimeout(900);
        const put = puts.slice(before).find((p) => p.path === '/api/config/jev');
        console.log('[probe] puts after switch =', JSON.stringify(puts.slice(before)));
        ok('切换 Jev 提供方 ⇒ PUT /api/config/jev（含 provider=laya）', !!put && String(put.body || '').includes('laya'));
        // 设置页模型节（.mp-group-list）同源渲染
        await page.keyboard.press('Escape').catch(() => {});
        await page.waitForTimeout(300);
        await openSettings(page).catch(() => {});
        const st = await page.evaluate(() => {
          const list = document.querySelector('#model-chain-section-body .mp-group-list') || document.querySelector('.mp-group-list');
          return { list: !!list, groups: list ? list.querySelectorAll('.mp-group').length : 0, details: list ? list.querySelectorAll('details.mp-group').length : 0 };
        });
        ok('设置页模型节渲染同款分组（.mp-group-list 内 .mp-group ≥ 2）', st.list && st.groups >= 2);
        ok('设置页分组为折叠式（details.mp-group）', st.details >= 2);
        await shot(page, '#settings-modal', 'settings-model__desktop__dark');
        await page.keyboard.press('Escape').catch(() => {});
      }

      if (errors.length) console.log(`console errors (${tag}/${scheme}):`, errors.slice(0, 4));
      await page.close();
    }
  }

  // ── 空态验收（未配置任何提供方 ⇒ 零预填）──────────────────────────────
  {
    const { page } = await boot({ scheme: 'dark', viewport: DESKTOP.viewport, mobile: false, jevStatus: JEV_EMPTY });
    await openSettings(page);
    await page.click('#jev-advance-toggle');
    await page.waitForSelector('#cfg-jev-name', { timeout: 6000 }).catch(() => {});
    await page.waitForTimeout(600);
    const empty = await page.evaluate(() => {
      const val = (id) => { const e = document.querySelector(id); return e ? e.value : undefined; };
      const wrap = document.querySelector('#jev-section-wrap');
      const typeSel = document.querySelector('#cfg-jev-type');
      const jevBody = document.querySelector('#jev-advance-body');
      return {
        cards: wrap ? wrap.querySelectorAll('.cfg-card[data-face="jev"]').length : -1,
        name: val('#cfg-jev-name'),
        endpoint: val('#cfg-jev-endpoint'),
        model: val('#cfg-jev-model'),
        key: val('#cfg-jev-key'),
        type: typeSel ? typeSel.value : undefined,
        keyRefInput: !!document.querySelector('#cfg-jev-keyref'),
        visible: !!(jevBody && !jevBody.hidden),
      };
    });
    ok('空态: 零卡片（未配置不造假）', empty.cards === 0);
    ok('空态: provider name 空（不预填）', empty.name === '');
    ok('空态: 端点空（不预填 pinned endpoint）', empty.endpoint === '');
    ok('空态: 模型空', empty.model === '');
    ok('空态: 密钥框空', empty.key === '');
    ok('空态: 协议下拉 = 占位项（不预选适配器）', empty.type === '');
    ok('空态: keyRef 行零痕迹', !empty.keyRefInput);
    await shot(page, '#jev-section-wrap', 'jev-empty__desktop__dark');
    await page.close();
  }

  await browser.close();
  const failed = results.filter(([, c]) => !c);
  console.log(`\n[${LABEL}] ${results.length - failed.length}/${results.length} assertions passed`);
  if (failed.length) { console.log('FAILED:'); for (const [n] of failed) console.log(' -', n); }
  process.exit(failed.length ? 1 : 0);
})().catch((e) => { console.error(e); process.exit(2); });

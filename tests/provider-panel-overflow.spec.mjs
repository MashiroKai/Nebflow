// provider-panel-overflow.spec.mjs — 服务商面板「文字溢出零容忍」真渲染断言
// （provider-add-tabs 批，2026-11-06 作者令逐字：「现在会出现文字溢出、导致面板在
// 手机端可以左右滑动的问题。这里一定要加断言，保证所有文字都不能溢出」）。
//
// 判据（三条，全部真渲染读数，禁静态替身）：
//   O1 面板不许横向可滚：`#settings-content`（以及面板本体 .nf-provider-panel /
//      #provider-list / 表单弹窗 .cfg-modal / .cfg-modal-body）的
//      `scrollWidth <= clientWidth`。出现 1px 以上超出即红。
//   O2 每一个文本节点不许溢出：遍历容器内所有元素，取「直接持有文本子节点」的元素
//      （含 ::before/::after 不受测——它们不参与可滚面），断言
//      `scrollWidth <= clientWidth + 1`。读数含元素标识（tag.class#id + 文本前 40 字）
//      与超出像素数，红时报出逐元素清单。
//   O3 禁「靠裁剪遮掉的假绿」：O2 的 scrollWidth 是**内容自然宽**，元素自身
//      overflow:hidden/-x 不会把它压小（CSSOM：scrollWidth 不受自身裁剪影响）——
//      所以「overflow-x:hidden 遮住」的写法在 O1/O2 下依然红。另加一条独立判据：
//      被点名的溢出元素若其自身 computed `overflow-x` 为 hidden/auto/scroll 且
//      **祖先链**上有横向可滚面，同样报出（避免「外层滚动条 + 内层裁剪」组合逃检）。
//
// 覆盖矩阵（与批要求一一对应）：
//   · 宽度 320（极窄）/ 375（作者报障机宽）/ 1440（桌面）；
//   · 主题 亮 / 暗；
//   · 三态：设置页（服务商面板）、新增表单（LLM 弹窗 = 表单骨架）、类型标签切到
//     Image generation / Jev 各一次（字段套不同的排版都要过）。
//
// 改前/改后对照：`NB_WEB_ROOT=<另一棵树>`（例：git archive 出来的 main 树）——
// 同一份判据跑旧树 = 改前读数（红），跑本树 = 改后读数（绿）。零真实引擎。
//
// Run: npx playwright test tests/provider-panel-overflow.spec.mjs
//      NB_WEB_ROOT=/tmp/old-web npx playwright test tests/provider-panel-overflow.spec.mjs

import { test, expect } from '@playwright/test';
import { createServer } from 'node:http';
import { readFile } from 'node:fs/promises';
import { join, dirname, extname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const REPO_ROOT = resolve(HERE, '..');
/** 被测 web 树（默认本仓；NB_WEB_ROOT 指向对照树 = 改前基线）。 */
const WEB = process.env.NB_WEB_ROOT ? resolve(process.env.NB_WEB_ROOT) : resolve(REPO_ROOT, 'src', 'main', 'resources', 'web');
const PORT = 8271; // 固定自持静态服（8100+；永不碰宿主 8080/6138）
const BASE = `http://127.0.0.1:${PORT}`;

/** 自持静态服务器（对照树同样适用：NB_WEB_ROOT 换根即为「改前」读数）。 */
function startServer() {
  return new Promise((done) => {
    const server = createServer(async (req, res) => {
      try {
        let p = decodeURIComponent(new URL(req.url, 'http://x').pathname);
        if (p === '/') p = '/index.html';
        if (p === '/favicon.ico') { res.writeHead(204); res.end(); return; }
        const file = resolve(join(WEB, p));
        if (!file.startsWith(WEB)) { res.writeHead(403); res.end(); return; }
        const data = await readFile(file);
        res.writeHead(200, { 'content-type': MIME[extname(file)] || 'application/octet-stream', 'cache-control': 'no-store' });
        res.end(data);
      } catch { res.writeHead(404); res.end('not found'); }
    });
    server.listen(PORT, '127.0.0.1', () => done(server));
  });
}
let server;
test.beforeAll(async () => { server = await startServer(); });
test.afterAll(async () => { if (server) server.close(); });
const MIME = {
  '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.svg': 'image/svg+xml',
  '.png': 'image/png', '.ico': 'image/x-icon', '.json': 'application/json',
  '.woff2': 'font/woff2', '.woff': 'font/woff', '.ttf': 'font/ttf',
};

const SID = 'prov-overflow-root';
const NOW = Date.now();

/** 宽度 × 主题矩阵（320/375/1440 × 亮/暗）。 */
const MATRIX = [
  { width: 320, height: 720, theme: 'light' },
  { width: 320, height: 720, theme: 'dark' },
  { width: 375, height: 812, theme: 'light' },
  { width: 375, height: 812, theme: 'dark' },
  { width: 1440, height: 900, theme: 'light' },
  { width: 1440, height: 900, theme: 'dark' },
];

/** 服务商夹具：三类各两条（长名 + 长模型 id，专打溢出）。 */
const CONFIG = {
  llm: {
    providers: {
      'anthropic-official-long-name-provider': {
        baseUrl: 'https://api.anthropic.example/v1/',
        apiKey: '***',
        protocol: 'anthropic',
        models: [{ id: 'claude-3-7-sonnet-20250219-extended-thinking-preview', contextWindow: 200000 }],
        unavailableWindows: [{ from: '23:00', to: '07:00', days: [6, 7] }],
      },
      kimi: {
        baseUrl: 'https://api.moonshot.example/v1/',
        apiKey: '***',
        protocol: 'openai',
        models: [{ id: 'kimi-k2', contextWindow: 128000 }],
      },
    },
  },
};

const IMAGEGEN = {
  compiledIn: true, enabled: true, defaultProvider: 'zhipu-cogview-production',
  providers: {
    'zhipu-cogview-production': { type: 'cogview', model: 'cogview-4-250304-flash-preview-long', keyConfigured: true },
    ark: { type: 'openai-images', model: 'doubao-seedream-3-0-t2i-250415', keyConfigured: false },
  },
};

// （jev-retire 批 2026-11-16：JEV 夹具与 jev 域投影随 JeV 退役摘除——面板只剩
// llm/imagegen 两域。）
const TYPES = {
  domains: [
    { id: 'llm', enabledSwitch: false },
    { id: 'imagegen', enabledSwitch: true },
  ],
  adapters: { llm: ['anthropic', 'openai'], imagegen: ['cogview', 'openai-images', 'imagen'] },
};

async function bootApp(page, theme) {
  await page.emulateMedia({ colorScheme: theme });
  await page.addInitScript(() => {
    localStorage.setItem('nebflow_token', 'e2e-token');
    localStorage.setItem('nebflow_locale', 'en');
    Object.defineProperty(navigator.serviceWorker, 'register', { value: () => Promise.resolve() });
  });
  await page.route('**/*', (route) => {
    const url = new URL(route.request().url());
    let p = decodeURIComponent(url.pathname);
    if (p === '/') p = '/index.html';
    if (url.pathname === '/favicon.ico') return route.fulfill({ status: 200, contentType: 'image/x-icon', body: '' });
    if (p === '/agents/Nebula/agent.json') return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
    if (p.startsWith('/api/')) {
      if (p === '/api/config/provider-types') return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(TYPES) });
      if (p === '/api/config/imagegen') return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(IMAGEGEN) });
      if (p === '/api/config/provider-keys') return route.fulfill({ status: 200, contentType: 'application/json', body: '{"providers":{}}' });
      if (p === '/api/secrets') return route.fulfill({ status: 200, contentType: 'application/json', body: '{"secrets":[]}' });
      if (p === '/api/decision/health') return route.fulfill({ status: 200, contentType: 'application/json', body: '{"decision":{"status":"up","reason":""}}' });
      if (p === '/api/sessions') {
        return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ sessions: [{ id: SID, name: 'root', agentName: 'Nebula' }], activeId: SID }) });
      }
      if (/\/api\/agents\/[^/]+\/model$/.test(p)) {
        return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ mode: 'follow', chain: null, effectiveChain: [] }) });
      }
      return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
    }
    return route.continue(); // 静态资源交给自持服务器
  });
  await page.routeWebSocket(/\/ws/, (ws) => {
    const cfg = JSON.stringify(CONFIG);
    const sendConfig = () => ws.send(JSON.stringify({ type: 'configData', config: cfg, configured: true, onboarding: 'done' }));
    const sendSessions = () => ws.send(JSON.stringify({ type: 'sessionList', sessions: [{ id: SID, name: 'root', agentName: 'Nebula' }], folders: [], activeId: SID }));
    sendConfig();
    sendSessions();
    ws.onMessage((raw) => {
      let m;
      try { m = JSON.parse(raw); } catch { return; }
      if (m.type === 'getConfig') sendConfig();
      else if (m.type === 'getSessions' || m.type === 'listSessions') sendSessions();
      else if (m.type === 'getHistory') ws.send(JSON.stringify({ type: 'historyPage', sessionId: m.sessionId, messages: [], hasMore: false, offset: 0, total: 0 }));
    });
  });
  await page.goto(`${BASE}/index.html`);
  await page.waitForSelector('#activity-bar', { state: 'attached', timeout: 20000 });
  await page.waitForTimeout(300);
}

/**
 * 测量一个容器：容器自身横向可滚 + 每个持文本元素的内容宽超框。
 * @returns {Promise<{scrollers: Array<{sel:string, over:number}>, texts: Array<{sel:string, over:number, text:string}>}>}
 */
async function measure(page, selector) {
  return page.evaluate((sel) => {
    const nameOf = (el) => {
      const id = el.id ? `#${el.id}` : '';
      const cls = typeof el.className === 'string' && el.className.trim()
        ? '.' + el.className.trim().split(/\s+/).slice(0, 3).join('.')
        : '';
      return `${el.tagName.toLowerCase()}${id}${cls}`;
    };
    const textOf = (el) => (el.textContent || '').replace(/\s+/g, ' ').trim().slice(0, 40);
    const scrollers = [];
    const texts = [];
    const roots = [...document.querySelectorAll(sel)].filter((el) => el.getClientRects().length > 0);
    // 容器自身 + 其祖先里带横向可滚的（#settings-content / .cfg-modal-body 等）
    for (const root of roots) {
      for (const node of [root, ...root.querySelectorAll('*')]) {
        if (!(node instanceof HTMLElement)) continue;
        if (node.getClientRects().length === 0) continue;
        const cs = getComputedStyle(node);
        if (cs.display === 'none' || cs.visibility === 'hidden') continue;
        const over = node.scrollWidth - node.clientWidth;
        if (over <= 1 || node.clientWidth <= 0) continue;
        const scrollX = cs.overflowX;
        // 合规省略号豁免（批规范「尾部省略号 + title 全文」是合法处置）：
        // white-space:nowrap + overflow-x:hidden/clip + text-overflow:ellipsis +
        // title 非空，四件齐 = 真省略号，O1/O2 全豁免；缺 title 或裸裁剪
        // （hidden 无 ellipsis）一律照红——那就是「靠遮掩」的假绿。
        const legitEllipsis = cs.whiteSpace === 'nowrap'
          && (scrollX === 'hidden' || scrollX === 'clip')
          && cs.textOverflow === 'ellipsis'
          && !!(node.title || node.getAttribute('title'));
        if (legitEllipsis) continue;
        // O1a：真横向滚（auto/scroll/overlay 且内容更宽）⇒ 面板能左右滑。
        if (scrollX === 'auto' || scrollX === 'scroll' || scrollX === 'overlay') {
          scrollers.push({ sel: nameOf(node), over });
          continue;
        }
        // O1b：裁剪遮内容（hidden/clip 且内容更宽）⇒ 批规范禁止的假绿形态。
        if (scrollX === 'hidden' || scrollX === 'clip') {
          scrollers.push({ sel: `${nameOf(node)}(裁剪)`, over });
          continue;
        }
        // O2：overflow 可见却装不下 ⇒ 文本真的溢出（换行失效/容器顶宽）。
        const hasOwnText = [...node.childNodes].some((n) => n.nodeType === 3 && (n.textContent || '').trim().length > 0);
        if (hasOwnText) texts.push({ sel: nameOf(node), over, text: textOf(node) });
        // 溢出但自身无文本节点（nb-toggle 的 44×44 透明命中盒 ::before、按钮旋钮
        // 等装饰/命中几何）≠ 文字溢出、也不构成面板滑动：溢出若真逃逸出面板，
        // 被扫的祖先根（#settings-content / .cfg-modal-body）自身 scrollWidth
        // 会长大并被本循环抓到——此处计数只会误伤合法命中盒档（设计规范 §44px）。
      }
    }
    return { scrollers, texts };
  }, selector);
}

function fmt(list, label) {
  if (!list.length) return '';
  return `\n${label}（${list.length}）：\n` + list.map((r) => `  · ${r.sel}  +${r.over}px${r.text ? `  「${r.text}」` : ''}`).join('\n');
}

for (const cell of MATRIX) {
  const tag = `${cell.width}/${cell.theme}`;
  test(`O. 服务商面板文字溢出零容忍 @ ${tag}`, async ({ page }) => {
    await page.setViewportSize({ width: cell.width, height: cell.height });
    await bootApp(page, cell.theme);
    // 设置面板入口：直接调 openSettingsPanel()（活动栏钮在窄视口被抽屉化、
    // 不可见——点它只会超时；本判据测的是面板版式，不是入口动效）。
    await page.evaluate(async () => {
      (await import('/js/sidebar.js')).openSettingsPanel();
      document.getElementById('settings-overlay')?.classList.add('on');
    });
    // settings-ios-rows 批（main 平行批）后设置节 = .set-card；服务商面板首帧
    // 由 bindProviderPanel 同步画——等卡 + 首帧标签都在场再开测。
    await page.waitForSelector('#settings-content .set-card', { timeout: 15000 });
    // settings-progressive-disclosure 批：服务商面板在**渐进披露体**里，默认折叠
    // ⇒ 不展开则 .nf-ptab 永不在场（本判据此前一直红在等它）。展开后再等面板。
    // 点击走 evaluate（窄视口下设置浮层不是「可见可点」态，真实 click 会被 #chat
    // 拦截——与本文件切换类型标签的既有口径一致：测版式，不测入口动效）。
    await page.evaluate(() => document.querySelector('[data-settings-disclosure="providers"]')?.click());
    await page.waitForSelector('#provider-panel-host .nf-ptab', { timeout: 15000 });
    await page.waitForTimeout(400);

    // 防空转：两张类型标签必须在场（ProviderDomains.All 投影），否则本判据
    // 的标签轮换腿会静默跳过 = 假绿。
    const tabCount = await page.evaluate(() => document.querySelectorAll('.nf-ptab').length);
    expect(tabCount, '类型标签数（llm/imagegen 两域投影）').toBe(2);

    // ── 态①：设置页里的服务商面板（ALL 行都在 = 一项不筛）───────────────
    const panel = await measure(page, '#settings-content');
    const panelMsg = fmt(panel.scrollers, '横向可滚元素') + fmt(panel.texts, '文本溢出元素');

    // ── 态②③：类型标签逐一切换（字段套/徽标词面都要过）─────────────────
    // 类型标签逐一切换（字段套/徽标词面都要过）。点击走 evaluate（窄视口下
    // 手机壳层 #chat 会拦 pointer——层叠与版式溢出无关）。
    const tabIds = await page.evaluate(() =>
      [...document.querySelectorAll('.nf-ptab')].map((b) => b.dataset.providerType || ''));
    for (const id of tabIds) {
      await page.evaluate((tid) => {
        document.querySelector(`.nf-ptab[data-provider-type="${tid}"]`)?.click();
      }, id);
      await page.waitForTimeout(120);
      const m = await measure(page, '#settings-content');
      panel.scrollers.push(...m.scrollers);
      panel.texts.push(...m.texts);
    }
    // ── 态④：新增表单（表单骨架 = 弹窗；长文本字段全在场）───────────────
    // 新增表单开在首个类型（LLM）。
    if (tabIds.length) {
      await page.evaluate(() => document.querySelector('.nf-ptab')?.click());
      await page.waitForTimeout(100);
    }
    // 面板内按钮用 evaluate 点（层叠层的 pointer 判定与版式溢出无关；目标
    // 可见性已由前面 measure 的 getClientRects 保证）。
    await page.evaluate(() => document.getElementById('btn-add-provider')?.click());
    await page.waitForSelector('#cfg-modal .cfg-modal-body', { timeout: 8000 });
    await page.waitForTimeout(150);
    const modal = await measure(page, '#cfg-modal');
    const modalMsg = fmt(modal.scrollers, '弹窗横向可滚元素') + fmt(modal.texts, '弹窗文本溢出元素');

    await page.keyboard.press('Escape');
    await page.waitForTimeout(100);



    const report = `[${tag}] 面板：${panelMsg || ' 无'}\n[${tag}] 表单：${modalMsg || ' 无'}`;
    console.log(report);
    // 断言：O1（不许横向可滚）与 O2（每个文本节点不溢出）双零。
    expect(panel.scrollers.length, `面板横向可滚：${report}`).toBe(0);
    expect(modal.scrollers.length, `弹窗横向可滚：${report}`).toBe(0);
    expect(panel.texts.length, `面板文本溢出：${report}`).toBe(0);
    expect(modal.texts.length, `弹窗文本溢出：${report}`).toBe(0);
  });
}

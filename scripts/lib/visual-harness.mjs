// visual-harness.mjs — 视觉基线采集的共享装置（零端口、零进程、零新依赖）。
//
// 路线不是新发明的：本仓 30 个截图脚本各自复制过同一份配方，这里把它收成一份，
// 样板取自 scripts/shot-header-name.cjs:102-146（自包含打桩）与
// scripts/verify-preset-empty-state.cjs:115-172（配置帧 + 真实入口点击）：
//
//   1. page.route('**/*') 从磁盘服务 src/main/resources/web —— 不起服务、不开端口
//      （因此天然不碰宿主实例 6138，也不需要 --port/--home）
//   2. addInitScript 注入 MockWS：window.__wsMock 承接帧，走真实 ws.js 分发路径
//      （不直写 DOM —— 否则基线测的是打桩而不是产品）
//   3. 帧序列 sessionList → configData → historyPage 是产品初始化契约
//   4. 拍摄前 settle：document.fonts.ready + 双 rAF + animations:'disabled'
//
// 为什么坚持零端口：视觉基线要的是「同一输入 → 同一像素」。真实后端实例带着
// 会话状态、时钟、随机 id，是像素稳定性的最大污染源；打桩路线把它整层拿掉。

import { createRequire } from 'node:module';
import { readFileSync } from 'node:fs';
import { extname, join, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';

const require = createRequire(import.meta.url);

export const ROOT = fileURLToPath(new URL('../..', import.meta.url));
export const WEB = join(ROOT, 'src', 'main', 'resources', 'web');

const MIME = {
  '.html': 'text/html', '.js': 'text/javascript', '.mjs': 'text/javascript', '.css': 'text/css',
  '.svg': 'image/svg+xml', '.png': 'image/png', '.jpg': 'image/jpeg', '.gif': 'image/gif',
  '.ico': 'image/x-icon', '.json': 'application/json', '.webmanifest': 'application/manifest+json',
  '.woff': 'font/woff', '.woff2': 'font/woff2', '.ttf': 'font/ttf', '.otf': 'font/otf',
  '.map': 'application/json', '.wasm': 'application/wasm', '.mp4': 'video/mp4', '.pdf': 'application/pdf',
};

/**
 * 解析 playwright。解析顺序（本地与 CI 共用同一脚本）：
 *   1. NEBFLOW_PW_PATH        显式路径（CI/非常规安装）
 *   2. 'playwright'           常规安装（CI：npm install --no-save @playwright/test 附带）
 *   3. 'playwright-core'      只装核心包的环境（不带浏览器，需配 executablePath）
 * 全失败则抛错并给出可执行修复建议 —— 绝不静默降级到「无浏览器跑空」。
 */
export function loadPlaywright() {
  const tried = [];
  const cands = [];
  if (process.env.NEBFLOW_PW_PATH) cands.push(process.env.NEBFLOW_PW_PATH);
  cands.push('playwright', 'playwright-core');
  for (const c of cands) {
    try {
      return { mod: require(c), from: c };
    } catch (e) {
      tried.push(`${c} (${String(e.message).split('\n')[0]})`);
    }
  }
  throw new Error(
    'visual-baseline: playwright not resolvable.\n  tried:\n    ' + tried.join('\n    ') + '\n' +
    '  fix: npm install (devDeps) 或 NEBFLOW_PW_PATH=<path to playwright|playwright-core>\n' +
    '  本仓不把 playwright 列入 devDependencies（见 package.json），由运行环境自带。'
  );
}

const KNOWN_CHROME = [
  process.env.NEBFLOW_CHROME_PATH,
  'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe',
  'C:/Program Files/Microsoft/Edge/Application/msedge.exe',
  'C:/Program Files/Google/Chrome/Application/chrome.exe',
  'C:/Program Files (x86)/Google/Chrome/Application/chrome.exe',
  '/usr/bin/google-chrome',
  '/usr/bin/chromium',
  '/usr/bin/chromium-browser',
  '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome',
  '/Applications/Microsoft Edge.app/Contents/MacOS/Microsoft Edge',
].filter(Boolean);

/**
 * 起浏览器。返回 { browser, label }，label 进基准图目录名（跨机不可比的根因就是它）。
 * 顺序：显式 executablePath → playwright 自带 chromium → 系统 Edge/Chrome。
 */
export async function launchBrowser(pw, opts = {}) {
  const chromium = (pw.playwright || pw).chromium;
  const attempts = [];
  if (opts.executablePath) attempts.push({ tag: 'explicit', arg: { executablePath: opts.executablePath } });
  attempts.push({ tag: 'bundled', arg: {} });
  for (const p of KNOWN_CHROME) attempts.push({ tag: p.split(/[\\/]/).pop(), arg: { executablePath: p } });

  const errors = [];
  for (const a of attempts) {
    try {
      const browser = await chromium.launch({ headless: true, ...a.arg });
      const v = browser.version();
      const tag = a.tag === 'bundled' ? 'chromium' : a.tag.replace(/\.exe$/i, '');
      return { browser, label: `${tag}-${v.split('.')[0]}`, version: v, source: a.tag };
    } catch (e) {
      errors.push(`${a.tag}: ${String(e.message).split('\n')[0]}`);
    }
  }
  throw new Error('visual-baseline: no usable browser.\n  ' + errors.join('\n  '));
}

/**
 * 建页 + 装打桩。返回 { page, frame, pushConfig, boot, close }。
 * @param {object} opts { width, height, colorScheme, locale, apiStubs, time }
 */
export async function createSession(browser, opts = {}) {
  const width = opts.width ?? 1440;
  const height = opts.height ?? 900;
  const colorScheme = opts.colorScheme ?? 'dark';
  // deviceScaleFactor 恒为 1：跨机比对的唯一机会就在「同样的 CSS 像素」，
  // DPR 变化会让所有取整/描边落到不同物理像素上（全仓只有
  // scripts/verify-unified-panel.cjs:136 用过 DSF=2，那是人眼评审用途）。
  const page = await browser.newPage({
    viewport: { width, height },
    deviceScaleFactor: 1,
    colorScheme,
    locale: opts.locale ?? 'zh-CN',
    timezoneId: 'Asia/Shanghai',
    reducedMotion: 'reduce',
    // prefers-reduced-motion: reduce 是给产品侧的确定性开关的一半（另一半是
    // 截图期的 animations:'disabled'）；产品目前不读它，但留给后续批。
  });

  // 错误面分两档：pageErrors = 真 JS 异常（基线采集必须为 0）；
  // netErrors = 打桩未覆盖的端点 404（预期噪声，只做记录，不让基线因此变红）。
  const errors = [];
  const netErrors = [];
  page.on('pageerror', (e) => {
    const msg = String(e && e.message ? e.message : e);
    const stack = String(e && e.stack ? e.stack : '');
    // monaco 的 blob/web-worker 在 route 打桩下取不到 worker 模块
    // （vendor/monaco/vs/base/worker/workerMain.js 内部 fetch 404）——真实服务端
    // 不存在该问题，且不影响渲染像素（语言基础高亮走主线程）。归 netErrors，
    // 精确按栈帧归因，不做「全部吞掉」。
    if (/vendor\/monaco\/vs\/base\/worker\/workerMain\.js/.test(stack)) { netErrors.push(msg); return; }
    errors.push(msg);
  });
  page.on('console', (m) => {
    if (m.type() !== 'error') return;
    const text = m.text();
    // 「Failed to load resource」是网络层噪声（打桩对未覆盖端点一律 404），
    // 不是产品 JS 异常 —— 归到 netErrors，不计入 pageErrors。
    if (/Failed to load resource|net::ERR_|An unknown error occurred when fetching the script/.test(text)) {
      netErrors.push(text);
      return;
    }
    errors.push(`console: ${text}`);
  });

  await page.addInitScript((cfg) => {
    try {
      localStorage.setItem('nebflow_token', 'visual-baseline-token');
      localStorage.setItem('nebflow_locale', cfg.locale);
    } catch { /* ignore */ }
    // 文件树固定夹具：真实实例总能答 listDir，打桩不答就会永远停在
    // 「加载中…」（explorer.js:881-940 的有界等待只管超时，不管内容）。
    // 答上它，侧栏才是产品的常态而不是打桩的破绽。
    const EXPLORER_ENTRIES = [
      { name: 'docs', type: 'dir' },
      { name: 'src', type: 'dir' },
      { name: 'README.md', type: 'file', size: 2048, mtime: 1757000000000 },
      { name: 'CONTRIBUTING.md', type: 'file', size: 1024, mtime: 1757000000000 },
    ];
    class MockWS {
      static CONNECTING = 0; static OPEN = 1; static CLOSING = 2; static CLOSED = 3;
      constructor(u) {
        this.url = u;
        this.readyState = MockWS.CONNECTING;
        window.__wsMock = this;
        window.__wsSent = [];
      }
      send(data) {
        let f = null;
        try { f = JSON.parse(data); } catch { /* ignore */ }
        if (f) window.__wsSent.push(f);
        // listDir 是唯一「不答就画不出来」的只读帧，固定应答保侧栏常态。
        if (cfg.autoRespond && f && f.type === 'listDir') {
          const reply = { type: 'dirListing', path: f.path, resolvedPath: f.path, entries: EXPLORER_ENTRIES };
          setTimeout(() => { try { this.onmessage && this.onmessage({ data: JSON.stringify(reply) }); } catch { /* ignore */ } }, 0);
        }
      }
      close() { this.readyState = MockWS.CLOSED; }
      onopen = null; onmessage = null; onclose = null; onerror = null;
    }
    Object.defineProperty(window, 'WebSocket', { value: MockWS });
  }, { locale: opts.locale ?? 'zh-CN', autoRespond: opts.autoRespond !== false });

  const stubs = opts.apiStubs || {};
  await page.route('**/*', (route) => {
    const url = new URL(route.request().url());
    let p = decodeURIComponent(url.pathname);
    if (p === '/') p = '/index.html';
    // 桩匹配：最长前缀命中（'/api/agents' 能覆盖 '/api/agents/X/avatar/meta'）
    let stubKey = null;
    for (const k of Object.keys(stubs)) if (p === k || p.startsWith(k + '/') || p.startsWith(k + '?')) {
      if (!stubKey || k.length > stubKey.length) stubKey = k;
    }
    const stub = stubKey ? stubs[stubKey] : null;
    if (stub) {
      const status = stub.status ?? 200;
      // 无 body 的桩 = 只回状态码（204 之类），别硬塞 JSON 空对象
      if (stub.body === undefined) return route.fulfill({ status, body: '' });
      return route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(stub.body) });
    }
    if (p.startsWith('/api/')) {
      return route.fulfill({ status: 404, contentType: 'application/json', body: '{"error":"not found"}' });
    }
    const file = normalize(join(WEB, p));
    if (!file.startsWith(normalize(WEB))) return route.fulfill({ status: 403, body: 'forbidden' });
    try {
      return route.fulfill({ status: 200, contentType: MIME[extname(file)] || 'application/octet-stream', body: readFileSync(file) });
    } catch {
      return route.fulfill({ status: 404, body: 'not found' });
    }
  });

  // 注入一条服务端帧 —— 走真实 ws.js 分发（产品路径），不直写 DOM。
  const frame = (f) => page.evaluate((fr) => window.__wsMock.onmessage({ data: JSON.stringify(fr) }), f);

  const boot = async (base = {}) => {
    await page.goto('http://localhost:1/', { waitUntil: 'domcontentloaded' });
    await page.waitForFunction(() => window.__wsMock, null, { timeout: 20000 });
    await page.evaluate(() => {
      window.__wsMock.readyState = 1;
      window.__wsMock.onopen && window.__wsMock.onopen();
    });
    await page.waitForTimeout(250);
    const sid = base.sessionId ?? 'visual-root';
    await frame({
      type: 'sessionList', sessionId: sid, activeId: sid, folders: [],
      sessions: base.sessions ?? [{ id: sid, name: 'Nebula', agentName: 'Nebula' }],
    });
    await frame({
      type: 'configData',
      config: typeof base.config === 'string' ? base.config : JSON.stringify(base.config ?? { llm: { providers: {} }, features: { friends: false } }),
      configured: true, onboarding: 'done',
    });
    await frame({ type: 'historyPage', sessionId: sid, messages: base.messages ?? [], hasMore: false, offset: 0 });
    await settle(page);
    return sid;
  };

  return { page, frame, boot, errors, netErrors, close: () => page.close() };
}

/** 稳定化：字体就位 + 双 rAF + 一拍静置。之后才允许截图。 */
export async function settle(page, extra = 150) {
  await page.evaluate(async () => {
    if (document.fonts && document.fonts.ready) await document.fonts.ready;
    await new Promise((r) => requestAnimationFrame(() => requestAnimationFrame(r)));
  });
  await page.waitForTimeout(extra);
}

/**
 * 遮罩：把时钟/随机/图片等不可比区域涂成纯色再拍。
 * 必须「拍基准」和「拍当前」同样执行 —— 遮罩随即进入基准图像素，天然对齐。
 */
export async function applyMasks(page, selectors = []) {
  if (!selectors.length) return;
  await page.evaluate((sels) => {
    document.querySelectorAll('[data-visual-mask]').forEach((n) => n.remove());
    for (const sel of sels) {
      for (const el of document.querySelectorAll(sel)) {
        const r = el.getBoundingClientRect();
        if (!r.width || !r.height) continue;
        const d = document.createElement('div');
        d.setAttribute('data-visual-mask', '1');
        Object.assign(d.style, {
          position: 'fixed',
          left: `${r.left}px`, top: `${r.top}px`, width: `${r.width}px`, height: `${r.height}px`,
          background: '#ff00ff', zIndex: '2147483647', pointerEvents: 'none',
        });
        document.body.appendChild(d);
      }
    }
  }, selectors);
}

/** 截图 → Buffer（元素截图缺省；animations:'disabled' 冻结 CSS 动效/过渡）。 */
export async function shoot(page, { selector, fullPage = false } = {}) {
  const opt = { animations: 'disabled', caret: 'hide', scale: 'css', timeout: 15000 };
  if (selector) return page.locator(selector).first().screenshot(opt);
  return page.screenshot({ ...opt, fullPage });
}

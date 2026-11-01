// dd-selector-reskin-shots.mjs — @ / 命令候选浮层选中/hover 高亮换代批（作者令
// 2026-11-01 20:03「@ 选项目 / 选 model 的弹窗，选中项的高亮从现在的黑色改成
// 浅灰色」）截图 + 断言。本文件 = 该浮层面 spec 第 2 代（第 1 代 = 2026-10-31
// 换装批墨族选中行，随本令退役；材质段已按统一按钮玻璃批 2026-11-01 现行真值
// 重锚 --glass-bg + blur24 sat1.15）。
//
// 断言面（材质/几何全部走运行时 computed 值，双引擎（chromium+webkit）× 双主题）：
//   R1 两浮层容器：底色 = --glass-bg 面
//      级浮件档解析值（亮 rgba(255,255,255,.55) / 暗 rgba(24,28,38,.68)）、r16、
//      blur(24px) saturate(1.15)；分组标题在场（aria-hidden 非 option）
//   R2 @ 行选中态换代：选中行 = 浅灰 wash（亮 rgba(17,24,39,.08) / 暗
//      rgba(255,255,255,.12) 低透明 wash 非白块），字色不翻转（active 行
//      主/副文字 = rest 行同色，path 透明度回 1）；行内缩 r10 圆角
//   R3 / 浮层同语言：同一套 --dd-row-* 档（与 @ 浮层共用，逐值同断言）
//   R4 hover 同语言淡一档：鼠标停驻行在键盘把 active 移走后 :hover = 淡一档
//      wash（亮 rgba(17,24,39,.04) / 暗 rgba(255,255,255,.06)）≠ 选中 wash
//   R5 交互零改：↓ 键盘导航 active 行迁移、@ 浮层 Esc 关闭（行为契约原样）
//   R6 零新增 page error（双引擎 × 双主题全腿）
//   产物 = evidence/20261101_dd-gray-select/*.png（@ 选中帧 = 验收实拍：
//   双引擎 × 双主题各一张 + hover 淡一档帧 + / 浮层同语言帧）
//
// 隔离：自包含——静态文件按 route 直接读盘（无静态服务器、无端口、无实例）、
// WS = routeWebSocket mock、/api/projects mock 项目表。不碰宿主 8080 / 宿主进程；
// 收尾 browser.close()（try/finally）。webkit 不走 <1024 特权口（restricted
// network port），BASE 换 127.0.0.1:59903（bg-source-chain-shots 同款）。
// Run: node tests/dd-selector-reskin-shots.mjs [--engine chromium|webkit|both]
import { chromium, webkit } from 'playwright-core';
import { readFileSync, mkdirSync } from 'node:fs';
import { join, dirname, extname, normalize, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const WEB = resolve(process.env.SLASH_WEB_ROOT || join(HERE, '..', 'src', 'main', 'resources', 'web'));
const OUT = join(HERE, '..', 'evidence', '20261101_dd-gray-select');
mkdirSync(OUT, { recursive: true });
const GATE_KEY = 'stand_slash.enabled';
const PROJECTS = [
  { name: 'Stand', workspace: '/Users/kaiyu/Claude code/Stand' },
  { name: 'DOSTEL', workspace: '/c/DOSTEL' },
  { name: 'wiki-docs', workspace: '/tmp/wiki-docs' },
];
const MIME = {
  '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.svg': 'image/svg+xml',
  '.png': 'image/png', '.ico': 'image/x-icon', '.json': 'application/json', '.woff2': 'font/woff2',
};
const ENGINE_ARG = (process.argv[2] || '').includes('webkit') ? 'webkit' : (process.argv[2] || '').includes('chromium') ? 'chromium' : 'both';

let failures = 0;
function ok(name, cond, extra = '') {
  console.log(`${cond ? 'PASS' : 'FAIL'}  ${name}${extra !== '' ? '  — ' + extra : ''}`);
  if (!cond) failures++;
}
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

/** boot — 真渲染页面（index.html + 真 CSS/JS 读盘）+ WS mock + /api/projects mock。 */
async function boot(browserType, { theme = 'light', gate = false, width = 1440, height = 900 } = {}) {
  const browser = await browserType.launch();
  const ctx = await browser.newContext({ viewport: { width, height }, colorScheme: theme });
  await ctx.addInitScript(([gateKey, gateOn]) => {
    localStorage.setItem('nebflow_token', 't');
    localStorage.setItem('neblink_token', 't');
    localStorage.setItem('nebflow_locale', 'zh-CN');
    localStorage.setItem('neblink_locale', 'zh-CN');
    if (gateOn) localStorage.setItem(gateKey, '1');
    else localStorage.removeItem(gateKey);
  }, [GATE_KEY, gate]);
  const page = await ctx.newPage();
  const pageErrors = [];
  page.on('pageerror', (e) => pageErrors.push(e.message));
  const base = browserType.name() === 'webkit' ? 'http://127.0.0.1:59903/' : 'http://localhost:1/';
  // catch-all 先注册 —— Playwright 逆序匹配（后注册者优先）
  await page.route('**/*', (route) => {
    const url = new URL(route.request().url());
    let p = decodeURIComponent(url.pathname);
    if (p === '/') p = '/index.html';
    if (p.startsWith('/api/')) return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
    const file = normalize(join(WEB, p));
    if (!file.startsWith(normalize(WEB))) return route.fulfill({ status: 403, body: 'forbidden' });
    try {
      return route.fulfill({ status: 200, contentType: MIME[extname(file)] || 'application/octet-stream', body: readFileSync(file) });
    } catch { return route.fulfill({ status: 404, body: 'not found' }); }
  });
  // /api/projects：mention 数据源 mock（后注册优先于 catch-all）。
  // 载荷形态 = {projects:[...]}（nodeData.fetchProjectsRaw 契约，非裸数组）。
  await page.route('**/api/projects', (route) => route.fulfill({
    status: 200, contentType: 'application/json', body: JSON.stringify({ projects: PROJECTS }),
  }));
  // /sw.js 404 ⇒ SW 注册被拒 = 静默空转（swReg.js 降级门，零影响）。否则
  // webkit 下 SW 偶发中途激活、拦截虚拟路由请求 → "Response served by
  // service worker is an error" 假 page error（R6 只验本批浮层面，不含 SW 面）。
  await page.route('**/sw.js', (route) => route.fulfill({ status: 404, body: 'not found' }));
  await page.routeWebSocket(/\/ws/, (ws) => {
    ws.send(JSON.stringify({
      type: 'configData',
      config: '{"features":{"friends":false}}',
      configured: true,
      onboarding: 'done',
      models: [],
      defaults: {},
    }));
    ws.send(JSON.stringify({ type: 'sessionList', sessions: [{ id: 's1', agentName: 'Nebula', title: 'T', updatedAt: Date.now() }], activeId: 's1', folders: [] }));
  });
  await page.goto(base + 'index.html');
  await page.waitForSelector('#input', { state: 'attached', timeout: 15000 });
  await sleep(900);
  return { browser, ctx, page, pageErrors };
}

/** 打开 @ 浮层并等待行渲染。 */
async function openMention(page) {
  await page.click('#input');
  await page.fill('#input', '@');
  await page.waitForSelector('#mention-dropdown.on .mention-item', { timeout: 5000 });
  await sleep(150);
}

/** 打开 / 浮层并等待行渲染（需 gate=on 才有 ≥2 行）。 */
async function openSlash(page) {
  await page.click('#input');
  await page.fill('#input', '/');
  await page.waitForSelector('#slash-dropdown.on .slash-item', { timeout: 5000 });
  await sleep(150);
}

/** 读 @ 浮层材质/行态 computed 值（含 hover 行 = :hover 且非 active 的行）。 */
const readMention = (page) => page.evaluate(() => {
  const dd = document.getElementById('mention-dropdown');
  const cs = getComputedStyle(dd);
  const rows = Array.from(dd.querySelectorAll('.mention-item'));
  const active = dd.querySelector('.mention-item.active');
  const title = dd.querySelector('.dd-group-title');
  const acs = active ? getComputedStyle(active) : null;
  const ncs = active ? getComputedStyle(active.querySelector('.mention-name')) : null;
  const pcs = active ? getComputedStyle(active.querySelector('.mention-path')) : null;
  const rest = rows.find((r) => !r.classList.contains('active'));
  const rcs = rest ? getComputedStyle(rest) : null;
  const rns = rest ? getComputedStyle(rest.querySelector('.mention-name')) : null;
  const rps = rest ? getComputedStyle(rest.querySelector('.mention-path')) : null;
  const hovered = rows.find((r) => r.matches(':hover') && !r.classList.contains('active'));
  const hcs = hovered ? getComputedStyle(hovered) : null;
  return {
    ddBg: cs.backgroundColor, ddRadius: cs.borderRadius, ddBlur: cs.backdropFilter || cs.webkitBackdropFilter,
    titleText: title?.textContent ?? null, titleAriaHidden: title?.getAttribute('aria-hidden') ?? null,
    rowCount: rows.length, rowRadius: rows[0] ? getComputedStyle(rows[0]).borderRadius : null,
    activeBg: acs?.backgroundColor ?? null, activeNameColor: ncs?.color ?? null,
    activePathColor: pcs?.color ?? null, activePathOpacity: pcs?.opacity ?? null,
    restNameColor: rns?.color ?? null, restPathColor: rps?.color ?? null,
    hoverBg: hcs?.backgroundColor ?? null,
  };
});
/** 读 / 浮层材质/行态 computed 值（同语言断言）。 */
const readSlash = (page) => page.evaluate(() => {
  const dd = document.getElementById('slash-dropdown');
  const cs = getComputedStyle(dd);
  const rows = Array.from(dd.querySelectorAll('.slash-item'));
  const active = dd.querySelector('.slash-item.active');
  const title = dd.querySelector('.dd-group-title');
  const acs = active ? getComputedStyle(active) : null;
  const ncs = active ? getComputedStyle(active.querySelector('.slash-cmd')) : null;
  const rest = rows.find((r) => !r.classList.contains('active'));
  const rns = rest ? getComputedStyle(rest.querySelector('.slash-cmd')) : null;
  return {
    ddBg: cs.backgroundColor, ddRadius: cs.borderRadius, ddBlur: cs.backdropFilter || cs.webkitBackdropFilter,
    titleText: title?.textContent ?? null, titleAriaHidden: title?.getAttribute('aria-hidden') ?? null,
    rowCount: rows.length, cmds: rows.map((r) => r.querySelector('.slash-cmd')?.textContent),
    activeBg: acs?.backgroundColor ?? null, activeNameColor: ncs?.color ?? null,
    restNameColor: rns?.color ?? null,
  };
});

// 双主题期望值（--glass-bg / --dd-row-* 逐字 input.css 现行真值）
const EXPECT = {
  light: {
    ddBg: 'rgba(255, 255, 255, 0.55)',
    activeBg: 'rgba(17, 24, 39, 0.08)', hoverBg: 'rgba(17, 24, 39, 0.04)',
  },
  dark: {
    ddBg: 'rgba(24, 28, 38, 0.68)',
    activeBg: 'rgba(255, 255, 255, 0.12)', hoverBg: 'rgba(255, 255, 255, 0.06)',
  },
};

const ENGINES = ENGINE_ARG === 'webkit' ? [['webkit', webkit]]
  : ENGINE_ARG === 'chromium' ? [['chromium', chromium]]
  : [['chromium', chromium], ['webkit', webkit]];

for (const [engineName, browserType] of ENGINES) {
  // ══════════════════════════════════════════════════════════════════════
  // @ 浮层（mention）· 双主题：材质 + 分组标题 + 浅灰选中行 + hover 淡一档
  //   + 键盘/Esc 契约（验收实拍腿：选中项高亮那一帧）
  // ══════════════════════════════════════════════════════════════════════
  for (const theme of ['light', 'dark']) {
    const { browser, ctx, page, pageErrors } = await boot(browserType, { theme });
    const exp = EXPECT[theme];
    const tag = `[${engineName}·${theme === 'light' ? '亮' : '暗'}]`;

    await openMention(page);
    const m = await readMention(page);
    ok(`${tag} R1a @浮层底色 = --glass-bg 解析值`, m.ddBg === exp.ddBg, m.ddBg);
    ok(`${tag} R1b @浮层圆角 16px / blur(24px) saturate(1.15)`, m.ddRadius === '16px' && m.ddBlur.includes('blur(24px)') && m.ddBlur.includes('saturate(1.15)'), `${m.ddRadius} | ${m.ddBlur}`);
    ok(`${tag} R1c 分组标题「项目」在场且 aria-hidden（非 option）`, m.titleText === '项目' && m.titleAriaHidden === 'true', JSON.stringify({ t: m.titleText, a: m.titleAriaHidden }));
    ok(`${tag} R1d 行数 = mock 项目数 3，行圆角 10px（内缩圆角行）`, m.rowCount === 3 && m.rowRadius === '10px', `${m.rowCount} | ${m.rowRadius}`);
    ok(`${tag} R2a 选中行 = 浅灰 wash（非墨面黑块）`, m.activeBg === exp.activeBg, m.activeBg);
    ok(`${tag} R2b 选中行主文字不翻转（= rest 行同色）`, m.activeNameColor === m.restNameColor, `active ${m.activeNameColor} | rest ${m.restNameColor}`);
    ok(`${tag} R2c 选中行副行不翻转、透明度回 1`, m.activePathColor === m.restPathColor && m.activePathOpacity === '1', `${m.activePathColor} | ${m.activePathOpacity}`);
    // 验收实拍：@ 浮层展开 + 选中项高亮那一帧（双引擎 × 双主题各一张）
    await page.locator('#mention-dropdown').screenshot({ path: join(OUT, `mention-${engineName}-${theme}-row1-active.png`) });

    // R4 hover 同语言淡一档：鼠标停驻第 2 行（mouseenter 即接管选中），再
    // ↓ 把 active 移到第 3 行 —— 停驻行只剩 :hover，读其背景 = 淡一档 wash。
    // 读 computed 前等过行背景 0.15s transition（读在过渡中 = 半程值假红）。
    await page.locator('#mention-dropdown .mention-item').nth(1).hover();
    await sleep(300);
    await page.keyboard.press('ArrowDown');
    await sleep(400);
    const hv = await readMention(page);
    ok(`${tag} R4a hover 行 = 同语言淡一档 wash`, hv.hoverBg === exp.hoverBg, hv.hoverBg);
    ok(`${tag} R4b hover wash ≠ 选中 wash（淡一档可辨）`, hv.hoverBg !== hv.activeBg && hv.activeBg === exp.activeBg, `hover ${hv.hoverBg} | active ${hv.activeBg}`);
    await page.locator('#mention-dropdown').screenshot({ path: join(OUT, `mention-${engineName}-${theme}-hover-faint.png`) });

    // 键盘导航契约（原样）：active 在第 3 行（transition 收尾后再读/拍帧）。
    const nav = await page.evaluate(() => {
      const rows = Array.from(document.querySelectorAll('#mention-dropdown .mention-item'));
      return { activeIdx: rows.findIndex((r) => r.classList.contains('active')) };
    });
    ok(`${tag} R5a ↓ 键导航 active 迁移到第 3 行（交互零改）`, nav.activeIdx === 2, `idx=${nav.activeIdx}`);
    await page.locator('#mention-dropdown').screenshot({ path: join(OUT, `mention-${engineName}-${theme}-row3-active.png`) });
    await page.keyboard.press('Escape');
    await sleep(120);
    const closed = await page.evaluate(() => !document.getElementById('mention-dropdown').classList.contains('on'));
    ok(`${tag} R5b Esc 关闭 @浮层（交互零改）`, closed);
    ok(`${tag} R6 零 page error`, pageErrors.length === 0, pageErrors.join('; '));
    await ctx.close();
    await browser.close();
  }

  // ══════════════════════════════════════════════════════════════════════
  // / 浮层（slash，gate=on）· 双主题：同一套 --dd-row-* 档（两浮层共用态样式）
  // ══════════════════════════════════════════════════════════════════════
  for (const theme of ['light', 'dark']) {
    const { browser, ctx, page, pageErrors } = await boot(browserType, { theme, gate: true });
    const exp = EXPECT[theme];
    const tag = `[${engineName}·${theme === 'light' ? '亮' : '暗'}]`;

    await openSlash(page);
    const s = await readSlash(page);
    ok(`${tag} R3a /浮层底色 = --glass-bg 解析值`, s.ddBg === exp.ddBg, s.ddBg);
    ok(`${tag} R3b /浮层圆角 16px / blur(24px) saturate(1.15)`, s.ddRadius === '16px' && s.ddBlur.includes('blur(24px)') && s.ddBlur.includes('saturate(1.15)'), `${s.ddRadius} | ${s.ddBlur}`);
    ok(`${tag} R3c 分组标题「斜杠命令」在场且 aria-hidden`, s.titleText === '斜杠命令' && s.titleAriaHidden === 'true', JSON.stringify({ t: s.titleText, a: s.titleAriaHidden }));
    ok(`${tag} R3d gate=on 行集 ≥2（/ask /onboarding /model）`, s.rowCount >= 2 && s.cmds.includes('/model'), JSON.stringify(s.cmds));
    ok(`${tag} R3e / 浮层选中行 = 同一套浅灰 wash（与 @ 共用）`, s.activeBg === exp.activeBg, s.activeBg);
    ok(`${tag} R3f / 浮层选中行主文字不翻转（与 @ 同语言）`, s.activeNameColor === s.restNameColor, `active ${s.activeNameColor} | rest ${s.restNameColor}`);
    await page.locator('#slash-dropdown').screenshot({ path: join(OUT, `slash-${engineName}-${theme}-row1-active.png`) });

    await page.keyboard.press('ArrowDown');
    await sleep(120);
    const nav = await page.evaluate(() => {
      const rows = Array.from(document.querySelectorAll('#slash-dropdown .slash-item'));
      return { activeIdx: rows.findIndex((r) => r.classList.contains('active')) };
    });
    ok(`${tag} R5c /浮层 ↓ 键导航 active 迁移（交互零改）`, nav.activeIdx === 1, `idx=${nav.activeIdx}`);
    ok(`${tag} R6 零 page error`, pageErrors.length === 0, pageErrors.join('; '));
    await ctx.close();
    await browser.close();
  }
}

console.log(`\nshots → ${OUT}`);
console.log(failures === 0 ? 'ALL PASS' : `${failures} FAILURES`);
process.exit(failures === 0 ? 0 : 1);

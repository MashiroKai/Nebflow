// dd-selector-reskin-shots.mjs — @ / 命令候选浮层换装批（作者令 2026-10-31
// 「@ 和 / 命令出现在对话框上的选择器的 ui 能不能优化一下…风格跟我们对不上」）
// 截图 + 断言。
//
// 断言面（材质/几何全部走运行时 computed 值，双主题各一遍）：
//   R1 @ 浮层：分组标题（项目，aria-hidden 非 option）在场；容器底色/圆角/ backdrop
//      blur = --g2-fab-* 液态玻璃悬浮件 token 解析值（亮 rgba(255,255,255,.60) /
//      暗 .14；r16；blur(16px) saturate(1.8)）
//   R2 @ 行：选中行墨族高亮（亮 #111827 底白字 / 暗 #e6edf3 底深字），副行随
//      高亮翻转 72% 透明；行内缩 r10 圆角
//   R3 / 浮层：分组标题（斜杠命令）+ 同材质断言；亮暗双档
//   R4 交互零改：↓ 键盘导航 active 行迁移、@ 浮层 Esc 关闭（行为契约原样）
//   R5 零新增 page error（双主题）
//   产物 = evidence/20261031_dd-selector-reskin/*.png（双浮层 × 双主题 + 选中态）
//
// 隔离：自包含——静态文件按 route 直接读盘（无静态服务器、无端口、无实例）、
// WS = routeWebSocket mock、/api/projects mock 项目表。不碰宿主 8080 / 宿主进程；
// 收尾 browser.close()（try/finally）。
// Run: node tests/dd-selector-reskin-shots.mjs
import { chromium } from 'playwright-core';
import { readFileSync, mkdirSync } from 'node:fs';
import { join, dirname, extname, normalize, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const WEB = resolve(process.env.SLASH_WEB_ROOT || join(HERE, '..', 'src', 'main', 'resources', 'web'));
const OUT = join(HERE, '..', 'evidence', '20261031_dd-selector-reskin');
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

let failures = 0;
function ok(name, cond, extra = '') {
  console.log(`${cond ? 'PASS' : 'FAIL'}  ${name}${extra !== '' ? '  — ' + extra : ''}`);
  if (!cond) failures++;
}
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

const browser = await chromium.launch();

/** boot — 真渲染页面（index.html + 真 CSS/JS 读盘）+ WS mock + /api/projects mock。 */
async function boot({ theme = 'light', gate = false, width = 1440, height = 900 } = {}) {
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
  await page.goto('http://localhost:1/index.html');
  await page.waitForSelector('#input', { state: 'attached', timeout: 15000 });
  await sleep(900);
  return { ctx, page, pageErrors };
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

/** 读浮层材质/行态 computed 值。 */
const readMention = (page) => page.evaluate(() => {
  const dd = document.getElementById('mention-dropdown');
  const cs = getComputedStyle(dd);
  const rows = Array.from(dd.querySelectorAll('.mention-item'));
  const active = dd.querySelector('.mention-item.active');
  const title = dd.querySelector('.dd-group-title');
  const acs = active ? getComputedStyle(active) : null;
  const ncs = active ? getComputedStyle(active.querySelector('.mention-name')) : null;
  const pcs = active ? getComputedStyle(active.querySelector('.mention-path')) : null;
  return {
    ddBg: cs.backgroundColor, ddRadius: cs.borderRadius, ddBlur: cs.backdropFilter || cs.webkitBackdropFilter,
    titleText: title?.textContent ?? null, titleAriaHidden: title?.getAttribute('aria-hidden') ?? null,
    rowCount: rows.length, rowRadius: rows[0] ? getComputedStyle(rows[0]).borderRadius : null,
    activeBg: acs?.backgroundColor ?? null, activeNameColor: ncs?.color ?? null,
    activePathColor: pcs?.color ?? null, activePathOpacity: pcs?.opacity ?? null,
  };
});
const readSlash = (page) => page.evaluate(() => {
  const dd = document.getElementById('slash-dropdown');
  const cs = getComputedStyle(dd);
  const rows = Array.from(dd.querySelectorAll('.slash-item'));
  const active = dd.querySelector('.slash-item.active');
  const title = dd.querySelector('.dd-group-title');
  const acs = active ? getComputedStyle(active) : null;
  const ncs = active ? getComputedStyle(active.querySelector('.slash-cmd')) : null;
  return {
    ddBg: cs.backgroundColor, ddRadius: cs.borderRadius, ddBlur: cs.backdropFilter || cs.webkitBackdropFilter,
    titleText: title?.textContent ?? null, titleAriaHidden: title?.getAttribute('aria-hidden') ?? null,
    rowCount: rows.length, cmds: rows.map((r) => r.querySelector('.slash-cmd')?.textContent),
    activeBg: acs?.backgroundColor ?? null, activeNameColor: ncs?.color ?? null,
  };
});

// 双主题期望值（--g2-fab-* / --g2-ink-* 档，逐字 stand-tokens.css）
const EXPECT = {
  light: {
    ddBg: 'rgba(255, 255, 255, 0.6)', inkBg: 'rgb(17, 24, 39)', inkText: 'rgb(255, 255, 255)',
  },
  dark: {
    ddBg: 'rgba(255, 255, 255, 0.14)', inkBg: 'rgb(230, 237, 243)', inkText: 'rgb(13, 17, 23)',
  },
};

// ════════════════════════════════════════════════════════════════════════
// @ 浮层（mention）· 双主题：材质 + 分组标题 + 墨族选中行 + 键盘/Esc 契约
// ════════════════════════════════════════════════════════════════════════
for (const theme of ['light', 'dark']) {
  const { ctx, page, pageErrors } = await boot({ theme });
  const exp = EXPECT[theme];
  const tag = theme === 'light' ? '亮' : '暗';

  await openMention(page);
  const m = await readMention(page);
  ok(`[${tag}] R1a @浮层底色 = --g2-fab-bg 解析值`, m.ddBg === exp.ddBg, m.ddBg);
  ok(`[${tag}] R1b @浮层圆角 16px / blur(16px) saturate(1.8)`, m.ddRadius === '16px' && m.ddBlur.includes('blur(16px)') && m.ddBlur.includes('saturate(1.8)'), `${m.ddRadius} | ${m.ddBlur}`);
  ok(`[${tag}] R1c 分组标题「项目」在场且 aria-hidden（非 option）`, m.titleText === '项目' && m.titleAriaHidden === 'true', JSON.stringify({ t: m.titleText, a: m.titleAriaHidden }));
  ok(`[${tag}] R1d 行数 = mock 项目数 3，行圆角 10px（内缩圆角行）`, m.rowCount === 3 && m.rowRadius === '10px', `${m.rowCount} | ${m.rowRadius}`);
  ok(`[${tag}] R2a 选中行墨族高亮 = --g2-ink-bg`, m.activeBg === exp.inkBg, m.activeBg);
  ok(`[${tag}] R2b 选中行主文字随高亮翻转为墨字色`, m.activeNameColor === exp.inkText, m.activeNameColor);
  ok(`[${tag}] R2c 选中行副行（路径）翻转为墨字 72% 透明`, m.activePathColor === exp.inkText && m.activePathOpacity === '0.72', `${m.activePathColor} | ${m.activePathOpacity}`);
  await page.locator('#mention-dropdown').screenshot({ path: join(OUT, `mention-${theme}-row1-active.png`) });

  // 键盘导航契约（原样）：↓ → active 迁移到第 2 行；Esc 关闭。
  await page.keyboard.press('ArrowDown');
  await sleep(120);
  const nav = await page.evaluate(() => {
    const rows = Array.from(document.querySelectorAll('#mention-dropdown .mention-item'));
    return { activeIdx: rows.findIndex((r) => r.classList.contains('active')) };
  });
  ok(`[${tag}] R4a ↓ 键导航 active 迁移到第 2 行（交互零改）`, nav.activeIdx === 1, `idx=${nav.activeIdx}`);
  await page.locator('#mention-dropdown').screenshot({ path: join(OUT, `mention-${theme}-row2-active.png`) });
  await page.keyboard.press('Escape');
  await sleep(120);
  const closed = await page.evaluate(() => !document.getElementById('mention-dropdown').classList.contains('on'));
  ok(`[${tag}] R4b Esc 关闭 @浮层（交互零改）`, closed);
  ok(`[${tag}] R5 零 page error`, pageErrors.length === 0, pageErrors.join('; '));
  await ctx.close();
}

// ════════════════════════════════════════════════════════════════════════
// / 浮层（slash，gate=on）· 双主题：同材质断言 + 分组标题 + 键盘契约
// ════════════════════════════════════════════════════════════════════════
for (const theme of ['light', 'dark']) {
  const { ctx, page, pageErrors } = await boot({ theme, gate: true });
  const exp = EXPECT[theme];
  const tag = theme === 'light' ? '亮' : '暗';

  await openSlash(page);
  const s = await readSlash(page);
  ok(`[${tag}] R3a /浮层底色 = --g2-fab-bg 解析值`, s.ddBg === exp.ddBg, s.ddBg);
  ok(`[${tag}] R3b /浮层圆角 16px / blur(16px) saturate(1.8)`, s.ddRadius === '16px' && s.ddBlur.includes('blur(16px)') && s.ddBlur.includes('saturate(1.8)'), `${s.ddRadius} | ${s.ddBlur}`);
  ok(`[${tag}] R3c 分组标题「斜杠命令」在场且 aria-hidden`, s.titleText === '斜杠命令' && s.titleAriaHidden === 'true', JSON.stringify({ t: s.titleText, a: s.titleAriaHidden }));
  ok(`[${tag}] R3d gate=on 行集 ≥2（/ask /onboarding /model）`, s.rowCount >= 2 && s.cmds.includes('/model'), JSON.stringify(s.cmds));
  ok(`[${tag}] R3e 选中行墨族高亮 / 主文字翻转`, s.activeBg === exp.inkBg && s.activeNameColor === exp.inkText, `${s.activeBg} | ${s.activeNameColor}`);
  await page.locator('#slash-dropdown').screenshot({ path: join(OUT, `slash-${theme}-row1-active.png`) });

  await page.keyboard.press('ArrowDown');
  await sleep(120);
  const nav = await page.evaluate(() => {
    const rows = Array.from(document.querySelectorAll('#slash-dropdown .slash-item'));
    return { activeIdx: rows.findIndex((r) => r.classList.contains('active')) };
  });
  ok(`[${tag}] R4c /浮层 ↓ 键导航 active 迁移（交互零改）`, nav.activeIdx === 1, `idx=${nav.activeIdx}`);
  await page.locator('#slash-dropdown').screenshot({ path: join(OUT, `slash-${theme}-row2-active.png`) });
  ok(`[${tag}] R5 零 page error`, pageErrors.length === 0, pageErrors.join('; '));
  await ctx.close();
}

console.log(`\nshots → ${OUT}`);
console.log(failures === 0 ? 'ALL PASS' : `${failures} FAILURES`);
try { await browser.close(); } finally { process.exit(failures === 0 ? 0 : 1); }

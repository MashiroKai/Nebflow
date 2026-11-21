#!/usr/bin/env node
// select-unify-f.spec.mjs — F 批（ui-unify-panels 2026-11-17）自检：
// 原生 <select> 全站收编为「点击展开点选」。
//
// Capability under test: js/dropdown.js 收编器 + css/dropdown.css 修饰层。
//   · 桌面设置页（#cfg-thinking-effort / #cfg-language）：包裹结构、蒙层
//     透明 + 指针穿透、点 trigger 开选项单、点选项 = select.value 落值 +
//     change 冒泡 + 菜单收、Esc / 点外收、单例、键盘道（Enter 开 / 箭头巡 /
//     Enter 选）、选项表重建后 trigger 脸跟、禁用态镜像、optgroup 分组头；
//   · Playwright 兼容契约：收编后 selectOption 仍直打原生 select（回归面 ——
//     全站十余个既有 spec 靠它活）；
//   · 手机层：设置页 #cfg-thinking-effort 触发钮点开动作表形态选项单、点选落值
//     （原样本 = 搜索面板 #mh-search-kind，search-page-density 批 2026-11-21
//     作者令①「删掉『全部来源』大下拉框」后整件退役 ⇒ 换宿主，能力断言不变）；
//     #search-scope（手机层被 CSS 藏）不被包裹 —— isHidden 语义不动。
//
// Run: node tests/select-unify-f.spec.mjs
//
// 零后端 harness（与 tests/todo-row-reply.spec.mjs 同族）：page.route 供
// web/ 源树 + routeWebSocket 帧桩 + localStorage 注入。

import { chromium } from 'playwright';
import { readFileSync, mkdirSync, writeFileSync } from 'node:fs';
import { join, dirname, extname, normalize, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const ROOT = resolve(HERE, '..');
const WEB = join(ROOT, 'src', 'main', 'resources', 'web');
const OUT = join(ROOT, 'evidence', '20261117_ui-unify-panels', 'f-select');
mkdirSync(OUT, { recursive: true });

const SID = 'select-unify-f-spec';
const MIME = {
  '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css',
  '.svg': 'image/svg+xml', '.png': 'image/png', '.ico': 'image/x-icon',
  '.json': 'application/json', '.woff2': 'font/woff2', '.woff': 'font/woff', '.ttf': 'font/ttf',
};

let pass = 0;
let fail = 0;
const lines = [];
function ok(name, cond, detail = '') {
  const line = `${cond ? 'PASS' : 'FAIL'}  ${name}${detail ? '  — ' + detail : ''}`;
  console.log(line);
  lines.push(line);
  if (cond) pass++; else fail++;
}
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function makePage(browser, opts) {
  const page = await (await browser.newContext(opts)).newPage();
  const errors = [];
  page.on('pageerror', (e) => errors.push(`pageerror: ${e.message}`));
  // console 的资源 404（字体/脚手架件缺席）不算页面逻辑错误——与同族 spec 口径一致。
  page.on('console', (m) => { if (m.type() === 'error' && !/Failed to load resource|fetching the script/.test(m.text())) errors.push(m.text()); });
  await page.addInitScript(() => {
    localStorage.setItem('nebflow_token', 'e2e-token');
    localStorage.setItem('nebflow_locale', 'zh-CN');
    if (navigator.serviceWorker) Object.defineProperty(navigator.serviceWorker, 'register', { value: () => Promise.resolve(), configurable: true });
  });
  await page.route('**/*', (route) => {
    const url = new URL(route.request().url());
    let p = decodeURIComponent(url.pathname);
    if (p === '/') p = '/index.html';
    if (p.startsWith('/api/')) {
      if (p === '/api/sessions') return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ sessions: [{ id: SID, name: 'root', agentName: 'Nebula' }], activeId: SID }) });
      return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
    }
    const file = normalize(join(WEB, p));
    if (!file.startsWith(WEB)) return route.fulfill({ status: 403, body: 'forbidden' });
    try { return route.fulfill({ status: 200, contentType: MIME[extname(file)] || 'application/octet-stream', body: readFileSync(file) }); }
    catch { return route.fulfill({ status: 404, body: 'not found' }); }
  });
  await page.routeWebSocket(/\/ws/, (ws) => {
    const config = () => ws.send(JSON.stringify({ type: 'configData', config: '{"features":{}}', configured: true, onboarding: 'done', models: [], defaults: {} }));
    const sessions = () => ws.send(JSON.stringify({ type: 'sessionList', sessions: [{ id: SID, name: 'root', agentName: 'Nebula' }], folders: [], activeId: SID }));
    config(); sessions();
    ws.onMessage((raw) => {
      let m; try { m = JSON.parse(raw); } catch { return; }
      if (m.type === 'ping') ws.send(JSON.stringify({ type: 'pong' }));
      else if (m.type === 'getConfig') config();
      else if (m.type === 'getSessions' || m.type === 'listSessions') sessions();
      else if (m.type === 'getHistory') ws.send(JSON.stringify({ type: 'historyPage', sessionId: m.sessionId, messages: [], hasMore: false, offset: 0, total: 0 }));
    });
  });
  await page.goto('http://localhost:1/');
  await page.waitForFunction(async (sid) => {
    const s = (await import('/js/state.js')).default;
    return s.activeSessionId === sid && s.ws?.readyState === 1;
  }, SID, { timeout: 20000 });
  return { page, errors };
}

const READ_FACE = (id) => `(() => {
  const sel = document.getElementById(${JSON.stringify(id)});
  if (!sel) return null;
  const wrap = sel.closest('.nf-select');
  const trigger = wrap && wrap.querySelector('.nf-select-trigger');
  const cs = sel && getComputedStyle(sel);
  return {
    wrapped: !!wrap, idKept: sel.id, tag: sel.tagName,
    opacity: cs.opacity, pe: cs.pointerEvents, tabIdx: sel.tabIndex,
    triggerVisible: !!trigger && trigger.offsetParent !== null && getComputedStyle(trigger).width !== '0px',
    triggerLabel: trigger ? (trigger.querySelector('.nf-select-val') || {}).textContent : null,
    triggerDisabled: trigger ? trigger.disabled : null,
    optCount: sel.options.length,
    value: sel.value,
  };
})()`;

const browser = await chromium.launch();
let desktopErrors = [];
let mobileErrors = [];

try {
  /* ══════ 桌面腿：设置页 ══════ */
  {
    const { page, errors } = await makePage(browser, { viewport: { width: 1440, height: 900 } });
    desktopErrors = errors;
    await sleep(600);
    await page.click('#settings-btn');
    await page.waitForSelector('#cfg-thinking-effort', { timeout: 10000 });
    await sleep(500); // 收编观察器 + 脸面同步落定

    const TRG = '#cfg-thinking-effort ~ .nf-select-trigger, #cfg-thinking-effort';
    const face = await page.evaluate(READ_FACE('cfg-thinking-effort'));
    ok('F1 设置页下拉被收编（.nf-select 包裹 / id 保序 / 蒙层透明 + 指针穿透 / 退 Tab 序 / trigger 可见）',
      face && face.wrapped && face.idKept === 'cfg-thinking-effort' && face.tag === 'SELECT'
      && face.opacity === '0' && face.pe === 'none' && face.tabIdx === -1 && face.triggerVisible,
      JSON.stringify(face));

    await page.locator('#cfg-thinking-effort + .nf-select-trigger, .nf-select:has(#cfg-thinking-effort) .nf-select-trigger').first().click();
    await sleep(200);
    const menu1 = await page.evaluate(`(() => {
      const menu = document.querySelector('.nf-select-menu');
      if (!menu) return null;
      return { role: menu.getAttribute('role'), material: menu.classList.contains('fm-context-menu'),
        opts: menu.querySelectorAll('.nf-select-opt').length,
        cur: menu.querySelectorAll('.nf-select-opt.is-cur').length,
        ariaSel: menu.querySelectorAll('[aria-selected="true"]').length,
        expanded: (document.querySelector('.nf-select:has(#cfg-thinking-effort) .nf-select-trigger') || {}).getAttribute('aria-expanded') };
    })()`);
    const face1 = await page.evaluate(READ_FACE('cfg-thinking-effort'));
    ok('F2 点 trigger ⇒ 选项单开（listbox / fm 材质 / 选项数 = select 项数 / 当前项唯一勾选 + aria-selected / aria-expanded）',
      !!menu1 && menu1.role === 'listbox' && menu1.material && menu1.opts === face1.optCount && menu1.cur === 1 && menu1.ariaSel === 1 && menu1.expanded === 'true',
      JSON.stringify(menu1));
    await page.screenshot({ path: join(OUT, 'desktop-menu-open.png') });

    // F3 点选项 = 落值 + change 冒泡 + 收单 + 脸跟随
    await page.evaluate(() => { window.__fSpy = 0; document.getElementById('cfg-thinking-effort').addEventListener('change', () => { window.__fSpy++; }); });
    const wantText = await page.evaluate(`(() => {
      const sel = document.getElementById('cfg-thinking-effort');
      const opt = [...sel.options].find(o => o.value === 'high');
      const btn = [...document.querySelectorAll('.nf-select-menu .nf-select-opt')].find(b => b.textContent.replace('✓','') === opt.textContent);
      btn.click();
      return opt.textContent;
    })()`);
    await sleep(250);
    const after3 = await page.evaluate(`(() => ({
      value: document.getElementById('cfg-thinking-effort').value,
      spy: window.__fSpy,
      menuGone: !document.querySelector('.nf-select-menu'),
      label: (document.querySelector('.nf-select:has(#cfg-thinking-effort) .nf-select-val') || {}).textContent,
      expanded: (document.querySelector('.nf-select:has(#cfg-thinking-effort) .nf-select-trigger') || {}).getAttribute('aria-expanded'),
    }))()`);
    ok('F3 点选项 ⇒ select.value 落值 + change 冒泡 + 单收 + trigger 脸跟 + aria-expanded 收',
      after3.value === 'high' && after3.spy === 1 && after3.menuGone && after3.label === wantText && after3.expanded === 'false',
      `want=${wantText} after=${JSON.stringify(after3)}`);

    // F4 Esc 收
    await page.locator('.nf-select:has(#cfg-thinking-effort) .nf-select-trigger').click();
    await page.waitForSelector('.nf-select-menu');
    await page.keyboard.press('Escape');
    await sleep(150);
    ok('F4 Esc 收单', await page.evaluate(() => !document.querySelector('.nf-select-menu')), '');

    // F5 点外收
    await page.locator('.nf-select:has(#cfg-thinking-effort) .nf-select-trigger').click();
    await page.waitForSelector('.nf-select-menu');
    await page.locator('#settings-content h2').first().click();
    await sleep(150);
    ok('F5 点外收单', await page.evaluate(() => !document.querySelector('.nf-select-menu')), '');

    // F6 单例：开第二个 = 第一个让位
    await page.locator('.nf-select:has(#cfg-language) .nf-select-trigger').click();
    await page.waitForSelector('.nf-select-menu');
    const single = await page.evaluate(`(() => {
      const menus = document.querySelectorAll('.nf-select-menu');
      const cur = menus[0] && menus[0].querySelector('.nf-select-opt.is-cur');
      const langSel = document.getElementById('cfg-language');
      const langCur = langSel && langSel.selectedOptions[0];
      return { count: menus.length, curText: cur ? cur.textContent.replace('✓','') : null, langCurText: langCur ? langCur.textContent : null };
    })()`);
    ok('F6 单例（全文档恰一单，且服务于新开的 #cfg-language）',
      single.count === 1 && single.curText === single.langCurText, JSON.stringify(single));
    await page.keyboard.press('Escape');

    // F7 Playwright 兼容契约：selectOption 直打原生 select 仍工作且脸跟随（原子读）
    await page.selectOption('#cfg-thinking-effort', 'low');
    await sleep(250);
    const compat = await page.evaluate(`(() => {
      const sel = document.getElementById('cfg-thinking-effort');
      const wrap = sel.closest('.nf-select');
      const label = wrap ? (wrap.querySelector('.nf-select-val') || {}).textContent : null;
      return { value: sel.value, label, optText: sel.selectedOptions[0].textContent };
    })()`);
    ok('F7 selectOption 兼容（原生 select 仍可见可打；值落 + 脸跟随 change）',
      compat.value === 'low' && compat.label === compat.optText,
      JSON.stringify(compat));

    // F8 键盘道：Enter 开 / ArrowDown 巡走 / Enter 选
    await page.evaluate(`document.querySelector('.nf-select:has(#cfg-thinking-effort) .nf-select-trigger').focus()`);
    await page.keyboard.press('Enter');
    await page.waitForSelector('.nf-select-menu');
    const kbOpen = await page.evaluate(() => !!document.querySelector('.nf-select-menu'));
    await page.keyboard.press('ArrowDown');
    await sleep(100);
    await page.keyboard.press('Enter');
    await sleep(250);
    const kbAfter = await page.evaluate(`(() => ({
      menuGone: !document.querySelector('.nf-select-menu'),
      value: document.getElementById('cfg-thinking-effort').value,
      focusOnTrigger: document.activeElement && document.activeElement.classList.contains('nf-select-trigger'),
    }))()`);
    ok('F8 键盘道（Enter 开单 / 箭头巡走 / Enter 选值 / 焦点回 trigger）',
      kbOpen && kbAfter.menuGone && kbAfter.value !== 'low' && kbAfter.focusOnTrigger, JSON.stringify(kbAfter));

    // F9 选项表重建（usageDashboard / searchFace 同款：innerHTML 重灌 + 静默回值）→ 脸跟
    await page.evaluate(`(() => {
      const sel = document.getElementById('cfg-thinking-effort');
      sel.innerHTML = sel.innerHTML; // childList 重建
      sel.value = 'medium';
    })()`);
    await sleep(200);
    const rebuilt = await page.evaluate(`(() => {
      const sel = document.getElementById('cfg-thinking-effort');
      const wrap = sel.closest('.nf-select');
      return { value: sel.value, label: wrap ? (wrap.querySelector('.nf-select-val') || {}).textContent : null, optText: sel.selectedOptions[0].textContent };
    })()`);
    ok('F9 选项表重建 + 静默回值 ⇒ trigger 脸跟（观察器异步腿）',
      rebuilt.value === 'medium' && rebuilt.label === rebuilt.optText,
      JSON.stringify(rebuilt));

    // F10 禁用镜像
    await page.evaluate(`document.getElementById('cfg-thinking-effort').disabled = true`);
    await sleep(150);
    const dis = await page.evaluate(READ_FACE('cfg-thinking-effort'));
    await page.locator('.nf-select:has(#cfg-thinking-effort) .nf-select-trigger').click({ force: true });
    await sleep(150);
    const noMenu = await page.evaluate(() => !document.querySelector('.nf-select-menu'));
    ok('F10 禁用镜像（trigger.disabled 跟随；点不开单）', dis.triggerDisabled === true && noMenu, JSON.stringify({ triggerDisabled: dis.triggerDisabled, noMenu }));
    await page.evaluate(`document.getElementById('cfg-thinking-effort').disabled = false`);

    // F11 optgroup 分组头（微夹具：optGroups select 手动收编）
    const grp = await page.evaluate(async () => {
      const sel = document.createElement('select');
      sel.innerHTML = '<option value="a">甲</option><optgroup label="分组"><option value="b">乙</option></optgroup>';
      document.body.appendChild(sel);
      const { enhanceSelects } = await import('/js/dropdown.js');
      enhanceSelects(sel);
      const trigger = sel.closest('.nf-select').querySelector('.nf-select-trigger');
      trigger.click();
      const menu = document.querySelector('.nf-select-menu');
      const out = { group: menu ? (menu.querySelector('.nf-select-group') || {}).textContent : null, opts: menu ? menu.querySelectorAll('.nf-select-opt').length : 0 };
      if (menu) menu.remove();
      sel.closest('.nf-select').remove();
      return out;
    });
    ok('F11 optgroup 分组头渲染（.nf-select-group + 选项平铺）', grp.group === '分组' && grp.opts === 2, JSON.stringify(grp));

    ok('F-DESKTOP 全程零页面错误', desktopErrors.length === 0, desktopErrors.join(' | ').slice(0, 200));
    await page.close();
  }

  /* ══════ 手机腿：搜索面板 ══════ */
  {
    const { page, errors } = await makePage(browser, { viewport: { width: 375, height: 812 }, deviceScaleFactor: 2, hasTouch: true, isMobile: true });
    mobileErrors = errors;
    await page.waitForFunction(() => document.body.classList.contains('mobile-layer'), null, { timeout: 15000 });
    await sleep(600);
    await page.locator("[data-chrome='search']").click();
    await page.waitForSelector('#search-modal', { timeout: 8000 });
    await sleep(400);

    // 🔴 search-page-density 批（2026-11-21 作者令①「删掉『全部来源』大下拉框」）：
    // 手机搜索面板的收编样本 `#mh-search-kind` **整件退役**（DOM/CSS 双侧零节点）
    // ⇒ 手机腿换宿主：设置页 `#cfg-thinking-effort`（手机层同一个收编器，仍是
    // 「原生 select → 动作表形态选项单」这条链；样本换、能力断言一字不动）。
    // 设置页在手机层是真页面：走 navController 的页面栈推入（与
    // tests/mobile-workbench-account-chat.spec.mjs:115-117 同法），
    // 这样 trigger 才在栈顶页里可点（不是被搜索页压住的死面）。
    await page.evaluate(async () => {
      const nav = await import('/js/mobile/navController.js');
      nav.goHome();
      nav.dockTo('workbench');
      nav.pushPage('settings');
    });
    await page.waitForFunction(() => {
      const sel = document.getElementById('cfg-thinking-effort');
      const trg = sel && sel.closest('.nf-select')?.querySelector('.nf-select-trigger');
      return !!trg && trg.getBoundingClientRect().height > 0;
    }, null, { timeout: 15000 });
    await sleep(400);

    // 退役确认（作者令①的可执行判据，落在本 spec 里防回潮）。
    ok('M0 手机搜索面板角色下拉整件退役（#mh-search-kind / -row 零节点）',
      await page.evaluate(() => !document.getElementById('mh-search-kind') && !document.getElementById('mh-search-kind-row')),
      'chips 三档接管来源筛选（见 tests/search-page-density.spec.mjs M1）');

    const mFace = await page.evaluate(READ_FACE('cfg-thinking-effort'));
    ok('M1 手机层下拉被收编（包裹 / 蒙层透明 + 指针穿透 / trigger 可见）',
      mFace && mFace.wrapped && mFace.opacity === '0' && mFace.pe === 'none' && mFace.triggerVisible,
      JSON.stringify(mFace));

    await page.locator('.nf-select:has(#cfg-thinking-effort) .nf-select-trigger').click();
    await page.waitForSelector('.nf-select-menu', { timeout: 5000 });
    await sleep(250);
    await page.screenshot({ path: join(OUT, 'mobile-menu-open.png') });
    const mPick = await page.evaluate(`(() => {
      const menu = document.querySelector('.nf-select-menu');
      const sheetForm = menu && menu.classList.contains('fm-context-menu');
      const sel = document.getElementById('cfg-thinking-effort');
      const want = [...sel.options].find(o => o.value !== sel.value);
      const opt = menu && [...menu.querySelectorAll('.nf-select-opt')].find(b => {
        const cur = menu.querySelector('.nf-select-opt.is-cur');
        return b !== cur && b.textContent.replace('✓','') === (want ? want.textContent : '');
      });
      if (opt) opt.click();
      return { sheetForm, clicked: !!opt, want: want ? want.value : null };
    })()`);
    await sleep(250);
    const mAfter = await page.evaluate(`(() => ({
      value: document.getElementById('cfg-thinking-effort').value,
      menuGone: !document.querySelector('.nf-select-menu'),
    }))()`);
    ok('M2 手机点选落值（动作表形态 fm-context-menu / 点选 ⇒ 值落 / 单收）',
      mPick.sheetForm && mPick.clicked && mAfter.value === mPick.want && mAfter.menuGone,
      JSON.stringify({ pick: mPick, after: mAfter }));

    // M3 藏在 .search-filters（display:none 行）里的 #search-scope：整行连同
    // 包裹层一起藏——select 与 trigger 都零盒（Playwright toBeHidden 同口径），
    // 既有 isHidden 断言语义不动。
    const scope = await page.evaluate(`(() => {
      const sel = document.getElementById('search-scope');
      if (!sel) return { absent: true };
      const wrap = sel.closest('.nf-select');
      const trigger = wrap && wrap.querySelector('.nf-select-trigger');
      return {
        filtersRow: getComputedStyle(document.querySelector('#search-modal .search-filters')).display,
        selZeroBox: sel.getClientRects().length === 0,
        triggerZeroBox: !trigger || trigger.getClientRects().length === 0,
      };
    })()`);
    ok('M3 手机层整行藏住的 #search-scope：select 与 trigger 都零盒（isHidden 语义不动）',
      scope.filtersRow === 'none' && scope.selZeroBox && scope.triggerZeroBox, JSON.stringify(scope));

    ok('F-MOBILE 全程零页面错误', mobileErrors.length === 0, mobileErrors.join(' | ').slice(0, 200));
    await page.close();
  }
} catch (err) {
  ok('SPEC-ABORT', false, String(err && err.stack ? err.stack.split('\n').slice(0, 4).join(' | ') : err));
} finally {
  await browser.close();
}

const summary = `\n合计 ${pass} PASS / ${fail} FAIL`;
console.log(summary);
lines.push(summary);
writeFileSync(join(OUT, 'spec-output.txt'), lines.join('\n') + '\n');
process.exit(fail > 0 ? 1 : 0);

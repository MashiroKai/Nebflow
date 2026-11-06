#!/usr/bin/env node
// shot-settings-ios-rows.cjs — 设置页「iOS 设置行」批的真渲染验收 + DOM 断言
// （settings-ios-rows 批 2026-11-06；作者令附实拍 IMG_1764 现状 / IMG_1765 iOS 对照）。
//
// 用法: node scripts/shot-settings-ios-rows.cjs <base> <token> <outdir> [webRoot]
//   webRoot 给定时：本树未落地的静态文件（/js/** /css/** /index.html /vendor/**）
//   由本地 webRoot 树直供（Playwright route 兜底），/api 与 WS 照走真实例 ——
//   「改前树」/「改后树」共用同一活引擎，读数同源可比。
//
// 矩阵: chromium × {375×812 手机层, 1440×900 桌面} × {light, dark} 四格 × 改前/改后。
//
// 断言（任一失败 → 该格 exit 1；--json 出机器读数）:
//   A1 重启服务零痕: 页面文本零「重启服务」，#btn-restart-gateway / #restart-status 不存在
//   A2 可点进行唯一 + chevron: .set-nav ≥ 4 且每个都含 .set-nav-chev svg；
//      旧壳（.secrets-entry / .settings-collapse-toggle）DOM 数 = 0
//   A3 计数只说一遍: 密钥行 .set-nav-val 恰 1 个、无 .count-pill；副值含「个密钥」时
//      行内该数字只出现一次
//   A4 按钮规整: #btn-toggle-json / #btn-clear-nf-cache（#btn-rerun-onboarding 在场时
//      一并）高度全等；清空钮右缘 ≈ 密钥行 chevron 盒左缘（±1.5px）；副值右缘 ≈
//      chevron 盒左缘（±1.5px）
//   A5 行高网格: .set-nav 行高 ≥ 44（手机 ≥ 50）
//   A6 手机题不逃逸（仅 375 格）: 每个 .set-card-title 的盒顶 ≥ #settings-content
//      滚动容器的盒顶（负外边距逃逸 = 红，作者实拍「标题被截 + 上下压字」根因）
//   A7 零 pageerror（作用窗内）

const { chromium } = require('playwright');
const { mkdirSync, writeFileSync, readFileSync, existsSync } = require('node:fs');
const { join, resolve } = require('node:path');

const BASE = process.argv[2] || 'http://127.0.0.1:6138';
const TOKEN = process.argv[3] || '';
const OUT_DIR = resolve(process.argv[4] || '.nebflow/shots-settings-ios-rows');
const WEB_ROOT = process.argv[5] ? resolve(process.argv[5]) : null;
const TAG = process.argv[6] || (WEB_ROOT ? 'custom' : 'live');

mkdirSync(OUT_DIR, { recursive: true });

const STATIC_PREFIXES = ['/js/', '/css/', '/vendor/', '/index.html'];

async function shoot(browser, vp, scheme, mobile) {
  const label = `${TAG}-${vp.width}-${scheme}`;
  const page = await browser.newPage({ viewport: { width: vp.width, height: vp.height }, colorScheme: scheme, deviceScaleFactor: 2 });
  const errors = [];
  let recording = false;
  const wsNoise = (s) => /ws:\/\/[^/]+\/ws|WebSocket connection/i.test(s);
  page.on('pageerror', (e) => { if (recording && !wsNoise(String(e))) errors.push(`pageerror: ${e}`); });
  // 网络控制台噪声（fresh 实例的 agents/plugins 403 探测、无关资源 404）不属本批
  // 面；pageerror 与 js/css 自身的加载失败仍硬记。
  page.on('console', (m) => {
    if (!recording || m.type() !== 'error' || wsNoise(m.text())) return;
    const t = m.text();
    if (/Failed to load resource/.test(t)) {
      const loc = m.location ? m.location().url : '';
      if (!/(^|(\/))(js|css)\//.test(loc)) return;
    }
    // nf-reach 环回探测的 CORS 噪声（localhost vs 127.0.0.1 地址空间）= 既有健康
    // 探测面，与本批无关。
    if (/nf-reach/.test(t)) return;
    errors.push(`console: ${t}`);
  });

  if (WEB_ROOT) {
    // 静态文件改走本地树（改前/改后同一引擎下的对照读数）。活引擎可能供给
    // classpath/disk dist（解析序里 dist 优先于 dev 树，且 lazy 一次定格），dist
    // 的 index.html 不含新 <link> ⇒ '/' 也必须改写成本地 index.html。
    const MIME = { '.js': 'text/javascript', '.mjs': 'text/javascript', '.css': 'text/css', '.html': 'text/html', '.png': 'image/png', '.jpg': 'image/jpeg', '.svg': 'image/svg+xml', '.woff2': 'font/woff2', '.json': 'application/json' };
    await page.route(`${BASE}/**`, (route) => {
      const u = new URL(route.request().url());
      let p = decodeURIComponent(u.pathname);
      if (p === '/' || p === '') p = '/index.html';
      const hit = STATIC_PREFIXES.some((pre) => p === pre || p.startsWith(pre));
      if (!hit) return route.continue();
      const f = join(WEB_ROOT, p.replace(/^\//, ''));
      if (existsSync(f)) {
        const ext = f.slice(f.lastIndexOf('.'));
        return route.fulfill({ status: 200, contentType: MIME[ext] || 'application/octet-stream', body: readFileSync(f) });
      }
      return route.continue();
    });
  }

  await page.addInitScript(() => { localStorage.setItem('stand_locale', 'zh-CN'); });
  await page.addInitScript((t) => { localStorage.setItem('stand_token', t); }, TOKEN);
  await page.goto(`${BASE}/`, { waitUntil: 'domcontentloaded', timeout: 30000 });
  await page.waitForFunction(() => document.querySelector('#settings-overlay'), null, { timeout: 30000 });
  recording = true;

  await page.evaluate(async () => {
    const { openSettingsPanel } = await import('/js/sidebar.js');
    openSettingsPanel();
  });
  await page.waitForSelector('.set-nav, .secrets-entry', { timeout: 20000 });
  // 等密钥计数读数落位（refreshSecretsEntryCount 异步）
  // 等密钥计数读数落位（refreshSecretsEntryCount 异步；STT 副值先到不算数）
  await page.waitForFunction(() => {
    const el = document.querySelector('#btn-open-secrets .set-nav-val, #btn-open-secrets .entry-sub');
    return el && el.textContent && !el.textContent.includes('…');
  }, null, { timeout: 15000 }).catch(() => {});
  // 探针环境的既有噪声：无会话 cookie 的 WS 握手触发 showTicketEntry →
  // activateAccountEntry ⇒ boot 稍后弹「个人主页/退出登录」账号菜单（真实浏览器
  // 带 cookie 不复现）。在取证前把它关掉（隐藏后再等一拍确认不再出现）。
  for (let i = 0; i < 6; i++) {
    const still = await page.evaluate(() => !!document.querySelector('.fm-context-menu'));
    if (!still) break;
    await page.evaluate(async () => {
      try { const m = await import('/js/contextMenu.js'); m.hidePopupMenu(); } catch { /* noop */ }
    });
    await page.waitForTimeout(500);
  }
  await page.waitForTimeout(600);

  const readouts = await page.evaluate(() => {
    const $ = (s) => document.querySelector(s);
    const $$ = (s) => Array.from(document.querySelectorAll(s));
    const box = (el) => { const r = el.getBoundingClientRect(); return { x: r.x, y: r.y, w: r.width, h: r.height, right: r.right, bottom: r.bottom }; };
    const out = { title: document.title };
    const navs = $$('.set-nav');
    out.navCount = navs.length;
    out.navsWithChevron = navs.filter((n) => n.querySelector('.set-nav-chev svg')).length;
    out.oldShells = $$('.secrets-entry, .settings-collapse-toggle, .settings-section, .settings-row').length;
    out.restartBtn = !!document.getElementById('btn-restart-gateway');
    out.restartStatusEl = !!document.getElementById('restart-status');
    out.restartText = document.getElementById('settings-content').textContent.includes('重启服务');

    const sub = $('#btn-open-secrets .set-nav-val');
    out.subText = sub ? sub.textContent : null;
    out.secretsValCount = $$('#btn-open-secrets .set-nav-val').length;
    out.countPill = $$('.count-pill').length;

    const chev = $('#btn-open-secrets .set-nav-chev');
    const chevBox = chev ? box(chev) : null;
    out.chevronBox = chevBox;
    if (sub && chevBox) out.subRightDelta = Math.abs(box(sub).right - chevBox.x);

    const btns = ['btn-toggle-json', 'btn-rerun-onboarding', 'btn-clear-nf-cache']
      .map((id) => document.getElementById(id)).filter(Boolean);
    out.btnHeights = btns.map((b) => Math.round(box(b).h * 10) / 10);
    out.btnHeightsEqual = out.btnHeights.length < 2 || new Set(out.btnHeights).size === 1;
    const cacheBtn = document.getElementById('btn-clear-nf-cache');
    if (cacheBtn && chevBox) out.cacheBtnRightDelta = Math.abs(box(cacheBtn).right - chevBox.x);

    out.navHeights = navs.slice(0, 8).map((n) => Math.round(box(n).h * 10) / 10);
    out.minNavH = Math.min(...out.navHeights);

    const sc = $('#settings-content');
    const scBox = box(sc);
    out.scTop = scBox.y;
    out.titleEscapes = $$('.set-card-title').map((t) => {
      const b = box(t);
      return { text: t.textContent.slice(0, 10), top: Math.round(b.y * 10) / 10, escapes: b.y < scBox.y - 0.5 };
    });

    const secretsNav = navs.find((n) => n.id === 'btn-open-secrets');
    out.secretsRowH = secretsNav ? Math.round(box(secretsNav).h * 10) / 10 : null;
    // 密钥行内数字唯一性：副值里的数字在整行文本中只出现一次
    if (secretsNav && sub) {
      const m = sub.textContent.match(/\d+/);
      out.secretNumberOnce = !m || (secretsNav.textContent.match(new RegExp(m[0], 'g')) || []).length === 1;
    }
    return out;
  });

  readouts.errors = errors;
  const shot = join(OUT_DIR, `${label}.png`);
  await page.screenshot({ path: shot, fullPage: false });

  // 手机层加拍：滚动一格看组题是否被壳层标题截断（对照实拍病灶）
  let scrollShot = null;
  if (mobile) {
    await page.evaluate(() => { const el = document.getElementById('settings-content'); el.scrollTop = 160; });
    await page.waitForTimeout(350);
    scrollShot = join(OUT_DIR, `${label}-scrolled.png`);
    await page.screenshot({ path: scrollShot, fullPage: false });
  }

  const hardFails = [];
  if (readouts.restartBtn || readouts.restartStatusEl || readouts.restartText) hardFails.push('A1 重启服务残留');
  if (readouts.navCount < 4 || readouts.navsWithChevron !== readouts.navCount) hardFails.push('A2 行族/chevron 不齐');
  if (readouts.oldShells > 0) hardFails.push(`A2 旧壳 DOM ${readouts.oldShells}`);
  if (readouts.secretsValCount !== 1 || readouts.countPill > 0 || readouts.secretNumberOnce === false) hardFails.push('A3 计数重复');
  if (!readouts.btnHeightsEqual) hardFails.push('A4 按钮高度不齐');
  if (readouts.subRightDelta != null && readouts.subRightDelta > 1.5) hardFails.push(`A4 副值右缘偏 ${readouts.subRightDelta}`);
  if (readouts.cacheBtnRightDelta != null && readouts.cacheBtnRightDelta > 1.5) hardFails.push(`A4 清空钮右缘偏 ${readouts.cacheBtnRightDelta}`);
  const minH = mobile ? 50 : 44;
  if (readouts.minNavH < minH) hardFails.push(`A5 行高 ${readouts.minNavH} < ${minH}`);
  if (mobile && readouts.titleEscapes.some((t) => t.escapes)) hardFails.push('A6 组题负边距逃逸');
  if (errors.length) hardFails.push(`A7 pageerror/console ${errors.length}`);

  const result = { label, shot, scrollShot, readouts, hardFails, ok: hardFails.length === 0 };
  writeFileSync(join(OUT_DIR, `${label}.json`), JSON.stringify(result, null, 2));
  console.log(`${result.ok ? 'PASS' : 'FAIL'} ${label}${hardFails.length ? ' — ' + hardFails.join(' | ') : ''}  (nav=${readouts.navCount} chev=${readouts.navsWithChevron} navH=${readouts.minNavH} btnH=[${readouts.btnHeights}] subΔ=${readouts.subRightDelta == null ? '-' : Math.round(readouts.subRightDelta * 10) / 10} cacheΔ=${readouts.cacheBtnRightDelta == null ? '-' : Math.round(readouts.cacheBtnRightDelta * 10) / 10} sub="${readouts.subText}")`);
  await page.close();
  return result.ok;
}

(async () => {
  const browser = await chromium.launch();
  let allOk = true;
  for (const scheme of ['light', 'dark']) {
    for (const [vp, mobile] of [[{ width: 375, height: 812 }, true], [{ width: 1440, height: 900 }, false]]) {
      allOk = (await shoot(browser, vp, scheme, mobile)) && allOk;
    }
  }
  await browser.close();
  process.exit(allOk ? 0 : 1);
})().catch((e) => { console.error(e); process.exit(2); });

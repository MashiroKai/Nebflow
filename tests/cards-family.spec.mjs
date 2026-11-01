// cards-family.spec.mjs — 卡片家族统一样式包验收（2026-11-01 作者 16:05 令）。
//
// 基准 = stand 官网首次启动过渡卡（auth/link 域实色面卡）。家族包 = css/cards.css
// （token --nfc-* 三腿主题解析 + .nfc 族类）；设计稿 =
// docs/design/20261101_162000_cards-family-design__delegate-nebflow-67f23d42.zh-CN.md
// （§1 考古四证据源 / §2 token 出处表 / §6 验收点——本 spec 即 §6 的自动化）。
//
// 契约钉（全部二值）：
//   C1  token 三腿：亮档默认 / html[data-theme=dark] 强制档（亮系统偏好下仍暗）/
//       prefers-color-scheme 回退档（无 data-theme 时）；
//   C2  弹窗卡 #takeover-box（家族 DNA：bg/border/radius/width/shadow/字标/字阶）；
//   C3  加载卡 .nf-entry-card（同 DNA + 步骤清单四态机制 + .nfc-step 行类双挂）；
//   C4  失败卡 .nf-boot-card（内联手抄档与家族值逐值一致——零请求纪律）；
//   C5  家族变体探针（.nfc-link 下划线动作 / .nfc-error S9 行 / .nfc-actions.is-center
//       / .nfc-steps + .nfc-step）——挂真实页面断 computed；
//   C6  双主题 × Chromium+WebKit 双引擎样张存证；全程零 pageerror / 零 console error。
//
// Run: node tests/cards-family.spec.mjs（自包含：静态服务器随机隔离端口，finally 必关）
import { chromium, webkit } from 'playwright-core';
import { createServer } from 'node:http';
import { readFile, mkdir } from 'node:fs/promises';
import { join, dirname, extname, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..');
const WEB = join(ROOT, 'src', 'main', 'resources', 'web');
const SHOTS = join(ROOT, 'evidence', '20261101_cards-family__delegate-nebflow-67f23d42', 'after');

let failures = 0;
function ok(name, cond, extra = '') {
  console.log(`${cond ? 'PASS' : 'FAIL'}  ${name}${extra ? '  — ' + extra : ''}`);
  if (!cond) failures++;
}
const sleep = ms => new Promise(r => setTimeout(r, ms));
const MIME = { '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.svg': 'image/svg+xml', '.png': 'image/png', '.json': 'application/json', '.woff2': 'font/woff2' };

// ── 静态服务器（failOne 参数 = 故意断掉一张表，供 C4 失败脸）───────────────
const failOne = { file: null };
const server = createServer(async (req, res) => {
  try {
    const path = decodeURIComponent(new URL(req.url, 'http://x').pathname);
    const file = normalize(join(WEB, path === '/' ? 'index.html' : path));
    if (failOne.file && file.endsWith(failOne.file)) { res.destroy(); return; }
    const body = await readFile(file);
    res.writeHead(200, { 'Content-Type': MIME[extname(file)] || 'application/octet-stream' });
    res.end(body);
  } catch {
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end('{}');
  }
});
await new Promise(r => server.listen(0, '127.0.0.1', r));
const BASE = `http://127.0.0.1:${server.address().port}`;
await mkdir(SHOTS, { recursive: true });

const INIT = () => {
  localStorage.setItem('nebflow_token', 't');
  localStorage.setItem('nebflow_locale', 'zh-CN');
  localStorage.setItem('stand_input_history', '[]');
};
// ── 桩 WS（boot 需要的最小帧；holdConfig = 入场卡观察窗扣住 configData）──────
const stub = { holdConfig: true, releaseConfig: false };
function wireUp(ws) {
  ws.onMessage(raw => {
    let m; try { m = JSON.parse(raw); } catch { return; }
    if (m.type === 'ping') { ws.send(JSON.stringify({ type: 'pong' })); return; }
    if (m.type === 'getHistory') {
      ws.send(JSON.stringify({ type: 'historyPage', sessionId: m.sessionId, messages: [], hasMore: false, offset: 0, total: 0 }));
      return;
    }
  });
  ws.send(JSON.stringify({ type: 'sessionList', sessions: [{ id: 'root-local-session', name: '本机会话' }], activeId: 'root-local-session', folders: [] }));
  const pushConfig = () => ws.send(JSON.stringify({ type: 'configData', config: '{"features":{}}', configured: true, onboarding: 'done', models: [], defaults: {} }));
  if (!stub.holdConfig) { pushConfig(); return; }
  const iv = setInterval(() => { if (stub.releaseConfig) { clearInterval(iv); pushConfig(); } }, 100);
}
/** 推 takeover 帧给当前连接（ConsoleTakeover.takeoverFrame 同形）。 */
let currentWs = null;
const pushTakeover = () => currentWs && currentWs.send(JSON.stringify({ type: 'takeover', at: Date.now() }));

function watchErrors(page, sink) {
  page.on('pageerror', e => sink.push('pageerror: ' + e.message));
  page.on('console', m => { if (m.type() === 'error') sink.push('console: ' + m.text()); });
}

async function openConsole(browser, { engine, colorScheme = 'light', theme = null, holdEntry = true } = {}) {
  const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 }, colorScheme });
  await ctx.addInitScript(INIT);
  const page = await ctx.newPage();
  const errs = [];
  watchErrors(page, errs);
  await page.route('**/api/**', r => r.fulfill({ json: {} }));
  await page.routeWebSocket(/\/ws/, ws => { currentWs = ws; wireUp(ws); });
  await page.goto(BASE + '/index.html');
  if (theme) {
    // 主题强制腿：boot 后由应用写 html[data-theme]；本 spec 在门脚本运行前
    // 抢注属性不可行（no-flash 引导在网站面）——控制台面由 initScript 在
    // documentElement 上直置，验证 token 的 data-theme 腿解析。
    await page.evaluate(t => { document.documentElement.dataset.theme = t; }, theme);
  }
  if (holdEntry) {
    // 入场卡观察窗：host done 而 data 扣住 ⇒ 卡面稳定在场
    await page.waitForSelector('#nf-boot-gate .nf-entry-item', { timeout: 20000 });
    await (async () => {
      const t0 = Date.now();
      for (;;) {
        const st = await page.evaluate(() => window.__nfEntryState || null).catch(() => null);
        if (st && st.host === 'done' && st.data !== 'done') return;
        if (Date.now() - t0 > 12000) return;
        await sleep(80);
      }
    })();
  } else {
    await page.waitForSelector('#input', { state: 'visible', timeout: 20000 });
    await sleep(800);
  }
  return { ctx, page, errs };
}

// ── 家族探针（C5）：全部家族类挂真实页面（变体全集一卡看全）────────────────
const PROBE = `
<div id="nfc-probe" style="position:fixed;inset:0;display:flex;gap:40px;align-items:center;justify-content:center;background:var(--nfc-page);z-index:50000;">
  <div class="nfc nfc-hint">
    <div class="nfc-title">主机暂时不在线</div>
    <div class="nfc-body">页面每 5 秒自动检测一次，检测到在线后会自动进入控制界面。</div>
    <button class="nfc-link" type="button">账号设置</button>
  </div>
  <div class="nfc nfc-offline">
    <img class="nfc-lockup nfc-lockup-light" src="/brand/stand/lockup-light.png" alt="STAND">
    <img class="nfc-lockup nfc-lockup-dark" src="/brand/stand/lockup-dark.png" alt="" aria-hidden="true">
    <div class="nfc-title">连接已断开</div>
    <div class="nfc-body">与主机的连接中断，正在自动重连。</div>
    <div class="nfc-error" role="alert">重连失败：主机拒绝了连接</div>
    <div class="nfc-actions is-center">
      <button class="g2-pill" type="button">重试</button>
    </div>
  </div>
  <div class="nfc nfc-loading">
    <div class="nfc-title">正在检查主机…</div>
    <div class="nfc-steps">
      <div class="nfc-step">样式表</div>
      <div class="nfc-step">界面模块</div>
      <div class="nfc-step">连接主机</div>
      <div class="nfc-step">同步数据</div>
    </div>
  </div>
</div>`;

async function withProbe(page) {
  await page.evaluate(html => {
    document.body.insertAdjacentHTML('beforeend', html);
  }, PROBE);
  await sleep(120);
  return page.locator('#nfc-probe');
}

// ════════════════════════════════════════════════════════════════════════
for (const engine of ['chromium', 'webkit']) {
  const browserType = engine === 'chromium' ? chromium : webkit;
  const browser = await browserType.launch();
  try {
    console.log(`\n━━ ${engine} ━━`);

    // ── C1+C2: 弹窗卡（亮档默认腿）────────────────────────────────────────
    {
      const { ctx, page, errs } = await openConsole(browser, { engine, holdEntry: false });
      pushTakeover();
      await sleep(400);
      const box = page.locator('#takeover-box');
      ok(`C2 ${engine} 弹窗卡在场`, await box.isVisible());
      const card = await box.evaluate(el => {
        const s = getComputedStyle(el);
        return {
          bg: s.backgroundColor, radius: s.borderRadius, width: s.width,
          border: `${s.borderWidth} ${s.borderColor}`, shadow: s.boxShadow,
          pad: s.padding, align: getComputedStyle(el).alignItems,
        };
      });
      ok(`C2 ${engine} 家族 DNA（bg #fff / radius 18 / width 384 / 边 #e5e7eb）`,
        card.bg === 'rgb(255, 255, 255)' && card.radius === '18px' && card.width === '384px'
        && card.border === '1px rgb(229, 231, 235)', JSON.stringify(card));
      ok(`C2 ${engine} 家族影亮档（两档官方外影、无 inset）`,
        card.shadow.includes('rgba(17, 24, 39, 0.06)') && !card.shadow.includes('inset'), card.shadow.slice(0, 140));
      ok(`C2 ${engine} 家族内距（32/32/40 栅格档）`, card.pad === '32px 32px 40px', card.pad);
      ok(`C2 ${engine} 字标在场（亮档 lockup 可见）`, await box.locator('.nfc-lockup-light').isVisible());
      const lockupH = await box.locator('.nfc-lockup-light').evaluate(el => getComputedStyle(el).height);
      ok(`C2 ${engine} 字标 24px 档`, lockupH === '24px', lockupH);
      const title = await page.locator('#takeover-title').evaluate(el => {
        const s = getComputedStyle(el);
        return `${s.fontSize}/${s.fontWeight}/${s.color}`;
      });
      ok(`C2 ${engine} 家族标题（21px/600/#111827）`, title === '21px/600/rgb(17, 24, 39)', title);
      const msg = await page.locator('#takeover-msg').evaluate(el => {
        const s = getComputedStyle(el);
        return `${s.fontSize}/${s.color}`;
      });
      ok(`C2 ${engine} 家族正文（14px/#9ca3af）`, msg === '14px/rgb(156, 163, 175)', msg);
      const actions = await page.locator('#takeover-actions').evaluate(el => getComputedStyle(el).justifyContent);
      ok(`C2 ${engine} 动作行右对齐（双按钮行归位）`, actions === 'flex-end', actions);
      await page.screenshot({ path: join(SHOTS, `family-dialog-light-${engine}.png`) });
      ok(`C2 ${engine} 零页面/console 异常（弹窗卡）`, errs.length === 0, errs.join(' | ').slice(0, 160));
      await ctx.close();
    }

    // ── C1 强制暗腿 + C2-dark: data-theme=dark 在亮系统偏好下仍解析暗档 ────
    {
      const { ctx, page, errs } = await openConsole(browser, { engine, colorScheme: 'light', theme: 'dark', holdEntry: false });
      pushTakeover();
      await sleep(400);
      const card = await page.locator('#takeover-box').evaluate(el => {
        const s = getComputedStyle(el);
        return { bg: s.backgroundColor, border: `${s.borderWidth} ${s.borderColor}`, shadow: s.boxShadow };
      });
      ok(`C1 ${engine} 强制暗腿（data-theme=dark ⇒ bg #161b22 + 边 #2d333b）`,
        card.bg === 'rgb(22, 27, 34)' && card.border === '1px rgb(45, 51, 59)', JSON.stringify(card));
      ok(`C1 ${engine} 强制暗腿阴影（官方 panel-shadow 逐值）`,
        card.shadow.includes('rgba(0, 0, 0, 0.5)') && card.shadow.includes('rgba(0, 0, 0, 0.35)'), card.shadow.slice(0, 140));
      ok(`C1 ${engine} 暗字标切换`, await page.locator('#takeover-box .nfc-lockup-dark').isVisible()
        && !(await page.locator('#takeover-box .nfc-lockup-light').isVisible()));
      const title = await page.locator('#takeover-title').evaluate(el => getComputedStyle(el).color);
      ok(`C1 ${engine} 强制暗腿标题（#e6edf3）`, title === 'rgb(230, 237, 243)', title);
      await page.screenshot({ path: join(SHOTS, `family-dialog-dark-forced-${engine}.png`) });
      ok(`C1 ${engine} 零异常（强制暗腿）`, errs.length === 0, errs.join(' | ').slice(0, 160));
      await ctx.close();
    }

    // ── C1 回退腿 + C3: 系统暗档（无 data-theme）下的加载卡 ─────────────────
    {
      const { ctx, page, errs } = await openConsole(browser, { engine, colorScheme: 'dark', holdEntry: true });
      const card = await page.locator('.nf-entry-card').evaluate(el => {
        const s = getComputedStyle(el);
        return { bg: s.backgroundColor, border: `${s.borderWidth} ${s.borderColor}`, width: s.width, radius: s.borderRadius };
      });
      ok(`C3 ${engine} 加载卡家族 DNA 暗档（回退腿：bg #161b22 + 边 #2d333b + 384/18）`,
        card.bg === 'rgb(22, 27, 34)' && card.border === '1px rgb(45, 51, 59)'
        && card.width === '384px' && card.radius === '18px', JSON.stringify(card));
      ok(`C3 ${engine} 加载卡字标在场`, await page.locator('.nf-entry-card .nfc-lockup-dark').isVisible());
      await sleep(400); // 行色 transition 0.2s 过完再采样（activity 翻面瞬间为过渡中值）
      const rows = await page.locator('.nf-entry-card .nf-entry-item').count();
      const stepCls = await page.locator('.nf-entry-card .nf-entry-item').first().evaluate(el => el.className);
      ok(`C3 ${engine} 步骤清单四行 + 行类双挂（.nf-entry-item.nfc-step）`,
        rows === 4 && stepCls.includes('nfc-step'), `${rows} rows, cls=${stepCls}`);
      const rowColor = await page.locator('.nf-entry-card .nf-entry-item[data-state="active"]').evaluate(el => getComputedStyle(el).color);
      ok(`C3 ${engine} 活动行字色 = 家族 heading`, rowColor === 'rgb(230, 237, 243)', rowColor);
      await page.screenshot({ path: join(SHOTS, `family-loading-dark-fallback-${engine}.png`) });
      ok(`C3 ${engine} 零异常（加载卡暗档）`, errs.length === 0, errs.join(' | ').slice(0, 160));
      await ctx.close();
    }

    // ── C3 加载卡亮档（样张）───────────────────────────────────────────────
    {
      const { ctx, page, errs } = await openConsole(browser, { engine, colorScheme: 'light', holdEntry: true });
      const card = await page.locator('.nf-entry-card').evaluate(el => {
        const s = getComputedStyle(el);
        return { bg: s.backgroundColor, width: s.width };
      });
      ok(`C3 ${engine} 加载卡亮档（bg #fff / width 384）`,
        card.bg === 'rgb(255, 255, 255)' && card.width === '384px', JSON.stringify(card));
      const gateBg = await page.locator('#nf-boot-gate').evaluate(el => getComputedStyle(el).backgroundColor);
      ok(`C3 ${engine} 门整面页底 = 官方 page 档（#f9fafb）`, gateBg === 'rgb(249, 250, 251)', gateBg);
      await page.screenshot({ path: join(SHOTS, `family-loading-light-${engine}.png`) });
      ok(`C3 ${engine} 零异常（加载卡亮档）`, errs.length === 0, errs.join(' | ').slice(0, 160));
      await ctx.close();
    }

    // ── C4: 失败脸（断一张 CSS 表 ⇒ failedHard ⇒ 内联失败卡）────────────────
    {
      failOne.file = 'voice.css'; // 任一非家族表即可触发失败面（选低耦合表）
      const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 }, colorScheme: 'light' });
      await ctx.addInitScript(INIT);
      const page = await ctx.newPage();
      const errs = [];
      watchErrors(page, errs);
      await page.route('**/api/**', r => r.fulfill({ json: {} }));
      await page.goto(BASE + '/index.html');
      await page.waitForSelector('#nf-boot-gate[data-state="failed"]', { timeout: 20000 });
      await sleep(300);
      const failCard = await page.locator('.nf-boot-card').evaluate(el => {
        const s = getComputedStyle(el);
        return {
          bg: s.backgroundColor, radius: s.borderRadius, width: s.width,
          border: `${s.borderWidth} ${s.borderColor}`,
          titleSize: getComputedStyle(el.querySelector('.nf-boot-card-title')).fontSize,
          titleColor: getComputedStyle(el.querySelector('.nf-boot-card-title')).color,
          hintColor: getComputedStyle(el.querySelector('.nf-boot-card-hint')).color,
        };
      });
      ok(`C4 ${engine} 失败卡 = 家族值内联手抄（bg #fff/radius 18/width 384/边 #e5e7eb）`,
        failCard.bg === 'rgb(255, 255, 255)' && failCard.radius === '18px' && failCard.width === '384px'
        && failCard.border === '1px rgb(229, 231, 235)', JSON.stringify(failCard));
      ok(`C4 ${engine} 失败卡字阶 = 家族档（标题 21px #111827 / hint #9ca3af）`,
        failCard.titleSize === '21px' && failCard.titleColor === 'rgb(17, 24, 39)'
        && failCard.hintColor === 'rgb(156, 163, 175)', JSON.stringify(failCard));
      await page.screenshot({ path: join(SHOTS, `family-fail-light-${engine}.png`) });
      await ctx.close();
      failOne.file = null;
    }

    // ── C5: 家族变体探针（link/error/actions/steps）亮暗两档 ─────────────────
    for (const scheme of ['light', 'dark']) {
      const { ctx, page, errs } = await openConsole(browser, { engine, colorScheme: scheme, holdEntry: false });
      const probe = await withProbe(page);
      ok(`C5 ${engine}/${scheme} 探针三卡在场`, (await probe.locator('.nfc').count()) === 3);
      const link = await probe.locator('.nfc-hint .nfc-link').evaluate(el => {
        const s = getComputedStyle(el);
        return {
          color: s.color,
          line: s.textDecorationLine,
          deco: s.textDecorationColor || s.textDecorationLineColor,
          size: s.fontSize,
        };
      });
      // 期望随主题腿：亮 #6b7280/#e5e7eb，暗 #98a0aa/#2d333b（官网 auth 域双档）。
      const linkExpect = scheme === 'light'
        ? { color: 'rgb(107, 114, 128)', deco: 'rgb(229, 231, 235)' }
        : { color: 'rgb(152, 160, 170)', deco: 'rgb(45, 51, 59)' };
      ok(`C5 ${engine}/${scheme} 极简文字动作（下划线 + auth-link 双档）`,
        link.color === linkExpect.color && link.deco === linkExpect.deco
        && link.line === 'underline' && link.size === '14px', JSON.stringify(link));
      const err = await probe.locator('.nfc-offline .nfc-error').evaluate(el => {
        const s = getComputedStyle(el);
        return { bl: s.borderLeftWidth, blc: s.borderLeftColor, align: s.textAlign };
      });
      ok(`C5 ${engine}/${scheme} 错误行 S9 族（红左缘 2px + 左对齐）`,
        err.bl === '2px' && err.align === 'left', JSON.stringify(err));
      const center = await probe.locator('.nfc-offline .nfc-actions').evaluate(el => getComputedStyle(el).justifyContent);
      ok(`C5 ${engine}/${scheme} 居中动作行变体（.is-center）`, center === 'center', center);
      const step = await probe.locator('.nfc-loading .nfc-step').first().evaluate(el => {
        const s = getComputedStyle(el);
        return `${s.fontSize}/${s.color}`;
      });
      const stepExpect = scheme === 'light' ? '14px/rgb(156, 163, 175)' : '14px/rgb(110, 118, 129)';
      ok(`C5 ${engine}/${scheme} 步骤行 = 家族正文档`, step === stepExpect, step);
      const pageBg = await probe.evaluate(el => getComputedStyle(el).backgroundColor);
      ok(`C5 ${engine}/${scheme} 页底 token（--nfc-page ${scheme === 'light' ? '#f9fafb' : '#0d1117'}）`,
        pageBg === (scheme === 'light' ? 'rgb(249, 250, 251)' : 'rgb(13, 17, 23)'), pageBg);
      await page.screenshot({ path: join(SHOTS, `family-variants-${scheme}-${engine}.png`) });
      ok(`C5 ${engine}/${scheme} 零异常（探针）`, errs.length === 0, errs.join(' | ').slice(0, 160));
      await ctx.close();
    }
  } finally {
    await browser.close().catch(() => {});
  }
}

await new Promise(resolve => server.close(() => resolve()));
console.log(failures === 0 ? '\nALL PASS' : `\n${failures} FAILURE(S)`);
process.exit(failures === 0 ? 0 : 1);

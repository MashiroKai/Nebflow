// composer-mobile-slim-shots.mjs — 手机输入卡垂直收窄批（2026-11-01 作者令「收窄
// 输入框的上下高度，给内容腾显示空间」）前后对照证据 + 几何契约回归。
//
// 上批（2026-10-31 composer-slim）已把桌面/手机共用件收到 6px 档；本批按令
// **手机优先、桌面零触碰**，只动 body.mobile-layer 两点：
//   ① #input-bar 垂直内 padding 6→4px（横向 12px 不动）；
//   ② #mic-orb-wrap 上下对称负边距 -8px —— 光球 64px 画布的**布局足印**收到
//      48px（球面可见径 ~40px 与 WebGL 渲染缓冲零变化 = 球体零缩放零模糊，
//      只是布局盒与条解耦）；条高 78 → 58px（实测基线 78 = 64 光球 + 12
//      padding + 2 border），省 20px 给消息列。
// 多行自动长高（textarea scrollHeight 增长逻辑）与 200px 封顶零触碰。
//
// 本脚本（形制同 tests/composer-slim-shots.mjs）：真 web 树 + 真 WS 桩，
// 手机 375×812（作者令验收视口）× 亮暗双主题 × rest/multiline/attach 三态
// 整视口截图 + 几何探针；桌面 1280×860 零触碰腿（padding/条高/光球边距钉
// 6px/76px/0px 修前原值）。断言面：
//   M1 手机层激活 + 发送钮完整可见（rest / multiline / attach 三态）
//   M2 垂直居中：发送/附件钮与光球画布中心 = 条中心（|Δ| ≤ 1.5px）
//   M3 触控热区下限：发送/附件 ≥ 36px（现值不缩）、光球按钮 ≥ 44px
//   M4 多行增长：5 行后 textarea offsetHeight +80 以上（增长逻辑未动）
//   M5 附件条零遮挡：chip 条带底缘 ≤ textarea 顶缘（B12 红线语义）
//   G[TAG=before] 修前态钉：手机条高 78 / bar padding 6/6 / 光球足印 64
//   G[TAG=after]  修后态钉：手机条高 58 / bar padding 4/4 / 光球足印 48
//   D  桌面零触碰：#input-bar padding 6/6、#input 6px、光球边距 0、条高 78
//   C  console 零新增 error
//
// 用法：TAG=before SHOTS_DIR=… node tests/composer-mobile-slim-shots.mjs
//       TAG=after  SHOTS_DIR=… node tests/composer-mobile-slim-shots.mjs
import { chromium } from 'playwright-core';
import { createServer } from 'node:http';
import { readFile } from 'node:fs/promises';
import { mkdirSync } from 'node:fs';
import { join, dirname, extname, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const WEB = join(HERE, '..', 'src', 'main', 'resources', 'web');
const TAG = process.env.TAG || 'before';
const SHOTS_DIR = process.env.SHOTS_DIR ||
  join(HERE, '..', 'evidence', '20261101_composer-slim2', TAG);
mkdirSync(SHOTS_DIR, { recursive: true });

const MIME = {
  '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css',
  '.svg': 'image/svg+xml', '.png': 'image/png', '.ico': 'image/x-icon',
  '.json': 'application/json', '.woff2': 'font/woff2', '.woff': 'font/woff',
};
const SID = 's-composerslim2';
const NOW = Date.now();

let failures = 0;
const ok = (name, cond, extra = '') => {
  console.log(`${cond ? 'PASS' : 'FAIL'}  ${name}${extra ? '  — ' + extra : ''}`);
  if (!cond) failures++;
};
const sleep = (ms) => new Promise(r => setTimeout(r, ms));

// ── 历史夹具：AI/用户交替 10 条，把消息列撑出可滚形态（「腾显示空间」对照面）──
const HISTORY = Array.from({ length: 10 }, (_, i) => i % 2 === 0
  ? { type: 'ai', text: `填充消息 ${i + 1}：输入卡收窄后，消息列表应获得更多的可视高度。`, timestamp: NOW - 300_000 + i * 10_000 }
  : { type: 'user', text: `收到，继续第 ${i + 1} 项。`, timestamp: NOW - 300_000 + i * 10_000 });

// ── 静态服务器 ──────────────────────────────────────────────────────
const server = createServer(async (req, res) => {
  try {
    const p = decodeURIComponent(new URL(req.url, 'http://x').pathname);
    const file = normalize(join(WEB, p === '/' ? 'index.html' : p));
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

// ── WS 桩 ───────────────────────────────────────────────────────────
function wireUp(ws) {
  const sendConfig = () => ws.send(JSON.stringify({
    type: 'configData', config: '{"features":{}}', configured: true, onboarding: 'done', models: [], defaults: {},
  }));
  const sendSessions = () => ws.send(JSON.stringify({
    type: 'sessionList', sessions: [{ id: SID, name: 'root', agentName: 'Nebula' }], folders: [], activeId: SID,
  }));
  sendConfig();
  sendSessions();
  ws.onMessage((raw) => {
    let m; try { m = JSON.parse(raw); } catch { return; }
    if (m.type === 'ping') { ws.send(JSON.stringify({ type: 'pong' })); return; }
    if (m.type === 'getConfig') sendConfig();
    else if (m.type === 'getSessions' || m.type === 'listSessions') sendSessions();
    else if (m.type === 'getHistory') {
      ws.send(JSON.stringify({ type: 'historyPage', sessionId: m.sessionId, messages: HISTORY, hasMore: false, offset: 0 }));
    }
  });
}

const browser = await chromium.launch();
const consoleErrors = [];

async function boot(colorScheme, viewport, hasTouch) {
  const ctx = await browser.newContext({ viewport, colorScheme, deviceScaleFactor: 2, hasTouch });
  await ctx.addInitScript(() => {
    localStorage.setItem('nebflow_token', 't');
    localStorage.setItem('nebflow_locale', 'zh-CN');
    localStorage.setItem('stand_input_history', '[]');
  });
  const page = await ctx.newPage();
  page.on('console', (m) => { if (m.type() === 'error') consoleErrors.push(`[${TAG}] ${m.text()}`); });
  page.on('pageerror', (e) => consoleErrors.push(`[${TAG}] pageerror: ${e.message}`));
  await page.routeWebSocket(/\/ws/, ws => wireUp(ws));
  await page.goto(BASE + '/index.html');
  await page.waitForSelector('#input', { state: 'visible', timeout: 20000 });
  await sleep(1200);
  return { ctx, page };
}

/** 几何探针：输入卡垂直链 + 居中/热区/遮挡不变量。 */
async function probe(page) {
  return page.evaluate(() => {
    const cs = (el) => el ? getComputedStyle(el) : null;
    const area = document.getElementById('input-area');
    const bar = document.getElementById('input-bar');
    const input = document.getElementById('input');
    const send = document.getElementById('send-btn');
    const stop = document.getElementById('stop-btn');
    const attach = document.getElementById('attach-btn');
    const orbWrap = document.getElementById('mic-orb-wrap');
    const orbBtn = document.getElementById('voice-btn');
    const orbCanvas = document.getElementById('mic-canvas');
    const strip = document.getElementById('attachment-preview');
    const chat = document.getElementById('chat');
    const r = (el) => { const x = el.getBoundingClientRect(); return { top: +x.top.toFixed(1), bottom: +x.bottom.toFixed(1), left: +x.left.toFixed(1), right: +x.right.toFixed(1), h: +x.height.toFixed(1), cy: +((x.top + x.bottom) / 2).toFixed(1) }; };
    const vw = window.innerWidth, vh = window.innerHeight;
    const barRect = bar ? r(bar) : null;
    const sendRect = send ? r(send) : null;
    const sendVisible = sendRect
      && sendRect.bottom <= vh + 0.5 && sendRect.right <= vw + 0.5 && sendRect.top >= 0 && sendRect.left >= 0;
    // 遮挡：附件条（in-flow 行）底缘必须 ≤ textarea 顶缘（B12 红线语义）。
    let stripOccludes = false;
    if (strip && strip.children.length > 0 && input) {
      const sr = strip.getBoundingClientRect(), ir = input.getBoundingClientRect();
      stripOccludes = sr.bottom > ir.top + 0.5;
    }
    // 垂直居中：钮中心 vs 条中心（flex align-items:center 契约）。
    const attachRect = attach ? r(attach) : null;
    const canvasRect = orbCanvas ? r(orbCanvas) : null;
    const dSend = sendRect && barRect ? Math.abs(sendRect.cy - barRect.cy) : null;
    const dAttach = attachRect && barRect ? Math.abs(attachRect.cy - barRect.cy) : null;
    const dOrb = canvasRect && barRect ? Math.abs(canvasRect.cy - barRect.cy) : null;
    const orbBtnRect = orbBtn ? r(orbBtn) : null;
    return {
      mobileLayer: document.body.classList.contains('mobile-layer'),
      areaPadBottom: cs(area)?.paddingBottom ?? null,
      barPadTop: cs(bar)?.paddingTop ?? null,
      barPadBottom: cs(bar)?.paddingBottom ?? null,
      inputPadTop: cs(input)?.paddingTop ?? null,
      inputPadBottom: cs(input)?.paddingBottom ?? null,
      orbMarginTop: cs(orbWrap)?.marginTop ?? null,
      orbMarginBottom: cs(orbWrap)?.marginBottom ?? null,
      // 布局足印 = margin box（border 盒 + 上下负边距；负边距解耦后这里读 48）。
      orbFootprint: orbWrap
        ? orbWrap.offsetHeight + parseFloat(cs(orbWrap)?.marginTop || '0') + parseFloat(cs(orbWrap)?.marginBottom || '0')
        : null,
      orbBtnH: orbBtnRect ? orbBtnRect.h : null,
      barH: bar ? bar.offsetHeight : null,
      areaH: area ? area.offsetHeight : null,
      chatPadBottom: chat ? cs(chat).paddingBottom : null,
      inputH: input ? input.offsetHeight : null,
      gapBelowBar: area && bar ? +(area.getBoundingClientRect().bottom - barRect.bottom).toFixed(1) : null,
      dSend, dAttach, dOrb,
      sendH: sendRect ? sendRect.h : null,
      attachH: attachRect ? attachRect.h : null,
      stopHidden: stop ? getComputedStyle(stop).display === 'none' : null,
      sendVisible,
      stripOccludes,
      vh,
    };
  });
}

const DESKTOP = { width: 1280, height: 860 };
const MOBILE = { width: 375, height: 812 };

for (const scheme of ['light', 'dark']) {
  // ── 桌面零触碰腿：padding/条高/光球边距钉修前原值 ────────────────────
  {
    const { ctx, page } = await boot(scheme, DESKTOP, false);
    const p = await probe(page);
    ok(`desktop-${scheme} D padding 原值（bar 6/6 · input 6）`,
      p.barPadTop === '6px' && p.barPadBottom === '6px' && p.inputPadTop === '6px' && p.inputPadBottom === '6px',
      JSON.stringify({ bar: [p.barPadTop, p.barPadBottom], input: [p.inputPadTop, p.inputPadBottom] }));
    ok(`desktop-${scheme} D 条高 78 原值（光球驱动；实测绘：64 光球 + 12 padding + 2 border）`, p.barH === 78, `barH=${p.barH}`);
    ok(`desktop-${scheme} D 光球边距 0 原值`, p.orbMarginTop === '0px' && p.orbMarginBottom === '0px' && p.orbFootprint === 64,
      `m=${p.orbMarginTop}/${p.orbMarginBottom} fp=${p.orbFootprint}`);
    console.log(`GEOM desktop-${scheme} TAG=${TAG} ` + JSON.stringify(p));
    await page.screenshot({ path: join(SHOTS_DIR, `desktop-${scheme}-rest.png`) });
    await ctx.close();
  }

  // ── 手机 375 腿：rest / multiline / attach 三态 ─────────────────────
  {
    const { ctx, page } = await boot(scheme, MOBILE, true);
    await page.waitForFunction(() => window.__nfMobileLayer && window.__nfMobileLayer.active === true, null, { timeout: 15000 });
    await sleep(600);
    const p0 = await probe(page);

    // M1 手机层 + 发送钮可见
    ok(`mobile-${scheme} M1 mobile-layer 已激活`, p0.mobileLayer === true);
    ok(`mobile-${scheme} M1 发送钮完整可见（rest）`, p0.sendVisible === true, JSON.stringify({ vh: p0.vh, areaH: p0.areaH }));
    // G 修前/修后几何契约钉（实测基线：条高 = 内容 + padding + 2 border）
    const expect = TAG === 'after'
      ? { barH: 58, pad: '4px', orbMargin: '-8px', fp: 48 }
      : { barH: 78, pad: '6px', orbMargin: '0px', fp: 64 };
    ok(`mobile-${scheme} G[${TAG}] 条高 ${expect.barH}（收窄契约钉）`, p0.barH === expect.barH, `barH=${p0.barH}`);
    ok(`mobile-${scheme} G[${TAG}] bar 垂直 padding ${expect.pad}`, p0.barPadTop === expect.pad && p0.barPadBottom === expect.pad,
      `${p0.barPadTop}/${p0.barPadBottom}`);
    ok(`mobile-${scheme} G[${TAG}] 光球布局足印 ${expect.fp}（边距 ${expect.orbMargin}，球体渲染盒 64 不变）`,
      p0.orbMarginTop === expect.orbMargin && p0.orbMarginBottom === expect.orbMargin && p0.orbFootprint === expect.fp && p0.orbBtnH === 64,
      `m=${p0.orbMarginTop}/${p0.orbMarginBottom} fp=${p0.orbFootprint} btn=${p0.orbBtnH}`);
    // M2 垂直居中（发送/附件/光球画布中心 = 条中心）
    ok(`mobile-${scheme} M2 三钮垂直居中（|Δ|≤1.5px）`,
      p0.dSend !== null && p0.dSend <= 1.5 && p0.dAttach !== null && p0.dAttach <= 1.5 && p0.dOrb !== null && p0.dOrb <= 1.5,
      `Δsend=${p0.dSend} Δattach=${p0.dAttach} Δorb=${p0.dOrb}`);
    // M3 触控热区下限
    ok(`mobile-${scheme} M3 热区下限（send/attach≥36 · orb≥44）`,
      p0.sendH >= 36 && p0.attachH >= 36 && p0.orbBtnH >= 44, `send=${p0.sendH} attach=${p0.attachH} orb=${p0.orbBtnH}`);
    ok(`mobile-${scheme} M3 busy 前中断钮隐藏`, p0.stopHidden === true);
    console.log(`GEOM mobile-${scheme} TAG=${TAG} ` + JSON.stringify(p0));
    await page.screenshot({ path: join(SHOTS_DIR, `mobile-${scheme}-rest.png`) });

    // M4 多行自动长高（增长逻辑未动；修后条随 textarea 增长）
    const input = page.locator('#input');
    await input.click();
    await input.fill(Array.from({ length: 5 }, (_, i) => `手机档第 ${i + 1} 行：多行自动长高回归。`).join('\n'));
    await input.dispatchEvent('input');
    await sleep(400);
    const p1 = await probe(page);
    ok(`mobile-${scheme} M4 多行增长（textarea +80 以上）`, p1.inputH > p0.inputH + 80, `h ${p0.inputH} → ${p1.inputH}`);
    ok(`mobile-${scheme} M4 条随行高增长且发送钮仍完整可见`, p1.barH > p0.barH + 60 && p1.sendVisible === true, `barH ${p0.barH} → ${p1.barH}`);
    await page.screenshot({ path: join(SHOTS_DIR, `mobile-${scheme}-multiline.png`) });
    await input.fill('');
    await input.dispatchEvent('input');
    await sleep(200);

    // M5 附件条零遮挡 + 发送钮不遮挡
    await page.evaluate(async () => {
      const chatMod = await import('/js/chat.js');
      const { activeView } = await import('/js/chatView.js');
      activeView.pendingAttachments.push(
        { type: 'file', name: '输入卡收窄对照说明.md', mimeType: 'text/markdown' },
        { type: 'file', name: 'mobile-evidence.png', mimeType: 'image/png' },
      );
      chatMod.renderAttachmentPreview();
    });
    await sleep(300);
    const p2 = await probe(page);
    ok(`mobile-${scheme} M5 附件条零遮挡（chip 底 ≤ textarea 顶）`, p2.stripOccludes === false);
    ok(`mobile-${scheme} M5 附件态发送钮完整可见`, p2.sendVisible === true);
    await page.screenshot({ path: join(SHOTS_DIR, `mobile-${scheme}-attach.png`) });
    await ctx.close();
  }
}

await browser.close();
await new Promise(r => server.close(r));
const errs = consoleErrors.filter((t) => !t.includes('favicon') && !/Failed to load resource.*404/.test(t));
console.log(`done TAG=${TAG} → ${SHOTS_DIR}`);
console.log(errs.length ? `console errors (${errs.length}):\n` + errs.slice(0, 10).join('\n') : 'console: no errors');
ok(`${TAG} console 零新增错误`, errs.length === 0, errs.slice(0, 3).join(' | '));
console.log(failures === 0 ? 'RESULT: ALL PASS' : `RESULT: ${failures} FAILURE(S)`);
process.exit(failures === 0 ? 0 : 1);

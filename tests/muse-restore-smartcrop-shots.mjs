#!/usr/bin/env node
// muse-restore-smartcrop-shots.mjs — Muse 头像基座回归 + 叠卡封面智能取景批
// （作者令 2026-11-01 21:40 三件：①叠卡内容感知取景 ②头像+名字回归顶层居中
// ③名字扁平毛玻璃考古适配）验收 harness。
//
// 考古结论（③的 diff 依据，详见批报告）：Muse 基座块原居 liquid-glass.css:330-402
// （b4b6d8745 落地、49e2e7477 合入）；统一按钮玻璃批（dd78d5d05）整文件退役时
// 把基座一并带走 —— 本批在 mobileLayer.css 按现行玻璃语言重建（名字=扁平毛玻璃，
// 玻璃单源包 token，零立体边框）。
//
// 产出 → evidence/20261101_muse-restore-smartcrop/：
//   muse-<engine>-<scheme>-rest.png      手机 375 静止态（头像+名字顶层居中入流）
//   muse-<engine>-<scheme>-pinned.png    手机 375 滚动态（sticky 锚贴窗口顶压流）
//   crop-ui-screenshot-<engine>-pre-center.png   恶意样本 修前（强制几何中心）
//   crop-ui-screenshot-<engine>-post.png         恶意样本 修后（算法取景）
//   crop-samples-<engine>-<scheme>.png   5 类样本整版（目检不劣化）
//   desktop-<engine>-<scheme>.png        桌面 1280 回归（#header 头像行原样+叠卡取景）
//
// 探针：
//   A① Muse 形态回归：.g2-chat-head display:flex/column/center；头像 56px 水平
//      居中（与 #chat 同心 ±2px）；名字在头像正下方且居中；sticky 顶锚 + 压流；
//      静止态落位 = 组顶 4px（= --muse-head-anchor，锚双钮中心行；同行批
//      2026-11-02 11:08 再改 —— 旧值 64px「悬浮双键行下缘」= 让 60px 错位合法化
//      的口径，已退役）
//   A② 名字扁平毛玻璃：background = --glass-bg 真值（亮 rgba(255,255,255,.55) /
//      暗 rgba(24,28,38,.68)）；border-width 0（无立体边框）；box-shadow none；
//      backdrop-filter 含 blur；999px 胶囊圆角
//   A③ 消息流零行内身份行（.g2-msg-meta 计数 0）；桌面 #header 形态零触碰
//   B① 恶意样本（UI 截图：上 35% 内容 + 下 65% 空白）：算法选中窗 y ≤ 40（修前
//      中心裁出空白、修后裁到内容区，pre/post 实拍对照）
//   B② 5 类样本（UI 截图/人像/风景/文档/暗图）取景位置全在 [0,100] 合理域且
//      全域纹理样本（人像）不 slam 到边（10–90）；实拍目检不劣化
//   B③ 缓存：同 src 二次渲染命中 cache-hit 且取景结果逐字一致
//   R0 双引擎（Chromium/WebKit）×双主题（light/dark）×双端（375/1280）零 console error
// 走 mobile-muse-avatar-shots 同款静态 harness（无后端：routeWebSocket + fm_api_mock
// + catch-all API 200）；叠卡走真实生产路径 buildUserMediaRow（data URI 内联腿）。
// Run: node tests/muse-restore-smartcrop-shots.mjs

import { chromium, webkit } from '@playwright/test';
import { readFileSync, mkdirSync, existsSync } from 'node:fs';
import { join, dirname, extname, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';

const WEB = join(dirname(fileURLToPath(import.meta.url)), '..', 'src', 'main', 'resources', 'web');
const OUT = join(dirname(fileURLToPath(import.meta.url)), '..', 'evidence', '20261101_muse-restore-smartcrop');
const MIME = { '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.svg': 'image/svg+xml', '.png': 'image/png', '.ico': 'image/x-icon', '.json': 'application/json', '.woff2': 'font/woff2', '.woff': 'font/woff', '.ttf': 'font/ttf' };
const SID = 'muse-spider-session';

// 夹具头像：珊瑚底白字「S」圆牌（Spider 身份；muse/dock-shape harness 同族）。
const AVATAR_SVG = '<svg xmlns="http://www.w3.org/2000/svg" width="156" height="156"><defs><linearGradient id="g" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="#e8804f"/><stop offset="1" stop-color="#d64545"/></linearGradient></defs><rect width="156" height="156" rx="78" fill="url(#g)"/><text x="78" y="102" font-family="-apple-system,sans-serif" font-size="64" font-weight="600" text-anchor="middle" fill="#fff">S</text></svg>';
const AV = 'data:image/svg+xml;utf8,' + encodeURIComponent(AVATAR_SVG);

// 历史夹具：Spider agent 会话，12 条长文（375×812 视口必出滚动 ⇒ 滚动态可锚定）。
const LONG = '这次真重启完成了（新引擎 pid=30450，13:23 就绪）：核查干净零残留会话，所有批都在重启前落了地；新代码已生效，待办圆框、主 agent stand logo、长按退役、文件图片 footer、手机空白根治全在里面；手机和电脑各刷新一次就能看到，消息列表应该恢复正常了。';
const HISTORY = [];
for (let i = 0; i < 12; i++) {
  HISTORY.push({ type: i % 2 === 0 ? 'user' : 'ai', text: i % 2 === 0 ? `第 ${Math.floor(i / 2) + 1} 轮：${LONG.slice(0, 40)}` : `第 ${Math.floor(i / 2) + 1} 轮回复：${LONG}`, timestamp: Date.now() - (600 - i * 30) * 1e3 });
}

mkdirSync(OUT, { recursive: true });
if (!existsSync(join(WEB, 'index.html'))) { console.error('web root missing: ' + WEB); process.exit(2); }

let failures = 0;
const ok = (name, pass, detail = '') => {
  console.log(`${pass ? 'PASS' : 'FAIL'}  ${name}${detail ? '  — ' + detail : ''}`);
  if (!pass) failures++;
};

/** 页内取景样本绘制（in-page canvas → data URI；零夹具体积）。 */
const SAMPLES_SCRIPT = `
(() => {
  const mk = (w, h, draw) => {
    const c = document.createElement('canvas'); c.width = w; c.height = h;
    const g = c.getContext('2d'); draw(g, w, h);
    return c.toDataURL('image/png');
  };
  const noise = (g, w, h, n, seed) => {
    let s = seed;
    const rnd = () => (s = (s * 1103515245 + 12345) & 0x7fffffff) / 0x7fffffff;
    for (let i = 0; i < n; i++) {
      g.fillStyle = 'hsl(' + (rnd() * 360 | 0) + ' 60% ' + (25 + rnd() * 45 | 0) + '%)';
      g.beginPath(); g.ellipse(rnd() * w, rnd() * h, 6 + rnd() * 26, 5 + rnd() * 20, rnd() * 3, 0, 7); g.fill();
    }
  };
  // 恶意样本：UI 截图——上 35% 内容（顶栏/文本条/按钮），下 65% 近均匀空白。
  const uiShot = mk(640, 1280, (g, w, h) => {
    g.fillStyle = '#e9eaee'; g.fillRect(0, 0, w, h);
    g.fillStyle = '#4a7dff'; g.fillRect(0, 0, w, 96);
    g.fillStyle = '#ffffff'; g.font = '600 34px -apple-system,sans-serif'; g.fillText('设置', 32, 62);
    g.fillStyle = '#f7f8fa'; g.fillRect(24, 128, w - 48, 168);
    for (let i = 0; i < 4; i++) {
      g.fillStyle = '#c9ccd4';
      g.fillRect(44, 156 + i * 38, (w - 120) * (0.55 + (i % 3) * 0.12), 14);
    }
    g.fillStyle = '#4a7dff'; g.fillRect(24, 322, w - 48, 74);
    g.fillStyle = '#ffffff'; g.font = '600 28px -apple-system,sans-serif';
    g.fillText('保存并重启引擎', (w - 220) / 2, 368);
  });
  // 人像（全域纹理：渐变 + 全图噪斑）。
  const portrait = mk(600, 900, (g, w, h) => {
    const gr = g.createLinearGradient(0, 0, w, h);
    gr.addColorStop(0, '#ffd9a8'); gr.addColorStop(1, '#a8c4ff');
    g.fillStyle = gr; g.fillRect(0, 0, w, h);
    noise(g, w, h, 220, 42);
    g.fillStyle = 'rgba(120,72,40,0.85)';
    g.beginPath(); g.ellipse(w / 2, h * 0.34, 86, 108, 0, 0, 7); g.fill();
    g.beginPath(); g.ellipse(w / 2, h * 0.72, 150, 130, 0, 0, 7); g.fill();
  });
  // 风景（横向：天空 + 山脊，内容中偏横）。
  const landscape = mk(1280, 720, (g, w, h) => {
    const sky = g.createLinearGradient(0, 0, 0, h);
    sky.addColorStop(0, '#8ec9ff'); sky.addColorStop(1, '#e8f4ff');
    g.fillStyle = sky; g.fillRect(0, 0, w, h);
    g.fillStyle = '#5a7d52';
    g.beginPath(); g.moveTo(0, h); g.lineTo(w * 0.3, h * 0.34); g.lineTo(w * 0.62, h); g.fill();
    g.fillStyle = '#3f5c3a';
    g.beginPath(); g.moveTo(w * 0.42, h); g.lineTo(w * 0.74, h * 0.24); g.lineTo(w, h); g.fill();
    g.fillStyle = '#ffffff'; g.beginPath(); g.ellipse(w * 0.82, h * 0.16, 46, 26, 0, 0, 7); g.fill();
  });
  // 文档（白底黑字行，满幅均匀纹理）。
  const docSample = mk(620, 877, (g, w, h) => {
    g.fillStyle = '#ffffff'; g.fillRect(0, 0, w, h);
    for (let y = 60; y < h - 40; y += 30) {
      g.fillStyle = '#23262d';
      g.fillRect(48, y, (w - 96) * (0.7 + ((y * 7) % 30) / 100), 10);
    }
  });
  // 暗图（近黑底 + 亮主体偏上）。
  const dark = mk(640, 960, (g, w, h) => {
    g.fillStyle = '#0a0c10'; g.fillRect(0, 0, w, h);
    noise(g, w, h, 90, 7);
    const glow = g.createRadialGradient(w / 2, h * 0.3, 8, w / 2, h * 0.3, 150);
    glow.addColorStop(0, '#bff3ff'); glow.addColorStop(1, 'rgba(10,12,16,0)');
    g.fillStyle = glow; g.fillRect(0, 0, w, h);
  });
  const filler = portrait;
  return { uiShot, portrait, landscape, document: docSample, dark, filler };
})()
`;

/** 组装取景样本区（真实生产路径 buildUserMediaRow；每样本一条双图叠卡行）。 */
async function mountCropSection(page) {
  return page.evaluate(async (samples) => {
    const { buildUserMediaRow } = await import('/js/popArtifacts.js');
    const chat = document.getElementById('chat');
    const host = document.createElement('div');
    host.id = 'crop-spec-host';
    host.style.cssText = 'padding:12px 14px;display:flex;flex-direction:column;gap:18px;background:var(--color-bg,#f6f7f9);';
    const defs = [
      ['ui-screenshot', samples.uiShot],
      ['portrait', samples.portrait],
      ['landscape', samples.landscape],
      ['document', samples.document],
      ['dark', samples.dark],
    ];
    const rows = {};
    for (const [name, uri] of defs) {
      const row = buildUserMediaRow(
        [{ type: 'image', name: name + '.png', preview: uri }, { type: 'image', name: 'filler.png', preview: samples.filler }],
        { ts: Date.now() });
      row.dataset.sampleName = name;
      host.appendChild(row);
      rows[name] = uri;
    }
    // 缓存腿：同 src（ui-screenshot 的 data URI）二次渲染 ⇒ 应 cache-hit。
    const cacheRow = buildUserMediaRow(
      [{ type: 'image', name: 'ui-screenshot.png', preview: samples.uiShot }, { type: 'image', name: 'filler.png', preview: samples.filler }],
      { ts: Date.now() });
    cacheRow.dataset.sampleName = 'ui-screenshot-cache';
    host.appendChild(cacheRow);
    chat.appendChild(host);
    // webkit 怪癖：src 在 detached 期已设的 lazy img，插入视口后不重评估（对照
    // 实验在案：同 img chromium 正常加载、webkit 永不 load）⇒ harness 侧改 eager
    // 触发既有 load 链（生产 lazy 语义零触碰；取景仍走真实 load→natural 回调）。
    host.querySelectorAll('img.pop-img').forEach((i) => { i.loading = 'eager'; });
    return rows;
  }, await page.evaluate(SAMPLES_SCRIPT));
}

/** 等待取景落定并回读全部样本的探测面。 */
async function probeCropSection(page) {
  await page.waitForFunction(() => {
    const imgs = document.querySelectorAll('#crop-spec-host .pop-stack-view .media-layer.layer-front img.pop-img');
    return imgs.length >= 6 && [...imgs].every((i) => i.dataset.smartCrop === 'computed' || i.dataset.smartCrop === 'cache-hit');
  }, { timeout: 20000 });
  return page.evaluate(() => {
    const read = (row) => {
      const img = row.querySelector('.pop-stack-view .media-layer.layer-front img.pop-img');
      const pos = img.style.objectPosition || '';
      const m = pos.match(/^([\d.]+)%\s+([\d.]+)%$/);
      return {
        mode: img.dataset.smartCrop || '',
        pos,
        x: m ? +m[1] : -1,
        y: m ? +m[2] : -1,
        natural: img.naturalWidth + 'x' + img.naturalHeight,
      };
    };
    const out = { __modes: [] };
    document.querySelectorAll('#crop-spec-host .row.user-media').forEach((row) => {
      out[row.dataset.sampleName] = read(row);
      out.__modes.push(row.querySelector('.pop-stack-view .media-layer.layer-front img.pop-img')?.dataset.smartCrop || '');
    });
    return out;
  });
}

/** 一台手机跑（engine × scheme）：Muse 形态 + 扁平毛玻璃名 + 取景全腿。 */
async function runMobile(engineKind, scheme) {
  const tag = `${engineKind}-${scheme}`;
  const errors = [];
  const launcher = engineKind === 'chromium' ? chromium : webkit;
  const browser = await launcher.launch();
  try {
    const page = await browser.newPage({ viewport: { width: 375, height: 812 }, hasTouch: true, colorScheme: scheme });
    page.on('pageerror', (e) => errors.push(`pageerror: ${e.message}`));
    page.on('console', (m) => { if (m.type() === 'error') errors.push(`console: ${m.text()}`); });
    await page.addInitScript(({ avatar }) => {
      for (const ns of ['stand', 'nebflow']) {
        localStorage.setItem(`${ns}_token`, 'e2e-token');
        localStorage.setItem(`${ns}_locale`, 'zh-CN');
        localStorage.setItem(`${ns}_agent_avatar_Spider`, JSON.stringify({ dataUrl: avatar, sha: 'evidence-seed', at: Date.now() }));
      }
      Object.defineProperty(navigator.serviceWorker, 'register', { value: () => Promise.resolve(), configurable: true });
      localStorage.setItem('fm_api_mock', '1');
      localStorage.setItem('fm_api_mock_seed', JSON.stringify({
        self: { userId: 'me', neblinkId: 'me@example.com', name: 'Me', avatarUrl: '' },
        users: [], friends: [], incoming: [], outgoing: [], conversations: [], messages: {},
      }));
    }, { avatar: AV });

    await page.route('**/*', (route) => {
      const url = new URL(route.request().url());
      let p = decodeURIComponent(url.pathname);
      if (p === '/') p = '/index.html';
      if (p.startsWith('/api/') || p.startsWith('/agents/')) {
        if (p === '/api/sessions') return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ sessions: [{ id: SID, name: 'spider-proc', agentName: 'Spider' }], activeId: SID }) });
        if (p === '/api/neblink/status') return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ loggedIn: true, device: { id: 'd1', name: '本机', platform: 'macos', userDescription: '', avatarUrl: '', email: 'me@example.com', displayName: '测试用户' }, peers: [] }) });
        return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
      }
      const file = normalize(join(WEB, p));
      if (!file.startsWith(WEB)) return route.fulfill({ status: 403, body: 'forbidden' });
      try { return route.fulfill({ status: 200, contentType: MIME[extname(file)] || 'application/octet-stream', body: readFileSync(file) }); }
      catch { return route.fulfill({ status: 404, body: 'not found' }); }
    });

    await page.routeWebSocket(/\/ws/, (ws) => {
      const sendConfig = () => ws.send(JSON.stringify({ type: 'configData', config: '{"features":{"friends":false}}', configured: true, onboarding: 'done' }));
      const sessions = [{ id: SID, name: 'spider-proc', agentName: 'Spider' }];
      const sendSessions = () => ws.send(JSON.stringify({ type: 'sessionList', sessions, folders: [], activeId: SID }));
      const sendAgents = () => ws.send(JSON.stringify({ type: 'agentList', agents: [{ name: 'Spider', displayName: 'Spider' }] }));
      sendConfig(); sendSessions(); sendAgents();
      ws.onMessage((raw) => {
        let parsed; try { parsed = JSON.parse(raw); } catch { return; }
        if (parsed.type === 'getConfig') sendConfig();
        else if (parsed.type === 'getSessions' || parsed.type === 'listSessions') { sendSessions(); sendAgents(); }
        else if (parsed.type === 'getHistory') ws.send(JSON.stringify({ type: 'historyPage', sessionId: parsed.sessionId, messages: HISTORY, hasMore: false, offset: 0, total: HISTORY.length }));
      });
    });

    await page.goto('http://localhost:59999/', { timeout: 90000 });
    await page.waitForFunction(async (rootSid) => {
      const s = (await import('/js/state.js')).default;
      return s.activeSessionId === rootSid && s.ws && s.ws.readyState === 1 && document.body.classList.contains('mobile-layer');
    }, SID, { timeout: 20000 });
    await page.waitForFunction(() => document.querySelectorAll('#chat .row.ai').length >= 6, { timeout: 20000 });
    await page.waitForFunction(() => {
      const img = document.querySelector('#agent-avatar-btn .agent-avatar-img');
      return !!img && img.complete && img.naturalWidth > 0;
    }, { timeout: 20000 });
    await page.waitForTimeout(700);

    // ── A：Muse 形态（静止态探针 + 实拍）─────────────────────────────
    await page.evaluate(() => { const c = document.getElementById('chat'); c.scrollTop = 0; });
    await page.waitForFunction(() => document.getElementById('chat').scrollTop === 0, { timeout: 10000 });
    await page.waitForTimeout(250);
    const rest = await probeHead(page, scheme);
    assertMuse(tag, rest, scheme);
    ok(`${tag} A① 静止态：悬浮组锚双钮中心行（组顶 4 ±1、头像顶 12 ±1）`,
      Math.abs(rest.head.top - 4) <= 1 && Math.abs(rest.avTop - 12) <= 1,
      `headTop=${rest.head.top.toFixed(1)} avTop=${rest.avTop.toFixed(1)} h=${rest.head.height}`);
    await page.screenshot({ path: join(OUT, `muse-${tag}-rest.png`) });

    // ── A：滚动态（sticky 贴顶 + 压流）─────────────────────────────
    await page.evaluate(() => { const c = document.getElementById('chat'); c.scrollTop = c.scrollHeight; });
    await page.waitForFunction(() => document.getElementById('chat').scrollTop > 0, { timeout: 10000 });
    await page.waitForTimeout(250);
    const pinned = await probeHead(page, scheme);
    assertMuse(tag, pinned, scheme);
    ok(`${tag} A① 滚动态：落点 == 静止落点（组顶 4 ±1、头像顶 12 ±1，零 sticky 位移）`,
      Math.abs(pinned.head.top - 4) <= 1 && Math.abs(pinned.avTop - 12) <= 1,
      `headTop=${pinned.head.top}/${rest.head.top} avTop=${pinned.avTop}`);
    ok(`${tag} A① 滚动态：压在消息流上缘（rowsBehind>0）`, pinned.rowsBehind > 0, `rowsBehind=${pinned.rowsBehind}`);
    await page.screenshot({ path: join(OUT, `muse-${tag}-pinned.png`) });

    // ── B：取景样本区（恶意样本 + 5 类 + 缓存）───────────────────────
    await mountCropSection(page);
    // lazy img 在视口外不加载（webkit 阈值更紧）——先滚到样本区再等取景落定。
    await page.evaluate(() => document.getElementById('crop-spec-host').scrollIntoView({ block: 'start' }));
    await page.waitForTimeout(400);
    const crop = await probeCropSection(page);
    const modeOk = (k) => crop[k].mode === 'computed' || crop[k].mode === 'cache-hit';
    ok(`${tag} B① 恶意样本（UI 截图）算法取景落上部（y ≤ 40）`,
      modeOk('ui-screenshot') && crop['ui-screenshot'].y >= 0 && crop['ui-screenshot'].y <= 40,
      `mode=${crop['ui-screenshot'].mode} pos=${crop['ui-screenshot'].pos}`);
    ok(`${tag} B② 人像（全域纹理）不 slam 到边（x,y ∈ [10,90]）`,
      crop.portrait.x >= 10 && crop.portrait.x <= 90 && crop.portrait.y >= 10 && crop.portrait.y <= 90,
      `pos=${crop.portrait.pos}`);
    for (const name of ['landscape', 'document', 'dark']) {
      const c = crop[name];
      ok(`${tag} B② ${name} 取景位置合理（x,y ∈ [0,100]，算法在场）`,
        modeOk(name) && c.x >= 0 && c.x <= 100 && c.y >= 0 && c.y <= 100, `pos=${c.pos}`);
    }
    ok(`${tag} B③ 缓存腿：同 src 三面（ui 行/缓存行/展开面）取景逐字一致 + 命中面在场`,
      crop['ui-screenshot-cache'].pos === crop['ui-screenshot'].pos &&
      crop.__modes.includes('computed') && crop.__modes.includes('cache-hit'),
      `modes=[${crop.__modes}] pos=${crop['ui-screenshot'].pos}`);

    // 恶意样本 修前（强制几何中心）/ 修后（算法取景）实拍对照。
    const uiRow = page.locator('#crop-spec-host .row.user-media[data-sample-name="ui-screenshot"]').first();
    await uiRow.scrollIntoViewIfNeeded();
    await page.waitForTimeout(350);
    await page.evaluate(() => {
      const row = document.querySelector('#crop-spec-host .row.user-media[data-sample-name="ui-screenshot"]');
      const img = row.querySelector('.pop-stack-view .media-layer.layer-front img.pop-img');
      img.dataset.savedPos = img.style.objectPosition || '';
      img.style.objectPosition = ''; // 修前形态 = object-position 缺省（50% 50% 中心）
    });
    await page.waitForTimeout(200);
    await page.screenshot({ path: join(OUT, `crop-ui-screenshot-${tag}-pre-center.png`) });
    await page.evaluate(() => {
      const row = document.querySelector('#crop-spec-host .row.user-media[data-sample-name="ui-screenshot"]');
      const img = row.querySelector('.pop-stack-view .media-layer.layer-front img.pop-img');
      img.style.objectPosition = img.dataset.savedPos || '';
      delete img.dataset.savedPos;
    });
    await page.waitForTimeout(200);
    await page.screenshot({ path: join(OUT, `crop-ui-screenshot-${tag}-post.png`) });

    // 5 类样本逐行实拍（目检不劣化；首行 ui-screenshot 另有 pre/post 对照）。
    for (const name of ['portrait', 'landscape', 'document', 'dark']) {
      const row = page.locator(`#crop-spec-host .row.user-media[data-sample-name="${name}"]`).first();
      await row.scrollIntoViewIfNeeded();
      await page.waitForTimeout(250);
      await page.screenshot({ path: join(OUT, `crop-sample-${name}-${tag}.png`) });
    }

    ok(`${tag} R0 零 console error`, errors.length === 0, errors.slice(0, 3).join(' | '));
    await page.close();
  } finally {
    await browser.close();
  }
}

/** 头部形态页内探针（静止/滚动两态共用）。 */
async function probeHead(page, scheme) {
  return page.evaluate((scheme) => {
    const r = (el) => el.getBoundingClientRect();
    const chat = document.getElementById('chat');
    const head = document.querySelector('#chat > .g2-chat-head');
    const avatarBtn = document.getElementById('agent-avatar-btn');
    const nameEl = document.getElementById('session-name');
    const cs = head ? getComputedStyle(head) : null;
    const nameCs = nameEl ? getComputedStyle(nameEl) : null;
    const av = r(avatarBtn);
    const nr = r(nameEl);
    const chatBox = r(chat);
    const headBox = r(head);
    const intersect = (a, b) => Math.max(0, Math.min(a.right, b.right) - Math.max(a.left, b.left)) * Math.max(0, Math.min(a.bottom, b.bottom) - Math.max(a.top, b.top));
    /* 压流探针口径（同位置批修正）：判据本意是「消息行从顶栏下缘穿过」，行类型
       与语义无关 ⇒ 数全部 .row（原口径只数 .row.ai ⇒ 落底时穿顶栏的那一行恰好
       是 user 行就恒判 0）。 */
    const rowsBehind = [...chat.querySelectorAll('.row')]
      .filter((row) => intersect(r(row), headBox) > 4).length;
    const glassBgLight = 'rgba(255, 255, 255, 0.55)';
    const glassBgDark = 'rgba(24, 28, 38, 0.68)';
    return {
      mobileLayer: document.body.classList.contains('mobile-layer'),
      display: cs?.display, flexDir: cs?.flexDirection, alignItems: cs?.alignItems,
      position: cs?.position,
      metas: chat.querySelectorAll('.g2-msg-meta, .g2-msg-avatar-slot, .g2-msg-name').length,
      aiRows: chat.querySelectorAll('.row.ai').length,
      avW: av.width, avH: av.height,
      avCx: (av.left + av.right) / 2,
      avRight: av.right,
      avTop: av.top,
      avBottom: av.bottom,
      vw: window.innerWidth,
      chatCx: (chatBox.left + chatBox.right) / 2,
      /* 下置判据：胶囊顶 ≥ 头像底（竖排）；水平中心与头像同轴。 */
      nameOnLine: nr.top >= av.top - 0.5 && nr.bottom <= av.bottom + 0.5,
      nameTop: nr.top,
      nameBottom: nr.bottom,
      nameGap: nr.left - av.right,
      nameCx: (nr.left + nr.right) / 2,
      nameText: nameEl.textContent,
      // A② 名字材质：单行档 = 零自绘底（直接写在玻璃顶栏上，玻璃叠玻璃退役）。
      nameBg: nameCs?.backgroundColor,
      nameBgOk: nameCs?.backgroundColor === 'rgba(0, 0, 0, 0)',
      nameBorderZero: nameCs ? parseFloat(nameCs.borderWidth) === 0 || nameCs.borderStyle === 'none' : false,
      nameShadowNone: !nameCs?.boxShadow || nameCs.boxShadow === 'none',
      nameBackdropBlur: (nameCs?.backdropFilter || nameCs?.webkitBackdropFilter || '').includes('blur'),
      nameRadius: nameCs?.borderRadius,
      head: headBox, chat: chatBox,
      pinnedNow: Math.abs(headBox.top - chatBox.top) <= 1.5,
      rowsBehind,
    };
  }, scheme);
}

/** Muse 形态两态共用断言（A①A②A③）。 */
function assertMuse(tag, p, scheme) {
  // ⚠ 契约改写（Muse 悬浮身份头恢复批 2026-11-02 09:14 作者令「头像在上、名字在下，
  // 拆掉顶栏」）：上一批（07:23 单行三段式：flex row 顶栏 / 头像 40 / 名字横排右侧 /
  // 零自绘底 / position:fixed）整条回退 —— 回到本文件 2026-11-01 21:40 批钉的
  // Muse 形态：flex column 竖排基座 + 头像 56px + 名字玻璃胶囊下置 + sticky 悬浮锚。
  ok(`${tag} A① Muse 基座在场（flex/column/center）`,
    p.display === 'flex' && p.flexDir === 'column' && p.alignItems === 'center',
    `${p.display}/${p.flexDir}/${p.alignItems}`);
  ok(`${tag} A① 头像 56px（收小批档；09:14 实拍 ≈50.8 佐证）`,
    Math.abs(p.avW - 56) <= 0.8 && Math.abs(p.avH - 56) <= 0.8,
    `${p.avW.toFixed(1)}×${p.avH.toFixed(1)}`);
  ok(`${tag} A① 头像水平居中（屏幕中线 ±1px）`, Math.abs(p.avCx - p.vw / 2) <= 1,
    `avCx=${p.avCx.toFixed(1)} mid=${(p.vw / 2).toFixed(1)}`);
  ok(`${tag} A① 名字在头像正下方且居中（胶囊顶 ≥ 头像底 −1、中心 ≤1px）`,
    p.nameTop >= p.avBottom - 1 && Math.abs(p.nameCx - p.avCx) <= 1,
    `gap=${(p.nameTop - p.avBottom).toFixed(1)} |nameCx−avCx|=${Math.abs(p.nameCx - p.avCx).toFixed(2)}`);
  ok(`${tag} A① sticky 悬浮锚在场（非 fixed 顶栏）`, p.position === 'sticky', p.position);
  ok(`${tag} A① 消息流零行内身份行（meta/slot/name 全零）`, p.aiRows > 0 && p.metas === 0,
    `aiRows=${p.aiRows} metas=${p.metas}`);
  // A② 名字 = 扁平毛玻璃胶囊（glass token 单源；零 border —— 作者令③历史裁定
  // 「不再用胶囊立体感边框」；999 圆角；有 backdrop blur）。上一批「零自绘底/
  // radius 0/零 backdrop」的单行顶栏判据随顶栏一并退役。
  const wantBg = scheme === 'dark' ? 'rgba(24, 28, 38, 0.68)' : 'rgba(255, 255, 255, 0.55)';
  ok(`${tag} A② 名字胶囊底 = glass token 真值（${wantBg}）`, p.nameBg === wantBg, p.nameBg);
  ok(`${tag} A② 名字零立体边框（border-width 0）`, p.nameBorderZero, '');
  ok(`${tag} A② 名字零 box-shadow（立体感退役）`, p.nameShadowNone, p.nameShadowNone ? 'none' : 'shadow!');
  ok(`${tag} A② 名字自带 backdrop-filter blur（胶囊玻璃）`, p.nameBackdropBlur, p.nameBackdropBlur ? 'blur' : 'none');
  ok(`${tag} A② 名字 999px 胶囊圆角`, p.nameRadius === '999px', p.nameRadius);
}

// ── 手机腿：双引擎 × 双主题（4 跑）─────────────────────────────────────
for (const engineKind of ['chromium', 'webkit']) {
  for (const scheme of ['light', 'dark']) {
    await runMobile(engineKind, scheme);
  }
}

// ── 桌面回归腿（1280×800 双引擎 × 双主题）：#header 头像行原样 + 取景同腿 ──
for (const engineKind of ['chromium', 'webkit']) {
  for (const scheme of ['light', 'dark']) {
    const tag = `desktop-${engineKind}-${scheme}`;
    const errors = [];
    const launcher = engineKind === 'chromium' ? chromium : webkit;
    const browser = await launcher.launch();
    try {
      const page = await browser.newPage({ viewport: { width: 1280, height: 800 }, colorScheme: scheme });
      page.on('pageerror', (e) => errors.push(`pageerror: ${e.message}`));
      page.on('console', (m) => { if (m.type() === 'error') errors.push(`console: ${m.text()}`); });
      await page.addInitScript(() => {
        for (const ns of ['stand', 'nebflow']) {
          localStorage.setItem(`${ns}_token`, 'e2e-token');
          localStorage.setItem(`${ns}_locale`, 'zh-CN');
        }
        Object.defineProperty(navigator.serviceWorker, 'register', { value: () => Promise.resolve(), configurable: true });
        localStorage.setItem('fm_api_mock', '1');
        localStorage.setItem('fm_api_mock_seed', JSON.stringify({
          self: { userId: 'me', neblinkId: 'me@example.com', name: 'Me', avatarUrl: '' },
          users: [], friends: [], incoming: [], outgoing: [], conversations: [], messages: {},
        }));
      });
      await page.route('**/*', (route) => {
        const url = new URL(route.request().url());
        let p = decodeURIComponent(url.pathname);
        if (p === '/') p = '/index.html';
        if (p.startsWith('/api/') || p.startsWith('/agents/')) {
          if (p === '/api/sessions') return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ sessions: [{ id: 'muse-root-session', name: 'root', agentName: 'Nebula' }], activeId: 'muse-root-session' }) });
          if (p === '/api/neblink/status') return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ loggedIn: true, device: { id: 'd1', name: '本机', platform: 'macos', userDescription: '', avatarUrl: '', email: 'me@example.com', displayName: '测试用户' }, peers: [] }) });
          return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
        }
        const file = normalize(join(WEB, p));
        if (!file.startsWith(WEB)) return route.fulfill({ status: 403, body: 'forbidden' });
        try { return route.fulfill({ status: 200, contentType: MIME[extname(file)] || 'application/octet-stream', body: readFileSync(file) }); }
        catch { return route.fulfill({ status: 404, body: 'not found' }); }
      });
      await page.routeWebSocket(/\/ws/, (ws) => {
        const sendConfig = () => ws.send(JSON.stringify({ type: 'configData', config: '{"features":{"friends":false}}', configured: true, onboarding: 'done' }));
        const sendSessions = () => ws.send(JSON.stringify({ type: 'sessionList', sessions: [{ id: 'muse-root-session', name: 'root', agentName: 'Nebula' }], folders: [], activeId: 'muse-root-session' }));
        sendConfig(); sendSessions();
        ws.onMessage((raw) => {
          let parsed; try { parsed = JSON.parse(raw); } catch { return; }
          if (parsed.type === 'getConfig') sendConfig();
          else if (parsed.type === 'getSessions' || parsed.type === 'listSessions') sendSessions();
          else if (parsed.type === 'getHistory') ws.send(JSON.stringify({ type: 'historyPage', sessionId: parsed.sessionId, messages: HISTORY.slice(0, 4), hasMore: false, offset: 0, total: 4 }));
        });
      });
      await page.goto('http://localhost:59999/', { timeout: 90000 });
      await page.waitForFunction(async (rootSid) => {
        const s = (await import('/js/state.js')).default;
        return s.activeSessionId === rootSid && s.ws && s.ws.readyState === 1;
      }, 'muse-root-session', { timeout: 20000 });
      await page.waitForFunction(() => document.querySelectorAll('#chat .row.ai').length >= 2, { timeout: 20000 });
      await page.waitForFunction(() => {
        const logo = document.querySelector('#agent-avatar-btn .agent-avatar-stand-logo img');
        return !!logo && logo.complete && logo.naturalWidth > 0;
      }, { timeout: 20000 });
      await page.waitForTimeout(600);

      const d = await page.evaluate(() => {
        const header = document.getElementById('header');
        const center = header?.querySelector('.header-center');
        const slot = document.getElementById('agent-avatar-btn');
        const slotRect = slot?.getBoundingClientRect();
        const nameEl = document.getElementById('session-name');
        return {
          mobileLayer: document.body.classList.contains('mobile-layer'),
          headerShown: !!header && getComputedStyle(header).display !== 'none',
          slotInHeader: !!slot && !!center && center.contains(slot),
          nameInHeader: !!nameEl && !!center && center.contains(nameEl),
          slotVisible: !!slotRect && slotRect.width > 20 && slotRect.height > 20,
          slotLogoPainted: (() => { const img = slot?.querySelector('.agent-avatar-stand-logo img'); return !!img && img.complete && img.naturalWidth > 0; })(),
          noChatHead: !document.querySelector('#chat > .g2-chat-head'),
          metas: document.querySelectorAll('#chat .g2-msg-meta').length,
          aiRows: document.querySelectorAll('#chat .row.ai').length,
        };
      });
      ok(`${tag} A③ #header 头像行原样（header-center 含头像+名字，可见可绘）`,
        !d.mobileLayer && d.headerShown && d.slotInHeader && d.nameInHeader && d.slotVisible && d.slotLogoPainted,
        `inHeader=${d.slotInHeader} name=${d.nameInHeader} painted=${d.slotLogoPainted}`);
      ok(`${tag} A③ 零悬浮锚 + 零身份头（手机层装饰桌面零感知）`,
        d.noChatHead && d.metas === 0 && d.aiRows > 0, `head=${d.noChatHead} metas=${d.metas}`);

      // 取景腿（桌面同一生产路径）。
      await mountCropSection(page);
      await page.evaluate(() => document.getElementById('crop-spec-host').scrollIntoView({ block: 'center' }));
      await page.waitForTimeout(400);
      const crop = await probeCropSection(page);
      const desktopModeOk = (k) => crop[k].mode === 'computed' || crop[k].mode === 'cache-hit';
      ok(`${tag} B① 恶意样本取景落上部（y ≤ 40）`,
        desktopModeOk('ui-screenshot') && crop['ui-screenshot'].y >= 0 && crop['ui-screenshot'].y <= 40,
        `mode=${crop['ui-screenshot'].mode} pos=${crop['ui-screenshot'].pos}`);
      ok(`${tag} B② 人像不 slam 到边（x,y ∈ [10,90]）`,
        desktopModeOk('portrait') && crop.portrait.x >= 10 && crop.portrait.x <= 90 && crop.portrait.y >= 10 && crop.portrait.y <= 90,
        `pos=${crop.portrait.pos}`);
      await page.evaluate(() => document.getElementById('crop-spec-host').scrollIntoView({ block: 'center' }));
      await page.waitForTimeout(300);
      await page.screenshot({ path: join(OUT, `${tag}.png`) });
      ok(`${tag} R0 零 console error`, errors.length === 0, errors.slice(0, 3).join(' | '));
      await page.close();
    } finally {
      await browser.close();
    }
  }
}

console.log(`═══ muse-restore-smartcrop-shots: ${failures === 0 ? 'ALL PASS' : failures + ' FAIL'} ═══`);
if (failures > 0) process.exit(1);

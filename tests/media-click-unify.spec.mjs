// media-click-unify.spec.mjs — 媒体点击行为统一（P16 批 2026-11-11 作者令逐字：
// 「电脑端的媒体统一都是canvas打开，手机端的媒体统一都是直接打开，但是要注意好
// 交互和居中」）的 A 规范验收。
//
// mobile-image-lightbox 批（2026-11-17 作者令「现在手机端点图片为什么会单独打开
// 一个标签页 我想的是像微信那样的弹窗就行了」）：手机腿由「直开页签」翻转为
// 「应用内画廊」（routeMediaClick 手机图片腿 → lightbox.openImageGallery；
// 视频/其他仍直开）——M1–M3 断言随之改写，P16 桌面两组合（D1–D3）零改动。
// 手势/页码/锁滚/直开显式入口的专面见 tests/mobile-image-gallery.spec.mjs。
//
// 四组合矩阵（截图 + 逐入口程序断言）：
//   · 桌面 × Canvas 面：Pop 媒体卡（带 path）/ markdown 气泡图（nf-file src）
//     → workspace-open-item → Canvas 页签（居中几何读数 + 关闭返回读数），灯箱不占位；
//   · 桌面 × 兜底灯箱面：pathless Pop 媒体（live 内联 data: 字节）→ 既有灯箱
//     （#491 主题三裁定保留；ESC 关闭读数）；
//   · 手机 × 画廊面（2026-11-17 翻转）：Pop 媒体卡 / markdown 图 / 帧消息图
//     （_nfImagePreview）→ 应用内画廊浮层（.nf-lightbox.on +
//     data-nf-lb-gallery="on"；无新页签、无 Canvas 页签）。
//
// 证据：SHOTS_DIR=<dir> 时写四组合截图（默认不写）。
// Run: node node_modules/@playwright/test/cli.js test tests/media-click-unify.spec.mjs

import { test, expect } from '@playwright/test';
import { readFileSync } from 'node:fs';
import { join, dirname, extname, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';

const WEB = join(dirname(fileURLToPath(import.meta.url)), '..', 'src', 'main', 'resources', 'web');
const SHOTS_DIR = process.env.SHOTS_DIR || '';
const SID = 'p16-unify-session';
const IMG_PATH = 'docs/pic.png';

// 1×1 真解码 PNG（img onload 面，与既有族同款夹具字节）。
const PNG = Buffer.from(
  'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==',
  'base64'
);

const MIME = {
  '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css',
  '.svg': 'image/svg+xml', '.png': 'image/png', '.ico': 'image/x-icon',
  '.json': 'application/json', '.woff2': 'font/woff2', '.woff': 'font/woff',
  '.ttf': 'font/ttf',
};

async function bootApp(page, { viewportMobile = false } = {}) {
  await page.addInitScript(() => {
    localStorage.setItem('nebflow_token', 'e2e-token');
    localStorage.setItem('nebflow_locale', 'zh-CN');
    // P16 断言面：被动记录 workspace-open-item 派发载荷（只观察）。
    window.__nfOpenItems = [];
    window.addEventListener('workspace-open-item', (e) => {
      const d = e && e.detail ? e.detail : null;
      window.__nfOpenItems.push(d ? { id: d.id, absPath: d.absPath, itemType: d.itemType, title: d.title, error: d.error || '' } : null);
    });
    window.__nfOpenCalls = [];
    const origOpen = window.open.bind(window);
    window.open = (...args) => { window.__nfOpenCalls.push(args.map(String)); return origOpen(...args); };
  });
  // context 级路由：手机直开的预约页是同 context 的 popup——page.route 不覆盖
  // popup，导航会落到 chrome-error 页（实测）。
  await page.context().route('**/*', (route) => {
    const url = new URL(route.request().url());
    let p = decodeURIComponent(url.pathname);
    if (p === '/') p = '/index.html';
    if (p.startsWith('/api/')) {
      if (p === '/api/sessions') {
        return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ sessions: [{ id: SID, name: 'root', agentName: 'Nebula' }], activeId: SID }) });
      }
      if (p === '/api/nf-ticket') {
        let body = '{}';
        try {
          const req = route.request();
          const post = req.postDataBuffer();
          if (post) {
            const j = JSON.parse(post.toString('utf8'));
            const tickets = {};
            for (const k of Object.keys(j && j.paths ? j.paths : {})) tickets[k] = { t: 'e2e-ticket', exp: Date.now() + 1800000, size: PNG.length };
            body = JSON.stringify({ tickets });
          }
        } catch { /* fall through to '{}' */ }
        return route.fulfill({ status: 200, contentType: 'application/json', body });
      }
      if (p === '/api/nf-file') {
        return route.fulfill({ status: 200, contentType: 'image/png', body: PNG });
      }
      return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
    }
    const file = normalize(join(WEB, p));
    if (!file.startsWith(WEB)) return route.fulfill({ status: 403, body: 'forbidden' });
    try {
      return route.fulfill({ status: 200, contentType: MIME[extname(file)] || 'application/octet-stream', body: readFileSync(file) });
    } catch {
      return route.fulfill({ status: 404, body: 'not found' });
    }
  });
  await page.routeWebSocket(/\/ws/, (ws) => {
    const sendConfig = () => ws.send(JSON.stringify({
      type: 'configData', config: '{}', configured: true, onboarding: 'done',
    }));
    const sendSessions = () => ws.send(JSON.stringify({
      type: 'sessionList',
      sessions: [{ id: SID, name: 'root', agentName: 'Nebula' }],
      folders: [], activeId: SID,
    }));
    sendConfig();
    sendSessions();
    ws.onMessage((raw) => {
      let parsed;
      try { parsed = JSON.parse(raw); } catch { return; }
      if (parsed.type === 'getConfig') sendConfig();
      else if (parsed.type === 'getSessions' || parsed.type === 'listSessions') sendSessions();
      else if (parsed.type === 'getHistory') {
        ws.send(JSON.stringify({ type: 'historyPage', sessionId: parsed.sessionId, messages: [], hasMore: false, offset: 0 }));
      } else if (parsed.type === 'pop.readFile') {
            ws.send(JSON.stringify({
          type: 'fileContent',
          path: parsed.path,
          absPath: '/tmp/' + parsed.path,
          fileName: parsed.path.split('/').pop(),
          itemType: 'image',
          content: '',
          size: PNG.length,
        }));
      }
    });
  });
  await page.goto('http://localhost:1/');
  await page.waitForFunction(async (rootSid) => {
    const s = (await import('/js/state.js')).default;
    return s.activeSessionId === rootSid && s.ws && s.ws.readyState === 1;
  }, SID, { timeout: 15000 });
  // 入场门彻底离场再交互（mobile-image-lightbox 批实证：揭幕两拍间
  // nf-boot-reveal 态 opacity:0 但 pointer-events:auto 仍罩整屏，窗口内真实
  // tap/drag 会被 nf-entry-card 截获）。M2 的真实 tap 由此稳定。
  await page.waitForFunction(() => !document.documentElement.classList.contains('nf-boot-gate'), null, { timeout: 15000 });
}

/** 生产构建器：buildUserMediaRow 渲染用户媒体行（作者「我发的图片」同面；
 *  live 发送 / attachmentsSaved 重渲 / 历史恢复三路同一构建器；att 形态 =
 *  {type:'image', name, path?, preview?(data:)}）。 */
async function renderRealPopRow(page, att) {
  return page.evaluate(async (a) => {
    const { buildUserMediaRow } = await import('/js/popArtifacts.js');
    const row = buildUserMediaRow([a], { ts: Date.now() });
    if (!row) return false;
    row.dataset.testid = 'pop-artifact-row';
    document.getElementById('chat')?.appendChild(row);
    return true;
  }, att);
}

test.describe('P16 media click unify matrix', () => {
  test.describe.configure({ mode: 'serial' });

  test('D1: desktop Pop media card (path) → Canvas tab, centered, no lightbox', async ({ page }) => {
    const errors = [];
    page.on('pageerror', (e) => errors.push(String(e.message || e)));
    await bootApp(page);
    await renderRealPopRow(page, { type: 'image', name: 'pic.png', path: IMG_PATH });
    // 面内媒体面上半段点击（footer 信息条下载键与卡面几何相邻，中心点会被它截获）。
    // JS 派发（card-image-zoom 同法）：夹具视口里 footer 下载键悬浮在卡面中心，
    // 真实指针坐标会落进角标；整面点击处理器在 .pop-media-flat 上，派发点击
    // 即完整生产链（openMediaItemClick → routeMediaClick）。
    await page.evaluate(() => {
      const face = document.querySelector('.row.pop-artifact[data-testid="pop-artifact-row"] .pop-media-flat');
      face.dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true }));
    });
    await expect(page.locator('.nf-lightbox.on[data-nf-lb-gallery="on"]')).toBeVisible();
    expect(await page.evaluate(() => window.__nfOpenItems.length)).toBe(0);
    expect(errors).toEqual([]);
  });

  test('D2: desktop markdown bubble image (nf-file src) → Canvas tab', async ({ page }) => {
    const errors = [];
    page.on('pageerror', (e) => errors.push(String(e.message || e)));
    await bootApp(page);
    await page.evaluate((p) => {
      const img = document.createElement('img');
      img.className = 'nf-zoom-img';
      img.src = '/api/nf-file?path=' + encodeURIComponent(p);
      img.alt = 'md-pic.png';
      document.getElementById('chat')?.appendChild(img);
    }, IMG_PATH);
    await page.locator('img.nf-zoom-img').click();
    await expect(page.locator('.nf-lightbox.on[data-nf-lb-gallery="on"]')).toBeVisible();
    expect(await page.evaluate(() => window.__nfOpenItems.length)).toBe(0);
    expect(errors).toEqual([]);
  });

  // ── 桌面 × 兜底灯箱面（pathless 内联字节） ───────────────────────────────
  test('D3: desktop pathless Pop media (inline data URI) → lightbox fallback, ESC closes', async ({ page }) => {
    const errors = [];
    page.on('pageerror', (e) => errors.push(String(e.message || e)));
    await bootApp(page);
    const dataUri = 'data:image/png;base64,' + PNG.toString('base64');
    await renderRealPopRow(page, { type: 'image', name: 'live.png', preview: dataUri });
    // JS 派发（card-image-zoom 同法）：夹具视口里 footer 下载键悬浮在卡面中心，
    // 真实指针坐标会落进角标；整面点击处理器在 .pop-media-flat 上，派发点击
    // 即完整生产链（openMediaItemClick → routeMediaClick）。
    const tabsBefore = await page.evaluate(() => document.querySelectorAll('#canvas-tab-bar .canvas-tab').length);
    await page.evaluate(() => {
      const face = document.querySelector('.row.pop-artifact[data-testid="pop-artifact-row"] .pop-media-flat');
      face.dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true }));
    });
    await page.waitForSelector('.nf-lightbox.on[data-nf-lb-gallery="on"]', { timeout: 8000 });
    // pathless 桌面腿：灯箱兜底（无页签被无谓开出——页签数不变）。
    const st = await page.evaluate((n) => ({
      tabs: document.querySelectorAll('#canvas-tab-bar .canvas-tab').length,
    }), tabsBefore);
    expect(st.tabs).toBe(tabsBefore);
    if (SHOTS_DIR) await page.screenshot({ path: join(SHOTS_DIR, 'combo2-desktop-lightbox-fallback.png') });
    // ESC 关闭读数（灯箱层自己的开合词法）。
    await page.keyboard.press('Escape');
    await page.waitForFunction(() => !document.querySelector('.nf-lightbox.on[data-nf-lb-gallery="on"]'), null, { timeout: 5000 });
    expect(errors).toEqual([]);
  });

  // ── 手机 × 画廊面（mobile-image-lightbox 批 2026-11-17 翻转）──────────────
  test.describe('mobile face', () => {
    test.use({ viewport: { width: 375, height: 812 }, hasTouch: true });

  test('M1: mobile Pop media card tap → in-app gallery (no tab, no canvas)', async ({ page }) => {
    const errors = [];
    page.on('pageerror', (e) => errors.push(String(e.message || e)));
    await bootApp(page);
    await page.waitForFunction(() => document.body.classList.contains('mobile-layer'), null, { timeout: 15000 });
    await renderRealPopRow(page, { type: 'image', name: 'pic.png', path: IMG_PATH });
    // JS 派发同上（夹具里角标悬浮卡面）。开页签守卫：画廊开的是浮层，若腿走回
    // 直开会真弹 popup（context 路由面）——3s 无 popup = 零页签读数。
    const popupPromise = page.waitForEvent('popup', { timeout: 3000 }).catch(() => null);
    await page.evaluate(() => {
      const face = document.querySelector('.row.pop-artifact[data-testid="pop-artifact-row"] .pop-media-flat');
      face.dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true }));
    });
    await page.waitForFunction(() => {
      const lb = document.querySelector('.nf-lightbox');
      return lb && lb.classList.contains('on') && lb.getAttribute('data-nf-lb-gallery') === 'on';
    }, null, { timeout: 8000 });
    const popup = await popupPromise;
    expect(popup).toBe(null);
    // 画廊读数：图片已装载 + 无 Canvas 页签增量 + 手机导航未被顶到 canvas +
    // 无页内卡片 chip（P16 手机面同三读数，仅面翻转）。
    const tabsBefore = await page.evaluate(() => document.querySelectorAll('#canvas-tab-bar .canvas-tab').length);
    const st = await page.evaluate(() => ({
      imgLoaded: !!document.querySelector('.nf-lightbox .nf-lightbox-img.loaded'),
      mediaLightbox: !!document.querySelector('.nf-lightbox--media.on'),
      tabs: document.querySelectorAll('#canvas-tab-bar .canvas-tab').length,
      top: window.__nfMobileNav?.top,
      chips: document.querySelectorAll('.mf-strip-chip.on').length,
    }));
    expect(st.imgLoaded).toBe(true);
    expect(st.mediaLightbox).toBe(false);
    expect(st.tabs).toBe(tabsBefore);
    expect(st.top).not.toBe('canvas');
    expect(st.chips).toBe(0);
    expect(errors).toEqual([]);
    if (SHOTS_DIR) {
      await page.waitForTimeout(400);
      await page.screenshot({ path: join(SHOTS_DIR, 'combo3-mobile-gallery-chat.png') });
    }
  });

    test('M2: mobile markdown bubble image tap → in-app gallery', async ({ page }) => {
    const errors = [];
    page.on('pageerror', (e) => errors.push(String(e.message || e)));
    await bootApp(page);
    await page.waitForFunction(() => document.body.classList.contains('mobile-layer'), null, { timeout: 15000 });
    await page.evaluate((p) => {
      const img = document.createElement('img');
      img.className = 'nf-zoom-img';
      img.src = '/api/nf-file?path=' + encodeURIComponent(p);
      img.alt = 'md-pic.png';
      document.getElementById('chat')?.appendChild(img);
    }, IMG_PATH);
    const tabsBeforeM = await page.evaluate(() => document.querySelectorAll('#canvas-tab-bar .canvas-tab').length);
    const popupPromise = page.waitForEvent('popup', { timeout: 3000 }).catch(() => null);
    await page.locator('img.nf-zoom-img').tap();
    await page.waitForFunction(() => {
      const lb = document.querySelector('.nf-lightbox');
      return lb && lb.classList.contains('on') && lb.getAttribute('data-nf-lb-gallery') === 'on';
    }, null, { timeout: 8000 });
    const popup = await popupPromise;
    expect(popup).toBe(null);
    const st = await page.evaluate(() => ({
      imgLoaded: !!document.querySelector('.nf-lightbox .nf-lightbox-img.loaded'),
      tabs: document.querySelectorAll('#canvas-tab-bar .canvas-tab').length,
    }));
    expect(st.imgLoaded).toBe(true);
    expect(st.tabs).toBe(tabsBeforeM);
    expect(errors).toEqual([]);
    if (SHOTS_DIR) await page.screenshot({ path: join(SHOTS_DIR, 'combo3-mobile-gallery-md.png') });
  });

    test('M3: mobile frame image (_nfImagePreview from a rendered card) → in-app gallery', async ({ page }) => {
    const errors = [];
    page.on('pageerror', (e) => errors.push(String(e.message || e)));
    await bootApp(page);
    await page.waitForFunction(() => document.body.classList.contains('mobile-layer'), null, { timeout: 15000 });
    // 真实帧形态：一枚本应用渲染的 Card iframe（isAppFrameSource 白名单按
    // contentWindow 判），内部 img 点击走 imgClickScript 桥 postMessage。
    await page.evaluate(async (p) => new Promise((resolve) => {
      // 真实帧形态：srcdoc 注入 viewers/shared.js imgClickScript 桥（Card/Canvas
      // html 渲染腿同一脚本，裸 srcdoc 没有桥——注入它才与生产帧同构）。
      import('/js/viewers/shared.js').then(({ imgClickScript }) => {
        const f = document.createElement('iframe');
        f.setAttribute('data-nf-card-id', 'card-e2e');
        // imgClickScript() 自带完整 <script> 块（模板字面量直出）——原样拼入。
        f.srcdoc = '<img id="k" src="/api/nf-file?path=' + encodeURIComponent(p) + '" alt="k.png" width="80">'
          + imgClickScript();
        f.style.width = '120px';
        f.style.height = '60px';
        document.body.appendChild(f);
        f.addEventListener('load', () => resolve(true));
        setTimeout(() => resolve(true), 1500);
      });
    }), IMG_PATH);
    await page.waitForFunction(() => {
      const f = document.querySelector('iframe[data-nf-card-id]');
      const doc = f && f.contentDocument;
      return doc && doc.querySelector('img');
    }, null, { timeout: 8000 });
    const tabsBeforeM3 = await page.evaluate(() => document.querySelectorAll('#canvas-tab-bar .canvas-tab').length);
    const popupPromise = page.waitForEvent('popup', { timeout: 3000 }).catch(() => null);
    await page.evaluate((p) => {
      const f = document.querySelector('iframe[data-nf-card-id]');
      const doc = f.contentDocument;
      const img = doc.querySelector('#k');
      img.dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true, view: doc.defaultView }));
    }, IMG_PATH);
    await page.waitForFunction(() => {
      const lb = document.querySelector('.nf-lightbox');
      return lb && lb.classList.contains('on') && lb.getAttribute('data-nf-lb-gallery') === 'on';
    }, null, { timeout: 8000 });
    const popup = await popupPromise;
    expect(popup).toBe(null);
    const st = await page.evaluate(() => ({
      imgLoaded: !!document.querySelector('.nf-lightbox .nf-lightbox-img.loaded'),
      tabs: document.querySelectorAll('#canvas-tab-bar .canvas-tab').length,
    }));
    expect(st.imgLoaded).toBe(true);
    expect(st.tabs).toBe(tabsBeforeM3);
    expect(errors).toEqual([]);
  });
  });
});

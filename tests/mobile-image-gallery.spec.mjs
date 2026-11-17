// mobile-image-gallery.spec.mjs — 手机层图片画廊（mobile-image-lightbox 批
// 2026-11-17 作者令逐字：「现在手机端点图片为什么会单独打开一个标签页 我想的是
// 像微信那样的弹窗就行了」）的验收面。
//
// 覆盖（真 DOM 手机视口 + Playwright 自建路由，不碰任何真实实例端口）：
//   · G1 叠卡图组点开 → 应用内画廊（遮罩 + 居中大图 + 页码 n / m + 「在标签页
//     打开」显式入口 + 返回钮 .mfs-back + 页体锁滚），不开新页签；
//   · G2 横滑跟手切图（页码/序号读数推进；组端橡皮筋不越界）；
//   · G3 下滑关闭 + 页体滚动位保持（锁滚不吞 scrollTop）；
//   · G4 单图打开 → 无页码（组内 ≥2 才显示）；
//   · G5 「在标签页打开」→ 预约式直开铸票 URL（原能力显式保留，P16 腿原样）；
//   · G6 返回钮 .mfs-back → 关闭画廊；
//   · V1 视频点按 → 仍直开（新页签），画廊只吃图片（作者令「图片」面）；
//   · D0 桌面对照：同夹具同入口在桌面视口 → Canvas 页签（P16 桌面腿逐字不变，
//     画廊 chrome 节点在桌面 DOM 不出现）。
//
// 与 media-click-unify.spec.mjs 的关系：那边的 M1–M3 已翻转为画廊断言（手机×
// 直开面 → 手机×画廊面的矩阵改写），本 spec 补手势/页码/锁滚/返回/直开入口的
// 画廊专面；D1–D3 桌面断言零改动继续在跑 = 桌面零变化的逐字证据。
//
// 证据：SHOTS_DIR=<dir> 时写各态截图（默认不写）；录屏 = test-results 下的
// video.webm（describe 级 video:'on'）。
// Run: node node_modules/@playwright/test/cli.js test tests/mobile-image-gallery.spec.mjs

import { test, expect } from '@playwright/test';
import { readFileSync } from 'node:fs';
import { join, dirname, extname, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';

const WEB = join(dirname(fileURLToPath(import.meta.url)), '..', 'src', 'main', 'resources', 'web');
const SHOTS_DIR = process.env.SHOTS_DIR || '';
const SID = 'mil-gallery-session';
const IMG_PATH = 'docs/pic.png';

// 1×1 真解码 PNG（img onload 面，与 media-click-unify 族同款夹具字节）。
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

async function bootApp(page) {
  await page.addInitScript(() => {
    localStorage.setItem('nebflow_token', 'e2e-token');
    localStorage.setItem('nebflow_locale', 'zh-CN');
    window.__nfOpenItems = [];
    window.addEventListener('workspace-open-item', (e) => {
      const d = e && e.detail ? e.detail : null;
      window.__nfOpenItems.push(d ? { id: d.id, absPath: d.absPath, itemType: d.itemType, title: d.title, error: d.error || '' } : null);
    });
    window.__nfOpenCalls = [];
    const origOpen = window.open.bind(window);
    window.open = (...args) => { window.__nfOpenCalls.push(args.map(String)); return origOpen(...args); };
  });
  // context 级路由：直开的预约页是同 context popup——page.route 覆盖不到。
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
  // 真实指针面（tap/drag）要求入场门彻底离场：揭幕腿是两拍——先加
  // nf-boot-reveal（opacity:0 但 pointer-events:auto 仍罩整屏），220ms 淡出后
  // 才摘 nf-boot-gate 类。窗口内起手会被门卡接住（本批探针实证
  // nf-entry-card 截获 pointerdown ⇒ 画廊手势全灭）。类摘除 = 门真正让位。
  await page.waitForFunction(() => !document.documentElement.classList.contains('nf-boot-gate'), null, { timeout: 15000 });
}

/** 生产构建器：buildUserMediaRow（live 发送 / attachmentsSaved 重渲 / 历史恢复
 *  三路同一构建器）。多附件 → 叠卡图组（buildMediaStack，整组进画廊）。 */
async function renderRealPopRow(page, atts) {
  const list = Array.isArray(atts) ? atts : [atts];
  return page.evaluate(async (a) => {
    const { buildUserMediaRow } = await import('/js/popArtifacts.js');
    const row = buildUserMediaRow(a, { ts: Date.now() });
    if (!row) return false;
    row.dataset.testid = 'pop-artifact-row';
    document.getElementById('chat')?.appendChild(row);
    return true;
  }, list);
}

/** 画廊态读数（一处收口，各断言面共用）。滚动读数取 #chat——手机层会话滚动
 *  容器就是它自己（chat.css `#chat{overflow-y:auto}`，手机层无覆盖；
 *  .mobile-page-body 是设置/fm 等其余页的滚动容器，锁规则同批覆盖）。 */
function galState(page) {
  return page.evaluate(() => {
    const lb = document.querySelector('.nf-lightbox');
    const pager = document.querySelector('.nf-lightbox .nf-lightbox-pager');
    const scroller = document.getElementById('chat');
    return {
      on: !!(lb && lb.classList.contains('on')),
      mode: lb ? lb.getAttribute('data-nf-lb-mode') : null,
      gallery: lb ? lb.getAttribute('data-nf-lb-gallery') : null,
      index: lb ? lb.getAttribute('data-nf-lb-gal-index') : null,
      total: lb ? lb.getAttribute('data-nf-lb-gal-total') : null,
      pagerText: pager ? pager.textContent.trim() : '',
      pagerShown: !!pager && pager.style.display !== 'none',
      opentab: !!document.querySelector('.nf-lightbox .nf-lightbox-opentab'),
      back: !!document.querySelector('.nf-lightbox > .mfs-back'),
      imgLoaded: !!document.querySelector('.nf-lightbox .nf-lightbox-img.loaded'),
      bodyLock: document.body.classList.contains('nf-lightbox-open'),
      scrollLocked: scroller ? getComputedStyle(scroller).overflow : null,
      scrollTop: scroller ? scroller.scrollTop : -1,
      openCalls: (window.__nfOpenCalls || []).length,
    };
  });
}

/** 鼠标拖动（Playwright mouse 面派发对应 pointer 事件——bindGalleryGestures
 *  的 pointerdown/move/up 消费面）。分步 move 产生跟手帧。 */
async function drag(page, x, y, dx, dy, steps = 8) {
  await page.mouse.move(x, y);
  await page.mouse.down();
  for (let i = 1; i <= steps; i++) {
    await page.mouse.move(x + (dx * i) / steps, y + (dy * i) / steps);
    await page.waitForTimeout(16);
  }
  await page.mouse.up();
}

// 录屏证据面：video 只能文件级 use（describe 级会强制新 worker，Playwright 收集
// 期即拒）——录全部用例（含 D0 桌面对照），落在 test-results/<用例>/video.webm。
test.use({ video: 'on' });

test.describe('mobile image gallery (WeChat-style overlay)', () => {
  test.describe.configure({ mode: 'serial' });
  test.use({ viewport: { width: 375, height: 812 }, hasTouch: true });

  test('G1: stack group tap → in-app gallery, pager 1 / 3, chrome + locks, no new tab', async ({ page }) => {
    const errors = [];
    page.on('pageerror', (e) => errors.push(String(e.message || e)));
    await bootApp(page);
    await page.waitForFunction(() => document.body.classList.contains('mobile-layer'), null, { timeout: 15000 });
    await renderRealPopRow(page, [
      { type: 'image', name: 'a.png', path: 'docs/a.png' },
      { type: 'image', name: 'b.png', path: 'docs/b.png' },
      { type: 'image', name: 'c.png', path: 'docs/c.png' },
    ]);
    // 折叠叠卡整面点击 = 开当前张（带整组）。
    await page.evaluate(() => {
      const face = document.querySelector('.row.pop-artifact[data-testid="pop-artifact-row"] .pop-stack-view');
      face.dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true }));
    });
    await page.waitForFunction(() => {
      const lb = document.querySelector('.nf-lightbox');
      return lb && lb.classList.contains('on') && lb.querySelector('.nf-lightbox-img.loaded');
    }, null, { timeout: 8000 });
    const st = await galState(page);
    // 画廊面：遮罩浮层开、图片模式、页码 1 / 3、序号读数、chrome 两枚、锁滚两道。
    expect(st.on).toBe(true);
    expect(st.mode).toBe('img');
    expect(st.gallery).toBe('on');
    expect(st.index).toBe('0');
    expect(st.total).toBe('3');
    expect(st.pagerText).toBe('1 / 3');
    expect(st.pagerShown).toBe(true);
    expect(st.opentab).toBe(true);
    expect(st.back).toBe(true);
    expect(st.imgLoaded).toBe(true);
    expect(st.bodyLock).toBe(true);
    expect(st.scrollLocked).toBe('hidden');
    // 关键行为面：不开新页签（P16 直开在新批里只保留为显式入口，见 G5）。
    expect(st.openCalls).toBe(0);
    expect(errors).toEqual([]);
    if (SHOTS_DIR) {
      await page.waitForTimeout(400);
      await page.screenshot({ path: join(SHOTS_DIR, 'gal-1-open-pager.png') });
    }
  });

  test('G2: horizontal drag advances the page; rubber band holds at group ends', async ({ page }) => {
    const errors = [];
    page.on('pageerror', (e) => errors.push(String(e.message || e)));
    await bootApp(page);
    await page.waitForFunction(() => document.body.classList.contains('mobile-layer'), null, { timeout: 15000 });
    await renderRealPopRow(page, [
      { type: 'image', name: 'a.png', path: 'docs/a.png' },
      { type: 'image', name: 'b.png', path: 'docs/b.png' },
      { type: 'image', name: 'c.png', path: 'docs/c.png' },
    ]);
    await page.evaluate(() => {
      const face = document.querySelector('.row.pop-artifact[data-testid="pop-artifact-row"] .pop-stack-view');
      face.dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true }));
    });
    await page.waitForFunction(() => {
      const lb = document.querySelector('.nf-lightbox');
      return lb && lb.classList.contains('on') && lb.querySelector('.nf-lightbox-img.loaded');
    }, null, { timeout: 8000 });
    // 左滑（dx<0）→ 下一张：1 / 3 → 2 / 3。
    await drag(page, 187, 400, -160, 0);
    await page.waitForFunction(() => document.querySelector('.nf-lightbox')?.getAttribute('data-nf-lb-gal-index') === '1', null, { timeout: 5000 });
    let st = await galState(page);
    expect(st.pagerText).toBe('2 / 3');
    // 再左滑 → 末张 3 / 3。
    await drag(page, 187, 400, -160, 0);
    await page.waitForFunction(() => document.querySelector('.nf-lightbox')?.getAttribute('data-nf-lb-gal-index') === '2', null, { timeout: 5000 });
    st = await galState(page);
    expect(st.pagerText).toBe('3 / 3');
    // 组端橡皮筋：末张再左滑不过阈也不越界（页码停在 3 / 3）。
    await drag(page, 187, 400, -120, 0);
    await page.waitForTimeout(300);
    st = await galState(page);
    expect(st.index).toBe('2');
    expect(st.pagerText).toBe('3 / 3');
    expect(st.on).toBe(true);
    expect(errors).toEqual([]);
    if (SHOTS_DIR) {
      await page.waitForTimeout(400);
      await page.screenshot({ path: join(SHOTS_DIR, 'gal-2-swipe-pager.png') });
    }
  });

  test('G3: drag-down closes; .mobile-page-body scrollTop preserved, overflow restored', async ({ page }) => {
    const errors = [];
    page.on('pageerror', (e) => errors.push(String(e.message || e)));
    await bootApp(page);
    await page.waitForFunction(() => document.body.classList.contains('mobile-layer'), null, { timeout: 15000 });
    await renderRealPopRow(page, [
      { type: 'image', name: 'a.png', path: 'docs/a.png' },
      { type: 'image', name: 'b.png', path: 'docs/b.png' },
    ]);
    // 会话滚动容器 #chat 垫高：#chat 是 flex 列——spacer 不写 flex:none 会被
    // flex-shrink 压回视口高（垫不出 scrollHeight，本批探针实证）；且 chat 的
    // 「新内容滚到底」观察器会在垫入后抢滚到底（2751）——所以滚动位设在锁滚
    // 生效后（overflow:hidden 仍可程序设 scrollTop，观察器从此滚不动它），
    // 正好直测「锁滚期间滚动位不漂、关闭后原样」。
    await page.evaluate(() => {
      const sc = document.getElementById('chat');
      const spacer = document.createElement('div');
      spacer.style.cssText = 'height: 3000px; flex: none;';
      sc.appendChild(spacer);
    });
    await page.evaluate(() => {
      const face = document.querySelector('.row.pop-artifact[data-testid="pop-artifact-row"] .pop-stack-view');
      face.dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true }));
    });
    await page.waitForFunction(() => {
      const lb = document.querySelector('.nf-lightbox');
      return lb && lb.classList.contains('on') && lb.querySelector('.nf-lightbox-img.loaded');
    }, null, { timeout: 8000 });
    // 行渲染的「贴底」定时器链（persistence.js rAF/100ms/500ms 三拍抢滚到底）与
    // 进场落底武装窗（chat/settleBottom.js 1200ms 每帧钉底）排空/解除后再设
    // 900。武装窗的解除闸 = 用户意图事件（pointerdown/mousedown…）——真实用户
    // 点开画廊的那次按下天然解除；本夹具的点击是 JS 派发（无指针事件），须补
    // 一枚同语义的意图事件，滚动位从此只受锁滚规则管。
    await page.waitForTimeout(700);
    await page.evaluate(() => { document.dispatchEvent(new Event('pointerdown')); });
    await page.evaluate(() => { document.getElementById('chat').scrollTop = 900; });
    let st = await galState(page);
    expect(st.scrollLocked).toBe('hidden');
    expect(st.scrollTop).toBe(900);      // 锁滚不动滚动位
    if (SHOTS_DIR) await page.screenshot({ path: join(SHOTS_DIR, 'gal-3a-open-locked.png') });
    // 下滑跟手关闭（dy 过阈 110）。
    await drag(page, 187, 350, 0, 220);
    await page.waitForFunction(() => !document.querySelector('.nf-lightbox.on'), null, { timeout: 5000 });
    st = await galState(page);
    expect(st.on).toBe(false);
    expect(st.gallery).toBe(null);       // 画廊态属性一并收零
    expect(st.bodyLock).toBe(false);
    expect(st.scrollLocked).not.toBe('hidden');
    expect(st.scrollTop).toBe(900);      // 关闭回原滚动位
    expect(errors).toEqual([]);
    if (SHOTS_DIR) {
      await page.waitForTimeout(300);
      await page.screenshot({ path: join(SHOTS_DIR, 'gal-3b-closed-restored.png') });
    }
  });

  test('G4: single image open → pager hidden (group of 1)', async ({ page }) => {
    const errors = [];
    page.on('pageerror', (e) => errors.push(String(e.message || e)));
    await bootApp(page);
    await page.waitForFunction(() => document.body.classList.contains('mobile-layer'), null, { timeout: 15000 });
    await renderRealPopRow(page, { type: 'image', name: 'pic.png', path: IMG_PATH });
    await page.evaluate(() => {
      const face = document.querySelector('.row.pop-artifact[data-testid="pop-artifact-row"] .pop-media-flat');
      face.dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true }));
    });
    await page.waitForFunction(() => {
      const lb = document.querySelector('.nf-lightbox');
      return lb && lb.classList.contains('on') && lb.querySelector('.nf-lightbox-img.loaded');
    }, null, { timeout: 8000 });
    const st = await galState(page);
    expect(st.on).toBe(true);
    expect(st.gallery).toBe('on');
    expect(st.total).toBe('1');
    expect(st.pagerShown).toBe(false);   // 组内 ≥2 才显示页码
    expect(st.opentab).toBe(true);       // 单图也保留直开显式入口
    expect(st.openCalls).toBe(0);
    expect(errors).toEqual([]);
    if (SHOTS_DIR) {
      await page.waitForTimeout(400);
      await page.screenshot({ path: join(SHOTS_DIR, 'gal-4-single.png') });
    }
  });

  test('G5: explicit open-in-tab button → reserved direct-open with ticketed URL', async ({ page }) => {
    const errors = [];
    page.on('pageerror', (e) => errors.push(String(e.message || e)));
    await bootApp(page);
    await page.waitForFunction(() => document.body.classList.contains('mobile-layer'), null, { timeout: 15000 });
    await renderRealPopRow(page, { type: 'image', name: 'pic.png', path: IMG_PATH });
    await page.evaluate(() => {
      const face = document.querySelector('.row.pop-artifact[data-testid="pop-artifact-row"] .pop-media-flat');
      face.dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true }));
    });
    await page.waitForFunction(() => {
      const lb = document.querySelector('.nf-lightbox');
      return lb && lb.classList.contains('on') && lb.querySelector('.nf-lightbox-img.loaded');
    }, null, { timeout: 8000 });
    // 原能力显式入口：点「在标签页打开」→ 预约式 window.open → 铸票 nf-file URL。
    const popupPromise = page.waitForEvent('popup');
    await page.locator('.nf-lightbox .nf-lightbox-opentab').click();
    const popup = await popupPromise;
    let navOk = false;
    for (let i = 0; i < 40 && !navOk; i++) {
      await page.waitForTimeout(200);
      const u = popup.url();
      if (u.includes('/api/nf-file?path=')) navOk = true;
    }
    expect(navOk).toBe(true);
    // 画廊本体仍在（入口不打断浏览）。
    const st = await galState(page);
    expect(st.on).toBe(true);
    expect(errors).toEqual([]);
  });

  test('G6: injected .mfs-back button closes the gallery', async ({ page }) => {
    const errors = [];
    page.on('pageerror', (e) => errors.push(String(e.message || e)));
    await bootApp(page);
    await page.waitForFunction(() => document.body.classList.contains('mobile-layer'), null, { timeout: 15000 });
    await renderRealPopRow(page, { type: 'image', name: 'pic.png', path: IMG_PATH });
    await page.evaluate(() => {
      const face = document.querySelector('.row.pop-artifact[data-testid="pop-artifact-row"] .pop-media-flat');
      face.dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true }));
    });
    await page.waitForFunction(() => {
      const lb = document.querySelector('.nf-lightbox');
      return lb && lb.classList.contains('on');
    }, null, { timeout: 8000 });
    await page.locator('.nf-lightbox > .mfs-back').click();
    await page.waitForFunction(() => !document.querySelector('.nf-lightbox.on'), null, { timeout: 5000 });
    const st = await galState(page);
    expect(st.on).toBe(false);
    expect(st.bodyLock).toBe(false);
    expect(errors).toEqual([]);
  });

  test('V1: video tap stays direct-open (gallery only claims images)', async ({ page }) => {
    const errors = [];
    page.on('pageerror', (e) => errors.push(String(e.message || e)));
    await bootApp(page);
    await page.waitForFunction(() => document.body.classList.contains('mobile-layer'), null, { timeout: 15000 });
    await renderRealPopRow(page, { type: 'video', name: 'v.mp4', path: 'docs/v.mp4' });
    const popupPromise = page.waitForEvent('popup');
    await page.evaluate(() => {
      const face = document.querySelector('.row.pop-artifact[data-testid="pop-artifact-row"] .pop-media-flat');
      face.dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true }));
    });
    const popup = await popupPromise;
    let navOk = false;
    for (let i = 0; i < 40 && !navOk; i++) {
      await page.waitForTimeout(200);
      if (popup.url().includes('/api/nf-file?path=')) navOk = true;
    }
    expect(navOk).toBe(true);
    const st = await galState(page);
    expect(st.on).toBe(false);           // 视频不进画廊
    expect(st.gallery).toBe(null);
    expect(errors).toEqual([]);
  });
});

test.describe('desktop zero-change control (D0)', () => {
  test('D0: same fixture on desktop viewport → Canvas tab, no gallery chrome in DOM', async ({ page }) => {
    const errors = [];
    page.on('pageerror', (e) => errors.push(String(e.message || e)));
    await bootApp(page);
    await renderRealPopRow(page, { type: 'image', name: 'pic.png', path: IMG_PATH });
    const popupPromise = page.waitForEvent('popup', { timeout: 3000 }).catch(() => null);
    await page.evaluate(() => {
      const face = document.querySelector('.row.pop-artifact[data-testid="pop-artifact-row"] .pop-media-flat');
      face.dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true }));
    });
    // 桌面腿逐字不变：workspace-open-item（Canvas 页签）+ 无新页签 + 无画廊。
    await page.waitForFunction(() => (window.__nfOpenItems || []).length > 0, null, { timeout: 8000 });
    const popup = await popupPromise;
    expect(popup).toBe(null);            // 桌面不开页签（P16 语义原样）
    const st = await galState(page);
    expect(st.on).toBe(false);
    expect(st.gallery).toBe(null);
    // 画廊 chrome 惰性建：桌面 DOM 永不出现页码/开页签钮节点。
    expect(await page.evaluate(() => document.querySelector('.nf-lightbox-pager, .nf-lightbox-opentab'))).toBe(null);
    const items = await page.evaluate(() => window.__nfOpenItems);
    // 张数不进断言面（D1 同法）：workspace-open-item 的既有派发数非 1（开件/
    // 激活多拍），断末项载荷即可。
    expect(items.length).toBeGreaterThanOrEqual(1);
    const last = items[items.length - 1];
    // ws mock 回的 absPath 是 '/tmp/'+path，应用侧还会再并工作目录前缀——末段
    // 对上即可（路径拼合非本批断言面）。
    expect(String(last.absPath)).toContain(IMG_PATH);
    expect(last.itemType).toBe('image');
    expect(errors).toEqual([]);
    if (SHOTS_DIR) {
      await page.waitForTimeout(600);
      await page.screenshot({ path: join(SHOTS_DIR, 'd0-desktop-canvas-unchanged.png') });
    }
  });
});
